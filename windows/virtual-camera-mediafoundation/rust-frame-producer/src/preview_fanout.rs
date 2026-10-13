//! Authenticated loopback compressed preview. Socket writes happen only on
//! subscriber threads; the producer only clones bounded Arc packets/try_sends.
use crate::ocb2;
use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::mpsc::{sync_channel, Receiver, SyncSender, TrySendError};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

const MAX_CACHE_RECORDS: usize = 240;
const MAX_CACHE_BYTES: usize = 8 * 1024 * 1024;
const MAX_QUEUE_BYTES: usize = 4 * 1024 * 1024;
const MAX_METADATA_BYTES: usize = 64 * 1024;
const QUEUE_RECORDS: usize = 32;
const MAX_CLIENTS: usize = 4;
const MAX_CACHE_AGE: Duration = Duration::from_secs(2);
const MAX_QUEUE_AGE: Duration = Duration::from_millis(500);

fn queue_age(profile: &str) -> Duration {
    Duration::from_millis(match profile {
        "low" => 120,
        "smooth" => 500,
        _ => 250,
    })
}

fn encode(record: &ocb2::Record) -> Arc<[u8]> {
    let mut bytes = Vec::with_capacity(ocb2::HEADER_SIZE + record.payload.len());
    bytes.extend_from_slice(&ocb2::MAGIC);
    bytes.extend_from_slice(&ocb2::VERSION.to_le_bytes());
    bytes.extend_from_slice(&(ocb2::HEADER_SIZE as u16).to_le_bytes());
    bytes.extend_from_slice(&record.record_type.to_le_bytes());
    bytes.extend_from_slice(&0u16.to_le_bytes());
    bytes.extend_from_slice(&record.flags.to_le_bytes());
    bytes.extend_from_slice(&record.sequence.to_le_bytes());
    bytes.extend_from_slice(&record.capture_timestamp_ns.to_le_bytes());
    bytes.extend_from_slice(&record.encoder_timestamp_us.to_le_bytes());
    bytes.extend_from_slice(&(record.payload.len() as u32).to_le_bytes());
    bytes.extend_from_slice(&record.send_delta_us.to_le_bytes());
    bytes.extend_from_slice(&record.payload);
    bytes.into()
}

#[derive(Default)]
struct Cache {
    info: Option<Arc<[u8]>>,
    config: Option<Arc<[u8]>>,
    chain: Vec<Arc<[u8]>>,
    bytes: usize,
    key_at: Option<Instant>,
    format: Option<(u32, u32, u32, u32)>,
    key_has_config: bool,
}
impl Cache {
    fn clear_chain(&mut self) {
        self.chain.clear();
        self.bytes = 0;
        self.key_at = None;
        self.key_has_config = false;
    }
    fn push(&mut self, record: &ocb2::Record) -> Arc<[u8]> {
        let bytes = encode(record);
        if record.is_discontinuity() {
            self.clear_chain();
        }
        match record.record_type {
            ocb2::TYPE_STREAM_INFO => {
                // A fresh subscriber must configure first, then start at an IDR.
                self.clear_chain();
                let format = serde_json::from_slice::<ocb2::StreamInfo>(&record.payload)
                    .ok()
                    .map(|info| {
                        (
                            info.width,
                            info.height,
                            info.fps_numerator,
                            info.fps_denominator,
                        )
                    });
                if format != self.format {
                    self.config = None;
                }
                self.format = format;
                self.info = (bytes.len() <= MAX_METADATA_BYTES).then(|| Arc::clone(&bytes));
            }
            ocb2::TYPE_CODEC_CONFIG => {
                self.clear_chain();
                self.config = (bytes.len() <= MAX_METADATA_BYTES).then(|| Arc::clone(&bytes));
            }
            ocb2::TYPE_VIDEO_ACCESS_UNIT => {
                if record.is_keyframe() {
                    self.clear_chain();
                    self.key_at = Some(Instant::now());
                    let mut sps = false;
                    let mut pps = false;
                    for prefix in record.payload.windows(4) {
                        if prefix[..3] == [0, 0, 1] {
                            sps |= prefix[3] & 31 == 7;
                            pps |= prefix[3] & 31 == 8;
                        }
                    }
                    self.key_has_config = sps && pps;
                }
                if self.key_at.is_some() {
                    self.bytes += bytes.len();
                    if self.bytes > MAX_CACHE_BYTES
                        || self.chain.len() >= MAX_CACHE_RECORDS
                        || self.key_at.is_some_and(|at| at.elapsed() > MAX_CACHE_AGE)
                    {
                        self.clear_chain();
                    } else {
                        self.chain.push(Arc::clone(&bytes));
                    }
                }
            }
            ocb2::TYPE_END_OF_STREAM | ocb2::TYPE_ERROR => self.clear_chain(),
            _ => {}
        }
        bytes
    }
    fn replay(&self) -> Vec<Arc<[u8]>> {
        let mut records: Vec<_> = self
            .info
            .iter()
            .chain(self.config.iter())
            .cloned()
            .collect();
        if self.info.is_some()
            && (self.config.is_some() || self.key_has_config)
            && self.key_at.is_some_and(|at| at.elapsed() <= MAX_CACHE_AGE)
        {
            records.extend(self.chain.iter().cloned());
        }
        records
    }
}

struct Packet {
    bytes: Arc<[u8]>,
    queued_at: Instant,
}
struct Client {
    sender: SyncSender<Packet>,
    pending: Arc<AtomicUsize>,
}
struct Subscription {
    bootstrap: Vec<Arc<[u8]>>,
    receiver: Receiver<Packet>,
    pending: Arc<AtomicUsize>,
    max_age: Duration,
}
#[derive(Default)]
pub struct Hub {
    cache: Mutex<Cache>,
    clients: Mutex<Vec<Client>>,
    overflow_disconnects: AtomicUsize,
    queue_age_ms: AtomicUsize,
}
impl Hub {
    pub fn reset(&self) {
        let mut cache = self.cache.lock().unwrap_or_else(|e| e.into_inner());
        *cache = Cache::default();
        self.clients
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clear();
    }
    pub fn publish(&self, record: &ocb2::Record) {
        let mut cache = self.cache.lock().unwrap_or_else(|e| e.into_inner());
        if record.record_type == ocb2::TYPE_STREAM_INFO {
            let info =
                serde_json::from_slice::<serde_json::Value>(&record.payload).unwrap_or_default();
            let age = queue_age(
                info.get("latencyProfile")
                    .and_then(|v| v.as_str())
                    .unwrap_or("balanced"),
            );
            self.queue_age_ms
                .store(age.as_millis() as usize, Ordering::Relaxed);
        }
        // Large packets remain supported by native decode. Preview resumes at
        // the next keyframe instead of allowing unbounded memory per client.
        if record.payload.len() + ocb2::HEADER_SIZE > MAX_QUEUE_BYTES {
            cache.clear_chain();
            self.clients
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .clear();
            return;
        }
        let bytes = cache.push(record);
        self.clients
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .retain(|client| {
                let previous = client.pending.fetch_add(bytes.len(), Ordering::Relaxed);
                let sent = if previous + bytes.len() > MAX_QUEUE_BYTES {
                    self.overflow_disconnects.fetch_add(1, Ordering::Relaxed);
                    false
                } else {
                    match client.sender.try_send(Packet {
                        bytes: Arc::clone(&bytes),
                        queued_at: Instant::now(),
                    }) {
                        Ok(()) => true,
                        Err(TrySendError::Full(_)) => {
                            self.overflow_disconnects.fetch_add(1, Ordering::Relaxed);
                            false
                        }
                        Err(TrySendError::Disconnected(_)) => false,
                    }
                };
                if !sent {
                    client.pending.fetch_sub(bytes.len(), Ordering::Relaxed);
                }
                sent
            });
    }
    fn subscribe(&self) -> Option<Subscription> {
        // Same lock order as publish: bootstrap and live registration form one
        // atomic step, so no delta can be lost between the cached GOP and live.
        let cache = self.cache.lock().unwrap_or_else(|e| e.into_inner());
        let mut clients = self.clients.lock().unwrap_or_else(|e| e.into_inner());
        if clients.len() >= MAX_CLIENTS {
            return None;
        }
        let (sender, receiver) = sync_channel(QUEUE_RECORDS);
        let pending = Arc::new(AtomicUsize::new(0));
        clients.push(Client {
            sender,
            pending: Arc::clone(&pending),
        });
        Some(Subscription {
            bootstrap: cache.replay(),
            receiver,
            pending,
            max_age: self.queue_age(),
        })
    }
    fn queue_age(&self) -> Duration {
        let milliseconds = self.queue_age_ms.load(Ordering::Relaxed);
        if milliseconds == 0 {
            queue_age("balanced")
        } else {
            Duration::from_millis(milliseconds as u64)
        }
    }
    pub fn metrics(&self) -> serde_json::Value {
        serde_json::json!({ "subscribers": self.clients.lock().unwrap_or_else(|e| e.into_inner()).len(),
            "overflow_disconnects": self.overflow_disconnects.load(Ordering::Relaxed),
            "max_queue_bytes": MAX_QUEUE_BYTES, "max_queue_age_ms": self.queue_age().as_millis() })
    }
}

fn authorized(request: &str, token: &str) -> bool {
    let mut lines = request.split("\r\n");
    if lines.next() != Some("GET /stream.ocb2 HTTP/1.1") {
        return false;
    }
    let supplied = lines
        .filter_map(|line| line.split_once(':'))
        .find(|(name, _)| name.eq_ignore_ascii_case("X-OpenCamBridge-Preview"))
        .map(|(_, value)| value.trim())
        .unwrap_or_default();
    // Fixed-length credential; never accept query strings or log requests.
    supplied.len() == token.len()
        && supplied
            .as_bytes()
            .iter()
            .zip(token.bytes())
            .fold(0u8, |diff, (a, b)| diff | (a ^ b))
            == 0
}

fn serve(mut stream: TcpStream, hub: &Hub, token: &str) -> std::io::Result<()> {
    stream.set_read_timeout(Some(Duration::from_secs(2)))?;
    stream.set_write_timeout(Some(Duration::from_millis(250)))?;
    stream.set_nodelay(true)?;
    let mut request = Vec::new();
    let mut buffer = [0; 1024];
    while request.len() < 8192 {
        let count = stream.read(&mut buffer)?;
        if count == 0 {
            return Ok(());
        }
        request.extend_from_slice(&buffer[..count]);
        if request.windows(4).any(|w| w == b"\r\n\r\n") {
            break;
        }
    }
    let request = std::str::from_utf8(&request).unwrap_or_default();
    if !request.ends_with("\r\n\r\n") || !authorized(request, token) {
        stream.write_all(
            b"HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
        )?;
        return Ok(());
    }
    let Some(subscription) = hub.subscribe() else {
        stream.write_all(
            b"HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
        )?;
        return Ok(());
    };
    stream.write_all(b"HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n")?;
    let started = Instant::now();
    for bytes in subscription.bootstrap {
        if started.elapsed() > MAX_QUEUE_AGE {
            return Ok(());
        }
        stream.write_all(&bytes)?;
    }
    while let Ok(packet) = subscription.receiver.recv_timeout(Duration::from_secs(5)) {
        subscription
            .pending
            .fetch_sub(packet.bytes.len(), Ordering::Relaxed);
        if packet.queued_at.elapsed() > subscription.max_age {
            break;
        }
        stream.write_all(&packet.bytes)?;
    }
    Ok(())
}

/// Token is supplied privately by the launching desktop process; CLI users can
/// omit it to disable the local server. No credentials are emitted on stdout.
pub fn start_from_env() -> Result<Arc<Hub>, String> {
    let hub = Arc::new(Hub::default());
    let Ok(token) = std::env::var("OPENCAMBRIDGE_PREVIEW_TOKEN") else {
        return Ok(hub);
    };
    if token.len() != 64 || !token.bytes().all(|b| b.is_ascii_hexdigit()) {
        return Err("Invalid local preview credential".into());
    }
    let listener = TcpListener::bind((std::net::Ipv4Addr::LOCALHOST, 0))
        .map_err(|e| format!("Preview bind: {e}"))?;
    let port = listener.local_addr().map_err(|e| e.to_string())?.port();
    println!(
        "{}",
        serde_json::json!({ "type": "preview_endpoint", "port": port })
    );
    let serving = Arc::clone(&hub);
    std::thread::spawn(move || {
        let active = Arc::new(AtomicUsize::new(0));
        for stream in listener.incoming().flatten() {
            if active.fetch_add(1, Ordering::AcqRel) >= MAX_CLIENTS {
                active.fetch_sub(1, Ordering::AcqRel);
                continue;
            }
            let active = Arc::clone(&active);
            let hub = Arc::clone(&serving);
            let token = token.clone();
            std::thread::spawn(move || {
                let _ = serve(stream, &hub, &token);
                active.fetch_sub(1, Ordering::AcqRel);
            });
        }
    });
    Ok(hub)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn phone_profiles_set_live_fanout_age_and_unknown_profiles_remain_balanced() {
        let hub = Hub::default();
        assert_eq!(hub.queue_age(), Duration::from_millis(250));
        for (profile, expected) in [
            ("low", 120),
            ("smooth", 500),
            ("balanced", 250),
            ("unknown", 250),
        ] {
            let mut info = record(ocb2::TYPE_STREAM_INFO, 0, 1);
            info.payload =
                serde_json::to_vec(&serde_json::json!({ "latencyProfile": profile })).unwrap();
            hub.publish(&info);
            assert_eq!(
                hub.subscribe().unwrap().max_age,
                Duration::from_millis(expected)
            );
            hub.reset();
        }
    }
    fn record(kind: u16, flags: u32, sequence: u64) -> ocb2::Record {
        ocb2::Record {
            record_type: kind,
            flags,
            sequence,
            capture_timestamp_ns: 0,
            encoder_timestamp_us: sequence as i64,
            send_delta_us: 0,
            payload: vec![sequence as u8],
        }
    }
    #[test]
    fn late_join_replays_configuration_and_whole_reference_chain() {
        let mut cache = Cache::default();
        for r in [
            record(1, 0, 1),
            record(2, 1, 2),
            record(3, 2, 3),
            record(3, 0, 4),
        ] {
            cache.push(&r);
        }
        let replay = cache.replay();
        let mut parser = ocb2::Parser::new();
        for bytes in replay {
            parser.push(&bytes);
        }
        let mut sequences = Vec::new();
        while let Some(r) = parser.next().unwrap() {
            sequences.push(r.sequence);
        }
        assert_eq!(sequences, vec![1, 2, 3, 4]);
    }
    #[test]
    fn in_band_parameter_sets_support_late_join_without_a_config_record() {
        let mut cache = Cache::default();
        cache.push(&record(1, 0, 1));
        let mut key = record(3, 2, 2);
        key.payload = vec![
            0, 0, 0, 1, 0x67, 100, 0, 42, 0, 0, 1, 0x68, 0x80, 0, 0, 1, 0x65,
        ];
        cache.push(&key);
        cache.push(&record(3, 0, 3));
        assert_eq!(cache.replay().len(), 3);
    }
    #[test]
    fn a_closed_preview_does_not_count_as_an_overflow() {
        let hub = Hub::default();
        drop(hub.subscribe().unwrap());
        hub.publish(&record(3, 2, 1));
        assert_eq!(hub.overflow_disconnects.load(Ordering::Relaxed), 0);
        assert_eq!(hub.clients.lock().unwrap().len(), 0);
    }
    #[test]
    fn discontinuity_and_cache_overflow_never_replay_partial_chain() {
        let mut cache = Cache::default();
        cache.push(&record(1, 0, 1));
        cache.push(&record(2, 1, 2));
        cache.push(&record(3, 2, 3));
        cache.push(&record(4, 4, 4));
        cache.push(&record(3, 0, 5));
        assert_eq!(cache.replay().len(), 2);
        cache.push(&record(3, 2, 6));
        for sequence in 7..=MAX_CACHE_RECORDS as u64 + 7 {
            cache.push(&record(3, 0, sequence));
        }
        assert_eq!(cache.replay().len(), 2);
        cache.push(&record(3, 2, 999));
        assert_eq!(cache.replay().len(), 3);
    }
    #[test]
    fn slow_subscriber_is_removed_and_publishing_keeps_progressing() {
        let hub = Hub::default();
        let subscription = hub.subscribe().unwrap();
        for sequence in 1..=QUEUE_RECORDS as u64 + 3 {
            hub.publish(&record(3, 0, sequence));
        }
        assert_eq!(hub.clients.lock().unwrap().len(), 0);
        drop(subscription);
        let healthy = hub.subscribe().unwrap();
        hub.publish(&record(3, 2, 50));
        assert!(healthy.receiver.try_recv().is_ok());
    }
    #[test]
    fn preview_auth_requires_exact_token_header_and_stream_path() {
        let token = "a".repeat(64);
        assert!(authorized(
            &format!("GET /stream.ocb2 HTTP/1.1\r\nX-OpenCamBridge-Preview: {token}\r\n\r\n"),
            &token
        ));
        assert!(!authorized("GET /stream.ocb2 HTTP/1.1\r\n\r\n", &token));
        assert!(!authorized(
            &format!("GET /stream.ocb2?token={token} HTTP/1.1\r\n\r\n"),
            &token
        ));
        assert!(!authorized(
            "GET /stream.ocb2 HTTP/1.1\r\nX-OpenCamBridge-Preview: invalid\r\n\r\n",
            &token
        ));
    }
    #[test]
    fn loopback_http_authentication_bootstrap_and_live_share_one_subscription() {
        let hub = Arc::new(Hub::default());
        for r in [
            record(1, 0, 1),
            record(2, 1, 2),
            record(3, 2, 3),
            record(3, 0, 4),
        ] {
            hub.publish(&r);
        }
        let listener = TcpListener::bind((std::net::Ipv4Addr::LOCALHOST, 0)).unwrap();
        let address = listener.local_addr().unwrap();
        let token = "ab".repeat(32);
        let serving = Arc::clone(&hub);
        let credential = token.clone();
        let server = std::thread::spawn(move || {
            for _ in 0..2 {
                let (stream, _) = listener.accept().unwrap();
                let _ = serve(stream, &serving, &credential);
            }
        });
        let mut unauthorized = TcpStream::connect(address).unwrap();
        unauthorized
            .write_all(b"GET /stream.ocb2 HTTP/1.1\r\n\r\n")
            .unwrap();
        let mut rejected = String::new();
        unauthorized.read_to_string(&mut rejected).unwrap();
        assert!(rejected.starts_with("HTTP/1.1 403"));
        let mut stream = TcpStream::connect(address).unwrap();
        stream
            .set_read_timeout(Some(Duration::from_secs(2)))
            .unwrap();
        stream
            .write_all(
                format!("GET /stream.ocb2 HTTP/1.1\r\nX-OpenCamBridge-Preview: {token}\r\n\r\n")
                    .as_bytes(),
            )
            .unwrap();
        let mut headers = Vec::new();
        while !headers.ends_with(b"\r\n\r\n") {
            let mut b = [0];
            stream.read_exact(&mut b).unwrap();
            headers.push(b[0]);
        }
        assert!(headers.starts_with(b"HTTP/1.1 200"));
        hub.publish(&record(3, 0, 5));
        let mut bytes = vec![0; (ocb2::HEADER_SIZE + 1) * 5];
        stream.read_exact(&mut bytes).unwrap();
        let mut parser = ocb2::Parser::new();
        parser.push(&bytes);
        let mut sequences = Vec::new();
        while let Some(record) = parser.next().unwrap() {
            sequences.push(record.sequence);
        }
        assert_eq!(sequences, vec![1, 2, 3, 4, 5]);
        hub.reset();
        server.join().unwrap();
    }
}

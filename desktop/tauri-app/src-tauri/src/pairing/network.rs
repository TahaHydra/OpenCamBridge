use super::{session::Invitation, store::Store};
use serde_json::{json, Value};
use std::{
    io::{Read, Write},
    net::{IpAddr, TcpListener, TcpStream, UdpSocket},
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};

pub fn hosts() -> Result<Vec<String>, String> {
    let mut hosts: Vec<String> = if_addrs::get_if_addrs()
        .map_err(|_| "Cannot list LAN adapters")?
        .into_iter()
        .filter_map(|interface| match interface.ip() {
            IpAddr::V4(ip) if ip.is_private() => Some(ip.to_string()),
            _ => None,
        })
        .collect();
    hosts.sort();
    hosts.dedup();
    if hosts.is_empty() {
        return Err("Connect this PC to a private Wi-Fi or Ethernet LAN first".into());
    }
    Ok(hosts)
}

fn headers(bytes: &[u8]) -> Result<(String, usize), String> {
    let text = std::str::from_utf8(bytes).map_err(|_| "Invalid HTTP headers")?;
    let mut lines = text.split("\r\n");
    let first: Vec<_> = lines
        .next()
        .unwrap_or_default()
        .split_whitespace()
        .collect();
    if first.len() != 3
        || first[0] != "POST"
        || !matches!(first[1], "/pair/start" | "/pair/prove" | "/pair/finish")
        || first[2] != "HTTP/1.1"
    {
        return Err("Invalid pairing endpoint".into());
    }
    let mut length = None;
    let mut json_type = false;
    for line in lines.filter(|line| !line.is_empty()) {
        let (key, value) = line.split_once(':').ok_or("Invalid HTTP header")?;
        match key.to_ascii_lowercase().as_str() {
            "content-length" => {
                if length.is_some() {
                    return Err("Duplicate content length".into());
                }
                length = Some(
                    value
                        .trim()
                        .parse::<usize>()
                        .map_err(|_| "Invalid content length")?,
                );
            }
            "content-type" => {
                json_type = value.trim().split(';').next() == Some("application/json")
            }
            "transfer-encoding" | "origin" => {
                return Err("Browser and chunked pairing requests are not accepted".into())
            }
            _ => {}
        }
    }
    let length = length
        .filter(|length| *length > 0 && *length <= 8192)
        .ok_or("Invalid pairing payload length")?;
    if !json_type {
        return Err("JSON content type required".into());
    }
    Ok((first[1].to_string(), length))
}

fn request(stream: &mut TcpStream) -> Result<(String, Value), String> {
    stream
        .set_read_timeout(Some(Duration::from_millis(400)))
        .map_err(|_| "Socket timeout failed")?;
    let deadline = Instant::now() + Duration::from_secs(3);
    let mut bytes = Vec::new();
    let mut buffer = [0; 2048];
    let (path, length, offset) = loop {
        if Instant::now() >= deadline {
            return Err("Pairing request timed out".into());
        }
        match stream.read(&mut buffer) {
            Ok(0) => return Err("Incomplete pairing request".into()),
            Ok(count) => bytes.extend_from_slice(&buffer[..count]),
            Err(error)
                if matches!(
                    error.kind(),
                    std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut
                ) =>
            {
                continue
            }
            Err(_) => return Err("Pairing read failed".into()),
        }
        if let Some(end) = bytes.windows(4).position(|part| part == b"\r\n\r\n") {
            if end > 8192 {
                return Err("Pairing headers too large".into());
            }
            let (path, length) = headers(&bytes[..end])?;
            break (path, length, end + 4);
        }
        if bytes.len() > 8192 {
            return Err("Pairing headers too large".into());
        }
    };
    while bytes.len() < offset + length {
        if Instant::now() >= deadline {
            return Err("Pairing request timed out".into());
        }
        match stream.read(&mut buffer) {
            Ok(0) => return Err("Incomplete pairing request".into()),
            Ok(count) => bytes.extend_from_slice(&buffer[..count]),
            Err(error)
                if matches!(
                    error.kind(),
                    std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut
                ) =>
            {
                continue
            }
            Err(_) => return Err("Pairing read failed".into()),
        }
    }
    let value = serde_json::from_slice(&bytes[offset..offset + length])
        .map_err(|_| "Invalid pairing JSON")?;
    Ok((path, value))
}

pub struct Listener {
    tcp: TcpListener,
    udp: UdpSocket,
}
impl Listener {
    pub fn bind() -> Result<Self, String> {
        let tcp = TcpListener::bind((std::net::Ipv4Addr::UNSPECIFIED, 0))
            .map_err(|_| "Cannot open local pairing listener")?;
        let udp = UdpSocket::bind((std::net::Ipv4Addr::UNSPECIFIED, 47653)).map_err(|_| {
            "Pairing discovery port 47653 is in use. Close the other pairing window and retry."
        })?;
        tcp.set_nonblocking(true)
            .map_err(|_| "Cannot configure pairing listener")?;
        udp.set_nonblocking(true)
            .map_err(|_| "Cannot configure pairing discovery")?;
        Ok(Self { tcp, udp })
    }
    pub fn port(&self) -> Result<u16, String> {
        self.tcp
            .local_addr()
            .map(|address| address.port())
            .map_err(|_| "Cannot read pairing port".into())
    }
    pub fn run(
        self,
        invitation: Arc<Mutex<Invitation>>,
        store: Arc<Mutex<Store>>,
    ) -> std::thread::JoinHandle<()> {
        std::thread::spawn(move || {
            loop {
                if !invitation
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .listening()
                {
                    break;
                }
                let mut buffer = [0; 512];
                // Bound discovery work so a datagram flood cannot starve expiry/TCP.
                for _ in 0..8 {
                    let Ok((count, peer)) = self.udp.recv_from(&mut buffer) else {
                        break;
                    };
                    if !matches!(peer.ip(), IpAddr::V4(ip) if ip.is_private())
                        || &buffer[..count] != b"OCB_PAIR_DISCOVER_V1"
                    {
                        continue;
                    }
                    let current = invitation.lock().unwrap_or_else(|e| e.into_inner());
                    if current.window.paired || !current.listening() {
                        continue;
                    }
                    let reply = json!({"service":"ocb-pair-v1","id":current.view.id,"name":current.view.name,"port":current.view.port}).to_string();
                    let _ = self.udp.send_to(reply.as_bytes(), peer);
                }
                match self.tcp.accept() {
                    Ok((mut stream, peer)) => {
                        let IpAddr::V4(ip) = peer.ip() else {
                            continue;
                        };
                        if !ip.is_private() {
                            continue;
                        }
                        let result = request(&mut stream).and_then(|(path, value)| {
                            let mut current =
                                invitation.lock().map_err(|_| "Pairing state unavailable")?;
                            let mut saved =
                                store.lock().map_err(|_| "Pairing storage unavailable")?;
                            current.handle(&path, value, ip, &mut saved)
                        });
                        let (status, body) = match result {
                            Ok(value) => ("200 OK", value),
                            Err(error) => ("400 Bad Request", json!({"error":error})),
                        };
                        let body = body.to_string();
                        let reply = format!("HTTP/1.1 {status}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n{body}", body.len());
                        let _ = stream.set_write_timeout(Some(Duration::from_secs(1)));
                        let _ = stream.write_all(reply.as_bytes());
                    }
                    Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                        std::thread::sleep(Duration::from_millis(20))
                    }
                    Err(_) => break,
                }
            }
            invitation
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .clear_secrets();
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn parser_rejects_browser_smuggling_and_unbounded_requests() {
        let valid =
            "POST /pair/start HTTP/1.1\r\nContent-Type: application/json\r\nContent-Length: 2";
        assert_eq!(
            headers(valid.as_bytes()).unwrap(),
            ("/pair/start".into(), 2)
        );
        for extra in [
            "\r\nOrigin: http://evil",
            "\r\nTransfer-Encoding: chunked",
            "\r\nContent-Length: 3",
        ] {
            assert!(headers(format!("{valid}{extra}").as_bytes()).is_err());
        }
        assert!(headers(valid.replace("Length: 2", "Length: 999999").as_bytes()).is_err());
        assert!(headers(valid.replace("POST", "GET").as_bytes()).is_err());
    }
}

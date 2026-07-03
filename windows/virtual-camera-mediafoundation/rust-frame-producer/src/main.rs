use clap::Parser;
use std::ffi::c_void;
use std::ptr::{null_mut, copy_nonoverlapping};
use std::time::{Instant, Duration};
use std::thread::{sleep, spawn};
use std::sync::{Arc, Mutex};
use std::io::Read;
use windows::core::PCWSTR;
use windows::Win32::Foundation::{CloseHandle, HANDLE, INVALID_HANDLE_VALUE, WAIT_OBJECT_0, WAIT_ABANDONED};
use windows::Win32::Security::{SECURITY_ATTRIBUTES, PSECURITY_DESCRIPTOR};
use windows::Win32::Security::Authorization::{ConvertStringSecurityDescriptorToSecurityDescriptorW, SDDL_REVISION_1};
use windows::Win32::System::Memory::{CreateFileMappingW, MapViewOfFile, UnmapViewOfFile, FILE_MAP_ALL_ACCESS, PAGE_READWRITE};
use windows::Win32::System::Performance::QueryPerformanceCounter;
use windows::Win32::System::Threading::{CreateMutexW, ReleaseMutex, WaitForSingleObject};
use windows::Win32::Storage::FileSystem::{CreateFileW, LockFileEx, UnlockFileEx, OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, FILE_SHARE_READ, FILE_SHARE_WRITE, LOCKFILE_EXCLUSIVE_LOCK};
use windows::Win32::System::IO::OVERLAPPED;

const OCBF_MAGIC: u32 = 0x4642434F;
const FORMAT_BGRA32: u32 = 1;
const MAX_SHM_SIZE: u32 = 1920 * 1080 * 4 + 1024; // Big enough for 1080p

#[repr(C, packed)]
struct OpenCamBridgeFrameHeader {
    magic: u32,
    version: u32,
    width: u32,
    height: u32,
    stride: u32,
    format: u32,
    frame_counter: u64,
    timestamp_qpc: u64,
    data_size: u32,
    reserved: u32,
}

#[derive(Parser, Debug)]
#[command(author, version, about, long_about = None)]
struct Args {
    /// Frame source: "mjpeg" (stable), "test-pattern", or "h264" (EXPERIMENTAL scaffold, no decode yet)
    #[arg(short, long)]
    source: String,

    #[arg(short, long)]
    url: Option<String>,

    #[arg(long)]
    width: Option<u32>,

    #[arg(long)]
    height: Option<u32>,

    #[arg(long)]
    fps: Option<u32>,

    #[arg(long)]
    quality: Option<u32>,

    #[arg(long)]
    latest_only: bool,

    #[arg(long, default_value = "custom")]
    profile: String,

    /// LAN access token; sent as the X-OpenCamBridge-Token header on stream requests.
    #[arg(long)]
    token: Option<String>,

    /// Explicit output rotation in degrees (0, 90, 180, 270). Overrides portrait auto-rotate.
    #[arg(long)]
    rotate: Option<u32>,

    /// Horizontally mirror the output frame (applied after rotation).
    #[arg(long)]
    mirror: bool,
}

struct SharedMemoryIpc {
    h_file: HANDLE,
    h_map: HANDLE,
    h_mutex: HANDLE,
    p_map: *mut c_void,
    use_file_lock: bool,
    backend_name: String,
}

impl SharedMemoryIpc {
    fn new() -> Result<Self, String> {
        unsafe {
            let mut p_sd: PSECURITY_DESCRIPTOR = PSECURITY_DESCRIPTOR(null_mut());
            let sddl: Vec<u16> = "D:P(A;;GA;;;SY)(A;;GA;;;BA)(A;;GRGW;;;LS)(A;;GRGW;;;IU)(A;;GRGW;;;AU)\0".encode_utf16().collect();

            if ConvertStringSecurityDescriptorToSecurityDescriptorW(PCWSTR(sddl.as_ptr()), SDDL_REVISION_1, &mut p_sd, None).is_err() {
                return Err("Failed to create preferred security descriptor".into());
            }

            let mut sa = SECURITY_ATTRIBUTES {
                nLength: std::mem::size_of::<SECURITY_ATTRIBUTES>() as u32,
                lpSecurityDescriptor: p_sd.0,
                bInheritHandle: windows::Win32::Foundation::BOOL(0),
            };

            let _ = std::fs::create_dir_all("C:\\ProgramData\\OpenCamBridge");

            let path: Vec<u16> = "C:\\ProgramData\\OpenCamBridge\\framebuffer.bin\0".encode_utf16().collect();
            let h_file = CreateFileW(
                PCWSTR(path.as_ptr()),
                (windows::Win32::Storage::FileSystem::FILE_GENERIC_READ.0 | windows::Win32::Storage::FileSystem::FILE_GENERIC_WRITE.0) as u32,
                FILE_SHARE_READ | FILE_SHARE_WRITE,
                Some(&sa),
                OPEN_ALWAYS,
                FILE_ATTRIBUTE_NORMAL,
                None,
            );

            if let Ok(h_file) = h_file {
                if !h_file.is_invalid() {
                    let h_map = CreateFileMappingW(
                        h_file,
                        Some(&sa),
                        PAGE_READWRITE,
                        0,
                        MAX_SHM_SIZE,
                        PCWSTR(null_mut()),
                    );

                    if let Ok(h_map_val) = h_map {
                        if !h_map_val.is_invalid() {
                            let p_map = MapViewOfFile(h_map_val, FILE_MAP_ALL_ACCESS, 0, 0, 0);
                            if !p_map.Value.is_null() {
                                return Ok(Self {
                                    h_file, h_map: h_map_val, h_mutex: HANDLE(0),
                                    p_map: p_map.Value, use_file_lock: true,
                                    backend_name: "C:\\ProgramData\\OpenCamBridge\\framebuffer.bin".to_string(),
                                });
                            }
                            let _ = CloseHandle(h_map_val);
                        }
                    }
                    let _ = CloseHandle(h_file);
                }
            }

            let name_buffer: Vec<u16> = "Global\\OpenCamBridgeFrameBuffer\0".encode_utf16().collect();
            let h_map = CreateFileMappingW(
                INVALID_HANDLE_VALUE, Some(&sa), PAGE_READWRITE, 0, MAX_SHM_SIZE, PCWSTR(name_buffer.as_ptr()),
            ).map_err(|e| format!("CreateFileMappingW failed: {}", e))?;

            let name_mutex: Vec<u16> = "Global\\OpenCamBridgeFrameMutex\0".encode_utf16().collect();
            let h_mutex = CreateMutexW(Some(&sa), false, PCWSTR(name_mutex.as_ptr()))
                .map_err(|e| format!("CreateMutexW failed: {}", e))?;

            let p_map = MapViewOfFile(h_map, FILE_MAP_ALL_ACCESS, 0, 0, 0);
            if p_map.Value.is_null() {
                let _ = CloseHandle(h_mutex);
                let _ = CloseHandle(h_map);
                return Err("MapViewOfFile failed".into());
            }

            std::ptr::write_bytes(p_map.Value as *mut u8, 0, MAX_SHM_SIZE as usize);

            Ok(Self { h_file: HANDLE(0), h_map, h_mutex, p_map: p_map.Value, use_file_lock: false, backend_name: "Global\\OpenCamBridgeFrameBuffer".to_string() })
        }
    }

    fn write_frame(&self, frame_counter: u64, frame_data: &[u8], width: u32, height: u32) {
        // Defensive bound check: never write past the mapped region.
        let needed = (width as usize) * (height as usize) * 4 + std::mem::size_of::<OpenCamBridgeFrameHeader>();
        if needed > MAX_SHM_SIZE as usize {
            eprintln!("write_frame refused: {}x{} exceeds shared memory capacity", width, height);
            return;
        }

        unsafe {
            let mut locked = false;

            if self.use_file_lock {
                let mut overlapped = OVERLAPPED::default();
                if LockFileEx(self.h_file, LOCKFILE_EXCLUSIVE_LOCK, 0, 1, 0, &mut overlapped).is_ok() {
                    locked = true;
                }
            } else {
                let wait_res = WaitForSingleObject(self.h_mutex, 100);
                if wait_res == WAIT_OBJECT_0 || wait_res == WAIT_ABANDONED {
                    locked = true;
                }
            }

            if locked {
                let stride = width * 4;
                let data_size = width * height * 4;

                let mut qpc: i64 = 0;
                let _ = QueryPerformanceCounter(&mut qpc);

                let header_ptr = self.p_map as *mut OpenCamBridgeFrameHeader;
                (*header_ptr).magic = OCBF_MAGIC;
                (*header_ptr).version = 1;
                (*header_ptr).width = width;
                (*header_ptr).height = height;
                (*header_ptr).stride = stride;
                (*header_ptr).format = FORMAT_BGRA32;
                (*header_ptr).frame_counter = frame_counter;
                (*header_ptr).timestamp_qpc = qpc as u64;
                (*header_ptr).data_size = data_size;
                (*header_ptr).reserved = 0;

                let data_ptr = (self.p_map as *mut u8).add(std::mem::size_of::<OpenCamBridgeFrameHeader>());
                copy_nonoverlapping(frame_data.as_ptr(), data_ptr, std::cmp::min(data_size as usize, frame_data.len()));

                if self.use_file_lock {
                    let mut overlapped = OVERLAPPED::default();
                    let _ = UnlockFileEx(self.h_file, 0, 1, 0, &mut overlapped);
                } else {
                    let _ = ReleaseMutex(self.h_mutex);
                }
            }
        }
    }
}

impl Drop for SharedMemoryIpc {
    fn drop(&mut self) {
        unsafe {
            if !self.p_map.is_null() {
                let _ = UnmapViewOfFile(windows::Win32::System::Memory::MEMORY_MAPPED_VIEW_ADDRESS { Value: self.p_map });
            }
            if !self.h_mutex.is_invalid() && self.h_mutex.0 != 0 {
                let _ = CloseHandle(self.h_mutex);
            }
            if !self.h_map.is_invalid() && self.h_map.0 != 0 {
                let _ = CloseHandle(self.h_map);
            }
            if !self.h_file.is_invalid() && self.h_file.0 != 0 {
                let _ = CloseHandle(self.h_file);
            }
        }
    }
}

fn generate_test_pattern(frame_counter: u64, width: u32, height: u32) -> Vec<u8> {
    let data_size = width * height * 4;
    let mut buf = vec![0u8; data_size as usize];
    let offset = (frame_counter % height as u64) as u32;

    for y in 0..height {
        for x in 0..width {
            let index = ((y * width * 4) + (x * 4)) as usize;
            let gray = ((y + offset) % 256) as u8;
            buf[index] = gray;     // B
            buf[index + 1] = gray; // G
            buf[index + 2] = gray; // R
            buf[index + 3] = 255;  // A
        }
    }
    buf
}

/// Minimal JSON string escaping for the single-line metrics output.
fn json_escape(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 8);
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' | '\r' | '\t' => out.push(' '),
            c if (c as u32) < 0x20 => out.push(' '),
            c => out.push(c),
        }
    }
    out
}

fn build_http_client() -> Result<reqwest::blocking::Client, String> {
    // IMPORTANT: the default blocking client applies a 30s TOTAL request timeout,
    // which silently kills long-lived streaming responses and forces a reconnect
    // loop. Disable the total timeout, keep a connect timeout, and rely on TCP
    // keepalive to detect dead peers.
    reqwest::blocking::Client::builder()
        .connect_timeout(Duration::from_secs(5))
        .timeout(None::<Duration>)
        .tcp_keepalive(Duration::from_secs(15))
        .build()
        .map_err(|e| e.to_string())
}

fn set_error(slot: &Arc<Mutex<Option<String>>>, msg: String) {
    eprintln!("{}", msg);
    *slot.lock().unwrap() = Some(msg);
}

fn clear_error(slot: &Arc<Mutex<Option<String>>>) {
    *slot.lock().unwrap() = None;
}

fn backoff_secs(consecutive_failures: u32) -> u64 {
    match consecutive_failures {
        0 | 1 => 1,
        2 => 2,
        3 => 4,
        _ => 8,
    }
}

#[allow(clippy::too_many_arguments)]
fn start_mjpeg_reader(
    url: String,
    token: Option<String>,
    latest_jpeg: Arc<Mutex<Option<Vec<u8>>>>,
    http_jpeg_counter: Arc<Mutex<u32>>,
    dropped_jpeg_counter: Arc<Mutex<u32>>,
    mjpeg_bytes_counter: Arc<Mutex<u64>>,
    last_error: Arc<Mutex<Option<String>>>,
) {
    spawn(move || {
        let mut failures: u32 = 0;
        loop {
            let client = match build_http_client() {
                Ok(c) => c,
                Err(e) => {
                    set_error(&last_error, format!("HTTP client init failed: {}", e));
                    sleep(Duration::from_secs(backoff_secs(failures)));
                    failures = failures.saturating_add(1);
                    continue;
                }
            };

            let mut request = client.get(&url);
            if let Some(t) = &token {
                request = request.header("X-OpenCamBridge-Token", t.as_str());
            }

            match request.send() {
                Ok(mut res) => {
                    if !res.status().is_success() {
                        let hint = if res.status().as_u16() == 401 {
                            " (unauthorized: check the LAN access token)"
                        } else {
                            ""
                        };
                        set_error(&last_error, format!("MJPEG stream returned HTTP {}{}", res.status(), hint));
                        failures = failures.saturating_add(1);
                    } else {
                        failures = 0;
                        clear_error(&last_error);

                        let mut buf = [0u8; 32768];
                        let mut frame_buffer = Vec::new();

                        loop {
                            match res.read(&mut buf) {
                                Ok(0) => {
                                    set_error(&last_error, "MJPEG stream ended (server closed connection); reconnecting".to_string());
                                    break;
                                }
                                Ok(n) => {
                                    frame_buffer.extend_from_slice(&buf[..n]);

                                    while let Some(start) = frame_buffer.windows(2).position(|w| w == [0xFF, 0xD8]) {
                                        if let Some(end_offset) = frame_buffer[start..].windows(2).position(|w| w == [0xFF, 0xD9]) {
                                            let end = start + end_offset + 2;
                                            let jpeg_data = &frame_buffer[start..end];

                                            {
                                                let mut lock = latest_jpeg.lock().unwrap();
                                                if lock.is_some() {
                                                    let mut drops = dropped_jpeg_counter.lock().unwrap();
                                                    *drops += 1;
                                                }
                                                *lock = Some(jpeg_data.to_vec());
                                                let mut http_count = http_jpeg_counter.lock().unwrap();
                                                *http_count += 1;
                                                let mut bytes_lock = mjpeg_bytes_counter.lock().unwrap();
                                                *bytes_lock += jpeg_data.len() as u64;
                                            }

                                            frame_buffer.drain(..end);
                                        } else {
                                            break;
                                        }
                                    }

                                    if frame_buffer.len() > 10_000_000 {
                                        frame_buffer.clear();
                                    }
                                }
                                Err(e) => {
                                    set_error(&last_error, format!("MJPEG stream read error: {}; reconnecting", e));
                                    break;
                                }
                            }
                        }
                    }
                }
                Err(e) => {
                    set_error(&last_error, format!("MJPEG connect failed: {}; retrying", e));
                    failures = failures.saturating_add(1);
                }
            }
            sleep(Duration::from_secs(backoff_secs(failures)));
        }
    });
}

// ---------------------------------------------------------------------------
// H.264 EXPERIMENTAL SCAFFOLD
//
// STATUS: transport + Annex B parsing only. There is NO H.264 decoder wired in
// yet, so this source NEVER writes frames to the virtual camera framebuffer.
// It exists to validate the network path and bitstream shape end-to-end, and
// to give the desktop app honest telemetry about what is (not) happening.
//
// Decoder integration candidates, in rough order of preference for this repo:
//   1. Windows Media Foundation H.264 MFT (no new redistributable, needs
//      IMFTransform COM plumbing + NV12 -> BGRA conversion).
//   2. openh264 crate (Cisco binary license considerations).
//   3. ffmpeg-based decode in a separate helper process.
// ---------------------------------------------------------------------------

#[derive(Default)]
struct H264Stats {
    nal_count: u64,
    sps_seen: bool,
    pps_seen: bool,
    idr_count: u64,
    bytes_total: u64,
}

/// Scans an Annex B byte stream for completed NAL units, updating stats.
/// Retains the trailing partial NAL in `pending` for the next chunk.
fn scan_annex_b(pending: &mut Vec<u8>, stats: &Arc<Mutex<H264Stats>>) {
    let mut positions: Vec<usize> = Vec::new();
    let mut i = 0usize;
    while i + 3 < pending.len() {
        if pending[i] == 0 && pending[i + 1] == 0 && (pending[i + 2] == 1 || (pending[i + 2] == 0 && pending[i + 3] == 1)) {
            positions.push(i);
            i += 3;
        } else {
            i += 1;
        }
    }

    if positions.len() < 2 {
        return;
    }

    {
        let mut s = stats.lock().unwrap();
        for w in positions.windows(2) {
            let start = w[0];
            let header_idx = if pending[start + 2] == 1 { start + 3 } else { start + 4 };
            if header_idx < w[1] {
                let nal_type = pending[header_idx] & 0x1F;
                s.nal_count += 1;
                match nal_type {
                    7 => s.sps_seen = true,
                    8 => s.pps_seen = true,
                    5 => s.idr_count += 1,
                    _ => {}
                }
            }
        }
    }

    let last = *positions.last().unwrap();
    pending.drain(..last);
}

fn start_h264_reader(
    url: String,
    token: Option<String>,
    stats: Arc<Mutex<H264Stats>>,
    last_error: Arc<Mutex<Option<String>>>,
) {
    spawn(move || {
        let mut failures: u32 = 0;
        loop {
            let client = match build_http_client() {
                Ok(c) => c,
                Err(e) => {
                    set_error(&last_error, format!("HTTP client init failed: {}", e));
                    sleep(Duration::from_secs(backoff_secs(failures)));
                    failures = failures.saturating_add(1);
                    continue;
                }
            };

            let mut request = client.get(&url);
            if let Some(t) = &token {
                request = request.header("X-OpenCamBridge-Token", t.as_str());
            }

            match request.send() {
                Ok(mut res) => {
                    if !res.status().is_success() {
                        set_error(&last_error, format!("H.264 stream returned HTTP {}", res.status()));
                        failures = failures.saturating_add(1);
                    } else {
                        failures = 0;
                        clear_error(&last_error);

                        let mut buf = [0u8; 32768];
                        let mut pending: Vec<u8> = Vec::new();

                        loop {
                            match res.read(&mut buf) {
                                Ok(0) => break,
                                Ok(n) => {
                                    {
                                        stats.lock().unwrap().bytes_total += n as u64;
                                    }
                                    pending.extend_from_slice(&buf[..n]);
                                    scan_annex_b(&mut pending, &stats);
                                    if pending.len() > 4_000_000 {
                                        pending.clear();
                                    }
                                }
                                Err(e) => {
                                    set_error(&last_error, format!("H.264 stream read error: {}; reconnecting", e));
                                    break;
                                }
                            }
                        }
                    }
                }
                Err(e) => {
                    set_error(&last_error, format!("H.264 connect failed: {}; retrying", e));
                    failures = failures.saturating_add(1);
                }
            }
            sleep(Duration::from_secs(backoff_secs(failures)));
        }
    });
}

/// Emits honest metrics for the H.264 scaffold: transport statistics plus an
/// explicit "decode not implemented" error so no UI can mistake this for a
/// working video path. Never writes to the framebuffer.
fn run_h264_scaffold(args: Args, _ipc: SharedMemoryIpc) {
    eprintln!("WARNING: --source h264 is an EXPERIMENTAL transport scaffold.");
    eprintln!("WARNING: No H.264 decoder is integrated; NO frames will reach the virtual camera.");

    let url = args.url.clone().expect("URL is required for h264 source");
    let stats = Arc::new(Mutex::new(H264Stats::default()));
    let last_error = Arc::new(Mutex::new(None::<String>));

    start_h264_reader(url, args.token.clone(), stats.clone(), last_error.clone());

    let width = args.width.unwrap_or(1280);
    let height = args.height.unwrap_or(720);
    let fps = args.fps.unwrap_or(30);

    loop {
        sleep(Duration::from_secs(1));

        let (nals, sps, pps, idr, bytes) = {
            let s = stats.lock().unwrap();
            (s.nal_count, s.sps_seen, s.pps_seen, s.idr_count, s.bytes_total)
        };
        let transport_err = { last_error.lock().unwrap().clone() };

        let status = match transport_err {
            Some(e) => format!(
                "H264_DECODE_NOT_IMPLEMENTED: no frames written to framebuffer. Transport error: {}",
                e
            ),
            None => format!(
                "H264_DECODE_NOT_IMPLEMENTED: transport OK (nals={}, sps={}, pps={}, idr={}, bytes={}) but no frames written to framebuffer",
                nals, sps, pps, idr, bytes
            ),
        };

        println!(
            r#"{{"source":"h264","profile":"{}","source_width":0,"source_height":0,"output_width":{},"output_height":{},"fps_target":{},"http_jpeg_fps":0,"decoded_fps":0,"written_fps":0,"dropped_jpegs":0,"jpeg_queue_len":0,"decode_ms_avg":0,"rotate_ms_avg":0,"resize_ms_avg":0,"write_ms_avg":0,"total_pipeline_ms":0,"bytes_per_sec":{},"estimated_mbps":"0.00","pixel_format":"BGRA32","last_error":"{}"}}"#,
            json_escape(&args.profile), width, height, fps, bytes, json_escape(&status)
        );
    }
}

fn main() {
    let args = Args::parse();

    match args.source.as_str() {
        "mjpeg" | "test-pattern" | "h264" => {}
        other => {
            eprintln!("Unknown --source '{}'. Expected mjpeg, test-pattern, or h264.", other);
            std::process::exit(2);
        }
    }

    let defaults = match args.profile.as_str() {
        "low-latency" => (960, 540, 30, 70),
        "balanced" => (1280, 720, 30, 85),
        "balanced-720p60" => (1280, 720, 60, 80),
        "quality" => (1920, 1080, 30, 90),
        "experimental-1080p60" => (1920, 1080, 60, 85),
        _ => (1280, 720, 30, 85),
    };

    let width = args.width.unwrap_or(defaults.0);
    let height = args.height.unwrap_or(defaults.1);
    let fps = args.fps.unwrap_or(defaults.2).max(1);
    let _quality = args.quality.unwrap_or(defaults.3); // Kept for completeness

    // The shared memory region is sized for 1080p BGRA. Refuse configurations
    // that could otherwise write past the mapping.
    let needed = (width as u64) * (height as u64) * 4 + std::mem::size_of::<OpenCamBridgeFrameHeader>() as u64;
    if needed > MAX_SHM_SIZE as u64 {
        eprintln!(
            "Requested output {}x{} needs {} bytes but the shared framebuffer holds {} bytes (max 1920x1080).",
            width, height, needed, MAX_SHM_SIZE
        );
        std::process::exit(2);
    }

    if let Some(r) = args.rotate {
        if r % 90 != 0 || r >= 360 {
            eprintln!("--rotate must be one of 0, 90, 180, 270 (got {}).", r);
            std::process::exit(2);
        }
    }

    let ipc = SharedMemoryIpc::new().expect("Failed to initialize IPC");
    eprintln!("Framebuffer backend: {}", ipc.backend_name);

    if args.source == "h264" {
        run_h264_scaffold(args, ipc);
        return;
    }

    let mut frame_counter = 0u64;
    let target_duration = Duration::from_secs_f64(1.0 / fps as f64);
    let mut next_frame_deadline = Instant::now();
    let mut last_print = Instant::now();
    let mut output_fps_counter = 0;

    let latest_jpeg = Arc::new(Mutex::new(None));
    let http_jpeg_counter = Arc::new(Mutex::new(0));
    let dropped_jpeg_counter = Arc::new(Mutex::new(0));
    let mjpeg_bytes_counter = Arc::new(Mutex::new(0u64));
    let last_error = Arc::new(Mutex::new(None::<String>));

    if args.source == "mjpeg" {
        let url = args.url.clone().expect("URL is required for mjpeg source");
        start_mjpeg_reader(
            url,
            args.token.clone(),
            latest_jpeg.clone(),
            http_jpeg_counter.clone(),
            dropped_jpeg_counter.clone(),
            mjpeg_bytes_counter.clone(),
            last_error.clone(),
        );
    }

    let mut sum_decode_ms = 0;
    let mut sum_rotate_ms = 0;
    let mut sum_resize_ms = 0;
    let mut sum_write_ms = 0;
    let mut sum_total_ms = 0;
    let mut decoded_fps_counter = 0;
    let mut source_w = 0;
    let mut source_h = 0;

    loop {
        let start_time = Instant::now();
        let mut loop_total_ms = 0;

        if args.source == "test-pattern" {
            let frame = generate_test_pattern(frame_counter, width, height);
            let write_start = Instant::now();
            ipc.write_frame(frame_counter, &frame, width, height);
            sum_write_ms += write_start.elapsed().as_millis() as u32;
            frame_counter += 1;
            output_fps_counter += 1;
            loop_total_ms = start_time.elapsed().as_millis() as u32;
        } else if args.source == "mjpeg" {
            let jpeg_opt = {
                let mut lock = latest_jpeg.lock().unwrap();
                lock.take() // Takes the newest JPEG, leaving None (latest-only)
            };

            if let Some(jpeg_data) = jpeg_opt {
                let decode_start = Instant::now();
                match image::load_from_memory(&jpeg_data) {
                    Ok(img) => {
                        let mut rgba = img.to_rgba8();
                        source_w = rgba.width();
                        source_h = rgba.height();

                        // RGBA -> BGRA in place (framebuffer format is BGRA32).
                        for pixel in rgba.pixels_mut() {
                            let r = pixel[0];
                            pixel[0] = pixel[2];
                            pixel[2] = r;
                        }
                        sum_decode_ms += decode_start.elapsed().as_millis() as u32;

                        let rotate_start = Instant::now();
                        // Explicit --rotate wins; otherwise auto-rotate portrait
                        // sources into landscape outputs (legacy behavior).
                        let rotation = match args.rotate {
                            Some(r) => r % 360,
                            None => {
                                if source_w < source_h && width >= height { 90 } else { 0 }
                            }
                        };
                        let rotated = match rotation {
                            90 => image::imageops::rotate90(&rgba),
                            180 => image::imageops::rotate180(&rgba),
                            270 => image::imageops::rotate270(&rgba),
                            _ => rgba,
                        };
                        // Mirror is applied after rotation so it always means
                        // "flip left/right as seen by the viewer".
                        let oriented = if args.mirror {
                            image::imageops::flip_horizontal(&rotated)
                        } else {
                            rotated
                        };
                        sum_rotate_ms += rotate_start.elapsed().as_millis() as u32;

                        let resize_start = Instant::now();
                        let final_frame = if oriented.width() != width || oriented.height() != height {
                            image::imageops::resize(&oriented, width, height, image::imageops::FilterType::Triangle).into_raw()
                        } else {
                            oriented.into_raw()
                        };
                        sum_resize_ms += resize_start.elapsed().as_millis() as u32;

                        let write_start = Instant::now();
                        ipc.write_frame(frame_counter, &final_frame, width, height);
                        sum_write_ms += write_start.elapsed().as_millis() as u32;

                        frame_counter += 1;
                        output_fps_counter += 1;
                        decoded_fps_counter += 1;
                    }
                    Err(e) => {
                        set_error(&last_error, format!("JPEG decode failed: {}", e));
                    }
                }
                loop_total_ms = start_time.elapsed().as_millis() as u32;
            }
        }
        sum_total_ms += loop_total_ms;

        if last_print.elapsed().as_secs() >= 1 {
            let interval_secs = last_print.elapsed().as_secs_f64().max(0.001);
            let mut http_fps = ((output_fps_counter as f64) / interval_secs).round() as u32;
            let mut dropped_jpegs = 0;
            let mut queue_len = 0;
            let mut mjpeg_bytes = 0;

            if args.source == "mjpeg" {
                let mut lock = http_jpeg_counter.lock().unwrap();
                http_fps = ((*lock as f64) / interval_secs).round() as u32;
                *lock = 0;

                let mut drop_lock = dropped_jpeg_counter.lock().unwrap();
                dropped_jpegs = *drop_lock;
                *drop_lock = 0;

                let jpeg_lock = latest_jpeg.lock().unwrap();
                if jpeg_lock.is_some() { queue_len = 1; }

                let mut bytes_lock = mjpeg_bytes_counter.lock().unwrap();
                mjpeg_bytes = *bytes_lock;
                *bytes_lock = 0;
            }

            let denom = if output_fps_counter > 0 { output_fps_counter } else { 1 };
            let avg_decode = sum_decode_ms / denom;
            let avg_rotate = sum_rotate_ms / denom;
            let avg_resize = sum_resize_ms / denom;
            let avg_write = sum_write_ms / denom;
            let avg_total = sum_total_ms / denom;

            let mbps = (mjpeg_bytes as f64 * 8.0) / 1_000_000.0;
            let decoded_fps_normalized = ((decoded_fps_counter as f64) / interval_secs).round() as u32;
            let written_fps_normalized = ((output_fps_counter as f64) / interval_secs).round() as u32;

            let err_snapshot = { last_error.lock().unwrap().clone() };
            let last_error_json = match &err_snapshot {
                Some(e) => format!("\"{}\"", json_escape(e)),
                None => "null".to_string(),
            };

            println!(r#"{{"source":"{}","profile":"{}","source_width":{},"source_height":{},"output_width":{},"output_height":{},"fps_target":{},"http_jpeg_fps":{},"decoded_fps":{},"written_fps":{},"dropped_jpegs":{},"jpeg_queue_len":{},"decode_ms_avg":{},"rotate_ms_avg":{},"resize_ms_avg":{},"write_ms_avg":{},"total_pipeline_ms":{},"bytes_per_sec":{},"estimated_mbps":"{:.2}","pixel_format":"BGRA32","last_error":{}}}"#,
                args.source, json_escape(&args.profile), source_w, source_h, width, height, fps,
                http_fps, decoded_fps_normalized, written_fps_normalized, dropped_jpegs, queue_len,
                avg_decode, avg_rotate, avg_resize, avg_write, avg_total,
                mjpeg_bytes, mbps, last_error_json
            );

            last_print = Instant::now();
            output_fps_counter = 0;
            decoded_fps_counter = 0;
            sum_decode_ms = 0;
            sum_rotate_ms = 0;
            sum_resize_ms = 0;
            sum_write_ms = 0;
            sum_total_ms = 0;
        }

        next_frame_deadline += target_duration;

        let now = Instant::now();
        if next_frame_deadline > now {
            let remaining = next_frame_deadline - now;

            if remaining > Duration::from_millis(2) {
                sleep(remaining - Duration::from_millis(1));
            }

            while Instant::now() < next_frame_deadline {
                std::thread::yield_now();
            }
        } else {
            // If we are too far behind, reset the clock instead of accumulating lag.
            if now.duration_since(next_frame_deadline) > target_duration {
                next_frame_deadline = now;
            }
        }
    }
}

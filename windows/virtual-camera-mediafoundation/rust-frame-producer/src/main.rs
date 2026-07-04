use clap::Parser;
use std::ffi::c_void;
use std::ptr::{null_mut, copy_nonoverlapping};
use std::time::{Instant, Duration};
use std::thread::{sleep, spawn};
use std::sync::{Arc, Mutex};
use std::sync::mpsc::{sync_channel, Receiver, SyncSender, TrySendError};
use std::io::Read;
use openh264::decoder::Decoder;
use openh264::formats::YUVSource;
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
    /// Frame source: "mjpeg" (stable), "test-pattern", or "h264"
    /// (experimental; decoded with the bundled openh264 decoder)
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

            let sa = SECURITY_ATTRIBUTES {
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

/// Orientation test pattern. Deliberately NOT symmetric so a vertical flip or
/// horizontal mirror is obvious in OBS without needing the phone:
///   - top band RED, bottom band BLUE (detects upside-down)
///   - a GREEN square in the TOP-LEFT corner (detects mirror + which corner is
///     "origin")
///   - a thin moving white scanline so you can tell it is live, not frozen
/// Run: rust-frame-producer --source test-pattern --width 1280 --height 720
/// Correct in OBS = red on top, blue on bottom, green square top-left.
fn generate_test_pattern(frame_counter: u64, width: u32, height: u32) -> Vec<u8> {
    let data_size = (width * height * 4) as usize;
    let mut buf = vec![0u8; data_size];

    let band = height / 3;
    let marker = (width.min(height)) / 6; // top-left corner square
    let scan = (frame_counter % height as u64) as u32; // moving scanline row

    for y in 0..height {
        // BGRA channel values for this row's base colour.
        let (mut b, mut g, mut r) = if y < band {
            (0u8, 0u8, 220u8)          // top: RED
        } else if y >= height - band {
            (220u8, 0u8, 0u8)          // bottom: BLUE
        } else {
            (40u8, 40u8, 40u8)         // middle: dark gray
        };
        if y == scan { b = 255; g = 255; r = 255; } // white scanline

        for x in 0..width {
            let index = ((y * width * 4) + (x * 4)) as usize;
            if y < marker && x < marker {
                buf[index] = 0; buf[index + 1] = 220; buf[index + 2] = 0; // GREEN top-left
            } else {
                buf[index] = b; buf[index + 1] = g; buf[index + 2] = r;
            }
            buf[index + 3] = 255;
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

/// Rotation/mirror/resize applied to a decoded frame before it is written to
/// the shared framebuffer. The pixel data is BGRA stored in an RgbaImage; all
/// operations used here are channel-order agnostic.
struct StageTimings {
    rotate_ms: u32,
    resize_ms: u32,
    write_ms: u32,
}

/// Scales `src` to fit inside `out_w` x `out_h` while preserving its aspect
/// ratio, centered on an opaque black canvas (BGRA). Used when a 90/270
/// rotation leaves portrait content that would otherwise be stretched into a
/// landscape output. No pixels are cropped; unused space becomes black bars.
fn letterbox_into(src: &image::RgbaImage, out_w: u32, out_h: u32) -> Vec<u8> {
    let sw = src.width().max(1) as f64;
    let sh = src.height().max(1) as f64;
    let scale = (out_w as f64 / sw).min(out_h as f64 / sh);
    let new_w = ((sw * scale).round() as u32).clamp(1, out_w);
    let new_h = ((sh * scale).round() as u32).clamp(1, out_h);
    let resized = image::imageops::resize(src, new_w, new_h, image::imageops::FilterType::Triangle);
    let resized_raw = resized.into_raw();

    let mut canvas = vec![0u8; (out_w as usize) * (out_h as usize) * 4];
    // Opaque black background (BGRA: alpha in byte 3).
    for px in canvas.chunks_exact_mut(4) {
        px[3] = 255;
    }

    let off_x = (out_w - new_w) / 2;
    let off_y = (out_h - new_h) / 2;
    let row_bytes = (new_w as usize) * 4;
    for row in 0..new_h as usize {
        let dst = (((off_y as usize + row) * out_w as usize + off_x as usize) * 4) as usize;
        let sptr = row * row_bytes;
        canvas[dst..dst + row_bytes].copy_from_slice(&resized_raw[sptr..sptr + row_bytes]);
    }
    canvas
}

fn orient_resize_write(
    rgba: image::RgbaImage,
    rotate_arg: Option<u32>,
    mirror: bool,
    out_w: u32,
    out_h: u32,
    ipc: &SharedMemoryIpc,
    frame_counter: u64,
) -> StageTimings {
    let src_w = rgba.width();
    let src_h = rgba.height();

    let rotate_start = Instant::now();
    // Explicit --rotate wins; otherwise auto-rotate portrait sources into
    // landscape outputs (legacy behavior).
    let rotation = match rotate_arg {
        Some(r) => r % 360,
        None => {
            if src_w < src_h && out_w >= out_h { 90 } else { 0 }
        }
    };
    let rotated = match rotation {
        90 => image::imageops::rotate90(&rgba),
        180 => image::imageops::rotate180(&rgba),
        270 => image::imageops::rotate270(&rgba),
        _ => rgba,
    };
    // Mirror is applied after rotation so it always means "flip left/right as
    // seen by the viewer".
    let oriented = if mirror {
        image::imageops::flip_horizontal(&rotated)
    } else {
        rotated
    };
    let rotate_ms = rotate_start.elapsed().as_millis() as u32;

    let resize_start = Instant::now();
    // The Media Foundation virtual camera renders a fixed output size, so the
    // frame must land in an `out_w` x `out_h` buffer no matter how it was
    // rotated.
    //
    // - Same orientation as the output box (no rotation, or a rotation that
    //   keeps landscape/portrait): stretch to fill, exactly as before. For a
    //   matching aspect ratio (the stable 16:9 MJPEG case) this is a plain
    //   resize with no visible change.
    // - Orientation flipped by an explicit 90/270 rotation (portrait content in
    //   a landscape box): fit while preserving aspect and pad with black.
    //   Stretching here is what produced the "vertically cropped"/squashed
    //   image; letterboxing rotates correctly without cropping.
    let oriented_landscape = oriented.width() >= oriented.height();
    let box_landscape = out_w >= out_h;
    let final_frame = if oriented_landscape == box_landscape {
        if oriented.width() != out_w || oriented.height() != out_h {
            image::imageops::resize(&oriented, out_w, out_h, image::imageops::FilterType::Triangle).into_raw()
        } else {
            oriented.into_raw()
        }
    } else {
        letterbox_into(&oriented, out_w, out_h)
    };
    let resize_ms = resize_start.elapsed().as_millis() as u32;

    let write_start = Instant::now();
    ipc.write_frame(frame_counter, &final_frame, out_w, out_h);
    let write_ms = write_start.elapsed().as_millis() as u32;

    StageTimings { rotate_ms, resize_ms, write_ms }
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
// H.264 (EXPERIMENTAL — DEVELOPER-ONLY, NOT A V1 RELEASE PATH)
//
// Transport: raw Annex B byte stream from /stream.h264 (see protocol/SPEC.md).
// Decode: bundled openh264 (compiled from source at build time). Decoded
// frames are converted to BGRA and written to the shared framebuffer through
// the same rotation/mirror/resize pipeline as the MJPEG path.
//
// V1 status: MJPEG is the stable public path. H.264 is hidden behind the
// desktop's Developer/Experimental mode and must never auto-start.
//
// TODO (H.264 rewrite, not a V1 blocker): the current per-NAL feed to openh264
// still fails on some Android MediaCodec output with
//   "OpenH264 ... error. Native:16" (dsDataErrorConcealed)
// i.e. reference/data loss the decoder conceals — typically when frames are
// dropped under load (software-decoding 1080p60 in real time is CPU-bound) or
// when the encoder emits a profile/framing the decoder mishandles. A proper fix
// likely needs: (1) access-unit framing (feed a whole AU: SPS+PPS+IDR / all
// slices of a frame together, keyed off AUD or first-VCL detection) instead of
// one NAL per decode call; (2) enforcing Constrained Baseline on the encoder
// (done on the Android side) end-to-end; and/or (3) evaluating a hardware
// (D3D11VA/NVDEC) or more tolerant decoder. Until then, prefer MJPEG.
// ---------------------------------------------------------------------------

#[derive(Default)]
struct H264Stats {
    nal_count: u64,
    sps_seen: bool,
    pps_seen: bool,
    idr_count: u64,
    bytes_total: u64,
    dropped_nals: u64,
}

#[derive(Default)]
struct DecodeStats {
    frames: u32,
    ms_sum: u32,
}

/// Extracts complete NAL units (each including its start code) from `pending`.
/// The trailing, possibly incomplete NAL stays in `pending` for the next chunk.
fn extract_nal_units(pending: &mut Vec<u8>) -> Vec<Vec<u8>> {
    let mut starts: Vec<usize> = Vec::new();
    let mut i = 0usize;
    while i + 2 < pending.len() {
        if pending[i] == 0 && pending[i + 1] == 0 {
            if pending[i + 2] == 1 {
                starts.push(i);
                i += 3;
                continue;
            }
            if i + 3 < pending.len() && pending[i + 2] == 0 && pending[i + 3] == 1 {
                starts.push(i);
                i += 4;
                continue;
            }
        }
        i += 1;
    }

    if starts.len() < 2 {
        return Vec::new();
    }

    let mut units = Vec::with_capacity(starts.len() - 1);
    for w in starts.windows(2) {
        units.push(pending[w[0]..w[1]].to_vec());
    }
    let last = *starts.last().unwrap();
    pending.drain(..last);
    units
}

/// NAL unit type of a start-code-prefixed NAL (0 if malformed).
fn nal_unit_type(nal: &[u8]) -> u8 {
    if nal.len() >= 4 && nal[0] == 0 && nal[1] == 0 && nal[2] == 1 {
        return nal[3] & 0x1F;
    }
    if nal.len() >= 5 && nal[0] == 0 && nal[1] == 0 && nal[2] == 0 && nal[3] == 1 {
        return nal[4] & 0x1F;
    }
    0
}

fn start_h264_reader(
    url: String,
    token: Option<String>,
    tx: SyncSender<Vec<u8>>,
    stats: Arc<Mutex<H264Stats>>,
    last_error: Arc<Mutex<Option<String>>>,
) {
    spawn(move || {
        let mut failures: u32 = 0;
        // When the decoder queue overflows we cannot drop arbitrary NALs
        // (that corrupts the bitstream); we drop everything until the next
        // IDR and resume from that clean keyframe.
        let mut waiting_for_idr = false;

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
                        set_error(&last_error, format!("H.264 stream returned HTTP {}{}", res.status(), hint));
                        failures = failures.saturating_add(1);
                    } else {
                        failures = 0;
                        clear_error(&last_error);

                        let mut buf = [0u8; 32768];
                        let mut pending: Vec<u8> = Vec::new();

                        loop {
                            match res.read(&mut buf) {
                                Ok(0) => {
                                    set_error(&last_error, "H.264 stream ended (server closed connection); reconnecting".to_string());
                                    break;
                                }
                                Ok(n) => {
                                    {
                                        stats.lock().unwrap().bytes_total += n as u64;
                                    }
                                    pending.extend_from_slice(&buf[..n]);

                                    for nal in extract_nal_units(&mut pending) {
                                        let nal_type = nal_unit_type(&nal);
                                        {
                                            let mut s = stats.lock().unwrap();
                                            s.nal_count += 1;
                                            match nal_type {
                                                7 => s.sps_seen = true,
                                                8 => s.pps_seen = true,
                                                5 => s.idr_count += 1,
                                                _ => {}
                                            }
                                        }

                                        // Parameter sets and keyframes end a
                                        // skip period; everything else is
                                        // dropped while we wait for one.
                                        let is_sync_point = matches!(nal_type, 5 | 7 | 8);
                                        if waiting_for_idr && !is_sync_point {
                                            stats.lock().unwrap().dropped_nals += 1;
                                            continue;
                                        }

                                        match tx.try_send(nal) {
                                            Ok(()) => {
                                                if waiting_for_idr && nal_type == 5 {
                                                    waiting_for_idr = false;
                                                }
                                            }
                                            Err(TrySendError::Full(_)) => {
                                                waiting_for_idr = true;
                                                let mut s = stats.lock().unwrap();
                                                s.dropped_nals += 1;
                                                if s.dropped_nals == 1 || s.dropped_nals % 100 == 0 {
                                                    eprintln!("H.264 decoder queue full; dropped {} NAL units so far (resyncing at next keyframe)", s.dropped_nals);
                                                }
                                            }
                                            Err(TrySendError::Disconnected(_)) => {
                                                set_error(&last_error, "H.264 decoder stopped; reader exiting".to_string());
                                                return;
                                            }
                                        }
                                    }

                                    if pending.len() > 4_000_000 {
                                        pending.clear();
                                        waiting_for_idr = true;
                                    }
                                }
                                Err(e) => {
                                    set_error(&last_error, format!("H.264 stream read error: {}; reconnecting", e));
                                    break;
                                }
                            }
                        }
                        // Mid-stream reconnects land at an arbitrary bitstream
                        // position; wait for the next clean sync point.
                        waiting_for_idr = true;
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

fn start_h264_decoder(
    rx: Receiver<Vec<u8>>,
    latest_frame: Arc<Mutex<Option<image::RgbaImage>>>,
    decode_stats: Arc<Mutex<DecodeStats>>,
    last_error: Arc<Mutex<Option<String>>>,
    wake_tx: SyncSender<()>,
) {
    spawn(move || {
        let mut decoder = match Decoder::new() {
            Ok(d) => d,
            Err(e) => {
                set_error(&last_error, format!("H.264 decoder init failed: {}; no frames will be produced", e));
                return;
            }
        };

        while let Ok(nal) = rx.recv() {
            let decode_start = Instant::now();
            match decoder.decode(&nal) {
                Ok(Some(yuv)) => {
                    let (w, h) = yuv.dimensions();
                    if w == 0 || h == 0 {
                        continue;
                    }

                    // Single pass: RGBA out of the decoder, then an in-place
                    // R<->B swap to get the BGRA the framebuffer expects.
                    let mut buf = vec![0u8; w * h * 4];
                    yuv.write_rgba8(&mut buf);
                    for px in buf.chunks_exact_mut(4) {
                        px.swap(0, 2);
                    }

                    if let Some(img) = image::RgbaImage::from_raw(w as u32, h as u32, buf) {
                        {
                            let mut s = decode_stats.lock().unwrap();
                            s.frames += 1;
                            s.ms_sum += decode_start.elapsed().as_millis() as u32;
                        }
                        *latest_frame.lock().unwrap() = Some(img);
                        // Wake the writer immediately instead of letting the
                        // frame wait for the next pacing tick (saves up to a
                        // full frame interval of latency). Capacity-1 channel;
                        // a pending wake already covers this frame.
                        let _ = wake_tx.try_send(());
                        clear_error(&last_error);
                    }
                }
                Ok(None) => {
                    // Parameter set or partial data; no picture yet.
                }
                Err(e) => {
                    // Expected right after a mid-stream (re)connect until the
                    // next keyframe arrives; the decoder recovers on its own.
                    set_error(&last_error, format!("H.264 decode error (recovers at next keyframe): {}", e));
                }
            }
        }
    });
}

/// Dedicated H.264 main loop. Unlike the fixed-tick MJPEG loop, this one is
/// event-driven: the decoder thread wakes it the moment a frame is ready, so
/// end-to-end latency does not include waiting for the next pacing tick.
/// Writes are still paced to the target FPS; every NAL is decoded regardless
/// (the reference chain must stay intact even when frames are not written).
fn run_h264(args: &Args, ipc: &SharedMemoryIpc, width: u32, height: u32, fps: u32) {
    eprintln!("NOTE: --source h264 is experimental. Decoding uses the bundled openh264; MJPEG remains the stable path.");

    let url = args.url.clone().expect("URL is required for h264 source");
    let (nal_tx, nal_rx) = sync_channel::<Vec<u8>>(512);
    let (wake_tx, wake_rx) = sync_channel::<()>(1);

    let latest_frame: Arc<Mutex<Option<image::RgbaImage>>> = Arc::new(Mutex::new(None));
    let stats = Arc::new(Mutex::new(H264Stats::default()));
    let decode_stats = Arc::new(Mutex::new(DecodeStats::default()));
    let last_error = Arc::new(Mutex::new(None::<String>));

    start_h264_reader(url, args.token.clone(), nal_tx, stats.clone(), last_error.clone());
    start_h264_decoder(nal_rx, latest_frame.clone(), decode_stats.clone(), last_error.clone(), wake_tx);

    let min_write_interval = Duration::from_secs_f64(1.0 / fps as f64);
    // 10% tolerance so normal jitter does not halve the effective rate.
    let pace_threshold = min_write_interval.mul_f64(0.9);
    let mut last_write = Instant::now() - min_write_interval;
    let mut last_print = Instant::now();
    let mut decoder_alive = true;

    let mut frame_counter = 0u64;
    let mut written_counter: u32 = 0;
    let mut sum_rotate_ms: u32 = 0;
    let mut sum_resize_ms: u32 = 0;
    let mut sum_write_ms: u32 = 0;
    let mut source_w: u32 = 0;
    let mut source_h: u32 = 0;

    loop {
        let next_print = last_print + Duration::from_secs(1);

        if decoder_alive {
            let timeout = next_print.saturating_duration_since(Instant::now());
            match wake_rx.recv_timeout(timeout) {
                Ok(()) => {}
                Err(std::sync::mpsc::RecvTimeoutError::Timeout) => {}
                Err(std::sync::mpsc::RecvTimeoutError::Disconnected) => {
                    // Decoder thread is gone (init failure); keep printing
                    // metrics so the desktop shows the error.
                    decoder_alive = false;
                }
            }
        } else {
            let wait = next_print
                .saturating_duration_since(Instant::now())
                .min(Duration::from_millis(100));
            if wait > Duration::ZERO {
                sleep(wait);
            }
        }

        // Pace-check first: when we are ahead of schedule the frame stays in
        // the slot (possibly replaced by a newer one) and is picked up by a
        // later wake or the metrics tick, so the last frame of a burst is
        // never lost.
        if last_write.elapsed() >= pace_threshold {
            let frame_opt = { latest_frame.lock().unwrap().take() };
            if let Some(rgba) = frame_opt {
                source_w = rgba.width();
                source_h = rgba.height();
                let timings = orient_resize_write(rgba, args.rotate, args.mirror, width, height, ipc, frame_counter);
                sum_rotate_ms += timings.rotate_ms;
                sum_resize_ms += timings.resize_ms;
                sum_write_ms += timings.write_ms;
                frame_counter += 1;
                written_counter += 1;
                last_write = Instant::now();
            }
        }

        if Instant::now() >= next_print {
            let interval_secs = last_print.elapsed().as_secs_f64().max(0.001);

            let source_bytes = {
                let mut s = stats.lock().unwrap();
                let b = s.bytes_total;
                s.bytes_total = 0;
                b
            };
            let (decoded_fps, decode_ms_avg) = {
                let mut d = decode_stats.lock().unwrap();
                let f = ((d.frames as f64) / interval_secs).round() as u32;
                let avg = if d.frames > 0 { d.ms_sum / d.frames } else { 0 };
                d.frames = 0;
                d.ms_sum = 0;
                (f, avg)
            };
            let queue_len: u32 = if latest_frame.lock().unwrap().is_some() { 1 } else { 0 };

            let denom = if written_counter > 0 { written_counter } else { 1 };
            let avg_rotate = sum_rotate_ms / denom;
            let avg_resize = sum_resize_ms / denom;
            let avg_write = sum_write_ms / denom;
            let avg_total = decode_ms_avg + avg_rotate + avg_resize + avg_write;

            let written_fps = ((written_counter as f64) / interval_secs).round() as u32;
            let mbps = (source_bytes as f64 * 8.0) / 1_000_000.0;

            let err_snapshot = { last_error.lock().unwrap().clone() };
            let last_error_json = match &err_snapshot {
                Some(e) => format!("\"{}\"", json_escape(e)),
                None => "null".to_string(),
            };

            println!(r#"{{"source":"h264","profile":"{}","source_width":{},"source_height":{},"output_width":{},"output_height":{},"fps_target":{},"http_jpeg_fps":0,"decoded_fps":{},"written_fps":{},"dropped_jpegs":0,"jpeg_queue_len":{},"decode_ms_avg":{},"rotate_ms_avg":{},"resize_ms_avg":{},"write_ms_avg":{},"total_pipeline_ms":{},"bytes_per_sec":{},"estimated_mbps":"{:.2}","pixel_format":"BGRA32","last_error":{}}}"#,
                json_escape(&args.profile), source_w, source_h, width, height, fps,
                decoded_fps, written_fps, queue_len,
                decode_ms_avg, avg_rotate, avg_resize, avg_write, avg_total,
                source_bytes, mbps, last_error_json
            );

            last_print = Instant::now();
            written_counter = 0;
            sum_rotate_ms = 0;
            sum_resize_ms = 0;
            sum_write_ms = 0;
        }
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
        // Event-driven loop, fully separate from the MJPEG/test-pattern path.
        run_h264(&args, &ipc, width, height, fps);
        return;
    }

    let mut frame_counter = 0u64;
    let target_duration = Duration::from_secs_f64(1.0 / fps as f64);
    let mut next_frame_deadline = Instant::now();
    let mut last_print = Instant::now();
    let mut output_fps_counter = 0;

    // MJPEG source state
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

                        let timings = orient_resize_write(rgba, args.rotate, args.mirror, width, height, &ipc, frame_counter);
                        sum_rotate_ms += timings.rotate_ms;
                        sum_resize_ms += timings.resize_ms;
                        sum_write_ms += timings.write_ms;

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
            let mut source_bytes = 0u64;

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
                source_bytes = *bytes_lock;
                *bytes_lock = 0;
            }

            let denom = if output_fps_counter > 0 { output_fps_counter } else { 1 };
            let avg_decode = sum_decode_ms / denom;
            let avg_rotate = sum_rotate_ms / denom;
            let avg_resize = sum_resize_ms / denom;
            let avg_write = sum_write_ms / denom;
            let avg_total = sum_total_ms / denom;

            let mbps = (source_bytes as f64 * 8.0) / 1_000_000.0;
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
                source_bytes, mbps, last_error_json
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

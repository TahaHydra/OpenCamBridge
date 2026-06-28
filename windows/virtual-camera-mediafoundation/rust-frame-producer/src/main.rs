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
                let header_ptr = self.p_map as *mut OpenCamBridgeFrameHeader;
                (*header_ptr).magic = OCBF_MAGIC;
                (*header_ptr).version = 1;
                (*header_ptr).width = width;
                (*header_ptr).height = height;
                (*header_ptr).stride = stride;
                (*header_ptr).format = FORMAT_BGRA32;
                (*header_ptr).frame_counter = frame_counter;
                (*header_ptr).timestamp_qpc = 0;
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

fn start_mjpeg_reader(url: String, latest_jpeg: Arc<Mutex<Option<Vec<u8>>>>, http_jpeg_counter: Arc<Mutex<u32>>, dropped_jpeg_counter: Arc<Mutex<u32>>, mjpeg_bytes_counter: Arc<Mutex<u64>>) {
    spawn(move || {
        loop {
            let client = reqwest::blocking::Client::builder()
                .timeout(Duration::from_secs(5))
                .build()
                .unwrap();

            match client.get(&url).send() {
                Ok(mut res) => {
                    let mut buf = [0u8; 32768];
                    let mut frame_buffer = Vec::new();

                    loop {
                        match res.read(&mut buf) {
                            Ok(0) => break,
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
                            Err(_) => break,
                        }
                    }
                }
                Err(e) => {
                    // eprintln!("Failed to connect to MJPEG stream: {}", e);
                }
            }
            sleep(Duration::from_secs(1));
        }
    });
}

fn main() {
    let args = Args::parse();
    
    let defaults = match args.profile.as_str() {
        "low-latency" => (960, 540, 30, 70),
        "balanced" => (1280, 720, 30, 85),
        "quality" => (1920, 1080, 30, 90),
        "experimental-1080p60" => (1920, 1080, 60, 85),
        _ => (1280, 720, 30, 85),
    };

    let width = args.width.unwrap_or(defaults.0);
    let height = args.height.unwrap_or(defaults.1);
    let fps = args.fps.unwrap_or(defaults.2);
    let _quality = args.quality.unwrap_or(defaults.3); // Kept for completeness

    let ipc = SharedMemoryIpc::new().expect("Failed to initialize IPC");

    let mut frame_counter = 0u64;
    let target_duration = Duration::from_millis(1000 / fps as u64);
    let mut last_print = Instant::now();
    let mut output_fps_counter = 0;

    let latest_jpeg = Arc::new(Mutex::new(None));
    let http_jpeg_counter = Arc::new(Mutex::new(0));
    let dropped_jpeg_counter = Arc::new(Mutex::new(0));
    let mjpeg_bytes_counter = Arc::new(Mutex::new(0u64));

    if args.source == "mjpeg" {
        let url = args.url.clone().expect("URL is required for mjpeg source");
        start_mjpeg_reader(url, latest_jpeg.clone(), http_jpeg_counter.clone(), dropped_jpeg_counter.clone(), mjpeg_bytes_counter.clone());
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
                if let Ok(img) = image::load_from_memory(&jpeg_data) {
                    let mut rgba = img.to_rgba8();
                    source_w = rgba.width();
                    source_h = rgba.height();

                    for pixel in rgba.pixels_mut() {
                        let r = pixel[0];
                        pixel[0] = pixel[2];
                        pixel[2] = r;
                    }
                    sum_decode_ms += decode_start.elapsed().as_millis() as u32;

                    let rotate_start = Instant::now();
                    let needs_rotate = source_w < source_h && width >= height;
                    let rotated_opt = if needs_rotate {
                        Some(image::imageops::rotate90(&rgba))
                    } else {
                        None
                    };
                    let current_w = if needs_rotate { source_h } else { source_w };
                    let current_h = if needs_rotate { source_w } else { source_h };
                    let current_ref = if let Some(ref r) = rotated_opt { r } else { &rgba };
                    sum_rotate_ms += rotate_start.elapsed().as_millis() as u32;

                    let resize_start = Instant::now();
                    let final_frame = if current_w != width || current_h != height {
                        let resized = image::imageops::resize(current_ref, width, height, image::imageops::FilterType::Triangle);
                        resized.into_raw()
                    } else {
                        if let Some(r) = rotated_opt { r.into_raw() } else { rgba.into_raw() }
                    };
                    sum_resize_ms += resize_start.elapsed().as_millis() as u32;

                    let write_start = Instant::now();
                    ipc.write_frame(frame_counter, &final_frame, width, height);
                    sum_write_ms += write_start.elapsed().as_millis() as u32;
                    
                    frame_counter += 1;
                    output_fps_counter += 1;
                    decoded_fps_counter += 1;
                }
                loop_total_ms = start_time.elapsed().as_millis() as u32;
            }
        }
        sum_total_ms += loop_total_ms;

        if last_print.elapsed().as_secs() >= 1 {
            let mut http_fps = output_fps_counter;
            let mut dropped_jpegs = 0;
            let mut queue_len = 0;
            let mut mjpeg_bytes = 0;

            if args.source == "mjpeg" {
                let mut lock = http_jpeg_counter.lock().unwrap();
                http_fps = *lock;
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

            println!(r#"{{"source":"{}","profile":"{}","source_width":{},"source_height":{},"output_width":{},"output_height":{},"fps_target":{},"http_jpeg_fps":{},"decoded_fps":{},"written_fps":{},"dropped_jpegs":{},"jpeg_queue_len":{},"decode_ms_avg":{},"rotate_ms_avg":{},"resize_ms_avg":{},"write_ms_avg":{},"total_pipeline_ms":{},"bytes_per_sec":{},"estimated_mbps":"{:.2}","pixel_format":"BGRA32","last_error":null}}"#, 
                args.source, args.profile, source_w, source_h, width, height, fps,
                http_fps, decoded_fps_counter, output_fps_counter, dropped_jpegs, queue_len,
                avg_decode, avg_rotate, avg_resize, avg_write, avg_total,
                mjpeg_bytes, mbps
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

        let elapsed = start_time.elapsed();
        if elapsed < target_duration {
            sleep(target_duration - elapsed);
        }
    }
}

use clap::{Parser, ValueEnum};
use image::{ImageFormat, imageops::FilterType};
use serde::Serialize;
use std::io::Read;
use std::time::{Duration, Instant};
use virtualcam::{Camera, PixelFormat};

#[derive(Parser, Debug)]
#[command(author, version, about, long_about = None)]
struct Args {
    #[arg(long, default_value = "test-pattern")]
    source: SourceMode,

    #[arg(long, default_value = "http://127.0.0.1:8080/stream.mjpeg")]
    url: String,

    #[arg(long, default_value_t = 1280)]
    width: u32,

    #[arg(long, default_value_t = 720)]
    height: u32,

    #[arg(long, default_value_t = 30.0)]
    fps: f64,

    #[arg(long, default_value = "OpenCamBridge Camera")]
    device: String,

    #[arg(long)]
    json: bool,
}

#[derive(Copy, Clone, PartialEq, Eq, PartialOrd, Ord, ValueEnum, Debug)]
enum SourceMode {
    TestPattern,
    Mjpeg,
}

#[derive(Serialize)]
struct Metrics {
    input_fps: u32,
    output_fps: u32,
    decode_ms_avg: f32,
    bytes_per_sec: usize,
    dropped_frames: u32,
    frame_age_ms: u128,
    status: &'static str,
    pixel_format: &'static str,
    stride: usize,
    frame_len: usize,
}

fn main() {
    let args = Args::parse();

    if !args.json {
        println!("Initializing OpenCamBridge Virtual Camera Feeder...");
    }

    let cam = match Camera::builder(args.width, args.height, args.fps)
        .format(PixelFormat::RGBA)
        .device(&args.device)
        .backend("unitycapture")
        .build()
    {
        Ok(c) => c,
        Err(e) => {
            eprintln!("OpenCamBridge Camera backend not registered. Run register.ps1 first.");
            eprintln!("Detailed error: {:?}", e);
            std::process::exit(1);
        }
    };

    if !args.json {
        let expected_stride = args.width * 4;
        let expected_len = args.width * args.height * 4;
        println!("Successfully connected to device: {}", cam.device());
        println!("virtualcam backend selected: unitycapture");
        println!("camera name requested: {}", args.device);
        println!("pixel format: RGBA32");
        println!("bytes per pixel: 4");
        println!("width: {}", args.width);
        println!("height: {}", args.height);
        println!("fps: {}", args.fps);
        println!("stride: {}", expected_stride);
        println!("frame_len: {}", expected_len);
    }

    match args.source {
        SourceMode::TestPattern => run_test_pattern(cam, args.width, args.height, args.json),
        SourceMode::Mjpeg => run_mjpeg(cam, &args.url, args.width, args.height, args.json),
    }
}

fn run_test_pattern(mut cam: Camera, width: u32, height: u32, json_mode: bool) {
    if !json_mode {
        println!("Sending {}x{} RGBA moving pattern. Press Ctrl+C to stop.", width, height);
    }
    let expected_len = (width * height * 4) as usize;
    let mut frame = vec![0u8; expected_len];
    let start_time = Instant::now();

    loop {
        let elapsed = start_time.elapsed().as_secs_f32();
        let shift = (elapsed * 50.0) as usize % width as usize;

        for y in 0..height {
            for x in 0..width {
                let i = ((y * width + x) * 4) as usize;
                let r = ((x as usize + shift) % 255) as u8;
                let g = ((y as usize + shift) % 255) as u8;
                let b = 150u8;

                frame[i] = r;
                frame[i + 1] = g;
                frame[i + 2] = b;
                frame[i + 3] = 255;
            }
        }

        assert_eq!(frame.len(), expected_len);

        if let Err(e) = cam.send(&frame) {
            eprintln!("Failed to send frame: {:?}", e);
            break;
        }

        cam.sleep_until_next_frame();
    }
}

fn run_mjpeg(mut cam: Camera, url: &str, target_width: u32, target_height: u32, json_mode: bool) {
    if !json_mode {
        println!("Connecting to MJPEG stream at {}...", url);
    }

    let mut last_metric_time = Instant::now();
    let mut frames_decoded = 0;
    let mut frames_pushed = 0;
    let mut bytes_received = 0;
    let mut dropped_frames = 0;
    let mut decode_times = vec![];
    let mut frame_age = Duration::ZERO;

    loop {
        let req = match ureq::get(url).call() {
            Ok(resp) => resp,
            Err(e) => {
                eprintln!("Android MJPEG stream unavailable. Retrying in 2 seconds... ({})", e);
                std::thread::sleep(Duration::from_secs(2));
                continue;
            }
        };

        let mut reader = req.into_body().into_reader();
        let mut buffer = Vec::new();
        let mut chunk = [0u8; 8192];
        
        loop {
            // Metrics logging once per second
            if last_metric_time.elapsed() >= Duration::from_secs(1) {
                let avg_decode = if decode_times.is_empty() { 0.0 } else { 
                    decode_times.iter().sum::<f32>() / decode_times.len() as f32 
                };
                
                if json_mode {
                    let m = Metrics {
                        input_fps: frames_decoded,
                        output_fps: frames_pushed,
                        decode_ms_avg: avg_decode,
                        bytes_per_sec: bytes_received,
                        dropped_frames,
                        frame_age_ms: frame_age.as_millis(),
                        status: "OK",
                        pixel_format: "RGBA32",
                        stride: (target_width * 4) as usize,
                        frame_len: (target_width * target_height * 4) as usize,
                    };
                    if let Ok(json_str) = serde_json::to_string(&m) {
                        println!("{}", json_str);
                    }
                } else {
                    println!("metrics | input_fps: {} | output_fps: {} | decode_ms_avg: {:.1} | bytes/sec: {} | dropped: {} | frame_age_ms: {} | vcam_status: OK",
                        frames_decoded, frames_pushed, avg_decode, bytes_received, dropped_frames, frame_age.as_millis());
                }
                
                frames_decoded = 0;
                frames_pushed = 0;
                bytes_received = 0;
                decode_times.clear();
                last_metric_time = Instant::now();
            }

            match reader.read(&mut chunk) {
                Ok(0) => {
                    eprintln!("Stream closed by server. Reconnecting in 2 seconds...");
                    break;
                }
                Ok(n) => {
                    bytes_received += n;
                    buffer.extend_from_slice(&chunk[..n]);

                    // Look for JPEG SOI (0xFF, 0xD8) and EOI (0xFF, 0xD9)
                    while let Some(soi_idx) = buffer.windows(2).position(|w| w == [0xFF, 0xD8]) {
                        if let Some(eoi_offset) = buffer[soi_idx..].windows(2).position(|w| w == [0xFF, 0xD9]) {
                            let eoi_idx = soi_idx + eoi_offset + 2;
                            let jpeg_bytes = &buffer[soi_idx..eoi_idx];

                            // Decode JPEG
                            let decode_start = Instant::now();
                            if let Ok(img) = image::load_from_memory_with_format(jpeg_bytes, ImageFormat::Jpeg) {
                                let mut rgba_img = img.into_rgba8();
                                
                                // Resize if needed
                                if rgba_img.width() != target_width || rgba_img.height() != target_height {
                                    rgba_img = image::imageops::resize(&rgba_img, target_width, target_height, FilterType::Triangle);
                                }

                                let expected_len = (target_width * target_height * 4) as usize;
                                if rgba_img.as_raw().len() != expected_len {
                                    eprintln!("Frame size mismatch: got {}, expected {}", rgba_img.as_raw().len(), expected_len);
                                    dropped_frames += 1;
                                    buffer.drain(..eoi_idx);
                                    continue;
                                }
                                
                                let decode_time = decode_start.elapsed();
                                decode_times.push(decode_time.as_secs_f32() * 1000.0);
                                frames_decoded += 1;
                                frame_age = decode_time;

                                // Push to virtual camera
                                if let Err(e) = cam.send(rgba_img.as_raw()) {
                                    eprintln!("Failed to push frame to Virtual Camera: {:?}", e);
                                    dropped_frames += 1;
                                } else {
                                    frames_pushed += 1;
                                }
                            } else {
                                dropped_frames += 1;
                            }

                            // Remove processed frame from buffer
                            buffer.drain(..eoi_idx);
                        } else {
                            // Waiting for EOI
                            break;
                        }
                    }
                    
                    // Prevent memory unbounded growth if stream is corrupted
                    if buffer.len() > 10 * 1024 * 1024 {
                        eprintln!("Buffer grew too large without finding JPEG boundaries. Flushing.");
                        buffer.clear();
                    }
                }
                Err(e) => {
                    eprintln!("Error reading stream: {}. Reconnecting in 2 seconds...", e);
                    break;
                }
            }
        }
        std::thread::sleep(Duration::from_secs(2));
    }
}

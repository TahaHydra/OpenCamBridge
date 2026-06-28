use serde::{Deserialize, Serialize};
use std::io::{BufRead, BufReader};
use std::process::{Child, Command, Stdio};
use std::sync::Mutex;
use tauri::{AppHandle, Manager, State};

#[derive(Default, Serialize, Deserialize, Clone)]
pub struct VirtualCamMetrics {
    pub source: String,
    pub profile: String,
    pub source_width: u32,
    pub source_height: u32,
    pub output_width: u32,
    pub output_height: u32,
    pub fps_target: u32,
    pub http_jpeg_fps: u32,
    pub decoded_fps: u32,
    pub written_fps: u32,
    pub dropped_jpegs: u32,
    pub jpeg_queue_len: u32,
    pub decode_ms_avg: u32,
    pub resize_ms_avg: u32,
    pub write_ms_avg: u32,
    pub total_pipeline_ms: u32,
    pub bytes_per_sec: usize,
    pub pixel_format: String,
    pub last_error: Option<String>,
}

#[derive(Serialize, Clone)]
pub struct VirtualCamState {
    pub running: bool,
    pub host_running: bool,
    pub registered: bool,
    pub metrics: Option<VirtualCamMetrics>,
    pub producer_path: Option<String>,
    pub producer_exists: bool,
    pub producer_pid: Option<u32>,
    pub last_error: Option<String>,
    pub last_metrics_time: Option<u64>,
}

pub struct VirtualCamManager {
    child: Mutex<Option<Child>>,
    host_child: Mutex<Option<Child>>,
    metrics: Mutex<Option<VirtualCamMetrics>>,
    producer_path: Mutex<Option<String>>,
    last_error: Mutex<Option<String>>,
    last_metrics_time: Mutex<Option<u64>>,
}

impl VirtualCamManager {
    pub fn new() -> Self {
        Self {
            child: Mutex::new(None),
            host_child: Mutex::new(None),
            metrics: Mutex::new(None),
            producer_path: Mutex::new(None),
            last_error: Mutex::new(None),
            last_metrics_time: Mutex::new(None),
        }
    }
}

#[tauri::command]
pub fn check_virtual_camera_backend() -> bool {
    let hklm = winreg::RegKey::predef(winreg::enums::HKEY_LOCAL_MACHINE);
    let path = r#"Software\Classes\CLSID\{8CF75B14-3F68-46BC-80DF-5FB86AED931E}"#;
    hklm.open_subkey(path).is_ok()
}

#[tauri::command]
pub fn register_virtual_camera_backend() -> Result<String, String> {
    // Requires Admin, currently not supported from Tauri UI directly.
    Err("Please use the VirtualCamera_Installer.exe to register the camera manually for the MVP.".to_string())
}

#[tauri::command]
pub fn start_virtual_camera_host(state: State<'_, VirtualCamManager>) -> Result<(), String> {
    let mut host_guard = state.host_child.lock().unwrap();
    if host_guard.is_some() {
        return Ok(()); // Already running
    }

    let mut repo_root = std::env::current_dir().unwrap();
    while !repo_root.join("windows").exists() && repo_root.parent().is_some() {
        repo_root = repo_root.parent().unwrap().to_path_buf();
    }

    let exe_path_release = repo_root.join("windows/virtual-camera-mediafoundation/VirtualCamera_Installer/x64/Release/VirtualCamera_Installer.exe");
    let exe_path = std::fs::canonicalize(&exe_path_release).unwrap_or(exe_path_release);

    let child = Command::new(exe_path)
        .arg("--mode")
        .arg("host")
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|e| e.to_string())?;

    *host_guard = Some(child);
    Ok(())
}

#[tauri::command]
pub fn stop_virtual_camera_host(state: State<'_, VirtualCamManager>) -> Result<(), String> {
    let mut host_guard = state.host_child.lock().unwrap();
    if let Some(mut child) = host_guard.take() {
        let _ = child.kill();
        let _ = child.wait();
    }
    Ok(())
}

#[tauri::command]
pub fn start_virtual_camera_feeder(
    app: AppHandle,
    state: State<'_, VirtualCamManager>,
    url: String,
    width: u32,
    height: u32,
    fps: f64,
    quality: Option<u32>,
    profile: Option<String>,
) -> Result<(), String> {
    println!(">>> [Tauri] start_virtual_camera_feeder called with width={}, height={}, fps={}", width, height, fps);
    let mut child_guard = state.child.lock().unwrap();
    if let Some(mut child) = child_guard.take() {
        println!(">>> [Tauri] Found existing producer (PID {}). Stopping it.", child.id());
        let _ = child.kill();
        let _ = child.wait();
        *state.last_error.lock().unwrap() = Some(format!("Killed previous producer for restart"));
        *state.metrics.lock().unwrap() = None;
        *state.last_metrics_time.lock().unwrap() = None;
    }

    let mut repo_root = std::env::current_dir().unwrap();
    while !repo_root.join("windows").exists() && repo_root.parent().is_some() {
        repo_root = repo_root.parent().unwrap().to_path_buf();
    }
    
    let exe_path_release = repo_root.join("windows/virtual-camera-mediafoundation/rust-frame-producer/target/release/rust-frame-producer.exe");
    let exe_path_debug = repo_root.join("windows/virtual-camera-mediafoundation/rust-frame-producer/target/debug/rust-frame-producer.exe");

    println!(">>> [Tauri] Checking path: {:?}", exe_path_release);
    println!(">>> [Tauri] Path exists? {}", exe_path_release.exists());

    let exe_path = if exe_path_release.exists() {
        exe_path_release
    } else if exe_path_debug.exists() {
        exe_path_debug
    } else {
        println!(">>> [Tauri] rust-frame-producer.exe not found.");
        return Err(format!("rust-frame-producer.exe not found. Run cargo build --release in rust-frame-producer."));
    };

    let exe_path = std::fs::canonicalize(&exe_path).unwrap_or(exe_path);
    let path_string = exe_path.to_string_lossy().to_string();

    let mut cmd = Command::new(exe_path);
    cmd.arg("--source").arg("mjpeg")
       .arg("--url").arg(&url)
       .arg("--width").arg(width.to_string())
       .arg("--height").arg(height.to_string())
       .arg("--fps").arg(fps.to_string())
       .arg("--latest-only");
       
    if let Some(q) = quality {
        cmd.arg("--quality").arg(q.to_string());
    }

    if let Some(p) = profile {
        cmd.arg("--profile").arg(p);
    }

    println!(">>> [Tauri] Executing exactly: {:?}", cmd);

    let mut child = match cmd
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn() {
            Ok(c) => c,
            Err(e) => {
                println!(">>> [Tauri] Spawn failed: {}", e);
                let mut err_guard = state.last_error.lock().unwrap();
                *err_guard = Some(e.to_string());
                return Err(e.to_string());
            }
        };

    println!(">>> [Tauri] Spawned rust-frame-producer with PID: {}", child.id());

    let stdout = child.stdout.take().unwrap();
    let stderr = child.stderr.take().unwrap();
    let app_clone = app.clone();
    let app_clone_err = app.clone();

    // STDOUT drain thread
    std::thread::spawn(move || {
        let reader = BufReader::new(stdout);
        for line in reader.lines() {
            if let Ok(line) = line {
                if let Ok(metrics) = serde_json::from_str::<VirtualCamMetrics>(&line) {
                    let state_manager = app_clone.state::<VirtualCamManager>();
                    let mut metrics_guard = state_manager.metrics.lock().unwrap();
                    *metrics_guard = Some(metrics);
                    
                    let mut time_guard = state_manager.last_metrics_time.lock().unwrap();
                    *time_guard = Some(std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_secs());
                } else {
                    println!(">>> [Producer STDOUT] {}", line);
                }
            }
        }
        println!(">>> [Tauri] STDOUT thread exiting.");
    });

    // STDERR drain thread
    std::thread::spawn(move || {
        let reader = BufReader::new(stderr);
        for line in reader.lines() {
            if let Ok(line) = line {
                println!(">>> [Producer STDERR] {}", line);
                let state_manager = app_clone_err.state::<VirtualCamManager>();
                let mut err_guard = state_manager.last_error.lock().unwrap();
                *err_guard = Some(line.clone());
            }
        }
        println!(">>> [Tauri] STDERR thread exiting.");
    });

    let mut path_guard = state.producer_path.lock().unwrap();
    *path_guard = Some(path_string);
    
    let mut err_guard = state.last_error.lock().unwrap();
    *err_guard = None;

    *child_guard = Some(child);
    Ok(())
}

#[tauri::command]
pub fn stop_virtual_camera_feeder(state: State<'_, VirtualCamManager>) -> Result<(), String> {
    let mut child_guard = state.child.lock().unwrap();
    if let Some(mut child) = child_guard.take() {
        let _ = child.kill();
        let _ = child.wait();
    }
    
    let mut metrics_guard = state.metrics.lock().unwrap();
    *metrics_guard = None;
    
    let mut time_guard = state.last_metrics_time.lock().unwrap();
    *time_guard = None;
    
    Ok(())
}

#[tauri::command]
pub fn get_virtual_camera_status(state: State<'_, VirtualCamManager>) -> VirtualCamState {
    let mut child_guard = state.child.lock().unwrap();
    
    let mut running = false;
    let mut producer_pid = None;
    
    if let Some(child) = child_guard.as_mut() {
        match child.try_wait() {
            Ok(Some(status)) => {
                // Process exited
                let mut err_guard = state.last_error.lock().unwrap();
                let current_err = err_guard.clone().unwrap_or_default();
                if !current_err.contains(&status.to_string()) {
                    *err_guard = Some(format!("Exited with {}. {}", status, current_err));
                }
                *state.metrics.lock().unwrap() = None;
                *state.last_metrics_time.lock().unwrap() = None;
            }
            Ok(None) => {
                // Still running
                running = true;
                producer_pid = Some(child.id());
            }
            Err(_) => {}
        }
    }
    
    if !running && child_guard.is_some() {
        *child_guard = None;
    }
    
    let host_running = state.host_child.lock().unwrap().is_some();
    let mut metrics = state.metrics.lock().unwrap().clone();
    let registered = check_virtual_camera_backend();
    let producer_path = state.producer_path.lock().unwrap().clone();
    let last_error = state.last_error.lock().unwrap().clone();
    let last_metrics_time = state.last_metrics_time.lock().unwrap().clone();

    if let Some(last_time) = last_metrics_time {
        let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_secs();
        if now > last_time + 3 {
            metrics = None;
            *state.metrics.lock().unwrap() = None;
        }
    }

    let mut repo_root = std::env::current_dir().unwrap();
    while !repo_root.join("windows").exists() && repo_root.parent().is_some() {
        repo_root = repo_root.parent().unwrap().to_path_buf();
    }
    let exe_path_release = repo_root.join("windows/virtual-camera-mediafoundation/rust-frame-producer/target/release/rust-frame-producer.exe");
    let producer_exists = exe_path_release.exists() || repo_root.join("windows/virtual-camera-mediafoundation/rust-frame-producer/target/debug/rust-frame-producer.exe").exists();

    VirtualCamState {
        running,
        host_running,
        registered,
        metrics,
        producer_path,
        producer_exists,
        producer_pid,
        last_error,
        last_metrics_time,
    }
}

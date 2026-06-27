use serde::{Deserialize, Serialize};
use std::io::{BufRead, BufReader};
use std::process::{Child, Command, Stdio};
use std::sync::Mutex;
use tauri::{AppHandle, Manager, State};

#[derive(Default, Serialize, Deserialize, Clone)]
pub struct VirtualCamMetrics {
    pub input_fps: u32,
    pub output_fps: u32,
    pub decode_ms_avg: f32,
    pub bytes_per_sec: usize,
    pub dropped_frames: u32,
    pub frame_age_ms: u128,
    pub status: String,
}

#[derive(Serialize, Clone)]
pub struct VirtualCamState {
    pub running: bool,
    pub registered: bool,
    pub metrics: Option<VirtualCamMetrics>,
}

pub struct VirtualCamManager {
    child: Mutex<Option<Child>>,
    metrics: Mutex<Option<VirtualCamMetrics>>,
}

impl VirtualCamManager {
    pub fn new() -> Self {
        Self {
            child: Mutex::new(None),
            metrics: Mutex::new(None),
        }
    }
}

#[tauri::command]
pub fn check_virtual_camera_backend() -> bool {
    let hkcu = winreg::RegKey::predef(winreg::enums::HKEY_CURRENT_USER);
    let path = r#"Software\Classes\CLSID\{5C2CD55C-92AD-4999-8666-912BD3E70010}"#;
    hkcu.open_subkey(path).is_ok()
}

#[tauri::command]
pub fn register_virtual_camera_backend() -> Result<String, String> {
    let script_path = std::env::current_dir()
        .unwrap()
        .join("../../../windows/virtual-camera-directshow/register.ps1");
    
    let status = Command::new("powershell")
        .arg("-ExecutionPolicy")
        .arg("Bypass")
        .arg("-File")
        .arg(script_path)
        .status()
        .map_err(|e| e.to_string())?;

    if status.success() {
        Ok("Registered successfully".to_string())
    } else {
        Err("Failed to register".to_string())
    }
}

#[tauri::command]
pub fn start_virtual_camera_feeder(
    app: AppHandle,
    state: State<'_, VirtualCamManager>,
    url: String,
    width: u32,
    height: u32,
    fps: f64,
) -> Result<(), String> {
    let mut child_guard = state.child.lock().unwrap();
    if child_guard.is_some() {
        return Ok(()); // Already running
    }

    let exe_path_release = std::env::current_dir()
        .unwrap()
        .join("../../../windows/virtual-camera-directshow/rust-feeder/target/release/rust-feeder.exe");
        
    let exe_path_debug = std::env::current_dir()
        .unwrap()
        .join("../../../windows/virtual-camera-directshow/rust-feeder/target/debug/rust-feeder.exe");

    let exe_path = if exe_path_release.exists() {
        exe_path_release
    } else if exe_path_debug.exists() {
        exe_path_debug
    } else {
        return Err(format!("Feeder binary not found. Checked:\n- {:?}\n- {:?}", exe_path_release, exe_path_debug));
    };

    let exe_path = std::fs::canonicalize(&exe_path).unwrap_or(exe_path);

    let mut child = Command::new(exe_path)
        .arg("--source")
        .arg("mjpeg")
        .arg("--url")
        .arg(url)
        .arg("--width")
        .arg(width.to_string())
        .arg("--height")
        .arg(height.to_string())
        .arg("--fps")
        .arg(fps.to_string())
        .arg("--json")
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|e| e.to_string())?;

    let stdout = child.stdout.take().unwrap();
    let app_clone = app.clone();

    std::thread::spawn(move || {
        let reader = BufReader::new(stdout);
        for line in reader.lines() {
            if let Ok(line) = line {
                if let Ok(metrics) = serde_json::from_str::<VirtualCamMetrics>(&line) {
                    let state_manager = app_clone.state::<VirtualCamManager>();
                    let mut metrics_guard = state_manager.metrics.lock().unwrap();
                    *metrics_guard = Some(metrics);
                }
            }
        }
    });

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
    
    Ok(())
}

#[tauri::command]
pub fn get_virtual_camera_status(state: State<'_, VirtualCamManager>) -> VirtualCamState {
    let running = state.child.lock().unwrap().is_some();
    let metrics = state.metrics.lock().unwrap().clone();
    let registered = check_virtual_camera_backend();

    VirtualCamState {
        running,
        registered,
        metrics,
    }
}

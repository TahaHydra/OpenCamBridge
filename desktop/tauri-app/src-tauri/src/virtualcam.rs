use serde::{Deserialize, Serialize};
use std::io::{BufRead, BufReader};
use std::process::{Child, Command, Stdio};
use std::sync::atomic::AtomicBool;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;
use tauri::{AppHandle, Manager, State};

#[derive(Default, Serialize, Deserialize, Clone)]
pub struct VirtualCamMetrics {
    #[serde(default, rename = "type")]
    pub message_type: String,
    #[serde(default)]
    pub producer_state: String,
    #[serde(default)]
    pub ring_frames_committed: u64,
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
    #[serde(default)]
    pub transport_fps: u32,
    #[serde(default)]
    pub decoded_unique_fps: u32,
    #[serde(default)]
    pub virtual_camera_unique_fps: u32,
    #[serde(default)]
    pub repeated_samples: u64,
    pub dropped_jpegs: u32,
    #[serde(default)]
    pub replaced_frames: u64,
    pub jpeg_queue_len: u32,
    pub decode_ms_avg: u32,
    // default so metrics from older producer builds still parse
    #[serde(default)]
    pub rotate_ms_avg: u32,
    pub resize_ms_avg: u32,
    pub write_ms_avg: u32,
    pub total_pipeline_ms: u32,
    #[serde(default)]
    pub latency_ms: u32,
    pub bytes_per_sec: usize,
    // Producer emits this; the desktop "Est. Bandwidth" readout stayed blank
    // without the field. Default keeps older producer builds parseable.
    #[serde(default)]
    pub estimated_mbps: String,
    pub pixel_format: String,
    // Which optimized paths actually ran (defaults keep older producer builds
    // parseable): "zune"/"standard" decode, "simd"/"standard"/"skipped" resize.
    #[serde(default)]
    pub decode_backend: String,
    #[serde(default)]
    pub resize_backend: String,
    #[serde(default)]
    pub decoder_name: String,
    #[serde(default)]
    pub d3d11_output: bool,
    /** None means the Microsoft MFT accepted D3D11 output but its internal
     * DXVA/software acceleration mode cannot be proven. */
    #[serde(default)]
    pub hardware_decoder: Option<bool>,
    #[serde(default)]
    pub encoder_name: String,
    #[serde(default)]
    pub hardware_encoder: bool,
    #[serde(default)]
    pub camera_id: String,
    #[serde(default)]
    pub fallback_reason: String,
    #[serde(default)]
    pub rotation: u32,
    pub last_error: Option<String>,
    #[serde(default)]
    pub ring: Option<RingDiagnostics>,
    #[serde(default)]
    pub virtual_camera_ready: bool,
}

#[derive(Default, Serialize, Deserialize, Clone)]
pub struct RingDiagnostics {
    pub consumer_attached: bool,
    pub consumer_pid: u32,
    pub consumer_heartbeat_qpc: u64,
    pub sample_requests: u64,
    pub ring_read_attempts: u64,
    pub ring_read_successes: u64,
    pub ring_validation_failures: u64,
    pub sample_copy_failures: u64,
    pub last_ring_error: i32,
    pub last_accepted_sequence: u64,
    pub negotiated_subtype: u32,
    pub negotiated_width: u32,
    pub negotiated_height: u32,
    pub negotiated_fps_num: u32,
    pub negotiated_fps_den: u32,
    pub source_fps_num: u32,
    pub source_fps_den: u32,
    pub resize_backend: String,
    pub resize_failures: u32,
    pub installed_dll_build_hash: String,
    pub producer_build_hash: String,
    pub ring_abi_hash: u64,
}

#[derive(Deserialize)]
struct ProducerEvent {
    #[serde(rename = "type")]
    _message_type: String,
    severity: String,
    code: String,
    message: String,
    producer_state: Option<String>,
}

#[derive(Serialize, Clone)]
pub struct VirtualCamState {
    pub running: bool,
    pub process_running: bool,
    pub pipeline_ready: bool,
    pub producer_ready: bool,
    pub virtual_camera_ready: bool,
    pub producer_state: String,
    pub host_running: bool,
    pub host_activated: bool,
    pub registered: bool,
    pub metrics: Option<VirtualCamMetrics>,
    pub producer_path: Option<String>,
    pub producer_exists: bool,
    pub producer_pid: Option<u32>,
    pub producer_instance: u64,
    pub last_error: Option<String>,
    pub last_metrics_time: Option<u64>,
    pub last_event: Option<String>,
}

pub struct VirtualCamManager {
    child: Mutex<Option<Child>>,
    host_child: Mutex<Option<Child>>,
    metrics: Mutex<Option<VirtualCamMetrics>>,
    producer_path: Mutex<Option<String>>,
    last_error: Mutex<Option<String>>,
    last_metrics_time: Mutex<Option<u64>>,
    producer_state: Mutex<String>,
    last_event: Mutex<Option<String>>,
    producer_instance: AtomicU64,
    host_activated: AtomicBool,
}

fn complete_pipeline_ready(
    producer_ready: bool,
    host_running: bool,
    host_activated: bool,
    registered: bool,
) -> bool {
    producer_ready && host_running && host_activated && registered
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
            producer_state: Mutex::new("STOPPED".to_string()),
            last_event: Mutex::new(None),
            producer_instance: AtomicU64::new(0),
            host_activated: AtomicBool::new(false),
        }
    }

    pub fn preview_identity(&self) -> (Option<u32>, u64, bool, Option<String>) {
        let pid = self
            .child
            .lock()
            .ok()
            .and_then(|guard| guard.as_ref().map(|child| child.id()));
        let streaming = pid.is_some()
            && self
                .producer_state
                .lock()
                .map(|s| s.as_str() == "WRITING_RING")
                .unwrap_or(false);
        let build_hash = self.metrics.lock().ok().and_then(|metrics| {
            metrics
                .as_ref()?
                .ring
                .as_ref()
                .map(|ring| ring.producer_build_hash.clone())
        });
        (
            pid,
            self.producer_instance.load(Ordering::Acquire),
            streaming,
            build_hash,
        )
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
    Err(
        "Please use the VirtualCamera_Installer.exe to register the camera manually for the MVP."
            .to_string(),
    )
}

#[tauri::command]
pub fn start_virtual_camera_host(state: State<'_, VirtualCamManager>) -> Result<(), String> {
    let mut host_guard = state.host_child.lock().unwrap();

    // A stale handle to a dead host must not block a restart (this made the
    // Start button a silent no-op after the host crashed or failed to start).
    if let Some(child) = host_guard.as_mut() {
        match child.try_wait() {
            Ok(None) if state.host_activated.load(Ordering::Acquire) => return Ok(()),
            Ok(None) => {
                let _ = child.kill();
                let _ = child.wait();
                *host_guard = None;
            }
            _ => {
                *host_guard = None;
            }
        }
    }

    let mut repo_root = std::env::current_dir().unwrap();
    while !repo_root.join("windows").exists() && repo_root.parent().is_some() {
        repo_root = repo_root.parent().unwrap().to_path_buf();
    }

    let exe_path_release = repo_root.join("windows/virtual-camera-mediafoundation/VirtualCamera_Installer/x64/Release/VirtualCamera_Installer.exe");
    if !exe_path_release.exists() {
        let msg = format!(
            "VirtualCamera_Installer.exe not found at {}. Run .\\dev-build-vcam.ps1 (it builds and copies the host exe).",
            exe_path_release.display()
        );
        *state.last_error.lock().unwrap() = Some(msg.clone());
        return Err(msg);
    }
    let exe_path = std::fs::canonicalize(&exe_path_release).unwrap_or(exe_path_release);

    if !check_virtual_camera_backend() {
        return Err(
            "Virtual-camera COM backend is not registered; run the installer before Start Webcam"
                .into(),
        );
    }

    let mut child = Command::new(exe_path)
        .arg("--mode")
        .arg("host")
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|e| {
            let msg = format!("Failed to start virtual camera host: {}", e);
            *state.last_error.lock().unwrap() = Some(msg.clone());
            msg
        })?;

    let stdout = child
        .stdout
        .take()
        .ok_or("Virtual-camera host stdout was unavailable")?;
    let stderr = child
        .stderr
        .take()
        .ok_or("Virtual-camera host stderr was unavailable")?;
    let (ready_tx, ready_rx) = std::sync::mpsc::channel::<Result<String, String>>();
    let stdout_tx = ready_tx.clone();
    std::thread::spawn(move || {
        for line in BufReader::new(stdout).lines().map_while(Result::ok) {
            let _ = stdout_tx.send(Ok(line));
        }
    });
    std::thread::spawn(move || {
        for line in BufReader::new(stderr).lines().map_while(Result::ok) {
            let _ = ready_tx.send(Err(line));
        }
    });

    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(8);
    let mut activation_error = None;
    let activated = loop {
        if let Ok(Some(status)) = child.try_wait() {
            activation_error = Some(format!(
                "Virtual-camera host exited before activation: {status}"
            ));
            break false;
        }
        match ready_rx.recv_timeout(std::time::Duration::from_millis(50)) {
            Ok(Ok(line)) if line.contains("OCB_VCAM_HOST_READY") => break true,
            Ok(Ok(line)) => println!(">>> [VCam Host] {line}"),
            Ok(Err(line)) => {
                eprintln!(">>> [VCam Host] {line}");
                activation_error = Some(line);
            }
            Err(std::sync::mpsc::RecvTimeoutError::Disconnected) => {
                activation_error.get_or_insert_with(|| {
                    "Virtual-camera host closed its activation output".into()
                });
                break false;
            }
            Err(std::sync::mpsc::RecvTimeoutError::Timeout) => {}
        }
        if std::time::Instant::now() >= deadline {
            activation_error
                .get_or_insert_with(|| "Virtual-camera host activation handshake timed out".into());
            break false;
        }
    };
    if !activated {
        let _ = child.kill();
        let _ = child.wait();
        state.host_activated.store(false, Ordering::Release);
        let message =
            activation_error.unwrap_or_else(|| "Virtual-camera host activation failed".into());
        *state.last_error.lock().unwrap() = Some(message.clone());
        return Err(message);
    }

    println!(
        ">>> [Tauri] Virtual camera host activated with PID: {}",
        child.id()
    );
    state.host_activated.store(true, Ordering::Release);
    *host_guard = Some(child);
    Ok(())
}

#[tauri::command]
pub fn stop_virtual_camera_host(state: State<'_, VirtualCamManager>) -> Result<(), String> {
    state.host_activated.store(false, Ordering::Release);
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
    profile: Option<String>,
    token: Option<String>,
    source: Option<String>,
) -> Result<(), String> {
    let source = match source.as_deref() {
        None | Some("mjpeg") => "mjpeg",
        Some("h264") => "h264",
        Some(other) => {
            return Err(format!(
                "Unknown stream source '{}' (expected mjpeg or h264)",
                other
            ))
        }
    };
    println!(">>> [Tauri] start_virtual_camera_feeder called with source={}, width={}, height={}, fps={}", source, width, height, fps);
    let mut child_guard = state.child.lock().unwrap();
    if let Some(mut child) = child_guard.take() {
        println!(
            ">>> [Tauri] Found existing producer (PID {}). Stopping it.",
            child.id()
        );
        let _ = child.kill();
        let _ = child.wait();
        *state.last_error.lock().unwrap() = None;
        *state.metrics.lock().unwrap() = None;
        *state.last_metrics_time.lock().unwrap() = None;
    }
    *state.producer_state.lock().unwrap() = "STARTING".to_string();

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
        return Err(format!(
            "rust-frame-producer.exe not found. Run cargo build --release in rust-frame-producer."
        ));
    };

    let exe_path = std::fs::canonicalize(&exe_path).unwrap_or(exe_path);
    let path_string = exe_path.to_string_lossy().to_string();

    // Note: the producer no longer accepts --latest-only (always on) or
    // --quality (JPEG quality is applied on the Android side).
    let mut cmd = Command::new(exe_path);
    cmd.arg("--source")
        .arg(source)
        .arg("--url")
        .arg(&url)
        .arg("--width")
        .arg(width.to_string())
        .arg("--height")
        .arg(height.to_string())
        .arg("--fps")
        .arg(fps.to_string());

    if let Some(p) = profile {
        cmd.arg("--profile").arg(p);
    }

    // Pass the LAN token via environment variable so it never appears in the
    // child's command line (visible to any local process).
    if let Some(t) = token.filter(|t| !t.is_empty()) {
        cmd.env("OPENCAMBRIDGE_TOKEN", t);
    }

    // Print program + args only (never the env, which holds the token).
    println!(
        ">>> [Tauri] Executing exactly: {:?} {:?}",
        cmd.get_program(),
        cmd.get_args().collect::<Vec<_>>()
    );

    let mut child = match cmd.stdout(Stdio::piped()).stderr(Stdio::piped()).spawn() {
        Ok(c) => c,
        Err(e) => {
            println!(">>> [Tauri] Spawn failed: {}", e);
            let mut err_guard = state.last_error.lock().unwrap();
            *err_guard = Some(e.to_string());
            return Err(e.to_string());
        }
    };

    println!(
        ">>> [Tauri] Spawned rust-frame-producer with PID: {}",
        child.id()
    );
    state.producer_instance.fetch_add(1, Ordering::AcqRel);

    let stdout = child.stdout.take().unwrap();
    let stderr = child.stderr.take().unwrap();
    let app_clone = app.clone();

    // STDOUT drain thread
    std::thread::spawn(move || {
        let reader = BufReader::new(stdout);
        for line in reader.lines() {
            if let Ok(line) = line {
                let value = serde_json::from_str::<serde_json::Value>(&line).ok();
                match value
                    .as_ref()
                    .and_then(|v| v.get("type"))
                    .and_then(|v| v.as_str())
                {
                    Some("metrics") => {
                        if let Ok(metrics) =
                            serde_json::from_value::<VirtualCamMetrics>(value.unwrap())
                        {
                            let state_manager = app_clone.state::<VirtualCamManager>();
                            *state_manager.producer_state.lock().unwrap() =
                                metrics.producer_state.clone();
                            *state_manager.metrics.lock().unwrap() = Some(metrics);
                            *state_manager.last_metrics_time.lock().unwrap() = Some(
                                std::time::SystemTime::now()
                                    .duration_since(std::time::UNIX_EPOCH)
                                    .unwrap()
                                    .as_secs(),
                            );
                        }
                    }
                    Some("event") => {
                        if let Ok(event) = serde_json::from_value::<ProducerEvent>(value.unwrap()) {
                            let state_manager = app_clone.state::<VirtualCamManager>();
                            if let Some(producer_state) = event.producer_state {
                                *state_manager.producer_state.lock().unwrap() = producer_state;
                            }
                            *state_manager.last_event.lock().unwrap() =
                                Some(format!("{}: {}", event.code, event.message));
                            if event.severity == "error" {
                                *state_manager.last_error.lock().unwrap() =
                                    Some(event.message.clone());
                            }
                            println!(
                                ">>> [Producer {}] {}: {}",
                                event.severity, event.code, event.message
                            );
                        }
                    }
                    _ => println!(">>> [Producer STDOUT] {}", line),
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
                // stderr is retained for developer logs only. Health is driven
                // exclusively by structured stdout events with severity=error.
            }
        }
        println!(">>> [Tauri] STDERR thread exiting.");
    });

    let mut path_guard = state.producer_path.lock().unwrap();
    *path_guard = Some(path_string);
    drop(path_guard);

    let mut err_guard = state.last_error.lock().unwrap();
    *err_guard = None;
    drop(err_guard);

    *child_guard = Some(child);
    drop(child_guard);

    // Process existence is not readiness. Wait until the producer has decoded
    // and committed at least three frames to the ring.
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(20);
    loop {
        if let Some(metrics) = state.metrics.lock().unwrap().as_ref() {
            if metrics.producer_state == "WRITING_RING" && metrics.ring_frames_committed >= 3 {
                return Ok(());
            }
        }
        let current_error = { state.last_error.lock().unwrap().clone() };
        if let Some(error) = current_error {
            let _ = stop_virtual_camera_feeder(state);
            return Err(error);
        }
        {
            let mut guard = state.child.lock().unwrap();
            if let Some(child) = guard.as_mut() {
                if let Ok(Some(status)) = child.try_wait() {
                    *guard = None;
                    return Err(format!("Producer exited before ring readiness: {status}"));
                }
            }
        }
        if std::time::Instant::now() >= deadline {
            let producer_state = state.producer_state.lock().unwrap().clone();
            let _ = stop_virtual_camera_feeder(state);
            return Err(format!(
                "Producer readiness timed out in state {producer_state}"
            ));
        }
        std::thread::sleep(std::time::Duration::from_millis(50));
    }
}

#[tauri::command]
pub fn stop_virtual_camera_feeder(state: State<'_, VirtualCamManager>) -> Result<(), String> {
    state.producer_instance.fetch_add(1, Ordering::AcqRel);
    let mut child_guard = state.child.lock().unwrap();
    if let Some(mut child) = child_guard.take() {
        let _ = child.kill();
        let _ = child.wait();
    }

    let mut metrics_guard = state.metrics.lock().unwrap();
    *metrics_guard = None;

    let mut time_guard = state.last_metrics_time.lock().unwrap();
    *time_guard = None;
    *state.producer_state.lock().unwrap() = "STOPPED".to_string();

    Ok(())
}

#[tauri::command]
pub fn get_virtual_camera_status(state: State<'_, VirtualCamManager>) -> VirtualCamState {
    let mut child_guard = state.child.lock().unwrap();

    let mut process_running = false;
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
                *state.producer_state.lock().unwrap() = "FAILED".to_string();
            }
            Ok(None) => {
                // Still running
                process_running = true;
                producer_pid = Some(child.id());
            }
            Err(_) => {}
        }
    }

    if !process_running && child_guard.is_some() {
        *child_guard = None;
    }

    // Reap the host like the producer: a host that exited (crash, COM error)
    // must show as Stopped instead of a phantom "Running".
    let host_running = {
        let mut host_guard = state.host_child.lock().unwrap();
        let mut alive = false;
        if let Some(child) = host_guard.as_mut() {
            match child.try_wait() {
                Ok(Some(status)) => {
                    let mut err_guard = state.last_error.lock().unwrap();
                    *err_guard = Some(format!("Virtual camera host exited with {}", status));
                }
                Ok(None) => alive = true,
                Err(_) => {}
            }
        }
        if !alive && host_guard.is_some() {
            *host_guard = None;
            state.host_activated.store(false, Ordering::Release);
        }
        alive
    };
    let metrics = state.metrics.lock().unwrap().clone();
    let registered = check_virtual_camera_backend();
    let producer_path = state.producer_path.lock().unwrap().clone();
    let last_error = state.last_error.lock().unwrap().clone();
    let last_metrics_time = state.last_metrics_time.lock().unwrap().clone();

    let mut metrics_fresh = false;
    if let Some(last_time) = last_metrics_time {
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_secs();
        metrics_fresh = now <= last_time + 3;
        if !metrics_fresh && process_running {
            *state.producer_state.lock().unwrap() = "STALLED".to_string();
        }
    }
    let producer_ready = process_running
        && metrics_fresh
        && metrics.as_ref().is_some_and(|m| {
            m.producer_state == "WRITING_RING"
                && m.ring_frames_committed >= 3
                && m.last_error.is_none()
        });
    let host_activated = host_running && state.host_activated.load(Ordering::Acquire);
    let pipeline_ready =
        complete_pipeline_ready(producer_ready, host_running, host_activated, registered);
    let virtual_camera_ready =
        pipeline_ready && metrics.as_ref().is_some_and(|m| m.virtual_camera_ready);
    let producer_state = state.producer_state.lock().unwrap().clone();
    let last_event = state.last_event.lock().unwrap().clone();
    let producer_instance = state.producer_instance.load(Ordering::Acquire);

    let mut repo_root = std::env::current_dir().unwrap();
    while !repo_root.join("windows").exists() && repo_root.parent().is_some() {
        repo_root = repo_root.parent().unwrap().to_path_buf();
    }
    let exe_path_release = repo_root.join("windows/virtual-camera-mediafoundation/rust-frame-producer/target/release/rust-frame-producer.exe");
    let producer_exists = exe_path_release.exists() || repo_root.join("windows/virtual-camera-mediafoundation/rust-frame-producer/target/debug/rust-frame-producer.exe").exists();

    VirtualCamState {
        running: pipeline_ready,
        process_running,
        pipeline_ready,
        producer_ready,
        virtual_camera_ready,
        producer_state,
        host_running,
        host_activated,
        registered,
        metrics,
        producer_path,
        producer_exists,
        producer_pid,
        producer_instance,
        last_error,
        last_metrics_time,
        last_event,
    }
}

#[cfg(test)]
mod tests {
    use super::complete_pipeline_ready;

    #[test]
    fn complete_readiness_requires_host_activation_and_registration() {
        assert!(complete_pipeline_ready(true, true, true, true));
        assert!(!complete_pipeline_ready(true, false, true, true));
        assert!(!complete_pipeline_ready(true, true, false, true));
        assert!(!complete_pipeline_ready(true, true, true, false));
        assert!(!complete_pipeline_ready(false, true, true, true));
    }
}

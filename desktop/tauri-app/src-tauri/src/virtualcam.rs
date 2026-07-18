use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::fs::File;
use std::io::Read;
use std::io::{BufRead, BufReader};
use std::os::windows::process::CommandExt;
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::atomic::AtomicBool;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;
use tauri::{AppHandle, Manager, State};

use crate::sync_state::RecoverMutex;
use crate::winproc::CREATE_NO_WINDOW;

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
    pub binary_identity: BinaryIdentityStatus,
}

#[derive(Default, Serialize, Clone)]
pub struct BinaryIdentityStatus {
    pub ready: bool,
    pub producer_path: String,
    pub producer_file_hash: String,
    pub producer_runtime_hash: String,
    pub built_dll_path: String,
    pub built_dll_hash: String,
    pub installed_dll_path: String,
    pub installed_dll_hash: String,
    pub registered_dll_path: String,
    pub registered_dll_hash: String,
    pub loaded_dll_hash: String,
    pub loaded_dll_current: bool,
    pub error: Option<String>,
    pub remediation: String,
}

pub struct VirtualCamManager {
    // Lock-order audit (2026-07-17): process handles are outer locks
    // (`child` before `host_child` when both are needed); metrics/error/path
    // locks are leaves and no path acquires a process lock while holding one.
    // RecoverMutex changes poison behavior only and preserves this order.
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

/// How a producer process exit should be surfaced to the UI.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ProducerExitKind {
    /// Terminated by a console/Ctrl control event (`STATUS_CONTROL_C_EXIT`,
    /// `0xC000013A`). This is a managed teardown, never a producer-reported
    /// failure, so it must not become a persistent `FAILED`/`Last Error`.
    Benign,
    /// The producer died on its own for an unknown reason (a real crash).
    Unexpected,
}

/// Classify a *self-observed* producer exit. Intentional stop/restart paths take
/// the child handle before killing it, so they never reach this classifier; it
/// only guards exits the status poll observes while the handle is still owned.
/// The producer reports genuine failures via structured stdout events, so the
/// raw exit code alone is only ever used to reject benign terminations.
fn classify_producer_exit(exit_code: Option<i32>) -> ProducerExitKind {
    match exit_code {
        // STATUS_CONTROL_C_EXIT — the process was terminated by a console
        // control event, not an internal producer failure.
        Some(code) if code as u32 == 0xC000_013A => ProducerExitKind::Benign,
        _ => ProducerExitKind::Unexpected,
    }
}

fn repository_root() -> PathBuf {
    let mut root = std::env::current_dir().unwrap_or_default();
    while !root.join("windows").exists() && root.parent().is_some() {
        root = root.parent().unwrap().to_path_buf();
    }
    root
}

fn virtual_camera_installer_path() -> PathBuf {
    let root = repository_root();
    // Prefer the canonical solution output during development. The copied
    // installer-folder executable can be locked by an elevated/orphaned host,
    // which previously made a successful rebuild impossible to exercise until
    // reboot. Packaged/source-only layouts still use the retained fallback.
    let built = root.join(
        "windows/virtual-camera-mediafoundation/x64/Release/VirtualCamera_Installer.exe",
    );
    if built.exists() {
        return built;
    }
    root.join(
        "windows/virtual-camera-mediafoundation/VirtualCamera_Installer/x64/Release/VirtualCamera_Installer.exe",
    )
}

fn installer_remediation(command: &str) -> String {
    format!(
        "Run from an elevated PowerShell: & '{}\\windows\\virtual-camera-mediafoundation\\VirtualCamera_Installer\\x64\\Release\\VirtualCamera_Installer.exe' {command}",
        repository_root().display()
    )
}

fn run_installer_command(argument: &str) -> Result<String, String> {
    let installer = virtual_camera_installer_path();
    if !installer.exists() {
        return Err(format!(
            "VirtualCamera_Installer.exe is missing at {}. Run .\\dev-build-vcam.ps1 first.",
            installer.display()
        ));
    }
    let output = Command::new(&installer)
        .arg(argument)
        .creation_flags(CREATE_NO_WINDOW)
        .output()
        .map_err(|error| format!("Failed to launch {}: {error}", installer.display()))?;
    let stdout = String::from_utf8_lossy(&output.stdout).trim().to_string();
    let stderr = String::from_utf8_lossy(&output.stderr).trim().to_string();
    if output.status.success() {
        return Ok(if stdout.is_empty() {
            format!("OpenCamBridge installer completed {argument}")
        } else {
            stdout
        });
    }
    let exit_code = output.status.code().unwrap_or(-1);
    let detail = if stderr.is_empty() { stdout } else { stderr };
    if exit_code == 5 {
        return Err(format!(
            "Administrator elevation is required. {}",
            installer_remediation(argument)
        ));
    }
    Err(format!(
        "OpenCamBridge installer {argument} failed with exit code {exit_code}: {detail}. {}",
        installer_remediation(argument)
    ))
}

fn sha256_file(path: &std::path::Path) -> String {
    let mut file = match File::open(path) {
        Ok(file) => file,
        Err(_) => return String::new(),
    };
    let mut hasher = Sha256::new();
    // Heap-allocate the read buffer: `get_virtual_camera_status` is a synchronous
    // command that Tauri runs on the main thread, whose Windows stack defaults to
    // 1 MiB. A 1 MiB stack array here overflowed it (STATUS_STACK_OVERFLOW).
    let mut buffer = vec![0u8; 1024 * 1024];
    loop {
        match file.read(&mut buffer) {
            Ok(0) => break,
            Ok(count) => hasher.update(&buffer[..count]),
            Err(_) => return String::new(),
        }
    }
    format!("{:x}", hasher.finalize())
}

fn registered_dll_path() -> String {
    let hklm = winreg::RegKey::predef(winreg::enums::HKEY_LOCAL_MACHINE);
    let path = r#"Software\Classes\CLSID\{8CF75B14-3F68-46BC-80DF-5FB86AED931E}\InprocServer32"#;
    hklm.open_subkey(path)
        .ok()
        .and_then(|key| key.get_value::<String, _>("").ok())
        .unwrap_or_default()
}

fn binary_identity_mismatches(
    built_dll_hash: &str,
    installed_dll_hash: &str,
    registered_dll_hash: &str,
    producer_file_hash: &str,
    producer_runtime_hash: &str,
    loaded_dll_hash: &str,
    loaded_dll_current: bool,
) -> Vec<&'static str> {
    let mut mismatches = Vec::new();
    if !built_dll_hash.is_empty()
        && !installed_dll_hash.is_empty()
        && built_dll_hash != installed_dll_hash
    {
        mismatches.push("built DLL differs from installed DLL");
    }
    if installed_dll_hash.is_empty() {
        mismatches.push("installed DLL is missing");
    }
    if registered_dll_hash.is_empty() {
        mismatches.push("registered DLL is missing or unreadable");
    } else if !installed_dll_hash.is_empty() && registered_dll_hash != installed_dll_hash {
        mismatches.push("registered DLL differs from installed DLL");
    }
    if !producer_runtime_hash.is_empty()
        && !producer_file_hash.is_empty()
        && producer_runtime_hash != producer_file_hash
    {
        mismatches.push("running producer differs from its on-disk executable");
    }
    if loaded_dll_current
        && !loaded_dll_hash.is_empty()
        && !registered_dll_hash.is_empty()
        && loaded_dll_hash != registered_dll_hash
    {
        mismatches.push("loaded DLL differs from the registered DLL");
    }
    mismatches
}

fn evaluate_binary_identity(
    producer_path: Option<&str>,
    metrics: Option<&VirtualCamMetrics>,
) -> BinaryIdentityStatus {
    let root = repository_root();
    let built_dll = root
        .join("windows/virtual-camera-mediafoundation/x64/Release/VirtualCameraMediaSource.dll");
    let installed_dll = root.join(
        "windows/virtual-camera-mediafoundation/VirtualCamera_Installer/x64/Release/VirtualCameraMediaSource.dll",
    );
    let producer_path = producer_path.unwrap_or_default().to_string();
    let producer_file_hash = if producer_path.is_empty() {
        String::new()
    } else {
        sha256_file(std::path::Path::new(&producer_path))
    };
    let built_dll_hash = sha256_file(&built_dll);
    let installed_dll_hash = sha256_file(&installed_dll);
    let registered_dll_path = registered_dll_path();
    let registered_dll_hash = if registered_dll_path.is_empty() {
        String::new()
    } else {
        sha256_file(std::path::Path::new(&registered_dll_path))
    };
    let ring = metrics.and_then(|item| item.ring.as_ref());
    let producer_runtime_hash = ring
        .map(|item| item.producer_build_hash.to_ascii_lowercase())
        .unwrap_or_default();
    let loaded_dll_hash = ring
        .map(|item| item.installed_dll_build_hash.to_ascii_lowercase())
        .unwrap_or_default();
    let loaded_dll_current = ring.is_some_and(|item| item.consumer_attached);
    let mismatches = binary_identity_mismatches(
        &built_dll_hash,
        &installed_dll_hash,
        &registered_dll_hash,
        &producer_file_hash,
        &producer_runtime_hash,
        &loaded_dll_hash,
        loaded_dll_current,
    );
    let remediation = format!(
        "Stop camera consumers, then run .\\dev-build-vcam.ps1 and {}",
        installer_remediation("--register")
    );
    BinaryIdentityStatus {
        ready: mismatches.is_empty(),
        producer_path,
        producer_file_hash,
        producer_runtime_hash,
        built_dll_path: built_dll.display().to_string(),
        built_dll_hash,
        installed_dll_path: installed_dll.display().to_string(),
        installed_dll_hash,
        registered_dll_path,
        registered_dll_hash,
        loaded_dll_hash,
        loaded_dll_current,
        error: (!mismatches.is_empty()).then(|| mismatches.join("; ")),
        remediation,
    }
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
        let pid = self.child.lock_recover().as_ref().map(|child| child.id());
        let streaming =
            pid.is_some() && self.producer_state.lock_recover().as_str() == "WRITING_RING";
        let build_hash = self.metrics.lock_recover().as_ref().and_then(|metrics| {
            metrics
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
    run_installer_command("--register")
}

#[tauri::command]
pub fn unregister_virtual_camera_backend() -> Result<String, String> {
    run_installer_command("--unregister")
}

#[tauri::command]
pub fn get_virtual_camera_backend_details() -> Result<String, String> {
    run_installer_command("--status")
}

#[tauri::command]
pub fn start_virtual_camera_host(state: State<'_, VirtualCamManager>) -> Result<(), String> {
    let mut host_guard = state.host_child.lock_recover();

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

    let exe_path_release = virtual_camera_installer_path();
    if !exe_path_release.exists() {
        let msg = format!(
            "VirtualCamera_Installer.exe not found at {}. Run .\\dev-build-vcam.ps1 (it builds and copies the host exe).",
            exe_path_release.display()
        );
        *state.last_error.lock_recover() = Some(msg.clone());
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
        .creation_flags(CREATE_NO_WINDOW)
        .spawn()
        .map_err(|e| {
            let msg = format!("Failed to start virtual camera host: {}", e);
            *state.last_error.lock_recover() = Some(msg.clone());
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
        *state.last_error.lock_recover() = Some(message.clone());
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
    let mut host_guard = state.host_child.lock_recover();
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
    source_width: u32,
    source_height: u32,
    source_fps: f64,
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
    println!(
        ">>> [Tauri] start_virtual_camera_feeder source={} source={}x{}@{} output={}x{}",
        source, source_width, source_height, source_fps, width, height
    );
    let mut child_guard = state.child.lock_recover();
    if let Some(mut child) = child_guard.take() {
        println!(
            ">>> [Tauri] Found existing producer (PID {}). Stopping it.",
            child.id()
        );
        let _ = child.kill();
        let _ = child.wait();
        *state.last_error.lock_recover() = None;
        *state.metrics.lock_recover() = None;
        *state.last_metrics_time.lock_recover() = None;
    }
    *state.producer_state.lock_recover() = "STARTING".to_string();

    let repo_root = repository_root();

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
        .arg(fps.round().max(1.0).to_string())
        .arg("--source-width")
        .arg(source_width.to_string())
        .arg("--source-height")
        .arg(source_height.to_string())
        .arg("--source-fps")
        .arg(source_fps.round().max(1.0).to_string());

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

    let mut child = match cmd
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .creation_flags(CREATE_NO_WINDOW)
        .spawn()
    {
        Ok(c) => c,
        Err(e) => {
            println!(">>> [Tauri] Spawn failed: {}", e);
            let mut err_guard = state.last_error.lock_recover();
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
                            *state_manager.producer_state.lock_recover() =
                                metrics.producer_state.clone();
                            *state_manager.metrics.lock_recover() = Some(metrics);
                            *state_manager.last_metrics_time.lock_recover() = Some(
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
                                *state_manager.producer_state.lock_recover() = producer_state;
                            }
                            *state_manager.last_event.lock_recover() =
                                Some(format!("{}: {}", event.code, event.message));
                            if event.severity == "error" {
                                *state_manager.last_error.lock_recover() =
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

    let mut path_guard = state.producer_path.lock_recover();
    *path_guard = Some(path_string);
    drop(path_guard);

    let mut err_guard = state.last_error.lock_recover();
    *err_guard = None;
    drop(err_guard);

    *child_guard = Some(child);
    drop(child_guard);

    // Process existence is not readiness. Wait until the producer has decoded
    // and committed at least three frames to the ring.
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(20);
    loop {
        if let Some(metrics) = state.metrics.lock_recover().as_ref() {
            if metrics.producer_state == "WRITING_RING" && metrics.ring_frames_committed >= 3 {
                return Ok(());
            }
        }
        let current_error = { state.last_error.lock_recover().clone() };
        if let Some(error) = current_error {
            let _ = stop_virtual_camera_feeder(state);
            return Err(error);
        }
        {
            let mut guard = state.child.lock_recover();
            if let Some(child) = guard.as_mut() {
                if let Ok(Some(status)) = child.try_wait() {
                    *guard = None;
                    return Err(format!("Producer exited before ring readiness: {status}"));
                }
            }
        }
        if std::time::Instant::now() >= deadline {
            let producer_state = state.producer_state.lock_recover().clone();
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
    let mut child_guard = state.child.lock_recover();
    if let Some(mut child) = child_guard.take() {
        let _ = child.kill();
        let _ = child.wait();
    }

    let mut metrics_guard = state.metrics.lock_recover();
    *metrics_guard = None;

    let mut time_guard = state.last_metrics_time.lock_recover();
    *time_guard = None;
    *state.producer_state.lock_recover() = "STOPPED".to_string();

    Ok(())
}

#[tauri::command]
pub fn get_virtual_camera_status(state: State<'_, VirtualCamManager>) -> VirtualCamState {
    let mut child_guard = state.child.lock_recover();

    let mut process_running = false;
    let mut producer_pid = None;

    if let Some(child) = child_guard.as_mut() {
        match child.try_wait() {
            Ok(Some(status)) => {
                // Process exited on its own while we still held the handle.
                match classify_producer_exit(status.code()) {
                    ProducerExitKind::Benign => {
                        // A console-control teardown is not a crash. Drop any
                        // stale exit error so it does not linger as "Last Error",
                        // and report a clean STOPPED rather than FAILED.
                        let mut err_guard = state.last_error.lock_recover();
                        if err_guard
                            .as_deref()
                            .is_some_and(|error| error.contains("xited"))
                        {
                            *err_guard = None;
                        }
                        *state.producer_state.lock_recover() = "STOPPED".to_string();
                    }
                    ProducerExitKind::Unexpected => {
                        // Keep any structured error the producer already reported
                        // (the real reason); only synthesize one if none exists.
                        let mut err_guard = state.last_error.lock_recover();
                        if err_guard.as_deref().unwrap_or_default().is_empty() {
                            *err_guard = Some(format!("Producer exited unexpectedly: {status}"));
                        }
                        *state.producer_state.lock_recover() = "FAILED".to_string();
                    }
                }
                *state.metrics.lock_recover() = None;
                *state.last_metrics_time.lock_recover() = None;
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
        let mut host_guard = state.host_child.lock_recover();
        let mut alive = false;
        if let Some(child) = host_guard.as_mut() {
            match child.try_wait() {
                Ok(Some(status)) => {
                    let mut err_guard = state.last_error.lock_recover();
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
    let metrics = state.metrics.lock_recover().clone();
    let registered = check_virtual_camera_backend();
    let producer_path = state.producer_path.lock_recover().clone();
    let last_error = state.last_error.lock_recover().clone();
    let last_metrics_time = state.last_metrics_time.lock_recover().clone();

    let mut metrics_fresh = false;
    if let Some(last_time) = last_metrics_time {
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_secs();
        metrics_fresh = now <= last_time + 3;
        if !metrics_fresh && process_running {
            *state.producer_state.lock_recover() = "STALLED".to_string();
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
    let binary_identity = evaluate_binary_identity(producer_path.as_deref(), metrics.as_ref());
    let pipeline_ready = complete_pipeline_ready(
        producer_ready && binary_identity.ready,
        host_running,
        host_activated,
        registered,
    );
    let virtual_camera_ready =
        pipeline_ready && metrics.as_ref().is_some_and(|m| m.virtual_camera_ready);
    let producer_state = state.producer_state.lock_recover().clone();
    let last_event = state.last_event.lock_recover().clone();
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
        binary_identity,
    }
}

#[cfg(test)]
mod tests {
    use super::{
        binary_identity_mismatches, classify_producer_exit, complete_pipeline_ready,
        ProducerExitKind,
    };

    #[test]
    fn console_control_exit_is_benign_not_a_crash() {
        // 0xC000013A (STATUS_CONTROL_C_EXIT) as an i32 exit code.
        let control_c = 0xC000_013Au32 as i32;
        assert_eq!(
            classify_producer_exit(Some(control_c)),
            ProducerExitKind::Benign
        );
        // Any other exit (a real crash, or an unknown code) is unexpected.
        assert_eq!(
            classify_producer_exit(Some(1)),
            ProducerExitKind::Unexpected
        );
        assert_eq!(
            classify_producer_exit(Some(-1073741819)), // 0xC0000005 access violation
            ProducerExitKind::Unexpected
        );
        assert_eq!(classify_producer_exit(None), ProducerExitKind::Unexpected);
    }

    #[test]
    fn complete_readiness_requires_host_activation_and_registration() {
        assert!(complete_pipeline_ready(true, true, true, true));
        assert!(!complete_pipeline_ready(true, false, true, true));
        assert!(!complete_pipeline_ready(true, true, false, true));
        assert!(!complete_pipeline_ready(true, true, true, false));
        assert!(!complete_pipeline_ready(false, true, true, true));
    }

    #[test]
    fn stale_binary_combinations_are_never_ready() {
        assert!(
            binary_identity_mismatches("same", "same", "same", "same", "same", "same", true)
                .is_empty()
        );
        assert_eq!(
            binary_identity_mismatches(
                "built",
                "installed",
                "installed",
                "producer",
                "producer",
                "installed",
                true
            ),
            vec!["built DLL differs from installed DLL"]
        );
        assert_eq!(
            binary_identity_mismatches(
                "dll",
                "dll",
                "dll",
                "producer-file",
                "producer-runtime",
                "dll",
                true
            ),
            vec!["running producer differs from its on-disk executable"]
        );
        assert_eq!(
            binary_identity_mismatches(
                "dll",
                "dll",
                "registered",
                "producer",
                "producer",
                "loaded",
                true
            ),
            vec![
                "registered DLL differs from installed DLL",
                "loaded DLL differs from the registered DLL"
            ]
        );
        assert_eq!(
            binary_identity_mismatches("dll", "dll", "dll", "producer", "producer", "stale", false),
            Vec::<&'static str>::new(),
            "an inactive stale ring is not treated as the currently loaded DLL"
        );
    }
}

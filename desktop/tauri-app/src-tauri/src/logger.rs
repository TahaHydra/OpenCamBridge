// Persistent PC-side session logging for runtime testing.
//
// Writes human-readable, sampled logs to C:\ProgramData\OpenCamBridge\logs\ so a
// test session can be sent as a single file instead of scraping the console.
// The frontend owns formatting/sampling (it has easy local time + the metrics);
// this module is a robust append-only sink plus folder/clipboard helpers.

use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::PathBuf;
use std::sync::Mutex;
use tauri::State;

const LOG_DIR: &str = r"C:\ProgramData\OpenCamBridge\logs";

pub struct SessionLog {
    path: Mutex<Option<PathBuf>>,
}

impl SessionLog {
    pub fn new() -> Self {
        Self { path: Mutex::new(None) }
    }
}

fn logs_dir() -> PathBuf {
    PathBuf::from(LOG_DIR)
}

/// Starts (or restarts) a session log file. `stamp` is a caller-formatted
/// "YYYYMMDD-HHMMSS" string (the frontend has local time; Rust std does not
/// without extra deps). `header` is written as the first block.
#[tauri::command]
pub fn start_log_session(state: State<'_, SessionLog>, stamp: String, header: String) -> Result<String, String> {
    let dir = logs_dir();
    fs::create_dir_all(&dir).map_err(|e| format!("create {}: {}", dir.display(), e))?;
    // Sanitize the stamp so it can never escape the folder.
    let safe: String = stamp.chars().filter(|c| c.is_ascii_alphanumeric() || *c == '-' || *c == '_').collect();
    let name = if safe.is_empty() { "session".to_string() } else { safe };
    let file = dir.join(format!("opencambridge-session-{}.log", name));

    fs::write(&file, format!("{}\n", header)).map_err(|e| format!("write header: {}", e))?;
    *state.path.lock().unwrap() = Some(file.clone());
    Ok(file.to_string_lossy().to_string())
}

/// Appends one already-formatted line. No-op (Ok) if no session started, so the
/// UI never errors just because logging wasn't initialized yet.
#[tauri::command]
pub fn append_log(state: State<'_, SessionLog>, line: String) -> Result<(), String> {
    let guard = state.path.lock().unwrap();
    let path = match guard.as_ref() {
        Some(p) => p.clone(),
        None => return Ok(()),
    };
    let mut f = OpenOptions::new().create(true).append(true).open(&path).map_err(|e| e.to_string())?;
    writeln!(f, "{}", line).map_err(|e| e.to_string())?;
    Ok(())
}

#[tauri::command]
pub fn get_log_path(state: State<'_, SessionLog>) -> Option<String> {
    state.path.lock().unwrap().as_ref().map(|p| p.to_string_lossy().to_string())
}

/// Returns the last `max_lines` lines for the in-app Logs tab.
#[tauri::command]
pub fn read_log_tail(state: State<'_, SessionLog>, max_lines: usize) -> String {
    let path = match state.path.lock().unwrap().as_ref() {
        Some(p) => p.clone(),
        None => return String::new(),
    };
    let content = fs::read_to_string(&path).unwrap_or_default();
    let lines: Vec<&str> = content.lines().collect();
    let start = lines.len().saturating_sub(max_lines);
    lines[start..].join("\n")
}

/// Truncates the current session file (keeps the same file/path).
#[tauri::command]
pub fn clear_log(state: State<'_, SessionLog>) -> Result<(), String> {
    if let Some(p) = state.path.lock().unwrap().as_ref() {
        fs::write(p, "").map_err(|e| e.to_string())?;
    }
    Ok(())
}

/// Opens the logs folder in Explorer (creates it if needed).
#[tauri::command]
pub fn open_logs_folder() -> Result<(), String> {
    let dir = logs_dir();
    let _ = fs::create_dir_all(&dir);
    std::process::Command::new("explorer")
        .arg(dir)
        .spawn()
        .map_err(|e| e.to_string())?;
    Ok(())
}

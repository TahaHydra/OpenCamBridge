use std::process::Command;
use std::env;
use std::path::PathBuf;

fn get_adb_path() -> String {
    if let Ok(local_app_data) = env::var("LOCALAPPDATA") {
        let mut path = PathBuf::from(local_app_data);
        path.push("Android");
        path.push("Sdk");
        path.push("platform-tools");
        path.push("adb.exe");
        if path.exists() {
            return path.to_string_lossy().into_owned();
        }
    }
    "adb".to_string()
}

#[tauri::command]
pub fn get_adb_status() -> Result<String, String> {
    let adb = get_adb_path();
    let output = Command::new(adb)
        .arg("--version")
        .output()
        .map_err(|e| format!("Failed to execute adb: {}", e))?;
        
    if output.status.success() {
        Ok(String::from_utf8_lossy(&output.stdout).to_string())
    } else {
        Err(String::from_utf8_lossy(&output.stderr).to_string())
    }
}

#[tauri::command]
pub fn list_devices() -> Result<String, String> {
    let adb = get_adb_path();
    let output = Command::new(adb)
        .arg("devices")
        .output()
        .map_err(|e| format!("Failed to execute adb: {}", e))?;
        
    if output.status.success() {
        Ok(String::from_utf8_lossy(&output.stdout).to_string())
    } else {
        Err(String::from_utf8_lossy(&output.stderr).to_string())
    }
}

#[tauri::command]
pub fn forward_port(port: u16) -> Result<String, String> {
    let adb = get_adb_path();
    let port_str = format!("tcp:{}", port);
    let output = Command::new(adb)
        .args(["forward", &port_str, &port_str])
        .output()
        .map_err(|e| format!("Failed to execute adb: {}", e))?;
        
    if output.status.success() {
        Ok(String::from_utf8_lossy(&output.stdout).to_string())
    } else {
        Err(String::from_utf8_lossy(&output.stderr).to_string())
    }
}

#[tauri::command]
pub fn remove_forwards() -> Result<String, String> {
    let adb = get_adb_path();
    let output = Command::new(adb)
        .arg("forward")
        .arg("--remove-all")
        .output()
        .map_err(|e| format!("Failed to execute adb: {}", e))?;
        
    if output.status.success() {
        Ok("Forwards removed successfully".to_string())
    } else {
        Err(String::from_utf8_lossy(&output.stderr).to_string())
    }
}

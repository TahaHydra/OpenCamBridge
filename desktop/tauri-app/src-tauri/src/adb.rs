use serde::Serialize;
use std::env;
use std::os::windows::process::CommandExt;
use std::path::PathBuf;
use std::process::Command;

use crate::winproc::CREATE_NO_WINDOW;

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

#[derive(Serialize, Clone)]
pub struct AdbDevice {
    serial: String,
    state: String,
    model: Option<String>,
}

fn authorized_devices(adb: &str) -> Result<Vec<AdbDevice>, String> {
    let output = Command::new(adb)
        .args(["devices", "-l"])
        .creation_flags(CREATE_NO_WINDOW)
        .output()
        .map_err(|e| format!("Failed to list ADB devices: {e}"))?;
    if !output.status.success() {
        return Err(String::from_utf8_lossy(&output.stderr).to_string());
    }
    let stdout = String::from_utf8_lossy(&output.stdout);
    Ok(stdout
        .lines()
        .skip(1)
        .filter_map(|line| {
            let mut parts = line.split_whitespace();
            let serial = parts.next()?;
            let state = parts.next()?;
            if state != "device" {
                return None;
            }
            let model = parts.find_map(|field| field.strip_prefix("model:").map(str::to_string));
            Some(AdbDevice {
                serial: serial.to_string(),
                state: state.to_string(),
                model,
            })
        })
        .collect())
}

fn resolve_serial(adb: &str, selected: Option<&str>) -> Result<String, String> {
    let devices = authorized_devices(adb)?;
    if let Some(serial) = selected.filter(|s| !s.is_empty()) {
        return devices
            .iter()
            .find(|d| d.serial == serial)
            .map(|d| d.serial.clone())
            .ok_or_else(|| {
                format!("Selected ADB device '{serial}' is no longer connected or authorized")
            });
    }
    match devices.as_slice() {
        [] => Err("No authorized ADB device is connected".to_string()),
        [device] => Ok(device.serial.clone()),
        _ => {
            Err("Several ADB devices are connected; select the phone before connecting".to_string())
        }
    }
}

fn has_exact_forward(list: &str, serial: &str, local: &str, remote: &str) -> bool {
    list.lines().any(|line| {
        let mut fields = line.split_whitespace();
        fields.next() == Some(serial)
            && fields.next() == Some(local)
            && fields.next() == Some(remote)
            && fields.next().is_none()
    })
}

#[tauri::command]
pub fn get_adb_status() -> Result<String, String> {
    let adb = get_adb_path();
    let output = Command::new(adb)
        .arg("--version")
        .creation_flags(CREATE_NO_WINDOW)
        .output()
        .map_err(|e| format!("Failed to execute adb: {}", e))?;

    if output.status.success() {
        Ok(String::from_utf8_lossy(&output.stdout).to_string())
    } else {
        Err(String::from_utf8_lossy(&output.stderr).to_string())
    }
}

#[tauri::command]
pub fn list_devices() -> Result<Vec<AdbDevice>, String> {
    let adb = get_adb_path();
    authorized_devices(&adb)
}

#[tauri::command]
pub fn forward_port(port: u16, serial: Option<String>) -> Result<String, String> {
    let adb = get_adb_path();
    let port_str = format!("tcp:{}", port);
    let serial = resolve_serial(&adb, serial.as_deref())?;

    // The USB watchdog calls this periodically so it can restore a forward
    // after a cable cycle. Re-issuing `adb forward` for an already healthy
    // mapping can rebind the listener and disturb the long-lived H.264 HTTP
    // connection, so first make this operation an idempotent ensure.
    let list_output = Command::new(&adb)
        .args(["forward", "--list"])
        .creation_flags(CREATE_NO_WINDOW)
        .output()
        .map_err(|e| format!("Failed to list ADB forwards: {e}"))?;
    if !list_output.status.success() {
        return Err(String::from_utf8_lossy(&list_output.stderr).to_string());
    }
    let forwards = String::from_utf8_lossy(&list_output.stdout);
    if has_exact_forward(&forwards, &serial, &port_str, &port_str) {
        return Ok("ADB forward already active".to_string());
    }

    let mut cmd = Command::new(&adb);
    cmd.args(["-s", &serial]);
    let output = cmd
        .args(["forward", &port_str, &port_str])
        .creation_flags(CREATE_NO_WINDOW)
        .output()
        .map_err(|e| format!("Failed to execute adb: {}", e))?;

    if output.status.success() {
        Ok(String::from_utf8_lossy(&output.stdout).to_string())
    } else {
        Err(String::from_utf8_lossy(&output.stderr).to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::has_exact_forward;

    #[test]
    fn recognizes_only_the_exact_device_and_port_mapping() {
        let forwards = "phone-a tcp:8080 tcp:8080\nphone-b tcp:9090 tcp:9090\n";

        assert!(has_exact_forward(
            forwards, "phone-a", "tcp:8080", "tcp:8080"
        ));
        assert!(!has_exact_forward(
            forwards, "phone-b", "tcp:8080", "tcp:8080"
        ));
        assert!(!has_exact_forward(
            forwards, "phone-a", "tcp:8080", "tcp:9090"
        ));
    }

    #[test]
    fn accepts_adb_whitespace_but_rejects_extra_fields() {
        assert!(has_exact_forward(
            "phone-a   tcp:8080\ttcp:8080\r\n",
            "phone-a",
            "tcp:8080",
            "tcp:8080"
        ));
        assert!(!has_exact_forward(
            "phone-a tcp:8080 tcp:8080 unexpected",
            "phone-a",
            "tcp:8080",
            "tcp:8080"
        ));
    }
}

#[tauri::command]
pub fn remove_forwards(port: u16, serial: Option<String>) -> Result<String, String> {
    let adb = get_adb_path();
    let port_str = format!("tcp:{}", port);
    let mut cmd = Command::new(&adb);
    let serial = resolve_serial(&adb, serial.as_deref())?;
    cmd.args(["-s", &serial]);
    // Only remove the forward this app created; --remove-all would destroy
    // forwards owned by other tools (scrcpy, Android Studio, ...).
    let output = cmd
        .args(["forward", "--remove", &port_str])
        .creation_flags(CREATE_NO_WINDOW)
        .output()
        .map_err(|e| format!("Failed to execute adb: {}", e))?;

    if output.status.success() {
        Ok("Forward removed successfully".to_string())
    } else {
        let stderr = String::from_utf8_lossy(&output.stderr).to_string();
        // Removing a forward that does not exist is benign.
        if stderr.contains("not found") {
            Ok("No matching forward to remove".to_string())
        } else {
            Err(stderr)
        }
    }
}

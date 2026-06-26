// Learn more about Tauri commands at https://tauri.app/develop/calling-rust/
mod adb;

#[tauri::command]
fn greet(name: &str) -> String {
    format!("Hello, {}! You've been greeted from Rust!", name)
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .plugin(tauri_plugin_http::init())
        .plugin(tauri_plugin_opener::init())
        .invoke_handler(tauri::generate_handler![
            greet,
            adb::get_adb_status,
            adb::list_devices,
            adb::forward_port,
            adb::remove_forwards
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}

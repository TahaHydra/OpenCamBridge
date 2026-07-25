mod adb;
mod logger;
mod nv12_preview;
mod sync_state;
mod virtualcam;
mod winproc;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .plugin(tauri_plugin_http::init())
        .plugin(tauri_plugin_opener::init())
        .manage(virtualcam::VirtualCamManager::new())
        .manage(logger::SessionLog::new())
        .manage(nv12_preview::Nv12PreviewReader::new())
        .invoke_handler(tauri::generate_handler![
            adb::get_adb_status,
            adb::list_devices,
            adb::forward_port,
            adb::remove_forwards,
            virtualcam::check_virtual_camera_backend,
            virtualcam::register_virtual_camera_backend,
            virtualcam::unregister_virtual_camera_backend,
            virtualcam::get_virtual_camera_backend_details,
            virtualcam::start_virtual_camera_host,
            virtualcam::stop_virtual_camera_host,
            virtualcam::start_virtual_camera_feeder,
            virtualcam::stop_virtual_camera_feeder,
            virtualcam::get_virtual_camera_status,
            nv12_preview::get_nv12_preview_frame,
            nv12_preview::get_nv12_preview_cursor_stats,
            logger::start_log_session,
            logger::append_log,
            logger::get_log_path,
            logger::read_log_tail,
            logger::clear_log,
            logger::open_logs_folder
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}

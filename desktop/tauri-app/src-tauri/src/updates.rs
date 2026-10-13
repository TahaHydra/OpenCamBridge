//! Optional, explicit HTTPS release checks. Never downloads or executes updates.
use serde::{Deserialize, Serialize};
use std::io::Read;
use std::time::Duration;

#[derive(Deserialize)]
struct Release {
    tag_name: String,
    draft: bool,
    prerelease: bool,
}
#[derive(Serialize)]
pub struct ReleaseInfo {
    version: String,
    release_url: String,
}

#[tauri::command]
pub async fn check_desktop_update() -> Result<Option<ReleaseInfo>, String> {
    tauri::async_runtime::spawn_blocking(|| {
        let client = reqwest::blocking::Client::builder()
            .timeout(Duration::from_secs(10))
            .redirect(reqwest::redirect::Policy::none())
            .build()
            .map_err(|_| "Cannot initialize update check")?;
        let response = client
            .get("https://api.github.com/repos/TahaHydra/OpenCamBridge/releases/latest")
            .header("User-Agent", "OpenCamBridge-release-check")
            .header("Accept", "application/vnd.github+json")
            .send()
            .map_err(|_| "Could not contact GitHub. Local camera operation is unaffected.")?;
        if response.status() == reqwest::StatusCode::NOT_FOUND {
            return Ok(None);
        }
        if !response.status().is_success() {
            return Err("GitHub release check is temporarily unavailable".into());
        }
        let mut bytes = Vec::new();
        response
            .take(65537)
            .read_to_end(&mut bytes)
            .map_err(|_| "Cannot read release information")?;
        if bytes.len() > 65536 {
            return Err("Release response exceeds the size limit".into());
        }
        let release: Release =
            serde_json::from_slice(&bytes).map_err(|_| "Invalid release information")?;
        if release.draft || release.prerelease {
            return Ok(None);
        }
        if release.tag_name.len() > 48 {
            return Err("Invalid release version".into());
        }
        Ok(Some(ReleaseInfo {
            version: release.tag_name,
            release_url: "https://github.com/TahaHydra/OpenCamBridge/releases/latest".into(),
        }))
    })
    .await
    .map_err(|_| "Update check task failed".to_string())?
}

mod crypto;
mod network;
mod policy;
mod reconnect;
mod session;
mod store;

use serde::Serialize;
use session::{Invitation, InvitationView, Status};
use std::sync::{Arc, Mutex};
use store::{SavedPhone, Store};
use tauri::Manager;

struct Active {
    invitation: Arc<Mutex<Invitation>>,
    worker: std::thread::JoinHandle<()>,
}
#[derive(Default)]
pub struct PairingManager {
    store: Mutex<Option<Arc<Mutex<Store>>>>,
    active: Mutex<Option<Active>>,
}
impl PairingManager {
    fn store(&self, app: &tauri::AppHandle) -> Result<Arc<Mutex<Store>>, String> {
        let mut slot = self
            .store
            .lock()
            .map_err(|_| "Pairing storage unavailable")?;
        if slot.is_none() {
            let directory = app
                .path()
                .app_local_data_dir()
                .map_err(|_| "Cannot locate pairing storage")?;
            *slot = Some(Arc::new(Mutex::new(Store::load(&directory)?)));
        }
        Ok(slot.as_ref().unwrap().clone())
    }
}
impl Drop for PairingManager {
    fn drop(&mut self) {
        if let Ok(active) = self.active.get_mut() {
            if let Some(active) = active.take() {
                active
                    .invitation
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .cancel();
                let _ = active.worker.join();
            }
        }
    }
}

#[tauri::command]
pub async fn pairing_start(app: tauri::AppHandle) -> Result<InvitationView, String> {
    tauri::async_runtime::spawn_blocking(move || {
        let manager = app.state::<PairingManager>();
        let store = manager.store(&app)?;
        let mut slot = manager
            .active
            .lock()
            .map_err(|_| "Pairing state unavailable")?;
        if let Some(old) = slot.take() {
            old.invitation
                .lock()
                .map_err(|_| "Pairing state unavailable")?
                .cancel();
            let _ = old.worker.join();
        }
        let hosts = network::hosts()?;
        let listener = network::Listener::bind()?;
        let pc_id = store
            .lock()
            .map_err(|_| "Pairing storage unavailable")?
            .pc_id
            .clone();
        let name: String = std::env::var("COMPUTERNAME")
            .unwrap_or_else(|_| "Windows PC".into())
            .chars()
            .filter(|c| !c.is_control())
            .take(80)
            .collect();
        let invitation = Invitation::new(pc_id, name, hosts, listener.port()?)?;
        let view = invitation.view.clone();
        let invitation = Arc::new(Mutex::new(invitation));
        let worker = listener.run(invitation.clone(), store);
        *slot = Some(Active { invitation, worker });
        Ok(view)
    })
    .await
    .map_err(|_| "Pairing worker failed")?
}

#[tauri::command]
pub fn pairing_status(
    id: String,
    manager: tauri::State<'_, PairingManager>,
) -> Result<Status, String> {
    let slot = manager
        .active
        .lock()
        .map_err(|_| "Pairing state unavailable")?;
    if let Some(active) = slot.as_ref() {
        let invitation = active
            .invitation
            .lock()
            .map_err(|_| "Pairing state unavailable")?;
        if invitation.view.id == id {
            return Ok(invitation.status());
        }
    }
    Ok(Status {
        state: "cancelled",
        phone: None,
    })
}

#[tauri::command]
pub fn pairing_cancel(id: String, manager: tauri::State<'_, PairingManager>) -> Result<(), String> {
    let slot = manager
        .active
        .lock()
        .map_err(|_| "Pairing state unavailable")?;
    if let Some(active) = slot.as_ref() {
        let mut invitation = active
            .invitation
            .lock()
            .map_err(|_| "Pairing state unavailable")?;
        if invitation.view.id == id {
            invitation.cancel();
        }
    }
    Ok(())
}

#[tauri::command]
pub async fn pairing_list(app: tauri::AppHandle) -> Result<Vec<SavedPhone>, String> {
    tauri::async_runtime::spawn_blocking(move || {
        let store = app.state::<PairingManager>().store(&app)?;
        let saved = store.lock().map_err(|_| "Pairing storage unavailable")?;
        Ok(saved
            .phones
            .iter()
            .map(|credential| credential.phone.clone())
            .collect())
    })
    .await
    .map_err(|_| "Pairing worker failed")?
}

#[tauri::command]
pub async fn pairing_forget(phone_id: String, app: tauri::AppHandle) -> Result<(), String> {
    tauri::async_runtime::spawn_blocking(move || {
        let store = app.state::<PairingManager>().store(&app)?;
        let result = store
            .lock()
            .map_err(|_| "Pairing storage unavailable")?
            .forget(&phone_id);
        result
    })
    .await
    .map_err(|_| "Pairing worker failed")?
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Connection {
    base_url: String,
    token: String,
    phone_id: String,
    name: String,
    port: u16,
}
#[tauri::command]
pub async fn pairing_connect(
    phone_id: String,
    app: tauri::AppHandle,
) -> Result<Connection, String> {
    tauri::async_runtime::spawn_blocking(move || {
        let store = app.state::<PairingManager>().store(&app)?;
        let mut credential = store
            .lock()
            .map_err(|_| "Pairing storage unavailable")?
            .phones
            .iter()
            .find(|item| item.phone.phone_id == phone_id)
            .cloned()
            .ok_or("Phone is not paired")?;
        let (host, port) = reconnect::resolve(&credential)?;
        let mut saved = store.lock().map_err(|_| "Pairing storage unavailable")?;
        if !saved.phones.iter().any(|item| {
            item.phone.phone_id == phone_id
                && item.credential_id == credential.credential_id
                && item.token == credential.token
        }) {
            return Err("Pairing was forgotten or replaced. Select the phone again.".into());
        }
        credential.phone.last_host = host.clone();
        credential.phone.port = port;
        saved.upsert(credential.clone())?;
        Ok(Connection {
            base_url: format!("http://{host}:{port}"),
            token: credential.token.clone(),
            phone_id,
            name: credential.phone.name.clone(),
            port,
        })
    })
    .await
    .map_err(|_| "Pairing worker failed")?
}

use super::crypto::random_hex;
use serde::{Deserialize, Serialize};
use std::{
    fs,
    io::Write,
    path::{Path, PathBuf},
};
use windows::Win32::{
    Foundation::{LocalFree, HLOCAL},
    Security::Cryptography::{
        CryptProtectData, CryptUnprotectData, CRYPTPROTECT_UI_FORBIDDEN, CRYPT_INTEGER_BLOB,
    },
};
use zeroize::Zeroizing;

fn protect(bytes: &[u8], decrypt: bool) -> Result<Vec<u8>, String> {
    let input = CRYPT_INTEGER_BLOB {
        cbData: bytes.len() as u32,
        pbData: bytes.as_ptr() as *mut u8,
    };
    let mut output = CRYPT_INTEGER_BLOB::default();
    unsafe {
        let result = if decrypt {
            CryptUnprotectData(
                &input,
                None,
                None,
                None,
                None,
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut output,
            )
        } else {
            CryptProtectData(
                &input,
                None,
                None,
                None,
                None,
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut output,
            )
        };
        result.map_err(|_| {
            "Windows could not unlock the saved pairing. Pair again on this Windows account."
                .to_string()
        })?;
        let value = std::slice::from_raw_parts(output.pbData, output.cbData as usize).to_vec();
        // Clear DPAPI's unmanaged plaintext before releasing it.
        std::ptr::write_bytes(output.pbData, 0, output.cbData as usize);
        LocalFree(Some(HLOCAL(output.pbData.cast())));
        Ok(value)
    }
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SavedPhone {
    pub phone_id: String,
    pub name: String,
    pub last_host: String,
    pub port: u16,
    pub paired_at: u64,
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Credential {
    #[serde(flatten)]
    pub phone: SavedPhone,
    pub credential_id: String,
    pub token: String,
}
impl Drop for Credential {
    fn drop(&mut self) {
        use zeroize::Zeroize;
        self.token.zeroize();
    }
}
#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Store {
    pub pc_id: String,
    pub phones: Vec<Credential>,
    #[serde(skip)]
    path: PathBuf,
}
impl Store {
    pub fn load(directory: &Path) -> Result<Self, String> {
        fs::create_dir_all(directory).map_err(|_| "Cannot create pairing storage")?;
        let path = directory.join("paired-phones.dpapi");
        if !path.exists() {
            let store = Self {
                pc_id: random_hex(16),
                phones: vec![],
                path,
            };
            store.save()?;
            return Ok(store);
        }
        let encrypted = fs::read(&path).map_err(|_| "Cannot read saved pairings")?;
        if encrypted.len() > 128 * 1024 {
            return Err("Saved pairing storage is invalid".into());
        }
        let plaintext = Zeroizing::new(protect(&encrypted, true)?);
        let mut store: Self =
            serde_json::from_slice(&plaintext).map_err(|_| "Saved pairing storage is invalid")?;
        store.path = path;
        Ok(store)
    }
    pub fn save(&self) -> Result<(), String> {
        let plaintext = Zeroizing::new(
            serde_json::to_vec(self).map_err(|_| "Cannot serialize saved pairings")?,
        );
        let encrypted = protect(&plaintext, false)?;
        let temp = self.path.with_extension("tmp");
        let mut file = fs::File::create(&temp).map_err(|_| "Cannot save pairing")?;
        file.write_all(&encrypted)
            .and_then(|_| file.sync_all())
            .map_err(|_| "Cannot save pairing")?;
        drop(file);
        // Windows rename replaces existing destination atomically (MoveFileEx).
        fs::rename(&temp, &self.path).map_err(|_| "Cannot replace saved pairing".to_string())
    }
    pub fn upsert(&mut self, credential: Credential) -> Result<(), String> {
        let mut next = self.phones.clone();
        next.retain(|item| item.phone.phone_id != credential.phone.phone_id);
        if next.len() >= 20 {
            return Err("Forget an old phone before pairing another".into());
        }
        next.push(credential);
        let old = std::mem::replace(&mut self.phones, next);
        if let Err(error) = self.save() {
            self.phones = old;
            return Err(error);
        }
        Ok(())
    }
    pub fn forget(&mut self, phone_id: &str) -> Result<(), String> {
        let old = self.phones.clone();
        self.phones.retain(|item| item.phone.phone_id != phone_id);
        if let Err(error) = self.save() {
            self.phones = old;
            return Err(error);
        }
        Ok(())
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn saved_credentials_are_dpapi_protected_and_corruption_fails_closed() {
        let input = b"credential-never-save-in-plaintext";
        let encrypted = protect(input, false).unwrap();
        assert!(!encrypted.windows(input.len()).any(|part| part == input));
        assert_eq!(protect(&encrypted, true).unwrap(), input);
        assert!(protect(b"corrupted", true).is_err());
    }
    #[test]
    fn saved_phone_survives_atomic_replace_and_forget() {
        let directory = std::env::temp_dir().join(format!("ocb-pairing-test-{}", random_hex(16)));
        let mut store = Store::load(&directory).unwrap();
        let pc_id = store.pc_id.clone();
        let credential = Credential {
            phone: SavedPhone {
                phone_id: random_hex(16),
                name: "Test phone".into(),
                last_host: "192.168.1.2".into(),
                port: 8080,
                paired_at: 1,
            },
            credential_id: random_hex(16),
            token: random_hex(32),
        };
        store.upsert(credential.clone()).unwrap();
        let mut restored = Store::load(&directory).unwrap();
        assert_eq!(restored.pc_id, pc_id);
        assert_eq!(restored.phones[0].token, credential.token);
        restored.forget(&credential.phone.phone_id).unwrap();
        assert!(Store::load(&directory).unwrap().phones.is_empty());
        std::fs::remove_dir_all(&directory).unwrap();
    }
}

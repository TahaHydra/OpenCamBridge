//! Installed resources must never depend on the shortcut's working directory.
use std::path::{Path, PathBuf};

fn installed_root(executable: &Path) -> Option<PathBuf> {
    let root = executable.parent()?;
    root.join("windows/virtual-camera-mediafoundation/rust-frame-producer/target/release/rust-frame-producer.exe")
        .is_file()
        .then(|| root.to_path_buf())
}

pub fn root() -> PathBuf {
    let executable = std::env::current_exe().unwrap_or_default();
    resolve(&executable, Path::new(env!("CARGO_MANIFEST_DIR")))
}

fn resolve(executable: &Path, manifest: &Path) -> PathBuf {
    if let Some(root) = installed_root(executable) {
        return root;
    }
    // An incomplete installed package must never load the developer's copy.
    if executable.starts_with(manifest.join("target"))
        && manifest
            .join("../../../protocol/ring-abi.schema.json")
            .is_file()
    {
        return manifest.join("../../..");
    }
    executable.parent().unwrap_or(Path::new("")).to_path_buf()
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn installed_resources_win_over_a_foreign_working_directory() {
        let root = std::env::temp_dir().join(format!("ocb-runtime-paths-{}", std::process::id()));
        let producer = root.join("windows/virtual-camera-mediafoundation/rust-frame-producer/target/release/rust-frame-producer.exe");
        std::fs::create_dir_all(producer.parent().unwrap()).unwrap();
        std::fs::write(&producer, b"test").unwrap();
        assert_eq!(
            installed_root(&root.join("OpenCamBridge.exe")),
            Some(root.clone())
        );
        std::fs::remove_file(producer).unwrap();
    }
    #[test]
    fn source_fallback_is_not_selected_by_an_empty_install_directory() {
        assert!(installed_root(Path::new("C:/unrelated/tauri-app.exe")).is_none());
        assert_eq!(
            resolve(
                Path::new("C:/unrelated/tauri-app.exe"),
                Path::new(env!("CARGO_MANIFEST_DIR"))
            ),
            PathBuf::from("C:/unrelated")
        );
        assert!(PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../../../protocol/ring-abi.schema.json")
            .is_file());
    }
}

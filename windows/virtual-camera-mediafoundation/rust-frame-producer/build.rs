use std::{env, path::Path, process::Command};

fn main() {
    println!("cargo:rerun-if-env-changed=OCB_SOURCE_COMMIT");
    let explicit = env::var("OCB_SOURCE_COMMIT").ok().filter(|v| !v.is_empty());
    let manifest = env::var("CARGO_MANIFEST_DIR").unwrap_or_default();
    let repo = Path::new(&manifest)
        .ancestors()
        .nth(3)
        .unwrap_or(Path::new(&manifest));
    let commit = explicit
        .or_else(|| {
            Command::new("git")
                .args(["rev-parse", "HEAD"])
                .current_dir(repo)
                .output()
                .ok()
                .filter(|o| o.status.success())
                .and_then(|o| String::from_utf8(o.stdout).ok())
                .map(|s| s.trim().to_owned())
                .filter(|s| !s.is_empty())
        })
        .unwrap_or_else(|| "unknown".to_owned());
    println!("cargo:rustc-env=OCB_SOURCE_COMMIT={commit}");

    let git_head = repo.join(".git").join("HEAD");
    if git_head.exists() {
        println!("cargo:rerun-if-changed={}", git_head.display());
    }
}

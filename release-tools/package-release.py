"""Collect a clean, committed release candidate; never includes private signing keys."""
from pathlib import Path
import subprocess
import json
import hashlib
import shutil
import base64

ROOT = Path(__file__).resolve().parents[1]
def git(*args):
    return subprocess.check_output(["git", "-C", str(ROOT), *args], text=True).strip()
if git("status", "--porcelain"):
    raise SystemExit("Commit/review all intended source first; the source archive must match the build.")
config = json.loads((ROOT / "desktop/tauri-app/src-tauri/tauri.conf.json").read_text(encoding="utf-8"))
version = config["version"]
out = ROOT / "release-output" / version
out.mkdir(parents=True, exist_ok=True)
artifacts = {
    f"OpenCamBridge_{version}_x64_en-US.msi": ROOT / f"desktop/tauri-app/src-tauri/target/release/bundle/msi/OpenCamBridge_{version}_x64_en-US.msi",
    f"OpenCamBridge-{version}-android.apk": ROOT / "android/app/build/outputs/apk/release/app-release.apk",
    f"OpenCamBridge-{version}-play.aab": ROOT / "android/app/build/outputs/bundle/release/app-release.aab",
    "upload-certificate.pem": ROOT / ".release-secrets/upload-certificate.pem",
}
for name, source in artifacts.items():
    if not source.is_file():
        raise SystemExit(f"Required artifact is missing: {source}")
    shutil.copyfile(source, out / name)
source_name = f"OpenCamBridge-{version}-source.zip"
subprocess.run(["git", "-C", str(ROOT), "archive", "--format=zip", f"--output={out / source_name}", "HEAD"], check=True)
shutil.copytree(ROOT / "branding/generated", out / "graphics", dirs_exist_ok=True)
shutil.copytree(ROOT / "docs/release", out / "documentation", dirs_exist_ok=True, ignore=shutil.ignore_patterns("third-party"))
shutil.copytree(ROOT / "docs/release/third-party", out / "THIRD-PARTY-NOTICES", dirs_exist_ok=True)
for name in ("LICENSE", "LICENSES.md"):
    shutil.copyfile(ROOT / name, out / name)
def sha256(path):
    with path.open("rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest()
certificate = (out / "upload-certificate.pem").read_text(encoding="ascii")
der = base64.b64decode(''.join(line for line in certificate.splitlines() if not line.startswith('---')))
manifest = {
    "version": version, "androidVersionCode": 1, "sourceCommit": git("rev-parse", "HEAD"),
    "branch": git("branch", "--show-current"), "status": "signed-release-candidate-not-published",
    "stableStreamingBase": "78445588732df49b46ec506d49c8d4217fdef759",
    "preservedExperimentalCheckpoint": "24928d7e03d460e6c7832f0532de33587c9b9723",
    "ringAbi": {"version": 4, "hash": "0x9582d952654a8f3d"},
    "windowsSignerSha1": "F7CF5FB6237A244E81451045FD946452178F9485",
    "androidUploadCertificateSha256": hashlib.sha256(der).hexdigest(),
    "artifacts": {name: {"sha256": sha256(out / name), "bytes": (out / name).stat().st_size} for name in [*artifacts, source_name]},
    "remainingGates": ["Actual store screenshots deferred", "Publish privacy/guide and verify support mailbox", "Play Console declarations, account/testing requirements and delivery validation", "Clean Windows PC and fresh signed Android LAN acceptance", "Dependency licence/patent distribution review"],
}
(out / "release-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
checksums = []
for path in sorted(out.rglob('*')):
    if path.is_file() and path.name != "SHA256SUMS.txt":
        checksums.append(f"{sha256(path)}  {path.relative_to(out).as_posix()}")
(out / "SHA256SUMS.txt").write_text('\n'.join(checksums) + '\n', encoding="utf-8")
print("Prepared signed release candidate:", out)

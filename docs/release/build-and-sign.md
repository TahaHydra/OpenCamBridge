# Build and sign release candidates

Run from the repository root on the release Windows machine. Prerequisites:
Android Studio JBR/SDK 36, the pinned Gradle wrapper, Node/npm, Rust, Visual Studio
2022 C++ Build Tools, Windows SDK, NuGet, Pillow and an unlocked SimplySign
identity. Scripts use the current machine's SDK/signing paths; review these when
moving machines. No script publishes a release or installs on a phone.

```powershell
python release-tools/generate-branding.py
python release-tools/build-release-site.py
cd android
.\gradlew.bat -I ../release-tools/android-notices.init.gradle :app:exportReleaseDependencyInventory --no-daemon
cd ..
python release-tools/collect-notices.py
powershell -NoProfile -ExecutionPolicy Bypass -File release-tools/android-release.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File release-tools/windows-release.ps1 -BuildNative
```

On first setup only, pass `-CreateUploadKey` to `android-release.ps1`; it refuses
to replace an existing key. Back up the PKCS12 key and its password separately.
Private signing files stay under ignored `.release-secrets/`. Do not paste their
contents into terminals, chats, logs or release metadata. Only the public upload
certificate belongs in a publisher package.

`windows-release.ps1` stages native release files, app-local Microsoft CRT DLLs,
licences and guides. It signs the canonical producer/host/DLL, copies signed host
files to the companion's alternate path, then builds/signs the app and MSI.
Omit `-BuildNative` only when those native binaries have already been built from
the intended source. Staging alone overwrites signed copies with source outputs;
always complete signing after staging. Use the release config, not a bare Tauri
build, for distribution.

Checks run separately:

```powershell
cd desktop/tauri-app
npm test
cargo test --locked --manifest-path src-tauri/Cargo.toml
cd ../..
python release-tools/audit-apk.py android/app/build/outputs/apk/release/app-release.apk
powershell -NoProfile -ExecutionPolicy Bypass -File release-tools/verify-windows.ps1 -MsiPath desktop/tauri-app/src-tauri/target/release/bundle/msi/OpenCamBridge_0.1.0_x64_en-US.msi
```

Verify Android APK with SDK `apksigner verify --verbose --print-certs` and AAB
with JBR `jarsigner -verify`. Confirm the expected upload certificate, version
name/code and target SDK. These checks do not replace Play's device delivery
checks or running on a 16 KiB device.

Before publishing, create SHA-256 checksums and an exact source archive from the
reviewed source commit. Keep matching source, GPL licence and dependency notices
available alongside binaries. Do not archive HEAD while using uncommitted source
and claim that archive matches the binaries. Increment all application versions
and Android version code before a later release; keep the MSI upgrade GUID.

After committing the reviewed source, `python release-tools/package-release.py`
collects binaries, graphics, website pages, documentation, public upload
certificate, dependency notices, exact source archive and checksums into
`release-output/<version>/`. It refuses to package an uncommitted working tree.
It does not verify new binaries automatically: complete signing/build checks
first, then inspect the manifest before publishing.

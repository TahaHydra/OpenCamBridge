# OpenCamBridge

OpenCamBridge is a free open-source phone-as-webcam project.
No cloud, no account, no telemetry, no ads, no watermark.

## V1 scope

OpenCamBridge V1 is a clean, privacy-first, open-source Android-to-Windows
webcam: no cloud, no account, no telemetry, no ads, no watermark. USB-first,
LAN optional with a token. **MJPEG is the stable production path.** H.264 is
kept in the tree but hidden behind Developer/Experimental mode and is not a V1
release path.

Normal users independently choose Resolution, Frame Rate, JPEG Quality, and
target Bandwidth/Auto-quality — the app no longer drives capture through bundled
profile presets. FPS and resolution are separate controls; the UI only offers a
frame rate the selected lens actually reports at the chosen resolution.

## Architecture Pipeline

- **Stable V1 (MJPEG)**: Android CameraX -> `/stream.mjpeg` -> Rust producer -> shared memory framebuffer -> Media Foundation virtual camera -> OBS.
- **Developer-only (H.264, experimental/unstable)**: Android MediaCodec (Constrained Baseline) -> `/stream.h264` (Annex B) -> Rust producer `--source h264` -> bundled openh264 decoder -> shared memory framebuffer -> virtual camera. Hidden unless Developer/Experimental mode is enabled in the desktop app; never auto-starts. The openh264 decoder still errors (`Native:16`) on some phone encoder output — use MJPEG.

All lenses the phone exposes (main, ultrawide, telephoto, front, external)
are selectable; the app negotiates per-device resolutions, FPS ranges, torch
availability, and encoder input formats instead of assuming fixed values. The
desktop hides torch on lenses without a flash and disables frame rates a lens
cannot deliver at the chosen resolution.

Details: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and [protocol/SPEC.md](protocol/SPEC.md).

## Connecting the phone

- **USB (recommended, default)**: the phone binds to `127.0.0.1` only; the
  desktop app runs `adb forward` for you (Connection screen -> USB tab).
  Video never leaves the cable, and no token is needed.
- **Wi-Fi (LAN)**: switch the phone to *LAN Token* mode (app -> Security tab),
  then enter the phone URL and the 32-character access token in the desktop
  app's Wi-Fi tab. Every endpoint except `/health` requires the token.
  Security settings themselves can only be changed from the phone or over USB.

## Current stable workflow

```powershell
cd C:\Dev\OpenCamBridge
.\dev-reset.ps1
.\dev-start.ps1
```

## When C++ Media Foundation code changes

```powershell
cd C:\Dev\OpenCamBridge
.\dev-build-vcam.ps1
.\dev-reset.ps1
.\dev-start.ps1
```

`dev-build-vcam.ps1` works on a fresh clone with no manual NuGet steps: it
restores the pinned packages from `VirtualCameraMediaSource\packages.config`
into `windows\virtual-camera-mediafoundation\packages` (location fixed by the
`nuget.config` next to the `.sln`) the first time, which needs network access;
later runs are offline. It builds the `.vcxproj` with an explicit
`/p:SolutionDir` so package imports and the output dir
(`windows\virtual-camera-mediafoundation\x64\Release`) resolve exactly like a
Visual Studio solution build. Flags: `-ForceKillApps` also closes
Teams/Zoom/etc. when the DLL is locked; `-NoKill` skips all process/service
kills (useful for CI or scripted verification).

## OBS setup

Use:

* Source: Video Capture Device
* Device: OpenCamBridge Camera
* Resolution/FPS Type: Custom
* Match the Resolution and Frame Rate you selected in the desktop app
  (e.g. 1280x720 @ 30, or 1920x1080 @ 60 on a phone/lens that supports it)
* Video Format: Any or RGB32

Do not use Device Default during development.

## Troubleshooting

If DLL copy fails:

```powershell
tasklist /svc /fi "PID eq <PID>"
Stop-Service FrameServer -Force
Stop-Service FrameServerMonitor -Force
```

If still locked, reboot and rerun `dev-build-vcam.ps1` before opening OBS/Tauri.

If OBS is blue:

```powershell
Get-Content C:\ProgramData\OpenCamBridge\vcam.log -Tail 100
Get-Process rust-frame-producer
Invoke-RestMethod http://127.0.0.1:8080/api/stream/metrics
```

## Initial V1 Goals

- Android phone camera over Wi-Fi
- Android USB via adb reverse
- Browser MJPEG stream
- Local HTTP control API
- Windows virtual camera driver
- No cloud, No account, No ads, No telemetry, No watermark

## Not in V1

- iOS implementation
- Bluetooth video
- WebRTC
- macOS virtual camera driver
- HEVC

## Build from source

```powershell
# Android APK
cd android
.\gradlew.bat assembleDebug
adb install -r .\app\build\outputs\apk\debug\app-debug.apk

# Rust frame producer
cd windows\virtual-camera-mediafoundation\rust-frame-producer
cargo build --release

# Desktop app
cd desktop\tauri-app
npm ci
npx tsc --noEmit
cargo check --manifest-path .\src-tauri\Cargo.toml

# Media Foundation virtual camera DLL (from repo root)
.\dev-build-vcam.ps1
```

## Diagnostics

The desktop app has a copyable **Diagnostics** panel (in the control panel)
showing the selected lens/resolution/FPS, target-vs-actual FPS, producer
in/out FPS, bandwidth, latency, dropped frames, torch/rotation state, and the
last errors. Use **Copy** to grab a snapshot for bug reports. The Android app
has a **Logs** tab covering control/camera errors.

## Known Limitations

- No audio support.
- No iOS app.
- No macOS virtual camera driver yet.
- **H.264 is developer-only and unstable**: the bundled openh264 decoder still
  errors (`Native:16`) on some phone encoder output. MJPEG is the supported V1
  path. See the TODO in `rust-frame-producer/src/main.rs` for the rewrite plan.
- 60 FPS depends on the phone lens + resolution + lighting; the UI reports the
  actual delivered rate and only offers frame rates the lens supports.
- Building the Rust producer requires a C/C++ compiler (openh264 is compiled
  from source; installing `nasm` is optional and only enables its faster
  assembly routines).

## Release Checklist

Before tagging a release, ensure:
- [ ] Android build completes (`.\gradlew.bat assembleDebug` / `assembleRelease`)
- [ ] Rust producer builds (`cargo build --release`)
- [ ] Desktop type-checks and its backend compiles (`npx tsc --noEmit`, `cargo check --manifest-path .\src-tauri\Cargo.toml`)
- [ ] Tauri app builds (`npm run tauri build`)
- [ ] Virtual camera DLL builds from a clean clone (`.\dev-build-vcam.ps1`)
- [ ] Pipeline runs correctly via `dev-reset.ps1` and `dev-start.ps1`
- [ ] Runtime matrix in [docs/RUNTIME-CHECKLIST.md](docs/RUNTIME-CHECKLIST.md) passes on target devices
- [ ] MJPEG 720p60 works on a supported phone; 1080p falls back honestly when unsupported
- [ ] Torch shows only on lenses that support it and works there
- [ ] Every lens in the camera dropdown actually switches the picture
- [ ] Phone and desktop controls stay in sync
- [ ] H.264 is hidden unless Developer mode is on; MJPEG is the default
- [ ] SHA256 hashes generated for all release artifacts (below)

## Generating release artifact hashes

Publish a SHA256 for every binary you ship (APK, installer, producer exe) so
users can verify downloads:

```powershell
# Single file
Get-FileHash .\app-release.apk -Algorithm SHA256

# All artifacts in a folder -> SHA256SUMS.txt
Get-ChildItem .\release\* -File |
  Get-FileHash -Algorithm SHA256 |
  ForEach-Object { "{0}  {1}" -f $_.Hash.ToLower(), (Split-Path $_.Path -Leaf) } |
  Out-File -Encoding ascii .\release\SHA256SUMS.txt
```

## Security Note

- **USB mode (default)** is recommended and binds securely to 127.0.0.1.
- **LAN mode** requires a 128-bit token; it is enforced from the moment the
  server binds and cannot be disabled remotely, even with a valid token.
- Security settings (mode/port/token) are changeable only from the phone or
  over USB.
- Open unauthenticated LAN camera access is intentionally not supported.

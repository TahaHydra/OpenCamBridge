# OpenCamBridge

OpenCamBridge is a privacy-first Android-to-Windows webcam: no cloud, account, telemetry, ads, or watermark. This branch contains the V2 low-latency streaming engine.

## V2 pipeline

The primary path is:

```text
Android Camera2
  -> MediaCodec H.264 surface encoder
  -> OCB2 framed access units over USB/ADB or authenticated LAN
  -> Windows Media Foundation H.264 decoder with D3D11 output
  -> NV12 two/three-slot ring
  -> Media Foundation virtual camera
```

MJPEG remains a complete compatibility path when H.264 capture/encode/decode is unavailable or the user selects compatibility mode. The primary H.264 path does not pass camera frames through Kotlin YUV conversion, and the Windows virtual-camera path remains NV12 unless RGB32 compatibility is negotiated.

Supported consumer formats are NV12 1920x1080 and 1280x720 at 60 or 30 FPS, plus RGB32 fallback. A mode is offered only when the selected camera and encoder expose a complete path. Adaptive preference is 1080p60, 720p60, 1080p30, 720p30, then a canonical MJPEG tuple; explicit profiles never silently adapt.

See [architecture](docs/ARCHITECTURE.md), [protocol](protocol/SPEC.md), [native dependency map](docs/NATIVE_DEPENDENCY_MAP.md), and [validation checklist](docs/VALIDATION.md).

## Connecting

- USB is recommended. Android binds to `127.0.0.1`; the desktop selects an explicit ADB serial and creates a targeted forward.
- LAN binds publicly only in token mode. Every route except `/health` requires the token captured when the server binds. Security mutations remain loopback-only.

## Development

```powershell
cd C:\Dev\OpenCamBridge-Fable
.\dev-reset.ps1
.\dev-start.ps1
```

After native changes:

```powershell
.\dev-build-vcam.ps1
```

The native script builds the production DLL and host, runs installer CLI, ABI, buffer-lock, and NV12-resize self-tests, and compares built/installed/registered/runtime binary identities. Registration commands are explicit:

```powershell
VirtualCamera_Installer.exe --register
VirtualCamera_Installer.exe --unregister
VirtualCamera_Installer.exe --status
VirtualCamera_Installer.exe --mode host
VirtualCamera_Installer.exe --self-test-pipeline
```

Registration requires an elevated terminal. No-argument execution prints usage; it does not enter an inherited sample menu.

## Build from source

```powershell
# Android unit tests, APKs, lint
cd android
.\gradlew.bat testDebugUnitTest assembleDebug assembleRelease lint

# Rust producer
cd ..\windows\virtual-camera-mediafoundation\rust-frame-producer
cargo fmt --check
cargo test --all-targets
cargo build --release

# Desktop backend and frontend
cd ..\..\..\desktop\tauri-app
npm install
npm run test
npm run build
cd src-tauri
cargo fmt --check
cargo test --all-targets
cargo build --release

# Native DLL + host, from repository root
cd ..\..\..
.\dev-build-vcam.ps1 -NoKill
```

## OBS

Add a Video Capture Device, select `OpenCamBridge Camera`, choose Custom resolution/FPS, and match the negotiated consumer format. Prefer NV12; use RGB32 only for compatibility. OBS attachment is reported separately from producer/ring readiness.

## Diagnostics

Desktop diagnostics separate desired, selected, actual, and consumer output; camera/session, GPU bridge, encoded, transport, decoded-unique, virtual-camera-unique, and repeated sample rates; fallback reason; frame latency; drops/replacements; decoder/D3D11 status; and binary identities. A process being alive is not reported as a flowing pipeline.

Useful logs:

```powershell
Get-Content C:\ProgramData\OpenCamBridge\vcam.log -Tail 100
Invoke-RestMethod http://127.0.0.1:8080/api/stream/metrics
```

## Security

USB-only mode binds loopback. LAN uses a UUIDv4 bearer token (122 random bits); token comparison uses `MessageDigest.isEqual`, and remote clients cannot change binding/security state. Tokens are passed through protected process environment only and removed immediately after the producer reads them.

## Current validation status

Automated evidence is tracked in `AUDIT.md`. Phone, OBS, FFmpeg, screen-off, USB reconnect, thermal, live constrained-high-speed, and external allocator acceptance remain `NOT YET TESTED` unless an explicit physical result is recorded there.

No audio, iOS, macOS virtual camera, Bluetooth video, HEVC, or cloud relay is provided.

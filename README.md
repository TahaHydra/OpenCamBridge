# OpenCamBridge

OpenCamBridge is a free open-source phone-as-webcam project.
No cloud, no account, no telemetry, no ads, no watermark.

## Architecture Pipeline

- **Stable V1 (MJPEG)**: Android CameraX -> `/stream.mjpeg` -> Rust producer -> shared memory framebuffer -> Media Foundation virtual camera -> OBS.
- **Experimental (H.264)**: An H.264 encoder endpoint (`/stream.h264`) exists on Android, and the Rust producer has an `--source h264` transport scaffold (connects, authenticates, parses Annex B NAL units, reports stats), but **no H.264 decoder is integrated yet**, so H.264 never reaches the virtual camera and is not OBS-ready.

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

## OBS setup

Use:

* Source: Video Capture Device
* Device: OpenCamBridge Camera
* Resolution/FPS Type: Custom
* Balanced: 1280x720 @ 30
* 1080p60 experimental: 1920x1080 @ 60
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

## Known Limitations

- No audio support.
- No iOS app.
- No macOS virtual camera driver yet.
- H.264 stream cannot yet be consumed by the OBS virtual camera pipeline.

## Release Checklist

Before tagging a release, ensure:
- [ ] Android build completes (`.\gradlew.bat assembleDebug` / `assembleRelease`)
- [ ] Rust producer builds (`cargo build`)
- [ ] Tauri app builds (`npm run tauri build`)
- [ ] Pipeline runs correctly via `dev-reset.ps1` and `dev-start.ps1`
- [ ] OBS Custom Resolution is tested:
  - [ ] 720p30 (Balanced) works flawlessly
  - [ ] 1080p60 (Experimental) produces reasonable framerates
- [ ] Torch and profile changes update UI state reliably
- [ ] Full manual pass of [docs/VALIDATION.md](docs/VALIDATION.md)

## Security Note

- **USB mode (default)** is recommended and binds securely to 127.0.0.1.
- **LAN mode** requires a 128-bit token; it is enforced from the moment the
  server binds and cannot be disabled remotely, even with a valid token.
- Security settings (mode/port/token) are changeable only from the phone or
  over USB.
- Open unauthenticated LAN camera access is intentionally not supported.

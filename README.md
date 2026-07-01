# OpenCamBridge

OpenCamBridge is a free open-source phone-as-webcam project.

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
## Security Note

- **USB mode (default)** is recommended and binds securely to 127.0.0.1.
- **LAN mode** requires a token for authentication.
- Open unauthenticated LAN camera access is intentionally not supported.

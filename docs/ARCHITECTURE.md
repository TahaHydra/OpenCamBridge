# OpenCamBridge Architecture

Last updated for branch `fable/full-product-hardening`.

## Pipeline overview

```
Android phone                         Windows PC
=============                         ==========
CameraX ImageAnalysis (YUV_420_888)
   |
   |  MjpegStreamer: NV21 -> JPEG          (stable path)
   |  H264Streamer:  NV12 -> MediaCodec    (experimental path)
   v
Ktor HTTP server (ControlServer)
   /stream.mjpeg  /stream.h264  /api/*  /obs  /health
   |
   |  USB: adb forward tcp:8080  (default, private)
   |  LAN: token-authenticated HTTP
   v
rust-frame-producer.exe
   decode JPEG -> BGRA32 -> rotate/mirror/resize
   |
   v
Shared memory framebuffer (OCBF header + BGRA pixels)
   C:\ProgramData\OpenCamBridge\framebuffer.bin  (or Global\ section)
   |
   v
Media Foundation virtual camera (VirtualCameraMediaSource.dll,
hosted via VirtualCamera_Installer --mode host)
   |
   v
OBS "Video Capture Device" / Windows camera apps
```

The Tauri desktop app (`desktop/tauri-app`) orchestrates the Windows side:
it connects to the phone (USB or LAN+token), starts/stops the producer and
the virtual camera host, mirrors settings to the phone, and shows metrics.

## Components

### Android app (`android/`)

- `StreamService` - foreground service (camera type); owns the lifecycle
  state machine (STOPPED/STARTING/STREAMING/REBINDING/STOPPING/ERROR), the
  Ktor server, and both streamers. Bind/codec failures propagate into the
  ERROR state; they are not swallowed.
- `MjpegStreamer` - CameraX ImageAnalysis -> NV21 -> JPEG into
  `StreamState.latestFrame` (single latest-frame slot; HTTP side fans out).
- `H264Streamer` - CameraX -> NV12 -> MediaCodec AVC; broadcasts Annex B
  buffers to per-client bounded channels; slow clients are disconnected.
- `ControlServer` - Ktor CIO server; REST API + streams + embedded web UI.
  See `protocol/SPEC.md` for the auth rules (bind-time enforcement,
  loopback-only security settings, constant-time token compare).
- `StreamState` - atomics-based shared state (single source of truth),
  including `torchRequested` so torch survives rebinds.
- `ResolutionPolicy` - profile-driven CameraX resolution selection.

### Rust frame producer (`windows/.../rust-frame-producer/`)

Single binary, three sources:

- `--source mjpeg` (stable): HTTP client with **no total request timeout**
  (the default reqwest 30s timeout would kill long-lived streams), TCP
  keepalive, exponential reconnect backoff, HTTP status checking (401 hints
  at token problems), JPEG decode -> BGRA, explicit `--rotate`/`--mirror`
  or portrait auto-rotate, resize, shared-memory write with QPC timestamp.
- `--source test-pattern`: synthetic frames for debugging the vcam side alone.
- `--source h264` (EXPERIMENTAL SCAFFOLD): transport + Annex B NAL parsing
  and statistics only. **No decoder is integrated; it never writes frames**
  and reports `H264_DECODE_NOT_IMPLEMENTED` in metrics. Decoder candidates,
  in order of preference: Windows Media Foundation H.264 MFT (no new
  redistributables), the openh264 crate, or an ffmpeg helper process.

Metrics: one JSON line per second on stdout (parsed by the Tauri app);
errors also go to stderr (surfaced as `last_error` in the desktop UI).

### Windows virtual camera (`windows/virtual-camera-mediafoundation/`)

Derived from the Microsoft Media Foundation virtual camera sample.
`SimpleMediaSource`/`SimpleMediaStream` read the shared framebuffer through
`SharedMemoryClient`, which validates the OCBF header and nearest-neighbor
scales on resolution mismatch. Registration requires admin
(`register_hklm.bat` / `VirtualCamera_Installer`).

### Desktop app (`desktop/tauri-app/`)

- Connection screen with explicit **USB (recommended)** and **Wi-Fi (LAN)**
  modes; USB mode runs `adb forward` itself; LAN mode requires the token from
  the phone's Security tab and validates it before entering the dashboard.
- All API calls and stream URLs carry the token (header for fetches, query
  parameter for `<img>`/OBS URLs). The producer receives `--token`.
- Producer receives `--rotate`/`--mirror` so the virtual camera output
  matches the preview orientation.
- OBS fallback modes (browser source / window capture via obs-websocket)
  remain available but the Media Foundation virtual camera is the main path.

## Known failure modes and mitigations

- Camera bind failure on Android -> service enters ERROR with the cause in
  `/api/camera/status.lastError`; `/api/stream/recover` retries.
- Producer loses the phone connection -> reconnects with backoff; the vcam
  keeps showing the last written frame; desktop shows the producer error.
- Framebuffer/consumer resolution mismatch -> vcam scales nearest-neighbor
  (visible quality drop) and logs via OutputDebugString.
- LAN token mismatch -> HTTP 401 everywhere except /health; producer metrics
  say "check the LAN access token".

## Non-goals (V1)

No audio, no iOS, no macOS driver, no WebRTC, no cloud, no accounts,
no telemetry, no watermark.

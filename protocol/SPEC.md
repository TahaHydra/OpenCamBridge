# OpenCamBridge Protocol V1

## Security and Auth

- **usbOnly (Default)**: Server binds to `127.0.0.1`. No token required. Only
  reachable from the phone itself or through `adb forward` over USB.
- **lanToken**: Server binds to `0.0.0.0`. Every endpoint except `/health`
  requires the access token, via the `?token=...` query parameter or the
  `X-OpenCamBridge-Token` header.
- Unauthenticated LAN access is not supported.

Enforcement rules (implemented in `ControlServer`):

1. The token requirement is decided by the **bind-time** access mode, not the
   live setting. If the server was bound to `0.0.0.0`, tokens stay mandatory
   even if `accessMode` is later changed at runtime; the new mode only takes
   effect after the streaming service restarts and rebinds.
2. `accessMode`, `port`, and `accessToken` can only be changed by **loopback**
   clients (the phone UI, or a desktop connected through USB `adb forward`).
   Requests from LAN addresses that include these fields have them ignored,
   even with a valid token.
3. Token comparison is constant-time. An empty configured token never matches.
4. Tokens are 32 hex characters (128 bits, generated from `UUID.randomUUID()`).
5. CORS is restricted to the desktop app origins (`tauri.localhost`,
   `localhost:1420`, `127.0.0.1:1420`). The phone web UI and `/obs` page are
   same-origin and unaffected.

## Ports

- HTTP control/API: 8080 (or dynamically assigned)
- Stream: same HTTP server for V1

## Endpoints

GET /health  
Returns plain text `OK`. (No token required.)

GET /api/device/info  
Returns app/device/server info.

GET /api/camera/list  
Returns available Android cameras.

GET /api/camera/status  
Returns current camera/stream state.

GET /api/camera/controls
Returns available control options for the current camera.

GET /api/camera/capabilities
Returns camera capabilities (resolutions, FPS, etc.).

GET /api/settings
Returns current active settings.

POST /api/settings
Updates multiple settings at once. Security-critical fields (`accessMode`,
`port`, `accessToken`) are accepted only from loopback clients (see above).
`accessMode`/`port` changes require a streaming service restart to take
effect, because the server socket binds once at startup.

POST /api/stream/start  
Starts camera capture.

POST /api/stream/stop  
Stops camera capture.

POST /api/stream/recover
Attempts to recover from camera errors.

GET /api/stream/metrics
Returns performance metrics.

POST /api/camera/switch  
Switches camera by camera id.

POST /api/camera/zoom
POST /api/camera/torch
POST /api/camera/autofocus
Various camera hardware controls. Torch state is remembered and restored
after camera rebinds.

POST /api/settings/resolution  
Changes requested resolution.

POST /api/settings/fps  
Changes requested FPS.

POST /api/settings/jpeg-quality  
Changes MJPEG JPEG quality.

POST /api/settings/preview-fit-mode
POST /api/settings/aspect-ratio
Additional settings endpoints.

GET /api/logs
POST /api/logs/clear
Log management endpoints.

GET /stream.mjpeg  
Returns `multipart/x-mixed-replace; boundary=FRAME` MJPEG stream. **(Stable)**
Each part is a complete JPEG with a `Content-Length` header. Output is paced
to the configured FPS. The connection survives camera rebinds (frames pause,
the socket stays open); it closes when streaming stops or errors.

GET /stream.h264
Returns a raw H.264 byte stream. **(Experimental - see "H.264 status" below.)**

GET /api/stream/info
Returns stream metadata: `mode`, `resolution`, `fps`, `h264Bitrate`, plus
`codec`, `container`, `experimental`, and `notes` describing exactly what the
active stream is.

GET /obs
Returns a clean HTML page displaying the MJPEG stream for browser-based OBS
captures. Accepts `fit`, `mirror`, `rotate` query parameters (and `token` in
LAN mode).

## H.264 status (experimental, truthful description)

What exists today:

- Android encodes with `MediaCodec` (`video/avc`) and serves the encoder
  output at `/stream.h264` with content type `video/h264`.
- Bitstream: **Annex B** byte stream (start-code delimited NAL units), no
  container, no framing protocol, no timestamps on the wire.
- SPS/PPS: the codec-config buffer is cached and sent as the first bytes to
  each new subscriber when available. Some device encoders additionally repeat
  SPS/PPS inline before IDR frames. Consumers must tolerate both.
- Encoder profile: the encoder is asked for **Constrained Baseline** (no
  CABAC, no B-slices, no 8x8 transform) when it advertises support, because
  that is the H.264 subset the Windows-side openh264 software decoder handles
  most reliably. Encoders that do not offer the profile keep their default.
  Keyframe interval and bitrate are configurable via settings.
- 1080p60 caveat: real-time software decoding of 1080p60 H.264 with openh264 is
  CPU-bound and may not sustain 60 fps; when the decoder falls behind, the
  producer drops to the next keyframe (visible as a brief jump) and reports the
  transient error in `last_error`. MJPEG is the recommended path for 1080p60.
- Slow subscribers are disconnected rather than having NAL units dropped
  (dropping arbitrary NALs would corrupt the stream until the next IDR).

- The encoder negotiates a concrete raw input layout (NV12 or I420) with the
  device codec and is configured only after CameraX reports the actually
  selected capture size, so the bitstream geometry always matches the frames.
- The encoder is asked to repeat SPS/PPS before IDR frames
  (`prepend-sps-pps-to-idr-frames`); encoders that do not support the key
  ignore it, so consumers must still tolerate config-only startup.
- Every new `/stream.h264` subscriber triggers an immediate sync-frame
  request, so consumers get decodable video right away instead of waiting up
  to a keyframe interval.
- B-slices are disabled (`max-bframes` 0): lower latency, and the openh264
  decoder on the Windows side does not support them. Low-latency and
  realtime-priority hints are set where the encoder supports them.
- `h264Bitrate` changes are applied to the running encoder via
  `MediaCodec.setParameters` (no camera rebind, no stream interruption);
  `h264KeyframeInterval` changes still require a rebind.

Windows consumption (experimental):

- The Rust frame producer's `--source h264` mode decodes the Annex B stream
  with the bundled **openh264** decoder (compiled from source at build time)
  and writes BGRA frames to the shared framebuffer through the same
  rotation/mirror/resize pipeline as MJPEG.
- On decoder-queue overflow or a mid-stream reconnect, the producer drops
  data only until the next SPS/PPS/IDR sync point rather than feeding the
  decoder a corrupt bitstream; decode errors before the first keyframe are
  expected and reported in `last_error`, then clear on recovery.
- This path is **experimental until validated on real devices**; MJPEG
  remains the Stable V1 path.

## Shared framebuffer format (Windows IPC)

Producer: `rust-frame-producer.exe`. Consumer: the Media Foundation virtual
camera media source. Backing store, in order of preference:

1. File mapping of `C:\ProgramData\OpenCamBridge\framebuffer.bin`
   (synchronized with `LockFileEx`), or
2. Named section `Global\OpenCamBridgeFrameBuffer` with mutex
   `Global\OpenCamBridgeFrameMutex`.

Layout: a packed little-endian header followed immediately by pixel data.
Pixel rows are stored **top-down** (row 0 = top of the image). The Media
Foundation consumer copies rows straight and declares the surface top-down by
setting a **positive** `MF_MT_DEFAULT_STRIDE` (= width*4) on its RGB32 media
types. RGB32 in MF otherwise defaults to bottom-up (negative derived stride),
which makes consumers such as OBS render the image upside down. Orientation is
fixed via that media-type attribute, never by flipping rows in the copy (a
flipped copy with a negative locked pitch writes out of bounds → black screen).

| Field         | Type | Meaning                                   |
|---------------|------|-------------------------------------------|
| magic         | u32  | `0x4642434F` ("OCBF")                     |
| version       | u32  | 1                                         |
| width         | u32  | Frame width in pixels                     |
| height        | u32  | Frame height in pixels                    |
| stride        | u32  | Bytes per row (width * 4)                 |
| format        | u32  | 1 = BGRA32                                |
| frame_counter | u64  | Monotonic frame counter                   |
| timestamp_qpc | u64  | QueryPerformanceCounter at write time     |
| data_size     | u32  | Payload size in bytes (width*height*4)    |
| reserved      | u32  | 0                                         |

The mapping is sized for at most 1920x1080 BGRA (`1920*1080*4 + 1024` bytes);
the producer refuses larger configurations.

## Rust producer CLI (summary)

```
rust-frame-producer --source <mjpeg|test-pattern|h264> --url <stream url>
                    [--width W] [--height H] [--fps N] [--profile NAME]
                    [--token TOKEN]       # sent as X-OpenCamBridge-Token
                    [--rotate 0|90|180|270]  # explicit output rotation
                    [--mirror]            # horizontal flip, applied after rotation
```

Without `--rotate`, portrait sources are auto-rotated 90 degrees into
landscape outputs (legacy behavior). One metrics JSON line is printed per
second on stdout; `last_error` is `null` or a human-readable string.

## Rotation model (V1, MJPEG)

Rotation is applied to the actual pixels **on the phone**, before JPEG
encoding, in two composed parts:

1. **Auto-upright**: the streaming service tracks the phone's *physical*
   orientation (accelerometer `OrientationEventListener`, works in background
   and with display auto-rotate locked) and feeds it to CameraX as
   `targetRotation`; each frame is then rotated by
   `imageInfo.rotationDegrees`. Held vertical, horizontal, or upside down, the
   streamed video is always upright.
2. **Manual offset**: `displayRotation` (0/90/180/270) is added on top. It
   remains in the API, but the product UIs no longer expose a rotate button —
   they expose an **orientation mode** instead (stored in `aspectRatio`:
   `auto` | `16:9` | `9:16`) that shapes the preview canvas: `auto` follows the
   phone (vertical phone -> 9:16 preview box), the other two pin it, with
   letterboxing on mismatch. Selecting a mode resets `displayRotation` to 0 so
   stale offsets cannot leave the stream sideways. The virtual camera output
   itself stays 16:9 (consuming apps expect a landscape webcam); vertical video
   is pillarboxed there.

Because `/stream.mjpeg` frames arrive already rotated, **no consumer rotates
again**: the desktop app launches the producer with `--rotate 0`, the `/obs`
page uses `rotate=0`, and the desktop preview applies only mirroring. Frame
dimensions on the wire flip between landscape and portrait as the phone turns;
consumers must not assume a fixed frame size.

Producer fitting into the fixed output size: the Media Foundation virtual
camera renders a fixed output resolution (`--width` x `--height`), so each
frame is fit into that box. A frame whose orientation matches the box is
resized to fill (a plain no-op resize in the matching-16:9 case); a portrait
frame in the landscape box is scaled to fit preserving aspect ratio and
centered with black side bars — never cropped, never stretched. The `--rotate`
CLI flag still exists for standalone/manual producer use, but the desktop app
always passes 0 now.

## V1 scope

- MJPEG is the stable V1 path and the default codec.
- H.264 is **developer-only** for V1: it is hidden behind the desktop app's
  Developer/Experimental mode, never auto-starts, and the bundled openh264
  decoder still fails (`Native:16`) on some phone encoder output. It remains in
  the tree for future work but is not a V1 release path.
- Camera capabilities (per-lens torch availability and per-resolution max FPS)
  are reported by `/api/camera/list` so the UI only offers controls the active
  lens actually supports.
- No audio.  
- No iOS.  
- No macOS virtual camera driver yet.
- No Bluetooth video.  
- No cloud, no accounts, no telemetry.

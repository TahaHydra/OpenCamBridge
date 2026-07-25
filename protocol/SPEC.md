# OpenCamBridge Protocol V2

OCB2 is the low-latency H.264 transport between the Android Camera2 encoder and
the Windows producer. TCP packet boundaries have no meaning: receivers parse
self-delimiting records and pass one complete H.264 access unit at a time to the
decoder. All integers are little-endian.

MJPEG V1 remains available at `/stream.mjpeg` as a compatibility fallback.

## Security and endpoints

- `usbOnly` binds Android to loopback and is reached through a serial-specific
  `adb forward`. It requires no token.
- `lanToken` binds to the LAN and requires `X-OpenCamBridge-Token` (preferred)
  or a `token` query parameter on every endpoint except `/health`.
- Tokens are compared in constant time. LAN callers cannot change the access
  mode, port, or token.
- The desktop passes the token to the producer through its environment, never
  through process arguments. The producer removes the variable immediately
  after reading it.

Streaming endpoints:

- `GET /stream.ocb2` — OCB2 records, content type
  `application/vnd.opencambridge.ocb2`.
- `GET /stream.mjpeg` — multipart MJPEG compatibility stream. Each part has a
  validated `Content-Length`.
- `GET /stream.h264` — retired raw-NAL endpoint; returns HTTP 410.

The existing control, settings, camera, OBS, health, and metrics endpoints are
unchanged.

## OCB2 record header

Every record starts with this fixed 48-byte header, followed immediately by
`payload_length` bytes.

| Offset | Size | Field | Meaning |
|---:|---:|---|---|
| 0 | 4 | magic | ASCII `OCB2` |
| 4 | 2 | version | `2` |
| 6 | 2 | header size | `48` |
| 8 | 2 | record type | See below |
| 10 | 2 | reserved | Must be zero |
| 12 | 4 | flags | Bit field below |
| 16 | 8 | sequence | Monotonic video-frame sequence; non-video records reuse the latest value |
| 24 | 8 | capture timestamp | Monotonic nanoseconds |
| 32 | 8 | encoder timestamp | Signed MediaCodec presentation time in microseconds |
| 40 | 4 | payload length | Maximum 16 MiB |
| 44 | 4 | reserved | Must be zero |

Record types:

| Value | Name | Payload |
|---:|---|---|
| 1 | stream information | UTF-8 JSON |
| 2 | codec configuration | Annex-B SPS/PPS bytes |
| 3 | video access unit | One complete Annex-B H.264 access unit |
| 4 | heartbeat | Empty |
| 5 | end of stream | Optional UTF-8 reason |
| 6 | error | UTF-8 error text |

Flags are `codec configuration = 1`, `keyframe = 2`, `discontinuity = 4`, and
`end of stream = 8`.

The discontinuity flag is a property of the stream, not of one record type, and
is meaningful on any record. A heartbeat may carry it as a bare marker: Android
sends one after discarding a slow client's queued records, so the client resets
in place instead of the connection being closed. A video access unit always
carries one complete access unit and is never used as an empty marker.

The stream-information JSON contains `codec`, `framing`, `width`, `height`,
`fpsNumerator`, `fpsDenominator`, `bitrate`, `cameraId`, `encoderName`,
`hardwareEncoder`, and `pixelFormat`. V2 currently requires H.264,
`annex-b-access-units`, and NV12 decoder output.

## Connection and recovery rules

On each client connection Android sends stream information and current codec
configuration, requests an encoder sync frame, and marks the restart as a
discontinuity. A client resets any partial parser record on reconnect, flushes
its decoder on a discontinuity, and waits for a keyframe before resuming decode.
It never scans arbitrary TCP chunks for Annex-B start codes.

For USB sessions the desktop retains the explicitly selected ADB serial and
local port. It periodically re-applies only that device's `tcp:<port>` forward,
allowing the producer's normal HTTP reconnect loop to recover after a cable
disconnect without restarting the desktop application or producer.

The encoder uses Camera2 directly with the MediaCodec input surface, no
ImageAnalysis/YUV conversion. It uses no B-frames, a one-second keyframe
interval, bounded bitrate, asynchronous output, and output presentation
timestamps. The advertised H.264 modes are the intersection of the selected
Camera2 surface capabilities and a hardware AVC encoder:

1. 1920x1080 at 60 fps
2. 1280x720 at 60 fps
3. 1920x1080 at 30 fps
4. 1280x720 at 30 fps

The first requested/supported mode is used. Unsupported requests fall through
the preference list. Encoder or Windows decoder failure activates MJPEG.

## Windows decode and frame ring

The primary Windows decoder is the Media Foundation H.264 MFT with a D3D11
device manager. Its output subtype is NV12. OpenH264 is retained only as the
software decoder fallback, and its I420 result is interleaved directly to NV12.
Neither H.264 path converts frames to BGRA.
If the software decoder cannot sustain at least 80 percent of the selected rate
for three measurement windows, the producer asks Android to rebind to MJPEG.

Producer and virtual-camera DLL share a version-2 `OCBR` ring. The header is 256
bytes followed by three equal slots. Each slot has a 128-byte metadata header and
space for at most a 1920x1080 NV12 frame.

Ring metadata includes the published slot and sequence, producer heartbeat,
consumer-selected width/height/rate, and total virtual-camera unique/repeated
sample counters. Slot metadata contains write and commit epochs, sequence,
capture/receive/decode timestamps, dimensions, Y/UV strides, pixel format,
payload size, flags, data offset, and NV12 bytes.

The producer fills a non-published slot, commits it with release ordering, then
atomically publishes it. The virtual camera copies only a stable newest slot
whose epoch and all metadata validate. It does not queue old presentation
frames. There is no global pacing mutex. A repeated sample reuses the newest
frame but increments only the repeated counter.

The shared object grants frame write access only to SYSTEM, LOCAL SERVICE, and
the current application user. Creation fails if the current user SID cannot be
resolved; it never falls back to a broadly writable ACL.

## Media Foundation virtual-camera formats

The source advertises, in order:

- NV12 1920x1080 60 fps
- NV12 1920x1080 30 fps
- NV12 1280x720 60 fps
- NV12 1280x720 30 fps
- RGB32 1280x720 30 fps compatibility fallback

NV12 is copied directly. RGB32 conversion happens only when a legacy consumer
selects that fallback. Sample timestamps are monotonically increasing Media
Foundation 100-nanosecond units. A frame is reported as unique only when its
source sequence changes.

## Metrics

Metrics keep capture, encoded-access-unit, received, decoded-unique, and
virtual-camera-unique FPS separate. They also report repeated samples,
transport bitrate, encoder/decoder names and hardware status, source/output
dimensions, latency, replaced frames, and the active fallback reason.

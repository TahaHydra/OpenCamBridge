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
| 44 | 4 | send delta | Microseconds since the previous video record was queued for transmission; `0` when unknown |

### Send delta

This field was reserved through version 2 and is now diagnostic, which is why the
version and header size did not change: senders that never wrote it emitted zero,
and readers that ignore it behave exactly as before. Receivers must treat `0` as
"unknown" rather than "no delay".

It is deliberately a delta and not an absolute timestamp. The phone's monotonic
clock and the desktop's have no fixed relationship, so absolute values are not
comparable across the two machines, but an interval is. Holding the sender's
deltas next to the receiver's arrival deltas is what separates *the encoder
emitted late* from *the transport batched* — the one question that frame-rate
averages cannot answer, because bursty and even delivery average identically.

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
timestamps. Advertised regular modes are complete tuples: camera ID, output
format, resolution, AE FPS range, and minimum frame duration. A rate is
selectable only when that exact Camera2 output/resolution tuple can meet it and
the encoder also supports it. A camera-wide 60 FPS AE range does not turn a
30 FPS 1080p output into a 60 FPS mode. The usual preference order is:

1. 1920x1080 at 60 fps
2. 1280x720 at 60 fps
3. 1920x1080 at 30 fps
4. 1280x720 at 30 fps

The first requested/supported mode is used. Unsupported requests fall through
the preference list. Encoder or Windows decoder failure activates MJPEG. The
selected source rate is carried in stream metadata and remains authoritative
through the producer, ring, preview, and virtual-camera mode list.

Stream information also carries the YUV matrix, nominal range, primaries, and
transfer function reported by MediaCodec. HD SDR defaults to BT.709 limited
range and SD SDR to BT.601 limited range when the encoder omits a value.

## Windows decode and frame ring

The primary Windows decoder is the Media Foundation H.264 MFT with a D3D11
device manager. Its output subtype is NV12. OpenH264 is retained only as the
software decoder fallback, and its I420 result is interleaved directly to NV12.
Neither H.264 path converts frames to BGRA.
If the software decoder cannot sustain at least 80 percent of the selected rate
for three measurement windows, the producer asks Android to rebind to MJPEG.

Producer and virtual-camera DLL share a version-4 `OCBR` ring. The header is 320
bytes followed by sixteen equal slots. Each slot has a 128-byte metadata header
and space for at most a 1920x1080 NV12 frame, so the mapping is roughly 50 MB.

Ring metadata includes the published slot and sequence, producer heartbeat,
consumer-selected width/height/rate, and total virtual-camera unique/repeated
sample counters. Slot metadata contains write and commit epochs, sequence,
capture/receive/decode timestamps, dimensions, Y/UV strides, pixel format,
payload size, flags, data offset, packed colour descriptor, and NV12 bytes. The
descriptor uses a previously reserved field, so the ring ABI is unchanged.

The producer fills a non-published slot, commits it with release ordering, then
atomically publishes it. The virtual camera copies only a stable selected slot
whose epoch and all metadata validate. Its complete Media Foundation sample
request is serialized so concurrent client requests cannot re-arm one timer or
publish catch-up samples while the previous frame is still being copied. A
repeated sample reuses the last stable frame and increments only the repeated
counter.

### Frame history and consumer cursors

Version 4 makes the ring a bounded history rather than a single latest frame. The
distinction matters because the two consumers — the virtual camera and the desktop
preview — read on unrelated clocks, and `published_slot` can only ever name the
newest frame, never what came before it.

`ring_write_sequence` is a monotonic count of committed writes, published after the
slot commits, so it is exactly "how many frames are fully written". Write *n* lives in
slot `(n - 1) % slot_count`, which lets a consumer holding a cursor determine which
frames the ring still holds. Each slot repeats its own `ring_sequence`, so a consumer
can confirm the slot still holds the write it selected. `stream_generation` is bumped
on every reconnect and geometry change; without it a restart is indistinguishable from
catastrophic loss, since both look like the sequence jumping.

Cursors are consumer-local: neither consumer may disturb the other's accounting. The
selection arithmetic is generated into all three languages from
`protocol/ring-abi.schema.json` rather than hand-written per consumer, because a
disagreement about which slot holds a given write would make one consumer read a
different frame than the one it reports. The Rust copy carries the tests.

The virtual camera and desktop preview have separate bounded cursors. The desktop
preview's first call returns the newest committed frame immediately, then drains newer
live frames in order and skips forward when more than three are pending. The virtual
camera starts immediately from a stable frame about 90-100 ms behind the live edge,
then consumes equal-rate sequences in order. Neither cursor changes the other's state.
Both consumers can count *skipped* frames separately from frames already overwritten
in the ring.

### Playout scheduling

Media Foundation requests are paced at the negotiated output interval. A missed
deadline is rebased to the current time; it is never repaid with a sub-frame catch-up
interval. The virtual camera uses a fixed time-sized sequence queue rather than the
adaptive capture-timestamp servo. The queue target is 90 ms rounded up to whole source
intervals: three intervals (about 100 ms) at 30 fps and six at 60 fps.

For equal source and output rates, the consumer releases the next committed sequence
in order. If output is faster than the source, it repeats the previous stable sequence
until the next exists. If the source is faster than output, it recentres at the fixed
live-edge delay, which performs deterministic rate conversion. A generation change
immediately selects a stable frame from the new generation; retained slots from the
old stream are never replayed.

This deliberately replaces the adaptive scheduler in the native shipping path. Live
probing showed that scheduler pinning its target at 140 ms while a healthy 30 fps ring
advanced, reducing virtual-camera unique output to 22-25 fps. The fixed queue delivered
3,044 unique frames from 3,046 ring writes over 101.6 seconds, with three isolated
repeats and no catch-up burst.

The desktop preview uses its own bounded cursor queue and never modifies
virtual-camera consumer state.

The shared object grants frame write access only to SYSTEM, LOCAL SERVICE, and
the current application user. Creation fails if the current user SID cannot be
resolved; it never falls back to a broadly writable ACL.

## Media Foundation virtual-camera formats

The source advertises the current source-matching common NV12 type first, then
safe compatibility modes:

- NV12 1920x1080 30 fps
- NV12 1280x720 30 fps
- NV12 640x480 30 fps
- NV12 1920x1080 60 fps only when the source is genuinely at least 50 fps
- NV12 1280x720 60 fps only when the source is genuinely at least 50 fps
- YUY2 1920x1080, 1280x720, and 640x480 at 30 fps for DirectShow/WebRTC
- RGB32 1280x720 30 fps compatibility fallback
- RGB32 640x480 30 fps compatibility fallback

NV12 is copied directly. RGB32 conversion happens only when a legacy consumer
selects that fallback. Sample timestamps are monotonically increasing Media
Foundation 100-nanosecond units. A frame is reported as unique only when its
source sequence changes. Every media type carries matching BT.709 matrix,
nominal-range, primaries, and transfer-function attributes.

## Metrics

Metrics keep camera capture, phone encoded, transport received, producer
decoded, ring written, preview received/displayed, and virtual-camera
requested/unique/repeated FPS separate. Preview sequence skips, IPC transfer
time, WebGL upload time, producer processing time, phone encode time, playout
depth/target, underruns, late drops, and transport bandwidth retain those
literal meanings. MJPEG queue/drop counters are shown only in MJPEG mode.
Producer processing time is not labelled as end-to-end latency.

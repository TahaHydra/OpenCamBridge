# OpenCamBridge V2 architecture

Last updated for `v2/streaming-engine`.

## End-to-end data path

```text
Camera2 regular / constrained high-speed / high-speed GPU bridge
  -> MediaCodec AVC encoder input Surface (optional phone preview target)
  -> asynchronous complete H.264 access units
  -> OCB2 /stream.ocb2
  -> Media Foundation H.264 MFT + D3D11 manager
  -> decoded NV12, optional one-time D3D11 video-processor resize
     (bounded CPU NV12 letterbox fallback after GPU failure)
  -> validated NV12 ring, newest complete slot only
  -> SimpleMediaStream NV12 sample
  -> Windows camera consumer
```

MJPEG uses CameraX ImageAnalysis, performs the authoritative NV21 rotation/mirror on Android, emits Content-Length multipart JPEG frames, decodes into NV12 on Windows, and joins the same ring/consumer path. It is compatibility fallback, not removed.

## Android ownership

`StreamService` and its bounded `PipelineController` own lifecycle and command serialization. Lifecycle states are `STOPPED`, `STARTING`, `STREAMING`, `RECONFIGURING`, `RECOVERING`, `STOPPING`, and `FAILED`. Stop/recover take priority; compatible same-client settings are coalesced; different clients still pass revision conflict processing.

`PipelineSnapshot` is the authoritative immutable CAS state and contains revision, generation, lifecycle, desired, selected, actual, fallback, request identity/source, preview status, and update time. Generation-gated publications prevent status from mixing old actual values with new desired values.

`H264Streamer` selects only complete Camera2/encoder tuples. Regular surface capture is attempted where valid; constrained high-speed direct surface and SurfaceTexture/EGL bridge paths cover public high-speed configurations. Encoder output is asynchronous, B-frames are disabled, keyframe interval is one second, and a new client requests a keyframe. OCB2 stream info carries the source transform.

`MjpegStreamer` retains CameraX compatibility. Canonical MJPEG modes come from per-resolution camera FPS capability and internal H.264 fallback selects a valid tuple rather than retaining an impossible H.264 FPS.

`ControlServer` exposes `/stream.ocb2`, `/stream.mjpeg`, REST state/control, SSE, dashboard, and `/obs`. Dashboard and OBS use the shared incremental browser OCB2 parser plus WebCodecs, with explicit unsupported-browser errors.

## OCB2

Every 48-byte little-endian header includes magic, version, header size, record type, flags, sequence, capture timestamp, encoder timestamp, payload length, and reserved bytes. Records cover stream info, codec configuration, one complete access unit, heartbeat, end, and error. Payloads are bounded to 16 MiB independently by parsers and decoder entry points. Reconnect resets partial-record state and decoding waits for a new keyframe after stream info/discontinuity.

The shared corpus in `protocol/conformance` runs against Kotlin, Rust, and the actual browser parser.

## Windows producer and ring

The producer has observable states from `STARTING` through connection, stream-info/config/keyframe/decode, `WRITING_RING`, stalled/fallback/failed. Media Foundation D3D11 output is reported separately from hardware decode, which remains `unknown` unless acceleration can be proven.

The ring ABI is generated from `protocol/ring-abi.schema.json`. It has a 256-byte header and aligned slot headers/data, atomic publication, ACL-restricted file/mapping access, producer/consumer heartbeats, build hashes, dimensions/strides/format/size/offset validation, and two or three reusable NV12 slots. Producer writes and camera reads never queue old presentation frames.

## Virtual camera

`VirtualCameraMediaSource.dll` ships only the Synthetic/SimpleMediaSource production path. `SimpleMediaStream` advertises NV12 1080p60/30 and 720p60/30, with RGB32 compatibility last. Buffer writes use the `IMF2DBuffer2` -> `IMF2DBuffer` -> `IMFMediaBuffer` fallback chain and validate positive NV12 pitch/negative RGB32 pitch correctly.

`VirtualCamera_Installer.exe` owns production register/unregister/status/host commands. Inherited Microsoft manager/test/MSI/wrapper projects are archived under `upstream-samples` and are absent from the production build graph.

## Desktop orchestration

Tauri chooses an explicit ADB device, applies revisioned complete tuples, starts the producer with Android selected/actual source properties and independent Windows output dimensions, verifies three ring commits plus host activation, and maintains current virtual-camera-consumer readiness separately. The desktop preview reads the newest NV12 slot at at most 30 FPS and cannot back-pressure the ring.

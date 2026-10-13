# OpenCamBridge V2 architecture

Last updated on main for the October 2026 streaming audit fixes.

## End-to-end data path

```text
Camera2 regular / constrained high-speed / high-speed GPU bridge
  -> MediaCodec AVC encoder input Surface (optional phone preview target)
  -> asynchronous complete H.264 access units
  -> OCB2 /stream.ocb2
  -> Media Foundation H.264 MFT + D3D11 manager
  -> owned decoded NV12, CPU orientation transform
  -> validated 16-slot NV12 ring, bounded sequence playback
  -> consumer D3D11 resize/framing or compatible CPU fallback/comparison
  -> SimpleMediaStream NV12 sample
  -> Windows camera consumer
```

MJPEG uses CameraX ImageAnalysis, performs the authoritative NV21 rotation/mirror on Android, emits Content-Length multipart JPEG frames, decodes into NV12 on Windows, and joins the same ring/consumer path. It is compatibility fallback, not removed.

## Android ownership

`StreamService` and its bounded `PipelineController` own lifecycle and command serialization. Lifecycle states are `STOPPED`, `STARTING`, `STREAMING`, `RECONFIGURING`, `RECOVERING`, `STOPPING`, and `FAILED`. Stop/recover take priority; compatible same-client settings are coalesced; different clients still pass revision conflict processing.

`PipelineSnapshot` is the authoritative immutable CAS state and contains revision, generation, lifecycle, desired, selected, actual, fallback, request identity/source, preview status, and update time. Generation-gated publications prevent status from mixing old actual values with new desired values.

`H264Streamer` selects only complete Camera2/encoder tuples. Regular surface capture is attempted where valid; constrained high-speed direct surface and SurfaceTexture/EGL bridge paths cover public high-speed configurations. Encoder output is asynchronous, B-frames are disabled, the default keyframe interval is five seconds, and a new client requests a keyframe. OCB2 stream info carries the source transform and color metadata. One Auto/Manual bitrate policy handles configure and live updates.

`MjpegStreamer` retains CameraX compatibility. Canonical MJPEG modes come from per-resolution camera FPS capability and internal H.264 fallback selects a valid tuple rather than retaining an impossible H.264 FPS.

`ControlServer` exposes `/stream.ocb2`, `/stream.mjpeg`, REST state/control, SSE, dashboard, and `/obs`. Dashboard and OBS use the shared incremental browser OCB2 parser plus WebCodecs, with explicit unsupported-browser errors.

## OCB2

Every 48-byte little-endian header includes magic, version, header size, record type, flags, sequence, capture timestamp, encoder timestamp, payload length, and reserved bytes. Records cover stream info, codec configuration, one complete access unit, heartbeat, end, and error. Payloads are bounded to 16 MiB independently by parsers and decoder entry points. Reconnect resets partial-record state and decoding waits for a new keyframe after stream info/discontinuity.

The shared corpus in `protocol/conformance` runs against Kotlin, Rust, and the actual browser parser.

## Windows producer and ring

The producer has observable states from `STARTING` through connection, stream-info/config/keyframe/decode, `WRITING_RING`, stalled/fallback/failed. Media Foundation D3D11 output is reported separately from hardware decode, which remains `unknown` unless acceleration can be proven.

The ring ABI is generated from `protocol/ring-abi.schema.json`. Version 5 has a 384-byte header, explicit full-HD pixel/payload limits, sixteen reusable NV12 slots and aligned 128-byte slot headers, atomic publication, ACL-restricted file/mapping access, producer/consumer heartbeats, build hashes and geometry/bounds validation. The production camera uses a fixed-delay queue with startup fill, bounded drift correction and consumer-local rate conversion. Low/Balanced/Smooth use 55/90/140 ms presentation targets. The older adaptive scheduler remains available for historical simulations; it is not the production selection policy.

## Virtual camera

`VirtualCameraMediaSource.dll` ships only the Synthetic/SimpleMediaSource production path. `SimpleMediaStream` advertises NV12 1080p60/30 and 720p60/30, with RGB32 compatibility last. Buffer writes use the `IMF2DBuffer2` -> `IMF2DBuffer` -> `IMFMediaBuffer` fallback chain and validate positive NV12 pitch/negative RGB32 pitch correctly.

`VirtualCamera_Installer.exe` owns production register/unregister/status/host commands. Inherited Microsoft manager/test/MSI/wrapper projects are archived under `upstream-samples` and are absent from the production build graph.

## Desktop orchestration

Tauri chooses an explicit ADB device, applies revisioned complete tuples, starts the producer with Android selected/actual source properties and independent Windows output dimensions, verifies three ring commits plus host activation, and maintains current virtual-camera-consumer readiness separately.

The producer owns the only phone video connection. The primary desktop preview
opens its separately authenticated loopback OCB2 fanout through the native HTTP plugin.
Only compressed chunks cross IPC, with one chunk
in flight to the worker. The shared OCB2 parser feeds an Annex-B WebCodecs decoder,
preferring hardware with software allowed. Stream discontinuities reset decoding
and wait for a keyframe. OCB2 SDR color metadata and rotation/mirror are preserved.
Full-resolution VideoFrames are presented to an OffscreenCanvas on display rAF;
the compositor scales the source to the UI rectangle. The bounded decoded-frame
queue uses the shared latency profile and slowly corrects phone/host clock phase. Discarded/displayed frames
are closed, and transport/worker resources are canceled on Stop/unmount.

Unsupported or repeatedly failing WebCodecs uses the existing 960-edge NV12 ring
preview. H.264 preview auto-starts the native producer for either renderer. The lifecycle
controller stays mounted when clean feed hides the controls. Neither preview
path activates or stops the virtual-camera host on failure.

Diagnostics distinguish producer processing, an unsynchronized phone-to-ring
lower-bound estimate, WebCodecs submit-to-output callback time, and canvas draw
submission time. None is a sensor-to-display measurement. Reader timestamps are
taken when an OCB2 record is complete, before its bounded decoder queue.

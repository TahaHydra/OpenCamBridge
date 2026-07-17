I checked the actual pushed commit a68a2701484548a6378e2eb930cfae2e5c8a1354, its diff against 1ebbcf9, the relevant branch files, your runtime logs, and the Android/Microsoft APIs.

Verdict

a68a270 fixed several real low-level issues:

stale Qualcomm MediaCodec callbacks;
missing SPS/PPS configuration on Qualcomm IDR frames;
Media Foundation decoder ownership/crash handling;
false early adaptive downgrade;
dev-reset.ps1 killing its own PowerShell.

But it did not fix the complete application. The commit modified only seven files and did not modify:

Tauri ControlPanel.tsx;
Android shared state architecture;
embedded web dashboard state logic;
C++ virtual-camera DLL;
desktop preview;
virtual-camera registration/install path.

Therefore the blue virtual camera, zero virtual-camera FPS, UI desynchronization, fake MJPEG 60 option, black desktop preview, and 60-fps capture architecture were never solved by a68a270.

1. OnePlus 60 FPS is definitely not implemented

This is proven directly by the code.

H264Capabilities.kt accepts a mode only when that FPS appears in:

CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES

It rejects everything else.

H264Streamer.kt then explicitly creates:

SessionConfiguration.SESSION_REGULAR

and uses:

configured.setRepeatingRequest(...)

There is no:

SESSION_HIGH_SPEED
CameraConstrainedHighSpeedCaptureSession
createHighSpeedRequestList
setRepeatingBurst

So the worker’s statement:

“This OnePlus exposes 1080p30—not 1080p60—through its complete public Camera2/encoder path”

is too broad.

The accurate statement is:

“The current implementation tested only the regular Camera2 path, and that path exposes 1080p30.”

Android’s constrained high-speed API is a separate session type. It uses SESSION_HIGH_SPEED, must build requests using createHighSpeedRequestList(), and submits them through a repeating burst. Android documents that path for high-speed recording at 120 fps or more.

Correct 60-fps work

Implement three capture engines:

REGULAR_SURFACE
Camera2 regular session → MediaCodec surface

HIGH_SPEED_SURFACE
Camera2 constrained high-speed session → supported high-speed encoder surface

HIGH_SPEED_GPU_BRIDGE
Camera high-speed output → SurfaceTexture/EGL →
select frames → MediaCodec surface at 60 fps

Because constrained high-speed commonly begins at 120 fps, the generic OnePlus solution may require:

Camera2 high-speed at 120
→ GPU surface bridge
→ retain every second camera timestamp
→ encode 60 unique frames

No CPU YUV conversion should be introduced.

The app must test the exact high-speed surface combination at runtime. Android high-speed sessions have restricted output sizes, FPS ranges, and surface combinations.

2. The virtual camera still cannot advertise 60 FPS

The C++ virtual-camera stream contains this filter:

framerate <= 30 && framerate >= 15

That directly contradicts the claimed V2 formats:

1080p60
720p60

Even when the producer eventually delivers 60, this stream code filters out media types above 30.

Fix

Replace the inherited heuristic with an explicit OpenCamBridge media-type list:

NV12 1920×1080 @ 60/1
NV12 1920×1080 @ 30/1
NV12 1280×720 @ 60/1
NV12 1280×720 @ 30/1
RGB32 compatibility formats

Then test enumeration through:

ffmpeg -list_options true -f dshow -i video="OpenCamBridge Camera"

The test must prove that FFmpeg and OBS actually see all four NV12 modes.

3. Blue lines and Virtual-camera unique FPS: 0

This is the clearest critical failure.

Your H.264 data proves:

Android capture: ~30
Encoded: ~30
Transport: ~29
Decoded unique: ~29
Virtual-camera unique: 0

The Windows decoder works. The virtual-camera consumer does not deliver valid samples.

a68a270 did not change the C++ virtual-camera DLL at all, so it could not have fixed this issue.

The shared-memory reader increments virtualCameraUniqueFrames only after successfully validating and copying a ring slot.

Since the counter stays at zero, one of these is happening:

the virtual-camera DLL is not calling ReadFrame;
Windows loaded an older DLL;
no consumer is reaching the ring-reading stream;
ring validation rejects every frame;
the consumer requests a media type that never reaches this path;
sample creation/copy fails before counter publication.

The blue lines are likely uninitialized or malformed fallback samples, but the exact pixel corruption cause cannot be determined from the producer metrics alone.

Required diagnostic implementation

Add these shared counters:

consumerAttached
consumerPid
consumerHeartbeat
sampleRequests
ringReadAttempts
ringReadSuccesses
ringValidationFailures
sampleCopyFailures
lastRingError
lastAcceptedSequence
negotiatedSubtype
negotiatedWidth
negotiatedHeight
negotiatedFps
installedDllBuildHash
producerBuildHash
ringAbiHash

Add deterministic producer modes:

--test-pattern nv12-bars
--test-pattern nv12-gradient
--test-pattern solid-red
--test-pattern solid-green
--test-pattern solid-blue

Test the virtual camera without Android or H.264.

Interpretation:

Test pattern broken:
C++ virtual camera / registration / ring reader problem.

Test pattern correct, decoded camera broken:
producer NV12 decode/ring-write problem.
Verify the installed DLL

The build script must print:

Built DLL SHA-256
Installed DLL SHA-256
Loaded DLL path
Source commit
ABI version

It must fail if the installed and built hashes differ.

4. The virtual-camera NV12 copy must use real Media Foundation pitch

The ring copy itself validates dimensions and copies NV12 plane by plane. That part is structurally sensible.

However, the complete sample path must obtain the real destination buffer bounds and pitch correctly.

Microsoft recommends locking video buffers in this order:

IMF2DBuffer2::Lock2DSize
IMF2DBuffer::Lock2D
IMFMediaBuffer::Lock

It specifically says to use the returned scanline pointer, buffer bounds, and actual pitch.

The virtual-camera implementation must not assume:

pitch == width
UV begins at width × height
ordinary memory buffer always behaves like a 2D NV12 surface

The C++ sampling path needs explicit validation around the actual destination sample, not just the shared-memory source.

5. Stop/restart can still race and crash

H264Streamer.start() calls stopInternal() first and uses its own mutex, which helps.

But stopInternal():

calls stopRepeating() and abortCaptures();
closes the session;
closes the camera;
immediately stops/releases the codec;
immediately calls quitSafely() on the handler threads.

It does not await:

CameraCaptureSession.onClosed
CameraDevice.onClosed
codec callback queue drain
handler thread termination

The service compounds this because startCamera(), stopCamera(), and rebindCamera() each launch asynchronous coroutines and return immediately.

An HTTP caller can therefore receive success while the previous camera/codec is still shutting down.

The adaptive monitor can also independently stop and restart H.264 after four low-FPS windows.

Fix

Use one serialized PipelineController actor. Every caller submits commands to the same queue:

sealed interface PipelineCommand {
    data object Start : PipelineCommand
    data object Stop : PipelineCommand
    data class Apply(val request: SettingsRequest) : PipelineCommand
    data class Recover(val reason: String) : PipelineCommand
    data class Adapt(val mode: Mode) : PipelineCommand
}

Only that controller may open, stop, or reconfigure the camera and codec.

Each command must return a completion result only after the transition is finished.

Required state machine:

STOPPED
STARTING
STREAMING
STOPPING
RECONFIGURING
RECOVERING
FAILED

Add a monotonically increasing pipeline generation. Camera, session, codec, network, and metrics callbacks must all verify that generation.

Do not rely on a fixed 150 ms or 200 ms delay as proof that hardware was released.

6. Web, Android and Tauri are not using one authoritative state

The Android state object is a large collection of independent atomic variables.

It has a revision counter, but settings updates are not true compare-and-swap transactions.

appliedSettingsVersion only tracks a Tauri-local applyId.

The Tauri client sends its entire local settings object:

profile
width
height
outputWidth
outputHeight
fps
quality
camera
rotation
mirror
codec
bitrate
preview

A browser client can independently send another complete settings object. Whichever request arrives last wins.

There is no shared conflict control such as:

baseRevision
expectedRevision
409 Conflict
This directly explains the mismatch

Tauri explicitly contains this behavior:

If Android streams a different resolution than requested, continue and let the producer resize.

Therefore:

Web requests 1920×1080
Android applies 1280×720
Tauri accepts it
Producer resizes to 1920×1080
UI displays “heavy resize”

That is not mysterious. The desktop intentionally accepts the mismatch.

Fix

Replace all partial atomic mutation with one immutable configuration snapshot:

data class PipelineSnapshot(
    val revision: Long,
    val lifecycle: LifecycleState,
    val desired: DesiredConfig,
    val selected: SelectedConfig?,
    val actual: RuntimeConfig?,
    val lastRequestId: String?,
    val lastUpdatedBy: String
)

Every mutation sends:

{
  "requestId": "uuid",
  "baseRevision": 152,
  "changes": {
    "codec": "h264",
    "resolution": "1920x1080",
    "fps": 30
  }
}

Server behavior:

baseRevision current:
normalize, apply, increment revision, return snapshot

baseRevision stale:
HTTP 409 with newest snapshot

Clients must:

load the snapshot before enabling controls;
never post their defaults during initial render;
render desired, selected, and actual separately;
subscribe to server-pushed updates using WebSocket or SSE;
stop merging independent status fields locally.
7. Fake MJPEG 60 is a server-side validation failure

The shared state defaults to:

fps = 60

applySettingsPatch() accepts any FPS from 1 to 120:

req.fps?.let {
    StreamState.fps.set(it.coerceIn(1, 120))
}

That is not capability validation.

The H.264 path has separate capability filtering, but MJPEG does not prevent the UI/server from accepting an impossible 60-fps request.

Your snapshot proves exactly this state:

requested fps: 60
maxFps@res: 30
actual: 30

Fix

Generate capabilities per complete path:

camera
× capture engine
× codec
× resolution
× fps
× preview combination

The server must reject invalid requests:

422 Unprocessable Entity

Response:

{
  "error": "Unsupported mode",
  "requested": "MJPEG 1920x1080@60",
  "alternatives": [
    "MJPEG 1920x1080@30",
    "MJPEG 1280x720@30"
  ]
}

All three interfaces must populate their selectors from the exact same capability endpoint. They must not construct their own FPS lists.

8. The black desktop preview is intentional, not working

The screenshot says:

“Hardware H.264 is feeding the Windows virtual camera. Enable preview on the phone…”

That is a placeholder. The desktop H.264 preview is not implemented.

a68a270 did not modify the preview component.

Correct solution

Render the desktop preview from the existing decoded NV12 ring:

decoded NV12 ring
→ D3D11 texture
→ video processor
→ native Tauri preview surface

Do not require phone preview.

Split the controls:

phonePreviewEnabled
desktopPreviewEnabled
virtualCameraEnabled
streamEnabled

Phone screen off must not affect desktop preview or streaming.

9. Screen-off behavior was not fully implemented by this commit

The Android change simply calls:

requestPermissionsAndStart()

when MainActivity is created.

That auto-starts the service, but it is not a complete screen-off design.

The commit does not establish from the diff:

a partial wake lock;
explicit screen-off lifecycle tests;
separation between Activity preview surface and encoder lifetime;
OEM battery-management handling;
30-minute locked-screen soak tests.

The service is already a camera foreground service, which is the right foundation.

Required behavior:

Activity destroyed:
encoder continues

phone preview surface removed:
only preview removed

screen locked:
camera + encoder continue

stream stopped:
wake lock released
10. H.264 diagnostics are carrying stale MJPEG metrics

StreamState stores these global values:

yuvMsAvg
jpegMsAvg
rotateMsAvg
androidEncodeMsAvg

The H.264 path does not reset all MJPEG-specific fields when it starts.

That is why H.264 logs display:

yuv=0.6
rot=11.1
jpeg=14.1

even though Camera2 writes directly to the MediaCodec input surface.

Fix

Use path-specific nullable metrics:

{
  "capture": {
    "engine": "camera2-surface",
    "fps": 30
  },
  "h264": {
    "encoderMs": 3.2
  },
  "mjpeg": null,
  "transport": {},
  "decoder": {},
  "ring": {},
  "virtualCamera": {}
}

Reset every metrics object when the pipeline generation changes.

Never print a field that does not apply to the active engine.

11. Framebuffer SDDL and decoder identity are falsely shown as errors

The Tauri stderr filter treats only these as benign:

Framebuffer backend
NOTE
Resize backend preference
JPEG decode preference

It does not whitelist:

Framebuffer SDDL:
Decoder: Microsoft H.264 Video Decoder...

Therefore both become last_error.

This directly explains your diagnostics.

Fix

Do not parse human stderr strings to determine health.

Producer stdout must emit structured events:

{"type":"info","code":"FRAMEBUFFER_ACL","message":"..."}
{"type":"info","code":"DECODER_SELECTED","message":"..."}
{"type":"error","code":"DECODER_PROCESS_INPUT_FAILED","message":"..."}

Tauri should set last_error only for events where:

"severity": "error"
12. “Producer running” does not mean frames are flowing

The desktop marks the producer as running when the child process exists.

finalProducerRunning=true therefore only means:

process handle exists

It does not mean:

OCB2 connected
codec config received
keyframe decoded
ring frame committed
virtual camera consumed frame

Your logs show that contradiction directly:

finalProducerRunning=true
then prodIn=0 / prodOut=0

Fix

Expose a producer state machine:

STARTING
CONNECTING
WAITING_FOR_STREAM_INFO
WAITING_FOR_CODEC_CONFIG
WAITING_FOR_KEYFRAME
DECODING
WRITING_RING
STALLED
FALLBACK
FAILED

Start Webcam succeeds only after:

producer alive
+ stream connected
+ first keyframe decoded
+ three ring frames committed

Virtual-camera readiness is a separate condition:

consumer attached
+ first frame delivered

---

## Implementation status (v2/streaming-engine)

This table is the live branch checklist. A checked **code** box means the code-side finding is implemented and covered by the listed automated evidence. It does not imply that a physical-device, OBS, FFmpeg, reconnect, or screen-off test passed. Those results are recorded separately and remain `NOT YET TESTED` until actually performed.

| # | Code status | Files / implementation commit | Automated evidence | Live-test status |
|---|---|---|---|---|
| 1 | [x] All three public Camera2 engines are implemented and independently reported: regular encoder surface, constrained high-speed encoder surface, and constrained high-speed SurfaceTexture/EGL bridge with timestamped frame selection (for example 120→60). Candidate failure diagnostics retain the exact camera/session exception. | `H264Capabilities.kt`, `H264Streamer.kt`, `HighSpeedGpuBridge.kt`, `CapturePathPolicy.kt`; `242c8d6` | Android policy tests **6/6** (direct 60, 120→60 bridge, 30 never promoted, preference order, MJPEG capability gates); debug/release APK and release lint passed | **NOT YET TESTED** on OnePlus 9 or another physical high-speed device (ADB device list was empty) |
| 2 | [x] The virtual camera explicitly advertises NV12 1080p60/30 and 720p60/30, with RGB32 last as compatibility only; the normal sample path remains NV12. Native-size copy is preferred, D3D11 video processing is the resize path, and a bounded bilinear NV12 CPU letterbox is compatibility fallback only. | `SimpleMediaStream.cpp`, `SimpleMediaSource.cpp`, `SharedMemoryClient.cpp`, `Nv12ResizeFallback.h`; `1ebbcf9`, `c7515e8`, `a70a548` | Media Foundation DLL/host builds passed at committed code HEAD; native up/down/portrait/device-loss tests passed; zero C++ warnings/errors | **NOT YET TESTED** in FFmpeg or OBS (executables unavailable in this environment) |
| 3 | [x] Ring ABI v3 diagnostics, consumer/sample/copy/validation counters, build hashes, strict slot validation, and deterministic direct-NV12 patterns are implemented. | `main.rs`, `SharedMemoryClient.*`, `SimpleMediaStream.cpp`, `ControlPanel.tsx`, `dev-build-vcam.ps1`; `dac442c`, `e69dcd2`, `caf9bd3` | Producer **16/16** tests; deterministic colour/plane and bounds tests; final built/installed/loaded DLL SHA-256 all matched `78362FAB...AF178D4` | **NOT YET TESTED** for visible OBS output |
| 4 | [x] One buffer-lock abstraction tries `IMF2DBuffer2::Lock2DSize`, `IMF2DBuffer::Lock2D`, then `IMFMediaBuffer::Lock`, including fallthrough when QI succeeds but the corresponding lock call fails. It releases failed interfaces, retains scanline/buffer start/length/pitch/unlock state, supports negative RGB32 pitch, rejects negative NV12 pitch, validates bounds, and calls `SetCurrentLength` for contiguous writes. | `BufferLockFallback.h`, `SharedMemoryClient.cpp`, `SimpleFrameGenerator.cpp`, `SimpleMediaStream.cpp`; `a70a548` | Fault-injection policy self-tests and DLL/host release build passed with zero warnings/errors; producer ring bounds/invalid-metadata tests passed | **NOT YET TESTED** with an external physical camera consumer |
| 5 | [x] One bounded, coalescing `PipelineController` owns start/stop/reconfigure/recover/adapt/fallback/preview/control commands. Camera/session close callbacks and handler-thread termination are awaited, callbacks are generation-gated, and phone controls await authoritative results. Required lifecycle states are exposed. | `PipelineController.kt`, `ServiceBridge.kt`, `StreamService.kt`, `H264Streamer.kt`, `StreamState.kt`; `242c8d6`, `1921826`, `d2776fd` | Android tests **9/9**; debug APK build passed | **NOT YET TESTED** for repeated physical start/stop/reconnect stress |
| 6 | [x] Desired, selected, actual, fallback, revision, generation, lifecycle, request identity, and update metadata live in one immutable CAS snapshot. Selected/actual/fallback publications are generation-gated. All mutation sources use `baseRevision`/`requestId`/source; stale updates return 409 plus authoritative state; clients restore that state immediately. | `StreamState.kt`, `StreamService.kt`, `ControlServer.kt`, `StreamViewModel.kt`, `ControlPanel.tsx`; `242c8d6`, `1921826`, `d2776fd`, `e69dcd2` | Android **9/9**, Android APK, Tauri **1/1**, Rust/TypeScript release builds passed | **NOT YET TESTED** with simultaneous physical phone/web/desktop edits |
| 7 | [x] All UIs consume canonical per-camera complete-path tuples. H.264 uses only `h264Modes`; MJPEG uses only server-generated `mjpegModes` derived from `fpsByResolution`. Invalid explicit requests return HTTP 422 with requested mode, alternatives, and authoritative state. | `H264Capabilities.kt`, `CameraInfoDto.kt`, `CameraRepository.kt`, `CapturePathPolicy.kt`, `MainActivity.kt`, `ControlServer.kt`, `StreamService.kt`, `ControlPanel.tsx`; `242c8d6`, `1921826`, `d2776fd`, `e69dcd2` | Android policy tests prove a 30-FPS MJPEG path cannot advertise 60; Android **9/9**, frontend production build passed | **NOT YET TESTED** against live OnePlus capability/session results |
| 8 | [x] Desktop H.264 preview reads the newest validated decoded NV12 ring slot and uploads Y/UV directly to a WebGL2/ANGLE D3D11 GPU shader. It prefers the producer/DLL file-backed ring, validates build hash and heartbeat, and remaps on stall or producer PID/generation change. | `nv12_preview.rs`, `Nv12RingPreview.tsx`, `Preview.tsx`, `ControlPanel.tsx`; `e681985`, `c7515e8`, `e69dcd2` | Tauri ring ABI test **1/1**, Rust release build and frontend production build passed | **NOT YET TESTED** for live decoded-phone preview visibility and producer restart remapping |
| 9 | [x] The foreground camera service owns a partial wake lock only while a stream/recovery is active, releases it on stop/error, survives Activity preview removal, reports battery-optimization exemption state, and logs an OEM power warning. | `StreamService.kt`, `ControlServer.kt`, `StreamViewModel.kt`, manifest; `242c8d6`, `c7515e8` | Android debug/release builds and release lint passed | **NOT YET TESTED** (required 30-minute locked-screen physical soak) |
| 10 | [x] Metrics are generation-reset and path-scoped: capture is common; H.264 and MJPEG objects are nullable/exclusive; capture, encoded, transport, decoded unique, virtual-camera unique, repeats, latency, bitrate, fallback, and rejected paths remain separate. MJPEG capture FPS is measured before encode pacing. | `StreamState.kt`, `ControlServer.kt`, `MjpegStreamer.kt`, `main.rs`, `virtualcam.rs`, `ControlPanel.tsx`; `242c8d6`, `c7515e8` | Producer unique/repeat and 30/60 pacing tests passed; Android/debug/release and frontend builds passed | **NOT YET TESTED** against live sustained FPS/latency |
| 11 | [x] Human stderr is not health. Producer stdout emits structured events and desktop `last_error` changes only for `severity=error`. | `main.rs`, `mf_decoder.rs`, `virtualcam.rs`; `dac442c` | Tauri `cargo test` **1/1**, `cargo check`, and full production build passed | **NOT YET TESTED** with a live decoder/device-loss event |
| 12 | [x] Producer readiness has explicit connection/config/keyframe/decode/ring states. Start rejects unless Android is `STREAMING`, the producer is alive and `WRITING_RING` with at least three commits, the host child remains alive after its activation handshake, and the backend is registered/activated. OBS attachment remains a separate current-activity state requiring recent consumer heartbeat, sample requests, successful ring reads, and accepted-sequence progress with PID/generation baseline resets. | `main.rs`, `virtualcam.rs`, `ControlPanel.tsx`, `VirtualCamera_Installer/main.cpp`; `dac442c`, `e69dcd2`, `a70a548` | Producer **16/16**, including stale-readiness/PID-reset tests; Tauri **2/2**, including complete readiness prerequisites; Rust/frontend/native release builds passed | **NOT YET TESTED** for live connect/consume/reconnect or abnormal OBS exit |

### Follow-up repair pass (2026-07-17)

These entries correspond to the 12 follow-up defects and three resource/diagnostic requirements supplied after the original audit. A checked box means the code-side repair and available automated verification are complete; it does not fabricate physical-device or external-consumer results.

| # | Code status | Files / implementation commit | Automated evidence | Live-test status |
|---|---|---|---|---|
| 13 | [x] H.264 rotation/mirror authority is carried in OCB2 stream info as `effectiveRotation`, `mirror`, `sensorOrientation`, and `deviceRotation`. Android computes it from sensor/device/manual state; a transform change performs a generation/discontinuity restart; both Windows decode paths apply the OCB2 values directly to NV12. MJPEG rotation and mirror are applied once to NV21 on Android before JPEG encode, with all downstream MJPEG transforms removed. | `FrameTransform.kt`, `H264Streamer.kt`, `Nv21Transform.kt`, `MjpegStreamer.kt`, `ocb2.rs`, `main.rs`, `ControlPanel.tsx`; `d2776fd`, `e69dcd2`, `8bf9492`, `a70a548` | Android transform tests **11/11** (three H.264 policy plus eight NV21 rotation/mirror combinations); producer NV12 rotate/mirror and OCB2 transform-validation tests passed | **NOT YET TESTED** for visible 90/180/270 and front/back mirror output on a phone/OBS |
| 14 | [x] Producer launch failures propagate. Start polls a bounded readiness deadline and rejects when Android is not streaming, the producer child is absent/not `WRITING_RING`/below three commits, the host exits or never emits its post-activation handshake, registration is absent, or readiness times out. A failed start stops the feeder, preserves the visible error, and leaves the action retryable. | `ControlPanel.tsx`, `virtualcam.rs`, `VirtualCamera_Installer/main.cpp`; `e69dcd2`, `a70a548` | TypeScript/Vite production build and Tauri Rust **2/2** test/release build passed | **NOT YET TESTED** with an intentionally broken live producer executable/stream |
| 15 | [x] The immutable CAS `PipelineSnapshot` contains desired, selected, actual, fallback, lifecycle, revision, generation, request identity, and source. Runtime publications carry/check generation, and status DTOs are produced from one snapshot without mixing generations. Legacy atomics are compatibility projections only. | `StreamState.kt`, `H264Streamer.kt`, `MjpegStreamer.kt`, `ControlServer.kt`; `d2776fd` | Android **9/9** and debug APK build passed | **NOT YET TESTED** during rapid physical reconfiguration while streaming |
| 16 | [x] Phone Compose, embedded web, Tauri, producer fallback, legacy settings routes, watchdog/adaptation, torch, and zoom all provide request ID, base revision, and source identity or publish a serialized internal runtime revision. Torch/zoom execute through `PipelineController`. | `StreamViewModel.kt`, `ServiceBridge.kt`, `StreamService.kt`, `ControlServer.kt`, `main.rs`, `ControlPanel.tsx`; `d2776fd`, `e69dcd2` | Android/frontend/Rust builds passed; server/client schemas compile | **NOT YET TESTED** with concurrent phone/web/Tauri mutation races |
| 17 | [x] Tauri no longer uses the parallel `appliedVersion` model or increments an accepted counter optimistically. A 409/422 immediately imports `authoritativeState`, restores selectors/revision, and shows the rejected request and alternatives. | `ControlPanel.tsx`, `StreamState.kt`; `d2776fd`, `e69dcd2` | Frontend production build and Android compile passed; repository search confirms the obsolete fields are absent | **NOT YET TESTED** against live repeated 409/422 responses |
| 18 | [x] Phone, embedded web, and Tauri selectors consume only canonical complete modes from the selected camera: `mjpegModes` derived server-side from `fpsByResolution`, or `h264Modes`. Codec/lens/resolution changes rebuild and normalize the complete tuple; UI code does not synthesize 60-FPS choices. | `CameraInfoDto.kt`, `CameraRepository.kt`, `MainActivity.kt`, `ControlServer.kt`, `ControlPanel.tsx`; `d2776fd`, `e69dcd2` | MJPEG capability-policy tests passed; Android **9/9** and frontend production build passed | **NOT YET TESTED** with live OnePlus capability enumeration |
| 19 | [x] Embedded dashboard and `/obs` have an H.264 preview: an incremental bounded OCB2 parser handles fragmented/multiple records, configures WebCodecs from codec configuration, decodes complete access units, renders transformed frames to canvas, and displays an explicit support/decoder error instead of black output. A separate MJPEG preview remains. | `ControlServer.kt`; `d2776fd` | All three embedded JavaScript blocks compile; Android build passed; producer OCB2 fragmentation/config/keyframe tests passed | **NOT YET TESTED** in a real Edge/Chrome WebCodecs session or OBS browser source |
| 20 | [x] Desktop preview now follows producer/DLL backend order (file-backed ring first), validates ABI/build hash/producer heartbeat, caps stale retention, remaps after about two seconds without sequence/heartbeat progress, and resets on producer PID/instance changes. | `nv12_preview.rs`, `virtualcam.rs`; `e69dcd2` | Tauri ring ABI **1/1**, Rust test/release build passed | **NOT YET TESTED** across a live producer crash/restart and fallback-mapping transition |
| 21 | [x] Virtual-camera readiness uses current activity, not lifetime counters: attachment plus recent heartbeat, sample-request delta, ring-read-success delta, and accepted-sequence delta. Baselines reset for consumer PID, ring connection, and stream-info generation changes. | `main.rs`, `virtualcam.rs`; `e69dcd2` | Producer tests cover stale readiness and consumer PID reset; producer **16/16** | **NOT YET TESTED** after an actual OBS crash/abnormal exit |
| 22 | [x] Media sample writing uses an RAII lock abstraction with true failure fallthrough through `IMF2DBuffer2::Lock2DSize` → `IMF2DBuffer::Lock2D` → `IMFMediaBuffer::Lock`, clearing failed COM state before each attempt. NV12/RGB32 bounds, negative-pitch policy, unlock behavior, and contiguous `SetCurrentLength` are explicit. | `BufferLockFallback.h`, `SimpleMediaStream.cpp`, `SimpleFrameGenerator.cpp`, `SharedMemoryClient.cpp`; `a70a548` | Native lock fault-injection self-test passed; Media Foundation DLL/host release rebuild passed with zero warnings/errors; installed/loaded hash matched | **NOT YET TESTED** across multiple external consumers/sample allocator implementations |
| 23 | [x] Decoder diagnostics distinguish identity and output mode from acceleration: `decoder=Microsoft H.264 Video Decoder MFT`, `D3D11 output=active/inactive`, and `hardware decode=unknown` for the MF path. Device-manager acceptance no longer claims hardware acceleration. | `mf_decoder.rs`, `main.rs`, `virtualcam.rs`, `ControlPanel.tsx`; `e69dcd2` | Producer/Tauri Rust test and release builds plus frontend production build passed | **NOT YET TESTED** with vendor DXVA diagnostics or decoder device loss |
| 24 | [x] `PipelineController` uses a bounded/coalescing pending queue rather than one suspended sender coroutine per action. Apply-settings coalescing additionally requires identical source, client identity, base revision, and request category; cross-client requests remain separate for ordinary conflict processing. Preview/control changes collapse safely, stop/recover/failure receive priority, and superseded/closed commands complete explicitly as `CANCELLED`. | `PipelineController.kt`, `StreamService.kt`, `ControlServer.kt`; `d2776fd`, `8bf9492` | Android **27/27**, including five coalescing identity/category tests; debug/release APK builds passed | **NOT YET TESTED** with rapid physical slider/reconfigure/recover stress |
| 25 | [x] Desktop preview is capped at 30 FPS, reads only the newest complete ring slot, and cannot back-pressure the producer or virtual camera; intermediate preview frames are replaced. The remaining Tauri IPC NV12 copy is documented as a later native shared-texture optimization, not hidden as zero-copy. | `Nv12RingPreview.tsx`, `nv12_preview.rs`; `e69dcd2` | Frontend production build and Tauri release build passed | **NOT YET TESTED** for sustained 1080p resource usage |
| 26 | [x] Windows output width/height changes no longer count as Android capture changes. Phone preview target changes still perform the necessary Camera2 session rebind; Windows-only canvas changes stay in the producer/output path. | `StreamService.kt`, `ControlPanel.tsx`; `d2776fd`, `e69dcd2` | Android/frontend production builds passed | **NOT YET TESTED** while switching virtual-camera formats live |
| 27 | [x] Adaptive capture mismatch is explicit: desktop diagnostics render Requested, Selected, Actual, and fallback/reason separately. An unexplained size mismatch rejects start instead of silently presenting resized 720p capture as native 1080p. | `StreamState.kt`, `ControlPanel.tsx`; `d2776fd`, `e69dcd2` | Android/frontend builds passed | **NOT YET TESTED** through a live 1080p→720p adaptive fallback |

### Exact-HEAD blocker closure (2026-07-17)

These entries cover the eight code-side blockers reported against `29da22e2b497f4db023bffb6f987529cbaa5c027`. Checked status means the implementation and available automated evidence are complete. It does not mark any phone, OBS, FFmpeg, reconnect, or screen-off acceptance test as passed.

| # | Code status | Files / implementation commit | Automated evidence | Live-test status |
|---|---|---|---|---|
| 28 | [x] Explicit H.264 profiles try only their exact requested tuple. Only profile `adaptive` has fallback candidates, and candidates are the requested tuple followed by the exact downgrade-only subsequence of 1080p60 → 720p60 → 1080p30 → 720p30. A 720p or 30-FPS request cannot upgrade. | `CapturePathPolicy.kt`, `H264Capabilities.kt`, `H264Streamer.kt`; `8bf9492` | Policy tests cover 720p60/720p30 no-upgrade, explicit quality/native exactness, 30→60 prevention, and exact adaptive order | **NOT YET TESTED** against physical Camera2 session acceptance/rejection |
| 29 | [x] Internal H.264 failure queries canonical `camera.mjpegModes`, selects only a same-or-lower supported MJPEG tuple, retains desired H.264 state, publishes the selected/actual MJPEG tuple and exact H.264 failure, and fails if no tuple exists. | `CapturePathPolicy.kt`, `ResolutionPolicy.kt`, `MjpegStreamer.kt`, `StreamService.kt`; `8bf9492` | Unit test proves failed H.264 1080p60 selects canonical MJPEG 1080p30 and never reports 60 as actual | **NOT YET TESTED** with a live encoder/session failure |
| 30 | [x] MJPEG transforms are source-authoritative: Android rotates and mirrors NV21 before JPEG encoding; producer, embedded web/OBS, and desktop preview do not rotate or mirror MJPEG again. A settings mutation from any client goes through the same revisioned pipeline restart. | `Nv21Transform.kt`, `MjpegStreamer.kt`, `ControlServer.kt`, `Preview.tsx`, `ControlPanel.tsx`, `virtualcam.rs`, `main.rs`; `8bf9492`, `a70a548` | Eight NV21 tests cover 0/90/180/270 × mirror off/on; Android/Rust/frontend builds passed | **NOT YET TESTED** for pixel identity across phone/web/Tauri/OBS after a live mirror change |
| 31 | [x] Authoritative state exposes `phonePreviewRequested`, `phonePreviewActive`, and `phonePreviewFailureReason`. Camera2 preview uses an exact-size TextureView/SurfaceTexture, applies requested rotation/mirror and aspect scaling, and reports encoder+preview rejection, encoder-only recovery, missing targets, EGL rejection, and EGL detach without claiming active. | `StreamState.kt`, `StreamViewModel.kt`, `MainActivity.kt`, `H264Streamer.kt`, `HighSpeedGpuBridge.kt`, `MjpegStreamer.kt`, `ControlServer.kt`, `ControlPanel.tsx`; `8bf9492`, `a70a548` | Android unit suite and debug/release builds plus frontend production build passed | **NOT YET TESTED** on regular/constrained/GPU physical preview sessions |
| 32 | [x] Start Webcam requires Android `STREAMING`, a live producer in `WRITING_RING`, three committed frames, a live host child after a post-activation handshake, and registered/activated Media Foundation backend state. It polls a bounded deadline, rejects host exit, and keeps OBS attachment separate. | `ControlPanel.tsx`, `virtualcam.rs`, `VirtualCamera_Installer/main.cpp`; `a70a548` | Tauri readiness prerequisite test and release build passed; native host build passed | **NOT YET TESTED** with live host crash, registration removal, or OBS attachment |
| 33 | [x] Media Foundation buffer locking performs real lock-failure fallthrough, releases failed interfaces, supports negative RGB32 pitch, explicitly rejects negative NV12 pitch, validates every row/buffer bound, and sets current length after contiguous writes. | `BufferLockFallback.h`, `SimpleMediaStream.cpp`, `SimpleFrameGenerator.cpp`, `SharedMemoryClient.cpp`; `a70a548` | `OCB_BUFFER_LOCK_FALLBACK_TEST=PASSED`; fault cases cover first and second QI-success/lock-failure paths; DLL/host build has zero warnings/errors | **NOT YET TESTED** across external allocator implementations |
| 34 | [x] Virtual-camera resize uses the actual ring source FPS and negotiated consumer FPS, native-match copy when possible, D3D11 video processing as primary, resource recreation/retry after device loss, and bilinear CPU NV12 letterbox fallback. Ring diagnostics report `native-match`, `gpu`, or `cpu-fallback` plus GPU failures. | `Nv12ResizeFallback.h`, `SharedMemoryClient.*`, `main.rs`, `nv12_preview.rs`, `virtualcam.rs`, `ControlPanel.tsx`, `dev-build-vcam.ps1`; `a70a548` | `OCB_NV12_RESIZE_FALLBACK_TEST=PASSED`; covers 720→1080, 1080→720, landscape→portrait canvas, and simulated D3D device removal/fallback | **NOT YET TESTED** for live D3D device loss or visible resize in OBS/FFmpeg |
| 35 | [x] Pending ApplySettings patches coalesce only for the same source, same client identity, same non-null base revision, and identical request-category mask. Web, phone, and Tauri requests never cross-merge and retain separate request IDs for normal revision/conflict handling. | `PipelineController.kt`, `ApplyPatchCoalescingPolicyTest.kt`; `8bf9492` | Five policy tests cover allowed same-client merging and rejected cross-client, shared-transport/different-client, different-revision, and incompatible-category merging | **NOT YET TESTED** with concurrent physical phone/web/Tauri input |

### Pre-physical static-review closure (2026-07-17)

These findings were reported against `ddb2bfcf71e8d127c9b379a931c1098a049c1ccf`. A checked box records code-side implementation plus the available automated evidence only. No physical result is promoted by this section.

| # | Code status | Files / implementation commit | Automated evidence | Live-test status |
|---|---|---|---|---|
| 36 | [x] Producer-triggered H.264 → MJPEG fallback queries authoritative status and canonical per-camera capabilities, selects a complete same-or-lower tuple, posts mode/width/height/FPS atomically, retries 409 with a fresh revision/request ID, parses and intersects 422 alternatives with canonical modes, and adopts the accepted authoritative selected tuple. | `rust-frame-producer/src/main.rs`; `b4b017e` | Producer **18/18**; pure policy proves H.264 1080p60 selects MJPEG 1080p30 and 422 alternative strings become complete tuples | **NOT YET TESTED** with a live decoder-triggered fallback |
| 37 | [x] Tauri refuses to launch without Android selected/actual source properties, passes actual encoded width/height/FPS separately from the Windows output canvas, and initializes producer/ring source timing from that tuple. Diagnostics label desired, selected, actual/encoded, active mode, and negotiated consumer output independently. | `ControlPanel.tsx`, `virtualcam.rs`, producer CLI source fields; `9e6fa2e`, `b4b017e` | Frontend production build, Tauri **2/2**, and both optimized Rust release builds passed | **NOT YET TESTED** with a live 1080p60 → MJPEG 1080p30 fallback |
| 38 | [x] Runtime adaptive H.264 downgrade resumes at the selected point in the original exact ladder, so rejected 720p60 continues to 1080p30 then 720p30 before MJPEG; explicit/non-adaptive behavior remains exact. | `CapturePathPolicy.kt`, `H264Capabilities.kt`, `H264Streamer.kt`; `9b88c4b` | Android **29/29**; new rejected-intermediate test asserts the exact suffix 720p60 → 1080p30 → 720p30 | **NOT YET TESTED** against physical intermediate-session rejection |
| 39 | [x] MJPEG actual dimensions are published from the post-transform JPEG dimensions, including 1920x1080 remaining landscape at 0/180 and becoming 1080x1920 at 90/270. | `Nv21Transform.kt`, `MjpegStreamer.kt`, `Nv21TransformTest.kt`; `ba2bd6e` | Android **29/29**; explicit full-HD dimension assertions cover all four rotations in addition to the eight pixel/mirror tests | **NOT YET TESTED** with live portrait MJPEG output |
| 40 | [x] High-speed diagnostics use the measured Camera2 capture callback rate for `cameraSessionFps`, reset it per generation, report GPU bridge output separately, and retain encoded/output FPS separately; a 120→60 bridge therefore reports session 120 and bridge/encode 60 rather than selected 60 as camera-session FPS. | `H264Streamer.kt`, `MjpegStreamer.kt`, `StreamService.kt`, `ControlServer.kt`; `9b88c4b`, `ba2bd6e` | Android **29/29**, including the existing 120→60 bridge policy test; debug/release builds passed | **NOT YET TESTED** on a constrained high-speed physical session |
| 41 | [x] `/api/stream/info` is built from one authoritative snapshot, exposes desired, selected, and actual tuples, and derives top-level active mode/codec/container/resolution/FPS from selected/actual state so MJPEG fallback is not mislabeled as desired H.264. | `ControlServer.kt`; `ba2bd6e` | Android unit suite and debug/release compilation passed | **NOT YET TESTED** against a live fallback response |
| 42 | [x] The unsupported autofocus switch is removed from the embedded web UI; it explicitly states continuous autofocus is automatic, control status is truthful, and the retained compatibility endpoint rejects manual switching with HTTP 422 and a clear reason. | `ControlServer.kt`; `ba2bd6e` | Embedded JavaScript/Android compilation and frontend build passed | **NOT YET TESTED** for visible focus behavior on CameraX/Camera2 |

### Claude post-audit reproduction baseline (starting HEAD `3f5ac86163a902d7c89e2adeebd332183196c0ed`)

The attached source review is preserved as `OpenCamBridge-audit-v2-streaming-engine.md`. Before implementation changes, local `HEAD`, `origin/v2/streaming-engine`, and the requested starting SHA were all verified equal to `3f5ac86163a902d7c89e2adeebd332183196c0ed`; the tracked worktree was clean. The evidence below records line numbers at that starting commit so later edits cannot obscure what was originally reproduced.

| ID | Disposition before repair | Exact starting-HEAD evidence | Required handling |
|---|---|---|---|
| C01 | **ALREADY FIXED** | Contrary to the audit text, `dev-build-vcam.ps1:158` already invokes `VirtualCamera_Installer.exe --self-test-pipeline`; `VirtualCamera_Installer/main.cpp:378` dispatches it. | Preserve and include it in the normal native verification; do not add a duplicate invocation. |
| C02 | **CONFIRMED** | `android/app/src/main/java/com/opencambridge/android/MainActivity.kt:258-268` catches service-start failure and only calls `Log.e` at line 267. `StreamState.lastError` exists at `StreamState.kt:145` but is not updated by this path. | Route a sanitized error through `AppLogger`, authoritative error state, Compose UI, and a retry action while retaining the original exception as the log cause. |
| C03 | **CONFIRMED** | The canonical limit is `ocb2.rs:6`; parser enforcement is at `ocb2.rs:144`. `mf_decoder.rs:227` independently clamps allocation to a copied 16 MiB literal, then copies the full AU length at line 237 without its own rejection. | Share the constant, reject before allocation/copy, and test exact-limit and over-limit inputs. |
| C04 | **CONFIRMED** | `SimpleMediaStream.cpp:441` says the caller holds `m_Lock`, while `SimpleMediaStream::Stop` starts at line 442 and acquires the same lock itself at line 444. `StopInternal` separately and correctly retains the annotation at lines 592-593. | Remove only the incorrect annotation on `Stop`; retain runtime locking. |
| C05 | **CONFIRMED** | `dev-reset.ps1:4` sets global `SilentlyContinue`; line 55 always prints `Reset done.`. There is no elevation check or essential-step result aggregation. | Make essential steps fail fast, scope benign ignores, report PASSED/SKIPPED/FAILED, and return nonzero on essential failure. |
| C06 | **PARTIALLY CONFIRMED** | `dev-build-vcam.ps1:146-180` already compares built, installed, and registry-selected DLL hashes. The ring carries producer/DLL hashes (`rust-frame-producer/src/main.rs:634-635`), but the desktop only renders truncated values (`ControlPanel.tsx:1224-1225`) and does not gate readiness or provide a remediation command. | Extend identity to producer, built DLL, installed DLL, and actually loaded/registered DLL; surface a blocking mismatch with an exact repair command. |
| C07 | **PARTIALLY CONFIRMED** | Installer self-test and host mode exist at `VirtualCamera_Installer/main.cpp:378` and `:392-399`. `--register`, `--unregister`, `--status`, and `--dev-menu` do not exist; no-argument execution reaches `VCamApp()` at line 438 and its inherited menu at lines 275-279. | Add and test the production CLI, make no-args print usage, keep the sample menu only behind `--dev-menu`, and give Tauri clear elevation-aware operations. |
| C08 | **CONFIRMED** | The production installer project directly compiles `..\VirtualCameraTest\*.cpp` and headers at `VirtualCamera_Installer.vcxproj:138-159`, including the registration/host utility used by production. | Move production registration/host ownership into a production-named component before moving/removing the sample project. |
| C09 | **CONFIRMED** | `desktop/tauri-app/src-tauri/src/virtualcam.rs:6` imports `std::sync::Mutex`; state access uses `lock().unwrap()` throughout, including lines 226, 255, 352, 389, and 616. `nv12_preview.rs:3,286,323` uses the same poisoning mutex model. | Introduce deliberate poison recovery, verify lock ordering/reentrancy, and test recovery after a simulated panic. |
| C10 | **CONFIRMED** | ABI fingerprints are manually copied at `SharedMemoryClient.h:13`, producer `main.rs:34`, and `nv12_preview.rs:18`. C++ asserts only three offsets at `SharedMemoryClient.h:77-81`; producer tests only three at `main.rs:1081-1089`; preview tests only two at `nv12_preview.rs:389-390`. | Add one canonical layout description, derive the fingerprint, and enforce every field size/offset plus total sizes during ordinary builds/tests without packed reads. |
| C11 | **CONFIRMED** | Kotlin only has the writer and constants (`Ocb2.kt:12-40`); Rust has native parser fixtures (`ocb2.rs:193-268`); browser parsing is separately embedded in `ControlServer.kt:863-891`. No shared conformance corpus exists. | Create one fixture corpus and run it against Kotlin, Rust, and browser JavaScript for all required framing/state cases. |
| C12 | **CONFIRMED** | `docs/ARCHITECTURE.md:45` documents obsolete `REBINDING`/`ERROR`; actual lifecycle states are `StreamState.kt:14` (`RECONFIGURING`, `RECOVERING`, `FAILED`). | Update architecture/state documentation. |
| C13 | **CONFIRMED** | `StreamState.streaming` is declared write-only at `StreamState.kt:141`; writes remain in `StreamService.kt:299-431`, `H264Streamer.kt:172,211`, and `MjpegStreamer.kt:230,248`; repository search finds no read. | Remove the field and every write. |
| C14 | **CONFIRMED** | `desktop/tauri-app/test_cors.cjs:4` and `test_cors_all.cjs:5` hard-code a live local endpoint; `package.json:6-11` has no test script. | Replace with automated auth/CORS tests or move any retained manual probes to `dev-tools`. |
| C15 | **CONFIRMED** | `README.md:22,158-163` still describes experimental raw `/stream.h264`/OpenH264. `docs/ARCHITECTURE.md:10-25,73,94,116` describes CameraX-YUV/raw-H.264/BGRA/single framebuffer/nearest-neighbour. `docs/VALIDATION.md:53,63` still tests `/stream.h264`. | Rewrite current architecture and validation docs for OCB2, Camera2/MediaCodec, MF decode, NV12 ring, GPU resize/CPU fallback. |
| C16 | **CONFIRMED** | The inherited menu is the default no-argument path (`main.cpp:275-279,438`) and offers Simple/HW/Augmented choices at line 216. | Gate it behind explicit `--dev-menu`; production commands must never enter it. |
| C17 | **PARTIALLY CONFIRMED** | `VirtualCameraMediaSource.vcxproj:45,49` compiles Augmented/HW sources and activation branches remain at `VirtualCameraMediaSourceActivate.cpp:62-70`; however the audit's claim that they are strictly unreachable is overstated while the default installer menu can still register those kinds (`main.cpp:216`). | First isolate the dev menu; then exclude unreachable wrapper sources/branches from the production DLL while preserving upstream attribution. |
| C18 | **PARTIALLY CONFIRMED** | `VirtualCameraSample.sln:6-47` lists installer, DLL, test, manager, app, systray, MSI, and helper projects; the normal script builds only the DLL and installer. Some `VirtualCameraTest` source is nevertheless compiled into the installer (`VirtualCamera_Installer.vcxproj:138-159`). | Produce an exact dependency map before moving/removing any project; retain needed production source under production ownership. |
| C19 | **CONFIRMED** | Auth boundaries are implemented (`ControlServer.kt:80-121,186,196,106-108,1340-1346`) but no Android test exercises LAN token enforcement, `/health`, loopback-only mutations, bind snapshot, CORS, or the chosen constant-time compare path. | Add deterministic HTTP/auth policy tests covering every boundary. |
| C20 | **PARTIALLY CONFIRMED** | Existing Android tests cover extracted capture/transform/coalescing policies, but no test drives `StreamService`/`PipelineController` through the full lifecycle, priority, stale-generation, cancellation, failure, and recovery matrix. | Add state-machine tests; retain the already-useful policy tests. |
| C21 | **CONFIRMED** | `desktop/tauri-app/package.json:6-11` has no frontend test command or framework despite synchronization logic in `ControlPanel.tsx`. | Extract synchronization policy and test 409 rollback, 422 alternatives, SSE/poll convergence, selected/actual launch, missing tuple, and cross-client conflicts. |
| C22 | **PARTIALLY CONFIRMED** | Producer pure tests already cover canonical fallback policy and 422 tuple parsing (`main.rs:903-976`), but no HTTP integration test proves 409 retry, accepted tuple propagation, or request bodies. | Add a local mock-server integration suite without discarding the existing pure-policy coverage. |
| C23 | **PARTIALLY CONFIRMED** | Native buffer/resize self-tests exist (`main.cpp:378`) and Tauri has readiness tests, but installer parsing/exit codes, registration/status/elevation failures, stale identity, host activation, and reset exit behavior lack automated coverage. | Add production CLI/parser and packaging-policy tests; keep real admin/registration mutation isolated from ordinary unit tests. |
| C24 | **PARTIALLY CONFIRMED** | The audit correctly notes UUIDv4 has 122 random bits while a comment says 128; the implementation remains cryptographically adequate and this is documentation precision, not a security defect. | Correct the comment while adding tests to the selected token comparison/auth path; do not replace secure generation unnecessarily. |
| C25 | **FALSE POSITIVE** as a blocker | The audit notes SSE status serialization every 250 ms per client (`ControlServer.kt:1197-1217`) but explicitly limits expected clients to roughly 1-3 and identifies no correctness or measured resource failure. | Record as a scalability observation only; do not redesign the state notification mechanism in this hardening pass absent evidence. |
| C26 | **FALSE POSITIVE** as a blocker | Smooth zoom rebuilds a constrained high-speed burst in `H264Streamer.kt:751-760`, but the audit explicitly reports no defect at current cadence and no failing behavior. | Preserve current behavior; physical stress remains **NOT YET TESTED**. |
| C27 | **ALREADY FIXED** (implementation), **CONFIRMED** (coverage gap) | Bind-time LAN auth, `/health`, constant-time comparison, loopback-only security mutation, and CORS allowlist are already present at `ControlServer.kt:80-121,186,196,106-108,1340-1346`; the audit found no functional gap. | Do not rewrite working auth; add the missing regression tests in C19. |

All phone, OBS, FFmpeg, screen-off, USB reconnect, thermal, live high-speed, and external allocator results remain **NOT YET TESTED** at this baseline.

### Automated evidence log

- 2026-07-17 — `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease`: **PASSED** at `1921826`; **6/6** policy tests, debug APK, unsigned release APK, and release lint. No device instrumentation result is implied.
- 2026-07-17 — `cargo fmt --check && cargo test && cargo build --release`, `windows/virtual-camera-mediafoundation/rust-frame-producer`: **PASSED, 13/13**. Covers fragmented OCB2 reads, malformed lengths, multiple records/read, mid-record reconnect, codec configuration/keyframe restart flags, ring bounds/invalid metadata including portrait NV12, stable C++ ABI layouts, exact 30/60 pacing, unique versus repeated samples, deterministic NV12 patterns, and direct NV12 rotate/mirror plane placement.
- 2026-07-17 — `cargo test && cargo check`, Tauri Rust backend: **PASSED, 1/1** ring ABI test plus compile/check.
- 2026-07-17 — `npm run tauri build`: **PASSED**. TypeScript/Vite frontend, optimized Tauri Rust application, MSI, and NSIS bundles completed.
- 2026-07-17 — `dev-build-vcam.ps1 -NoKill`, Media Foundation DLL and virtual-camera host at source `1921826954c9cbcf16965ddeb1bc8c0835adfcb0`: **PASSED**, zero warnings/errors. Ring ABI `3` / `0x4f43425200030080`; built, installed, and loaded DLL SHA-256 values all matched `F1E32AC767C3BE4A256E72E02F47052DEE1B7369DB41FA0971F9D7283D03D9F7`. This is build/identity evidence, not an OBS/FFmpeg visible-frame pass.
- 2026-07-17 follow-up — `./gradlew testDebugUnitTest assembleDebug --console=plain`: **PASSED, 9/9** (six capture-path policy tests and three frame-transform tests) plus debug APK assembly at implementation commit `d2776fd`.
- 2026-07-17 follow-up — `cargo fmt --check`, `cargo test`, and `cargo build --release`, Rust producer: **PASSED, 16/16** at implementation commit `e69dcd2`. New coverage includes OCB2 transform metadata validation, current virtual-camera readiness, and consumer-PID baseline reset.
- 2026-07-17 follow-up — `cargo fmt --check`, `cargo test`, and `cargo build --release`, Tauri backend: **PASSED, 1/1** at implementation commit `e69dcd2`; `npm run build`: **PASSED** (TypeScript and Vite production output). The three embedded Android web/OBS JavaScript blocks also passed syntax compilation.
- 2026-07-17 follow-up — `dev-build-vcam.ps1 -NoKill` after the buffer-lock implementation now committed as `caf9bd3`: **PASSED**, zero warnings/errors. Built, installed, and loaded DLL SHA-256 values all matched `78362FAB92F014A6F92F1D9073F44B76EB3286D48FFDA792868913846AF178D4`. The script now fails on restore/DLL/host build errors rather than continuing with stale binaries.
- 2026-07-17 exact-HEAD closure — `gradlew testDebugUnitTest assembleDebug assembleRelease --console=plain`: **PASSED, 27/27** at `8bf9492`; debug and release APK assembly plus release lint passed. New tests cover exact/adaptive tuple policy, canonical MJPEG fallback, all eight NV21 rotation/mirror combinations, and cross-client coalescing guards.
- 2026-07-17 exact-HEAD closure — producer `cargo fmt --check`, `cargo test --all-targets`, and `cargo build --release`: **PASSED, 16/16** at `a70a548`; optimized release completed.
- 2026-07-17 exact-HEAD closure — Tauri backend `cargo fmt --check`, `cargo test --all-targets`, and `cargo build --release`: **PASSED, 2/2** for ring ABI and complete host/producer readiness; optimized release completed. Frontend `npm run build`: **PASSED**, TypeScript and Vite production output completed.
- 2026-07-17 exact-HEAD closure — `dev-build-vcam.ps1 -NoKill` at source `a70a5483f7952f9a75db91cc24eba9e4d3c54b24`: **PASSED**, zero warnings/errors. Ring ABI `3` / `0x4f43425200030090`; buffer-lock and NV12 resize fallback self-tests passed; built, installed, and registered-path DLL SHA-256 values all matched `27239FF0F47B148CCA2C9D580731D11DE575F55B99ACE7762BAA51653A438B87`.
- 2026-07-17 pre-physical closure — `gradlew :app:test :app:assembleDebug :app:assembleRelease --console=plain`: **PASSED, 29/29** at implementation head `9e6fa2e`; debug APK, release APK, and release lint-vital completed.
- 2026-07-17 pre-physical closure — producer `cargo fmt --check`, `cargo test --all-targets`, and `cargo build --release`: **PASSED, 18/18**. New tests cover canonical H.264 1080p60 → MJPEG 1080p30 selection and 422 alternative parsing; optimized release completed.
- 2026-07-17 pre-physical closure — Tauri backend `cargo fmt --check`, `cargo test --all-targets`, and `cargo build --release`: **PASSED, 2/2**; frontend `npm run build`: **PASSED** (TypeScript plus Vite production output).
- 2026-07-17 pre-physical closure — `dev-build-vcam.ps1 -NoKill` at implementation head `9e6fa2ed1af4ba93144317f4f943c463b9f91d1c`: **PASSED**, zero warnings/errors. `OCB_BUFFER_LOCK_FALLBACK_TEST=PASSED` and `OCB_NV12_RESIZE_FALLBACK_TEST=PASSED`; built, installed, and loaded DLL SHA-256 values all matched `27239FF0F47B148CCA2C9D580731D11DE575F55B99ACE7762BAA51653A438B87`.

### Physical/live evidence log

- 2026-07-17 — ADB checked repeatedly, including after the final Android build: `adb devices -l` returned an empty device list. APK deployment and OnePlus path/session validation are **NOT YET TESTED**; no 30-FPS or 60-FPS device claim is made.
- 2026-07-17 — FFmpeg and OBS executables were not available on this machine. Enumeration, visible output, exact negotiated formats, unique FPS, USB reconnect, screen-off soak, and end-to-end latency remain **NOT YET TESTED**.

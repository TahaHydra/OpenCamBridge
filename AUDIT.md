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
| 2 | [x] The virtual camera explicitly advertises NV12 1080p60/30 and 720p60/30, with RGB32 last as compatibility only; the normal sample path remains NV12 and GPU-resizes once to the consumer format. | `SimpleMediaStream.cpp`, `SimpleMediaSource.cpp`, `SharedMemoryClient.cpp`; `1ebbcf9`, `c7515e8` | Media Foundation DLL/host builds passed at final code HEAD; zero C++ warnings/errors | **NOT YET TESTED** in FFmpeg or OBS (executables unavailable in this environment) |
| 3 | [x] Ring ABI v3 diagnostics, consumer/sample/copy/validation counters, build hashes, strict slot validation, and deterministic direct-NV12 patterns are implemented. | `main.rs`, `SharedMemoryClient.*`, `SimpleMediaStream.cpp`, `ControlPanel.tsx`, `dev-build-vcam.ps1`; `dac442c` | Producer **13/13** tests; deterministic colour/plane and bounds tests; built/installed/loaded DLL SHA-256 all matched `F1E32AC...D03D9F7` | **NOT YET TESTED** for visible OBS output |
| 4 | [x] `IMF2DBuffer2::Lock2DSize` bounds and true scanline start/pitch are used, with `IMF2DBuffer::Lock2D` and contiguous-buffer fallbacks; every source/destination plane is bounds checked. | `SharedMemoryClient.*`, `SimpleMediaStream.cpp`; `dac442c` | Native DLL/host build passed; producer ring bounds/invalid-metadata tests passed | **NOT YET TESTED** with an external physical camera consumer |
| 5 | [x] One rendezvous `PipelineController` owns start/stop/reconfigure/recover/adapt/fallback/preview commands. Camera/session close callbacks and handler-thread termination are awaited, callbacks are generation-gated, and phone controls await authoritative results. Required lifecycle states are exposed. | `PipelineController.kt`, `ServiceBridge.kt`, `StreamService.kt`, `H264Streamer.kt`, `StreamState.kt`; `242c8d6`, `1921826` | Android tests **6/6**; debug/release builds and release lint passed | **NOT YET TESTED** for repeated physical start/stop/reconnect stress |
| 6 | [x] Desired configuration, revision, generation, lifecycle, request identity, and update metadata live in one immutable CAS snapshot. Mutations use `baseRevision`/`requestId`, stale updates return 409 plus authoritative desired/selected/actual state, clients hydrate before sending changes, and desktop/web subscribe through SSE. | `StreamState.kt`, `StreamService.kt`, `ControlServer.kt`, `StreamViewModel.kt`, `ControlPanel.tsx`; `242c8d6`, `1921826` | Android and frontend production builds passed; structured response schemas compile in debug/release | **NOT YET TESTED** with simultaneous physical phone/web/desktop edits |
| 7 | [x] All UIs consume the canonical per-camera path model. H.264 modes require a complete camera+encoder path; MJPEG selectors only expose FPS supported by regular ImageAnalysis. Invalid explicit requests return HTTP 422 with requested mode, alternatives, and authoritative state. | `H264Capabilities.kt`, `CameraInfoDto.kt`, `CapturePathPolicy.kt`, `MainActivity.kt`, `ControlServer.kt`, `StreamService.kt`, `ControlPanel.tsx`; `242c8d6`, `1921826` | Android policy tests prove a 30-FPS MJPEG path cannot advertise 60; Android **6/6**, frontend build passed | **NOT YET TESTED** against live OnePlus capability/session results |
| 8 | [x] Desktop H.264 preview reads the newest validated decoded NV12 ring slot and uploads Y/UV directly to a WebGL2/ANGLE D3D11 GPU shader; it does not depend on the phone preview or convert through RGBA/BGRA. Phone preview, desktop preview, stream, and virtual-camera controls are distinct. | `nv12_preview.rs`, `Nv12RingPreview.tsx`, `Preview.tsx`, `ControlPanel.tsx`; `e681985`, `c7515e8` | Tauri ring ABI test **1/1**, `cargo check`, frontend build, and full Tauri release bundles passed | **NOT YET TESTED** for live decoded-phone preview visibility |
| 9 | [x] The foreground camera service owns a partial wake lock only while a stream/recovery is active, releases it on stop/error, survives Activity preview removal, reports battery-optimization exemption state, and logs an OEM power warning. | `StreamService.kt`, `ControlServer.kt`, `StreamViewModel.kt`, manifest; `242c8d6`, `c7515e8` | Android debug/release builds and release lint passed | **NOT YET TESTED** (required 30-minute locked-screen physical soak) |
| 10 | [x] Metrics are generation-reset and path-scoped: capture is common; H.264 and MJPEG objects are nullable/exclusive; capture, encoded, transport, decoded unique, virtual-camera unique, repeats, latency, bitrate, fallback, and rejected paths remain separate. MJPEG capture FPS is measured before encode pacing. | `StreamState.kt`, `ControlServer.kt`, `MjpegStreamer.kt`, `main.rs`, `virtualcam.rs`, `ControlPanel.tsx`; `242c8d6`, `c7515e8` | Producer unique/repeat and 30/60 pacing tests passed; Android/debug/release and frontend builds passed | **NOT YET TESTED** against live sustained FPS/latency |
| 11 | [x] Human stderr is not health. Producer stdout emits structured events and desktop `last_error` changes only for `severity=error`. | `main.rs`, `mf_decoder.rs`, `virtualcam.rs`; `dac442c` | Tauri `cargo test` **1/1**, `cargo check`, and full production build passed | **NOT YET TESTED** with a live decoder/device-loss event |
| 12 | [x] Producer readiness has explicit connection/config/keyframe/decode/ring states. Start requires `WRITING_RING` plus three commits; virtual-camera readiness separately requires a consumer and a successful read. Stale metrics transition to `STALLED`. | `main.rs`, `virtualcam.rs`, `ControlPanel.tsx`; `dac442c` | Producer **13/13**, Tauri **1/1**, Tauri `cargo check`, frontend and release-bundle builds passed | **NOT YET TESTED** for live connect/consume/reconnect |

### Automated evidence log

- 2026-07-17 — `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease`: **PASSED** at `1921826`; **6/6** policy tests, debug APK, unsigned release APK, and release lint. No device instrumentation result is implied.
- 2026-07-17 — `cargo fmt --check && cargo test && cargo build --release`, `windows/virtual-camera-mediafoundation/rust-frame-producer`: **PASSED, 13/13**. Covers fragmented OCB2 reads, malformed lengths, multiple records/read, mid-record reconnect, codec configuration/keyframe restart flags, ring bounds/invalid metadata including portrait NV12, stable C++ ABI layouts, exact 30/60 pacing, unique versus repeated samples, deterministic NV12 patterns, and direct NV12 rotate/mirror plane placement.
- 2026-07-17 — `cargo test && cargo check`, Tauri Rust backend: **PASSED, 1/1** ring ABI test plus compile/check.
- 2026-07-17 — `npm run tauri build`: **PASSED**. TypeScript/Vite frontend, optimized Tauri Rust application, MSI, and NSIS bundles completed.
- 2026-07-17 — `dev-build-vcam.ps1 -NoKill`, Media Foundation DLL and virtual-camera host at source `1921826954c9cbcf16965ddeb1bc8c0835adfcb0`: **PASSED**, zero warnings/errors. Ring ABI `3` / `0x4f43425200030080`; built, installed, and loaded DLL SHA-256 values all matched `F1E32AC767C3BE4A256E72E02F47052DEE1B7369DB41FA0971F9D7283D03D9F7`. This is build/identity evidence, not an OBS/FFmpeg visible-frame pass.

### Physical/live evidence log

- 2026-07-17 — ADB checked repeatedly, including after the final Android build: `adb devices -l` returned an empty device list. APK deployment and OnePlus path/session validation are **NOT YET TESTED**; no 30-FPS or 60-FPS device claim is made.
- 2026-07-17 — FFmpeg and OBS executables were not available on this machine. Enumeration, visible output, exact negotiated formats, unique FPS, USB reconnect, screen-off soak, and end-to-end latency remain **NOT YET TESTED**.

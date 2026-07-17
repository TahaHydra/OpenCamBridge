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
| 1 | [ ] In progress: regular surface exists; constrained high-speed surface and GPU bridge still required | Android capture files; pending | Existing Android build only; new engine tests pending | NOT YET TESTED |
| 2 | [ ] Format enumeration is implemented; external enumeration/visible-frame acceptance remains | `SimpleMediaStream.cpp`; earlier `1ebbcf9`, diagnostics `dac442c` | Media Foundation DLL and host build passed on 2026-07-17 | NOT YET TESTED (FFmpeg and OBS) |
| 3 | [x] Code-side diagnostics and deterministic direct-NV12 patterns implemented | producer ring ABI v3, `SharedMemoryClient.*`, `SimpleMediaStream.cpp`, `ControlPanel.tsx`, `dev-build-vcam.ps1`; `dac442c` | Producer 11/11 tests; NV12 pattern colour/plane test; ring ABI layout test; DLL/host build with matching built/installed/registered hashes | NOT YET TESTED (OBS visible output) |
| 4 | [x] `IMF2DBuffer2::Lock2DSize` bounds and true scanline offset/pitch are used; `IMF2DBuffer::Lock2D`, contiguous fallback retained | `SharedMemoryClient.*`, `SimpleMediaStream.cpp`; `dac442c` | Media Foundation DLL/host build passed; ring metadata/bounds tests passed | NOT YET TESTED |
| 5 | [ ] Serialized Android controller and awaited teardown still required | pending | pending | NOT YET TESTED |
| 6 | [ ] Authoritative revisioned snapshot, common capability endpoint, conflict handling, and server-pushed state still required | pending | pending | NOT YET TESTED |
| 7 | [ ] Common exact-path capability validation and HTTP 422 rejection still required | pending | pending | NOT YET TESTED |
| 8 | [ ] Native decoded-NV12 desktop preview still required | pending | pending | NOT YET TESTED |
| 9 | [ ] Wake lock and activity-independent streaming lifecycle still required | pending | pending | NOT YET TESTED (30-minute screen-off test) |
| 10 | [ ] Generation-scoped nullable metrics schema/reset still required | pending | pending | NOT YET TESTED |
| 11 | [x] Human stderr is no longer health; producer emits structured events and only `severity=error` sets desktop `last_error` | `main.rs`, `mf_decoder.rs`, `virtualcam.rs`; `dac442c` | Tauri `cargo check` and frontend production build passed | NOT YET TESTED |
| 12 | [x] Code-side producer and readiness states implemented; Start waits for `WRITING_RING` plus three committed frames; consumer readiness is separate | `main.rs`, `virtualcam.rs`, `ControlPanel.tsx`; `dac442c` | Tauri `cargo check`; frontend production build; producer 11/11 tests | NOT YET TESTED (live connect/consume) |

### Automated evidence log

- 2026-07-17 — `cargo test`, `windows/virtual-camera-mediafoundation/rust-frame-producer`: **PASSED, 11/11**. Covers fragmented OCB2 reads, malformed lengths, multiple records/read, mid-record reconnect, codec config/keyframe flags, ring bounds/metadata, stable C++ ABI layouts, exact 30/60 pacing, unique versus repeated samples, and deterministic NV12 patterns.
- 2026-07-17 — `cargo build --release`, Rust producer: **PASSED**.
- 2026-07-17 — `cargo check`, Tauri Rust backend: **PASSED**.
- 2026-07-17 — `npm run build`, Tauri frontend: **PASSED**.
- 2026-07-17 — `dev-build-vcam.ps1 -NoKill`, Media Foundation DLL and host: **PASSED**, zero warnings/errors; built, installed, and registered DLL hashes matched. This is a build/identity result, not an OBS/FFmpeg frame-output pass.

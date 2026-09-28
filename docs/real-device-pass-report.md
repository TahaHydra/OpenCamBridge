# GUI real-device pass

Branch: `ui/camo-redesign`. Regression baseline:
`0c4712f3f8a3b323bb3715747c7a020e661a689f`.
Worktree: `C:\Dev\OpenCamBridge-GUI` only.

## Implementation

- Keep the existing full-resolution WebCodecs preview as an independent subscriber.
  Native Start now attaches the producer to the already-running phone stream,
  waits for ring frames, then starts the camera host. Native Stop does not stop
  Android or reload preview. Previously Start restarted the full pipeline, and
  transient preview failures could permanently select the slow compatibility path.
- Transient H.264 failures now reconnect with bounded backoff. Unsupported decoder
  environments retain the NV12 compatibility fallback. Removed the old preview
  watchdog that used JPEG counters even for H.264.
- Phone controls use native Tauri HTTP with connect/whole-response deadlines,
  cancellation, bounded control bodies and no redirects carrying a token. Native
  polling replaces browser EventSource. MJPEG now also renders through native HTTP,
  a bounded JPEG parser and a full-resolution canvas rather than browser-only URLs.
- Native process operations run off the UI thread. Generation checks prevent a
  queued old Stop or failed Start from tearing down a newer session. Offline phone
  detection cancels requests and initiates local teardown without remotely starting
  the phone service. Android's full Stop semantics are unchanged.
- Output framing is a normalized, persisted source crop, independent of capture
  resolution and preview Fit/Fill. The full-source editor supports presets and drag/
  resize. Upright dimensions come from source metadata, not preview diagnostics, so
  pausing preview cannot change the output crop.
- A small versioned shared settings mapping carries output framing to the native
  compositor, including the FrameServer service. Native Fit pads; Fill/Custom trim
  to the negotiated destination aspect without stretching. GPU and CPU paths use
  the same geometry; source/ring pixels stay full resolution. Portrait formats are
  added alongside landscape formats. The existing playout scheduler is unchanged.
- Padding uses explicit video color/range handling and exact neutral NV12 padding
  after GPU readback. CPU fallback uses the same black values. The old background
  declaration alone was insufficient protection against the observed colored bars;
  this pass does not claim a separately proven driver-specific root cause.
- Zoom requests one IDR after the final tagged Camera2 capture request completes,
  rather than at intermediate animation steps. Curtain disposal restores the exact
  previous window brightness and system-bar state.
- Manual H.264 bitrate below 70% of Auto gets a warning, not a restriction. Existing
  capability-clamped Auto policy remains (1080p60 recommendation: 16 Mb/s). Restored
  the original packaged logo instead of the redesign's replacement mark.

## Completed verification

- Frontend: 78 tests, 10 OCB2 conformance cases, TypeScript checks.
- Rust desktop: 20 tests, including stale Start/Stop regressions and real shared
  framing mapping readback.
- Android: 71 tests, debug APK build.
- Native DLL/host built; geometry tests and all four installer pipeline self-tests
  passed. GUI DLL was registered by the user from administrator PowerShell.
- S24 regular 1920x1080 H.264 at 60 FPS: WebCodecs rendered the upright 1080x1920
  canvas at approximately 59–60 FPS across native startup and Stop. No compatibility
  fallback or diagnostics reset observed. Producer decoded/wrote approximately 60 FPS.
- Actual Media Foundation consumer, not a mock source:

| Output | Distinct / samples | Repeats / timestamp gaps | Arrival FPS |
|---|---:|---:|---:|
| 1920x1080 NV12 60 | 598 / 598 | 0 / 0 | 60.005 |
| 1080x1920 NV12 60 | 300 / 300 | 0 / 0 | 59.999 |
| 1920x1080 YUY2 30 | 150 / 150 | 0 / 0 | 29.995 |
| 1280x720 RGB32 30 | 150 / 150 | 0 / 0 | 30.020 |

These are short 5–10-second consumer checks, not long-run drift measurements.
Gaps mean sample timestamps exceeding 1.5 frame periods. Distinctness uses full
sample fingerprints. Landscape padding measured NV12 YUV 16/128/128, YUY2
16/128/16/128, and RGB32 BGRA 0/0/0/255.

- Two completed zoom requests each logged exactly one settled IDR, with unchanged
  capture generation and dimensions. This is not a subjective detail-quality test.
- Dim changed brightness override to 0.0; waking restored automatic control
  (`NaN`, no app override). Other disposal cases have unit coverage, not physical
  coverage in this pass.

The final two review fixes (generation-safe native commands and preview-independent
framing dimensions) were automated-tested and included in the final build. The
earlier physical runs are not represented as a rerun of every final-build scenario.

## User-run acceptance checklist

Hands-on testing stopped at the user's explicit request. These remain manual checks:

1. USB / H.264 1080p60 Auto: move the phone, start/stop virtual camera several times.
   Preview should remain WebCodecs near 60 FPS without blinking. Check Windows Camera
   or OBS concurrently; repeat at 30 FPS and confirm it is not promoted to fake 60.
2. Press full **Stop on the phone**, including while native Start is pending. Desktop
   must stay responsive and show stopped/disconnected. Press Start on the phone and
   confirm preview reconnects. Native camera can then be started again.
3. In Windows Camera/OBS compare Full/Fit, Fill, portrait and a dragged Custom crop.
   The output should match the editor's application trim, never stretch, and have
   black padding. Pause/resume preview and resize the window: output crop must not move.
4. Zoom in/out and inspect fine detail after settling. Compare 720p30 and 1080p30 H.264
   using Auto, then 1080p60 Auto 16 Mb/s; do not compare low-bitrate H.264 against
   high-bitrate MJPEG as an equal quality test. Repeat front/back and MJPEG. Record
   genuine vendor/lens/FPS field-of-view differences rather than assuming a bug.
5. Dim and wake; also Stop while dimmed and leave/reopen the activity. Brightness and
   system bars should return to their previous state.
6. LAN: phone Settings > Connection > Wi-Fi + token, then Stop/Start on the phone.
   Remove the 8080 ADB forward (`adb forward --remove tcp:8080`) or unplug USB. Connect
   desktop using the phone's LAN IP and token. Check settings, preview, virtual camera,
   phone Stop/Start and reconnect. This end-to-end LAN run is not yet verified.
7. Smoke-test OBS, Windows Camera, Discord and a browser/WebRTC app, including portrait
   negotiation where supported. Only the direct Media Foundation consumer was tested
   here; application-specific acceptance and other vendors/iGPUs remain unverified.

During setup, the phone's connection preference was changed to Wi-Fi + token, applied
at its next Start. For USB testing, change it back to USB only and Stop/Start first.

## Tradeoffs / remaining limits

WebCodecs and the native producer decode separately; this isolates preview failure
from native output but is not a single shared-texture decoder. Compressed HTTP still
crosses Tauri IPC, while full raw H.264 preview frames do not. Native CPU fallback
and existing Media Foundation readback/copies remain for compatibility. Output crops
that differ from an application's aspect are center-trimmed, shown in the editor;
the application still chooses its supported output format. No claim of identical
Camera2/CameraX vendor field of view, universal portrait support, exact end-to-end
latency, or validated performance across other phones and integrated GPUs is made.

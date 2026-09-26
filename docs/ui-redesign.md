# UI redesign (`ui/camo-redesign`)

The desktop and Android UIs were reorganised from engineering dashboards into
product interfaces: the camera preview dominates, everyday controls are plain,
OBS and the virtual camera (which apps list as "OpenCamBridge Camera") are
first-class, and engineering detail lives behind **Advanced** and
**Diagnostics**. The streaming engine, protocols and backend APIs are
unchanged.

## Desktop (`desktop/tauri-app/src`)

```text
+-----------------------------------------------------------------------+
| logo OpenCamBridge        [ Meeting HD  1080p · 30 FPS · H.264 v ]  Advanced o  (gear) |
+--------------+----------------------------------------+---------------+
| DEVICE       |                                        | OUTPUTS       |
| phone v      |                                        | Virtual       |
| Camera v     |              PREVIEW                   |   camera      |
| VIDEO  preset|      (untouched <Preview> in a         | OBS Studio    |
| Resolution v |       PreviewStage slot)               | Clean feed    |
| Frame rate   |                                        | (Advanced:    |
| H.264|MJPEG  |                                        |  Performance) |
| ADJUST       |                                        |               |
| Torch Rotate Mirror-output, Zoom                      |               |
| PREVIEW Preview framing Fit|Fill, Mirror preview      |               |
| (Advanced: Quality)                                   |               |
+--------------+----------------------------------------+---------------+
```

| Area | Files |
|---|---|
| App shell, connection lifecycle, USB forward repair, session log | `App.tsx` |
| Connect screen (USB phone list, Wi-Fi, auto-reconnect) | `components/connect/ConnectScreen.tsx` |
| Main window | `components/shell/Studio.tsx`, `TopBar.tsx`, `ProfileMenu.tsx` |
| Camera rail | `components/camera/CameraRail.tsx`, `DevicePicker.tsx`, `CommitSlider.tsx` |
| Preview container / clean feed | `components/stage/PreviewStage.tsx`, `CleanFeed.tsx` |
| Outputs rail | `components/outputs/OutputsRail.tsx` |
| Settings modal (10 pages) | `components/settings/**` |
| Diagnostics window | `components/diagnostics/DiagnosticsModal.tsx` |
| Design system | `components/primitives.tsx`, `styles/*.css` (tokens, base, components, studio, settings, connect, legacy) |
| Camera engine (sync + orchestration) | `state/useCameraController.ts` |
| Preferences, profiles, status wording, OBS state | `state/preferences.ts`, `state/profiles.ts`, `state/status.ts`, `state/useObs.ts` |
| Profile policy (tested) | `services/profilePolicy.js` + `.d.ts` + `.test.mjs` |
| Dev-only mock backend | `dev/mockRuntime.ts` |

`ControlPanel.tsx` is gone. Its logic moved **unchanged** into
`useCameraController` (same refs, ordering, restart-scope policy, readiness
checks, diagnostics log); its markup was replaced by the rails, stage and
modals.

### Where the old controls went

| Old location | New location |
|---|---|
| Header meters (Source / Rate / Codec) | Preview HUD (normal), top-bar live stats (Advanced) |
| Signal › Publish to Windows | Outputs › Virtual camera card |
| Signal › granular pipeline (developer) | Diagnostics › Developer |
| Signal › Signal chain, Session | Diagnostics › Overview; Settings › Devices |
| Output › Resolution, Frame rate, Codec | Camera rail › Video |
| Output › Capture profile (developer presets) | Top-bar profile menu, Settings › Profiles |
| Output › Bitrate (Automatic / Manual), JPEG quality, Target bandwidth | Camera rail › Quality (Advanced), Settings › Video › Quality |
| Output › Keyframes | Settings › Advanced › Developer options ("H.264 keyframe safety interval") |
| Output › Desktop preview on/off | Settings › Performance (and the paused-preview stage message) |
| Output › Developer mode | Advanced switch (top bar, Settings › General/Advanced) |
| Image › Lens, Zoom | Camera rail › Device / Adjust |
| Image › Rotation, Mirror, Torch | Camera rail › Adjust (Rotate, Mirror output, Torch); Settings › Video |
| Image › Preview layout (developer) | Camera rail › Preview (Advanced), Settings › Video |
| Diag › Events log, copy | Diagnostics › Event log; Settings › Advanced |
| Diag › Preview stages, Throughput, Phone pipeline, Processes, Ring & binaries, Reference | Diagnostics tabs |
| Diag › OBS fallback | Outputs › OBS Studio card, Settings › Integrations |
| Diag › Enter clean feed | Outputs › Clean feed card |
| Logs button | Settings › Advanced › Session log; Diagnostics › Event log |

### Intentional behaviour changes

- **Stop virtual camera** (Virtual camera card) stops the decoded feed and the
  Windows camera host but leaves the phone streaming, so the preview continues
  (`stopVirtualCamera`). The old full stop (feed + host + phone stream) is
  still available as *Stop phone camera* in the device menu (`stopEverything`).
  Neither is the phone's own Stop button, which still ends everything on the
  phone and cannot be undone from the desktop.
- **Profiles** are shortcuts, not a second set of settings (see below). They
  are exact: a profile the selected lens does not report is disabled with the
  reason. Applying one sends an explicit phone profile (same
  resolution→profile mapping the resolution picker always used) and only the
  keys that change, so a bitrate-only switch never rebinds the camera.
- **Keyframe interval** is imported from the phone instead of being pinned to 5
  (the pin silently reset phone-side choices on the next desktop change). It
  is no longer on the camera rail or Settings › Video, even in Advanced mode:
  it lives in Settings › Advanced › Developer options with a Default button.
  The 5 s default and the encoder logic are unchanged.
- **Zoom** is single-flight/latest-wins; quality sliders commit on release.
- **OBS**: the default setup method adds OpenCamBridge Camera itself as a Video
  Capture Device (`dshow_input`); browser-source and window-capture remain as
  fallbacks. Browser-source setup no longer forces the window into clean feed.
- **Clean feed** hides its exit button after 2.5 s without mouse movement so
  window capture never records it.
- The controller lives for the whole connection, so clean feed, settings and
  diagnostics no longer reset its state or stop preview-decoder supervision.
- Google Fonts were removed (offline/privacy); system fonts only.
- Default window 1440×900, minimum 1024×640.

### Profiles and Custom

`services/profilePolicy.js` is the single source of truth (unit-tested).

- A profile sets resolution, frame rate, format and quality — the same values
  the camera rail edits. When the current settings match no profile, the top
  bar, the rail's Video header and Settings › Profiles all say **Custom** and
  show the settings in use (for example `720p · 60 FPS · H.264 · 16 Mb/s`), with
  a one-click "Save as profile". User-made profiles are tagged "Yours" and
  default to "My profile N" so they are never confused with Custom.
- **H.264 bitrate mode is part of a profile.** Meeting HD and Smooth Motion
  use *Automatic* (the phone's recommended rate for the mode); Low Latency
  (4 Mb/s) and High Quality (16 Mb/s) pin a *Manual* rate. Applying a profile
  always sends `h264BitrateMode` explicitly — Automatic profiles leave the
  stored manual rate alone, MJPEG profiles leave H.264 settings alone.
  Matching requires the same mode (and, for Manual, the same rate), so
  switching Meeting HD to Manual reads as Custom. Saved profiles keep the mode;
  ones saved before modes existed pinned a rate and count as Manual.
- **Smooth Motion** (`smooth-motion`, id unchanged) is the only
  capability-aware preset. `resolveProfile(profile, camera)` picks the first of
  its candidates that the selected camera reports as a *genuine regular* H.264
  60 FPS mode: 1920×1080, else 1280×720, both at Automatic bitrate (16 and
  9 Mb/s on the phone). "Genuine regular" means listed in
  `h264Modes` **and**, when the phone sends `h264PathCapabilities`, backed by a
  supported `REGULAR_SURFACE` path — a rate only a high-speed session reaches
  does not count. Phones without path evidence are judged by `h264Modes`.
  With no such mode it stays in the list, disabled, with the reason
  ("Needs 60 FPS — Front camera supports up to 30 FPS").
- `useProfiles(camera)` returns every profile resolved for the selected lens;
  `profileAvailability` and the controller's `applyProfile` resolve again for
  the lens they are given, so a profile resolved for another camera is never
  applied by mistake.

### H.264 bitrate: Automatic / Manual

The phone owns the policy (`H264BitratePolicy.kt`): Automatic targets
16 / 10 Mb/s for 1080p at 60 / 30 FPS, 9 / 6 Mb/s for 720p and 3 Mb/s below,
clamped to the encoder's range; Manual uses the user's rate (1–50 Mb/s),
clamped the same way, at startup and live. The desktop shows it in the rail's
Quality group (Advanced) and Settings › Video › Quality
(`components/camera/BitrateControl.tsx`):

- a Segmented **Automatic | Manual** control;
- Automatic shows the target read-only ("10 Mb/s for 1080p · 30 FPS", from
  `automaticBitrateMbps`, a copy of the phone table);
- the slider exists only in Manual. Choosing Manual starts it at the current
  automatic rate, so nothing changes until the slider moves.

`updateBitrateMode` / `updateManualBitrate` in the controller always send the
mode with the rate: the phone would otherwise infer Manual from any bare
`h264Bitrate`. The phone omits `h264BitrateMode` from JSON while it is
"auto" (kotlinx does not encode defaults), so `importAuthoritativeState`
reads a status with `h264Bitrate` and no mode as Automatic. The Android app
has the same control at the top of Settings › Advanced › Encoding.

### Preview slot contract

`PreviewStage` owns product states (connecting, unreachable, stopped, failed,
paused) and local presentation (Preview framing Fit/Fill, mirror preview —
both affect only this preview and the clean feed, never the virtual camera
output); it mounts
`<Preview baseUrl token fitMode serverStatus />` inside `.viewer__viewport`.
`Preview` renders Codex's `H264Preview` for H.264 (worker-parsed OCB2,
WebCodecs decode, full-resolution `VideoFrame`s drawn to an
`OffscreenCanvas`, timestamp-driven presentation with bounded queues and its
own reconnects). After repeated failures, or without WebCodecs /
`OffscreenCanvas`, it falls back to the native `Nv12RingPreview` (max 960 px,
flagged by a "Compatibility preview" badge) and announces that with
`PREVIEW_FALLBACK_EVENT`; only then does the controller start the desktop
producer for preview. The stage keeps its place in the tree in clean feed
(`CleanFeed` wraps it as `display: contents` until active), so entering or
leaving clean feed never reconnects the preview. A replacement renderer only
needs to:

- fill its parent (`.preview-wrapper` → `.preview-stage` sizing is in
  `styles/legacy.css`),
- render the picture as `.preview-img` (or a plain `canvas`/`video`) so the
  mirror-preview transform applies without flipping text overlays,
- honour `fitMode` (`'fit' | 'fill'`),
- keep publishing `PREVIEW_DIAGNOSTICS_EVENT`; the controller consumes it.

### Integrated `v2/streaming-engine` work (3f3aa45)

Merged into this branch; where each upstream change lives now:

| Upstream change | Here |
|---|---|
| `H264Preview`, `h264Preview.worker.ts`, `h264PreviewPolicy.*` (+ tests), `Preview.tsx`, `previewDiagnostics.ts` | Taken as-is. The fallback badge got a `vf-hud--notice` class so it no longer covers the source readout. |
| `ControlPanel.tsx`: `nativePreviewFallback` listener, producer gated on fallback | `useCameraController.ts`, preview auto-start effect. |
| `ControlPanel.tsx`: `h264BitrateMode` in settings, import, posted keys | `state/types.ts`, `useCameraController.ts` (import treats a missing mode as Automatic, see above). |
| `ControlPanel.tsx`: Automatic / Manual select, 1–50 Mb/s | `BitrateControl.tsx` in the rail's Quality group and Settings › Video › Quality; profiles carry the mode. |
| `ControlPanel.tsx`: renderer-aware preview diagnostics | Diagnostics › Desktop preview (WebCodecs rows, or the native NV12 rows in fallback); Performance card and Settings › Performance show the renderer and decode / draw times instead of raw IPC figures. |
| `ControlPanel.tsx`: "Phone→ring estimate (lower bound)", "Output underruns" | Diagnostics › Throughput. |
| `App.tsx`: controller stays mounted in clean feed | Already true: `Studio` owns the controller for the whole connection. |
| `MainActivity.kt` `OutputTab`: Automatic / Manual | `AdvancedSettingsPage.kt` › Encoding (the old tab UI stays deleted). |
| Phone bitrate policy, persistence, `StreamViewModel`, Tauri `http` allowlist, frame producer, docs | Taken as-is. |

### Dev mock

`npm run dev`, then open `http://localhost:1420/?mock` (add `&connected` to
auto-connect, `&advanced` for Advanced mode; scenarios `?mock=stopped`,
`offline`, `failed`, `unregistered`, `nodevice`). It simulates the phone API and
the Tauri commands, including NV12 frames through the native preview renderer:
`/stream.ocb2` is not simulated, so the WebCodecs preview falls back after its
retries, which exercises the fallback path. Like the phone, it omits
`h264BitrateMode` while Automatic and treats a bare bitrate as Manual.
Its lenses cover every Smooth Motion outcome: Back main has regular 1080p60,
Back ultrawide only 720p60, Front camera 1 only 30 FPS. It is compiled out of
production builds.

## Android (`android/app/src/main/java/com/opencambridge/android`)

| Screen | File |
|---|---|
| Root, navigation, phase logic, snackbars | `ui/screens/OpenCamBridgeApp.kt` |
| Stopped / Starting / Paused / Camera error / Stopping | `ui/screens/StatusScreen.kt` |
| Live (preview, status, Switch, Torch, Dim, Zoom, Stop) | `ui/screens/LiveScreen.kt` |
| Settings (Camera & quality, Display & power, Connection, Advanced, Diagnostics, Logs, About) | `ui/screens/settings/*` |
| Help | `ui/screens/HelpScreen.kt` |
| Screen curtain | `ui/ScreenCurtain.kt` |
| Phone-only UI state (curtain mode, mirror preview, service running, USB status) | `ui/PhoneUiViewModel.kt` |
| Persisted settings while stopped | `state/StoppedSettingsStore.kt` |
| Local camera preview (moved verbatim, + mirror preview) | `ui/preview/CameraPreview.kt` |
| Design system | `ui/Theme.kt`, `ui/components/*` |

`MainActivity` keeps its permission, start, stop and rotation functions
byte-for-byte; it only hosts `OpenCamBridgeApp`. Stop still ends camera,
stream, control server and foreground service, and nothing can start the phone
remotely afterwards. The phone UI calls exactly the same `startCameraOrService`
and `stopEverything` as before.

Old tabs → new places: Stream → Home (Stopped/Live); Camera → Settings › Camera
& quality (lens, size, rate, format, mirror output, rotation) and Live
(switch, torch, zoom); Output → Settings › Advanced (encoding, preview shapes,
zoom speed) and Display & power (phone preview, mirror preview); Link →
Settings › Connection; Logs → Settings › Advanced › Diagnostics / Logs. The
developer-mode toggle is replaced by the Logs page's Problems/Everything filter.

Settings › Advanced › Encoding has the Automatic / Manual control from
`v2/streaming-engine`: Automatic shows the rate `H264BitratePolicy` targets
for the current mode; Manual shows a 1–50 Mb/s slider and, when chosen,
starts at that automatic rate.

## Backend gaps the UI cannot expose cleanly

- **Settings while the phone service is stopped**: pipeline changes need the
  running controller (`ServiceBridge` is null), and `StreamState` only loads
  settings at service start. The UI reads persisted values directly and allows
  connection/preview edits while stopped; camera changes wait for Start.
- **Access mode / port changes need a service restart** to rebind; the UI can
  only detect it by comparing against the bind it observed at start.
- **Output mirror and rotation rebind the camera** (they count as capture
  changes on the phone), so they are not instant.
- **Unauthorised adb devices are filtered out** by `list_devices`, so the
  desktop cannot say "accept the USB debugging prompt on the phone".
- **No virtual-camera format negotiation readback before a consumer attaches**
  beyond the ring, and no OBS source-format control (the OBS Video Capture
  Device is added with the device's default format).
- **Start with Windows / tray / auto-update** need Tauri plugins
  (autostart, tray, updater) that are not in this build.
- **LAN / non-8080 connections vs CSP**: the CSP in `tauri.conf.json`
  (`connect-src`, `img-src`) only allows `127.0.0.1:8080` and
  `localhost:8080`, so Wi-Fi phones and custom ports may be blocked in
  packaged builds even though the UI offers them.
- **Phone app version** is hard-coded as `2.0.0` in `/api/device/info`.

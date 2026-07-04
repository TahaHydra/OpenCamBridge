# Runtime verification checklist (phone + OBS)

These checks require real hardware (an Android phone + OBS on Windows) and are
**not** covered by the automated build checks. Run them after building all
components. The build checks that *are* automated:

```powershell
# Android
cd android; .\gradlew.bat assembleDebug

# Rust producer
cd windows\virtual-camera-mediafoundation\rust-frame-producer; cargo build --release

# Desktop
cd desktop\tauri-app; npm ci; npx tsc --noEmit; cargo check --manifest-path .\src-tauri\Cargo.toml

# Virtual camera Media Foundation DLL (from a clean clone)
.\dev-build-vcam.ps1
```

## Codec x resolution x FPS matrix

For each row: set it from the desktop **Resolution** and **Frame Rate**
controls (independent of the Capture Profile preset), start the pipeline, and
open the OpenCamBridge Camera in OBS. Record the **Android FPS** and producer
**written_fps** shown in the desktop metrics panel.

| Codec | Resolution | Target FPS | Expected | What to confirm |
|-------|-----------|-----------|----------|-----------------|
| MJPEG | 1280x720  | 30 | Stable 30 | Baseline; must remain perfect |
| MJPEG | 1280x720  | 60 | ~50-60 if sensor+light allow | Actual FPS readout climbs above 30 |
| MJPEG | 1920x1080 | 30 | Stable 30 | No regression vs. before |
| MJPEG | 1920x1080 | 60 | Device dependent | Readout shows achieved rate honestly |
| H.264 | 1280x720  | 30 | Stable 30, no jumps | No `Native:16`; steady frames |
| H.264 | 1280x720  | 60 | Stable if CPU allows | Watch `last_error` and jumps |
| H.264 | 1920x1080 | 30 | Stable 30 | No decode errors |
| H.264 | 1920x1080 | 60 | May be "Not Viable" | Documented software-decode limit |

Notes:
- **FPS is delivered by the phone camera.** If the readout stays at 30 for a 60
  request, the sensor/use-case/lighting does not support 60 at that resolution;
  this is a hardware limit, not the app forcing 30. The metrics panel now shows
  the actual delivered FPS next to the target so the bottleneck is visible.
- 60 fps and resolution are independent settings; any resolution can pair with
  any frame rate.

## Rotation (must rotate, must not crop)

For each output orientation (Rotate Output 90 deg on the desktop, or the phone's
Output Orientation control), confirm in OBS:

- [ ] Landscape (0 deg): full-frame, no bars, upright.
- [ ] Portrait CW (90 deg): image is upright and **not vertically squashed**;
      portrait content sits centered with black side bars (pillarbox), no
      cropping.
- [ ] Portrait CCW (270 deg): same as 90 deg, opposite direction.
- [ ] Upside down (180 deg): full-frame, inverted correctly.
- [ ] Mirror toggle flips left/right after rotation, as seen by the viewer.

## Phone preview button

- [ ] With streaming running, toggle **Preview on phone** ON: the phone shows
      live camera frames within ~1s (a brief rebind occurs).
- [ ] Toggle it OFF: preview stops, the stream to OBS is uninterrupted.
- [ ] Toggle ON again mid-stream: preview returns (this was previously black).

## Phone <-> desktop state sync

- [ ] Change FPS / resolution / codec / JPEG quality / rotation / torch on the
      **phone**, confirm the **desktop** control reflects it within ~1-2s.
- [ ] Change the same settings on the **desktop**, confirm the **phone** UI
      reflects it within ~1s.
- [ ] Drag the desktop **JPEG Quality** slider: the producer must **not**
      restart (no PID change in the metrics panel) — quality applies live.
- [ ] Switch codec MJPEG <-> H.264 a few times: exactly one producer restart per
      switch, no restart storm.

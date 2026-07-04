# V1 runtime verification checklist (phone + OBS)

These checks require real hardware (an Android phone + OBS on Windows) and are
**not** covered by the automated build checks. V1 is the stable **MJPEG** path;
H.264 is developer-only.

## Automated build checks (must pass before committing)

```powershell
# Android
cd android
.\gradlew.bat assembleDebug
adb install -r .\app\build\outputs\apk\debug\app-debug.apk

# Rust producer
cd windows\virtual-camera-mediafoundation\rust-frame-producer
cargo build --release

# Desktop
cd desktop\tauri-app
npm ci
npx tsc --noEmit
cargo check --manifest-path .\src-tauri\Cargo.toml

# Virtual camera Media Foundation DLL (from repo root, clean clone)
.\dev-build-vcam.ps1
```

## MJPEG capture matrix (main/back-wide lens unless noted)

Set Resolution and Frame Rate from the desktop controls (independent of any
profile). Read the desktop **Diagnostics** panel for target-vs-actual FPS.

| Lens | Resolution | FPS | Expected |
|------|-----------|-----|----------|
| back-wide | 640x480   | 30 | Stable |
| back-wide | 640x480   | 60 | Stable if lens reports it (else option disabled) |
| back-wide | 1280x720  | 30 | Stable (baseline) |
| back-wide | 1280x720  | 60 | ~60 on capable lens; honest lower value otherwise |
| back-wide | 1920x1080 | 30 | Stable |
| back-wide | 1920x1080 | 60 | If supported; else falls back with clear readout |
| telephoto | 1280x720  | 30/60 | Only if exposed; 30-only is acceptable and shown |
| front     | 1280x720  | 30 | Stable |

- [ ] The 60 fps option is **disabled** when the selected lens can't do 60 at
      that resolution (capability-driven, not guessed).
- [ ] Diagnostics shows "Delivering N/target fps"; N is honest, not faked.
- [ ] Default lens on connect is main/back-wide, not telephoto.

## Rotation (one manual button, phone + desktop synced)

Cycle the Rotate button: 0 → 90 → 180 → 270 → 0. In OBS confirm:

- [ ] 0°: upright, full frame, no bars.
- [ ] 90°/270°: content is **actually rotated and upright**, not sideways;
      portrait content is pillarboxed (black side bars), **not** stretched or
      vertically cropped.
- [ ] 180°: upright, inverted correctly.
- [ ] Phone held vertical and horizontal both produce a usable upright image.
- [ ] Phone and desktop always show the same rotation value.

## Torch (capability-based)

- [ ] Torch control is shown **only** when the active lens has a flash.
- [ ] Switching to a lens without flash hides the torch control.
- [ ] Switching to main/back-wide (with flash) shows and toggles torch.
- [ ] A torch failure shows a message and a Diagnostics/Logs entry (not silent).

## Phone ↔ desktop sync

- [ ] Change lens / resolution / FPS / quality / bandwidth / mirror / rotation /
      torch on the **phone** → desktop reflects it within ~1–2s.
- [ ] Change the same on the **desktop** → phone UI reflects it within ~1s.
- [ ] No phone control is decorative: every button either works or is
      hidden/disabled; failures appear in the phone Logs tab.
- [ ] Dragging the desktop JPEG Quality slider does **not** restart the producer.

## Codec / Developer mode

- [ ] Default codec is MJPEG; H.264 is not visible in normal mode.
- [ ] Enabling Developer/Experimental mode reveals the codec selector, capture
      profiles, and verbose metrics.
- [ ] H.264 is labeled experimental/unstable and never auto-starts.
- [ ] Turning Developer mode off resets the codec to MJPEG.

## Security

- [ ] LAN without a token fails (401) on every endpoint except `/health`.
- [ ] LAN with the correct token works.
- [ ] USB mode (127.0.0.1 via adb forward) needs no token.

## Diagnostics

- [ ] Desktop Diagnostics panel shows lens/resolution/FPS, target-vs-actual FPS,
      producer in/out FPS, bandwidth, latency, dropped frames, rotation, and
      last errors.
- [ ] Copy produces a pasteable snapshot; Clear empties the log.

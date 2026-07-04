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

## Orientation (auto-upright content + Auto/Horizontal/Vertical view toggle)

The stream content is always auto-uprighted for the phone's physical
orientation. The Orientation toggle (synced across phone app, web UI, Tauri)
shapes the VIEW: Auto follows the phone; Horizontal/Vertical pin the box.

- [ ] **Auto**, phone vertical: Tauri preview box becomes 9:16 with upright
      full-height video (no giant side bars in the preview).
- [ ] **Auto**, phone horizontal: preview box becomes 16:9, upright full-frame.
- [ ] **Auto**, turning the phone mid-stream: content re-uprights AND the
      preview box follows within ~1-2s, no stream restart.
- [ ] Phone upside down: content still upright.
- [ ] **Horizontal** pinned, phone vertical: 16:9 box, upright video
      pillarboxed (not cropped, not stretched, not sideways).
- [ ] **Vertical** pinned, phone horizontal: 9:16 box, upright video
      letterboxed top/bottom.
- [ ] OBS / virtual camera (always 16:9): vertical video appears upright and
      pillarboxed; horizontal video full-frame.
- [ ] The toggle shows the same value on phone app, phone web UI, and Tauri.
- [ ] Mirror works from both sides and stays in sync.

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

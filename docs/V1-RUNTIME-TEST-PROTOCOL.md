# V1 runtime test protocol

Run these on real hardware and send back the session log file. The desktop app
writes one log per session to:

```
C:\ProgramData\OpenCamBridge\logs\opencambridge-session-YYYYMMDD-HHMMSS.log
```

Open it any time from the desktop app: **Logs** button (top-right) → **Copy** or
**Folder**. Each resolution/FPS change auto-writes a `TEST_START` marker; press
**Mark** in the Logs view to write a `TEST_END` summary after a step settles.

## Setup (do this first, every session)

- Bright room, phone on a stable mount.
- USB cable connected; `adb devices` shows your phone.
- Close OBS, run `.\dev-reset.ps1`, then `.\dev-start.ps1`, THEN open OBS.
- Developer/Experimental mode **OFF**.
- Codec **MJPEG** (H.264 is dev-only, not part of V1 testing unless asked).
- Lens: main/back-wide.
- Quality: 80–85.
- Record phone model + ROM (e.g. "Samsung S24 / OneUI 6", "OnePlus 9 / LineageOS 21").
  The log captures device info automatically, but note it too.

## Durations (so logs are comparable)

- Each capture mode: **60 s** minimum.
- Each rotation state: **15 s**.
- Control-sync check: watch **5–10 s** after each button press.
- LAN mode (if tested): **60 s** per mode.

## Capture matrix (MJPEG, main/back-wide unless noted)

For each: set it in the desktop, let it run the duration, then press **Mark**.

| # | Lens | Resolution | FPS | Duration | Notes |
|---|------|-----------|-----|----------|-------|
| 1 | back-wide | 640x480   | 30 | 60s | |
| 2 | back-wide | 1280x720  | 30 | 60s | baseline |
| 3 | back-wide | 1280x720  | 60 | 60s | key target |
| 4 | back-wide | 1920x1080 | 30 | 60s | |
| 5 | back-wide | 1920x1080 | 60 | 60s | only if 60 is offered |
| 6 | front     | 1280x720  | 30 | 60s | |
| 7 | telephoto/ultrawide | 720p | 30/60 | 60s | only if exposed; 30-only is OK |

If a 60 option is disabled/greyed, that lens can't do 60 at that resolution via
the normal camera API — note it; the log records the capability.

## Orientation

- Orientation control: leave defaults; the video is auto-uprighted.
- Rotate button: cycle 0 → 90 → 180 → 270, **15 s** each. Confirm OBS shows the
  content actually turning (upright at 0, pillarboxed when portrait), not just a
  box change.
- Phone held vertical, then horizontal: content stays upright.
- Mirror on/off: 15 s each.

## Torch

- Torch on/off if the control is visible (only shown on lenses with a flash).
- Switch to a lens without flash: torch control should disappear.

## Control sync

- Change lens / resolution / FPS / quality / mirror / rotate / torch on the
  **phone** → desktop reflects within ~1–2 s.
- Change the same on the **desktop** → phone reflects within ~1 s.
- No control should silently do nothing; failures show a Snackbar (phone) and a
  Diagnostics/log entry (both).

## Security

- LAN with the correct token: works.
- LAN without a token: every endpoint except `/health` must fail (401).
- USB (127.0.0.1 via adb): no token needed.

## Virtual camera

- OBS shows the OpenCamBridge Camera **right-side up** (the MF_MT_DEFAULT_STRIDE
  fix). If it's upside down, note it — do not add an OBS flip transform.

## H.264 (dev only, skip for V1 unless asked)

- Hidden by default. Only if explicitly testing: enable Developer mode, switch
  codec, and note whether `Native:16` decode errors appear (expected on some
  devices) — then switch back to MJPEG.

## After testing

Send the `opencambridge-session-*.log` file. It contains: device info, per-lens
capabilities (incl. high-speed diagnostics), every control change, TEST markers,
10-second metrics summaries (Android FPS, producer in/out FPS, bandwidth,
latency, drops), and any errors/reconnects.

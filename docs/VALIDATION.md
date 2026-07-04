# Manual Validation Checklist

None of the changes on `fable/full-product-hardening` have been run on a
device, in OBS, or through adb. Everything below must be validated by hand
before merging or releasing.

## 0. Builds (do these first)

- [ ] Android: `cd android && .\gradlew.bat assembleDebug`
- [ ] Producer: `cd windows\virtual-camera-mediafoundation\rust-frame-producer && cargo build --release`
      (now compiles openh264 from source: needs a C/C++ compiler in the
      environment; if the build complains about assembly, install `nasm` or
      it should fall back to plain C)
- [ ] Desktop: `cd desktop\tauri-app && npm install && npm run tauri build` (or `npx tsc --noEmit` for a fast type check)

Fix anything that does not compile before continuing.

## 1. Stable MJPEG path (regression check)

- [ ] `.\dev-reset.ps1` then `.\dev-start.ps1` brings up phone + Tauri as before.
- [ ] Desktop connection screen: USB tab -> "Set up USB & Connect" succeeds with a USB-attached, adb-authorized phone.
- [ ] Preview shows live video; Start All brings up host + producer.
- [ ] OBS Video Capture Device "OpenCamBridge Camera" shows video at 1280x720@30 (Custom resolution).
- [ ] Producer no longer reconnects periodically: previously the stream was silently killed by a 30s client timeout; watch producer metrics for >2 minutes and confirm `http_jpeg_fps` never dips to 0.

## 2. Android lifecycle & error honesty

- [ ] Start stream with camera permission revoked mid-session: service must end in ERROR state with `lastError` set (not fake STREAMING).
- [ ] Change resolution/profile while streaming: rebind completes; web UI preview does NOT drop the connection (frames pause then resume).
- [ ] Torch: enable torch, change resolution -> torch comes back on after the rebind.
- [ ] Torch toggle from desktop and from phone UI stay in sync.
- [ ] Set FPS to 15: `/api/camera/status` shows fps=15 and MJPEG output is paced at ~15fps (check producer `http_jpeg_fps`).

## 3. LAN security (critical - these are the new guarantees)

- [ ] Phone in LAN Token mode: `/health` works without token; every other endpoint returns 401 without token (query param AND header both accepted).
- [ ] Wrong token -> 401. Correct 32-char token -> 200.
- [ ] From a LAN client with a valid token, POST `/api/settings` with `{"accessMode":"usbOnly"}`: response must say security settings were ignored, and token must STILL be required on subsequent requests.
- [ ] From the phone UI, switching access mode works, and takes effect after service restart (notification text changes USB/LAN).
- [ ] From a browser on another machine, with token in URL, `/obs?token=...` renders the stream.
- [ ] Desktop LAN tab: connecting without token fails with clear message; with token succeeds; preview, controls, metrics all work.
- [ ] Producer in LAN mode receives `--token` and streams (check with phone in LAN mode + desktop LAN connect + Start All).
- [ ] CORS: from a random website's devtools, `fetch('http://<phone>:8080/api/camera/status')` must be CORS-blocked (no `Access-Control-Allow-Origin`).

## 4. Rotation / mirror on the virtual camera

- [ ] Set Mirror ON in desktop, restart feed: OBS virtual camera image is mirrored (previously only the preview mirrored).
- [ ] Rotate Output 90: OBS image rotates accordingly after pipeline restart.
- [ ] Preview orientation and OBS orientation match in all 4 rotations.

## 5. H.264 experimental path (now end-to-end; validate hard)

- [ ] Phone in h264 stream mode: `ffplay http://127.0.0.1:8080/stream.h264` (over adb forward) plays video. Confirm new subscribers get SPS/PPS (start ffplay AFTER the stream has been running a while).
- [ ] `/api/stream/info` reports codec `h264-annexb`, experimental=true.
- [ ] Desktop: switch Stream Codec to H.264 -> pipeline restarts, producer runs with `--source h264`, and **OBS shows live video** with correct colors (no green/purple tint - that would indicate an encoder input-format negotiation bug on this device) and correct geometry (no diagonal shearing).
- [ ] Producer metrics in h264 mode: `decoded_fps` ≈ camera FPS, `decode_ms_avg` sane (< ~15ms at 720p), `last_error` null after the first keyframe.
- [ ] Kill and restart the Android app mid-h264-stream: producer reconnects and video resumes near-instantly (each new subscriber triggers an immediate IDR request on the encoder).
- [ ] Bitrate slider (desktop) changes quality/bandwidth **live, without the stream restarting** (dynamic MediaCodec.setParameters); keyframe interval change still restarts the pipeline.
- [ ] ffprobe/ffplay the stream and confirm there are no B-frames (has_b_frames=0) — the encoder is configured with max-bframes 0 for latency and openh264 compatibility.
- [ ] Latency feel: wave a hand in front of the phone and compare OBS latency between MJPEG and H.264 modes; H.264 should be comparable or better (event-driven producer writes, no pacing-tick wait).
- [ ] Rotate/mirror in h264 mode affect OBS output the same as in MJPEG mode.
- [ ] Desktop preview in h264 mode shows the "~5 fps snapshot" note and updates slowly (expected; the virtual camera is full rate).
- [ ] Slow-client disconnect: open `/stream.h264` with a paused consumer (e.g. `curl --limit-rate 1k`) and confirm Android logs "Dropping slow H.264 client" instead of OOMing.
- [ ] Encoded size == selected capture size: change resolution while in h264 mode and confirm the stream stays clean (codec is reconfigured after bind now).

## 5b. Multi-lens / device universality

- [ ] Camera dropdown (phone UI, web UI, desktop) lists every lens with a sensible label (wide/ultrawide/telephoto).
- [ ] Selecting an ultrawide or telephoto id actually changes the picture (previously any id other than "1" silently used the main back camera).
- [ ] Selecting a bogus/stale camera id (e.g. settings restored from another phone) falls back to the default camera instead of erroring.
- [ ] `/api/camera/capabilities` returns JSON (it used to 500 - Map serialization bug).
- [ ] FPS 60 on a 30fps-max lens: stream still starts (closest supported range is used).
- [ ] `/api/camera/status` shows `mjpegClients`/`h264Clients` counts matching reality.

## 5c. MJPEG efficiency (new behavior)

- [ ] With no preview and no producer connected (0 clients), phone CPU drops noticeably after ~1s (encoder idles at ~2fps); connecting a client restores full FPS immediately.
- [ ] With FPS limit 15 and a 30fps camera, Android encode work is ~15fps (check `androidEncodeMsAvg` / battery, and producer `http_jpeg_fps` ≈ 15).

## 6. Producer robustness

- [ ] Kill the Android app while producer runs: producer retries with backoff (1,2,4,8s), stderr lines appear as Last Error in desktop UI, and recovery is automatic when the app returns.
- [ ] `--width 3840 --height 2160` exits immediately with the shared-memory capacity error (no crash).
- [ ] Invalid `--source foo` and `--rotate 45` exit with clear errors.
- [ ] `--source test-pattern` still renders the moving gradient in OBS.

## 7. Desktop misc

- [ ] Disconnect button returns to connection screen and clears the token.
- [ ] USB tab works when adb is missing from PATH/SDK (falls back to direct connect attempt with an informative message).
- [ ] OBS fallback (browser source) still works, including in LAN mode (token embedded in /obs URL).

## Known-incomplete (do not expect these to work)

- `register_virtual_camera_backend` from the desktop UI: still directs to the manual installer.
- H.264 is implemented end-to-end but carries an "experimental" label until
  section 5 above passes on at least one real device.

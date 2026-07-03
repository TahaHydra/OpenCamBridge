# Manual Validation Checklist

None of the changes on `fable/full-product-hardening` have been run on a
device, in OBS, or through adb. Everything below must be validated by hand
before merging or releasing.

## 0. Builds (do these first)

- [ ] Android: `cd android && .\gradlew.bat assembleDebug`
- [ ] Producer: `cd windows\virtual-camera-mediafoundation\rust-frame-producer && cargo build --release`
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

## 5. H.264 experimental honesty

- [ ] Phone in h264 stream mode: `ffplay http://127.0.0.1:8080/stream.h264` (over adb forward) plays video. Confirm new subscribers get SPS/PPS (start ffplay AFTER the stream has been running a while).
- [ ] `/api/stream/info` reports codec `h264-annexb`, experimental=true.
- [ ] `rust-frame-producer --source h264 --url ...` connects, prints NAL statistics in metrics, reports `H264_DECODE_NOT_IMPLEMENTED`, and writes NO frames (OBS shows the previous/test-pattern frame, not h264 video).
- [ ] Desktop UI keeps H.264 selector disabled with the honest explanation.
- [ ] Slow-client disconnect: open `/stream.h264` with a paused consumer (e.g. `curl --limit-rate 1k`) and confirm Android logs "Dropping slow H.264 client" instead of OOMing.

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

- H.264 -> virtual camera: not implemented (scaffold only).
- `register_virtual_camera_backend` from the desktop UI: still directs to the manual installer.
- Camera selection beyond front/back binary mapping (cameraId "1" = front, otherwise back) in the streamers.

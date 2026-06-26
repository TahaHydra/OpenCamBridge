# Windows Desktop MVP

The OpenCamBridge Desktop MVP provides a lightweight Windows client to preview the MJPEG stream and control the Android camera server. It is built using the Tauri framework (Rust + React/Vite).

## Supported Features (MVP Phase)
* **Connection Manager:** Auto-connects to the Android server via ADB forward (`http://127.0.0.1:8080`).
* **MJPEG Preview:** Live `<img src=".../stream.mjpeg">` preview with auto-retry and cache busting.
* **Camera Controls:** Start/stop stream, switch cameras, adjust resolution, FPS, JPEG quality, display rotation, fit mode, mirroring, and flashlight.
* **Status Readout:** Displays `LifecycleState` (e.g. STREAMING, ERROR) and active error messages.

## Known Limitations
* **MJPEG Only:** H.264 is strictly marked experimental in the server and is not decoded by the desktop app yet.
* **No Virtual Camera:** The desktop app previews the stream but does not yet expose it as a standard Windows webcam device.
* **ADB Requirement:** Requires a USB connection and manual ADB port forwarding. No automatic LAN discovery yet.

## Future Phases
1. **Virtual Camera:** Integrate DirectShow/Media Foundation to broadcast the feed as a native Windows webcam (e.g. for OBS/Zoom).
2. **H.264 Native Decoder:** Feed the `/stream.h264` byte-stream directly into `FFmpeg` or a Windows hardware decoder for zero-latency preview.
3. **USB Auto-Discovery & Wi-Fi Pairing:** Eliminate the need to manually run `adb forward`.

---

## 🛠 Setup & Run Instructions

### 1. Run Android Server
Install the APK on your phone and start the app.
Ensure your phone is plugged in via USB with USB Debugging enabled.

### 2. Forward the Port (Crucial Step)
You must bridge the Android server to your desktop using ADB:
```powershell
adb forward tcp:8080 tcp:8080
```

### 3. Desktop Install & Run
The desktop app requires **Node.js** and **Rust** installed on your system.

**Dependencies:**
- Node.js & npm
- Rust (`cargo`)
- MSVC Build Tools (for Windows Rust)

If `npm run tauri dev` fails with `cargo metadata: program not found`, you must install Rust via [rustup.rs](https://rustup.rs/).

```powershell
cd desktop/tauri-app
npm install

# Option A: Run purely in the browser (Web UI only, no native shell)
npm run dev

# Option B: Run the native Windows Desktop App (Requires Rust and MSVC Build Tools)
npm run tauri dev
```

## Security & CSP (Tauri v2)
To allow the native Tauri webview to access the Android server over HTTP and display the MJPEG feed, the following Content Security Policy (CSP) is enforced in `tauri.conf.json`:
```json
"csp": "default-src 'self' 'unsafe-inline' 'unsafe-eval' http://127.0.0.1:8080 http://localhost:8080 ws://localhost:1420; img-src 'self' data: blob: http://127.0.0.1:8080 http://localhost:8080; connect-src 'self' http://127.0.0.1:8080 http://localhost:8080 ws://localhost:1420;"
```

## Known Missing UI Features
- The desktop app MVP UI (`ControlPanel.tsx`) currently lacks sliders for **Zoom** and dropdowns for **Camera Lens Selection** that were present in the Android original Web UI. These will be ported over in a future update.

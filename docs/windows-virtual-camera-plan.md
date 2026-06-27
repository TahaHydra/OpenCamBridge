# Windows Virtual Camera Implementation Plan

This document outlines the technical plan for exposing the OpenCamBridge MJPEG stream as a native Windows webcam, allowing it to be used in applications like Microsoft Teams, Zoom, and Discord.

## Implementation Paths

### Path A — OBS Virtual Camera Integration (Recommended for MVP)
Use OBS Studio as the bridge. The user captures the Tauri desktop app window in OBS, and uses the built-in "Start Virtual Camera" feature.
- **Admin Rights:** No (assuming OBS is already installed).
- **Driver Signing:** No (OBS's driver is already signed by the OBS Project).
- **Code Needed:** None on the driver side. Only documentation and possibly an OBS scene JSON template.

### Path B — DirectShow Virtual Camera Filter
Create a custom user-mode `.ax` DirectShow filter that consumes the local HTTP MJPEG stream and exposes it as a DirectShow capture device.
- **Admin Rights:** Yes (requires `regsvr32` to register the COM object in the registry).
- **Driver Signing:** User-mode DirectShow filters do not require strict EV Kernel driver signing, but modern UWP apps (like the Windows 11 Camera app or Teams V2) will silently block unsigned DirectShow filters due to device guard/process mitigation policies.
- **Code Needed:** C++ COM implementation of `IBaseFilter`, `IPin`, and `IAMStreamConfig`.

### Path C — Media Foundation Virtual Camera
The modern Windows 10/11 approach using the Windows Camera Device Driver API or Frame Server.
- **Admin Rights:** Yes.
- **Driver Signing:** Yes, absolutely requires a valid EV Code Signing Certificate and WHQL certification to run on machines with Secure Boot enabled.
- **Code Needed:** Complex C++ Windows Driver Framework (WDF) codebase.

### Path D — Existing Open-Source Virtual Camera Bridges
Tools like `AkVCam` or `UnityCapture`.
- **Admin Rights:** Yes.
- **Driver Signing:** Many open-source tools use test-signing or outdated certificates, meaning users must disable Secure Boot or enable "Test Mode" in Windows, which is unacceptable for end-users.

---

## Conclusion & Recommendation

**Recommended Path for MVP:** **Path A (OBS Virtual Camera integration)**

### 1. Why it is safest
It guarantees immediate compatibility with all modern video conferencing apps (Teams, Discord, Zoom) without requiring the user to disable Secure Boot, purchase a $300/year EV Code Signing certificate, or mess with Windows Registry and Administrator privileges.

### 2. What code would be needed
Almost no code is needed for MVP 1. We simply need to provide an "OBS Mode" button in the Tauri app that strips away all UI overlays (hiding the control panel, borders, and buttons) so the MJPEG feed fills the entire green-screen/black window.

### 3. What dependencies are needed
The user must have OBS Studio installed on their Windows machine.

### 4. Admin rights / Driver signing required?
None required from our application. OBS handles the signed driver bridging.

### 5. How Teams/Discord/Zoom see the camera
They will see a standard webcam device labeled "OBS Virtual Camera".

### 6. What to avoid
Avoid attempting to write a custom DirectShow `.ax` filter right now. While it seems like a "standalone" solution, unsigned DirectShow filters are aggressively blocked by modern Windows apps (like the new React-based Microsoft Teams and Discord). Attempting this route will result in weeks of debugging "black screens" in conferencing apps.

---

## Step-by-Step Implementation Phases

### Phase 1: OBS Integration (Current Target)
1. Add an "OBS Clean Feed" button to the Desktop App.
2. When clicked, hide all React UI elements and make the MJPEG `<img>` 100vw/100vh.
3. Add a visual guide in the app (or a `docs` file) explaining how to add a "Window Capture" in OBS and click "Start Virtual Camera".

### Phase 2: Standalone Evaluation (Future)
1. Research purchasing an EV Code Signing Certificate.
2. Fork an existing modern open-source Media Foundation camera driver (e.g., Microsoft's simple virtual camera samples).
3. Build a Rust/C++ bridge that feeds our MJPEG HTTP stream directly into the Media Foundation pipeline.

---

## Appendix: Using OBS-Assisted Mode

**Limitation Warning:** The current implementation is an "OBS-Assisted Mode", meaning the OpenCamBridge Desktop app does not install a standalone, kernel-level virtual camera driver yet. It bridges via OBS Studio.

### OBS Setup Steps
1. Download and install **OBS Studio**.
2. Open OBS Studio. Go to **Tools > WebSocket Server Settings**.
3. Check **Enable WebSocket server**. Note the **Server Port** (default is `4455`).
4. (Optional but recommended) Set a password and note it down.

### Using the Virtual Camera
1. Open the OpenCamBridge desktop application.
2. Under the "OBS Virtual Camera" section, enter the password you configured (leave blank if authentication is disabled).
3. Click **Start OBS Virtual Camera**. The app will automatically connect to OBS, configure a "Clean Feed" window capture, and start the virtual camera.
4. Open your conferencing app (Microsoft Teams, Zoom, or Discord).
5. In your video settings, select **OBS Virtual Camera** as your webcam device.

### Troubleshooting
- **Password Error:** If you get an authentication error, ensure the password exactly matches what is set in OBS WebSocket settings. If you forget it, click "Show Connect Info" inside OBS.
- **Source Not Capturing:** If the OBS feed is a black screen, open OBS manually, find the `OpenCamBridge Clean Feed` source, double click it, and ensure the Window dropdown is targeting `tauri-app.exe`.

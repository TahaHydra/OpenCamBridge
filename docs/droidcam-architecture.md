# DroidCam Architecture & Windows Virtual Camera Paths

## 1. How DroidCam-Like Architecture Works
DroidCam successfully bridges an Android smartphone to Windows video conferencing apps using a three-part architecture:
1. **Android App (Producer):** Captures frames from CameraX/Camera2 API and serves them over a local TCP socket or USB ADB forward (usually as raw frames or MJPEG).
2. **Windows Desktop App (Consumer & Bridge):** A background service or desktop UI that receives the TCP stream, decodes the frames, and writes the raw RGB/YUV data into a shared memory segment or named pipe.
3. **Windows Virtual Camera Backend (The Driver):** A signed, user-mode DirectShow `.ax` filter (or Media Foundation driver) installed on the system. Apps like Teams or Zoom load this filter. The filter simply reads the raw frames from the shared memory segment and passes them to the requesting application.

## 2. Windows Virtual Camera Paths Evaluated

### Path A — DirectShow Virtual Camera Filter
- **Is it viable?** Yes, but deteriorating rapidly. DirectShow is legacy.
- **Admin Rights:** Yes. Registering a COM `.ax` filter requires `regsvr32` running as Administrator.
- **Signing:** While user-mode DirectShow filters technically do not require EV Kernel-level signing, Microsoft Teams, Discord, and modern UWP apps enforce "Process Mitigation Policies" that aggressively block unsigned DLLs/filters from being loaded into their process space. Unsigned filters result in a black screen or missing camera.
- **Rust/Tauri Feasibility:** You can write a DirectShow filter in C++ that reads from a Named Pipe (which Tauri/Rust writes to), but the signing barrier remains high for open-source distribution.

### Path B — Media Foundation Virtual Camera (Modern Path)
- **Is it viable?** This is the modern, official Microsoft path (Frame Server).
- **Admin Rights:** Yes.
- **Signing:** **Strictly Requires EV Code Signing Certificate and WHQL certification.** Without it, Windows 10/11 Secure Boot will refuse to load the driver, completely locking out casual users.
- **Feasibility:** Extremely difficult for a lightweight open-source project due to the ~$300/yr certificate cost and complex Windows Driver Framework (WDF) requirements.

### Path C — Existing Open-Source Backends
- **AkVCam / UnityCapture / pyvirtualcam:**
  - **Issues:** They all rely on either DirectShow filters or test-signed drivers. Users often have to disable Secure Boot or enable Windows "Test Mode" to use them. They are frequently blocked by modern anti-cheat engines and strict enterprise environments (e.g., corporate laptops running Teams).

### Path D — OBS Virtual Camera Automation (The Realistic Approach)
- **Is it viable?** Highly viable.
- OBS Studio ships with a fully signed, globally recognized, and universally trusted Virtual Camera driver.
- We can bypass the driver-signing nightmare by programmatically controlling OBS via `obs-websocket-js`. We can tell OBS to create a "Window Capture" source targeting our Tauri app, crop it, and automatically click "Start Virtual Camera" on the user's behalf.

## Conclusion
Creating a standalone DirectShow or Media Foundation virtual camera is **impossible** to distribute to end-users without purchasing an expensive EV Code Signing certificate. Microsoft Teams and Discord will silently block unsigned capture filters.

**The most realistic DroidCam-like path for this project is Path D.** By treating OBS as our "free, signed, pre-installed driver," we achieve the exact same user experience (OpenCamBridge appears in Teams) without the catastrophic maintenance overhead of kernel driver signing.

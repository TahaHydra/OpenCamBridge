# ABANDONED EXPERIMENT — NOT CURRENT ARCHITECTURE

Current production architecture is: Android MJPEG -> rust-frame-producer -> ProgramData IPC framebuffer -> Media Foundation VirtualCameraMediaSource -> OpenCamBridge Camera -> OBS/Teams/etc.

# Windows Virtual Camera Prototype

## Objective
The objective was to build or identify the smallest realistic prototype demonstrating how OpenCamBridge can be piped into a Virtual Camera backend that appears in Teams/Discord/Zoom, without tripping Windows 11 Secure Boot or anti-cheat blocks.

## Prototype Implementation: OBS WebSocket Automation
Given the insurmountable driver signing constraints of Media Foundation and DirectShow user-mode filters (which require an EV Certificate to avoid being silently blocked by Microsoft Teams), we pursued **Path D: OBS Virtual Camera Automation**.

We created a tiny proof-of-concept Node.js script (`desktop/tauri-app/obs-auto-start.js`) that acts as a fully automated Virtual Camera backend manager.

### How the Prototype Works
The script utilizes the `obs-websocket` v5 protocol to connect to a running instance of OBS Studio. It proves that the Tauri application can eventually do the following entirely in the background:
1. **Connect:** Securely connect to OBS.
2. **Setup Source:** Send a `CreateInput` JSON payload to dynamically generate a "Window Capture" source locked directly onto the `tauri-app.exe` executable window.
3. **Trigger Driver:** Send a `StartVirtualCam` payload to instantly wake up the fully-signed OBS driver.

### Testing the Prototype
Because OBS Studio ships with a globally trusted driver, automating it means OpenCamBridge inherits that trust. 
- You can review the POC code at `desktop/tauri-app/obs-auto-start.js`.
- If you have OBS running with WebSockets enabled, running `node obs-auto-start.js` will force OBS to capture the app and instantly make it available globally across your operating system as a webcam.

## Next Code Step
The prototype proves OBS automation is the only safe short-term option to get a reliable, universally compatible webcam feed without throwing kernel signing warnings to the user.

**Exact Next Code Step:**
Integrate the logic from `obs-auto-start.js` directly into the Tauri Rust backend. When the user clicks the "Enter OBS Clean Feed" button we added in the previous milestone, Tauri should automatically fire off the WebSocket commands to OBS, making the entire "Window capture -> Start Virtual Camera" process completely invisible and seamless to the end user.

## Phase 1: OBS Integration (Complete)
We proved the integration using OBS WebSocket to orchestrate OBS Studio from the Tauri frontend. Both Window Capture and Browser Source modes were implemented successfully.

## Phase 2: Standalone Prototype (Complete)
We evaluated DirectShow and Media Foundation APIs. To build a rapid, standalone prototype without requiring OBS, we chose to leverage the open-source MIT-licensed `UnityCapture` DirectShow filter.

**Steps Taken:**
1. Downloaded the 32/64-bit UnityCapture `.ax` filter DLLs.
2. Registered the DLL directly into `HKEY_CURRENT_USER\Software\Classes\CLSID` (bypassing UAC Administrator requirements).
3. Rebranded the `FriendlyName` to **OpenCamBridge Camera** natively in the registry.
4. Created a Rust feeder binary using the `virtualcam` crate.
5. Successfully pushed a 1280x720 30FPS gradient video stream via Shared Memory directly into the Windows camera stack.

**Next Steps:**
- Establish an IPC mechanism (e.g., Local Sockets or Shared Memory) between the Tauri app and a Rust daemon.
- Route decoded Android MJPEG frames out to the `virtualcam` backend.


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

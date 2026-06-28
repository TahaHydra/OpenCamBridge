# ABANDONED EXPERIMENT — NOT CURRENT ARCHITECTURE

Current production architecture is: Android MJPEG -> rust-frame-producer -> ProgramData IPC framebuffer -> Media Foundation VirtualCameraMediaSource -> OpenCamBridge Camera -> OBS/Teams/etc.

# Standalone Windows Virtual Camera Architecture

## Overview
This document covers the research and implementation of the standalone virtual camera backend for Windows, allowing OpenCamBridge to appear natively as `OpenCamBridge Camera` without relying on OBS Studio.

## Architecture Selection
We evaluated three paths:
1. **DirectShow (C++ COM)**: The legacy standard. Very difficult to write in pure Rust.
2. **Media Foundation (`MFCreateVirtualCamera`)**: The modern Windows 11 API. Can be done in Rust but requires a massive amount of COM interface boilerplate.
3. **Existing Backend Reuse**: Utilizing an existing open-source DirectShow filter and feeding it frames via Rust Shared Memory.

We chose **Option 3 (Existing Backend Reuse)** using the MIT-licensed `UnityCapture` DirectShow filter. 

## Prototype Implementation
The prototype exists in `windows/virtual-camera-directshow`.

### 1. The Driver
We downloaded the precompiled `UnityCaptureFilter64.dll`. Because `regsvr32` globally registers COM components into `HKLM` (which requires UAC Administrator elevation), we implemented a script to map the CLSID directly into `HKEY_CURRENT_USER`.

### 2. The Rebranding
By injecting the COM keys directly into `HKCU\Software\Classes`, we successfully rebranded the `FriendlyName` of the filter to `OpenCamBridge Camera`. Windows now aggregates this user-level key into `HKEY_CLASSES_ROOT` seamlessly, making it visible to all camera apps.

## Usage via Tauri Desktop App

The easiest way to use the standalone camera is via the OpenCamBridge Desktop app:
1. Open the Desktop App.
2. In the "Native Virtual Camera (Beta)" section, click **Register Camera Backend** if you have not done so already.
3. Click **Start Camera**.
4. The metrics will populate showing FPS, Decode Time, and Latency.
5. Open Windows Camera or a browser meeting (Google Meet) and select **OpenCamBridge Camera**.

*Note: The Tauri app spawns the `rust-feeder.exe` as a child process and reads its metrics via JSON.*

## Usage via CLI (Advanced)

Once registered, you can manually feed frames to it:

### 3. The Rust Feeder
We created a minimal Rust binary (`rust-feeder`) utilizing the `virtualcam` crate.
It connects to the `OpenCamBridge Camera` instance by explicitly targeting its CLSID and Device Name.
It continuously loops, generating a 1280x720 moving color-bar pattern, and pushes the frames into the DirectShow filter via Shared Memory (`UnityCapture_0`).
   * If the decoded image is not exactly 1280x720, use `image::imageops::resize` (Fast Bilinear) to force it.
   * Send the 1280x720x3 frame to `cam.send()`.

## Result & Testing
* **Device Appearance:** `OpenCamBridge Camera` immediately appears in Windows applications (e.g., Windows Camera, Zoom, Discord web).
* **Frames:** The moving gradient is successfully passed from Rust -> Shared Memory -> DirectShow -> Host Application.
* **Blockers:** 
  * Unsigned DirectShow `.ax` DLLs may be rejected by strict Desktop applications (e.g., native Discord desktop or Teams with Signature Mitigation enabled). Browsers and Windows Camera app accept them fine.
  * To ship this professionally, we would either need to EV sign the `.ax` DLL, or transition to the `MFCreateVirtualCamera` API (Windows 11 only) in the future.

## Setup Instructions
To run the prototype:
1. Run `./register.ps1` in the `windows/virtual-camera-directshow` directory to register the DLL in `HKCU`.
2. To test generated frames, run `./run-test-pattern.ps1`.
3. To test live Android streams:
   * Start the Android app.
   * Forward the port: `adb forward tcp:8080 tcp:8080`
   * Run `./run-android-mjpeg.ps1`
4. Open a camera application (Windows Camera, Zoom, browser) and select `OpenCamBridge Camera`.
5. To clean up the registry, run `./unregister.ps1`.

## Exact CLI Commands
The `rust-feeder` uses `clap` for typed arguments.

```powershell
# Test Pattern Mode
cargo run -- --source test-pattern --width 1280 --height 720 --fps 30

# Live MJPEG Mode
cargo run -- --source mjpeg --url http://127.0.0.1:8080/stream.mjpeg
```

## Third-Party Binary Safety
We leverage the MIT-licensed `UnityCapture` DirectShow filter to act as our `OpenCamBridge Camera` device.

* **Source URL:** https://github.com/schellingb/UnityCapture
* **License:** MIT
* **Why included:** Windows requires a native C++ COM DLL to register a DirectShow filter. Building one from scratch in Rust is prohibitively difficult without relying on heavily outdated C++ Microsoft BaseClasses. `UnityCapture` provides a robust, zero-copy shared-memory bridge.
* **Cleanup:** Run `unregister.ps1` to scrub the CLSID keys from `HKCU`.

> **Important:** Why does OBS fallback still exist? Unsigned DirectShow `.ax` DLLs may be rejected by strict Desktop applications (e.g., native Discord desktop or Teams) if Windows Signature Mitigation is active. Browsers and the Windows Camera app accept them without issues. OBS Virtual Camera bypasses this because OBS digitally signs their drivers.


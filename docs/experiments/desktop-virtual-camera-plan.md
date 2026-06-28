# ABANDONED EXPERIMENT — NOT CURRENT ARCHITECTURE

Current production architecture is: Android MJPEG -> rust-frame-producer -> ProgramData IPC framebuffer -> Media Foundation VirtualCameraMediaSource -> OpenCamBridge Camera -> OBS/Teams/etc.

# Virtual Camera Implementation Plan

The current Desktop Companion App is an "MVP 1" build that only provides stream preview, settings control, and connection management. It does **not** expose the phone stream as a native webcam to the Windows operating system (e.g., for use in Zoom, Teams, or Discord).

To achieve Virtual Camera functionality in future milestones, we will employ the following strategies depending on the platform constraints and latency requirements.

## 1. Windows Implementation Options

### A. The DirectShow / Media Foundation Driver (Native approach)
To create a true virtual camera on Windows, we must implement a Windows native camera driver.
- **Technology**: Windows Media Foundation (MF) Virtual Camera or a DirectShow filter.
- **Approach**: The Tauri Rust backend acts as a local server receiving the H.264 or MJPEG stream from the phone. A separate C++ or Rust-based virtual camera driver is registered with the OS. The backend feeds decoded frames via named pipes or shared memory into the virtual driver.
- **Pros**: Highest compatibility with modern UWP apps and traditional desktop apps.
- **Cons**: Requires complex driver signing, C++ integration, and OS-level installations.

### B. The OBS Virtual Camera Bridge (Fallback approach)
Instead of building our own driver, we can leverage the open-source OBS Virtual Camera driver.
- **Approach**: Create a lightweight OBS Plugin or standalone process that talks to the existing OBS Virtual Camera DirectShow filter.
- **Pros**: Avoids driver signing issues.
- **Cons**: Requires users to install OBS or a packaged version of the OBS virtual cam driver.

## 2. Linux Implementation

- **Technology**: `v4l2loopback`
- **Approach**: The Rust backend uses `ffmpeg` or native Rust bindings to pipe the decoded video stream directly to `/dev/videoX` created by the `v4l2loopback` kernel module.
- **Pros**: Standard, well-documented, widely supported.
- **Cons**: Requires users to install the kernel module via their package manager.

## 3. macOS Implementation

- **Technology**: CoreMediaIO Camera Extension
- **Approach**: macOS completely deprecated DAL plug-ins. We must implement a System Extension utilizing `CMIOExtension`. The Rust app will act as the host application communicating with the extension via XPC.
- **Pros**: The only officially supported way forward on Apple Silicon / macOS 12.3+.
- **Cons**: High development overhead, strict Apple code-signing and notarization requirements.

## Next Steps for Milestone 2

1. **Evaluate Latency**: Once H.264 is fully vetted, measure if the Rust -> DirectShow bridge adds unacceptable latency.
2. **Develop Windows PoC**: Start by writing a simple Media Foundation Virtual Camera filter in C++ that receives an RGB frame via shared memory and displays it in Windows Camera.


# OpenCamBridge Desktop Companion

The OpenCamBridge Desktop Companion is a lightweight Windows application built using Tauri v2 and React. It allows you to connect to the OpenCamBridge Android app (via Wi-Fi or USB ADB forward), preview the live stream, and control camera settings directly from your PC.

## Architecture

- **Backend**: Rust + Tauri v2. Handles native USB ADB interactions without heavy Electron overhead.
- **Frontend**: React + TypeScript. Connects to the Android phone's REST API and streams MJPEG video into a native webview window. Bypasses CORS restrictions using Tauri's native HTTP client.
- **Design**: Implements the dark "Stitch" design system.

## Prerequisites

To develop or build this application, you must install the following:

1. **Node.js**: [Download here](https://nodejs.org/) (v18+ recommended)
2. **Rust Toolchain**: [Download here](https://rustup.rs/) (required for the Tauri backend)
3. **C++ Build Tools**: On Windows, install the "Desktop development with C++" workload using the Visual Studio Installer.

*Note: If you do not have Rust installed, `npm run tauri dev` will fail during the backend build step.*

## Running in Dev Mode

Once prerequisites are installed:

1. Install Node dependencies:
   ```bash
   npm install
   ```

2. Run the Tauri development server:
   ```bash
   npm run tauri dev
   ```

## Using USB Connection (Local ADB)

The "Connect via USB" feature expects the Android SDK `adb.exe` to be present at `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` or globally in your `PATH`.
When you click connect, the desktop app automatically runs `adb forward tcp:8080 tcp:8080` to bridge the phone's port to your local machine.

## Intentionally Not Implemented (MVP 1)
- Virtual Camera Output (See `docs/desktop-virtual-camera-plan.md`)
- External cloud calls or telemetry
- H.264 stream decoding in the preview pane (defaults to MJPEG preview)

# OpenCamBridge Media Foundation virtual camera

This directory is derived from Microsoft's Windows Camera VirtualCamera sample and retains its license in `LICENSE`.

The production graph contains only:

- `VirtualCameraMediaSource`: Synthetic/SimpleMediaSource camera DLL reading the validated NV12 ring.
- `VirtualCamera_Installer`: explicit register/unregister/status, Tauri host mode, and native pipeline self-tests.
- `rust-frame-producer`: OCB2/MJPEG decode and NV12 ring producer.

Build from the repository root with `dev-build-vcam.ps1`. The script builds Release/x64 DLL and host directly, runs self-tests, copies the matching DLL beside the host, and verifies binary identities.

The original Microsoft test, manager, UWP, systray, MSI, CameraKs helper, and physical-camera wrapper examples are retained for attribution/reference under `upstream-samples`; they are not part of the OpenCamBridge production solution or build.

See `docs/NATIVE_DEPENDENCY_MAP.md` for exact source ownership and `VirtualCamera_Installer.exe` with no arguments for production command usage.

# Native production dependency map

This map was created from the MSBuild project inclusions before the upstream-sample cleanup. It is the deletion/move gate for native source: a file is not removed until its project inclusion and repository references have been checked.

## Production build entry points

`dev-build-vcam.ps1` builds two Release/x64 projects directly:

- `VirtualCameraMediaSource/VirtualCameraMediaSource.vcxproj` — the in-process Media Foundation camera source DLL loaded by Windows camera consumers.
- `VirtualCamera_Installer/VirtualCamera_Installer.vcxproj` — the production registration, status, self-test, and host executable started by Tauri.

The Rust frame producer and Tauri backend are separate Cargo projects and do not compile C++ source.

## DLL source graph before cleanup

The production DLL project compiled:

- Production OpenCamBridge path: `dllmain.cpp`, `EventHandler.cpp`, `SimpleFrameGenerator.cpp`, `SharedMemoryClient.cpp`, `SimpleMediaSource.cpp`, `SimpleMediaStream.cpp`, `VirtualCameraMediaSourceActivate.cpp`, `winrtCommon.cpp`.
- Inherited Microsoft wrapper examples: `HWMediaSource.cpp`, `HWMediaStream.cpp`, `AugmentedMediaSource.cpp`, `AugmentedMediaStream.cpp`.
- Build support: `pch.cpp`.

Only `VirtualCameraKind::Synthetic` activates the OpenCamBridge `SimpleMediaSource`. The Basic/Augmented branches are inherited physical-camera wrappers and are not registered by the production CLI.

## Installer/host source graph before cleanup

The installer project compiled its own `main.cpp` and `pch.cpp`, but also directly compiled these inherited test-project files:

- `VirtualCameraTest/MediaCaptureUtils.cpp`
- `VirtualCameraTest/MediaSourceUT_Common.cpp`
- `VirtualCameraTest/SimpleMediaSourceUT.cpp`
- `VirtualCameraTest/VCamUtils.cpp`
- `VirtualCameraTest/EVRHelper.cpp`
- `VirtualCameraTest/HWMediaSourceUT.cpp`
- `VirtualCameraTest/AugmentedMediaSourceUT.cpp`

Production register/host/status/unregister operations depended on `SimpleMediaSourceUT` and `VCamUtils`; the other sources existed only for the inherited interactive menu. This coupling is removed by the cleanup: production ownership moves to `VirtualCamera_Installer/ProductionVirtualCamera.*`, and the inherited menu/test sources leave the production project.

## Non-production upstream projects

The original `VirtualCameraSample.sln` also listed `VirtualCameraTest`, `VirtualCameraManager_WinRT`, `VirtualCameraManager_App`, `VirtualCameraSystray`, `VirtualCamera_MSI`, and `Shared/CameraKsPropertyHelper`. None is invoked by `dev-build-vcam.ps1`, Tauri, the producer, or Android. They are retained only as upstream Microsoft reference material and must live under the clearly named `upstream-samples` area if retained.

## Production graph after cleanup

- DLL: Synthetic/SimpleMediaSource files only; no HW/Augmented activation branch or project inclusion.
- Installer/host: `main.cpp`, `ProductionVirtualCamera.cpp`, `pch.cpp`; no source from `VirtualCameraTest`.
- `--dev-menu`: reports that the inherited Microsoft interactive sample was intentionally removed; it never enters sample registration code.
- Production solution: DLL and installer only. Upstream sample code is outside the production solution/build graph with its original license notices preserved.

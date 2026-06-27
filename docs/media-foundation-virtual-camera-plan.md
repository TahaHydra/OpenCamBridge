# Media Foundation Virtual Camera Plan

## Baseline Documentation

### Source Details
- **Source URL:** https://github.com/microsoft/Windows-Camera/tree/master/Samples/VirtualCamera
- **License:** MIT License (as per the `Windows-Camera` repository)

### Requirements
- **Windows Requirement:** Windows 11 (Build 22000 or newer) is required for the `MFCreateVirtualCamera` API.
- **SDK Requirement:** Windows 11 SDK (10.0.22000.0 or newer).
- **Visual Studio Workload Requirement:** Visual Studio 2022 with the "Desktop development with C++" workload. 

### Included Projects (from Sample)
- `VirtualCameraMediaSource`: The core Media Foundation Media Source (the COM object).
- `VirtualCamera_Installer`: Likely used for deployment.
- `VirtualCamera_MSI`: Windows Installer package project.
- `VirtualCameraManager_App`: UWP/WinUI app to manage/test the virtual camera.
- `VirtualCameraManager_WinRT`: WinRT component for management.
- `VirtualCameraSystray`: A system tray utility.
- `VirtualCameraTest`: A test client application to verify functionality.

## Phase 1 Objectives
1. Fetch and build the sample unchanged.
2. Register the camera utilizing `MFVirtualCameraAccess_CurrentUser` first to avoid needing Administrator privileges if possible.
3. Verify that the sample camera appears in apps and displays synthetic frames.

### Phase 1 Baseline Report

```text
Media Foundation baseline report

Build:
- VirtualCameraMediaSource: success
- VirtualCamera_Installer: success
- VirtualCameraTest: success

Registration:
- HKLM CLSID exists: yes
- DLL path: C:\Dev\OpenCamBridge\windows\virtual-camera-mediafoundation\VirtualCamera_Installer\x64\Release\VirtualCameraMediaSource.dll
- DLL exists: yes

Camera creation:
- command/app used: VirtualCamera_Installer.exe < register.txt
- MFCreateVirtualCamera result: Succeeded (000001DB6081B100)
- Start() result: Succeeded!
- HRESULT/error: None (after registering DLL in HKLM)

Windows visibility:
- Get-PnpDevice result: "Windows Virtual Camera Device" under SoftwareDevice
- Browser camera selector result: SWCamMediaSource appears
- OBS result: SWCamMediaSource appears

Frame display:
- synthetic frames visible: yes
- output shown: blue synthetic sample bars
- black/green/error/no camera: no

Files changed:
- task.md
- implementation_plan.md
- VCamUtils.cpp (ignored AddProperty ERROR_ACCESS_DENIED)
- register_hklm.bat
```

## Phase 2 Objectives (Rebrand)
1. Friendly name becomes `OpenCamBridge Camera`.
2. Use unique OpenCamBridge CLSIDs instead of the Microsoft sample ones.
3. Keep HKLM COM registration documented, as the Frame Server (`LOCAL SERVICE`) requires this to instantiate the virtual camera DLL.
4. Verify the rebranded camera still appears in browser/OBS.
5. Verify synthetic frames still display after rebrand.

## Phase 2 Report
- Registration of new CLSID {8CF75B14-3F68-46BC-80DF-5FB86AED931E} successful.
- Old Microsoft sample CLSID successfully unregistered.
- Rebranded camera successfully discovered in OBS as OpenCamBridge Camera with synthetic frames intact.
- **Note**: VirtualCameraTest.exe prints an XML/data-drive warning (C4244 conversion warning during compile or runtime warning), but this is non-blocking and does not prevent OBS/Browser from using the Virtual Camera. It should only be addressed if it blocks future automated tests.

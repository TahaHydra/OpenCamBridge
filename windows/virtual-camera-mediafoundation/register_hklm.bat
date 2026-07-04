@echo off
rem Registers the OpenCamBridge virtual camera COM object using the DLL that
rem lives NEXT TO THIS SCRIPT (%~dp0), so each clone registers its own build.
rem A hardcoded path here once left the camera loading a stale DLL from a
rem different clone no matter what was rebuilt. Run as Administrator.
set DLL=%~dp0VirtualCamera_Installer\x64\Release\VirtualCameraMediaSource.dll
if not exist "%DLL%" (
    echo ERROR: %DLL% not found. Run dev-build-vcam.ps1 first.
    exit /b 1
)
reg add "HKLM\Software\Classes\CLSID\{8CF75B14-3F68-46BC-80DF-5FB86AED931E}" /ve /t REG_SZ /d "OpenCamBridge Camera" /f
reg add "HKLM\Software\Classes\CLSID\{8CF75B14-3F68-46BC-80DF-5FB86AED931E}\InprocServer32" /ve /t REG_SZ /d "%DLL%" /f
reg add "HKLM\Software\Classes\CLSID\{8CF75B14-3F68-46BC-80DF-5FB86AED931E}\InprocServer32" /v "ThreadingModel" /t REG_SZ /d "Both" /f
echo Registered: %DLL%

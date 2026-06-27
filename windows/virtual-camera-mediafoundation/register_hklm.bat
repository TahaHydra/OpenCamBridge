@echo off
reg add "HKLM\Software\Classes\CLSID\{7B89B92E-FE71-42D0-8A41-E137D06EA184}" /ve /t REG_SZ /d "VirtualCameraMediaSource" /f
reg add "HKLM\Software\Classes\CLSID\{7B89B92E-FE71-42D0-8A41-E137D06EA184}\InprocServer32" /ve /t REG_SZ /d "C:\Dev\OpenCamBridge\windows\virtual-camera-mediafoundation\VirtualCamera_Installer\x64\Release\VirtualCameraMediaSource.dll" /f
reg add "HKLM\Software\Classes\CLSID\{7B89B92E-FE71-42D0-8A41-E137D06EA184}\InprocServer32" /v "ThreadingModel" /t REG_SZ /d "Both" /f

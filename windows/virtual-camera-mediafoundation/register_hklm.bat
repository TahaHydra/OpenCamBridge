@echo off
reg add "HKLM\Software\Classes\CLSID\{8CF75B14-3F68-46BC-80DF-5FB86AED931E}" /ve /t REG_SZ /d "OpenCamBridge Camera" /f
reg add "HKLM\Software\Classes\CLSID\{8CF75B14-3F68-46BC-80DF-5FB86AED931E}\InprocServer32" /ve /t REG_SZ /d "C:\Dev\OpenCamBridge\windows\virtual-camera-mediafoundation\VirtualCamera_Installer\x64\Release\VirtualCameraMediaSource.dll" /f
reg add "HKLM\Software\Classes\CLSID\{8CF75B14-3F68-46BC-80DF-5FB86AED931E}\InprocServer32" /v "ThreadingModel" /t REG_SZ /d "Both" /f

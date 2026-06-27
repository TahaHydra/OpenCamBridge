@echo off
echo Removing old Microsoft Sample CLSID...
reg delete "HKLM\Software\Classes\CLSID\{7B89B92E-FE71-42D0-8A41-E137D06EA184}" /f 2>nul

echo Removing new OpenCamBridge Camera CLSID...
reg delete "HKLM\Software\Classes\CLSID\{8CF75B14-3F68-46BC-80DF-5FB86AED931E}" /f 2>nul

echo Done.

Write-Host "=== Build OpenCamBridge Media Foundation DLL ===" -ForegroundColor Cyan

$ErrorActionPreference = "Stop"

$root = "C:\Dev\OpenCamBridge"
$mfRoot = "$root\windows\virtual-camera-mediafoundation"
$vcxproj = "$mfRoot\VirtualCameraMediaSource\VirtualCameraMediaSource.vcxproj"
$builtDll = "$mfRoot\VirtualCameraMediaSource\x64\Release\VirtualCameraMediaSource.dll"
$targetDll = "$mfRoot\VirtualCamera_Installer\x64\Release\VirtualCameraMediaSource.dll"

$vsDevCmd = "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\Common7\Tools\VsDevCmd.bat"

if (!(Test-Path $vsDevCmd)) {
    Write-Host "VsDevCmd not found: $vsDevCmd" -ForegroundColor Red
    exit 1
}

Write-Host "Stopping processes that can lock the DLL..." -ForegroundColor Yellow
Stop-Process -Name obs64 -Force -ErrorAction SilentlyContinue
Stop-Process -Name tauri-app -Force -ErrorAction SilentlyContinue
Stop-Process -Name node -Force -ErrorAction SilentlyContinue
Stop-Process -Name rust-frame-producer -Force -ErrorAction SilentlyContinue
Stop-Process -Name VirtualCamera_Installer -Force -ErrorAction SilentlyContinue
Stop-Process -Name WindowsCamera -Force -ErrorAction SilentlyContinue
Stop-Process -Name Teams -Force -ErrorAction SilentlyContinue
Stop-Process -Name ms-teams -Force -ErrorAction SilentlyContinue
Stop-Process -Name Zoom -Force -ErrorAction SilentlyContinue
Stop-Process -Name Discord -Force -ErrorAction SilentlyContinue
Stop-Service FrameServer -Force -ErrorAction SilentlyContinue
Stop-Service FrameServerMonitor -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2

Write-Host "Finding cppwinrt.exe..." -ForegroundColor Yellow
$cppwinrtExe = Get-ChildItem $mfRoot -Recurse -Filter cppwinrt.exe | Select-Object -First 1

if (!$cppwinrtExe) {
    Write-Host "cppwinrt.exe not found under $mfRoot" -ForegroundColor Red
    exit 1
}

$cppwinrtDir = $cppwinrtExe.Directory.FullName + "\"

Write-Host "CppWinRTPath: $cppwinrtDir" -ForegroundColor Green

Write-Host "Building VirtualCameraMediaSource.vcxproj with v143..." -ForegroundColor Yellow

$cmd = "call `"$vsDevCmd`" -arch=amd64 && msbuild `"$vcxproj`" /p:Configuration=Release /p:Platform=x64 /p:PlatformToolset=v143 /p:CppWinRTPath=`"$cppwinrtDir`""

cmd /c $cmd

if (!(Test-Path $builtDll)) {
    Write-Host "Build finished but DLL not found: $builtDll" -ForegroundColor Red
    exit 1
}

Write-Host "Copying DLL to installer folder..." -ForegroundColor Yellow
try {
    Copy-Item $builtDll $targetDll -Force -ErrorAction Stop
} catch {
    Write-Host "DLL is locked by Windows FrameServer/svchost. Stop FrameServer or reboot, then rerun dev-build-vcam.ps1." -ForegroundColor Red
    exit 1
}

Write-Host "Built DLL:" -ForegroundColor Green
Get-Item $builtDll | Select-Object FullName,Length,LastWriteTime

Write-Host "Installed DLL:" -ForegroundColor Green
Get-Item $targetDll | Select-Object FullName,Length,LastWriteTime

Write-Host "Media Foundation DLL build/copy done." -ForegroundColor Green
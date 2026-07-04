param(
    [switch]$ForceKillApps,
    [switch]$NoKill
)

Write-Host "=== Build OpenCamBridge Media Foundation DLL ===" -ForegroundColor Cyan

$ErrorActionPreference = "Stop"

$root = $PSScriptRoot
$mfRoot = "$root\windows\virtual-camera-mediafoundation"
$vcxproj = "$mfRoot\VirtualCameraMediaSource\VirtualCameraMediaSource.vcxproj"
# Solution-level output dir: the build passes SolutionDir, so OutDir resolves to
# $(SolutionDir)x64\Release\ exactly like a Visual Studio solution build.
$builtDll = "$mfRoot\x64\Release\VirtualCameraMediaSource.dll"
$targetDll = "$mfRoot\VirtualCamera_Installer\x64\Release\VirtualCameraMediaSource.dll"

$vsDevCmd = "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\Common7\Tools\VsDevCmd.bat"

if (!(Test-Path $vsDevCmd)) {
    Write-Host "VsDevCmd not found: $vsDevCmd" -ForegroundColor Red
    exit 1
}

if (!$NoKill) {
Write-Host "Stopping processes that can lock the DLL..." -ForegroundColor Yellow
Stop-Process -Name obs64 -Force -ErrorAction SilentlyContinue
Stop-Process -Name tauri-app -Force -ErrorAction SilentlyContinue
Stop-Process -Name node -Force -ErrorAction SilentlyContinue
Stop-Process -Name rust-frame-producer -Force -ErrorAction SilentlyContinue
Stop-Process -Name VirtualCamera_Installer -Force -ErrorAction SilentlyContinue
Stop-Service FrameServer -Force -ErrorAction SilentlyContinue
Stop-Service FrameServerMonitor -Force -ErrorAction SilentlyContinue

if ($ForceKillApps) {
    Write-Host "Force-killing additional apps..." -ForegroundColor Yellow
    Stop-Process -Name WindowsCamera -Force -ErrorAction SilentlyContinue
    Stop-Process -Name Teams -Force -ErrorAction SilentlyContinue
    Stop-Process -Name ms-teams -Force -ErrorAction SilentlyContinue
    Stop-Process -Name Zoom -Force -ErrorAction SilentlyContinue
    Stop-Process -Name Discord -Force -ErrorAction SilentlyContinue
}
Start-Sleep -Seconds 2
}

# NuGet restore (packages.config). Versions are pinned in packages.config; packages
# land in $mfRoot\packages (see nuget.config repositoryPath). The vcxproj resolves
# them via $(SolutionDir)packages, so every msbuild call below passes SolutionDir
# explicitly ($(SolutionDir) is undefined when building a .vcxproj outside the .sln).
# The doubled trailing backslash keeps cmd/msbuild from eating the closing quote.
$solutionDirArg = "/p:SolutionDir=`"$mfRoot\\`""

[xml]$pkgConfig = Get-Content "$mfRoot\VirtualCameraMediaSource\packages.config"
$cppwinrtVer = ($pkgConfig.packages.package | Where-Object id -eq 'Microsoft.Windows.CppWinRT').version
$wilVer = ($pkgConfig.packages.package | Where-Object id -eq 'Microsoft.Windows.ImplementationLibrary').version
$cppwinrtExe = "$mfRoot\packages\Microsoft.Windows.CppWinRT.$cppwinrtVer\bin\cppwinrt.exe"
$wilTargets = "$mfRoot\packages\Microsoft.Windows.ImplementationLibrary.$wilVer\build\native\Microsoft.Windows.ImplementationLibrary.targets"

if (!(Test-Path $cppwinrtExe) -or !(Test-Path $wilTargets)) {
    Write-Host "Restoring NuGet packages (CppWinRT $cppwinrtVer, WIL $wilVer)..." -ForegroundColor Yellow
    cmd /c "call `"$vsDevCmd`" -arch=amd64 && msbuild `"$vcxproj`" /t:Restore /p:RestorePackagesConfig=true /p:Configuration=Release /p:Platform=x64 $solutionDirArg /v:minimal"
    if (!(Test-Path $cppwinrtExe) -or !(Test-Path $wilTargets)) {
        Write-Host "NuGet restore failed: expected packages under $mfRoot\packages (needs network access on first build)." -ForegroundColor Red
        exit 1
    }
} else {
    Write-Host "NuGet packages already restored (CppWinRT $cppwinrtVer, WIL $wilVer)." -ForegroundColor Green
}

Write-Host "Building VirtualCameraMediaSource.vcxproj with v143..." -ForegroundColor Yellow

$cmd = "call `"$vsDevCmd`" -arch=amd64 && msbuild `"$vcxproj`" /p:Configuration=Release /p:Platform=x64 /p:PlatformToolset=v143 $solutionDirArg"

cmd /c $cmd

if (!(Test-Path $builtDll)) {
    Write-Host "Build finished but DLL not found: $builtDll" -ForegroundColor Red
    exit 1
}

Write-Host "Copying DLL to installer folder..." -ForegroundColor Yellow
New-Item -ItemType Directory -Force (Split-Path $targetDll) | Out-Null
try {
    Copy-Item $builtDll $targetDll -Force -ErrorAction Stop
} catch {
    Write-Host "DLL is locked by Windows FrameServer/svchost or another camera app. Close camera apps or rerun with -ForceKillApps, or reboot." -ForegroundColor Red
    exit 1
}

Write-Host "Built DLL:" -ForegroundColor Green
Get-Item $builtDll | Select-Object FullName,Length,LastWriteTime

Write-Host "Installed DLL:" -ForegroundColor Green
Get-Item $targetDll | Select-Object FullName,Length,LastWriteTime

Write-Host "Media Foundation DLL build/copy done." -ForegroundColor Green
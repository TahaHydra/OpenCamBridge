Write-Host "=== OpenCamBridge DEV START ===" -ForegroundColor Cyan

$ErrorActionPreference = "Continue"

$root = "C:\Dev\OpenCamBridge"
$tauri = "$root\desktop\tauri-app"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

if (!(Test-Path $adb)) {
    Write-Host "ADB not found at: $adb" -ForegroundColor Red
    exit 1
}

Write-Host "Checking ADB devices..."
& $adb devices



Write-Host "Starting Android app..."
& $adb shell am force-stop com.opencambridge.android
Start-Sleep -Milliseconds 500
& $adb shell monkey -p com.opencambridge.android 1

Write-Host "Setting ADB forward 8080..."
& $adb forward --remove-all
& $adb forward tcp:8080 tcp:8080

Write-Host "Waiting for Android control server..."
$healthOk = $false

for ($i = 0; $i -lt 60; $i++) {
    try {
        $res = Invoke-WebRequest http://127.0.0.1:8080/health -UseBasicParsing -TimeoutSec 2
        if ($res.StatusCode -eq 200) {
            $healthOk = $true
            break
        }
    } catch {
        Start-Sleep -Seconds 1
    }
}

if ($healthOk) {
    Write-Host "Android server OK." -ForegroundColor Green
} else {
    Write-Host "ERROR: Android server not reachable. Tauri will NOT start." -ForegroundColor Red
    Write-Host "Unlock phone, keep OpenCamBridge foreground, then run dev-start.ps1 again." -ForegroundColor Yellow
    exit 1
}

Write-Host "Starting Tauri..."
cd $tauri
npm run tauri dev

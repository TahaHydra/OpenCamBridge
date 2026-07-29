param([string]$DeviceSerial)

Write-Host "=== OpenCamBridge DEV START ===" -ForegroundColor Cyan

$ErrorActionPreference = "Continue"

$root = $PSScriptRoot
$tauri = "$root\desktop\tauri-app"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

if (!(Test-Path $adb)) {
    Write-Host "ADB not found at: $adb" -ForegroundColor Red
    exit 1
}

Write-Host "Checking ADB devices..."
& $adb devices
$connected = @(& $adb devices | Select-Object -Skip 1 | ForEach-Object {
    if ($_ -match '^([^\s]+)\s+device$') { $Matches[1] }
} | Where-Object { $_ })
if ([string]::IsNullOrWhiteSpace($DeviceSerial)) {
    if ($connected.Count -gt 1) {
        Write-Host "Several phones are connected. Re-run with -DeviceSerial <serial>." -ForegroundColor Red
        exit 1
    }
    if ($connected.Count -eq 1) { $DeviceSerial = $connected[0] }
}
if ($connected -notcontains $DeviceSerial) {
    Write-Host "Selected phone '$DeviceSerial' is not connected and authorized." -ForegroundColor Red
    exit 1
}
$adbTarget = @('-s', $DeviceSerial)



Write-Host "Starting Android app..."
& $adb @adbTarget shell am force-stop com.opencambridge.android
Start-Sleep -Milliseconds 500
& $adb @adbTarget shell am start -n com.opencambridge.android/.MainActivity

Write-Host "Setting ADB forward 8080..."
# Only remove our own forward; --remove-all would kill forwards owned by other tools.
& $adb @adbTarget forward --remove tcp:8080 2>$null
& $adb @adbTarget forward tcp:8080 tcp:8080

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

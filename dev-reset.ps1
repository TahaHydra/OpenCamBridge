Write-Host "=== OpenCamBridge HARD DEV RESET ===" -ForegroundColor Cyan
$ErrorActionPreference = "SilentlyContinue"

$root = "C:\Dev\OpenCamBridge"
$framebuffer = "C:\ProgramData\OpenCamBridge\framebuffer.bin"
$producerLog = "C:\ProgramData\OpenCamBridge\producer.log"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

Write-Host "Closing OBS / desktop / producer / node..." -ForegroundColor Yellow
Stop-Process -Name obs64 -Force
Stop-Process -Name tauri-app -Force
Stop-Process -Name node -Force
Stop-Process -Name rust-frame-producer -Force
Stop-Process -Name VirtualCamera_Installer -Force
Stop-Process -Name WindowsCamera -Force

Write-Host "Killing any producer by executable path..." -ForegroundColor Yellow
Get-CimInstance Win32_Process | Where-Object {
    $_.CommandLine -like "*rust-frame-producer.exe*" -or
    $_.CommandLine -like "*OpenCamBridge*"
} | ForEach-Object {
    Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
}

Write-Host "Stopping Windows camera frame services..." -ForegroundColor Yellow
Stop-Service FrameServer -Force
Stop-Service FrameServerMonitor -Force

Start-Sleep -Seconds 2

Write-Host "Killing port 1420 if still used..." -ForegroundColor Yellow
Get-NetTCPConnection -LocalPort 1420 -ErrorAction SilentlyContinue | ForEach-Object {
    Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue
}

Write-Host "Removing ADB forwards..." -ForegroundColor Yellow
if (Test-Path $adb) {
    & $adb forward --remove-all
}

Write-Host "Keeping framebuffer.bin to avoid stale Media Foundation mappings..." -ForegroundColor Yellow
Remove-Item $producerLog -Force -ErrorAction SilentlyContinue

Write-Host "Reset done." -ForegroundColor Green
Write-Host "Important: OBS was closed. Reopen OBS only after Tauri + producer are running." -ForegroundColor Cyan
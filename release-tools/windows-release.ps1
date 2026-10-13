param([switch]$BuildNative)
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
Push-Location $repo
try {
    if ($BuildNative) {
        & .\dev-build-vcam.ps1 -NoKill
        if ($LASTEXITCODE -ne 0) { throw 'Native camera build failed' }
        & cargo build --release --locked --manifest-path windows/virtual-camera-mediafoundation/rust-frame-producer/Cargo.toml
        if ($LASTEXITCODE -ne 0) { throw 'Native producer build failed' }
    }
    & "$PSScriptRoot\stage-windows.ps1"
    $stage=Join-Path $repo 'desktop\tauri-app\src-tauri\release-runtime'
    $mf=Join-Path $stage 'windows\virtual-camera-mediafoundation'
    $canonical=Join-Path $mf 'x64\Release'
    & "$PSScriptRoot\sign-windows.ps1" -Files @(
        (Join-Path $mf 'rust-frame-producer\target\release\rust-frame-producer.exe'),
        (Join-Path $canonical 'VirtualCamera_Installer.exe'),
        (Join-Path $canonical 'VirtualCameraMediaSource.dll')
    )
    Get-ChildItem $canonical -File | Copy-Item -Destination (Join-Path $mf 'VirtualCamera_Installer\x64\Release') -Force
    Push-Location (Join-Path $repo 'desktop\tauri-app')
    try {
        & npm.cmd run tauri -- build --config src-tauri/tauri.release.conf.json --bundles msi
        if ($LASTEXITCODE -ne 0) { throw 'Signed MSI build failed' }
    } finally { Pop-Location }
    $config=Get-Content (Join-Path $repo 'desktop\tauri-app\src-tauri\tauri.conf.json') -Raw | ConvertFrom-Json
    $msiName="$($config.productName)_$($config.version)_x64_en-US.msi"
    & "$PSScriptRoot\verify-windows.ps1" -MsiPath (Join-Path $repo "desktop\tauri-app\src-tauri\target\release\bundle\msi\$msiName")
} finally { Pop-Location }

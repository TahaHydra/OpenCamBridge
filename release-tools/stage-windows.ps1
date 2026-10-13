param()
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$stage=Join-Path $repo 'desktop\tauri-app\src-tauri\release-runtime'
$mf='windows\virtual-camera-mediafoundation'
$files=@(
    "$mf\rust-frame-producer\target\release\rust-frame-producer.exe",
    "$mf\x64\Release\VirtualCamera_Installer.exe",
    "$mf\x64\Release\VirtualCameraMediaSource.dll"
)
foreach($relative in $files) {
    $source=Join-Path $repo $relative
    if(!(Test-Path -LiteralPath $source)) { throw "Build native release components first: $relative" }
    $destination=Join-Path $stage $relative
    New-Item -ItemType Directory -Force (Split-Path $destination) | Out-Null
    Copy-Item -LiteralPath $source -Destination $destination -Force
}
# Static CRT is used by the DLL; host API-forwarder DLLs remain app-local.
Get-ChildItem (Join-Path $repo "$mf\x64\Release") -Filter '*_app.dll' | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $stage "$mf\x64\Release") -Force
}
$canonical=Join-Path $stage "$mf\x64\Release"
$installed=Join-Path $stage "$mf\VirtualCamera_Installer\x64\Release"
New-Item -ItemType Directory -Force $installed | Out-Null
Get-ChildItem $canonical -File | Copy-Item -Destination $installed -Force
# The host/producer import MSVCP140/VCRUNTIME140; a clean PC need not have VS.
$redistBase='C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Redist\MSVC'
$redist=Get-ChildItem $redistBase -Directory | Where-Object Name -Match '^\d+\.\d+' | Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (!$redist) { throw 'Microsoft Visual C++ x64 redistributable files are required for staging.' }
$crt=Join-Path $redist.FullName 'x64\Microsoft.VC143.CRT'
foreach($destination in @($stage,$canonical,$installed,(Join-Path $stage "$mf\rust-frame-producer\target\release"))) {
    Get-ChildItem $crt -Filter '*.dll' -File | Copy-Item -Destination $destination -Force
}
foreach($name in @('LICENSE','LICENSES.md')) { Copy-Item -LiteralPath (Join-Path $repo $name) -Destination $stage -Force }
Copy-Item -LiteralPath (Join-Path $repo 'docs\release\user-guide.md') -Destination (Join-Path $stage 'GETTING-STARTED.md') -Force
Copy-Item -LiteralPath (Join-Path $repo 'docs\release\privacy-policy.md') -Destination (Join-Path $stage 'PRIVACY.md') -Force
$notices=Join-Path $repo 'docs\release\third-party'
if (Test-Path -LiteralPath $notices) {
    $noticeDestination=Join-Path $stage 'THIRD-PARTY-NOTICES'
    New-Item -ItemType Directory -Force $noticeDestination | Out-Null
    Get-ChildItem -LiteralPath $notices | Copy-Item -Destination $noticeDestination -Recurse -Force
}
Write-Host "Windows runtime staged at $stage"

param(
    [switch]$ForceKillApps,
    [switch]$NoKill
)

Write-Host "=== Build OpenCamBridge Media Foundation DLL ===" -ForegroundColor Cyan

$ErrorActionPreference = "Stop"

# Some launchers inject both PATH and Path into the Windows environment block.
# MSBuild/CL enumerates that block into a case-insensitive dictionary and then
# fails with MSB6001 before compiling anything. Canonicalize to one Path entry.
$pathLines = & "$env:SystemRoot\System32\cmd.exe" /c "set path" 2>$null
$canonicalPath = ($pathLines | Where-Object { $_ -match '^(?i:path)=' } | Select-Object -Last 1) -replace '^[^=]*=', ''
if ($canonicalPath) {
    [Environment]::SetEnvironmentVariable('PATH', $null, 'Process')
    [Environment]::SetEnvironmentVariable('Path', $null, 'Process')
    [Environment]::SetEnvironmentVariable('Path', $canonicalPath, 'Process')
}

$root = $PSScriptRoot
$mfRoot = "$root\windows\virtual-camera-mediafoundation"
$vcxproj = "$mfRoot\VirtualCameraMediaSource\VirtualCameraMediaSource.vcxproj"
$installerProj = "$mfRoot\VirtualCamera_Installer\VirtualCamera_Installer.vcxproj"
# Solution-level output dir: the build passes SolutionDir, so OutDir resolves to
# $(SolutionDir)x64\Release\ exactly like a Visual Studio solution build.
$builtDll = "$mfRoot\x64\Release\VirtualCameraMediaSource.dll"
$targetDll = "$mfRoot\VirtualCamera_Installer\x64\Release\VirtualCameraMediaSource.dll"
# The installer exe doubles as the virtual camera HOST (--mode host); the
# desktop app launches it from the path below, so build it and put it there.
$builtInstaller = "$mfRoot\x64\Release\VirtualCamera_Installer.exe"
$targetInstaller = "$mfRoot\VirtualCamera_Installer\x64\Release\VirtualCamera_Installer.exe"
$sourceCommit = (& git -C $root rev-parse HEAD).Trim()
$abiSchema = Get-Content "$root\protocol\ring-abi.schema.json" -Raw | ConvertFrom-Json
$abiVersion = $abiSchema.version
& "$root\protocol\generate-ring-abi.ps1" -Check
if ($LASTEXITCODE -ne 0) { throw "Ring ABI generated-file validation failed" }
$generatedAbi = Get-Content "$mfRoot\VirtualCameraMediaSource\RingAbi.generated.h" -Raw
if ($generatedAbi -notmatch '#define OCBR_ABI_HASH (0x[0-9a-f]+)ULL') { throw "Generated ring ABI fingerprint is missing" }
$abiHash = $Matches[1]

Write-Host "Source commit: $sourceCommit" -ForegroundColor Cyan
Write-Host "Ring ABI: version=$abiVersion hash=$abiHash" -ForegroundColor Cyan

$vsDevCmd = "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\Common7\Tools\VsDevCmd.bat"

if (!(Test-Path $vsDevCmd)) {
    Write-Host "VsDevCmd not found: $vsDevCmd" -ForegroundColor Red
    exit 1
}

if (!$NoKill) {
Write-Host "Stopping processes that can lock the DLL..." -ForegroundColor Yellow
Stop-Process -Name obs64 -Force -ErrorAction SilentlyContinue
Stop-Process -Name tauri-app -Force -ErrorAction SilentlyContinue
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
[xml]$instPkgConfig = Get-Content "$mfRoot\VirtualCamera_Installer\packages.config"
$vcrtVer = ($instPkgConfig.packages.package | Where-Object id -eq 'Microsoft.VCRTForwarders.140').version
$cppwinrtExe = "$mfRoot\packages\Microsoft.Windows.CppWinRT.$cppwinrtVer\bin\cppwinrt.exe"
$wilTargets = "$mfRoot\packages\Microsoft.Windows.ImplementationLibrary.$wilVer\build\native\Microsoft.Windows.ImplementationLibrary.targets"
$vcrtTargets = "$mfRoot\packages\Microsoft.VCRTForwarders.140.$vcrtVer\build\native\Microsoft.VCRTForwarders.140.targets"

if (!(Test-Path $cppwinrtExe) -or !(Test-Path $wilTargets) -or !(Test-Path $vcrtTargets)) {
    Write-Host "Restoring NuGet packages (CppWinRT $cppwinrtVer, WIL $wilVer, VCRTForwarders $vcrtVer)..." -ForegroundColor Yellow
    cmd /c "call `"$vsDevCmd`" -arch=amd64 && msbuild `"$vcxproj`" /t:Restore /p:RestorePackagesConfig=true /p:Configuration=Release /p:Platform=x64 $solutionDirArg /v:minimal && msbuild `"$installerProj`" /t:Restore /p:RestorePackagesConfig=true /p:Configuration=Release /p:Platform=x64 $solutionDirArg /v:minimal"
    if ($LASTEXITCODE -ne 0) { throw "NuGet/MSBuild restore failed with exit code $LASTEXITCODE" }
    if (!(Test-Path $cppwinrtExe) -or !(Test-Path $wilTargets) -or !(Test-Path $vcrtTargets)) {
        Write-Host "NuGet restore failed: expected packages under $mfRoot\packages (needs network access on first build)." -ForegroundColor Red
        exit 1
    }
} else {
    Write-Host "NuGet packages already restored (CppWinRT $cppwinrtVer, WIL $wilVer, VCRTForwarders $vcrtVer)." -ForegroundColor Green
}

Write-Host "Building VirtualCameraMediaSource.vcxproj with v143..." -ForegroundColor Yellow

$cmd = "call `"$vsDevCmd`" -arch=amd64 && msbuild `"$vcxproj`" /p:Configuration=Release /p:Platform=x64 /p:PlatformToolset=v143 $solutionDirArg"

cmd /c $cmd
if ($LASTEXITCODE -ne 0) { throw "VirtualCameraMediaSource build failed with exit code $LASTEXITCODE" }

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

Write-Host "Building VirtualCamera_Installer.vcxproj (virtual camera host exe)..." -ForegroundColor Yellow

$cmdHost = "call `"$vsDevCmd`" -arch=amd64 && msbuild `"$installerProj`" /p:Configuration=Release /p:Platform=x64 /p:PlatformToolset=v143 $solutionDirArg"

cmd /c $cmdHost
if ($LASTEXITCODE -ne 0) { throw "VirtualCamera host build failed with exit code $LASTEXITCODE" }

if (!(Test-Path $builtInstaller)) {
    Write-Host "Build finished but host exe not found: $builtInstaller" -ForegroundColor Red
    exit 1
}

try {
    Copy-Item $builtInstaller $targetInstaller -Force -ErrorAction Stop
} catch {
    $builtHash = (Get-FileHash $builtInstaller -Algorithm SHA256).Hash
    $targetHash = if (Test-Path $targetInstaller) { (Get-FileHash $targetInstaller -Algorithm SHA256).Hash } else { "" }
    if ($builtHash -eq $targetHash) {
        Write-Host "Host exe is locked (running) but already up to date; skipping copy." -ForegroundColor Yellow
    } else {
        Write-Host "Host exe is locked (VirtualCamera_Installer still running) and OUTDATED. Close it or rerun without -NoKill." -ForegroundColor Red
        exit 1
    }
}

Write-Host "Built DLL:" -ForegroundColor Green
Get-Item $builtDll | Select-Object FullName,Length,LastWriteTime

Write-Host "Installed DLL:" -ForegroundColor Green
Get-Item $targetDll | Select-Object FullName,Length,LastWriteTime
$builtDllHash = (Get-FileHash $builtDll -Algorithm SHA256).Hash
$installedDllHash = (Get-FileHash $targetDll -Algorithm SHA256).Hash
Write-Host "Built DLL SHA-256:     $builtDllHash" -ForegroundColor Cyan
Write-Host "Installed DLL SHA-256: $installedDllHash" -ForegroundColor Cyan
if ($builtDllHash -ne $installedDllHash) {
    throw "Installed DLL hash does not match the built DLL. Refusing to report a successful camera build."
}

Write-Host "Virtual camera host exe:" -ForegroundColor Green
Get-Item $targetInstaller | Select-Object FullName,Length,LastWriteTime

Write-Host "Running native buffer-lock and NV12 resize fallback tests..." -ForegroundColor Yellow
& $targetInstaller --self-test-pipeline
if ($LASTEXITCODE -ne 0) {
    throw "Virtual-camera native pipeline self-tests failed with exit code $LASTEXITCODE"
}

# The COM registration decides which DLL the Windows FrameServer actually
# loads. If it points at another clone/path, rebuilding here changes nothing
# for the live camera — warn loudly instead of letting that stay silent.
try {
    $regKey = "HKLM:\Software\Classes\CLSID\{8CF75B14-3F68-46BC-80DF-5FB86AED931E}\InprocServer32"
    $registeredDll = (Get-ItemProperty -Path $regKey -ErrorAction Stop).'(default)'
    if ($registeredDll -and ($registeredDll -ne $targetDll)) {
        Write-Host ""
        Write-Host "WARNING: the virtual camera COM registration points at a DIFFERENT DLL:" -ForegroundColor Red
        Write-Host "  registered: $registeredDll" -ForegroundColor Red
        Write-Host "  this build: $targetDll" -ForegroundColor Red
        Write-Host "The camera keeps loading the registered DLL - your rebuild will NOT take effect." -ForegroundColor Red
        throw "Registered/loaded DLL path differs from this build. Run register_hklm.bat as Administrator."
    } elseif ($registeredDll) {
        $registeredHash = (Get-FileHash $registeredDll -Algorithm SHA256).Hash
        Write-Host "Loaded DLL path: $registeredDll" -ForegroundColor Cyan
        Write-Host "Loaded DLL SHA-256: $registeredHash" -ForegroundColor Cyan
        if ($registeredHash -ne $builtDllHash) {
            throw "Registered DLL hash differs from the built DLL."
        }
    }
} catch {
    if ($_.Exception.Message -like "*Cannot find path*" -or $_.Exception.Message -like "*does not exist*") {
        Write-Host "Loaded DLL path: NOT REGISTERED" -ForegroundColor Yellow
        Write-Host "Run windows\virtual-camera-mediafoundation\register_hklm.bat as Administrator once." -ForegroundColor Yellow
    } else {
        throw
    }
}

# Cross-process identity validation. The registry path says what Windows will
# load on the next activation; the live ring says what a currently attached
# camera consumer actually loaded. Never label stale ring bytes as current.
$producerExe = "$mfRoot\rust-frame-producer\target\release\rust-frame-producer.exe"
$producerHash = if (Test-Path $producerExe) { (Get-FileHash $producerExe -Algorithm SHA256).Hash } else { "" }
Write-Host "Producer executable: $producerExe" -ForegroundColor Cyan
Write-Host "Producer SHA-256: $(if ($producerHash) { $producerHash } else { 'NOT BUILT' })" -ForegroundColor $(if ($producerHash) { 'Cyan' } else { 'Yellow' })

$ringPath = "C:\ProgramData\OpenCamBridge\framebuffer.bin"
$runtimeIdentityCurrent = $false
$runtimeDllHash = ""
$runtimeProducerHash = ""
if (Test-Path $ringPath) {
    try {
        $header = New-Object byte[] 256
        $stream = [System.IO.File]::Open($ringPath, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
        try {
            $read = $stream.Read($header, 0, $header.Length)
        } finally {
            $stream.Dispose()
        }
        if ($read -eq 256 -and [BitConverter]::ToUInt32($header, 0) -eq 0x5242434F -and [BitConverter]::ToUInt16($header, 4) -eq $abiVersion) {
            $heartbeat = [BitConverter]::ToInt64($header, 40)
            $heartbeatAge = ([Diagnostics.Stopwatch]::GetTimestamp() - $heartbeat) / [Diagnostics.Stopwatch]::Frequency
            $runtimeIdentityCurrent = $heartbeat -gt 0 -and $heartbeatAge -ge 0 -and $heartbeatAge -lt 2.0
            $runtimeDllHash = -join ($header[160..191] | ForEach-Object { $_.ToString('x2') })
            $runtimeProducerHash = -join ($header[192..223] | ForEach-Object { $_.ToString('x2') })
        }
    } catch {
        Write-Host "Runtime ring identity: UNAVAILABLE ($($_.Exception.Message))" -ForegroundColor Yellow
    }
}

if ($runtimeIdentityCurrent) {
    Write-Host "Loaded DLL SHA-256 (current ring): $runtimeDllHash" -ForegroundColor Cyan
    Write-Host "Running producer SHA-256 (current ring): $runtimeProducerHash" -ForegroundColor Cyan
    $identityErrors = @()
    if ($runtimeDllHash -ne $builtDllHash.ToLowerInvariant()) {
        $identityErrors += "loaded DLL differs from the built/installed DLL"
    }
    if ($producerHash -and $runtimeProducerHash -ne $producerHash.ToLowerInvariant()) {
        $identityErrors += "running producer differs from the release producer executable"
    }
    if ($identityErrors.Count -gt 0) {
        Write-Host "BINARY IDENTITY MISMATCH: $($identityErrors -join '; ')" -ForegroundColor Red
        Write-Host "Remediation: stop camera consumers, run .\dev-build-vcam.ps1, then run the installer --register from an elevated PowerShell." -ForegroundColor Red
        throw "OpenCamBridge built/installed/loaded identity mismatch"
    }
    Write-Host "Producer/built/installed/registered/loaded identities: PASSED" -ForegroundColor Green
} else {
    Write-Host "Loaded DLL/running producer identity: SKIPPED (no current ring heartbeat; not reported as ready)" -ForegroundColor Yellow
}

Write-Host "Media Foundation DLL + host build/copy done." -ForegroundColor Green

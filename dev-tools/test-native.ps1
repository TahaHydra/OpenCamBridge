# Build and exercise native binaries without registering or replacing a camera.
param()
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$mf = Join-Path $repo 'windows\virtual-camera-mediafoundation'
$vs = 'C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\Common7\Tools\VsDevCmd.bat'
# MSBuild's environment is case insensitive. Some launchers supply PATH and Path.
$pathLines = & "$env:SystemRoot\System32\cmd.exe" /c 'set path' 2>$null
$taskPathValue = ($pathLines | Where-Object { $_ -match '^(?i:path)=' } | Select-Object -Last 1) -replace '^[^=]*=', ''
if ($taskPathValue) {
    [Environment]::SetEnvironmentVariable('PATH', $null, 'Process')
    [Environment]::SetEnvironmentVariable('Path', $null, 'Process')
    [Environment]::SetEnvironmentVariable('Path', $taskPathValue, 'Process')
}
& (Join-Path $repo 'protocol\generate-ring-abi.ps1') -Check
if ($LASTEXITCODE -ne 0) { throw 'Ring ABI validation failed' }
$solutionArg = '/p:SolutionDir="' + $mf + '\\"'
foreach ($name in @('VirtualCameraMediaSource', 'VirtualCamera_Installer')) {
    $project = Join-Path $mf "$name\$name.vcxproj"
    & cmd /c "call `"$vs`" -arch=amd64 >nul && msbuild `"$project`" /t:Restore /p:RestorePackagesConfig=true /p:Configuration=Release /p:Platform=x64 $solutionArg /v:minimal && msbuild `"$project`" /p:Configuration=Release /p:Platform=x64 /p:PlatformToolset=v143 $solutionArg /v:minimal"
    if ($LASTEXITCODE -ne 0) { throw "Native build failed: $name" }
}
& (Join-Path $mf 'x64\Release\VirtualCamera_Installer.exe') --self-test-pipeline
if ($LASTEXITCODE -ne 0) { throw 'Native pipeline self-tests failed' }
& (Join-Path $PSScriptRoot 'test-playout.ps1')

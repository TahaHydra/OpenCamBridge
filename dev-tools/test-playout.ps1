param()
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$out = Join-Path $repo 'windows\virtual-camera-mediafoundation\x64\playout-tests'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$vs = 'C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\Common7\Tools\VsDevCmd.bat'
$source = Join-Path $PSScriptRoot 'playout-soak.cpp'
$exe = Join-Path $out 'playout-soak.exe'
$obj = Join-Path $out 'playout-soak.obj'
& cmd /c "call `"$vs`" -arch=amd64 >nul && cl /nologo /std:c++17 /EHsc /O2 /W4 `"$source`" /Fo:`"$obj`" /Fe:`"$exe`""
if ($LASTEXITCODE -ne 0) { throw 'Playout simulation compilation failed' }
& $exe
if ($LASTEXITCODE -ne 0) { throw 'Playout simulation failed' }

$clsid = "{5C2CD55C-92AD-4999-8666-912BD3E70010}"
$dllPath = Join-Path $PSScriptRoot "UnityCaptureFilter64.dll"
if (!(Test-Path $dllPath)) {
    Write-Error "Could not find UnityCaptureFilter64.dll in $PSScriptRoot"
    exit
}
$dllPath = (Resolve-Path $dllPath).Path
$name = "OpenCamBridge Camera"

Write-Host "Registering UnityCapture as '$name' in HKCU..."

New-Item -Path "HKCU:\Software\Classes\CLSID\$clsid" -Force | Out-Null
Set-ItemProperty -Path "HKCU:\Software\Classes\CLSID\$clsid" -Name "(default)" -Value $name

New-Item -Path "HKCU:\Software\Classes\CLSID\$clsid\InprocServer32" -Force | Out-Null
Set-ItemProperty -Path "HKCU:\Software\Classes\CLSID\$clsid\InprocServer32" -Name "(default)" -Value $dllPath
Set-ItemProperty -Path "HKCU:\Software\Classes\CLSID\$clsid\InprocServer32" -Name "ThreadingModel" -Value "Both"

$instancePath = "HKCU:\Software\Classes\CLSID\{860BB310-5D01-11D0-BD3B-00A0C911CE86}\Instance\$clsid"
New-Item -Path $instancePath -Force | Out-Null
Set-ItemProperty -Path $instancePath -Name "FriendlyName" -Value $name
Set-ItemProperty -Path $instancePath -Name "CLSID" -Value $clsid

Write-Host "Registration successful."

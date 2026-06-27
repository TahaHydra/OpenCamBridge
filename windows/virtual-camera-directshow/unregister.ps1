$clsid = "{5C2CD55C-92AD-4999-8666-912BD3E70010}"

Write-Host "Removing OpenCamBridge Camera from HKCU..."

$clsidPath = "HKCU:\Software\Classes\CLSID\$clsid"
if (Test-Path $clsidPath) {
    Remove-Item -Path $clsidPath -Recurse -Force
}

$instancePath = "HKCU:\Software\Classes\CLSID\{860BB310-5D01-11D0-BD3B-00A0C911CE86}\Instance\$clsid"
if (Test-Path $instancePath) {
    Remove-Item -Path $instancePath -Recurse -Force
}

Write-Host "Unregistration successful."

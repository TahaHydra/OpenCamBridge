param(
    [Parameter(Mandatory=$true)][string[]]$Files,
    [string]$Thumbprint = 'F7CF5FB6237A244E81451045FD946452178F9485'
)
$ErrorActionPreference = 'Stop'
$signTool = 'C:\Program Files (x86)\Windows Kits\10\bin\10.0.26100.0\x64\signtool.exe'
$certificate = Get-Item "Cert:\CurrentUser\My\$Thumbprint"
if (!$certificate.HasPrivateKey -or $certificate.NotAfter -le (Get-Date) -or
    !($certificate.EnhancedKeyUsageList | Where-Object { $_.ObjectId -eq '1.3.6.1.5.5.7.3.3' -or $_.ObjectId.Value -eq '1.3.6.1.5.5.7.3.3' })) {
    throw 'A current code-signing certificate with a private key is required.'
}
foreach ($file in $Files) {
    $path = (Resolve-Path -LiteralPath $file).Path
    & $signTool sign /sha1 $Thumbprint /fd SHA256 /tr http://time.certum.pl /td SHA256 /d 'OpenCamBridge' /du https://xhydra.fr/products/opencambridge $path
    if ($LASTEXITCODE -ne 0) { throw "Signing failed: $path. Unlock SimplySign and retry; no unsigned release will be declared signed." }
    & $signTool verify /pa /all /v $path
    if ($LASTEXITCODE -ne 0) { throw "Signature validation failed: $path" }
}

param([Parameter(Mandatory=$true)][string]$MsiPath)
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$msi=(Resolve-Path -LiteralPath $MsiPath).Path
$signature=Get-AuthenticodeSignature -LiteralPath $msi
if($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Thumbprint -ne 'F7CF5FB6237A244E81451045FD946452178F9485') { throw 'MSI signature/identity validation failed' }
$installer=New-Object -ComObject WindowsInstaller.Installer
$database=$installer.OpenDatabase($msi,0)
function Read-Rows([string]$Sql,[int]$Columns) {
    $view=$database.OpenView($Sql)
    $view.Execute() | Out-Null
    $rows=@()
    while($record=$view.Fetch()) { $row=@(); for($index=1;$index -le $Columns;$index++){ $row += $record.StringData($index) }; $rows += [pscustomobject]@{Values=$row} }
    $view.Close() | Out-Null
    return $rows
}
$files=@(Read-Rows 'SELECT `FileName` FROM `File`' 1)
$names=@($files | ForEach-Object { ($_.Values[0] -split '\|')[-1] })
foreach($required in @('tauri-app.exe','rust-frame-producer.exe','VirtualCameraMediaSource.dll','VirtualCamera_Installer.exe','vcruntime140.dll','vcruntime140_1.dll','msvcp140.dll','GETTING-STARTED.md','PRIVACY.md','LICENSE')) {
    if($names -notcontains $required) { throw "MSI payload missing $required" }
}
$shortcuts=@(Read-Rows 'SELECT `Name` FROM `Shortcut`' 1)
if($shortcuts.Count -lt 2) { throw 'Desktop/Start-menu shortcuts are missing' }
$actions=Read-Rows 'SELECT `Action` FROM `CustomAction`' 1
$actionNames=@($actions | ForEach-Object { $_.Values[0] })
foreach($action in @('RegisterOcbCamera','RemoveOcbCamera','AddOcbFirewall','RemoveOcbFirewall','RollbackOcbCamera','RollbackOcbFirewall')) {
    if($actionNames -notcontains $action) { throw "Missing MSI action: $action" }
}
$conditions=Read-Rows 'SELECT `Condition` FROM `LaunchCondition`' 1
if(!($conditions | Where-Object { $_.Values[0] -match 'OCBWINDOWSBUILD' -and $_.Values[0] -match 'OCBPROCESSORARCH' })) { throw 'Windows build/architecture guard is missing' }
Write-Output "MSI_VERIFICATION=PASSED payloadFiles=$($files.Count) shortcuts=$($shortcuts.Count) signer=$($signature.SignerCertificate.Subject)"
Write-Output 'This is package inspection, not clean-PC installation or a hardware compatibility test.'

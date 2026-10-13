param([switch]$CreateUploadKey)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$secrets = Join-Path $repo '.release-secrets'
$key = Join-Path $secrets 'opencambridge-upload.p12'
$passwordFile = Join-Path $secrets 'upload-password.dpapi'
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = 'C:\Users\Hydra\AppData\Local\Android\Sdk'
if ($CreateUploadKey) {
    if (Test-Path $key) { throw 'An upload key already exists. It will never be overwritten.' }
    New-Item -ItemType Directory -Force $secrets | Out-Null
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
    & icacls $secrets /inheritance:r /grant:r "${identity}:(OI)(CI)F" 'SYSTEM:(OI)(CI)F' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot protect signing directory' }
    $randomBytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($randomBytes) } finally { $rng.Dispose() }
    $newPassword = [Convert]::ToBase64String($randomBytes)
    $env:OCB_ANDROID_STORE_PASSWORD = $newPassword
    $env:OCB_ANDROID_KEY_PASSWORD = $newPassword
    $newPassword | ConvertTo-SecureString -AsPlainText -Force | ConvertFrom-SecureString | Set-Content $passwordFile
    & "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -keystore $key -storetype PKCS12 -alias opencambridge-upload -keyalg RSA -keysize 4096 -validity 10000 -dname 'CN=OpenCamBridge Upload, O=xHydra, C=FR' -storepass:env OCB_ANDROID_STORE_PASSWORD -keypass:env OCB_ANDROID_KEY_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw 'Upload key creation failed' }
    $newPassword = $null
}
if (!(Test-Path $key) -or !(Test-Path $passwordFile)) { throw 'Create or provision an upload key first.' }
$secure = (Get-Content $passwordFile -Raw).Trim() | ConvertTo-SecureString
$credential = [Management.Automation.PSCredential]::new('upload', $secure)
$env:OCB_ANDROID_STORE_PASSWORD = $credential.GetNetworkCredential().Password
$env:OCB_ANDROID_KEY_PASSWORD = $env:OCB_ANDROID_STORE_PASSWORD
$env:OCB_ANDROID_KEYSTORE = $key
$env:OCB_ANDROID_KEY_ALIAS = 'opencambridge-upload'
try {
    Push-Location (Join-Path $repo 'android')
    try {
        & .\gradlew.bat :app:testDebugUnitTest :app:assembleRelease :app:bundleRelease :app:lintRelease --no-daemon
        if ($LASTEXITCODE -ne 0) { throw 'Android release build/checks failed' }
    } finally { Pop-Location }
    & "$env:JAVA_HOME\bin\keytool.exe" -exportcert -rfc -keystore $key -alias opencambridge-upload -storepass:env OCB_ANDROID_STORE_PASSWORD -file (Join-Path $secrets 'upload-certificate.pem')
    if ($LASTEXITCODE -ne 0) { throw 'Upload certificate export failed' }
    Write-Host 'Android release APK/AAB built. Upload key and DPAPI password are in .release-secrets (never commit).'
} finally {
    Remove-Item Env:OCB_ANDROID_STORE_PASSWORD,Env:OCB_ANDROID_KEY_PASSWORD,Env:OCB_ANDROID_KEYSTORE,Env:OCB_ANDROID_KEY_ALIAS -ErrorAction SilentlyContinue
}

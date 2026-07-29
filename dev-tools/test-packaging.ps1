param(
    [string]$InstallerPath = "$PSScriptRoot\..\windows\virtual-camera-mediafoundation\VirtualCamera_Installer\x64\Release\VirtualCamera_Installer.exe"
)

$ErrorActionPreference = "Stop"
$repositoryRoot = (Resolve-Path "$PSScriptRoot\..").Path
$InstallerPath = [IO.Path]::GetFullPath($InstallerPath)
$resetScript = Join-Path $repositoryRoot "dev-reset.ps1"
$failures = [Collections.Generic.List[string]]::new()

function Invoke-CheckedProcess {
    param(
        [string]$Name,
        [string]$Executable,
        [string[]]$Arguments,
        [int]$ExpectedExit,
        [string[]]$RequiredText = @(),
        [string[]]$ForbiddenText = @()
    )
    # Windows PowerShell promotes native stderr to a terminating
    # NativeCommandError under Stop. Capture it as test output while keeping
    # fail-fast behavior for the rest of this harness.
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = (& $Executable @Arguments 2>&1 | Out-String)
        $actualExit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    $problem = @()
    if ($actualExit -ne $ExpectedExit) {
        $problem += "exit $actualExit (expected $ExpectedExit)"
    }
    foreach ($text in $RequiredText) {
        if ($output -notmatch [regex]::Escape($text)) { $problem += "missing '$text'" }
    }
    foreach ($text in $ForbiddenText) {
        if ($output -match [regex]::Escape($text)) { $problem += "unexpected '$text'" }
    }
    if ($problem.Count -gt 0) {
        $failures.Add("$Name`: $($problem -join '; ')")
        Write-Host "[FAILED] $Name - $($problem -join '; ')" -ForegroundColor Red
        Write-Host $output
    } else {
        Write-Host "[PASSED] $Name" -ForegroundColor Green
    }
}

if (!(Test-Path -LiteralPath $InstallerPath)) {
    throw "Installer not found: $InstallerPath. Run .\dev-build-vcam.ps1 first."
}

Invoke-CheckedProcess "no arguments prints production usage" $InstallerPath @() 0 @("OpenCamBridge Virtual Camera Installer", "--register")
Invoke-CheckedProcess "invalid arguments" $InstallerPath @("--unknown") 2 @("invalid OpenCamBridge installer arguments")
Invoke-CheckedProcess "status is read-only" $InstallerPath @("--status") 0 @("OpenCamBridge virtual-camera status", "cameraIdentity=Synthetic/SimpleMediaSource")
Invoke-CheckedProcess "development menu is explicitly gated" $InstallerPath @("--dev-menu") 0 @("interactive sample menu was removed")
Invoke-CheckedProcess "native pipeline and CLI self-tests" $InstallerPath @("--self-test-pipeline") 0 @("OCB_BUFFER_LOCK_FALLBACK_TEST=PASSED", "OCB_NV12_RESIZE_FALLBACK_TEST=PASSED", "OCB_INSTALLER_CLI_TEST=PASSED")

$powershell = (Get-Process -Id $PID).Path
Invoke-CheckedProcess "reset success exit policy" $powershell @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $resetScript, "-SelfTestScenario", "success") 0 @("Reset done.") @("Reset FAILED")
Invoke-CheckedProcess "reset essential-failure exit policy" $powershell @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $resetScript, "-SelfTestScenario", "essential-failure") 1 @("Reset FAILED", "Simulated essential step") @("Reset done.")

if ($failures.Count -gt 0) {
    Write-Host "Packaging policy tests FAILED ($($failures.Count))." -ForegroundColor Red
    foreach ($failure in $failures) { Write-Host "  - $failure" -ForegroundColor Red }
    exit 1
}

Write-Host "Packaging policy tests PASSED." -ForegroundColor Green
exit 0

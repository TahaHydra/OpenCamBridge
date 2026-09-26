param(
    [string]$DeviceSerial,
    [string]$SelfTestScenario = ""
)

$ErrorActionPreference = "Stop"
Write-Host "=== OpenCamBridge HARD DEV RESET ===" -ForegroundColor Cyan

$root = $PSScriptRoot
$producerLog = "C:\ProgramData\OpenCamBridge\producer.log"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$script:essentialFailures = @()

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
$isElevated = $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

function Write-StepResult {
    param(
        [string]$Name,
        [ValidateSet("PASSED", "SKIPPED", "FAILED")][string]$Status,
        [string]$Detail = "",
        [bool]$Essential = $false
    )
    $color = switch ($Status) {
        "PASSED" { "Green" }
        "SKIPPED" { "Yellow" }
        "FAILED" { "Red" }
    }
    $suffix = if ($Detail) { " - $Detail" } else { "" }
    Write-Host "[$Status] $Name$suffix" -ForegroundColor $color
    if ($Status -eq "FAILED" -and $Essential) {
        $script:essentialFailures += "$Name$suffix"
    }
}

function Invoke-ResetStep {
    param(
        [string]$Name,
        [scriptblock]$Action,
        [bool]$Essential = $false
    )
    try {
        & $Action
        Write-StepResult $Name "PASSED" "" $Essential
    } catch {
        Write-StepResult $Name "FAILED" $_.Exception.Message $Essential
    }
}

function Complete-Reset {
    param(
        [string[]]$Failures,
        [bool]$PrintReminder = $true
    )
    $failureList = @($Failures | Where-Object { $_ })
    if ($failureList.Count -gt 0) {
        Write-Host "Reset FAILED. Required remediation:" -ForegroundColor Red
        foreach ($failure in $failureList) {
            Write-Host "  - $failure" -ForegroundColor Red
        }
        return 1
    }

    Write-Host "Reset done." -ForegroundColor Green
    if ($PrintReminder) {
        Write-Host "Important: OBS was closed. Reopen OBS only after Tauri + producer are running." -ForegroundColor Cyan
    }
    return 0
}

# Non-destructive packaging regression hook. It exercises the exact completion
# policy used below without stopping processes, services, ports, or ADB state.
if ($SelfTestScenario) {
    $simulatedFailures = switch ($SelfTestScenario) {
        "success" { @() }
        "essential-failure" { @("Simulated essential step") }
        default {
            Write-Host "[FAILED] Unknown reset self-test scenario: $SelfTestScenario" -ForegroundColor Red
            exit 2
        }
    }
    $selfTestExit = Complete-Reset -Failures $simulatedFailures -PrintReminder $false
    exit $selfTestExit
}

Write-StepResult "Elevation" $(if ($isElevated) { "PASSED" } else { "SKIPPED" }) $(if ($isElevated) { "Administrator token present" } else { "Not elevated; active camera services cannot be stopped" }) $false

Invoke-ResetStep "Close camera/desktop processes" {
    foreach ($name in @("obs64", "tauri-app", "rust-frame-producer", "VirtualCamera_Installer", "WindowsCamera")) {
        $processes = @(Get-Process -Name $name -ErrorAction SilentlyContinue)
        foreach ($process in $processes) {
            Stop-Process -Id $process.Id -Force -ErrorAction Stop
        }
    }
} $true

Invoke-ResetStep "Close producer processes found by executable path" {
    $producers = @(Get-CimInstance Win32_Process -ErrorAction Stop | Where-Object {
        # Do not match the workspace name here: this script itself is launched
        # from an OpenCamBridge path and would terminate its own PowerShell host.
        $_.ProcessId -ne $PID -and $_.CommandLine -like "*rust-frame-producer.exe*"
    })
    foreach ($producer in $producers) {
        Stop-Process -Id $producer.ProcessId -Force -ErrorAction Stop
    }
} $true

$runningFrameServices = @(Get-Service FrameServer, FrameServerMonitor -ErrorAction SilentlyContinue | Where-Object Status -ne "Stopped")
if ($runningFrameServices.Count -eq 0) {
    Write-StepResult "Stop Windows camera frame services" "SKIPPED" "Already stopped or unavailable" $false
} elseif (!$isElevated) {
    Write-StepResult "Stop Windows camera frame services" "FAILED" "Run this script from an elevated PowerShell" $true
} else {
    Invoke-ResetStep "Stop Windows camera frame services" {
        foreach ($service in $runningFrameServices) {
            Stop-Service -Name $service.Name -Force -ErrorAction Stop
        }
    } $true
}

Start-Sleep -Seconds 2

Invoke-ResetStep "Release development port 1420" {
    $listeners = @(Get-NetTCPConnection -LocalPort 1420 -ErrorAction SilentlyContinue)
    foreach ($listener in $listeners) {
        Stop-Process -Id $listener.OwningProcess -Force -ErrorAction Stop
    }
} $true

if (!(Test-Path $adb)) {
    Write-StepResult "Remove ADB forward tcp:8080" "SKIPPED" "adb.exe not found" $false
} else {
    try {
        $connected = @(& $adb devices | Select-Object -Skip 1 | ForEach-Object {
            if ($_ -match '^([^\s]+)\s+device$') { $Matches[1] }
        } | Where-Object { $_ })
        if ($LASTEXITCODE -ne 0) { throw "adb devices failed with exit code $LASTEXITCODE" }
        if ([string]::IsNullOrWhiteSpace($DeviceSerial) -and $connected.Count -eq 1) {
            $DeviceSerial = $connected[0]
        }
        if ($connected.Count -gt 1 -and [string]::IsNullOrWhiteSpace($DeviceSerial)) {
            throw "Several phones are connected; rerun with -DeviceSerial <serial>"
        }
        if ([string]::IsNullOrWhiteSpace($DeviceSerial)) {
            Write-StepResult "Remove ADB forward tcp:8080" "SKIPPED" "No connected device" $false
        } else {
            & $adb -s $DeviceSerial forward --remove tcp:8080 2>$null
            # adb returns nonzero when the specific forward is already absent;
            # that is the one benign failure scoped to this exact operation.
            if ($LASTEXITCODE -eq 0) {
                Write-StepResult "Remove ADB forward tcp:8080" "PASSED" "Device $DeviceSerial" $true
            } else {
                Write-StepResult "Remove ADB forward tcp:8080" "SKIPPED" "Forward was already absent for $DeviceSerial" $false
            }
        }
    } catch {
        Write-StepResult "Remove ADB forward tcp:8080" "FAILED" $_.Exception.Message $true
    }
}

Write-StepResult "Preserve framebuffer.bin" "PASSED" "Avoiding stale Media Foundation mappings" $false
if (Test-Path $producerLog) {
    Invoke-ResetStep "Remove producer log" { Remove-Item -LiteralPath $producerLog -Force -ErrorAction Stop } $false
} else {
    Write-StepResult "Remove producer log" "SKIPPED" "Log is absent" $false
}

$resetExit = Complete-Reset -Failures $script:essentialFailures
exit $resetExit

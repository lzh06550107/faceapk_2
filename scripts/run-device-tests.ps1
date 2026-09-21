[CmdletBinding()]
param(
    [string]$Serial,
    [ValidateRange(60, 600)]
    [int]$InstrumentationTimeoutSeconds = 180,
    [ValidateRange(5, 60)]
    [int]$MaintenanceReadyTimeoutSeconds = 15,
    [string]$PlatformPk8,
    [string]$PlatformX509Pem,
    [string]$ApkSigner,
    [switch]$UseDeviceOwnerMaintenanceBridge,
    [switch]$StopProductionAppForSmoke
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "lib\DeviceTestCommon.ps1")

$projectRoot = Resolve-ProjectRoot -ScriptsDirectory $PSScriptRoot
$reportDirectory = New-TestReportDirectory -ProjectRoot $projectRoot -Prefix "device-tests"
$summaryPath = Join-Path $reportDirectory "summary.txt"
$status = "FAIL"
$failureReason = "Unknown failure"
$resolvedSerial = $null

try {
    Write-Host "========================================"
    Write-Host " Punch App Device Test Harness V1.4"
    Write-Host "========================================"

    Assert-CommandAvailable -Name "adb"
    $resolvedSerial = Resolve-AndroidSerial -RequestedSerial $Serial
    Write-DeviceInfo -Serial $resolvedSerial -OutputPath (Join-Path $reportDirectory "device-info.txt")
    Assert-LocalBuildEnvironment -ProjectRoot $projectRoot

    Write-Host "[1/2] JVM unit tests"
    $unitExit = Invoke-LoggedCommand -FilePath (Join-Path $projectRoot "gradlew.bat") `
        -Arguments @(":app:testDebugUnitTest", "--stacktrace") `
        -LogPath (Join-Path $reportDirectory "gradle-unit-test.log") `
        -WorkingDirectory $projectRoot
    if ($unitExit -ne 0) {
        throw "JVM unit tests failed with exit code $unitExit."
    }

    Write-Host "[2/2] Real-device UI smoke"
    $smokeScript = Join-Path $PSScriptRoot "run-ui-smoke.ps1"
    $smokeArguments = @(
        "-Serial", $resolvedSerial,
        "-ReportDirectory", $reportDirectory,
        "-InstrumentationTimeoutSeconds", $InstrumentationTimeoutSeconds.ToString(),
        "-MaintenanceReadyTimeoutSeconds", $MaintenanceReadyTimeoutSeconds.ToString()
    )
    if (-not [string]::IsNullOrWhiteSpace($PlatformPk8)) {
        $smokeArguments += @("-PlatformPk8", $PlatformPk8)
    }
    if (-not [string]::IsNullOrWhiteSpace($PlatformX509Pem)) {
        $smokeArguments += @("-PlatformX509Pem", $PlatformX509Pem)
    }
    if (-not [string]::IsNullOrWhiteSpace($ApkSigner)) {
        $smokeArguments += @("-ApkSigner", $ApkSigner)
    }
    if ($UseDeviceOwnerMaintenanceBridge) {
        $smokeArguments += "-UseDeviceOwnerMaintenanceBridge"
    }
    if ($StopProductionAppForSmoke) {
        $smokeArguments += "-StopProductionAppForSmoke"
    }
    $smokeExit = Invoke-ChildPowerShellScript -ScriptPath $smokeScript `
        -Arguments $smokeArguments `
        -LogPath (Join-Path $reportDirectory "ui-smoke-runner.log")
    if ($smokeExit -ne 0) {
        throw "Real-device UI smoke failed with exit code $smokeExit."
    }

    $status = "PASS"
    $failureReason = ""
}
catch {
    $failureReason = $_.Exception.Message
    Write-Host "[FAIL] $failureReason" -ForegroundColor Red
}
finally {
    @(
        "Punch App Device Test Harness V1.4",
        "status=$status",
        "serial=$resolvedSerial",
        "instrumentation_timeout_seconds=$InstrumentationTimeoutSeconds",
        "maintenance_ready_timeout_seconds=$MaintenanceReadyTimeoutSeconds",
        "use_device_owner_maintenance_bridge=$UseDeviceOwnerMaintenanceBridge",
        "platform_signing_requested=$(-not [string]::IsNullOrWhiteSpace($PlatformPk8))",
        "stop_production_app_for_smoke=$StopProductionAppForSmoke",
        "report_directory=$reportDirectory",
        "finished_at=$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')",
        "failure_reason=$failureReason"
    ) | Set-Content -Path $summaryPath -Encoding UTF8
}

if ($status -eq "PASS") {
    Write-Host "----------------------------------------"
    Write-Host "RESULT: PASS" -ForegroundColor Green
    Write-Host "Report: $reportDirectory"
    exit 0
}

Write-Host "----------------------------------------"
Write-Host "RESULT: FAIL" -ForegroundColor Red
Write-Host "Reason: $failureReason"
Write-Host "Report: $reportDirectory"
exit 1

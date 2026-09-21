[CmdletBinding()]
param(
    [string]$Serial,
    [string]$ReportDirectory,
    [string]$PackageName = "com.punch.app.smoke",
    [string]$ProductionPackageName = "com.punch.app",
    [string]$InstrumentationTarget = "com.punch.app.smoke.test/com.punch.app.test.UiSmokeTestRunner",
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
if (-not $ReportDirectory) {
    $ReportDirectory = New-TestReportDirectory -ProjectRoot $projectRoot -Prefix "ui-smoke"
}
else {
    New-Item -ItemType Directory -Path $ReportDirectory -Force | Out-Null
    $ReportDirectory = (Resolve-Path $ReportDirectory).Path
}

if ($StopProductionAppForSmoke -and -not $UseDeviceOwnerMaintenanceBridge) {
    Write-Host "[WARN] -StopProductionAppForSmoke is deprecated for Device Owner devices; using the V1.4 maintenance bridge instead." -ForegroundColor Yellow
    $UseDeviceOwnerMaintenanceBridge = $true
}

$summaryPath = Join-Path $ReportDirectory "ui-smoke-summary.txt"
$instrumentationPath = Join-Path $ReportDirectory "instrumentation.txt"
$logcatPath = Join-Path $ReportDirectory "logcat.txt"
$fatalEventsPath = Join-Path $ReportDirectory "fatal-events.txt"
$status = "FAIL"
$failureReason = "Unknown failure"
$resolvedSerial = $null
$preflightConflict = $null
$productionBackup = $null
$maintenanceBuildInstalled = $false
$maintenanceEntered = $false
$bridgeUsed = $false
$productionRestoreSucceeded = $false
$smokeTestPackageName = ($InstrumentationTarget -split '/')[0]

function Add-RestoreFailure {
    param([string]$Message)
    if ([string]::IsNullOrWhiteSpace($Message)) {
        return
    }
    if ($script:status -eq "PASS") {
        $script:status = "FAIL"
        $script:failureReason = $Message
    }
    elseif ([string]::IsNullOrWhiteSpace($script:failureReason)) {
        $script:failureReason = $Message
    }
    else {
        $script:failureReason = "$($script:failureReason) | ALSO: $Message"
    }
}

function Get-InstrumentationFailureDetail {
    param(
        [string]$Text,
        [int]$ExitCode = 0
    )

    $safeText = if ($null -eq $Text) { "" } else { [string]$Text }
    $lines = @($safeText -split "`r?`n" | ForEach-Object { $_.Trim() } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    $tailLines = if ($lines.Count -gt 12) { @($lines[($lines.Count - 12)..($lines.Count - 1)]) } else { $lines }
    $tail = if ($tailLines.Count -gt 0) { $tailLines -join " | " } else { "<empty>" }
    if ($tail.Length -gt 1200) {
        $tail = $tail.Substring($tail.Length - 1200)
    }

    $failureLine = $lines | Where-Object { $_ -match 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed|shortMsg=Process crashed|INSTRUMENTATION_STATUS:\s*Error|INSTRUMENTATION_RESULT:\s*shortMsg=|AssertionFailedError|java\.lang\.(?:AssertionError|RuntimeException)' } | Select-Object -First 1
    if ($failureLine) {
        return "failure_marker=$failureLine; instrumentation_tail=$tail"
    }
    if ($ExitCode -ne 0) {
        return "exit_code=$ExitCode; instrumentation_tail=$tail"
    }
    if ($safeText -notmatch '(?m)^OK \(\d+ tests?\)') {
        return "missing_success_marker=OK(test count); instrumentation_tail=$tail"
    }
    return ""
}

try {
    Assert-LocalBuildEnvironment -ProjectRoot $projectRoot
    Assert-CommandAvailable -Name "adb"
    $resolvedSerial = Resolve-AndroidSerial -RequestedSerial $Serial

    Write-Host "[INFO] Device: $resolvedSerial"
    Write-Host "[INFO] Report: $ReportDirectory"
    Write-DeviceInfo -Serial $resolvedSerial -OutputPath (Join-Path $ReportDirectory "device-info.txt")

    # Get-SmokeDeviceConflict persists device-policy.txt and activity-state.txt as preflight evidence.
    $preflightConflict = Get-SmokeDeviceConflict `
        -Serial $resolvedSerial `
        -ProductionPackageName $ProductionPackageName `
        -ReportDirectory $ReportDirectory

    if ($preflightConflict.Conflict -and -not $UseDeviceOwnerMaintenanceBridge) {
        throw "Smoke UI preflight detected an active production managed-device/Kiosk conflict: $($preflightConflict.Reason). Re-run on this dedicated terminal with -UseDeviceOwnerMaintenanceBridge."
    }

    $buildTasks = @(":app:assembleSmoke", ":app:assembleSmokeAndroidTest")
    if ($preflightConflict.Conflict -and $UseDeviceOwnerMaintenanceBridge) {
        $buildTasks += ":app:assembleDeviceOwnerTest"
    }
    $buildTasks += "--stacktrace"

    $buildLog = Join-Path $ReportDirectory "smoke-build.log"
    $buildExit = Invoke-LoggedCommand -FilePath (Join-Path $projectRoot "gradlew.bat") `
        -Arguments $buildTasks `
        -LogPath $buildLog `
        -WorkingDirectory $projectRoot
    if ($buildExit -ne 0) {
        throw "Smoke/device-owner-test APK build failed with exit code $buildExit. See $buildLog"
    }

    $appApk = Get-ChildItem -Path (Join-Path $projectRoot "app\build\outputs\apk\smoke") -Filter "*.apk" -File -ErrorAction Stop |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    $testApk = Get-ChildItem -Path (Join-Path $projectRoot "app\build\outputs\apk\androidTest\smoke") -Filter "*.apk" -File -ErrorAction Stop |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    if (-not $appApk) {
        throw "Smoke app APK was not found after build."
    }
    if (-not $testApk) {
        throw "Smoke androidTest APK was not found after build."
    }

    $deviceOwnerTestApk = $null
    if ($preflightConflict.Conflict -and $UseDeviceOwnerMaintenanceBridge) {
        $deviceOwnerTestApk = Get-ChildItem -Path (Join-Path $projectRoot "app\build\outputs\apk\deviceOwnerTest") -Filter "*.apk" -File -ErrorAction Stop |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
        if (-not $deviceOwnerTestApk) {
            throw "Device Owner maintenance APK was not found after build."
        }

        if ($preflightConflict.IsSystemApp) {
            if ([string]::IsNullOrWhiteSpace($PlatformPk8) -or
                [string]::IsNullOrWhiteSpace($PlatformX509Pem)) {
                throw "Android 12 System App maintenance requires -PlatformPk8 and -PlatformX509Pem so the temporary com.punch.app build can update the platform-signed production package."
            }
            if (-not (Test-Path $PlatformPk8)) {
                throw "Platform key not found: $PlatformPk8"
            }
            if (-not (Test-Path $PlatformX509Pem)) {
                throw "Platform certificate not found: $PlatformX509Pem"
            }

            $signScript = Join-Path $PSScriptRoot "sign-platform-apk.ps1"
            $signedMaintenanceApk = Join-Path $ReportDirectory "device-owner-test-platform.apk"
            $signArgs = @{
                InputApk = $deviceOwnerTestApk.FullName
                PlatformPk8 = $PlatformPk8
                PlatformX509Pem = $PlatformX509Pem
                OutputApk = $signedMaintenanceApk
            }
            if (-not [string]::IsNullOrWhiteSpace($ApkSigner)) {
                $signArgs["ApkSigner"] = $ApkSigner
            }
            & $signScript @signArgs
            if (-not (Test-Path $signedMaintenanceApk)) {
                throw "Platform-signed maintenance APK was not produced: $signedMaintenanceApk"
            }
            $deviceOwnerTestApk = Get-Item $signedMaintenanceApk
            Write-Host "[INFO] System App maintenance APK platform-signed for transactional update."
        }
    }

    Write-Host "[INFO] App APK: $($appApk.FullName)"
    Write-Host "[INFO] Test APK: $($testApk.FullName)"
    if ($deviceOwnerTestApk) {
        Write-Host "[INFO] Device Owner maintenance APK: $($deviceOwnerTestApk.FullName)"
    }

    $clearLogcatExit = Invoke-Adb -Serial $resolvedSerial -Arguments @("logcat", "-c")
    if ($clearLogcatExit -ne 0) {
        throw "Unable to clear logcat on device $resolvedSerial."
    }

    Collect-DeviceSnapshot -Serial $resolvedSerial -PackageName $PackageName -OutputDirectory $ReportDirectory -Prefix "before"

    # Install test packages first. A production Device Owner may immediately suspend
    # them; the maintenance bridge intentionally reverses that policy afterwards.
    $installAppExit = Invoke-Adb -Serial $resolvedSerial `
        -Arguments @("install", "-r", "-t", $appApk.FullName) `
        -LogPath (Join-Path $ReportDirectory "install-app.log")
    if ($installAppExit -ne 0) {
        throw "Smoke app installation failed with exit code $installAppExit."
    }

    $installTestExit = Invoke-Adb -Serial $resolvedSerial `
        -Arguments @("install", "-r", "-t", $testApk.FullName) `
        -LogPath (Join-Path $ReportDirectory "install-test.log")
    if ($installTestExit -ne 0) {
        throw "Smoke test APK installation failed with exit code $installTestExit."
    }

    if ($preflightConflict.Conflict -and $UseDeviceOwnerMaintenanceBridge) {
        $bridgeUsed = $true
        Write-Host "[WARN] Managed-device/Kiosk conflict confirmed: $($preflightConflict.Reason)" -ForegroundColor Yellow
        Write-Host "[INFO] Entering transactional managed-device maintenance mode."

        $backupDirectory = Join-Path $ReportDirectory "production-backup"
        $productionBackup = Backup-InstalledPackageApks `
            -Serial $resolvedSerial `
            -PackageName $ProductionPackageName `
            -BackupDirectory $backupDirectory

        [void](Invoke-Adb -Serial $resolvedSerial -Arguments @("shell", "setprop", "debug.punch.ui_smoke", "1"))
        [void](Invoke-Adb -Serial $resolvedSerial -Arguments @("shell", "setprop", "debug.punch.device_test_maintenance", "1"))

        $maintenanceInstallExit = Invoke-Adb -Serial $resolvedSerial `
            -Arguments @("install", "-r", "-d", $deviceOwnerTestApk.FullName) `
            -LogPath (Join-Path $ReportDirectory "install-device-owner-test.log")
        if ($maintenanceInstallExit -ne 0) {
            throw "Unable to update $ProductionPackageName with the temporary maintenance build (exit $maintenanceInstallExit). The installed production APK and maintenance build must use the same signing certificate."
        }
        $maintenanceBuildInstalled = $true

        $controlComponent = "$ProductionPackageName/.receiver.DeviceOwnerTestControlReceiver"
        $enterExit = Invoke-Adb -Serial $resolvedSerial `
            -Arguments @("shell", "am", "broadcast", "-W", "-n", $controlComponent, "--es", "mode", "enter") `
            -LogPath (Join-Path $ReportDirectory "maintenance-enter.log")
        if ($enterExit -ne 0) {
            throw "Device Owner maintenance control broadcast failed (exit $enterExit)."
        }
        $maintenanceEntered = $true

        Write-Host "[INFO] Waiting up to $MaintenanceReadyTimeoutSeconds seconds for LockTask to exit and Smoke packages to become runnable."
        $maintenanceState = Wait-DeviceOwnerMaintenanceReady `
            -Serial $resolvedSerial `
            -ProductionPackageName $ProductionPackageName `
            -SmokePackageName $PackageName `
            -SmokeTestPackageName $smokeTestPackageName `
            -TimeoutSeconds $MaintenanceReadyTimeoutSeconds `
            -ReportDirectory (Join-Path $ReportDirectory "maintenance-enter")
        if (-not $maintenanceState.Ready) {
            throw "Managed-device maintenance mode did not become test-ready within $MaintenanceReadyTimeoutSeconds seconds. lockTask=$($maintenanceState.IsLockTaskActive), productionForeground=$($maintenanceState.ProductionForeground), productionHomeHeld=$($maintenanceState.ProductionHomeHeld), smokeSuspended=$($maintenanceState.SmokeSuspended), smokeTestSuspended=$($maintenanceState.SmokeTestSuspended). See maintenance-enter\maintenance-state.txt and maintenance-wait.txt."
        }
    }

    Write-Host "[INFO] Starting instrumentation (timeout: $InstrumentationTimeoutSeconds seconds)"
    $instrumentResult = Invoke-AdbWithTimeout -Serial $resolvedSerial `
        -Arguments @("shell", "am", "instrument", "-w", "-r", $InstrumentationTarget) `
        -LogPath $instrumentationPath `
        -TimeoutSeconds $InstrumentationTimeoutSeconds

    if ($instrumentResult.TimedOut) {
        [void](Invoke-Adb -Serial $resolvedSerial -Arguments @("shell", "am", "force-stop", $PackageName))
        throw "Instrumentation timed out after $InstrumentationTimeoutSeconds seconds. See instrumentation.txt and the maintenance/device-policy evidence in this report."
    }

    $instrumentation = if (Test-Path $instrumentationPath) {
        Get-Content -Raw -Path $instrumentationPath
    }
    else {
        ""
    }

    $instrumentationFailureDetail = Get-InstrumentationFailureDetail -Text $instrumentation -ExitCode $instrumentResult.ExitCode
    if (-not [string]::IsNullOrWhiteSpace($instrumentationFailureDetail)) {
        throw "Instrumentation validation failed: $instrumentationFailureDetail"
    }

    $status = "PASS"
    $failureReason = ""
}
catch {
    $failureReason = $_.Exception.Message
    Write-Host "[FAIL] $failureReason" -ForegroundColor Red
}
finally {
    if ($resolvedSerial) {
        try {
            $logcatExit = Invoke-Adb -Serial $resolvedSerial -Arguments @("logcat", "-d", "-v", "threadtime") -LogPath $logcatPath
            if ($logcatExit -ne 0 -and $status -eq "PASS") {
                $status = "FAIL"
                $failureReason = "Unable to capture logcat (exit $logcatExit)."
            }
        }
        catch {
            if ($status -eq "PASS") {
                $status = "FAIL"
                $failureReason = "Unable to capture logcat: $($_.Exception.Message)"
            }
        }

        try {
            Collect-DeviceSnapshot -Serial $resolvedSerial -PackageName $PackageName -OutputDirectory $ReportDirectory -Prefix "after"
        }
        catch {
            if ($status -eq "PASS") {
                $status = "FAIL"
                $failureReason = "Unable to capture after-test metrics: $($_.Exception.Message)"
            }
        }

        try {
            $fatalCount = Find-AppFatalEvents -LogPath $logcatPath -PackageName $PackageName -OutputPath $fatalEventsPath
            if ($fatalCount -gt 0 -and $status -eq "PASS") {
                $status = "FAIL"
                $failureReason = "Detected $fatalCount app-specific crash/ANR/OOM/native-fatal log event(s)."
            }
        }
        catch {
            if ($status -eq "PASS") {
                $status = "FAIL"
                $failureReason = "Fatal-event scan failed: $($_.Exception.Message)"
            }
        }

        if ($maintenanceBuildInstalled) {
            try {
                Write-Host "[INFO] Leaving Device Owner maintenance mode."
                $controlComponent = "$ProductionPackageName/.receiver.DeviceOwnerTestControlReceiver"
                $exitCode = Invoke-Adb -Serial $resolvedSerial `
                    -Arguments @("shell", "am", "broadcast", "-W", "-n", $controlComponent, "--es", "mode", "exit") `
                    -LogPath (Join-Path $ReportDirectory "maintenance-exit.log")
                if ($exitCode -ne 0) {
                    Add-RestoreFailure "Device Owner maintenance exit broadcast failed with exit code $exitCode."
                }
            }
            catch {
                Add-RestoreFailure "Device Owner maintenance exit failed: $($_.Exception.Message)"
            }
        }

        # Always clear debug runtime flags, even if the maintenance build failed
        # before its control activity could start.
        try {
            [void](Invoke-Adb -Serial $resolvedSerial -Arguments @("shell", "setprop", "debug.punch.device_test_maintenance", "0"))
            [void](Invoke-Adb -Serial $resolvedSerial -Arguments @("shell", "setprop", "debug.punch.ui_smoke", "0"))
        }
        catch {
            Add-RestoreFailure "Unable to clear device-test runtime properties: $($_.Exception.Message)"
        }

        if ($productionBackup) {
            try {
                Write-Host "[INFO] Restoring original production APK(s)."
                $restoreExit = Restore-InstalledPackageApks `
                    -Serial $resolvedSerial `
                    -ApkPaths $productionBackup.ApkPaths `
                    -LogPath (Join-Path $ReportDirectory "restore-production-apks.log")
                if ($restoreExit -ne 0) {
                    Add-RestoreFailure "Restoring original production APK(s) failed with exit code $restoreExit. Backup remains in production-backup."
                }
                elseif (-not $bridgeUsed) {
                    $productionRestoreSucceeded = $true
                }
            }
            catch {
                Add-RestoreFailure "Restoring original production APK(s) failed: $($_.Exception.Message). Backup remains in production-backup."
            }
        }

        if ($bridgeUsed) {
            try {
                Write-Host "[INFO] Restoring production Kiosk entry: $ProductionPackageName"
                $component = "$ProductionPackageName/.activity.KioskHomeActivity"
                $restoreKioskExit = Invoke-Adb -Serial $resolvedSerial `
                    -Arguments @("shell", "am", "start", "-n", $component) `
                    -LogPath (Join-Path $ReportDirectory "restore-production-kiosk.log")
                if ($restoreKioskExit -ne 0) {
                    Add-RestoreFailure "Restoring production Kiosk entry failed with exit code $restoreKioskExit."
                }
                else {
                    $productionRestoreSucceeded = $true
                }
                Start-Sleep -Seconds 2
            }
            catch {
                Add-RestoreFailure "Restoring production Kiosk entry failed: $($_.Exception.Message)"
            }
        }

        try {
            $afterStateDirectory = Join-Path $ReportDirectory "after-test-state"
            [void](Get-SmokeDeviceConflict `
                -Serial $resolvedSerial `
                -ProductionPackageName $ProductionPackageName `
                -ReportDirectory $afterStateDirectory)
        }
        catch {
            if ($status -eq "PASS") {
                $status = "FAIL"
                $failureReason = "Unable to capture final Device Owner/Kiosk state: $($_.Exception.Message)"
            }
        }
    }

    @(
        "Punch App UI Smoke Test V1.4",
        "status=$status",
        "serial=$resolvedSerial",
        "package=$PackageName",
        "production_package=$ProductionPackageName",
        "instrumentation=$InstrumentationTarget",
        "instrumentation_timeout_seconds=$InstrumentationTimeoutSeconds",
        "maintenance_ready_timeout_seconds=$MaintenanceReadyTimeoutSeconds",
        "use_device_owner_maintenance_bridge=$UseDeviceOwnerMaintenanceBridge",
        "legacy_stop_production_switch=$StopProductionAppForSmoke",
        "bridge_used=$bridgeUsed",
        "maintenance_build_installed=$maintenanceBuildInstalled",
        "maintenance_entered=$maintenanceEntered",
        "preflight_system_app=$($null -ne $preflightConflict -and $preflightConflict.IsSystemApp)",
        "platform_maintenance_signing=$($null -ne $preflightConflict -and $preflightConflict.IsSystemApp -and -not [string]::IsNullOrWhiteSpace($PlatformPk8))",
        "production_backup_available=$($null -ne $productionBackup)",
        "production_restore_succeeded=$productionRestoreSucceeded",
        "report_directory=$ReportDirectory",
        "finished_at=$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')",
        "failure_reason=$failureReason"
    ) | Set-Content -Path $summaryPath -Encoding UTF8
}

if ($status -eq "PASS") {
    Write-Host "[PASS] UI smoke tests passed." -ForegroundColor Green
    Write-Host "[INFO] Report: $ReportDirectory"
    exit 0
}

Write-Host "[FAIL] UI smoke tests failed: $failureReason" -ForegroundColor Red
Write-Host "[INFO] Report: $ReportDirectory"
exit 1

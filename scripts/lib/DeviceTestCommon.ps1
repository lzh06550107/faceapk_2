Set-StrictMode -Version Latest

function Resolve-ProjectRoot {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ScriptsDirectory
    )

    $root = Join-Path $ScriptsDirectory ".."
    return (Resolve-Path $root).Path
}

function New-TestReportDirectory {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ProjectRoot,

        [string]$Prefix = "device"
    )

    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $baseDirectory = Join-Path $ProjectRoot "test-results"
    if (-not (Test-Path $baseDirectory)) {
        New-Item -ItemType Directory -Path $baseDirectory -Force | Out-Null
    }

    $reportDirectory = Join-Path $baseDirectory ("{0}-{1}" -f $timestamp, $Prefix)
    New-Item -ItemType Directory -Path $reportDirectory -Force | Out-Null
    return (Resolve-Path $reportDirectory).Path
}

function Assert-CommandAvailable {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command '$Name' was not found on PATH."
    }
}


function Invoke-NativeCapture {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,

        [string[]]$Arguments = @(),

        [string]$WorkingDirectory
    )

    $previousErrorActionPreference = $ErrorActionPreference
    if ($WorkingDirectory) {
        Push-Location $WorkingDirectory
    }
    try {
        # Windows PowerShell 5.1 can turn a native program's stderr into
        # ErrorRecord objects. With ErrorActionPreference=Stop that can abort
        # otherwise successful commands such as `java -version`. Native exit
        # codes, not stderr text, are authoritative here.
        $ErrorActionPreference = "Continue"
        $rawOutput = & $FilePath @Arguments 2>&1
        $exitCode = $LASTEXITCODE
        if ($null -eq $exitCode) {
            $exitCode = 0
        }

        $outputLines = @($rawOutput | ForEach-Object { $_.ToString() })
        return [pscustomobject]@{
            ExitCode = [int]$exitCode
            Output = ($outputLines -join [Environment]::NewLine).Trim()
        }
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
        if ($WorkingDirectory) {
            Pop-Location
        }
    }
}

function Assert-Java17 {
    Assert-CommandAvailable -Name "java"
    $result = Invoke-NativeCapture -FilePath "java" -Arguments @("-version")
    if ($result.ExitCode -ne 0) {
        throw "Java version check failed with exit code $($result.ExitCode).`n$($result.Output)"
    }
    if ($result.Output -notmatch '(?im)(?:java|openjdk)\s+version\s+"17(?:\.|\")') {
        throw "JDK 17 is required. Detected:`n$($result.Output)"
    }
    return $result.Output
}

function Assert-GradleWrapper {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ProjectRoot
    )

    $gradlew = Join-Path $ProjectRoot "gradlew.bat"
    if (-not (Test-Path $gradlew)) {
        throw "Gradle wrapper not found: $gradlew"
    }

    $result = Invoke-NativeCapture -FilePath $gradlew -Arguments @("--version") -WorkingDirectory $ProjectRoot
    if ($result.ExitCode -ne 0) {
        throw "Gradle wrapper failed with exit code $($result.ExitCode).`n$($result.Output)"
    }
    if ($result.Output -notmatch '(?m)^Gradle\s+8\.') {
        throw "Gradle 8.x is required for this project. Detected:`n$($result.Output)"
    }
    return $result.Output
}

function Assert-LocalBuildEnvironment {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ProjectRoot
    )

    [void](Assert-Java17)
    [void](Assert-GradleWrapper -ProjectRoot $ProjectRoot)
}

function Get-OnlineAndroidDevices {
    Assert-CommandAvailable -Name "adb"
    $result = Invoke-NativeCapture -FilePath "adb" -Arguments @("devices")
    if ($result.ExitCode -ne 0) {
        throw "Unable to enumerate ADB devices.`n$($result.Output)"
    }

    $devices = @()
    foreach ($line in ($result.Output -split "`r?`n")) {
        if ($line -match '^([^\s]+)\s+device(?:\s|$)') {
            $devices += $Matches[1]
        }
    }
    return @($devices)
}

function Resolve-AndroidSerial {
    param(
        [string]$RequestedSerial
    )

    $devices = @(Get-OnlineAndroidDevices)

    if ($RequestedSerial) {
        if ($devices -notcontains $RequestedSerial) {
            $visible = if ($devices.Count -gt 0) { $devices -join ", " } else { "<none>" }
            throw "Requested device '$RequestedSerial' is not online. Online devices: $visible"
        }
        return $RequestedSerial
    }

    if ($devices.Count -eq 0) {
        throw "No online Android device found. Run 'adb devices -l' and authorize USB debugging first."
    }
    if ($devices.Count -gt 1) {
        throw "Multiple Android devices are online ($($devices -join ', ')). Re-run with -Serial <serial>."
    }
    return $devices[0]
}

function Invoke-LoggedCommand {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,

        [string[]]$Arguments = @(),

        [Parameter(Mandatory = $true)]
        [string]$LogPath,

        [string]$WorkingDirectory
    )

    $parent = Split-Path -Parent $LogPath
    if ($parent -and -not (Test-Path $parent)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }

    if (Test-Path $LogPath) {
        Remove-Item $LogPath -Force
    }

    $previousErrorActionPreference = $ErrorActionPreference
    if ($WorkingDirectory) {
        Push-Location $WorkingDirectory
    }
    try {
        # Do not treat native stderr as a terminating PowerShell error. The
        # command's process exit code remains the PASS/FAIL authority.
        $ErrorActionPreference = "Continue"
        # Windows PowerShell 5.1 wraps native stderr as ErrorRecord objects.
        # If those objects reach Out-Host/Tee-Object directly, normal native
        # progress written to stderr is rendered as a red NativeCommandError.
        # Convert every merged native output item to plain text first; the
        # process exit code remains the only PASS/FAIL authority.
        & $FilePath @Arguments 2>&1 |
            ForEach-Object { $_.ToString() } |
            Tee-Object -FilePath $LogPath |
            ForEach-Object { Write-Host $_ }
        $exitCode = $LASTEXITCODE
        if ($null -eq $exitCode) {
            $exitCode = 0
        }
        return [int]$exitCode
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
        if ($WorkingDirectory) {
            Pop-Location
        }
    }
}

function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Serial,

        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,

        [string]$LogPath
    )

    Assert-CommandAvailable -Name "adb"
    $adbArguments = @("-s", $Serial) + $Arguments
    if ($LogPath) {
        return Invoke-LoggedCommand -FilePath "adb" -Arguments $adbArguments -LogPath $LogPath
    }

    $result = Invoke-NativeCapture -FilePath "adb" -Arguments $adbArguments
    if ($result.Output) {
        Write-Host $result.Output
    }
    return [int]$result.ExitCode
}



function Invoke-AdbWithTimeout {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Serial,

        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,

        [Parameter(Mandatory = $true)]
        [string]$LogPath,

        [Parameter(Mandatory = $true)]
        [ValidateRange(1, 86400)]
        [int]$TimeoutSeconds
    )

    Assert-CommandAvailable -Name "adb"

    $parent = Split-Path -Parent $LogPath
    if ($parent -and -not (Test-Path $parent)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }

    $stdoutPath = "$LogPath.stdout.tmp"
    $stderrPath = "$LogPath.stderr.tmp"
    foreach ($path in @($LogPath, $stdoutPath, $stderrPath)) {
        if (Test-Path $path) {
            Remove-Item $path -Force
        }
    }

    $adbCommand = Get-Command "adb" -ErrorAction Stop
    $adbPath = $adbCommand.Source
    if (-not $adbPath) {
        $adbPath = $adbCommand.Path
    }
    if (-not $adbPath) {
        $adbPath = "adb"
    }

    $adbArguments = @("-s", $Serial) + $Arguments
    $process = Start-Process -FilePath $adbPath `
        -ArgumentList $adbArguments `
        -NoNewWindow `
        -PassThru `
        -RedirectStandardOutput $stdoutPath `
        -RedirectStandardError $stderrPath

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $stdoutLinesShown = 0
    $stderrLinesShown = 0
    $timedOut = $false

    try {
        while (-not $process.HasExited) {
            Start-Sleep -Milliseconds 500

            if (Test-Path $stdoutPath) {
                $stdoutLines = @(Get-Content -Path $stdoutPath -ErrorAction SilentlyContinue)
                if ($stdoutLines.Count -gt $stdoutLinesShown) {
                    $stdoutLines[$stdoutLinesShown..($stdoutLines.Count - 1)] | ForEach-Object { Write-Host $_ }
                    $stdoutLinesShown = $stdoutLines.Count
                }
            }
            if (Test-Path $stderrPath) {
                $stderrLines = @(Get-Content -Path $stderrPath -ErrorAction SilentlyContinue)
                if ($stderrLines.Count -gt $stderrLinesShown) {
                    $stderrLines[$stderrLinesShown..($stderrLines.Count - 1)] | ForEach-Object { Write-Host $_ }
                    $stderrLinesShown = $stderrLines.Count
                }
            }

            if ((Get-Date) -ge $deadline) {
                $timedOut = $true
                try {
                    $process.Kill()
                }
                catch {
                    # Best effort: the timeout result remains authoritative.
                }
                break
            }
        }

        try {
            $process.WaitForExit()
        }
        catch {
            # A killed adb process can already be fully gone.
        }
    }
    finally {
        $stdoutText = if (Test-Path $stdoutPath) { Get-Content -Raw -Path $stdoutPath -ErrorAction SilentlyContinue } else { "" }
        $stderrText = if (Test-Path $stderrPath) { Get-Content -Raw -Path $stderrPath -ErrorAction SilentlyContinue } else { "" }
        @(
            $stdoutText,
            $(if ($stderrText) { "--- STDERR ---`r`n$stderrText" } else { "" })
        ) -join "`r`n" | Set-Content -Path $LogPath -Encoding UTF8

        foreach ($path in @($stdoutPath, $stderrPath)) {
            if (Test-Path $path) {
                Remove-Item $path -Force -ErrorAction SilentlyContinue
            }
        }
    }

    $exitCode = if ($timedOut) { 124 } elseif ($process.HasExited) { $process.ExitCode } else { 1 }
    return [pscustomobject]@{
        ExitCode = [int]$exitCode
        TimedOut = [bool]$timedOut
        TimeoutSeconds = [int]$TimeoutSeconds
    }
}

function Get-SmokeDeviceConflict {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Serial,

        [string]$ProductionPackageName = "com.punch.app",

        [string]$ReportDirectory
    )

    $owners = Get-AdbOutput -Serial $Serial -Arguments @("shell", "dpm", "list-owners") -IgnoreFailure
    $devicePolicy = Get-AdbOutput -Serial $Serial -Arguments @("shell", "dumpsys", "device_policy") -IgnoreFailure
    $activityState = Get-AdbOutput -Serial $Serial -Arguments @("shell", "dumpsys", "activity", "activities") -IgnoreFailure
    $productionPackage = Get-AdbOutput -Serial $Serial -Arguments @("shell", "dumpsys", "package", $ProductionPackageName) -IgnoreFailure
    $homeRoleHolders = Get-AdbOutput -Serial $Serial -Arguments @("shell", "cmd", "role", "get-role-holders", "android.app.role.HOME") -IgnoreFailure
    $productionPid = Get-AdbOutput -Serial $Serial -Arguments @("shell", "pidof", $ProductionPackageName) -IgnoreFailure

    if ($ReportDirectory) {
        if (-not (Test-Path $ReportDirectory)) {
            New-Item -ItemType Directory -Path $ReportDirectory -Force | Out-Null
        }
        @(
            "command=adb -s $Serial shell dpm list-owners",
            "captured_at=$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')",
            "",
            $owners,
            "",
            "--- dumpsys device_policy ---",
            $devicePolicy
        ) | Set-Content -Path (Join-Path $ReportDirectory "device-policy.txt") -Encoding UTF8
        @(
            "command=adb -s $Serial shell dumpsys activity activities",
            "captured_at=$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')",
            "",
            $activityState
        ) | Set-Content -Path (Join-Path $ReportDirectory "activity-state.txt") -Encoding UTF8
        @(
            "captured_at=$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')",
            "--- dumpsys package ---",
            $productionPackage,
            "",
            "--- HOME role holders ---",
            $homeRoleHolders
        ) | Set-Content -Path (Join-Path $ReportDirectory "system-app-state.txt") -Encoding UTF8
    }

    $escapedPackage = [Regex]::Escape($ProductionPackageName)
    $ownerCombined = "$owners`n$devicePolicy"
    $isDeviceOwner = (
        $owners -match "(?is)(?:DeviceOwner|device\s+owner).{0,600}$escapedPackage" -or
        $owners -match "(?is)$escapedPackage.{0,600}(?:DeviceOwner|device\s+owner)" -or
        $devicePolicy -match "(?is)Device\s+Owner.{0,1200}$escapedPackage"
    )
    $isProductionRunning = -not [string]::IsNullOrWhiteSpace($productionPid)
    $isProductionForeground = $activityState -match "(?im)(?:mResumedActivity|topResumedActivity|ResumedActivity)[^\r\n]*$escapedPackage/"
    $isLockTaskActive = $activityState -match "(?im)mLockTaskModeState\s*=\s*(?:LOCKED|PINNED)" -or
        $activityState -match "(?im)lockTaskModeState\s*=\s*(?:LOCKED|PINNED)"
    $isSystemApp = $productionPackage -match "(?im)(?:pkgFlags|flags)\s*=\s*\[[^\]]*\bSYSTEM\b" -or
        $productionPackage -match "(?im)\bUPDATED_SYSTEM_APP\b"
    $isProductionHome = $homeRoleHolders -match "(?im)^\s*$escapedPackage\s*$"

    $reasons = New-Object System.Collections.Generic.List[string]
    if ($isDeviceOwner -and $isProductionRunning) {
        [void]$reasons.Add("production Device Owner process is running")
    }
    if ($isProductionForeground) {
        [void]$reasons.Add("production app is the resumed foreground activity")
    }
    if ($isDeviceOwner -and $isLockTaskActive) {
        [void]$reasons.Add("device is in active LockTask/Kiosk mode")
    }
    if ($isSystemApp -and $isProductionRunning) {
        [void]$reasons.Add("production Android 12 System App process is running")
    }
    if ($isSystemApp -and $isProductionHome) {
        [void]$reasons.Add("production Android 12 System App holds ROLE_HOME")
    }

    $hasConflict = $reasons.Count -gt 0
    $reason = if ($hasConflict) { $reasons -join "; " } else { "" }

    return [pscustomobject]@{
        Conflict = [bool]$hasConflict
        Reason = $reason
        IsDeviceOwner = [bool]$isDeviceOwner
        IsSystemApp = [bool]$isSystemApp
        IsProductionHome = [bool]$isProductionHome
        IsProductionRunning = [bool]$isProductionRunning
        IsProductionForeground = [bool]$isProductionForeground
        IsLockTaskActive = [bool]$isLockTaskActive
        ProductionPid = $productionPid.Trim()
    }
}

function Get-AdbOutput {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Serial,

        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,

        [switch]$IgnoreFailure
    )

    Assert-CommandAvailable -Name "adb"
    $adbArguments = @("-s", $Serial) + $Arguments
    $result = Invoke-NativeCapture -FilePath "adb" -Arguments $adbArguments
    if ($result.ExitCode -ne 0 -and -not $IgnoreFailure) {
        throw "ADB command failed (exit $($result.ExitCode)): adb -s $Serial $($Arguments -join ' ')`n$($result.Output)"
    }
    return $result.Output
}


function Start-AndroidLauncherPackage {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Serial,

        [Parameter(Mandatory = $true)]
        [string]$PackageName,

        [string]$OutputPath,

        [switch]$IgnoreFailure
    )

    $arguments = @(
        "shell", "am", "start", "-W",
        "-a", "android.intent.action.MAIN",
        "-c", "android.intent.category.LAUNCHER",
        "-p", $PackageName
    )

    try {
        $output = Get-AdbOutput -Serial $Serial -Arguments $arguments -IgnoreFailure:$IgnoreFailure
        if ($OutputPath) {
            $parent = Split-Path -Parent $OutputPath
            if ($parent -and -not (Test-Path $parent)) {
                New-Item -ItemType Directory -Path $parent -Force | Out-Null
            }
            $output | Set-Content -Path $OutputPath -Encoding UTF8
        }
        return $output
    }
    catch {
        if ($OutputPath) {
            $parent = Split-Path -Parent $OutputPath
            if ($parent -and -not (Test-Path $parent)) {
                New-Item -ItemType Directory -Path $parent -Force | Out-Null
            }
            $_.Exception.Message | Set-Content -Path $OutputPath -Encoding UTF8
        }
        throw
    }
}


function Backup-InstalledPackageApks {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Serial,

        [Parameter(Mandatory = $true)]
        [string]$PackageName,

        [Parameter(Mandatory = $true)]
        [string]$BackupDirectory
    )

    New-Item -ItemType Directory -Path $BackupDirectory -Force | Out-Null
    $pathsOutput = Get-AdbOutput -Serial $Serial -Arguments @("shell", "pm", "path", $PackageName)
    $remotePaths = @()
    foreach ($line in ($pathsOutput -split "`r?`n")) {
        if ($line -match '^package:(.+)$') {
            $remotePaths += $Matches[1].Trim()
        }
    }
    if ($remotePaths.Count -eq 0) {
        throw "Unable to locate installed APK path(s) for $PackageName."
    }

    $localPaths = @()
    $index = 0
    foreach ($remotePath in $remotePaths) {
        $leaf = [System.IO.Path]::GetFileName($remotePath)
        if ([string]::IsNullOrWhiteSpace($leaf)) {
            $leaf = "package-$index.apk"
        }
        if ($remotePaths.Count -gt 1) {
            $leaf = ("{0:D2}-{1}" -f $index, $leaf)
        }
        $localPath = Join-Path $BackupDirectory $leaf
        $pullExit = Invoke-Adb -Serial $Serial `
            -Arguments @("pull", $remotePath, $localPath) `
            -LogPath (Join-Path $BackupDirectory ("pull-{0:D2}.log" -f $index))
        if ($pullExit -ne 0 -or -not (Test-Path $localPath)) {
            throw "Failed to back up installed APK '$remotePath' for $PackageName."
        }
        $localPaths += (Resolve-Path $localPath).Path
        $index += 1
    }

    @(
        "package=$PackageName",
        "serial=$Serial",
        "captured_at=$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')",
        "remote_paths=$($remotePaths -join ';')",
        "local_paths=$($localPaths -join ';')"
    ) | Set-Content -Path (Join-Path $BackupDirectory "backup-manifest.txt") -Encoding UTF8

    return [pscustomobject]@{
        PackageName = $PackageName
        ApkPaths = @($localPaths)
        BackupDirectory = (Resolve-Path $BackupDirectory).Path
    }
}

function Restore-InstalledPackageApks {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Serial,

        [Parameter(Mandatory = $true)]
        [string[]]$ApkPaths,

        [Parameter(Mandatory = $true)]
        [string]$LogPath
    )

    $existing = @($ApkPaths | Where-Object { $_ -and (Test-Path $_) })
    if ($existing.Count -eq 0) {
        throw "No backed-up APKs are available for restoration."
    }

    if ($existing.Count -eq 1) {
        return Invoke-Adb -Serial $Serial `
            -Arguments @("install", "-r", "-d", $existing[0]) `
            -LogPath $LogPath
    }

    $args = @("install-multiple", "-r", "-d") + $existing
    return Invoke-Adb -Serial $Serial -Arguments $args -LogPath $LogPath
}

function Get-DeviceOwnerMaintenanceState {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Serial,

        [string]$ProductionPackageName = "com.punch.app",
        [string]$SmokePackageName = "com.punch.app.smoke",
        [string]$SmokeTestPackageName = "com.punch.app.smoke.test",
        [string]$ReportDirectory
    )

    $activityState = Get-AdbOutput -Serial $Serial -Arguments @("shell", "dumpsys", "activity", "activities") -IgnoreFailure
    $smokePackage = Get-AdbOutput -Serial $Serial -Arguments @("shell", "dumpsys", "package", $SmokePackageName) -IgnoreFailure
    $smokeTestPackage = Get-AdbOutput -Serial $Serial -Arguments @("shell", "dumpsys", "package", $SmokeTestPackageName) -IgnoreFailure
    $homeRoleHolders = Get-AdbOutput -Serial $Serial -Arguments @("shell", "cmd", "role", "get-role-holders", "android.app.role.HOME") -IgnoreFailure

    $escapedProductionPackage = [Regex]::Escape($ProductionPackageName)
    $isLockTaskActive = $activityState -match "(?im)mLockTaskModeState\s*=\s*(?:LOCKED|PINNED)" -or
        $activityState -match "(?im)lockTaskModeState\s*=\s*(?:LOCKED|PINNED)"
    $productionForeground = $activityState -match "(?im)(?:mResumedActivity|topResumedActivity|ResumedActivity)[^\r\n]*$escapedProductionPackage/"
    $productionHomeHeld = $homeRoleHolders -match "(?im)^\s*$escapedProductionPackage\s*$"
    $smokeSuspended = $smokePackage -match "(?im)\bsuspended=true\b"
    $smokeTestSuspended = $smokeTestPackage -match "(?im)\bsuspended=true\b"

    if ($ReportDirectory) {
        New-Item -ItemType Directory -Path $ReportDirectory -Force | Out-Null
        @(
            "captured_at=$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')",
            "production_package=$ProductionPackageName",
            "smoke_package=$SmokePackageName",
            "smoke_test_package=$SmokeTestPackageName",
            "lock_task_active=$isLockTaskActive",
            "production_foreground=$productionForeground",
            "production_home_held=$productionHomeHeld",
            "smoke_suspended=$smokeSuspended",
            "smoke_test_suspended=$smokeTestSuspended",
            "",
            "--- activity ---",
            $activityState,
            "",
            "--- smoke package ---",
            $smokePackage,
            "",
            "--- smoke test package ---",
            $smokeTestPackage
        ) | Set-Content -Path (Join-Path $ReportDirectory "maintenance-state.txt") -Encoding UTF8
    }

    return [pscustomobject]@{
        Ready = [bool](
            -not $isLockTaskActive -and
            -not $productionForeground -and
            -not $productionHomeHeld -and
            -not $smokeSuspended -and
            -not $smokeTestSuspended
        )
        IsLockTaskActive = [bool]$isLockTaskActive
        ProductionForeground = [bool]$productionForeground
        ProductionHomeHeld = [bool]$productionHomeHeld
        SmokeSuspended = [bool]$smokeSuspended
        SmokeTestSuspended = [bool]$smokeTestSuspended
    }
}

function Wait-DeviceOwnerMaintenanceReady {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Serial,

        [string]$ProductionPackageName = "com.punch.app",
        [string]$SmokePackageName = "com.punch.app.smoke",
        [string]$SmokeTestPackageName = "com.punch.app.smoke.test",

        [ValidateRange(5, 60)]
        [int]$TimeoutSeconds = 15,

        [ValidateRange(200, 5000)]
        [int]$PollIntervalMilliseconds = 500,

        [string]$ReportDirectory
    )

    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    $lastState = $null
    $attempt = 0
    do {
        $attempt += 1
        $lastState = Get-DeviceOwnerMaintenanceState `
            -Serial $Serial `
            -ProductionPackageName $ProductionPackageName `
            -SmokePackageName $SmokePackageName `
            -SmokeTestPackageName $SmokeTestPackageName
        if ($lastState.Ready) {
            if ($ReportDirectory) {
                [void](Get-DeviceOwnerMaintenanceState `
                    -Serial $Serial `
                    -ProductionPackageName $ProductionPackageName `
                    -SmokePackageName $SmokePackageName `
                    -SmokeTestPackageName $SmokeTestPackageName `
                    -ReportDirectory $ReportDirectory)
                @(
                    "ready=true",
                    "attempts=$attempt",
                    "timeout_seconds=$TimeoutSeconds",
                    "completed_at=$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')"
                ) | Set-Content -Path (Join-Path $ReportDirectory "maintenance-wait.txt") -Encoding UTF8
            }
            return $lastState
        }
        Start-Sleep -Milliseconds $PollIntervalMilliseconds
    } while ([DateTime]::UtcNow -lt $deadline)

    if ($ReportDirectory) {
        $lastState = Get-DeviceOwnerMaintenanceState `
            -Serial $Serial `
            -ProductionPackageName $ProductionPackageName `
            -SmokePackageName $SmokePackageName `
            -SmokeTestPackageName $SmokeTestPackageName `
            -ReportDirectory $ReportDirectory
        @(
            "ready=false",
            "attempts=$attempt",
            "timeout_seconds=$TimeoutSeconds",
            "completed_at=$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')"
        ) | Set-Content -Path (Join-Path $ReportDirectory "maintenance-wait.txt") -Encoding UTF8
    }
    return $lastState
}

function Write-DeviceInfo {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Serial,

        [Parameter(Mandatory = $true)]
        [string]$OutputPath
    )

    $lines = @(
        "serial=$Serial",
        "manufacturer=$(Get-AdbOutput -Serial $Serial -Arguments @('shell','getprop','ro.product.manufacturer') -IgnoreFailure)",
        "model=$(Get-AdbOutput -Serial $Serial -Arguments @('shell','getprop','ro.product.model') -IgnoreFailure)",
        "product=$(Get-AdbOutput -Serial $Serial -Arguments @('shell','getprop','ro.product.name') -IgnoreFailure)",
        "android=$(Get-AdbOutput -Serial $Serial -Arguments @('shell','getprop','ro.build.version.release') -IgnoreFailure)",
        "api=$(Get-AdbOutput -Serial $Serial -Arguments @('shell','getprop','ro.build.version.sdk') -IgnoreFailure)",
        "build=$(Get-AdbOutput -Serial $Serial -Arguments @('shell','getprop','ro.build.fingerprint') -IgnoreFailure)",
        "screen=$(Get-AdbOutput -Serial $Serial -Arguments @('shell','wm','size') -IgnoreFailure)",
        "density=$(Get-AdbOutput -Serial $Serial -Arguments @('shell','wm','density') -IgnoreFailure)",
        "captured_at=$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')"
    )
    $lines | Set-Content -Path $OutputPath -Encoding UTF8
}

function Write-AdbCommandOutput {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Serial,

        [Parameter(Mandatory = $true)]
        [string]$CommandLabel,

        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,

        [Parameter(Mandatory = $true)]
        [string]$OutputPath
    )

    $output = Get-AdbOutput -Serial $Serial -Arguments $Arguments -IgnoreFailure
    @(
        "command=$CommandLabel",
        "serial=$Serial",
        "captured_at=$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')",
        "",
        $output
    ) | Set-Content -Path $OutputPath -Encoding UTF8
}

function Collect-DeviceSnapshot {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Serial,

        [Parameter(Mandatory = $true)]
        [string]$PackageName,

        [Parameter(Mandatory = $true)]
        [string]$OutputDirectory,

        [Parameter(Mandatory = $true)]
        [string]$Prefix
    )

    if (-not (Test-Path $OutputDirectory)) {
        New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
    }

    # Command labels are intentionally human-readable because these files are test evidence.
    Write-AdbCommandOutput -Serial $Serial -CommandLabel "dumpsys meminfo $PackageName" -Arguments @("shell", "dumpsys", "meminfo", $PackageName) -OutputPath (Join-Path $OutputDirectory "$Prefix-meminfo.txt")
    Write-AdbCommandOutput -Serial $Serial -CommandLabel "dumpsys battery" -Arguments @("shell", "dumpsys", "battery") -OutputPath (Join-Path $OutputDirectory "$Prefix-battery.txt")
    Write-AdbCommandOutput -Serial $Serial -CommandLabel "dumpsys thermalservice" -Arguments @("shell", "dumpsys", "thermalservice") -OutputPath (Join-Path $OutputDirectory "$Prefix-thermal.txt")
    Write-AdbCommandOutput -Serial $Serial -CommandLabel "dumpsys cpuinfo" -Arguments @("shell", "dumpsys", "cpuinfo") -OutputPath (Join-Path $OutputDirectory "$Prefix-cpuinfo.txt")
    Write-AdbCommandOutput -Serial $Serial -CommandLabel "df /data" -Arguments @("shell", "df", "/data") -OutputPath (Join-Path $OutputDirectory "$Prefix-disk.txt")
}

function Find-AppFatalEvents {
    param(
        [Parameter(Mandatory = $true)]
        [string]$LogPath,

        [Parameter(Mandatory = $true)]
        [string]$PackageName,

        [Parameter(Mandatory = $true)]
        [string]$OutputPath
    )

    if (-not (Test-Path $LogPath)) {
        "logcat file missing: $LogPath" | Set-Content -Path $OutputPath -Encoding UTF8
        return 1
    }

    $raw = Get-Content -Raw -Path $LogPath
    $escapedPackage = [Regex]::Escape($PackageName)
    $events = New-Object System.Collections.Generic.List[string]

    $directPatterns = @(
        "ANR in\s+$escapedPackage",
        "$escapedPackage.*OutOfMemoryError",
        "OutOfMemoryError.*$escapedPackage",
        "$escapedPackage.*Fatal signal",
        "Fatal signal.*$escapedPackage",
        "$escapedPackage.*SIGSEGV",
        "SIGSEGV.*$escapedPackage"
    )

    foreach ($pattern in $directPatterns) {
        $matches = [Regex]::Matches($raw, "(?im)^.*$pattern.*$")
        foreach ($match in $matches) {
            [void]$events.Add($match.Value.Trim())
        }
    }

    if ($raw -match '(?im)^.*FATAL EXCEPTION.*$' -and $raw -match "(?im)^.*Process:\s+$escapedPackage(?:,|\s|$).*$") {
        $contextMatches = Select-String -Path $LogPath -Pattern "FATAL EXCEPTION|Process:\s+$escapedPackage|OutOfMemoryError|Caused by:" -Context 1, 4
        foreach ($match in $contextMatches) {
            [void]$events.Add($match.ToString())
        }
    }

    $uniqueEvents = @($events | Select-Object -Unique)
    if ($uniqueEvents.Count -eq 0) {
        "No app-specific fatal events detected for $PackageName." | Set-Content -Path $OutputPath -Encoding UTF8
        return 0
    }

    $uniqueEvents | Set-Content -Path $OutputPath -Encoding UTF8
    return $uniqueEvents.Count
}

function Get-CurrentPowerShellExecutable {
    $process = Get-Process -Id $PID
    if (-not $process.Path) {
        throw "Unable to determine the current PowerShell executable."
    }
    return $process.Path
}

function Invoke-ChildPowerShellScript {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ScriptPath,

        [string[]]$Arguments = @(),

        [Parameter(Mandatory = $true)]
        [string]$LogPath
    )

    $powerShell = Get-CurrentPowerShellExecutable
    $childArguments = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $ScriptPath) + $Arguments
    return Invoke-LoggedCommand -FilePath $powerShell -Arguments $childArguments -LogPath $LogPath
}

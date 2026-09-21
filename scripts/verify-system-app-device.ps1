param(
    [string]$Adb = "adb",
    [string]$PackageName = "com.punch.app"
)

$ErrorActionPreference = "Stop"

Write-Host "=== APK path ==="
& $Adb shell pm path $PackageName

Write-Host ""
Write-Host "=== Package privileges ==="
& $Adb shell dumpsys package $PackageName | Select-String -Pattern "WRITE_SECURE_SETTINGS|WRITE_SETTINGS|STATUS_BAR|INSTALL_PACKAGES|REBOOT|MANAGE_USERS|SET_PREFERRED_APPLICATIONS|GRANT_RUNTIME_PERMISSIONS"

Write-Host ""
Write-Host "=== HOME resolution ==="
& $Adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME

Write-Host ""
Write-Host "=== Device Owner (expected: not required) ==="
& $Adb shell dpm list-owners

Write-Host ""
Write-Host "=== NTP ==="
& $Adb shell settings get global ntp_server
& $Adb shell settings get global auto_time

Write-Host ""
Write-Host "=== Screen timeout ==="
& $Adb shell settings get system screen_off_timeout
& $Adb shell settings get global stay_on_while_plugged_in

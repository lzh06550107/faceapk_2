param(
    [string]$Adb = "adb",
    [string]$PackageName = "com.punch.app"
)

$ErrorActionPreference = "Stop"

Write-Host "=== APK path ==="
& $Adb shell pm path $PackageName

Write-Host ""
Write-Host "=== Package privileges ==="
& $Adb shell dumpsys package $PackageName | Select-String -Pattern "WRITE_SECURE_SETTINGS|WRITE_SETTINGS|STATUS_BAR|INSTALL_PACKAGES|MANAGE_USERS|READ_PRIVILEGED_PHONE_STATE|MANAGE_ROLE_HOLDERS|GRANT_RUNTIME_PERMISSIONS|START_ACTIVITIES_FROM_BACKGROUND|ACCESS_FINE_LOCATION|ACCESS_COARSE_LOCATION"

Write-Host ""
Write-Host "=== Wi-Fi scan runtime permission ==="
& $Adb shell dumpsys package $PackageName | Select-String -Pattern "android.permission.ACCESS_FINE_LOCATION: granted=|android.permission.ACCESS_COARSE_LOCATION: granted="

Write-Host ""
Write-Host "=== HOME resolution ==="
& $Adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
& $Adb shell cmd role get-role-holders android.app.role.HOME

Write-Host ""
Write-Host "=== WRITE_SETTINGS app-op ==="
& $Adb shell appops get $PackageName WRITE_SETTINGS

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

Write-Host ""
Write-Host "=== Persistent system app flag ==="
& $Adb shell dumpsys package $PackageName | Select-String -Pattern "PERSISTENT|SYSTEM|UPDATED_SYSTEM_APP"

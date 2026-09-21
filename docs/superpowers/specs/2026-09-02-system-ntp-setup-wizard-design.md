# Android 12 System NTP Setup Wizard Design

Date: 2026-09-02
Baseline: FaceAPK V4 realtime-priority face update baseline
Package: `com.punch.app`
Target device: Android 12, Device Owner

## 1. Goal

Add a system-time step to the existing installation wizard so an authorized installer can configure the Android system NTP server used by the device, enable Android automatic time, and prevent manual date/time changes. The app continues to use the Android system clock; it does not introduce an application-owned SNTP clock, offset, or punch-time correction layer.

## 2. Verified device capabilities

The target Android 12 device currently exposes `Settings.Global` key `ntp_server`, with a current value such as `ntp.aliyun.com`. The ROM has a working `network_time_update_service` and a cached NTP result. The ROM does not expose a shell command implementation for `cmd network_time_update_service`, so the app must not depend on a force-refresh shell command.

The production APK declares `android.permission.WRITE_SECURE_SETTINGS`, and device provisioning grants it once with ADB. The app is already Device Owner. These two capabilities are intentionally separated:

- Device Owner: enable automatic time and prohibit user date/time changes.
- `WRITE_SECURE_SETTINGS`: write and verify `Settings.Global` key `ntp_server`.

## 3. Wizard flow

The existing wizard changes from two pages to three:

1. Wi-Fi
2. System Time / NTP
3. Server / Company ID

The NTP page displays:

- Device Owner availability.
- `WRITE_SECURE_SETTINGS` availability.
- Current system NTP server.
- Editable target NTP server.
- Test NTP button.
- Apply System Configuration button.
- Result/status text.

The target field is initialized from the current system `ntp_server`; if it is empty, the field is left empty instead of inventing an environment value.

The installer can test the target without changing the system. Moving to the server page requires one successful system configuration application during the current NTP page session. Returning to the page preserves the applied state as long as the field still matches the applied server.

## 4. Server input rules

Android 12 `ntp_server` is treated as a host value, not a URI. V1 accepts:

- IPv4 addresses, e.g. `192.168.111.240`.
- DNS host names, e.g. `ntp.example.internal`.

V1 rejects:

- Empty values.
- `http://`, `https://`, `ntp://` or any value containing `/`.
- Host plus port such as `host:123`.
- Whitespace inside the host.

IPv6 host configuration is out of scope for this first version because a colon would be ambiguous with an unsupported custom port in the current UI and Android 12 deployment requirements do not need IPv6.

## 5. NTP probe

`NtpProbeClient` sends a standard 48-byte SNTP client request over UDP/123 and waits with a bounded timeout. It does not call `SystemClock.setCurrentTimeMillis()`, does not write `Settings.Global`, and does not persist an offset.

A successful probe validates at minimum:

- UDP response received.
- Response packet length is at least 48 bytes.
- Server mode is server/broadcast-compatible for an NTP response.
- Stratum is between 1 and 15.
- Transmit timestamp is non-zero.

The UI reports host, round-trip time, server epoch time and approximate local system offset. The offset is diagnostic only.

## 6. System configuration transaction

`SystemNtpConfigurator.apply()` performs the following steps:

1. Validate Android version supports `DevicePolicyManager.setAutoTimeEnabled()` (API 30+).
2. Verify the app is Device Owner.
3. Verify `WRITE_SECURE_SETTINGS` is granted.
4. Validate the host.
5. Read the previous `ntp_server`, `AUTO_TIME`, and date/time user restriction state.
6. Write `Settings.Global` `ntp_server`.
7. Read the setting back and require exact equality.
8. Enable automatic time through `DevicePolicyManager.setAutoTimeEnabled(admin, true)`.
9. Add `UserManager.DISALLOW_CONFIG_DATE_TIME`.
10. Verify `Settings.Global.AUTO_TIME == 1`, the setting read-back still matches, and the user restriction is present.

If a later step fails after the NTP server was changed, the configurator makes a best-effort rollback of the previous NTP server and the previous policy values. It returns a structured failure result that includes whether rollback succeeded. The UI must never display success solely because `Settings.Global.putString()` returned true.

## 7. No forced network-time refresh

The target ROM reports a normal NTP polling interval of one day and does not implement the shell command used by some AOSP versions for forced refresh. Therefore V1 does not execute shell commands, toggle networking, reboot the device, or manipulate the system clock to force immediate synchronization.

After successful application, UI wording is precise:

- `系统 NTP 配置已写入` is allowed.
- `等待 Android 系统时间服务下一次同步` is allowed.
- `已从新 NTP 服务器完成系统校时` is not shown unless a future implementation obtains direct evidence.

## 8. Components

### `SystemNtpPolicy`

Pure Java rules for host validation and capability classification. This is unit-tested without Android runtime.

### `NtpPacket`

Pure Java SNTP packet encode/decode helpers used by the network probe. It owns NTP epoch conversion and response validation.

### `NtpProbeClient`

Blocking UDP probe API. The Activity runs it on a dedicated single-thread executor.

### `SystemNtpConfigurator`

Android system bridge for capability detection, current server read, and transactional apply/rollback. This is the only production class allowed to write `Settings.Global` NTP configuration.

### `SetupWizardActivity`

UI orchestration only: display capability/current state, run probe off main thread, apply configuration, and control three-page navigation.

## 9. Security and deployment

`WRITE_SECURE_SETTINGS` remains a non-runtime privileged/development permission. The deployment process must include:

```text
adb shell pm grant com.punch.app android.permission.WRITE_SECURE_SETTINGS
```

The app cannot grant this permission to itself. If the permission is absent, the NTP page shows a clear provisioning error and disables system apply/next actions. Uninstall + reinstall requires provisioning to grant it again.

No tokens, Wi-Fi passwords, face data, or other sensitive material are added to NTP logs.

## 10. Failure behavior

- Invalid host: no network request and no system change.
- Probe failure: status shows failure; no system change.
- Missing Device Owner: apply disabled/fails explicitly.
- Missing `WRITE_SECURE_SETTINGS`: apply disabled/fails explicitly.
- System setting read-back mismatch: failure + rollback attempt.
- Auto-time/restriction policy failure: failure + rollback attempt.
- Activity destroyed while probe is running: result is ignored after destruction; executor is shut down.

## 11. Non-goals

V1 does not:

- Configure `ntp_timeout`.
- Change NTP polling intervals.
- Force-refresh `network_time_update_service`.
- Run root or shell commands.
- Maintain an app time offset.
- Change punch timestamps independently of the Android clock.
- Modify Face SDK scheduling or employee synchronization behavior.

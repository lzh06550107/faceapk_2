# Android 12 系统 NTP 安装引导

## 1. 适用范围

本功能用于 Android 12 专用打卡终端，App 包名为 `com.punch.app`。

生产模式优先使用：

```text
platform-signed Android 12 system app
```

Device Owner 仅保留为开发/兼容回退。

目标是修改 Android 系统使用的 NTP 时间源；打卡、日志、SQLite 和其他业务继续使用系统时钟。

## 2. 生产权限模型

System App 模式要求：

- APK 位于 system image（或作为其同签名 updated-system-app）；
- APK 与 Android framework 使用相同 platform certificate；
- Manifest 声明 `WRITE_SECURE_SETTINGS`；
- ROM 正确授予相关 platform/privileged 权限。

生产设备不再执行：

```powershell
adb shell dpm set-device-owner com.punch.app/.receiver.KioskDeviceAdminReceiver
adb shell pm grant com.punch.app android.permission.WRITE_SECURE_SETTINGS
```

App 会在运行时验证 `WRITE_SECURE_SETTINGS` 是否实际 granted；如果 ROM 权限配置错误，安装引导会明确报错。

## 3. System App NTP 写入流程

```text
校验 NTP Host
    ↓
SNTP UDP/123 探测
    ↓
Settings.Global[ntp_server] = Host
    ↓
Settings.Global[AUTO_TIME] = 1
    ↓
UserManager.DISALLOW_CONFIG_DATE_TIME = true
    ↓
重新读取并验证
```

任一步骤失败都会尝试恢复修改前的 NTP、自动时间状态和日期时间限制。

如果设备走 Device Owner 回退路径，则继续使用原来的 DevicePolicyManager 自动时间/限制接口。

## 4. 安装引导流程

```text
Wi-Fi
  ↓
系统时间 / NTP
  ↓
服务器 / Company ID
```

页面会显示：

- 当前设备管理模式；
- Android 12 System App 是否生效；
- Device Owner 回退是否生效；
- `WRITE_SECURE_SETTINGS` 是否已授予；
- 当前系统 NTP 服务器。

## 5. NTP Host 规则

接受：

```text
192.168.111.240
ntp.example.internal
```

不接受：

```text
http://192.168.111.240
ntp://ntp.example.internal
ntp.example.internal:123
```

固定使用标准 UDP/123。

## 6. 测试按钮

“测试 NTP 服务器”只执行 SNTP 探测并显示：

- Stratum；
- RTT；
- 服务器时间；
- 本机系统时钟估算偏差。

它不会直接修改系统时间，也不会给打卡时间增加 App 级 offset。

## 7. 为什么不强制立即刷新

当前目标 ROM 的网络时间服务由 Android 自己按轮询策略刷新。App 只负责写入系统 NTP 源、开启自动时间并验证配置，不执行 root/shell 强刷、不直接调用 `SystemClock.setCurrentTimeMillis()`。

## 8. 现场验收

```powershell
adb shell settings get global ntp_server
adb shell settings get global auto_time
adb shell dumpsys network_time_update_service
```

预期 `auto_time=1`，NTP host 与安装引导输入一致。

生产 System App 还应运行：

```powershell
scripts\verify-system-app-device.ps1
```

确认 APK 来源、平台权限、HOME、NTP 和息屏状态。

## 9. 故障排查

若页面提示缺少 `WRITE_SECURE_SETTINGS`，不要在生产流程中用 `pm grant` 临时补救；应检查：

1. APK 是否确实使用 ROM platform certificate；
2. APK 是否来自 `/system/priv-app` 或为其 updated-system-app；
3. Manifest 是否包含目标权限；
4. ROM privileged permission 配置是否正确；
5. 实际烧录的是否为本分支构建产物。

NTP 探测失败时检查 DNS、VLAN、防火墙与 UDP/123。

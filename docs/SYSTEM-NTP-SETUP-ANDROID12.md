# Android 12 系统 NTP 安装引导

## 1. 适用范围

本功能用于当前 Android 12 专用打卡终端。App 包名为 `com.punch.app`，设备已配置为 Device Owner。

目标不是给 App 单独维护一个“服务器时间”，而是修改整个 Android 系统使用的 NTP 时间源。打卡、日志、SQLite 和其他业务继续使用 Android 系统时钟。

## 2. 权限模型

系统 NTP 配置需要两类能力：

- Device Owner：开启 Android 自动时间，并禁止用户手工修改日期和时间。
- `android.permission.WRITE_SECURE_SETTINGS`：写入 `Settings.Global` 的 `ntp_server`。

Manifest 已声明 `WRITE_SECURE_SETTINGS`，但 App 不能自行授予该权限。设备首次部署时需要通过 ADB 授权：

```powershell
adb shell pm grant com.punch.app android.permission.WRITE_SECURE_SETTINGS
```

验证：

```powershell
adb shell dumpsys package com.punch.app | findstr WRITE_SECURE_SETTINGS
```

应看到：

```text
android.permission.WRITE_SECURE_SETTINGS: granted=true
```

覆盖安装通常保留该授权；卸载后重新安装需要重新执行 `pm grant`。

## 3. 当前设备已验证的系统入口

读取当前系统 NTP：

```powershell
adb shell settings get global ntp_server
```

当前设备示例：

```text
ntp.aliyun.com
```

系统网络时间服务状态：

```powershell
adb shell dumpsys network_time_update_service
```

当前 ROM 有 `network_time_update_service` 和 NTP cache，但：

```powershell
adb shell cmd network_time_update_service help
```

返回：

```text
No shell command implementation.
```

因此 App 不依赖 `force_refresh` shell 命令。

## 4. 安装引导流程

安装引导顺序为：

```text
Wi-Fi
  ↓
系统时间 / NTP
  ↓
服务器 / Company ID
```

NTP 页面会显示：

- Device Owner 是否有效。
- `WRITE_SECURE_SETTINGS` 是否已授权。
- 当前 Android 系统 NTP 服务器。
- 新 NTP 服务器输入框。
- “测试 NTP 服务器”。
- “应用到 Android 系统”。

第一版接受 IPv4 或 DNS 主机名，例如：

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

Android 12 当前部署固定使用标准 NTP UDP/123，不在页面提供端口配置。

## 5. “测试 NTP 服务器”的语义

测试按钮发送一次 SNTP UDP/123 请求，并显示：

- 是否获得有效 NTP 响应。
- Stratum。
- RTT。
- 服务器时间。
- 服务器时间与当前系统时钟的估算偏差。

测试只用于诊断，不执行以下操作：

```text
SystemClock.setCurrentTimeMillis()
App 时间 offset
打卡时间 + offset
```

## 6. “应用到 Android 系统”的语义

应用时执行：

```text
校验 NTP Host
    ↓
SNTP 测试成功
    ↓
Settings.Global[ntp_server] = 新 Host
    ↓
重新读取并验证
    ↓
DevicePolicyManager.setAutoTimeEnabled(true)
    ↓
DISALLOW_CONFIG_DATE_TIME
    ↓
再次验证系统设置与限制
```

任一后续步骤失败时，会尝试恢复应用前的 NTP、自动时间状态和日期时间限制状态。

成功页面只表示：

```text
系统 NTP 配置已写入并验证
Android 自动时间已开启
用户已禁止手工修改日期和时间
```

它不表示 Android 已经立即向新服务器完成一次系统 NTP 刷新。

## 7. 为什么不强制立即刷新

当前 ROM 的 `dumpsys network_time_update_service` 显示正常轮询周期约为 1 天，失败后的短轮询约为 1 分钟，但 ROM 没有实现 `cmd network_time_update_service` shell command。

因此第一版不：

- 执行 root/shell 强刷。
- 重启设备。
- 断开/重连网络来强制触发。
- 直接修改系统时钟。

配置成功后由 Android 系统时间服务按照 ROM 自己的策略完成后续同步。

## 8. 现场验收命令

应用完成后读取系统配置：

```powershell
adb shell settings get global ntp_server
```

应与安装引导输入一致。

检查自动时间：

```powershell
adb shell settings get global auto_time
```

预期：

```text
1
```

查看系统 NTP 服务：

```powershell
adb shell dumpsys network_time_update_service
```

注意：NTP cache 的更新时间是否已经切换到新服务器不能仅根据“写入配置成功”推断；需要等待系统刷新或结合服务器端 NTP 请求日志验证。

## 9. 故障排查

如果页面显示 `WRITE_SECURE_SETTINGS 未授权`：

```powershell
adb shell pm grant com.punch.app android.permission.WRITE_SECURE_SETTINGS
```

如果 `pm grant` 提示 App 未请求该权限，说明安装的 APK 不是包含最新 Manifest 的版本。

如果测试 NTP 失败，优先检查：

```text
目标 Host 是否能解析
终端到 NTP Server 的 UDP/123 是否放行
服务器是否真的提供 NTP/SNTP 服务
内网 VLAN / 防火墙是否允许 UDP/123
```

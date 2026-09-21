# FaceAPK Android 12 System App 部署与权限架构

## 1. 目标

本分支 `feature/android12-system-app` 将 FaceAPK 的生产设备管理模式从“必须是 Device Owner”扩展为“Android 12 platform-signed system app 优先，Device Owner 兼容回退”。

生产 Android 12 ROM 的目标状态：

```text
/system/priv-app/FaceAPK/FaceAPK.apk
        +
ROM platform certificate
        +
privileged/signature permissions
        ↓
FaceAPK Android 12 System App backend
        ↓
Kiosk / HOME / SystemUI / NTP / 息屏 / 静默 OTA / 运行时权限
```

因此生产设备不再需要执行：

```bash
adb shell dpm set-device-owner com.punch.app/.receiver.KioskDeviceAdminReceiver
adb shell pm grant com.punch.app android.permission.WRITE_SECURE_SETTINGS
```

## 2. 为什么没有使用 android.uid.system

本实现故意不声明 `android:sharedUserId="android.uid.system"`。FaceAPK 保持独立 Linux UID，只获得实际需要的 platform/signature/privileged 权限。Android 12 当前功能不要求 UID 1000。只有遇到明确检查 `Process.SYSTEM_UID` 的厂商私有 API 时才应单独评估 UID 1000。

## 3. System App 身份判定

App 只在以下条件全部满足时启用 System App backend：Android API 31 或 32；APK 来自 system image 或其同签名 updated-system-app；`PackageManager.checkSignatures("android", "com.punch.app") == SIGNATURE_MATCH`。普通 debug APK、普通 adb install APK 不会误进入 System App 模式。

## 4. Manifest 权限

主 Manifest 已声明生产所需权限：`WRITE_SECURE_SETTINGS`、`WRITE_SETTINGS`、`STATUS_BAR`、`INSTALL_PACKAGES`、`MANAGE_USERS`、`MANAGE_ROLE_HOLDERS`、`GRANT_RUNTIME_PERMISSIONS`、`READ_PRIVILEGED_PHONE_STATE`、`DISABLE_KEYGUARD`、`START_ACTIVITIES_FROM_BACKGROUND`。

## 5. ROM allowlist

将 `system-app/privapp-permissions-com.punch.app.xml` 复制到 `/system/etc/permissions/privapp-permissions-com.punch.app.xml`，并将 platform-signed APK 放到 `/system/priv-app/FaceAPK/FaceAPK.apk`。不要把 platform 私钥提交到本仓库。

## 5.1 AOSP / Soong 集成模板

仓库提供：

```text
system-app/Android.bp.example
system-app/product-packages.mk.example
```

推荐 ROM 构建路径：

1. Gradle 只负责产出未使用 ROM 私钥的 release APK；
2. 将 APK 放进 AOSP 设备/vendor 目录并命名为 `FaceAPK-unsigned.apk`；
3. 使用示例 `android_app_import`；
4. 由 Soong 的 `certificate: "platform"` 使用 ROM 平台证书签名；
5. `privileged: true` 安装到 priv-app；
6. XML allowlist 作为 required 模块同步进入 `/system/etc/permissions`。

这种方式比把 `platform.pk8` 放进应用仓库更适合正式 ROM 构建。

## 6. Platform 签名

正式 ROM 集成优先走上一节的 Soong 路径：Gradle 产出 unsigned release APK，由 `android_app_import { certificate: "platform" }` 在 AOSP 构建阶段完成 platform 签名，**不要再对同一产物额外执行一次 apksigner**。

仓库中的 `scripts/sign-platform-apk.ps1` 仅用于工程验证场景，例如已有 system-image 基线、需要临时生成同 platform certificate 的 updated-system-app APK 做 OTA/覆盖安装测试。platform 私钥不得提交到本仓库。

## 7. Kiosk 对应关系

原 Device Owner 路径使用 `setLockTaskPackages/startLockTask/addPersistentPreferredActivity/addUserRestriction/setKeyguardDisabled/setPermissionGrantState`。Android 12 System App 路径使用 `STATUS_BAR` 隐藏 API、RoleManager `ROLE_HOME` System API、`UserManager.setUserRestriction`、`DISABLE_KEYGUARD`、`GRANT_RUNTIME_PERMISSIONS` 与 Activity immersive navigation。Device Owner 后端继续保留作开发/兼容回退。

## 8. NTP

System App 模式直接写 `Settings.Global[ntp_server]` 和 `Settings.Global[AUTO_TIME]=1`，并通过 `UserManager.DISALLOW_CONFIG_DATE_TIME=true` 禁止手工改时间；不再依赖 Device Owner。写入依赖 `WRITE_SECURE_SETTINGS`。

## 9. 息屏

System App 模式直接写 `Settings.System.SCREEN_OFF_TIMEOUT` 与 `Settings.Global.STAY_ON_WHILE_PLUGGED_IN`。原始值仍会在首次修改前保存，以便维护场景恢复。默认业务配置仍为 30 秒。

## 10. 静默 OTA

`UpdateManager` 在“Android 12 platform system app + INSTALL_PACKAGES”或“Device Owner”任一条件满足时走 PackageInstaller 静默路径。更新 APK 必须继续使用相同 platform certificate。更新后的 `/data/app` 版本仍属于 updated system app，因此 System App backend 仍可识别。

## 11. 常驻与开机恢复

生产 Manifest 使用 `android:persistent="true"`。Android 只对 system image 应用生效，用于专用打卡终端的进程常驻/异常后恢复；普通开发安装不会因此获得系统持久进程能力。


新增 `SystemBootReceiver`。Android 12 System App 在 `BOOT_COMPLETED` 后恢复 Kiosk enabled、重施 HOME/SystemUI/User restrictions/Keyguard/Screen timeout，并启动 `KioskHomeActivity`。由现有路由恢复 Setup/Login/Main 页面。

## 12. 验收

刷入 ROM 后不执行任何 Device Owner / pm grant 命令。可运行 `scripts/verify-system-app-device.ps1`，重点验证：APK 位于 system/priv-app 或为 updated-system-app；platform 证书匹配；相关权限 granted；HOME 解析到 `KioskHomeActivity`；NTP/30 秒息屏可设置；OTA 无确认页；重启后自动回到 FaceAPK。

## 13. Device Owner 兼容回退

本分支暂不删除 `KioskDeviceAdminReceiver` 与 DevicePolicyManager 实现，以保留现有自动化测试和普通开发机兼容路径。生产 Android 12 System App 模式不会要求 Device Owner。

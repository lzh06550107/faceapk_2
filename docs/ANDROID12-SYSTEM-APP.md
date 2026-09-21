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

## 14. 开机解锁约束

当前 FaceAPK 不声明 Direct Boot，并继续使用现有 Session / 加密偏好 / 数据库启动链。专用打卡终端的生产 ROM 应保持“开机后无需用户输入锁屏凭据即可进入系统”的设备形态。

如果 ROM 配置了 PIN / 密码等需要首次人工解锁的 secure credential，则 `BOOT_COMPLETED`、凭据加密数据以及 FaceAPK 完整业务恢复会受首次解锁时机影响。该设备形态不属于当前无人值守 Release Gate。

## 15. Android 12 System App Release Gate

刷入正式 system image 后，在 **不执行** `dpm set-device-owner` 和 **不执行** `pm grant WRITE_SECURE_SETTINGS` 的前提下逐项验收：

1. 系统身份：APK 来自 system image / updated-system-app，且与 framework platform certificate 匹配。
2. 权限：System App 权限自检无缺失；`WRITE_SETTINGS` AppOp 可写。
3. HOME：`android.app.role.HOME` holder 为 `com.punch.app`，按 HOME 不离开 FaceAPK。
4. Kiosk：状态栏不可下拉；Recent/Home/Back 不能逃出业务；导航栏保持隐藏/受控。
5. 用户限制：安全模式、恢复出厂、添加用户、挂载物理介质限制生效。
6. 息屏：默认 `screen_off_timeout=30000`，`stay_on_while_plugged_in=0`。
7. NTP：目标 `ntp_server` 写入、`auto_time=1`，并禁止手工改日期时间。
8. Wi-Fi：可扫描、保存、静默连接，并在断网/重启后自动重连。
9. 设备身份：设备序列号读取正常，不需要 ADB 临时授权。
10. 人脸业务：SDK 激活/初始化、人脸库准备、真实图片识别正常。
11. 打卡链路：SQLite 本地落库、同步队列、真实服务器上传和回执正常。
12. OTA：platform 同签名 APK 可后台安装且无确认页；更新后自动拉起并恢复业务。
13. 重启恢复：冷启动/系统重启后自动回到 FaceAPK，HOME/Kiosk/NTP/息屏策略重新生效。
14. 长稳：至少执行现有设备恢复/相机人脸/网络故障测试集，确认不会退回 Launcher 或系统界面。

现场首先运行：

```powershell
scripts\verify-system-app-device.ps1
```

脚本检查通过只是静态/系统状态门禁；第 4、8、10、11、12、13、14 项仍必须在实际 Android 12 ROM 真机上验收。

## 16. System App 真机自动化测试

原有 `deviceOwnerTest` maintenance bridge 继续复用，但现在同时支持 Android 12 System App：

- preflight 会识别 `ROLE_HOME` 与 system/updated-system-app 状态，不再只依赖 Device Owner / LockTask；
- maintenance 进入时会解除 System App Kiosk，并对 Smoke 包执行 unhide / unsuspend；
- maintenance 退出时重新 suspend 测试包并恢复 HOME/Kiosk；
- 因生产 `com.punch.app` 使用 platform certificate，临时 maintenance APK 必须使用相同 platform certificate 原地覆盖；
- platform 私钥只通过本机命令参数传入，不写入 Gradle 配置、不提交仓库。

示例：

```powershell
.\scripts\run-device-tests.ps1 `
  -Serial <device-serial> `
  -UseDeviceOwnerMaintenanceBridge `
  -PlatformPk8 D:\android-keys\platform.pk8 `
  -PlatformX509Pem D:\android-keys\platform.x509.pem
```

如果 `apksigner.bat` 无法从 `ANDROID_HOME\build-tools` 自动找到，可额外传：

```powershell
-ApkSigner D:\Android\Sdk\build-tools\<version>\apksigner.bat
```

`-UseDeviceOwnerMaintenanceBridge` 是为兼容现有测试脚本保留的历史参数名；在本分支中它实际表示“managed-device maintenance bridge”，同时覆盖 Device Owner 与 Android 12 System App。

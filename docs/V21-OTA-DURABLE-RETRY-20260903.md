# V21 OTA Durable Retry / Backoff

日期：2026-09-03
基线：V20 Kiosk System Info

## 范围

V21 只增强 Device Owner 自动 OTA 的失败恢复，不改变 APK 下载协议、APK 签名/包名/versionCode 校验、PackageInstaller 静默安装、更新后 Kiosk 拉起和业务健康检查/回滚策略。

## Durable retry

OTA 可重试失败会把以下状态同步写入 `SharedPreferences`：

- retry pending
- retry count
- next retry epoch time
- last error
- target version
- APK URL

失败退避：

1. 1 分钟
2. 5 分钟
3. 15 分钟
4. 30 分钟
5. 60 分钟
6. 之后固定每 3 小时

没有最大重试次数。

## 独立调度

`UpdateRetryReceiver` 使用 `AlarmManager.setAndAllowWhileIdle(RTC_WAKEUP, ...)`。因此重试不依赖下一次 `config_changed`，应用进程死亡后 Alarm 仍可拉起进程。

Manifest 增加 `RECEIVE_BOOT_COMPLETED`。设备重启后 Receiver 从持久化 retry/install-pending 状态恢复 Alarm。

首次/每次 OTA 真正进入异步执行前，还会先持久化并注册 15 分钟 attempt watchdog。这样即使下载过程中进程被杀，也仍有后续恢复入口。

## 可重试失败

当前自动 OTA 会为下列失败进入 durable retry：

- update directory unavailable
- old APK delete failed
- download crash/failure
- PackageInstaller submit failed
- install pending 超过 30 分钟
- PackageInstaller generic/aborted/storage failure

APK validation failure、需要用户操作、非 Device Owner 等被视为永久/配置错误，不做无限自动重试。

## 代际处理

retry state 同时绑定 target version + APK URL。服务器更新目标版本或 URL 后，旧 retry generation 会清理；新版本立即按新的 OTA 入口尝试，不继承旧版本的高 retry count。

## 成功清理

以下任一安装成功证据都会清除 retry state 并取消 Alarm：

- PackageInstaller `STATUS_SUCCESS`
- installed version 已达到目标
- `MY_PACKAGE_REPLACED`

## 验证

- TDD RED：V21 durable retry 6 个契约全部失败；attempt-watchdog 2 个契约全部失败。
- GREEN：V21 durable retry + attempt-watchdog 8/8 PASS。
- Pure Java `UpdateRetryPolicyTest`: 2/2 PASS。
- Full static harness: 250 tests, 0 failures/errors, 1 skipped（Linux 无 PowerShell parser）。
- Android Gradle gate 无法启动：基线缺少 `gradle/wrapper/gradle-wrapper.jar`，报 `ClassNotFoundException: org.gradle.wrapper.GradleWrapperMain`。因此本环境不声明 Android 编译、lint 或 APK 组装通过。

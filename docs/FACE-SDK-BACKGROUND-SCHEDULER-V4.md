# Face SDK 后台人脸更新与实时识别优先 V4

日期：2026-09-02

## 1. 目标

V4 在 V1-V3 的增量人脸同步、Feature 缓存、4 路头像并发下载和完成顺序消费基础上，解决“大批量人脸下发期间不能打卡”的问题。

目标运行模型：

```text
Heartbeat / person_changed
        ↓
后台员工增量同步
        ↓
4 路头像下载 + SHA256
        ↓
单个人脸 BACKGROUND Face SDK 任务
        ↓
FaceSdkScheduler（唯一 Native 执行通道）
        ↑
Camera REALTIME 识别任务优先
```

百度离线 SDK 8.5 接入文档 FAQ 10.1.4 明确要求：`pushPersonById` / `pushPersonFeatureList` 注册特征时不能同时执行 `search()`，否则可能打乱底层特征列表顺序并造成误识别。因此 V4 不让 `search` 与人脸库写操作真正并发，而是在业务层并发、Native 层串行。

## 2. 关键变化

### 2.1 FaceSdkScheduler

新增三个优先级：

```text
REALTIME    摄像头/Bitmap 人脸识别
BACKGROUND 单人注册、换脸、删除
MAINTENANCE 全量 rebuild、运行时配置维护
```

所有任务由一个 daemon worker 执行；`FaceSdkOperationGuard` 继续作为最终 Native 互斥兜底。

正常情况下 REALTIME 优先；为避免上下班高峰时后台任务永久饥饿，连续实时任务达到上限且后台任务已等待到 aging 阈值后，允许执行一个 BACKGROUND，然后重新检查 REALTIME。

日志新增：

```text
Face SDK op begin: op=... thread=face-sdk-scheduler schedulerWaitMs=... guardWaitMs=...
Face SDK op end: op=... elapsedMs=...
```

`schedulerWaitMs` 用于判断识别实际等待后台单人操作的时间。

### 2.2 正常 person_changed 不再关闭 Punch Ready

事件模式员工同步不再调用 `beginPunchDataPreparation()`。因此允许：

```text
punchRecognitionReady = true
faceLibraryUpdating    = true
```

首次启动、SDK 未初始化、恢复性全量 rebuild 仍保留原来的准备/维护门禁。

### 2.3 Heartbeat 与长事件解耦

Heartbeat 只负责发现事件并提交给 `PlatformEventExecutor`，不在线程内执行长时间人脸任务。

Cursor 状态：

```text
NEW → RUNNING → ACK_PENDING → ACKED(移除)
```

- 重复 `RUNNING` cursor：忽略，不重复执行员工/人脸任务。
- `ACK_PENDING` 再次收到同 cursor：只重试 `/event/result`，不重跑事件 body。
- ACK 失败：保持 `ACK_PENDING`。
- 进程重启后内存状态丢失：依赖原有幂等员工同步重新执行，服务器未收到 ACK 会再次下发。

### 2.4 换脸保持旧脸到新脸准备成功

换脸不再在下载新头像之前删除旧 FaceSearch entry 或旧 Feature cache。

```text
旧脸继续可用
   ↓
下载新头像
   ↓
Detect + Feature 成功
   ↓
单个 BACKGROUND 临界区
   ↓
del old
   ↓
push new
```

新 `push` 失败时，如果旧 Feature cache 可用，则立即尝试 rollback：

```text
push new FAIL
   ↓
push old feature
   ↓
rollback SUCCESS → 旧脸继续可用
```

新 Feature cache 只有在运行时 `push` 成功后才写入。

### 2.5 Desired Face Ready 不等于 face_registered=1

服务器新头像元数据已经写入 SQLite、但新脸注册失败并回滚旧脸时，`face_registered=1` 只代表“仍有一个可用旧脸”，不能代表“目标新脸已经生效”。

V4 使用：

```text
face_registered == 1
AND
face_features(faceVersion + SHA + featureSchemaVersion) 命中
```

判断服务器当前目标脸是否真正 Ready。这样同一事件重试时不会因为旧脸仍可用而错误跳过新脸更新。

### 2.6 删除/禁用人员双重保护

服务器删除/禁用后 SQLite desired state 立即更新。即使 BACKGROUND `delPersonById()` 尚未轮到，FaceSearch 暂时还能搜到该 ID，`PunchFragment` 也会在创建打卡前重新读取 Employee 并校验：

```text
is_deleted == 0
face_status == enabled
employee status 可打卡
```

不满足立即拒绝，不进入 PunchTransaction。

`removeFace()` 现在只有在 Native `delPersonById()` 成功后才删除 Java 映射并减少 loadedFaceCount；Native 删除失败会返回失败并进入该员工的 event result。

## 3. 600 人后台更新时的预期行为

```text
下载线程：最多 4 路并发
Face SDK：永远最多 1 路

LOW E001 register
  ↓
真人识别请求到达 HIGH
  ↓
E001 当前单人 Native 操作完成
  ↓
HIGH recognize
  ↓
LOW E002 register
```

不做 Native 中途抢占，因此实时识别的理论额外等待上限是“当前正在执行的单个人脸操作耗时”，而不是整批 600 人耗时。

## 4. ACK 语义

仍保持强一致：

```text
员工 SQLite 入库             ≠ ACK
FaceUpdateJob 入队            ≠ ACK
头像下载完成                  ≠ ACK
对应 add/update/remove 执行完  → 生成逐员工结果
                               → POST /v3/handheld/event/result
```

ACK HTTP 失败后仅重试 ACK，不重复执行已经完成的人脸任务（同进程生命周期内）。

## 5. 本轮自动验证

当前容器可执行：

- V4 聚焦纯 Java JUnit：45 tests PASS。
- 项目 static contract tests：142 tests PASS，1 个 PowerShell parser 测试因环境无 PowerShell SKIPPED。
- V4 Face scheduler static gates：8 tests PASS。
- 修改 Android Java 文件通过 javac 解析阶段检查：未发现 `illegal start`、`; expected`、`reached end of file while parsing` 等语法错误模式；类型解析因容器无 Android SDK 不作为编译结论。

当前容器无法执行 Android Gradle 构建：

```text
Error: Could not find or load main class org.gradle.wrapper.GradleWrapperMain
Caused by: java.lang.ClassNotFoundException: org.gradle.wrapper.GradleWrapperMain
```

原因是上传基线缺少 `gradle/wrapper/gradle-wrapper.jar`。因此必须在 Windows 完整工程继续执行 `testDebugUnitTest` / `assembleDebug`。

## 6. Windows 构建门禁

在完整项目根目录：

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

必须两条均成功后再装到生产手持机验证。

## 7. 真机验收场景

### 场景 A：600 人首次/大批量后台更新 + 实时打卡

1. 触发 600 人人脸下发。
2. 确认日志出现后台 Face batch。
3. 人脸更新进行到中间时，让一个“原人脸库已存在”的员工真实打卡。
4. 预期：识别在当前单个人脸任务结束后插队，不等待整个 600 人完成。
5. 检查 `schedulerWaitMs`，确认等待时间为单任务级而非批次级。

### 场景 B：修改 1 人头像，新头像有效

预期：旧脸在新 Feature 准备完成前仍可识别；更新成功后新脸可识别；不触发全量 `featureClear/rebuild`。

### 场景 C：修改 1 人头像，新图片无效

预期：新 Feature 提取失败时旧人脸不被删除；event result 该员工失败；后续同事件/新事件仍可重试目标新脸。

### 场景 D：模拟新 push 失败

预期日志出现 rollback；rollback 成功时旧人脸继续可用，Feature cache 不被新失败数据覆盖。

### 场景 E：删除/禁用员工

在 Native remove 尚未执行的短窗口内，即使 FaceSearch 搜到旧 ID，PunchFragment 的 SQLite eligibility gate 也必须拒绝打卡。Native delete 失败时逐员工 result 必须失败。

### 场景 F：重复 cursor / ACK 网络故障

1. 让 event body 已完成。
2. 阻断 `/event/result`。
3. 恢复 heartbeat，让服务器再次返回相同 cursor。
4. 预期只重试 ACK，不重新下载/注册 600 人。

## 8. 暂不包含

V4 不包含：

- 多套 `BDFaceInstance` 并行 Detect/Feature；
- `FaceSearch.search()` 与 push/delete 的真正并发；
- 修改百度 Native 对象生命周期；
- 系统时间/SNTP/NTP；
- 打卡记录面板头像显示修复。

这些应分别作为后续独立任务处理。

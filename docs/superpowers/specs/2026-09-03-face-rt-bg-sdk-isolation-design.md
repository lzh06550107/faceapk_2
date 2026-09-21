# FaceAPK RT/BG Face SDK 对象隔离设计

## 1. 目标

在不改变当前打卡业务、FaceSearch 权威底库语义、员工同步协议和 SQLite 结构的前提下，参照上传的百度 FaceSDKAndroid Demo 的“边导入边识别”模型，将实时刷脸（RT）与后台人脸图片校验/特征提取（BG）从共享 `FaceDetect` / `FaceFeature` 改为独立 SDK 计算对象，使两条业务链能够安全并行。

本阶段仅实施 V1：**RT/BG SDK 计算对象隔离**。

明确不在本阶段实施：

- SQLite feature BLOB 持久化（Feature Store）。
- 员工变更后的增量 `FaceSearch` 提交替代 Full Rebuild。
- 第二套 `FaceSearch`。
- 多个 BG worker / 多个 BG `BDFaceInstance`。
- 与本目标无关的 `SyncService.triggerSync()` 后台启动修复。
- FaceManager 单例、`initialized` 可见性等历史并发风险的额外重构，除非它们直接阻断本阶段实现或测试。

## 2. 当前基线事实

当前生产基线 `faceapk-current-FULL-20260831` 中：

- `FaceSDKManager` 使用无参构造创建 `FaceDetect`、`FaceFeature`、`FaceSearch`、`FaceLive`、`FaceMouthMask`。
- `FaceManager.recognizeFromNv21()` 实时刷脸调用 `getFaceDetectPerson()` 和 `getFacePersonFeature()`。
- `FaceManager.extractFeatureFromFile()` 后台注册、校验和 Full Rebuild 同样调用 `getFaceDetectPerson()` 和 `getFacePersonFeature()`。
- `FaceSearch.search()`、`pushPersonById()`、`delPersonById()`、`featureClear()` 已通过 `faceLibraryLock` 形成统一底库临界区。
- `FaceRegistrationManager` 使用单线程 `ExecutorService` 顺序处理后台人脸注册/校验任务。

因此当前主要风险不是 `FaceSearch` 无锁，而是 RT 与 BG 可能同时进入同一个 `FaceDetect` / `FaceFeature` 对象。

## 3. Demo 参考模型

上传的 `FaceSDKAndroid(1).zip` Demo 中，`FaceModel` 显式创建多个 `BDFaceInstance`，例如 `detectInstance` 和 `detectQualityInstance`；实时检测与导入/质量检测分别绑定到不同实例和不同 SDK 对象。Demo 同时使用独立 Executor 处理实时识别和后台导入。

本项目不原样照搬 Demo 的全部对象数量，而只提取与当前问题直接相关的最小模式：

- RT 计算链保持现有对象不变。
- BG 计算链新增一个独立 `BDFaceInstance`，以及绑定到该实例的 `FaceDetect` 与 `FaceFeature`。
- `FaceSearch` 继续只保留一套权威对象。

## 4. V1 推荐架构

```text
Realtime lane (existing)                 Background lane (new)

Camera NV21                              Employee JPG
    |                                        |
    v                                        v
FaceDetect RT                           FaceDetect BG
(existing faceDetectPerson)            (backgroundInstance)
    |                                        |
    v                                        v
Live / Mask                              FaceFeature BG
    |
    v
FaceFeature RT
(existing facePersonFeature)
    |
    +--------------------+-------------------+
                         |
                         v
                 single FaceSearch
                         |
                 faceLibraryLock
                  /      |       \
               search   push    clear/delete
```

### 4.1 RT 对象

V1 为最小改动，不替换现有 RT 对象构造方式：

- `faceDetectPerson`
- `facePersonFeature`
- `faceLive`
- `faceMouthMask`
- `faceSearch`

实时识别代码继续使用这些对象。

### 4.2 BG 对象

`FaceSDKManager` 新增：

```java
private BDFaceInstance backgroundFaceInstance;
private FaceDetect backgroundFaceDetect;
private FaceFeature backgroundFaceFeature;
```

创建顺序：

```java
backgroundFaceInstance = new BDFaceInstance();
backgroundFaceInstance.creatInstance();
backgroundFaceDetect = new FaceDetect(backgroundFaceInstance);
backgroundFaceFeature = new FaceFeature(backgroundFaceInstance);
```

`backgroundFaceDetect` 使用与现有 RT detect 相同的 `BDFaceSDKConfig`。

BG detect / feature 加载与 RT 对应相同的检测、对齐和特征模型，以保持注册图片特征与实时查询特征的模型兼容性。

### 4.3 BG 单 lane 约束

BG 对象只允许由后台注册/校验/重建特征路径调用：

```text
FaceRegistrationManager single-thread executor
       |
       +--> validateFaceImage()
       +--> registerFace()

FaceManager rebuild executor
       |
       +--> rebuildFaceLibrarySync()
```

V1 不引入多个 BG worker。若 `FaceRegistrationManager` 与 `FaceManager.executor` 可能同时进入 BG 对象，则必须通过一个专用 BG operation gate 或统一 BG scheduler 保证 **同一 BG `FaceDetect` / `FaceFeature` 自身仍然单线程使用**。

推荐 V1 使用 `FaceSDKManager` 内部专用锁：

```java
private final Object backgroundFaceOperationLock = new Object();
```

所有 `backgroundFaceDetect.detect()` + `backgroundFaceFeature.feature()` 组合通过一个封装方法串行执行。这样既允许 RT 与 BG 并行，又禁止两个 BG 调用彼此并发。

## 5. API 边界

禁止 `FaceManager` 直接散落调用 BG SDK 对象 getter。新增一个窄接口，使对象所有权留在 `FaceSDKManager`：

```java
public byte[] extractBackgroundFeature(BDFaceImageInstance imageInstance)
```

该方法职责：

1. 检查 BG 模型已 ready。
2. 在 `backgroundFaceOperationLock` 内执行 BG detect。
3. 若无人脸，返回 `null`。
4. 使用检测结果 landmarks 执行 BG feature。
5. `feature()` 返回值 `<= 0` 时返回 `null`。
6. 成功返回 512-byte feature。

为保留现有日志语义，`empId`、文件路径等业务信息仍由 `FaceManager.extractFeatureFromFile()` 记录，不传入 SDK manager。

如果需要区分“无人脸”和“特征失败”，可使用一个内部结果类型；V1 不为了额外错误分类扩大 API，除非现有测试要求。

## 6. 模型初始化

当前 `initCoreModels()` 仅等待 4 个 RT 模型组：detect、feature、live、mouthMask。

V1 必须将 BG detect 和 BG feature 纳入同一次 SDK ready 门禁。`FaceManager.initialized=true` 之前必须保证：

- RT detect ready。
- RT feature ready。
- live ready。
- mouth mask ready。
- BG detect ready。
- BG feature ready。

因此 pending 计数从 4 调整为 6，BG 任意模型初始化失败都应触发既有 `initModelFail()`，不能让 App 在 BG 模型不可用时误报 Face SDK 初始化成功。

`isModelReady()` 同时检查 BG instance / detect / feature 非空以及全局 init 成功状态。

## 7. 运行时配置更新

当前 `FaceManager.refreshRuntimeConfig()` 只调用 RT `faceDetectPerson.loadConfig()`。

V1 后运行时配置必须同时作用于：

- RT detect。
- BG detect。

配置更新不能与 BG `detect/feature` 并发进入同一个 BG instance。BG config 更新使用与 BG feature extraction 相同的 `backgroundFaceOperationLock`。

RT 配置更新并发风险属于既有问题；本阶段不扩大为完整 RT scheduler 重构，但不能因为增加 BG 对象而使 BG 出现同类问题。

## 8. FaceManager 调整

`FaceManager.extractFeatureFromFile()` 保留：

- `BitmapFactory.decodeFile()`。
- `BDFaceImageInstance` 创建/销毁。
- 图片/员工日志。

将 SDK 调用从：

```java
getFaceDetectPerson().detect(...)
getFacePersonFeature().feature(...)
```

改为：

```java
FaceSDKManager.getInstance().extractBackgroundFeature(inst)
```

因此以下现有流程自动走 BG 对象：

- `validateFaceImage()`。
- `registerFace()` 的图片特征生成阶段。
- `rebuildFaceLibrarySync()` 在锁外准备 `FaceLibraryEntry` 的阶段。

实时：

- `recognizeFromNv21()`。
- `recognizeFromBitmap()`。

继续走 RT 对象。

## 9. FaceSearch 并发边界保持不变

V1 必须保留当前 `faceLibraryLock` 语义：

```text
FaceSearch.search()
FaceSearch.pushPersonById()
FaceSearch.delPersonById()
FaceSearch.featureClear()
empToIntId / intToEmpId 与上述操作的一致性修改
```

这些操作仍然互斥。

特别要求：

- 不创建第二套 `FaceSearch`。
- 不把 `FaceDetectRT` / `FaceFeatureRT` 放入 `faceLibraryLock`。
- 不把 `FaceDetectBG` / `FaceFeatureBG` 放入 `faceLibraryLock`。
- Full Rebuild 继续先在锁外生成全部 feature entries，最后才进入 `faceLibraryLock` clear + push。

因此实时识别可与 BG detect/feature 同时运行，只在最终 search 与库提交时发生短时互斥。

## 10. 生命周期与失败策略

BG engine 与 RT engine 属于同一个 `FaceSDKManager` 生命周期：

- 创建于 license `createInstance()` 成功之后。
- 模型统一初始化。
- 初始化任一阶段失败，则全局 Face SDK 不进入 ready。
- V1 不新增主动 destroy API，因为当前生产管理类没有完整 SDK teardown 生命周期；避免只销毁 BG 而 RT 仍引用全局 Native 状态造成不对称释放。

若后续引入显式 SDK teardown，应一次性设计 RT/BG/Search/Live/Mask 全生命周期，不在 V1 局部实现。

## 11. 线程安全不变量

实施后必须能够证明以下不变量：

1. RT `FaceDetect` 不被 BG 路径调用。
2. RT `FaceFeature` 不被 BG 路径调用。
3. BG `FaceDetect` 只在 `backgroundFaceOperationLock` 内调用。
4. BG `FaceFeature` 只在同一 gate 内调用。
5. RT 与 BG 可以同时执行 Native detect / feature。
6. `FaceSearch.search` 与 `push/delete/clear` 永不并发。
7. 同一张后台图片的 detect -> feature 保持顺序依赖，不人为并行。
8. 不因 V1 引入第二个人脸底库或第二套员工 ID mapping。

## 12. 测试与验证

### 12.1 JVM / 静态测试

新增或扩展测试验证：

- `FaceSDKManager` 初始化 ready 条件包含 BG engine。
- 文件特征提取路径不再引用 RT getter。
- `FaceSearch` 操作仍通过现有 lock。
- BG extraction gate 能串行两个 BG 调用（若 SDK 类无法 JVM mock，则对 gate / facade 做可测试边界，不尝试加载 Native SO）。

### 12.2 构建门禁

至少执行：

```text
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew assembleRelease
```

`lintDebug` 如仍只命中基线已知 `UiTestSetupWizardHostActivity` 缺失问题，应记录为既有失败；不得通过 baseline 或禁用规则隐藏。

### 12.3 真机专项（代码构建完成后，由用户明确允许设备操作再执行）

并发场景：

```text
持续 Camera 刷脸
+
后台 validate/rebuild 500+ 员工
```

采集：

- Crash / ANR / SIGSEGV。
- Face SDK Native fatal。
- RT 成功识别率。
- RT detect/feature/search P50/P95/P99。
- BG 每员工 detect/feature 耗时。
- PSS / Native Heap / RSS。
- threads / FD。

验收核心不是“两个线程都在运行”，而是日志必须能够证明 RT 与 BG 进入不同 SDK 对象，并且长时间运行无共享对象 Native 崩溃。

## 13. 后续阶段（不属于 V1）

V1 真机稳定后再分别评审：

- V2 Feature Store：持久化 512-byte feature，Full Rebuild 不再重新 decode/detect/feature。
- V3 Incremental Search Commit：员工 add/update/delete 直接 push/delete，Full Rebuild 降级为启动/故障恢复路径。
- V4 RT 优先级、BG CPU yield、完整 SDK 生命周期和剩余并发风险收口。

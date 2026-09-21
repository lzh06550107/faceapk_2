# Punch Preparation Status V4.2.1

## 现象

重新登录或进程内 Face runtime library 已经存在时，打卡页可能长期显示“正在初始化打卡环境...”，但实际上人脸识别已经可用。

## 根因

`LoginActivity.postLogin()` 先把当前状态设置为“正在初始化打卡环境...”，随后调用 `preparePunchRecognitionData()`。V4.1 以后 `isPunchRecognitionReady()` 直接以 `FaceManager.isRuntimeFaceLibraryUsable()` 为真值。如果 SDK 已初始化且 `loadedFaceCount > 0`，旧实现直接 `return`，没有再发布 Ready 状态，所以 UI 留在登录阶段的旧状态。

## 修复

`PunchApplication.preparePunchRecognitionData()` 现在把 gate 拆开：

1. token 无效：记录 `reason=token_missing_or_expired` 后返回；
2. 已有 preparation 在运行：记录 `reason=already_preparing` 后返回；
3. runtime library 已可用：立即 `markPunchRecognitionReady("准备完成，可以开始打卡")`，记录 `reason=runtime_face_library_already_usable` 后返回；
4. 只有 runtime 不可用时才真正提交 preparation。

同时新增关键诊断日志：

```text
Punch preparation requested: tokenValid=... preparing=... sdkInitialized=... loadedFaceCount=... runtimeUsable=...
Punch preparation skipped: reason=...
Punch preparation scheduled
Punch preparation begin
Punch preparation face SDK ready: loadedFaceCount=...
Punch preparation data path: activeEmployeeCount=... mode=...
Punch preparation failed: stage=...
Punch preparation completed: ...
```

这些日志不包含 token 值、人脸数据或员工敏感信息。

## 正常日志路径

### Runtime 已经可用

```text
Punch preparation requested: ... sdkInitialized=true loadedFaceCount=N runtimeUsable=true
Punch preparation skipped: reason=runtime_face_library_already_usable loadedFaceCount=N
```

UI 应立即收敛为：

```text
准备完成，可以开始打卡
```

### 需要完整准备

```text
Punch preparation requested: ... runtimeUsable=false
Punch preparation scheduled
Punch preparation begin
Punch preparation face SDK ready: loadedFaceCount=...
Punch preparation data path: activeEmployeeCount=N mode=rebuild_local_face_library
... Face registration / rebuild logs ...
Punch preparation completed: mode=rebuild_local_face_library ... runtimeUsable=true
```

首次安装、无本地员工时：

```text
Punch preparation data path: activeEmployeeCount=0 mode=sync_employees
```

## 不变边界

本补丁不修改：

- `FaceManager`
- `FaceSdkScheduler`
- `FaceSdkOperationGuard`
- `SyncCoordinator`
- `PunchFragment`
- Face SDK 实例数量与串行策略
- 稳定匹配 2 帧 / 1500ms
- Android 12 System NTP V5

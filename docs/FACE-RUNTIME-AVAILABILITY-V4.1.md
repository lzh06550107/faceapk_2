# Face Runtime Availability V4.1 真机修复说明

日期：2026-09-02

## 1. 修复对象

V4 真机测试场景：约 600 人人脸下发过程中连续真人打卡，摄像头正常但没有进入 `recognize-nv21`，无法打卡。

完整日志确认：

- rebuild validation 出现 `Face registration timed out`；
- timeout 后 `rebuild-face-library` 成功加载 679 人；
- 同一时间后续 validation 仍继续执行到批次结束；
- 597 人批次 `elapsedMs=156514`，其中 `face_process_ms=156310`；
- 测试窗口没有 `recognize-nv21`，说明识别在进入 FaceSdkScheduler 前被 Punch Ready gate 拦截。

## 2. V4.1 修复

### 2.1 Runtime library availability 是打卡门禁真值

`FaceManager.isRuntimeFaceLibraryUsable()`：

```text
Face SDK initialized && loadedFaceCount > 0
```

`PunchApplication.isPunchRecognitionReady()` 使用该状态，不再把 preparation/background workflow flag 当作 Native runtime 是否可识别的真值。

因此：

```text
后台仍在准备 / validation
+
runtime FaceSearch 已经有可用人脸
=
继续允许真人识别打卡
```

冷启动且 `loadedFaceCount == 0` 时仍保持阻塞，直到至少加载一个运行时人脸。

### 2.2 preparation timeout + 可用运行库 => 降级可用

如果 validation timeout / incomplete，但最终 rebuild 后 `loadedFaceCount > 0`：

- `markPunchRecognitionReady(...)`；
- 状态提示“人脸准备未完整完成，但现有人脸可继续打卡，后台稍后重试”；
- 不再把 `punchDataReady` 留在 failed / false。

只有 runtime library 仍为空时才继续按失败处理。

### 2.3 rebuild validation 批次合并

`validateEmployeesForRebuild()` 新增 covering-batch coalescing：

```text
当前 batch = [A,B,C,...]
新请求      = [B,C,...]  (子集)
=> JOIN 当前 batch，不再进入单线程 executor 第二次处理
```

如果新请求包含当前 batch 没覆盖的新员工，则允许创建后续 batch；重叠部分会再由 cache-first 快速跳过。

### 2.4 exact Feature Cache first

仅 rebuild validation (`addToRuntimeLibrary=false`) 使用：

```text
getReusableFaceFeature(empId, faceVersion, sha256, schemaVersion)
        |
        +-- HIT --> face_registered=1 --> success
        |           不下载、不JPEG decode、不Detect、不Feature
        |
        +-- MISS --> 保持 V4 原路径
                    download -> validateFaceImage -> Detect -> Feature
```

这里只接受当前 face version + SHA + feature schema 完全匹配的缓存；fallback feature 不作为 validation 命中。

## 3. 明确未修改

- 单 `BDFaceInstance`；
- `FaceSdkScheduler` 优先级实现；
- `FaceSdkOperationGuard`；
- FaceSearch `search/push/delete/clear` 串行安全边界；
- 2 帧稳定确认；
- `STABLE_MATCH_MAX_GAP_MS = 1500`；
- Heartbeat / PlatformEventExecutor / ACK 状态机；
- SQLite schema / DB version。

## 4. 新日志

重点观察：

```text
Face validation batch new: employees=...
Face validation batch join: requested=... covered=...
Face validation batch complete: employees=...
Face validation cache hit: empId=...
Face batch begin: employees=... cacheHits=... nativeCandidates=...
Face batch end: employees=... cacheHits=... nativeCandidates=...
Face preparation incomplete but runtime library remains usable: loaded=...
Face SDK op begin: op=recognize-nv21 ...
```

600人后台处理时，只要 runtime library 已可用，应重新看到 `recognize-nv21` 在后台任务之间出现。

## 5. 真机验收

推荐至少做三组：

1. **已有可用人脸库 + 600人同步**：从同步开始到结束持续真人打卡，必须能完成打卡；日志应出现 `recognize-nv21`。
2. **重复 preparation**：确认第二个相同/子集 validation 打印 `Face validation batch join`，而不是重新产生整批 Detect/Feature。
3. **冷启动空运行库**：`loadedFaceCount=0` 时仍不能错误放行识别；完成第一批运行库加载后自动恢复打卡。

另外复查删除/禁用员工：即使旧 FaceSearch 短暂命中，PunchFragment 仍必须通过 SQLite eligibility 校验拒绝打卡。

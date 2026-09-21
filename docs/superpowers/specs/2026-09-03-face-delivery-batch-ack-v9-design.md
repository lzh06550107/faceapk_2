# Face Delivery Batch ACK V9 Design

## Goal

将 `person_changed` 人脸下发从“整次事件全部处理并注册 FaceSearch 后一次 ACK”改为“每次最多 50 人，完成人脸特征生成和 SQLite 可靠落库后立即用现有 `employees[]` 回传确认；FaceSearch 由设备内部持久任务异步应用”。

## Protocol constraints

- `/employee/sync` 请求字段名称保持不变：`device_id`、`page`、`page_size`、`op_status`。
- 事件模式固定请求 `page=1`、`page_size=50`。服务端根据 `/event/result` 中本次 `employees[]` 结果标记这些员工已处理；下一次 `page=1` 自动得到剩余未处理员工。
- `/event/result` 字段保持不变；同一个 `event_cursor` 允许多次回传不同 `employees[]` 子集。
- 不增加 `batch_cursor`、不增加新的服务端 APPLIED 状态。
- ACK 成功语义：该员工的下发数据已形成确定处理结果；对于成功的人脸更新，至少已经得到有效 512-byte feature，并且员工、特征、FaceApply 持久任务已可靠提交到 SQLite。
- FaceSearch 的 ADD/REPLACE/REMOVE 不属于服务端 ACK 的前置条件。

## Runtime architecture

### Server sync lane

1. `person_changed` 收到 `event_cursor`。
2. 调用 `/employee/sync`，固定 `page=1`，`page_size=50`。
3. 对最多 50 条 change 做业务合并、下载、SHA 校验和 BG feature 提取。
4. 用一个 SQLite transaction 提交：
   - `employees` desired state；
   - 成功提取的 `face_features`；
   - `face_apply_tasks` 的 UPSERT/REMOVE pending desired operation。
5. transaction COMMIT 后，调用现有 `/event/result`，只回传这一批 `employees[]`。
6. ACK 成功后再次请求 `page=1`；若返回空 changeItems，当前事件处理完成。
7. ACK 失败则停止该事件；下一次 heartbeat 重新处理，数据库写入和任务 UPSERT 必须幂等。

### Face apply lane

- `FaceApplyWorker` 使用单线程 executor。
- 队列表 `face_apply_tasks` 每个 `emp_id` 最多一条最新 desired task；新任务覆盖旧任务。
- Worker 从 DB 读取 pending task：
  - `UPSERT`: 读取 `employees + face_features`，调用 `FaceManager.applyStoredFeature(...)`；
  - `REMOVE`: 调用 `FaceManager.removeFace(...)`。
- 成功后按 task `id` 删除。若任务在执行中被新版本覆盖，新 task 的 `id` 不同，不会被旧任务误删。
- 失败记录 `retry_count/last_error`，本轮继续处理更高 task id；同一失败 task 本轮只尝试一次，下次触发再重试，避免单员工阻塞队列和 busy-loop。
- 每一批 durable COMMIT 后触发 worker；App 完成 SDK/FaceSearch 准备后再次触发 worker。

## Database

DB version: 11.

新增：

```sql
CREATE TABLE IF NOT EXISTS face_apply_tasks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    emp_id TEXT NOT NULL UNIQUE,
    operation TEXT NOT NULL,
    face_version INTEGER NOT NULL DEFAULT 0,
    retry_count INTEGER NOT NULL DEFAULT 0,
    last_error TEXT NOT NULL DEFAULT '',
    updated_at INTEGER NOT NULL DEFAULT 0
)
```

任务存在表示尚未成功应用到运行时 FaceSearch；任务成功后删除。

批量落库 API 必须在同一 SQLite transaction 中处理 employee + optional feature + optional apply task。Feature 提取和 HTTP 下载不得在 SQLite transaction 内执行。

## FaceSearch consistency

- `FaceManager.applyStoredFeature()` 不重新解码图片、不重新提取 feature，只消费持久化的 512-byte feature。
- REPLACE 在一个 `faceLibraryLock` 临界区中完成旧 Native delete + 新 Native push + Java maps commit。
- old delete 失败：不改 Java mapping，返回失败。
- old delete 成功但 push 失败：删除旧 Java mapping/count，使 Java 与 Native 同为 NOT_LOADED，任务保留待重试。
- REMOVE：只有 Native delete 成功后才删除 Java mapping/count；不存在 mapping 视为幂等成功。

## Preparation and recovery

- `syncEmployeesForPreparation()` 保持现有准备模式语义，不使用每批 ACK 循环。
- Full Rebuild 仍是启动/恢复的权威运行时重建路径，使用 `employees + face_features`。
- 为避免 Worker 已完成 Native push、但尚未提交 `face_registered=1` 时与 Full Rebuild 竞态，重建候选集合包含“已注册员工”以及存在 durable `UPSERT` task 的员工；task 的存在本身就是尚未收敛完成的 desired-state 证据。
- Full Rebuild 成功后触发 `FaceApplyWorker`，以应用在 rebuild 期间或之后产生的最新 pending desired state。
- 同一服务器批次若极端情况下包含相同 `numbers` 的多条变更，设备在下载/Feature 前先按 `op_time` 合并为该员工的最新 desired state；时间缺失或相等时以批次中后出现的记录为准。

## Failure semantics

- 单员工图片下载、SHA、无人脸等业务失败：该员工 `employees[].success=false`；其余员工仍可成功持久化并在同一批 ACK。
- SQLite batch transaction 失败：本批不得 ACK success，停止当前 event。
- `/event/result` ACK 失败：停止当前 event；同一批次下次可重新拉取并幂等覆盖 SQLite。
- FaceApply 失败不影响已经发出的服务器 ACK，由设备内部任务持续恢复。

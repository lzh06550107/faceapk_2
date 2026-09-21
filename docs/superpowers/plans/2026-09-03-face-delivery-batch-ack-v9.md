# Face Delivery Batch ACK V9 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 每 50 人完成 BG feature + SQLite durable commit 后立即通过现有 employees[] ACK，并将 FaceSearch mutation 解耦为本地持久队列单线程应用。

**Architecture:** 事件同步 lane 固定 `page=1/page_size=50` 循环消费未处理员工；每批数据以 SQLite transaction 写入 employees、face_features、face_apply_tasks 后立即 ACK。FaceApplyWorker 从持久队列读取最新 desired state，串行更新唯一 FaceSearch；Full Rebuild 保留为启动/恢复路径。

**Tech Stack:** Android Java 8, SQLiteOpenHelper, Baidu Face SDK, existing SyncCoordinator/ApiService/static contract harness.

**Spec:** `docs/superpowers/specs/2026-09-03-face-delivery-batch-ack-v9-design.md`

## Global Constraints

- `/employee/sync` 请求字段名称不得增加、删除或重命名，只允许把 `page_size` 改为 50，并在事件模式固定 `page=1`。
- `/event/result` 继续使用现有 `event_cursor + employees[]`，不增加 batch_cursor。
- Face SDK BG compute 仍单线程；唯一 FaceSearch + faceLibraryLock 架构不变。
- 数据库变化必须升级 `Constants.DB_VERSION` 并提供前向 migration。
- `face_sdk_ids` 稳定映射不得复用。
- 不引入 Kotlin、依赖升级或无关重构。

---

### Task 1: Persisted FaceApply queue and batch transaction

**Files:**
- Modify: `app/src/main/java/com/punch/app/utils/Constants.java`
- Modify: `app/src/main/java/com/punch/app/db/DatabaseHelper.java`
- Test: `scripts/tests/test_harness_static.py`

**Interfaces:**
- Produces `DatabaseHelper.FaceBatchWrite`, `commitEmployeeFaceBatch(List<FaceBatchWrite>)`, `getNextFaceApplyTask()`, `deleteFaceApplyTask(long)`, `markFaceApplyTaskFailed(long,String)`.

- [ ] Write failing static contracts for DB version 11, table schema, forward migration, one transaction containing employee/feature/task writes, and task-id conditional completion.
- [ ] Run focused contracts and confirm RED.
- [ ] Implement schema and batch APIs with task row identity protected by auto-increment `id`.
- [ ] Run focused contracts and confirm GREEN.

### Task 2: Consume stored feature without recomputation and make delete semantics transactional

**Files:**
- Modify: `app/src/main/java/com/punch/app/face/FaceManager.java`
- Test: `scripts/tests/test_harness_static.py`

**Interfaces:**
- Produces `RegisterResult applyStoredFeature(Context,String,byte[])` and boolean `removeFace(String)`.

- [ ] Write failing contracts proving stored feature path does not decode/extract/persist feature and checks old `delPersonById()` before push.
- [ ] Write failing remove contract proving Java maps/count are changed only after successful Native delete.
- [ ] Run and confirm RED.
- [ ] Implement minimal runtime mutation methods under existing `faceLibraryLock`.
- [ ] Run and confirm GREEN.

### Task 3: Add single-thread durable FaceApplyWorker

**Files:**
- Create: `app/src/main/java/com/punch/app/face/FaceApplyWorker.java`
- Test: `scripts/tests/test_harness_static.py`

**Interfaces:**
- Consumes DB pending tasks and FaceManager stored-feature/remove APIs.
- Produces `FaceApplyWorker.get().trigger(Context)`.

- [ ] Write failing contracts for single-thread executor, DB-first task consumption, UPSERT/REMOVE dispatch, complete-by-task-id on success, record failure and continue higher task ids without retrying the same task in one drain.
- [ ] Confirm RED.
- [ ] Implement worker with coalesced trigger and no busy-loop.
- [ ] Confirm GREEN.

### Task 4: Prepare one event batch without touching FaceSearch

**Files:**
- Modify: `app/src/main/java/com/punch/app/face/FaceRegistrationManager.java`
- Modify: `app/src/main/java/com/punch/app/face/FaceManager.java`
- Modify: `app/src/main/java/com/punch/app/service/SyncCoordinator.java`
- Test: `scripts/tests/test_harness_static.py`

**Interfaces:**
- Produces a feature-preparation result containing employee id, success/failure, local image path metadata as needed, and 512-byte feature; does not mutate FaceSearch.

- [ ] Write failing contracts that event-mode preparation uses BG feature extraction only and does not call `registerFace()`/`removeFace()` before ACK.
- [ ] Confirm RED.
- [ ] Add a preparation API that downloads/verifies and returns the feature bytes without DB/FaceSearch side effects.
- [ ] Build `FaceBatchWrite` records and commit once per server batch.
- [ ] Confirm GREEN.

### Task 5: 50-person page=1 loop and per-batch ACK

**Files:**
- Modify: `app/src/main/java/com/punch/app/network/ApiService.java`
- Modify: `app/src/main/java/com/punch/app/service/SyncCoordinator.java`
- Test: `scripts/tests/test_harness_static.py`

**Interfaces:**
- Existing `/employee/sync` fields unchanged.
- Existing `reportEventResult(event.cursor, ..., employeeResults, ...)` called once after each durable batch.

- [ ] Write failing contracts for page_size=50, event-mode repeated `syncEmployees(1)`, ACK inside each batch before next fetch, stop on ACK failure, and no duplicate outer person_changed ACK.
- [ ] Confirm RED.
- [ ] Implement person_changed special event loop while leaving preparation-mode pagination intact.
- [ ] Trigger FaceApplyWorker only after COMMIT/ACK boundary without waiting for its result.
- [ ] Confirm GREEN.

### Task 6: Recovery integration and regression

**Files:**
- Modify: `app/src/main/java/com/punch/app/PunchApplication.java`
- Modify: `app/src/main/java/com/punch/app/service/SyncCoordinator.java` if needed
- Test: `scripts/tests/test_harness_static.py`

**Interfaces:**
- Full Rebuild success triggers pending FaceApply drain.

- [ ] Add contract that startup/rebuild path triggers FaceApplyWorker only after runtime is ready.
- [ ] Add recovery contract that Full Rebuild includes durable pending UPSERT employees even before `face_registered=1` is committed.
- [ ] Add batch contract that duplicate employee changes are coalesced to the latest desired state before Feature preparation.
- [ ] Confirm RED then implement.
- [ ] Run V1-V9 focused contracts.
- [ ] Run full static harness.
- [ ] Attempt Gradle gate and report environmental blockers without claiming build PASS.

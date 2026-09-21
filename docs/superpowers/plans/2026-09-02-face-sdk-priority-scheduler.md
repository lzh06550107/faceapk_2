# Face SDK Realtime-Priority Background Update Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Keep punching available during large employee-face updates by moving person events to a background event executor and routing all runtime Face SDK work through a realtime-priority single-worker scheduler.

**Architecture:** A new `FaceSdkScheduler` owns ordering for realtime, background and maintenance native tasks while `FaceSdkOperationGuard` remains the final mutex. `PlatformEventExecutor` decouples heartbeat from long platform events and tracks cursor `RUNNING`/`ACK_PENDING` state. Face replacement becomes a single-employee transaction that keeps the old face until the new feature is ready and rolls back on push failure.

**Tech Stack:** Java 8, Android, Baidu Android Offline Face SDK 8.5, SQLite, OkHttp, JUnit4.

**Spec:** `docs/superpowers/specs/2026-09-02-face-sdk-priority-scheduler-design.md`

## Global Constraints

- `FaceSearch.search()` must never execute concurrently with `pushPersonById`, `pushPersonFeatureList`, `delPersonById`, or `featureClear`.
- V4 uses one effective native Face SDK execution lane; no new parallel `BDFaceInstance` pipelines.
- Normal `person_changed` does not disable an already-ready punch path.
- `/v3/handheld/event/result` ACK waits for actual face add/update/remove completion.
- Existing 4-way face-image download and completion-order processing remain.
- No raw face feature bytes, image bytes, or tokens in logs.
- The supplied source bundle has no `.git` metadata, so execution uses verified file checkpoints instead of git commits.

---

### Task 1: Realtime-priority Face SDK scheduler

**Files:**
- Create: `app/src/main/java/com/punch/app/face/FaceSdkScheduler.java`
- Create: `app/src/test/java/com/punch/app/face/FaceSdkSchedulerTest.java`
- Modify: `app/src/main/java/com/punch/app/face/FaceManager.java`

**Interfaces:**
- Produces: `FaceSdkScheduler.shared()`.
- Produces: `<T> T callRealtime(String name, Operation<T> op)`.
- Produces: `<T> T callBackground(String name, Operation<T> op)`.
- Produces: `<T> T callMaintenance(String name, Operation<T> op)`.
- Consumes: `FaceSdkOperationGuard.Operation<T>` so existing lambda call sites stay simple.

- [x] Write JUnit tests proving native operations are never concurrent, realtime work overtakes queued background work after the currently-running task, FIFO order is preserved within priority, and aged background work receives bounded progress.
- [x] Run `FaceSdkSchedulerTest` and verify RED because the scheduler does not exist.
- [x] Implement one daemon worker with separate realtime/background/maintenance queues, synchronous result futures, queue wait metrics, and bounded realtime burst/background aging.
- [x] Run scheduler tests and verify GREEN.
- [x] Route `recognizeFromNv21` and `recognizeFromBitmap` to REALTIME; `registerFace`/`validateFaceImage`/`removeFace` to BACKGROUND; `rebuildFaceLibrarySync`/runtime config maintenance to MAINTENANCE. Keep `FaceSdkOperationGuard` inside each actual worker operation.
- [x] Run scheduler + existing `FaceSdkOperationGuardTest` + face policy/cache tests.

### Task 2: Safe single-employee replacement with rollback

**Files:**
- Modify: `app/src/main/java/com/punch/app/face/FaceManager.java`
- Modify: `app/src/main/java/com/punch/app/face/FaceRegistrationManager.java`
- Modify: `app/src/main/java/com/punch/app/db/DatabaseHelper.java` only if a focused helper is required.
- Create: `app/src/main/java/com/punch/app/face/FaceReplacementPolicy.java`
- Create: `app/src/test/java/com/punch/app/face/FaceReplacementPolicyTest.java`

**Interfaces:**
- Produces: replacement logic that extracts the new feature before runtime delete, preserves the old cached feature until success, and reports rollback status.
- `FaceRegistrationManager.registerEmployees(...)` remains callback-compatible for `SyncCoordinator`.

- [x] Write RED policy tests for ADD, REPLACE, REMOVE and rollback decisions.
- [x] Implement `FaceReplacementPolicy` as pure Java decision logic.
- [x] Run policy tests GREEN.
- [x] Change runtime replacement so discovery does not delete old cache/runtime first. In the single BACKGROUND SDK task: extract new feature, read reusable old feature, delete old runtime entry, push new feature, and if push fails attempt old-feature rollback.
- [x] Persist new feature cache/`face_registered=1` only after new runtime push success; preserve old cache on failed replacement.
- [x] Add rollback logs without feature bytes.
- [x] Run face manager static/unit tests and compilation gate.

### Task 3: Background update state separate from punch readiness

**Files:**
- Modify: `app/src/main/java/com/punch/app/PunchApplication.java`
- Modify: `app/src/main/java/com/punch/app/service/SyncCoordinator.java`
- Create: `app/src/main/java/com/punch/app/service/FaceUpdateState.java`
- Create: `app/src/test/java/com/punch/app/service/FaceUpdateStateTest.java`

**Interfaces:**
- Produces: `PunchApplication.setFaceLibraryUpdating(boolean, String)` and `isFaceLibraryUpdating()`.
- Startup preparation methods keep existing `punchDataReady` semantics.

- [x] Write RED tests showing normal background update status does not alter punch readiness.
- [x] Implement the independent update-state holder and application API.
- [x] Remove `beginPunchDataPreparation()` / `updatePunchDataPreparationStatus()` calls from event-mode employee sync; replace them with background update status/progress.
- [x] Keep preparation-mode startup behavior unchanged.
- [x] Ensure face event failure does not call `markPunchRecognitionFailed()` when an existing usable runtime library remains.
- [x] Run state tests and source-structure regression checks.

### Task 4: Recognition-time employee eligibility gate

**Files:**
- Create: `app/src/main/java/com/punch/app/service/PunchEmployeeEligibility.java`
- Create: `app/src/test/java/com/punch/app/service/PunchEmployeeEligibilityTest.java`
- Modify: `app/src/main/java/com/punch/app/fragment/PunchFragment.java`

**Interfaces:**
- Produces: `PunchEmployeeEligibility.isEligible(Employee emp)` pure Java predicate.
- Consumes current `Employee.isDeleted`, `faceStatus`, and production employee `status` fields.

- [x] Write RED tests for null employee, deleted employee, disabled face, abnormal employee status, and normal employee.
- [x] Implement the minimal eligibility predicate using existing production status constants.
- [x] After `getEmployee(result.empId)` and before snapshot/punch, reject ineligible employees and reset pending recognition state.
- [x] Run eligibility tests and PunchFragment static checks.

### Task 5: Heartbeat/event execution decoupling and cursor dedupe

**Files:**
- Create: `app/src/main/java/com/punch/app/service/PlatformEventExecutor.java`
- Create: `app/src/main/java/com/punch/app/service/PlatformEventStateMachine.java`
- Create: `app/src/test/java/com/punch/app/service/PlatformEventStateMachineTest.java`
- Modify: `app/src/main/java/com/punch/app/service/SyncCoordinator.java`

**Interfaces:**
- `PlatformEventExecutor.submit(Context, HeartbeatEventData, EventHandler, AckHandler)` returns quickly.
- State machine supports `NEW`, `RUNNING`, `ACK_PENDING`; successful ACK removes the cursor.
- Duplicate RUNNING cursor is ignored; duplicate ACK_PENDING cursor retries ACK only.

- [x] Write RED state-machine tests for first submit, duplicate RUNNING, transition to ACK_PENDING, duplicate ACK_PENDING, ACK success cleanup, and ACK failure retention.
- [x] Implement pure Java state machine.
- [x] Implement one event executor that runs event bodies off the heartbeat executor and retains completed outcomes for ACK retry.
- [x] Change `applyHeartbeatEvents()` to validate/log then submit; do not call `handleEvent()` inline.
- [x] Ensure event body still waits for face completion before producing its `EventProcessingOutcome`.
- [x] Run state-machine and sync trigger tests.

### Task 6: Employee face-delta sequencing cleanup

**Files:**
- Modify: `app/src/main/java/com/punch/app/service/SyncCoordinator.java`
- Modify: `app/src/main/java/com/punch/app/face/FaceRegistrationManager.java`
- Modify: `app/src/test/java/com/punch/app/service/EmployeeFaceDeltaPolicyTest.java`

**Interfaces:**
- REPLACE no longer enters the pre-registration `removalTargets` path.
- REMOVE/DELETE still schedules background runtime removal.
- Registration result remains the source of per-employee ACK success/failure.

- [x] Extend RED delta tests to distinguish REPLACE from REMOVE execution sequencing.
- [x] Stop deleting `face_features` at face-change discovery and stop pre-removing registered replacement faces.
- [x] Preserve true delete/disable removal behavior, but execute each remove through BACKGROUND scheduler.
- [x] Verify no-face-change events do not alter punch readiness and finish quickly.
- [x] Run employee face delta + registration batch tests.

### Task 7: Full regression and artifact packaging

**Files:**
- Create: `docs/FACE-SDK-BACKGROUND-SCHEDULER-V4.md`
- Create package: `FaceEmployeeSync-RealtimePriority-V4-Cumulative-Patch-20260902.zip`

**Interfaces:**
- Cumulative patch contains V1-V3 optimizations plus V4 scheduler/event/background changes.

- [x] Run all focused pure Java tests for scheduler, guard, parallel download, feature cache, face delta, update state, eligibility, platform event state and sync trigger.
- [x] Run available Gradle unit tests/build if wrapper JAR and Android SDK are available; otherwise record the exact environmental blocker and do not claim full Android build success.
- [x] Run Java/source static gates proving `search` and runtime writes route through scheduler/guard, event-mode employee sync no longer calls punch-preparation blockers, and heartbeat submits events without inline long processing.
- [x] Write deployment/test notes covering 600-face background update, realtime punch during update, replace failure rollback, delete during queued removal, duplicate cursor and ACK failure.
- [x] Build cumulative ZIP and SHA256; verify ZIP CRC and file hashes.

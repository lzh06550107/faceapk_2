# Face SDK Realtime-Priority Background Update Design

Date: 2026-09-02
Baseline: FaceAPK V3 cumulative employee/face sync optimization
Package: `com.punch.app`

## 1. Goal

Allow employee/person face updates to run as a true background task without disabling normal punching, while preserving Baidu Face SDK correctness: realtime recognition must have scheduling priority, runtime face-library writes must never execute concurrently with `FaceSearch.search()`, and `/v3/handheld/event/result` must not ACK a person event until all face changes for that event have actually finished.

## 2. SDK safety constraints

The Baidu Android offline SDK documentation states that `pushPersonById()` / `pushPersonFeatureList()` must not execute concurrently with `search()` because registration can disturb the underlying feature-list ordering and cause mismatched/incorrect recognition results. The application therefore keeps a single effective native SDK execution lane.

V4 does not introduce multiple `BDFaceInstance` pipelines. HTTP download, SHA verification, JSON parsing and SQLite work may run concurrently; `FaceDetect`, `FaceFeature`, `FaceSearch.search`, runtime push/remove and rebuild remain serialized through the application scheduler plus the existing `FaceSdkOperationGuard` safety net.

## 3. Current problems

1. `SyncCoordinator.syncEmployeesInternal()` calls `PunchApplication.beginPunchDataPreparation()` before employee sync. This forces `punchDataReady=false`, so `PunchFragment.processFrame()` refuses recognition during the whole person event.
2. `FaceSdkOperationGuard` is a plain synchronized mutex. It guarantees serialization but has no realtime/background priority.
3. Runtime replacement currently deletes the old cached feature/runtime face before the replacement is proven usable. A download, detect, feature or push failure can temporarily remove a previously usable employee.
4. Heartbeat event handling executes the whole event and ACK inline inside the heartbeat executor, so large face batches extend the heartbeat cycle.
5. Repeated heartbeat delivery of the same cursor can create duplicate work unless event execution is tracked independently.

## 4. Target architecture

### 4.1 FaceSdkScheduler

Create a singleton `FaceSdkScheduler` with one worker thread and two primary queues:

- `REALTIME`: camera/bitmap recognition and other user-facing recognition work.
- `BACKGROUND`: single-employee register/update/remove work.
- `MAINTENANCE`: full rebuild/config maintenance work, lowest priority.

The scheduler is non-preemptive at the native call level. If one background task is already running, a realtime request waits only for that single task to finish. Before starting the next background task, the worker always checks realtime work first.

To prevent starvation, after a bounded burst of realtime work, one waiting background task may run if it has waited longer than the scheduler aging threshold. First-version constants are internal and covered by tests, not user-configurable.

### 4.2 Existing guard remains

`FaceSdkOperationGuard` stays around every native execution as a final mutual-exclusion guard. The scheduler determines order and priority; the guard prevents accidental unscheduled native concurrency from future code.

### 4.3 FaceManager API mapping

- `recognizeFromNv21()` -> scheduler REALTIME
- `recognizeFromBitmap()` -> scheduler REALTIME
- `registerFace()` / replacement -> scheduler BACKGROUND
- `removeFace()` -> scheduler BACKGROUND
- `rebuildFaceLibrarySync()` -> scheduler MAINTENANCE
- SDK init remains outside the runtime scheduler until initialization is complete.

Synchronous public methods may wait for their submitted task result, preserving existing callers while moving actual native execution to the scheduler worker.

## 5. Background employee/face event flow

`HeartbeatManager` keeps its existing schedule. `SyncCoordinator.runHeartbeatCycle()` only fetches heartbeat, queues punch sync, and submits platform events to a dedicated `PlatformEventExecutor`; it does not wait for long-running face work.

`PlatformEventExecutor` owns cursor state in memory:

- `RUNNING`: event body is executing.
- `ACK_PENDING`: event body completed and has a stable outcome, but result POST has not succeeded.

If the same cursor is seen again while `RUNNING`, it is not started twice. If seen in `ACK_PENDING`, only the stored ACK is retried. After successful ACK the entry is removed. Process restart may cause re-execution; employee sync and face delta handling must remain idempotent.

`event/result` continues to be strong-consistency ACK: queueing a background face job does not count as success. ACK occurs only after every face add/update/remove required by that cursor has completed or produced an explicit failure result.

## 6. Punch readiness state

Split startup readiness from background update state.

- `punchRecognitionReady`: whether realtime recognition can safely run.
- `faceLibraryUpdating`: whether a normal background face event is in progress.

Normal `person_changed` must not set `punchRecognitionReady=false` when an already usable runtime face library exists. Startup SDK initialization, first library preparation and explicit recovery rebuild may still disable punching.

Background sync may publish progress/status messages without calling `beginPunchDataPreparation()` or `updatePunchDataPreparationStatus()`.

## 7. Single-employee face update transaction

### 7.1 Add

1. Download/verify the image outside the SDK scheduler.
2. Submit one BACKGROUND SDK task.
3. Detect/extract feature.
4. Push into FaceSearch.
5. Persist feature cache and mark `face_registered=1` only after runtime push succeeds.

### 7.2 Replace

Do not delete the old face or old `face_features` record when the delta is first discovered.

1. Keep old runtime face active while downloading the new image.
2. In one BACKGROUND SDK task, extract the new feature first.
3. Snapshot the reusable old feature from SQLite before mutating runtime state.
4. Delete old runtime entry only after new feature extraction succeeds.
5. Push the new feature.
6. On success, replace persisted feature cache and registration metadata.
7. If new push fails after delete, best-effort push the old cached feature back and keep the employee marked usable when rollback succeeds. Report the replacement operation as failed so the server does not receive a false success.

### 7.3 Remove/disable/delete

SQLite desired state becomes inactive immediately. Runtime removal is a BACKGROUND SDK task. Recognition must revalidate employee eligibility after FaceSearch match so a deleted/disabled employee cannot punch during the short interval before the runtime feature is removed.

## 8. Recognition post-search eligibility gate

After stable FaceSearch match and before snapshot/punch transaction, `PunchFragment` reloads the employee and rejects if any current business state makes the employee ineligible, including `is_deleted != 0`, disabled face status, or non-normal employee status according to existing production status rules.

This gate protects the interval between SQLite desired-state application and delayed background FaceSearch deletion.

## 9. Failure semantics

- Employee metadata sync failure: event fails; no success ACK.
- Face SDK unavailable while an already usable library exists: per-face tasks fail; punching stays available with current library.
- Replacement download/feature failure: old runtime face remains; replacement result fails.
- Replacement push failure with successful rollback: old face remains; replacement result fails.
- Replacement push failure and rollback failure: employee runtime face is unavailable; result fails and an error status is logged.
- ACK HTTP failure: cursor enters/remains `ACK_PENDING`; do not rerun the event body while process remains alive.

## 10. Startup/recovery behavior

Startup preparation keeps current conservative behavior: SDK must initialize and a usable runtime library must be prepared before `punchRecognitionReady=true`. Full rebuild remains a maintenance/recovery operation and may block realtime recognition because it uses `featureClear()`.

Normal `person_changed` must never call full rebuild.

## 11. Observability

Add structured logs for:

- scheduler task type, queue wait, execution time;
- realtime queue depth/background queue depth;
- background face progress completed/total;
- replacement rollback success/failure;
- cursor state transitions `RUNNING -> ACK_PENDING -> ACKED`;
- duplicate cursor suppression.

Do not log raw face feature bytes, raw image data or authentication tokens.

## 12. Acceptance criteria

1. During a 600-face person event, `PunchFragment` remains recognition-ready once the initial application startup library is usable.
2. `FaceSearch.search()` and runtime push/remove/clear never execute concurrently.
3. A realtime recognition queued while background registration is pending runs before the next background employee update, except the bounded anti-starvation rule.
4. A slow face-image download does not block realtime recognition.
5. Replacing an employee face keeps the old face usable until the new feature is ready; push failure attempts rollback.
6. Deleted/disabled employees cannot create a new punch even if their runtime FaceSearch entry has not yet been removed.
7. Heartbeat fetching continues while a long person event is running.
8. Duplicate heartbeat cursor does not start duplicate face work in-process.
9. Event ACK is sent only after face work finishes; ACK retry does not rerun completed face work.
10. Existing V1-V3 incremental sync, feature cache, 4-way download and completion-order behavior remain intact.

# Face Runtime Availability V4.1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Keep realtime punching available during large face preparation batches whenever the native runtime library already contains usable faces, while eliminating duplicate rebuild validation and repeated feature extraction.

**Architecture:** Add a pure Java runtime-availability policy and validation-batch coalescer, integrate them at `PunchApplication` / `FaceRegistrationManager`, and make rebuild validation exact-cache-first. Preserve the V4 single Native worker and global Face SDK guard.

**Tech Stack:** Java 8 Android app, SQLite feature cache, Baidu Face SDK, Python static regression tests, JVM unit tests in `app/src/test`.

**Spec:** `docs/superpowers/specs/2026-09-02-face-runtime-availability-v4-1-design.md`

## Global Constraints

- Keep one `BDFaceInstance`; do not add concurrent Native Face SDK execution.
- Keep `FaceSdkScheduler` and `FaceSdkOperationGuard` behavior unchanged.
- Do not change database schema or `Constants.DB_VERSION`.
- Do not change stable-match frame count / 1500ms window.
- Do not modify heartbeat/event ACK architecture.

---

### Task 1: Runtime availability gate

**Files:**
- Create: `app/src/main/java/com/punch/app/face/FaceRuntimeAvailabilityPolicy.java`
- Create: `app/src/test/java/com/punch/app/face/FaceRuntimeAvailabilityPolicyTest.java`
- Modify: `app/src/main/java/com/punch/app/face/FaceManager.java`
- Modify: `app/src/main/java/com/punch/app/PunchApplication.java`

**Interfaces:**
- Produces: `FaceRuntimeAvailabilityPolicy.isUsable(boolean sdkInitialized, int loadedFaceCount)`
- Produces: `FaceManager.isRuntimeFaceLibraryUsable()`

- [x] Write failing policy tests for initialized/uninitialized and loaded count zero/nonzero.
- [x] Run a standalone JVM harness and confirm RED because the policy class is missing.
- [x] Implement the policy and `FaceManager.isRuntimeFaceLibraryUsable()`.
- [x] Change `PunchApplication.isPunchRecognitionReady()` to use actual runtime availability as the recognition gate.
- [x] Re-run the standalone JVM harness and V4 static tests.

### Task 2: Degraded-ready timeout outcome

**Files:**
- Create: `app/src/main/java/com/punch/app/service/FacePreparationReadinessPolicy.java`
- Create: `app/src/test/java/com/punch/app/service/FacePreparationReadinessPolicyTest.java`
- Modify: `app/src/main/java/com/punch/app/service/SyncCoordinator.java`

**Interfaces:**
- Produces: `FacePreparationReadinessPolicy.shouldRemainReady(boolean outcomeCompleted, int loadedFaceCount)`

- [x] Write failing tests proving incomplete outcome + loaded faces remains ready, but incomplete + zero faces fails.
- [x] Run standalone JVM harness and confirm RED.
- [x] Implement policy.
- [x] Update `publishPreparationOutcome()` so incomplete preparation with a usable rebuilt library marks recognition ready and emits an error/warning event instead of setting not-ready.
- [x] Re-run harness and static tests.

### Task 3: Coalesce duplicate rebuild-validation batches

**Files:**
- Create: `app/src/main/java/com/punch/app/face/FaceValidationBatchCoordinator.java`
- Create: `app/src/test/java/com/punch/app/face/FaceValidationBatchCoordinatorTest.java`
- Modify: `app/src/main/java/com/punch/app/face/FaceRegistrationManager.java`

**Interfaces:**
- Produces: coordinator submissions where `isOwner()` distinguishes new work from joined work, and `complete()` returns all listeners for one batch.

- [x] Write failing tests: subset joins covering batch; superset creates another batch; completed batch no longer absorbs later requests.
- [x] Run standalone JVM harness and confirm RED.
- [x] Implement coordinator as a synchronized pure Java class.
- [x] Route only `validateEmployeesForRebuild()` through it; preserve `registerEmployees()` semantics.
- [x] Log validation batch new/join/complete counts.
- [x] Re-run harness and static tests.

### Task 4: Exact-cache-first rebuild validation

**Files:**
- Create: `app/src/main/java/com/punch/app/face/FaceValidationCachePolicy.java`
- Create: `app/src/test/java/com/punch/app/face/FaceValidationCachePolicyTest.java`
- Modify: `app/src/main/java/com/punch/app/face/FaceRegistrationManager.java`

**Interfaces:**
- Produces: `FaceValidationCachePolicy.canReuse(byte[] exactFeature)`.

- [x] Write failing tests for exact 512-byte feature reuse and null/wrong-length rejection.
- [x] Run standalone JVM harness and confirm RED.
- [x] Implement policy.
- [x] Before download in rebuild validation, query exact reusable feature; on hit update face registration and return success without network/Native work.
- [x] Add batch cache-hit/native-processing metrics.
- [x] Re-run harness and static tests.

### Task 5: Regression gates and packaging

**Files:**
- Modify: `scripts/tests/test_face_scheduler_static.py`
- Create: `docs/FACE-RUNTIME-AVAILABILITY-V4.1.md`
- Modify: `README_PATCH.md`

**Interfaces:**
- Static gates prove runtime availability, degraded-ready timeout, batch coalescing, cache-first validation, and unchanged scheduler/guard boundaries.

- [x] Add static tests and first run them against baseline to confirm RED.
- [x] Run all static tests against V4.1 and confirm GREEN.
- [x] Verify no `BDFaceInstance` / scheduler-priority / stable-match constants changed.
- [x] Attempt Gradle verification only if a complete wrapper exists; otherwise report the existing wrapper limitation.
- [x] Package a V4.1 cumulative patch and V4.1-only patch with SHA256 manifests.

# Face Incremental Search V3 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Replace heartbeat `person_changed` full FaceSearch rebuilds with per-employee add/replace/delete updates while preserving full rebuild for preparation/startup/recovery.

**Architecture:** Keep one authoritative `FaceSearch` behind `faceLibraryLock`. Event-mode face registration extracts on the BG SDK lane, then atomically replaces the employee's runtime feature by deleting an already-loaded stable sdkId before `pushPersonById`; deletes/disabled employees remove the runtime feature immediately. Preparation mode keeps the existing validation + full rebuild path.

**Tech Stack:** Android Java 8, SQLite, Baidu Offline Face SDK 8.6, Python static harness.

**Spec:** `docs/superpowers/specs/2026-09-03-face-rt-bg-sdk-isolation-design.md` plus approved V2/V3 continuation in this conversation.

## Global Constraints

- Continue from the V2 RT/BG + `face_features` worktree, not the original FULL ZIP.
- Keep exactly one `FaceSearch`; do not add a second runtime search database.
- `pushPersonById` IDs are unique; replacement must not push a duplicate ID without deleting the loaded prior feature first.
- Keep `face_sdk_ids` stable across employee soft deletes; do not recycle sdkIds.
- Event-mode employee sync must not call `rebuildFinalFaceLibrary()`.
- Preparation/startup/recovery and explicit local rebuild retain `rebuildFaceLibrarySync()`.
- Failed changed-face registration must not leave the stale old runtime feature active.
- Disabled/deleted employees must be removed from runtime FaceSearch immediately.
- No schema change in V3.

---

### Task 1: Define incremental FaceSearch replacement semantics

**Files:**
- Modify: `scripts/tests/test_harness_static.py`
- Modify: `app/src/main/java/com/punch/app/face/FaceManager.java`

**Interfaces:**
- Consumes: existing `registerFace(Context,String,String)`, stable `DatabaseHelper.getOrCreateFaceSdkId`, `faceLibraryLock`.
- Produces: replacement-safe runtime registration and accurate `loadedFaceCount`.

- [x] Add failing static tests requiring registration to delete an already-loaded sdkId before pushing the replacement, check `pushPersonById` return code, update maps only after successful push, increment count only for newly loaded employees, and decrement count in `removeFace`.
- [x] Run focused tests and confirm RED.
- [x] Implement the minimal replacement-safe commit inside `FaceManager.registerFaceInternal` and count maintenance in `removeFace`.
- [x] Re-run focused tests and confirm GREEN.

### Task 2: Make event-mode employee sync incremental

**Files:**
- Modify: `scripts/tests/test_harness_static.py`
- Modify: `app/src/main/java/com/punch/app/service/SyncCoordinator.java`
- Modify: `app/src/main/java/com/punch/app/face/FaceRegistrationManager.java`

**Interfaces:**
- Consumes: `FaceRegistrationManager.registerEmployees(...)`, `FaceManager.removeFace(...)`.
- Produces: event-mode changed employees directly modify runtime FaceSearch without a full rebuild.

- [x] Add failing static tests requiring event-mode registration to use runtime registration, event-mode path to omit `rebuildFinalFaceLibrary`, deletes/disabled employees to call `removeFace`, and failed runtime registrations to remove stale runtime faces.
- [x] Add a failing regression contract requiring reactivation of a soft-deleted employee to force face re-registration even if image metadata is unchanged.
- [x] Run focused tests and confirm RED.
- [x] Add an event-mode/runtime-registration flag to `waitForFaceRegistration` without changing preparation-mode behavior.
- [x] Remove event-mode final full rebuild; keep preparation mode full rebuild.
- [x] Remove runtime faces for successful delete/disabled transitions and for failed runtime registration.
- [x] Treat `existing.isDeleted != 0` as a face membership change so reactivation becomes a registration target.
- [x] Re-run focused tests and confirm GREEN.

### Task 3: Regression and packaging

**Files:**
- Modify: `docs/superpowers/plans/2026-09-03-face-incremental-search-v3.md` only for checked task state if desired.
- Create delivery verification report and cumulative patch outside source tree.

**Interfaces:**
- Produces: cumulative V1+V2+V3 patch from the original 2026-08-31 FULL and a complete V3 source ZIP.

- [x] Run V1/V2/V3 focused static tests.
- [x] Run full `python -m unittest scripts.tests.test_harness_static -v`.
- [x] Attempt Gradle validation and report the exact environment blocker if wrapper/SDK remains unavailable.
- [x] Inspect diffs against V2 and original FULL; confirm only intended production/test/doc files changed.
- [x] Package cumulative patch and full source; run ZIP integrity and SHA-256 verification.

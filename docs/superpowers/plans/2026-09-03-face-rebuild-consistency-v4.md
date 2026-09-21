# FaceSearch Full Rebuild Consistency V4 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Full Rebuild publish a usable FaceSearch only after every native mutation and final native size are verified.

**Architecture:** Keep feature preparation outside the search lock, then perform a fail-closed native commit under `faceLibraryLock`. Track explicit runtime library state and reject recognition whenever the verified state is not READY.

**Tech Stack:** Android Java, Baidu offline Face SDK 8.x AAR, Python `unittest` static contract harness.

**Spec:** `docs/superpowers/specs/2026-09-03-face-rebuild-consistency-v4-design.md`

## Global Constraints

- Preserve V1 RT/BG SDK object isolation.
- Preserve V2 `face_features` persistence and cache-first Full Rebuild preparation.
- Preserve V3 event-mode incremental FaceSearch updates.
- Keep exactly one runtime `FaceSearch` and the existing `faceLibraryLock` mutation/search boundary.
- Do not modify DB schema or `SyncService` in V4.

---

### Task 1: Add failing V4 Full Rebuild consistency contracts

**Files:**
- Modify: `scripts/tests/test_harness_static.py`

**Interfaces:**
- Consumes: existing `FaceManager.java` structure.
- Produces: `FaceRebuildConsistencyV4ContractTest`.

- [ ] Add tests asserting runtime state, fail-closed recognition, checked clear/push/native-size verification, delayed map/count publication, and failure cleanup.
- [ ] Run only `FaceRebuildConsistencyV4ContractTest`; verify RED because V3 lacks these protections.

### Task 2: Implement verified Full Rebuild commit

**Files:**
- Modify: `app/src/main/java/com/punch/app/face/FaceManager.java`

**Interfaces:**
- Produces: `FaceLibraryState`, `isFaceLibraryReady()`, verified `rebuildFaceLibrarySync()`, fail-closed `doSearch()`.

- [ ] Add `FaceLibraryState` and state accessor.
- [ ] Track expected entries and fail preparation if a registered face cannot produce an entry.
- [ ] Commit with checked `featureClear()` and checked per-entry `pushPersonById()`.
- [ ] Verify `FaceSearch.getSize()` before publishing Java maps/count.
- [ ] Add one failure-cleanup helper that clears native/runtime state and sets `REBUILD_FAILED`.
- [ ] Gate `doSearch()` on READY inside `faceLibraryLock`.
- [ ] Run V4 focused tests; verify GREEN.

### Task 3: Run regression and packaging gates

**Files:**
- Modify: `docs/superpowers/plans/2026-09-03-face-rebuild-consistency-v4.md` only for checkbox completion if needed.

**Interfaces:**
- Consumes: final V4 tree.
- Produces: regression evidence and distributable cumulative patch/full ZIP.

- [ ] Run V1+V2+V3+V4 focused contracts.
- [ ] Run the full static harness.
- [ ] Attempt Gradle unit/debug/release/lint gate and report environment blockers exactly.
- [ ] Diff V3→V4 and original→V4.
- [ ] Build a cumulative patch that applies directly to the original 2026-08-31 FULL baseline and simulate overlay equality.

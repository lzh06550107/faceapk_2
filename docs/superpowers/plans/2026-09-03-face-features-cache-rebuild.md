# Face Features Cache Rebuild Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Reuse the existing `face_features` SQLite table as the persistent Face SDK feature cache so full library rebuilds avoid re-decoding and re-extracting unchanged employee face images.

**Architecture:** Keep the V1 RT/BG SDK isolation unchanged. Persist each successful BG-extracted 512-byte feature with the employee face version, image SHA-256, and feature schema version; during rebuild, load a feature only when all metadata matches the current employee, otherwise regenerate it with the BG engine and refresh the cache. `FaceSearch` remains a single authoritative runtime index protected by the existing `faceLibraryLock`.

**Tech Stack:** Android Java 8, SQLiteOpenHelper, Baidu offline Face SDK, Python static contract tests.

**Spec:** `docs/superpowers/specs/2026-09-03-face-rt-bg-sdk-isolation-design.md` plus the approved follow-up requirement to reuse the existing `face_features` table shown on-device.

## Global Constraints

- Use the modified 2026-08-31 FULL V1 RT/BG workspace as the implementation base.
- Keep `employees` and `face_features` separate; do not add the feature BLOB to `employees`.
- Preserve one `FaceSearch`; do not add a second search instance.
- Preserve `faceLibraryLock` scope around search-library mutations only.
- Preserve the V1 dedicated BG `BDFaceInstance + FaceDetect + FaceFeature` lane.
- Database schema changes require a `Constants.DB_VERSION` bump and forward migration.
- Do not depend on clearing application data during upgrade.
- Do not log feature bytes or full biometric data.

---

### Task 1: Add face_features persistence contract

**Files:**
- Modify: `scripts/tests/test_harness_static.py`
- Modify: `app/src/main/java/com/punch/app/utils/Constants.java`
- Modify: `app/src/main/java/com/punch/app/db/DatabaseHelper.java`

**Interfaces:**
- Produces: `DatabaseHelper.getValidFaceFeature(String,int,String,int): byte[]`
- Produces: `DatabaseHelper.upsertFaceFeature(String,int,String,int,byte[]): boolean`
- Produces: schema `face_features(emp_id, face_version, image_sha256, feature_schema_version, feature, updated_at)`

- [x] **Step 1: Write failing static contract tests** that require DB version 10, table creation in `onCreate` and `onUpgrade`, exact table columns, a metadata-matching read API, a 512-byte validated upsert API, and cleanup on hard employee deletion/full local business clear.
- [x] **Step 2: Run the focused static test class** and verify it fails because the V1 source has no `face_features` support.
- [x] **Step 3: Implement minimal schema/migration and CRUD methods.** Use `CREATE TABLE IF NOT EXISTS`, `emp_id TEXT PRIMARY KEY`, feature metadata columns, `feature BLOB NOT NULL`, `updated_at INTEGER NOT NULL DEFAULT 0`, and `SQLiteDatabase.CONFLICT_REPLACE`. Reject writes whose feature length is not 512; reject reads whose BLOB length is not 512.
- [x] **Step 4: Re-run focused tests** and verify Task 1 is green.

### Task 2: Persist features after successful validation/registration

**Files:**
- Modify: `scripts/tests/test_harness_static.py`
- Modify: `app/src/main/java/com/punch/app/face/FaceManager.java`

**Interfaces:**
- Consumes: `DatabaseHelper.upsertFaceFeature(...)`
- Produces: every successful `registerFace()` or `validateFaceImage()` refreshes the cache using the current `Employee.faceVersion` and `Employee.faceImageSha256`.

- [x] **Step 1: Write failing static tests** requiring `FaceManager` to define feature schema version 1 and persist the extracted feature before returning successful registration/validation.
- [x] **Step 2: Run focused tests** and verify failure against V1.
- [x] **Step 3: Implement minimal persistence helper** in `FaceManager`; fetch the current employee, use its face metadata, and treat cache persistence as best-effort: log a warning on write failure while preserving the existing image-based rebuild fallback.
- [x] **Step 4: Re-run focused tests** and verify green.

### Task 3: Rebuild from cached feature first, BG extraction only as fallback

**Files:**
- Modify: `scripts/tests/test_harness_static.py`
- Modify: `app/src/main/java/com/punch/app/face/FaceManager.java`

**Interfaces:**
- Consumes: `DatabaseHelper.getValidFaceFeature(...)`
- Consumes: V1 `extractFeatureFromFile()` BG engine fallback.
- Produces: `rebuildFaceLibrarySync()` loads matching cached features without decoding images; fallback regeneration refreshes the cache before the `FaceSearch` commit.

- [x] **Step 1: Write failing static tests** requiring cache lookup before `FaceFileManager.getFaceImagePath` / `extractFeatureFromFile`, fallback-only extraction, cache refresh after fallback, and rebuild observability counters for cached/regenerated features.
- [x] **Step 2: Run focused tests** and verify failure against V1.
- [x] **Step 3: Implement minimal cache-first rebuild** while preserving the two-phase design: prepare entries outside `faceLibraryLock`, then clear/push under the lock.
- [x] **Step 4: Re-run focused tests** and verify green.

### Task 4: Regression verification and packaging

**Files:**
- Verify: `scripts/tests/test_harness_static.py`
- Package modified source and incremental patch.

**Interfaces:**
- Produces: V2 source archive, incremental patch archive, verification report, SHA-256 manifest.

- [x] **Step 1: Run the full Python static harness** and require zero failures.
- [x] **Step 2: Verify SDK/object isolation contracts from V1 remain green.**
- [x] **Step 3: Attempt Gradle unit/build verification; report environment blockers exactly if wrapper/Android SDK are unavailable.**
- [x] **Step 4: Inspect changed files and package only intended V2 changes plus V1 base.**

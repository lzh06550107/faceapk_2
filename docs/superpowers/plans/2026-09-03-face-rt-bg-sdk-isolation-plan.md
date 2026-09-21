# Face RT/BG SDK Isolation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Separate realtime camera recognition from background face-image validation/feature extraction by giving background work its own `BDFaceInstance`, `FaceDetect`, and `FaceFeature`, while preserving the single authoritative `FaceSearch` and existing business behavior.

**Architecture:** Keep the current realtime SDK objects unchanged. Add one background SDK instance and one background detect/feature pair inside `FaceSDKManager`, serialize all background native operations with a private lock, and route `FaceManager.extractFeatureFromFile()` through a narrow background extraction API. Keep `FaceSearch` and `faceLibraryLock` unchanged.

**Tech Stack:** Android Java 8, Baidu offline Face SDK 8.5/8.6-compatible API, Python `unittest` static contract tests, Gradle 8.0.

**Spec:** `docs/superpowers/specs/2026-09-03-face-rt-bg-sdk-isolation-design.md`

## Global Constraints

- Use `faceapk-current-FULL-20260831` as the only production source baseline.
- Do not add a second `FaceSearch`.
- Do not change SQLite schema, employee sync protocol, punch behavior, or `SyncService.triggerSync()`.
- Realtime `FaceDetect`/`FaceFeature` remain the existing objects in V1.
- Background `FaceDetect`/`FaceFeature` must share one dedicated `BDFaceInstance` and must never run concurrently with another BG operation.
- `FaceSearch.search/push/delete/clear` remain protected by the existing `faceLibraryLock`.
- Do not perform device install/uninstall/Device Owner operations in this task.

---

### Task 1: Add a failing RT/BG isolation contract test

**Files:**
- Modify: `scripts/tests/test_harness_static.py`

**Interfaces:**
- Consumes: current Java source text.
- Produces: `FaceRtBgSdkIsolationContractTest` that proves the required object ownership and call routing.

- [ ] **Step 1: Add static source-contract tests**

Add tests that require all of the following:

```text
FaceSDKManager imports BDFaceInstance
backgroundFaceInstance/backgroundFaceDetect/backgroundFaceFeature exist
backgroundFaceOperationLock exists
backgroundFaceInstance.creatInstance() is called
FaceDetect(backgroundFaceInstance) and FaceFeature(backgroundFaceInstance) are used
initCoreModels waits for 6 model callbacks
isModelReady checks BG objects
extractBackgroundFeature(BDFaceImageInstance) exists and synchronizes on the BG gate
FaceManager.extractFeatureFromFile() calls extractBackgroundFeature(inst)
FaceManager.extractFeatureFromFile() no longer calls RT detect/feature getters
FaceManager realtime recognition still calls the RT getters
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
python3 -m unittest scripts.tests.test_harness_static.FaceRtBgSdkIsolationContractTest -v
```

Expected: FAIL because the BG SDK objects/API do not exist yet.

---

### Task 2: Add the dedicated BG SDK engine and initialization gate

**Files:**
- Modify: `app/src/main/java/com/punch/app/face/FaceSDKManager.java`

**Interfaces:**
- Consumes: `BDFaceSDKConfig`, current model constants, existing init callback machinery.
- Produces:
  - `byte[] extractBackgroundFeature(BDFaceImageInstance imageInstance)`
  - `void loadBackgroundConfig(BDFaceSDKConfig config)`
  - dedicated BG `BDFaceInstance`/`FaceDetect`/`FaceFeature` owned by `FaceSDKManager`.

- [ ] **Step 1: Create BG object ownership fields**

Add:

```java
private BDFaceInstance backgroundFaceInstance;
private FaceDetect backgroundFaceDetect;
private FaceFeature backgroundFaceFeature;
private final Object backgroundFaceOperationLock = new Object();
```

- [ ] **Step 2: Construct BG objects with one explicit instance**

Inside `createFaceEngines(...)` after computing `sdkConfig`:

```java
backgroundFaceInstance = new BDFaceInstance();
backgroundFaceInstance.creatInstance();
backgroundFaceDetect = new FaceDetect(backgroundFaceInstance);
backgroundFaceDetect.loadConfig(sdkConfig);
backgroundFaceFeature = new FaceFeature(backgroundFaceInstance);
```

Keep the existing RT and `FaceSearch` construction unchanged.

- [ ] **Step 3: Include BG models in init readiness**

Change the pending model count from `4` to `6`. Initialize `backgroundFaceDetect` with the same detect/alignment models and `backgroundFaceFeature` with the same feature models as RT. Use the existing `handleInitCallback(...)` with distinct stage names `backgroundDetect` and `backgroundFeature`.

- [ ] **Step 4: Extend `isModelReady()`**

Require non-null BG instance/detect/feature in addition to the existing RT/Search/Live/Mask objects and `initModelSuccess`.

- [ ] **Step 5: Add the narrow BG extraction/config API**

Implement:

```java
public byte[] extractBackgroundFeature(BDFaceImageInstance imageInstance)
```

Behavior:

```text
null image or BG not ready -> null
lock backgroundFaceOperationLock
BG detect DETECT_VIS
no face -> null
BG feature LIVE_PHOTO using first face landmarks
feature result <= 0 -> null
success -> return 512-byte feature
```

Also implement:

```java
public void loadBackgroundConfig(BDFaceSDKConfig config)
```

under the same lock.

- [ ] **Step 6: Run focused static contract test**

Run the same focused Python unittest. Expected: remaining failures only for `FaceManager` routing until Task 3.

---

### Task 3: Route file-based validation/registration/rebuild to BG engine

**Files:**
- Modify: `app/src/main/java/com/punch/app/face/FaceManager.java`

**Interfaces:**
- Consumes: `FaceSDKManager.extractBackgroundFeature(...)`, `FaceSDKManager.loadBackgroundConfig(...)`.
- Produces: all file-based face feature extraction uses the BG engine; realtime recognition remains unchanged.

- [ ] **Step 1: Update runtime config propagation**

Keep the existing RT `loadConfig(buildSdkConfig())`, but compute one config value and also call:

```java
FaceSDKManager.getInstance().loadBackgroundConfig(sdkConfig);
```

- [ ] **Step 2: Replace file extraction SDK calls**

In `extractFeatureFromFile(...)`, keep bitmap decode, `BDFaceImageInstance` lifetime, and business logging. Replace direct RT detect/feature calls with:

```java
byte[] feature = FaceSDKManager.getInstance().extractBackgroundFeature(inst);
```

Return `null` with the existing invalid-image logging semantics when extraction fails.

- [ ] **Step 3: Verify realtime paths remain RT**

Do not modify `recognizeFromNv21()` or `recognizeFromBitmap()` SDK getter usage.

- [ ] **Step 4: Run focused contract test**

Expected: PASS.

---

### Task 4: Regression and build verification

**Files:**
- No production changes unless verification identifies a regression caused by Tasks 2-3.

**Interfaces:**
- Produces: local evidence that source contracts and Android compilation remain valid.

- [ ] **Step 1: Run complete static harness tests**

```bash
python3 -m unittest scripts.tests.test_harness_static -v
```

Expected: PASS.

- [ ] **Step 2: Run JVM unit tests**

```bash
./gradlew testDebugUnitTest
```

Expected: PASS, subject to environment SDK/toolchain availability.

- [ ] **Step 3: Build debug and release**

```bash
./gradlew assembleDebug assembleRelease
```

Expected: PASS.

- [ ] **Step 4: Run lint**

```bash
./gradlew lintDebug
```

Expected: either PASS or only the documented pre-existing debug manifest missing `UiTestSetupWizardHostActivity` issue. Do not suppress it.

- [ ] **Step 5: Review changed files**

Because this delivery copy has no `.git`, compare the work directory against `/mnt/data/faceapk-baseline-20260831/faceapk-current-FULL-20260831` with `diff` and confirm only the design, plan, test, `FaceSDKManager.java`, and `FaceManager.java` changed.


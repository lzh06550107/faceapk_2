# Face Runtime Availability V4.1 Design

## Problem

True-device V4 testing showed a 597-person preparation batch timing out while the runtime FaceSearch was successfully rebuilt with 679 faces. The application then kept punch recognition blocked because `punchDataReady=false`, so `PunchFragment` returned before submitting `recognize-nv21`. The same test also showed the timed-out validation batch continuing to run and spending almost all elapsed time in Face Detect / Face Feature, even though the final rebuild had exact cached features for all 679 faces.

## Goals

1. Recognition availability is determined by the actual runtime face library: Face SDK initialized and `loadedFaceCount > 0`.
2. Preparation/background status no longer globally disables recognition when a usable runtime library exists.
3. If preparation times out but final rebuild leaves a usable runtime library, publish a degraded-ready state instead of failed/not-ready.
4. Rebuild validation batches with the same or subset employee IDs join an existing queued/running batch instead of enqueueing duplicate work.
5. Rebuild validation checks exact reusable feature cache before downloading or running Detect / Feature. Cache hits mark the employee face as validated for the desired version/SHA and skip native preprocessing.
6. Keep one `BDFaceInstance`, the V4 `FaceSdkScheduler`, and `FaceSdkOperationGuard`; do not introduce Native concurrency.

## Non-goals

- No dual `BDFaceInstance`.
- No changes to stable-match 2-frame / 1500ms policy.
- No changes to heartbeat/event ACK architecture.
- No database schema change.
- No changes to FaceSearch search/push/delete/clear serialization.

## Runtime availability

`FaceManager.isRuntimeFaceLibraryUsable()` is the source of truth for whether recognition may be attempted:

```
initialized == true && loadedFaceCount > 0
```

`PunchApplication.isPunchRecognitionReady()` returns true whenever that runtime condition is true, even if `punchDataPreparing=true` or the last preparation outcome timed out. `punchDataReady` remains workflow/UI status but is not allowed to override an actually usable native runtime library.

Cold start with `loadedFaceCount == 0` remains blocked until at least one face is loaded.

## Timeout outcome

After `rebuildFinalFaceLibrary()`:

- `loadedFaceCount > 0`: punch recognition is ready.
- if the validation outcome timed out/failed, show an error/warning status that background preparation was incomplete but current loaded faces remain usable.
- `loadedFaceCount == 0`: preserve failure behavior.

## Validation batch coalescing

Only `validateEmployeesForRebuild()` is coalesced. Incremental runtime registration keeps existing per-event semantics.

A queued/running validation batch records its employee ID set and listeners. A new validation request joins an existing batch when the existing batch covers every requested employee ID. This handles stale subset snapshots such as a second 597-person request arriving while an earlier 600-person batch is queued/running.

If a new request contains additional employee IDs not covered by an existing batch, it creates a separate batch. Cache-first validation makes overlap with completed earlier batches cheap.

## Cache-first validation

For `addToRuntimeLibrary=false`, before network download:

1. query `DatabaseHelper.getReusableFaceFeature(emp.id, emp.faceVersion, emp.faceImageSha256, FEATURE_CACHE_SCHEMA_VERSION)`;
2. if present, set `face_registered=1` and a deterministic local face ID (`FACE_<empId>` if needed), return success, and do not download/decode/detect/extract;
3. otherwise follow the existing download + `validateFaceImage()` path.

Exact version, SHA and schema matching remains mandatory. Fallback features are not accepted as a validation cache hit.

## Diagnostics

Add concise logs for:

- runtime availability transitions / degraded-ready timeout;
- validation batch `new`, `join`, `complete` with request size;
- `Face validation cache hit` count and batch native-processing count.

Do not log face feature bytes or image contents.

## Acceptance tests

- SDK initialized + loaded count 1 => recognition gate open even while preparation flag is true.
- SDK initialized + loaded count 0 => gate closed.
- timed-out preparation + rebuild loaded 679 => ready/degraded, not failed/not-ready.
- validation request `[A,B,C]`, then `[B,C]` => one owner batch, second joins.
- after owner completes, new `[B,C]` creates a new batch.
- exact feature cache hit skips validation native path and marks desired face registered.
- cache miss keeps existing validation path.
- existing V4 scheduler static tests remain green.

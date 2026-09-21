# FaceSearch Full Rebuild Consistency V4 Design

## Goal

Make startup/recovery Full Rebuild fail closed: the runtime FaceSearch is READY only when every prepared entry is pushed successfully and the native FaceSearch size exactly matches the expected entry count.

## Scope

- Keep V1 RT/BG `BDFaceInstance` isolation unchanged.
- Keep V2 `face_features` cache and fallback regeneration unchanged.
- Keep V3 event-mode incremental `push/delete` unchanged.
- Change only Full Rebuild runtime state, commit verification, and recognition gating.
- Do not modify `SyncService`, SDK initialization state machine, database schema, or employee sync protocol in this round.

## Runtime state

`FaceManager` owns a volatile runtime library state:

- `NOT_READY`: SDK may be initialized but no verified runtime library has been published yet.
- `REBUILDING`: a Full Rebuild commit is replacing the native search library.
- `READY`: native library, Java mappings, and `loadedFaceCount` were verified and published together.
- `REBUILD_FAILED`: the last Full Rebuild failed; recognition must fail closed until a later Full Rebuild reaches READY.

Recognition must verify `READY` inside `faceLibraryLock` immediately before `FaceSearch.search()`.

## Two-phase Full Rebuild

### Phase A: prepare outside `faceLibraryLock`

For each active employee that persistent state marks as registered and that has a face image URL:

1. Load a metadata-matched 512-byte feature from `face_features`.
2. If needed, regenerate using the BG Face SDK lane and refresh `face_features`.
3. Resolve or allocate stable `sdk_id`.
4. Build immutable `FaceLibraryEntry` values.

If an employee that should be loaded cannot produce a feature or `sdk_id`, the rebuild fails before publishing a new runtime index.

### Phase B: commit inside `faceLibraryLock`

1. Set state to `REBUILDING`.
2. `featureClear()` and require return code 0.
3. Push every prepared entry and require every `pushPersonById()` return code 0.
4. Require `FaceSearch.getSize()` to equal the number of prepared entries.
5. Only after all checks pass, replace `empToIntId` and `intToEmpId`, set `loadedFaceCount`, and set state `READY`.

## Failure behavior

On any commit/preparation failure:

- Set state to `REBUILD_FAILED`.
- Best-effort `featureClear()` the native FaceSearch under `faceLibraryLock`.
- Clear Java runtime mappings.
- Set `loadedFaceCount = 0`.
- Return `false`.
- `doSearch()` returns a dedicated "face library not ready" result while the state is not READY.

The persistent `employees`, `face_features`, and `face_sdk_ids` data are not deleted. A later Full Rebuild can reconstruct the native library.

## Incremental sync behavior

V3 event-mode incremental updates retain their current behavior. A single employee update can fail without forcing the entire app into `REBUILD_FAILED`; this strict state transition is reserved for Full Rebuild.

## Verification

Static/TDD contracts must prove:

- the explicit runtime state exists;
- recognition checks READY inside the search lock;
- Full Rebuild checks `featureClear`, each `pushPersonById`, and `getSize`;
- mappings/count are published only after native verification;
- failure cleanup sets `REBUILD_FAILED`, clears mappings/count, and returns false;
- V1/V2/V3 contracts continue to pass.

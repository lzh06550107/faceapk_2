package com.punch.app.face;

import android.content.Context;

import com.punch.app.db.DatabaseHelper;
import com.punch.app.utils.AppLogger;

import java.util.HashSet;
import java.util.Set;

/**
 * Verifies that the single runtime FaceSearch index can be explained by durable database state.
 * PENDING UPSERT/REMOVE tasks are treated as legitimate transitional differences. Any other
 * mismatch requires the existing fail-closed Full Rebuild path.
 */
public final class FaceLibraryReconciler {
    private static final String TAG = "FaceLibraryReconciler";

    private static final class Holder {
        private static final FaceLibraryReconciler INSTANCE = new FaceLibraryReconciler();
    }

    private FaceLibraryReconciler() {
    }

    public static FaceLibraryReconciler get() {
        return Holder.INSTANCE;
    }

    public ReconcileResult reconcile(Context context) {
        if (context == null) {
            return ReconcileResult.rebuild("context is null");
        }

        FaceManager.RuntimeSnapshot snapshot = FaceManager.get().snapshotRuntime();
        if (snapshot.state != FaceManager.FaceLibraryState.READY) {
            return ReconcileResult.rebuild("runtime state=" + snapshot.state);
        }
        if (snapshot.nativeSize < 0 || snapshot.nativeSize != snapshot.loadedFaceCount) {
            return ReconcileResult.rebuild(
                    "native/runtime count mismatch native=" + snapshot.nativeSize
                            + " loaded=" + snapshot.loadedFaceCount);
        }
        if (!snapshot.mappingConsistent || snapshot.employeeIds.size() != snapshot.loadedFaceCount) {
            return ReconcileResult.rebuild(
                    "runtime mapping mismatch mapped=" + snapshot.employeeIds.size()
                            + " loaded=" + snapshot.loadedFaceCount);
        }

        DatabaseHelper db = DatabaseHelper.get(context.getApplicationContext());
        Set<String> activeRegistered = db.getActiveRegisteredFaceEmployeeIds();
        Set<String> pendingUpserts = db.getPendingFaceApplyTaskEmployeeIds(
                DatabaseHelper.FaceBatchWrite.OP_UPSERT);
        Set<String> pendingRemoves = db.getPendingFaceApplyTaskEmployeeIds(
                DatabaseHelper.FaceBatchWrite.OP_REMOVE);

        Set<String> missingFeatures = db.getFaceRuntimeEmployeeIdsMissingValidFeature(
                FaceManager.get().getFeatureSchemaVersion());
        if (!missingFeatures.isEmpty()) {
            return ReconcileResult.rebuild(
                    "missing or invalid persisted face features count=" + missingFeatures.size());
        }

        // A registered employee without a pending UPSERT must already be present. A pending
        // UPSERT is transitional: the old face may still be loaded or the employee may be absent.
        Set<String> mustBeLoaded = new HashSet<>(activeRegistered);
        mustBeLoaded.removeAll(pendingUpserts);
        if (!snapshot.employeeIds.containsAll(mustBeLoaded)) {
            Set<String> missing = new HashSet<>(mustBeLoaded);
            missing.removeAll(snapshot.employeeIds);
            return ReconcileResult.rebuild("missing runtime employees count=" + missing.size());
        }

        // Runtime entries must either be durable active registrations or be explained by an
        // in-flight UPSERT/REMOVE. Anything else is stale Native/Java runtime state.
        Set<String> allowedRuntime = new HashSet<>(activeRegistered);
        allowedRuntime.addAll(pendingUpserts);
        allowedRuntime.addAll(pendingRemoves);
        if (!allowedRuntime.containsAll(snapshot.employeeIds)) {
            Set<String> unexpected = new HashSet<>(snapshot.employeeIds);
            unexpected.removeAll(allowedRuntime);
            return ReconcileResult.rebuild("unexpected runtime employees count=" + unexpected.size());
        }

        AppLogger.i(TAG, "Face runtime reconcile consistent: loaded=" + snapshot.loadedFaceCount
                + " native=" + snapshot.nativeSize
                + " registered=" + activeRegistered.size()
                + " pendingUpsert=" + pendingUpserts.size()
                + " pendingRemove=" + pendingRemoves.size());
        return ReconcileResult.consistent();
    }

    public static final class ReconcileResult {
        private final boolean consistent;
        public final String reason;

        private ReconcileResult(boolean consistent, String reason) {
            this.consistent = consistent;
            this.reason = reason == null ? "" : reason;
        }

        public static ReconcileResult consistent() {
            return new ReconcileResult(true, "");
        }

        public static ReconcileResult rebuild(String reason) {
            return new ReconcileResult(false, reason);
        }

        public boolean isConsistent() {
            return consistent;
        }
    }
}

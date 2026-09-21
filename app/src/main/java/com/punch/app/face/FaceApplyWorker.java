package com.punch.app.face;

import android.content.Context;

import com.punch.app.db.DatabaseHelper;
import com.punch.app.model.Employee;
import com.punch.app.utils.AppLogger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Applies durable face desired-state tasks to the single runtime FaceSearch instance.
 * Server acknowledgement never waits for this worker. Retryable runtime failures use
 * capped backoff; permanent data errors stay durable as FAILED until newer desired state
 * replaces the task.
 */
public final class FaceApplyWorker {
    private static final String TAG = "FaceApplyWorker";
    private static final long[] RETRY_DELAYS_MS = {
            1_000L,
            5_000L,
            30_000L,
            60_000L,
            300_000L
    };

    private static final class Holder {
        private static final FaceApplyWorker INSTANCE = new FaceApplyWorker();
    }

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean queued = new AtomicBoolean(false);
    private final AtomicBoolean rerunRequested = new AtomicBoolean(false);
    private final Object scheduleLock = new Object();
    private ScheduledFuture<?> scheduledRetry;
    private long scheduledRetryAt;

    private FaceApplyWorker() {
    }

    public static FaceApplyWorker get() {
        return Holder.INSTANCE;
    }

    public void trigger(Context context) {
        if (context == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        rerunRequested.set(true);
        cancelScheduledRetry();
        if (!queued.compareAndSet(false, true)) {
            return;
        }
        executor.execute(() -> runQueued(appContext));
    }

    private void runQueued(Context context) {
        try {
            do {
                rerunRequested.set(false);
                drain(context);
            } while (rerunRequested.get());
        } finally {
            queued.set(false);
            if (rerunRequested.get()) {
                trigger(context);
            } else {
                scheduleNextRetry(context);
            }
        }
    }

    private void drain(Context context) {
        DatabaseHelper db = DatabaseHelper.get(context);
        long afterTaskId = 0L;
        // Freeze eligibility for this pass. A task that fails in this drain can never become
        // eligible again in the same pass even if processing later employees takes a long time.
        long nowMs = System.currentTimeMillis();
        while (true) {
            DatabaseHelper.FaceApplyTask task = db.getNextReadyFaceApplyTask(afterTaskId, nowMs);
            if (task == null) {
                return;
            }
            afterTaskId = task.id;

            ApplyOutcome outcome = applyOne(context, db, task);
            if (outcome.success) {
                continue;
            }
            if (outcome.retryable) {
                long nextRetryAt = System.currentTimeMillis() + retryDelayMs(task.retryCount);
                db.markFaceApplyTaskRetry(task.id, outcome.error, nextRetryAt);
                AppLogger.w(TAG, "Face apply deferred: empId=" + task.empId
                        + " operation=" + task.operation
                        + " retryCount=" + (task.retryCount + 1)
                        + " nextRetryAt=" + nextRetryAt
                        + " reason=" + outcome.error);
            } else {
                db.markFaceApplyTaskPermanentFailed(task.id, outcome.error);
                AppLogger.e(TAG, "Face apply permanently failed: empId=" + task.empId
                        + " operation=" + task.operation + " reason=" + outcome.error);
            }
        }
    }

    private ApplyOutcome applyOne(Context context,
                                  DatabaseHelper db,
                                  DatabaseHelper.FaceApplyTask task) {
        FaceManager faceManager = FaceManager.get();

        if ("REMOVE".equals(task.operation)) {
            if (!faceManager.isInitialized() || !faceManager.isFaceLibraryReady()) {
                return ApplyOutcome.retry("Face runtime is not ready");
            }
            boolean removed = faceManager.removeFace(task.empId);
            if (!removed) {
                return ApplyOutcome.retry("FaceSearch remove failed");
            }
            db.completeFaceApplyTask(task.id, task.empId, null, false);
            return ApplyOutcome.success();
        }

        if (!"UPSERT".equals(task.operation)) {
            return ApplyOutcome.permanent("Unknown face apply operation: " + task.operation);
        }

        Employee employee = db.getEmployee(task.empId);
        if (employee == null || employee.isDeleted != 0) {
            return ApplyOutcome.permanent("Employee missing or deleted");
        }
        if (employee.faceVersion != task.faceVersion) {
            return ApplyOutcome.permanent("Face task version is stale");
        }

        byte[] feature = db.getValidFaceFeature(
                task.empId,
                task.faceVersion,
                employee.faceImageSha256,
                faceManager.getFeatureSchemaVersion());
        if (feature == null) {
            return ApplyOutcome.permanent("Persisted face feature is missing");
        }

        if (!faceManager.isInitialized() || !faceManager.isFaceLibraryReady()) {
            return ApplyOutcome.retry("Face runtime is not ready");
        }

        FaceManager.RegisterResult result = faceManager.applyStoredFeature(
                context, task.empId, feature);
        if (!result.success) {
            return ApplyOutcome.retry(
                    result.errorMsg == null ? "FaceSearch apply failed" : result.errorMsg);
        }
        db.completeFaceApplyTask(task.id, task.empId, result.localFaceId, true);
        return ApplyOutcome.success();
    }

    private long retryDelayMs(int retryCount) {
        int index = Math.min(Math.max(retryCount, 0), RETRY_DELAYS_MS.length - 1);
        return RETRY_DELAYS_MS[index];
    }

    private void scheduleNextRetry(Context context) {
        if (queued.get()) {
            return;
        }
        DatabaseHelper db = DatabaseHelper.get(context);
        long nextRetryAt = db.getNextFaceApplyRetryAt();
        if (nextRetryAt <= 0L) {
            return;
        }
        if (nextRetryAt <= System.currentTimeMillis()) {
            trigger(context);
            return;
        }
        scheduleRetry(context, nextRetryAt);
    }

    private void scheduleRetry(Context context, long nextRetryAt) {
        if (queued.get()) {
            return;
        }
        synchronized (scheduleLock) {
            if (scheduledRetry != null && !scheduledRetry.isDone()) {
                if (scheduledRetryAt > 0L && scheduledRetryAt <= nextRetryAt) {
                    return;
                }
                scheduledRetry.cancel(false);
            }
            long delayMs = Math.max(0L, nextRetryAt - System.currentTimeMillis());
            scheduledRetryAt = nextRetryAt;
            scheduledRetry = executor.schedule(() -> {
                synchronized (scheduleLock) {
                    scheduledRetry = null;
                    scheduledRetryAt = 0L;
                }
                trigger(context);
            }, delayMs, TimeUnit.MILLISECONDS);
        }
    }

    private void cancelScheduledRetry() {
        synchronized (scheduleLock) {
            if (scheduledRetry != null) {
                scheduledRetry.cancel(false);
                scheduledRetry = null;
                scheduledRetryAt = 0L;
            }
        }
    }

    private static final class ApplyOutcome {
        final boolean success;
        final boolean retryable;
        final String error;

        private ApplyOutcome(boolean success, boolean retryable, String error) {
            this.success = success;
            this.retryable = retryable;
            this.error = error;
        }

        static ApplyOutcome success() {
            return new ApplyOutcome(true, false, null);
        }

        static ApplyOutcome retry(String error) {
            return new ApplyOutcome(false, true, error);
        }

        static ApplyOutcome permanent(String error) {
            return new ApplyOutcome(false, false, error);
        }
    }
}

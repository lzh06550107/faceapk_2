package com.punch.app.face;

public final class PunchPreparationPolicy {
    private PunchPreparationPolicy() {
    }

    public enum Action {
        SYNC_EMPLOYEES,
        REUSE_FACE_LIBRARY,
        REBUILD_FACE_LIBRARY
    }

    public static Action decide(int activeEmployeeCount, boolean runtimeLibraryReusable) {
        if (activeEmployeeCount <= 0) {
            return Action.SYNC_EMPLOYEES;
        }
        return runtimeLibraryReusable
                ? Action.REUSE_FACE_LIBRARY
                : Action.REBUILD_FACE_LIBRARY;
    }

    public static boolean isRetryAllowed(long retryNotBeforeAtMs, long nowMs) {
        return retryNotBeforeAtMs <= 0L || nowMs >= retryNotBeforeAtMs;
    }

    public static long nextRetryAt(long nowMs, long retryDelayMs) {
        if (retryDelayMs <= 0L) {
            return nowMs;
        }
        long maxDelay = Long.MAX_VALUE - nowMs;
        return retryDelayMs >= maxDelay ? Long.MAX_VALUE : nowMs + retryDelayMs;
    }
}

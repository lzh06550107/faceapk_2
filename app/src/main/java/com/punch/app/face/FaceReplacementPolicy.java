package com.punch.app.face;

/**
 * Pure decision helpers for updating one employee's runtime face entry.
 * Native SDK calls remain in {@link FaceManager}; this class only makes the
 * add/replace/remove and rollback decisions explicit and unit-testable.
 */
public final class FaceReplacementPolicy {

    public enum Mode {
        ADD,
        REPLACE,
        REMOVE
    }

    private FaceReplacementPolicy() {
    }

    public static Mode mode(boolean runtimeFaceLoaded, boolean desiredFaceEnabled) {
        if (!desiredFaceEnabled) {
            return Mode.REMOVE;
        }
        return runtimeFaceLoaded ? Mode.REPLACE : Mode.ADD;
    }

    public static boolean shouldRollback(boolean runtimeFaceWasLoaded,
                                         boolean previousFeatureAvailable) {
        return runtimeFaceWasLoaded && previousFeatureAvailable;
    }

    public static boolean keepRuntimeMappingAfterPushFailure(boolean runtimeFaceWasLoaded,
                                                             boolean rollbackSucceeded) {
        return runtimeFaceWasLoaded && rollbackSucceeded;
    }
}

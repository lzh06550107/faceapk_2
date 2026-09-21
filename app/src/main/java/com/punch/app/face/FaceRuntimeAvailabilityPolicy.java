package com.punch.app.face;

/**
 * Defines whether realtime recognition can use the current in-memory FaceSearch library.
 * Preparation / synchronization workflow flags must not override an actually usable runtime library.
 */
public final class FaceRuntimeAvailabilityPolicy {
    private FaceRuntimeAvailabilityPolicy() {
    }

    public static boolean isUsable(boolean sdkInitialized, int loadedFaceCount) {
        return sdkInitialized && loadedFaceCount > 0;
    }
}

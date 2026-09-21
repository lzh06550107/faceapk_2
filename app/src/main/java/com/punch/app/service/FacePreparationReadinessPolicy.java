package com.punch.app.service;

/**
 * Decides whether punching may remain available after a preparation attempt.
 * The decisive signal is whether the final native runtime library contains faces.
 */
public final class FacePreparationReadinessPolicy {
    private FacePreparationReadinessPolicy() {
    }

    public static boolean shouldRemainReady(boolean outcomeCompleted, int loadedFaceCount) {
        return loadedFaceCount > 0;
    }
}

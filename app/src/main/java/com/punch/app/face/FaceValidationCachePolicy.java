package com.punch.app.face;

/**
 * Rebuild validation may skip network/native preprocessing only when DatabaseHelper has already
 * returned an exact reusable feature for the current face version / SHA / feature schema.
 */
public final class FaceValidationCachePolicy {
    private FaceValidationCachePolicy() {
    }

    public static boolean canReuse(byte[] exactReusableFeature) {
        return exactReusableFeature != null
                && exactReusableFeature.length == FaceFeatureCachePolicy.FEATURE_LENGTH;
    }
}

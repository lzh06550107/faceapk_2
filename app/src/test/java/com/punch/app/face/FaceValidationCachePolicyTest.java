package com.punch.app.face;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FaceValidationCachePolicyTest {
    @Test
    public void exactReusableFeatureCanSkipNativeValidation() {
        assertTrue(FaceValidationCachePolicy.canReuse(new byte[FaceFeatureCachePolicy.FEATURE_LENGTH]));
    }

    @Test
    public void missingFeatureCannotSkipValidation() {
        assertFalse(FaceValidationCachePolicy.canReuse(null));
    }

    @Test
    public void wrongLengthFeatureCannotSkipValidation() {
        assertFalse(FaceValidationCachePolicy.canReuse(new byte[128]));
    }
}

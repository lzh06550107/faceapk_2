package com.punch.app.face;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FaceRuntimeAvailabilityPolicyTest {
    @Test
    public void initializedSdkWithLoadedFaceIsUsable() {
        assertTrue(FaceRuntimeAvailabilityPolicy.isUsable(true, 1));
    }

    @Test
    public void initializedSdkWithoutLoadedFaceIsNotUsable() {
        assertFalse(FaceRuntimeAvailabilityPolicy.isUsable(true, 0));
    }

    @Test
    public void uninitializedSdkIsNotUsableEvenIfCountIsStale() {
        assertFalse(FaceRuntimeAvailabilityPolicy.isUsable(false, 679));
    }
}

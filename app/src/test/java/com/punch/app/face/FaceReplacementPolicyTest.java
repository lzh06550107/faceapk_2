package com.punch.app.face;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FaceReplacementPolicyTest {

    @Test
    public void newRuntimeFaceIsAdd() {
        assertEquals(FaceReplacementPolicy.Mode.ADD,
                FaceReplacementPolicy.mode(false, true));
    }

    @Test
    public void loadedRuntimeFaceWithNewFeatureIsReplace() {
        assertEquals(FaceReplacementPolicy.Mode.REPLACE,
                FaceReplacementPolicy.mode(true, true));
    }

    @Test
    public void disabledOrDeletedFaceIsRemove() {
        assertEquals(FaceReplacementPolicy.Mode.REMOVE,
                FaceReplacementPolicy.mode(true, false));
    }

    @Test
    public void failedReplacementRollsBackOnlyWhenOldFeatureExists() {
        assertTrue(FaceReplacementPolicy.shouldRollback(true, true));
        assertFalse(FaceReplacementPolicy.shouldRollback(true, false));
        assertFalse(FaceReplacementPolicy.shouldRollback(false, true));
    }

    @Test
    public void successfulRollbackKeepsRuntimeMapping() {
        assertTrue(FaceReplacementPolicy.keepRuntimeMappingAfterPushFailure(true, true));
        assertFalse(FaceReplacementPolicy.keepRuntimeMappingAfterPushFailure(true, false));
        assertFalse(FaceReplacementPolicy.keepRuntimeMappingAfterPushFailure(false, false));
    }
}

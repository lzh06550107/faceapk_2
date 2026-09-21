package com.punch.app.service;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FacePreparationReadinessPolicyTest {
    @Test
    public void incompletePreparationStaysReadyWhenRebuildLoadedFaces() {
        assertTrue(FacePreparationReadinessPolicy.shouldRemainReady(false, 679));
    }

    @Test
    public void incompletePreparationFailsWhenRuntimeLibraryIsEmpty() {
        assertFalse(FacePreparationReadinessPolicy.shouldRemainReady(false, 0));
    }

    @Test
    public void completedPreparationWithLoadedFacesIsReady() {
        assertTrue(FacePreparationReadinessPolicy.shouldRemainReady(true, 1));
    }
}

package com.punch.app.face;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FaceValidationBatchCoordinatorTest {
    @Test
    public void subsetRequestJoinsCoveringBatch() {
        FaceValidationBatchCoordinator<String> coordinator = new FaceValidationBatchCoordinator<>();
        AtomicInteger callbacks = new AtomicInteger();
        FaceValidationBatchCoordinator.Submission<String> first = coordinator.submit(
                ids("A", "B", "C"), result -> callbacks.incrementAndGet());
        FaceValidationBatchCoordinator.Submission<String> second = coordinator.submit(
                ids("B", "C"), result -> callbacks.incrementAndGet());

        assertTrue(first.isOwner());
        assertFalse(second.isOwner());
        assertEquals(1, coordinator.activeBatchCount());

        coordinator.complete(first, "done");
        assertEquals(2, callbacks.get());
        assertEquals(0, coordinator.activeBatchCount());
    }

    @Test
    public void supersetCreatesSeparateBatch() {
        FaceValidationBatchCoordinator<String> coordinator = new FaceValidationBatchCoordinator<>();
        FaceValidationBatchCoordinator.Submission<String> first = coordinator.submit(
                ids("A", "B"), result -> { });
        FaceValidationBatchCoordinator.Submission<String> second = coordinator.submit(
                ids("A", "B", "C"), result -> { });

        assertTrue(first.isOwner());
        assertTrue(second.isOwner());
        assertEquals(2, coordinator.activeBatchCount());
    }

    @Test
    public void completedBatchDoesNotAbsorbLaterRequest() {
        FaceValidationBatchCoordinator<String> coordinator = new FaceValidationBatchCoordinator<>();
        FaceValidationBatchCoordinator.Submission<String> first = coordinator.submit(
                ids("A", "B"), result -> { });
        coordinator.complete(first, "done");

        FaceValidationBatchCoordinator.Submission<String> second = coordinator.submit(
                ids("B"), result -> { });
        assertTrue(second.isOwner());
        assertEquals(1, coordinator.activeBatchCount());
    }

    private HashSet<String> ids(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }
}

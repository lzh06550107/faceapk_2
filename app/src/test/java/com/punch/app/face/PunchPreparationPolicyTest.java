package com.punch.app.face;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PunchPreparationPolicyTest {
    @Test
    public void noLocalEmployeesRequiresEmployeeSync() {
        assertEquals(
                PunchPreparationPolicy.Action.SYNC_EMPLOYEES,
                PunchPreparationPolicy.decide(0, false));
    }

    @Test
    public void reusableRuntimeLibrarySkipsRebuild() {
        assertEquals(
                PunchPreparationPolicy.Action.REUSE_FACE_LIBRARY,
                PunchPreparationPolicy.decide(20, true));
    }

    @Test
    public void coldRuntimeLibraryRequiresRebuild() {
        assertEquals(
                PunchPreparationPolicy.Action.REBUILD_FACE_LIBRARY,
                PunchPreparationPolicy.decide(20, false));
    }

    @Test
    public void firstPreparationAttemptStartsImmediately() {
        assertTrue(PunchPreparationPolicy.isRetryAllowed(0L, 1_000L));
    }

    @Test
    public void failedPreparationWaitsUntilRetryDeadline() {
        long retryAt = PunchPreparationPolicy.nextRetryAt(1_000L, 10_000L);
        assertEquals(11_000L, retryAt);
        assertFalse(PunchPreparationPolicy.isRetryAllowed(retryAt, 10_999L));
        assertTrue(PunchPreparationPolicy.isRetryAllowed(retryAt, 11_000L));
    }

    @Test
    public void retryDeadlineDoesNotOverflow() {
        assertEquals(
                Long.MAX_VALUE,
                PunchPreparationPolicy.nextRetryAt(Long.MAX_VALUE - 5L, 10L));
    }
}

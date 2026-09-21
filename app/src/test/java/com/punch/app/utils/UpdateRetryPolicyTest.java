package com.punch.app.utils;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class UpdateRetryPolicyTest {
    @Test
    public void delayMsForFailureCount_usesExpectedBackoffAndThenCapsAtThreeHours() {
        assertEquals(60_000L, UpdateRetryPolicy.delayMsForFailureCount(1));
        assertEquals(5 * 60_000L, UpdateRetryPolicy.delayMsForFailureCount(2));
        assertEquals(15 * 60_000L, UpdateRetryPolicy.delayMsForFailureCount(3));
        assertEquals(30 * 60_000L, UpdateRetryPolicy.delayMsForFailureCount(4));
        assertEquals(60 * 60_000L, UpdateRetryPolicy.delayMsForFailureCount(5));
        assertEquals(3 * 60 * 60_000L, UpdateRetryPolicy.delayMsForFailureCount(6));
        assertEquals(3 * 60 * 60_000L, UpdateRetryPolicy.delayMsForFailureCount(1000));
    }

    @Test
    public void nextFailureCount_resetsWhenRetryGenerationChanges() {
        assertEquals(1, UpdateRetryPolicy.nextFailureCount(8, false));
        assertEquals(9, UpdateRetryPolicy.nextFailureCount(8, true));
        assertEquals(1, UpdateRetryPolicy.nextFailureCount(0, true));
    }
}

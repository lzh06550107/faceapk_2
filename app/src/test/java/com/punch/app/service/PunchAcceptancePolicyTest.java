package com.punch.app.service;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PunchAcceptancePolicyTest {
    @Test public void transportAuthThrottleAndServerFailuresRemainRecoverable() {
        assertTrue(PunchAcceptancePolicy.shouldRetryLater(-1));
        assertTrue(PunchAcceptancePolicy.shouldRetryLater(0));
        assertTrue(PunchAcceptancePolicy.shouldRetryLater(200));
        assertTrue(PunchAcceptancePolicy.shouldRetryLater(401));
        assertTrue(PunchAcceptancePolicy.shouldRetryLater(408));
        assertTrue(PunchAcceptancePolicy.shouldRetryLater(429));
        assertTrue(PunchAcceptancePolicy.shouldRetryLater(500));
        assertTrue(PunchAcceptancePolicy.shouldRetryLater(10501));
    }

    @Test public void clearClientOrBusinessRejectionsCanDiscardIntent() {
        assertFalse(PunchAcceptancePolicy.shouldRetryLater(400));
        assertFalse(PunchAcceptancePolicy.shouldRetryLater(403));
        assertFalse(PunchAcceptancePolicy.shouldRetryLater(404));
        assertFalse(PunchAcceptancePolicy.shouldRetryLater(422));
    }
}

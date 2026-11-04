package com.punch.app.service;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class PunchAcceptancePolicyTest {
    @Test public void everyNonFinalResponseCodeRemainsRecoverable() {
        assertTrue(PunchAcceptancePolicy.shouldRetryLater(-1));
        assertTrue(PunchAcceptancePolicy.shouldRetryLater(0));
        assertTrue(PunchAcceptancePolicy.shouldRetryLater(200));
        assertTrue(PunchAcceptancePolicy.shouldRetryLater(400));
        assertTrue(PunchAcceptancePolicy.shouldRetryLater(401));
        assertTrue(PunchAcceptancePolicy.shouldRetryLater(403));
        assertTrue(PunchAcceptancePolicy.shouldRetryLater(404));
        assertTrue(PunchAcceptancePolicy.shouldRetryLater(408));
        assertTrue(PunchAcceptancePolicy.shouldRetryLater(422));
        assertTrue(PunchAcceptancePolicy.shouldRetryLater(429));
        assertTrue(PunchAcceptancePolicy.shouldRetryLater(500));
        assertTrue(PunchAcceptancePolicy.shouldRetryLater(10501));
    }
}

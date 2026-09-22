package com.punch.app.service;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PunchDepartureLatchTest {
    @Test public void sameEmployeeStaysBlockedUntilEnoughNoFaceFrames() {
        PunchDepartureLatch latch = new PunchDepartureLatch(3);
        latch.latch("E1");
        assertTrue(latch.shouldBlockMatchedEmployee("E1"));

        latch.onNoFaceFrame();
        latch.onNoFaceFrame();
        assertTrue(latch.isLatchedFor("E1"));

        latch.onNoFaceFrame();
        assertFalse(latch.isLatchedFor("E1"));
        assertFalse(latch.shouldBlockMatchedEmployee("E1"));
    }

    @Test public void differentConfirmedEmployeeReleasesPreviousLatch() {
        PunchDepartureLatch latch = new PunchDepartureLatch(3);
        latch.latch("E1");
        assertFalse(latch.shouldBlockMatchedEmployee("E2"));
        assertFalse(latch.isLatchedFor("E1"));
    }

    @Test public void matchedFramesResetNoFaceProgress() {
        PunchDepartureLatch latch = new PunchDepartureLatch(2);
        latch.latch("E1");
        latch.onNoFaceFrame();
        assertTrue(latch.shouldBlockMatchedEmployee("E1"));
        latch.onNoFaceFrame();
        assertTrue(latch.isLatchedFor("E1"));
    }
}

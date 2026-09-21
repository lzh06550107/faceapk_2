package com.punch.app.utils;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class NtpProbeClientTest {
    @Test
    public void probeTimeoutIsBounded() {
        assertEquals(500, NtpProbeClient.normalizeTimeoutMs(1));
        assertEquals(3_000, NtpProbeClient.normalizeTimeoutMs(3_000));
        assertEquals(10_000, NtpProbeClient.normalizeTimeoutMs(60_000));
    }
}

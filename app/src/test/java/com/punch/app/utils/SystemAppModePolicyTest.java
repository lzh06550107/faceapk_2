package com.punch.app.utils;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SystemAppModePolicyTest {
    @Test
    public void android12PlatformSystemAppIsSupported() {
        assertTrue(SystemAppModePolicy.isSupported(31, true, true));
        assertTrue(SystemAppModePolicy.isSupported(32, true, true));
    }

    @Test
    public void ordinaryOrUnsignedInstallIsRejected() {
        assertFalse(SystemAppModePolicy.isSupported(31, false, true));
        assertFalse(SystemAppModePolicy.isSupported(31, true, false));
        assertFalse(SystemAppModePolicy.isSupported(30, true, true));
        assertFalse(SystemAppModePolicy.isSupported(33, true, true));
    }
}

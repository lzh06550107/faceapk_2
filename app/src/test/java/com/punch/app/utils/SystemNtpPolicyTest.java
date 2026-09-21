package com.punch.app.utils;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SystemNtpPolicyTest {
    @Test
    public void defaultTargetServerIsFactoryNtpHost() {
        assertEquals("192.168.111.240", SystemNtpPolicy.DEFAULT_TARGET_SERVER);
    }

    @Test
    public void normalizesSupportedHostValues() {
        assertEquals("192.168.111.240", SystemNtpPolicy.normalizeHost(" 192.168.111.240 "));
        assertEquals("ntp.example.internal", SystemNtpPolicy.normalizeHost(" NTP.Example.Internal. "));
    }

    @Test
    public void acceptsIpv4AndDnsHostNamesOnly() {
        assertTrue(SystemNtpPolicy.isValidHost("192.168.111.240"));
        assertTrue(SystemNtpPolicy.isValidHost("ntp.example.internal"));
        assertTrue(SystemNtpPolicy.isValidHost("time1"));

        assertFalse(SystemNtpPolicy.isValidHost(""));
        assertFalse(SystemNtpPolicy.isValidHost("http://192.168.111.240"));
        assertFalse(SystemNtpPolicy.isValidHost("ntp://ntp.example.internal"));
        assertFalse(SystemNtpPolicy.isValidHost("ntp.example.internal:123"));
        assertFalse(SystemNtpPolicy.isValidHost("ntp example.internal"));
        assertFalse(SystemNtpPolicy.isValidHost("192.168.111.999"));
        assertFalse(SystemNtpPolicy.isValidHost("-ntp.example.com"));
        assertFalse(SystemNtpPolicy.isValidHost("ntp-.example.com"));
    }

    @Test
    public void managementRequiresAndroidElevenDeviceOwnerAndWriteSecureSettings() {
        assertEquals(
                SystemNtpPolicy.ManagementAvailability.UNSUPPORTED_ANDROID_VERSION,
                SystemNtpPolicy.getAvailability(true, true, 29)
        );
        assertEquals(
                SystemNtpPolicy.ManagementAvailability.NOT_DEVICE_OWNER,
                SystemNtpPolicy.getAvailability(false, true, 31)
        );
        assertEquals(
                SystemNtpPolicy.ManagementAvailability.WRITE_PERMISSION_MISSING,
                SystemNtpPolicy.getAvailability(true, false, 31)
        );
        assertEquals(
                SystemNtpPolicy.ManagementAvailability.AVAILABLE,
                SystemNtpPolicy.getAvailability(true, true, 31)
        );
    }
}

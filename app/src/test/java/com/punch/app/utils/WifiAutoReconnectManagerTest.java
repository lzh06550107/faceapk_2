package com.punch.app.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class WifiAutoReconnectManagerTest {

    @Before
    public void setUp() {
        WifiAutoReconnectManager.resetForTest();
    }

    @After
    public void tearDown() {
        WifiAutoReconnectManager.resetForTest();
    }

    @Test
    public void ensureSavedWifiConnection_shouldSkipWhenSavedSsidIsBlank() {
        FakeDeps deps = new FakeDeps();
        deps.savedSsid = "";

        WifiAutoReconnectManager.ensureSavedWifiConnection(deps);

        assertFalse(deps.wifiEnabledCalled);
    }

    @Test
    public void ensureSavedWifiConnection_shouldSkipWhenNotManagedDevice() {
        FakeDeps deps = new FakeDeps();
        deps.savedSsid = "Office-WiFi";
        deps.managedDevice = false;

        WifiAutoReconnectManager.ensureSavedWifiConnection(deps);

        assertFalse(deps.wifiEnabledCalled);
        assertEquals(0, deps.addOrFindNetworkCalls);
    }

    @Test
    public void ensureSavedWifiConnection_shouldSkipWhenAlreadyConnected() {
        FakeDeps deps = new FakeDeps();
        deps.savedSsid = "Office-WiFi";
        deps.connectedToTarget = true;

        WifiAutoReconnectManager.ensureSavedWifiConnection(deps);

        assertFalse(deps.wifiEnabledCalled);
        assertEquals(0, deps.addOrFindNetworkCalls);
    }

    @Test
    public void attemptSavedWifiConnection_shouldWaitForWifiToBecomeEnabled() {
        FakeDeps deps = new FakeDeps();
        deps.savedSsid = "Office-WiFi";
        deps.wifiEnabled = false;

        WifiAutoReconnectManager.AttemptResult result =
                WifiAutoReconnectManager.attemptSavedWifiConnection(deps);

        assertEquals(WifiAutoReconnectManager.AttemptResult.WIFI_ENABLING, result);
        assertTrue(deps.wifiEnabledCalled);
        assertEquals(0, deps.addOrFindNetworkCalls);
        assertEquals(0, deps.reconnectCalls);
    }

    @Test
    public void attemptWifiConnection_shouldUseCurrentManualCredentials() {
        FakeDeps deps = new FakeDeps();
        deps.savedSsid = "Old-WiFi";
        deps.savedPassword = "old-password";
        deps.networkIdToReturn = 18;

        WifiAutoReconnectManager.AttemptResult result =
                WifiAutoReconnectManager.attemptWifiConnection(
                        deps,
                        "New-WiFi",
                        "new-password"
                );

        assertEquals(WifiAutoReconnectManager.AttemptResult.SUBMITTED, result);
        assertEquals("New-WiFi", deps.lastSsid);
        assertEquals("new-password", deps.lastPassword);
        assertEquals(18, deps.lastEnabledNetworkId);
    }

    @Test
    public void ensureSavedWifiConnection_shouldReconnectWhenSavedNetworkExists() {
        FakeDeps deps = new FakeDeps();
        deps.savedSsid = "Office-WiFi";
        deps.savedPassword = "secret123";
        deps.networkIdToReturn = 42;

        WifiAutoReconnectManager.AttemptResult result =
                WifiAutoReconnectManager.attemptSavedWifiConnection(deps);

        assertEquals(WifiAutoReconnectManager.AttemptResult.SUBMITTED, result);
        assertFalse(deps.wifiEnabledCalled);
        assertEquals("Office-WiFi", deps.lastSsid);
        assertEquals("secret123", deps.lastPassword);
        assertEquals(42, deps.lastEnabledNetworkId);
        assertEquals(1, deps.reconnectCalls);
    }

    @Test
    public void ensureSavedWifiConnection_shouldSkipReconnectWhenNetworkIdMissing() {
        FakeDeps deps = new FakeDeps();
        deps.savedSsid = "Office-WiFi";
        deps.networkIdToReturn = -1;

        WifiAutoReconnectManager.ensureSavedWifiConnection(deps);

        assertFalse(deps.wifiEnabledCalled);
        assertEquals(0, deps.reconnectCalls);
    }

    @Test
    public void ensureSavedWifiConnection_shouldThrottleRepeatedAttempts() {
        FakeDeps deps = new FakeDeps();
        deps.savedSsid = "Office-WiFi";
        deps.networkIdToReturn = 1;
        deps.now = 100_000L;

        WifiAutoReconnectManager.ensureSavedWifiConnection(deps);
        WifiAutoReconnectManager.ensureSavedWifiConnection(deps);

        assertEquals(1, deps.addOrFindNetworkCalls);
        assertEquals(1, deps.reconnectCalls);
    }

    private static final class FakeDeps implements WifiAutoReconnectManager.Deps {
        long now = 100_000L;
        String savedSsid = "Office-WiFi";
        String savedPassword = "";
        boolean managedDevice = true;
        boolean hasWifiService = true;
        boolean wifiEnabled = true;
        boolean setWifiEnabledResult = true;
        boolean connectedToTarget = false;
        int networkIdToReturn = 7;
        boolean enableNetworkResult = true;

        boolean wifiEnabledCalled;
        int addOrFindNetworkCalls;
        int reconnectCalls;
        int lastEnabledNetworkId = -1;
        String lastSsid = "";
        String lastPassword = "";

        @Override
        public long now() {
            return now;
        }

        @Override
        public String getSavedSsid() {
            return savedSsid;
        }

        @Override
        public String getSavedPassword() {
            return savedPassword;
        }

        @Override
        public boolean canManageWifi() {
            return managedDevice;
        }

        @Override
        public boolean hasWifiService() {
            return hasWifiService;
        }

        @Override
        public boolean isWifiEnabled() {
            return wifiEnabled;
        }

        @Override
        public boolean isConnectedToTargetSsid(String ssid) {
            return connectedToTarget;
        }

        @Override
        public boolean setWifiEnabled(boolean enabled) {
            wifiEnabledCalled = enabled;
            return setWifiEnabledResult;
        }

        @Override
        public int addOrFindNetworkId(String ssid, String password) {
            addOrFindNetworkCalls++;
            lastSsid = ssid;
            lastPassword = password;
            return networkIdToReturn;
        }

        @Override
        public boolean enableNetwork(int networkId, boolean disableOthers) {
            lastEnabledNetworkId = networkId;
            return enableNetworkResult;
        }

        @Override
        public void reconnect() {
            reconnectCalls++;
        }
    }
}

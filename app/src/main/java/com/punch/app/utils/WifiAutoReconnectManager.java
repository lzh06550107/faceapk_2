package com.punch.app.utils;

import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import java.util.List;

public final class WifiAutoReconnectManager {
    private static final String TAG = "WifiAutoReconnect";
    private static final long MIN_RETRY_INTERVAL_MS = 10_000L;

    private static long lastAttemptAt = 0L;

    private WifiAutoReconnectManager() {
    }

    public enum AttemptResult {
        ALREADY_CONNECTED,
        WIFI_ENABLING,
        SUBMITTED,
        NO_SAVED_WIFI,
        NOT_MANAGED_DEVICE,
        WIFI_SERVICE_UNAVAILABLE,
        NETWORK_CONFIG_FAILED,
        PERMISSION_DENIED,
        FAILED
    }

    public static void ensureSavedWifiConnection(Context context) {
        if (context == null) {
            return;
        }
        ensureSavedWifiConnection(new AndroidDeps(context.getApplicationContext()));
    }

    public static AttemptResult attemptSavedWifiConnection(Context context) {
        if (context == null) {
            return AttemptResult.FAILED;
        }
        return attemptSavedWifiConnection(new AndroidDeps(context.getApplicationContext()));
    }

    public static AttemptResult attemptWifiConnection(Context context,
                                                       String ssid,
                                                       String password) {
        if (context == null) {
            return AttemptResult.FAILED;
        }
        return attemptWifiConnection(
                new AndroidDeps(context.getApplicationContext()),
                ssid,
                password
        );
    }

    public static boolean isConnectedToSavedWifi(Context context) {
        if (context == null) {
            return false;
        }
        Deps deps = new AndroidDeps(context.getApplicationContext());
        String ssid = deps.getSavedSsid();
        return ssid != null
                && !ssid.trim().isEmpty()
                && deps.hasWifiService()
                && deps.isConnectedToTargetSsid(ssid);
    }

    static void ensureSavedWifiConnection(Deps deps) {
        if (deps == null) {
            return;
        }
        long now = deps.now();
        if (now - lastAttemptAt < MIN_RETRY_INTERVAL_MS) {
            return;
        }
        lastAttemptAt = now;

        AttemptResult result = attemptSavedWifiConnection(deps);
        if (result == AttemptResult.ALREADY_CONNECTED) {
            AppLogger.i(TAG, "Saved wifi already connected: " + deps.getSavedSsid());
        } else if (result == AttemptResult.SUBMITTED) {
            AppLogger.i(TAG, "Auto reconnect submitted: ssid=" + deps.getSavedSsid());
        } else if (result != AttemptResult.WIFI_ENABLING) {
            AppLogger.w(TAG, "Auto reconnect not submitted: result=" + result.name());
        }
    }

    static AttemptResult attemptSavedWifiConnection(Deps deps) {
        return attemptWifiConnection(deps, deps.getSavedSsid(), deps.getSavedPassword());
    }

    static AttemptResult attemptWifiConnection(Deps deps, String ssid, String password) {
        if (ssid == null || ssid.trim().isEmpty()) {
            return AttemptResult.NO_SAVED_WIFI;
        }
        if (!deps.isDeviceOwner()) {
            return AttemptResult.NOT_DEVICE_OWNER;
        }
        if (!deps.hasWifiService()) {
            return AttemptResult.WIFI_SERVICE_UNAVAILABLE;
        }
        if (deps.isConnectedToTargetSsid(ssid)) {
            return AttemptResult.ALREADY_CONNECTED;
        }

        try {
            if (!deps.isWifiEnabled()) {
                return deps.setWifiEnabled(true)
                        ? AttemptResult.WIFI_ENABLING
                        : AttemptResult.FAILED;
            }
            int networkId = deps.addOrFindNetworkId(ssid, password);
            if (networkId < 0) {
                return AttemptResult.NETWORK_CONFIG_FAILED;
            }
            boolean enabled = deps.enableNetwork(networkId, true);
            if (!enabled) {
                return AttemptResult.FAILED;
            }
            deps.reconnect();
            return AttemptResult.SUBMITTED;
        } catch (SecurityException e) {
            AppLogger.e(TAG, "Auto reconnect failed because of missing permission", e);
            return AttemptResult.PERMISSION_DENIED;
        } catch (Exception e) {
            AppLogger.e(TAG, "Auto reconnect failed: " + e.getMessage(), e);
            return AttemptResult.FAILED;
        }
    }

    static void resetForTest() {
        lastAttemptAt = 0L;
    }

    @SuppressWarnings("deprecation")
    private static int addOrFindNetworkId(WifiManager wifiManager, String ssid, String password) {
        WifiConfiguration config = new WifiConfiguration();
        config.SSID = quoteWifiValue(ssid);
        if (password == null || password.isEmpty()) {
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        } else {
            config.preSharedKey = quoteWifiValue(password);
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        }

        int existingNetworkId = findConfiguredNetworkId(wifiManager, ssid);
        if (existingNetworkId >= 0) {
            config.networkId = existingNetworkId;
            int updatedNetworkId = wifiManager.updateNetwork(config);
            return updatedNetworkId >= 0 ? updatedNetworkId : existingNetworkId;
        }

        int networkId = wifiManager.addNetwork(config);
        return networkId >= 0 ? networkId : findConfiguredNetworkId(wifiManager, ssid);
    }

    @SuppressWarnings("deprecation")
    private static int findConfiguredNetworkId(WifiManager wifiManager, String ssid) {
        try {
            List<WifiConfiguration> configuredNetworks = wifiManager.getConfiguredNetworks();
            if (configuredNetworks == null) {
                return -1;
            }
            String targetSsid = stripWifiQuotes(ssid);
            for (WifiConfiguration item : configuredNetworks) {
                if (item == null) {
                    continue;
                }
                if (stripWifiQuotes(item.SSID).equals(targetSsid)) {
                    return item.networkId;
                }
            }
        } catch (SecurityException e) {
            AppLogger.e(TAG, "Read configured networks failed", e);
        }
        return -1;
    }

    @SuppressWarnings("deprecation")
    private static boolean isConnectedToTargetSsid(WifiManager wifiManager, String targetSsid) {
        try {
            WifiInfo wifiInfo = wifiManager.getConnectionInfo();
            if (wifiInfo == null || wifiInfo.getNetworkId() == -1) {
                return false;
            }
            return stripWifiQuotes(wifiInfo.getSSID()).equals(stripWifiQuotes(targetSsid));
        } catch (Exception e) {
            return false;
        }
    }

    private static String quoteWifiValue(String value) {
        String safeValue = value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + safeValue + "\"";
    }

    private static String stripWifiQuotes(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    interface Deps {
        long now();

        String getSavedSsid();

        String getSavedPassword();

        boolean canManageWifi();

        boolean hasWifiService();

        boolean isWifiEnabled();

        boolean isConnectedToTargetSsid(String ssid);

        boolean setWifiEnabled(boolean enabled);

        int addOrFindNetworkId(String ssid, String password);

        boolean enableNetwork(int networkId, boolean disableOthers);

        void reconnect();
    }

    private static final class AndroidDeps implements Deps {
        private final Context appContext;
        private final WifiManager wifiManager;

        private AndroidDeps(Context appContext) {
            this.appContext = appContext;
            this.wifiManager = appContext == null
                    ? null
                    : (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
        }

        @Override
        public long now() {
            return System.currentTimeMillis();
        }

        @Override
        public String getSavedSsid() {
            return SessionManager.get().getLastWifiSsid();
        }

        @Override
        public String getSavedPassword() {
            return SessionManager.get().getLastWifiPassword();
        }

        @Override
        public boolean canManageWifi() {
            return appContext != null && KioskManager.isManagedDevice(appContext);
        }

        @Override
        public boolean hasWifiService() {
            return wifiManager != null;
        }

        @Override
        public boolean isWifiEnabled() {
            return wifiManager != null && wifiManager.isWifiEnabled();
        }

        @Override
        public boolean isConnectedToTargetSsid(String ssid) {
            return wifiManager != null && WifiAutoReconnectManager.isConnectedToTargetSsid(wifiManager, ssid);
        }

        @Override
        public boolean setWifiEnabled(boolean enabled) {
            return wifiManager != null && wifiManager.setWifiEnabled(enabled);
        }

        @Override
        public int addOrFindNetworkId(String ssid, String password) {
            if (wifiManager == null) {
                return -1;
            }
            return WifiAutoReconnectManager.addOrFindNetworkId(wifiManager, ssid, password);
        }

        @Override
        public boolean enableNetwork(int networkId, boolean disableOthers) {
            return wifiManager != null && wifiManager.enableNetwork(networkId, disableOthers);
        }

        @Override
        public void reconnect() {
            if (wifiManager != null) {
                wifiManager.reconnect();
            }
        }
    }
}

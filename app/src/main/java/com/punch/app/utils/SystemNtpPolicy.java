package com.punch.app.utils;

import java.util.Locale;

public final class SystemNtpPolicy {
    public static final String DEFAULT_TARGET_SERVER = "192.168.111.240";

    public enum ManagementAvailability {
        AVAILABLE,
        UNSUPPORTED_ANDROID_VERSION,
        NOT_DEVICE_OWNER,
        WRITE_PERMISSION_MISSING
    }

    private static final int MIN_MANAGED_AUTO_TIME_API = 30;

    private SystemNtpPolicy() {
    }

    public static ManagementAvailability getAvailability(boolean deviceOwner,
                                                         boolean writeSecureSettingsGranted,
                                                         int sdkInt) {
        if (sdkInt < MIN_MANAGED_AUTO_TIME_API) {
            return ManagementAvailability.UNSUPPORTED_ANDROID_VERSION;
        }
        if (!deviceOwner) {
            return ManagementAvailability.NOT_DEVICE_OWNER;
        }
        if (!writeSecureSettingsGranted) {
            return ManagementAvailability.WRITE_PERMISSION_MISSING;
        }
        return ManagementAvailability.AVAILABLE;
    }

    public static String normalizeHost(String rawHost) {
        if (rawHost == null) {
            return "";
        }
        String host = rawHost.trim().toLowerCase(Locale.US);
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        return host;
    }

    public static boolean isValidHost(String rawHost) {
        String host = normalizeHost(rawHost);
        if (host.isEmpty() || host.length() > 253) {
            return false;
        }
        if (host.indexOf('/') >= 0 || host.indexOf(':') >= 0) {
            return false;
        }
        for (int i = 0; i < host.length(); i++) {
            if (Character.isWhitespace(host.charAt(i))) {
                return false;
            }
        }
        if (looksLikeIpv4(host)) {
            return isValidIpv4(host);
        }
        String[] labels = host.split("\\.", -1);
        for (String label : labels) {
            if (!isValidDnsLabel(label)) {
                return false;
            }
        }
        return true;
    }

    private static boolean looksLikeIpv4(String host) {
        boolean hasDot = false;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c == '.') {
                hasDot = true;
                continue;
            }
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return hasDot;
    }

    private static boolean isValidIpv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            int value = 0;
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (c < '0' || c > '9') {
                    return false;
                }
                value = value * 10 + (c - '0');
            }
            if (value > 255) {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidDnsLabel(String label) {
        if (label.isEmpty() || label.length() > 63) {
            return false;
        }
        if (label.charAt(0) == '-' || label.charAt(label.length() - 1) == '-') {
            return false;
        }
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            boolean alphaNumeric = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            if (!alphaNumeric && c != '-') {
                return false;
            }
        }
        return true;
    }
}

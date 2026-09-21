package com.punch.app.utils;

/**
 * Pure policy for selecting the Android 12 platform-system-app backend.
 *
 * <p>The production system-app backend is intentionally limited to Android 12/12L.
 * It requires both system-image installation and a platform-signature match. This keeps
 * ordinary debug/adb-installed builds on the existing Device Owner or unmanaged path.</p>
 */
public final class SystemAppModePolicy {
    private SystemAppModePolicy() {
    }

    public static boolean isSupported(int sdkInt,
                                      boolean installedOnSystemImage,
                                      boolean platformSigned) {
        return (sdkInt == 31 || sdkInt == 32)
                && installedOnSystemImage
                && platformSigned;
    }
}

package com.punch.app.utils;

public final class KioskRestorePolicy {
    private KioskRestorePolicy() {
    }

    public static boolean shouldRestore(
            boolean kioskEnabled,
            boolean managedDevice,
            boolean screenInteractive,
            boolean keyguardLocked,
            boolean changingConfigurations,
            int resumedActivityCount
    ) {
        return kioskEnabled
                && managedDevice
                && screenInteractive
                && !keyguardLocked
                && !changingConfigurations
                && resumedActivityCount <= 0;
    }
}

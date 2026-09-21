package com.punch.app.utils;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;

import com.punch.app.network.InteractionLogger;
import com.punch.app.receiver.KioskDeviceAdminReceiver;

public final class ScreenTimeoutPolicyManager {
    private static final long FALLBACK_SYSTEM_TIMEOUT_MS = 60_000L;

    private ScreenTimeoutPolicyManager() {
    }

    public static ScreenTimeoutPolicy.ManagementAvailability getAvailability(Context context) {
        boolean managedController = context != null
                && (KioskManager.isSystemAppMode(context) || KioskManager.isDeviceOwner(context));
        return ScreenTimeoutPolicy.getManagementAvailability(
                managedController,
                Build.VERSION.SDK_INT
        );
    }

    public static ApplyResult applyAndSave(Context context, long timeoutMs) {
        if (context == null) {
            return ApplyResult.failure("Context unavailable");
        }
        if (!ScreenTimeoutPolicy.isSupportedTimeoutMs(timeoutMs)) {
            return ApplyResult.failure("Unsupported screen timeout: " + timeoutMs);
        }
        ScreenTimeoutPolicy.ManagementAvailability availability = getAvailability(context);
        if (availability != ScreenTimeoutPolicy.ManagementAvailability.AVAILABLE) {
            return ApplyResult.failure(buildUnavailableMessage(availability));
        }

        SessionManager session = SessionManager.get();
        long previousTimeoutMs = session.getScreenTimeoutMs();
        ApplyResult result = applySystemPolicy(context, timeoutMs);
        if (result.success) {
            if (!session.saveScreenTimeoutMs(timeoutMs)) {
                ApplyResult rollbackResult = applySystemPolicy(context, previousTimeoutMs);
                boolean configReverted = session.saveScreenTimeoutMs(previousTimeoutMs);
                return ApplyResult.failure(
                        "Screen timeout configuration could not be saved"
                                + "; systemRollback=" + rollbackResult.message
                                + "; configReverted=" + configReverted
                );
            }
        } else {
            ApplyResult rollbackResult = timeoutMs == previousTimeoutMs
                    ? ApplyResult.success("Existing configuration unchanged")
                    : applySystemPolicy(context, previousTimeoutMs);
            InteractionLogger.logBusinessFailure(
                    InteractionLogger.GROUP_GENERAL,
                    "Save screen timeout policy failed",
                    "timeoutMs=" + timeoutMs
                            + "\nreason=" + result.message
                            + "\nrollback=" + rollbackResult.message
            );
            return ApplyResult.failure(
                    result.message + "; rollback=" + rollbackResult.message
            );
        }
        return result;
    }

    public static void applyConfiguredPolicy(Context context) {
        if (context == null
                || getAvailability(context)
                != ScreenTimeoutPolicy.ManagementAvailability.AVAILABLE) {
            return;
        }
        long timeoutMs = SessionManager.get().getScreenTimeoutMs();
        ApplyResult result = applySystemPolicy(context, timeoutMs);
        if (!result.success) {
            InteractionLogger.logBusinessFailure(
                    InteractionLogger.GROUP_GENERAL,
                    "Apply configured screen timeout failed",
                    result.message
            );
        }
    }

    public static ApplyResult restoreOriginalSettings(Context context) {
        if (context == null) {
            return ApplyResult.failure("Context unavailable");
        }
        if (!SessionManager.get().hasOriginalScreenSettings()) {
            return ApplyResult.success("No original screen settings to restore");
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return ApplyResult.failure("Screen timeout management requires Android 9 or later");
        }
        if (getAvailability(context)
                != ScreenTimeoutPolicy.ManagementAvailability.AVAILABLE) {
            return ApplyResult.failure("Device Owner screen settings are unavailable");
        }

        SessionManager session = SessionManager.get();
        if (KioskManager.isSystemAppMode(context)) {
            return restoreSystemAppSettings(context, session);
        }
        DevicePolicyManager dpm = getDevicePolicyManager(context);
        ComponentName admin = getAdminComponent(context);
        if (dpm == null) {
            return ApplyResult.failure("DevicePolicyManager unavailable");
        }
        try {
            dpm.setSystemSetting(
                    admin,
                    Settings.System.SCREEN_OFF_TIMEOUT,
                    String.valueOf(session.getOriginalScreenTimeoutMs())
            );
            dpm.setGlobalSetting(
                    admin,
                    Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
                    String.valueOf(session.getOriginalStayOnWhilePluggedIn())
            );
            long restoredTimeoutMs = Settings.System.getLong(
                    context.getContentResolver(),
                    Settings.System.SCREEN_OFF_TIMEOUT,
                    -1L
            );
            int restoredStayOn = Settings.Global.getInt(
                    context.getContentResolver(),
                    Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
                    -1
            );
            if (restoredTimeoutMs != session.getOriginalScreenTimeoutMs()
                    || restoredStayOn != session.getOriginalStayOnWhilePluggedIn()) {
                return ApplyResult.failure(
                        "System did not restore original screen settings"
                                + "; timeoutMs=" + restoredTimeoutMs
                                + "; stayOnWhilePluggedIn=" + restoredStayOn
                );
            }
            if (!session.clearOriginalScreenSettings()) {
                return ApplyResult.failure("Original screen settings restored but snapshot cleanup failed");
            }
            InteractionLogger.logBusiness(
                    InteractionLogger.GROUP_GENERAL,
                    "Restored original screen settings",
                    "reason=screen_timeout_policy_released"
            );
            return ApplyResult.success("Original screen settings restored");
        } catch (RuntimeException e) {
            return ApplyResult.failure(e.getClass().getSimpleName() + ": " + safeMessage(e));
        }
    }

    private static ApplyResult applySystemPolicy(Context context, long timeoutMs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return ApplyResult.failure("Screen timeout management requires Android 9 or later");
        }
        if (timeoutMs == ScreenTimeoutPolicy.KEEP_SCREEN_ON) {
            return restoreOriginalSettings(context);
        }

        SessionManager session = SessionManager.get();
        if (KioskManager.isSystemAppMode(context)) {
            return applySystemAppPolicy(context, timeoutMs, session);
        }
        DevicePolicyManager dpm = getDevicePolicyManager(context);
        ComponentName admin = getAdminComponent(context);
        if (dpm == null) {
            return ApplyResult.failure("DevicePolicyManager unavailable");
        }
        try {
            if (!captureOriginalSettingsIfNeeded(context, session)) {
                session.clearOriginalScreenSettings();
                return ApplyResult.failure("Original screen settings could not be saved");
            }
            long currentTimeoutMs = Settings.System.getLong(
                    context.getContentResolver(),
                    Settings.System.SCREEN_OFF_TIMEOUT,
                    FALLBACK_SYSTEM_TIMEOUT_MS
            );
            int stayOnWhilePluggedIn = Settings.Global.getInt(
                    context.getContentResolver(),
                    Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
                    0
            );
            if (currentTimeoutMs != timeoutMs) {
                dpm.setSystemSetting(
                        admin,
                        Settings.System.SCREEN_OFF_TIMEOUT,
                        String.valueOf(timeoutMs)
                );
            }
            if (stayOnWhilePluggedIn != 0) {
                dpm.setGlobalSetting(
                        admin,
                        Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
                        "0"
                );
            }
            long appliedTimeoutMs = Settings.System.getLong(
                    context.getContentResolver(),
                    Settings.System.SCREEN_OFF_TIMEOUT,
                    -1L
            );
            int appliedStayOn = Settings.Global.getInt(
                    context.getContentResolver(),
                    Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
                    -1
            );
            if (appliedTimeoutMs != timeoutMs || appliedStayOn != 0) {
                return ApplyResult.failure(
                        "System did not accept screen timeout"
                                + "; timeoutMs=" + appliedTimeoutMs
                                + "; stayOnWhilePluggedIn=" + appliedStayOn
                );
            }
            InteractionLogger.logBusiness(
                    InteractionLogger.GROUP_GENERAL,
                    "Applied screen timeout policy",
                    "timeoutMs=" + timeoutMs + "\nstayOnWhilePluggedIn=0"
            );
            return ApplyResult.success("Screen timeout applied");
        } catch (RuntimeException e) {
            return ApplyResult.failure(e.getClass().getSimpleName() + ": " + safeMessage(e));
        }
    }

    private static ApplyResult applySystemAppPolicy(Context context,
                                                    long timeoutMs,
                                                    SessionManager session) {
        if (!SystemAppController.hasPermission(
                context,
                SystemAppController.PERMISSION_WRITE_SECURE_SETTINGS)) {
            return ApplyResult.failure("WRITE_SECURE_SETTINGS is not granted");
        }
        if (!SystemAppController.hasPermission(
                context,
                SystemAppController.PERMISSION_WRITE_SETTINGS)) {
            return ApplyResult.failure("WRITE_SETTINGS is not granted");
        }
        try {
            if (!captureOriginalSettingsIfNeeded(context, session)) {
                session.clearOriginalScreenSettings();
                return ApplyResult.failure("Original screen settings could not be saved");
            }
            boolean timeoutWritten = Settings.System.putLong(
                    context.getContentResolver(),
                    Settings.System.SCREEN_OFF_TIMEOUT,
                    timeoutMs
            );
            boolean stayOnWritten = Settings.Global.putInt(
                    context.getContentResolver(),
                    Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
                    0
            );
            if (!timeoutWritten || !stayOnWritten) {
                return ApplyResult.failure(
                        "Android rejected system screen settings"
                                + "; timeoutWritten=" + timeoutWritten
                                + "; stayOnWritten=" + stayOnWritten
                );
            }
            long appliedTimeoutMs = Settings.System.getLong(
                    context.getContentResolver(),
                    Settings.System.SCREEN_OFF_TIMEOUT,
                    -1L
            );
            int appliedStayOn = Settings.Global.getInt(
                    context.getContentResolver(),
                    Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
                    -1
            );
            if (appliedTimeoutMs != timeoutMs || appliedStayOn != 0) {
                return ApplyResult.failure(
                        "System did not accept screen timeout"
                                + "; timeoutMs=" + appliedTimeoutMs
                                + "; stayOnWhilePluggedIn=" + appliedStayOn
                );
            }
            InteractionLogger.logBusiness(
                    InteractionLogger.GROUP_GENERAL,
                    "Applied Android 12 system-app screen timeout",
                    "timeoutMs=" + timeoutMs + "\nstayOnWhilePluggedIn=0"
            );
            return ApplyResult.success("Screen timeout applied by Android 12 system app");
        } catch (RuntimeException e) {
            return ApplyResult.failure(e.getClass().getSimpleName() + ": " + safeMessage(e));
        }
    }

    private static ApplyResult restoreSystemAppSettings(Context context,
                                                        SessionManager session) {
        if (!SystemAppController.hasPermission(
                context,
                SystemAppController.PERMISSION_WRITE_SECURE_SETTINGS)
                || !SystemAppController.hasPermission(
                context,
                SystemAppController.PERMISSION_WRITE_SETTINGS)) {
            return ApplyResult.failure("System settings privileges are unavailable");
        }
        try {
            boolean timeoutWritten = Settings.System.putLong(
                    context.getContentResolver(),
                    Settings.System.SCREEN_OFF_TIMEOUT,
                    session.getOriginalScreenTimeoutMs()
            );
            boolean stayOnWritten = Settings.Global.putInt(
                    context.getContentResolver(),
                    Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
                    session.getOriginalStayOnWhilePluggedIn()
            );
            if (!timeoutWritten || !stayOnWritten) {
                return ApplyResult.failure("Android rejected original screen settings");
            }
            long restoredTimeoutMs = Settings.System.getLong(
                    context.getContentResolver(),
                    Settings.System.SCREEN_OFF_TIMEOUT,
                    -1L
            );
            int restoredStayOn = Settings.Global.getInt(
                    context.getContentResolver(),
                    Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
                    -1
            );
            if (restoredTimeoutMs != session.getOriginalScreenTimeoutMs()
                    || restoredStayOn != session.getOriginalStayOnWhilePluggedIn()) {
                return ApplyResult.failure(
                        "System did not restore original screen settings"
                                + "; timeoutMs=" + restoredTimeoutMs
                                + "; stayOnWhilePluggedIn=" + restoredStayOn
                );
            }
            if (!session.clearOriginalScreenSettings()) {
                return ApplyResult.failure(
                        "Original screen settings restored but snapshot cleanup failed"
                );
            }
            return ApplyResult.success("Original screen settings restored by Android 12 system app");
        } catch (RuntimeException e) {
            return ApplyResult.failure(e.getClass().getSimpleName() + ": " + safeMessage(e));
        }
    }

    private static boolean captureOriginalSettingsIfNeeded(Context context,
                                                           SessionManager session) {
        if (session.hasOriginalScreenSettings()) {
            return true;
        }
        long originalTimeoutMs = Settings.System.getLong(
                context.getContentResolver(),
                Settings.System.SCREEN_OFF_TIMEOUT,
                FALLBACK_SYSTEM_TIMEOUT_MS
        );
        int originalStayOn = Settings.Global.getInt(
                context.getContentResolver(),
                Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
                0
        );
        return session.saveOriginalScreenSettings(originalTimeoutMs, originalStayOn);
    }

    private static DevicePolicyManager getDevicePolicyManager(Context context) {
        return (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
    }

    private static ComponentName getAdminComponent(Context context) {
        return new ComponentName(context, KioskDeviceAdminReceiver.class);
    }

    private static String buildUnavailableMessage(
            ScreenTimeoutPolicy.ManagementAvailability availability) {
        if (availability == ScreenTimeoutPolicy.ManagementAvailability.NOT_DEVICE_OWNER) {
            return "Android 12 platform system app or Device Owner is required";
        }
        return "Screen timeout management requires Android 9 or later";
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable == null ? null : throwable.getMessage();
        return message == null || message.trim().isEmpty() ? "unknown" : message.trim();
    }

    public static final class ApplyResult {
        public final boolean success;
        public final String message;

        private ApplyResult(boolean success, String message) {
            this.success = success;
            this.message = message == null ? "" : message;
        }

        public static ApplyResult success(String message) {
            return new ApplyResult(true, message);
        }

        public static ApplyResult failure(String message) {
            return new ApplyResult(false, message);
        }
    }
}

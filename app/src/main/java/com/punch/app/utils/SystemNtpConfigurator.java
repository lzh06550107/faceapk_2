package com.punch.app.utils;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.UserManager;
import android.provider.Settings;

import com.punch.app.receiver.KioskDeviceAdminReceiver;

import androidx.annotation.RequiresApi;

public final class SystemNtpConfigurator {
    private static final String KEY_NTP_SERVER = "ntp_server";
    private static final String PERMISSION_WRITE_SECURE_SETTINGS =
            "android.permission.WRITE_SECURE_SETTINGS";

    private SystemNtpConfigurator() {
    }

    public static SystemNtpPolicy.ManagementAvailability getAvailability(Context context) {
        if (context == null) {
            return SystemNtpPolicy.ManagementAvailability.NOT_DEVICE_OWNER;
        }
        boolean writePermissionGranted = context.checkSelfPermission(
                PERMISSION_WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED;
        return SystemNtpPolicy.getAvailability(
                KioskManager.isDeviceOwner(context),
                writePermissionGranted,
                Build.VERSION.SDK_INT
        );
    }

    public static boolean hasWritePermission(Context context) {
        return context != null
                && context.checkSelfPermission(PERMISSION_WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static String getCurrentServer(Context context) {
        if (context == null) {
            return "";
        }
        try {
            String value = Settings.Global.getString(
                    context.getContentResolver(),
                    KEY_NTP_SERVER
            );
            return SystemNtpPolicy.normalizeHost(value);
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    public static ApplyResult apply(Context context, String rawHost) {
        if (context == null) {
            return ApplyResult.failure("Context unavailable", "", false);
        }
        String host = SystemNtpPolicy.normalizeHost(rawHost);
        if (!SystemNtpPolicy.isValidHost(host)) {
            return ApplyResult.failure("NTP server host is invalid", host, false);
        }

        SystemNtpPolicy.ManagementAvailability availability = getAvailability(context);
        if (availability != SystemNtpPolicy.ManagementAvailability.AVAILABLE) {
            return ApplyResult.failure(buildUnavailableMessage(availability), host, false);
        }

        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE
        );
        ComponentName admin = new ComponentName(context, KioskDeviceAdminReceiver.class);
        if (dpm == null) {
            return ApplyResult.failure("DevicePolicyManager unavailable", host, false);
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ApplyResult.failure(
                    "System NTP policy requires Android 11 or newer",
                    host,
                    false
            );
        }

        try {
            return applyWithSnapshot(context, dpm, admin, host);
        } catch (RuntimeException e) {
            return ApplyResult.failure(
                    e.getClass().getSimpleName() + ": " + safeMessage(e),
                    getCurrentServer(context),
                    false
            );
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private static ApplyResult applyWithSnapshot(Context context,
                                                 DevicePolicyManager dpm,
                                                 ComponentName admin,
                                                 String host) {
        String previousServer = Settings.Global.getString(
                context.getContentResolver(),
                KEY_NTP_SERVER
        );
        boolean previousAutoTime = Settings.Global.getInt(
                context.getContentResolver(),
                Settings.Global.AUTO_TIME,
                0
        ) == 1;
        Bundle previousRestrictions = dpm.getUserRestrictions(admin);
        boolean previousDateTimeRestriction = previousRestrictions != null
                && previousRestrictions.getBoolean(UserManager.DISALLOW_CONFIG_DATE_TIME, false);
        boolean serverChanged = !host.equals(SystemNtpPolicy.normalizeHost(previousServer));

        try {
            boolean putOk = Settings.Global.putString(
                    context.getContentResolver(),
                    KEY_NTP_SERVER,
                    host
            );
            if (!putOk) {
                return ApplyResult.failure("Android rejected the NTP server setting", host, false);
            }

            String readBack = getCurrentServer(context);
            if (!host.equals(readBack)) {
                boolean rollback = rollback(
                        context,
                        dpm,
                        admin,
                        previousServer,
                        previousAutoTime,
                        previousDateTimeRestriction
                );
                return ApplyResult.failure(
                        "NTP server read-back mismatch; rollback=" + rollback,
                        readBack,
                        rollback
                );
            }

            dpm.setAutoTimeEnabled(admin, true);
            dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_DATE_TIME);

            String verifiedServer = getCurrentServer(context);
            boolean autoTimeEnabled = Settings.Global.getInt(
                    context.getContentResolver(),
                    Settings.Global.AUTO_TIME,
                    0
            ) == 1;
            Bundle restrictions = dpm.getUserRestrictions(admin);
            boolean dateTimeRestricted = restrictions != null
                    && restrictions.getBoolean(UserManager.DISALLOW_CONFIG_DATE_TIME, false);
            if (!host.equals(verifiedServer) || !autoTimeEnabled || !dateTimeRestricted) {
                boolean rollback = rollback(
                        context,
                        dpm,
                        admin,
                        previousServer,
                        previousAutoTime,
                        previousDateTimeRestriction
                );
                return ApplyResult.failure(
                        "System NTP policy verification failed; rollback=" + rollback,
                        verifiedServer,
                        rollback
                );
            }

            return ApplyResult.success(
                    host,
                    serverChanged
                            ? "System NTP configuration applied"
                            : "System NTP configuration verified"
            );
        } catch (RuntimeException e) {
            boolean rollback = rollback(
                    context,
                    dpm,
                    admin,
                    previousServer,
                    previousAutoTime,
                    previousDateTimeRestriction
            );
            return ApplyResult.failure(
                    e.getClass().getSimpleName() + ": " + safeMessage(e)
                            + "; rollback=" + rollback,
                    getCurrentServer(context),
                    rollback
            );
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private static boolean rollback(Context context,
                                    DevicePolicyManager dpm,
                                    ComponentName admin,
                                    String previousServer,
                                    boolean previousAutoTime,
                                    boolean previousDateTimeRestriction) {
        boolean success = true;
        try {
            success &= Settings.Global.putString(
                    context.getContentResolver(),
                    KEY_NTP_SERVER,
                    previousServer
            );
        } catch (RuntimeException ignored) {
            success = false;
        }
        try {
            dpm.setAutoTimeEnabled(admin, previousAutoTime);
        } catch (RuntimeException ignored) {
            success = false;
        }
        try {
            if (previousDateTimeRestriction) {
                dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_DATE_TIME);
            } else {
                dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_DATE_TIME);
            }
        } catch (RuntimeException ignored) {
            success = false;
        }
        return success;
    }

    private static String buildUnavailableMessage(SystemNtpPolicy.ManagementAvailability availability) {
        if (availability == SystemNtpPolicy.ManagementAvailability.UNSUPPORTED_ANDROID_VERSION) {
            return "System NTP management requires Android 11 or later";
        }
        if (availability == SystemNtpPolicy.ManagementAvailability.NOT_DEVICE_OWNER) {
            return "App is not Device Owner";
        }
        if (availability == SystemNtpPolicy.ManagementAvailability.WRITE_PERMISSION_MISSING) {
            return "WRITE_SECURE_SETTINGS is not granted";
        }
        return "System NTP management unavailable";
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.trim().isEmpty() ? "unknown error" : message.trim();
    }

    public static final class ApplyResult {
        public final boolean success;
        public final String message;
        public final String server;
        public final boolean rollbackSucceeded;

        private ApplyResult(boolean success,
                            String message,
                            String server,
                            boolean rollbackSucceeded) {
            this.success = success;
            this.message = message;
            this.server = server;
            this.rollbackSucceeded = rollbackSucceeded;
        }

        static ApplyResult success(String server, String message) {
            return new ApplyResult(true, message, server, true);
        }

        static ApplyResult failure(String message, String server, boolean rollbackSucceeded) {
            return new ApplyResult(false, message, server, rollbackSucceeded);
        }
    }
}

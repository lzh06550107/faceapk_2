package com.punch.app.utils;

import android.annotation.SuppressLint;
import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

import com.punch.app.network.InteractionLogger;

public final class DeviceIdentityUtils {
    private static final String TAG = "DeviceIdentity";
    private static final long LOG_THROTTLE_MS = 60_000L;
    private static volatile long lastUnavailableLogAt;
    private static volatile String lastUnavailableReason = "";

    private DeviceIdentityUtils() {
    }

    public static String getDeviceSerial(Context context) {
        String serial = readBuildSerial(context);
        if (isUsableSerial(serial)) {
            return serial.trim();
        }
        return "";
    }

    @SuppressLint({"HardwareIds", "MissingPermission"})
    private static String readBuildSerial(Context context) {
        try {
            if (context != null) {
                KioskManager.ensureOwnerRuntimePermissions(context);
            }
            String serial;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                serial = Build.getSerial();
            } else {
                serial = Build.SERIAL;
            }
            if (!isUsableSerial(serial)) {
                logSerialUnavailable(context, "Build serial is empty or unknown: " + String.valueOf(serial), false);
            }
            return serial;
        } catch (SecurityException e) {
            logSerialUnavailable(context, "SecurityException: " + safeMessage(e), true);
            return "";
        } catch (Exception e) {
            logSerialUnavailable(context, e.getClass().getSimpleName() + ": " + safeMessage(e), true);
            return "";
        }
    }

    private static void logSerialUnavailable(Context context, String reason, boolean failure) {
        String safeReason = reason == null ? "" : reason;
        long now = System.currentTimeMillis();
        if (safeReason.equals(lastUnavailableReason) && now - lastUnavailableLogAt < LOG_THROTTLE_MS) {
            return;
        }
        lastUnavailableReason = safeReason;
        lastUnavailableLogAt = now;
        String detail = buildDebugDetail(context, reason);
        if (failure) {
            InteractionLogger.logBusinessFailure(InteractionLogger.GROUP_GENERAL, "读取设备序列号失败", detail);
        } else {
            InteractionLogger.logBusiness(InteractionLogger.GROUP_GENERAL, "设备序列号不可用", detail);
        }
        AppLogger.w(TAG, detail);
    }

    @SuppressLint("HardwareIds")
    private static String buildDebugDetail(Context context, String reason) {
        boolean managedDevice = false;
        boolean systemAppMode = false;
        boolean deviceOwner = false;
        boolean readPhoneStateGranted = false;
        String packageName = "";
        String managementMode = "Unmanaged";
        if (context != null) {
            packageName = context.getPackageName();
            managedDevice = KioskManager.isManagedDevice(context);
            systemAppMode = KioskManager.isSystemAppMode(context);
            deviceOwner = KioskManager.isDeviceOwner(context);
            managementMode = KioskManager.managementModeLabel(context);
            readPhoneStateGranted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_PHONE_STATE
            ) == PackageManager.PERMISSION_GRANTED;
        }
        return "reason=" + (reason == null ? "" : reason)
                + "\npackage=" + packageName
                + "\nsdk=" + Build.VERSION.SDK_INT
                + "\nmanagementMode=" + managementMode
                + "\nmanagedDevice=" + managedDevice
                + "\nsystemAppMode=" + systemAppMode
                + "\ndeviceOwner=" + deviceOwner
                + "\nreadPhoneStateGranted=" + readPhoneStateGranted
                + "\nbuildSerialField=" + String.valueOf(Build.SERIAL);
    }

    private static String safeMessage(Exception e) {
        if (e == null || e.getMessage() == null || e.getMessage().trim().isEmpty()) {
            return "";
        }
        return e.getMessage().trim();
    }

    private static boolean isUsableSerial(String serial) {
        if (serial == null) {
            return false;
        }
        String value = serial.trim();
        return !value.isEmpty()
                && !"unknown".equalsIgnoreCase(value)
                && !"null".equalsIgnoreCase(value);
    }
}

package com.punch.app.utils;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Process;
import android.os.UserHandle;

import java.lang.reflect.Method;

/**
 * Minimal Android 12 platform-signature bootstrap.
 *
 * <p>The app keeps the existing Device Owner architecture. When the APK is signed with the
 * same platform certificate as the Android framework, signature permissions allow it to grant
 * its own dangerous runtime permissions and enable the global location switch without asking
 * the user to visit Settings.</p>
 */
public final class PlatformPermissionBootstrap {
    private static final String TAG = "PlatformPermissionBoot";
    private static final String ANDROID_PACKAGE = "android";
    private static final String PERMISSION_GRANT_RUNTIME_PERMISSIONS =
            "android.permission.GRANT_RUNTIME_PERMISSIONS";
    private static final String PERMISSION_WRITE_SECURE_SETTINGS =
            "android.permission.WRITE_SECURE_SETTINGS";

    private PlatformPermissionBootstrap() {
    }

    public static boolean prepareWifiScanAccess(Context context) {
        if (context == null) {
            return false;
        }
        boolean runtimePermissionsReady = ensureWifiScanRuntimePermissions(context);
        boolean locationReady = ensureLocationEnabled(context);
        return runtimePermissionsReady && locationReady;
    }

    public static boolean ensureWifiScanRuntimePermissions(Context context) {
        if (context == null) {
            return false;
        }

        boolean coarse = grantRuntimePermissionIfNeeded(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
        );
        boolean fine = grantRuntimePermissionIfNeeded(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
        );

        boolean nearby = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            nearby = grantRuntimePermissionIfNeeded(
                    context,
                    Manifest.permission.NEARBY_WIFI_DEVICES
            );
        }

        boolean success = coarse && fine && nearby;
        AppLogger.i(TAG, "Wi-Fi runtime permissions ready=" + success);
        return success;
    }

    public static boolean ensureLocationEnabled(Context context) {
        if (context == null) {
            return false;
        }
        LocationManager locationManager =
                (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            AppLogger.w(TAG, "LocationManager unavailable");
            return false;
        }
        if (locationManager.isLocationEnabled()) {
            return true;
        }
        if (!isPlatformSigned(context)) {
            AppLogger.w(TAG, "Location is disabled and APK is not platform-signed");
            return false;
        }
        if (context.checkSelfPermission(PERMISSION_WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) {
            AppLogger.w(TAG, "WRITE_SECURE_SETTINGS is not granted");
            return false;
        }

        try {
            Method method = LocationManager.class.getMethod(
                    "setLocationEnabledForUser",
                    boolean.class,
                    UserHandle.class
            );
            method.invoke(locationManager, true, Process.myUserHandle());
            boolean enabled = locationManager.isLocationEnabled();
            AppLogger.i(TAG, "Enabled global location for Wi-Fi scan=" + enabled);
            return enabled;
        } catch (Exception e) {
            AppLogger.e(TAG, "Failed to enable global location for Wi-Fi scan", e);
            return false;
        }
    }

    public static boolean isPlatformSigned(Context context) {
        if (context == null) {
            return false;
        }
        try {
            return context.getPackageManager().checkSignatures(
                    ANDROID_PACKAGE,
                    context.getPackageName()
            ) == PackageManager.SIGNATURE_MATCH;
        } catch (RuntimeException e) {
            AppLogger.e(TAG, "Failed to check platform signature", e);
            return false;
        }
    }

    private static boolean grantRuntimePermissionIfNeeded(Context context, String permission) {
        if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        if (!isPlatformSigned(context)) {
            AppLogger.w(TAG, "Skip self-grant because APK is not platform-signed: " + permission);
            return false;
        }
        if (context.checkSelfPermission(PERMISSION_GRANT_RUNTIME_PERMISSIONS)
                != PackageManager.PERMISSION_GRANTED) {
            AppLogger.w(TAG, "GRANT_RUNTIME_PERMISSIONS is not granted");
            return false;
        }

        try {
            PackageManager packageManager = context.getPackageManager();
            Method method = PackageManager.class.getMethod(
                    "grantRuntimePermission",
                    String.class,
                    String.class,
                    UserHandle.class
            );
            method.invoke(
                    packageManager,
                    context.getPackageName(),
                    permission,
                    Process.myUserHandle()
            );
            boolean granted =
                    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
            AppLogger.i(TAG, "Self-granted " + permission + "=" + granted);
            return granted;
        } catch (Exception e) {
            AppLogger.e(TAG, "Failed to self-grant " + permission, e);
            return false;
        }
    }
}

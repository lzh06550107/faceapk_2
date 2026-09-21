package com.punch.app.utils;

import android.Manifest;
import android.app.Activity;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Android 12 privileged-system-app device controller.
 *
 * <p>This backend is used only when the APK is installed from the system image and is signed
 * with the same platform certificate as the Android framework. It deliberately avoids
 * android.uid.system: the app keeps its own Linux UID and receives only the platform/privileged
 * permissions declared in the manifest and ROM allowlist.</p>
 *
 * <p>Hidden StatusBar APIs are invoked reflectively so the normal Gradle project can still
 * compile against the public SDK. On the production ROM the platform-signed system app is
 * expected to be exempt from non-SDK restrictions.</p>
 */
public final class SystemAppController {
    private static final String TAG = "SystemAppController";

    public static final String PERMISSION_WRITE_SECURE_SETTINGS =
            "android.permission.WRITE_SECURE_SETTINGS";
    public static final String PERMISSION_WRITE_SETTINGS =
            "android.permission.WRITE_SETTINGS";
    public static final String PERMISSION_STATUS_BAR =
            "android.permission.STATUS_BAR";
    public static final String PERMISSION_INSTALL_PACKAGES =
            "android.permission.INSTALL_PACKAGES";
    public static final String PERMISSION_REBOOT =
            "android.permission.REBOOT";
    public static final String PERMISSION_MANAGE_USERS =
            "android.permission.MANAGE_USERS";
    public static final String PERMISSION_SET_PREFERRED_APPLICATIONS =
            "android.permission.SET_PREFERRED_APPLICATIONS";
    public static final String PERMISSION_GRANT_RUNTIME_PERMISSIONS =
            "android.permission.GRANT_RUNTIME_PERMISSIONS";
    public static final String PERMISSION_READ_PRIVILEGED_PHONE_STATE =
            "android.permission.READ_PRIVILEGED_PHONE_STATE";
    public static final String PERMISSION_START_ACTIVITIES_FROM_BACKGROUND =
            "android.permission.START_ACTIVITIES_FROM_BACKGROUND";

    private static final String[] REQUIRED_PRODUCTION_PERMISSIONS = {
            PERMISSION_WRITE_SECURE_SETTINGS,
            PERMISSION_WRITE_SETTINGS,
            PERMISSION_STATUS_BAR,
            PERMISSION_INSTALL_PACKAGES,
            PERMISSION_MANAGE_USERS,
            PERMISSION_SET_PREFERRED_APPLICATIONS,
            PERMISSION_GRANT_RUNTIME_PERMISSIONS,
            PERMISSION_READ_PRIVILEGED_PHONE_STATE,
            PERMISSION_START_ACTIVITIES_FROM_BACKGROUND
    };

    private static volatile boolean kioskPoliciesApplied;
    @SuppressWarnings("deprecation")
    private static KeyguardManager.KeyguardLock keyguardLock;

    private SystemAppController() {
    }

    public static boolean isPlatformSystemApp(Context context) {
        if (context == null) {
            return false;
        }
        boolean systemImage = false;
        boolean platformSigned = false;
        try {
            ApplicationInfo appInfo = context.getApplicationInfo();
            systemImage = (appInfo.flags
                    & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            platformSigned = context.getPackageManager().checkSignatures(
                    "android",
                    context.getPackageName()
            ) == PackageManager.SIGNATURE_MATCH;
        } catch (RuntimeException e) {
            AppLogger.e(TAG, "Unable to inspect system-app identity", e);
        }
        return SystemAppModePolicy.isSupported(
                Build.VERSION.SDK_INT,
                systemImage,
                platformSigned
        );
    }

    public static boolean hasPermission(Context context, String permission) {
        return context != null
                && permission != null
                && context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    public static List<String> getMissingProductionPrivileges(Context context) {
        List<String> missing = new ArrayList<>();
        if (!isPlatformSystemApp(context)) {
            missing.add("platform_system_app_identity");
            return missing;
        }
        for (String permission : REQUIRED_PRODUCTION_PERMISSIONS) {
            if (!hasPermission(context, permission)) {
                missing.add(permission);
            }
        }
        return missing;
    }

    public static boolean hasAllProductionPrivileges(Context context) {
        return getMissingProductionPrivileges(context).isEmpty();
    }

    public static String describeMissingProductionPrivileges(Context context) {
        List<String> missing = getMissingProductionPrivileges(context);
        if (missing.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String item : missing) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            if (item != null && item.startsWith("android.permission.")) {
                builder.append(item.substring("android.permission.".length()));
            } else {
                builder.append(item);
            }
        }
        return builder.toString();
    }

    public static boolean canManageSystemSettings(Context context) {
        return isPlatformSystemApp(context)
                && hasPermission(context, PERMISSION_WRITE_SECURE_SETTINGS);
    }

    public static boolean canManageScreenSettings(Context context) {
        return canManageSystemSettings(context)
                && hasPermission(context, PERMISSION_WRITE_SETTINGS);
    }

    public static boolean canInstallPackages(Context context) {
        return isPlatformSystemApp(context)
                && hasPermission(context, PERMISSION_INSTALL_PACKAGES);
    }

    public static boolean canControlStatusBar(Context context) {
        return isPlatformSystemApp(context)
                && hasPermission(context, PERMISSION_STATUS_BAR);
    }

    public static boolean canManageUsers(Context context) {
        return isPlatformSystemApp(context)
                && hasPermission(context, PERMISSION_MANAGE_USERS);
    }

    public static boolean canReboot(Context context) {
        return isPlatformSystemApp(context)
                && hasPermission(context, PERMISSION_REBOOT);
    }

    public static boolean isKioskPoliciesApplied() {
        return kioskPoliciesApplied;
    }

    public static boolean applyKioskPolicies(Context context) {
        if (!isPlatformSystemApp(context)) {
            return false;
        }
        boolean success = true;
        success &= ensureRuntimePermissions(context);
        success &= applyUserRestrictions(context, true);
        success &= makeKioskHomePreferred(context);
        success &= setStatusBarKioskState(context, true);
        disableKeyguard(context);
        ScreenTimeoutPolicyManager.applyConfiguredPolicy(context);
        kioskPoliciesApplied = success;
        AppLogger.i(TAG, "Android 12 system-app kiosk policies applied=" + success);
        return success;
    }

    public static boolean clearKioskPolicies(Context context) {
        if (!isPlatformSystemApp(context)) {
            return false;
        }
        boolean success = true;
        success &= setStatusBarKioskState(context, false);
        success &= applyUserRestrictions(context, false);
        clearPreferredHome(context);
        reenableKeyguard();
        kioskPoliciesApplied = false;
        AppLogger.i(TAG, "Android 12 system-app kiosk policies cleared=" + success);
        return success;
    }

    public static void applyActivityKioskUi(Activity activity) {
        if (activity == null || !isPlatformSystemApp(activity)
                || !SessionManager.get().isKioskEnabled()) {
            return;
        }
        View decor = activity.getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
    }

    public static void clearActivityKioskUi(Activity activity) {
        if (activity == null) {
            return;
        }
        activity.getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    public static boolean ensureRuntimePermissions(Context context) {
        if (!isPlatformSystemApp(context)) {
            return false;
        }
        String[] permissions = new String[]{
                Manifest.permission.CAMERA,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
        };
        boolean success = true;
        for (String permission : permissions) {
            if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                continue;
            }
            if (!grantRuntimePermission(context, permission)) {
                success = false;
            }
        }
        return success;
    }

    private static boolean grantRuntimePermission(Context context, String permission) {
        if (!hasPermission(context, PERMISSION_GRANT_RUNTIME_PERMISSIONS)) {
            AppLogger.w(TAG, "GRANT_RUNTIME_PERMISSIONS missing; cannot self-grant " + permission);
            return false;
        }
        try {
            PackageManager pm = context.getPackageManager();
            Method method = PackageManager.class.getMethod(
                    "grantRuntimePermission",
                    String.class,
                    String.class,
                    UserHandle.class
            );
            method.invoke(
                    pm,
                    context.getPackageName(),
                    permission,
                    Process.myUserHandle()
            );
            boolean granted =
                    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
            AppLogger.i(TAG, "Self-granted runtime permission " + permission + "=" + granted);
            return granted;
        } catch (Exception e) {
            AppLogger.e(TAG, "Unable to self-grant runtime permission " + permission, e);
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private static boolean applyUserRestrictions(Context context, boolean enabled) {
        if (!canManageUsers(context)) {
            AppLogger.w(TAG, "MANAGE_USERS missing; kiosk user restrictions unavailable");
            return false;
        }
        UserManager userManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
        if (userManager == null) {
            return false;
        }
        try {
            userManager.setUserRestriction(UserManager.DISALLOW_SAFE_BOOT, enabled);
            userManager.setUserRestriction(UserManager.DISALLOW_FACTORY_RESET, enabled);
            userManager.setUserRestriction(UserManager.DISALLOW_ADD_USER, enabled);
            userManager.setUserRestriction(UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA, enabled);
            return true;
        } catch (RuntimeException e) {
            AppLogger.e(TAG, "Unable to apply system-app user restrictions", e);
            return false;
        }
    }

    private static boolean makeKioskHomePreferred(Context context) {
        if (!hasPermission(context, PERMISSION_SET_PREFERRED_APPLICATIONS)) {
            AppLogger.w(TAG, "SET_PREFERRED_APPLICATIONS missing; cannot persist kiosk HOME");
            return false;
        }
        PackageManager pm = context.getPackageManager();
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        homeIntent.addCategory(Intent.CATEGORY_DEFAULT);
        List<ResolveInfo> homeActivities = pm.queryIntentActivities(
                homeIntent,
                PackageManager.MATCH_DEFAULT_ONLY
        );
        List<ComponentName> candidates = new ArrayList<>();
        if (homeActivities != null) {
            for (ResolveInfo info : homeActivities) {
                if (info == null || info.activityInfo == null) {
                    continue;
                }
                candidates.add(new ComponentName(
                        info.activityInfo.packageName,
                        info.activityInfo.name
                ));
            }
        }
        ComponentName target = new ComponentName(
                context,
                "com.punch.app.activity.KioskHomeActivity"
        );
        if (!candidates.contains(target)) {
            candidates.add(target);
        }

        IntentFilter filter = new IntentFilter(Intent.ACTION_MAIN);
        filter.addCategory(Intent.CATEGORY_HOME);
        filter.addCategory(Intent.CATEGORY_DEFAULT);
        try {
            pm.clearPackagePreferredActivities(context.getPackageName());
            pm.addPreferredActivity(
                    filter,
                    IntentFilter.MATCH_CATEGORY_EMPTY,
                    candidates.toArray(new ComponentName[0]),
                    target
            );
            ResolveInfo resolved = pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY);
            boolean selected = resolved != null
                    && resolved.activityInfo != null
                    && context.getPackageName().equals(resolved.activityInfo.packageName)
                    && target.getClassName().equals(resolved.activityInfo.name);
            AppLogger.i(TAG, "Kiosk HOME preferred=" + selected);
            return selected;
        } catch (RuntimeException e) {
            AppLogger.e(TAG, "Unable to set kiosk HOME", e);
            return false;
        }
    }

    private static void clearPreferredHome(Context context) {
        try {
            context.getPackageManager()
                    .clearPackagePreferredActivities(context.getPackageName());
        } catch (RuntimeException e) {
            AppLogger.w(TAG, "Unable to clear kiosk HOME preference: " + e.getMessage());
        }
    }

    private static boolean setStatusBarKioskState(Context context, boolean enabled) {
        if (!canControlStatusBar(context)) {
            AppLogger.w(TAG, "STATUS_BAR permission missing; SystemUI kiosk restrictions unavailable");
            return false;
        }
        try {
            Object statusBar = context.getSystemService("statusbar");
            if (statusBar == null) {
                return false;
            }
            Class<?> clazz = Class.forName("android.app.StatusBarManager");
            int flags = 0;
            int flags2 = 0;
            if (enabled) {
                flags |= readStaticInt(clazz, "DISABLE_EXPAND");
                flags |= readStaticInt(clazz, "DISABLE_NOTIFICATION_ICONS");
                flags |= readStaticInt(clazz, "DISABLE_NOTIFICATION_ALERTS");
                flags |= readStaticInt(clazz, "DISABLE_HOME");
                flags |= readStaticInt(clazz, "DISABLE_RECENT");
                flags |= readStaticInt(clazz, "DISABLE_BACK");
                flags2 |= readStaticInt(clazz, "DISABLE2_QUICK_SETTINGS");
            }
            Method disable = clazz.getMethod("disable", int.class);
            disable.invoke(statusBar, flags);
            try {
                Method disable2 = clazz.getMethod("disable2", int.class);
                disable2.invoke(statusBar, flags2);
            } catch (NoSuchMethodException ignored) {
                // Android 12 normally has disable2; tolerate vendor variants without it.
            }
            return true;
        } catch (Exception e) {
            AppLogger.e(TAG, "Unable to configure Android 12 StatusBar kiosk state", e);
            return false;
        }
    }

    private static int readStaticInt(Class<?> clazz, String fieldName) throws Exception {
        Field field = clazz.getField(fieldName);
        return field.getInt(null);
    }

    @SuppressWarnings("deprecation")
    private static void disableKeyguard(Context context) {
        try {
            KeyguardManager manager =
                    (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
            if (manager == null) {
                return;
            }
            if (keyguardLock == null) {
                keyguardLock = manager.newKeyguardLock("FaceAPK-SystemKiosk");
            }
            keyguardLock.disableKeyguard();
        } catch (RuntimeException e) {
            AppLogger.w(TAG, "Unable to disable non-secure keyguard: " + e.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    private static void reenableKeyguard() {
        try {
            if (keyguardLock != null) {
                keyguardLock.reenableKeyguard();
                keyguardLock = null;
            }
        } catch (RuntimeException e) {
            AppLogger.w(TAG, "Unable to re-enable keyguard: " + e.getMessage());
        }
    }
}

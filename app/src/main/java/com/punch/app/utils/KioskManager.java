package com.punch.app.utils;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.AppTask;
import android.app.ActivityManager.RecentTaskInfo;
import android.app.KeyguardManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.KeyEvent;
import android.os.UserManager;

import java.util.List;

import com.punch.app.receiver.KioskDeviceAdminReceiver;

public final class KioskManager {
    private static final String TAG = "KioskManager";
    private static final long[] ENTER_RETRY_DELAYS_MS = {200L, 600L, 1200L};
    private static final long RESTORE_DELAY_MS = 500L;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final DevicePolicyApplyGate OWNER_POLICY_GATE = new DevicePolicyApplyGate();
    private static volatile boolean uiTestBypassEnabled = false;
    private static volatile boolean deviceTestMaintenanceModeEnabled = false;
    private static Runnable pendingAppTaskRestore;

    private KioskManager() {
    }

    public static void enterIfPossible(Activity activity) {
        if (uiTestBypassEnabled || deviceTestMaintenanceModeEnabled) {
            return;
        }
        enterIfPossible(activity, 0);
    }

    private static void enterIfPossible(Activity activity, int attempt) {
        if (activity == null) {
            return;
        }
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        if (isDeviceOwner(activity) && !SessionManager.get().isKioskEnabled()) {
            SessionManager.get().saveKioskEnabled(true);
            AppLogger.i(TAG, "Device owner detected, force kiosk enabled");
        }
        if (!SessionManager.get().isKioskEnabled()) {
            AppLogger.i(TAG, "Kiosk disabled, skip enter");
            return;
        }
        ensureOwnerKioskPolicies(activity);
        if (!isLockTaskPermitted(activity)) {
            AppLogger.w(TAG, "Lock task not permitted for package " + activity.getPackageName());
            scheduleEnterRetryIfNeeded(activity, attempt);
            return;
        }
        if (isInLockedTaskMode(activity)) {
            return;
        }
        try {
            activity.startLockTask();
            AppLogger.i(TAG, "Requested lock task mode, state=" + getLockTaskModeState(activity));
        } catch (Exception e) {
            AppLogger.e(TAG, "Failed to enter lock task mode", e);
        }
        scheduleEnterRetryIfNeeded(activity, attempt);
    }

    private static void scheduleEnterRetryIfNeeded(Activity activity, int attempt) {
        if (isInLockedTaskMode(activity) || attempt >= ENTER_RETRY_DELAYS_MS.length) {
            return;
        }
        long delayMs = ENTER_RETRY_DELAYS_MS[attempt];
        MAIN_HANDLER.postDelayed(() -> enterIfPossible(activity, attempt + 1), delayMs);
    }

    public static void exitAndDisable(Activity activity) {
        exitAndDisableInternal(activity, false);
    }

    public static boolean exitForDeviceOwnerRemoval(Activity activity) {
        return exitAndDisableInternal(activity, true);
    }

    private static boolean exitAndDisableInternal(Activity activity, boolean allowDeviceOwnerExit) {
        if (activity == null) {
            return false;
        }
        if (isDeviceOwner(activity) && !allowDeviceOwnerExit) {
            SessionManager.get().saveKioskEnabled(true);
            AppLogger.w(TAG, "Device owner cannot exit kiosk without owner removal");
            enterIfPossible(activity);
            return false;
        }
        if (allowDeviceOwnerExit && isDeviceOwner(activity)) {
            ScreenTimeoutPolicyManager.ApplyResult restoreResult =
                    ScreenTimeoutPolicyManager.restoreOriginalSettings(activity);
            if (!restoreResult.success) {
                AppLogger.w(TAG, "Failed to restore screen settings: " + restoreResult.message);
                return false;
            }
        }
        SessionManager.get().saveKioskEnabled(false);
        clearOwnerPolicies(activity);
        if (getLockTaskModeState(activity) == ActivityManager.LOCK_TASK_MODE_NONE) {
            AppLogger.i(TAG, "Lock task already inactive");
            return true;
        }
        try {
            activity.stopLockTask();
            AppLogger.i(TAG, "Exited lock task mode");
        } catch (Exception e) {
            AppLogger.e(TAG, "Failed to exit lock task mode", e);
        }
        return true;
    }

    public static void enableAndEnter(Activity activity) {
        if (activity == null) {
            return;
        }
        SessionManager.get().saveKioskEnabled(true);
        enterIfPossible(activity);
    }

    public static boolean isDeviceOwner(Context context) {
        if (uiTestBypassEnabled) {
            return false;
        }
        DevicePolicyManager dpm = getDevicePolicyManager(context);
        return dpm != null && dpm.isDeviceOwnerApp(context.getPackageName());
    }

    public static boolean isLockTaskPermitted(Context context) {
        if (uiTestBypassEnabled) {
            return false;
        }
        DevicePolicyManager dpm = getDevicePolicyManager(context);
        return dpm != null && dpm.isLockTaskPermitted(context.getPackageName());
    }

    public static boolean isInLockedTaskMode(Context context) {
        return getLockTaskModeState(context) == ActivityManager.LOCK_TASK_MODE_LOCKED;
    }

    public static boolean shouldBlockSystemKey(int keyCode) {
        if (uiTestBypassEnabled || deviceTestMaintenanceModeEnabled) {
            return false;
        }
        if (!SessionManager.get().isKioskEnabled()) {
            return false;
        }
        return keyCode == KeyEvent.KEYCODE_BACK
                || keyCode == KeyEvent.KEYCODE_HOME
                || keyCode == KeyEvent.KEYCODE_APP_SWITCH;
    }

    public static void ensureOwnerRuntimePermissions(Context context) {
        if (uiTestBypassEnabled) {
            return;
        }
        DevicePolicyManager dpm = getDevicePolicyManager(context);
        ComponentName admin = getAdminComponent(context);
        if (dpm == null || admin == null) {
            return;
        }
        if (!dpm.isDeviceOwnerApp(context.getPackageName())) {
            return;
        }
        grantRuntimePermission(dpm, admin, context, Manifest.permission.READ_PHONE_STATE);
        grantRuntimePermission(dpm, admin, context, Manifest.permission.CAMERA);
        grantRuntimePermission(dpm, admin, context, Manifest.permission.ACCESS_COARSE_LOCATION);
        grantRuntimePermission(dpm, admin, context, Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            grantRuntimePermission(dpm, admin, context, Manifest.permission.NEARBY_WIFI_DEVICES);
        }
    }

    public static void ensureOwnerKioskPolicies(Context context) {
        if (uiTestBypassEnabled || deviceTestMaintenanceModeEnabled) {
            return;
        }
        DevicePolicyManager dpm = getDevicePolicyManager(context);
        ComponentName admin = getAdminComponent(context);
        if (dpm == null || admin == null) {
            return;
        }
        if (!dpm.isDeviceOwnerApp(context.getPackageName())) {
            return;
        }
        if (!OWNER_POLICY_GATE.tryBegin()) {
            return;
        }
        boolean applied = false;
        try {
            dpm.clearPackagePersistentPreferredActivities(admin, context.getPackageName());
            dpm.setLockTaskPackages(admin, new String[]{context.getPackageName()});
            ensureOwnerRuntimePermissions(context);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                int lockTaskFeatures = DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
                        | DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO;
                dpm.setLockTaskFeatures(admin, lockTaskFeatures);
            }
            dpm.setStatusBarDisabled(admin, false);
            dpm.setKeyguardDisabled(admin, true);
            dpm.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT);
            dpm.addUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET);
            dpm.addUserRestriction(admin, UserManager.DISALLOW_ADD_USER);
            dpm.addUserRestriction(admin, UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA);
            ScreenTimeoutPolicyManager.applyConfiguredPolicy(context);
            IntentFilter homeFilter = new IntentFilter(Intent.ACTION_MAIN);
            homeFilter.addCategory(Intent.CATEGORY_HOME);
            homeFilter.addCategory(Intent.CATEGORY_DEFAULT);
            dpm.addPersistentPreferredActivity(
                    admin,
                    homeFilter,
                    new ComponentName(context, "com.punch.app.activity.KioskHomeActivity")
            );
            AppLogger.i(TAG, "Applied kiosk owner policies");
            applied = true;
        } catch (Exception e) {
            AppLogger.e(TAG, "Failed to apply kiosk owner policies", e);
        } finally {
            OWNER_POLICY_GATE.finish(applied);
        }
    }

    public static void setDeviceTestMaintenanceModeForTest(boolean enabled) {
        deviceTestMaintenanceModeEnabled = enabled;
        OWNER_POLICY_GATE.invalidate();
        if (enabled) {
            cancelPendingAppTaskRestore();
        }
    }

    public static boolean isDeviceTestMaintenanceModeForTest() {
        return deviceTestMaintenanceModeEnabled;
    }

    public static boolean enterDeviceTestMaintenanceMode(Context context, String[] testPackages) {
        if (context == null) {
            return false;
        }
        DevicePolicyManager dpm = getDevicePolicyManager(context);
        ComponentName admin = getAdminComponent(context);
        if (dpm == null || admin == null || !dpm.isDeviceOwnerApp(context.getPackageName())) {
            AppLogger.w(TAG, "Device-test maintenance requires active Device Owner");
            return false;
        }

        setDeviceTestMaintenanceModeForTest(true);
        SessionManager.get().saveKioskEnabled(false);
        cancelPendingAppTaskRestore();

        try {
            dpm.clearPackagePersistentPreferredActivities(admin, context.getPackageName());
            dpm.setStatusBarDisabled(admin, false);
            dpm.setKeyguardDisabled(admin, false);

            if (testPackages != null && testPackages.length > 0) {
                for (String packageName : testPackages) {
                    if (packageName == null || packageName.trim().isEmpty()) {
                        continue;
                    }
                    try {
                        dpm.setApplicationHidden(admin, packageName, false);
                    } catch (Exception e) {
                        AppLogger.w(TAG, "Unable to unhide test package " + packageName + ": " + e.getMessage());
                    }
                }
                try {
                    String[] failures = dpm.setPackagesSuspended(admin, testPackages, false);
                    if (failures != null && failures.length > 0) {
                        AppLogger.w(TAG, "Some test packages could not be unsuspended: " + java.util.Arrays.toString(failures));
                    }
                } catch (Exception e) {
                    AppLogger.w(TAG, "Unable to unsuspend test packages: " + e.getMessage());
                }
            }

            // Important: do not call Activity.stopLockTask() here. The maintenance
            // controller is not guaranteed to be the Activity that originally called
            // startLockTask(). On Android 6.0+ a DPC exits the active lock task by
            // revoking the locked package's allowlist authorization. Keep the
            // allowlist empty for the entire maintenance window so Smoke activities
            // cannot automatically re-enter lock task via android:lockTaskMode.
            dpm.setLockTaskPackages(admin, new String[]{});

            AppLogger.i(TAG, "Entered Device Owner test maintenance policy; waiting for LockTask to exit");
            return true;
        } catch (Exception e) {
            AppLogger.e(TAG, "Failed to enter Device Owner test maintenance mode", e);
            return false;
        }
    }

    public static boolean restoreDeviceOwnerKioskAfterTest(Context context, String[] testPackages) {
        if (context == null) {
            return false;
        }
        DevicePolicyManager dpm = getDevicePolicyManager(context);
        ComponentName admin = getAdminComponent(context);
        if (dpm == null || admin == null || !dpm.isDeviceOwnerApp(context.getPackageName())) {
            AppLogger.w(TAG, "Device-test restore requires active Device Owner");
            return false;
        }

        try {
            if (testPackages != null && testPackages.length > 0) {
                try {
                    String[] failures = dpm.setPackagesSuspended(admin, testPackages, true);
                    if (failures != null && failures.length > 0) {
                        AppLogger.w(TAG, "Some test packages could not be re-suspended: " + java.util.Arrays.toString(failures));
                    }
                } catch (Exception e) {
                    AppLogger.w(TAG, "Unable to re-suspend test packages: " + e.getMessage());
                }
            }
            SessionManager.get().saveKioskEnabled(true);
            setDeviceTestMaintenanceModeForTest(false);
            ensureOwnerKioskPolicies(context);
            AppLogger.i(TAG, "Restored Device Owner kiosk policies after test maintenance");
            return true;
        } catch (Exception e) {
            AppLogger.e(TAG, "Failed to restore Device Owner kiosk after test maintenance", e);
            return false;
        }
    }

    private static void grantRuntimePermission(DevicePolicyManager dpm,
                                               ComponentName admin,
                                               Context context,
                                               String permission) {
        if (permission == null || permission.trim().isEmpty()) {
            return;
        }
        if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            dpm.setPermissionGrantState(
                    admin,
                    context.getPackageName(),
                    permission,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            );
            AppLogger.i(TAG, "Granted runtime permission: " + permission);
        } catch (Exception e) {
            AppLogger.e(TAG, "Failed to grant runtime permission: " + permission, e);
        }
    }

    private static void clearOwnerPolicies(Context context) {
        OWNER_POLICY_GATE.invalidate();
        DevicePolicyManager dpm = getDevicePolicyManager(context);
        ComponentName admin = getAdminComponent(context);
        if (dpm == null || admin == null) {
            return;
        }
        if (!dpm.isDeviceOwnerApp(context.getPackageName())) {
            return;
        }
        try {
            dpm.setStatusBarDisabled(admin, false);
            dpm.setKeyguardDisabled(admin, false);
            dpm.setLockTaskPackages(admin, new String[]{});
            dpm.clearPackagePersistentPreferredActivities(admin, context.getPackageName());
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT);
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET);
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_ADD_USER);
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA);
            AppLogger.i(TAG, "Cleared kiosk owner policies");
        } catch (Exception e) {
            AppLogger.e(TAG, "Failed to clear kiosk owner policies", e);
        }
    }

    public static boolean bringExistingAppTaskToFront(Context context, int excludedTaskId) {
        return bringExistingAppTaskToFront(context, excludedTaskId, true);
    }

    public static boolean bringExistingAppTaskToFrontQuietly(Context context, int excludedTaskId) {
        return bringExistingAppTaskToFront(context, excludedTaskId, false);
    }

    public static void restoreAppTaskSoon(Context context) {
        if (uiTestBypassEnabled || deviceTestMaintenanceModeEnabled) {
            return;
        }
        if (!canAttemptForegroundRestore(context)) {
            return;
        }
        Context appContext = context.getApplicationContext();
        cancelPendingAppTaskRestore();
        pendingAppTaskRestore = () -> {
            pendingAppTaskRestore = null;
            if (canAttemptForegroundRestore(appContext)) {
                bringExistingAppTaskToFrontQuietly(appContext, -1);
            }
        };
        MAIN_HANDLER.postDelayed(pendingAppTaskRestore, RESTORE_DELAY_MS);
    }

    public static void cancelPendingAppTaskRestore() {
        Runnable pending = pendingAppTaskRestore;
        if (pending != null) {
            MAIN_HANDLER.removeCallbacks(pending);
            pendingAppTaskRestore = null;
        }
    }

    public static boolean shouldRestoreAppTask(
            Context context,
            boolean changingConfigurations,
            int resumedActivityCount
    ) {
        if (context == null || deviceTestMaintenanceModeEnabled) {
            return false;
        }
        return KioskRestorePolicy.shouldRestore(
                SessionManager.get().isKioskEnabled(),
                isDeviceOwner(context),
                isScreenInteractive(context),
                isKeyguardLocked(context),
                changingConfigurations,
                resumedActivityCount
        );
    }

    private static boolean bringExistingAppTaskToFront(Context context, int excludedTaskId, boolean logSuccess) {
        if (!canAttemptForegroundRestore(context)) {
            return false;
        }
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) {
            return false;
        }
        List<AppTask> appTasks = am.getAppTasks();
        if (appTasks == null || appTasks.isEmpty()) {
            return false;
        }
        for (AppTask task : appTasks) {
            if (task == null) {
                continue;
            }
            try {
                RecentTaskInfo taskInfo = task.getTaskInfo();
                if (taskInfo == null || taskInfo.id == excludedTaskId) {
                    continue;
                }
                if (!isOwnNonHomeTask(context, taskInfo)) {
                    continue;
                }
                task.moveToFront();
                if (logSuccess) {
                    AppLogger.i(TAG, "Moved existing app task to front: " + taskInfo.id);
                }
                return true;
            } catch (Exception e) {
                AppLogger.e(TAG, "Failed to move app task to front", e);
            }
        }
        return false;
    }

    private static boolean canAttemptForegroundRestore(Context context) {
        return shouldRestoreAppTask(context, false, 0);
    }

    private static boolean isScreenInteractive(Context context) {
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return powerManager != null && powerManager.isInteractive();
    }

    private static boolean isKeyguardLocked(Context context) {
        KeyguardManager keyguardManager =
                (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        return keyguardManager != null && keyguardManager.isKeyguardLocked();
    }

    private static boolean isOwnNonHomeTask(Context context, RecentTaskInfo taskInfo) {
        Intent baseIntent = taskInfo.baseIntent;
        ComponentName component = baseIntent == null ? null : baseIntent.getComponent();
        if (component == null || !context.getPackageName().equals(component.getPackageName())) {
            return false;
        }
        return !"com.punch.app.activity.KioskHomeActivity".equals(component.getClassName());
    }

    private static int getLockTaskModeState(Context context) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) {
            return ActivityManager.LOCK_TASK_MODE_NONE;
        }
        return am.getLockTaskModeState();
    }

    private static DevicePolicyManager getDevicePolicyManager(Context context) {
        return (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
    }

    private static ComponentName getAdminComponent(Context context) {
        return new ComponentName(context, KioskDeviceAdminReceiver.class);
    }

    public static void setUiTestBypassForTest(boolean enabled) {
        uiTestBypassEnabled = enabled;
        OWNER_POLICY_GATE.invalidate();
    }
}

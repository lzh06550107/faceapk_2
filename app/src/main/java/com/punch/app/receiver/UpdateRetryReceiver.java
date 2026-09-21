package com.punch.app.receiver;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.punch.app.network.InteractionLogger;
import com.punch.app.utils.SessionManager;
import com.punch.app.utils.UpdateManager;

/**
 * OTA 重试闹钟入口。AlarmManager 负责在应用进程死亡后重新拉起进程；
 * SharedPreferences 中的 retry 状态负责在设备重启后重新注册闹钟。
 */
public class UpdateRetryReceiver extends BroadcastReceiver {
    public static final String ACTION_UPDATE_RETRY =
            "com.punch.app.action.UPDATE_RETRY";

    private static final String EXTRA_TARGET_VERSION = "target_version";
    private static final String EXTRA_APK_URL = "apk_url";
    private static final int REQ_UPDATE_RETRY = 1003;
    private static final long MIN_RESTORE_DELAY_MS = 1_000L;
    private static final long ATTEMPT_WATCHDOG_MS = 15 * 60 * 1000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        SessionManager.get().init(appContext);
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            restorePersistedRetry(appContext);
            return;
        }
        if (!ACTION_UPDATE_RETRY.equals(action)) {
            return;
        }

        SessionManager session = SessionManager.get();
        String expectedTarget = safe(intent.getStringExtra(EXTRA_TARGET_VERSION));
        String expectedUrl = safe(intent.getStringExtra(EXTRA_APK_URL));
        String currentTarget = safe(session.getUpdateTargetVersion());
        String currentUrl = safe(session.getUpdateApkUrl());
        if (!expectedTarget.equals(currentTarget) || !expectedUrl.equals(currentUrl)) {
            InteractionLogger.logBusiness(
                    InteractionLogger.GROUP_UPDATE,
                    "Ignored stale OTA retry alarm",
                    "expectedTarget=" + expectedTarget
                            + "\ncurrentTarget=" + currentTarget
            );
            return;
        }

        if (session.isUpdateInstallPending()) {
            UpdateManager.startBackgroundUpdateIfEligible(appContext, "ota_install_watchdog");
            return;
        }
        if (!session.isUpdateRetryPending()) {
            return;
        }

        long now = System.currentTimeMillis();
        long nextAt = session.getUpdateRetryNextAt();
        if (nextAt > now) {
            schedule(appContext, nextAt, currentTarget, currentUrl);
            return;
        }
        boolean started = UpdateManager.startBackgroundUpdateIfEligible(
                appContext,
                "ota_retry_alarm"
        );
        if (!started && session.isUpdateRetryPending() && !session.isUpdateInstallPending()) {
            schedule(
                    appContext,
                    System.currentTimeMillis() + ATTEMPT_WATCHDOG_MS,
                    currentTarget,
                    currentUrl
            );
        }
    }

    public static void restorePersistedRetry(Context context) {
        if (context == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        SessionManager.get().init(appContext);
        SessionManager session = SessionManager.get();
        if (session.isUpdateInstallPending()) {
            UpdateManager.startBackgroundUpdateIfEligible(appContext, "ota_boot_restore");
            return;
        }
        if (!session.isUpdateRetryPending()) {
            cancel(appContext);
            return;
        }
        long now = System.currentTimeMillis();
        long nextAt = Math.max(now + MIN_RESTORE_DELAY_MS, session.getUpdateRetryNextAt());
        schedule(
                appContext,
                nextAt,
                session.getUpdateRetryTargetVersion(),
                session.getUpdateRetryApkUrl()
        );
    }

    public static void schedule(Context context,
                                long nextAtMillis,
                                String targetVersion,
                                String apkUrl) {
        if (context == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        AlarmManager alarmManager =
                (AlarmManager) appContext.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            InteractionLogger.logBusinessFailure(
                    InteractionLogger.GROUP_UPDATE,
                    "Schedule OTA retry failed",
                    "reason=alarm_manager_unavailable"
            );
            return;
        }
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                appContext,
                REQ_UPDATE_RETRY,
                buildRetryIntent(appContext, targetVersion, apkUrl),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        long triggerAt = Math.max(
                System.currentTimeMillis() + MIN_RESTORE_DELAY_MS,
                nextAtMillis
        );
        alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                pendingIntent
        );
    }

    public static void cancel(Context context) {
        if (context == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                appContext,
                REQ_UPDATE_RETRY,
                buildRetryIntent(appContext, "", ""),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE
        );
        if (pendingIntent == null) {
            return;
        }
        AlarmManager alarmManager =
                (AlarmManager) appContext.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) {
            alarmManager.cancel(pendingIntent);
        }
        pendingIntent.cancel();
    }

    private static Intent buildRetryIntent(Context context, String targetVersion, String apkUrl) {
        Intent intent = new Intent(context, UpdateRetryReceiver.class);
        intent.setAction(ACTION_UPDATE_RETRY);
        intent.putExtra(EXTRA_TARGET_VERSION, safe(targetVersion));
        intent.putExtra(EXTRA_APK_URL, safe(apkUrl));
        return intent;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}

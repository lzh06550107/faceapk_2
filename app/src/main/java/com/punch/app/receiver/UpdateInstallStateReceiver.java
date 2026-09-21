package com.punch.app.receiver;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;

import com.punch.app.PunchApplication;
import com.punch.app.activity.KioskHomeActivity;
import com.punch.app.activity.SplashActivity;
import com.punch.app.network.InteractionLogger;
import com.punch.app.utils.Constants;
import com.punch.app.utils.SessionManager;
import com.punch.app.utils.UpdateManager;

public class UpdateInstallStateReceiver extends BroadcastReceiver {
    public static final String ACTION_UPDATE_RELAUNCH_RETRY =
            "com.punch.app.action.UPDATE_RELAUNCH_RETRY";

    private static final String EXTRA_RELAUNCH_VERSION_CODE = "relaunch_version_code";
    private static final int REQ_UPDATE_RELAUNCH = 1002;
    private static final int LEGACY_RELAUNCH_COUNT = 8;
    private static final long RECENT_ACTIVITY_WINDOW_MS = 3_000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        String action = intent.getAction();
        if (UpdateManager.ACTION_PACKAGE_INSTALL_RESULT.equals(action)) {
            UpdateManager.handlePackageInstallerResult(context, intent);
            return;
        }
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            handlePackageReplaced(context);
            return;
        }
        if (ACTION_UPDATE_RELAUNCH_RETRY.equals(action)) {
            long expectedVersionCode = intent.getLongExtra(EXTRA_RELAUNCH_VERSION_CODE, 0L);
            evaluateRelaunch(context, expectedVersionCode, "retry_alarm");
        }
    }

    public static void acknowledgeUpdatedAppLaunch(Context context) {
        if (context == null) {
            return;
        }
        SessionManager session = SessionManager.get();
        String phase = session.getUpdateRelaunchPhase();
        if (!UpdateRelaunchPolicy.isPendingPhase(phase)) {
            return;
        }
        long generationVersionCode = session.getUpdateRelaunchVersionCode();
        long installedVersionCode = resolveInstalledVersionCode(context);
        if (generationVersionCode <= 0L || generationVersionCode != installedVersionCode) {
            return;
        }
        session.acknowledgeUpdateRelaunch();
        cancelRetryAlarm(context);
        InteractionLogger.logBusiness(
                InteractionLogger.GROUP_UPDATE,
                "Update relaunch acknowledged",
                "versionCode=" + generationVersionCode
                        + "\nattempt=" + session.getUpdateRelaunchAttempt()
        );
    }

    private static void handlePackageReplaced(Context context) {
        String version = resolveInstalledVersion(context);
        long versionCode = resolveInstalledVersionCode(context);
        String message = isBlank(version) ? "Update installed" : "Updated to " + version;
        SessionManager.get().markUpdateInstallResult(
                Constants.UPDATE_INSTALL_STATUS_SUCCESS,
                message,
                0
        );
        UpdateManager.clearDurableRetry(context, "package_replaced");
        cancelLegacyActivityRelaunches(context);
        cancelRetryAlarm(context);
        InteractionLogger.logBusiness(
                InteractionLogger.GROUP_UPDATE,
                "App package replaced",
                "version=" + safeValue(version) + "\nversionCode=" + versionCode
        );
        if (versionCode <= 0L) {
            InteractionLogger.logBusinessFailure(
                    InteractionLogger.GROUP_UPDATE,
                    "Start update relaunch recovery failed",
                    "reason=installed_version_unavailable"
            );
            return;
        }
        SessionManager.get().beginUpdateRelaunch(versionCode, SystemClock.elapsedRealtime());
        evaluateRelaunch(context, versionCode, "package_replaced");
    }

    private static void evaluateRelaunch(Context context,
                                         long expectedVersionCode,
                                         String trigger) {
        SessionManager session = SessionManager.get();
        UpdateRelaunchPolicy.State state = new UpdateRelaunchPolicy.State(
                session.getUpdateRelaunchPhase(),
                session.getUpdateRelaunchVersionCode(),
                session.getUpdateRelaunchAttempt(),
                session.getUpdateRelaunchStartedElapsed(),
                session.getUpdateRelaunchLastLaunchElapsed()
        );
        if (expectedVersionCode > 0L && expectedVersionCode != state.versionCode) {
            InteractionLogger.logBusiness(
                    InteractionLogger.GROUP_UPDATE,
                    "Ignored stale update relaunch retry",
                    "expectedVersionCode=" + expectedVersionCode
                            + "\nactiveVersionCode=" + state.versionCode
            );
            return;
        }

        long now = SystemClock.elapsedRealtime();
        long installedVersionCode = resolveInstalledVersionCode(context);
        PunchApplication app = PunchApplication.get();
        boolean activityVisible = app != null
                && app.wasNonHomeActivityRecentlyVisible(RECENT_ACTIVITY_WINDOW_MS);
        UpdateRelaunchPolicy.Decision decision = UpdateRelaunchPolicy.decide(
                state,
                installedVersionCode,
                now,
                activityVisible
        );

        if (decision == UpdateRelaunchPolicy.Decision.IGNORE) {
            if (!UpdateRelaunchPolicy.isPendingPhase(state.phase)) {
                cancelRetryAlarm(context);
            }
            return;
        }
        if (decision == UpdateRelaunchPolicy.Decision.EXHAUST) {
            session.exhaustUpdateRelaunch();
            cancelRetryAlarm(context);
            InteractionLogger.logBusinessFailure(
                    InteractionLogger.GROUP_UPDATE,
                    "Update relaunch recovery exhausted",
                    "versionCode=" + state.versionCode
                            + "\nattempt=" + state.attempt
                            + "\ntrigger=" + safeValue(trigger)
            );
            return;
        }

        long nextDelayMs = UpdateRelaunchPolicy.nextDelayMs(
                state.attempt,
                state.startedElapsedRealtime,
                now
        );
        if (nextDelayMs <= 0L) {
            session.exhaustUpdateRelaunch();
            cancelRetryAlarm(context);
            return;
        }

        int nextAttempt = UpdateRelaunchPolicy.attemptAfterDecision(
                state.attempt,
                decision
        );
        if (decision == UpdateRelaunchPolicy.Decision.LAUNCH) {
            session.markUpdateRelaunchLaunching(nextAttempt, now);
        }
        scheduleRetryAlarm(context, state.versionCode, nextDelayMs);

        if (decision == UpdateRelaunchPolicy.Decision.WAIT) {
            InteractionLogger.logBusiness(
                    InteractionLogger.GROUP_UPDATE,
                    "Deferred update relaunch retry",
                    "versionCode=" + state.versionCode
                            + "\nattempt=" + nextAttempt
                            + "\nactivityVisible=" + activityVisible
                            + "\nnextDelayMs=" + nextDelayMs
            );
            return;
        }

        boolean requested = launchKioskHome(context, state.attempt > 0);
        if (requested) {
            InteractionLogger.logBusiness(
                    InteractionLogger.GROUP_UPDATE,
                    "Requested update relaunch",
                    "versionCode=" + state.versionCode
                            + "\nattempt=" + nextAttempt
                            + "\ntrigger=" + safeValue(trigger)
                            + "\nnextDelayMs=" + nextDelayMs
            );
        }
    }

    private static void scheduleRetryAlarm(Context context,
                                           long versionCode,
                                           long delayMs) {
        AlarmManager alarmManager =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            InteractionLogger.logBusinessFailure(
                    InteractionLogger.GROUP_UPDATE,
                    "Schedule update relaunch retry failed",
                    "reason=alarm_manager_unavailable\nversionCode=" + versionCode
            );
            return;
        }
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                REQ_UPDATE_RELAUNCH,
                buildRetryIntent(context, versionCode),
                PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag()
        );
        alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs,
                pendingIntent
        );
    }

    private static void cancelRetryAlarm(Context context) {
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                REQ_UPDATE_RELAUNCH,
                buildRetryIntent(context, 0L),
                PendingIntent.FLAG_NO_CREATE | immutableFlag()
        );
        if (pendingIntent == null) {
            return;
        }
        AlarmManager alarmManager =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) {
            alarmManager.cancel(pendingIntent);
        }
        pendingIntent.cancel();
    }

    private static Intent buildRetryIntent(Context context, long versionCode) {
        Intent intent = new Intent(context, UpdateInstallStateReceiver.class);
        intent.setAction(ACTION_UPDATE_RELAUNCH_RETRY);
        intent.putExtra(EXTRA_RELAUNCH_VERSION_CODE, versionCode);
        return intent;
    }

    private static boolean launchKioskHome(Context context, boolean forceFreshTarget) {
        Intent intent = new Intent(context, KioskHomeActivity.class);
        intent.putExtra(KioskHomeActivity.EXTRA_FORCE_FRESH_TARGET, forceFreshTarget);
        intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
        );
        try {
            context.startActivity(intent);
            return true;
        } catch (RuntimeException e) {
            InteractionLogger.logBusinessFailure(
                    InteractionLogger.GROUP_UPDATE,
                    "Request update relaunch failed",
                    e.getClass().getSimpleName() + ": " + safeValue(e.getMessage())
            );
            return false;
        }
    }

    private static void cancelLegacyActivityRelaunches(Context context) {
        AlarmManager alarmManager =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        for (int i = 0; i < LEGACY_RELAUNCH_COUNT; i++) {
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    context,
                    REQ_UPDATE_RELAUNCH + i,
                    buildLegacyLaunchIntent(context),
                    PendingIntent.FLAG_NO_CREATE | immutableFlag()
            );
            if (pendingIntent == null) {
                continue;
            }
            if (alarmManager != null) {
                alarmManager.cancel(pendingIntent);
            }
            pendingIntent.cancel();
        }
    }

    private static Intent buildLegacyLaunchIntent(Context context) {
        Intent intent = new Intent(context, SplashActivity.class);
        intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
        );
        return intent;
    }

    private static int immutableFlag() {
        return PendingIntent.FLAG_IMMUTABLE;
    }

    private static String resolveInstalledVersion(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0)
                    .versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private static long resolveInstalledVersionCode(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return info.getLongVersionCode();
            }
            return info.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0L;
        }
    }

    private static String safeValue(String value) {
        return isBlank(value) ? "-" : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}

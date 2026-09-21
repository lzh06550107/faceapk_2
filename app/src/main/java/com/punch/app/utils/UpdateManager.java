package com.punch.app.utils;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.Nullable;

import com.punch.app.network.InteractionLogger;
import com.punch.app.network.ApiService;
import com.punch.app.receiver.UpdateInstallStateReceiver;
import com.punch.app.receiver.UpdateRetryReceiver;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class UpdateManager {
    private static final String TAG = "UpdateManager";
    public static final String ACTION_PACKAGE_INSTALL_RESULT =
            "com.punch.app.action.PACKAGE_INSTALL_RESULT";
    private static final String EXTRA_INSTALL_RESULT =
            "android.intent.extra.INSTALL_RESULT";
    private static final int REQ_INSTALL_RESULT = 1001;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final String AUTO_UPDATE_DIR = "updates";
    private static final String AUTO_UPDATE_APK_NAME = "faceapk-auto-update.apk";
    private static final long INSTALL_PENDING_TIMEOUT_MS = 30 * 60 * 1000L;
    private static final long AUTO_UPDATE_ATTEMPT_WATCHDOG_MS = 15 * 60 * 1000L;
    private static final ExecutorService AUTO_UPDATE_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean AUTO_UPDATE_RUNNING = new AtomicBoolean(false);

    private UpdateManager() {
    }

    public static boolean startBackgroundUpdateIfEligible(Context context, String reason) {
        if (context == null) {
            return false;
        }
        Context appContext = context.getApplicationContext();
        reconcileInstallState(appContext);
        if (clearTimedOutInstallPending(appContext)) {
            return false;
        }
        reconcileRetryGeneration(appContext);
        String blockedReason = getAutoUpdateBlockedReason(appContext);
        if (!blockedReason.isEmpty()) {
            handleBlockedAutoUpdateState(appContext, blockedReason);
            log("Auto update skipped", buildEligibilityDetail(appContext, blockedReason, reason));
            return false;
        }
        if (!AUTO_UPDATE_RUNNING.compareAndSet(false, true)) {
            log("Auto update skipped", "reason=already_running\ntrigger=" + safe(reason));
            return false;
        }
        armAttemptWatchdog(appContext);
        AUTO_UPDATE_EXECUTOR.execute(() -> {
            try {
                runBackgroundUpdate(appContext, reason);
            } finally {
                AUTO_UPDATE_RUNNING.set(false);
            }
        });
        return true;
    }

    private static boolean shouldAttemptBackgroundUpdate(Context context) {
        return getAutoUpdateBlockedReason(context).isEmpty();
    }

    private static String getAutoUpdateBlockedReason(Context context) {
        SessionManager session = SessionManager.get();
        if (session.isUpdateInstallPending()) {
            return "install_pending";
        }
        String apkUrl = safe(session.getUpdateApkUrl());
        if (apkUrl.isEmpty()) {
            return "apk_url_empty";
        }
        String targetVersion = safe(session.getUpdateTargetVersion());
        if (!targetVersion.isEmpty()) {
            return compareVersions(resolveInstalledVersionName(context), targetVersion) < 0
                    ? ""
                    : "installed_version_not_lower_than_target";
        }
        return session.isUpdateNeeded() ? "" : "need_update_false";
    }

    private static void runBackgroundUpdate(Context context, String reason) {
        reconcileInstallState(context);
        if (SessionManager.get().isUpdateInstallPending()) {
            log("Auto update skipped", "reason=install_pending\ntrigger=" + safe(reason));
            return;
        }
        if (!KioskManager.isDeviceOwner(context)) {
            clearDurableRetry(context, "device_not_owner");
            logFailure(
                    "Auto update skipped",
                    "reason=device_not_owner\n"
                            + "message=后台静默安装要求应用是 Device Owner\n"
                            + "trigger=" + safe(reason)
            );
            return;
        }
        if (SessionManager.get().isKioskEnabled()) {
            KioskManager.ensureOwnerKioskPolicies(context);
        }

        String apkUrl = resolveDownloadUrl(SessionManager.get().getUpdateApkUrl());
        File apkFile = buildAutoUpdateApkFile(context);
        if (apkFile == null) {
            String detail = "reason=update_dir_unavailable\ntrigger=" + safe(reason);
            logFailure("Auto update download failed", detail);
            scheduleDurableRetry(context, "update_dir_unavailable", detail);
            return;
        }
        if (apkFile.exists() && !apkFile.delete()) {
            String detail = "reason=old_apk_delete_failed\npath=" + apkFile.getAbsolutePath();
            logFailure("Auto update download failed", detail);
            scheduleDurableRetry(context, "old_apk_delete_failed", detail);
            return;
        }

        log(
                "Auto update download started",
                "trigger=" + safe(reason)
                        + "\nurl=" + apkUrl
                        + "\npath=" + apkFile.getAbsolutePath()
        );
        boolean downloaded;
        try {
            downloaded = ApiService.downloadToFile(apkUrl, apkFile);
        } catch (RuntimeException e) {
            String message = e.getClass().getSimpleName() + ": " + safe(e.getMessage());
            logFailure("Auto update download crashed", message + "\nurl=" + apkUrl);
            scheduleDurableRetry(context, "download_crashed", message);
            return;
        }
        if (!downloaded) {
            logFailure("Auto update download failed", "url=" + apkUrl);
            scheduleDurableRetry(context, "download_failed", "url=" + apkUrl);
            return;
        }

        ApkInstallValidator.Result validation =
                ApkInstallValidator.validateUpdateApk(context, apkFile.getAbsolutePath());
        if (!validation.success) {
            markFailed(validation.message, Integer.MIN_VALUE);
            clearDurableRetry(context, "apk_validation_failed");
            logFailure(
                    "Auto update APK validation failed",
                    validation.message + "\napkPath=" + apkFile.getAbsolutePath()
            );
            return;
        }

        String targetVersion = !TextUtils.isEmpty(validation.versionName)
                ? validation.versionName
                : safe(SessionManager.get().getUpdateTargetVersion());
        SessionManager.get().markUpdateInstallStarted(
                apkFile.getAbsolutePath(),
                targetVersion,
                validation.versionCode
        );
        log(
                "Auto update APK validation passed",
                "package=" + validation.packageName
                        + "\nversionName=" + targetVersion
                        + "\nversionCode=" + validation.versionCode
                        + "\nsize=" + validation.fileSize
                        + "\napkPath=" + apkFile.getAbsolutePath()
        );

        StartResult result = installWithPackageInstaller(context, apkFile.getAbsolutePath(), validation);
        if (!result.success) {
            logFailure("Auto update install submit failed", result.message);
            scheduleDurableRetry(context, "install_submit_failed", result.message);
            return;
        }
        scheduleInstallPendingWatchdog(context);
    }

    public static StartResult startInstall(
            Activity activity,
            Uri apkUri,
            String apkPath,
            String fallbackTargetVersion,
            ActivityResultLauncher<Intent> systemInstallerLauncher
    ) {
        if (activity == null) {
            return StartResult.fail("Install context unavailable");
        }
        if (apkUri == null) {
            return StartResult.fail("Update APK uri is empty");
        }

        ApkInstallValidator.Result validation =
                ApkInstallValidator.validateUpdateApk(activity, apkPath);
        if (!validation.success) { // TODO
            markFailed(validation.message, Integer.MIN_VALUE);
            logFailure("Update APK validation failed",
                    validation.message + "\napkPath=" + safe(apkPath));
            return StartResult.fail(validation.message);
        }

        String targetVersion = !TextUtils.isEmpty(validation.versionName)
                ? validation.versionName
                : safe(fallbackTargetVersion);
        SessionManager.get().markUpdateInstallStarted(
                apkPath,
                targetVersion,
                validation.versionCode
        );
        log("Update APK validation passed",
                "package=" + validation.packageName
                        + "\nversionName=" + targetVersion
                        + "\nversionCode=" + validation.versionCode
                        + "\nsize=" + validation.fileSize
                        + "\napkPath=" + safe(apkPath));

        if (KioskManager.isDeviceOwner(activity)) {
            if (SessionManager.get().isKioskEnabled()) {
                KioskManager.ensureOwnerKioskPolicies(activity);
            }
            return installWithPackageInstaller(activity, apkPath, validation);
        }
        return launchSystemInstaller(activity, apkUri, systemInstallerLauncher);
    }

    public static void handleSystemInstallerResult(
            Context context,
            int resultCode,
            @Nullable Intent data
    ) {
        String detail = buildInstallerResultDetail(resultCode, data);
        if (isInstalledVersionAtTarget(context)) {
            String version = resolveInstalledVersionName(context);
            SessionManager.get().markUpdateInstallResult(
                    Constants.UPDATE_INSTALL_STATUS_SUCCESS,
                    "Updated to " + safe(version),
                    resultCode
            );
            log("System installer reported success", detail + "\nversion=" + safe(version));
            return;
        }

        int installResult = extractInstallResult(data, resultCode);
        String message = mapInstallResult(installResult);
        SessionManager.get().markUpdateInstallResult(
                Constants.UPDATE_INSTALL_STATUS_FAILED,
                message,
                installResult
        );
        logFailure("System installer reported failure", detail + "\nmessage=" + message);
    }

    public static void handlePackageInstallerResult(Context context, Intent intent) {
        int status = intent == null
                ? PackageInstaller.STATUS_FAILURE
                : intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String statusMessage = intent == null
                ? ""
                : intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        int sessionId = intent == null
                ? -1
                : intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);

        if (status == PackageInstaller.STATUS_SUCCESS || isInstalledVersionAtTarget(context)) {
            String version = resolveInstalledVersionName(context);
            SessionManager.get().markUpdateInstallResult(
                    Constants.UPDATE_INSTALL_STATUS_SUCCESS,
                    "Updated to " + safe(version),
                    status
            );
            clearDurableRetry(context, "install_success");
            log("Device Owner install succeeded",
                    "sessionId=" + sessionId + "\nversion=" + safe(version));
            return;
        }

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            SessionManager.get().markUpdateInstallResult(
                    Constants.UPDATE_INSTALL_STATUS_FAILED,
                    "Installer requires user action",
                    status
            );
            clearDurableRetry(context, "installer_requires_user_action");
            logFailure("Device Owner install requires user action",
                    "sessionId=" + sessionId + "\nmessage=" + safe(statusMessage));
            return;
        }

        String message = "Install failed: " + safe(statusMessage);
        SessionManager.get().markUpdateInstallResult(
                Constants.UPDATE_INSTALL_STATUS_FAILED,
                message,
                status
        );
        logFailure("Device Owner install failed",
                "sessionId=" + sessionId
                        + "\nstatus=" + status
                        + "\nmessage=" + safe(statusMessage));
        if (isRetryablePackageInstallerStatus(status)) {
            scheduleDurableRetry(context, "installer_status_" + status, message);
        } else {
            clearDurableRetry(context, "installer_permanent_failure_" + status);
        }
    }

    public static void reconcileInstallState(Context context) {
        if (!SessionManager.get().isUpdateInstallPending()) {
            return;
        }
        if (!isInstalledVersionAtTarget(context)) {
            return;
        }
        String version = resolveInstalledVersionName(context);
        SessionManager.get().markUpdateInstallResult(
                Constants.UPDATE_INSTALL_STATUS_SUCCESS,
                "Updated to " + safe(version),
                0
        );
        clearDurableRetry(context, "install_reconciled");
        log("Install state reconciled by version check",
                "version=" + safe(version)
                        + "\ntargetVersionCode="
                        + SessionManager.get().getUpdateInstallTargetVersionCode());
    }

    private static boolean clearTimedOutInstallPending(Context context) {
        if (!SessionManager.get().isUpdateInstallPending()) {
            return false;
        }
        if (isInstalledVersionAtTarget(context)) {
            return false;
        }
        long startedAt = SessionManager.get().getUpdateInstallStartedAt();
        long elapsed = startedAt > 0L ? System.currentTimeMillis() - startedAt : Long.MAX_VALUE;
        if (elapsed < INSTALL_PENDING_TIMEOUT_MS) {
            return false;
        }
        String message = "Install result timed out";
        SessionManager.get().markUpdateInstallResult(
                Constants.UPDATE_INSTALL_STATUS_FAILED,
                message,
                Integer.MIN_VALUE
        );
        logFailure(
                "Install pending state cleared",
                "reason=timeout\nelapsedMs=" + elapsed
                        + "\ntargetVersion="
                        + safe(SessionManager.get().getUpdateInstallTargetVersion())
                        + "\ntargetVersionCode="
                        + SessionManager.get().getUpdateInstallTargetVersionCode()
        );
        scheduleDurableRetry(context, "install_pending_timeout", message);
        return true;
    }

    private static void reconcileRetryGeneration(Context context) {
        SessionManager session = SessionManager.get();
        if (!session.isUpdateRetryPending()) {
            return;
        }
        String retryTarget = safe(session.getUpdateRetryTargetVersion());
        String retryUrl = safe(session.getUpdateRetryApkUrl());
        String currentTarget = safe(session.getUpdateTargetVersion());
        String currentUrl = safe(session.getUpdateApkUrl());
        if (!retryTarget.equals(currentTarget) || !retryUrl.equals(currentUrl)) {
            clearDurableRetry(context, "retry_generation_changed");
        }
    }

    private static void handleBlockedAutoUpdateState(Context context, String blockedReason) {
        if ("install_pending".equals(blockedReason)) {
            scheduleInstallPendingWatchdog(context);
            return;
        }
        if ("installed_version_not_lower_than_target".equals(blockedReason)
                || "apk_url_empty".equals(blockedReason)
                || "need_update_false".equals(blockedReason)) {
            clearDurableRetry(context, "blocked_" + blockedReason);
        }
    }

    private static void scheduleInstallPendingWatchdog(Context context) {
        SessionManager session = SessionManager.get();
        if (!session.isUpdateInstallPending()) {
            return;
        }
        long startedAt = session.getUpdateInstallStartedAt();
        long nextAt = startedAt > 0L
                ? startedAt + INSTALL_PENDING_TIMEOUT_MS
                : System.currentTimeMillis() + INSTALL_PENDING_TIMEOUT_MS;
        UpdateRetryReceiver.schedule(
                context,
                nextAt,
                session.getUpdateTargetVersion(),
                session.getUpdateApkUrl()
        );
    }

    private static void armAttemptWatchdog(Context context) {
        SessionManager session = SessionManager.get();
        String targetVersion = safe(session.getUpdateTargetVersion());
        String apkUrl = safe(session.getUpdateApkUrl());
        if (apkUrl.isEmpty()) {
            return;
        }
        boolean sameGeneration = session.isUpdateRetryPending()
                && targetVersion.equals(safe(session.getUpdateRetryTargetVersion()))
                && apkUrl.equals(safe(session.getUpdateRetryApkUrl()));
        int retryCount = sameGeneration ? session.getUpdateRetryCount() : 0;
        long nextAt = System.currentTimeMillis() + AUTO_UPDATE_ATTEMPT_WATCHDOG_MS;
        session.saveUpdateRetryState(
                retryCount,
                nextAt,
                "attempt_watchdog",
                targetVersion,
                apkUrl
        );
        UpdateRetryReceiver.schedule(context, nextAt, targetVersion, apkUrl);
    }

    private static void scheduleDurableRetry(Context context, String reason, String error) {
        if (context == null) {
            return;
        }
        SessionManager session = SessionManager.get();
        String targetVersion = safe(session.getUpdateTargetVersion());
        String apkUrl = safe(session.getUpdateApkUrl());
        if (apkUrl.isEmpty()) {
            clearDurableRetry(context, "retry_generation_missing");
            return;
        }
        boolean sameGeneration = session.isUpdateRetryPending()
                && targetVersion.equals(safe(session.getUpdateRetryTargetVersion()))
                && apkUrl.equals(safe(session.getUpdateRetryApkUrl()));
        int nextCount = UpdateRetryPolicy.nextFailureCount(
                session.getUpdateRetryCount(),
                sameGeneration
        );
        long delayMs = UpdateRetryPolicy.delayMsForFailureCount(nextCount);
        long nextAt = System.currentTimeMillis() + delayMs;
        String lastError = safe(reason) + ": " + safe(error);
        session.saveUpdateRetryState(
                nextCount,
                nextAt,
                lastError,
                targetVersion,
                apkUrl
        );
        UpdateRetryReceiver.schedule(context, nextAt, targetVersion, apkUrl);
        log(
                "OTA durable retry scheduled",
                "reason=" + safe(reason)
                        + "\nretryCount=" + nextCount
                        + "\ndelayMs=" + delayMs
                        + "\nnextAt=" + nextAt
                        + "\ntargetVersion=" + targetVersion
        );
    }

    public static void clearDurableRetry(Context context, String reason) {
        SessionManager.get().clearUpdateRetryState();
        UpdateRetryReceiver.cancel(context);
        log("OTA durable retry cleared", "reason=" + safe(reason));
    }

    private static boolean isRetryablePackageInstallerStatus(int status) {
        return status == PackageInstaller.STATUS_FAILURE
                || status == PackageInstaller.STATUS_FAILURE_ABORTED
                || status == PackageInstaller.STATUS_FAILURE_STORAGE;
    }

    public static boolean isInstalledVersionAtTarget(Context context) {
        long targetCode = SessionManager.get().getUpdateInstallTargetVersionCode();
        if (targetCode > 0L) {
            return ApkInstallValidator.getInstalledVersionCode(context) >= targetCode;
        }
        String targetVersion = SessionManager.get().getUpdateInstallTargetVersion();
        return compareVersions(resolveInstalledVersionName(context), targetVersion) >= 0;
    }

    private static StartResult installWithPackageInstaller(
            Context context,
            String apkPath,
            ApkInstallValidator.Result validation
    ) {
        PackageInstaller.Session session = null;
        int sessionId = -1;
        try {
            File apkFile = new File(apkPath);
            PackageInstaller installer = context.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(context.getPackageName());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            }
            sessionId = installer.createSession(params);
            session = installer.openSession(sessionId);
            try (FileInputStream input = new FileInputStream(apkFile);
                 OutputStream output = session.openWrite("base.apk", 0, apkFile.length())) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int len;
                while ((len = input.read(buffer)) != -1) {
                    output.write(buffer, 0, len);
                }
                session.fsync(output);
            }

            Intent callbackIntent = new Intent(context, UpdateInstallStateReceiver.class);
            callbackIntent.setAction(ACTION_PACKAGE_INSTALL_RESULT);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    context,
                    REQ_INSTALL_RESULT,
                    callbackIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | mutableFlag()
            );
            session.commit(pendingIntent.getIntentSender());
            log("Device Owner install session committed",
                    "sessionId=" + sessionId
                            + "\nversionName=" + safe(validation.versionName)
                            + "\nversionCode=" + validation.versionCode);
            return StartResult.ok("Background install submitted", true);
        } catch (Exception e) {
            if (sessionId >= 0) {
                try {
                    context.getPackageManager().getPackageInstaller().abandonSession(sessionId);
                } catch (Exception ignored) {
                }
            }
            String message = "Install submit failed: " + e.getClass().getSimpleName()
                    + ": " + safe(e.getMessage());
            markFailed(message, Integer.MIN_VALUE);
            logFailure("Device Owner install submit failed", message);
            return StartResult.fail(message);
        } finally {
            if (session != null) {
                session.close();
            }
        }
    }

    private static StartResult launchSystemInstaller(
            Context context,
            Uri apkUri,
            ActivityResultLauncher<Intent> launcher
    ) {
        Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        intent.setData(apkUri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
        intent.putExtra(Intent.EXTRA_RETURN_RESULT, true);
        try {
            log("System installer launched", "uri=" + apkUri);
            launcher.launch(intent);
            return StartResult.ok("Installer launched", false);
        } catch (Exception e) {
            String message = "Unable to launch installer: " + safe(e.getMessage());
            markFailed(message, Integer.MIN_VALUE);
            logFailure("System installer launch failed", message);
            return StartResult.fail(message);
        }
    }

    private static int mutableFlag() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return PendingIntent.FLAG_MUTABLE;
        }
        return 0;
    }

    private static void markFailed(String message, int resultCode) {
        SessionManager.get().markUpdateInstallResult(
                Constants.UPDATE_INSTALL_STATUS_FAILED,
                message,
                resultCode
        );
    }

    private static int extractInstallResult(@Nullable Intent data, int fallback) {
        if (data == null || data.getExtras() == null) {
            return fallback;
        }
        return data.getExtras().getInt(EXTRA_INSTALL_RESULT, fallback);
    }

    private static String mapInstallResult(int result) {
        switch (result) {
            case -1:
                return "Install failed: already exists";
            case -2:
                return "Install failed: invalid APK";
            case -3:
                return "Install failed: invalid URI";
            case -4:
                return "Install failed: insufficient storage";
            case -5:
                return "Install failed: duplicate package";
            case -7:
                return "Install failed: update signature is incompatible";
            case -25:
                return "Install failed: version downgrade";
            default:
                return "Install cancelled or failed: result=" + result;
        }
    }

    private static String buildInstallerResultDetail(int resultCode, @Nullable Intent data) {
        StringBuilder builder = new StringBuilder()
                .append("resultCode=").append(resultCode);
        if (data != null && data.getExtras() != null && !data.getExtras().isEmpty()) {
            builder.append("\nextras=").append(data.getExtras());
        }
        return builder.toString();
    }

    private static String resolveInstalledVersionName(Context context) {
        if (context == null) {
            return "";
        }
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0)
                    .versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private static int compareVersions(String left, String right) {
        String[] leftParts = normalizeVersion(left);
        String[] rightParts = normalizeVersion(right);
        int length = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < length; i++) {
            int leftValue = i < leftParts.length ? parseVersionPart(leftParts[i]) : 0;
            int rightValue = i < rightParts.length ? parseVersionPart(rightParts[i]) : 0;
            if (leftValue != rightValue) {
                return Integer.compare(leftValue, rightValue);
            }
        }
        return 0;
    }

    private static String[] normalizeVersion(String version) {
        if (version == null || version.trim().isEmpty()) {
            return new String[0];
        }
        return version.trim().split("\\.");
    }

    private static int parseVersionPart(String part) {
        if (part == null || part.isEmpty()) {
            return 0;
        }
        String digits = part.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String resolveDownloadUrl(String rawUrl) {
        String url = safe(rawUrl);
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        String baseUrl = safe(SessionManager.get().getBaseUrl());
        if (url.startsWith("/")) {
            return baseUrl + url;
        }
        return baseUrl + "/" + url;
    }

    private static File buildAutoUpdateApkFile(Context context) {
        if (context == null) {
            return null;
        }
        File updateDir = new File(context.getFilesDir(), AUTO_UPDATE_DIR);
        if (!updateDir.exists() && !updateDir.mkdirs()) {
            return null;
        }
        return new File(updateDir, AUTO_UPDATE_APK_NAME);
    }

    private static void log(String title, String detail) {
        AppLogger.i(TAG, title + " | " + compact(detail));
        InteractionLogger.logBusiness(InteractionLogger.GROUP_UPDATE, title, detail);
    }

    private static void logFailure(String title, String detail) {
        AppLogger.e(TAG, title + " | " + compact(detail));
        InteractionLogger.logBusinessFailure(InteractionLogger.GROUP_UPDATE, title, detail);
    }

    private static String buildEligibilityDetail(Context context, String reason, String trigger) {
        SessionManager session = SessionManager.get();
        return "reason=" + safe(reason)
                + "\ntrigger=" + safe(trigger)
                + "\ninstalledVersion=" + safe(resolveInstalledVersionName(context))
                + "\nneedUpdate=" + session.isUpdateNeeded()
                + "\napkUrlEmpty=" + safe(session.getUpdateApkUrl()).isEmpty()
                + "\ntargetVersion=" + safe(session.getUpdateTargetVersion())
                + "\ninstallPending=" + session.isUpdateInstallPending();
    }

    private static String compact(String value) {
        String text = safe(value).replace('\n', ';');
        return text.length() > 1000 ? text.substring(0, 1000) + "..." : text;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public static final class StartResult {
        public final boolean success;
        public final String message;
        public final boolean deviceOwnerInstall;

        private StartResult(boolean success, String message, boolean deviceOwnerInstall) {
            this.success = success;
            this.message = message == null ? "" : message;
            this.deviceOwnerInstall = deviceOwnerInstall;
        }

        public static StartResult ok(String message, boolean deviceOwnerInstall) {
            return new StartResult(true, message, deviceOwnerInstall);
        }

        public static StartResult fail(String message) {
            return new StartResult(false, message, false);
        }
    }
}

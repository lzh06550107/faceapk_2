package com.punch.app;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.punch.app.activation.ActivationManager;
import com.punch.app.activity.KioskHomeActivity;
import com.punch.app.db.DatabaseHelper;
import com.punch.app.face.FaceApplyWorker;
import com.punch.app.face.FaceLibraryReconciler;
import com.punch.app.face.FaceManager;
import com.punch.app.face.PunchPreparationPolicy;
import com.punch.app.network.InteractionLogStore;
import com.punch.app.service.HeartbeatManager;
import com.punch.app.service.SyncCoordinator;
import com.punch.app.utils.AppLogger;
import com.punch.app.utils.KioskManager;
import com.punch.app.utils.SessionManager;
import com.punch.app.utils.UpdateManager;
import com.punch.app.receiver.UpdateRetryReceiver;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PunchApplication extends Application {
    private static final String TAG = "PunchApplication";
    private static final long FACE_SDK_READY_TIMEOUT_MS = 120_000L;
    private static final long PUNCH_PREPARATION_RETRY_DELAY_MS = 10_000L;
    private static final String PUNCH_STATUS_TOKEN_EXPIRED = "登录状态已失效，请重新登录";
    private static final String PUNCH_STATUS_PREPARATION_CRASHED = "打卡数据准备异常，稍后自动重试";
    private static final long KIOSK_RESTORE_DELAY_MS = 250L;
    private static final long KIOSK_FOREGROUND_WATCHDOG_INTERVAL_MS = 1_000L;
    private static final int MAX_STATUS_HISTORY = 5;
    private static final long EMPLOYEE_SYNC_UI_THROTTLE_MS = 300L;

    public static final int STATUS_LEVEL_INFO = 0;
    public static final int STATUS_LEVEL_PROGRESS = 1;
    public static final int STATUS_LEVEL_SUCCESS = 2;
    public static final int STATUS_LEVEL_ERROR = 3;

    private static PunchApplication instance;
    private static volatile boolean uiTestModeEnabled;

    private volatile boolean faceSdkInitializing;
    private volatile boolean punchDataPreparing;
    private volatile boolean punchDataReady;
    private volatile long punchPreparationRetryNotBeforeAtMs;
    private volatile String punchDataStatus = "正在准备打卡数据...";
    private volatile int punchDataStatusLevel = STATUS_LEVEL_PROGRESS;
    private volatile boolean punchStatusAttention;
    private volatile EmployeeSyncProgress employeeSyncProgress;
    private volatile long employeeSyncStartedAtElapsedMs;
    private volatile long employeeSyncLastUiPublishElapsedMs;
    private volatile int resumedNonHomeActivityCount;
    private volatile long lastNonHomeActivityVisibleAt;
    private volatile Class<? extends Activity> lastNonHomeActivityClass;
    private volatile boolean kioskForegroundWatchdogRunning;

    private final ExecutorService appExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object statusLock = new Object();
    private final Deque<PunchStatusEntry> recentStatusEntries = new ArrayDeque<>();
    private final List<PunchStatusListener> statusListeners = new CopyOnWriteArrayList<>();

    public static PunchApplication get() {
        return instance;
    }

    public static void setUiTestModeForTest(boolean enabled) {
        uiTestModeEnabled = enabled;
    }

    public static boolean isUiTestModeEnabled() {
        return uiTestModeEnabled;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        if (uiTestModeEnabled) {
            SessionManager.get().init(this);
            return;
        }
        if (KioskManager.isDeviceTestMaintenanceModeForTest()) {
            SessionManager.get().init(this);
            return;
        }

        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
            }

            @Override
            public void onActivityStarted(Activity activity) {
            }

            @Override
            public void onActivityResumed(Activity activity) {
                if (activity instanceof KioskHomeActivity) {
                    return;
                }
                KioskManager.cancelPendingAppTaskRestore();
                KioskManager.enterIfPossible(activity);
                startKioskForegroundWatchdog();
                resumedNonHomeActivityCount += 1;
                lastNonHomeActivityVisibleAt = System.currentTimeMillis();
                lastNonHomeActivityClass = activity.getClass();
            }

            @Override
            public void onActivityPaused(Activity activity) {
                if (activity instanceof KioskHomeActivity) {
                    return;
                }
                resumedNonHomeActivityCount = Math.max(0, resumedNonHomeActivityCount - 1);
                lastNonHomeActivityVisibleAt = System.currentTimeMillis();
                scheduleKioskTaskRestore(activity);
            }

            @Override
            public void onActivityStopped(Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
            }

            @Override
            public void onActivityDestroyed(Activity activity) {
            }
        });

        SessionManager.get().init(this);
        if (KioskManager.isDeviceOwner(this)) {
            SessionManager.get().saveKioskEnabled(true);
            KioskManager.ensureOwnerKioskPolicies(this);
            startKioskForegroundWatchdog();
        }
        InteractionLogStore.init(this); // 日志在应用启动就初始化
        UpdateRetryReceiver.restorePersistedRetry(this);
        DatabaseHelper.get(this);
        ActivationManager.get().ensureDeviceRegistered(this);

        if (SessionManager.get().isTokenValid()) {
            initFaceSDK();
            preparePunchRecognitionData();
            startSyncService(); // 心跳/同步只在 token 有效时启动
            UpdateManager.startBackgroundUpdateIfEligible(this, "app_start");
        }
    }

    public boolean wasNonHomeActivityRecentlyVisible(long windowMs) {
        if (resumedNonHomeActivityCount > 0) {
            return true;
        }
        long lastVisibleAt = lastNonHomeActivityVisibleAt;
        return lastVisibleAt > 0L && System.currentTimeMillis() - lastVisibleAt <= windowMs;
    }

    public Class<? extends Activity> getLastNonHomeActivityClass() {
        return lastNonHomeActivityClass;
    }

    private void scheduleKioskTaskRestore(Activity activity) {
        if (activity == null || !KioskManager.shouldRestoreAppTask(
                this,
                activity.isChangingConfigurations(),
                resumedNonHomeActivityCount
        )) {
            return;
        }
        mainHandler.postDelayed(() -> {
            if (!KioskManager.shouldRestoreAppTask(this, false, resumedNonHomeActivityCount)) {
                return;
            }
            KioskManager.bringExistingAppTaskToFront(this, -1);
        }, KIOSK_RESTORE_DELAY_MS);
    }

    private void startKioskForegroundWatchdog() {
        if (kioskForegroundWatchdogRunning) {
            return;
        }
        kioskForegroundWatchdogRunning = true;
        mainHandler.post(kioskForegroundWatchdogRunnable);
    }

    private final Runnable kioskForegroundWatchdogRunnable = new Runnable() {
        @Override
        public void run() {
            if (KioskManager.isDeviceTestMaintenanceModeForTest()
                    || !SessionManager.get().isKioskEnabled()
                    || !KioskManager.isDeviceOwner(PunchApplication.this)) {
                kioskForegroundWatchdogRunning = false;
                return;
            }
            if (KioskManager.shouldRestoreAppTask(
                    PunchApplication.this,
                    false,
                    resumedNonHomeActivityCount
            )) {
                KioskManager.bringExistingAppTaskToFrontQuietly(PunchApplication.this, -1);
            }
            mainHandler.postDelayed(this, KIOSK_FOREGROUND_WATCHDOG_INTERVAL_MS);
        }
    };

    public void initFaceSDK() {
        if (!SessionManager.get().isTokenValid()) {
            return;
        }
        if (faceSdkInitializing || FaceManager.get().isInitialized()) {
            return;
        }
        faceSdkInitializing = true;

        ActivationManager.get().prepareActivation(this, (ready, failureMessage) -> {
            if (!ready) {
                faceSdkInitializing = false;
                markPunchRecognitionFailed(
                        failureMessage == null || failureMessage.trim().isEmpty()
                                ? "人脸引擎初始化失败"
                                : failureMessage.trim()
                );
                AppLogger.w(TAG, "Activation preparation failed: " + failureMessage);
                return;
            }
            FaceManager.get().init(PunchApplication.this, "idl-license.face-android", new FaceManager.InitCallback() {
                @Override
                public void onSuccess() {
                    faceSdkInitializing = false;
                    reportStatusEvent("人脸引擎初始化完成", STATUS_LEVEL_SUCCESS);
                    AppLogger.i(TAG, "Face SDK ready");
                }

                @Override
                public void onError(int code, String msg) {
                    faceSdkInitializing = false;
                    markPunchRecognitionFailed("人脸引擎初始化失败");
                    AppLogger.e(TAG, "Face SDK init error: " + msg);
                }
            });
        });
    }

    public synchronized void preparePunchRecognitionData() {
        if (!SessionManager.get().isTokenValid()) {
            if (!PUNCH_STATUS_TOKEN_EXPIRED.equals(punchDataStatus)
                    || punchDataStatusLevel != STATUS_LEVEL_ERROR) {
                markPunchRecognitionFailed(PUNCH_STATUS_TOKEN_EXPIRED);
            }
            return;
        }
        if (punchDataPreparing || punchDataReady) {
            return;
        }

        long now = System.currentTimeMillis();
        if (!PunchPreparationPolicy.isRetryAllowed(punchPreparationRetryNotBeforeAtMs, now)) {
            return;
        }
        beginPunchDataPreparation("正在准备打卡数据...");
        enqueuePunchPreparation();
    }

    /**
     * Forces a fresh punch-environment preparation after login.
     * <p>Unlike {@link #preparePunchRecognitionData()}, this method intentionally does not
     * short-circuit on an in-flight/ready state. The work is queued on the same single-thread
     * executor, so any older preparation finishes first and this restart owns the final status.
     * This prevents an older async preparation from racing with LoginActivity and leaving the
     * UI stuck on an "initializing" message while punch recognition is already ready.</p>
     */
    public synchronized void restartPunchRecognitionData() {
        if (!SessionManager.get().isTokenValid()) {
            if (!PUNCH_STATUS_TOKEN_EXPIRED.equals(punchDataStatus)
                    || punchDataStatusLevel != STATUS_LEVEL_ERROR) {
                markPunchRecognitionFailed(PUNCH_STATUS_TOKEN_EXPIRED);
            }
            return;
        }
        punchPreparationRetryNotBeforeAtMs = 0L;
        beginPunchDataPreparation("正在初始化打卡环境...");
        enqueuePunchPreparation();
    }

    public boolean isPunchRecognitionReady() {
        return punchDataReady;
    }

    public boolean isPunchDataPreparing() {
        return punchDataPreparing;
    }

    public String getPunchDataStatus() {
        return punchDataStatus;
    }

    public int getPunchDataStatusLevel() {
        return punchDataStatusLevel;
    }

    public void resetPunchRecognitionState() {
        punchDataPreparing = false;
        punchDataReady = false;
        punchPreparationRetryNotBeforeAtMs = 0L;
        punchDataStatus = "正在准备打卡数据...";
        punchDataStatusLevel = STATUS_LEVEL_PROGRESS;
    }

    public void resetPunchStatusTimeline(String status, int level) {
        synchronized (statusLock) {
            recentStatusEntries.clear();
            punchStatusAttention = false;
            employeeSyncProgress = null;
            employeeSyncStartedAtElapsedMs = 0L;
            employeeSyncLastUiPublishElapsedMs = 0L;
        }
        pushStatus(status, level, true, false);
    }

    public void addPunchStatusListener(PunchStatusListener listener) {
        if (listener == null) {
            return;
        }
        statusListeners.add(listener);
        notifyListener(listener, getPunchStatusSnapshot());
    }

    public void removePunchStatusListener(PunchStatusListener listener) {
        if (listener == null) {
            return;
        }
        statusListeners.remove(listener);
    }

    public PunchStatusSnapshot getPunchStatusSnapshot() {
        synchronized (statusLock) {
            return new PunchStatusSnapshot(
                    punchDataStatus,
                    punchDataStatusLevel,
                    punchStatusAttention,
                    new ArrayList<>(recentStatusEntries),
                    employeeSyncProgress
            );
        }
    }

    public void clearPunchStatusAttention() {
        synchronized (statusLock) {
            punchStatusAttention = false;
        }
    }

    public void reportStatusEvent(String status, int level) {
        pushStatus(status, level, false, level == STATUS_LEVEL_ERROR);
    }

    public void setCurrentPunchStatus(String status, int level) {
        pushStatus(status, level, true, level == STATUS_LEVEL_ERROR);
    }

    public void beginEmployeeSyncProgress(String eventCursor) {
        long now = SystemClock.elapsedRealtime();
        synchronized (statusLock) {
            employeeSyncStartedAtElapsedMs = now;
            employeeSyncLastUiPublishElapsedMs = now;
            employeeSyncProgress = new EmployeeSyncProgress(
                    true,
                    false,
                    false,
                    0,
                    0,
                    0,
                    0,
                    0,
                    "人员数据更新中",
                    "正在获取员工变更...",
                    0,
                    0L
            );
            addHistoryLocked(new PunchStatusEntry(
                    System.currentTimeMillis(),
                    "收到人员更新",
                    STATUS_LEVEL_INFO
            ));
        }
        notifyStatusChanged();
    }

    public void updateEmployeeSyncBatchProcessing(int page,
                                                  int totalPages,
                                                  int batchSize,
                                                  int processedTotal) {
        long elapsedMs = currentEmployeeSyncElapsedMs();
        String pageText = totalPages > 0
                ? "第 " + page + "/" + totalPages + " 批"
                : "第 " + page + " 批";
        String detail = pageText
                + " · 本批 " + Math.max(0, batchSize) + " 人"
                + " · 已处理 " + Math.max(0, processedTotal) + " 人";
        synchronized (statusLock) {
            employeeSyncProgress = new EmployeeSyncProgress(
                    true,
                    false,
                    false,
                    Math.max(0, page),
                    Math.max(0, totalPages),
                    Math.max(0, batchSize),
                    Math.max(0, processedTotal),
                    resolveEmployeeSyncProgressPercent(Math.max(0, page - 1), totalPages),
                    "人员数据更新中",
                    detail,
                    0,
                    elapsedMs
            );
        }
        notifyStatusChanged();
    }

    public void updateEmployeeSyncItemProgress(int page,
                                               int totalPages,
                                               int batchSize,
                                               int batchProcessed,
                                               int processedTotal) {
        long now = SystemClock.elapsedRealtime();
        long elapsedMs = currentEmployeeSyncElapsedMs();
        int safeBatchSize = Math.max(0, batchSize);
        int safeBatchProcessed = Math.max(0, Math.min(batchProcessed, safeBatchSize));
        int safeProcessedTotal = Math.max(0, processedTotal);
        String pageText = totalPages > 0
                ? "第 " + page + "/" + totalPages + " 批"
                : "第 " + page + " 批";
        String detail = pageText
                + " · 当前 " + safeBatchProcessed + "/" + safeBatchSize
                + " · 累计 " + safeProcessedTotal + " 人";
        boolean shouldNotify;
        synchronized (statusLock) {
            employeeSyncProgress = new EmployeeSyncProgress(
                    true,
                    false,
                    false,
                    Math.max(0, page),
                    Math.max(0, totalPages),
                    safeBatchSize,
                    safeProcessedTotal,
                    resolveEmployeeSyncItemProgressPercent(
                            page, totalPages, safeBatchProcessed, safeBatchSize),
                    "人员数据更新中",
                    detail,
                    0,
                    elapsedMs
            );
            boolean forcePublish = safeBatchProcessed <= 1
                    || (safeBatchSize > 0 && safeBatchProcessed >= safeBatchSize);
            shouldNotify = forcePublish
                    || employeeSyncLastUiPublishElapsedMs <= 0L
                    || now - employeeSyncLastUiPublishElapsedMs >= EMPLOYEE_SYNC_UI_THROTTLE_MS;
            if (shouldNotify) {
                employeeSyncLastUiPublishElapsedMs = now;
            }
        }
        if (shouldNotify) {
            notifyStatusChanged();
        }
    }

    public void markEmployeeSyncBatchCommitted(int page,
                                               int totalPages,
                                               int batchSize,
                                               int processedTotal) {
        long elapsedMs = currentEmployeeSyncElapsedMs();
        String pageText = totalPages > 0
                ? "第 " + page + "/" + totalPages + " 批完成"
                : "第 " + page + " 批完成";
        String detail = pageText + " · 累计 " + Math.max(0, processedTotal) + " 人";
        synchronized (statusLock) {
            employeeSyncProgress = new EmployeeSyncProgress(
                    true,
                    false,
                    false,
                    Math.max(0, page),
                    Math.max(0, totalPages),
                    Math.max(0, batchSize),
                    Math.max(0, processedTotal),
                    resolveEmployeeSyncProgressPercent(page, totalPages),
                    "人员数据更新中",
                    detail,
                    0,
                    elapsedMs
            );
            addHistoryLocked(new PunchStatusEntry(
                    System.currentTimeMillis(),
                    "人员更新" + pageText + " · 累计 " + Math.max(0, processedTotal) + " 人",
                    STATUS_LEVEL_PROGRESS
            ));
        }
        notifyStatusChanged();
    }

    public void markEmployeeSyncReporting(int processedTotal, int successTotal, int failedTotal) {
        long elapsedMs = currentEmployeeSyncElapsedMs();
        synchronized (statusLock) {
            EmployeeSyncProgress previous = employeeSyncProgress;
            int page = previous == null ? 0 : previous.currentPage;
            int totalPages = previous == null ? 0 : previous.totalPages;
            int batchSize = previous == null ? 0 : previous.currentBatchSize;
            employeeSyncProgress = new EmployeeSyncProgress(
                    true,
                    false,
                    false,
                    page,
                    totalPages,
                    batchSize,
                    Math.max(0, processedTotal),
                    100,
                    "人员数据处理完成",
                    "共 " + Math.max(0, processedTotal) + " 人 · 正在确认服务器...",
                    Math.max(0, failedTotal),
                    elapsedMs
            );
        }
        notifyStatusChanged();
    }

    public void completeEmployeeSyncProgress(int processedTotal, int successTotal, int failedTotal) {
        long elapsedMs = currentEmployeeSyncElapsedMs();
        String title = "人员更新完成 · " + Math.max(0, processedTotal)
                + " 人 · 用时 " + formatEmployeeSyncDuration(elapsedMs);
        String detail = failedTotal > 0
                ? "成功 " + Math.max(0, successTotal) + " · 失败 " + Math.max(0, failedTotal)
                : "全部人员数据已处理并确认";
        synchronized (statusLock) {
            EmployeeSyncProgress previous = employeeSyncProgress;
            int page = previous == null ? 0 : previous.currentPage;
            int totalPages = previous == null ? 0 : previous.totalPages;
            int batchSize = previous == null ? 0 : previous.currentBatchSize;
            employeeSyncProgress = new EmployeeSyncProgress(
                    false,
                    true,
                    false,
                    page,
                    totalPages,
                    batchSize,
                    Math.max(0, processedTotal),
                    100,
                    title,
                    detail,
                    Math.max(0, failedTotal),
                    elapsedMs
            );
            addHistoryLocked(new PunchStatusEntry(
                    System.currentTimeMillis(),
                    title + (failedTotal > 0 ? " · 失败 " + Math.max(0, failedTotal) + " 人" : ""),
                    failedTotal > 0 ? STATUS_LEVEL_INFO : STATUS_LEVEL_SUCCESS
            ));
        }
        notifyStatusChanged();
    }

    public void failEmployeeSyncProgress(String reason, int processedTotal) {
        long elapsedMs = currentEmployeeSyncElapsedMs();
        String message = reason == null || reason.trim().isEmpty()
                ? "人员更新中断，等待服务器重新下发"
                : reason.trim();
        synchronized (statusLock) {
            EmployeeSyncProgress previous = employeeSyncProgress;
            int page = previous == null ? 0 : previous.currentPage;
            int totalPages = previous == null ? 0 : previous.totalPages;
            int batchSize = previous == null ? 0 : previous.currentBatchSize;
            int percent = previous == null ? 0 : previous.progressPercent;
            int effectiveProcessedTotal = previous == null
                    ? Math.max(0, processedTotal)
                    : Math.max(Math.max(0, processedTotal), previous.processedTotal);
            employeeSyncProgress = new EmployeeSyncProgress(
                    false,
                    false,
                    true,
                    page,
                    totalPages,
                    batchSize,
                    effectiveProcessedTotal,
                    percent,
                    "人员更新中断 · 已处理 " + effectiveProcessedTotal
                            + " 人 · 用时 " + formatEmployeeSyncDuration(elapsedMs),
                    message,
                    0,
                    elapsedMs
            );
            addHistoryLocked(new PunchStatusEntry(
                    System.currentTimeMillis(),
                    "人员更新中断 · 已处理 " + effectiveProcessedTotal + " 人",
                    STATUS_LEVEL_ERROR
            ));
        }
        notifyStatusChanged();
    }

    private long currentEmployeeSyncElapsedMs() {
        long startedAt = employeeSyncStartedAtElapsedMs;
        if (startedAt <= 0L) {
            return 0L;
        }
        return Math.max(0L, SystemClock.elapsedRealtime() - startedAt);
    }

    private int resolveEmployeeSyncProgressPercent(int completedPages, int totalPages) {
        if (totalPages <= 0) {
            return -1;
        }
        int safeCompleted = Math.max(0, Math.min(completedPages, totalPages));
        return Math.min(100, Math.round(safeCompleted * 100f / totalPages));
    }

    private int resolveEmployeeSyncItemProgressPercent(int currentPage,
                                                       int totalPages,
                                                       int batchProcessed,
                                                       int batchSize) {
        if (currentPage <= 0 || totalPages <= 0 || batchSize <= 0) {
            return -1;
        }
        int safePage = Math.max(1, Math.min(currentPage, totalPages));
        int safeBatchProcessed = Math.max(0, Math.min(batchProcessed, batchSize));
        float completedPages = safePage - 1;
        float currentPageFraction = safeBatchProcessed / (float) batchSize;
        return Math.min(100, Math.max(0,
                Math.round((completedPages + currentPageFraction) * 100f / totalPages)));
    }

    public static String formatEmployeeSyncDuration(long durationMs) {
        long totalSeconds = Math.max(0L, durationMs) / 1000L;
        if (totalSeconds < 60L) {
            return totalSeconds + "秒";
        }
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            return hours + "小时" + twoDigits(minutes) + "分" + twoDigits(seconds) + "秒";
        }
        return minutes + "分" + seconds + "秒";
    }

    private static String twoDigits(long value) {
        return value < 10L ? "0" + value : String.valueOf(value);
    }

    public void beginPunchDataPreparation(String status) {
        punchDataPreparing = true;
        punchDataReady = false;
        pushStatus(status, STATUS_LEVEL_PROGRESS, true, false);
    }

    public void updatePunchDataPreparationStatus(String status) {
        punchDataReady = false;
        pushStatus(status, STATUS_LEVEL_PROGRESS, true, false);
    }

    public void markPunchRecognitionReady(String status) {
        punchDataPreparing = false;
        punchDataReady = true;
        punchPreparationRetryNotBeforeAtMs = 0L;
        pushStatus(status, STATUS_LEVEL_SUCCESS, true, false);
    }

    public void markPunchRecognitionFailed(String status) {
        punchDataPreparing = false;
        punchDataReady = false;
        punchPreparationRetryNotBeforeAtMs = PunchPreparationPolicy.nextRetryAt(
                System.currentTimeMillis(),
                PUNCH_PREPARATION_RETRY_DELAY_MS
        );
        pushStatus(status, STATUS_LEVEL_ERROR, true, true);
    }

    public void startSyncService() {
        if (!SessionManager.get().isTokenValid()) {
            return;
        }
        try {
            HeartbeatManager.get(this).start();
        } catch (Exception e) {
            Log.e(TAG, "startSyncService error", e);
        }
    }

    private void runPunchPreparation() {
        beginPunchDataPreparation("正在初始化人脸引擎...");
        initFaceSDK();
        if (!waitForFaceSdkReady()) {
            markPunchRecognitionFailed("人脸引擎初始化失败");
            return;
        }

        if (DatabaseHelper.get(this).getActiveEmployeeCount() <= 0) {
            if (!SyncCoordinator.get().syncEmployeesForPreparation(this)) {
                markPunchRecognitionFailed("员工同步失败，等待重试");
                return;
            }
        } else {
            beginPunchDataPreparation("正在检查人脸库...");
            FaceLibraryReconciler.ReconcileResult reconcileResult =
                    FaceLibraryReconciler.get().reconcile(this);
            if (reconcileResult.isConsistent()) {
                AppLogger.i(TAG, "Skip Full Rebuild: runtime face library is consistent");
                markPunchRecognitionReady("准备完成，可以开始打卡");
            } else {
                AppLogger.w(TAG, "Face runtime reconcile requires rebuild: " + reconcileResult.reason);
                beginPunchDataPreparation("正在重建人脸库...");
                if (!SyncCoordinator.get().rebuildLocalFaceLibrary(this)) {
                    markPunchRecognitionFailed("人脸库重建失败，请稍后重试");
                    return;
                }
            }
        }
        FaceApplyWorker.get().trigger(this);
    }

    private void enqueuePunchPreparation() {
        try {
            appExecutor.execute(() -> {
                try {
                    runPunchPreparation();
                } catch (RuntimeException | LinkageError error) {
                    handlePunchPreparationCrash(error);
                }
            });
        } catch (RuntimeException | LinkageError error) {
            handlePunchPreparationCrash(error);
        }
    }

    private void handlePunchPreparationCrash(Throwable error) {
        AppLogger.e(TAG, "Punch preparation crashed", error);
        markPunchRecognitionFailed(PUNCH_STATUS_PREPARATION_CRASHED);
    }

    private boolean waitForFaceSdkReady() {
        long deadline = System.currentTimeMillis() + FACE_SDK_READY_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (FaceManager.get().isInitialized()) {
                return true;
            }
            if (!faceSdkInitializing) {
                return false;
            }
            try {
                Thread.sleep(300L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void pushStatus(String status, int level, boolean keepAsCurrent, boolean requestAttention) {
        String message = status == null ? "" : status.trim();
        if (message.isEmpty()) {
            return;
        }
        synchronized (statusLock) {
            if (keepAsCurrent) {
                punchDataStatus = message;
                punchDataStatusLevel = level;
            }
            if (requestAttention) {
                punchStatusAttention = true;
            }
            addHistoryLocked(new PunchStatusEntry(System.currentTimeMillis(), message, level));
        }
        notifyStatusChanged();
    }

    private void addHistoryLocked(PunchStatusEntry entry) {
        PunchStatusEntry latest = recentStatusEntries.peekFirst();
        if (latest != null
                && latest.level == entry.level
                && latest.message.equals(entry.message)) {
            return;
        }
        recentStatusEntries.addFirst(entry);
        while (recentStatusEntries.size() > MAX_STATUS_HISTORY) {
            recentStatusEntries.removeLast();
        }
    }

    private void notifyStatusChanged() {
        PunchStatusSnapshot snapshot = getPunchStatusSnapshot();
        for (PunchStatusListener listener : statusListeners) {
            notifyListener(listener, snapshot);
        }
    }

    private void notifyListener(PunchStatusListener listener, PunchStatusSnapshot snapshot) {
        mainHandler.post(() -> listener.onPunchStatusChanged(snapshot));
    }

    public interface PunchStatusListener {
        void onPunchStatusChanged(PunchStatusSnapshot snapshot);
    }

    public static final class PunchStatusSnapshot {
        public final String currentStatus;
        public final int currentLevel;
        public final boolean shouldExpandHistory;
        public final List<PunchStatusEntry> recentEntries;
        public final EmployeeSyncProgress employeeSyncProgress;

        private PunchStatusSnapshot(String currentStatus,
                                    int currentLevel,
                                    boolean shouldExpandHistory,
                                    List<PunchStatusEntry> recentEntries,
                                    EmployeeSyncProgress employeeSyncProgress) {
            this.currentStatus = currentStatus;
            this.currentLevel = currentLevel;
            this.shouldExpandHistory = shouldExpandHistory;
            this.recentEntries = recentEntries;
            this.employeeSyncProgress = employeeSyncProgress;
        }
    }

    public static final class EmployeeSyncProgress {
        public final boolean active;
        public final boolean completed;
        public final boolean failed;
        public final int currentPage;
        public final int totalPages;
        public final int currentBatchSize;
        public final int processedTotal;
        public final int progressPercent;
        public final String title;
        public final String detail;
        public final int failedTotal;
        public final long elapsedMs;

        private EmployeeSyncProgress(boolean active,
                                     boolean completed,
                                     boolean failed,
                                     int currentPage,
                                     int totalPages,
                                     int currentBatchSize,
                                     int processedTotal,
                                     int progressPercent,
                                     String title,
                                     String detail,
                                     int failedTotal,
                                     long elapsedMs) {
            this.active = active;
            this.completed = completed;
            this.failed = failed;
            this.currentPage = currentPage;
            this.totalPages = totalPages;
            this.currentBatchSize = currentBatchSize;
            this.processedTotal = processedTotal;
            this.progressPercent = progressPercent;
            this.title = title == null ? "" : title;
            this.detail = detail == null ? "" : detail;
            this.failedTotal = failedTotal;
            this.elapsedMs = elapsedMs;
        }
    }

    public static final class PunchStatusEntry {
        public final long timeMillis;
        public final String message;
        public final int level;

        private PunchStatusEntry(long timeMillis, String message, int level) {
            this.timeMillis = timeMillis;
            this.message = message;
            this.level = level;
        }
    }
}

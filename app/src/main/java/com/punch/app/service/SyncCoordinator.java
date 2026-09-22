package com.punch.app.service;

import android.content.Context;

import com.punch.app.PunchApplication;
import com.punch.app.db.DatabaseHelper;
import com.punch.app.face.FaceApplyWorker;
import com.punch.app.face.FaceFileManager;
import com.punch.app.face.FaceManager;
import com.punch.app.face.FaceRegistrationManager;
import com.punch.app.model.Employee;
import com.punch.app.model.PunchRecord;
import com.punch.app.model.SyncQueueItem;
import com.punch.app.network.ApiResult;
import com.punch.app.network.InteractionLogger;
import com.punch.app.network.ApiService;
import com.punch.app.network.dto.DeviceDto;
import com.punch.app.network.dto.EmployeeSyncData;
import com.punch.app.network.dto.EventResultDto;
import com.punch.app.network.dto.HeartbeatDto;
import com.punch.app.network.dto.PunchDto;
import com.punch.app.utils.AppLogger;
import com.punch.app.utils.Constants;
import com.punch.app.utils.PunchSnapshotHelper;
import com.punch.app.utils.SessionManager;
import com.punch.app.utils.UpdateManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class SyncCoordinator {
    private static final String TAG = "SyncCoordinator";
    private static final long FACE_REGISTRATION_TIMEOUT_SECONDS = 300L;
    private static final long SYNCED_SNAPSHOT_CLEANUP_DELAY_MS = 5000L;
    private static final int FACE_APPLY_BACKLOG_HIGH_WATERMARK = 500;
    private static final int FACE_APPLY_BACKLOG_LOW_WATERMARK = 200;
    private static final long FACE_APPLY_BACKPRESSURE_WAIT_MS = 250L;
    private static final long FACE_APPLY_BACKPRESSURE_MAX_WAIT_MS = 30_000L;
    private static final String FAILURE_MSG_EMPLOYEE_SYNC_FAILED = "员工同步失败";
    private static final String FAILURE_MSG_FACE_SDK_NOT_READY = "人脸引擎未就绪";
    private static final String FAILURE_MSG_FACE_REGISTRATION_INCOMPLETE = "人脸注册未完成";
    private static final String FAILURE_MSG_DEVICE_CONFIG_SYNC_FAILED = "设备配置同步失败";
    private static final String FAILURE_MSG_FACE_IMAGE_URL_EMPTY = "人脸图片URL为空";
    private static final String STATUS_MSG_PUNCH_READY = "准备完成，可以开始打卡";
    private static final String STATUS_MSG_PUNCH_PARTIAL_READY = "打卡已可用，部分员工人脸未入库";
    private static final String STATUS_MSG_FACE_LIBRARY_REBUILD_FAILED = "人脸库重建失败，请稍后重试";
    private static SyncCoordinator instance;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService punchSyncExecutor = Executors.newSingleThreadExecutor();
    private final PlatformEventExecutor platformEventExecutor =
            new PlatformEventExecutor("platform-event-executor");
    private final ScheduledExecutorService snapshotCleanupExecutor = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean heartbeatQueued = new AtomicBoolean(false);
    private final AtomicBoolean punchSyncQueued = new AtomicBoolean(false);
    private final AtomicBoolean faceApplyBackpressureActive = new AtomicBoolean(false);
    private final AtomicReference<SyncTrigger> pendingTrigger = new AtomicReference<>();
    private final AtomicReference<SyncTrigger> pendingPunchTrigger = new AtomicReference<>();

    private SyncCoordinator() {
    }

    public static synchronized SyncCoordinator get() {
        if (instance == null) {
            instance = new SyncCoordinator();
        }
        return instance;
    }

    public void enqueueHeartbeatCycle(Context context) {
        enqueueHeartbeatCycle(context, SyncTrigger.HEARTBEAT);
    }

    public void enqueueHeartbeatCycle(Context context, SyncTrigger trigger) {
        Context appContext = context.getApplicationContext();
        mergePendingTrigger(trigger);
        if (!heartbeatQueued.compareAndSet(false, true)) {
            AppLogger.d(TAG, "Heartbeat cycle already queued");
            return;
        }
        executor.execute(() -> {
            try {
                do {
                    SyncTrigger currentTrigger = pendingTrigger.getAndSet(null);
                    runHeartbeatCycle(appContext, currentTrigger);
                } while (pendingTrigger.get() != null);
            } finally {
                heartbeatQueued.set(false);
                SyncTrigger nextTrigger = pendingTrigger.get();
                if (nextTrigger != null) {
                    enqueueHeartbeatCycle(appContext, nextTrigger);
                }
            }
        });
    }

    private void mergePendingTrigger(SyncTrigger trigger) {
        SyncTrigger safeTrigger = trigger != null ? trigger : SyncTrigger.HEARTBEAT;
        pendingTrigger.updateAndGet(existing -> mergeTriggers(existing, safeTrigger));
    }

    private SyncTrigger mergeTriggers(SyncTrigger existing, SyncTrigger incoming) {
        if (existing == null) {
            return incoming;
        }
        if (!existing.shouldResetLimitedPunchRetries() && incoming.shouldResetLimitedPunchRetries()) {
            return incoming;
        }
        return existing;
    }

    public boolean syncEmployeesForPreparation(Context context) {
        return syncEmployeesInternal(context.getApplicationContext()).overallSuccess;
    }

    public boolean rebuildLocalFaceLibrary(Context context) {
        Context appContext = context.getApplicationContext();
        if (!FaceManager.get().isInitialized()) {
            AppLogger.w(TAG, "Cannot rebuild face library: face SDK is not ready");
            return false;
        }
        FaceRegistrationOutcome outcome = waitForFaceRegistration(appContext);
        PunchApplication app = PunchApplication.get();
        if (!rebuildFinalFaceLibrary(appContext, app)) {
            return false;
        }
        if (app != null) {
            publishPreparationOutcome(app, outcome);
        }
        return outcome.isUsable() || FaceManager.get().getLoadedFaceCount() > 0;
    }

    private void runHeartbeatCycle(Context appContext, SyncTrigger trigger) {
        if (!SessionManager.get().isDeviceRegistered()) {
            AppLogger.d(TAG, "Skip sync: device not registered");
            return;
        }
        if (!SessionManager.get().isTokenValid()) {
            AppLogger.d(TAG, "Skip sync: token missing or expired");
            return;
        }

        enqueuePunchSync(appContext, trigger);
        if (FaceManager.get().isInitialized() && FaceManager.get().isFaceLibraryReady()) {
            FaceApplyWorker.get().trigger(appContext);
        }

        ApiResult<HeartbeatDto.HeartbeatData> heartbeat = ApiService.fetchHeartbeat(appContext);
        if (!heartbeat.success || heartbeat.data == null) {
            AppLogger.w(TAG, "Heartbeat failed: " + heartbeat.message);
            InteractionLogger.logBusinessFailure(
                    InteractionLogger.GROUP_HEARTBEAT,
                    "心跳请求失败",
                    safeString(heartbeat.message)
            );
            return;
        }

        long nowSeconds = System.currentTimeMillis() / 1000L;
        SessionManager.get().saveLastHeartbeatTime(nowSeconds);
        if (heartbeat.data.serverTime > 0) {
            SessionManager.get().saveLastServerTime(heartbeat.data.serverTime);
        }

        if (heartbeat.data.hasChanges && !heartbeat.data.events.isEmpty()) {
            InteractionLogger.logBusiness(
                    InteractionLogger.GROUP_HEARTBEAT,
                    "收到平台事件",
                    "事件数 " + heartbeat.data.events.size()
            );
            applyHeartbeatEvents(appContext, heartbeat.data.events);
        }
    }

    private void applyHeartbeatEvents(Context appContext, List<HeartbeatDto.HeartbeatEventData> events) {
        for (HeartbeatDto.HeartbeatEventData event : events) {
            if (event == null || isBlank(event.cursor)) {
                InteractionLogger.logBusinessFailure(
                        InteractionLogger.GROUP_HEARTBEAT,
                        "收到无效事件",
                        "缺少 cursor，事件已跳过"
                );
                continue;
            }

            InteractionLogger.logBusiness(
                    resolveEventGroup(event.eventType),
                    "平台事件已进入独立执行队列",
                    "cursor=" + event.cursor + "\\nevent_type=" + safeString(event.eventType)
            );
            PlatformEventExecutor.SubmitResult submitResult = platformEventExecutor.submit(
                    event.cursor,
                    () -> preparePlatformEventAck(appContext, event)
            );
            AppLogger.d(TAG, "Platform event submit: cursor=" + event.cursor
                    + " result=" + submitResult.name());
        }
    }

    private PlatformEventExecutor.AckWork preparePlatformEventAck(
            Context appContext,
            HeartbeatDto.HeartbeatEventData event) {
        InteractionLogger.logBusiness(
                resolveEventGroup(event.eventType),
                "开始处理平台事件",
                "cursor=" + event.cursor + "\\nevent_type=" + safeString(event.eventType)
        );

        if ("person_changed".equals(safeString(event.eventType))) {
            return processEmployeesEventInBatches(appContext, event);
        }

        EventProcessingOutcome outcome = handleEvent(appContext, event);
        if (!outcome.success) {
            AppLogger.w(TAG, "Event handled with failure: cursor=" + event.cursor
                    + ", reason=" + outcome.failureMessage);
            InteractionLogger.logBusinessFailure(
                    resolveEventGroup(event.eventType),
                    "平台事件处理失败",
                    "cursor=" + event.cursor + "\\nreason=" + safeString(outcome.failureMessage)
            );
        }
        return () -> reportPlatformEventAck(event, outcome);
    }

    private boolean reportPlatformEventAck(HeartbeatDto.HeartbeatEventData event,
                                           EventProcessingOutcome outcome) {
        ApiResult<Void> ackResult = ApiService.reportEventResult(
                event.cursor,
                event.eventType,
                outcome.success,
                outcome.employeeResults,
                outcome.failureMessage
        );
        if (!ackResult.success) {
            AppLogger.w(TAG, "Event result report failed: " + ackResult.message
                    + ", cursor=" + event.cursor);
            InteractionLogger.logBusinessFailure(
                    InteractionLogger.GROUP_EVENT_RESULT,
                    "事件结果回传失败",
                    "cursor=" + event.cursor + "\\nreason=" + safeString(ackResult.message)
            );
            return false;
        }
        InteractionLogger.logBusiness(
                InteractionLogger.GROUP_EVENT_RESULT,
                "事件结果已回传",
                "cursor=" + event.cursor + "\\nsuccess=" + outcome.success
        );
        return true;
    }

    private EmployeeBatchOutcome processEmployeeEventBatch(
            Context context,
            List<EmployeeSyncData.ChangeItem> changeItems,
            EmployeeBatchProgressCallback progressCallback) {
        int rawBatchSize = changeItems == null ? 0 : changeItems.size();
        List<EmployeeSyncData.ChangeItem> effectiveChanges = coalesceLatestEmployeeChanges(changeItems);
        AtomicInteger processedCount = new AtomicInteger(Math.max(0, rawBatchSize - effectiveChanges.size()));
        DatabaseHelper db = DatabaseHelper.get(context);
        LinkedHashMap<String, EventResultDto.EmployeeResult> employeeResults = new LinkedHashMap<>();
        LinkedHashMap<String, DatabaseHelper.FaceBatchWrite> writesByEmployee = new LinkedHashMap<>();
        LinkedHashMap<String, Employee> preparationTargets = new LinkedHashMap<>();
        Map<String, String> resultKeyByEmployeeId = new HashMap<>();

        if (!FaceManager.get().isInitialized()) {
            return EmployeeBatchOutcome.failure(FAILURE_MSG_FACE_SDK_NOT_READY, new ArrayList<>());
        }

        for (EmployeeSyncData.ChangeItem changeItem : effectiveChanges) {
            if (changeItem == null || isBlank(changeItem.numbers)) {
                return EmployeeBatchOutcome.failure("员工变更缺少人员编号", toEmployeeResultList(employeeResults));
            }
            String numbers = changeItem.numbers;
            String opType = safeOpType(changeItem.opType);

            if ("delete".equalsIgnoreCase(changeItem.opType)) {
                Employee existing = db.getEmployee(numbers);
                if (isStaleEmployeeChange(existing, changeItem.opTime) || existing == null) {
                    employeeResults.put(numbers, EventResultDto.EmployeeResult.success(numbers, "delete"));
                    markEmployeeBatchItemProcessed(progressCallback, processedCount, rawBatchSize);
                    continue;
                }
                preparationTargets.remove(numbers);
                writesByEmployee.put(numbers, DatabaseHelper.FaceBatchWrite.remove(numbers, changeItem.opTime));
                resultKeyByEmployeeId.put(numbers, numbers);
                employeeResults.put(numbers, EventResultDto.EmployeeResult.success(numbers, "delete"));
                markEmployeeBatchItemProcessed(progressCallback, processedCount, rawBatchSize);
                continue;
            }

            Employee incoming = changeItem.employee;
            if (incoming == null) {
                employeeResults.put(
                        numbers,
                        EventResultDto.EmployeeResult.failure(numbers, opType, FaceManager.ERROR_INVALID_FACE_IMAGE));
                markEmployeeBatchItemProcessed(progressCallback, processedCount, rawBatchSize);
                continue;
            }

            Employee existing = db.getEmployee(incoming.id);
            if (isStaleEmployeeChange(existing, changeItem.opTime)) {
                employeeResults.put(numbers, EventResultDto.EmployeeResult.success(numbers, opType));
                markEmployeeBatchItemProcessed(progressCallback, processedCount, rawBatchSize);
                continue;
            }

            Employee merged = mergeEmployee(existing, incoming);
            DatabaseHelper.FaceBatchWrite write = DatabaseHelper.FaceBatchWrite.upsert(merged);
            writesByEmployee.put(merged.id, write);
            resultKeyByEmployeeId.put(merged.id, numbers);
            EventResultDto.EmployeeResult employeeResult = EventResultDto.EmployeeResult.success(numbers, opType);
            employeeResults.put(numbers, employeeResult);

            if (!isFaceEnabled(merged)) {
                preparationTargets.remove(merged.id);
                merged.localFaceId = null;
                merged.faceRegistered = 0;
                write.withRemoveTask();
                markEmployeeBatchItemProcessed(progressCallback, processedCount, rawBatchSize);
                continue;
            }

            if (isBlank(merged.faceImageUrl)) {
                preparationTargets.remove(merged.id);
                merged.localFaceId = null;
                merged.faceRegistered = 0;
                write.withRemoveTask();
                employeeResult.success = false;
                employeeResult.failMsg = FAILURE_MSG_FACE_IMAGE_URL_EMPTY;
                markEmployeeBatchItemProcessed(progressCallback, processedCount, rawBatchSize);
                continue;
            }

            if (merged.faceRegistered == 1) {
                preparationTargets.remove(merged.id);
                markEmployeeBatchItemProcessed(progressCallback, processedCount, rawBatchSize);
                continue;
            }

            byte[] cachedFeature = db.getValidFaceFeature(
                    merged.id,
                    merged.faceVersion,
                    merged.faceImageSha256,
                    FaceManager.get().getFeatureSchemaVersion());
            if (cachedFeature != null) {
                write.withFeature(cachedFeature, FaceManager.get().getFeatureSchemaVersion());
                markEmployeeBatchItemProcessed(progressCallback, processedCount, rawBatchSize);
                continue;
            }
            preparationTargets.put(merged.id, merged);
        }

        if (!preparationTargets.isEmpty()) {
            FacePreparationOutcome preparationOutcome = waitForFacePreparation(
                    context,
                    new ArrayList<>(preparationTargets.values()),
                    prepared -> markEmployeeBatchItemProcessed(
                            progressCallback, processedCount, rawBatchSize));
            if (!preparationOutcome.completed) {
                return EmployeeBatchOutcome.failure(
                        FAILURE_MSG_FACE_REGISTRATION_INCOMPLETE,
                        toEmployeeResultList(employeeResults));
            }

            Map<String, FaceRegistrationManager.PreparedFaceResult> preparedById = new HashMap<>();
            for (FaceRegistrationManager.PreparedFaceResult prepared : preparationOutcome.results) {
                if (prepared != null && !isBlank(prepared.empId)) {
                    preparedById.put(prepared.empId, prepared);
                }
            }

            for (Map.Entry<String, Employee> entry : preparationTargets.entrySet()) {
                String empId = entry.getKey();
                Employee employee = entry.getValue();
                DatabaseHelper.FaceBatchWrite write = writesByEmployee.get(empId);
                String resultKey = resultKeyByEmployeeId.get(empId);
                EventResultDto.EmployeeResult employeeResult = employeeResults.get(resultKey);
                FaceRegistrationManager.PreparedFaceResult prepared = preparedById.get(empId);
                if (write == null || employeeResult == null) {
                    continue;
                }
                if (prepared == null || !prepared.success || prepared.feature == null || prepared.feature.length != 512) {
                    employee.localFaceId = null;
                    employee.faceRegistered = 0;
                    write.withRemoveTask();
                    employeeResult.success = false;
                    employeeResult.failMsg = prepared == null
                            ? FAILURE_MSG_FACE_REGISTRATION_INCOMPLETE
                            : safeString(prepared.failMsg);
                    continue;
                }
                write.withFeature(prepared.feature, FaceManager.get().getFeatureSchemaVersion());
            }
        }

        List<DatabaseHelper.FaceBatchWrite> batchWrites = new ArrayList<>(writesByEmployee.values());
        if (!db.commitEmployeeFaceBatch(batchWrites)) {
            return EmployeeBatchOutcome.failure("员工批次本地落库失败", toEmployeeResultList(employeeResults));
        }
        pruneCommittedFaceFiles(context, batchWrites);
        return EmployeeBatchOutcome.success(toEmployeeResultList(employeeResults));
    }

    private void pruneCommittedFaceFiles(Context context,
                                         List<DatabaseHelper.FaceBatchWrite> writes) {
        if (context == null || writes == null) {
            return;
        }
        for (DatabaseHelper.FaceBatchWrite write : writes) {
            if (write == null) {
                continue;
            }
            String employeeId = write.employee != null ? write.employee.id : write.employeeId;
            if (isBlank(employeeId)) {
                continue;
            }
            if (write.markDeleted || DatabaseHelper.FaceBatchWrite.OP_REMOVE.equals(write.applyOperation)) {
                FaceFileManager.deleteFaceImage(context, employeeId);
                continue;
            }
            if (write.employee != null && write.feature != null) {
                FaceFileManager.pruneObsoleteFaceImages(
                        context,
                        employeeId,
                        write.employee.faceVersion,
                        write.employee.faceImageSha256,
                        write.employee.faceImageUrl
                );
            }
        }
    }

    private void markEmployeeBatchItemProcessed(EmployeeBatchProgressCallback progressCallback,
                                                AtomicInteger processedCount,
                                                int rawBatchSize) {
        int processed = processedCount.incrementAndGet();
        if (progressCallback != null) {
            progressCallback.onProgress(processed, Math.max(0, rawBatchSize));
        }
    }

    private List<EmployeeSyncData.ChangeItem> coalesceLatestEmployeeChanges(
            List<EmployeeSyncData.ChangeItem> changeItems) {
        List<EmployeeSyncData.ChangeItem> effective = new ArrayList<>();
        Map<String, Integer> indexByEmployee = new HashMap<>();
        if (changeItems == null) {
            return effective;
        }
        for (EmployeeSyncData.ChangeItem item : changeItems) {
            if (item == null || isBlank(item.numbers)) {
                effective.add(item);
                continue;
            }
            String key = item.numbers.trim();
            Integer index = indexByEmployee.get(key);
            if (index == null) {
                indexByEmployee.put(key, effective.size());
                effective.add(item);
                continue;
            }
            EmployeeSyncData.ChangeItem current = effective.get(index);
            long currentTime = current == null ? 0L : current.opTime;
            long candidateTime = item.opTime;
            if (candidateTime <= 0L || currentTime <= 0L || candidateTime >= currentTime) {
                effective.set(index, item);
            }
        }
        return effective;
    }

    private boolean shouldPauseEmployeeBatchFetch(Context context) {
        DatabaseHelper db = DatabaseHelper.get(context);
        int pendingCount = db.getPendingFaceApplyTaskCount();
        boolean active = faceApplyBackpressureActive.get();

        if (pendingCount >= FACE_APPLY_BACKLOG_HIGH_WATERMARK) {
            faceApplyBackpressureActive.set(true);
            FaceApplyWorker.get().trigger(context);
            AppLogger.w(TAG, "Pause employee batch fetch: face apply backlog=" + pendingCount
                    + " highWatermark=" + FACE_APPLY_BACKLOG_HIGH_WATERMARK);
            return true;
        }

        if (active && pendingCount > FACE_APPLY_BACKLOG_LOW_WATERMARK) {
            FaceApplyWorker.get().trigger(context);
            AppLogger.d(TAG, "Keep employee batch fetch paused: face apply backlog=" + pendingCount
                    + " lowWatermark=" + FACE_APPLY_BACKLOG_LOW_WATERMARK);
            return true;
        }

        if (active) {
            faceApplyBackpressureActive.set(false);
            AppLogger.i(TAG, "Resume employee batch fetch: face apply backlog=" + pendingCount);
        }
        return false;
    }

    private boolean waitForFaceApplyBackpressure(Context context) {
        if (!shouldPauseEmployeeBatchFetch(context)) {
            return true;
        }
        long deadline = System.currentTimeMillis() + FACE_APPLY_BACKPRESSURE_MAX_WAIT_MS;
        while (shouldPauseEmployeeBatchFetch(context)) {
            if (!FaceManager.get().isInitialized() || !FaceManager.get().isFaceLibraryReady()) {
                AppLogger.w(TAG, "Face apply backlog cannot drain because runtime library is not ready");
                return false;
            }
            if (System.currentTimeMillis() >= deadline) {
                AppLogger.w(TAG, "Timed out waiting for face apply backlog to drain");
                return false;
            }
            try {
                Thread.sleep(FACE_APPLY_BACKPRESSURE_WAIT_MS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    private PlatformEventExecutor.AckWork processEmployeesEventInBatches(Context context,
                                                HeartbeatDto.HeartbeatEventData event) {
        PunchApplication app = PunchApplication.get();
        if (app != null) {
            app.beginEmployeeSyncProgress(event.cursor);
        }
        InteractionLogger.logBusiness(
                InteractionLogger.GROUP_EMPLOYEE_SYNC,
                "开始分批同步员工列表",
                "event_cursor=" + safeString(event.cursor) + "\n每批最多 200 人，全部落库后统一回传");

        int page = 1;
        List<EventResultDto.EmployeeResult> eventEmployeeResults = new ArrayList<>();

        while (true) {
            ApiResult<EmployeeSyncData> result = ApiService.syncEmployees(page);
            if (!result.success || result.data == null) {
                if (app != null) {
                    app.failEmployeeSyncProgress("人员更新中断，等待服务器重新下发", eventEmployeeResults.size());
                }
                InteractionLogger.logBusinessFailure(
                        InteractionLogger.GROUP_EMPLOYEE_SYNC,
                        "员工批次拉取失败",
                        "page=" + page + "\nreason=" + safeString(result.message));
                return null;
            }

            EmployeeSyncData data = result.data;
            if (data.changeItems == null || data.changeItems.isEmpty()) {
                if (data.serverTime > 0) {
                    SessionManager.get().saveLastServerTime(data.serverTime);
                }
                break;
            }

            if (app != null) {
                app.updateEmployeeSyncBatchProcessing(
                        page,
                        data.totalPages,
                        data.changeItems.size(),
                        eventEmployeeResults.size());
            }

            final int currentPage = page;
            final int totalPages = data.totalPages;
            final int processedBeforePage = eventEmployeeResults.size();
            EmployeeBatchOutcome batch = processEmployeeEventBatch(
                    context,
                    data.changeItems,
                    (batchProcessed, batchTotal) -> {
                        if (app != null) {
                            app.updateEmployeeSyncItemProgress(
                                    currentPage,
                                    totalPages,
                                    batchTotal,
                                    batchProcessed,
                                    processedBeforePage + batchProcessed);
                        }
                    });
            if (!batch.durableCommitSucceeded) {
                if (app != null) {
                    app.failEmployeeSyncProgress("人员更新落库失败，等待服务器重新下发", eventEmployeeResults.size());
                }
                InteractionLogger.logBusinessFailure(
                        InteractionLogger.GROUP_EMPLOYEE_SYNC,
                        "员工批次处理失败",
                        "page=" + page + "\nreason=" + safeString(batch.failureMessage));
                return null;
            }

            eventEmployeeResults.addAll(batch.employeeResults);
            if (app != null) {
                app.markEmployeeSyncBatchCommitted(
                        page,
                        data.totalPages,
                        batch.employeeResults.size(),
                        eventEmployeeResults.size());
            }

            // Each batch has already committed its desired state. FaceSearch application is
            // device-local and can converge asynchronously while later pages are prepared.
            FaceApplyWorker.get().trigger(context);

            if (data.serverTime > 0) {
                SessionManager.get().saveLastServerTime(data.serverTime);
            }
            InteractionLogger.logBusiness(
                    InteractionLogger.GROUP_EMPLOYEE_SYNC,
                    "员工批次已落库",
                    "page=" + page + "\n人数=" + batch.employeeResults.size());

            if (!data.hasMore) {
                break;
            }
            if (!waitForFaceApplyBackpressure(context)) {
                if (app != null) {
                    app.failEmployeeSyncProgress(
                            "人员更新暂停：本地人脸应用积压，等待下一次重试",
                            eventEmployeeResults.size());
                }
                InteractionLogger.logBusinessFailure(
                        InteractionLogger.GROUP_EMPLOYEE_SYNC,
                        "员工批次触发背压保护",
                        "page=" + page + "\\npending_face_apply="
                                + DatabaseHelper.get(context).getPendingFaceApplyTaskCount());
                return null;
            }
            page += 1;
        }

        int successTotal = countSucceededEmployeeResults(eventEmployeeResults);
        int failedTotal = eventEmployeeResults.size() - successTotal;
        if (app != null) {
            app.markEmployeeSyncReporting(eventEmployeeResults.size(), successTotal, failedTotal);
        }

        final int total = eventEmployeeResults.size();
        final List<EventResultDto.EmployeeResult> ackEmployeeResults =
                new ArrayList<>(eventEmployeeResults);
        return () -> {
            ApiResult<Void> ackResult = ApiService.reportEventResult(
                    event.cursor,
                    event.eventType,
                    true,
                    ackEmployeeResults,
                    null);
            if (!ackResult.success) {
                if (app != null) {
                    app.failEmployeeSyncProgress(
                            "人员更新确认失败，等待服务器重新下发",
                            total);
                }
                AppLogger.w(TAG, "Employee event ACK failed: cursor=" + event.cursor
                        + " reason=" + ackResult.message);
                InteractionLogger.logBusinessFailure(
                        InteractionLogger.GROUP_EVENT_RESULT,
                        "员工事件结果回传失败",
                        "cursor=" + event.cursor + "\\nreason=" + safeString(ackResult.message));
                return false;
            }

            if (app != null) {
                app.completeEmployeeSyncProgress(total, successTotal, failedTotal);
            }
            InteractionLogger.logBusiness(
                    InteractionLogger.GROUP_EVENT_RESULT,
                    "事件结果已回传",
                    "cursor=" + event.cursor + "\\n人数=" + total);
            return true;
        };
    }

    private EventProcessingOutcome handleEvent(Context context, HeartbeatDto.HeartbeatEventData event) {
        String eventType = safeString(event.eventType);
        switch (eventType) {
            case "config_changed":
                return syncDeviceConfig(context)
                        ? EventProcessingOutcome.success()
                        : EventProcessingOutcome.failure(FAILURE_MSG_DEVICE_CONFIG_SYNC_FAILED);
            default:
                AppLogger.d(TAG, "Ignore heartbeat event: " + eventType);
                return EventProcessingOutcome.success();
        }
    }

    private void enqueuePunchSync(Context context, SyncTrigger trigger) {
        Context appContext = context.getApplicationContext();
        SyncTrigger safeTrigger = trigger != null ? trigger : SyncTrigger.HEARTBEAT;
        pendingPunchTrigger.updateAndGet(existing -> mergeTriggers(existing, safeTrigger));
        if (!punchSyncQueued.compareAndSet(false, true)) {
            return;
        }
        punchSyncExecutor.execute(() -> {
            try {
                do {
                    SyncTrigger currentTrigger = pendingPunchTrigger.getAndSet(null);
                    syncPunches(appContext, currentTrigger);
                } while (pendingPunchTrigger.get() != null);
            } finally {
                punchSyncQueued.set(false);
                SyncTrigger nextTrigger = pendingPunchTrigger.get();
                if (nextTrigger != null) {
                    enqueuePunchSync(appContext, nextTrigger);
                }
            }
        });
    }

    private void syncPunches(Context context, SyncTrigger trigger) {
        SyncTrigger safeTrigger = trigger != null ? trigger : SyncTrigger.HEARTBEAT;
        DatabaseHelper db = DatabaseHelper.get(context);
        int repaired = db.repairPunchSyncQueue();
        if (repaired > 0) {
            InteractionLogger.logBusiness(
                    InteractionLogger.GROUP_PUNCH,
                    "补建打卡同步队列",
                    "补建 " + repaired + " 条历史未同步打卡记录"
            );
        }
        if (safeTrigger.shouldResetLimitedPunchRetries()) {
            int reset = db.resetLimitedPunchSyncRetries();
            if (reset > 0) {
                InteractionLogger.logBusiness(
                        InteractionLogger.GROUP_PUNCH,
                        "重置打卡同步重试次数",
                        "trigger=" + safeTrigger.name()
                                + "\nreset_count=" + reset
                );
            }
        } else if (safeTrigger.shouldResetExpiredPunchRetries()) {
            long localDayStartSeconds = PunchSyncPolicy.startOfLocalDayEpochSeconds(
                    System.currentTimeMillis(),
                    TimeZone.getDefault());
            int reset = db.resetExpiredPunchSyncRetries(localDayStartSeconds);
            if (reset > 0) {
                InteractionLogger.logBusiness(
                        InteractionLogger.GROUP_PUNCH,
                        "跨日重置打卡同步重试次数",
                        "trigger=" + safeTrigger.name()
                                + "\nreset_count=" + reset
                                + "\nlocal_day_start=" + localDayStartSeconds
                );
            }
        }
        List<SyncQueueItem> queue = db.getRetryableSyncQueue(
                Constants.ACTION_PUNCH_PUSH,
                Constants.SYNC_MAX_RETRY,
                Constants.PUNCH_BATCH_SIZE);
        if (queue.isEmpty()) {
            return;
        }

        long batchStartedAt = System.nanoTime();
        int processed = 0;
        for (SyncQueueItem item : queue) {
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - batchStartedAt);
            if (!PunchSyncPolicy.canContinueBatch(
                    processed,
                    elapsedMillis,
                    Constants.PUNCH_BATCH_SIZE,
                    Constants.PUNCH_SYNC_TIME_BUDGET_MS)) {
                break;
            }
            processed++;

            PunchRecord punch = db.getUnsyncedPunchRecord(item.recordId);
            if (punch == null) {
                InteractionLogger.logBusinessFailure(
                        InteractionLogger.GROUP_PUNCH,
                        "移除无效打卡同步任务",
                        "record_id=" + safeString(item.recordId)
                                + "\nreason=未找到对应未同步打卡记录"
                );
                db.removeSyncQueueItem(item.id);
                continue;
            }

            InteractionLogger.logBusiness(
                    InteractionLogger.GROUP_PUNCH,
                    "开始上传打卡记录",
                    buildPunchSyncLogDetail(punch, item.retryCount, "")
            );
            ApiResult<PunchDto.PunchPushData> result = ApiService.pushPunch(punch);
            if (result.success) {
                db.markPunchSynced(punch.id);
                db.removeSyncQueueItem(item.id);
                cleanupSyncedSnapshotLater(punch.snapImagePath);
                AppLogger.i(TAG, "Punch synced: " + punch.clientRecordId);
                InteractionLogger.logBusiness(
                        InteractionLogger.GROUP_PUNCH,
                        "打卡记录上传成功",
                        buildPunchSyncLogDetail(
                                punch,
                                item.retryCount,
                                "server_record_id=" + (result.data == null ? "" : safeString(result.data.recordId))
                        )
                );
            } else {
                db.incrementSyncRetry(item.id);
                AppLogger.w(TAG, "Punch sync failed: " + result.message);
                InteractionLogger.logBusinessFailure(
                        InteractionLogger.GROUP_PUNCH,
                        "打卡记录上传失败",
                        buildPunchSyncLogDetail(
                                punch,
                                item.retryCount + 1,
                                "code=" + result.code + "\nreason=" + safeString(result.message)
                        )
                );
                if (PunchSyncPolicy.shouldStopAfterFailure(result.code)) {
                    AppLogger.w(TAG, "Stop punch batch after transport failure");
                    break;
                }
            }
        }
    }

    private void cleanupSyncedSnapshotLater(String snapshotPath) {
        if (isBlank(snapshotPath)) {
            return;
        }
        snapshotCleanupExecutor.schedule(
                () -> PunchSnapshotHelper.deleteSnapshot(snapshotPath),
                SYNCED_SNAPSHOT_CLEANUP_DELAY_MS,
                TimeUnit.MILLISECONDS
        );
    }

    private String buildPunchSyncLogDetail(PunchRecord punch, int retryCount, String extra) {
        if (punch == null) {
            return safeString(extra);
        }
        StringBuilder builder = new StringBuilder();
        builder.append("record_id=").append(safeString(punch.id));
        builder.append("\nclient_record_id=").append(safeString(punch.clientRecordId));
        builder.append("\nnumbers=").append(safeString(punch.empId));
        builder.append("\nteam_binding=").append(punch.teamBindingId);
        builder.append("\nline_binding_code=").append(safeString(punch.lineCode));
        builder.append("\nsnap_time=").append(punch.punchTime);
        builder.append("\npunch_type=").append(safeString(punch.punchType));
        builder.append("\nclock_index=").append(punch.clockIndex);
        builder.append("\nmatch_score=").append(punch.matchScore);
        builder.append("\nretry_count=").append(retryCount);
        builder.append("\nhas_snap_image=").append(!isBlank(punch.snapImagePath) && punch.snapImageSize > 0);
        builder.append("\nsnap_image_size=").append(punch.snapImageSize);
        if (!isBlank(extra)) {
            builder.append("\n").append(extra.trim());
        }
        return builder.toString();
    }

    private boolean syncDeviceConfig(Context context) {
        PunchApplication app = PunchApplication.get();
        if (app != null) {
            app.reportStatusEvent("\u6536\u5230\u914d\u7f6e\u53d8\u66f4\uff0c\u6b63\u5728\u540c\u6b65\u8bbe\u5907\u914d\u7f6e...", PunchApplication.STATUS_LEVEL_PROGRESS);
        }
        InteractionLogger.logBusiness(
                InteractionLogger.GROUP_DEVICE_CONFIG,
                "开始同步设备配置",
                "收到 config_changed 事件，准备拉取最新配置"
        );
        ApiResult<DeviceDto.DeviceConfigData> result = ApiService.fetchDeviceConfig();
        if (!result.success || result.data == null) {
            if (app != null) {
                app.reportStatusEvent("\u8bbe\u5907\u914d\u7f6e\u540c\u6b65\u5931\u8d25\uff0c\u7b49\u5f85\u91cd\u8bd5", PunchApplication.STATUS_LEVEL_ERROR);
            }
            AppLogger.w(TAG, "Device config sync failed: " + result.message);
            InteractionLogger.logBusinessFailure(
                    InteractionLogger.GROUP_DEVICE_CONFIG,
                    "设备配置同步失败",
                    safeString(result.message)
            );
            return false;
        }

        applyDeviceConfig(result.data, true);
        UpdateManager.startBackgroundUpdateIfEligible(context, "device_config_sync");
        if (app != null) {
            app.reportStatusEvent("\u8bbe\u5907\u914d\u7f6e\u5df2\u66f4\u65b0", PunchApplication.STATUS_LEVEL_SUCCESS);
        }
        if (FaceManager.get().isInitialized()) {
            FaceManager.get().refreshRuntimeConfig();
        }
        InteractionLogger.logBusiness(
                InteractionLogger.GROUP_DEVICE_CONFIG,
                "设备配置同步完成",
                "本地配置已更新"
        );
        return true;
    }

    private void applyDeviceConfig(DeviceDto.DeviceConfigData data, boolean preserveLocalBindings) {
        SessionManager.get().saveDeviceConfigInitialized(true);
        SessionManager.get().saveLineBindingOptions(data.lines);
        SessionManager.get().saveTeamBindingOptions(data.teams);
        boolean missingLocalLine = isBlank(SessionManager.get().getLineCode());
        boolean missingLocalTeam = SessionManager.get().getTeamBindingId() <= 0;

        if (!preserveLocalBindings || missingLocalLine) {
            DeviceDto.LineOptionData line = resolveLineBinding(data);
            if (line != null) {
                SessionManager.get().saveLineBinding(line.code, line.name);
                if (missingLocalLine) {
                    AppLogger.i(TAG, "Recovered missing line binding from device config: code="
                            + safeString(line.code) + ", name=" + safeString(line.name));
                }
            }
        }

        int effectiveTeamBindingId = SessionManager.get().getTeamBindingId();
        if (!preserveLocalBindings || missingLocalTeam) {
            DeviceDto.TeamOptionData team = resolveTeamBinding(data);
            if (team != null && team.id > 0) {
                SessionManager.get().saveTeamBindingId(team.id);
                SessionManager.get().saveTeamBindingName(team.name);
                SessionManager.get().saveCurrentTeamTimeRanges(team.timeRanges);
                effectiveTeamBindingId = team.id;
                if (missingLocalTeam) {
                    AppLogger.i(TAG, "Recovered missing team binding from device config: id="
                            + team.id + ", name=" + safeString(team.name));
                }
            }
        }
        if (!preserveLocalBindings && !isBlank(data.teamBindingName)
                && SessionManager.get().getTeamBindingName().trim().isEmpty()) {
            SessionManager.get().saveTeamBindingName(data.teamBindingName);
        }
        SessionManager.get().saveCurrentTeamTimeRanges(resolveTeamTimeRanges(
                data,
                effectiveTeamBindingId
        ));
        SessionManager.get().saveCheckCount(data.checkCount);
        if (!isBlank(data.account)) {
            SessionManager.get().saveAccount(data.account);
        }
        if (!isBlank(data.password)) {
            SessionManager.get().savePassword(data.password);
        }
        SessionManager.get().saveUpdateInfo(
                data.updateInfo.needUpdate || data.needUpdate,
                data.updateInfo.apkUrl,
                data.updateInfo.currentVersion,
                data.updateInfo.targetVersion,
                data.updateInfo.versionName
        );
        if (data.matchThreshold != null) {
            SessionManager.get().saveMatchThreshold(data.matchThreshold);
        }
        if (data.faceThreshold != null) {
            SessionManager.get().saveFaceThreshold(data.faceThreshold);
        }
        if (data.livenessCheck != null) {
            SessionManager.get().saveLivenessCheck(data.livenessCheck);
        }
        if (data.maskDetect != null) {
            SessionManager.get().saveMaskDetectEnabled(data.maskDetect);
        }
        if (data.timeoutSeconds != null) {
            SessionManager.get().saveRecognitionTimeoutSeconds(data.timeoutSeconds);
        }
        if (!isBlank(data.recognitionDistanceMode)) {
            SessionManager.get().saveRecognitionDistanceMode(data.recognitionDistanceMode);
        }
    }

    private DeviceDto.LineOptionData resolveLineBinding(DeviceDto.DeviceConfigData data) {
        if (data == null) {
            return null;
        }
        if (!isBlank(data.lineCode) || !isBlank(data.lineName)) {
            DeviceDto.LineOptionData line = new DeviceDto.LineOptionData();
            line.code = safeString(data.lineCode).trim();
            line.name = safeString(data.lineName).trim();
            return line;
        }
        for (DeviceDto.LineOptionData line : data.lines) {
            if (line != null && (!isBlank(line.code) || !isBlank(line.name))) {
                return line;
            }
        }
        return null;
    }

    private DeviceDto.TeamOptionData resolveTeamBinding(DeviceDto.DeviceConfigData data) {
        if (data == null) {
            return null;
        }
        if (data.teamBindingId > 0) {
            for (DeviceDto.TeamOptionData team : data.teams) {
                if (team != null && team.id == data.teamBindingId) {
                    return team;
                }
            }
            DeviceDto.TeamOptionData team = new DeviceDto.TeamOptionData();
            team.id = data.teamBindingId;
            team.name = safeString(data.teamBindingName).trim();
            return team;
        }
        for (DeviceDto.TeamOptionData team : data.teams) {
            if (team != null && team.id > 0) {
                return team;
            }
        }
        return null;
    }

    private EmployeeSyncProcessingResult syncEmployeesInternal(Context context) {
        PunchApplication app = PunchApplication.get();
        if (app != null) {
            app.beginPunchDataPreparation("正在同步员工数据...");
        }
        InteractionLogger.logBusiness(
                InteractionLogger.GROUP_EMPLOYEE_SYNC,
                "开始同步员工列表",
                "准备模式：仅构建本地人脸数据");
        DatabaseHelper db = DatabaseHelper.get(context);
        int page = 1;

        while (true) {
            ApiResult<EmployeeSyncData> result = ApiService.syncEmployees(page);
            if (!result.success || result.data == null) {
                if (app != null) {
                    app.markPunchRecognitionFailed("员工同步失败，等待重试");
                }
                AppLogger.w(TAG, "Employee sync failed: " + result.message);
                InteractionLogger.logBusinessFailure(
                        InteractionLogger.GROUP_EMPLOYEE_SYNC,
                        "员工同步失败",
                        "page=" + page + "\nreason=" + safeString(result.message));
                return EmployeeSyncProcessingResult.failure(
                        FAILURE_MSG_EMPLOYEE_SYNC_FAILED,
                        new ArrayList<>());
            }

            EmployeeSyncData data = result.data;
            for (EmployeeSyncData.ChangeItem changeItem : data.changeItems) {
                if (changeItem == null || isBlank(changeItem.numbers)) {
                    continue;
                }
                if ("delete".equalsIgnoreCase(changeItem.opType)) {
                    if (!applyDeleteChange(db, changeItem)) {
                        continue;
                    }
                    if (FaceManager.get().isInitialized()) {
                        FaceManager.get().removeFace(changeItem.numbers);
                    }
                    db.updateFaceRegistration(changeItem.numbers, null, false);
                    continue;
                }

                Employee incoming = changeItem.employee;
                if (incoming == null) {
                    continue;
                }
                Employee existing = db.getEmployee(incoming.id);
                if (isStaleEmployeeChange(existing, changeItem.opTime)) {
                    continue;
                }

                Employee merged = mergeEmployee(existing, incoming);
                db.upsertEmployee(merged);
                if (!isFaceEnabled(merged)) {
                    if (FaceManager.get().isInitialized()) {
                        FaceManager.get().removeFace(merged.id);
                    }
                    db.updateFaceRegistration(merged.id, null, false);
                }
            }

            if (!data.hasMore) {
                if (data.serverTime > 0) {
                    SessionManager.get().saveLastServerTime(data.serverTime);
                }
                InteractionLogger.logBusiness(
                        InteractionLogger.GROUP_EMPLOYEE_SYNC,
                        "员工列表拉取完成",
                        "最后页 page=" + page + "\n变更数 " + data.changeItems.size());
                break;
            }
            page += 1;
        }

        if (!FaceManager.get().isInitialized()) {
            if (app != null) {
                app.markPunchRecognitionFailed("人脸引擎未就绪，无法重建人脸库");
            }
            AppLogger.w(TAG, "Employee sync applied but face SDK is not ready");
            InteractionLogger.logBusinessFailure(
                    InteractionLogger.GROUP_EMPLOYEE_SYNC,
                    "员工数据已入库，但人脸引擎未就绪",
                    FAILURE_MSG_FACE_SDK_NOT_READY);
            return EmployeeSyncProcessingResult.failure(
                    FAILURE_MSG_FACE_SDK_NOT_READY,
                    new ArrayList<>());
        }

        if (app != null) {
            app.updatePunchDataPreparationStatus("正在下载并校验人脸图片...");
        }
        InteractionLogger.logBusiness(
                InteractionLogger.GROUP_EMPLOYEE_SYNC,
                "开始下载并校验人脸图片",
                "处理当前全部待注册人员");

        FaceRegistrationOutcome registrationOutcome = waitForFaceRegistration(context);
        if (!rebuildFinalFaceLibrary(context, app)) {
            return EmployeeSyncProcessingResult.failure(
                    STATUS_MSG_FACE_LIBRARY_REBUILD_FAILED,
                    new ArrayList<>());
        }
        boolean ready = registrationOutcome.isUsable() || FaceManager.get().getLoadedFaceCount() > 0;
        if (app != null) {
            publishPreparationOutcome(app, registrationOutcome);
        }
        InteractionLogger.logBusiness(
                InteractionLogger.GROUP_EMPLOYEE_SYNC,
                "准备模式员工同步完成",
                "可用状态 " + ready + "\n成功 " + registrationOutcome.succeeded + "\n失败 " + registrationOutcome.failed);
        return EmployeeSyncProcessingResult.success(ready, new ArrayList<>());
    }

    private Employee mergeEmployee(Employee existing, Employee incoming) {
        if (existing == null) {
            incoming.faceStatus = isBlank(incoming.faceStatus) ? "enabled" : incoming.faceStatus;
            incoming.status = isBlank(incoming.status) ? Constants.STATUS_NORMAL : incoming.status;
            incoming.isDeleted = 0;
            return incoming;
        }

        boolean faceUrlChanged = !safeString(existing.faceImageUrl).equals(safeString(incoming.faceImageUrl));
        boolean faceShaChanged = !isBlank(incoming.faceImageSha256)
                && !safeString(existing.faceImageSha256).equals(safeString(incoming.faceImageSha256));
        boolean faceVersionChanged = incoming.faceVersion > 0 && existing.faceVersion != incoming.faceVersion;
        String resolvedFaceStatus = isBlank(incoming.faceStatus)
                ? safeString(existing.faceStatus)
                : incoming.faceStatus;
        boolean faceStatusChanged = !safeString(existing.faceStatus).equals(safeString(resolvedFaceStatus));
        boolean reactivated = existing.isDeleted != 0;
        boolean faceChanged = faceUrlChanged || faceShaChanged || faceVersionChanged || faceStatusChanged
                || reactivated;
        incoming.name = isBlank(incoming.name) ? existing.name : incoming.name;
        incoming.dept = isBlank(incoming.dept) ? existing.dept : incoming.dept;
        incoming.faceImageSha256 = resolveIncomingFaceSha(existing, incoming, faceUrlChanged);
        incoming.faceVersion = resolveIncomingFaceVersion(existing, incoming, faceUrlChanged);
        incoming.faceStatus = isBlank(resolvedFaceStatus) ? "enabled" : resolvedFaceStatus;
        incoming.localFaceId = faceChanged ? "" : existing.localFaceId;
        incoming.faceRegistered = faceChanged ? 0 : existing.faceRegistered;
        incoming.assignedLineCode = existing.assignedLineCode;
        incoming.assignedLineName = existing.assignedLineName;
        incoming.status = isBlank(incoming.status) ? safeString(existing.status) : incoming.status;
        incoming.syncVersion = existing.syncVersion;
        incoming.isDeleted = 0;
        if (incoming.updatedAt <= 0) {
            incoming.updatedAt = existing.updatedAt;
        }
        return incoming;
    }

    private String resolveIncomingFaceSha(Employee existing, Employee incoming, boolean faceUrlChanged) {
        if (!isBlank(incoming.faceImageSha256)) {
            return incoming.faceImageSha256;
        }
        return faceUrlChanged ? "" : existing.faceImageSha256;
    }

    private int resolveIncomingFaceVersion(Employee existing, Employee incoming, boolean faceUrlChanged) {
        if (incoming.faceVersion > 0) {
            return incoming.faceVersion;
        }
        return faceUrlChanged ? 0 : existing.faceVersion;
    }

    private boolean applyDeleteChange(DatabaseHelper db, EmployeeSyncData.ChangeItem changeItem) {
        Employee existing = db.getEmployee(changeItem.numbers);
        if (isStaleEmployeeChange(existing, changeItem.opTime)) {
            return false;
        }
        if (existing == null) {
            return false;
        }
        db.markEmployeeDeleted(changeItem.numbers, changeItem.opTime);
        return true;
    }

    private FacePreparationOutcome waitForFacePreparation(
            Context context,
            List<Employee> employees,
            FaceRegistrationManager.PreparedProgressCallback progressCallback) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<List<FaceRegistrationManager.PreparedFaceResult>> holder =
                new AtomicReference<>(new ArrayList<>());
        FaceRegistrationManager.get().prepareEmployeesForPersistence(
                context,
                employees,
                progressCallback,
                results -> {
                    holder.set(results == null ? new ArrayList<>() : results);
                    latch.countDown();
                });
        try {
            if (!latch.await(FACE_REGISTRATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return FacePreparationOutcome.timeout(holder.get());
            }
            return FacePreparationOutcome.success(holder.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return FacePreparationOutcome.timeout(holder.get());
        }
    }

    private FaceRegistrationOutcome waitForFaceRegistration(Context context) {
        return waitForFaceRegistration(context, null, false);
    }

    private boolean rebuildFinalFaceLibrary(Context context, PunchApplication app) {
        if (app != null) {
            app.updatePunchDataPreparationStatus("\u6b63\u5728\u91cd\u5efa\u4eba\u8138\u5e93...");
        }
        boolean success = FaceManager.get().rebuildFaceLibrarySync(context);
        if (!success) {
            if (app != null) {
                app.markPunchRecognitionFailed(STATUS_MSG_FACE_LIBRARY_REBUILD_FAILED);
            }
            InteractionLogger.logBusinessFailure(
                    InteractionLogger.GROUP_EMPLOYEE_SYNC,
                    "人脸库最终重建失败",
                    STATUS_MSG_FACE_LIBRARY_REBUILD_FAILED
            );
        }
        return success;
    }

    private FaceRegistrationOutcome waitForFaceRegistration(Context context,
                                                            List<Employee> employees,
                                                            boolean addToRuntimeLibrary) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<List<FaceRegistrationManager.RegistrationResult>> holder =
                new AtomicReference<>(new ArrayList<>());

        FaceRegistrationManager.DetailedCallback callback = results -> {
            holder.set(results == null ? new ArrayList<>() : new ArrayList<>(results));
            latch.countDown();
        };
        List<Employee> targets = employees == null
                ? DatabaseHelper.get(context).getUnregisteredFaces()
                : employees;
        if (addToRuntimeLibrary) {
            FaceRegistrationManager.get().registerEmployees(context, targets, callback);
        } else {
            FaceRegistrationManager.get().validateEmployeesForRebuild(context, targets, callback);
        }

        try {
            if (!latch.await(FACE_REGISTRATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                AppLogger.w(TAG, "Face registration timed out");
                InteractionLogger.logBusinessFailure(
                        InteractionLogger.GROUP_EMPLOYEE_SYNC,
                        "等待人脸注册超时",
                        "超时时间 " + FACE_REGISTRATION_TIMEOUT_SECONDS + " 秒"
                );
                return FaceRegistrationOutcome.timeout(holder.get());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            InteractionLogger.logBusinessFailure(
                    InteractionLogger.GROUP_EMPLOYEE_SYNC,
                    "等待人脸注册被中断",
                    safeString(e.getMessage())
            );
            return FaceRegistrationOutcome.failure(holder.get());
        }

        int ok = 0;
        int fail = 0;
        for (FaceRegistrationManager.RegistrationResult result : holder.get()) {
            if (result != null && result.success) {
                ok++;
            } else if (result != null) {
                fail++;
            }
        }

        AppLogger.i(TAG, "Face registration: ok=" + ok + " fail=" + fail);
        String detail = buildFaceRegistrationDetail(holder.get(), ok, fail);
        if (fail > 0) {
            InteractionLogger.logBusinessFailure(
                    InteractionLogger.GROUP_EMPLOYEE_SYNC,
                    "人脸注册结果",
                    detail
            );
        } else {
            InteractionLogger.logBusiness(
                    InteractionLogger.GROUP_EMPLOYEE_SYNC,
                    "人脸注册结果",
                    detail
            );
        }
        return FaceRegistrationOutcome.success(holder.get(), ok, fail);
    }

    private String buildFaceRegistrationDetail(
            List<FaceRegistrationManager.RegistrationResult> results,
            int ok,
            int fail
    ) {
        StringBuilder detail = new StringBuilder();
        detail.append("成功 ").append(ok).append("\n失败 ").append(fail);
        if (results == null || fail <= 0) {
            return detail.toString();
        }
        int failedIndex = 0;
        for (FaceRegistrationManager.RegistrationResult result : results) {
            if (result == null || result.success) {
                continue;
            }
            failedIndex += 1;
            detail.append("\n失败明细 ")
                    .append(failedIndex)
                    .append(": empId=")
                    .append(safeString(result.empId))
                    .append(", reason=")
                    .append(safeString(result.failMsg));
            AppLogger.w(TAG, "Face registration failed: empId=" + safeString(result.empId)
                    + " reason=" + safeString(result.failMsg));
        }
        return detail.toString();
    }

    private List<String> resolveTeamTimeRanges(DeviceDto.DeviceConfigData data, int teamBindingId) {
        if (data == null) {
            return java.util.Collections.emptyList();
        }
        for (DeviceDto.TeamOptionData team : data.teams) {
            if (team != null && team.id == teamBindingId) {
                return team.timeRanges;
            }
        }
        return java.util.Collections.emptyList();
    }

    private boolean isFaceEnabled(Employee employee) {
        return employee != null
                && "enabled".equalsIgnoreCase(safeString(employee.faceStatus).trim());
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean isStaleEmployeeChange(Employee existing, long incomingUpdatedAt) {
        return existing != null
                && incomingUpdatedAt > 0
                && existing.updatedAt > 0
                && existing.updatedAt > incomingUpdatedAt;
    }

    private String safeString(String value) {
        return value == null ? "" : value;
    }

    private String safeOpType(String opType) {
        return isBlank(opType) ? "sync" : opType.trim();
    }

    private String resolveEventGroup(String eventType) {
        if ("person_changed".equals(safeString(eventType))) {
            return InteractionLogger.GROUP_EMPLOYEE_SYNC;
        }
        if ("config_changed".equals(safeString(eventType))) {
            return InteractionLogger.GROUP_DEVICE_CONFIG;
        }
        return InteractionLogger.GROUP_GENERAL;
    }

    private List<EventResultDto.EmployeeResult> applyRegistrationResults(
            LinkedHashMap<String, EventResultDto.EmployeeResult> employeeResults,
            List<FaceRegistrationManager.RegistrationResult> registrationResults
    ) {
        if (employeeResults == null || employeeResults.isEmpty()) {
            return new ArrayList<>();
        }
        if (registrationResults != null) {
            for (FaceRegistrationManager.RegistrationResult registrationResult : registrationResults) {
                if (registrationResult == null || isBlank(registrationResult.empId)) {
                    continue;
                }
                EventResultDto.EmployeeResult employeeResult = employeeResults.get(registrationResult.empId);
                if (employeeResult == null || registrationResult.success) {
                    continue;
                }
                employeeResult.success = false;
                employeeResult.failMsg = safeString(registrationResult.failMsg);
            }
        }
        return new ArrayList<>(employeeResults.values());
    }

    private void markPendingRegistrationsFailed(
            LinkedHashMap<String, EventResultDto.EmployeeResult> employeeResults,
            LinkedHashMap<String, Employee> registrationTargets,
            String failMsg
    ) {
        if (employeeResults == null || employeeResults.isEmpty()
                || registrationTargets == null || registrationTargets.isEmpty()) {
            return;
        }
        for (String empId : registrationTargets.keySet()) {
            EventResultDto.EmployeeResult employeeResult = employeeResults.get(empId);
            if (employeeResult == null) {
                continue;
            }
            employeeResult.success = false;
            employeeResult.failMsg = safeString(failMsg);
        }
    }

    private void markMissingRegistrationResultsFailed(
            LinkedHashMap<String, EventResultDto.EmployeeResult> employeeResults,
            LinkedHashMap<String, Employee> registrationTargets,
            List<FaceRegistrationManager.RegistrationResult> registrationResults,
            String failMsg
    ) {
        if (employeeResults == null || employeeResults.isEmpty()
                || registrationTargets == null || registrationTargets.isEmpty()) {
            return;
        }
        Map<String, FaceRegistrationManager.RegistrationResult> reportedResults = new HashMap<>();
        if (registrationResults != null) {
            for (FaceRegistrationManager.RegistrationResult registrationResult : registrationResults) {
                if (registrationResult == null || isBlank(registrationResult.empId)) {
                    continue;
                }
                reportedResults.put(registrationResult.empId, registrationResult);
            }
        }
        for (String empId : registrationTargets.keySet()) {
            if (reportedResults.containsKey(empId)) {
                continue;
            }
            EventResultDto.EmployeeResult employeeResult = employeeResults.get(empId);
            if (employeeResult == null) {
                continue;
            }
            employeeResult.success = false;
            employeeResult.failMsg = safeString(failMsg);
        }
    }

    private List<EventResultDto.EmployeeResult> toEmployeeResultList(
            LinkedHashMap<String, EventResultDto.EmployeeResult> employeeResults
    ) {
        return employeeResults == null ? new ArrayList<>() : new ArrayList<>(employeeResults.values());
    }

    private int countSucceededEmployeeResults(List<EventResultDto.EmployeeResult> results) {
        int count = 0;
        if (results == null) {
            return count;
        }
        for (EventResultDto.EmployeeResult result : results) {
            if (result != null && result.success) {
                count += 1;
            }
        }
        return count;
    }

    private void publishPreparationOutcome(PunchApplication app, FaceRegistrationOutcome outcome) {
        if (app == null || outcome == null) {
            return;
        }
        if (!outcome.completed) {
            app.markPunchRecognitionFailed(STATUS_MSG_FACE_LIBRARY_REBUILD_FAILED);
            return;
        }
        if (outcome.failed > 0 && outcome.succeeded > 0) {
            app.markPunchRecognitionReady(STATUS_MSG_PUNCH_PARTIAL_READY);
            app.reportStatusEvent(
                    "人脸入库部分失败：成功 " + outcome.succeeded + "，失败 " + outcome.failed
                            + buildFirstFailureSuffix(outcome.results)
                            + "，稍后自动重试",
                    PunchApplication.STATUS_LEVEL_ERROR
            );
            return;
        }
        if (outcome.failed > 0) {
            if (FaceManager.get().getLoadedFaceCount() > 0) {
                app.markPunchRecognitionReady(STATUS_MSG_PUNCH_PARTIAL_READY);
                app.reportStatusEvent(
                        "人脸入库部分失败：成功 " + outcome.succeeded + "，失败 " + outcome.failed
                                + buildFirstFailureSuffix(outcome.results)
                                + "，已保留现有 " + FaceManager.get().getLoadedFaceCount() + " 个人脸，可继续打卡",
                        PunchApplication.STATUS_LEVEL_ERROR
                );
                return;
            }
            app.markPunchRecognitionFailed(STATUS_MSG_FACE_LIBRARY_REBUILD_FAILED);
            app.reportStatusEvent(
                    "人脸入库失败：成功 " + outcome.succeeded + "，失败 " + outcome.failed
                            + buildFirstFailureSuffix(outcome.results),
                    PunchApplication.STATUS_LEVEL_ERROR
            );
            return;
        }
        app.markPunchRecognitionReady(STATUS_MSG_PUNCH_READY);
    }

    private String buildFirstFailureSuffix(List<FaceRegistrationManager.RegistrationResult> results) {
        if (results == null) {
            return "";
        }
        for (FaceRegistrationManager.RegistrationResult result : results) {
            if (result == null || result.success) {
                continue;
            }
            return "，首个失败 empId=" + safeString(result.empId)
                    + "，原因=" + safeString(result.failMsg);
        }
        return "";
    }

    private interface EmployeeBatchProgressCallback {
        void onProgress(int processed, int total);
    }

    private static final class EmployeeBatchOutcome {
        final boolean durableCommitSucceeded;
        final String failureMessage;
        final List<EventResultDto.EmployeeResult> employeeResults;

        private EmployeeBatchOutcome(boolean durableCommitSucceeded,
                                     String failureMessage,
                                     List<EventResultDto.EmployeeResult> employeeResults) {
            this.durableCommitSucceeded = durableCommitSucceeded;
            this.failureMessage = failureMessage;
            this.employeeResults = employeeResults == null ? new ArrayList<>() : employeeResults;
        }

        static EmployeeBatchOutcome success(List<EventResultDto.EmployeeResult> employeeResults) {
            return new EmployeeBatchOutcome(true, null, employeeResults);
        }

        static EmployeeBatchOutcome failure(String failureMessage,
                                            List<EventResultDto.EmployeeResult> employeeResults) {
            return new EmployeeBatchOutcome(false, failureMessage, employeeResults);
        }
    }

    private static final class FacePreparationOutcome {
        final boolean completed;
        final List<FaceRegistrationManager.PreparedFaceResult> results;

        private FacePreparationOutcome(boolean completed,
                                       List<FaceRegistrationManager.PreparedFaceResult> results) {
            this.completed = completed;
            this.results = results == null ? new ArrayList<>() : results;
        }

        static FacePreparationOutcome success(List<FaceRegistrationManager.PreparedFaceResult> results) {
            return new FacePreparationOutcome(true, results);
        }

        static FacePreparationOutcome timeout(List<FaceRegistrationManager.PreparedFaceResult> results) {
            return new FacePreparationOutcome(false, results);
        }
    }

    private static final class EmployeeSyncProcessingResult {
        final boolean overallSuccess;
        final boolean eventSuccess;
        final String failureMessage;
        final List<EventResultDto.EmployeeResult> employeeResults;

        private EmployeeSyncProcessingResult(boolean overallSuccess,
                                             boolean eventSuccess,
                                             String failureMessage,
                                             List<EventResultDto.EmployeeResult> employeeResults) {
            this.overallSuccess = overallSuccess;
            this.eventSuccess = eventSuccess;
            this.failureMessage = failureMessage;
            this.employeeResults = employeeResults == null ? new ArrayList<>() : employeeResults;
        }

        static EmployeeSyncProcessingResult success(boolean overallSuccess,
                                                    List<EventResultDto.EmployeeResult> employeeResults) {
            return new EmployeeSyncProcessingResult(overallSuccess, true, null, employeeResults);
        }

        static EmployeeSyncProcessingResult failure(String failureMessage,
                                                    List<EventResultDto.EmployeeResult> employeeResults) {
            return new EmployeeSyncProcessingResult(false, false, failureMessage, employeeResults);
        }
    }

    private static final class FaceRegistrationOutcome {
        final boolean completed;
        final List<FaceRegistrationManager.RegistrationResult> results;
        final int succeeded;
        final int failed;

        private FaceRegistrationOutcome(boolean completed,
                                        List<FaceRegistrationManager.RegistrationResult> results,
                                        int succeeded,
                                        int failed) {
            this.completed = completed;
            this.results = results == null ? new ArrayList<>() : results;
            this.succeeded = succeeded;
            this.failed = failed;
        }

        static FaceRegistrationOutcome success(List<FaceRegistrationManager.RegistrationResult> results,
                                               int succeeded,
                                               int failed) {
            return new FaceRegistrationOutcome(true, results, succeeded, failed);
        }

        static FaceRegistrationOutcome timeout(List<FaceRegistrationManager.RegistrationResult> results) {
            return new FaceRegistrationOutcome(false, results, 0, 0);
        }

        static FaceRegistrationOutcome failure(List<FaceRegistrationManager.RegistrationResult> results) {
            return new FaceRegistrationOutcome(false, results, 0, 0);
        }

        boolean isUsable() {
            return completed && failed == 0 || completed && succeeded > 0;
        }
    }

    private static final class EventProcessingOutcome {
        final boolean success;
        final String failureMessage;
        final List<EventResultDto.EmployeeResult> employeeResults;

        private EventProcessingOutcome(boolean success,
                                       String failureMessage,
                                       List<EventResultDto.EmployeeResult> employeeResults) {
            this.success = success;
            this.failureMessage = failureMessage;
            this.employeeResults = employeeResults == null ? new ArrayList<>() : employeeResults;
        }

        static EventProcessingOutcome success() {
            return new EventProcessingOutcome(true, null, new ArrayList<>());
        }

        static EventProcessingOutcome success(List<EventResultDto.EmployeeResult> employeeResults) {
            return new EventProcessingOutcome(true, null, employeeResults);
        }

        static EventProcessingOutcome failure(String failureMessage) {
            return new EventProcessingOutcome(false, failureMessage, new ArrayList<>());
        }

        static EventProcessingOutcome failure(String failureMessage,
                                              List<EventResultDto.EmployeeResult> employeeResults) {
            return new EventProcessingOutcome(false, failureMessage, employeeResults);
        }
    }
}

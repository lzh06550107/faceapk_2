package com.punch.app.face;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.RectF;
import android.util.Log;

import com.baidu.idl.main.facesdk.FaceInfo;
import com.baidu.idl.main.facesdk.FaceLive;
import com.baidu.idl.main.facesdk.FaceMouthMask;
import com.baidu.idl.main.facesdk.FaceSearch;
import com.baidu.idl.main.facesdk.model.BDFaceDetectListConf;
import com.baidu.idl.main.facesdk.model.BDFaceImageInstance;
import com.baidu.idl.main.facesdk.model.BDFaceSDKCommon;
import com.baidu.idl.main.facesdk.model.BDFaceSDKConfig;
import com.baidu.idl.main.facesdk.model.Feature;
import com.punch.app.R;
import com.punch.app.db.DatabaseHelper;
import com.punch.app.model.Employee;
import com.punch.app.utils.AppLogger;
import com.punch.app.utils.SessionManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FaceManager {
    private static final String TAG = "FaceManager";
    private static final float MASK_SCORE_THRESHOLD = 0.5f;
    private static final float SAFE_MATCH_THRESHOLD_FLOOR = 0.75f;
    private static final float SAFE_MATCH_SCORE_GAP = 0.03f;
    private static final int FACE_FEATURE_SCHEMA_VERSION = 1;
    public static final String ERROR_INVALID_FACE_IMAGE = "\u4eba\u8138\u56fe\u7247\u4e0d\u5408\u683c";
    public static final String ERROR_NO_FACE_DETECTED = "\u672a\u68c0\u6d4b\u5230\u4eba\u8138";
    public static final String ERROR_LIVENESS_MODEL_NOT_READY = "\u6d3b\u4f53\u68c0\u6d4b\u6a21\u578b\u672a\u5c31\u7eea";
    public static final String ERROR_LIVENESS_CHECK_FAILED = "\u6d3b\u4f53\u68c0\u6d4b\u5931\u8d25";
    public static final String ERROR_FACE_SDK_NOT_READY = "\u4eba\u8138\u5f15\u64ce\u672a\u5c31\u7eea";
    public static final String ERROR_FACE_SEARCH_NOT_READY = "\u4eba\u8138\u641c\u7d22\u6a21\u5757\u672a\u5c31\u7eea";
    public static final String ERROR_MASK_DETECTED = "\u68c0\u6d4b\u5230\u53e3\u7f69\uff0c\u8bf7\u6458\u4e0b\u540e\u91cd\u8bd5";
    public static final String ERROR_NO_MATCHING_FACE = "\u672a\u627e\u5230\u5339\u914d\u4eba\u5458";
    public static final String ERROR_MATCH_AMBIGUOUS = "\u8bc6\u522b\u7ed3\u679c\u4e0d\u591f\u660e\u786e\uff0c\u8bf7\u91cd\u8bd5";
    public static final String ERROR_FACE_ID_MAPPING_MISSING = "\u4eba\u8138\u7d22\u5f15\u6620\u5c04\u4e22\u5931";
    public static final String ERROR_FACE_SEARCH_REGISTER_FAILED = "\u4eba\u8138\u7279\u5f81\u6ce8\u518c\u5230\u641c\u7d22\u5e93\u5931\u8d25";
    public static final String ERROR_FACE_LIBRARY_NOT_READY = "\u4eba\u8138\u5e93\u672a\u5c31\u7eea\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5";

    public enum FaceLibraryState {
        NOT_READY,
        REBUILDING,
        READY,
        REBUILD_FAILED
    }

    public enum InitState {
        UNINITIALIZED,
        INITIALIZING,
        READY,
        FAILED
    }

    private static final class Holder {
        private static final FaceManager INSTANCE = new FaceManager();
    }

    public static FaceManager get() {
        return Holder.INSTANCE;
    }

    private volatile Context appContext;
    private volatile InitState initState = InitState.UNINITIALIZED;

    private final Object initLock = new Object();
    private final List<InitCallback> pendingInitCallbacks = new ArrayList<>();
    private final Map<String, Integer> empToIntId = new HashMap<>();
    private final Map<Integer, String> intToEmpId = new HashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Object faceLibraryLock = new Object();
    private volatile int loadedFaceCount;
    private volatile FaceLibraryState faceLibraryState = FaceLibraryState.NOT_READY;

    public void init(Context context, String licenseFileName, final InitCallback callback) {
        Context candidateContext = context == null ? null : context.getApplicationContext();
        if (candidateContext == null) {
            notifyInitError(callback, -1, "Application context is null");
            return;
        }

        boolean alreadyReady = false;
        synchronized (initLock) {
            appContext = candidateContext;
            if (initState == InitState.READY) {
                alreadyReady = true;
            } else {
                if (callback != null) {
                    pendingInitCallbacks.add(callback);
                }
                if (initState == InitState.INITIALIZING) {
                    return;
                }
                initState = InitState.INITIALIZING;
                faceLibraryState = FaceLibraryState.NOT_READY;
            }
        }

        if (alreadyReady) {
            notifyInitSuccess(callback);
            return;
        }

        try {
            FaceSDKManager.getInstance().initModel(appContext, buildSdkConfig(), new SdkInitListener() {
                @Override
                public void initStart() {
                    Log.i(TAG, "SDK init start");
                }

                @Override
                public void initLicenseSuccess() {
                    Log.i(TAG, "SDK license ok");
                }

                @Override
                public void initLicenseFail(int code, String msg) {
                    Log.e(TAG, "SDK license failed code=" + code + " msg=" + msg);
                    completeInitFailure(code, "License init failed: " + msg);
                }

                @Override
                public void initModelSuccess() {
                    Log.i(TAG, "SDK initModel success");
                    completeInitSuccess();
                }

                @Override
                public void initModelFail(int code, String msg) {
                    Log.e(TAG, "SDK model failed code=" + code + " msg=" + msg);
                    completeInitFailure(code, "Model init failed: " + msg);
                }
            });
        } catch (RuntimeException e) {
            Log.e(TAG, "SDK init threw", e);
            completeInitFailure(-1, "SDK init exception: " + e.getMessage());
        }
    }

    private void completeInitSuccess() {
        List<InitCallback> callbacks;
        synchronized (initLock) {
            if (initState != InitState.INITIALIZING) {
                Log.w(TAG, "Ignore stale SDK init success, state=" + initState);
                return;
            }
            initState = InitState.READY;
            callbacks = drainPendingInitCallbacksLocked();
        }
        for (InitCallback callback : callbacks) {
            notifyInitSuccess(callback);
        }
    }

    private void completeInitFailure(int code, String message) {
        List<InitCallback> callbacks;
        synchronized (initLock) {
            if (initState != InitState.INITIALIZING) {
                Log.w(TAG, "Ignore stale SDK init failure, state=" + initState
                        + " code=" + code + " message=" + message);
                return;
            }
            initState = InitState.FAILED;
            faceLibraryState = FaceLibraryState.NOT_READY;
            callbacks = drainPendingInitCallbacksLocked();
        }
        for (InitCallback callback : callbacks) {
            notifyInitError(callback, code, message);
        }
    }

    private List<InitCallback> drainPendingInitCallbacksLocked() {
        List<InitCallback> callbacks = new ArrayList<>(pendingInitCallbacks);
        pendingInitCallbacks.clear();
        return callbacks;
    }

    private void notifyInitSuccess(InitCallback callback) {
        if (callback == null) {
            return;
        }
        try {
            callback.onSuccess();
        } catch (RuntimeException e) {
            Log.e(TAG, "SDK init success callback failed", e);
        }
    }

    private void notifyInitError(InitCallback callback, int code, String message) {
        if (callback == null) {
            return;
        }
        try {
            callback.onError(code, message);
        } catch (RuntimeException e) {
            Log.e(TAG, "SDK init error callback failed", e);
        }
    }

    public void refreshRuntimeConfig() {
        if (!isInitialized()) {
            return;
        }
        BDFaceSDKConfig sdkConfig = buildSdkConfig();
        FaceSDKManager sdkManager = FaceSDKManager.getInstance();
        if (sdkManager.getFaceDetectPerson() != null) {
            sdkManager.getFaceDetectPerson().loadConfig(sdkConfig);
        }
        sdkManager.loadBackgroundConfig(sdkConfig);
    }

    public void rebuildFaceLibrary(Context context) {
        executor.execute(() -> rebuildFaceLibrarySync(context));
    }

    public boolean rebuildFaceLibrarySync(Context context) {
        FaceSearch faceSearch = FaceSDKManager.getInstance().getFaceSearch();
        if (!isInitialized()) {
            return failFullRebuild(faceSearch, "face SDK is not initialized");
        }
        if (faceSearch == null) {
            return failFullRebuild(null, "FaceSearch is not ready");
        }

        DatabaseHelper db = DatabaseHelper.get(context);
        List<Employee> employees = db.getAllActiveEmployees();
        Set<String> pendingRuntimeUpsertEmployeeIds = db.getPendingFaceApplyEmployeeIds(
                DatabaseHelper.FaceBatchWrite.OP_UPSERT);
        List<Employee> rebuildEmployees = new ArrayList<>();
        List<String> rebuildEmployeeIds = new ArrayList<>();
        for (Employee emp : employees) {
            boolean registeredForRuntime = emp.faceRegistered == 1 && emp.localFaceId != null;
            boolean pendingRuntimeUpsert = pendingRuntimeUpsertEmployeeIds.contains(emp.id);
            if ((registeredForRuntime || pendingRuntimeUpsert) && emp.faceImageUrl != null) {
                rebuildEmployees.add(emp);
                rebuildEmployeeIds.add(emp.id);
            }
        }

        Map<String, DatabaseHelper.FaceFeatureCacheEntry> cachedFeatures =
                db.getFaceFeaturesByEmployeeIds(rebuildEmployeeIds);
        Map<String, byte[]> preparedFeatures = new HashMap<>();
        RebuildFeatureStats stats = new RebuildFeatureStats();
        int expectedEntries = rebuildEmployees.size();
        boolean preparationFailed = false;
        for (Employee emp : rebuildEmployees) {
            byte[] feature = loadFeatureForRebuild(context, db, emp, cachedFeatures, stats);
            if (feature == null) {
                preparationFailed = true;
                AppLogger.e(TAG, "Face rebuild feature unavailable: empId=" + safeEmpId(emp.id));
                continue;
            }
            preparedFeatures.put(emp.id, feature);
        }

        if (preparationFailed || preparedFeatures.size() != expectedEntries) {
            return failFullRebuild(faceSearch,
                    "rebuild preparation incomplete expected=" + expectedEntries
                            + " prepared=" + preparedFeatures.size());
        }

        Map<String, Integer> rebuildSdkIds;
        try {
            rebuildSdkIds = db.getOrCreateFaceSdkIds(rebuildEmployeeIds);
        } catch (RuntimeException e) {
            return failFullRebuild(faceSearch, "Face SDK ID bulk allocation failed: " + e.getMessage());
        }
        if (rebuildSdkIds.size() != expectedEntries) {
            return failFullRebuild(faceSearch,
                    "Face SDK ID preparation incomplete expected=" + expectedEntries
                            + " prepared=" + rebuildSdkIds.size());
        }

        List<FaceLibraryEntry> entries = new ArrayList<>();
        for (Employee emp : rebuildEmployees) {
            Integer sdkId = rebuildSdkIds.get(emp.id);
            byte[] feature = preparedFeatures.get(emp.id);
            if (sdkId == null || sdkId <= 0 || feature == null) {
                return failFullRebuild(faceSearch,
                        "Face rebuild metadata missing: empId=" + safeEmpId(emp.id));
            }
            entries.add(new FaceLibraryEntry(emp.id, sdkId, feature));
        }
        if (entries.size() != expectedEntries) {
            return failFullRebuild(faceSearch,
                    "Face rebuild entries incomplete expected=" + expectedEntries
                            + " prepared=" + entries.size());
        }

        Map<String, Integer> newEmpToIntId = new HashMap<>();
        Map<Integer, String> newIntToEmpId = new HashMap<>();
        synchronized (faceLibraryLock) {
            faceLibraryState = FaceLibraryState.REBUILDING;

            int clearResult = faceSearch.featureClear();
            if (clearResult != 0) {
                return failFullRebuildLocked(faceSearch,
                        "FaceSearch.featureClear failed code=" + clearResult);
            }

            for (FaceLibraryEntry entry : entries) {
                int pushResult = faceSearch.pushPersonById(entry.sdkId, entry.feature);
                if (pushResult != 0) {
                    return failFullRebuildLocked(faceSearch,
                            "FaceSearch.pushPersonById failed empId=" + safeEmpId(entry.empId)
                                    + " sdkId=" + entry.sdkId + " code=" + pushResult);
                }
                newEmpToIntId.put(entry.empId, entry.sdkId);
                newIntToEmpId.put(entry.sdkId, entry.empId);
            }

            int nativeSize = faceSearch.getSize();
            if (nativeSize != entries.size()) {
                return failFullRebuildLocked(faceSearch,
                        "FaceSearch size mismatch expected=" + entries.size()
                                + " actual=" + nativeSize);
            }

            empToIntId.clear();
            empToIntId.putAll(newEmpToIntId);
            intToEmpId.clear();
            intToEmpId.putAll(newIntToEmpId);
            loadedFaceCount = entries.size();
            faceLibraryState = FaceLibraryState.READY;
            Log.i(TAG, "rebuildFaceLibrary: " + entries.size() + " faces loaded"
                    + " cached=" + stats.cached
                    + " regenerated=" + stats.regenerated
                    + " nativeSize=" + nativeSize);
        }
        return true;
    }

    public RegisterResult registerFace(Context context, String empId, String imageFilePath) {
        return registerFaceInternal(context, empId, imageFilePath, true);
    }

    public RegisterResult validateFaceImage(Context context, String empId, String imageFilePath) {
        return registerFaceInternal(context, empId, imageFilePath, false);
    }

    private RegisterResult registerFaceInternal(Context context,
                                                  String empId,
                                                  String imageFilePath,
                                                  boolean addToRuntimeLibrary) {
        FeatureResult prepared = extractFeatureForPersistence(empId, imageFilePath);
        if (!prepared.success) {
            return RegisterResult.fail(prepared.errorMsg);
        }

        persistExtractedFeature(context, empId, prepared.feature);
        if (!addToRuntimeLibrary) {
            AppLogger.i(TAG, "Face image validated: empId=" + empId);
            return RegisterResult.ok("FACE_" + empId);
        }
        return applyStoredFeature(context, empId, prepared.feature);
    }

    public FeatureResult extractFeatureForPersistence(String empId, String imageFilePath) {
        if (!isInitialized()) {
            return FeatureResult.fail(ERROR_FACE_SDK_NOT_READY);
        }
        byte[] feature = extractFeatureFromFile(imageFilePath, empId);
        if (feature == null || feature.length != 512) {
            return FeatureResult.fail(ERROR_INVALID_FACE_IMAGE);
        }
        return FeatureResult.ok(feature);
    }

    public int getFeatureSchemaVersion() {
        return FACE_FEATURE_SCHEMA_VERSION;
    }

    public RegisterResult applyStoredFeature(Context context, String empId, byte[] feature) {
        if (!isInitialized()) {
            return RegisterResult.fail(ERROR_FACE_SDK_NOT_READY);
        }
        if (feature == null || feature.length != 512) {
            return RegisterResult.fail(ERROR_INVALID_FACE_IMAGE);
        }
        FaceSearch faceSearch = FaceSDKManager.getInstance().getFaceSearch();
        if (faceSearch == null) {
            return RegisterResult.fail(ERROR_FACE_SEARCH_NOT_READY);
        }
        if (faceLibraryState != FaceLibraryState.READY) {
            return RegisterResult.fail(ERROR_FACE_LIBRARY_NOT_READY);
        }

        final int intId;
        try {
            intId = DatabaseHelper.get(context).getOrCreateFaceSdkId(empId);
        } catch (RuntimeException e) {
            AppLogger.e(TAG, "Face SDK ID allocation failed: empId="
                    + safeEmpId(empId) + " error=" + e.getMessage());
            return RegisterResult.fail(ERROR_FACE_ID_MAPPING_MISSING);
        }

        synchronized (faceLibraryLock) {
            if (faceLibraryState != FaceLibraryState.READY) {
                return RegisterResult.fail(ERROR_FACE_LIBRARY_NOT_READY);
            }
            Integer loadedIntId = empToIntId.get(empId);
            boolean wasLoaded = loadedIntId != null;
            if (wasLoaded) {
                int deleteResult = faceSearch.delPersonById(loadedIntId);
                if (deleteResult != 0) {
                    AppLogger.e(TAG, "Face search delete-before-replace failed: empId="
                            + safeEmpId(empId) + " intId=" + loadedIntId + " code=" + deleteResult);
                    return RegisterResult.fail(ERROR_FACE_SEARCH_REGISTER_FAILED);
                }
            }

            int pushResult = faceSearch.pushPersonById(intId, feature);
            if (pushResult != 0) {
                if (wasLoaded) {
                    empToIntId.remove(empId);
                    intToEmpId.remove(loadedIntId);
                    loadedFaceCount = Math.max(0, loadedFaceCount - 1);
                }
                AppLogger.e(TAG, "Face search registration failed: empId="
                        + safeEmpId(empId) + " intId=" + intId + " code=" + pushResult);
                return RegisterResult.fail(ERROR_FACE_SEARCH_REGISTER_FAILED);
            }

            if (wasLoaded && loadedIntId != intId) {
                intToEmpId.remove(loadedIntId);
            }
            empToIntId.put(empId, intId);
            intToEmpId.put(intId, empId);
            if (!wasLoaded) {
                loadedFaceCount += 1;
            }
            AppLogger.i(TAG, "Stored face applied: empId=" + empId + " intId=" + intId);
        }
        return RegisterResult.ok("FACE_" + empId);
    }

    private boolean failFullRebuild(FaceSearch faceSearch, String reason) {
        synchronized (faceLibraryLock) {
            return failFullRebuildLocked(faceSearch, reason);
        }
    }

    private boolean failFullRebuildLocked(FaceSearch faceSearch, String reason) {
        faceLibraryState = FaceLibraryState.REBUILD_FAILED;
        empToIntId.clear();
        intToEmpId.clear();
        loadedFaceCount = 0;

        int cleanupResult = 0;
        if (faceSearch != null) {
            try {
                cleanupResult = faceSearch.featureClear();
            } catch (RuntimeException e) {
                cleanupResult = Integer.MIN_VALUE;
                AppLogger.e(TAG, "Face rebuild cleanup threw: " + e.getMessage());
            }
        }
        AppLogger.e(TAG, "Face rebuild failed: " + reason
                + " cleanupResult=" + cleanupResult);
        return false;
    }

    private byte[] loadFeatureForRebuild(Context context,
                                         DatabaseHelper db,
                                         Employee emp,
                                         Map<String, DatabaseHelper.FaceFeatureCacheEntry> cachedFeatures,
                                         RebuildFeatureStats stats) {
        DatabaseHelper.FaceFeatureCacheEntry cached = cachedFeatures.get(emp.id);
        if (cached != null
                && cached.faceVersion == emp.faceVersion
                && cached.featureSchemaVersion == FACE_FEATURE_SCHEMA_VERSION
                && cached.feature != null && cached.feature.length == 512
                && normalizeFaceSha256(cached.imageSha256)
                .equals(normalizeFaceSha256(emp.faceImageSha256))) {
            stats.cached += 1;
            return cached.feature;
        }

        String imagePath = FaceFileManager.getFaceImagePath(context, emp.id);
        byte[] feature = extractFeatureFromFile(imagePath, emp.id);
        if (feature == null) {
            return null;
        }

        stats.regenerated += 1;
        boolean persisted = db.upsertFaceFeature(
                emp.id,
                emp.faceVersion,
                emp.faceImageSha256,
                FACE_FEATURE_SCHEMA_VERSION,
                feature);
        if (!persisted) {
            AppLogger.w(TAG, "Face feature cache refresh failed during rebuild: empId="
                    + safeEmpId(emp.id));
        }
        return feature;
    }

    private void persistExtractedFeature(Context context, String empId, byte[] feature) {
        DatabaseHelper db = DatabaseHelper.get(context);
        Employee employee = db.getEmployee(empId);
        if (employee == null) {
            AppLogger.w(TAG, "Face feature cache skipped because employee is missing: empId="
                    + safeEmpId(empId));
            return;
        }
        boolean persisted = db.upsertFaceFeature(
                empId,
                employee.faceVersion,
                employee.faceImageSha256,
                FACE_FEATURE_SCHEMA_VERSION,
                feature);
        if (!persisted) {
            AppLogger.w(TAG, "Face feature cache write failed: empId=" + safeEmpId(empId));
        }
    }

    public boolean removeFace(String empId) {
        synchronized (faceLibraryLock) {
            Integer intId = empToIntId.get(empId);
            if (intId == null) {
                return true;
            }
            FaceSearch faceSearch = FaceSDKManager.getInstance().getFaceSearch();
            if (faceSearch == null) {
                return false;
            }
            int deleteResult = faceSearch.delPersonById(intId);
            if (deleteResult != 0) {
                AppLogger.e(TAG, "Face search remove failed: empId=" + safeEmpId(empId)
                        + " intId=" + intId + " code=" + deleteResult);
                return false;
            }
            empToIntId.remove(empId);
            intToEmpId.remove(intId);
            loadedFaceCount = Math.max(0, loadedFaceCount - 1);
            return true;
        }
    }

    public RecognizeResult recognizeFromBitmap(Bitmap bmp) {
        if (!isInitialized()) {
            return RecognizeResult.fail(ERROR_FACE_SDK_NOT_READY);
        }

        FaceSearch faceSearch = FaceSDKManager.getInstance().getFaceSearch();
        if (faceSearch == null) {
            return RecognizeResult.fail(ERROR_FACE_SEARCH_NOT_READY);
        }

        BDFaceImageInstance inst = new BDFaceImageInstance(bmp);
        try {
            FaceInfo[] faceInfos = FaceSDKManager.getInstance()
                    .getFaceDetectPerson()
                    .detect(BDFaceSDKCommon.DetectType.DETECT_VIS, inst);
            if (faceInfos == null || faceInfos.length == 0) {
                return RecognizeResult.fail(ERROR_NO_FACE_DETECTED);
            }

            RecognizeResult checkResult = runPreChecks(inst, faceInfos[0]);
            if (checkResult != null) {
                return checkResult;
            }

            byte[] feature = new byte[512];
            FaceSDKManager.getInstance().getFacePersonFeature()
                    .feature(BDFaceSDKCommon.FeatureType.BDFACE_FEATURE_TYPE_LIVE_PHOTO,
                            inst, faceInfos[0].landmarks, feature);
            return doSearch(feature, faceInfos[0]);
        } finally {
            inst.destory();
        }
    }

    public RecognizeResult recognizeFromNv21(byte[] nv21, int width, int height, int angle, int mirror) {
        if (!isInitialized()) {
            return RecognizeResult.fail(ERROR_FACE_SDK_NOT_READY);
        }

        FaceSearch faceSearch = FaceSDKManager.getInstance().getFaceSearch();
        if (faceSearch == null) {
            return RecognizeResult.fail(ERROR_FACE_SEARCH_NOT_READY);
        }

        BDFaceImageInstance inst = new BDFaceImageInstance(
                nv21,
                height,
                width,
                BDFaceSDKCommon.BDFaceImageType.BDFACE_IMAGE_TYPE_YUV_NV21,
                angle,
                mirror
        );
        try {
            BDFaceDetectListConf conf = new BDFaceDetectListConf();
            conf.usingDetect = true;
            FaceInfo[] faceInfos = FaceSDKManager.getInstance()
                    .getFaceDetectPerson()
                    .detect(BDFaceSDKCommon.DetectType.DETECT_VIS,
                            BDFaceSDKCommon.AlignType.BDFACE_ALIGN_TYPE_RGB_ACCURATE,
                            inst, null, conf);
            if (faceInfos == null || faceInfos.length == 0) {
                return RecognizeResult.fail(ERROR_NO_FACE_DETECTED);
            }

            RecognizeResult checkResult = runPreChecks(inst, faceInfos[0]);
            if (checkResult != null) {
                return checkResult;
            }

            byte[] feature = new byte[512];
            FaceSDKManager.getInstance().getFacePersonFeature()
                    .feature(BDFaceSDKCommon.FeatureType.BDFACE_FEATURE_TYPE_LIVE_PHOTO,
                            inst, faceInfos[0].landmarks, feature);
            return doSearch(feature, faceInfos[0]);
        } finally {
            inst.destory();
        }
    }

    private RecognizeResult runPreChecks(BDFaceImageInstance inst, FaceInfo faceInfo) {
        if (appContext != null
                && appContext.getResources().getBoolean(R.bool.face_punch_stress_bypass_prechecks)) {
            return null;
        }
        SessionManager session = SessionManager.get();

        if (session.isLivenessCheck()) {
            FaceLive faceLive = FaceSDKManager.getInstance().getFaceLive();
            if (faceLive == null) {
                return RecognizeResult.fail(ERROR_LIVENESS_MODEL_NOT_READY);
            }
            float threshold = session.getLivenessThreshold();
            float score = faceLive.silentLive(
                    BDFaceSDKCommon.LiveType.BDFACE_SILENT_LIVE_TYPE_RGB,
                    inst,
                    faceInfo.landmarks
            );
            String livenessDetail = buildLivenessDetail(faceInfo, score, threshold);
            AppLogger.d(TAG, "RGB liveness check: " + livenessDetail);
            if (score < threshold) {
                return RecognizeResult.fail(ERROR_LIVENESS_CHECK_FAILED, livenessDetail);
            }
        }

        FaceMouthMask faceMouthMask = FaceSDKManager.getInstance().getFaceMouthMask();
        if (session.isMaskDetectEnabled() && faceMouthMask != null) {
            float[] maskScores = faceMouthMask.checkMask(inst, new FaceInfo[]{faceInfo});
            float maskScore = (maskScores != null && maskScores.length > 0) ? maskScores[0] : 0f;
            if (maskScore >= MASK_SCORE_THRESHOLD) {
                return RecognizeResult.fail(ERROR_MASK_DETECTED);
            }
        }

        return null;
    }

    private RecognizeResult doSearch(byte[] feature, FaceInfo faceInfo) {
        FaceSearch faceSearch = FaceSDKManager.getInstance().getFaceSearch();
        if (faceSearch == null) {
            return RecognizeResult.fail(ERROR_FACE_SEARCH_NOT_READY);
        }

        FaceLibraryState observedState = faceLibraryState;
        if (observedState != FaceLibraryState.READY) {
            return RecognizeResult.fail(
                    ERROR_FACE_LIBRARY_NOT_READY,
                    "state=" + observedState.name());
        }

        float configuredThreshold = SessionManager.get().getMatchThreshold();
        float effectiveThreshold = Math.max(configuredThreshold, SAFE_MATCH_THRESHOLD_FLOOR);

        boolean hasResults = false;
        float bestScore = 0f;
        float secondScore = 0f;
        String empId = null;

        synchronized (faceLibraryLock) {
            observedState = faceLibraryState;
            if (observedState != FaceLibraryState.READY) {
                return RecognizeResult.fail(
                        ERROR_FACE_LIBRARY_NOT_READY,
                        "state=" + observedState.name());
            }

            List<? extends Feature> results = faceSearch.search(
                    BDFaceSDKCommon.FeatureType.BDFACE_FEATURE_TYPE_LIVE_PHOTO,
                    effectiveThreshold,
                    2,
                    feature
            );
            if (results != null && !results.isEmpty()) {
                hasResults = true;
                Feature best = results.get(0);
                bestScore = best.getScore();
                if (results.size() > 1 && results.get(1) != null) {
                    secondScore = results.get(1).getScore();
                }
                empId = intToEmpId.get(best.getId());
            }
        }

        if (!hasResults) {
            AppLogger.d(TAG, "Face search miss: configuredThreshold=" + configuredThreshold
                    + ", effectiveThreshold=" + effectiveThreshold);
            return RecognizeResult.fail(ERROR_NO_MATCHING_FACE);
        }

        if (bestScore < effectiveThreshold * 100) {
            AppLogger.d(TAG, "Face search below threshold: bestScore=" + bestScore
                    + ", configuredThreshold=" + configuredThreshold
                    + ", effectiveThreshold=" + effectiveThreshold);
            return RecognizeResult.fail(ERROR_NO_MATCHING_FACE);
        }

        float scoreGap = bestScore - secondScore;
        if (secondScore > 0f && scoreGap < SAFE_MATCH_SCORE_GAP) {
            AppLogger.w(TAG, "Reject ambiguous face match: bestScore=" + bestScore
                    + ", secondScore=" + secondScore
                    + ", scoreGap=" + scoreGap
                    + ", configuredThreshold=" + configuredThreshold
                    + ", effectiveThreshold=" + effectiveThreshold);
            return RecognizeResult.fail(ERROR_MATCH_AMBIGUOUS);
        }

        if (empId == null) {
            return RecognizeResult.fail(ERROR_FACE_ID_MAPPING_MISSING);
        }
        AppLogger.d(TAG, "Face search matched: empId=" + empId
                + ", bestScore=" + bestScore
                + ", secondScore=" + secondScore
                + ", scoreGap=" + scoreGap
                + ", configuredThreshold=" + configuredThreshold
                + ", effectiveThreshold=" + effectiveThreshold);
        return RecognizeResult.ok(empId, bestScore, buildFaceBounds(faceInfo));
    }

    private RectF buildFaceBounds(FaceInfo faceInfo) {
        if (faceInfo == null || faceInfo.width <= 0 || faceInfo.height <= 0) {
            return null;
        }
        float halfWidth = faceInfo.width / 2f;
        float halfHeight = faceInfo.height / 2f;
        return new RectF(
                faceInfo.centerX - halfWidth,
                faceInfo.centerY - halfHeight,
                faceInfo.centerX + halfWidth,
                faceInfo.centerY + halfHeight
        );
    }

    private BDFaceSDKConfig buildSdkConfig() {
        BDFaceSDKConfig sdkConfig = new BDFaceSDKConfig();
        sdkConfig.minFaceSize = SessionManager.get().getMinFaceSizeForRecognitionDistance();

        float faceThreshold = SessionManager.get().getFaceThreshold();
        sdkConfig.notRGBFaceThreshold = faceThreshold;
        sdkConfig.notNIRFaceThreshold = faceThreshold;

        sdkConfig.isCropFace = true;
        sdkConfig.isAttribute = false;
        sdkConfig.isBestImage = false;
        return sdkConfig;
    }

    private String buildLivenessDetail(FaceInfo faceInfo, float score, float threshold) {
        if (faceInfo == null) {
            return "score=" + score + ", threshold=" + threshold + ", faceInfo=null";
        }
        return "score=" + score
                + ", threshold=" + threshold
                + ", faceWidth=" + faceInfo.width
                + ", faceHeight=" + faceInfo.height
                + ", yaw=" + faceInfo.yaw
                + ", roll=" + faceInfo.roll
                + ", pitch=" + faceInfo.pitch
                + ", blur=" + faceInfo.bluriness
                + ", illum=" + faceInfo.illum;
    }


    private byte[] extractFeatureFromFile(String imagePath, String empId) {
        Bitmap bmp = BitmapFactory.decodeFile(imagePath);
        if (bmp == null) {
            AppLogger.w(TAG, "Face image decode failed: empId=" + safeEmpId(empId)
                    + " path=" + imagePath);
            return null;
        }

        BDFaceImageInstance inst = new BDFaceImageInstance(bmp);
        bmp.recycle();
        try {
            byte[] feature = FaceSDKManager.getInstance().extractBackgroundFeature(inst);
            if (feature == null) {
                AppLogger.w(TAG, "Face background feature extraction failed: empId=" + safeEmpId(empId)
                        + " path=" + imagePath);
                return null;
            }
            return feature;
        } finally {
            inst.destory();
        }
    }

    private String normalizeFaceSha256(String value) {
        return value == null ? "" : value.trim();
    }

    private String safeEmpId(String empId) {
        return empId == null || empId.trim().isEmpty() ? "-" : empId.trim();
    }

    public boolean isInitialized() {
        return initState == InitState.READY;
    }

    public InitState getInitState() {
        return initState;
    }

    public boolean isFaceLibraryReady() {
        return faceLibraryState == FaceLibraryState.READY;
    }

    public FaceLibraryState getFaceLibraryState() {
        return faceLibraryState;
    }

    public int getLoadedFaceCount() {
        return loadedFaceCount;
    }

    public RuntimeSnapshot snapshotRuntime() {
        synchronized (faceLibraryLock) {
            FaceSearch faceSearch = FaceSDKManager.getInstance().getFaceSearch();
            int nativeSize = faceSearch == null ? -1 : faceSearch.getSize();
            boolean mappingConsistent = empToIntId.size() == intToEmpId.size()
                    && empToIntId.size() == loadedFaceCount;
            if (mappingConsistent) {
                for (Map.Entry<String, Integer> entry : empToIntId.entrySet()) {
                    String reverseEmpId = intToEmpId.get(entry.getValue());
                    if (!entry.getKey().equals(reverseEmpId)) {
                        mappingConsistent = false;
                        break;
                    }
                }
            }
            return new RuntimeSnapshot(
                    faceLibraryState,
                    loadedFaceCount,
                    nativeSize,
                    new HashSet<>(empToIntId.keySet()),
                    mappingConsistent);
        }
    }

    public static final class RuntimeSnapshot {
        public final FaceLibraryState state;
        public final int loadedFaceCount;
        public final int nativeSize;
        public final Set<String> employeeIds;
        public final boolean mappingConsistent;

        RuntimeSnapshot(FaceLibraryState state,
                        int loadedFaceCount,
                        int nativeSize,
                        Set<String> employeeIds,
                        boolean mappingConsistent) {
            this.state = state;
            this.loadedFaceCount = loadedFaceCount;
            this.nativeSize = nativeSize;
            this.employeeIds = employeeIds;
            this.mappingConsistent = mappingConsistent;
        }
    }

    private static final class RebuildFeatureStats {
        int cached;
        int regenerated;
    }

    private static final class FaceLibraryEntry {
        final String empId;
        final int sdkId;
        final byte[] feature;

        FaceLibraryEntry(String empId, int sdkId, byte[] feature) {
            this.empId = empId;
            this.sdkId = sdkId;
            this.feature = feature;
        }
    }

    public static class FeatureResult {
        public final boolean success;
        public final byte[] feature;
        public final String errorMsg;

        private FeatureResult(boolean success, byte[] feature, String errorMsg) {
            this.success = success;
            this.feature = feature;
            this.errorMsg = errorMsg;
        }

        public static FeatureResult ok(byte[] feature) {
            return new FeatureResult(true, feature, null);
        }

        public static FeatureResult fail(String errorMsg) {
            return new FeatureResult(false, null, errorMsg);
        }
    }

    public static class RegisterResult {
        public final boolean success;
        public final String localFaceId;
        public final String errorMsg;

        private RegisterResult(boolean success, String localFaceId, String errorMsg) {
            this.success = success;
            this.localFaceId = localFaceId;
            this.errorMsg = errorMsg;
        }

        public static RegisterResult ok(String localFaceId) {
            return new RegisterResult(true, localFaceId, null);
        }

        public static RegisterResult fail(String errorMsg) {
            return new RegisterResult(false, null, errorMsg);
        }
    }

    public static class RecognizeResult {
        public final boolean matched;
        public final String empId;
        public final float score;
        public final String errorMsg;
        public final RectF faceBounds;
        public final String debugDetail;

        private RecognizeResult(boolean matched,
                                String empId,
                                float score,
                                String errorMsg,
                                RectF faceBounds,
                                String debugDetail) {
            this.matched = matched;
            this.empId = empId;
            this.score = score;
            this.errorMsg = errorMsg;
            this.faceBounds = faceBounds;
            this.debugDetail = debugDetail == null ? "" : debugDetail;
        }

        public static RecognizeResult ok(String empId, float score) {
            return ok(empId, score, null);
        }

        public static RecognizeResult ok(String empId, float score, RectF faceBounds) {
            return new RecognizeResult(true, empId, score, null, faceBounds, "");
        }

        public static RecognizeResult fail(String errorMsg) {
            return fail(errorMsg, "");
        }

        public static RecognizeResult fail(String errorMsg, String debugDetail) {
            return new RecognizeResult(false, null, 0, errorMsg, null, debugDetail);
        }
    }

    public interface InitCallback {
        void onSuccess();

        void onError(int code, String msg);
    }
}

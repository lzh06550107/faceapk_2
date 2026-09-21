package com.punch.app.face;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.baidu.idl.main.facesdk.FaceAuth;
import com.baidu.idl.main.facesdk.FaceDetect;
import com.baidu.idl.main.facesdk.FaceFeature;
import com.baidu.idl.main.facesdk.FaceInfo;
import com.baidu.idl.main.facesdk.FaceLive;
import com.baidu.idl.main.facesdk.FaceMouthMask;
import com.baidu.idl.main.facesdk.FaceSearch;
import com.baidu.idl.main.facesdk.callback.Callback;
import com.baidu.idl.main.facesdk.model.BDFaceImageInstance;
import com.baidu.idl.main.facesdk.model.BDFaceInstance;
import com.baidu.idl.main.facesdk.model.BDFaceSDKCommon;
import com.baidu.idl.main.facesdk.model.BDFaceSDKConfig;
import com.baidu.vis.facecollect.license.AndroidLicenser;
import com.punch.app.activation.ActivationManager;
import com.punch.app.utils.Constants;
import com.punch.app.utils.SessionManager;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class FaceSDKManager {
    private static final String TAG = "FaceSDKManager";
    private static final int ALGORITHM_ID = 3;
    private static final String LICENSE_NAME = "idl-license.face-android";
    private static final String LICENSE_ZIP_NAME = "License.zip";
    private static final String LICENSE_INI_NAME = "license.ini";
    private static final String LICENSE_KEY_NAME = "license.key";

    private static final String DETECT_VIS_MODEL =
            "face-sdk-models/detect/detect_rgb-customized-pa-192.model.float32-0.0.18.1";
    private static final String ALIGN_RGB_MODEL =
            "face-sdk-models/align/align_rgb-customized-pa-80.model.float32-6.4.14.4";
    private static final String RECOGNIZE_VIS_MODEL =
            "face-sdk-models/feature/feature_live-mnasnet-pa-attention_v4.model.int8-2.0.239.1";
    private static final String RECOGNIZE_IDPHOTO_MODEL =
            "face-sdk-models/feature/feature_live-mnasnet-pa-attention_v4.model.int8-2.0.239.1";
    private static final String RECOGNIZE_NIR_MODEL =
            "face-sdk-models/feature/feature_nir-mnasnet-pa-foreign.model.int8-2.0.189.1";
    private static final String LIVE_VIS_MODEL =
            "face-sdk-models/silent_live/liveness_rgb-customized-pa-DCQsdk80.model.float32-1.1.82.1";
    private static final String LIVE_NIR_MODEL =
            "face-sdk-models/silent_live/liveness_nir-customized-pa-DCQ_80.model.float32-1.1.78.1";

    private static final String LIVE_VIS_2DMASK_MODEL =
            "face-sdk-models/silent_live/liveness_rgb-customized-pa-model_freeze_2dmask_20211210_sdk_224_epoch7.model.float32-1.1.80.1";
    private static final String LIVE_VIS_HAND_MODEL =
            "face-sdk-models/silent_live/liveness_rgb-customized-pa-hand_sdk_224.model.float32-1.1.69.1";
    private static final String LIVE_VIS_REFLECTION_MODEL =
            "face-sdk-models/silent_live/liveness_rgb-customized-pa-reflection.model.float32-1.1.81.1";
    private static final String LIVE_DEPTH_MODEL =
            "face-sdk-models/silent_live/liveness_depth-customized-pa-paddle_60.model.float32-1.1.13.2";
    private static final String MOUTH_MASK_MODEL =
            "face-sdk-models/mouth_mask/mouth_mask-customized-pa-faceocc_3classes.model.float32-1.0.9.2";

    public static volatile boolean initModelSuccess = false;

    private static final FaceSDKManager INSTANCE = new FaceSDKManager();

    private final FaceAuth faceAuth;
    private FaceDetect faceDetectPerson;
    private FaceFeature facePersonFeature;
    private FaceSearch faceSearch;
    private FaceLive faceLive;
    private FaceMouthMask faceMouthMask;
    private BDFaceInstance backgroundFaceInstance;
    private FaceDetect backgroundFaceDetect;
    private FaceFeature backgroundFaceFeature;
    private final Object backgroundFaceOperationLock = new Object();
    private boolean modelLoading;

    private FaceSDKManager() {
        faceAuth = new FaceAuth();
        faceAuth.setCoreConfigure(BDFaceSDKCommon.BDFaceCoreRunMode.BDFACE_LITE_POWER_NO_BIND, 2);
    }

    public static FaceSDKManager getInstance() {
        return INSTANCE;
    }

    public synchronized void initModel(Context context, BDFaceSDKConfig config, SdkInitListener listener) {
        if (listener != null) {
            listener.initStart();
        }

        if (isModelReady()) {
            if (listener != null) {
                listener.initLicenseSuccess();
                listener.initModelSuccess();
            }
            return;
        }

        initLicense(context.getApplicationContext(), config, listener);
    }

    private boolean isModelReady() {
        return initModelSuccess
                && faceDetectPerson != null
                && facePersonFeature != null
                && faceSearch != null
                && faceLive != null
                && faceMouthMask != null
                && backgroundFaceInstance != null
                && backgroundFaceDetect != null
                && backgroundFaceFeature != null;
    }

    private void initLicense(Context appContext, BDFaceSDKConfig config, SdkInitListener listener) {
        if (faceAuth == null) {
            handleLicenseFailure(appContext, listener, -1, "FaceAuth not initialized", false);
            return;
        }

        String activationMode = SessionManager.get().getActivationMode();
        String activationCode = SessionManager.get().getActivationCode();

        if (!Constants.ACTIVATION_MODE_OFFLINE_ZIP.equals(activationMode) && !activationCode.isEmpty()) {
            Log.i(TAG, "Using online activation");
            faceAuth.initLicenseOnLine(appContext, activationCode, new Callback() {
                @Override
                public void onResponse(int code, String response) {
                    ActivationManager.get().reportActivationResult(appContext, code, response);
                    onOnlineLicenseCallback(appContext, config, listener, code, response);
                }
            });
            return;
        }

        if (!Constants.ACTIVATION_MODE_OFFLINE_ZIP.equals(activationMode)) {
            handleLicenseFailure(appContext, listener, -1, "Activation code missing", false);
            return;
        }

        File licenseZip = ActivationManager.get().getOfflineLicenseCacheFile(appContext);
        initOfflineLicense(appContext, config, listener, licenseZip);
    }

    private void initOfflineLicense(Context appContext, BDFaceSDKConfig config,
                                    SdkInitListener listener, File sourceZip) {
        if (sourceZip == null || !sourceZip.exists()) {
            handleLicenseFailure(appContext, listener, -1,
                    "License.zip not found in app private storage", false);
            return;
        }

        OfflineLicenseData licenseData = readOfflineLicenseData(appContext, sourceZip);
        if (!licenseData.success) {
            handleLicenseFailure(appContext, listener, licenseData.code, licenseData.message, true);
            return;
        }

        AndroidLicenser licenser = AndroidLicenser.getInstance();
        AndroidLicenser.ErrorCode fileCode = licenser.authFromFile(
                appContext,
                licenseData.key,
                LICENSE_NAME,
                false,
                ALGORITHM_ID
        );
        if (fileCode == AndroidLicenser.ErrorCode.SUCCESS) {
            completeLicenseSetup(appContext, config, listener, "offline license ok");
            return;
        }

        AndroidLicenser.ErrorCode memoryCode = licenser.authFromMemory(
                appContext,
                licenseData.key,
                licenseData.values,
                LICENSE_NAME,
                ALGORITHM_ID
        );
        if (memoryCode != AndroidLicenser.ErrorCode.SUCCESS) {
            handleLicenseFailure(
                    appContext,
                    listener,
                    memoryCode.ordinal(),
                    buildOfflineLicenseMessage(licenser, memoryCode),
                    true
            );
            return;
        }

        completeLicenseSetup(appContext, config, listener, "offline license ok");
    }

    private String buildOfflineLicenseMessage(AndroidLicenser licenser, AndroidLicenser.ErrorCode errorCode) {
        String detail = licenser.getErrorMsg(ALGORITHM_ID);
        if (TextUtils.isEmpty(detail)) {
            return "offline license failed: " + errorCode.name();
        }
        return "offline license failed: " + errorCode.name() + " / " + detail;
    }

    private OfflineLicenseData readOfflineLicenseData(Context context, File sourceZip) {
        File targetDir = new File(context.getCacheDir(), "offline_license");
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            return OfflineLicenseData.fail(1001, "Failed to create offline license cache dir");
        }

        File iniFile = new File(targetDir, LICENSE_INI_NAME);
        File keyFile = new File(targetDir, LICENSE_KEY_NAME);
        deleteQuietly(iniFile);
        deleteQuietly(keyFile);

        OfflineLicenseData unzipResult = unzipLicensePackage(sourceZip, targetDir);
        if (!unzipResult.success) {
            return unzipResult;
        }

        if (!iniFile.exists() || !keyFile.exists()) {
            return OfflineLicenseData.fail(1002, "License.zip missing license.ini or license.key");
        }

        try {
            String key = readSingleLineFile(keyFile);
            String[] values = readIniLines(iniFile);
            if (TextUtils.isEmpty(key) || values[0] == null || values[1] == null) {
                return OfflineLicenseData.fail(1003, "License.zip content format invalid");
            }
            return OfflineLicenseData.ok(key, values);
        } catch (Exception e) {
            return OfflineLicenseData.fail(1004, "Failed to parse License.zip: " + e.getMessage());
        }
    }

    private OfflineLicenseData unzipLicensePackage(File zipFile, File targetDir) {
        try (FileInputStream fis = new FileInputStream(zipFile);
             ZipInputStream zis = new ZipInputStream(new BufferedInputStream(fis))) {
            ZipEntry entry;
            byte[] buffer = new byte[4096];
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zis.closeEntry();
                    continue;
                }
                String entryName = new File(entry.getName()).getName();
                if (!LICENSE_INI_NAME.equalsIgnoreCase(entryName) && !LICENSE_KEY_NAME.equalsIgnoreCase(entryName)) {
                    zis.closeEntry();
                    continue;
                }

                File outFile = new File(targetDir, entryName.toLowerCase());
                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                    int len;
                    while ((len = zis.read(buffer)) != -1) {
                        fos.write(buffer, 0, len);
                    }
                    fos.flush();
                }
                zis.closeEntry();
            }
            return OfflineLicenseData.ok(null, null);
        } catch (Exception e) {
            return OfflineLicenseData.fail(1005, "Failed to unzip License.zip: " + e.getMessage());
        }
    }

    private String readSingleLineFile(File file) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line.trim());
            }
            return builder.toString();
        }
    }

    private String[] readIniLines(File file) throws IOException {
        String[] lines = new String[2];
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            int index = 0;
            while ((line = reader.readLine()) != null && index < 2) {
                lines[index++] = line.trim();
            }
        }
        return lines;
    }

    private void deleteQuietly(File file) {
        if (file != null && file.exists()) {
            file.delete();
        }
    }

    private static final class OfflineLicenseData {
        private final boolean success;
        private final int code;
        private final String message;
        private final String key;
        private final String[] values;

        private OfflineLicenseData(boolean success, int code, String message, String key, String[] values) {
            this.success = success;
            this.code = code;
            this.message = message;
            this.key = key;
            this.values = values;
        }

        private static OfflineLicenseData ok(String key, String[] values) {
            return new OfflineLicenseData(true, 0, "", key, values);
        }

        private static OfflineLicenseData fail(int code, String message) {
            return new OfflineLicenseData(false, code, message, null, null);
        }
    }

    private void onOnlineLicenseCallback(
            Context appContext,
            BDFaceSDKConfig config,
            SdkInitListener listener,
            int code,
            String response
    ) {
        if (code != 0) {
            handleLicenseFailure(appContext, listener, code, safeMsg(response), false);
            return;
        }

        completeLicenseSetup(appContext, config, listener, safeMsg(response));
    }

    private void completeLicenseSetup(
            Context appContext,
            BDFaceSDKConfig config,
            SdkInitListener listener,
            String successMessage
    ) {
        if (faceAuth == null) {
            handleLicenseFailure(appContext, listener, -1, "FaceAuth not initialized", true);
            return;
        }
        int status = faceAuth.createInstance();
        Log.i(TAG, "license initialized, createInstance=" + status);
        if (status != 0) {
            handleLicenseFailure(
                    appContext,
                    listener,
                    status,
                    "createInstance failed: " + status,
                    true
            );
            return;
        }

        ActivationManager.get().reportActivationResult(appContext, 0, successMessage);
        if (listener != null) {
            listener.initLicenseSuccess();
        }

        synchronized (this) {
            if (modelLoading) {
                return;
            }
            modelLoading = true;
            createFaceEngines(config);
        }
        initCoreModels(appContext, listener);
    }

    private void handleLicenseFailure(
            Context appContext,
            SdkInitListener listener,
            int code,
            String message,
            boolean reportActivation
    ) {
        synchronized (this) {
            initModelSuccess = false;
            modelLoading = false;
        }
        if (reportActivation) {
            ActivationManager.get().reportActivationResult(appContext, code, message);
        }
        if (listener != null) {
            listener.initLicenseFail(code, message);
        }
    }

    private synchronized void createFaceEngines(BDFaceSDKConfig config) {
        BDFaceSDKConfig sdkConfig = config != null ? config : new BDFaceSDKConfig();

        faceDetectPerson = new FaceDetect();
        faceDetectPerson.loadConfig(sdkConfig);

        facePersonFeature = new FaceFeature();

        backgroundFaceInstance = new BDFaceInstance();
        backgroundFaceInstance.creatInstance();
        backgroundFaceDetect = new FaceDetect(backgroundFaceInstance);
        backgroundFaceDetect.loadConfig(sdkConfig);
        backgroundFaceFeature = new FaceFeature(backgroundFaceInstance);

        faceSearch = new FaceSearch();
        faceLive = new FaceLive();
        faceMouthMask = new FaceMouthMask();

        faceSearch.setMaxUpdateSize(0);
        faceSearch.setInputDBIntervalTime(0);
        faceSearch.setRegisterCompareThreshold(90f);
        faceSearch.setUpdateCompareThreshold(0.9f);
        faceSearch.setInputDBThreshold(0.92f);
    }

    private void initCoreModels(Context context, SdkInitListener listener) {
        AtomicInteger pending = new AtomicInteger(6);
        AtomicBoolean finished = new AtomicBoolean(false);

        faceDetectPerson.initModel(
                context,
                DETECT_VIS_MODEL,
                ALIGN_RGB_MODEL,
                BDFaceSDKCommon.DetectType.DETECT_VIS,
                BDFaceSDKCommon.AlignType.BDFACE_ALIGN_TYPE_RGB_ACCURATE,
                new Callback() {
                    @Override
                    public void onResponse(int code, String response) {
                        handleInitCallback("detect", code, response, pending, finished, listener);
                    }
                }
        );

        facePersonFeature.initModel(
                context,
                RECOGNIZE_IDPHOTO_MODEL,
                RECOGNIZE_VIS_MODEL,
                RECOGNIZE_NIR_MODEL,
                "",
                new Callback() {
                    @Override
                    public void onResponse(int code, String response) {
                        handleInitCallback("feature", code, response, pending, finished, listener);
                    }
                }
        );

        backgroundFaceDetect.initModel(
                context,
                DETECT_VIS_MODEL,
                ALIGN_RGB_MODEL,
                BDFaceSDKCommon.DetectType.DETECT_VIS,
                BDFaceSDKCommon.AlignType.BDFACE_ALIGN_TYPE_RGB_ACCURATE,
                new Callback() {
                    @Override
                    public void onResponse(int code, String response) {
                        handleInitCallback("backgroundDetect", code, response, pending, finished, listener);
                    }
                }
        );

        backgroundFaceFeature.initModel(
                context,
                RECOGNIZE_IDPHOTO_MODEL,
                RECOGNIZE_VIS_MODEL,
                RECOGNIZE_NIR_MODEL,
                "",
                new Callback() {
                    @Override
                    public void onResponse(int code, String response) {
                        handleInitCallback("backgroundFeature", code, response, pending, finished, listener);
                    }
                }
        );

        faceLive.initModel(
                context,
                LIVE_VIS_MODEL,
                LIVE_VIS_2DMASK_MODEL,
                LIVE_VIS_HAND_MODEL,
                LIVE_VIS_REFLECTION_MODEL,
                resolveNirLiveModelPath(),
                resolveDepthLiveModelPath(),
                new Callback() {
                    @Override
                    public void onResponse(int code, String response) {
                        handleInitCallback("live", code, response, pending, finished, listener);
                    }
                }
        );

        faceMouthMask.initModel(
                context,
                MOUTH_MASK_MODEL,
                new Callback() {
                    @Override
                    public void onResponse(int code, String response) {
                        handleInitCallback("mouthMask", code, response, pending, finished, listener);
                    }
                }
        );
    }

    private String resolveDepthLiveModelPath() {
        // Current handheld integration has no depth/structured-light camera input path,
        // so the depth liveness model should stay disabled.
        return "";
    }

    private String resolveNirLiveModelPath() {
        // Current handheld integration has no NIR camera input path,
        // so the NIR liveness model should stay disabled.
        return "";
    }

    private void handleInitCallback(
            String stage,
            int code,
            String response,
            AtomicInteger pending,
            AtomicBoolean finished,
            SdkInitListener listener
    ) {
        if (code != 0) {
            if (finished.compareAndSet(false, true)) {
                synchronized (this) {
                    modelLoading = false;
                    initModelSuccess = false;
                }
                if (listener != null) {
                    listener.initModelFail(code, stage + " init failed: " + safeMsg(response));
                }
            }
            return;
        }

        if (pending.decrementAndGet() == 0 && finished.compareAndSet(false, true)) {
            synchronized (this) {
                modelLoading = false;
                initModelSuccess = true;
            }
            if (listener != null) {
                listener.initModelSuccess();
            }
        }
    }

    private String safeMsg(String response) {
        return response == null ? "" : response;
    }

    public byte[] extractBackgroundFeature(BDFaceImageInstance imageInstance) {
        if (imageInstance == null || !initModelSuccess) {
            return null;
        }
        synchronized (backgroundFaceOperationLock) {
            if (backgroundFaceDetect == null || backgroundFaceFeature == null) {
                return null;
            }
            FaceInfo[] faceInfos = backgroundFaceDetect.detect(
                    BDFaceSDKCommon.DetectType.DETECT_VIS,
                    imageInstance
            );
            if (faceInfos == null || faceInfos.length == 0) {
                return null;
            }

            byte[] feature = new byte[512];
            float size = backgroundFaceFeature.feature(
                    BDFaceSDKCommon.FeatureType.BDFACE_FEATURE_TYPE_LIVE_PHOTO,
                    imageInstance,
                    faceInfos[0].landmarks,
                    feature
            );
            return size > 0 ? feature : null;
        }
    }

    public void loadBackgroundConfig(BDFaceSDKConfig config) {
        synchronized (backgroundFaceOperationLock) {
            if (backgroundFaceDetect != null) {
                backgroundFaceDetect.loadConfig(config != null ? config : new BDFaceSDKConfig());
            }
        }
    }

    public FaceDetect getFaceDetectPerson() {
        return faceDetectPerson;
    }

    public FaceFeature getFacePersonFeature() {
        return facePersonFeature;
    }

    public FaceSearch getFaceSearch() {
        return faceSearch;
    }

    public FaceLive getFaceLive() {
        return faceLive;
    }

    public FaceMouthMask getFaceMouthMask() {
        return faceMouthMask;
    }
}

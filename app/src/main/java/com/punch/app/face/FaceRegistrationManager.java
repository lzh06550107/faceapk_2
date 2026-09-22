package com.punch.app.face;

import android.content.Context;
import android.util.Log;

import com.punch.app.db.DatabaseHelper;
import com.punch.app.model.Employee;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FaceRegistrationManager {
    private static final String TAG = "FaceRegManager";
    private static final String FAIL_MSG_FACE_IMAGE_DOWNLOAD_FAILED = "人脸图片下载失败";
    private static final String FAIL_MSG_FACE_IMAGE_INVALID = "人脸图片不合格";
    private static final String FAIL_MSG_FACE_IMAGE_URL_EMPTY = "人脸图片URL为空";

    private static FaceRegistrationManager instance;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public static FaceRegistrationManager get() {
        if (instance == null) {
            instance = new FaceRegistrationManager();
        }
        return instance;
    }

    public void registerPending(Context ctx, Callback callback) {
        executor.execute(() -> {
            List<RegistrationResult> results = registerEmployeesInternal(
                    ctx,
                    DatabaseHelper.get(ctx).getUnregisteredFaces(),
                    true
            );
            int ok = countSucceeded(results);
            int fail = countFailed(results);
            Log.i(TAG, "registerPending done: ok=" + ok + " fail=" + fail);
            if (callback != null) {
                callback.onDone(ok, fail);
            }
        });
    }

    public void registerEmployees(Context ctx,
                                  List<Employee> employees,
                                  DetailedCallback callback) {
        executor.execute(() -> {
            List<RegistrationResult> results = registerEmployeesInternal(ctx, employees, true);
            if (callback != null) {
                callback.onDone(results);
            }
        });
    }

    public void validateEmployeesForRebuild(Context ctx,
                                            List<Employee> employees,
                                            DetailedCallback callback) {
        executor.execute(() -> {
            List<RegistrationResult> results = registerEmployeesInternal(ctx, employees, false);
            if (callback != null) {
                callback.onDone(results);
            }
        });
    }

    public void prepareEmployeesForPersistence(Context ctx,
                                               List<Employee> employees,
                                               PreparedCallback callback) {
        prepareEmployeesForPersistence(ctx, employees, null, callback);
    }

    public void prepareEmployeesForPersistence(Context ctx,
                                               List<Employee> employees,
                                               PreparedProgressCallback progressCallback,
                                               PreparedCallback callback) {
        executor.execute(() -> {
            List<PreparedFaceResult> results = prepareEmployeesInternal(ctx, employees, progressCallback);
            if (callback != null) {
                callback.onDone(results);
            }
        });
    }

    private List<PreparedFaceResult> prepareEmployeesInternal(Context ctx,
                                                              List<Employee> employees,
                                                              PreparedProgressCallback progressCallback) {
        List<PreparedFaceResult> results = new ArrayList<>();
        if (employees == null) {
            return results;
        }
        for (Employee emp : employees) {
            if (emp == null || emp.id == null || emp.id.trim().isEmpty()) {
                continue;
            }
            PreparedFaceResult result = prepareSingleForPersistence(ctx, emp);
            results.add(result);
            if (progressCallback != null) {
                try {
                    progressCallback.onPrepared(result);
                } catch (RuntimeException e) {
                    Log.w(TAG, "prepare progress callback failed", e);
                }
            }
        }
        return results;
    }

    private PreparedFaceResult prepareSingleForPersistence(Context ctx, Employee emp) {
        if (emp.faceImageUrl == null || emp.faceImageUrl.trim().isEmpty()) {
            return PreparedFaceResult.fail(emp.id, FAIL_MSG_FACE_IMAGE_URL_EMPTY);
        }
        FaceFileManager.DownloadResult downloadResult = FaceFileManager.downloadAndVerify(
                ctx, emp.id, emp.faceVersion, emp.faceImageUrl, emp.faceImageSha256);
        if (!downloadResult.success) {
            String failMsg = downloadResult.failMsg == null || downloadResult.failMsg.trim().isEmpty()
                    ? FAIL_MSG_FACE_IMAGE_DOWNLOAD_FAILED
                    : downloadResult.failMsg.trim();
            return PreparedFaceResult.fail(emp.id, failMsg);
        }
        FaceManager.FeatureResult featureResult = FaceManager.get()
                .extractFeatureForPersistence(emp.id, downloadResult.path);
        if (!featureResult.success) {
            String failMsg = featureResult.errorMsg == null || featureResult.errorMsg.trim().isEmpty()
                    ? FAIL_MSG_FACE_IMAGE_INVALID
                    : featureResult.errorMsg.trim();
            return PreparedFaceResult.fail(emp.id, failMsg);
        }
        return PreparedFaceResult.ok(emp.id, featureResult.feature);
    }

    public void refreshEmployee(Context ctx, Employee emp, Callback callback) {
        executor.execute(() -> {
            FaceManager.get().removeFace(emp.id);
            DatabaseHelper.get(ctx).updateFaceRegistration(emp.id, null, false);
            RegistrationResult result = registerSingle(ctx, emp, true);
            boolean success = result.success;
            if (callback != null) {
                callback.onDone(success ? 1 : 0, success ? 0 : 1);
            }
        });
    }

    private List<RegistrationResult> registerEmployeesInternal(Context ctx,
                                                              List<Employee> employees,
                                                              boolean addToRuntimeLibrary) {
        List<RegistrationResult> results = new ArrayList<>();
        if (employees == null) {
            return results;
        }
        for (Employee emp : employees) {
            if (emp == null || emp.id == null || emp.id.trim().isEmpty()) {
                continue;
            }
            results.add(registerSingle(ctx, emp, addToRuntimeLibrary));
        }
        return results;
    }

    private RegistrationResult registerSingle(Context ctx, Employee emp, boolean addToRuntimeLibrary) {
        if (emp.faceImageUrl == null || emp.faceImageUrl.trim().isEmpty()) {
            Log.w(TAG, "Face image url is empty: empId=" + emp.id);
            return failRegistration(ctx, emp, FAIL_MSG_FACE_IMAGE_URL_EMPTY, addToRuntimeLibrary);
        }

        FaceFileManager.DownloadResult downloadResult = FaceFileManager.downloadAndVerify(
                ctx,
                emp.id,
                emp.faceVersion,
                emp.faceImageUrl,
                emp.faceImageSha256
        );
        if (!downloadResult.success) {
            String failMsg = downloadResult.failMsg == null || downloadResult.failMsg.trim().isEmpty()
                    ? FAIL_MSG_FACE_IMAGE_DOWNLOAD_FAILED
                    : downloadResult.failMsg.trim();
            Log.w(TAG, "Download/verify failed: empId=" + emp.id + " reason=" + failMsg);
            return failRegistration(ctx, emp, failMsg, addToRuntimeLibrary);
        }

        FaceManager.RegisterResult result = addToRuntimeLibrary
                ? FaceManager.get().registerFace(ctx, emp.id, downloadResult.path)
                : FaceManager.get().validateFaceImage(ctx, emp.id, downloadResult.path);
        if (result.success) {
            DatabaseHelper.get(ctx).updateFaceRegistration(emp.id, result.localFaceId, true);
            return RegistrationResult.ok(emp.id);
        }

        Log.w(TAG, "SDK register failed " + emp.id + ": " + result.errorMsg);
        String failMsg = result.errorMsg != null && !result.errorMsg.trim().isEmpty()
                ? result.errorMsg.trim()
                : FAIL_MSG_FACE_IMAGE_INVALID;
        return failRegistration(ctx, emp, failMsg, addToRuntimeLibrary);
    }

    private RegistrationResult failRegistration(Context ctx,
                                                Employee emp,
                                                String failMsg,
                                                boolean addToRuntimeLibrary) {
        if (addToRuntimeLibrary) {
            FaceManager.get().removeFace(emp.id);
            DatabaseHelper.get(ctx).updateFaceRegistration(emp.id, null, false);
        }
        return RegistrationResult.fail(emp.id, failMsg);
    }

    private int countSucceeded(List<RegistrationResult> results) {
        int count = 0;
        for (RegistrationResult result : results) {
            if (result != null && result.success) {
                count++;
            }
        }
        return count;
    }

    private int countFailed(List<RegistrationResult> results) {
        int count = 0;
        for (RegistrationResult result : results) {
            if (result != null && !result.success) {
                count++;
            }
        }
        return count;
    }

    public interface PreparedProgressCallback {
        void onPrepared(PreparedFaceResult result);
    }

    public interface PreparedCallback {
        void onDone(List<PreparedFaceResult> results);
    }

    public static final class PreparedFaceResult {
        public final String empId;
        public final boolean success;
        public final String failMsg;
        public final byte[] feature;

        private PreparedFaceResult(String empId, boolean success, String failMsg, byte[] feature) {
            this.empId = empId;
            this.success = success;
            this.failMsg = failMsg == null ? "" : failMsg;
            this.feature = feature;
        }

        public static PreparedFaceResult ok(String empId, byte[] feature) {
            return new PreparedFaceResult(empId, true, "", feature);
        }

        public static PreparedFaceResult fail(String empId, String failMsg) {
            return new PreparedFaceResult(empId, false, failMsg, null);
        }
    }

    public interface Callback {
        void onDone(int succeeded, int failed);
    }

    public interface DetailedCallback {
        void onDone(List<RegistrationResult> results);
    }

    public static final class RegistrationResult {
        public final String empId;
        public final boolean success;
        public final String failMsg;

        private RegistrationResult(String empId, boolean success, String failMsg) {
            this.empId = empId;
            this.success = success;
            this.failMsg = failMsg == null ? "" : failMsg;
        }

        public static RegistrationResult ok(String empId) {
            return new RegistrationResult(empId, true, "");
        }

        public static RegistrationResult fail(String empId, String failMsg) {
            return new RegistrationResult(empId, false, failMsg);
        }
    }
}

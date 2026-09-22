package com.punch.app.face;

import android.content.Context;
import android.util.Log;

import com.punch.app.network.ApiService;
import com.punch.app.utils.SessionManager;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;

public class FaceFileManager {
    private static final String TAG = "FaceFileManager";
    private static final String FACES_DIR = "faces";

    public static File ensureFacesDir(Context ctx) {
        File dir = new File(ctx.getFilesDir(), FACES_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /** Legacy pre-versioning path retained only as a read/migration fallback. */
    public static String getFaceImagePath(Context ctx, String empId) {
        File dir = ensureFacesDir(ctx);
        return new File(dir, empId + ".jpg").getAbsolutePath();
    }

    public static String getVersionedFaceImagePath(Context ctx,
                                                   String empId,
                                                   int faceVersion,
                                                   String expectedSha256,
                                                   String imageUrl) {
        File dir = ensureFacesDir(ctx);
        return new File(dir, buildVersionedFileName(
                empId, faceVersion, expectedSha256, imageUrl)).getAbsolutePath();
    }

    /**
     * Returns the immutable versioned image when available, otherwise falls back to the
     * legacy empId.jpg file so upgrades can rebuild once before the next server face refresh.
     */
    public static String findFaceImagePath(Context ctx,
                                           String empId,
                                           int faceVersion,
                                           String expectedSha256,
                                           String imageUrl) {
        File versioned = new File(getVersionedFaceImagePath(
                ctx, empId, faceVersion, expectedSha256, imageUrl));
        if (versioned.exists() && versioned.isFile()) {
            return versioned.getAbsolutePath();
        }
        File legacy = new File(getFaceImagePath(ctx, empId));
        return legacy.exists() && legacy.isFile()
                ? legacy.getAbsolutePath()
                : versioned.getAbsolutePath();
    }

    public static void deleteFaceImage(Context ctx, String empId) {
        File dir = ensureFacesDir(ctx);
        File legacy = new File(getFaceImagePath(ctx, empId));
        if (legacy.exists() && !legacy.delete()) {
            Log.w(TAG, "Unable to delete legacy face image: " + legacy.getAbsolutePath());
        }
        String prefix = safeEmployeeFileStem(empId) + "__";
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file != null && file.isFile() && file.getName().startsWith(prefix)
                    && !file.delete()) {
                Log.w(TAG, "Unable to delete versioned face image: " + file.getAbsolutePath());
            }
        }
    }

    public static void clearAllFaceImages(Context ctx) {
        File dir = ensureFacesDir(ctx);
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isFile() && !file.delete()) {
                Log.w(TAG, "Unable to delete face image: " + file.getAbsolutePath());
            }
        }
    }

    /** Backward-compatible overload for callers that do not yet have face_version. */
    public static DownloadResult downloadAndVerify(Context ctx,
                                                   String empId,
                                                   String imageUrl,
                                                   String expectedSha256) {
        return downloadAndVerify(ctx, empId, 0, imageUrl, expectedSha256);
    }

    /**
     * Downloads into a staging file and only promotes it after validation succeeds.
     * The final file name includes the desired face version/hash/url identity, therefore a
     * failed new download can never overwrite the last known-good image used by the old state.
     */
    public static DownloadResult downloadAndVerify(Context ctx,
                                                   String empId,
                                                   int faceVersion,
                                                   String imageUrl,
                                                   String expectedSha256) {
        File dir = ensureFacesDir(ctx);
        String safeUrl = imageUrl == null ? "" : imageUrl.trim();
        if (safeUrl.isEmpty()) {
            Log.w(TAG, "Face image url is empty for " + empId);
            return DownloadResult.fail("人脸图片URL为空");
        }

        File dest = new File(getVersionedFaceImagePath(
                ctx, empId, faceVersion, expectedSha256, safeUrl));
        String expected = expectedSha256 == null ? "" : expectedSha256.trim();

        if (dest.exists() && dest.isFile()) {
            if (expected.isEmpty() || expected.equalsIgnoreCase(sha256(dest))) {
                return DownloadResult.ok(dest.getAbsolutePath());
            }
            Log.w(TAG, "Existing versioned face image is corrupt, replacing: empId=" + empId);
        }

        String resolvedUrl = resolveImageUrl(safeUrl);
        File staging = new File(dir, dest.getName() + ".tmp-"
                + Thread.currentThread().getId() + "-" + System.nanoTime());
        try {
            boolean downloaded;
            try {
                downloaded = ApiService.downloadToFile(resolvedUrl, staging);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Invalid face image url: empId=" + empId + " url=" + resolvedUrl, e);
                return DownloadResult.fail("人脸图片URL无效");
            } catch (RuntimeException e) {
                Log.e(TAG, "Face image download error: empId=" + empId + " url=" + resolvedUrl, e);
                return DownloadResult.fail("人脸图片下载异常");
            }
            if (!downloaded || !staging.exists() || !staging.isFile() || staging.length() <= 0L) {
                Log.w(TAG, "Face image download failed: empId=" + empId + " url=" + resolvedUrl);
                return DownloadResult.fail("人脸图片下载失败");
            }

            if (!expected.isEmpty()) {
                String actual = sha256(staging);
                if (!expected.equalsIgnoreCase(actual)) {
                    Log.w(TAG, "Face image SHA256 mismatch: empId=" + empId
                            + " expected=" + expected + " actual=" + actual);
                    return DownloadResult.fail("人脸图片校验失败");
                }
            }

            if (dest.exists() && !dest.delete()) {
                return DownloadResult.fail("人脸图片版本文件替换失败");
            }
            if (!staging.renameTo(dest)) {
                Log.w(TAG, "Unable to atomically promote staged face image: empId=" + empId);
                return DownloadResult.fail("人脸图片保存失败");
            }
            return DownloadResult.ok(dest.getAbsolutePath());
        } finally {
            if (staging.exists() && !staging.delete()) {
                Log.w(TAG, "Unable to remove staged face image: " + staging.getAbsolutePath());
            }
        }
    }

    /**
     * Removes obsolete versions only after the DB desired-state transaction has committed.
     * If the current versioned file is absent (for example an upgraded legacy cache), nothing
     * is pruned so the legacy fallback remains usable.
     */
    public static void pruneObsoleteFaceImages(Context ctx,
                                               String empId,
                                               int faceVersion,
                                               String expectedSha256,
                                               String imageUrl) {
        File keep = new File(getVersionedFaceImagePath(
                ctx, empId, faceVersion, expectedSha256, imageUrl));
        if (!keep.exists() || !keep.isFile()) {
            return;
        }
        File dir = ensureFacesDir(ctx);
        String prefix = safeEmployeeFileStem(empId) + "__";
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file == null || !file.isFile() || file.equals(keep)
                        || !file.getName().startsWith(prefix)) {
                    continue;
                }
                if (!file.delete()) {
                    Log.w(TAG, "Unable to prune obsolete face image: " + file.getAbsolutePath());
                }
            }
        }
        File legacy = new File(getFaceImagePath(ctx, empId));
        if (legacy.exists() && !legacy.equals(keep) && !legacy.delete()) {
            Log.w(TAG, "Unable to prune legacy face image: " + legacy.getAbsolutePath());
        }
    }

    private static String buildVersionedFileName(String empId,
                                                 int faceVersion,
                                                 String expectedSha256,
                                                 String imageUrl) {
        StringBuilder token = new StringBuilder();
        if (faceVersion > 0) {
            token.append("v").append(faceVersion);
        }
        String sha = expectedSha256 == null ? "" : expectedSha256.trim().toLowerCase();
        if (!sha.isEmpty()) {
            if (token.length() > 0) token.append("-");
            token.append("sha-").append(sha.substring(0, Math.min(16, sha.length())));
        }
        if (token.length() == 0) {
            token.append("url-").append(shortHash(imageUrl == null ? "" : imageUrl.trim()));
        }
        return safeEmployeeFileStem(empId) + "__" + token + ".jpg";
    }

    private static String safeEmployeeFileStem(String empId) {
        String raw = empId == null ? "" : empId.trim();
        String safe = raw.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.isEmpty()) {
            safe = "emp";
        }
        return safe + "-" + shortHash(raw);
    }

    private static String shortHash(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((value == null ? "" : value)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < digest.length && sb.length() < 12; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(value == null ? 0 : value.hashCode());
        }
    }

    private static String resolveImageUrl(String source) {
        if (source.startsWith("http://") || source.startsWith("https://")) {
            return source;
        }
        String baseUrl = SessionManager.get().getBaseUrl();
        if (source.startsWith("/")) {
            return baseUrl + source;
        }
        return baseUrl + "/" + source;
    }

    private static String sha256(File file) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (FileInputStream in = new FileInputStream(file)) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) {
                    md.update(buf, 0, n);
                }
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static final class DownloadResult {
        public final boolean success;
        public final String path;
        public final String failMsg;

        private DownloadResult(boolean success, String path, String failMsg) {
            this.success = success;
            this.path = path;
            this.failMsg = failMsg == null ? "" : failMsg;
        }

        static DownloadResult ok(String path) {
            return new DownloadResult(true, path, "");
        }

        static DownloadResult fail(String failMsg) {
            return new DownloadResult(false, null, failMsg);
        }
    }
}

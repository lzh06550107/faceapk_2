package com.punch.app.network;

import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.punch.app.utils.Constants;
import com.punch.app.utils.SessionManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.Buffer;

public class ApiClient {
    private static final String TAG = "ApiClient";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final Gson GSON = new Gson();

    private static OkHttpClient client;
    private static String baseUrlOverride;

    private static OkHttpClient getClient() {
        if (client == null) {
            client = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .writeTimeout(15, TimeUnit.SECONDS)
                    .build();
        }
        return client;
    }

    static void setClientForTest(OkHttpClient testClient) {
        client = testClient;
    }

    static void setBaseUrlForTest(String baseUrl) {
        baseUrlOverride = baseUrl;
    }

    static void resetForTest() {
        client = null;
        baseUrlOverride = null;
    }

    private static String baseUrl() {
        return baseUrlOverride != null ? baseUrlOverride : SessionManager.get().getBaseUrl();
    }

    public static ApiResponse get(String path) {
        Request request = buildRequest(path, null);
        return execute(request);
    }

    public static ApiResponse post(String path, Object body) {
        String json = GSON.toJson(body);
        RequestBody rb = RequestBody.create(json, JSON);
        Request request = buildRequest(path, rb);
        return execute(request);
    }

    public static ApiResponse post(String path, Object body, long callTimeoutMillis) {
        String json = GSON.toJson(body);
        RequestBody rb = RequestBody.create(json, JSON);
        Request request = buildRequest(path, rb);
        return execute(request, callTimeoutMillis);
    }

    public static ApiResponse postForm(String path, Map<String, Object> body) {
        FormBody.Builder builder = new FormBody.Builder();
        if (body != null) {
            for (Map.Entry<String, Object> entry : body.entrySet()) {
                Object value = entry.getValue();
                if (entry.getKey() == null || value == null) {
                    continue;
                }
                builder.add(entry.getKey(), String.valueOf(value));
            }
        }
        Request request = buildFormRequest(path, builder.build());
        return execute(request);
    }

    public static ApiResponse post(String baseUrl, String path, Object body) {
        String json = GSON.toJson(body);
        RequestBody rb = RequestBody.create(json, JSON);
        Request request = buildRequest(baseUrl, path, rb);
        return execute(request);
    }

    public static ApiResponse postPublic(String path, Object body) {
        String json = GSON.toJson(body);
        RequestBody rb = RequestBody.create(json, JSON);
        Request request = buildPublicRequest(baseUrl(), path, rb, "POST");
        return execute(request);
    }

    public static ApiResponse postPublic(String baseUrl, String path, Object body) {
        String json = GSON.toJson(body);
        RequestBody rb = RequestBody.create(json, JSON);
        Request request = buildPublicRequest(baseUrl, path, rb, "POST");
        return execute(request);
    }

    public static ApiResponse put(String path, Object body) {
        String json = GSON.toJson(body);
        RequestBody rb = RequestBody.create(json, JSON);
        Request.Builder builder = new Request.Builder()
                .url(baseUrl() + path)
                .put(rb)
                .addHeader("X-Device-Id", SessionManager.get().getDeviceId())
                .addHeader("Content-Type", "application/json");
        addAuthorizationHeader(builder);
        return execute(builder.build());
    }

    private static Request buildRequest(String path, RequestBody body) {
        return buildRequest(baseUrl(), path, body);
    }

    private static Request buildRequest(String baseUrl, String path, RequestBody body) {
        Request.Builder builder = new Request.Builder()
                .url(baseUrl + path)
                .addHeader("X-Device-Id", SessionManager.get().getDeviceId())
                .addHeader("Content-Type", "application/json");
        addAuthorizationHeader(builder);
        if (body != null) {
            builder.post(body);
        } else {
            builder.get();
        }
        return builder.build();
    }

    private static Request buildPublicRequest(String baseUrl, String path, RequestBody body, String method) {
        Request.Builder builder = new Request.Builder()
                .url(baseUrl + path)
                .addHeader("X-Device-Id", SessionManager.get().getDeviceId())
                .addHeader("Content-Type", "application/json");
        if ("POST".equals(method)) {
            builder.post(body);
        } else {
            builder.method(method, body);
        }
        return builder.build();
    }

    private static Request buildFormRequest(String path, RequestBody body) {
        Request.Builder builder = new Request.Builder()
                .url(baseUrl() + path)
                .addHeader("X-Device-Id", SessionManager.get().getDeviceId())
                .addHeader("Content-Type", "application/x-www-form-urlencoded");
        addAuthorizationHeader(builder);
        return builder.post(body).build();
    }

    private static ApiResponse execute(Request request) {
        return execute(request, 0L);
    }

    private static ApiResponse execute(Request request, long callTimeoutMillis) {
        long startedAt = System.currentTimeMillis();
        okhttp3.Call call = getClient().newCall(request);
        if (callTimeoutMillis > 0L) {
            call.timeout().timeout(callTimeoutMillis, TimeUnit.MILLISECONDS);
        }
        try (Response response = call.execute()) {
            String bodyStr = response.body() != null ? response.body().string() : "{}";
            JsonObject obj = parseJsonObject(bodyStr);
            int backendCode = extractCode(obj, response.code());
            String backendMessage = extractMessage(obj);
            if (!response.isSuccessful()) {
                int code = backendCode;
                String message = backendMessage;
                if (message == null || message.trim().isEmpty()) {
                    message = "HTTP " + response.code();
                }
                logInteraction(request, response.code(), backendCode, false,
                        backendMessage, message, bodyStr, startedAt);
                return new ApiResponse(false, code, message, null);
            }
            if (obj == null) {
                logInteraction(request, response.code(), backendCode, false,
                        backendMessage, "Invalid response", bodyStr, startedAt);
                return new ApiResponse(false, response.code(), "Invalid response", null);
            }
            int code = obj.has("code") ? obj.get("code").getAsInt() : 200;
            String msg = obj.has("msg") ? obj.get("msg").getAsString() : "";
            boolean ok = code == 200 || code == 0;
            logInteraction(request, response.code(), code, ok, msg, ok ? "" : msg, bodyStr, startedAt);
            return new ApiResponse(ok, code, msg, ok && obj.has("data") ? obj.get("data") : null);
        } catch (IOException e) {
            logNetworkError("Request failed: " + e.getMessage());
            logInteraction(request, 0, 0, false, "", e.getMessage(), "", startedAt);
            return new ApiResponse(false, -1, e.getMessage(), null);
        }
    }

    public static boolean isNetworkAvailable() {
        try {
            Request req = new Request.Builder()
                    .url(baseUrl() + ApiEndpoints.HEALTH)
                    .get().build();
            Response resp = getClient().newCall(req).execute();
            resp.close();
            return resp.isSuccessful();
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean downloadToFile(String url, File destination) {
        long startedAt = System.currentTimeMillis();
        Request.Builder builder = new Request.Builder()
                .url(url)
                .addHeader("X-Device-Id", SessionManager.get().getDeviceId());
        addAuthorizationHeader(builder);
        Request request = builder.build();

        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            logDownloadInteraction(
                    request,
                    0,
                    false,
                    "Unable to create destination directory",
                    "",
                    startedAt,
                    destination
            );
            return false;
        }

        try (Response response = getClient().newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                String responseInfo = response.body() == null
                        ? "response_body_empty=true"
                        : buildDownloadResponseInfo(response, 0L);
                logDownloadInteraction(
                        request,
                        response.code(),
                        false,
                        response.body() == null ? "Response body is empty" : "HTTP " + response.code(),
                        responseInfo,
                        startedAt,
                        destination
                );
                return false;
            }

            try (InputStream input = response.body().byteStream();
                 FileOutputStream output = new FileOutputStream(destination, false)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
                output.flush();
                return true;
            }
        } catch (IOException e) {
            Log.e(TAG, "Download failed: " + e.getMessage());
            logDownloadInteraction(
                    request,
                    0,
                    false,
                    e.getClass().getSimpleName() + ": " + safeString(e.getMessage()),
                    "",
                    startedAt,
                    destination
            );
            return false;
        }
    }

    private static void logNetworkError(String message) {
        try {
            Log.e(TAG, message == null ? "" : message);
        } catch (RuntimeException ignored) {
            // android.util.Log is an unimplemented stub in plain JVM unit tests.
        }
    }

    private static void addAuthorizationHeader(Request.Builder builder) {
        String token = SessionManager.get().getToken();
        if (token != null && !token.trim().isEmpty()) {
            builder.addHeader("Authorization", "Bearer " + token.trim());
        }
    }

    private static JsonObject parseJsonObject(String bodyStr) {
        try {
            return GSON.fromJson(bodyStr, JsonObject.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int extractCode(JsonObject obj, int fallbackCode) {
        if (obj == null || !obj.has("code") || obj.get("code").isJsonNull()) {
            return fallbackCode;
        }
        try {
            return obj.get("code").getAsInt();
        } catch (Exception ignored) {
            return fallbackCode;
        }
    }

    private static String extractMessage(JsonObject obj) {
        if (obj == null || !obj.has("msg") || obj.get("msg").isJsonNull()) {
            return null;
        }
        try {
            return obj.get("msg").getAsString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void logInteraction(Request request,
                                       int httpStatus,
                                       int backendCode,
                                       boolean success,
                                       String backendMessage,
                                       String errorMessage,
                                       String responseBody,
                                       long startedAt) {
        InteractionLogStore store = InteractionLogStore.get();
        if (store == null || request == null) {
            return;
        }
        InteractionLogEntry entry = new InteractionLogEntry();
        entry.category = InteractionLogger.CATEGORY_NETWORK;
        entry.timeMillis = startedAt;
        entry.method = request.method();
        entry.url = request.url().toString();
        entry.path = request.url().encodedPath();
        if (request.url().encodedQuery() != null && !request.url().encodedQuery().isEmpty()) {
            entry.path += "?" + request.url().encodedQuery();
        }
        entry.group = InteractionLogger.resolveNetworkGroup(entry.path);
        entry.title = InteractionLogger.resolveNetworkTitle(entry.method, entry.path);
        entry.requestBody = sanitizeLoggedRequestBody(entry.path, bodyToString(request.body()));
        entry.responseBody = responseBody == null ? "" : responseBody;
        entry.httpStatus = httpStatus;
        entry.backendCode = backendCode;
        entry.success = success;
        entry.durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
        entry.backendMessage = backendMessage == null ? "" : backendMessage;
        entry.errorMessage = errorMessage == null ? "" : errorMessage;
        store.append(entry);
    }

    private static void logDownloadInteraction(Request request,
                                               int httpStatus,
                                               boolean success,
                                               String errorMessage,
                                               String responseInfo,
                                               long startedAt,
                                               File destination) {
        InteractionLogStore store = InteractionLogStore.get();
        if (store == null || request == null) {
            return;
        }
        InteractionLogEntry entry = new InteractionLogEntry();
        entry.category = InteractionLogger.CATEGORY_NETWORK;
        entry.group = InteractionLogger.resolveNetworkGroup(request.url().encodedPath());
        entry.title = "下载文件";
        entry.timeMillis = startedAt;
        entry.method = request.method();
        entry.url = request.url().toString();
        entry.path = request.url().encodedPath();
        if (request.url().encodedQuery() != null && !request.url().encodedQuery().isEmpty()) {
            entry.path += "?" + request.url().encodedQuery();
        }
        entry.httpStatus = httpStatus;
        entry.success = success;
        entry.durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
        entry.errorMessage = errorMessage == null ? "" : errorMessage.trim();
        entry.responseBody = appendDestinationInfo(responseInfo, destination);
        store.append(entry);
    }

    private static String buildDownloadResponseInfo(Response response, long bytesWritten) {
        StringBuilder builder = new StringBuilder();
        builder.append("bytes_written=").append(bytesWritten);
        if (response != null && response.body() != null) {
            builder.append("\ncontent_length=").append(response.body().contentLength());
            if (response.body().contentType() != null) {
                builder.append("\ncontent_type=").append(response.body().contentType());
            }
        }
        return builder.toString();
    }

    private static String appendDestinationInfo(String responseInfo, File destination) {
        StringBuilder builder = new StringBuilder();
        if (responseInfo != null && !responseInfo.trim().isEmpty()) {
            builder.append(responseInfo.trim());
        }
        if (destination != null) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append("destination=").append(destination.getAbsolutePath());
            builder.append("\nfile_exists=").append(destination.exists());
            builder.append("\nfile_size=").append(destination.exists() ? destination.length() : 0L);
        }
        return builder.toString();
    }

    private static String bodyToString(RequestBody body) {
        if (body == null) {
            return "";
        }
        try {
            Buffer buffer = new Buffer();
            body.writeTo(buffer);
            return buffer.readUtf8();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String sanitizeLoggedRequestBody(String path, String rawBody) {
        if (rawBody == null || rawBody.trim().isEmpty()) {
            return "";
        }
        if (path == null || !path.contains(ApiEndpoints.PUNCH)) {
            return rawBody;
        }
        JsonObject obj = parseJsonObject(rawBody);
        if (obj == null) {
            return rawBody;
        }
        if (obj.has("snap_image") && !obj.get("snap_image").isJsonNull()) {
            String snapImage = "";
            try {
                snapImage = obj.get("snap_image").getAsString();
            } catch (Exception ignored) {
                snapImage = "";
            }
            obj.addProperty("snap_image", "[base64 omitted]");
            obj.addProperty("snap_image_length", snapImage.length());
        }
        return GSON.toJson(obj);
    }

    private static String safeString(String value) {
        return value == null ? "" : value.trim();
    }
}

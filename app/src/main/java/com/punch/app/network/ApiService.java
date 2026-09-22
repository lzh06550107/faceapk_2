package com.punch.app.network;

import android.content.Context;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.punch.app.activation.DeviceInfoProvider;
import com.punch.app.model.Employee;
import com.punch.app.model.PunchRecord;
import com.punch.app.network.dto.AuthDto;
import com.punch.app.network.dto.DeviceDto;
import com.punch.app.network.dto.EmployeeSyncData;
import com.punch.app.network.dto.EventResultDto;
import com.punch.app.network.dto.HeartbeatDto;
import com.punch.app.network.dto.PunchDto;
import com.punch.app.utils.AppLogger;
import com.punch.app.utils.Constants;
import com.punch.app.utils.SessionManager;

import java.util.ArrayList;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okio.ByteString;

public final class ApiService {
    private static final int EMPLOYEE_SYNC_PAGE_SIZE = 200;
    private static final String TAG = "ApiService";

    private ApiService() {
    }

    public static ApiResult<AuthDto.LoginData> login(String account, String password, String deviceId) {
        Map<String, Object> body = new HashMap<>();
        body.put("account", account);
        body.put("password", password);
        body.put("device_id", deviceId);
        return parseLogin(ApiClient.postPublic(ApiEndpoints.AUTH_LOGIN, body));
    }

    public static ApiResult<AuthDto.TokenData> refreshToken(String token) {
        Map<String, Object> body = new HashMap<>();
        body.put("token", token);
        return parseToken(ApiClient.post(ApiEndpoints.AUTH_REFRESH, body));
    }

    public static ApiResult<DeviceDto.DeviceConfigData> fetchDeviceConfig() {
        return parseDeviceConfig(ApiClient.post(ApiEndpoints.DEVICE_CONFIG, new HashMap<>()));
    }

    public static ApiResult<HeartbeatDto.HeartbeatData> fetchHeartbeat(Context context) {
        return parseHeartbeat(ApiClient.post(
                ApiEndpoints.DEVICE_HEARTBEAT,
                DeviceInfoProvider.buildHeartbeatPayload(context)
        ));
    }

    public static ApiResult<EmployeeSyncData> syncEmployees(int page) {
        Map<String, Object> body = new HashMap<>();
        body.put("device_id", SessionManager.get().getDeviceId());
        body.put("page", page);
        body.put("page_size", EMPLOYEE_SYNC_PAGE_SIZE);
        body.put("op_status", 1);
        return parseEmployeeSync(ApiClient.post(ApiEndpoints.EMPLOYEE_SYNC, body));
    }

    public static ApiResult<Void> reportEventResult(String cursor,
                                                    String eventType,
                                                    boolean success,
                                                    String failureMsg) {
        return reportEventResult(cursor, eventType, success, null, failureMsg);
    }

    public static ApiResult<Void> reportEventResult(String cursor,
                                                    String eventType,
                                                    boolean success,
                                                    List<EventResultDto.EmployeeResult> employeeResults,
                                                    String failureMsg) {
        Map<String, Object> body = new HashMap<>();
        body.put("device_id", SessionManager.get().getDeviceId());
        body.put("event_cursor", cursor);
        body.put("event_type", eventType);
        body.put("success", success);
        if (employeeResults != null && !employeeResults.isEmpty()) {
            List<Map<String, Object>> employees = new ArrayList<>(employeeResults.size());
            for (EventResultDto.EmployeeResult employeeResult : employeeResults) {
                if (employeeResult == null) {
                    continue;
                }
                Map<String, Object> item = new HashMap<>();
                item.put("numbers", safeString(employeeResult.numbers));
                item.put("op_type", safeString(employeeResult.opType));
                item.put("success", employeeResult.success);
                item.put("fail_msg", safeString(employeeResult.failMsg));
                employees.add(item);
            }
            if (!employees.isEmpty()) {
                body.put("employees", employees);
            }
        }
        if (failureMsg != null && !failureMsg.trim().isEmpty()) {
            body.put("failure_msg", failureMsg.trim());
        }
        return emptyResult(ApiClient.post(ApiEndpoints.EVENT_RESULT, body));
    }

    /**
     * Synchronously asks the server to accept one punch attempt.
     *
     * <p>Despite the legacy endpoint name, a successful response with
     * {@code is_over_capacity=false} means the server has already atomically accepted
     * this punch attempt. The later {@code /clock/upload} only completes detail data.</p>
     */
    public static ApiResult<PunchDto.LineCapacityData> acceptLinePunch(String clientRecordId,
                                                                       String numbers,
                                                                       String lineBindingCode,
                                                                       long punchTime) {
        if (safeString(clientRecordId).trim().isEmpty()
                || safeString(numbers).trim().isEmpty()
                || safeString(lineBindingCode).trim().isEmpty()
                || punchTime <= 0L) {
            return ApiResult.failure(400, "invalid punch acceptance request");
        }
        String token = safeString(SessionManager.get().getToken()).trim();
        if (token.isEmpty()) {
            return ApiResult.failure(401, "token is missing");
        }
        Map<String, Object> body = new HashMap<>();
        body.put("client_record_id", clientRecordId.trim());
        body.put("numbers", numbers.trim());
        body.put("line_binding_code", lineBindingCode.trim());
        body.put("punch_time", punchTime);
        body.put("token", token);
        return parseLineCapacity(ApiClient.post(ApiEndpoints.LINE_IS_OVER_CAPACITY, body));
    }

    public static ApiResult<PunchDto.PunchPushData> pushPunch(PunchRecord punch) {
        if (punch == null || safeString(punch.clientRecordId).trim().isEmpty()) {
            return ApiResult.failure(400, "client_record_id is missing");
        }
        if (punch.teamBindingId <= 0) {
            return ApiResult.failure(400, "team_binding is missing");
        }
        return parsePunchPush(ApiClient.post(ApiEndpoints.PUNCH, buildPunchBody(punch)));
    }

    public static ApiResult<PunchDto.ClockStatisticsData> fetchClockStatistics(String date, String lineCode, int clockIndex, Integer clockStatus, int page, int pageSize) {
        Map<String, Object> body = new HashMap<>();
        body.put("dates", date);
        body.put("line_code", lineCode);
        body.put("clock_index", clockIndex);
        if (clockStatus != null) {
            body.put("clock_status", clockStatus);
        }
        body.put("page", page);
        body.put("page_size", pageSize);
        return parseClockStatistics(ApiClient.post(ApiEndpoints.PUNCH_STATISTICS, body));
    }

    public static ApiResult<DeviceDto.DeviceRegisterData> registerDevice(Context context) {
        return parseDeviceRegister(ApiClient.postPublic(
                ApiEndpoints.DEVICE_REGISTER,
                DeviceInfoProvider.buildRegisterPayload(context)
        ));
    }

    public static ApiResult<DeviceDto.DeviceActivateData> activateDevice(String deviceId) {
        Map<String, Object> body = new HashMap<>();
        body.put("device_id", deviceId);
        return parseDeviceActivate(ApiClient.postPublic(
                ApiEndpoints.DEVICE_ACTIVATE_BASE_URL,
                ApiEndpoints.DEVICE_ACTIVATE,
                body
        ));
    }

    public static boolean isBackendAvailable() {
        return ApiClient.isNetworkAvailable();
    }

    public static boolean downloadToFile(String url, File destination) {
        return ApiClient.downloadToFile(url, destination);
    }

    private static ApiResult<AuthDto.LoginData> parseLogin(ApiResponse response) {
        JsonObject data = getDataObject(response);
        if (data == null) {
            return failure(response);
        }

        String token = getString(data, "token");
        Long tokenExpireAt = getLong(data, "token_expire_at");
        if (token == null || tokenExpireAt == null) {
            return invalidResponse(response);
        }

        AuthDto.LoginData result = new AuthDto.LoginData();
        result.token = token;
        result.tokenExpireAt = tokenExpireAt;

        JsonObject device = getObject(data, "device");
        if (device != null) {
            result.deviceId = safeString(getString(device, "device_id"));
            result.deviceName = safeString(getString(device, "name"));
            result.lineCode = safeString(getString(device, "line_code"));
            result.lineName = safeString(getString(device, "line_name"));
            result.teamCode = safeString(getString(device, "team"));
        }
        return success(response, result);
    }

    private static ApiResult<AuthDto.TokenData> parseToken(ApiResponse response) {
        JsonObject data = getDataObject(response);
        if (data == null) {
            return failure(response);
        }

        String token = getString(data, "token");
        Long tokenExpireAt = getLong(data, "token_expire_at");
        if (token == null || tokenExpireAt == null) {
            return invalidResponse(response);
        }

        AuthDto.TokenData result = new AuthDto.TokenData();
        result.token = token;
        result.tokenExpireAt = tokenExpireAt;
        return success(response, result);
    }

    private static ApiResult<DeviceDto.DeviceRegisterData> parseDeviceRegister(ApiResponse response) {
        JsonObject data = getDataObject(response);
        if (data == null) {
            return failure(response);
        }

        DeviceDto.DeviceRegisterData result = new DeviceDto.DeviceRegisterData();
        result.deviceId = safeString(getString(data, "device_id"));
        return success(response, result);
    }

    private static ApiResult<DeviceDto.DeviceActivateData> parseDeviceActivate(ApiResponse response) {
        if (response == null || !response.success || response.data == null || response.data.isJsonNull()) {
            return failure(response);
        }

        DeviceDto.DeviceActivateData result = new DeviceDto.DeviceActivateData();
        JsonElement data = response.data;
        if (data.isJsonObject()) {
            JsonObject dataObject = data.getAsJsonObject();
            result.deviceId = firstNonBlank(
                    getString(dataObject, "device_id"),
                    getString(dataObject, "deviceId")
            );
            result.activationCode = firstNonBlank(
                    getString(dataObject, "activation_code"),
                    getString(dataObject, "activationCode"),
                    getString(dataObject, "code"),
                    getString(dataObject, "license_code")
            );
        } else if (data.isJsonPrimitive()) {
            result.activationCode = safeString(data.getAsString());
        } else if (data.isJsonArray() && data.getAsJsonArray().size() > 0) {
            JsonElement first = data.getAsJsonArray().get(0);
            if (first != null && first.isJsonPrimitive()) {
                result.activationCode = safeString(first.getAsString());
            }
        }
        if (result.activationCode.isEmpty()) {
            return invalidResponse(response);
        }
        return success(response, result);
    }

    private static ApiResult<DeviceDto.DeviceConfigData> parseDeviceConfig(ApiResponse response) {
        JsonObject data = getDataObject(response);
        if (data == null) {
            return failure(response);
        }

        DeviceDto.DeviceConfigData result = new DeviceDto.DeviceConfigData();
        result.deviceId = safeString(getString(data, "device_id"));
        result.account = safeString(getString(data, "account"));
        result.password = safeString(getString(data, "password"));
        result.lineCode = safeString(getString(data, "line_binding_code"));
        result.lineName = safeString(getString(data, "line_binding_name"));
        result.teamBindingId = valueOrZero(getInt(data, "team_binding"));
        result.teamBindingName = safeString(getString(data, "team_binding_name"));
        result.checkCount = valueOrZero(getInt(data, "check_count"));
        result.needUpdate = Boolean.TRUE.equals(getBoolean(data, "need_update"));

        JsonArray lines = getArray(data, "lines");
        if (lines != null) {
            for (JsonElement element : lines) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject line = element.getAsJsonObject();
                DeviceDto.LineOptionData option = new DeviceDto.LineOptionData();
                option.code = safeString(getString(line, "code"));
                option.name = safeString(getString(line, "name"));
                result.lines.add(option);
            }
        }

        JsonArray teams = getArray(data, "teams");
        if (teams != null) {
            for (JsonElement element : teams) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject team = element.getAsJsonObject();
                DeviceDto.TeamOptionData option = new DeviceDto.TeamOptionData();
                option.id = valueOrZero(getInt(team, "id"));
                option.name = safeString(getString(team, "name"));
                JsonArray timeRanges = getArray(team, "time_ranges");
                if (timeRanges != null) {
                    for (JsonElement rangeElement : timeRanges) {
                        if (rangeElement == null || rangeElement.isJsonNull()) {
                            continue;
                        }
                        option.timeRanges.add(safeString(rangeElement.getAsString()));
                    }
                }
                result.teams.add(option);
            }
        }

        JsonObject update = getObject(data, "update");
        if (update != null) {
            result.updateInfo.needUpdate = Boolean.TRUE.equals(getBoolean(update, "need_update"));
            result.updateInfo.apkUrl = safeString(getString(update, "apk_url"));
            result.updateInfo.currentVersion = safeString(getString(update, "current_version"));
            result.updateInfo.targetVersion = safeString(getString(update, "target_version"));
            result.updateInfo.versionName = safeString(getString(update, "version_name"));
        }

        JsonObject baiduParams = getObject(data, "baidu_params");
        if (baiduParams != null) {
            Integer matchThreshold = getInt(baiduParams, "match_threshold");
            if (matchThreshold != null) {
                result.matchThreshold = matchThreshold / 100f;
            }
            Integer faceThreshold = getInt(baiduParams, "face_threshold");
            if (faceThreshold != null) {
                result.faceThreshold = faceThreshold / 100f;
            }
            result.livenessCheck = getBoolean(baiduParams, "liveness_check");
            result.maskDetect = getBoolean(baiduParams, "mask_detect");
            result.timeoutSeconds = getInt(baiduParams, "timeout");
            Integer distanceCode = getInt(baiduParams, "recognize_distance");
            if (distanceCode != null) {
                result.recognitionDistanceMode = mapDistanceMode(distanceCode);
            }
        }
        return success(response, result);
    }

    private static ApiResult<HeartbeatDto.HeartbeatData> parseHeartbeat(ApiResponse response) {
        JsonObject data = getDataObject(response);
        if (data == null) {
            return failure(response);
        }

        HeartbeatDto.HeartbeatData result = new HeartbeatDto.HeartbeatData();
        Long serverTime = getLong(data, "server_time");
        result.serverTime = serverTime != null ? serverTime : 0L;
        result.hasChanges = Boolean.TRUE.equals(getBoolean(data, "has_changes"));

        JsonArray events = getArray(data, "events");
        if (events != null) {
            for (JsonElement element : events) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject item = element.getAsJsonObject();
                HeartbeatDto.HeartbeatEventData event = new HeartbeatDto.HeartbeatEventData();
                event.cursor = firstNonBlank(
                        getString(item, "event_cursor"),
                        getString(item, "cursor")
                );
                event.eventType = safeString(getString(item, "event_type"));
                result.events.add(event);
            }
        }
        return success(response, result);
    }

    private static ApiResult<EmployeeSyncData> parseEmployeeSync(ApiResponse response) {
        JsonObject data = getDataObject(response);
        if (data == null) {
            return failure(response);
        }

        EmployeeSyncData result = new EmployeeSyncData();
        result.hasMore = Boolean.TRUE.equals(getBoolean(data, "has_more"));
        result.page = valueOrZero(getInt(data, "page"));
        result.totalPages = valueOrZero(getInt(data, "total_pages"));
        result.serverTime = valueOrZero(getLong(data, "server_time"));

        JsonArray employees = getArray(data, "employees");
        if (employees != null) {
            for (JsonElement element : employees) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject item = element.getAsJsonObject();
                String numbers = safeString(getString(item, "numbers"));
                String opType = safeString(getString(item, "op_type"));
                long opTime = valueOrZero(getLong(item, "op_time"));
                if (numbers.isEmpty()) {
                    continue;
                }
                EmployeeSyncData.ChangeItem changeItem = new EmployeeSyncData.ChangeItem();
                changeItem.numbers = numbers;
                changeItem.opType = opType;
                changeItem.opTime = opTime;
                if ("delete".equalsIgnoreCase(opType)) {
                    result.deletedIds.add(numbers);
                    result.changeItems.add(changeItem);
                    continue;
                }

                Employee employee = new Employee();
                employee.id = numbers;
                employee.name = safeString(getString(item, "name"));
                employee.dept = safeString(getString(item, "dept"));
                employee.faceImageUrl = firstNonBlank(
                        getString(item, "face_image_url"),
                        getString(item, "face_url")
                );
                employee.faceImageSha256 = firstNonBlank(
                        getString(item, "face_image_sha256"),
                        getString(item, "face_sha256"),
                        getString(item, "face_hash")
                );
                employee.faceVersion = valueOrZero(getInt(item, "face_version"));
                employee.faceStatus = safeString(getString(item, "face_status"));
                employee.status = safeString(getString(item, "status"));
                employee.syncVersion = valueOrZero(getInt(item, "sync_version"));
                employee.updatedAt = opTime;
                employee.isDeleted = 0;
                result.employees.add(employee);
                changeItem.employee = employee;
                result.changeItems.add(changeItem);
            }
        }
        return success(response, result);
    }

    private static ApiResult<PunchDto.LineCapacityData> parseLineCapacity(ApiResponse response) {
        JsonObject data = getDataObject(response);
        if (data == null) {
            return failure(response);
        }
        Boolean isOverCapacity = getBoolean(data, "is_over_capacity");
        if (isOverCapacity == null) {
            return invalidResponse(response);
        }
        PunchDto.LineCapacityData result = new PunchDto.LineCapacityData();
        result.isOverCapacity = isOverCapacity;
        return success(response, result);
    }

    private static ApiResult<PunchDto.PunchPushData> parsePunchPush(ApiResponse response) {
        JsonObject data = getDataObject(response);
        if (data == null) {
            return failure(response);
        }

        PunchDto.PunchPushData result = new PunchDto.PunchPushData();
        result.recordId = safeString(getString(data, "record_id"));
        result.snapTime = valueOrZero(getLong(data, "snap_time"));
        result.snapTimeStr = safeString(getString(data, "snap_time_str"));
        result.dates = safeString(getString(data, "dates"));
        result.attendReportId = safeString(getString(data, "attend_report_id"));
        result.attendReportTable = safeString(getString(data, "attend_report_table"));
        return success(response, result);
    }

    private static ApiResult<PunchDto.ClockStatisticsData> parseClockStatistics(ApiResponse response) {
        JsonObject data = getDataObject(response);
        if (data == null) {
            return failure(response);
        }

        PunchDto.ClockStatisticsData result = new PunchDto.ClockStatisticsData();
        result.date = safeString(getString(data, "date"));
        result.lineCode = safeString(getString(data, "line_code"));
        result.clockIndex = valueOrZero(getInt(data, "clock_index"));
        result.clockIndexName = safeString(getString(data, "clock_index_name"));
        result.total = valueOrZero(getInt(data, "total"));
        result.page = valueOrZero(getInt(data, "page"));
        result.pageSize = valueOrZero(getInt(data, "page_size"));

        JsonObject summary = getObject(data, "summary");
        if (summary != null) {
            result.summary.total = valueOrZero(getInt(summary, "total"));
            result.summary.clocked = valueOrZero(getInt(summary, "clocked"));
            result.summary.unclocked = valueOrZero(getInt(summary, "unclocked"));
            result.summary.special = valueOrZero(getInt(summary, "special"));
        }

        JsonArray rows = getArray(data, "rows");
        if (rows != null) {
            for (JsonElement element : rows) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject item = element.getAsJsonObject();
                PunchDto.Row row = new PunchDto.Row();
                row.numbers = safeString(getString(item, "numbers"));
                row.name = safeString(getString(item, "name"));
                row.lineId = valueOrZero(getInt(item, "line_id"));
                row.lineName = safeString(getString(item, "line_name"));
                row.facePath = safeString(getString(item, "face_path"));
                row.clockTime = safeString(getString(item, "clock_time"));
                row.clockStatus = valueOrZero(getInt(item, "clock_status"));
                row.clockStatusText = safeString(getString(item, "clock_status_text"));
                row.sign = safeString(getString(item, "sign"));
                row.specialText = safeString(getString(item, "special_text"));
                row.lateMinutes = valueOrZero(getInt(item, "late_minutes"));
                row.earlyMinutes = valueOrZero(getInt(item, "early_minutes"));
                result.rows.add(row);
            }
        }
        return success(response, result);
    }

    private static ApiResult<Void> emptyResult(ApiResponse response) {
        if (response == null) {
            return ApiResult.failure(-1, "");
        }
        return response.success
                ? ApiResult.success(response.code, response.message, null)
                : ApiResult.failure(response.code, response.message);
    }

    private static <T> ApiResult<T> success(ApiResponse response, T data) {
        return ApiResult.success(response.code, response.message, data);
    }

    private static <T> ApiResult<T> failure(ApiResponse response) {
        if (response == null) {
            return ApiResult.failure(-1, "");
        }
        return ApiResult.failure(response.code, response.message);
    }

    private static <T> ApiResult<T> invalidResponse(ApiResponse response) {
        String message = response != null && response.message != null && !response.message.isEmpty()
                ? response.message
                : "Invalid response";
        int code = response != null ? response.code : -1;
        return ApiResult.failure(code, message);
    }

    private static Map<String, Object> buildPunchBody(PunchRecord punch) {
        Map<String, Object> body = new HashMap<>();
        body.put("client_record_id", safeString(punch.clientRecordId));
        body.put("numbers", safeString(punch.empId));
        body.put("team_binding", punch.teamBindingId);
        body.put("line_binding_code", safeString(punch.lineCode));
        body.put("snap_time", punch.punchTime);
        body.put("snap_image", encodeSnapshotBase64(punch.snapImagePath));
        body.put("match_score", punch.matchScore);
        return body;
    }

    private static String encodeSnapshotBase64(String snapshotPath) {
        if (snapshotPath == null || snapshotPath.trim().isEmpty()) {
            return "";
        }
        File file = new File(snapshotPath);
        if (!file.exists() || !file.isFile()) {
            AppLogger.w(TAG, "Punch snapshot missing: " + snapshotPath);
            return "";
        }
        try (FileInputStream inputStream = new FileInputStream(file);
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            return ByteString.of(outputStream.toByteArray()).base64();
        } catch (IOException e) {
            AppLogger.w(TAG, "Encode punch snapshot failed: " + e.getMessage());
            return "";
        }
    }

    private static String mapDistanceMode(int code) {
        if (code <= 1) {
            return Constants.DISTANCE_MODE_NEAR;
        }
        if (code >= 3) {
            return Constants.DISTANCE_MODE_FAR;
        }
        return Constants.DISTANCE_MODE_STANDARD;
    }

    private static JsonObject getDataObject(ApiResponse response) {
        if (response == null || !response.success || response.data == null || !response.data.isJsonObject()) {
            return null;
        }
        return response.data.getAsJsonObject();
    }

    private static JsonObject getObject(JsonObject obj, String key) {
        return obj.has(key) && obj.get(key).isJsonObject() ? obj.getAsJsonObject(key) : null;
    }

    private static JsonArray getArray(JsonObject obj, String key) {
        return obj.has(key) && obj.get(key).isJsonArray() ? obj.getAsJsonArray(key) : null;
    }

    private static String getString(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }

    private static Integer getInt(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        try {
            return obj.get(key).getAsInt();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Long getLong(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsLong() : null;
    }

    private static Boolean getBoolean(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsBoolean() : null;
    }

    private static int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }

    private static String safeString(String value) {
        return value == null ? "" : value;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }
}

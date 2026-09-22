package com.punch.app.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.punch.app.network.dto.DeviceDto;
import com.punch.app.network.dto.EmployeeSyncData;
import com.punch.app.network.dto.HeartbeatDto;
import com.punch.app.utils.SessionManager;

import org.junit.Test;

import okhttp3.Request;

public class DeviceApiTest extends ApiTestSupport {

    @Test
    public void fetchHeartbeat_shouldParseEventReceiptPayload() throws Exception {
        SessionManager.get().saveToken("token-abc", 1893456000L);
        interceptor.enqueueJson(200, successEnvelope(
                "{" +
                        "\"server_time\":1750001002," +
                        "\"has_changes\":true," +
                        "\"events\":[{" +
                        "\"event_cursor\":\"evt_001\"," +
                        "\"event_type\":\"person_changed\"},{" +
                        "\"cursor\":\"evt_002\"," +
                        "\"event_type\":\"config_changed\"}]" +
                        "}"
        ));

        ApiResult<HeartbeatDto.HeartbeatData> result = ApiService.fetchHeartbeat(null);

        assertTrue(result.success);
        assertEquals(1750001002L, result.data.serverTime);
        assertTrue(result.data.hasChanges);
        assertEquals(2, result.data.events.size());
        assertEquals("evt_001", result.data.events.get(0).cursor);
        assertEquals("person_changed", result.data.events.get(0).eventType);
        assertEquals("evt_002", result.data.events.get(1).cursor);
        assertEquals("config_changed", result.data.events.get(1).eventType);

        Request request = interceptor.takeRequest();
        assertEquals("/v3/handheld/device/heartbeat", request.url().encodedPath());
        assertEquals("Bearer token-abc", request.header("Authorization"));
        assertTrue(interceptor.takeBody().contains("\"device_id\":\"A1B2C3D4E5F60789\""));
    }

    @Test
    public void fetchHeartbeat_shouldReturnHttpFailureWhenServerReturns500() throws Exception {
        SessionManager.get().saveToken("token-abc", 1893456000L);
        interceptor.enqueueJson(500, "{\"msg\":\"server error\"}");

        ApiResult<HeartbeatDto.HeartbeatData> result = ApiService.fetchHeartbeat(null);

        assertFalse(result.success);
        assertEquals(500, result.code);
        assertEquals("server error", result.message);
        assertNull(result.data);
    }

    @Test
    public void syncEmployees_shouldParseSyncAndDeleteEntries() throws Exception {
        SessionManager.get().saveToken("token-abc", 1893456000L);
        interceptor.enqueueJson(200, successEnvelope(
                "{" +
                        "\"employees\":[" +
                        "{\"numbers\":\"E001\",\"name\":\"Alice\",\"face_image_url\":\"https://x/a.jpg\",\"op_type\":\"sync\",\"op_time\":1750524000}," +
                        "{\"numbers\":\"E002\",\"name\":\"Bob\",\"face_image_url\":\"\",\"op_type\":\"delete\",\"op_time\":1750524000}]," +
                        "\"total\":100," +
                        "\"page\":1," +
                        "\"total_pages\":1," +
                        "\"page_size\":500," +
                        "\"has_more\":false," +
                        "\"server_time\":1750524010" +
                        "}"
        ));

        ApiResult<EmployeeSyncData> result = ApiService.syncEmployees(1);

        assertTrue(result.success);
        assertEquals(1, result.data.employees.size());
        assertEquals("E001", result.data.employees.get(0).id);
        assertEquals("Alice", result.data.employees.get(0).name);
        assertEquals("https://x/a.jpg", result.data.employees.get(0).faceImageUrl);
        assertEquals(1, result.data.deletedIds.size());
        assertEquals("E002", result.data.deletedIds.get(0));
        assertFalse(result.data.hasMore);
        assertEquals(1, result.data.page);
        assertEquals(1, result.data.totalPages);
        assertEquals(1750524010L, result.data.serverTime);

        Request request = interceptor.takeRequest();
        assertEquals("/v3/handheld/employee/sync", request.url().encodedPath());
        assertEquals("Bearer token-abc", request.header("Authorization"));
        String body = interceptor.takeBody();
        assertTrue(body.contains("\"page\":1"));
        assertTrue(body.contains("\"page_size\":200"));
        assertTrue(body.contains("\"op_status\":1"));
        assertTrue(body.contains("\"device_id\":\"A1B2C3D4E5F60789\""));
    }

    @Test
    public void fetchDeviceConfig_shouldUseEmptyBodyAndParseResponse() throws Exception {
        SessionManager.get().saveToken("token-abc", 1893456000L);
        interceptor.enqueueJson(200, successEnvelope(
                "{" +
                        "\"device_id\":\"A1B2C3D4E5F60789\"," +
                        "\"account\":\"admin\"," +
                        "\"password\":\"admin\"," +
                        "\"line_binding_code\":\"PKZ450\"," +
                        "\"line_binding_name\":\"SMT Line\"," +
                        "\"team_binding\":1," +
                        "\"team_binding_name\":\"Team C\"," +
                        "\"check_count\":15," +
                        "\"need_update\":true," +
                        "\"lines\":[{\"code\":\"PKZ450\",\"name\":\"SMT Line\"}]," +
                        "\"teams\":[{\"id\":\"1\",\"name\":\"Team C\",\"time_ranges\":[\"09:00-22:00\"]}]," +
                        "\"update\":{" +
                        "\"need_update\":true," +
                        "\"apk_url\":\"http://192.168.1.180/storage/uploaded/app.apk\"," +
                        "\"current_version\":\"1.0.0\"," +
                        "\"target_version\":\"2.0.0\"," +
                        "\"version_name\":\"release\"}," +
                        "\"baidu_params\":{" +
                        "\"face_threshold\":68," +
                        "\"match_threshold\":41," +
                        "\"recognize_distance\":3," +
                        "\"liveness_check\":true," +
                        "\"mask_detect\":true," +
                        "\"timeout\":5}" +
                        "}"
        ));

        ApiResult<DeviceDto.DeviceConfigData> result = ApiService.fetchDeviceConfig();

        assertTrue(result.success);
        assertEquals("A1B2C3D4E5F60789", result.data.deviceId);
        assertEquals("admin", result.data.account);
        assertEquals("admin", result.data.password);
        assertEquals("PKZ450", result.data.lineCode);
        assertEquals("SMT Line", result.data.lineName);
        assertEquals(1, result.data.teamBindingId);
        assertEquals("Team C", result.data.teamBindingName);
        assertEquals(15, result.data.checkCount);
        assertTrue(result.data.needUpdate);
        assertEquals(1, result.data.lines.size());
        assertEquals("PKZ450", result.data.lines.get(0).code);
        assertEquals("SMT Line", result.data.lines.get(0).name);
        assertEquals(1, result.data.teams.size());
        assertEquals(1, result.data.teams.get(0).id);
        assertEquals("Team C", result.data.teams.get(0).name);
        assertEquals("09:00-22:00", result.data.teams.get(0).timeRanges.get(0));
        assertTrue(result.data.updateInfo.needUpdate);
        assertEquals("http://192.168.1.180/storage/uploaded/app.apk", result.data.updateInfo.apkUrl);
        assertEquals("1.0.0", result.data.updateInfo.currentVersion);
        assertEquals("2.0.0", result.data.updateInfo.targetVersion);
        assertEquals("release", result.data.updateInfo.versionName);
        assertEquals(Float.valueOf(0.41f), result.data.matchThreshold);
        assertEquals(Float.valueOf(0.68f), result.data.faceThreshold);
        assertEquals(Boolean.TRUE, result.data.livenessCheck);
        assertEquals(Boolean.TRUE, result.data.maskDetect);
        assertEquals(Integer.valueOf(5), result.data.timeoutSeconds);
        assertEquals("far", result.data.recognitionDistanceMode);

        Request request = interceptor.takeRequest();
        assertEquals("/v3/handheld/device/get-config", request.url().encodedPath());
        assertEquals("{}", interceptor.takeBody());
        assertEquals("Bearer token-abc", request.header("Authorization"));
    }

    @Test
    public void fetchDeviceConfig_shouldReturnFailureWhenDataIsMissing() throws Exception {
        SessionManager.get().saveToken("token-abc", 1893456000L);
        interceptor.enqueueJson(200, "{" +
                "\"code\":200," +
                "\"msg\":\"success\"" +
                "}");

        ApiResult<DeviceDto.DeviceConfigData> result = ApiService.fetchDeviceConfig();

        assertFalse(result.success);
        assertEquals(200, result.code);
        assertEquals("success", result.message);
        assertNull(result.data);
    }

    @Test
    public void registerDevice_shouldUsePublicEndpointAndParseDeviceId() throws Exception {
        SessionManager.get().saveCompanyId(18);
        interceptor.enqueueJson(200, successEnvelope(
                "{" +
                        "\"device_id\":\"A1B2C3D4E5F60789\"" +
                        "}"
        ));

        ApiResult<DeviceDto.DeviceRegisterData> result = ApiService.registerDevice(null);

        assertTrue(result.success);
        assertNotNull(result.data);
        assertEquals("A1B2C3D4E5F60789", result.data.deviceId);

        Request request = interceptor.takeRequest();
        assertEquals("/v3/handheld/device/register", request.url().encodedPath());
        assertEquals("POST", request.method());
        assertNull(request.header("Authorization"));
        String body = interceptor.takeBody();
        assertTrue(body.contains("\"company_id\":18"));
        assertTrue(body.contains("\"device_name\":"));
        assertFalse(body.contains("\"device_name\":\"\""));
        assertTrue(body.contains("\"device_id\":\"A1B2C3D4E5F60789\""));
        assertTrue(body.contains("\"ip\":"));
        assertTrue(body.contains("\"software_version\":\"1.0.0\""));
    }

    @Test
    public void activateDevice_shouldUseDedicatedBaseUrlWithoutAuthorizationAndParseActivationCode() throws Exception {
        SessionManager.get().saveToken("token-abc", 1893456000L);
        interceptor.enqueueJson(200, successEnvelope(
                "{" +
                        "\"device_id\":\"A1B2C3D4E5F60789\"," +
                        "\"activation_code\":\"ACT-12345\"" +
                        "}"
        ));

        ApiResult<DeviceDto.DeviceActivateData> result = ApiService.activateDevice("A1B2C3D4E5F60789");

        assertTrue(result.success);
        assertNotNull(result.data);
        assertEquals("A1B2C3D4E5F60789", result.data.deviceId);
        assertEquals("ACT-12345", result.data.activationCode);

        Request request = interceptor.takeRequest();
        assertEquals("/device/activate", request.url().encodedPath());
        assertEquals("park.hainasmart.com.cn", request.url().host());
        assertEquals(8898, request.url().port());
        assertNull(request.header("Authorization"));
        assertTrue(interceptor.takeBody().contains("\"device_id\":\"A1B2C3D4E5F60789\""));
    }

    @Test
    public void activateDevice_shouldAcceptPrimitiveActivationCodePayload() throws Exception {
        interceptor.enqueueJson(200, successEnvelope("\"ACT-PRIMITIVE\""));

        ApiResult<DeviceDto.DeviceActivateData> result = ApiService.activateDevice("A1B2C3D4E5F60789");

        assertTrue(result.success);
        assertNotNull(result.data);
        assertEquals("", result.data.deviceId);
        assertEquals("ACT-PRIMITIVE", result.data.activationCode);
    }
}

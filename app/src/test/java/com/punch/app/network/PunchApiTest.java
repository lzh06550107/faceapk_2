package com.punch.app.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.punch.app.model.PunchRecord;
import com.punch.app.network.dto.PunchDto;
import com.punch.app.utils.SessionManager;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import okhttp3.Request;

public class PunchApiTest extends ApiTestSupport {

    @Test
    public void pushPunch_shouldFailWhenClientRecordIdMissing() {
        PunchRecord punch = new PunchRecord();
        punch.teamBindingId = 2;

        ApiResult<PunchDto.PunchPushData> result = ApiService.pushPunch(punch);

        assertFalse(result.success);
        assertEquals(400, result.code);
    }


    @Test
    public void acceptLinePunch_shouldSendMinimalAcceptancePayloadAndParseAllowed() throws Exception {
        SessionManager.get().saveToken("token-abc", 1893456000L);
        interceptor.enqueueJson(200, successEnvelope("{\"is_over_capacity\":false}"));

        ApiResult<PunchDto.LineCapacityData> result = ApiService.acceptLinePunch(
                "PDEVICE001_01KXYZ",
                "EMP001",
                "LINE01",
                1782424800L
        );

        assertTrue(result.success);
        assertFalse(result.data.isOverCapacity);

        Request request = interceptor.takeRequest();
        assertEquals("/v3/handheld/line/isOverCapacity", request.url().encodedPath());
        assertEquals("Bearer token-abc", request.header("Authorization"));
        String body = interceptor.takeBody();
        assertTrue(body.contains("\"client_record_id\":\"PDEVICE001_01KXYZ\""));
        assertTrue(body.contains("\"numbers\":\"EMP001\""));
        assertTrue(body.contains("\"line_binding_code\":\"LINE01\""));
        assertTrue(body.contains("\"punch_time\":1782424800"));
        assertTrue(body.contains("\"token\":\"token-abc\""));
        assertFalse(body.contains("\"team_binding\""));
        assertFalse(body.contains("\"snap_image\""));
    }

    @Test
    public void acceptLinePunch_shouldRetryTransportFailureWithSameClientRecordId() throws Exception {
        SessionManager.get().saveToken("token-abc", 1893456000L);
        interceptor.enqueueFailure("timeout");
        interceptor.enqueueJson(200, successEnvelope("{\"is_over_capacity\":false}"));

        ApiResult<PunchDto.LineCapacityData> result = ApiService.acceptLinePunch(
                "PDEVICE001_01KXYZ",
                "EMP001",
                "LINE01",
                1782424800L
        );

        assertTrue(result.success);
        assertFalse(result.data.isOverCapacity);
        assertEquals(2, interceptor.getRequestCount());

        Request request = interceptor.takeRequest();
        assertEquals("/v3/handheld/line/isOverCapacity", request.url().encodedPath());
        String body = interceptor.takeBody();
        assertTrue(body.contains("\"client_record_id\":\"PDEVICE001_01KXYZ\""));
        assertTrue(body.contains("\"punch_time\":1782424800"));
    }

    @Test
    public void acceptLinePunch_shouldStopAfterOneTransportRetry() {
        SessionManager.get().saveToken("token-abc", 1893456000L);
        interceptor.enqueueFailure("timeout-1");
        interceptor.enqueueFailure("timeout-2");

        ApiResult<PunchDto.LineCapacityData> result = ApiService.acceptLinePunch(
                "PDEVICE001_01KXYZ",
                "EMP001",
                "LINE01",
                1782424800L
        );

        assertFalse(result.success);
        assertEquals(-1, result.code);
        assertEquals(2, interceptor.getRequestCount());
    }

    @Test
    public void acceptLinePunch_shouldNotRetryHttp500() {
        SessionManager.get().saveToken("token-abc", 1893456000L);
        interceptor.enqueueJson(500, "{\"code\":500,\"msg\":\"server error\",\"data\":null}");

        ApiResult<PunchDto.LineCapacityData> result = ApiService.acceptLinePunch(
                "PDEVICE001_01KXYZ",
                "EMP001",
                "LINE01",
                1782424800L
        );

        assertFalse(result.success);
        assertEquals(500, result.code);
        assertEquals(1, interceptor.getRequestCount());
    }

    @Test
    public void acceptLinePunch_shouldFailWhenTokenMissing() {
        ApiResult<PunchDto.LineCapacityData> result = ApiService.acceptLinePunch(
                "PDEVICE001_01KXYZ",
                "EMP001",
                "LINE01",
                1782424800L
        );

        assertFalse(result.success);
        assertEquals(401, result.code);
    }

    @Test
    public void acceptLinePunch_shouldParseOverCapacity() {
        SessionManager.get().saveToken("token-abc", 1893456000L);
        interceptor.enqueueJson(200, successEnvelope("{\"is_over_capacity\":true}"));

        ApiResult<PunchDto.LineCapacityData> result = ApiService.acceptLinePunch(
                "PDEVICE001_01KXYZ",
                "EMP001",
                "LINE01",
                1782424800L
        );

        assertTrue(result.success);
        assertTrue(result.data.isOverCapacity);
    }

    @Test
    public void acceptLinePunch_shouldFailWhenResponseMissingCapacityFlag() {
        SessionManager.get().saveToken("token-abc", 1893456000L);
        interceptor.enqueueJson(200, successEnvelope("{}"));

        ApiResult<PunchDto.LineCapacityData> result = ApiService.acceptLinePunch(
                "PDEVICE001_01KXYZ",
                "EMP001",
                "LINE01",
                1782424800L
        );

        assertFalse(result.success);
    }

    @Test
    public void pushPunch_shouldFailWhenTeamBindingMissing() {
        PunchRecord punch = new PunchRecord();
        punch.clientRecordId = "PDEVICE001_01KXYZ";
        punch.empId = "pnFNxH";
        punch.lineCode = "PKZ450";
        punch.punchTime = 1782424800L;

        ApiResult<PunchDto.PunchPushData> result = ApiService.pushPunch(punch);

        assertFalse(result.success);
        assertEquals(400, result.code);
    }

    @Test
    public void pushPunch_shouldSendNewPayloadWhenTeamBindingPresent() throws Exception {
        SessionManager.get().saveToken("token-abc", 1893456000L);
        interceptor.enqueueJson(200, successEnvelope(
                "{" +
                        "\"record_id\":12345," +
                        "\"snap_time\":1782424800," +
                        "\"snap_time_str\":\"08:30:00\"," +
                        "\"dates\":\"2025-07-02\"," +
                        "\"attend_report_id\":67890," +
                        "\"attend_report_table\":\"hzq_attend_report\"" +
                        "}"
        ));

        PunchRecord punch = new PunchRecord();
        punch.clientRecordId = "PDEVICE001_01KXYZ";
        punch.empId = "pnFNxH";
        punch.lineCode = "PKZ450";
        punch.punchTime = 1782424800L;
        punch.teamBindingId = 2;

        ApiResult<PunchDto.PunchPushData> result = ApiService.pushPunch(punch);

        assertTrue(result.success);
        assertEquals("12345", result.data.recordId);
        assertEquals("67890", result.data.attendReportId);

        Request request = interceptor.takeRequest();
        assertEquals("/v3/handheld/clock/upload", request.url().encodedPath());
        assertEquals("Bearer token-abc", request.header("Authorization"));
        String body = interceptor.takeBody();
        assertTrue(body.contains("\"numbers\":\"pnFNxH\""));
        assertTrue(body.contains("\"team_binding\":2"));
        assertTrue(body.contains("\"line_binding_code\":\"PKZ450\""));
        assertTrue(body.contains("\"snap_time\":1782424800"));
        assertTrue(body.contains("\"client_record_id\":\"PDEVICE001_01KXYZ\""));
    }

    @Test
    public void pushPunch_shouldIncludeSnapshotAndMatchScoreWhenProvided() throws Exception {
        SessionManager.get().saveToken("token-abc", 1893456000L);
        interceptor.enqueueJson(200, successEnvelope(
                "{" +
                        "\"record_id\":12345," +
                        "\"snap_time\":1782424800," +
                        "\"snap_time_str\":\"08:30:00\"," +
                        "\"dates\":\"2025-07-02\"," +
                        "\"attend_report_id\":67890," +
                        "\"attend_report_table\":\"hzq_attend_report\"" +
                        "}"
        ));

        File tempFile = File.createTempFile("punch-snap", ".txt");
        tempFile.deleteOnExit();
        Files.write(tempFile.toPath(), "snapshot".getBytes(StandardCharsets.UTF_8));

        PunchRecord punch = new PunchRecord();
        punch.clientRecordId = "PDEVICE001_01KXYZ";
        punch.empId = "pnFNxH";
        punch.lineCode = "PKZ450";
        punch.punchTime = 1782424800L;
        punch.teamBindingId = 2;
        punch.matchScore = 0.93d;
        punch.snapImagePath = tempFile.getAbsolutePath();

        ApiResult<PunchDto.PunchPushData> result = ApiService.pushPunch(punch);

        assertTrue(result.success);

        String body = afterSingleRequestBody(ApiEndpoints.PUNCH);
        assertTrue(body, body.contains("\"snap_image\":\"c25hcHNob3Q"));
        assertTrue(body, body.contains("\"match_score\":0.93"));
    }

    @Test
    public void fetchClockStatistics_shouldSendFormPayloadAndParseRows() throws Exception {
        SessionManager.get().saveToken("token-abc", 1893456000L);
        interceptor.enqueueJson(200, successEnvelope(
                "{" +
                        "\"date\":\"2026-06-27\"," +
                        "\"line_code\":\"PKZ450\"," +
                        "\"clock_index\":1," +
                        "\"clock_index_name\":\"06:00-14:00 上班\"," +
                        "\"summary\":{\"total\":32,\"clocked\":20,\"unclocked\":10,\"special\":2}," +
                        "\"total\":32," +
                        "\"page\":1," +
                        "\"page_size\":20," +
                        "\"rows\":[" +
                        "{\"numbers\":\"pnFNxH\",\"name\":\"张三\",\"line_id\":2,\"line_name\":\"SMT线1\",\"face_path\":\"/face/a.jpg\",\"clock_time\":\"06:03:00\",\"clock_status\":1,\"clock_status_text\":\"已打卡\",\"sign\":\"late\",\"special_text\":\"\",\"late_minutes\":3,\"early_minutes\":0}," +
                        "{\"numbers\":\"ab12cd\",\"name\":\"李四\",\"line_id\":2,\"line_name\":\"SMT线1\",\"face_path\":\"\",\"clock_time\":\"\",\"clock_status\":2,\"clock_status_text\":\"未打卡\",\"sign\":\"absence\",\"special_text\":\"\",\"late_minutes\":0,\"early_minutes\":0}" +
                        "]" +
                        "}"
        ));

        ApiResult<PunchDto.ClockStatisticsData> result = ApiService.fetchClockStatistics(
                "2026-06-27",
                "PKZ450",
                1,
                3,
                1,
                20
        );

        assertTrue(result.success);
        assertEquals(1, result.data.clockIndex);
        assertEquals("06:00-14:00 上班", result.data.clockIndexName);
        assertEquals(32, result.data.summary.total);
        assertEquals(2, result.data.rows.size());
        assertEquals("late", result.data.rows.get(0).sign);
        assertEquals("absence", result.data.rows.get(1).sign);

        Request request = interceptor.takeRequest();
        assertEquals("/v3/handheld/clock/statistics", request.url().encodedPath());
        assertEquals("Bearer token-abc", request.header("Authorization"));
        String body = interceptor.takeBody();
        assertTrue(body.contains("\"dates\":\"2026-06-27\""));
        assertTrue(body.contains("\"line_code\":\"PKZ450\""));
        assertTrue(body.contains("\"clock_index\":1"));
        assertTrue(body.contains("\"clock_status\":3"));
        assertTrue(body.contains("\"page\":1"));
        assertTrue(body.contains("\"page_size\":20"));
    }
}

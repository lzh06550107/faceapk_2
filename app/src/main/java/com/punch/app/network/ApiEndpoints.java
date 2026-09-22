package com.punch.app.network;

public final class ApiEndpoints {
    private static final String HANDHELD = "/v3/handheld";
    public static final String DEVICE_ACTIVATE_BASE_URL = "http://park.hainasmart.com.cn:8898";

    public static final String AUTH_LOGIN = HANDHELD + "/auth/login";
    public static final String AUTH_REFRESH = HANDHELD + "/auth/refresh";
    public static final String DEVICE_REGISTER = HANDHELD + "/device/register";
    public static final String DEVICE_ACTIVATE = "/device/activate";
    public static final String DEVICE_CONFIG = HANDHELD + "/device/get-config";
    public static final String DEVICE_HEARTBEAT = HANDHELD + "/device/heartbeat";
    public static final String EVENT_RESULT = HANDHELD + "/event/result";
    public static final String EMPLOYEE_SYNC = HANDHELD + "/employee/sync";
    public static final String PUNCH = HANDHELD + "/clock/upload";
    public static final String LINE_IS_OVER_CAPACITY = HANDHELD + "/line/isOverCapacity";
    public static final String PUNCH_STATISTICS = HANDHELD + "/clock/statistics";
    public static final String HEALTH = HANDHELD + "/health";

    private ApiEndpoints() {
    }
}

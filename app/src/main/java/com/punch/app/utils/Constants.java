package com.punch.app.utils;

public class Constants {
    public static final String DEFAULT_BASE_URL = "http://192.168.111.240";
    public static final int DEFAULT_COMPANY_ID = 2;

    public static final String PREF_NAME = "punch_prefs";
    public static final String KEY_TOKEN = "token";
    public static final String KEY_TOKEN_EXPIRE = "token_expire_at";
    public static final String KEY_ACCOUNT_PASSWORD = "account_password";
    public static final String KEY_DEVICE_ID = "device_id";
    public static final String KEY_DEVICE_REGISTERED = "device_registered";
    public static final String KEY_DEVICE_CONFIG_INITIALIZED = "device_config_initialized";
    public static final String KEY_SETUP_COMPLETED = "setup_completed";
    public static final String KEY_COMPANY_ID = "company_id";
    public static final String KEY_BASE_URL = "base_url";
    public static final String KEY_KIOSK_ENABLED = "kiosk_enabled";
    public static final String KEY_SCREEN_TIMEOUT_MS = "screen_timeout_ms";
    public static final String KEY_ORIGINAL_SCREEN_SETTINGS_CAPTURED =
            "original_screen_settings_captured";
    public static final String KEY_ORIGINAL_SCREEN_TIMEOUT_MS = "original_screen_timeout_ms";
    public static final String KEY_ORIGINAL_STAY_ON_WHILE_PLUGGED_IN =
            "original_stay_on_while_plugged_in";
    public static final String KEY_ACCOUNT = "account";
    public static final String KEY_LINE_CODE = "line_code";
    public static final String KEY_LINE_NAME = "line_name";
    public static final String KEY_LINE_OPTIONS = "line_options";
    public static final String KEY_TEAM_BINDING_ID = "team_binding_id";
    public static final String KEY_TEAM_BINDING_NAME = "team_binding_name";
    public static final String KEY_TEAM_OPTIONS = "team_options";
    public static final String KEY_TEAM_TIME_RANGES = "team_time_ranges";
    public static final String KEY_CHECK_COUNT = "check_count";
    public static final String KEY_UPDATE_NEED = "update_need";
    public static final String KEY_UPDATE_APK_URL = "update_apk_url";
    public static final String KEY_UPDATE_CURRENT_VERSION = "update_current_version";
    public static final String KEY_UPDATE_TARGET_VERSION = "update_target_version";
    public static final String KEY_UPDATE_VERSION_NAME = "update_version_name";
    public static final String KEY_UPDATE_INSTALL_PENDING = "update_install_pending";
    public static final String KEY_UPDATE_INSTALL_APK_PATH = "update_install_apk_path";
    public static final String KEY_UPDATE_INSTALL_TARGET_VERSION = "update_install_target_version";
    public static final String KEY_UPDATE_INSTALL_TARGET_VERSION_CODE = "update_install_target_version_code";
    public static final String KEY_UPDATE_INSTALL_STATUS = "update_install_status";
    public static final String KEY_UPDATE_INSTALL_MESSAGE = "update_install_message";
    public static final String KEY_UPDATE_INSTALL_RESULT_CODE = "update_install_result_code";
    public static final String KEY_UPDATE_INSTALL_STARTED_AT = "update_install_started_at";
    public static final String KEY_UPDATE_RETRY_PENDING = "update_retry_pending";
    public static final String KEY_UPDATE_RETRY_COUNT = "update_retry_count";
    public static final String KEY_UPDATE_RETRY_NEXT_AT = "update_retry_next_at";
    public static final String KEY_UPDATE_RETRY_LAST_ERROR = "update_retry_last_error";
    public static final String KEY_UPDATE_RETRY_TARGET_VERSION = "update_retry_target_version";
    public static final String KEY_UPDATE_RETRY_APK_URL = "update_retry_apk_url";
    public static final String KEY_UPDATE_AUTO_LAUNCH_SCHEDULED = "update_auto_launch_scheduled";
    public static final String KEY_UPDATE_AUTO_LAUNCH_COMPLETED = "update_auto_launch_completed";
    public static final String KEY_UPDATE_RELAUNCH_VERSION_CODE = "update_relaunch_version_code";
    public static final String KEY_UPDATE_RELAUNCH_PHASE = "update_relaunch_phase";
    public static final String KEY_UPDATE_RELAUNCH_ATTEMPT = "update_relaunch_attempt";
    public static final String KEY_UPDATE_RELAUNCH_STARTED_ELAPSED = "update_relaunch_started_elapsed";
    public static final String KEY_UPDATE_RELAUNCH_LAST_LAUNCH_ELAPSED =
            "update_relaunch_last_launch_elapsed";
    public static final String UPDATE_RELAUNCH_PHASE_IDLE = "idle";
    public static final String UPDATE_RELAUNCH_PHASE_WAITING = "waiting";
    public static final String UPDATE_RELAUNCH_PHASE_LAUNCHING = "launching";
    public static final String UPDATE_RELAUNCH_PHASE_UI_ACKED = "ui_acked";
    public static final String UPDATE_RELAUNCH_PHASE_EXHAUSTED = "exhausted";
    public static final String KEY_EMP_DATA_VERSION = "emp_data_version";
    public static final String KEY_MATCH_THRESHOLD = "match_threshold";
    public static final String KEY_FACE_THRESHOLD = "face_threshold";
    public static final String KEY_LIVENESS_CHECK = "liveness_check";
    public static final String KEY_LIVENESS_THRESHOLD = "liveness_threshold";
    public static final String KEY_RECOGNITION_DISTANCE_MODE = "recognition_distance_mode";
    public static final String KEY_MASK_DETECT = "mask_detect";
    public static final String KEY_RECOGNITION_TIMEOUT_SECONDS = "recognition_timeout_seconds";
    public static final String KEY_FAST_PUNCH_ENABLED = "fast_punch_enabled";
    public static final String KEY_SHOW_PUNCH_RESULT_CARD = "show_punch_result_card";
    public static final String KEY_PUNCH_RESULT_DISPLAY_MS = "punch_result_display_ms";
    public static final String KEY_PUNCH_SPEECH_MODE = "punch_speech_mode";
    public static final String KEY_PUNCH_SPEECH_RATE = "punch_speech_rate";
    public static final String KEY_RECOGNITION_FRAME_INTERVAL_MS = "recognition_frame_interval_ms";
    public static final String KEY_SUCCESS_COOLDOWN_MS = "success_cooldown_ms";
    public static final String KEY_CAMERA_FACING = "camera_facing";
    public static final String KEY_SOUND_ENABLED = "sound_enabled";
    public static final String KEY_ACTIVATION_MODE = "activation_mode";
    public static final String KEY_ACTIVATION_CODE = "activation_code";
    public static final String KEY_ACTIVATION_STATUS = "activation_status";
    public static final String KEY_LAST_ACTIVATION_CODE = "last_activation_code";
    public static final String KEY_LAST_ACTIVATION_MESSAGE = "last_activation_message";
    public static final String KEY_LAST_ACTIVATION_TIME = "last_activation_time";
    public static final String KEY_LAST_HEARTBEAT_TIME = "last_heartbeat_time";
    public static final String KEY_LAST_SERVER_TIME = "last_server_time";
    public static final String KEY_ADVANCED_SETTINGS_PASSWORD = "advanced_settings_password";
    public static final String KEY_LAST_WIFI_SSID = "last_wifi_ssid";
    public static final String KEY_LAST_WIFI_PASSWORD = "last_wifi_password";
    public static final String ADVANCED_SETTINGS_PASSWORD = "8899";

    public static final int TOKEN_VALID_DAYS = 7;
    public static final int TOKEN_REFRESH_HOURS = 24;

    public static final int SYNC_INTERVAL_MINUTES = 5;
    public static final int SYNC_MAX_RETRY = 3;
    public static final int PUNCH_BATCH_SIZE = 50;
    public static final long PUNCH_SYNC_TIME_BUDGET_MS = 30_000L;
    public static final long HEARTBEAT_INITIAL_DELAY_MS = 30_000L;
    public static final long HEARTBEAT_INTERVAL_MS = 60_000L;

    public static final String PUNCH_TYPE_SIGN_IN = "sign_in";
    public static final String PUNCH_TYPE_SIGN_OUT = "sign_out";

    public static final String STATUS_NORMAL = "normal";
    public static final String STATUS_LEAVE = "leave";
    public static final String STATUS_REST = "rest";

    public static final String ACTION_PUNCH_PUSH = "punch_push";
    public static final String ACTION_PUNCH_STRESS_NO_UPLOAD = "stress_punch_no_upload";

    public static final String UPDATE_INSTALL_STATUS_NONE = "none";
    public static final String UPDATE_INSTALL_STATUS_PENDING = "pending";
    public static final String UPDATE_INSTALL_STATUS_SUCCESS = "success";
    public static final String UPDATE_INSTALL_STATUS_FAILED = "failed";

    public static final float DEFAULT_MATCH_THRESHOLD = 0.80f;
    public static final float DEFAULT_FACE_THRESHOLD = 0.80f;
    public static final float DEFAULT_LIVENESS_THRESHOLD = 0.80f;
    public static final boolean DEFAULT_LIVENESS_CHECK = false;
    public static final int DEFAULT_MIN_FACE_SIZE = 80;
    public static final String DISTANCE_MODE_NEAR = "near";
    public static final String DISTANCE_MODE_STANDARD = "standard";
    public static final String DISTANCE_MODE_FAR = "far";
    public static final String DEFAULT_DISTANCE_MODE = DISTANCE_MODE_STANDARD;
    public static final boolean DEFAULT_MASK_DETECT = false;
    public static final int DEFAULT_RECOGNITION_TIMEOUT_SECONDS = 5;
    public static final boolean DEFAULT_FAST_PUNCH_ENABLED = true;
    public static final boolean DEFAULT_SHOW_PUNCH_RESULT_CARD = false;
    public static final int DEFAULT_PUNCH_RESULT_DISPLAY_MS = 3000;
    public static final String PUNCH_SPEECH_MODE_FULL = "full";
    public static final String PUNCH_SPEECH_MODE_NAME = "name";
    public static final String PUNCH_SPEECH_MODE_SUCCESS = "success";
    public static final String DEFAULT_PUNCH_SPEECH_MODE = PUNCH_SPEECH_MODE_NAME;
    public static final float DEFAULT_PUNCH_SPEECH_RATE = 1.3f;
    public static final int DEFAULT_RECOGNITION_FRAME_INTERVAL_MS = 350;
    public static final int DEFAULT_SUCCESS_COOLDOWN_MS = 500;

    public static final String ACTIVATION_MODE_ONLINE = "online";
    public static final String ACTIVATION_MODE_OFFLINE_ZIP = "offline_zip";
    public static final String ACTIVATION_STATUS_PENDING = "pending";
    public static final String ACTIVATION_STATUS_SUCCESS = "success";
    public static final String ACTIVATION_STATUS_FAILED = "failed";
    public static final String ACTIVATION_STATUS_DISABLED = "disabled";

    public static final String DB_NAME = "punch.db";
    public static final int DB_VERSION = 12;
}

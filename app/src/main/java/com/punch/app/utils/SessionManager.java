package com.punch.app.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import com.punch.app.activation.BaiduDeviceFingerprint;
import com.punch.app.network.dto.DeviceDto;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Pattern;

public class SessionManager {
    private static final String TAG = "SessionManager";
    private static final int DEVICE_ID_LENGTH = 16;
    private static final int MAX_DEVICE_ID_LENGTH = 64;
    private static final Pattern DEVICE_ID_PATTERN = Pattern.compile("^[0-9A-Za-z_-]{4,64}$");

    private static SessionManager instance;
    private static String resolvedDeviceIdForTest;

    private Context appContext;
    private SharedPreferences prefs;
    private SharedPreferences securePrefs;

    private SessionManager() {
    }

    public static SessionManager get() {
        if (instance == null) {
            instance = new SessionManager();
        }
        return instance;
    }

    public void init(Context context) {
        appContext = context.getApplicationContext();
        prefs = context.getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE);
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            securePrefs = EncryptedSharedPreferences.create(
                    context,
                    "punch_secure",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) {
            Log.e(TAG, "EncryptedPrefs init failed, fallback", e);
            securePrefs = context.getSharedPreferences("punch_secure", Context.MODE_PRIVATE);
        }
    }

    public void saveToken(String token, long expireAt) {
        securePrefs.edit()
                .putString(Constants.KEY_TOKEN, token)
                .putLong(Constants.KEY_TOKEN_EXPIRE, expireAt)
                .apply();
    }

    public String getToken() {
        return securePrefs.getString(Constants.KEY_TOKEN, null);
    }

    public long getTokenExpireAt() {
        return securePrefs.getLong(Constants.KEY_TOKEN_EXPIRE, 0);
    }

    public boolean isTokenValid() {
        return getTokenExpireAt() > System.currentTimeMillis() / 1000L;
    }

    public boolean isTokenNearExpiry() {
        long expireAt = getTokenExpireAt();
        long now = System.currentTimeMillis() / 1000L;
        return expireAt > now && (expireAt - now) < Constants.TOKEN_REFRESH_HOURS * 3600L;
    }

    public void clearToken() {
        securePrefs.edit()
                .remove(Constants.KEY_TOKEN)
                .remove(Constants.KEY_TOKEN_EXPIRE)
                .apply();
    }

    public void clearLoginState() {
        clearToken();
        prefs.edit()
                .remove(Constants.KEY_ACCOUNT)
                .remove(Constants.KEY_LAST_HEARTBEAT_TIME)
                .remove(Constants.KEY_LAST_SERVER_TIME)
                .apply();
        securePrefs.edit()
                .remove(Constants.KEY_ACCOUNT_PASSWORD)
                .apply();
    }

    public String getOrCreateDeviceId() {
        String id = prefs.getString(Constants.KEY_DEVICE_ID, null);
        String preferredId = resolveDeviceId();
        if (!preferredId.isEmpty() && !preferredId.equals(id)) {
            id = replaceDeviceId(preferredId, true);
        } else if (!preferredId.isEmpty()) {
            id = preferredId;
        }
        if (id == null || id.isEmpty()) {
            id = resolveDeviceId();
            prefs.edit().putString(Constants.KEY_DEVICE_ID, id).apply();
        } else if (!isValidDeviceId(id)) {
            id = rebuildDeviceId();
        }
        return id;
    }

    public String getDeviceId() {
        return getOrCreateDeviceId();
    }

    public void saveDeviceId(String deviceId) {
        String safeDeviceId = resolveDeviceId();
        if (safeDeviceId.isEmpty()) {
            return;
        }
        prefs.edit().putString(Constants.KEY_DEVICE_ID, safeDeviceId).apply();
    }

    public String rebuildDeviceId() {
        String id = resolveDeviceId();
        return replaceDeviceId(id, true);
    }

    public boolean isDeviceRegistered() {
        return prefs.getBoolean(Constants.KEY_DEVICE_REGISTERED, false);
    }

    public void saveDeviceRegistered(boolean registered) {
        prefs.edit().putBoolean(Constants.KEY_DEVICE_REGISTERED, registered).apply();
    }

    public boolean isDeviceConfigInitialized() {
        return prefs.getBoolean(Constants.KEY_DEVICE_CONFIG_INITIALIZED, false);
    }

    public void saveDeviceConfigInitialized(boolean initialized) {
        prefs.edit().putBoolean(Constants.KEY_DEVICE_CONFIG_INITIALIZED, initialized).apply();
    }

    public void clearServerBoundState() {
        clearToken();
        prefs.edit()
                .putBoolean(Constants.KEY_DEVICE_REGISTERED, false)
                .putBoolean(Constants.KEY_DEVICE_CONFIG_INITIALIZED, false)
                .remove(Constants.KEY_LINE_CODE)
                .remove(Constants.KEY_LINE_NAME)
                .remove(Constants.KEY_LINE_OPTIONS)
                .remove(Constants.KEY_TEAM_BINDING_ID)
                .remove(Constants.KEY_TEAM_BINDING_NAME)
                .remove(Constants.KEY_TEAM_OPTIONS)
                .remove(Constants.KEY_TEAM_TIME_RANGES)
                .remove(Constants.KEY_CHECK_COUNT)
                .remove(Constants.KEY_UPDATE_NEED)
                .remove(Constants.KEY_UPDATE_APK_URL)
                .remove(Constants.KEY_UPDATE_CURRENT_VERSION)
                .remove(Constants.KEY_UPDATE_TARGET_VERSION)
                .remove(Constants.KEY_UPDATE_VERSION_NAME)
                .remove(Constants.KEY_LAST_HEARTBEAT_TIME)
                .remove(Constants.KEY_LAST_SERVER_TIME)
                .apply();
    }

    public boolean isSetupCompleted() {
        return prefs.getBoolean(Constants.KEY_SETUP_COMPLETED, false);
    }

    public void saveSetupCompleted(boolean completed) {
        prefs.edit().putBoolean(Constants.KEY_SETUP_COMPLETED, completed).apply();
    }

    public void saveCompanyId(int companyId) {
        prefs.edit().putInt(Constants.KEY_COMPANY_ID, companyId > 0 ? companyId : Constants.DEFAULT_COMPANY_ID).apply();
    }

    public int getCompanyId() {
        int companyId = prefs.getInt(Constants.KEY_COMPANY_ID, Constants.DEFAULT_COMPANY_ID);
        return companyId > 0 ? companyId : Constants.DEFAULT_COMPANY_ID;
    }

    public void saveBaseUrl(String baseUrl) {
        prefs.edit()
                .putString(Constants.KEY_BASE_URL, normalizeBaseUrl(baseUrl))
                .apply();
    }

    public String getBaseUrl() {
        if (prefs == null) {
            return Constants.DEFAULT_BASE_URL;
        }
        return normalizeBaseUrl(prefs.getString(Constants.KEY_BASE_URL, Constants.DEFAULT_BASE_URL));
    }

    public void saveKioskEnabled(boolean enabled) {
        prefs.edit().putBoolean(Constants.KEY_KIOSK_ENABLED, enabled).apply();
    }

    public boolean isKioskEnabled() {
        if (prefs == null) {
            return true;
        }
        return prefs.getBoolean(Constants.KEY_KIOSK_ENABLED, true);
    }

    public long getScreenTimeoutMs() {
        long timeoutMs = prefs.getLong(
                Constants.KEY_SCREEN_TIMEOUT_MS,
                ScreenTimeoutPolicy.DEFAULT_TIMEOUT_MS
        );
        return ScreenTimeoutPolicy.isSupportedTimeoutMs(timeoutMs)
                ? timeoutMs
                : ScreenTimeoutPolicy.DEFAULT_TIMEOUT_MS;
    }

    public boolean saveScreenTimeoutMs(long timeoutMs) {
        long safeTimeoutMs = ScreenTimeoutPolicy.isSupportedTimeoutMs(timeoutMs)
                ? timeoutMs
                : ScreenTimeoutPolicy.DEFAULT_TIMEOUT_MS;
        return prefs.edit().putLong(Constants.KEY_SCREEN_TIMEOUT_MS, safeTimeoutMs).commit();
    }

    public boolean hasOriginalScreenSettings() {
        return prefs.getBoolean(Constants.KEY_ORIGINAL_SCREEN_SETTINGS_CAPTURED, false);
    }

    public boolean saveOriginalScreenSettings(long timeoutMs, int stayOnWhilePluggedIn) {
        return prefs.edit()
                .putBoolean(Constants.KEY_ORIGINAL_SCREEN_SETTINGS_CAPTURED, true)
                .putLong(Constants.KEY_ORIGINAL_SCREEN_TIMEOUT_MS, timeoutMs)
                .putInt(Constants.KEY_ORIGINAL_STAY_ON_WHILE_PLUGGED_IN,
                        stayOnWhilePluggedIn)
                .commit();
    }

    public long getOriginalScreenTimeoutMs() {
        return prefs.getLong(Constants.KEY_ORIGINAL_SCREEN_TIMEOUT_MS, 60_000L);
    }

    public int getOriginalStayOnWhilePluggedIn() {
        return prefs.getInt(Constants.KEY_ORIGINAL_STAY_ON_WHILE_PLUGGED_IN, 0);
    }

    public boolean clearOriginalScreenSettings() {
        return prefs.edit()
                .remove(Constants.KEY_ORIGINAL_SCREEN_SETTINGS_CAPTURED)
                .remove(Constants.KEY_ORIGINAL_SCREEN_TIMEOUT_MS)
                .remove(Constants.KEY_ORIGINAL_STAY_ON_WHILE_PLUGGED_IN)
                .commit();
    }

    public void saveAdvancedSettingsPassword(String password) {
        String safePassword = password == null ? "" : password.trim();
        if (safePassword.isEmpty()) {
            safePassword = Constants.ADVANCED_SETTINGS_PASSWORD;
        }
        securePrefs.edit()
                .putString(Constants.KEY_ADVANCED_SETTINGS_PASSWORD, safePassword)
                .apply();
    }

    public String getAdvancedSettingsPassword() {
        if (securePrefs == null) {
            return Constants.ADVANCED_SETTINGS_PASSWORD;
        }
        String password = securePrefs.getString(
                Constants.KEY_ADVANCED_SETTINGS_PASSWORD,
                Constants.ADVANCED_SETTINGS_PASSWORD
        );
        if (password == null || password.trim().isEmpty()) {
            return Constants.ADVANCED_SETTINGS_PASSWORD;
        }
        return password.trim();
    }

    public void saveAccount(String account) {
        prefs.edit().putString(Constants.KEY_ACCOUNT, account).apply();
    }

    public String getAccount() {
        return prefs.getString(Constants.KEY_ACCOUNT, "");
    }

    public void savePassword(String password) {
        securePrefs.edit()
                .putString(Constants.KEY_ACCOUNT_PASSWORD, password == null ? "" : password)
                .apply();
    }

    public String getPassword() {
        return securePrefs.getString(Constants.KEY_ACCOUNT_PASSWORD, "");
    }

    public void saveLastWifiConfig(String ssid, String password) {
        String safeSsid = ssid == null ? "" : ssid.trim();
        prefs.edit()
                .putString(Constants.KEY_LAST_WIFI_SSID, safeSsid)
                .apply();
        securePrefs.edit()
                .putString(Constants.KEY_LAST_WIFI_PASSWORD, password == null ? "" : password)
                .apply();
    }

    public String getLastWifiSsid() {
        return prefs.getString(Constants.KEY_LAST_WIFI_SSID, "");
    }

    public String getLastWifiPassword() {
        return securePrefs.getString(Constants.KEY_LAST_WIFI_PASSWORD, "");
    }

    public void saveLineBinding(String code, String name) {
        prefs.edit()
                .putString(Constants.KEY_LINE_CODE, code)
                .putString(Constants.KEY_LINE_NAME, name)
                .apply();
    }

    public String getLineCode() {
        return prefs.getString(Constants.KEY_LINE_CODE, "");
    }

    public String getLineName() {
        return prefs.getString(Constants.KEY_LINE_NAME, "");
    }

    public void saveLineBindingOptions(List<DeviceDto.LineOptionData> options) {
        JSONArray array = new JSONArray();
        if (options != null) {
            for (DeviceDto.LineOptionData option : options) {
                if (option == null) {
                    continue;
                }
                JSONObject item = new JSONObject();
                try {
                    item.put("code", option.code == null ? "" : option.code);
                    item.put("name", option.name == null ? "" : option.name);
                    array.put(item);
                } catch (JSONException e) {
                    Log.w(TAG, "Failed to encode line option", e);
                }
            }
        }
        prefs.edit().putString(Constants.KEY_LINE_OPTIONS, array.toString()).apply();
    }

    public List<DeviceDto.LineOptionData> getLineBindingOptions() {
        String json = prefs.getString(Constants.KEY_LINE_OPTIONS, "");
        if (json == null || json.trim().isEmpty()) {
            return Collections.emptyList();
        }
        try {
            JSONArray array = new JSONArray(json);
            List<DeviceDto.LineOptionData> options = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                DeviceDto.LineOptionData option = new DeviceDto.LineOptionData();
                option.code = item.optString("code", "").trim();
                option.name = item.optString("name", "").trim();
                if (!option.code.isEmpty() || !option.name.isEmpty()) {
                    options.add(option);
                }
            }
            return options;
        } catch (JSONException e) {
            Log.w(TAG, "Failed to parse line options", e);
            return Collections.emptyList();
        }
    }

    public void saveTeamBindingId(int teamBindingId) {
        prefs.edit().putInt(Constants.KEY_TEAM_BINDING_ID, teamBindingId).apply();
    }

    public int getTeamBindingId() {
        return prefs.getInt(Constants.KEY_TEAM_BINDING_ID, 0);
    }

    public void saveTeamBindingName(String teamBindingName) {
        prefs.edit()
                .putString(Constants.KEY_TEAM_BINDING_NAME, teamBindingName == null ? "" : teamBindingName)
                .apply();
    }

    public String getTeamBindingName() {
        return prefs.getString(Constants.KEY_TEAM_BINDING_NAME, "");
    }

    public void saveTeamBindingOptions(List<DeviceDto.TeamOptionData> options) {
        JSONArray array = new JSONArray();
        if (options != null) {
            for (DeviceDto.TeamOptionData option : options) {
                if (option == null) {
                    continue;
                }
                JSONObject item = new JSONObject();
                try {
                    item.put("id", option.id);
                    item.put("name", option.name == null ? "" : option.name);
                    JSONArray timeRanges = new JSONArray();
                    if (option.timeRanges != null) {
                        for (String timeRange : option.timeRanges) {
                            if (timeRange == null || timeRange.trim().isEmpty()) {
                                continue;
                            }
                            timeRanges.put(timeRange.trim());
                        }
                    }
                    item.put("time_ranges", timeRanges);
                    array.put(item);
                } catch (JSONException e) {
                    Log.w(TAG, "Failed to encode team option", e);
                }
            }
        }
        prefs.edit().putString(Constants.KEY_TEAM_OPTIONS, array.toString()).apply();
    }

    public List<DeviceDto.TeamOptionData> getTeamBindingOptions() {
        String json = prefs.getString(Constants.KEY_TEAM_OPTIONS, "");
        if (json == null || json.trim().isEmpty()) {
            return Collections.emptyList();
        }
        try {
            JSONArray array = new JSONArray(json);
            List<DeviceDto.TeamOptionData> options = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                DeviceDto.TeamOptionData option = new DeviceDto.TeamOptionData();
                option.id = item.optInt("id", 0);
                option.name = item.optString("name", "").trim();
                JSONArray timeRanges = item.optJSONArray("time_ranges");
                if (timeRanges != null) {
                    for (int j = 0; j < timeRanges.length(); j++) {
                        String timeRange = timeRanges.optString(j, "").trim();
                        if (!timeRange.isEmpty()) {
                            option.timeRanges.add(timeRange);
                        }
                    }
                }
                if (option.id > 0 || !option.name.isEmpty()) {
                    options.add(option);
                }
            }
            return options;
        } catch (JSONException e) {
            Log.w(TAG, "Failed to parse team options", e);
            return Collections.emptyList();
        }
    }

    public void saveCurrentTeamTimeRanges(List<String> timeRanges) {
        JSONArray array = new JSONArray();
        if (timeRanges != null) {
            for (String timeRange : timeRanges) {
                if (timeRange == null) {
                    continue;
                }
                String value = timeRange.trim();
                if (!value.isEmpty()) {
                    array.put(value);
                }
            }
        }
        prefs.edit()
                .putString(Constants.KEY_TEAM_TIME_RANGES, array.toString())
                .apply();
    }

    public List<String> getCurrentTeamTimeRanges() {
        String json = prefs.getString(Constants.KEY_TEAM_TIME_RANGES, "");
        if (json == null || json.trim().isEmpty()) {
            return Collections.emptyList();
        }
        try {
            JSONArray array = new JSONArray(json);
            List<String> ranges = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) {
                String value = array.optString(i, "").trim();
                if (!value.isEmpty()) {
                    ranges.add(value);
                }
            }
            return ranges;
        } catch (JSONException e) {
            Log.w(TAG, "Failed to parse team time ranges", e);
            return Collections.emptyList();
        }
    }

    public void saveCheckCount(int checkCount) {
        prefs.edit().putInt(Constants.KEY_CHECK_COUNT, Math.max(0, checkCount)).apply();
    }

    public int getCheckCount() {
        return prefs.getInt(Constants.KEY_CHECK_COUNT, 0);
    }

    public void saveUpdateInfo(boolean needUpdate, String apkUrl, String currentVersion, String targetVersion, String versionName) {
        prefs.edit()
                .putBoolean(Constants.KEY_UPDATE_NEED, needUpdate)
                .putString(Constants.KEY_UPDATE_APK_URL, apkUrl == null ? "" : apkUrl)
                .putString(Constants.KEY_UPDATE_CURRENT_VERSION, currentVersion == null ? "" : currentVersion)
                .putString(Constants.KEY_UPDATE_TARGET_VERSION, targetVersion == null ? "" : targetVersion)
                .putString(Constants.KEY_UPDATE_VERSION_NAME, versionName == null ? "" : versionName)
                .apply();
    }

    public boolean isUpdateNeeded() {
        return prefs.getBoolean(Constants.KEY_UPDATE_NEED, false);
    }

    public String getUpdateTargetVersion() {
        return prefs.getString(Constants.KEY_UPDATE_TARGET_VERSION, "");
    }

    public String getUpdateApkUrl() {
        return prefs.getString(Constants.KEY_UPDATE_APK_URL, "");
    }

    public String getUpdateCurrentVersion() {
        return prefs.getString(Constants.KEY_UPDATE_CURRENT_VERSION, "");
    }

    public String getUpdateVersionName() {
        return prefs.getString(Constants.KEY_UPDATE_VERSION_NAME, "");
    }

    public void saveUpdateRetryState(int retryCount,
                                     long nextRetryAt,
                                     String lastError,
                                     String targetVersion,
                                     String apkUrl) {
        prefs.edit()
                .putBoolean(Constants.KEY_UPDATE_RETRY_PENDING, true)
                .putInt(Constants.KEY_UPDATE_RETRY_COUNT, Math.max(0, retryCount))
                .putLong(Constants.KEY_UPDATE_RETRY_NEXT_AT, Math.max(0L, nextRetryAt))
                .putString(Constants.KEY_UPDATE_RETRY_LAST_ERROR,
                        lastError == null ? "" : lastError.trim())
                .putString(Constants.KEY_UPDATE_RETRY_TARGET_VERSION,
                        targetVersion == null ? "" : targetVersion.trim())
                .putString(Constants.KEY_UPDATE_RETRY_APK_URL,
                        apkUrl == null ? "" : apkUrl.trim())
                .commit();
    }

    public boolean isUpdateRetryPending() {
        return prefs.getBoolean(Constants.KEY_UPDATE_RETRY_PENDING, false);
    }

    public int getUpdateRetryCount() {
        return Math.max(0, prefs.getInt(Constants.KEY_UPDATE_RETRY_COUNT, 0));
    }

    public long getUpdateRetryNextAt() {
        return Math.max(0L, prefs.getLong(Constants.KEY_UPDATE_RETRY_NEXT_AT, 0L));
    }

    public String getUpdateRetryLastError() {
        return prefs.getString(Constants.KEY_UPDATE_RETRY_LAST_ERROR, "");
    }

    public String getUpdateRetryTargetVersion() {
        return prefs.getString(Constants.KEY_UPDATE_RETRY_TARGET_VERSION, "");
    }

    public String getUpdateRetryApkUrl() {
        return prefs.getString(Constants.KEY_UPDATE_RETRY_APK_URL, "");
    }

    public void clearUpdateRetryState() {
        prefs.edit()
                .putBoolean(Constants.KEY_UPDATE_RETRY_PENDING, false)
                .remove(Constants.KEY_UPDATE_RETRY_COUNT)
                .remove(Constants.KEY_UPDATE_RETRY_NEXT_AT)
                .remove(Constants.KEY_UPDATE_RETRY_LAST_ERROR)
                .remove(Constants.KEY_UPDATE_RETRY_TARGET_VERSION)
                .remove(Constants.KEY_UPDATE_RETRY_APK_URL)
                .commit();
    }

    public void markUpdateInstallStarted(String apkPath, String targetVersion) {
        markUpdateInstallStarted(apkPath, targetVersion, 0L);
    }

    public void markUpdateInstallStarted(String apkPath, String targetVersion, long targetVersionCode) {
        prefs.edit()
                .putBoolean(Constants.KEY_UPDATE_INSTALL_PENDING, true)
                .putString(Constants.KEY_UPDATE_INSTALL_APK_PATH, apkPath == null ? "" : apkPath)
                .putString(Constants.KEY_UPDATE_INSTALL_TARGET_VERSION, targetVersion == null ? "" : targetVersion)
                .putLong(Constants.KEY_UPDATE_INSTALL_TARGET_VERSION_CODE, Math.max(0L, targetVersionCode))
                .putString(Constants.KEY_UPDATE_INSTALL_STATUS, Constants.UPDATE_INSTALL_STATUS_PENDING)
                .putString(Constants.KEY_UPDATE_INSTALL_MESSAGE, "")
                .putInt(Constants.KEY_UPDATE_INSTALL_RESULT_CODE, Integer.MIN_VALUE)
                .putLong(Constants.KEY_UPDATE_INSTALL_STARTED_AT, System.currentTimeMillis())
                .putBoolean(Constants.KEY_UPDATE_AUTO_LAUNCH_SCHEDULED, false)
                .putBoolean(Constants.KEY_UPDATE_AUTO_LAUNCH_COMPLETED, false)
                .remove(Constants.KEY_UPDATE_RELAUNCH_VERSION_CODE)
                .putString(Constants.KEY_UPDATE_RELAUNCH_PHASE,
                        Constants.UPDATE_RELAUNCH_PHASE_IDLE)
                .remove(Constants.KEY_UPDATE_RELAUNCH_ATTEMPT)
                .remove(Constants.KEY_UPDATE_RELAUNCH_STARTED_ELAPSED)
                .remove(Constants.KEY_UPDATE_RELAUNCH_LAST_LAUNCH_ELAPSED)
                .commit();
    }

    public void markUpdateInstallResult(String status, String message, int resultCode) {
        String safeStatus = status == null || status.trim().isEmpty()
                ? Constants.UPDATE_INSTALL_STATUS_NONE
                : status.trim();
        prefs.edit()
                .putBoolean(Constants.KEY_UPDATE_INSTALL_PENDING,
                        Constants.UPDATE_INSTALL_STATUS_PENDING.equals(safeStatus))
                .putString(Constants.KEY_UPDATE_INSTALL_STATUS, safeStatus)
                .putString(Constants.KEY_UPDATE_INSTALL_MESSAGE, message == null ? "" : message.trim())
                .putInt(Constants.KEY_UPDATE_INSTALL_RESULT_CODE, resultCode)
                .apply();
    }

    public boolean isUpdateInstallPending() {
        return prefs.getBoolean(Constants.KEY_UPDATE_INSTALL_PENDING, false);
    }

    public String getUpdateInstallApkPath() {
        return prefs.getString(Constants.KEY_UPDATE_INSTALL_APK_PATH, "");
    }

    public String getUpdateInstallTargetVersion() {
        return prefs.getString(Constants.KEY_UPDATE_INSTALL_TARGET_VERSION, "");
    }

    public long getUpdateInstallTargetVersionCode() {
        return prefs.getLong(Constants.KEY_UPDATE_INSTALL_TARGET_VERSION_CODE, 0L);
    }

    public String getUpdateInstallStatus() {
        return prefs.getString(Constants.KEY_UPDATE_INSTALL_STATUS, Constants.UPDATE_INSTALL_STATUS_NONE);
    }

    public String getUpdateInstallMessage() {
        return prefs.getString(Constants.KEY_UPDATE_INSTALL_MESSAGE, "");
    }

    public int getUpdateInstallResultCode() {
        return prefs.getInt(Constants.KEY_UPDATE_INSTALL_RESULT_CODE, Integer.MIN_VALUE);
    }

    public long getUpdateInstallStartedAt() {
        return prefs.getLong(Constants.KEY_UPDATE_INSTALL_STARTED_AT, 0L);
    }

    public void clearUpdateInstallState() {
        prefs.edit()
                .putBoolean(Constants.KEY_UPDATE_INSTALL_PENDING, false)
                .remove(Constants.KEY_UPDATE_INSTALL_APK_PATH)
                .remove(Constants.KEY_UPDATE_INSTALL_TARGET_VERSION)
                .remove(Constants.KEY_UPDATE_INSTALL_TARGET_VERSION_CODE)
                .putString(Constants.KEY_UPDATE_INSTALL_STATUS, Constants.UPDATE_INSTALL_STATUS_NONE)
                .remove(Constants.KEY_UPDATE_INSTALL_MESSAGE)
                .remove(Constants.KEY_UPDATE_INSTALL_RESULT_CODE)
                .remove(Constants.KEY_UPDATE_INSTALL_STARTED_AT)
                .remove(Constants.KEY_UPDATE_AUTO_LAUNCH_SCHEDULED)
                .remove(Constants.KEY_UPDATE_AUTO_LAUNCH_COMPLETED)
                .remove(Constants.KEY_UPDATE_RELAUNCH_VERSION_CODE)
                .remove(Constants.KEY_UPDATE_RELAUNCH_PHASE)
                .remove(Constants.KEY_UPDATE_RELAUNCH_ATTEMPT)
                .remove(Constants.KEY_UPDATE_RELAUNCH_STARTED_ELAPSED)
                .remove(Constants.KEY_UPDATE_RELAUNCH_LAST_LAUNCH_ELAPSED)
                .apply();
    }

    public void beginUpdateRelaunch(long versionCode, long startedElapsedRealtime) {
        prefs.edit()
                .putLong(Constants.KEY_UPDATE_RELAUNCH_VERSION_CODE, Math.max(0L, versionCode))
                .putString(Constants.KEY_UPDATE_RELAUNCH_PHASE,
                        Constants.UPDATE_RELAUNCH_PHASE_WAITING)
                .putInt(Constants.KEY_UPDATE_RELAUNCH_ATTEMPT, 0)
                .putLong(Constants.KEY_UPDATE_RELAUNCH_STARTED_ELAPSED,
                        Math.max(0L, startedElapsedRealtime))
                .putLong(Constants.KEY_UPDATE_RELAUNCH_LAST_LAUNCH_ELAPSED, 0L)
                .remove(Constants.KEY_UPDATE_AUTO_LAUNCH_SCHEDULED)
                .remove(Constants.KEY_UPDATE_AUTO_LAUNCH_COMPLETED)
                .commit();
    }

    public long getUpdateRelaunchVersionCode() {
        return prefs.getLong(Constants.KEY_UPDATE_RELAUNCH_VERSION_CODE, 0L);
    }

    public String getUpdateRelaunchPhase() {
        return prefs.getString(
                Constants.KEY_UPDATE_RELAUNCH_PHASE,
                Constants.UPDATE_RELAUNCH_PHASE_IDLE
        );
    }

    public int getUpdateRelaunchAttempt() {
        return Math.max(0, prefs.getInt(Constants.KEY_UPDATE_RELAUNCH_ATTEMPT, 0));
    }

    public long getUpdateRelaunchStartedElapsed() {
        return prefs.getLong(Constants.KEY_UPDATE_RELAUNCH_STARTED_ELAPSED, 0L);
    }

    public long getUpdateRelaunchLastLaunchElapsed() {
        return prefs.getLong(Constants.KEY_UPDATE_RELAUNCH_LAST_LAUNCH_ELAPSED, 0L);
    }

    public void markUpdateRelaunchLaunching(int nextAttempt, long launchElapsedRealtime) {
        prefs.edit()
                .putString(Constants.KEY_UPDATE_RELAUNCH_PHASE,
                        Constants.UPDATE_RELAUNCH_PHASE_LAUNCHING)
                .putInt(Constants.KEY_UPDATE_RELAUNCH_ATTEMPT, Math.max(0, nextAttempt))
                .putLong(Constants.KEY_UPDATE_RELAUNCH_LAST_LAUNCH_ELAPSED,
                        Math.max(0L, launchElapsedRealtime))
                .commit();
    }

    public void acknowledgeUpdateRelaunch() {
        prefs.edit()
                .putString(Constants.KEY_UPDATE_RELAUNCH_PHASE,
                        Constants.UPDATE_RELAUNCH_PHASE_UI_ACKED)
                .commit();
    }

    public void exhaustUpdateRelaunch() {
        prefs.edit()
                .putString(Constants.KEY_UPDATE_RELAUNCH_PHASE,
                        Constants.UPDATE_RELAUNCH_PHASE_EXHAUSTED)
                .commit();
    }

    public boolean isUpdateAutoLaunchScheduled() {
        return prefs.getBoolean(Constants.KEY_UPDATE_AUTO_LAUNCH_SCHEDULED, false);
    }

    public boolean isUpdateAutoLaunchCompleted() {
        return prefs.getBoolean(Constants.KEY_UPDATE_AUTO_LAUNCH_COMPLETED, false);
    }

    public void markUpdateAutoLaunchScheduled() {
        prefs.edit()
                .putBoolean(Constants.KEY_UPDATE_AUTO_LAUNCH_SCHEDULED, true)
                .apply();
    }

    public void markUpdateAutoLaunchCompleted() {
        prefs.edit()
                .putBoolean(Constants.KEY_UPDATE_AUTO_LAUNCH_SCHEDULED, false)
                .putBoolean(Constants.KEY_UPDATE_AUTO_LAUNCH_COMPLETED, true)
                .apply();
    }

    public void saveEmpDataVersion(int version) {
        prefs.edit().putInt(Constants.KEY_EMP_DATA_VERSION, version).apply();
    }

    public int getEmpDataVersion() {
        return prefs.getInt(Constants.KEY_EMP_DATA_VERSION, 0);
    }

    public float getMatchThreshold() {
        return prefs.getFloat(Constants.KEY_MATCH_THRESHOLD, Constants.DEFAULT_MATCH_THRESHOLD);
    }

    public void saveMatchThreshold(float value) {
        prefs.edit().putFloat(Constants.KEY_MATCH_THRESHOLD, value).apply();
    }

    public float getFaceThreshold() {
        return prefs.getFloat(Constants.KEY_FACE_THRESHOLD, Constants.DEFAULT_FACE_THRESHOLD);
    }

    public void saveFaceThreshold(float value) {
        prefs.edit().putFloat(Constants.KEY_FACE_THRESHOLD, value).apply();
    }

    public boolean isLivenessCheck() {
        return prefs.getBoolean(Constants.KEY_LIVENESS_CHECK, Constants.DEFAULT_LIVENESS_CHECK);
    }

    public void saveLivenessCheck(boolean value) {
        prefs.edit().putBoolean(Constants.KEY_LIVENESS_CHECK, value).apply();
    }

    public float getLivenessThreshold() {
        return prefs.getFloat(Constants.KEY_LIVENESS_THRESHOLD, Constants.DEFAULT_LIVENESS_THRESHOLD);
    }

    public void saveLivenessThreshold(float value) {
        prefs.edit().putFloat(Constants.KEY_LIVENESS_THRESHOLD, value).apply();
    }

    public String getRecognitionDistanceMode() {
        return prefs.getString(Constants.KEY_RECOGNITION_DISTANCE_MODE, Constants.DEFAULT_DISTANCE_MODE);
    }

    public void saveRecognitionDistanceMode(String mode) {
        prefs.edit().putString(Constants.KEY_RECOGNITION_DISTANCE_MODE, mode).apply();
    }

    public int getMinFaceSizeForRecognitionDistance() {
        String mode = getRecognitionDistanceMode();
        if (Constants.DISTANCE_MODE_NEAR.equals(mode)) {
            return 120;
        }
        if (Constants.DISTANCE_MODE_FAR.equals(mode)) {
            return 50;
        }
        return Constants.DEFAULT_MIN_FACE_SIZE;
    }

    public boolean isMaskDetectEnabled() {
        return prefs.getBoolean(Constants.KEY_MASK_DETECT, Constants.DEFAULT_MASK_DETECT);
    }

    public void saveMaskDetectEnabled(boolean enabled) {
        prefs.edit().putBoolean(Constants.KEY_MASK_DETECT, enabled).apply();
    }

    public int getRecognitionTimeoutSeconds() {
        return prefs.getInt(Constants.KEY_RECOGNITION_TIMEOUT_SECONDS, Constants.DEFAULT_RECOGNITION_TIMEOUT_SECONDS);
    }

    public void saveRecognitionTimeoutSeconds(int seconds) {
        prefs.edit().putInt(Constants.KEY_RECOGNITION_TIMEOUT_SECONDS, seconds).apply();
    }

    public boolean isFastPunchEnabled() {
        return prefs.getBoolean(
                Constants.KEY_FAST_PUNCH_ENABLED,
                Constants.DEFAULT_FAST_PUNCH_ENABLED
        );
    }

    public void saveFastPunchEnabled(boolean enabled) {
        prefs.edit().putBoolean(Constants.KEY_FAST_PUNCH_ENABLED, enabled).apply();
    }

    public boolean shouldShowPunchResultCard() {
        return prefs.getBoolean(
                Constants.KEY_SHOW_PUNCH_RESULT_CARD,
                Constants.DEFAULT_SHOW_PUNCH_RESULT_CARD
        );
    }

    public void saveShowPunchResultCard(boolean show) {
        prefs.edit().putBoolean(Constants.KEY_SHOW_PUNCH_RESULT_CARD, show).apply();
    }

    public int getPunchResultDisplayMs() {
        return prefs.getInt(
                Constants.KEY_PUNCH_RESULT_DISPLAY_MS,
                Constants.DEFAULT_PUNCH_RESULT_DISPLAY_MS
        );
    }

    public void savePunchResultDisplayMs(int milliseconds) {
        prefs.edit()
                .putInt(Constants.KEY_PUNCH_RESULT_DISPLAY_MS, Math.max(0, milliseconds))
                .apply();
    }

    public String getPunchSpeechMode() {
        String mode = prefs.getString(
                Constants.KEY_PUNCH_SPEECH_MODE,
                Constants.DEFAULT_PUNCH_SPEECH_MODE
        );
        if (Constants.PUNCH_SPEECH_MODE_NAME.equals(mode)
                || Constants.PUNCH_SPEECH_MODE_SUCCESS.equals(mode)
                || Constants.PUNCH_SPEECH_MODE_FULL.equals(mode)) {
            return mode;
        }
        return Constants.DEFAULT_PUNCH_SPEECH_MODE;
    }

    public void savePunchSpeechMode(String mode) {
        String safeMode = Constants.PUNCH_SPEECH_MODE_FULL;
        if (Constants.PUNCH_SPEECH_MODE_NAME.equals(mode)
                || Constants.PUNCH_SPEECH_MODE_SUCCESS.equals(mode)) {
            safeMode = mode;
        }
        prefs.edit().putString(Constants.KEY_PUNCH_SPEECH_MODE, safeMode).apply();
    }

    public float getPunchSpeechRate() {
        return prefs.getFloat(
                Constants.KEY_PUNCH_SPEECH_RATE,
                Constants.DEFAULT_PUNCH_SPEECH_RATE
        );
    }

    public void savePunchSpeechRate(float rate) {
        prefs.edit()
                .putFloat(Constants.KEY_PUNCH_SPEECH_RATE, Math.max(0.5f, Math.min(2.0f, rate)))
                .apply();
    }

    public int getRecognitionFrameIntervalMs() {
        return prefs.getInt(
                Constants.KEY_RECOGNITION_FRAME_INTERVAL_MS,
                Constants.DEFAULT_RECOGNITION_FRAME_INTERVAL_MS
        );
    }

    public void saveRecognitionFrameIntervalMs(int milliseconds) {
        prefs.edit()
                .putInt(Constants.KEY_RECOGNITION_FRAME_INTERVAL_MS, Math.max(0, milliseconds))
                .apply();
    }

    public int getSuccessCooldownMs() {
        return prefs.getInt(
                Constants.KEY_SUCCESS_COOLDOWN_MS,
                Constants.DEFAULT_SUCCESS_COOLDOWN_MS
        );
    }

    public void saveSuccessCooldownMs(int milliseconds) {
        prefs.edit()
                .putInt(Constants.KEY_SUCCESS_COOLDOWN_MS, Math.max(0, milliseconds))
                .apply();
    }

    public void saveActivationMode(String mode) {
        prefs.edit().putString(Constants.KEY_ACTIVATION_MODE, mode).apply();
    }

    public String getActivationMode() {
        return prefs.getString(Constants.KEY_ACTIVATION_MODE, "");
    }

    public void saveActivationCode(String activationCode) {
        securePrefs.edit().putString(Constants.KEY_ACTIVATION_CODE, activationCode == null ? "" : activationCode).apply();
    }

    public String getActivationCode() {
        return securePrefs.getString(Constants.KEY_ACTIVATION_CODE, "");
    }

    public void saveActivationStatus(String status) {
        prefs.edit().putString(Constants.KEY_ACTIVATION_STATUS, status).apply();
    }

    public String getActivationStatus() {
        return prefs.getString(Constants.KEY_ACTIVATION_STATUS, Constants.ACTIVATION_STATUS_PENDING);
    }

    public void saveLastActivationCode(int code) {
        prefs.edit().putInt(Constants.KEY_LAST_ACTIVATION_CODE, code).apply();
    }

    public int getLastActivationCode() {
        return prefs.getInt(Constants.KEY_LAST_ACTIVATION_CODE, Integer.MIN_VALUE);
    }

    public void saveLastActivationMessage(String message) {
        prefs.edit().putString(Constants.KEY_LAST_ACTIVATION_MESSAGE, message).apply();
    }

    public String getLastActivationMessage() {
        return prefs.getString(Constants.KEY_LAST_ACTIVATION_MESSAGE, "");
    }

    public void saveLastActivationTime(long timeSeconds) {
        prefs.edit().putLong(Constants.KEY_LAST_ACTIVATION_TIME, timeSeconds).apply();
    }

    public long getLastActivationTime() {
        return prefs.getLong(Constants.KEY_LAST_ACTIVATION_TIME, 0L);
    }

    public void saveLastHeartbeatTime(long timeSeconds) {
        prefs.edit().putLong(Constants.KEY_LAST_HEARTBEAT_TIME, timeSeconds).apply();
    }

    public long getLastHeartbeatTime() {
        return prefs.getLong(Constants.KEY_LAST_HEARTBEAT_TIME, 0L);
    }

    public void saveLastServerTime(long timeSeconds) {
        prefs.edit().putLong(Constants.KEY_LAST_SERVER_TIME, timeSeconds).apply();
    }

    public long getLastServerTime() {
        return prefs.getLong(Constants.KEY_LAST_SERVER_TIME, 0L);
    }

    public void clearActivationState() {
        prefs.edit()
                .remove(Constants.KEY_ACTIVATION_MODE)
                .remove(Constants.KEY_ACTIVATION_STATUS)
                .remove(Constants.KEY_LAST_ACTIVATION_CODE)
                .remove(Constants.KEY_LAST_ACTIVATION_MESSAGE)
                .remove(Constants.KEY_LAST_ACTIVATION_TIME)
                .apply();
        securePrefs.edit().remove(Constants.KEY_ACTIVATION_CODE).apply();
    }

    public int getCameraFacing(int fallbackFacing) {
        return prefs.getInt(Constants.KEY_CAMERA_FACING, fallbackFacing);
    }

    public void saveCameraFacing(int cameraFacing) {
        prefs.edit().putInt(Constants.KEY_CAMERA_FACING, cameraFacing).apply();
    }

    public boolean isSoundEnabled() {
        return prefs.getBoolean(Constants.KEY_SOUND_ENABLED, true);
    }

    public void saveSoundEnabled(boolean enabled) {
        prefs.edit().putBoolean(Constants.KEY_SOUND_ENABLED, enabled).apply();
    }

    public void clearAll() {
        prefs.edit().clear().apply();
        securePrefs.edit().clear().apply();
    }

    static String buildStableDeviceId(String seed) {
        if (seed == null || seed.trim().isEmpty()) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(seed.trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(String.format(Locale.US, "%02X", b));
            }
            return builder.substring(0, DEVICE_ID_LENGTH);
        } catch (Exception e) {
            Log.e(TAG, "buildStableDeviceId failed", e);
            return "";
        }
    }

    private String resolveDeviceId() {
        String testOverride = normalizeDeviceId(resolvedDeviceIdForTest);
        if (!testOverride.isEmpty()) {
            return testOverride;
        }
        String id = resolveSerialDeviceId();
        if (!id.isEmpty()) {
            return id;
        }
        if (appContext != null) {
            id = buildStableDeviceId(BaiduDeviceFingerprint.get(appContext));
        }
        return id;
    }

    static void setResolvedDeviceIdForTest(String deviceId) {
        resolvedDeviceIdForTest = deviceId;
    }

    private String resolveSerialDeviceId() {
        if (appContext == null) {
            return "";
        }
        return normalizeDeviceId(DeviceIdentityUtils.getDeviceSerial(appContext));
    }

    private String replaceDeviceId(String deviceId, boolean resetRegistration) {
        String safeDeviceId = normalizeDeviceId(deviceId);
        if (safeDeviceId.isEmpty()) {
            safeDeviceId = resolveDeviceId();
        }
        if (safeDeviceId.isEmpty()) {
            return "";
        }
        SharedPreferences.Editor editor = prefs.edit()
                .putString(Constants.KEY_DEVICE_ID, safeDeviceId);
        if (resetRegistration) {
            editor.putBoolean(Constants.KEY_DEVICE_REGISTERED, false)
                    .putBoolean(Constants.KEY_DEVICE_CONFIG_INITIALIZED, false);
        }
        editor.apply();
        if (resetRegistration) {
            clearToken();
        }
        return safeDeviceId;
    }

    private static String normalizeDeviceId(String deviceId) {
        if (deviceId == null) {
            return "";
        }
        String normalized = deviceId.trim();
        if (normalized.isEmpty()) {
            return "";
        }
        normalized = normalized.replaceAll("[^0-9A-Za-z_-]", "");
        if (normalized.length() > MAX_DEVICE_ID_LENGTH) {
            normalized = normalized.substring(0, MAX_DEVICE_ID_LENGTH);
        }
        return normalized;
    }

    static boolean isModernDeviceId(String deviceId) {
        return isValidDeviceId(deviceId);
    }

    static boolean isValidDeviceId(String deviceId) {
        return deviceId != null && DEVICE_ID_PATTERN.matcher(deviceId.trim()).matches();
    }

    public static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return Constants.DEFAULT_BASE_URL;
        }
        String normalized = baseUrl.trim();
        if (normalized.isEmpty()) {
            return Constants.DEFAULT_BASE_URL;
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        if (normalized.isEmpty()) {
            return Constants.DEFAULT_BASE_URL;
        }
        try {
            URI uri = new URI(normalized);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (host == null || host.trim().isEmpty()) {
                return Constants.DEFAULT_BASE_URL;
            }
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return Constants.DEFAULT_BASE_URL;
            }
            return normalized;
        } catch (Exception ignored) {
            return Constants.DEFAULT_BASE_URL;
        }
    }
}

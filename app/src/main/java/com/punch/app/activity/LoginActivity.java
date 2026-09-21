package com.punch.app.activity;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.punch.app.PunchApplication;
import com.punch.app.R;
import com.punch.app.network.ApiResult;
import com.punch.app.network.ApiService;
import com.punch.app.network.dto.AuthDto;
import com.punch.app.network.dto.DeviceDto;
import com.punch.app.receiver.UpdateInstallStateReceiver;
import com.punch.app.service.SyncService;
import com.punch.app.service.SyncTrigger;
import com.punch.app.utils.AppLogger;
import com.punch.app.utils.KioskManager;
import com.punch.app.utils.LifecycleRequestGate;
import com.punch.app.utils.SessionManager;
import com.punch.app.utils.UpdateManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.List;

public class LoginActivity extends AppCompatActivity {
    private static final String TAG = "LoginActivity";
    private static final String DEFAULT_ACCOUNT = "admin";
    private static final String DEFAULT_PASSWORD = "a123456!";

    private EditText etAccount;
    private EditText etPassword;
    private Button btnLogin;
    private Button btnEditSetup;
    private ProgressBar progress;
    private TextView tvError;
    private TextView tvProgressStatus;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final LifecycleRequestGate lifecycleGate = new LifecycleRequestGate();
    private int activityToken;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        activityToken = lifecycleGate.open();
        setContentView(R.layout.activity_login);

        etAccount = findViewById(R.id.et_account);
        etPassword = findViewById(R.id.et_password);
        btnLogin = findViewById(R.id.btn_login);
        btnEditSetup = findViewById(R.id.btn_edit_setup);
        progress = findViewById(R.id.progress);
        tvError = findViewById(R.id.tv_error);
        tvProgressStatus = findViewById(R.id.tv_progress_status);

        String savedAccount = SessionManager.get().getAccount();
        String savedPassword = SessionManager.get().getPassword();
        etAccount.setText(savedAccount.isEmpty() ? DEFAULT_ACCOUNT : savedAccount);
        etPassword.setText(savedPassword.isEmpty() ? DEFAULT_PASSWORD : savedPassword);
        btnLogin.setOnClickListener(v -> doLogin());
        btnEditSetup.setOnClickListener(v -> openSetupWizard());
    }

    @Override
    protected void onResume() {
        super.onResume();
        KioskManager.enterIfPossible(this);
        refreshDeviceIdFromPreferredSource();
    }

    @Override
    protected void onDestroy() {
        lifecycleGate.close();
        executor.shutdownNow();
        super.onDestroy();
    }

    boolean isLoginExecutorShutdownForTest() {
        return executor.isShutdown();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event != null && KioskManager.shouldBlockSystemKey(event.getKeyCode())) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            UpdateInstallStateReceiver.acknowledgeUpdatedAppLaunch(this);
            return;
        }
        KioskManager.restoreAppTaskSoon(this);
    }

    @Override
    public void onBackPressed() {
        if (SessionManager.get().isKioskEnabled()) {
            return;
        }
        super.onBackPressed();
    }
    private void refreshDeviceIdFromPreferredSource() {
        SessionManager.get().getDeviceId();
    }

    private void doLogin() {
        final int taskToken = activityToken;
        String account = etAccount.getText().toString().trim();
        String password = etPassword.getText().toString();
        if (TextUtils.isEmpty(account) || TextUtils.isEmpty(password)) {
            showError("\u8bf7\u8f93\u5165\u8d26\u53f7\u548c\u5bc6\u7801");
            return;
        }
        PunchApplication app = PunchApplication.get();
        if (app != null) {
            app.resetPunchRecognitionState();
            app.resetPunchStatusTimeline("\u6b63\u5728\u51c6\u5907\u767b\u5f55...", PunchApplication.STATUS_LEVEL_PROGRESS);
        }
        setLoading(true, "\u6b63\u5728\u51c6\u5907\u767b\u5f55...");

        executor.execute(() -> {
            String error = null;
            String deviceId = SessionManager.get().getDeviceId();

            if (!SessionManager.get().isDeviceRegistered()) {
                updateLoadingStatus("\u6b63\u5728\u6ce8\u518c\u8bbe\u5907...");
                reportStatusEvent("\u6b63\u5728\u6ce8\u518c\u8bbe\u5907...", PunchApplication.STATUS_LEVEL_PROGRESS);
                ApiResult<DeviceDto.DeviceRegisterData> registerResult =
                        ApiService.registerDevice(getApplicationContext());
                if (!registerResult.success || registerResult.data == null || registerResult.data.deviceId.isEmpty()) {
                    reportStatusEvent("\u8bbe\u5907\u6ce8\u518c\u5931\u8d25", PunchApplication.STATUS_LEVEL_ERROR);
                    error = registerResult.message != null && !registerResult.message.isEmpty()
                            ? registerResult.message
                            : "\u8bbe\u5907\u6ce8\u518c\u5931\u8d25";
                } else {
                    SessionManager.get().saveDeviceId(registerResult.data.deviceId);
                    SessionManager.get().saveDeviceRegistered(true);
                    deviceId = registerResult.data.deviceId;
                    reportStatusEvent("\u8bbe\u5907\u6ce8\u518c\u6210\u529f", PunchApplication.STATUS_LEVEL_SUCCESS);
                }
            }

            AuthDto.LoginData loginData = null;
            if (error == null) {
                updateLoadingStatus("\u6b63\u5728\u767b\u5f55...");
                reportStatusEvent("\u6b63\u5728\u767b\u5f55...", PunchApplication.STATUS_LEVEL_PROGRESS);
                ApiResult<AuthDto.LoginData> loginResult = ApiService.login(account, password, deviceId);
                if (!loginResult.success || loginResult.data == null) {
                    reportStatusEvent("\u767b\u5f55\u5931\u8d25", PunchApplication.STATUS_LEVEL_ERROR);
                    error = loginResult.message != null && !loginResult.message.isEmpty()
                            ? loginResult.message
                            : "\u767b\u5f55\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5\u7f51\u7edc\u6216\u7a0d\u540e\u91cd\u8bd5";
                } else {
                    loginData = loginResult.data;
                    SessionManager.get().saveToken(loginData.token, loginData.tokenExpireAt);
                    SessionManager.get().saveAccount(account);
                    SessionManager.get().savePassword(password);
                    reportStatusEvent("\u767b\u5f55\u6210\u529f", PunchApplication.STATUS_LEVEL_SUCCESS);
                }
            }

            DeviceDto.DeviceConfigData configData = null;
            if (error == null && shouldBootstrapDeviceConfig()) {
                updateLoadingStatus("\u6b63\u5728\u540c\u6b65\u8bbe\u5907\u914d\u7f6e...");
                reportStatusEvent("\u6b63\u5728\u540c\u6b65\u8bbe\u5907\u914d\u7f6e...", PunchApplication.STATUS_LEVEL_PROGRESS);
                ApiResult<DeviceDto.DeviceConfigData> configResult = ApiService.fetchDeviceConfig();
                if (!configResult.success || configResult.data == null) {
                    SessionManager.get().clearLoginState();
                    String configError = buildApiFailureMessage("\u83b7\u53d6\u914d\u7f6e\u5931\u8d25", configResult);
                    AppLogger.e(TAG, "Device config sync failed after login: "
                            + buildApiFailureDetail(configResult));
                    reportStatusEvent(
                            "\u8bbe\u5907\u914d\u7f6e\u540c\u6b65\u5931\u8d25: " + buildApiFailureDetail(configResult),
                            PunchApplication.STATUS_LEVEL_ERROR
                    );
                    error = configError;
                } else {
                    configData = configResult.data;
                    reportStatusEvent("\u8bbe\u5907\u914d\u7f6e\u540c\u6b65\u5b8c\u6210", PunchApplication.STATUS_LEVEL_SUCCESS);
                }
            }

            AuthDto.LoginData finalLoginData = loginData;
            DeviceDto.DeviceConfigData finalConfigData = configData;
            String finalError = error;
            postToActiveActivity(taskToken, () -> {
                if (finalError != null) {
                    setLoading(false, null);
                    showError(finalError);
                    return;
                }

                updateLoadingStatus("\u767b\u5f55\u6210\u529f\uff0c\u6b63\u5728\u8fdb\u5165\u7cfb\u7edf...");
                applyLoginState(account, finalLoginData, finalConfigData);
                postLogin();
            });
        });
    }

    private void openSetupWizard() {
        Intent intent = new Intent(this, SetupWizardActivity.class);
        startActivity(intent);
    }

    private void applyLoginState(String account,
                                 AuthDto.LoginData loginData,
                                 DeviceDto.DeviceConfigData configData) {
        SessionManager.get().saveAccount(account);

        if (configData == null) {
            return;
        }
        SessionManager.get().saveDeviceConfigInitialized(true);
        SessionManager.get().saveLineBindingOptions(configData.lines);
        SessionManager.get().saveTeamBindingOptions(configData.teams);
        DeviceDto.LineOptionData line = resolveDefaultLine(configData);
        if (line != null) {
            SessionManager.get().saveLineBinding(line.code, line.name);
        }

        DeviceDto.TeamOptionData team = resolveDefaultTeam(configData);
        if (team != null && team.id > 0) {
            SessionManager.get().saveTeamBindingId(team.id);
            SessionManager.get().saveTeamBindingName(team.name);
            SessionManager.get().saveCurrentTeamTimeRanges(team.timeRanges);
        } else {
            SessionManager.get().saveCurrentTeamTimeRanges(resolveTeamTimeRanges(configData));
        }
        SessionManager.get().saveCheckCount(configData.checkCount);
        if (!configData.account.isEmpty()) {
            SessionManager.get().saveAccount(configData.account);
        }
        if (!configData.password.isEmpty()) {
            SessionManager.get().savePassword(configData.password);
        }
        SessionManager.get().saveUpdateInfo(
                configData.updateInfo.needUpdate || configData.needUpdate,
                configData.updateInfo.apkUrl,
                configData.updateInfo.currentVersion,
                configData.updateInfo.targetVersion,
                configData.updateInfo.versionName
        );
        UpdateManager.startBackgroundUpdateIfEligible(this, "login_device_config");
        if (configData.matchThreshold != null) {
            SessionManager.get().saveMatchThreshold(configData.matchThreshold);
        }
        if (configData.faceThreshold != null) {
            SessionManager.get().saveFaceThreshold(configData.faceThreshold);
        }
        if (configData.livenessCheck != null) {
            SessionManager.get().saveLivenessCheck(configData.livenessCheck);
        }
        if (configData.maskDetect != null) {
            SessionManager.get().saveMaskDetectEnabled(configData.maskDetect);
        }
        if (configData.timeoutSeconds != null) {
            SessionManager.get().saveRecognitionTimeoutSeconds(configData.timeoutSeconds);
        }
        if (configData.recognitionDistanceMode != null && !configData.recognitionDistanceMode.isEmpty()) {
            SessionManager.get().saveRecognitionDistanceMode(configData.recognitionDistanceMode);
        }
    }

    private void postLogin() {
        updateLoadingStatus("\u6b63\u5728\u521d\u59cb\u5316\u6253\u5361\u73af\u5883...");
        PunchApplication app = PunchApplication.get();
        if (app != null) {
            app.reportStatusEvent("\u767b\u5f55\u6210\u529f", PunchApplication.STATUS_LEVEL_SUCCESS);
            app.restartPunchRecognitionData();
        }
        SyncService.triggerSync(this, SyncTrigger.APP_START);
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    private boolean shouldBootstrapDeviceConfig() {
        return !SessionManager.get().isDeviceConfigInitialized()
                || SessionManager.get().getCurrentTeamTimeRanges().isEmpty();
    }

    private List<String> resolveTeamTimeRanges(DeviceDto.DeviceConfigData configData) {
        if (configData == null) {
            return java.util.Collections.emptyList();
        }
        for (DeviceDto.TeamOptionData team : configData.teams) {
            if (team != null && team.id == configData.teamBindingId) {
                return team.timeRanges;
            }
        }
        return java.util.Collections.emptyList();
    }

    private DeviceDto.LineOptionData resolveDefaultLine(DeviceDto.DeviceConfigData configData) {
        if (configData == null) {
            return null;
        }
        if (!isBlank(configData.lineCode) || !isBlank(configData.lineName)) {
            DeviceDto.LineOptionData line = new DeviceDto.LineOptionData();
            line.code = safeString(configData.lineCode).trim();
            line.name = safeString(configData.lineName).trim();
            return line;
        }
        for (DeviceDto.LineOptionData line : configData.lines) {
            if (line != null && (!isBlank(line.code) || !isBlank(line.name))) {
                return line;
            }
        }
        return null;
    }

    private DeviceDto.TeamOptionData resolveDefaultTeam(DeviceDto.DeviceConfigData configData) {
        if (configData == null) {
            return null;
        }
        if (configData.teamBindingId > 0) {
            for (DeviceDto.TeamOptionData team : configData.teams) {
                if (team != null && team.id == configData.teamBindingId) {
                    return team;
                }
            }
            DeviceDto.TeamOptionData team = new DeviceDto.TeamOptionData();
            team.id = configData.teamBindingId;
            team.name = safeString(configData.teamBindingName).trim();
            return team;
        }
        for (DeviceDto.TeamOptionData team : configData.teams) {
            if (team != null && team.id > 0) {
                return team;
            }
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String safeString(String value) {
        return value == null ? "" : value;
    }

    private void setLoading(boolean loading, String message) {
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnLogin.setEnabled(!loading);
        btnEditSetup.setEnabled(!loading);
        etAccount.setEnabled(!loading);
        etPassword.setEnabled(!loading);
        tvError.setVisibility(View.GONE);
        if (loading) {
            tvProgressStatus.setVisibility(View.VISIBLE);
            tvProgressStatus.setText(message == null ? "" : message);
        } else {
            tvProgressStatus.setVisibility(View.GONE);
            tvProgressStatus.setText("");
        }
    }

    private void showError(String msg) {
        tvError.setText(msg);
        tvError.setVisibility(View.VISIBLE);
    }

    private static String buildApiFailureMessage(String fallback, ApiResult<?> result) {
        if (result == null) {
            return fallback;
        }
        String message = result.message == null ? "" : result.message.trim();
        if (!message.isEmpty()) {
            return fallback + ": " + message + " (code=" + result.code + ")";
        }
        return fallback + " (code=" + result.code + ", dataNull=" + (result.data == null) + ")";
    }

    private static String buildApiFailureDetail(ApiResult<?> result) {
        if (result == null) {
            return "result=null";
        }
        return "success=" + result.success
                + ", code=" + result.code
                + ", message=" + (result.message == null ? "" : result.message.trim())
                + ", dataNull=" + (result.data == null);
    }

    private void updateLoadingStatus(String message) {
        final int taskToken = activityToken;
        postToActiveActivity(taskToken, () -> {
            if (progress.getVisibility() == View.VISIBLE) {
                tvProgressStatus.setVisibility(View.VISIBLE);
                tvProgressStatus.setText(message == null ? "" : message);
            }
        });
    }

    private void postToActiveActivity(int taskToken, Runnable action) {
        runOnUiThread(() -> {
            if (lifecycleGate.isActive(taskToken)) {
                action.run();
            }
        });
    }

    private void reportStatusEvent(String message, int level) {
        PunchApplication app = PunchApplication.get();
        if (app != null) {
            app.reportStatusEvent(message, level);
        }
    }
}

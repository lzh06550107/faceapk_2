package com.punch.app.activity;

import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;

import androidx.appcompat.app.AppCompatActivity;

import com.punch.app.R;
import com.punch.app.receiver.UpdateInstallStateReceiver;
import com.punch.app.utils.KioskManager;
import com.punch.app.utils.NtpProbeClient;
import com.punch.app.utils.SessionManager;
import com.punch.app.utils.SystemAppController;
import com.punch.app.utils.SystemNtpConfigurator;
import com.punch.app.utils.SystemNtpPolicy;
import com.punch.app.utils.WifiConfigDialogHelper;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class SetupWizardActivity extends AppCompatActivity {
    private static final int PAGE_WIFI = 0;
    private static final int PAGE_NTP = 1;
    private static final int PAGE_SERVER = 2;
    private static final int NTP_PROBE_TIMEOUT_MS = 3_000;

    private ViewFlipper flipper;
    private TextView tvWifiStatus;
    private TextView tvNtpCapability;
    private TextView tvNtpCurrentServer;
    private TextView tvNtpStatus;
    private TextView tvError;
    private EditText etNtpServer;
    private EditText etBaseUrl;
    private EditText etCompanyId;
    private Button btnNext;
    private Button btnTestNtp;
    private Button btnApplyNtp;
    private Button btnNtpNext;
    private Button btnFinish;
    private WifiConfigDialogHelper wifiConfigDialogHelper;

    private final ExecutorService ntpExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger ntpOperationGeneration = new AtomicInteger();
    private volatile boolean destroyed;
    private volatile boolean ntpBusy;
    private String ntpAppliedServer = "";
    private SystemNtpPolicy.ManagementAvailability ntpAvailability =
            SystemNtpPolicy.ManagementAvailability.NOT_DEVICE_OWNER;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup_wizard);

        flipper = findViewById(R.id.flipper);
        tvWifiStatus = findViewById(R.id.tv_wifi_status);
        tvNtpCapability = findViewById(R.id.tv_ntp_capability);
        tvNtpCurrentServer = findViewById(R.id.tv_ntp_current_server);
        tvNtpStatus = findViewById(R.id.tv_ntp_status);
        tvError = findViewById(R.id.tv_error);
        etNtpServer = findViewById(R.id.et_ntp_server);
        etBaseUrl = findViewById(R.id.et_base_url);
        etCompanyId = findViewById(R.id.et_company_id);
        btnNext = findViewById(R.id.btn_next);
        btnTestNtp = findViewById(R.id.btn_test_ntp);
        btnApplyNtp = findViewById(R.id.btn_apply_ntp);
        btnNtpNext = findViewById(R.id.btn_ntp_next);
        btnFinish = findViewById(R.id.btn_finish);
        wifiConfigDialogHelper = new WifiConfigDialogHelper(this);

        etBaseUrl.setText(SessionManager.get().getBaseUrl());
        etCompanyId.setText(String.valueOf(SessionManager.get().getCompanyId()));

        findViewById(R.id.btn_configure_wifi).setOnClickListener(
                v -> wifiConfigDialogHelper.showConfigDialog()
        );
        findViewById(R.id.btn_skip_wifi).setOnClickListener(v -> goToNtpPage());
        btnNext.setOnClickListener(v -> goToNtpPage());

        findViewById(R.id.btn_ntp_prev).setOnClickListener(v -> goToWifiPage());
        btnTestNtp.setOnClickListener(v -> testNtpServer());
        btnApplyNtp.setOnClickListener(v -> applyNtpServer());
        btnNtpNext.setOnClickListener(v -> continueFromNtpPage());

        findViewById(R.id.btn_prev).setOnClickListener(v -> goToNtpPage());
        btnFinish.setOnClickListener(v -> completeSetup());

        etNtpServer.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String host = SystemNtpPolicy.normalizeHost(s == null ? "" : s.toString());
                if (!host.equals(ntpAppliedServer)) {
                    ntpAppliedServer = "";
                }
                updateNtpButtons();
            }
        });

        refreshNtpState(true);
        showWifiPage();
    }

    @Override
    protected void onResume() {
        super.onResume();
        KioskManager.enterIfPossible(this);
        wifiConfigDialogHelper.onResume();
        refreshWifiStatus();
        if (flipper != null && flipper.getDisplayedChild() == PAGE_NTP) {
            refreshNtpState(false);
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        ntpOperationGeneration.incrementAndGet();
        ntpExecutor.shutdownNow();
        wifiConfigDialogHelper.onDestroy();
        super.onDestroy();
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
        int page = flipper.getDisplayedChild();
        if (page == PAGE_SERVER) {
            goToNtpPage();
            return;
        }
        if (page == PAGE_NTP) {
            goToWifiPage();
            return;
        }
        super.onBackPressed();
    }

    private void showWifiPage() {
        flipper.setDisplayedChild(PAGE_WIFI);
        refreshWifiStatus();
    }

    private void goToWifiPage() {
        showWifiPage();
    }

    private void goToNtpPage() {
        flipper.setDisplayedChild(PAGE_NTP);
        refreshNtpState(false);
    }

    private void goToServerPage() {
        flipper.setDisplayedChild(PAGE_SERVER);
        tvError.setVisibility(View.GONE);
    }

    private void continueFromNtpPage() {
        if (!isCurrentNtpApplied()) {
            showNtpStatus("请先成功应用系统 NTP 配置，再进入下一步。");
            return;
        }
        goToServerPage();
    }

    private void refreshWifiStatus() {
        String ssid = readConnectedSsid();
        if (ssid.isEmpty()) {
            tvWifiStatus.setText("尚未连接 Wi-Fi");
        } else {
            tvWifiStatus.setText("已连接：" + ssid);
        }
    }

    private void refreshNtpState(boolean initializeInput) {
        ntpAvailability = SystemNtpConfigurator.getAvailability(this);
        boolean systemAppMode = KioskManager.isSystemAppMode(this);
        boolean deviceOwner = KioskManager.isDeviceOwner(this);
        boolean writePermission = SystemNtpConfigurator.hasWritePermission(this);
        String currentServer = SystemNtpConfigurator.getCurrentServer(this);

        String missingSystemPrivileges = systemAppMode
                ? SystemAppController.describeMissingProductionPrivileges(this)
                : "";
        tvNtpCapability.setText(
                "设备管理模式：" + KioskManager.managementModeLabel(this)
                        + "\nAndroid 12 System App：" + (systemAppMode ? "✓" : "✗")
                        + "\nDevice Owner 回退：" + (deviceOwner ? "✓" : "✗")
                        + "\n系统 NTP 写权限：" + (writePermission ? "✓" : "✗")
                        + (systemAppMode
                        ? "\nSystem App 权限自检："
                        + (missingSystemPrivileges.isEmpty()
                        ? "✓"
                        : "缺失 " + missingSystemPrivileges)
                        : "")
        );
        tvNtpCurrentServer.setText(
                currentServer.isEmpty() ? "当前系统 NTP：未配置" : "当前系统 NTP：" + currentServer
        );

        String fieldHost = SystemNtpPolicy.normalizeHost(
                etNtpServer.getText() == null ? "" : etNtpServer.getText().toString()
        );
        if (initializeInput || fieldHost.isEmpty()) {
            etNtpServer.setText(SystemNtpPolicy.DEFAULT_TARGET_SERVER);
            etNtpServer.setSelection(etNtpServer.length());
        }

        if (ntpAvailability != SystemNtpPolicy.ManagementAvailability.AVAILABLE) {
            showNtpStatus(unavailableNtpMessage(ntpAvailability));
        }
        updateNtpButtons();
    }

    private void testNtpServer() {
        String host = readNtpHostOrShowError();
        if (host.isEmpty()) {
            return;
        }
        runNtpOperation(host, false);
    }

    private void applyNtpServer() {
        String host = readNtpHostOrShowError();
        if (host.isEmpty()) {
            return;
        }
        if (ntpAvailability != SystemNtpPolicy.ManagementAvailability.AVAILABLE) {
            showNtpStatus(unavailableNtpMessage(ntpAvailability));
            return;
        }
        runNtpOperation(host, true);
    }

    private String readNtpHostOrShowError() {
        String raw = etNtpServer.getText() == null ? "" : etNtpServer.getText().toString();
        String host = SystemNtpPolicy.normalizeHost(raw);
        if (!SystemNtpPolicy.isValidHost(host)) {
            showNtpStatus("NTP 服务器必须是 IPv4 或 DNS 主机名，不能包含协议、端口或路径。");
            etNtpServer.requestFocus();
            return "";
        }
        return host;
    }

    private void runNtpOperation(String host, boolean applyAfterProbe) {
        final int generation = ntpOperationGeneration.incrementAndGet();
        final Context appContext = getApplicationContext();
        setNtpBusy(true);
        showNtpStatus(applyAfterProbe ? "正在测试 NTP 服务器并应用系统配置..." : "正在测试 NTP 服务器...");

        ntpExecutor.execute(() -> {
            NtpProbeClient.ProbeResult probe = NtpProbeClient.probe(host, NTP_PROBE_TIMEOUT_MS);
            if (!probe.success) {
                postNtpResult(generation, () -> {
                    setNtpBusy(false);
                    showNtpStatus("NTP 测试失败：" + probe.message);
                });
                return;
            }

            if (!applyAfterProbe) {
                postNtpResult(generation, () -> {
                    setNtpBusy(false);
                    showNtpStatus(formatProbeSuccess(probe));
                });
                return;
            }

            SystemNtpConfigurator.ApplyResult applied = SystemNtpConfigurator.apply(appContext, host);
            postNtpResult(generation, () -> {
                setNtpBusy(false);
                if (!applied.success) {
                    showNtpStatus("系统 NTP 配置失败：" + applied.message);
                    refreshNtpState(false);
                    return;
                }
                ntpAppliedServer = applied.server;
                tvNtpCurrentServer.setText("当前系统 NTP：" + applied.server);
                showNtpStatus(
                        formatProbeSuccess(probe)
                                + "\n✓ 系统 NTP 配置已写入并验证"
                                + "\n✓ Android 自动时间已开启"
                                + "\n✓ 已禁止手工修改日期和时间"
                                + "\n等待 Android 系统时间服务下一次同步。"
                );
                updateNtpButtons();
            });
        });
    }

    private void postNtpResult(int generation, Runnable action) {
        runOnUiThread(() -> {
            if (destroyed || generation != ntpOperationGeneration.get()) {
                return;
            }
            action.run();
        });
    }

    private void setNtpBusy(boolean busy) {
        ntpBusy = busy;
        updateNtpButtons();
    }

    private void updateNtpButtons() {
        if (btnTestNtp == null || btnApplyNtp == null || btnNtpNext == null) {
            return;
        }
        boolean validHost = SystemNtpPolicy.isValidHost(
                etNtpServer == null || etNtpServer.getText() == null
                        ? ""
                        : etNtpServer.getText().toString()
        );
        btnTestNtp.setEnabled(!ntpBusy && validHost);
        btnApplyNtp.setEnabled(
                !ntpBusy
                        && validHost
                        && ntpAvailability == SystemNtpPolicy.ManagementAvailability.AVAILABLE
        );
        btnNtpNext.setEnabled(!ntpBusy && isCurrentNtpApplied());
    }

    private boolean isCurrentNtpApplied() {
        if (ntpAppliedServer.isEmpty() || etNtpServer == null || etNtpServer.getText() == null) {
            return false;
        }
        return ntpAppliedServer.equals(SystemNtpPolicy.normalizeHost(etNtpServer.getText().toString()));
    }

    private String formatProbeSuccess(NtpProbeClient.ProbeResult probe) {
        DateFormat dateFormat = DateFormat.getDateTimeInstance(
                DateFormat.MEDIUM,
                DateFormat.MEDIUM,
                Locale.getDefault()
        );
        String offset = probe.localClockOffsetMillis >= 0
                ? "+" + probe.localClockOffsetMillis
                : String.valueOf(probe.localClockOffsetMillis);
        return "✓ NTP 服务器响应正常"
                + "\nUDP/123 · Stratum " + probe.stratum
                + "\nRTT：" + probe.roundTripMillis + " ms"
                + "\n服务器时间：" + dateFormat.format(new Date(probe.serverTimeMillis))
                + "\n当前系统时间估算偏差：" + offset + " ms（仅诊断，不修改时间）";
    }

    private String unavailableNtpMessage(SystemNtpPolicy.ManagementAvailability availability) {
        if (availability == SystemNtpPolicy.ManagementAvailability.UNSUPPORTED_ANDROID_VERSION) {
            return "当前 Android 版本不支持此系统时间管理方案。";
        }
        if (availability == SystemNtpPolicy.ManagementAvailability.NOT_DEVICE_OWNER) {
            return "当前 App 既不是 Android 12 platform system app，也不是 Device Owner，无法应用系统时间策略。";
        }
        if (availability == SystemNtpPolicy.ManagementAvailability.WRITE_PERMISSION_MISSING) {
            return "WRITE_SECURE_SETTINGS 未授权。System App 版本请检查 platform 签名、Manifest 权限和 ROM privileged-permission 配置。";
        }
        return "系统 NTP 管理能力不可用。";
    }

    private void showNtpStatus(String message) {
        tvNtpStatus.setText(message);
        tvNtpStatus.setVisibility(View.VISIBLE);
    }

    @SuppressWarnings("deprecation")
    private String readConnectedSsid() {
        try {
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wifiManager == null) {
                return "";
            }
            WifiInfo info = wifiManager.getConnectionInfo();
            if (info == null || info.getNetworkId() == -1) {
                return "";
            }
            String ssid = info.getSSID();
            if (ssid == null) {
                return "";
            }
            String trimmed = ssid.trim();
            if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                trimmed = trimmed.substring(1, trimmed.length() - 1);
            }
            if ("<unknown ssid>".equals(trimmed)) {
                return "";
            }
            return trimmed;
        } catch (Exception ignored) {
            return "";
        }
    }

    private void completeSetup() {
        String rawBaseUrl = etBaseUrl.getText() == null
                ? ""
                : etBaseUrl.getText().toString().trim();
        String baseUrl = SessionManager.normalizeBaseUrl(rawBaseUrl);
        if (rawBaseUrl.isEmpty() || !baseUrl.equals(rawBaseUrl.replaceAll("/+$", "").trim())) {
            showError("Base URL 必须是有效的 http/https 地址");
            etBaseUrl.requestFocus();
            return;
        }

        int companyId = parseCompanyId();
        if (companyId <= 0) {
            showError("Company ID 必须为正整数");
            etCompanyId.requestFocus();
            return;
        }

        SessionManager session = SessionManager.get();
        boolean serverChanged = !baseUrl.equals(session.getBaseUrl()) || companyId != session.getCompanyId();
        session.saveBaseUrl(baseUrl);
        session.saveCompanyId(companyId);
        if (serverChanged) {
            session.clearServerBoundState();
        }
        session.saveSetupCompleted(true);

        Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show();
        Intent intent = new Intent(this, LoginActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private int parseCompanyId() {
        String value = etCompanyId.getText() == null
                ? ""
                : etCompanyId.getText().toString().trim();
        if (value.isEmpty() || !TextUtils.isDigitsOnly(value)) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private void showError(String message) {
        tvError.setText(message);
        tvError.setVisibility(View.VISIBLE);
    }
}

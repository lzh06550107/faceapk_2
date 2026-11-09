package com.punch.app.fragment;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.punch.app.PunchApplication;
import com.punch.app.R;
import com.punch.app.activity.AdvancedConfigActivity;
import com.punch.app.activity.LoginActivity;
import com.punch.app.activation.ActivationManager;
import com.punch.app.db.DatabaseHelper;
import com.punch.app.network.ApiResult;
import com.punch.app.network.ApiService;
import com.punch.app.network.InteractionLogger;
import com.punch.app.network.dto.DeviceDto;
import com.punch.app.service.HeartbeatManager;
import com.punch.app.service.SyncService;
import com.punch.app.service.SyncTrigger;
import com.punch.app.utils.Constants;
import com.punch.app.utils.SessionManager;
import com.punch.app.utils.UpdateManager;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ConfigFragment extends Fragment {
    private static final String UPDATE_BUTTON_IDLE = "更新安装";
    private static final String UPDATE_BUTTON_DOWNLOADING = "下载中...";
    private static final String UPDATE_BUTTON_INSTALLING = "安装中...";

    private AutoCompleteTextView dropdownLineBinding;
    private AutoCompleteTextView dropdownTeamBinding;
    private TextView tvDeviceId;
    private TextView tvUpdateStatus;
    private TextView tvAccount;
    private TextView tvPendingCount;
    private TextView tvAdvancedSettings;
    private MaterialButton btnUpdateInstall;
    private MaterialButton btnSync;
    private MaterialButton btnSaveConfig;
    private MaterialButton btnLogout;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<LineBindingOption> lineOptions = new ArrayList<>();
    private final List<TeamBindingOption> teamOptions = new ArrayList<>();
    private ArrayAdapter<LineBindingOption> lineBindingAdapter;
    private ArrayAdapter<TeamBindingOption> teamBindingAdapter;
    private LineBindingOption selectedLineBinding;
    private TeamBindingOption selectedTeamBinding;
    private long updateDownloadId = -1L;
    private boolean updateReceiverRegistered = false;
    private boolean updateInstalling = false;
    private String lastInstallResultMessage = "";
    private final ActivityResultLauncher<Intent> updateInstallLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> handleInstallActivityResult(result.getResultCode(), result.getData())
            );

    private final BroadcastReceiver updateDownloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
                return;
            }
            long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
            if (downloadId != updateDownloadId || downloadId <= 0L) {
                return;
            }
            DownloadStatusInfo statusInfo = queryDownloadStatusInfo(downloadId);
            if (statusInfo == null) {
                updateDownloadId = -1L;
                updateInstalling = false;
                updateUpdateButtonState();
                logUpdate("Update package status unknown", "downloadId=" + downloadId);
                showUpdateStatus("更新包状态未知，请重新下载");
                return;
            }
            if (statusInfo.status == DownloadManager.STATUS_SUCCESSFUL) {
                logUpdate("Update package downloaded", "downloadId=" + downloadId);
                installDownloadedApk(downloadId);
                return;
            }
            if (statusInfo.status == DownloadManager.STATUS_FAILED) {
                updateDownloadId = -1L;
                updateInstalling = false;
                updateUpdateButtonState();
                logUpdateFailure(
                        "Update download failed",
                        "downloadId=" + downloadId
                                + "\nreason=" + statusInfo.reason
                                + "\nmessage=" + buildDownloadFailureMessage(statusInfo.reason)
                );
                showUpdateStatus(buildDownloadFailureMessage(statusInfo.reason));
            }
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_config, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        dropdownLineBinding = view.findViewById(R.id.dropdown_line_binding);
        dropdownTeamBinding = view.findViewById(R.id.dropdown_team_binding);
        tvDeviceId = view.findViewById(R.id.tv_device_id);
        tvUpdateStatus = view.findViewById(R.id.tv_update_status);
        tvAccount = view.findViewById(R.id.tv_account);
        tvPendingCount = view.findViewById(R.id.tv_pending_count);
        tvAdvancedSettings = view.findViewById(R.id.tv_advanced_settings);
        btnUpdateInstall = view.findViewById(R.id.btn_update_install);
        btnSync = view.findViewById(R.id.btn_sync);
        btnSaveConfig = view.findViewById(R.id.btn_save_config);
        btnLogout = view.findViewById(R.id.btn_logout);

        setupBindingDropdowns();

        btnUpdateInstall.setOnClickListener(v -> startUpdateDownload());
        btnSync.setOnClickListener(v -> doSync());
        btnSaveConfig.setOnClickListener(v -> saveConfig());
        btnLogout.setOnClickListener(v -> confirmLogout());
        tvAdvancedSettings.setOnClickListener(v -> showAdvancedPasswordDialog());

        loadConfig();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadConfig();
        refreshUpdateDownloadState();
        reconcileUpdateInstallState();
        showPendingInstallResultIfAny();
    }

    @Override
    public void onStart() {
        super.onStart();
        registerUpdateDownloadReceiver();
    }

    @Override
    public void onStop() {
        unregisterUpdateDownloadReceiver();
        super.onStop();
    }

    @Override
    public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void loadConfig() {
        restoreBindingOptions();
        renderConfig();
        requestBindingOptionsIfNeeded();
    }

    private void renderConfig() {
        if (!isAdded()) {
            return;
        }
        ensureDefaultBindingOptions();
        selectLineBinding(SessionManager.get().getLineCode());
        selectTeamBinding(SessionManager.get().getTeamBindingId());
        tvDeviceId.setText(emptyFallback(SessionManager.get().getDeviceId()));
        if (lastInstallResultMessage != null && !lastInstallResultMessage.trim().isEmpty()) {
            tvUpdateStatus.setText(lastInstallResultMessage.trim());
        } else {
            tvUpdateStatus.setText(formatUpdateStatus());
        }
        tvAccount.setText(emptyFallback(SessionManager.get().getAccount()));
        updateUpdateButtonState();

        int pending = DatabaseHelper.get(requireContext()).getPendingCount();
        if (pending > 0) {
            tvPendingCount.setText(String.format(Locale.getDefault(), "待同步 %d 条", pending));
        } else {
            tvPendingCount.setText("全部已同步");
        }
    }

    private void saveConfig() {
        LineBindingOption lineOption = getSelectedLineOption();
        if (lineOption != null) {
            SessionManager.get().saveLineBinding(lineOption.code, lineOption.name);
        }

        TeamBindingOption teamOption = getSelectedTeamOption();
        if (teamOption != null && teamOption.id > 0) {
            SessionManager.get().saveTeamBindingId(teamOption.id);
            SessionManager.get().saveTeamBindingName(teamOption.name);
            SessionManager.get().saveCurrentTeamTimeRanges(teamOption.timeRanges);
        }

        renderConfig();
        Toast.makeText(requireContext(), "配置已保存", Toast.LENGTH_SHORT).show();
    }

    private void doSync() {
        btnSync.setEnabled(false);
        btnSync.setText("同步中...");
        executor.execute(() -> {
            ActivationManager.get().prepareActivation(requireContext(), (ready, failureMessage) -> {
            });
            SyncService.triggerSync(requireContext(), SyncTrigger.MANUAL);
            if (!isAdded()) {
                return;
            }
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) {
                    return;
                }
                btnSync.setEnabled(true);
                btnSync.setText("立即同步");
                loadConfig();
                Toast.makeText(requireContext(), "同步任务已触发", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void showAdvancedPasswordDialog() {
        if (!isAdded()) {
            return;
        }
        EditText passwordInput = new EditText(requireContext());
        passwordInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        passwordInput.setHint("请输入密码");
        int horizontal = (int) (16 * requireContext().getResources().getDisplayMetrics().density);
        int vertical = (int) (12 * requireContext().getResources().getDisplayMetrics().density);
        passwordInput.setPadding(horizontal, vertical, horizontal, vertical);

        new AlertDialog.Builder(requireContext())
                .setTitle("高级设置")
                .setView(passwordInput)
                .setPositiveButton("确定", (dialog, which) -> {
                    String password = passwordInput.getText() == null
                            ? ""
                            : passwordInput.getText().toString().trim();
                    if (!SessionManager.get().getAdvancedSettingsPassword().equals(password)) {
                        Toast.makeText(requireContext(), "密码错误", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    startActivity(new Intent(requireContext(), AdvancedConfigActivity.class));
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmLogout() {
        new AlertDialog.Builder(requireContext())
                .setTitle("退出登录")
                .setMessage("退出后将清空当前登录态并返回登录页，本地人脸数据和待同步记录不会删除，是否继续？")
                .setPositiveButton("退出登录", (dialog, which) -> doLogout())
                .setNegativeButton("取消", null)
                .show();
    }

    private void doLogout() {
        HeartbeatManager.get(requireContext()).stop();
        PunchApplication app = PunchApplication.get();
        if (app != null) {
            app.resetPunchRecognitionState();
            app.resetPunchStatusTimeline("请重新登录后继续使用", PunchApplication.STATUS_LEVEL_INFO);
        }
        SessionManager.get().clearLoginState();
        Intent intent = new Intent(requireContext(), LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
    }

    private void setupBindingDropdowns() {
        lineBindingAdapter = new ArrayAdapter<LineBindingOption>(
                requireContext(),
                R.layout.item_binding_option,
                lineOptions
        ) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                LineBindingOption option = getItem(position);
                return bindBindingOptionView(
                        convertView,
                        parent,
                        option == null ? "" : option.primaryText()
                );
            }

            @Override
            public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                return getView(position, convertView, parent);
            }
        };
        dropdownLineBinding.setThreshold(0);
        dropdownLineBinding.setAdapter(lineBindingAdapter);
        dropdownLineBinding.setOnItemClickListener((parent, view, position, id) -> {
            selectedLineBinding = lineBindingAdapter.getItem(position);
            if (selectedLineBinding != null) {
                dropdownLineBinding.setText(selectedLineBinding.primaryText(), false);
            }
        });
        dropdownLineBinding.setOnClickListener(v -> dropdownLineBinding.showDropDown());
        dropdownLineBinding.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                dropdownLineBinding.showDropDown();
            }
        });

        teamBindingAdapter = new ArrayAdapter<TeamBindingOption>(
                requireContext(),
                R.layout.item_binding_option,
                teamOptions
        ) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                TeamBindingOption option = getItem(position);
                return bindBindingOptionView(
                        convertView,
                        parent,
                        option == null ? "" : option.primaryText()
                );
            }

            @Override
            public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                return getView(position, convertView, parent);
            }
        };
        dropdownTeamBinding.setThreshold(0);
        dropdownTeamBinding.setAdapter(teamBindingAdapter);
        dropdownTeamBinding.setOnItemClickListener((parent, view, position, id) -> {
            selectedTeamBinding = teamBindingAdapter.getItem(position);
            if (selectedTeamBinding != null) {
                dropdownTeamBinding.setText(selectedTeamBinding.primaryText(), false);
            }
        });
        dropdownTeamBinding.setOnClickListener(v -> dropdownTeamBinding.showDropDown());
        dropdownTeamBinding.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                dropdownTeamBinding.showDropDown();
            }
        });
    }

    private View bindBindingOptionView(@Nullable View convertView,
                                       @NonNull ViewGroup parent,
                                       String primaryText) {
        View view = convertView;
        if (view == null || view.findViewById(R.id.tv_binding_option_primary) == null) {
            view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_binding_option, parent, false);
        }
        TextView primary = view.findViewById(R.id.tv_binding_option_primary);
        primary.setText(primaryText == null ? "" : primaryText);
        return view;
    }

    private void ensureDefaultBindingOptions() {
        addMissingCurrentLine(lineOptions, SessionManager.get().getLineCode(), SessionManager.get().getLineName());
        addMissingCurrentTeam(
                teamOptions,
                SessionManager.get().getTeamBindingId(),
                SessionManager.get().getTeamBindingName(),
                SessionManager.get().getCurrentTeamTimeRanges()
        );
        lineBindingAdapter.notifyDataSetChanged();
        teamBindingAdapter.notifyDataSetChanged();
    }

    private void restoreBindingOptions() {
        lineOptions.clear();
        for (DeviceDto.LineOptionData option : SessionManager.get().getLineBindingOptions()) {
            if (option == null) {
                continue;
            }
            addMissingCurrentLine(lineOptions, option.code, option.name);
        }
        if (lineOptions.isEmpty() && isAdded()) {
            Map<String, String> lines = new LinkedHashMap<>(DatabaseHelper.get(requireContext()).getAvailableLines());
            for (Map.Entry<String, String> entry : lines.entrySet()) {
                addMissingCurrentLine(lineOptions, entry.getKey(), entry.getValue());
            }
        }

        teamOptions.clear();
        for (DeviceDto.TeamOptionData option : SessionManager.get().getTeamBindingOptions()) {
            if (option == null) {
                continue;
            }
            teamOptions.add(new TeamBindingOption(option.id, option.name, option.timeRanges));
        }

        ensureDefaultBindingOptions();
    }

    private void requestBindingOptionsIfNeeded() {
        boolean hasSavedLines = !SessionManager.get().getLineBindingOptions().isEmpty();
        boolean hasSavedTeams = !SessionManager.get().getTeamBindingOptions().isEmpty();
        if ((hasSavedLines || hasSavedTeams) || !SessionManager.get().isTokenValid()) {
            return;
        }

        Context appContext = requireContext().getApplicationContext();
        executor.execute(() -> {
            ApiResult<DeviceDto.DeviceConfigData> result = ApiService.fetchDeviceConfig();
            if (!result.success || result.data == null || !isAdded()) {
                return;
            }
            SessionManager.get().saveLineBindingOptions(result.data.lines);
            SessionManager.get().saveTeamBindingOptions(result.data.teams);
            persistMissingBindingDefaults(result.data);
            SessionManager.get().saveUpdateInfo(
                    result.data.updateInfo.needUpdate || result.data.needUpdate,
                    result.data.updateInfo.apkUrl,
                    result.data.updateInfo.currentVersion,
                    result.data.updateInfo.targetVersion,
                    result.data.updateInfo.versionName
            );
            UpdateManager.startBackgroundUpdateIfEligible(appContext, "config_initial_fetch");
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) {
                    return;
                }
                restoreBindingOptions();
                renderConfig();
            });
        });
    }

    private void persistMissingBindingDefaults(DeviceDto.DeviceConfigData data) {
        if (data == null) {
            return;
        }
        if (isBlank(SessionManager.get().getLineCode())) {
            DeviceDto.LineOptionData line = resolveDefaultLine(data);
            if (line != null) {
                SessionManager.get().saveLineBinding(line.code, line.name);
            }
        }
        if (SessionManager.get().getTeamBindingId() <= 0) {
            DeviceDto.TeamOptionData team = resolveDefaultTeam(data);
            if (team != null && team.id > 0) {
                SessionManager.get().saveTeamBindingId(team.id);
                SessionManager.get().saveTeamBindingName(team.name);
                SessionManager.get().saveCurrentTeamTimeRanges(team.timeRanges);
            }
        }
    }

    @Nullable
    private DeviceDto.LineOptionData resolveDefaultLine(DeviceDto.DeviceConfigData data) {
        if (!isBlank(data.lineCode) || !isBlank(data.lineName)) {
            DeviceDto.LineOptionData line = new DeviceDto.LineOptionData();
            line.code = safeString(data.lineCode).trim();
            line.name = safeString(data.lineName).trim();
            return line;
        }
        for (DeviceDto.LineOptionData line : data.lines) {
            if (line != null && (!isBlank(line.code) || !isBlank(line.name))) {
                return line;
            }
        }
        return null;
    }

    @Nullable
    private DeviceDto.TeamOptionData resolveDefaultTeam(DeviceDto.DeviceConfigData data) {
        if (data.teamBindingId > 0) {
            for (DeviceDto.TeamOptionData team : data.teams) {
                if (team != null && team.id == data.teamBindingId) {
                    return team;
                }
            }
            DeviceDto.TeamOptionData team = new DeviceDto.TeamOptionData();
            team.id = data.teamBindingId;
            team.name = safeString(data.teamBindingName).trim();
            return team;
        }
        for (DeviceDto.TeamOptionData team : data.teams) {
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

    private void selectLineBinding(String lineCode) {
        if (lineBindingAdapter == null || lineOptions.isEmpty()) {
            dropdownLineBinding.setText("", false);
            selectedLineBinding = null;
            return;
        }
        String targetCode = lineCode == null ? "" : lineCode;
        for (LineBindingOption option : lineOptions) {
            if (targetCode.equals(option.code)) {
                selectedLineBinding = option;
                dropdownLineBinding.setText(option.primaryText(), false);
                return;
            }
        }
        selectedLineBinding = lineOptions.get(0);
        dropdownLineBinding.setText(selectedLineBinding.primaryText(), false);
    }

    private void selectTeamBinding(int teamId) {
        if (teamBindingAdapter == null || teamOptions.isEmpty()) {
            dropdownTeamBinding.setText("", false);
            selectedTeamBinding = null;
            return;
        }
        for (TeamBindingOption option : teamOptions) {
            if (teamId == option.id) {
                selectedTeamBinding = option;
                dropdownTeamBinding.setText(option.primaryText(), false);
                return;
            }
        }
        selectedTeamBinding = teamOptions.get(0);
        dropdownTeamBinding.setText(selectedTeamBinding.primaryText(), false);
    }

    @Nullable
    private LineBindingOption getSelectedLineOption() {
        if (selectedLineBinding != null) {
            return selectedLineBinding;
        }
        String currentText = dropdownLineBinding.getText() == null ? "" : dropdownLineBinding.getText().toString().trim();
        for (LineBindingOption option : lineOptions) {
            if (option.toString().equals(currentText) || option.primaryText().equals(currentText)) {
                return option;
            }
        }
        return null;
    }

    @Nullable
    private TeamBindingOption getSelectedTeamOption() {
        if (selectedTeamBinding != null) {
            return selectedTeamBinding;
        }
        String currentText = dropdownTeamBinding.getText() == null ? "" : dropdownTeamBinding.getText().toString().trim();
        for (TeamBindingOption option : teamOptions) {
            if (option.toString().equals(currentText) || option.primaryText().equals(currentText)) {
                return option;
            }
        }
        return null;
    }

    private void addMissingCurrentLine(List<LineBindingOption> options, String code, String name) {
        String safeCode = code == null ? "" : code.trim();
        String safeName = name == null ? "" : name.trim();
        if (safeCode.isEmpty() && safeName.isEmpty()) {
            return;
        }
        for (LineBindingOption option : options) {
            if (safeCode.equals(option.code)) {
                return;
            }
        }
        options.add(0, new LineBindingOption(safeCode, safeName));
    }

    private void addMissingCurrentTeam(List<TeamBindingOption> options, int id, String name, List<String> timeRanges) {
        String safeName = name == null ? "" : name.trim();
        if (id <= 0 && safeName.isEmpty()) {
            return;
        }
        for (TeamBindingOption option : options) {
            if (id == option.id) {
                return;
            }
        }
        options.add(0, new TeamBindingOption(id, safeName, timeRanges));
    }

    private String formatUpdateStatus() {
        if (!SessionManager.get().isUpdateNeeded()) {
            return "已是最新版本";
        }
        String currentVersion = emptyFallback(resolveCurrentVersion());
        String targetVersion = emptyFallback(SessionManager.get().getUpdateTargetVersion());
        String versionName = emptyFallback(SessionManager.get().getUpdateVersionName());
        if (!shouldShowUpdateButton()) {
            return "当前版本 " + currentVersion + "，已是最新";
        }
        if ("-".equals(versionName)) {
            return "当前 " + currentVersion + "，可升级至 " + targetVersion;
        }
        return versionName + " / " + currentVersion + " -> " + targetVersion;
    }

    private boolean shouldShowUpdateButton() {
        if (!SessionManager.get().isUpdateNeeded()) {
            return false;
        }
        String apkUrl = SessionManager.get().getUpdateApkUrl();
        if (apkUrl == null || apkUrl.trim().isEmpty()) {
            return false;
        }
        return compareVersions(resolveCurrentVersion(), SessionManager.get().getUpdateTargetVersion()) < 0;
    }

    private String resolveCurrentVersion() {
        String installedVersion = resolveInstalledVersion();
        if (installedVersion != null && !installedVersion.trim().isEmpty()) {
            return installedVersion.trim();
        }
        String syncedVersion = SessionManager.get().getUpdateCurrentVersion();
        if (syncedVersion != null && !syncedVersion.trim().isEmpty()) {
            return syncedVersion.trim();
        }
        return "";
    }

    private String resolveInstalledVersion() {
        try {
            return requireContext()
                    .getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0)
                    .versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private int compareVersions(String left, String right) {
        String[] leftParts = normalizeVersion(left);
        String[] rightParts = normalizeVersion(right);
        int length = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < length; i++) {
            int leftValue = i < leftParts.length ? parseVersionPart(leftParts[i]) : 0;
            int rightValue = i < rightParts.length ? parseVersionPart(rightParts[i]) : 0;
            if (leftValue != rightValue) {
                return Integer.compare(leftValue, rightValue);
            }
        }
        return 0;
    }

    private String[] normalizeVersion(String version) {
        if (version == null || version.trim().isEmpty()) {
            return new String[0];
        }
        return version.trim().split("\\.");
    }

    private int parseVersionPart(String part) {
        if (part == null || part.isEmpty()) {
            return 0;
        }
        String digits = part.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
    private void startUpdateDownload() {
        if (!isAdded()) {
            return;
        }
        if (isUpdateReadyToInstall()) {
            logUpdate("检测到更新包已下载", "downloadId=" + updateDownloadId);
            installDownloadedApk(updateDownloadId);
            return;
        }
        if (isUpdateDownloadRunning()) {
            logUpdate("更新包正在下载", "downloadId=" + updateDownloadId);
            showUpdateStatus("更新包正在下载，请稍候");
            updateUpdateButtonState();
            return;
        }
        String apkUrl = SessionManager.get().getUpdateApkUrl();
        if (apkUrl == null || apkUrl.trim().isEmpty()) {
            logUpdateFailure("未获取到更新地址", "");
            showUpdateStatus("未获取到更新地址");
            return;
        }
        DownloadManager downloadManager = (DownloadManager) requireContext().getSystemService(Context.DOWNLOAD_SERVICE);
        if (downloadManager == null) {
            logUpdateFailure("系统下载服务不可用", "");
            showUpdateStatus("系统下载服务不可用");
            return;
        }
        File downloadDir = requireContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (downloadDir == null) {
            logUpdateFailure("下载目录不可用", "");
            showUpdateStatus("下载目录不可用");
            return;
        }
        File apkFile = new File(downloadDir, "faceapk-update.apk");
        if (apkFile.exists() && !apkFile.delete()) {
            apkFile = new File(downloadDir, "faceapk-update-" + System.currentTimeMillis() + ".apk");
        }

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(apkUrl.trim()))
                .setTitle("faceapk 更新")
                .setDescription("正在下载更新安装包")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setMimeType("application/vnd.android.package-archive")
                .setDestinationUri(Uri.fromFile(apkFile))
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true);

        updateDownloadId = downloadManager.enqueue(request);
        updateInstalling = false;
        logUpdate(
                "开始下载更新包",
                "downloadId=" + updateDownloadId
                        + "\napkUrl=" + apkUrl.trim()
                        + "\npath=" + apkFile.getAbsolutePath()
        );
        updateUpdateButtonState();
        showUpdateStatus("开始下载更新包");
    }

    private void installDownloadedApk(long downloadId) {
        if (!isAdded()) {
            return;
        }
        updateInstalling = true;
        updateUpdateButtonState();
        DownloadManager downloadManager = (DownloadManager) requireContext().getSystemService(Context.DOWNLOAD_SERVICE);
        if (downloadManager == null) {
            logUpdateFailure("系统下载服务不可用", "downloadId=" + downloadId);
            updateInstalling = false;
            updateUpdateButtonState();
            return;
        }
        Uri apkUri = downloadManager.getUriForDownloadedFile(downloadId);
        if (apkUri == null) {
            File apkFile = new File(requireContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "faceapk-update.apk");
            if (!apkFile.exists()) {
                logUpdateFailure("更新包不存在", "downloadId=" + downloadId);
                showUpdateStatus("更新安装包不存在，请重新下载");
                updateInstalling = false;
                updateDownloadId = -1L;
                updateUpdateButtonState();
                return;
            }
            apkUri = FileProvider.getUriForFile(
                    requireContext(),
                    requireContext().getPackageName() + ".fileprovider",
                    apkFile
            );
        }
        String apkPath = resolveDownloadedApkPath(downloadId);
        logUpdate("Start installing update package", "downloadId=" + downloadId + "\nuri=" + apkUri + "\napkPath=" + apkPath);
        showUpdateStatus("正在安装更新包...");
        UpdateManager.StartResult startResult = UpdateManager.startInstall(
                requireActivity(),
                apkUri,
                apkPath,
                SessionManager.get().getUpdateTargetVersion(),
                updateInstallLauncher
        );
        if (!startResult.success) {
            showUpdateStatus("安装启动失败：" + startResult.message);
            updateInstalling = false;
            updateDownloadId = -1L;
            updateUpdateButtonState();
            return;
        }
        showUpdateStatus(startResult.deviceOwnerInstall ? "后台安装已提交，请等待安装结果" : "已打开系统安装界面");
        if (startResult.deviceOwnerInstall) {
            updateInstalling = false;
            updateUpdateButtonState();
        }
    }
    private void refreshUpdateDownloadState() {
        if (!isAdded()) {
            return;
        }
        if (!shouldShowUpdateButton()) {
            updateDownloadId = -1L;
            updateInstalling = false;
            updateUpdateButtonState();
            return;
        }
        if (updateDownloadId <= 0L) {
            updateInstalling = false;
            updateUpdateButtonState();
            return;
        }
        DownloadStatusInfo statusInfo = queryDownloadStatusInfo(updateDownloadId);
        if (statusInfo == null || statusInfo.status == DownloadManager.STATUS_FAILED) {
            updateDownloadId = -1L;
            updateInstalling = false;
        } else if (statusInfo.status == DownloadManager.STATUS_SUCCESSFUL) {
            updateInstalling = false;
        }
        updateUpdateButtonState();
    }

    private void handleInstallActivityResult(int resultCode, @Nullable Intent data) {
        UpdateManager.handleSystemInstallerResult(requireContext(), resultCode, data);
        updateInstalling = false;
        updateUpdateButtonState();
    }

    private String resolveDownloadedApkPath(long downloadId) {
        DownloadStatusInfo statusInfo = queryDownloadStatusInfo(downloadId);
        if (statusInfo != null && statusInfo.localUri != null && !statusInfo.localUri.trim().isEmpty()) {
            try {
                Uri uri = Uri.parse(statusInfo.localUri.trim());
                if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
                    return uri.getPath();
                }
            } catch (Exception ignored) {
            }
        }
        File downloadDir = requireContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (downloadDir == null) {
            return "";
        }
        return new File(downloadDir, "faceapk-update.apk").getAbsolutePath();
    }

    private void reconcileUpdateInstallState() {
        UpdateManager.reconcileInstallState(requireContext());
    }

    private void showPendingInstallResultIfAny() {
        String status = SessionManager.get().getUpdateInstallStatus();
        if (Constants.UPDATE_INSTALL_STATUS_NONE.equals(status)
                || Constants.UPDATE_INSTALL_STATUS_PENDING.equals(status)) {
            return;
        }
        String message = SessionManager.get().getUpdateInstallMessage();
        if (message != null && !message.trim().isEmpty()) {
            lastInstallResultMessage = "最近安装结果：" + message.trim();
        } else if (Constants.UPDATE_INSTALL_STATUS_SUCCESS.equals(status)) {
            lastInstallResultMessage = "最近安装结果：应用更新安装成功";
        } else {
            lastInstallResultMessage = "最近安装结果：应用更新安装未完成";
        }
        SessionManager.get().clearUpdateInstallState();
        renderConfig();
    }

    private void showUpdateStatus(String message) {
        if (message == null || message.trim().isEmpty()) {
            return;
        }
        lastInstallResultMessage = message.trim();
        if (tvUpdateStatus != null) {
            tvUpdateStatus.setText(lastInstallResultMessage);
        }
    }

    private boolean isUpdateDownloadRunning() {
        DownloadStatusInfo statusInfo = queryDownloadStatusInfo(updateDownloadId);
        return statusInfo != null
                && (statusInfo.status == DownloadManager.STATUS_PENDING
                || statusInfo.status == DownloadManager.STATUS_RUNNING
                || statusInfo.status == DownloadManager.STATUS_PAUSED);
    }

    private boolean isUpdateReadyToInstall() {
        DownloadStatusInfo statusInfo = queryDownloadStatusInfo(updateDownloadId);
        return statusInfo != null && statusInfo.status == DownloadManager.STATUS_SUCCESSFUL;
    }

    @Nullable
    private DownloadStatusInfo queryDownloadStatusInfo(long downloadId) {
        if (!isAdded() || downloadId <= 0L) {
            return null;
        }
        DownloadManager downloadManager = (DownloadManager) requireContext().getSystemService(Context.DOWNLOAD_SERVICE);
        if (downloadManager == null) {
            return null;
        }
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(downloadId);
        try (Cursor cursor = downloadManager.query(query)) {
            if (cursor != null && cursor.moveToFirst()) {
                int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                int reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                String localUri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI));
                return new DownloadStatusInfo(status, reason, localUri);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String buildDownloadFailureMessage(int reason) {
        switch (reason) {
            case DownloadManager.ERROR_CANNOT_RESUME:
                return "更新包下载失败：无法继续下载";
            case DownloadManager.ERROR_DEVICE_NOT_FOUND:
                return "更新包下载失败：未找到存储设备";
            case DownloadManager.ERROR_FILE_ALREADY_EXISTS:
                return "更新包下载失败：目标文件已存在";
            case DownloadManager.ERROR_FILE_ERROR:
                return "更新包下载失败：文件写入异常";
            case DownloadManager.ERROR_HTTP_DATA_ERROR:
                return "更新包下载失败：网络数据错误";
            case DownloadManager.ERROR_INSUFFICIENT_SPACE:
                return "更新包下载失败：存储空间不足";
            case DownloadManager.ERROR_TOO_MANY_REDIRECTS:
                return "更新包下载失败：下载地址重定向过多";
            case DownloadManager.ERROR_UNHANDLED_HTTP_CODE:
                return "更新包下载失败：服务端返回异常状态";
            case DownloadManager.ERROR_UNKNOWN:
            default:
                return "更新包下载失败，请稍后重试";
        }
    }

    private void updateUpdateButtonState() {
        if (btnUpdateInstall == null) {
            return;
        }
        if (!shouldShowUpdateButton()) {
            btnUpdateInstall.setVisibility(View.GONE);
            return;
        }
        btnUpdateInstall.setVisibility(View.VISIBLE);
        if (SessionManager.get().isUpdateInstallPending()) {
            btnUpdateInstall.setEnabled(false);
            btnUpdateInstall.setText(UPDATE_BUTTON_INSTALLING);
            return;
        }
        if (updateInstalling) {
            btnUpdateInstall.setEnabled(false);
            btnUpdateInstall.setText(UPDATE_BUTTON_INSTALLING);
            return;
        }
        if (isUpdateDownloadRunning()) {
            btnUpdateInstall.setEnabled(false);
            btnUpdateInstall.setText(UPDATE_BUTTON_DOWNLOADING);
            return;
        }
        btnUpdateInstall.setEnabled(true);
        btnUpdateInstall.setText(UPDATE_BUTTON_IDLE);
    }

    private String emptyFallback(String value) {
        return (value == null || value.trim().isEmpty()) ? "-" : value;
    }

    private void logUpdate(String title, String detail) {
        InteractionLogger.logBusiness(
                InteractionLogger.GROUP_UPDATE,
                title,
                detail == null ? "" : detail
        );
    }

    private void logUpdateFailure(String title, String detail) {
        InteractionLogger.logBusinessFailure(
                InteractionLogger.GROUP_UPDATE,
                title,
                detail == null ? "" : detail
        );
    }

    private void registerUpdateDownloadReceiver() {
        if (!isAdded() || updateReceiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(updateDownloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            requireContext().registerReceiver(updateDownloadReceiver, filter);
        }
        updateReceiverRegistered = true;
    }

    private void unregisterUpdateDownloadReceiver() {
        if (!isAdded() || !updateReceiverRegistered) {
            return;
        }
        try {
            requireContext().unregisterReceiver(updateDownloadReceiver);
        } catch (Exception ignored) {
        }
        updateReceiverRegistered = false;
    }
    private static final class DownloadStatusInfo {
        private final int status;
        private final int reason;
        private final String localUri;

        private DownloadStatusInfo(int status, int reason, @Nullable String localUri) {
            this.status = status;
            this.reason = reason;
            this.localUri = localUri == null ? "" : localUri;
        }
    }

    private static final class LineBindingOption {
        private final String code;
        private final String name;

        private LineBindingOption(String code, String name) {
            this.code = code == null ? "" : code;
            this.name = name == null ? "" : name;
        }

        private String primaryText() {
            if (!name.isEmpty()) {
                return name;
            }
            return code.isEmpty() ? "-" : code;
        }

        @NonNull
        @Override
        public String toString() {
            if (code.isEmpty() && name.isEmpty()) {
                return "-";
            }
            if (name.isEmpty() || name.equals(code)) {
                return code;
            }
            return name + " (" + code + ")";
        }
    }

    private static final class TeamBindingOption {
        private final int id;
        private final String name;
        private final List<String> timeRanges;

        private TeamBindingOption(int id, String name, List<String> timeRanges) {
            this.id = id;
            this.name = name == null ? "" : name;
            this.timeRanges = timeRanges == null ? new ArrayList<>() : new ArrayList<>(timeRanges);
        }

        private String primaryText() {
            if (!name.isEmpty()) {
                return name;
            }
            return id > 0 ? String.valueOf(id) : "-";
        }

        @NonNull
        @Override
        public String toString() {
            if (id <= 0 && name.isEmpty()) {
                return "-";
            }
            if (name.isEmpty()) {
                return String.valueOf(id);
            }
            if (id <= 0) {
                return name;
            }
            return name + " (" + id + ")";
        }
    }

}

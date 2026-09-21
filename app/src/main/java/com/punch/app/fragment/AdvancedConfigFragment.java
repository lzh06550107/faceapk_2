package com.punch.app.fragment;

import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.hardware.Camera;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.punch.app.R;
import com.punch.app.activity.InteractionLogActivity;
import com.punch.app.activity.LocalEmployeeDebugActivity;
import com.punch.app.activation.BaiduDeviceFingerprint;
import com.punch.app.face.FaceManager;
import com.punch.app.network.InteractionLogger;
import com.punch.app.network.dto.DeviceDto;
import com.punch.app.receiver.KioskDeviceAdminReceiver;
import com.punch.app.utils.Constants;
import com.punch.app.utils.KioskManager;
import com.punch.app.utils.PunchTimeResolver;
import com.punch.app.utils.ScreenTimeoutPolicy;
import com.punch.app.utils.ScreenTimeoutPolicyManager;
import com.punch.app.utils.SessionManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AdvancedConfigFragment extends Fragment {
    private static final String[] DISTANCE_LABELS = {"近距离", "标准", "远距离"};
    private static final String[] DISTANCE_VALUES = {
            Constants.DISTANCE_MODE_NEAR,
            Constants.DISTANCE_MODE_STANDARD,
            Constants.DISTANCE_MODE_FAR
    };
    private static final String[] PUNCH_SPEECH_MODE_LABELS = {"完整播报", "只播姓名", "只播成功"};
    private static final String[] PUNCH_SPEECH_MODE_VALUES = {
            Constants.PUNCH_SPEECH_MODE_FULL,
            Constants.PUNCH_SPEECH_MODE_NAME,
            Constants.PUNCH_SPEECH_MODE_SUCCESS
    };
    private static final String[] PUNCH_SPEECH_RATE_LABELS = {"1.0x", "1.2x", "1.3x", "1.5x", "1.8x", "2.0x"};
    private static final float[] PUNCH_SPEECH_RATE_VALUES = {1.0f, 1.2f, 1.3f, 1.5f, 1.8f, 2.0f};
    private static final Integer[] TIMEOUT_OPTIONS = {3, 5, 8, 10};
    private static final String[] SCREEN_TIMEOUT_LABELS = {
            "\u4fdd\u6301\u5e38\u4eae",
            "30 \u79d2",
            "1 \u5206\u949f",
            "2 \u5206\u949f",
            "5 \u5206\u949f",
            "10 \u5206\u949f"
    };
    private static final long[] SCREEN_TIMEOUT_VALUES_MS = {
            ScreenTimeoutPolicy.KEEP_SCREEN_ON,
            30_000L,
            60_000L,
            120_000L,
            300_000L,
            600_000L
    };
    private static final int PUNCH_INTERVAL_STEP_MINUTES = 1;
    private static final int PUNCH_INTERVAL_MIN_MINUTES = 0;
    private static final int PUNCH_INTERVAL_MAX_MINUTES = 240;
    private static final int PUNCH_RESULT_DISPLAY_STEP_MS = 100;
    private static final int PUNCH_RESULT_DISPLAY_MIN_MS = 0;
    private static final int PUNCH_RESULT_DISPLAY_MAX_MS = 5000;
    private static final int RECOGNITION_FRAME_INTERVAL_STEP_MS = 50;
    private static final int RECOGNITION_FRAME_INTERVAL_MIN_MS = 250;
    private static final int RECOGNITION_FRAME_INTERVAL_MAX_MS = 1000;
    private static final int SUCCESS_COOLDOWN_STEP_MS = 100;
    private static final int SUCCESS_COOLDOWN_MIN_MS = 0;
    private static final int SUCCESS_COOLDOWN_MAX_MS = 3000;
    private static final long CLEAR_DEVICE_OWNER_POLL_INTERVAL_MS = 1000L;
    private static final long CLEAR_DEVICE_OWNER_TIMEOUT_MS = 20000L;
    private EditText etBaseUrl;
    private EditText etCompanyId;
    private EditText etPunchIntervalMinutes;
    private EditText etPunchResultDisplayMs;
    private EditText etRecognitionFrameIntervalMs;
    private EditText etSuccessCooldownMs;
    private LinearLayout layoutOvertimeOptions;
    private TextView tvBaiduFingerprint;
    private TextView tvActivationMode;
    private TextView tvActivationStatus;
    private TextView tvActivationTime;
    private TextView tvMatchThresholdValue;
    private TextView tvFaceThresholdValue;
    private TextView tvLivenessThresholdValue;
    private MaterialAutoCompleteTextView dropdownDistanceMode;
    private MaterialAutoCompleteTextView dropdownRecognitionTimeout;
    private MaterialAutoCompleteTextView dropdownScreenTimeout;
    private TextView tvScreenTimeoutStatus;
    private MaterialAutoCompleteTextView dropdownPunchSpeechMode;
    private MaterialAutoCompleteTextView dropdownPunchSpeechRate;
    private SeekBar seekMatchThreshold;
    private SeekBar seekFaceThreshold;
    private SeekBar seekLivenessThreshold;
    private Switch switchLiveness;
    private Switch switchMaskDetect;
    private Switch switchFastPunch;
    private Switch switchShowPunchResultCard;
    private Button btnCopyFingerprint;
    private Button btnSaveConfig;
    private Button btnKioskMode;
    private MaterialButton btnViewLogs;
    private MaterialButton btnViewLocalConfig;
    private MaterialButton btnViewLocalEmployees;
    private MaterialButton btnClearDeviceOwner;
    private MaterialButton btnPunchIntervalMinus;
    private MaterialButton btnPunchIntervalPlus;
    private MaterialButton btnPunchResultDisplayMinus;
    private MaterialButton btnPunchResultDisplayPlus;
    private MaterialButton btnRecognitionFrameIntervalMinus;
    private MaterialButton btnRecognitionFrameIntervalPlus;
    private MaterialButton btnSuccessCooldownMinus;
    private MaterialButton btnSuccessCooldownPlus;
    private final List<Switch> overtimeSwitches = new ArrayList<>();

    private String selectedDistanceModeValue = DISTANCE_VALUES[1];
    private int selectedRecognitionTimeoutValue = TIMEOUT_OPTIONS[0];
    private long selectedScreenTimeoutMs = ScreenTimeoutPolicy.DEFAULT_TIMEOUT_MS;
    private String selectedPunchSpeechModeValue = Constants.DEFAULT_PUNCH_SPEECH_MODE;
    private float selectedPunchSpeechRateValue = Constants.DEFAULT_PUNCH_SPEECH_RATE;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean clearingDeviceOwner = false;
    private long clearDeviceOwnerStartedAt = 0L;
    private Context appContext;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        appContext = context.getApplicationContext();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_advanced_config, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        appContext = view.getContext().getApplicationContext();

        etBaseUrl = view.findViewById(R.id.et_base_url);
        etCompanyId = view.findViewById(R.id.et_company_id);
        etPunchIntervalMinutes = view.findViewById(R.id.et_punch_interval_minutes);
        etPunchResultDisplayMs = view.findViewById(R.id.et_punch_result_display_ms);
        etRecognitionFrameIntervalMs = view.findViewById(R.id.et_recognition_frame_interval_ms);
        etSuccessCooldownMs = view.findViewById(R.id.et_success_cooldown_ms);
        layoutOvertimeOptions = view.findViewById(R.id.layout_overtime_options);
        tvBaiduFingerprint = view.findViewById(R.id.tv_baidu_fingerprint);
        tvActivationMode = view.findViewById(R.id.tv_activation_mode);
        tvActivationStatus = view.findViewById(R.id.tv_activation_status);
        tvActivationTime = view.findViewById(R.id.tv_activation_time);
        tvMatchThresholdValue = view.findViewById(R.id.tv_threshold_value);
        tvFaceThresholdValue = view.findViewById(R.id.tv_face_threshold_value);
        tvLivenessThresholdValue = view.findViewById(R.id.tv_liveness_threshold_value);
        dropdownDistanceMode = view.findViewById(R.id.dropdown_distance_mode);
        dropdownRecognitionTimeout = view.findViewById(R.id.dropdown_recognition_timeout);
        dropdownScreenTimeout = view.findViewById(R.id.dropdown_screen_timeout);
        tvScreenTimeoutStatus = view.findViewById(R.id.tv_screen_timeout_status);
        dropdownPunchSpeechMode = view.findViewById(R.id.dropdown_punch_speech_mode);
        dropdownPunchSpeechRate = view.findViewById(R.id.dropdown_punch_speech_rate);
        seekMatchThreshold = view.findViewById(R.id.seek_match_threshold);
        seekFaceThreshold = view.findViewById(R.id.seek_face_threshold);
        seekLivenessThreshold = view.findViewById(R.id.seek_liveness_threshold);
        switchLiveness = view.findViewById(R.id.switch_liveness);
        switchMaskDetect = view.findViewById(R.id.switch_mask_detect);
        switchFastPunch = view.findViewById(R.id.switch_fast_punch);
        switchShowPunchResultCard = view.findViewById(R.id.switch_show_punch_result_card);
        btnCopyFingerprint = view.findViewById(R.id.btn_copy_fingerprint);
        btnSaveConfig = view.findViewById(R.id.btn_save_config);
        btnKioskMode = view.findViewById(R.id.btn_kiosk_mode);
        btnViewLogs = view.findViewById(R.id.btn_view_logs);
        btnViewLocalConfig = view.findViewById(R.id.btn_view_local_config);
        btnViewLocalEmployees = view.findViewById(R.id.btn_view_local_employees);
        btnClearDeviceOwner = view.findViewById(R.id.btn_clear_device_owner);
        btnPunchIntervalMinus = view.findViewById(R.id.btn_punch_interval_minus);
        btnPunchIntervalPlus = view.findViewById(R.id.btn_punch_interval_plus);
        btnPunchResultDisplayMinus = view.findViewById(R.id.btn_punch_result_display_minus);
        btnPunchResultDisplayPlus = view.findViewById(R.id.btn_punch_result_display_plus);
        btnRecognitionFrameIntervalMinus = view.findViewById(R.id.btn_recognition_frame_interval_minus);
        btnRecognitionFrameIntervalPlus = view.findViewById(R.id.btn_recognition_frame_interval_plus);
        btnSuccessCooldownMinus = view.findViewById(R.id.btn_success_cooldown_minus);
        btnSuccessCooldownPlus = view.findViewById(R.id.btn_success_cooldown_plus);

        setupConfigDropdowns();
        setupThresholdListeners();

        btnCopyFingerprint.setOnClickListener(v -> copyBaiduFingerprint());
        btnSaveConfig.setOnClickListener(v -> saveConfig());
        btnKioskMode.setOnClickListener(v -> toggleKioskMode());
        btnViewLogs.setOnClickListener(v -> startActivity(new Intent(requireContext(), InteractionLogActivity.class)));
        btnViewLocalConfig.setOnClickListener(v -> showLocalConfigDialog());
        btnViewLocalEmployees.setOnClickListener(v -> startActivity(new Intent(requireContext(), LocalEmployeeDebugActivity.class)));
        btnClearDeviceOwner.setOnClickListener(v -> confirmClearDeviceOwner());
        btnPunchIntervalMinus.setOnClickListener(v -> adjustPunchInterval(-PUNCH_INTERVAL_STEP_MINUTES));
        btnPunchIntervalPlus.setOnClickListener(v -> adjustPunchInterval(PUNCH_INTERVAL_STEP_MINUTES));
        btnPunchResultDisplayMinus.setOnClickListener(v -> adjustIntField(
                etPunchResultDisplayMs,
                -PUNCH_RESULT_DISPLAY_STEP_MS,
                PUNCH_RESULT_DISPLAY_MIN_MS,
                PUNCH_RESULT_DISPLAY_MAX_MS,
                SessionManager.get().getPunchResultDisplayMs()
        ));
        btnPunchResultDisplayPlus.setOnClickListener(v -> adjustIntField(
                etPunchResultDisplayMs,
                PUNCH_RESULT_DISPLAY_STEP_MS,
                PUNCH_RESULT_DISPLAY_MIN_MS,
                PUNCH_RESULT_DISPLAY_MAX_MS,
                SessionManager.get().getPunchResultDisplayMs()
        ));
        btnRecognitionFrameIntervalMinus.setOnClickListener(v -> adjustIntField(
                etRecognitionFrameIntervalMs,
                -RECOGNITION_FRAME_INTERVAL_STEP_MS,
                RECOGNITION_FRAME_INTERVAL_MIN_MS,
                RECOGNITION_FRAME_INTERVAL_MAX_MS,
                SessionManager.get().getRecognitionFrameIntervalMs()
        ));
        btnRecognitionFrameIntervalPlus.setOnClickListener(v -> adjustIntField(
                etRecognitionFrameIntervalMs,
                RECOGNITION_FRAME_INTERVAL_STEP_MS,
                RECOGNITION_FRAME_INTERVAL_MIN_MS,
                RECOGNITION_FRAME_INTERVAL_MAX_MS,
                SessionManager.get().getRecognitionFrameIntervalMs()
        ));
        btnSuccessCooldownMinus.setOnClickListener(v -> adjustIntField(
                etSuccessCooldownMs,
                -SUCCESS_COOLDOWN_STEP_MS,
                SUCCESS_COOLDOWN_MIN_MS,
                SUCCESS_COOLDOWN_MAX_MS,
                SessionManager.get().getSuccessCooldownMs()
        ));
        btnSuccessCooldownPlus.setOnClickListener(v -> adjustIntField(
                etSuccessCooldownMs,
                SUCCESS_COOLDOWN_STEP_MS,
                SUCCESS_COOLDOWN_MIN_MS,
                SUCCESS_COOLDOWN_MAX_MS,
                SessionManager.get().getSuccessCooldownMs()
        ));
        renderConfig();
    }

    @Override
    public void onResume() {
        super.onResume();
        renderConfig();
        if (clearingDeviceOwner) {
            scheduleClearDeviceOwnerStateCheck(0L);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mainHandler.removeCallbacksAndMessages(null);
    }

    private void setupConfigDropdowns() {
        ArrayAdapter<String> distanceModeAdapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                DISTANCE_LABELS
        );
        dropdownDistanceMode.setThreshold(0);
        dropdownDistanceMode.setAdapter(distanceModeAdapter);
        dropdownDistanceMode.setOnItemClickListener((parent, view, position, id) ->
                selectedDistanceModeValue = DISTANCE_VALUES[position]);
        dropdownDistanceMode.setOnClickListener(v -> dropdownDistanceMode.showDropDown());
        dropdownDistanceMode.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                dropdownDistanceMode.showDropDown();
            }
        });

        ArrayAdapter<Integer> recognitionTimeoutAdapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                TIMEOUT_OPTIONS
        );
        dropdownRecognitionTimeout.setThreshold(0);
        dropdownRecognitionTimeout.setAdapter(recognitionTimeoutAdapter);
        dropdownRecognitionTimeout.setOnItemClickListener((parent, view, position, id) ->
                selectedRecognitionTimeoutValue = TIMEOUT_OPTIONS[position]);
        dropdownRecognitionTimeout.setOnClickListener(v -> dropdownRecognitionTimeout.showDropDown());
        dropdownRecognitionTimeout.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                dropdownRecognitionTimeout.showDropDown();
            }
        });

        ArrayAdapter<String> screenTimeoutAdapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                SCREEN_TIMEOUT_LABELS
        );
        dropdownScreenTimeout.setThreshold(0);
        dropdownScreenTimeout.setAdapter(screenTimeoutAdapter);
        dropdownScreenTimeout.setOnItemClickListener((parent, view, position, id) ->
                selectedScreenTimeoutMs = SCREEN_TIMEOUT_VALUES_MS[position]);
        dropdownScreenTimeout.setOnClickListener(v -> dropdownScreenTimeout.showDropDown());
        dropdownScreenTimeout.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                dropdownScreenTimeout.showDropDown();
            }
        });

        ArrayAdapter<String> punchSpeechModeAdapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                PUNCH_SPEECH_MODE_LABELS
        );
        dropdownPunchSpeechMode.setThreshold(0);
        dropdownPunchSpeechMode.setAdapter(punchSpeechModeAdapter);
        dropdownPunchSpeechMode.setOnItemClickListener((parent, view, position, id) ->
                selectedPunchSpeechModeValue = PUNCH_SPEECH_MODE_VALUES[position]);
        dropdownPunchSpeechMode.setOnClickListener(v -> dropdownPunchSpeechMode.showDropDown());
        dropdownPunchSpeechMode.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                dropdownPunchSpeechMode.showDropDown();
            }
        });

        ArrayAdapter<String> punchSpeechRateAdapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                PUNCH_SPEECH_RATE_LABELS
        );
        dropdownPunchSpeechRate.setThreshold(0);
        dropdownPunchSpeechRate.setAdapter(punchSpeechRateAdapter);
        dropdownPunchSpeechRate.setOnItemClickListener((parent, view, position, id) ->
                selectedPunchSpeechRateValue = PUNCH_SPEECH_RATE_VALUES[position]);
        dropdownPunchSpeechRate.setOnClickListener(v -> dropdownPunchSpeechRate.showDropDown());
        dropdownPunchSpeechRate.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                dropdownPunchSpeechRate.showDropDown();
            }
        });
    }

    private void setupThresholdListeners() {
        seekMatchThreshold.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvMatchThresholdValue.setText(String.format(Locale.getDefault(), "%d", progress));
            }
        });
        seekFaceThreshold.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvFaceThresholdValue.setText(String.format(Locale.getDefault(), "%d", progress));
            }
        });
        seekLivenessThreshold.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvLivenessThresholdValue.setText(String.format(Locale.getDefault(), "%.2f", progress / 100f));
            }
        });
    }

    private void renderConfig() {
        if (!isAdded()) {
            return;
        }

        etBaseUrl.setText(SessionManager.get().getBaseUrl());
        etCompanyId.setText(String.valueOf(SessionManager.get().getCompanyId()));
        bindBaiduFingerprint();
        tvActivationMode.setText(formatActivationMode(SessionManager.get().getActivationMode()));
        tvActivationStatus.setText(formatActivationStatus(SessionManager.get().getActivationStatus()));
        tvActivationTime.setText(formatActivationTime(SessionManager.get().getLastActivationTime()));

        int matchThreshold = Math.round(SessionManager.get().getMatchThreshold() * 100);
        seekMatchThreshold.setProgress(matchThreshold);
        tvMatchThresholdValue.setText(String.format(Locale.getDefault(), "%d", matchThreshold));

        int faceThreshold = Math.round(SessionManager.get().getFaceThreshold() * 100);
        seekFaceThreshold.setProgress(faceThreshold);
        tvFaceThresholdValue.setText(String.format(Locale.getDefault(), "%d", faceThreshold));

        int livenessThreshold = Math.round(SessionManager.get().getLivenessThreshold() * 100);
        seekLivenessThreshold.setProgress(livenessThreshold);
        tvLivenessThresholdValue.setText(String.format(Locale.getDefault(), "%.2f", livenessThreshold / 100f));

        switchLiveness.setChecked(SessionManager.get().isLivenessCheck());
        switchMaskDetect.setChecked(SessionManager.get().isMaskDetectEnabled());
        selectDistanceMode(SessionManager.get().getRecognitionDistanceMode());
        selectRecognitionTimeout(SessionManager.get().getRecognitionTimeoutSeconds());
        selectScreenTimeout(SessionManager.get().getScreenTimeoutMs());
        updateScreenTimeoutControlState();
        etPunchIntervalMinutes.setText(String.valueOf(SessionManager.get().getPunchTimeWindowMinutes()));
        switchFastPunch.setChecked(SessionManager.get().isFastPunchEnabled());
        switchShowPunchResultCard.setChecked(SessionManager.get().shouldShowPunchResultCard());
        etPunchResultDisplayMs.setText(String.valueOf(SessionManager.get().getPunchResultDisplayMs()));
        selectPunchSpeechMode(SessionManager.get().getPunchSpeechMode());
        selectPunchSpeechRate(SessionManager.get().getPunchSpeechRate());
        etRecognitionFrameIntervalMs.setText(String.valueOf(SessionManager.get().getRecognitionFrameIntervalMs()));
        etSuccessCooldownMs.setText(String.valueOf(SessionManager.get().getSuccessCooldownMs()));
        renderOvertimeOptions(SessionManager.get().getOvertimeSignOutOptions());
        updateKioskButtonState();
        updateClearDeviceOwnerButtonState();
    }

    private void renderOvertimeOptions(List<String> enabledOptions) {
        if (layoutOvertimeOptions == null || !isAdded()) {
            return;
        }
        overtimeSwitches.clear();
        layoutOvertimeOptions.removeAllViews();

        List<String> timeRanges = SessionManager.get().getCurrentTeamTimeRanges();
        if (timeRanges == null || timeRanges.isEmpty()) {
            TextView emptyView = new TextView(requireContext());
            emptyView.setText("\u5f53\u524d\u73ed\u7ec4\u6682\u65e0\u53ef\u914d\u7f6e\u7684\u4e0b\u73ed\u9879");
            emptyView.setTextSize(12);
            layoutOvertimeOptions.addView(emptyView);
            return;
        }

        List<String> optionLabels = new ArrayList<>();
        for (String timeRange : timeRanges) {
            if (timeRange == null || timeRange.trim().isEmpty()) {
                continue;
            }
            optionLabels.add(buildSignOutOptionLabel(timeRange));
        }
        boolean useDefaultLastOption = !SessionManager.get().hasOvertimeSignOutOptionsConfig()
                && !optionLabels.isEmpty();
        String defaultEnabledOption = useDefaultLastOption
                ? optionLabels.get(optionLabels.size() - 1)
                : "";

        for (String optionLabel : optionLabels) {
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));

            TextView labelView = new TextView(requireContext());
            labelView.setText(optionLabel);
            labelView.setTextSize(13);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
            );
            row.addView(labelView, labelParams);

            Switch overtimeSwitch = new Switch(requireContext());
            overtimeSwitch.setTag(optionLabel);
            overtimeSwitch.setChecked(
                    (enabledOptions != null && enabledOptions.contains(optionLabel))
                            || optionLabel.equals(defaultEnabledOption)
            );
            row.addView(overtimeSwitch);
            overtimeSwitches.add(overtimeSwitch);
            layoutOvertimeOptions.addView(row);
        }
    }

    private List<String> collectSelectedOvertimeSignOutOptions() {
        List<String> selected = new ArrayList<>();
        for (Switch overtimeSwitch : overtimeSwitches) {
            if (!overtimeSwitch.isChecked()) {
                continue;
            }
            Object tag = overtimeSwitch.getTag();
            if (tag instanceof String) {
                String value = ((String) tag).trim();
                if (!value.isEmpty() && !selected.contains(value)) {
                    selected.add(value);
                }
            }
        }
        return selected;
    }

    private String buildSignOutOptionLabel(String timeRange) {
        return timeRange.trim() + " \u4e0b\u73ed";
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void bindBaiduFingerprint() {
        String fingerprint = BaiduDeviceFingerprint.get(requireContext());
        tvBaiduFingerprint.setText(emptyFallback(fingerprint));
    }
    private void copyBaiduFingerprint() {
        String fingerprint = BaiduDeviceFingerprint.get(requireContext());
        if (fingerprint == null || fingerprint.trim().isEmpty()) {
            Toast.makeText(requireContext(), "授权指纹为空", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager clipboardManager =
                (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboardManager == null) {
            Toast.makeText(requireContext(), "剪贴板服务不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        clipboardManager.setPrimaryClip(ClipData.newPlainText("baidu_fingerprint", fingerprint));
        Toast.makeText(requireContext(), "授权指纹已复制", Toast.LENGTH_SHORT).show();
    }

    private void saveConfig() {
        String rawBaseUrl = etBaseUrl.getText() == null ? "" : etBaseUrl.getText().toString().trim();
        String baseUrl = SessionManager.normalizeBaseUrl(rawBaseUrl);
        if (!rawBaseUrl.isEmpty() && !baseUrl.equals(rawBaseUrl.replaceAll("/+$", "").trim())) {
            Toast.makeText(requireContext(), "Base URL 必须是有效的 http/https 地址", Toast.LENGTH_SHORT).show();
            etBaseUrl.requestFocus();
            return;
        }

        int companyId = parseCompanyId();
        if (companyId <= 0) {
            Toast.makeText(requireContext(), "Company ID 必须为正整数", Toast.LENGTH_SHORT).show();
            etCompanyId.requestFocus();
            return;
        }

        SessionManager session = SessionManager.get();
        int punchIntervalMinutes = parsePunchIntervalMinutes();
        if (punchIntervalMinutes < 0) {
            Toast.makeText(requireContext(), "打卡间隔必须为 0-240 分钟", Toast.LENGTH_SHORT).show();
            etPunchIntervalMinutes.requestFocus();
            return;
        }
        int punchResultDisplayMs = parseIntField(
                etPunchResultDisplayMs,
                PUNCH_RESULT_DISPLAY_MIN_MS,
                PUNCH_RESULT_DISPLAY_MAX_MS
        );
        if (punchResultDisplayMs < 0) {
            Toast.makeText(requireContext(), "结果显示时长必须为 0-5000 毫秒", Toast.LENGTH_SHORT).show();
            etPunchResultDisplayMs.requestFocus();
            return;
        }
        int recognitionFrameIntervalMs = parseIntField(
                etRecognitionFrameIntervalMs,
                RECOGNITION_FRAME_INTERVAL_MIN_MS,
                RECOGNITION_FRAME_INTERVAL_MAX_MS
        );
        if (recognitionFrameIntervalMs < 0) {
            Toast.makeText(requireContext(), "识别帧间隔必须为 250-1000 毫秒", Toast.LENGTH_SHORT).show();
            etRecognitionFrameIntervalMs.requestFocus();
            return;
        }
        int successCooldownMs = parseIntField(
                etSuccessCooldownMs,
                SUCCESS_COOLDOWN_MIN_MS,
                SUCCESS_COOLDOWN_MAX_MS
        );
        if (successCooldownMs < 0) {
            Toast.makeText(requireContext(), "成功后冷却必须为 0-3000 毫秒", Toast.LENGTH_SHORT).show();
            etSuccessCooldownMs.requestFocus();
            return;
        }
        List<String> overtimeSignOutOptions = collectSelectedOvertimeSignOutOptions();
        PunchTimeResolver.WindowValidationResult windowValidation =
                PunchTimeResolver.validatePunchTimeWindows(
                        session.getCurrentTeamTimeRanges(),
                        punchIntervalMinutes,
                        overtimeSignOutOptions
                );
        if (!windowValidation.valid) {
            Toast.makeText(requireContext(), windowValidation.buildMessage(), Toast.LENGTH_LONG).show();
            etPunchIntervalMinutes.requestFocus();
            return;
        }
        if (selectedScreenTimeoutMs != session.getScreenTimeoutMs()) {
            ScreenTimeoutPolicyManager.ApplyResult screenTimeoutResult =
                    ScreenTimeoutPolicyManager.applyAndSave(
                            requireContext(),
                            selectedScreenTimeoutMs
                    );
            if (!screenTimeoutResult.success) {
                Toast.makeText(
                        requireContext(),
                        "\u606f\u5c4f\u65f6\u95f4\u5e94\u7528\u5931\u8d25\uff1a"
                                + screenTimeoutResult.message,
                        Toast.LENGTH_LONG
                ).show();
                return;
            }
        }
        boolean serverChanged = !baseUrl.equals(session.getBaseUrl()) || companyId != session.getCompanyId();
        session.saveBaseUrl(baseUrl);
        session.saveCompanyId(companyId);
        if (serverChanged) {
            session.clearServerBoundState();
        }
        session.saveMatchThreshold(seekMatchThreshold.getProgress() / 100f);
        session.saveFaceThreshold(seekFaceThreshold.getProgress() / 100f);
        session.saveRecognitionDistanceMode(selectedDistanceModeValue);
        session.saveLivenessCheck(switchLiveness.isChecked());
        session.saveLivenessThreshold(seekLivenessThreshold.getProgress() / 100f);
        session.saveMaskDetectEnabled(switchMaskDetect.isChecked());
        session.saveRecognitionTimeoutSeconds(selectedRecognitionTimeoutValue);
        session.savePunchTimeWindowMinutes(punchIntervalMinutes);
        session.saveFastPunchEnabled(switchFastPunch.isChecked());
        session.saveShowPunchResultCard(switchShowPunchResultCard.isChecked());
        session.savePunchResultDisplayMs(punchResultDisplayMs);
        session.savePunchSpeechMode(selectedPunchSpeechModeValue);
        session.savePunchSpeechRate(selectedPunchSpeechRateValue);
        session.saveRecognitionFrameIntervalMs(recognitionFrameIntervalMs);
        session.saveSuccessCooldownMs(successCooldownMs);
        session.saveOvertimeSignOutOptions(overtimeSignOutOptions);

        if (FaceManager.get().isInitialized()) {
            FaceManager.get().refreshRuntimeConfig();
        }

        renderConfig();
        Toast.makeText(requireContext(), "高级配置已保存", Toast.LENGTH_SHORT).show();
    }

    private void showLocalConfigDialog() {
        if (!isAdded()) {
            return;
        }
        String configText = buildLocalConfigText();
        new AlertDialog.Builder(requireContext())
                .setTitle("\u672c\u5730\u914d\u7f6e")
                .setMessage(configText)
                .setNegativeButton("\u5173\u95ed", null)
                .setPositiveButton("\u590d\u5236", (dialog, which) -> copyText("local_config", configText))
                .show();
    }

    private String buildLocalConfigText() {
        SessionManager session = SessionManager.get();
        StringBuilder builder = new StringBuilder();

        appendSection(builder, "\u7cfb\u7edf\u4e0e\u6388\u6743");
        appendLine(builder, "Base URL", session.getBaseUrl());
        appendLine(builder, "Company ID", String.valueOf(session.getCompanyId()));
        appendLine(builder, "\u81ea\u52a8\u606f\u5c4f", formatScreenTimeout(session.getScreenTimeoutMs()));
        appendLine(builder, "\u9996\u6b21\u914d\u7f6e\u5b8c\u6210", formatBoolean(session.isSetupCompleted()));
        appendLine(builder, "\u8bbe\u5907 ID", emptyFallback(session.getDeviceId()));
        appendLine(builder, "\u8bbe\u5907\u5df2\u6ce8\u518c", formatBoolean(session.isDeviceRegistered()));
        appendLine(builder, "\u8bbe\u5907\u914d\u7f6e\u5df2\u521d\u59cb\u5316", formatBoolean(session.isDeviceConfigInitialized()));
        appendLine(builder, "\u6388\u6743\u6307\u7eb9", emptyFallback(BaiduDeviceFingerprint.get(requireContext())));
        appendLine(builder, "\u6388\u6743\u65b9\u5f0f", formatActivationMode(session.getActivationMode()));
        appendLine(builder, "\u6fc0\u6d3b\u72b6\u6001", formatActivationStatus(session.getActivationStatus()));
        appendLine(builder, "\u6fc0\u6d3b\u65f6\u95f4", formatEpochSeconds(session.getLastActivationTime()));

        appendSection(builder, "\u767b\u5f55\u4e0e\u540c\u6b65");
        appendLine(builder, "\u8d26\u53f7", session.getAccount());
        appendLine(builder, "\u767b\u5f55\u5bc6\u7801", session.getPassword());
        appendLine(builder, "Token", session.getToken());
        appendLine(builder, "Token \u6709\u6548", formatBoolean(session.isTokenValid()));
        appendLine(builder, "Token \u8fc7\u671f\u65f6\u95f4", formatEpochSeconds(session.getTokenExpireAt()));
        appendLine(builder, "\u6700\u540e\u5fc3\u8df3\u65f6\u95f4", formatEpochSeconds(session.getLastHeartbeatTime()));
        appendLine(builder, "\u6700\u540e\u670d\u52a1\u5668\u65f6\u95f4", formatEpochSeconds(session.getLastServerTime()));

        appendSection(builder, "\u4e1a\u52a1\u7ed1\u5b9a");
        appendLine(builder, "\u7ebf\u4f53", buildLineBindingText(session));
        appendLine(builder, "\u73ed\u7ec4", buildTeamBindingText(session));
        appendLine(builder, "\u73ed\u6b21\u65f6\u95f4", joinStrings(session.getCurrentTeamTimeRanges()));
        appendLine(builder, "\u6253\u5361\u4eba\u6570\u4e0a\u9650", String.valueOf(session.getCheckCount()));
        appendLine(builder, "\u6253\u5361\u95f4\u9694", session.getPunchTimeWindowMinutes() + "\u5206\u949f");
        appendLine(builder, "\u5feb\u901f\u6253\u5361", formatBoolean(session.isFastPunchEnabled()));
        appendLine(builder, "\u663e\u793a\u6253\u5361\u7ed3\u679c", formatBoolean(session.shouldShowPunchResultCard()));
        appendLine(builder, "\u7ed3\u679c\u663e\u793a\u65f6\u957f", session.getPunchResultDisplayMs() + "ms");
        appendLine(builder, "\u8bed\u97f3\u64ad\u62a5\u5185\u5bb9", formatPunchSpeechMode(session.getPunchSpeechMode()));
        appendLine(builder, "\u8bed\u97f3\u901f\u5ea6", formatFloat(session.getPunchSpeechRate()) + "x");
        appendLine(builder, "\u8bc6\u522b\u5e27\u95f4\u9694", session.getRecognitionFrameIntervalMs() + "ms");
        appendLine(builder, "\u6210\u529f\u540e\u51b7\u5374", session.getSuccessCooldownMs() + "ms");
        appendLine(builder, "\u52a0\u73ed\u4e0b\u73ed\u9879", joinStrings(session.getOvertimeSignOutOptions()));
        appendLine(builder, "\u53ef\u9009\u7ebf\u4f53", buildLineOptionsText(session.getLineBindingOptions()));
        appendLine(builder, "\u53ef\u9009\u73ed\u7ec4", buildTeamOptionsText(session.getTeamBindingOptions()));

        appendSection(builder, "\u8bc6\u522b\u53c2\u6570");
        appendLine(builder, "\u4eba\u8138\u6bd4\u5bf9\u9608\u503c", formatFloat(session.getMatchThreshold()));
        appendLine(builder, "\u4eba\u8138\u68c0\u6d4b\u9608\u503c", formatFloat(session.getFaceThreshold()));
        appendLine(builder, "\u6d3b\u4f53\u68c0\u6d4b", formatBoolean(session.isLivenessCheck()));
        appendLine(builder, "\u6d3b\u4f53\u68c0\u6d4b\u9608\u503c", formatFloat(session.getLivenessThreshold()));
        appendLine(builder, "\u53e3\u7f69\u68c0\u6d4b", formatBoolean(session.isMaskDetectEnabled()));
        appendLine(builder, "\u8bc6\u522b\u8ddd\u79bb", formatDistanceMode(session.getRecognitionDistanceMode()));
        appendLine(builder, "\u8bc6\u522b\u8d85\u65f6", session.getRecognitionTimeoutSeconds() + "s");
        appendLine(builder, "\u6700\u5c0f\u4eba\u8138\u5c3a\u5bf8", String.valueOf(session.getMinFaceSizeForRecognitionDistance()));

        appendSection(builder, "\u8bbe\u5907\u9009\u9879");
        appendLine(builder, "\u6444\u50cf\u5934", formatCameraFacing(session.getCameraFacing(-1)));
        appendLine(builder, "\u58f0\u97f3", formatBoolean(session.isSoundEnabled()));
        appendLine(builder, "Kiosk", formatBoolean(session.isKioskEnabled()));
        appendLine(builder, "\u4e0a\u6b21 Wi-Fi SSID", session.getLastWifiSsid());
        appendLine(builder, "\u4e0a\u6b21 Wi-Fi \u5bc6\u7801", session.getLastWifiPassword());

        appendSection(builder, "\u66f4\u65b0");
        appendLine(builder, "\u9700\u8981\u66f4\u65b0", formatBoolean(session.isUpdateNeeded()));
        appendLine(builder, "APK URL", session.getUpdateApkUrl());
        appendLine(builder, "\u5f53\u524d\u7248\u672c", session.getUpdateCurrentVersion());
        appendLine(builder, "\u76ee\u6807\u7248\u672c", session.getUpdateTargetVersion());
        appendLine(builder, "\u7248\u672c\u540d", session.getUpdateVersionName());
        appendLine(builder, "\u5b89\u88c5\u72b6\u6001", session.getUpdateInstallStatus());
        appendLine(builder, "\u5b89\u88c5\u6d88\u606f", session.getUpdateInstallMessage());

        return builder.toString();
    }

    private void copyText(String label, String text) {
        ClipboardManager clipboardManager =
                (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboardManager == null) {
            Toast.makeText(requireContext(), "\u526a\u8d34\u677f\u670d\u52a1\u4e0d\u53ef\u7528", Toast.LENGTH_SHORT).show();
            return;
        }
        clipboardManager.setPrimaryClip(ClipData.newPlainText(label, text == null ? "" : text));
        Toast.makeText(requireContext(), "\u672c\u5730\u914d\u7f6e\u5df2\u590d\u5236", Toast.LENGTH_SHORT).show();
    }

    private void appendSection(StringBuilder builder, String title) {
        if (builder.length() > 0) {
            builder.append('\n');
        }
        builder.append("[").append(title).append("]").append('\n');
    }

    private void appendLine(StringBuilder builder, String label, String value) {
        builder.append(label).append(": ").append(emptyFallback(value)).append('\n');
    }

    private String buildLineBindingText(SessionManager session) {
        String code = session.getLineCode();
        String name = session.getLineName();
        if (code.isEmpty() && name.isEmpty()) {
            return "-";
        }
        if (name.isEmpty()) {
            return code;
        }
        if (code.isEmpty()) {
            return name;
        }
        return name + " (" + code + ")";
    }

    private String buildTeamBindingText(SessionManager session) {
        int id = session.getTeamBindingId();
        String name = session.getTeamBindingName();
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

    private String buildLineOptionsText(List<DeviceDto.LineOptionData> options) {
        if (options == null || options.isEmpty()) {
            return "-";
        }
        StringBuilder builder = new StringBuilder();
        for (DeviceDto.LineOptionData option : options) {
            if (option == null) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("; ");
            }
            String name = option.name == null ? "" : option.name.trim();
            String code = option.code == null ? "" : option.code.trim();
            if (!name.isEmpty() && !code.isEmpty()) {
                builder.append(name).append(" (").append(code).append(")");
            } else {
                builder.append(!name.isEmpty() ? name : code);
            }
        }
        return builder.length() == 0 ? "-" : builder.toString();
    }

    private String buildTeamOptionsText(List<DeviceDto.TeamOptionData> options) {
        if (options == null || options.isEmpty()) {
            return "-";
        }
        StringBuilder builder = new StringBuilder();
        for (DeviceDto.TeamOptionData option : options) {
            if (option == null) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("; ");
            }
            String name = option.name == null ? "" : option.name.trim();
            builder.append(name.isEmpty() ? String.valueOf(option.id) : name + " (" + option.id + ")");
            String ranges = joinStrings(option.timeRanges);
            if (!"-".equals(ranges)) {
                builder.append(" ").append(ranges);
            }
        }
        return builder.length() == 0 ? "-" : builder.toString();
    }

    private String joinStrings(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "-";
        }
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(value.trim());
        }
        return builder.length() == 0 ? "-" : builder.toString();
    }

    private String formatBoolean(boolean value) {
        return value ? "\u662f" : "\u5426";
    }

    private String formatFloat(float value) {
        return String.format(Locale.getDefault(), "%.2f", value);
    }

    private String formatDistanceMode(String mode) {
        if (Constants.DISTANCE_MODE_NEAR.equals(mode)) {
            return "\u8fd1\u8ddd\u79bb";
        }
        if (Constants.DISTANCE_MODE_FAR.equals(mode)) {
            return "\u8fdc\u8ddd\u79bb";
        }
        return "\u6807\u51c6";
    }

    private String formatCameraFacing(int cameraFacing) {
        if (cameraFacing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            return "\u524d\u7f6e";
        }
        if (cameraFacing == Camera.CameraInfo.CAMERA_FACING_BACK) {
            return "\u540e\u7f6e";
        }
        return "\u672a\u77e5";
    }

    private String formatEpochSeconds(long seconds) {
        if (seconds <= 0) {
            return "-";
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new java.util.Date(seconds * 1000L));
    }

    private void toggleKioskMode() {
        if (!isAdded() || requireActivity().isFinishing() || clearingDeviceOwner) {
            return;
        }
        if (SessionManager.get().isKioskEnabled()) {
            if (KioskManager.isManagedDevice(requireContext())) {
                Toast.makeText(
                        requireContext(),
                        KioskManager.managementModeLabel(requireContext())
                                + " 生产管理模式下不能直接退出 Kiosk",
                        Toast.LENGTH_LONG
                ).show();
                KioskManager.enableAndEnter(requireActivity());
                updateKioskButtonState();
                return;
            }
            KioskManager.exitAndDisable(requireActivity());
            Toast.makeText(requireContext(), "已退出 Kiosk 模式", Toast.LENGTH_SHORT).show();
        } else {
            KioskManager.enableAndEnter(requireActivity());
            if (!KioskManager.isLockTaskPermitted(requireContext())) {
                Toast.makeText(requireContext(), "Kiosk 已启用，但当前设备未授予锁定权限", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(requireContext(), "已启用 Kiosk 模式", Toast.LENGTH_SHORT).show();
            }
        }
        updateKioskButtonState();
    }

    private void updateKioskButtonState() {
        if (clearingDeviceOwner) {
            btnKioskMode.setEnabled(false);
            btnKioskMode.setAlpha(0.6f);
            return;
        }
        btnKioskMode.setEnabled(true);
        btnKioskMode.setAlpha(1f);
        btnKioskMode.setText(SessionManager.get().isKioskEnabled() ? "退出 Kiosk 模式" : "启用 Kiosk 模式");
    }

    private void updateClearDeviceOwnerButtonState() {
        if (clearingDeviceOwner) {
            btnClearDeviceOwner.setEnabled(false);
            btnClearDeviceOwner.setAlpha(0.6f);
            btnClearDeviceOwner.setText("正在解除 Device Owner...");
            return;
        }
        if (KioskManager.isSystemAppMode(requireContext())) {
            btnClearDeviceOwner.setEnabled(false);
            btnClearDeviceOwner.setAlpha(0.6f);
            btnClearDeviceOwner.setText("Android 12 System App（无需 Device Owner）");
            return;
        }
        boolean isDeviceOwner = KioskManager.isDeviceOwner(requireContext());
        btnClearDeviceOwner.setEnabled(isDeviceOwner);
        btnClearDeviceOwner.setAlpha(isDeviceOwner ? 1f : 0.6f);
        btnClearDeviceOwner.setText(isDeviceOwner ? "解除 Device Owner" : "当前不是 Device Owner");
    }

    private void confirmClearDeviceOwner() {
        if (KioskManager.isSystemAppMode(requireContext())) {
            Toast.makeText(
                    requireContext(),
                    "Android 12 System App 模式不依赖 Device Owner；系统预装应用不能通过此按钮解除",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }
        if (!KioskManager.isDeviceOwner(requireContext())) {
            Toast.makeText(requireContext(), "当前应用不是 Device Owner", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle("解除 Device Owner")
                .setMessage("该操作会取消当前设备上的 Device Owner 身份，Kiosk 和设备管理能力会立即失效。只有系统真正完成移除后，才会自动弹出系统卸载界面。是否继续？")
                .setNegativeButton("取消", null)
                .setPositiveButton("继续", (dialog, which) -> clearDeviceOwner())
                .show();
    }

    private void clearDeviceOwner() {
        Context context = requireContext();
        DevicePolicyManager dpm =
                (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm == null) {
            InteractionLogger.logBusinessFailure(
                    InteractionLogger.GROUP_GENERAL,
                    "解除 Device Owner 失败",
                    "DevicePolicyManager unavailable"
            );
            Toast.makeText(context, "设备策略服务不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            InteractionLogger.logBusiness(
                    InteractionLogger.GROUP_GENERAL,
                    "开始解除 Device Owner",
                    "package=" + context.getPackageName()
            );
            clearingDeviceOwner = true;
            clearDeviceOwnerStartedAt = System.currentTimeMillis();
            updateKioskButtonState();
            updateClearDeviceOwnerButtonState();
            if (!KioskManager.exitForDeviceOwnerRemoval(requireActivity())) {
                clearingDeviceOwner = false;
                updateKioskButtonState();
                updateClearDeviceOwnerButtonState();
                Toast.makeText(
                        context,
                        "\u6062\u590d\u539f\u7cfb\u7edf\u606f\u5c4f\u8bbe\u7f6e\u5931\u8d25\uff0c\u672a\u89e3\u9664 Device Owner",
                        Toast.LENGTH_LONG
                ).show();
                return;
            }
            dpm.clearDeviceOwnerApp(context.getPackageName());
            openSystemUninstallPage(appContext);
            InteractionLogger.logBusiness(
                    InteractionLogger.GROUP_GENERAL,
                    "解除 Device Owner 请求已提交",
                    "waiting for owner/admin removal"
            );
            scheduleClearDeviceOwnerStateCheck(CLEAR_DEVICE_OWNER_POLL_INTERVAL_MS);
        } catch (SecurityException e) {
            clearingDeviceOwner = false;
            InteractionLogger.logBusinessFailure(
                    InteractionLogger.GROUP_GENERAL,
                    "解除 Device Owner 失败",
                    "SecurityException: " + e.getMessage()
            );
            updateKioskButtonState();
            updateClearDeviceOwnerButtonState();
            Toast.makeText(context, "解除失败：权限不足", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            clearingDeviceOwner = false;
            InteractionLogger.logBusinessFailure(
                    InteractionLogger.GROUP_GENERAL,
                    "解除 Device Owner 失败",
                    e.getClass().getSimpleName() + ": " + e.getMessage()
            );
            updateKioskButtonState();
            updateClearDeviceOwnerButtonState();
            Toast.makeText(context, "解除失败：" + safeMessage(e), Toast.LENGTH_LONG).show();
        }
    }
    private void scheduleClearDeviceOwnerStateCheck(long delayMillis) {
        mainHandler.removeCallbacksAndMessages(null);
        mainHandler.postDelayed(this::checkClearDeviceOwnerState, delayMillis);
    }
    private void checkClearDeviceOwnerState() {
        Context context = appContext;
        if (context == null) {
            Context currentContext = getContext();
            if (currentContext != null) {
                context = currentContext.getApplicationContext();
                appContext = context;
            }
        }
        if (context == null) {
            return;
        }
        DevicePolicyManager dpm =
                (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        boolean ownerCleared = dpm == null || !dpm.isDeviceOwnerApp(context.getPackageName());
        boolean adminCleared = dpm == null || !dpm.isAdminActive(getAdminComponent(context));

        if (ownerCleared) {
            clearingDeviceOwner = false;
            InteractionLogger.logBusiness(
                    InteractionLogger.GROUP_GENERAL,
                    "解除 Device Owner 成功",
                    "ownerCleared=" + ownerCleared + ", adminCleared=" + adminCleared
            );
            if (isAdded()) {
                updateKioskButtonState();
                updateClearDeviceOwnerButtonState();
                renderConfig();
            }
            openSystemUninstallPage(context);
            return;
        }

        if (System.currentTimeMillis() - clearDeviceOwnerStartedAt >= CLEAR_DEVICE_OWNER_TIMEOUT_MS) {
            clearingDeviceOwner = false;
            InteractionLogger.logBusinessFailure(
                    InteractionLogger.GROUP_GENERAL,
                    "解除 Device Owner 超时",
                    "ownerCleared=" + ownerCleared + ", adminCleared=" + adminCleared
            );
            if (isAdded()) {
                updateKioskButtonState();
                updateClearDeviceOwnerButtonState();
                renderConfig();
            }
            Toast.makeText(
                    context,
                    "\u89e3\u9664\u4ecd\u5728\u5904\u7406\u4e2d\uff0c\u5c06\u518d\u6b21\u5c1d\u8bd5\u6253\u5f00\u5378\u8f7d\u9875",
                    Toast.LENGTH_LONG
            ).show();
            openSystemUninstallPage(context);
            return;
        }

        InteractionLogger.logBusiness(
                InteractionLogger.GROUP_GENERAL,
                "继续等待 Device Owner 移除",
                "ownerCleared=" + ownerCleared + ", adminCleared=" + adminCleared
        );
        scheduleClearDeviceOwnerStateCheck(CLEAR_DEVICE_OWNER_POLL_INTERVAL_MS);
    }
    private ComponentName getAdminComponent() {
        return getAdminComponent(requireContext());
    }

    private ComponentName getAdminComponent(Context context) {
        return new ComponentName(context, KioskDeviceAdminReceiver.class);
    }

    private void openSystemUninstallPage(Context context) {
        if (context == null) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_DELETE);
        intent.setData(Uri.parse("package:" + context.getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
            InteractionLogger.logBusiness(
                    InteractionLogger.GROUP_GENERAL,
                    "Open uninstall page",
                    "package=" + context.getPackageName()
            );
        } catch (Exception e) {
            InteractionLogger.logBusinessFailure(
                    InteractionLogger.GROUP_GENERAL,
                    "Open uninstall page failed",
                    e.getClass().getSimpleName() + ": " + safeMessage(e)
            );
            Toast.makeText(
                    context,
                    "\u65e0\u6cd5\u6253\u5f00\u7cfb\u7edf\u5378\u8f7d\u9875\uff0c\u8bf7\u624b\u52a8\u5378\u8f7d",
                    Toast.LENGTH_LONG
            ).show();
        }
    }
    private void selectDistanceMode(String distanceMode) {
        int index = 1;
        if (Constants.DISTANCE_MODE_NEAR.equals(distanceMode)) {
            index = 0;
        } else if (Constants.DISTANCE_MODE_FAR.equals(distanceMode)) {
            index = 2;
        }
        selectedDistanceModeValue = DISTANCE_VALUES[index];
        dropdownDistanceMode.setText(DISTANCE_LABELS[index], false);
    }

    private void selectRecognitionTimeout(int timeoutSeconds) {
        int selected = TIMEOUT_OPTIONS[0];
        for (int option : TIMEOUT_OPTIONS) {
            if (option == timeoutSeconds) {
                selected = option;
                break;
            }
        }
        selectedRecognitionTimeoutValue = selected;
        dropdownRecognitionTimeout.setText(String.valueOf(selected), false);
    }

    private void selectScreenTimeout(long timeoutMs) {
        int selectedIndex = 0;
        for (int i = 0; i < SCREEN_TIMEOUT_VALUES_MS.length; i++) {
            if (SCREEN_TIMEOUT_VALUES_MS[i] == timeoutMs) {
                selectedIndex = i;
                break;
            }
        }
        selectedScreenTimeoutMs = SCREEN_TIMEOUT_VALUES_MS[selectedIndex];
        dropdownScreenTimeout.setText(SCREEN_TIMEOUT_LABELS[selectedIndex], false);
    }

    private void updateScreenTimeoutControlState() {
        ScreenTimeoutPolicy.ManagementAvailability availability =
                ScreenTimeoutPolicyManager.getAvailability(requireContext());
        boolean available = availability == ScreenTimeoutPolicy.ManagementAvailability.AVAILABLE;
        dropdownScreenTimeout.setEnabled(available);
        dropdownScreenTimeout.setAlpha(available ? 1f : 0.6f);
        if (available) {
            tvScreenTimeoutStatus.setText(
                    "\u7a7a\u95f2\u65f6\u7531\u7cfb\u7edf\u5012\u8ba1\u606f\u5c4f\uff0c\u6253\u5361\u5904\u7406\u671f\u95f4\u4e34\u65f6\u5e38\u4eae"
            );
        } else if (availability
                == ScreenTimeoutPolicy.ManagementAvailability.NOT_DEVICE_OWNER) {
            tvScreenTimeoutStatus.setText(
                    "需要 Android 12 platform system app 或 Device Owner"
            );
        } else {
            tvScreenTimeoutStatus.setText(
                    "\u7cfb\u7edf\u606f\u5c4f\u7b56\u7565\u9700\u8981 Android 9 \u6216\u66f4\u9ad8\u7248\u672c"
            );
        }
    }

    private String formatScreenTimeout(long timeoutMs) {
        for (int i = 0; i < SCREEN_TIMEOUT_VALUES_MS.length; i++) {
            if (SCREEN_TIMEOUT_VALUES_MS[i] == timeoutMs) {
                return SCREEN_TIMEOUT_LABELS[i];
            }
        }
        return SCREEN_TIMEOUT_LABELS[0];
    }

    private void selectPunchSpeechMode(String mode) {
        int index = 0;
        for (int i = 0; i < PUNCH_SPEECH_MODE_VALUES.length; i++) {
            if (PUNCH_SPEECH_MODE_VALUES[i].equals(mode)) {
                index = i;
                break;
            }
        }
        selectedPunchSpeechModeValue = PUNCH_SPEECH_MODE_VALUES[index];
        dropdownPunchSpeechMode.setText(PUNCH_SPEECH_MODE_LABELS[index], false);
    }

    private void selectPunchSpeechRate(float rate) {
        int index = 0;
        float nearestDiff = Math.abs(PUNCH_SPEECH_RATE_VALUES[0] - rate);
        for (int i = 0; i < PUNCH_SPEECH_RATE_VALUES.length; i++) {
            float diff = Math.abs(PUNCH_SPEECH_RATE_VALUES[i] - rate);
            if (diff < nearestDiff) {
                index = i;
                nearestDiff = diff;
            }
        }
        selectedPunchSpeechRateValue = PUNCH_SPEECH_RATE_VALUES[index];
        dropdownPunchSpeechRate.setText(PUNCH_SPEECH_RATE_LABELS[index], false);
    }

    private int parseCompanyId() {
        String value = etCompanyId.getText() == null ? "" : etCompanyId.getText().toString().trim();
        if (value.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private void adjustPunchInterval(int deltaMinutes) {
        int current = parsePunchIntervalMinutes();
        if (current < 0) {
            current = SessionManager.get().getPunchTimeWindowMinutes();
        }
        int adjusted = Math.max(
                PUNCH_INTERVAL_MIN_MINUTES,
                Math.min(PUNCH_INTERVAL_MAX_MINUTES, current + deltaMinutes)
        );
        etPunchIntervalMinutes.setText(String.valueOf(adjusted));
        etPunchIntervalMinutes.setSelection(etPunchIntervalMinutes.getText().length());
    }

    private void adjustIntField(EditText editText, int delta, int min, int max, int fallback) {
        int current = parseIntField(editText, min, max);
        if (current < 0) {
            current = Math.max(min, Math.min(max, fallback));
        }
        int adjusted = Math.max(min, Math.min(max, current + delta));
        editText.setText(String.valueOf(adjusted));
        editText.setSelection(editText.getText().length());
    }

    private int parsePunchIntervalMinutes() {
        String value = etPunchIntervalMinutes.getText() == null
                ? ""
                : etPunchIntervalMinutes.getText().toString().trim();
        if (value.isEmpty()) {
            return -1;
        }
        try {
            int minutes = Integer.parseInt(value);
            if (minutes < PUNCH_INTERVAL_MIN_MINUTES || minutes > PUNCH_INTERVAL_MAX_MINUTES) {
                return -1;
            }
            return minutes;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private int parseIntField(EditText editText, int min, int max) {
        String value = editText.getText() == null ? "" : editText.getText().toString().trim();
        if (value.isEmpty()) {
            return -1;
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= min && parsed <= max ? parsed : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private float parseFloatField(EditText editText, float min, float max) {
        String value = editText.getText() == null ? "" : editText.getText().toString().trim();
        if (value.isEmpty()) {
            return -1f;
        }
        try {
            float parsed = Float.parseFloat(value);
            return parsed >= min && parsed <= max ? parsed : -1f;
        } catch (NumberFormatException ignored) {
            return -1f;
        }
    }

    private String formatPunchSpeechMode(String mode) {
        for (int i = 0; i < PUNCH_SPEECH_MODE_VALUES.length; i++) {
            if (PUNCH_SPEECH_MODE_VALUES[i].equals(mode)) {
                return PUNCH_SPEECH_MODE_LABELS[i];
            }
        }
        return PUNCH_SPEECH_MODE_LABELS[0];
    }

    private String formatActivationMode(String mode) {
        if (Constants.ACTIVATION_MODE_ONLINE.equals(mode)) {
            return "在线激活";
        }
        if (Constants.ACTIVATION_MODE_OFFLINE_ZIP.equals(mode)) {
            return "License.zip";
        }
        return "未知";
    }

    private String formatActivationStatus(String status) {
        if (Constants.ACTIVATION_STATUS_SUCCESS.equals(status)) {
            return "已激活";
        }
        if (Constants.ACTIVATION_STATUS_FAILED.equals(status)) {
            return "激活失败";
        }
        if (Constants.ACTIVATION_STATUS_DISABLED.equals(status)) {
            return "已禁用";
        }
        return "待激活";
    }

    private String formatActivationTime(long activationTimeSeconds) {
        if (activationTimeSeconds <= 0) {
            return "-";
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(activationTimeSeconds * 1000L);
    }

    private String safeMessage(Exception e) {
        if (e == null || e.getMessage() == null || e.getMessage().trim().isEmpty()) {
            return "未知错误";
        }
        return e.getMessage().trim();
    }

    private String emptyFallback(String value) {
        return (value == null || value.trim().isEmpty()) ? "-" : value;
    }

    private abstract static class SimpleSeekBarListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}

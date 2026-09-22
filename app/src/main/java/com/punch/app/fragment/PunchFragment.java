package com.punch.app.fragment;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Camera;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.punch.app.PunchApplication;
import com.punch.app.activity.MainActivity;
import com.punch.app.R;
import com.punch.app.db.DatabaseHelper;
import com.punch.app.face.FaceManager;
import com.punch.app.model.Employee;
import com.punch.app.model.PunchRecord;
import com.punch.app.network.InteractionLogger;
import com.punch.app.service.PunchPersistence;
import com.punch.app.service.SyncService;
import com.punch.app.utils.AvatarLoader;
import com.punch.app.utils.AppLogger;
import com.punch.app.utils.Constants;
import com.punch.app.utils.KioskManager;
import com.punch.app.utils.LifecycleRequestGate;
import com.punch.app.utils.PunchSnapshotHelper;
import com.punch.app.utils.PunchTimeResolver;
import com.punch.app.utils.ScreenTimeoutPolicy;
import com.punch.app.utils.SessionManager;
import com.punch.app.utils.UlidGenerator;
import com.punch.app.widget.FaceFrameView;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Punch screen fragment.
 * <p>Handles camera preview, face recognition, punch persistence, audio feedback, and result display.</p>
 */

public class PunchFragment extends Fragment implements TextureView.SurfaceTextureListener {

    private static final String TAG = "PunchFragment";
    // Views
    private View layoutHeader;
    private View layoutControls;
    private View layoutCameraContainer;
    private View layoutCameraLoading;
    private View layoutPunchStatusPanel;
    private TextureView textureView;
    private FaceFrameView faceFrameView;
    private TextView tvLine, tvTeam, tvStatus, tvResult, btnSwitchCamera, btnSound, btnPunchToggle, btnFullscreen, tvCameraLoading;
    private TextView tvResultAvatarFallback;
    private TextView tvResultAvatarTag;
    private TextView tvPunchStatusLevel;
    private TextView tvPunchStatusCurrent;
    private TextView tvPunchStatusToggle;
    private TextView tvPunchStatusHint;
    private View layoutEmployeeSyncProgress;
    private TextView tvEmployeeSyncTitle;
    private TextView tvEmployeeSyncDetail;
    private ProgressBar progressEmployeeSync;
    private Spinner spinnerShift;
    private Switch switchSpecialTime;
    private LinearLayout layoutResult;
    private FrameLayout layoutResultAvatar;
    private LinearLayout layoutPunchStatusHistory;
    private ImageView ivResultAvatar;

    // Camera
    private Camera camera;
    private int cameraId = -1;
    private int cameraFacing = Camera.CameraInfo.CAMERA_FACING_BACK;
    private int frameRotation = 0;
    private int frameMirror = 0;
    private int previewWidth = 0;
    private int previewHeight = 0;
    private boolean openingCamera = false;

    // State
    private String punchType = Constants.PUNCH_TYPE_SIGN_IN;
    private boolean soundEnabled = true;
    private boolean punchEnabled = false;
    private boolean specialTimeEnabled = false;
    private boolean previewFullscreen = false;
    private volatile boolean recognizing = false;
    private volatile boolean faceInteractionActive = false;
    private long recognitionAttemptStartedAt = 0;
    private long lastRecognitionTimeoutAt = 0;
    private long lastLivenessDebugLogAt = 0;
    private long lastFrameTime = 0;
    private long previewLayoutSettlingUntil = 0;
    private static final long FRAME_INTERVAL_MS = 600;
    private static final long RESULT_DISPLAY_MS = 3000;
    private static final long RECOGNITION_TIMEOUT_FEEDBACK_COOLDOWN_MS = 1500;
    private static final long LIVENESS_DEBUG_LOG_COOLDOWN_MS = 1500;
    private static final int STABLE_MATCH_REQUIRED_FRAMES = 2;
    private static final long STABLE_MATCH_MAX_GAP_MS = 1500;
    private static final long CAMERA_RELEASE_DELAY_MS = 1800;
    private static final long FACE_INTERACTION_GRACE_MS = 2000;
    private static final long STATUS_PANEL_AUTO_HIDE_DELAY_MS = 3500;
    private static final long STATUS_PANEL_FADE_DURATION_MS = 500;
    private static final long PREVIEW_LAYOUT_SETTLE_MS = 300;
    private static final float FACE_FRAME_MIN_OVERLAP = 0.55f;
    private static final float TTS_SPEECH_RATE_DEFAULT = 1.0f;
    private static final float TTS_SPEECH_RATE_CHINESE = 0.88f;
    private static final int FREE_PUNCH_OPTION_VALUE = 0;
    private static final String FREE_PUNCH_OPTION_LABEL = "\u81ea\u7531\u6253\u5361";
    private static final String PUNCH_TYPE_FREE = "free";
    private final List<String> punchOptions = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable clearFaceInteractionRunnable = () -> {
        faceInteractionActive = false;
        updateScreenAwakeState();
    };
    private final LifecycleRequestGate viewGate = new LifecycleRequestGate();
    private int viewToken;
    private final Runnable delayedCameraRelease = this::releaseCameraNow;
    private MediaPlayer feedbackPlayer;
    private TextToSpeech textToSpeech;
    private boolean ttsReady = false;
    private boolean ttsInitializing = false;
    private Locale ttsActiveLocale = Locale.getDefault();
    private String pendingSpeechText;
    private int pendingSpeechFallbackResId = 0;
    private boolean punchActive = false;
    private boolean waitingFirstPreviewFrame = false;
    private boolean statusHistoryExpanded = false;
    private boolean ambiguousPunchSelectionPending = false;
    private boolean userChangingPunchSelection = false;
    private ArrayAdapter<String> punchOptionAdapter;
    private final PunchApplication.PunchStatusListener punchStatusListener = this::renderPunchStatusSnapshot;
    private final Runnable hideStatusPanelRunnable = this::fadeOutStatusPanel;

    private volatile boolean frameProcessing = false;
    private String pendingMatchEmpId;
    private int pendingMatchCount = 0;
    private long pendingMatchLastAt = 0L;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_punch, container, false);
    }

    @Override
    
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewToken = viewGate.open();

        layoutHeader = view.findViewById(R.id.layout_punch_header);
        layoutCameraContainer = view.findViewById(R.id.layout_camera_container);
        layoutCameraLoading = view.findViewById(R.id.layout_camera_loading);
        layoutPunchStatusPanel = view.findViewById(R.id.layout_punch_status_panel);
        textureView = view.findViewById(R.id.texture_view);
        faceFrameView = view.findViewById(R.id.face_frame_view);
        tvLine = view.findViewById(R.id.tv_line);
        tvTeam = view.findViewById(R.id.tv_team);
        tvStatus = view.findViewById(R.id.tv_status);
        tvResult = view.findViewById(R.id.tv_result);
        ivResultAvatar = view.findViewById(R.id.iv_result_avatar);
        tvResultAvatarFallback = view.findViewById(R.id.tv_result_avatar_fallback);
        tvResultAvatarTag = view.findViewById(R.id.tv_result_avatar_tag);
        btnSwitchCamera = view.findViewById(R.id.btn_switch_camera);
        btnSound = view.findViewById(R.id.btn_sound);
        btnPunchToggle = view.findViewById(R.id.btn_punch_toggle);
        btnFullscreen = view.findViewById(R.id.btn_fullscreen);
        tvCameraLoading = view.findViewById(R.id.tv_camera_loading);
        tvPunchStatusLevel = view.findViewById(R.id.tv_punch_status_level);
        tvPunchStatusCurrent = view.findViewById(R.id.tv_punch_status_current);
        tvPunchStatusToggle = view.findViewById(R.id.tv_punch_status_toggle);
        tvPunchStatusHint = view.findViewById(R.id.tv_punch_status_hint);
        layoutEmployeeSyncProgress = view.findViewById(R.id.layout_employee_sync_progress);
        tvEmployeeSyncTitle = view.findViewById(R.id.tv_employee_sync_title);
        tvEmployeeSyncDetail = view.findViewById(R.id.tv_employee_sync_detail);
        progressEmployeeSync = view.findViewById(R.id.progress_employee_sync);
        layoutResult = view.findViewById(R.id.layout_result);
        layoutResultAvatar = view.findViewById(R.id.layout_result_avatar);
        layoutPunchStatusHistory = view.findViewById(R.id.layout_punch_status_history);
        cameraFacing = resolveInitialCameraFacing();
        soundEnabled = SessionManager.get().isSoundEnabled();

        refreshBindingHeader();

        btnSwitchCamera.setOnClickListener(v -> toggleCameraFacing());
        btnSwitchCamera.setVisibility(hasMultipleCameras() ? View.VISIBLE : View.GONE);
        btnSound.setOnClickListener(v -> toggleSoundEnabled());
        btnPunchToggle.setOnClickListener(v -> setPunchEnabled(!punchEnabled));
        btnFullscreen.setOnClickListener(v -> setPreviewFullscreen(!previewFullscreen));
        View statusSummary = view.findViewById(R.id.layout_punch_status_summary);
        statusSummary.setOnClickListener(v -> setStatusHistoryExpanded(!statusHistoryExpanded));
        tvPunchStatusToggle.setOnClickListener(v -> setStatusHistoryExpanded(!statusHistoryExpanded));
        layoutCameraContainer.setOnClickListener(v -> revealStatusPanel());
        updateSoundButtonLabel();
        updatePunchToggleLabel();
        updateSwitchCameraLabel();
        updateFullscreenButtonLabel();
        initAudioFeedback();
        PunchApplication app = PunchApplication.get();
        if (app != null) {
            renderPunchStatusSnapshot(app.getPunchStatusSnapshot());
        }

        textureView.setSurfaceTextureListener(this);
        textureView.addOnLayoutChangeListener((v, left, top, right, bottom,
                                               oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                markPreviewLayoutSettling();
                updatePreviewTransform();
            }
        });
    }


    @Override
    
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int w, int h) {
        ensureCameraReady();
    }

    @Override
    public void onStart() {
        super.onStart();
        PunchApplication app = PunchApplication.get();
        if (app != null) {
            app.addPunchStatusListener(punchStatusListener);
        }
    }

    @Override
    public void onStop() {
        PunchApplication app = PunchApplication.get();
        if (app != null) {
            app.removePunchStatusListener(punchStatusListener);
        }
        super.onStop();
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture s, int w, int h) {
        updatePreviewTransform();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture s) {
        releaseCamera();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture s) {
        if (waitingFirstPreviewFrame) {
            waitingFirstPreviewFrame = false;
            hideCameraLoading();
        }
    }

    
    private void toggleCameraFacing() {
        if (openingCamera) {
            return;
        }

        int targetFacing = cameraFacing == Camera.CameraInfo.CAMERA_FACING_FRONT
                ? Camera.CameraInfo.CAMERA_FACING_BACK
                : Camera.CameraInfo.CAMERA_FACING_FRONT;
        int targetCameraId = findCameraId(targetFacing);
        if (targetCameraId < 0) {
            String failureMessage = targetFacing == Camera.CameraInfo.CAMERA_FACING_FRONT
                    ? "\u8bbe\u5907\u4e0d\u652f\u6301\u524d\u7f6e\u76f8\u673a"
                    : "\u8bbe\u5907\u4e0d\u652f\u6301\u540e\u7f6e\u76f8\u673a";
            setStatus(failureMessage);
            playFailFeedback(buildGenericFailureSpeech(failureMessage));
            return;
        }

        cameraFacing = targetFacing;
        cameraId = targetCameraId;
        SessionManager.get().saveCameraFacing(cameraFacing);
        updateSwitchCameraLabel();
        releaseCamera();
        ensureCameraReady();
    }

    private void updateSwitchCameraLabel() {
        if (btnSwitchCamera == null) {
            return;
        }
        btnSwitchCamera.setText(cameraFacing == Camera.CameraInfo.CAMERA_FACING_FRONT
                ? "\u5207\u540e\u7f6e"
                : "\u5207\u524d\u7f6e");
    }

    private void toggleSoundEnabled() {
        soundEnabled = !soundEnabled;
        SessionManager.get().saveSoundEnabled(soundEnabled);
        updateSoundButtonLabel();
    }

    private void updateSoundButtonLabel() {
        if (btnSound == null) {
            return;
        }
        btnSound.setText(soundEnabled
                ? "\u58f0\u97f3\uff1a\u5f00"
                : "\u58f0\u97f3\uff1a\u5173");
    }

    private void setPunchEnabled(boolean enabled) {
        punchEnabled = enabled;
        if (faceFrameView != null) {
            faceFrameView.setScanAnimationEnabled(enabled && punchActive);
        }
        if (!enabled) {
            clearFaceInteraction();
            setRecognizing(false);
            resetRecognitionAttempt();
            resetPendingMatch();
            if (layoutResult != null) {
                layoutResult.setVisibility(View.GONE);
            }
            waitingFirstPreviewFrame = false;
            hideCameraLoading();
            releaseCamera();
        } else {
            ensureCameraReady();
        }
        updatePunchToggleLabel();
        updateIdleStatus();
        updateScreenAwakeState();
    }

    private void setRecognizing(boolean value) {
        recognizing = value;
        updateScreenAwakeState();
    }

    private void noteFaceInteraction() {
        faceInteractionActive = true;
        uiHandler.removeCallbacks(clearFaceInteractionRunnable);
        uiHandler.postDelayed(clearFaceInteractionRunnable, FACE_INTERACTION_GRACE_MS);
        updateScreenAwakeState();
    }

    private void clearFaceInteraction() {
        faceInteractionActive = false;
        uiHandler.removeCallbacks(clearFaceInteractionRunnable);
        updateScreenAwakeState();
    }

    private void updateScreenAwakeState() {
        Activity activity = getActivity();
        if (activity == null) {
            return;
        }
        Runnable updateFlag = () -> {
            if (getActivity() != activity) {
                return;
            }
            boolean screenActive = punchActive && isResumed() && !isHidden();
            boolean keepScreenOn = ScreenTimeoutPolicy.shouldKeepScreenOn(
                    SessionManager.get().getScreenTimeoutMs(),
                    recognizing || faceInteractionActive,
                    screenActive
            );
            applyScreenOnFlag(activity, keepScreenOn);
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            updateFlag.run();
        } else {
            activity.runOnUiThread(updateFlag);
        }
    }

    private void updatePunchToggleLabel() {
        if (btnPunchToggle == null) {
            return;
        }
        btnPunchToggle.setText(punchEnabled
                ? "\u6253\u5361\uff1a\u5f00"
                : "\u6253\u5361\uff1a\u5173");
    }

    private void updateIdleStatus() {
        if (!isAdded() || tvStatus == null) {
            return;
        }
        if (punchEnabled) {
            setStatus("\u8bf7\u5c06\u9762\u90e8\u5bf9\u51c6\u8bc6\u522b\u6846");
        } else {
            setStatus("\u6253\u5361\u5df2\u5173\u95ed\uff0c\u70b9\u51fb\u201c\u6253\u5361\uff1a\u5173\u201d\u5f00\u542f");
        }
    }

    
    private void setPreviewFullscreen(boolean fullscreen) {
        previewFullscreen = fullscreen;
        markPreviewLayoutSettling();
        if (layoutHeader != null) {
            layoutHeader.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        }
        if (layoutCameraContainer != null) {
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) layoutCameraContainer.getLayoutParams();
            params.height = 0;
            params.weight = 1f;
            layoutCameraContainer.setLayoutParams(params);
        }
        updateFullscreenButtonLabel();
        if (textureView != null) {
            textureView.post(this::updatePreviewTransform);
        }
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).setPunchFullscreen(fullscreen);
        }
    }

    private void updateFullscreenButtonLabel() {
        if (btnFullscreen == null) {
            return;
        }
        btnFullscreen.setText(previewFullscreen
                ? "\u9000\u51fa\u5168\u5c4f"
                : "\u5168\u5c4f");
    }

    private boolean hasMultipleCameras() {
        return Camera.getNumberOfCameras() > 1;
    }

    private int resolveInitialCameraFacing() {
        int savedFacing = SessionManager.get().getCameraFacing(Integer.MIN_VALUE);
        if (findCameraId(savedFacing) >= 0) {
            return savedFacing;
        }
        if (findCameraId(Camera.CameraInfo.CAMERA_FACING_BACK) >= 0) {
            return Camera.CameraInfo.CAMERA_FACING_BACK;
        }
        if (findCameraId(Camera.CameraInfo.CAMERA_FACING_FRONT) >= 0) {
            return Camera.CameraInfo.CAMERA_FACING_FRONT;
        }
        return Camera.CameraInfo.CAMERA_FACING_BACK;
    }

    private int findCameraId(int facing) {
        int count = Camera.getNumberOfCameras();
        Camera.CameraInfo info = new Camera.CameraInfo();
        for (int i = 0; i < count; i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == facing) {
                return i;
            }
        }
        return -1;
    }

    private int getDisplayDegrees() {
        int rotation = requireActivity().getWindowManager().getDefaultDisplay().getRotation();
        switch (rotation) {
            case Surface.ROTATION_90:
                return 90;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_270:
                return 270;
            case Surface.ROTATION_0:
            default:
                return 0;
        }
    }

    private int getCameraDisplayOrientation(int targetCameraId) {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(targetCameraId, info);
        int degrees = getDisplayDegrees();
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            int result = (info.orientation + degrees) % 360;
            return (360 - result) % 360;
        }
        return (info.orientation - degrees + 360) % 360;
    }

    
    private void initAudioFeedback() {
        releaseMediaFeedback();
        initTextToSpeech();
    }

    
    private void playPunchFeedback(PunchRecord record) {
        playResultFeedback(
                buildPunchSuccessSpeech(record),
                R.raw.punch_success
        );
    }

    
    private void playFailFeedback() {
        playResultFeedback(null, R.raw.punch_fail);
    }

    private void playFailFeedback(@Nullable String spokenText) {
        playResultFeedback(spokenText, R.raw.punch_fail);
    }

    /** Plays fixed feedback for forbidden punch scenarios. */
    private void playForbiddenFeedback() {
        playResultFeedback(null, R.raw.punch_forbidden);
    }

    private void playForbiddenFeedback(@Nullable String spokenText) {
        playResultFeedback(spokenText, R.raw.punch_forbidden);
    }

    private void playResultFeedback(@Nullable String spokenText, int fallbackResId) {
        if (!soundEnabled || !isAdded()) {
            return;
        }
        String normalizedText = normalizeSpeechText(spokenText);
        if (!normalizedText.isEmpty() && speakTextFeedback(normalizedText)) {
            return;
        }
        if (!normalizedText.isEmpty() && ttsInitializing) {
            pendingSpeechText = normalizedText;
            pendingSpeechFallbackResId = fallbackResId;
            return;
        }
        playRawSound(fallbackResId);
    }

    private void playRawSound(int resId) {
        releaseMediaFeedback();
        feedbackPlayer = MediaPlayer.create(requireContext().getApplicationContext(), resId);
        if (feedbackPlayer == null) {
            return;
        }
        feedbackPlayer.setOnCompletionListener(mp -> {
            mp.release();
            if (feedbackPlayer == mp) {
                feedbackPlayer = null;
            }
        });
        feedbackPlayer.setOnErrorListener((mp, what, extra) -> {
            mp.release();
            if (feedbackPlayer == mp) {
                feedbackPlayer = null;
            }
            return true;
        });
        feedbackPlayer.start();
    }

    private void initTextToSpeech() {
        if (!soundEnabled || !isAdded() || ttsReady || ttsInitializing) {
            return;
        }
        if (textToSpeech != null) {
            releaseTextToSpeech();
        }
        ttsInitializing = true;
        textToSpeech = new TextToSpeech(requireContext().getApplicationContext(), status -> {
            ttsInitializing = false;
            if (status != TextToSpeech.SUCCESS || textToSpeech == null) {
                flushPendingSpeechFallback();
                releaseTextToSpeech();
                return;
            }
            int languageStatus = textToSpeech.setLanguage(Locale.CHINA);
            if (languageStatus == TextToSpeech.LANG_MISSING_DATA
                    || languageStatus == TextToSpeech.LANG_NOT_SUPPORTED) {
                ttsActiveLocale = Locale.getDefault();
                textToSpeech.setLanguage(ttsActiveLocale);
            } else {
                ttsActiveLocale = Locale.CHINA;
            }
            applyTextToSpeechRate();
            ttsReady = true;
            if (pendingSpeechText != null && !pendingSpeechText.trim().isEmpty()) {
                String text = pendingSpeechText;
                pendingSpeechText = null;
                pendingSpeechFallbackResId = 0;
                speakTextFeedback(text);
            }
        });
    }

    private boolean speakTextFeedback(@Nullable String text) {
        String normalizedText = normalizeSpeechText(text);
        if (normalizedText.isEmpty() || !soundEnabled || !isAdded()) {
            return false;
        }
        if (!ttsReady || textToSpeech == null) {
            initTextToSpeech();
            return false;
        }
        applyTextToSpeechRate();
        textToSpeech.stop();
        int result = textToSpeech.speak(
                normalizedText,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "punch_feedback_" + System.currentTimeMillis()
        );
        return result == TextToSpeech.SUCCESS;
    }

    private String normalizeSpeechText(@Nullable String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace('\n', '\uFF0C').replace('\r', '\u3000').trim();
        while (normalized.contains("，，")) {
            normalized = normalized.replace("，，", "，");
        }
        return normalized;
    }

    private void flushPendingSpeechFallback() {
        if (pendingSpeechFallbackResId == 0 || !isAdded()) {
            pendingSpeechText = null;
            pendingSpeechFallbackResId = 0;
            return;
        }
        int fallbackResId = pendingSpeechFallbackResId;
        pendingSpeechText = null;
        pendingSpeechFallbackResId = 0;
        postToActiveView(() -> {
            if (isAdded()) {
                playRawSound(fallbackResId);
            }
        });
    }

    private void applyTextToSpeechRate() {
        if (textToSpeech == null) {
            return;
        }
        textToSpeech.setSpeechRate(getTextToSpeechRate());
    }

    private boolean isChineseLocale(@Nullable Locale locale) {
        if (locale == null) {
            return false;
        }
        String language = locale.getLanguage();
        return language != null && language.toLowerCase(Locale.ROOT).startsWith("zh");
    }

    private void releaseTextToSpeech() {
        pendingSpeechText = null;
        pendingSpeechFallbackResId = 0;
        ttsReady = false;
        ttsInitializing = false;
        ttsActiveLocale = Locale.getDefault();
        if (textToSpeech != null) {
            try {
                textToSpeech.stop();
            } catch (Exception ignored) {
            }
            textToSpeech.shutdown();
            textToSpeech = null;
        }
    }

    private void releaseMediaFeedback() {
        if (feedbackPlayer != null) {
            try {
                if (feedbackPlayer.isPlaying()) {
                    feedbackPlayer.stop();
                }
            } catch (IllegalStateException ignored) {
            }
            feedbackPlayer.release();
            feedbackPlayer = null;
        }
    }

    private void releaseAudioFeedback() {
        releaseMediaFeedback();
        releaseTextToSpeech();
    }

    
    private void beginRecognitionAttempt() {
        if (recognitionAttemptStartedAt == 0) {
            recognitionAttemptStartedAt = System.currentTimeMillis();
        }
    }

    
    private void resetRecognitionAttempt() {
        recognitionAttemptStartedAt = 0;
    }

    private void resetPendingMatch() {
        pendingMatchEmpId = null;
        pendingMatchCount = 0;
        pendingMatchLastAt = 0L;
    }

    private boolean confirmStableMatch(String empId, long nowMillis) {
        if (empId == null || empId.trim().isEmpty()) {
            resetPendingMatch();
            return false;
        }
        if (!empId.equals(pendingMatchEmpId) || nowMillis - pendingMatchLastAt > STABLE_MATCH_MAX_GAP_MS) {
            pendingMatchEmpId = empId;
            pendingMatchCount = 1;
            pendingMatchLastAt = nowMillis;
            return false;
        }
        pendingMatchCount += 1;
        pendingMatchLastAt = nowMillis;
        if (pendingMatchCount >= STABLE_MATCH_REQUIRED_FRAMES) {
            resetPendingMatch();
            return true;
        }
        return false;
    }

    
    private long getRecognitionTimeoutMs() {
        return SessionManager.get().getRecognitionTimeoutSeconds() * 1000L;
    }

    private boolean isFastPunchModeEnabled() {
        return SessionManager.get().isFastPunchEnabled();
    }

    private long getFrameIntervalMs() {
        if (!isFastPunchModeEnabled()) {
            return FRAME_INTERVAL_MS;
        }
        return clamp(SessionManager.get().getRecognitionFrameIntervalMs(), 250, 1000);
    }

    private long getSuccessResultDisplayMs() {
        if (!isFastPunchModeEnabled()) {
            return RESULT_DISPLAY_MS;
        }
        return clamp(SessionManager.get().getPunchResultDisplayMs(), 0, 5000);
    }

    private long getSuccessCooldownMs() {
        if (!isFastPunchModeEnabled()) {
            return getSuccessResultDisplayMs();
        }
        return clamp(SessionManager.get().getSuccessCooldownMs(), 0, 3000);
    }

    private boolean shouldShowSuccessResultCard() {
        return !isFastPunchModeEnabled() || SessionManager.get().shouldShowPunchResultCard();
    }

    private float getTextToSpeechRate() {
        if (isFastPunchModeEnabled()) {
            return clamp(SessionManager.get().getPunchSpeechRate(), 0.5f, 2.0f);
        }
        return isChineseLocale(ttsActiveLocale)
                ? TTS_SPEECH_RATE_CHINESE
                : TTS_SPEECH_RATE_DEFAULT;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String getEmployeeDisplayName(@Nullable String employeeName, @Nullable String employeeId) {
        if (employeeName != null) {
            String trimmed = employeeName.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        if (employeeId != null) {
            String trimmedId = employeeId.trim();
            if (!trimmedId.isEmpty()) {
                return "\u5de5\u53f7" + trimmedId;
            }
        }
        return "\u8be5\u5458\u5de5";
    }

    private String buildEmployeeStatusMessage(@Nullable String employeeName,
                                              @Nullable String employeeId,
                                              String action,
                                              @Nullable String detail) {
        StringBuilder builder = new StringBuilder()
                .append(getEmployeeDisplayName(employeeName, employeeId))
                .append(action);
        if (detail != null) {
            String trimmed = detail.trim();
            if (!trimmed.isEmpty()) {
                builder.append("\uff1a").append(trimmed);
            }
        }
        return builder.toString();
    }

    private String buildEmployeeResultMessage(@Nullable String employeeName,
                                              @Nullable String employeeId,
                                              String headline,
                                              @Nullable String detail) {
        StringBuilder builder = new StringBuilder()
                .append(getEmployeeDisplayName(employeeName, employeeId))
                .append('\n')
                .append(headline);
        if (detail != null) {
            String trimmed = detail.trim();
            if (!trimmed.isEmpty()) {
                builder.append('\n').append(trimmed);
            }
        }
        return builder.toString();
    }

    private String buildPunchSuccessSpeech(PunchRecord record) {
        String displayName = getEmployeeDisplayName(record.empName, record.empId);
        if (isFastPunchModeEnabled()) {
            String speechMode = SessionManager.get().getPunchSpeechMode();
            if (Constants.PUNCH_SPEECH_MODE_NAME.equals(speechMode)) {
                return displayName;
            }
            if (Constants.PUNCH_SPEECH_MODE_SUCCESS.equals(speechMode)) {
                return "\u6253\u5361\u6210\u529f";
            }
        }
        String action;
        if (PUNCH_TYPE_FREE.equals(record.punchType)) {
            action = "\u81ea\u7531\u6253\u5361\u6210\u529f";
        } else if (Constants.PUNCH_TYPE_SIGN_IN.equals(record.punchType)) {
            action = "\u4e0a\u73ed\u6253\u5361\u6210\u529f";
        } else {
            action = "\u4e0b\u73ed\u6253\u5361\u6210\u529f";
        }
        return displayName + "\uff0c" + action;
    }
    private String buildPunchFailureSpeech(@Nullable String employeeName,
                                           @Nullable String employeeId,
                                           @Nullable String detail) {
        StringBuilder builder = new StringBuilder(getEmployeeDisplayName(employeeName, employeeId))
                .append("，打卡失败");
        if (detail != null) {
            String trimmed = detail.trim();
            if (!trimmed.isEmpty()) {
                builder.append("，").append(trimmed);
            }
        }
        return builder.toString();
    }

    private String buildForbiddenSpeech(@Nullable String employeeName,
                                        @Nullable String employeeId,
                                        @Nullable String detail) {
        StringBuilder builder = new StringBuilder(getEmployeeDisplayName(employeeName, employeeId))
                .append("，禁止打卡");
        if (detail != null) {
            String trimmed = detail.trim();
            if (!trimmed.isEmpty()) {
                builder.append("，").append(trimmed);
            }
        }
        return builder.toString();
    }

    private String buildGenericFailureSpeech(@Nullable String message) {
        if (message == null) {
            return "操作失败，请重试";
        }
        String normalized = message.replace('\n', '，').replace('\r', ' ').trim();
        if (normalized.isEmpty()) {
            return "操作失败，请重试";
        }
        if ("识别超时，请重试".equals(normalized) || "识别超时，请重试。".equals(normalized)) {
            return "识别超时，请重试";
        }
        if (normalized.contains("设备不支持前置相机")) {
            return "设备不支持前置相机";
        }
        if (normalized.contains("设备不支持后置相机")) {
            return "设备不支持后置相机";
        }
        if (normalized.contains("未找到可用相机")) {
            return "未找到可用相机";
        }
        if (normalized.contains("相机权限被拒绝")) {
            return "相机权限被拒绝";
        }
        if (normalized.contains("相机启动失败")) {
            return "相机启动失败，请检查设备相机";
        }
        return normalized;
    }

    private CharSequence formatResultMessage(String resultMessage, boolean success) {
        if (resultMessage == null || resultMessage.trim().isEmpty()) {
            return "";
        }
        SpannableString spannable = new SpannableString(resultMessage);
        String[] lines = resultMessage.split("\n");
        int cursor = 0;
        int successColor = ContextCompat.getColor(requireContext(), R.color.log_status_success);
        int errorColor = ContextCompat.getColor(requireContext(), R.color.log_status_error);
        int primaryColor = ContextCompat.getColor(requireContext(), R.color.text_primary);
        int secondaryColor = ContextCompat.getColor(requireContext(), R.color.text_secondary);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int start = cursor;
            int end = start + line.length();
            if (end > start) {
                if (i == 0) {
                    applyLineStyle(spannable, start, end, 24, primaryColor, true);
                } else if (i == 1) {
                    applyLineStyle(spannable, start, end, 26, success ? successColor : errorColor, true);
                } else {
                    applyLineStyle(spannable, start, end, 18, secondaryColor, true);
                }
            }
            cursor = end + 1;
        }
        return spannable;
    }

    private void applyLineStyle(SpannableString spannable,
                                int start,
                                int end,
                                int sizeSp,
                                int color,
                                boolean bold) {
        spannable.setSpan(new AbsoluteSizeSpan(sizeSp, true), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        spannable.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (bold) {
            spannable.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private void showResultCard(String statusMessage, String resultMessage, boolean success) {
        showResultCard(statusMessage, resultMessage, success, null, null, null, null);
    }

    private void showResultCard(String statusMessage,
                                String resultMessage,
                                boolean success,
                                @Nullable String displayName,
                                @Nullable String primaryAvatarSource,
                                @Nullable String fallbackAvatarSource,
                                @Nullable String cleanupAvatarPath) {
        showResultCard(
                statusMessage,
                resultMessage,
                success,
                displayName,
                primaryAvatarSource,
                fallbackAvatarSource,
                cleanupAvatarPath,
                RESULT_DISPLAY_MS
        );
    }

    private void showResultCard(String statusMessage,
                                String resultMessage,
                                boolean success,
                                @Nullable String displayName,
                                @Nullable String primaryAvatarSource,
                                @Nullable String fallbackAvatarSource,
                                @Nullable String cleanupAvatarPath,
                                long displayMs) {
        setRecognizing(true);
        setStatus(statusMessage);
        applyResultCardStyle(success);
        bindResultAvatar(displayName, primaryAvatarSource, fallbackAvatarSource);
        tvResult.setText(formatResultMessage(resultMessage, success));
        layoutResult.setVisibility(View.VISIBLE);

        postToActiveViewDelayed(() -> {
            if (cleanupAvatarPath != null && !cleanupAvatarPath.trim().isEmpty()) {
                PunchSnapshotHelper.deleteSnapshot(cleanupAvatarPath);
            }
            clearResultAvatar();
            layoutResult.setVisibility(View.GONE);
            setRecognizing(false);
            resetRecognitionAttempt();
            resetPendingMatch();
            updateIdleStatus();
        }, Math.max(0L, displayMs));
    }

    private void releaseRecognitionAfter(long delayMs) {
        postToActiveViewDelayed(() -> {
            setRecognizing(false);
            resetRecognitionAttempt();
            resetPendingMatch();
            updateIdleStatus();
        }, Math.max(0L, delayMs));
    }

    private void bindResultAvatar(@Nullable String displayName,
                                  @Nullable String primaryAvatarSource,
                                  @Nullable String fallbackAvatarSource) {
        if (layoutResultAvatar == null || ivResultAvatar == null
                || tvResultAvatarFallback == null || tvResultAvatarTag == null) {
            return;
        }
        String snapshotSource = sanitizeLocalImageSource(primaryAvatarSource);
        String fallbackSource = sanitizeImageSource(fallbackAvatarSource);
        String selectedSource = snapshotSource != null ? snapshotSource : fallbackSource;
        String avatarName = displayName != null ? displayName : "";
        if (selectedSource == null && avatarName.trim().isEmpty()) {
            clearResultAvatar();
            layoutResultAvatar.setVisibility(View.GONE);
            return;
        }
        layoutResultAvatar.setVisibility(View.VISIBLE);
        if (selectedSource != null) {
            AvatarLoader.load(ivResultAvatar, tvResultAvatarFallback, avatarName, selectedSource);
        } else {
            AvatarLoader.clear(ivResultAvatar);
            tvResultAvatarFallback.setText(getEmployeeDisplayName(displayName, null).substring(0, 1));
            tvResultAvatarFallback.setVisibility(View.VISIBLE);
        }
        if (snapshotSource != null) {
            tvResultAvatarTag.setVisibility(View.VISIBLE);
            tvResultAvatarTag.setText("本次抓拍");
        } else if (fallbackSource != null) {
            tvResultAvatarTag.setVisibility(View.VISIBLE);
            tvResultAvatarTag.setText("员工库照");
        } else {
            tvResultAvatarTag.setVisibility(View.GONE);
        }
    }

    private void clearResultAvatar() {
        if (layoutResultAvatar != null) {
            layoutResultAvatar.setVisibility(View.GONE);
        }
        if (ivResultAvatar != null) {
            AvatarLoader.clear(ivResultAvatar);
        }
        if (tvResultAvatarFallback != null) {
            tvResultAvatarFallback.setText("");
        }
        if (tvResultAvatarTag != null) {
            tvResultAvatarTag.setVisibility(View.GONE);
        }
    }

    @Nullable
    private String sanitizeLocalImageSource(@Nullable String source) {
        if (source == null) {
            return null;
        }
        String trimmed = source.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        File file = new File(trimmed);
        if (!file.exists() || !file.isFile()) {
            return null;
        }
        return file.getAbsolutePath();
    }

    @Nullable
    private String sanitizeImageSource(@Nullable String source) {
        String local = sanitizeLocalImageSource(source);
        if (local != null) {
            return local;
        }
        if (source == null) {
            return null;
        }
        String trimmed = source.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void showEmployeeLookupFailure(String empId) {
        String statusMessage = buildEmployeeStatusMessage(
                null,
                empId,
                "\u6253\u5361\u5931\u8d25",
                "\u672a\u627e\u5230\u672c\u5730\u5458\u5de5\u6570\u636e"
        );
        playFailFeedback(buildPunchFailureSpeech(null, empId, "\u672a\u627e\u5230\u672c\u5730\u5458\u5de5\u6570\u636e"));
        showResultCard(
                statusMessage,
                buildEmployeeResultMessage(
                        null,
                        empId,
                        "\u6253\u5361\u5931\u8d25",
                        "\u672a\u627e\u5230\u672c\u5730\u5458\u5de5\u6570\u636e"
                ),
                false,
                getEmployeeDisplayName(null, empId),
                null,
                null,
                null
        );
    }

    private void showSnapshotCaptureFailure(Employee emp) {
        String statusMessage = buildEmployeeStatusMessage(
                emp.name,
                emp.id,
                "\u6253\u5361\u5931\u8d25",
                "\u4eba\u8138\u6293\u62cd\u5931\u8d25\uff0c\u8bf7\u91cd\u8bd5"
        );
        playFailFeedback(buildPunchFailureSpeech(emp.name, emp.id, "\u4eba\u8138\u6293\u62cd\u5931\u8d25\uff0c\u8bf7\u91cd\u8bd5"));
        showResultCard(
                statusMessage,
                buildEmployeeResultMessage(
                        emp.name,
                        emp.id,
                        "\u6253\u5361\u5931\u8d25",
                        "\u4eba\u8138\u6293\u62cd\u5931\u8d25\uff0c\u8bf7\u91cd\u8bd5"
                ),
                false,
                getEmployeeDisplayName(emp.name, emp.id),
                null,
                emp.faceImageUrl,
                null
        );
    }

    
    private void showTransientFailureResult(String message) {
        showTransientFailureResult(message, null);
    }

    private void showTransientFailureResult(String message, @Nullable String snapshotPath) {
        playFailFeedback(buildGenericFailureSpeech(message));
        showResultCard(message, message, false, null, snapshotPath, null, snapshotPath);
    }



    
    private void updatePreviewTransform() {
        if (textureView == null || previewWidth <= 0 || previewHeight <= 0) {
            return;
        }
        int viewWidth = textureView.getWidth();
        int viewHeight = textureView.getHeight();
        if (viewWidth <= 0 || viewHeight <= 0) {
            return;
        }

        boolean rotated = frameRotation == 90 || frameRotation == 270;
        float bufferWidth = rotated ? previewHeight : previewWidth;
        float bufferHeight = rotated ? previewWidth : previewHeight;
        float scale = Math.max(viewWidth / bufferWidth, viewHeight / bufferHeight);

        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0f, 0f, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0f, 0f, bufferWidth, bufferHeight);
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
        matrix.postScale(scale, scale, centerX, centerY);
        textureView.setTransform(matrix);
    }

    @SuppressWarnings("deprecation")
    
    private void openCamera(SurfaceTexture surface) {
        if (!isAdded() || surface == null || camera != null || openingCamera) {
            return;
        }
        try {
            openingCamera = true;
            Camera.CameraInfo info = new Camera.CameraInfo();
            cameraId = findCameraId(cameraFacing);
            if (cameraId < 0) {
                waitingFirstPreviewFrame = false;
                hideCameraLoading();
                String failureMessage = "\u672a\u627e\u5230\u53ef\u7528\u76f8\u673a";
                setStatus(failureMessage);
                playFailFeedback(buildGenericFailureSpeech(failureMessage));
                return;
            }
            camera = Camera.open(cameraId);
            Camera.Parameters params = camera.getParameters();
            params.setPreviewFormat(ImageFormat.NV21);
            Camera.Size previewSize = choosePreviewSize(params);
            if (previewSize != null) {
                previewWidth = previewSize.width;
                previewHeight = previewSize.height;
                params.setPreviewSize(previewSize.width, previewSize.height);
                surface.setDefaultBufferSize(previewSize.width, previewSize.height);
            }
            List<String> focusModes = params.getSupportedFocusModes();
            if (focusModes != null && focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            }
            camera.setParameters(params);

            Camera.getCameraInfo(cameraId, info);
            frameMirror = info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT ? 1 : 0;
            frameRotation = getCameraDisplayOrientation(cameraId);
            camera.setDisplayOrientation(frameRotation);
            camera.setPreviewTexture(surface);
            textureView.post(this::updatePreviewTransform);

            camera.setPreviewCallback((data, cam) -> {
                if (!punchActive) {
                    return;
                }
                if (!punchEnabled || recognizing) {
                    return;
                }
                if (isPreviewLayoutSettling()) {
                    return;
                }
                long now = System.currentTimeMillis();
                if (frameProcessing || (now - lastFrameTime) < getFrameIntervalMs()) return;
                frameProcessing = true;
                lastFrameTime = now;

                Camera.Parameters p = cam.getParameters();
                int w = p.getPreviewSize().width;
                int h = p.getPreviewSize().height;
                byte[] frameCopy = data.clone();

                final int snapshotRotation = getSnapshotRotation(cameraId);
                final int taskViewToken = viewToken;
                Context context = getContext();
                if (context == null || !viewGate.isActive(taskViewToken)) {
                    frameProcessing = false;
                    return;
                }
                final Context taskContext = context.getApplicationContext();

                executor.execute(() -> {
                    try {
                        processFrame(
                                taskContext,
                                taskViewToken,
                                frameCopy,
                                w,
                                h,
                                frameRotation,
                                frameMirror,
                                snapshotRotation);
                    } finally {
                        frameProcessing = false;
                    }
                });
            });

            camera.startPreview();
            updateIdleStatus();
        } catch (IOException | RuntimeException e) {
            waitingFirstPreviewFrame = false;
            hideCameraLoading();
            Log.e(TAG, "openCamera error", e);
            releaseCamera();
            String failureMessage = "\u76f8\u673a\u542f\u52a8\u5931\u8d25: " + e.getMessage();
            setStatus(failureMessage);
            playFailFeedback(buildGenericFailureSpeech(failureMessage));
        } finally {
            openingCamera = false;
        }
    }

    
    private void releaseCamera() {
        cancelDelayedCameraRelease();
        releaseCameraNow();
    }

    private void releaseCameraNow() {
        waitingFirstPreviewFrame = false;
        if (camera != null) {
            try {
                camera.setPreviewCallback(null);
            } catch (Exception ignored) {
            }
            try {
                camera.stopPreview();
            } catch (Exception ignored) {
            }
            try {
                camera.release();
            } catch (Exception ignored) {
            }
            camera = null;
        }
        openingCamera = false;
    }

    private void scheduleCameraRelease() {
        cancelDelayedCameraRelease();
        uiHandler.postDelayed(delayedCameraRelease, CAMERA_RELEASE_DELAY_MS);
    }

    private void cancelDelayedCameraRelease() {
        uiHandler.removeCallbacks(delayedCameraRelease);
    }

    
    private void ensureCameraReady() {
        cancelDelayedCameraRelease();
        if (!isAdded() || textureView == null || !PunchCameraPolicy.shouldUseCamera(
                punchActive,
                punchEnabled,
                textureView.isAvailable(),
                isHidden()
        )) {
            return;
        }
        if (!hasCameraPermission()) {
            KioskManager.ensureOwnerRuntimePermissions(requireContext());
            if (!hasCameraPermission()) {
                waitingFirstPreviewFrame = false;
                hideCameraLoading();
                String failureMessage = "\u76f8\u673a\u6743\u9650\u672a\u6388\u4e88\uff0c\u8bf7\u786e\u8ba4 Device Owner \u9759\u9ed8\u6388\u6743";
                setStatus(failureMessage);
                playFailFeedback(buildGenericFailureSpeech(failureMessage));
                return;
            }
        }
        waitingFirstPreviewFrame = true;
        showCameraLoading("\u76f8\u673a\u51c6\u5907\u4e2d...");
        openCamera(textureView.getSurfaceTexture());
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressWarnings("deprecation")
    private Camera.Size choosePreviewSize(Camera.Parameters params) {
        List<Camera.Size> sizes = params.getSupportedPreviewSizes();
        if (sizes == null || sizes.isEmpty()) {
            return params.getPreviewSize();
        }

        Camera.Size fallback = sizes.get(0);
        Camera.Size preferred = null;
        for (Camera.Size size : sizes) {
            if (size.width == 640 && size.height == 480) {
                return size;
            }
            if (size.width == 720 && size.height == 480) {
                preferred = size;
            } else if (preferred == null && size.width <= 1280 && size.height <= 720) {
                preferred = size;
            }
        }
        return preferred != null ? preferred : fallback;
    }

    @Override
    
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (hidden) {
            punchActive = false;
            setPunchEnabled(false);
            waitingFirstPreviewFrame = false;
            hideCameraLoading();
            resetRecognitionAttempt();
            setScreenOnLocked(false);
            if (previewFullscreen) {
                setPreviewFullscreen(false);
            }
            scheduleCameraRelease();
        } else {
            punchActive = true;
            setPunchEnabled(PunchCameraPolicy.shouldEnablePunchOnVisibleEntry(
                    getResources().getBoolean(R.bool.camera_face_soak_auto_enable)));
            updateScreenAwakeState();
            if (tvLine != null) {
                refreshBindingHeader();
            }
            }
    }


    
    private void processFrame(Context context,
                              int taskViewToken,
                              byte[] nv21,
                              int width,
                              int height,
                              int angle,
                              int mirror,
                              int snapshotRotation) {
        if (!viewGate.isActive(taskViewToken) || recognizing || !punchEnabled) return;
        if (isPreviewLayoutSettling()) return;
        PunchApplication app = PunchApplication.get();
        if (app != null && !app.isPunchRecognitionReady()) {
            app.preparePunchRecognitionData();
            postToActiveView(taskViewToken, () -> setStatus(app.getPunchDataStatus()));
            return;
        }
        if (!FaceManager.get().isInitialized()) {
            if (app != null) {
                app.preparePunchRecognitionData();
            }
            postToActiveView(taskViewToken, () -> setStatus("\u4eba\u8138\u5f15\u64ce\u521d\u59cb\u5316\u4e2d..."));
            return;
        }

        long startedAt = System.currentTimeMillis();
        FaceManager.RecognizeResult result =
                FaceManager.get().recognizeFromNv21(nv21, width, height, angle, mirror);
        long durationMs = System.currentTimeMillis() - startedAt;
        if (!viewGate.isActive(taskViewToken)) {
            return;
        }
        if (!FaceManager.ERROR_NO_FACE_DETECTED.equals(result.errorMsg)) {
            postToActiveView(taskViewToken, this::noteFaceInteraction);
        }

        if (!result.matched) {
            resetPendingMatch();
            if (FaceManager.ERROR_NO_FACE_DETECTED.equals(result.errorMsg)) {
                resetRecognitionAttempt();
                return;
            }

            if (FaceManager.ERROR_LIVENESS_CHECK_FAILED.equals(result.errorMsg)) {
                logLivenessFailure(width, height, angle, mirror, durationMs, result.debugDetail);
            }

            beginRecognitionAttempt();
            long now = System.currentTimeMillis();
            if (now - recognitionAttemptStartedAt >= getRecognitionTimeoutMs()
                    && now - lastRecognitionTimeoutAt >= RECOGNITION_TIMEOUT_FEEDBACK_COOLDOWN_MS) {
                lastRecognitionTimeoutAt = now;
                postToActiveView(taskViewToken, () -> showTransientFailureResult("\u8bc6\u522b\u8d85\u65f6\n\u8bf7\u91cd\u8bd5"));
                return;
            }

            if (!FaceManager.ERROR_NO_FACE_DETECTED.equals(result.errorMsg)) {
                postToActiveView(taskViewToken, () -> setStatus(result.errorMsg != null ? result.errorMsg : "\u6b63\u5728\u8bc6\u522b..."));
            }
            return;
        }

        if (!isFaceInsideFrame(result.faceBounds, width, height, angle)) {
            resetPendingMatch();
            resetRecognitionAttempt();
            postToActiveView(taskViewToken, () -> setStatus("\u8bf7\u5c06\u9762\u90e8\u5bf9\u51c6\u8bc6\u522b\u6846"));
            return;
        }

        resetRecognitionAttempt();
        long matchedAt = System.currentTimeMillis();
        if (!confirmStableMatch(result.empId, matchedAt)) {
            postToActiveView(taskViewToken, () -> setStatus("\u6b63\u5728\u786e\u8ba4\u8eab\u4efd..."));
            return;
        }

        if (!viewGate.isActive(taskViewToken)) {
            return;
        }
        Employee emp = DatabaseHelper.get(context).getEmployee(result.empId);
        if (emp == null) {
            postToActiveView(taskViewToken, () -> showEmployeeLookupFailure(result.empId));
            return;
        }

        String clientRecordId = "P" + SessionManager.get().getDeviceId() + "_" + UlidGenerator.generate();
        PunchSnapshotHelper.Snapshot snapshot = PunchSnapshotHelper.capture(
                context,
                clientRecordId,
                nv21,
                width,
                height,
                snapshotRotation,
                mirror,
                getNormalizedFrameCropRect(width, height, angle)
        );
        postToActiveView(taskViewToken,
                () -> doPunch(emp, result.score, durationMs, clientRecordId, snapshot));
    }

    private boolean isFaceInsideFrame(@Nullable RectF faceBounds, int width, int height, int angle) {
        RectF faceViewRect = mapDisplayBufferRectToView(faceBounds, width, height, angle);
        RectF frameRect = getFaceFrameRect();
        if (faceViewRect == null || frameRect == null) {
            return false;
        }
        boolean centerInside = frameRect.contains(faceViewRect.centerX(), faceViewRect.centerY());
        RectF intersection = new RectF(faceViewRect);
        boolean intersects = intersection.intersect(frameRect);
        float faceArea = faceViewRect.width() * faceViewRect.height();
        float overlap = intersects && faceArea > 0f
                ? (intersection.width() * intersection.height()) / faceArea
                : 0f;
        return centerInside && overlap >= FACE_FRAME_MIN_OVERLAP;
    }

    @Nullable
    private RectF getNormalizedFrameCropRect(int width, int height, int angle) {
        RectF frameRect = getFaceFrameRect();
        if (frameRect == null || textureView == null) {
            return null;
        }
        int viewWidth = textureView.getWidth();
        int viewHeight = textureView.getHeight();
        if (viewWidth <= 0 || viewHeight <= 0 || width <= 0 || height <= 0) {
            return null;
        }
        boolean rotated = angle == 90 || angle == 270;
        float displayWidth = rotated ? height : width;
        float displayHeight = rotated ? width : height;
        float scale = Math.max(viewWidth / displayWidth, viewHeight / displayHeight);
        float offsetX = (viewWidth - displayWidth * scale) / 2f;
        float offsetY = (viewHeight - displayHeight * scale) / 2f;

        float left = clamp((frameRect.left - offsetX) / scale, 0f, displayWidth);
        float top = clamp((frameRect.top - offsetY) / scale, 0f, displayHeight);
        float right = clamp((frameRect.right - offsetX) / scale, left + 1f, displayWidth);
        float bottom = clamp((frameRect.bottom - offsetY) / scale, top + 1f, displayHeight);
        return new RectF(
                left / displayWidth,
                top / displayHeight,
                right / displayWidth,
                bottom / displayHeight
        );
    }

    @Nullable
    private RectF mapDisplayBufferRectToView(@Nullable RectF sourceRect, int width, int height, int angle) {
        if (sourceRect == null || textureView == null || width <= 0 || height <= 0) {
            return null;
        }
        int viewWidth = textureView.getWidth();
        int viewHeight = textureView.getHeight();
        if (viewWidth <= 0 || viewHeight <= 0) {
            return null;
        }
        boolean rotated = angle == 90 || angle == 270;
        float displayWidth = rotated ? height : width;
        float displayHeight = rotated ? width : height;
        float scale = Math.max(viewWidth / displayWidth, viewHeight / displayHeight);
        float offsetX = (viewWidth - displayWidth * scale) / 2f;
        float offsetY = (viewHeight - displayHeight * scale) / 2f;
        return new RectF(
                sourceRect.left * scale + offsetX,
                sourceRect.top * scale + offsetY,
                sourceRect.right * scale + offsetX,
                sourceRect.bottom * scale + offsetY
        );
    }

    @Nullable
    private RectF getFaceFrameRect() {
        if (faceFrameView == null || faceFrameView.getWidth() <= 0 || faceFrameView.getHeight() <= 0) {
            return null;
        }
        return faceFrameView.getFrameRect();
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private void markPreviewLayoutSettling() {
        previewLayoutSettlingUntil = System.currentTimeMillis() + PREVIEW_LAYOUT_SETTLE_MS;
    }

    private boolean isPreviewLayoutSettling() {
        return System.currentTimeMillis() < previewLayoutSettlingUntil;
    }

    private void logLivenessFailure(int width,
                                    int height,
                                    int angle,
                                    int mirror,
                                    long durationMs,
                                    @Nullable String livenessDetail) {
        long now = System.currentTimeMillis();
        if (now - lastLivenessDebugLogAt < LIVENESS_DEBUG_LOG_COOLDOWN_MS) {
            return;
        }
        lastLivenessDebugLogAt = now;
        String detail = "liveness=" + (livenessDetail == null || livenessDetail.trim().isEmpty()
                ? "-"
                : livenessDetail.trim())
                + "\nwidth=" + width
                + ", height=" + height
                + ", angle=" + angle
                + ", mirror=" + mirror
                + ", cameraFacing=" + cameraFacing
                + ", frameRotation=" + frameRotation
                + ", frameMirror=" + frameMirror
                + ", previewWidth=" + previewWidth
                + ", previewHeight=" + previewHeight
                + ", durationMs=" + durationMs;
        AppLogger.w(TAG, "Liveness check failed: " + detail);
        InteractionLogger.logBusinessFailure(
                InteractionLogger.GROUP_PUNCH,
                "活体检测失败",
                detail
        );
    }

    private int getSnapshotRotation(int targetCameraId) {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(targetCameraId, info);
        int degrees = getDisplayDegrees();
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            return (info.orientation + degrees) % 360;
        }
        return (info.orientation - degrees + 360) % 360;
    }

    
    private void updatePunchTypeFromSelection() {
        if (isSelectedFreePunch()) {
            punchType = PUNCH_TYPE_FREE;
        } else {
            punchType = isSelectedSignIn()
                    ? Constants.PUNCH_TYPE_SIGN_IN
                    : Constants.PUNCH_TYPE_SIGN_OUT;
        }
    }

    
    private String getSelectedPunchOptionLabel() {
        Object selected = spinnerShift != null ? spinnerShift.getSelectedItem() : null;
        return selected != null ? selected.toString() : FREE_PUNCH_OPTION_LABEL;
    }

    
    private boolean isSelectedSignIn() {
        return getSelectedPunchOptionLabel().endsWith("\u4e0a\u73ed");
    }

    private boolean isSelectedFreePunch() {
        return FREE_PUNCH_OPTION_LABEL.equals(getSelectedPunchOptionLabel());
    }

    private List<Integer> getAllowedPunchOptionIndexesNow() {
        return PunchTimeResolver.findAllowedPunchOptionIndexes(
                punchOptions,
                System.currentTimeMillis(),
                SessionManager.get().getPunchTimeWindowMinutes(),
                SessionManager.get().getOvertimeSignOutOptions()
        );
    }

    private boolean isSelectedPunchOptionWithinAllowedTime() {
        if (isSelectedFreePunch() || specialTimeEnabled) {
            return true;
        }
        return PunchTimeResolver.isWithinAllowedPunchTime(
                getSelectedPunchOptionLabel(),
                System.currentTimeMillis(),
                SessionManager.get().getPunchTimeWindowMinutes(),
                punchOptions,
                SessionManager.get().getOvertimeSignOutOptions()
        );
    }

    private void showOutOfPunchTimeRangeDialog(@Nullable PunchSnapshotHelper.Snapshot snapshot) {
        if (snapshot != null) {
            PunchSnapshotHelper.deleteSnapshot(snapshot.path);
        }
        resetRecognitionAttempt();
        resetPendingMatch();
        if (!isAdded()) {
            setRecognizing(false);
            return;
        }
        setStatus("\u5f53\u524d\u6253\u5361\u4e0d\u5728\u6253\u5361\u65f6\u95f4\u8303\u56f4");
        playForbiddenFeedback("\u5f53\u524d\u6253\u5361\u4e0d\u5728\u6253\u5361\u65f6\u95f4\u8303\u56f4");
        final boolean[] autoSelected = {false};
        final int[] selectedIndex = {-1};
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext())
                .setTitle("\u65e0\u6cd5\u6253\u5361")
                .setMessage("\u5f53\u524d\u6253\u5361\u4e0d\u5728\u6253\u5361\u65f6\u95f4\u8303\u56f4")
                .setPositiveButton("\u786e\u5b9a", null)
                .setOnDismissListener(dialog -> {
                    setRecognizing(false);
                    if (autoSelected[0]) {
                        setStatus("\u5df2\u81ea\u52a8\u9009\u62e9\uff1a" + getPunchOptionLabel(selectedIndex[0]));
                    } else {
                        updateIdleStatus();
                    }
                });
        int autoSelectableIndex = resolveUniqueAllowedPunchOptionIndex();
        if (autoSelectableIndex >= 0) {
            builder.setNegativeButton("\u81ea\u52a8\u9009\u62e9", (dialog, which) -> {
                autoSelected[0] = true;
                selectedIndex[0] = autoSelectableIndex;
                spinnerShift.setSelection(autoSelectableIndex);
                ambiguousPunchSelectionPending = false;
            });
        }
        builder.show();
    }

    private int resolveUniqueAllowedPunchOptionIndex() {
        List<Integer> allowedIndexes = getAllowedPunchOptionIndexesNow();
        if (allowedIndexes.size() != 1) {
            return -1;
        }
        int index = allowedIndexes.get(0);
        if (spinnerShift != null && spinnerShift.getSelectedItemPosition() == index) {
            return -1;
        }
        return index;
    }

    private String getPunchOptionLabel(int position) {
        if (position < 0 || position >= punchOptions.size()) {
            return "";
        }
        String label = punchOptions.get(position);
        return label == null ? "" : label;
    }

    private void showAmbiguousPunchOptionDialog(@Nullable PunchSnapshotHelper.Snapshot snapshot) {
        if (snapshot != null) {
            PunchSnapshotHelper.deleteSnapshot(snapshot.path);
        }
        resetRecognitionAttempt();
        resetPendingMatch();
        if (!isAdded()) {
            setRecognizing(false);
            return;
        }
        setStatus("\u5f53\u524d\u65f6\u95f4\u547d\u4e2d\u591a\u4e2a\u6253\u5361\u9879\uff0c\u8bf7\u624b\u52a8\u9009\u62e9");
        playForbiddenFeedback("\u5f53\u524d\u65f6\u95f4\u547d\u4e2d\u591a\u4e2a\u6253\u5361\u9879\uff0c\u8bf7\u624b\u52a8\u9009\u62e9");
        new AlertDialog.Builder(requireContext())
                .setTitle("\u8bf7\u624b\u52a8\u9009\u62e9\u6253\u5361\u9879")
                .setMessage("\u5f53\u524d\u65f6\u95f4\u547d\u4e2d\u591a\u4e2a\u6253\u5361\u9879\uff0c\u8bf7\u624b\u52a8\u9009\u62e9")
                .setPositiveButton("\u786e\u5b9a", null)
                .setOnDismissListener(dialog -> {
                    setRecognizing(false);
                    updateIdleStatus();
                })
                .show();
    }

    private int getSelectedClockIndex() {
        if (spinnerShift == null) {
            return FREE_PUNCH_OPTION_VALUE;
        }
        if (isSelectedFreePunch()) {
            return FREE_PUNCH_OPTION_VALUE;
        }
        int selectedIndex = spinnerShift.getSelectedItemPosition();
        return selectedIndex >= 0 ? selectedIndex + 1 : FREE_PUNCH_OPTION_VALUE;
    }

    
    private long resolvePunchTimeSeconds() {
        if (!specialTimeEnabled) {
            return System.currentTimeMillis() / 1000;
        }
        return resolveScheduledPunchTimeSeconds(getSelectedPunchOptionLabel());
    }

    
    private long resolveScheduledPunchTimeSeconds(String optionLabel) {
        return PunchTimeResolver.resolveAllowedPunchTimeSeconds(
                optionLabel,
                System.currentTimeMillis(),
                SessionManager.get().getPunchTimeWindowMinutes(),
                punchOptions,
                SessionManager.get().getOvertimeSignOutOptions()
        );
    }

    
    private String buildPunchDate(long punchTimeSeconds) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                .format(new Date(punchTimeSeconds * 1000));
    }

    /** Leave and rest statuses are both forbidden for punching. */
    private boolean isForbiddenStatus(String status) {
        return Constants.STATUS_LEAVE.equals(status) || Constants.STATUS_REST.equals(status);
    }

    
    private String getStatusLabel(String status) {
        if (Constants.STATUS_LEAVE.equals(status)) {
            return "\u8bf7\u5047";
        }
        if (Constants.STATUS_REST.equals(status)) {
            return "\u4f11\u606f";
        }
        return "\u6b63\u5e38";
    }

    
    private void showForbiddenPunchDialog(Employee emp, String status, @Nullable String snapshotPath) {
        postToActiveView(() -> {
            String statusLabel = getStatusLabel(status);
            String statusMessage = buildEmployeeStatusMessage(emp.name, emp.id, "\u7981\u6b62\u6253\u5361", statusLabel);
            playForbiddenFeedback(buildForbiddenSpeech(emp.name, emp.id, statusLabel));
            showResultCard(
                    statusMessage,
                    buildEmployeeResultMessage(emp.name, emp.id, "\u7981\u6b62\u6253\u5361", statusLabel),
                    false,
                    getEmployeeDisplayName(emp.name, emp.id),
                    snapshotPath,
                    emp.faceImageUrl,
                    snapshotPath
            );
        });
    }

    private void doPunch(Employee emp,
                         float matchScore,
                         long durationMs,
                         String clientRecordId,
                         @Nullable PunchSnapshotHelper.Snapshot snapshot) {
        setRecognizing(true);

        String deviceId = SessionManager.get().getDeviceId();
        String lineCode = SessionManager.get().getLineCode();
        int teamBindingId = SessionManager.get().getTeamBindingId();
        int clockIndex = getSelectedClockIndex();
        String status = emp.status != null ? emp.status : Constants.STATUS_NORMAL;
        String shiftLabel = getSelectedPunchOptionLabel();
        long punchTime = resolvePunchTimeSeconds();
        String punchDate = buildPunchDate(punchTime);

        if (ambiguousPunchSelectionPending) {
            showAmbiguousPunchOptionDialog(snapshot);
            return;
        }

        if (!isSelectedPunchOptionWithinAllowedTime()) {
            showOutOfPunchTimeRangeDialog(snapshot);
            return;
        }

        if (isForbiddenStatus(status)) {
            showForbiddenPunchDialog(emp, status, snapshot != null ? snapshot.path : null);
            return;
        }

        if (isBlank(lineCode) || teamBindingId <= 0) {
            showMissingBindingConfigFailure(snapshot, lineCode, teamBindingId);
            return;
        }

        if (shouldBlockPunchByCheckCount(emp.id, punchDate, lineCode, teamBindingId, clockIndex)) {
            showCheckCountLimitReached(emp, snapshot);
            return;
        }
        if (snapshot == null) {
            showSnapshotCaptureFailure(emp);
            return;
        }

        PunchRecord record = new PunchRecord();
        record.id = UlidGenerator.generate();
        record.clientRecordId = clientRecordId;
        record.empId = emp.id;
        record.empName = emp.name;
        record.dept = emp.dept;
        record.punchTime = punchTime;
        record.punchDate = punchDate;
        record.punchType = punchType;
        record.shiftName = shiftLabel;
        record.lineCode = lineCode;
        record.teamBindingId = teamBindingId;
        record.clockIndex = clockIndex;
        record.matchScore = matchScore;
        record.snapImagePath = snapshot.path;
        record.snapImageMimeType = snapshot.mimeType;
        record.snapImageWidth = snapshot.width;
        record.snapImageHeight = snapshot.height;
        record.snapImageSize = snapshot.sizeBytes;
        record.snapCapturedAt = snapshot.capturedAtSeconds;
        record.isSynced = 0;

        savePunchAndSync(record);
    }

    private boolean shouldBlockPunchByCheckCount(String empId,
                                                 String punchDate,
                                                 String lineCode,
                                                 int teamBindingId,
                                                 int clockIndex) {
        int checkCount = SessionManager.get().getCheckCount();
        if (checkCount <= 0 || lineCode == null || lineCode.trim().isEmpty() || teamBindingId <= 0) {
            return false;
        }
        List<String> signedEmpIds = DatabaseHelper.get(requireContext())
                .getSignedEmpIds(punchDate, lineCode, teamBindingId, clockIndex);
        if (signedEmpIds.contains(empId)) {
            return false;
        }
        return signedEmpIds.size() >= checkCount;
    }

    private void showCheckCountLimitReached(Employee emp, @Nullable PunchSnapshotHelper.Snapshot snapshot) {
        String statusMessage = buildEmployeeStatusMessage(
                emp.name,
                emp.id,
                "\u7981\u6b62\u6253\u5361",
                "\u5f53\u524d\u73ed\u6b21\u4eba\u6570\u5df2\u8fbe\u4e0a\u9650"
        );
        playForbiddenFeedback(buildForbiddenSpeech(emp.name, emp.id, "\u5f53\u524d\u73ed\u6b21\u4eba\u6570\u5df2\u8fbe\u4e0a\u9650"));
        showResultCard(
                statusMessage,
                buildEmployeeResultMessage(emp.name, emp.id, "\u7981\u6b62\u6253\u5361", "\u5f53\u524d\u73ed\u6b21\u4eba\u6570\u5df2\u8fbe\u4e0a\u9650"),
                false,
                getEmployeeDisplayName(emp.name, emp.id),
                snapshot != null ? snapshot.path : null,
                emp.faceImageUrl,
                snapshot != null ? snapshot.path : null
        );
    }

    
    private void savePunchAndSync(PunchRecord record) {
        boolean inserted = PunchPersistence.persist(
                requireContext(), record, Constants.ACTION_PUNCH_PUSH);
        if (!inserted) {
            Employee employee = DatabaseHelper.get(requireContext()).getEmployee(record.empId);
            postToActiveView(() -> {
                String statusMessage = buildEmployeeStatusMessage(
                        record.empName,
                        record.empId,
                        "\u6253\u5361\u5931\u8d25",
                        "\u6253\u5361\u8bb0\u5f55\u5df2\u5b58\u5728"
                );
                playFailFeedback(buildPunchFailureSpeech(record.empName, record.empId, "\u6253\u5361\u8bb0\u5f55\u5df2\u5b58\u5728"));
                showResultCard(
                        statusMessage,
                        buildEmployeeResultMessage(record.empName, record.empId, "\u6253\u5361\u5931\u8d25", "\u6253\u5361\u8bb0\u5f55\u5df2\u5b58\u5728"),
                        false,
                        getEmployeeDisplayName(record.empName, record.empId),
                        record.snapImagePath,
                        employee != null ? employee.faceImageUrl : null,
                        record.snapImagePath
                );
            });
            return;
        }
        postToActiveView(() -> showPunchResult(record, false, true));
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String safeString(String value) {
        return value == null ? "" : value;
    }

    private void showMissingBindingConfigFailure(@Nullable PunchSnapshotHelper.Snapshot snapshot,
                                                 String lineCode,
                                                 int teamBindingId) {
        String message = "\u6253\u5361\u914d\u7f6e\u4e0d\u5b8c\u6574\n\u8bf7\u5148\u540c\u6b65\u8bbe\u5907\u914d\u7f6e\u6216\u4fdd\u5b58\u7ebf\u4f53/\u73ed\u7ec4\u7ed1\u5b9a";
        AppLogger.w(TAG, "Punch blocked by missing binding config: lineCode="
                + safeString(lineCode) + ", teamBindingId=" + teamBindingId);
        InteractionLogger.logBusinessFailure(
                InteractionLogger.GROUP_PUNCH,
                "\u6253\u5361\u914d\u7f6e\u7f3a\u5931",
                "line_binding_code=" + safeString(lineCode)
                        + "\nteam_binding=" + teamBindingId
        );
        showTransientFailureResult(message, snapshot != null ? snapshot.path : null);
    }

    
    private void showPunchResult(PunchRecord record, boolean synced) {
        showPunchResult(record, synced, true);
    }

    private void showPunchResult(PunchRecord record, boolean synced, boolean triggerSyncAfterResult) {
        String timeStr = new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                .format(new Date(record.punchTime * 1000));
        Employee employee = DatabaseHelper.get(requireContext()).getEmployee(record.empId);
        String typeStr;
        if (PUNCH_TYPE_FREE.equals(record.punchType)) {
            typeStr = FREE_PUNCH_OPTION_LABEL;
        } else {
            typeStr = Constants.PUNCH_TYPE_SIGN_IN.equals(record.punchType)
                    ? "\u4e0a\u73ed\u6253\u5361"
                    : "\u4e0b\u73ed\u6253\u5361";
        }
        String syncStr = synced
                ? "\u5df2\u540c\u6b65"
                : (isFastPunchModeEnabled() ? "\u5df2\u4fdd\u5b58\uff0c\u540e\u53f0\u540c\u6b65" : "\u5df2\u79bb\u7ebf\u4fdd\u5b58");
        String statusMessage = buildEmployeeStatusMessage(record.empName, record.empId, "\u6253\u5361\u6210\u529f", null);
        String resultMessage = buildEmployeeResultMessage(
                record.empName,
                record.empId,
                "\u6253\u5361\u6210\u529f",
                typeStr + " " + timeStr + "\n" + syncStr
        );
        if (shouldShowSuccessResultCard()) {
            showResultCard(
                    statusMessage,
                    resultMessage,
                    true,
                    getEmployeeDisplayName(record.empName, record.empId),
                    record.snapImagePath,
                    employee != null ? employee.faceImageUrl : null,
                    synced ? record.snapImagePath : null,
                    getSuccessResultDisplayMs()
            );
        } else {
            setStatus(statusMessage);
            clearResultAvatar();
            if (layoutResult != null) {
                layoutResult.setVisibility(View.GONE);
            }
            releaseRecognitionAfter(getSuccessCooldownMs());
        }
        playPunchFeedback(record);
        if (triggerSyncAfterResult) {
            SyncService.triggerSync(requireContext());
        }
    }

    private void renderPunchStatusSnapshot(PunchApplication.PunchStatusSnapshot snapshot) {
        if (!isAdded() || snapshot == null || tvPunchStatusCurrent == null) {
            return;
        }
        tvPunchStatusCurrent.setText(snapshot.currentStatus);
        bindStatusLevelChip(snapshot.currentLevel);
        if (snapshot.shouldExpandHistory && !statusHistoryExpanded) {
            statusHistoryExpanded = true;
            PunchApplication app = PunchApplication.get();
            if (app != null) {
                app.clearPunchStatusAttention();
            }
        }
        renderStatusHistory(snapshot.recentEntries);
        renderEmployeeSyncProgress(snapshot.employeeSyncProgress);
        updateStatusHistoryVisibility();
        showStatusPanel(true);
    }

    private void renderEmployeeSyncProgress(PunchApplication.EmployeeSyncProgress progress) {
        if (layoutEmployeeSyncProgress == null
                || tvEmployeeSyncTitle == null
                || tvEmployeeSyncDetail == null
                || progressEmployeeSync == null) {
            return;
        }
        if (progress == null) {
            layoutEmployeeSyncProgress.setVisibility(View.GONE);
            return;
        }

        layoutEmployeeSyncProgress.setVisibility(View.VISIBLE);
        tvEmployeeSyncTitle.setText(progress.title);
        tvEmployeeSyncDetail.setText(progress.detail);
        if (progress.progressPercent < 0) {
            progressEmployeeSync.setIndeterminate(true);
        } else {
            progressEmployeeSync.setIndeterminate(false);
            progressEmployeeSync.setMax(100);
            progressEmployeeSync.setProgress(Math.max(0, Math.min(100, progress.progressPercent)));
        }
    }

    private void bindStatusLevelChip(int level) {
        int fillColor;
        String label;
        switch (level) {
            case PunchApplication.STATUS_LEVEL_SUCCESS:
                fillColor = ContextCompat.getColor(requireContext(), R.color.green);
                label = "已就绪";
                break;
            case PunchApplication.STATUS_LEVEL_ERROR:
                fillColor = ContextCompat.getColor(requireContext(), R.color.red);
                label = "异常";
                break;
            case PunchApplication.STATUS_LEVEL_INFO:
                fillColor = ContextCompat.getColor(requireContext(), R.color.primary);
                label = "提示";
                break;
            case PunchApplication.STATUS_LEVEL_PROGRESS:
            default:
                fillColor = ContextCompat.getColor(requireContext(), R.color.orange);
                label = "进行中";
                break;
        }
        GradientDrawable background = new GradientDrawable();
        background.setColor(fillColor);
        background.setCornerRadius(dp(12));
        tvPunchStatusLevel.setBackground(background);
        tvPunchStatusLevel.setText(label);
        tvPunchStatusHint.setText(level == PunchApplication.STATUS_LEVEL_ERROR
                ? "存在异常，已展开最近状态"
                : "点击摄像头区域可再次查看状态");
    }

    private void renderStatusHistory(List<PunchApplication.PunchStatusEntry> entries) {
        if (layoutPunchStatusHistory == null) {
            return;
        }
        layoutPunchStatusHistory.removeAllViews();
        if (entries == null || entries.isEmpty()) {
            addStatusHistoryRow("--:--:--", "等待新的状态更新", PunchApplication.STATUS_LEVEL_INFO);
            return;
        }
        for (PunchApplication.PunchStatusEntry entry : entries) {
            String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    .format(new Date(entry.timeMillis));
            addStatusHistoryRow(time, entry.message, entry.level);
        }
    }

    private void addStatusHistoryRow(String time, String message, int level) {
        if (layoutPunchStatusHistory == null) {
            return;
        }
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dpInt(4), 0, dpInt(4));

        TextView timeView = new TextView(requireContext());
        timeView.setText(time);
        timeView.setTextColor(0xFFD7E0EA);
        timeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        LinearLayout.LayoutParams timeParams = new LinearLayout.LayoutParams(dpInt(52), ViewGroup.LayoutParams.WRAP_CONTENT);
        row.addView(timeView, timeParams);

        View dot = new View(requireContext());
        GradientDrawable dotDrawable = new GradientDrawable();
        dotDrawable.setShape(GradientDrawable.OVAL);
        dotDrawable.setColor(resolveStatusLevelColor(level));
        dot.setBackground(dotDrawable);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dpInt(8), dpInt(8));
        dotParams.topMargin = dpInt(4);
        dotParams.rightMargin = dpInt(8);
        row.addView(dot, dotParams);

        TextView messageView = new TextView(requireContext());
        messageView.setText(message);
        messageView.setTextColor(ContextCompat.getColor(requireContext(), R.color.white));
        messageView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        messageView.setLineSpacing(0f, 1.15f);
        LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(messageView, messageParams);

        layoutPunchStatusHistory.addView(row);
    }

    private void setStatusHistoryExpanded(boolean expanded) {
        statusHistoryExpanded = expanded;
        if (expanded) {
            PunchApplication app = PunchApplication.get();
            if (app != null) {
                app.clearPunchStatusAttention();
            }
        }
        updateStatusHistoryVisibility();
        showStatusPanel(true);
    }

    private void updateStatusHistoryVisibility() {
        if (layoutPunchStatusHistory == null || tvPunchStatusToggle == null || tvPunchStatusHint == null) {
            return;
        }
        layoutPunchStatusHistory.setVisibility(statusHistoryExpanded ? View.VISIBLE : View.GONE);
        tvPunchStatusHint.setVisibility(statusHistoryExpanded ? View.VISIBLE : View.GONE);
        tvPunchStatusToggle.setText(statusHistoryExpanded ? "收起" : "详情");
    }

    private void revealStatusPanel() {
        showStatusPanel(false);
    }

    private void showStatusPanel(boolean fromStatusUpdate) {
        if (layoutPunchStatusPanel == null) {
            return;
        }
        uiHandler.removeCallbacks(hideStatusPanelRunnable);
        layoutPunchStatusPanel.animate().cancel();
        if (layoutPunchStatusPanel.getVisibility() != View.VISIBLE) {
            layoutPunchStatusPanel.setVisibility(View.VISIBLE);
            layoutPunchStatusPanel.setAlpha(0f);
            layoutPunchStatusPanel.animate()
                    .alpha(1f)
                    .setDuration(220L)
                    .start();
        } else if (layoutPunchStatusPanel.getAlpha() < 1f) {
            layoutPunchStatusPanel.animate()
                    .alpha(1f)
                    .setDuration(180L)
                    .start();
        }
        if (!statusHistoryExpanded) {
            long delay = fromStatusUpdate ? STATUS_PANEL_AUTO_HIDE_DELAY_MS : STATUS_PANEL_AUTO_HIDE_DELAY_MS + 1200L;
            uiHandler.postDelayed(hideStatusPanelRunnable, delay);
        }
    }

    private void fadeOutStatusPanel() {
        if (layoutPunchStatusPanel == null || statusHistoryExpanded || !isAdded()) {
            return;
        }
        layoutPunchStatusPanel.animate()
                .alpha(0f)
                .setDuration(STATUS_PANEL_FADE_DURATION_MS)
                .withEndAction(() -> {
                    if (layoutPunchStatusPanel != null && !statusHistoryExpanded) {
                        layoutPunchStatusPanel.setVisibility(View.GONE);
                    }
                })
                .start();
    }

    private int resolveStatusLevelColor(int level) {
        switch (level) {
            case PunchApplication.STATUS_LEVEL_SUCCESS:
                return ContextCompat.getColor(requireContext(), R.color.green);
            case PunchApplication.STATUS_LEVEL_ERROR:
                return ContextCompat.getColor(requireContext(), R.color.red);
            case PunchApplication.STATUS_LEVEL_INFO:
                return ContextCompat.getColor(requireContext(), R.color.primary);
            case PunchApplication.STATUS_LEVEL_PROGRESS:
            default:
                return ContextCompat.getColor(requireContext(), R.color.orange);
        }
    }

    private float dp(int value) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                requireContext().getResources().getDisplayMetrics()
        );
    }

    private int dpInt(int value) {
        return Math.round(dp(value));
    }

    
    private void setStatus(String msg) {
        if (isAdded()) tvStatus.setText(msg);
    }

    private void showCameraLoading(String message) {
        if (layoutCameraLoading != null) {
            layoutCameraLoading.setVisibility(View.VISIBLE);
        }
        if (tvCameraLoading != null) {
            tvCameraLoading.setText(message);
        }
    }

    private void hideCameraLoading() {
        if (layoutCameraLoading != null) {
            layoutCameraLoading.setVisibility(View.GONE);
        }
    }

    
    private void applyResultCardStyle(boolean success) {
        if (layoutResult == null) {
            return;
        }
        layoutResult.setBackgroundResource(success
                ? R.drawable.bg_result_card_success
                : R.drawable.bg_result_card_error);
    }

    
    private void setScreenOnLocked(boolean keepScreenOn) {
        Activity activity = getActivity();
        if (activity == null) {
            return;
        }
        Runnable applyFlag = () -> {
            if (getActivity() != activity) {
                return;
            }
            applyScreenOnFlag(activity, keepScreenOn);
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyFlag.run();
        } else {
            activity.runOnUiThread(applyFlag);
        }
    }

    private void applyScreenOnFlag(Activity activity, boolean keepScreenOn) {
        Window window = activity.getWindow();
        if (keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    
    @Override
    public void onResume() {
        super.onResume();
        punchActive = !isHidden();
        setPunchEnabled(PunchCameraPolicy.shouldEnablePunchOnVisibleEntry(
                getResources().getBoolean(R.bool.camera_face_soak_auto_enable)));
        updateScreenAwakeState();
        refreshBindingHeader();
        PunchApplication app = PunchApplication.get();
        if (app != null) {
            renderPunchStatusSnapshot(app.getPunchStatusSnapshot());
        }
        rebuildPunchOptions();
    }

    @Override
    public void onPause() {
        punchActive = false;
        setPunchEnabled(false);
        waitingFirstPreviewFrame = false;
        hideCameraLoading();
        uiHandler.removeCallbacks(hideStatusPanelRunnable);
        setScreenOnLocked(false);
        releaseMediaFeedback();
        if (textToSpeech != null) {
            textToSpeech.stop();
        }
        super.onPause();
        if (previewFullscreen) {
            setPreviewFullscreen(false);
        }
        releaseCamera();
    }

    private void refreshBindingHeader() {
        if (tvLine != null) {
            String lineName = SessionManager.get().getLineName();
            if (lineName == null || lineName.trim().isEmpty()) {
                lineName = SessionManager.get().getLineCode();
            }
            if (lineName == null || lineName.trim().isEmpty()) {
                lineName = "未绑定线体";
            }
            tvLine.setText(lineName.trim());
        }
        if (tvTeam != null) {
            String teamName = SessionManager.get().getTeamBindingName();
            if (teamName == null || teamName.trim().isEmpty()) {
                tvTeam.setVisibility(View.GONE);
            } else {
                tvTeam.setVisibility(View.VISIBLE);
                tvTeam.setText(teamName.trim());
            }
        }
    }

    private void postToActiveView(Runnable action) {
        postToActiveView(viewToken, action);
    }

    private void postToActiveView(int token, Runnable action) {
        uiHandler.post(() -> {
            if (viewGate.isActive(token) && isAdded() && getView() != null) {
                action.run();
            }
        });
    }

    private void postToActiveViewDelayed(Runnable action, long delayMillis) {
        final int token = viewToken;
        uiHandler.postDelayed(() -> {
            if (viewGate.isActive(token) && isAdded() && getView() != null) {
                action.run();
            }
        }, delayMillis);
    }

    @Override
    public void onDestroyView() {
        viewGate.close();
        uiHandler.removeCallbacksAndMessages(null);
        punchActive = false;
        punchEnabled = false;
        clearFaceInteraction();
        setRecognizing(false);
        frameProcessing = false;
        setScreenOnLocked(false);
        waitingFirstPreviewFrame = false;
        hideCameraLoading();
        releaseCamera();
        if (previewFullscreen) {
            setPreviewFullscreen(false);
        }
        releaseAudioFeedback();
        layoutHeader = null;
        layoutCameraContainer = null;
        layoutCameraLoading = null;
        layoutPunchStatusPanel = null;
        textureView = null;
        faceFrameView = null;
        tvLine = null;
        tvTeam = null;
        tvStatus = null;
        tvResult = null;
        btnSwitchCamera = null;
        btnSound = null;
        btnPunchToggle = null;
        btnFullscreen = null;
        tvCameraLoading = null;
        tvResultAvatarFallback = null;
        tvResultAvatarTag = null;
        tvPunchStatusLevel = null;
        tvPunchStatusCurrent = null;
        tvPunchStatusToggle = null;
        tvPunchStatusHint = null;
        layoutResult = null;
        layoutResultAvatar = null;
        layoutPunchStatusHistory = null;
        ivResultAvatar = null;
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void rebuildPunchOptions() {
        if (spinnerShift == null || punchOptionAdapter == null) {
            return;
        }
        Object selectedItem = spinnerShift.getSelectedItem();
        String currentSelection = selectedItem != null ? selectedItem.toString() : null;
        ambiguousPunchSelectionPending = false;
        userChangingPunchSelection = false;
        punchOptions.clear();
        for (String timeRange : SessionManager.get().getCurrentTeamTimeRanges()) {
            if (timeRange == null) {
                continue;
            }
            String range = timeRange.trim();
            if (range.isEmpty()) {
                continue;
            }
            punchOptions.add(range + " \u4e0a\u73ed");
            punchOptions.add(range + " \u4e0b\u73ed");
        }
        punchOptions.add(FREE_PUNCH_OPTION_LABEL);
        punchOptionAdapter.notifyDataSetChanged();
        PunchTimeResolver.WindowValidationResult windowValidation =
                PunchTimeResolver.validatePunchTimeWindows(
                        SessionManager.get().getCurrentTeamTimeRanges(),
                        SessionManager.get().getPunchTimeWindowMinutes(),
                        SessionManager.get().getOvertimeSignOutOptions()
                );
        List<Integer> allowedIndexes = windowValidation.valid
                ? getAllowedPunchOptionIndexesNow()
                : new ArrayList<>();
        int selectedIndex = currentSelection != null ? punchOptions.indexOf(currentSelection) : -1;
        int freeIndex = punchOptions.indexOf(FREE_PUNCH_OPTION_LABEL);
        if (!windowValidation.valid && selectedIndex < 0) {
            selectedIndex = freeIndex;
            setStatus(windowValidation.buildMessage());
        } else if (selectedIndex < 0) {
            if (allowedIndexes.size() == 1) {
                selectedIndex = allowedIndexes.get(0);
            } else if (allowedIndexes.size() > 1) {
                ambiguousPunchSelectionPending = true;
                selectedIndex = freeIndex;
                setStatus("\u5f53\u524d\u65f6\u95f4\u547d\u4e2d\u591a\u4e2a\u6253\u5361\u9879\uff0c\u8bf7\u624b\u52a8\u9009\u62e9");
            }
        }
        if (selectedIndex < 0) {
            selectedIndex = freeIndex;
        }
        if (selectedIndex < 0) {
            selectedIndex = punchOptions.size() > 1 ? 0 : freeIndex;
        }
        if (selectedIndex < 0) {
            selectedIndex = 0;
        }
        spinnerShift.setSelection(selectedIndex);
        updatePunchTypeFromSelection();
    }

    private final class PunchOptionAdapter extends ArrayAdapter<String> {
        PunchOptionAdapter(Context context, List<String> options) {
            super(context, android.R.layout.simple_spinner_dropdown_item, options);
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            View view = super.getView(position, convertView, parent);
            bindOptionView(view, getItem(position), false);
            return view;
        }

        @Override
        public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            View view = super.getDropDownView(position, convertView, parent);
            bindOptionView(view, getItem(position), true);
            return view;
        }

        private void bindOptionView(View view, @Nullable String label, boolean dropdown) {
            if (!(view instanceof TextView)) {
                return;
            }
            TextView textView = (TextView) view;
            textView.setTextColor(resolvePunchOptionTextColor(label));
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, dropdown ? 15 : 14);
            textView.setTypeface(null, dropdown ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            int horizontalPadding = dpInt(dropdown ? 14 : 8);
            int verticalPadding = dpInt(dropdown ? 10 : 4);
            textView.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);
            GradientDrawable background = new GradientDrawable();
            background.setColor(resolvePunchOptionBackgroundColor(label, dropdown));
            background.setCornerRadius(0f);
            textView.setBackground(background);
        }
    }

    private int resolvePunchOptionTextColor(@Nullable String label) {
        if (FREE_PUNCH_OPTION_LABEL.equals(label)) {
            return 0xFFFFFFFF;
        }
        if (label != null && label.endsWith("\u4e0a\u73ed")) {
            return 0xFFFFFFFF;
        }
        if (label != null && label.endsWith("\u4e0b\u73ed")) {
            return 0xFFFFFFFF;
        }
        return 0xFF1D1D1F;
    }

    private int resolvePunchOptionBackgroundColor(@Nullable String label, boolean dropdown) {
        if (FREE_PUNCH_OPTION_LABEL.equals(label)) {
            return dropdown ? 0xFFB71C1C : 0xFFC62828;
        }
        if (label != null && label.endsWith("\u4e0a\u73ed")) {
            return dropdown ? 0xFF1B7A3A : 0xFF2E7D32;
        }
        if (label != null && label.endsWith("\u4e0b\u73ed")) {
            return dropdown ? 0xFF0B4F8C : 0xFF0D5FA8;
        }
        return dropdown ? 0xFFF6F8FB : 0xFFEFF3F7;
    }
}

package com.example.flashswitch;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String TAG = "FlashSwitch";
    private static final int CAMERA_PERMISSION_REQUEST = 4301;

    private static final int ZINC_950 = Color.rgb(9, 9, 11);
    private static final int ZINC_900 = Color.rgb(24, 24, 27);
    private static final int ZINC_800 = Color.rgb(39, 39, 42);
    private static final int ZINC_700 = Color.rgb(63, 63, 70);
    private static final int ZINC_500 = Color.rgb(113, 113, 122);
    private static final int ZINC_200 = Color.rgb(228, 228, 231);
    private static final int ZINC_100 = Color.rgb(244, 244, 245);
    private static final int BULB_YELLOW = Color.rgb(250, 204, 21);
    private static final int BULB_AMBER = Color.rgb(245, 158, 11);

    private CameraManager cameraManager;
    private Handler mainHandler;
    private Runnable clockRunnable;
    private String torchCameraId;
    private boolean hasFlash;
    private boolean isTorchOn;
    private boolean pendingToggleAfterPermission;
    private boolean torchCallbackRegistered;
    private int maxTorchStrengthLevel = 1;
    private int currentTorchStrengthLevel = 1;
    private String statusOverride;
    private String detailOverride;

    private LinearLayout rootLayout;
    private View statusBarView;
    private LinearLayout appBarView;
    private TextView timeLabel;
    private TextView appTitleLabel;
    private TextView appLeadingLabel;
    private TextView appTrailingLabel;
    private TextView statusIconsLabel;
    private View punchHoleView;
    private View gesturePillView;
    private FlashToggleView flashToggleView;
    private LinearLayout heroCard;
    private LinearLayout statusCard;
    private LinearLayout infoCard;
    private TextView statusLabel;
    private TextView detailLabel;
    private TextView actionLabel;
    private TextView brightnessTitleLabel;
    private LinearLayout brightnessChipsGroup;
    private TextView brightnessLowChip;
    private TextView brightnessMidChip;
    private TextView brightnessHighChip;
    private TextView permissionChip;
    private TextView capabilityLabel;
    private TextView keepAwakeLabel;
    private TextView feedbackLabel;

    private final CameraManager.TorchCallback torchCallback = new CameraManager.TorchCallback() {
        @Override
        public void onTorchModeChanged(String cameraId, boolean enabled) {
            if (cameraId.equals(torchCameraId)) {
                isTorchOn = enabled;
                if (enabled) {
                    clearStatusOverride();
                }
                refreshUi();
            }
        }

        @Override
        public void onTorchModeUnavailable(String cameraId) {
            if (cameraId.equals(torchCameraId)) {
                isTorchOn = false;
                currentTorchStrengthLevel = 1;
                showStatus("플래시를 잠시 사용할 수 없어요", "다른 앱이 카메라를 사용 중일 수 있어요.");
                refreshUi();
            }
        }

        @Override
        public void onTorchStrengthLevelChanged(String cameraId, int newStrengthLevel) {
            if (cameraId.equals(torchCameraId)) {
                currentTorchStrengthLevel = Math.max(1, newStrengthLevel);
                refreshUi();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mainHandler = new Handler(Looper.getMainLooper());
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        configureSystemBars();
        buildUi();
        if (cameraManager == null) {
            hasFlash = false;
            showStatus("카메라 서비스를 찾을 수 없어요", "이 기기에서는 플래시 제어를 사용할 수 없습니다.");
            refreshUi();
            return;
        }
        findTorchCamera();
        try {
            cameraManager.registerTorchCallback(torchCallback, mainHandler);
            torchCallbackRegistered = true;
        } catch (SecurityException exception) {
            Log.w(TAG, "Torch callback registration needs camera permission", exception);
            showStatus("카메라 권한이 필요해요", "권한을 허용하면 플래시 상태를 확인할 수 있어요.");
        }
        refreshUi();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mainHandler != null && clockRunnable != null) {
            mainHandler.removeCallbacks(clockRunnable);
        }
        if (isTorchOn && torchCameraId != null) {
            setTorch(false);
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (cameraManager != null && torchCallbackRegistered) {
            cameraManager.unregisterTorchCallback(torchCallback);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != CAMERA_PERMISSION_REQUEST) {
            return;
        }

        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted && pendingToggleAfterPermission) {
            pendingToggleAfterPermission = false;
            ensureTorchCallbackRegistered();
            clearStatusOverride();
            setTorch(!isTorchOn);
            return;
        }

        pendingToggleAfterPermission = false;
        if (!granted) {
            if (!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                showStatus("권한이 차단됐어요", "설정 > 앱 > Flash Switch에서 카메라 권한을 허용해 주세요.");
            } else {
                showStatus("권한이 필요해요", "플래시를 켜려면 카메라 권한을 허용해 주세요.");
            }
            performErrorHaptic();
        }
        refreshUi();
    }

    private void configureSystemBars() {
        applySystemBars(false);
    }

    private void buildUi() {
        rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(ZINC_950);
        rootLayout.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        rootLayout.addView(buildStatusBar());
        rootLayout.addView(buildAppBar());

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(24), dp(18), dp(24), dp(10));
        rootLayout.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        content.addView(buildHeroCard());
        content.addView(buildStatusCard());
        content.addView(buildInfoCard());

        View spacer = new View(this);
        content.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));
        content.addView(buildGestureBar());

        setContentView(rootLayout);
        startClock();
    }

    private View buildStatusBar() {
        FrameLayout bar = new FrameLayout(this);
        statusBarView = bar;
        bar.setPadding(dp(22), 0, dp(20), 0);
        bar.setBackgroundColor(ZINC_950);

        timeLabel = text("9:30", 14, ZINC_100, Typeface.BOLD);
        timeLabel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams timeParams = new FrameLayout.LayoutParams(
                dp(128),
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.START | Gravity.CENTER_VERTICAL
        );
        bar.addView(timeLabel, timeParams);

        punchHoleView = new View(this);
        punchHoleView.setBackground(oval(Color.argb(205, 39, 39, 42)));
        punchHoleView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams holeParams = new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER);
        bar.addView(punchHoleView, holeParams);

        statusIconsLabel = text("◢  ▰", 13, ZINC_100, Typeface.BOLD);
        statusIconsLabel.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        statusIconsLabel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(
                dp(96),
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END | Gravity.CENTER_VERTICAL
        );
        bar.addView(statusIconsLabel, iconParams);

        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(40)
        ));
        return bar;
    }

    private View buildAppBar() {
        appBarView = new LinearLayout(this);
        appBarView.setOrientation(LinearLayout.HORIZONTAL);
        appBarView.setGravity(Gravity.CENTER_VERTICAL);
        appBarView.setPadding(dp(12), dp(4), dp(12), 0);
        appBarView.setBackgroundColor(ZINC_950);

        appLeadingLabel = appBarIcon("‹");
        appLeadingLabel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        appBarView.addView(appLeadingLabel);

        appTitleLabel = text("손전등", 22, ZINC_100, Typeface.BOLD);
        appTitleLabel.setGravity(Gravity.CENTER);
        appBarView.addView(appTitleLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        appTrailingLabel = appBarIcon("⋯");
        appTrailingLabel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        appBarView.addView(appTrailingLabel);
        appBarView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(60)
        ));
        return appBarView;
    }

    private TextView appBarIcon(String icon) {
        TextView label = text(icon, 24, ZINC_100, Typeface.NORMAL);
        label.setGravity(Gravity.CENTER);
        label.setBackground(oval(Color.argb(18, 255, 255, 255)));
        label.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        return label;
    }

    private View buildHeroCard() {
        heroCard = new LinearLayout(this);
        heroCard.setOrientation(LinearLayout.VERTICAL);
        heroCard.setGravity(Gravity.CENTER_HORIZONTAL);
        heroCard.setPadding(0, dp(8), 0, dp(8));
        heroCard.setBackgroundColor(Color.TRANSPARENT);
        heroCard.setClickable(false);
        heroCard.setFocusable(false);

        flashToggleView = new FlashToggleView(this);
        flashToggleView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(dp(280), dp(280));
        toggleParams.gravity = Gravity.CENTER_HORIZONTAL;
        toggleParams.topMargin = dp(18);
        heroCard.addView(flashToggleView, toggleParams);

        statusLabel = text("플래시 준비 중", 30, ZINC_100, Typeface.BOLD);
        statusLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        statusParams.topMargin = dp(10);
        heroCard.addView(statusLabel, statusParams);

        detailLabel = text("카메라 플래시를 확인하고 있어요.", 15, ZINC_500, Typeface.NORMAL);
        detailLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        detailParams.topMargin = dp(8);
        heroCard.addView(detailLabel, detailParams);

        actionLabel = text("⏻", 34, ZINC_950, Typeface.BOLD);
        actionLabel.setGravity(Gravity.CENTER);
        actionLabel.setClickable(true);
        actionLabel.setFocusable(true);
        actionLabel.setOnClickListener(view -> onToggleRequested());
        actionLabel.setBackground(oval(Color.WHITE));
        actionLabel.setElevation(dp(12));
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(dp(92), dp(92));
        actionParams.gravity = Gravity.CENTER_HORIZONTAL;
        actionParams.topMargin = dp(34);
        heroCard.addView(actionLabel, actionParams);

        heroCard.addView(buildBrightnessSelector());

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cardParams.bottomMargin = dp(18);
        heroCard.setLayoutParams(cardParams);
        return heroCard;
    }

    private View buildStatusCard() {
        statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        statusCard.setBackground(roundRect(Color.argb(18, 255, 255, 255), dp(22), Color.argb(20, 255, 255, 255)));
        statusCard.setElevation(dp(2));

        statusCard.addView(tweakSectionLabel("DEVICE"));

        permissionChip = text("권한 확인 중", 14, ZINC_100, Typeface.BOLD);
        LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        chipParams.topMargin = dp(4);
        statusCard.addView(permissionChip, chipParams);

        capabilityLabel = text("토치 기능을 확인하고 있어요", 12, ZINC_500, Typeface.NORMAL);
        LinearLayout.LayoutParams capabilityParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        capabilityParams.topMargin = dp(4);
        statusCard.addView(capabilityLabel, capabilityParams);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(12);
        statusCard.setLayoutParams(params);
        return statusCard;
    }

    private View buildInfoCard() {
        infoCard = new LinearLayout(this);
        infoCard.setOrientation(LinearLayout.VERTICAL);
        infoCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        infoCard.setBackground(roundRect(Color.argb(18, 255, 255, 255), dp(22), Color.argb(20, 255, 255, 255)));
        infoCard.setElevation(dp(2));

        infoCard.addView(tweakSectionLabel("SAFETY"));

        keepAwakeLabel = text("화면 유지 · 꺼짐", 12, ZINC_500, Typeface.NORMAL);
        feedbackLabel = text("햅틱 피드백 · 준비됨", 12, ZINC_500, Typeface.NORMAL);

        infoCard.addView(featureRow("◐", "토치가 켜져 있을 때 화면을 계속 밝게 유지", keepAwakeLabel));
        infoCard.addView(featureRow("•", "탭과 오류 상태를 촉감으로 알려줌", feedbackLabel));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        infoCard.setLayoutParams(params);
        return infoCard;
    }

    private View buildBrightnessSelector() {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setPadding(0, dp(28), 0, dp(2));

        brightnessTitleLabel = text("밝기", 13, ZINC_500, Typeface.BOLD);
        brightnessTitleLabel.setGravity(Gravity.CENTER);
        group.addView(brightnessTitleLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(24)
        ));

        brightnessChipsGroup = new LinearLayout(this);
        brightnessChipsGroup.setOrientation(LinearLayout.HORIZONTAL);
        brightnessChipsGroup.setPadding(dp(4), dp(4), dp(4), dp(4));
        brightnessChipsGroup.setBackground(roundRect(Color.argb(18, 255, 255, 255), dp(999), Color.argb(18, 255, 255, 255)));

        brightnessLowChip = brightnessChip("약", 0);
        brightnessMidChip = brightnessChip("중", 1);
        brightnessHighChip = brightnessChip("강", 2);
        brightnessChipsGroup.addView(brightnessLowChip, new LinearLayout.LayoutParams(0, dp(36), 1f));
        brightnessChipsGroup.addView(brightnessMidChip, new LinearLayout.LayoutParams(0, dp(36), 1f));
        brightnessChipsGroup.addView(brightnessHighChip, new LinearLayout.LayoutParams(0, dp(36), 1f));

        LinearLayout.LayoutParams chipGroupParams = new LinearLayout.LayoutParams(dp(190), ViewGroup.LayoutParams.WRAP_CONTENT);
        chipGroupParams.gravity = Gravity.CENTER_HORIZONTAL;
        chipGroupParams.topMargin = dp(6);
        group.addView(brightnessChipsGroup, chipGroupParams);
        return group;
    }

    private TextView brightnessChip(String label, int preset) {
        TextView chip = text(label, 12, ZINC_100, Typeface.BOLD);
        chip.setGravity(Gravity.CENTER);
        chip.setClickable(true);
        chip.setFocusable(true);
        chip.setOnClickListener(view -> onBrightnessPresetRequested(preset));
        return chip;
    }

    private View featureRow(String icon, String descriptionText, TextView valueLabel) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, 0);

        TextView leading = text(icon, 15, ZINC_950, Typeface.BOLD);
        leading.setGravity(Gravity.CENTER);
        leading.setBackground(oval(BULB_YELLOW));
        row.addView(leading, new LinearLayout.LayoutParams(dp(28), dp(28)));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setPadding(dp(12), 0, 0, 0);
        TextView description = text(descriptionText, 13, ZINC_100, Typeface.NORMAL);
        copy.addView(description);
        copy.addView(valueLabel);
        row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private TextView tweakSectionLabel(String value) {
        TextView label = text(value, 10, ZINC_500, Typeface.BOLD);
        label.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        label.setLetterSpacing(0.06f);
        label.setPadding(0, dp(10), 0, 0);
        return label;
    }

    private View buildGestureBar() {
        FrameLayout nav = new FrameLayout(this);
        gesturePillView = new View(this);
        gesturePillView.setAlpha(0.4f);
        gesturePillView.setBackground(roundRect(ZINC_100, dp(2), 0));
        gesturePillView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        nav.addView(gesturePillView, new FrameLayout.LayoutParams(dp(108), dp(4), Gravity.CENTER));
        nav.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(24)
        ));
        return nav;
    }

    private void findTorchCamera() {
        torchCameraId = null;
        hasFlash = false;
        maxTorchStrengthLevel = 1;
        currentTorchStrengthLevel = 1;
        clearStatusOverride();
        try {
            String firstFlashCameraId = null;
            int firstFlashStrength = 1;
            for (String cameraId : cameraManager.getCameraIdList()) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                Boolean flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (!Boolean.TRUE.equals(flashAvailable)) {
                    continue;
                }

                int strengthLevel = readMaxTorchStrength(characteristics);

                hasFlash = true;
                if (firstFlashCameraId == null) {
                    firstFlashCameraId = cameraId;
                    firstFlashStrength = strengthLevel;
                }

                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    torchCameraId = cameraId;
                    maxTorchStrengthLevel = strengthLevel;
                    currentTorchStrengthLevel = Math.max(1, strengthLevel);
                    return;
                }
            }
            torchCameraId = firstFlashCameraId;
            maxTorchStrengthLevel = firstFlashStrength;
            currentTorchStrengthLevel = Math.max(1, firstFlashStrength);
        } catch (CameraAccessException | RuntimeException exception) {
            Log.w(TAG, "Unable to inspect cameras", exception);
            hasFlash = false;
            torchCameraId = null;
            showStatus("카메라를 확인할 수 없어요", "기기의 카메라 서비스가 응답하지 않아요.");
        }
    }

    private void onToggleRequested() {
        if (!hasFlash || torchCameraId == null) {
            showStatus("플래시가 없어요", "이 기기에서는 후면 플래시를 찾지 못했어요.");
            refreshUi();
            performErrorHaptic();
            return;
        }

        if (!hasCameraPermission()) {
            pendingToggleAfterPermission = true;
            if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                showStatus("카메라 권한이 필요해요", "플래시 제어에는 카메라 권한이 필요합니다. 사진은 촬영하지 않아요.");
            } else {
                showStatus("권한을 요청할게요", "허용을 누르면 바로 플래시를 켤 수 있어요.");
            }
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
            refreshUi();
            return;
        }

        setTorch(!isTorchOn);
    }

    private void setTorch(boolean enabled) {
        if (cameraManager == null || torchCameraId == null) {
            showStatus("플래시가 없어요", "켜고 끌 수 있는 카메라 플래시가 없습니다.");
            refreshUi();
            performErrorHaptic();
            return;
        }

        try {
            if (enabled && supportsStrengthControl()) {
                int level = clampTorchStrength(currentTorchStrengthLevel <= 1 ? maxTorchStrengthLevel : currentTorchStrengthLevel);
                cameraManager.turnOnTorchWithStrengthLevel(torchCameraId, level);
                currentTorchStrengthLevel = level;
            } else {
                cameraManager.setTorchMode(torchCameraId, enabled);
            }
            isTorchOn = enabled;
            clearStatusOverride();
            refreshUi();
            performToggleHaptic();
        } catch (SecurityException exception) {
            Log.w(TAG, "Camera permission missing while toggling torch", exception);
            showStatus("권한이 필요해요", "설정에서 카메라 권한을 허용한 뒤 다시 시도해 주세요.");
            refreshUi();
            performErrorHaptic();
        } catch (CameraAccessException | IllegalArgumentException exception) {
            Log.w(TAG, "Unable to toggle torch", exception);
            showStatus("플래시 전환 실패", describeCameraError(exception));
            refreshUi();
            performErrorHaptic();
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unexpected torch failure", exception);
            showStatus("플래시 전환 실패", "기기 카메라 서비스가 요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.");
            refreshUi();
            performErrorHaptic();
        }
    }

    private void onBrightnessPresetRequested(int preset) {
        if (!hasFlash || torchCameraId == null) {
            performErrorHaptic();
            showStatus("플래시가 없어요", "이 기기에서는 밝기를 조절할 토치를 찾지 못했어요.");
            refreshUi();
            return;
        }

        if (!hasCameraPermission()) {
            pendingToggleAfterPermission = true;
            currentTorchStrengthLevel = strengthForPreset(preset);
            showStatus("권한을 요청할게요", "허용하면 선택한 밝기로 바로 켜집니다.");
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
            refreshUi();
            return;
        }

        if (!supportsStrengthControl()) {
            currentTorchStrengthLevel = 1;
            boolean wasOn = isTorchOn;
            if (!wasOn) {
                setTorch(true);
            }
            showStatus("자동 밝기", "이 기기는 별도 밝기 단계 없이 기본 토치 모드로 켜집니다.");
            refreshUi();
            if (wasOn) {
                performToggleHaptic();
            }
            return;
        }

        currentTorchStrengthLevel = strengthForPreset(preset);
        try {
            cameraManager.turnOnTorchWithStrengthLevel(torchCameraId, currentTorchStrengthLevel);
            isTorchOn = true;
            clearStatusOverride();
            showStatus(brightnessPresetLabel(preset) + " 밝기", "밝기 선택은 약 · 중 · 강 세 단계로 간단히 바꿀 수 있어요.");
            refreshUi();
            performToggleHaptic();
        } catch (SecurityException exception) {
            Log.w(TAG, "Camera permission missing while changing torch strength", exception);
            showStatus("권한이 필요해요", "밝기를 조절하려면 카메라 권한을 허용해 주세요.");
            refreshUi();
            performErrorHaptic();
        } catch (CameraAccessException | RuntimeException exception) {
            Log.w(TAG, "Unable to change torch strength", exception);
            showStatus("밝기 조절 실패", describeCameraError(exception));
            refreshUi();
            performErrorHaptic();
        }
    }

    private void refreshUi() {
        boolean permissionNeeded = hasFlash && !hasCameraPermission();
        String defaultStatus;
        String defaultDetail;
        if (!hasFlash || torchCameraId == null) {
            defaultStatus = "사용 불가";
            defaultDetail = "이 기기에서는 후면 플래시를 찾지 못했어요.";
            actionLabel.setText("!");
            permissionChip.setText("플래시 하드웨어 없음");
            capabilityLabel.setText("사용 가능한 카메라 플래시를 찾지 못했습니다.");
        } else if (permissionNeeded) {
            defaultStatus = "권한 필요";
            defaultDetail = "권한을 허용하면 바로 플래시를 켤 수 있어요.";
            actionLabel.setText("⏻");
            permissionChip.setText("카메라 권한 필요");
            capabilityLabel.setText(torchCapabilityText());
        } else if (isTorchOn) {
            defaultStatus = "켜짐";
            defaultDetail = "주변을 밝히는 중이에요. 탭하면 꺼집니다.";
            actionLabel.setText("⏻");
            permissionChip.setText("권한 허용됨 · 토치 켜짐");
            capabilityLabel.setText(torchCapabilityText());
        } else {
            defaultStatus = "꺼짐";
            defaultDetail = "전원 버튼을 탭하면 손전등이 켜집니다.";
            actionLabel.setText("⏻");
            permissionChip.setText("권한 허용됨 · 대기 중");
            capabilityLabel.setText(torchCapabilityText());
        }

        setStatusText(
                statusOverride != null ? statusOverride : defaultStatus,
                detailOverride != null ? detailOverride : defaultDetail
        );

        applyVisualState(isTorchOn, permissionNeeded);
        flashToggleView.setFlashState(isTorchOn, hasFlash && torchCameraId != null, permissionNeeded);
        updateBrightnessChips();
        updateKeepScreenOn();
        updateAccessibility();
        keepAwakeLabel.setText(isTorchOn ? "화면 유지 · 켜짐" : "화면 유지 · 꺼짐");
        feedbackLabel.setText("햅틱 피드백 · " + (isTorchOn ? "켜짐 확인" : "탭 준비"));
    }

    private void applyVisualState(boolean on, boolean permissionNeeded) {
        int background = on ? Color.WHITE : ZINC_950;
        int primaryText = on ? ZINC_800 : ZINC_100;
        int mutedText = on ? ZINC_500 : ZINC_500;
        int cardBackground = on ? Color.argb(18, 0, 0, 0) : Color.argb(18, 255, 255, 255);
        int cardStroke = on ? Color.argb(20, 0, 0, 0) : Color.argb(20, 255, 255, 255);

        rootLayout.setBackgroundColor(background);
        statusBarView.setBackgroundColor(background);
        appBarView.setBackgroundColor(background);
        heroCard.setBackgroundColor(Color.TRANSPARENT);
        statusCard.setBackground(roundRect(cardBackground, dp(22), cardStroke));
        infoCard.setBackground(roundRect(cardBackground, dp(22), cardStroke));
        applySystemBars(on);

        timeLabel.setTextColor(primaryText);
        statusIconsLabel.setTextColor(primaryText);
        appTitleLabel.setTextColor(primaryText);
        appLeadingLabel.setTextColor(primaryText);
        appTrailingLabel.setTextColor(primaryText);
        appLeadingLabel.setBackground(oval(on ? Color.argb(8, 0, 0, 0) : Color.argb(18, 255, 255, 255)));
        appTrailingLabel.setBackground(oval(on ? Color.argb(8, 0, 0, 0) : Color.argb(18, 255, 255, 255)));
        punchHoleView.setBackground(oval(on ? ZINC_200 : Color.argb(205, 39, 39, 42)));
        gesturePillView.setBackground(roundRect(on ? ZINC_800 : ZINC_100, dp(2), 0));

        statusLabel.setTextColor(primaryText);
        detailLabel.setTextColor(mutedText);
        permissionChip.setTextColor(primaryText);
        capabilityLabel.setTextColor(mutedText);
        keepAwakeLabel.setTextColor(mutedText);
        feedbackLabel.setTextColor(mutedText);
        brightnessTitleLabel.setTextColor(mutedText);
        brightnessChipsGroup.setBackground(roundRect(cardBackground, dp(999), cardStroke));

        if (!hasFlash || torchCameraId == null || permissionNeeded) {
            actionLabel.setTextColor(on ? ZINC_800 : ZINC_950);
            actionLabel.setBackground(oval(Color.argb(230, 244, 244, 245)));
        } else if (on) {
            actionLabel.setTextColor(BULB_AMBER);
            actionLabel.setBackground(oval(ZINC_900));
        } else {
            actionLabel.setTextColor(ZINC_950);
            actionLabel.setBackground(oval(Color.WHITE));
        }
    }

    private void applySystemBars(boolean lightSurface) {
        Window window = getWindow();
        int color = lightSurface ? Color.WHITE : ZINC_950;
        window.setStatusBarColor(color);
        window.setNavigationBarColor(color);

        int flags = lightSurface ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && lightSurface) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    private void startClock() {
        updateClock();
        clockRunnable = new Runnable() {
            @Override
            public void run() {
                updateClock();
                mainHandler.postDelayed(this, 60_000L);
            }
        };
        mainHandler.postDelayed(clockRunnable, 60_000L);
    }

    private void updateClock() {
        if (timeLabel == null) {
            return;
        }
        String time = new SimpleDateFormat("H:mm", Locale.KOREA).format(new Date());
        timeLabel.setText(time);
    }

    private void showStatus(String status, String detail) {
        statusOverride = status;
        detailOverride = detail;
        setStatusText(status, detail);
    }

    private void setStatusText(String status, String detail) {
        if (statusLabel != null) {
            statusLabel.setText(status);
        }
        if (detailLabel != null) {
            detailLabel.setText(detail);
        }
    }

    private void clearStatusOverride() {
        statusOverride = null;
        detailOverride = null;
    }

    private int readMaxTorchStrength(CameraCharacteristics characteristics) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return 1;
        }
        Integer level = characteristics.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL);
        return level == null ? 1 : Math.max(1, level);
    }

    private boolean supportsStrengthControl() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && maxTorchStrengthLevel > 1;
    }

    private int clampTorchStrength(int requestedLevel) {
        return Math.max(1, Math.min(maxTorchStrengthLevel, requestedLevel));
    }

    private int strengthForPreset(int preset) {
        if (!supportsStrengthControl()) {
            return 1;
        }
        if (preset <= 0) {
            return 1;
        }
        if (preset == 1) {
            return clampTorchStrength(Math.max(1, Math.round(maxTorchStrengthLevel * 0.55f)));
        }
        return maxTorchStrengthLevel;
    }

    private int presetForCurrentStrength() {
        if (!supportsStrengthControl()) {
            return -1;
        }

        int low = strengthForPreset(0);
        int mid = strengthForPreset(1);
        int high = strengthForPreset(2);
        int lowDistance = Math.abs(currentTorchStrengthLevel - low);
        int midDistance = Math.abs(currentTorchStrengthLevel - mid);
        int highDistance = Math.abs(currentTorchStrengthLevel - high);
        if (lowDistance <= midDistance && lowDistance <= highDistance) {
            return 0;
        }
        if (midDistance <= highDistance) {
            return 1;
        }
        return 2;
    }

    private String brightnessPresetLabel(int preset) {
        if (preset <= 0) {
            return "약";
        }
        if (preset == 1) {
            return "중";
        }
        return "강";
    }

    private String torchCapabilityText() {
        if (supportsStrengthControl()) {
            return "밝기 조절 지원 · 약/중/강 중 선택";
        }
        return "기본 토치 제어 지원 · 안정적인 켜기/끄기 모드";
    }

    private void updateBrightnessChips() {
        if (brightnessLowChip == null || brightnessMidChip == null || brightnessHighChip == null) {
            return;
        }

        int selectedPreset = presetForCurrentStrength();
        boolean enabled = hasFlash && torchCameraId != null;
        styleBrightnessChip(brightnessLowChip, selectedPreset == 0, enabled);
        styleBrightnessChip(brightnessMidChip, selectedPreset == 1, enabled);
        styleBrightnessChip(brightnessHighChip, selectedPreset == 2, enabled);
    }

    private void styleBrightnessChip(TextView chip, boolean selected, boolean enabled) {
        int background;
        if (selected && enabled) {
            background = isTorchOn ? ZINC_900 : Color.WHITE;
        } else {
            background = Color.TRANSPARENT;
        }
        int textColor;
        if (!enabled) {
            textColor = Color.argb(120, 113, 113, 122);
        } else if (selected && isTorchOn) {
            textColor = BULB_YELLOW;
        } else if (selected) {
            textColor = ZINC_950;
        } else {
            textColor = isTorchOn ? ZINC_700 : ZINC_100;
        }
        chip.setTextColor(textColor);
        chip.setAlpha(enabled ? 1f : 0.55f);
        chip.setBackground(roundRect(background, dp(999), selected && enabled ? Color.argb(34, 250, 204, 21) : 0));
        chip.setEnabled(enabled);
    }

    private void ensureTorchCallbackRegistered() {
        if (cameraManager == null || torchCallbackRegistered) {
            return;
        }

        try {
            cameraManager.registerTorchCallback(torchCallback, mainHandler);
            torchCallbackRegistered = true;
        } catch (SecurityException exception) {
            Log.w(TAG, "Torch callback registration needs camera permission", exception);
        }
    }

    private String describeCameraError(Exception exception) {
        if (exception instanceof CameraAccessException) {
            CameraAccessException cameraException = (CameraAccessException) exception;
            switch (cameraException.getReason()) {
                case CameraAccessException.CAMERA_IN_USE:
                    return "다른 앱이 카메라를 사용 중이에요. 닫은 뒤 다시 시도해 주세요.";
                case CameraAccessException.MAX_CAMERAS_IN_USE:
                    return "동시에 열린 카메라가 너무 많아요. 잠시 후 다시 시도해 주세요.";
                case CameraAccessException.CAMERA_DISABLED:
                    return "기기 정책에서 카메라가 비활성화되어 플래시를 켤 수 없어요.";
                case CameraAccessException.CAMERA_DISCONNECTED:
                    return "카메라 연결이 끊겼어요. 화면을 잠시 껐다 켠 뒤 다시 시도해 주세요.";
                default:
                    return "카메라 서비스가 잠시 응답하지 않아요. 다시 시도해 주세요.";
            }
        }
        return "이 기기의 토치가 현재 요청을 받을 수 없어요. 잠시 후 다시 시도해 주세요.";
    }

    private void updateKeepScreenOn() {
        if (isTorchOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void updateAccessibility() {
        String state = statusLabel.getText().toString();
        String detail = detailLabel.getText().toString();
        String action = actionAccessibilityLabel();
        heroCard.setContentDescription(state + ". " + detail);
        actionLabel.setContentDescription(action);
        permissionChip.setContentDescription("상태: " + permissionChip.getText());
    }

    private String actionAccessibilityLabel() {
        if (!hasFlash || torchCameraId == null) {
            return "플래시 사용 불가";
        }
        if (!hasCameraPermission()) {
            return "카메라 권한 요청";
        }
        return isTorchOn ? "손전등 끄기" : "손전등 켜기";
    }

    private void performToggleHaptic() {
        View decorView = getWindow().getDecorView();
        decorView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    private void performErrorHaptic() {
        View decorView = getWindow().getDecorView();
        decorView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
    }

    private boolean hasCameraPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private TextView text(String value, int sp, int color, int style) {
        TextView textView = new TextView(this);
        textView.setText(value);
        textView.setTextSize(sp);
        textView.setTextColor(color);
        textView.setGravity(Gravity.CENTER_VERTICAL);
        textView.setIncludeFontPadding(true);
        textView.setTypeface(Typeface.create(Typeface.SANS_SERIF, style));
        return textView;
    }

    private GradientDrawable oval(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        return drawable;
    }

    private GradientDrawable roundRect(int color, float radius, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(radius);
        drawable.setColor(color);
        if (strokeColor != 0) {
            drawable.setStroke(dp(1), strokeColor);
        }
        return drawable;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private class FlashToggleView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        private boolean on;
        private boolean available;
        private boolean permissionNeeded;
        private float progress;
        private ValueAnimator animator;

        FlashToggleView(Context context) {
            super(context);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            setClickable(false);
        }

        void setFlashState(boolean nextOn, boolean nextAvailable, boolean nextPermissionNeeded) {
            boolean changed = on != nextOn || available != nextAvailable || permissionNeeded != nextPermissionNeeded;
            on = nextOn;
            available = nextAvailable;
            permissionNeeded = nextPermissionNeeded;
            animateTo(on ? 1f : 0f, changed);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth();
            float height = getHeight();
            float centerX = width / 2f;
            float centerY = height * 0.5f - dp(10) * progress;
            float radius = Math.min(width, height) * (0.31f + 0.02f * progress);
            float active = available && !permissionNeeded ? progress : 0f;

            drawGlow(canvas, centerX, centerY, radius, active);
            drawLightRays(canvas, centerX, centerY, radius, active);
            drawBulb(canvas, centerX, centerY, radius, active);
        }

        private void drawGlow(Canvas canvas, float centerX, float centerY, float radius, float active) {
            if (active <= 0f) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2));
                paint.setColor(available ? Color.argb(34, 244, 244, 245) : Color.argb(24, 113, 113, 122));
                canvas.drawCircle(centerX, centerY, radius * 1.06f, paint);
                return;
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(Math.round(72 * active), 250, 204, 21));
            paint.setShadowLayer(dp(42), 0f, 0f, Color.argb(Math.round(150 * active), 250, 204, 21));
            canvas.drawCircle(centerX, centerY, radius * (1.34f + 0.07f * active), paint);
            paint.clearShadowLayer();

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(Math.round(44 * active), 245, 158, 11));
            canvas.drawCircle(centerX, centerY, radius * 1.02f, paint);
        }

        private void drawBulb(Canvas canvas, float centerX, float centerY, float radius, float active) {
            float unit = radius / 100f;
            float globeRadius = 44f * unit;
            float globeCenterY = centerY - 16f * unit;
            int bulbColor = available ? blend(ZINC_700, BULB_YELLOW, active) : Color.argb(120, 113, 113, 122);
            int innerColor = available ? blend(Color.argb(46, 244, 244, 245), Color.argb(180, 254, 240, 138), active) : Color.argb(28, 113, 113, 122);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(innerColor);
            canvas.drawCircle(centerX, globeCenterY, globeRadius * 0.86f, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(9f * unit);
            paint.setColor(bulbColor);
            paint.setShadowLayer(active > 0f ? dp(12) : 0f, 0f, 0f, Color.argb(Math.round(130 * active), 250, 204, 21));
            canvas.drawCircle(centerX, globeCenterY, globeRadius, paint);
            paint.clearShadowLayer();

            paint.setStrokeWidth(7f * unit);
            path.reset();
            path.moveTo(centerX - 19f * unit, centerY + 18f * unit);
            path.cubicTo(centerX - 10f * unit, centerY + 28f * unit, centerX + 10f * unit, centerY + 28f * unit, centerX + 19f * unit, centerY + 18f * unit);
            canvas.drawPath(path, paint);

            paint.setStrokeWidth(8f * unit);
            canvas.drawLine(centerX - 23f * unit, centerY + 39f * unit, centerX + 23f * unit, centerY + 39f * unit, paint);
            canvas.drawLine(centerX - 18f * unit, centerY + 55f * unit, centerX + 18f * unit, centerY + 55f * unit, paint);
            canvas.drawLine(centerX - 10f * unit, centerY + 70f * unit, centerX + 10f * unit, centerY + 70f * unit, paint);

            paint.setStrokeWidth(5f * unit);
            paint.setColor(blend(Color.argb(120, 212, 212, 216), Color.rgb(146, 64, 14), active));
            canvas.drawLine(centerX - 15f * unit, centerY - 14f * unit, centerX - 1f * unit, centerY + 8f * unit, paint);
            canvas.drawLine(centerX + 15f * unit, centerY - 14f * unit, centerX + 1f * unit, centerY + 8f * unit, paint);

            if (active < 0.5f || !available || permissionNeeded) {
                paint.setStrokeWidth(8f * unit);
                paint.setColor(permissionNeeded ? BULB_AMBER : Color.argb(210, 113, 113, 122));
                canvas.drawLine(centerX - 58f * unit, centerY + 62f * unit, centerX + 58f * unit, centerY - 58f * unit, paint);
            }
            paint.setStrokeCap(Paint.Cap.BUTT);
        }

        private void drawLightRays(Canvas canvas, float centerX, float centerY, float radius, float active) {
            if (active <= 0f) {
                return;
            }
            float unit = radius / 100f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(6f * unit);
            paint.setColor(Color.argb(Math.round(190 * active), 250, 204, 21));

            for (int index = 0; index < 8; index++) {
                double angle = Math.toRadians(index * 45.0 - 90.0);
                float inner = radius * 0.76f;
                float outer = radius * 1.02f;
                float startX = centerX + (float) Math.cos(angle) * inner;
                float startY = centerY + (float) Math.sin(angle) * inner;
                float endX = centerX + (float) Math.cos(angle) * outer;
                float endY = centerY + (float) Math.sin(angle) * outer;
                canvas.drawLine(startX, startY, endX, endY, paint);
            }
            paint.setStrokeCap(Paint.Cap.BUTT);
        }

        private void animateTo(float target, boolean animated) {
            if (animator != null) {
                animator.cancel();
            }
            if (!animated) {
                progress = target;
                invalidate();
                return;
            }

            animator = ValueAnimator.ofFloat(progress, target);
            animator.setDuration(180L);
            animator.addUpdateListener(animation -> {
                progress = (float) animation.getAnimatedValue();
                invalidate();
            });
            animator.start();
        }

        private int blend(int from, int to, float amount) {
            float clamped = Math.max(0f, Math.min(1f, amount));
            int alpha = Math.round(Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * clamped);
            int red = Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * clamped);
            int green = Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * clamped);
            int blue = Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * clamped);
            return Color.argb(alpha, red, green, blue);
        }
    }
}

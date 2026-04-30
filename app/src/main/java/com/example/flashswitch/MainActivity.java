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
import android.graphics.RectF;
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

public class MainActivity extends Activity {
    private static final String TAG = "FlashSwitch";
    private static final int CAMERA_PERMISSION_REQUEST = 4301;

    private static final int SURFACE = Color.rgb(244, 251, 248);
    private static final int SURFACE_VARIANT = Color.rgb(218, 229, 225);
    private static final int INVERSE_ON_SURFACE = Color.rgb(236, 242, 239);
    private static final int SECONDARY_CONTAINER = Color.rgb(205, 232, 225);
    private static final int PRIMARY_FIXED_DIM = Color.rgb(131, 213, 198);
    private static final int ON_SURFACE = Color.rgb(23, 29, 27);
    private static final int ON_SURFACE_VARIANT = Color.rgb(73, 69, 79);
    private static final int ON_PRIMARY_CONTAINER = Color.rgb(0, 32, 28);
    private static final int PRIMARY = Color.rgb(0, 106, 96);
    private static final int SURFACE_CONTAINER_LOW = Color.rgb(239, 247, 243);
    private static final int OUTLINE_VARIANT = Color.argb(66, 116, 119, 117);
    private static final int TWEAK_PANEL = Color.argb(224, 250, 249, 247);
    private static final int TWEAK_TEXT = Color.rgb(41, 38, 27);
    private static final int TWEAK_MUTED = Color.argb(184, 41, 38, 27);
    private static final int TWEAK_RULE = Color.argb(38, 0, 0, 0);
    private static final int SYSTEM_GREEN = Color.rgb(52, 199, 89);

    private CameraManager cameraManager;
    private Handler mainHandler;
    private String torchCameraId;
    private boolean hasFlash;
    private boolean isTorchOn;
    private boolean pendingToggleAfterPermission;
    private boolean torchCallbackRegistered;
    private int maxTorchStrengthLevel = 1;
    private int currentTorchStrengthLevel = 1;
    private String statusOverride;
    private String detailOverride;

    private FlashToggleView flashToggleView;
    private LinearLayout heroCard;
    private TextView statusLabel;
    private TextView detailLabel;
    private TextView actionLabel;
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
        Window window = getWindow();
        window.setStatusBarColor(SURFACE);
        window.setNavigationBarColor(SURFACE);

        int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(SURFACE);
        root.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        root.addView(buildStatusBar());
        root.addView(buildAppBar());

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(18), dp(20), dp(18), dp(12));
        root.addView(content, new LinearLayout.LayoutParams(
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

        setContentView(root);
    }

    private View buildStatusBar() {
        FrameLayout bar = new FrameLayout(this);
        bar.setPadding(dp(16), 0, dp(16), 0);
        bar.setBackgroundColor(SURFACE);

        TextView time = text("9:30", 14, ON_SURFACE, Typeface.NORMAL);
        FrameLayout.LayoutParams timeParams = new FrameLayout.LayoutParams(
                dp(128),
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.START | Gravity.CENTER_VERTICAL
        );
        bar.addView(time, timeParams);

        View punchHole = new View(this);
        punchHole.setBackground(oval(Color.rgb(46, 46, 46)));
        FrameLayout.LayoutParams holeParams = new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER);
        bar.addView(punchHole, holeParams);

        TextView icons = text("◢  ▰", 13, ON_SURFACE, Typeface.BOLD);
        icons.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(
                dp(96),
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END | Gravity.CENTER_VERTICAL
        );
        bar.addView(icons, iconParams);

        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(40)
        ));
        return bar;
    }

    private View buildAppBar() {
        LinearLayout appBar = new LinearLayout(this);
        appBar.setOrientation(LinearLayout.HORIZONTAL);
        appBar.setGravity(Gravity.CENTER_VERTICAL);
        appBar.setPadding(dp(4), dp(4), dp(4), 0);
        appBar.setBackgroundColor(SURFACE);

        appBar.addView(appBarDot());

        TextView title = text("Flash Switch", 22, ON_SURFACE, Typeface.NORMAL);
        appBar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        appBar.addView(appBarDot());
        appBar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(60)
        ));
        return appBar;
    }

    private View appBarDot() {
        FrameLayout box = new FrameLayout(this);
        View dot = new View(this);
        dot.setAlpha(0.3f);
        dot.setBackground(oval(ON_SURFACE_VARIANT));
        box.addView(dot, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        box.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        return box;
    }

    private View buildHeroCard() {
        heroCard = new LinearLayout(this);
        heroCard.setOrientation(LinearLayout.VERTICAL);
        heroCard.setPadding(dp(14), dp(10), dp(14), dp(14));
        heroCard.setBackground(roundRect(TWEAK_PANEL, dp(14), Color.argb(153, 255, 255, 255)));
        heroCard.setElevation(dp(10));
        heroCard.setClickable(true);
        heroCard.setFocusable(true);
        heroCard.setOnClickListener(view -> onToggleRequested());

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Flash Tweaks", 12, TWEAK_TEXT, Typeface.BOLD);
        TextView close = text("✕", 13, Color.argb(140, 41, 38, 27), Typeface.NORMAL);
        close.setGravity(Gravity.CENTER);
        close.setBackground(roundRect(Color.TRANSPARENT, dp(6), 0));
        header.addView(title, new LinearLayout.LayoutParams(0, dp(32), 1f));
        header.addView(close, new LinearLayout.LayoutParams(dp(28), dp(28)));
        heroCard.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        heroCard.addView(tweakSectionLabel("TORCH"));

        LinearLayout powerRow = new LinearLayout(this);
        powerRow.setOrientation(LinearLayout.HORIZONTAL);
        powerRow.setGravity(Gravity.CENTER_VERTICAL);
        powerRow.setPadding(0, dp(5), 0, dp(5));
        TextView powerLabel = text("Power", 15, TWEAK_TEXT, Typeface.NORMAL);
        actionLabel = text("OFF", 11, Color.WHITE, Typeface.BOLD);
        actionLabel.setGravity(Gravity.CENTER);
        actionLabel.setPadding(dp(10), 0, dp(10), 0);
        actionLabel.setBackground(roundRect(Color.argb(38, 0, 0, 0), dp(999), 0));
        powerRow.addView(powerLabel, new LinearLayout.LayoutParams(0, dp(30), 1f));
        powerRow.addView(actionLabel, new LinearLayout.LayoutParams(dp(52), dp(26)));
        heroCard.addView(powerRow);

        heroCard.addView(buildBrightnessSelector());

        flashToggleView = new FlashToggleView(this);
        flashToggleView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(dp(188), dp(188));
        toggleParams.gravity = Gravity.CENTER_HORIZONTAL;
        toggleParams.topMargin = dp(4);
        heroCard.addView(flashToggleView, toggleParams);

        heroCard.addView(tweakSectionLabel("STATUS"));

        statusLabel = text("플래시 준비 중", 16, TWEAK_TEXT, Typeface.BOLD);
        statusLabel.setGravity(Gravity.START);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        statusParams.topMargin = dp(2);
        heroCard.addView(statusLabel, statusParams);

        detailLabel = text("카메라 플래시를 확인하고 있어요.", 13, TWEAK_MUTED, Typeface.NORMAL);
        detailLabel.setGravity(Gravity.START);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        detailParams.topMargin = dp(3);
        heroCard.addView(detailLabel, detailParams);

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cardParams.bottomMargin = dp(14);
        heroCard.setLayoutParams(cardParams);
        return heroCard;
    }

    private View buildStatusCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(roundRect(TWEAK_PANEL, dp(14), Color.argb(153, 255, 255, 255)));
        card.setElevation(dp(6));

        card.addView(tweakSectionLabel("DEVICE"));

        permissionChip = text("권한 확인 중", 14, TWEAK_TEXT, Typeface.NORMAL);
        LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        chipParams.topMargin = dp(4);
        card.addView(permissionChip, chipParams);

        capabilityLabel = text("토치 기능을 확인하고 있어요", 12, TWEAK_MUTED, Typeface.NORMAL);
        LinearLayout.LayoutParams capabilityParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        capabilityParams.topMargin = dp(4);
        card.addView(capabilityLabel, capabilityParams);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(12);
        card.setLayoutParams(params);
        return card;
    }

    private View buildInfoCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(roundRect(TWEAK_PANEL, dp(14), Color.argb(153, 255, 255, 255)));
        card.setElevation(dp(6));

        card.addView(tweakSectionLabel("SAFETY"));

        keepAwakeLabel = text("화면 유지 · 꺼짐", 12, TWEAK_MUTED, Typeface.NORMAL);
        feedbackLabel = text("햅틱 피드백 · 준비됨", 12, TWEAK_MUTED, Typeface.NORMAL);

        card.addView(featureRow("◐", "토치가 켜져 있을 때 화면을 계속 밝게 유지", keepAwakeLabel));
        card.addView(featureRow("•", "탭과 오류 상태를 촉감으로 알려줌", feedbackLabel));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        card.setLayoutParams(params);
        return card;
    }

    private View buildBrightnessSelector() {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setPadding(0, dp(4), 0, dp(6));

        TextView label = text("Brightness", 13, TWEAK_TEXT, Typeface.NORMAL);
        group.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(24)
        ));

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(dp(2), dp(2), dp(2), dp(2));
        chips.setBackground(roundRect(Color.argb(15, 0, 0, 0), dp(8), 0));

        brightnessLowChip = brightnessChip("약", 0);
        brightnessMidChip = brightnessChip("중", 1);
        brightnessHighChip = brightnessChip("강", 2);
        chips.addView(brightnessLowChip, new LinearLayout.LayoutParams(0, dp(28), 1f));
        chips.addView(brightnessMidChip, new LinearLayout.LayoutParams(0, dp(28), 1f));
        chips.addView(brightnessHighChip, new LinearLayout.LayoutParams(0, dp(28), 1f));

        group.addView(chips, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        return group;
    }

    private TextView brightnessChip(String label, int preset) {
        TextView chip = text(label, 12, TWEAK_TEXT, Typeface.BOLD);
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

        TextView leading = text(icon, 15, Color.WHITE, Typeface.BOLD);
        leading.setGravity(Gravity.CENTER);
        leading.setBackground(oval(SYSTEM_GREEN));
        row.addView(leading, new LinearLayout.LayoutParams(dp(28), dp(28)));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setPadding(dp(12), 0, 0, 0);
        TextView description = text(descriptionText, 13, TWEAK_TEXT, Typeface.NORMAL);
        copy.addView(description);
        copy.addView(valueLabel);
        row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private TextView tweakSectionLabel(String value) {
        TextView label = text(value, 10, Color.argb(115, 41, 38, 27), Typeface.BOLD);
        label.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        label.setLetterSpacing(0.06f);
        label.setPadding(0, dp(10), 0, 0);
        return label;
    }

    private View buildGestureBar() {
        FrameLayout nav = new FrameLayout(this);
        View pill = new View(this);
        pill.setAlpha(0.4f);
        pill.setBackground(roundRect(ON_SURFACE, dp(2), 0));
        nav.addView(pill, new FrameLayout.LayoutParams(dp(108), dp(4), Gravity.CENTER));
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
            defaultStatus = "플래시가 없어요";
            defaultDetail = "이 기기에서는 후면 플래시를 찾지 못했어요.";
            actionLabel.setText("N/A");
            permissionChip.setText("플래시 하드웨어 없음");
            capabilityLabel.setText("사용 가능한 카메라 플래시를 찾지 못했습니다.");
        } else if (permissionNeeded) {
            defaultStatus = "카메라 권한이 필요해요";
            defaultDetail = "권한을 허용하면 바로 플래시를 켤 수 있어요.";
            actionLabel.setText("ALLOW");
            permissionChip.setText("카메라 권한 필요");
            capabilityLabel.setText(torchCapabilityText());
        } else if (isTorchOn) {
            defaultStatus = "플래시 켜짐";
            defaultDetail = "주변을 밝히는 중이에요. 탭하면 꺼집니다.";
            actionLabel.setText("ON");
            permissionChip.setText("권한 허용됨 · 토치 켜짐");
            capabilityLabel.setText(torchCapabilityText());
        } else {
            defaultStatus = "플래시 꺼짐";
            defaultDetail = "둥근 스위치를 탭하면 토치가 켜집니다.";
            actionLabel.setText("OFF");
            permissionChip.setText("권한 허용됨 · 대기 중");
            capabilityLabel.setText(torchCapabilityText());
        }

        setStatusText(
                statusOverride != null ? statusOverride : defaultStatus,
                detailOverride != null ? detailOverride : defaultDetail
        );

        heroCard.setBackground(roundRect(TWEAK_PANEL, dp(14), Color.argb(153, 255, 255, 255)));
        actionLabel.setBackground(roundRect(isTorchOn ? SYSTEM_GREEN : Color.argb(38, 0, 0, 0), dp(999), 0));
        flashToggleView.setFlashState(isTorchOn, hasFlash && torchCameraId != null, permissionNeeded);
        updateBrightnessChips();
        updateKeepScreenOn();
        updateAccessibility();
        keepAwakeLabel.setText(isTorchOn ? "화면 유지 · 켜짐" : "화면 유지 · 꺼짐");
        feedbackLabel.setText("햅틱 피드백 · " + (isTorchOn ? "켜짐 확인" : "탭 준비"));
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
        int background = selected && enabled ? Color.WHITE : Color.TRANSPARENT;
        int textColor = enabled ? TWEAK_TEXT : Color.argb(95, 41, 38, 27);
        chip.setTextColor(textColor);
        chip.setAlpha(enabled ? 1f : 0.55f);
        chip.setBackground(roundRect(background, dp(6), selected && enabled ? TWEAK_RULE : 0));
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
        String action = actionLabel.getText().toString();
        String description = state + ". " + detail + " " + action;
        heroCard.setContentDescription(description);
        actionLabel.setContentDescription(action);
        permissionChip.setContentDescription("상태: " + permissionChip.getText());
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
        private final RectF rect = new RectF();

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
            float centerY = height * 0.43f;
            float radius = Math.min(width, height) * 0.34f;
            float active = available && !permissionNeeded ? progress : 0f;

            drawGlow(canvas, centerX, centerY, radius, active);
            drawMainButton(canvas, centerX, centerY, radius, active);
            drawBeam(canvas, centerX, centerY, radius, active);
            drawFlashIcon(canvas, centerX, centerY, radius, active);
            drawStateDots(canvas, centerX, centerY, radius, active);
            drawSwitch(canvas, centerX, height * 0.83f, width * 0.44f, height * 0.16f, active);
        }

        private void drawGlow(Canvas canvas, float centerX, float centerY, float radius, float active) {
            if (active <= 0f) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1));
                paint.setColor(Color.argb(42, 0, 106, 96));
                canvas.drawCircle(centerX, centerY, radius * 1.12f, paint);
                return;
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(Math.round(64 * active), 131, 213, 198));
            paint.setShadowLayer(dp(24), 0f, dp(6), Color.argb(Math.round(90 * active), 0, 106, 96));
            canvas.drawCircle(centerX, centerY, radius * (1.17f + 0.05f * active), paint);
            paint.clearShadowLayer();

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(Color.argb(Math.round(90 * active), 0, 106, 96));
            canvas.drawCircle(centerX, centerY, radius * 1.32f, paint);
        }

        private void drawMainButton(Canvas canvas, float centerX, float centerY, float radius, float active) {
            int disabledColor = permissionNeeded ? SECONDARY_CONTAINER : SURFACE_VARIANT;
            int buttonColor = blend(disabledColor, PRIMARY_FIXED_DIM, active);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(buttonColor);
            paint.setShadowLayer(dp(18), 0f, dp(8), Color.argb(42, 0, 0, 0));
            canvas.drawCircle(centerX, centerY, radius, paint);
            paint.clearShadowLayer();

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(Color.argb(120, 255, 255, 255));
            canvas.drawCircle(centerX, centerY, radius - dp(4), paint);

            paint.setStrokeWidth(dp(1));
            paint.setColor(Color.argb(70, 0, 32, 28));
            canvas.drawCircle(centerX, centerY, radius + dp(1), paint);
        }

        private void drawBeam(Canvas canvas, float centerX, float centerY, float radius, float active) {
            if (active <= 0f) {
                return;
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(Math.round(54 * active), 255, 255, 255));
            path.reset();
            path.moveTo(centerX - radius * 0.34f, centerY - radius * 0.72f);
            path.lineTo(centerX + radius * 0.34f, centerY - radius * 0.72f);
            path.lineTo(centerX + radius * 0.72f, centerY + radius * 0.64f);
            path.lineTo(centerX - radius * 0.72f, centerY + radius * 0.64f);
            path.close();
            canvas.drawPath(path, paint);
        }

        private void drawFlashIcon(Canvas canvas, float centerX, float centerY, float radius, float active) {
            int bodyColor = available ? blend(ON_SURFACE_VARIANT, PRIMARY, active) : Color.argb(120, 73, 69, 79);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(bodyColor);

            float unit = radius / 64f;
            rect.set(centerX - 17f * unit, centerY - 38f * unit, centerX + 17f * unit, centerY - 19f * unit);
            canvas.drawRoundRect(rect, 6f * unit, 6f * unit, paint);

            path.reset();
            path.moveTo(centerX - 13f * unit, centerY - 16f * unit);
            path.lineTo(centerX + 13f * unit, centerY - 16f * unit);
            path.lineTo(centerX + 8f * unit, centerY + 10f * unit);
            path.lineTo(centerX + 2f * unit, centerY + 18f * unit);
            path.lineTo(centerX + 2f * unit, centerY + 37f * unit);
            path.lineTo(centerX - 2f * unit, centerY + 37f * unit);
            path.lineTo(centerX - 2f * unit, centerY + 18f * unit);
            path.lineTo(centerX - 8f * unit, centerY + 10f * unit);
            path.close();
            canvas.drawPath(path, paint);

            paint.setColor(blend(Color.WHITE, ON_PRIMARY_CONTAINER, active * 0.35f));
            path.reset();
            path.moveTo(centerX - 2f * unit, centerY - 8f * unit);
            path.lineTo(centerX + 9f * unit, centerY - 8f * unit);
            path.lineTo(centerX + 1f * unit, centerY + 6f * unit);
            path.lineTo(centerX + 10f * unit, centerY + 6f * unit);
            path.lineTo(centerX - 8f * unit, centerY + 27f * unit);
            path.lineTo(centerX - 3f * unit, centerY + 11f * unit);
            path.lineTo(centerX - 12f * unit, centerY + 11f * unit);
            path.close();
            canvas.drawPath(path, paint);

            if (!available || permissionNeeded) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeWidth(5f * unit);
                paint.setColor(permissionNeeded ? PRIMARY : ON_SURFACE_VARIANT);
                canvas.drawLine(centerX - 28f * unit, centerY + 30f * unit, centerX + 28f * unit, centerY - 30f * unit, paint);
                paint.setStrokeCap(Paint.Cap.BUTT);
            }
        }

        private void drawStateDots(Canvas canvas, float centerX, float centerY, float radius, float active) {
            float dotY = centerY + radius * 0.72f;
            float gap = radius * 0.2f;
            paint.setStyle(Paint.Style.FILL);
            for (int index = -1; index <= 1; index++) {
                float emphasis = index == 0 ? 1f : 0.45f;
                int color = blend(SURFACE_VARIANT, SYSTEM_GREEN, active * emphasis);
                paint.setColor(color);
                canvas.drawCircle(centerX + gap * index, dotY, dp(index == 0 ? 3.5f : 2.5f), paint);
            }
        }

        private void drawSwitch(Canvas canvas, float centerX, float centerY, float switchWidth, float switchHeight, float active) {
            float left = centerX - switchWidth / 2f;
            float top = centerY - switchHeight / 2f;
            float right = centerX + switchWidth / 2f;
            float bottom = centerY + switchHeight / 2f;
            float radius = switchHeight / 2f;

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(blend(Color.argb(38, 0, 0, 0), SYSTEM_GREEN, active));
            rect.set(left, top, right, bottom);
            canvas.drawRoundRect(rect, radius, radius, paint);

            paint.setTextSize(dp(9));
            paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(blend(ON_SURFACE_VARIANT, Color.WHITE, active));
            canvas.drawText(active > 0.5f ? "ON" : "OFF", centerX, centerY + dp(3), paint);

            float thumbRadius = switchHeight * 0.38f;
            float thumbX = left + radius + (switchWidth - switchHeight) * active;
            paint.setColor(Color.WHITE);
            paint.setShadowLayer(dp(3), 0f, dp(1), Color.argb(70, 0, 0, 0));
            canvas.drawCircle(thumbX, centerY, thumbRadius, paint);
            paint.clearShadowLayer();
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

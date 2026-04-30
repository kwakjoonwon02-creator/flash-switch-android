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
        content.setPadding(dp(24), dp(22), dp(24), dp(12));
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
        heroCard.setGravity(Gravity.CENTER_HORIZONTAL);
        heroCard.setPadding(dp(20), dp(24), dp(20), dp(18));
        heroCard.setBackground(roundRect(INVERSE_ON_SURFACE, dp(32), OUTLINE_VARIANT));
        heroCard.setClickable(true);
        heroCard.setFocusable(true);
        heroCard.setOnClickListener(view -> onToggleRequested());
        heroCard.setOnLongClickListener(view -> {
            cycleTorchStrength();
            return true;
        });

        TextView eyebrow = text("MATERIAL TORCH", 12, PRIMARY, Typeface.BOLD);
        eyebrow.setGravity(Gravity.CENTER);
        eyebrow.setLetterSpacing(0.12f);
        eyebrow.setPadding(dp(14), 0, dp(14), 0);
        eyebrow.setBackground(roundRect(SECONDARY_CONTAINER, dp(100), 0));
        LinearLayout.LayoutParams eyebrowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(32)
        );
        eyebrowParams.bottomMargin = dp(10);
        heroCard.addView(eyebrow, eyebrowParams);

        flashToggleView = new FlashToggleView(this);
        flashToggleView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        heroCard.addView(flashToggleView, new LinearLayout.LayoutParams(dp(252), dp(252)));

        statusLabel = text("플래시 준비 중", 26, ON_SURFACE, Typeface.NORMAL);
        statusLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        statusParams.topMargin = dp(10);
        heroCard.addView(statusLabel, statusParams);

        detailLabel = text("카메라 플래시를 확인하고 있어요.", 15, ON_SURFACE_VARIANT, Typeface.NORMAL);
        detailLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        detailParams.topMargin = dp(6);
        heroCard.addView(detailLabel, detailParams);

        actionLabel = text("탭해서 켜기", 15, ON_PRIMARY_CONTAINER, Typeface.BOLD);
        actionLabel.setGravity(Gravity.CENTER);
        actionLabel.setPadding(dp(22), 0, dp(22), 0);
        actionLabel.setBackground(roundRect(SECONDARY_CONTAINER, dp(100), 0));
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(44)
        );
        actionParams.topMargin = dp(18);
        heroCard.addView(actionLabel, actionParams);

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
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setBackground(roundRect(SURFACE_CONTAINER_LOW, dp(24), OUTLINE_VARIANT));

        TextView title = text("현재 상태", 14, ON_SURFACE_VARIANT, Typeface.BOLD);
        title.setLetterSpacing(0.04f);
        card.addView(title);

        permissionChip = text("권한 확인 중", 18, ON_SURFACE, Typeface.NORMAL);
        LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        chipParams.topMargin = dp(4);
        card.addView(permissionChip, chipParams);

        capabilityLabel = text("토치 기능을 확인하고 있어요", 14, ON_SURFACE_VARIANT, Typeface.NORMAL);
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
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackground(roundRect(Color.WHITE, dp(24), OUTLINE_VARIANT));

        TextView headline = text("안심 사용 기능", 16, ON_SURFACE, Typeface.BOLD);
        card.addView(headline);

        keepAwakeLabel = text("화면 유지 · 꺼짐", 14, ON_SURFACE_VARIANT, Typeface.NORMAL);
        feedbackLabel = text("햅틱 피드백 · 준비됨", 14, ON_SURFACE_VARIANT, Typeface.NORMAL);

        card.addView(featureRow("◐", "토치가 켜져 있을 때 화면을 계속 밝게 유지", keepAwakeLabel));
        card.addView(featureRow("•", "탭과 오류 상태를 촉감으로 알려줌", feedbackLabel));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        card.setLayoutParams(params);
        return card;
    }

    private View featureRow(String icon, String descriptionText, TextView valueLabel) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, 0);

        TextView leading = text(icon, 18, Color.WHITE, Typeface.BOLD);
        leading.setGravity(Gravity.CENTER);
        leading.setBackground(oval(PRIMARY));
        row.addView(leading, new LinearLayout.LayoutParams(dp(36), dp(36)));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setPadding(dp(12), 0, 0, 0);
        TextView description = text(descriptionText, 14, ON_SURFACE, Typeface.NORMAL);
        copy.addView(description);
        copy.addView(valueLabel);
        row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
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
                if (!enabled) {
                    currentTorchStrengthLevel = 1;
                }
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

    private void cycleTorchStrength() {
        if (!isTorchOn || !supportsStrengthControl()) {
            performErrorHaptic();
            showStatus("밝기 조절 대기 중", "Android 13+ 지원 기기에서 플래시가 켜져 있을 때 길게 누르면 밝기가 바뀝니다.");
            refreshUi();
            return;
        }

        int nextLevel = currentTorchStrengthLevel >= maxTorchStrengthLevel ? 1 : currentTorchStrengthLevel + 1;
        try {
            cameraManager.turnOnTorchWithStrengthLevel(torchCameraId, nextLevel);
            currentTorchStrengthLevel = nextLevel;
            showStatus("밝기 " + currentTorchStrengthLevel + "단계", "길게 누르면 다음 밝기 단계로 전환됩니다.");
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
            actionLabel.setText("사용 불가");
            permissionChip.setText("플래시 하드웨어 없음");
            capabilityLabel.setText("사용 가능한 카메라 플래시를 찾지 못했습니다.");
        } else if (permissionNeeded) {
            defaultStatus = "카메라 권한이 필요해요";
            defaultDetail = "권한을 허용하면 바로 플래시를 켤 수 있어요.";
            actionLabel.setText("권한 허용 후 켜기");
            permissionChip.setText("카메라 권한 필요");
            capabilityLabel.setText(torchCapabilityText());
        } else if (isTorchOn) {
            defaultStatus = "플래시 켜짐";
            defaultDetail = "주변을 밝히는 중이에요. 탭하면 꺼집니다.";
            actionLabel.setText("탭해서 끄기");
            permissionChip.setText("권한 허용됨 · 토치 켜짐");
            capabilityLabel.setText(torchCapabilityText());
        } else {
            defaultStatus = "플래시 꺼짐";
            defaultDetail = "둥근 스위치를 탭하면 토치가 켜집니다.";
            actionLabel.setText("탭해서 켜기");
            permissionChip.setText("권한 허용됨 · 대기 중");
            capabilityLabel.setText(torchCapabilityText());
        }

        setStatusText(
                statusOverride != null ? statusOverride : defaultStatus,
                detailOverride != null ? detailOverride : defaultDetail
        );

        heroCard.setBackground(roundRect(isTorchOn ? SECONDARY_CONTAINER : INVERSE_ON_SURFACE, dp(32), OUTLINE_VARIANT));
        actionLabel.setBackground(roundRect(isTorchOn ? PRIMARY_FIXED_DIM : SECONDARY_CONTAINER, dp(100), 0));
        flashToggleView.setFlashState(isTorchOn, hasFlash && torchCameraId != null, permissionNeeded);
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

    private String torchCapabilityText() {
        if (supportsStrengthControl()) {
            return "Android 13+ 밝기 제어 지원 · 현재 " + currentTorchStrengthLevel + "/" + maxTorchStrengthLevel + "단계 · 길게 눌러 변경";
        }
        return "기본 토치 제어 지원 · 안정적인 켜기/끄기 모드";
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
                int color = blend(SURFACE_VARIANT, PRIMARY, active * emphasis);
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
            paint.setColor(blend(Color.argb(38, 0, 0, 0), PRIMARY, active));
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

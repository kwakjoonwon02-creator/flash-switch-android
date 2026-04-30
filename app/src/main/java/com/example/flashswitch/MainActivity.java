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
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.MobileAds;

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
    private static final String BANNER_AD_UNIT_ID = "ca-app-pub-2142114665268625/7143992451";

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

    private LinearLayout rootLayout;
    private LinearLayout appBarView;
    private FrameLayout adContainerView;
    private TextView appTitleLabel;
    private View gesturePillView;
    private AdView bannerAdView;
    private FlashToggleView flashToggleView;
    private LinearLayout heroCard;
    private LinearLayout statusCard;
    private LinearLayout infoCard;
    private TextView statusLabel;
    private TextView detailLabel;
    private PowerButtonView powerButtonView;
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
                showStatus("Flash is temporarily unavailable", "Another app may be using the camera.");
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
        initializeAds();
        if (cameraManager == null) {
            hasFlash = false;
            showStatus("Camera service unavailable", "Flash control is not available on this device.");
            refreshUi();
            return;
        }
        findTorchCamera();
        try {
            cameraManager.registerTorchCallback(torchCallback, mainHandler);
            torchCallbackRegistered = true;
        } catch (SecurityException exception) {
            Log.w(TAG, "Torch callback registration needs camera permission", exception);
            showStatus("Camera permission required", "Allow permission to check the flash status.");
        }
        refreshUi();
    }

    @Override
    protected void onPause() {
        if (bannerAdView != null) {
            bannerAdView.pause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (bannerAdView != null) {
            bannerAdView.resume();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bannerAdView != null) {
            bannerAdView.destroy();
            bannerAdView = null;
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
                showStatus("Permission blocked", "Allow camera permission in Settings > Apps > Flash Switch.");
            } else {
                showStatus("Permission required", "Allow camera permission to turn on the flash.");
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

        rootLayout.addView(buildAppBar());

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(20), dp(8), dp(20), dp(6));
        scrollView.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        rootLayout.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        content.addView(buildHeroCard());
        content.addView(buildStatusCard());
        content.addView(buildInfoCard());

        rootLayout.addView(buildAdContainer());
        rootLayout.addView(buildGestureBar());

        setContentView(rootLayout);
    }

    private View buildAdContainer() {
        adContainerView = new FrameLayout(this);
        adContainerView.setPadding(dp(10), dp(4), dp(10), dp(4));
        adContainerView.setBackgroundColor(ZINC_950);
        adContainerView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        adContainerView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        return adContainerView;
    }

    private void initializeAds() {
        MobileAds.initialize(this, initializationStatus -> loadBannerAd());
    }

    private void loadBannerAd() {
        if (adContainerView == null) {
            return;
        }

        if (bannerAdView != null) {
            bannerAdView.destroy();
        }

        bannerAdView = new AdView(this);
        bannerAdView.setAdUnitId(BANNER_AD_UNIT_ID);
        bannerAdView.setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(this, adWidthDp()));
        adContainerView.removeAllViews();
        adContainerView.addView(bannerAdView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
        ));
        bannerAdView.loadAd(new AdRequest.Builder().build());
    }

    private int adWidthDp() {
        float density = getResources().getDisplayMetrics().density;
        int widthPixels = getResources().getDisplayMetrics().widthPixels - dp(20);
        return Math.max(1, Math.round(widthPixels / density));
    }

    private View buildAppBar() {
        appBarView = new LinearLayout(this);
        appBarView.setOrientation(LinearLayout.HORIZONTAL);
        appBarView.setGravity(Gravity.CENTER_VERTICAL);
        appBarView.setPadding(dp(20), dp(8), dp(20), 0);
        appBarView.setBackgroundColor(ZINC_950);

        appTitleLabel = text("Flashlight", 22, ZINC_100, Typeface.BOLD);
        appTitleLabel.setGravity(Gravity.CENTER);
        appBarView.addView(appTitleLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        appBarView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(56)
        ));
        return appBarView;
    }

    private View buildHeroCard() {
        heroCard = new LinearLayout(this);
        heroCard.setOrientation(LinearLayout.VERTICAL);
        heroCard.setGravity(Gravity.CENTER_HORIZONTAL);
        heroCard.setPadding(0, dp(2), 0, dp(4));
        heroCard.setBackgroundColor(Color.TRANSPARENT);
        heroCard.setClickable(false);
        heroCard.setFocusable(false);

        flashToggleView = new FlashToggleView(this);
        flashToggleView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(dp(210), dp(210));
        toggleParams.gravity = Gravity.CENTER_HORIZONTAL;
        toggleParams.topMargin = dp(4);
        heroCard.addView(flashToggleView, toggleParams);

        statusLabel = text("Preparing flash", 30, ZINC_100, Typeface.BOLD);
        statusLabel.setGravity(Gravity.CENTER);
        statusLabel.setTextSize(24);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        statusParams.topMargin = dp(4);
        heroCard.addView(statusLabel, statusParams);

        detailLabel = text("Checking the camera flash.", 15, ZINC_500, Typeface.NORMAL);
        detailLabel.setGravity(Gravity.CENTER);
        detailLabel.setTextSize(13);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        detailParams.topMargin = dp(3);
        heroCard.addView(detailLabel, detailParams);

        powerButtonView = new PowerButtonView(this);
        powerButtonView.setClickable(true);
        powerButtonView.setFocusable(true);
        powerButtonView.setOnClickListener(view -> onToggleRequested());
        powerButtonView.setElevation(dp(12));
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(dp(72), dp(72));
        actionParams.gravity = Gravity.CENTER_HORIZONTAL;
        actionParams.topMargin = dp(12);
        heroCard.addView(powerButtonView, actionParams);

        heroCard.addView(buildBrightnessSelector());

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cardParams.bottomMargin = dp(8);
        heroCard.setLayoutParams(cardParams);
        return heroCard;
    }

    private View buildStatusCard() {
        statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setPadding(dp(14), dp(8), dp(14), dp(8));
        statusCard.setBackground(roundRect(Color.argb(18, 255, 255, 255), dp(22), Color.argb(20, 255, 255, 255)));
        statusCard.setElevation(dp(2));

        statusCard.addView(tweakSectionLabel("DEVICE"));

        permissionChip = text("Checking permission", 14, ZINC_100, Typeface.BOLD);
        LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        chipParams.topMargin = dp(4);
        statusCard.addView(permissionChip, chipParams);

        capabilityLabel = text("Checking torch capability", 12, ZINC_500, Typeface.NORMAL);
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
        params.bottomMargin = dp(8);
        statusCard.setLayoutParams(params);
        return statusCard;
    }

    private View buildInfoCard() {
        infoCard = new LinearLayout(this);
        infoCard.setOrientation(LinearLayout.VERTICAL);
        infoCard.setPadding(dp(14), dp(8), dp(14), dp(8));
        infoCard.setBackground(roundRect(Color.argb(18, 255, 255, 255), dp(22), Color.argb(20, 255, 255, 255)));
        infoCard.setElevation(dp(2));

        infoCard.addView(tweakSectionLabel("SAFETY"));

        keepAwakeLabel = text("Keep screen on - Off", 12, ZINC_500, Typeface.NORMAL);
        feedbackLabel = text("Haptic feedback - Ready", 12, ZINC_500, Typeface.NORMAL);

        infoCard.addView(featureRow("S", "Keeps the screen awake while the torch is on", keepAwakeLabel));
        infoCard.addView(featureRow("H", "Confirms taps and errors with haptic feedback", feedbackLabel));

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
        group.setPadding(0, dp(12), 0, 0);

        brightnessTitleLabel = text("Brightness", 13, ZINC_500, Typeface.BOLD);
        brightnessTitleLabel.setGravity(Gravity.CENTER);
        group.addView(brightnessTitleLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(20)
        ));

        brightnessChipsGroup = new LinearLayout(this);
        brightnessChipsGroup.setOrientation(LinearLayout.HORIZONTAL);
        brightnessChipsGroup.setGravity(Gravity.CENTER);
        brightnessChipsGroup.setPadding(dp(5), dp(5), dp(5), dp(5));
        brightnessChipsGroup.setBackground(roundRect(Color.argb(18, 255, 255, 255), dp(999), Color.argb(18, 255, 255, 255)));

        brightnessLowChip = brightnessChip("Low", 0);
        brightnessMidChip = brightnessChip("Medium", 1);
        brightnessHighChip = brightnessChip("High", 2);
        brightnessChipsGroup.addView(brightnessLowChip, new LinearLayout.LayoutParams(0, dp(34), 1f));
        brightnessChipsGroup.addView(brightnessMidChip, new LinearLayout.LayoutParams(0, dp(34), 1f));
        brightnessChipsGroup.addView(brightnessHighChip, new LinearLayout.LayoutParams(0, dp(34), 1f));

        LinearLayout.LayoutParams chipGroupParams = new LinearLayout.LayoutParams(dp(214), dp(44));
        chipGroupParams.gravity = Gravity.CENTER_HORIZONTAL;
        chipGroupParams.topMargin = dp(4);
        group.addView(brightnessChipsGroup, chipGroupParams);
        return group;
    }

    private TextView brightnessChip(String label, int preset) {
        TextView chip = text(label, 12, ZINC_100, Typeface.BOLD);
        chip.setGravity(Gravity.CENTER);
        chip.setIncludeFontPadding(false);
        chip.setMinWidth(0);
        chip.setPadding(0, 0, 0, 0);
        chip.setClickable(true);
        chip.setFocusable(true);
        chip.setOnClickListener(view -> onBrightnessPresetRequested(preset));
        return chip;
    }

    private View featureRow(String icon, String descriptionText, TextView valueLabel) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, 0);

        TextView leading = text(icon, 13, ZINC_950, Typeface.BOLD);
        leading.setGravity(Gravity.CENTER);
        leading.setBackground(oval(BULB_YELLOW));
        row.addView(leading, new LinearLayout.LayoutParams(dp(24), dp(24)));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setPadding(dp(12), 0, 0, 0);
        TextView description = text(descriptionText, 12, ZINC_100, Typeface.NORMAL);
        copy.addView(description);
        copy.addView(valueLabel);
        row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private TextView tweakSectionLabel(String value) {
        TextView label = text(value, 10, ZINC_500, Typeface.BOLD);
        label.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        label.setLetterSpacing(0.06f);
        label.setPadding(0, dp(4), 0, 0);
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
                dp(18)
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
            showStatus("Unable to check camera", "The device camera service is not responding.");
        }
    }

    private void onToggleRequested() {
        if (!hasFlash || torchCameraId == null) {
            showStatus("No flash found", "No rear flash was found on this device.");
            refreshUi();
            performErrorHaptic();
            return;
        }

        if (!hasCameraPermission()) {
            pendingToggleAfterPermission = true;
            if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                showStatus("Camera permission required", "Camera permission is required for flash control. No photos are taken.");
            } else {
                showStatus("Requesting permission", "Tap Allow to turn on the flash right away.");
            }
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
            refreshUi();
            return;
        }

        setTorch(!isTorchOn);
    }

    private void setTorch(boolean enabled) {
        if (cameraManager == null || torchCameraId == null) {
            showStatus("No flash found", "There is no camera flash available to turn on or off.");
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
            showStatus("Permission required", "Allow camera permission in Settings, then try again.");
            refreshUi();
            performErrorHaptic();
        } catch (CameraAccessException | IllegalArgumentException exception) {
            Log.w(TAG, "Unable to toggle torch", exception);
            showStatus("Flash toggle failed", describeCameraError(exception));
            refreshUi();
            performErrorHaptic();
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unexpected torch failure", exception);
            showStatus("Flash toggle failed", "The device camera service could not handle the request. Try again shortly.");
            refreshUi();
            performErrorHaptic();
        }
    }

    private void onBrightnessPresetRequested(int preset) {
        if (!hasFlash || torchCameraId == null) {
            performErrorHaptic();
            showStatus("No flash found", "No torch with brightness control was found on this device.");
            refreshUi();
            return;
        }

        if (!hasCameraPermission()) {
            pendingToggleAfterPermission = true;
            currentTorchStrengthLevel = strengthForPreset(preset);
            showStatus("Requesting permission", "Allow permission to turn it on with the selected brightness.");
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
            showStatus("Automatic brightness", "This device uses the default torch mode without separate brightness levels.");
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
            showStatus(brightnessPresetLabel(preset) + " brightness", "Choose brightness quickly with Low, Medium, or High presets.");
            refreshUi();
            performToggleHaptic();
        } catch (SecurityException exception) {
            Log.w(TAG, "Camera permission missing while changing torch strength", exception);
            showStatus("Permission required", "Allow camera permission to adjust brightness.");
            refreshUi();
            performErrorHaptic();
        } catch (CameraAccessException | RuntimeException exception) {
            Log.w(TAG, "Unable to change torch strength", exception);
            showStatus("Brightness adjustment failed", describeCameraError(exception));
            refreshUi();
            performErrorHaptic();
        }
    }

    private void refreshUi() {
        boolean permissionNeeded = hasFlash && !hasCameraPermission();
        String defaultStatus;
        String defaultDetail;
        if (!hasFlash || torchCameraId == null) {
            defaultStatus = "Unavailable";
            defaultDetail = "No rear flash was found on this device.";
            permissionChip.setText("No flash hardware");
            capabilityLabel.setText("No available camera flash was found.");
        } else if (permissionNeeded) {
            defaultStatus = "Permission needed";
            defaultDetail = "Allow permission to turn on the flash right away.";
            permissionChip.setText("Camera permission required");
            capabilityLabel.setText(torchCapabilityText());
        } else if (isTorchOn) {
            defaultStatus = "On";
            defaultDetail = "Lighting your surroundings. Tap to turn off.";
            permissionChip.setText("Permission granted - Torch on");
            capabilityLabel.setText(torchCapabilityText());
        } else {
            defaultStatus = "Off";
            defaultDetail = "Tap the power button to turn on the flashlight.";
            permissionChip.setText("Permission granted - Standby");
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
        keepAwakeLabel.setText(isTorchOn ? "Keep screen on - On" : "Keep screen on - Off");
        feedbackLabel.setText("Haptic feedback - " + (isTorchOn ? "On confirmed" : "Ready for tap"));
    }

    private void applyVisualState(boolean on, boolean permissionNeeded) {
        int background = on ? Color.WHITE : ZINC_950;
        int primaryText = on ? ZINC_800 : ZINC_100;
        int mutedText = on ? ZINC_500 : ZINC_500;
        int cardBackground = on ? Color.argb(18, 0, 0, 0) : Color.argb(18, 255, 255, 255);
        int cardStroke = on ? Color.argb(20, 0, 0, 0) : Color.argb(20, 255, 255, 255);

        rootLayout.setBackgroundColor(background);
        appBarView.setBackgroundColor(background);
        adContainerView.setBackgroundColor(background);
        heroCard.setBackgroundColor(Color.TRANSPARENT);
        statusCard.setBackground(roundRect(cardBackground, dp(22), cardStroke));
        infoCard.setBackground(roundRect(cardBackground, dp(22), cardStroke));
        applySystemBars(on);

        appTitleLabel.setTextColor(primaryText);
        gesturePillView.setBackground(roundRect(on ? ZINC_800 : ZINC_100, dp(2), 0));

        statusLabel.setTextColor(primaryText);
        detailLabel.setTextColor(mutedText);
        permissionChip.setTextColor(primaryText);
        capabilityLabel.setTextColor(mutedText);
        keepAwakeLabel.setTextColor(mutedText);
        feedbackLabel.setTextColor(mutedText);
        brightnessTitleLabel.setTextColor(mutedText);
        brightnessChipsGroup.setBackground(roundRect(cardBackground, dp(999), cardStroke));

        boolean unavailable = !hasFlash || torchCameraId == null;
        powerButtonView.setPowerState(on, permissionNeeded, unavailable);
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
            return "Low";
        }
        if (preset == 1) {
            return "Medium";
        }
        return "High";
    }

    private String torchCapabilityText() {
        if (supportsStrengthControl()) {
            return "Brightness control supported - Choose Low/Medium/High";
        }
        return "Basic torch control supported - Stable on/off mode";
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
                    return "Another app is using the camera. Close it, then try again.";
                case CameraAccessException.MAX_CAMERAS_IN_USE:
                    return "Too many cameras are open. Try again shortly.";
                case CameraAccessException.CAMERA_DISABLED:
                    return "Camera is disabled by device policy, so the flash cannot be turned on.";
                case CameraAccessException.CAMERA_DISCONNECTED:
                    return "The camera disconnected. Turn the screen off and on, then try again.";
                default:
                    return "The camera service is not responding right now. Try again.";
            }
        }
        return "This device's torch cannot accept requests right now. Try again shortly.";
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
        powerButtonView.setContentDescription(action);
        permissionChip.setContentDescription("Status: " + permissionChip.getText());
    }

    private String actionAccessibilityLabel() {
        if (!hasFlash || torchCameraId == null) {
            return "Flash unavailable";
        }
        if (!hasCameraPermission()) {
            return "Request camera permission";
        }
        return isTorchOn ? "Turn flashlight off" : "Turn flashlight on";
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

    private class PowerButtonView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean on;
        private boolean permissionNeeded;
        private boolean unavailable;

        PowerButtonView(Context context) {
            super(context);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        void setPowerState(boolean nextOn, boolean nextPermissionNeeded, boolean nextUnavailable) {
            on = nextOn;
            permissionNeeded = nextPermissionNeeded;
            unavailable = nextUnavailable;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth();
            float height = getHeight();
            float centerX = width / 2f;
            float centerY = height / 2f;
            float radius = Math.min(width, height) * 0.46f;

            int fillColor;
            int iconColor;
            if (unavailable || permissionNeeded) {
                fillColor = Color.argb(235, 244, 244, 245);
                iconColor = ZINC_700;
            } else if (on) {
                fillColor = ZINC_900;
                iconColor = BULB_AMBER;
            } else {
                fillColor = Color.WHITE;
                iconColor = ZINC_950;
            }

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(fillColor);
            paint.setShadowLayer(dp(14), 0f, dp(7), Color.argb(on ? 80 : 54, 0, 0, 0));
            canvas.drawCircle(centerX, centerY, radius, paint);
            paint.clearShadowLayer();

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(dp(5));
            paint.setColor(iconColor);
            float iconRadius = radius * 0.42f;
            canvas.drawArc(
                    centerX - iconRadius,
                    centerY - iconRadius * 0.55f,
                    centerX + iconRadius,
                    centerY + iconRadius * 1.45f,
                    135f,
                    270f,
                    false,
                    paint
            );
            canvas.drawLine(centerX, centerY - radius * 0.48f, centerX, centerY + radius * 0.04f, paint);
            paint.setStrokeCap(Paint.Cap.BUTT);

            if (unavailable) {
                paint.setStyle(Paint.Style.FILL);
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
                paint.setTextSize(dp(16));
                paint.setColor(ZINC_700);
                canvas.drawText("!", centerX, centerY + radius * 0.72f, paint);
            }
        }
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

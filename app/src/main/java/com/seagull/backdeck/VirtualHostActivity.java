package com.seagull.backdeck;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.RoundedCorner;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Displays another app inside the rear screen's camera-safe region. */
public final class VirtualHostActivity extends Activity implements TextureView.SurfaceTextureListener {
    private static final String TAG = "BackDeckVirtual";
    private FrameLayout root;
    private FrameLayout viewport;
    private FrameLayout returnPreview;
    private FrameLayout previewCard;
    private LinearLayout previewLabels;
    private TextView previewTime;
    private TextView previewDate;
    private TextureView texture;
    private Surface outputSurface;
    private VirtualDisplay virtualDisplay;
    private TextView badge;
    private TextView homeConfirmationHint;
    private ActionConfig target;
    private RootInputClient inputClient;
    private VirtualTaskWatcher taskWatcher;
    private OnBackInvokedCallback backCallback;
    private final ExecutorService inputWorker = Executors.newSingleThreadExecutor();
    /** Keeps root WMS policy negotiation off the rear activity's render thread. */
    private float downX;
    private float downY;
    private long downTime;
    private int virtualWidth;
    private int virtualHeight;
    private int topCropPx;
    private int physicalWidth;
    private int physicalHeight;
    private static final int VIRTUAL_DENSITY_DPI = 240;
    private int virtualDensityDpi = VIRTUAL_DENSITY_DPI;
    private boolean streamedGesture;
    private boolean edgeBackCandidate;
    private boolean bottomHomeCandidate;
    private boolean bottomHomeActive;
    private boolean returningHome;
    /** True only when the target itself exited; that empty virtual session must be discarded. */
    private boolean discardSessionOnReturn;
    private boolean confirmReturnHome = true;
    /** Identity of the app/activity currently owned by this one physical rear host. */
    private String targetIdentity;
    /** Invalidates delayed TextureView callbacks when a different drawer item wins. */
    private int virtualGeneration;
    private long homeConfirmationDeadline;
    private MotionEvent heldBottomDown;
    private float downRawX;
    private float downRawY;
    private float previousRawY;
    private long previousEventTime;
    private float upwardVelocity;
    private final Handler touchHandler = new Handler(Looper.getMainLooper());
    private final Runnable expireHomeConfirmation = () -> {
        homeConfirmationDeadline = 0L;
        if (homeConfirmationHint != null) homeConfirmationHint.setVisibility(View.GONE);
    };
    private final Runnable flushHeldBottomDown = () -> {
        // Only the reserved bottom strip is buffered. A stationary long press is
        // handed back to the virtual app; all other app touches stream at once.
        if (!bottomHomeCandidate || bottomHomeActive || heldBottomDown == null) return;
        bottomHomeCandidate = false;
        streamedGesture = forwardHeldBottomDown();
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // Keep the assistant-owned rear host visible when the front keyguard is showing.
        // This does not dismiss keyguard or override a target app's own lockscreen policy.
        setShowWhenLocked(true);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getAttributes().layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        createReturnPreview();
        applyLaunchIntent(getIntent());
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = this::forwardBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }
        viewport = new FrameLayout(this);
        viewport.setBackgroundColor(Color.BLACK);
        viewport.setClipChildren(true);
        viewport.setClipToOutline(true);
        root.addView(viewport);
        texture = new TextureView(this);
        texture.setSurfaceTextureListener(this);
        texture.setOnTouchListener((view, event) -> handleTouch(event));
        viewport.addView(texture);
        createHomeConfirmationHint();
        if (target == null) {
            badge = new TextView(this);
            badge.setTextColor(Color.WHITE);
            badge.setTextSize(9);
            badge.setText("VIRTUAL DISPLAY: WAITING");
            FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT);
            badgeParams.topMargin = 8;
            badgeParams.rightMargin = 12;
            root.addView(badge, badgeParams);
        }
        setContentView(root);
        root.post(this::layoutSafeSurface);
    }

    /**
     * This activity is a single rear-screen host.  A second drawer tap must not create a
     * second host/virtual display; an explicit different app replaces the one owned here.
     */
    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!applyLaunchIntent(intent)) {
            // The clock was reordered over this host after the user swiped Home.  Bring
            // this exact host/VD back without launching the target a second time.
            if (returningHome) restoreRetainedHost();
            else Log.i(TAG, "ignored duplicate safe-area launch: " + targetIdentity);
            return;
        }
        restartForChangedLaunch();
    }

    /** @return true only when the target viewport needs to be replaced. */
    private boolean applyLaunchIntent(android.content.Intent intent) {
        String targetValue = intent.getStringExtra("target_value");
        if (targetValue == null || targetValue.isEmpty()) return false;
        ActionConfig requested = new ActionConfig();
        requested.type = intent.getStringExtra("target_type");
        requested.value = targetValue;
        requested.label = intent.getStringExtra("target_label");
        requested.displayId = ActionConfig.DISPLAY_REAR_SAFE;
        requested.enabled = true;
        requested.virtualDpi = intent.getIntExtra("virtual_dpi", VIRTUAL_DENSITY_DPI);
        requested.virtualTopCropPx = intent.getIntExtra("virtual_top_crop_px", 0);
        requested.confirmReturnHome = intent.getBooleanExtra("confirm_return_home", true);
        return applyLaunchConfig(requested);
    }

    /** Applies the exact latest drawer choice, including an explicit 0 px top crop. */
    private boolean applyLaunchConfig(ActionConfig requested) {
        if (requested == null || requested.value == null || requested.value.isEmpty()) return false;
        String requestedType = requested.type == null ? "uri" : requested.type;
        int requestedDpi = ActionConfig.clampVirtualDpi(requested.virtualDpi);
        int requestedTopCrop = ActionConfig.clampVirtualTopCropPx(requested.virtualTopCropPx);
        String requestedIdentity = requestedType + '\u0000' + requested.value;
        boolean replace = target == null || !requestedIdentity.equals(targetIdentity)
                || requestedDpi != virtualDensityDpi || requestedTopCrop != topCropPx;
        target = requested.copy();
        target.type = requestedType;
        target.displayId = ActionConfig.DISPLAY_REAR_SAFE;
        target.enabled = true;
        targetIdentity = requestedIdentity;
        virtualDensityDpi = requestedDpi;
        topCropPx = requestedTopCrop;
        confirmReturnHome = requested.confirmReturnHome;
        if (replace) virtualGeneration++;
        return replace;
    }

    /** Rebuild only when a real DPI/crop/target change is observed. */
    private void restartForChangedLaunch() {
        Log.i(TAG, "switching safe-area target to " + targetIdentity
                + " cropTop=" + topCropPx);
        clearHomeConfirmation();
        if (returningHome) restoreRetainedHost();
        // A retained host keeps the old TextureView bounds.  Recompute before creating the
        // next VD so an explicit 0 -> 144 (or 144 -> 0) crop changes its real height rather
        // than only changing a field that the old 680×596 surface never observes.
        if (root != null && root.getWidth() > 0 && root.getHeight() > 0) {
            layoutSafeSurface();
        }
        releaseVirtualDisplay();
        if (texture != null && texture.isAvailable()) scheduleVirtualDisplay(virtualGeneration);
    }

    /** Handles HyperOS REORDER_TO_FRONT paths that resume without delivering onNewIntent(). */
    private void applyPendingSafeLaunchIfChanged() {
        ActionConfig pending = ActionStore.getPendingSafeLaunch(this);
        if (pending == null || !applyLaunchConfig(pending)) return;
        restartForChangedLaunch();
    }

    private void layoutSafeSurface() {
        int width = root.getWidth();
        int height = root.getHeight();
        if (width <= 0 || height <= 0) return;
        int previousWidth = virtualWidth;
        int previousHeight = virtualHeight;
        int safeLeft = 0;
        WindowInsets insets = root.getRootWindowInsets();
        DisplayCutout cutout = insets == null ? null : insets.getDisplayCutout();
        if (cutout != null) safeLeft = cutout.getSafeInsetLeft();
        if (safeLeft == 0 && width > 750) safeLeft = Math.round(width * 296f / 976f);
        physicalWidth = width - safeLeft;
        physicalHeight = height;
        topCropPx = Math.min(topCropPx, height - 100);
        virtualHeight = physicalHeight + topCropPx;
        // Overscan vertically at native pixel size. A portrait target stays portrait;
        // no horizontal pixels are thrown away to fake a landscape aspect ratio.
        virtualWidth = physicalWidth;
        int sideCrop = 0;

        FrameLayout.LayoutParams viewportParams = new FrameLayout.LayoutParams(
                physicalWidth, physicalHeight);
        viewportParams.leftMargin = safeLeft;
        viewport.setLayoutParams(viewportParams);
        FrameLayout.LayoutParams confirmationParams = new FrameLayout.LayoutParams(
                physicalWidth - dp(28), -2, Gravity.BOTTOM | Gravity.LEFT);
        confirmationParams.leftMargin = safeLeft + dp(14);
        confirmationParams.bottomMargin = dp(28);
        homeConfirmationHint.setLayoutParams(confirmationParams);
        FrameLayout.LayoutParams previewParams = new FrameLayout.LayoutParams(
                physicalWidth - dp(14), physicalHeight - dp(12));
        previewParams.leftMargin = safeLeft + dp(7);
        previewParams.topMargin = dp(6);
        previewCard.setLayoutParams(previewParams);
        previewLabels.setTranslationY(physicalHeight * .5f);
        FrameLayout.LayoutParams textureParams = new FrameLayout.LayoutParams(
                virtualWidth, virtualHeight);
        textureParams.leftMargin = -sideCrop;
        textureParams.topMargin = -topCropPx;
        texture.setLayoutParams(textureParams);
        if (virtualDisplay != null && (virtualWidth != previousWidth
                || virtualHeight != previousHeight)) {
            if (texture.isAvailable()) {
                texture.getSurfaceTexture().setDefaultBufferSize(virtualWidth, virtualHeight);
            }
            virtualDisplay.resize(virtualWidth, virtualHeight, virtualDensityDpi);
        }

        int cornerRadius = dp(34);
        if (insets != null) {
            RoundedCorner corner = insets.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT);
            if (corner != null) cornerRadius = corner.getRadius();
        }
        final int clipRadius = cornerRadius;
        viewport.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                // The left edge is an internal camera-island boundary, not an outer corner.
                Path mask = new Path();
                mask.addRoundRect(new RectF(0, 0, view.getWidth(), view.getHeight()),
                        new float[]{0, 0, clipRadius, clipRadius,
                                clipRadius, clipRadius, 0, 0}, Path.Direction.CW);
                outline.setPath(mask);
            }
        });
        viewport.invalidateOutline();
        Log.i(TAG, "physical viewport=" + safeLeft + ",0-" + width + "," + height
                + " virtual=" + virtualWidth + "x" + virtualHeight
                + " cropTop=" + topCropPx + " sideCrop=" + sideCrop
                + " rounded=" + cornerRadius);
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture holder, int width, int height) {
        if (outputSurface != null) outputSurface.release();
        outputSurface = new Surface(holder);
        scheduleVirtualDisplay(virtualGeneration);
    }

    private void scheduleVirtualDisplay(int generation) {
        root.postDelayed(() -> startOrAttachVirtualDisplay(generation), 200);
    }

    private void startOrAttachVirtualDisplay(int generation) {
        if (generation != virtualGeneration || isFinishing() || isDestroyed()
                || texture == null || !texture.isAvailable()) return;
        if (virtualWidth <= 0 || virtualHeight <= 0) layoutSafeSurface();
        if (virtualWidth <= 0 || virtualHeight <= 0) return;
        SurfaceTexture holder = texture.getSurfaceTexture();
        if (holder == null) return;
        holder.setDefaultBufferSize(virtualWidth, virtualHeight);
        if (outputSurface == null) outputSurface = new Surface(holder);
        if (virtualDisplay != null) {
            // The OEM temporarily destroys TextureView while rear AOD/home owns the panel.
            // Reattach the existing single VD; never relaunch its app here.
            virtualDisplay.setSurface(outputSurface);
            Log.i(TAG, "reattached virtual display="
                    + virtualDisplay.getDisplay().getDisplayId());
            return;
        }
        DisplayManager dm = getSystemService(DisplayManager.class);
            // Hidden SDK constant VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL.
            // Without it, Android moves a PUBLIC virtual display's live app to
            // the main/front screen when this host is dismissed by Home.
            final int destroyContentOnRemoval = 1 << 8;
            int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                    | destroyContentOnRemoval;
            try {
                virtualDisplay = dm.createVirtualDisplay("BackDeckEmbedded", virtualWidth,
                        virtualHeight, virtualDensityDpi,
                        outputSurface, flags);
                int id = virtualDisplay == null ? -1 : virtualDisplay.getDisplay().getDisplayId();
                if (generation != virtualGeneration) {
                    // A newer drawer selection arrived while createVirtualDisplay was running.
                    // Drop this orphan before it can receive the stale target launch.
                    releaseVirtualDisplay();
                    scheduleVirtualDisplay(virtualGeneration);
                    return;
                }
                Log.i(TAG, "virtual display id=" + id + " densityDpi=" + virtualDensityDpi);
                if (badge != null) badge.setText("VIRTUAL DISPLAY " + id);
                if (id >= 2) {
                    inputClient = new RootInputClient(this, id, virtualWidth, virtualHeight);
                    inputClient.startAsync();
                    if (target != null) {
                        taskWatcher = new VirtualTaskWatcher(id, reason -> {
                            Log.i(TAG, "returning to rear dashboard: " + reason);
                            // Back/exit inside the embedded app is a real session end.  Do
                            // not retain an empty VD behind clock, otherwise the next drawer
                            // tap would only revive a blank host.
                            animateReturnHome(true);
                        });
                        taskWatcher.start();
                    }
                }
                if (target != null && id >= 2) ActionLauncher.launchInVirtual(this, target, id);
            } catch (Exception e) {
                Log.e(TAG, "createVirtualDisplay failed", e);
                if (badge != null) badge.setText("VIRTUAL DISPLAY FAILED: " + e.getClass().getSimpleName());
            }
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture holder, int width, int height) {
        if (virtualWidth > 0 && virtualHeight > 0) {
            holder.setDefaultBufferSize(virtualWidth, virtualHeight);
        }
    }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture holder) {
        if (virtualDisplay != null && !isFinishing() && !isDestroyed()) {
            try { virtualDisplay.setSurface(null); }
            catch (RuntimeException error) {
                Log.w(TAG, "could not detach virtual surface", error);
                releaseVirtualDisplay();
            }
            Log.i(TAG, "detached surface; retained virtual display="
                    + virtualDisplay.getDisplay().getDisplayId());
        } else {
            releaseVirtualDisplay();
        }
        if (outputSurface != null) outputSurface.release();
        outputSurface = null;
        return true;
    }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture holder) { }

    @Override protected void onResume() {
        super.onResume();
        // FLAG_ACTIVITY_REORDER_TO_FRONT is allowed to resume an existing activity without
        // delivering a new Intent on some OEM builds. Read the explicit hand-off so changes
        // such as 144 px -> 0 px take effect even for the retained same-app session.
        applyPendingSafeLaunchIfChanged();
        if (returningHome) restoreRetainedHost();
    }

    @Override protected void onDestroy() {
        touchHandler.removeCallbacks(flushHeldBottomDown);
        touchHandler.removeCallbacks(expireHomeConfirmation);
        releaseHeldBottomDown();
        if (viewport != null) viewport.animate().cancel();
        if (returnPreview != null) returnPreview.animate().cancel();
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        }
        releaseVirtualDisplay();
        if (outputSurface != null) outputSurface.release();
        outputSurface = null;
        inputWorker.shutdownNow();
        super.onDestroy();
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        root.post(this::layoutSafeSurface);
    }

    @Override public void onBackPressed() {
        forwardBack();
    }

    private void forwardBack() {
        int id = virtualDisplay == null ? -1 : virtualDisplay.getDisplay().getDisplayId();
        Log.i(TAG, "physical back gesture forwarded to virtual display=" + id);
        if (id < 2) { finish(); return; }
        RootInputClient client = inputClient;
        if (client != null && client.isReady()) client.sendBack();
        else inputWorker.execute(() -> RootBridge.injectBack(id));
        if (taskWatcher != null) taskWatcher.onBackForwarded();
    }

    private void releaseVirtualDisplay() {
        if (taskWatcher != null) taskWatcher.close();
        taskWatcher = null;
        if (inputClient != null) inputClient.close();
        inputClient = null;
        if (virtualDisplay != null) virtualDisplay.release();
        virtualDisplay = null;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + .5f);
    }

    private void createReturnPreview() {
        returnPreview = new FrameLayout(this);
        returnPreview.setBackgroundColor(Color.rgb(7, 10, 15));
        returnPreview.setAlpha(0f);
        previewCard = new FrameLayout(this);
        GradientDrawable panel = new GradientDrawable();
        panel.setColor(Color.rgb(16, 21, 28));
        panel.setCornerRadius(dp(17));
        previewCard.setBackground(panel);
        returnPreview.addView(previewCard);
        previewLabels = new LinearLayout(this);
        previewLabels.setOrientation(LinearLayout.VERTICAL);
        previewLabels.setGravity(Gravity.CENTER);
        previewTime = new TextView(this);
        previewTime.setGravity(Gravity.CENTER);
        previewTime.setTextColor(Color.rgb(244, 247, 250));
        previewTime.setTextSize(48);
        previewTime.setTypeface(android.graphics.Typeface.create(
                "sans-serif-light", android.graphics.Typeface.NORMAL));
        previewLabels.addView(previewTime);
        previewDate = new TextView(this);
        previewDate.setGravity(Gravity.CENTER);
        previewDate.setTextColor(Color.rgb(150, 163, 177));
        previewDate.setTextSize(12);
        previewLabels.addView(previewDate);
        TextView hint = new TextView(this);
        hint.setText("背屏助手");
        hint.setTextColor(Color.rgb(109, 229, 184));
        hint.setTextSize(10);
        hint.setGravity(Gravity.CENTER);
        previewLabels.addView(hint);
        previewCard.addView(previewLabels, new FrameLayout.LayoutParams(-1, -1));
        root.addView(returnPreview, new FrameLayout.LayoutParams(-1, -1));
        updatePreviewClock();
    }

    private void createHomeConfirmationHint() {
        homeConfirmationHint = new TextView(this);
        homeConfirmationHint.setText("再上滑一次返回表盘");
        homeConfirmationHint.setTextColor(Color.WHITE);
        homeConfirmationHint.setTextSize(12);
        homeConfirmationHint.setGravity(Gravity.CENTER);
        homeConfirmationHint.setPadding(dp(12), dp(7), dp(12), dp(7));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(220, 23, 31, 40));
        background.setCornerRadius(dp(16));
        homeConfirmationHint.setBackground(background);
        homeConfirmationHint.setClickable(false);
        homeConfirmationHint.setFocusable(false);
        homeConfirmationHint.setVisibility(View.GONE);
        root.addView(homeConfirmationHint);
    }

    private void armHomeConfirmation() {
        homeConfirmationDeadline = SystemClock.uptimeMillis() + 3000L;
        homeConfirmationHint.setVisibility(View.VISIBLE);
        touchHandler.removeCallbacks(expireHomeConfirmation);
        touchHandler.postDelayed(expireHomeConfirmation, 3000L);
    }

    private void clearHomeConfirmation() {
        homeConfirmationDeadline = 0L;
        touchHandler.removeCallbacks(expireHomeConfirmation);
        if (homeConfirmationHint != null) homeConfirmationHint.setVisibility(View.GONE);
    }

    private void updatePreviewClock() {
        Date now = new Date();
        previewTime.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now));
        previewDate.setText(new SimpleDateFormat("M月d日  EEEE", Locale.CHINA).format(now));
    }

    private void showReturnProgress(float distance) {
        float height = Math.max(1, physicalHeight);
        float progress = Math.min(1f, Math.max(0f, distance / height));
        float reveal = Math.min(height, Math.max(0f, distance));
        viewport.setTranslationY(-reveal);
        // Keep rendered app pixels opaque; fading the live surface makes both the app and
        // clock preview show through each other, which looks like blur/ghosting on this panel.
        viewport.setAlpha(1f);
        // Centre the clock in the portion already uncovered by the user's finger.
        previewLabels.setTranslationY((height - reveal) * .5f);
        returnPreview.setAlpha(Math.min(1f, progress * 2.2f));
    }

    private void springBack() {
        viewport.animate().translationY(0f).alpha(1f).setDuration(210).start();
        returnPreview.animate().alpha(0f).setDuration(210).start();
        previewLabels.animate().translationY(Math.max(1, physicalHeight) * .5f)
                .setDuration(210).start();
    }

    /** Reset the hand-off animation when this retained host is reordered back over clock. */
    private void restoreRetainedHost() {
        returningHome = false;
        discardSessionOnReturn = false;
        clearHomeConfirmation();
        touchHandler.removeCallbacks(flushHeldBottomDown);
        releaseHeldBottomDown();
        if (viewport != null) {
            viewport.animate().cancel();
            viewport.setTranslationY(0f);
            viewport.setAlpha(1f);
        }
        if (returnPreview != null) {
            returnPreview.animate().cancel();
            returnPreview.setAlpha(0f);
        }
        if (previewLabels != null) {
            previewLabels.animate().cancel();
            previewLabels.setTranslationY(Math.max(1, physicalHeight) * .5f);
        }
        Log.i(TAG, "resumed retained safe-area host: " + targetIdentity);
    }

    /**
     * Put the existing dashboard above this activity rather than finishing this host.
     * The retained virtual display keeps the target's one rear-only task alive, so reopening
     * from the drawer resumes it instead of creating an identical task in Recents.
     */
    private void returnToDashboard() {
        android.content.Intent dashboard = new android.content.Intent(this, RearActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            android.app.ActivityOptions options = android.app.ActivityOptions.makeBasic();
            options.setLaunchDisplayId(getDisplay().getDisplayId());
            startActivity(dashboard, options.toBundle());
            overridePendingTransition(0, 0);
        } catch (RuntimeException error) {
            // Do not leave a black/translated host if the OEM rejects a same-display reorder.
            Log.w(TAG, "could not reorder dashboard over virtual host", error);
            finish();
            overridePendingTransition(0, 0);
        }
    }

    private void animateReturnHome() {
        animateReturnHome(false);
    }

    /**
     * @param discardSession false for the assistant's own bottom-up Home gesture, true when
     *                       the target app has actually exited from the virtual display.
     */
    private void animateReturnHome(boolean discardSession) {
        if (returningHome || isFinishing()) return;
        returningHome = true;
        discardSessionOnReturn = discardSession;
        clearHomeConfirmation();
        touchHandler.removeCallbacks(flushHeldBottomDown);
        releaseHeldBottomDown();
        updatePreviewClock();
        viewport.animate().cancel();
        returnPreview.animate().cancel();
        previewLabels.animate().cancel();
        viewport.animate().translationY(-Math.max(1, physicalHeight)).alpha(1f)
                .setDuration(220).withEndAction(() -> {
                    if (!returningHome) return;
                    if (discardSessionOnReturn) {
                        finish();
                        overridePendingTransition(0, 0);
                    } else {
                        returnToDashboard();
                    }
                }).start();
        returnPreview.animate().alpha(1f).setDuration(200).start();
        previewLabels.animate().translationY(0f).setDuration(220).start();
    }

    private void releaseHeldBottomDown() {
        if (heldBottomDown != null) {
            heldBottomDown.recycle();
            heldBottomDown = null;
        }
        bottomHomeCandidate = false;
        bottomHomeActive = false;
    }

    private boolean forwardHeldBottomDown() {
        MotionEvent down = heldBottomDown;
        heldBottomDown = null;
        if (down == null) return false;
        boolean sent = inputClient != null && inputClient.sendMotion(
                down, texture.getWidth(), texture.getHeight());
        down.recycle();
        return sent;
    }

    private void injectFallbackTouch(MotionEvent event) {
        if (virtualDisplay == null) return;
        int id = virtualDisplay.getDisplay().getDisplayId();
        int x1 = Math.max(0, Math.min(virtualWidth - 1, Math.round(downX)));
        int y1 = Math.max(0, Math.min(virtualHeight - 1, Math.round(downY)));
        int x2 = Math.max(0, Math.min(virtualWidth - 1, Math.round(event.getX())));
        int y2 = Math.max(0, Math.min(virtualHeight - 1, Math.round(event.getY())));
        long duration = Math.max(80, event.getEventTime() - downTime);
        inputWorker.execute(() -> RootBridge.injectTouch(id, x1, y1, x2, y2, duration));
    }

    private boolean handleTouch(MotionEvent event) {
        if (virtualDisplay == null || returningHome) return true;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            viewport.animate().cancel();
            returnPreview.animate().cancel();
            previewLabels.animate().cancel();
            viewport.setTranslationY(0f);
            viewport.setAlpha(1f);
            returnPreview.setAlpha(0f);
            previewLabels.setTranslationY(Math.max(1, physicalHeight) * .5f);
            downX = event.getX();
            downY = event.getY();
            downRawX = event.getRawX();
            downRawY = event.getRawY();
            previousRawY = downRawY;
            previousEventTime = event.getEventTime();
            upwardVelocity = 0f;
            downTime = event.getEventTime();
            // Reserve only the lowest strip of the camera-safe viewport for Home.
            // Everywhere else, the persistent ROOT pipe still receives DOWN now.
            bottomHomeCandidate = downY >= texture.getHeight() - dp(58);
            bottomHomeActive = false;
            if (bottomHomeCandidate) {
                heldBottomDown = MotionEvent.obtain(event);
                touchHandler.postDelayed(flushHeldBottomDown, 250);
                edgeBackCandidate = false;
                streamedGesture = false;
                return true;
            }
            // Xiaomi's former right-edge overlay reserved roughly 62 physical pixels
            // for Back. Once its touch window is made transparent, handle that same
            // gesture here instead of delivering it as an app swipe.
            edgeBackCandidate = downX >= texture.getWidth() - Math.max(62, dp(22));
            streamedGesture = !edgeBackCandidate && inputClient != null && inputClient.isReady();
        }
        if (bottomHomeCandidate || bottomHomeActive) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_CANCEL) {
                touchHandler.removeCallbacks(flushHeldBottomDown);
                boolean wasActive = bottomHomeActive;
                releaseHeldBottomDown();
                if (wasActive) springBack();
                return true;
            }
            long interval = event.getEventTime() - previousEventTime;
            if (interval > 0 && action != MotionEvent.ACTION_DOWN) {
                float sample = (event.getRawY() - previousRawY) * 1000f / interval;
                upwardVelocity = upwardVelocity * .35f + sample * .65f;
                previousRawY = event.getRawY();
                previousEventTime = event.getEventTime();
            }
            float rise = downRawY - event.getRawY();
            float sideways = Math.abs(event.getRawX() - downRawX);
            if (!bottomHomeActive && action == MotionEvent.ACTION_MOVE
                    && rise > dp(9) && rise > sideways * 1.15f) {
                touchHandler.removeCallbacks(flushHeldBottomDown);
                if (heldBottomDown != null) {
                    heldBottomDown.recycle();
                    heldBottomDown = null;
                }
                bottomHomeCandidate = false;
                bottomHomeActive = true;
                updatePreviewClock();
            }
            if (bottomHomeActive) {
                if (action == MotionEvent.ACTION_MOVE) {
                    showReturnProgress(rise);
                } else if (action == MotionEvent.ACTION_UP) {
                    bottomHomeActive = false;
                    float distance = Math.max(0f, rise);
                    if (distance >= physicalHeight * .27f
                            || (distance >= dp(58) && upwardVelocity < -750f)) {
                        Log.i(TAG, "bottom Home gesture: distance=" + distance
                                + " velocity=" + upwardVelocity);
                        if (confirmReturnHome
                                && SystemClock.uptimeMillis() > homeConfirmationDeadline) {
                            // The first complete swipe is deliberately consumed. A short or
                            // cancelled swipe above does not arm the confirmation window.
                            armHomeConfirmation();
                            springBack();
                        } else {
                            animateReturnHome();
                        }
                    } else {
                        springBack();
                    }
                }
                return true;
            }
            if (action == MotionEvent.ACTION_POINTER_DOWN
                    || (action == MotionEvent.ACTION_MOVE
                    && (sideways > dp(12) || rise < -dp(12)))) {
                // Not a bottom-up Home swipe: deliver the held DOWN and this sample
                // to the virtual app, then stream subsequent samples without delay.
                touchHandler.removeCallbacks(flushHeldBottomDown);
                bottomHomeCandidate = false;
                streamedGesture = forwardHeldBottomDown();
                if (streamedGesture && inputClient != null) {
                    streamedGesture = inputClient.sendMotion(
                            event, texture.getWidth(), texture.getHeight());
                }
                return true;
            }
            if (action == MotionEvent.ACTION_UP) {
                touchHandler.removeCallbacks(flushHeldBottomDown);
                bottomHomeCandidate = false;
                streamedGesture = forwardHeldBottomDown();
                if (!streamedGesture || inputClient == null || !inputClient.sendMotion(
                        event, texture.getWidth(), texture.getHeight())) {
                    injectFallbackTouch(event);
                }
                streamedGesture = false;
                return true;
            }
            return true;
        }
        if (edgeBackCandidate) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_CANCEL) {
                edgeBackCandidate = false;
                return true;
            }
            if (action != MotionEvent.ACTION_UP) return true;
            edgeBackCandidate = false;
            float dx = event.getX() - downX;
            float dy = event.getY() - downY;
            if (dx < -dp(18) && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                forwardBack();
                return true;
            }
            // Edge taps still belong to the target app. They need no gesture stream.
            int edgeDisplayId = virtualDisplay.getDisplay().getDisplayId();
            int ex1 = Math.max(0, Math.min(virtualWidth - 1, Math.round(downX)));
            int ey1 = Math.max(0, Math.min(virtualHeight - 1, Math.round(downY)));
            int ex2 = Math.max(0, Math.min(virtualWidth - 1, Math.round(event.getX())));
            int ey2 = Math.max(0, Math.min(virtualHeight - 1, Math.round(event.getY())));
            long edgeDuration = Math.max(80, event.getEventTime() - downTime);
            inputWorker.execute(() -> RootBridge.injectTouch(edgeDisplayId,
                    ex1, ey1, ex2, ey2, edgeDuration));
            return true;
        }
        if (streamedGesture && inputClient != null) {
            if (inputClient.sendMotion(event, texture.getWidth(), texture.getHeight())) {
                if (event.getActionMasked() == MotionEvent.ACTION_UP
                        || event.getActionMasked() == MotionEvent.ACTION_CANCEL) streamedGesture = false;
                return true;
            }
            streamedGesture = false;
            inputClient.cancelGesture();
        }
        if (event.getActionMasked() != MotionEvent.ACTION_UP) return true;
        injectFallbackTouch(event);
        return true;
    }
}

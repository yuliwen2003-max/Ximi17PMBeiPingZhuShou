package com.seagull.backdeck;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.view.DisplayCutout;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.VelocityTracker;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** A real Activity on the physical secondary display, not a screen mock-up. */
public final class RearActivity extends Activity {
    // The camera island is physical black. Match it so the actual rear surface reads as one display.
    private static final int BG = Color.BLACK;
    private static final int PANEL = Color.rgb(16, 21, 28);
    private static final int WHITE = Color.rgb(244, 247, 250);
    private static final int MUTED = Color.rgb(150, 163, 177);
    private static final int GREEN = Color.rgb(109, 229, 184);

    private GestureRoot root;
    private Dashboard dashboard;
    private View drawer;
    private ScrollView drawerScroll;
    private ValueAnimator drawerAnimator;
    private float drawerProgress;
    private OnBackInvokedCallback backCallback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (dashboard != null) dashboard.invalidate();
            handler.postDelayed(this, 10_000);
        }
    };
    private int battery = -1;
    private int wifiLevel = -1;
    private int cellLevel = -1;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // The rear clock/host may stay visible at the keyguard; it never dismisses it.
        setShowWhenLocked(true);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        // The rear panel is an independent power group.  Without this flag the clock page
        // can enter DOZE_SUSPEND after the front screen sleeps, at which point HyperOS resumes
        // SubScreenLauncher/AOD over it.  VirtualHost already carries the same flag.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getAttributes().layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        getWindow().setNavigationBarColor(BG);
        getWindow().setStatusBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        root = new GestureRoot(this);
        root.setBackgroundColor(BG);
        dashboard = new Dashboard(this);
        root.addView(dashboard, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = this::handleBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        refreshStatus();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    @Override public void onBackPressed() {
        handleBack();
    }

    private void handleBack() {
        if (drawer != null) hideDrawer();
        // The clock is the rear-screen home. Back must not reveal Xiaomi's launcher.
    }

    @Override protected void onDestroy() {
        if (drawerAnimator != null) drawerAnimator.cancel();
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        }
        super.onDestroy();
    }

    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }

    private int safeLeft() {
        int width = root == null ? 0 : root.getWidth();
        if (width < dp(275)) return 0; // already cropped by window manager
        int fromCutout = 0;
        WindowInsets insets = root.getRootWindowInsets();
        if (insets != null) {
            DisplayCutout cutout = insets.getDisplayCutout();
            if (cutout != null) {
                for (Rect r : cutout.getBoundingRects()) {
                    if (r.left <= width / 8 && r.height() > root.getHeight() / 2)
                        fromCutout = Math.max(fromCutout, r.right);
                }
            }
        }
        // This device's full-height camera island occupies the left 296 of 976 pixels.
        return Math.max(fromCutout, (int) (width * .303f));
    }

    private void refreshStatus() {
        BatteryManager bm = getSystemService(BatteryManager.class);
        battery = bm == null ? -1 : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        wifiLevel = -1;
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            Network network = cm == null ? null : cm.getActiveNetwork();
            NetworkCapabilities caps = network == null ? null : cm.getNetworkCapabilities(network);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                WifiManager wm = getSystemService(WifiManager.class);
                WifiInfo info = wm == null ? null : wm.getConnectionInfo();
                wifiLevel = info == null ? 2 : WifiManager.calculateSignalLevel(info.getRssi(), 5);
                wifiLevel = Math.max(0, Math.min(4, wifiLevel));
            }
        } catch (Exception ignored) { wifiLevel = -1; }
        cellLevel = -1;
        try {
            if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                    == PackageManager.PERMISSION_GRANTED) {
                TelephonyManager tm = getSystemService(TelephonyManager.class);
                SignalStrength signal = tm == null ? null : tm.getSignalStrength();
                if (signal != null) cellLevel = Math.max(0, Math.min(4, signal.getLevel()));
            }
        } catch (Exception ignored) { cellLevel = -1; }
        if (dashboard != null) dashboard.invalidate();
    }

    private void perform(String key) {
        ActionConfig config = ActionStore.get(this, key);
        if (config == null || !config.enabled) {
            Toast.makeText(this, "这个手势还没设置", Toast.LENGTH_SHORT).show();
            return;
        }
        launchConfigured(config);
    }

    private void launchConfigured(ActionConfig config) {
        if (config.displayId != 2) {
            ActionLauncher.launch(this, config);
            return;
        }
        String launchKey = "virtual-host:" + config.type + ':' + config.value;
        if (!ActionLauncher.reserveLaunch(launchKey)) return;
        // Persist before reordering the retained host. HyperOS can resume it without
        // delivering onNewIntent(), and this carries an explicit 0 px crop through that path.
        ActionStore.setPendingSafeLaunch(this, config);
        Intent host = new Intent(this, VirtualHostActivity.class);
        // Keep one host/virtual-display session behind the clock.  After a bottom-up
        // return, RearActivity is reordered above this host; reordering the host back to
        // front resumes the same target task rather than making a second app task.
        // CLEAR_TOP would destroy the clock beneath the host and turn every reopen into a
        // fresh virtual display/task.
        host.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        host.putExtra("target_type", config.type);
        host.putExtra("target_value", config.value);
        host.putExtra("target_label", config.label);
        host.putExtra("virtual_dpi", ActionConfig.clampVirtualDpi(config.virtualDpi));
        host.putExtra("virtual_top_crop_px", ActionConfig.clampVirtualTopCropPx(config.virtualTopCropPx));
        host.putExtra("confirm_return_home", config.confirmReturnHome);
        try {
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchDisplayId(getDisplay().getDisplayId());
            startActivity(host, options.toBundle());
            ActionLauncher.finishLaunch(launchKey, true);
        } catch (Exception e) {
            ActionLauncher.finishLaunch(launchKey, false);
            Toast.makeText(this, "虚拟屏宿主启动失败", Toast.LENGTH_SHORT).show();
        }
    }

    private final class Dashboard extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final GestureDetector gestures;
        Dashboard(Context context) {
            super(context);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent e) { return true; }
                @Override public void onLongPress(MotionEvent e) { perform("long_press"); }
                @Override public boolean onDoubleTap(MotionEvent e) {
                    perform("double_tap"); return true;
                }
                @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                    if (a == null || b == null) return false;
                    float dx = b.getX() - a.getX();
                    float dy = b.getY() - a.getY();
                    if (Math.abs(dx) > dp(45) && Math.abs(dx) > Math.abs(dy) * 1.25f) {
                        perform(dx < 0 ? "swipe_left" : "swipe_right"); return true;
                    }
                    return false;
                }
            });
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            return gestures.onTouchEvent(event) || super.onTouchEvent(event);
        }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            int left = safeLeft();
            float cx = left + (getWidth() - left) / 2f;
            float h = getHeight();
            c.drawColor(BG);
            // No panel: the clock surface must remain pure black.

            Date now = new Date();
            String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now);
            String date = new SimpleDateFormat("M月d日  EEEE", Locale.CHINA).format(now);
            p.setColor(WHITE); p.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            p.setTextAlign(Paint.Align.CENTER); p.setTextSize(dp(48));
            c.drawText(time, cx, h * .44f, p);
            p.setColor(MUTED); p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            p.setTextSize(dp(12)); c.drawText(date, cx, h * .57f, p);

            // The clock page contains nothing except time, date and system status.
            // Gesture handling remains on this view; it deliberately has no visible hints.
            float statusY = h * .69f;
            drawWifi(c, cx - dp(39), statusY, wifiLevel);
            drawCellular(c, cx - dp(7), statusY, cellLevel);
            drawBattery(c, cx + dp(25), statusY, battery);
        }

        private void drawWifi(Canvas canvas, float centerX, float centerY, int level) {
            float outer = dp(11);
            float middle = dp(8);
            float inner = dp(5);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(dp(1.5f));
            drawWifiArc(canvas, centerX, centerY, outer, level >= 4, level >= 0);
            drawWifiArc(canvas, centerX, centerY, middle, level >= 3, level >= 0);
            drawWifiArc(canvas, centerX, centerY, inner, level >= 2, level >= 0);
            p.setStyle(Paint.Style.FILL);
            p.setColor(level >= 1 ? WHITE : MUTED);
            p.setAlpha(level < 0 ? 105 : 255);
            canvas.drawCircle(centerX, centerY + dp(3), dp(1.8f), p);
            p.setAlpha(255);
        }

        private void drawWifiArc(Canvas canvas, float centerX, float centerY, float radius,
                                 boolean active, boolean known) {
            p.setColor(active ? WHITE : MUTED);
            p.setAlpha(known ? 255 : 105);
            RectF bounds = new RectF(centerX - radius, centerY - radius,
                    centerX + radius, centerY + radius);
            canvas.drawArc(bounds, 225f, 90f, false, p);
        }

        private void drawCellular(Canvas canvas, float centerX, float centerY, int level) {
            float barWidth = dp(3);
            float gap = dp(2);
            float base = centerY + dp(4);
            p.setStyle(Paint.Style.FILL);
            for (int i = 0; i < 4; i++) {
                p.setColor(level > i ? WHITE : MUTED);
                p.setAlpha(level < 0 ? 105 : 255);
                float height = dp(4 + i * 3);
                float x = centerX + i * (barWidth + gap);
                canvas.drawRoundRect(new RectF(x, base - height, x + barWidth, base),
                        dp(.8f), dp(.8f), p);
            }
            p.setAlpha(255);
        }

        private void drawBattery(Canvas canvas, float left, float centerY, int percent) {
            float width = dp(18);
            float height = dp(9);
            float top = centerY - height / 2f;
            p.setStrokeCap(Paint.Cap.SQUARE);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1.2f));
            p.setColor(percent >= 0 ? WHITE : MUTED);
            p.setAlpha(percent < 0 ? 105 : 255);
            canvas.drawRoundRect(new RectF(left, top, left + width, top + height),
                    dp(1.5f), dp(1.5f), p);
            p.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(new RectF(left + width, top + height * .28f,
                    left + width + dp(2), top + height * .72f), dp(.5f), dp(.5f), p);
            if (percent >= 0) {
                float fill = Math.max(0f, Math.min(1f, percent / 100f));
                p.setColor(WHITE);
                canvas.drawRoundRect(new RectF(left + dp(1.5f), top + dp(1.5f),
                        left + dp(1.5f) + (width - dp(3)) * fill, top + height - dp(1.5f)),
                        dp(.7f), dp(.7f), p);
            }
            p.setAlpha(255);
            p.setColor(percent >= 0 ? WHITE : MUTED);
            p.setTextAlign(Paint.Align.LEFT);
            p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            p.setTextSize(dp(9));
            canvas.drawText(percent >= 0 ? percent + "%" : "--",
                    left + width + dp(5), centerY + dp(3), p);
        }
    }

    /** Intercepts only vertical drawer drags; taps and configured horizontal gestures stay intact. */
    private final class GestureRoot extends FrameLayout {
        private final int touchSlop;
        private final int minFlingVelocity;
        private float downX;
        private float downY;
        private float startProgress;
        private boolean dragging;
        private boolean startedOpen;
        private boolean scrollAtTopWhenDown;
        private VelocityTracker velocity;

        GestureRoot(Context context) {
            super(context);
            ViewConfiguration vc = ViewConfiguration.get(context);
            touchSlop = vc.getScaledTouchSlop();
            minFlingVelocity = Math.max(vc.getScaledMinimumFlingVelocity(), dp(300));
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    dragging = false;
                    scrollAtTopWhenDown = drawerScroll == null
                            || !drawerScroll.canScrollVertically(-1);
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (dragging) return true;
                    float dx = event.getX() - downX;
                    float dy = event.getY() - downY;
                    if (Math.abs(dy) <= touchSlop || Math.abs(dy) <= Math.abs(dx) * 1.15f)
                        return false;
                    if (drawer == null && dy < 0) {
                        beginDrag(event, false);
                        return true;
                    }
                    if (drawer != null && dy > 0
                            && (drawerScroll == null || !drawerScroll.canScrollVertically(-1))) {
                        beginDrag(event, true);
                        return true;
                    }
                    return false;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    releaseVelocity();
                    return false;
                default:
                    return false;
            }
        }

        private void beginDrag(MotionEvent event, boolean closing) {
            if (drawerAnimator != null) drawerAnimator.cancel();
            if (drawer == null) createDrawer();
            dragging = true;
            startedOpen = closing;
            startProgress = drawerProgress;
            // A nested ScrollView may reach its top during this same gesture. Do not
            // count the distance it already consumed as drawer travel.
            if (closing && !scrollAtTopWhenDown) downY = event.getY();
            velocity = VelocityTracker.obtain();
            velocity.addMovement(event);
            setDrawerProgress(startProgress - (event.getY() - downY) / travelDistance());
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (!dragging) return true;
            int action = event.getActionMasked();
            if (velocity != null) velocity.addMovement(event);
            if (action == MotionEvent.ACTION_MOVE) {
                setDrawerProgress(startProgress - (event.getY() - downY) / travelDistance());
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                float vy = 0;
                if (velocity != null) {
                    velocity.computeCurrentVelocity(1000);
                    vy = velocity.getYVelocity();
                }
                dragging = false;
                releaseVelocity();
                if (action == MotionEvent.ACTION_CANCEL) {
                    animateDrawerTo(startedOpen ? 1f : 0f, null);
                } else {
                    boolean fling = Math.abs(vy) >= minFlingVelocity;
                    float target = fling ? (vy < 0 ? 1f : 0f)
                            : startedOpen ? (drawerProgress < .8f ? 0f : 1f)
                            : (drawerProgress > .2f ? 1f : 0f);
                    animateDrawerTo(target, null);
                }
                return true;
            }
            return true;
        }

        private void releaseVelocity() {
            if (velocity != null) velocity.recycle();
            velocity = null;
        }
    }

    private float travelDistance() { return Math.max(1, root.getHeight()); }

    private void setDrawerProgress(float value) {
        drawerProgress = Math.max(0f, Math.min(1f, value));
        if (drawer == null) return;
        float distance = travelDistance();
        drawer.setTranslationY(distance * (1f - drawerProgress));
        dashboard.setTranslationY(-distance * .10f * drawerProgress);
        dashboard.setAlpha(1f - .55f * drawerProgress);
    }

    private void animateDrawerTo(float target, Runnable after) {
        if (drawerAnimator != null) drawerAnimator.cancel();
        if (drawer == null) {
            if (after != null) after.run();
            return;
        }
        float start = drawerProgress;
        drawerAnimator = ValueAnimator.ofFloat(start, target);
        drawerAnimator.setDuration((long) (90 + Math.abs(target - start) * 200));
        drawerAnimator.setInterpolator(new DecelerateInterpolator());
        drawerAnimator.addUpdateListener(animation ->
                setDrawerProgress((float) animation.getAnimatedValue()));
        drawerAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(Animator animation) { cancelled = true; }
            @Override public void onAnimationEnd(Animator animation) {
                if (drawerAnimator == animation) drawerAnimator = null;
                if (cancelled) return;
                if (target == 0f) removeDrawer();
                if (after != null) after.run();
            }
        });
        drawerAnimator.start();
    }

    private void removeDrawer() {
        if (drawer != null) root.removeView(drawer);
        drawer = null;
        drawerScroll = null;
        drawerProgress = 0f;
        dashboard.setAlpha(1f);
        dashboard.setTranslationY(0f);
    }

    private void showDrawer() {
        if (drawer == null) createDrawer();
        animateDrawerTo(1f, null);
    }

    private void createDrawer() {
        if (drawer != null) return;
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(BG);
        overlay.setClickable(true);
        int left = safeLeft();
        int availableWidth = root.getWidth() - left;
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(11), dp(10), dp(11), dp(5));
        panel.setBackgroundColor(PANEL);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(availableWidth - dp(14), -1);
        pp.leftMargin = left + dp(7);
        pp.topMargin = dp(6);
        pp.bottomMargin = dp(6);
        overlay.addView(panel, pp);

        TextView header = text("应用抽屉", 14, WHITE);
        header.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        panel.addView(header, new LinearLayout.LayoutParams(-1, dp(24)));
        TextView subtitle = text("点按打开 · 从正屏设置应用和目标屏幕", 8, MUTED);
        panel.addView(subtitle, new LinearLayout.LayoutParams(-1, dp(18)));

        ScrollView scroll = new ScrollView(this);
        drawerScroll = scroll;
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(3);
        scroll.addView(grid);
        List<ActionConfig> apps = drawerApps();
        int cellWidth = Math.max(dp(48), (availableWidth - dp(38)) / 3);
        for (ActionConfig app : apps) grid.addView(appCell(app, cellWidth));
        if (apps.isEmpty()) {
            TextView empty = text("暂无可启动应用，请到正屏设置。", 10, MUTED);
            panel.addView(empty);
        }
        TextView close = text("⌄  返回表盘", 10, GREEN);
        close.setGravity(Gravity.CENTER);
        close.setOnClickListener(v -> hideDrawer());
        panel.addView(close, new LinearLayout.LayoutParams(-1, dp(22)));
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        drawer = overlay;
        setDrawerProgress(0f);
    }

    private List<ActionConfig> drawerApps() {
        List<ActionConfig> chosen = ActionStore.getDrawer(this);
        List<ActionConfig> result = new ArrayList<>();
        for (ActionConfig item : chosen) if (item.enabled) result.add(item);
        return result;
    }

    private View appCell(ActionConfig config, int width) {
        LinearLayout cell = new LinearLayout(this);
        cell.setGravity(Gravity.CENTER);
        cell.setOrientation(LinearLayout.VERTICAL);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = width;
        lp.height = dp(60);
        gridMargins(lp, dp(2));
        cell.setLayoutParams(lp);
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Drawable icon = null;
        try {
            String pkg = config.type.equals("component")
                    ? android.content.ComponentName.unflattenFromString(config.value).getPackageName()
                    : config.value;
            if (config.type.equals("uri")) pkg = "com.android.browser";
            icon = getPackageManager().getApplicationIcon(pkg);
        } catch (Exception ignored) { }
        if (icon != null) image.setImageDrawable(icon);
        else image.setImageResource(android.R.drawable.sym_def_app_icon);
        cell.addView(image, new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView label = text(config.label == null ? config.value : config.label, 9, WHITE);
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(width - dp(5), dp(18));
        tlp.topMargin = dp(2);
        cell.addView(label, tlp);
        cell.setOnClickListener(v -> {
            animateDrawerTo(0f, () -> launchConfigured(config));
        });
        return cell;
    }

    private static void gridMargins(GridLayout.LayoutParams lp, int px) {
        lp.setMargins(px, px, px, px);
    }

    private TextView text(String value, float sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

    private void hideDrawer() {
        animateDrawerTo(0f, null);
    }
}

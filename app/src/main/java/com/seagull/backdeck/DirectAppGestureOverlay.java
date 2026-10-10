package com.seagull.backdeck;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A narrow, display-specific bottom gesture window for user-selected direct-open rear apps.
 * It is absent on the front screen, OEM home, our own hosts, and unselected apps. The first
 * 24 physical pixels are reserved. Aborted swipes are consumed inside this strip rather than
 * replayed: synthetic input would hit Xiaomi's higher-priority Home window once ours detaches.
 */
final class DirectAppGestureOverlay {
    static final String WINDOW_TITLE = "BackDeckDirectHomeGesture";
    private static final String TAG = "BackDeckDirectGesture";
    private static final long POLL_MS = 750;
    private static final long CONFIRM_WINDOW_MS = 3_000;
    private static final Pattern TOP_ACTIVITY = Pattern.compile(
            "topResumedActivity=.*?\\bu\\d+\\s+([A-Za-z_][A-Za-z0-9_.]*)/");

    private final RearTakeoverService service;
    private final Handler main;
    private final ExecutorService worker;
    private final DisplayManager displays;
    private final Runnable poll = this::pollTop;
    private Context windowContext;
    private WindowManager windows;
    private WindowManager.LayoutParams params;
    private GestureView view;
    private int attachedDisplay = Display.INVALID_DISPLAY;
    private int displayHeight;
    private int stripHeight;
    private boolean polling;
    private boolean committing;
    private volatile boolean stopped;
    private long lastWindowError;
    private String lastObservedPackage;
    private boolean lastObservedSelected;
    private String activePackage;
    private boolean confirmReturnHome = true;
    private String pendingConfirmationPackage;
    private long confirmationDeadline;
    private final Runnable confirmationTimeout = () -> {
        clearConfirmation();
        if (view != null && !view.active && view.reveal <= 0) collapse();
    };

    DirectAppGestureOverlay(RearTakeoverService service, Handler main, ExecutorService worker) {
        this.service = service;
        this.main = main;
        this.worker = worker;
        displays = service.getSystemService(DisplayManager.class);
    }

    void start() {
        stopped = false;
        refreshSoon();
    }

    void stop() {
        stopped = true;
        committing = false;
        main.removeCallbacks(poll);
        detach();
    }

    /** Called on the service main thread when the physical panel changes state or ID. */
    void onRearState(int id, int state) {
        if (stopped) return;
        if (state != Display.STATE_ON || (attachedDisplay != Display.INVALID_DISPLAY
                && attachedDisplay != id)) {
            committing = false;
            detach();
        }
        refreshSoon();
    }

    /** Also lets onStartCommand recheck promptly after the front settings grant appops. */
    void refreshSoon() {
        if (stopped) return;
        main.removeCallbacks(poll);
        main.post(poll);
    }

    private void pollTop() {
        if (stopped) return;
        if (polling || committing) {
            scheduleNext();
            return;
        }
        int rearId = DisplayRouting.rearDisplayId(service);
        Display rear = displays == null ? null : displays.getDisplay(rearId);
        if (!RearTakeoverService.isEnabled(service) || rear == null
                || rear.getState() != Display.STATE_ON || !Settings.canDrawOverlays(service)) {
            detach();
            scheduleNext();
            return;
        }
        polling = true;
        worker.execute(() -> {
            String topPackage = readTopPackage(rearId);
            Map<String, Boolean> selectedApps = allowedDirectPackages();
            boolean selected = topPackage != null && selectedApps.containsKey(topPackage);
            boolean requiresConfirmation = selected
                    && Boolean.TRUE.equals(selectedApps.get(topPackage));
            main.post(() -> {
                polling = false;
                if (stopped) return;
                if (!java.util.Objects.equals(activePackage, topPackage)
                        || confirmReturnHome != requiresConfirmation) {
                    clearConfirmation();
                    activePackage = topPackage;
                    confirmReturnHome = requiresConfirmation;
                }
                if (!java.util.Objects.equals(lastObservedPackage, topPackage)
                        || lastObservedSelected != selected) {
                    lastObservedPackage = topPackage;
                    lastObservedSelected = selected;
                    Log.i(TAG, "rear top=" + topPackage + " directSelected=" + selected);
                }
                Display current = displays == null ? null : displays.getDisplay(rearId);
                if (selected && !committing
                        && RearTakeoverService.isEnabled(service)
                        && Settings.canDrawOverlays(service)
                        && current != null && current.getState() == Display.STATE_ON
                        && rearId == DisplayRouting.rearDisplayId(service)) {
                    attach(current);
                } else if (!committing) {
                    detach();
                }
                scheduleNext();
            });
        });
    }

    private void scheduleNext() {
        if (stopped) return;
        main.removeCallbacks(poll);
        main.postDelayed(poll, POLL_MS);
    }

    private Map<String, Boolean> allowedDirectPackages() {
        Map<String, Boolean> selected = new HashMap<>();
        for (String key : new String[]{"long_press", "double_tap", "swipe_left", "swipe_right"}) {
            addDirect(selected, ActionStore.get(service, key));
        }
        // A drawer entry is an explicit per-app choice and wins over a gesture shortcut.
        for (ActionConfig item : ActionStore.getDrawer(service)) addDirect(selected, item);
        return selected;
    }

    private void addDirect(Map<String, Boolean> selected, ActionConfig action) {
        if (action == null || !action.enabled
                || action.displayId != ActionConfig.DISPLAY_REAR_DIRECT
                || action.value == null || action.value.isEmpty()) return;
        String pkg = null;
        if ("package".equals(action.type)) {
            pkg = action.value;
        } else if ("component".equals(action.type)) {
            ComponentName component = ComponentName.unflattenFromString(action.value);
            if (component != null) pkg = component.getPackageName();
        } else if ("uri".equals(action.type)) {
            Uri uri = Uri.parse(action.value);
            String scheme = uri.getScheme();
            if ("weixin".equalsIgnoreCase(scheme)) pkg = "com.tencent.mm";
            else if ("alipay".equalsIgnoreCase(scheme)
                    || "alipays".equalsIgnoreCase(scheme)) pkg = "com.eg.android.AlipayGphone";
            else if (scheme != null) {
                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                List<ResolveInfo> matches = service.getPackageManager().queryIntentActivities(intent, 0);
                if (matches.size() == 1 && matches.get(0).activityInfo != null) {
                    pkg = matches.get(0).activityInfo.packageName;
                }
            }
        }
        if (pkg != null && pkg.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")) {
            selected.put(pkg, action.confirmReturnHome);
        }
    }

    private static String readTopPackage(int displayId) {
        if (displayId == Display.INVALID_DISPLAY) return null;
        String marker = "Display #" + displayId + " (activities from top to bottom):";
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", "dumpsys activity activities | grep -F -A 80 '"
                    + marker + "'").redirectErrorStream(true).start();
            if (!process.waitFor(4, TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroyForcibly();
                return null;
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream stream = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = stream.read(buffer)) != -1) output.write(buffer, 0, count);
            }
            // grep -A can include the following (front) display if this section is short.
            // Never mistake its foreground app for the physical rear foreground app.
            boolean inRearSection = false;
            for (String line : output.toString(StandardCharsets.UTF_8.name()).split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.contains(marker)) {
                    inRearSection = true;
                } else if (inRearSection && trimmed.startsWith("Display #")) {
                    break;
                } else if (inRearSection && trimmed.contains("topResumedActivity=")) {
                    Matcher match = TOP_ACTIVITY.matcher(trimmed);
                    return match.find() ? match.group(1) : null;
                }
            }
            return null;
        } catch (Exception error) {
            if (process != null) process.destroyForcibly();
            return null;
        }
    }

    private void attach(Display rear) {
        int id = rear.getDisplayId();
        if (view != null && attachedDisplay == id) return;
        detach();
        Point size = new Point();
        rear.getRealSize(size);
        if (size.x <= 0 || size.y <= 0) return;
        int safeLeft = 0;
        DisplayCutout cutout = rear.getCutout();
        if (cutout != null) safeLeft = cutout.getSafeInsetLeft();
        if (safeLeft == 0 && size.x > 750) safeLeft = Math.round(size.x * 296f / 976f);
        if (safeLeft >= size.x - 100) return;
        int trigger = Math.max(18, Math.round(size.y * 24f / 596f));
        try {
            Context displayContext = service.createDisplayContext(rear);
            windowContext = displayContext.createWindowContext(
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
            windows = windowContext.getSystemService(WindowManager.class);
            if (windows == null) return;
            displayHeight = size.y;
            stripHeight = trigger;
            params = new WindowManager.LayoutParams(size.x - safeLeft, trigger,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            params.setTitle(WINDOW_TITLE);
            params.gravity = Gravity.LEFT | Gravity.BOTTOM;
            params.x = safeLeft;
            params.y = 0;
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            params.setFitInsetsTypes(0);
            view = new GestureView(windowContext);
            windows.addView(view, params);
            attachedDisplay = id;
            Log.i(TAG, "attached display=" + id + " x=" + safeLeft + " width="
                    + (size.x - safeLeft) + " strip=" + trigger);
        } catch (RuntimeException error) {
            if (SystemClock.elapsedRealtime() - lastWindowError > 10_000) {
                Log.w(TAG, "rear gesture window unavailable; left OEM/app input unchanged", error);
                lastWindowError = SystemClock.elapsedRealtime();
            }
            detach();
        }
    }

    private void detach() {
        clearConfirmation();
        if (view != null && windows != null) {
            Log.i(TAG, "detached display=" + attachedDisplay);
            view.cancelAnimation();
            try { windows.removeViewImmediate(view); }
            catch (RuntimeException ignored) { }
        }
        view = null;
        params = null;
        windows = null;
        windowContext = null;
        attachedDisplay = Display.INVALID_DISPLAY;
    }

    private void expand() {
        if (view == null || params == null || windows == null || params.height == displayHeight) return;
        params.height = displayHeight;
        try { windows.updateViewLayout(view, params); }
        catch (RuntimeException error) { detach(); }
    }

    private void collapse() {
        if (view == null || params == null || windows == null) return;
        int desiredHeight = confirmationPending() ? Math.max(stripHeight, 64) : stripHeight;
        if (params.height == desiredHeight) {
            view.invalidate();
            return;
        }
        params.height = desiredHeight;
        try { windows.updateViewLayout(view, params); }
        catch (RuntimeException error) { detach(); }
    }

    private boolean confirmationPending() {
        return pendingConfirmationPackage != null
                && SystemClock.elapsedRealtime() <= confirmationDeadline;
    }

    private void clearConfirmation() {
        main.removeCallbacks(confirmationTimeout);
        pendingConfirmationPackage = null;
        confirmationDeadline = 0;
    }

    private void onCompletedSwipe() {
        if (!confirmReturnHome) {
            commitHome();
            return;
        }
        if (confirmationPending()
                && pendingConfirmationPackage.equals(activePackage)) {
            clearConfirmation();
            commitHome();
            return;
        }
        pendingConfirmationPackage = activePackage;
        confirmationDeadline = SystemClock.elapsedRealtime() + CONFIRM_WINDOW_MS;
        main.removeCallbacks(confirmationTimeout);
        main.postDelayed(confirmationTimeout, CONFIRM_WINDOW_MS);
        if (view != null) view.animateReveal(0, 150, this::collapse);
    }

    private void commitHome() {
        if (committing || view == null) return;
        committing = true;
        final int launchDisplay = attachedDisplay;
        view.animateReveal(displayHeight, 175, () -> worker.execute(() -> {
            Display current = displays == null ? null : displays.getDisplay(launchDisplay);
            if (stopped || !RearTakeoverService.isEnabled(service)
                    || current == null || current.getState() != Display.STATE_ON) {
                main.post(() -> {
                    committing = false;
                    detach();
                    refreshSoon();
                });
                return;
            }
            RootBridge.Result result = RootBridge.startRearHostChecked(service);
            main.post(() -> {
                if (!result.ok) Log.w(TAG, "rear home gesture launch failed: " + result.output);
                committing = false;
                detach();
                refreshSoon();
            });
        }));
    }

    private final class GestureView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
        private final SimpleDateFormat dateFormat = new SimpleDateFormat("M月d日  EEEE", Locale.CHINA);
        private ValueAnimator animator;
        private float downY;
        private float latestY;
        private long latestAt;
        private float upwardSpeed;
        private float reveal;
        private boolean active;

        GestureView(Context context) { super(context); }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (committing) return true;
            float y = event.getRawY();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    cancelAnimation();
                    downY = y;
                    latestY = y;
                    latestAt = event.getEventTime();
                    upwardSpeed = 0;
                    reveal = 0;
                    active = true;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!active) return true;
                    if (event.getEventTime() > latestAt) {
                        upwardSpeed = (latestY - y) * 1000f
                                / (event.getEventTime() - latestAt);
                    }
                    latestY = y;
                    latestAt = event.getEventTime();
                    float moved = Math.max(0, downY - y);
                    if (moved > 5 || reveal > 0) {
                        expand();
                        reveal = Math.min(displayHeight, moved);
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!active) return true;
                    active = false;
                    float distance = downY - y;
                    boolean home = distance >= displayHeight * .26f
                            || (distance >= displayHeight * .13f && upwardSpeed > 650);
                    if (home) {
                        expand();
                        onCompletedSwipe();
                    } else if (reveal > 0) {
                        animateReveal(0, 105, DirectAppGestureOverlay.this::collapse);
                    } else {
                        collapse();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    active = false;
                    if (reveal > 0) animateReveal(0, 105, DirectAppGestureOverlay.this::collapse);
                    return true;
                default:
                    return true;
            }
        }

        void animateReveal(float to, long duration, Runnable done) {
            cancelAnimation();
            animator = ValueAnimator.ofFloat(reveal, to);
            animator.setDuration(duration);
            animator.setInterpolator(new DecelerateInterpolator());
            animator.addUpdateListener(a -> {
                reveal = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.addListener(new AnimatorListenerAdapter() {
                private boolean canceled;
                @Override public void onAnimationCancel(Animator animation) { canceled = true; }
                @Override public void onAnimationEnd(Animator animation) {
                    animator = null;
                    if (!canceled && done != null) done.run();
                }
            });
            animator.start();
        }

        void cancelAnimation() {
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
        }

        @Override protected void onDraw(Canvas canvas) {
            if (params == null) return;
            int width = getWidth();
            int height = getHeight();
            if (reveal <= 0) {
                if (confirmationPending()) {
                    float hintTop = Math.max(0, height - Math.max(stripHeight, 64));
                    paint.setColor(Color.argb(232, 17, 22, 30));
                    canvas.drawRoundRect(new RectF(12, hintTop + 7, width - 12, height - 7),
                            22, 22, paint);
                    paint.setTextAlign(Paint.Align.CENTER);
                    paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                    paint.setTextSize(18);
                    paint.setColor(Color.rgb(229, 245, 239));
                    canvas.drawText("再上滑一次返回表盘", width / 2f,
                            (hintTop + height) / 2f - (paint.ascent() + paint.descent()) / 2f,
                            paint);
                }
                return;
            }
            if (params.height != displayHeight) return;
            float top = height - Math.min(height, reveal);
            paint.setColor(Color.argb(Math.min(170, Math.round(reveal / height * 170)), 0, 0, 0));
            canvas.drawRect(0, 0, width, height, paint);
            paint.setColor(Color.rgb(7, 10, 15));
            canvas.drawRoundRect(new RectF(0, top, width, height + 36), 27, 27, paint);
            paint.setColor(Color.rgb(16, 21, 28));
            canvas.drawRoundRect(new RectF(7, top + 6, width - 7, height + 28), 24, 24, paint);
            paint.setColor(Color.rgb(109, 229, 184));
            canvas.drawRoundRect(new RectF(width / 2f - 25, top + 13,
                    width / 2f + 25, top + 17), 3, 3, paint);
            if (reveal > 80) {
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
                paint.setTextSize(Math.min(width * .145f, 96));
                paint.setColor(Color.rgb(244, 247, 250));
                canvas.drawText(timeFormat.format(new Date()), width / 2f,
                        Math.min(height - 44, top + Math.max(76, reveal * .53f)), paint);
                if (reveal > 150) {
                    paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                    paint.setTextSize(20);
                    paint.setColor(Color.rgb(150, 163, 177));
                    canvas.drawText(dateFormat.format(new Date()), width / 2f,
                            Math.min(height - 18, top + Math.max(112, reveal * .7f)), paint);
                }
            }
        }
    }
}

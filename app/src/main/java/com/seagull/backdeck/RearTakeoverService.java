package com.seagull.backdeck;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Keeps Xiaomi's wake/Doze stack intact, then restores the assistant after rear wake. */
public final class RearTakeoverService extends Service {
    private static final String TAG = "BackDeckTakeover";
    private static final String CHANNEL = "backdeck_rear_takeover";
    private static final int NOTIFICATION_ID = 17;
    private static final String PREFS = "backdeck_takeover";
    private static final String ENABLED = "enabled";
    private static final long DIRECT_POLL_MS = 650;
    private static final long DIRECT_TO_OEM_WINDOW_MS = 2_000;
    private static final long ASSISTANT_TO_OEM_WINDOW_MS = 8_000;
    private static final long SCREEN_POWER_TAKEOVER_WINDOW_MS = 5_000;
    private static final long RESTORE_COOLDOWN_MS = 1_500;
    private static final long RESTORE_BURST_WINDOW_MS = 30_000;
    private static final int MAX_RESTORES_PER_BURST = 3;
    private static final long ASSISTANT_STABLE_RESET_MS = 5_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "backdeck-wake-check");
        thread.setDaemon(true);
        return thread;
    });
    private DisplayManager displays;
    private DirectAppGestureOverlay directGestureOverlay;
    private int rearId = Display.INVALID_DISPLAY;
    private int lastState = Display.STATE_UNKNOWN;
    private boolean initialized;
    private volatile boolean destroyed;
    private long lastWakeCheck;
    private boolean directPolling;
    private String armedDirectPackage;
    private long lastDirectSeenAt;
    private boolean assistantArmed;
    private long lastAssistantSeenAt;
    private long lastScreenPowerTransitionAt;
    private long assistantStableSince;
    private boolean restoreInFlight;
    private long lastRestoreAttemptAt;
    private long restoreWindowStartedAt;
    private int restoresInWindow;
    private boolean screenReceiverRegistered;
    private final Runnable directTopPoll = this::pollDirectToOemTransition;
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent == null ? null : intent.getAction();
            if (!Intent.ACTION_SCREEN_OFF.equals(action)
                    && !Intent.ACTION_SCREEN_ON.equals(action)) return;
            lastScreenPowerTransitionAt = SystemClock.elapsedRealtime();
            // The front power button can replace the rear task without changing the rear
            // display's STATE_ON. Poll promptly rather than waiting for an OFF -> ON event.
            main.postDelayed(() -> pollDirectToOemTransition(), 250);
            main.postDelayed(() -> pollDirectToOemTransition(), 1_050);
        }
    };
    private final DisplayManager.DisplayListener listener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int displayId) { observeRearState(); }
        @Override public void onDisplayRemoved(int displayId) { observeRearState(); }
        @Override public void onDisplayChanged(int displayId) {
            if (displayId == rearId || rearId == Display.INVALID_DISPLAY) observeRearState();
        }
    };

    public static boolean isEnabled(Context context) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(ENABLED, enabled).apply();
        if (enabled) ensureRunning(context);
        else context.stopService(new Intent(context, RearTakeoverService.class));
    }

    public static void ensureRunning(Context context) {
        if (!isEnabled(context)) return;
        Intent intent = new Intent(context, RearTakeoverService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (RuntimeException error) {
            Log.e(TAG, "Could not start rear wake listener", error);
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null && Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "背屏助手接管",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("背屏亮起后恢复助手，不禁用小米背屏唤醒服务");
            manager.createNotificationChannel(channel);
        }
        Intent settings = new Intent(this, MainActivity.class);
        PendingIntent open = PendingIntent.getActivity(this, 0, settings,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("背屏助手已接管")
                .setContentText("背屏亮起后自动返回助手 · 点按调整")
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        displays = getSystemService(DisplayManager.class);
        directGestureOverlay = new DirectAppGestureOverlay(this, main, worker);
        directGestureOverlay.start();
        IntentFilter screenFilter = new IntentFilter();
        screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
        screenFilter.addAction(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, screenFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenReceiver, screenFilter);
        }
        screenReceiverRegistered = true;
        observeRearState();
        main.post(directTopPoll);
        if (displays != null) {
            if (Build.VERSION.SDK_INT >= 34) {
                displays.registerDisplayListener(getMainExecutor(),
                        DisplayManager.EVENT_TYPE_DISPLAY_ADDED
                                | DisplayManager.EVENT_TYPE_DISPLAY_REMOVED
                                | DisplayManager.EVENT_TYPE_DISPLAY_CHANGED
                                | DisplayManager.EVENT_TYPE_DISPLAY_STATE, listener);
            } else {
                displays.registerDisplayListener(listener, main);
            }
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isEnabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (directGestureOverlay != null) directGestureOverlay.refreshSoon();
        main.removeCallbacks(directTopPoll);
        main.post(directTopPoll);
        return START_STICKY;
    }

    private void observeRearState() {
        if (displays == null) return;
        int id = DisplayRouting.rearDisplayId(this);
        Display rear = id == Display.INVALID_DISPLAY ? null : displays.getDisplay(id);
        int state = rear == null ? Display.STATE_UNKNOWN : rear.getState();
        boolean woke = initialized && state == Display.STATE_ON && lastState != Display.STATE_ON;
        initialized = true;
        if (id != rearId) {
            Log.i(TAG, "rear display changed " + rearId + " -> " + id);
            rearId = id;
        }
        lastState = state;
        if (directGestureOverlay != null) directGestureOverlay.onRearState(id, state);
        if (state != Display.STATE_ON) disarmDirectFallback();
        if (!woke || !isEnabled(this)) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastWakeCheck < 1_500) return;
        lastWakeCheck = now;
        // OEM's SubScreenLauncher often takes the panel briefly during wake. Wait for
        // its transition, then relaunch only if it is still the rear foreground task.
        final int wakeDisplayId = id;
        main.postDelayed(() -> restoreOemHomeIfEligible("rear wake", wakeDisplayId), 450);
    }

    /**
     * Xiaomi can consume a direct-open app's bottom gesture before our overlay gets input and
     * place SubScreenLauncher on top. Recover only that observed app -> OEM-home transition;
     * never reclaim an unrelated visit to Xiaomi's own rear home.
     */
    private void pollDirectToOemTransition() {
        if (destroyed) return;
        if (directPolling) {
            scheduleDirectPoll();
            return;
        }
        int displayId = DisplayRouting.rearDisplayId(this);
        Display rear = displays == null ? null : displays.getDisplay(displayId);
        if (!isEnabled(this) || rear == null || rear.getState() != Display.STATE_ON) {
            disarmDirectFallback();
            scheduleDirectPoll();
            return;
        }
        directPolling = true;
        worker.execute(() -> {
            ComponentName top = RootBridge.rearTopActivity(this, displayId);
            String topPackage = top == null ? null : top.getPackageName();
            boolean selectedDirect = topPackage != null
                    && selectedDirectPackages().contains(topPackage);
            boolean assistantTop = top != null
                    && getPackageName().equals(topPackage)
                    && (RearActivity.class.getName().equals(top.getClassName())
                    || VirtualHostActivity.class.getName().equals(top.getClassName()));
            boolean oemHome = top != null
                    && "com.xiaomi.subscreencenter".equals(topPackage)
                    && "com.xiaomi.subscreencenter.SubScreenLauncher".equals(top.getClassName());
            main.post(() -> {
                if (destroyed) return;
                directPolling = false;
                Display current = displays == null ? null : displays.getDisplay(displayId);
                if (!isEnabled(this) || displayId != DisplayRouting.rearDisplayId(this)
                        || current == null || current.getState() != Display.STATE_ON) {
                    disarmDirectFallback();
                    scheduleDirectPoll();
                    return;
                }
                long now = SystemClock.elapsedRealtime();
                if (assistantTop) {
                    if (!assistantArmed) assistantStableSince = now;
                    assistantArmed = true;
                    lastAssistantSeenAt = now;
                    if (assistantStableSince != 0
                            && now - assistantStableSince >= ASSISTANT_STABLE_RESET_MS) {
                        restoresInWindow = 0;
                        restoreWindowStartedAt = 0;
                    }
                } else if (top != null && !oemHome) {
                    // A real app (or camera) between our page and OEM home is not a
                    // lock-button takeover of the assistant. Do not reclaim it later.
                    assistantArmed = false;
                    assistantStableSince = 0;
                }
                if (selectedDirect) {
                    armedDirectPackage = topPackage;
                    lastDirectSeenAt = now;
                } else if (oemHome && armedDirectPackage != null
                        && now - lastDirectSeenAt <= DIRECT_TO_OEM_WINDOW_MS) {
                    String fromPackage = armedDirectPackage;
                    disarmDirectFallback(); // One shot, even if launch fails; no reclaim loop.
                    restoreOemHomeIfEligible("direct app " + fromPackage, displayId);
                } else if (top != null || now - lastDirectSeenAt > DIRECT_TO_OEM_WINDOW_MS) {
                    disarmDirectFallback();
                }
                if (oemHome && assistantArmed
                        && now - lastAssistantSeenAt <= ASSISTANT_TO_OEM_WINDOW_MS
                        && lastScreenPowerTransitionAt != 0
                        && now - lastScreenPowerTransitionAt
                        <= SCREEN_POWER_TAKEOVER_WINDOW_MS) {
                    assistantArmed = false; // A single OEM takeover gets a single restore.
                    assistantStableSince = 0;
                    restoreOemHomeIfEligible("assistant displaced", displayId);
                } else if (now - lastAssistantSeenAt > ASSISTANT_TO_OEM_WINDOW_MS) {
                    assistantArmed = false;
                    assistantStableSince = 0;
                }
                scheduleDirectPoll();
            });
        });
    }

    /** Never launch over a sleeping panel, another app, or repeatedly fight OEM's AOD. */
    private void restoreOemHomeIfEligible(String reason, int displayId) {
        if (destroyed || restoreInFlight || !isEnabled(this)
                || displayId != DisplayRouting.rearDisplayId(this)) return;
        Display panel = displays == null ? null : displays.getDisplay(displayId);
        if (panel == null || panel.getState() != Display.STATE_ON) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastRestoreAttemptAt < RESTORE_COOLDOWN_MS) return;
        if (restoreWindowStartedAt == 0
                || now - restoreWindowStartedAt > RESTORE_BURST_WINDOW_MS) {
            restoreWindowStartedAt = now;
            restoresInWindow = 0;
        }
        if (restoresInWindow >= MAX_RESTORES_PER_BURST) {
            Log.w(TAG, "restore suppressed after repeated OEM takeovers: " + reason);
            return;
        }
        restoreInFlight = true;
        worker.execute(() -> {
            RootBridge.Result result = null;
            if (!destroyed && isEnabled(this)
                    && DisplayRouting.rearDisplayId(this) == displayId) {
                Display current = displays == null ? null : displays.getDisplay(displayId);
                if (current != null && current.getState() == Display.STATE_ON
                        && RootBridge.isOemRearHomeTop(this)) {
                    result = RootBridge.startRearHostChecked(this);
                }
            }
            final RootBridge.Result finalResult = result;
            main.post(() -> {
                restoreInFlight = false;
                if (destroyed || finalResult == null) return;
                lastRestoreAttemptAt = SystemClock.elapsedRealtime();
                restoresInWindow++;
                Log.i(TAG, reason + " restore=" + finalResult.ok + " " + finalResult.output);
            });
        });
    }

    private void scheduleDirectPoll() {
        main.removeCallbacks(directTopPoll);
        main.postDelayed(directTopPoll, DIRECT_POLL_MS);
    }

    private void disarmDirectFallback() {
        armedDirectPackage = null;
        lastDirectSeenAt = 0;
    }

    private Set<String> selectedDirectPackages() {
        Set<String> selected = new HashSet<>();
        for (String key : new String[]{"long_press", "double_tap", "swipe_left", "swipe_right"}) {
            addSelectedDirect(selected, ActionStore.get(this, key));
        }
        for (ActionConfig item : ActionStore.getDrawer(this)) addSelectedDirect(selected, item);
        return selected;
    }

    private void addSelectedDirect(Set<String> selected, ActionConfig action) {
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
                List<ResolveInfo> matches = getPackageManager().queryIntentActivities(
                        new Intent(Intent.ACTION_VIEW, uri), 0);
                if (matches.size() == 1 && matches.get(0).activityInfo != null) {
                    pkg = matches.get(0).activityInfo.packageName;
                }
            }
        }
        if (pkg != null && pkg.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")) {
            selected.add(pkg);
        }
    }

    @Override public void onDestroy() {
        destroyed = true;
        if (displays != null) displays.unregisterDisplayListener(listener);
        if (screenReceiverRegistered) unregisterReceiver(screenReceiver);
        if (directGestureOverlay != null) directGestureOverlay.stop();
        disarmDirectFallback();
        main.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}

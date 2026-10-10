package com.seagull.backdeck;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.widget.Toast;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Launches a configured target on the explicitly selected physical screen. */
public final class ActionLauncher {
    private static final long DUPLICATE_LAUNCH_WINDOW_MS = 1_500;
    private static final Map<String, Long> recentLaunches = new HashMap<>();
    private static final Set<String> launchesInFlight = new HashSet<>();
    private ActionLauncher() {}

    /** A second gesture/click during one launch must not create another task. */
    static synchronized boolean reserveLaunch(String key) {
        long now = SystemClock.elapsedRealtime();
        Long previous = recentLaunches.get(key);
        if (launchesInFlight.contains(key)
                || (previous != null && now - previous < DUPLICATE_LAUNCH_WINDOW_MS)) return false;
        launchesInFlight.add(key);
        recentLaunches.put(key, now);
        if (recentLaunches.size() > 32) {
            recentLaunches.entrySet().removeIf(entry ->
                    now - entry.getValue() >= DUPLICATE_LAUNCH_WINDOW_MS);
        }
        return true;
    }

    static synchronized void finishLaunch(String key, boolean success) {
        launchesInFlight.remove(key);
        if (success) recentLaunches.put(key, SystemClock.elapsedRealtime());
        else recentLaunches.remove(key);
    }

    public static void launchInVirtual(Activity host, ActionConfig config, int virtualDisplayId) {
        if (config == null || !config.enabled || virtualDisplayId < 2) return;
        String launchKey = "virtual:" + config.type + ':' + config.value;
        if (!reserveLaunch(launchKey)) return;
        try {
            Intent intent = toIntent(host, config);
            if (intent == null) {
                finishLaunch(launchKey, false);
                toast(host, "目标应用不存在");
                return;
            }
            // A fresh virtual display needs an independent task, not the front-screen task.
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            rootLaunch(host, intent, virtualDisplayId, launchKey);
        } catch (Exception e) {
            finishLaunch(launchKey, false);
            toast(host, "虚拟屏启动失败：" + e.getMessage());
        }
    }

    public static void launch(Activity activity, ActionConfig config) {
        if (config == null || !config.enabled) return;
        int displayId = config.displayId == 0 ? Display.DEFAULT_DISPLAY
                : DisplayRouting.rearDisplayId(activity);
        if (displayId == Display.INVALID_DISPLAY) {
            toast(activity, "背屏未连接");
            return;
        }
        Intent intent;
        try {
            intent = toIntent(activity, config);
        } catch (Exception e) {
            toast(activity, "目标配置无效：" + e.getMessage());
            return;
        }
        if (intent == null) {
            toast(activity, "目标应用或 Activity 不存在");
            return;
        }
        boolean needsRoot = false;
        if (intent.getComponent() != null) {
            try {
                ActivityInfo info = activity.getPackageManager().getActivityInfo(intent.getComponent(), 0);
                needsRoot = !info.exported;
            } catch (PackageManager.NameNotFoundException e) {
                toast(activity, "目标 Activity 不存在");
                return;
            }
        } else if (intent.resolveActivity(activity.getPackageManager()) == null) {
            toast(activity, "目标应用不存在");
            return;
        }
        String launchKey = "physical:" + displayId + ':' + config.type + ':' + config.value;
        if (!reserveLaunch(launchKey)) return;
        // The rear task must never be resolved against an existing front-screen task.
        // RootBridge checks for a rear task first; only a genuinely new rear task
        // receives MULTIPLE_TASK, so repeated launches do not duplicate it.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        // HyperOS may silently return result code 102 rather than throwing to startActivity.
        // For physical rear launches use the checked ROOT path (-W) from the outset.
        if (needsRoot || displayId != Display.DEFAULT_DISPLAY) {
            rootLaunch(activity, intent, displayId, launchKey);
            return;
        }
        try {
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchDisplayId(displayId);
            activity.startActivity(intent, options.toBundle());
            finishLaunch(launchKey, true);
        } catch (Exception standardError) {
            rootLaunch(activity, intent, displayId, launchKey);
        }
    }

    private static Intent toIntent(Activity activity, ActionConfig config) {
        if ("uri".equals(config.type)) {
            Uri uri = Uri.parse(config.value);
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            // Avoid an intermediate resolver on the rear display for this known scheme.
            if ("alipays".equalsIgnoreCase(uri.getScheme())) {
                intent.setComponent(new ComponentName("com.eg.android.AlipayGphone",
                        "com.alipay.mobile.quinox.SchemeLauncherActivity"));
            }
            return intent;
        }
        if ("component".equals(config.type)) {
            ComponentName component = ComponentName.unflattenFromString(config.value);
            if (component == null) throw new IllegalArgumentException("component 格式应为包名/Activity");
            return new Intent().setComponent(component);
        }
        if ("package".equals(config.type)) {
            return activity.getPackageManager().getLaunchIntentForPackage(config.value);
        }
        throw new IllegalArgumentException("type=" + config.type);
    }

    private static void toast(Activity activity, String message) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }

    private static void rootLaunch(Activity activity, Intent intent, int displayId,
                                   String launchKey) {
        new Thread(() -> {
            RootBridge.Result root = displayId != Display.DEFAULT_DISPLAY
                    && displayId == DisplayRouting.rearDisplayId(activity)
                    ? RootBridge.launchPhysicalRear(activity, intent, displayId)
                    : RootBridge.launch(intent, displayId);
            finishLaunch(launchKey, root.ok);
            String message = root.ok ? "已用 ROOT 启动" : "启动失败；检查背屏白名单 / LSPosed";
            new Handler(Looper.getMainLooper()).post(() -> toast(activity, message));
        }, "backdeck-root-launch").start();
    }
}

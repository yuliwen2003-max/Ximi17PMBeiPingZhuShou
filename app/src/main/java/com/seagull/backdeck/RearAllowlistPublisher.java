package com.seagull.backdeck;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Publishes only enabled rear-screen gesture and drawer targets to a root-writable global setting.
 * The LSPosed hook reads this cross-process snapshot; normal apps cannot write Settings.Global.
 */
public final class RearAllowlistPublisher {
    private static final String KEY = "backdeck_allowed_packages";
    private static final String[] GESTURES = {
            "long_press", "double_tap", "swipe_left", "swipe_right"
    };
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "backdeck-allowlist");
        thread.setDaemon(true);
        return thread;
    });
    private static String lastPublished;

    private RearAllowlistPublisher() { }

    public static void schedule(Context context) {
        Context app = context.getApplicationContext();
        WORKER.execute(() -> publish(app));
    }

    private static void publish(Context context) {
        SortedSet<String> allowed = new TreeSet<>();
        for (String gesture : GESTURES) {
            addTarget(context, allowed, ActionStore.get(context, gesture));
        }
        for (ActionConfig config : ActionStore.getDrawer(context)) {
            addTarget(context, allowed, config);
        }
        if (!allowed.isEmpty()) {
            // HyperOS routes protected apps through its AppLockActivity on the same display.
            // Allow the authentication UI; this does not bypass the user's app lock.
            allowed.add("com.miui.securitycenter");
        }
        String csv = String.join(",", allowed);
        if (csv.equals(lastPublished)) return;
        RootBridge.Result result = RootBridge.putGlobalSetting(KEY, csv);
        if (result.ok) lastPublished = csv;
    }

    private static void addTarget(Context context, SortedSet<String> allowed, ActionConfig config) {
        if (config == null || !config.enabled
                || (config.displayId != ActionConfig.DISPLAY_REAR_DIRECT
                && config.displayId != ActionConfig.DISPLAY_REAR_SAFE)) return;
        String value = config.value;
        if (value == null || value.isEmpty()) return;
        String pkg = null;
        if ("package".equals(config.type)) {
            pkg = value;
        } else if ("component".equals(config.type)) {
            ComponentName component = ComponentName.unflattenFromString(value);
            if (component != null) pkg = component.getPackageName();
        } else if ("uri".equals(config.type)) {
            Uri uri = Uri.parse(value);
            String scheme = uri.getScheme();
            if ("weixin".equalsIgnoreCase(scheme)) {
                pkg = "com.tencent.mm";
            } else if ("alipays".equalsIgnoreCase(scheme) || "alipay".equalsIgnoreCase(scheme)) {
                pkg = "com.eg.android.AlipayGphone";
            } else if (scheme != null) {
                // A custom URI is allowed only when it resolves to one unambiguous app.
                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                List<ResolveInfo> matches = context.getPackageManager().queryIntentActivities(intent, 0);
                if (matches.size() == 1 && matches.get(0).activityInfo != null) {
                    pkg = matches.get(0).activityInfo.packageName;
                }
            }
        }
        if (validPackage(pkg)) allowed.add(pkg);
    }

    private static boolean validPackage(String value) {
        return value != null && value.length() <= 255
                && value.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+");
    }
}

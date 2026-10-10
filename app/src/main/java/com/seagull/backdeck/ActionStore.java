package com.seagull.backdeck;

import android.content.Context;
import android.content.ComponentName;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Shared gesture/drawer configuration consumed by both the front settings and rear host. */
public final class ActionStore {
    private static final String PREFS = "backdeck_actions";
    // v1 was populated with every installed launcher app. Never interpret it as
    // the user's explicit drawer selection; retain it only to restore per-app
    // settings if an app is manually added again.
    private static final String LEGACY_DRAWER_KEY = "drawer_items_v1";
    private static final String DRAWER_KEY = "drawer_items_v2";
    /** Latest explicit safe-area launch; used when HyperOS reorders an existing host silently. */
    private static final String PENDING_SAFE_LAUNCH_KEY = "pending_safe_launch_v1";
    private static final String SAFE_MODE_MIGRATION = "legacy_status_bar_safe_mode_v2";

    private ActionStore() { }

    public static ActionConfig get(Context context, String key) {
        String raw = prefs(context).getString("gesture_" + key, null);
        if (raw != null) {
            try {
                return fromJson(new JSONObject(raw));
            } catch (JSONException ignored) { }
        }
        return defaultAction(key);
    }

    public static void put(Context context, String key, ActionConfig config) {
        prefs(context).edit().putString("gesture_" + key, toJson(config).toString()).apply();
        RearAllowlistPublisher.schedule(context);
    }

    /** Only apps explicitly added by the user appear in the drawer. */
    public static List<ActionConfig> getDrawer(Context context) {
        String raw = prefs(context).getString(DRAWER_KEY, null);
        return parseDrawer(raw);
    }

    /** Restore old DPI/viewport choices without importing the old all-apps drawer. */
    public static ActionConfig getLegacyDrawerConfig(Context context, String type, String value) {
        for (ActionConfig item : parseDrawer(prefs(context).getString(LEGACY_DRAWER_KEY, null))) {
            if (item.type.equals(type) && item.value.equals(value)) {
                // v1 auto-created a default entry for every installed launcher app.
                // Reusing that catalog entry would turn its implicit 0 px into the
                // default for every newly added v2 app. Only carry over choices
                // that differ from the old generated defaults.
                if (item.displayId == ActionConfig.DISPLAY_REAR_SAFE
                        && item.virtualDpi == ActionConfig.DEFAULT_VIRTUAL_DPI
                        && item.virtualTopCropPx == 0) return null;
                return item;
            }
        }
        return null;
    }

    private static List<ActionConfig> parseDrawer(String raw) {
        List<ActionConfig> items = new ArrayList<>();
        if (raw == null) return items;
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object != null) items.add(fromJson(object));
            }
        } catch (JSONException ignored) { }
        return items;
    }

    public static void setDrawer(Context context, List<ActionConfig> items) {
        JSONArray array = new JSONArray();
        if (items != null) {
            for (ActionConfig item : items) {
                if (item != null) array.put(toJson(item));
            }
        }
        prefs(context).edit().putString(DRAWER_KEY, array.toString()).apply();
        RearAllowlistPublisher.schedule(context);
    }

    /**
     * Persist the exact drawer/gesture choice before bringing a retained host to the front.
     * Some HyperOS builds reorder a singleTop activity without invoking onNewIntent(), so the
     * host must be able to recover the newest DPI/crop configuration from this tiny hand-off.
     */
    public static void setPendingSafeLaunch(Context context, ActionConfig action) {
        if (action == null || action.displayId != ActionConfig.DISPLAY_REAR_SAFE) return;
        prefs(context).edit().putString(PENDING_SAFE_LAUNCH_KEY, toJson(action).toString()).apply();
    }

    public static ActionConfig getPendingSafeLaunch(Context context) {
        String raw = prefs(context).getString(PENDING_SAFE_LAUNCH_KEY, null);
        if (raw == null) return null;
        try {
            ActionConfig action = fromJson(new JSONObject(raw));
            return action.displayId == ActionConfig.DISPLAY_REAR_SAFE ? action : null;
        } catch (JSONException ignored) {
            return null;
        }
    }

    /** One-time migration for the operator-approved IT之家/QQ safe-area mode. */
    public static boolean migrateLegacyDirectAppsToSafe(Context context) {
        SharedPreferences preferences = prefs(context);
        if (preferences.getBoolean(SAFE_MODE_MIGRATION, false)) return false;
        SharedPreferences.Editor editor = preferences.edit();
        boolean changed = false;
        for (String drawerKey : new String[]{LEGACY_DRAWER_KEY, DRAWER_KEY}) {
            String rawDrawer = preferences.getString(drawerKey, null);
            if (rawDrawer == null) continue;
            try {
                JSONArray array = new JSONArray(rawDrawer);
                boolean drawerChanged = false;
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.optJSONObject(i);
                    if (item != null && migrateItem(context, item)) drawerChanged = true;
                }
                if (drawerChanged) {
                    editor.putString(drawerKey, array.toString());
                    changed = true;
                }
            } catch (JSONException ignored) { }
        }
        for (String key : new String[]{"long_press", "double_tap", "swipe_left", "swipe_right"}) {
            String prefKey = "gesture_" + key;
            String raw = preferences.getString(prefKey, null);
            if (raw == null) continue;
            try {
                JSONObject item = new JSONObject(raw);
                if (migrateItem(context, item)) {
                    editor.putString(prefKey, item.toString());
                    changed = true;
                }
            } catch (JSONException ignored) { }
        }
        editor.putBoolean(SAFE_MODE_MIGRATION, true).apply();
        if (changed) RearAllowlistPublisher.schedule(context);
        return changed;
    }

    private static boolean migrateItem(Context context, JSONObject item) throws JSONException {
        int displayId = item.optInt("displayId", 1);
        if (displayId != ActionConfig.DISPLAY_REAR_DIRECT
                && displayId != ActionConfig.DISPLAY_REAR_SAFE) return false;
        ActionConfig action = fromJson(item);
        // Keep the older migration limited to the explicitly approved apps.
        // The new 144 px default must not rewrite any previously saved item.
        int suggested = legacyKnownTopCompensationPx(context, action);
        if (suggested <= 0) return false;
        if (displayId == ActionConfig.DISPLAY_REAR_SAFE
                && item.optInt("virtualTopCropPx", 0) != 0) return false;
        item.put("displayId", ActionConfig.DISPLAY_REAR_SAFE);
        item.put("virtualTopCropPx", suggested);
        return true;
    }

    private static ActionConfig defaultAction(String key) {
        if ("long_press".equals(key)) {
            // This device's WeChat 8.0.78 routes weixin://dl/scan to a warning page.
            // The actual scanner Activity is private, so ActionLauncher uses ROOT for it.
            return new ActionConfig("component", "com.tencent.mm/.plugin.scanner.ui.BaseScanUI",
                    "微信扫一扫", ActionConfig.DISPLAY_REAR_SAFE, true);
        }
        if ("double_tap".equals(key)) {
            return new ActionConfig("uri", "alipays://platformapi/startapp?appId=10000007", "支付宝扫一扫", 1, true);
        }
        if ("swipe_left".equals(key)) return new ActionConfig("uri", "", "向左滑动", 1, false);
        if ("swipe_right".equals(key)) return new ActionConfig("uri", "", "向右滑动", 1, false);
        return new ActionConfig();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** New safe-area selections start uncropped; per-app compensation is explicit. */
    public static int suggestedTopCompensationPx(Context context, ActionConfig action) {
        return 0;
    }

    /** Former targeted migration, intentionally separate from new-app defaults. */
    private static int legacyKnownTopCompensationPx(Context context, ActionConfig action) {
        String packageName = packageNameOf(action);
        if (!"com.ruanmei.ithome".equals(packageName)
                && !"com.tencent.mobileqq".equals(packageName)
                && !"tv.danmaku.bili".equals(packageName)) return 0;
        try {
            int id = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id == 0) return 0;
            DisplayManager manager = context.getSystemService(DisplayManager.class);
            Display front = manager == null ? null : manager.getDisplay(Display.DEFAULT_DISPLAY);
            Context frontContext = front == null ? context : context.createDisplayContext(front);
            return ActionConfig.clampVirtualTopCropPx(
                    frontContext.getResources().getDimensionPixelSize(id));
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static String packageNameOf(ActionConfig action) {
        if (action == null) return null;
        String packageName = null;
        if ("package".equals(action.type)) packageName = action.value;
        if ("component".equals(action.type)) {
            ComponentName component = ComponentName.unflattenFromString(action.value);
            if (component != null) packageName = component.getPackageName();
        }
        return packageName;
    }

    private static JSONObject toJson(ActionConfig action) {
        JSONObject object = new JSONObject();
        if (action == null) action = new ActionConfig();
        try {
            object.put("type", safe(action.type));
            object.put("value", safe(action.value));
            object.put("label", safe(action.label));
            object.put("displayId", validDisplayId(action.displayId));
            object.put("enabled", action.enabled);
            object.put("virtualDpi", ActionConfig.clampVirtualDpi(action.virtualDpi));
            object.put("virtualTopCropPx", ActionConfig.clampVirtualTopCropPx(action.virtualTopCropPx));
            object.put("confirmReturnHome", action.confirmReturnHome);
        } catch (JSONException ignored) { }
        return object;
    }

    private static ActionConfig fromJson(JSONObject object) {
        ActionConfig action = new ActionConfig();
        String type = object.optString("type", "uri");
        action.type = "package".equals(type) || "component".equals(type) ? type : "uri";
        action.value = object.optString("value", "");
        action.label = object.optString("label", "未命名动作");
        action.displayId = validDisplayId(object.optInt("displayId", ActionConfig.DISPLAY_REAR_DIRECT));
        action.enabled = object.optBoolean("enabled", false);
        action.virtualDpi = ActionConfig.clampVirtualDpi(
                object.optInt("virtualDpi", ActionConfig.DEFAULT_VIRTUAL_DPI));
        action.virtualTopCropPx = ActionConfig.clampVirtualTopCropPx(
                object.optInt("virtualTopCropPx", 0));
        action.confirmReturnHome = object.optBoolean("confirmReturnHome", true);
        return action;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static int validDisplayId(int value) {
        return value == ActionConfig.DISPLAY_FRONT || value == ActionConfig.DISPLAY_REAR_SAFE
                ? value : ActionConfig.DISPLAY_REAR_DIRECT;
    }
}

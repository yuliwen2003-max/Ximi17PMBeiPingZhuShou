package com.seagull.backdeck;

/** A user-configurable launch action. Values are URI, package name or flattened component. */
public class ActionConfig {
    public static final int DISPLAY_FRONT = 0;
    public static final int DISPLAY_REAR_DIRECT = 1;
    public static final int DISPLAY_REAR_SAFE = 2;
    public static final int DEFAULT_VIRTUAL_DPI = 240;
    public static final int MIN_VIRTUAL_DPI = 160;
    public static final int MAX_VIRTUAL_DPI = 450;
    public static final int MAX_VIRTUAL_TOP_CROP_PX = 240;
    /** Safe-area apps are 1:1 by default; compensation is an explicit per-app opt-in. */
    public static final int DEFAULT_VIRTUAL_TOP_COMPENSATION_PX = 0;

    public String type;
    public String value;
    public String label;
    /** 0 front, 1 physical rear direct, 2 camera-safe virtual display on the rear. */
    public int displayId;
    public boolean enabled;
    /** Logical density of the camera-safe virtual display. Lower means more content fits. */
    public int virtualDpi = DEFAULT_VIRTUAL_DPI;
    /** Physical pixels hidden from the top of the camera-safe virtual display. */
    public int virtualTopCropPx = 0;
    /** Require a second bottom-up Home swipe within the confirmation window. */
    public boolean confirmReturnHome = true;

    public ActionConfig() {
        this("uri", "", "未设置", 1, false);
    }

    public ActionConfig(String type, String value, String label, int displayId, boolean enabled) {
        this.type = type;
        this.value = value;
        this.label = label;
        this.displayId = displayId;
        this.enabled = enabled;
    }

    public ActionConfig copy() {
        ActionConfig copy = new ActionConfig(type, value, label, displayId, enabled);
        copy.virtualDpi = clampVirtualDpi(virtualDpi);
        copy.virtualTopCropPx = clampVirtualTopCropPx(virtualTopCropPx);
        copy.confirmReturnHome = confirmReturnHome;
        return copy;
    }

    public static int clampVirtualDpi(int dpi) {
        return Math.max(MIN_VIRTUAL_DPI, Math.min(MAX_VIRTUAL_DPI, dpi));
    }

    public static int clampVirtualTopCropPx(int px) {
        return Math.max(0, Math.min(MAX_VIRTUAL_TOP_CROP_PX, px));
    }
}

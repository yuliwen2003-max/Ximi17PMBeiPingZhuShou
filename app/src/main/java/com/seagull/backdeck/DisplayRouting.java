package com.seagull.backdeck;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;

/** Finds the physical rear display at runtime; Android display IDs are not stable. */
public final class DisplayRouting {
    private DisplayRouting() {}

    public static int rearDisplayId(Context context) {
        DisplayManager manager = context.getSystemService(DisplayManager.class);
        if (manager == null) return Display.INVALID_DISPLAY;
        Display front = manager.getDisplay(Display.DEFAULT_DISPLAY);
        String builtInName = front == null ? null : front.getName();
        Display best = null;
        Display fallback = null;
        for (Display display : manager.getDisplays()) {
            if (display.getDisplayId() == Display.DEFAULT_DISPLAY) continue;
            // The app's embedded VirtualDisplay is also PRESENTATION and smaller
            // than the physical rear screen. Never route the clock into itself.
            int flags = display.getFlags();
            int required = Display.FLAG_PRESENTATION | Display.FLAG_SECURE
                    | Display.FLAG_SUPPORTS_PROTECTED_BUFFERS;
            if ((flags & required) != required) continue;
            if (builtInName != null && builtInName.equals(display.getName())) {
                if (best == null || isBetter(display, best)) best = display;
            } else if (fallback == null || isBetter(display, fallback)) {
                fallback = display;
            }
        }
        Display rear = best != null ? best : fallback;
        return rear == null ? Display.INVALID_DISPLAY : rear.getDisplayId();
    }

    private static boolean isBetter(Display candidate, Display old) {
        boolean cp = (candidate.getFlags() & Display.FLAG_PRESENTATION) != 0;
        boolean op = (old.getFlags() & Display.FLAG_PRESENTATION) != 0;
        if (cp != op) return cp;
        Display.Mode cm = candidate.getMode();
        Display.Mode om = old.getMode();
        return cm.getPhysicalWidth() * cm.getPhysicalHeight()
                < om.getPhysicalWidth() * om.getPhysicalHeight();
    }
}

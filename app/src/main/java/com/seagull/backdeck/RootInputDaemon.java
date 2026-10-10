package com.seagull.backdeck;

import android.hardware.input.InputManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * A short-lived, root-owned input injector launched through app_process.
 *
 * The only command channel is its parent's private stdin pipe. It is bound to one
 * virtual display and dies on EOF; it never listens on a socket or opens a port.
 * Hidden framework APIs are resolved once, not on every touch sample.
 */
public final class RootInputDaemon {
    private static final String TAG = "BackDeckInput";
    private static final int MAX_POINTERS = 10;
    private static final int MAX_LINE = 2048;
    private final int displayId;
    private final int width;
    private final int height;
    private final Object inputManager;
    private final Method injectInputEvent;
    private final Method motionSetDisplayId;
    private final Method keySetDisplayId;
    private MotionEvent lastMotion;
    private boolean gestureActive;

    private RootInputDaemon(int displayId, int width, int height) throws Exception {
        this.displayId = displayId;
        this.width = width;
        this.height = height;

        Class<?> managerClass;
        try {
            managerClass = Class.forName("android.hardware.input.InputManagerGlobal");
        } catch (ClassNotFoundException oldAndroid) {
            managerClass = InputManager.class;
        }
        inputManager = managerClass.getMethod("getInstance").invoke(null);
        injectInputEvent = managerClass.getMethod("injectInputEvent", InputEvent.class, int.class);
        motionSetDisplayId = MotionEvent.class.getMethod("setDisplayId", int.class);
        keySetDisplayId = KeyEvent.class.getMethod("setDisplayId", int.class);
    }

    public static void main(String[] args) {
        try {
            if (android.os.Process.myUid() != 0) throw new SecurityException("root required");
            if (args.length != 3) throw new IllegalArgumentException("display, width, height required");
            int displayId = Integer.parseInt(args[0]);
            int width = Integer.parseInt(args[1]);
            int height = Integer.parseInt(args[2]);
            if (displayId < 2 || width < 100 || width > 4096 || height < 100 || height > 4096) {
                throw new IllegalArgumentException("invalid virtual display geometry");
            }
            RootInputDaemon daemon = new RootInputDaemon(displayId, width, height);
            System.out.println("READY");
            System.out.flush();
            daemon.run();
        } catch (Throwable error) {
            System.out.println("ERROR " + oneLine(error));
            System.out.flush();
        }
    }

    private void run() throws Exception {
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = readBoundedLine(in)) != null) {
                if (line.isEmpty()) continue;
                try {
                    if (line.equals("Q")) break;
                    if (line.equals("C")) {
                        cancelGesture();
                    } else if (line.equals("B")) {
                        injectBack();
                    } else if (line.startsWith("M,")) {
                        injectMotion(line);
                    }
                } catch (Throwable error) {
                    // An injection error is terminal; continuing can leave a half-delivered
                    // stream (DOWN without UP) in the target application.
                    System.out.println("ERROR " + oneLine(error));
                    System.out.flush();
                    break;
                }
            }
        } finally {
            try { cancelGesture(); } catch (Throwable ignored) { }
            if (lastMotion != null) lastMotion.recycle();
        }
    }

    private static String readBoundedLine(BufferedReader in) throws Exception {
        StringBuilder result = new StringBuilder(160);
        int ch;
        while ((ch = in.read()) != -1) {
            if (ch == '\n') return result.toString();
            if (result.length() < MAX_LINE) {
                result.append((char) ch);
            } else {
                // Drain an oversized frame without ever trying to parse it.
                while ((ch = in.read()) != -1 && ch != '\n') { }
                return "";
            }
        }
        return result.length() == 0 ? null : result.toString();
    }

    private void injectMotion(String line) throws Exception {
        String[] fields = line.split(",", -1);
        if (fields.length < 5) throw new IllegalArgumentException("short motion frame");
        long downTime = Long.parseLong(fields[1]);
        long eventTime = Long.parseLong(fields[2]);
        int action = Integer.parseInt(fields[3]);
        int count = Integer.parseInt(fields[4]);
        int masked = action & MotionEvent.ACTION_MASK;
        if (count < 1 || count > MAX_POINTERS || fields.length != 5 + count * 6
                || eventTime < downTime || eventTime > SystemClock.uptimeMillis() + 2000
                || (masked != MotionEvent.ACTION_DOWN && masked != MotionEvent.ACTION_UP
                && masked != MotionEvent.ACTION_MOVE && masked != MotionEvent.ACTION_CANCEL
                && masked != MotionEvent.ACTION_POINTER_DOWN
                && masked != MotionEvent.ACTION_POINTER_UP)) {
            throw new IllegalArgumentException("invalid motion frame");
        }
        int actionIndex = (action & MotionEvent.ACTION_POINTER_INDEX_MASK)
                >> MotionEvent.ACTION_POINTER_INDEX_SHIFT;
        if ((masked == MotionEvent.ACTION_POINTER_DOWN || masked == MotionEvent.ACTION_POINTER_UP)
                && actionIndex >= count) {
            throw new IllegalArgumentException("bad pointer action index");
        }
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
        for (int i = 0, offset = 5; i < count; i++, offset += 6) {
            MotionEvent.PointerProperties p = new MotionEvent.PointerProperties();
            p.id = Integer.parseInt(fields[offset]);
            p.toolType = Integer.parseInt(fields[offset + 5]);
            if (p.id < 0 || p.id > 31) throw new IllegalArgumentException("bad pointer id");
            if (p.toolType != MotionEvent.TOOL_TYPE_FINGER
                    && p.toolType != MotionEvent.TOOL_TYPE_STYLUS
                    && p.toolType != MotionEvent.TOOL_TYPE_ERASER) {
                p.toolType = MotionEvent.TOOL_TYPE_FINGER;
            }
            properties[i] = p;
            MotionEvent.PointerCoords c = new MotionEvent.PointerCoords();
            c.x = clamp(Float.parseFloat(fields[offset + 1]), 0, width - 1);
            c.y = clamp(Float.parseFloat(fields[offset + 2]), 0, height - 1);
            c.pressure = clamp(Float.parseFloat(fields[offset + 3]), 0, 1.5f);
            c.size = clamp(Float.parseFloat(fields[offset + 4]), 0, 1);
            coords[i] = c;
        }
        if (masked != MotionEvent.ACTION_DOWN && !gestureActive) return;
        MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, count,
                properties, coords, 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0);
        try {
            motionSetDisplayId.invoke(event, displayId);
            inject(event);
            if (masked == MotionEvent.ACTION_DOWN || masked == MotionEvent.ACTION_UP) {
                Log.i(TAG, "touch action=" + masked + " injectionLagMs="
                        + Math.max(0, SystemClock.uptimeMillis() - eventTime));
            }
            if (lastMotion != null) lastMotion.recycle();
            lastMotion = MotionEvent.obtain(event);
            gestureActive = masked != MotionEvent.ACTION_UP && masked != MotionEvent.ACTION_CANCEL;
        } finally {
            event.recycle();
        }
    }

    private void cancelGesture() throws Exception {
        if (!gestureActive || lastMotion == null) return;
        MotionEvent event = MotionEvent.obtain(lastMotion);
        try {
            event.setAction(MotionEvent.ACTION_CANCEL);
            inject(event);
        } finally {
            event.recycle();
            gestureActive = false;
        }
    }

    private void injectBack() throws Exception {
        cancelGesture();
        long time = SystemClock.uptimeMillis();
        for (int action : new int[] {KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
            KeyEvent key = new KeyEvent(time, SystemClock.uptimeMillis(), action,
                    KeyEvent.KEYCODE_BACK, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD,
                    0, 0, InputDevice.SOURCE_KEYBOARD);
            keySetDisplayId.invoke(key, displayId);
            inject(key);
        }
    }

    private void inject(InputEvent event) throws Exception {
        Object result = injectInputEvent.invoke(inputManager, event, 0 /* ASYNC */);
        if (result instanceof Boolean && !((Boolean) result)) {
            throw new IllegalStateException("input injection rejected");
        }
    }

    private static float clamp(float value, float low, float high) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException("nonfinite pointer coordinate");
        return Math.max(low, Math.min(high, value));
    }

    private static String oneLine(Throwable error) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        return cause.toString().replace('\n', ' ').replace('\r', ' ');
    }
}

package com.seagull.backdeck;

import android.content.Context;
import android.util.Log;
import android.view.MotionEvent;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * UI-side, non-blocking pipe into {@link RootInputDaemon}.
 *
 * One root process is started per virtual display. Unlike `su -c input swipe`,
 * each real DOWN/MOVE/UP is forwarded immediately and retains its own event time.
 */
public final class RootInputClient implements AutoCloseable {
    private static final String TAG = "BackDeckInput";
    private static final int MOVE_BACKLOG_LIMIT = 8;
    private final String apkPath;
    private final int displayId;
    private final int virtualWidth;
    private final int virtualHeight;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "backdeck-input-writer");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "backdeck-input-watchdog");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger pending = new AtomicInteger();
    private volatile boolean ready;
    private volatile boolean closed;
    private volatile String lastError = "starting";
    private volatile Process process;
    private volatile BufferedWriter pipe;
    private boolean forwardingGesture;

    /** Display id and dimensions are immutable, preventing injection to any other screen. */
    public RootInputClient(Context context, int displayId, int virtualWidth, int virtualHeight) {
        if (displayId < 2 || virtualWidth < 100 || virtualHeight < 100) {
            throw new IllegalArgumentException("A live virtual display is required");
        }
        this.apkPath = context.getApplicationInfo().sourceDir;
        this.displayId = displayId;
        this.virtualWidth = virtualWidth;
        this.virtualHeight = virtualHeight;
    }

    /** Returns at once; call {@link #isReady()} before forwarding a gesture. */
    public void startAsync() {
        if (!closed) worker.execute(this::startOnWorker);
    }

    public boolean isReady() { return ready && !closed; }

    public String lastError() { return lastError; }

    /**
     * Call from TextureView.OnTouchListener for EVERY event. Coordinates are local
     * to that view, so the method scales them to the virtual display dimensions.
     * Do not add the physical rear screen's camera-safe left offset.
     */
    public boolean sendMotion(MotionEvent event, int viewWidth, int viewHeight) {
        if (closed || viewWidth <= 0 || viewHeight <= 0) return false;
        int masked = event.getActionMasked();
        if (masked == MotionEvent.ACTION_DOWN) {
            forwardingGesture = isReady();
        } else if (!forwardingGesture) {
            return false;
        }
        if (!isReady()) {
            forwardingGesture = false;
            return false;
        }
        if (masked != MotionEvent.ACTION_DOWN && masked != MotionEvent.ACTION_UP
                && masked != MotionEvent.ACTION_MOVE && masked != MotionEvent.ACTION_CANCEL
                && masked != MotionEvent.ACTION_POINTER_DOWN
                && masked != MotionEvent.ACTION_POINTER_UP) {
            return false;
        }
        int count = event.getPointerCount();
        if (count < 1 || count > 10) return false;
        if (masked == MotionEvent.ACTION_MOVE && pending.get() >= MOVE_BACKLOG_LIMIT) {
            // Never replay stale move samples after a temporarily busy target app.
            return true;
        }
        float scaleX = (float) virtualWidth / viewWidth;
        float scaleY = (float) virtualHeight / viewHeight;
        StringBuilder frame = new StringBuilder(32 + count * 48);
        frame.append("M,").append(event.getDownTime()).append(',')
                .append(event.getEventTime()).append(',')
                .append(event.getAction()).append(',').append(count);
        for (int i = 0; i < count; i++) {
            frame.append(',').append(event.getPointerId(i))
                    .append(',').append(clamp(event.getX(i) * scaleX, virtualWidth))
                    .append(',').append(clamp(event.getY(i) * scaleY, virtualHeight))
                    .append(',').append(event.getPressure(i))
                    .append(',').append(event.getSize(i))
                    .append(',').append(event.getToolType(i));
        }
        enqueue(frame.toString());
        if (masked == MotionEvent.ACTION_UP || masked == MotionEvent.ACTION_CANCEL) {
            forwardingGesture = false;
        }
        return true;
    }

    /** Inject KEYCODE_BACK into the same virtual display, not the physical rear host. */
    public void sendBack() {
        if (isReady()) enqueue("B");
    }

    /** Release a gesture if the host stops intercepting touch halfway through. */
    public void cancelGesture() {
        forwardingGesture = false;
        if (isReady()) enqueue("C");
    }

    private void enqueue(String frame) {
        if (closed) return;
        pending.incrementAndGet();
        worker.execute(() -> {
            try {
                BufferedWriter current = pipe;
                if (!ready || current == null) return;
                current.write(frame);
                current.newLine();
                current.flush();
            } catch (Exception e) {
                fail("write failed: " + e);
            } finally {
                pending.decrementAndGet();
            }
        });
    }

    private void startOnWorker() {
        if (closed || ready) return;
        // Most Android releases accept the runtime flag. The fallback handles OEM
        // app_process builds that reject it, without changing global hidden-API policy.
        if (!launchOnce(true) && !closed) launchOnce(false);
    }

    private boolean launchOnce(boolean disableHiddenChecks) {
        Process candidate = null;
        ScheduledFuture<?> timeout = null;
        try {
            String command = "export CLASSPATH=" + quote(apkPath)
                    + "; exec /system/bin/app_process "
                    + (disableHiddenChecks ? "-Xno-hidden-api-checks " : "")
                    + "/system/bin com.seagull.backdeck.RootInputDaemon "
                    + displayId + " " + virtualWidth + " " + virtualHeight;
            candidate = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true).start();
            process = candidate;
            final Process pendingProcess = candidate;
            timeout = watchdog.schedule(() -> {
                if (!ready) pendingProcess.destroyForcibly();
            }, 8, TimeUnit.SECONDS);
            BufferedReader status = new BufferedReader(new InputStreamReader(
                    candidate.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while (!closed && (line = status.readLine()) != null) {
                if (line.equals("READY")) {
                    pipe = new BufferedWriter(new OutputStreamWriter(
                            candidate.getOutputStream(), StandardCharsets.UTF_8));
                    ready = true;
                    lastError = "";
                    Log.i(TAG, "persistent ROOT input ready for display=" + displayId);
                    if (timeout != null) timeout.cancel(false);
                    final BufferedReader statusReader = status;
                    final Process liveProcess = candidate;
                    Thread monitor = new Thread(() -> monitorStatus(statusReader, liveProcess),
                            "backdeck-input-status");
                    monitor.setDaemon(true);
                    monitor.start();
                    return true;
                }
                if (line.startsWith("ERROR ")) {
                    lastError = line;
                    Log.e(TAG, line);
                    break;
                }
                if (!line.isEmpty()) Log.d(TAG, "daemon: " + line);
            }
            if (lastError.isEmpty() || "starting".equals(lastError)) {
                lastError = "input daemon exited before READY";
            }
        } catch (Exception e) {
            lastError = "input daemon launch failed: " + e;
            Log.e(TAG, lastError, e);
        } finally {
            if (timeout != null) timeout.cancel(false);
            if (!ready && candidate != null) candidate.destroyForcibly();
        }
        return false;
    }

    private void monitorStatus(BufferedReader status, Process liveProcess) {
        try {
            String line;
            while ((line = status.readLine()) != null) {
                if (line.startsWith("ERROR ")) {
                    if (process == liveProcess) fail(line);
                    break;
                }
            }
        } catch (Exception e) {
            if (!closed && process == liveProcess) fail("input daemon read failed: " + e);
        } finally {
            if (!closed && ready && process == liveProcess) fail("input daemon exited");
        }
    }

    private void fail(String error) {
        ready = false;
        lastError = error;
        Log.e(TAG, error);
        Process current = process;
        if (current != null) current.destroyForcibly();
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        ready = false;
        forwardingGesture = false;
        Process starting = process;
        if (starting != null && pipe == null) starting.destroyForcibly();
        // Keep the daemon alive just long enough to receive CANCEL and QUIT.
        worker.execute(() -> {
            try {
                BufferedWriter current = pipe;
                if (current != null) {
                    current.write("C\nQ\n");
                    current.flush();
                    current.close();
                }
            } catch (Exception ignored) { }
            Process currentProcess = process;
            if (currentProcess != null) {
                try {
                    if (!currentProcess.waitFor(500, TimeUnit.MILLISECONDS)) {
                        currentProcess.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    currentProcess.destroyForcibly();
                }
            }
            watchdog.shutdownNow();
        });
        worker.shutdown();
    }

    private static float clamp(float coordinate, int extent) {
        if (!Float.isFinite(coordinate)) return 0;
        return Math.max(0, Math.min(extent - 1, coordinate));
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}

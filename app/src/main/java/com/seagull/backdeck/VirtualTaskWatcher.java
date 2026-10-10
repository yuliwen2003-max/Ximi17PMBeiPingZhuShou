package com.seagull.backdeck;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Watches the target task on an app-owned virtual display.
 *
 * <p>Android does not expose another app's task lifecycle to an ordinary app. In particular,
 * {@code ActivityManager.getAppTasks()} only returns this package's tasks. A rooted, bounded
 * {@code dumpsys activity activities} sample is therefore used. The sample is intentionally
 * limited to the first few lines of this display's section, not the entire activity dump.
 * Unknown/root-error samples never count as an exited app.</p>
 *
 * <p>Usage: create after the VirtualDisplay is created, call {@link #start()} just before
 * launching its target, call {@link #onBackForwarded()} after sending Back to that display,
 * and {@link #close()} before releasing the display. The callback is delivered on the main
 * thread exactly once. The host should finish itself in the callback, revealing the rear
 * dashboard Activity below it; it must not finish the dashboard.</p>
 */
public final class VirtualTaskWatcher implements AutoCloseable {
    private static final String TAG = "BackDeckTaskWatch";
    private static final long NORMAL_POLL_MS = 1_500;
    private static final long STARTUP_GRACE_MS = 10_000;
    private static final long FAST_BACK_MS = 2_500;

    public enum Reason {
        /** Target launched but no visible task ever appeared on the virtual display. */
        STARTUP_TIMEOUT,
        /** Target task exited or migrated away, leaving this display without a visible app. */
        NO_VISIBLE_TASK,
        /** The virtual display itself disappeared from ActivityTaskManager. */
        DISPLAY_GONE
    }

    public interface Listener {
        void onVirtualTaskGone(Reason reason);
    }

    enum Presence { VISIBLE, EMPTY, DISPLAY_MISSING, UNKNOWN }

    private final int displayId;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "backdeck-task-watch");
        thread.setDaemon(true);
        return thread;
    });

    private volatile boolean closed;
    private volatile boolean started;
    private boolean everVisible;
    private int misses;
    private long firstMissAt;
    private long startedAt;
    private volatile long fastUntil;
    private boolean notified;

    public VirtualTaskWatcher(int virtualDisplayId, Listener listener) {
        if (virtualDisplayId < 2) throw new IllegalArgumentException("not a virtual display");
        if (listener == null) throw new IllegalArgumentException("listener is null");
        this.displayId = virtualDisplayId;
        this.listener = listener;
    }

    public synchronized void start() {
        if (started || closed) return;
        started = true;
        startedAt = SystemClock.uptimeMillis();
        worker.scheduleWithFixedDelay(this::poll, 450, NORMAL_POLL_MS, TimeUnit.MILLISECONDS);
    }

    /** Accelerate checks after a physical rear-screen Back gesture is forwarded. */
    public void onBackForwarded() {
        if (closed || !started) return;
        fastUntil = SystemClock.uptimeMillis() + FAST_BACK_MS;
        try {
            worker.execute(this::poll);
            worker.schedule(this::poll, 200, TimeUnit.MILLISECONDS);
            worker.schedule(this::poll, 450, TimeUnit.MILLISECONDS);
            worker.schedule(this::poll, 750, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) { /* closing */ }
    }

    private void poll() {
        if (closed || notified) return;
        Presence state = sample();
        if (closed || notified) return;
        long now = SystemClock.uptimeMillis();
        if (state == Presence.UNKNOWN) return;
        if (state == Presence.VISIBLE) {
            everVisible = true;
            misses = 0;
            firstMissAt = 0;
            return;
        }
        if (misses++ == 0) firstMissAt = now;
        if (!everVisible) {
            if (now - startedAt >= STARTUP_GRACE_MS && misses >= 3) {
                notifyGone(Reason.STARTUP_TIMEOUT);
            }
            return;
        }
        boolean fast = now < fastUntil;
        long minimumLostMs = fast ? 180 : 1_000;
        int minimumMisses = fast ? 2 : 3;
        if (misses >= minimumMisses && now - firstMissAt >= minimumLostMs) {
            notifyGone(state == Presence.DISPLAY_MISSING
                    ? Reason.DISPLAY_GONE : Reason.NO_VISIBLE_TASK);
        }
    }

    private void notifyGone(Reason reason) {
        notified = true;
        Log.i(TAG, "virtual display=" + displayId + " target gone: " + reason);
        main.post(() -> {
            if (!closed) listener.onVirtualTaskGone(reason);
        });
    }

    private Presence sample() {
        // Numeric ID is validated in the constructor. -m 1 / -A keeps the pipe bounded;
        // never let the huge raw dumpsys output fill the child's stdout pipe.
        String header = "Display #" + displayId + " (activities from top to bottom):";
        String command = "dumpsys activity activities | grep -F -m 1 -A 16 '" + header + "'";
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true).start();
            if (!process.waitFor(4, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                Log.w(TAG, "task sample timed out for display=" + displayId);
                return Presence.UNKNOWN;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream stream = process.getInputStream()) {
                byte[] buffer = new byte[2048];
                int count;
                while ((count = stream.read(buffer)) != -1 && out.size() < 16_384) {
                    out.write(buffer, 0, count);
                }
            }
            String output = out.toString(StandardCharsets.UTF_8.name());
            if (process.exitValue() != 0) {
                // grep exits 1 when the display header is absent. Root/dumpsys failures
                // normally print diagnostics, which must not be mistaken for task exit.
                return output.isEmpty() ? Presence.DISPLAY_MISSING : Presence.UNKNOWN;
            }
            return parseDisplaySection(output, header);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Presence.UNKNOWN;
        } catch (Exception e) {
            Log.w(TAG, "task sample failed", e);
            return Presence.UNKNOWN;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    /** Package-visible for parser tests with captured dumpsys text. */
    static Presence parseDisplaySection(String output, String header) {
        boolean inDisplay = false;
        for (String line : output.split("\\R")) {
            if (!inDisplay) {
                if (line.equals(header)) inDisplay = true;
                continue;
            }
            if (line.startsWith("Display #") || line.startsWith("ActivityTaskSupervisor state:"))
                break;
            String trimmed = line.trim();
            if (!trimmed.startsWith("* Task{")) continue;
            // A nonempty task still belongs to this VD while its panel sleeps/AODs.
            // Requiring visible=true would falsely close the host during DOZE.
            if (trimmed.contains("type=standard") && !trimmed.contains("sz=0"))
                return Presence.VISIBLE;
            if (trimmed.contains("type=home")) return Presence.EMPTY;
        }
        return inDisplay ? Presence.EMPTY : Presence.UNKNOWN;
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        worker.shutdownNow();
    }
}

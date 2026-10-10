package com.seagull.backdeck;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.ComponentName;
import android.hardware.display.DisplayManager;
import android.view.Display;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small, bounded KernelSU/Magisk command bridge. No persistent root daemon. */
public final class RootBridge {
    private static final Pattern REAR_TOP_ACTIVITY = Pattern.compile(
            "topResumedActivity=.*?\\bu\\d+\\s+([A-Za-z_][A-Za-z0-9_.]*)/([A-Za-z_.$][A-Za-z0-9_.$]*)");
    private static final Pattern DISPLAY_TASK_ID = Pattern.compile("\\b#(\\d+)\\b");
    private static final Pattern DISPLAY_TASK_AFFINITY = Pattern.compile(
            "\\bA=\\d+:([^\\s}]+)");
    private static final Pattern DISPLAY_ACTIVITY_PACKAGE = Pattern.compile(
            "^\\s*packageName=([A-Za-z_][A-Za-z0-9_.]*)(?:\\s|$)");

    private RootBridge() {}

    public static final class Result {
        public final boolean ok;
        public final String output;
        Result(boolean ok, String output) {
            this.ok = ok;
            this.output = output;
        }
    }

    public static boolean hasRoot() {
        Result result = run("id", 15);
        return result.ok && result.output.contains("uid=0");
    }

    /** Allow only this package's physical-rear gesture window; never grant other apps. */
    public static Result grantOwnOverlay(Context context) {
        String packageName = context.getPackageName();
        if (!"com.seagull.backdeck".equals(packageName)) {
            return new Result(false, "Unexpected package");
        }
        return run("appops set " + quote(packageName) + " SYSTEM_ALERT_WINDOW allow", 10);
    }

    public static boolean startRearHost(Context context) {
        return startRearHostChecked(context).ok;
    }

    /** Launch and confirm the task is actually on the physical rear display. */
    public static synchronized Result startRearHostChecked(Context context) {
        int displayId = DisplayRouting.rearDisplayId(context);
        if (displayId == Display.INVALID_DISPLAY) {
            return new Result(false, "未找到物理背屏（已排除虚拟显示）");
        }
        // The rear panel may have entered OEM AOD while the front settings stay visible.
        run("input -d " + displayId + " keyevent 224", 5); // KEYCODE_WAKEUP
        DisplayManager manager = context.getSystemService(DisplayManager.class);
        Display rear = manager == null ? null : manager.getDisplay(displayId);
        ComponentName alreadyTop = rearTopActivity(context, displayId);
        if (rear != null && rear.getState() == Display.STATE_ON
                && new ComponentName(context, RearActivity.class).equals(alreadyTop)) {
            return new Result(true, "背屏表盘已在顶部；未重复启动");
        }
        String component = new ComponentName(context, RearActivity.class).flattenToString();
        // A safe-area host can be sitting above RearActivity in the same task.  Reorder
        // the clock above it instead of CLEAR_TOP: the host/virtual display stays as the
        // one reusable rear-only app session and is not recreated on the next drawer tap.
        Result launch = run("am start-activity -W --user 0 --display " + displayId
                + " --activity-reorder-to-front --activity-single-top -n "
                + quote(component), 15);
        if (!launch.ok) return new Result(false, "display=" + displayId + "; " + launch.output);
        String top = null;
        boolean visible = false;
        // am -W can return before HyperOS commits the rear-screen transition.
        for (int attempt = 0; attempt < 8; attempt++) {
            Result task = run("dumpsys activity activities | grep -A 40 "
                    + quote("Display #" + displayId + " (activities from top to bottom):"), 8);
            if (task.ok) {
                for (String line : task.output.split("\\R")) {
                    if (line.contains("topResumedActivity=")) { top = line.trim(); break; }
                }
            }
            // This operation is specifically a return-to-clock.  Treating the retained
            // VirtualHost as success would leave the app visible and invite a second tap.
            if (top != null && top.contains("com.seagull.backdeck/.RearActivity")) {
                visible = true;
                break;
            }
            try { Thread.sleep(250); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return new Result(false, "等待背屏切换时被中断");
            }
        }
        if (!visible) {
            return new Result(false, "启动命令已执行，但物理背屏未显示助手。当前顶部："
                    + (top == null ? "未知" : top));
        }
        rear = manager == null ? null : manager.getDisplay(displayId);
        if (rear != null && rear.getState() == Display.STATE_OFF) {
            return new Result(false, "助手已在物理背屏，但屏幕仍处于 OFF 状态");
        }
        return new Result(true, "物理背屏 display=" + displayId + "; " + top);
    }

    /** Read only the physical rear display's focused activity; never infer it from front focus. */
    public static boolean isOemRearHomeTop(Context context) {
        ComponentName top = rearTopActivity(context, DisplayRouting.rearDisplayId(context));
        return top != null
                && "com.xiaomi.subscreencenter".equals(top.getPackageName())
                && "com.xiaomi.subscreencenter.SubScreenLauncher".equals(top.getClassName());
    }

    /** Never let the default/front display's top task masquerade as the rear foreground. */
    static ComponentName rearTopActivity(Context context, int displayId) {
        if (displayId == Display.INVALID_DISPLAY) return null;
        String marker = "Display #" + displayId + " (activities from top to bottom):";
        Result snapshot = run("dumpsys activity activities | grep -F -A 80 " + quote(marker), 8);
        if (!snapshot.ok) return null;
        boolean inRear = false;
        for (String line : snapshot.output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.contains(marker)) {
                inRear = true;
            } else if (inRear && trimmed.startsWith("Display #")) {
                break;
            } else if (inRear && trimmed.contains("topResumedActivity=")) {
                Matcher match = REAR_TOP_ACTIVITY.matcher(trimmed);
                return match.find() ? ComponentName.unflattenFromString(
                        match.group(1) + "/" + match.group(2)) : null;
            }
        }
        return null;
    }

    public static Result showOemRearHome(Context context) {
        int displayId = DisplayRouting.rearDisplayId(context);
        if (displayId == Display.INVALID_DISPLAY) return new Result(false, "Rear display unavailable");
        return run("am start-activity -W --user 0 --display " + displayId
                + " -n com.xiaomi.subscreencenter/.SubScreenLauncher", 15);
    }

    public static Result launch(Intent intent, int displayId) {
        if (displayId < 0) return new Result(false, "Display unavailable");
        StringBuilder command = new StringBuilder("am start-activity -W --user 0 --display ")
                .append(displayId);
        if (intent.getFlags() != 0) {
            command.append(" -f 0x").append(Integer.toHexString(intent.getFlags()));
        }
        if (intent.getComponent() != null) {
            command.append(" -n ").append(quote(intent.getComponent().flattenToString()));
        }
        if (intent.getAction() != null) {
            command.append(" -a ").append(quote(intent.getAction()));
        }
        if (intent.getData() != null) {
            command.append(" -d ").append(quote(intent.getData().toString()));
        }
        if (intent.getPackage() != null && intent.getComponent() == null) {
            command.append(" -p ").append(quote(intent.getPackage()));
        }
        return run(command.toString(), 15);
    }

    /**
     * A rear app must be isolated from its front-screen task. NEW_TASK alone may
     * select and move that front task onto the rear display; MULTIPLE_TASK alone
     * creates a duplicate every time. Reuse only a task proven to live on the
     * physical rear display, or create a genuinely new task with both flags.
     */
    public static synchronized Result launchPhysicalRear(Context context, Intent intent, int displayId) {
        if (displayId == Display.DEFAULT_DISPLAY
                || displayId != DisplayRouting.rearDisplayId(context)) {
            return new Result(false, "Physical rear display unavailable");
        }
        String targetPackage = intent.getComponent() != null
                ? intent.getComponent().getPackageName() : intent.getPackage();
        if (targetPackage == null) {
            ComponentName resolved = intent.resolveActivity(context.getPackageManager());
            if (resolved != null) targetPackage = resolved.getPackageName();
        }
        if (targetPackage != null) {
            TaskLookup existing = findRearTasks(displayId, targetPackage);
            if (!existing.ok) return new Result(false, existing.error);
            if (!existing.tasks.isEmpty()) {
                // The dump is ordered top-to-bottom, so this is the existing task the
                // user last saw.  Crucially it was discovered inside Display #1 only;
                // a matching front-screen task is never eligible for moving/reusing.
                int existingTaskId = existing.tasks.get(0).taskId;
                ComponentName top = rearTopActivity(context, displayId);
                if (top != null && targetPackage.equals(top.getPackageName())) {
                    pruneDuplicateRearTasks(existing.tasks, existingTaskId);
                    return new Result(true, "Rear task already on top: " + existingTaskId);
                }
                ActivityManager tasks = context.getSystemService(ActivityManager.class);
                if (tasks == null) return new Result(false, "ActivityManager unavailable");
                ActivityOptions options = ActivityOptions.makeBasic();
                options.setLaunchDisplayId(displayId);
                try {
                    tasks.moveTaskToFront(existingTaskId, 0, options.toBundle());
                } catch (RuntimeException error) {
                    // Never retry without MULTIPLE_TASK: that can steal a front task.
                    return new Result(false, "Could not reuse rear task " + existingTaskId
                            + ": " + error.getClass().getSimpleName());
                }
                for (int attempt = 0; attempt < 8; attempt++) {
                    top = rearTopActivity(context, displayId);
                    if (top != null && targetPackage.equals(top.getPackageName())) {
                        pruneDuplicateRearTasks(existing.tasks, existingTaskId);
                        return new Result(true, "Reused rear task " + existingTaskId);
                    }
                    try { Thread.sleep(150); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return new Result(false, "Waiting for rear task interrupted");
                    }
                }
                return new Result(false, "Rear task did not come to front: " + existingTaskId);
            }
        }
        Intent isolated = new Intent(intent);
        isolated.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        Result launched = launch(isolated, displayId);
        if (!launched.ok || targetPackage == null) return launched;
        for (int attempt = 0; attempt < 8; attempt++) {
            ComponentName top = rearTopActivity(context, displayId);
            if (top != null && targetPackage.equals(top.getPackageName())) {
                return new Result(true, "New isolated rear task; " + launched.output);
            }
            try { Thread.sleep(150); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return new Result(false, "Waiting for rear launch interrupted");
            }
        }
        return new Result(false, "Launch completed but target is not on rear display; "
                + launched.output);
    }

    private static final class TaskLookup {
        final boolean ok;
        final List<RearTask> tasks;
        final String error;
        TaskLookup(boolean ok, List<RearTask> tasks, String error) {
            this.ok = ok;
            this.tasks = tasks;
            this.error = error;
        }
    }

    private static final class RearTask {
        final int taskId;
        final boolean independentRootTask;
        boolean matchesTarget;
        RearTask(int taskId, boolean independentRootTask) {
            this.taskId = taskId;
            this.independentRootTask = independentRootTask;
        }
    }

    /**
     * Only the physical rear display block is parsed, never the front block.  Matching the
     * task affinity alone is not enough: several apps use a custom affinity, which was the
     * reason an already-open rear app could be missed and launched as a duplicate.
     */
    private static TaskLookup findRearTasks(int displayId, String packageName) {
        String marker = "Display #" + displayId + " (activities from top to bottom):";
        String awk = "index($0, \"" + marker + "\") == 1 "
                + "{ rear=1; print \"REAR_TASKS_FOUND\"; next } "
                + "rear && /^Display #[0-9]/ { exit } "
                + "rear && /^[ \\t]*\\* Task\\{/ { print \"T\" $0; next } "
                + "rear && /^[ \\t]*packageName=/ { print \"P\" $0; next }";
        Result snapshot = run("dumpsys activity activities | awk " + quote(awk), 8);
        if (!snapshot.ok || !snapshot.output.contains("REAR_TASKS_FOUND")) {
            return new TaskLookup(false, new ArrayList<>(), "Could not inspect rear tasks");
        }
        List<RearTask> allTasks = new ArrayList<>();
        RearTask current = null;
        for (String line : snapshot.output.split("\\R")) {
            if (line.startsWith("T")) {
                String header = line.substring(1);
                Matcher id = DISPLAY_TASK_ID.matcher(header);
                if (!id.find()) continue;
                try {
                    // `am stack remove` only accepts an independent/root task.  Nested
                    // children (for example the OEM home child task) are reusable but are
                    // never candidates for duplicate cleanup.
                    current = new RearTask(Integer.parseInt(id.group(1)),
                            header.contains("type=standard")
                                    && !header.contains("rootTaskId="));
                } catch (NumberFormatException ignored) {
                    return new TaskLookup(false, new ArrayList<>(), "Invalid rear task ID");
                }
                Matcher affinity = DISPLAY_TASK_AFFINITY.matcher(header);
                current.matchesTarget = affinity.find()
                        && packageName.equals(affinity.group(1));
                allTasks.add(current);
                continue;
            }
            if (line.startsWith("P") && current != null) {
                Matcher activityPackage = DISPLAY_ACTIVITY_PACKAGE.matcher(line.substring(1));
                if (activityPackage.find() && packageName.equals(activityPackage.group(1))) {
                    current.matchesTarget = true;
                }
            }
        }
        List<RearTask> matches = new ArrayList<>();
        for (RearTask task : allTasks) {
            if (!task.matchesTarget) continue;
            boolean alreadyRecorded = false;
            for (RearTask prior : matches) {
                if (prior.taskId == task.taskId) { alreadyRecorded = true; break; }
            }
            if (!alreadyRecorded) matches.add(task);
        }
        return new TaskLookup(true, matches, null);
    }

    /**
     * Remove only stale, independently rooted copies on the physical rear display.  We retain
     * the topmost task the user actually selected and never enumerate or touch Display #0, so a
     * front-screen session cannot be moved or closed as part of de-duplication.
     */
    private static void pruneDuplicateRearTasks(List<RearTask> tasks, int retainedTaskId) {
        for (RearTask task : tasks) {
            if (task.taskId == retainedTaskId || !task.independentRootTask) continue;
            // Android 16 keeps task removal under the legacy `am stack` shell subcommand.
            // This executes as root only after the task has been proven to belong to Display #1.
            run("am stack remove " + task.taskId, 10);
        }
    }

    /** Publishes the bounded rear-app allowlist for the system_server LSPosed hook. */
    public static Result putGlobalSetting(String key, String value) {
        if (!"backdeck_allowed_packages".equals(key) || value == null || value.length() > 65_536) {
            return new Result(false, "Invalid allowlist setting");
        }
        return run("settings put global " + quote(key) + " " + quote(value), 15);
    }

    /** Inject a mapped pointer gesture into an app-owned virtual display. */
    public static Result injectTouch(int displayId, int x1, int y1, int x2, int y2,
                                     long durationMs) {
        if (displayId < 2) return new Result(false, "Not a virtual display");
        int duration = (int) Math.max(80, Math.min(5000, durationMs));
        String command;
        if (Math.abs(x2 - x1) < 8 && Math.abs(y2 - y1) < 8 && duration < 450) {
            command = "input -d " + displayId + " tap " + x2 + " " + y2;
        } else {
            command = "input -d " + displayId + " swipe " + x1 + " " + y1
                    + " " + x2 + " " + y2 + " " + duration;
        }
        return run(command, 8);
    }

    public static Result injectBack(int displayId) {
        if (displayId < 2) return new Result(false, "Not a virtual display");
        return run("input -d " + displayId + " keyevent 4", 8);
    }

    /** Insert the bridge editor's explicitly committed clipboard text into its virtual target. */
    public static Result injectPaste(int displayId) {
        if (displayId < 2) return new Result(false, "Not a virtual display");
        // KEYCODE_PASTE is handled by the focused target editor and preserves Unicode clipboard
        // text, unlike shell `input text` which cannot faithfully send Chinese composition.
        return run("input -d " + displayId + " keyevent 279", 8);
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static Result run(String command, int seconds) {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true).start();
            boolean finished = process.waitFor(seconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new Result(false, "root command timed out");
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream stream = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = stream.read(buffer)) != -1) output.write(buffer, 0, read);
            }
            String text = output.toString(StandardCharsets.UTF_8.name());
            boolean success = process.exitValue() == 0 && !text.contains("Error:")
                    && !text.contains("Exception") && !text.contains("not allowed");
            if (command.startsWith("am ")) {
                success = success && (text.contains("Status: ok") || text.contains("Activity: "));
            }
            return new Result(success, text);
        } catch (Exception e) {
            if (process != null) process.destroyForcibly();
            return new Result(false, e.toString());
        }
    }
}

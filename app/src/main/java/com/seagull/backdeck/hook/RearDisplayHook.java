package com.seagull.backdeck.hook;

import android.annotation.SuppressLint;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.Context;
import android.content.ComponentName;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.DisplayCutout;
import android.view.WindowManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Small, deliberately narrow HyperOS rear-display compatibility hook.
 *
 * <p>The routing hooks run only in system_server. They change a denied launch to an allowed launch
 * exclusively for BackDeck or an explicitly enabled rear-screen action target on this device's
 * rear display (logical display 1), or for same-UID navigation already inside BackDeck's own
 * embedded virtual display. The allowlist is published through Settings.Global by a
 * root-authorized app action; ordinary apps cannot write that setting. The hook never reads
 * BackDeck's private SharedPreferences from system_server and fails closed if settings cannot
 * be read.</p>
 */
public final class RearDisplayHook extends XposedModule {
    private static final String TAG = "BackDeckHook";
    private static final String APP_PACKAGE = "com.seagull.backdeck";
    private static final String XIAOMI_REAR_PACKAGE = "com.xiaomi.subscreencenter";
    private static final String REAR_ACTIVITY = APP_PACKAGE + ".RearActivity";
    private static final String VIRTUAL_HOST_ACTIVITY = APP_PACKAGE + ".VirtualHostActivity";
    private static final String OEM_GESTURE_HOME = "SubScreenGestureHome";
    private static final String OEM_GESTURE_BACK = "SubScreenGestureBack";
    private static final String DIRECT_HOME_GESTURE = "BackDeckDirectHomeGesture";
    private static final int TYPE_APPLICATION_OVERLAY = 2038;
    // The two observed OEM gesture windows use TYPE_MAGNIFICATION_OVERLAY on this ROM.
    private static final int OEM_GESTURE_WINDOW_TYPE = 2027;
    // Xiaomi's own SubScreenLauncher uses this WindowManager.LayoutParams.miuiFlags bit.
    private static final int MIUI_DISABLE_SUBSCREEN_DOUBLE_TAP = 0x100;
    private static final int REAR_DISPLAY_ID = 1;
    // android.view.Display.TYPE_VIRTUAL is hidden from the public SDK.
    private static final int DISPLAY_TYPE_VIRTUAL = 5;
    private static final String EMBEDDED_DISPLAY_NAME = "BackDeckEmbedded";
    private static final String ALLOWLIST_SETTING = "backdeck_allowed_packages";
    /** InputMethodManagerService policy value: render the IME on the target display. */
    private static final int DISPLAY_IME_POLICY_LOCAL = 0;
    /** Keep an explicit platform HIDE policy intact; only replace normal fallback routing. */
    private static final int DISPLAY_IME_POLICY_FALLBACK_DISPLAY = 1;
    private static final long REFRESH_INTERVAL_MS = 2_000L;

    private volatile Context systemContext;
    private volatile ClassLoader systemServerClassLoader;
    private volatile Set<String> allowedPackages = Collections.emptySet();
    private volatile long lastRefreshElapsed;
    private volatile int backDeckUid = -1;
    private volatile int xiaomiRearUid = -1;
    private volatile long lastGestureYieldLogElapsed;
    private volatile long lastVirtualAllowLogElapsed;
    private volatile long lastVirtualErrorLogElapsed;
    private volatile long lastImePolicyLogElapsed;
    private volatile long lastImeLayeringLogElapsed;
    private volatile long lastWmsImePolicyLogElapsed;

    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        ClassLoader loader = param.getClassLoader();
        systemServerClassLoader = loader;
        systemContext = findSystemContext(loader);
        installXiaomiHooks(loader);
        installShowWhenLockedHook(loader);
        installFrameworkHook(loader);
        installImeDisplayPolicyHook(loader);
        installBackDeckWmsImePolicyHook(loader);
        installBackDeckImeLayeringHook(loader);
        installRearBoundsHook(loader);
        installVirtualOrientationHook(loader);
        installRearGestureInputHook(loader);
        installRearDoubleTapFlagHook(loader);
        installRearOverlayPermissionHook(loader);
    }

    /**
     * HyperOS gates {@code Activity#setShowWhenLocked(true)} through AppOp 10020 in
     * {@code ActivityRecordImpl.canShowWhenLocked()}.  That rejects ordinary apps even when
     * their manifest and window flags request the documented behavior.  Do not turn that into
     * a package-wide exception: only the two BackDeck-owned host activities, already assigned
     * to the physical rear display, may remain visible above the keyguard.  This does not set
     * {@code dismissKeyguard}, alter the keyguard state, or grant the capability to any target
     * application rendered by the host.
     */
    private void installShowWhenLockedHook(ClassLoader loader) {
        final Class<?> implementation;
        try {
            implementation = loader.loadClass("com.android.server.wm.ActivityRecordImpl");
        } catch (ClassNotFoundException error) {
            log(Log.WARN, TAG, "Xiaomi ActivityRecordImpl unavailable for lockscreen policy", error);
            return;
        }
        for (Method method : implementation.getDeclaredMethods()) {
            if (!"canShowWhenLocked".equals(method.getName())
                    || method.getReturnType() != boolean.class
                    || method.getParameterCount() != 0) {
                continue;
            }
            try {
                hook(method).intercept(chain -> {
                    try {
                        // Handle the exception before Xiaomi performs its AppOp denial.  The
                        // predicate binds it to an exact component and display, failing closed
                        // for every uncertain or third-party activity.
                        if (isBackDeckLockscreenHostOnPhysicalRear(chain.getThisObject())) {
                            return Boolean.TRUE;
                        }
                    } catch (Throwable ignored) {
                        // Preserve the OEM policy if a ROM update changes these internals.
                    }
                    return chain.proceed();
                });
                log(Log.INFO, TAG, "Installed scoped BackDeck show-when-locked policy");
                return;
            } catch (Throwable error) {
                log(Log.ERROR, TAG, "Could not hook BackDeck show-when-locked policy", error);
            }
        }
        log(Log.WARN, TAG, "ActivityRecordImpl.canShowWhenLocked unavailable", null);
    }

    /** Exact host + physical display predicate for the lockscreen exception. */
    private static boolean isBackDeckLockscreenHostOnPhysicalRear(Object implementation)
            throws ReflectiveOperationException {
        Object activity = readField(implementation, "mAr");
        if (activity == null || !"com.android.server.wm.ActivityRecord"
                .equals(activity.getClass().getName())) {
            return false;
        }
        Integer displayId = invokeInt(activity, "getDisplayId");
        if (displayId == null || displayId != REAR_DISPLAY_ID) return false;
        Object component = readField(activity, "mActivityComponent");
        if (!(component instanceof ComponentName)) return false;
        ComponentName name = (ComponentName) component;
        if (!APP_PACKAGE.equals(name.getPackageName())) return false;
        String className = name.getClassName();
        return REAR_ACTIVITY.equals(className) || VIRTUAL_HOST_ACTIVITY.equals(className);
    }

    /**
     * HyperOS rejects non-system 2000-series windows on the independent rear display before
     * ViewRootImpl can attach them. Allow only our own TYPE_APPLICATION_OVERLAY on display 1;
     * the platform's ordinary overlay/AppOps permission checks still run separately.
     */
    private void installRearOverlayPermissionHook(ClassLoader loader) {
        final Class<?> implementation;
        final Method uidForPid;
        try {
            implementation = loader.loadClass("com.android.server.wm.WindowManagerServiceImpl");
            uidForPid = android.os.Process.class.getDeclaredMethod("getUidForPid", int.class);
        } catch (ReflectiveOperationException | RuntimeException error) {
            log(Log.WARN, TAG, "Rear overlay owner validation unavailable", error);
            return;
        }
        for (Method method : implementation.getDeclaredMethods()) {
            if (!"hasPermission".equals(method.getName())
                    || method.getReturnType() != boolean.class) continue;
            Class<?>[] args = method.getParameterTypes();
            if (args.length != 4 || args[0] != String.class || args[1] != int.class
                    || args[2] != int.class || args[3] != int.class) continue;
            try {
                hook(method).intercept(chain -> {
                    if (APP_PACKAGE.equals(chain.getArg(0))
                            && Integer.valueOf(TYPE_APPLICATION_OVERLAY).equals(chain.getArg(1))
                            && Integer.valueOf(REAR_DISPLAY_ID).equals(chain.getArg(2))) {
                        // LayoutParams.packageName is caller-controlled; bind the exception to
                        // the actual Binder caller process instead of trusting that string.
                        int ownerUid = getBackDeckUid();
                        Object pid = chain.getArg(3);
                        if (ownerUid >= 0 && pid instanceof Integer && (Integer) pid > 0) {
                            try {
                                if (Integer.valueOf(ownerUid).equals(uidForPid.invoke(null, pid))) {
                                    return Boolean.TRUE;
                                }
                            } catch (ReflectiveOperationException | RuntimeException ignored) {
                                // Fail closed: preserve Xiaomi's original permission result.
                            }
                        }
                    }
                    return chain.proceed();
                });
                log(Log.INFO, TAG, "Installed scoped physical rear overlay permission policy");
                return;
            } catch (Throwable error) {
                log(Log.ERROR, TAG, "Could not hook rear overlay permission", error);
            }
        }
        log(Log.WARN, TAG, "WindowManagerServiceImpl.hasPermission unavailable", null);
    }

    /**
     * The OEM's full-screen SPY gesture monitor interprets a double tap as rear Home/Sleep.
     * Xiaomi's own launcher opts out via getInputMiuiFlags() bit 0x100. Report the same bit
     * only for our host windows and user-approved apps on the physical rear display; do not
     * change the OFF-screen wake gesture, the front display, or an unconfigured app.
     */
    private void installRearDoubleTapFlagHook(ClassLoader loader) {
        final Class<?> windowState;
        try {
            windowState = loader.loadClass("com.android.server.wm.WindowState");
        } catch (ClassNotFoundException error) {
            log(Log.WARN, TAG, "WindowState unavailable for rear double tap", error);
            return;
        }
        for (Method method : windowState.getDeclaredMethods()) {
            if (!"getInputMiuiFlags".equals(method.getName())
                    || method.getParameterCount() != 0
                    || method.getReturnType() != int.class) continue;
            try {
                hook(method).intercept(chain -> {
                    Object original = chain.proceed();
                    if (!(original instanceof Integer)) return original;
                    try {
                        return shouldDisableRearDoubleTap(chain.getThisObject())
                                ? ((Integer) original) | MIUI_DISABLE_SUBSCREEN_DOUBLE_TAP
                                : original;
                    } catch (Throwable error) {
                        return original;
                    }
                });
                log(Log.INFO, TAG, "Installed physical rear double-tap window flag policy");
                return;
            } catch (Throwable error) {
                log(Log.ERROR, TAG, "Could not hook rear double-tap flag", error);
            }
        }
        log(Log.WARN, TAG, "WindowState.getInputMiuiFlags unavailable", null);
    }

    private boolean shouldDisableRearDoubleTap(Object window)
            throws ReflectiveOperationException {
        if (window == null || !"com.android.server.wm.WindowState"
                .equals(window.getClass().getName())) return false;
        Integer displayId = invokeInt(window, "getDisplayId");
        if (displayId == null || displayId != REAR_DISPLAY_ID) return false;
        Object activity = readField(window, "mActivityRecord");
        if (activity == null || !"com.android.server.wm.ActivityRecord"
                .equals(activity.getClass().getName())) return false;
        String packageName = targetPackage(activity);
        if (APP_PACKAGE.equals(packageName)) {
            Object component = readField(activity, "mActivityComponent");
            if (!(component instanceof ComponentName)) return false;
            String className = ((ComponentName) component).getClassName();
            return REAR_ACTIVITY.equals(className) || VIRTUAL_HOST_ACTIVITY.equals(className);
        }
        // Direct-open apps are included only after a user explicitly configures them.
        return packageName != null && !isSystemOrHostPackage(packageName)
                && isAllowedPackage(packageName);
    }

    /**
     * Xiaomi keeps edge/bottom gesture overlays above ordinary rear-display activities. They
     * should stop taking touch input while BackDeck itself is focused. For a user-approved
     * direct-open app, only the OEM Home strip yields, and only while our own gesture window
     * is actually visible and touchable on this physical display. All other OEM input stays.
     * This changes input eligibility in system_server, not Xiaomi's windows or app processes.
     */
    private void installRearGestureInputHook(ClassLoader loader) {
        final Class<?> windowState;
        try {
            windowState = loader.loadClass("com.android.server.wm.WindowState");
        } catch (ClassNotFoundException e) {
            log(Log.WARN, TAG, "WindowState unavailable for rear gesture input", e);
            return;
        }
        for (Method method : windowState.getDeclaredMethods()) {
            if (!"canReceiveTouchInput".equals(method.getName())
                    || method.getParameterCount() != 0
                    || method.getReturnType() != boolean.class) {
                continue;
            }
            try {
                hook(method).intercept(chain -> {
                    Object original = chain.proceed();
                    if (!Boolean.TRUE.equals(original)) return original;
                    try {
                        if (shouldYieldOemGestureToBackDeck(chain.getThisObject())) {
                            long now = SystemClock.elapsedRealtime();
                            if (now - lastGestureYieldLogElapsed > 30_000L) {
                                lastGestureYieldLogElapsed = now;
                                log(Log.INFO, TAG, "Yielded OEM rear gesture input to BackDeck");
                            }
                            return Boolean.FALSE;
                        }
                        return original;
                    } catch (Throwable error) {
                        // The system input path must remain OEM-original on any uncertainty.
                        return original;
                    }
                });
                log(Log.INFO, TAG, "Installed focused BackDeck rear gesture input policy");
                return;
            } catch (Throwable error) {
                log(Log.ERROR, TAG, "Could not hook rear gesture input policy", error);
            }
        }
        log(Log.WARN, TAG, "WindowState.canReceiveTouchInput unavailable", null);
    }

    private boolean shouldYieldOemGestureToBackDeck(Object window)
            throws ReflectiveOperationException {
        if (window == null || !"com.android.server.wm.WindowState"
                .equals(window.getClass().getName())) return false;
        Integer displayId = invokeInt(window, "getDisplayId");
        if (displayId == null || displayId != REAR_DISPLAY_ID) return false;

        Object attrsValue = readField(window, "mAttrs");
        if (!(attrsValue instanceof WindowManager.LayoutParams)) return false;
        WindowManager.LayoutParams attrs = (WindowManager.LayoutParams) attrsValue;
        CharSequence title = attrs.getTitle();
        if (!XIAOMI_REAR_PACKAGE.equals(attrs.packageName)
                || attrs.type != OEM_GESTURE_WINDOW_TYPE
                || title == null
                || !(OEM_GESTURE_HOME.contentEquals(title)
                        || OEM_GESTURE_BACK.contentEquals(title))) return false;

        // Do not let another process impersonate a similarly titled overlay.
        Object ownerUid = readField(window, "mOwnerUid");
        if (!(ownerUid instanceof Integer)
                || ((Integer) ownerUid) != getXiaomiRearUid()) return false;

        Object displayContent = invoke(window, "getDisplayContent");
        if (displayContent == null) return false;
        Object focusedApp = readField(displayContent, "mFocusedApp");
        if (focusedApp == null || !"com.android.server.wm.ActivityRecord"
                .equals(focusedApp.getClass().getName())) return false;
        Object component = readField(focusedApp, "mActivityComponent");
        if (!(component instanceof ComponentName)) return false;
        ComponentName activity = (ComponentName) component;
        if (APP_PACKAGE.equals(activity.getPackageName())) {
            return REAR_ACTIVITY.equals(activity.getClassName())
                    || VIRTUAL_HOST_ACTIVITY.equals(activity.getClassName());
        }
        return OEM_GESTURE_HOME.contentEquals(title)
                && !isSystemOrHostPackage(activity.getPackageName())
                && isAllowedPackage(activity.getPackageName())
                && hasTouchableDirectGestureOverlay(displayContent);
    }

    private boolean hasTouchableDirectGestureOverlay(Object displayContent) {
        final int ownerUid = getBackDeckUid();
        if (ownerUid < 0) return false;
        try {
            Predicate<Object> isOurWindow = candidate -> {
                try {
                    if (candidate == null || !"com.android.server.wm.WindowState"
                            .equals(candidate.getClass().getName())) return false;
                    Object value = readField(candidate, "mAttrs");
                    if (!(value instanceof WindowManager.LayoutParams)) return false;
                    WindowManager.LayoutParams lp = (WindowManager.LayoutParams) value;
                    if (!APP_PACKAGE.equals(lp.packageName)
                            || lp.type != WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            || !DIRECT_HOME_GESTURE.contentEquals(lp.getTitle())
                            || (lp.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0)
                        return false;
                    Object uid = readField(candidate, "mOwnerUid");
                    return uid instanceof Integer && ((Integer) uid) == ownerUid
                            && Boolean.TRUE.equals(invoke(candidate, "isVisible"));
                } catch (Throwable ignored) {
                    return false;
                }
            };
            return findMethod(displayContent.getClass(), "getWindow", Predicate.class)
                    .invoke(displayContent, isOurWindow) != null;
        } catch (ReflectiveOperationException | SecurityException ignored) {
            return false;
        }
    }

    private int getBackDeckUid() {
        int uid = backDeckUid;
        if (uid >= 0) return uid;
        Context context = systemContext;
        if (context == null) {
            context = findSystemContext(getClass().getClassLoader());
            systemContext = context;
        }
        if (context == null) return -1;
        try {
            uid = context.getPackageManager().getPackageUid(APP_PACKAGE, 0);
            backDeckUid = uid;
            return uid;
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            return -1;
        }
    }

    private int getXiaomiRearUid() {
        int uid = xiaomiRearUid;
        if (uid >= 0) return uid;
        Context context = systemContext;
        if (context == null) {
            context = findSystemContext(getClass().getClassLoader());
            systemContext = context;
        }
        if (context == null) return -1;
        try {
            uid = context.getPackageManager().getPackageUid(XIAOMI_REAR_PACKAGE, 0);
            xiaomiRearUid = uid;
            return uid;
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            return -1;
        }
    }

    /**
     * Make a configured app use the entire landscape-shaped embedded display, rather than
     * letterboxing a portrait-only activity in its centre. Never change its front-screen or
     * physical-rear-screen orientation policy.
     */
    private void installVirtualOrientationHook(ClassLoader loader) {
        final Class<?> activityRecord;
        try {
            activityRecord = loader.loadClass("com.android.server.wm.ActivityRecord");
        } catch (ClassNotFoundException e) {
            log(Log.WARN, TAG, "ActivityRecord unavailable for virtual orientation", e);
            return;
        }
        for (Method method : activityRecord.getDeclaredMethods()) {
            if (!"getOverrideOrientation".equals(method.getName())
                    || method.getParameterCount() != 0
                    || method.getReturnType() != int.class) {
                continue;
            }
            try {
                hook(method).intercept(chain -> {
                    Object original = chain.proceed();
                    try {
                        Object activity = chain.getThisObject();
                        String packageName = targetPackage(activity);
                        if (packageName != null
                                && !isSystemOrHostPackage(packageName)
                                && isAllowedPackage(packageName)
                                && isBackDeckVirtualDisplay(activity)) {
                            Object displayContent = invoke(activity, "getDisplayContent");
                            Object displayInfo = invoke(displayContent, "getDisplayInfo");
                            Object width = displayInfo == null ? null : readField(displayInfo, "logicalWidth");
                            Object height = displayInfo == null ? null : readField(displayInfo, "logicalHeight");
                            if (width instanceof Integer && height instanceof Integer) {
                                return ((Integer) height) > ((Integer) width)
                                        ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                        : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
                            }
                        }
                    } catch (Throwable error) {
                        // Orientation queries happen inside system_server's layout path.
                        log(Log.ERROR, TAG, "Virtual orientation check failed", error);
                    }
                    return original;
                });
                log(Log.INFO, TAG, "Installed owned virtual-display orientation hook");
                return;
            } catch (Throwable error) {
                log(Log.ERROR, TAG, "Could not hook virtual-display orientation", error);
            }
        }
        log(Log.WARN, TAG, "ActivityRecord.getOverrideOrientation unavailable", null);
    }

    private static boolean isBackDeckVirtualDisplay(Object activity)
            throws ReflectiveOperationException {
        Object displayContent = invoke(activity, "getDisplayContent");
        Object displayInfo = invoke(displayContent, "getDisplayInfo");
        if (displayInfo == null) return false;
        Object type = readField(displayInfo, "type");
        Object owner = readField(displayInfo, "ownerPackageName");
        Object name = readField(displayInfo, "name");
        Object uniqueId = readField(displayInfo, "uniqueId");
        return type instanceof Integer && ((Integer) type) == DISPLAY_TYPE_VIRTUAL
                && APP_PACKAGE.equals(owner)
                && EMBEDDED_DISPLAY_NAME.equals(name)
                && uniqueId instanceof String
                && ((String) uniqueId).startsWith("virtual:" + APP_PACKAGE + ",");
    }

    /**
     * Keep the task fullscreen and constrain only its activity configuration. A freeform task
     * with an x-offset is physically drawn at x=0 by this ROM's rear-display compositor.
     */
    private void installRearBoundsHook(ClassLoader loader) {
        final Class<?> implementation;
        try {
            implementation = loader.loadClass("com.android.server.wm.ActivityRecordImpl");
        } catch (ClassNotFoundException e) {
            log(Log.WARN, TAG, "Xiaomi ActivityRecordImpl unavailable", e);
            return;
        }
        for (Method method : implementation.getDeclaredMethods()) {
            if (!"resolveOverrideConfiguration".equals(method.getName())
                    || method.getParameterCount() != 2
                    || method.getParameterTypes()[0] != Configuration.class
                    || method.getParameterTypes()[1] != Configuration.class) {
                continue;
            }
            try {
                hook(method).intercept(chain -> {
                    Object original = chain.proceed();
                    try {
                        applyRearBounds(chain.getThisObject(),
                                (Configuration) chain.getArg(0),
                                (Configuration) chain.getArg(1));
                    } catch (Throwable error) {
                        // Never let a compatibility rule take down system_server.
                        log(Log.ERROR, TAG, "Rear bounds configuration failed", error);
                    }
                    return original;
                });
                log(Log.INFO, TAG, "Installed rear activity bounds compatibility hook");
                return;
            } catch (Throwable error) {
                log(Log.ERROR, TAG, "Could not hook rear activity bounds", error);
            }
        }
    }

    private void applyRearBounds(Object implementation, Configuration parent,
            Configuration resolved) throws ReflectiveOperationException {
        if (implementation == null || parent == null || resolved == null) return;
        Object activity = readField(implementation, "mAr");
        String packageName = targetPackage(activity);
        if (packageName == null || isSystemOrHostPackage(packageName)
                || !isAllowedPackage(packageName)) return;

        Integer displayId = invokeInt(activity, "getDisplayId");
        Object multiWindowValue = invoke(activity, "inMultiWindowMode");
        if (displayId == null || displayId != REAR_DISPLAY_ID
                || !Boolean.FALSE.equals(multiWindowValue)) return;

        Object displayContent = invoke(activity, "getDisplayContent");
        if (displayContent == null) return;
        Object displayInfo = invoke(displayContent, "getDisplayInfo");
        Object cutoutValue = displayInfo == null ? null : readField(displayInfo, "displayCutout");
        if (!(cutoutValue instanceof DisplayCutout)) return;
        DisplayCutout cutout = (DisplayCutout) cutoutValue;

        Object parentWindow = readField(parent, "windowConfiguration");
        Object resolvedWindow = readField(resolved, "windowConfiguration");
        if (parentWindow == null || resolvedWindow == null) return;
        // The activity's own mode may still be undefined while its configuration is resolving.
        // The parent mode is authoritative for this fullscreen-only compatibility rule.
        Integer parentMode = invokeInt(parentWindow, "getWindowingMode");
        if (parentMode == null || parentMode != 1) return;
        Object parentBoundsValue = invoke(parentWindow, "getBounds");
        if (!(parentBoundsValue instanceof Rect)) return;
        Rect full = (Rect) parentBoundsValue;
        // Only reshape a full-display container. Never double-inset an OEM-created child area.
        if (full.left != 0 || full.top != 0 || full.isEmpty()) return;
        Rect safe = new Rect(full.left + cutout.getSafeInsetLeft(),
                full.top + cutout.getSafeInsetTop(),
                full.right - cutout.getSafeInsetRight(),
                full.bottom - cutout.getSafeInsetBottom());
        if (safe.isEmpty() || safe.equals(full)) return;

        Method setBounds = findMethod(resolvedWindow.getClass(), "setBounds", Rect.class);
        Method setAppBounds = findMethod(resolvedWindow.getClass(), "setAppBounds", Rect.class);
        Method setMaxBounds = findMethod(resolvedWindow.getClass(), "setMaxBounds", Rect.class);
        setBounds.invoke(resolvedWindow, safe);
        setAppBounds.invoke(resolvedWindow, safe);
        setMaxBounds.invoke(resolvedWindow, safe);

        int dpi = parent.densityDpi > 0 ? parent.densityDpi : resolved.densityDpi;
        if (dpi <= 0) return;
        resolved.densityDpi = dpi;
        int widthDp = Math.round(safe.width() * 160f / dpi);
        int heightDp = Math.round(safe.height() * 160f / dpi);
        resolved.screenWidthDp = widthDp;
        resolved.screenHeightDp = heightDp;
        resolved.smallestScreenWidthDp = Math.min(widthDp, heightDp);
        resolved.orientation = widthDp >= heightDp
                ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;
        setOptionalInt(resolved, "compatScreenWidthDp", widthDp);
        setOptionalInt(resolved, "compatScreenHeightDp", heightDp);
        setOptionalInt(resolved, "compatSmallestScreenWidthDp", Math.min(widthDp, heightDp));
    }

    private static boolean isSystemOrHostPackage(String packageName) {
        return APP_PACKAGE.equals(packageName)
                || packageName.equals("android")
                || packageName.startsWith("com.android.")
                || packageName.startsWith("com.miui.")
                || packageName.startsWith("com.xiaomi.");
    }

    private static Object readField(Object receiver, String name)
            throws ReflectiveOperationException {
        if (receiver == null) return null;
        for (Class<?> type = receiver.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(receiver);
            } catch (NoSuchFieldException ignored) {
                // Window-management members may be inherited on different ROM builds.
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod(name, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                // Try the superclass.
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static Object invoke(Object receiver, String name)
            throws ReflectiveOperationException {
        return receiver == null ? null : findMethod(receiver.getClass(), name).invoke(receiver);
    }

    private static Integer invokeInt(Object receiver, String name)
            throws ReflectiveOperationException {
        Object value = invoke(receiver, name);
        return value instanceof Integer ? (Integer) value : null;
    }

    private static void setOptionalInt(Object receiver, String name, int value) {
        try {
            for (Class<?> type = receiver.getClass(); type != null; type = type.getSuperclass()) {
                try {
                    Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    field.setInt(receiver, value);
                    return;
                } catch (NoSuchFieldException ignored) {
                    // This compatibility field is absent on some platform releases.
                }
            }
        } catch (ReflectiveOperationException | SecurityException ignored) {
            // The public Configuration fields above are sufficient on this ROM.
        }
    }

    private void installXiaomiHooks(ClassLoader loader) {
        final Class<?> starter;
        try {
            starter = loader.loadClass("com.android.server.wm.ActivityStarterImpl");
        } catch (ClassNotFoundException e) {
            log(Log.WARN, TAG, "Xiaomi ActivityStarterImpl unavailable", e);
            return;
        }

        for (Method method : starter.getDeclaredMethods()) {
            String name = method.getName();
            if (!"isShouldShowOnRearDisplay".equals(name)
                    && !"isAllowedToStartOnRearDisplay".equals(name)) {
                continue;
            }
            if (method.getReturnType() != boolean.class || method.getParameterCount() < 1) {
                continue;
            }
            // Known ROM variants accept ActivityInfo or ActivityRecord as their first argument.
            Class<?> first = method.getParameterTypes()[0];
            if (first != ActivityInfo.class
                    && !"com.android.server.wm.ActivityRecord".equals(first.getName())) {
                continue;
            }
            try {
                hook(method).intercept(chain -> {
                    Object original = chain.proceed();
                    if (!Boolean.FALSE.equals(original)) return original;
                    String target = targetPackage(chain.getArg(0));
                    // Keep the OEM's other checks and behavior unchanged for every other app.
                    return isAllowedPackage(target) ? Boolean.TRUE : original;
                });
                log(Log.INFO, TAG, "Installed " + name);
            } catch (Throwable e) {
                log(Log.ERROR, TAG, "Could not hook " + name, e);
            }
        }
    }

    private void installFrameworkHook(ClassLoader loader) {
        final Class<?> supervisor;
        try {
            supervisor = loader.loadClass("com.android.server.wm.ActivityTaskSupervisor");
        } catch (ClassNotFoundException e) {
            log(Log.WARN, TAG, "ActivityTaskSupervisor unavailable", e);
            return;
        }

        for (Method method : supervisor.getDeclaredMethods()) {
            if (!"isCallerAllowedToLaunchOnDisplay".equals(method.getName())
                    || method.getReturnType() != boolean.class
                    || method.getParameterCount() != 4
                    || method.getParameterTypes()[2] != int.class
                    || method.getParameterTypes()[3] != ActivityInfo.class) {
                continue;
            }
            try {
                hook(method).intercept(chain -> {
                    Object original = chain.proceed();
                    if (!Boolean.FALSE.equals(original)) return original;
                    Object displayArg = chain.getArg(2);
                    Object infoArg = chain.getArg(3);
                    if (!(displayArg instanceof Integer)
                            || !(infoArg instanceof ActivityInfo)) {
                        return original;
                    }
                    int displayId = (Integer) displayArg;
                    ActivityInfo info = (ActivityInfo) infoArg;
                    if (displayId == REAR_DISPLAY_ID) {
                        // Preserve the existing, root-published physical rear-screen policy.
                        return isAllowedPackage(info.packageName) ? Boolean.TRUE : original;
                    }
                    if (displayId < 2 || !(chain.getArg(1) instanceof Integer)) return original;
                    try {
                        if (allowSameUidOnOwnedVirtualDisplay(chain.getThisObject(), displayId,
                                (Integer) chain.getArg(1), info)) {
                            long now = SystemClock.elapsedRealtime();
                            if (now - lastVirtualAllowLogElapsed > 30_000L) {
                                lastVirtualAllowLogElapsed = now;
                                log(Log.INFO, TAG, "Allowed same-UID navigation on owned virtual "
                                        + "display=" + displayId + " package=" + info.packageName);
                            }
                            return Boolean.TRUE;
                        }
                    } catch (Throwable error) {
                        // Framework internals differ across ROMs. Never broaden access on error.
                        long now = SystemClock.elapsedRealtime();
                        if (now - lastVirtualErrorLogElapsed > 30_000L) {
                            lastVirtualErrorLogElapsed = now;
                            log(Log.WARN, TAG, "Virtual-display launch check failed closed", error);
                        }
                    }
                    return original;
                });
                log(Log.INFO, TAG, "Installed physical rear and owned virtual-display launch policy");
            } catch (Throwable e) {
                log(Log.ERROR, TAG, "Could not hook display launch policy", e);
            }
        }
    }

    /**
     * Android 16 routes an IME for a secondary display through
     * {@code ImeVisibilityStateComputer}'s display-policy callback.  The normal policy sends
     * an app-owned virtual display's IME to display 0, so the keyboard is invisible in the
     * TextureView host.  Keep the exception deliberately narrow: the policy becomes local
     * only for the physical rear display while BackDeck/a selected app is focused, or for an
     * exact BackDeck-owned embedded display with a selected app focused.
     *
     * <p>This does not make the virtual display {@code SECURE}, {@code TRUSTED}, or capable
     * of rendering protected content.  It changes only the placement of the system IME.</p>
     */
    private void installImeDisplayPolicyHook(ClassLoader loader) {
        final Class<?> callback;
        try {
            // Confirmed on this HyperOS Android-16 build. Hooking the validator callback is
            // safer than globally changing WindowManager's policy for every secondary display.
            callback = loader.loadClass(
                    "com.android.server.inputmethod.ImeVisibilityStateComputer$$ExternalSyntheticLambda0");
        } catch (ClassNotFoundException error) {
            log(Log.WARN, TAG, "IME display-policy callback unavailable", error);
            return;
        }
        for (Method method : callback.getDeclaredMethods()) {
            if (!"getDisplayImePolicy".equals(method.getName())
                    || method.getReturnType() != int.class
                    || method.getParameterCount() != 1
                    || method.getParameterTypes()[0] != int.class) {
                continue;
            }
            try {
                hook(method).intercept(chain -> {
                    Object original = chain.proceed();
                    Object value = chain.getArg(0);
                    if (!(value instanceof Integer)) return original;
                    try {
                        int displayId = (Integer) value;
                        if (!shouldUseLocalIme(displayId)) return original;
                        long now = SystemClock.elapsedRealtime();
                        if (now - lastImePolicyLogElapsed > 30_000L) {
                            lastImePolicyLogElapsed = now;
                            log(Log.INFO, TAG, "Kept IME on BackDeck display=" + displayId);
                        }
                        return Integer.valueOf(DISPLAY_IME_POLICY_LOCAL);
                    } catch (Throwable error) {
                        // IME is a global system path. Any uncertainty must preserve the
                        // platform's original routing rather than affect another display.
                        return original;
                    }
                });
                log(Log.INFO, TAG, "Installed scoped BackDeck IME display policy");
                return;
            } catch (Throwable error) {
                log(Log.ERROR, TAG, "Could not hook IME display policy", error);
            }
        }
        log(Log.WARN, TAG, "IME display-policy method unavailable", null);
    }

    private boolean shouldUseLocalIme(int displayId) throws ReflectiveOperationException {
        if (displayId < 0) return false;
        Object displayContent = displayContentForImePolicy(displayId);
        if (displayContent == null) return false;
        Object focused = readField(displayContent, "mFocusedApp");
        String packageName = targetPackage(focused);
        if (displayId == REAR_DISPLAY_ID) {
            // Do not affect Xiaomi's launcher, its lockscreen, or an arbitrary rear app.
            return APP_PACKAGE.equals(packageName)
                    || (packageName != null && !isSystemOrHostPackage(packageName)
                    && isAllowedPackage(packageName));
        }
        Object displayInfo = invoke(displayContent, "getDisplayInfo");
        return packageName != null && !isSystemOrHostPackage(packageName)
                && isAllowedPackage(packageName)
                && isOwnedVirtualDisplayInfo(displayInfo, getBackDeckUid());
    }

    /**
     * WMS has an earlier policy gate than InputMethodManagerService.  On this HyperOS build
     * {@code DisplayContent#getImePolicy()} hard-returns FALLBACK for an untrusted virtual
     * display before it consults any configured policy.  Returning LOCAL here is deliberately
     * narrower than changing {@code isTrusted()}, a display flag, or a setting: it affects only
     * the one policy query, only while an explicit BackDeck drawer target is focused.
     *
     * <p>Do not override HIDE.  A platform component that intentionally suppresses IME retains
     * that decision even if its display happens to satisfy the ownership predicate.</p>
     */
    private void installBackDeckWmsImePolicyHook(ClassLoader loader) {
        final Class<?> displayContent;
        try {
            displayContent = loader.loadClass("com.android.server.wm.DisplayContent");
        } catch (ClassNotFoundException error) {
            log(Log.WARN, TAG, "DisplayContent unavailable for BackDeck WMS IME policy", error);
            return;
        }
        for (Method method : displayContent.getDeclaredMethods()) {
            if (!"getImePolicy".equals(method.getName())
                    || method.getReturnType() != int.class || method.getParameterCount() != 0) {
                continue;
            }
            try {
                hook(method).intercept(chain -> {
                    Object original = chain.proceed();
                    // Keep LOCAL, HIDE, unknown/new policy values, and all non-BackDeck
                    // displays exactly as WMS decided.  The only permitted substitution is the
                    // platform's ordinary fallback-display route for our live embedded VD.
                    if (!Integer.valueOf(DISPLAY_IME_POLICY_FALLBACK_DISPLAY).equals(original)) {
                        return original;
                    }
                    try {
                        if (!isFocusedAllowedBackDeckVirtualDisplay(chain.getThisObject())) {
                            return original;
                        }
                        long now = SystemClock.elapsedRealtime();
                        if (now - lastWmsImePolicyLogElapsed > 30_000L) {
                            lastWmsImePolicyLogElapsed = now;
                            log(Log.INFO, TAG, "Kept WMS IME local on verified BackDeck VD");
                        }
                        return Integer.valueOf(DISPLAY_IME_POLICY_LOCAL);
                    } catch (Throwable ignored) {
                        // This runs in WMS policy evaluation.  Any changed private field or
                        // unexpected display state must preserve the untouched framework value.
                        return original;
                    }
                });
                log(Log.INFO, TAG, "Installed scoped BackDeck WMS IME policy");
                return;
            } catch (Throwable error) {
                log(Log.ERROR, TAG, "Could not hook BackDeck WMS IME policy", error);
            }
        }
        log(Log.WARN, TAG, "DisplayContent.getImePolicy unavailable", null);
    }

    /**
     * Lock-free/read-only WMS predicate.  It intentionally uses already-published UID and
     * allowlist snapshots: calling PackageManager or Settings while WMS holds its global lock
     * would be an unnecessary cross-service dependency.  A cold cache fails closed; the normal
     * launch policy path refreshes it before the selected activity reaches focused state.
     */
    private boolean isFocusedAllowedBackDeckVirtualDisplay(Object displayContent)
            throws ReflectiveOperationException {
        if (displayContent == null || backDeckUid < 0) return false;
        Object displayInfo = invoke(displayContent, "getDisplayInfo");
        if (!isOwnedVirtualDisplayInfo(displayInfo, backDeckUid)) return false;
        Object focused = readField(displayContent, "mFocusedApp");
        String packageName = targetPackage(focused);
        return packageName != null && !isSystemOrHostPackage(packageName)
                && allowedPackages.contains(packageName);
    }

    /**
     * HyperOS has a second, independent IME gate in WindowManager.  Its back-screen extension
     * can reject the otherwise valid IME layering target after InputMethodManagerService has
     * already bound the IME to our virtual display.  The result is a correctly bound IME whose
     * window remains GONE.  Do not broadly turn off that OEM gate: retain the original result
     * for every normal display/window and make a narrowly verified embedded app window eligible
     * only as an IME layering target.
     *
     * <p>The predicate intentionally requires all of the following: a non-host allowlisted app,
     * the currently focused activity on an exact BackDeck-owned virtual display, and the
     * display's current IME input target being that same window.  It therefore cannot affect an
     * arbitrary secondary display, the physical rear panel, the OEM launcher, or an app that has
     * not been explicitly selected by the user.</p>
     */
    private void installBackDeckImeLayeringHook(ClassLoader loader) {
        final Class<?> windowState;
        try {
            windowState = loader.loadClass("com.android.server.wm.WindowState");
        } catch (ClassNotFoundException error) {
            log(Log.WARN, TAG, "WindowState unavailable for BackDeck IME layering", error);
            return;
        }
        for (Method method : windowState.getDeclaredMethods()) {
            if (!"canBeImeLayeringTarget".equals(method.getName())
                    || method.getParameterCount() != 0
                    || method.getReturnType() != boolean.class) {
                continue;
            }
            try {
                hook(method).intercept(chain -> {
                    Object original = chain.proceed();
                    if (!Boolean.FALSE.equals(original)) return original;
                    try {
                        if (!isBackDeckOwnedVirtualImeTarget(chain.getThisObject())) {
                            return original;
                        }
                        long now = SystemClock.elapsedRealtime();
                        if (now - lastImeLayeringLogElapsed > 30_000L) {
                            lastImeLayeringLogElapsed = now;
                            log(Log.INFO, TAG,
                                    "Allowed IME layering for verified BackDeck virtual target");
                        }
                        return Boolean.TRUE;
                    } catch (Throwable ignored) {
                        // This lies in the WindowManager traversal path.  Any ROM/layout change
                        // must fall back to the untouched OEM decision.
                        return original;
                    }
                });
                log(Log.INFO, TAG, "Installed scoped BackDeck IME layering policy");
                return;
            } catch (Throwable error) {
                log(Log.ERROR, TAG, "Could not hook BackDeck IME layering policy", error);
            }
        }
        log(Log.WARN, TAG, "WindowState.canBeImeLayeringTarget unavailable", null);
    }

    /** Exact WindowManager predicate used by the IME-layering exception above. */
    private boolean isBackDeckOwnedVirtualImeTarget(Object window)
            throws ReflectiveOperationException {
        if (window == null || !"com.android.server.wm.WindowState"
                .equals(window.getClass().getName())) {
            return false;
        }
        // Never make the IME's own window, an overlay, or a host window its target.
        if (Boolean.TRUE.equals(readField(window, "mIsImWindow"))) return false;

        Object activity = readField(window, "mActivityRecord");
        Object displayContent = invoke(window, "getDisplayContent");
        if (displayContent == null) return false;
        String packageName = targetPackage(activity);
        if (packageName == null || isSystemOrHostPackage(packageName)
                || !isAllowedPackage(packageName)) {
            return false;
        }
        Object displayInfo = invoke(displayContent, "getDisplayInfo");
        if (!isOwnedVirtualDisplayInfo(displayInfo, getBackDeckUid())) return false;

        // Bind this to the foreground activity, rather than just any window owned by the app.
        if (readField(displayContent, "mFocusedApp") != activity) return false;

        // On a virtual display mCurrentFocus may legitimately be null.  The IME input target is
        // the authoritative focus signal there; obtain the underlying WindowState if the ROM
        // stores it through the InputTarget interface rather than directly.
        Object imeTarget = readField(displayContent, "mImeInputTarget");
        if (imeTarget == window) return true;
        Object targetWindow = invoke(imeTarget, "getWindowState");
        return targetWindow == window;
    }

    /** Resolve a DisplayContent through LocalServices without keeping a WMS instance globally. */
    @SuppressLint("BlockedPrivateApi") // This method executes only inside LSPosed system_server scope.
    private Object displayContentForImePolicy(int displayId) throws ReflectiveOperationException {
        ClassLoader loader = systemServerClassLoader;
        if (loader == null) return null;
        Class<?> localServices = loader.loadClass("com.android.server.LocalServices");
        Class<?> windowManagerInternal = loader.loadClass("com.android.server.wm.WindowManagerInternal");
        Method getService = localServices.getDeclaredMethod("getService", Class.class);
        getService.setAccessible(true);
        Object localService = getService.invoke(null, windowManagerInternal);
        if (localService == null) return null;

        Object owner = localService;
        for (int depth = 0; depth < 3 && owner != null; depth++) {
            Object root = readField(owner, "mRoot");
            if (root == null) root = readField(owner, "mRootWindowContainer");
            if (root != null) {
                try {
                    return findMethod(root.getClass(), "getDisplayContent", int.class)
                            .invoke(root, displayId);
                } catch (NoSuchMethodException ignored) {
                    // Try the next enclosing WMS holder below.
                }
            }
            Object next = readField(owner, "mService");
            if (next == null) next = readField(owner, "this$0");
            if (next == owner) break;
            owner = next;
        }
        return null;
    }

    /** Only relax the untrusted-display embedding rule for an app already running on our VD. */
    private boolean allowSameUidOnOwnedVirtualDisplay(Object supervisor, int displayId,
            int callingUid, ActivityInfo info) throws ReflectiveOperationException {
        if (callingUid < 0 || info.applicationInfo == null
                || callingUid != info.applicationInfo.uid
                || !isAllowedPackage(info.packageName)) return false;

        Context context = systemContext;
        if (context == null) {
            context = findSystemContext(getClass().getClassLoader());
            systemContext = context;
        }
        if (context == null) return false;
        int ownerUid = backDeckUid;
        if (ownerUid < 0) {
            try {
                ownerUid = context.getPackageManager().getPackageUid(APP_PACKAGE, 0);
                backDeckUid = ownerUid;
            } catch (PackageManager.NameNotFoundException e) {
                return false;
            }
        }

        Object root = readField(supervisor, "mRootWindowContainer");
        if (root == null) return false;
        // Do not create a DisplayContent while evaluating a denied launch.
        Object displayContent = findMethod(root.getClass(), "getDisplayContent", int.class)
                .invoke(root, displayId);
        if (displayContent == null || Boolean.TRUE.equals(invoke(displayContent, "isRemoved")))
            return false;
        Object displayInfo = invoke(displayContent, "getDisplayInfo");
        if (!isOwnedVirtualDisplayInfo(displayInfo, ownerUid)) return false;

        // UID presence is required in addition to a same-UID target: an unrelated app cannot
        // launch a fresh task onto a surface it does not already own or occupy.
        return Boolean.TRUE.equals(findMethod(displayContent.getClass(), "isUidPresent", int.class)
                .invoke(displayContent, callingUid));
    }

    private static boolean isOwnedVirtualDisplayInfo(Object displayInfo, int ownerUid)
            throws ReflectiveOperationException {
        if (displayInfo == null || ownerUid < 0) return false;
        Object type = readField(displayInfo, "type");
        Object owner = readField(displayInfo, "ownerPackageName");
        Object uid = readField(displayInfo, "ownerUid");
        Object name = readField(displayInfo, "name");
        Object uniqueId = readField(displayInfo, "uniqueId");
        return type instanceof Integer && ((Integer) type) == DISPLAY_TYPE_VIRTUAL
                && APP_PACKAGE.equals(owner)
                && uid instanceof Integer && ((Integer) uid) == ownerUid
                && EMBEDDED_DISPLAY_NAME.equals(name)
                && uniqueId instanceof String
                && ((String) uniqueId).startsWith("virtual:" + APP_PACKAGE + "," + ownerUid
                        + "," + EMBEDDED_DISPLAY_NAME + ",");
    }

    private static String targetPackage(Object value) {
        if (value instanceof ActivityInfo) {
            return ((ActivityInfo) value).packageName;
        }
        if (value == null
                || !"com.android.server.wm.ActivityRecord".equals(value.getClass().getName())) {
            return null;
        }
        try {
            Field field = value.getClass().getDeclaredField("packageName");
            field.setAccessible(true);
            Object packageName = field.get(value);
            return packageName instanceof String ? (String) packageName : null;
        } catch (ReflectiveOperationException | SecurityException ignored) {
            return null;
        }
    }

    private boolean isAllowedPackage(String packageName) {
        if (APP_PACKAGE.equals(packageName)) return true;
        if (packageName == null) return false;
        long now = SystemClock.elapsedRealtime();
        if (now - lastRefreshElapsed >= REFRESH_INTERVAL_MS) refreshAllowlist(now);
        return allowedPackages.contains(packageName);
    }

    private synchronized void refreshAllowlist(long now) {
        if (now - lastRefreshElapsed < REFRESH_INTERVAL_MS) return;
        lastRefreshElapsed = now;
        Context context = systemContext;
        if (context == null) {
            context = findSystemContext(getClass().getClassLoader());
            systemContext = context;
        }
        if (context == null) return;
        try {
            String raw = Settings.Global.getString(context.getContentResolver(), ALLOWLIST_SETTING);
            if (raw == null || raw.length() > 65_536) {
                allowedPackages = Collections.emptySet();
                return;
            }
            Set<String> parsed = new HashSet<>();
            for (String value : raw.split(",")) {
                if (parsed.size() >= 512) break;
                if (validPackage(value)) parsed.add(value);
            }
            allowedPackages = Collections.unmodifiableSet(parsed);
        } catch (RuntimeException e) {
            // Retain the previous successful snapshot. If none exists, this fails closed.
            log(Log.WARN, TAG, "Could not read root-published allowlist", e);
        }
    }

    private static boolean validPackage(String value) {
        return value != null && value.length() <= 255
                && value.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+");
    }

    private static Context findSystemContext(ClassLoader loader) {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread", false, loader);
            Method current = activityThread.getDeclaredMethod("currentActivityThread");
            current.setAccessible(true);
            Object thread = current.invoke(null);
            if (thread == null) return null;
            Method getContext = activityThread.getDeclaredMethod("getSystemContext");
            getContext.setAccessible(true);
            Object context = getContext.invoke(thread);
            return context instanceof Context ? (Context) context : null;
        } catch (ReflectiveOperationException | SecurityException ignored) {
            return null;
        }
    }
}

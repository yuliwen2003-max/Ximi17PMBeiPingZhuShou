package com.seagull.backdeck;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.text.InputType;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Front-screen settings. The rear display is owned by RearActivity. */
public class MainActivity extends Activity {
    private static final int BG = Color.rgb(8, 11, 17);
    private static final int PANEL = Color.rgb(20, 25, 34);
    private static final int PANEL_2 = Color.rgb(29, 36, 47);
    private static final int WHITE = Color.rgb(245, 248, 250);
    private static final int MUTED = Color.rgb(153, 164, 177);
    private static final int ACCENT = Color.rgb(123, 237, 210);
    private static final int LINE = Color.rgb(49, 59, 72);

    private static final String[] KEYS = {"long_press", "double_tap", "swipe_left", "swipe_right"};
    private static final String[] GESTURES = {"长按", "双击", "向左滑动", "向右滑动"};
    private static final String[] SYMBOLS = {"◎", "◇", "←", "→"};

    private LinearLayout gestureList;
    private LinearLayout drawerItemsContainer;
    private List<ActionConfig> drawerItems;
    private DrawerAdapter drawerAdapter;
    private TextView displayStatus;
    private TextView drawerSummary;
    private TextView signalPermission;
    private TextView launchButton;
    private Switch takeoverSwitch;
    private final ScrollView[] settingsPages = new ScrollView[3];
    private final TextView[] bottomTabs = new TextView[3];
    private int selectedPage;
    private static final int REQUEST_SIGNAL_PERMISSION = 417;

    private interface ActionPicked {
        void onPicked(ActionConfig action);
    }

    @Override public void onCreate(Bundle state) {
        setTheme(android.R.style.Theme_Material_NoActionBar);
        super.onCreate(state);
        Window window = getWindow();
        window.setDecorFitsSystemWindows(false);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setStatusBarContrastEnforced(false);
        window.setNavigationBarColor(PANEL);
        window.setNavigationBarContrastEnforced(false);
        selectedPage = state == null ? 0 : Math.max(0, Math.min(2, state.getInt("front_tab", 0)));
        boolean migratedSafeMode = ActionStore.migrateLegacyDirectAppsToSafe(this);
        buildScreen();
        if (migratedSafeMode) {
            Toast.makeText(this, "背屏旧状态栏高度兼容配置已更新", Toast.LENGTH_LONG).show();
        }
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_PHONE_STATE}, REQUEST_SIGNAL_PERMISSION);
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("front_tab", selectedPage);
        super.onSaveInstanceState(state);
    }

    @Override protected void onResume() {
        super.onResume();
        RearTakeoverService.ensureRunning(this);
        RearAllowlistPublisher.schedule(this);
        refreshStatus();
        refreshGestures();
        refreshDrawerSummary();
        refreshSignalPermission();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_SIGNAL_PERMISSION) refreshSignalPermission();
    }

    private void buildScreen() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(BG);
        LinearLayout shell = column();
        FrameLayout content = new FrameLayout(this);
        LinearLayout[] pages = new LinearLayout[3];
        for (int i = 0; i < settingsPages.length; i++) {
            ScrollView scroll = new ScrollView(this);
            scroll.setFillViewport(true);
            scroll.setClipToPadding(false);
            scroll.setVerticalScrollBarEnabled(false);
            LinearLayout section = column();
            section.setPadding(dp(20), dp(27), dp(20), dp(28));
            scroll.addView(section, new ScrollView.LayoutParams(-1, -2));
            content.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
            settingsPages[i] = scroll;
            pages[i] = section;
        }
        shell.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout tabHost = column();
        tabHost.setBackgroundColor(PANEL);
        View divider = new View(this);
        divider.setBackgroundColor(LINE);
        tabHost.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        LinearLayout tabRow = row();
        tabRow.setPadding(dp(12), dp(8), dp(12), dp(8));
        String[] tabTitles = {"⌂\n主页", "◇\n手势捷径", "▦\n应用抽屉"};
        for (int i = 0; i < tabTitles.length; i++) {
            final int pageIndex = i;
            TextView tab = text(tabTitles[i], 12, MUTED, Typeface.BOLD);
            tab.setGravity(Gravity.CENTER);
            tab.setContentDescription(tabTitles[i].replace('\n', ' '));
            tab.setOnClickListener(v -> showPage(pageIndex));
            tabRow.addView(tab, weighted(1, dp(54), i == 2 ? 0 : dp(5)));
            bottomTabs[i] = tab;
        }
        tabHost.addView(tabRow);
        shell.addView(tabHost, new LinearLayout.LayoutParams(-1, -2));
        frame.addView(shell, new FrameLayout.LayoutParams(-1, -1));
        setContentView(frame);
        frame.post(() -> {
            WindowInsetsController bars = getWindow().getDecorView().getWindowInsetsController();
            if (bars != null) bars.setSystemBarsAppearance(0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        });
        frame.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets safe = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            for (ScrollView scroll : settingsPages) scroll.setPadding(0, safe.top, 0, 0);
            tabHost.setPadding(0, 0, 0, safe.bottom);
            return insets;
        });
        LinearLayout page = pages[0];
        LinearLayout gesturesPage = pages[1];
        LinearLayout drawerPage = pages[2];

        LinearLayout brand = row();
        brand.setGravity(Gravity.CENTER_VERTICAL);
        TextView brandMark = text("B", 18, BG, Typeface.BOLD);
        brandMark.setGravity(Gravity.CENTER);
        brandMark.setBackground(round(ACCENT, 13));
        brand.addView(brandMark, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout brandText = column();
        brandText.setPadding(dp(11), 0, 0, 0);
        brandText.addView(text("BACKDECK", 13, WHITE, Typeface.BOLD));
        brandText.addView(text("小米 17 Pro · 背屏助手", 11, MUTED, Typeface.NORMAL));
        brand.addView(brandText, new LinearLayout.LayoutParams(0, -2, 1));
        TextView version = badge("BETA", ACCENT, PANEL_2);
        brand.addView(version);
        page.addView(brand);

        page.addView(space(31));
        page.addView(text("把背屏，变成\n你的快捷主场。", 30, WHITE, Typeface.BOLD));
        page.addView(space(10));
        TextView subtitle = text("时间、状态、扫一扫和常用应用，抬手就能完成。", 14, MUTED, Typeface.NORMAL);
        subtitle.setLineSpacing(dp(3), 1f);
        page.addView(subtitle);
        page.addView(space(24));

        page.addView(preview());
        page.addView(space(16));

        LinearLayout statusCard = card();
        LinearLayout statusRow = row();
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView dot = text("●", 13, ACCENT, Typeface.NORMAL);
        statusRow.addView(dot);
        displayStatus = text("正在检测背屏…", 13, WHITE, Typeface.BOLD);
        displayStatus.setPadding(dp(9), 0, 0, 0);
        statusRow.addView(displayStatus, new LinearLayout.LayoutParams(0, -2, 1));
        statusRow.addView(text("DISPLAY 01", 10, MUTED, Typeface.BOLD));
        statusCard.addView(statusRow);
        statusCard.addView(space(7));
        signalPermission = text("", 11, MUTED, Typeface.NORMAL);
        signalPermission.setOnClickListener(v -> {
            if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.READ_PHONE_STATE}, REQUEST_SIGNAL_PERMISSION);
            }
        });
        statusCard.addView(signalPermission);
        statusCard.addView(space(13));
        launchButton = actionButton("启动背屏助手   ↗", true);
        launchButton.setOnClickListener(v -> launchRearHost());
        statusCard.addView(launchButton, new LinearLayout.LayoutParams(-1, dp(48)));
        statusCard.addView(space(14));
        takeoverSwitch = new Switch(this);
        takeoverSwitch.setText("背屏亮起后自动恢复助手");
        takeoverSwitch.setTextColor(WHITE);
        takeoverSwitch.setTextSize(13);
        takeoverSwitch.setChecked(RearTakeoverService.isEnabled(this));
        takeoverSwitch.setOnCheckedChangeListener((button, checked) ->
                RearTakeoverService.setEnabled(this, checked));
        statusCard.addView(takeoverSwitch);
        TextView takeoverHint = text("保留原厂唤醒和息屏服务；直开应用的底边返回需 ROOT 授予本助手悬浮窗权限。",
                11, MUTED, Typeface.NORMAL);
        statusCard.addView(takeoverHint);
        statusCard.addView(space(11));
        TextView originalHome = miniButton("暂停接管，返回小米原生背屏");
        originalHome.setOnClickListener(v -> {
            if (takeoverSwitch != null) takeoverSwitch.setChecked(false);
            new Thread(() -> {
                RootBridge.Result result = RootBridge.showOemRearHome(this);
                runOnUiThread(() -> Toast.makeText(this,
                        result.ok ? "已返回小米原生背屏" : "切换失败：" + result.output,
                        Toast.LENGTH_SHORT).show());
            }, "backdeck-original-home").start();
        });
        statusCard.addView(originalHome, new LinearLayout.LayoutParams(-1, dp(38)));
        page.addView(statusCard);

        gesturesPage.addView(text("手势捷径", 28, WHITE, Typeface.BOLD));
        gesturesPage.addView(space(7));
        gesturesPage.addView(text("在背屏时间页触发；每个动作都能单独选择应用和打开屏幕。",
                13, MUTED, Typeface.NORMAL));
        gesturesPage.addView(space(23));
        gestureList = column();
        gesturesPage.addView(gestureList);

        drawerPage.addView(text("应用抽屉", 28, WHITE, Typeface.BOLD));
        drawerPage.addView(space(7));
        drawerPage.addView(text("只显示你手动加入的应用或 Activity。点应用可修改目标屏幕、DPI 和顶部补偿。",
                13, MUTED, Typeface.NORMAL));
        drawerPage.addView(space(23));
        LinearLayout drawerCard = card();
        LinearLayout drawerTop = row();
        drawerTop.setGravity(Gravity.CENTER_VERTICAL);
        TextView drawerIcon = text("▦", 28, ACCENT, Typeface.NORMAL);
        drawerIcon.setGravity(Gravity.CENTER);
        drawerIcon.setBackground(round(PANEL_2, 14));
        drawerTop.addView(drawerIcon, new LinearLayout.LayoutParams(dp(50), dp(50)));
        LinearLayout drawerInfo = column();
        drawerInfo.setPadding(dp(13), 0, 0, 0);
        drawerInfo.addView(text("我的应用", 16, WHITE, Typeface.BOLD));
        drawerSummary = text("读取中…", 12, MUTED, Typeface.NORMAL);
        drawerInfo.addView(drawerSummary);
        drawerTop.addView(drawerInfo, new LinearLayout.LayoutParams(0, -2, 1));
        drawerCard.addView(drawerTop);
        drawerCard.addView(space(15));
        LinearLayout addChoices = row();
        TextView addApp = actionButton("＋ 添加应用", false);
        TextView addActivity = actionButton("＋ Activity", false);
        addChoices.addView(addApp, weighted(1, dp(46), dp(8)));
        addChoices.addView(addActivity, weighted(1, dp(46), 0));
        drawerCard.addView(addChoices);
        drawerPage.addView(drawerCard);
        drawerPage.addView(space(17));
        drawerItems = new ArrayList<>(ActionStore.getDrawer(this));
        drawerAdapter = new DrawerAdapter(drawerItems);
        drawerItemsContainer = column();
        drawerPage.addView(drawerItemsContainer);
        Runnable persist = () -> persistDrawerItems(drawerItems, drawerAdapter);
        addApp.setOnClickListener(v -> pickInstalledApp(action ->
                addDrawerItem(drawerItems, action, persist)));
        addActivity.setOnClickListener(v -> pickExportedActivity(action ->
                addDrawerItem(drawerItems, action, persist)));
        refreshDrawerItems();

        page.addView(space(28));
        TextView footer = text("所有配置保存在本机。ROOT 仅用于跨屏启动兜底，不会修改系统分区。", 11, MUTED, Typeface.NORMAL);
        footer.setLineSpacing(dp(3), 1f);
        page.addView(footer);
        showPage(selectedPage);
        refreshStatus();
        refreshGestures();
        refreshDrawerSummary();
        refreshSignalPermission();
    }

    private void showPage(int index) {
        selectedPage = Math.max(0, Math.min(settingsPages.length - 1, index));
        for (int i = 0; i < settingsPages.length; i++) {
            boolean active = i == selectedPage;
            settingsPages[i].setVisibility(active ? View.VISIBLE : View.GONE);
            bottomTabs[i].setTextColor(active ? ACCENT : MUTED);
            bottomTabs[i].setBackground(active ? round(PANEL_2, 12) : null);
            bottomTabs[i].setSelected(active);
        }
    }

    private View preview() {
        LinearLayout shell = column();
        shell.setBackground(round(Color.rgb(15, 20, 28), 25, LINE));
        shell.setPadding(dp(9), dp(9), dp(9), dp(9));
        TextView previewNote = text("背屏界面预览 · 状态数值仅供示意", 10, MUTED, Typeface.NORMAL);
        previewNote.setPadding(dp(8), dp(3), 0, dp(7));
        shell.addView(previewNote);
        LinearLayout screen = row();
        screen.setBackground(round(Color.BLACK, 20));
        screen.setMinimumHeight(dp(168));
        TextView camera = text("◉\n◉", 32, Color.rgb(52, 60, 69), Typeface.NORMAL);
        camera.setGravity(Gravity.CENTER);
        camera.setLineSpacing(dp(4), 1f);
        screen.addView(camera, new LinearLayout.LayoutParams(dp(73), -1));
        LinearLayout face = column();
        face.setGravity(Gravity.CENTER);
        face.setPadding(dp(6), dp(8), dp(13), dp(8));
        face.addView(text("●  5G   ▰▰   87%", 10, ACCENT, Typeface.BOLD));
        face.addView(space(4));
        String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new java.util.Date());
        face.addView(text(time, 37, WHITE, Typeface.BOLD));
        String date = new SimpleDateFormat("M月d日  EEEE", Locale.CHINA).format(new java.util.Date());
        face.addView(text(date, 11, MUTED, Typeface.NORMAL));
        screen.addView(face, new LinearLayout.LayoutParams(0, -1, 1));
        shell.addView(screen, new LinearLayout.LayoutParams(-1, dp(168)));
        return shell;
    }

    private void refreshGestures() {
        if (gestureList == null) return;
        gestureList.removeAllViews();
        for (int i = 0; i < KEYS.length; i++) {
            final int index = i;
            final ActionConfig action = ActionStore.get(this, KEYS[i]);
            LinearLayout item = card();
            LinearLayout head = row();
            head.setGravity(Gravity.CENTER_VERTICAL);
            TextView icon = text(SYMBOLS[i], 23, ACCENT, Typeface.BOLD);
            icon.setGravity(Gravity.CENTER);
            icon.setBackground(round(PANEL_2, 12));
            head.addView(icon, new LinearLayout.LayoutParams(dp(46), dp(46)));
            LinearLayout labels = column();
            labels.setPadding(dp(12), 0, dp(4), 0);
            labels.addView(text(GESTURES[i], 15, WHITE, Typeface.BOLD));
            String destination = displayLabel(action.displayId);
            if (action.displayId == ActionConfig.DISPLAY_REAR_SAFE) {
                destination += " · " + action.virtualDpi + " DPI";
                if (action.virtualTopCropPx > 0)
                    destination += " · 裁顶 " + action.virtualTopCropPx + "px";
            }
            if (action.displayId != ActionConfig.DISPLAY_FRONT)
                destination += action.confirmReturnHome ? " · 二次确认" : " · 单次返回";
            String description = action.enabled ? action.label + "  ·  " + destination : "未启用";
            TextView desc = text(description, 12, action.enabled ? MUTED : Color.rgb(106, 116, 130), Typeface.NORMAL);
            desc.setSingleLine(true);
            desc.setEllipsize(android.text.TextUtils.TruncateAt.END);
            labels.addView(desc);
            head.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            head.addView(text("›", 26, MUTED, Typeface.NORMAL));
            item.addView(head);
            item.setOnClickListener(v -> showGestureEditor(KEYS[index], GESTURES[index]));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(9);
            gestureList.addView(item, lp);
        }
    }

    private void refreshDrawerSummary() {
        if (drawerSummary == null) return;
        List<ActionConfig> items = ActionStore.getDrawer(this);
        int active = 0;
        for (ActionConfig item : items) if (item.enabled) active++;
        drawerSummary.setText(items.isEmpty() ? "尚未添加 · 点击下方按钮选择应用"
                : "已添加 " + items.size() + " 个 · 已启用 " + active + " 个");
    }

    private void refreshStatus() {
        if (displayStatus == null) return;
        DisplayManager manager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        int rearId = DisplayRouting.rearDisplayId(this);
        Display display = manager == null || rearId == Display.INVALID_DISPLAY
                ? null : manager.getDisplay(rearId);
        if (display == null) {
            displayStatus.setText("未检测到背屏");
        } else {
            Point size = new Point();
            display.getRealSize(size);
            displayStatus.setText("背屏已连接 · " + size.x + " × " + size.y);
        }
    }

    private void refreshSignalPermission() {
        if (signalPermission == null) return;
        boolean granted = checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED;
        signalPermission.setText(granted ? "蜂窝信号权限已开启" : "蜂窝信号权限未授权 · 点此开启（否则显示 --）");
        signalPermission.setTextColor(granted ? ACCENT : MUTED);
    }

    private void launchRearHost() {
        if (DisplayRouting.rearDisplayId(this) == Display.INVALID_DISPLAY) {
            Toast.makeText(this, "没有检测到背屏，请检查设备状态", Toast.LENGTH_LONG).show();
            return;
        }
        launchButton.setEnabled(false);
        launchButton.setText("正在请求背屏启动…");
        new Thread(() -> {
            RootBridge.Result overlayGrant = RootBridge.grantOwnOverlay(this);
            RootBridge.Result launch = RootBridge.startRearHostChecked(this);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                launchButton.setEnabled(true);
                if (launch.ok) {
                    if (takeoverSwitch != null && !takeoverSwitch.isChecked()) {
                        takeoverSwitch.setChecked(true);
                    } else {
                        RearTakeoverService.ensureRunning(this);
                    }
                    launchButton.setText("已在物理背屏启动   ✓");
                    displayStatus.setText("助手已在物理背屏运行");
                    Toast.makeText(this, "背屏助手已启动", Toast.LENGTH_SHORT).show();
                    if (!overlayGrant.ok) {
                        Toast.makeText(this, "直开应用全局底边手势尚未启用：悬浮窗授权失败",
                                Toast.LENGTH_LONG).show();
                    }
                } else {
                    launchButton.setText("启动背屏助手   ↗");
                    displayStatus.setText("背屏启动失败 · 点击重试");
                    String detail = launch.output == null ? "未知错误" : launch.output.trim();
                    if (detail.length() > 600) detail = detail.substring(0, 600) + "…";
                    AlertDialog blocked = new AlertDialog.Builder(this)
                            .setTitle("未能在物理背屏启动")
                            .setMessage(detail + "\n\n检查 ROOT / LSPosed；如果是原厂桌面抢占，请唤醒背屏后重试。")
                            .setPositiveButton("知道了", null)
                            .create();
                    blocked.setOnShowListener(ignored -> styleDialog(blocked));
                    blocked.show();
                }
            });
        }, "backdeck-rear-host").start();
    }

    private void showGestureEditor(String key, String gestureName) {
        ActionConfig current = ActionStore.get(this, key);
        final String[] type = {current.type};
        LinearLayout form = column();
        form.setPadding(dp(20), dp(8), dp(20), dp(4));

        Switch enabled = new Switch(this);
        enabled.setText("启用这个手势");
        enabled.setTextColor(WHITE);
        enabled.setTextSize(14);
        enabled.setChecked(current.enabled);
        form.addView(enabled);
        form.addView(space(18));
        form.addView(fieldLabel("动作名称"));
        EditText label = edit(current.label, "例如：微信扫一扫", false);
        form.addView(label, new LinearLayout.LayoutParams(-1, dp(46)));
        form.addView(space(15));
        form.addView(fieldLabel("打开方式"));
        TextView kind = text(typeName(type[0]), 12, ACCENT, Typeface.BOLD);
        form.addView(kind);
        form.addView(space(8));
        LinearLayout choices = row();
        TextView appButton = miniButton("选应用");
        TextView activityButton = miniButton("选 Activity");
        TextView uriButton = miniButton("输入 URI");
        choices.addView(appButton, weighted(1, dp(38), dp(5)));
        choices.addView(activityButton, weighted(1, dp(38), dp(5)));
        choices.addView(uriButton, weighted(1, dp(38), 0));
        form.addView(choices);
        form.addView(space(11));
        EditText value = edit(current.value, "链接、包名或 Activity", true);
        form.addView(value, new LinearLayout.LayoutParams(-1, dp(58)));
        final EditText[] topCompensationEditor = new EditText[1];
        appButton.setOnClickListener(v -> pickInstalledApp(picked -> {
            type[0] = picked.type;
            kind.setText(typeName(type[0]));
            label.setText(picked.label);
            value.setText(picked.value);
            if (topCompensationEditor[0] != null) topCompensationEditor[0].setText(
                    String.valueOf(ActionStore.suggestedTopCompensationPx(this, picked)));
        }));
        activityButton.setOnClickListener(v -> pickExportedActivity(picked -> {
            type[0] = picked.type;
            kind.setText(typeName(type[0]));
            label.setText(picked.label);
            value.setText(picked.value);
            if (topCompensationEditor[0] != null) topCompensationEditor[0].setText(
                    String.valueOf(ActionStore.suggestedTopCompensationPx(this, picked)));
        }));
        uriButton.setOnClickListener(v -> {
            type[0] = "uri";
            kind.setText(typeName(type[0]));
            value.setHint("例如：weixin://dl/scan");
            value.requestFocus();
        });
        form.addView(space(18));
        form.addView(fieldLabel("在哪里打开"));
        RadioGroup destination = new RadioGroup(this);
        destination.setOrientation(LinearLayout.VERTICAL);
        RadioButton front = radio("正屏", ActionConfig.DISPLAY_FRONT);
        RadioButton rear = radio("背屏直开（原生显示）", ActionConfig.DISPLAY_REAR_DIRECT);
        RadioButton safe = radio("背屏安全区（避开镜头）", ActionConfig.DISPLAY_REAR_SAFE);
        destination.addView(front);
        destination.addView(rear);
        destination.addView(safe);
        destination.check(current.displayId == ActionConfig.DISPLAY_FRONT ? front.getId()
                : current.displayId == ActionConfig.DISPLAY_REAR_SAFE ? safe.getId() : rear.getId());
        form.addView(destination);
        form.addView(space(6));
        TextView displayHint = text(displayModeHint(current.displayId), 11, MUTED, Typeface.NORMAL);
        displayHint.setLineSpacing(dp(3), 1f);
        form.addView(displayHint);
        destination.setOnCheckedChangeListener((group, checkedId) -> {
            int selected = checkedId == front.getId() ? ActionConfig.DISPLAY_FRONT
                    : checkedId == safe.getId() ? ActionConfig.DISPLAY_REAR_SAFE
                    : ActionConfig.DISPLAY_REAR_DIRECT;
            displayHint.setText(displayModeHint(selected));
        });
        EditText virtualDpi = addVirtualDpiEditor(form, current.virtualDpi);
        EditText virtualTopCropPx = addVirtualTopCropEditor(form, current.virtualTopCropPx);
        topCompensationEditor[0] = virtualTopCropPx;
        form.addView(space(18));
        Switch confirmHome = new Switch(this);
        confirmHome.setText("上滑返回表盘需二次确认");
        confirmHome.setTextColor(WHITE);
        confirmHome.setTextSize(14);
        confirmHome.setChecked(current.confirmReturnHome);
        form.addView(confirmHome);
        form.addView(text("首次上滑会回弹提示；3 秒内再上滑才返回。仅背屏打开时生效。",
                11, MUTED, Typeface.NORMAL));

        ScrollView wrapper = new ScrollView(this);
        wrapper.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("设置 · " + gestureName)
                .setView(wrapper)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            styleDialog(dialog);
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
                String actionValue = value.getText().toString().trim();
                if (enabled.isChecked() && actionValue.isEmpty()) {
                    value.setError("先选择应用或输入 URI");
                    return;
                }
                String actionLabel = label.getText().toString().trim();
                if (actionLabel.isEmpty()) actionLabel = gestureName;
                int checked = destination.getCheckedRadioButtonId();
                int displayId = checked == front.getId() ? ActionConfig.DISPLAY_FRONT
                        : checked == safe.getId() ? ActionConfig.DISPLAY_REAR_SAFE
                        : ActionConfig.DISPLAY_REAR_DIRECT;
                ActionConfig updated = new ActionConfig(type[0], actionValue, actionLabel,
                        displayId, enabled.isChecked());
                updated.virtualDpi = readVirtualDpi(virtualDpi);
                updated.virtualTopCropPx = readVirtualTopCropPx(virtualTopCropPx);
                updated.confirmReturnHome = confirmHome.isChecked();
                ActionStore.put(this, key, updated);
                refreshGestures();
                dialog.dismiss();
            });
        });
        dialog.show();
    }

    private void refreshDrawerItems() {
        if (drawerItemsContainer == null || drawerAdapter == null) return;
        drawerItemsContainer.removeAllViews();
        if (drawerItems.isEmpty()) {
            TextView empty = text("抽屉还是空的。点击上方按钮，手动加入应用或 Activity。",
                    13, MUTED, Typeface.NORMAL);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(20), dp(29), dp(20), dp(29));
            empty.setBackground(round(PANEL, 16, LINE));
            drawerItemsContainer.addView(empty);
            return;
        }
        for (int i = 0; i < drawerAdapter.getCount(); i++) {
            View item = drawerAdapter.getView(i, null, drawerItemsContainer);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.bottomMargin = dp(9);
            drawerItemsContainer.addView(item, params);
        }
    }

    private void addDrawerItem(List<ActionConfig> items, ActionConfig action, Runnable persist) {
        for (ActionConfig existing : items) {
            if (existing.type.equals(action.type) && existing.value.equals(action.value)) {
                existing.enabled = true;
                persist.run();
                Toast.makeText(this, "已在抽屉中启用", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        ActionConfig legacy = ActionStore.getLegacyDrawerConfig(this, action.type, action.value);
        if (legacy != null) {
            // v1 was a full auto-populated catalog. Reuse only this app's saved
            // viewport choices after the user explicitly adds it to v2.
            legacy.label = action.label;
            legacy.enabled = true;
            items.add(legacy);
        } else {
            action.displayId = ActionConfig.DISPLAY_REAR_SAFE;
            action.virtualTopCropPx = ActionStore.suggestedTopCompensationPx(this, action);
            action.enabled = true;
            items.add(action);
        }
        persist.run();
        Toast.makeText(this, "已添加到抽屉", Toast.LENGTH_SHORT).show();
    }

    private void persistDrawerItems(List<ActionConfig> items, DrawerAdapter adapter) {
        ActionStore.setDrawer(this, items);
        adapter.notifyDataSetChanged();
        refreshDrawerSummary();
        if (adapter == drawerAdapter) refreshDrawerItems();
    }

    private void showDrawerItemEditor(ActionConfig action, List<ActionConfig> items, DrawerAdapter adapter) {
        LinearLayout form = column();
        form.setPadding(dp(20), dp(8), dp(20), dp(5));
        Switch enabled = new Switch(this);
        enabled.setText("在抽屉中显示");
        enabled.setTextColor(WHITE);
        enabled.setTextSize(14);
        enabled.setChecked(action.enabled);
        form.addView(enabled);
        form.addView(space(18));
        form.addView(fieldLabel("在哪里打开"));
        RadioGroup destination = new RadioGroup(this);
        destination.setOrientation(LinearLayout.VERTICAL);
        RadioButton front = radio("正屏", ActionConfig.DISPLAY_FRONT);
        RadioButton rear = radio("背屏直开（原生显示）", ActionConfig.DISPLAY_REAR_DIRECT);
        RadioButton safe = radio("背屏安全区（避开镜头）", ActionConfig.DISPLAY_REAR_SAFE);
        destination.addView(front);
        destination.addView(rear);
        destination.addView(safe);
        destination.check(action.displayId == ActionConfig.DISPLAY_FRONT ? front.getId()
                : action.displayId == ActionConfig.DISPLAY_REAR_SAFE ? safe.getId() : rear.getId());
        form.addView(destination);
        form.addView(space(7));
        TextView hint = text(displayModeHint(action.displayId), 11, MUTED, Typeface.NORMAL);
        hint.setLineSpacing(dp(3), 1f);
        form.addView(hint);
        destination.setOnCheckedChangeListener((group, checkedId) -> {
            int selected = checkedId == front.getId() ? ActionConfig.DISPLAY_FRONT
                    : checkedId == safe.getId() ? ActionConfig.DISPLAY_REAR_SAFE
                    : ActionConfig.DISPLAY_REAR_DIRECT;
            hint.setText(displayModeHint(selected));
        });
        EditText virtualDpi = addVirtualDpiEditor(form, action.virtualDpi);
        EditText virtualTopCropPx = addVirtualTopCropEditor(form, action.virtualTopCropPx);
        form.addView(space(18));
        Switch confirmHome = new Switch(this);
        confirmHome.setText("上滑返回表盘需二次确认");
        confirmHome.setTextColor(WHITE);
        confirmHome.setTextSize(14);
        confirmHome.setChecked(action.confirmReturnHome);
        form.addView(confirmHome);
        form.addView(text("首次上滑会回弹提示；3 秒内再上滑才返回。仅背屏打开时生效。",
                11, MUTED, Typeface.NORMAL));
        ScrollView wrapper = new ScrollView(this);
        wrapper.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(action.label)
                .setView(wrapper)
                .setNegativeButton("取消", null)
                .setNeutralButton("移除", (which, button) -> confirmRemoveDrawerItem(action, items, adapter))
                .setPositiveButton("保存", (which, button) -> {
                    action.enabled = enabled.isChecked();
                    int checked = destination.getCheckedRadioButtonId();
                    action.displayId = checked == front.getId() ? ActionConfig.DISPLAY_FRONT
                            : checked == safe.getId() ? ActionConfig.DISPLAY_REAR_SAFE
                            : ActionConfig.DISPLAY_REAR_DIRECT;
                    action.virtualDpi = readVirtualDpi(virtualDpi);
                    action.virtualTopCropPx = readVirtualTopCropPx(virtualTopCropPx);
                    action.confirmReturnHome = confirmHome.isChecked();
                    persistDrawerItems(items, adapter);
                })
                .create();
        dialog.setOnShowListener(ignored -> styleDialog(dialog));
        dialog.show();
    }

    private void confirmRemoveDrawerItem(ActionConfig action, List<ActionConfig> items, DrawerAdapter adapter) {
        new AlertDialog.Builder(this)
                .setTitle("移除 " + action.label + "？")
                .setMessage("只从背屏抽屉移除，不会卸载应用。")
                .setNegativeButton("取消", null)
                .setPositiveButton("移除", (which, button) -> {
                    items.remove(action);
                    persistDrawerItems(items, adapter);
                }).show();
    }

    private final class DrawerAdapter extends BaseAdapter {
        private final List<ActionConfig> items;

        DrawerAdapter(List<ActionConfig> items) { this.items = items; }
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View reusable, ViewGroup parent) {
            ActionConfig action = items.get(position);
            LinearLayout row = row();
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(8), dp(10), dp(8));
            row.setBackground(round(PANEL, 15, LINE));
            LinearLayout names = column();
            TextView title = text(action.label, 13, WHITE, Typeface.BOLD);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            names.addView(title);
            String detail = "component".equals(action.type) ? "Activity" : "应用";
            if (action.displayId == ActionConfig.DISPLAY_REAR_SAFE)
                detail += " · " + action.virtualDpi + " DPI";
            if (action.displayId == ActionConfig.DISPLAY_REAR_SAFE && action.virtualTopCropPx > 0)
                detail += " · 裁顶 " + action.virtualTopCropPx + "px";
            if (action.displayId != ActionConfig.DISPLAY_FRONT)
                detail += action.confirmReturnHome ? " · 二次确认" : " · 单次返回";
            names.addView(text(detail, 10, MUTED, Typeface.NORMAL));
            row.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
            TextView destination = badge(displayLabel(action.displayId) + " ›", ACCENT, PANEL_2);
            destination.setOnClickListener(v -> showDrawerItemEditor(action, items, this));
            row.addView(destination);
            Switch enabled = new Switch(MainActivity.this);
            enabled.setChecked(action.enabled);
            LinearLayout.LayoutParams switchParams = new LinearLayout.LayoutParams(-2, -2);
            switchParams.leftMargin = dp(12);
            row.addView(enabled, switchParams);
            enabled.setOnCheckedChangeListener((button, checked) -> {
                action.enabled = checked;
                ActionStore.setDrawer(MainActivity.this, items);
                refreshDrawerSummary();
            });
            row.setOnClickListener(v -> showDrawerItemEditor(action, items, this));
            row.setOnLongClickListener(v -> {
                confirmRemoveDrawerItem(action, items, this);
                return true;
            });
            return row;
        }
    }

    private void pickInstalledApp(ActionPicked callback) {
        PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN);
        launcher.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> matches = pm.queryIntentActivities(launcher, 0);
        List<ResolveInfo> apps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo item : matches) {
            if (item.activityInfo == null || !item.activityInfo.exported) continue;
            if (seen.add(item.activityInfo.packageName)) apps.add(item);
        }
        Collections.sort(apps, new ResolveInfo.DisplayNameComparator(pm));
        if (apps.isEmpty()) {
            Toast.makeText(this, "未找到可启动应用；请检查应用可见性配置", Toast.LENGTH_LONG).show();
            return;
        }
        CharSequence[] labels = new CharSequence[apps.size()];
        for (int i = 0; i < apps.size(); i++) labels[i] = apps.get(i).loadLabel(pm);
        new AlertDialog.Builder(this)
                .setTitle("选择应用")
                .setItems(labels, (dialog, index) -> {
                    ResolveInfo chosen = apps.get(index);
                    String packageName = chosen.activityInfo.packageName;
                    callback.onPicked(new ActionConfig("package", packageName,
                            labels[index].toString(), 1, true));
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void pickExportedActivity(ActionPicked callback) {
        pickInstalledApp(app -> {
            PackageManager pm = getPackageManager();
            List<ActivityInfo> activities = new ArrayList<>();
            try {
                PackageInfo info = pm.getPackageInfo(app.value, PackageManager.GET_ACTIVITIES);
                if (info.activities != null) {
                    for (ActivityInfo activity : info.activities) {
                        if (activity.exported && activity.enabled) activities.add(activity);
                    }
                }
            } catch (PackageManager.NameNotFoundException ignored) { }
            if (activities.isEmpty()) {
                Toast.makeText(this, "这个应用没有可公开启动的 Activity", Toast.LENGTH_LONG).show();
                return;
            }
            Collections.sort(activities, (a, b) -> a.name.compareToIgnoreCase(b.name));
            CharSequence[] labels = new CharSequence[activities.size()];
            for (int i = 0; i < activities.size(); i++) {
                ActivityInfo activity = activities.get(i);
                CharSequence display = activity.loadLabel(pm);
                String simple = activity.name.substring(activity.name.lastIndexOf('.') + 1);
                labels[i] = (display == null || display.toString().equals(app.label))
                        ? simple : display + " · " + simple;
            }
            new AlertDialog.Builder(this)
                    .setTitle(app.label + " · Activity")
                    .setItems(labels, (dialog, index) -> {
                        ActivityInfo chosen = activities.get(index);
                        String component = new ComponentName(chosen.packageName, chosen.name).flattenToString();
                        callback.onPicked(new ActionConfig("component", component,
                                app.label + " · " + chosen.name.substring(chosen.name.lastIndexOf('.') + 1), 1, true));
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
    }

    private void styleDialog(AlertDialog dialog) {
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(round(PANEL, 20));
        if (dialog.getButton(DialogInterface.BUTTON_POSITIVE) != null)
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setTextColor(ACCENT);
        if (dialog.getButton(DialogInterface.BUTTON_NEGATIVE) != null)
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setTextColor(MUTED);
        if (dialog.getButton(DialogInterface.BUTTON_NEUTRAL) != null)
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setTextColor(MUTED);
    }

    private String typeName(String type) {
        if ("package".equals(type)) return "应用 · 包名";
        if ("component".equals(type)) return "指定 Activity";
        return "URI / Deep Link";
    }

    private String displayLabel(int displayId) {
        if (displayId == ActionConfig.DISPLAY_FRONT) return "正屏";
        if (displayId == ActionConfig.DISPLAY_REAR_SAFE) return "背屏安全区";
        return "背屏直开";
    }

    private String displayModeHint(int displayId) {
        if (displayId == ActionConfig.DISPLAY_FRONT) {
            return "在正屏打开；背屏仍保留助手界面。";
        }
        if (displayId == ActionConfig.DISPLAY_REAR_SAFE) {
            return "应用显示在避开左侧镜头的虚拟安全区。受保护页面可能黑屏，部分触控或输入法可能不完整；遇到问题请改用背屏直开。";
        }
        return "在整块物理背屏原生打开。受保护页面兼容性更好，但应用可能占用左侧镜头区域。";
    }

    private EditText addVirtualDpiEditor(LinearLayout form, int currentDpi) {
        form.addView(space(15));
        form.addView(fieldLabel("背屏安全区 DPI（160–450）"));
        EditText field = edit(String.valueOf(ActionConfig.clampVirtualDpi(currentDpi)),
                "例如 240", false);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setSingleLine(true);
        field.setSelectAllOnFocus(true);
        form.addView(field, new LinearLayout.LayoutParams(-1, dp(46)));
        form.addView(space(7));
        LinearLayout presets = row();
        for (int dpi : new int[]{220, 240, 300}) {
            TextView preset = miniButton(dpi + " DPI");
            preset.setOnClickListener(v -> field.setText(String.valueOf(dpi)));
            presets.addView(preset, weighted(1, dp(36), dpi == 300 ? 0 : dp(6)));
        }
        form.addView(presets);
        form.addView(space(5));
        form.addView(text("仅安全区生效；DPI 越低，元素越小、能容纳更多内容。",
                10, MUTED, Typeface.NORMAL));
        return field;
    }

    private int readVirtualDpi(EditText field) {
        try {
            return ActionConfig.clampVirtualDpi(
                    Integer.parseInt(field.getText().toString().trim()));
        } catch (NumberFormatException ignored) {
            return ActionConfig.DEFAULT_VIRTUAL_DPI;
        }
    }

    private EditText addVirtualTopCropEditor(LinearLayout form, int currentPx) {
        form.addView(space(15));
        form.addView(fieldLabel("背屏安全区顶部视口补偿（0–240 px）"));
        EditText field = edit(String.valueOf(ActionConfig.clampVirtualTopCropPx(currentPx)),
                "默认 0；例如 144", false);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setSingleLine(true);
        field.setSelectAllOnFocus(true);
        form.addView(field, new LinearLayout.LayoutParams(-1, dp(46)));
        form.addView(space(7));
        LinearLayout presets = row();
        for (int px : new int[]{0, 96, 144}) {
            TextView preset = miniButton(px + " px");
            preset.setOnClickListener(v -> field.setText(String.valueOf(px)));
            presets.addView(preset, weighted(1, dp(36), px == 144 ? 0 : dp(6)));
        }
        form.addView(presets);
        form.addView(space(5));
        form.addView(text("默认 0（不裁顶）。仅对仍沿用正屏状态栏高度的旧应用手动补偿；补偿不拉伸画面，但过大可能隐藏真实内容。",
                10, MUTED, Typeface.NORMAL));
        return field;
    }

    private int readVirtualTopCropPx(EditText field) {
        try {
            return ActionConfig.clampVirtualTopCropPx(
                    Integer.parseInt(field.getText().toString().trim()));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private EditText edit(String initial, String hint, boolean monospace) {
        EditText field = new EditText(this);
        field.setSingleLine(!monospace);
        field.setInputType(InputType.TYPE_CLASS_TEXT | (monospace ? InputType.TYPE_TEXT_VARIATION_URI : 0));
        field.setTextColor(WHITE);
        field.setHintTextColor(MUTED);
        field.setTextSize(13);
        field.setHint(hint);
        field.setText(initial == null ? "" : initial);
        field.setPadding(dp(12), dp(8), dp(12), dp(8));
        field.setBackground(round(PANEL_2, 11, LINE));
        return field;
    }

    private RadioButton radio(String name, int identity) {
        RadioButton button = new RadioButton(this);
        button.setId(View.generateViewId());
        button.setText(name);
        button.setTextColor(WHITE);
        button.setTextSize(14);
        button.setPadding(0, 0, dp(22), 0);
        button.setTag(identity);
        return button;
    }

    private TextView fieldLabel(String label) {
        TextView view = text(label, 12, MUTED, Typeface.BOLD);
        view.setPadding(0, 0, 0, dp(7));
        return view;
    }

    private TextView miniButton(String title) {
        TextView button = text(title, 12, WHITE, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setBackground(round(PANEL_2, 10, LINE));
        return button;
    }

    private TextView actionButton(String title, boolean primary) {
        TextView button = text(title, 14, primary ? BG : ACCENT, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setBackground(round(primary ? ACCENT : PANEL_2, 13, primary ? ACCENT : LINE));
        return button;
    }

    private TextView badge(String title, int foreground, int background) {
        TextView view = text(title, 10, foreground, Typeface.BOLD);
        view.setPadding(dp(9), dp(6), dp(9), dp(6));
        view.setBackground(round(background, 8));
        return view;
    }

    private LinearLayout card() {
        LinearLayout card = column();
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(round(PANEL, 18, LINE));
        return card;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
    }

    private TextView text(String value, float size, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, style);
        return view;
    }

    private View space(int heightDp) {
        View view = new View(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(1, dp(heightDp)));
        return view;
    }

    private LinearLayout.LayoutParams weighted(int weight, int height, int rightMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, height, weight);
        params.rightMargin = rightMargin;
        return params;
    }

    private GradientDrawable round(int fill, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private GradientDrawable round(int fill, int radiusDp, int stroke) {
        GradientDrawable drawable = round(fill, radiusDp);
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

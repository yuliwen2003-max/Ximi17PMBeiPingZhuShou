# BackDeck · 小米 17 Pro 系列背屏助手

已在 **Xiaomi 17 Pro Max (2509FPN0BC)、Android 17 / HyperOS 4** 实机验证的 Android/ROOT 原型。正屏负责设置，背屏显示表盘并接收手势。正屏设置现按底栏拆为「主页／手势捷径／应用抽屉」三页，透明状态栏与深色界面衔接。

## 已实现

- 背屏表盘：居中时间、日期、Wi-Fi/蜂窝信号格数、电量；按实际 `DisplayCutout` 避开左侧相机岛。
- 长按、双击、左右滑动：选择应用、显式 Activity 或 URI；逐项启停并选择正屏／背屏直开／背屏安全区。
- 上滑应用抽屉：默认空白，只显示用户从正屏手动添加的应用或 Activity；可在正屏「应用抽屉」分页直接逐项编辑、禁用或移除。新添加应用默认背屏安全区、240 DPI、**顶部不裁切（0px）**；已有手动配置不覆盖。旧版自动全选目录保留为配置备份，但不再自动显示；重新添加旧版有明确自定义的同一应用时恢复其 DPI/视口设置。
- 背屏手势冲突处理：仅当助手表盘或虚拟屏宿主位于背屏前台时，让小米底部 Home、右侧 Back 触摸覆盖层暂不接收输入。上滑进入助手抽屉；在虚拟屏应用里从底部上滑，画面随手指上移并露出表盘，松手返回。用户选定的背屏直开应用启用专用底边手势窗口；小米的 Home 覆盖层仅在该窗口确实存在时让出触摸，返回目标是助手表盘而非原厂主页。回到原厂背屏后，原厂手势保持原样。系统级双击回原厂主页也只对助手宿主和用户配置的背屏直开应用关闭，应用内部双击仍能接收。
- 可选的亮屏恢复：正屏开启「背屏亮起后自动恢复助手」后，前台服务监听背屏 OFF/DOZE→ON；仅当小米原生背屏仍在前台时恢复助手，不抢占相机或其他应用。正屏可一键暂停接管并返回小米原生背屏。
- **背屏安全区**：在右侧 680×596 可见区域嵌入虚拟显示。默认 240 DPI、**0px 裁顶**，可逐应用调整。仅对仍沿用正屏状态栏高度的旧应用，可手动设置 144px 等顶部补偿；这会生成 680×740 的竖屏视口并上移可见画面。可见区保持 **1:1 像素映射，不拉伸、不裁左右**。右侧外角读取系统圆角半径做裁切。底部上滑回表盘后会保留同一个安全区会话；再次打开同一应用直接恢复原任务，不再在最近任务中生成副本。
- 触摸由常驻 ROOT `app_process` 逐帧转发 DOWN/MOVE/UP/CANCEL 与多指事件；背屏边缘返回手势转发给虚拟屏应用，不再占用底部按钮区域。微信扫码、IT之家点击/滑动已实机验证。
- **背屏直开**：Activity 在物理背屏 display 上运行；LSPosed 的小范围窗口配置 hook 按系统 cutout 将第三方 Activity 约束到右侧。微信扫码实测 window bounds 为 `Rect(296,0–976,596)`。
- **输入法与锁屏**：系统框架 hook 只为物理背屏上已选择的应用、以及 BackDeck 自有虚拟显示上的已选择应用保留本地 IME；键盘随目标 display 渲染。表盘和安全区宿主可在锁屏界面显示，但不会解锁设备或覆盖第三方应用自身的锁屏策略。
- 直开应用底部返回：HyperOS 默认拒绝普通应用在独立背屏创建 2038 类型窗口；`system_server` hook 仅对背屏助手自身 UID、display 1、2038 窗口放行。24px 底边开始的上滑手势由助手接管，过渡跟手；其他手势和未选择的应用仍按原系统处理。若系统更新使窗口创建失败，前台服务可将观察到的“已选择直开应用→原厂主页”单次转移拉回助手表盘。
- KernelSU/Magisk `su` 用于启动私有 Activity、向虚拟屏注入手势及发布允许包清单；LSPosed `system` 作用域仅对本应用和配置过的目标包放开 HyperOS 背屏白名单。

默认长按为微信扫一扫（安全区）；双击为支付宝扫一扫（直开，因 AppLock/安全窗口可能无法在普通虚拟屏呈现）。这些都是用户可改的。

## 构建及启用

1. 安装 JDK 17+ 和 Android SDK 36；执行 `gradlew.bat assembleDebug`。
2. 执行 `adb install -r app/build/outputs/apk/debug/app-debug.apk`。
3. 在 LSPosed 启用「背屏助手」模块，作用域为 **系统框架 / system**，然后重启设备。仅 ROOT 不足以绕过这台 HyperOS 的背屏启动拦截（实测错误码 102）。
4. 在正屏打开 BackDeck，授予 ROOT；按「启动背屏助手」。成功启动后会启用亮屏恢复；可在正屏关闭该开关，或使用「暂停接管，返回小米原生背屏」。
5. 在正屏「管理应用抽屉」中自行添加需要的应用。升级自旧版时不会自动全选已安装应用。

可用以下命令复核：

```sh
adb shell su -c 'am start -W --display 1 -n com.seagull.backdeck/.RearActivity'
adb shell dumpsys activity activities
adb shell dumpsys SurfaceFlinger --display-id
```

`displayId` 不应在 APP 中长期假定为 1；APP 运行时通过 `DisplayManager` 的安全/受保护内屏标志寻找**物理背屏**，排除自己创建的 `BackDeckEmbedded` 虚拟屏。启动后还会核验物理背屏顶部 Activity；若被原厂桌面抢占，正屏会显示具体错误，不再误报成功。上面的命令仅针对本测试机。SurfaceFlinger 截图使用的是**物理** display ID，不是逻辑 ID。

## 已知边界

- 原厂背屏在 DOZE/AOD 阶段会主动接管显示。本版不抢 AOD，以免影响锁屏、耗电和原厂相机功能；开启亮屏恢复后，仅在原厂主页位于前台时重新显示助手。HyperOS 省电策略可能停止前台服务，届时可从正屏重新启动。
- 双击冲突策略依赖本机 HyperOS 的 `getInputMiuiFlags()` / `0x100` 语义与 LSPosed 系统框架作用域；系统更新后需重新实测。系统弹窗、输入法或锁屏获得焦点时，不保证覆盖其双击行为。
- 直开底边手势依赖本机 HyperOS `WindowManagerServiceImpl.hasPermission` 和 LSPosed 的系统框架作用域；系统更新后须重新验证 2038 窗口创建和 OEM Home 让出触摸。它只保留右侧可视区最底部的 24px 作为系统手势区：此条带内未完成的手势被消费，不再回放给小米 Home；起点不在该条带的触摸仍按目标应用处理。
- 普通虚拟显示不能呈现某些 `FLAG_SECURE` 页面；本项目不会清除或伪造第三方应用的保护标志。此类应用应改为「背屏直开」（物理背屏具备 secure/protected buffer 能力）或「正屏」。
- 顶部裁切是逐应用可调的，可能隐藏该应用真正需要的顶部内容；不需要时设为 0。虚拟显示方向按其实际宽高强制为横／竖屏，不影响正屏应用的方向。
- 部分应用本身不适配约 242×212dp 的背屏安全区；可逐项改为正屏。系统/OEM Activity 不做强制布局 hook，防止影响原厂组件。
- 背屏应用内部页面启动受 HyperOS 非可信虚拟屏策略限制；LSPosed `system` 作用域仅对**已在本助手虚拟屏上的同 UID 应用**放行内部导航，不全局放开其它虚拟屏。若目标任务退出或迁离，宿主自动收起并返回背屏表盘；APP 不强杀目标进程。
- **不注入 QQ、IT之家等第三方应用进程**。虚拟屏本身已报告无状态栏；少数应用仍读取正屏全局的旧 `status_bar_height`，故使用逐应用、可关闭的宿主视口补偿，而不是修改应用 APK 或进程。

参考：[Android DisplayCutout](https://developer.android.com/develop/ui/views/layout/display-cutout)、[VirtualDisplay API](https://developer.android.com/reference/android/hardware/display/DisplayManager)、[ActivityOptions.setLaunchDisplayId](https://developer.android.com/reference/android/app/ActivityOptions#setLaunchDisplayId(int))、[REAREye](https://github.com/killerprojecte/REAREye) 的 HyperOS 兼容研究。代码未复制 REAREye 的实现。

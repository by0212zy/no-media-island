package com.example.noislandmedia;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 现代 libxposed 模块（API 101）。
 * 作用：拦截 HyperOS「超级岛 / 通知岛（DynamicIsland）」把媒体渲染成顶部胶囊的逻辑，
 * 让媒体不再上岛，但音乐 App 自身的 MediaStyle 通知仍正常留在通知栏。
 *
 * <p>v1.2 修复思路（针对「hook 注册成功但媒体照样上岛」）：
 * 静态分析确认媒体上岛的唯一入口是 {@code MiuiIslandMediaController.addDynamicIslandView(...)}，
 * 它内部调用 {@code DynamicIslandContent.addDynamicIslandView / updateDynamicIslandView}（真正落岛的接口方法）。
 * ART 会把 {@code addDynamicIslandView} 内联进其调用方，导致直接 hook 该方法时回调不触发。
 * 因此 v1.2 采取<b>双保险</b>：
 *   (1) 继续 hook 3 参 {@code addDynamicIslandView} 并 deoptimize 其调用方；
 *   (2) 直接在「真正落岛」的接口方法 {@code DynamicIslandContent.addDynamicIslandView / updateDynamicIslandView}
 *       上 hook，只要调用方来自 {@code MiuiIslandMediaController}（即媒体）就拦截，物理上无法被内联绕过。
 *
 * <p>v1.3 新增（针对「媒体不上岛了，但状态栏也没有音乐图标」）：
 * 反编译发现状态栏媒体图标被 {@code MediaCoordinator$1.shouldFilterOut} 过滤掉——
 * 当媒体特性开启且 {@code Flags.notificationsBackgroundIcons()} 为 true 时，
 * 媒体通知会从正常状态栏图标列表被抽走、挪到「背景图标」区（该位置原本就是由岛显示的）。
 * v1.3 对该方法 hook：对<b>媒体通知</b>强制返回 {@code false}（不抽走），
 * 让音乐 App 的状态栏图标正常显示。
 *
 * <p>v1.4 新增（针对「下拉栏出现两张音乐卡片」+ 对齐 HyperOS 2.0.212 的效果）：
 * 对比 2.0.212 与 3.0.3 的 SystemUI 得到关键差异——
 *   2.0.212：<b>不存在</b> MiuiIslandMediaController / DynamicIslandContent /
 *            MiuiIslandMediaViewBinder / MiuiMediaNotificationController（即没有媒体岛整套东西）。
 *   3.0.3  ：新增了上述组件。其中 {@code MiuiMediaNotificationController} 被
 *            {@code MiuiNotificationSectionsManager.reinflateViews} 持有，是通知中心里的一个 section，
 *            也就是那张「系统风格媒体卡」（用户不想要的那张）。
 * 放行 {@code shouldFilterOut} 后，通知中心会同时出现「音乐 App 自己的原始通知」+「系统风格媒体卡」两张。
 * 因此 v1.4 在 {@code MediaDataManager.onNotificationAdded} 处对媒体通知直接跳过，
 * 不再生成 MediaData → 系统风格媒体卡消失，只留音乐 App 自己的通知 + 状态栏图标，
 * 与 2.0.212 的表现一致。
 *
 * <p>v1.5–v1.27 迭代：通知栏媒体卡横滑切换（多会话）、原生页码指示点、
 * 原始通知行折叠为 0 高度且保持 GONE、进度条区域只拖动不切卡、
 * 整卡几何命中（封面/标题/按钮任意位置可滑）等，均面向 HyperOS 3.0.3 实测调校。
 *
 * <p>v2.0 正式版：全部功能改为<b>设置开关驱动</b>。App 侧（SettingsActivity）写入
 * libxposed Remote Preferences（group = {@link Prefs#GROUP}），本模块在 SystemUI 进程
 * 通过 {@code getRemotePreferences} 读取同一份数据并注册变化监听，钩子热路径只读缓存字段：
 * <ul>
 *   <li>block_island：阻止媒体上超级岛（默认开）</li>
 *   <li>status_icon：音乐 App 显示状态栏图标（默认开）</li>
 *   <li>hide_miuicard：隐藏系统媒体卡片，改显 App 自己的播放通知（默认关）</li>
 *   <li>card_swipe：媒体卡横滑切换（默认开）</li>
 *   <li>indicator：显示页码指示点（默认开）</li>
 *   <li>hide_raw：隐藏原始媒体通知行（默认开；hide_miuicard 开启时无效）</li>
 *   <li>only_listed / packages：仅对列表内应用生效 + 生效应用列表（默认全应用）</li>
 * </ul>
 *
 * <p>v2.1：所有功能钩子常驻注册、拦截器实时读开关——设置界面勾选后<b>即时生效</b>，
 * 无需重启 SystemUI；新增「隐藏系统媒体卡片」；移除本机必然失败的死 hook
 * （DynamicIslandContent 抽象方法无法 hook）。
 */
public class NoMediaIsland extends XposedModule {

    private static final String TAG = "NoMediaIsland";
    private static final String SYSTEMUI = "com.android.systemui";

    private static final String TARGET_CLASS =
            "com.android.systemui.statusbar.notification.mediaisland.MiuiIslandMediaController";
    private static final String METHOD = "addDynamicIslandView";

    // addDynamicIslandView(MiuiIslandMediaViewHolder, MiuiIslandMediaViewHolder, MediaData)
    private static final String[] PARAM_TYPES = {
            "com.android.systemui.statusbar.notification.mediaisland.MiuiIslandMediaViewHolder",
            "com.android.systemui.statusbar.notification.mediaisland.MiuiIslandMediaViewHolder",
            "com.android.systemui.media.controls.shared.model.MediaData"
    };

    /**
     * {@code addDynamicIslandView} 在本机 MiuiSystemUI 中的全部调用方，需 deoptimize 以防内联。
     * 每项：[类名, 方法名, 参数类型（可空；多个用 ';' 分隔）]。
     */
    private static final String[][] CALLERS = {
            {"com.android.systemui.statusbar.notification.mediaisland.MiuiIslandMediaController$mediaDataChangeListener$1",
             "onMediaDataChanged", "com.android.systemui.media.controls.shared.model.MediaData"},
            {"com.android.systemui.statusbar.notification.mediaisland.MiuiIslandMediaController",
             "onCastInfoChanged", "java.util.Map"},
            {"com.android.systemui.statusbar.notification.mediaisland.MiuiIslandMediaController",
             "reInflateView$1"},
    };

    // ===== 设置（remote preferences，App 界面与 Hook 实时共享）=====
    /** remote preferences 实例（可能为 null：模块未激活/服务不可用时用默认值）。 */
    private static volatile SharedPreferences prefs = null;
    /** 各开关缓存值（变化监听里刷新，钩子热路径只读缓存，不走 IPC）。 */
    private static volatile boolean vBlockIsland = Prefs.DEF_BLOCK_ISLAND;
    private static volatile boolean vStatusIcon = Prefs.DEF_STATUS_ICON;
    private static volatile boolean vCardSwipe = Prefs.DEF_CARD_SWIPE;
    private static volatile boolean vIndicator = Prefs.DEF_INDICATOR;
    private static volatile boolean vHideRaw = Prefs.DEF_HIDE_RAW;
    private static volatile boolean vHideMiuiCard = Prefs.DEF_HIDE_MIUICARD;
    private static volatile boolean vOnlyListed = Prefs.DEF_ONLY_LISTED;
    private static volatile Set<String> vBlockedPkgs =
            new java.util.HashSet<>(Prefs.parsePackages(Prefs.DEF_PACKAGES));

    /** 读取 remote preferences 并注册变化监听（ Hook 进程启动时调用一次）。 */
    private void initPrefs() {
        try {
            prefs = getRemotePreferences(Prefs.GROUP);
            if (prefs != null) {
                prefs.registerOnSharedPreferenceChangeListener((sp, key) -> reloadPrefs());
                reloadPrefs();
                log(Log.INFO, TAG, "prefs ready: block_island=" + vBlockIsland
                        + " status_icon=" + vStatusIcon + " card_swipe=" + vCardSwipe
                        + " indicator=" + vIndicator + " hide_raw=" + vHideRaw
                        + " hide_miuicard=" + vHideMiuiCard
                        + " only_listed=" + vOnlyListed);
            } else {
                log(Log.WARN, TAG, "remote prefs unavailable, use defaults");
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "init prefs failed: " + t);
        }
    }

    /** 把 remote preferences 的当前值刷进缓存字段。 */
    private static void reloadPrefs() {
        SharedPreferences sp = prefs;
        if (sp == null) {
            return;
        }
        vBlockIsland = sp.getBoolean(Prefs.KEY_BLOCK_ISLAND, Prefs.DEF_BLOCK_ISLAND);
        vStatusIcon = sp.getBoolean(Prefs.KEY_STATUS_ICON, Prefs.DEF_STATUS_ICON);
        vCardSwipe = sp.getBoolean(Prefs.KEY_CARD_SWIPE, Prefs.DEF_CARD_SWIPE);
        vIndicator = sp.getBoolean(Prefs.KEY_INDICATOR, Prefs.DEF_INDICATOR);
        vHideRaw = sp.getBoolean(Prefs.KEY_HIDE_RAW, Prefs.DEF_HIDE_RAW);
        vHideMiuiCard = sp.getBoolean(Prefs.KEY_HIDE_MIUICARD, Prefs.DEF_HIDE_MIUICARD);
        vOnlyListed = sp.getBoolean(Prefs.KEY_ONLY_LISTED, Prefs.DEF_ONLY_LISTED);
        vBlockedPkgs = new java.util.HashSet<>(Prefs.parsePackages(
                sp.getString(Prefs.KEY_PACKAGES, Prefs.DEF_PACKAGES)));
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!SYSTEMUI.equals(param.getPackageName())) {
            return;
        }

        ClassLoader cl = param.getClassLoader();
        initPrefs();

        // 设计说明（v2.1）：所有功能钩子<b>常驻注册一次</b>，每个拦截器第一行先读设置缓存
        // （volatile boolean，开销可忽略），关闭时直接 proceed。这样任何开关在设置界面
        // 勾选后都即时生效，不需要重启 SystemUI。
        // 注：早期版本还 hook 过落岛接口 DynamicIslandContent.add/updateDynamicIslandView
        // 作为「双保险」，但本机（HyperOS 3.0.3）实测必报 "Cannot hook abstract methods"
        // （接口抽象方法无法 hook），属无用 hook，已移除。

        // (1) 阻止媒体上超级岛：入口级 hook + 调用方去优化（防 ART 内联）
        try {
            Class<?> target = Class.forName(TARGET_CLASS, false, cl);
            Class<?>[] pts = new Class<?>[PARAM_TYPES.length];
            for (int i = 0; i < PARAM_TYPES.length; i++) {
                pts[i] = Class.forName(PARAM_TYPES[i], false, cl);
            }
            Method hooked = target.getDeclaredMethod(METHOD, pts);

            // 入口级 hook：直接拦截 addDynamicIslandView(3 参)
            hook(hooked)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept((XposedInterface.Chain chain) -> {
                        if (!vBlockIsland) {
                            return chain.proceed();
                        }
                        if (vOnlyListed) {
                            Object mediaData = chain.getArgs().get(2);
                            String pkg = readPackageName(mediaData);
                            if (pkg == null || !vBlockedPkgs.contains(pkg)) {
                                return chain.proceed();
                            }
                        }
                        log(Log.INFO, TAG, "[3arg] media blocked from DynamicIsland");
                        return null;
                    });

            log(Log.INFO, TAG, "hooked " + TARGET_CLASS + "." + METHOD);

            // ===== 去优化，避免内联导致回调不触发 =====
            log(Log.INFO, TAG, "deoptimize self=" + deoptimize(hooked));
            for (String[] spec : CALLERS) {
                try {
                    Class<?> cc = Class.forName(spec[0], false, cl);
                    Class<?>[] cpts;
                    if (spec.length >= 3 && !spec[2].isEmpty()) {
                        String[] ps = spec[2].split(";");
                        cpts = new Class<?>[ps.length];
                        for (int i = 0; i < ps.length; i++) {
                            cpts[i] = Class.forName(ps[i], false, cl);
                        }
                    } else {
                        cpts = new Class<?>[0];
                    }
                    Method cm = cc.getDeclaredMethod(spec[1], cpts);
                    log(Log.INFO, TAG, "deoptimize caller " + spec[1] + " -> " + deoptimize(cm));
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "deoptimize caller failed: " + spec[1] + " (" + t + ")");
                }
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hook failed: " + t);
        }

        // (2) 状态栏音乐图标：让媒体通知不被从状态栏图标列表抽走
        hookStatusBarMediaIcon(cl);

        // (3) 从数据入口断掉「媒体上岛」（双保险，确保岛不出现）
        hookBlockIslandDataIntake(cl);

        // (4) 隐藏 HyperOS 自带的「系统风格媒体卡」（MiuiMediaNotificationController）
        hookHideMiuiMediaCard(cl);

        // (5) 隐藏 App 裸通知那一行（行仍渲染→状态栏图标保留）
        //     「隐藏系统媒体卡片」开启时不停用本钩子，由 isMediaRow 动态判断：
        //     系统卡隐藏期间始终显示 App 自己的通知，避免下拉栏没有媒体卡。
        hookHideRawMediaRow(cl);

        // (6) 多媒体卡片横滑切换：在 MIUI 媒体卡上左右滑动切换不同音乐 App 的会话
        //     dispatchTouchEvent 拦截器第一行检查 vCardSwipe；
        //     指示器挂载/更新检查 vIndicator；系统卡隐藏时卡片不会 attach，自然停用。
        hookMediaCardSwitcher(cl);
    }

    /**
     * 恢复媒体通知的状态栏图标。
     * {@code MediaCoordinator$1.shouldFilterOut} 在媒体特性开启 + 背景图标特性开启时，
     * 会把媒体通知从正常状态栏图标列表过滤掉（挪到背景图标区，该位置原本由岛显示）。
     * 这里对媒体通知强制返回 false（不抽走），让音乐 App 图标正常显示在状态栏。
     */
    private void hookStatusBarMediaIcon(ClassLoader cl) {
        try {
            Class<?> coordinatorCls = Class.forName(
                    "com.android.systemui.statusbar.notification.collection.coordinator.MediaCoordinator$1",
                    false, cl);
            Class<?> entryCls = Class.forName(
                    "com.android.systemui.statusbar.notification.collection.NotificationEntry",
                    false, cl);
            Class<?> sbnCls = Class.forName(
                    "android.service.notification.StatusBarNotification", false, cl);
            Class<?> mdmCls = Class.forName(
                    "com.android.systemui.media.controls.domain.pipeline.MediaDataManager",
                    false, cl);

            Method shouldFilterOut = coordinatorCls.getDeclaredMethod(
                    "shouldFilterOut", entryCls, long.class);
            Method isMediaNotification = mdmCls.getDeclaredMethod(
                    "isMediaNotification", sbnCls);

            hook(shouldFilterOut)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept((XposedInterface.Chain chain) -> {
                        if (!vStatusIcon) {
                            return chain.proceed(); // 开关关闭：恢复原生过滤行为
                        }
                        Object entry = chain.getArgs().get(0);
                        try {
                            // NotificationEntry.mSbn : StatusBarNotification (实际为 ExpandedNotification 子类)
                            Field sbnField = findField(entryCls, "mSbn");
                            if (sbnField != null) {
                                sbnField.setAccessible(true);
                                Object sbn = sbnField.get(entry);
                                if (sbn != null
                                        && Boolean.TRUE.equals(isMediaNotification.invoke(null, sbn))) {
                                    log(Log.INFO, TAG, "[statusbar] media -> keep icon (not filtered)");
                                    return Boolean.FALSE;
                                }
                            }
                        } catch (Throwable t) {
                            log(Log.WARN, TAG, "[statusbar] check failed: " + t);
                        }
                        return chain.proceed();
                    });

            log(Log.INFO, TAG, "hooked MediaCoordinator$1.shouldFilterOut");
            // 防止该方法被调用方内联导致回调不触发
            log(Log.INFO, TAG, "deoptimize shouldFilterOut -> " + deoptimize(shouldFilterOut));
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook statusbar media icon failed: " + t);
        }
    }

    /**
     * 停用 3.0.3 独有的「系统媒体卡」组件 {@code MiuiMediaNotificationController}，
     * 忠实复刻 2.0.212（该版本<b>根本不存在</b>此组件，也没有媒体岛）。
     *
     * <p>为什么不是「隐藏通知行」：v1.5 尝试跳过 {@code NotificationRowBinderImpl.inflateViews}
     * 来隐藏原始通知行，实测<b>状态栏图标也随之消失</b>——图标与行渲染是绑定的，无法这样解耦。
     * 所以改为从源头停掉 3.0.3 新增的那个系统卡组件本身：它拿不到媒体数据就不会渲染，
     * 于是只剩音乐 App 自己的通知 + 状态栏图标，即 2.0.212 的效果。
     *
     * <p>拦截它的两个数据入口（void 方法不 proceed 即阻断）：
     * <ul>
     *   <li>{@code MiuiMediaNotificationController$mediaDataListener$1.onMediaDataLoaded}</li>
     *   <li>{@code MiuiMediaNotificationController$MediaDataChangeListener.onMediaDataChanged}</li>
     * </ul>
     */
    private void hookHideRawMediaRow(ClassLoader cl) {
        try {
            Class<?> rowCls = Class.forName(
                    "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow",
                    false, cl);
            Class<?> sbnCls = Class.forName(
                    "android.service.notification.StatusBarNotification", false, cl);
            Class<?> mdmCls = Class.forName(
                    "com.android.systemui.media.controls.domain.pipeline.MediaDataManager", false, cl);
            Method isMedia = mdmCls.getDeclaredMethod("isMediaNotification", sbnCls);
            Method getEntry = rowCls.getMethod("getEntry");

            // 1) 主手段：媒体行高度置 0（持久生效；行仍在管线里，状态栏图标不受影响）
            Method getH = rowCls.getDeclaredMethod("getIntrinsicHeight");
            hook(getH)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept((XposedInterface.Chain chain) -> {
                        if (isMediaRow(chain.getThisObject(), getEntry, isMedia)) {
                            return 0; // 折叠为 0 高度 → 界面上看不见
                        }
                        return chain.proceed();
                    });
            log(Log.INFO, TAG, "hooked ExpandableNotificationRow.getIntrinsicHeight (collapse media row)");
            log(Log.INFO, TAG, "deoptimize getIntrinsicHeight -> " + deoptimize(getH));

            // 2) 附加手段：附加到窗口时直接 GONE（不 hook setVisibility，避免递归）
            Method onAttach = rowCls.getDeclaredMethod("onAttachedToWindow");
            hook(onAttach)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept((XposedInterface.Chain chain) -> {
                        Object row = chain.getThisObject();
                        Object result = chain.proceed();
                        try {
                            if (isMediaRow(row, getEntry, isMedia)) {
                                android.view.View.class
                                        .getMethod("setVisibility", int.class)
                                        .invoke(row, android.view.View.GONE);
                                log(Log.INFO, TAG, "[row] hide raw media row (GONE)");
                            }
                        } catch (Throwable t) {
                            log(Log.WARN, TAG, "[rowvisibility] " + t);
                        }
                        return result;
                    });
            log(Log.INFO, TAG, "hooked ExpandableNotificationRow.onAttachedToWindow");
            log(Log.INFO, TAG, "deoptimize onAttachedToWindow -> " + deoptimize(onAttach));

            // 3) 兜底：媒体行被外部布局重新 setVisible(VISIBLE) 时强制保持 GONE，
            //    彻底消除“折叠但残留占位”造成的卡片下方空白（随会话数 1→2→3 倍增的根因）。
            //    仅对 ExpandableNotificationRow 子类做判断，避免全局每个 View 都走反射。
            Method setVis = android.view.View.class.getDeclaredMethod("setVisibility", int.class);
            hook(setVis)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept((XposedInterface.Chain chain) -> {
                        Object view = chain.getThisObject();
                        List<Object> args = chain.getArgs();
                        int vis = (args != null && !args.isEmpty() && args.get(0) instanceof Integer)
                                ? (int) args.get(0) : android.view.View.VISIBLE;
                        try {
                            if (vis != android.view.View.GONE
                                    && view instanceof android.view.View
                                    && view.getClass().getName().contains("ExpandableNotificationRow")
                                    && isMediaRow(view, getEntry, isMedia)) {
                                // 递归进入本 hook：第二次 vis==GONE 会正常 proceed，不会死循环
                                ((android.view.View) view).setVisibility(android.view.View.GONE);
                                return null;
                            }
                        } catch (Throwable ignored) {
                        }
                        return chain.proceed();
                    });
            log(Log.INFO, TAG, "hooked View.setVisibility (keep media row GONE, no residue)");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook hide raw media row failed: " + t);
        }
    }

    /**
     * 从<b>数据入口</b>断掉媒体上岛，而不只是拦渲染。
     *
     * <p>假设：3.0.3 之所以不用标准轮播 {@code MediaCarouselController}，是因为系统判定
     * 「媒体要上岛」（岛是 HyperOS 3.0 的媒体主入口）。如果只拦渲染，系统状态仍然是“媒体在岛上”，
     * 标准轮播就不会接管。这里直接拦 {@code MiuiIslandMediaController} 的媒体数据入口
     * {@code mediaDataChangeListener$1.onMediaDataChanged}，让它根本收不到媒体，
     * 促使系统回落到标准轮播（支持多会话滑动切换，即 2.0.212 的行为）。
     */
    private void hookBlockIslandDataIntake(ClassLoader cl) {
        try {
            Class<?> c = Class.forName(
                    "com.android.systemui.statusbar.notification.mediaisland"
                            + ".MiuiIslandMediaController$mediaDataChangeListener$1", false, cl);
            Method m = null;
            for (Method mm : c.getDeclaredMethods()) {
                if ("onMediaDataChanged".equals(mm.getName())) {
                    m = mm;
                    break;
                }
            }
            if (m == null) {
                log(Log.WARN, TAG, "island listener onMediaDataChanged not found");
                return;
            }
            hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept((XposedInterface.Chain chain) -> {
                        if (!vBlockIsland) {
                            return chain.proceed(); // 开关关闭：媒体照常入岛
                        }
                        // 「仅对列表内应用生效」开启时：不在名单内的媒体照常入岛
                        if (vOnlyListed) {
                            Object md = (chain.getArgs() != null && !chain.getArgs().isEmpty())
                                    ? chain.getArgs().get(0) : null;
                            String pkg = readPackageName(md);
                            if (pkg == null || !vBlockedPkgs.contains(pkg)) {
                                return chain.proceed();
                            }
                        }
                        log(Log.INFO, TAG, "[islanddata] block island intake -> fallback to standard carousel");
                        return null; // void 方法：不 proceed 即阻断
                    });
            log(Log.INFO, TAG, "hooked island data intake (blocked)");
            log(Log.INFO, TAG, "deoptimize island intake -> " + deoptimize(m));
        } catch (Throwable t) {
            log(Log.WARN, TAG, "block island data intake failed: " + t);
        }
    }

    /**
     * 隐藏 HyperOS 3.0.3 的「系统风格媒体卡」（{@code MiuiMediaNotificationController}，
     * 即下拉通知栏里那张带大封面的系统卡）。
     *
     * <p>做法：拦掉它自己的两个数据入口（void 方法不 proceed 即阻断）——
     * <ul>
     *   <li>{@code MiuiMediaNotificationController$mediaDataListener$1.onMediaDataLoaded}</li>
     *   <li>{@code MiuiMediaNotificationController$MediaDataChangeListener.onMediaDataChanged}</li>
     * </ul>
     * 控制器拿不到媒体数据就不渲染卡片，通知栏改为显示音乐 App 自己的播放通知
     * （HyperOS 2.0.212 的效果）。开启后横滑切换/页码指示点随卡片一同消失。
     *
     * <p>拦截器实时读 {@link #vHideMiuiCard}：设置界面勾选后即时生效，无需重启 SystemUI。
     * 「仅对列表内应用生效」开启时从调用参数里找 MediaData/NotificationEntry 读包名，
     * 不在名单内的媒体照常显示系统卡。
     */
    private void hookHideMiuiMediaCard(ClassLoader cl) {
        String[][] specs = {
                {"com.android.systemui.statusbar.notification.mediacontrol"
                        + ".MiuiMediaNotificationController$mediaDataListener$1", "onMediaDataLoaded"},
                {"com.android.systemui.statusbar.notification.mediacontrol"
                        + ".MiuiMediaNotificationController$MediaDataChangeListener", "onMediaDataChanged"},
        };
        for (String[] spec : specs) {
            try {
                Class<?> c = Class.forName(spec[0], false, cl);
                Method m = null;
                for (Method mm : c.getDeclaredMethods()) {
                    if (spec[1].equals(mm.getName())) {
                        m = mm;
                        break;
                    }
                }
                if (m == null) {
                    log(Log.WARN, TAG, "miui card method not found: " + spec[1]);
                    continue;
                }
                final String mName = spec[1];
                hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept((XposedInterface.Chain chain) -> {
                            if (!vHideMiuiCard) {
                                return chain.proceed(); // 开关关闭：照常显示系统卡
                            }
                            if (vOnlyListed) {
                                String pkg = null;
                                List<Object> args = chain.getArgs();
                                if (args != null) {
                                    for (Object a : args) {
                                        pkg = readAnyPackageName(a);
                                        if (pkg != null) {
                                            break;
                                        }
                                    }
                                }
                                if (pkg == null || !vBlockedPkgs.contains(pkg)) {
                                    return chain.proceed();
                                }
                            }
                            log(Log.INFO, TAG, "[miuicard] block " + mName + " (hide system media card)");
                            return null; // void 方法：不 proceed 即阻断
                        });
                log(Log.INFO, TAG, "hooked " + spec[0].substring(spec[0].lastIndexOf('.') + 1)
                        + "." + spec[1] + " (hide system media card)");
                log(Log.INFO, TAG, "deoptimize " + spec[1] + " -> " + deoptimize(m));
            } catch (Throwable t) {
                log(Log.WARN, TAG, "hide miui media card failed: " + spec[1] + " (" + t + ")");
            }
        }
    }

    // ===== 多媒体卡片横滑切换（MIUI 同款：管理多个媒体会话）=====

    /** 当前所有活跃媒体会话：notificationKey -> MediaData（保持插入顺序）。 */
    private static final Map<String, Object> SESSIONS =
            Collections.synchronizedMap(new LinkedHashMap<String, Object>());
    /** 当前卡片正在控制的会话 key。 */
    private static volatile String currentSessionKey = null;

    /**
     * 所有登记过的媒体卡 View（弱引用集合）。
     * HyperOS 会为不同形态（正常/迷你/锁屏）各调用一次 attach，
     * 只记一个会记错（v1.13 的「划不动」就是这么来的），所以全部登记，任一命中即处理。
     */
    private static final Map<View, Boolean> CARD_VIEWS =
            Collections.synchronizedMap(new java.util.WeakHashMap<View, Boolean>());
    /** 卡片对应的视图控制器与其 bindMediaData。 */
    private static volatile Object cardViewController = null;
    private static volatile Method cardBindMethod = null;
    /** 手势状态（只针对 mediaCardView）。 */
    private static volatile float gestureDownX;
    private static volatile float gestureDownY;
    private static volatile boolean gestureDecided;
    private static volatile boolean gestureHorizontal;
    /** 本次手势是否已切换过（避免一次滑动连续切多张）。 */
    private static volatile boolean gestureSwitched;
    /** 本次触摸是否落在媒体卡内（DOWN 时命中测试决定，MOVE/UP 沿用）。 */
    private static volatile boolean gestureOnCard;
    /** 本次触摸是否落在进度条上（拖动调进度，绝不触发切卡手势）。 */
    private static volatile boolean gestureOnSeek;

    // ===== 手势覆盖范围 + 页面指示器（原生 PageIndicator） =====
    /**
     * 整卡根容器（覆盖封面/标题/进度条/按钮全部区域的容器，通常是 MiuiMediaHeaderView，
     * 或指示器实际挂载的那个 FrameLayout 父容器）。用于<b>屏幕坐标几何命中</b>：
     * 以该容器的屏幕边界判定手指是否落在卡内，完全不依赖「触摸 view 是否为某子树后代」的层级假设，
     * 因此对封面/标题/进度条位于 player 兄弟子树的情况同样稳健。
     */
    private static volatile View mediaCardRoot = null;
    /** 原生 PageIndicator 实例（com.android.systemui.qs.PageIndicator），直接挂在卡片 FrameLayout 父容器上。 */
    private static volatile View pageIndicatorView = null;
    /** PageIndicator 的 setNumPages / setLocation 方法。 */
    private static volatile Method piSetNumPages = null;
    private static volatile Method piSetLocation = null;
    /** 已设置的页数（避免重复 setNumPages）。 */
    private static volatile int piPageCount = -1;
    private static volatile Handler mainHandler = null;

    /**
     * 在 HyperOS 3 的 MIUI 媒体卡上实现<b>横滑切换媒体会话</b>（MIUI 同款多会话管理）。
     *
     * <p>解决问题：{@code MiuiMediaNotificationController} 只呈现 {@code topMediaData}（一个会话），
     * 同时播两个音乐 App 时另一个无法控制。这里：
     * <ol>
     *   <li>hook 控制器构造函数，从参数里抓出 {@code MediaDataManager}（该字段在本机不存在，
     *       但构造函数第二个参数就是它）。</li>
     *   <li>注册 {@code MediaDataManager.Listener}（动态代理）收集所有会话 MediaData。</li>
     *   <li>hook {@code MiuiMediaViewController.attach(holder)}，拿到卡片 View（holder.player），
     *       挂上横滑手势。</li>
     *   <li>横滑时调用 {@code bindMediaData(另一个会话的 MediaData)} 完成切换。</li>
     * </ol>
     */
    private void hookMediaCardSwitcher(ClassLoader cl) {
        try {
            Class<?> ctrlCls = Class.forName(
                    "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaNotificationController",
                    false, cl);
            Class<?> vcCls = Class.forName(
                    "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaViewController",
                    false, cl);
            Class<?> holderCls = Class.forName(
                    "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaViewHolder",
                    false, cl);
            Class<?> mdmCls = Class.forName(
                    "com.android.systemui.media.controls.domain.pipeline.MediaDataManager", false, cl);
            Class<?> mediaDataCls = Class.forName(
                    "com.android.systemui.media.controls.shared.model.MediaData", false, cl);

            final Method bindMediaData = vcCls.getDeclaredMethod("bindMediaData", mediaDataCls);
            final Method attach = vcCls.getDeclaredMethod("attach", holderCls);
            final Field playerField = holderCls.getDeclaredField("player");
            playerField.setAccessible(true);

            // 1) 构造函数：抓 MediaDataManager 注册会话监听
            for (Constructor<?> ctor : ctrlCls.getDeclaredConstructors()) {
                hook(ctor)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept((XposedInterface.Chain chain) -> {
                            Object result = chain.proceed();
                            try {
                                Object mdm = null;
                                for (Object a : chain.getArgs()) {
                                    if (a != null && mdm == null && mdmCls.isInstance(a)) {
                                        mdm = a;
                                    }
                                }
                                if (mdm != null) {
                                    registerSessionListener(mdm, cl);
                                }
                            } catch (Throwable t) {
                                log(Log.WARN, TAG, "[switcher] ctor capture: " + t);
                            }
                            return result;
                        });
            }

            // 2) attach：记录媒体卡 View / 视图控制器（手势在 dispatchTouchEvent 里统一处理）
            hook(attach)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept((XposedInterface.Chain chain) -> {
                        Object result = chain.proceed();
                        try {
                            Object vc = chain.getThisObject();
                            Object holder = chain.getArgs().get(0);
                            Object player = playerField.get(holder);
                            if (vc != null && player instanceof View) {
                                View pv = (View) player;
                                CARD_VIEWS.put(pv, Boolean.TRUE);
                                cardViewController = vc;
                                cardBindMethod = bindMediaData;
                                View parent = pv.getParent() instanceof View
                                        ? (View) pv.getParent() : null;
                                if (parent instanceof ViewGroup) {
                                    CARD_VIEWS.put(parent, Boolean.TRUE);
                                }
                                // 关键：向上找到整卡根容器 MiuiMediaHeaderView（卡片在通知堆里的根容器），
                                // 登记它，使卡片任意位置（封面/标题/进度条/按钮）的滑动都能切卡，
                                // 而不再局限于中间按钮区。
                                View cardRoot = findCardRoot(pv);
                                if (cardRoot != null && cardRoot != pv) {
                                    CARD_VIEWS.put(cardRoot, Boolean.TRUE);
                                }
                                // 延迟挂指示器：此刻 player 可能还没真正加入布局，
                                // 需在 player.post 里再取 parent（参考 HyperLyric）。
                                attachIndicator(pv);
                                // 布局完成后再解析一次整卡根容器：attach 时 player 可能尚未挂入
                                // MiuiMediaHeaderView，用 post 延迟拿到真正的整卡根，记为 mediaCardRoot
                                // （供几何命中判定整卡边界使用）。
                                final View fpv = pv;
                                fpv.post(() -> {
                                    try {
                                        View r = findCardRoot(fpv);
                                        if (r != null) {
                                            CARD_VIEWS.put(r, Boolean.TRUE);
                                            mediaCardRoot = r;
                                            Log.i(TAG, "[switcher] card root re-registered: "
                                                    + r.getClass().getSimpleName());
                                        }
                                    } catch (Throwable ignored) {
                                    }
                                });
                                log(Log.INFO, TAG, "[switcher] media card registered root="
                                        + (cardRoot == null ? "null"
                                                : cardRoot.getClass().getSimpleName())
                                        + " total=" + CARD_VIEWS.size());
                            }
                        } catch (Throwable t) {
                            log(Log.WARN, TAG, "[switcher] attach capture failed: " + t);
                        }
                        return result;
                    });

            // 2b) 手势拦截：挂在 View.dispatchTouchEvent，按身份限定只处理这张媒体卡。
            //     这样卡片内任意子 View（封面/进度条/按钮）上的滑动都能捕获，
            //     不会出现 DOWN 被子 View 吃掉而收不到 MOVE 的情况。
            hookViewDispatchTouch();

            // 3) bindMediaData：记录当前正在控制的会话
            hook(bindMediaData)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept((XposedInterface.Chain chain) -> {
                        Object result = chain.proceed();
                        try {
                            String key = readStringField(chain.getArgs().get(0), "notificationKey");
                            if (key != null) {
                                currentSessionKey = key;
                                log(Log.INFO, TAG, "[switcher] now controlling: " + key);
                            }
                        } catch (Throwable ignored) {
                        }
                        return result;
                    });

            log(Log.INFO, TAG, "hooked media card switcher");
            log(Log.INFO, TAG, "deoptimize attach -> " + deoptimize(attach));
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook media card switcher failed: " + t);
        }
    }

    /**
     * 从媒体卡内的某个 View 向上爬父链，返回<b>整张卡片</b>的根容器。
     *
     * <p>本机（HyperOS 3.0.3 / 安卓15）实测：{@code holder.player} 只是卡片<b>右侧控制区</b>，
     * 其直接父 {@code MiuiMediaHeaderView} 也只覆盖控制区；<b>封面在它的兄弟节点</b>里，
     * 二者同属于一个更大的「整卡容器」。因此整卡根 = {@code MiuiMediaHeaderView} 的父容器。
     * 把命中/指示器都挂在这个更大的容器上，才能让封面/标题/进度条/按钮整张卡都能滑。
     *
     * <p>兼容另一种机型（封面就在 MiuiMediaHeaderView 内部，它本身即整卡）：
     * 若 MiuiMediaHeaderView 的父节点是通知行（ExpandableNotificationRow 等），
     * 说明没有更大的兄弟容器，则回退为 MiuiMediaHeaderView 自身。
     */
    private static View findCardRoot(View v) {
        View cur = v;
        View header = null;
        View top = v;
        while (cur != null) {
            String name = cur.getClass().getName();
            if (header == null && (name.contains("MiuiMediaHeaderView")
                    || name.endsWith("MediaHeaderView")
                    || name.contains("MediaHeader"))) {
                header = cur;
            }
            top = cur;
            cur = (cur.getParent() instanceof View) ? (View) cur.getParent() : null;
        }
        if (header == null) {
            return top;
        }
        View parent = (header.getParent() instanceof View) ? (View) header.getParent() : null;
        if (parent instanceof ViewGroup && !isNotificationRow(parent.getClass().getName())) {
            return parent; // 整卡容器（含封面兄弟节点）
        }
        return header; // 回退：封面就在 header 内部，header 即整卡
    }

    /** 判断类名是否为「通知列表/通知行」相关容器，命中区域绝不能向上爬到这些。 */
    private static boolean isNotificationRow(String name) {
        return name.contains("ExpandableNotificationRow")
                || name.contains("NotificationContentView")
                || name.contains("ActivatableNotificationView")
                || name.contains("NotificationBackgroundView")
                || name.endsWith("NotificationRow")
                || name.contains("MiuiNotificationView")
                || name.contains("NotificationStackScrollLayout")
                || name.contains("StackScrollLayout");
    }

    /** 从 view 向上找第一个 MiuiMediaHeaderView（卡片控制区容器，FrameLayout）。 */
    private static View findHeader(View v) {
        View cur = v;
        while (cur != null) {
            String name = cur.getClass().getName();
            if (name.contains("MiuiMediaHeaderView")
                    || name.endsWith("MediaHeaderView")
                    || name.contains("MediaHeader")) {
                return cur;
            }
            cur = (cur.getParent() instanceof View) ? (View) cur.getParent() : null;
        }
        return null;
    }

    /**
     * 把原生 PageIndicator 挂到媒体卡底部。
     * 完全参照 HyperLyric 的可靠实现：取 {@code player.getParent()}（即 MiuiMediaHeaderView，
     * 一个 FrameLayout），把 PageIndicator<b>直接</b>作为子 View 加进去，
     * 用 {@code Gravity.BOTTOM | CENTER_HORIZONTAL} 让它贴底居中。
     *
     * <p><b>关键：不包一层包装 FrameLayout、也不挂到 player 自身。</b>
     * 直接加到 FrameLayout 父容器上只是 overlay（player 先定义尺寸，指示器叠加在底部），
     * 不会撑高卡片高度——这正是之前 v1.19 用包装 FrameLayout 挂到 player 上、导致
     * 空白随会话数 1→2→3 倍增、且跑到顶部的根因。
     *
     * <p>若此刻 player 尚未加入布局（parent 为 null），post 一次重试（参考 HyperLyric）；
     * 若父容器不是 FrameLayout 则直接跳过，绝不强行挂载。
     * 设置项「显示页码指示点」关闭时直接跳过。
     */
    private static void attachIndicator(View player) {
        if (!vIndicator) {
            return;
        }
        try {
            // 指示器挂在 MiuiMediaHeaderView（FrameLayout）底部：作为 overlay 不撑高卡片、不产生空白，
            // 且位置精确落在卡片底部。
            View header = findHeader(player);
            ViewGroup host = (header instanceof ViewGroup) ? (ViewGroup) header
                    : (player.getParent() instanceof ViewGroup ? (ViewGroup) player.getParent() : null);
            if (host == null) {
                // 尚未挂载到布局，延迟一次重试
                player.post(() -> attachIndicator(player));
                return;
            }
            // 整卡根容器（含封面兄弟节点）用于几何命中，覆盖整张卡片
            View cardRoot = findCardRoot(player);
            if (cardRoot != null) {
                mediaCardRoot = cardRoot;
            }
            attachIndicatorTo(host);
        } catch (Throwable t) {
            Log.w(TAG, "[indicator] attach: " + t);
        }
    }

    private static void attachIndicatorTo(ViewGroup host) {
        try {
            // 只接受 FrameLayout 父容器（MiuiMediaHeaderView）；其余类型不挂，避免改变布局产生空白
            if (!(host instanceof FrameLayout)) {
                Log.w(TAG, "[indicator] host not FrameLayout: "
                        + host.getClass().getName() + " (skip)");
                return;
            }
            // 已挂在同一个宿主上则只更新，不重复 addView
            if (pageIndicatorView != null && pageIndicatorView.getParent() == host) {
                updateIndicator();
                return;
            }
            // 若挂到过别的父容器，先移除，保证全程只有一个指示器实例
            if (pageIndicatorView != null && pageIndicatorView.getParent() != null) {
                ((ViewGroup) pageIndicatorView.getParent()).removeView(pageIndicatorView);
            }
            if (pageIndicatorView == null) {
                pageIndicatorView = createPageIndicator(
                        host.getContext(), host.getClass().getClassLoader());
            }
            if (pageIndicatorView == null) {
                return;
            }
            pageIndicatorView.setClickable(false);
            pageIndicatorView.setFocusable(false);
            pageIndicatorView.setFocusableInTouchMode(false);
            int margin = (int) (4 * host.getResources().getDisplayMetrics().density);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            lp.bottomMargin = margin;
            host.addView(pageIndicatorView, lp);
            piPageCount = -1;
            // 整卡根容器（含封面兄弟节点）用于手势几何命中，覆盖整张卡片；
            // 注意：指示器本身挂在 header（host），但命中区域用更大的 cardRoot。
            mediaCardRoot = findCardRoot(host);
            Log.i(TAG, "[indicator] PageIndicator attached to "
                    + host.getClass().getSimpleName() + " (bottom, overlay), cardRoot="
                    + (mediaCardRoot == null ? "null" : mediaCardRoot.getClass().getSimpleName()));
            updateIndicator();
        } catch (Throwable t) {
            Log.w(TAG, "[indicator] attachTo: " + t);
        }
    }

    /** 创建 SystemUI 原生 PageIndicator（com.android.systemui.qs.PageIndicator）。 */
    private static View createPageIndicator(Context ctx, ClassLoader cl) {
        try {
            Class<?> pic = Class.forName("com.android.systemui.qs.PageIndicator", false, cl);
            if (!View.class.isAssignableFrom(pic)) {
                return null;
            }
            Constructor<?> ctor = pic.getConstructor(Context.class, AttributeSet.class);
            View v = (View) ctor.newInstance(ctx, null);
            piSetNumPages = pic.getMethod("setNumPages", int.class);
            piSetLocation = pic.getMethod("setLocation", float.class);
            Log.i(TAG, "[indicator] native PageIndicator created");
            return v;
        } catch (Throwable t) {
            Log.w(TAG, "[indicator] create PageIndicator failed (fallback: none): " + t);
            return null;
        }
    }

    /** 更新页面指示器：会话 <2 时隐藏（setNumPages(0)+GONE，原生指示器自行折叠不占空间），否则显示并高亮当前会话。 */
    private static void updateIndicator() {
        if (mainHandler == null) {
            try {
                mainHandler = new Handler(Looper.getMainLooper());
            } catch (Throwable t) {
                return;
            }
        }
        mainHandler.post(() -> {
            try {
                View pi = pageIndicatorView;
                if (pi == null) {
                    return;
                }
                // 设置项关闭指示点时保持隐藏（运行时可实时生效）
                if (!vIndicator) {
                    pi.setVisibility(View.GONE);
                    piPageCount = -1;
                    return;
                }
                int count;
                synchronized (SESSIONS) {
                    count = SESSIONS.size();
                }
                if (count <= 1) {
                    // 单会话：隐藏且不占任何空间（参照 HyperLyric 的 hide()）
                    if (piSetNumPages != null) {
                        try {
                            piSetNumPages.invoke(pi, 0);
                        } catch (Throwable ignored) {
                        }
                    }
                    pi.setVisibility(View.GONE);
                    piPageCount = -1;
                    return;
                }
                List<String> keys;
                synchronized (SESSIONS) {
                    keys = new ArrayList<>(SESSIONS.keySet());
                }
                int activeIdx = currentSessionKey == null ? 0 : keys.indexOf(currentSessionKey);
                if (activeIdx < 0) {
                    activeIdx = 0;
                }
                if (piPageCount != count && piSetNumPages != null) {
                    piSetNumPages.invoke(pi, count);
                    piPageCount = count;
                }
                pi.setVisibility(View.VISIBLE);
                if (piSetLocation != null) {
                    piSetLocation.invoke(pi, (float) activeIdx);
                }
                Log.i(TAG, "[indicator] updated pages=" + count + " active=" + activeIdx);
            } catch (Throwable t) {
                Log.w(TAG, "[indicator] update: " + t);
            }
        });
    }

    /** 注册 MediaDataManager.Listener（动态代理）收集所有媒体会话。 */
    private void registerSessionListener(Object mediaDataManager, ClassLoader cl) {
        try {
            Class<?> listenerCls = Class.forName(
                    "com.android.systemui.media.controls.domain.pipeline.MediaDataManager$Listener",
                    false, cl);
            Method addListener = null;
            for (Method m : mediaDataManager.getClass().getMethods()) {
                if ("addListener".equals(m.getName()) && m.getParameterCount() == 1) {
                    addListener = m;
                    break;
                }
            }
            if (addListener == null) {
                log(Log.WARN, TAG, "[switcher] addListener not found");
                return;
            }
            InvocationHandler handler = (proxy, method, args) -> {
                String name = method.getName();
                if ("onMediaDataLoaded".equals(name)) {
                    String key = args != null && args.length > 0 ? (String) args[0] : null;
                    Object data = args != null && args.length > 2 ? args[2] : null;
                    if (key != null && data != null) {
                        SESSIONS.put(key, data);
                        log(Log.INFO, TAG, "[switcher] session: " + key
                                + " (total=" + SESSIONS.size() + ")");
                        updateIndicator();
                    }
                    return null;
                }
                if ("onMediaDataRemoved".equals(name)) {
                    String key = args != null && args.length > 0 ? (String) args[0] : null;
                    if (key != null) {
                        SESSIONS.remove(key);
                        log(Log.INFO, TAG, "[switcher] removed: " + key);
                        updateIndicator();
                    }
                    return null;
                }
                if ("toString".equals(name)) return "NoMediaIsland.Switcher";
                if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                if ("equals".equals(name)) return proxy == (args != null ? args[0] : null);
                Class<?> rt = method.getReturnType();
                if (rt == boolean.class) return Boolean.FALSE;
                if (rt.isPrimitive()) return 0;
                return null;
            };
            Object proxy = Proxy.newProxyInstance(cl, new Class<?>[]{listenerCls}, handler);
            addListener.invoke(mediaDataManager, proxy);
            log(Log.INFO, TAG, "[switcher] MediaDataManager listener registered");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "[switcher] register listener failed: " + t);
        }
    }

    /** 切换到上/下一个媒体会话（step = +1 下一个，-1 上一个）。 */
    private void switchSession(int step, Object viewController, Method bindMethod) {
        synchronized (SESSIONS) {
            if (SESSIONS.size() < 2) {
                log(Log.INFO, TAG, "[switcher] only one session, nothing to switch");
                return;
            }
            List<String> keys = new ArrayList<>(SESSIONS.keySet());
            int idx = currentSessionKey == null ? -1 : keys.indexOf(currentSessionKey);
            if (idx < 0) idx = 0;
            int next = (idx + step + keys.size()) % keys.size();
            String nextKey = keys.get(next);
            Object data = SESSIONS.get(nextKey);
            try {
                bindMethod.invoke(viewController, data);
                currentSessionKey = nextKey;
                log(Log.INFO, TAG, "[switcher] switched -> " + nextKey);
                updateIndicator();
            } catch (Throwable t) {
                log(Log.WARN, TAG, "[switcher] bind failed: " + t);
            }
        }
    }

    /** 读取对象的 String 字段（先 getter 再字段，含父类）。 */
    private static String readStringField(Object target, String name) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod("get" + Character.toUpperCase(name.charAt(0))
                    + name.substring(1));
            Object r = m.invoke(target);
            if (r instanceof String) return (String) r;
        } catch (Throwable ignored) {
        }
        try {
            Field f = findField(target.getClass(), name);
            if (f != null) {
                f.setAccessible(true);
                Object r = f.get(target);
                if (r instanceof String) return (String) r;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 命中测试：从手指触摸的 view 向上实时爬祖先链，只要遇到「整卡根容器 MiuiMediaHeaderView」
     * 或任一登记的媒体卡视图，即视为卡内。
     *
     * <p><b>关键修复（v1.22）：在手势触发的当下实时计算，不依赖 attach 时机的静态登记。</b>
     * 此前 attach 钩子在布局完成前执行，player 尚未挂入 MiuiMediaHeaderView，findCardRoot 只爬到
     * 中间按钮区就把该区域登记进 CARD_VIEWS，导致「只有卡片中间能横滑、封面/标题/进度条区域失效」。
     * 指示器之所以正确，是因为它走了 player.post() 延迟挂接、布局完成后才解析到真正的根容器。
     * 现在命中测试同样实时向上找 MiuiMediaHeaderView，任意子 View（封面/标题/进度条/按钮）上的
     * 触摸都能命中整卡，而非局限于中间按钮区。
     */
    private static boolean isInsideCard(View v) {
        if (v == null) {
            return false;
        }
        View p = v;
        while (p != null) {
            if (CARD_VIEWS.containsKey(p)) {
                return true;
            }
            String name = p.getClass().getName();
            if (name.contains("MiuiMediaHeaderView")
                    || name.endsWith("MediaHeaderView")
                    || name.contains("MediaHeader")) {
                return true;
            }
            Object parent = p.getParent();
            p = (parent instanceof View) ? (View) parent : null;
        }
        return false;
    }

    /**
     * 整卡命中测试（v1.23 主方案）：以「整卡根容器」的<b>屏幕坐标边界</b>判定手指是否落在卡内。
     *
     * <p>为什么不用祖先链：本机（HyperOS 3.0.3 / 安卓15）的 {@code holder.player} 只是卡片中间的
     * 控制区，封面/标题/进度条是位于 {@code MiuiMediaHeaderView} 内的<b>兄弟子树</b>。仅靠「触摸 view
     * 是否为某子树后代」的祖先链判定，会因层级假设而漏掉兄弟子树，导致「只有中间能滑」。
     * 而指示器挂载的那个根容器正好覆盖整张卡片，用它的屏幕矩形做几何命中，对兄弟/合成布局都稳健。
     *
     * <p>优先用 {@link #mediaCardRoot}（指示器宿主，整卡根）；若尚未解析则退化为实时向上找
     * MiuiMediaHeaderView；两者皆无再退化为祖先链 {@link #isInsideCard(View)}。
     */
    private static boolean isInsideCardGeometry(View self, MotionEvent ev) {
        if (self == null || ev == null) {
            return false;
        }
        // 1) 直接用整卡根容器的屏幕边界判定
        View root = mediaCardRoot;
        if (root == null) {
            root = resolveCardRoot(self);
            if (root != null) {
                mediaCardRoot = root;
            }
        }
        if (root != null && pointInViewScreenBounds(root, ev.getRawX(), ev.getRawY())) {
            return true;
        }
        // 2) 整行命中：封面/控制区可能是同一行的左右兄弟（无共同父容器），
        //    用整卡根容器的 Y 范围 + 屏幕左右安全区判定，覆盖同行任意位置。
        if (root != null && pointInSameRow(root, ev.getRawX(), ev.getRawY())) {
            return true;
        }
        // 3) 兜底：祖先链判定
        return isInsideCard(self);
    }

    /** 屏幕坐标 (x,y) 是否落在 view 的同一「水平行」内（Y 范围含容差，X 不限，用于覆盖同行兄弟封面）。 */
    private static boolean pointInSameRow(View v, float x, float y) {
        if (v == null || v.getWidth() <= 0 || v.getHeight() <= 0) {
            return false;
        }
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        float density = v.getResources().getDisplayMetrics().density;
        float inset = 10f * density; // 上下各放宽 10dp，容忍封面与控制在 Y 上的细微错位
        boolean inRow = y >= loc[1] - inset && y <= loc[1] + v.getHeight() + inset;
        // X 限制在屏幕有效范围（避免命中到右侧其它通知列），给一个合理的安全边距
        int screenW = v.getResources().getDisplayMetrics().widthPixels;
        float edge = 4f * density;
        boolean inX = x >= edge && x <= screenW - edge;
        return inRow && inX;
    }

    /** 从 view 向上爬祖先，返回第一个整卡根容器（MiuiMediaHeaderView 等）。 */
    private static View resolveCardRoot(View v) {
        View p = v;
        while (p != null) {
            String name = p.getClass().getName();
            if (name.contains("MiuiMediaHeaderView")
                    || name.endsWith("MediaHeaderView")
                    || name.contains("MediaHeader")) {
                return p;
            }
            Object parent = p.getParent();
            p = (parent instanceof View) ? (View) parent : null;
        }
        return null;
    }

    /** 判断屏幕坐标 (x,y) 是否落在 view 的可见屏幕矩形内（含少量内缩容差）。 */
    private static boolean pointInViewScreenBounds(View v, float x, float y) {
        if (v == null || v.getWidth() <= 0 || v.getHeight() <= 0) {
            return false;
        }
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        // 上下各内缩 2dp，避免误把卡片与相邻通知间的缝隙算进卡内
        float inset = 2f * v.getResources().getDisplayMetrics().density;
        return x >= loc[0]
                && x <= loc[0] + v.getWidth()
                && y >= loc[1] + inset
                && y <= loc[1] + v.getHeight() - inset;
    }

    /** 缓存的进度条实例（HyperOS 用 miuix 的 HyperProgressSeekBar），避免每次遍历。 */
    private static volatile java.lang.ref.WeakReference<View> seekBarRef = null;

    /** 在卡片根容器子树里查找进度条（SeekBar/ProgressBar 及其子类），带缓存。 */
    private static View findSeekBar(View root) {
        if (root == null) {
            return null;
        }
        View cached = seekBarRef != null ? seekBarRef.get() : null;
        if (cached != null && cached.getParent() != null && cached.getWidth() > 0) {
            return cached;
        }
        View found = findSeekBarRec(root, 8);
        if (found != null) {
            seekBarRef = new java.lang.ref.WeakReference<>(found);
        }
        return found;
    }

    private static View findSeekBarRec(View v, int depth) {
        if (v == null || depth <= 0) {
            return null;
        }
        String n = v.getClass().getName();
        if (n.endsWith("SeekBar") || n.endsWith("ProgressBar")) {
            return v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findSeekBarRec(g.getChildAt(i), depth - 1);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    /**
     * 判断手指落点是否在「真实进度条」的屏幕矩形内（上下各放宽 12dp 便于抓取）。
     * 只排除进度条本身那一小块，封面/标题/按钮/空白等其它区域一律不视为进度条。
     */
    private static boolean isPointOnSeekBar(MotionEvent ev) {
        if (ev == null) {
            return false;
        }
        View root = mediaCardRoot;
        if (root == null) {
            return false;
        }
        View sb = findSeekBar(root);
        if (sb == null || sb.getWidth() <= 0 || sb.getHeight() <= 0) {
            return false;
        }
        int[] loc = new int[2];
        sb.getLocationOnScreen(loc);
        float pad = 12f * sb.getResources().getDisplayMetrics().density;
        float x = ev.getRawX();
        float y = ev.getRawY();
        return x >= loc[0] - pad && x <= loc[0] + sb.getWidth() + pad
                && y >= loc[1] - pad && y <= loc[1] + sb.getHeight() + pad;
    }

    /**
     * 挂 {@code View.dispatchTouchEvent} 用命中测试处理整张媒体卡的手势。
     * 卡片内任意子 View 上的触摸都会作为 self 经过本钩子；命中卡内后，
     * 本次手势的 MOVE/UP 均按横向滑动处理（滑到子 View 也不丢手势）。
     */
    private void hookViewDispatchTouch() {
        try {
            Method dte = View.class.getDeclaredMethod("dispatchTouchEvent", MotionEvent.class);
            hook(dte)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept((XposedInterface.Chain chain) -> {
                        View self = (View) chain.getThisObject();
                        if (self == null) {
                            return chain.proceed();
                        }
                        // 横滑开关关闭：直接放行（volatile 读，开销可忽略）
                        if (!vCardSwipe) {
                            return chain.proceed();
                        }
                        MotionEvent ev = (MotionEvent) chain.getArgs().get(0);
                        int action = ev.getActionMasked();
                        try {
                                if (action == MotionEvent.ACTION_DOWN) {
                                // 进度条区域不触发切换手势，保留原生拖动调进度。
                                // 关键（v1.27）：必须按「屏幕坐标是否落在真实进度条矩形内」判断，
                                // 而不能按 view 子树判断——否则卡片根容器（含进度条后代）会被整体
                                // 误判成进度条，导致封面/标题/空白等不可点击区域全部无法切卡
                                // （只有按钮这种叶子可点击 view 能滑，正是这个 bug 的表现）。
                                if (isPointOnSeekBar(ev)) {
                                    gestureOnCard = false;
                                    gestureOnSeek = true;
                                    return chain.proceed();
                                }
                                gestureOnSeek = false;
                                // 命中测试：以整卡根容器的屏幕边界判定手指是否落在卡内
                                // （覆盖封面/标题/进度条/按钮全部区域，对兄弟子树布局同样稳健）。
                                gestureOnCard = isInsideCardGeometry(self, ev);
                                if (gestureOnCard) {
                                    gestureDownX = ev.getRawX();
                                    gestureDownY = ev.getRawY();
                                    gestureDecided = false;
                                    gestureHorizontal = false;
                                    gestureSwitched = false;
                                    log(Log.INFO, TAG, "[switcher] gesture down on card (geom)");
                                }
                                return chain.proceed();
                            }
                            if (!gestureOnCard) {
                                return chain.proceed();
                            }
                            switch (action) {
                                case MotionEvent.ACTION_MOVE: {
                                    float dx = ev.getRawX() - gestureDownX;
                                    float dy = ev.getRawY() - gestureDownY;
                                    float density =
                                            self.getResources().getDisplayMetrics().density;
                                    float slop = 6f * density;
                                    if (!gestureDecided) {
                                        if (Math.max(Math.abs(dx), Math.abs(dy)) < slop) break;
                                        gestureDecided = true;
                                        gestureHorizontal = Math.abs(dx) > Math.abs(dy) * 1.2f;
                                    }
                                    if (gestureHorizontal) {
                                        // 阻止通知行的侧滑清除/父容器抢走这次手势：
                                        // 对整卡根容器的父节点 disallow（参考 HyperLyric 的 headerParent 思路），
                                        // 比只对 self 的父节点更可靠。
                                        ViewParent blockParent = (mediaCardRoot != null
                                                && mediaCardRoot.getParent() != null)
                                                ? mediaCardRoot.getParent()
                                                : (self.getParent() != null
                                                        ? self.getParent() : null);
                                        if (blockParent != null) {
                                            blockParent.requestDisallowInterceptTouchEvent(true);
                                        }
                                        // 滑到阈值立刻切换（更跟手），每次手势只切一次
                                        if (!gestureSwitched) {
                                            float threshold = Math.max(
                                                    self.getWidth() * 0.08f, 24f * density);
                                            if (Math.abs(dx) > threshold) {
                                                switchSession(dx < 0 ? 1 : -1,
                                                        cardViewController, cardBindMethod);
                                                gestureSwitched = true;
                                            }
                                        }
                                        return Boolean.TRUE; // 消费掉，后续事件继续由我们处理
                                    }
                                    break;
                                }

                                case MotionEvent.ACTION_UP:
                                case MotionEvent.ACTION_CANCEL: {
                                    if (gestureHorizontal && !gestureOnSeek) {
                                        // 关键：滑动后必须消费掉 UP，
                                        // 否则事件被卡片当成「点击」→ 打开下一个媒体卡片的 App
                                        // 同时释放对父容器的 disallow，让后续手势恢复正常
                                        if (mediaCardRoot != null
                                                && mediaCardRoot.getParent() != null) {
                                            mediaCardRoot.getParent()
                                                    .requestDisallowInterceptTouchEvent(false);
                                        }
                                        gestureDecided = false;
                                        gestureHorizontal = false;
                                        gestureSwitched = false;
                                        gestureOnSeek = false;
                                        return Boolean.TRUE;
                                    }
                                    gestureDecided = false;
                                    gestureHorizontal = false;
                                    gestureSwitched = false;
                                    gestureOnSeek = false;
                                    break;
                                }

                                default:
                                    return Boolean.TRUE;
                            }
                        } catch (Throwable t) {
                            log(Log.WARN, TAG, "[switcher] gesture error: " + t);
                        }
                        return chain.proceed();
                    });
            log(Log.INFO, TAG, "hooked View.dispatchTouchEvent (media card swipe)");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook dispatchTouchEvent failed: " + t);
        }
    }

    /** 判断该通知行是否属于需要隐藏的媒体通知。 */
    private static boolean isMediaRow(Object row, Method getEntry, Method isMedia) {
        if (row == null) {
            return false;
        }
        try {
            Object entry = getEntry.invoke(row);
            if (entry == null) {
                return false;
            }
            Field f = findField(entry.getClass(), "mSbn");
            if (f == null) {
                return false;
            }
            f.setAccessible(true);
            Object sbn = f.get(entry);
            return sbn != null
                    && Boolean.TRUE.equals(isMedia.invoke(null, sbn))
                    // 系统媒体卡隐藏期间始终显示 App 自己的通知（否则下拉栏会没有媒体卡）
                    && vHideRaw && !vHideMiuiCard
                    && shouldBlock(sbn);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 是否应拦掉该媒体通知的 MediaData。
     * 与「不上岛」的过滤策略保持一致：默认拦所有媒体；「仅对列表内应用生效」开启时只拦列表内应用。
     */
    private static boolean shouldBlock(Object sbn) {
        if (!vOnlyListed) {
            return true;
        }
        try {
            Method m = sbn.getClass().getMethod("getPackageName");
            Object pkg = m.invoke(sbn);
            return pkg instanceof String && vBlockedPkgs.contains((String) pkg);
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** 在类及其父类中查找字段（含父类）。 */
    private static Field findField(Class<?> cls, String name) {
        Class<?> c = cls;
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    /** 通过反射读取 MediaData 的 packageName（先试 getter，再试字段）。 */
    private static String readPackageName(Object mediaData) {
        if (mediaData == null) {
            return null;
        }
        try {
            Method m = mediaData.getClass().getMethod("getPackageName");
            Object r = m.invoke(mediaData);
            return r instanceof String ? (String) r : null;
        } catch (Throwable ignored) {
            try {
                Field f = mediaData.getClass().getDeclaredField("packageName");
                f.setAccessible(true);
                Object r = f.get(mediaData);
                return r instanceof String ? (String) r : null;
            } catch (Throwable ignored2) {
                return null;
            }
        }
    }

    /**
     * 尽力从任意回调参数里读包名：先按 MediaData 读，再尝试经
     * NotificationEntry（getSbn()/mSbn 字段）转 StatusBarNotification 读。
     */
    private static String readAnyPackageName(Object o) {
        if (o == null) {
            return null;
        }
        String p = readPackageName(o);
        if (p != null) {
            return p;
        }
        try {
            Object sbn = null;
            try {
                Method m = o.getClass().getMethod("getSbn");
                sbn = m.invoke(o);
            } catch (Throwable ignored) {
            }
            if (sbn == null) {
                Field f = findField(o.getClass(), "mSbn");
                if (f != null) {
                    f.setAccessible(true);
                    sbn = f.get(o);
                }
            }
            if (sbn != null) {
                p = readPackageName(sbn);
            }
        } catch (Throwable ignored) {
        }
        return p;
    }
}

package com.example.noislandmedia;

import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

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

    // DynamicIslandContent 接口（真正把任意内容画到岛上的方法所在）
    private static final String ISLAND_IFACE =
            "com.android.systemui.plugins.miui.dynamicisland.DynamicIslandContent";
    private static final String ISLAND_DATA =
            "com.android.systemui.plugins.miui.dynamicisland.DynamicIslandData";

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

    // ===== 过滤策略 =====
    // 默认 false：把所有媒体都挡在「超级岛」之外（音乐 App 自己的通知仍在通知栏）。
    // 若只想排除特定几个音乐 App，请把 ONLY_BLOCK_LISTED 改为 true，并在 BLOCKED_PACKAGES 填入包名。
    private static final boolean ONLY_BLOCK_LISTED = false;
    private static final Set<String> BLOCKED_PACKAGES = Set.of(
            "com.netease.cloudmusic",   // 网易云音乐
            "com.tencent.qqmusic"       // QQ 音乐
    );

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!SYSTEMUI.equals(param.getPackageName())) {
            return;
        }

        ClassLoader cl = param.getClassLoader();
        try {
            Class<?> target = Class.forName(TARGET_CLASS, false, cl);
            Class<?>[] pts = new Class<?>[PARAM_TYPES.length];
            for (int i = 0; i < PARAM_TYPES.length; i++) {
                pts[i] = Class.forName(PARAM_TYPES[i], false, cl);
            }
            Method hooked = target.getDeclaredMethod(METHOD, pts);

            // (1) 入口级 hook：直接拦截 addDynamicIslandView(3 参)
            hook(hooked)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept((XposedInterface.Chain chain) -> {
                        if (ONLY_BLOCK_LISTED) {
                            Object mediaData = chain.getArgs().get(2);
                            String pkg = readPackageName(mediaData);
                            if (pkg == null || !BLOCKED_PACKAGES.contains(pkg)) {
                                return chain.proceed();
                            }
                        }
                        log(Log.INFO, TAG, "[3arg] media blocked from DynamicIsland");
                        return null;
                    });

            log(Log.INFO, TAG, "hooked " + TARGET_CLASS + "." + METHOD);

            // (2) 落岛接口级 hook：add / update 都拦，只要调用方是媒体控制器
            Class<?> iface = Class.forName(ISLAND_IFACE, false, cl);
            Class<?> did = Class.forName(ISLAND_DATA, false, cl);
            for (String mName : new String[]{"addDynamicIslandView", "updateDynamicIslandView"}) {
                try {
                    Method im = iface.getMethod(mName, did, boolean.class);
                    hook(im)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept((XposedInterface.Chain chain) -> {
                                if (isMediaCall()) {
                                    log(Log.INFO, TAG, "[iface." + mName + "] media blocked");
                                    return null;
                                }
                                return chain.proceed();
                            });
                    log(Log.INFO, TAG, "hooked iface " + ISLAND_IFACE + "." + mName);
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "hook iface " + mName + " failed: " + t);
                }
            }

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
            // 落岛接口方法也尝试去优化（部分 ART 会内联实现体）
            try {
                Method imAdd = iface.getMethod("addDynamicIslandView", did, boolean.class);
                log(Log.INFO, TAG, "deoptimize iface.add -> " + deoptimize(imAdd));
            } catch (Throwable ignored) {}
            try {
                Method imUpd = iface.getMethod("updateDynamicIslandView", did, boolean.class);
                log(Log.INFO, TAG, "deoptimize iface.update -> " + deoptimize(imUpd));
            } catch (Throwable ignored) {}

            // (3) 状态栏音乐图标：让媒体通知不被从状态栏图标列表抽走
            hookStatusBarMediaIcon(cl);

        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hook failed: " + t);
        }

        // (4) 从数据入口断掉「媒体上岛」（双保险，确保岛不出现）
        hookBlockIslandDataIntake(cl);

        // (5) 隐藏 App 裸通知那一行（行仍渲染→状态栏图标保留）
        hookHideRawMediaRow(cl);

        // (6) 多媒体卡片横滑切换：在 MIUI 媒体卡上左右滑动切换不同音乐 App 的会话
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
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook hide raw media row failed: " + t);
        }
    }

    /**
     * 停用 3.0.3 的 MIUI 媒体卡 {@code MiuiMediaNotificationController}
     * （它用 {@code miui_media_session.xml}，2.0.212 无此布局），
     * 让媒体改由标准 {@code MediaCarouselController} 的
     * {@code media_session_view.xml} 呈现，即 2.0.212 的标准卡。
     *
     * <p>拦它的两个数据入口（void 方法不 proceed 即阻断）：
     * <ul>
     *   <li>{@code MiuiMediaNotificationController$mediaDataListener$1.onMediaDataLoaded}</li>
     *   <li>{@code MiuiMediaNotificationController$MediaDataChangeListener.onMediaDataChanged}</li>
     * </ul>
     */
    private void hookDisableMiuiMediaCard(ClassLoader cl) {
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
                hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept((XposedInterface.Chain chain) -> {
                            log(Log.INFO, TAG, "[miuicard] block " + spec[1] + " -> use standard card");
                            return null; // void 方法：不 proceed 即阻断
                        });
                log(Log.INFO, TAG, "hooked " + spec[0].substring(spec[0].lastIndexOf('.') + 1)
                        + "." + spec[1] + " (MIUI card disabled)");
                log(Log.INFO, TAG, "deoptimize " + spec[1] + " -> " + deoptimize(m));
            } catch (Throwable t) {
                log(Log.WARN, TAG, "disable MIUI media card failed: " + spec[1] + " (" + t + ")");
            }
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
     * 强制标准轮播（{@code MediaCarouselController}）的宿主可见。
     *
     * <p>前面已确认：3.0.3 里 QS 媒体宿主、{@code MediaHierarchyManager}、
     * {@code MediaCarouselController} 及其 MediaData 监听都完整存在且在收数据，
     * 且排除「岛占用」这一原因（v1.9 已验证）。所以最后只剩宿主被判定为不可见。
     *
     * <p>这里把 {@code MediaHostStateHolder.getVisible()} 与 {@code MediaHost.getVisible()}
     * 都强制返回 true，让 {@code updateViewVisibility()} 把轮播视图显示出来。
     */
    private void hookForceCarouselVisible(ClassLoader cl) {
        String[][] specs = {
                {"com.android.systemui.media.controls.ui.view.MediaHost$MediaHostStateHolder",
                        "getVisible"},
                {"com.android.systemui.media.controls.ui.view.MediaHost", "getVisible"},
        };
        for (String[] spec : specs) {
            try {
                Class<?> c = Class.forName(spec[0], false, cl);
                Method m = c.getDeclaredMethod(spec[1]);
                hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept((XposedInterface.Chain chain) -> {
                            log(Log.INFO, TAG, "[carousel] force " + spec[1] + "=true");
                            return Boolean.TRUE;
                        });
                log(Log.INFO, TAG, "hooked " + spec[0].substring(spec[0].lastIndexOf('.') + 1)
                        + "." + spec[1] + " (forced visible)");
                log(Log.INFO, TAG, "deoptimize " + spec[1] + " -> " + deoptimize(m));
            } catch (Throwable t) {
                log(Log.WARN, TAG, "force visible failed: " + spec[0] + "." + spec[1] + " (" + t + ")");
            }
        }
    }

    /** 捕获到的 MediaHost.hostView（key = MediaHost.location）。 */
    private static final java.util.Map<Integer, android.view.View> HOST_VIEWS =
            new java.util.HashMap<>();

    /**
     * UI 结构改动：把标准轮播的宿主视图挂进媒体挂载容器。
     *
     * <p>v1.10 已证明「强制 getVisible」无效，说明轮播的 {@code hostView} 从来没有被加进视图树。
     * HyperOS 3.0 把媒体挪到岛/MIUI 卡后，这个挂载步骤不再发生。
     *
     * <p>做法两步：
     * <ol>
     *   <li>hook {@code MediaHost.init(int)}，事后从 {@code hostView} 字段抓出
     *       {@code UniqueObjectHostView}（轮播真正的容器视图）。</li>
     *   <li>hook {@code MediaHierarchyManager.updateHostAttachment()}，事后把该视图挂进
     *       {@code MediaHierarchyManager.rootView}（媒体视图的指定挂载容器）。</li>
     * </ol>
     * 全程 try/catch 兜底，任何一步失败都只是「不显示」，不会影响 SystemUI 稳定。
     */
    private void hookCaptureAndAttachHostView(ClassLoader cl) {
        Class<?> hostCls = null;
        try {
            hostCls = Class.forName(
                    "com.android.systemui.media.controls.ui.view.MediaHost", false, cl);
            final Class<?> hc = hostCls;
            Method init = hc.getDeclaredMethod("init", int.class);
            hook(init)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept((XposedInterface.Chain chain) -> {
                        Object result = chain.proceed();
                        try {
                            int loc = (Integer) chain.getArgs().get(0);
                            Field f = hc.getDeclaredField("hostView");
                            f.setAccessible(true);
                            Object hv = f.get(chain.getThisObject());
                            if (hv instanceof android.view.View) {
                                HOST_VIEWS.put(loc, (android.view.View) hv);
                                log(Log.INFO, TAG, "[carousel] captured hostView location=" + loc);
                            }
                        } catch (Throwable t) {
                            log(Log.WARN, TAG, "[carousel] capture failed: " + t);
                        }
                        return result;
                    });
            log(Log.INFO, TAG, "hooked MediaHost.init (capture hostView)");
            log(Log.INFO, TAG, "deoptimize MediaHost.init -> " + deoptimize(init));
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook MediaHost.init failed: " + t);
        }

        try {
            Class<?> mgrCls = Class.forName(
                    "com.android.systemui.media.controls.ui.controller.MediaHierarchyManager",
                    false, cl);
            final Class<?> mc = mgrCls;
            Method uha = mc.getDeclaredMethod("updateHostAttachment");
            hook(uha)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept((XposedInterface.Chain chain) -> {
                        Object result = chain.proceed();
                        try {
                            Field rootF = mc.getDeclaredField("rootView");
                            rootF.setAccessible(true);
                            Object root = rootF.get(chain.getThisObject());
                            if (root instanceof android.view.ViewGroup) {
                                android.view.ViewGroup parent = (android.view.ViewGroup) root;
                                for (android.view.View hv : HOST_VIEWS.values()) {
                                    if (hv.getParent() == null) {
                                        parent.addView(hv,
                                                new android.widget.FrameLayout.LayoutParams(
                                                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
                                        log(Log.INFO, TAG, "[carousel] attached hostView into rootView");
                                    }
                                }
                            } else {
                                log(Log.WARN, TAG, "[carousel] rootView not a ViewGroup: " + root);
                            }
                        } catch (Throwable t) {
                            log(Log.WARN, TAG, "[carousel] attach failed: " + t);
                        }
                        return result;
                    });
            log(Log.INFO, TAG, "hooked MediaHierarchyManager.updateHostAttachment (attach hostView)");
            log(Log.INFO, TAG, "deoptimize updateHostAttachment -> " + deoptimize(uha));
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook updateHostAttachment failed: " + t);
        }
    }

    // ===== 多媒体卡片横滑切换（MIUI 同款：管理多个媒体会话）=====

    /** 当前所有活跃媒体会话：notificationKey -> MediaData（保持插入顺序）。 */
    private static final Map<String, Object> SESSIONS =
            Collections.synchronizedMap(new LinkedHashMap<String, Object>());
    /** 媒体卡视图控制器 -> 其 bindMediaData 方法。 */
    private static final Map<Object, Method> VIEW_CONTROLLER_BIND =
            Collections.synchronizedMap(new java.util.WeakHashMap<Object, Method>());
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

            // 1) 构造函数：抓 MediaDataManager 与 ViewController
            for (Constructor<?> ctor : ctrlCls.getDeclaredConstructors()) {
                hook(ctor)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept((XposedInterface.Chain chain) -> {
                            Object result = chain.proceed();
                            try {
                                Object mdm = null;
                                Object vc = null;
                                for (Object a : chain.getArgs()) {
                                    if (a == null) continue;
                                    if (mdm == null && mdmCls.isInstance(a)) mdm = a;
                                    if (vc == null && vcCls.isInstance(a)) vc = a;
                                }
                                if (mdm != null) {
                                    registerSessionListener(mdm, cl);
                                }
                                if (vc != null) {
                                    VIEW_CONTROLLER_BIND.put(vc, bindMediaData);
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
                                CARD_VIEWS.put((View) player, Boolean.TRUE);
                                cardViewController = vc;
                                cardBindMethod = bindMediaData;
                                log(Log.INFO, TAG, "[switcher] media card registered (total="
                                        + CARD_VIEWS.size() + ")");
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
                    }
                    return null;
                }
                if ("onMediaDataRemoved".equals(name)) {
                    String key = args != null && args.length > 0 ? (String) args[0] : null;
                    if (key != null) {
                        SESSIONS.remove(key);
                        log(Log.INFO, TAG, "[switcher] removed: " + key);
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
     * 挂 {@code View.dispatchTouchEvent} 并用身份判断只处理媒体卡。
     * 非媒体卡的事件直接放行（开销仅一次引用比较）。
     */
    private void hookViewDispatchTouch() {
        try {
            Method dte = View.class.getDeclaredMethod("dispatchTouchEvent", MotionEvent.class);
            hook(dte)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept((XposedInterface.Chain chain) -> {
                        View self = (View) chain.getThisObject();
                        if (self == null || CARD_VIEWS.isEmpty() || !CARD_VIEWS.containsKey(self)) {
                            return chain.proceed();
                        }
                        MotionEvent ev = (MotionEvent) chain.getArgs().get(0);
                        try {
                            switch (ev.getActionMasked()) {
                                case MotionEvent.ACTION_DOWN:
                                    log(Log.INFO, TAG, "[switcher] gesture down on card");
                                    gestureDownX = ev.getRawX();
                                    gestureDownY = ev.getRawY();
                                    gestureDecided = false;
                                    gestureHorizontal = false;
                                    break;

                                case MotionEvent.ACTION_MOVE: {
                                    float dx = ev.getRawX() - gestureDownX;
                                    float dy = ev.getRawY() - gestureDownY;
                                    float slop = 8f * self.getResources().getDisplayMetrics().density;
                                    if (!gestureDecided) {
                                        if (Math.max(Math.abs(dx), Math.abs(dy)) < slop) break;
                                        gestureDecided = true;
                                        gestureHorizontal = Math.abs(dx) > Math.abs(dy) * 1.2f;
                                    }
                                    if (gestureHorizontal) {
                                        // 阻止通知行的侧滑清除/父容器抢走这次手势
                                        if (self.getParent() != null) {
                                            self.getParent().requestDisallowInterceptTouchEvent(true);
                                        }
                                        return Boolean.TRUE; // 消费掉，继续收后续事件
                                    }
                                    break;
                                }

                                case MotionEvent.ACTION_UP: {
                                    if (gestureHorizontal) {
                                        float dx = ev.getRawX() - gestureDownX;
                                        float threshold = Math.max(
                                                self.getWidth() * 0.12f,
                                                40f * self.getResources().getDisplayMetrics().density);
                                        if (Math.abs(dx) > threshold) {
                                            switchSession(dx < 0 ? 1 : -1,
                                                    cardViewController, cardBindMethod);
                                        }
                                    }
                                    gestureDecided = false;
                                    gestureHorizontal = false;
                                    break;
                                }

                                case MotionEvent.ACTION_CANCEL:
                                    gestureDecided = false;
                                    gestureHorizontal = false;
                                    break;

                                default:
                                    break;
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

    /** 媒体卡横滑手势：左右滑动切换媒体会话。（保留备用实现） */
    private static final class MediaCardTouchListener implements View.OnTouchListener {
        private final NoMediaIsland module;
        private final Object viewController;
        private final Method bindMethod;
        private float downX;
        private float downY;
        private boolean decided;
        private boolean horizontal;

        MediaCardTouchListener(NoMediaIsland module, Object viewController, Method bindMethod) {
            this.module = module;
            this.viewController = viewController;
            this.bindMethod = bindMethod;
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX();
                    downY = event.getRawY();
                    decided = false;
                    horizontal = false;
                    break;

                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - downX;
                    float dy = event.getRawY() - downY;
                    float slop = 12f * v.getResources().getDisplayMetrics().density;
                    if (!decided) {
                        if (Math.max(Math.abs(dx), Math.abs(dy)) < slop) break;
                        decided = true;
                        horizontal = Math.abs(dx) > Math.abs(dy) * 1.2f;
                    }
                    if (horizontal) {
                        // 阻止通知行的侧滑清除抢走这次手势
                        if (v.getParent() != null) {
                            v.getParent().requestDisallowInterceptTouchEvent(true);
                        }
                        return true;
                    }
                    break;
                }

                case MotionEvent.ACTION_UP: {
                    if (horizontal) {
                        float dx = event.getRawX() - downX;
                        float threshold = Math.max(
                                v.getWidth() * 0.15f,
                                48f * v.getResources().getDisplayMetrics().density);
                        if (Math.abs(dx) > threshold) {
                            module.switchSession(dx < 0 ? 1 : -1, viewController, bindMethod);
                        }
                    }
                    decided = false;
                    horizontal = false;
                    break;
                }

                case MotionEvent.ACTION_CANCEL:
                    decided = false;
                    horizontal = false;
                    break;

                default:
                    break;
            }
            return false;
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
                    && shouldBlock(sbn);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 是否应拦掉该媒体通知的 MediaData。
     * 与「不上岛」的过滤策略保持一致：默认拦所有媒体；ONLY_BLOCK_LISTED 为 true 时只拦指定音乐 App。
     */
    private static boolean shouldBlock(Object sbn) {
        if (!ONLY_BLOCK_LISTED) {
            return true;
        }
        try {
            Method m = sbn.getClass().getMethod("getPackageName");
            Object pkg = m.invoke(sbn);
            return pkg instanceof String && BLOCKED_PACKAGES.contains((String) pkg);
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

    /**
     * 判断当前调用栈是否来自媒体控制器（即本次上岛属于「媒体」）。
     * 仅当调用方链路中出现 MiuiIslandMediaController 才判为媒体，
     * 设备通知 / 充电 / 焦点超时等其它岛不受影响。
     */
    private static boolean isMediaCall() {
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        for (StackTraceElement e : st) {
            String cn = e.getClassName();
            if (cn != null && cn.contains("MiuiIslandMediaController")) {
                return true;
            }
        }
        return false;
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
}

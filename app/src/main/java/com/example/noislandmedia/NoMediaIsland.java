package com.example.noislandmedia;

import android.util.Log;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
 * 让音乐 App 的状态栏图标正常显示，同时保留下拉栏的媒体卡。
 * 注：该方法同时管下拉栏列表，理论上有极小概率导致下拉栏出现重复媒体卡；若真出现，反馈后我再细化。
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

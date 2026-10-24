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

        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hook failed: " + t);
        }
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

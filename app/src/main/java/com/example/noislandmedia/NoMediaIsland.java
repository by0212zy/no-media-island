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
 * 作用：拦截 HyperOS「超级岛 / 通知岛（DynamicIsland）」把媒体渲染成顶部胶囊的唯一汇聚点
 *       {@code MiuiIslandMediaController.addDynamicIslandView(...)}，让媒体不再上岛，
 *       但音乐 App 自身的 MediaStyle 通知仍正常留在通知栏。
 *
 * <p>关键修复（v1.1）：本机 MiuiSystemUI 的 {@code addDynamicIslandView} 会被其调用方
 * （{@code onMediaDataChanged} / {@code onCastInfoChanged} / {@code reInflateView$1}）在 ART 编译期
 * <b>内联（inline）</b>，导致 hook 回调根本不会被触发（LSPosed 日志虽显示 “hooked” 成功，但方法体不执行）。
 * 因此必须对这三个调用方（以及方法自身）执行 {@link XposedInterface#deoptimize}，
 * 强制调用退回为真实方法调用，hook 才会真正生效。详见 libxposed XposedInterface.deoptimize 文档。
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
     * {@code addDynamicIslandView} 在本机 MiuiSystemUI（classes3.dex）中的全部调用方。
     * 这些方法是内联热点，必须逐个 deoptimize，否则 hook 回调不触发。
     * 每项：[类名, 方法名, 参数类型（可空；多个用 ';' 分隔）]。
     */
    private static final String[][] CALLERS = {
            // 媒体数据变化时把媒体送上岛
            {"com.android.systemui.statusbar.notification.mediaisland.MiuiIslandMediaController$mediaDataChangeListener$1",
             "onMediaDataChanged", "com.android.systemui.media.controls.shared.model.MediaData"},
            // 投屏信息变化时
            {"com.android.systemui.statusbar.notification.mediaisland.MiuiIslandMediaController",
             "onCastInfoChanged", "java.util.Map"},
            // 视图重建（如密度/字体变化）后重新上岛
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
        // 仅对 SystemUI 生效
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

            // 拦截该方法的调用链：不调用 chain.proceed() 即等价于「直接 return」，原逻辑不执行。
            hook(hooked)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept((XposedInterface.Chain chain) -> {
                        if (ONLY_BLOCK_LISTED) {
                            Object mediaData = chain.getArgs().get(2); // 第三个参数即 MediaData
                            String pkg = readPackageName(mediaData);
                            if (pkg == null || !BLOCKED_PACKAGES.contains(pkg)) {
                                return chain.proceed(); // 不在名单内，照常上岛
                            }
                        }
                        // 不上岛：直接拦截，原方法不再执行
                        Log.i(TAG, "media blocked from DynamicIsland");
                        return null;
                    });

            log(Log.INFO, TAG, "hooked " + TARGET_CLASS + "." + METHOD);

            // ===== 关键修复：去优化调用方，避免内联导致 hook 回调不触发 =====
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
                    boolean ok = deoptimize(cm);
                    log(Log.INFO, TAG, "deoptimize caller " + spec[0] + "." + spec[1] + " -> " + ok);
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "deoptimize caller failed: " + spec[1] + " (" + t + ")");
                }
            }

        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hook failed: " + t);
        }
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

package com.example.noislandmedia;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置项 key / 默认值 / 工具方法。
 * 通过 libxposed Remote Preferences 在模块进程与 SystemUI 进程间共享（group 相同即同一份文件）。
 */
public final class Prefs {

    /** remote preferences group，App 与 Hook 侧必须一致 */
    public static final String GROUP = "noisland_settings";

    // ---- keys ----
    public static final String KEY_BLOCK_ISLAND = "block_island";   // 阻止媒体上超级岛
    public static final String KEY_STATUS_ICON  = "status_icon";    // 音乐 App 显示状态栏图标
    public static final String KEY_CARD_SWIPE   = "card_swipe";     // 媒体卡横滑切换
    public static final String KEY_INDICATOR    = "indicator";      // 显示页码指示点
    public static final String KEY_HIDE_RAW     = "hide_raw";       // 隐藏原始媒体通知行
    public static final String KEY_ONLY_LISTED  = "only_listed";    // 仅对列表内应用生效
    public static final String KEY_PACKAGES     = "packages";       // 生效应用列表

    // ---- 默认值 ----
    public static final boolean DEF_BLOCK_ISLAND = true;
    public static final boolean DEF_STATUS_ICON  = true;
    public static final boolean DEF_CARD_SWIPE   = true;
    public static final boolean DEF_INDICATOR    = true;
    public static final boolean DEF_HIDE_RAW     = true;
    public static final boolean DEF_ONLY_LISTED  = false;
    public static final String  DEF_PACKAGES     = "com.netease.cloudmusic\ncom.tencent.qqmusic";

    private Prefs() {}

    /** 解析生效应用列表：逗号/空白/换行分隔，去空行 */
    public static List<String> parsePackages(String raw) {
        List<String> out = new ArrayList<>();
        if (TextUtils.isEmpty(raw)) return out;
        for (String p : raw.split("[,\\s]+")) {
            if (!TextUtils.isEmpty(p)) out.add(p);
        }
        return out;
    }
}

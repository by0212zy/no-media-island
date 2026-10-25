package com.example.noislandmedia;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

/**
 * 标准设置页（androidx.preference）。
 * 所有开关直接写入传入的 SharedPreferences（remote 优先，本地兜底）。
 *
 * <p>关键（v2.0 修复）：androidx Preference 默认只把勾选持久化到
 * {@code PreferenceManager.getDefaultSharedPreferences()}（App 本地），
 * 而本界面展示的是 remote preferences——不显式写回就会出现
 * 「改了但重开还是默认值」。因此对每个 Preference 都挂
 * {@code setOnPreferenceChangeListener}，把新值写入当前绑定的 prefs。
 */
public class SettingsFragment extends PreferenceFragmentCompat
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    private SharedPreferences prefs;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.prefs_settings, rootKey);
        setupWriteListeners();
    }

    /** 绑定一份 SharedPreferences（本地或 remote），刷新 UI */
    public void bind(SharedPreferences p) {
        if (p == null) return;
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(this);
        prefs = p;
        prefs.registerOnSharedPreferenceChangeListener(this);
        refreshUi();
    }

    /**
     * 勾选/输入即时写入当前绑定的 prefs（remote 优先）。
     * 返回 true 让 androidx 同步自己的状态并写一份本地默认 prefs 作兜底，
     * 模块未激活时本地值仍在，激活后 migration 会补写到 remote。
     */
    private void setupWriteListeners() {
        String[] boolKeys = {
                Prefs.KEY_BLOCK_ISLAND, Prefs.KEY_STATUS_ICON, Prefs.KEY_CARD_SWIPE,
                Prefs.KEY_INDICATOR, Prefs.KEY_HIDE_RAW, Prefs.KEY_HIDE_MIUICARD,
                Prefs.KEY_ONLY_LISTED,
        };
        for (String key : boolKeys) {
            SwitchPreferenceCompat sp = findPreference(key);
            if (sp == null) continue;
            sp.setOnPreferenceChangeListener((preference, newValue) -> {
                boolean v = Boolean.TRUE.equals(newValue);
                SharedPreferences p = prefs;
                if (p != null) {
                    p.edit().putBoolean(preference.getKey(), v).apply();
                }
                return true;
            });
        }
        EditTextPreference pkg = findPreference(Prefs.KEY_PACKAGES);
        if (pkg != null) {
            pkg.setOnPreferenceChangeListener((preference, newValue) -> {
                String v = newValue == null ? "" : (String) newValue;
                SharedPreferences p = prefs;
                if (p != null) {
                    p.edit().putString(preference.getKey(), v).apply();
                }
                return true;
            });
        }
    }

    private void refreshUi() {
        if (prefs == null) return;

        switchPref(Prefs.KEY_BLOCK_ISLAND, Prefs.DEF_BLOCK_ISLAND);
        switchPref(Prefs.KEY_STATUS_ICON, Prefs.DEF_STATUS_ICON);
        switchPref(Prefs.KEY_CARD_SWIPE, Prefs.DEF_CARD_SWIPE);
        switchPref(Prefs.KEY_INDICATOR, Prefs.DEF_INDICATOR);
        switchPref(Prefs.KEY_HIDE_RAW, Prefs.DEF_HIDE_RAW);
        switchPref(Prefs.KEY_HIDE_MIUICARD, Prefs.DEF_HIDE_MIUICARD);
        switchPref(Prefs.KEY_ONLY_LISTED, Prefs.DEF_ONLY_LISTED);

        EditTextPreference pkg = findPreference(Prefs.KEY_PACKAGES);
        if (pkg != null) {
            String cur = prefs.getString(Prefs.KEY_PACKAGES, Prefs.DEF_PACKAGES);
            pkg.setText(cur);
            pkg.setSummary(TextUtils.isEmpty(cur) ? getString(R.string.packages_summary) : cur);
        }
    }

    private void switchPref(String key, boolean def) {
        SwitchPreferenceCompat sp = findPreference(key);
        if (sp != null) {
            boolean v = prefs.getBoolean(key, def);
            sp.setChecked(v);
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        // remote 变化（含本界面写入触发的回执）时同步刷新
        refreshUi();
    }
}

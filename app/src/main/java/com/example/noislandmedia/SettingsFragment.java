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
 */
public class SettingsFragment extends PreferenceFragmentCompat
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    private SharedPreferences prefs;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.prefs_settings, rootKey);
    }

    /** 绑定一份 SharedPreferences（本地或 remote），刷新 UI */
    public void bind(SharedPreferences p) {
        if (p == null) return;
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(this);
        prefs = p;
        prefs.registerOnSharedPreferenceChangeListener(this);
        refreshUi();
    }

    private void refreshUi() {
        if (prefs == null) return;

        switchPref(Prefs.KEY_BLOCK_ISLAND, Prefs.DEF_BLOCK_ISLAND);
        switchPref(Prefs.KEY_STATUS_ICON, Prefs.DEF_STATUS_ICON);
        switchPref(Prefs.KEY_CARD_SWIPE, Prefs.DEF_CARD_SWIPE);
        switchPref(Prefs.KEY_INDICATOR, Prefs.DEF_INDICATOR);
        switchPref(Prefs.KEY_HIDE_RAW, Prefs.DEF_HIDE_RAW);
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
        // remote 变化（极少来自本机双开）时同步刷新
        refreshUi();
    }
}

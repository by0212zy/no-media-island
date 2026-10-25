package com.example.noislandmedia;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;
import io.github.libxposed.service.XposedServiceHelper.OnServiceListener;

/**
 * 设置入口：承载 SettingsFragment。
 * 本地 SharedPreferences 作为兜底，模块激活后优先绑定 remote preferences（跨进程共享）。
 */
public class SettingsActivity extends AppCompatActivity {

    private SettingsFragment fragment;
    private boolean serviceBound = false;

    private final OnServiceListener serviceListener = new OnServiceListener() {
        @Override
        public void onServiceBind(XposedService service) {
            SharedPreferences remote = service.getRemotePreferences(Prefs.GROUP);
            if (remote == null) return;
            // 首次：把本地已有值迁移到 remote，保证与旧版本兼容
            migrateLocalToRemote(remote);
            serviceBound = true;
            runOnUiThread(() -> {
                if (fragment != null) fragment.bind(remote);
            });
        }

        @Override
        public void onServiceDied(XposedService service) {
            serviceBound = false;
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        setTitle(R.string.settings_title);

        if (savedInstanceState == null) {
            fragment = new SettingsFragment();
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.settings_container, fragment)
                    .commit();
        } else {
            fragment = (SettingsFragment) getSupportFragmentManager()
                    .findFragmentById(R.id.settings_container);
        }

        // 先用本地默认值渲染，等 service 绑定后再切到 remote
        if (fragment != null) {
            fragment.bind(PreferenceManager.getDefaultSharedPreferences(this));
        }

        // 未激活时 remote 不可用，保留本地兜底（会提示用户）
        XposedServiceHelper.registerListener(serviceListener);
        if (!serviceBound) {
            runOnUiThreadDelayed();
        }
    }

    private void runOnUiThreadDelayed() {
        // 延迟检查：若 service 始终未绑定，提示模块未激活（仅一次）
        getWindow().getDecorView().postDelayed(() -> {
            if (!serviceBound && !isFinishing()) {
                Toast.makeText(this, R.string.hint_module_inactive, Toast.LENGTH_LONG).show();
            }
        }, 1200);
    }

    /** 本地有值而 remote 没有时，把本地值搬过去 */
    private void migrateLocalToRemote(SharedPreferences remote) {
        SharedPreferences local = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor ed = null;
        for (String key : new String[]{
                Prefs.KEY_BLOCK_ISLAND, Prefs.KEY_STATUS_ICON, Prefs.KEY_CARD_SWIPE,
                Prefs.KEY_INDICATOR, Prefs.KEY_HIDE_RAW, Prefs.KEY_ONLY_LISTED}) {
            if (local.contains(key) && !remote.contains(key)) {
                if (ed == null) ed = remote.edit();
                ed.putBoolean(key, local.getBoolean(key, false));
            }
        }
        if (local.contains(Prefs.KEY_PACKAGES) && !remote.contains(Prefs.KEY_PACKAGES)) {
            if (ed == null) ed = remote.edit();
            ed.putString(Prefs.KEY_PACKAGES, local.getString(Prefs.KEY_PACKAGES, Prefs.DEF_PACKAGES));
        }
        if (ed != null) ed.apply();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // XposedServiceHelper 仅提供 registerListener，服务断开由 onServiceDied 感知
    }
}

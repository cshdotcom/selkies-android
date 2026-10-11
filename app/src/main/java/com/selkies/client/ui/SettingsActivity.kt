package com.selkies.client.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat
import com.selkies.client.R
import com.selkies.client.data.ServerRepository

/**
 * 设置页宿主。
 */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, PrefsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    /** 偏好项片段：写 SharedPrefeferences（key 与 SettingsStore 一致） */
    class PrefsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs_settings, rootKey)

            // 清除所有已保存密码
            findPreference<androidx.preference.Preference>("clear_passwords")?.setOnPreferenceClickListener {
                val repo = ServerRepository(requireContext())
                val all = repo.list()
                all.forEach { it.username = null; it.password = null }
                repo.save(all)
                android.widget.Toast.makeText(
                    requireContext(), R.string.pref_cleared, android.widget.Toast.LENGTH_SHORT
                ).show()
                true
            }

            // 关于页（含版本号）
            findPreference<androidx.preference.Preference>("about_app")?.apply {
                summary = getString(
                    R.string.pref_about_app,
                    com.selkies.client.BuildConfig.VERSION_NAME
                )
                setOnPreferenceClickListener {
                    // 附带最近崩溃日志入口（诊断闪退用）
                    val crash = (activity?.application as? com.selkies.client.SelkiesApp)?.latestCrashLog()
                    val msg = summary.toString() +
                            if (crash.isNullOrBlank()) "\n\n暂无崩溃记录"
                            else "\n\n—— 最近崩溃日志 ——\n${crash.take(2000)}"
                    android.app.AlertDialog.Builder(requireContext())
                        .setTitle(R.string.about_title)
                        .setMessage(msg)
                        .setPositiveButton(R.string.close, null)
                        .show()
                    true
                }
            }
        }
    }
}

package com.selkies.client.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.selkies.client.R
import com.selkies.client.data.ServerRepository
import com.selkies.client.data.SettingsStore
import com.selkies.client.databinding.ActivitySettingsBinding

/**
 * v1.3.0 设置页（自定义 UI 全面重写）：
 *
 * - 彻底替换 PreferenceFragment —— 修复「开关无法点击」问题；
 * - 新增 深色/浅色/跟随系统 主题切换（即时生效）；
 * - 新增 服务器列表导出/导入（与列表页共用逻辑）；
 * - 所有开关 = MaterialSwitch，所有多选 = ToggleGroup，所见即所得。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: SettingsStore
    private lateinit var repo: ServerRepository

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri?.let { ServerIo.exportServers(this, repo, it, includeCredsPending) }
        }
    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                val n = ServerIo.importServers(this, repo, it)
                Toast.makeText(
                    this,
                    if (n >= 0) getString(R.string.import_done, n) else getString(R.string.import_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    /** 导出时是否包含凭据（弹询问框后暂存） */
    private var includeCredsPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = SettingsStore(this)
        repo = ServerRepository(this)

        binding.toolbar.setNavigationOnClickListener { finish() }
        setupAppearance()
        setupQuality()
        setupGestures()
        setupClipboard()
        setupConnection()
        setupData()
        setupAbout()
    }

    // ------------------------------------------------------------------
    // 外观（主题）
    // ------------------------------------------------------------------

    private fun setupAppearance() {
        val tg = binding.tgTheme
        val map = mapOf(
            "system" to binding.btnThemeSystem, "light" to binding.btnThemeLight,
            "dark" to binding.btnThemeDark
        )
        map[settings.themeMode]?.isChecked = true
        tg.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = map.entries.firstOrNull { it.value.id == checkedId }?.key ?: "system"
            settings.themeMode = mode
            AppCompatDelegate.setDefaultNightMode(
                when (mode) {
                    "light" -> AppCompatDelegate.MODE_NIGHT_NO
                    "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
        }
    }

    // ------------------------------------------------------------------
    // 画质与性能
    // ------------------------------------------------------------------

    private fun setupQuality() {
        // CRF 滑杆
        binding.seekCrf.progress = settings.videoCrf
        binding.txtCrf.text = settings.videoCrf.toString()
        binding.seekCrf.setOnSeekBarChangeListener(object :
            android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                binding.txtCrf.text = p.toString()
                if (fromUser) settings.videoCrf = p
            }

            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })

        // 码率
        fun bitrateLabel() = getString(R.string.pref_video_bitrate) + "：" + settings.videoBitrate + " kbps"
        binding.btnBitrate.text = bitrateLabel()
        binding.btnBitrate.setOnClickListener { numberDialog(getString(R.string.pref_video_bitrate), settings.videoBitrate) { v ->
            settings.videoBitrate = v; binding.btnBitrate.text = bitrateLabel()
        } }

        // 帧率
        val fpsMap = mapOf(15 to binding.btnFps15, 30 to binding.btnFps30, 60 to binding.btnFps60)
        fpsMap[settings.framerate]?.isChecked = true
        binding.tgFramerate.addOnButtonCheckedListener { _, id, checked ->
            if (checked) fpsMap.entries.firstOrNull { it.value.id == id }?.key?.let { settings.framerate = it }
        }

        // 码率控制
        (if (settings.rateControl == "crf") binding.btnRateCrf else binding.btnRateCbr).isChecked = true
        binding.tgRate.addOnButtonCheckedListener { _, id, checked ->
            if (checked) settings.rateControl = if (id == R.id.btn_rate_crf) "crf" else "cbr"
        }

        // 自动音频
        binding.switchAutoAudio.isChecked = settings.autoAudio
        binding.switchAutoAudio.setOnCheckedChangeListener { _, c -> settings.autoAudio = c }

        // 音频码率
        fun audioBrLabel() = getString(R.string.pref_audio_bitrate) + "：" + settings.audioBitrate + " bps"
        binding.btnAudioBitrate.text = audioBrLabel()
        binding.btnAudioBitrate.setOnClickListener { numberDialog(getString(R.string.pref_audio_bitrate), settings.audioBitrate) { v ->
            settings.audioBitrate = v; binding.btnAudioBitrate.text = audioBrLabel()
        } }
    }

    // ------------------------------------------------------------------
    // 手势与触控
    // ------------------------------------------------------------------

    private fun setupGestures() {
        // 触摸模式
        (if (settings.touchMode == "direct") binding.btnTouchDirect else binding.btnTouchTrackpad).isChecked = true
        binding.tgTouchMode.addOnButtonCheckedListener { _, id, checked ->
            if (checked) settings.touchMode = if (id == R.id.btn_touch_direct) "direct" else "trackpad"
        }

        // 双指轻点
        val twoFingerBtn = when (settings.twoFingerTap) {
            "middle" -> binding.btn2fMiddle; "scroll" -> binding.btn2fScroll; else -> binding.btn2fRight
        }
        twoFingerBtn.isChecked = true
        binding.tgTwoFinger.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            settings.twoFingerTap = when (id) {
                R.id.btn_2f_middle -> "middle"; R.id.btn_2f_scroll -> "scroll"; else -> "right"
            }
        }

        // 长按
        val lpBtn = when (settings.longPress) {
            "middle" -> binding.btnLpMiddle; "drag" -> binding.btnLpDrag; else -> binding.btnLpRight
        }
        lpBtn.isChecked = true
        binding.tgLongPress.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            settings.longPress = when (id) {
                R.id.btn_lp_middle -> "middle"; R.id.btn_lp_drag -> "drag"; else -> "right"
            }
        }

        // 捏合缩放 / 跟随旋转
        binding.switchPinch.isChecked = settings.pinchZoom
        binding.switchPinch.setOnCheckedChangeListener { _, c -> settings.pinchZoom = c }
        binding.switchRotation.isChecked = settings.followRotation
        binding.switchRotation.setOnCheckedChangeListener { _, c -> settings.followRotation = c }
    }

    // ------------------------------------------------------------------
    // 剪贴板
    // ------------------------------------------------------------------

    private fun setupClipboard() {
        fun clipLabel(): String {
            val mode = settings.clipboardMode
            return getString(R.string.pref_clipboard_mode) + "：" + when (mode) {
                "recv" -> "仅接收"; "send" -> "仅发送"; "off" -> "关闭"; else -> "双向"
            }
        }
        binding.btnClipboardMode.text = clipLabel()
        binding.btnClipboardMode.setOnClickListener {
            val items = arrayOf("双向", "仅接收", "仅发送", "关闭")
            val vals = arrayOf("both", "recv", "send", "off")
            val cur = vals.indexOf(settings.clipboardMode).coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle(R.string.pref_clipboard_mode)
                .setSingleChoiceItems(items, cur) { d, which ->
                    settings.clipboardMode = vals[which]
                    binding.btnClipboardMode.text = clipLabel()
                    d.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        binding.switchClipBinary.isChecked = settings.clipboardBinary
        binding.switchClipBinary.setOnCheckedChangeListener { _, c -> settings.clipboardBinary = c }
    }

    // ------------------------------------------------------------------
    // 连接
    // ------------------------------------------------------------------

    private fun setupConnection() {
        binding.switchReconnect.isChecked = settings.autoReconnect
        binding.switchReconnect.setOnCheckedChangeListener { _, c -> settings.autoReconnect = c }

        fun maxLabel() = "最大重连次数：" + if (settings.maxReconnects == 0) "无限" else settings.maxReconnects
        binding.btnMaxReconnects.text = maxLabel()
        binding.btnMaxReconnects.setOnClickListener {
            numberDialog("最大重连次数（0=无限）", settings.maxReconnects) { v ->
                settings.maxReconnects = v; binding.btnMaxReconnects.text = maxLabel()
            }
        }
    }

    // ------------------------------------------------------------------
    // 数据（导出 / 导入 / 清密码）
    // ------------------------------------------------------------------

    private fun setupData() {
        binding.btnExport.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.export_servers)
                .setMessage(R.string.export_include_creds)
                .setPositiveButton(R.string.confirm) { _, _ ->
                    includeCredsPending = true
                    exportLauncher.launch("selkies-servers.json")
                }
                .setNegativeButton(R.string.cancel) { _, _ ->
                    includeCredsPending = false
                    exportLauncher.launch("selkies-servers.json")
                }
                .setNeutralButton(R.string.close, null)
                .show()
        }
        binding.btnImport.setOnClickListener { importLauncher.launch(arrayOf("application/json")) }
        binding.btnClearPasswords.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.pref_clear_passwords)
                .setMessage(R.string.pref_clear_passwords_desc)
                .setPositiveButton(R.string.delete) { _, _ ->
                    val all = repo.list()
                    all.forEach { it.username = null; it.password = null }
                    repo.save(all)
                    Toast.makeText(this, R.string.pref_cleared, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun setupAbout() {
        binding.txtAbout.text = getString(R.string.pref_about_app, com.selkies.client.BuildConfig.VERSION_NAME)
        binding.btnCrashLog.setOnClickListener {
            val crash = (application as? com.selkies.client.SelkiesApp)?.latestCrashLog()
            AlertDialog.Builder(this)
                .setTitle(R.string.about_title)
                .setMessage(if (crash.isNullOrBlank()) "暂无崩溃记录" else crash.take(4000))
                .setPositiveButton(R.string.close, null)
                .show()
        }
    }

    /** 数字输入对话框 */
    private fun numberDialog(title: String, current: Int, onSave: (Int) -> Unit) {
        val edit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(current.toString())
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(edit)
            .setPositiveButton(R.string.save) { _, _ ->
                edit.text.toString().toIntOrNull()?.let(onSave)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}

/**
 * 服务器列表 JSON 导入/导出（v1.3.0）。
 * 列表页与设置页共用。格式：{"servers":[{...ServerConfig...}]}。
 */
object ServerIo {
    private val gson = com.google.gson.Gson()

    fun exportServers(activity: android.app.Activity, repo: ServerRepository, uri: Uri, includeCreds: Boolean): Boolean {
        return try {
            val list = repo.list().map {
                if (includeCreds) it
                else it.copy(username = null, password = null)
            }
            val json = gson.toJson(mapOf("app" to "selkies-android", "version" to 1, "servers" to list))
            val os = activity.contentResolver.openOutputStream(uri) ?: return false
            os.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            Toast.makeText(activity, activity.getString(R.string.export_done, list.size), Toast.LENGTH_SHORT).show()
            true
        } catch (e: Exception) {
            Toast.makeText(activity, activity.getString(R.string.import_failed), Toast.LENGTH_SHORT).show()
            false
        }
    }

    /** 返回导入合并的条数；-1 = 格式错误 */
    fun importServers(activity: android.app.Activity, repo: ServerRepository, uri: Uri): Int {
        return try {
            val text = activity.contentResolver.openInputStream(uri)?.use { ins ->
                ins.readBytes().toString(Charsets.UTF_8)
            } ?: return -1
            val root = com.google.gson.JsonParser.parseString(text).asJsonObject
            val arr = root.getAsJsonArray("servers") ?: return -1
            val type = object : com.google.gson.reflect.TypeToken<MutableList<com.selkies.client.data.ServerConfig>>() {}.type
            val incoming: MutableList<com.selkies.client.data.ServerConfig> = gson.fromJson(arr, type)
            val existing = repo.list()
            var added = 0
            incoming.forEach { cfg ->
                // 按 host+port+path+username 去重：已存在则跳过，不覆盖
                val dup = existing.any {
                    it.host == cfg.host && it.port == cfg.port &&
                            it.normalizedPath == cfg.normalizedPath && it.username == cfg.username
                }
                if (!dup) {
                    existing.add(if (existing.any { e -> e.id == cfg.id }) cfg.copy(
                        id = java.util.UUID.randomUUID().toString()
                    ) else cfg)
                    added++
                }
            }
            repo.save(existing)
            added
        } catch (e: Exception) {
            -1
        }
    }
}

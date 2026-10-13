package com.cshdotcom.selkies

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.cshdotcom.selkies.model.Prefs
import com.cshdotcom.selkies.model.ServerStore
import java.io.File

/**
 * 设置页：画质（码率/帧率/分辨率匹配）、手势（长按动作/滚动方向/滚动步长/灵敏度）、
 * 行为（音频/剪贴板/自动重连/亮屏）、密钥管理（清除所有已存密码）、关于。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        prefs = Prefs(this)

        // ---------------- 画质 ----------------
        val sbBitrate = findViewById<SeekBar>(R.id.sbBitrate)
        val tvBitrate = findViewById<TextView>(R.id.tvBitrate)
        sbBitrate.max = 40 // 2Mbps..40Mbps
        sbBitrate.progress = (prefs.videoBitrateKbps / 1000).coerceIn(2, 40)
        tvBitrate.text = getString(R.string.fmt_bitrate, prefs.videoBitrateKbps)
        sbBitrate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                tvBitrate.text = getString(R.string.fmt_bitrate, p * 1000)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                prefs.videoBitrateKbps = (sb?.progress ?: 8) * 1000
            }
        })

        val spFps = findViewById<Spinner>(R.id.spFps)
        val fpsList = listOf(30, 45, 60, 90, 120)
        spFps.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            fpsList.map { getString(R.string.fmt_fps, it) })
        spFps.setSelection(fpsList.indexOf(prefs.framerate).coerceAtLeast(0))
        spFps.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.framerate = fpsList[pos]
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        bindSwitch(R.id.swMatchRes, prefs.matchResolution) { prefs.matchResolution = it }

        // ---------------- 音频/行为 ----------------
        bindSwitch(R.id.swAudio, prefs.audioEnabled) { prefs.audioEnabled = it }
        bindSwitch(R.id.swClipboard, prefs.clipboardSync) { prefs.clipboardSync = it }
        bindSwitch(R.id.swReconnect, prefs.autoReconnect) { prefs.autoReconnect = it }
        bindSwitch(R.id.swKeepOn, prefs.keepScreenOn) { prefs.keepScreenOn = it }
        bindSwitch(R.id.swAutoKb, prefs.autoShowKeyboard) { prefs.autoShowKeyboard = it }

        // ---------------- 手势 ----------------
        val spLongPress = findViewById<Spinner>(R.id.spLongPress)
        val lpActions = listOf(0, 1, 2)
        val lpLabels = listOf(getString(R.string.lp_right), getString(R.string.lp_middle), getString(R.string.lp_none))
        spLongPress.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, lpLabels)
        spLongPress.setSelection(lpActions.indexOf(prefs.longPressAction).coerceAtLeast(0))
        spLongPress.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { prefs.longPressAction = lpActions[pos] }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        bindSwitch(R.id.swNaturalScroll, prefs.naturalScroll) { prefs.naturalScroll = it }

        val sbScrollStep = findViewById<SeekBar>(R.id.sbScrollStep)
        val tvScrollStep = findViewById<TextView>(R.id.tvScrollStep)
        sbScrollStep.max = 80   // 20..100 px
        sbScrollStep.progress = (prefs.scrollStepPx - 20).coerceIn(0, 80)
        tvScrollStep.text = getString(R.string.fmt_scroll_px, prefs.scrollStepPx)
        sbScrollStep.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                tvScrollStep.text = getString(R.string.fmt_scroll_px, p + 20)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                prefs.scrollStepPx = (sb?.progress ?: 30) + 20
            }
        })

        // ---------------- 密钥管理 ----------------
        findViewById<View>(R.id.btnClearPasswords).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.clear_pw_title)
                .setMessage(R.string.clear_pw_msg)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    ServerStore(File(filesDir, "servers.json")).clearPasswords()
                    Toast.makeText(this, R.string.clear_pw_done, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        // ---------------- 关于 ----------------
        findViewById<TextView>(R.id.tvAbout).text = getString(R.string.about_text, "1.0.0")
    }

    private fun bindSwitch(id: Int, init: Boolean, set: (Boolean) -> Unit) {
        val sw = findViewById<Switch>(id)
        sw.isChecked = init
        sw.setOnCheckedChangeListener { _, checked -> set(checked) }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}

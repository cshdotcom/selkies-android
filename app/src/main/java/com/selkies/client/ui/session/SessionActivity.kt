package com.selkies.client.ui.session

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.input.InputManager
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.selkies.client.R
import com.selkies.client.data.ServerConfig
import com.selkies.client.data.ServerRepository
import com.selkies.client.data.SettingsStore
import com.selkies.client.databinding.ActivitySessionBinding
import com.selkies.client.input.GestureHandler
import com.selkies.client.input.ImeCaptureView
import com.selkies.client.input.InputController
import com.selkies.client.input.Keysyms
import com.selkies.client.input.ShortcutStore
import com.selkies.client.media.AudioPlayer
import com.selkies.client.media.MicUplink
import com.selkies.client.media.VideoDecoder
import com.selkies.client.media.WebcamUplink
import com.selkies.client.net.ConnectionEngine
import com.selkies.client.net.DiagLog
import com.selkies.client.net.FilesApi
import com.selkies.client.net.SessionBridge
import com.selkies.client.ui.AppsActivity
import com.selkies.client.ui.FilesActivity
import com.selkies.client.ui.PrintHelper
import com.selkies.client.ui.ShortcutsActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/**
 * 远程会话页 —— 引擎 / 解码器 / 输入 / 硬件上行的装配现场。
 *
 * v1.1.0 新增：
 *  - IME 直通（ImeCaptureView）：点键盘直接弹输入法，实时 co,end，回车发键事件；
 *  - 快捷键系统（预置+自定义）：底部快捷键栏 + 管理（ShortcutsActivity）；
 *  - 有线键盘模式：检测到外接键盘自动切换（隐藏软键盘 + 键盘直通）；
 *  - 远程硬件：麦克风（0x02 Opus）与摄像头（0x06 H264）上行，响应 CAPTURE_DEMAND；
 *  - 远程打印：print_document 信号 → 自动拉起系统打印界面；
 *  - 截图（/api/screenshot）、远端文件浏览器入口、全屏沉浸模式。
 */
class SessionActivity : AppCompatActivity(), ConnectionEngine.Listener {

    companion object {
        const val EXTRA_SERVER_ID = "server_id"
        private const val TAG = "SessionActivity"
        private const val REQ_PERM_MIC = 5001
        private const val REQ_PERM_CAM = 5002
    }

    private lateinit var binding: ActivitySessionBinding
    private lateinit var settings: SettingsStore
    private lateinit var repo: ServerRepository
    private var config: ServerConfig? = null

    // 核心组件
    private lateinit var engine: ConnectionEngine
    private lateinit var videoDecoder: VideoDecoder
    private val audioPlayer = AudioPlayer()
    private lateinit var input: InputController
    private lateinit var gestures: GestureHandler
    private lateinit var shortcutStore: ShortcutStore
    private var micUplink: MicUplink? = null
    private var webcamUplink: WebcamUplink? = null

    // 视图状态
    private var surfaceView: SurfaceView? = null
    private var streamW = 0
    private var streamH = 0
    private var zoomFactor = 1f
    private var zoomCenterX = 0.5f   // 0..1 缩放焦点（容器比例）
    private var zoomCenterY = 0.5f
    private var audioOn = true
    private var keyboardVisible = false
    private var roleCapped = false
    private var sessionEverConnected = false
    private var fullscreen = false

    // 有线键盘模式（检测到外接键盘自动切换）
    private var hwKeyboardMode = false
    private var inputDeviceListener: InputManager.InputDeviceListener? = null

    // 硬件权限拒绝后的"拒绝粘滞"（对齐官方 webcamDemandRefused 逻辑：拒绝过就不再自动请求）
    private var micPermRefused = false
    private var camPermRefused = false

    // IME 直通层
    private lateinit var imeCapture: ImeCaptureView

    // v1.2.0：剪贴板双向同步状态
    private var clipListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    /** 回声抑制：最近一次从远端收到的剪贴板文本（避免回环） */
    private var lastRemoteClip: String = ""
    /** 最近一次本地推送到远端的文本（同样抑制） */
    private var lastLocalPush: String = ""
    private val clipPushDebounce = Runnable { pushLocalClipboardNow() }

    // v1.2.0：应用启动器 / 命令能力（server_settings 解析）
    private var appsAvailable = false
    private var commandEnabled = false
    private var appsLocalRepo = false

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivitySessionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = SettingsStore(this)
        repo = ServerRepository(this)
        config = repo.byId(intent.getStringExtra(EXTRA_SERVER_ID) ?: "")

        if (config == null) {
            Toast.makeText(this, R.string.error_connect_failed, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val cfg = config!!

        // ---- 装配核心组件 ----
        engine = ConnectionEngine(cfg, settings, this)
        videoDecoder = VideoDecoder()
        input = InputController(engine)
        gestures = GestureHandler(engine, input, settings)
        shortcutStore = ShortcutStore(this)

        input.viewOnly = cfg.viewOnly
        engine.mediaSink = CombinedSink()
        videoDecoder.onRequestKeyframe = { engine.requestKeyframe() }
        videoDecoder.onResolutionChanged = { w, h -> runOnUiThread { onStreamResolution(w, h) } }
        gestures.isZoomed = { zoomFactor > 1.01f }
        gestures.onZoom = { factor, fx, fy -> applyZoom(factor, fx, fy) }
        gestures.onPan = { dx, dy -> applyPan(dx, dy) }

        // 屏幕尺寸（SETTINGS 的 initialClientWidth/Height）
        val metrics = resources.displayMetrics
        engine.clientWidth = metrics.widthPixels
        engine.clientHeight = metrics.heightPixels

        setupStreamView()
        setupPanel()
        setupKeyboard()
        setupIme()
        setupAuthBox()
        setupHardwareKeyboardDetection()
        setupClipboardSync()

        // 跨页命令桥：AppsActivity 通过它发送 cmd 命令
        SessionBridge.sendCommand = { cmd -> engine.sendCommand(cmd) }

        engine.connect()
    }

    override fun onStart() {
        super.onStart()
        // 回前台：恢复视频流 + 请求关键帧补画面
        if (this::engine.isInitialized && sessionEverConnected) {
            engine.sendText("START_VIDEO")
            engine.requestKeyframe()
        }
    }

    override fun onStop() {
        super.onStop()
        // 切后台：暂停视频（服务端停编码省带宽），释放远端按键防卡键
        if (this::engine.isInitialized) {
            engine.sendText("STOP_VIDEO")
            input.releaseAll()
        }
        hideKeyboard()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            inputDeviceListener?.let {
                (getSystemService(Context.INPUT_SERVICE) as? InputManager)
                    ?.unregisterInputDeviceListener(it)
            }
        } catch (_: Exception) {
        }
        // 注销剪贴板监听与会话桥，防泄漏
        try {
            clipListener?.let {
                (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                    ?.removePrimaryClipChangedListener(it)
            }
        } catch (_: Exception) {
        }
        SessionBridge.sendCommand = null
        SessionBridge.onInstalledChanged = null
        micUplink?.stop(); micUplink = null
        webcamUplink?.stop(); webcamUplink = null
        if (this::engine.isInitialized) {
            engine.mediaSink = null
            engine.shutdown()
        }
        if (this::videoDecoder.isInitialized) videoDecoder.release()
        audioPlayer.release()
    }

    /** 返回键：键盘开着先收起；再确认退出 */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (keyboardVisible) {
            hideKeyboard()
            return
        }
        android.app.AlertDialog.Builder(this)
            .setMessage(R.string.exit_session)
            .setPositiveButton(R.string.confirm) { _, _ -> finish() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------
    // 流视图（SurfaceView + letterbox + 本地缩放）
    // ------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun setupStreamView() {
        val sv = SurfaceView(this)
        surfaceView = sv
        sv.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        binding.streamContainer.addView(sv, 0)

        sv.holder.addCallback(object : android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                if (this@SessionActivity::videoDecoder.isInitialized) {
                    videoDecoder.surface = holder.surface
                    if (streamW > 0 && streamH > 0) {
                        try {
                            holder.setFixedSize(streamW, streamH)
                        } catch (_: Exception) {
                        }
                    }
                }
                if (this@SessionActivity::engine.isInitialized) engine.requestKeyframe()
            }

            override fun surfaceChanged(
                holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int
            ) {
            }

            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                if (this@SessionActivity::videoDecoder.isInitialized) {
                    videoDecoder.surface = null
                }
            }
        })

        // 触摸全部交给手势处理器（面板按钮在自己的视图层，不经过容器触摸）
        binding.streamContainer.setOnTouchListener { _, e ->
            gestures.onTouchEvent(e)
            true
        }

        // 布局变化 → 重新计算映射与画面位置
        binding.streamContainer.viewTreeObserver.addOnGlobalLayoutListener {
            applyStreamLayout()
        }
    }

    /** 按流分辨率 + 缩放状态布局 SurfaceView（letterbox 居中） */
    private var lastFixedW = 0
    private var lastFixedH = 0
    private var lastContainerW = 0
    private var lastContainerH = 0
    private val resDebounce = Runnable {
        // 旋转后把新屏幕尺寸告知服务端（调整桌面分辨率），可设置关闭
        if (settings.followRotation && this::engine.isInitialized) {
            engine.clientWidth = lastContainerW
            engine.clientHeight = lastContainerH
            engine.sendResolution(lastContainerW, lastContainerH)
        }
    }

    private fun applyStreamLayout() {
        val sv = surfaceView ?: return
        val cw = binding.streamContainer.width
        val ch = binding.streamContainer.height
        if (cw <= 0 || ch <= 0) return

        gestures.viewW = cw.toFloat()
        gestures.viewH = ch.toFloat()

        // 容器尺寸变化（旋转/键盘弹出）→ 防抖后重设远端分辨率
        if (cw != lastContainerW || ch != lastContainerH) {
            val firstLayout = lastContainerW == 0
            lastContainerW = cw
            lastContainerH = ch
            if (!firstLayout) {
                binding.streamContainer.removeCallbacks(resDebounce)
                binding.streamContainer.postDelayed(resDebounce, 600)
            }
        }

        if (streamW <= 0 || streamH <= 0) {
            gestures.contentX = 0f; gestures.contentY = 0f
            gestures.contentW = cw.toFloat(); gestures.contentH = ch.toFloat()
            sv.layout(0, 0, cw, ch)
            return
        }

        val scaleBase = min(cw.toFloat() / streamW, ch.toFloat() / streamH)
        val scale = scaleBase * zoomFactor
        val dw = (streamW * scale).toInt()
        val dh = (streamH * scale).toInt()
        // 缩放中心锚定的布局；钳制内容不脱离屏幕
        var dx = ((cw - dw) * zoomCenterX).toFloat()
        var dy = ((ch - dh) * zoomCenterY).toFloat()
        dx = dx.coerceIn(min(0f, cw - dw.toFloat()), max(0f, cw - dw.toFloat()))
        dy = dy.coerceIn(min(0f, ch - dh.toFloat()), max(0f, ch - dh.toFloat()))

        gestures.contentX = dx
        gestures.contentY = dy
        gestures.contentW = dw.toFloat()
        gestures.contentH = dh.toFloat()
        gestures.streamW = streamW.toFloat()
        gestures.streamH = streamH.toFloat()

        sv.layout(dx.toInt(), dy.toInt(), (dx + dw).toInt(), (dy + dh).toInt())
        // 缓冲区固定为流原始分辨率（仅尺寸变化时重设，避免 surface 抖动）
        if (streamW != lastFixedW || streamH != lastFixedH) {
            lastFixedW = streamW
            lastFixedH = streamH
            try {
                sv.holder.setFixedSize(streamW, streamH)
            } catch (_: Exception) {
            }
        }
    }

    /** 捏合缩放（增量模型：factor 是本次增量比） */
    private fun applyZoom(factor: Float, focusViewX: Float, focusViewY: Float) {
        val cw = binding.streamContainer.width
        val ch = binding.streamContainer.height
        if (cw <= 0 || ch <= 0) return
        val newZoom = (zoomFactor * factor).coerceIn(1f, 4f)
        if (newZoom <= 1.01f) {
            zoomFactor = 1f
            zoomCenterX = 0.5f
            zoomCenterY = 0.5f
        } else {
            zoomFactor = newZoom
            // 缩放焦点（视图坐标 → 容器比例）
            zoomCenterX = (focusViewX / cw).coerceIn(0f, 1f)
            zoomCenterY = (focusViewY / ch).coerceIn(0f, 1f)
        }
        applyStreamLayout()
    }

    /** 双指平移（缩放态） */
    private fun applyPan(dx: Float, dy: Float) {
        if (zoomFactor <= 1.01f) return
        val cw = binding.streamContainer.width.toFloat()
        val ch = binding.streamContainer.height.toFloat()
        zoomCenterX = (zoomCenterX - dx / cw).coerceIn(0f, 1f)
        zoomCenterY = (zoomCenterY - dy / ch).coerceIn(0f, 1f)
        applyStreamLayout()
    }

    private fun resetZoom() {
        zoomFactor = 1f
        zoomCenterX = 0.5f
        zoomCenterY = 0.5f
        applyStreamLayout()
    }

    // ------------------------------------------------------------------
    // 控制面板
    // ------------------------------------------------------------------

    private fun setupPanel() {
        binding.btnIdr.setOnClickListener {
            engine.requestKeyframe()
            toast(getString(R.string.panel_idr))
        }
        binding.btnIdr.setOnLongClickListener {
            resetZoom()
            true
        }

        binding.btnClipboard.setOnClickListener { showClipboardDialog() }

        binding.btnAudio.setOnClickListener {
            audioOn = !audioOn
            audioPlayer.enabled = audioOn
            toast(getString(if (audioOn) R.string.audio_on else R.string.audio_off))
        }

        binding.btnMouseMode.setOnClickListener {
            val modes = arrayOf(
                getString(R.string.mouse_mode_touch),
                getString(R.string.mouse_mode_direct)
            )
            android.app.AlertDialog.Builder(this)
                .setTitle(R.string.panel_mouse_mode)
                .setSingleChoiceItems(modes, if (settings.touchMode == "direct") 1 else 0) { d, which ->
                    settings.touchMode = if (which == 1) "direct" else "trackpad"
                    d.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        binding.btnKeyboard.setOnClickListener {
            if (keyboardVisible) hideKeyboard() else showKeyboard()
        }

        binding.btnStats.setOnClickListener {
            binding.txtStats.visibility =
                if (binding.txtStats.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        binding.btnHidePanel.setOnClickListener { hidePanel() }
        binding.btnShowPanel.setOnClickListener { showPanel() }

        binding.btnDisconnect.setOnClickListener {
            engine.disconnect()
            finish()
        }

        // ---- v1.2.0 第三行按钮 ----
        binding.btnApps.setOnClickListener { openAppsLauncher() }
        binding.btnQuality.setOnClickListener { showQualityDialog() }
        binding.btnCommand.setOnClickListener { showCommandDialog() }
        binding.btnDiag.setOnClickListener { showDiagnostics() }

        binding.btnReconnect.setOnClickListener {
            binding.btnReconnect.visibility = View.GONE
            engine.connect()
        }
        binding.btnExit.setOnClickListener { finish() }

        // ---- v1.1.0 新增按钮 ----
        binding.btnFiles.setOnClickListener {
            config?.let { c ->
                startActivity(
                    Intent(this, FilesActivity::class.java)
                        .putExtra(FilesActivity.EXTRA_SERVER_ID, c.id)
                )
            }
        }

        binding.btnScreenshot.setOnClickListener { takeScreenshot() }

        binding.btnMic.setOnClickListener { toggleMic() }
        binding.btnWebcam.setOnClickListener { toggleWebcam() }

        binding.btnFullscreen.setOnClickListener { toggleFullscreen() }
    }

    private fun showPanel() {
        binding.panelTop.visibility = View.VISIBLE
        binding.btnShowPanel.visibility = View.GONE
    }

    private fun hidePanel() {
        binding.panelTop.visibility = View.GONE
        binding.btnShowPanel.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------------
    // 全屏沉浸模式
    // ------------------------------------------------------------------

    private fun toggleFullscreen() {
        fullscreen = !fullscreen
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (fullscreen) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            hidePanel()
            toast(getString(R.string.fullscreen_on))
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            showPanel()
        }
    }

    // ------------------------------------------------------------------
    // 截图（GET /api/screenshot → 系统下载目录）
    // ------------------------------------------------------------------

    private fun takeScreenshot() {
        val cfg = config ?: return
        toast(getString(R.string.screenshot_saving))
        thread(name = "screenshot") {
            try {
                val tmp = File(cacheDir, "shot_${System.currentTimeMillis()}.png")
                FilesApi.screenshot(cfg, tmp)
                val saved = FilesApi.saveToDownloads(
                    this,
                    "selkies_${System.currentTimeMillis()}.png",
                    "image/png"
                ) { tmp.readBytes() }
                tmp.delete()
                runOnUiThread {
                    toast(if (saved != null) getString(R.string.screenshot_saved)
                    else getString(R.string.screenshot_failed))
                }
            } catch (e: Exception) {
                Log.w(TAG, "screenshot: ${e.message}")
                runOnUiThread { toast(getString(R.string.screenshot_failed)) }
            }
        }
    }

    // ------------------------------------------------------------------
    // 麦克风 / 摄像头上行（0x02 / 0x06）
    // ------------------------------------------------------------------

    private fun toggleMic() {
        if (roleCapped || input.viewOnly) {
            toast(getString(R.string.status_viewonly))
            return
        }
        if (micUplink?.isRunning == true) {
            micUplink?.stop()
            binding.btnMic.isSelected = false
            toast(getString(R.string.mic_off))
            return
        }
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            if (micPermRefused) {
                toast(getString(R.string.mic_perm_refused))
                return
            }
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_PERM_MIC)
            return
        }
        startMic()
    }

    private fun startMic() {
        if (micUplink?.isRunning == true) return
        if (!MicUplink.encoderAvailable()) {
            toast(getString(R.string.mic_no_encoder))
            return
        }
        micUplink = MicUplink(engine) { err ->
            runOnUiThread {
                toast(getString(R.string.mic_error, err))
                binding.btnMic.isSelected = false
            }
        }.also { up ->
            if (up.start()) {
                binding.btnMic.isSelected = true
                toast(getString(R.string.mic_on))
            } else {
                binding.btnMic.isSelected = false
            }
        }
    }

    private fun toggleWebcam() {
        if (roleCapped || input.viewOnly) {
            toast(getString(R.string.status_viewonly))
            return
        }
        if (webcamUplink?.isRunning == true) {
            webcamUplink?.stop()
            binding.btnWebcam.isSelected = false
            toast(getString(R.string.webcam_off))
            return
        }
        if (!hasPermission(Manifest.permission.CAMERA)) {
            if (camPermRefused) {
                toast(getString(R.string.webcam_perm_refused))
                return
            }
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_PERM_CAM)
            return
        }
        startWebcam()
    }

    private fun startWebcam() {
        if (webcamUplink?.isRunning == true) return
        webcamUplink = WebcamUplink(this, engine) { err ->
            runOnUiThread {
                toast(getString(R.string.webcam_error, err))
                binding.btnWebcam.isSelected = false
            }
        }.also { up ->
            if (up.start()) {
                binding.btnWebcam.isSelected = true
                toast(getString(R.string.webcam_on))
            } else {
                binding.btnWebcam.isSelected = false
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQ_PERM_MIC -> {
                if (granted) startMic()
                else {
                    micPermRefused = true
                    toast(getString(R.string.mic_perm_refused))
                }
            }
            REQ_PERM_CAM -> {
                if (granted) startWebcam()
                else {
                    camPermRefused = true
                    toast(getString(R.string.webcam_perm_refused))
                }
            }
        }
    }

    private fun hasPermission(p: String): Boolean =
        checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------
    // 剪贴板双向同步
    // ------------------------------------------------------------------

    private fun showClipboardDialog() {
        val modeNames = arrayOf(
            getString(R.string.clip_mode_both),
            getString(R.string.clip_mode_recv),
            getString(R.string.clip_mode_send),
            getString(R.string.clip_mode_off)
        )
        val modeValues = arrayOf("both", "recv", "send", "off")
        val modeIdx = modeValues.indexOf(settings.clipboardMode).coerceAtLeast(0)
        val items = arrayOf(
            getString(R.string.clip_action_pull),
            getString(R.string.clip_action_push),
            getString(R.string.clip_action_edit_send),
            getString(R.string.clip_action_settings)
        )
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.panel_clipboard_sync)
            .setMessage(getString(R.string.clip_current_mode, modeNames[modeIdx]))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        engine.requestClipboard()
                        toast(getString(R.string.clip_pulling))
                    }
                    1 -> sendLocalClipboard()
                    2 -> showEditClipboardDialog()
                    3 -> {
                        android.app.AlertDialog.Builder(this)
                            .setTitle(R.string.clip_action_settings)
                            .setSingleChoiceItems(modeNames, modeIdx) { d, sel ->
                                settings.clipboardMode = modeValues[sel]
                                applyClipboardSyncMode()
                                toast(getString(R.string.clip_mode_set, modeNames[sel]))
                                d.dismiss()
                            }
                            .setNegativeButton(R.string.cancel, null)
                            .show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 编辑文本发送到远端（任意内容，不必依赖本地剪贴板） */
    private fun showEditClipboardDialog() {
        val edit = EditText(this)
        edit.hint = getString(R.string.clip_edit_hint)
        edit.setText(lastRemoteClip)
        edit.minLines = 3
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.clip_action_edit_send)
            .setView(edit)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val text = edit.text.toString()
                if (text.isNotEmpty()) {
                    engine.sendClipboardText(text)
                    toast(getString(R.string.panel_clipboard_sent))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 本地 → 远端（手动） */
    private fun sendLocalClipboard() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
            if (text.isEmpty()) {
                toast(getString(R.string.clip_local_empty))
                return
            }
            engine.sendClipboardText(text)
            lastLocalPush = text
            toast(getString(R.string.panel_clipboard_sent))
        } catch (e: Exception) {
            Log.w(TAG, "read local clipboard: ${e.message}")
        }
    }

    /** 远端 → 本地（引擎回调 onClipboardText） */
    private fun setLocalClipboard(text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("selkies", text))
            toast(getString(R.string.panel_clipboard_got) + " (${text.length})")
        } catch (e: Exception) {
            Log.w(TAG, "set local clipboard: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // 剪贴板双向自动同步（v1.2.0）：本地监听 → cw 上行；远端下行 → 本地写入
    // ------------------------------------------------------------------

    /** 注册本地剪贴板变化监听（按设置方向动态启停） */
    private fun setupClipboardSync() {
        clipListener = ClipboardManager.OnPrimaryClipChangedListener {
            // 防抖：短时间连续变化只推一次
            binding.root.removeCallbacks(clipPushDebounce)
            binding.root.postDelayed(clipPushDebounce, 400)
        }
        applyClipboardSyncMode()
    }

    /** 按当前模式注册/注销监听 */
    private fun applyClipboardSyncMode() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipListener?.let { cm.removePrimaryClipChangedListener(it) }
            if (settings.clipboardSend) {
                clipListener?.let { cm.addPrimaryClipChangedListener(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "applyClipboardSyncMode: ${e.message}")
        }
    }

    /** 本地剪贴板变化 → 推送到远端（回声抑制 + 图片二进制） */
    private fun pushLocalClipboardNow() {
        if (!settings.clipboardSend || roleCapped || input.viewOnly) return
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip ?: return
            if (clip.itemCount == 0) return
            val item = clip.getItemAt(0)

            // 文本优先
            val text = item.coerceToText(this)?.toString() ?: ""
            if (text.isNotEmpty()) {
                if (text == lastRemoteClip || text == lastLocalPush) return // 回声抑制
                if (text.length > 1_000_000) return // 过大不自动推
                engine.sendClipboardText(text)
                lastLocalPush = text
                return
            }

            // 图片：URI 读取后 cb 上行（需二进制剪贴板开启）
            if (settings.clipboardBinary) {
                val uri = item.uri ?: return
                val mime = try {
                    contentResolver.getType(uri) ?: "image/png"
                } catch (_: Exception) {
                    "image/png"
                }
                if (!mime.startsWith("image/")) return
                thread(name = "clip-img-push") {
                    try {
                        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        if (bytes != null && bytes.size in 1..(2 * 1024 * 1024)) {
                            engine.sendClipboardBinary(mime, bytes)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "clip image read: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "pushLocalClipboard: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // v1.2.0：远程应用启动器 / 画质热切换 / 远程命令 / 诊断
    // ------------------------------------------------------------------

    private fun openAppsLauncher() {
        if (!appsAvailable) {
            toast(getString(R.string.apps_not_enabled))
            return
        }
        config?.let { c ->
            startActivity(
                Intent(this, AppsActivity::class.java)
                    .putExtra(AppsActivity.EXTRA_SERVER_ID, c.id)
                    .putExtra(AppsActivity.EXTRA_LOCAL_REPO, appsLocalRepo)
                    .putExtra(AppsActivity.EXTRA_COMMAND_ENABLED, commandEnabled)
            )
        }
    }

    /** 画质热切换：部分 SETTINGS 载荷即时生效（官方 sendFullSettingsUpdateToServer 同款机制） */
    private fun showQualityDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_quality, null)
        val seekCrf = view.findViewById<SeekBar>(R.id.seek_crf)
        val valCrf = view.findViewById<TextView>(R.id.val_crf)
        val seekBitrate = view.findViewById<SeekBar>(R.id.seek_bitrate)
        val valBitrate = view.findViewById<TextView>(R.id.val_bitrate)
        val txtFps = view.findViewById<TextView>(R.id.val_framerate)
        val txtMode = view.findViewById<TextView>(R.id.val_rate_mode)

        var fps = settings.framerate
        var crf = settings.videoCrf
        var bitrate = settings.videoBitrate
        var mode = settings.rateControl

        fun syncLabels() {
            valCrf.text = crf.toString()
            valBitrate.text = getString(R.string.quality_bitrate_fmt, bitrate)
            txtFps.text = getString(R.string.quality_fps_fmt, fps)
            txtMode.text = getString(if (mode == "cbr") R.string.quality_mode_cbr else R.string.quality_mode_crf)
        }

        // 预设
        view.findViewById<Button>(R.id.preset_smooth).setOnClickListener { fps = 15; crf = 32; bitrate = 2500; mode = "cbr"; syncLabels() }
        view.findViewById<Button>(R.id.preset_balance).setOnClickListener { fps = 30; crf = 25; bitrate = 8000; mode = "cbr"; syncLabels() }
        view.findViewById<Button>(R.id.preset_hd).setOnClickListener { fps = 30; crf = 20; bitrate = 16000; mode = "cbr"; syncLabels() }
        view.findViewById<Button>(R.id.preset_max).setOnClickListener { fps = 60; crf = 16; bitrate = 30000; mode = "cbr"; syncLabels() }

        // 帧率
        view.findViewById<Button>(R.id.btn_fps_down).setOnClickListener { fps = (fps - 5).coerceIn(10, 60); syncLabels() }
        view.findViewById<Button>(R.id.btn_fps_up).setOnClickListener { fps = (fps + 5).coerceIn(10, 60); syncLabels() }

        // 码率模式
        view.findViewById<Button>(R.id.btn_mode_toggle).setOnClickListener {
            mode = if (mode == "cbr") "crf" else "cbr"
            syncLabels()
        }

        seekCrf.max = 51
        seekCrf.progress = crf
        seekCrf.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                crf = v; syncLabels()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        seekBitrate.max = 100 // 500..50500 kbps
        seekBitrate.progress = ((bitrate - 500) / 500).coerceIn(0, 100)
        seekBitrate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                bitrate = 500 + v * 500; syncLabels()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        syncLabels()

        AlertDialog.Builder(this)
            .setTitle(R.string.panel_quality)
            .setView(view)
            .setPositiveButton(R.string.confirm) { _, _ ->
                // 持久化
                settings.framerate = fps
                settings.videoCrf = crf
                settings.videoBitrate = bitrate
                settings.rateControl = mode
                // 热更新（仅变更键的部分载荷，服务端即时应用）
                val o = JSONObject()
                o.put("framerate", fps)
                o.put("video_crf", crf)
                o.put("video_bitrate", bitrate)
                o.put("rate_control_mode", mode)
                engine.sendSettingsUpdate(o)
                toast(getString(R.string.quality_applied))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 远程命令执行（cmd,<command>；服务端 command_enabled 开关门禁） */
    private fun showCommandDialog() {
        if (!commandEnabled) {
            toast(getString(R.string.command_not_enabled))
            return
        }
        val edit = EditText(this)
        edit.hint = getString(R.string.command_hint)
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.panel_command)
            .setMessage(getString(R.string.command_desc))
            .setView(edit)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val cmd = edit.text.toString().trim()
                if (cmd.isNotEmpty()) {
                    engine.sendCommand(cmd)
                    toast(getString(R.string.command_sent))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 连接诊断：查看并复制/分享诊断日志 */
    private fun showDiagnostics() {
        val text = DiagLog.dump()
        AlertDialog.Builder(this)
            .setTitle(R.string.panel_diag)
            .setMessage(DiagLog.tail(30))
            .setPositiveButton(R.string.diag_copy) { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("selkies-diag", text))
                toast(getString(R.string.diag_copied))
            }
            .setNeutralButton(R.string.diag_share) { _, _ ->
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, text)
                try {
                    startActivity(Intent.createChooser(send, getString(R.string.panel_diag)))
                } catch (_: Exception) {
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    // ------------------------------------------------------------------
    // Basic Auth 内嵌表单
    // ------------------------------------------------------------------

    private fun setupAuthBox() {
        binding.btnAuthOk.setOnClickListener {
            val user = binding.editAuthUser.text.toString()
            val pass = binding.editAuthPass.text.toString()
            if (user.isEmpty()) {
                binding.editAuthUser.error = getString(R.string.server_username)
                return@setOnClickListener
            }
            engine.provideCredentials(user, pass)
            binding.authBox.visibility = View.GONE
            showStatus(getString(R.string.status_connecting))
        }
    }

    // ------------------------------------------------------------------
    // IME 直通（核心新特性：直接唤起输入法，实时输入，回车=键事件）
    // ------------------------------------------------------------------

    private fun setupIme() {
        imeCapture = binding.imeCapture
        // 打字实时上屏：每次 IME 提交即 co,end（无需发送按钮）
        imeCapture.onTextCommit = { text ->
            input.commitText(text)
        }
        // IME 直发键（回车/退格等）→ keysym 键事件
        imeCapture.onKey = { keyCode, action, _ ->
            val keysym = Keysyms.fromAndroidKeyCode(keyCode, null)
            if (keysym != null) {
                input.key(keysym, action == KeyEvent.ACTION_DOWN)
                true
            } else false
        }
        imeCapture.onImeOpened = { /* 状态跟踪（键盘可见性由 IMM 管理） */ }
    }

    // ------------------------------------------------------------------
    // 虚拟键盘（快捷键栏 + 扩展键位 + IME 直通按钮）
    // ------------------------------------------------------------------

    private fun setupKeyboard() {
        // 修饰键（粘滞）
        val mods = mapOf(
            R.id.kb_ctrl to Keysyms.CTRL_L,
            R.id.kb_alt to Keysyms.ALT_L,
            R.id.kb_shift to Keysyms.SHIFT_L,
            R.id.kb_super to Keysyms.SUPER_L
        )
        mods.forEach { (id, keysym) ->
            findViewById<Button>(id).setOnClickListener { v ->
                val on = input.toggleModifier(keysym)
                v.isSelected = on
            }
        }
        input.onModifiersChanged = {
            // 同步按钮视觉态
            findViewById<Button>(R.id.kb_ctrl).isSelected = Keysyms.CTRL_L in input.activeModifiers
            findViewById<Button>(R.id.kb_alt).isSelected = Keysyms.ALT_L in input.activeModifiers
            findViewById<Button>(R.id.kb_shift).isSelected = Keysyms.SHIFT_L in input.activeModifiers
            findViewById<Button>(R.id.kb_super).isSelected = Keysyms.SUPER_L in input.activeModifiers
        }

        // 点按键
        val taps = mapOf(
            R.id.kb_f1 to Keysyms.F1, R.id.kb_f2 to Keysyms.F2, R.id.kb_f3 to Keysyms.F3,
            R.id.kb_f4 to Keysyms.F4, R.id.kb_f5 to Keysyms.F5, R.id.kb_f6 to Keysyms.F6,
            R.id.kb_f7 to Keysyms.F7, R.id.kb_f8 to Keysyms.F8, R.id.kb_f9 to Keysyms.F9,
            R.id.kb_f10 to Keysyms.F10, R.id.kb_f11 to Keysyms.F11, R.id.kb_f12 to Keysyms.F12,
            R.id.kb_esc to Keysyms.ESCAPE, R.id.kb_tab to Keysyms.TAB,
            R.id.kb_home to Keysyms.HOME, R.id.kb_end to Keysyms.END,
            R.id.kb_pgup to Keysyms.PAGE_UP, R.id.kb_pgdn to Keysyms.PAGE_DOWN,
            R.id.kb_del to Keysyms.DELETE, R.id.kb_up to Keysyms.UP,
            R.id.kb_down to Keysyms.DOWN, R.id.kb_left to Keysyms.LEFT,
            R.id.kb_right to Keysyms.RIGHT,
            R.id.kb_backspace to Keysyms.BACKSPACE, R.id.kb_enter to Keysyms.RETURN
        )
        taps.forEach { (id, keysym) ->
            findViewById<Button>(id).setOnClickListener { input.tapKey(keysym) }
        }

        // IME 直通按钮：点一下直接弹系统输入法（不需要任何发送操作）
        findViewById<Button>(R.id.kb_ime).setOnClickListener { showImeDirect() }

        // 快捷键管理入口
        findViewById<Button>(R.id.kb_manage_shortcuts).setOnClickListener {
            startActivity(Intent(this, ShortcutsActivity::class.java))
        }
    }

    /** onResume 时刷新快捷键栏（用户可能在管理页增删） */
    override fun onResume() {
        super.onResume()
        if (this::shortcutStore.isInitialized) rebuildShortcutRow()
    }

    /** 快捷键栏：预置 + 自定义动态填充（点击即发送组合键） */
    private fun rebuildShortcutRow() {
        val row = findViewById<LinearLayout>(R.id.kb_shortcut_row) ?: return
        // 保留"管理"按钮，其余重建
        val manageBtn = findViewById<Button>(R.id.kb_manage_shortcuts)
        row.removeAllViews()
        if (manageBtn != null) row.addView(manageBtn)
        try {
            shortcutStore.all().forEach { sc ->
                val btn = Button(this, null, 0, R.style.ShortcutBtn)
                btn.text = sc.name
                btn.setOnClickListener { input.tapChord(sc.modifiers, sc.key) }
                row.addView(btn)
            }
        } catch (e: Exception) {
            Log.w(TAG, "shortcut row: ${e.message}")
        }
    }

    /** 直接唤起系统输入法（IME 直通层聚焦） */
    private fun showImeDirect() {
        keyboardVisible = true
        binding.virtualKeyboardInclude.root.visibility = View.VISIBLE
        imeCapture.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(imeCapture, 0)
    }

    private fun showKeyboard() = showImeDirect()

    private fun hideKeyboard() {
        keyboardVisible = false
        binding.virtualKeyboardInclude.root.visibility = View.GONE
        if (this::input.isInitialized) input.releaseModifiers()
        try {
            if (this::imeCapture.isInitialized) {
                val imm =
                    getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(imeCapture.windowToken, 0)
                imeCapture.clearFocus()
            }
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------
    // 有线键盘模式（检测到外接键盘 → 自动切换：隐藏软键盘 + 物理键直通）
    // ------------------------------------------------------------------

    private fun setupHardwareKeyboardDetection() {
        val im = getSystemService(Context.INPUT_SERVICE) as? InputManager ?: return
        // 初始状态（配置级：hardKeyboardHidden）
        if (resources.configuration.hardKeyboardHidden ==
            android.content.res.Configuration.HARDKEYBOARDHIDDEN_NO
        ) {
            enterHwKeyboardMode(silent = true)
        }
        inputDeviceListener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) {
                handleDevice(deviceId)
            }

            override fun onInputDeviceRemoved(deviceId: Int) {
                // 设备移除后重新评估
                if (!anyExternalKeyboard()) exitHwKeyboardMode()
            }

            override fun onInputDeviceChanged(deviceId: Int) {
                handleDevice(deviceId)
            }

            private fun handleDevice(deviceId: Int) {
                try {
                    val dev = InputDevice.getDevice(deviceId) ?: return
                    val isKb = (dev.sources and InputDevice.SOURCE_KEYBOARD) != 0 &&
                            dev.isExternal && !dev.isVirtual
                    if (isKb) enterHwKeyboardMode()
                } catch (_: Exception) {
                }
            }
        }
        im.registerInputDeviceListener(inputDeviceListener, null)
    }

    private fun anyExternalKeyboard(): Boolean = try {
        (getSystemService(Context.INPUT_SERVICE) as? InputManager)?.inputDeviceIds
            ?.any { id ->
                InputDevice.getDevice(id)?.let { d ->
                    (d.sources and InputDevice.SOURCE_KEYBOARD) != 0 &&
                            d.isExternal && !d.isVirtual
                } == true
            } == true
    } catch (_: Exception) {
        hwKeyboardMode
    }

    private fun enterHwKeyboardMode(silent: Boolean = false) {
        if (hwKeyboardMode) return
        hwKeyboardMode = true
        // 物理键盘接管输入：收起软键盘与虚拟键盘（键事件已由 Activity 直通远端）
        hideKeyboard()
        if (!silent) toast(getString(R.string.hw_keyboard_on))
    }

    private fun exitHwKeyboardMode() {
        if (!hwKeyboardMode) return
        hwKeyboardMode = false
        toast(getString(R.string.hw_keyboard_off))
    }

    // ------------------------------------------------------------------
    // 引擎事件回调（主线程）
    // ------------------------------------------------------------------

    override fun onState(state: ConnectionEngine.State, detail: String?) {
        when (state) {
            ConnectionEngine.State.PROBING -> showStatus(getString(R.string.status_probing))
            ConnectionEngine.State.NEED_AUTH -> {
                showStatus(getString(R.string.auth_required))
                binding.authBox.visibility = View.VISIBLE
                config?.username?.let { binding.editAuthUser.setText(it) }
                config?.password?.let { binding.editAuthPass.setText(it) }
            }
            ConnectionEngine.State.CONNECTING -> showStatus(getString(R.string.status_connecting))
            ConnectionEngine.State.CONNECTED -> {
                sessionEverConnected = true
                binding.authBox.visibility = View.GONE
                showStatus(getString(R.string.status_waiting_video))
            }
            ConnectionEngine.State.RECONNECTING -> {
                val n = detail?.toIntOrNull() ?: 1
                showStatus(getString(R.string.status_reconnecting, n))
                binding.btnReconnect.visibility = View.VISIBLE
                binding.btnExit.visibility = View.VISIBLE
            }
            ConnectionEngine.State.FAILED -> {
                hideStatusProgress()
                showStatus(getString(R.string.error_connect_failed) + (detail?.let { ": $it" } ?: ""))
                binding.btnReconnect.visibility = View.VISIBLE
                binding.btnExit.visibility = View.VISIBLE
            }
            ConnectionEngine.State.DISCONNECTED -> {
                if (!isFinishing) {
                    hideStatusProgress()
                    showStatus(
                        getString(R.string.status_disconnected) + (detail?.let { " — $it" } ?: "")
                    )
                    binding.btnReconnect.visibility = View.VISIBLE
                    binding.btnExit.visibility = View.VISIBLE
                }
            }
        }
    }

    override fun onServerSettings(settings: JSONObject) {
        // 能力提示：麦克风/摄像头上行被策略锁死时置灰
        try {
            val micAllowed = when (val v = settings.opt("microphone_enabled")) {
                is Boolean -> v
                is org.json.JSONArray -> v.optBoolean(0, true)
                else -> true
            }
            val camAllowed = when (val v = settings.opt("webcam_enabled")) {
                is Boolean -> v
                is org.json.JSONArray -> v.optBoolean(0, true)
                else -> true
            }
            if (!micAllowed) {
                binding.btnMic.isEnabled = false
                binding.btnMic.alpha = 0.4f
            }
            if (!camAllowed) {
                binding.btnWebcam.isEnabled = false
                binding.btnWebcam.alpha = 0.4f
            }
        } catch (_: Exception) {
        }

        // v1.2.0：应用启动器 / 命令能力门禁（对照官方 server_settings 载荷）
        try {
            appsAvailable = settingBool(settings, "ui_sidebar_show_apps", false)
            commandEnabled = settingBool(settings, "command_enabled", false)
            appsLocalRepo = settingBool(settings, "apps_local_repo", false)
            runOnUiThread {
                binding.btnApps.visibility = if (appsAvailable) View.VISIBLE else View.GONE
                binding.btnCommand.visibility = if (commandEnabled) View.VISIBLE else View.GONE
            }
            // 已安装集合（apps_installed 可能是 ["name",...] 或 [{value:[...]}] 形态）
            val installed = when (val v = settings.opt("apps_installed")) {
                is org.json.JSONArray -> v
                is org.json.JSONObject -> v.optJSONArray("value")
                else -> null
            }
            if (installed != null) {
                val set = HashSet<String>()
                for (i in 0 until installed.length()) set.add(installed.optString(i))
                SessionBridge.installedApps = set
                SessionBridge.onInstalledChanged?.invoke()
            }
        } catch (_: Exception) {
        }
    }

    /** server_settings 的值可能是裸布尔、[bool]、或 {value:...} 三种形态 */
    private fun settingBool(o: JSONObject, key: String, def: Boolean): Boolean = try {
        when (val v = o.opt(key)) {
            is Boolean -> v
            is org.json.JSONArray -> v.optBoolean(0, def)
            is org.json.JSONObject -> v.optBoolean("value", def)
            else -> def
        }
    } catch (_: Exception) {
        def
    }

    override fun onStreamResolution(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (width == streamW && height == streamH) return
        streamW = width
        streamH = height
        applyStreamLayout()
        // 首次拿到分辨率时初始化虚拟鼠标位置（屏幕中心）
        if (!::gestures.isInitialized) return
        gestures.initCursorPosition(
            binding.streamContainer.width / 2f,
            binding.streamContainer.height / 2f
        )
    }

    override fun onVideoStarted() {
        hideStatus()
    }

    override fun onVideoStopped() { /* 保持画面，等待恢复 */ }

    override fun onAudioStarted() {
        if (!audioPlayer.started) audioPlayer.start()
        if (audioPlayer.started && audioOn) {
            audioPlayer.enabled = true
        } else if (audioPlayer.unavailableReason != null) {
            toast(getString(R.string.audio_unavailable))
        }
    }

    override fun onAudioUnavailable(reason: String?) {
        if (audioPlayer.started) audioPlayer.stop()
    }

    override fun onKilled(reason: String) {
        hideStatusProgress()
        showStatus(getString(R.string.error_server_killed, reason))
        binding.btnReconnect.visibility = View.VISIBLE
        binding.btnExit.visibility = View.VISIBLE
    }

    override fun onClipboardText(text: String) {
        lastRemoteClip = text
        if (text.isNotEmpty() && settings.clipboardRecv) {
            setLocalClipboard(text)
        }
    }

    override fun onClipboardBinary(mime: String, data: ByteArray) {
        if (!settings.clipboardRecv) {
            toast(getString(R.string.panel_clipboard_got) + " ($mime ${data.size / 1024}KB)")
            return
        }
        // 图片剪贴板 → 保存到下载目录（Android 无法直接把图片字节写入系统剪贴板）
        if (mime.startsWith("image/")) {
            val ext = when {
                mime.contains("png") -> "png"
                mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
                mime.contains("webp") -> "webp"
                else -> "img"
            }
            thread(name = "clip-img-save") {
                val saved = FilesApi.saveToDownloads(
                    this,
                    "selkies_clip_${System.currentTimeMillis()}.$ext",
                    mime
                ) { data }
                runOnUiThread {
                    toast(
                        if (saved != null) getString(R.string.clip_image_saved, saved)
                        else getString(R.string.clip_image_save_failed)
                    )
                }
            }
        } else {
            toast(getString(R.string.panel_clipboard_got) + " ($mime ${data.size / 1024}KB)")
        }
    }

    override fun onRoleCappedToViewer() {
        roleCapped = true
        input.viewOnly = true
        engine.inputAllowed = false
        toast(getString(R.string.status_viewonly))
    }

    override fun onDisplayConfig(fingerScroll: Boolean) {
        gestures.fingerScrollAvailable = fingerScroll
    }

    override fun onPrintDocument(name: String, sizeBytes: Long) {
        // 远端打印信号 → 下载 PDF → 自动打开系统打印界面
        val cfg = config ?: return
        toast(getString(R.string.print_received, name))
        PrintHelper.handle(this, cfg, name, sizeBytes)
    }

    override fun onWebcamKeyframeRequest() {
        webcamUplink?.requestKeyframe()
    }

    override fun onCaptureDemand(subject: String, on: Boolean) {
        when (subject) {
            "webcam" -> if (on) {
                if (hasPermission(Manifest.permission.CAMERA) && !camPermRefused) startWebcam()
                else if (!camPermRefused)
                    requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_PERM_CAM)
            } else {
                webcamUplink?.stop()
                binding.btnWebcam.isSelected = false
            }
            "microphone" -> if (on) {
                if (hasPermission(Manifest.permission.RECORD_AUDIO) && !micPermRefused) startMic()
                else if (!micPermRefused)
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_PERM_MIC)
            } else {
                micUplink?.stop()
                binding.btnMic.isSelected = false
            }
        }
    }

    override fun onMicrophoneDisabled() {
        micUplink?.stop()
        binding.btnMic.isSelected = false
        toast(getString(R.string.mic_disabled_remote))
    }

    override fun onWebcamDisabled() {
        webcamUplink?.stop()
        binding.btnWebcam.isSelected = false
        toast(getString(R.string.webcam_disabled_remote))
    }

    override fun onSystemCommand(action: String) {
        when {
            // system,{"action":"command_error,<text>"}
            action.startsWith("command_error,") -> {
                toast(getString(R.string.command_error) + ": " + action.removePrefix("command_error,").take(120))
            }
            // system,{"action":"command_done,<cmd>"}
            action.startsWith("command_done,") -> {
                val cmd = action.removePrefix("command_done,")
                DiagLog.i("命令完成: $cmd")
            }
            // system,{"action":"apps_installed,[...]"} —— 安装/卸载/更新后广播
            action.startsWith("apps_installed,") -> {
                try {
                    val arr = JSONArray(action.removePrefix("apps_installed,"))
                    val set = HashSet<String>()
                    for (i in 0 until arr.length()) set.add(arr.optString(i))
                    SessionBridge.installedApps = set
                    SessionBridge.onInstalledChanged?.invoke()
                    toast(getString(R.string.apps_installed_updated, set.size))
                } catch (_: Exception) {
                }
            }
            action == "reload" -> {
                // 服务端要求重载：重新请求关键帧并拉取状态
                engine.requestKeyframe()
                engine.requestClipboard()
            }
        }
    }

    override fun onStats(stats: ConnectionEngine.Stats) {
        if (binding.txtStats.visibility == View.VISIBLE) {
            binding.txtStats.text = buildString {
                appendLine("FPS ${stats.fps}  ${stats.bitrateKbps} kbps")
                appendLine("RTT ${if (stats.rttMs >= 0) "${stats.rttMs}ms" else "-"}")
                appendLine("RES ${streamW}x${streamH}")
                appendLine("FRAMES ${stats.frames}")
            }
        }
    }

    // ------------------------------------------------------------------
    // 硬件键盘 & 游戏手柄（物理键直通远端；手柄 → js 协议）
    // ------------------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event?.device != null && isGamepadDevice(event.device)) {
            if (gamepadKey(event.device, keyCode, down = true)) return true
        }
        val keysym = Keysyms.fromAndroidKeyCode(keyCode, event)
        if (keysym != null) {
            input.key(keysym, true)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (event?.device != null && isGamepadDevice(event.device)) {
            if (gamepadKey(event.device, keyCode, down = false)) return true
        }
        val keysym = Keysyms.fromAndroidKeyCode(keyCode, event)
        if (keysym != null) {
            input.key(keysym, false)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /** 游戏手柄：按钮 → js,b（标准布局索引） */
    private val gamepadButtons = mapOf(
        KeyEvent.KEYCODE_BUTTON_A to 0,
        KeyEvent.KEYCODE_BUTTON_B to 1,
        KeyEvent.KEYCODE_BUTTON_X to 2,
        KeyEvent.KEYCODE_BUTTON_Y to 3,
        KeyEvent.KEYCODE_BUTTON_L1 to 4,
        KeyEvent.KEYCODE_BUTTON_R1 to 5,
        KeyEvent.KEYCODE_BUTTON_L2 to 6,
        KeyEvent.KEYCODE_BUTTON_R2 to 7,
        KeyEvent.KEYCODE_BUTTON_SELECT to 8,
        KeyEvent.KEYCODE_BUTTON_START to 9,
        KeyEvent.KEYCODE_BUTTON_THUMBL to 10,
        KeyEvent.KEYCODE_BUTTON_THUMBR to 11,
        KeyEvent.KEYCODE_DPAD_UP to 12,
        KeyEvent.KEYCODE_DPAD_DOWN to 13,
        KeyEvent.KEYCODE_DPAD_LEFT to 14,
        KeyEvent.KEYCODE_DPAD_RIGHT to 15,
        KeyEvent.KEYCODE_BUTTON_MODE to 16
    )

    private val gamepadAnnounced = LinkedHashMap<Int, String>() // deviceId -> name

    private fun isGamepadDevice(d: InputDevice?): Boolean {
        val src = d?.sources ?: 0
        return (src and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    }

    private fun gamepadIndex(deviceId: Int): Int =
        gamepadAnnounced.keys.toList().indexOf(deviceId)

    private fun ensureGamepadAnnounced(dev: InputDevice) {
        if (gamepadAnnounced.containsKey(dev.id)) return
        if (gamepadAnnounced.size >= 4) return
        val idx = gamepadAnnounced.size
        gamepadAnnounced[dev.id] = dev.name
        engine.gamepadConnect(idx, dev.name, 4, 17)
    }

    private fun gamepadKey(dev: InputDevice, keyCode: Int, down: Boolean): Boolean {
        val button = gamepadButtons[keyCode] ?: return false
        ensureGamepadAnnounced(dev)
        val idx = gamepadIndex(dev.id)
        if (idx >= 0) engine.gamepadButton(idx, button, if (down) 1f else 0f)
        return true
    }

    /** 游戏手柄：摇杆/扳机 → js,a */
    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        if (event == null || !isGamepadDevice(event.device)) {
            return super.onGenericMotionEvent(event)
        }
        val dev = event.device ?: return super.onGenericMotionEvent(event)
        if (roleCapped || input.viewOnly) return true
        ensureGamepadAnnounced(dev)
        val idx = gamepadIndex(dev.id)
        if (idx < 0) return true
        try {
            // 标准布局轴：LX=0 LY=1 RX=2 RY=3；扳机同时作为按钮 6/7
            engine.gamepadAxis(idx, 0, deadzone(event.getAxisValue(MotionEvent.AXIS_X)))
            engine.gamepadAxis(idx, 1, deadzone(event.getAxisValue(MotionEvent.AXIS_Y)))
            engine.gamepadAxis(idx, 2, deadzone(event.getAxisValue(MotionEvent.AXIS_Z)))
            engine.gamepadAxis(idx, 3, deadzone(event.getAxisValue(MotionEvent.AXIS_RZ)))
            engine.gamepadButton(idx, 6, event.getAxisValue(MotionEvent.AXIS_LTRIGGER))
            engine.gamepadButton(idx, 7, event.getAxisValue(MotionEvent.AXIS_RTRIGGER))
        } catch (_: Exception) {
        }
        return true
    }

    private fun deadzone(v: Float): Float = if (kotlin.math.abs(v) < 0.05f) 0f else v

    // ------------------------------------------------------------------
    // 状态浮层 & 提示
    // ------------------------------------------------------------------

    private fun showStatus(text: String) {
        binding.statusOverlay.visibility = View.VISIBLE
        binding.txtStatus.text = text
    }

    private fun hideStatusProgress() {
        binding.statusProgress.visibility = View.GONE
    }

    private fun hideStatus() {
        binding.statusOverlay.visibility = View.GONE
    }

    private var toastTime = 0L
    private fun toast(text: String) {
        val now = System.currentTimeMillis()
        if (now - toastTime < 1800 && binding.txtToast.text == text) return
        toastTime = now
        binding.txtToast.text = text
        binding.txtToast.visibility = View.VISIBLE
        binding.txtToast.removeCallbacks(hideToast)
        binding.txtToast.postDelayed(hideToast, 2200)
    }

    private val hideToast = Runnable {
        binding.txtToast.visibility = View.GONE
    }

    // ------------------------------------------------------------------
    // 组合媒体接收端：视频→VideoDecoder，音频→AudioPlayer
    // ------------------------------------------------------------------

    private inner class CombinedSink : ConnectionEngine.MediaSink {
        override fun onVideoFrame(
            header: com.selkies.client.net.Protocol.VideoFrameHeader,
            data: ByteArray, payloadOffset: Int, payloadLen: Int
        ) {
            if (this@SessionActivity::videoDecoder.isInitialized) {
                videoDecoder.onVideoFrame(header, data, payloadOffset, payloadLen)
            }
        }

        override fun onJpegStripe(
            header: com.selkies.client.net.Protocol.JpegStripeHeader,
            data: ByteArray, payloadOffset: Int, payloadLen: Int
        ) {
            if (this@SessionActivity::videoDecoder.isInitialized) {
                videoDecoder.onJpegStripe(header, data, payloadOffset, payloadLen)
            }
        }

        override fun onAudioFrames(frames: List<Pair<Long, ByteArray>>) {
            if (!audioPlayer.started && audioOn && settings.autoAudio) {
                audioPlayer.start()
            }
            audioPlayer.onAudioFrames(frames)
        }

        override fun onPipelineReset() {
            if (this@SessionActivity::videoDecoder.isInitialized) {
                videoDecoder.onPipelineReset()
            }
        }

        override fun onVideoInactive() {
            if (this@SessionActivity::videoDecoder.isInitialized) {
                videoDecoder.onVideoInactive()
            }
        }
    }
}

package com.selkies.client.ui.session

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.selkies.client.R
import com.selkies.client.data.ServerConfig
import com.selkies.client.data.ServerRepository
import com.selkies.client.data.SettingsStore
import com.selkies.client.databinding.ActivitySessionBinding
import com.selkies.client.input.GestureHandler
import com.selkies.client.input.InputController
import com.selkies.client.input.Keysyms
import com.selkies.client.media.AudioPlayer
import com.selkies.client.media.VideoDecoder
import com.selkies.client.net.ConnectionEngine
import com.selkies.client.net.EndpointProber
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/**
 * 远程会话页 —— 引擎 / 解码器 / 输入系统的装配现场。
 *
 * 生命周期与防闪退要点：
 *  - Activity 自行处理旋转等配置变更（manifest configChanges），
 *    全程只创建一次引擎与解码器 —— 旋转绝不重建会话；
 *  - Surface 销毁（切后台/熄屏）只解绑解码器，回前台自动重新绑定，
 *    并请求关键帧恢复画面；
 *  - onStart/onStop 控制 STOP_VIDEO/START_VIDEO（省流），onDestroy 全量清理；
 *  - NEED_AUTH 弹内嵌凭据表单；FAILED/重连有明确状态提示与手动重连按钮。
 */
class SessionActivity : AppCompatActivity(), ConnectionEngine.Listener {

    companion object {
        const val EXTRA_SERVER_ID = "server_id"
        private const val TAG = "SessionActivity"
        private const val REQ_UPLOAD = 4271
        private const val UPLOAD_CHUNK = 48L * 1024 * 1024 // 48MB/片（低于 CF 100MB 上限）
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
        setupAuthBox()

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
        binding.btnUpload.setOnClickListener { pickUploadFile() }

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

        binding.btnReconnect.setOnClickListener {
            binding.btnReconnect.visibility = View.GONE
            engine.connect()
        }
        binding.btnExit.setOnClickListener { finish() }
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
    // 剪贴板双向同步
    // ------------------------------------------------------------------

    private fun showClipboardDialog() {
        val items = arrayOf(
            getString(R.string.panel_clipboard_sent),
            getString(R.string.panel_clipboard_got)
        )
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.panel_clipboard_sync)
            .setItems(items) { _, which ->
                if (which == 0) sendLocalClipboard() else engine.requestClipboard()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 本地 → 远端 */
    private fun sendLocalClipboard() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
            if (text.isEmpty()) {
                toast(getString(R.string.panel_clipboard_sent) + " (empty)")
                return
            }
            engine.sendClipboardText(text)
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
    // 文件上传（POST /api/upload，X-Upload-Path 头）
    // ------------------------------------------------------------------

    private fun pickUploadFile() {
        if (roleCapped || input.viewOnly) {
            toast(getString(R.string.status_viewonly))
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            startActivityForResult(intent, REQ_UPLOAD)
        } catch (e: Exception) {
            Log.w(TAG, "no file picker: ${e.message}")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_UPLOAD) return
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        uploadFile(uri)
    }

    /** 上传到远端 ~/Uploads/<文件名>；>48MB 自动分片（兼容 CF 100MB 上限） */
    private fun uploadFile(uri: Uri) {
        val cfg = config ?: return
        toast(getString(R.string.panel_upload) + "…")
        thread(name = "upload") {
            try {
                val name = queryDisplayName(uri) ?: "upload_${System.currentTimeMillis()}"
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("cannot read file")

                val destRel = "Uploads/$name"
                val urlEncoded = java.net.URLEncoder.encode(destRel, "UTF-8")
                    .replace("+", "%20")
                val base = buildUploadBase(cfg)
                val auth = if (!cfg.username.isNullOrEmpty()) {
                    "Basic " + java.util.Base64.getEncoder()
                        .encodeToString("${cfg.username}:${cfg.password ?: ""}".toByteArray())
                } else null

                val client = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .writeTimeout(120, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .apply { if (cfg.trustAllCerts) EndpointProber.applyTrustAll(this) }
                    .build()

                val total = bytes.size.toLong()
                val mediaType = "application/octet-stream".toMediaType()

                if (total <= UPLOAD_CHUNK) {
                    // 单次 POST（官方 plain 形态）
                    val req = Request.Builder()
                        .url("$base/api/upload")
                        .header("Content-Type", "application/octet-stream")
                        .header("X-Upload-Path", urlEncoded)
                        .apply { if (auth != null) header("Authorization", auth) }
                        .post(bytes.toRequestBody(mediaType))
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            throw IllegalStateException(
                                "HTTP ${resp.code}: ${resp.body?.string()?.take(120) ?: ""}"
                            )
                        }
                    }
                } else {
                    // 分片（官方 chunked 形态）
                    val uploadId = java.util.UUID.randomUUID().toString().substring(0, 16)
                    var off = 0L
                    while (off < total) {
                        val end = minOf(off + UPLOAD_CHUNK, total)
                        val slice = bytes.copyOfRange(off.toInt(), end.toInt())
                        val isFinal = end >= total
                        val req = Request.Builder()
                            .url("$base/api/upload")
                            .header("Content-Type", "application/octet-stream")
                            .header("X-Upload-Path", urlEncoded)
                            .header("X-Upload-Id", uploadId)
                            .header("X-Upload-Offset", off.toString())
                            .header("X-Upload-Total", total.toString())
                            .header("X-Upload-Final", if (isFinal) "1" else "0")
                            .apply { if (auth != null) header("Authorization", auth) }
                            .post(slice.toRequestBody(mediaType))
                            .build()
                        client.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} @ $off")
                        }
                        val pct = (end * 100 / total).toInt()
                        runOnUiThread { toast(getString(R.string.uploading, pct, name)) }
                        off = end
                    }
                }
                runOnUiThread { toast(getString(R.string.upload_done, destRel)) }
            } catch (e: Exception) {
                Log.w(TAG, "upload failed: ${e.message}")
                runOnUiThread { toast(getString(R.string.upload_failed, e.message ?: "?")) }
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun buildUploadBase(cfg: ServerConfig): String = buildString {
        append(if (cfg.https) "https://" else "http://")
        append(cfg.host)
        if (cfg.port != 80 && cfg.port != 443) append(":").append(cfg.port)
        append(cfg.normalizedPath)
    }

    // ------------------------------------------------------------------
    // 虚拟键盘
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

        // 文字输入行：IME 输入 → 回车/发送 → co,end
        val sendAction = {
            val text = binding.virtualKeyboardInclude.kbTextInput.text?.toString() ?: ""
            if (text.isNotEmpty()) {
                input.commitText(text)
                binding.virtualKeyboardInclude.kbTextInput.setText("")
            }
        }
        binding.virtualKeyboardInclude.kbTextInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND ||
                (event?.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                sendAction()
                true
            } else false
        }
        binding.virtualKeyboardInclude.kbTextSend.setOnClickListener { sendAction() }
    }

    private fun showKeyboard() {
        keyboardVisible = true
        binding.virtualKeyboardInclude.root.visibility = View.VISIBLE
        binding.virtualKeyboardInclude.kbTextInput.requestFocus()
        val imm =
            getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(binding.virtualKeyboardInclude.kbTextInput, 0)
    }

    private fun hideKeyboard() {
        keyboardVisible = false
        binding.virtualKeyboardInclude.root.visibility = View.GONE
        if (this::input.isInitialized) input.releaseModifiers()
        try {
            val imm =
                getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(binding.virtualKeyboardInclude.kbTextInput.windowToken, 0)
        } catch (_: Exception) {
        }
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

    override fun onServerSettings(settings: JSONObject) { /* 能力提示，暂不消费 */ }

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
        if (text.isNotEmpty()) setLocalClipboard(text)
    }

    override fun onClipboardBinary(mime: String, data: ByteArray) {
        toast(getString(R.string.panel_clipboard_got) + " ($mime ${data.size / 1024}KB)")
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
    // 硬件键盘 & 游戏手柄
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

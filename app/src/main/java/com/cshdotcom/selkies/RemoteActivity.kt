package com.cshdotcom.selkies

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import android.view.KeyEvent
import android.view.MotionEvent
import com.cshdotcom.selkies.connection.EndpointProber
import com.cshdotcom.selkies.connection.SelkiesClient
import com.cshdotcom.selkies.input.CursorOverlayView
import com.cshdotcom.selkies.input.GamepadBridge
import com.cshdotcom.selkies.input.TouchGestureView
import com.cshdotcom.selkies.input.VirtualKeyboardView
import com.cshdotcom.selkies.media.AudioPlayer
import com.cshdotcom.selkies.media.VideoDecoder
import com.cshdotcom.selkies.model.Prefs
import com.cshdotcom.selkies.model.ServerStore
import com.cshdotcom.selkies.util.ClipboardUtil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * 远程会话页：视频渲染(TextureView 矩阵缩放/平移)、手势输入、虚拟键盘、
 * 工具栏（关键帧/声音/模式/剪贴板/上传/适配/统计/断开/隐藏）、状态与统计、
 * 剪贴板双向同步、文件上传、手柄桥接、认证弹窗、横竖屏自适应。
 */
class RemoteActivity : AppCompatActivity(), SelkiesClient.Listener, TouchGestureView.Callback {

    private lateinit var prefs: Prefs
    private lateinit var store: ServerStore
    private var config: com.cshdotcom.selkies.model.ServerConfig? = null

    private var client: SelkiesClient? = null
    private var gamepad: GamepadBridge? = null

    // ---- 视图 ----
    private lateinit var video: TextureView
    private lateinit var cursor: CursorOverlayView
    private lateinit var gesture: TouchGestureView
    private lateinit var vkbd: VirtualKeyboardView
    private lateinit var tvStatus: TextView
    private lateinit var tvStats: TextView
    private lateinit var loading: View
    private lateinit var toolbar: View
    private var loadingText: TextView? = null

    // ---- 几何 ----
    private var remoteW = 1280
    private var remoteH = 720
    private var baseScale = 1f       // 适配缩放
    private var zoomMult = 1f        // 手势缩放倍率
    private var panX = 0f
    private var panY = 0f

    private var clipboardGuard = false    // 防回环：本地剪贴板变化来源标记
    private var audioOn = false
    private var statsVisible = false
    private var viewOnlySession = false

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { doUpload(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote)
        prefs = Prefs(this)
        store = ServerStore(java.io.File(filesDir, "servers.json"))
        config = store.get(intent.getStringExtra("serverId")) ?: store.default()

        if (prefs.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        video = findViewById(R.id.video)
        cursor = findViewById(R.id.cursor)
        gesture = findViewById(R.id.gesture)
        vkbd = findViewById(R.id.vkbd)
        tvStatus = findViewById(R.id.tvStatus)
        tvStats = findViewById(R.id.tvStats)
        loading = findViewById(R.id.loadingOverlay)
        toolbar = findViewById(R.id.toolbar)
        loadingText = findViewById(R.id.tvLoading)

        gesture.callback = this
        applyGesturePrefs()

        // 视频表面就绪 → 挂解码器（首关键帧自动启动）
        video.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                attachDecoder(Surface(st))
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { applyFit() }
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                client?.videoDecoder?.release()
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }

        bindToolbar()
        startSession()
        hideSystemUi()
    }

    private fun applyGesturePrefs() {
        gesture.tapSlopPx = 18 + (10 - prefs.touchSensitivity) * 3
        gesture.longPressAction = prefs.longPressAction
        gesture.naturalScroll = prefs.naturalScroll
        gesture.scrollStepPx = prefs.scrollStepPx
    }

    // =====================================================================
    // 会话
    // =====================================================================
    private fun startSession() {
        val cfg = config ?: run { toast(getString(R.string.err_no_config)); finish(); return }
        viewOnlySession = cfg.viewOnly
        // 记录目标分辨率（竖屏时交换宽高，更匹配手机）
        val dm = resources.displayMetrics
        val land = dm.widthPixels > dm.heightPixels
        val w = (if (land) dm.widthPixels else dm.heightPixels)
        val h = (if (land) dm.heightPixels else dm.widthPixels)
        prefs.matchResolutionWidth = w
        prefs.matchResolutionHeight = h

        client = SelkiesClient(lifecycleScope, this)
        gesture.client = client
        vkbd.client = client
        gamepad = GamepadBridge(client!!)
        client?.connect(cfg, prefs)
    }

    private fun attachDecoder(surface: Surface) {
        val c = client ?: return
        c.videoDecoder = VideoDecoder(
            surface,
            onLostFrame = { id -> c.lostFrame(id) },
            onFormatChanged = { w, h -> runOnUiThread { onResolution(w, h) } }
        )
    }

    // =====================================================================
    // SelkiesClient.Listener
    // =====================================================================
    override fun onStatus(state: SelkiesClient.State, message: String) {
        runOnUiThread {
            tvStatus.text = message
            val streaming = state == SelkiesClient.State.STREAMING
            loading.visibility = if (streaming || state == SelkiesClient.State.CLOSED) View.GONE else View.VISIBLE
            loadingText?.text = message
            if (state == SelkiesClient.State.CLOSED) {
                tvStatus.text = "ℹ $message（可返回重连）"
            }
        }
    }

    /** 挂起式认证弹窗（不阻塞主线程） */
    override suspend fun onAuthChallenge(realm: String?): Pair<String, String>? =
        suspendCancellableCoroutine { cont ->
            runOnUiThread {
                val box = layoutInflater.inflate(R.layout.dialog_auth, null)
                val user = box.findViewById<TextView>(R.id.authUser)
                val pass = box.findViewById<TextView>(R.id.authPass)
                config?.username?.takeIf { it.isNotEmpty() }?.let { user.text = it }
                val dlg = AlertDialog.Builder(this)
                    .setTitle(getString(R.string.auth_title, realm ?: ""))
                    .setView(box)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val u = user.text.toString().trim()
                        val p = pass.text.toString()
                        cont.resume(Pair(u, p)) {}
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> cont.resume(null) {} }
                    .setOnDismissListener { if (cont.isActive) cont.resume(null) {} }
                    .show()
                cont.invokeOnCancellation { dlg.dismiss() }
            }
        }

    override fun onResolution(w: Int, h: Int) {
        runOnUiThread {
            if (w != remoteW || h != remoteH) {
                remoteW = w; remoteH = h
                zoomMult = 1f; panX = 0f; panY = 0f
                applyFit()
            }
        }
    }

    override fun onCursor(b64Png: String?, hotX: Int, hotY: Int, w: Int, h: Int) {
        runOnUiThread {
            if (b64Png == null) { cursor.setCursor(null, 0, 0); return@runOnUiThread }
            try {
                val bytes = Base64.decode(b64Png, Base64.DEFAULT)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                cursor.setCursor(bmp, hotX, hotY)
            } catch (e: Exception) {}
        }
    }

    override fun onStats(fps: Int, bitrateKbps: Int, rttMs: Int, pending: Int) {
        runOnUiThread {
            tvStats.visibility = if (statsVisible) View.VISIBLE else View.GONE
            tvStats.text = getString(R.string.fmt_stats, remoteW, remoteH, fps, bitrateKbps, rttMs, pending)
        }
    }

    override fun onRemoteClipboard(text: String) {
        if (!prefs.clipboardSync || text.isEmpty()) return
        clipboardGuard = true
        runOnUiThread {
            ClipboardUtil.set(this, text)
            toast(getString(R.string.clipboard_in))
        }
    }

    override fun onError(title: String, detail: String) {
        runOnUiThread {
            AlertDialog.Builder(this).setTitle(title).setMessage(detail)
                .setPositiveButton(android.R.string.ok, null).show()
        }
    }

    override fun onDisconnected(reason: String) { /* 状态条已提示；自动重连在 client 内处理 */ }

    override fun onVideoActive(active: Boolean) {
        runOnUiThread { if (active) { loading.visibility = View.GONE } }
    }

    override fun onAudioActive(active: Boolean, reason: String?) {
        runOnUiThread {
            audioOn = active
            val btn = findViewById<Button>(R.id.btnAudio)
            btn.setText(if (active) R.string.btn_audio_on else R.string.btn_audio_off)
            reason?.let { toast(it) }
        }
    }

    override fun onRole(role: String, slot: Int?) {
        runOnUiThread {
            if (role == "viewer") {
                viewOnlySession = true
                client?.inputBlocked = true
                tvStatus.text = "以 viewer（仅查看）角色连接"
                findViewById<Button>(R.id.btnMode).setText(R.string.btn_mode_view)
            }
        }
    }

    override fun onRumble(strong: Float, weak: Float, durationMs: Int) {
        runOnUiThread {
            try {
                val v = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    v.vibrate(android.os.VibrationEffect.createOneShot(
                        durationMs.coerceAtLeast(20).toLong(),
                        ((strong + weak) / 2f).coerceIn(0f, 1f).let { amp ->
                            (255 * amp).roundToInt().toByte().toInt().coerceAtLeast(1)
                        }))
                } else @Suppress("DEPRECATION") v.vibrate(durationMs.coerceAtLeast(20).toLong())
            } catch (e: Exception) {}
        }
    }

    // =====================================================================
    // TouchGestureView.Callback
    // =====================================================================
    override fun mapToRemote(x: Float, y: Float): Pair<Int, Int> {
        val total = baseScale * zoomMult
        val rx = ((x - panX) / total).roundToInt().coerceIn(0, remoteW - 1)
        val ry = ((y - panY) / total).roundToInt().coerceIn(0, remoteH - 1)
        return rx to ry
    }

    override fun onZoom(factor: Float, pivotX: Float, pivotY: Float) {
        val newZoom = (zoomMult * factor).coerceIn(0.5f, 8f)
        // 围绕枢轴缩放：保持枢轴下的远端坐标不动
        val total = baseScale * zoomMult
        val rx = (pivotX - panX) / total
        val ry = (pivotY - panY) / total
        zoomMult = newZoom
        val newTotal = baseScale * zoomMult
        panX = pivotX - rx * newTotal
        panY = pivotY - ry * newTotal
        applyMatrix()
    }

    /** 适配缩放（铺满或 contain）+ 平移 → TextureView 矩阵 */
    private fun applyFit() {
        zoomMult = 1f
        applyMatrix()
    }

    private fun applyMatrix() {
        val vw = video.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val vh = video.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        baseScale = minOf(vw.toFloat() / remoteW, vh.toFloat() / remoteH)
        val total = baseScale * zoomMult
        if (panX == 0f && panY == 0f && zoomMult == 1f) {
            // 初始居中
            panX = (vw - remoteW * total) / 2f
            panY = (vh - remoteH * total) / 2f
        }
        val m = Matrix()
        m.postScale(total, total)
        m.postTranslate(panX, panY)
        video.setTransform(m)
        cursor.setTransform(total, panX, panY)
    }

    // =====================================================================
    // 工具栏
    // =====================================================================
    @SuppressLint("SetTextI18n")
    private fun bindToolbar() {
        fun btn(id: Int, label: Int, onClick: (Button) -> Unit) {
            val b = findViewById<Button>(id)
            b.setText(label)
            b.setOnClickListener { onClick(b) }
        }
        btn(R.id.btnKeyboard, R.string.btn_keyboard) { toggleKeyboard() }
        btn(R.id.btnIdr, R.string.btn_idr) { client?.requestKeyframe(); toast(getString(R.string.idr_sent)) }
        btn(R.id.btnAudio, R.string.btn_audio_off) { toggleAudio() }
        btn(R.id.btnMode, if (viewOnlySession) R.string.btn_mode_view else R.string.btn_mode_control) { toggleMode() }
        btn(R.id.btnClipboard, R.string.btn_clipboard) { clipboardAction() }
        btn(R.id.btnUpload, R.string.btn_upload) { filePicker.launch(arrayOf("*/*")) }
        btn(R.id.btnFit, R.string.btn_fit) { applyFit(); toast(getString(R.string.fit_applied)) }
        btn(R.id.btnStats, R.string.btn_stats) { statsVisible = !statsVisible; tvStats.visibility = if (statsVisible) View.VISIBLE else View.GONE }
        btn(R.id.btnHide, R.string.btn_hide) {
            toolbar.visibility = View.GONE
            tvStatus.text = getString(R.string.panel_hidden_hint)
        }
        btn(R.id.btnDisconnect, R.string.btn_disconnect) { confirmDisconnect() }
        // 点状态条重新显示工具栏
        tvStatus.setOnClickListener { toolbar.visibility = View.VISIBLE }
        findViewById<Button>(R.id.btnMode).setText(if (viewOnlySession) R.string.btn_mode_view else R.string.btn_mode_control)
    }

    private fun toggleKeyboard() {
        if (vkbd.visibility == View.VISIBLE) {
            vkbd.visibility = View.GONE
            vkbd.resetLocal()
        } else {
            vkbd.visibility = View.VISIBLE
            if (prefs.autoShowKeyboard) vkbd.focusTextInput()
        }
    }

    private fun toggleAudio() {
        val c = client ?: return
        if (audioOn) { c.stopAudio() } else { c.maybeStartAudio(); ensureAudioPlayer() }
    }

    private fun ensureAudioPlayer() {
        val c = client ?: return
        if (c.audioPlayer == null) {
            val player = AudioPlayer()
            player.volumePercent = prefs.audioVolume
            c.audioPlayer = player
        }
    }

    private fun toggleMode() {
        viewOnlySession = !viewOnlySession
        client?.inputBlocked = viewOnlySession
        if (!viewOnlySession) vkbd.visibility = View.GONE
        findViewById<Button>(R.id.btnMode).setText(if (viewOnlySession) R.string.btn_mode_view else R.string.btn_mode_control)
        toast(getString(if (viewOnlySession) R.string.mode_switched_view else R.string.mode_switched_control))
    }

    /** 剪贴板动作：当前文本 + 双向同步按钮 */
    private fun clipboardAction() {
        val c = client ?: return
        val current = ClipboardUtil.get(this) ?: ""
        AlertDialog.Builder(this)
            .setTitle(R.string.btn_clipboard)
            .setMessage(getString(R.string.clipboard_prompt, current.take(400)))
            .setPositiveButton(R.string.clip_push) { _, _ ->
                current.takeIf { it.isNotEmpty() }?.let { c.sendClipboardText(it); toast(getString(R.string.clipboard_out)) }
            }
            .setNeutralButton(R.string.clip_pull) { _, _ -> c.requestClipboard() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDisconnect() {
        AlertDialog.Builder(this)
            .setTitle(R.string.btn_disconnect)
            .setMessage(R.string.confirm_disconnect)
            .setPositiveButton(android.R.string.ok) { _, _ -> client?.disconnect(); finish() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // =====================================================================
    // 文件上传
    // =====================================================================
    private fun doUpload(uri: Uri) {
        val c = client ?: return
        val name = queryDisplayName(uri) ?: "upload.bin"
        toast(getString(R.string.upload_started, name))
        c.uploadFile(contentResolver, uri, name,
            onProgress = { sent, total ->
                runOnUiThread {
                    tvStatus.text = if (total > 0) "上传 $name … ${sent * 100 / total}%" else "上传 $name … ${sent / 1024}KB"
                }
            },
            onDone = { ok, msg ->
                runOnUiThread {
                    toast(msg)
                    tvStatus.text = if (ok) "上传完成：$name" else "上传失败"
                }
            })
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { cur ->
            val idx = cur.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cur.moveToFirst()) cur.getString(idx) else null
        }
    } catch (e: Exception) { null }

    // =====================================================================
    // 剪贴板监听（双向同步）
    // =====================================================================
    private val clipboardListener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
        if (clipboardGuard) { clipboardGuard = false; return@OnPrimaryClipChangedListener }
        val c = client ?: return@OnPrimaryClipChangedListener
        if (!prefs.clipboardSync || viewOnlySession) return@OnPrimaryClipChangedListener
        val text = ClipboardUtil.get(this) ?: return@OnPrimaryClipChangedListener
        if (text.isNotEmpty()) c.sendClipboardText(text)
    }

    override fun onResume() {
        super.onResume()
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).addPrimaryClipChangedListener(clipboardListener)
        hideSystemUi()
    }

    override fun onPause() {
        super.onPause()
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).removePrimaryClipChangedListener(clipboardListener)
    }

    override fun onDestroy() {
        client?.disconnect()
        client?.releaseMedia()
        gamepad?.release()
        super.onDestroy()
    }

    // =====================================================================
    // 手柄/按键转发
    // =====================================================================
    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean =
        (gamepad?.onMotionEvent(e) ?: false) || super.dispatchGenericMotionEvent(e)

    override fun dispatchKeyEvent(e: KeyEvent): Boolean =
        (gamepad?.onKeyEvent(e) ?: false) || super.dispatchKeyEvent(e)

    /** 音量键调节远端音频音量 */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            prefs.audioVolume = (prefs.audioVolume + 10).coerceAtMost(100)
            client?.audioPlayer?.volumePercent = prefs.audioVolume
            toast(getString(R.string.fmt_volume, prefs.audioVolume)); return true
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            prefs.audioVolume = (prefs.audioVolume - 10).coerceAtLeast(0)
            client?.audioPlayer?.volumePercent = prefs.audioVolume
            toast(getString(R.string.fmt_volume, prefs.audioVolume)); return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)
    }

    /** 旋转（manifest 已声明 configChanges）：重新适配 + 可选同步远端分辨率 */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyFit()
        if (prefs.matchResolution && client?.state == SelkiesClient.State.STREAMING) {
            val dm = resources.displayMetrics
            val land = dm.widthPixels > dm.heightPixels
            val w = if (land) dm.widthPixels else dm.heightPixels
            val h = if (land) dm.heightPixels else dm.widthPixels
            if (w != remoteW || h != remoteH) client?.setResolution(w, h)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

package com.cshdotcom.selkies.connection

import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import com.cshdotcom.selkies.model.Prefs
import com.cshdotcom.selkies.model.ServerConfig
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

/**
 * Selkies 2.0 原生客户端核心：
 * - 探测（301 跟随 / Basic 认证 / 路径回退 / Cloudflare 头模拟）
 * - WebSocket 会话（SETTINGS → MODE → START_VIDEO / START_AUDIO → 流）
 * - 视频帧(0x04)→MediaCodec，音频(0x01)→Opus 解码，0x05 gzip 控制文本
 * - 输入（kd/ku/kh/m/m2/co,end）、剪贴板（cw/分块/接收全家桶）、文件上传、手柄
 * - CLIENT_FRAME_ACK 回压、_f/_l 指标、_stats 订阅、自动重连
 * 全部消息格式对照官方 selkies-ws-core.js 校对，见 docs/PROTOCOL.md。
 */
class SelkiesClient(
    private val scope: CoroutineScope,
    private val listener: Listener
) {
    interface Listener {
        /** 连接状态与用户可读信息 */
        fun onStatus(state: State, message: String)
        /** 401 Basic 质询：弹账号密码框并挂起等待结果。返回 null 表示用户取消（挂起函数，不阻塞主线程） */
        suspend fun onAuthChallenge(realm: String?): Pair<String, String>?
        /** 流分辨率变化（帧头/服务端通知） */
        fun onResolution(w: Int, h: Int)
        /** 服务端光标图片（curdata 可能为空=隐藏） */
        fun onCursor(b64Png: String?, hotX: Int, hotY: Int, w: Int, h: Int)
        /** 统计刷新 */
        fun onStats(fps: Int, bitrateKbps: Int, rttMs: Int, pending: Int)
        /** 服务端剪贴板内容（文本） */
        fun onRemoteClipboard(text: String)
        /** 需要用户看到的错误 */
        fun onError(title: String, detail: String)
        /** 连接断开（非用户主动） */
        fun onDisconnected(reason: String)
        /** 视频管道启停 */
        fun onVideoActive(active: Boolean)
        /** 音频状态 */
        fun onAudioActive(active: Boolean, reason: String?)
        /** 手柄震动 */
        fun onRumble(strong: Float, weak: Float, durationMs: Int)
        /** 角色更新（controller/viewer） */
        fun onRole(role: String, slot: Int?)
    }

    enum class State { IDLE, PROBING, AUTH, CONNECTING, CONNECTED, STREAMING, CLOSED }

    // ---- 会话 ----
    private var http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10L, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(0L, java.util.concurrent.TimeUnit.MILLISECONDS)   // WS 流不超时
        .pingInterval(20L, java.util.concurrent.TimeUnit.SECONDS)      // WS 协议层保活
        .retryOnConnectionFailure(true)
        .build()
    private var ws: WebSocket? = null
    private var config: ServerConfig? = null
    private var prefs: Prefs? = null
    var state: State = State.IDLE
        private set

    /** 仅查看模式：拦截一切输入发送（需求：只能观看画面） */
    @Volatile var inputBlocked: Boolean = false

    // ---- 协议状态 ----
    @Volatile private var modeReceived = false
    @Volatile private var serverSettingsReceived = false
    @Volatile private var pendingAudioStart = false
    @Volatile private var lastVideoFrameId = -1
    @Volatile private var lastVideoFrameAt = 0L
    @Volatile private var lastVideoW = 0
    @Volatile private var lastVideoH = 0
    @Volatile private var gzTxEnabled = false
    @Volatile private var userDisconnect = false
    private var reconnectAttempts = 0
    private var creds: EndpointProber.Creds? = null
    private var currentWsUrl: HttpUrl? = null

    // 键盘按住状态（kh 心跳）
    private val heldKeys = LinkedHashSet<Int>()
    @Volatile private var currentMask = 0

    // 输入序号（m 消息可选 seq，防乱序）
    private var pointerSeq = 0L

    // 剪贴板接收组装
    private var clipBuf = ByteArrayOutputStream()
    private var clipMime = "text/plain"
    private var clipExpected = 0
    private var clipIsCacheOnly = false

    // 统计
    private var framesThisSec = 0
    private var bytesThisSec = 0L
    private var serverRttMs = 0
    private var serverBitrateKbps = 0
    private var serverPending = 0

    // 媒体
    var videoDecoder: com.cshdotcom.selkies.media.VideoDecoder? = null
    var audioPlayer: com.cshdotcom.selkies.media.AudioPlayer? = null

    private var ackJob: Job? = null
    private var metricsJob: Job? = null
    private var heartbeatJob: Job? = null

    // =====================================================================
    // 连接生命周期
    // =====================================================================

    fun connect(cfg: ServerConfig, p: Prefs) {
        userDisconnect = false
        config = cfg
        prefs = p
        inputBlocked = cfg.viewOnly
        creds = if (cfg.hasCredentials) EndpointProber.Creds(cfg.username, cfg.password()) else null
        reconnectAttempts = 0
        scope.launch(Dispatchers.IO) { runConnect() }
    }

    private suspend fun runConnect() {
        val cfg = config ?: return
        val p = prefs ?: return
        setState(State.PROBING, "正在探测服务器端点…")
        val scheme = if (cfg.https) "https" else "http"
        val base = try {
            HttpUrl.Builder().scheme(scheme).host(cfg.host).port(cfg.port).build()
        } catch (e: IllegalArgumentException) {
            listener.onError("地址无效", "主机名或端口不合法：${cfg.host}:${cfg.port}")
            setState(State.CLOSED, "地址无效")
            return
        }
        var outcome: EndpointProber.Outcome
        try {
            outcome = EndpointProber.resolve(http, base, creds)
        } catch (e: Exception) {
            listener.onError("无法连接", "网络错误：${e.message}")
            setState(State.CLOSED, "网络错误")
            return
        }

        // 401 → 弹窗取凭据 → 复测
        if (outcome is EndpointProber.Outcome.NeedAuth) {
            setState(State.AUTH, "服务器要求 Basic 认证（realm=${outcome.realm ?: "?"}）")
            val pair = listener.onAuthChallenge(outcome.realm) ?: run {
                setState(State.CLOSED, "已取消认证")
                return
            }
            creds = EndpointProber.Creds(pair.first, pair.second)
            if (cfg.savePassword) { cfg.username = pair.first; cfg.setPassword(pair.second) }
            outcome = EndpointProber.resolve(http, base, creds)
            if (outcome is EndpointProber.Outcome.NeedAuth) {
                listener.onError("认证失败", "用户名或密码错误（401）。")
                setState(State.CLOSED, "认证失败")
                return
            }
        }
        when (outcome) {
            is EndpointProber.Outcome.Failed -> {
                listener.onError("无法连接", outcome.reason)
                setState(State.CLOSED, outcome.reason)
                return
            }
            is EndpointProber.Outcome.CloudflareBlocked -> {
                listener.onError("Cloudflare 拦截（HTTP ${outcome.httpCode}）",
                    "请求头已被 Cloudflare 拒绝。请确认：\n1. Cloudflare 已开启 WebSocket 支持（Network→WebSockets）\n2. 访问的域名/端口正确（CF 只代理标准端口）\n3. SSL 模式为 Full，且未开启错误的重定向规则")
                setState(State.CLOSED, "Cloudflare 400")
                return
            }
            is EndpointProber.Outcome.Ready -> {
                currentWsUrl = outcome.wsUrl
                openSocket(outcome.wsUrl, outcome.origin)
                return
            }
            is EndpointProber.Outcome.NeedAuth -> { /* unreachable */ }
        }
    }

    private fun openSocket(wsUrl: HttpUrl, origin: String) {
        setState(State.CONNECTING, "正在建立 WebSocket 连接…")
        val cfg = config
        val headers = Protocol.browserHeaders(origin).toMutableMap()
        creds?.let { headers["Authorization"] = "Basic " + Base64.encodeToString("${it.user}:${it.password}".toByteArray(), Base64.NO_WRAP) }
        val req = Request.Builder().url(wsUrl).apply { for ((k, v) in headers) header(k, v) }.build()
        ws = http.newWebSocket(req, SocketListener(wsUrl, origin))
    }

    private inner class SocketListener(val wsUrl: HttpUrl, val origin: String) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            reconnectAttempts = 0
            setState(State.CONNECTED, "连接已建立，发送初始设置…")
            // ---- 官方 onSocketOpen 时序 ----
            send("_gz,1")                       // gzip 能力协商
            sendInitialSettings()
            send("cr")                          // cache-only 剪贴板快照
            send("_stats,1")                    // 统计订阅
            startTimers()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            dispatchText(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            onBinary(bytes.toByteArray())
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val code = response?.code
            if (code == 401) {
                // WS 握手 401：走认证流程后重连
                scope.launch(Dispatchers.IO) {
                    setState(State.AUTH, "WebSocket 握手返回 401，请求凭据…")
                    val pair = listener.onAuthChallenge(null)
                    if (pair == null) { setState(State.CLOSED, "已取消认证"); return@launch }
                    creds = EndpointProber.Creds(pair.first, pair.second)
                    val cfg = config
                    if (cfg != null && cfg.savePassword) { cfg.username = pair.first; cfg.setPassword(pair.second) }
                    currentWsUrl?.let { openSocket(it, originOf()) }
                }
                return
            }
            if (code in 301..308) {
                // 手动跟随重定向（OkHttp WS 不跟随 3xx —— 修复 BUG#1）
                val loc = response?.header("Location")
                val next = loc?.let { wsUrl.resolve(it) }
                if (next != null && (next.scheme == "ws" || next.scheme == "wss" || next.scheme == "http" || next.scheme == "https")) {
                    scope.launch(Dispatchers.IO) {
                        setState(State.CONNECTING, "跟随重定向 → ${next.redact()}…")
                        val fixed = EndpointProber.toWs(EndpointProber.toHttp(next))
                        currentWsUrl = fixed
                        delay(150)
                        openSocket(fixed, EndpointProber.originOf(EndpointProber.toHttp(fixed)))
                    }
                    return
                }
            }
            handleDisconnect("连接失败：${t.message ?: "网络错误"}（HTTP $code）")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            handleDisconnect("连接已关闭（$code ${reason.ifBlank { "" }}）")
        }

        private fun originOf(): String = EndpointProber.originOf(EndpointProber.toHttp(currentWsUrl ?: wsUrl))
    }

    private fun handleDisconnect(reason: String) {
        stopTimers()
        clearInputState()
        ws = null
        if (userDisconnect) {
            setState(State.CLOSED, "已断开")
            return
        }
        val auto = prefs?.autoReconnect == true
        if (auto && reconnectAttempts < 5) {
            reconnectAttempts++
            val wait = minOf(1000L * (1L shl (reconnectAttempts - 1)), 10000L) // 1,2,4,8,10s
            setState(State.PROBING, "连接断开：$reason\n${wait / 1000.0}s 后第 $reconnectAttempts 次重连…")
            listener.onDisconnected(reason)
            scope.launch(Dispatchers.IO) { delay(wait); if (!userDisconnect) runConnect() }
        } else {
            setState(State.CLOSED, "已断开：$reason")
            listener.onDisconnected(reason)
        }
    }

    fun disconnect() {
        userDisconnect = true
        stopTimers()
        clearInputState()
        try { ws?.close(1000, "client disconnect") } catch (e: Exception) {}
        ws = null
        setState(State.CLOSED, "已手动断开")
    }

    private fun setState(s: State, msg: String) {
        state = s
        scope.launch(Dispatchers.Main) { listener.onStatus(s, msg) }
    }

    // =====================================================================
    // 初始 SETTINGS（对齐官方 onSocketOpen 的字段与类型）
    // =====================================================================
    private fun sendInitialSettings() {
        val p = prefs ?: return
        val metrics = android.util.DisplayMetrics()
        // 无 Activity 上下文：用 1280x720 兜底，由 RemoteActivity 的真实屏幕在连接前写入 prefs
        val w = p.matchResolutionWidth.takeIf { it > 0 } ?: 1280
        val h = p.matchResolutionHeight.takeIf { it > 0 } ?: 720
        val json = JSONObject().apply {
            put("manual_resolution", false)
            put("initialClientWidth", w - w % 2)
            put("initialClientHeight", h - h % 2)
            put("scaling_dpi", 96)
            put("framerate", p.framerate)
            put("keyboardLayout", p.keyboardLayout)
            put("useCssScaling", true)
            put("displayId", "primary")
            put("tabId", tabId)
            put("displayScale", 1.0)
            put("audioRedundancy", true)
            put("video_bitrate", p.videoBitrateKbps)
        }
        send("SETTINGS," + json.toString())
    }

    /** 每条连接的 tabId（稳定随机） */
    private val tabId: String = UUID.randomUUID().toString().take(18)

    // =====================================================================
    // 发送原语
    // =====================================================================
    private fun send(text: String) {
        val w = ws ?: return
        // gzip 大文本（服务端回显 _gz,1 后，≥512B 的控制文本走 0x05）
        if (gzTxEnabled && text.length >= 512) {
            try {
                val bos = ByteArrayOutputStream()
                GZIPOutputStream(bos).use { it.write(text.toByteArray(Charsets.UTF_8)) }
                w.send(bos.toByteArray().toByteString())
                return
            } catch (e: Exception) { /* 退化明文 */ }
        }
        w.send(text)
    }

    private fun inputAllowed(): Boolean = !inputBlocked && ws != null

    // ---- 键盘 ----
    fun sendKey(keysym: Int, down: Boolean) {
        if (!inputAllowed()) return
        send(if (down) "kd,$keysym" else "ku,$keysym")
        if (down) heldKeys.add(keysym) else heldKeys.remove(keysym)
    }

    fun resetKeyboard() { if (inputAllowed()) send("kr"); heldKeys.clear() }

    /** 文本直投（中文/IME 场景） */
    fun sendText(text: String) {
        if (!inputAllowed() || text.isEmpty()) return
        send("co,end,$text")
    }

    // ---- 鼠标 ----
    fun pointerMove(x: Int, y: Int) {
        if (!inputAllowed()) return
        send("m,$x,$y,$currentMask,0,${++pointerSeq}")
    }

    fun buttonDown(maskBit: Int) {
        if (!inputAllowed()) return
        currentMask = currentMask or maskBit
        send("m,${lastX},${lastY},$currentMask,0,${++pointerSeq}")
    }

    fun buttonUp(maskBit: Int) {
        if (!inputAllowed()) return
        currentMask = currentMask and maskBit.inv()
        send("m,${lastX},${lastY},$currentMask,0,${++pointerSeq}")
    }

    fun moveWithMask(x: Int, y: Int) {
        if (!inputAllowed()) return
        lastX = x; lastY = y
        send("m,$x,$y,$currentMask,0,${++pointerSeq}")
    }

    /** 滚轮脉冲：官方三连发上升沿语义 m2,0,0,mask,mag */
    fun scrollPulse(dir: ScrollDir, magnitude: Int = 1) {
        if (!inputAllowed() || magnitude < 1) return
        val bit = when (dir) {
            ScrollDir.UP -> Protocol.MASK_SCROLL_UP
            ScrollDir.DOWN -> Protocol.MASK_SCROLL_DOWN
            ScrollDir.LEFT -> Protocol.MASK_SCROLL_LEFT
            ScrollDir.RIGHT -> Protocol.MASK_SCROLL_RIGHT
        }
        if (dir == ScrollDir.LEFT || dir == ScrollDir.RIGHT) {
            send("m2,0,0,${currentMask or bit},$magnitude")
            send("m2,0,0,$currentMask,$magnitude")
        } else {
            val cleared = currentMask and bit.inv()
            send("m2,0,0,$cleared,$magnitude")
            send("m2,0,0,${cleared or bit},$magnitude")
            send("m2,0,0,$currentMask,$magnitude")
        }
    }

    enum class ScrollDir { UP, DOWN, LEFT, RIGHT }

    private var lastX = 0
    private var lastY = 0

    // ---- 请求类 ----
    fun requestKeyframe() { if (ws != null) send("REQUEST_KEYFRAME") }
    fun lostFrame(frameId: Int) { if (ws != null) send("LOST_FRAME $frameId") }
    fun setResolution(w: Int, h: Int) { if (ws != null) send("r,${w - w % 2}x${h - h % 2}") }
    fun setVideoBitrate(kbps: Int) { if (ws != null && kbps > 0) send("vb,$kbps") }
    fun setFps(fps: Int) { if (ws != null && fps > 0) send("_arg_fps,$fps") }
    fun startAudio() { if (ws != null) send("START_AUDIO") }
    fun stopAudio() { if (ws != null) send("STOP_AUDIO") }
    fun startVideo() { if (ws != null) send("START_VIDEO") }
    fun stopVideo() { if (ws != null) send("STOP_VIDEO") }
    fun requestClipboard() { if (ws != null) send("REQUEST_CLIPBOARD") }

    // ---- 手柄（js 协议，对照 input_handler.py） ----
    fun gamepadConnect(idx: Int, name: String, numAxes: Int, numBtns: Int) {
        if (!inputAllowed()) return
        val b64 = Base64.encodeToString(name.toByteArray(Charsets.ISO_8859_1), Base64.NO_WRAP)
        send("js,c,$idx,$b64,$numAxes,$numBtns")
    }
    fun gamepadDisconnect(idx: Int) { if (ws != null) send("js,d,$idx") }
    fun gamepadButton(idx: Int, btn: Int, value: Float) { if (inputAllowed()) send("js,b,$idx,$btn,${"%.4f".format(value)}") }
    fun gamepadAxis(idx: Int, axis: Int, value: Float) { if (inputAllowed()) send("js,a,$idx,$axis,${"%.4f".format(value)}") }
    fun gamepadHeartbeat(idx: Int) { if (inputAllowed()) send("js,h,$idx") }

    // ---- 剪贴板发送（对照 cws/cwd/cwe） ----
    fun sendClipboardText(text: String) {
        if (ws == null || text.isEmpty() || !inputAllowed()) return
        val data = text.toByteArray(Charsets.UTF_8)
        if (data.size < Protocol.CLIPBOARD_CHUNK) {
            send("cw," + Base64.encodeToString(data, Base64.NO_WRAP))
            return
        }
        val id = UUID.randomUUID().toString().take(8)
        send("cws,$id,${data.size}")
        var off = 0
        while (off < data.size) {
            val len = minOf(Protocol.CLIPBOARD_CHUNK, data.size - off)
            val chunk = data.copyOfRange(off, off + len)
            send("cwd,$id," + Base64.encodeToString(chunk, Base64.NO_WRAP))
            off += len
        }
        send("cwe,$id")
    }

    // =====================================================================
    // 文件上传（HTTP POST /api/upload，对照 lib/file-upload.js）
    // =====================================================================
    fun uploadFile(resolver: android.content.ContentResolver, uri: Uri, displayName: String,
                   onProgress: (sent: Long, total: Long) -> Unit,
                   onDone: (ok: Boolean, message: String) -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                val httpUrl = EndpointProber.toHttp(currentWsUrl ?: return@launch).newBuilder()
                    .encodedPath("/api/upload").build()
                val auth = creds?.let { "Basic " + Base64.encodeToString("${it.user}:${it.password}".toByteArray(), Base64.NO_WRAP) }
                val size = resolver.openAssetFileDescriptor(uri, "r")?.length ?: -1L
                val upId = UUID.randomUUID().toString().take(12)
                var sent = 0L
                resolver.openInputStream(uri)!!.use { input ->
                    val buf = ByteArray(1024 * 1024)
                    val chunkStream = ByteArrayOutputStream()
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        chunkStream.write(buf, 0, n)
                        sent += n
                        // 64MB 一片（Cloudflare 单请求上限）
                        if (chunkStream.size() >= Protocol.UPLOAD_CHUNK) {
                            flushChunk(httpUrl, auth, displayName, chunkStream.toByteArray(), upId, sent - chunkStream.size(), size, false)
                            onProgress(sent, size)
                            chunkStream.reset()
                        }
                    }
                    val last = chunkStream.toByteArray()
                    flushChunk(httpUrl, auth, displayName, last, upId, sent - last.size, size, true)
                    onProgress(sent, size)
                }
                withContext(Dispatchers.Main) { onDone(true, "已上传到服务端上传目录（默认远端桌面）") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onDone(false, "上传失败：${e.message}") }
            }
        }
    }

    private fun flushChunk(url: HttpUrl, auth: String?, name: String, body: ByteArray,
                           upId: String, offset: Long, total: Long, final: Boolean) {
        val req = Request.Builder()
            .post(body.toRequestBody("application/octet-stream".toMediaType()))
            .url(url)
            .apply {
                header("X-Upload-Path", java.net.URLEncoder.encode(name, "UTF-8"))
                if (offset > 0 || !final || total > Protocol.UPLOAD_CHUNK) {
                    header("X-Upload-Id", upId)
                    header("X-Upload-Offset", offset.toString())
                    header("X-Upload-Total", total.toString())
                    if (final) header("X-Upload-Final", "1")
                }
                for ((k, v) in Protocol.browserHeaders(EndpointProber.originOf(url))) header(k, v)
                auth?.let { header("Authorization", it) }
            }.build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}: ${resp.body?.string()?.take(160)}")
        }
    }

    // =====================================================================
    // 接收：文本分发
    // =====================================================================
    private fun dispatchText(text: String) {
        when {
            text == "MODE websockets" -> {
                modeReceived = true
                setState(State.CONNECTED, "服务端确认 websockets 模式，启动视频流…")
                if (ws != null) send("START_VIDEO")
            }
            text.startsWith("AUTH_SUCCESS,") -> {
                val o = try { JSONObject(text.substring(13)) } catch (e: Exception) { JSONObject() }
                listener.onRole(o.optString("role", "controller"), if (o.isNull("slot")) null else o.optInt("slot"))
            }
            text.startsWith("ROLE_UPDATE,") -> {
                val o = try { JSONObject(text.substring(12)) } catch (e: Exception) { JSONObject() }
                listener.onRole(o.optString("role", "controller"), if (o.isNull("slot")) null else o.optInt("slot"))
            }
            text.startsWith("MK_ACCESS,") -> {
                val granted = text.substring(9).trim() == "1"
                if (config?.viewOnly == false) inputBlocked = !granted
            }
            text == "VIDEO_STARTED" -> listener.onVideoActive(true)
            text == "VIDEO_STOPPED" -> listener.onVideoActive(false)
            text == "AUDIO_STARTED" -> listener.onAudioActive(true, null)
            text == "AUDIO_STOPPED" -> listener.onAudioActive(false, null)
            text == "AUDIO_DISABLED" -> listener.onAudioActive(false, "服务端音频被禁用")
            text == "MICROPHONE_DISABLED" || text == "WEBCAM_DISABLED" -> { /* 记录即可 */ }
            text.startsWith("PIPELINE_RESETTING ") -> videoDecoder?.flush()
            text.startsWith("DISPLAY_CONFIG_UPDATE,") -> {
                try {
                    val o = JSONObject(text.substring("DISPLAY_CONFIG_UPDATE,".length))
                    // layouts: {displayId: {x,y,w,h,...}}，取 primary
                    val layouts = o.optJSONObject("layouts")
                    val primary = layouts?.optJSONObject("primary")
                    val w = primary?.optInt("w", 0) ?: 0
                    val h = primary?.optInt("h", 0) ?: 0
                    if (w > 0 && h > 0) listener.onResolution(w, h)
                } catch (e: Exception) {}
            }
            text.startsWith("KILL ") -> {
                val reason = text.substring(5)
                listener.onError("服务端断开连接", reason)
                userDisconnect = true   // KILL 不重连
                try { ws?.close(1000, "kill") } catch (e: Exception) {}
            }
            text.startsWith("cursor,") -> handleCursor(text.substring(7))
            text.startsWith("system,") -> handleSystem(text.substring(7))
            // ---- 剪贴板 ----
            text.startsWith("clipboard_reply,") -> { clipIsCacheOnly = true }
            text == "clipboard_secret" -> { /* 密文提示：仅标记 */ }
            text.startsWith("clipboard_start,") -> {
                val parts = text.split(",")
                clipMime = parts.getOrNull(1) ?: "text/plain"
                clipExpected = parts.getOrNull(2)?.toIntOrNull() ?: 0
                clipBuf.reset()
            }
            text.startsWith("clipboard_data,") -> {
                try {
                    clipBuf.write(Base64.decode(text.substring(15), Base64.DEFAULT))
                } catch (e: Exception) {}
            }
            text == "clipboard_finish" -> deliverClipboard()
            text.startsWith("clipboard_binary,") -> {
                val parts = text.split(",", limit = 3)
                if (parts.size == 3) {
                    clipMime = parts[1]
                    clipBuf.reset()
                    try { clipBuf.write(Base64.decode(parts[2], Base64.DEFAULT)) } catch (e: Exception) {}
                    deliverClipboard()
                }
            }
            text.startsWith("clipboard,") -> {
                clipMime = "text/plain"; clipBuf.reset()
                try { clipBuf.write(Base64.decode(text.substring(10), Base64.DEFAULT)) } catch (e: Exception) {}
                deliverClipboard()
            }
            // ---- gzip 回显 ----
            text == "_gz,1" -> gzTxEnabled = true
            // ---- ping ----
            text == "ping" || text.startsWith("ping,") -> send("pong")
            // ---- JSON 家族 ----
            text.startsWith("{") -> handleJson(text)
        }
    }

    private fun deliverClipboard() {
        val bytes = clipBuf.toByteArray()
        clipBuf.reset()
        if (bytes.isEmpty()) return
        if (clipMime == "text/plain" || clipMime.startsWith("text/")) {
            listener.onRemoteClipboard(String(bytes, Charsets.UTF_8))
        } else {
            // 二进制剪贴板（图片等）：写入本地需要 content provider，v1 记录并提示
            listener.onRemoteClipboard("")
        }
        clipIsCacheOnly = false
    }

    private fun handleCursor(json: String) {
        try {
            val o = JSONObject(json)
            val handle = o.optLong("handle", 0)
            val b64 = if (o.isNull("curdata")) null else o.optString("curdata")
            if (b64.isNullOrEmpty() || handle == 0L) {
                listener.onCursor(null, 0, 0, 0, 0)
            } else {
                listener.onCursor(b64, o.optInt("hotx"), o.optInt("hoty"), o.optInt("width"), o.optInt("height"))
            }
        } catch (e: Exception) {}
    }

    private fun handleSystem(json: String) {
        try {
            val o = JSONObject(json)
            val action = o.optString("action", "")
            when {
                action.startsWith("rumble,") -> {
                    val p = action.split(",")
                    listener.onRumble(p.getOrNull(1)?.toFloatOrNull() ?: 0f,
                        p.getOrNull(2)?.toFloatOrNull() ?: 0f,
                        p.getOrNull(3)?.toIntOrNull() ?: 0)
                }
                action == "reload" -> { /* 服务端要求重载：等效重连 */ }
                // pointer,<display>,<x>,<y>,<scale>,<seq> —— 触控板回显，v1 忽略
            }
        } catch (e: Exception) {}
    }

    private fun handleJson(text: String) {
        try {
            val o = JSONObject(text)
            when (o.optString("type")) {
                "server_settings" -> {
                    serverSettingsReceived = true
                    if (pendingAudioStart) { pendingAudioStart = false; startAudio() }
                }
                "stream_resolution" -> {
                    // {"type":"stream_resolution","width":W,"height":H,"displayId":"primary"}
                    val w = o.optInt("width", 0)
                    val h = o.optInt("height", 0)
                    if (w > 0 && h > 0) listener.onResolution(w, h)
                }
                "display_settings" -> {
                    val s = o.optJSONObject("settings") ?: return
                    val w = s.optInt("w", 0); val h = s.optInt("h", 0)
                    if (w > 0 && h > 0) listener.onResolution(w, h)
                }
                "stream_stats" -> {
                    val st = o.optJSONObject("stats") ?: return
                    serverRttMs = st.optInt("rtt", st.optDouble("rtt", 0.0).roundToInt())
                    serverBitrateKbps = st.optInt("video_bitrate", st.optDouble("video_bitrate", 0.0).roundToInt())
                    serverPending = st.optInt("pending", 0)
                }
                "pipeline_status" -> {
                    val v = o.optJSONObject("status")
                    // {"type":"pipeline_status","status":{"video":true,"audio":true}}
                    v?.let {
                        if (it.optBoolean("audio", false) && prefs?.audioEnabled == true && !serverSettingsReceived) {
                            pendingAudioStart = true
                        }
                    }
                }
                // server_apps / stream_info：统计面板可后续扩展
            }
        } catch (e: Exception) { /* 非法 JSON 忽略 */ }
    }

    // =====================================================================
    // 接收：二进制分发
    // =====================================================================
    private fun onBinary(data: ByteArray) {
        if (data.isEmpty()) return
        bytesThisSec += data.size
        when (data[0].toInt() and 0xff) {
            Protocol.BIN_VIDEO -> onVideoFrame(data)
            Protocol.BIN_AUDIO -> audioPlayer?.feed(data)
            Protocol.BIN_JPEG -> onJpegStripe(data)
            Protocol.BIN_GZ_TEXT -> {
                try {
                    val text = GZIPInputStream(java.io.ByteArrayInputStream(data, 1, data.size - 1)).readBytes().toString(Charsets.UTF_8)
                    dispatchText(text)
                } catch (e: Exception) {}
            }
        }
    }

    private var jpegModeNotified = false
    private fun onJpegStripe(data: ByteArray) {
        if (!jpegModeNotified) {
            jpegModeNotified = true
            scope.launch(Dispatchers.Main) {
                listener.onError("后端处于 JPEG 降级模式",
                    "当前流为 JPEG 条带（0x03）。请在后端设置 SELKIES_ENCODER=h264enc 以获得 H264 硬解流。")
            }
        }
    }

    private fun onVideoFrame(data: ByteArray) {
        if (data.size < Protocol.VIDEO_HEADER_LEN) return
        val typeByte = data[1].toInt() and 0xff
        val frameId = ((data[2].toInt() and 0xff) shl 8) or (data[3].toInt() and 0xff)
        val stripeY = ((data[4].toInt() and 0xff) shl 8) or (data[5].toInt() and 0xff)
        val w = ((data[6].toInt() and 0xff) shl 8) or (data[7].toInt() and 0xff)
        val h = ((data[8].toInt() and 0xff) shl 8) or (data[9].toInt() and 0xff)
        val refId = ((data[10].toInt() and 0xff) shl 8) or (data[11].toInt() and 0xff)
        val codec = typeByte shr 4
        val isKey = (typeByte and 0x0f) == 0x01

        lastVideoFrameId = frameId
        lastVideoFrameAt = SystemClock.elapsedRealtime()
        if (w > 0 && h > 0 && (w != lastVideoW || h != lastVideoH)) {
            lastVideoW = w; lastVideoH = h
            listener.onResolution(w, h)
        }
        if (codec != Protocol.CODEC_H264) {
            // VP8/VP9/AV1/H265：本版仅支持 H264 硬解，提示切换
            if (codec != lastCodecNotified) {
                lastCodecNotified = codec
                scope.launch(Dispatchers.Main) {
                    listener.onError("视频编码不支持",
                        "服务端正在发送 ${codecName(codec)} 流，本客户端当前仅支持 H.264。请在后端设置 SELKIES_ENCODER=h264enc。")
                }
            }
            return
        }
        if (state == State.CONNECTED) setState(State.STREAMING, "视频流已建立")
        framesThisSec++
        videoDecoder?.onFrame(frameId, isKey, w, h, data, Protocol.VIDEO_HEADER_LEN)
    }

    private var lastCodecNotified = -1
    private fun codecName(id: Int) = when (id) {
        Protocol.CODEC_VP8 -> "VP8"; Protocol.CODEC_VP9 -> "VP9"
        Protocol.CODEC_AV1 -> "AV1"; Protocol.CODEC_H265 -> "H.265"; else -> "未知($id)"
    }

    // =====================================================================
    // 周期任务
    // =====================================================================
    private fun startTimers() {
        stopTimers()
        ackJob = scope.launch(Dispatchers.IO) {
            while (state in listOf(State.CONNECTED, State.STREAMING, State.AUTH, State.PROBING) && ws != null) {
                delay(Protocol.ACK_INTERVAL_MS)
                val id = lastVideoFrameId
                if (id >= 0 && ws != null) {
                    val held = (SystemClock.elapsedRealtime() - lastVideoFrameAt).toInt().coerceAtLeast(0)
                    try { ws?.send("CLIENT_FRAME_ACK $id $held") } catch (e: Exception) {}
                }
            }
        }
        metricsJob = scope.launch(Dispatchers.IO) {
            while (ws != null) {
                delay(Protocol.METRICS_INTERVAL_MS)
                if (ws != null) {
                    send("_f,${framesThisSec * 2}")   // 500ms 窗口 → 每秒帧率
                    send("_l,$serverRttMs")
                    val fps = framesThisSec * 2
                    val kbps = ((bytesThisSec * 2) / 1024).toInt()
                    framesThisSec = 0; bytesThisSec = 0
                    scope.launch(Dispatchers.Main) { listener.onStats(fps, kbps, serverRttMs, serverPending) }
                }
            }
        }
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (ws != null) {
                delay(Protocol.KEY_HEARTBEAT_MS)
                val held = synchronized(heldKeys) { heldKeys.toList() }
                if (held.isNotEmpty() && inputAllowed()) send("kh," + held.joinToString(","))
            }
        }
    }

    private fun stopTimers() {
        ackJob?.cancel(); ackJob = null
        metricsJob?.cancel(); metricsJob = null
        heartbeatJob?.cancel(); heartbeatJob = null
    }

    private fun clearInputState() {
        heldKeys.clear()
        currentMask = 0
        pointerSeq = 0
        lastVideoFrameId = -1
        modeReceived = false
        serverSettingsReceived = false
        pendingAudioStart = prefs?.audioEnabled == true
        gzTxEnabled = false
        clipBuf.reset()
    }

    /** 音频启动（MODE 收到 + server_settings 收到后调用） */
    fun maybeStartAudio() {
        val p = prefs ?: return
        if (!p.audioEnabled) return
        if (serverSettingsReceived) startAudio() else pendingAudioStart = true
    }

    /** 触发帧率/码率调整（设置页实时生效） */
    fun applyQuality(p: Prefs) {
        setVideoBitrate(p.videoBitrateKbps)
        setFps(p.framerate)
    }

    fun releaseMedia() {
        videoDecoder?.release(); videoDecoder = null
        audioPlayer?.release(); audioPlayer = null
    }
}

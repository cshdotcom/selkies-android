package com.selkies.client.net

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.selkies.client.data.ServerConfig
import com.selkies.client.data.SettingsStore
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 连接引擎 —— 整个客户端的心脏。
 *
 * 职责：
 *  1. 端点探测（双路径回退 / 301 跟随 / Basic Auth 识别）
 *  2. WebSocket 会话与协议状态机（握手 → SETTINGS → r → START_VIDEO/AUDIO）
 *  3. 控制消息路由（文本）与媒体帧分发（二进制，直达解码器工作线程）
 *  4. 回压 ACK 心跳（CLIENT_FRAME_ACK，1s 心跳 —— 缺失会导致服务端判卡顿限流）
 *  5. 断线自动重连（指数退避，尊重服务端 4029 防抖限制）
 *
 * 线程模型：
 *  - 文本控制消息 → 解析后经主线程 Handler 分发 UI 事件（低频，安全）；
 *  - 媒体帧（视频/音频/JPEG）→ 直接投递 MediaSink（视频/音频解码器各自的
 *    工作线程 + 有界队列），绝不经过主线程 —— 高频路径零 UI 干扰；
 *  - 引擎自身无锁（原子字段 + volatile）。
 *
 * 防闪退设计：
 *  - onMessage 的每一条消息都在 try/catch 中处理，单条畸形消息绝不让进程崩溃；
 *  - 所有发送都经过 safeSend，网络层异常只记日志；
 *  - 重连定时器与生命周期解耦，UI 退出时 shutdown() 幂等清理。
 */
class ConnectionEngine(
    private val config: ServerConfig,
    private val settings: SettingsStore,
    private val listener: Listener
) {

    // ------------------------------------------------------------------
    // 媒体接收端（线程安全；由 SessionActivity 装配/拆卸）
    // ------------------------------------------------------------------
    interface MediaSink {
        /** 视频帧（0x04）。data 为完整二进制消息，负载位于 [payloadOffset, payloadOffset+payloadLen) */
        fun onVideoFrame(header: Protocol.VideoFrameHeader, data: ByteArray, payloadOffset: Int, payloadLen: Int)

        /** JPEG 条带（0x03），负载位于 [payloadOffset, ...) */
        fun onJpegStripe(header: Protocol.JpegStripeHeader, data: ByteArray, payloadOffset: Int, payloadLen: Int)

        /** 音频 Opus 分组（0x01 解包、RED 去重后的按序帧） */
        fun onAudioFrames(frames: List<Pair<Long, ByteArray>>)

        /** 服务端要求管道重置：丢弃缓存，等待下一关键帧重新配置 */
        fun onPipelineReset()

        /** 视频停止（VIDEO_STOPPED / PIPELINE_RESETTING 期间清屏） */
        fun onVideoInactive()
    }

    // ------------------------------------------------------------------
    // 对外事件（均已在主线程回调）
    // ------------------------------------------------------------------
    interface Listener {
        /** 连接状态变化 */
        fun onState(state: State, detail: String? = null)

        /** 服务端设置（server_settings JSON 的 settings 部分） */
        fun onServerSettings(settings: JSONObject)

        /** 流分辨率变化（服务器已实现的桌面分辨率） */
        fun onStreamResolution(width: Int, height: Int)

        fun onVideoStarted()
        fun onVideoStopped()

        /** 音频：started=已开始；unavailable=设备/服务端不支持 */
        fun onAudioStarted()
        fun onAudioUnavailable(reason: String?)

        /** 服务端 KILL（被新客户端顶替 / 二屏不可用等） */
        fun onKilled(reason: String)

        /** 远端剪贴板文本（含分段重组） */
        fun onClipboardText(text: String)

        /** 远端剪贴板二进制（image/png 等） */
        fun onClipboardBinary(mime: String, data: ByteArray)

        /** 角色被服务端限制为 viewer（仅查看，输入被拒） */
        fun onRoleCappedToViewer()

        /** 显示配置（finger_scroll 能力等） */
        fun onDisplayConfig(fingerScroll: Boolean)

        /** 远端打印作业：服务器打印到 PDF 后通知客户端下载（api/print/<name>） */
        fun onPrintDocument(name: String, sizeBytes: Long)

        /** 服务器要求摄像头上行发送关键帧 */
        fun onWebcamKeyframeRequest() { }

        /** 服务器按需要求客户端开启/停止硬件捕获（subject: webcam / microphone） */
        fun onCaptureDemand(subject: String, on: Boolean) { }

        /** 麦克风上行被服务端拒绝（策略禁用 / viewer 角色） */
        fun onMicrophoneDisabled() { }

        /** 摄像头上行被服务端拒绝 */
        fun onWebcamDisabled() { }

        /** system 命令回执（command_error,/command_done,/apps_installed,/reload 前缀） */
        fun onSystemCommand(action: String) { }

        /** 统计快照（每秒） */
        fun onStats(stats: Stats)
    }

    enum class State {
        DISCONNECTED,   // 未连接
        PROBING,        // 探测端点
        NEED_AUTH,      // 等待用户输入凭据
        CONNECTING,     // WS 握手中
        CONNECTED,      // 已连接（等待/正在收流）
        RECONNECTING,   // 断线重连中
        FAILED          // 彻底失败（需用户干预）
    }

    data class Stats(
        val fps: Int,
        val bitrateKbps: Int,
        val rttMs: Long,
        val frames: Long,
        val bytes: Long
    )

    // ------------------------------------------------------------------
    // 内部状态
    // ------------------------------------------------------------------
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var state = State.DISCONNECTED
    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var client: OkHttpClient? = null
    @Volatile private var currentAuthHeader: String? = null
    @Volatile private var probedBase: HttpUrl? = null
    @Volatile private var wsPathIndex = 0
    @Volatile private var probedPaths: List<String> =
        listOf(Protocol.WS_PATH_PRIMARY, Protocol.WS_PATH_FALLBACK)
    @Volatile private var basicAuthUser: String? = config.username
    @Volatile private var basicAuthPass: String? = config.password

    // ---- v1.2.0：多策略 WS 握手 ----
    /** 握手策略游标：0=完整浏览器头 1=无 Origin 2=极简头。握手期网络层失败时逐个轮换重试 */
    @Volatile private var wsStrategy = 0
    /** 本次连接尝试是否已到达 onOpen（区分握手期失败与会话中断） */
    @Volatile private var wsOpenedThisAttempt = false

    /** 媒体接收端（未装配时直接丢帧） */
    @Volatile var mediaSink: MediaSink? = null

    /** 输入许可：仅查看模式（本地配置或服务端角色限制）下为 false */
    @Volatile var inputAllowed: Boolean = !config.viewOnly

    private val closed = AtomicBoolean(false)
    private val userRequestedDisconnect = AtomicBoolean(false)

    // 回压 ACK 状态
    private val lastFrameId = AtomicLong(-1)
    private val lastFrameAt = AtomicLong(0)
    private val lastAckSentId = AtomicLong(-1)
    private val lastAckSentAt = AtomicLong(0)

    // 音频 RED 去重游标
    @Volatile private var audioLastPts: Long? = null

    // 剪贴板分段重组
    private var clipBuf = StringBuilder()

    // 统计
    private val statFrames = AtomicLong(0)
    private val statBytes = AtomicLong(0)
    private val statWindowFrames = AtomicLong(0)
    private val statWindowBytes = AtomicLong(0)
    @Volatile private var rttMs = -1L

    // 握手进度
    @Volatile private var serverSettingsReceived = false
    @Volatile private var videoStarted = false
    @Volatile private var audioStarted = false

    /** 初始屏幕尺寸（SessionActivity 提供，打包进 SETTINGS 的 initialClientWidth/Height） */
    @Volatile var clientWidth: Int = 1280
    @Volatile var clientHeight: Int = 720

    // 重连计数与定时器
    private var reconnectAttempt = 0
    private val reconnectRunnable = Runnable { startConnectThread() }
    private val ackRunnable = object : Runnable {
        override fun run() {
            try {
                sendBackpressureAck()
            } catch (e: Exception) {
                Log.w(TAG, "ack tick error: ${e.message}")
            }
            if (!closed.get()) main.postDelayed(this, ACK_INTERVAL_MS)
        }
    }
    private val statsRunnable = object : Runnable {
        override fun run() {
            try {
                reportStats()
            } catch (e: Exception) {
                Log.w(TAG, "stats tick error: ${e.message}")
            }
            if (!closed.get()) main.postDelayed(this, STATS_INTERVAL_MS)
        }
    }

    // ------------------------------------------------------------------
    // 公共 API
    // ------------------------------------------------------------------

    /** 启动连接（幂等） */
    fun connect() {
        if (closed.get()) return
        if (state != State.DISCONNECTED && state != State.FAILED) return
        userRequestedDisconnect.set(false)
        wsStrategy = 0 // 用户主动连接：从默认策略开始
        DiagLog.newSession()
        DiagLog.i("连接启动: ${config.host}:${config.port}${config.normalizedPath} https=${config.https}")
        startConnectThread()
    }

    private fun startConnectThread() {
        Thread {
            try {
                connectInternal()
            } catch (e: Exception) {
                Log.e(TAG, "connect thread crashed", e)
                postState(State.FAILED, e.message)
            }
        }.apply { name = "selkies-connect" }.start()
    }

    /** 用户提供了凭据（NEED_AUTH 之后调用），将重新探测验证并连接 */
    fun provideCredentials(user: String, pass: String) {
        basicAuthUser = user
        basicAuthPass = pass
        if (state == State.NEED_AUTH) {
            probedBase = null // 强制重新探测
            DiagLog.i("凭据已提供，重新探测")
            Thread {
                try {
                    connectInternal()
                } catch (e: Exception) {
                    Log.e(TAG, "reconnect after auth crashed", e)
                    postState(State.FAILED, e.message)
                }
            }.apply { name = "selkies-reauth" }.start()
        }
    }

    /** 手动断开（不触发自动重连） */
    fun disconnect() {
        userRequestedDisconnect.set(true)
        shutdown()
        postState(State.DISCONNECTED)
    }

    /** 彻底销毁（Activity 销毁时调用；幂等） */
    fun shutdown() {
        closed.set(true)
        main.removeCallbacks(reconnectRunnable)
        main.removeCallbacks(ackRunnable)
        main.removeCallbacks(statsRunnable)
        try {
            webSocket?.close(1000, "client shutdown")
        } catch (_: Exception) {
        }
        webSocket = null
        try {
            client?.dispatcher?.executorService?.shutdown()
        } catch (_: Exception) {
        }
        client = null
    }

    // ---------------- 发送原语 ----------------

    fun sendText(msg: String) {
        val ws = webSocket ?: return
        try {
            ws.send(msg)
        } catch (e: Exception) {
            Log.w(TAG, "sendText failed: ${e.message}")
        }
    }

    /** 输入类消息门禁：仅查看模式下一律丢弃（双保险之一，另一处在 InputController） */
    private fun sendInput(msg: String) {
        if (!inputAllowed) return
        sendText(msg)
    }

    // ------------------------------------------------------------------
    // 连接流程
    // ------------------------------------------------------------------

    private fun connectInternal() {
        if (closed.get() || userRequestedDisconnect.get()) return
        postState(
            if (reconnectAttempt > 0) State.RECONNECTING else State.PROBING,
            if (reconnectAttempt > 0) reconnectAttempt.toString() else null
        )

        // 1. Basic Auth 头
        val user = basicAuthUser
        val pass = basicAuthPass
        currentAuthHeader = if (!user.isNullOrEmpty() || !pass.isNullOrEmpty()) {
            "Basic " + java.util.Base64.getEncoder()
                .encodeToString("$user:${pass ?: ""}".toByteArray(Charsets.UTF_8))
        } else null

        // 2. 探测（含 301 跟随与 Basic Auth 识别；已探测过则复用结果）
        var base = probedBase
        if (base == null) {
            val initialBase = buildInitialUrl() ?: run {
                DiagLog.e("地址无效: ${config.host}")
                postState(State.FAILED, "invalid address")
                return
            }
            DiagLog.i("探测 ${initialBase}")
            when (val r = EndpointProber.probe(initialBase, currentAuthHeader, config.trustAllCerts)) {
                is EndpointProber.ProbeResult.NeedAuth -> {
                    DiagLog.w("需要 Basic 认证 (realm=${r.realm})")
                    postState(State.NEED_AUTH)
                    return
                }
                is EndpointProber.ProbeResult.Failed -> {
                    DiagLog.e("探测失败: ${r.reason} ${r.detail ?: ""}")
                    handleConnectFailure("probe: ${r.reason}", r.detail)
                    return
                }
                is EndpointProber.ProbeResult.Ready -> {
                    base = r.finalBase
                    probedBase = r.finalBase
                    probedPaths = r.wsPaths
                    wsPathIndex = 0
                    DiagLog.i("探测成功: base=${r.finalBase} wsPaths=${r.wsPaths}")
                }
            }
        }
        if (closed.get()) return

        // 3. 清理旧连接资源后发起 WS
        cleanupOldSocket()
        postState(State.CONNECTING)
        val path = probedPaths[wsPathIndex.coerceIn(0, probedPaths.size - 1)]
        DiagLog.i("WS 握手: $base$path (策略 $wsStrategy: ${strategyName(wsStrategy)})")
        openWebSocket(base!!, path)
    }

    /** 关闭上一轮残留的 socket / client（重连与路径回退时调用） */
    private fun cleanupOldSocket() {
        try {
            webSocket?.cancel()
        } catch (_: Exception) {
        }
        webSocket = null
        try {
            client?.dispatcher?.executorService?.shutdown()
        } catch (_: Exception) {
        }
        client = null
    }

    /** 根据服务器配置构造初始探测地址 */
    private fun buildInitialUrl(): HttpUrl? {
        var host = config.host.trim()
        if (host.startsWith("http://")) host = host.removePrefix("http://")
        if (host.startsWith("https://")) host = host.removePrefix("https://")
        if (host.startsWith("wss://")) host = host.removePrefix("wss://")
        if (host.startsWith("ws://")) host = host.removePrefix("ws://")
        host = host.trim().trimEnd('/')
        if (host.isEmpty()) return null
        val scheme = if (config.https) "https" else "http"
        return try {
            val builder = HttpUrl.Builder().scheme(scheme).host(host)
            if (config.port in 1..65535) builder.port(config.port)
            val p = config.normalizedPath
            if (p.isNotEmpty()) builder.encodedPath(p)
            builder.build()
        } catch (e: Exception) {
            Log.w(TAG, "invalid url: ${e.message}")
            null
        }
    }

    private fun openWebSocket(base: HttpUrl, wsPath: String) {
        val url = base.newBuilder()
            .encodedPath(EndpointProber.joinPath(base.encodedPath, wsPath))
            .build()
        val wsUrl = url.newBuilder()
            .scheme(if (url.isHttps) "wss" else "ws")
            .build()

        val clientBuilder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)  // 流式长连接，禁用读超时
            .pingInterval(20, TimeUnit.SECONDS)     // WS 层保活（aiohttp 自动回 pong）
            .retryOnConnectionFailure(true)
        if (config.trustAllCerts) EndpointProber.applyTrustAll(clientBuilder)
        val c = clientBuilder.build()
        client = c

        wsOpenedThisAttempt = false
        val reqBuilder = EndpointProber.baseRequest(wsUrl, currentAuthHeader, headOnly = false, strategy = wsStrategy)
        webSocket = c.newWebSocket(reqBuilder.build(), WsListener())
    }

    /** 握手策略名称（诊断用） */
    private fun strategyName(s: Int): String = when (s) {
        0 -> "浏览器头"
        1 -> "无 Origin"
        2 -> "极简头"
        else -> "?"
    }

    // ------------------------------------------------------------------
    // WebSocket 监听器
    // ------------------------------------------------------------------

    private inner class WsListener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "WS open: ${response.request.url}")
            this@ConnectionEngine.webSocket = webSocket
            wsOpenedThisAttempt = true
            DiagLog.i("WS 已连接 (策略 $wsStrategy): ${response.request.url}")
            reconnectAttempt = 0
            postState(State.CONNECTED)

            // 握手序列（对齐官方 onopen）：
            //  1) SETTINGS（含 initialClientWidth/Height —— 服务端据此设定初始分辨率）
            //  2) cr（请求远端剪贴板快照）
            serverSettingsReceived = false
            videoStarted = false
            audioStarted = false
            audioLastPts = null
            sendText("SETTINGS," + Protocol.buildSettingsJson(
                clientWidth, clientHeight,
                settings.framerate, settings.videoCrf, settings.videoBitrate,
                settings.rateControl, settings.audioBitrate,
                settings.clipboardBinary
            ))
            sendText("cr")

            // 启动回压 ACK 心跳与统计
            main.removeCallbacks(ackRunnable)
            main.postDelayed(ackRunnable, ACK_INTERVAL_MS)
            main.removeCallbacks(statsRunnable)
            main.postDelayed(statsRunnable, STATS_INTERVAL_MS)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                handleTextMessage(text)
            } catch (e: Exception) {
                // 单条消息处理失败绝不影响连接 —— 防闪退关键点
                Log.w(TAG, "text handling error: ${e.message} :: ${text.take(80)}")
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            try {
                handleBinaryMessage(bytes.toByteArray())
            } catch (e: Exception) {
                Log.w(TAG, "binary handling error: ${e.message}")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val code = response?.code ?: -1
            Log.w(TAG, "WS failure: ${t.message} code=$code")
            if (closed.get() || userRequestedDisconnect.get()) return
            when {
                code == 401 -> {
                    // 凭据缺失/失效（Selkies Basic 层或反代层）
                    DiagLog.w("WS 握手 401：需要认证")
                    postState(State.NEED_AUTH)
                }
                code == 404 && wsPathIndex < probedPaths.size - 1 -> {
                    // 路径回退：/api/websockets 404 → /api/ws
                    DiagLog.i("路径 404，回退到 ${probedPaths[wsPathIndex + 1]}")
                    wsPathIndex++
                    main.postDelayed({ startConnectThread() }, 200)
                }
                code == 4029 -> {
                    // 服务端重连防抖：加大退避
                    DiagLog.w("服务端防抖 4029，延迟重连")
                    scheduleReconnect(extraDelayMs = 2000L)
                }
                else -> {
                    // v1.2.0 核心修复：握手期网络层失败（unexpected end of stream 等）
                    // 或服务器错误码 → 先轮换握手策略重试，全部失败才进入重连/失败。
                    // 会话中断（已 onOpen 过）不轮换，直接走重连。
                    if (!wsOpenedThisAttempt && wsStrategy < WS_STRATEGY_MAX) {
                        wsStrategy++
                        DiagLog.w(
                            "WS 失败(${t.javaClass.simpleName}: ${t.message} code=$code) → " +
                                    "改用策略 $wsStrategy(${strategyName(wsStrategy)}) 重试"
                        )
                        main.postDelayed({ startConnectThread() }, 300)
                    } else {
                        DiagLog.e("WS 失败: ${t.javaClass.simpleName}: ${t.message} code=$code")
                        handleConnectFailure("ws: ${t.message}", "code=$code", httpCode = code)
                    }
                }
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WS closed: code=$code reason=$reason")
            if (closed.get() || userRequestedDisconnect.get()) return
            handleConnectFailure("closed", "code=$code ${reason.take(60)}")
        }
    }

    // ------------------------------------------------------------------
    // 文本消息路由
    // ------------------------------------------------------------------

    private fun handleTextMessage(text: String) {
        statBytes.addAndGet(text.length.toLong())
        when {
            text == "MODE websockets" -> { /* 协议确认 */ }

            text.startsWith("AUTH_SUCCESS,") -> {
                try {
                    val role = JSONObject(text.substringAfter(",")).optString("role")
                    if (role == "viewer") main.post { listener.onRoleCappedToViewer() }
                } catch (_: Exception) {
                }
            }

            text.startsWith("MK_ACCESS,") -> {
                if (text.substringAfter(",").trim() == "0") {
                    main.post { listener.onRoleCappedToViewer() }
                }
            }

            text.startsWith("DISPLAY_CONFIG_UPDATE,") -> {
                try {
                    val o = JSONObject(text.substringAfter(","))
                    main.post { listener.onDisplayConfig(o.optBoolean("finger_scroll", false)) }
                } catch (_: Exception) {
                }
            }

            text == "VIDEO_STARTED" -> {
                videoStarted = true
                main.post { listener.onVideoStarted() }
                maybeStartStreams()
            }

            text == "VIDEO_STOPPED" -> {
                videoStarted = false
                main.post { listener.onVideoStopped() }
                mediaSink?.onVideoInactive()
            }

            text == "AUDIO_STARTED" -> {
                audioStarted = true
                main.post { listener.onAudioStarted() }
            }

            text == "AUDIO_STOPPED" -> { /* 停止即静默，无需 UI 动作 */ }

            text == "AUDIO_DISABLED" -> main.post { listener.onAudioUnavailable("disabled") }

            text == "MICROPHONE_DISABLED" -> main.post { listener.onMicrophoneDisabled() }

            text == "WEBCAM_DISABLED" -> main.post { listener.onWebcamDisabled() }

            text == "WEBCAM_KEYFRAME" -> main.post { listener.onWebcamKeyframeRequest() }

            text.startsWith("CAPTURE_DEMAND ") -> {
                // CAPTURE_DEMAND <subject> <0|1>：按需开启/停止硬件上行
                val parts = text.split(" ")
                if (parts.size >= 3) {
                    val on = parts[2].trim() == "1"
                    main.post { listener.onCaptureDemand(parts[1].trim(), on) }
                }
            }

            text.startsWith("PIPELINE_RESETTING") -> {
                main.post { listener.onVideoStopped() }
                mediaSink?.onPipelineReset()
                // 管道重置后主动请求关键帧，加速画面恢复（官方行为）
                sendText("REQUEST_KEYFRAME")
            }

            text.startsWith("KILL ") -> {
                val reason = text.removePrefix("KILL ")
                main.post { listener.onKilled(reason) }
                userRequestedDisconnect.set(true) // KILL 场景（多为被顶替）不自动重连
                shutdown()
                postState(State.DISCONNECTED, reason)
            }

            text.startsWith("cursor,") -> { /* 服务端光标：Android 本地渲染光标，忽略 */ }

            // ---------- 剪贴板族 ----------
            text.startsWith("clipboard_start,") -> {
                val parts = text.split(",")
                clipBuf = StringBuilder()
                // parts[1]=tid, parts[2]=total —— 只需 total
                val total = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                if (total in 1..CLIPBOARD_MAX) { /* 接收中 */ } else clipBuf = StringBuilder()
            }
            text.startsWith("clipboard_data,") -> {
                clipBuf.append(text.substringAfter("clipboard_data,"))
            }
            text == "clipboard_finish" -> {
                val s = clipBuf.toString()
                clipBuf = StringBuilder()
                deliverClipboardText(s)
            }
            text.startsWith("clipboard_binary,") -> {
                val rest = text.substringAfter("clipboard_binary,")
                val mime = rest.substringBefore(",")
                val b64 = rest.substringAfter(",", "")
                try {
                    val data = java.util.Base64.getDecoder().decode(b64)
                    main.post { listener.onClipboardBinary(mime, data) }
                } catch (_: Exception) {
                }
            }
            text.startsWith("clipboard,") -> deliverClipboardText(text.substringAfter("clipboard,"))
            text.startsWith("clipboard_reply,") -> { /* cr 的标签应答；内容走 clipboard, 分支 */ }

            // ---------- JSON 对象族 ----------
            text.startsWith("{") -> handleJsonMessage(text)

            // ---------- 旧版 r,WxH,display 分辨率广播 ----------
            text.startsWith("r,") -> {
                Protocol.parseResolutionMessage(text)?.let { (w, h, _) ->
                    if (w > 0 && h > 0) main.post { listener.onStreamResolution(w, h) }
                }
            }

            else -> { /* 未知控制消息：向前兼容，忽略 */ }
        }
    }

    private fun handleJsonMessage(text: String) {
        try {
            val o = JSONObject(text)
            when (o.optString("type")) {
                "server_settings" -> {
                    val s = o.optJSONObject("settings") ?: JSONObject()
                    val first = !serverSettingsReceived
                    serverSettingsReceived = true
                    main.post { listener.onServerSettings(s) }
                    if (first) {
                        // 收到服务端设置后：请求分辨率 → 启动媒体流
                        sendResolution()
                        maybeStartStreams()
                    }
                }
                "stream_resolution" -> {
                    val w = o.optInt("width")
                    val h = o.optInt("height")
                    if (w > 0 && h > 0) main.post { listener.onStreamResolution(w, h) }
                }
                "stream_stats" -> {
                    try {
                        rttMs = o.optLong("rtt", rttMs)
                    } catch (_: Exception) {
                    }
                }
                "pipeline_status", "server_apps", "stream_info" -> { /* 暂不消费 */ }

                // 远端打印：PDF 已落在服务器打印池，客户端拉取后进入系统打印界面
                "print_document" -> {
                    val name = o.optString("name")
                    val size = o.optLong("size_bytes", 0L)
                    if (name.isNotEmpty()) main.post { listener.onPrintDocument(name, size) }
                }

                "system" -> {
                    val action = o.optString("action")
                    if (action.isNotEmpty()) {
                        main.post { listener.onSystemCommand(action) }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    /** server_settings 已收 → 启动视频/音频（幂等） */
    private fun maybeStartStreams() {
        if (!serverSettingsReceived) return
        if (!videoStarted) sendText("START_VIDEO")
        if (settings.autoAudio && !audioStarted) sendText("START_AUDIO")
    }

    /** 发送分辨率请求 r,WxH,primary（服务端按此调整桌面分辨率匹配手机屏幕） */
    fun sendResolution(width: Int = clientWidth, height: Int = clientHeight) {
        val w = Protocol.alignResolution(width).coerceIn(16, 4080)
        val h = Protocol.alignResolution(height).coerceIn(16, 4080)
        sendText("r,${w}x${h},primary")
    }

    // ------------------------------------------------------------------
    // 二进制消息路由（高频路径：零主线程跳转）
    // ------------------------------------------------------------------

    private fun handleBinaryMessage(data: ByteArray) {
        if (data.isEmpty()) return
        statBytes.addAndGet(data.size.toLong())
        when (data[0].toInt() and 0xff) {
            Protocol.BINARY_VIDEO -> {
                val header = Protocol.parseVideoHeader(data) ?: return
                statFrames.incrementAndGet()
                statWindowFrames.incrementAndGet()
                statWindowBytes.addAndGet(data.size.toLong())
                // 回压游标（ACK 心跳读取）
                lastFrameId.set(header.frameId.toLong())
                lastFrameAt.set(android.os.SystemClock.elapsedRealtime())
                mediaSink?.onVideoFrame(header, data, 12, data.size - 12)
            }
            Protocol.BINARY_AUDIO -> {
                val (frames, newPts) = Protocol.parseAudioFrames(data, audioLastPts)
                audioLastPts = newPts
                if (frames.isNotEmpty()) {
                    statWindowBytes.addAndGet(data.size.toLong())
                    mediaSink?.onAudioFrames(frames)
                }
            }
            Protocol.BINARY_JPEG -> {
                val header = Protocol.parseJpegHeader(data) ?: return
                statFrames.incrementAndGet()
                statWindowFrames.incrementAndGet()
                statWindowBytes.addAndGet(data.size.toLong())
                lastFrameId.set(header.frameId.toLong())
                lastFrameAt.set(android.os.SystemClock.elapsedRealtime())
                mediaSink?.onJpegStripe(header, data, 6, data.size - 6)
            }
            Protocol.BINARY_GZIP_TEXT -> {
                val text = Protocol.gunzip(data)
                if (text.isNotEmpty()) {
                    try {
                        handleTextMessage(text)
                    } catch (e: Exception) {
                        Log.w(TAG, "gzip text handling error: ${e.message}")
                    }
                }
            }
            else -> { /* 未知类型：向前兼容 */ }
        }
    }

    // ------------------------------------------------------------------
    // 回压 ACK（官方节奏：tick 50ms / 心跳 1000ms）
    // ------------------------------------------------------------------

    private fun sendBackpressureAck() {
        val ws = webSocket ?: return
        val id = lastFrameId.get()
        if (id < 0) return // 尚未收到任何帧
        val now = android.os.SystemClock.elapsedRealtime()
        val isNew = id != lastAckSentId.get()
        val heartbeatDue = now - lastAckSentAt.get() >= ACK_HEARTBEAT_MS
        if (!isNew && !heartbeatDue) return
        // heldMs：该帧等待本 tick 的时长（服务端扣除后估算 RTT）
        val held = (now - lastFrameAt.get()).coerceIn(0, 10_000)
        try {
            ws.send("CLIENT_FRAME_ACK $id $held")
            lastAckSentId.set(id)
            lastAckSentAt.set(now)
        } catch (e: Exception) {
            Log.w(TAG, "ack send failed: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // 统计
    // ------------------------------------------------------------------

    private fun reportStats() {
        val frames = statWindowFrames.getAndSet(0)
        val bytes = statWindowBytes.getAndSet(0)
        val fps = (frames * 1000 / STATS_INTERVAL_MS).toInt()
        val kbps = (bytes * 8 / STATS_INTERVAL_MS / 1000).toInt()
        val s = Stats(fps, kbps, rttMs, statFrames.get(), statBytes.get())
        main.post { listener.onStats(s) }
    }

    // ------------------------------------------------------------------
    // 剪贴板
    // ------------------------------------------------------------------

    private fun deliverClipboardText(b64: String) {
        try {
            if (b64.isEmpty()) {
                main.post { listener.onClipboardText("") }
                return
            }
            val bytes = java.util.Base64.getDecoder().decode(b64)
            main.post { listener.onClipboardText(String(bytes, Charsets.UTF_8)) }
        } catch (_: Exception) {
        }
    }

    /** 本地剪贴板 → 远端（小文本 cw 单发；大文本 cws/cwd/cwe 分段） */
    fun sendClipboardText(text: String) {
        if (text.isEmpty()) return
        try {
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (bytes.size <= CLIPBOARD_CHUNK_RAW) {
                sendInput("cw," + java.util.Base64.getEncoder().encodeToString(bytes))
            } else {
                val tid = (0..999).random()
                sendInput("cws,$tid,${bytes.size}")
                var off = 0
                while (off < bytes.size) {
                    val end = minOf(off + CLIPBOARD_CHUNK_RAW, bytes.size)
                    sendInput(
                        "cwd,$tid," + java.util.Base64.getEncoder()
                            .encodeToString(bytes.copyOfRange(off, end))
                    )
                    off = end
                }
                sendInput("cwe,$tid")
            }
        } catch (e: Exception) {
            Log.w(TAG, "sendClipboardText: ${e.message}")
        }
    }

    /** 主动拉取远端剪贴板（REQUEST_CLIPBOARD） */
    fun requestClipboard() = sendText("REQUEST_CLIPBOARD")

    /** 本地剪贴板二进制 → 远端（图片等）：cb,<mime>,<b64>（小负载单发） */
    fun sendClipboardBinary(mime: String, data: ByteArray) {
        if (data.isEmpty() || data.size > 2 * 1024 * 1024) return // 过大不传（官方小负载路径）
        try {
            sendInput("cb,$mime," + java.util.Base64.getEncoder().encodeToString(data))
        } catch (e: Exception) {
            Log.w(TAG, "sendClipboardBinary: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // 输入原语（InputController 复用；view-only 门禁在此再次生效 = 双保险）
    // ------------------------------------------------------------------

    fun requestKeyframe() = sendText("REQUEST_KEYFRAME")

    fun keyEvent(keysym: Int, down: Boolean) =
        sendInput((if (down) "kd," else "ku,") + keysym)

    fun releaseAllKeys() = sendInput("kr")

    /** 绝对鼠标：m,x,y,mask,scroll */
    fun mouseAbsolute(x: Int, y: Int, mask: Int) = sendInput("m,$x,$y,$mask,0")

    /** 相对鼠标：m2,dx,dy,mask,scroll */
    fun mouseRelative(dx: Int, dy: Int, mask: Int) = sendInput("m2,$dx,$dy,$mask,0")

    /** 滚轮脉冲（RFB 位：bit3=上 bit4=下 bit6=左 bit7=右），magnitude=格数 */
    fun wheelPulse(bit: Int, magnitude: Int) {
        sendInput("m2,0,0,0,$magnitude")
        sendInput("m2,0,0,${1 shl bit},$magnitude")
        sendInput("m2,0,0,0,$magnitude")
    }

    /** 平滑手指滚动（Wayland finger_scroll 可用）：sf,dx,dy —— 流像素 */
    fun fingerScroll(dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        sendInput("sf,${"%.2f".format(dx)},${"%.2f".format(dy)}")
    }

    fun fingerScrollEnd() = sendInput("sfe")

    /** 提交文本（文字输入框 / 输入法）：co,end,text */
    fun commitText(text: String) = sendInput("co,end,$text")

    // ------------------------------------------------------------------
    // v1.2.0：热更新 SETTINGS（部分载荷，官方 sendFullSettingsUpdateToServer 同款机制）
    // ------------------------------------------------------------------

    /** 发送部分 SETTINGS 更新（仅变更的键；服务端热应用，无需重连） */
    fun sendSettingsUpdate(json: JSONObject) {
        sendText("SETTINGS,$json")
        DiagLog.i("热更新 SETTINGS: $json")
    }

    /** 远程命令执行：cmd,<command>（服务端 command_enabled 门禁；回执 system 消息） */
    fun sendCommand(command: String) {
        if (!inputAllowed) return
        sendText("cmd,$command")
        DiagLog.i("发送远程命令: $command")
    }

    // ------------------------------------------------------------------
    // 二进制上行（麦克风 0x02 / 摄像头 0x06 —— 官方 websockets 模式上行链路）
    // ------------------------------------------------------------------

    /** 发送二进制帧（麦克风/摄像头上行）。返回是否成功入队。 */
    fun sendBinary(data: ByteArray): Boolean {
        val ws = webSocket ?: return false
        return try {
            ws.send(okio.ByteString.of(*data))
        } catch (e: Exception) {
            Log.w(TAG, "sendBinary failed: ${e.message}")
            false
        }
    }

    /** 输入门禁（麦克风/摄像头上行同样受 view-only 限制） */
    fun canSendUplink(): Boolean = inputAllowed && !closed.get()

    /** 游戏手柄（标准布局：A=0 B=1 X=2 Y=3 LB=4 RB=5 LT=6 RT=7 Back=8 Start=9
     *  LS=10 RS=11 DUp=12 DDown=13 DLeft=14 DRight=15 Guide=16；轴 LX=0 LY=1 RX=2 RY=3） */
    fun gamepadConnect(index: Int, id: String, axes: Int, buttons: Int) {
        val b64 = java.util.Base64.getEncoder().encodeToString(id.toByteArray(Charsets.ISO_8859_1))
        sendInput("js,c,$index,$b64,$axes,$buttons")
    }

    fun gamepadDisconnect(index: Int) = sendInput("js,d,$index")
    fun gamepadButton(index: Int, button: Int, value: Float) = sendInput("js,b,$index,$button,$value")
    fun gamepadAxis(index: Int, axis: Int, value: Float) = sendInput("js,a,$index,$axis,$value")
    fun gamepadHeld(index: Int) = sendInput("js,h,$index")

    // ------------------------------------------------------------------
    // 失败处理与自动重连
    // ------------------------------------------------------------------

    private fun handleConnectFailure(reason: String, detail: String?, httpCode: Int = -1) {
        if (closed.get() || userRequestedDisconnect.get()) return
        Log.w(TAG, "connect failure: $reason $detail")
        val human = DiagLog.humanizeReason(reason, httpCode)
        if (!settings.autoReconnect) {
            postState(State.FAILED, "$reason｜$human")
            return
        }
        val max = settings.maxReconnects
        if (max in 1..reconnectAttempt) {
            postState(State.FAILED, "$reason｜重连已达上限｜$human")
            return
        }
        scheduleReconnect()
    }

    private fun scheduleReconnect(extraDelayMs: Long = 0L) {
        reconnectAttempt++
        // 指数退避 1s→15s；服务端有 ~750ms 重连防抖（4029），起步即 1s
        val delay = (1000L shl (reconnectAttempt - 1).coerceAtMost(4))
            .coerceAtMost(15_000L) + extraDelayMs
        Log.i(TAG, "reconnect #$reconnectAttempt in ${delay}ms")
        postState(State.RECONNECTING, reconnectAttempt.toString())
        main.removeCallbacks(reconnectRunnable)
        main.postDelayed(reconnectRunnable, delay)
    }

    private fun postState(s: State, detail: String? = null) {
        state = s
        main.post { listener.onState(s, detail) }
    }

    companion object {
        private const val TAG = "ConnectionEngine"
        private const val ACK_INTERVAL_MS = 50L        // 官方 BACKPRESSURE_INTERVAL_MS
        private const val ACK_HEARTBEAT_MS = 1000L     // 官方 ACK_HEARTBEAT_MS
        private const val STATS_INTERVAL_MS = 1000L
        private const val CLIPBOARD_CHUNK_RAW = 768 * 1024
        private const val CLIPBOARD_MAX = 32L * 1024 * 1024
        /** 握手策略总数（0/1/2） */
        private const val WS_STRATEGY_MAX = 2
    }
}

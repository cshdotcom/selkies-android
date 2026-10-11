package com.selkies.client.net

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 端点探测器 —— v1.3.0 连接层修复（针对 Cloudflare + 慢启动 Selkies 服务实测调优）：
 *
 * 1. **真实实测结论（2026-10 真机验证）**：
 *    - 服务器链路：Cloudflare (HTTP/2) → Nginx → Selkies(aiohttp)
 *    - WS 端点：/api/websockets（/api/ws 返回 409 = WebRTC 模式，不能用）
 *    - 无升级 GET /api/websockets → 400（端点存在）
 *    - 401 + WWW-Authenticate: Basic → 凭据缺失
 *    - 带凭据 WS 升级 → 101 成功
 *
 * 2. /api/status 慢：实测该端点可能长时间不回（服务器冷启动/慢盘）。
 *    探测只等 STATUS_TIMEOUT_MS，超时/网络错误视为非致命，继续 WS 路径探测
 *    —— 彻底解决"一直连不上也没有错误"的卡死。
 *
 * 3. WS 保活：v1.3.0 最终方案 = 完全禁用客户端 ping（对齐浏览器行为）。
 *    服务器经 Cloudflare 不回客户端 ping 的 pong，OkHttp 会主动断连；
 *    浏览器从不发 ping，故网页不断；保活靠 CLIENT_FRAME_ACK 心跳（1s）。
 *
 * 4. **301 重定向**：Nginx http→https 跳转，手动跟随（最多 5 跳）保留认证头。
 *
 * 5. **双路径**：/api/websockets 优先；409（WebRTC 模式）明确报错而非静默回退。
 */
object EndpointProber {

    /** 模拟 Chrome 桌面浏览器的 UA —— 与网页客户端一致，避免 CF 拦截 */
    const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /** WS 层保活间隔（秒）。Cloudflare 空闲超时约 30s，10s ping 有充分余量 */
    const val WS_PING_INTERVAL_SEC = 10L

    sealed class ProbeResult {
        /** 探测成功：wsPaths 按优先级排列的候选路径；finalBase 是跟随重定向后的 HTTP 基址 */
        data class Ready(val finalBase: HttpUrl, val wsPaths: List<String>) : ProbeResult()

        /** 服务器要求 Basic 认证 */
        data class NeedAuth(val realm: String?) : ProbeResult()

        /** 服务器以 WebRTC 模式运行（WS 端点 409）—— 本客户端仅支持 websockets 模式 */
        data class WebRtcMode(val path: String) : ProbeResult()

        /** 探测失败（网络错误 / 超时 / 无法识别的服务器） */
        data class Failed(val reason: String, val detail: String? = null) : ProbeResult()
    }

    /** /api/status 探测超时：该端点可能极慢，超时非致命 */
    private const val STATUS_TIMEOUT_MS = 8_000L

    /** WS 路径探测超时 */
    private const val WS_PROBE_TIMEOUT_MS = 10_000L

    private fun probeClient(trustAllCerts: Boolean, timeoutMs: Long): OkHttpClient {
        val b = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs + 2_000L, TimeUnit.MILLISECONDS)
        if (trustAllCerts) applyTrustAll(b)
        return b.build()
    }

    /** 信任所有证书（自签名场景）。仅影响本客户端的网络栈，不改变系统设置。 */
    fun applyTrustAll(builder: OkHttpClient.Builder) {
        try {
            val tm = object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            }
            val sslCtx = javax.net.ssl.SSLContext.getInstance("TLS")
            sslCtx.init(null, arrayOf(tm), java.security.SecureRandom())
            builder.sslSocketFactory(sslCtx.socketFactory, tm)
            builder.hostnameVerifier { _, _ -> true }
        } catch (_: Exception) {
            // 初始化失败则回退默认校验
        }
    }

    /**
     * 对某个地址发起一次探测（阻塞调用，请在 IO 线程执行）。
     */
    fun probe(base: HttpUrl, basicAuth: String?, trustAllCerts: Boolean = false): ProbeResult {
        val client = probeClient(trustAllCerts, STATUS_TIMEOUT_MS)
        return try {
            probeInner(client, base, basicAuth, trustAllCerts)
        } catch (e: InterruptedException) {
            ProbeResult.Failed("interrupted")
        } finally {
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun probeInner(client: OkHttpClient, initialBase: HttpUrl, basicAuth: String?, trustAllCerts: Boolean): ProbeResult {
        var current = initialBase

        // ---- 第一步：GET /api/status（尽力而为；慢/超时不阻塞主流程）----
        var statusUrl = current.newBuilder().encodedPath(joinPath(current.encodedPath, Protocol.HTTP_STATUS_PATH)).build()
        var lastCode = -1

        for (hop in 0 until 6) {
            val req = baseRequest(statusUrl, basicAuth, headOnly = false).build()
            val resp = try {
                client.newCall(req).execute()
            } catch (e: Exception) {
                // v1.3.0：/api/status 超时/网络错误不再判死 —— 服务器可能冷启动。
                // 记录后继续探测 WS 路径（真实连接路径）。
                DiagLog.w("/api/status 不可达（非致命）: ${e.javaClass.simpleName}: ${e.message}")
                break
            }
            try {
                lastCode = resp.code
                DiagLog.i("GET $statusUrl → ${resp.code}")
                if (resp.code in 301..308 && resp.header("Location") != null) {
                    val loc = resp.header("Location")!!
                    DiagLog.i("重定向 $hop: → $loc")
                    val next = statusUrl.resolve(loc)
                        ?: return ProbeResult.Failed("bad_redirect", loc)
                    statusUrl = next
                    current = stripPathSuffix(next, Protocol.HTTP_STATUS_PATH) ?: next
                    continue
                }
                if (resp.code == 401) {
                    // 需要 Basic 认证（Basic 场景下即弹凭据框）
                    return ProbeResult.NeedAuth(parseRealm(resp.header("WWW-Authenticate")))
                }
                break
            } finally {
                resp.close()
            }
        }

        when (lastCode) {
            200, 404, -1 -> { /* 200=确认 Selkies；404/-1=不确定，继续 */ }
            in 500..599 -> { /* 服务端错误，仍继续尝试 WS */ }
            403 -> return ProbeResult.Failed(
                "forbidden",
                "HTTP 403：IP/来源被拒绝（若走 Cloudflare，请关闭该域名的代理或加白）"
            )
            else -> { /* 其他状态码仍继续尝试 WS 端点 */ }
        }

        // ---- 第二步：逐个探测 WS 路径（GET 不带 Upgrade，观察响应）----
        // aiohttp 的 WS 路由对非升级 GET 返回 400（"No WebSocket UPGRADE hdr"）—— 端点存在。
        // 404 = 路径不存在；409 = 服务器运行在 WebRTC 模式；401 = 凭据缺失/错误。
        val wsProbeClient = probeClient(trustAllCerts, WS_PROBE_TIMEOUT_MS)
        try {
            val found = ArrayList<String>()
            var webRtcPath: String? = null
            for (path in listOf(Protocol.WS_PATH_PRIMARY, Protocol.WS_PATH_FALLBACK)) {
                val wsProbeUrl = current.newBuilder()
                    .encodedPath(joinPath(current.encodedPath, path))
                    .build()
                val req = baseRequest(wsProbeUrl, basicAuth, headOnly = false).build()
                val resp = try {
                    wsProbeClient.newCall(req).execute()
                } catch (e: Exception) {
                    DiagLog.w("WS 路径探测失败 $path（非致命）: ${e.message}")
                    continue
                }
                resp.use {
                    DiagLog.i("GET $wsProbeUrl → ${it.code}")
                    when {
                        it.code == 401 -> {
                            return ProbeResult.NeedAuth(parseRealm(it.header("WWW-Authenticate")))
                        }
                        it.code == 409 -> {
                            // WebRTC 模式：记住路径，若两个路径都 409 再报错
                            webRtcPath = path
                        }
                        it.code == 403 -> {
                            return ProbeResult.Failed(
                                "forbidden",
                                "HTTP 403（$path）：被反代/WAF 拒绝"
                            )
                        }
                        it.code != 404 -> found.add(path)
                    }
                }
            }
            if (found.isNotEmpty()) return ProbeResult.Ready(current, found)
            // 一个都没探到：若全部 409 → WebRTC 模式
            if (webRtcPath != null) return ProbeResult.WebRtcMode(webRtcPath)
            // 探测全部失败（超时/网络错）—— 不判死，交给 WS 握手实测
            DiagLog.w("WS 路径探测无果，按默认顺序直接握手")
            return ProbeResult.Ready(current, listOf(Protocol.WS_PATH_PRIMARY, Protocol.WS_PATH_FALLBACK))
        } finally {
            wsProbeClient.dispatcher.executorService.shutdown()
        }
    }

    /**
     * 构造浏览器风格请求。
     * Origin 必须与 Host 匹配（Selkies 服务端校验规则：netloc == host）。
     *
     * @param strategy 握手策略：0=完整浏览器头（默认）；1=无 Origin；2=极简头
     */
    fun baseRequest(url: HttpUrl, basicAuth: String?, headOnly: Boolean, strategy: Int = 0): Request.Builder {
        val b = if (headOnly) Request.Builder().head() else Request.Builder().get()
        b.url(url)
        b.header("User-Agent", BROWSER_UA)
        if (strategy == 0) {
            val origin = buildString {
                append(if (url.isHttps) "https" else "http")
                append("://")
                append(url.host)
                if (url.port != 80 && url.port != 443) append(":").append(url.port)
            }
            b.header("Origin", origin)
        }
        if (strategy <= 1) {
            b.header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            b.header("Cache-Control", "no-cache")
            b.header("Pragma", "no-cache")
        }
        if (basicAuth != null) b.header("Authorization", basicAuth)
        return b
    }

    /** 将子目录前缀与 API 路径拼接：("/selkies", "/api/status") → "/selkies/api/status" */
    fun joinPath(prefix: String, apiPath: String): String {
        val p = prefix.trim().trimEnd('/')
        return if (p.isEmpty()) apiPath else "/$p$apiPath"
    }

    /** 重定向后把尾部 API 路径剥掉，还原基址 */
    private fun stripPathSuffix(url: HttpUrl, suffix: String): HttpUrl? {
        val path = url.encodedPath
        val idx = path.lastIndexOf(suffix)
        if (idx < 0) return null
        val base = path.substring(0, idx)
        return url.newBuilder().encodedPath(if (base.isEmpty()) "/" else base).build()
    }

    /** 从 WWW-Authenticate 头解析 realm（用于弹窗提示） */
    private fun parseRealm(header: String?): String? = try {
        if (header == null) null
        else Regex("realm=\"([^\"]*)\"").find(header)?.groupValues?.get(1)
    } catch (_: Exception) {
        null
    }
}

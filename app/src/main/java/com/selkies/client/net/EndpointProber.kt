package com.selkies.client.net

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 端点探测器 —— 修复用户上报的三大连接层问题：
 *
 * 1. **301 重定向失败**：Nginx 常见把 http 301 到 https。OkHttp 的 WebSocket
 *    客户端不会跟随握手重定向，所以先用 HTTP 探测，手动跟随 301/302/307/308
 *    （最多 5 跳），记录最终 scheme/host/port/path，再以最终地址发起 wss 连接。
 *
 * 2. **Cloudflare 400**：CF 等反代对非浏览器特征的请求直接 400。这里所有请求
 *    （探测与 WS 握手）都带完整浏览器请求头（UA / Origin / Accept-Language），
 *    Origin 严格等于 scheme://host[:port]，与 Host 头匹配 —— 这正是 Selkies
 *    服务端 Origin 校验（stream_server._is_origin_allowed）要求的形态。
 *
 * 3. **双路径兼容**：优先 /api/websockets（Selkies v2），失败回退 /api/ws。
 *    探测顺序：/api/status（免认证健康检查，200 即确认是 Selkies 服务）→
 *    逐个尝试 WS 路径是否响应（非 404 即存在）。
 *
 * 4. **Basic Auth**：探测返回 401 + WWW-Authenticate: Basic 时上报 NEED_AUTH，
 *    由 UI 弹出账号密码输入，随后带上 Authorization 头重新探测与连接。
 */
object EndpointProber {

    /** 模拟 Chrome 桌面浏览器的 UA —— 与网页客户端一致，避免 CF 拦截 */
    const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    sealed class ProbeResult {
        /** 探测成功：finalWsUrls 按优先级排列的候选 WS 地址；finalBase 是跟随重定向后的 HTTP 基址 */
        data class Ready(val finalBase: HttpUrl, val wsPaths: List<String>) : ProbeResult()

        /** 服务器要求 Basic 认证 */
        data class NeedAuth(val realm: String?) : ProbeResult()

        /** 探测失败（网络错误 / 超时 / 无法识别的服务器） */
        data class Failed(val reason: String, val detail: String? = null) : ProbeResult()
    }

    /** 单独构造探测用 HTTP 客户端：不自动跟随重定向（手动跟随以便追踪 scheme 变化）
     * @param trustAllCerts 服务器使用自签名证书时置 true（LSIO 3001 自签 https 场景） */
    private fun probeClient(trustAllCerts: Boolean): OkHttpClient {
        val b = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
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

    /** 探测单次请求的超时（毫秒） */
    private const val PROBE_TIMEOUT_MS = 12_000L

    /**
     * 对某个地址发起一次探测（阻塞调用，请在 IO 线程执行）。
     *
     * @param base 初始基址（http 或 https 均可，会跟随重定向切换）
     * @param basicAuth 可选的 "Basic xxx" 凭据头
     * @param trustAllCerts 自签名证书信任开关
     */
    fun probe(base: HttpUrl, basicAuth: String?, trustAllCerts: Boolean = false): ProbeResult {
        val client = probeClient(trustAllCerts)
        return try {
            probeInner(client, base, basicAuth)
        } catch (e: InterruptedException) {
            ProbeResult.Failed("interrupted")
        } finally {
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun probeInner(client: OkHttpClient, initialBase: HttpUrl, basicAuth: String?): ProbeResult {
        var current = initialBase

        // ---- 第一步：GET /api/status，手动跟随重定向 ----
        var statusUrl = current.newBuilder().encodedPath(joinPath(current.encodedPath, Protocol.HTTP_STATUS_PATH)).build()
        var lastCode = -1
        var lastWwwAuth: String? = null

        for (hop in 0 until 6) {
            val req = baseRequest(statusUrl, basicAuth, headOnly = false).build()
            val resp = try {
                client.newCall(req).execute()
            } catch (e: Exception) {
                DiagLog.e("探测请求失败: ${e.javaClass.simpleName}: ${e.message}")
                return ProbeResult.Failed(
                    if (e.message?.contains("timeout", true) == true) "timeout" else "network",
                    e.message
                )
            }
            try {
                lastCode = resp.code
                lastWwwAuth = resp.header("WWW-Authenticate")
                DiagLog.i("GET $statusUrl → ${resp.code}")
                if (resp.code in 301..308 && resp.header("Location") != null) {
                    // 手动跟随重定向：http→https 切换就在这里发生
                    val loc = resp.header("Location")!!
                    DiagLog.i("重定向 $hop: → $loc")
                    val next = statusUrl.resolve(loc)
                        ?: return ProbeResult.Failed("bad_redirect", loc)
                    statusUrl = next
                    current = stripPathSuffix(next, Protocol.HTTP_STATUS_PATH) ?: next
                    continue
                }
                if (resp.code == 401) {
                    // 需要 Basic 认证（或 token —— Basic 场景下即弹凭据框）
                    return ProbeResult.NeedAuth(parseRealm(lastWwwAuth))
                }
                break
            } finally {
                resp.close()
            }
        }

        when (lastCode) {
            200 -> {
                // 确认是 Selkies 服务器；current 为跟随重定向后的基址
            }
            404 -> {
                // /api/status 不存在：可能不是 Selkies，也可能是旧版 —— 继续尝试 WS 端点
            }
            in 500..599 -> {
                // 服务端错误，但仍证明"有东西在监听"，继续尝试 WS
            }
            -1 -> return ProbeResult.Failed("no_response")
            else -> {
                // 其他状态码（403 等）：仍继续尝试 WS 端点 —— 有些部署对 status 有额外保护
            }
        }

        // ---- 第二步：逐个探测 WS 路径（GET 不带 Upgrade，观察响应）----
        // aiohttp 的 WS 路由对非升级 GET 返回 400（"no websocket upgrade"），
        // 404 说明路径不存在。Basic Auth 开启时这里会返回 401 + WWW-Authenticate
        // （/api/status 免认证，探测不出认证需求 —— 认证检测必须在这一步做）。
        val found = ArrayList<String>()
        for (path in listOf(Protocol.WS_PATH_PRIMARY, Protocol.WS_PATH_FALLBACK)) {
            val wsProbeUrl = current.newBuilder()
                .encodedPath(joinPath(current.encodedPath, path))
                .build()
            val req = baseRequest(wsProbeUrl, basicAuth, headOnly = false).build()
            val resp = try {
                client.newCall(req).execute()
            } catch (e: Exception) {
                DiagLog.w("WS 路径探测失败 $path: ${e.message}")
                continue // 该路径网络失败，尝试下一个
            }
            resp.use {
                DiagLog.i("GET $wsProbeUrl → ${it.code}")
                when {
                    it.code == 401 -> {
                        // Basic Auth 凭据缺失或不正确 → 交由 UI 弹凭据表单
                        return ProbeResult.NeedAuth(parseRealm(it.header("WWW-Authenticate")))
                    }
                    it.code != 404 -> found.add(path)
                }
            }
        }
        // 一个都没探到也按默认顺序连（有些反代对无 Upgrade 的 GET 行为怪异，
        // 直接交给 WS 握手去验证 —— 引擎内部还有回退逻辑兜底）
        val paths = if (found.isNotEmpty()) found
        else listOf(Protocol.WS_PATH_PRIMARY, Protocol.WS_PATH_FALLBACK)
        return ProbeResult.Ready(current, paths)
    }

    /**
     * 构造浏览器风格请求。
     * Origin 必须与 Host 匹配（Selkies 服务端校验规则：netloc == host）。
     *
     * @param strategy v1.2.0 握手策略：0=完整浏览器头（默认）；1=无 Origin（某些反代对
     * 跨源 Origin 头直接 RST 连接）；2=极简头（仅 UA + 认证，最接近裸 WS 握手）。
     * 策略轮换仅在默认策略握手失败时由引擎逐个尝试。
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
            // 对齐浏览器 WS 握手头（部分反代/WAF 严格匹配）
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

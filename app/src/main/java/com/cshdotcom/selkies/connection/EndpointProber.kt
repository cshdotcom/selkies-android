package com.cshdotcom.selkies.connection

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 端点探测器 —— 修复已知 BUG 的核心组件：
 *
 * BUG#1 301 重定向：OkHttp 的 WebSocket 握手不会自动跟随 3xx（官方浏览器无此问题、
 *       原生客户端常见）。这里先用 HTTP GET 手动逐跳跟随重定向（最多 8 跳，含
 *       http→https 的协议切换、端口改写），得到最终 URL 后再在其上发起 WS 握手。
 * BUG#2 Cloudflare 400：GET/WS 请求全部使用与官方网页前端一致的浏览器请求头
 *       （User-Agent / Origin / Accept-Language / Cache-Control / Pragma），
 *       不携带任何自定义异常头。
 * BUG#3 Basic Auth：GET 探测拿到 401 + WWW-Authenticate: Basic 时回报上层弹账号
 *       密码框；携带凭据复测通过后才继续 WS 握手（Authorization: Basic 头）。
 * BUG#4 路径回退：按 [ /api/websockets, /api/ws ] 顺序探测，404/不存在则回退旧路径。
 */
object EndpointProber {

    sealed class Outcome {
        /** 探测通过，可直接在 wsUrl 上发起握手 */
        data class Ready(val wsUrl: HttpUrl, val origin: String, val legacyPath: Boolean) : Outcome()
        /** 需要 Basic 凭据（401 + WWW-Authenticate: Basic） */
        data class NeedAuth(val wsUrl: HttpUrl, val realm: String?) : Outcome()
        /** Cloudflare 拦截（400 且带 CF 特征头） */
        data class CloudflareBlocked(val httpCode: Int, val url: HttpUrl) : Outcome()
        /** 其它错误 */
        data class Failed(val reason: String) : Outcome()
    }

    data class Creds(val user: String, val password: String)

    private fun authHeader(creds: Creds?): String? =
        creds?.let { "Basic " + java.util.Base64.getEncoder().encodeToString("${it.user}:${it.password}".toByteArray(Charsets.UTF_8)) }

    /** 探测一次：跟随重定向 + 判定 401/404/CF，返回（最终URL, 服务器实际返回码, 鉴权质询） */
    private data class ProbeResult(val finalUrl: HttpUrl, val code: Int, val needBasic: Boolean, val realm: String?, val cfBlocked: Boolean, val bodyHint: String?)

    private suspend fun probeOnce(client: OkHttpClient, url: HttpUrl, creds: Creds?): ProbeResult {
        var current = url
        var hops = 0
        while (true) {
            val req = Request.Builder()
                .get()
                .url(current)
                .apply {
                    val origin = "${if (current.scheme == "https") "https" else "http"}://${current.host}${if (current.port !in listOf(80, 443)) ":${current.port}" else ""}"
                    for ((k, v) in Protocol.browserHeaders(origin)) header(k, v)
                    if (current.scheme == "https") header("Referer", "$origin/")
                    authHeader(creds)?.let { header("Authorization", it) }
                }
                .build()
            val resp = client.newCall(req).await2xxOrAny()
            // Cloudflare 特征：server 头或 cf-ray 头
            val isCf = resp.header("server")?.contains("cloudflare", ignoreCase = true) == true
                || resp.header("cf-ray") != null
            if (resp.code in 301..308) {
                if (++hops > 8) return ProbeResult(current, resp.code, false, null, false, "too many redirects")
                val loc = resp.header("Location") ?: return ProbeResult(current, resp.code, false, null, false, "redirect without Location")
                val next = current.resolve(loc)
                    ?: return ProbeResult(current, resp.code, false, null, false, "bad redirect target: $loc")
                if (next.scheme != "http" && next.scheme != "https")
                    return ProbeResult(current, resp.code, false, null, false, "redirect to non-http: $loc")
                // 保留查询（子路径代理场景），路径照 Location
                resp.close()
                current = next
                continue
            }
            val needBasic = resp.code == 401 &&
                resp.header("WWW-Authenticate")?.contains("Basic", ignoreCase = true) == true
            val realm = Regex("""realm="([^"]*)"""").find(resp.header("WWW-Authenticate") ?: "")?.groupValues?.get(1)
            val body = try { resp.body?.string()?.take(180) } catch (e: Exception) { null }
            val code = resp.code
            resp.close()
            return ProbeResult(current, code, needBasic, realm, isCf && code == 400, body)
        }
    }

    /**
     * 完整解析流程。
     * @param base http(s)://host:port 形式的基址
     */
    suspend fun resolve(client: OkHttpClient, base: HttpUrl, creds: Creds?): Outcome {
        for (pathIdx in Protocol.WS_PATHS.indices) {
            val path = Protocol.WS_PATHS[pathIdx]
            val url = base.newBuilder().encodedPath(path).build()
            val r = try {
                probeOnce(client, url, creds)
            } catch (e: Exception) {
                return Outcome.Failed("网络错误：${e.message}")
            }
            // 404：路径不存在 → 尝试下一个路径
            if (r.code == 404) {
                if (pathIdx < Protocol.WS_PATHS.size - 1) continue
                return Outcome.Failed("服务器可达但两个 WebSocket 路径均返回 404（/api/websockets 与 /api/ws）。请确认后端为 Selkies 2.0。")
            }
            if (r.cfBlocked) return Outcome.CloudflareBlocked(r.code, r.finalUrl)
            if (r.needBasic) return Outcome.NeedAuth(toWs(r.finalUrl), r.realm)
            // Cloudflare 400 但无 Basic 质询：提示
            if (r.code == 400 && r.finalUrl.host != base.host) { /* 代理改写 host，仍继续 */ }
            // 路径存在（200/400/405/426/101 等都视为存在）
            return Outcome.Ready(toWs(r.finalUrl), originOf(r.finalUrl), legacyPath = pathIdx > 0)
        }
        return Outcome.Failed("未找到可用端点")
    }

    /** 复核凭据是否有效（编辑页"测试连接"用） */
    suspend fun verifyCreds(client: OkHttpClient, wsUrl: HttpUrl, creds: Creds): Boolean {
        val httpUrl = toHttp(wsUrl)
        val r = try { probeOnce(client, httpUrl, creds) } catch (e: Exception) { return false }
        return !r.needBasic && r.code != 401
    }

    fun toWs(u: HttpUrl): HttpUrl {
        val scheme = if (u.scheme == "https") "wss" else "ws"
        return u.newBuilder().scheme(scheme).build()
    }

    fun toHttp(u: HttpUrl): HttpUrl {
        val scheme = if (u.scheme == "wss") "https" else "http"
        return u.newBuilder().scheme(scheme).build()
    }

    fun originOf(u: HttpUrl): String {
        val scheme = if (u.scheme == "https" || u.scheme == "wss") "https" else "http"
        return if (u.port in listOf(80, 443)) "$scheme://${u.host}" else "$scheme://${u.host}:${u.port}"
    }

    /** Call.await 的最小实现（不引入额外依赖） */
    private suspend fun okhttp3.Call.await2xxOrAny(): okhttp3.Response =
        suspendCancellableCoroutine { cont ->
            enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) =
                    cont.resumeWithException(e)
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) =
                    cont.resume(response)
            })
            cont.invokeOnCancellation { cancel() }
        }
}

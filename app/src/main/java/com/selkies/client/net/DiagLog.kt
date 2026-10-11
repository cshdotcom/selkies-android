package com.selkies.client.net

import android.os.SystemClock
import android.util.Log

/**
 * 连接诊断日志 —— 环形缓冲，记录连接全过程每一步。
 *
 * 目的：用户上报「ws: unexpected end of stream」等网络层错误时，没有 adb 也无法定位。
 * DiagLog 把探测/重定向/认证/WS 握手/失败重试的每个环节都留痕，会话页与
 * 服务器编辑页均可一键导出（系统分享 / 复制到剪贴板），用户回传文本即可远程排障。
 *
 * 线程安全：synchronized 环形队列，任意线程可写；读取快照同样加锁。
 */
object DiagLog {

    private const val TAG = "SelkiesDiag"
    private const val MAX_LINES = 600

    private val lines = ArrayDeque<String>(MAX_LINES)
    private val startTime = SystemClock.elapsedRealtime()
    private var session = 0

    /** 新会话（每次发起连接时递增，导出时标注） */
    fun newSession(): Int {
        synchronized(lines) {
            session++
            lines.clear()
            add("==== 诊断会话 #$session ${timestamp()} ====")
            return session
        }
    }

    fun i(msg: String) = add("I $msg")
    fun w(msg: String) = add("W $msg")
    fun e(msg: String) = add("E $msg")

    private fun add(line: String) {
        synchronized(lines) {
            if (lines.size >= MAX_LINES) lines.removeFirst()
            val rel = (SystemClock.elapsedRealtime() - startTime) / 1000.0
            lines.addLast("[${"%.1f".format(rel)}s] $line")
        }
        Log.d(TAG, line)
    }

    /** 完整快照（换行分隔） */
    fun dump(): String = synchronized(lines) {
        lines.joinToString("\n")
    }

    /** 尾部 N 行（给 UI 展示用） */
    fun tail(n: Int = 40): String = synchronized(lines) {
        if (lines.size <= n) lines.joinToString("\n")
        else lines.toList().takeLast(n).joinToString("\n")
    }

    private fun timestamp(): String {
        val cal = java.util.Calendar.getInstance()
        return "%04d-%02d-%02d %02d:%02d:%02d".format(
            cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH), cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE), cal.get(java.util.Calendar.SECOND)
        )
    }

    // ------------------------------------------------------------------
    // 常见网络错误 → 人性化中文提示（附带行动建议）
    // ------------------------------------------------------------------

    /** 把底层异常/状态码翻译成用户能看懂、能行动的提示 */
    fun humanize(t: Throwable?, code: Int): String =
        humanizeReason("${t?.javaClass?.simpleName ?: ""}: ${t?.message ?: ""}", code)

    /** 按原因字符串翻译（reason 形如 "ws: unexpected end of stream..."） */
    fun humanizeReason(reasonIn: String, code: Int): String {
        val msg = reasonIn
        return when {
            msg.contains("unexpected end of stream", true) ->
                "服务器在握手时中断了连接。常见原因：反向代理未正确转发 WebSocket（需要 proxy_set_header Upgrade/Connection），或中间层（CDN/WAF）拦截了升级请求。"
            msg.contains("CLEARTEXT", true) ->
                "Android 禁止明文 HTTP 连接。请在服务器设置中勾选 HTTPS，或改用 https 地址。"
            msg.contains("SSLException", true) || msg.contains("handshake failed", true) ->
                "TLS 握手失败。若服务器使用自签名证书，请在服务器设置中开启「信任自签名证书」。"
            msg.contains("timeout", true) || msg.contains("SocketTimeout", true) ->
                "连接超时。请检查地址/端口是否可达、网络是否通畅。"
            msg.contains("ECONNREFUSED") || msg.contains("failed to connect", true) ->
                "无法连接到服务器：端口未开放或地址错误。"
            msg.contains("Unable to resolve", true) || msg.contains("UnknownHost", true) ->
                "域名无法解析。请检查地址拼写与 DNS。"
            code == 400 ->
                "服务器拒绝请求 (400)。可能被反向代理或 Cloudflare 的规则拦截。"
            code == 403 ->
                "服务器拒绝访问 (403)。Origin/来源校验未通过，或 IP 被限制。"
            code in 500..599 ->
                "服务器/反向代理错误 ($code)。请检查服务端与反代日志。"
            code in 200..399 && code != -1 ->
                "握手被拒绝 ($code)。"
            else -> "网络错误：${msg.take(80)}"
        }
    }
}

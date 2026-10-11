package com.selkies.client.net

import android.net.Uri
import android.util.Log
import com.selkies.client.data.ServerConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * 远端文件 API —— 对照 selkies v2 stream_server.py：
 *  - GET /api/files/<path>/  → HTML 目录列表（fancy_index_handler），
 *    行格式 <tr><td><a href="name">name</a></td><td>mtime</td><td>size</td></tr>；
 *  - GET /api/files/<path>   → 文件（attachment 下载）；
 *  - GET /api/print/<name>   → 打印池 PDF；
 *  - GET /api/screenshot     → 当前画面 PNG。
 *
 * 认证：与 WS 一致的 Basic 头（浏览器式 UA，Cloudflare 兼容）。
 */
object FilesApi {

    data class Entry(
        val name: String,
        val isDir: Boolean,
        val mtime: String,
        val size: String
    )

    private fun client(cfg: ServerConfig): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .apply { if (cfg.trustAllCerts) EndpointProber.applyTrustAll(this) }
            .build()

    fun baseUrl(cfg: ServerConfig): String = buildString {
        append(if (cfg.https) "https://" else "http://")
        append(cfg.host)
        if (cfg.port != 80 && cfg.port != 443) append(":").append(cfg.port)
        append(cfg.normalizedPath.trimEnd('/'))
    }

    private fun authHeader(cfg: ServerConfig): String? =
        if (!cfg.username.isNullOrEmpty() || !cfg.password.isNullOrEmpty()) {
            "Basic " + java.util.Base64.getEncoder().encodeToString(
                "${cfg.username}:${cfg.password ?: ""}".toByteArray(Charsets.UTF_8)
            )
        } else null

    private fun Request.Builder.applyCommon(cfg: ServerConfig): Request.Builder {
        header("User-Agent", EndpointProber.BROWSER_UA)
        authHeader(cfg)?.let { header("Authorization", it) }
        return this
    }

    /** 目录列表（path 为相对 Uploads 根的路径，"" = 根） */
    fun listDirectory(cfg: ServerConfig, path: String): List<Entry> {
        val clean = path.trim('/')
        val url = baseUrl(cfg) + "/api/files/" +
                Uri.encode(clean, "").replace("%2F", "/").let { if (it.isEmpty()) "" else "$it/" }
        val req = Request.Builder().url(url).applyCommon(cfg).get().build()
        client(cfg).newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val html = resp.body?.string() ?: ""
            return parseListing(html)
        }
    }

    /** 解析 fancy_index HTML 表格 */
    fun parseListing(html: String): List<Entry> {
        val out = ArrayList<Entry>()
        // <a href="name">name</a> + 两个 <td>
        val pattern = Pattern.compile(
            "<td><a href=\"([^\"]+)\">([^<]*)</a></td>\\s*<td>([^<]*)</td>\\s*<td>([^<]*)</td>",
            Pattern.CASE_INSENSITIVE
        )
        val m = pattern.matcher(html)
        while (m.find()) {
            try {
                val href = m.group(1) ?: continue
                val label = m.group(2) ?: href
                val name = java.net.URLDecoder.decode(href, "UTF-8")
                    .removeSuffix("/")
                if (name.isEmpty()) continue
                val isDir = href.endsWith("/") || name == ".."
                out.add(Entry(name, isDir, m.group(3) ?: "", m.group(4) ?: ""))
            } catch (_: Exception) {
            }
        }
        // 目录在前，名称自然序（服务器已排序，前端再保证一次）
        return out.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    /** 下载文件到目标（流式写盘，onProgress(已读, 总长)） */
    fun download(cfg: ServerConfig, path: String, dest: File, onProgress: (Long, Long) -> Unit) {
        val clean = path.trim('/')
        val url = baseUrl(cfg) + "/api/files/" +
                Uri.encode(clean, "").replace("%2F", "/")
        val req = Request.Builder().url(url).applyCommon(cfg).get().build()
        client(cfg).newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("empty body")
            val total = body.contentLength()
            dest.parentFile?.mkdirs()
            body.byteStream().use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        read += n
                        onProgress(read, total)
                    }
                }
            }
        }
    }

    /** 打印池文档下载（PDF） */
    fun downloadPrint(cfg: ServerConfig, name: String, dest: File): File {
        val url = baseUrl(cfg) + "/api/print/" + Uri.encode(name, "")
        val req = Request.Builder().url(url).applyCommon(cfg).get().build()
        client(cfg).newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("empty body")
            dest.parentFile?.mkdirs()
            body.byteStream().use { input ->
                dest.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            }
        }
        return dest
    }

    /** 截图（GET /api/screenshot → PNG） */
    fun screenshot(cfg: ServerConfig, dest: File): File {
        val url = baseUrl(cfg) + "/api/screenshot"
        val req = Request.Builder().url(url).applyCommon(cfg).get().build()
        client(cfg).newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("empty body")
            dest.parentFile?.mkdirs()
            body.byteStream().use { input ->
                dest.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            }
        }
        return dest
    }

    /** 保存到公共下载目录的通用实现（MediaStore API 29+；旧版直写公共 Downloads） */
    fun saveToDownloads(
        context: android.content.Context,
        displayName: String,
        mime: String,
        bytes: () -> ByteArray
    ): String? = try {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: return null
            context.contentResolver.openOutputStream(uri)?.use { os ->
                val data = bytes()
                os.write(data)
                os.flush()
            }
            values.clear()
            values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            "Downloads/$displayName"
        } else {
            @Suppress("DEPRECATION")
            val dir = android.os.Environment
                .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            val f = File(dir, displayName)
            dir.mkdirs()
            f.writeBytes(bytes())
            f.absolutePath
        }
    } catch (e: Exception) {
        Log.w("FilesApi", "saveToDownloads: ${e.message}")
        null
    }
}

package com.selkies.client.net

import com.selkies.client.data.ServerConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 远程应用目录 —— 对照官方 lib/app-commands.js + stream_server.py：
 *  - 服务端配置了本地 proot-apps 仓库（server_settings.apps_local_repo == true）时
 *    目录来自服务器自身：GET {base}/api/apps/metadata.yml（带会话认证）；
 *  - 否则来自 GitHub 远程仓库：
 *    https://raw.githubusercontent.com/linuxserver/proot-apps/master/metadata/metadata.yml
 *  - 动作命令（服务端 shell 执行，cmd,<command> 消息）：
 *      启动: selkies-proot run <name>
 *      安装: selkies-proot install <name>
 *      卸载: selkies-proot remove <name>
 *      更新: selkies-proot update <name>
 */
object AppsApi {

    private const val REMOTE_CATALOG =
        "https://raw.githubusercontent.com/linuxserver/proot-apps/master/metadata/metadata.yml"

    data class AppEntry(
        val name: String,        // 机器名（命令参数）
        val fullName: String,    // 显示名
        val icon: String,        // 图标文件名（SVG，客户端用字母头像代替）
        val description: String,
        val disabled: Boolean
    )

    private fun client(cfg: ServerConfig?): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
        if (cfg?.trustAllCerts == true) EndpointProber.applyTrustAll(b)
        return b.build()
    }

    /**
     * 拉取应用目录。优先服务器本地仓库（apps_local_repo），404/失败回退 GitHub。
     * @return 目录列表 + 来源是否为服务器本地
     */
    fun fetchCatalog(cfg: ServerConfig, localRepo: Boolean): Pair<List<AppEntry>, Boolean> {
        if (localRepo) {
            try {
                val text = fetchText(FilesApi.baseUrl(cfg) + "/api/apps/metadata.yml", cfg)
                if (text.isNotBlank()) return parseCatalog(text) to true
            } catch (_: Exception) {
                // 本地仓库不可用 → 回退远程
            }
        }
        val text = fetchText(REMOTE_CATALOG, null)
        return parseCatalog(text) to false
    }

    private fun fetchText(url: String, cfg: ServerConfig?): String {
        val b = Request.Builder().url(url)
            .header("User-Agent", EndpointProber.BROWSER_UA)
            .get()
        if (cfg != null && (!cfg.username.isNullOrEmpty() || !cfg.password.isNullOrEmpty())) {
            b.header(
                "Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString(
                    "${cfg.username}:${cfg.password ?: ""}".toByteArray(Charsets.UTF_8)
                )
            )
        }
        client(cfg).newCall(b.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            return resp.body?.string() ?: ""
        }
    }

    /**
     * 解析 proot-apps metadata.yml（子集语法，行式解析足够稳定）：
     * include:
     *   - name: anki
     *     full_name: Anki
     *     arch: linux/amd64,linux/arm64
     *     icon: anydesk.svg
     *     description: "..."
     *     disabled: True
     */
    fun parseCatalog(yaml: String): List<AppEntry> {
        val out = ArrayList<AppEntry>()
        var cur: MutableMap<String, String>? = null
        for (rawLine in yaml.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line == "include:") continue
            if (line.startsWith("- ") || line.startsWith("-\t")) {
                cur?.let { out.add(build(it)) }
                cur = LinkedHashMap()
                val rest = line.substring(2).trim()
                if (rest.isNotEmpty()) applyKv(cur, rest)
                continue
            }
            val map = cur ?: continue
            applyKv(map, line)
        }
        cur?.let { out.add(build(it)) }
        return out.sortedBy { it.fullName.lowercase() }
    }

    private fun applyKv(map: MutableMap<String, String>, kv: String) {
        val idx = kv.indexOf(':')
        if (idx <= 0) return
        val k = kv.substring(0, idx).trim()
        var v = kv.substring(idx + 1).trim()
        // 去引号与行内注释
        if (v.startsWith("\"") && v.endsWith("\"") && v.length >= 2) v = v.substring(1, v.length - 1)
        else if (v.startsWith("'") && v.endsWith("'") && v.length >= 2) v = v.substring(1, v.length - 1)
        if (k.isNotEmpty()) map[k] = v
    }

    private fun build(m: Map<String, String>): AppEntry {
        val name = m["name"] ?: ""
        return AppEntry(
            name = name,
            fullName = m["full_name"]?.ifBlank { name } ?: name,
            icon = m["icon"] ?: "",
            description = m["description"] ?: "",
            disabled = m["disabled"]?.equals("true", true) == true ||
                    m["disabled"]?.equals("yes", true) == true
        )
    }
}

package com.selkies.client.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * 一台 Selkies 服务器的全部连接配置。
 *
 * @param id      唯一标识（UUID），编辑/删除时使用
 * @param name    显示名称（可重命名）
 * @param host    主机名或 IP（不含协议前缀）
 * @param port    端口（LSIO webtop 默认 3000；Nginx https 反代后通常 443）
 * @param path    子目录前缀（如 "/selkies"，需以 / 开头或留空）
 * @param https   是否使用 TLS（wss/https）
 * @param trustAllCerts 信任自签名证书（LSIO 3001 自签 https / 内网自签反代场景）
 * @param username Basic Auth 用户名（可选）
 * @param password Basic Auth 密码（可选）
 * @param saveCredentials 是否在本机保存凭据；false 时连接成功后即丢弃
 * @param viewOnly 权限模式：true=仅查看（不发任何输入），false=控制模式
 * @param isDefault 是否默认服务器（列表页一键连接）
 */
data class ServerConfig(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var host: String = "",
    var port: Int = 3000,
    var path: String = "",
    var https: Boolean = false,
    var trustAllCerts: Boolean = false,
    var username: String? = null,
    var password: String? = null,
    var saveCredentials: Boolean = true,
    var viewOnly: Boolean = false,
    var isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    /** 规范化子目录：保证以 / 开头且不以 / 结尾 */
    val normalizedPath: String
        get() {
            val p = path.trim()
            if (p.isEmpty()) return ""
            val withSlash = if (p.startsWith("/")) p else "/$p"
            return if (withSlash.endsWith("/")) withSlash.dropLast(1) else withSlash
        }
}

/**
 * 服务器配置仓库：JSON 持久化在 SharedPreferences 中。
 * 所有方法都在调用线程执行（数据量小，无需异步）。
 */
class ServerRepository(context: Context) {

    private val prefs =
        context.getSharedPreferences("servers", Context.MODE_PRIVATE)
    private val gson = Gson()

    /** 读取全部服务器，按创建时间升序 */
    fun list(): MutableList<ServerConfig> {
        val raw = prefs.getString(KEY_LIST, null) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<ServerConfig>>() {}.type
            gson.fromJson<MutableList<ServerConfig>>(raw, type) ?: mutableListOf()
        } catch (e: Exception) {
            android.util.Log.e("ServerRepository", "corrupt server list, resetting", e)
            mutableListOf()
        }
    }

    fun save(servers: List<ServerConfig>) {
        prefs.edit().putString(KEY_LIST, gson.toJson(servers)).apply()
    }

    fun byId(id: String): ServerConfig? = list().firstOrNull { it.id == id }

    /** 新增或更新（按 id 匹配） */
    fun upsert(config: ServerConfig) {
        val all = list()
        val idx = all.indexOfFirst { it.id == config.id }
        if (idx >= 0) all[idx] = config else all.add(config)
        save(all)
    }

    fun delete(id: String) {
        val all = list()
        val removed = all.firstOrNull { it.id == id }
        all.removeAll { it.id == id }
        // 删除的是默认服务器时，把默认转移给最早的剩余项
        if (removed?.isDefault == true) {
            all.minByOrNull { it.createdAt }?.isDefault = true
        }
        save(all)
    }

    /** 设置默认服务器（保证唯一） */
    fun setDefault(id: String) {
        val all = list()
        all.forEach { it.isDefault = (it.id == id) }
        save(all)
    }

    fun defaultServer(): ServerConfig? = list().firstOrNull { it.isDefault }
        ?: list().minByOrNull { it.createdAt }

    companion object {
        private const val KEY_LIST = "server_list_json"
    }
}

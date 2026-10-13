package com.cshdotcom.selkies.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.UUID

/**
 * 一条服务器配置。字段与需求一一对应：
 * 地址/端口/是否 https/账号密码（可选保存）/默认权限（仅查看|可控制）/是否默认服务器
 */
data class ServerConfig(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var host: String = "",
    var port: Int = 3000,
    var https: Boolean = false,
    var username: String = "",
    /** base64 存储（非强加密，仅供防误读；可在设置页一键清除） */
    var passwordB64: String = "",
    var savePassword: Boolean = false,
    /** 仅查看：禁止一切输入事件发送 */
    var viewOnly: Boolean = false,
    var isDefault: Boolean = false,
    /** 测试连接结果 */
    var lastTestOk: Boolean? = null,
    var lastTestMsg: String? = null
) {
    val hasCredentials: Boolean get() = username.isNotEmpty() && passwordB64.isNotEmpty()

    fun password(): String = if (passwordB64.isEmpty()) "" else
        String(Base64.getDecoder().decode(passwordB64), Charsets.UTF_8)

    fun setPassword(pw: String) {
        passwordB64 = if (pw.isEmpty()) "" else Base64.getEncoder().encodeToString(pw.toByteArray(Charsets.UTF_8))
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("host", host); put("port", port)
        put("https", https); put("username", username); put("passwordB64", passwordB64)
        put("savePassword", savePassword); put("viewOnly", viewOnly); put("isDefault", isDefault)
        put("lastTestOk", lastTestOk ?: JSONObject.NULL)
        put("lastTestMsg", lastTestMsg ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject): ServerConfig = ServerConfig(
            id = o.optString("id", UUID.randomUUID().toString()),
            name = o.optString("name"), host = o.optString("host"),
            port = if (o.has("port")) o.optInt("port") else 3000,
            https = o.optBoolean("https"), username = o.optString("username"),
            passwordB64 = o.optString("passwordB64"), savePassword = o.optBoolean("savePassword"),
            viewOnly = o.optBoolean("viewOnly"), isDefault = o.optBoolean("isDefault"),
            lastTestOk = if (o.isNull("lastTestOk")) null else o.optBoolean("lastTestOk"),
            lastTestMsg = if (o.isNull("lastTestMsg")) null else o.optString("lastTestMsg")
        )
    }
}

/** 多服务器配置仓库：JSON 持久化到应用私有目录 */
class ServerStore(private val file: java.io.File) {
    private var list = mutableListOf<ServerConfig>()

    init { reload() }

    @Synchronized
    fun reload() {
        list = if (file.exists()) {
            try {
                val arr = JSONArray(file.readText(Charsets.UTF_8))
                (0 until arr.length()).map { ServerConfig.fromJson(arr.getJSONObject(it)) }.toMutableList()
            } catch (e: Exception) { mutableListOf() }
        } else mutableListOf()
    }

    @Synchronized
    fun all(): List<ServerConfig> = list.toList()

    @Synchronized
    fun get(id: String?): ServerConfig? = list.firstOrNull { it.id == id }

    @Synchronized
    fun default(): ServerConfig? = list.firstOrNull { it.isDefault } ?: list.firstOrNull()

    @Synchronized
    fun upsert(cfg: ServerConfig) {
        val i = list.indexOfFirst { it.id == cfg.id }
        if (cfg.isDefault) list.forEach { it.isDefault = false }
        if (i >= 0) list[i] = cfg else list.add(cfg)
        persist()
    }

    @Synchronized
    fun delete(id: String) {
        list.removeAll { it.id == id }
        persist()
    }

    @Synchronized
    fun rename(id: String, name: String) {
        list.firstOrNull { it.id == id }?.let { it.name = name; persist() }
    }

    @Synchronized
    fun clearPasswords() {
        list.forEach { it.passwordB64 = ""; it.username = ""; it.savePassword = false }
        persist()
    }

    private fun persist() {
        try {
            file.parentFile?.mkdirs()
            file.writeText(JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }.toString(), Charsets.UTF_8)
        } catch (e: Exception) { /* 存储失败静默，界面仍可用内存态 */ }
    }
}

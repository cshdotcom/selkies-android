package com.cshdotcom.selkies

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cshdotcom.selkies.connection.EndpointProber
import com.cshdotcom.selkies.model.ServerConfig
import com.cshdotcom.selkies.model.ServerStore
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/**
 * 服务器列表主页：
 * - 展示多服务器配置（名称/地址/权限模式/默认标记/测试状态）
 * - 点击连接（进入 RemoteActivity），长按编辑，加号新增
 * - 默认服务器一键连接
 */
class MainActivity : AppCompatActivity() {

    private lateinit var store: ServerStore
    private lateinit var adapter: ServerAdapter
    private val http = OkHttpClient.Builder().connectTimeout(6L, java.util.concurrent.TimeUnit.SECONDS).build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        store = ServerStore(java.io.File(filesDir, "servers.json"))
        adapter = ServerAdapter(store.all().toMutableList(), this::onServerClick, this::onServerLongClick)
        findViewById<RecyclerView>(R.id.serverList).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }
        findViewById<FloatingActionButton>(R.id.fabAdd).setOnClickListener {
            startActivity(Intent(this, EditServerActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        store.reload()
        adapter.update(store.all())
        val empty = findViewById<TextView>(R.id.emptyHint)
        empty.visibility = if (store.all().isEmpty()) View.VISIBLE else View.GONE
    }

    private fun onServerClick(cfg: ServerConfig) {
        startActivity(Intent(this, RemoteActivity::class.java).apply {
            putExtra("serverId", cfg.id)
        })
    }

    private fun onServerLongClick(cfg: ServerConfig): Boolean {
        val opts = arrayOf(
            getString(R.string.action_connect),
            getString(R.string.action_edit),
            getString(R.string.action_set_default),
            getString(R.string.action_test),
            getString(R.string.action_delete))
        AlertDialog.Builder(this)
            .setTitle(cfg.name.ifBlank { cfg.host })
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> onServerClick(cfg)
                    1 -> startActivity(Intent(this, EditServerActivity::class.java).apply { putExtra("serverId", cfg.id) })
                    2 -> {
                        store.all().forEach { it.isDefault = false }
                        cfg.isDefault = true
                        store.upsert(cfg)
                        adapter.update(store.all())
                    }
                    3 -> testConnection(cfg)
                    4 -> {
                        AlertDialog.Builder(this)
                            .setMessage(getString(R.string.confirm_delete))
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                store.delete(cfg.id); adapter.update(store.all())
                                findViewById<TextView>(R.id.emptyHint).visibility =
                                    if (store.all().isEmpty()) View.VISIBLE else View.GONE
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                }
            }.show()
        return true
    }

    /** 测试连接：探测端点（含 401 检测），标记可用/不可用 */
    private fun testConnection(cfg: ServerConfig) {
        val dlg = AlertDialog.Builder(this).setMessage(R.string.testing).setCancelable(false).show()
        lifecycleScope.launch(Dispatchers.IO) {
            val base = HttpUrl.Builder()
                .scheme(if (cfg.https) "https" else "http")
                .host(cfg.host).port(cfg.port).build()
            val creds = if (cfg.hasCredentials) EndpointProber.Creds(cfg.username, cfg.password()) else null
            val outcome = try { EndpointProber.resolve(http, base, creds) } catch (e: Exception) {
                EndpointProber.Outcome.Failed("网络错误：${e.message}")
            }
            val (ok, msg) = when (outcome) {
                is EndpointProber.Outcome.Ready -> true to "连接成功${if (outcome.legacyPath) "（旧路径 /api/ws）" else ""}"
                is EndpointProber.Outcome.NeedAuth -> true to "可达（需要 Basic 认证）"
                is EndpointProber.Outcome.CloudflareBlocked -> false to "Cloudflare 拦截（400）"
                is EndpointProber.Outcome.Failed -> false to outcome.reason
            }
            cfg.lastTestOk = ok; cfg.lastTestMsg = msg
            store.upsert(cfg)
            withContext(Dispatchers.Main) {
                dlg.dismiss()
                adapter.update(store.all())
                AlertDialog.Builder(this@MainActivity).setMessage(msg).show()
            }
        }
    }

    // ---------------- 选项菜单（设置） ----------------
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
        else -> super.onOptionsItemSelected(item)
    }

    // ---------------- RecyclerView ----------------
    private class ServerAdapter(
        private val items: MutableList<ServerConfig>,
        private val click: (ServerConfig) -> Unit,
        private val longClick: (ServerConfig) -> Boolean
    ) : RecyclerView.Adapter<ServerAdapter.VH>() {

        fun update(list: List<ServerConfig>) {
            items.clear(); items.addAll(list)
            notifyDataSetChanged()
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.itemName)
            val addr: TextView = v.findViewById(R.id.itemAddr)
            val mode: TextView = v.findViewById(R.id.itemMode)
            val status: TextView = v.findViewById(R.id.itemStatus)
            val root: View = v
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH =
            VH(android.view.LayoutInflater.from(parent.context).inflate(R.layout.item_server, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val c = items[pos]
            h.name.text = (if (c.isDefault) "⭐ " else "") + c.name.ifBlank { c.host }
            h.addr.text = "${if (c.https) "https" else "http"}://${c.host}:${c.port}"
            h.mode.setText(if (c.viewOnly) R.string.mode_view_only else R.string.mode_control)
            h.status.text = when (c.lastTestOk) {
                true -> "✓ ${c.lastTestMsg ?: ""}"
                false -> "✗ ${c.lastTestMsg ?: ""}"
                null -> ""
            }
            h.root.setOnClickListener { click(c) }
            h.root.setOnLongClickListener { longClick(c) }
        }
    }
}

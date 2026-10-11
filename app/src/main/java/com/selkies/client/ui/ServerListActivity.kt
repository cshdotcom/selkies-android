package com.selkies.client.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.selkies.client.R
import com.selkies.client.data.ServerConfig
import com.selkies.client.data.ServerRepository
import com.selkies.client.databinding.ActivityServerListBinding
import com.selkies.client.net.EndpointProber
import com.selkies.client.ui.session.SessionActivity
import kotlin.concurrent.thread

/**
 * 服务器列表页（主入口）。
 *
 * - 展示全部已配置服务器（默认服务器带星标，点击行即连接）
 * - 长按进入【多选批量模式】：批量连接 / 批量测试 / 批量删除
 * - 条目菜单：编辑 / 设为默认 / 删除
 * - FAB 添加新服务器；工具栏进入全局设置
 */
class ServerListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityServerListBinding
    private lateinit var repo: ServerRepository
    private lateinit var adapter: ServerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityServerListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repo = ServerRepository(this)

        adapter = ServerAdapter(
            onClick = { cfg -> connect(cfg) },
            onLongClick = { cfg, anchor ->
                if (!adapter.selectMode) {
                    // 长按 → 进入多选模式
                    enterBatchMode(preselect = cfg.id)
                }
            },
            onMoreClick = { cfg, anchor -> showItemMenu(cfg, anchor) }
        )
        adapter.onSelectionChanged = { count ->
            binding.txtBatchCount.text = getString(R.string.batch_selected_count, count)
            binding.btnBatchConnect.isEnabled = count > 0
            binding.btnBatchTest.isEnabled = count > 0
            binding.btnBatchDelete.isEnabled = count > 0
        }
        binding.recyclerServers.layoutManager = LinearLayoutManager(this)
        binding.recyclerServers.adapter = adapter

        binding.fabAdd.setOnClickListener {
            startActivity(Intent(this, ServerEditActivity::class.java))
        }

        binding.btnBatchCancel.setOnClickListener { exitBatchMode() }
        binding.btnBatchConnect.setOnClickListener { batchConnect() }
        binding.btnBatchTest.setOnClickListener { batchTest() }
        binding.btnBatchDelete.setOnClickListener { batchDelete() }

        binding.toolbar.inflateMenu(R.menu.menu_server_list)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val list = repo.list()
        adapter.submit(list)
        binding.emptyHint.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        if (adapter.selectMode && list.isEmpty()) exitBatchMode()
    }

    /** 点击条目：直接进入会话 */
    private fun connect(cfg: ServerConfig) {
        val intent = Intent(this, SessionActivity::class.java)
        intent.putExtra(SessionActivity.EXTRA_SERVER_ID, cfg.id)
        startActivity(intent)
    }

    // ------------------------------------------------------------------
    // 多选批量模式
    // ------------------------------------------------------------------

    private fun enterBatchMode(preselect: String? = null) {
        adapter.setSelectMode(true)
        preselect?.let { adapter.preselect(it) }
        binding.batchBar.visibility = View.VISIBLE
        binding.fabAdd.visibility = View.GONE
        binding.txtBatchCount.text =
            getString(R.string.batch_selected_count, adapter.selectedCount())
    }

    private fun exitBatchMode() {
        adapter.setSelectMode(false)
        binding.batchBar.visibility = View.GONE
        binding.fabAdd.visibility = View.VISIBLE
    }

    /** 批量连接：依次启动每个会话（多实例并行查看） */
    private fun batchConnect() {
        val ids = adapter.selectedIds()
        if (ids.isEmpty()) return
        ids.forEach { id ->
            repo.byId(id)?.let { cfg -> connect(cfg) }
        }
        exitBatchMode()
    }

    /** 批量测试连接（并发探测，结果以 toast 汇总） */
    private fun batchTest() {
        val ids = adapter.selectedIds()
        if (ids.isEmpty()) return
        val cfgs = ids.mapNotNull { repo.byId(it) }
        Toast.makeText(this, R.string.test_in_progress, Toast.LENGTH_SHORT).show()
        thread(name = "batch-test") {
            val ok = mutableListOf<String>()
            val fail = mutableListOf<String>()
            cfgs.forEach { cfg ->
                try {
                    val scheme = if (cfg.https) "https" else "http"
                    val url = okhttp3.HttpUrl.Builder()
                        .scheme(scheme).host(cfg.host).port(cfg.port)
                        .apply { if (cfg.normalizedPath.isNotEmpty()) encodedPath(cfg.normalizedPath) }
                        .build()
                    val auth = if (!cfg.username.isNullOrEmpty()) {
                        "Basic " + java.util.Base64.getEncoder()
                            .encodeToString("${cfg.username}:${cfg.password ?: ""}".toByteArray())
                    } else null
                    val result = EndpointProber.probe(url, auth, cfg.trustAllCerts)
                    val name = cfg.name.ifEmpty { cfg.host }
                    if (result is EndpointProber.ProbeResult.Ready ||
                        result is EndpointProber.ProbeResult.NeedAuth
                    ) ok.add(name) else fail.add(name)
                } catch (_: Exception) {
                    fail.add(cfg.name.ifEmpty { cfg.host })
                }
            }
            runOnUiThread {
                Toast.makeText(
                    this,
                    "✓ ${ok.size} 台可连，✗ ${fail.size} 台失败" +
                            (if (fail.isNotEmpty()) "\n${fail.joinToString("、")}" else ""),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** 批量删除 */
    private fun batchDelete() {
        val ids = adapter.selectedIds()
        if (ids.isEmpty()) return
        android.app.AlertDialog.Builder(this)
            .setMessage(getString(R.string.batch_delete_confirm, ids.size))
            .setPositiveButton(R.string.delete) { _, _ ->
                ids.forEach { repo.delete(it) }
                Toast.makeText(this, getString(R.string.batch_deleted, ids.size), Toast.LENGTH_SHORT).show()
                exitBatchMode()
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 条目菜单：编辑 / 设为默认 / 删除 */
    private fun showItemMenu(cfg: ServerConfig, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, getString(R.string.edit))
        popup.menu.add(0, 2, 1, getString(R.string.default_server))
        popup.menu.add(0, 3, 2, getString(R.string.delete))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    val i = Intent(this, ServerEditActivity::class.java)
                    i.putExtra(ServerEditActivity.EXTRA_SERVER_ID, cfg.id)
                    startActivity(i)
                }
                2 -> {
                    repo.setDefault(cfg.id)
                    refresh()
                }
                3 -> {
                    android.app.AlertDialog.Builder(this)
                        .setMessage(getString(R.string.delete_server_confirm, cfg.name))
                        .setPositiveButton(R.string.delete) { _, _ ->
                            repo.delete(cfg.id)
                            refresh()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
            true
        }
        popup.show()
    }
}

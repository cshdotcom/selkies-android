package com.selkies.client.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.selkies.client.R
import com.selkies.client.data.ServerConfig
import com.selkies.client.data.ServerRepository
import com.selkies.client.databinding.ActivityServerListBinding
import com.selkies.client.ui.session.SessionActivity

/**
 * 服务器列表页（主入口）。
 *
 * - 展示全部已配置服务器（默认服务器带星标，点击行即连接）
 * - 长按/右侧菜单：编辑 / 设为默认 / 删除
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
            onLongClick = { cfg, anchor -> showItemMenu(cfg, anchor) },
            onMoreClick = { cfg, anchor -> showItemMenu(cfg, anchor) }
        )
        binding.recyclerServers.layoutManager = LinearLayoutManager(this)
        binding.recyclerServers.adapter = adapter

        binding.fabAdd.setOnClickListener {
            startActivity(Intent(this, ServerEditActivity::class.java))
        }

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
    }

    /** 点击条目：直接进入会话 */
    private fun connect(cfg: ServerConfig) {
        val intent = Intent(this, SessionActivity::class.java)
        intent.putExtra(SessionActivity.EXTRA_SERVER_ID, cfg.id)
        startActivity(intent)
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

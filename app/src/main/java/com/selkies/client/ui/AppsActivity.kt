package com.selkies.client.ui

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.selkies.client.R
import com.selkies.client.data.ServerConfig
import com.selkies.client.data.ServerRepository
import com.selkies.client.databinding.ActivityAppsBinding
import com.selkies.client.net.AppsApi
import com.selkies.client.net.SessionBridge
import kotlin.concurrent.thread

/**
 * 远程应用启动器 —— 官方网页「Apps 面板」的原生等价物。
 *
 * 协议（核对自 lib/app-commands.js + input_handler.py cmd 分支）：
 *  - 目录：本地仓库 → GET /api/apps/metadata.yml；否则 GitHub proot-apps
 *  - 命令：cmd,selkies-proot run|install|remove|update <name>
 *  - 已安装集合：server_settings.apps_installed + system apps_installed 广播
 *
 * 会话页在下方保持连接（SessionBridge.sendCommand），命令直接走当前 WS。
 */
class AppsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SERVER_ID = "server_id"
        const val EXTRA_LOCAL_REPO = "local_repo"
        const val EXTRA_COMMAND_ENABLED = "command_enabled"
    }

    private lateinit var binding: ActivityAppsBinding
    private var config: ServerConfig? = null
    private var localRepo = false
    private var commandEnabled = true

    private var allApps: List<AppsApi.AppEntry> = emptyList()
    private var filtered: List<AppsApi.AppEntry> = emptyList()
    private val adapter = AppsAdapter()
    private var loading = false

    // 已安装集合变化监听（由会话页 system 广播驱动）
    private val installedListener = {
        runOnUiThread { adapter.notifyDataSetChanged() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val repo = ServerRepository(this)
        config = repo.byId(intent.getStringExtra(EXTRA_SERVER_ID) ?: "")
        localRepo = intent.getBooleanExtra(EXTRA_LOCAL_REPO, false)
        commandEnabled = intent.getBooleanExtra(EXTRA_COMMAND_ENABLED, true)

        if (config == null) {
            Toast.makeText(this, R.string.error_connect_failed, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.btnAppsBack.setOnClickListener { finish() }
        binding.btnAppsRefresh.setOnClickListener { load() }

        binding.listApps.layoutManager = LinearLayoutManager(this)
        binding.listApps.adapter = adapter

        binding.editAppsSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyFilter(s?.toString() ?: "")
            }
        })

        load()
    }

    override fun onResume() {
        super.onResume()
        SessionBridge.onInstalledChanged = installedListener
    }

    override fun onPause() {
        super.onPause()
        if (SessionBridge.onInstalledChanged === installedListener) {
            SessionBridge.onInstalledChanged = null
        }
    }

    private fun load() {
        val cfg = config ?: return
        if (loading) return
        loading = true
        binding.appsProgress.visibility = View.VISIBLE
        binding.txtAppsEmpty.visibility = View.GONE
        thread(name = "apps-catalog") {
            try {
                val (apps, fromLocal) = AppsApi.fetchCatalog(cfg, localRepo)
                runOnUiThread {
                    loading = false
                    binding.appsProgress.visibility = View.GONE
                    allApps = apps.filter { it.name.isNotBlank() }
                    binding.txtAppsSource.text =
                        getString(if (fromLocal) R.string.apps_source_local else R.string.apps_source_remote)
                    applyFilter(binding.editAppsSearch.text.toString())
                }
            } catch (e: Exception) {
                runOnUiThread {
                    loading = false
                    binding.appsProgress.visibility = View.GONE
                    binding.txtAppsEmpty.text = getString(R.string.apps_load_failed, e.message ?: "")
                    binding.txtAppsEmpty.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun applyFilter(q: String) {
        val query = q.trim()
        filtered = if (query.isEmpty()) allApps
        else allApps.filter {
            it.fullName.contains(query, true) || it.name.contains(query, true) ||
                    it.description.contains(query, true)
        }
        // 已安装在前
        val installed = SessionBridge.installedApps
        filtered = filtered.sortedBy { (it.name !in installed) }
        adapter.notifyDataSetChanged()
        binding.txtAppsEmpty.text = getString(R.string.apps_empty)
        binding.txtAppsEmpty.visibility =
            if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------
    // 列表适配器
    // ------------------------------------------------------------------

    private inner class AppsAdapter : RecyclerView.Adapter<AppHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppHolder =
            AppHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_app, parent, false)
            )

        override fun getItemCount(): Int = filtered.size

        override fun onBindViewHolder(holder: AppHolder, position: Int) {
            val app = filtered[position]
            val installed = app.name in SessionBridge.installedApps
            holder.bind(app, installed)
        }
    }

    private inner class AppHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val avatar: TextView = view.findViewById(R.id.txt_app_avatar)
        private val name: TextView = view.findViewById(R.id.txt_app_name)
        private val desc: TextView = view.findViewById(R.id.txt_app_desc)
        private val action: com.google.android.material.button.MaterialButton =
            view.findViewById(R.id.btn_app_action)
        private val menu: ImageButton = view.findViewById(R.id.btn_app_menu)

        fun bind(app: AppsApi.AppEntry, installed: Boolean) {
            avatar.text = app.fullName.trim().take(1).uppercase().ifBlank { "?" }
            avatar.setBackgroundColor(avatarColor(app.name))
            name.text = if (app.disabled) "${app.fullName}（不可用）" else app.fullName
            desc.text = app.description

            // 主按钮：已安装 → 启动；未安装 → 安装
            action.visibility = View.VISIBLE
            action.text = getString(if (installed) R.string.apps_launch else R.string.apps_install)
            action.isEnabled = commandEnabled && !app.disabled
            action.setOnClickListener { postCommand(buildCmd(installed, app)) }

            // 次级菜单：更新 / 卸载 / 安装其他变体
            menu.setOnClickListener {
                val items = if (installed) arrayOf(
                    getString(R.string.apps_launch),
                    getString(R.string.apps_update),
                    getString(R.string.apps_remove)
                ) else arrayOf(
                    getString(R.string.apps_install)
                )
                AlertDialog.Builder(this@AppsActivity)
                    .setTitle(app.fullName)
                    .setItems(items) { _, which ->
                        val cmd = when (which) {
                            0 -> buildCmd(installed, app)
                            1 -> "selkies-proot update ${app.name}"
                            2 -> "selkies-proot remove ${app.name}"
                            else -> return@setItems
                        }
                        postCommand(cmd)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }

        /** 主按钮命令：已安装=run，未安装=install */
        private fun buildCmd(installed: Boolean, app: AppsApi.AppEntry): String =
            "selkies-proot ${if (installed) "run" else "install"} ${app.name}"

        private fun postCommand(cmd: String) {
            val sender = SessionBridge.sendCommand
            if (sender == null) {
                Toast.makeText(this@AppsActivity, R.string.apps_no_session, Toast.LENGTH_SHORT).show()
                return
            }
            sender(cmd)
            Toast.makeText(this@AppsActivity, getString(R.string.apps_cmd_sent, cmd), Toast.LENGTH_SHORT).show()
        }
    }

    private fun avatarColor(seed: String): Int {
        val palette = intArrayOf(
            0xFF3D6FB4.toInt(), 0xFF2E8B74.toInt(), 0xFFB4573D.toInt(),
            0xFF7A5CB4.toInt(), 0xFF4B8B3B.toInt(), 0xFFB43D6F.toInt()
        )
        return palette[(seed.hashCode().let { if (it < 0) -it else it }) % palette.size]
    }
}

package com.selkies.client.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.selkies.client.R
import com.selkies.client.data.ServerConfig
import com.selkies.client.data.ServerRepository
import com.selkies.client.databinding.ActivityServerEditBinding
import com.selkies.client.net.EndpointProber
import okhttp3.HttpUrl
import kotlin.concurrent.thread

/**
 * 服务器编辑页：新增 / 修改服务器配置 + 连接测试。
 *
 * 测试连接走与真实连接完全相同的探测路径
 * （EndpointProber：/api/status 探测 + 301 跟随 + Basic Auth 识别），
 * 因此"测试成功"意味着真实会话可以建立。
 */
class ServerEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SERVER_ID = "server_id"
    }

    private lateinit var binding: ActivityServerEditBinding
    private lateinit var repo: ServerRepository
    private var editingId: String? = null
    private var testing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityServerEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repo = ServerRepository(this)
        editingId = intent.getStringExtra(EXTRA_SERVER_ID)

        val cfg = editingId?.let { repo.byId(it) }
        if (cfg != null) {
            binding.toolbar.title = cfg.name
            binding.editName.setText(cfg.name)
            binding.editHost.setText(cfg.host)
            binding.editPort.setText(cfg.port.toString())
            binding.editPath.setText(cfg.path)
            binding.switchHttps.isChecked = cfg.https
            binding.switchTrustAll.isChecked = cfg.trustAllCerts
            binding.editUsername.setText(cfg.username ?: "")
            binding.editPassword.setText(cfg.password ?: "")
            binding.switchSaveCreds.isChecked = cfg.saveCredentials
            if (cfg.viewOnly) binding.radioViewonly.isChecked = true
            else binding.radioControl.isChecked = true
        }

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnTest.setOnClickListener { testConnection() }
        binding.btnSave.setOnClickListener { save() }
    }

    /** 从表单收集配置（不落盘） */
    private fun collect(): ServerConfig? {
        val name = binding.editName.text.toString().trim()
        var host = binding.editHost.text.toString().trim()
        if (host.startsWith("http://")) host = host.removePrefix("http://")
        if (host.startsWith("https://")) host = host.removePrefix("https://")
        if (host.startsWith("ws://")) host = host.removePrefix("ws://")
        if (host.startsWith("wss://")) host = host.removePrefix("wss://")
        host = host.trimEnd('/')
        val port = binding.editPort.text.toString().toIntOrNull() ?: -1

        if (host.isEmpty()) {
            binding.editHost.error = getString(R.string.invalid_address)
            return null
        }
        if (port !in 1..65535) {
            binding.editPort.error = getString(R.string.invalid_port)
            return null
        }

        val base = editingId?.let { repo.byId(it) } ?: ServerConfig()
        base.name = name.ifEmpty { host }
        base.host = host
        base.port = port
        base.path = binding.editPath.text.toString().trim()
        base.https = binding.switchHttps.isChecked
        base.trustAllCerts = binding.switchTrustAll.isChecked
        base.username = binding.editUsername.text.toString().ifEmpty { null }
        base.password = binding.editPassword.text.toString().ifEmpty { null }
        base.saveCredentials = binding.switchSaveCreds.isChecked
        base.viewOnly = binding.radioViewonly.isChecked
        return base
    }

    private fun save() {
        val cfg = collect() ?: return
        // 未勾选保存凭据时，验证后即丢（连接时仍需输入）—— 此处直接不保存
        if (!cfg.saveCredentials) {
            cfg.username = null
            cfg.password = null
        }
        val all = repo.list()
        if (all.none { it.id == cfg.id } && all.any { it.name == cfg.name }) {
            Toast.makeText(this, R.string.duplicate_name, Toast.LENGTH_SHORT).show()
            return
        }
        repo.upsert(cfg)
        if (all.isEmpty()) repo.setDefault(cfg.id)
        finish()
    }

    /** 连接测试：走真实探测管线，测量端到端 RTT */
    private fun testConnection() {
        if (testing) return
        val cfg = collect() ?: return
        testing = true
        binding.btnTest.isEnabled = false
        binding.txtTestResult.apply {
            visibility = View.VISIBLE
            setTextColor(getColor(R.color.status_warn))
            text = getString(R.string.test_in_progress)
        }

        thread(name = "conn-test") {
            val t0 = android.os.SystemClock.elapsedRealtime()
            val scheme = if (cfg.https) "https" else "http"
            val url = try {
                val b = HttpUrl.Builder().scheme(scheme).host(cfg.host).port(cfg.port)
                val p = cfg.normalizedPath
                if (p.isNotEmpty()) b.encodedPath(p)
                b.build()
            } catch (e: Exception) {
                null
            }

            val auth = if (!cfg.username.isNullOrEmpty()) {
                "Basic " + java.util.Base64.getEncoder()
                    .encodeToString("${cfg.username}:${cfg.password ?: ""}".toByteArray())
            } else null

            val result = url?.let { EndpointProber.probe(it, auth, cfg.trustAllCerts) }
            val rtt = android.os.SystemClock.elapsedRealtime() - t0

            runOnUiThread {
                testing = false
                binding.btnTest.isEnabled = true
                val tv = binding.txtTestResult
                tv.visibility = View.VISIBLE
                when (result) {
                    is EndpointProber.ProbeResult.Ready -> {
                        tv.setTextColor(getColor(R.color.status_ok))
                        tv.text = getString(
                            if (rtt < 60_000) R.string.test_ok else R.string.test_ok_no_rtt,
                            rtt.toInt()
                        )
                    }
                    is EndpointProber.ProbeResult.NeedAuth -> {
                        tv.setTextColor(getColor(R.color.status_warn))
                        tv.text = getString(R.string.auth_required)
                    }
                    is EndpointProber.ProbeResult.Failed -> {
                        tv.setTextColor(getColor(R.color.status_err))
                        val reason = when (result.reason) {
                            "timeout" -> getString(R.string.error_timeout)
                            "network" -> getString(R.string.error_network, result.detail ?: "")
                            else -> result.reason
                        }
                        tv.text = getString(R.string.test_failed, reason)
                    }
                    null -> {
                        tv.setTextColor(getColor(R.color.status_err))
                        tv.text = getString(R.string.invalid_address)
                    }
                }
            }
        }
    }
}

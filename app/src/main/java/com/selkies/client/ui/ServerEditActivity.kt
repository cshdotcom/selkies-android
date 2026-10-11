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

    /** 连接测试：走真实探测管线 + WS 握手干跑，输出完整诊断报告 */
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

            com.selkies.client.net.DiagLog.newSession()
            com.selkies.client.net.DiagLog.i("连接测试: ${cfg.host}:${cfg.port}${cfg.normalizedPath}")
            val result = url?.let { EndpointProber.probe(it, auth, cfg.trustAllCerts) }
            val rtt = android.os.SystemClock.elapsedRealtime() - t0

            // WS 握手干跑：探测成功后实际发起一次 WS 连接验证反代转发（连上即关）
            var wsOk = false
            var wsDetail = ""
            if (result is EndpointProber.ProbeResult.Ready) {
                try {
                    val latch = java.util.concurrent.CountDownLatch(1)
                    val wsClient = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .apply { if (cfg.trustAllCerts) EndpointProber.applyTrustAll(this) }
                        .build()
                    val wsUrl = result.finalBase.newBuilder()
                        .encodedPath(EndpointProber.joinPath(
                            result.finalBase.encodedPath,
                            result.wsPaths.firstOrNull() ?: "/api/websockets"))
                        .build()
                        .newBuilder()
                        .scheme(if (result.finalBase.isHttps) "wss" else "ws")
                        .build()
                    com.selkies.client.net.DiagLog.i("WS 握手干跑: $wsUrl")
                    val ws = wsClient.newWebSocket(
                        EndpointProber.baseRequest(wsUrl, auth, headOnly = false).build(),
                        object : okhttp3.WebSocketListener() {
                            override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                                wsOk = true
                                com.selkies.client.net.DiagLog.i("WS 干跑成功 (101)")
                                webSocket.close(1000, "test done")
                                latch.countDown()
                            }

                            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                                wsDetail = "${t.javaClass.simpleName}: ${t.message} code=${response?.code ?: -1}"
                                com.selkies.client.net.DiagLog.e("WS 干跑失败: $wsDetail")
                                latch.countDown()
                            }
                        })
                    latch.await(12, java.util.concurrent.TimeUnit.SECONDS)
                    ws.cancel()
                    wsClient.dispatcher.executorService.shutdown()
                } catch (e: Exception) {
                    wsDetail = e.message ?: e.javaClass.simpleName
                }
            }

            runOnUiThread {
                testing = false
                binding.btnTest.isEnabled = true
                val tv = binding.txtTestResult
                tv.visibility = View.VISIBLE
                when (result) {
                    is EndpointProber.ProbeResult.Ready -> {
                        tv.setTextColor(getColor(R.color.status_ok))
                        tv.text = if (wsOk) {
                            getString(
                                if (rtt < 60_000) R.string.test_ok else R.string.test_ok_no_rtt,
                                rtt.toInt()
                            )
                        } else {
                            getString(R.string.test_failed, "WS: $wsDetail")
                        }
                        // 附加完整诊断报告查看
                        if (!wsOk || rtt >= 60_000) showDiagReport()
                    }
                    is EndpointProber.ProbeResult.NeedAuth -> {
                        tv.setTextColor(getColor(R.color.status_warn))
                        tv.text = getString(R.string.auth_required)
                    }
                    is EndpointProber.ProbeResult.WebRtcMode -> {
                        tv.setTextColor(getColor(R.color.status_err))
                        tv.text = getString(
                            R.string.test_failed,
                            "服务器为 WebRTC 模式（409），需切换为 websockets 流模式"
                        )
                    }
                    is EndpointProber.ProbeResult.Failed -> {
                        tv.setTextColor(getColor(R.color.status_err))
                        val reason = when (result.reason) {
                            "timeout" -> getString(R.string.error_timeout)
                            "network" -> getString(R.string.error_network, result.detail ?: "")
                            else -> result.reason
                        }
                        tv.text = getString(R.string.test_failed, reason)
                        showDiagReport()
                    }
                    null -> {
                        tv.setTextColor(getColor(R.color.status_err))
                        tv.text = getString(R.string.invalid_address)
                    }
                }
            }
        }
    }

    /** 诊断报告（含复制按钮，便于回传排障） */
    private fun showDiagReport() {
        AlertDialog.Builder(this)
            .setTitle(R.string.panel_diag)
            .setMessage(com.selkies.client.net.DiagLog.tail(40))
            .setPositiveButton(R.string.diag_copy) { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(
                    android.content.ClipData.newPlainText(
                        "selkies-diag", com.selkies.client.net.DiagLog.dump()
                    )
                )
                Toast.makeText(this, R.string.diag_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }
}

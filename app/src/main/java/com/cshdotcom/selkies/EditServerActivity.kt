package com.cshdotcom.selkies

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import com.cshdotcom.selkies.connection.EndpointProber
import com.cshdotcom.selkies.model.ServerConfig
import com.cshdotcom.selkies.model.ServerStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/**
 * 新增/编辑服务器配置：
 * 名称、地址、端口、https、账号密码（可选保存）、权限模式（仅查看/可控制）、
 * 设为默认服务器、测试连接（探测 + 401 提示 + 路径回退结果）。
 */
class EditServerActivity : AppCompatActivity() {

    private lateinit var store: ServerStore
    private var editing: ServerConfig? = null
    private val http = OkHttpClient.Builder().connectTimeout(6L, java.util.concurrent.TimeUnit.SECONDS).build()

    private lateinit var etName: EditText
    private lateinit var etHost: EditText
    private lateinit var etPort: EditText
    private lateinit var cbHttps: CheckBox
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var cbSavePass: CheckBox
    private lateinit var rgMode: RadioGroup
    private lateinit var cbDefault: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit_server)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        store = ServerStore(java.io.File(filesDir, "servers.json"))
        editing = store.get(intent.getStringExtra("serverId"))

        etName = findViewById(R.id.etName)
        etHost = findViewById(R.id.etHost)
        etPort = findViewById(R.id.etPort)
        cbHttps = findViewById(R.id.cbHttps)
        etUser = findViewById(R.id.etUser)
        etPass = findViewById(R.id.etPass)
        cbSavePass = findViewById(R.id.cbSavePass)
        rgMode = findViewById(R.id.rgMode)
        cbDefault = findViewById(R.id.cbDefault)

        // 端口提示（3000/6900/443 常见）
        val portHint = findViewById<TextView>(R.id.portHint)
        val ports = listOf("3000", "3001", "6900", "443", "8080")
        findViewById<AutoCompleteTextView>(R.id.etPort).setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, ports))

        editing?.let { c ->
            etName.setText(c.name); etHost.setText(c.host); etPort.setText(c.port.toString())
            cbHttps.isChecked = c.https
            etUser.setText(c.username)
            if (c.savePassword && c.hasCredentials) etPass.setText(c.password())
            cbSavePass.isChecked = c.savePassword
            rgMode.check(if (c.viewOnly) R.id.rbViewOnly else R.id.rbControl)
            cbDefault.isChecked = c.isDefault
            title = getString(R.string.title_edit_server)
        } ?: run { title = getString(R.string.title_add_server) }

        findViewById<Button>(R.id.btnTest).setOnClickListener { test() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }
        findViewById<Button>(R.id.btnDelete).setOnClickListener { delete() }
        if (editing == null) findViewById<Button>(R.id.btnDelete).visibility = View.GONE
        portHint.visibility = View.VISIBLE
    }

    private fun collect(): ServerConfig? {
        val host = etHost.text.toString().trim()
        val port = etPort.text.toString().trim().toIntOrNull() ?: run {
            Toast.makeText(this, R.string.err_port, Toast.LENGTH_SHORT).show(); return null
        }
        val c = editing ?: ServerConfig()
        c.name = etName.text.toString().trim().ifBlank { host }
        c.host = host
        c.port = port
        c.https = cbHttps.isChecked
        c.username = etUser.text.toString().trim()
        if (etPass.text.isNotEmpty()) c.setPassword(etPass.text.toString())
        else if (!cbSavePass.isChecked) c.passwordB64 = ""
        c.savePassword = cbSavePass.isChecked && c.username.isNotEmpty() && etPass.text.isNotEmpty()
        c.viewOnly = rgMode.checkedRadioButtonId == R.id.rbViewOnly
        c.isDefault = cbDefault.isChecked
        if (host.isEmpty()) { Toast.makeText(this, R.string.err_host, Toast.LENGTH_SHORT).show(); return null }
        return c
    }

    private fun save() {
        val c = collect() ?: return
        store.upsert(c)
        finish()
    }

    private fun delete() {
        editing?.let { store.delete(it.id) }
        finish()
    }

    /** 测试连接：完整探测（重定向/401/路径回退/Cloudflare） */
    private fun test() {
        val c = collect() ?: return
        val dlg = AlertDialog.Builder(this).setMessage(R.string.testing).setCancelable(false).show()
        lifecycleScope.launch(Dispatchers.IO) {
            val base = HttpUrl.Builder().scheme(if (c.https) "https" else "http").host(c.host).port(c.port).build()
            val creds = if (c.hasCredentials) EndpointProber.Creds(c.username, c.password()) else null
            var outcome = EndpointProber.resolve(http, base, creds)
            if (outcome is EndpointProber.Outcome.NeedAuth && creds != null) {
                outcome = EndpointProber.resolve(http, base, creds)
            }
            val msg = when (outcome) {
                is EndpointProber.Outcome.Ready ->
                    "✓ 连接成功：${outcome.wsUrl}\n${if (outcome.legacyPath) "（回退使用旧路径 /api/ws）" else "（使用 /api/websockets）"}"
                is EndpointProber.Outcome.NeedAuth -> "⚠ 端点可达，但服务器要求 Basic 认证（401）。请填写正确的账号密码。"
                is EndpointProber.Outcome.CloudflareBlocked -> "✗ Cloudflare 返回 400：请检查 CF 的 WebSocket 开关、端口与 SSL 配置。"
                is EndpointProber.Outcome.Failed -> "✗ ${outcome.reason}"
            }
            withContext(Dispatchers.Main) {
                dlg.dismiss()
                AlertDialog.Builder(this@EditServerActivity).setTitle(R.string.test_result).setMessage(msg).show()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}

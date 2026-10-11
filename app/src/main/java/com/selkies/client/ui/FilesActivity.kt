package com.selkies.client.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.selkies.client.R
import com.selkies.client.data.ServerConfig
import com.selkies.client.net.EndpointProber
import com.selkies.client.net.FilesApi
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 远端文件浏览器 —— 对齐官方 dashboard 文件管理器能力：
 *  - 目录导航（面包屑 + .. 上级）；
 *  - 单文件下载（Downloads 目录）；
 *  - 多选批量下载（进度提示）；
 *  - 上传（从本机选文件，多选支持，上传到当前目录）。
 *
 * 数据源：GET /api/files/<path>/（HTML 目录列表，FilesApi 解析）。
 */
class FilesActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SERVER_ID = "server_id"
        private const val TAG = "FilesActivity"
        private const val REQ_UPLOAD = 4272
        private const val UPLOAD_CHUNK = 48L * 1024 * 1024
    }

    private lateinit var cfg: ServerConfig
    private lateinit var adapter: FileAdapter
    private val entries = mutableListOf<FilesApi.Entry>()
    private val selected = LinkedHashSet<String>() // 相对路径

    private var path = ""       // 当前目录（相对根，无首尾斜杠）
    private var selecting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_files)

        val repo = com.selkies.client.data.ServerRepository(this)
        cfg = repo.byId(intent.getStringExtra(EXTRA_SERVER_ID) ?: "")
            ?: run { finish(); return }

        adapter = FileAdapter()
        findViewById<RecyclerView>(R.id.recycler).apply {
            layoutManager = LinearLayoutManager(this@FilesActivity)
            adapter = this@FilesActivity.adapter
        }
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener {
            if (path.isNotEmpty()) {
                // 面包屑返回键：上一级
                path = path.substringBeforeLast('/', "")
                reload()
            } else finish()
        }
        findViewById<ImageButton>(R.id.btn_upload).setOnClickListener { pickFiles() }
        findViewById<ImageButton>(R.id.btn_select_all).setOnClickListener {
            if (!selecting) {
                startSelecting()
                entries.filter { !it.isDir }.forEach { selected.add(joinPath(it.name)) }
            } else {
                if (selected.size == entries.count { !it.isDir }) selected.clear()
                else entries.filter { !it.isDir }.forEach { selected.add(joinPath(it.name)) }
            }
            refreshSelectionBar()
            adapter.notifyDataSetChanged()
        }
        findViewById<View>(R.id.btn_download_selected).setOnClickListener { downloadSelected() }

        reload()
    }

    private fun joinPath(name: String): String =
        if (path.isEmpty()) name else "$path/$name"

    private fun startSelecting() {
        selecting = true
        findViewById<View>(R.id.batch_bar).visibility = View.VISIBLE
    }

    private fun stopSelecting() {
        selecting = false
        selected.clear()
        findViewById<View>(R.id.batch_bar).visibility = View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun refreshSelectionBar() {
        val txt = findViewById<TextView>(R.id.txt_selected)
        txt.text = getString(R.string.files_selected, selected.size)
    }

    // ------------------------------------------------------------------
    // 目录加载
    // ------------------------------------------------------------------

    private fun reload() {
        findViewById<View>(R.id.progress).visibility = View.VISIBLE
        findViewById<TextView>(R.id.txt_empty).visibility = View.GONE
        findViewById<TextView>(R.id.txt_path).text = "/$path"
        thread {
            try {
                val list = FilesApi.listDirectory(cfg, path)
                runOnUiThread {
                    findViewById<View>(R.id.progress).visibility = View.GONE
                    entries.clear()
                    entries.addAll(list)
                    adapter.notifyDataSetChanged()
                    findViewById<TextView>(R.id.txt_empty).visibility =
                        if (list.isEmpty()) View.VISIBLE else View.GONE
                    if (selecting && selected.isNotEmpty()) stopSelecting()
                }
            } catch (e: Exception) {
                Log.w(TAG, "list failed: ${e.message}")
                runOnUiThread {
                    findViewById<View>(R.id.progress).visibility = View.GONE
                    Toast.makeText(this, e.message ?: "error", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 适配器
    // ------------------------------------------------------------------

    private inner class FileAdapter : RecyclerView.Adapter<FileAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.img_icon)
            val name: TextView = v.findViewById(R.id.txt_name)
            val meta: TextView = v.findViewById(R.id.txt_meta)
            val download: ImageButton = v.findViewById(R.id.btn_download)
            val root: View = v
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false))

        override fun getItemCount(): Int = entries.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val e = entries[position]
            val rel = joinPath(e.name)
            holder.name.text = if (e.isDir) "📁 $e.name" else e.name
            holder.meta.text = listOf(e.mtime, e.size).filter { it.isNotBlank() }
                .joinToString("  ·  ")
            holder.icon.setImageResource(
                if (e.isDir) android.R.drawable.ic_menu_myplaces
                else android.R.drawable.ic_menu_edit
            )
            holder.download.visibility = if (!e.isDir) View.VISIBLE else View.GONE
            holder.download.setOnClickListener {
                if (!e.isDir) downloadOne(rel, e.name)
            }
            holder.root.setOnClickListener {
                if (e.isDir) {
                    if (e.name == "..") path = path.substringBeforeLast('/', "")
                    else path = rel
                    reload()
                } else if (selecting) {
                    if (selected.contains(rel)) selected.remove(rel) else selected.add(rel)
                    refreshSelectionBar()
                    holder.root.isSelected = selected.contains(rel)
                    holder.root.setBackgroundColor(if (selected.contains(rel)) 0x334FC3F7 else 0x00000000)
                } else {
                    // 单击：选择下载位置提示
                    downloadOne(rel, e.name)
                }
            }
            holder.root.setOnLongClickListener {
                if (!e.isDir) {
                    if (!selecting) startSelecting()
                    if (selected.contains(rel)) selected.remove(rel) else selected.add(rel)
                    refreshSelectionBar()
                    holder.root.setBackgroundColor(if (selected.contains(rel)) 0x334FC3F7 else 0x00000000)
                }
                true
            }
            if (!e.isDir) {
                holder.root.setBackgroundColor(if (selected.contains(rel)) 0x334FC3F7 else 0x00000000)
            } else {
                holder.root.setBackgroundColor(0x00000000)
            }
        }
    }

    // ------------------------------------------------------------------
    // 下载
    // ------------------------------------------------------------------

    private fun downloadOne(rel: String, displayName: String) {
        download(listOf(rel to displayName))
    }

    private fun downloadSelected() {
        val list = selected.map { it to it.substringAfterLast('/') }
        if (list.isEmpty()) return
        download(list)
    }

    private fun download(files: List<Pair<String, String>>) {
        toast(getString(R.string.downloading, files.size))
        thread(name = "files-download") {
            var ok = 0
            files.forEach { (rel, name) ->
                try {
                    val tmp = File(cacheDir, "dl_${System.nanoTime()}_${name}")
                    FilesApi.download(cfg, rel, tmp) { _, _ -> }
                    val saved = FilesApi.saveToDownloads(this, name, "application/octet-stream") {
                        tmp.readBytes()
                    }
                    tmp.delete()
                    if (saved != null) ok++
                } catch (e: Exception) {
                    Log.w(TAG, "download $rel: ${e.message}")
                }
            }
            runOnUiThread {
                toast(
                    if (ok == files.size) getString(R.string.download_done_all, ok)
                    else getString(R.string.download_done_part, ok, files.size)
                )
                if (selecting) stopSelecting()
            }
        }
    }

    // ------------------------------------------------------------------
    // 上传（多选）
    // ------------------------------------------------------------------

    private fun pickFiles() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            startActivityForResult(intent, REQ_UPLOAD)
        } catch (e: Exception) {
            Log.w(TAG, "no picker: ${e.message}")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_UPLOAD || resultCode != Activity.RESULT_OK) return
        val uris = ArrayList<Uri>()
        data?.data?.let { uris.add(it) }
        data?.clipData?.let { cd ->
            for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri)
        }
        if (uris.isEmpty()) return
        upload(uris)
    }

    private fun upload(uris: List<Uri>) {
        toast(getString(R.string.uploading_n, uris.size))
        thread(name = "files-upload") {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .writeTimeout(300, TimeUnit.SECONDS)
                    .readTimeout(300, TimeUnit.SECONDS)
                    .apply { if (cfg.trustAllCerts) EndpointProber.applyTrustAll(this) }
                    .build()
                val auth = if (!cfg.username.isNullOrEmpty()) {
                    "Basic " + java.util.Base64.getEncoder().encodeToString(
                        "${cfg.username}:${cfg.password ?: ""}".toByteArray()
                    )
                } else null
                val base = FilesApi.baseUrl(cfg)
                val mediaType = "application/octet-stream".toMediaType()

                uris.forEach { uri ->
                    val name = queryDisplayName(uri)
                        ?: "upload_${System.currentTimeMillis()}"
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("cannot read $name")
                    val destRel = if (path.isEmpty()) name else "$path/$name"
                    val urlEncoded = java.net.URLEncoder.encode(destRel, "UTF-8")
                        .replace("+", "%20")
                    val total = bytes.size.toLong()
                    if (total <= UPLOAD_CHUNK) {
                        val req = Request.Builder()
                            .url("$base/api/upload")
                            .header("Content-Type", "application/octet-stream")
                            .header("X-Upload-Path", urlEncoded)
                            .apply { if (auth != null) header("Authorization", auth) }
                            .post(bytes.toRequestBody(mediaType))
                            .build()
                        client.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} ($name)")
                        }
                    } else {
                        val uploadId = java.util.UUID.randomUUID().toString().substring(0, 16)
                        var off = 0L
                        while (off < total) {
                            val end = minOf(off + UPLOAD_CHUNK, total)
                            val slice = bytes.copyOfRange(off.toInt(), end.toInt())
                            val req = Request.Builder()
                                .url("$base/api/upload")
                                .header("Content-Type", "application/octet-stream")
                                .header("X-Upload-Path", urlEncoded)
                                .header("X-Upload-Id", uploadId)
                                .header("X-Upload-Offset", off.toString())
                                .header("X-Upload-Total", total.toString())
                                .header("X-Upload-Final", if (end >= total) "1" else "0")
                                .apply { if (auth != null) header("Authorization", auth) }
                                .post(slice.toRequestBody(mediaType))
                                .build()
                            client.newCall(req).execute().use { resp ->
                                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} ($name)")
                            }
                            off = end
                        }
                    }
                }
                runOnUiThread {
                    toast(getString(R.string.upload_done_all, uris.size))
                    reload()
                }
            } catch (e: Exception) {
                Log.w(TAG, "upload failed: ${e.message}")
                runOnUiThread { toast("${getString(R.string.upload_failed, "")} ${e.message}") }
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (selecting) {
            stopSelecting()
            return
        }
        if (path.isNotEmpty()) {
            path = path.substringBeforeLast('/', "")
            reload()
            return
        }
        super.onBackPressed()
    }
}

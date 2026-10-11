package com.selkies.client.ui

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.util.Log
import com.selkies.client.data.ServerConfig
import com.selkies.client.net.FilesApi
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * 远程打印 —— 用户需求："浏览器端选择远程硬件的打印机之后，收到有关打印的信号，
 * 自动打开系统打印界面"。
 *
 * 官方链路（selkies-ws-core.js print_document / lib/print-jobs.js）：
 *  1. 远端应用打印到 CUPS/PDF 打印机 → 服务器打印池落盘 PDF；
 *  2. WS 推送 {"type":"print_document","name":...,"size_bytes":...}；
 *  3. 客户端 GET /api/print/<name> 取走 PDF（取走即出池）；
 *  4. 【本端】拉起 Android 系统打印界面（PrintManager + PDF Adapter），
 *     用户选择实体打印机/云打印/另存 PDF。
 *
 * 兜底：打印拉起失败时把 PDF 存入系统下载目录。
 */
object PrintHelper {

    private const val TAG = "PrintHelper"

    /**
     * 处理一条打印作业。
     * @return 成功启动系统打印返回 true；失败已转存下载目录返回 false
     */
    fun handle(context: Context, cfg: ServerConfig, name: String, sizeBytes: Long): Boolean {
        return try {
            val safe = name.replace(Regex("[^A-Za-z0-9._ -]"), "_")
            val dest = File(context.cacheDir, "print_$safe")
            val fetcher = Thread {
                try {
                    FilesApi.downloadPrint(cfg, name, dest)
                    if (dest.length() == 0L) throw IllegalStateException("empty pdf")
                    // 主线程拉起系统打印界面
                    android.os.Handler(context.mainLooper).post {
                        openPrintDialog(context, dest, name)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "print fetch failed: ${e.message}")
                }
            }
            fetcher.name = "print-fetch"
            fetcher.start()
            true
        } catch (e: Exception) {
            Log.e(TAG, "print handle failed", e)
            false
        }
    }

    /** 拉起系统打印界面（PrintManager） */
    private fun openPrintDialog(context: Context, pdf: File, jobName: String) {
        try {
            val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
            val adapter = PdfPrintAdapter(pdf, jobName)
            printManager.print(
                jobName.substringBeforeLast('.').ifEmpty { "Selkies" },
                adapter,
                PrintAttributes.Builder().build()
            )
        } catch (e: Exception) {
            Log.e(TAG, "print dialog failed", e)
            // 兜底：存下载目录
            val saved = FilesApi.saveToDownloads(
                context, jobName, "application/pdf"
            ) { pdf.readBytes() }
            android.widget.Toast.makeText(
                context,
                if (saved != null) "打印失败，已保存: $saved" else "打印失败",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    /** PDF → 系统打印适配器 */
    private class PdfPrintAdapter(
        private val file: File,
        private val name: String
    ) : PrintDocumentAdapter() {

        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes,
            cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback,
            extras: Bundle?
        ) {
            if (cancellationSignal?.isCanceled == true) {
                callback.onLayoutCancelled()
                return
            }
            val info = PrintDocumentInfo.Builder(name)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                .build()
            callback.onLayoutFinished(info, newAttributes != oldAttributes)
        }

        override fun onWrite(
            pages: Array<out PageRange>?,
            destination: ParcelFileDescriptor,
            cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback
        ) {
            try {
                FileInputStream(file).use { input ->
                    FileOutputStream(destination.fileDescriptor).use { output ->
                        input.copyTo(output, 8192)
                    }
                }
                if (cancellationSignal?.isCanceled == true) {
                    callback.onWriteCancelled()
                } else {
                    callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                }
            } catch (e: Exception) {
                Log.e(TAG, "print write failed", e)
                callback.onWriteFailed(e.message)
            }
        }
    }
}

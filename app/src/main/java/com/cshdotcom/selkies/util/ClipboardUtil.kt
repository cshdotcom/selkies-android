package com.cshdotcom.selkies.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/** 剪贴板小工具：仅前台读取（Android 10+ 限制），写入总是允许 */
object ClipboardUtil {
    fun set(context: Context, text: String) {
        if (text.isEmpty()) return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("selkies", text))
    }

    fun get(context: Context): String? {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
        } catch (e: Exception) { null }
    }
}

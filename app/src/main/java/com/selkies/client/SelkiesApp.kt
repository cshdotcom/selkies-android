package com.selkies.client

import android.app.Application
import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 应用入口。
 *
 * 主要职责：
 * 1. 提供全局 Context 访问；
 * 2. 安装全局未捕获异常记录器 —— 任何线程上的未处理异常都会先落盘到
 *    filesDir/crash/ 目录再交给系统默认处理，方便用户反馈“闪退”问题时
 *    有迹可循（这是排查连接闪退的关键日志通道）。
 */
class SelkiesApp : Application() {

    companion object {
        @Volatile
        private var instance: SelkiesApp? = null

        /** 全局 Application Context（线程安全） */
        fun get(): SelkiesApp = instance
            ?: throw IllegalStateException("SelkiesApp not created yet")
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashLogger()
    }

    /**
     * 全局崩溃日志：写入应用私有目录 crash/ 下，按时间戳命名。
     * 注意：这里记录后仍然交回系统默认处理器（进程依旧会终止），
     * 但日志保留了第一现场。
     */
    private fun installCrashLogger() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val dir = File(filesDir, "crash").apply { mkdirs() }
                val file = File(dir, "crash_${System.currentTimeMillis()}.txt")
                file.writeText(buildString {
                    appendLine("time: ${java.util.Date()}")
                    appendLine("thread: ${thread.name}")
                    appendLine("stack:")
                    val sw = StringWriter()
                    throwable.printStackTrace(PrintWriter(sw))
                    appendLine(sw.toString())
                })
            } catch (_: Exception) {
                // 记录失败也不能阻断默认流程
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    /** 供“关于”页展示最近的崩溃日志（帮助诊断问题） */
    fun latestCrashLog(): String? = try {
        val dir = File(filesDir, "crash")
        dir.listFiles()?.maxByOrNull { it.lastModified() }?.takeIf { it.length() < 512 * 1024 }?.readText()
    } catch (_: Exception) {
        null
    }

    /** 清空崩溃日志 */
    fun clearCrashLogs() {
        try {
            File(filesDir, "crash").deleteRecursively()
        } catch (_: Exception) {
        }
    }
}

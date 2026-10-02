package com.something.sthkey.core.log

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃日志落盘。
 *
 * ============================================================
 * 为什么需要它
 * ============================================================
 * [AppLog] 是内存环形缓冲，进程一死内容就没了 —— 而崩溃恰恰是"进程马上就死"，
 * 所以调试页永远看不到崩溃原因。真机上复现问题时又常常没连电脑，
 * 拿不到 logcat。因此这里把未捕获异常写到文件，
 * 下次启动时在调试页展示，用户可以一键复制发出来。
 *
 * ============================================================
 * 实现要点
 * ============================================================
 * - 安装在 `Application.onCreate` 最早期，保证能捕获启动阶段的崩溃；
 * - 只做最小工作（拼字符串 + 写文件），因为进程随时会被杀；
 * - 处理完必须交还给系统默认处理器，否则 ANR / 崩溃对话框等行为会异常。
 */
object CrashLogger {

    private const val TAG = "Crash"

    private const val FILE_NAME = "last_crash.txt"

    /** 最多保留的日志长度，避免异常信息过大把文件写爆 */
    private const val MAX_CHARS = 16_000

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** 是否已经安装过处理器 */
    @Volatile
    private var installed = false

    /**
     * 安装全局崩溃捕获。
     *
     * 应在 `Application.onCreate` 里第一时间调用。
     */
    fun install(context: Context) {
        if (installed) return
        installed = true

        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeToFile(appContext, thread, throwable)
            } catch (_: Throwable) {
                // 崩溃处理里再抛异常就什么都救不回来了，直接忽略
            } finally {
                // 交还给系统（或上一个处理器），保证系统的崩溃流程照常进行
                previous?.uncaughtException(thread, throwable)
            }
        }
    }

    /** 读取上一次崩溃记录；没有则返回 null */
    fun readLast(context: Context): String? {
        val file = crashFile(context)
        if (!file.exists()) return null
        return try {
            file.readText().ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    /** 记录的写入时间（文件修改时间），列表展示用 */
    fun lastCrashTime(context: Context): Long? =
        crashFile(context).takeIf { it.exists() }?.lastModified()

    /** 清除崩溃记录（用户看过后手动清掉） */
    fun clear(context: Context) {
        try {
            crashFile(context).delete()
        } catch (_: Exception) {
        }
    }

    private fun writeToFile(context: Context, thread: Thread, throwable: Throwable) {
        val stackTrace = StringWriter().also { writer ->
            PrintWriter(writer).use { throwable.printStackTrace(it) }
        }.toString()

        val content = buildString {
            appendLine("时间：${timeFormat.format(Date())}")
            appendLine("线程：${thread.name}")
            appendLine("应用版本：${appVersion(context)}")
            appendLine("设备：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / API ${android.os.Build.VERSION.SDK_INT}")
            appendLine("异常：${throwable.javaClass.name}: ${throwable.message}")
            appendLine()
            appendLine(stackTrace)
        }.take(MAX_CHARS)

        crashFile(context).writeText(content)

        // 同时打进内存日志与 logcat，连着电脑时也能直接看到
        AppLog.e(TAG, "捕获到未处理异常：${throwable.javaClass.simpleName}: ${throwable.message}")
    }

    private fun crashFile(context: Context): File = File(context.filesDir, FILE_NAME)

    private fun appVersion(context: Context): String = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName}(${info.longVersionCode})"
    } catch (_: Exception) {
        "unknown"
    }
}

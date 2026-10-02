package com.something.sthkey.core.log

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一条应用日志。
 *
 * 时间戳在构造时就格式化好，UI 只负责展示，避免列表滚动时反复创建格式化对象。
 */
data class LogRecord(
    /** 自增序号，用于稳定排序（同一毫秒内的多条日志也能保持先后顺序） */
    val seq: Long,
    /** 事件发生时间（Unix 毫秒） */
    val timeMillis: Long,
    /** 预格式化的 "HH:mm:ss.SSS" */
    val timeText: String,
    val level: LogLevel,
    /** 来源标记，例如 "App" / "Capture" / "Shizuku" / "Shizuku-Input" */
    val tag: String,
    val message: String,
) {
    /** 调试页的一行文本，复制日志时也用这个格式 */
    fun toLine(): String = "$timeText ${level.label}/$tag: $message"

    internal companion object {
        /** 注意：SimpleDateFormat 非线程安全，这里用 ThreadLocal 包一层 */
        private val FORMATTER = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat =
                SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        }

        fun formatTime(timeMillis: Long): String =
            FORMATTER.get()!!.format(Date(timeMillis))
    }
}

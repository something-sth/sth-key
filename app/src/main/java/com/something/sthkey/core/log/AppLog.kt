package com.something.sthkey.core.log

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 应用内日志中枢。
 *
 * 为什么需要它（而不是只看 logcat）：
 * - 采集相关的问题多数只在真机上复现，用户不一定连着电脑；
 * - 调试页要能把"我点了什么 → 应用做了什么"串起来看；
 * - root / shizuku 两条采集路径的每次启停都必须留痕，方便定位。
 *
 * 因此这里做两件事：
 * 1. 写入内存环形缓冲（供调试页展示）；
 * 2. 同步转发到 android.util.Log（连着电脑时仍可看 logcat）。
 *
 * UI 通过 [updates] 订阅变化：它只是一个自增计数，不承载日志内容，
 * 避免高频日志把 Compose 重组拖垮。
 */
object AppLog {

    /** 调试页一次最多加载多少条 */
    const val DISPLAY_LIMIT = 800

    private val buffer = LogBuffer(LogBuffer.DEFAULT_CAPACITY)

    private val _updates = MutableStateFlow(0L)

    /** 每次写入自增；UI 收集它来触发刷新 */
    val updates: StateFlow<Long> = _updates.asStateFlow()

    fun d(tag: String, message: String) = append(LogLevel.DEBUG, tag, message)

    fun i(tag: String, message: String) = append(LogLevel.INFO, tag, message)

    fun w(tag: String, message: String) = append(LogLevel.WARN, tag, message)

    fun e(tag: String, message: String) = append(LogLevel.ERROR, tag, message)

    /** 记录异常：消息 + 异常类型 + 异常消息，避免把整段堆栈塞进一行 */
    fun e(tag: String, message: String, throwable: Throwable) {
        append(
            LogLevel.ERROR,
            tag,
            "$message · ${throwable.javaClass.simpleName}: ${throwable.message}",
        )
    }

    /**
     * 取最近的日志。
     *
     * @param limit    最多返回多少条（返回最新的这些）
     * @param minLevel 最低级别过滤，null 表示不过滤
     * @param query    关键字过滤，大小写不敏感，匹配 tag 或 message
     */
    fun recent(
        limit: Int = DISPLAY_LIMIT,
        minLevel: LogLevel? = null,
        query: String = "",
    ): List<LogRecord> {
        val keyword = query.trim()
        val filtered = buffer.snapshot().asSequence()
            .filter { minLevel == null || it.level.ordinal >= minLevel.ordinal }
            .filter { record ->
                keyword.isEmpty() ||
                    record.message.contains(keyword, ignoreCase = true) ||
                    record.tag.contains(keyword, ignoreCase = true)
            }
            .toList()

        val take = limit.coerceAtLeast(0)
        return if (filtered.size <= take) filtered else filtered.subList(filtered.size - take, filtered.size)
    }

    /** 全部日志的文本形式，用于"复制日志" */
    fun dump(): String = buffer.snapshot().joinToString(separator = "\n") { it.toLine() }

    /** 已写入的总条数 */
    val totalCount: Long
        get() = buffer.writtenCount

    fun clear() {
        buffer.clear()
        i(TAG, "日志已清空")
    }

    private fun append(level: LogLevel, tag: String, message: String) {
        buffer.append(level, tag, message)
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, message)
            LogLevel.INFO -> Log.i(tag, message)
            LogLevel.WARN -> Log.w(tag, message)
            LogLevel.ERROR -> Log.e(tag, message)
        }
        _updates.value = buffer.writtenCount
    }

    private const val TAG = "App"
}

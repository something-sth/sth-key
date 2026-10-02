package com.something.sthkey.core.log

/**
 * 定长环形日志缓冲。
 *
 * 设计意图：
 * - 采集线程每秒可能产生大量事件，内存必须**有上限**，不能无限增长；
 * - 调试页只需要"最近 N 条"，环形写入是 O(1) 且稳态下不产生垃圾；
 * - 排序/过滤只在 UI 取快照时做一次，写入路径保持极轻。
 *
 * 线程安全：所有公开方法通过 [lock] 串行化。
 */
class LogBuffer(val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0) { "capacity 必须为正数" }
    }

    private val lock = Any()

    private val records = arrayOfNulls<LogRecord>(capacity)

    /** 下一个写入位置 */
    private var writeIndex = 0

    /** 已写入的总条数（可能大于 capacity） */
    private var totalWritten = 0L

    /** 自增序号，保证同毫秒内顺序稳定 */
    private var sequence = 0L

    fun append(level: LogLevel, tag: String, message: String): LogRecord {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val record = LogRecord(
                seq = sequence++,
                timeMillis = now,
                timeText = LogRecord.formatTime(now),
                level = level,
                tag = tag,
                message = message,
            )
            records[writeIndex] = record
            writeIndex = (writeIndex + 1) % capacity
            totalWritten++
            return record
        }
    }

    /**
     * 当前缓冲区内容，按时间从旧到新排列。
     *
     * @param limit 最多返回多少条；超过时返回**最新的** limit 条。默认全部。
     */
    fun snapshot(limit: Int = Int.MAX_VALUE): List<LogRecord> {
        synchronized(lock) {
            val size = sizeLocked()
            if (size == 0) return emptyList()

            val take = limit.coerceIn(0, size)
            if (take == 0) return emptyList()

            // 缓冲区未写满时最旧的一条在 0；写满后最旧的一条在 writeIndex。
            val oldest = if (size < capacity) 0 else writeIndex

            val result = ArrayList<LogRecord>(take)
            for (offset in (size - take) until size) {
                records[(oldest + offset) % capacity]?.let(result::add)
            }
            return result
        }
    }

    /** 当前实际保存的条数（不超过 capacity） */
    val size: Int
        get() = synchronized(lock) { sizeLocked() }

    /** 已写入的总条数，UI 用它判断"是否有新日志" */
    val writtenCount: Long
        get() = synchronized(lock) { totalWritten }

    fun clear() {
        synchronized(lock) {
            records.fill(null)
            writeIndex = 0
            totalWritten = 0L
        }
    }

    private fun sizeLocked(): Int =
        if (totalWritten > capacity) capacity else totalWritten.toInt()

    companion object {
        /** 默认保留 2000 条：足够定位一次复现，内存占用可忽略 */
        const val DEFAULT_CAPACITY = 2000
    }
}

package com.something.sthkey.capture.shizuku

/**
 * `getevent` 一行输出的解析结果。
 *
 * ============================================================
 * 为什么从"只返回事件"改成三类结果
 * ============================================================
 * 原来是一设备一进程（`getevent /dev/input/eventN`），那种用法下
 * **不会有设备公告**，只有事件行，所以解析器只需要返回事件。
 *
 * 现在改成全局监听 `getevent -lt`（不带 device 参数），它会额外打印：
 *
 * ```
 * add device 5: /dev/input/event3
 *   name:     "Xbox Wireless Controller"
 * remove device 5: /dev/input/event3
 * ```
 *
 * 于是解析器必须能区分"事件行"与"设备公告行" ——
 * 后者是**热插拔的来源**，而前者需要带上设备路径（设备拔出时按设备清状态）。
 */
sealed interface ParsedLine {

    /**
     * 一个输入事件。
     *
     * @param device 设备路径（`/dev/input/event3`）；解析不出时为 null。
     *   `-lt` 模式下每行都带，所以正常情况下不会是 null。
     * @param timestampMicros 内核时间戳（微秒）。取不到为 0。
     *
     *   ⚠️ 暂时**没有**用它替换 `uptimeMillis()`（那是独立的一块改动，
     *   混在一起出问题就分不清是谁的）。但解析时顺手带上，
     *   免得以后要用还得再动一次解析。
     */
    data class Event(
        val device: String?,
        val type: Int,
        val code: Int,
        val value: Int,
        val timestampMicros: Long,
    ) : ParsedLine

    /** 设备接入（启动时 `getevent` 会把当时所有设备都公告一遍） */
    data class Attached(val device: String) : ParsedLine

    /** 设备拔出 */
    data class Detached(val device: String) : ParsedLine
}

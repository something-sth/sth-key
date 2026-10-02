package com.something.sthkey.capture

import com.something.sthkey.domain.config.KeyStrokesConfig
import com.something.sthkey.domain.style.KeyLayout

/**
 * CPS（每秒点击次数）统计。
 *
 * ============================================================
 * 为什么按"键位"而不是按"键码"统计
 * ============================================================
 * LMB / RMB 位置可以绑定**任意**输入键码 —— 有些玩家用键盘按键代替鼠标
 * （比如把某个键映射成 LMB）。如果统计里写死 BTN_LEFT(272) / BTN_RIGHT(273)，
 * 这类玩家的 CPS 就永远是 0。
 *
 * 因此这里按 [KeyLayout.Id.LMB] / [KeyLayout.Id.RMB] 这两个**位置**统计：
 * 一个位置绑了哪些键码，它们的点击就都算在这个位置上。
 *
 * ============================================================
 * 线程安全
 * ============================================================
 * 采集线程写（[recordClick]），UI 线程读（[cpsOf]），因此内部加锁。
 * 数据量极小（每个位置一个时间队列），锁竞争可以忽略。
 */
class CpsCounter {

    /** 位置 → 该位置的时间窗口内的点击时刻队列 */
    private val clicks = mutableMapOf<String, ArrayDeque<Long>>()

    private val lock = Any()

    /**
     * 记录一次点击。
     *
     * ============================================================
     * 为什么不再限制"只统计 LMB / RMB"
     * ============================================================
     * 早先这里写死了 `if (slotId != LMB && slotId != RMB) return` ——
     * 那是"CPS 只能显示鼠标点击次数"时代的假设。自定义 Key 之后，
     * 用户可以给**任意一个绑定了按键的位置**显示 CPS（例如 Q 键按了多少次/秒），
     * 于是这个限制就成了"功能做了但永远显示 0"。
     *
     * 现在**任何槽位都统计**。代价是极端情况下会多几个槽位的数据
     * （每个槽位一个时间队列，几十字节），完全不值得为它设限。
     *
     * @param slotId 键位标识（W / A / SPACE / LMB / RMB…）—— 就是键位映射里那个 id
     */
    fun recordClick(slotId: String, now: Long = System.currentTimeMillis()) {
        if (slotId.isBlank()) return

        synchronized(lock) {
            val queue = clicks.getOrPut(slotId) { ArrayDeque() }
            queue.addLast(now)
            pruneLocked(queue, now)
        }
    }

    /** 读取某个位置的 CPS（最近 [WINDOW_MS] 毫秒内的点击数） */
    fun cpsOf(slotId: String, now: Long = System.currentTimeMillis()): Int =
        synchronized(lock) {
            val queue = clicks[slotId] ?: return 0
            pruneLocked(queue, now)
            queue.size
        }

    /**
     * 一次性读出**所有**槽位的 CPS。
     *
     * 悬浮窗每秒都要取一次（自定义 Key 的文本组件可以绑任意槽位，
     * 事先不知道要哪几个），所以整份快照比逐个查询更省事，也少几次加锁。
     */
    fun snapshot(now: Long = System.currentTimeMillis()): Map<String, Int> =
        synchronized(lock) {
            buildMap {
                clicks.keys.forEach { slotId ->
                    val queue = clicks.getValue(slotId)
                    pruneLocked(queue, now)
                    put(slotId, queue.size)
                }
            }
        }

    /** 清空；停止采集时必须调用 */
    fun clear() {
        synchronized(lock) { clicks.clear() }
    }

    /** 丢弃窗口之外的时间点 */
    private fun pruneLocked(queue: ArrayDeque<Long>, now: Long) {
        while (queue.isNotEmpty() && now - queue.first() >= WINDOW_MS) {
            queue.removeFirst()
        }
    }

    companion object {
        /** CPS 的统计窗口：1 秒 */
        const val WINDOW_MS = 1000L

        /**
         * 从配置里算出"某个输入键码属于哪个位置"。
         *
         * 这正是"键码 → 位置"的多对一映射（见 [KeyLayout.codeToSlotMap]）。
         * 一个位置可能绑了多个物理键（左右 Shift、左右 Meta），
         * 所以反查是**多对一**的：那几个键的点击都算同一个位置的 CPS。
         *
         * 图省事的话可以认为它就是"某个按键的 CPS 该记在哪个桶里"。
         */
        fun slotOf(config: KeyStrokesConfig, code: Int): String? =
            KeyLayout.codeToSlotMap(config)[code]
    }
}

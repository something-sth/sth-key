package com.something.sthkey.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "哪个设备按着哪些键"的记账。
 *
 * ============================================================
 * 这里防的是什么
 * ============================================================
 * 全局监听（`getevent -lt`）下设备可能在**按键还按着**的时候被拔掉，
 * 内核**不会**补发 UP 事件。不释放的话那个键会**永久卡在按下态** ——
 * 悬浮窗上一直亮着，用户完全不知道为什么，只能去重启监听。
 *
 * 而"释放"有两种写法，**只有一种是对的**：
 *
 * | 写法 | 后果 |
 * |---|---|
 * | 拔设备就清空全部按键 | 键盘上按着 W、拔掉鼠标 → **W 也灭了**。用户会以为"动了动鼠标键盘就失灵了" |
 * | 只清那个设备持有的键 | 正确 |
 *
 * 第二种要求"知道每个键由谁按着"，这就是这个类的全部职责。
 */
class HeldKeyTrackerTest {

    private val keyboard = "/dev/input/event3"
    private val mouse = "/dev/input/event5"

    @Test
    fun `设备拔出时只释放它持有的键`() {
        val tracker = HeldKeyTracker()
        tracker.onKey(code = KEY_W, pressed = true, device = keyboard)
        tracker.onKey(code = BTN_LEFT, pressed = true, device = mouse)

        val released = tracker.releaseDevice(mouse)

        assertEquals("只应释放鼠标的那个键", listOf(BTN_LEFT), released)
        assertEquals("键盘的 W 必须还按着", 1, tracker.heldCount())
    }

    /**
     * ⚠️ 同一个键可能**同时**来自多个输入节点。
     *
     * 一个手柄既暴露标准 evdev 节点、又暴露厂商扩展节点是常见的。
     * 只有**所有**持有者都走了才该释放。
     */
    @Test
    fun `同一个键被两个设备按着时，只走一个不释放`() {
        val tracker = HeldKeyTracker()
        tracker.onKey(code = BTN_LEFT, pressed = true, device = keyboard)
        tracker.onKey(code = BTN_LEFT, pressed = true, device = mouse)

        assertTrue("走掉一个设备后不该释放", tracker.releaseDevice(mouse).isEmpty())
        assertEquals("另一个设备还按着", 1, tracker.heldCount())

        assertEquals("最后一个持有者走了才释放", listOf(BTN_LEFT), tracker.releaseDevice(keyboard))
        assertEquals(0, tracker.heldCount())
    }

    @Test
    fun `正常抬起后不会被重复释放`() {
        val tracker = HeldKeyTracker()
        tracker.onKey(code = KEY_W, pressed = true, device = keyboard)
        tracker.onKey(code = KEY_W, pressed = false, device = keyboard)

        assertEquals("已经抬起了，不该再释放", emptyList<Int>(), tracker.releaseDevice(keyboard))
        assertEquals(0, tracker.heldCount())
    }

    /**
     * ⚠️ 设备路径未知时（某些 ROM 的公告只打节点序号）**释放全部**。
     *
     * 保守但安全：漏清会永久卡键，误清只是"松手了"，用户再按一下就回来。
     */
    @Test
    fun `路径未知时释放全部`() {
        val tracker = HeldKeyTracker()
        tracker.onKey(code = KEY_W, pressed = true, device = keyboard)
        tracker.onKey(code = BTN_LEFT, pressed = true, device = mouse)

        val released = tracker.releaseDevice("")

        assertEquals("两个都要释放", setOf(KEY_W, BTN_LEFT), released.toSet())
        assertEquals(0, tracker.heldCount())
    }

    /** 抬起一个从没按下过的键不该崩、也不该留下记录 */
    @Test
    fun `抬起未记录的键是安全的`() {
        val tracker = HeldKeyTracker()
        tracker.onKey(code = KEY_W, pressed = false, device = keyboard)

        assertEquals(0, tracker.heldCount())
    }

    /**
     * ⚠️ 按设备计数，而不是"记一个设备集合"。
     *
     * 同一个设备上**同一个键**收到两次 DOWN（某些设备会重复上报）
     * 时，一次 UP 不该就算它抬起了 —— 计数要能表达这种情形。
     */
    @Test
    fun `同一设备重复按下要按次数计`() {
        val tracker = HeldKeyTracker()
        tracker.onKey(code = KEY_W, pressed = true, device = keyboard)
        tracker.onKey(code = KEY_W, pressed = true, device = keyboard)

        tracker.onKey(code = KEY_W, pressed = false, device = keyboard)
        assertEquals("还欠一次抬起，仍然算按着", 1, tracker.heldCount())

        tracker.onKey(code = KEY_W, pressed = false, device = keyboard)
        assertEquals(0, tracker.heldCount())
    }

    @Test
    fun `releaseAll 返回全部并清空`() {
        val tracker = HeldKeyTracker()
        tracker.onKey(code = KEY_W, pressed = true, device = keyboard)
        tracker.onKey(code = BTN_LEFT, pressed = true, device = mouse)

        assertEquals(setOf(KEY_W, BTN_LEFT), tracker.releaseAll().toSet())
        assertEquals(0, tracker.heldCount())
    }

    private companion object {
        /** KEY_W：内核里 W 的键码 */
        const val KEY_W = 17

        /** BTN_LEFT：鼠标左键 */
        const val BTN_LEFT = 272
    }
}

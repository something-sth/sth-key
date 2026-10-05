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
     * ⚠️ 同一个设备上重复的 DOWN **不再累加** —— 这是修 bug 改的语义。
     *
     * ============================================================
     * 老行为（以及它造成的真机 bug）
     * ============================================================
     * 老实现是 `byDevice[key] = (byDevice[key] ?: 0) + 1`，于是
     * `DOWN, DOWN, UP` 之后计数是 **1** —— 这个键**永久卡在按下态**，
     * 只有重启应用（[HeldKeyTracker.releaseAll]）才清得掉。
     *
     * 用户遇到的原话:"手柄开机之后，悬浮窗上 LT 与 RT 被一直识别成
     * 按下状态……重启监听都没用，只能划掉后台才能恢复"。
     * 成因就是手柄开机瞬间轴值抖动，扳机反复跨过阈值发了多条 DOWN。
     *
     * ============================================================
     * ⚠️ 老测试的前提其实不成立
     * ============================================================
     * 老测试的注释写着"某些设备会重复上报"，于是要求 `DOWN, DOWN, UP, UP`
     * 计两次。但 **evdev 不会连发两条同值的 `value=1`** ——
     * 长按是 `value=2`（REPEAT），而那条路在
     * [com.something.sthkey.capture.KeyStateManager] 里已被显式忽略。
     *
     * 所以"两次 DOWN"在真实设备上**只可能是抖动或脏数据**，
     * 而为一件不会发生的事保留"永久卡键"的代价太大了。
     *
     * ============================================================
     * ⚠️ 这不影响它存在的理由：**多设备**计数
     * ============================================================
     * "同一个键由两个不同设备按着"（键盘与手柄报同一个键码）依然靠计数
     * 区分 —— 见上面 `同一个键被两个设备按着时，只走一个不释放`。
     * 被夹掉的只是"**同一个设备**说同一个键按了两次"，而那在物理上不存在。
     */
    @Test
    fun `同一设备重复按下不累加（防止计数顶高导致永久卡键）`() {
        val tracker = HeldKeyTracker()
        tracker.onKey(code = KEY_W, pressed = true, device = keyboard)
        /* 抖动 / 脏数据造成的第二条 DOWN */
        tracker.onKey(code = KEY_W, pressed = true, device = keyboard)

        /*
         * ⚠️ 一次抬起就该完全释放 —— 这是关键断言。
         *
         * 老行为在这里会留下计数 1，也就是那个"永久卡在按下状态"。
         */
        tracker.onKey(code = KEY_W, pressed = false, device = keyboard)
        assertEquals("一次抬起就该完全释放（老行为会卡在 1）", 0, tracker.heldCount())
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

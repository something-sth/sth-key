package com.something.sthkey.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CPS 计数的测试。
 *
 * ============================================================
 * 为什么专门测"任意槽位都能计数"
 * ============================================================
 * 这里原本写死 `if (slotId != LMB && slotId != RMB) return` ——
 * 那是"CPS 只能显示鼠标点击"时代的假设。自定义 Key 允许给**任意绑定了按键的位置**
 * 显示 CPS（例如 Q 键每秒按了多少次），这个限制会让功能"做完了但永远显示 0"，
 * 而且从界面上完全看不出原因。
 *
 * 所以用测试把"不再限制槽位"这件事钉住。
 */
class CpsCounterTest {

    @Test
    fun `鼠标左右键照常计数`() {
        val counter = CpsCounter()
        counter.recordClick("LMB")
        counter.recordClick("LMB")
        counter.recordClick("RMB")

        assertEquals(2, counter.cpsOf("LMB"))
        assertEquals(1, counter.cpsOf("RMB"))
    }

    @Test
    fun `键盘位置也能计数`() {
        val counter = CpsCounter()
        counter.recordClick("W")
        counter.recordClick("W")
        counter.recordClick("W")

        // 这条在旧实现下会是 0（被 LMB/RMB 白名单挡掉了）
        assertEquals(3, counter.cpsOf("W"))
    }

    @Test
    fun `快照包含全部槽位`() {
        val counter = CpsCounter()
        counter.recordClick("Q")
        counter.recordClick("LMB")

        val snapshot = counter.snapshot()
        assertTrue("快照里应该有 Q", snapshot.containsKey("Q"))
        assertTrue("快照里应该有 LMB", snapshot.containsKey("LMB"))
        assertEquals(1, snapshot["Q"])
    }

    @Test
    fun `一对多的位置共享计数`() {
        // 左右 Shift 属于同一个映射位置，它们的点击应该算在一起 ——
        // 这正是"按位置而不是按键码统计"的意义
        val counter = CpsCounter()
        counter.recordClick("SHIFT")
        counter.recordClick("SHIFT")

        assertEquals(2, counter.cpsOf("SHIFT"))
    }

    @Test
    fun `没有记录的槽位返回零`() {
        val counter = CpsCounter()
        assertEquals(0, counter.cpsOf("从来没按过"))
    }

    @Test
    fun `空槽位被忽略`() {
        val counter = CpsCounter()
        counter.recordClick("")
        assertTrue(counter.snapshot().isEmpty())
    }

    @Test
    fun `超出时间窗口的点击会衰减掉`() {
        val counter = CpsCounter()
        val now = 10_000L

        // 两秒前记的三次点击，已经超出 1 秒的统计窗口
        counter.recordClick("LMB", now = now - 2_000L)
        counter.recordClick("LMB", now = now - 2_000L)
        counter.recordClick("LMB", now = now - 2_000L)
        assertEquals(0, counter.cpsOf("LMB", now = now))

        // 窗口内的仍然算数
        counter.recordClick("LMB", now = now)
        assertEquals(1, counter.cpsOf("LMB", now = now))
    }

    @Test
    fun `清空后全部归零`() {
        val counter = CpsCounter()
        counter.recordClick("LMB")
        counter.recordClick("Q")
        counter.clear()

        assertEquals(0, counter.cpsOf("LMB"))
        assertEquals(0, counter.cpsOf("Q"))
        assertTrue(counter.snapshot().isEmpty())
    }

    /*
     * ============================================================
     * 按配置隔离（这里踩过一个真实的"数字翻倍"bug）
     * ============================================================
     * 计数是按**键位 id** 记的，而"这个 id 属于哪份配置"是配置自己的事：
     * 三份配置都可能有一条 LMB 映射。如果所有配置共用同一个计数器，
     * 一次点击就会被记三次 —— 用户看到的 CPS 直接变成三倍。
     */

    @Test
    fun `同一个槽位在不同配置里互不影响`() {
        // 两份配置各有一个 LMB 槽位，各记各的
        CaptureSession.recordCpsClick("config-a", "LMB")
        CaptureSession.recordCpsClick("config-a", "LMB")
        CaptureSession.recordCpsClick("config-b", "LMB")

        assertEquals(2, CaptureSession.cpsSnapshotOf("config-a")["LMB"])
        assertEquals(1, CaptureSession.cpsSnapshotOf("config-b")["LMB"])
    }

    @Test
    fun `没有记录的配置返回空快照`() {
        assertTrue(CaptureSession.cpsSnapshotOf("从来没出现的配置").isEmpty())
    }

    @Test
    fun `清空某个配置不影响别的配置`() {
        CaptureSession.recordCpsClick("config-c", "LMB")
        CaptureSession.recordCpsClick("config-d", "LMB")
        CaptureSession.clearCpsOf("config-c")

        assertEquals(0, CaptureSession.cpsSnapshotOf("config-c")["LMB"] ?: 0)
        assertEquals(1, CaptureSession.cpsSnapshotOf("config-d")["LMB"])
    }

    @Test
    fun `空槽位不产生计数`() {
        CaptureSession.recordCpsClick("config-e", "")
        assertTrue(CaptureSession.cpsSnapshotOf("config-e").isEmpty())
    }
}

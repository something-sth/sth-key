package com.something.sthkey.capture.shizuku

import com.something.sthkey.capture.shizuku.StartupPolicy.Attempt
import com.something.sthkey.capture.shizuku.StartupPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「先快后稳」启动策略的测试。
 *
 * ============================================================
 * 为什么这些用例值得写
 * ============================================================
 * 这段逻辑有两个特点，使它**必须**靠测试兜住：
 *
 * 1. **真机上不容易复现** —— "第一轮偏快失败、第二轮偏稳成功"要碰巧遇到
 *    慢启动才会发生，不可能每次都手动验；
 * 2. **错了的代价是用户白等或永久失败** —— 该重试时不重试 = 明明能用的
 *    设备被判死；不该重试时重试 = 每次都多等一轮。
 *
 * 所以这里把三类决定各钉住：什么时候重试、失败原因怎么合并、
 * 以及"靠第二轮才成功"要被标记出来。
 */
class StartupPolicyTest {

    /*
     * ============================================================
     * 什么时候重试
     * ============================================================
     */

    @Test
    fun `第一轮成功就不走第二轮`() {
        val decision = StartupPolicy.decide(
            attemptIndex = 0,
            attempt = Attempt(readyCount = 7, failures = emptyList(), allTransient = false),
        )

        assertEquals(Decision.Succeeded(usedFallback = false), decision)
    }

    @Test
    fun `靠第二轮成功要标记为用了回退`() {
        val decision = StartupPolicy.decide(
            attemptIndex = 1,
            attempt = Attempt(readyCount = 3, failures = listOf("x"), allTransient = false),
        )

        assertEquals(Decision.Succeeded(usedFallback = true), decision)
    }

    /**
     * 这条钉的是"常规情况不要白等一轮"。
     *
     * 第一轮全失败、而且失败是"转瞬即逝"的（没有任何报错输出），
     * 才值得花时间再试一次。
     */
    @Test
    fun `第一轮全灭且没有报错证据时值得再试`() {
        val decision = StartupPolicy.decide(
            attemptIndex = 0,
            attempt = Attempt(readyCount = 0, failures = listOf("a", "b"), allTransient = true),
        )

        assertEquals(Decision.Retry, decision)
    }

    /**
     * ⚠️ 这条是本文件最重要的一条。
     *
     * 如果失败**带着明确报错**（`getevent` 打印用法、`could not open`），
     * 那说明原因不在速度上 —— 再来一轮必然同样失败，只是让用户多等一次。
     * 这类"确定性失败"绝不能重试。
     */
    @Test
    fun `有明确报错的确定性失败不重试`() {
        val decision = StartupPolicy.decide(
            attemptIndex = 0,
            attempt = Attempt(
                readyCount = 0,
                failures = listOf("/dev/input/event0：默认参数：启动后立刻结束（读到 13 行输出）"),
                allTransient = false,
            ),
        )

        assertEquals(Decision.GiveUp, decision)
    }

    @Test
    fun `已经是最后一轮时不再重试`() {
        val decision = StartupPolicy.decide(
            attemptIndex = StartupPolicy.STRATEGIES.lastIndex,
            attempt = Attempt(readyCount = 0, failures = listOf("a"), allTransient = true),
        )

        assertEquals(Decision.GiveUp, decision)
    }

    @Test
    fun `部分设备失败但至少有一个成功就算成功`() {
        // 某些节点本来就可能打不开（权限受限的传感器）——
        // 一个打不开不该拖垮全部，更不该为它多跑一轮
        val decision = StartupPolicy.decide(
            attemptIndex = 0,
            attempt = Attempt(readyCount = 5, failures = listOf("a", "b"), allTransient = false),
        )

        assertEquals(Decision.Succeeded(usedFallback = false), decision)
    }

    /*
     * ============================================================
     * 策略本身
     * ============================================================
     */

    @Test
    fun `策略是两轮且先快后稳`() {
        assertEquals(2, StartupPolicy.STRATEGIES.size)

        val fast = StartupPolicy.STRATEGIES[0]
        val steady = StartupPolicy.STRATEGIES[1]

        assertEquals("偏快", fast.label)
        assertEquals("偏稳", steady.label)
        // 顺序不能反：反了就变成"每次都慢"
        assertTrue("偏快的窗口必须更短", fast.probeWindowMs < steady.probeWindowMs)
        assertTrue("偏稳那轮必须做复核", steady.verifyViaProc)
        assertTrue("偏快那轮不该做复核（省一次 binder 往返）", !fast.verifyViaProc)
    }

    /*
     * ============================================================
     * 失败原因必须全部保留
     * ============================================================
     */

    /**
     * ⚠️ 这条钉的是"证据被覆盖"。
     *
     * 真正有用的线索常常在**前一轮**：第一轮读到了 `getevent` 的用法提示，
     * 第二轮因为探活更厚，报的可能只是一句笼统的"进程已退出"。
     * 只报最后一条就把线索丢了 —— 这个项目已经踩过一次，
     * 浪费了一整轮排查。
     */
    @Test
    fun `两轮的失败原因都要出现`() {
        val attempts = listOf(
            Attempt(
                readyCount = 0,
                failures = listOf("/dev/input/event0：默认参数：启动后立刻结束（读到 13 行输出）"),
                allTransient = true,
            ),
            Attempt(
                readyCount = 0,
                failures = listOf("/dev/input/event0：偏稳：进程已退出"),
                allTransient = false,
            ),
        )

        val text = StartupPolicy.describeFailures(attempts)

        assertTrue("第一轮的原因不能丢", text.contains("13 行输出"))
        assertTrue("第二轮的原因也要在", text.contains("进程已退出"))
        assertTrue("要标出是哪一轮", text.contains("偏快") && text.contains("偏稳"))
    }

    @Test
    fun `最终失败提示要说清试了几轮`() {
        val attempts = listOf(
            Attempt(readyCount = 0, failures = listOf("原因一"), allTransient = true),
            Attempt(readyCount = 0, failures = listOf("原因二"), allTransient = false),
        )

        val message = StartupPolicy.failureMessage(attempts)

        // 只看到一轮的原因时，用户会以为我们只试了一次
        assertTrue("要说明试了几种策略", message.contains("2 种"))
        assertTrue(message.contains("原因一"))
        assertTrue(message.contains("原因二"))
    }

    @Test
    fun `某轮没有记录到原因也不会输出空白`() {
        val text = StartupPolicy.describeFailures(
            listOf(Attempt(readyCount = 0, failures = emptyList(), allTransient = true)),
        )

        // 空列表 join 出来是空串，界面上会变成一个孤零零的标签
        assertTrue("要给出占位说明", text.contains("没有记录到原因"))
    }
}

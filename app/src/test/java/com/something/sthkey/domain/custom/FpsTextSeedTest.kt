package com.something.sthkey.domain.custom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `(fps…)` 占位符的偏移**必须按秒**，不能按"渲染次数"。
 *
 * ============================================================
 * ⚠️ 背景（用户反馈）
 * ============================================================
 * 带 `offset` 的占位符，**每按一次按键数值就变一次** —— 因为键盘按下会让
 * 悬浮窗重组，`render(...)` 再跑一遍，早先的实现每次都重新 `random()`。
 *
 * 裸 `(fps)` / `(fps+1000)` 没有随机量，所以看不出问题，只有带 offset 的才明显。
 *
 * 现在随机源由 `seed`（= 采样序号）派生:同一秒内重组多少次，结果都一样。
 */
class FpsTextSeedTest {

    @Test
    fun `同一个 seed 渲染多次结果完全一致`() {
        val template = "(fps+1000,offset=20-80)"
        val first = FpsText.render(template, fps = 60.0, seed = 7L)
        repeat(20) {
            assertEquals(
                "同一秒内的重组不应该改变数值",
                first,
                FpsText.render(template, fps = 60.0, seed = 7L),
            )
        }
    }

    @Test
    fun `换一个 seed 数值会变`() {
        val template = "(fps,offset=20-80)"
        val values = (1L..40L).map { seed -> FpsText.render(template, fps = 60.0, seed = seed) }
        assertTrue("不同秒之间应该有抖动", values.toSet().size > 1)
    }

    @Test
    fun `偏移始终落在设定范围内`() {
        val template = "(fps,offset=20-80)"
        for (seed in 1L..200L) {
            val text = FpsText.render(template, fps = 60.0, seed = seed)
            val value = text.toInt()
            /*
             * 60 ± [20,80]:加上去最多 140，减下去最小 −20 —— 负数会被夹到 0，
             * 所以合法区间是 0..140（不是 40..140）。
             */
            assertTrue("第 $seed 秒的值 $value 超出 0~140", value in 0..140)
        }
    }

    @Test
    fun `没有偏移时 seed 不影响结果`() {
        val template = "(fps+1000)"
        assertEquals("1060", FpsText.render(template, fps = 60.0, seed = 1L))
        assertEquals("1060", FpsText.render(template, fps = 60.0, seed = 999L))
    }

    @Test
    fun `读不到帧率时整体显示 N A`() {
        assertEquals("N/A", FpsText.render("(fps+1000,offset=20-80)", fps = null, seed = 3L))
    }

    @Test
    fun `同一段文字里两个占位符各自抖动`() {
        val template = "(fps,offset=20-80) / (fps,offset=20-80)"
        val text = FpsText.render(template, fps = 60.0, seed = 5L)
        val parts = text.split(" / ")
        assertEquals(2, parts.size)
        assertNotEquals(
            "两个占位符不该得到一模一样的抖动",
            parts[0],
            parts[1],
        )
    }
}

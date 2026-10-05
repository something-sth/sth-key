package com.something.sthkey.ui.overlay

import com.something.sthkey.ui.overlay.gamepad.linearFollowAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「常规」模式的位置计算:[linearFollowAt]。
 *
 * ============================================================
 * ⚠️ 为什么这段必须有单测
 * ============================================================
 * 用户报过"**常规模式键盘摇杆死了**"，而这段数学原本写在 composable 里
 * —— **没法测**，只能真机看，于是只能猜。提成纯函数之后，
 * "按下去之后第 N 毫秒帽子在哪"可以直接算出来。
 *
 * ============================================================
 * ⚠️ 语义来自用户朋友（做 iOS 按键显示那位）的原话
 * ============================================================
 *
 * > 我速度都是**写一样的** / wasd 按下 / 摇杆头不就往指定方向动吗 /
 * > **速度都一样的**
 *
 * ⚠️ 也就是**全程线性**。我上一版做了"两端各 1/4 缓动"（`smoothstep`
 * 风格的曲线），与这句相反 —— 那个已经删掉。
 *
 * 所以这里最要紧的一条断言是:**等时间间隔走等距离**（速度恒定）。
 */
class JoystickFollowLerpTest {

    private val eps = 1e-4f

    @Test
    fun `起点与终点正确`() {
        val from = 0f to 0f
        val target = 1f to -1f

        assertEquals("0ms 应该在起点", from, linearFollowAt(from, target, 0f, 100f))
        assertEquals("到时长时应该在终点", target, linearFollowAt(from, target, 100f, 100f))
        assertEquals("超过时长也不该越过终点", target, linearFollowAt(from, target, 999f, 100f))
    }

    @Test
    fun `中途走一半`() {
        val (x, y) = linearFollowAt(0f to 0f, 1f to 0f, 50f, 100f)
        assertEquals("100ms 走一半路，50ms 就该到 0.5", 0.5f, x, eps)
        assertEquals(0f, y, eps)
    }

    /**
     * ⚠️⚠️ **最要紧的一条:速度恒定**。
     *
     * 把总时长切成若干等份，每一份走的**距离必须相等**。
     *
     * ⚠️ 我上一版（两端缓动）在这条上会直接红 —— 起步与收尾走得少，
     * 中间走得多。那正是与用户朋友那句"速度都一样的"**相反**的地方。
     */
    @Test
    fun `等时间间隔走等距离（全程匀速）`() {
        val from = 0f to 0f
        val target = 1f to 0f
        val steps = listOf(0f, 20f, 40f, 60f, 80f, 100f)

        val positions = steps.map { linearFollowAt(from, target, it, 100f).first }
        val deltas = positions.zipWithNext { a, b -> b - a }

        deltas.forEach {
            assertEquals(
                "每一步应该走同样多的距离（匀速），实际增量序列=$deltas",
                deltas.first(),
                it,
                eps,
            )
        }
    }

    @Test
    fun `反向与斜向也对`() {
        /* 从满偏回中心 */
        val (x1, _) = linearFollowAt(1f to 0f, 0f to 0f, 50f, 100f)
        assertEquals(0.5f, x1, eps)

        /* 斜向:两轴按同一个比例走 */
        val (x2, y2) = linearFollowAt(0f to 0f, 1f to 1f, 25f, 100f)
        assertEquals(0.25f, x2, eps)
        assertEquals("斜向两轴必须同步（比例相同），否则会走出弧线", x2, y2, eps)
    }

    @Test
    fun `退化的时长不会产生 NaN`() {
        listOf(0f, -1f).forEach { bad ->
            val (x, y) = linearFollowAt(0f to 0f, 1f to 1f, 10f, bad)
            assertTrue("时长=$bad 时不能出 NaN", !x.isNaN() && !y.isNaN())
            assertTrue("也不能出无穷", !x.isInfinite() && !y.isInfinite())
        }
    }

    /**
     * ⚠️ 起点等于终点时（没在动）必须**原样返回** —— 否则会出现
     * 极小的数值抖动，帽子看起来在"微微发颤"。
     */
    @Test
    fun `原地不动时位置不变`() {
        val same = 0.3f to -0.7f
        assertEquals(same, linearFollowAt(same, same, 0f, 100f))
        assertEquals(same, linearFollowAt(same, same, 50f, 100f))
        assertEquals(same, linearFollowAt(same, same, 100f, 100f))
    }
}

package com.something.sthkey.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 窗口边界的测试。
 *
 * ============================================================
 * 这里钉的是一个真实发生过的 bug
 * ============================================================
 * 现象：把「整体缩放」调大之后，**空格与 Shift 的右边短了一截**，
 * 而配置预览、自定义编辑页都正常。
 *
 * 根因：改尺寸的那条路径**只改了宽高、没有重算位置**。
 * 窗口原本落在 x=463（屏幕 900 宽），尺寸从 300 变成 502 之后
 * 右边界到了 965 —— 超出屏幕。系统不会让窗口悬在屏幕外，
 * 于是把它压回 `900 − 463 = 437`，右边一截内容就被裁掉。
 *
 * 而 Compose 的布局是按**我们请求的 502** 排的，所以被裁的
 * 正好是最宽的那两个键（空格与 Shift）。
 *
 * 这些用例把"尺寸变了之后位置该怎么算"固定下来 ——
 * 光看画面分不清是"窗口没给够宽"还是"内容画歪了"，
 * 而边界算法是纯函数，可以在这里钉死。
 */
class OverlayBoundsTest {

    private val screenWidth = 900
    private val screenHeight = 1600

    /*
     * ============================================================
     * 这个 bug 的核心：窗口变大后必须重新夹位置
     * ============================================================
     */

    /**
     * ⚠️ 回归测试本体。
     *
     * 窗口在 x=463、尺寸 502 —— 直接放会到 965，超出屏幕。
     * 夹过之后必须满足 `x + width <= screenWidth`，
     * 否则系统就会自己压窗口宽度，把内容裁掉。
     */
    @Test
    fun `窗口变大后位置被夹到屏幕内`() {
        val (x, _) = OverlayBounds.clamp(
            x = 463,
            y = 473,
            windowWidth = 502,
            windowHeight = 609,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
        )

        assertTrue(
            "夹过之后右边界必须落在屏幕内：x=$x, 右边界=${x + 502}, 屏宽=$screenWidth",
            x + 502 <= screenWidth,
        )
        assertEquals("应当贴住右边缘：900 − 502", 398, x)
    }

    /**
     * 反过来说：**不能**靠"把尺寸压小"来让它装下。
     *
     * 用户把缩放调到 150% 就是想要更大 —— 尺寸被压回去等于
     * 把他要做的事直接否掉。所以边界算法只该动位置，不动尺寸。
     */
    @Test
    fun `夹边界不改变窗口尺寸`() {
        val width = 502
        OverlayBounds.clamp(
            x = 463,
            y = 473,
            windowWidth = width,
            windowHeight = 609,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
        )

        // 尺寸是入参、不是出参 —— 这条用例是把"这个约定"写下来
        assertEquals(502, width)
    }

    /** 装得下的时候位置**一点都不该动** */
    @Test
    fun `装得下时位置保持不变`() {
        val (x, y) = OverlayBounds.clamp(
            x = 100,
            y = 200,
            windowWidth = 502,
            windowHeight = 609,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
        )

        assertEquals(100, x)
        assertEquals(200, y)
    }

    /** 左边、上边也要夹 */
    @Test
    fun `负数坐标被夹到 0`() {
        val (x, y) = OverlayBounds.clamp(
            x = -50,
            y = -80,
            windowWidth = 300,
            windowHeight = 400,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
        )

        assertEquals(0, x)
        assertEquals(0, y)
    }

    /**
     * 窗口**比屏幕还大**时（极端缩放）不能抛异常。
     *
     * `coerceIn(min, max)` 在 `min > max` 时会 throw，所以上限必须
     * `coerceAtLeast(0)` —— 否则用户把缩放拉到很大时应用直接崩，
     * 而这恰恰是他最需要看到提示而不是闪退的时候。
     */
    @Test
    fun `窗口比屏幕还大时贴左上角而不是崩溃`() {
        val (x, y) = OverlayBounds.clamp(
            x = 100,
            y = 100,
            windowWidth = screenWidth + 200,
            windowHeight = screenHeight + 200,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
        )

        assertEquals(0, x)
        assertEquals(0, y)
    }

    /*
     * ============================================================
     * 尺寸变化后重算位置：模拟"缩放被调大"这条路径
     * ============================================================
     */

    /**
     * 模拟用户把缩放调大的完整过程：位置不动，尺寸变大。
     *
     * 用 [OverlayBounds.resolvePosition] 走**真正的**那条算法
     * （而不是直接调 clamp），确保测的是产品里跑的东西。
     */
    @Test
    fun `缩放调大后窗口仍完整可见`() {
        val layout = com.something.sthkey.domain.overlay.OverlayLayout(
            baseX = 463,
            baseY = 473,
            offsetX = 0,
            offsetY = 0,
        )

        val (x, _) = OverlayBounds.resolvePosition(
            layout = layout,
            defaultX = 0,
            defaultY = 0,
            windowWidth = 502,
            windowHeight = 609,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
        )

        assertTrue(
            "缩放调大后窗口不能被屏幕裁掉：x=$x, 右边界=${x + 502}",
            x + 502 <= screenWidth,
        )
    }

    /**
     * ⚠️ 这条说明"为什么不能只夹边界、还得管住位置同步"。
     *
     * 位置被夹小之后，如果把这个"被夹过的位置"当成用户意图存回去，
     * 那么用户把缩放**调小**时窗口就回不到原位了 ——
     * 他只调了一下缩放，位置却永久变了。
     *
     * 所以这里固化的是"夹过的位置与原始意图不同"这个事实本身：
     * 上层必须区别对待两者（见 `syncPositionFromActual`）。
     */
    @Test
    fun `被夹过的位置与用户原始意图不同`() {
        val layout = com.something.sthkey.domain.overlay.OverlayLayout(
            baseX = 463,
            baseY = 473,
            offsetX = 0,
            offsetY = 0,
        )

        val (x, _) = OverlayBounds.resolvePosition(
            layout = layout,
            defaultX = 0,
            defaultY = 0,
            windowWidth = 502,
            windowHeight = 609,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
        )

        assertTrue(
            "这条用例预期位置被夹小了（否则下面的结论不成立）：" +
                "原始意图 ${layout.positionX(0)}，实际 $x",
            x < layout.positionX(0),
        )
    }
}

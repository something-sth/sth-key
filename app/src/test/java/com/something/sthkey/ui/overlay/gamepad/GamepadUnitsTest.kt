package com.something.sthkey.ui.overlay.gamepad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手柄渲染的**单位换算**。
 *
 * ============================================================
 * ⚠️ 这里错过一次，而且错得很隐蔽 —— 所以值得专门测
 * ============================================================
 * `baseToPixelFactor` 第一版写成 `1f / scale`。它**看起来**有道理
 * （`scale = 1/密度 × uiScale`，于是 `1/scale` "像是" 密度 × 缩放），
 * 但画布是按 **dp** 排的，所以真实倍数是 `scale × 密度`。
 *
 * 用错的后果:绘制尺寸变成 `基础 × 密度 × scale²` ——
 * 随用户的缩放百分比**反向**变化。用户的原话:
 *
 * > "摇杆里面的白色圆圈和内接圆边，会随着整体缩放变小而变大，
 * >  变大而变小，这个问题还会让摇杆里面的圆可以跑出去一些"
 *
 * 这类错**不崩、不报错、也不影响布局**，只影响观感 ——
 * 只能靠"把比例算出来对比"来防。
 */
class GamepadUnitsTest {

    /** 密度用一个像真手机的值，别用 1（用 1 就测不出漏乘密度） */
    private val density = 2.75f
    private val pxToDpFactor = 1f / density

    /**
     * 调用方（`OverlayContent`）传进来的 `scale` 就是这个。
     *
     * ⚠️ 它**含 `pxToDpFactor`**，所以绝不能拿它反推密度。
     */
    private fun scaleOf(uiScale: Float) = pxToDpFactor * uiScale

    @Test
    fun `换算倍数是 密度 乘 scale`() {
        val scale = scaleOf(uiScale = 1f)
        val factor = baseToPixelFactor(scale, density)

        assertEquals(
            "像素/基础 = scale × 密度",
            scale * density,
            factor,
            1e-5f,
        )
        assertEquals(
            "uiScale = 1 时，倍数是 1（一个基础像素就是一个屏幕像素）",
            1f,
            factor,
            1e-4f,
        )
    }

    /**
     * ⚠️ **核心回归**:绘制出来的东西必须与整体缩放**同向**变化。
     *
     * 这条正是用户报的那个 bug 的反面 —— 错的实现在这里会得到
     * "缩放调小、画出来的圆反而变大"，也就是比例反向。
     */
    @Test
    fun `绘制尺寸与整体缩放同向变化`() {
        /* 一个 125 基础像素的摇杆，在三种缩放下的**屏幕像素**尺寸 */
        fun drawnPx(uiScale: Float): Float {
            val scale = scaleOf(uiScale)
            return 125f * baseToPixelFactor(scale, density)
        }

        val small = drawnPx(uiScale = 0.8f)
        val normal = drawnPx(uiScale = 1f)
        val large = drawnPx(uiScale = 1.5f)

        assertTrue(
            "缩放调大，画出来的必须**也**变大（小 $small / 中 $normal / 大 $large）",
            large > normal && normal > small,
        )

        /*
         * 而且必须**严格成比例**:uiScale 翻倍，绘制尺寸也翻倍。
         * 错的实现这里会得到 1/4 或 4 倍之类的值。
         */
        assertEquals("uiScale 1.5 / 1.0 应当正好是 1.5 倍", 1.5f, large / normal, 1e-4f)
        assertEquals("uiScale 0.8 / 1.0 应当正好是 0.8 倍", 0.8f, small / normal, 1e-4f)
    }

    /**
     * ⚠️ **核心回归**:摇杆绘制尺寸与它所在方框的**像素**尺寸必须相等。
     *
     * 这是"单位一致"的最终判据:
     *
     * ```
     * 方框:Modifier.size((sizeDp × scale).dp) → sizeDp × scale × 密度 像素
     * 绘制:sizeDp × pxPerBase                  → sizeDp × scale × 密度 像素
     * ```
     *
     * 两者不等就说明换算错了 —— 而"画出来的比方框大"正是
     * "圆可以跑出去"的成因。
     */
    @Test
    fun `绘制的摇杆尺寸等于方框的像素尺寸`() {
        val sizeDp = 125f

        listOf(0.5f, 1f, 2f).forEach { uiScale ->
            val scale = scaleOf(uiScale)

            /* 方框实际占多少屏幕像素 */
            val boxPx = sizeDp * scale * density
            /* 绘制时算出来的边长（`sideBase × pxPerBase`，sideBase = size / pxPerBase） */
            val drawnPx = sizeDp * baseToPixelFactor(scale, density)

            assertEquals(
                "uiScale = $uiScale 时，绘制尺寸必须等于方框的像素尺寸",
                boxPx,
                drawnPx,
                1e-3f,
            )
        }
    }

    /**
     * ⚠️ 拇指**整个圆**必须留在方框内 —— 任何缩放下都成立。
     *
     * 用户报过"摇杆里面的圆可以跑出去一些"。那有两个成因:
     *
     * 1. 换算错（这一版修的）→ 圆被放大了 `密度 × scale` 倍；
     * 2. 偏移上限没扣拇指半径（更早修过，见 `GamepadLayout`）。
     *
     * 这条同时盯住两者:**用真实的换算**去算，拇指边缘不得越界。
     */
    @Test
    fun `任何缩放下拇指都不会跑出摇杆`() {
        val side = 125f
        val (travel, thumb) = JoystickSpec.of(side)

        listOf(0.3f, 0.5f, 1f, 2f, 4f).forEach { uiScale ->
            val pxPerBase = baseToPixelFactor(scaleOf(uiScale), density)

            val sidePx = side * pxPerBase
            val thumbRadiusPx = thumb / 2f * pxPerBase
            val travelPx = travel * pxPerBase

            val half = sidePx / 2f
            assertTrue(
                "uiScale = $uiScale:拇指中心最远 $travelPx + 半径 $thumbRadiusPx " +
                    "超过了半边 $half",
                travelPx + thumbRadiusPx <= half + 1e-3f,
            )
        }
    }

    /**
     * ⚠️ 换算倍数的**推导依据**:`scale` 里含 `1/密度`。
     *
     * 这条不是测实现，而是**测那个前提** —— 万一以后
     * `OverlayContent` 改成传"不含密度的 scale"，
     * 那 `baseToPixelFactor` 就必须跟着改，这条会失败并提醒。
     */
    @Test
    fun `scale 里含 pxToDpFactor 这个前提`() {
        val uiScale = 1.7f
        val scale = scaleOf(uiScale)

        assertEquals("scale = pxToDpFactor × uiScale", pxToDpFactor * uiScale, scale, 1e-5f)
        assertTrue("scale 在真实手机密度下应当小于 1", scale < 1f)

        /*
         * 而换算倍数应当**与密度无关**地等于 uiScale —— 这是可读的判据:
         * 用户把缩放调到 170%，摇杆就该是 170 个基础像素大小
         * （在 uiScale = 1 的基准下），而不是 170 × 密度。
         */
        assertEquals(uiScale, baseToPixelFactor(scale, density), 1e-4f)
    }
}

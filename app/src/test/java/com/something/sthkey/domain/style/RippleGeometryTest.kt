package com.something.sthkey.domain.style

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「扩散 / 收缩」动画的几何。
 *
 * ============================================================
 * 这个测试防的是**用户肉眼才能发现的那类问题**
 * ============================================================
 * 用户的原话:"在圆角矩形上（非正方形组件），扩散动画扩散完成时，
 * 扩散出的最终图形，会超出该组件边框，我看上去好像是个矩形，
 * 但是我仔细看了看它的边还有点弯"。
 *
 * 那是"用一个圆去盖圆角矩形"的必然结果 —— 而这种错**不会崩、不报错、
 * 也过编译**，只能靠看。所以把它变成纯函数 + 断言:
 *
 * 1. **永不超出组件** —— 这是用户报的那条；
 * 2. **结束时精确重合** —— 否则按下去盖不满，看起来"没按实"；
 * 3. **全程与组件同比例** —— 否则非正方形下形状会走样。
 */
class RippleGeometryTest {

    /* ============================================================
     * 起点与终点
     * ============================================================ */

    @Test
    fun `进度为 0 时不画`() {
        val (sx, sy) = RippleGeometry.rippleScale(0f)

        assertEquals("进度 0 时不该有任何色块", 0f, sx, 0.0001f)
        assertEquals(0f, sy, 0.0001f)
    }

    @Test
    fun `进度为 1 时正好与组件重合`() {
        val (sx, sy) = RippleGeometry.rippleScale(1f)

        /*
         * ⚠️ 必须**正好 1**，不能是 0.99 或 1.01:
         * - 小于 1 → 按到底还差一圈没盖上（看起来"没按实"）；
         * - 大于 1 → 超出组件（虽然外层有 clip 挡着，但那说明算错了）。
         */
        assertEquals("结束时宽应当正好等于组件的宽", 1f, sx, 0.0001f)
        assertEquals("结束时高应当正好等于组件的高", 1f, sy, 0.0001f)
    }

    /* ============================================================
     * ⚠️ 核心:全程不超出组件（用户报的那条）
     * ============================================================ */

    @Test
    fun `全程都不超出组件`() {
        var t = 0f
        while (t <= 1f) {
            val (sx, sy) = RippleGeometry.rippleScale(t)

            assertTrue("进度 $t 时宽度不该超出组件（$sx）", sx <= 1f + 0.0001f)
            assertTrue("进度 $t 时高度不该超出组件（$sy）", sy <= 1f + 0.0001f)
            assertTrue("进度 $t 时不该是负的（$sx）", sx >= 0f)

            t += 0.02f
        }
    }

    /**
     * ⚠️ 超范围的进度也要夹住。
     *
     * 补间动画在极端掉帧时可能给出 >1 的值，那时色块会**大于组件本身** ——
     * 虽然外层 `clip(shape)` 会把它裁掉，但**裁掉的前提是那个 clip 在**。
     * 少了它就会看到"按下去的瞬间闪出一个更大的方块"。
     */
    @Test
    fun `超出范围的进度被夹住`() {
        val (over, _) = RippleGeometry.rippleScale(1.8f)
        assertEquals("progress > 1 应当夹到 1", 1f, over, 0.0001f)

        val (negative, _) = RippleGeometry.rippleScale(-0.5f)
        assertEquals("progress < 0 应当当成 0（不画）", 0f, negative, 0.0001f)
    }

    /* ============================================================
     * 形状与比例
     * ============================================================ */

    @Test
    fun `起点不为 0（否则画不出来）`() {
        val (sx, _) = RippleGeometry.rippleScale(0.001f)

        assertTrue(
            "起点应当是个能画出来的小值（现在 $sx）—— 缩到 0 宽高都是 0，" +
                "某些平台上 0 尺寸绘制会走异常路径",
            sx > 0f,
        )
        assertEquals(
            "起点比例与 iOS 那边的 CGAffineTransformMakeScale(0.1, 0.1) 一致",
            RippleGeometry.START_SCALE,
            sx,
            0.02f,
        )
    }

    @Test
    fun `进度越大色块越大`() {
        val samples = listOf(0.1f, 0.3f, 0.5f, 0.7f, 0.9f, 1f)
            .map { RippleGeometry.rippleScale(it).first }

        samples.zipWithNext().forEach { (a, b) ->
            assertTrue("进度变大时色块必须随之变大（$a → $b）", b > a)
        }
    }

    /**
     * ⚠️ 两个方向的倍数**必须相同**。
     *
     * 不同的话色块的比例会与组件不一致 —— 在非正方形组件上
     * （例如 260×55 的空格）看起来就是"横向拉长的一个东西"，
     * 与"同一个形状在放大"完全不是一回事。
     */
    @Test
    fun `两个方向的缩放倍数相同`() {
        listOf(0.1f, 0.5f, 0.9f, 1f).forEach { t ->
            val (sx, sy) = RippleGeometry.rippleScale(t)
            assertEquals("进度 $t 时两个方向的倍数必须相同", sx, sy, 0.0001f)
        }
    }

    /* ============================================================
     * 圆角
     * ============================================================ */

    /**
     * ⚠️ 圆角必须**跟着缩**。
     *
     * 只缩宽高、圆角保持不变的话，小色块会变成"圆角大到像个胶囊"，
     * 放大过程看起来是"胶囊长成方角"，而不是"同一个形状在变大"。
     */
    @Test
    fun `圆角按同一个比例缩`() {
        assertEquals(
            "圆角 8、比例 0.5 时应当是 4",
            4f,
            RippleGeometry.rippleCornerRadius(componentRadius = 8f, scale = 0.5f),
            0.0001f,
        )

        assertEquals(
            "比例 1 时圆角应当原样不变（否则结束时与组件形状不一致）",
            8f,
            RippleGeometry.rippleCornerRadius(componentRadius = 8f, scale = 1f),
            0.0001f,
        )
    }

    @Test
    fun `圆角不会是负数`() {
        assertEquals(
            0f,
            RippleGeometry.rippleCornerRadius(componentRadius = 8f, scale = 0f),
            0.0001f,
        )
    }
}

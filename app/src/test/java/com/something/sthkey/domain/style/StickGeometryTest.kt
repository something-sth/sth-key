package com.something.sthkey.domain.style

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 摇杆几何。
 *
 * ============================================================
 * 这些测试在防什么
 * ============================================================
 * 摇杆最难的一点是**映射**:输入向量该画到圆里的哪个点。
 * 写错的后果全是"看起来很玄学"的手感问题:
 *
 * - 斜推 45° 时拇指到不了外圈的角（用了纯线性映射）；
 * - 小幅度移动时方向跳到最近的轴（用了纯方形映射）；
 * - 推到底拇指还差一截（死区没重新拉满）；
 * - 往上推拇指往下走（Y 轴忘了取反）。
 *
 * 前三条都能用数值钉死，所以这里把**边界值**（0.6 / 0.9 / 45°）
 * 全部断言上 —— 那些正是"改坏了也看不出来"的地方。
 */
class StickGeometryTest {

    private val r = 100f

    /* ============================================================
     * 基本方向与长度
     * ============================================================ */

    @Test
    fun `中心不动`() {
        val (dx, dy) = StickGeometry.thumbOffset(0f, 0f, r)
        assertEquals(0f, dx, 0.001f)
        assertEquals(0f, dy, 0.001f)
    }

    @Test
    fun `正右推到底`() {
        val (dx, dy) = StickGeometry.thumbOffset(1f, 0f, r)
        assertEquals("向右应当走满", r, dx, 0.001f)
        assertEquals("不该有竖直分量", 0f, dy, 0.001f)
    }

    @Test
    fun `正上推到底`() {
        val (dx, dy) = StickGeometry.thumbOffset(0f, 1f, r)
        assertEquals(0f, dx, 0.001f)
        /* ⚠️ 输入与输出都是"Y 向上为正" —— 取反是渲染层的事 */
        assertEquals("向上应当走满（Y 向上为正）", r, dy, 0.001f)
    }

    /**
     * ⚠️ **45° 斜推必须能到外圈的角**（长度 = `r * √2`）。
     *
     * 这是"线性/方形混合映射"存在的全部理由。纯线性映射下
     * 斜推到底只会到 `r`（圆上），拇指**永远碰不到方框的角** ——
     * 而真摇杆的可动范围是一个方形。
     */
    @Test
    fun `斜推四十五度能到外圈的角`() {
        val d = 1f / kotlin.math.sqrt(2f)
        val (dx, dy) = StickGeometry.thumbOffset(d, d, r)

        assertEquals("斜推 45° 应当刚好落在方框的角上", r, dx, 0.01f)
        assertEquals(r, dy, 0.01f)
        assertEquals(
            "长度应当是 r*√2",
            r * kotlin.math.sqrt(2f),
            hypot(dx, dy),
            0.05f,
        )
    }

    /**
     * ⚠️ 小幅度移动要用**线性**映射 —— 否则方向会被量化。
     *
     * 长度 0.3 在混合区间（0.6）以下，应当完全是线性的:
     * `距离 = 0.3 * r`，而方形映射会给出 `0.3 * r / 0.707 ≈ 0.42 * r`。
     */
    @Test
    fun `小幅度移动是线性的`() {
        val d = 0.3f / kotlin.math.sqrt(2f)
        val (dx, dy) = StickGeometry.thumbOffset(d, d, r)

        assertEquals(
            "长度 0.3 时应当是线性的（0.3r），不该被方形映射放大",
            0.3f * r,
            hypot(dx, dy),
            0.5f,
        )
    }

    /* ============================================================
     * 混合区间的边界
     * ============================================================ */

    @Test
    fun `混合起点之前完全是线性`() {
        /* 长度 0.6 正好是 BLEND_START，此时 blend = 0 → 纯线性 */
        val d = 0.6f / kotlin.math.sqrt(2f)
        val (dx, dy) = StickGeometry.thumbOffset(d, d, r)

        assertEquals("在混合起点上应当还是线性的（0.6r）", 0.6f * r, hypot(dx, dy), 0.5f)
    }

    @Test
    fun `混合终点之后完全是方形`() {
        /* 长度 1.0 超过 BLEND_END，blend = 1 → 纯方形 */
        val d = 1f / kotlin.math.sqrt(2f)
        val (dx, dy) = StickGeometry.thumbOffset(d, d, r)

        val dominant = d
        assertEquals(
            "在混合终点之后应当是方形映射（r/dominant）",
            r / dominant,
            hypot(dx, dy),
            0.5f,
        )
    }

    /**
     * 混合区间中间的值要**落在两端之间**。
     *
     * 长度 0.75 正好在中点，blend = 0.5。
     */
    @Test
    fun `混合区间中间落在两端之间`() {
        val linear = 0.75f * r
        val d = 1f / kotlin.math.sqrt(2f)
        val square = r / d

        val mid = 0.75f / kotlin.math.sqrt(2f)
        val (dx, dy) = StickGeometry.thumbOffset(mid, mid, r)
        val actual = hypot(dx, dy)

        assertTrue("应当大于纯线性的 $linear", actual > linear)
        assertTrue("应当小于纯方形的 $square", actual < square)
    }

    /**
     * ⚠️ 任意方向的偏移长度都**不该超过外接圆**（`r * √2`）。
     *
     * 超过就说明方形映射的边界算错了 —— 拇指会画到方框外面去。
     */
    @Test
    fun `任何方向都不会超出外接圆`() {
        val limit = r * kotlin.math.sqrt(2f) + 0.5f

        /* 扫一圈 360 度 */
        for (i in 0 until 360 step 5) {
            val rad = Math.toRadians(i.toDouble())
            val x = kotlin.math.cos(rad).toFloat()
            val y = kotlin.math.sin(rad).toFloat()
            val (dx, dy) = StickGeometry.thumbOffset(x, y, r)
            val length = hypot(dx, dy)

            assertTrue(
                "角度 $i° 的偏移长度 $length 超出了外接圆 $limit",
                length <= limit,
            )
        }
    }

    /** 方向不能被映射改变 —— 推右上就必须往右上 */
    @Test
    fun `方向保持不变`() {
        val cases = listOf(
            0.5f to 0.5f,
            0.2f to 0.9f,
            -0.7f to 0.3f,
            -0.4f to -0.8f,
            0.9f to -0.1f,
        )
        cases.forEach { (x, y) ->
            val (dx, dy) = StickGeometry.thumbOffset(x, y, r)
            val expected = kotlin.math.atan2(y, x)
            val actual = kotlin.math.atan2(dy, dx)
            assertEquals(
                "输入 ($x, $y) 的方向不该被改变",
                expected,
                actual,
                0.01f,
            )
        }
    }

    /* ============================================================
     * WASD / 方向键点亮
     * ============================================================ */

    @Test
    fun `阈值内不点亮`() {
        val dirs = StickGeometry.wasdDirections(0.3f, -0.3f)
        assertFalse("X 没过阈值", dirs.right)
        assertFalse("Y 没过阈值", dirs.down)
        assertFalse(dirs.any)
    }

    @Test
    fun `正好在阈值上点亮`() {
        val dirs = StickGeometry.wasdDirections(StickGeometry.WASD_THRESHOLD, 0f)
        assertTrue("达到阈值就该亮", dirs.right)
    }

    /**
     * ⚠️ 斜推要能**同时点亮两个键** —— 两个轴独立判定。
     *
     * 若实现里写成 `when` 二选一，斜推只会亮一个键，
     * 而用户按 W+D 想斜着走时会发现"只有一个键亮"。
     */
    @Test
    fun `斜推同时点亮两个键`() {
        val dirs = StickGeometry.wasdDirections(0.8f, 0.8f)
        assertTrue("右", dirs.right)
        assertTrue("上", dirs.up)
        assertFalse("不该点亮相反的键", dirs.left)
        assertFalse(dirs.down)
    }

    @Test
    fun `四个方向各自点亮`() {
        assertTrue(StickGeometry.wasdDirections(-0.9f, 0f).left)
        assertTrue(StickGeometry.wasdDirections(0.9f, 0f).right)
        assertTrue(StickGeometry.wasdDirections(0f, 0.9f).up)
        assertTrue(StickGeometry.wasdDirections(0f, -0.9f).down)
    }

    /**
     * ⚠️ 方向键用**离散判定**，不套阈值。
     *
     * 方向键的轴只报 `-1 / 0 / 1`，没有"推了一半"。
     * 用 0.35 的阈值去判它在理论上也对，但一旦某个手柄报的是
     * `-1 / 0 / 1` 之外的值（比如 `-128 / 0 / 127` 归一化后仍有偏差），
     * 就会点不亮 —— 而那是"方向键没反应"的经典成因。
     */
    @Test
    fun `方向键用离散判定`() {
        val up = StickGeometry.hatDirections(0f, 1f)
        assertTrue("推到 1 就亮", up.up)

        /* 只要不是 0 就算按下 —— 不套 0.35 的阈值 */
        val slight = StickGeometry.hatDirections(0.05f, 0f)
        assertTrue("非零即按下（不套阈值）", slight.right)
        assertFalse(slight.left)

        val idle = StickGeometry.hatDirections(0f, 0f)
        assertFalse("回中不亮", idle.any)
    }

    /** 方向键的 Y 也是"上为正"，与摇杆一致 */
    @Test
    fun `方向键Y上为正`() {
        assertTrue(StickGeometry.hatDirections(0f, 1f).up)
        assertTrue(StickGeometry.hatDirections(0f, -1f).down)
    }

    /* ============================================================
     * 退化情况
     * ============================================================ */

    /** 输入超出 ±1（理论上采集层已钳制，但渲染层不能因此崩） */
    @Test
    fun `超范围输入不会算出离谱的偏移`() {
        val (dx, dy) = StickGeometry.thumbOffset(2f, 2f, r)
        val length = hypot(dx, dy)

        assertTrue("长度应当被夹住，不该是 2 倍", length <= r * kotlin.math.sqrt(2f) + 0.5f)
        assertTrue("方向仍然正确", dx > 0f && dy > 0f)
    }

    /** 极小的非零输入不能因为除零算出 NaN / Infinity */
    @Test
    fun `极小输入不会产生 NaN`() {
        val (dx, dy) = StickGeometry.thumbOffset(1e-8f, 0f, r)
        assertFalse("dx 不该是 NaN", dx.isNaN())
        assertFalse("dy 不该是 NaN", dy.isNaN())
        assertTrue("极小输入应当几乎不动", abs(dx) < 0.01f)
    }

    @Test
    fun `负的输入方向正确`() {
        val (dx, dy) = StickGeometry.thumbOffset(-1f, -1f, r)
        assertTrue("左下", dx < 0f && dy < 0f)
    }
}

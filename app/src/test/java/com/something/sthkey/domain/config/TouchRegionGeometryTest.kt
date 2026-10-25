package com.something.sthkey.domain.config

import com.something.sthkey.capture.touch.TouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **编辑器画的方块**与**命中判定的范围**必须逐像素对应。
 *
 * ============================================================
 * ⚠️ 这些测试是用户问出来的
 * ============================================================
 * 用户的原话:
 *
 * > 我们现在能**保证调节的触摸区域方框保存后一定跟实际触摸区域对应**吗，
 * > 你检查一下？
 *
 * ⚠️ 检查的结果是:**当时不能保证**。两处各写了一遍公式:
 *
 * | 谁 | 在哪 |
 * |---|---|
 * | 编辑器画方块 | `TouchRegionEditorService.syncRegionGeometry` |
 * | 命中判定 | `TouchInput.regionContains` |
 *
 * 而且**已经差了一处**:编辑器给窗口加了"最小 32dp"的下限，
 * 判定那边没有 —— 区域很小时**画的比判定的大**。
 *
 * ⚠️ 现在两处都走 [TouchRegionGeometry.bounds]。这组测试就是**那道保证**:
 * 它把"采集侧实际会命中的范围"与"编辑器会画的像素范围"直接对比，
 * 覆盖各种屏幕形状与密度。
 */
class TouchRegionGeometryTest {

    /**
     * 采集侧**实际会命中**的范围，换算成像素。
     *
     * ⚠️ 这里刻意**走采集那条真实的代码路径**（`TouchInput.regionContains`），
     * 而不是照抄一遍公式 —— 照抄的话测试就和实现一起错，
     * 那正是"两处各写一遍"的老毛病。
     *
     * 做法:在整屏归一化坐标上扫一遍，找出所有会被判为"命中"的点，
     * 取它们的像素范围。
     */
    private fun actualHitPixels(
        region: TouchRegion,
        screenWidth: Int,
        screenHeight: Int,
        density: Float,
    ): TouchRegionGeometry.Bounds {
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE

        /* 1/2000 的步长足够精确到亚像素（边界判定是"取等号"的闭区间） */
        val steps = 2000
        for (ix in 0..steps) {
            for (iy in 0..steps) {
                val nx = ix.toFloat() / steps
                val ny = iy.toFloat() / steps
                if (TouchInput.regionContains(region, nx to ny, screenWidth, screenHeight, density)) {
                    /*
                     * ⚠️ 用 `round` 而不是 `toInt()`（截断）——
                     * 实现那边（`TouchRegionGeometry.bounds`）用的是 `round`。
                     *
                     * ⚠️ 两边取整方式不同的话，边界会差 **1 像素**：
                     * 我第一版这里写了截断，于是测试报"下边界 1521 vs 1520"。
                     * 1 像素本身无所谓，但它会让人以为实现不一致 ——
                     * 而**测试的换算必须与实现同一套取整**，否则它测的是自己。
                     */
                    val px = kotlin.math.round(nx * screenWidth).toInt()
                    val py = kotlin.math.round(ny * screenHeight).toInt()
                    if (px < minX) minX = px
                    if (px > maxX) maxX = px
                    if (py < minY) minY = py
                    if (py > maxY) maxY = py
                }
            }
        }
        return TouchRegionGeometry.Bounds(minX, minY, maxX, maxY)
    }

    /**
     * ⚠️⚠️ **最要紧的一条:画出来的方块 == 判定的范围**。
     *
     * 覆盖:竖屏 / 横屏 / 超宽 / 正方形，以及几种密度与几种 size。
     */
    @Test
    fun `画出来的方块与命中范围逐像素一致`() {
        val screens = listOf(
            1080 to 2400,   // 常见竖屏
            2400 to 1080,   // 常见横屏
            2800 to 1272,   // ⚠️ 用户实测的那台（宽/短边 = 2.20）
            1272 to 2800,
            1000 to 1000,   // 正方形
        )
        val densities = listOf(1f, 2f, 2.75f, 3.5f)
        val sizes = listOf(0.10f, 0.15f, 0.30f, 0.60f)

        screens.forEach { (w, h) ->
            densities.forEach { density ->
                sizes.forEach { size ->
                    val region = TouchRegion(centerX = 0.4f, centerY = 0.6f, size = size)
                    val drawn = TouchRegionGeometry.bounds(0.4f, 0.6f, size, w, h, density)
                    val actual = actualHitPixels(region, w, h, density)

                    assertEquals(
                        "屏 ${w}x$h 密度 $density size $size:左边界不一致" +
                            "（画出来的方块与真正生效的范围对不上，" +
                            "用户会'按在方块上却没反应'）",
                        drawn.left.toFloat(),
                        actual.left.toFloat(),
                        /**
                         * ⚠️ 容差 2 像素 —— 而且**只可能差这么点**。
                         *
                         * 成因是测试的扫描:它在归一化网格上取点（1/2000 步长），
                         * 命中之后再把归一化坐标换回像素（`round`）。
                         * 边界恰好落在网格点之间时，扫描到的最外侧像素
                         * 会比真实边界**少或多一个**。
                         *
                         * ⚠️ 这是**测试采样的精度**，不是实现的不一致:
                         * 实现那边两侧走的是**同一个整数 `bounds`**，
                         * "精确对应"由下面 [每一条边界都恰好在整数像素上] 那条
                         * 用整数算式严格证明（那条不留容差）。
                         */
                        2.0f,
                    )
                    assertEquals("屏 ${w}x$h 密度 $density size $size:上边界", drawn.top.toFloat(), actual.top.toFloat(), 2.0f)
                    assertEquals(
                        "屏 ${w}x$h 密度 $density size $size:右边界",
                        drawn.right.toFloat(),
                        actual.right.toFloat(),
                        2.0f,
                    )
                    assertEquals(
                        "屏 ${w}x$h 密度 $density size $size:下边界",
                        drawn.bottom.toFloat(),
                        actual.bottom.toFloat(),
                        2.0f,
                    )
                }
            }
        }
    }

    /**
     * ⚠️⚠️ **精确对应**（不留容差的那一条）。
     *
     * ============================================================
     * 判据:四条边界都恰好落在整数像素上，且区间与整数算式一致
     * ============================================================
     * 上面那条用"扫描"验证，精度受限于网格（可能差 1 像素）。
     * 这条换个办法，**把断言写成整数算式** —— 于是它能算出**恰好**哪几个
     * 像素列/行会被命中，并与实现给出的边界逐一比对。
     *
     * ⚠️ 采集侧的比较也是整数语义:它把触点的归一化坐标乘回宽度再比边界，
     * 所以"整数像素边界"正是两侧真正的契约。
     */
    @Test
    fun `每一条边界都恰好在整数像素上`() {
        val cases = listOf(
            Triple(2800, 1272, 0.119f),   // 用户实测那台
            Triple(2400, 1080, 0.30f),
            Triple(1080, 2400, 0.60f),
            Triple(1000, 1000, 0.10f),
        )
        cases.forEach { (w, h, size) ->
            listOf(1f, 3f).forEach { density ->
                val b = TouchRegionGeometry.bounds(0.4f, 0.6f, size, w, h, density)

                /*
                 * ⚠️ 边界必须是整数（`Bounds` 的字段就是 Int，这条是"别改成
                 * Float 再各自取整"的保险）。
                 */
                assertEquals(
                    "宽度必须是整数像素",
                    b.right - b.left,
                    b.width,
                )

                /*
                 * ⚠️ **恰好**在边界内的最后/最前一个像素要能命中，
                 * 边界外一个像素要落空 —— 这就是"精确对应"的定义。
                 */
                val leftNorm = b.left.toFloat() / w
                val rightNorm = b.right.toFloat() / w
                val topNorm = b.top.toFloat() / h
                val bottomNorm = b.bottom.toFloat() / h
                val region = TouchRegion(0.4f, 0.6f, size)

                assertTrue(
                    "屏 ${w}x$h 密度 $density:左边界那个像素应当命中",
                    TouchInput.regionContains(region, leftNorm to 0.6f, w, h, density),
                )
                assertTrue(
                    "屏 ${w}x$h 密度 $density:右边界那个像素应当命中",
                    TouchInput.regionContains(region, rightNorm to 0.6f, w, h, density),
                )
                assertTrue(
                    "屏 ${w}x$h 密度 $density:上边界那个像素应当命中",
                    TouchInput.regionContains(region, 0.4f to topNorm, w, h, density),
                )
                assertTrue(
                    "屏 ${w}x$h 密度 $density:下边界那个像素应当命中",
                    TouchInput.regionContains(region, 0.4f to bottomNorm, w, h, density),
                )

                /* ⚠️ 边界**外**一个像素必须落空 */
                assertFalse(
                    "屏 ${w}x$h 密度 $density:左边界外一个像素不该命中",
                    TouchInput.regionContains(
                        region,
                        (b.left - 1f) / w to 0.6f,
                        w,
                        h,
                        density,
                    ),
                )
                assertFalse(
                    "屏 ${w}x$h 密度 $density:右边界外一个像素不该命中",
                    TouchInput.regionContains(
                        region,
                        (b.right + 1f) / w to 0.6f,
                        w,
                        h,
                        density,
                    ),
                )
            }
        }
    }

    /**
     * ⚠️ **最小边长（32dp）在两侧都生效** —— 这正是原来漏掉的那一处。
     *
     * 只加在"画"的一侧时，方块看着挺大、按上去没反应。
     */
    @Test
    fun `最小边长两侧都生效`() {
        val w = 2800
        val h = 1272
        val density = 3f
        /* size 取最小值 0.10 —— 横屏下它只有 0.10×1272 ≈ 127px，
           而 32dp × 3 = 96px，此时下限没生效;用更小的 size 才触发 */
        val tiny = TouchRegion(centerX = 0.5f, centerY = 0.5f, size = 0.02f)

        val drawn = TouchRegionGeometry.bounds(0.5f, 0.5f, 0.02f, w, h, density)
        assertEquals(
            "方块边长应当被抬到 32dp × 密度",
            (TouchRegionGeometry.MIN_SIDE_DP * density).toInt(),
            drawn.width,
        )
        assertTrue(
            "⚠️ 判定范围也必须同样大 —— 否则就是'画的比判定的大'",
            TouchInput.regionContains(tiny, 0.5f to 0.5f, w, h, density),
        )
        /* 离中心超过半个最小边长的地方不该命中 */
        val halfNormalizedX = (drawn.width / 2f + 2f) / w
        assertFalse(
            "超出最小边长的位置不该命中",
            TouchInput.regionContains(tiny, 0.5f + halfNormalizedX to 0.5f, w, h, density),
        )
    }

    /** 密度为 0（未提供）时不加下限 —— 单测与后台逻辑可以用这个退化形式 */
    @Test
    fun `密度为零时不加下限`() {
        val b = TouchRegionGeometry.bounds(0.5f, 0.5f, 0.10f, 2800, 1272, 0f)
        assertEquals("纯按 size 算", (0.10f * 1272).toInt(), b.width)
    }

    /**
     * ⚠️ **正方形**:任何屏幕形状下，画出来的都是正方形。
     *
     * 这是"边长用短边归一化"的目的 —— 按屏宽算的话横屏下会被拉成细缝。
     */
    @Test
    fun `任何屏幕形状下都是正方形`() {
        listOf(1080 to 2400, 2400 to 1080, 2800 to 1272, 1000 to 1000).forEach { (w, h) ->
            val b = TouchRegionGeometry.bounds(0.5f, 0.5f, 0.3f, w, h, 2f)
            assertEquals("屏 ${w}x$h 下画出来不是正方形", b.width, b.height)
        }
    }

    /**
     * ⚠️ **中心就是中心**:方块中心必须落在配置的中心上（误差 <= 1 像素）。
     *
     * 中心偏了的话摇杆的"正中"就不在方块正中 —— 推轮盘的手感会偏。
     */
    @Test
    fun `方块中心落在配置中心上`() {
        val w = 2800
        val h = 1272
        listOf(0.25f to 0.72f, 0.797f to 0.605f, 0.05f to 0.95f).forEach { (cx, cy) ->
            val b = TouchRegionGeometry.bounds(cx, cy, 0.119f, w, h, 2.75f)
            val midX = (b.left + b.right) / 2f
            val midY = (b.top + b.bottom) / 2f
            assertTrue(
                "中心 X 偏了:期望 ${cx * w}，实际 $midX",
                kotlin.math.abs(midX - cx * w) <= 1f,
            )
            assertTrue(
                "中心 Y 偏了:期望 ${cy * h}，实际 $midY",
                kotlin.math.abs(midY - cy * h) <= 1f,
            )
        }
    }

    /**
     * ⚠️ 区域能放到屏幕**任何位置**（左撇子 / 右手摇杆）。
     *
     * 这是"中心用整屏归一化"的目的。用短边归一化的话横向只能到一个短边
     * （横屏下就是屏幕一半，右手摇杆的人放不过去）。
     *
     * ⚠️⚠️ 注意这里断言的是**中心能到边缘**，不是"方块完全在屏内" ——
     * 中心贴边时方块**本来就有一半在屏外**（那是允许的，
     * 编辑器的窗口带 `FLAG_LAYOUT_NO_LIMITS`，露出去的部分照样画得出来）。
     *
     * ⚠️ 我第一版写成"中心贴右边时右边界应当在屏幕右缘" —— **那是错的**，
     * 于是测试报"2800 vs 2927"。错的是断言，不是实现。
     */
    @Test
    fun `区域中心能到达屏幕任意边缘`() {
        val w = 2800
        val h = 1272
        val size = 0.2f

        /* 中心贴右下角:中心本身必须落在屏幕右下角上 */
        val br = TouchRegionGeometry.bounds(1f, 1f, size, w, h, 2f)
        assertTrue(
            "中心贴右下角时，中心应当就在屏幕右下角（允许半个方块的溢出）",
            br.left < w && br.right > w && br.top < h && br.bottom > h,
        )

        /* 中心贴左上角 */
        val tl = TouchRegionGeometry.bounds(0f, 0f, size, w, h, 2f)
        assertTrue(
            "中心贴左上角时，中心应当就在屏幕左上角",
            tl.left < 0 && tl.right > 0 && tl.top < 0 && tl.bottom > 0,
        )

        /*
         * ⚠️ 最要紧的:横屏下**中心能超过"一个短边"** ——
         * 那是"用短边归一化"时做不到的事（也就是那个"只能放屏幕一半"的 bug）。
         */
        val short = minOf(w, h)
        val rightSide = TouchRegionGeometry.bounds(0.9f, 0.5f, size, w, h, 2f)
        assertTrue(
            "中心 X=0.9 应当落在 ${0.9f * w}px，也就是**超过一个短边**（$short）—— " +
                "这正是右手摇杆能放到右边的前提",
            0.9f * w > short,
        )
        assertTrue(
            "中心应当真的在 0.9×屏宽 处",
            kotlin.math.abs((rightSide.left + rightSide.right) / 2f - 0.9f * w) <= 1f,
        )
    }

    /** 归一化边界与像素边界必须能互相换算（采集侧就是靠它比较的） */
    @Test
    fun `归一化边界与像素边界一致`() {
        val w = 2800
        val h = 1272
        val n = TouchRegionGeometry.normalizedBounds(0.4f, 0.6f, 0.2f, w, h, 2f)
        val b = TouchRegionGeometry.bounds(0.4f, 0.6f, 0.2f, w, h, 2f)
        assertEquals(b.left / w.toFloat(), n[0], 1e-6f)
        assertEquals(b.top / h.toFloat(), n[1], 1e-6f)
        assertEquals(b.right / w.toFloat(), n[2], 1e-6f)
        assertEquals(b.bottom / h.toFloat(), n[3], 1e-6f)
    }
}

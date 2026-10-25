package com.something.sthkey.capture.touch

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 触摸坐标的屏幕旋转换算:[TouchRotation]。
 *
 * ============================================================
 * ⚠️ 这里保的是什么
 * ============================================================
 * 映射方向**抄的是 Axon**（它在真机上验证过），而我**自己推导的结论与它相反**。
 * 我没有真机可验，所以:
 *
 * 1. 用**数学性质**把"四条映射"钉死（它们必须真的是旋转，不能是镜像）；
 * 2. 用 [TouchRotation.isConsistent] 在运行时自查方向。
 *
 * ⚠️ 数学性质这一层是**方向无关**的:不管最后选哪一套，
 * 它都必须满足"转四次回原位""转两次等于 180°""可逆"。
 * 所以这些断言能保住"别写错"，但**保不住"方向对不对"** ——
 * 后者只能靠真机，见 `isConsistent` 那条。
 */
class TouchRotationTest {

    private val eps = 1e-4f

    // ---------- 基本映射 ----------

    @Test
    fun `零度不换算`() {
        assertEquals(0.3f to 0.7f, TouchRotation.map(0.3f, 0.7f, Surface.ROTATION_0))
    }

    /**
     * ⚠️ 未知旋转值走"不换算"而不是硬套某个角度。
     *
     * 系统以后加了新的 rotation 常量时，不换算顶多是方向不对，
     * 而硬套一个角度会**镜像**坐标 —— 后者更难查。
     */
    @Test
    fun `未知旋转不换算`() {
        assertEquals(0.3f to 0.7f, TouchRotation.map(0.3f, 0.7f, 99))
        assertEquals(0.3f to 0.7f, TouchRotation.map(0.3f, 0.7f, -1))
    }

    @Test
    fun `四角映射到四角`() {
        /* 不管哪个旋转，四个角都必须还在四个角上（不能跑到边中间） */
        val corners = listOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f)
        listOf(
            Surface.ROTATION_0,
            Surface.ROTATION_90,
            Surface.ROTATION_180,
            Surface.ROTATION_270,
        ).forEach { rotation ->
            corners.forEach { (x, y) ->
                val (nx, ny) = TouchRotation.map(x, y, rotation)
                assertTrue(
                    "旋转 $rotation 下 ($x,$y) 映射到 ($nx,$ny)，坐标跑出 0..1 了",
                    nx in -eps..(1f + eps) && ny in -eps..(1f + eps),
                )
                assertTrue(
                    "旋转 $rotation 下角点必须映射到角点（0 或 1），实际 ($nx,$ny)",
                    (near(nx, 0f) || near(nx, 1f)) && (near(ny, 0f) || near(ny, 1f)),
                )
            }
        }
    }

    /**
     * ⚠️⚠️ 最要紧的一条:四次 90° 必须回到原位。
     *
     * 这一条能把"某个角度写成了镜像"抓出来 ——
     * 镜像变换转四次**不会**回到原位（它转两次就回去了）。
     * 所以它是"这四条真的是旋转、不是镜像"的证据。
     */
    @Test
    fun `转四次回原位`() {
        val probes = listOf(0.2f to 0.3f, 0.8f to 0.1f, 0.5f to 0.9f, 0.11f to 0.77f)
        probes.forEach { (x, y) ->
            var (cx, cy) = x to y
            repeat(4) {
                val (nx, ny) = TouchRotation.map(cx, cy, Surface.ROTATION_90)
                cx = nx
                cy = ny
            }
            assertEquals("转四次之后 X 应该回到 $x", x, cx, eps)
            assertEquals("转四次之后 Y 应该回到 $y", y, cy, eps)
        }
    }

    /** 180° 等于转两次 90° */
    @Test
    fun `一百八十度等于转两次九十度`() {
        val probes = listOf(0.2f to 0.3f, 0.8f to 0.1f, 0.5f to 0.9f)
        probes.forEach { (x, y) ->
            val (ax, ay) = TouchRotation.map(x, y, Surface.ROTATION_180)
            val (bx0, by0) = TouchRotation.map(x, y, Surface.ROTATION_90)
            val (bx, by) = TouchRotation.map(bx0, by0, Surface.ROTATION_90)
            assertEquals("X 对不上", ax, bx, eps)
            assertEquals("Y 对不上", ay, by, eps)
        }
    }

    /** 270° 等于转三次 90°（也等于反向转一次） */
    @Test
    fun `两百七十度等于转三次九十度`() {
        val probes = listOf(0.2f to 0.3f, 0.8f to 0.1f, 0.5f to 0.9f)
        probes.forEach { (x, y) ->
            val (ax, ay) = TouchRotation.map(x, y, Surface.ROTATION_270)
            var (cx, cy) = x to y
            repeat(3) {
                val (nx, ny) = TouchRotation.map(cx, cy, Surface.ROTATION_90)
                cx = nx
                cy = ny
            }
            assertEquals("X 对不上", ax, cx, eps)
            assertEquals("Y 对不上", ay, cy, eps)
        }
    }

    /**
     * ⚠️ **非平凡**:90° 必须真的动，不能因为"懒得写"而返回原值。
     *
     * 一条能抓出"复制粘贴时忘了改"的断言。
     */
    @Test
    fun `九十度必须真的改变坐标`() {
        val (x, y) = 0.25f to 0.75f
        val (nx, ny) = TouchRotation.map(x, y, Surface.ROTATION_90)
        assertNotEquals("90° 不该原样返回", x to y, nx to ny)
    }

    /** `mapPoints` 与逐点 `map` 一致；0° 时**不重新构造列表**（省一次分配） */
    @Test
    fun `批量换算与逐点一致`() {
        val points = listOf(
            TouchNativeProtocol.Point(0, 0.2f, 0.3f),
            TouchNativeProtocol.Point(1, 0.7f, 0.9f),
        )
        val batch = TouchRotation.mapPoints(points, Surface.ROTATION_90)
        assertEquals(points.size, batch.size)
        points.forEachIndexed { i, p ->
            val (nx, ny) = TouchRotation.map(p.x, p.y, Surface.ROTATION_90)
            assertEquals("第 $i 个点 X 对不上", nx, batch[i].x, eps)
            assertEquals("第 $i 个点 Y 对不上", ny, batch[i].y, eps)
            assertEquals("id 必须保留", p.id, batch[i].id)
        }

        /* 0° 时应当原样返回（同一个列表实例，不额外分配） */
        assertTrue(
            "0° 时不该重新构造列表",
            TouchRotation.mapPoints(points, Surface.ROTATION_0) === points,
        )
    }

    // ---------- 方向自检 ----------

    /**
     * ⚠️ 自检的判据:90°/270° 应该把"横竖"互换，0°/180° 不该。
     *
     * | 自然屏 | 当前屏 | 正确 rotation |
     * |---|---|---|
     * | 竖 | 竖 | 0 或 180 |
     * | 竖 | 横 | 90 或 270 |
     */
    @Test
    fun `自检认得出一致的组合`() {
        /* 自然竖屏（1080×2400） */
        val nw = 1080
        val nh = 2400

        /* 当前也是竖屏（1080×2400）→ 0/180 才对 */
        assertTrue(TouchRotation.isConsistent(Surface.ROTATION_0, 1080, 2400, nw, nh))
        assertTrue(TouchRotation.isConsistent(Surface.ROTATION_180, 1080, 2400, nw, nh))
        /* 同一个竖屏屏幕却报 90° → 不自洽 */
        assertFalse(TouchRotation.isConsistent(Surface.ROTATION_90, 1080, 2400, nw, nh))
        assertFalse(TouchRotation.isConsistent(Surface.ROTATION_270, 1080, 2400, nw, nh))

        /* 当前是横屏（2400×1080）→ 90/270 才对 */
        assertTrue(TouchRotation.isConsistent(Surface.ROTATION_90, 2400, 1080, nw, nh))
        assertTrue(TouchRotation.isConsistent(Surface.ROTATION_270, 2400, 1080, nw, nh))
        assertFalse(TouchRotation.isConsistent(Surface.ROTATION_0, 2400, 1080, nw, nh))
        assertFalse(TouchRotation.isConsistent(Surface.ROTATION_180, 2400, 1080, nw, nh))
    }

    /** 自然屏本来就是横的（平板 / 车机）时判据要反过来 */
    @Test
    fun `自然横屏也认得出来`() {
        val nw = 2400
        val nh = 1080
        /* 当前横屏 → 0/180 */
        assertTrue(TouchRotation.isConsistent(Surface.ROTATION_0, 2400, 1080, nw, nh))
        assertFalse(TouchRotation.isConsistent(Surface.ROTATION_90, 2400, 1080, nw, nh))
        /* 当前竖屏 → 90/270 */
        assertTrue(TouchRotation.isConsistent(Surface.ROTATION_90, 1080, 2400, nw, nh))
        assertFalse(TouchRotation.isConsistent(Surface.ROTATION_0, 1080, 2400, nw, nh))
    }

    /**
     * ⚠️ 判不出来时**不要报假警**:
     * 正方形屏幕（折叠屏内屏、某些模拟器）横竖一样，两种都算自洽。
     */
    @Test
    fun `正方形屏幕不报假警`() {
        assertTrue(TouchRotation.isConsistent(Surface.ROTATION_0, 1000, 1000, 1000, 1000))
        assertTrue(TouchRotation.isConsistent(Surface.ROTATION_90, 1000, 1000, 1000, 1000))
    }

    /** 尺寸缺失（拿不到自然屏信息）时不报假警 */
    @Test
    fun `尺寸缺失时不报假警`() {
        assertTrue(TouchRotation.isConsistent(Surface.ROTATION_90, 0, 0, 1080, 2400))
        assertTrue(TouchRotation.isConsistent(Surface.ROTATION_90, 2400, 1080, 0, 0))
    }

    private fun near(a: Float, b: Float) = kotlin.math.abs(a - b) < eps
}

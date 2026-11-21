package com.something.sthkey.ui.component

import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * 色环 / 方板的几何关系。
 *
 * ⚠️ 这些不是"好看不好看"的问题：绘制与命中测试共用 `ringGeometry`，
 * 一旦方板超出内径，点方板角落会被判成在拖色环（改色相而不是饱和度）；
 * 一旦环的中心线贴到画布边缘，描边会被画布裁掉一截（用户报过"顶上被吃了一点"）。
 */
class ColorPickerGeometryTest {

    @Test
    fun `方板四个角不会超出色环内径`() {
        listOf(96f, 120f, 272f, 600f, 1080f).forEach { side ->
            val g = ringGeometry(Size(side, side))
            val cornerDistance = hypot(g.squareHalf, g.squareHalf)
            assertTrue(
                "side=$side corner=$cornerDistance inner=${g.innerRadius}",
                cornerDistance <= g.innerRadius,
            )
        }
    }

    @Test
    fun `色环描边完全落在画布内`() {
        listOf(96f, 120f, 272f, 1080f).forEach { side ->
            val g = ringGeometry(Size(side, side))
            assertTrue(
                "side=$side 环外缘=${g.outerRadius} 画布=${g.canvasRadius}",
                g.outerRadius <= g.canvasRadius + 0.01f,
            )
            assertTrue("环内缘必须为正", g.innerRadius > 0f)
        }
    }

    @Test
    fun `色环往里缩了一点，不是贴着画布边`() {
        val g = ringGeometry(Size(272f, 272f))
        assertTrue(
            "环外缘 ${g.outerRadius} 应该比画布半径 ${g.canvasRadius} 小一点",
            g.outerRadius < g.canvasRadius - 1f,
        )
    }

    @Test
    fun `方板与色环之间留有间隙`() {
        val g = ringGeometry(Size(272f, 272f))
        assertTrue("squareHalf=${g.squareHalf} inner=${g.innerRadius}", g.squareHalf < g.innerRadius)
        assertTrue("方板不能是 0", g.squareHalf > 1f)
    }

    @Test
    fun `非正方形画布按短边居中`() {
        val g = ringGeometry(Size(400f, 200f))
        assertEquals(200f, g.center.x, 0.01f)
        assertEquals(100f, g.center.y, 0.01f)
        assertEquals(100f, g.canvasRadius, 0.01f)
        assertTrue("方板必须落在画布内", g.square.width <= 200f)
    }

    @Test
    fun `尺寸极小时也不会算出负数或零`() {
        val g = ringGeometry(Size(8f, 8f))
        assertTrue(g.ringWidth > 0f)
        assertTrue(g.innerRadius > 0f)
        assertTrue(g.squareHalf > 0f)
        assertTrue(g.square.width > 0f)
    }

    @Test
    fun `方板以圆心为中心`() {
        val g = ringGeometry(Size(300f, 300f))
        assertEquals(g.center.x, g.square.center.x, 0.01f)
        assertEquals(g.center.y, g.square.center.y, 0.01f)
    }
}

package com.something.sthkey.ui.overlay.touch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 区域**序号 / 配色**的规则（用户反馈"几块区域名字一样分不清"）。
 *
 * ⚠️ 这里只测纯函数:面板/方块上的绘制在 JVM 里跑不了，
 * 但"第几块显示什么字、什么颜色"是它们共用的唯一来源，钉住它就够了。
 */
class TouchRegionOrdinalTest {

    @Test
    fun `1 到 20 用带圈数字`() {
        assertEquals("①", touchRegionOrdinal(0))
        assertEquals("②", touchRegionOrdinal(1))
        assertEquals("⑩", touchRegionOrdinal(9))
        assertEquals("⑳", touchRegionOrdinal(19))
    }

    @Test
    fun `超过 20 退化成普通数字`() {
        /* ⚠️ ㉑ 之后的带圈字形字体支持不稳定，宁可显示 "21" */
        assertEquals("21", touchRegionOrdinal(20))
        assertEquals("26", touchRegionOrdinal(25))
    }

    @Test
    fun `1 到 20 的序号互不重复`() {
        val labels = (0 until 20).map { touchRegionOrdinal(it) }
        assertEquals("序号有重复:$labels", labels.size, labels.toSet().size)
    }

    @Test
    fun `颜色按列表顺序循环`() {
        assertEquals(0xFF7C4DFF.toInt(), touchRegionColor(0))
        assertNotEquals("相邻两块颜色应当不同", touchRegionColor(0), touchRegionColor(1))
        /* 调色板只有 6 个 → 第 7 块与第 1 块同色（这也是颜色只能当辅助的原因） */
        assertEquals(touchRegionColor(0), touchRegionColor(6))
    }
}

package com.something.sthkey.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * HSV 数学（色环/方板/滑杆全部依赖它）。
 *
 * 这些是纯函数，不依赖 Compose，所以能在 JVM 单测里跑。
 */
class HsvColorTest {

    @Test
    fun `纯色分量`() {
        assertHsv(0xFF0000L, 0f, 1f, 1f)
        assertHsv(0x00FF00L, 120f, 1f, 1f)
        assertHsv(0x0000FFL, 240f, 1f, 1f)
        assertHsv(0xFFFF00L, 60f, 1f, 1f)
        assertHsv(0x00FFFFL, 180f, 1f, 1f)
        assertHsv(0xFF00FFL, 300f, 1f, 1f)
    }

    @Test
    fun `黑白灰的饱和度`() {
        assertEquals(0f, rgbToHsv(0xFFFFFFL).saturation, 1e-6f)
        assertEquals(1f, rgbToHsv(0xFFFFFFL).value, 1e-6f)

        assertEquals(0f, rgbToHsv(0x000000L).saturation, 1e-6f)
        assertEquals(0f, rgbToHsv(0x000000L).value, 1e-6f)

        assertEquals(0f, rgbToHsv(0x808080L).saturation, 1e-6f)
        assertEquals(0.502f, rgbToHsv(0x808080L).value, 0.01f)
    }

    @Test
    fun `灰阶保留上一次的色相`() {
        /*
         * ⚠️ 回归点：把明度拖到 0 再拖回来，颜色不能跳成红色。
         * 组件里传的是 hsv.hue，这里钉住 rgbToHsv 的行为。
         */
        assertEquals(210f, rgbToHsv(0x000000L, previousHue = 210f).hue, 1e-6f)
        assertEquals(210f, rgbToHsv(0x808080L, previousHue = 210f).hue, 1e-6f)
        assertEquals(210f, rgbToHsv(0xFFFFFFL, previousHue = 210f).hue, 1e-6f)
    }

    @Test
    fun `HSV 转 RGB 的端点`() {
        assertEquals(0xFF0000L, hsvToRgb(Hsv(0f, 1f, 1f)))
        assertEquals(0x00FF00L, hsvToRgb(Hsv(120f, 1f, 1f)))
        assertEquals(0x0000FFL, hsvToRgb(Hsv(240f, 1f, 1f)))
        assertEquals(0xFFFFFFL, hsvToRgb(Hsv(0f, 0f, 1f)))
        assertEquals(0x000000L, hsvToRgb(Hsv(0f, 0f, 0f)))
    }

    @Test
    fun `色相越界会绕回来、分量越界会夹住`() {
        assertEquals(0xFF0000L, hsvToRgb(Hsv(360f, 1f, 1f)))
        assertEquals(0xFF0000L, hsvToRgb(Hsv(-360f, 1f, 1f)))
        assertEquals(0xFF0000L, hsvToRgb(Hsv(720f, 1f, 1f)))
        /* 饱和度/明度超范围不应产生非法通道值 */
        val clamped = hsvToRgb(Hsv(200f, 2f, 2f))
        assertTrue(clamped in 0x000000L..0xFFFFFFL)
    }

    @Test
    fun `RGB 转 HSV 的色相永远在 0 到 360`() {
        listOf(0x123456L, 0xFEDCBAL, 0x00FF7FL, 0xFF0080L).forEach { rgb ->
            val hue = rgbToHsv(rgb).hue
            assertTrue("hue=$hue for ${"%06X".format(rgb)}", hue >= 0f && hue < 360f)
        }
    }

    @Test
    fun `往返误差不超过 1 个通道`() {
        /*
         * 拖动路径会经过 HSV，允许 ±1 的取整误差；
         * 「打开→保存」不走这条路（保存直接用 rgb），所以不会漂移。
         */
        val samples = buildList {
            addAll(listOf(0x000000L, 0xFFFFFFL, 0xFF0000L, 0x00FF00L, 0x0000FFL))
            addAll(listOf(0x123456L, 0xABCDEFL, 0x808080L, 0x7F3F1FL, 0x0A0B0CL))
            var seed = 12345L
            repeat(200) {
                seed = (seed * 1103515245L + 12345L) and 0xFFFFFFL
                add(seed)
            }
        }
        samples.forEach { rgb ->
            val back = hsvToRgb(rgbToHsv(rgb))
            assertChannelNear(rgb, back, 16, "R")
            assertChannelNear(rgb, back, 8, "G")
            assertChannelNear(rgb, back, 0, "B")
        }
    }

    @Test
    fun `结果永远不含 alpha`() {
        val rgb = hsvToRgb(Hsv(30f, 0.5f, 0.5f))
        assertEquals(0L, rgb and 0xFF000000L)
    }

    private fun assertChannelNear(rgb: Long, back: Long, shift: Int, name: String) {
        val expected = (rgb shr shift) and 0xFF
        val actual = (back shr shift) and 0xFF
        assertTrue(
            "$name ${"%06X".format(rgb)}: $expected vs $actual",
            abs(expected - actual) <= 1L,
        )
    }

    private fun assertHsv(rgb: Long, hue: Float, saturation: Float, value: Float) {
        val hsv = rgbToHsv(rgb)
        assertTrue(
            "hue ${hsv.hue} != $hue for ${"%06X".format(rgb)}",
            abs(hsv.hue - hue) < 0.01f || abs(abs(hsv.hue - hue) - 360f) < 0.01f,
        )
        assertEquals(saturation, hsv.saturation, 1e-3f)
        assertEquals(value, hsv.value, 1e-3f)
    }
}

package com.something.sthkey.domain.live2d

import com.something.sthkey.domain.config.Live2DBackground
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 背景几何：窗口尺寸与预览尺寸。
 *
 * 这两处**必须同一个算法** —— 预览里"刚好铺满"，悬浮窗上却溢出去/留白，
 * 是这类功能最容易出的问题。所以断言都写成"比例关系"，而不是死记像素值。
 */
class Live2DBackgroundGeometryTest {

    @Test
    fun `关闭背景时窗口尺寸与现状一致`() {
        // 有 contentAspect：宽 = 354 × aspect，高 = 354（与 OverlayStyles 原规则一样）
        val (w, h) = Live2DBackgroundGeometry.windowBase(1.5f, 612f, 354f, Live2DBackground())
        assertEquals(531f, w, 0.01f)
        assertEquals(354f, h, 0.01f)

        // 没有 contentAspect（内置模型）：设计分辨率
        val (w2, h2) = Live2DBackgroundGeometry.windowBase(0f, 612f, 354f, Live2DBackground())
        assertEquals(612f, w2, 0.01f)
        assertEquals(354f, h2, 0.01f)
    }

    @Test
    fun `关闭背景时即使百分比不是 100 也不放大窗口`() {
        val bg = Live2DBackground(enabled = false, widthPercent = 200, heightPercent = 200)
        val (w, h) = Live2DBackgroundGeometry.windowBase(1f, 612f, 354f, bg)
        assertEquals(354f, w, 0.01f)
        assertEquals(354f, h, 0.01f)
    }

    @Test
    fun `背景大于 100 时窗口按百分比放大_两个轴独立`() {
        val wider = Live2DBackground(enabled = true, widthPercent = 150, heightPercent = 100)
        val (w, h) = Live2DBackgroundGeometry.windowBase(1f, 612f, 354f, wider)
        assertEquals(354f * 1.5f, w, 0.01f)
        assertEquals(354f, h, 0.01f)

        val taller = Live2DBackground(enabled = true, widthPercent = 100, heightPercent = 150)
        val (w2, h2) = Live2DBackgroundGeometry.windowBase(1f, 612f, 354f, taller)
        assertEquals(354f, w2, 0.01f)
        assertEquals(354f * 1.5f, h2, 0.01f)
    }

    @Test
    fun `背景小于 100 时窗口不缩（否则会把模型裁掉）`() {
        val bg = Live2DBackground(enabled = true, widthPercent = 40, heightPercent = 40)
        val (w, h) = Live2DBackgroundGeometry.windowBase(1f, 612f, 354f, bg)
        assertEquals(354f, w, 0.01f)
        assertEquals(354f, h, 0.01f)
    }

    @Test
    fun `预览_100 百分比时模型矩形就是并集矩形`() {
        val rects = Live2DBackgroundGeometry.previewRects(
            boxWidth = 200f,
            boxHeight = 200f,
            aspect = 1f,
            background = Live2DBackground(enabled = true),
        )
        assertEquals(200f, rects.modelWidth, 0.01f)
        assertEquals(200f, rects.modelHeight, 0.01f)
        assertEquals(200f, rects.bgWidth, 0.01f)
        assertEquals(200f, rects.bgHeight, 0.01f)
    }

    @Test
    fun `预览_宽 200 高 100 时背景正好铺满_模型收窄`() {
        val rects = Live2DBackgroundGeometry.previewRects(
            boxWidth = 200f,
            boxHeight = 200f,
            aspect = 1f,
            background = Live2DBackground(enabled = true, widthPercent = 200, heightPercent = 100),
        )
        // 并集比例 2:1 → 预览框里宽吃满、高一半
        assertEquals(200f, rects.bgWidth, 0.01f)
        assertEquals(100f, rects.bgHeight, 0.01f)
        // 模型 = 背景 / 2
        assertEquals(100f, rects.modelWidth, 0.01f)
        assertEquals(100f, rects.modelHeight, 0.01f)
    }

    @Test
    fun `预览_背景小于 100 时比模型小且比例正确`() {
        val rects = Live2DBackgroundGeometry.previewRects(
            boxWidth = 200f,
            boxHeight = 200f,
            aspect = 1f,
            background = Live2DBackground(enabled = true, widthPercent = 50, heightPercent = 80),
        )
        assertEquals(200f, rects.modelWidth, 0.01f)
        assertEquals(200f, rects.modelHeight, 0.01f)
        assertEquals(100f, rects.bgWidth, 0.01f)
        assertEquals(160f, rects.bgHeight, 0.01f)
    }

    @Test
    fun `预览与窗口用的是同一个百分比语义`() {
        // 随机挑几组：窗口放大倍数必须等于预览里 bg / model 的比
        val cases = listOf(
            Live2DBackground(enabled = true, widthPercent = 100, heightPercent = 100),
            Live2DBackground(enabled = true, widthPercent = 140, heightPercent = 100),
            Live2DBackground(enabled = true, widthPercent = 100, heightPercent = 175),
            Live2DBackground(enabled = true, widthPercent = 200, heightPercent = 160),
        )
        cases.forEach { bg ->
            val (winW, winH) = Live2DBackgroundGeometry.windowBase(1f, 612f, 354f, bg)
            val rects = Live2DBackgroundGeometry.previewRects(300f, 300f, 1f, bg)
            assertEquals(
                "宽度语义不一致：$bg",
                winW / 354f,
                rects.bgWidth / rects.modelWidth,
                0.02f,
            )
            assertEquals(
                "高度语义不一致：$bg",
                winH / 354f,
                rects.bgHeight / rects.modelHeight,
                0.02f,
            )
        }
    }

    @Test
    fun `aspect 退化时退回设计画布比例`() {
        val rects = Live2DBackgroundGeometry.previewRects(
            boxWidth = 200f,
            boxHeight = 200f,
            aspect = 0f,
            background = Live2DBackground(enabled = true),
        )
        // 200 宽的框里按 612:354 摆 → 宽吃满
        assertEquals(200f, rects.modelWidth, 0.01f)
        assertEquals(200f * 354f / 612f, rects.modelHeight, 0.01f)
    }

    @Test
    fun `coerce 收敛越界值并抹掉颜色的 alpha`() {
        val fixed = Live2DBackgroundGeometry.coerce(
            Live2DBackground(
                enabled = true,
                widthPercent = 500,
                heightPercent = 1,
                color = 0x80ABCDEF,
                opacityPercent = -20,
            ),
        )
        assertEquals(200, fixed.widthPercent)
        assertEquals(20, fixed.heightPercent)
        assertEquals(0xABCDEFL, fixed.color)
        assertEquals(0, fixed.opacityPercent)
        assertTrue("开关不能被动", fixed.enabled)
    }

    @Test
    fun `背景色转不透明 ARGB`() {
        val argb = Live2DBackgroundGeometry.opaqueArgb(Live2DBackground(color = 0xFF0000))
        assertEquals("红色不透明", 0xFFFF0000.toInt(), argb)
        assertEquals("白色", 0xFFFFFFFF.toInt(), Live2DBackgroundGeometry.opaqueArgb(Live2DBackground()))
    }
}

package com.something.sthkey.ui.overlay.touch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 方块文字的**缩放 / 截断**策略（方案 §11.3）。
 *
 * ============================================================
 * ⚠️ 为什么用假 measure
 * ============================================================
 * 真正的 `Paint.measureText` 在 JVM 单测里跑不了（Android 桩）。
 * 而这里要验的是**策略**（先缩字号、再截断、截断要保住括号），
 * 不是字体的实际宽度 —— 用一个"宽度 = 字数 × 字号 × 0.5"的假实现
 * 就足以把三条规则钉死。
 */
class TouchRegionLabelFitTest {

    /** 假字体:每个字宽 = 字号 × 0.5（近似中文全角的一半） */
    private val measure: (String, Float) -> Float = { text, size -> text.length * size * 0.5f }

    private fun fit(text: String, maxWidth: Float, max: Float = 30f, min: Float = 20f) =
        fitRegionLabel(text, maxWidth, max, min, measure)

    @Test
    fun `宽度够时原字号整段显示`() {
        val text = "按键区域 (按键 · A)"
        val fitted = fit(text, maxWidth = 1000f)
        assertEquals(text, fitted.text)
        assertEquals(30f, fitted.textSize, 0.001f)
    }

    @Test
    fun `略超时按比例缩字号（不截断）`() {
        val text = "按键区域 (按键 · A)"
        /* 13 字 × 30 × 0.5 = 195 → 130 宽时缩到 30×130/195 = 20 */
        val fitted = fit(text, maxWidth = 130f, min = 10f)
        assertEquals(text, fitted.text)
        assertEquals(20f, fitted.textSize, 0.01f)
        assertTrue("缩完必须真的放得下", measure(text, fitted.textSize) <= 130f)
    }

    @Test
    fun `缩到最小仍超时保留括号里的组件名`() {
        val text = "按键区域 (按键 · A)"
        /* 最小 20 时整段要 130；只给 100 → 截成 "…(按键 · A)"（9 字 = 90） */
        val fitted = fit(text, maxWidth = 100f, min = 20f)
        assertEquals("…(按键 · A)", fitted.text)
        assertEquals(20f, fitted.textSize, 0.001f)
        assertTrue("截断结果必须放得下", measure(fitted.text, fitted.textSize) <= 100f)
    }

    @Test
    fun `括号都放不下时退化成逐字省略`() {
        val text = "轮盘区域 (手柄摇杆 · 左摇杆)"
        val fitted = fit(text, maxWidth = 60f, min = 20f)
        assertTrue("应当以省略号结尾，实际='${fitted.text}'", fitted.text.endsWith("…"))
        assertTrue("必须放得下", measure(fitted.text, fitted.textSize) <= 60f)
        assertTrue("不能比原文还长", fitted.text.length < text.length)
    }

    @Test
    fun `方块太小放不下省略号时宁可不画`() {
        assertEquals("", fit("按键区域", maxWidth = 1f).text)
        assertEquals("", fit("", maxWidth = 100f).text)
        assertEquals("", fit("按键区域", maxWidth = 0f).text)
    }

    @Test
    fun `没有括号的短文本也能截断`() {
        val fitted = fit("触摸区域", maxWidth = 30f, min = 20f)
        assertTrue("应当以省略号结尾，实际='${fitted.text}'", fitted.text.endsWith("…"))
        assertTrue(measure(fitted.text, fitted.textSize) <= 30f)
    }
}

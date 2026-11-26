package com.something.sthkey.domain.live2d

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 多模式模型导入后的默认变体。
 *
 * ⚠️ 回归点：以前默认取"第一个"（导入排序把 keyboard 排在最前），
 * 但大量模型包只有 standard 是认真做的，keyboard/gamepad 只是占位，
 * 于是用户导入后看到一套假画面，还得自己去切模式。
 */
class Live2DDefaultVariantTest {

    private fun variant(name: String, pageDir: String) = Live2DVariant(name = name, pageDir = pageDir)

    @Test
    fun `多模式默认选标准`() {
        val variants = listOf(
            variant("键盘", "keyboard"),
            variant("标准", "standard"),
            variant("手柄", "gamepad"),
        )
        assertEquals("standard", Live2DModels.defaultVariant(variants))
    }

    @Test
    fun `目录名大小写与嵌套路径也能认出标准`() {
        val variants = listOf(
            variant("键盘", "img/keyboard"),
            variant("标准", "img/Standard"),
        )
        assertEquals("img/Standard", Live2DModels.defaultVariant(variants))
    }

    @Test
    fun `没有标准时退回第一个`() {
        val variants = listOf(
            variant("键盘", "keyboard"),
            variant("手柄", "gamepad"),
        )
        assertEquals("keyboard", Live2DModels.defaultVariant(variants))
    }

    @Test
    fun `单模式返回根目录空串`() {
        assertEquals("", Live2DModels.defaultVariant(emptyList()))
    }
}

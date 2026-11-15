package com.something.sthkey.data.config

import com.something.sthkey.domain.style.StyleId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导出说明（`_comment`）的覆盖率。
 *
 * ============================================================
 * ⚠️ 为什么要有这条
 * ============================================================
 * 字段说明是**手写**的，而配置字段会随版本增加。没有测试的话，
 * 新字段的说明会悄悄缺失，导出的注释变成"（暂无说明：xxx）"。
 *
 * 所以这里用 `JsonConfigCodec.expectedKeysFor(style)` —— 与导入差异比较
 * **同一个基准** —— 逐字段检查有没有说明。
 */
class ConfigJsonCommentsTest {

    private val styles = listOf(
        StyleId.KEYSTROKES,
        StyleId.GAMEPAD1,
        StyleId.GAMEPAD2,
        StyleId.TOUCHING,
        StyleId.CUSTOM_KEY,
        StyleId.KEYBOARD_CAT,
    )

    @Test
    fun `每种样式的每个预期字段都有说明`() {
        styles.forEach { style ->
            val keys = JsonConfigCodec.expectedKeysFor(style).toList()
            val lines = ConfigJsonComments.paramsFor(style, keys)
            val missing = lines.filter { it.contains("暂无说明") }
            assertTrue(
                "样式 $style 有字段缺说明:\n${missing.joinToString("\n")}",
                missing.isEmpty(),
            )
        }
    }

    @Test
    fun `说明里有键位 ID 与单位提示`() {
        val lines = ConfigJsonComments.paramsFor(StyleId.KEYSTROKES, listOf("scalePercent"))
        val text = lines.joinToString("\n")
        assertTrue("应提示键位是明文 ID:$text", text.contains("明文 ID"))
        assertTrue("应提示坐标/百分比单位:$text", text.contains("百分比"))
        assertTrue("应列出字段:$text", text.contains("scalePercent"))
    }

    @Test
    fun `包注释不会丢原字段`() {
        val original = JSONObject().apply {
            put("schemaVersion", 1)
            put("styleId", StyleId.KEYSTROKES)
            put("scalePercent", 100)
        }

        val wrapped = ConfigJsonComments.withParamsComment(original, StyleId.KEYSTROKES)
        assertTrue(wrapped.has(ConfigJsonComments.KEY))
        assertEquals(1, wrapped.getInt("schemaVersion"))
        assertEquals(100, wrapped.getInt("scalePercent"))
        assertEquals(StyleId.KEYSTROKES, wrapped.getString("styleId"))
    }

    @Test
    fun `manifest 与组合 manifest 都有说明`() {
        assertFalse(ConfigJsonComments.manifest().isEmpty())
        assertFalse(ConfigJsonComments.groupManifest().isEmpty())
        assertTrue(ConfigJsonComments.manifest().any { it.contains("format") })
        assertTrue(ConfigJsonComments.groupManifest().any { it.contains("configs") })
    }
}

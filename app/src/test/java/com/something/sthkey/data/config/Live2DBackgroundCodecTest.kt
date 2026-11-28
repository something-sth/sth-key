package com.something.sthkey.data.config

import com.something.sthkey.domain.config.KeyStrokesConfig
import com.something.sthkey.domain.config.Live2DBackground
import com.something.sthkey.domain.config.Live2DSettings
import com.something.sthkey.domain.style.StyleId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Live2D 纯色背景的编解码。
 *
 * 重点不是"能存能读"，而是三条**破坏性**的边界：
 * 1. 老配置（2.8.7，没有 `background`）读出来必须是**关闭**，外观零变化；
 * 2. 开关关着时五个字段**照样写出去** —— 否则调好再关掉就永久丢；
 * 3. 越界值（手工改的配置包）必须收敛，否则会算出巨大的窗口。
 */
class Live2DBackgroundCodecTest {

    private fun live2dConfig(background: Live2DBackground): KeyStrokesConfig =
        KeyStrokesConfig(
            id = "c",
            name = "C",
            description = "",
            styleId = StyleId.KEYBOARD_CAT,
            live2d = Live2DSettings(background = background),
        )

    @Test
    fun `默认是关闭的且默认值符合需求`() {
        val bg = Live2DBackground()
        assertFalse("背景默认关闭", bg.enabled)
        assertEquals("默认宽度 100%", 100, bg.widthPercent)
        assertEquals("默认高度 100%", 100, bg.heightPercent)
        assertEquals("默认颜色 FFFFFF", 0xFFFFFFL, bg.color)
        assertEquals("默认不透明度 100", 100, bg.opacityPercent)
    }

    @Test
    fun `五个字段都会写出去_即使开关关着`() {
        val config = live2dConfig(
            Live2DBackground(
                enabled = false,
                widthPercent = 140,
                heightPercent = 120,
                color = 0x123456,
                opacityPercent = 85,
            ),
        )
        val live2d = JsonConfigCodec.encodeForStorage(config).getJSONObject("live2d")
        val bg = live2d.getJSONObject("background")

        assertFalse(bg.getBoolean("enabled"))
        assertEquals(140, bg.getInt("widthPercent"))
        assertEquals(120, bg.getInt("heightPercent"))
        assertEquals(0x123456, bg.getInt("color"))
        assertEquals(85, bg.getInt("opacityPercent"))
    }

    @Test
    fun `编码再解码是往返的`() {
        val original = Live2DBackground(
            enabled = true,
            widthPercent = 175,
            heightPercent = 33,
            color = 0x00FF00,
            opacityPercent = 40,
        )
        val encoded = JsonConfigCodec.encodeForStorage(live2dConfig(original))
        val decoded = JsonConfigCodec.decode(encoded).live2d.background

        assertEquals(original, decoded)
    }

    @Test
    fun `老配置没有 background 时解出关闭_其它字段不受影响`() {
        // 2.8.7 的 live2d 段：没有 background
        val json = JSONObject(
            """
            {
              "schemaVersion": 1,
              "id": "c",
              "styleId": "keyboard_cat",
              "name": "C",
              "description": "",
              "live2d": {
                "modelId": "imported:abc",
                "opacityPercent": 60,
                "hideWatermark": false
              }
            }
            """.trimIndent(),
        )
        val config = JsonConfigCodec.decode(json)

        assertFalse("老配置背景必须是关", config.live2d.background.enabled)
        assertEquals(Live2DBackground(), config.live2d.background)
        // 既有字段不能被顺手改掉
        assertEquals("imported:abc", config.live2d.modelId)
        assertEquals(60, config.live2d.opacityPercent)
        assertFalse(config.live2d.hideWatermark)
    }

    @Test
    fun `越界值会收敛_颜色会被抹成 24 位`() {
        val background = JSONObject().apply {
            put("enabled", true)
            put("widthPercent", 500)
            put("heightPercent", -3)
            // 0xFFFF8800：带 alpha 的颜色，解码时只留 0xFF8800
            put("color", 0xFFFF8800L)
            put("opacityPercent", 300)
        }
        val json = JSONObject().apply {
            put("schemaVersion", 1)
            put("id", "c")
            put("name", "C")
            put("description", "")
            put("styleId", "keyboard_cat")
            put("live2d", JSONObject().apply { put("background", background) })
        }
        val bg = JsonConfigCodec.decode(json).live2d.background

        assertTrue(bg.enabled)
        assertEquals("宽度上限 200", 200, bg.widthPercent)
        assertEquals("高度下限 20", 20, bg.heightPercent)
        assertEquals("颜色抹掉 alpha", 0xFF8800L, bg.color)
        assertEquals("不透明度上限 100", 100, bg.opacityPercent)
    }

    @Test
    fun `非 Live2D 样式不写 live2d 段`() {
        val config = KeyStrokesConfig(id = "k", name = "K", description = "", styleId = StyleId.KEYSTROKES)
        val json = JsonConfigCodec.encodeForStorage(config)
        assertFalse("键盘样式不该有 live2d 字段", json.has("live2d"))
    }
}

package com.something.sthkey.data.config

import com.something.sthkey.domain.custom.CustomLayoutSettings
import com.something.sthkey.domain.custom.MediaComponent
import com.something.sthkey.domain.custom.MediaFit
import com.something.sthkey.domain.custom.MediaKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 媒体组件的染色/描边字段**只有图片才写进配置**。
 *
 * ============================================================
 * ⚠️ 用户要求（2026-11-08）
 * ============================================================
 * > 配置里也记得改，字段里不要出现视频与动图组件的描边颜色这些字段
 *
 * 原因:这两个能力目前只对图片实现（动图在悬浮窗里是 Canvas 逐帧画、
 * 视频是 TextureView 的 Surface），写在视频/动图的配置里等于**假承诺** ——
 * 用户改完发现没效果，还会以为是自己操作错了。
 */
class MediaStyleCodecTest {

    private fun media(kind: MediaKind) = MediaComponent(
        id = "m1",
        x = 0f,
        y = 0f,
        width = 100f,
        height = 100f,
        kind = kind,
        assetId = "asset-1",
        fit = MediaFit.COVER,
        tintColor = 0xFF0000,
        tintPercent = 40,
        outlineWidthPercent = 5,
        outlineColor = 0x00FF00,
    )

    private fun encoded(kind: MediaKind): JSONObject {
        val json = CustomLayoutCodec.encode(
            CustomLayoutSettings(components = listOf(media(kind))),
        )
        val components = json.getJSONArray("components")
        return components.getJSONObject(0)
    }

    @Test
    fun `图片会写出染色与描边字段`() {
        val item = encoded(MediaKind.IMAGE)
        assertEquals(0xFF0000L, item.getLong("tintColor"))
        assertEquals(40, item.getInt("tintPercent"))
        assertEquals(5, item.getInt("outlineWidthPercent"))
        assertEquals(0x00FF00L, item.getLong("outlineColor"))
    }

    @Test
    fun `动图与视频不写染色与描边字段`() {
        listOf(MediaKind.GIF, MediaKind.VIDEO).forEach { kind ->
            val item = encoded(kind)
            assertFalse("$kind 不该带 tintColor", item.has("tintColor"))
            assertFalse("$kind 不该带 tintPercent", item.has("tintPercent"))
            assertFalse("$kind 不该带 outlineWidthPercent", item.has("outlineWidthPercent"))
            assertFalse("$kind 不该带 outlineColor", item.has("outlineColor"))
            /* 其它字段照旧 */
            assertTrue("$kind 仍要写 mediaKind", item.has("mediaKind"))
            assertTrue("$kind 仍要写 fit", item.has("fit"))
        }
    }

    @Test
    fun `缺字段的配置读回来是默认值`() {
        val json = JSONObject(
            """
            {
              "components": [
                {
                  "type": "video",
                  "id": "m1",
                  "x": 0,
                  "y": 0,
                  "width": 100,
                  "height": 100,
                  "mediaKind": "video",
                  "assetId": "asset-1",
                  "fit": "cover"
                }
              ]
            }
            """.trimIndent(),
        )
        val settings = CustomLayoutCodec.decode(json)
        val component = settings.components.single() as MediaComponent
        assertEquals(0, component.tintPercent)
        assertEquals(0, component.outlineWidthPercent)
    }
}

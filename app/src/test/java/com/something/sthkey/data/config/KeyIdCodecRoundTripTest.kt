package com.something.sthkey.data.config

import com.something.sthkey.domain.config.KeyMapping
import com.something.sthkey.domain.config.defaultConfig
import com.something.sthkey.domain.custom.ComponentStyle
import com.something.sthkey.domain.custom.ComponentType
import com.something.sthkey.domain.custom.CustomLayoutSettings
import com.something.sthkey.domain.custom.JoystickComponent
import com.something.sthkey.domain.custom.JoystickSource
import com.something.sthkey.domain.custom.KeyComponent
import com.something.sthkey.domain.custom.TextComponent
import com.something.sthkey.domain.keys.KeyCodes
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 明文键位 ID 的**编解码往返**（2026-11-15）。
 *
 * ⚠️ 这一组测试的意义:
 * | 漏掉什么 | 用户看到 |
 * |---|---|
 * | 某个字段还在写数字 | JSON 里数字与 ID 混着，Windows 端解析不了 |
 * | 某个字段只改了编码、没改解码 | 存进去读不回来 → 绑定静默丢失 |
 * | 老数字不认 | 升级后所有老配置的键位全空 |
 */
class KeyIdCodecRoundTripTest {

    private fun key(id: String, codes: List<Int>) = KeyComponent(
        id = id,
        x = 0f,
        y = 0f,
        width = 80f,
        height = 80f,
        style = ComponentStyle(),
        label = "Q",
        inputKeyCodes = codes,
    )

    @Test
    fun `自定义组件的键位写出明文并读回原码`() {
        val settings = CustomLayoutSettings(
            components = listOf(
                key("k", listOf(30, KeyCodes.STICK_LEFT_UP)),
                TextComponent(
                    id = "t",
                    x = 0f,
                    y = 0f,
                    width = 120f,
                    height = 30f,
                    style = ComponentStyle(),
                    text = "(cps)",
                    cpsKeyCodesPerPlaceholder = listOf(
                        listOf(KeyCodes.BTN_SOUTH),
                        listOf(KeyCodes.STICK_RIGHT_DOWN),
                    ),
                ),
            ),
        )

        val json = CustomLayoutCodec.encode(settings)
        val components = json.getJSONArray("components")

        val keyCodes = components.getJSONObject(0).getJSONArray("inputKeyCodes")
        assertEquals("KEY_A", keyCodes.getString(0))
        assertEquals("JOYSTICK_LEFT_UP", keyCodes.getString(1))

        val cpsGroups = components.getJSONObject(1)
            .getJSONArray("cpsKeyCodesPerPlaceholder")
        assertEquals("GAMEPAD_A", cpsGroups.getJSONArray(0).getString(0))
        assertEquals("JOYSTICK_RIGHT_DOWN", cpsGroups.getJSONArray(1).getString(0))

        val decoded = CustomLayoutCodec.decode(json)
        val decodedKey = decoded.components.filterIsInstance<KeyComponent>().single()
        assertEquals(listOf(30, KeyCodes.STICK_LEFT_UP), decodedKey.inputKeyCodes)

        val decodedText = decoded.components.filterIsInstance<TextComponent>().single()
        assertEquals(
            listOf(listOf(KeyCodes.BTN_SOUTH), listOf(KeyCodes.STICK_RIGHT_DOWN)),
            decodedText.cpsKeyCodesPerPlaceholder,
        )
    }

    @Test
    fun `摇杆组件的四向键位也写明文，且不接受摇杆方向`() {
        val settings = CustomLayoutSettings(
            components = listOf(
                JoystickComponent(
                    id = "j",
                    x = 0f,
                    y = 0f,
                    width = 100f,
                    height = 100f,
                    source = JoystickSource.KEYBOARD,
                    inputKeyCodes = listOf(
                        listOf(17),
                        listOf(30),
                        listOf(31),
                        listOf(32),
                    ),
                ),
            ),
        )

        val json = CustomLayoutCodec.encode(settings)
        val groups = json.getJSONArray("components").getJSONObject(0)
            .getJSONArray("inputKeyCodes")
        assertEquals("KEY_W", groups.getJSONArray(0).getString(0))

        /*
         * ⚠️ 手改 JSON 把摇杆方向塞进四向键位 —— 解码必须过滤掉，
         * 否则"拿摇杆推摇杆"这种没有语义的绑定会留在配置里。
         */
        groups.getJSONArray(1).put("JOYSTICK_LEFT_UP")
        val decoded = CustomLayoutCodec.decode(json)
            .components.filterIsInstance<JoystickComponent>().single()
        assertFalse(
            "摇杆组件的四向键位里不该出现摇杆方向码",
            decoded.inputKeyCodes.flatten().any { KeyCodes.isStickDirection(it) },
        )
        assertEquals(listOf(17, 30, 31, 32), decoded.inputKeyCodes.map { it.first() })
    }

    @Test
    fun `老配置的数字键码仍然读得出来`() {
        val legacy = JSONObject().apply {
            put("version", 1)
            put(
                "components",
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("type", ComponentType.KEY.id)
                            put("id", "k")
                            put("x", 0)
                            put("y", 0)
                            put("width", 80)
                            put("height", 80)
                            put("label", "Q")
                            put("inputKeyCodes", JSONArray().apply { put(30); put(768) })
                        },
                    )
                },
            )
        }

        val decoded = CustomLayoutCodec.decode(legacy)
        val key = decoded.components.filterIsInstance<KeyComponent>().single()
        assertEquals(
            "老数字要照读，并且旧扳机伪码 768 要迁成 312",
            listOf(30, KeyCodes.BTN_TL2),
            key.inputKeyCodes,
        )
    }

    @Test
    fun `配置级键位映射也写出明文`() {
        val config = defaultConfig().copy(
            id = "t",
            name = "测试",
            keyMappings = listOf(
                KeyMapping(id = "W", inputKeyCodes = listOf(17), displayText = "W"),
            ),
        )

        val json = JsonConfigCodec.encodeForStorage(config)
        val codes = json.getJSONArray("keyMappings").getJSONObject(0)
            .getJSONArray("inputKeyCodes")
        assertEquals("KEY_W", codes.getString(0))
        assertTrue("写出去不该再有数字:${codes.opt(0)}", codes.opt(0) is String)

        val decoded = JsonConfigCodec.decode(json)
        assertEquals(listOf(17), decoded.keyMappings.single().inputKeyCodes)
    }

    @Test
    fun `键位映射不接受摇杆方向码`() {
        /*
         * ⚠️ 键位映射是"槽位 ↔ 物理键"，摇杆方向没有槽位可落。
         * 手改 JSON 塞进去的话，解码要过滤掉 —— 否则它会留着一个
         * 永远不会触发的绑定（摇杆方向码从不进入物理 pressedKeys）。
         */
        val config = defaultConfig().copy(
            id = "t",
            name = "测试",
            keyMappings = listOf(
                KeyMapping(
                    id = "W",
                    inputKeyCodes = listOf(17, KeyCodes.STICK_LEFT_UP),
                    displayText = "W",
                ),
            ),
        )

        val json = JsonConfigCodec.encodeForStorage(config)
        val decoded = JsonConfigCodec.decode(json)
        assertEquals(listOf(17), decoded.keyMappings.single().inputKeyCodes)
    }
}

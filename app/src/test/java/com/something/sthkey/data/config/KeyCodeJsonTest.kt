package com.something.sthkey.data.config

import com.something.sthkey.domain.keys.KeyCodes
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 键位数组的 **JSON 双读 / 明文写**。
 *
 * ⚠️ 只测合法值:不认识的 ID 会走 `AppLog.w`，而 JVM 单测里
 * `android.util.Log` 是空壳 —— 那是"无效配置"的路径，不该靠单测覆盖，
 * 真机上会记日志并跳过。
 */
class KeyCodeJsonTest {

    @Test
    fun `数字与明文可以混在同一个数组里`() {
        val array = JSONArray().apply {
            put(30) // 老配置:数字
            put("GAMEPAD_A")
            put("JOYSTICK_LEFT_UP")
            put(KeyCodes.LEGACY_PSEUDO_KEY_TRIGGER_LEFT)
        }

        assertEquals(
            listOf(30, KeyCodes.BTN_SOUTH, KeyCodes.STICK_LEFT_UP, KeyCodes.BTN_TL2),
            KeyCodeJson.readList(array),
        )
    }

    @Test
    fun `写出去永远是明文 ID`() {
        val array = JSONArray().apply {
            KeyCodeJson.putList(this, listOf(30, KeyCodes.BTN_SOUTH, KeyCodes.STICK_LEFT_UP))
        }

        assertEquals(3, array.length())
        assertEquals("KEY_A", array.opt(0))
        assertEquals("GAMEPAD_A", array.opt(1))
        assertEquals("JOYSTICK_LEFT_UP", array.opt(2))
        for (index in 0 until array.length()) {
            assertTrue("第 $index 项不是字符串:${array.opt(index)}", array.opt(index) is String)
        }
    }

    @Test
    fun `空数组与 null 都读成空列表`() {
        assertEquals(emptyList<Int>(), KeyCodeJson.readList(null))
        assertEquals(emptyList<Int>(), KeyCodeJson.readList(JSONArray()))
    }
}

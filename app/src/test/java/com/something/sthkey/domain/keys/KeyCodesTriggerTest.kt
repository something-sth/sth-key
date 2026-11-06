package com.something.sthkey.domain.keys

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 扳机键码的回归测试。
 *
 * ============================================================
 * ⚠️ 背景（用户反馈的真实问题）
 * ============================================================
 * LT / RT 曾经用**自造伪键码** `0x300` / `0x301`（= 768 / 769）。
 * 手柄样式里能用，是因为那条路专门认这两个码；
 * 但"键位映射"里用户能看到、能选的**正规**扳机码是
 * `BTN_TL2` / `BTN_TR2`（312 / 313）。
 *
 * 结果:手柄配置转自定义 Key 之后，312 / 313 完全没反应，
 * 而 768 / 769 极不稳定、多绑几个键就互相干扰。
 *
 * 修法:**统一成真实键码**，旧配置读取时迁移。
 * 这组测试把这两件事钉住。
 */
class KeyCodesTriggerTest {

    @Test
    fun `扳机常量必须是真实 evdev 键码`() {
        assertEquals(
            "左扳机必须等于 BTN_TL2(312)",
            312,
            KeyCodes.PSEUDO_KEY_TRIGGER_LEFT,
        )
        assertEquals(
            "右扳机必须等于 BTN_TR2(313)",
            313,
            KeyCodes.PSEUDO_KEY_TRIGGER_RIGHT,
        )
        assertEquals(KeyCodes.BTN_TL2, KeyCodes.PSEUDO_KEY_TRIGGER_LEFT)
        assertEquals(KeyCodes.BTN_TR2, KeyCodes.PSEUDO_KEY_TRIGGER_RIGHT)
    }

    @Test
    fun `旧伪键码要迁移成真实键码`() {
        assertEquals(KeyCodes.BTN_TL2, KeyCodes.migrateLegacyCode(0x300))
        assertEquals(KeyCodes.BTN_TR2, KeyCodes.migrateLegacyCode(0x301))
        assertEquals(768, KeyCodes.LEGACY_PSEUDO_KEY_TRIGGER_LEFT)
        assertEquals(769, KeyCodes.LEGACY_PSEUDO_KEY_TRIGGER_RIGHT)
    }

    @Test
    fun `迁移不会动其它键码`() {
        val untouched = listOf(
            KeyCodes.BTN_SOUTH,
            KeyCodes.BTN_EAST,
            KeyCodes.BTN_TL,
            KeyCodes.BTN_TR,
            KeyCodes.BTN_START,
            KeyCodes.BTN_LEFT,
            KeyCodes.KEY_W,
        )
        untouched.forEach { code ->
            assertEquals("键码 $code 不该被迁移", code, KeyCodes.migrateLegacyCode(code))
        }
    }

    /**
     * 键名表里 `LT` / `RT` 指向的必须是 312 / 313 —— 用户在"键位映射"里
     * 选的就是这一项，与采集侧合成的码必须是同一个。
     */
    @Test
    fun `键位映射里 LT 与 RT 指向真实键码`() {
        val lt = KeyCodes.ALL.firstOrNull { it.name == "LT" }
        val rt = KeyCodes.ALL.firstOrNull { it.name == "RT" }
        assertEquals("键名表里的 LT 必须是 BTN_TL2", KeyCodes.BTN_TL2, lt?.keyCode)
        assertEquals("键名表里的 RT 必须是 BTN_TR2", KeyCodes.BTN_TR2, rt?.keyCode)
    }
}

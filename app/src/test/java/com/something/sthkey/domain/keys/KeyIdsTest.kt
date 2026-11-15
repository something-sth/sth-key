package com.something.sthkey.domain.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 明文键位 ID 注册表（2026-11-15）。
 *
 * ============================================================
 * ⚠️ 这个表是**跨平台契约**，改了会让老配置读不出来
 * ============================================================
 * 三条必须一直成立:
 *
 * | 约束 | 破了会怎样 |
 * |---|---|
 * | 每个可选键都有 ID | 那个键写不进配置（或退化成 `EVDEV_`）|
 * | ID 全局唯一 | 读回来指向别的键 |
 * | ID ↔ evdev 码往返一致 | 配置里的绑定失效 |
 */
class KeyIdsTest {

    @Test
    fun `每个可选键都有规范 ID`() {
        val missing = KeyCodes.ALL.filter { !KeyIds.isCanonical(KeyIds.encode(it.keyCode)) }
        assertTrue(
            "以下键没有明文 ID（会退化成 EVDEV_）:${missing.map { "${it.name}=${it.keyCode}" }}",
            missing.isEmpty(),
        )
    }

    @Test
    fun `规范 ID 全局唯一`() {
        val ids = KeyCodes.ALL.map { KeyIds.encode(it.keyCode) }
        val duplicates = ids.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
        assertTrue("ID 重复:$duplicates", duplicates.isEmpty())
    }

    @Test
    fun `每个可选键的类型与编码往返一致`() {
        KeyCodes.ALL.forEach { key ->
            val id = KeyIds.encode(key.keyCode)
            assertEquals("「${key.name}」的 ID 往返不一致（id=$id）", key.keyCode, KeyIds.decode(id))
        }
    }

    @Test
    fun `ID 都有统一前缀`() {
        val bad = KeyIds.canonicalIds().filterNot {
            Regex("""^(KEY|MOUSE|GAMEPAD|JOYSTICK)_[A-Z0-9_]+$""").matches(it)
        }
        assertTrue("以下 ID 没有统一前缀:$bad", bad.isEmpty())
    }

    @Test
    fun `旧扳机伪码编码成正式手柄键`() {
        assertEquals("GAMEPAD_LT", KeyIds.encode(KeyCodes.LEGACY_PSEUDO_KEY_TRIGGER_LEFT))
        assertEquals("GAMEPAD_RT", KeyIds.encode(KeyCodes.LEGACY_PSEUDO_KEY_TRIGGER_RIGHT))
    }

    @Test
    fun `未知键用 EVDEV 前缀原样往返`() {
        assertEquals("EVDEV_514", KeyIds.encode(514))
        assertEquals(514, KeyIds.decode("EVDEV_514"))
    }

    @Test
    fun `认不出的字符串返回 null 而不是猜`() {
        assertNull(KeyIds.decode("A"))
        assertNull(KeyIds.decode(""))
        assertNull(KeyIds.decode("KEY_不存在的键"))
    }

    @Test
    fun `十六个摇杆方向都有独立 ID 且落在保留区间`() {
        val codes = listOf(
            KeyCodes.STICK_LEFT_UP, KeyCodes.STICK_LEFT_DOWN,
            KeyCodes.STICK_LEFT_LEFT, KeyCodes.STICK_LEFT_RIGHT,
            KeyCodes.STICK_LEFT_UP_LEFT, KeyCodes.STICK_LEFT_UP_RIGHT,
            KeyCodes.STICK_LEFT_DOWN_LEFT, KeyCodes.STICK_LEFT_DOWN_RIGHT,
            KeyCodes.STICK_RIGHT_UP, KeyCodes.STICK_RIGHT_DOWN,
            KeyCodes.STICK_RIGHT_LEFT, KeyCodes.STICK_RIGHT_RIGHT,
            KeyCodes.STICK_RIGHT_UP_LEFT, KeyCodes.STICK_RIGHT_UP_RIGHT,
            KeyCodes.STICK_RIGHT_DOWN_LEFT, KeyCodes.STICK_RIGHT_DOWN_RIGHT,
        )
        assertEquals(16, codes.toSet().size)
        codes.forEach { code ->
            assertTrue("$code 不在摇杆方向区间", KeyCodes.isStickDirection(code))
            val id = KeyIds.encode(code)
            assertTrue("$code 没有 JOYSTICK 前缀（id=$id）", id.startsWith("JOYSTICK_"))
            assertEquals(code, KeyIds.decode(id))
            assertTrue("摇杆方向缺显示名（$code）", KeyCodes.displayName(code).contains("摇杆"))
        }
    }

    @Test
    fun `摇杆方向与真实键码没有重叠`() {
        val real = KeyCodes.ALL.map { it.keyCode }.toSet()
        val overlaps = real.filter { KeyCodes.isStickDirection(it) }
        assertTrue("真实键码落在了摇杆方向区间:$overlaps", overlaps.isEmpty())
        assertFalse(KeyCodes.isStickDirection(KeyCodes.BTN_SOUTH))
    }
}

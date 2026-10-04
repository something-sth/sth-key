package com.something.sthkey.domain.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 按键选择器的**大类划分**。
 *
 * ============================================================
 * ⚠️ 为什么值得测
 * ============================================================
 * 键盘的 `A`（`KEY_A` = 30）与手柄的 `A`（`BTN_SOUTH` = 304）
 * 在列表里**名字都叫 A**。用户的原话:
 *
 * > "有些用户没有经验，想调 A 这类按键就会同时搜索出键盘的 A
 * >  和手柄的 A"
 *
 * 所以"哪个键属于哪个大类"直接决定用户能不能找到自己想要的键。
 * 分错的表现是**某类按键永远搜不到** —— 而用户会以为不支持。
 */
class KeyPickerGroupTest {

    @Test
    fun `键盘的 A 与手柄的 A 分属不同大类`() {
        /*
         * ⚠️ 这条就是用户报的那个场景本身。
         *
         * 两者的**显示名都是 A**，所以只能靠大类（或 code）区分。
         */
        val keyboardA = KeyCodes.ALL.first { it.keyCode == 30 }
        val gamepadA = KeyCodes.ALL.first { it.keyCode == KeyCodes.BTN_SOUTH }

        assertEquals("键盘 A 的名字", "A", keyboardA.name)
        assertEquals("手柄 A 的名字", "A", gamepadA.name)
        assertTrue(
            "正因为名字一样，大类必须不同 —— 否则用户没法区分",
            KeyCodes.groupOf(keyboardA.keyCode) != KeyCodes.groupOf(gamepadA.keyCode),
        )
        assertEquals("键盘 A 应当在键盘类", KeyCodes.PickerGroup.KEYBOARD, KeyCodes.groupOf(30))
        assertEquals(
            "手柄 A 应当在手柄类",
            KeyCodes.PickerGroup.GAMEPAD,
            KeyCodes.groupOf(KeyCodes.BTN_SOUTH),
        )
    }

    @Test
    fun `所有手柄按键都归到手柄类`() {
        val gamepadCodes = listOf(
            KeyCodes.BTN_SOUTH, KeyCodes.BTN_EAST, KeyCodes.BTN_NORTH, KeyCodes.BTN_WEST,
            KeyCodes.BTN_TL, KeyCodes.BTN_TR, KeyCodes.BTN_TL2, KeyCodes.BTN_TR2,
            KeyCodes.BTN_SELECT, KeyCodes.BTN_START, KeyCodes.BTN_MODE,
            KeyCodes.BTN_THUMBL, KeyCodes.BTN_THUMBR,
            KeyCodes.BTN_DPAD_UP, KeyCodes.BTN_DPAD_DOWN,
            KeyCodes.BTN_DPAD_LEFT, KeyCodes.BTN_DPAD_RIGHT,
            /* 本项目自造的扳机伪键码也要归进去 */
            KeyCodes.PSEUDO_KEY_TRIGGER_LEFT, KeyCodes.PSEUDO_KEY_TRIGGER_RIGHT,
        )

        gamepadCodes.forEach { code ->
            assertEquals(
                "code $code 应当归到手柄类",
                KeyCodes.PickerGroup.GAMEPAD,
                KeyCodes.groupOf(code),
            )
        }
    }

    @Test
    fun `普通键盘按键归到键盘类`() {
        listOf(
            30 /* A */, 17 /* W */, 57 /* SPACE */, 42 /* LSHIFT */,
            1 /* ESC */, 272 /* BTN_LEFT 鼠标左键 */, 273 /* 鼠标右键 */,
        ).forEach { code ->
            assertEquals(
                "code $code 应当归到键盘类",
                KeyCodes.PickerGroup.KEYBOARD,
                KeyCodes.groupOf(code),
            )
        }
    }

    /**
     * ⚠️ **鼠标不能归到手柄类**。
     *
     * 两者在 evdev 里码相邻（鼠标 `0x110`、手柄 `0x130`），
     * 差一点就会把鼠标扔进手柄分类 —— 那样"键盘"分类里就没有鼠标键了，
     * 而键盘样式的 `LMB` / `RMB` 默认绑的正是鼠标码。
     */
    @Test
    fun `鼠标按键不算手柄`() {
        assertEquals(KeyCodes.PickerGroup.KEYBOARD, KeyCodes.groupOf(KeyCodes.BTN_LEFT))
        assertEquals(KeyCodes.PickerGroup.KEYBOARD, KeyCodes.groupOf(KeyCodes.BTN_RIGHT))
        assertEquals(KeyCodes.PickerGroup.KEYBOARD, KeyCodes.groupOf(KeyCodes.BTN_MIDDLE))
    }

    /* ============================================================
     * 分组检索
     * ============================================================ */

    @Test
    fun `按大类检索会把另一类排除掉`() {
        val all = KeyCodes.searchIn("a", KeyCodes.PickerGroup.ALL)
        val gamepad = KeyCodes.searchIn("a", KeyCodes.PickerGroup.GAMEPAD)
        val keyboard = KeyCodes.searchIn("a", KeyCodes.PickerGroup.KEYBOARD)

        assertTrue("搜 a 应当同时有键盘与手柄的结果", all.size > 1)
        assertTrue("手柄类应当有结果（手柄 A）", gamepad.isNotEmpty())
        assertTrue("键盘类应当有结果（键盘 A）", keyboard.isNotEmpty())

        /* 三类互不越界 */
        gamepad.forEach {
            assertEquals(
                "手柄类里混进了 code ${it.keyCode}（${it.name}）",
                KeyCodes.PickerGroup.GAMEPAD,
                KeyCodes.groupOf(it.keyCode),
            )
        }
        keyboard.forEach {
            assertEquals(
                "键盘类里混进了 code ${it.keyCode}（${it.name}）",
                KeyCodes.PickerGroup.KEYBOARD,
                KeyCodes.groupOf(it.keyCode),
            )
        }

        /* 而"全部" = 两类之和 */
        assertEquals(
            "全部应当等于键盘 + 手柄",
            all.size,
            keyboard.size + gamepad.size,
        )
    }

    /**
     * ⚠️ [KeyCodes.searchIn] 与 [KeyCodes.search] 的匹配规则必须**一致**。
     *
     * 两份实现分叉的话，用户会看到"在全部里能搜到、在手柄里搜不到" ——
     * 而那是**同一个查询**，很难不让人以为是 bug。
     */
    @Test
    fun `全部大类与不分类检索结果一致`() {
        listOf("a", "b", "space", "l", "304", "").forEach { query ->
            assertEquals(
                "查询「$query」时，ALL 应当与 search 完全一致",
                KeyCodes.search(query).map { it.keyCode },
                KeyCodes.searchIn(query, KeyCodes.PickerGroup.ALL).map { it.keyCode },
            )
        }
    }

    /** 按键码数字精确检索不受大类影响（高级用法，输 `304` 直接命中） */
    @Test
    fun `按键码精确检索在各分类下都能命中`() {
        listOf(KeyCodes.PickerGroup.ALL, KeyCodes.PickerGroup.GAMEPAD).forEach { group ->
            val hit = KeyCodes.searchIn("304", group)
            assertTrue(
                "在 ${group.label} 里搜 304 应当命中手柄 A",
                hit.any { it.keyCode == KeyCodes.BTN_SOUTH },
            )
        }

        /* ⚠️ 而键盘类里搜 304 **不该**有结果 —— 大类确实在起作用 */
        assertTrue(
            "键盘类里不该搜到手柄的 code",
            KeyCodes.searchIn("304", KeyCodes.PickerGroup.KEYBOARD).isEmpty(),
        )
    }

    @Test
    fun `大类枚举有全部这一项`() {
        /*
         * ⚠️ "全部"必须存在 —— 高级用户直接输键码时要能用。
         * 少了它就只能先猜大类，而猜错的表现是"搜不到"，很费解。
         */
        assertTrue(
            "必须有 ALL 这一项",
            KeyCodes.PickerGroup.entries.any { it == KeyCodes.PickerGroup.ALL },
        )
        assertEquals("一共三项:键盘 / 手柄 / 全部", 3, KeyCodes.PickerGroup.entries.size)
    }
}

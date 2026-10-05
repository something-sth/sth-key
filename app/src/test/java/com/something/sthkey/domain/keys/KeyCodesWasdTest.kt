package com.something.sthkey.domain.keys

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [KeyCodes.KEY_W] / `KEY_A` / `KEY_S` / `KEY_D` 与 [KeyCodes.letters] 表一致。
 *
 * ============================================================
 * ⚠️ 这个测试防的是什么
 * ============================================================
 * 那四个常量是后加的具名常量，而**真正的数据**在 `letters` 那张表里
 * （组件选择器、显示名都从表里来）。两者不一致的话:
 *
 * - "摇杆-键盘"会去读一个**没有任何键会产生**的码 → 摇杆永远不动；
 * - 而界面上选 WASD 是正常的（走的是表），所以**看不出哪里坏了**。
 *
 * ⚠️ 这种"两处各写一遍同一份数据"正是本项目反复吃亏的模式
 * （按键间距的范围、摇杆描边的上限都踩过）。靠"记得同时改"不可靠，
 * 所以用一条测试钉住。
 */
class KeyCodesWasdTest {

    /**
     * 按**显示名**反查键码。
     *
     * ⚠️ 用公开的 [KeyCodes.ALL] 而不是那张私有的 `letters` 表 ——
     * 后者从外面取不到，而且它是实现细节（表的结构可能变）。
     * 这里要表达的是"用户看到的 W 就是 KEY_W"，与表怎么组织无关。
     */
    private fun codeOf(name: String): Int? =
        KeyCodes.ALL.firstOrNull { it.name == name }?.keyCode

    @Test
    fun `WASD 常量与字母表一致`() {
        assertEquals("W", KeyCodes.KEY_W, codeOf("W"))
        assertEquals("A", KeyCodes.KEY_A, codeOf("A"))
        assertEquals("S", KeyCodes.KEY_S, codeOf("S"))
        assertEquals("D", KeyCodes.KEY_D, codeOf("D"))
    }

    /**
     * ⚠️ 四个码必须**互不相同** —— 相同的话"按 W 也会点亮 A"这种怪事会出现，
     * 而它在界面上表现为"方向不对"，很难联想到是常量写重了。
     */
    @Test
    fun `四个方向键互不相同`() {
        val codes = listOf(KeyCodes.KEY_W, KeyCodes.KEY_A, KeyCodes.KEY_S, KeyCodes.KEY_D)
        assertEquals("WASD 的键码有重复", codes.size, codes.distinct().size)
    }
}

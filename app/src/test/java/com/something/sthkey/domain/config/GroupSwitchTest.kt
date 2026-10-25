package com.something.sthkey.domain.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置组合的开关语义。
 *
 * ⚠️ 这一组是全功能的**核心**:组合开关是派生的，只要公式错了，
 * 界面就会表现成"开关乱跳"。所以按用户描述的三条规则逐条覆盖。
 */
class GroupSwitchTest {

    private val group = ConfigGroup(
        id = "g1",
        name = "战斗",
        memberIds = listOf("a", "b"),
    )

    @Test
    fun `全部开启才算开启`() {
        assertFalse(GroupSwitch.isOn(group, emptySet()))
        assertFalse(GroupSwitch.isOn(group, setOf("a")))
        // ⚠️ 用户规则:"不全部开启都不刷新 switch"
        assertFalse(GroupSwitch.isOn(group, setOf("a", "x")))
        assertTrue(GroupSwitch.isOn(group, setOf("a", "b")))
    }

    @Test
    fun `组合外的配置开着不影响本组合`() {
        /*
         * ⚠️ 这条很重要:其它组合或未分组的配置开着时，
         * 本组合不能因此显示成"开启"。
         */
        assertFalse(GroupSwitch.isOn(group, setOf("x", "y", "a")))
        assertTrue(GroupSwitch.isOn(group, setOf("a", "b", "x")))
    }

    @Test
    fun `空组合永远是关闭的`() {
        val empty = group.copy(memberIds = emptyList())
        assertFalse(GroupSwitch.isOn(empty, emptySet()))
        // ⚠️ 即使启用集合里有别人，空组合也不算开启
        assertFalse(GroupSwitch.isOn(empty, setOf("a", "b")))
    }

    @Test
    fun `打开组合会把成员全部加进去`() {
        val result = GroupSwitch.setEnabled(group, setOf("x"), enabled = true)
        assertEquals(setOf("x", "a", "b"), result)
    }

    @Test
    fun `关闭组合只移除本组合的成员`() {
        /*
         * ⚠️ 用"移除成员"而不是"清空集合":
         * 集合里还可能有别的组合 / 未分组配置，清空会把它们一起关掉。
         */
        val result = GroupSwitch.setEnabled(group, setOf("a", "b", "x"), enabled = false)
        assertEquals(setOf("x"), result)
    }

    @Test
    fun `关闭后再打开能恢复`() {
        val off = GroupSwitch.setEnabled(group, setOf("a", "b", "x"), enabled = false)
        val on = GroupSwitch.setEnabled(group, off, enabled = true)
        assertEquals(setOf("x", "a", "b"), on)
    }

    @Test
    fun `统计已开启数量`() {
        assertEquals(0, GroupSwitch.enabledCount(group, emptySet()))
        assertEquals(1, GroupSwitch.enabledCount(group, setOf("a")))
        assertEquals(2, GroupSwitch.enabledCount(group, setOf("a", "b")))
        /* ⚠️ 组合外的 id 不计数 */
        assertEquals(1, GroupSwitch.enabledCount(group, setOf("a", "x", "y")))
    }
}

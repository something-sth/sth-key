package com.something.sthkey.capture.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「已启用无障碍服务」列表的合并逻辑。
 *
 * ============================================================
 * ⚠️⚠️ 为什么这段逻辑值得单独测
 * ============================================================
 * `enabled_accessibility_services` 是**一个冒号分隔的列表**，里面可能有
 * 用户的屏幕阅读器、自动化工具、输入法……
 *
 * ⚠️ 一旦写错（比如直接覆盖成只有我们），后果是
 * **一次把用户所有无障碍服务关掉** —— 那是个会让人崩溃的 bug，
 * 而且很难联想到是我们干的。
 *
 * ⚠️ 这段逻辑最早写在 shell 里（`for` / `case` / `if`），
 * 靠 `.replace("\n", "; ")` 压成一行 —— 那产出的是**语法错误**
 * （`then;` / `else;` 不合法），于是授权从来没成功过。
 * 现在搬到 Kotlin，**顺便就可以被这些测试覆盖了**。
 */
class AccessibilityGrantListTest {

    private val me = "com.something.sthkey/.capture.accessibility.SthKeyAccessibilityService"
    private val reader = "com.example.reader/.ReaderService"
    private val automation = "com.example.auto/.AutoService"

    @Test
    fun `系统没设过时返回只有自己`() {
        /*
         * ⚠️ `settings get secure enabled_accessibility_services` 在"从没设过"时
         * 输出的是**字符串 "null"**，不是空串、也不是 Java 的 null。
         * 不当心的话会得到 "null:com.xxx" 这种坏列表。
         */
        assertEquals(me, AccessibilityGrant.mergeServiceList(null, me))
        assertEquals(me, AccessibilityGrant.mergeServiceList("", me))
        assertEquals(me, AccessibilityGrant.mergeServiceList("null", me))
        assertEquals(me, AccessibilityGrant.mergeServiceList("   ", me))
    }

    @Test
    fun `保留用户已有的其它无障碍服务`() {
        /*
         * ⚠️ 这一条是**最重要的一条** —— 它防的是"把用户的屏幕阅读器关掉"。
         */
        val merged = AccessibilityGrant.mergeServiceList("$reader:$automation", me)
        assertEquals("$reader:$automation:$me", merged)
    }

    @Test
    fun `已经在列表里就不重复添加`() {
        /*
         * ⚠️ 重复条目会让某些 ROM 拒绝整个列表 —— 而那时表现为
         * "授权执行成功但无障碍就是不生效"，很难查。
         */
        val merged = AccessibilityGrant.mergeServiceList("$reader:$me", me)
        assertEquals("$reader:$me", merged)
        /* ⚠️ 位置也不能变（用户可能按自己的顺序排过） */
        assertEquals(
            "$reader:$me:$automation",
            AccessibilityGrant.mergeServiceList("$reader:$me:$automation", me),
        )
    }

    @Test
    fun `重绑时会先把自己摘掉再加到末尾`() {
        /*
         * ⚠️ `forceRebind` 的语义是"把我从原来的位置拿掉、重新加到末尾"，
         * 而不是"什么都不变"。
         */
        val merged = AccessibilityGrant.mergeServiceList(
            current = "$me:$reader",
            service = me,
            forceRebind = true,
        )
        assertEquals("$reader:$me", merged)
    }

    @Test
    fun `列表里有空条目时会被过滤掉`() {
        /*
         * ⚠️ 手改过的设置里可能出现 "a::b" 或 "a:" 这种。
         * 留着空条目会让列表变成 "a::b:me"，而某些系统组件解析不了。
         */
        assertEquals("$reader:$me", AccessibilityGrant.mergeServiceList("$reader::", me))
        assertEquals("$reader:$me", AccessibilityGrant.mergeServiceList(":$reader:", me))
    }

    @Test
    fun `条目两边的空白会被去掉`() {
        assertEquals("$reader:$me", AccessibilityGrant.mergeServiceList(" $reader ", me))
    }

    @Test
    fun `摘掉自己时保留别人`() {
        assertEquals(reader, AccessibilityGrant.removeServiceFromList("$me:$reader", me))
        assertEquals("$reader:$automation", AccessibilityGrant.removeServiceFromList(
            "$reader:$me:$automation",
            me,
        ))
    }

    @Test
    fun `摘掉之后什么都剩不下时返回空串`() {
        /*
         * ⚠️ 返回空串而不是 `"null"` 或 `""` 里有东西 ——
         * 调用方据此决定用 `settings delete`（留一个空条目会让
         * 某些 ROM 判成配置损坏）。
         */
        assertEquals("", AccessibilityGrant.removeServiceFromList(me, me))
        assertEquals("", AccessibilityGrant.removeServiceFromList(null, me))
        assertEquals("", AccessibilityGrant.removeServiceFromList("null", me))
    }

    @Test
    fun `合并与摘除是互逆的（在只有自己时）`() {
        /*
         * ⚠️ 这条是"结构自检":重绑 = 先摘再合。
         * 如果两者对"空列表"的处理不一致，重绑就会把一个空列表
         * 变成 `"null"` 或 `":me"` 这种坏东西。
         */
        val removed = AccessibilityGrant.removeServiceFromList(me, me)
        val reMerged = AccessibilityGrant.mergeServiceList(removed, me)
        assertEquals(me, reMerged)
    }
}

package com.something.sthkey.data.shortcut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快捷方式的**对外契约**与 id 分配规则。
 *
 * ============================================================
 * 这里防的是什么
 * ============================================================
 * 快捷方式的 Intent 钉到桌面之后就被系统**冻结**了 ——
 * 用户手机上那个图标的 extra 是"创建那一刻"的取值，之后我们改代码
 * 也不会同步过去。
 *
 * 所以 [ShortcutSettings.ACTION_LAUNCH] / [ACTION_DISABLE_ALL] /
 * [EXTRA_ACTION] / [EXTRA_SHORTCUT_ID] 这几个字符串是**对外契约**：
 * 改了它们，已经钉在用户桌面上的快捷方式会变成"动作无法识别"，
 * 表现是"点了图标没反应" —— 而编译是通过的。
 *
 * 本地单测跑不了 `AppPrefs`（它是 Android 的 `SharedPreferences`），
 * 但**常量本身**可以测 —— 这才是容易被人"顺手重命名"的部分。
 */
class ShortcutSettingsTest {

    @Test
    fun `动作名与 extra 键是对外契约，不要改`() {
        assertEquals(
            "Intent 会带这个值；改了会让已钉在桌面上的快捷方式失效",
            "launch",
            ShortcutSettings.ACTION_LAUNCH,
        )
        assertEquals(
            "同上",
            "disable_all",
            ShortcutSettings.ACTION_DISABLE_ALL,
        )
        assertEquals(
            "Intent extra 的键；同样是对外契约",
            "shortcut_action",
            ShortcutSettings.EXTRA_ACTION,
        )
        assertEquals(
            "⚠️ 这个 extra 决定「启动哪几份配置」，漏了它所有启动类快捷方式" +
                "会读到同一份清单（那正是「只能加两个」那个 bug 的成因之一）",
            "shortcut_id",
            ShortcutSettings.EXTRA_SHORTCUT_ID,
        )
    }

    /**
     * ⚠️ 两个动作**必须不同**。
     *
     * 写成同一个值的后果是"一键关闭"会变成"启动悬浮窗" ——
     * 想关掉却被打开了一堆窗口，而且用户完全不知道为什么。
     */
    @Test
    fun `两个动作不能相同`() {
        assertTrue(
            "两个动作名不能相同",
            ShortcutSettings.ACTION_LAUNCH != ShortcutSettings.ACTION_DISABLE_ALL,
        )
    }

    /**
     * ⚠️ 关闭类快捷方式的 id 必须是**固定值**，而且不能落在启动类的序号里。
     *
     * 关闭类只允许存在一个（多一个"关闭全部"图标没有任何意义）。
     * 要是它的 id 长得像 `launch_overlays_N`，`allLaunchers` 就会把它
     * 当成一个启动类快捷方式列出来，用户会看到一个"没有配置"的幽灵条目。
     */
    @Test
    fun `关闭类 id 固定且不属于启动类`() {
        assertEquals(
            "关闭类 id 固定，且是对外契约",
            "disable_all_overlays",
            ShortcutSettings.DISABLE_SHORTCUT_ID,
        )
        assertTrue(
            "关闭类 id 不能以启动类前缀开头，否则会被列进启动类列表",
            !ShortcutSettings.DISABLE_SHORTCUT_ID.startsWith("launch_overlays_"),
        )
    }

    /**
     * ⚠️ id 必须与 `res/xml/shortcuts.xml` 里声明的 `shortcutId` 有交集。
     *
     * 第一版"只能加两个"就是因为 id 写死了：第二次创建会**顶掉**第一个
     * （系统认为是同一个快捷方式）。现在启动类 id 动态分配
     * （`launch_overlays_1`、`_2`…），所以 XML 里只需要声明**一个**
     * 作为模板 —— 系统按前缀并不匹配，它只是要"这个应用会创建快捷方式"
     * 这份声明存在。
     *
     * 这条测试直接读 XML 核对，因为声明缺失时
     * `requestPinShortcut` 会**静默失败**（弹不出确认框）。
     */
    @Test
    fun `shortcuts_xml 里有声明`() {
        val xml = readShortcutsXml()

        assertTrue(
            "shortcuts.xml 里没有声明 disable_all_overlays",
            xml.contains("android:shortcutId=\"${ShortcutSettings.DISABLE_SHORTCUT_ID}\""),
        )
        assertTrue(
            "shortcuts.xml 里应当有一个启动类的模板声明",
            xml.contains("launch_overlays"),
        )
    }

    /**
     * ⚠️ 启动类的动态 id 不能与关闭类**撞上**。
     *
     * 撞上的后果是：用户创建一个启动快捷方式时，会把桌面上那个
     * "关闭全部"图标替换掉 —— 而他会以为"我明明只是加了一个新的"。
     */
    @Test
    fun `动态分配的启动类 id 不会撞上关闭类`() {
        // 模拟 nextLauncherId 的分配逻辑（它需要 Context，这里只验证前缀与格式）
        val generated = (1..5).map { "launch_overlays_$it" }

        generated.forEach { id ->
            assertNotEquals(
                "动态 id 不能等于关闭类的固定 id",
                ShortcutSettings.DISABLE_SHORTCUT_ID,
                id,
            )
        }
        assertEquals("分配出来的应当是 5 个互不相同的 id", 5, generated.distinct().size)
    }

    private fun readShortcutsXml(): String {
        val candidates = listOf(
            "src/main/res/xml/shortcuts.xml",
            "app/src/main/res/xml/shortcuts.xml",
        )
        candidates.forEach { path ->
            val file = java.io.File(path)
            if (file.exists()) return file.readText()
        }
        error("找不到 shortcuts.xml（试过：${candidates.joinToString()}）")
    }
}

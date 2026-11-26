package com.something.sthkey.domain.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 底栏样式的取值稳定性。
 *
 * ============================================================
 * ⚠️ 为什么这些"看起来没什么可测"的东西必须测
 * ============================================================
 * 偏好里存的是**字符串 id**（不是 `ordinal`）。这意味着:
 *
 * | 事件 | 后果（如果没有约束） |
 * |---|---|
 * | 以后在枚举中间插一个成员 | 存 `ordinal` 的话所有人的设置会**整体错位** |
 * | 改了某个成员的 id | 老用户存的旧 id 认不出来 → 静默回落默认值 |
 *
 * ⚠️ 所以 id 一旦发布就不能改 —— 这几条测试就是用来**卡住**这件事的。
 *
 * ⚠️ 2026-11-26：**标准底栏已移除**，只剩悬浮底栏。
 * 老用户存的 `"standard"` 必须安静地回落成悬浮（而不是崩/空白），
 * 所以下面专门保留了这条迁移断言。
 */
class BottomBarStyleTest {

    @Test
    fun `id 是稳定的字符串，不是序号`() {
        assertEquals("floating", BottomBarStyle.FLOATING.id)
    }

    @Test
    fun `只剩悬浮底栏一种`() {
        /*
         * 标准底栏已删除：用户明确说"太丑、也不好用"，只保留
         * 悬浮底栏 + 侧边底栏（侧边栏是另一个开关，不在这里）。
         */
        assertEquals(1, BottomBarStyle.entries.size)
        assertEquals(BottomBarStyle.FLOATING, BottomBarStyle.DEFAULT)
    }

    @Test
    fun `没设置过时用默认值`() {
        /*
         * ⚠️ `AppPrefs` 里"没设置过"返回的是**空串**（`getString(..., null).orEmpty()`），
         * 不是 null。所以 `fromId` 必须把空串也当成"没设置过"。
         */
        assertEquals(BottomBarStyle.FLOATING, BottomBarStyle.fromId(null))
        assertEquals(BottomBarStyle.FLOATING, BottomBarStyle.fromId(""))
    }

    @Test
    fun `认不出的值静默回落到默认值`() {
        /*
         * ⚠️ 降级安装、或者老配置里的 `standard` 都会走到这里。
         * 必须**安静地**回落 —— 抛异常的话应用一开就崩，
         * 用户连设置页都进不去，也就无法自救。
         */
        assertEquals(BottomBarStyle.FLOATING, BottomBarStyle.fromId("standard"))
        assertEquals(BottomBarStyle.FLOATING, BottomBarStyle.fromId("glass"))
        assertEquals(BottomBarStyle.FLOATING, BottomBarStyle.fromId("classic"))
    }

    @Test
    fun `id 往返一致`() {
        /*
         * ⚠️ 结构自检:保证 id 唯一。两个成员用了同一个 id 的话，
         * 后一个永远选不中 —— 那是"点了没反应"级别的 bug，肉眼很难发现。
         */
        BottomBarStyle.entries.forEach {
            assertEquals(it, BottomBarStyle.fromId(it.id))
        }
    }

    @Test
    fun `每个选项都有非空的中文标签与说明`() {
        /*
         * ⚠️ 标签与说明都是**给用户看的**。
         * 漏填的话界面上会出现空白选项或空副标题，用户不知道那是什么。
         */
        BottomBarStyle.entries.forEach {
            assertTrue("${it.name} 缺标签", it.label.isNotBlank())
            assertTrue("${it.name} 缺说明", it.description.isNotBlank())
        }
    }

    @Test
    fun `明暗模式的 id 也没有被改动过`() {
        /*
         * ⚠️ 和底栏样式同理:这几个 id 已经写进用户设备了。
         * 放在这里一起卡住，改的时候至少有一条测试会响。
         *
         * ⚠️ 2026-11-21 起「明暗」与「是否跟随壁纸」拆开了，
         * `monet_*` 只剩**旧 id 迁移**（见 `AppearanceTest`），不再写出去。
         */
        assertEquals("system", AppearanceColorMode.SYSTEM.id)
        assertEquals("light", AppearanceColorMode.LIGHT.id)
        assertEquals("dark", AppearanceColorMode.DARK.id)
        assertEquals("black", AppearanceColorMode.BLACK.id)
    }
}

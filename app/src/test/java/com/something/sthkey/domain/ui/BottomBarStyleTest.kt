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
 * | 以后在枚举中间插一个成员 | 存 `ordinal` 的话所有人的设置会**整体错位**，底栏突然变成另一种样式 |
 * | 改了某个成员的 id | 老用户存的旧 id 认不出来 → 静默回落默认值，用户以为自己没改过 |
 *
 * ⚠️ 所以 id 一旦发布就不能改 —— 这几条测试就是用来**卡住**这件事的。
 */
class BottomBarStyleTest {

    @Test
    fun `id 是稳定的字符串，不是序号`() {
        assertEquals("standard", BottomBarStyle.STANDARD.id)
        assertEquals("floating", BottomBarStyle.FLOATING.id)
    }

    @Test
    fun `只有标准和悬浮两种`() {
        /*
         * ⚠️ 刻意**没有**第三种（液态玻璃）:
         * 那需要 `miuix-blur 0.9.1+`（minSdk 32 / Kotlin 2.4），
         * 为了它把 minSdk 从 28 抬上去不划算。
         * 加回第三种时这条会失败 —— 那是**有意的提醒**:
         * 加之前先想清楚"选了不生效"的问题。
         */
        assertEquals(2, BottomBarStyle.entries.size)
    }

    @Test
    fun `没设置过时用默认值`() {
        /*
         * ⚠️ `AppPrefs` 里"没设置过"返回的是**空串**（`getString(..., null).orEmpty()`），
         * 不是 null。所以 `fromId` 必须把空串也当成"没设置过"。
         */
        assertEquals(BottomBarStyle.STANDARD, BottomBarStyle.fromId(null))
        assertEquals(BottomBarStyle.STANDARD, BottomBarStyle.fromId(""))
    }

    @Test
    fun `认不出的值静默回落到默认值`() {
        /*
         * ⚠️ 降级安装（装了新版本、又退回旧版本）会遇到这个:
         * 新版本写了一个旧版本不认识的 id。
         * 那时必须**安静地**回落 —— 抛异常的话应用一开就崩，
         * 用户连设置页都进不去，也就无法自救。
         */
        assertEquals(BottomBarStyle.STANDARD, BottomBarStyle.fromId("glass"))
        assertEquals(BottomBarStyle.STANDARD, BottomBarStyle.fromId("classic"))
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
         */
        assertEquals("system", AppearanceColorMode.SYSTEM.id)
        assertEquals("light", AppearanceColorMode.LIGHT.id)
        assertEquals("dark", AppearanceColorMode.DARK.id)
        assertEquals("monet_system", AppearanceColorMode.MONET_SYSTEM.id)
        assertEquals("monet_light", AppearanceColorMode.MONET_LIGHT.id)
        assertEquals("monet_dark", AppearanceColorMode.MONET_DARK.id)
    }
}

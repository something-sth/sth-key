package com.something.sthkey.domain.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外观设置三个枚举的**持久化规则**。
 *
 * ============================================================
 * ⚠️⚠️ 为什么这段值得单独测
 * ============================================================
 * 这些值会被写进 `SharedPreferences`，而用户**看不到**存的是什么 ——
 * 只有"下次打开还是我选的那个"这一个可见结果。
 *
 * ⚠️ 所以一旦解析规则写错（比如空串落到错误的默认值），
 * 表现是"设置没记住"，而那种 bug 很难被联想到是枚举解析。
 */
class AppearanceTest {

    /*
     * ============================================================
     * ⚠️ 存字符串 id，不存 ordinal
     * ============================================================
     * ⚠️ 用户看不到偏好的内容，但"枚举以后加成员"这件事**一定**会发生。
     * 存 ordinal 的话，加一个成员就会让所有老用户的设置**整体错位**
     * （选的是深色、打开变成浅色）。存 id 则完全不受影响。
     *
     * ⚠️ 下面这些断言把"id 长什么样"钉住了 —— 改 id 等于让老用户的
     * 设置失效，那是**数据迁移**级别的事，必须是有意为之。
     */
    @Test
    fun `明暗模式的 id 是稳定的字符串`() {
        assertEquals("system", AppearanceColorMode.SYSTEM.id)
        assertEquals("light", AppearanceColorMode.LIGHT.id)
        assertEquals("dark", AppearanceColorMode.DARK.id)
        assertEquals("monet_system", AppearanceColorMode.MONET_SYSTEM.id)
        assertEquals("monet_light", AppearanceColorMode.MONET_LIGHT.id)
        assertEquals("monet_dark", AppearanceColorMode.MONET_DARK.id)
    }


    @Test
    fun `没设置过时用默认值`() {
        /*
         * ⚠️ `AppPrefs` 里"没设置过"返回的是**空串**（`getString(..., null).orEmpty()`），
         * 不是 null。所以 `fromId` 必须把空串也当成"没设置过"。
         */
        assertEquals(AppearanceColorMode.SYSTEM, AppearanceColorMode.fromId(null))
        assertEquals(AppearanceColorMode.SYSTEM, AppearanceColorMode.fromId(""))
    }

    @Test
    fun `认不出的值回落到默认值，而不是崩溃`() {
        /*
         * ⚠️ 这是**降级安装**（装了新版本、又退回旧版本）会遇到的:
         * 新版本写了一个旧版本不认识的 id。
         *
         * ⚠️ 那时必须**安静地回落到默认值** —— 抛异常的话应用一开就崩，
         * 用户连设置页都进不去，也就无法自救。
         */
        assertEquals(AppearanceColorMode.SYSTEM, AppearanceColorMode.fromId("monet_ultra"))
    }

    @Test
    fun `id 能原样读回来（往返一致）`() {
        /*
         * ⚠️ 这条是**结构自检**:保证 `id` 唯一、且 `fromId` 真的能认出来。
         * 两个枚举成员用了同一个 id 的话，后一个永远选不中 ——
         * 那会是"点了没反应"级别的 bug，而肉眼很难发现。
         */
        AppearanceColorMode.entries.forEach {
            assertEquals(it, AppearanceColorMode.fromId(it.id))
        }
    }

    @Test
    fun `取色类模式能被识别出来`() {
        /*
         * ⚠️ 取色模式靠这个 id 前缀识别（用于主题里决定走取色还是固定配色） ——
         * 不取色时那个选项改了没有任何效果（ColorScheme 是固定的两套），
         * 显示出来只会让人以为设置坏了。
         */
        assertTrue(AppearanceColorMode.MONET_SYSTEM.id.startsWith("monet_"))
        assertTrue(AppearanceColorMode.MONET_LIGHT.id.startsWith("monet_"))
        assertTrue(AppearanceColorMode.MONET_DARK.id.startsWith("monet_"))

        assertFalse(AppearanceColorMode.SYSTEM.id.startsWith("monet_"))
        assertFalse(AppearanceColorMode.LIGHT.id.startsWith("monet_"))
        assertFalse(AppearanceColorMode.DARK.id.startsWith("monet_"))

        assertEquals(3, AppearanceColorMode.monetEntries.size)
    }

    @Test
    fun `每个选项都有非空的中文标签`() {
        /*
         * ⚠️ 标签是**给用户看的**，所以必须中文且非空。
         * 漏填的话界面上会出现一个空白选项，用户根本不知道那是什么。
         */
        AppearanceColorMode.entries.forEach {
            assertTrue("${it.name} 缺标签", it.label.isNotBlank())
        }
    }
}

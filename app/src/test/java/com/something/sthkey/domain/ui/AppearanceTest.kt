package com.something.sthkey.domain.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外观设置的**持久化规则**（明暗模式 / 色彩规格）。
 *
 * ⚠️ 这些值会写进 `SharedPreferences`，而用户**看不到**存的是什么 ——
 * 只有"下次打开还是我选的那个"这一个可见结果。所以解析规则（尤其是空串、
 * 认不出的 id、旧版本的 id）必须钉住，否则表现是"设置没记住"，很难联想到枚举解析。
 */
class AppearanceTest {

    @Test
    fun `明暗模式的 id 是稳定字符串`() {
        assertEquals("system", AppearanceColorMode.SYSTEM.id)
        assertEquals("light", AppearanceColorMode.LIGHT.id)
        assertEquals("dark", AppearanceColorMode.DARK.id)
        assertEquals("black", AppearanceColorMode.BLACK.id)
    }

    @Test
    fun `没设置过时用默认值`() {
        /* ⚠️ `AppPrefs` 里没设置过返回的是**空串**（`getString(..., null).orEmpty()`），不是 null */
        assertEquals(AppearanceColorMode.SYSTEM, AppearanceColorMode.fromId(null))
        assertEquals(AppearanceColorMode.SYSTEM, AppearanceColorMode.fromId(""))
    }

    @Test
    fun `认不出的值回落到默认值，而不是崩溃`() {
        /* 降级安装（装了新版本又退回旧版本）会读到旧版本不认识的 id，
         * 那时必须安静回落到默认值 —— 抛异常的话应用一开就崩。 */
        assertEquals(AppearanceColorMode.SYSTEM, AppearanceColorMode.fromId("monet_ultra"))
        assertEquals(AppearanceColorMode.SYSTEM, AppearanceColorMode.fromId("BLACK"))
    }

    @Test
    fun `旧版的取色模式 id 会迁移到对应明暗档`() {
        /*
         * ⚠️ 旧版本把「明暗」和「是否跟随壁纸」耦合成了 6 个值。
         * 迁移后「跟随壁纸」由 `seedColor == 0` 表达，明暗只留 4 档；
         * 老用户升级后观感不能变，所以这三个旧 id 必须映射对。
         */
        assertEquals(AppearanceColorMode.SYSTEM, AppearanceColorMode.fromId("monet_system"))
        assertEquals(AppearanceColorMode.LIGHT, AppearanceColorMode.fromId("monet_light"))
        assertEquals(AppearanceColorMode.DARK, AppearanceColorMode.fromId("monet_dark"))
    }

    @Test
    fun `id 能原样读回来（往返一致）`() {
        AppearanceColorMode.entries.forEach {
            assertEquals(it, AppearanceColorMode.fromId(it.id))
        }
    }

    @Test
    fun `明暗判断：跟随系统看参数，纯黑算深色`() {
        assertTrue(AppearanceColorMode.SYSTEM.isDark(systemDark = true))
        assertFalse(AppearanceColorMode.SYSTEM.isDark(systemDark = false))
        assertFalse(AppearanceColorMode.LIGHT.isDark(systemDark = true))
        assertTrue(AppearanceColorMode.DARK.isDark(systemDark = false))
        assertTrue(AppearanceColorMode.BLACK.isDark(systemDark = false))
    }

    @Test
    fun `只有纯黑是 AMOLED`() {
        AppearanceColorMode.entries.forEach {
            assertEquals(it == AppearanceColorMode.BLACK, it.isBlack)
        }
    }

    @Test
    fun `每个选项都有非空的中文标签`() {
        AppearanceColorMode.entries.forEach {
            assertTrue("${it.name} 缺标签", it.label.isNotBlank())
        }
    }

    // ---------------- 色彩规格 ----------------

    @Test
    fun `色彩规格的 id 稳定且默认 2025`() {
        assertEquals("spec_2025", ColorSpecOption.SPEC_2025.id)
        assertEquals("spec_2021", ColorSpecOption.SPEC_2021.id)
        assertEquals(ColorSpecOption.SPEC_2025, ColorSpecOption.DEFAULT)
        assertEquals(ColorSpecOption.SPEC_2025, ColorSpecOption.fromId(null))
        assertEquals(ColorSpecOption.SPEC_2025, ColorSpecOption.fromId(""))
        assertEquals(ColorSpecOption.SPEC_2025, ColorSpecOption.fromId("spec_2030"))
    }

    @Test
    fun `色彩规格往返一致且有标签说明`() {
        ColorSpecOption.entries.forEach {
            assertEquals(it, ColorSpecOption.fromId(it.id))
            assertTrue("${it.name} 缺标签", it.label.isNotBlank())
            assertTrue("${it.name} 缺说明", it.description.isNotBlank())
        }
    }
}

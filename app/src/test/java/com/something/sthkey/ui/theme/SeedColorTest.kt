package com.something.sthkey.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.something.sthkey.domain.ui.AppearancePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配色引擎（种子色 → Material 3 配色）。
 *
 * 作用只有一个：**防止以后悄悄换掉/改坏配色算法**。
 * 2026-11-21 从"自己拼 ColorScheme"换成了 materialKolor 5.0.1（与 SukiSU 同一个库同一个版本），
 * 当时与 SukiSU 的差异是：规格按风格回退、AMOLED、fixed 角色、壁纸种子。
 */
class SeedColorTest {

    private val seed = 0xFF6750A4.toInt()

    // ---------------- 映射 ----------------

    @Test
    fun `每一种风格都映射到独立的 PaletteStyle 且没有遗漏`() {
        val mapped = AppearancePalette.entries.map { it.toPaletteStyle() }
        assertEquals("风格数量应与 PaletteStyle 一致", AppearancePalette.entries.size, mapped.size)
        assertEquals("不允许两种风格映射到同一个", mapped.size, mapped.toSet().size)
        assertEquals(
            "9 种风格必须覆盖 PaletteStyle 的全部取值",
            PaletteStyle.entries.toSet(),
            mapped.toSet(),
        )
    }

    // ---------------- 规格回退（与 SukiSU 对齐的关键） ----------------

    @Test
    fun `不支持的风格在 2025 规格下会回退到 2021`() {
        listOf(
            AppearancePalette.RAINBOW,
            AppearancePalette.FRUIT_SALAD,
            AppearancePalette.MONOCHROME,
            AppearancePalette.FIDELITY,
            AppearancePalette.CONTENT,
        ).forEach { palette ->
            assertSameColors(
                expected = buildSeededColorScheme(seed, false, palette, ColorSpec.SpecVersion.SPEC_2021),
                actual = buildSeededColorScheme(seed, false, palette, ColorSpec.SpecVersion.SPEC_2025),
            )
        }
    }

    @Test
    fun `支持的风格在两种规格下确实不同`() {
        listOf(
            AppearancePalette.TONAL_SPOT,
            AppearancePalette.NEUTRAL,
            AppearancePalette.VIBRANT,
            AppearancePalette.EXPRESSIVE,
        ).forEach { palette ->
            val old = buildSeededColorScheme(seed, false, palette, ColorSpec.SpecVersion.SPEC_2021)
            val new = buildSeededColorScheme(seed, false, palette, ColorSpec.SpecVersion.SPEC_2025)
            assertNotEquals(
                "风格 $palette 的 2025 与 2021 应当产生不同主色",
                old.primary.toArgb(),
                new.primary.toArgb(),
            )
        }
    }

    // ---------------- AMOLED ----------------

    @Test
    fun `AMOLED 在深色下把 surface 系列刷成纯黑`() {
        val scheme = buildSeededColorScheme(seed, isDark = true, AppearancePalette.TONAL_SPOT, isAmoled = true)
        listOf(
            "surface" to scheme.surface,
            "background" to scheme.background,
            "surfaceDim" to scheme.surfaceDim,
            "surfaceContainer" to scheme.surfaceContainer,
            "surfaceContainerHigh" to scheme.surfaceContainerHigh,
            "surfaceContainerHighest" to scheme.surfaceContainerHighest,
            "surfaceContainerLow" to scheme.surfaceContainerLow,
            "surfaceContainerLowest" to scheme.surfaceContainerLowest,
        ).forEach { (name, color) ->
            assertEquals("$name 应当是纯黑", 0xFF000000.toInt(), color.toArgb())
        }
    }

    @Test
    fun `不开 AMOLED 时深色 surface 不是纯黑`() {
        val scheme = buildSeededColorScheme(seed, isDark = true, AppearancePalette.TONAL_SPOT)
        assertNotEquals(0xFF000000.toInt(), scheme.surface.toArgb())
        assertNotEquals(0xFF000000.toInt(), scheme.surfaceDim.toArgb())
    }

    // ---------------- 基本性质 ----------------

    @Test
    fun `同一输入结果稳定（纯函数）`() {
        assertSameColors(
            expected = buildSeededColorScheme(seed, false, AppearancePalette.TONAL_SPOT),
            actual = buildSeededColorScheme(seed, false, AppearancePalette.TONAL_SPOT),
        )
    }

    @Test
    fun `九种风格的主色：八种互不相同，Fidelity 与 Content 都等于种子色`() {
        val primaries = AppearancePalette.entries.associateWith {
            buildSeededColorScheme(seed, false, it).primary.toArgb()
        }
        assertEquals("九种风格里应有 8 种不同的主色", 8, primaries.values.toSet().size)
        assertEquals(
            "Fidelity 与 Content 都以种子色作为主色（这是两者的定义，不是 bug）",
            primaries[AppearancePalette.FIDELITY],
            primaries[AppearancePalette.CONTENT],
        )
    }

    @Test
    fun `深色与浅色的主色不同`() {
        val light = buildSeededColorScheme(seed, false, AppearancePalette.TONAL_SPOT)
        val dark = buildSeededColorScheme(seed, isDark = true, AppearancePalette.TONAL_SPOT)
        assertNotEquals(light.primary.toArgb(), dark.primary.toArgb())
    }

    @Test
    fun `fixed 角色也被填充（不是 Material 的默认值）`() {
        /* materialKolor 会映射 primaryFixed 等 16 个角色；自己拼 ColorScheme 时这里会漏。
         * ⚠️ 必须换一个非默认种子：Material 的默认 fixed 角色就是从 0xFF6750A4 推出来的。 */
        val scheme = buildSeededColorScheme(0xFFF44336.toInt(), false, AppearancePalette.TONAL_SPOT)
        val defaults = lightColorScheme()
        assertNotEquals(defaults.primaryFixed.toArgb(), scheme.primaryFixed.toArgb())
        assertNotEquals(defaults.secondaryFixed.toArgb(), scheme.secondaryFixed.toArgb())
        assertNotEquals(defaults.tertiaryFixed.toArgb(), scheme.tertiaryFixed.toArgb())
    }

    // ---------------- 钉住一组标准值 ----------------

    @Test
    fun `标准种子 + TonalSpot 的配色被钉住`() {
        val scheme = buildSeededColorScheme(
            seedArgb = 0xFF6750A4.toInt(),
            isDark = false,
            palette = AppearancePalette.TONAL_SPOT,
            spec = ColorSpec.SpecVersion.SPEC_2025,
        )
        /*
         * ⚠️ 这些值来自 materialKolor 5.0.1（= SukiSU 用的同一版本）。
         * 哪天升级 materialKolor 或改参数导致这里失败，说明**配色会变**：
         * 要么接受并更新期望值，要么把版本钉回去。
         */
        assertEquals("primary", 0xFF655789.toInt(), scheme.primary.toArgb())
        assertEquals("onPrimary", 0xFFFDF7FF.toInt(), scheme.onPrimary.toArgb())
        assertEquals("primaryContainer", 0xFFD4C3FD.toInt(), scheme.primaryContainer.toArgb())
        assertEquals("surface", 0xFFFDF7FE.toInt(), scheme.surface.toArgb())
        assertEquals("surfaceContainer", 0xFFF2ECF5.toInt(), scheme.surfaceContainer.toArgb())
    }

    // ---------------- 辅助 ----------------

    /**
     * 逐字段比较两个 `ColorScheme`。
     *
     * ⚠️ **不能用 `assertEquals(schemeA, schemeB)`**：material3 的 `ColorScheme` 虽然有
     * 可读的 `toString()`，但 `equals` 不是按颜色比较（实测同色也判不等）。
     * 这里通过反射取所有返回 `long`（内联 `Color`）的 getter 逐个比 ARGB。
     */
    private fun assertSameColors(expected: ColorScheme, actual: ColorScheme) {
        val diffs = mutableListOf<String>()
        expected.javaClass.methods
            .filter {
                it.name.startsWith("get") &&
                    it.parameterCount == 0 &&
                    it.returnType == Long::class.javaPrimitiveType
            }
            .sortedBy { it.name }
            .forEach { method ->
                val a = method.invoke(expected) as Long
                val b = method.invoke(actual) as Long
                if (a != b) diffs += "${method.name}: ${a.toHex()} vs ${b.toHex()}"
            }
        assertTrue("配色不一致：\n" + diffs.joinToString("\n"), diffs.isEmpty())
    }

    private fun Long.toHex(): String = "%08X".format(this.toInt())
}

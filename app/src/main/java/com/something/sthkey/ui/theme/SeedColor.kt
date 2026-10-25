package com.something.sthkey.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.dynamiccolor.DynamicColor
import com.materialkolor.dynamiccolor.MaterialDynamicColors
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.DynamicScheme
import com.materialkolor.scheme.SchemeContent
import com.materialkolor.scheme.SchemeExpressive
import com.materialkolor.scheme.SchemeFidelity
import com.materialkolor.scheme.SchemeFruitSalad
import com.materialkolor.scheme.SchemeMonochrome
import com.materialkolor.scheme.SchemeNeutral
import com.materialkolor.scheme.SchemeRainbow
import com.materialkolor.scheme.SchemeTonalSpot
import com.materialkolor.scheme.SchemeVibrant
import com.something.sthkey.domain.ui.AppearancePalette


/**
 * 从**取色种子**推导出整套 Material 3 配色。
 *
 * ============================================================
 * ⚠️⚠️ 为什么用 `material-color-utilities` 而不是 materialKolor
 * ============================================================
 * 用户要求接取色种子。看了一眼 materialKolor:
 *
 * | 版本 | 为什么用不了 |
 * |---|---|
 * | 5.0.1 | 依赖 `kotlin-stdlib:2.4.10` —— 本项目用 AGP 内建 Kotlin（2.2.10），读不了 2.4 的元数据 |
 * | 4.1.1 | 依赖**JetBrains Compose**（`org.jetbrains.compose.*`），和本项目的 AndroidX 混用容易撞版本 |
 *
 * ⚠️ 而 `material-color-utilities-android` 是**纯算法库**:
 * 没有任何 Compose 依赖（只有 kotlin-stdlib + 一个注解库），
 * 所以我们只借它算色，`ColorScheme` 自己拼 —— 版本耦合为零。
 *
 * ⚠️ 算法本身与 materialKolor/Google 一致（同一套 HCT + DynamicScheme），
 * 观感是对得上的。
 *
 * @param seedArgb 种子色（ARGB）
 * @param isDark 深色还是浅色
 * @param palette 色彩风格
 */
@Composable
fun rememberSeededColorScheme(
    seedArgb: Int,
    isDark: Boolean,
    palette: AppearancePalette,
): ColorScheme = remember(seedArgb, isDark, palette) {
    buildSeededColorScheme(seedArgb, isDark, palette)
}

/**
 * 纯函数版（可被单测覆盖，不依赖 Compose 运行时之外的东西）。
 *
 * ⚠️ 明暗判断交给调用方:这里只按 `isDark` 算，
 * 不自己去读系统设置 —— 否则「跟随系统 / 强制浅色」这类选项会被架空。
 */
fun buildSeededColorScheme(
    seedArgb: Int,
    isDark: Boolean,
    palette: AppearancePalette,
): ColorScheme {
    val hct = Hct.fromInt(seedArgb)

    /*
     * ⚠️ 对比度固定 0.0（默认档）。
     * 提高对比度会让整套配色发灰、发硬，Material 官方也是 0 为默认。
     */
    val scheme: DynamicScheme = when (palette) {
        AppearancePalette.TONAL_SPOT -> SchemeTonalSpot(hct, isDark, 0.0, SPEC, PLATFORM)
        AppearancePalette.NEUTRAL -> SchemeNeutral(hct, isDark, 0.0, SPEC, PLATFORM)
        AppearancePalette.VIBRANT -> SchemeVibrant(hct, isDark, 0.0, SPEC, PLATFORM)
        AppearancePalette.EXPRESSIVE -> SchemeExpressive(hct, isDark, 0.0, SPEC, PLATFORM)
        AppearancePalette.RAINBOW -> SchemeRainbow(hct, isDark, 0.0, SPEC, PLATFORM)
        AppearancePalette.FRUIT_SALAD -> SchemeFruitSalad(hct, isDark, 0.0, SPEC, PLATFORM)
        AppearancePalette.MONOCHROME -> SchemeMonochrome(hct, isDark, 0.0, SPEC, PLATFORM)
        AppearancePalette.FIDELITY -> SchemeFidelity(hct, isDark, 0.0, SPEC, PLATFORM)
        AppearancePalette.CONTENT -> SchemeContent(hct, isDark, 0.0, SPEC, PLATFORM)
    }

    val dyn = MaterialDynamicColors()
    fun of(color: DynamicColor): Color = Color(color.getArgb(scheme))

    /*
     * ⚠️⚠️ 必须按明暗选**不同的基础方案**，不能一律 `lightColorScheme()`
     * ============================================================
     * 之前一律用 `lightColorScheme()` 只覆盖我列出来的那些角色，
     * 剩下的（`inverseSurface` / `scrim` / `surfaceTint` 之类）
     * 会保留**浅色主题的默认值** —— 深色模式下就出现"大部分是深的、
     * 个别组件突然是浅的"，用户说的"取色观感非常奇怪"就是这个。
     *
     * ⚠️ 用 `copy()` 而不是重写两个 `xxxColorScheme(...)` 调用:
     * 一次覆盖全部角色，两种明暗共用同一份映射，不会漏。
     */
    val base = if (isDark) darkColorScheme() else lightColorScheme()

    /*
     * ⚠️ 只映射**项目里真正用到的角色**，不是全量 40 多个。
     *
     * ⚠️ 但下面这几个**必须**映射，漏了会退回默认值:
     * `surfaceBright` / `surfaceContainer`（设置行底色）、
     * `surfaceContainerHighest`（开关轨道 / 悬浮胶囊）、
     * `secondaryContainer`（图标方块）。这几处是我们自己的组件在用的。
     */
    return base.copy(
        primary = of(dyn.primary()),
        onPrimary = of(dyn.onPrimary()),
        primaryContainer = of(dyn.primaryContainer()),
        onPrimaryContainer = of(dyn.onPrimaryContainer()),
        inversePrimary = of(dyn.inversePrimary()),

        secondary = of(dyn.secondary()),
        onSecondary = of(dyn.onSecondary()),
        secondaryContainer = of(dyn.secondaryContainer()),
        onSecondaryContainer = of(dyn.onSecondaryContainer()),

        tertiary = of(dyn.tertiary()),
        onTertiary = of(dyn.onTertiary()),
        tertiaryContainer = of(dyn.tertiaryContainer()),
        onTertiaryContainer = of(dyn.onTertiaryContainer()),

        background = of(dyn.background()),
        onBackground = of(dyn.onBackground()),

        surface = of(dyn.surface()),
        onSurface = of(dyn.onSurface()),
        surfaceVariant = of(dyn.surfaceVariant()),
        onSurfaceVariant = of(dyn.onSurfaceVariant()),

        surfaceTint = of(dyn.primary()),
        inverseSurface = of(dyn.inverseSurface()),
        inverseOnSurface = of(dyn.inverseOnSurface()),

        outline = of(dyn.outline()),
        outlineVariant = of(dyn.outlineVariant()),
        scrim = of(dyn.scrim()),

        surfaceBright = of(dyn.surfaceBright()),
        surfaceDim = of(dyn.surfaceDim()),
        surfaceContainer = of(dyn.surfaceContainer()),
        surfaceContainerHigh = of(dyn.surfaceContainerHigh()),
        surfaceContainerHighest = of(dyn.surfaceContainerHighest()),
        surfaceContainerLow = of(dyn.surfaceContainerLow()),
        surfaceContainerLowest = of(dyn.surfaceContainerLowest()),

        error = of(dyn.error()),
        onError = of(dyn.onError()),
        errorContainer = of(dyn.errorContainer()),
        onErrorContainer = of(dyn.onErrorContainer()),
    )
}

/**
 * ⚠️ 用 **2025 版**规范（与 SukiSU 一致）:
 * Material 3 Expressive 之后，materialKolor 5 的默认就是 2025；
 * 用 2021 会和 SukiSU 的色阶差一点点（用户对比截图看出来了）。
 */
private val SPEC = ColorSpec.SpecVersion.SPEC_2025

/** ⚠️ `PHONE` 而不是 `WATCH`:手表版会把对比度整体拉高 */
private val PLATFORM = DynamicScheme.Platform.PHONE

/**
 * 把 `Color` 转成 ARGB Int（存偏好用）。
 *
 * ⚠️ 单独抽出来是因为 `toArgb()` 是 Compose 的扩展，
 * 而偏好层不该依赖 Compose —— 调用方在 UI 层转好再传下去。
 */
fun Color.toSeedArgb(): Int = this.toArgb()

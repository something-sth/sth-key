package com.something.sthkey.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.dynamiccolor.ColorSpec
import com.something.sthkey.domain.ui.AppearancePalette

/**
 * 从**取色种子**推导整套 Material 3 配色。
 *
 * ============================================================
 * 为什么现在直接用 materialKolor（2026-11-21 改）
 * ============================================================
 * 之前是拿 `material-color-utilities`（纯算法库）自己拼 `ColorScheme` ——
 * 因为 materialKolor **5.0.1** 依赖 `kotlin-stdlib 2.4.10`，而当时本项目
 * 用 AGP 内建 Kotlin 2.2.10，读不了 2.4 的元数据（详见 `docs/kotlin-version.md`）。
 *
 * Kotlin 升到 **2.4.21** 之后这个障碍没有了，于是直接用**与 SukiSU 完全相同的库和版本**：
 *
 * | 项 | SukiSU | 我们 |
 * |---|---|---|
 * | 库 | `com.materialkolor:material-kolor:5.0.1` | 同 |
 * | 入口 | `dynamicColorScheme(...)` | 同 |
 * | 对比度 | 0.0（`Contrast.Default`） | 同 |
 * | 平台 | `Platform.Default`（= PHONE） | 不传，用默认 |
 * | 规格 | `specVersion.effectiveFor(style)` | 同（[effectiveFor]） |
 *
 * 这样颜色是**逐位一致**的，而且 AMOLED（surface 纯黑）、`primaryFixed*` 那 16 个
 * 角色、壁纸种子都跟着一起来，不用自己复刻。
 *
 * ⚠️ 仍然保留 [buildSeededColorScheme] 这个**纯函数**（不碰 Compose 运行时），
 * 这样单测可以直接跑，不用起 Activity。
 */

/** 默认规格：2025（Material 3 Expressive 之后的默认，与 SukiSU 一致） */
internal val DEFAULT_SPEC = ColorSpec.SpecVersion.SPEC_2025

/** 对比度固定 0.0：Material 官方默认；调高会让整套配色发硬、发光 */
private const val CONTRAST_LEVEL = 0.0

@Composable
fun rememberSeededColorScheme(
    seedArgb: Int,
    isDark: Boolean,
    palette: AppearancePalette,
    /** 色彩规格（2021 / 2025）；默认 2025 */
    spec: ColorSpec.SpecVersion = DEFAULT_SPEC,
    /** AMOLED：深色下把 surface 系列刷成纯黑 */
    isAmoled: Boolean = false,
): ColorScheme = remember(seedArgb, isDark, palette, spec, isAmoled) {
    buildSeededColorScheme(
        seedArgb = seedArgb,
        isDark = isDark,
        palette = palette,
        spec = spec,
        isAmoled = isAmoled,
    )
}

/**
 * 纯函数版：种子色 → `ColorScheme`。
 *
 * @param seedArgb 种子色（ARGB，必须不透明；`0` 由调用方处理成"跟随壁纸"）
 * @param isDark 深色还是浅色
 * @param palette 色彩风格（映射到 materialKolor 的 [PaletteStyle]）
 * @param spec 色彩规格；**只有 4 种风格支持 2025**，其余会自动回退到 2021（见 [effectiveFor]）
 * @param isAmoled 深色下是否纯黑
 */
fun buildSeededColorScheme(
    seedArgb: Int,
    isDark: Boolean,
    palette: AppearancePalette,
    spec: ColorSpec.SpecVersion = DEFAULT_SPEC,
    isAmoled: Boolean = false,
): ColorScheme {
    val style = palette.toPaletteStyle()
    val scheme = dynamicColorScheme(
        seedColor = Color(seedArgb),
        isDark = isDark,
        isAmoled = isAmoled,
        style = style,
        contrastLevel = CONTRAST_LEVEL,
        specVersion = spec.effectiveFor(style),
    )
    return scheme.withAmoled(isAmoled)
}

/**
 * AMOLED：深色下把 surface 系列**全部**刷成纯黑。
 *
 * ⚠️ materialKolor 自己也处理 `isAmoled`，但**没有把 `surfaceDim` 刷黑**；
 *    SukiSU 在 `ThemeExt.amoledBackground()` 里把 background / surface / surfaceDim /
 *    surfaceContainer 系列一起设黑。这里照 SukiSU 的做法补全，否则"纯黑"下
 *    会有一条 `surfaceDim` 的深灰露出来。
 */
private fun ColorScheme.withAmoled(isAmoled: Boolean): ColorScheme = if (!isAmoled) {
    this
} else {
    copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceDim = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color.Black,
        surfaceContainer = Color.Black,
        surfaceContainerHigh = Color.Black,
        surfaceContainerHighest = Color.Black,
    )
}

/** 我们的风格枚举 → materialKolor 的 `PaletteStyle`（一一对应，改这里要同步两边） */
internal fun AppearancePalette.toPaletteStyle(): PaletteStyle = when (this) {
    AppearancePalette.TONAL_SPOT -> PaletteStyle.TonalSpot
    AppearancePalette.NEUTRAL -> PaletteStyle.Neutral
    AppearancePalette.VIBRANT -> PaletteStyle.Vibrant
    AppearancePalette.EXPRESSIVE -> PaletteStyle.Expressive
    AppearancePalette.RAINBOW -> PaletteStyle.Rainbow
    AppearancePalette.FRUIT_SALAD -> PaletteStyle.FruitSalad
    AppearancePalette.MONOCHROME -> PaletteStyle.Monochrome
    AppearancePalette.FIDELITY -> PaletteStyle.Fidelity
    AppearancePalette.CONTENT -> PaletteStyle.Content
}

/**
 * 哪些风格支持 **2025 规格**。
 *
 * ⚠️ 与 SukiSU 的 `supportsSpec2025` 保持一致：只有 TonalSpot / Neutral / Vibrant / Expressive。
 * 其余 5 种在 2025 规格下没有定义，必须回退到 2021 —— 不回退的话颜色会明显偏掉
 * （之前我们 9 种全用 2025，就是这里与 SukiSU 不一样）。
 */
internal fun PaletteStyle.supportsSpec2025(): Boolean = when (this) {
    PaletteStyle.TonalSpot,
    PaletteStyle.Neutral,
    PaletteStyle.Vibrant,
    PaletteStyle.Expressive,
    -> true

    else -> false
}

/** 请求 2025 但风格不支持时，静默回退到 2021（与 SukiSU 同名同逻辑） */
internal fun ColorSpec.SpecVersion.effectiveFor(style: PaletteStyle): ColorSpec.SpecVersion =
    if (this == ColorSpec.SpecVersion.SPEC_2025 && !style.supportsSpec2025()) {
        ColorSpec.SpecVersion.SPEC_2021
    } else {
        this
    }

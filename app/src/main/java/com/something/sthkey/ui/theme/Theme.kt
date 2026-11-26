package com.something.sthkey.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.materialkolor.dynamiccolor.ColorSpec
import com.something.sthkey.domain.ui.AppearanceColorMode
import com.something.sthkey.domain.ui.AppearancePalette
import com.something.sthkey.domain.ui.ColorSpecOption

/**
 * 应用主题（**只用 Material3**）。
 *
 * ============================================================
 * 2026-11-21：改成 SukiSU 的模型
 * ============================================================
 * | 维度 | 取值 | 谁决定 |
 * |---|---|---|
 * | 明暗 | 跟随系统 / 浅色 / 深色 / 纯黑 | [AppearanceColorMode] |
 * | 主色 | 跟随壁纸（`seedColor == 0`）或指定颜色 | `AppPreferences.seedColor` |
 * | 风格 | 9 种 palette style | [AppearancePalette] |
 * | 规格 | 2021 / 2025 | [ColorSpecOption] |
 *
 * ⚠️ 与旧实现最大的差别：**「跟随壁纸」不再是三种独立模式**，
 * 而是"主色 = 0"这一种状态。这样它有种子（系统的动态主色），
 * **色彩风格和规格也能作用在壁纸上** —— 旧实现里壁纸模式会忽略风格，
 * 与 SukiSU 不一致。
 *
 * ⚠️ API < 31 没有动态取色，此时回退到一个固定种子（[FALLBACK_SEED]），
 * 仍然走同一套推导 —— 而不是像以前那样切到两套写死的配色。
 */
@Composable
fun SthKeyTheme(
    colorMode: AppearanceColorMode = AppearanceColorMode.DEFAULT,
    /**
     * 主色（ARGB）。**0 表示跟随壁纸**。
     *
     * ⚠️ 用 0 当"未设置"而不是 null：偏好层存 Int 更简单，
     * 而 ARGB 里 0 是完全透明的黑，不可能是用户选出来的颜色
     * （取色器给的都是不透明色）。
     */
    seedColor: Int = 0,
    palette: AppearancePalette = AppearancePalette.DEFAULT,
    colorSpec: ColorSpecOption = ColorSpecOption.DEFAULT,
    /** 页面缩放百分比（70..130）；只缩放界面，不影响悬浮窗 */
    uiScalePercent: Int = 100,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = colorMode.isDark(systemDark)
    val effectiveSeed = rememberEffectiveSeed(seedColor, dark)

    val colorScheme = rememberSeededColorScheme(
        seedArgb = effectiveSeed,
        isDark = dark,
        palette = palette,
        spec = colorSpec.toSpecVersion(),
        isAmoled = colorMode.isBlack,
    )

    /*
     * 页面缩放：改 **density** 而不是 fontScale ——
     * 这样 dp 尺寸、文字、图标、间距一起缩放，和 SukiSU 的"页面缩放"观感一致；
     * 只改 fontScale 会变成"字变大、控件不变"，很别扭。
     *
     * ⚠️ 必须在 MaterialTheme **外层**提供：里面的所有 Composable
     * 都要读到新 density。
     */
    val baseDensity = LocalDensity.current
    val scale = (uiScalePercent.coerceIn(70, 130)) / 100f
    val scaledDensity = remember(baseDensity, scale) {
        Density(
            density = baseDensity.density * scale,
            fontScale = baseDensity.fontScale,
        )
    }

    CompositionLocalProvider(LocalDensity provides scaledDensity) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            content = content,
        )
    }
}

/**
 * 真正拿去推导配色的种子色。
 *
 * 优先级（**不要调换顺序**）：
 * 1. 用户选了颜色 → 用那个颜色；
 * 2. 跟随壁纸 + Android 12+ → 取系统动态配色里的 `primary` 当种子；
 * 3. 跟随壁纸 + 更低版本 → [FALLBACK_SEED]。
 *
 * ⚠️ 第 2 步是"**取一个种子**"，不是"直接用系统那套配色" ——
 * 这样色彩风格 / 规格才能参与推导，与 SukiSU 一致。
 *
 * ⚠️ 主题设置页的预览卡、主色块也要用它，否则预览和实际不一致。
 */
@Composable
fun rememberEffectiveSeed(seedColor: Int, isDark: Boolean): Int {
    val context = LocalContext.current
    return remember(seedColor, isDark, context) {
        when {
            seedColor != 0 -> seedColor

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val dynamic = if (isDark) {
                    dynamicDarkColorScheme(context)
                } else {
                    dynamicLightColorScheme(context)
                }
                dynamic.primary.toArgb()
            }

            else -> FALLBACK_SEED
        }
    }
}

/** 域层的规格枚举 → materialKolor 的 `ColorSpec.SpecVersion`（域层不引库） */
internal fun ColorSpecOption.toSpecVersion(): ColorSpec.SpecVersion = when (this) {
    ColorSpecOption.SPEC_2021 -> ColorSpec.SpecVersion.SPEC_2021
    ColorSpecOption.SPEC_2025 -> ColorSpec.SpecVersion.SPEC_2025
}

/**
 * API < 31 且没选主色时的回退种子。
 *
 * ⚠️ 用品牌主色（`LightPrimary` = #3F5F90），而不是随手挑一个粉色：
 * 这类设备的用户看到的仍然是熟悉的那套蓝灰，只是现在由算法推导而不是写死。
 */
private val FALLBACK_SEED = 0xFF3F5F90.toInt()

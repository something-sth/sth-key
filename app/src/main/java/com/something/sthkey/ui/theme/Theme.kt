package com.something.sthkey.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.something.sthkey.domain.ui.AppearanceColorMode
import com.something.sthkey.domain.ui.AppearancePalette


private val DarkColors = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    /*
     * ⚠️ surfaceContainer 系列必须写全，理由见 `Color.kt` 里那段注释:
     * 不写的话用的是 Material3 默认的**紫灰**色，
     * 悬浮底栏的胶囊会变成一大块和蓝灰配色不搭的脏灰色。
     */
    surfaceDim = DarkSurfaceDim,
    surfaceBright = DarkSurfaceBright,
    surfaceContainerLowest = DarkSurfaceContainerLowest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
    inverseSurface = DarkInverseSurface,
    inverseOnSurface = DarkInverseOnSurface,
    error = DarkError,
    onError = DarkOnError,
)

private val LightColors = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    /* ⚠️ 同上:必须写全，见 `Color.kt` 里的说明 */
    surfaceDim = LightSurfaceDim,
    surfaceBright = LightSurfaceBright,
    surfaceContainerLowest = LightSurfaceContainerLowest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
    inverseSurface = LightInverseSurface,
    inverseOnSurface = LightInverseOnSurface,
    error = LightError,
    onError = LightOnError,
)

/**
 * 应用主题（**只用 Material3**）。
 *
 * 用户的原话:
 *
 * > miuix 其实我觉得这个东西只会白白让我们工作量增加，而且半天也没修好，
 * > 你把 miuix 主题删了吧，我们还是使用 material 就好了
 *
 * 所以原来那层 `MiuixTheme` 整套删掉了（组件、依赖、偏好、皮肤开关）。
 *
 * [colorMode] 从偏好读（设置页「外观」里改），缺省见
 * [AppearanceColorMode.DEFAULT]。
 */
@Composable
fun SthKeyTheme(
    colorMode: AppearanceColorMode = AppearanceColorMode.DEFAULT,
    /**
     * 取色种子（ARGB）。**0 表示跟随壁纸 / 未设置**。
     *
     * ⚠️ 用 0 当"未设置"而不是 null:偏好层存 Int 更简单，
     * 而 ARGB 里 0 是完全透明的黑色，不可能是用户选出来的颜色（选色器给的是不透明色）。
     */
    seedColor: Int = 0,
    palette: AppearancePalette = AppearancePalette.DEFAULT,
    content: @Composable () -> Unit,
) {
    /*
     * ⚠️ 先算"到底是不是深色"。
     * 分开算的话（各自调 `isSystemInDarkTheme()`）在极端情况下会不同步。
     */
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (colorMode) {
        AppearanceColorMode.SYSTEM, AppearanceColorMode.MONET_SYSTEM -> systemDark
        AppearanceColorMode.LIGHT, AppearanceColorMode.MONET_LIGHT -> false
        AppearanceColorMode.DARK, AppearanceColorMode.MONET_DARK -> true
    }

    /*
     * ============================================================
     * 配色来源的**优先级**（不要调换顺序）
     * ============================================================
     * | 优先级 | 条件 | 结果 |
     * |---|---|---|
     * | 1 | 用户选了主色（`seedColor != 0`） | 用**那个颜色**推导整套配色 |
     * | 2 | 选了「取色」模式且系统支持（Android 12+） | 跟随壁纸 |
     * | 3 | 其它 | 固定的两套蓝灰配色 |
     *
     * ⚠️ 选定的主色优先于壁纸:用户明确点了一个颜色，
     * 却被壁纸取色盖掉，那是"改了没反应"。
     *
     * ⚠️ 第 1 条**不看** `monet_*` 模式 —— 主色是一个独立的选项
     * （在「主题设置 → 主色」里），与明暗模式无关。
     */
    val dynamicColor = colorMode.id.startsWith("monet_")
    val colorScheme = when {
        seedColor != 0 -> rememberSeededColorScheme(
            seedArgb = seedColor,
            isDark = darkTheme,
            palette = palette,
        )

        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}

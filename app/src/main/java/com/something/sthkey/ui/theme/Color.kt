package com.something.sthkey.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 非动态取色时的兜底配色。
 *
 * Android 12+ 默认使用系统壁纸取色（dynamic color），
 * 这里的颜色只在系统不支持动态取色时生效。
 * 选色偏中性蓝灰，避免和用户壁纸撞色太难看。
 */

// 浅色
val LightPrimary = Color(0xFF3F5F90)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFD6E3FF)
val LightOnPrimaryContainer = Color(0xFF001B3D)
val LightSecondary = Color(0xFF565E71)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFDAE2F9)
val LightOnSecondaryContainer = Color(0xFF131C2B)
val LightTertiary = Color(0xFF715573)
val LightOnTertiary = Color(0xFFFFFFFF)
val LightBackground = Color(0xFFEDEDF6)
val LightOnBackground = Color(0xFF1A1B20)
val LightSurface = Color(0xFFFAF8FE)
val LightOnSurface = Color(0xFF1A1B20)
val LightSurfaceVariant = Color(0xFFE0E2EC)
val LightOnSurfaceVariant = Color(0xFF44474E)
val LightOutline = Color(0xFF74777F)
val LightError = Color(0xFFBA1A1A)
val LightOnError = Color(0xFFFFFFFF)

// 深色
val DarkPrimary = Color(0xFFA9C7FF)
val DarkOnPrimary = Color(0xFF0B305F)
val DarkPrimaryContainer = Color(0xFF264778)
val DarkOnPrimaryContainer = Color(0xFFD6E3FF)
val DarkSecondary = Color(0xFFBEC6DC)
val DarkOnSecondary = Color(0xFF283041)
val DarkSecondaryContainer = Color(0xFF3E4759)
val DarkOnSecondaryContainer = Color(0xFFDAE2F9)
val DarkTertiary = Color(0xFFDEBCDF)
val DarkOnTertiary = Color(0xFF402843)
val DarkBackground = Color(0xFF111318)
val DarkOnBackground = Color(0xFFE2E2E9)
val DarkSurface = Color(0xFF111318)
val DarkOnSurface = Color(0xFFE2E2E9)
val DarkSurfaceVariant = Color(0xFF44474E)
val DarkOnSurfaceVariant = Color(0xFFC4C6D0)
val DarkOutline = Color(0xFF8E9099)
val DarkError = Color(0xFFFFB4AB)
val DarkOnError = Color(0xFF690005)

/* ============================================================
 * surfaceContainer 系列
 * ============================================================
 * ⚠️⚠️ 这一组**必须手写**，不能靠 Material3 的默认值。
 *
 * ⚠️ 默认值是**紫灰色**（#E6E0E9 那一系），而本项目的配色是**蓝灰**。
 * 不写的话会出现两种后果，而且都很难查:
 *
 * | 用到的地方 | 不写时的表现 |
 * |---|---|
 * | 悬浮底栏的胶囊（`surfaceContainerHighest`） | 一大块和页面不搭的脏灰色（用户报的"有块实色背景，很丑"） |
 * | 设置行的底色（`surfaceBright`） | 与页面底色相同，行与行之间的缝隙看不出来 |
 * | 图标方块（`secondaryContainer`） | 这一个已定义，所以没问题 |
 *
 * ⚠️ 明暗两套的**相对关系**必须一致:
 * `Lowest < Low < Container < High < Highest`（浅色时数值越大越深），
 * `surfaceBright` 永远是"最亮的那一档"。
 */
val LightSurfaceDim = Color(0xFFDEDCE4)
val LightSurfaceBright = Color(0xFFFAF8FE)
val LightSurfaceContainerLowest = Color(0xFFFAF8FE)
val LightSurfaceContainerLow = Color(0xFFF2F1F9)
val LightSurfaceContainer = Color(0xFFEDEDF6)
val LightSurfaceContainerHigh = Color(0xFFE7E5EE)
val LightSurfaceContainerHighest = Color(0xFFE1DFE8)
val LightOutlineVariant = Color(0xFFC4C6D0)
val LightInverseSurface = Color(0xFF2F3036)
val LightInverseOnSurface = Color(0xFFF1F0F7)

val DarkSurfaceDim = Color(0xFF111318)
val DarkSurfaceBright = Color(0xFF37393E)
val DarkSurfaceContainerLowest = Color(0xFF0C0E13)
val DarkSurfaceContainerLow = Color(0xFF191C20)
val DarkSurfaceContainer = Color(0xFF1D2024)
val DarkSurfaceContainerHigh = Color(0xFF282A2F)
val DarkSurfaceContainerHighest = Color(0xFF33353A)
val DarkOutlineVariant = Color(0xFF44474E)
val DarkInverseSurface = Color(0xFFE2E2E9)
val DarkInverseOnSurface = Color(0xFF2F3036)
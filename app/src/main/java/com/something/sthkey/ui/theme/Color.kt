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
val LightBackground = Color(0xFFF9F9FF)
val LightOnBackground = Color(0xFF1A1B20)
val LightSurface = Color(0xFFF9F9FF)
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

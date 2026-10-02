package com.something.sthkey.ui.overlay

import androidx.compose.ui.graphics.Color

/**
 * 两个 ARGB 整数之间的颜色插值。
 *
 * ============================================================
 * 为什么单独一个文件，而不是留在 KeyGrid 里
 * ============================================================
 * 两种样式（按键、自定义 Key）的按下动画都要做同一件事：
 * 把某个元素的"未按下色"与"按下色"按动画进度插值。
 * 早先两边各有一份私有实现，而自定义 Key 那份当时还**没有**用于
 * 描边与阴影 —— 于是同一个"颜色渐变"动画在两个样式下表现并不一致。
 *
 * ⚠️ 名字里带 `Blend` 前缀是**必须的**：`lerpColor` 与 Kotlin 标准库
 * `kotlin.comparisons` 里的同名扩展函数冲突，import 之后
 * `Modifier.background(...)` 之类的调用会被解析到那个比较器上，
 * 报出一串"类型不匹配 / 找不到候选"的怪错误。踩过一次，别改回去。
 */

/** 返回 ARGB 整数（给需要"先算完再交给别人"的场景，例如塞进 TextStyle 的阴影色） */
internal fun blendArgb(from: Int, to: Int, fraction: Float): Int {
    val t = fraction.coerceIn(0f, 1f)
    fun channel(shift: Int): Int {
        val a = (from shr shift) and 0xFF
        val b = (to shr shift) and 0xFF
        return (a + (b - a) * t).toInt().coerceIn(0, 255)
    }
    return (channel(24) shl 24) or
        (channel(16) shl 16) or
        (channel(8) shl 8) or
        channel(0)
}

/** 返回 Compose 的 [Color]，给直接绘制的场景 */
internal fun blendColor(from: Int, to: Int, fraction: Float): Color {
    val t = fraction.coerceIn(0f, 1f)
    fun channel(shift: Int): Float {
        val a = (from shr shift) and 0xFF
        val b = (to shr shift) and 0xFF
        return (a + (b - a) * t) / 255f
    }
    return Color(
        red = channel(16),
        green = channel(8),
        blue = channel(0),
        alpha = channel(24),
    )
}

package com.something.sthkey.domain.style

import com.something.sthkey.domain.config.KeyStrokesConfig
import com.something.sthkey.domain.config.TextShadow

/**
 * 按键外观的换算规则（单一真源）。
 *
 * 为什么单独抽出来：
 * 同一套规则有三个使用方 —— 配置页的预览、以后的悬浮窗绘制层、以及将来的其它样式。
 * 如果各写一遍，很容易出现"预览里是圆角、实际悬浮窗是直角""预览透明度 70% 实际 100%"
 * 这类对不上的问题。这里只放**纯计算**，不碰任何 Android 绘制 API，
 * 因此可以被预览和悬浮窗共同使用，也能直接写单元测试。
 */
object KeyStyleResolver {

    /** 键帽底色：颜色 + 透明度百分比 → 0xAARRGGBB */
    fun keyColor(config: KeyStrokesConfig, pressed: Boolean): Int {
        val rgb = if (pressed) config.colors.keyDown else config.colors.keyUp
        val alpha = if (pressed) config.opacity.keyDown else config.opacity.keyUp
        return argb(rgb, alpha)
    }

    /** 按键文字颜色：颜色 + 文字透明度百分比 → 0xAARRGGBB */
    fun textColor(config: KeyStrokesConfig, pressed: Boolean): Int {
        val rgb = if (pressed) config.colors.textDown else config.colors.textUp
        val alpha = if (pressed) config.opacity.textDown else config.opacity.textUp
        return argb(rgb, alpha)
    }

    /**
     * 描边颜色（按状态）。
     *
     * ⚠️ 这里**不再有"跟随样式默认色"的回退**：颜色已经搬进
     * [com.something.sthkey.domain.config.ColorSet]，默认值就是跟随文字色，
     * 由数据本身表达。老配置的颜色在解码时已经迁移过去（见
     * [com.something.sthkey.domain.config.migratedOutlineColors]）。
     */
    fun outlineColor(config: KeyStrokesConfig, pressed: Boolean): Int {
        val rgb = if (pressed) config.colors.outlineDown else config.colors.outlineUp
        val alpha = if (pressed) config.opacity.outlineDown else config.opacity.outlineUp
        return argb(rgb, alpha)
    }

    /** 文字阴影颜色（按状态） */
    fun shadowColor(config: KeyStrokesConfig, pressed: Boolean): Int {
        val rgb = if (pressed) config.colors.shadowDown else config.colors.shadowUp
        val alpha = if (pressed) config.opacity.shadowDown else config.opacity.shadowUp
        return argb(rgb, alpha)
    }

    /** 描边宽度（dp）；未启用描边时返回 0 */
    fun outlineWidth(config: KeyStrokesConfig): Float =
        if (config.outline.enabled) config.outline.width.coerceIn(0.5f, 5f) else 0f

    /**
     * 阴影参数（dp）；未启用时返回 null。
     *
     * 返回 null 而不是 0 尺寸：调用方要据此决定**画不画第二层文字**，
     * 用 0 当哨兵的话还得再判一次"是不是没启用"。
     */
    fun shadowSize(config: KeyStrokesConfig): Float? {
        if (!config.shadow.enabled) return null
        return config.shadow.size.coerceIn(TextShadow.SIZE_MIN, TextShadow.SIZE_MAX)
    }

    /**
     * 键帽圆角半径。
     *
     * 配置里存的是"半径百分比"，按键帽**短边**换算（与旧项目 drawKey 一致）：
     * 50% 就是胶囊/圆形，0% 是直角。
     *
     * @param shortSideDp 键帽短边长度（已换算成 dp）
     */
    fun cornerRadius(config: KeyStrokesConfig, shortSideDp: Float): Float {
        if (!config.cornerRadiusEnabled) return 0f
        val percent = config.cornerRadiusPercent.coerceIn(0f, 50f) / 100f
        return shortSideDp * percent
    }

    /** 把 6 位 RGB 与透明度百分比合成为 ARGB */
    private fun argb(rgb: Long, opacityPercent: Int): Int {
        val alpha = opacityPercent.coerceIn(0, 100) * 255 / 100
        return ((alpha shl 24) or (rgb.toInt() and 0xFFFFFF))
    }
}

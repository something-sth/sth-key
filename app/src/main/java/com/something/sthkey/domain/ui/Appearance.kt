package com.something.sthkey.domain.ui

/**
 * 应用外观相关的可选值。
 *
 * ============================================================
 * ⚠️ 为什么是纯枚举（不碰 Compose）
 * ============================================================
 * | 原因 | 说明 |
 * |---|---|
 * | **纯 Kotlin，不依赖 Android / Compose** | 这样能被 JVM 单测覆盖 |
 * | 每个值带一个**稳定的字符串 id** | ⚠️ 存偏好时绝不能存 `ordinal` —— 枚举以后加成员会整体错位，用户设置会莫名其妙变成别的选项 |
 *
 * ⚠️ 原来这里还有 `BottomBarStyle`（底栏样式）与 `UiMode`（Material / Miuix 皮肤），
 * 它们随 Miuix 一起删掉了（用户决定只用 Material）。
 */

/** 明暗模式 */
enum class AppearanceColorMode(
    val id: String,
    val label: String,
) {
    /**
     * 跟随系统。
     *
     * ⚠️ 默认值。用户没进过「外观」设置时走的就是它 ——
     * 系统深色就深色、系统浅色就浅色，这是最不打扰人的默认。
     */
    SYSTEM("system", "跟随系统"),
    LIGHT("light", "浅色"),
    DARK("dark", "深色"),

    /** 跟随系统 + 壁纸取色（Android 12+；更低版本回落到 [SYSTEM]） */
    MONET_SYSTEM("monet_system", "跟随系统（取色）"),
    MONET_LIGHT("monet_light", "浅色（取色）"),
    MONET_DARK("monet_dark", "深色（取色）"),
    ;

    companion object {
        val DEFAULT: AppearanceColorMode = SYSTEM

        fun fromId(id: String?): AppearanceColorMode =
            entries.firstOrNull { it.id == id } ?: DEFAULT

        /** 取色类（Android 12+ 才有意义） */
        val monetEntries: List<AppearanceColorMode>
            get() = entries.filter { it.id.startsWith("monet_") }
    }
}

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
 * ============================================================
 * 2026-11-21：按 SukiSU 的模型重做
 * ============================================================
 * 原来「明暗」和「是否跟随壁纸取色」是**耦合**成 6 个值的
 * （`system` / `light` / `dark` / `monet_system` / `monet_light` / `monet_dark`），
 * 于是"浅色 + 取色"和"浅色"是两个不同的选项，UI 上也要摆六个。
 *
 * 现在拆成**正交**的两个维度（与 SukiSU 一致）：
 *
 * | 维度 | 值 | 存在哪 |
 * |---|---|---|
 * | 明暗 | 跟随系统 / 浅色 / 深色 / **纯黑（AMOLED）** | 本枚举 |
 * | 是否跟随壁纸 | 主色 = 0 表示跟随壁纸；否则用选中的颜色 | `AppPrefs.seedColor` |
 *
 * ⚠️ **旧 id 的迁移**：[fromId] 会把 `monet_*` 映射到对应的明暗档，
 * 所以老用户升级后"跟随壁纸"由 `seedColor == 0` 继续表达，观感不变。
 * 这些旧 id **只读不写**，界面再也不会产生它们。
 */
enum class AppearanceColorMode(
    val id: String,
    val label: String,
) {
    /**
     * 跟随系统。
     *
     * ⚠️ 默认值。系统深色就深色、系统浅色就浅色，这是最不打扰人的默认。
     */
    SYSTEM("system", "跟随系统"),
    LIGHT("light", "浅色"),
    DARK("dark", "深色"),

    /**
     * 纯黑（AMOLED）。
     *
     * ⚠️ 语义上是"深色 + surface 系列刷成纯黑"，不是第四种明暗。
     * 深色面板上有 OLED 屏的用户会明显省电，也用得多，所以单独给一档。
     */
    BLACK("black", "纯黑"),
    ;

    /** 是否 AMOLED 纯黑 */
    val isBlack: Boolean get() = this == BLACK

    /** 在当前系统明暗下，最终是不是深色 */
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK, BLACK -> true
    }

    companion object {
        val DEFAULT: AppearanceColorMode = SYSTEM

        /**
         * 旧版（明暗 × 取色耦合）的 id → 新档位。
         *
         * ⚠️ 只在 [fromId] 里读，**不再写出去**；老用户升级后
         * "跟随壁纸"由 `seedColor == 0` 表达（见类注释）。
         */
        private val LEGACY_IDS: Map<String, AppearanceColorMode> = mapOf(
            "monet_system" to SYSTEM,
            "monet_light" to LIGHT,
            "monet_dark" to DARK,
        )

        fun fromId(id: String?): AppearanceColorMode =
            entries.firstOrNull { it.id == id } ?: LEGACY_IDS[id] ?: DEFAULT
    }
}

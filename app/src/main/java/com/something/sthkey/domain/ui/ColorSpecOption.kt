package com.something.sthkey.domain.ui

/**
 * 色彩规格（Material 3 的 spec version）。
 *
 * ============================================================
 * 为什么需要这一项
 * ============================================================
 * Material 在 2025 之后改了配色的推导规则（Expressive），同一个种子色
 * 在 2021 / 2025 两套规格下会得到**不一样的一组颜色**。
 *
 * ⚠️ **不是所有风格都支持 2025**：只有 柔和 / 素净 / 鲜艳 / 表现力 这 4 种有 2025 的定义，
 * 其余 5 种（彩虹 / 混合 / 单色 / 还原 / 内容）必须回退到 2021 ——
 * 这个回退在 `ui/theme/SeedColor.kt` 的 `effectiveFor()` 里做，与 SukiSU 一致。
 *
 * ⚠️ id 一旦发布就不要改（偏好里存的是字符串 id，不是 ordinal）。
 */
enum class ColorSpecOption(
    val id: String,
    val label: String,
    val description: String,
) {
    SPEC_2025(
        id = "spec_2025",
        label = "2025",
        description = "Material 3 Expressive 规则；只有部分风格支持，其余自动回退",
    ),
    SPEC_2021(
        id = "spec_2021",
        label = "2021",
        description = "经典 Material 3 规则，所有风格都支持",
    ),
    ;

    companion object {
        val DEFAULT: ColorSpecOption = SPEC_2025

        fun fromId(id: String?): ColorSpecOption =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

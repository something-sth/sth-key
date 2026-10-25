package com.something.sthkey.domain.ui

/**
 * 色彩风格（Material 3 的 palette style）。
 *
 * ============================================================
 * ⚠️ 这次它是**真的会生效**的
 * ============================================================
 * 上一轮做过一版又删掉了，原因是"改了没反应" —— 根因是**没有取色种子**:
 * palette style 只决定"怎么从种子里推导出整套配色"，
 * 没有种子就无从推导，改它自然什么都不变。
 *
 * ⚠️ 现在种子是「主题设置 → 主色」里选的那个颜色（或者跟随壁纸），
 * 所以这一项才有意义。⚠️ 界面上也要跟着做联动:
 * 没选主色（= 跟随壁纸且系统不支持动态取色）时，这一项**不该显示**，
 * 否则又是一次"改了没反应"。
 *
 * ⚠️ id 一旦发布就不要改（偏好里存的是字符串 id，不是 ordinal）。
 */
enum class AppearancePalette(
    val id: String,
    val label: String,
    val description: String,
) {
    TONAL_SPOT(
        id = "tonal_spot",
        label = "柔和",
        description = "Material 3 的默认风格，饱和度适中",
    ),
    NEUTRAL(
        id = "neutral",
        label = "素净",
        description = "接近灰阶，颜色很淡",
    ),
    VIBRANT(
        id = "vibrant",
        label = "鲜艳",
        description = "饱和度最高，色彩最跳",
    ),
    EXPRESSIVE(
        id = "expressive",
        label = "表现力",
        description = "色相跨度大，相邻颜色对比强",
    ),
    RAINBOW(
        id = "rainbow",
        label = "彩虹",
        description = "每个色相都拉开，适合喜欢多彩的",
    ),
    FRUIT_SALAD(
        id = "fruit_salad",
        label = "混合",
        description = "用种子的色相混合出第二、第三色",
    ),
    MONOCHROME(
        id = "monochrome",
        label = "单色",
        description = "只有明暗变化，没有色相变化",
    ),
    FIDELITY(
        id = "fidelity",
        label = "还原",
        description = "尽量贴近你选的那个颜色本身",
    ),
    CONTENT(
        id = "content",
        label = "内容",
        description = "与「还原」相近，但更适合大面积铺色",
    ),
    ;

    companion object {
        val DEFAULT: AppearancePalette = TONAL_SPOT

        fun fromId(id: String?): AppearancePalette =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

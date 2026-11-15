package com.something.sthkey.ui.overlay.touch

/**
 * 触摸区域的**配色**（面板行的小圆点 + 屏幕上的方块共用）。
 *
 * ============================================================
 * ⚠️ 为什么抽出来
 * ============================================================
 * 以前这套颜色只在 [TouchRegionContainerView] 里，用来画屏幕方块。
 * 面板现在要在每行前面点一个**同色小圆点**，让"面板第几行"与
 * "屏幕上第几块"能对上 —— 两处各写一份调色板的话，
 * 改了一个颜色另一个不跟，用户会以为对错了块。
 *
 * ⚠️ 颜色**按列表顺序循环**（第 7 块与第 1 块同色），所以
 * **颜色只能当辅助** —— 真正的标识是序号（见 [touchRegionOrdinal]）。
 */
internal val TOUCH_REGION_PALETTE = intArrayOf(
    0xFF7C4DFF.toInt(),
    0xFF03A9F4.toInt(),
    0xFF4CAF50.toInt(),
    0xFFFF9800.toInt(),
    0xFFE91E63.toInt(),
    0xFF00BCD4.toInt(),
)

/** 第 [index] 块区域的颜色（`index` 从 0 开始，按调色板循环） */
internal fun touchRegionColor(index: Int): Int {
    val size = TOUCH_REGION_PALETTE.size
    return TOUCH_REGION_PALETTE[((index % size) + size) % size]
}

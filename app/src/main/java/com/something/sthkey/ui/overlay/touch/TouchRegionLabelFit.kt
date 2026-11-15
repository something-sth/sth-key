package com.something.sthkey.ui.overlay.touch

/** 适配后的文字与字号（字号单位由调用方给 —— 这里只做比例计算） */
data class FittedRegionLabel(val text: String, val textSize: Float)

/**
 * 屏幕方块里那行文字的**缩放 / 截断**策略（用户确认过，见方案 §11.3）。
 *
 * ============================================================
 * 规则
 * ============================================================
 * | 情况 | 处理 |
 * |---|---|
 * | 宽度够 | 原字号、整段显示 |
 * | 略超 | 按比例缩字号，最小到 [minTextSize]，**不换行** |
 * | 仍超 | 截断成 `…(按键 · A)` —— **保住括号里的组件名** |
 * | 太小 / 放不下 | 交给调用方决定不画（方块极小时只画色块） |
 *
 * ⚠️ **为什么截断要保住括号**:区域名（"按键区域"）用户已经能从颜色/位置
 * 认出来，括号里"绑了谁"才是他真正要确认的信息。整段从右边截的话，
 * 恰恰把最有用的那部分切掉了（`按键区域 (按键 ·` 这种）。
 *
 * ⚠️ 缩字号是**线性**的:文字宽度与字号成正比，所以一次乘比例就够，
 * 不用二分迭代（迭代会让"略超"和"很超"两种情况的字号不连续）。
 *
 * ⚠️ 纯函数 + `measure` 回调:Compose/Canvas 的 `Paint` 在 JVM 单测里
 * 用不了，而"缩到多小、从哪儿截"正是最容易写错的部分 —— 抽出来才能测。
 *
 * @param maxWidth 可用宽度（像素）
 * @param maxTextSize 原字号
 * @param minTextSize 最小字号（再小就不画了）
 * @param measure 量一段文字在给定字号下的宽度
 */
fun fitRegionLabel(
    text: String,
    maxWidth: Float,
    maxTextSize: Float,
    minTextSize: Float,
    measure: (text: String, textSize: Float) -> Float,
): FittedRegionLabel {
    if (text.isEmpty() || maxWidth <= 0f) return FittedRegionLabel("", maxTextSize)

    val baseWidth = measure(text, maxTextSize)
    /* ① 宽度够:原样 */
    if (baseWidth <= maxWidth) return FittedRegionLabel(text, maxTextSize)

    /* ② 按比例缩小（原字号 × 可用宽度 / 实际宽度） */
    if (baseWidth > 0f) {
        val scaled = (maxTextSize * maxWidth / baseWidth).coerceAtLeast(minTextSize)
        if (measure(text, scaled) <= maxWidth) return FittedRegionLabel(text, scaled)
    }

    /* ③ 缩到最小仍放不下:截断，但把括号里的组件名留下 */
    val suffixStart = text.lastIndexOf('(')
    if (suffixStart > 0) {
        val kept = "…" + text.substring(suffixStart)
        if (measure(kept, minTextSize) <= maxWidth) return FittedRegionLabel(kept, minTextSize)
    }

    /*
     * ④ 连括号都放不下:逐字截（从尾巴开始试，第一个放得下的就是最长的）。
     * 极端情况下连省略号都放不下 → 返回空串，调用方就不画（方块太小了）。
     */
    var end = text.length - 1
    while (end > 0) {
        val candidate = text.substring(0, end) + "…"
        if (measure(candidate, minTextSize) <= maxWidth) {
            return FittedRegionLabel(candidate, minTextSize)
        }
        end--
    }
    return if (measure("…", minTextSize) <= maxWidth) {
        FittedRegionLabel("…", minTextSize)
    } else {
        FittedRegionLabel("", minTextSize)
    }
}

package com.something.sthkey.domain.live2d

import com.something.sthkey.domain.config.Live2DBackground
import kotlin.math.max

/**
 * Live2D 纯色背景的**几何**。
 *
 * 三处都要用同一套算法，所以抽成纯函数放 domain 层（不依赖 Android / Compose）：
 *
 * | 用在哪 | 函数 |
 * |---|---|
 * | 悬浮窗尺寸（`OverlayStyleRegistry` 的 `baseSize`） | [windowBase] |
 * | 配置页预览（`ConfigPreview`） | [previewRects] |
 * | 解码时的范围收敛 | [coerce] |
 *
 * 语义（三处必须一致，否则"预览里看着刚好、悬浮窗上却溢出去"）：
 *
 * - 宽度/高度百分比是相对**模型内容矩形**的；
 * - 背景以模型内容中心为轴**对称**伸缩（宽 → 左右一起、高 → 上下一起）；
 * - 背景 ≤100% 时背景比模型小（允许，视觉上是"人物冲出底板"）；
 * - 背景 >100% 时窗口要跟着变大，否则超出的部分会被窗口裁掉。
 */
object Live2DBackgroundGeometry {

    const val WIDTH_MIN = 20
    const val WIDTH_MAX = 200
    const val HEIGHT_MIN = 20
    const val HEIGHT_MAX = 200

    /** 收起越界值：手工改过的配置包、老配置都要经过这里 */
    fun coerce(background: Live2DBackground): Live2DBackground = background.copy(
        widthPercent = background.widthPercent.coerceIn(WIDTH_MIN, WIDTH_MAX),
        heightPercent = background.heightPercent.coerceIn(HEIGHT_MIN, HEIGHT_MAX),
        color = background.color and 0xFFFFFF,
        opacityPercent = background.opacityPercent.coerceIn(0, 100),
    )

    /**
     * 窗口相对"模型内容"的**放大倍数**（关背景时两轴都是 1）。
     *
     * 只放大、不缩小：背景 <100% 时窗口不变 —— 那时底板比模型小，
     * 窗口再缩就会把模型裁掉。
     */
    fun expandX(background: Live2DBackground): Float =
        if (!background.enabled) 1f else max(1f, background.widthPercent / 100f)

    fun expandY(background: Live2DBackground): Float =
        if (!background.enabled) 1f else max(1f, background.heightPercent / 100f)

    /**
     * 悬浮窗的**基础尺寸**（未乘 `uiScale`、未加内边距）。
     *
     * @param aspect       模型内容宽高比（`Live2DModelEntry.contentAspect`）；
     *                     ≤0（还没测到 / 内置模型）时退回设计尺寸
     * @param designWidth  设计画布宽（内置模型用 612）
     * @param designHeight 设计画布高（内置模型用 354）
     */
    fun windowBase(
        aspect: Float,
        designWidth: Float,
        designHeight: Float,
        background: Live2DBackground,
    ): Pair<Float, Float> {
        val baseWidth: Float
        val baseHeight: Float
        if (aspect.isFinite() && aspect > 0f) {
            // 高度保持设计高、宽度按内容比例推 —— 与 OverlayStyles 原有规则一致
            baseWidth = designHeight * aspect
            baseHeight = designHeight
        } else {
            baseWidth = designWidth
            baseHeight = designHeight
        }
        return (baseWidth * expandX(background)) to (baseHeight * expandY(background))
    }

    /**
     * 预览框里的三段尺寸（**只给背景开启时用**）。
     *
     * 计算顺序：先按"并集比例"把整个组合 contain 进预览框，再拆回模型矩形与背景矩形。
     *
     * @param boxWidth/boxHeight 预览框可用尺寸（dp）
     * @param aspect             模型内容宽高比
     * @return [PreviewRects.modelWidth]/[modelHeight] 模型（=100% 基准）矩形，
     *   [PreviewRects.bgWidth]/[bgHeight] 背景矩形，两者**同心**
     */
    fun previewRects(
        boxWidth: Float,
        boxHeight: Float,
        aspect: Float,
        background: Live2DBackground,
    ): PreviewRects {
        val safeAspect = if (aspect.isFinite() && aspect > 0f) aspect else DEFAULT_ASPECT
        val expandX = expandX(background)
        val expandY = expandY(background)
        val unionAspect = safeAspect * expandX / expandY

        val unionWidth: Float
        val unionHeight: Float
        if (boxWidth <= 0f || boxHeight <= 0f) {
            unionWidth = 0f
            unionHeight = 0f
        } else if (boxWidth / boxHeight > unionAspect) {
            // 预览框更宽 → 高度吃满，宽度按并集比例缩
            unionHeight = boxHeight
            unionWidth = boxHeight * unionAspect
        } else {
            unionWidth = boxWidth
            unionHeight = boxWidth / unionAspect
        }

        val modelWidth = unionWidth / expandX
        val modelHeight = unionHeight / expandY
        return PreviewRects(
            modelWidth = modelWidth,
            modelHeight = modelHeight,
            bgWidth = modelWidth * (background.widthPercent / 100f),
            bgHeight = modelHeight * (background.heightPercent / 100f),
        )
    }

    /** 预览用：与 `modelWidth × modelHeight` 同心的背景矩形尺寸 */
    data class PreviewRects(
        val modelWidth: Float,
        val modelHeight: Float,
        val bgWidth: Float,
        val bgHeight: Float,
    )

    /** 取色预览用的不透明度（0..1） */
    fun alphaFraction(background: Live2DBackground): Float =
        background.opacityPercent.coerceIn(0, 100) / 100f

    /**
     * 背景色 → **不透明** ARGB。
     *
     * 颜色字段只存 RGB（可能高 8 位是 0），直接 `Color(rgb)` 会被当成全透明，
     * 预览就会"发虚" —— 这与 `docs/color-picker-plan.md` §3 的既有坑一致。
     */
    fun opaqueArgb(background: Live2DBackground): Int =
        (0xFF shl 24) or (background.color and 0xFFFFFF).toInt()

    /**
     * 基准矩形的宽高比退化值：内置键盘猫的设计画布 612×354。
     *
     * 与 `OverlayStyles` 的 `LIVE2D_DESIGN_*` 是同一组数，但那边是 private，
     * 这里不能反向依赖 UI 层，所以各写一份**并各有单测**（见
     * `Live2DBackgroundGeometryTest`，两条都断言 612/354）。
     */
    private const val DEFAULT_WIDTH = 612f
    private const val DEFAULT_HEIGHT = 354f
    private val DEFAULT_ASPECT = DEFAULT_WIDTH / DEFAULT_HEIGHT
}

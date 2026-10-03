package com.something.sthkey.ui.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import com.something.sthkey.domain.font.bitmap.BitmapFontSpec
import com.something.sthkey.domain.font.bitmap.BitmapFontStore
import com.something.sthkey.domain.font.bitmap.Glyph
import com.something.sthkey.domain.font.bitmap.MaskMode

/**
 * 用**位图字体**（图片字体）画一段文字。
 *
 * ============================================================
 * 为什么单独一个渲染器，而不是接到 Compose 的 Text 上
 * ============================================================
 * Compose 没有"把某个字符替换成一张图"的机制：`SpanStyle` 只能改**样式**
 * （颜色、字号、字体族），改不了字形来源。所以位图字体必须自己画。
 *
 * 好消息是这样反而更简单：**没有字体整形、没有字距调整、没有连字**，
 * 每个字符就是"把图集里的一块贴到某个位置"，颜色与阴影全在绘制时决定。
 *
 * ⚠️ 本阶段**只处理纯 ASCII**。文字里出现别的字符时由调用方回退到矢量字体
 * （判据集中在 [canRenderAsBitmap]，以后做混排时只改那一处）。
 *
 * ============================================================
 * 几何约定：一切以**格子**为准
 * ============================================================
 * Minecraft 的字体网格里一个字符占**固定大小的格子**，墨迹在格子里的
 * 位置由 `ascent` 决定（原版格高 8、ascent 7，格子底边比基线低 1 像素，
 * 那一行留给 `g` `j` `p` `q` `y`）。扫描字形时已经把"墨迹相对基线在哪"
 * 算进了 [Glyph.top]，所以这里逐行往下排就够了——**不需要另算基线**。
 *
 * @param fontId 位图字体 id
 * @param text 要画的文字
 * @param fontSize 目标字号（像素）。规格里的 `height` 会被缩放到这个值
 * @param color 文字颜色；是否真的染色由 [BitmapFontSpec.tinted] 决定
 * @param shadowColor 阴影颜色；`null` 或全透明表示不画
 * @param shadowBlurPx 柔光半径（像素）；0 表示硬阴影
 * @param extraAdvance 额外的字距（相对字号的倍数，0 表示不加）
 */
@Composable
fun BitmapFontText(
    fontId: String,
    text: String,
    fontSize: TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
    shadowColor: Color? = null,
    shadowBlurPx: Float = 0f,
    extraAdvance: Float = 0f,
    /**
     * 是否把字形画成**实心剪影**（只取覆盖率、整块用 [color]）。
     *
     * ⚠️ 抠洞层（`DstOut`）必须开：那一层按源的不透明度擦除，
     * 而图片字体默认"按字形原色绘制" —— 渐变色形的浅色部分不透明度低，
     * 抠出来就是一圈没擦干净的毛边。
     */
    asSolidMask: Boolean = false,
    /**
     * 整体偏移（像素）—— **文字偏移**，由调用方从配置换算好传进来。
     *
     * ============================================================
     * ⚠️ 必须由这里统一应用，不能让调用方"在整层上挪一下"
     * ============================================================
     * 之前在 Key 样式里，文字偏移是在外层 `graphicsLayer.translationX/Y`
     * 上做的，而 `drawShadow` 的**柔光分支没有读那个偏移** —— 结果是：
     *
     * - 硬阴影：恰好跟着挪了（它走 `offsetPx` 那条路）；
     * - **柔光：留在原处不动** —— 用户看到的就是"柔光跟硬阴影一样，
     *   而且阴影不跟着文字走"。
     *
     * 现在偏移在这里统一生效：正文、硬阴影、柔光三条路都从同一个
     * [offsetPx] 出发，不可能再出现"某一条没跟上"。
     */
    offsetXPx: Float = 0f,
    offsetYPx: Float = 0f,
    /**
     * 逐行的**额外字号百分比**（100 = 与 [fontSize] 一样大）；空 = 所有行同号。
     *
     * ============================================================
     * 为什么需要它
     * ============================================================
     * 自定义 Key 的 CPS 模式 3 是"键内两行"，第二行**更小**
     * （原版是主文字 28、CPS 行 22）。而这两行是**同一段文字**里的两个
     * 换行行 —— 它们必须**一次画完**，才能让行高与行距算对。
     *
     * ⚠️ 不能靠"每行各调一次 [BitmapFontText]"来区分字号：
     * 那样每行都是一个独立 Canvas、各自按自身高度居中，
     * **两行会直接重合在一起**（用户实测的"两行挤在同一行"就是这个）。
     */
    lineScalePercents: List<Int> = emptyList(),
    /** 行距系数：1 = 行高即行距，< 1 收紧，> 1 拉开 */
    lineSpacingScale: Float = 1f,
    /**
     * 额外字间距，**相对字号的百分比**（0 = 不动）。
     *
     * 图片字体每个字形的推进宽度是从图集扫出来的，而不同材质包的
     * 字距松紧差别很大 —— 需要一个能整体调整的旋钮。
     */
    letterSpacingPercent: Float = 0f,
    /**
     * 额外行间距，**相对字号的百分比**（0 = 不动）。
     *
     * ⚠️ 收紧有下限：两行压在一起不是"紧凑"而是坏了，
     * 所以实际行距不小于行高的 [MIN_LINE_SPACING_FACTOR] 倍。
     */
    lineSpacingPercent: Float = 0f,
) {
    var font by remember(fontId) { mutableStateOf<BitmapFontStore.BitmapFont?>(null) }

    /*
     * 图集是文件 IO + 逐像素扫描，**不能在组合期同步做**：
     * 一张 512×512 的图集要遍历 26 万像素，同步做会让开场直接卡住。
     * 扫描结果在 [BitmapFontStore] 里按 id 缓存，所以只慢一次。
     */
    LaunchedEffect(fontId) {
        font = BitmapFontStore.load(fontId)
    }

    val loaded = font ?: return
    if (text.isEmpty()) return

    val density = LocalDensity.current
    val fontSizePx = with(density) { fontSize.toPx() }
    if (fontSizePx <= 0f) return

    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    val layout = remember(
        loaded, text, fontSizePx, extraAdvance,
        lineScalePercents, lineSpacingScale, letterSpacingPercent, lineSpacingPercent,
    ) {
        layoutBitmapText(
            font = loaded,
            text = text,
            fontSizePx = fontSizePx,
            extraAdvance = extraAdvance,
            lineScalePercents = lineScalePercents,
            lineSpacingScale = lineSpacingScale,
            letterSpacingPercent = letterSpacingPercent,
            lineSpacingPercent = lineSpacingPercent,
        )
    }

    Canvas(modifier = modifier.onSizeChanged { boxSize = it }) {
        /* 居中：与 Text 的 Alignment.Center 保持同一套观感 */
        val originX = (size.width - layout.widthPx) / 2f
        val originY = (size.height - layout.heightPx) / 2f
        val origin = Offset(originX, originY)

        if (shadowColor != null && shadowColor.alpha > 0f) {
            drawShadow(layout, loaded, origin, shadowColor, shadowBlurPx, asSolidMask, offsetXPx, offsetYPx)
        }
        // 正文也要跟着偏移走，否则"文字挪了、正文没挪"
        drawLayout(
            layout = layout,
            font = loaded,
            origin = Offset(origin.x + offsetXPx, origin.y + offsetYPx),
            color = color,
            asSolidMask = asSolidMask,
        )
    }
}

/*
 * ============================================================
 * 排版（纯计算，可以单测）
 * ============================================================
 */

/** 一个待绘制的字形：从图集取哪一块、画到哪 */
internal data class PlacedGlyph(
    val glyph: Glyph,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

internal data class BitmapTextLayout(
    val lines: List<List<PlacedGlyph>>,
    val widthPx: Float,
    val heightPx: Float,
    /** 名义缩放 = 目标字号 ÷ 规格 height */
    val scale: Float,
    /** 实际使用的倍率（开启像素对齐后会被吸附到整数） */
    val pixelScale: Float,
)

/**
 * 从 `TextStyle` 里安全地取出字距（em 倍数）。
 *
 * ============================================================
 * ⚠️ 为什么必须有这个函数
 * ============================================================
 * `TextStyle.letterSpacing` 没设置时是 **`TextUnit.Unspecified`**，
 * 而 `.value` **是 `Float.NaN`**，不是 0。
 *
 * 直接把 `.value` 传给 [BitmapFontText] 的 `extraAdvance` 会引发 NaN 传播：
 * `penX` 变成 NaN → **后面所有字形全叠在同一位置**。
 * 而且单字符看不出问题（NaN 出现在最后一个字形之后），只有多字符才炸 ——
 * 用户实测的"LMB 三个字母堆成一个 8"就是这个。
 *
 * 所以**取字距这件事必须走这里**，不要在各调用点直接写 `.value`。
 */
internal fun safeLetterSpacing(style: TextStyle): Float =
    style.letterSpacing.value.takeIf { it.isFinite() } ?: 0f

/**
 * 把一段文字排成一批待绘制的字形。
 *
 * 提成 `internal` 纯函数是为了能单测：推进宽度累加、换行、
 * 像素对齐这些都不需要 Compose，而它们恰恰最容易算错。
 */
internal fun layoutBitmapText(
    font: BitmapFontStore.BitmapFont,
    text: String,
    fontSizePx: Float,
    extraAdvance: Float = 0f,
    /** 逐行的字号百分比（100 = 与 [fontSizePx] 同号）；空 = 所有行同号 */
    lineScalePercents: List<Int> = emptyList(),
    /** 行距系数：1 = 行高即行距，< 1 收紧，> 1 拉开 */
    lineSpacingScale: Float = 1f,
    /** 额外字间距，相对字号的百分比（0 = 不动） */
    letterSpacingPercent: Float = 0f,
    /** 额外行间距，相对字号的百分比（0 = 不动） */
    lineSpacingPercent: Float = 0f,
): BitmapTextLayout {
    val spec = font.spec

    /*
     * ============================================================
     * 缩放基准：**参考墨迹高度**，不是声明的高度
     * ============================================================
     * ⚠️ 用户实测的问题是"不同分辨率的图集，初始大小不统一"
     * （128×128 / 256×256 / 512×512 各是一套）。
     *
     * 根因：不同材质包对同一个 `height` 的**留白习惯不同** ——
     * 有的字形占满声明的行高，有的只占六成。按声明高度归一化，
     * 结果就是"同样字号下，这个字体比那个字体大一圈"。
     *
     * 所以改成按**实际墨迹**归一化：量出参考墨迹有多高，再把它映射到
     * [REFERENCE_INK_RATIO] × 字号。
     *
     * 为什么取"典型非下伸字形"的中位数而不是量 `A`：
     * 不同图集里 `A` 这类字符可能只有几十个像素、也可能是空格，
     * 中位数对个别异常图元更稳。
     */
    val referenceInk = referenceInkHeight(font)

    /*
     * ⚠️ 额外字距必须**防 NaN**。
     *
     * 调用方传的是 `TextStyle.letterSpacing.value`，而它没设置时是
     * `TextUnit.Unspecified` —— **`.value` 是 `Float.NaN`**。
     *
     * 一旦漏进来：`advanceExtra = NaN * fontSizePx = NaN`，
     * 于是 `penX += advanceExtra` 之后 `penX` 就是 NaN，
     * **后面所有字形的 x 都变成 NaN → 全部叠在同一个位置**。
     *
     * 症状很有迷惑性：单个字符完全正常（`penX` 变 NaN 发生在最后一个
     * 字形之后，没有下一个了），只有多字符才叠在一起 ——
     * 看起来像"图片字体的字距算错了"，完全想不到是 NaN 传播。
     */
    val safeExtraAdvance = if (extraAdvance.isFinite()) extraAdvance else 0f
    val safeLineScales = lineScalePercents.map { it.coerceIn(1, 1000) / 100f }

    /*
     * 行间距倍数。
     *
     * ⚠️ 下限 [MIN_LINE_SPACING_FACTOR]：把行距调到 0 会让两行**直接重叠**，
     * 那不是"紧凑"而是坏了。收紧到底也留一点余地。
     */
    val effectiveLineSpacing = (1f + lineSpacingPercent / 100f)
        .coerceAtLeast(MIN_LINE_SPACING_FACTOR)

    val lines = mutableListOf<List<PlacedGlyph>>()
    var maxWidth = 0f

    /* 上一行的基线（相对内容顶边）；决定下一行落在哪 */
    var lastBaseline = 0f
    var lastLineHeight = 0f

    /*
     * 第一行的倍率。
     *
     * 整块文字只回报一个 `scale/pixelScale`（硬阴影的默认偏移等地方要用），
     * 而各行倍率可以不同 —— 取第一行的即可：它总是字号最大的那一行
     * （主文字），用它算出来的阴影偏移与视觉主次一致。
     */
    var firstScale = 1f
    var firstPixelScale = 1f

    text.split('\n').forEachIndexed { lineIndex, lineText ->
        val placed = mutableListOf<PlacedGlyph>()
        var penX = 0f

        /* 每一行可以有各自的字号（自定义 Key 的 CPS 行更小） */
        val lineScale = safeLineScales.getOrElse(lineIndex) { 1f }
        val lineFontSizePx = fontSizePx * lineScale

        /*
         * ============================================================
         * 缩放基准：**参考墨迹高度**，不是声明的高度
         * ============================================================
         * ⚠️ 用户实测的问题是"不同分辨率的图集，初始大小不统一"
         * （128×128 / 256×256 / 512×512 各是一套）。
         *
         * 根因：不同材质包对同一个 `height` 的**留白习惯不同** ——
         * 有的字形占满声明的行高，有的只占六成。按声明高度归一化，
         * 结果就是"同样字号下，这个字体比那个字体大一圈"。
         *
         * 所以改成按**实际墨迹**归一化：量出参考墨迹有多高，再把它映射到
         * [REFERENCE_INK_RATIO] × 字号。
         *
         * 为什么取"典型非下伸字形"的中位数而不是量 `A`：
         * 不同图集里 `A` 这类字符可能只有几十个像素、也可能是空格，
         * 中位数对个别异常图元更稳。
         */
        val rawScale = if (referenceInk <= 0f) {
            1f
        } else {
            lineFontSizePx * REFERENCE_INK_RATIO / referenceInk
        }

        /*
         * 像素对齐：把倍率吸附到最近的整数倍。
         *
         * 8×8 的像素字在 35px 下是 4.375 倍——非整数，像素边缘会忽粗忽细。
         * 吸附到 4 倍最锐利。⚠️ 默认关闭：强制整数倍会让字号滑块看起来是坏的
         * （拖了没反应），所以做成可选。
         */
        val pixelScale = if (spec.pixelAlign) {
            kotlin.math.round(rawScale).toInt().coerceAtLeast(1).toFloat()
        } else {
            rawScale
        }

        /* 一行的高度 = 格高 × 倍率 */
        val lineHeight = cellHeightOf(font) * pixelScale

        /*
         * 额外字距 = 字间距百分比 × 字号，换算成**图集像素**。
         *
         * ⚠️ 要除以 `pixelScale`：`penX` 累加的是"图集像素 × pixelScale"，
         * 所以把"渲染后的像素量"换回去才不会二次缩放 ——
         * 否则同一份字间距在大小字号下的视觉宽度不一样。
         */
        val advanceExtra = (
            safeExtraAdvance * lineFontSizePx +
                lineFontSizePx * letterSpacingPercent / 100f
            ) / pixelScale.coerceAtLeast(0.0001f)

        /*
         * 垂直微调：Minecraft 的基线约定与 TTF 行盒的居中假设不同，
         * 差出来的那一点在这里补偿（详见 `BitmapFontSpec.verticalNudge`）。
         *
         * ⚠️ 单位是**渲染后墨迹高度的百分比**，不是"设计像素"。
         * 用设计像素的话它不会跟着 [referenceInk] 的归一化走 ——
         * 同一个百分比在不同图集上推出来的视觉距离不一样，
         * 那就失去了"跨图集统一"的意义。
         */
        val nudge = lineFontSizePx * REFERENCE_INK_RATIO * (spec.verticalNudge / 100f)

        /*
         * ============================================================
         * 基线落点：**跟着上一行的实际基线走**，不是"行号 × 固定行高"
         * ============================================================
         * 字形的 `top` 是相对**基线**的偏移，所以必须知道基线在哪。
         *
         * ⚠️ 早先用的是 `lineIndex * lineHeight`，那在**所有行同号**时
         * 恰好等价；但自定义 Key 的 CPS 行更小，于是两行的基线距离
         * 不足一行高 → **两行直接重合**（用户实测的"两行挤在同一行"）。
         *
         * 现在的规则：本行基线 = 上一行基线 + max(上一行行高, 本行行高) × 行距系数。
         * 取 max 保证**行高不同的两行也不会重叠**；
         * 行距系数 < 1 时允许适当收紧（见 `lineSpacingScale`）。
         */
        val baseline = if (lineIndex == 0) {
            0f
        } else {
            lastBaseline +
                maxOf(lastLineHeight, lineHeight) * lineSpacingScale * effectiveLineSpacing
        }

        if (lineIndex == 0) {
            firstScale = rawScale
            firstPixelScale = pixelScale
        }

        lineText.forEachIndexed { charIndex, char ->            val glyph = font.glyphFor(char.code)

            if (glyph == null) {
                /*
                 * 这个字符不在这套位图字体里（本阶段只有 ASCII）。
                 *
                 * 留一个空格而不是直接跳过：跳过会让后面的字往左挤，
                 * 而"缺字"和"字距不对"是两个不同的问题——
                 * 前者肉眼可见，后者很难察觉。
                 */
                penX += lineFontSizePx * FALLBACK_ADVANCE_RATIO
                return@forEachIndexed
            }

            if (glyph.hasInk) {
                placed += PlacedGlyph(
                    glyph = glyph,
                    x = penX,
                    /* 纵向：扫描时已经把 `top` 算成"相对基线的偏移" */
                    y = baseline + glyph.top * pixelScale + nudge,
                    width = glyph.srcWidth * pixelScale,
                    height = glyph.srcHeight * pixelScale,
                )
            }

            /* ⚠️ 推进宽度来自**扫描结果**，不是格宽 —— 否则所有字符等宽 */
            penX += glyph.advance * pixelScale

            /*
             * ⚠️ 额外字距**只加在字形之间**，最后一个字之后不加。
             *
             * 加了的话整行宽度会多出一段尾巴：这块文字是按宽度居中的，
             * 多出来的尾巴会让它整体**偏左**半个字距 —— 而"偏了一点"
             * 在悬浮窗上很难看出是排版问题还是位置没调好。
             */
            if (charIndex != lineText.lastIndex) penX += advanceExtra
        }

        lines += placed
        if (penX > maxWidth) maxWidth = penX

        lastBaseline = baseline
        lastLineHeight = lineHeight
    }

    /*
     * 整块高度：从内容顶边到最后一行的下沿。
     *
     * 用"最后一行基线 + 它的行高"而不是"行数 × 某一个行高" ——
     * 各行高度可以不同（CPS 行更小），乘固定行高会算错，
     * 而整块高度决定**居中的基准**，算错就会整体偏移。
     */
    val contentHeight = if (lines.isEmpty()) 0f else lastBaseline + lastLineHeight

    return BitmapTextLayout(
        lines = lines,
        widthPx = maxWidth,
        heightPx = contentHeight,
        scale = firstScale,
        pixelScale = firstPixelScale,
    )
}

/** 一个格子的高度（图集像素） */
private fun cellHeightOf(font: BitmapFontStore.BitmapFont): Int {
    val rows = font.spec.grid.rows
    return if (rows > 0) font.atlas.height / rows else font.spec.height
}

/**
 * 参考墨迹高度（图集像素）。
 *
 * ============================================================
 * 为什么按它归一化，而不是按声明的 `height`
 * ============================================================
 * 不同材质包对同一个 `height` 的**留白习惯不同**：有的字形占满声明的行高，
 * 有的只占六成。按声明高度缩放，结果就是"同一个字号下滑块下，
 * 这个字体比那个字体大一圈" —— 这正是用户报的"初始大小不统一"。
 *
 * 按**实际墨迹**归一化就不受留白习惯影响。
 *
 * ⚠️ 取的是**典型的非下伸字形**（墨迹底边落在基线之上、且不在顶端）
 * 的高度中位数，而不是量某个具体字符：
 * - 包含 `g` `y` 这类下伸字形会把参考值抬高，于是整段文字被缩小；
 * - 量单个字符（比如 `A`）则会受那张图集里该字形画法的影响。
 *
 * 没有任何可用的非下伸字形时退回"全部字形的中位数"，
 * 再不行就退回 `height` —— 总之不让它返回 0 把缩放算成 0。
 */
internal fun referenceInkHeight(font: BitmapFontStore.BitmapFont): Float {
    val spec = font.spec
    val cellHeight = cellHeightOf(font).toFloat().coerceAtLeast(1f)

    /*
     * "非下伸"的判据：墨迹底边不低于格内 `ascent` 那一行。
     *
     * 原版 ascii.png：格高 8、ascent 7，非下伸字形占 y 0..6、下伸的到 y 7。
     * 用 `top + inkHeight <= -ascent + cellHeight` 表达就是"没伸到格子最后一行"。
     */
    val nonDescender = font.glyphs.values.filter { glyph ->
        glyph.hasInk && (glyph.top + glyph.srcHeight) <= (-spec.ascent + cellHeight)
    }

    val heights = (nonDescender.ifEmpty { font.glyphs.values.filter { it.hasInk } })
        .map { it.srcHeight.toFloat() }
        .sorted()

    if (heights.isEmpty()) return spec.height.toFloat().coerceAtLeast(1f)

    // 取中位数：比平均值更抗"个别图元特别高/特别矮"
    val mid = heights.size / 2
    val median = if (heights.size % 2 == 0) {
        (heights[mid - 1] + heights[mid]) / 2f
    } else {
        heights[mid]
    }
    return median.coerceAtLeast(1f)
}

/**
 * 参考墨迹高度占字号的比例。
 *
 * ============================================================
 * 为什么是 0.7 而不是 1.0
 * ============================================================
 * 图集里的字形高度**不含行距**，而字号（`fontSize`）对应的是 TTF 的
 * em 尺寸 —— 里面是含上伸、下伸与行距的。把"墨迹高度"直接映射成
 * "整个字号"，图片字体就会比同字号的 TTF 明显大一圈。
 *
 * 大写字高在多数 TTF 里约占 em 的 0.7，取这个值让两者观感接近。
 */
private const val REFERENCE_INK_RATIO = 0.7f

/** 位图字体里没有的字符，按这个比例留白（相对字号） */
private const val FALLBACK_ADVANCE_RATIO = 0.5f

/*
 * ============================================================
 * 绘制
 * ============================================================
 */

/**
 * 画一遍整块文字。
 *
 * @param offsetPx 整体偏移（硬阴影用它挪开一点）
 * @param color 这一次绘制用的颜色
 */
private fun DrawScope.drawLayout(
    layout: BitmapTextLayout,
    font: BitmapFontStore.BitmapFont,
    origin: Offset,
    color: Color,
    offsetXPx: Float = 0f,
    offsetYPx: Float = 0f,
    asSolidMask: Boolean = false,
) {
    val image: ImageBitmap = font.atlasImage
    val spec = font.spec

    /*
     * 染色：Minecraft 的语义是**字形 RGB 与文字颜色相乘**。
     *
     * ============================================================
     * ⚠️ 必须用 `Modulate`，不能用默认的 `SrcIn`
     * ============================================================
     * `ColorFilter.tint(color)` 的默认混合模式是 **`SrcIn`** ——
     * 它**只看 alpha、完全忽略输入的 RGB**，等于"把整块换成纯色"。
     *
     * 对纯白字形看不出区别（白乘任何色 = 那个色），但会**毁掉渐变**：
     * 材质包里常见"由上至下由白到灰"的字体图，用 `SrcIn` 出来就是一块纯色
     * —— 而"能显示渐变"正是引入图片字体的主要理由之一。
     *
     * `Modulate` 是逐通道相乘：
     * - 白色字形 → 变成文字颜色（与原来一样）；
     * - 渐变字形 → **渐变完整保留**，整体被文字颜色染色；
     * - 彩色字形 → 保留色相。
     *
     * ⚠️ LUMINANCE 模式**必须**换色，而且必须用 `SrcIn`：
     * 那种图集是"白底 + 深灰字形"，墨迹本身是**深色**的 ——
     * 相乘只会得到比背景更暗的一团糊。所以那种模式在
     * [BitmapFontStore] 里已经把图集转成"白色墨迹 + alpha 覆盖率"，
     * 这里就可以正常用 `Modulate` 了。
     */
    val filter = when {
        /*
         * 抠洞层：换成**实心纯色**（`SrcIn` 只看 alpha）。
         * 这一层只用来"擦掉"，颜色本身不参与显示。
         */
        asSolidMask -> ColorFilter.tint(color, BlendMode.SrcIn)

        spec.tinted -> ColorFilter.tint(color, BlendMode.Modulate)

        else -> null
    }

    /* 像素画放大必须关滤镜；抗锯齿的图集反过来要用低通 */
    val quality = if (spec.maskMode == MaskMode.LUMINANCE) {
        FilterQuality.Low
    } else {
        FilterQuality.None
    }

    val dx = origin.x + offsetXPx
    val dy = origin.y + offsetYPx

    layout.lines.forEach { line ->
        line.forEach { placed ->
            drawImage(
                image = image,
                srcOffset = IntOffset(placed.glyph.srcX, placed.glyph.srcY),
                srcSize = IntSize(placed.glyph.srcWidth, placed.glyph.srcHeight),
                dstOffset = IntOffset(
                    kotlin.math.round(dx + placed.x).toInt(),
                    kotlin.math.round(dy + placed.y).toInt(),
                ),
                dstSize = IntSize(
                    kotlin.math.round(placed.width).toInt().coerceAtLeast(1),
                    kotlin.math.round(placed.height).toInt().coerceAtLeast(1),
                ),
                filterQuality = quality,
                colorFilter = filter,
            )
        }
    }
}

/**
 * 阴影。
 *
 * ⚠️ 与 TTF 那边的做法**不同**，这是有意的：
 *
 * TTF 那边是"整层只画阴影、再用 `DstOut` 把文字轮廓抠掉"，因为文字是
 * 矢量描边、**笔画之间有缝隙**，直接叠两层会让阴影从缝里透出来。
 *
 * 而位图字形的字形本身就是**实心墨迹**，没有缝；两层叠加的效果就是
 * 想要的那个"阴影在下面"。所以这里直接叠，实现简单得多，
 * 也不必开 `Offscreen` 图层（那个在悬浮窗每帧绘制里是实打实的开销）。
 *
 * 柔光用"多层递减的偏移副本"近似高斯模糊：悬浮窗每帧都在画，
 * 真做高斯模糊太贵。层数固定为 8，开销可控。
 */
private fun DrawScope.drawShadow(
    layout: BitmapTextLayout,
    font: BitmapFontStore.BitmapFont,
    origin: Offset,
    shadowColor: Color,
    blurPx: Float,
    asSolidMask: Boolean = false,
    textOffsetXPx: Float = 0f,
    textOffsetYPx: Float = 0f,
) {
    if (blurPx <= 0f) {
        /* 硬阴影：原版 Minecraft 的做法就是右下各挪一点 */
        drawLayout(
            layout = layout,
            font = font,
            origin = origin,
            color = shadowColor,
            offsetXPx = textOffsetXPx + layout.pixelScale * 0.6f,
            offsetYPx = textOffsetYPx + layout.pixelScale * 0.6f,
            asSolidMask = asSolidMask,
        )
        return
    }

    /*
     * ⚠️ 半径要**放大**，不能直接用滑块值。
     *
     * 滑块范围是 0.5..8，而"柔光"在观感上要比"实心副本挪一点"扩散得多。
     * 只按滑块值铺 8 层的话，范围几乎贴着字形，再加上抠洞那一遍
     * 会把中心擦掉，剩下的光晕细得看不出来 ——
     * 这正是"硬阴影有效、柔光看起来没效果"的第二个原因。
     */
    val r = (blurPx * SOFT_SPREAD).coerceAtMost(MAX_SOFT_RADIUS)

    val offsets = listOf(
        -r to 0f, r to 0f, 0f to -r, 0f to r,
        -r * 0.7f to -r * 0.7f, r * 0.7f to -r * 0.7f,
        -r * 0.7f to r * 0.7f, r * 0.7f to r * 0.7f,
    )
    offsets.forEach { (ox, oy) ->
        /*
         * 每一层都降透明度：8 层全不透明地叠在一起会变成一块实心色块。
         *
         * `/ 2` 而不是 `/ 3`：三份之一叠 8 层也只有约 0.94 的中心不透明度，
         * 但边缘衰减太快、看不出"光晕"。二分之一既柔和又看得见。
         */
        drawLayout(
            layout = layout,
            font = font,
            origin = Offset(origin.x + ox + textOffsetXPx, origin.y + oy + textOffsetYPx),
            color = shadowColor.copy(alpha = shadowColor.alpha / 2f),
            asSolidMask = asSolidMask,
        )
    }
}

/** 柔光半径相对滑块值的放大倍数 */
private const val SOFT_SPREAD = 1.8f

/** 柔光半径上限（像素）：再大就糊成一团，也没必要每帧铺那么多 */
private const val MAX_SOFT_RADIUS = 24f

/**
 * 这段文字能不能用这张位图字体画。
 *
 * 本阶段只支持纯 ASCII，所以判据是"**每个字符都在字体的码点表里**"。
 * 集中在这一处是为了以后做中英混排时只改这里，
 * 而不是去改每一个绘制点。
 */
internal fun canRenderAsBitmap(text: String, spec: BitmapFontSpec): Boolean {
    if (text.isEmpty()) return false
    return text.all { it.code in spec.codePoints }
}



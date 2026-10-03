package com.something.sthkey.domain.font.bitmap

/**
 * 位图字体（Minecraft 风格图片字体）的**纯逻辑**部分。
 *
 * ============================================================
 * 为什么单独放一层、不碰 Android 的 Bitmap
 * ============================================================
 * 这一层要做的事全都是"数格子、扫像素、算宽度"——**没有任何一处需要
 * Android**。而它恰恰是最容易出错、也最值得单测的部分：
 *
 * - 格子切错一格 → **整张图每个字都错位**，但从画面上很难看出是哪错了；
 * - 推进宽度算错 → 所有字等宽或挤在一起；
 * - 遮罩取错 → 整块实心方块（见 [MaskMode.LUMINANCE] 的说明）。
 *
 * 所以这里通过 [PixelSource] 拿像素，测试里塞一个内存数组就能跑，
 * Android 那边只负责"把 PNG 解成 [PixelSource]"。
 *
 * 设计依据见 `docs/bitmap-font.md`。
 */

/*
 * ============================================================
 * 遮罩模式
 * ============================================================
 */

/**
 * 从像素里取"墨水覆盖率"的方式。
 *
 * ============================================================
 * ⚠️ 为什么必须有两种（这是实测踩出来的）
 * ============================================================
 * 常见的 Minecraft 字体图集是**白色字形 + alpha 通道**（原版、基岩版、
 * 各种高清材质包都是）。但**抗锯齿的"平滑字体"不是**：
 *
 * 实测某个材质包的做法是：**白色背景**（RGB 全 255）、字形用
 * 21 级灰度（208~255）画，而 alpha 通道是个**被二值化的遮罩**
 * （`alpha = 255 − 灰度`，且在 2×2 块内完全一致）。
 *
 * 这种图按 [ALPHA] 读会得到：整格 `alpha = 255` → **遮罩全满** →
 * 染成用户颜色后是**一坨实心方块**，那 21 级渐变全丢。
 */
enum class MaskMode(val id: String, val label: String) {
    /** 墨水在 alpha 通道里。适用于白色字形 + 透明的图集 */
    ALPHA("alpha", "透明度"),

    /**
     * 墨水在**亮度**里。
     *
     * 适用于"白底 + 灰度字形"的图。覆盖率 = `255 − 亮度`，
     * 颜色一律由用户指定（图片自身的白/灰被丢弃）。
     */
    LUMINANCE("luminance", "亮度"),

    ;

    companion object {
        val DEFAULT = ALPHA

        fun fromId(id: String?): MaskMode =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/*
 * ============================================================
 * 像素源
 * ============================================================
 */

/**
 * 只读的像素来源（`0xAARRGGBB`）。
 *
 * 把这个接口放在纯逻辑层、由 Android 侧实现，是为了让"数格子算宽度"
 * 这些高风险逻辑能脱离 Bitmap 做单测。
 */
interface PixelSource {
    val width: Int
    val height: Int

    /** 取一个像素，格式 `0xAARRGGBB`；越界返回 0（全透明） */
    fun argb(x: Int, y: Int): Int
}

/** 内存里的像素源（测试与裁剪用） */
class ArrayPixelSource(
    override val width: Int,
    override val height: Int,
    private val pixels: IntArray,
) : PixelSource {
    init {
        require(pixels.size >= width * height) {
            "像素数组太小：需要 ${width * height}，实际 ${pixels.size}"
        }
    }

    override fun argb(x: Int, y: Int): Int =
        if (x < 0 || y < 0 || x >= width || y >= height) 0 else pixels[y * width + x]
}

/*
 * ============================================================
 * 网格规格
 * ============================================================
 */

/**
 * 图集怎么切。
 *
 * ⚠️ **列数/行数由字体的定义决定，不由图片决定**（Minecraft 规范如此）。
 * 一张 512×512 的图可以是 16×16 格（每格 32px），也可以是 32×32 格
 * （每格 16px）——两者都合法，光看图判断不出来。所以这是必填项。
 */
data class AtlasGrid(
    /** 列数；格子宽度 = `图宽 ÷ 列数`，必须整除 */
    val columns: Int,
    /** 行数；格子高度 = `图高 ÷ 行数`，必须整除 */
    val rows: Int,
    /** 第一个字符的码点（原版 ascii.png 是 `0x20` 空格） */
    val firstCodePoint: Int = 0x20,
    /**
     * 第一个字符所在的行/列。
     *
     * ⚠️ **不是 (0,0)**：原版 `ascii.png` 的第 0、1 行是填充位，
     * ASCII 从**第 2 行第 0 列**开始。按 (0,0) 去算会得到
     * "空格那一格有墨水"这种荒谬结果。
     */
    val firstColumn: Int = 0,
    val firstRow: Int = 2,
) {
    /**
     * 一个字符占的格数（按 [BitmapFontSpec.widthInCells] 决定）。
     *
     * 这里只校验最基本的合法性；越界的格子在扫描时会被当成空。
     */
    fun isValid(): Boolean = columns > 0 && rows > 0

    /** 把字符序号摊平成格坐标；`widthInCells` 见 [BitmapFontSpec] */
    fun cellOf(index: Int, widthInCells: Int = 1): Pair<Int, Int> {
        val flat = firstRow * columns + firstColumn + index * widthInCells
        return (flat % columns) to (flat / columns)
    }
}

/*
 * ============================================================
 * 字形
 * ============================================================
 */

/**
 * 一个业已量好的字形。
 *
 * 坐标全部相对**基线**：为了排版时不必再算一遍，这里就直接给出
 * "该把图集的哪一块画到基线的哪一侧"。
 */
data class Glyph(
    val codePoint: Int,
    /** 图集里的源矩形（像素） */
    val srcX: Int,
    val srcY: Int,
    val srcWidth: Int,
    val srcHeight: Int,
    /** 相对基线的目标位置：`baselineX` 是绘制原点，`top` 是顶边（负值在基线上方） */
    val top: Int,
    /** 推进宽度（画完这个字，笔前进多少） */
    val advance: Int,
    /** 有没有实际墨迹；空字形（如空格）不画，但仍有 [advance] */
    val hasInk: Boolean,
)

/*
 * ============================================================
 * 定量：把图集扫成一批字形
 * ============================================================
 */

/**
 * 扫描一张图集，得出每个字符的字形。
 *
 * ============================================================
 * 两条规则直接来自 Minecraft 规范，**不能改**
 * ============================================================
 * 1. **推进宽度 = 墨迹右边界 + 2**（墨迹宽 + 1 像素间隔）。
 *    证据：原版 `█`（全块）在 **8 像素**的格子里推进 **9** 像素；
 *    我们实测原版 ascii.png 的 48 个字符，墨迹宽恰好都比官方宽度表少 1。
 *
 *    ⚠️ 不扫像素而直接用格子宽度的话，`i` 与 `W` 会一样宽，排版很难看。
 *
 * 2. **字形左对齐**：墨迹从格内 `x = 0` 开始，右侧的空白就是间隔。
 *    所以**不裁剪左边**——左边的空白是"左边距"，不是可以省掉的空隙。
 *
 * @param source 图集像素
 * @param codePoints 要扫描的码点（按顺序占用格子）
 * @param spec 网格与遮罩规格
 * @param isBlank 额外的"这一格算不算空"判断；默认只按覆盖率
 * @return 字形表；越界的格子跳过（不产生字形）
 */
fun scanAtlas(
    source: PixelSource,
    codePoints: List<Int>,
    spec: BitmapFontSpec,
    isBlank: (allZeroInk: Boolean) -> Boolean = { it },
): List<Glyph> {
    val grid = spec.grid
    if (!grid.isValid()) return emptyList()

    val cellWidth = source.width / grid.columns
    val cellHeight = source.height / grid.rows
    if (cellWidth <= 0 || cellHeight <= 0) return emptyList()

    return codePoints.mapIndexedNotNull { index, codePoint ->
        val (column, row) = grid.cellOf(index, spec.widthInCells)
        val cellX = column * cellWidth
        val cellY = row * cellHeight

        // 格子完全在图外 → 这个字符没有字形
        if (cellY + cellHeight > source.height || cellX + cellWidth > source.width) {
            return@mapIndexedNotNull null
        }

        scanGlyph(
            source = source,
            codePoint = codePoint,
            cellX = cellX,
            cellY = cellY,
            cellWidth = cellWidth,
            cellHeight = cellHeight,
            spec = spec,
            isBlank = isBlank,
        )
    }
}

/**
 * 扫一个格子。
 *
 * 分出来是为了能在"一个字符跨多格"（[BitmapFontSpec.widthInCells] > 1）时复用。
 */
private fun scanGlyph(
    source: PixelSource,
    codePoint: Int,
    cellX: Int,
    cellY: Int,
    cellWidth: Int,
    cellHeight: Int,
    spec: BitmapFontSpec,
    isBlank: (Boolean) -> Boolean,
): Glyph {
    var minX = Int.MAX_VALUE
    var maxX = Int.MIN_VALUE
    var minY = Int.MAX_VALUE
    var maxY = Int.MIN_VALUE
    var allZero = true

    for (y in 0 until cellHeight) {
        for (x in 0 until cellWidth) {
            val coverage = coverageOf(source.argb(cellX + x, cellY + y), spec.maskMode)
            if (coverage <= ALPHA_THRESHOLD) continue

            /*
             * ⚠️ 允许右边界越出本格。
             *
             * Minecraft 的墨迹**可以超出格子**（`█` 的推进是 9 > 格宽 8），
             * 说明"格子"只是排布单位、不是硬裁剪框。
             * 而我们裁剪时用的是**图集整图**，所以越界的墨迹照样能画出来。
             */
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            allZero = false
        }
    }

    /*
     * 绝对坐标要夹进图集内 —— 墨迹可以越过格子右边界，
     * 但不可能越过整张图（越过了就是没画，画出来会是黑边）。
     */
    val srcX = (cellX + minX).coerceIn(0, source.width - 1)
    val srcY = (cellY + minY).coerceIn(0, source.height - 1)
    val srcRight = (cellX + maxX + 1).coerceIn(srcX + 1, source.width)
    val srcBottom = (cellY + maxY + 1).coerceIn(srcY + 1, source.height)

    // 推进宽度：空白格给 1（规范里"声明了但透明"的格子就是这个下场），
    // 有墨迹则是"最右列 + 2"
    val advance = if (allZero || isBlank(allZero)) {
        BLANK_ADVANCE
    } else {
        (maxX + 2).coerceAtLeast(MIN_ADVANCE)
    }

    return Glyph(
        codePoint = codePoint,
        srcX = srcX,
        srcY = srcY,
        srcWidth = srcRight - srcX,
        srcHeight = srcBottom - srcY,
        /*
         * 纵向：格子顶边在基线上方 `ascent` 像素处。
         *
         * 原版 ascii.png：格高 8、ascent 7 → 格子底边比基线**低 1 像素**，
         * 那一行留给 g/j/p/q/y 这些下伸字母。
         */
        top = minY - spec.ascent,
        advance = advance,
        hasInk = !(allZero || isBlank(allZero)),
    )
}

/*
 * ============================================================
 * 覆盖率
 * ============================================================
 */

/** 低于这个覆盖率一律当透明：图集边缘常有一两个 alpha=1 的杂点 */
const val ALPHA_THRESHOLD = 8

/** 空白格子的推进宽度 */
const val BLANK_ADVANCE = 1

/** 有墨迹时推进宽度的下限 */
const val MIN_ADVANCE = 1

/**
 * 取一个像素的覆盖率（0~255）。
 *
 * [MaskMode.ALPHA]：直接用 alpha 通道。
 * [MaskMode.LUMINANCE]：用 `255 − 亮度`，**忽略 alpha**。
 */
fun coverageOf(argb: Int, mode: MaskMode): Int = when (mode) {
    MaskMode.ALPHA -> (argb ushr 24) and 0xFF

    MaskMode.LUMINANCE -> {
        /*
         * 亮度用整数近似（Rec.601 的整数权重）：
         * `(299R + 587G + 114B) / 1000`，够用且没有浮点误差。
         *
         * 覆盖率 = 255 − 亮度：白底(255) → 0（无墨），黑字(0) → 255（全墨）。
         */
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        255 - (299 * r + 587 * g + 114 * b) / 1000
    }
}

/**
 * 自动猜遮罩模式。
 *
 * 判据来自实测：
 * - 透明图集的绝大多数像素 `alpha = 0`；
 * - "白底灰度"图集的绝大多数像素 `alpha = 255`。
 *
 * ⚠️ 猜错的表现是"整块实心方块"，一眼看不出原因，
 * 所以界面上**必须允许手动改**，不能只靠自动。
 */
fun guessMaskMode(source: PixelSource): MaskMode {
    var total = 0
    var transparent = 0
    val step = 3 // 抽样即可，整图逐像素在 512×512 上没必要

    for (y in 0 until source.height step step) {
        for (x in 0 until source.width step step) {
            total++
            if (((source.argb(x, y) ushr 24) and 0xFF) == 0) transparent++
        }
    }

    if (total == 0) return MaskMode.DEFAULT
    return if (transparent * 2 >= total) MaskMode.ALPHA else MaskMode.LUMINANCE
}

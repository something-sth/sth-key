package com.something.sthkey.domain.font.bitmap

import org.json.JSONObject

/**
 * 一张**位图字体**的规格（可持久化的部分）。
 *
 * ============================================================
 * 为什么"字形"不在这里
 * ============================================================
 * 字形（每个字的墨迹范围与推进宽度）是**从图集扫出来的派生数据**，
 * 由 [scanAtlas] 现算并缓存。放进规格里会有两个问题：
 *
 * 1. 规格要写进配置、跟着配置包导出，而字形是几千个数——配置会变得很臃肿；
 * 2. 它和图集内容必须一致。一旦两处不同源（比如用户换了图但规格没更新），
 *    就会出现"字全部错位"，而且看不出原因。
 *
 * 这里只留"怎么切、怎么读"的参数，字形永远现算。
 */
data class BitmapFontSpec(
    /** 图集在字体目录里的文件名 */
    val atlasFileName: String,

    /** 怎么切格子 */
    val grid: AtlasGrid,

    /**
     * 要取哪些字符。
     *
     * 现阶段只做**纯 ASCII 可打印区**（`0x20..0x7E`）。中文不做：
     * 上万个字无法逐张画，而且矢量字体在缩放下更好。
     * 保留成可配置的列表是为了以后扩（比如把 CP437 的制表符也用上）。
     */
    val codePoints: List<Int> = DEFAULT_ASCII,

    /**
     * 映射到目标的**高度**（输出像素）。
     *
     * 对应 Minecraft 的 `height` 字段：字形按 `height ÷ 格高` 缩放。
     * 原版 ascii.png 是 8 像素格、height 省略即 8 → 1:1。
     */
    val height: Int = 8,

    /**
     * 格子顶边在基线上方多少像素。
     *
     * 对应 Minecraft 的 `ascent`。原版是 7（格高 8），
     * 所以格子底边比基线**低 1 像素**，那一行留给下伸字母。
     *
     * ⚠️ 允许大于 [height]（图标字体的常见做法），所以不校验这个关系。
     */
    val ascent: Int = 7,

    /** 怎么取遮罩；见 [MaskMode] 的说明（有两种，不能只有一个） */
    val maskMode: MaskMode = MaskMode.DEFAULT,

    /**
     * 一个字符横跨几个格子。
     *
     * Minecraft 规范里一个字符**只占一格**，所以恒为 1；
     * 保留这个字段是为了让 [AtlasGrid.cellOf] 的语义完整，
     * 以后要吃"宽字符跨格"的图集时不用改结构。
     */
    val widthInCells: Int = 1,

    /*
     * ============================================================
     * 渲染相关
     * ============================================================
     */

    /**
     * 缩放是否吸附到**整数倍**。
     *
     * 8×8 的像素字在 35px 下显示时倍率是 4.375——**非整数**，
     * 像素边缘会变粗变糊、粗细不均。开启后吸附到最近的整数倍，最锐利。
     *
     * ⚠️ 默认关闭。强制整数倍会让字号滑块看起来是坏的（拖了没反应），
     * 所以做成可选。
     */
    val pixelAlign: Boolean = false,

    /**
     * 是否让颜色跟随配置（也就是"染色"）。
     *
     * Minecraft 的语义是**字形 RGB 与文字颜色相乘**：
     * - 白色字形 → 完全跟随「文字颜色」；
     * - 彩色字形 → 保留色相；
     * - 黑色字形 → 永远黑。
     *
     * 所以对绝大多数图集（白色字形）来说，开启染色就是用户想要的。
     * 关掉则保留图集原色——给"画的就是彩色图案"的图集用。
     */
    val tinted: Boolean = true,

    /**
     * 垂直微调，单位是**渲染后墨迹高度的百分比**。
     *
     * ============================================================
     * 为什么需要它
     * ============================================================
     * Minecraft 的基线约定是"格子底边比基线低 1 像素"（格高 8、ascent 7），
     * 而 TTF 文字在一个 `lineHeight = fontSize` 的行盒里是按**字体自身的
     * 升降部**居中的。两者对"文字该落在哪"的假设不同，于是图片字体看起来
     * 会**整体偏上**一点。
     *
     * 正值往下挪。默认给一点点正值来抵消那个偏差。
     *
     * ⚠️ 这个数是**观感校准**，不是从规范里推出来的，所以做成可调参数：
     * 以后要接"字体微调"界面时，改的就是它。
     */
    val verticalNudge: Float = DEFAULT_VERTICAL_NUDGE,
) {
    /** 图集切出来是不是整数格；除不尽就必须报错，否则**每个字都会错位** */
    fun cellSizeOf(atlasWidth: Int, atlasHeight: Int): Pair<Int, Int>? {
        if (!grid.isValid()) return null
        if (atlasWidth % grid.columns != 0) return null
        if (atlasHeight % grid.rows != 0) return null
        val cellWidth = atlasWidth / grid.columns
        val cellHeight = atlasHeight / grid.rows
        if (cellWidth <= 0 || cellHeight <= 0) return null
        return cellWidth to cellHeight
    }

    /**
     * 字形高度相对 [height] 的缩放倍率。
     *
     * 渲染时 `字号 = height × 缩放`，而图集里的格子是 `格高 × 缩放`。
     */
    fun scaleFor(cellHeight: Int): Float =
        if (cellHeight <= 0) 1f else height.toFloat() / cellHeight

    /*
     * ============================================================
     * 持久化
     * ============================================================
     * 手写 JSON 而不是用序列化库：这个库整体就是这么做的
     * （见 data/config 的说明），保持一种方式，避免多一套约定。
     */

    fun toJson(): JSONObject = JSONObject().apply {
        put("atlasFileName", atlasFileName)
        put("columns", grid.columns)
        put("rows", grid.rows)
        put("firstCodePoint", grid.firstCodePoint)
        put("firstColumn", grid.firstColumn)
        put("firstRow", grid.firstRow)
        put("codePoints", codePoints.joinToString(","))
        put("height", height)
        put("ascent", ascent)
        put("maskMode", maskMode.id)
        put("widthInCells", widthInCells)
        put("pixelAlign", pixelAlign)
        put("tinted", tinted)
        put("verticalNudge", verticalNudge.toDouble())
    }

    companion object {
        /** 纯 ASCII 可打印区：`0x20`(空格) ~ `0x7E`(`~`)，共 95 个 */
        val DEFAULT_ASCII: List<Int> = (0x20..0x7E).toList()

        const val DEFAULT_HEIGHT = 8
        const val DEFAULT_ASCENT = 7

        /**
         * 默认的垂直微调（渲染后墨迹高度的百分比）。
         *
         * ============================================================
         * 这个数是怎么来的（实测 + 推导，不是试出来的）
         * ============================================================
         * 对四张图集实测：**非下伸字形的墨迹高度 = 格高的 7/8**，
         * 且基线在格内 `格高 − 1` 那行（即墨迹底边就是基线、
         * 基线之下留 1 行给下伸字母）。四种分辨率一致。
         *
         * 于是图片字体的墨迹box是：基线之上 `7/8 × 格高`、之下 `1/8 × 格高`。
         *
         * TTF 那边，`lineHeight = fontSize` 的行盒里文字按字体升降部居中，
         * 大写字高约 `0.7 × fontSize`、下伸约 `0.2 × fontSize` ——
         * 它的墨迹**更靠上**。
         *
         * 两者都居中放进行盒时，基线位置之差：
         * ``` 
         * TTF  : fontSize/2 + 0.35×fontSize = 0.85 × fontSize
         * 位图 : 7/16 × 格高 + 7/8 × 格高 = 7/16 × (1/0.875) × fontSize ≈ 0.5 × fontSize
         * ```
         * 差值约 `0.1 × fontSize`，而渲染后墨迹高度是 `0.7 × fontSize` →
         * **约 14%**。
         *
         * ⚠️ 但 TTF 的"下伸 0.2em"是**上限**而不是常规文字的实际情况
         * （`LMB`、`W` 这类键名根本没有下伸字母），按 14% 补偿会推过头。
         * 折中取 **7.5%**。
         *
         * 这是**观感校准**，不可能有唯一正确值 —— 真机上还要微调的话，
         * 以后接"字体微调"界面时改的就是这个数。
         */
        const val DEFAULT_VERTICAL_NUDGE = 7.5f

        /**
         * 从 JSON 读回。
         *
         * 任何字段缺失/非法都**回落到默认值**而不是抛异常：一份手改坏的
         * 配置只该少几个属性，不该整份读不出来（与项目其它解码器一致）。
         */
        fun fromJson(json: JSONObject, atlasFileName: String): BitmapFontSpec {
            val columns = json.optInt("columns", 16).coerceAtLeast(1)
            val rows = json.optInt("rows", 16).coerceAtLeast(1)

            return BitmapFontSpec(
                atlasFileName = atlasFileName,
                grid = AtlasGrid(
                    columns = columns,
                    rows = rows,
                    firstCodePoint = json.optInt("firstCodePoint", 0x20),
                    firstColumn = json.optInt("firstColumn", 0),
                    firstRow = json.optInt("firstRow", 2),
                ),
                codePoints = decodeCodePoints(json.optString("codePoints"))
                    ?: DEFAULT_ASCII,
                height = json.optInt("height", DEFAULT_HEIGHT).coerceIn(1, 256),
                /*
                 * ascent 不夹到 height 以内：规范允许 ascent > height
                 * （图标字体就是这么用的），夹了反而会破坏那种图集。
                 */
                ascent = json.optInt("ascent", DEFAULT_ASCENT).coerceIn(-64, 256),
                maskMode = MaskMode.fromId(json.optString("maskMode")),
                widthInCells = json.optInt("widthInCells", 1).coerceIn(1, 8),
                pixelAlign = json.optBoolean("pixelAlign", false),
                tinted = json.optBoolean("tinted", true),
                verticalNudge = json.optDouble(
                    "verticalNudge",
                    DEFAULT_VERTICAL_NUDGE.toDouble(),
                ).toFloat().coerceIn(-50f, 50f),
            )
        }

        /** 逗号分隔的码点表；空或全非法时返回 null，由调用方回落到默认 */
        private fun decodeCodePoints(raw: String): List<Int>? {
            if (raw.isBlank()) return null
            val list = raw.split(',').mapNotNull { it.trim().toIntOrNull() }
                .filter { it in 0..0x10FFFF }
            return list.takeIf { it.isNotEmpty() }
        }
    }
}

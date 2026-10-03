package com.something.sthkey.domain.font.bitmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 位图字体图集解析的测试。
 *
 * ============================================================
 * 为什么这些断言值得写
 * ============================================================
 * 这一层全是"数格子、扫像素、算宽度"，而它出错的表现**极难定位**：
 *
 * - **格子切错一格** → 整张图每个字都错位，但画面上看不出是哪一步错的；
 * - **行偏移算错**（ASCII 从第 0 行还是第 2 行开始）→ 得到"空格那格有墨水"
 *   这种荒谬结果，很容易误判成"这张图不是这个格式"；
 * - **推进宽度算错** → 所有字等宽（`i` 和 `W` 一样宽），或者全挤在一起。
 *
 * 这些都是纯计算，能在这里穷举；放到界面上就只能靠肉眼，等于没保证。
 *
 * ============================================================
 * 测试用的图集是"合成"的，但**尺寸与规格照抄原版**
 * ============================================================
 * 原版 `ascii.png` 是 128×128、16×16 格、每格 8×8，ASCII 从第 2 行第 0 列开始。
 * 这里按同样的规格画一张可控的图，于是"第 0、1 行是填充"、
 * "格子怎么切"、"推进怎么算"全都能被精确断言。
 *
 * 真实原版图的宽度我已逐格核对过（见 `docs/bitmap-font.md` 第 2.3 节），
 * 结论与这里的断言一致：`!`=2、`I`=4、`A`=6、`@`=7、`~`=7。
 */
class BitmapFontAtlasTest {

    /*
     * ============================================================
     * 造图工具
     * ============================================================
     */

    private class AtlasBuilder(val size: Int = 128, val cell: Int = 8) {
        val pixels = IntArray(size * size)

        fun set(x: Int, y: Int, argb: Int = WHITE) {
            if (x in 0 until size && y in 0 until size) pixels[y * size + x] = argb
        }

        /** 在某个格子里画一个实心矩形（相对格子左上角） */
        fun rect(
            column: Int,
            row: Int,
            left: Int,
            top: Int,
            width: Int,
            height: Int,
            argb: Int = WHITE,
        ) {
            for (y in top until top + height) {
                for (x in left until left + width) {
                    set(column * cell + x, row * cell + y, argb)
                }
            }
        }

        fun build(): PixelSource = ArrayPixelSource(size, size, pixels)
    }

    /** 把码点换算成它在该图集里的格坐标（与原版 ascii.png 一致） */
    private fun cellOf(code: Int): Pair<Int, Int> {
        val index = code - 0x20
        return (index % 16) to (2 + index / 16)
    }

    /**
     * 造一张"字母宽度已知"的图集。
     *
     * 每个字符画一条**实心横条**，宽度按原版官方宽度表 − 1（即墨迹宽）：
     * `!` 1px、`I` 3px、`A` 5px、`~` 6px…… 于是推进宽度应当等于官方值。
     */
    private fun buildKnownWidthAtlas(): PixelSource {
        val b = AtlasBuilder()

        // 空格：整格留空
        // 其余字符按"官方推进宽度 − 1"画实心条
        val inkWidths = mapOf(
            0x21 to 1, // !  推进 2
            0x22 to 4, // "  推进 5
            0x30 to 5, // 0  推进 6
            0x41 to 5, // A  推进 6
            0x49 to 3, // I  推进 4（比 A 窄，考的是"不能都按格宽算"）
            0x40 to 6, // @  推进 7
            0x7E to 6, // ~  推进 7
            0x69 to 1, // i  推进 2
            0x6C to 2, // l  推进 3
        )

        inkWidths.forEach { (code, width) ->
            val (column, row) = cellOf(code)
            b.rect(column, row, left = 0, top = 0, width = width, height = 7)
        }

        /*
         * 故意在第 0、1 行也画东西。
         *
         * 原版 `ascii.png` 的第 0、1 行**不是 ASCII**（是填充位，图里画的是
         * 控制符图形）。如果解析时行偏移算错、把第 0 行当成 ASCII 起点，
         * 那么 `space` 会落在有墨迹的格子上 —— 下面的断言会立刻抓到。
         */
        b.rect(column = 3, row = 0, left = 0, top = 0, width = 5, height = 7)
        b.rect(column = 7, row = 1, left = 0, top = 0, width = 5, height = 7)

        return b.build()
    }

    private fun spec(
        maskMode: MaskMode = MaskMode.ALPHA,
        ascent: Int = 7,
        height: Int = 8,
    ) = BitmapFontSpec(
        atlasFileName = "ascii.png",
        grid = AtlasGrid(columns = 16, rows = 16, firstCodePoint = 0x20, firstColumn = 0, firstRow = 2),
        height = height,
        ascent = ascent,
        maskMode = maskMode,
    )

    private fun scan(source: PixelSource, spec: BitmapFontSpec = spec()) =
        scanAtlas(source, spec.codePoints, spec).associateBy { it.codePoint }

    /*
     * ============================================================
     * 格子切分
     * ============================================================
     */

    @Test
    fun `16x16 的图每格 8x8`() {
        assertEquals(8 to 8, spec().cellSizeOf(128, 128))
        assertEquals(16 to 16, spec().cellSizeOf(256, 256))
        assertEquals(32 to 32, spec().cellSizeOf(512, 512))
    }

    /**
     * ⚠️ 除不尽必须**拒绝**，不能凑合。
     *
     * 图宽不是列数的整数倍时，格子会跨在字形之间 ——
     * 表现是**每个字都被切错、还互相粘连**，而画面上一眼看不出是切分问题。
     */
    @Test
    fun `图宽除不尽列数时拒绝解析`() {
        assertNull(spec().cellSizeOf(130, 128))
        assertNull(spec().cellSizeOf(128, 130))
    }

    /* ============================================================
     * 行偏移：ASCII 从第 2 行开始
     * ============================================================
     */

    /**
     * ⚠️ 这条是整个解析最容易错的地方。
     *
     * 原版 `ascii.png` 的第 0、1 行是填充位，ASCII 从**第 2 行第 0 列**开始。
     * 测试图在第 0、1 行故意画了东西：行偏移算错时 `space` 会落到有墨迹的格子，
     * 这条断言就会失败。
     */
    @Test
    fun `空格落在第 2 行第 0 列而且没有墨迹`() {
        val glyphs = scan(buildKnownWidthAtlas())
        val space = glyphs[0x20]

        assertNotNull("空格应当有字形（只是没墨迹）", space)
        assertFalse("空格不该有墨迹 —— 有的话说明行偏移算错了", space!!.hasInk)
    }

    @Test
    fun `叹号落在第 2 行第 1 列`() {
        val glyphs = scan(buildKnownWidthAtlas())
        val bang = glyphs[0x21]!!

        assertTrue(bang.hasInk)
        assertEquals("叹号那一格只有 1 像素宽的墨迹", 1, bang.srcWidth)
    }

    /* ============================================================
     * 推进宽度 = 墨迹宽 + 1
     * ============================================================
     */

    /**
     * ⚠️ 本测试的重点：**推进宽度必须扫描像素**，不能直接用格子宽度。
     *
     * 下面每个字符的推进值都取自 Minecraft Wiki 的官方宽度表，
     * 而它们在 8 像素的格子里墨迹宽度各不相同。
     * 如果实现改成"推进 = 格宽"，这些断言会全部失败。
     */
    @Test
    fun `推进宽度按官方宽度表`() {
        val glyphs = scan(buildKnownWidthAtlas())

        // 官方表：! =2、i =2、l =3、I =4、A =6、0 =6、@ =7、~ =7
        val expected = mapOf(
            0x21 to 2, // !
            0x69 to 2, // i
            0x6C to 3, // l
            0x49 to 4, // I（比 A 窄）
            0x41 to 6, // A
            0x30 to 6, // 0
            0x40 to 7, // @
            0x7E to 7, // ~
        )

        expected.forEach { (code, advance) ->
            assertEquals(
                "码点 0x${code.toString(16)} 的推进宽度不对",
                advance,
                glyphs[code]?.advance,
            )
        }
    }

    /**
     * 空格：原版在图里给它留了一个**完全透明**的格子，
     * 所以扫描只能得到"无墨迹"。它的推进宽度必须**另外给**
     * （原版为此专门挂了一个 `space` provider，`" ": 4`）。
     */
    @Test
    fun `空格的推进宽度不是从图里扫出来的`() {
        val glyphs = scan(buildKnownWidthAtlas())

        assertEquals("空白格只能扫出下限值", BLANK_ADVANCE, glyphs[0x20]!!.advance)
    }

    /**
     * 字形**左对齐**：墨迹从格内 x=0 开始，右侧空白就是间隔。
     *
     * 所以源矩形的 x 必须等于格子的左边界——**不能**去裁掉左边的空白，
     * 那是"左边距"，裁了字形会贴到前一个字符上。
     */
    @Test
    fun `字形不裁剪左边距`() {
        val source = buildKnownWidthAtlas()
        val glyphs = scan(source)
        val (column, _) = cellOf(0x41) // 'A'

        val a = glyphs[0x41]!!
        assertEquals("源矩形应当从格子左边界开始", column * 8, a.srcX)
    }

    /* ============================================================
     * 纵向：基线
     * ============================================================
     */

    /**
     * 格子顶边在基线上方 `ascent` 像素处。
     *
     * 原版 `ascii.png`：格高 8、ascent 7 → 格子底边比基线**低 1 像素**，
     * 那一行留给 `g` `j` `p` `q` `y` 这些下伸字母。
     */
    @Test
    fun `格顶边相对基线的位置由 ascent 决定`() {
        // 测试图的墨迹从格内 y=0 开始，高 7 像素
        val glyphs = scan(buildKnownWidthAtlas(), spec(ascent = 7))
        assertEquals("顶边应在基线上方 7 像素", -7, glyphs[0x41]!!.top)

        // ascent 改小，顶边就下移
        val lower = scan(buildKnownWidthAtlas(), spec(ascent = 5))
        assertEquals(-5, lower[0x41]!!.top)
    }

    /**
     * `ascent` 允许大于 `height`（图标字体就靠这个把图案顶到基线之上）。
     *
     * ⚠️ 这条测的是"**渲染时不去夹** `ascent ≤ height`"。
     *
     * 规范明确允许 `ascent > height`（ItemsAdder 文档里 `height: 8, ascent: 9`
     * 是常见写法），所以任何"顺手把它夹进 height"的校验都会破坏那种图集。
     * 有个第三方库的文档声称"ascent 不能大于 height"，那是那个库自己的限制，
     * 不是规范。
     *
     * 之所以不测 `fromJson`：`org.json` 在本地单测里是空壳（见项目说明），
     * 那种断言只能靠读代码。这里改测**纯逻辑**部分。
     */
    @Test
    fun `ascent 大于 height 时不夹取`() {
        val spec = spec(ascent = 12, height = 8)

        // 墨迹从格内 y=0 开始 → 顶边应当在基线上方 12 像素，而不是被夹到 8
        val glyphs = scan(buildKnownWidthAtlas(), spec)
        assertEquals(-12, glyphs[0x41]!!.top)
    }

    /* ============================================================
     * 两种遮罩模式
     * ============================================================
     */

    /**
     * ⚠️ 这条钉的是"平滑字体渲染成白块"那个坑。
     *
     * 实测某个材质包的做法是**白底 + 灰度字形**，而 alpha 通道是个
     * 被二值化的遮罩（整格 `alpha = 255`）。按 [MaskMode.ALPHA] 读会得到
     * "遮罩全满"，染成用户颜色后是**一坨实心方块**。
     */
    @Test
    fun `白底灰度的图必须用亮度模式`() {
        // 白底：alpha 全 255、RGB 全 255
        val white = 0xFFFFFFFF.toInt()
        // 字形：alpha 也是 255，但 RGB 是深灰
        val grey = 0xFF606060.toInt()

        val b = AtlasBuilder()
        // 整张图填白
        for (y in 0 until 128) for (x in 0 until 128) b.set(x, y, white)
        // 'A' 的格子里画一条深灰横条
        val (column, row) = cellOf(0x41)
        b.rect(column, row, left = 0, top = 0, width = 5, height = 7, argb = grey)
        val source = b.build()

        // 按透明度读：整格都是"墨水"（因为 alpha 全是 255）→ 错
        val byAlpha = scan(source, spec(maskMode = MaskMode.ALPHA))[0x41]!!
        assertEquals("透明度模式下整格都被当成墨迹", 8, byAlpha.srcWidth)

        // 按亮度读：只有那条深灰是墨水 → 对
        val byLuminance = scan(source, spec(maskMode = MaskMode.LUMINANCE))[0x41]!!
        assertEquals("亮度模式应当只认出那条深灰", 5, byLuminance.srcWidth)
    }

    @Test
    fun `透明图应当被判为透明度模式`() {
        assertEquals(MaskMode.ALPHA, guessMaskMode(buildKnownWidthAtlas()))
    }

    @Test
    fun `白底图应当被判为亮度模式`() {
        val b = AtlasBuilder()
        for (y in 0 until 128) for (x in 0 until 128) b.set(x, y, 0xFFFFFFFF.toInt())
        assertEquals(MaskMode.LUMINANCE, guessMaskMode(b.build()))
    }

    @Test
    fun `覆盖率计算`() {
        // 透明
        assertEquals(0, coverageOf(0x00000000, MaskMode.ALPHA))
        assertEquals(255, coverageOf(0xFFFFFFFF.toInt(), MaskMode.ALPHA))
        // 半透明：只看 alpha
        assertEquals(128, coverageOf(0x80FF0000.toInt(), MaskMode.ALPHA))

        // 亮度模式：白 → 0 覆盖率，黑 → 满
        assertEquals(0, coverageOf(0xFFFFFFFF.toInt(), MaskMode.LUMINANCE))
        assertEquals(255, coverageOf(0xFF000000.toInt(), MaskMode.LUMINANCE))
    }

    /*
     * ============================================================
     * 规格的健壮性
     * ============================================================
     */

    @Test
    fun `默认字符集是 95 个可打印 ASCII`() {
        val ascii = BitmapFontSpec.DEFAULT_ASCII

        assertEquals(95, ascii.size)
        assertEquals(0x20, ascii.first())
        assertEquals(0x7E, ascii.last())
    }

    @Test
    fun `图集小于网格时不产生字形`() {
        // 8×8 的图却声称 16×16 格 → 每格 0 像素 → 拒绝
        val tiny = ArrayPixelSource(8, 8, IntArray(64))
        assertTrue(scanAtlas(tiny, BitmapFontSpec.DEFAULT_ASCII, spec()).isEmpty())
    }

    /** 低于阈值的杂点不该被当成墨迹 */
    @Test
    fun `极低透明度的杂点被忽略`() {
        val b = AtlasBuilder()
        val (column, row) = cellOf(0x21)
        // 一个 alpha=3 的点在最右边 + 一条真正可见的墨迹
        b.set(column * 8 + 7, row * 8 + 0, 0x03FFFFFF)
        b.rect(column, row, left = 0, top = 0, width = 1, height = 7)
        val glyphs = scan(b.build())

        assertEquals("那个 alpha=3 的点不该扩大宽度", 1, glyphs[0x21]!!.srcWidth)
    }

    companion object {
        private const val WHITE = 0xFFFFFFFF.toInt()
    }
}

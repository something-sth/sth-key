package com.something.sthkey.ui.overlay

import com.something.sthkey.domain.font.bitmap.ArrayPixelSource
import com.something.sthkey.domain.font.bitmap.AtlasGrid
import com.something.sthkey.domain.font.bitmap.BitmapFontSpec
import com.something.sthkey.domain.font.bitmap.BitmapFontStore
import com.something.sthkey.domain.font.bitmap.Glyph
import com.something.sthkey.domain.font.bitmap.MaskMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 位图字体排版的测试。
 *
 * ============================================================
 * 这里钉的是什么
 * ============================================================
 * 排版全是"累加推进宽度、换行、缩放"这类算术。它出错的表现是
 * **字距不对、整行偏斜、放大倍率错**——都是"看着别扭但说不出哪错"的问题，
 * 而且改一处很容易碰坏另一处。所以这些算术必须钉住。
 *
 * ============================================================
 * ⚠️ 缩放基准是"墨迹高度"，不是声明的 `height`
 * ============================================================
 * 这一点改过一次，测试也跟着改了 —— 记在这里免得以后又"修回去"。
 *
 * 早先按 `height` 缩放，用户实测**不同分辨率的图集初始大小不统一**：
 * 不同材质包对同一个 `height` 的留白习惯不同（有的字形占满、有的只占六成）。
 *
 * 现在按**实际墨迹**归一化：把典型非下伸字形的高度映射到
 * `REFERENCE_INK_RATIO(0.7) × 字号`。所以下面的期望值里，
 * 本测试的假字形墨迹高 7 像素时，
 * **`倍率 = 字号 × 0.7 / 7 = 字号 / 10`**。
 *
 * 这一层不碰 Compose，只算坐标，所以能在本地单测里穷举。
 */
class BitmapTextLayoutTest {

    /** 本测试里假字形的墨迹高度（也是参考墨迹高度） */
    private val inkHeight = 7

    /** 于是倍率恒为 `字号 ÷ 10` */
    private fun scaleOf(fontSizePx: Float) = fontSizePx * 0.7f / inkHeight

    /**
     * 造一个可控的字体：字形表直接给定，不经过图集扫描。
     *
     * 扫描逻辑已经由 `BitmapFontAtlasTest` 单独测过了，
     * 这里只关心"拿到字形之后怎么摆"，所以把两者解耦。
     */
    private fun fakeFont(
        glyphs: Map<Int, Glyph>,
        height: Int = 8,
        ascent: Int = 7,
        pixelAlign: Boolean = false,
        atlasSize: Int = 128,
        verticalNudge: Float = 0f,
        specCodePoints: List<Int> = BitmapFontSpec.DEFAULT_ASCII,
    ): BitmapFontStore.BitmapFont {
        val spec = BitmapFontSpec(
            atlasFileName = "fake.png",
            grid = AtlasGrid(columns = 16, rows = 16, firstRow = 2),
            codePoints = specCodePoints,
            height = height,
            ascent = ascent,
            maskMode = MaskMode.ALPHA,
            pixelAlign = pixelAlign,
            // 默认关掉微调，让下面的纵向断言只反映"相对基线偏移"这一件事
            verticalNudge = verticalNudge,
        )
        return BitmapFontStore.BitmapFont(
            id = "bitmap:fake",
            displayName = "fake",
            spec = spec,
            atlas = ArrayPixelSource(atlasSize, atlasSize, IntArray(atlasSize * atlasSize)),
            glyphs = glyphs,
        )
    }

    /**
     * 一个"典型非下伸字形"：墨迹 [inkHeight] 像素高、推进 6。
     *
     * `top = -7` 是原版 `ascii.png` 的真实值（格高 8、ascent 7、
     * 墨迹从格内 y=0 起）—— 于是"非下伸"的判据成立，
     * 它会被算进参考墨迹高度。
     */
    private fun letter(code: Int, advance: Int = 6, inkWidth: Int = 5) = code to Glyph(
        codePoint = code,
        srcX = 0,
        srcY = 0,
        srcWidth = inkWidth,
        srcHeight = inkHeight,
        top = -7,
        advance = advance,
        hasInk = true,
    )

    /*
     * ============================================================
     * 缩放
     * ============================================================
     */

    /**
     * 倍率 = `字号 × 0.7 ÷ 参考墨迹高度`。
     *
     * ⚠️ **与图集分辨率无关**：参考墨迹高度是从字形量出来的，
     * 高清图集的字形也按同样比例放大，所以两者算出来的视觉大小一致。
     */
    @Test
    fun `倍率按参考墨迹高度归一化`() {
        val font = fakeFont(mapOf(letter(0x41)))

        assertEquals("字号 10 → 1 倍", 1f, layoutBitmapText(font, "A", 10f).scale, 0.001f)
        assertEquals("字号 35 → 3.5 倍", 3.5f, layoutBitmapText(font, "A", 35f).scale, 0.001f)
    }

    /**
     * ⚠️ 这条钉的正是用户报的"不同分辨率图集初始大小不统一"。
     *
     * 造两张图集：一张 128（格 8 像素）、一张 512（格 32 像素），
     * **逻辑字形完全一样**（墨迹都是 7 个"逻辑像素"高）。
     * 归一化之后，同一个字号下两者画出来必须**一样高**。
     */
    @Test
    fun `不同分辨率的图集在同一字号下大小一致`() {
        // 低清：格 8 像素，墨迹 7 像素
        val lowRes = fakeFont(
            mapOf(
                0x41 to Glyph(0x41, 0, 0, srcWidth = 5, srcHeight = 7, top = -7, advance = 6, hasInk = true),
            ),
            atlasSize = 128,
        )
        // 高清：格 32 像素，墨迹 28 像素（同样的 7/8 比例）
        val highRes = fakeFont(
            mapOf(
                0x41 to Glyph(0x41, 0, 0, srcWidth = 20, srcHeight = 28, top = -28, advance = 24, hasInk = true),
            ),
            atlasSize = 512,
        )

        val low = layoutBitmapText(lowRes, "A", fontSizePx = 35f)
        val high = layoutBitmapText(highRes, "A", fontSizePx = 35f)

        assertEquals(
            "同一字号下两张图集画出来必须一样高",
            low.lines[0][0].height,
            high.lines[0][0].height,
            0.01f,
        )
        assertEquals(
            "宽度也要一样（推进宽度按同一比例换算）",
            low.widthPx,
            high.widthPx,
            0.01f,
        )
    }

    /**
     * 像素对齐：把倍率吸附到整数。
     *
     * ⚠️ 默认关闭：强制整数倍会让字号滑块看起来是坏的（拖了没反应）。
     */
    @Test
    fun `像素对齐把倍率吸附到整数`() {
        val glyphs = mapOf(letter(0x41))
        val plain = layoutBitmapText(fakeFont(glyphs), "A", fontSizePx = 35f)
        assertEquals("默认不吸附", 3.5f, plain.pixelScale, 0.001f)

        val aligned = layoutBitmapText(fakeFont(glyphs, pixelAlign = true), "A", fontSizePx = 35f)
        assertEquals("吸附到最近的整数倍", 4f, aligned.pixelScale, 0.001f)
    }

    /* ============================================================
     * 推进宽度
     * ============================================================
     */

    /**
     * ⚠️ 关键：横向前进用的是**字形自己的推进宽度**，不是格子宽度。
     *
     * 这正是"必须扫描像素"的意义所在：`i` 推进 2、`W` 推进 6，
     * 如果按格宽算，两者会一样宽，排版会很难看。
     */
    @Test
    fun `横向前进用字形推进宽度而不是格宽`() {
        val font = fakeFont(
            mapOf(
                // i：墨迹 1 像素宽、推进 2
                0x69 to Glyph(0x69, 0, 0, 1, inkHeight, top = -7, advance = 2, hasInk = true),
                letter(0x57),
            ),
        )
        val s = scaleOf(8f) // 0.8

        assertEquals("一个 i 的宽度 = 推进 × 倍率", 2 * s, layoutBitmapText(font, "i", 8f).widthPx, 0.01f)
        assertEquals("一个 W 的宽度", 6 * s, layoutBitmapText(font, "W", 8f).widthPx, 0.01f)

        val both = layoutBitmapText(font, "iW", 8f)
        assertEquals("i 后面接 W：2 + 6 个推进单位", 8 * s, both.widthPx, 0.01f)
        assertEquals(
            "第二个字形紧跟第一个的推进位置",
            2 * s,
            both.lines[0][1].x,
            0.01f,
        )
    }

    /** 空格有推进宽度但没有墨迹，所以不该产生待绘制字形 */
    @Test
    fun `空格只推进不绘制`() {
        val font = fakeFont(
            mapOf(
                0x41 to Glyph(0x41, 0, 0, 5, inkHeight, top = -7, advance = 6, hasInk = true),
                0x20 to Glyph(0x20, 0, 0, 0, 0, top = 0, advance = 4, hasInk = false),
            ),
        )
        val s = scaleOf(10f) // 1.0

        val layout = layoutBitmapText(font, "A A", 10f)
        assertEquals("两个字母产生两个字形，空格不产生", 2, layout.lines[0].size)
        assertEquals("宽度 = 6 + 4 + 6", 16 * s, layout.widthPx, 0.01f)
        assertEquals("第二个字母在 6 + 4 处", 10 * s, layout.lines[0][1].x, 0.01f)
    }

    /**
     * 额外字距（对应"文字缩放"之外的字距调整）应当被加上，
     * 而且**只加在字形之间**，末字之后不加 ——
     * 加在末字之后会让整行宽度多一段尾巴，而这块文字是按宽度居中的，
     * 于是整体偏左半个字距。
     */
    @Test
    fun `额外字距叠加在字形之间`() {
        val font = fakeFont(mapOf(letter(0x41)))
        val s = scaleOf(10f) // 1.0

        val normal = layoutBitmapText(font, "AA", 10f)
        assertEquals(12 * s, normal.widthPx, 0.01f)

        // 字号 10、额外 0.5 → 只在中间加 5 像素
        val spaced = layoutBitmapText(font, "AA", 10f, extraAdvance = 0.5f)
        assertEquals("6 + 5 + 6，末字之后不加", 17 * s, spaced.widthPx, 0.01f)
    }

    /* ============================================================
     * 换行
     * ============================================================
     */

    /**
     * 换行按**格高 × 倍率**往下排。
     *
     * ⚠️ 行距用格高而不是字形的墨迹高度：字形高度各不相同
     * （`a` 只有 5 像素、`A` 有 7 像素），用墨迹高度算行距会让
     * 每行间距都不一样。
     *
     * ⚠️ 断言的是**两行之间的 y 差**，不是第二行的绝对 y ——
     * 绝对 y 里还含字形自身相对基线的偏移（`top`），那样断言的就不是行距了。
     */
    @Test
    fun `两行的行距等于格高乘以倍率`() {
        val font = fakeFont(mapOf(letter(0x41)))
        val layout = layoutBitmapText(font, "A\nA", fontSizePx = 20f)
        val expectedLineHeight = 8 * scaleOf(20f) // 格高 8 × 倍率 2

        assertEquals(2, layout.lines.size)

        val firstY = layout.lines[0][0].y
        val secondY = layout.lines[1][0].y
        assertEquals("两行之间的间距 = 一个行高", expectedLineHeight, secondY - firstY, 0.01f)
        assertEquals("整块高度 = 两行", expectedLineHeight * 2, layout.heightPx, 0.01f)
    }

    @Test
    fun `整体宽度取最长的一行`() {
        val font = fakeFont(mapOf(letter(0x41)))
        val s = scaleOf(10f)

        val layout = layoutBitmapText(font, "A\nAAA", 10f)
        assertEquals("第二行更长，宽度取它", 18 * s, layout.widthPx, 0.01f)
    }

    /* ============================================================
     * 纵向：字形相对基线的位置
     * ============================================================
     */

    /**
     * 扫描时已经把 `top` 算成"相对基线的偏移"，这里直接叠加即可。
     *
     * 这条钉住的是"不要再算一遍基线"——算两遍会让下伸字母的位置翻倍偏移。
     *
     * ⚠️ 倍率取决于**参考墨迹高度**（非下伸字形的中位数）。这里三个
     * 非下伸字形的墨迹都是 7 像素，所以倍率是 1.0 —— 期望值就是
     * 字形自己的 `top` 值，一眼能看出对不对。
     */
    @Test
    fun `字形纵向位置直接用扫描出来的相对基线偏移`() {
        val font = fakeFont(
            mapOf(
                // 三个 7 像素高的非下伸字形，把参考墨迹高度定在 7
                0x41 to Glyph(0x41, 0, 0, 5, 7, top = -7, advance = 6, hasInk = true),
                0x42 to Glyph(0x42, 0, 0, 5, 7, top = -7, advance = 6, hasInk = true),
                0x43 to Glyph(0x43, 0, 0, 5, 7, top = -7, advance = 6, hasInk = true),
                // 'g'：下伸，墨迹从格内 y=2 开始 → top = -5，高 7 → 底边到第 7 行
                0x67 to Glyph(0x67, 0, 0, 5, 7, top = -5, advance = 6, hasInk = true),
            ),
        )
        val s = scaleOf(10f) // 字号 10、参考 7 → 1.0

        val layout = layoutBitmapText(font, "Ag", 10f)
        assertEquals("A 在基线上方 7 个字形像素", -7 * s, layout.lines[0][0].y, 0.01f)
        assertEquals("g 的墨迹起点更低（下伸）", -5 * s, layout.lines[0][1].y, 0.01f)
    }

    /**
     * 垂直微调：正值往下挪，幅度是**渲染后墨迹高度的百分比**。
     *
     * ⚠️ 用"渲染后高度"而不是"设计像素"，这个微调才能跨图集一致 ——
     * 否则同一个百分比在不同分辨率的图集上推出来的视觉距离不一样。
     */
    @Test
    fun `垂直微调按渲染后墨迹高度的百分比`() {
        val glyphs = mapOf(letter(0x41))
        val noNudge = layoutBitmapText(fakeFont(glyphs, verticalNudge = 0f), "A", 100f)
        val nudged = layoutBitmapText(fakeFont(glyphs, verticalNudge = 10f), "A", 100f)

        // 字号 100、参考比例 0.7 → 渲染后墨迹高度 70；10% 就是 7 像素
        val delta = nudged.lines[0][0].y - noNudge.lines[0][0].y
        assertEquals("10% 的微调应当往下挪 7 像素", 7f, delta, 0.01f)
    }

    /* ============================================================
     * NaN 防护（用户实测的"LMB 三个字母堆成一个 8"）
     * ============================================================
     */

    /**
     * ⚠️ 多字符必须**逐个推进**，不能叠在一起。
     *
     * 这是那个 bug 的直接回归测试：`LMB` 三个字母曾经全部叠在同一个位置、
     * 看起来像一个 `8`。
     */
    @Test
    fun `多字符必须逐个推进不能叠在一起`() {
        val font = fakeFont(
            mapOf(
                letter(0x4C), // L
                letter(0x4D), // M
                letter(0x42), // B
            ),
        )

        val layout = layoutBitmapText(font, "LMB", 10f)
        val xs = layout.lines[0].map { it.x }

        assertEquals("三个字母应当产生三个字形", 3, xs.size)
        assertTrue("x 必须都是有限值（NaN 会让它们全叠在一起）", xs.all { it.isFinite() })
        assertTrue("x 必须严格递增：$xs", xs[0] < xs[1] && xs[1] < xs[2])
        assertEquals("第一个在 0", 0f, xs[0], 0.01f)
    }

    /**
     * ⚠️ `NaN` 字距**不能**污染 `penX`。
     *
     * 调用方传的是 `TextStyle.letterSpacing.value`，而它没设置时是
     * `TextUnit.Unspecified` —— **`.value` 是 `Float.NaN`**。
     *
     * 漏进来的话 `penX += NaN` → 后面所有字形的 x 都成 NaN → 全叠在一起。
     * 症状很有迷惑性：单字符完全正常（NaN 出现在最后一个字形之后），
     * 只有多字符才炸。
     */
    @Test
    fun `NaN 字距不能让字形叠在一起`() {
        val font = fakeFont(mapOf(letter(0x4C), letter(0x4D), letter(0x42)))

        val layout = layoutBitmapText(font, "LMB", 10f, extraAdvance = Float.NaN)
        val xs = layout.lines[0].map { it.x }

        assertTrue("宽度不能让 NaN 传出去", layout.widthPx.isFinite())
        assertTrue("x 必须都是有限值", xs.all { it.isFinite() })
        assertTrue("x 必须严格递增（NaN 会让它们全都相等）", xs[0] < xs[1] && xs[1] < xs[2])
    }

    /**
     * `TextUnit.Unspecified` 的字距取出来必须是 **0**，不是 NaN。
     *
     * 这条钉的是 [safeLetterSpacing] 这个访问器本身 ——
     * 只要有人图省事在别处直接写 `.letterSpacing.value`，这个坑就会回来。
     */
    @Test
    fun `未设置的字距取出来是 0 而不是 NaN`() {
        val unspecified = androidx.compose.ui.text.TextStyle.Default
        val value = safeLetterSpacing(unspecified)

        assertTrue("未设置时必须是有限值，实际是 $value", value.isFinite())
        assertEquals("未设置时应当当作 0", 0f, value, 0.0001f)
    }

    /* ============================================================
     * 缺字
     * ============================================================
     */

    /**
     * 字体里没有的字符：**留白而不是跳过**。
     *
     * 跳过会让后面的字往左挤——"缺字"和"字距不对"是两个不同的问题，
     * 前者肉眼可见，后者很难察觉。留白能让缺字一眼看出来。
     */
    @Test
    fun `字体里没有的字符留白而不是挤掉`() {
        val font = fakeFont(mapOf(letter(0x41)))

        val layout = layoutBitmapText(font, "A中A", 10f)
        assertEquals("只画出两个 A", 2, layout.lines[0].size)
        assertTrue("中间那个字符要占位，宽度必须大于两个字母", layout.widthPx > 12f)
    }

    /*
     * ============================================================
     * 参考墨迹高度
     * ============================================================
     */

    /**
     * 参考墨迹高度取**非下伸字形**的中位数。
     *
     * 为什么排除下伸字形：`g` `y` 这类字形比 `A` 高一截，
     * 把它们算进去会把参考值抬高，于是整段文字被缩小。
     */
    @Test
    fun `参考墨迹高度排除下伸字形`() {
        val font = fakeFont(
            mapOf(
                // 三个非下伸字形：墨迹 7 像素、底边在格内第 6 行
                0x41 to Glyph(0x41, 0, 0, 5, 7, top = -7, advance = 6, hasInk = true),
                0x42 to Glyph(0x42, 0, 0, 5, 7, top = -7, advance = 6, hasInk = true),
                0x43 to Glyph(0x43, 0, 0, 5, 7, top = -7, advance = 6, hasInk = true),
                // 一个下伸字形：占满 8 行（底边到格内第 7 行）
                0x67 to Glyph(0x67, 0, 0, 5, 8, top = -5, advance = 6, hasInk = true),
            ),
        )

        assertEquals("应当只算那三个非下伸字形", 7f, referenceInkHeight(font), 0.01f)
    }

    @Test
    fun `没有任何字形时退回声明高度`() {
        val font = fakeFont(emptyMap(), height = 8)

        assertEquals("不能让缩放算成 0", 8f, referenceInkHeight(font), 0.01f)
    }

    /*
     * ============================================================
     * 多行**不重叠**（用户实测的"CPS 模式 3 转自定义后两行挤在一起"）
     * ============================================================
     */

    /**
     * ⚠️ 两行的**墨迹范围不能相交**。
     *
     * 这是那个 bug 的直接回归测试：早先基线按 `lineIndex * lineHeight` 算，
     * 在**所有行同号**时恰好等价；但 CPS 那行更小，于是基线距离不足一行高，
     * **两行直接压在一起**。
     *
     * 断言用"实际墨迹的上下边界"而不是单个 y —— 那才是用户看到的东西。
     */
    @Test
    fun `行高不同的两行不能重叠`() {
        val font = fakeFont(mapOf(letter(0x41)))

        // 第一行 100%、第二行 50%（模拟 CPS 行更小）
        val layout = layoutBitmapText(
            font = font,
            text = "A\nA",
            fontSizePx = 40f,
            lineScalePercents = listOf(100, 50),
        )

        val first = layout.lines[0][0]
        val second = layout.lines[1][0]
        val firstBottom = first.y + first.height

        assertTrue(
            "第二行的顶边必须落在第一行底边之下：第一行底 $firstBottom、第二行顶 ${second.y}",
            second.y >= firstBottom,
        )
    }

    /** 行间距为正 → 两行更远；为负 → 更近（但有下限，不会压在一起） */
    @Test
    fun `行间距拉大与收紧`() {
        val font = fakeFont(mapOf(letter(0x41)))

        fun gapOf(percent: Float): Float {
            val layout = layoutBitmapText(
                font = font,
                text = "A\nA",
                fontSizePx = 40f,
                lineSpacingPercent = percent,
            )
            val first = layout.lines[0][0]
            val second = layout.lines[1][0]
            return second.y - (first.y + first.height)
        }

        val normal = gapOf(0f)
        assertTrue("默认不该重叠", normal >= 0f)
        assertTrue("行间距 +50% 应当更远", gapOf(50f) > normal)
        assertTrue("行间距 -30% 应当更近", gapOf(-30f) < normal)
    }

    /**
     * ⚠️ 行间距收到极端负值也**不能重叠**。
     *
     * 下限（`MIN_LINE_SPACING_FACTOR`）就是为了这个：
     * 两行压在一起不是"紧凑"而是坏了。
     */
    @Test
    fun `行间距收到极端值也不重叠`() {
        val font = fakeFont(mapOf(letter(0x41)))

        val layout = layoutBitmapText(
            font = font,
            text = "A\nA",
            fontSizePx = 40f,
            lineSpacingPercent = -500f,
        )

        val first = layout.lines[0][0]
        val second = layout.lines[1][0]

        assertTrue(
            "收到 -500% 也不该重叠：第一行底 ${first.y + first.height}、第二行顶 ${second.y}",
            second.y >= first.y,
        )
    }

    /** 字间距为正 → 整行更宽；为负 → 更窄；且第一个字形始终在 0 */
    @Test
    fun `字间距拉大与收紧`() {
        val font = fakeFont(mapOf(letter(0x41), letter(0x42), letter(0x43)))

        fun layoutOf(percent: Float) = layoutBitmapText(
            font = font,
            text = "ABC",
            fontSizePx = 40f,
            letterSpacingPercent = percent,
        )

        val normal = layoutOf(0f)
        val wide = layoutOf(50f)
        val tight = layoutOf(-20f)

        assertEquals("第一个字形始终在 0", 0f, normal.lines[0][0].x, 0.01f)
        assertTrue("字间距 +50% 应当更宽", wide.widthPx > normal.widthPx)
        assertTrue("字间距 -20% 应当更窄", tight.widthPx < normal.widthPx)
        assertTrue("第二个字形应当被推开", wide.lines[0][1].x > normal.lines[0][1].x)
        assertTrue("第二个字形应当被收紧", tight.lines[0][1].x < normal.lines[0][1].x)
    }

    /**
     * ⚠️ `lineScalePercents` 的第 0 项是**主行**，不是"要缩小的那一行"。
     *
     * ============================================================
     * 这条测试是为一个真实 bug 加的
     * ============================================================
     * 自定义 Key 里 `cpsTextScalePercent`（CPS 行相对主行的百分比，
     * 转换过来是 79）曾经被**当成了主行的倍率**传进来 ——
     * 于是主行被放大 79 倍、CPS 行反倒正常。
     *
     * 用户看到的现象是"CPS 行字号这个滑块没效果"，
     * 完全不会联想到"字号整个乱掉了"。所以要有一条测试把
     * "谁缩放谁"钉死在这里。
     */
    @Test
    fun `逐行字号是相对主行的倍率`() {
        val font = fakeFont(mapOf(letter(0x41)))

        val layout = layoutBitmapText(
            font = font,
            text = "A\nA",
            fontSizePx = 40f,
            // 主行 100%、CPS 行 79%（转换过来的真实取值）
            lineScalePercents = listOf(100, 79),
        )

        val main = layout.lines[0][0]
        val cps = layout.lines[1][0]

        assertTrue(
            "CPS 行的字形必须**明显小于**主行：主 ${main.height}、CPS ${cps.height}",
            cps.height < main.height,
        )
        /*
         * 79% 的比值要落在合理范围内（不是 79 倍、也不是 1/79）。
         * 用比例断言而不是绝对值：格高会随图集变，比例才是契约。
         */
        val ratio = cps.height / main.height
        assertTrue(
            "CPS 行应当是主行的约 79%，实际 $ratio",
            ratio in 0.7f..0.9f,
        )
    }

    /*
     * ============================================================
     * 能否用位图渲染
     * ============================================================
     */

    @Test
    fun `纯 ASCII 可用位图渲染`() {
        val spec = BitmapFontSpec(atlasFileName = "x.png", grid = AtlasGrid(16, 16))
        assertTrue(canRenderAsBitmap("LMB", spec))
        assertTrue(canRenderAsBitmap("CPS: 12", spec))
        assertTrue(canRenderAsBitmap("~!@#$%^&*()", spec))
    }

    @Test
    fun `含中文时不能用位图渲染`() {
        val spec = BitmapFontSpec(atlasFileName = "x.png", grid = AtlasGrid(16, 16))
        assertFalse("本阶段只做纯 ASCII，中文要回退矢量字体", canRenderAsBitmap("按键", spec))
        assertFalse(canRenderAsBitmap("LMB 键", spec))
    }

    @Test
    fun `空文字不渲染`() {
        val spec = BitmapFontSpec(atlasFileName = "x.png", grid = AtlasGrid(16, 16))
        assertFalse(canRenderAsBitmap("", spec))
    }
}

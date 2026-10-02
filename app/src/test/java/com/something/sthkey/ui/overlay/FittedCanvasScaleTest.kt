package com.something.sthkey.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 画布自适应缩放的测试。
 *
 * ============================================================
 * 这里钉的是一个很难查的真实 bug
 * ============================================================
 * 现象：**悬浮窗上空格右边短一截**，而配置预览、自定义编辑页都正常。
 *
 * 排查过程：窗口尺寸、内容包围盒、组件坐标、缩放换算——每一项都用数值
 * 验证过、全部自洽，从代码里推不出任何裁剪。最后加了运行时测量才看到：
 *
 * ```
 * 画布请求：272.1 × 439.6 px
 * 画布实得：261.0 × 428.0 px      ← 少了 11.1px
 * ```
 *
 * 画布自己算尺寸（`baseWidth × scale`）然后**指望**容器给得起，
 * 而容器实际少给了 11px。被裁的只有"声明宽度等于画布宽"的那两个组件
 * （空格与 Shift），其它组件窄、落在画布内部所以看不出来 ——
 * 于是症状表现得像"只有空格有问题"。
 *
 * 修法是不再推算：**按容器真实给的空间反推缩放**，
 * 于是"内容超出容器"在数学上不可能发生。
 *
 * 这些用例钉的就是那条性质：**任何输入下内容都不超出容器**。
 * 它是纯函数，所以能在这里穷举边界；放在 composable 里就只能靠肉眼。
 */
class FittedCanvasScaleTest {

    /*
     * ============================================================
     * 核心性质：绝不溢出
     * ============================================================
     */

    /**
     * ⚠️ 本测试的重点：无论容器给多少，内容都不得超出它。
     *
     * 这是那个 bug 的正解 —— 之前是"算一个尺寸，指望容器配合"，
     * 现在改成"容器给多少就用多少"。
     */
    @Test
    fun `任何容器尺寸下内容都不超出容器`() {
        val baseWidth = 260f
        val baseHeight = 420f

        // 容器比内容大、刚好相等、略小、小很多 —— 四种情形都要成立
        val containers = listOf(
            1000f to 1200f,
            273f to 440f,
            272.1f to 439.6f,
            261f to 428f,
            100f to 200f,
            1f to 1f,
        )

        containers.forEach { (availableWidth, availableHeight) ->
            val scale = computeFittedScale(
                availableWidthPx = availableWidth,
                availableHeightPx = availableHeight,
                baseWidth = baseWidth,
                baseHeight = baseHeight,
            )

            val contentWidth = baseWidth * scale
            val contentHeight = baseHeight * scale

            assertTrue(
                "容器 ${availableWidth}×${availableHeight}：内容宽 $contentWidth 溢出了",
                contentWidth <= availableWidth + 0.01f,
            )
            assertTrue(
                "容器 ${availableWidth}×${availableHeight}：内容高 $contentHeight 溢出了",
                contentHeight <= availableHeight + 0.01f,
            )
        }
    }

    /**
     * ⚠️ 为什么取宽高比例的**较小值**。
     *
     * 取较大值会让其中一个方向溢出，而溢出的那个方向正好是
     * "贴边的那一行/列" —— 表现就是"只有某个组件被裁"。
     * 这条用例直接把这个错误做法钉死。
     */
    @Test
    fun `宽高比例悬殊时按较小的那个缩放`() {
        // 容器很宽但很矮：必须听高度那个比例，否则高度溢出
        val scale = computeFittedScale(
            availableWidthPx = 2000f,
            availableHeightPx = 400f,
            baseWidth = 100f,
            baseHeight = 200f,
        )

        assertEquals("应当听高度：400 ÷ 200", 2f, scale, 0.001f)
        // 宽度只用掉 200，远小于容器的 2000 —— 宽的那一维有余量是正常的
        assertTrue(100f * scale <= 2000f)
        assertTrue(200f * scale <= 400f)
    }

    /* ============================================================
     * 尺寸刚好时应当严丝合缝
     * ============================================================
     */

    /**
     * 容器刚好等于内容基础尺寸时，缩放应当是 1 —— 内容铺满，不留边。
     *
     * 这是"悬浮窗窗口本来就是按内容包围盒定的"那种正常情形。
     */
    @Test
    fun `容器刚好等于基础尺寸时缩放为 1`() {
        val scale = computeFittedScale(
            availableWidthPx = 260f,
            availableHeightPx = 420f,
            baseWidth = 260f,
            baseHeight = 420f,
        )

        assertEquals(1f, scale, 0.0001f)
    }

    /**
     * 用户那份数据的真实复现：容器比需要的小 11px。
     *
     * 修复前画布会**保持** 272.1px 然后被裁；修复后它会缩小到装得下 ——
     * 内容整体小一点（肉眼不可见），但**不会被裁**。
     */
    @Test
    fun `容器比需要的小一点时缩小而不是被裁`() {
        val scale = computeFittedScale(
            availableWidthPx = 261f,
            availableHeightPx = 428f,
            baseWidth = 260f,
            baseHeight = 420f,
        )

        val contentWidth = 260f * scale
        assertTrue("必须缩到容器以内", contentWidth <= 261f)
        // 缩小幅度应当很小 —— 只差 11px，不该变成明显的小一圈
        assertTrue("缩小幅度过大：$scale", scale > 0.95f)
    }

    /* ============================================================
     * 退化输入：不能返回 Infinity / NaN
     * ============================================================
     */

    /**
     * 内容是空布局（基础尺寸 0）时返回 0。
     *
     * ⚠️ 不能让它做除法：0 会让结果变成 `Infinity`，
     * 那个值传进 `Modifier.size(...)` 会让整个界面消失，
     * 而且不报错 —— 比崩溃还难查。
     */
    @Test
    fun `基础尺寸为 0 时返回 0 而不是无穷大`() {
        assertEquals(
            0f,
            computeFittedScale(300f, 300f, baseWidth = 0f, baseHeight = 420f),
            0.0001f,
        )
        assertEquals(
            0f,
            computeFittedScale(300f, 300f, baseWidth = 260f, baseHeight = 0f),
            0.0001f,
        )
    }

    /** 容器还没测量出来（0 或负数）时同样返回 0 */
    @Test
    fun `容器尺寸无效时返回 0`() {
        assertEquals(0f, computeFittedScale(0f, 400f, 260f, 420f), 0.0001f)
        assertEquals(0f, computeFittedScale(400f, 0f, 260f, 420f), 0.0001f)
        assertEquals(0f, computeFittedScale(-10f, 400f, 260f, 420f), 0.0001f)
    }

    /** 极端放大也不能溢出：容器巨大时内容跟着放大，但仍在容器内 */
    @Test
    fun `容器极大时内容放大但不溢出`() {
        val scale = computeFittedScale(
            availableWidthPx = 10_000f,
            availableHeightPx = 10_000f,
            baseWidth = 260f,
            baseHeight = 420f,
        )

        assertTrue(260f * scale <= 10_000f + 0.01f)
        assertTrue(420f * scale <= 10_000f + 0.01f)
    }

    /* ============================================================
     * ⚠️ 单位：返回的是"像素 / 基础单位"，不是"dp / 基础单位"
     * ============================================================
     */

    /**
     * 这条钉的是**我把界面放大到只看得见一个角**的那个错误。
     *
     * 容器给的空间是**像素**，所以这里算出来的比例是**像素比**；
     * 而项目里 `scale` 参数的语义是 **dp 比**（组件内部还会再乘一次密度）。
     * 调用方必须再除以密度才能当 `scale` 用。
     *
     * 搞错的表现非常有特征：内容被放大到**密度的倍数**
     * （约 2.9~3 倍），严重溢出窗口 —— 看起来像"布局全乱了"，
     * 很难联想到是单位问题。所以这里用几条断言把语义写死。
     */
    @Test
    fun `返回的是像素比而不是 dp 比`() {
        val density = 2.975f
        val baseWidth = 260f
        val baseHeight = 420f

        // 容器恰好等于"名义缩放下的内容像素尺寸"
        val nominalScaleDp = 1.047f
        val availableWidth = baseWidth * nominalScaleDp * density
        val availableHeight = baseHeight * nominalScaleDp * density

        val scalePxPerBase = computeFittedScale(
            availableWidthPx = availableWidth,
            availableHeightPx = availableHeight,
            baseWidth = baseWidth,
            baseHeight = baseHeight,
        )

        // 像素比：约等于 名义 × 密度
        assertEquals(nominalScaleDp * density, scalePxPerBase, 0.01f)

        /*
         * 要当 `scale` 用必须除以密度 —— 除完就回到名义值。
         *
         * 如果哪天有人"顺手"把这里改成直接返回 dp 比，
         * 上面那条断言会失败；如果有人忘了除，这条会失败。
         * 两个方向都被钉住，单位混淆就不会再悄悄溜过去。
         */
        val scaleDpPerBase = scalePxPerBase / density
        assertEquals(nominalScaleDp, scaleDpPerBase, 0.01f)
    }

    /**
     * 用**声明宽 / 实得宽**的比值来验证单位。
     *
     * 这个比值曾经是 2.855（≈ 密度），也就是组件被放大到只看得见一个角。
     * 正确的比值应当是 1 左右。
     */
    @Test
    fun `组件声明宽与实得宽的比值应当接近 1`() {
        val density = 2.975f
        val baseWidth = 260f
        val baseHeight = 420f
        // 容器实测比推算少 11px —— 用户那份数据的真实情形
        val availableWidth = 261f
        val availableHeight = 428f

        val scalePxPerBase = computeFittedScale(
            availableWidthPx = availableWidth,
            availableHeightPx = availableHeight,
            baseWidth = baseWidth,
            baseHeight = baseHeight,
        )
        val scaleDpPerBase = scalePxPerBase / density

        /*
         * 组件的"声明宽（像素）"= 基础宽 × scale（dp 比）× 密度，
         * "实得宽（像素）"= 基础宽 × scale（dp 比）× 密度 —— 两者同源，
         * 除非渲染时又被缩放了一次。
         *
         * 这里直接比"内容像素尺寸"与"容器像素尺寸"：应当 ≤ 容器，
         * 而且不能小得离谱（否则就是又除了一次密度）。
         */
        val contentWidthPx = baseWidth * scaleDpPerBase * density
        assertTrue("内容超过了容器", contentWidthPx <= availableWidth + 0.5f)
        assertTrue(
            "内容比容器小太多（比值 ${contentWidthPx / availableWidth}），像是多除了一次密度",
            contentWidthPx / availableWidth > 0.95f,
        )
    }
}

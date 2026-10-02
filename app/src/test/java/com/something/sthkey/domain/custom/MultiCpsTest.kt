package com.something.sthkey.domain.custom

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 一个组件里**多个 CPS 占位符**的测试。
 *
 * ============================================================
 * 为什么这些用例最要紧
 * ============================================================
 * 这块逻辑有三处极易写错、而且错了以后**看不出来**：
 *
 * 1. **占位符下标会失效**：位置是在原始模板上算的，
 *    一旦先替换了左边那个，右边的下标就全错位了；
 * 2. **`(cps)` 是 `(cps2)` 的前缀**：直接对整串做
 *    `replace("(cps)", …)` 会把 `(cps2)` 截成 `"2"`；
 * 3. **两种占位符的先后顺序**：用户看到的"第 1 个"必须是
 *    **文字里真实排在最前**的那个，不能是"先扫完 (cps) 再扫完 (cps2)"。
 *
 * 这三条都不会崩溃、只会显示成错的数字或错的文字 ——
 * 所以只能靠测试钉住。
 */
class MultiCpsTest {

    private fun text(
        content: String,
        groups: List<List<Int>> = listOf(listOf(272)),
    ) = TextComponent(
        id = "t",
        x = 0f,
        y = 0f,
        width = 120f,
        height = 30f,
        style = ComponentStyle(),
        text = content,
        cpsKeyCodesPerPlaceholder = groups,
    )

    /*
     * ============================================================
     * 扫描：位置与顺序
     * ============================================================
     */

    @Test
    fun `没有占位符时扫不出任何东西`() {
        assertEquals(emptyList<CustomLayout.CpsPlaceholder>(), CustomLayout.cpsPlaceholders("LMB"))
        assertEquals(emptyList<CustomLayout.CpsPlaceholder>(), CustomLayout.cpsPlaceholders(""))
    }

    @Test
    fun `扫出单个占位符的位置`() {
        val found = CustomLayout.cpsPlaceholders("CPS: (cps)")

        assertEquals(1, found.size)
        assertEquals(5, found[0].start)
        assertEquals(0, found[0].index)
        assertEquals(false, found[0].isMode1)
    }

    @Test
    fun `扫出两个普通占位符`() {
        val found = CustomLayout.cpsPlaceholders("(cps) / (cps)")

        assertEquals(2, found.size)
        assertEquals(0, found[0].start)
        assertEquals(8, found[1].start)
        assertEquals(listOf(0, 1), found.map { it.index })
    }

    /**
     * ⚠️ 关键：`(cps2)` 必须整体被识别，**不能**被当成 `(cps)` 命中。
     *
     * 如果扫描时先找 `(cps)`，`"(cps2)"` 的前 5 个字符正好匹配，
     * 于是 `2)` 会被当成普通文字留下 —— 渲染结果变成 `"2"` 或 `" 32"` 这种东西。
     */
    @Test
    fun `cps2 不会被误认成 cps`() {
        val found = CustomLayout.cpsPlaceholders("LMB(cps2)")

        assertEquals(1, found.size)
        assertEquals(3, found[0].start)
        assertEquals(true, found[0].isMode1)
        assertEquals("(cps2)".length, found[0].length)
    }

    /**
     * ⚠️ 顺序必须是**文字里真实的先后**。
     *
     * 分两趟扫（先扫完所有 `(cps)` 再扫所有 `(cps2)`）会得到错误顺序，
     * 而编号是给用户看的 —— 顺序错了，颜色标记与键位列表就对不上，
     * 用户改了半天发现改错了那一路。
     */
    @Test
    fun `两种占位符混用时按文字顺序编号`() {
        // 第 0 个是 (cps2)，第 1 个是 (cps)
        val found = CustomLayout.cpsPlaceholders("A(cps2) B(cps)")

        assertEquals(2, found.size)
        assertEquals(true, found[0].isMode1)
        assertEquals(false, found[1].isMode1)
        assertEquals(1, found[0].start)
        assertEquals(9, found[1].start)
    }

    /*
     * ============================================================
     * 渲染：每个占位符各用各的值
     * ============================================================
     */

    @Test
    fun `两个占位符各用各的数值`() {
        assertEquals(
            "3 / 5",
            CustomLayout.renderCpsTextAt("(cps) / (cps)", values = listOf(3, 5)),
        )
    }

    @Test
    fun `重复的占位符不能都换成同一个值`() {
        // 早先用 replace(old, new) 会把两处都换成 3 —— 第二个就白配了
        assertEquals(
            "3-7",
            CustomLayout.renderCpsTextAt("(cps)-(cps)", values = listOf(3, 7)),
        )
    }

    /**
     * ⚠️ 这条钉的是"`(cps)` 会命中 `(cps2)` 前缀"那个坑。
     */
    @Test
    fun `cps2 与 cps 混排不会互相破坏`() {
        assertEquals(
            "LMB 3 RMB4",
            CustomLayout.renderCpsTextAt("LMB(cps2) RMB(cps)", values = listOf(3, 4)),
        )
    }

    @Test
    fun `cps2 为 0 时整段消失而 cps 仍显示 0`() {
        assertEquals(
            "LMB RMB0",
            CustomLayout.renderCpsTextAt("LMB(cps2) RMB(cps)", values = listOf(0, 0)),
        )
    }

    /** 数值比占位符少时按 0 处理，不能崩 */
    @Test
    fun `数值不足时缺的按 0 处理`() {
        assertEquals(
            "1 / 0",
            CustomLayout.renderCpsTextAt("(cps) / (cps)", values = listOf(1)),
        )
    }

    @Test
    fun `没有占位符时原样返回并去掉首尾空格`() {
        assertEquals("LMB", CustomLayout.renderCpsTextAt("  LMB  ", values = emptyList()))
    }

    /**
     * 单值那条路径（[CustomLayout.renderCpsText]）与多值那条
     * 必须给出**一致**的结果 —— 它俩并存过一次，结果就是
     * 只有一条路径上的行为是对的。
     */
    @Test
    fun `单值渲染与多值渲染结果一致`() {
        val template = "(cps) / (cps)"

        assertEquals(
            CustomLayout.renderCpsTextAt(template, listOf(9)),
            CustomLayout.renderCpsText(template, cps = 9),
        )
    }

    /*
     * ============================================================
     * 键位：按下标取，且要有回退
     * ============================================================
     */

    @Test
    fun `第 n 个占位符取第 n 组键位`() {
        val component = text(
            content = "(cps) (cps)",
            groups = listOf(listOf(272), listOf(273)),
        )

        assertEquals(listOf(272), CustomLayout.cpsKeyCodesAt(component, 0))
        assertEquals(listOf(273), CustomLayout.cpsKeyCodesAt(component, 1))
    }

    /**
     * 用户新写了一个占位符、还没给它配键位时，
     * 应当回落到第一组 —— 而不是变成"数鼠标左键"那种莫名其妙的结果，
     * 也不是崩掉。
     */
    @Test
    fun `下标超出分组时回落到第一组`() {
        val component = text(
            content = "(cps) (cps) (cps)",
            groups = listOf(listOf(273)),
        )

        assertEquals(listOf(273), CustomLayout.cpsKeyCodesAt(component, 0))
        assertEquals(listOf(273), CustomLayout.cpsKeyCodesAt(component, 2))
    }

    /** 空的**内层**表示"这一处不统计任何键"，是合法选择，不能被跳过 */
    @Test
    fun `空的一组不会被跳过而错位`() {
        val component = text(
            content = "(cps) (cps)",
            groups = listOf(emptyList(), listOf(273)),
        )

        assertEquals(emptyList<Int>(), CustomLayout.cpsKeyCodesAt(component, 0))
        assertEquals(listOf(273), CustomLayout.cpsKeyCodesAt(component, 1))
    }

    /**
     * 老配置只有扁平的 `cpsKeyCodes`（那时也只能有一个占位符）。
     *
     * 迁移规则：那份键位就是**第一组**。用户升级后看到的与之前一样。
     */
    @Test
    fun `老配置的扁平键位被当成第一组`() {
        val legacy = TextComponent(
            id = "old",
            x = 0f,
            y = 0f,
            width = 120f,
            height = 30f,
            style = ComponentStyle(),
            text = "CPS: (cps)",
            cpsKeyCodes = listOf(273),
            // 新字段保持默认（鼠标左键），模拟"从老 JSON 读出来的样子"
            cpsKeyCodesPerPlaceholder = listOf(DEFAULT_CPS_KEY_CODES),
        )

        // ⚠️ 这里说明一个真实存在的边界：新字段有默认值时，
        // 老字段是读不到的 —— 所以解码器必须**主动**把老值搬进新字段
        // （见 CustomLayoutCodec.decodeCpsKeyCodesPerPlaceholder）。
        // 本用例固化"解码器搬完之后"的状态。
        val migrated = legacy.copy(cpsKeyCodesPerPlaceholder = listOf(legacy.cpsKeyCodes))
        assertEquals(listOf(273), CustomLayout.cpsKeyCodesAt(migrated, 0))
    }
}

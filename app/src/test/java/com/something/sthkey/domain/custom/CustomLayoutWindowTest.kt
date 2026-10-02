package com.something.sthkey.domain.custom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「内容搬进窗口坐标」的测试。
 *
 * ============================================================
 * 这个文件是为了钉住一个**真实发生过**的错误
 * ============================================================
 * 悬浮窗尺寸改成"内容包围盒"之后，绘制前必须把内容平移一次
 * （组件坐标的原点是定位区，窗口只覆盖包围盒）。
 *
 * 当时把平移**写反了**（用了 `−left` 而不是 `+left`），后果是：
 *
 * - 内容被整体推出可视区；
 * - 屏幕上只剩靠下的一小部分 —— 默认布局里正好是那行 `CPS: 0`；
 * - 严重时什么都不剩；
 * - 而编辑器画布里**完全正常**（那边不画在窗口里），所以极难定位。
 *
 * 修好之后把平移收成了 [CustomLayout.toWindow]（纯函数），
 * 于是"每个组件都得落在窗口之内"这条不变量可以直接被测试。
 * 符号再写反，这里会立刻失败 —— 而不是等用户来报"悬浮窗缺了一块"。
 */
class CustomLayoutWindowTest {

    private fun component(
        id: String,
        x: Float,
        y: Float,
        width: Float = 80f,
        height: Float = 80f,
    ) = TextComponent(
        id = id,
        x = x,
        y = y,
        width = width,
        height = height,
        style = ComponentStyle(),
        text = id,
    )

    /*
     * ============================================================
     * 核心不变量
     * ============================================================
     */

    /**
     * 这是本文件最重要的一条：**搬完之后，每个组件都必须完整落在窗口里**。
     *
     * 平移符号写反时它会失败（组件坐标变成 `x + left`，跑到窗口右边外面去）。
     */
    private fun assertAllInsideWindow(components: List<CustomComponent>) {
        val windowed = CustomLayout.toWindow(components)
        val bounds = windowed.bounds

        windowed.components.forEach { c ->
            assertTrue(
                "组件 ${c.id} 的左边界 ${c.x} 跑到窗口外了（窗口宽 ${bounds.width}）",
                c.x >= -0.001f,
            )
            assertTrue(
                "组件 ${c.id} 的上边界 ${c.y} 跑到窗口外了（窗口高 ${bounds.height}）",
                c.y >= -0.001f,
            )
            assertTrue(
                "组件 ${c.id} 的右边界 ${c.x + c.width} 超出窗口宽 ${bounds.width}",
                c.x + c.width <= bounds.width + 0.001f,
            )
            assertTrue(
                "组件 ${c.id} 的下边界 ${c.y + c.height} 超出窗口高 ${bounds.height}",
                c.y + c.height <= bounds.height + 0.001f,
            )
        }
    }

    @Test
    fun `默认布局的全部组件都落在窗口内`() {
        // 默认布局就是用户第一次打开看到的东西，它错了就是"功能一上来就坏的"
        assertAllInsideWindow(defaultCustomComponents())
    }

    @Test
    fun `左上角对齐到窗口原点`() {
        val components = listOf(
            component("a", x = 120f, y = 340f),
            component("b", x = 400f, y = 500f),
        )

        val windowed = CustomLayout.toWindow(components)

        // 包围盒左上角是 (120, 340)，所以最靠左上的那个组件应该落在 (0, 0)
        assertEquals(0f, windowed.components[0].x, 0.001f)
        assertEquals(0f, windowed.components[0].y, 0.001f)
        // 另一个相对它偏移 (280, 160)
        assertEquals(280f, windowed.components[1].x, 0.001f)
        assertEquals(160f, windowed.components[1].y, 0.001f)
        assertEquals(280f + 80f, windowed.bounds.width, 0.001f)
        assertEquals(160f + 80f, windowed.bounds.height, 0.001f)
    }

    /**
     * 负坐标是最容易出错的一种：`x - left` 里 left 本身是负数，
     * 减负数就是加 —— 直觉上很容易写错成"减去绝对值"。
     */
    @Test
    fun `负坐标的组件也能落在窗口内`() {
        val components = listOf(
            component("a", x = -40f, y = -25f),
            component("b", x = 20f, y = 30f),
        )

        assertAllInsideWindow(components)

        val windowed = CustomLayout.toWindow(components)
        assertEquals("包围盒从 -40 开始", 0f, windowed.components[0].x, 0.001f)
        assertEquals(60f, windowed.components[1].x, 0.001f)
        assertEquals(140f, windowed.bounds.width, 0.001f)
    }

    @Test
    fun `远距离的组件不会跑出窗口`() {
        // 用户把组件摆到定位区很靠后的位置 —— 正是"不平移就看不见"的场景
        val components = listOf(
            component("a", x = 500f, y = 520f),
            component("b", x = 550f, y = 560f),
        )

        assertAllInsideWindow(components)
    }

    @Test
    fun `重叠的组件搬家后还是重叠的`() {
        // 平移是刚体变换：相对位置不该变
        val a = component("a", x = 100f, y = 100f, width = 100f, height = 100f)
        val b = component("b", x = 140f, y = 130f, width = 80f, height = 80f)

        val windowed = CustomLayout.toWindow(listOf(a, b))

        assertTrue(
            "原来重叠，搬完还该重叠",
            CustomLayout.overlaps(windowed.components[0], windowed.components[1]),
        )
    }

    @Test
    fun `搬家不改变尺寸与其它字段`() {
        val source = component("a", x = 200f, y = 300f, width = 120f, height = 45f)

        val windowed = CustomLayout.toWindow(listOf(source))

        val moved = windowed.components[0]
        assertEquals(source.width, moved.width, 0.001f)
        assertEquals(source.height, moved.height, 0.001f)
        assertEquals(source.id, moved.id)
        assertEquals(source.style, moved.style)
    }

    @Test
    fun `空布局搬家不崩且窗口不为零`() {
        val windowed = CustomLayout.toWindow(emptyList())

        assertTrue(windowed.components.isEmpty())
        assertTrue(windowed.bounds.width > 0f && windowed.bounds.height > 0f)
    }

    @Test
    fun `窗口尺寸与内容尺寸一致`() {
        // 尺寸必须**刚好**框住内容：多了是留白，少了是裁掉
        val components = listOf(
            component("a", x = 30f, y = 30f, width = 80f, height = 80f),
            component("b", x = 130f, y = 60f, width = 40f, height = 40f),
        )

        val windowed = CustomLayout.toWindow(components)

        val rightMost = windowed.components.maxOf { it.x + it.width }
        val bottomMost = windowed.components.maxOf { it.y + it.height }
        assertEquals("右边界要正好贴住窗口右边", windowed.bounds.width, rightMost, 0.001f)
        assertEquals("下边界要正好贴住窗口下边", windowed.bounds.height, bottomMost, 0.001f)
    }
}

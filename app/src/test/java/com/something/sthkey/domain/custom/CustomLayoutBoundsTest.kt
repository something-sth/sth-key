package com.something.sthkey.domain.custom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮窗范围（内容包围盒）的测试。
 *
 * ============================================================
 * 为什么必须测
 * ============================================================
 * 窗口尺寸与内容平移是**两个**互相依赖的值，而且各自在两个地方被消费
 * （尺寸 → 样式注册表；平移 → 渲染层）。算错的后果都不是"显示难看"，
 * 而是**内容跑到窗口外面、在屏幕上彻底看不见** —— 用户只会说
 * "我的布局没了"，完全联想不到是尺寸算错。
 *
 * 这三件事最容易错，所以各钉一条：
 * 1. 包围盒要覆盖到**最右边/最下边**组件的 `x + width`（漏了 `+width` 就裁掉一半）；
 * 2. 偏移是包围盒左上角，**负坐标也成立**（组件可以贴到定位区外沿）；
 * 3. 空列表不能返回 0（`WindowManager` 会拒绝 0 尺寸的窗口）。
 */
class CustomLayoutBoundsTest {

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
     * 包围盒
     * ============================================================
     */

    @Test
    fun `单个组件时窗口就是它自己`() {
        val bounds = CustomLayout.bounds(listOf(component("a", x = 30f, y = 40f)))

        assertEquals(30f, bounds.offsetX, 0.001f)
        assertEquals(40f, bounds.offsetY, 0.001f)
        assertEquals(80f, bounds.width, 0.001f)
        assertEquals(80f, bounds.height, 0.001f)
        assertTrue(bounds.visible)
    }

    @Test
    fun `两个组件时窗口是它们的最小外接矩形`() {
        val a = component("a", x = 10f, y = 10f, width = 100f, height = 50f)
        val b = component("b", x = 60f, y = 90f, width = 40f, height = 30f)

        val bounds = CustomLayout.bounds(listOf(a, b))

        // 左/上 = min(10, 60) / min(10, 90)
        assertEquals(10f, bounds.offsetX, 0.001f)
        assertEquals(10f, bounds.offsetY, 0.001f)
        // 右/下 = max(110, 100) / max(60, 120)
        assertEquals(100f, bounds.width, 0.001f)
        assertEquals(110f, bounds.height, 0.001f)
    }

    /**
     * 这条钉的是"漏掉 `+ width`"这个经典错法。
     *
     * 只用 `maxOf { it.x }` 算宽度的话，最右边那个组件会被裁掉一半 ——
     * 而且只有当两个组件的**右边**才是整体右边界时才暴露。
     */
    @Test
    fun `宽度用的是右边界而不是左边界的最大值`() {
        // 左边的组件很宽，右边界的组件反而窄：整体右边界来自第一个
        val wide = component("wide", x = 0f, y = 0f, width = 200f, height = 20f)
        val narrow = component("narrow", x = 150f, y = 0f, width = 10f, height = 20f)

        val bounds = CustomLayout.bounds(listOf(wide, narrow))

        assertEquals("右边界是 200 不是 160", 200f, bounds.width, 0.001f)
    }

    @Test
    fun `负坐标时偏移是负的`() {
        // 组件可以贴到定位区外沿，坐标允许为负（这正是"贴边"的用法）
        val bounds = CustomLayout.bounds(listOf(component("a", x = -40f, y = -25f)))

        assertEquals(-40f, bounds.offsetX, 0.001f)
        assertEquals(-25f, bounds.offsetY, 0.001f)
        assertEquals(80f, bounds.width, 0.001f)
        assertEquals(80f, bounds.height, 0.001f)
        assertTrue("负坐标不代表没有内容", bounds.visible)
    }

    @Test
    fun `包围盒与组件列表顺序无关`() {
        val a = component("a", x = 10f, y = 200f, width = 50f, height = 50f)
        val b = component("b", x = 300f, y = 10f, width = 50f, height = 50f)

        val forward = CustomLayout.bounds(listOf(a, b))
        val backward = CustomLayout.bounds(listOf(b, a))

        assertEquals(forward, backward)
    }

    @Test
    fun `重叠的组件包围盒等于它们之中最大的那个`() {
        val outer = component("outer", x = 0f, y = 0f, width = 100f, height = 100f)
        val inner = component("inner", x = 20f, y = 20f, width = 10f, height = 10f)

        val bounds = CustomLayout.bounds(listOf(outer, inner))

        assertEquals(100f, bounds.width, 0.001f)
        assertEquals(100f, bounds.height, 0.001f)
    }

    /*
     * ============================================================
     * 空布局：不能给 0
     * ============================================================
     */

    @Test
    fun `没有组件时给最小尺寸而不是零`() {
        val bounds = CustomLayout.bounds(emptyList())

        assertFalse("没有组件 = 悬浮窗不该显示", bounds.visible)
        assertTrue(
            "尺寸为 0 的窗口在 WindowManager 里是无效参数，会直接崩",
            bounds.width > 0f && bounds.height > 0f,
        )
    }

    /*
     * ============================================================
     * 定位区与窗口是两件事
     * ============================================================
     */

    /**
     * 这条钉的是整次改动的**核心解耦**：滑块的取值范围来自定位区（固定 600），
     * 与窗口尺寸无关。两者一旦重新耦合，就会回到"改组件位置 → 画布尺寸变 →
     * 滑块范围变"的自我递归。
     */
    @Test
    fun `定位区固定而窗口跟着内容变`() {
        val tiny = listOf(component("a", x = 0f, y = 0f, width = 20f, height = 20f))
        val big = listOf(component("a", x = 0f, y = 0f, width = 300f, height = 300f))

        // 定位区：与内容无关
        assertEquals(CustomLayout.BASE_CANVAS, CustomLayout.canvasWidth(tiny))
        assertEquals(CustomLayout.BASE_CANVAS, CustomLayout.canvasWidth(big))

        // 窗口：跟着内容变
        assertEquals(20f, CustomLayout.bounds(tiny).width, 0.001f)
        assertEquals(300f, CustomLayout.bounds(big).width, 0.001f)
    }

    @Test
    fun `滑块上界不随窗口尺寸变化`() {
        /*
         * ⚠️ 坐标范围这一版改成**固定**的 `-1000 .. 1000`，不再与
         * 定位区（600）或内容包围盒挂钩。
         *
         * 早先下界是 `-尺寸`、上界是定位区边长 —— 于是"改宽高会让
         * X 滑块的范围跟着变"，用户把组件调小之后原本能拖到的那一端
         * 会突然拖不到。
         *
         * 这条现在钉两件事:
         * 1. 范围与 `BASE_CANVAS` **无关**（否则又会跟着定位区漂）；
         * 2. 范围比定位区宽 —— 大组件要能摆到定位区之外。
         *
         * "与组件尺寸无关"那条在 `CustomLayoutTest` 里。
         */
        assertEquals(CustomLayout.COORDINATE_MAX, CustomLayout.maxCoordinate(), 0.001f)
        assertEquals(CustomLayout.COORDINATE_MIN, CustomLayout.minCoordinate(), 0.001f)
        assertTrue(
            "坐标上界必须比定位区宽，否则大组件摆不出去",
            CustomLayout.maxCoordinate() > CustomLayout.BASE_CANVAS,
        )
    }

    /*
     * ============================================================
     * 默认布局
     * ============================================================
     */

    @Test
    fun `默认布局的窗口贴合内容而不是定位区`() {
        val bounds = CustomLayout.bounds(defaultCustomComponents())

        assertTrue("默认布局该比定位区小得多", bounds.width < CustomLayout.BASE_CANVAS / 3f)
        assertTrue(bounds.height < CustomLayout.BASE_CANVAS / 3f)
        // 默认布局从 (10,10) 开始
        assertEquals(10f, bounds.offsetX, 0.001f)
        assertEquals(10f, bounds.offsetY, 0.001f)
    }
}

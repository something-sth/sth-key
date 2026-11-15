package com.something.sthkey.domain.custom

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 区域标题的**文案规则**（方案 §11.2）。
 *
 * ============================================================
 * ⚠️ 为什么这几条值得单测
 * ============================================================
 * 这段文字同时出现在**面板的行标题**和**屏幕上的方块**里 ——
 * 它是用户判断"这块区域到底绑没绑东西"的唯一依据。
 *
 * 而它的三种形态（没绑 / 绑一个 / 绑多个）都在真机上很难一条条试:
 * 没绑的样子最容易写错成"看起来和绑了一样"（旧模型最坑人的地方）。
 */
class TouchRegionDisplayTest {

    private fun key(id: String, label: String) = KeyComponent(
        id = id,
        x = 0f,
        y = 0f,
        width = 80f,
        height = 80f,
        style = ComponentStyle(),
        label = label,
        inputKeyCodes = emptyList(),
    )

    private fun text(id: String, value: String) = TextComponent(
        id = id,
        x = 0f,
        y = 0f,
        width = 120f,
        height = 30f,
        style = ComponentStyle(),
        text = value,
    )

    private fun stick(id: String) = JoystickComponent(
        id = id,
        x = 0f,
        y = 0f,
        width = 100f,
        height = 100f,
        source = JoystickSource.GAMEPAD,
        side = StickSide.LEFT,
    )

    @Test
    fun `没绑定要明说未绑定（不是只写类型）`() {
        val label = touchRegionLabel(
            kind = CustomTouchRegion.Kind.BUTTON,
            bindings = emptyList(),
            components = emptyList(),
        )
        assertEquals("按键区域  未绑定 ⚠", label)
    }

    @Test
    fun `一个按键组件`() {
        val components = listOf(key("a", "A"))
        assertEquals(
            "按键区域 (按键 · A)",
            touchRegionLabel(CustomTouchRegion.Kind.BUTTON, listOf("a" to null), components),
        )
    }

    @Test
    fun `多个组件按组件列表顺序排（不按勾选先后）`() {
        val components = listOf(key("a", "A"), text("t", "CPS"))
        /* 绑定顺序故意反过来 */
        val bindings = listOf<Pair<String, TouchDirection?>>("t" to null, "a" to null)
        assertEquals(
            "按键区域 (按键 · A、文本 · CPS)",
            touchRegionLabel(CustomTouchRegion.Kind.BUTTON, bindings, components),
        )
    }

    @Test
    fun `方向用箭头缀在组件名后面`() {
        val components = listOf(key("a", "A"))
        val bindings = listOf<Pair<String, TouchDirection?>>(
            "a" to TouchDirection.LEFT,
            "a" to TouchDirection.FRONT,
        )
        assertEquals(
            /* FRONT 的 ordinal 比 LEFT 小 → ↑ 在前 */
            "轮盘区域 (按键 · A ↑←)",
            touchRegionLabel(CustomTouchRegion.Kind.MOVE_STICK, bindings, components),
        )
    }

    @Test
    fun `摇杆组件用 displayName（与自定义编辑页一致）`() {
        val components = listOf(stick("j"))
        assertEquals(
            "轮盘区域 (手柄摇杆 · 左摇杆)",
            touchRegionLabel(CustomTouchRegion.Kind.MOVE_STICK, listOf("j" to null), components),
        )
    }

    @Test
    fun `组件被删掉时绑定回退显示 id（不能当成没绑）`() {
        assertEquals(
            "按键区域 (ghost)",
            touchRegionLabel(
                CustomTouchRegion.Kind.BUTTON,
                listOf("ghost" to null),
                components = listOf(key("a", "A")),
            ),
        )
    }

    @Test
    fun `八个方向都有箭头且不重复`() {
        val arrows = (TouchDirection.entries.map { directionArrow(it) } + directionArrow(null))
        assertEquals("方向字符不该重复:$arrows", arrows.size, arrows.toSet().size)
        assertEquals("无", directionArrow(null))
        assertEquals("↖", directionArrow(TouchDirection.FRONT_LEFT))
        assertEquals("↘", directionArrow(TouchDirection.BACK_RIGHT))
    }
}

package com.something.sthkey.domain.custom

import com.something.sthkey.domain.config.TouchRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「被按到的区域 → 真正触发的组件」的判定，**把方向也算进去**。
 *
 * ============================================================
 * ⚠️ 这条为什么必须单测
 * ============================================================
 * 用户报过"8 向没有生效"——而它的实际形态是:
 *
 * | 错法 | 现象 |
 * |---|---|
 * | 只做"区域 → 绑定"的平铺 | 一块轮盘绑了前/后/左/右四个组件，手指一碰**四个一起亮** |
 * | 每帧重判"手指现在在不在区域里" | 边缘上一帧算一帧不算 → **一顿一顿** |
 *
 * 这两件事在真机上只表现为"看着不对"，很难说出是哪一条;而在
 * 纯函数这一层，8 个方向可以逐条精确断言。
 *
 * ⚠️ 本测试**不碰** `TouchFrameTracker`（它负责算向量），
 * 只验"拿到向量之后怎么判"—— 两者接缝正是最容易漏的地方。
 */
class TouchBindingResolutionTest {

    private fun region(
        id: String,
        kind: CustomTouchRegion.Kind = CustomTouchRegion.Kind.MOVE_STICK,
        bindings: List<CustomTouchRegionBinding> = emptyList(),
    ) = CustomTouchRegion(
        id = id,
        kind = kind,
        region = TouchRegion(0.5f, 0.5f, 0.3f),
        bindings = bindings,
    )

    /** 每个方向一个"标准向量"（与 `TouchDirection.resolve` 的 45° 扇区对应） */
    private fun vectorOf(direction: TouchDirection): Pair<Float, Float> = when (direction) {
        TouchDirection.FRONT -> 0f to -1f
        TouchDirection.BACK -> 0f to 1f
        TouchDirection.LEFT -> -1f to 0f
        TouchDirection.RIGHT -> 1f to 0f
        TouchDirection.FRONT_LEFT -> -1f to -1f
        TouchDirection.FRONT_RIGHT -> 1f to -1f
        TouchDirection.BACK_LEFT -> -1f to 1f
        TouchDirection.BACK_RIGHT -> 1f to 1f
    }

    @Test
    fun `没按到的区域不触发任何组件`() {
        val regions = listOf(
            region("r", bindings = listOf(CustomTouchRegionBinding(componentId = "c"))),
        )
        assertTrue(
            touchPressedComponents(regions, pressedRegionIds = emptySet()) { 0f to 0f }.isEmpty(),
        )
    }

    @Test
    fun `按键区域不带方向按到就算`() {
        val regions = listOf(
            region(
                id = "btn",
                kind = CustomTouchRegion.Kind.BUTTON,
                bindings = listOf(CustomTouchRegionBinding(componentId = "c")),
            ),
        )
        /* ⚠️ 按键区域**没有向量**（vectorOf 返回 null），仍然要算按下 */
        val pressed = touchPressedComponents(regions, setOf("btn")) { null }
        assertEquals(setOf("c"), pressed)
    }

    @Test
    fun `轮盘区域绑整块时也不看方向`() {
        val regions = listOf(
            region("stick", bindings = listOf(CustomTouchRegionBinding(componentId = "joy"))),
        )
        val pressed = touchPressedComponents(regions, setOf("stick")) { 0f to -1f }
        assertEquals(setOf("joy"), pressed)
    }

    @Test
    fun `方向不匹配时不触发`() {
        val regions = listOf(
            region(
                "stick",
                bindings = listOf(
                    CustomTouchRegionBinding(componentId = "w", direction = TouchDirection.FRONT),
                ),
            ),
        )
        /* 手指往右推 → 只应触发"右"，"前"不成立 */
        assertTrue(touchPressedComponents(regions, setOf("stick")) { 1f to 0f }.isEmpty())
        /* 往上推 → 触发 */
        assertEquals(
            setOf("w"),
            touchPressedComponents(regions, setOf("stick")) { 0f to -1f },
        )
    }

    @Test
    fun `八向各推一次只触发各自那一个组件`() {
        /* 一块区域同时绑 8 个组件，每个组件一个方向 —— 用户要的八段式玩法 */
        val bindings = TouchDirection.entries.map {
            CustomTouchRegionBinding(componentId = "c_${it.id}", direction = it)
        }
        val regions = listOf(region("stick", bindings = bindings))

        TouchDirection.entries.forEach { direction ->
            val pressed = touchPressedComponents(regions, setOf("stick")) { vectorOf(direction) }
            assertEquals(
                "推 ${direction.label} 时应当只触发它自己",
                setOf("c_${direction.id}"),
                pressed,
            )
        }
    }

    @Test
    fun `斜推只命中斜向（不会同时点亮两个正向）`() {
        val regions = listOf(
            region(
                "stick",
                bindings = listOf(
                    CustomTouchRegionBinding(componentId = "w", direction = TouchDirection.FRONT),
                    CustomTouchRegionBinding(componentId = "a", direction = TouchDirection.LEFT),
                    CustomTouchRegionBinding(componentId = "wa", direction = TouchDirection.FRONT_LEFT),
                ),
            ),
        )
        assertEquals(
            setOf("wa"),
            touchPressedComponents(regions, setOf("stick")) { -1f to -1f },
        )
    }

    @Test
    fun `刚按下时的微小抖动不算方向`() {
        val regions = listOf(
            region(
                "stick",
                bindings = listOf(
                    CustomTouchRegionBinding(componentId = "w", direction = TouchDirection.FRONT),
                ),
            ),
        )
        /* ⚠️ 向量存在、但长度在死区内 → 方向不成立（否则手指一碰四个方向全亮） */
        assertTrue(
            touchPressedComponents(regions, setOf("stick")) { 0.01f to -0.01f }.isEmpty(),
        )
    }

    @Test
    fun `没有向量的轮盘区域即使绑了方向也不触发（防止凭空亮）`() {
        val regions = listOf(
            region(
                "stick",
                bindings = listOf(
                    CustomTouchRegionBinding(componentId = "w", direction = TouchDirection.FRONT),
                ),
            ),
        )
        assertTrue(touchPressedComponents(regions, setOf("stick")) { null }.isEmpty())
    }

    @Test
    fun `多块区域可以同时触发各自的组件`() {
        val regions = listOf(
            region(
                "r1",
                bindings = listOf(CustomTouchRegionBinding(componentId = "a")),
            ),
            region(
                "r2",
                bindings = listOf(
                    CustomTouchRegionBinding(componentId = "w", direction = TouchDirection.FRONT),
                ),
            ),
        )
        val pressed = touchPressedComponents(regions, setOf("r1", "r2")) { regionId ->
            if (regionId == "r2") 0f to -1f else null
        }
        assertEquals(setOf("a", "w"), pressed)
    }
}

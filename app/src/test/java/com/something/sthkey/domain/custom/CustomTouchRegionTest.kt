package com.something.sthkey.domain.custom

import com.something.sthkey.domain.config.TouchRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 新模型（**区域是配置级的一等数据**）的基本约束。
 *
 * ============================================================
 * ⚠️⚠️ 用户明确要求的第一条:默认**必须**是空的
 * ============================================================
 * 用户的原话:
 *
 * > 注意，**不要在自定义 key 中默认加触摸区域**，默认应该是空的，
 * > 否则会出现有用户用的键盘，手柄之类的**还启动触屏监听**，
 * > 不符合直觉与逻辑
 *
 * ⚠️ 这条不是"顺手记一下"，它有一个**可测的后果**:
 * `usesTouchCapture` 只看区域列表非不非空 —— 所以
 * "默认空"直接等于"纯键盘/手柄布局不启动触摸采集"。
 */
class CustomTouchRegionTest {

    @Test
    fun `新建的自定义布局没有触摸区域`() {
        assertEquals(emptyList<CustomTouchRegion>(), CustomLayoutSettings().touchRegions)
    }

    @Test
    fun `默认组件列表仍然有东西（默认空的是区域，不是组件）`() {
        /*
         * ⚠️ 这两件事很容易混:用户要的是"区域默认空"，
         * **不是**"画布默认空" —— 后者会让新建配置打开就是白板。
         */
        assertTrue(CustomLayoutSettings().components.isNotEmpty())
    }

    @Test
    fun `区域默认没有任何绑定`() {
        val region = CustomTouchRegion(
            id = "r1",
            region = TouchRegion(0.5f, 0.5f, 0.2f),
        )
        assertEquals(emptyList<CustomTouchRegionBinding>(), region.bindings)
    }

    @Test
    fun `区域默认是按键类型`() {
        val region = CustomTouchRegion(id = "r1", region = TouchRegion(0.5f, 0.5f, 0.2f))
        assertEquals(CustomTouchRegion.Kind.BUTTON, region.kind)
    }

    @Test
    fun `认不出的类型回退到按键区域`() {
        /*
         * ⚠️ 回退到**按键**而不是轮盘，是刻意的:
         * 轮盘区域会让绑定的按键/文本组件"只在某个方向上生效"，
         * 而按键区域是"按到就算"—— 后者是**更保守**的那种
         * （认错方向会让组件莫名其妙不亮，认成按键最多是少了方向判断）。
         */
        assertEquals(CustomTouchRegion.Kind.BUTTON, CustomTouchRegion.Kind.fromId("看不懂"))
        assertEquals(CustomTouchRegion.Kind.BUTTON, CustomTouchRegion.Kind.fromId(null))
    }

    @Test
    fun `四种方向都能认回来`() {
        TouchDirection.entries.forEach { direction ->
            assertEquals(direction, TouchDirection.fromId(direction.id))
        }
    }

    @Test
    fun `认不出的方向当作没有方向`() {
        /*
         * ⚠️ `null` = "按到就算"。猜一个方向的话，组件会
         * **只在某个方向上**亮，而用户完全看不出为什么。
         */
        assertNull(TouchDirection.fromId("斜着"))
        assertNull(TouchDirection.fromId(null))
    }

    @Test
    fun `一边区域可以绑多个组件`() {
        val region = CustomTouchRegion(
            id = "r1",
            kind = CustomTouchRegion.Kind.MOVE_STICK,
            region = TouchRegion(0.25f, 0.75f, 0.3f),
            bindings = listOf(
                CustomTouchRegionBinding(componentId = "k1"),
                CustomTouchRegionBinding(componentId = "k2", direction = TouchDirection.FRONT),
                CustomTouchRegionBinding(componentId = "j1"),
            ),
        )
        assertEquals(3, region.bindings.size)
        assertEquals(listOf("k1", "k2", "j1"), region.bindings.map { it.componentId })
    }

    @Test
    fun `同一块区域可以既绑摇杆又绑带方向的按键`() {
        /*
         * ⚠️ 这正是用户要的玩法:"轮盘区域既可以绑定按键组件/文本组件，
         * 也可以绑定触屏摇杆组件" —— 三者**共存**，不是三选一。
         */
        val region = CustomTouchRegion(
            id = "move",
            kind = CustomTouchRegion.Kind.MOVE_STICK,
            region = TouchRegion(0.25f, 0.75f, 0.3f),
            bindings = listOf(
                CustomTouchRegionBinding(componentId = "stick"),
                CustomTouchRegionBinding(componentId = "key_w", direction = TouchDirection.FRONT),
            ),
        )
        /* 绑摇杆的那条**不带方向**（摇杆上报的是轴） */
        assertNull(region.bindings.first { it.componentId == "stick" }.direction)
        assertEquals(
            TouchDirection.FRONT,
            region.bindings.first { it.componentId == "key_w" }.direction,
        )
    }
}

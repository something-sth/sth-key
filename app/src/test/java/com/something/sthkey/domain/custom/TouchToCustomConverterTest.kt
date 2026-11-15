package com.something.sthkey.domain.custom

import com.something.sthkey.domain.config.KeyStrokesConfig
import com.something.sthkey.domain.config.TouchRegionBinding
import com.something.sthkey.domain.config.TouchSettings
import com.something.sthkey.domain.config.defaultShowSpaceKey
import com.something.sthkey.domain.config.touchingKeyMappings
import com.something.sthkey.domain.style.KeyLayout
import com.something.sthkey.domain.style.StyleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「触屏样式 → 自定义 Key」转换里**触摸区域**那一半。
 *
 * ============================================================
 * ⚠️⚠️ 这个测试是给一个真实 bug 补的
 * ============================================================
 * 用户报:
 *
 * > 自定义 Key 的摇杆**无反应**
 *
 * 根因不在渲染，而在转换:触屏的"轮盘区域"用的槽位是
 * [TouchSettings.JOYSTICK_SLOT_ID]（`@move`）—— 那是**虚拟槽位**，
 * 而它在布局里对应的是 `JOYSTICK_LEFT` 那个摇杆槽位。
 *
 * 转换器当时拿 `"@move"` 去比组件 id 前缀，**永远匹配不到**，
 * 于是轮盘区域转出来是空绑定 —— 摇杆当然不动。
 *
 * ⚠️ 这个 bug 最要命的地方是**整条链路都不报错**:配置合法、区域也在、
 * 只是绑定是空的。所以只能在转换这一步把它钉住。
 */
class TouchToCustomConverterTest {

    /**
     * 一份**与真机同构**的触屏配置。
     *
     * 三个开关必须与 `ConfigStore.create` 对触屏样式的取值一致
     * （`TouchRegionSlotIdTest` 里已经栽过三次，这里照它写）。
     */
    private fun touchConfig(regions: List<TouchRegionBinding>) = KeyStrokesConfig(
        id = "touch",
        name = "触屏",
        styleId = StyleId.TOUCHING,
        keyMappings = touchingKeyMappings(),
        showSpaceKey = defaultShowSpaceKey(StyleId.TOUCHING),
        showShiftKey = true,
        showAButton = false,
        touch = TouchSettings(regions = regions),
    )

    @Test
    fun `轮盘区域必须绑到触屏移动摇杆（不是空绑定）`() {
        val config = touchConfig(
            listOf(
                TouchRegionBinding(
                    slotId = TouchSettings.JOYSTICK_SLOT_ID,
                    centerX = 0.2f,
                    centerY = 0.7f,
                    size = 0.3f,
                    pinned = true,
                ),
            ),
        )

        val custom = TouchToCustomConverter.convert(config).custom
        val region = custom.touchRegions.first { it.kind == CustomTouchRegion.Kind.MOVE_STICK }
        assertEquals(CustomTouchRegion.Kind.MOVE_STICK, region.kind)

        assertTrue(
            "轮盘区域转出来是空绑定 —— 转换后的移动摇杆会怎么推都不动",
            region.bindings.isNotEmpty(),
        )

        val boundId = region.bindings.single().componentId
        val component = custom.components.firstOrNull { it.id == boundId }
        assertNotNull("绑定指向了一个不存在的组件:$boundId", component)

        val stick = component as JoystickComponent
        assertEquals("转换后的摇杆必须跟触摸，而不是手柄", JoystickSource.TOUCH, stick.source)
        assertEquals("轮盘区域推的是**移动**摇杆", StickSide.LEFT, stick.side)

        /* 摇杆上报的是轴，不带方向 */
        assertNull(region.bindings.single().direction)
    }

    @Test
    fun `按键区域必须绑到对应的按键组件`() {
        val config = touchConfig(
            listOf(
                TouchRegionBinding(
                    slotId = KeyLayout.Id.LMB,
                    centerX = 0.8f,
                    centerY = 0.7f,
                    size = 0.2f,
                    pinned = true,
                ),
            ),
        )

        val custom = TouchToCustomConverter.convert(config).custom
        /* ⚠️ 生效区域 = 配过的 LMB + 其余槽位的默认坐标，所以不能再用 single() */
        val region = custom.touchRegions.first { it.id.startsWith("${KeyLayout.Id.LMB}_") }
        assertEquals(CustomTouchRegion.Kind.BUTTON, region.kind)

        val boundId = region.bindings.single().componentId
        val component = custom.components.firstOrNull { it.id == boundId }
        assertNotNull("绑定指向了一个不存在的组件:$boundId", component)
        assertTrue("按键区域应当绑到按键组件，实际是 ${component!!::class.simpleName}", component is KeyComponent)
    }

    @Test
    fun `所有有绑定的区域都能在组件列表里找到目标`() {
        val config = touchConfig(
            listOf(
                TouchRegionBinding(TouchSettings.JOYSTICK_SLOT_ID, 0.2f, 0.7f, 0.3f, pinned = true),
                TouchRegionBinding(KeyLayout.Id.LMB, 0.8f, 0.7f, 0.2f, pinned = true),
                TouchRegionBinding(KeyLayout.Id.SPACE, 0.5f, 0.85f, 0.3f, pinned = true),
            ),
        )

        val custom = TouchToCustomConverter.convert(config).custom
        /*
         * ⚠️ 不是 3 块:没配过的 RMB / SHIFT 会按**默认坐标**补上 ——
         * 与悬浮窗现在的行为一致（轮盘 + 4 个按键槽位）。
         */
        assertEquals(5, custom.touchRegions.size)

        val ids = custom.components.map { it.id }.toSet()
        custom.touchRegions.forEach { region ->
            assertEquals(
                "区域 ${region.id} 应当恰好一条绑定（槽位都能认出来）",
                1,
                region.bindings.size,
            )
            assertTrue(
                "区域 ${region.id} 绑到了不存在的组件 ${region.bindings.single().componentId}",
                region.bindings.single().componentId in ids,
            )
        }
    }

    @Test
    fun `区域几何原样搬运（不换算坐标）`() {
        val config = touchConfig(
            listOf(
                TouchRegionBinding(
                    slotId = TouchSettings.JOYSTICK_SLOT_ID,
                    centerX = 0.25f,
                    centerY = 0.65f,
                    size = 0.4f,
                    pinned = true,
                ),
            ),
        )
        val region = TouchToCustomConverter.convert(config).custom.touchRegions
            .first { it.kind == CustomTouchRegion.Kind.MOVE_STICK }
        assertEquals(0.25f, region.region.centerX, 0.0001f)
        assertEquals(0.65f, region.region.centerY, 0.0001f)
        assertEquals(0.4f, region.region.size, 0.0001f)
    }

    @Test
    fun `全新触屏配置（一块都没配）也能转出可用的区域`() {
        /*
         * ⚠️ 用户报的正是这个场景:新建的触屏配置没调过触摸区域，
         * 悬浮窗现在会用默认坐标；转换也必须把这些默认区域带上，
         * 否则"转完的按键按了不亮"。
         */
        val config = touchConfig(emptyList())
        val result = TouchToCustomConverter.convert(config)
        val regions = result.custom.touchRegions

        assertEquals("轮盘 + 4 个按键槽位的默认区域", 5, regions.size)
        regions.forEach { region ->
            assertTrue(
                "区域 ${region.id} 没有绑定 —— 转换后按了没反应",
                region.bindings.isNotEmpty(),
            )
        }

        val move = regions.first { it.kind == CustomTouchRegion.Kind.MOVE_STICK }
        val stick = result.custom.components.first { it.id == move.bindings.single().componentId }
        assertEquals(JoystickSource.TOUCH, (stick as JoystickComponent).source)
        assertEquals(StickSide.LEFT, stick.side)

        assertTrue(
            "说明里应如实告诉用户用了默认坐标，实际=${result.notes}",
            result.notes.any { it.contains("默认触摸区域坐标") },
        )
    }
}

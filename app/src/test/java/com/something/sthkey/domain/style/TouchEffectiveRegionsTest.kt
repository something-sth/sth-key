package com.something.sthkey.domain.style

import com.something.sthkey.domain.config.KeyStrokesConfig
import com.something.sthkey.domain.config.TouchRegionBinding
import com.something.sthkey.domain.config.TouchRegionSpec
import com.something.sthkey.domain.config.TouchSettings
import com.something.sthkey.domain.config.defaultShowSpaceKey
import com.something.sthkey.domain.config.touchingKeyMappings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 触屏样式的**默认区域生效**（用户 2026-11-15 的反馈）。
 *
 * ============================================================
 * ⚠️ 要守住的规则
 * ============================================================
 * | 情况 | 应有行为 |
 * |---|---|
 * | 新建配置（一块都没配） | 轮盘 + 每个按键槽位都按**默认坐标**生效 |
 * | 配过的槽位 | 用用户的位置，不被默认值覆盖 |
 * | `pinned = false` 的占位 | 忽略（它从来没生效过）|
 * | 当前布局里不存在的槽位 | 不生成（否则会有一块"按不到任何东西"的野区域）|
 *
 * ⚠️ 这里的"默认坐标"必须与编辑器画出来的位置**同一个函数**
 * （`TouchRegionSpec.defaultCenterFor`），否则用户会看到
 * "编辑器里在右上、悬浮窗上却在别处"。
 */
class TouchEffectiveRegionsTest {

    /**
     * 一份与真机同构的触屏配置。
     * 三个开关与 `ConfigStore.create` 对触屏的取值一致（照 `TouchRegionSlotIdTest`）。
     */
    private fun config(regions: List<TouchRegionBinding> = emptyList()) = KeyStrokesConfig(
        id = "touch",
        name = "触屏",
        styleId = StyleId.TOUCHING,
        keyMappings = touchingKeyMappings(),
        showSpaceKey = defaultShowSpaceKey(StyleId.TOUCHING),
        showShiftKey = true,
        showAButton = false,
        touch = TouchSettings(regions = regions),
    )

    /** 触屏会显示在悬浮窗上的按键槽位（按渲染顺序） */
    private fun expectedSlots(): List<String> = listOf(
        KeyLayout.Id.LMB,
        KeyLayout.Id.RMB,
        KeyLayout.Id.SPACE,
        KeyLayout.Id.SHIFT,
    )

    @Test
    fun `全新配置里轮盘和每个按键都有默认区域`() {
        val effective = touchEffectiveRegions(config())

        assertEquals("应当是轮盘 + 4 个按键槽位", 5, effective.size)
        assertEquals(
            "第一个必须是轮盘（移动摇杆靠它）",
            TouchSettings.JOYSTICK_SLOT_ID,
            effective.first().slotId,
        )
        assertEquals(
            "其余按渲染顺序",
            expectedSlots(),
            effective.drop(1).map { it.slotId },
        )

        val move = effective.first()
        assertEquals(TouchRegionSpec.MOVE_DEFAULT.centerX, move.centerX, 0.0001f)
        assertEquals(TouchRegionSpec.MOVE_DEFAULT.centerY, move.centerY, 0.0001f)

        val lmb = effective.first { it.slotId == KeyLayout.Id.LMB }
        val (expectedX, expectedY) = TouchRegionSpec.defaultCenterFor(0)
        assertEquals("按键默认位置必须与编辑器同一个函数", expectedX, lmb.centerX, 0.0001f)
        assertEquals(expectedY, lmb.centerY, 0.0001f)
        assertEquals(TouchRegionSpec.SIZE_DEFAULT, lmb.size, 0.0001f)
    }

    @Test
    fun `默认区域都是生效状态（pinned）`() {
        /* ⚠️ 下游凡是按 pinned 过滤的地方都要认它；不置 true 的话又变回"没生效" */
        assertTrue(touchEffectiveRegions(config()).all { it.pinned })
    }

    @Test
    fun `配过的几何优先于默认值`() {
        val effective = touchEffectiveRegions(
            config(
                listOf(
                    TouchRegionBinding(
                        slotId = TouchSettings.JOYSTICK_SLOT_ID,
                        centerX = 0.15f,
                        centerY = 0.65f,
                        size = 0.18f,
                        pinned = true,
                    ),
                    TouchRegionBinding(
                        slotId = KeyLayout.Id.LMB,
                        centerX = 0.9f,
                        centerY = 0.8f,
                        size = 0.2f,
                        pinned = true,
                    ),
                ),
            ),
        )

        val move = effective.first { it.slotId == TouchSettings.JOYSTICK_SLOT_ID }
        assertEquals(0.15f, move.centerX, 0.0001f)
        assertEquals(0.18f, move.size, 0.0001f)

        val lmb = effective.first { it.slotId == KeyLayout.Id.LMB }
        assertEquals(0.9f, lmb.centerX, 0.0001f)
        assertEquals(0.8f, lmb.centerY, 0.0001f)
        assertEquals(0.2f, lmb.size, 0.0001f)
    }

    @Test
    fun `pinned 为 false 的占位条目不算配置`() {
        /*
         * ⚠️ 编辑器会画占位并把 `pinned` 留 false。那些从来没生效过 ——
         * 如果被当成"配过的"，用户会看到默认坐标被一个编辑器内存里的
         * 临时位置顶掉（而且下次打开又变回去）。
         */
        val effective = touchEffectiveRegions(
            config(
                listOf(
                    TouchRegionBinding(
                        slotId = KeyLayout.Id.LMB,
                        centerX = 0.01f,
                        centerY = 0.01f,
                        size = 0.1f,
                        pinned = false,
                    ),
                ),
            ),
        )

        val lmb = effective.first { it.slotId == KeyLayout.Id.LMB }
        val (expectedX, expectedY) = TouchRegionSpec.defaultCenterFor(0)
        assertEquals(expectedX, lmb.centerX, 0.0001f)
        assertEquals(expectedY, lmb.centerY, 0.0001f)
    }

    @Test
    fun `当前布局里不存在的槽位不会生成区域`() {
        /*
         * ⚠️ 老配置/手改 JSON 可能留着已经没有按键的槽位。
         * 生成一块"按不到任何东西"的区域只会让调试更困惑。
         */
        val effective = touchEffectiveRegions(
            config(
                listOf(
                    TouchRegionBinding(
                        slotId = KeyLayout.Id.W,
                        centerX = 0.5f,
                        centerY = 0.5f,
                        size = 0.2f,
                        pinned = true,
                    ),
                ),
            ),
        )

        assertFalse(
            "触屏布局里没有 W 槽位，不该出现在生效区域里",
            effective.any { it.slotId == KeyLayout.Id.W },
        )
        assertEquals(5, effective.size)
    }
}

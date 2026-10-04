package com.something.sthkey.domain.style

import com.something.sthkey.domain.config.JoystickStyle
import com.something.sthkey.domain.config.KeyStrokesConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 按键**间距**滑块与摇杆**缩放上限**。
 *
 * ============================================================
 * 两者的共同点:都在"锁住某个量"的前提下变化
 * ============================================================
 * | 设置 | 锁住的量 | 变化的量 |
 * |---|---|---|
 * | 按键间距 | **外框跨度 260** | 键宽（间距大 → 键窄） |
 * | 摇杆缩放 | **两个摇杆不重叠** | 摇杆边长 |
 *
 * ⚠️ 如果锁错了量，表现是"改一个设置，窗口尺寸跟着变"——
 * 而那是用户明确要求避免的（"记得悬浮窗尺寸要算好"）。
 */
class KeyGapAndStickScaleTest {

    private fun config(
        gapPercent: Int = 100,
        styleId: String = StyleId.KEYSTROKES,
        joystick: JoystickStyle = JoystickStyle(),
    ) = KeyStrokesConfig(
        id = "test",
        name = "测试",
        styleId = styleId,
        keyGapPercent = gapPercent,
        joystick = joystick,
    )

    private fun boxesOf(config: KeyStrokesConfig) = KeyLayout.keys(config)

    private fun contentLeft(config: KeyStrokesConfig) =
        boxesOf(config).minOf { it.centerX - it.width / 2f }

    private fun contentRight(config: KeyStrokesConfig) =
        boxesOf(config).maxOf { it.centerX + it.width / 2f }

    /* ============================================================
     * 按键间距:只改位置、不改尺寸
     * ============================================================ */

    /**
     * ⚠️ **核心不变量**:间距怎么调，**键宽都不动**。
     *
     * 用户的原话:"这个调整间距的效果也需要改进一下，不能调整组件大小，
     * 只是起到调整间距的效果，本质是改位置，尺寸不能改"。
     *
     * ⚠️ 第一版做反了（"锁外框、让键宽让位"）—— 那让键跟着变小/变大，
     * 正是用户不要的效果。这条测试把它钉死。
     */
    @Test
    fun `间距怎么调键宽都不动`() {
        listOf(0, 50, 100, 200, 400).forEach { percent ->
            val w = boxesOf(config(gapPercent = percent)).first { it.slotId == "W" }
            assertEquals(
                "间距 $percent% 时键宽必须恒为 80（现在 ${w.width}）—— 间距只改位置",
                80f,
                w.width,
                0.01f,
            )
        }

        /* 鼠标键（= LT/RT）同理 */
        listOf(0, 100, 400).forEach { percent ->
            val lmb = boxesOf(config(gapPercent = percent)).first { it.slotId == "LMB" }
            assertEquals("间距 $percent% 时鼠标键宽必须恒为 125", 125f, lmb.width, 0.01f)
        }
    }

    /**
     * ⚠️ 间距变大 → 内容变宽 → **窗口也变宽**。
     *
     * 这是键宽不能变的**必然结果**（内容 = 键 + 间距）。
     * 所以"窗口宽度是一个常量"这个前提不再成立 ——
     * 样式注册值必须用 `KeyLayout.baseWidth(config)`。
     */
    @Test
    fun `间距变大则内容变宽`() {
        val narrow = config(gapPercent = 0)
        val normal = config(gapPercent = 100)
        val wide = config(gapPercent = 400)

        assertTrue(
            "间距 0% 应当比 100% 窄（${KeyLayout.baseWidth(narrow)} vs " +
                "${KeyLayout.baseWidth(normal)}）",
            KeyLayout.baseWidth(narrow) < KeyLayout.baseWidth(normal),
        )
        assertTrue(
            "间距 400% 应当比 100% 宽",
            KeyLayout.baseWidth(wide) > KeyLayout.baseWidth(normal),
        )

        /* 默认间距下仍然是老值 300（内容 260 + 边距 20×2） */
        assertEquals(
            "默认间距下窗口宽度应当还是 300 —— 默认外观一点没变",
            300f,
            KeyLayout.baseWidth(normal),
            0.01f,
        )
    }

    /**
     * ⚠️ 不论间距多大，内容**必须左右对称**。
     *
     * 这条防的是"中心用固定常量推"那种错 —— 实测过:
     * 间距 50% 时左边界跑到 22.5 而右边界还是 280。
     */
    @Test
    fun `任何间距下内容都左右对称`() {
        listOf(0, 50, 100, 200, 400).forEach { percent ->
            val c = config(gapPercent = percent)
            val width = KeyLayout.baseWidth(c)
            val left = contentLeft(c)
            val right = contentRight(c)

            assertEquals(
                "间距 $percent% 时左边距（$left）与右边距（${width - right}）必须相等",
                left,
                width - right,
                0.01f,
            )
        }
    }

    /** 空格横跨两列，所以它的宽度**跟着间距变**（两列的跨度本身变宽了） */
    @Test
    fun `空格的宽度跟着间距变`() {
        val narrow = boxesOf(config(gapPercent = 0)).first { it.slotId == "SPACE" }
        val wide = boxesOf(config(gapPercent = 400)).first { it.slotId == "SPACE" }

        assertTrue(
            "间距变大后空格应当更宽（${narrow.width} → ${wide.width}）—— " +
                "因为两列的跨度本身变宽了",
            wide.width > narrow.width,
        )
    }

    /** 极端间距下键宽仍然为正（滑块的下限现在是 0%） */
    @Test
    fun `极端间距下键宽仍然为正`() {
        listOf(0, 400).forEach { percent ->
            val w = boxesOf(config(gapPercent = percent)).first { it.slotId == "W" }
            assertTrue("间距 $percent% 时键宽必须 > 0（现在 ${w.width}）", w.width > 0f)
        }
    }

    /* ============================================================
     * 摇杆缩放上限
     * ============================================================ */

    /**
     * ⚠️ **核心不变量**:摇杆放大到上限时，两个摇杆**刚好贴在一起**
     * 而不是重叠。
     *
     * 用户的原话:"摇杆缩放不应该设置那么大的，最大也只是到了
     * '两个摇杆之间没有间距'的时候"。
     */
    @Test
    fun `摇杆放到最大也不会互相重叠`() {
        /* 用一个远大于上限的值 —— 布局必须自己夹住 */
        val c = config(
            styleId = StyleId.GAMEPAD2,
            joystick = JoystickStyle(sizeScale = 99f),
        )
        val left = boxesOf(c).first { it.slotId == KeyLayout.Id.JOYSTICK_LEFT }
        val right = boxesOf(c).first { it.slotId == KeyLayout.Id.JOYSTICK_RIGHT }

        val leftRight = left.centerX + left.width / 2f
        val rightLeft = right.centerX - right.width / 2f

        assertTrue(
            "左摇杆右边 $leftRight 必须在右摇杆左边 $rightLeft 之左（不能重叠）",
            leftRight <= rightLeft + 0.01f,
        )
    }

    @Test
    fun `调试输出摇杆尺寸`() {
        listOf(1f, 1.2f, 99f).forEach { s ->
            val js = JoystickStyle(sizeScale = s)
            println("sizeScale=$s -> JoystickStyle.sizeScale=${js.sizeScale}")
            val c = KeyStrokesConfig(
                id = "t", name = "t", styleId = StyleId.GAMEPAD2, joystick = js,
            )
            KeyLayout.keys(c).filter { it.slotId.startsWith("JOYSTICK") }.forEach {
                println("  ${it.slotId}: width=${it.width} centerX=${it.centerX}")
            }
        }
        org.junit.Assert.assertTrue(true)
    }

    /** 摇杆缩放生效（否则滑块没用） */
    @Test
    fun `摇杆缩放会改变摇杆边长`() {
        val normal = boxesOf(config(styleId = StyleId.GAMEPAD2))
            .first { it.slotId == KeyLayout.Id.JOYSTICK_LEFT }
        val bigger = boxesOf(
            config(styleId = StyleId.GAMEPAD2, joystick = JoystickStyle(sizeScale = 1.05f)),
        ).first { it.slotId == KeyLayout.Id.JOYSTICK_LEFT }

        assertTrue(
            "缩放 1.05 时摇杆应当更大（${normal.width} → ${bigger.width}）",
            bigger.width > normal.width,
        )
        assertEquals("边长应当正好是 1.05 倍", normal.width * 1.05f, bigger.width, 0.01f)
    }

    /**
     * ⚠️ 摇杆缩放**只影响摇杆自己那一行的高度**，不该动宽度。
     *
     * 用户的原话:"摇杆缩放你理解的没错……是整体缩放、摇杆缩放、
     * 摇杆内圆的缩放共同决定的"。
     */
    @Test
    fun `摇杆缩放只改高度不改窗口宽度`() {
        val normal = config(styleId = StyleId.GAMEPAD2)
        val bigger = config(styleId = StyleId.GAMEPAD2, joystick = JoystickStyle(sizeScale = 1.05f))

        assertEquals(
            "注册宽度不该变",
            OverlayStyleRegistry.baseSizeOf(normal).width,
            OverlayStyleRegistry.baseSizeOf(bigger).width,
            0.01f,
        )
        assertTrue(
            "但高度应当变大（摇杆行更高）",
            KeyLayout.baseHeight(bigger) > KeyLayout.baseHeight(normal),
        )
    }

    /** 摇杆缩放**以中心为锚点** —— 放大后中心不动 */
    @Test
    fun `摇杆缩放不改变摇杆中心`() {
        val normal = boxesOf(config(styleId = StyleId.GAMEPAD2))
            .first { it.slotId == KeyLayout.Id.JOYSTICK_LEFT }
        val bigger = boxesOf(
            config(styleId = StyleId.GAMEPAD2, joystick = JoystickStyle(sizeScale = 1.05f)),
        ).first { it.slotId == KeyLayout.Id.JOYSTICK_LEFT }

        assertEquals(
            "放大后中心必须不动，否则摇杆会'往一边跑'",
            normal.centerX,
            bigger.centerX,
            0.01f,
        )
    }
}

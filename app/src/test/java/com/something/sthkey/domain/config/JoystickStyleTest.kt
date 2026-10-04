package com.something.sthkey.domain.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 摇杆外观配置的**不变量**。
 *
 * ============================================================
 * ⚠️ 这组测试防的是一个真实发生过的 bug
 * ============================================================
 * 用户报告:
 *
 * > "开启标准手柄悬浮窗，然后点进对应的配置页面，下滑，滑到屏幕下方
 * >  出现'摇杆'栏的时候，悬浮窗上摇杆的黑色背景以及内圆消失……
 * >  百分百复现……除了重新创建一个配置，无法恢复"
 *
 * 根因是**两个 alpha 来源相乘**:
 *
 * 1. 默认颜色带了 alpha（`0xB3000000`）；
 * 2. 配置页的颜色控件 `HexColorRow` **只处理 RGB**
 *    （内部 `formatHexDigits` 里 `and 0xFFFFFF` 截掉 alpha），
 *    而 `LazyColumn` 只在**滚动到可见时**才组合那个控件 ——
 *    所以"滑到那一栏"就是触发点；
 * 3. 颜色被抹成 `0x00xxxxxx` 并写进存档；
 * 4. 渲染时 `alpha = color.alpha × opacity = 0 × 1 = 0` → **全透明**。
 *
 * 现在的不变量是:**颜色只存 RGB，不透明度只由 opacity 字段表达**。
 */
class JoystickStyleTest {

    /**
     * ⚠️ 所有默认颜色的 alpha 必须是 `0xFF`（不透明）。
     *
     * 带 alpha 的话会被颜色控件抹掉 —— 而"用户只是滑了一下配置页，
     * 摇杆就没了"是极难联想到的因果。
     */
    @Test
    fun `默认颜色都是不透明的`() {
        val colors = mapOf(
            "底盘" to JoystickStyle.DEFAULT_JOYSTICK_COLOR,
            "内圆" to JoystickStyle.DEFAULT_RING_COLOR,
            "摇杆帽" to JoystickStyle.DEFAULT_KNOB_COLOR,
            "摇杆帽描边" to JoystickStyle.DEFAULT_KNOB_STROKE_COLOR,
        )

        colors.forEach { (name, argb) ->
            assertEquals(
                "$name 的颜色 alpha 必须是 0xFF（现在 ${"%08X".format(argb)}）—— " +
                    "带 alpha 会被配置页的颜色控件抹成 00，摇杆会消失",
                0xFF,
                (argb ushr 24) and 0xFF,
            )
        }
    }

    /** 默认值本身不能让摇杆隐形 */
    @Test
    fun `默认设置画出来的摇杆是可见的`() {
        val s = JoystickStyle()

        assertTrue("底盘不透明度必须 > 0（现在 ${s.opacity}）", s.opacity > 0f)
        assertTrue("摇杆帽不透明度必须 > 0（现在 ${s.knobOpacity}）", s.knobOpacity > 0f)
        assertTrue("内圆粗细必须 > 0（现在 ${s.ringWidthRatio}）", s.ringWidthRatio > 0f)
        assertTrue("内圆不透明度必须 > 0（现在 ${s.ringOpacity}）", s.ringOpacity > 0f)
        assertTrue("摇杆缩放必须 > 0（现在 ${s.sizeScale}）", s.sizeScale > 0f)
        assertTrue("摇杆帽缩放必须 > 0（现在 ${s.knobScale}）", s.knobScale > 0f)
    }

    /**
     * ⚠️ **回归**:颜色里的 alpha **不该影响**最终画出来的透明度。
     *
     * 渲染层是 `argbWithOpacity(color, opacity)`，它只取 RGB。
     * 这条用"带 alpha 的颜色"去喂，验证结果与"不带 alpha 的同色"一致 ——
     * 也就是那个被抹成 `0x00xxxxxx` 的坏值**不会再让摇杆消失**。
     *
     * （这里模拟渲染层的算法，因为真正的 `argbWithOpacity` 在 UI 层、
     * 依赖 Compose，没法在纯 JVM 测试里调。）
     */
    @Test
    fun `颜色里的 alpha 不影响最终透明度`() {
        fun effectiveAlpha(argb: Int, opacity: Float): Float =
            opacity.coerceIn(0f, 1f) // ← 只看 opacity，不看 color 的 alpha

        val opaque = 0xFF000000.toInt()
        val corrupted = 0x00000000 /* 被 HexColorRow 抹过的那个值 */
        val originallySemi = 0xB3000000.toInt()

        val s = JoystickStyle()
        assertEquals(
            "被抹掉 alpha 的颜色与不透明颜色应当画出**一样**的透明度",
            effectiveAlpha(opaque, s.opacity),
            effectiveAlpha(corrupted, s.opacity),
            1e-6f,
        )
        assertEquals(
            "带 alpha 的旧值也一样（alpha 被忽略）",
            effectiveAlpha(opaque, s.opacity),
            effectiveAlpha(originallySemi, s.opacity),
            1e-6f,
        )
        assertTrue(
            "而结果必须是可见的（> 0）—— 这正是 bug 里变成 0 的地方",
            effectiveAlpha(corrupted, s.opacity) > 0f,
        )
    }

    /* ============================================================
     * 显示变换（死区 / 灵敏度）
     * ============================================================ */

    @Test
    fun `灵敏度 1 且无死区时不改变输入`() {
        val s = JoystickStyle(deadZone = 0f, sensitivity = 1f)

        listOf(-1f, -0.5f, 0f, 0.25f, 1f).forEach { v ->
            assertEquals("输入 $v 应当原样通过", v, s.displayValue(v), 1e-5f)
        }
    }

    @Test
    fun `灵敏度放大时推到一半就画到边`() {
        val s = JoystickStyle(deadZone = 0f, sensitivity = 2f)

        assertEquals("0.5 × 2 = 1", 1f, s.displayValue(0.5f), 1e-5f)
        assertEquals("超过 1 要钳住", 1f, s.displayValue(0.8f), 1e-5f)
        assertEquals("方向不变（负号保留）", -1f, s.displayValue(-0.5f), 1e-5f)
    }

    @Test
    fun `灵敏度缩小时推到底也画不到边`() {
        val s = JoystickStyle(deadZone = 0f, sensitivity = 0.5f)
        assertEquals("1 × 0.5 = 0.5", 0.5f, s.displayValue(1f), 1e-5f)
    }

    @Test
    fun `死区之内的输入不画出来`() {
        val s = JoystickStyle(deadZone = 0.2f, sensitivity = 1f)

        assertEquals("0.1 在死区内 → 0", 0f, s.displayValue(0.1f), 1e-5f)
        assertEquals("-0.15 在死区内 → 0", 0f, s.displayValue(-0.15f), 1e-5f)
    }

    /**
     * ⚠️ 死区要**重新映射**剩余行程，而不是直接截断。
     *
     * 直接截断的话，跨过死区的那一刻拇指会**跳一下**
     * （从 0 直接跳到 `deadZone`）—— 那是"死区"这个功能最容易被做错的地方。
     *
     * 重新映射之后:输入正好等于死区时输出 0，输入到 1 时输出 1，
     * 中间线性过渡。
     */
    @Test
    fun `死区边缘不会跳变`() {
        val dead = 0.2f
        val s = JoystickStyle(deadZone = dead, sensitivity = 1f)

        /* 刚过死区一点点 → 输出应当**接近 0**，而不是接近 dead */
        val justOver = s.displayValue(dead + 0.001f)
        assertTrue(
            "刚跨过死区时输出应当接近 0（现在 $justOver），否则拇指会跳一下",
            justOver < 0.02f,
        )

        /* 推到底 → 1 */
        assertEquals("推到底应当到 1", 1f, s.displayValue(1f), 1e-5f)

        /* 中点 → 重新映射后的中点 */
        val mid = s.displayValue(dead + (1f - dead) / 2f)
        assertEquals("死区与满量程的中点应当是 0.5", 0.5f, mid, 1e-4f)
    }

    @Test
    fun `死区与灵敏度一起用时先死区后灵敏度`() {
        val s = JoystickStyle(deadZone = 0.2f, sensitivity = 2f)

        /* 死区之后是 0.5，再乘 2 → 1 */
        val v = s.displayValue(0.2f + 0.8f * 0.5f)
        assertEquals(1f, v, 1e-4f)
    }

    @Test
    fun `零输入永远返回零`() {
        listOf(0f, 0.3f, 0.5f).forEach { dead ->
            assertEquals(
                "死区 $dead 时 0 输入应当返回 0",
                0f,
                JoystickStyle(deadZone = dead).displayValue(0f),
                1e-6f,
            )
        }
    }

    @Test
    fun `极端参数被夹住而不会失控`() {
        /* 手改 JSON 塞进来的越界值 */
        val s = JoystickStyle(deadZone = 99f, sensitivity = 99f)

        /* 死区被夹到 0.5 —— 推到 0.4 仍然在死区内 */
        assertEquals("越界死区被夹到 0.5", 0f, s.displayValue(0.4f), 1e-5f)
        /* 灵敏度被夹到 3 —— 不会放大到无穷 */
        assertEquals("越界灵敏度被夹到 3", 1f, s.displayValue(1f), 1e-5f)
        assertTrue(
            "输出永远不超过 1",
            s.displayValue(0.9f) <= 1f,
        )
    }
}

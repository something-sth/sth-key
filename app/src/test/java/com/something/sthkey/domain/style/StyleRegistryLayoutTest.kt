package com.something.sthkey.domain.style

import com.something.sthkey.domain.config.KeyStrokesConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **样式注册表声明的尺寸**必须与**实际渲染出来的内容**一致。
 *
 * ============================================================
 * 为什么值得单独一组测试
 * ============================================================
 * "窗口多大"由 `OverlayStyleRegistry.baseSizeOf` 回答，而"内容画多大"由
 * 各渲染组件自己决定。**两处各算一遍**，一旦不一致就会出真实故障:
 *
 * | 不一致 | 现象 |
 * |---|---|
 * | 注册值偏大 | 悬浮窗里**一大块空气**（用户描述:"右边有一大块空气，约占屏幕宽的三分之一"） |
 * | 注册值偏小 | 内容**被裁掉**（用户描述:"A 键被裁剪了"） |
 *
 * 而这两种现象**都不会报错、也不会崩** —— 只能靠人眼看出来。
 *
 * ⚠️ 它们真的同时发生过一次:把 gamepad2 改成"键盘布局 + 摇杆"之后，
 * 布局换了但注册值还是旧的 `600 × 266` —— 宽了 300（空气）、
 * 矮了 54（裁掉底部的 A 键）。
 *
 * 所以这组测试断言的是**跨模块的不变量**:
 * ```
 * 注册宽度 == 内容宽度
 * 注册高度 >= 内容实际占用（最高的那个槽位的底边）
 * ```
 */
class StyleRegistryLayoutTest {

    private fun config(styleId: String) = KeyStrokesConfig(
        id = "test-$styleId",
        name = "测试",
        styleId = styleId,
    )

    /**
     * 内容实际占用的高度:最高的那个槽位的底边。
     *
     * ⚠️ **不加边距** —— 要的就是"内容真正画到哪里"。
     * 加了边距这条断言就永远成立，也就测不出"内容被裁"。
     */
    private fun contentBottom(config: KeyStrokesConfig): Float {
        val boxes = KeyLayout.keys(config)
        if (boxes.isEmpty()) return 0f
        return boxes.maxOf { it.topY + it.height }
    }

    /** 内容实际占用的宽度:最左与最右的槽位之间的距离 */
    private fun contentWidth(config: KeyStrokesConfig): Float {
        val boxes = KeyLayout.keys(config)
        if (boxes.isEmpty()) return 0f
        val left = boxes.minOf { it.centerX - it.width / 2f }
        val right = boxes.maxOf { it.centerX + it.width / 2f }
        return right - left
    }

    /* ============================================================
     * 键盘样式
     * ============================================================ */

    @Test
    fun `键盘样式的注册宽度与内容一致`() {
        val c = config(StyleId.KEYSTROKES)
        val registered = OverlayStyleRegistry.baseSizeOf(c).width

        assertEquals(
            "注册宽度必须等于 `KeyLayout.BASE_WIDTH`（内容就画那么宽）",
            KeyLayout.BASE_WIDTH,
            registered,
            0.01f,
        )
        assertTrue(
            "内容宽度不能超过注册宽度，否则会被裁（内容 ${contentWidth(c)}，注册 $registered）",
            contentWidth(c) <= registered + 0.01f,
        )
    }

    @Test
    fun `键盘样式的注册高度够装下内容`() {
        val c = config(StyleId.KEYSTROKES)
        val registered = OverlayStyleRegistry.baseSizeOf(c).height

        assertEquals(
            "注册高度必须等于 `KeyLayout.baseHeight`（同一个函数）",
            KeyLayout.baseHeight(c),
            registered,
            0.01f,
        )
        assertTrue(
            "最低那个槽位的底边不能超出注册高度（底边 ${contentBottom(c)}，注册 $registered）",
            contentBottom(c) <= registered + 0.01f,
        )
    }

    /* ============================================================
     * gamepad2（改布局时最容易被漏掉的那个）
     * ============================================================ */

    @Test
    fun `gamepad2 的注册宽度与内容一致`() {
        val c = config(StyleId.GAMEPAD2)
        val registered = OverlayStyleRegistry.baseSizeOf(c).width

        assertEquals(
            "gamepad2 用的是键盘布局，宽度就该是 `BASE_WIDTH`",
            KeyLayout.BASE_WIDTH,
            registered,
            0.01f,
        )
        assertTrue(
            "内容宽度不能超过注册宽度（内容 ${contentWidth(c)}，注册 $registered）",
            contentWidth(c) <= registered + 0.01f,
        )
    }

    /**
     * ⚠️ 这条是**回归测试**:gamepad2 曾经注册成 `600 × 266`，
     * 而内容是 `300 × 320` —— 右边一大块空气 + 底部 A 键被裁。
     */
    @Test
    fun `gamepad2 的注册高度够装下内容`() {
        val c = config(StyleId.GAMEPAD2)
        val registered = OverlayStyleRegistry.baseSizeOf(c).height
        val bottom = contentBottom(c)

        assertTrue(
            "内容底边 $bottom 超出了注册高度 $registered —— 底部的键会被裁掉",
            bottom <= registered + 0.01f,
        )
        assertTrue(
            "注册高度不该比内容高太多（否则底部一大块空气）:" +
                "内容 $bottom，注册 $registered",
            registered <= bottom + 40f,
        )
    }

    /* ============================================================
     * gamepad1 —— 暂时下线
     * ============================================================ */

    /**
     * ⚠️ gamepad1 **暂时不在可用列表里**（用户要求先注释掉）。
     *
     * 用户的原话:"gamepad1 配置暂时先不考虑了，先注释掉吧回头有时间再做"。
     *
     * ⚠️ 这条不是"测实现"，而是**防止有人以为它坏了**:
     * `StyleId.GAMEPAD1` 这个常量还在（样式 id 是持久化契约，不能删），
     * 但注册被注释掉了 —— 于是 `resolveOrDefault(GAMEPAD1)` 会**回落到
     * 键盘样式**，而那是**有意**的降级（老配置里如果有 gamepad1，
     * 至少还能显示成键盘样式，而不是白屏或崩掉）。
     */
    @Test
    fun `gamepad1 暂时不在可用样式列表里`() {
        val ids = OverlayStyleRegistry.available().map { it.id }

        assertFalse(
            "gamepad1 已被注释下线，不该出现在可用列表里",
            StyleId.GAMEPAD1 in ids,
        )
        assertTrue("键盘样式必须在", StyleId.KEYSTROKES in ids)
        assertTrue("gamepad2 必须在", StyleId.GAMEPAD2 in ids)

        /* 老配置里的 gamepad1 会**降级成键盘样式**的尺寸，不是崩掉 */
        assertEquals(
            "gamepad1 下线后应当降级成键盘样式的宽度",
            KeyLayout.BASE_WIDTH,
            OverlayStyleRegistry.baseSizeOf(config(StyleId.GAMEPAD1)).width,
            0.01f,
        )
    }

    /* ============================================================
     * 全样式扫一遍:谁都不许把内容画到窗口外面
     * ============================================================ */

    /**
     * ⚠️ 遍历**所有**已注册样式，断言"内容装得下"。
     *
     * 单条断言只防住已知的那一个;这条防的是**下一个** ——
     * 以后再加样式、或改某个样式的布局时，它会立刻失败。
     */
    @Test
    fun `所有样式的注册尺寸都装得下内容`() {
        OverlayStyleRegistry.available().forEach { style ->
            /*
             * ⚠️ 只扫**走 `KeyLayout` 那套**的样式。
             *
             * | 样式 | 尺寸来自 |
             * |---|---|
             * | 键盘 / 标准 | `KeyLayout.BASE_WIDTH` + `baseHeight` |
             * | gamepad1 | **自己的** `GamepadLayout` 常量（600 宽） |
             * | 自定义 Key | 组件包围盒 |
             * | Live2D | 固定设计分辨率 |
             *
             * 所以这里**不能**断言"所有样式都是 BASE_WIDTH" ——
             * gamepad1 有自己的布局，它宽 600 是对的。
             */
            if (!usesKeyLayout(style.id)) return@forEach

            val c = config(style.id)
            val size = OverlayStyleRegistry.baseSizeOf(c)

            assertEquals(
                "${style.id}:走 KeyLayout 的样式，注册宽度应当是 BASE_WIDTH",
                KeyLayout.BASE_WIDTH,
                size.width,
                0.01f,
            )
            assertTrue(
                "${style.id}:内容宽度 ${contentWidth(c)} 超过注册宽度 ${size.width}",
                contentWidth(c) <= size.width + 0.01f,
            )
            assertTrue(
                "${style.id}:内容底边 ${contentBottom(c)} 超过注册高度 ${size.height}",
                contentBottom(c) <= size.height + 0.01f,
            )
            assertTrue(
                "${style.id}:注册高度比内容高太多（内容 ${contentBottom(c)}，" +
                    "注册 ${size.height}）—— 底部会留一块空气",
                size.height <= contentBottom(c) + 60f,
            )
        }
    }

    /**
     * 报告各槽位的精确边界 —— 用来核对"内容有没有超出注册宽度"。
     *
     * ⚠️ 这条**不是**为了通过，是为了**打印**。
     * 宽度就差一两个像素时，失败信息只说"超了"，
     * 而这里能看出是哪个槽位超的。
     */
    @Test
    fun `报告各槽位的精确边界`() {
        listOf(StyleId.KEYSTROKES, StyleId.GAMEPAD2).forEach { styleId ->
            reportSlots(styleId)
        }
        assertTrue("报告已打印（见标准输出）", true)
    }

    private fun reportSlots(styleId: String) {
        val c = config(styleId)
        val boxes = KeyLayout.keys(c)
        val left = boxes.minOf { it.centerX - it.width / 2f }
        val right = boxes.maxOf { it.centerX + it.width / 2f }

        val report = buildString {
            appendLine("=== $styleId ===")
            appendLine("注册宽度 ${OverlayStyleRegistry.baseSizeOf(c).width}")
            appendLine("内容左边界 $left，右边界 $right，总宽 ${right - left}")
            boxes.sortedBy { it.topY }.forEach { b ->
                appendLine(
                    "  ${b.slotId}: x ${b.centerX - b.width / 2f}..${b.centerX + b.width / 2f}" +
                        "  y ${b.topY}..${b.topY + b.height}  ${b.width}×${b.height}",
                )
            }
        }
        println(report)
    }

    /** 这个样式是不是走 `KeyLayout` 那套布局 */
    private fun usesKeyLayout(styleId: String): Boolean =
        styleId == StyleId.KEYSTROKES || KeyLayout.usesJoystickLayout(
            KeyStrokesConfig(id = "x", name = "x", styleId = styleId),
        )
}

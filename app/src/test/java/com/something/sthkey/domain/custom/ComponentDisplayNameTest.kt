package com.something.sthkey.domain.custom

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 组件在列表里的名字格式。
 *
 * ============================================================
 * ⚠️ 背景（用户反馈）
 * ============================================================
 * 之前各处自己拼 `typeLabel() + "·" + summary()`，结果:
 *
 * - 图片组件显示成「图片·图片」（类型名说了两遍）
 * - 没选资源时「图片·图片（未选择资源）」
 * - 触屏摇杆「触屏移动摇杆·触屏移动（吃触摸区域）」
 *
 * 现在的规则见 [ComponentExtensions.displayName]。
 */
class ComponentDisplayNameTest {

    private fun media(kind: MediaKind, assetId: String = "") = MediaComponent(
        id = "m1",
        x = 0f,
        y = 0f,
        width = 100f,
        height = 100f,
        kind = kind,
        assetId = assetId,
    )

    @Test
    fun `媒体组件没选资源时用括号说明`() {
        assertEquals("图片（未选择资源）", media(MediaKind.IMAGE).displayName())
        assertEquals("动图（未选择资源）", media(MediaKind.GIF).displayName())
        assertEquals("视频（未选择资源）", media(MediaKind.VIDEO).displayName())
    }

    @Test
    fun `按键与文本保持 类型 点 内容 的形式`() {
        val key = KeyComponent(
            id = "k1",
            x = 0f,
            y = 0f,
            width = 100f,
            height = 100f,
            style = ComponentStyle(),
            label = "A",
            inputKeyCodes = listOf(30),
        )
        assertEquals("按键 · A", key.displayName())

        val text = TextComponent(
            id = "t1",
            x = 0f,
            y = 0f,
            width = 100f,
            height = 100f,
            style = ComponentStyle(),
            text = "hello",
        )
        assertEquals("文本 · hello", text.displayName())
    }

    @Test
    fun `摇杆摘要不再重复类型名`() {
        assertEquals("手柄摇杆 · 左摇杆", joystick(JoystickSource.GAMEPAD, StickSide.LEFT).displayName())
        assertEquals("键盘摇杆 · WASD", joystick(JoystickSource.KEYBOARD, StickSide.LEFT).displayName())
        assertEquals("鼠标摇杆 · 指针位移", joystick(JoystickSource.MOUSE, StickSide.LEFT).displayName())
        assertEquals("触屏移动摇杆 · 触摸区域", joystick(JoystickSource.TOUCH, StickSide.LEFT).displayName())
        assertEquals("触屏视角摇杆 · 全屏滑动", joystick(JoystickSource.TOUCH, StickSide.RIGHT).displayName())
    }

    private fun joystick(source: JoystickSource, side: StickSide) = JoystickComponent(
        id = "j1",
        x = 0f,
        y = 0f,
        width = 100f,
        height = 100f,
        source = source,
        side = side,
    )
}

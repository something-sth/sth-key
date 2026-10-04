package com.something.sthkey.ui.overlay.gamepad

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.something.sthkey.capture.StickState
import com.something.sthkey.domain.config.KeyStrokesConfig
import com.something.sthkey.domain.style.KeyLayout
import com.something.sthkey.ui.overlay.OverlayKeySlot

/**
 * 「手柄（标准）」样式（gamepad2）。
 *
 * ============================================================
 * ⚠️ 它就是**键盘样式的布局**，只换掉了一块
 * ============================================================
 * | | 键盘样式 | gamepad2 |
 * |---|---|---|
 * | WASD 那块 | 四个键 | **一个左摇杆**（方形，与那块同级大小） |
 * | 鼠标键行 | 一样 | 一样（键位映射的**默认值**换成手柄键） |
 * | 空格 / Shift / CPS 模式 | 一样 | 一样 |
 * | 字号 / 描边 / 圆角 / 按下动画 | 一样 | 一样 |
 *
 * 所以这里**不自己算布局** —— 位置全部从 [KeyLayout.keys] 取，
 * 而那个函数在 gamepad2 下会把 WASD 换成一个
 * [KeyLayout.Id.JOYSTICK_LEFT] 槽位。
 *
 * 好处是下面几行（鼠标键、空格、Shift）的 `topY`
 * **自动跟着摇杆的高度算对了**，不需要在这里再偏移一次 ——
 * "在渲染层补一次偏移"正是"内容与窗口对不上"的经典成因。
 *
 * 同理，键帽本身**复用键盘样式那一个组件**（[OverlayKeySlot]）。
 * 复制一份绘制代码迟早会与键盘样式分叉，而分叉的表现是
 * "两个样式看起来不一样"，很难说清哪个才对。
 *
 * ============================================================
 * ⚠️ 与 gamepad1 的关系
 * ============================================================
 * 两者是**并列的预设**，没有优劣:
 *
 * - `gamepad1`：两个摇杆 + 方向键 + YXAB（手柄本来的样子）
 * - `gamepad2`：键盘布局 + 摇杆（"用手柄玩键鼠游戏"的样子）
 *
 * ⚠️ gamepad2 **目前只有左摇杆**。加右摇杆时要回到
 * [KeyLayout.keys] 里再挤一块位置出来（与左摇杆同一行还有空间），
 * 而不是在这里硬画一个 —— 那样窗口尺寸又会对不上。
 */
@Composable
fun Gamepad2Content(
    config: KeyStrokesConfig,
    sticks: StickState,
    pressedCodes: Set<Int>,
    scale: Float,
    modifier: Modifier = Modifier,
    cpsBySlot: Map<String, Int> = emptyMap(),
) {
    Box(
        modifier = modifier.size(
            (KeyLayout.baseWidth(config) * scale).dp,
            /*
             * ⚠️ 高度按内容算，与窗口尺寸**同源**
             * （`OverlayStyleRegistry` 那边也调 `baseHeight`）。
             * 写死固定值会在"开 Shift"或"改 CPS 模式"时把底部裁掉。
             */
            (KeyLayout.baseHeight(config, cpsBySlot) * scale).dp,
        ),
    ) {
        KeyLayout.keys(config, cpsBySlot).forEach { box ->
            /*
             * 摇杆槽位：位置与尺寸**都从槽位取**（见 JoystickSlot）。
             *
             * ⚠️ 左右摇杆用的是**同一个** `JoystickSlot`，只是喂不同的轴
             * （`lx`/`ly` 与 `rx`/`ry`）。分成两个组件会让"拇指大小、
             * 底盘比例"以后各改一半 —— 那是两个摇杆看起来不一样的常见来源。
             */
            val stickAxes = when (box.slotId) {
                KeyLayout.Id.JOYSTICK_LEFT ->
                    if (config.swapSticks) sticks.rx to sticks.ry else sticks.lx to sticks.ly

                KeyLayout.Id.JOYSTICK_RIGHT ->
                    if (config.swapSticks) sticks.lx to sticks.ly else sticks.rx to sticks.ry

                else -> null
            }

            if (stickAxes != null) {
                /*
                 * ⚠️ 死区与灵敏度在这里、**只在这里**做一次。
                 *
                 * 它们是**观感项** —— 用户明确说过"只改变悬浮窗，
                 * 不改变实际输入"。所以变换只发生在渲染前，
                 * `CaptureSession` 里存的仍是手柄报上来的原值。
                 *
                 * ⚠️ 别在 `Joystick` 里再做一遍 —— 两处各做一半的话，
                 * 改了一个设置只生效一半。
                 */
                JoystickSlot(
                    x = config.joystick.displayValue(stickAxes.first),
                    y = config.joystick.displayValue(stickAxes.second),
                    box = box,
                    scale = scale,
                    style = config.joystick,
                    modifier = Modifier.baseOffset(
                        left = box.centerX - box.width / 2f,
                        top = box.topY,
                        scale = scale,
                    ),
                )
            } else {
                /* 其余全部与键盘样式**同一个组件、同一套位置** */
                OverlayKeySlot(
                    box = box,
                    pressedCodes = pressedCodes,
                    config = config,
                    scale = scale,
                )
            }
        }
    }
}

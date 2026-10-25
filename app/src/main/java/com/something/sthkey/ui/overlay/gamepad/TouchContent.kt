package com.something.sthkey.ui.overlay.gamepad

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.something.sthkey.domain.config.KeyStrokesConfig
import com.something.sthkey.domain.style.KeyLayout
import com.something.sthkey.ui.overlay.OverlayKeySlot

/**
 * **触屏**样式的悬浮窗内容。
 *
 * ============================================================
 * 与 [Gamepad2Content] 的关系:布局共用、**输入来源不同**
 * ============================================================
 * | | 手柄 | 触屏 |
 * |---|---|---|
 * | 槽位在哪、多大 | `KeyLayout.keys` | **同一个函数**（`usesJoystickLayout` 为 true） |
 * | 摇杆读数 | `CaptureSession.sticks`（物理手柄的轴） | **参数传进来**（屏幕触摸算出来的） |
 * | 摇杆缩放 | `joystick.sizeScale`（两个摇杆共用） | **每摇杆一个**（在 `KeyLayout` 里已算进槽位尺寸） |
 *
 * ⚠️ 为什么**不直接改 `Gamepad2Content`** 让它接一个"摇杆读取器":
 * 那会让手柄那条路多一层间接（一个只在触屏下才不同的参数），
 * 而手柄那条路是**已经验证过的**。多一个分支就多一处可能出错的地方。
 * 触屏这条路重写一遍只多 60 行，但两边互不影响。
 *
 * ⚠️ 外观（颜色/圆角/描边）**取自 `config.joystick`** —— 与手柄同一份，
 * 用户要求"UI 用同一套"。触屏**只多**一个"每个摇杆自己的缩放"，
 * 而那个已经在 `KeyLayout` 里算进 `box.width` 了。
 *
 * @param move 移动轮盘的向量（`-1..1`，Y 向下为正）—— 由 `TouchFrameTracker` 算出
 * @param camera 视角摇杆的值（`-1..1`，Y 向下为正，**不回中**）
 * @param pressedSlots ⚠️ **触摸区域**按住的槽位 id（与 `KeyLayout.Id` 同一套）
 *
 * ============================================================
 * ⚠️⚠️ 为什么传"槽位"而不是把触摸翻译成"键码"
 * ============================================================
 * 我第一版把区域按下的槽位经 `keyMappings` 翻译成**键码**，再并进
 * `pressedCodes` —— **那是错的**，而且错得很隐蔽:
 *
 * 键帽点亮走的是 `OverlayKeySlot` 里这一行:
 * ```
 * pressed = box.codes.any { it in pressedCodes }
 * ```
 * 而 `box.codes` 是从 `KeyLayout.codeToSlotMap(config)` 来的，
 * 那张表**只从 `keyMappings.inputKeyCodes` 建**。
 *
 * ⚠️ 而触屏的键位映射我**特意把 `inputKeyCodes` 全清空了**
 * （触屏不读物理按键，留着它用户会去绑一个键然后发现没反应）。
 * 于是 `box.codes` 是**空集** → `any {}` 永远 `false` → **永远不亮**。
 *
 * ⚠️ 根子上的错误是:我把"按下"表达成了**键码**，而触屏**根本没有键码** ——
 * 它的输入是坐标。硬要塞进"键码"这条通道，就等于要求一个不存在的翻译。
 *
 * ⚠️ 所以这里直接收**槽位**，点亮判据是 `box.slotId in pressedSlots` ——
 * 与 `pressedCodes` 是**两条独立的路**（物理按键走键码、触摸走槽位），
 * 最后在 `OverlayKeySlot` 里取并集。
 */
@Composable
fun TouchContent(
    config: KeyStrokesConfig,
    move: Pair<Float, Float>,
    camera: Pair<Float, Float>,
    pressedCodes: Set<Int>,
    scale: Float,
    modifier: Modifier = Modifier,
    cpsBySlot: Map<String, Int> = emptyMap(),
    pressedSlots: Set<String> = emptySet(),
) {
    Box(
        modifier = modifier.size(
            (KeyLayout.baseWidth(config) * scale).dp,
            /*
             * ⚠️ 高度按内容算，与窗口尺寸**同源**
             * （`OverlayStyleRegistry` 那边也调 `baseHeight`）。
             * 写死固定值会在"开 Shift"或"改 CPS 模式"时把底部裁掉 ——
             * 手柄那边为这件事踩过（见 GAMEPAD2 的注册注释）。
             */
            (KeyLayout.baseHeight(config, cpsBySlot) * scale).dp,
        ),
    ) {
        KeyLayout.keys(config, cpsBySlot).forEach { box ->
            /*
             * ============================================================
             * ⚠️ 摇杆互换要在这里生效（原来**没接** —— 用户指出来的）
             * ============================================================
             * 用户的原话:
             *
             * > 现在触屏样式的**摇杆互换还没接上**，一会记得补上
             *
             * ⚠️ 语义与手柄那边**逐字相同**（见 `Gamepad2Content`）:
             * 换的是**两个槽位显示谁的数据**，不是"把输出对调" ——
             * 所以只影响观感，不影响实际输入。
             *
             * ⚠️ 触屏的"数据"是移动轮盘向量与视角摇杆值:
             * 开了互换之后，**左槽位画视角摇杆、右槽位画移动轮盘**。
             *
             * ⚠️ 注意**触摸区域不跟着换** —— 区域绑的是槽位（`@move` 还是
             * `LMB`），而区域是"手指按哪里"，与画在哪个槽位无关。
             * 这跟手柄那边一致（手柄的轴也不换，换的只是显示）。
             */
            val moveAxes = if (config.swapSticks) camera else move
            val cameraAxes = if (config.swapSticks) move else camera

            val stickAxes = when (box.slotId) {
                KeyLayout.Id.JOYSTICK_LEFT -> moveAxes
                KeyLayout.Id.JOYSTICK_RIGHT -> cameraAxes
                else -> null
            }

            if (stickAxes != null) {
                /*
                 * ============================================================
                 * ⚠️⚠️ 这里**不做**死区与灵敏度 —— 它们在 `TouchInput` 里做过了
                 * ============================================================
                 * 手柄那边要在这里调 [JoystickStyle.displayValue]，是因为
                 * `CaptureSession` 存的是**手柄上报的原始轴值**（观感项只影响
                 * 悬浮窗、不影响实际输入，所以变换放在渲染前）。
                 *
                 * ⚠️ 而触屏的向量是 `TouchInput.moveVector` / `accumulateCamera`
                 * **算出来的** —— 死区、灵敏度、夹取都已经在里面做过。
                 * 这里再调一次 `displayValue` 会**做两遍**:
                 * 死区被叠加（手要推得更远）、灵敏度被乘两次（更容易满偏）。
                 * 表现是"设置里填的值和实际手感对不上"。
                 *
                 * ⚠️ 这个坑与"别在两个地方各做一次死区"是同一类 ——
                 * 手柄那边的注释里明确写着"改了一个设置只生效一半"。
                 */
                JoystickSlot(
                    x = stickAxes.first,
                    y = stickAxes.second,
                    box = box,
                    scale = scale,
                    style = config.joystick,
                    /*
                     * ⚠️ `modifier` **必须传** —— [JoystickSlot] 靠它定位，
                     * 不传就永远画在画布原点 (0,0)。
                     * 写法与 `Gamepad2Content` **逐字相同**，包括那个
                     * `(height - side) / 2` 的居中修正（摇杆比槽位小时要在框内居中）。
                     */
                    modifier = Modifier.baseOffset(
                        left = box.centerX - box.width / 2f,
                        top = box.topY,
                        scale = scale,
                    ),
                )
            } else {
                /* 其余（LT/RT、A、空格、肩键…）与键盘样式**同一个组件、同一套位置** */
                OverlayKeySlot(
                    box = box,
                    pressedCodes = pressedCodes,
                    config = config,
                    scale = scale,
                    /*
                     * ⚠️ 触摸区域按下的槽位 —— 与 `pressedCodes` **取并集**，
                     * 见这个 composable 的 KDoc。
                     */
                    pressedSlots = pressedSlots,
                )
            }
        }
    }
}

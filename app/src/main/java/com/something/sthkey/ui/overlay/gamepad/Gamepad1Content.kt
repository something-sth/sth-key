package com.something.sthkey.ui.overlay.gamepad

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.something.sthkey.capture.StickState
import com.something.sthkey.domain.keys.GamepadFaceButtons
import com.something.sthkey.domain.keys.KeyCodes
import com.something.sthkey.domain.style.GamepadHeights
import com.something.sthkey.domain.config.JoystickStyle
import com.something.sthkey.domain.style.GamepadLayout

/**
 * 手柄样式一：两个摇杆 + 方向键 + YXAB。
 *        │  左摇杆  │  │  右摇杆  │        ← 摇杆行（STICK_SIZE）
 * ============================================================
 * 布局（基础坐标，画布 600 × H，内容居中）
 * ============================================================
 * ```
 *        ┌──────────┐  ┌──────────┐
 *        │  左摇杆  │  │  右摇杆  │        ← 摇杆行（STICK_SIZE）
 *        └──────────┘  └──────────┘
 *                         ↕ GAP
 *          ┌─┐                ┌─┐
 *        ┌─┤↑├─┐            ┌─┤Y├─┐
 *        │←│ │→│            │X│ │B│        ← 十字 / 菱形（两列等高）
 *        └─┤↓├─┘            └─┤A├─┘
 *          └─┘                └─┘
 * ```
 *
 * ============================================================
 * ⚠️ 为什么用绝对定位而不是 Column / Row
 * ============================================================
 * 十字与菱形**不是网格**。用 `Row` 只能拼出"两行四个方块" ——
 * 而那是用户明确不要的（他在方案里特意区分了这两种）。
 * 十字与菱形是手柄的**视觉签名**，摆成两行就没有那个味道了。
 *
 * ============================================================
 * ⚠️ 两列必须等高
 * ============================================================
 * 左列十字高 `DPAD × 3 + GAP × 2`，右列菱形高 `FACE × 2 + GAP` ——
 * 它们靠 `GamepadLayout.MARGIN` 补齐到同高（见那个文件里的验算）。
 * **改任何按钮尺寸都要重新验算那条**，否则两列底边会差几个像素，
 * 而那是肉眼一眼能看出来的不齐。
 */
@Composable
fun Gamepad1Content(
    sticks: StickState,
    pressedCodes: Set<Int>,
    scale: Float,
    modifier: Modifier = Modifier,
) {
    /* 内容整体在画布里居中 */
    val contentLeft = (GamepadLayout.BASE_WIDTH - gamepad1ContentWidth()) / 2f
    val stickTop = GamepadLayout.MARGIN
    val secondRowTop = stickTop + GamepadLayout.STICK_SIZE + GamepadLayout.GAP

    val leftStickCenterX = contentLeft + GamepadLayout.STICK_SIZE / 2f
    val rightStickCenterX = contentLeft + GamepadLayout.STICK_SIZE + GamepadLayout.GAP +
        GamepadLayout.STICK_SIZE / 2f

    /* 第二行两列共用同一个顶（它们等高，见上面的说明） */
    val padTop = secondRowTop + GamepadLayout.MARGIN

    Box(modifier = modifier.fillMaxSize()) {
        /* ---------------- 摇杆 ---------------- */

        Joystick(
            x = sticks.lx,
            y = sticks.ly,
            sizeDp = GamepadLayout.STICK_SIZE,
            scale = scale,
            /* ⚠️ 密度必须从 LocalDensity 读，不能靠 scale 反推 */
            pxPerBase = baseToPixelFactor(scale, LocalDensity.current.density),
            /* gamepad1 用标定值本身（比例就是从它推出来的，见 JoystickSpec） */
            travelRadius = GamepadLayout.STICK_TRAVEL_RADIUS,
            thumbSize = GamepadLayout.THUMB_SIZE,
            /*
             * ⚠️ gamepad1 **用的是默认观感**（`JoystickStyle()`）——
             * 它暂时下线（见 `OverlayStyles` 里被注释掉的注册），
             * 所以没有专属设置。以后重新上线时，这里改成读
             * `config.joystick` 即可（`Joystick` 已经支持全部外观项）。
             */
            style = JoystickStyle(),
            modifier = Modifier.baseOffset(
                left = leftStickCenterX - GamepadLayout.STICK_SIZE / 2f,
                top = stickTop,
                scale = scale,
            ),
        )

        Joystick(
            x = sticks.rx,
            y = sticks.ry,
            sizeDp = GamepadLayout.STICK_SIZE,
            scale = scale,
            /* ⚠️ 密度必须从 LocalDensity 读，不能靠 scale 反推 */
            pxPerBase = baseToPixelFactor(scale, LocalDensity.current.density),
            /* gamepad1 用标定值本身（比例就是从它推出来的，见 JoystickSpec） */
            travelRadius = GamepadLayout.STICK_TRAVEL_RADIUS,
            thumbSize = GamepadLayout.THUMB_SIZE,
            /* 同上面那个摇杆:gamepad1 用默认观感 */
            style = JoystickStyle(),
            modifier = Modifier.baseOffset(
                left = rightStickCenterX - GamepadLayout.STICK_SIZE / 2f,
                top = stickTop,
                scale = scale,
            ),
        )

        /* ---------------- 方向键（十字） ---------------- */

        /*
         * ⚠️ 方向键的按下状态有**两个来源**，都要看：
         *
         * 1. **轴报法**（实测的 Xbox 360 就是）—— 状态在 `StickState.hat*` 里；
         * 2. **按键报法** —— 状态在 `pressedCodes` 里（`BTN_DPAD_*`）。
         *
         * 只认一条就会出现"方向键在某个手柄上没反应" ——
         * 而那是我们**已经知道**会发生的差异（见 `docs/input-capture.md`）。
         */
        val dpadUp = sticks.hatY > 0f || KeyCodes.BTN_DPAD_UP in pressedCodes
        val dpadDown = sticks.hatY < 0f || KeyCodes.BTN_DPAD_DOWN in pressedCodes
        val dpadLeft = sticks.hatX < 0f || KeyCodes.BTN_DPAD_LEFT in pressedCodes
        val dpadRight = sticks.hatX > 0f || KeyCodes.BTN_DPAD_RIGHT in pressedCodes

        val d = GamepadLayout.DPAD_BUTTON_SIZE
        val step = d + GamepadLayout.GAP

        padButton(
            pressed = dpadUp,
            sizeDp = d,
            centerX = leftStickCenterX,
            top = padTop,
            scale = scale,
            cornerRatio = CORNER_SQUARE,
            label = "↑",
        )
        padButton(
            pressed = dpadLeft,
            sizeDp = d,
            centerX = leftStickCenterX - step,
            top = padTop + step,
            scale = scale,
            cornerRatio = CORNER_SQUARE,
            label = "←",
        )
        padButton(
            pressed = dpadRight,
            sizeDp = d,
            centerX = leftStickCenterX + step,
            top = padTop + step,
            scale = scale,
            cornerRatio = CORNER_SQUARE,
            label = "→",
        )
        padButton(
            pressed = dpadDown,
            sizeDp = d,
            centerX = leftStickCenterX,
            top = padTop + step * 2,
            scale = scale,
            cornerRatio = CORNER_SQUARE,
            label = "↓",
        )

        /* ---------------- YXAB（菱形） ---------------- */

        val f = GamepadLayout.FACE_BUTTON_SIZE
        val fStep = f + GamepadLayout.GAP

        /*
         * ⚠️ 菱形位置对应:
         * ```
         *        X（上）
         *  Y（左）       B（右）
         *        A（下）
         * ```
         *
         * ============================================================
         * ⚠️ 这四个映射**与 evdev 的标准约定都不同**，是按实测调过来的
         * ============================================================
         * 标准约定（Linux `input-event-codes.h` / joydev 文档）是:
         *
         * | evdev | 通常含义 |
         * |---|---|
         * | `BTN_NORTH` | **Y**（上） |
         * | `BTN_WEST` | **X**（左） |
         * | `BTN_EAST` | B（右） |
         * | `BTN_SOUTH` | A（下） |
         *
         * 而实测报告是 **X/Y 互换**、**A/B 互换** —— 四个键的方位约定
         * 与标准**整体转了 90°**（或者厂商用了另一套布局）。
         *
         * 所以这里全部按实测映射:
         * 上 = `BTN_WEST`、左 = `BTN_NORTH`、右 = `BTN_SOUTH`、下 = `BTN_EAST`。
         *
         * ⚠️ **这是设备相关的**。evdev 之所以用方位名（NORTH/SOUTH/EAST/WEST）
         * 而不是 A/B/X/Y，正是因为"哪个方位叫什么"是厂商约定。
         *
         * 如果以后有用户报告相反的情况，正确做法是**做成配置项**，
         * 而不是再翻一次 —— 来回翻会让两边都错。
         */
        padButton(
            pressed = GamepadFaceButtons.TOP in pressedCodes,
            sizeDp = f,
            centerX = rightStickCenterX,
            top = padTop,
            scale = scale,
            cornerRatio = CORNER_CIRCLE,
            label = GamepadFaceButtons.LABEL_TOP,
        )
        padButton(
            pressed = GamepadFaceButtons.LEFT in pressedCodes,
            sizeDp = f,
            centerX = rightStickCenterX - fStep,
            top = padTop + fStep,
            scale = scale,
            cornerRatio = CORNER_CIRCLE,
            label = GamepadFaceButtons.LABEL_LEFT,
        )
        padButton(
            pressed = GamepadFaceButtons.RIGHT in pressedCodes,
            sizeDp = f,
            centerX = rightStickCenterX + fStep,
            top = padTop + fStep,
            scale = scale,
            cornerRatio = CORNER_CIRCLE,
            label = GamepadFaceButtons.LABEL_RIGHT,
        )
        padButton(
            pressed = GamepadFaceButtons.BOTTOM in pressedCodes,
            sizeDp = f,
            centerX = rightStickCenterX,
            top = padTop + fStep * 2,
            scale = scale,
            cornerRatio = CORNER_CIRCLE,
            label = GamepadFaceButtons.LABEL_BOTTOM,
        )
    }
}

/**
 * 手柄样式一的**内容高度**。
 *
 * ⚠️ 它必须与 `OverlayStyles` 里注册的 `baseSize` **完全一致** ——
 * 窗口尺寸按那边算，这里是实际画出来的高度。不一致的话
 * 内容会被裁掉、或者底部留一片空白。
 *
 * 它同时也是"两列等高"的验算依据（见 [GamepadLayout.MARGIN]）。
 */
internal fun gamepad1ContentHeight(): Float = GamepadHeights.ONE

/** 内容宽度：两个摇杆 + 间隙 */
internal fun gamepad1ContentWidth(): Float =
    GamepadLayout.STICK_SIZE * 2 + GamepadLayout.GAP

/**
 * 把**基础坐标**里的左上角位置换算成 dp 偏移。
 *
 * ============================================================
 * ⚠️ 三个概念不能混
 * ============================================================
 * | 概念 | 说明 |
 * |---|---|
 * | 基础像素 | `GamepadLayout` 里的常量，与设备无关 |
 * | [scale] | `density * uiScale`，由调用方算好传进来 |
 * | dp | Compose 的单位 |
 *
 * `基础像素 × scale = dp`。**不要**在这里再乘一次 density ——
 * `scale` 里已经含了 density（见 `OverlayContent` 里那个 `factor`）。
 *
 * 而 `offset` 定的是**左上角**，布局常量给的多半是**中心点** ——
 * 调用方要在外面减掉半个尺寸。那种错在单个组件上看不出来，
 * 只有并排时才会发现"歪了一点"。
 */
@Composable
internal fun Modifier.baseOffset(left: Float, top: Float, scale: Float): Modifier =
    this.offset(x = (left * scale).dp, y = (top * scale).dp)

/**
 * 画一个方形/圆形的按键（方向键与 YXAB 共用）。
 *
 * [cornerRatio] 决定形状：`0.5` 是圆，`0.16` 是圆角方。
 * 不需要单独的形状分支 —— 这正是用户提的那条
 * （"圆形组件可以理解为长宽相等的 key 组件，圆角开最大"）。
 */
@Composable
private fun padButton(
    pressed: Boolean,
    sizeDp: Float,
    centerX: Float,
    top: Float,
    scale: Float,
    cornerRatio: Float,
    label: String? = null,
) {
    PadButton(
        pressed = pressed,
        sizeDp = sizeDp,
        scale = scale,
        /* ⚠️ 密度必须从 LocalDensity 读，不能靠 scale 反推 */
        pxPerBase = baseToPixelFactor(scale, LocalDensity.current.density),
        cornerRatio = cornerRatio,
        fillUp = PadColors.fillUp,
        fillDown = PadColors.fillDown,
        strokeColor = PadColors.stroke,
        label = label,
        labelColor = PadColors.label,
        modifier = Modifier.baseOffset(
            left = centerX - sizeDp / 2f,
            top = top,
            scale = scale,
        ),
    )
}

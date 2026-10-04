package com.something.sthkey.ui.overlay.gamepad

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp

/**
 * 手柄上的一个按键（圆形或方形，由 [cornerRatio] 决定）。
 *
 * ============================================================
 * ⚠️ 圆形按键就是"圆角开满的方形按键"
 * ============================================================
 * 不需要单独的形状分支：`cornerRatio = 0.5f` 时圆角半径 = 短边的一半，
 * 画出来就是圆。这样"圆形键 / 方形键"只是**一个参数**的差别 ——
 * 而这正是用户提的那条（"圆形组件可以理解为长宽相等的 key 组件
 * 圆角开最大"）。
 *
 * ============================================================
 * 动画（照抄参考实现的取舍）
 * ============================================================
 * - **按下 0.15s / 松开 0.12s**：松开比按下**快一点点**，
 *   符合"按下有份量、松开很干脆"的物理直觉。反过来会显得拖沓。
 * - **去抖**：手柄回调每秒几十次，[pressed] 不变时直接返回，
 *   不去抖会反复重启动画，看起来"抖"。Compose 里
 *   `animate*AsState` 天然做了这件事（目标值不变就不动）。
 */
@Composable
fun PadButton(
    pressed: Boolean,
    sizeDp: Float,
    scale: Float,
    /**
     * 基础像素 → 屏幕像素 的倍数（= `scale × 密度`）。
     *
     * ⚠️ 由调用方传入，不在组件内部算 —— 换算只有一处真源
     * （[baseToPixelFactor]），而它错过一次（见那里的注释）。
     */
    pxPerBase: Float,
    cornerRatio: Float,
    fillUp: Color,
    fillDown: Color,
    strokeColor: Color,
    modifier: Modifier = Modifier,
    /**
     * 键面文字（`Y` / `X` / `A` / `B` / `↑` …）。
     *
     * ⚠️ 用 Compose 的 `Text` **叠在画布上**，而不是在 `Canvas` 里
     * `drawText` —— 后者要 `TextMeasurer` + `nativeCanvas`，
     * 而且中文/符号的度量要自己处理。叠一层则连字体回退都自动有。
     *
     * ⚠️ 字号按**短边**的比例给（不用固定 sp）：这样它在任何缩放下
     * 都与键帽成比例，跟着 `scalePercent` 一起变大变小。
     */
    label: String? = null,
    /** 键面文字颜色 */
    labelColor: Color = Color.White,
) {
    val fill by animateColorAsState(
        targetValue = if (pressed) fillDown else fillUp,
        animationSpec = tween(if (pressed) PRESS_MS else RELEASE_MS),
        label = "padFill",
    )

    /*
     * 按下时轻微缩小 —— 比"只变色"更有按压感。
     *
     * ⚠️ 幅度要小(0.92)。缩太多会显得像在"躲避手指"，
     * 而悬浮窗上的按键是**指示器**，不是可以被按的控件。
     */
    val shrink by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = tween(if (pressed) PRESS_MS else RELEASE_MS),
        label = "padShrink",
    )

    Box(
        modifier = modifier.size((sizeDp * scale).dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier.fillMaxSize(),
        ) {
            val side = size.minDimension
            /*
             * ⚠️ 把画布尺寸换算回**基础像素**。
             *
             * 换算用 `pxPerBase`（= `scale × 密度`），
             * **不是** `1 / scale` —— 后者会让绘制的圆与描边
             * 随用户的缩放百分比**反向**变化。完整推导见
             * [baseToPixelFactor] 的注释。
             */
            val base = side / pxPerBase
            val inset = base * (1f - shrink) / 2f * pxPerBase
            val corner = base * cornerRatio * pxPerBase

            drawRoundRect(
                color = fill,
                topLeft = Offset(inset, inset),
                size = Size(side - inset * 2, side - inset * 2),
                cornerRadius = CornerRadius(corner, corner),
            )

            /*
             * 描边。
             *
             * ⚠️ 要画在填充**之后**、而且用同一套圆角参数 ——
             * 否则圆形键的描边会是一个略微错位的方框，看起来"脏"。
             */
            if (strokeColor.alpha > 0f) {
                drawRoundRect(
                    color = strokeColor,
                    topLeft = Offset(inset, inset),
                    size = Size(side - inset * 2, side - inset * 2),
                    cornerRadius = CornerRadius(corner, corner),
                    style = Stroke(width = base * 0.05f * pxPerBase),
                )
            }
        }

        if (!label.isNullOrEmpty()) {
            Text(
                text = label,
                color = labelColor,
                /*
                 * 字号按短边比例给。
                 *
                 * ⚠️ 乘以 `scale` 再 `.sp`:否则整体缩放时文字不变，
                 * 键帽变大了字还是那么小（或者反过来）。
                 *
                 * `0.34` 是"四个字母还能塞下"的经验值 ——
                 * 再大一点 `GUIDE` 这种长标签就会溢出。
                 */
                fontSize = (sizeDp * scale * LABEL_RATIO).sp,
                maxLines = 1,
                textAlign = TextAlign.Center,
                /*
                 * ⚠️ 不允许自动换行/缩放:键帽就那么大，
                 * 让文字自己去适应只会得到"某些标签被压成两行"。
                 */
                softWrap = false,
            )
        }
    }
}

/** 键面文字相对键帽短边的比例 */
private const val LABEL_RATIO = 0.34f

/** 按下动画时长（毫秒） */
private const val PRESS_MS = 150

/** 松开动画时长（毫秒）——比按下快一点，显得干脆 */
private const val RELEASE_MS = 120

/** 圆形按键用的圆角比例（短边的一半 = 圆） */
const val CORNER_CIRCLE = 0.5f

/** 方形按键用的圆角比例 */
const val CORNER_SQUARE = 0.16f

/**
 * 画一个**长条键**（宽高不等）。
 *
 * ⚠️ 与 [PadButton] 分开而不是给它加宽高参数：那个的圆角是按
 * **短边**算的，长条键也一样 —— 但两者的调用点完全不同
 * （一个是手柄上的方块键，一个是模标准的空格/鼠标键）。
 * 合成一个会带来一堆"这个参数什么时候有用"的分支。
 */
@Composable
fun BarKey(
    pressed: Boolean,
    widthDp: Float,
    heightDp: Float,
    scale: Float,
    cornerRatio: Float,
    modifier: Modifier = Modifier,
    /** 键面文字（`SPACE` / `LMB` / `RT` …） */
    label: String? = null,
    labelColor: Color = Color.White,
) {
    val fill by animateColorAsState(
        targetValue = if (pressed) PadColors.fillDown else PadColors.fillUp,
        animationSpec = tween(if (pressed) PRESS_MS else RELEASE_MS),
        label = "barFill",
    )

    Box(
        modifier = modifier.size((widthDp * scale).dp, (heightDp * scale).dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier.fillMaxSize(),
        ) {
            /*
             * ⚠️ 这里**不需要**单位换算 —— 绘制尺寸全部走
             * `size.minDimension` 的**相对比例**（圆角、描边都是），
             * 所以它们天然跟着 Box 的实际尺寸走，与缩放的来源无关。
             *
             * ⚠️ 原来这里挂了一个 `graphicsLayer(1/scale)`，
             * 那是**有害无益**的:比例值再被缩放一次，
             * 于是圆角与描边会随用户的缩放百分比反向变化。
             */
            /* 圆角按**短边**算 —— 长条键的短边是高 */
            val corner = size.minDimension * cornerRatio
            drawRoundRect(
                color = fill,
                topLeft = Offset.Zero,
                size = size,
                cornerRadius = CornerRadius(corner, corner),
            )
            if (PadColors.stroke.alpha > 0f) {
                drawRoundRect(
                    color = PadColors.stroke,
                    topLeft = Offset.Zero,
                    size = size,
                    cornerRadius = CornerRadius(corner, corner),
                    style = Stroke(width = size.minDimension * 0.06f),
                )
            }
        }

        if (!label.isNullOrEmpty()) {
            Text(
                text = label,
                color = labelColor,
                fontSize = (heightDp * scale * LABEL_RATIO).sp,
                maxLines = 1,
                textAlign = TextAlign.Center,
                softWrap = false,
            )
        }
    }
}

/**
 * 手柄样式的配色。
 *
 * ⚠️ 与按键样式**分开**：手柄样式暂时不开放颜色自定义（用户没提，
 * 而且它的键很多，逐个配色会很啰嗦）。等真有需求再接到
 * `KeyStrokesConfig.colors` 上 —— 那时只需要把这里的常量换成
 * 从配置读。
 */
object PadColors {
    val fillUp = Color(0xB3000000)
    val fillDown = Color(0xE6FFFFFF)
    val stroke = Color(0x59FFFFFF)
    val joystick = JoystickColors()

    /**
     * 键面文字颜色。
     *
     * ⚠️ 用**纯白**而不是"按下时变黑"：手柄按键的背景是半透明的
     * （未按下 `0xB3000000`），底下透出来的是游戏画面 ——
     * 白色在任何画面上都比黑色可读。而按下时背景变成亮色，
     * 白字确实会糊，但那时用户的注意力在"它亮了"这件事上，
     * 不在字上。
     */
    val label = Color.White
}

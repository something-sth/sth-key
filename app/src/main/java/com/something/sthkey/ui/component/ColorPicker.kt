package com.something.sthkey.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 取色对话框（色环 + 内嵌 S/V 方板 + H/S/V 滑杆 + 6 位十六进制）。
 *
 * ============================================================
 * 为什么不用第三方库
 * ============================================================
 * 2026-11-21 先试过 `skydoves/colorpicker-compose:1.3.0`：它的 `HsvColorPicker`
 * 画的是**色轮**（色相 = 角度、饱和度 = 半径，明度固定），既没有独立明度通道
 * （调不出灰/黑），下面再接一条色相条又会和色轮重复。
 * 其它主流库（anyColorPicker / kolor-picker / compose-color-picker）都是
 * "方板 + 色相条"，没有"色环 + 内嵌方板"这种画世界Pro 式布局 —— 所以这里自绘。
 *
 * ============================================================
 * 约定（**不要改**）
 * ============================================================
 * - 颜色**只存 RGB**（`0xRRGGBB`），**不含 alpha**。不透明度由各分区的
 *   「不透明度」滑块单独管。理由见 `docs/color-picker-plan.md` §3：
 *   数据层融合 alpha 会破坏配置格式与跨端契约，并且会把
 *   "alpha × opacity 双重相乘"那个让摇杆消失的 bug 带回来。
 * - 输入框**只接受 6 位**十六进制；`parseHexColor` 是唯一解析入口。
 * - [alphaPercent] **只用于预览**，不参与保存。
 *
 * ============================================================
 * ⚠️ 真源是 `rgb`；`hsv` 只是视图状态
 * ============================================================
 * - 拖色环 / 方板 / 滑杆 → 先改 `hsv`，再由 `hsvToRgb` 算出 `rgb`；
 * - 输入框合法 → 改 `rgb`，再 `rgbToHsv(previousHue)` 反推 `hsv`
 *   （灰/黑时保留旧色相，否则"调到黑再调回来"会跳成红色）；
 * - 保存时递出去的是 **`rgb`**，所以"打开→保存"永远等于原值。
 *
 * ⚠️ 整块内容可滚动：窄屏 / 低 dpi / 大字体下色环 + 三条滑杆 + 输入框会超高。
 */
@Composable
fun ColorPickerDialog(
    title: String,
    initialRgb: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
    alphaPercent: Int = 100,
) {
    val focusManager = LocalFocusManager.current

    var rgb by remember { mutableStateOf(initialRgb and 0xFFFFFF) }
    var hsv by remember { mutableStateOf(rgbToHsv(initialRgb and 0xFFFFFF)) }
    var text by remember { mutableStateOf(formatHexDigits(rgb)) }
    var error by remember { mutableStateOf(false) }

    val parsed = parseHexColor(text)

    /** 来自色环 / 方板 / 滑杆 */
    fun applyHsv(next: Hsv) {
        hsv = next
        val nextRgb = hsvToRgb(next)
        if (nextRgb != rgb) {
            rgb = nextRgb
            text = formatHexDigits(nextRgb)
            error = false
        }
    }

    /** 来自输入框 */
    fun applyRgb(next: Long) {
        rgb = next
        hsv = rgbToHsv(next, previousHue = hsv.hue)
        text = formatHexDigits(next)
        error = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    /* 色环直径跟对话框宽度走，最宽 264dp；方板内嵌在环里 */
                    val side = minOf(maxWidth, 264.dp)
                    ColorRingAndSquare(
                        hsv = hsv,
                        onChange = { applyHsv(it) },
                        modifier = Modifier
                            .size(side)
                            .align(Alignment.Center),
                    )
                }

                HsvSlider(
                    label = "H",
                    fraction = hsv.hue / 360f,
                    valueText = "${hsv.hue.roundToInt()}°",
                    brush = Brush.horizontalGradient(HUE_RING_COLORS),
                    /*
                     * ⚠️ 夹到 0.999 而不是 1：hue 360 与 0 是同一个颜色，
                     * 但 `Color.hsv(360f, ...)` 在部分版本会要求 [0, 360)；
                     * 而且拇指拖到最右端后突然跳回最左会很怪。
                     */
                    onFractionChange = {
                        applyHsv(hsv.copy(hue = it.coerceIn(0f, 0.999f) * 360f))
                    },
                )
                HsvSlider(
                    label = "S",
                    fraction = hsv.saturation,
                    valueText = "${(hsv.saturation * 100f).roundToInt()}%",
                    brush = Brush.horizontalGradient(
                        listOf(
                            Color.hsv(hsv.hue, 0f, hsv.value),
                            Color.hsv(hsv.hue, 1f, hsv.value),
                        ),
                    ),
                    onFractionChange = { applyHsv(hsv.copy(saturation = it)) },
                )
                HsvSlider(
                    label = "V",
                    fraction = hsv.value,
                    valueText = "${(hsv.value * 100f).roundToInt()}%",
                    brush = Brush.horizontalGradient(
                        listOf(
                            Color.Black,
                            Color.hsv(hsv.hue, hsv.saturation, 1f),
                        ),
                    ),
                    onFractionChange = { applyHsv(hsv.copy(value = it)) },
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ColorPreview(
                        rgb = parsed ?: rgb,
                        alphaPercent = alphaPercent,
                        modifier = Modifier.size(40.dp),
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    OutlinedTextField(
                        value = text,
                        onValueChange = { raw ->
                            text = normalizeHexDigits(raw)
                            val candidate = parseHexColor(text)
                            error = candidate == null
                            if (candidate != null) applyRgb(candidate)
                        },
                        singleLine = true,
                        isError = error,
                        label = { Text("颜色值") },
                        prefix = { Text("#") },
                        placeholder = { Text("FF8800") },
                        supportingText = if (error) {
                            { Text("需要 6 位十六进制，例如 FF8800") }
                        } else {
                            null
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Done,
                        ),
                        // 对话框里「完成」只收键盘；提交只认「保存」
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        modifier = Modifier.weight(1f),
                    )
                }

                Text(
                    text = "不透明度在「不透明度」分区单独调整（当前 $alphaPercent%），" +
                        "这里的预览已经按它合成。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { parsed?.let(onConfirm) },
                enabled = parsed != null,
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

// ---------------------------------------------------------------- 色环 + 方板

/** 色环上的 7 个停靠色（红→黄→绿→青→蓝→品红→红） */
private val HUE_RING_COLORS = listOf(
    Color.Red,
    Color.Yellow,
    Color.Green,
    Color.Cyan,
    Color.Blue,
    Color.Magenta,
    Color.Red,
)

/** 色环与内嵌方板的几何关系（px）。draw 与手势必须用同一套算法，否则点到的地方和画出来的对不上。 */
private class RingGeometry(
    val center: Offset,
    val outerRadius: Float,
    val ringWidth: Float,
) {
    val innerRadius: Float = outerRadius - ringWidth
    val ringMidRadius: Float = outerRadius - ringWidth / 2f
    val squareHalf: Float = (innerRadius - outerRadius * 0.05f).coerceAtLeast(1f)
    val square: Rect = Rect(
        left = center.x - squareHalf,
        top = center.y - squareHalf,
        right = center.x + squareHalf,
        bottom = center.y + squareHalf,
    )
}

private fun ringGeometry(size: Size): RingGeometry {
    val outer = min(size.width, size.height) / 2f
    return RingGeometry(
        center = Offset(size.width / 2f, size.height / 2f),
        outerRadius = outer,
        ringWidth = outer * 0.16f,
    )
}

/**
 * 色环（H）+ 内嵌方板（S 横轴 / V 纵轴）。
 *
 * 手势按"离圆心多远"分区：
 * - 落在环带上 → 改色相；
 * - 落在方板里 → 改饱和度与明度。
 *
 * ⚠️ `pointerInput(Unit)` + `rememberUpdatedState`：key 不能写成 `hsv`，
 * 否则拖动过程中每次状态变化都会重启手势识别，拖一下就断。
 */
@Composable
private fun ColorRingAndSquare(
    hsv: Hsv,
    onChange: (Hsv) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestHsv by rememberUpdatedState(hsv)
    val latestOnChange by rememberUpdatedState(onChange)

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    handleRingAndSquareTouch(pos, size, latestHsv, latestOnChange)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { pos ->
                        handleRingAndSquareTouch(pos, size, latestHsv, latestOnChange)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        handleRingAndSquareTouch(change.position, size, latestHsv, latestOnChange)
                    },
                )
            },
    ) {
        val g = ringGeometry(size)

        /* 1) 色环：扫掠渐变 + 粗描边 */
        drawArc(
            brush = Brush.sweepGradient(HUE_RING_COLORS),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(g.center.x - g.outerRadius, g.center.y - g.outerRadius),
            size = Size(g.outerRadius * 2f, g.outerRadius * 2f),
            style = Stroke(width = g.ringWidth),
        )

        /* 2) 方板：横向白→纯色相，纵向透明→黑（顺序不能反） */
        val pureHue = Color.hsv(hsv.hue, 1f, 1f)
        drawRect(
            brush = Brush.horizontalGradient(listOf(Color.White, pureHue)),
            topLeft = g.square.topLeft,
            size = g.square.size,
        )
        drawRect(
            brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black)),
            topLeft = g.square.topLeft,
            size = g.square.size,
        )

        /* 3) 色环滑块 */
        val angle = Math.toRadians(hsv.hue.toDouble())
        val ringThumb = Offset(
            x = g.center.x + (cos(angle) * g.ringMidRadius).toFloat(),
            y = g.center.y + (sin(angle) * g.ringMidRadius).toFloat(),
        )
        drawThumb(center = ringThumb, radius = g.ringWidth * 0.40f)

        /* 4) 方板滑块：x = 饱和度，y = 1 - 明度 */
        val squareThumb = Offset(
            x = g.square.left + hsv.saturation.coerceIn(0f, 1f) * g.square.width,
            y = g.square.bottom - hsv.value.coerceIn(0f, 1f) * g.square.height,
        )
        drawThumb(center = squareThumb, radius = 7.dp.toPx())
    }
}

/** 白心 + 深色描边，深色/浅色底上都看得见 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawThumb(
    center: Offset,
    radius: Float,
) {
    drawCircle(color = Color.White, radius = radius, center = center)
    drawCircle(
        color = Color.Black.copy(alpha = 0.35f),
        radius = radius,
        center = center,
        style = Stroke(width = 1.5.dp.toPx()),
    )
}

private fun handleRingAndSquareTouch(
    pos: Offset,
    sizePx: IntSize,
    current: Hsv,
    onChange: (Hsv) -> Unit,
) {
    val g = ringGeometry(Size(sizePx.width.toFloat(), sizePx.height.toFloat()))

    val dx = pos.x - g.center.x
    val dy = pos.y - g.center.y
    val distance = hypot(dx, dy)

    if (distance >= g.innerRadius - g.ringWidth * 0.25f) {
        /* 环带（含一点点内边界容差）：色相 = 角度 */
        var degrees = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
        if (degrees < 0f) degrees += 360f
        onChange(current.copy(hue = degrees % 360f))
    } else {
        val s = ((pos.x - g.square.left) / g.square.width).coerceIn(0f, 1f)
        val v = ((g.square.bottom - pos.y) / g.square.height).coerceIn(0f, 1f)
        onChange(current.copy(saturation = s, value = v))
    }
}

// ---------------------------------------------------------------- 滑杆

/**
 * 一条带渐变的细滑杆（H / S / V）。
 *
 * ⚠️ 轨道两端各留半个圆做圆角，所以映射时要扣掉两端半径，
 * 否则拖到最左/最右会和拇指位置对不上。
 */
@Composable
private fun HsvSlider(
    label: String,
    fraction: Float,
    valueText: String,
    brush: Brush,
    onFractionChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestFraction by rememberUpdatedState(fraction)
    val latestOnChange by rememberUpdatedState(onFractionChange)

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(16.dp),
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .height(24.dp)
                .pointerInput(Unit) {
                    detectTapGestures { pos ->
                        latestOnChange(fractionAt(pos.x, size.width.toFloat(), size.height.toFloat()))
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { pos ->
                            latestOnChange(fractionAt(pos.x, size.width.toFloat(), size.height.toFloat()))
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            latestOnChange(
                                fractionAt(
                                    change.position.x,
                                    size.width.toFloat(),
                                    size.height.toFloat(),
                                ),
                            )
                        },
                    )
                },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val h = size.height
                val r = h / 2f
                drawRoundRect(
                    brush = brush,
                    cornerRadius = CornerRadius(r, r),
                )
                val x = r + latestFraction.coerceIn(0f, 1f) * (size.width - 2f * r)
                drawThumb(center = Offset(x, r), radius = r * 0.72f)
            }
        }

        Text(
            text = valueText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(44.dp),
        )
    }
}

/** 像素 x → 0..1（扣掉两端圆角半径） */
private fun fractionAt(x: Float, width: Float, height: Float): Float {
    val r = height / 2f
    val usable = (width - 2f * r).coerceAtLeast(1f)
    return ((x - r) / usable).coerceIn(0f, 1f)
}

// ---------------------------------------------------------------- 预览与输入

/** 预览色块：棋盘底 + 按真实不透明度叠色（否则半透明色会被背景冲淡成"浅色"） */
@Composable
private fun ColorPreview(
    rgb: Long,
    alphaPercent: Int,
    modifier: Modifier = Modifier,
) {
    val alpha = alphaPercent.coerceIn(0, 100) / 100f
    val light = MaterialTheme.colorScheme.surfaceVariant
    val dark = MaterialTheme.colorScheme.surface

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(light),
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val cell = 8.dp.toPx().coerceAtLeast(1f)
            var y = 0f
            var row = 0
            while (y < size.height) {
                var x = 0f
                var col = 0
                while (x < size.width) {
                    if ((row + col) % 2 == 1) {
                        drawRect(
                            color = dark,
                            topLeft = Offset(x, y),
                            size = Size(
                                width = minOf(cell, size.width - x),
                                height = minOf(cell, size.height - y),
                            ),
                        )
                    }
                    x += cell
                    col++
                }
                y += cell
                row++
            }
        }

        Box(
            modifier = Modifier
                .matchParentSize()
                .background(rgbToOpaqueColor(rgb).copy(alpha = alpha)),
        )
    }
}

/**
 * `0xRRGGBB` → **不透明**的 Compose [Color]。
 *
 * ⚠️ 不能直接写 `Color(rgb.toInt())`：`Color(Int)` 把参数当成 **ARGB**，
 *    而我们的颜色高 8 位是 0 → 会得到一个 **alpha = 0 的透明色**，预览会发虚。
 */
private fun rgbToOpaqueColor(rgb: Long): Color =
    Color((0xFF000000L or (rgb and 0xFFFFFF)).toInt())

/**
 * 把用户输入/粘贴的内容整理成「6 位大写十六进制」。
 *
 * 规则（与 `docs/color-picker-plan.md` §6.4 一致）：
 * - 先剥掉 `#` / `0x` / `0X` 前缀（粘贴 `#FF8800`、`0xFF8800` 都能用）；
 * - 过滤掉非十六进制字符；
 * - 截断到 6 位；
 * - 统一大写。
 *
 * ⚠️ 顺序不能反：先过滤再剥前缀的话，`0xFF8800` 会被截成 `0FF880`（少一位、还错位）。
 */
internal fun normalizeHexDigits(raw: String): String {
    val stripped = raw
        .trim()
        .removePrefix("#")
        .removePrefix("0x")
        .removePrefix("0X")
    return stripped
        .filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        .take(6)
        .uppercase()
}

// ---------------------------------------------------------------- HSV 数学（纯函数，有单测）

/** HSV 三元组：hue 0..360，saturation / value 0..1 */
internal data class Hsv(
    val hue: Float,
    val saturation: Float,
    val value: Float,
)

/**
 * RGB → HSV。
 *
 * ⚠️ [previousHue]：灰/黑/白没有色相。如果每次都归 0，用户"先选蓝、明度拖到 0、
 * 再拖回来"时会跳成红色。所以灰阶一律沿用上一次的色相。
 */
internal fun rgbToHsv(rgb: Long, previousHue: Float = 0f): Hsv {
    val r = ((rgb shr 16) and 0xFF).toFloat() / 255f
    val g = ((rgb shr 8) and 0xFF).toFloat() / 255f
    val b = (rgb and 0xFF).toFloat() / 255f

    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min

    val hue = when {
        delta < 1e-6f -> previousHue
        max == r -> 60f * (((g - b) / delta) % 6f)
        max == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }

    return Hsv(
        hue = ((hue % 360f) + 360f) % 360f,
        saturation = if (max <= 0f) 0f else (delta / max).coerceIn(0f, 1f),
        value = max.coerceIn(0f, 1f),
    )
}

/** HSV → RGB（`0xRRGGBB`）。所有分量先夹到合法区间，不会抛出。 */
internal fun hsvToRgb(hsv: Hsv): Long {
    val h = ((hsv.hue % 360f) + 360f) % 360f
    val s = hsv.saturation.coerceIn(0f, 1f)
    val v = hsv.value.coerceIn(0f, 1f)

    val c = v * s
    val x = c * (1f - abs((h / 60f) % 2f - 1f))
    val m = v - c

    val (r1, g1, b1) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }

    fun channel(f: Float): Long =
        ((f + m) * 255f).roundToInt().coerceIn(0, 255).toLong()

    return (channel(r1) shl 16) or (channel(g1) shl 8) or channel(b1)
}

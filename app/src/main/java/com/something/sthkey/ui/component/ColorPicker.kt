package com.something.sthkey.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.github.skydoves.colorpicker.compose.HsvColorPicker
import com.github.skydoves.colorpicker.compose.HueSlider
import com.github.skydoves.colorpicker.compose.rememberColorPickerController
import kotlinx.coroutines.flow.collect

/**
 * 取色对话框（HSV 面板 + 色相条 + 6 位十六进制）。
 *
 * ============================================================
 * 约定（**不要改**）
 * ============================================================
 * - 颜色**只存 RGB**（`0xRRGGBB`），**不含 alpha**。不透明度由各分区的
 *   「不透明度」滑块单独管（`Opacity` / `JoystickStyle.*Opacity`）。
 *   理由见 `docs/color-picker-plan.md` §3：数据层融合 alpha 会破坏配置格式、
 *   跨端契约，并且会把"alpha × opacity 双重相乘"那个让摇杆消失的 bug 带回来。
 * - 输入框**只接受 6 位**十六进制；`parseHexColor` 是唯一解析入口。
 * - [alphaPercent] **只用于预览**，不参与保存；预览按真实不透明度合成，
 *   所以「看到的就是悬浮窗上的」。
 *
 * ============================================================
 * ⚠️ 为什么整块内容可滚动
 * ============================================================
 * 面板 + 色相条 + 输入框在窄屏 / 低 dpi / 大字体下会超过对话框可用高度。
 * 这里让内容 `verticalScroll`，并用 [BoxWithConstraints] 把面板边长夹到
 * `min(可用宽度, 280dp)` —— 保证「保存」永远够得着，不需要用户去猜。
 *
 * ============================================================
 * ⚠️ 真源是 `rgb`；与取色控件之间靠 `selectedColor` 单向同步
 * ============================================================
 * - 拖动面板 / 拖色相条 → 库的 `controller.selectedColor` 变 → 我们跟着更新 `rgb` 与输入框；
 * - 输入框合法 → 我们反过来 `selectByColor(...)` 让面板跳到那个颜色。
 *
 * ⚠️ 没有用 `HsvColorPicker.onColorChanged`：那个回调只覆盖"点面板"，
 *    拖色相条走的是另一条路（HueSlider 的回调），监听 `selectedColor`
 *    才能把两种来源一次收齐。
 *
 * ⚠️ 保存时递出去的是 `rgb`（不是从 HSV 反算），所以"打开→保存"永远等于原值，
 *    不会因为 HSV↔RGB 的浮点往返差 1（`docs/color-picker-plan.md` §7.4）。
 */
@Composable
fun ColorPickerDialog(
    title: String,
    initialRgb: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
    alphaPercent: Int = 100,
) {
    val controller = rememberColorPickerController()
    val focusManager = LocalFocusManager.current

    /*
     * ⚠️ initialColor 必须是**稳定值**：如果这里传随 `rgb` 变化的值，
     *    库内部按它设置颜色 → selectedColor 变 → 我们又改 rgb → 再触发……会自激。
     */
    val initialColor = remember { rgbToOpaqueColor(initialRgb) }

    var rgb by remember { mutableStateOf(initialRgb and 0xFFFFFF) }
    var text by remember { mutableStateOf(formatHexDigits(rgb)) }
    var error by remember { mutableStateOf(false) }

    val parsed = parseHexColor(text)

    fun applyFromPicker(color: Color) {
        val next = color.toArgb().toLong() and 0xFFFFFF
        if (next == rgb) return
        rgb = next
        text = formatHexDigits(next)
        error = false
    }

    /*
     * 面板与色相条的唯一出口：只要 controller 的颜色变了就同步过来。
     * 用 snapshotFlow 而不是回调，是因为拖色相条不会触发 HsvColorPicker.onColorChanged。
     */
    LaunchedEffect(controller) {
        snapshotFlow { controller.selectedColor.value }.collect { applyFromPicker(it) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            /*
             * ⚠️ 必须可滚动：小屏 / 低 dpi / 大字体下这一段会超过对话框高度。
             */
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    /* 面板是正方形；宽度跟对话框走，但最宽 280dp，避免平板上一屏占满 */
                    val side = minOf(maxWidth, 280.dp)
                    HsvColorPicker(
                        modifier = Modifier
                            .size(side)
                            .clip(RoundedCornerShape(12.dp))
                            .align(Alignment.Center),
                        controller = controller,
                        initialColor = initialColor,
                    )
                }

                HueSlider(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    controller = controller,
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
                            if (candidate != null) {
                                rgb = candidate
                                /* 输入合法就同步面板；fromUser = false，避免和自我回调打架 */
                                controller.selectByColor(rgbToOpaqueColor(candidate), false)
                            }
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

/**
 * `0xRRGGBB` → **不透明**的 Compose [Color]。
 *
 * ⚠️ 不能直接写 `Color(rgb.toInt())`：`Color(Int)` 把参数当成 **ARGB**，
 *    而我们的颜色高 8 位是 0 → 会得到一个 **alpha = 0 的透明色**。
 *    取色控件拿到透明色后虽然 HSV 还对，但 alpha 状态是 0，预览/缩略图会发虚。
 */
private fun rgbToOpaqueColor(rgb: Long): Color =
    Color((0xFF000000L or (rgb and 0xFFFFFF)).toInt())

/**
 * 预览色块：棋盘底 + 按真实不透明度叠色。
 *
 * ⚠️ 不垫棋盘的话，20% 的颜色会跟对话框背景混成一个"浅色"，
 * 用户会以为那是 100% 的浅色，而不是"半透明"。
 */
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

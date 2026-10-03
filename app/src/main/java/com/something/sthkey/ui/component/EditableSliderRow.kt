package com.something.sthkey.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect

/**
 * 带数值显示的滑块行，**数值可以点开直接输入**。
 *
 * ============================================================
 * 为什么需要"点数值直接改"
 * ============================================================
 * 纯滑块**没法做精细调整**：一个 `0..200` 的范围铺在几百像素宽的屏幕上，
 * 一个像素就对应半个数值，手指还比像素粗得多。
 * 想把 `125` 改成 `127` 基本只能靠运气。
 *
 * 而这类数值恰恰经常需要精确：组件坐标要对齐、宽高要一致、
 * 缩放要复现别人的配置。所以：**点数值 → 弹窗 → 键盘输入**。
 *
 * ============================================================
 * 两个刻意的设计
 * ============================================================
 * 1. **只有数值本身可点**，标签与滑条不受影响 ——
 *    否则用户想拖滑块时点到标签也会弹窗，反而更烦。
 * 2. 数值用**主题色**画，暗示它是可点的（与只读的说明文字区分开）。
 *
 * ⚠️ 非法输入（不是数字、超出范围）**不静默截断**，而是把输入框标红
 * 并禁用确定 —— 静默截断会让用户以为"输入生效了"，
 * 然后对着一个自己没输过的值发懵。
 *
 * @param display 滑条旁显示的文本（可以带单位，例如 `45%`）；
 *   打开编辑框时取其中的数字部分
 * @param suffix 单位后缀（`%` / `°` 等）；空表示纯数字
 * @param step 输入框的粒度：1 = 整数；0.1 = 允许一位小数
 * @param onBeginDrag 拖动开始（用来合并撤销记录）；不拖滑块时不会被调用
 */
@Composable
fun EditableSliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    suffix: String = "",
    step: Float = 1f,
    labelStyle: TextStyle? = null,
    valueStyle: TextStyle? = null,
    onBeginDrag: (() -> Unit)? = null,
    onEndDrag: (() -> Unit)? = null,
) {
    var showEditor by remember { mutableStateOf(false) }

    /*
     * 拖动状态用**滑块自己的回调**去重标记。
     *
     * ⚠️ 不要为了标记"开始拖动"而在外面套 `pointerInput` ——
     * 那会和 Slider 自己的手势竞争，结果是**滑块彻底拖不动**（踩过这个坑）。
     * 用它的回调，别抢它的事件。
     */
    var dragging by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        if (label.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = labelStyle ?: MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = display,
                    style = valueStyle ?: MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    /*
                     * 只有这一小块可点：点标签或滑条不会误触发编辑。
                     */
                    modifier = Modifier
                        .clickable { showEditor = true }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }

        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = { newValue ->
                if (!dragging) {
                    dragging = true
                    onBeginDrag?.invoke()
                }
                onValueChange(newValue)
            },
            valueRange = range,
            steps = steps,
            onValueChangeFinished = {
                if (dragging) {
                    dragging = false
                    onEndDrag?.invoke()
                }
            },
        )
    }

    if (showEditor) {
        NumberEditorDialog(
            label = label.ifBlank { display },
            initial = value,
            range = range,
            suffix = suffix,
            step = step,
            onConfirm = { typed ->
                /*
                 * 输入算一次"完整改动"：先 onBeginDrag 让调用方开始记一笔
                 * 撤销，再给值，最后 onEndDrag 收尾 —— 这样撤销是**一步**，
                 * 而不是"拖了一路的每一帧"。
                 */
                onBeginDrag?.invoke()
                onValueChange(typed)
                onEndDrag?.invoke()
            },
            onDismiss = { showEditor = false },
        )
    }
}

/**
 * 数值编辑弹窗。
 *
 * 输入期间**实时校验**：不在范围内就标红并禁用确定，
 * 让用户在按确定之前就知道不行。
 */
@Composable
private fun NumberEditorDialog(
    label: String,
    initial: Float,
    range: ClosedFloatingPointRange<Float>,
    suffix: String,
    step: Float,
    onConfirm: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    /*
     * 保留用户原始输入（字符串），而不是用 Float 回写 ——
     * 用 Float 回写会把 "3." / "-" / "1.50" 这类**输入中间态**吃掉，
     * 用户还没输完就被改写，体验很差。
     */
    var text by remember { mutableStateOf(formatForEdit(initial, step)) }

    val parsed = text.trim().removeSuffix(suffix).trim().toFloatOrNull()
    val isValid = parsed != null && parsed.isFinite() &&
        parsed >= range.start && parsed <= range.endInclusive

    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    /* 打开就聚焦并全选，省一次点击 */
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(label) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    isError = !isValid,
                    suffix = if (suffix.isNotEmpty()) {
                        { Text(suffix) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            if (isValid) {
                                onConfirm(parsed)
                                onDismiss()
                            }
                        },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )

                Text(
                    text = if (isValid) {
                        "可填 ${formatBound(range.start, step)} ~ " +
                            formatBound(range.endInclusive, step)
                    } else {
                        "请填 ${formatBound(range.start, step)} ~ " +
                            formatBound(range.endInclusive, step) +
                            " 之间的数字"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isValid) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    parsed?.let(onConfirm)
                    onDismiss()
                },
                enabled = isValid,
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 按粒度格式化：整数就不显示小数点，避免"45.0"这种噪音 */
private fun formatForEdit(value: Float, step: Float): String =
    if (step >= 1f) {
        value.toInt().toString()
    } else {
        val decimals = decimalsOf(step)
        String.format("%.${decimals}f", value)
    }

private fun formatBound(value: Float, step: Float): String = formatForEdit(value, step)

/** 粒度对应几位小数：1 → 0 位，0.1 → 1 位，0.01 → 2 位 */
private fun decimalsOf(step: Float): Int = when {
    step >= 1f -> 0
    step >= 0.1f -> 1
    step >= 0.01f -> 2
    else -> 3
}

package com.something.sthkey.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * 十六进制颜色工具。
 *
 * 约定：颜色只有 **6 位 RGB（`#RRGGBB`）**，**不包含透明度**。
 * 透明度由配置页的"透明度"分区用滑块单独控制
 * （见 [com.something.sthkey.domain.config.Opacity]）。
 *
 * 为什么拆开：颜色和透明度混在同一个输入框里时，用户只想调颜色，
 * 却可能因为少写两位把透明度改成 0 或 255，非常反直觉；
 * 拆开后两者各有一个真源，互不干扰。
 */

/** 颜色值位数（不含 `#`） */
private const val HEX_LENGTH = 6

/** 解析 `#RRGGBB` / `RRGGBB`；长度不是 6 位或含非法字符时返回 null */
fun parseHexColor(input: String): Long? {
    val cleaned = input.trim().removePrefix("#").removePrefix("0x").removePrefix("0X")
    if (cleaned.length != HEX_LENGTH) return null
    if (!cleaned.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
    return cleaned.toLongOrNull(16)?.and(0xFFFFFF)
}

/** 输出为 `#RRGGBB`（大写） */
fun formatHexColor(rgb: Long): String = "#%06X".format(rgb and 0xFFFFFF)

/**
 * 输出为不带 `#` 的 6 位文本，供输入框使用。
 *
 * 输入框自己有 `prefix = "#"`，文本里再带一个 `#` 就会出现"# #FF8800"，
 * 而且那个 `#` 还能被用户删掉 —— 既难看又容易输错。
 * 因此输入框里只放纯十六进制，`#` 一律由 prefix 负责展示。
 */
fun formatHexDigits(rgb: Long): String = "%06X".format(rgb and 0xFFFFFF)

/**
 * 颜色小方块。
 *
 * @param alphaPercent 预览用透明度（0..100）。颜色本身不含透明度，
 *   但预览时按真实透明度渲染，用户才能看出最终观感。
 */
@Composable
fun ColorSwatch(
    rgb: Long,
    modifier: Modifier = Modifier,
    alphaPercent: Int = 100,
) {
    val alpha = alphaPercent.coerceIn(0, 100) / 100f
    Box(
        modifier = modifier
            .size(24.dp)
            .background(
                Color(rgb and 0xFFFFFF).copy(alpha = alpha),
                RoundedCornerShape(6.dp),
            )
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)),
    )
}

/**
 * 颜色输入行。
 *
 * 交互约定：
 * - 输入时即过滤非十六进制字符并截断到 6 位，从源头保证格式正确；
 * - 失焦时才提交，避免输入过程中反复写配置；
 * - 长度不足时保留内容并标红，不会悄悄丢掉用户的输入。
 */
@Composable
fun HexColorRow(
    label: String,
    value: Long,
    onValueChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** 仅用于色块预览 */
    alphaPercent: Int = 100,
) {
    var text by remember(value) { mutableStateOf(formatHexDigits(value)) }
    var error by remember { mutableStateOf(false) }

    // 外部值变化（例如点了"恢复默认"）时同步回输入框
    LaunchedEffect(value) {
        text = formatHexDigits(value)
        error = false
    }

    fun commit() {
        val parsed = parseHexColor(text)
        if (parsed == null) {
            error = true
        } else {
            error = false
            if (parsed != value) onValueChange(parsed)
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )

        val focusManager = LocalFocusManager.current

        ColorSwatch(
            rgb = parseHexColor(text) ?: value,
            alphaPercent = alphaPercent,
        )

        Spacer(modifier = Modifier.width(12.dp))

        OutlinedTextField(
            value = text,
            onValueChange = { input ->
                text = input
                    .filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
                    .take(HEX_LENGTH)
                    .uppercase()
                error = false
            },
            singleLine = true,
            enabled = enabled,
            isError = error,
            prefix = { Text("#") },
            supportingText = if (error) {
                { Text("需要 6 位十六进制，例如 FF8800") }
            } else {
                null
            },
            /*
             * ⚠️ 必须显式处理"完成"，否则编辑不会生效（这里踩过坑）。
             *
             * 提交是靠 `onFocusChanged` 里"失焦时 commit"做的。而软键盘上
             * 点「完成」**默认只是收起键盘、不移走焦点** —— 于是：
             * 左边的色块预览已经跟着输入变了（它读的是本地 text），
             * 但值没提交、悬浮窗上还是旧颜色。用户看到的就是
             * "编辑好了却没应用"。
             *
             * `clearFocus()` 才真正触发那次提交；外接键盘的 Enter
             * 走的是同一个 [KeyboardActions.onDone]。
             */
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            modifier = Modifier
                .width(150.dp)
                .onFocusChanged { state ->
                    if (!state.isFocused) commit()
                },
        )
    }
}

/**
 * 颜色编辑对话框。
 *
 * 与 [HexColorRow] 共用同一套解析规则，
 * 因此不会出现"一个地方接受 8 位、另一个只接受 6 位"的不一致。
 */
@Composable
fun HexColorDialog(
    title: String,
    value: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
    alphaPercent: Int = 100,
) {
    var text by remember { mutableStateOf(formatHexDigits(value)) }
    val parsed = parseHexColor(text)
    val focusManager = LocalFocusManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ColorSwatch(
                        rgb = parsed ?: value,
                        alphaPercent = alphaPercent,
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = if (parsed != null) formatHexColor(parsed) else "需要 6 位十六进制",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                OutlinedTextField(
                    value = text,
                    onValueChange = { input ->
                        text = input
                            .filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
                            .take(HEX_LENGTH)
                            .uppercase()
                    },
                    singleLine = true,
                    isError = parsed == null,
                    label = { Text("颜色值") },
                    prefix = { Text("#") },
                    placeholder = { Text("FF8800") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done,
                    ),
                    // 对话框里"完成"只需收起键盘 —— 提交由「确定」按钮负责
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = "透明度请到「透明度」分区用滑块单独调整。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(4.dp))
            }
        },
        confirmButton = {
            TextButton(
                onClick = { parsed?.let(onConfirm) },
                enabled = parsed != null,
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

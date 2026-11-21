package com.something.sthkey.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * 十六进制颜色工具。
 *
 * 约定：颜色只有 **6 位 RGB（`#RRGGBB`）**，**不包含透明度**。
 * 不透明度由配置页的"不透明度"分区用滑块单独控制
 * （见 [com.something.sthkey.domain.config.Opacity]）。
 *
 * 为什么拆开：颜色和不透明度混在同一个输入框里时，用户只想调颜色，
 * 却可能因为少写两位把不透明度改成 0 或 255，非常反直觉；
 * 拆开后两者各有一个真源，互不干扰。
 *
 * ⚠️ 曾经踩过的坑（`JoystickStyleTest` 钉着）：颜色里带 alpha 时，
 * 它会被抹成 `0x00xxxxxx` 并写进存档，渲染时再乘一次不透明度 →
 * `0 × 1.0 = 0`，**摇杆整个消失**。所以这里的解析/格式化永远只处理 RGB。
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
 * @param alphaPercent 预览用不透明度（0..100）。颜色本身不含 alpha，
 *   但预览时按真实不透明度渲染，用户才能看出最终观感。
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
 * 颜色行。
 *
 * ============================================================
 * 交互约定（2026-11-21 改版）
 * ============================================================
 * 以前是「色块 + 行内十六进制输入框」，一行很高、而且每改一个字符就可能提交。
 * 现在整行是一个**可点按钮**：`label + 色块 + #RRGGBB + ›`，
 * 点开 [ColorPickerDialog] 才编辑 —— 取色盘 + 手动输 6 位 + 取消/保存。
 *
 * ⚠️ 只有「保存」才回调 [onValueChange]；取消/返回键/点外部一律丢弃。
 * 这一点很关键：24 个调用点大多走 `onComponentChange(..., true)`（记一步 undo），
 * 如果拖动面板时实时提交，undo 栈会被刷成几百步。
 *
 * ⚠️ [alphaPercent] 只影响色块预览（看起来透不透），**不参与** [onValueChange]。
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
    var showDialog by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { showDialog = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
            modifier = Modifier.weight(1f),
        )

        ColorSwatch(rgb = value, alphaPercent = alphaPercent)

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = formatHexColor(value),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.width(2.dp))

        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }

    if (showDialog) {
        ColorPickerDialog(
            title = label,
            initialRgb = value,
            alphaPercent = alphaPercent,
            onDismiss = { showDialog = false },
            onConfirm = { picked ->
                showDialog = false
                if (picked != value) onValueChange(picked)
            },
        )
    }
}

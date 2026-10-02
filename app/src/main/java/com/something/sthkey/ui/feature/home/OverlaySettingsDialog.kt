package com.something.sthkey.ui.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.something.sthkey.domain.overlay.OverlayLayout
import kotlin.math.roundToInt

/**
 * 单个悬浮窗的设置弹窗。
 *
 * ============================================================
 * 三件事，以及它们为什么是现在这个约束
 * ============================================================
 * 1. **是否可触摸**（默认开）。关掉 = 纯贴图：不可拖动、也不拦截点击 ——
 *    注意窗口仍然在、仍然渲染，只是不再吃触摸（实现是 `FLAG_NOT_TOUCHABLE`）。
 * 2. **X / Y 偏移**，默认 0（滑块在正中间）。
 *    ⚠️ **只在"可触摸"关闭时可调**：可触摸时用户直接拖窗口就行了，
 *    两个都能动的话"我现在拖的是基准还是偏移"根本说不清；
 *    而不可触摸时窗口拖不动，滑块就是**唯一**能挪它的手段。
 * 3. **重置位置与偏移**：可触摸关掉之后拖不动，万一滑块的行程也不够用
 *    （例如换过设备、屏幕上位置算歪了），得有一条退路。
 *
 * ============================================================
 * 为什么滑块是"偏移"而不是"坐标"
 * ============================================================
 * 最终位置 = 基础坐标 + 偏移：拖动只改前者，滑块只改后者。
 * 所以弹窗里显示的**不是**窗口当前的绝对坐标，拖动窗口也不会让滑块跳动 ——
 * 这是刻意的，否则用户拖一下窗口回来会发现滑块自己跑了。
 *
 * @param configName 弹窗标题里显示的配置名（用户要确认自己调的是哪一个）
 * @param layout 当前设置
 * @param windowWidth/windowHeight 这个窗口的像素尺寸；滑块上限要减掉它
 * @param screenWidth/screenHeight 屏幕像素尺寸
 */
@Composable
fun OverlaySettingsDialog(
    configName: String,
    layout: OverlayLayout,
    windowWidth: Int,
    windowHeight: Int,
    screenWidth: Int,
    screenHeight: Int,
    onTouchableChange: (Boolean) -> Unit,
    onOffsetChange: (Int, Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    /*
     * 滑块上限 = 可用余量（屏幕 − 窗口）。
     *
     * 不用"半屏"：半屏偏移在多数机型上会被边界吃掉一大半，
     * 表现是滑块推到中间以后窗口不再动。用余量则每一段行程都真实有效 ——
     * 拉到两端正好贴住屏幕两侧。详见 OverlayBounds.maxOffset。
     */
    val maxOffsetX = (screenWidth - windowWidth).coerceAtLeast(1)
    val maxOffsetY = (screenHeight - windowHeight).coerceAtLeast(1)

    // 可触摸时禁止调偏移（原因见类注释第 2 条）
    val offsetEnabled = !layout.touchable

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("悬浮窗设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = configName,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )

                /*
                 * 可触摸开关。
                 *
                 * 用整行 + Switch 而不是 SwitchItem：`SwitchItem` 是"设置卡片里的一行"
                 * 的排版（带 16dp 边距与图标位），塞进对话框会显得很空。
                 */
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "可触摸", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = if (layout.touchable) {
                                "可以拖动窗口；此时偏移滑块不可调（直接拖更直观）"
                            } else {
                                "纯贴图：不能拖动、也不会拦截点击；用下面的滑块调整位置"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = layout.touchable,
                        onCheckedChange = onTouchableChange,
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                OffsetSlider(
                    label = "X 轴偏移",
                    value = layout.offsetX,
                    max = maxOffsetX,
                    enabled = offsetEnabled,
                    onChange = { onOffsetChange(it, layout.offsetY) },
                )

                OffsetSlider(
                    label = "Y 轴偏移",
                    value = layout.offsetY,
                    max = maxOffsetY,
                    enabled = offsetEnabled,
                    onChange = { onOffsetChange(layout.offsetX, it) },
                )

                if (!offsetEnabled) {
                    Text(
                        text = "想用滑块调位置，请先关闭上面的「可触摸」。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                OutlinedButton(
                    onClick = onReset,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Default.RestartAlt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(text = "重置位置与偏移", modifier = Modifier.padding(start = 8.dp))
                }

                Text(
                    text = "位置与偏移只保存在本机，不随配置导出 —— " +
                        "它们跟屏幕尺寸绑定，换台设备就不对了。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}

/**
 * 一根偏移滑块。
 *
 * 取值区间是 `[-max, max]`，0 在正中间 —— 也就是"默认位置"。
 * 显示当前像素值：用户看到数字变化才知道自己调了多少，
 * 也给"重置之后回到 0"一个可验证的判据。
 */
@Composable
private fun OffsetSlider(
    label: String,
    value: Int,
    max: Int,
    enabled: Boolean,
    onChange: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (value > 0) "+$value" else "$value",
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
            )
        }

        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = -max.toFloat()..max.toFloat(),
            enabled = enabled,
        )
    }
}

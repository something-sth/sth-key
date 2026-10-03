package com.something.sthkey.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.something.sthkey.domain.config.KeyStrokesConfig
import com.something.sthkey.domain.style.KeyLayout
import com.something.sthkey.domain.style.KeyBox
import kotlin.math.roundToInt

/**
 * 「逐个按键调整」这类窗口的**共用外壳**。
 *
 * ============================================================
 * 为什么抽出来
 * ============================================================
 * 文字偏移（X/Y）与字间距/行间距（letter/line）是两组不同的参数，
 * 但窗口结构完全一样：一段说明 + 按槽位排列的一串滑块 + 「全部归零」。
 *
 * 各写一份的话，最容易出的问题是**槽位清单不同步** ——
 * 比如偏移窗口列出了 `CPS_L`、间距窗口漏了，用户就会觉得
 * "这个键的间距调不了"。槽位清单只能有一个来源。
 *
 * ⚠️ 槽位清单取自 [KeyLayout.keys] —— 与悬浮窗**同一个真源**。
 * 自己再列一份（"W/A/S/D/空格/…"）的话，哪天布局改了这里就会不同步，
 * 而症状是"某个键在悬浮窗里有、在这个窗口里没有"，很难联想到原因。
 *
 * ⚠️ CPS 模式 2 会多出两个**静态显示位**（`CPS_L` / `CPS_R`），
 * 它们各自是独立的槽位 id，所以自动出现在列表里，不需要特判。
 *
 * 键用 `slotId` 而不是下标：布局调整（加 Shift 键、切 CPS 模式）后
 * 下标会错位，用户的调整就"跑到别的键上去了"。
 *
 * @param rows 每个槽位怎么画。拿到槽位本身（标题用它的 `label`）与
 *   "把这一项的改动写回整份 map" 的回调。
 */
@Composable
internal fun SlotSettingsDialog(
    config: KeyStrokesConfig,
    title: String,
    hint: String,
    onDismiss: () -> Unit,
    onClearAll: () -> Unit,
    rows: @Composable (
        slot: KeyBox,
        clear: () -> Unit,
    ) -> Unit,
) {
    val slots = KeyLayout.keys(config)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    slots.forEach { box ->
                        rows(box) { onClearAll() }
                        HorizontalDivider(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    // 一键清空：调乱了想重来时不必一个一个拖回 0
                    onClearAll()
                    onDismiss()
                },
            ) {
                Text("全部归零")
            }
        },
    )
}

/**
 * 一组"逐槽位设置"的标题行。
 *
 * 用**键位映射里的显示文字**（用户自己写的）而不是内部 id：
 * 用户认的是"空格"、"LMB"，而不是 `SPACE` / `LMB` 这种代码里的名字。
 */
@Composable
internal fun SlotSettingsTitle(slot: KeyBox) {
    Text(
        text = slot.label.ifBlank { slot.slotId },
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/**
 * 一行紧凑的滑块：标签 + 滑条 + 当前值。
 *
 * 两三个滑块挤在一个槽位里、而窗口要列 8~10 个槽位 ——
 * 每个占一大块的话滚都滚不完，反而不方便对照着调。
 */
@Composable
internal fun CompactSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    /** 数值后缀，例如 `%` */
    suffix: String = "",
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(40.dp),
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${value.roundToInt()}$suffix",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(40.dp),
        )
    }
}

/** 槽位滑块组的上下留白 */
internal val SlotSettingsRowPadding = 4.dp

/** 槽位滑块组里"标签 + 滑块"的外层 Column */
@Composable
internal fun SlotSettingsRow(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = SlotSettingsRowPadding),
    ) {
        content()
    }
}

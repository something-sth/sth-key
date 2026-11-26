package com.something.sthkey.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.something.sthkey.domain.keys.KeyCodes

private enum class SetStage { LIST, CAPTURE, PICKER }

/**
 * 通用的"绑定一组按键"对话框（单键 + 组合键）。
 *
 * 流程与键位映射完全一致（用户明确要求统一）：
 * ```
 * 列表 --「添加按键」--> 采集
 *                         ├─ 保存     → 加一组，回列表
 *                         └─ 手动选择 → 勾选选择框 → 回列表
 * ```
 *
 * @param initialSingles 旧的单键绑定（`inputKeyCodes`），保持"或"的语义
 * @param initialCombos  组合键（`inputCombos`），内层"同时按下"
 */
@Composable
fun KeyBindingSetDialog(
    title: String,
    hint: String,
    initialSingles: List<Int>,
    initialCombos: List<List<Int>>,
    onDismiss: () -> Unit,
    onConfirm: (List<Int>, List<List<Int>>) -> Unit,
) {
    var stage by remember { mutableStateOf(SetStage.LIST) }
    var singles by remember { mutableStateOf(initialSingles) }
    var combos by remember { mutableStateOf(initialCombos) }

    if (stage == SetStage.CAPTURE) {
        KeyCaptureDialog(
            title = title,
            initialCodes = emptyList(),
            onDismiss = { stage = SetStage.LIST },
            onConfirm = { combo ->
                if (combo.isNotEmpty()) combos = combos + listOf(combo)
                stage = SetStage.LIST
            },
            onManual = { stage = SetStage.PICKER },
        )
        return
    }

    if (stage == SetStage.PICKER) {
        KeyComboPickerDialog(
            title = title,
            initial = emptyList(),
            onDismiss = { stage = SetStage.LIST },
            onConfirm = { combo ->
                if (combo.isNotEmpty()) combos = combos + listOf(combo)
                stage = SetStage.LIST
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                combos.forEachIndexed { index, combo ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = combo.joinToString(" + ") { KeyCodes.displayName(it) },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { combos = combos.filterIndexed { i, _ -> i != index } }) {
                            Icon(Icons.Default.Close, contentDescription = "移除")
                        }
                    }
                }

                singles.forEach { code ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = KeyCodes.displayName(code),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { singles = singles - code }) {
                            Icon(Icons.Default.Close, contentDescription = "移除")
                        }
                    }
                }

                if (combos.isEmpty() && singles.isEmpty()) {
                    Text("当前未绑定任何按键", style = MaterialTheme.typography.bodyMedium)
                }

                TextButton(
                    onClick = { stage = SetStage.CAPTURE },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("添加按键")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    /* 单键归一化：只有一个键的组合折回单键列表 */
                    val singleFromCombos = combos.filter { it.size == 1 }.map { it.first() }
                    val realCombos = combos.filter { it.size > 1 }
                    onConfirm((singles + singleFromCombos).distinct(), realCombos)
                },
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

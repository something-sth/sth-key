package com.something.sthkey.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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

/**
 * **勾选式**按键选择框：一次勾多个键，确定后作为**一组组合键**返回。
 *
 * 与 [KeyCaptureDialog] 的分工：
 * - 采集框：直接按物理键，适合"我知道要按什么"；
 * - 这个框：键盘 / 手柄分类里一个个勾，适合采集不到输入的设备，
 *   或者想确认到底选了哪几个键的场景。
 */
@Composable
fun KeyComboPickerDialog(
    title: String,
    initial: List<Int>,
    onDismiss: () -> Unit,
    onConfirm: (List<Int>) -> Unit,
) {
    var selected by remember(initial) { mutableStateOf(initial) }
    var query by remember { mutableStateOf("") }
    var group by remember { mutableStateOf(KeyCodes.PickerGroup.ALL) }

    val keys = remember(query, group) { KeyCodes.searchIn(query, group) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "勾选多个键 = 一组组合键（必须同时按下）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("搜索按键") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = group == KeyCodes.PickerGroup.ALL,
                        onClick = { group = KeyCodes.PickerGroup.ALL },
                        label = { Text("全部") },
                    )
                    FilterChip(
                        selected = group == KeyCodes.PickerGroup.KEYBOARD,
                        onClick = { group = KeyCodes.PickerGroup.KEYBOARD },
                        label = { Text("键盘") },
                    )
                    FilterChip(
                        selected = group == KeyCodes.PickerGroup.GAMEPAD,
                        onClick = { group = KeyCodes.PickerGroup.GAMEPAD },
                        label = { Text("手柄") },
                    )
                }
                Spacer(Modifier.height(6.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp),
                ) {
                    items(keys, key = { it.keyCode }) { key ->
                        val checked = key.keyCode in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { isChecked ->
                                    selected = if (isChecked) {
                                        if (key.keyCode in selected) selected
                                        else selected + key.keyCode
                                    } else {
                                        selected - key.keyCode
                                    }
                                },
                            )
                            Text(
                                text = key.name,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = key.aliases.firstOrNull().orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

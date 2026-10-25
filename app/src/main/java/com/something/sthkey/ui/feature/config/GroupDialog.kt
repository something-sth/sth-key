package com.something.sthkey.ui.feature.config

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.something.sthkey.domain.config.ConfigGroup
import com.something.sthkey.domain.config.KeyStrokesConfig

/** 多选列表的高度上限（配置多了要能滚，不能把对话框撑出屏幕） */
private val PICKER_MAX_HEIGHT = 300.dp

/**
 * 组合的**名称 / 描述**编辑框。
 *
 * ⚠️ 抽成一个小函数而不是写两遍:两个字段只差"能不能为空"。
 */
@Composable
fun GroupTextEditDialog(
    title: String,
    initial: String,
    label: String,
    allowEmpty: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(initial) { mutableStateOf(initial) }
    val valid = allowEmpty || value.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text(label) },
                isError = !valid,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.trim()) }, enabled = valid) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * **新建配置组合**对话框。
 *
 * ============================================================
 * ⚠️ 结构对照「新建配置」窗口
 * ============================================================
 * | 位置 | 新建配置 | 新建组合 |
 * |---|---|---|
 * | 上半 | 名称 / 描述 | 名称 / 描述（一样） |
 * | 下半 | **配置样式**多选（一行两个） | **配置**多选（一行两个，可滚） |
 *
 * ⚠️ 允许一个都不选（用户要求）——建一个空组合，之后再往里面加。
 *
 * @param ownerOf 某个配置当前属于哪个组合（用于提示"会被搬过来"）
 */
@Composable
fun CreateGroupDialog(
    allConfigs: List<KeyStrokesConfig>,
    ownerOf: (String) -> ConfigGroup?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, description: String, memberIds: List<String>) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    /*
     * ⚠️ 用 `mutableStateListOf` 而不是 `Set` 状态:
     * 要保持**点选顺序**（组合里成员的顺序就是主页展开顺序）。
     */
    val picked = remember { mutableStateListOf<String>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建配置组合") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                /*
                 * ⚠️ 名称**可以留空**（用户要求）:
                 * 留空时由 `GroupStore.create` 兜底成「新建配置组合」并自动去重。
                 * 所以这里**不设 `isError`** —— 那圈红框在"留空是合法用法"时很突兀。
                 */
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("名称") },
                    placeholder = { Text("留空则用「新建配置组合」") },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    singleLine = true,
                    label = { Text("描述（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "加入哪些配置（可以一个都不选，之后再加）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(6.dp))

                GroupPickerGrid(
                    allConfigs = allConfigs,
                    picked = picked,
                    /* ⚠️ 新建时还没有"当前组合"，任何已占用的配置都算"在别的组合里" */
                    currentGroupId = null,
                    ownerOf = ownerOf,
                )
            }
        },
        confirmButton = {
            /*
             * ⚠️ 一直可点:名称为空是合法用法（由 store 兜底命名），
             * 所以不用 `enabled = …` 去挡。
             */
            TextButton(onClick = { onConfirm(name.trim(), description.trim(), picked.toList()) }) {
                Text("创建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * **配置管理**对话框（组合详情页用）。
 *
 * ⚠️ 与新建时的多选**同一个网格**（[GroupPickerGrid]），只是预选为当前成员。
 */
@Composable
fun GroupMemberPickerDialog(
    allConfigs: List<KeyStrokesConfig>,
    selectedIds: Set<String>,
    currentGroupId: String,
    ownerOf: (String) -> ConfigGroup?,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    val picked = remember(selectedIds) {
        mutableStateListOf<String>().apply { addAll(selectedIds) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("配置管理") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "勾选加入本组合；取消勾选会移出（配置本身不会被删除）。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(8.dp))

                GroupPickerGrid(
                    allConfigs = allConfigs,
                    picked = picked,
                    currentGroupId = currentGroupId,
                    ownerOf = ownerOf,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(picked.toSet()) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 配置多选网格（两个对话框共用）。
 *
 * ⚠️ **一行两个 + 可滚动**:用 `LazyVerticalGrid(GridCells.Fixed(2))` 加限高，
 * 比手写"算宽度 + chunked"简单，而且天然能滚。
 *
 * @param currentGroupId 当前正在编辑的组合；`null` 表示"新建"
 */
@Composable
private fun GroupPickerGrid(
    allConfigs: List<KeyStrokesConfig>,
    picked: MutableList<String>,
    currentGroupId: String?,
    ownerOf: (String) -> ConfigGroup?,
) {
    if (allConfigs.isEmpty()) {
        Text(
            text = "还没有配置，先去新建一个。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = PICKER_MAX_HEIGHT),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(allConfigs, key = { it.id }) { config ->
            val checked = config.id in picked
            val owner = ownerOf(config.id)
            /*
             * ⚠️ "被别的组合占用":一个配置只能属于一个组合（用户确认不允许重复），
             * 勾选它等于把它**搬**过来。这里只做提示，不再弹二次确认 ——
             * 搬移是可逆的（再取消勾选就回配置列表）。
             */
            val elsewhereBy = owner
                ?.takeIf { currentGroupId == null || it.id != currentGroupId }
                ?.name

            PickerTile(
                name = config.name,
                styleLabel = config.style.label,
                checked = checked,
                elsewhereBy = elsewhereBy,
                onClick = {
                    if (checked) picked.remove(config.id) else picked.add(config.id)
                },
            )
        }
    }
}

/**
 * 多选列表里的一个方块。
 *
 * ⚠️ 样式参照「新建配置」里的样式卡片:名称 + 一行小字 + 选中勾。
 * 选中态用**勾号**而不是整块变色 —— 整块变色在浅色主题下会把名称对比度拉低。
 */
@Composable
private fun PickerTile(
    name: String,
    styleLabel: String,
    checked: Boolean,
    elsewhereBy: String?,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (checked) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                /*
                 * ⚠️ 被别的组合占用时**替换掉样式那一行**去提示，
                 * 不额外加一行 —— 卡片在小屏上放不下三行。
                 */
                Text(
                    text = elsewhereBy?.let { "已在其它组合" } ?: styleLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (elsewhereBy != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (checked) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "已选中",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

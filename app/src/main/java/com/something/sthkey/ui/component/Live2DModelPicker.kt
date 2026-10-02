package com.something.sthkey.ui.component

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.something.sthkey.data.live2d.Live2DModelImporter
import com.something.sthkey.domain.live2d.Live2DModelEntry
import com.something.sthkey.domain.live2d.Live2DModelKind
import com.something.sthkey.domain.live2d.Live2DModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Live2D 模型选择对话框。
 *
 * 结构与字体选择器一致（[FontPickerDialog]）：上面是列表、下面是"导入"。
 * 差别在于模型是**目录**而不是单个文件，所以导入要解压，是异步的 ——
 * 期间按钮转圈、不允许重复点。
 *
 * 「右侧显示方向键还是鼠标」不需要单独做开关：那两个内置模型就是这两页，
 * 它们的副标题已经写明了。
 *
 * @param onLibraryChanged 库里发生增删改时回调，让调用方刷新显示的名称
 */
@Composable
fun Live2DModelPickerDialog(
    selectedId: String,
    onSelect: (String) -> Unit,
    onLibraryChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    /** 库的版本号：导入/改名/删除后自增，用来重读列表 */
    var revision by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<Live2DModelEntry?>(null) }
    var deleting by remember { mutableStateOf<Live2DModelEntry?>(null) }

    val models = remember(revision) { Live2DModels.all() }

    /**
     * 导入：SAF 选 zip → 解压到私有目录 → 生成页面 → 登记进库。
     *
     * 用通配类型而不是具体的压缩包类型：不同的文件管理器对 zip 的 MIME
     * 判断不一致，写死会让一部分设备上文件灰掉选不中。
     */
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        notice = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                Live2DModelImporter.importZip(context, uri)
            }
            busy = false
            result
                .onSuccess { outcome ->
                    revision++
                    onLibraryChanged()
                    // 导入完直接选中它：用户导入就是为了用它
                    onSelect(outcome.entry.id)
                    notice = buildString {
                        append(
                            if (outcome.reused) {
                                "这个模型本地已有，直接复用「${outcome.entry.displayName}」"
                            } else {
                                "已导入「${outcome.entry.displayName}」"
                            },
                        )
                        outcome.notes.forEach { append("\n· ").append(it) }
                    }
                }
                .onFailure { error ->
                    notice = "导入失败：${error.message ?: error.javaClass.simpleName}"
                }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("选择 Live2D 模型") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 模型可能很多，给个高度上限，超出的部分内部滚动
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(models, key = { it.id }) { model ->
                        ModelRow(
                            model = model,
                            selected = model.id == selectedId,
                            enabled = !busy,
                            onClick = { onSelect(model.id) },
                            onRename = { renaming = model },
                            onDelete = { deleting = model },
                        )
                    }
                }

                if (busy) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "正在导入模型…（大模型需要几秒）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                notice?.let { message ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("导入模型（zip）")
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "导入的模型对所有配置可用；只会保存在本机，不会自动分享给别人。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("完成") }
        },
    )

    renaming?.let { target ->
        RenameModelDialog(
            initialName = target.displayName,
            onDismiss = { renaming = null },
            onConfirm = { newName ->
                Live2DModels.rename(target.id, newName)
                renaming = null
                revision++
                onLibraryChanged()
            },
        )
    }

    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除模型？") },
            text = {
                Text(
                    "将删除「${target.displayName}」及其全部文件，此操作不可撤销。\n" +
                        "正在使用它的配置会回落到内置模型。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        Live2DModels.delete(target.id)
                        deleting = null
                        revision++
                        onLibraryChanged()
                    },
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            },
        )
    }
}

/** 列表里的一行：预览图 + 选中态 + 名称 + 说明 + （导入模型的）改名/删除 */
@Composable
private fun ModelRow(
    model: Live2DModelEntry,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = { if (enabled) onClick() },
            enabled = enabled,
        )

        ModelThumbnail(model)

        Spacer(modifier = Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = model.subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // 内置模型不能改名/删除：它们在 assets 里，删了下次启动又会出现
        if (model.kind == Live2DModelKind.IMPORTED) {
            IconButton(onClick = onRename, enabled = enabled) {
                Icon(
                    Icons.Default.DriveFileRenameOutline,
                    contentDescription = "重命名",
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onDelete, enabled = enabled) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** 列表里的预览图：共用 [Live2DModelThumbnail]，尺寸用列表的小图规格 */
@Composable
private fun ModelThumbnail(model: Live2DModelEntry) {
    Live2DModelThumbnail(
        modelId = model.id,
        widthDp = 64f,
        heightDp = 37f,
    )
}

/** 重命名对话框（与配置重命名同样的形态） */
@Composable
private fun RenameModelDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    val valid = name.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名模型") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("模型名称") },
                isError = !valid,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = valid) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

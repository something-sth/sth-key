package com.something.sthkey.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.something.sthkey.domain.font.FontEntry
import com.something.sthkey.domain.font.FontKind
import com.something.sthkey.domain.font.FontRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 字体选择窗口。
 *
 * ============================================================
 * 结构（与需求一致）
 * ============================================================
 * 1. **系统字体**（固定列表，不可删除）
 * 2. **内置字体**（随应用打包，不可删除）
 * 3. **导入的字体**（全局共享，可删除、可重命名）+ 导入按钮
 *
 * ============================================================
 * 两个体验上的重点
 * ============================================================
 * - **每项用自己的字体渲染样例文字**：选字体时"所见即所得"，
 *   否则只能靠名字猜（"中等"到底什么样？）。
 * - **字体全局共享**：在一个配置里导入，切到别的配置也能直接选。
 *   配置里只存一个 id，所以换字体不会动别的配置。
 *
 * @param selectedId 当前选中的字体 id
 * @param onSelect 选中回调；对话框不自己关闭，由调用方决定
 * @param onImport 触发导入（SAF 文件选择在调用方，因为需要 Activity）
 * @param refreshKey 外部变更标记。
 *   **导入是从这个对话框外面发起的**（SAF 选择器要 Activity，所以由调用方启动），
 *   导入完成后调用方把这个值 +1，这里才会重新读字体列表 ——
 *   否则会出现"导入成功了但列表里没有，得关掉重开才看得到"。
 */
@Composable
fun FontPickerDialog(
    selectedId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    onImport: () -> Unit,
    refreshKey: Int = 0,
) {
    val scope = rememberCoroutineScope()

    /** 对话框内部的变更（改名/删除）自增它；外部变更用 refreshKey */
    var localRevision by remember { mutableIntStateOf(0) }
    var renaming by remember { mutableStateOf<FontEntry?>(null) }
    var deleting by remember { mutableStateOf<FontEntry?>(null) }

    val fonts = remember(localRevision, refreshKey) { FontRegistry.all() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择字体") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                FontKind.entries.forEach { kind ->
                    val group = fonts.filter { it.kind == kind }
                    if (group.isEmpty()) return@forEach

                    if (kind != FontKind.entries.first()) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }

                    Text(
                        text = kind.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )

                    group.forEach { font ->
                        FontRow(
                            entry = font,
                            selected = font.id == selectedId,
                            onClick = { onSelect(font.id) },
                            onRename = if (font.kind == FontKind.IMPORTED) {
                                { renaming = font }
                            } else {
                                null
                            },
                            onDelete = if (font.kind == FontKind.IMPORTED) {
                                { deleting = font }
                            } else {
                                null
                            },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = onImport,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("导入字体文件")
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "支持 .ttf / .otf / .ttc。导入的字体对所有配置可见，" +
                        "系统字体与内置字体不可删除。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )

    /* ---------------- 重命名 ---------------- */

    renaming?.let { target ->
        var name by remember(target.id) { mutableStateOf(target.displayName) }
        val valid = name.isNotBlank()

        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("重命名字体") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("字体名称") },
                    isError = !valid,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = target.id
                        val newName = name
                        renaming = null
                        scope.launch {
                            withContext(Dispatchers.IO) { FontRegistry.rename(id, newName) }
                            localRevision++
                        }
                    },
                    enabled = valid,
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text("取消") }
            },
        )
    }

    /* ---------------- 删除 ---------------- */

    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除字体？") },
            text = {
                Text(
                    "将删除「${target.displayName}」。\n" +
                        "如果某个配置正在使用它，那个配置会回落到默认字体。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = target.id
                        deleting = null
                        scope.launch {
                            withContext(Dispatchers.IO) { FontRegistry.delete(id) }
                            localRevision++
                        }
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

/**
 * 字体 id → Compose 的 [FontFamily]。
 *
 * ============================================================
 * 为什么要分两条路
 * ============================================================
 * Compose 这个版本**没有 `Font(Typeface)` 这种桥接**，
 * 字体工厂只接受 File / AssetManager / ParcelFileDescriptor。
 * 因此：
 * - **系统字体** → 用内置的 [FontFamily.SansSerif] / [FontFamily.Serif] /
 *   [FontFamily.Monospace]（它们本来就是为通用族准备的）；
 * - **内置与导入字体** → `Font(file)`，文件由 [FontRegistry.resolveFile] 提供。
 *
 * 解析不出来时回落到默认字体，不会因为字体坏了画不出字。
 */
@Composable
fun composeFontFamily(fontId: String): FontFamily {
    val entry = remember(fontId) { FontRegistry.find(fontId) }
    val file = remember(fontId) { FontRegistry.resolveFile(fontId) }

    return remember(entry?.id, file?.path) {
        when {
            entry?.kind == FontKind.SYSTEM -> systemFontFamily(entry.id)
            file != null -> FontFamily(Font(file))
            else -> FontFamily.Default
        }
    }
}

/** 系统通用族 → Compose 的字体族 */
private fun systemFontFamily(fontId: String): FontFamily {
    val family = fontId.removePrefix("system:")
    return when (family) {
        "serif" -> FontFamily.Serif
        "monospace" -> FontFamily.Monospace
        // 其余统一用无衬线（当前系统字体只保留这一个）
        else -> FontFamily.SansSerif
    }
}

/**
 * 一行字体。
 *
 * 名称与样例文字都**用该字体自身渲染** —— 这是选字体时最有用的信息。
 * 系统字体加载失败（个别 ROM 上族名不认）时回落到默认字体，
 * 至少不会因为一个字体坏了整页崩掉。
 */
@Composable
private fun FontRow(
    entry: FontEntry,
    selected: Boolean,
    onClick: () -> Unit,
    onRename: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    val composeFont = composeFontFamily(entry.id)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = FONT_SAMPLE,
                fontFamily = composeFont,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
            )
        }

        onRename?.let { action ->
            IconButton(onClick = action) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "重命名",
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        onDelete?.let { action ->
            IconButton(onClick = action) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除",
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        if (selected) {
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                Icons.Default.Check,
                contentDescription = "当前使用",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** 样例文字：覆盖大小写字母与数字，能看出字体的主要特征 */
private const val FONT_SAMPLE = "ABCDEFG abcdefg 0123 按键显示"

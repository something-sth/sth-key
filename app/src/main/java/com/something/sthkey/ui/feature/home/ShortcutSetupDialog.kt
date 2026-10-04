package com.something.sthkey.ui.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 桌面快捷方式的设置窗口。
 *
 * ============================================================
 * 两种快捷方式，**互斥**（用单选区分"这次要创建哪一种"）
 * ============================================================
 * | 选择 | 行为 |
 * |---|---|
 * | 启动悬浮窗 | 勾选的配置会在点快捷方式时一起打开，并重启采集 |
 * | 一键关闭 | 关掉所有悬浮窗 |
 *
 * ⚠️ 为什么互斥而不是两套勾选：**一个快捷方式只能有一个行为**。
 * 做成"上面勾配置、下面独立一个开关"的话，用户会以为可以一次创建两个，
 * 或者以为那个开关是给勾选的配置用的。
 *
 * 但用户**可以创建多个**启动类快捷方式（每次确定都会分配一个新 id）。
 * 所以按钮文案是「添加到桌面」。
 *
 * ============================================================
 * ⚠️ 刻意**不列出已有的快捷方式、也不提供删除**
 * ============================================================
 * 第一版做过一个"已有的快捷方式"列表 + 删除按钮，但它**做不到它承诺的事**：
 *
 * - 应用能移除的只是**自己这条记录**与系统里的声明，
 *   而**桌面上那个图标是启动器管的** —— 多数启动器不会因为我们
 *   `removeLongLivedShortcuts` 就把用户的图标删掉；
 * - 结果就是用户点了删除、列表里没了，但桌面图标还在，
 *   而那个图标已经变成一个**空壳**（记录没了，点它什么也不发生）。
 *
 * "删不干净"比"没有删除功能"更糟。所以：**要删就让用户在桌面上长按删**，
 * 那是启动器自己的功能，一定删得掉。
 *
 * 也因此不需要读"已经创建了哪些" —— 每次打开这个窗口都是"新建一个"。
 *
 * ============================================================
 * ⚠️ 整个内容必须可滚动
 * ============================================================
 * 配置多起来时总高度会超出屏幕，而 `AlertDialog` 的内容区**不会自己滚动** ——
 * 表现是最下面的图标选择被切掉、或在窄屏上「确定」按钮直接看不见。
 */
@Composable
fun ShortcutSetupDialog(
    configs: List<Pair<String, String>>,
    iconOptions: List<ShortcutIconOption>,
    customIconBitmap: android.graphics.Bitmap?,
    onPickCustomIcon: () -> Unit,
    onConfirm: (disableAll: Boolean, configIds: List<String>, iconId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var disableAll by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf(emptySet<String>()) }

    /* 图标默认跟着第一份勾选的配置走，用户可以覆盖 */
    var iconId by remember { mutableStateOf(DEFAULT_ICON_ID) }

    val canConfirm = disableAll || checked.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("桌面快捷方式") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = DIALOG_CONTENT_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "创建一个桌面图标，点它就启动悬浮窗并重启监听，" +
                        "整个过程不会打开应用界面。想删掉图标就在桌面上长按它。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(12.dp))

                /* ---------- 启动悬浮窗 ---------- */

                ModeRow(
                    selected = !disableAll,
                    title = "启动悬浮窗",
                    subtitle = "点快捷方式时打开下面勾选的配置",
                    onSelect = { disableAll = false },
                )

                if (!disableAll) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = CONFIG_LIST_MAX_HEIGHT)
                            .padding(start = 12.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        configs.forEach { (id, name) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        checked = if (id in checked) checked - id else checked + id
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = id in checked,
                                    onCheckedChange = { on ->
                                        checked = if (on) checked + id else checked - id
                                    },
                                )
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    if (checked.isEmpty()) {
                        Text(
                            text = "至少勾选一份配置",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 12.dp, top = 4.dp),
                        )
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )

                /* ---------- 一键关闭 ---------- */

                ModeRow(
                    selected = disableAll,
                    title = "一键关闭所有悬浮窗",
                    subtitle = "点快捷方式时把所有悬浮窗都关掉",
                    onSelect = { disableAll = true },
                )

                /* ---------- 图标 ---------- */

                Spacer(modifier = Modifier.height(12.dp))

                ShortcutIconPicker(
                    options = iconOptions,
                    selectedId = iconId,
                    customBitmap = customIconBitmap,
                    onSelect = { iconId = it },
                    onPickCustom = onPickCustomIcon,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "图标在添加到桌面时定下，之后改配置不会自动更新 —— " +
                        "想换图标就再来这里创建一次。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(disableAll, checked.toList(), iconId) },
                enabled = canConfirm,
            ) {
                Text("添加到桌面")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** "默认图标"（应用图标）在 [ShortcutIconPicker] 里的标识 */
private const val DEFAULT_ICON_ID = "__default__"

/** 弹窗内容的总高度上限；超出的部分靠外层滚动 */
private val DIALOG_CONTENT_MAX_HEIGHT = 420.dp

/** 配置勾选列表自己的高度上限（它是最容易变长的一块） */
private val CONFIG_LIST_MAX_HEIGHT = 160.dp

/** 一个模式行：单选圆点 + 标题 + 副标题，整行可点 */
@Composable
private fun ModeRow(
    selected: Boolean,
    title: String,
    subtitle: String,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

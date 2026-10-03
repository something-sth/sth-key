package com.something.sthkey.ui.feature.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.something.sthkey.data.config.ExportMethod

/**
 * 导出方式选择窗口。
 *
 * ============================================================
 * 三个按钮，从上往下
 * ============================================================
 * | 按钮 | 机制 | 要不要弹系统界面 |
 * |---|---|---|
 * | 导出到目录 | 已配置的目录（默认 `Download/sthkeyconfigs/`） | 否 |
 * | 手动选择位置 | SAF `CreateDocument` | 是 |
 * | 分享 | FileProvider + 系统分享面板 | 是 |
 *
 * ⚠️ **"下载目录"与"自定义路径"合并成了一项** —— 它们对用户来说是
 * 同一件事（"存到某个目录里，别弹选择器"），差别只在哪个目录，
 * 而那是**设置**不是**方式**。目录在设置页改。
 *
 * 早先做成两个按钮是错的：用户在导出时被迫先想"这次存哪个目录"，
 * 而那个选择本来每次都该一样。
 *
 * @param configName 要导出的配置名（显示在标题与文件名提示里）
 * @param onPick 用户选了某种方式；`remember` 表示"设为默认导出方式"被勾上了
 */
@Composable
fun ExportMethodDialog(
    configName: String,
    onPick: (method: ExportMethod, remember: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var remember by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出「$configName」") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "选一种方式。勾选下面的开关后，下次点导出就不再问。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(12.dp))

                MethodRow(
                    icon = Icons.Default.Folder,
                    title = "导出到目录",
                    /*
                     * 副标题说清"会存到哪" —— 用户最怕的就是
                     * "点完了不知道文件去哪了"。
                     */
                    subtitle = "下载/sthkeyconfigs",
                    onClick = { onPick(ExportMethod.DOWNLOAD, remember) },
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                MethodRow(
                    icon = Icons.Default.CreateNewFolder,
                    title = "手动选择位置",
                    subtitle = "每次自己挑存到哪",
                    onClick = { onPick(ExportMethod.MANUAL, remember) },
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                MethodRow(
                    icon = Icons.Default.Share,
                    title = "分享",
                    subtitle = "直接发给微信、QQ 等",
                    onClick = { onPick(ExportMethod.SHARE, remember) },
                )

                Spacer(modifier = Modifier.height(8.dp))

                /*
                 * "设为默认导出方式"。
                 *
                 * ⚠️ 默认**不勾**：这是"以后不再问我"的授权，
                 * 不该预设成同意。用户勾了才记住。
                 */
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { remember = !remember }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = remember, onCheckedChange = { remember = it })
                    Text(
                        text = "设为默认导出方式",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                Text(
                    text = "想改回来：设置 → 配置导出 → 导出方式（选「每次询问」即可恢复这个窗口）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 弹窗里的一个方式：图标 + 标题 + 副标题，整行可点 */
@Composable
private fun MethodRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )

        Spacer(modifier = Modifier.width(14.dp))

        Column {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

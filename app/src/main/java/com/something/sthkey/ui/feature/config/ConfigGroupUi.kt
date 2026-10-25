package com.something.sthkey.ui.feature.config

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.something.sthkey.domain.config.ConfigGroup
import com.something.sthkey.domain.config.KeyStrokesConfig




/**
 * 配置列表里的一项：**要么是一份配置，要么是一个配置组合**。
 *
 * ⚠️ 用一个 sealed 类型而不是"两个列表分别渲染":
 * 列表是按 `columns` 切成行的（`chunked`），两个列表分开切会让
 * 行内混排变得很难对齐，而配置组合是要和配置**混在同一个网格里**的。
 */
sealed interface ConfigListItem {
    data class ConfigItem(val config: KeyStrokesConfig) : ConfigListItem
    data class GroupItem(val group: ConfigGroup) : ConfigListItem
}

/**
 * **配置组合卡片**。
 *
 * ============================================================
 * ⚠️ 为什么不把样式文字换成「配置组合」（用户自己也担心这点）
 * ============================================================
 * 用户的原话:
 *
 * > 卡片显示与其他配置一样，不过配置样式那一栏显示的是「配置组合」
 * > （我也不清楚这样做好不好，这会不会让用户以为"配置组合"也是一种配置样式呢？）
 *
 * ⭐ **会**。那个位置是"样式"信息，旁边的图标（键盘/手柄/触屏）也和样式绑定 ——
 * 把文字换成「配置组合」但图标仍是键盘，会很怪。
 *
 * ⚠️ 所以这里:
 * | 位置 | 配置卡片 | 组合卡片 |
 * |---|---|---|
 * | 右上角 | （无） | **「组合」徽章** —— 一眼区分 |
 * | 样式那一行 | `config.style.label` | **「N 个配置」** —— 描述内容，不是样式 |
 *
 * @param memberCount 成员数量（⚠️ 由调用方按**现存**成员算，不是 `memberIds.size`）
 */
@Composable
fun GroupCard(
    group: ConfigGroup,
    memberCount: Int,
    width: Dp,
    onClick: () -> Unit,
    onDissolve: () -> Unit,
    /** 是否显示描述行（受设置页「显示配置描述」控制） */
    showDescription: Boolean,
    /** 操作是否收进「更多」菜单（受设置页「操作收进更多菜单」控制） */
    compactActions: Boolean,
    /** 导出成 `.sthgroup`（⚠️ 与配置一样走 SAF 选择保存位置） */
    onExport: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .width(width)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            /* ⚠️ 与配置卡片同色（`surface`），否则组合会和普通配置长得不一样 */
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            /* 标题行：名称 + 「组合」徽章 */
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(6.dp))
                GroupBadge()
            }

            /* 描述（有才显示，跟配置卡片一致） */
            /* ⚠️ 与配置卡片一致：设置页关掉"显示描述"时整块不占位置 */
            if (showDescription && group.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = group.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            /*
             * ⚠️ 这一行原本是"样式名"，组合改成"N 个配置" ——
             * 说明的是**内容**，不会被当成样式。
             */
            Text(
                text = if (memberCount == 0) "还没有配置" else "$memberCount 个配置",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            /*
             * 操作区 —— 与配置卡片**同一套规矩**（用户要求统一）:
             *
             * | 设置页开关 | 卡片表现 |
             * |---|---|
             * | 关（默认） | 三个独立图标:导出 / 解散（没有复制，见下） |
             * | 开 | 收进一个「更多」菜单 |
             *
             * ⚠️ **没有「复制」**:配置组合的复制在语义上不成立 ——
             * 复制一个组合必须连里面的配置一起复制，否则两个组合会**共享同一批配置**，
             * 而"一个配置只属于一个组合"是这套模型的根基。
             * 用户也说"我不确定要不要搞这个，你看着办" —— 这就是我的判断。
             */
            if (compactActions) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Box {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "更多操作",
                            modifier = Modifier
                                .size(36.dp)
                                .clickable { menuOpen = true }
                                .padding(8.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("导出组合") },
                                onClick = {
                                    menuOpen = false
                                    onExport()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("解散组合") },
                                onClick = {
                                    menuOpen = false
                                    onDissolve()
                                },
                            )
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    CompactIconButton(
                        icon = Icons.Default.SaveAlt,
                        description = "导出组合",
                        onClick = onExport,
                        modifier = Modifier.weight(1f),
                    )
                    CompactIconButton(
                        icon = Icons.Default.Delete,
                        description = "解散组合",
                        onClick = onDissolve,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** 「组合」小徽章 —— 与主页 `StatusTag` 同一套观感（小圆角块） */
@Composable
private fun GroupBadge() {
    Box(
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                modifier = Modifier.size(11.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = "组合",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/** 解散确认对话框 */
@Composable
fun DissolveGroupDialog(
    group: ConfigGroup,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("解散「${group.name}」？") },
        text = {
            Text(
                "解散后，里面的 ${group.memberIds.size} 个配置会重新出现在配置列表里，" +
                    "配置本身不会被删除，悬浮窗开关也保持不变。",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("解散") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

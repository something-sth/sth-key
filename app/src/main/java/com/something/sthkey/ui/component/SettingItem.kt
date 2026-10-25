package com.something.sthkey.ui.component

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * 通用列表项。
 *
 * 设置页、关于页、调试页大量使用"标题 + 副标题 + 尾部"的结构，
 * 统一到这里，避免每个页面重复写 Row/Column 排版。
 *
 * ============================================================
 * ⚠️ 现在的样子（照 SukiSU 的 Material 侧组件）
 * ============================================================
 * | 部分 | 做法 |
 * |---|---|
 * | 整行 | **独立圆角矩形**（`SettingRowSurface`，18dp 圆角），不再是"卡片 + 分割线" |
 * | 左图标 | 装在 36dp 圆角方块里（`IconTile`），整列对齐 |
 * | 可点行 | 自动在尾部补一个 ‹ 箭头 |
 *
 * @param onClick 为 null 时整行不可点击（纯展示项）
 */
@Composable
fun SettingItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    SettingRowSurface(modifier = modifier, onClick = onClick) {
        if (leadingIcon != null) {
            IconTile(icon = leadingIcon)
            RowLeadingGap()
        }

        RowTextColumn(title = title, subtitle = subtitle)

        if (trailing != null) {
            Spacer(modifier = Modifier.width(12.dp))
            trailing()
        } else if (onClick != null) {
            /*
             * ⚠️ 可点行**自动**补箭头:这是"点进去"的通用暗示。
             * 只在 `trailing == null` 时补 —— 传了 `trailing` 说明调用方
             * 自己画了右边的东西（开关、数值…），再挤一个箭头会很难看。
             */
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 开关列表项。
 *
 * 整行可点（点哪都能切换）比只有开关本身可点要好用得多，
 * 触摸目标更大，也是 Material 的推荐做法。
 */
@Composable
fun SwitchItem(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
) {
    SettingItem(
        title = title,
        modifier = modifier,
        subtitle = subtitle,
        leadingIcon = leadingIcon,
        onClick = { if (enabled) onCheckedChange(!checked) },
        trailing = {
            /*
             * ⚠️ 用 `ExpressiveSwitch`（圆点里带对勾/叉），不是裸的 `Switch`。
             * 开关自己的 `onCheckedChange` 也要判 `enabled` ——
             * 禁用态下 Material3 虽然不响应，但这里再判一次更保险。
             */
            ExpressiveSwitch(
                checked = checked,
                onCheckedChange = { if (enabled) onCheckedChange(it) },
                enabled = enabled,
            )
        },
    )
}

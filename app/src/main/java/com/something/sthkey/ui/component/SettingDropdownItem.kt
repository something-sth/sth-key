package com.something.sthkey.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * **下拉选择行**：点一下在**这一行下面**展开菜单，选完收起。
 *
 * ============================================================
 * ⚠️ 为什么用 `DropdownMenu` 而不是对话框
 * ============================================================
 * 用户的原话:
 *
 * > 比如切换 material 与 miuix **你还搞了个窗口**，**你别这样搞**
 *
 * ⚠️ 早期版本用的是 `AlertDialog` —— 点开像"另开一页"，不是下拉。
 * 现在锚定在行上，与 SukiSU 的 `SegmentedDropdownItem`
 * （`ui/component/material/SegmentedList.kt`）是同一种交互形状。
 *
 * ⚠️ 每项右侧用对勾标出当前选中项，不用 `RadioButton` ——
 * 菜单里放单选按钮会显得像表单。
 *
 * @param selectedIndex 当前选中项；越界时会被安全钳到范围内
 */
@Composable
fun SettingDropdownItem(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true,
) {
    /*
     * ⚠️ 空列表时直接不渲染 —— 否则点开是一个空菜单，
     * 用户会以为坏了。SukiSU 用 `hasItems` / `safeIndex` 做同样的保护。
     */
    if (items.isEmpty()) return
    val safeIndex = selectedIndex.coerceIn(0, items.lastIndex)

    /* ---------------- 锚定在行上的 DropdownMenu ---------------- */
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxWidth()) {
        /*
         * ⚠️ 用 `SettingRowSurface` 而不是裸 `Row`:
         * 这样下拉行和普通行是**同一种独立圆角矩形**，
         * 混在同一个分组里不会突然变样。
         */
        SettingRowSurface(
            onClick = { if (enabled) expanded = true },
        ) {
            if (leadingIcon != null) {
                IconTile(icon = leadingIcon)
                RowLeadingGap()
            }

            /*
             * ⚠️ 没传 `subtitle` 时把**当前值**当副标题 ——
             * 下拉行不显示当前值会很难用（用户不知道现在选的是哪个）。
             */
            RowTextColumn(title = title, subtitle = subtitle ?: items[safeIndex])

            Spacer(modifier = Modifier.width(12.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }

        /*
         * ⚠️ `DropdownMenu` 锚在**这一行的 Box** 上（而不是整个屏幕），
         * 所以菜单会贴着这一行弹出 —— 与 SukiSU 的"锚定在按下位置"同一类交互。
         */
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            items.forEachIndexed { index, label ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        expanded = false
                        onItemSelected(index)
                    },
                    trailingIcon = if (index == safeIndex) {
                        {
                            /*
                             * ⚠️ 用勾号而不是 RadioButton:
                             * 下拉菜单里 RadioButton 会显得像表单，
                             * 勾号才是 SukiSU 那种菜单的观感。
                             */
                            Text(
                                text = "✓",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

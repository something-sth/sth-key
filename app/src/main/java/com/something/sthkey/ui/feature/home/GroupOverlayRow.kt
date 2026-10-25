package com.something.sthkey.ui.feature.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.something.sthkey.domain.config.ConfigGroup
import com.something.sthkey.ui.component.ExpressiveSwitch
import com.something.sthkey.ui.component.IconTileButton
import com.something.sthkey.ui.component.SettingRowSurface


/**
 * 主页的**配置组合行**。
 *
 * ============================================================
 * ⚠️ 与普通配置行的区别（用户的要求）
 * ============================================================
 * | 位置 | 普通配置行 | 组合行 |
 * |---|---|---|
 * | 左侧 | `Tune` 配置按钮（可触摸/可移出屏幕） | **向下的小三角**（展开/收起） |
 * | 副标题 | 样式名 + "· 显示中" | **「N 个配置」** |
 * | 右侧 | 开关 | 开关（开关整个组合） |
 *
 * ⚠️ 为什么组合行**没有** `Tune` 按钮:
 * 那按钮打开的是"这一个悬浮窗的可触摸 / 可移出屏幕"——
 * 而组合本身没有悬浮窗，那些设置是每个成员配置各自的。
 *
 * ⚠️ 展开**不要求**先打开开关（用户明确要求"这个不做限制"）。
 *
 * ⚠️ 空组合的开关要**禁用**:没有成员可开，点了什么都不会发生。
 */
@Composable
fun GroupOverlayRow(
    group: ConfigGroup,
    memberCount: Int,
    enabled: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    /* 三角旋转 180° 表示展开 —— 比换图标更连贯（`animateFloatAsState` 带过渡） */
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "group_arrow",
    )

    SettingRowSurface {
        /*
         * ⚠️ 三角要装在**圆角方块**里（用户要求:「配置组合左侧的小三角按钮，
         * 应该加上圆角正方形底，这是我们之前做UI时的策略，统一才好看」）。
         *
         * ⚠️ 用 `IconTileButton` + `rotationDegrees`，而不是自己包一个 `Box`:
         * 方块的尺寸/圆角/底色都来自同一处，改一处全都跟着变 ——
         * 手抄一份的话迟早和别处的方块对不上。
         *
         * ⚠️ 方块**不转**、只转里面的三角:方块跟着转的话，
         * 展开时会看到一个歪掉的方块，很怪。
         */
        IconTileButton(
            icon = Icons.Default.KeyboardArrowDown,
            contentDescription = if (expanded) "收起组合" else "展开组合",
            onClick = onToggleExpand,
            rotationDegrees = rotation,
        )

        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = group.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (memberCount == 0) "组合 · 还没有配置" else "组合 · $memberCount 个配置",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        /*
         * ⚠️ 空组合禁用开关:没有成员，"打开组合"无从谈起。
         * 不 Disable 的话用户点了开关，界面毫无变化，会以为功能坏了。
         */
        ExpressiveSwitch(
            checked = enabled,
            onCheckedChange = { on -> if (memberCount > 0) onToggle(on) },
            enabled = memberCount > 0,
        )
    }
}

package com.something.sthkey.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 悬浮胶囊底栏（**纯 Material3**，照 SukiSU 的 `BottomBarMaterial` 非玻璃分支）。
 *
 * ============================================================
 * ⚠️ 视觉与交互（不要随手改）
 * ============================================================
 * | 部分 | 值 | 来自 |
 * |---|---|---|
 * | 外框 | `Box` + 系统栏内边距 + `padding(horizontal = 24, vertical = 12)` | SukiSU 同款 |
 * | 胶囊 | `Surface(CircleShape)`、`surfaceContainerHighest`、`shadowElevation = 6.dp` | 同上 |
 * | 最大宽度 | `widthIn(max = 480.dp)` | 同上（平板/折叠屏上不至于拉成一条长条） |
 * | 行高 | `64.dp`，水平 padding `8.dp`，`SpaceBetween` | 同上 |
 *
 * ⚠️ **选中项会展开成"图标 + 文字"**，未选中只有图标：
 * 这是 Material3 Expressive 的标志性交互，也是 SukiSU 那个底栏最好认的特征。
 * 靠 `animateContentSize()` + `AnimatedVisibility` 实现，
 * 两项一起动才有"文字从图标里长出来"的效果 —— 只做一个会显得很硬。
 *
 * ⚠️ 选中项用 `primary` 铺底、`onPrimary` 上色；未选中用
 * `surfaceContainerHighest`（与胶囊同色，视觉上"隐形"）配 `onSurfaceVariant`。
 * 未选中如果也用 `primary` 的淡色，三个胶囊会一起抢眼。
 */
@Immutable
data class FloatingTab(
    val label: String,
    /** 选中时的图标（实心） */
    val selectedIcon: ImageVector,
    /** 未选中时的图标（描边） */
    val unselectedIcon: ImageVector,
)

@Composable
fun MaterialFloatingBottomBar(
    tabs: List<FloatingTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 是否套系统栏内边距。
     *
     * ⚠️ 真正挂在 `Scaffold` 的 `bottomBar` 槽里时**必须**为 true，
     * 否则手势条会压在胶囊上。
     *
     * ⚠️ 主题设置页里那小块**预览**要传 false —— 预览外面已经有卡片内边距了，
     * 再加一次导航栏高度会多出一大块空白，看起来像布局坏了。
     */
    applyWindowInsets: Boolean = true,
) {
    if (tabs.isEmpty()) return

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (applyWindowInsets) {
                    /*
                     * ⚠️ 必须自己避开系统栏。
                     * 放在 `Scaffold` 的 `bottomBar` 槽里时，系统**不会**帮你加这个内边距 ——
                     * 不写的话手势条会压在胶囊上，看起来像被切掉一截。
                     *
                     * ⚠️ 用 `systemBars ∪ displayCutout` 的**底部与水平**部分:
                     * 横屏时刘海在侧面，胶囊要靠里一点才不被挡。
                     */
                    Modifier.windowInsetsPadding(
                        WindowInsets.systemBars
                            .union(WindowInsets.displayCutout)
                            .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                    )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 480.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            /*
             * ⚠️ 阴影用 `shadowElevation` 而不是 `Modifier.shadow()`:
             * `Surface` 的 `shadowElevation` 会跟着 shape 走，而
             * `Modifier.shadow()` 要在 `background` 之前、还要自己配 clip，
             * 顺序错了就是一块灰底（这个坑之前踩过）。
             */
            shadowElevation = 6.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = 8.dp)
                    /*
                     * ⚠️ `selectableGroup()` 让无障碍把它读成"一组单选项"，
                     * 而不是三个互不相关的按钮。
                     */
                    .selectableGroup(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEachIndexed { index, tab ->
                    FloatingTabItem(
                        tab = tab,
                        selected = selectedIndex == index,
                        onClick = { onSelect(index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FloatingTabItem(
    tab: FloatingTab,
    selected: Boolean,
    onClick: () -> Unit,
) {
    /*
     * ⚠️ 颜色用 `animateColorAsState` 过渡，而不是瞬间切换 ——
     * 与 `animateContentSize` 的时长对不上就会显得"文字先出来、底色后到"。
     */
    val containerColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        label = "floating_tab_container",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "floating_tab_content",
    )

    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Row(
            modifier = Modifier
                .height(48.dp)
                .clip(CircleShape)
                .background(containerColor)
                .selectable(selected = selected, onClick = onClick, role = Role.Tab)
                .animateContentSize()
                /*
                 * ⚠️ 选中时水平留 16.dp（文字两侧要呼吸），未选中 12.dp ——
                 * 一样宽的话，未选中的图标会被撑得离边缘太远，三个图标看起来不齐。
                 */
                .padding(horizontal = if (selected) 16.dp else 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                /*
                 * ⚠️ 选中时 `contentDescription = null`:
                 * 旁边已经有文字了，读屏再念一遍是重复的。
                 * 未选中只有图标，必须给描述，否则读屏念不出来。
                 */
                contentDescription = if (selected) null else tab.label,
            )

            AnimatedVisibility(
                visible = selected,
                enter = fadeIn() + expandHorizontally(),
                exit = fadeOut() + shrinkHorizontally(),
            ) {
                Text(
                    text = tab.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

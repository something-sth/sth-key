package com.something.sthkey.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/*
 * ============================================================
 * 可折叠分区
 * ============================================================
 * 用户的原话:"优化一下键盘样式与手柄样式的配置编辑页，让现在的'外观'、
 * '颜色'等标题，变成可折叠的 box（就跟自定义编辑页一样），
 * 现在配置多，不方便找"。
 *
 * 配置项多起来之后，一页要滚很久才能找到想要的那一项。折叠之后
 * 一屏能放下所有分区标题，找东西变成"先看标题、再展开"。
 *
 * ============================================================
 * ⚠️⚠️ 为什么标题和内容必须写在**同一个函数**里
 * ============================================================
 * 第一版是分开写的 —— 调用方自己写 `if (key in expanded) { 标题; 内容 }`。
 * 结果连续出了两次事故:
 *
 * 1. **标题被包进了守卫**，而守卫默认是 false → 标题和内容**一起消失**，
 *    而且**没有任何办法展开**（能点的标题自己就在条件里面）；
 * 2. 手工插"折叠结束"的括号时**多写了一个 `}`**，把后面 8 个分区
 *    挤出了 `LazyColumn` —— 表现就是"除了基本信息和其它，其他都不见了"。
 *
 * ⚠️ 所以现在把这个结构**封在这一处**:
 *
 * - 标题**永远**渲染 → 不可能出现"没东西可点"
 * - 花括号由**这一个函数**配平 → 调用方不再手写守卫
 *
 * ⚠️ **不要**再退回到调用方手写 `if (… in expandedSections) { … }`。
 */

/**
 * 把某个分区的展开状态设成 [expanded]，返回新的集合。
 *
 * ⚠️ 提成**文件级函数**而不是写在 composable 里:
 * 折叠状态由页面持有，而分区散落在主函数与三个样式分流函数里
 * （`live2DStyleSections` / `customKeyStyleSections` / `joystickStyleSections`），
 * 各写一份切换逻辑的话，"点一下没反应"这类问题会只在其中一处出现 —— 很难查。
 */
internal fun setExpanded(current: Set<String>, key: String, expanded: Boolean): Set<String> =
    if (expanded) current + key else current - key

/**
 * 一个可折叠分区:标题 + 内容。
 *
 * 用法（`item { }` **不要**自己加，由本函数负责）:
 *
 * ```kotlin
 * collapsibleSection(
 *     expanded = "外观" in expandedSections,
 *     onExpandChange = { on -> expandedSections = setExpanded(expandedSections, "外观", on) },
 *     key = "外观",
 *     title = "外观",
 * ) {
 *     item { SettingsCard { … } }
 *     item { SectionHint(…) }
 * }
 * ```
 *
 * ⚠️ **不要在 `content` 里写标题** —— 标题由本函数渲染，
 * 写两份会得到两个标题，而且其中一个会在收起时消失。
 *
 * @param expanded 当前是否展开
 * @param onExpandChange 切换展开状态；**新值由本函数算好**，调用方只管写回
 * @param key 折叠状态的键。**必须唯一** —— 两种样式有同名分区（都叫"外观"），
 *   用标题当键会让两处联动（点一个，另一个也展开）
 * @param title 标题文字
 * @param content 分区内容（**不含标题**）
 */
internal fun LazyListScope.collapsibleSection(
    expanded: Boolean,
    onExpandChange: (Boolean) -> Unit,
    key: String,
    title: String,
    content: LazyListScope.() -> Unit,
) {
    item(key = "header:$key") {
        CollapsibleSectionHeader(
            text = title,
            expanded = expanded,
            onToggle = { onExpandChange(!expanded) },
        )
    }

    /*
     * ⚠️ 内容**只在展开时**加入列表 —— 收起时那几十个控件根本不组合，
     * 这也是折叠能"一屏放下所有标题"的原因（不是靠把它们设成不可见）。
     */
    if (expanded) {
        content()
    }
}

/**
 * 分区标题，**可点击折叠**。
 *
 * ⚠️ 与不可折叠的 [SectionHeader] 的区别**只有一个:能不能点**。
 * 字号、颜色、边距全部沿用同一套，这样同一个页面里两种标题
 * 看起来是一家人（`基本信息` / `其它` 用不可折叠的那种）。
 *
 * ⚠️ 右侧箭头是**必须的** —— 没有它用户不知道这里能点。
 * 自定义编辑页的 `PanelGroup` 也是这么做的，两边保持一致。
 */
@Composable
internal fun CollapsibleSectionHeader(
    text: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            /*
             * ⚠️ `clickable` 要在 `padding` **之前** —— 这样整行
             * （含标题左右两侧的空白）都是可点区域。
             * 反过来写的话只有文字本身能点，用户点在文字旁边没反应，
             * 会以为这个标题不能折叠。
             */
            .clickable(onClick = onToggle)
            .padding(top = 8.dp, start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )

        /* 箭头跟着展开状态翻转，告诉用户"点这里会发生什么" */
        Icon(
            imageVector = if (expanded) {
                Icons.Default.KeyboardArrowUp
            } else {
                Icons.Default.KeyboardArrowDown
            },
            contentDescription = if (expanded) "收起" else "展开",
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

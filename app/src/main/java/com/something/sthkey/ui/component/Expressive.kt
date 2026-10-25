package com.something.sthkey.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp




/**
 * 设置列表的**视觉基元**（照 SukiSU 的 Material 侧组件）。
 *
 * ============================================================
 * ⚠️ 与旧版的三处区别（用户点名要的）
 * ============================================================
 * | 项 | 旧 | 新 |
 * |---|---|---|
 * | 分组内的行 | 共用一张卡片 + 之间画**分割线** | 每行一张**独立圆角矩形**，行与行之间留 2dp 缝 |
 * | 左侧图标 | 一个光秃秃的 22dp 图标 | 图标装在 **36dp 圆角方块**里（`secondaryContainer` 底） |
 * | 开关 | Material3 默认（纯色圆点） | 圆点里带 **对勾 / 叉**，切换动画更明显 |
 *
 * ⚠️ 为什么"独立圆角矩形"比"一张卡片 + 分割线"好看:
 * 分割线要求两侧必须严丝合缝，任何一处内边距不一致都会露出断线；
 * 独立矩形之间靠**缝隙**分隔，天然不会有这种问题，而且按下时的水波
 * 被自己的圆角裁住，不会糊到相邻行上。
 *
 * ⚠️ 与 SukiSU 的一处**有意偏差**:它按"组内第几项"区分圆角
 * （首尾 24dp、中间 6dp），我们统一用 18dp。
 * 原因是它那套需要知道**组内一共几项**，而我们的
 * `SettingsCard { ... }` 是个普通 lambda，数不出子项数量 ——
 * 硬数（用计数器）在有条件分支的页面里会错位。
 * 统一 18dp 视觉上同样是"每行独立圆角矩形"，且不会出错。
 */

/** 设置行的圆角。⚠️ 改这里会同时影响所有行，不要给单个页面单独调 */
private val RowShape = RoundedCornerShape(18.dp)

/** 图标方块的圆角 */
private val IconTileShape = RoundedCornerShape(12.dp)

/**
 * 一行设置项的容器。
 *
 * ⚠️ 它**不画底色**，底色由外层 SettingsCard 提供（见 Common.kt 里的说明）。
 * 这里只负责两件事:圆角裁剪（让水波不糊到相邻行）与点击反馈。
 *
 * @param onClick 为 null 时不可点（纯展示行）
 */
@Composable
fun SettingRowSurface(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    /*
     * ⚠️ 两种 `Surface` 重载分开用:
     * 可点版本会自带水波 + 语义 role，不可点版本则完全没有交互反馈。
     * 判断写成 `if` 而不是给 onClick 传空 lambda —— 传空 lambda 的话
     * 纯展示行也会响应点击（按下去有水波），用户会以为它能点。
     */
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = RowShape,
            /* ⚠️ 透明:底色由 SettingsCard 画，画了就是卡里套卡 */
            color = Color.Transparent,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RowShape,
            /* ⚠️ 透明:底色由 SettingsCard 画，画了就是卡里套卡 */
            color = Color.Transparent,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}

/**
 * 图标方块：把图标装进一个圆角方块里。
 *
 * ⚠️ 这是 SukiSU 观感里很关键的一笔。纯图标排在左边会显得"散"，
 * 装进方块之后每行左侧有了一块**等宽的色块**，整列就对得很齐。
 *
 * ⚠️ 尺寸不要随手改:40dp 方块 + 22dp 图标是视觉上最稳的比例
 * （图标约占方块的 55%）。改大了图标显小、改小了图标挤。
 *
 * @param enabled 为 false 时整块压暗 —— 禁用态必须**看得出来**，
 *                否则用户会以为点了没反应。
 * @param rotationDegrees 图标自身的旋转角度（**方块不转**）。
 *                ⚠️ 主页组合行的展开三角用它:方块保持不动、只转三角，
 *                这样和"其它行左侧的方块"是同一个形状，观感才统一
 *                （用户的要求:「配置组合左侧的小三角按钮，应该加上圆角正方形底」）。
 */
@Composable
fun IconTile(
    icon: ImageVector,
    contentDescription: String? = null,
    enabled: Boolean = true,
    rotationDegrees: Float = 0f,
) {
    val alpha = if (enabled) 1f else 0.38f
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(IconTileShape)
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = alpha)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier
                .size(22.dp)
                .rotate(rotationDegrees),
            tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = alpha),
        )
    }
}

/**
 * 可点的图标方块（主页配置行左侧那个「编辑」按钮、组合行的展开三角）。
 *
 * ⚠️ 单独一个函数而不是给 [IconTile] 加可空的 `onClick`:
 * 不可点的图标方块在列表里到处都是（当装饰用），
 * 加一个可空的 `onClick` 会让「这块能不能点」变得含糊。
 */
@Composable
fun IconTileButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    rotationDegrees: Float = 0f,
) {
    Box(
        modifier = modifier
            .clip(IconTileShape)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        IconTile(
            icon = icon,
            contentDescription = contentDescription,
            enabled = enabled,
            rotationDegrees = rotationDegrees,
        )
    }
}

/**
 * 带对勾 / 叉的开关。
 *
 * ⚠️ 只是 Material3 `Switch` 加上 `thumbContent` —— 圆点里画图标，
 * 切换时图标跟着**转**（Material3 自带），这就是"更灵动"的来源。
 * 自己画一个的话这些细节都要重做，而且容易和系统动效对不上。
 *
 * ⚠️ 未选中时画**叉**而不是留空:留空的话用户分不清"关闭"与"不可用"。
 */
@Composable
fun ExpressiveSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: SwitchColors = expressiveSwitchColors(),
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        thumbContent = {
            Icon(
                imageVector = if (checked) Icons.Filled.Check else Icons.Filled.Close,
                contentDescription = null,
                modifier = Modifier.size(SwitchDefaults.IconSize),
            )
        },
        enabled = enabled,
        colors = colors,
    )
}

/**
 * 开关配色。
 *
 * ⚠️ 只覆盖**图标**与**禁用态**几项，其余保持 Material3 默认 ——
 * 自己把轨道色也改掉的话，深色模式下很容易和行背景糊在一起。
 */
@Composable
fun expressiveSwitchColors(): SwitchColors = SwitchDefaults.colors(
    checkedIconColor = MaterialTheme.colorScheme.primary,
    uncheckedIconColor = MaterialTheme.colorScheme.surfaceContainerHighest,
)

/**
 * 分组之间的空隙（`CardDivider` 的新实现）。
 *
 * ⚠️ 现在**不再画线**了:行与行本来就靠 `SettingsCard` 的 2dp 缝隙分隔。
 * 保留这个函数是为了不改动二十来个页面的调用点 ——
 * 它现在只额外多留一点点缝，用来区分"同一张卡片里的两小组"。
 */
@Composable
fun RowGap() {
    Spacer(modifier = Modifier.height(2.dp))
}

/** 行内的横向间隔（图标与文字之间） */
@Composable
internal fun RowLeadingGap() {
    Spacer(modifier = Modifier.width(12.dp))
}

/** 行内的文字列（标题 + 副标题），占满剩余宽度 */
@Composable
internal fun RowScope.RowTextColumn(
    title: String,
    subtitle: String?,
) {
    Column(
        modifier = Modifier.weight(1f),
        /*
         * ⚠️ 主标题与副标题之间留 **2dp**:
         * 用户对比 SukiSU 后指出「主标题与副标题之间有一点间隔，
         * 让组件整体看起来不会太高」—— 紧紧贴在一起会显得行更笨重。
         */
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        androidx.compose.material3.Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
        )
        if (subtitle != null) {
            androidx.compose.material3.Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

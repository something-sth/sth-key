package com.something.sthkey.ui.component

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
/**
 * 页面骨架。
 *
 * 统一三件事，避免每个页面各写一套 Scaffold：
 * 1. 顶栏（标题 + 可选副标题 + 可选返回键）；
 * 2. 内容区的水平内边距与横向间距；
 * 3. 滚动方式（[LazyColumn] 或普通 [Column]）。
 *
 * [bottomBar] 用于需要常驻底部操作的页面（例如"保存生效"模式下的配置编辑页）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    bottomBar: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(text = title, style = MaterialTheme.typography.titleLarge)
                        if (subtitle != null) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                            )
                        }
                    }
                },
                actions = { actions() },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = floatingActionButton,
        bottomBar = { bottomBar?.invoke() },
        content = content,
    )
}

/**
 * 可滚动页面容器：把 [ScreenScaffold] 与 LazyColumn 组合起来。
 *
 * 用 LazyColumn 而不是 Column + verticalScroll：配置页条目较多，
 * 懒加载能避免一次性组合全部内容。
 */
@Composable
fun ScrollableScreen(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    bottomBar: (@Composable () -> Unit)? = null,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    ScreenScaffold(
        title = title,
        modifier = modifier,
        subtitle = subtitle,
        onBack = onBack,
        actions = actions,
        floatingActionButton = floatingActionButton,
        bottomBar = bottomBar,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** 分区标题，用在卡片组之间 */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 8.dp, start = 4.dp),
    )
}

/** 分区说明文字 */
@Composable
fun SectionHint(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 4.dp),
    )
}

/**
 * **可折叠**的分区标题。
 *
 * ============================================================
 * 为什么需要它
 * ============================================================
 * 用户的原话:"优化一下键盘样式与手柄样式的配置编辑页，让现在的'外观'、'颜色'
 * 等标题，变成可折叠的 box（就跟自定义编辑页一样），现在配置多，不方便找"。
 *
 * 配置项多起来之后，一页要滚很久才能找到想要的那一项。折叠之后
 * 一屏能放下所有分区标题，找东西变成"先看标题、再展开"。
 *
 * ⚠️ **与 [SectionHeader] 的区别只有一个:能不能点。**
 * 字号、颜色、边距全部沿用同一套，这样同一个页面里两种标题
 * 看起来是一家人（`基本信息` / `其它` 用不可折叠的那种）。
 *
 * ⚠️ 右侧箭头是**必须的** —— 没有它用户不知道这里能点。
 * 自定义编辑页的 `PanelGroup` 也是这么做的，两边保持一致。
 *
 * @param text 分区标题
 * @param expanded 当前是否展开
 * @param onToggle 点击标题时切换展开/收起
 */
@Composable
fun CollapsibleSectionHeader(
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

/**
 * 卡片容器。
 *
 * 用于把相关联的设置项收进一张圆角卡片，是设置类界面的主要视觉单元。
 */
@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column {
            content()
        }
    }
}

/** 卡片内的分隔线，左右留出与内容一致的内边距 */
@Composable
fun CardDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** 两个区块之间的空隙 */
@Composable
fun Gap(height: Int = 8) {
    Spacer(modifier = Modifier.height(height.dp))
}

/**
 * 从 Compose 的 `LocalContext` 拿到宿主 Activity。
 *
 * 用途：个别系统 API 必须由 Activity 发起（例如 Shizuku 的授权对话框，
 * 它内部走的是 `Activity.requestPermissions()`）。
 * 这类引用**不能长期持有**（配置变更后旧 Activity 即失效并泄漏），
 * 所以只在点击回调里临时取一次。
 */
fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/** 空状态提示：列表没有内容时使用，避免出现"白屏" */
@Composable
fun EmptyHint(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(vertical = 32.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

package com.something.sthkey.ui.component

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    MaterialScreenScaffold(
        title = title,
        modifier = modifier,
        subtitle = subtitle,
        onBack = onBack,
        actions = actions,
        floatingActionButton = floatingActionButton,
        bottomBar = bottomBar,
        content = content,
    )
}

/** Material 3 皮肤的骨架（原来那一套，行为完全不变） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MaterialScreenScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    bottomBar: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    /*
     * ============================================================
     * ⚠️⚠️ 顶栏:普通 `TopAppBar` + **大号粗体标题**
     * ============================================================
     * 用户的原话:
     *
     * > 副标题删了之后主标题应该更大一些才是；标题位置偏下，
     * > 例如配置页、设置页、主题设置页、配置编辑页（配置名称的显示应该放在返回按钮右边）
     *
     * ⚠️⚠️ 为什么**不用**大标题顶栏（踩过的坑，别再试）:
     *
     * | 组件 | 情况 |
     * |---|---|
     * | `LargeTopAppBar` | 能用，但它把标题压在**一大条顶栏的底部** → 标题位置偏低（用户报的就是这个） |
     * | `LargeFlexibleTopAppBar`（SukiSU 用的那个） | ⚠️ **本项目用不了**:material3 1.5.0-alpha22 里它是 `internal` |
     *
     * ⚠️ 所以这里只用普通顶栏:高度固定 64dp，标题**紧贴左侧**（有返回键时紧贴返回键右侧），
     * 靠**字号加粗放大**来做出「大标题」的观感。
     *
     * ⚠️ 真要做 SukiSU 那种"上滚收缩"的大标题，唯一的路是把 material3 升到 1.5.0-alpha28+
     * （`LargeFlexibleTopAppBar` 从那一版起才是 public）—— 那要连带升 Compose 运行时，没敢动。
     */
    Scaffold(
        modifier = modifier,
        /* ⚠️ 页面底色 surfaceContainer（灰）+ 卡片 surface（白）—— 与 SukiSU 一致 */
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                title = {
                    /*
                     * ⚠️ 只显示主标题，**不显示副标题**（用户要求）；`subtitle` 参数保留但不渲染。
                     * ⚠️ 字号:一级页面（无返回键）用 `headlineSmall`(24sp)，
                     * 二级页面用 `titleLarge`(22sp) —— 副标题去掉后 22sp 已经显得空，
                     * 而 24sp 在有返回键的窄标题区里刚好不挤。
                     */
                    Text(
                        text = title,
                        style = if (onBack == null) {
                            MaterialTheme.typography.headlineSmall
                        } else {
                            MaterialTheme.typography.titleLarge
                        },
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (onBack != null) {
                        /*
                         * ⚠️ 返回按钮要有**圆形底**（用户要求，SukiSU 同款）:
                         * 光秃秃一个箭头压在页面上不够清楚，加圆形底之后一眼能认出可点。
                         *
                         * ⚠️ 用 `IconButtonDefaults.iconButtonColors(containerColor = ...)`，
                         * 不要自己包 `Box` + `background` —— 那样会丢掉水波与 48dp 触摸区。
                         *
                         * ⚠️ 底色 `surfaceContainerHighest`（比页面深一档），与 SukiSU 的
                         * `TopBarBackButton` 一致。
                         */
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.padding(horizontal = 10.dp),
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "返回",
                            )
                        }
                    }
                },
                actions = { actions() },
                colors = TopAppBarDefaults.topAppBarColors(
                    /* ⚠️ 与页面底色一致，否则顶栏会在灰底上留一条白带 */
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        /*
         * ⚠️ 悬浮底栏**不进** `bottomBar` 槽位（那样会预留一条空白，
         * 看起来像"底栏被安在一块背景上"）。
         *
         * ⚠️ 代价是 `Scaffold` 不知道底部有东西，所以**悬浮按钮要自己上移**，
         * 否则会被胶囊挡住（用户报的「新建配置按钮跑到底栏后面」）。
         * 高度从 `LocalBottomBarInset` 拿 —— 只有悬浮样式下它不是 0。
         */
        floatingActionButton = {
            Box(modifier = Modifier.padding(bottom = LocalBottomBarInset.current)) {
                floatingActionButton()
            }
        },
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
            /*
             * ⚠️ 底部加 `LocalBottomBarInset`:悬浮底栏压在内容上时，
             * 最后一项要能滚到胶囊**上面**，否则会被挡住半截。
             */
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = 24.dp + LocalBottomBarInset.current,
            ),
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
 * **可折叠**的分区标题 —— 已挪到 [SectionCollapse.kt]。
 *
 * ⚠️ 挪走的原因:它和"可折叠分区"那个封装必须放在一起。
 * 分开时调用方会自己写 `if (key in expanded) { 标题; 内容 }`，
 * 而那个写法出过两次事故（标题被包进守卫 → 整块消失且无法展开）。
 * 详见 `SectionCollapse.kt` 顶部那段。
 */

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
    /*
     * ============================================================
     * ⚠️⚠️ 这是一张**白色卡片**，不是透明容器
     * ============================================================
     * ⚠️ 上一版我把它改成了透明 `Column`（想让每行自己画圆角矩形），
     * 结果**大量内容失去了背景** —— 用户报的:
     *
     * > 配置页的配置卡片的背景没了，全糊背景上了，而且还有很多东西都没背景了，
     * > 你要知道不止 switch 组件需要背景
     *
     * ⚠️ 原因:全项目有 50 处 `SettingsCard`，其中很多装的是 `Text` / 滑块 /
     * 自定义行 —— 这些**自己不会画底色**，全靠这张卡。行能自己画，
     * 但其它内容不能，所以"卡片透明"这条路走不通。
     *
     * ⚠️ 现在的分工:
     * | 谁 | 负责什么 |
     * |---|---|
     * | `SettingsCard` | 白色圆角卡片**底色** |
     * | `SettingRowSurface` | 只负责裁剪与点击反馈，**不再画底色** |
     * | `CardDivider` | 行与行之间的细线 |
     *
     * ⚠️ 这就是 SukiSU 截图里「管理器版本」那一块的形状:白卡片 + 细分割线。
     */
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column {
            content()
        }
    }
}

/**
 * 卡片内的分隔线，左右留出与内容一致的内边距。
 *
 * ⚠️ 恢复成真正的线（上一版被我改成了 2dp 空隙）。
 * 卡片底色回来了之后，只有空隙没有线的话，行与行会糊成一片。
 */
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

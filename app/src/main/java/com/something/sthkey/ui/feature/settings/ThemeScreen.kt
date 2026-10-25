package com.something.sthkey.ui.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.ViewCarousel
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.something.sthkey.domain.ui.AppearanceColorMode
import com.something.sthkey.domain.ui.AppearancePalette
import com.something.sthkey.domain.ui.BottomBarStyle
import com.something.sthkey.ui.component.CardDivider
import com.something.sthkey.ui.component.FloatingTab
import com.something.sthkey.ui.component.MaterialFloatingBottomBar
import com.something.sthkey.ui.component.ScrollableScreen
import com.something.sthkey.ui.component.SectionHeader
import com.something.sthkey.ui.component.SectionHint
import com.something.sthkey.ui.component.SettingDropdownItem
import com.something.sthkey.ui.component.SettingItem
import com.something.sthkey.ui.component.SettingsCard
import com.something.sthkey.ui.MainViewModel




/**
 * **主题设置** —— 从「设置 → 外观」点进来。
 *
 * ============================================================
 * ⚠️⚠️ 为什么单独开一页（用户的要求 + 照 SukiSU 的做法）
 * ============================================================
 * 用户的原话:
 *
 * > 现在我想把 material 的 ui 主题也做细节一点，我们也像 sukisu 那样
 * > 单独分一个「主题设置」
 *
 * ⚠️ SukiSU 那边确实是独立一页（`ColorPaletteScreen`，从设置页的一行点进去，
 * 编辑框在顶上、下面是分组开关）。所以这里也是页面，不是塞进设置页的那一坨里。
 *
 * ============================================================
 * ⚠️ 这一页**只有真正生效的选项**
 * ============================================================
 * | 选项 | 为什么保留 / 不保留 |
 * |---|---|
 * | 明暗模式 | ✅ 对 Material 真正生效（`SthKeyTheme` 会切 colorScheme） |
 * | 底栏样式 | ✅ 两种样式都是 Material3 实现的，切了立刻能看到 |
 * | ~~色彩风格~~ | ❌ **删过又没加回来**:它必须配"取色种子"才生效，单给 palette 是空转 |
 * | ~~液态玻璃~~ | ❌ 需要 `miuix-blur 0.9.1+`（minSdk 32 / Kotlin 2.4），代价太大 |
 * | ~~导航栏角标~~ | ❌ 我们底栏上没有数量可显示（SukiSU 显示的是"已授权应用数"） |
 *
 * ⚠️ 所有选项**点完立即生效**（写偏好 + 改状态量），没有"保存"按钮。
 */
@Composable
fun ThemeScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScrollableScreen(
        title = "主题设置",
        subtitle = "配色与底栏",
        onBack = onBack,
        modifier = modifier,
    ) {
        /* ---------------- 配色 ---------------- */
        item { SectionHeader("配色") }

        item {
            SettingsCard {
                SettingDropdownItem(
                    title = "明暗模式",
                    leadingIcon = Icons.Rounded.DarkMode,
                    /* ⚠️ `AppearanceColorMode` 没有 description 字段，用 label 当副标题即可 */
                    items = AppearanceColorMode.entries.map { it.label },
                    selectedIndex = AppearanceColorMode.entries.indexOf(
                        viewModel.appearanceColorMode,
                    ),
                    onItemSelected = { index ->
                        AppearanceColorMode.entries.getOrNull(index)?.let(
                            viewModel::applyAppearanceColorMode,
                        )
                    },
                )
            }
        }

        item {
            SectionHint(
                "「跟随系统」是最省心的选择；选浅色 / 深色会覆盖系统设置。" +
                    "带「取色」的三种需要 Android 12 及以上，低版本会自动退回固定配色。",
            )
        }

        /* ---------------- 主色 ---------------- */
        item { SectionHeader("主色") }

        item {
            SettingsCard {
                SeedColorRow(
                    selected = viewModel.seedColor,
                    onSelect = viewModel::applySeedColor,
                )
            }
        }

        item {
            SectionHint(
                "选一个颜色，整套界面会按 Material 3 的算法从它推导出配色。" +
                    "「跟随壁纸」需要 Android 12 及以上。",
            )
        }

        /*
         * ⚠️ 色彩风格**只在选了主色时显示**。
         *
         * 它的作用是"怎么从主色推导"，没有主色时无从推导 ——
         * 显示出来就是一次"改了没反应"，上一轮已经因为这个把它删过一次。
         */
        if (viewModel.seedColor != 0) {
            item { SectionHeader("色彩风格") }

            item {
                SettingsCard {
                    SettingDropdownItem(
                        title = "色彩风格",
                        leadingIcon = Icons.Rounded.Style,
                        subtitle = viewModel.appearancePalette.description,
                        items = AppearancePalette.entries.map { it.label },
                        selectedIndex = AppearancePalette.entries.indexOf(
                            viewModel.appearancePalette,
                        ),
                        onItemSelected = { index ->
                            AppearancePalette.entries.getOrNull(index)?.let(
                                viewModel::applyAppearancePalette,
                            )
                        },
                    )
                }
            }
        }

        /* ---------------- 底栏 ---------------- */
        item { SectionHeader("底栏") }

        item {
            SettingsCard {
                SettingDropdownItem(
                    title = "底栏样式",
                    leadingIcon = Icons.Rounded.ViewCarousel,
                    subtitle = viewModel.bottomBarStyle.description,
                    items = BottomBarStyle.entries.map { it.label },
                    selectedIndex = BottomBarStyle.entries.indexOf(viewModel.bottomBarStyle),
                    onItemSelected = { index ->
                        BottomBarStyle.entries.getOrNull(index)?.let(
                            viewModel::applyBottomBarStyle,
                        )
                    },
                )
            }
        }

        item {
            SectionHint(
                "「标准」是贴着屏幕底边的整条底栏；" +
                    "「悬浮」是浮起来的胶囊，选中那一项会展开成文字。",
            )
        }

        /* ---------------- 预览 ---------------- */
        item { SectionHeader("预览") }

        item {
            SettingsCard {
                /*
                 * ============================================================
                 * ⚠️⚠️ 为什么这一页必须有预览
                 * ============================================================
                 * 底栏**只在三个一级页面上显示**（`showBottomBar` 判的是 `MAIN` 路由），
                 * 而主题设置是个二级页面 —— 也就是说改完「底栏样式」，
                 * 屏幕上**什么都看不到**，得返回才看得到效果。
                 *
                 * ⚠️ "点了没反应"是用户最讨厌的形态，所以这里给它一块
                 * **真实的底栏渲染**（不是示意图，就是同一个组件）。
                 * 点预览里的项也能切换高亮，不用来回跳。
                 */
                ThemePreview(
                    style = viewModel.bottomBarStyle,
                )
            }
        }

        /* ---------------- 预览说明 ---------------- */
        item {
            Text(
                text = "上面的预览可以点。改完立刻生效，不用重启。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * 主色选择器：一排色块 + 一个「跟随壁纸」。
 *
 * ============================================================
 * ⚠️ 为什么是一排预设色，而不是 HSV 取色盘
 * ============================================================
 * 取色盘要自己画色相/饱和度面板 + 拖动手势，代码量不小，
 * 而且在小屏上很难点准。先给一排**经过挑选的颜色**：
 * 每种都能拉开明显区别，用户一下就能看出效果。
 *
 * ⚠️ 将来要加取色盘的话，这里换成 `HsvColorPicker` 即可，
 * 对外接口（`onSelect(Int)`）不用变。
 *
 * ⚠️ 选中态用**外圈描边**而不是内部打勾:
 * 色块本身就小，打勾会盖住颜色；外圈还能保证浅色（白/黄）上看得见。
 */
@Composable
private fun SeedColorRow(
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    /*
     * ⚠️ 预设色是**不透明**的 ARGB。0 专门留给「跟随壁纸」，
     * 所以这里任何一个都不能是 0（透明黑）。
     */
    val presets = remember {
        listOf(
            0xFF6750A4.toInt(), // 紫（Material 3 默认）
            0xFF1F6FEB.toInt(), // 蓝
            0xFF00897B.toInt(), // 青绿
            0xFF2E7D32.toInt(), // 绿
            0xFF9A6A00.toInt(), // 琥珀
            0xFFD84315.toInt(), // 橙红
            0xFFC2185B.toInt(), // 玫红
            0xFF6D4C41.toInt(), // 棕
            0xFF546E7A.toInt(), // 蓝灰
        )
    }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            presets.forEach { argb ->
                SeedSwatch(
                    argb = argb,
                    selected = selected == argb,
                    onClick = { onSelect(argb) },
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        /*
         * 「跟随壁纸」= 清掉自定义主色（写回 0）。
         *
         * ⚠️ 用真正的 `SettingItem`（带圆角矩形的行）而不是裸 `Row`:
         * 它和上面那排色块在同一个卡片里，裸 Row 会显得"没做完"。
         */
        SettingItem(
            title = "跟随壁纸",
            leadingIcon = Icons.Rounded.Wallpaper,
            subtitle = if (selected == 0) "当前使用" else "点了之后不再使用上面的颜色",
            onClick = { onSelect(0) },
            trailing = {
                if (selected == 0) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "已选中",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            },
        )
    }
}

/** 一个可选的颜色块 */
@Composable
private fun SeedSwatch(
    argb: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val color = Color(argb)
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            /*
             * ⚠️ 描边用 `border` 画在**色块之上**:
             * 用 `background(color, CircleShape)` + `border` 的话，
             * 浅色块的边缘会和白色背景糊在一起看不出边界。
             */
            .background(color, CircleShape)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "已选中",
                /*
                 * ⚠️ 图标颜色按色块深浅自动选黑或白:
                 * 固定白色的话，选到黄色就看不见了。
                 */
                tint = if (color.luminance() > 0.5f) Color.Black else Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(18.dp),
            )
        }
    }
}

/**
 * 底栏预览。
 *
 * ⚠️ 直接复用**真正的底栏组件**（`MaterialFloatingBottomBar` / `NavigationBar`），
 * 而不是画一个示意图 —— 示意图迟早会和真实观感脱节，
 * 用户照着预览调完发现不一样，那比没有预览更糟。
 *
 * ⚠️ 悬浮样式要传 `applyWindowInsets = false`:卡片里已经有内边距，
 * 再加一次导航栏高度会多出一大块空白。
 */
@Composable
private fun ThemePreview(style: BottomBarStyle) {
    /*
     * 预览自己的选中项。点它切换高亮 —— 只是给它点反馈，
     * 与真正的一级页面页码无关（那个是 `pagerState`）。
     */
    var previewIndex by remember { mutableIntStateOf(0) }
    val tabs = previewTabs()

    when (style) {
        BottomBarStyle.FLOATING -> MaterialFloatingBottomBar(
            tabs = tabs,
            selectedIndex = previewIndex,
            onSelect = { previewIndex = it },
            applyWindowInsets = false,
        )

        BottomBarStyle.STANDARD -> NavigationBar(
            /*
             * ⚠️ 卡片里会自带圆角裁切，所以这里给个顶部内边距把它们隔开，
             * 不然底栏的上边缘会贴着卡片边，看着像被截断。
             */
            modifier = Modifier.padding(top = 8.dp),
        ) {
            tabs.forEachIndexed { index, tab ->
                NavigationBarItem(
                    selected = previewIndex == index,
                    onClick = { previewIndex = index },
                    icon = {
                        Icon(
                            imageVector = if (previewIndex == index) {
                                tab.selectedIcon
                            } else {
                                tab.unselectedIcon
                            },
                            contentDescription = tab.label,
                        )
                    },
                    label = { Text(tab.label) },
                )
            }
        }
    }
}

/**
 * 预览用的三个标签。
 *
 * ⚠️ 与一级页面的标签**保持一致**（主页 / 配置 / 设置）—— 用别的文字预览
 * 就看不出"三项会不会挤在一起"。
 */
private fun previewTabs(): List<FloatingTab> = listOf(
    FloatingTab("主页", Icons.Filled.Home, Icons.Outlined.Home),
    FloatingTab("配置", Icons.Filled.Tune, Icons.Outlined.Tune),
    FloatingTab("设置", Icons.Filled.Settings, Icons.Outlined.Settings),
)

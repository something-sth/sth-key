package com.something.sthkey.ui.feature.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness1
import androidx.compose.material.icons.filled.Brightness3
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.Brightness7
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.ViewCarousel
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.something.sthkey.domain.ui.AppearanceColorMode
import com.something.sthkey.domain.ui.AppearancePalette
import com.something.sthkey.domain.ui.BottomBarStyle
import com.something.sthkey.domain.ui.ColorSpecOption
import com.something.sthkey.ui.MainViewModel
import com.something.sthkey.ui.component.ColorPickerDialog
import com.something.sthkey.ui.component.FloatingTab
import com.something.sthkey.ui.component.MaterialFloatingBottomBar
import com.something.sthkey.ui.component.ScrollableScreen
import com.something.sthkey.ui.component.SectionHeader
import com.something.sthkey.ui.component.SectionHint
import com.something.sthkey.ui.component.SettingDropdownItem
import com.something.sthkey.ui.component.SettingsCard
import com.something.sthkey.ui.theme.rememberEffectiveSeed
import com.something.sthkey.ui.theme.rememberSeededColorScheme
import com.something.sthkey.ui.theme.toSpecVersion

/**
 * **主题设置** —— 从「设置 → 外观」点进来。
 *
 * ============================================================
 * 2026-11-21：照 SukiSU 重做（不含 miuix / 液态玻璃）
 * ============================================================
 * | 区块 | 做法 |
 * |---|---|
 * | 预览卡 | 按屏幕比例画一个"手机壳"，里面用**当前配色**画顶栏 / 主色块 / 信息卡 / 悬浮底栏 |
 * | 主色 | 一排 72dp 色块：跟随壁纸 + 预设 + 自定义（点开我们自己的取色盘）。每块按**自己的种子**实时算配色，画成上下两个半圆 |
 * | 明暗 | 4 个连体按钮：跟随系统 / 浅色 / 深色 / 纯黑（AMOLED） |
 * | 风格 / 规格 | 两个下拉（9 种风格、2021/2025 规格） |
 * | 底栏 | 沿用原来的「标准 / 悬浮」下拉 + 真组件预览 |
 *
 * ⚠️ 所有选项**点完立即生效**（写偏好 + 改状态量），没有"保存"按钮。
 * ⚠️ 「跟随壁纸」现在是**主色 = 0** 这一种状态，不再是独立的明暗档；
 *    所以色彩风格与规格对它同样生效（SukiSU 的行为）。
 */
@Composable
fun ThemeScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = viewModel.appearanceColorMode.isDark(systemDark)

    var showCustomColor by remember { mutableStateOf(false) }

    ScrollableScreen(
        title = "主题设置",
        subtitle = "配色与底栏",
        onBack = onBack,
        modifier = modifier,
    ) {
        /* ---------------- 预览 ---------------- */
        item {
            ThemePreviewCard(
                colorMode = viewModel.appearanceColorMode,
                seedColor = viewModel.seedColor,
                palette = viewModel.appearancePalette,
                colorSpec = viewModel.appearanceColorSpec,
            )
        }

        /* ---------------- 主色 ---------------- */
        item { SectionHeader("主色") }

        item {
            SeedColorRow(
                selected = viewModel.seedColor,
                dark = dark,
                amoled = viewModel.appearanceColorMode.isBlack,
                palette = viewModel.appearancePalette,
                colorSpec = viewModel.appearanceColorSpec,
                onSelect = viewModel::applySeedColor,
                onCustom = { showCustomColor = true },
            )
        }

        item {
            SectionHint(
                "「跟随壁纸」需要 Android 12 及以上；低版本会用一个固定的品牌色。" +
                    "选具体颜色则整套界面按 Material 3 的算法从它推导。",
            )
        }

        /* ---------------- 明暗 ---------------- */
        item { SectionHeader("明暗") }

        item {
            SettingsCard {
                ColorModeSelector(
                    selected = viewModel.appearanceColorMode,
                    onSelect = viewModel::applyAppearanceColorMode,
                )
            }
        }

        /* ---------------- 风格 / 规格 ---------------- */
        item { SectionHeader("配色规则") }

        item {
            SettingsCard {
                SettingDropdownItem(
                    title = "色彩风格",
                    leadingIcon = Icons.Rounded.Style,
                    subtitle = viewModel.appearancePalette.description,
                    items = AppearancePalette.entries.map { it.label },
                    selectedIndex = AppearancePalette.entries.indexOf(viewModel.appearancePalette),
                    onItemSelected = { index ->
                        AppearancePalette.entries.getOrNull(index)?.let(
                            viewModel::applyAppearancePalette,
                        )
                    },
                )
                SettingDropdownItem(
                    title = "色彩规格",
                    leadingIcon = Icons.Rounded.Colorize,
                    subtitle = viewModel.appearanceColorSpec.description,
                    items = ColorSpecOption.entries.map { it.label },
                    selectedIndex = ColorSpecOption.entries.indexOf(viewModel.appearanceColorSpec),
                    onItemSelected = { index ->
                        ColorSpecOption.entries.getOrNull(index)?.let(
                            viewModel::applyAppearanceColorSpec,
                        )
                    },
                )
            }
        }

        item {
            SectionHint(
                "规格 2025 是 Material 3 Expressive 的规则；" +
                    "但只有「柔和 / 素净 / 鲜艳 / 表现力」支持它，" +
                    "其余风格会自动按 2021 推导。",
            )
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
            SettingsCard {
                /*
                 * ⚠️ 底栏只在三个一级页面显示，而这是二级页 ——
                 * 改完如果不预览，屏幕上**什么都看不到**。所以这里放**真组件**，
                 * 不是示意图（示意图迟早和真实观感脱节）。
                 */
                ThemeBottomBarPreview(style = viewModel.bottomBarStyle)
            }
        }

        item {
            Text(
                text = "上面的底栏预览可以点。改完立刻生效，不用重启。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
    }

    if (showCustomColor) {
        /*
         * 自定义主色 = 我们自己的取色盘（色环 + 方板 + H/S/V）。
         * ⚠️ 主色必须**不透明**：`0` 要留给"跟随壁纸"。
         */
        ColorPickerDialog(
            title = "自定义主色",
            initialRgb = (viewModel.seedColor.takeIf { it != 0 } ?: 0xFF6750A4.toInt())
                .toLong() and 0xFFFFFF,
            alphaPercent = 100,
            alphaEditable = false,
            onDismiss = { showCustomColor = false },
            onConfirm = { rgb, _ ->
                showCustomColor = false
                viewModel.applySeedColor(0xFF000000.toInt() or rgb.toInt())
            },
        )
    }
}

// ---------------------------------------------------------------- 预览卡

/**
 * "手机壳"预览：用**当前配色**画应用的样子。
 *
 * ⚠️ 这是整页最值钱的东西 —— 用户改任何一项，这里立刻变，
 * 不用返回主页去看。比例跟真实屏幕走，所以横屏/平板也不会变形。
 */
@Composable
private fun ThemePreviewCard(
    colorMode: AppearanceColorMode,
    seedColor: Int,
    palette: AppearancePalette,
    colorSpec: ColorSpecOption,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = colorMode.isDark(systemDark)
    val seed = rememberEffectiveSeed(seedColor, dark)
    val scheme = rememberSeededColorScheme(
        seedArgb = seed,
        isDark = dark,
        palette = palette,
        spec = colorSpec.toSpecVersion(),
        isAmoled = colorMode.isBlack,
    )

    val configuration = LocalConfiguration.current
    val ratio = configuration.screenWidthDp.toFloat() /
        configuration.screenHeightDp.toFloat().coerceAtLeast(1f)

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.46f)
                .aspectRatio(ratio),
            shape = RoundedCornerShape(20.dp),
            color = scheme.surfaceContainer,
            border = androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color = scheme.outlineVariant,
            ),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                /* 顶栏 */
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = "sth key",
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                /* 主色块 */
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(scheme.primary),
                )

                /* 信息卡 */
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(scheme.surfaceBright),
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(0.7f)
                                .height(8.dp)
                                .clip(CircleShape)
                                .background(scheme.onSurfaceVariant.copy(alpha = 0.35f)),
                        )
                        Box(
                            Modifier
                                .fillMaxWidth(0.45f)
                                .height(8.dp)
                                .clip(CircleShape)
                                .background(scheme.onSurfaceVariant.copy(alpha = 0.2f)),
                        )
                    }
                }

                /* 悬浮胶囊底栏 */
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .height(24.dp)
                        .clip(CircleShape)
                        .background(scheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(width = 22.dp, height = 14.dp)
                                .clip(CircleShape)
                                .background(scheme.primary),
                        )
                        repeat(3) {
                            Box(
                                Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(scheme.onSurfaceVariant.copy(alpha = 0.5f)),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 主色块

/**
 * 一排主色块。
 *
 * ⚠️ 每一块都用**自己的种子**实时算一套配色，再画成"上半 primaryContainer /
 *    下半 tertiaryContainer"的两个半圆 —— 这样一列看过去就是"每种颜色长什么样"，
 *    比纯色点信息量大得多（SukiSU 也是这么做的）。
 */
@Composable
private fun SeedColorRow(
    selected: Int,
    dark: Boolean,
    amoled: Boolean,
    palette: AppearancePalette,
    colorSpec: ColorSpecOption,
    onSelect: (Int) -> Unit,
    onCustom: () -> Unit,
) {
    /*
     * ⚠️ 预设色必须**不透明**：0 专门留给"跟随壁纸"。
     * 取色顺序按色相排，相邻两块差异明显。
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

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SeedTile(
                    label = "壁纸",
                    seed = 0,
                    selected = selected == 0,
                    dark = dark,
                    amoled = amoled,
                    palette = palette,
                    colorSpec = colorSpec,
                    onClick = { onSelect(0) },
                    icon = Icons.Rounded.Wallpaper,
                )
            }

            items(presets) { argb ->
                SeedTile(
                    label = null,
                    seed = argb,
                    selected = selected == argb,
                    dark = dark,
                    amoled = amoled,
                    palette = palette,
                    colorSpec = colorSpec,
                    onClick = { onSelect(argb) },
                )
            }

            item {
                CustomSeedTile(onClick = onCustom)
            }
        }
    }
}

/**
 * 一个 72dp 的主色块。
 *
 * @param seed `0` = 跟随壁纸（照样算一套配色，只是种子取系统的）
 */
@Composable
private fun SeedTile(
    label: String?,
    seed: Int,
    selected: Boolean,
    dark: Boolean,
    amoled: Boolean,
    palette: AppearancePalette,
    colorSpec: ColorSpecOption,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    val effectiveSeed = if (seed == 0) rememberEffectiveSeed(0, dark) else seed
    val scheme = rememberSeededColorScheme(
        seedArgb = effectiveSeed,
        isDark = dark,
        palette = palette,
        spec = colorSpec.toSpecVersion(),
        isAmoled = amoled,
    )

    val scale = if (selected) 1.08f else 1f

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(20.dp),
            color = scheme.surfaceContainer,
            modifier = Modifier
                .size(72.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
            border = androidx.compose.foundation.BorderStroke(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) scheme.primary else scheme.outlineVariant,
            ),
        ) {
            Box(contentAlignment = Alignment.Center) {
                /* 上半 primaryContainer、下半 tertiaryContainer 的圆 */
                Canvas(modifier = Modifier.size(46.dp)) {
                    rotate(degrees = 180f) {
                        drawArc(
                            color = scheme.primaryContainer,
                            startAngle = 0f,
                            sweepAngle = 180f,
                            useCenter = true,
                        )
                    }
                    drawArc(
                        color = scheme.tertiaryContainer,
                        startAngle = 0f,
                        sweepAngle = 180f,
                        useCenter = true,
                    )
                }

                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }

                if (selected) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "已选中",
                        tint = scheme.onPrimaryContainer,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .size(16.dp),
                    )
                }
            }
        }

        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** "自定义"块：点开取色盘 */
@Composable
private fun CustomSeedTile(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier.size(72.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Rounded.Colorize,
                contentDescription = "自定义主色",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------- 明暗四连

/** 4 个连体按钮：跟随系统 / 浅色 / 深色 / 纯黑 */
@Composable
private fun ColorModeSelector(
    selected: AppearanceColorMode,
    onSelect: (AppearanceColorMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            AppearanceColorMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = selected == mode,
                    onClick = { onSelect(mode) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = AppearanceColorMode.entries.size,
                    ),
                    /* 只有图标：4 段并排时文字会挤成两行（SukiSU 也是只放图标） */
                    icon = {},
                    label = {
                        Icon(
                            imageVector = mode.icon(),
                            contentDescription = mode.label,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                )
            }
        }
    }
}

private fun AppearanceColorMode.icon() = when (this) {
    AppearanceColorMode.SYSTEM -> Icons.Filled.Brightness4
    AppearanceColorMode.LIGHT -> Icons.Filled.Brightness7
    AppearanceColorMode.DARK -> Icons.Filled.Brightness3
    AppearanceColorMode.BLACK -> Icons.Filled.Brightness1
}

// ---------------------------------------------------------------- 底栏预览

/**
 * 底栏预览：直接复用**真正的底栏组件**。
 *
 * ⚠️ 悬浮样式要传 `applyWindowInsets = false`：卡片里已经有内边距，
 * 再加一次导航栏高度会多出一大块空白。
 */
@Composable
private fun ThemeBottomBarPreview(style: BottomBarStyle) {
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

private fun previewTabs(): List<FloatingTab> = listOf(
    FloatingTab("主页", Icons.Filled.Home, Icons.Outlined.Home),
    FloatingTab("配置", Icons.Filled.Tune, Icons.Outlined.Tune),
    FloatingTab("设置", Icons.Filled.Settings, Icons.Outlined.Settings),
)

package com.something.sthkey.ui.feature.custom

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.something.sthkey.domain.config.ANIMATION_DURATION_MAX
import com.something.sthkey.domain.config.ANIMATION_DURATION_MIN
import com.something.sthkey.domain.config.AnimationMode
import com.something.sthkey.domain.config.JoystickStyle
import com.something.sthkey.domain.config.KeyStrokesConfig
import com.something.sthkey.domain.config.ShadowMode
import com.something.sthkey.domain.custom.ComponentCategory
import com.something.sthkey.domain.custom.ComponentStyle
import com.something.sthkey.domain.custom.ComponentType
import com.something.sthkey.domain.custom.JoystickComponent
import com.something.sthkey.domain.custom.StickSide
import com.something.sthkey.domain.custom.TextualComponent
import com.something.sthkey.domain.custom.summary
import com.something.sthkey.domain.custom.typeLabel
import com.something.sthkey.domain.custom.CustomComponent
import com.something.sthkey.domain.custom.CustomLayout
import com.something.sthkey.domain.custom.CustomLayoutSettings
import com.something.sthkey.domain.custom.resizedToSide
import com.something.sthkey.domain.custom.KeyComponent
import com.something.sthkey.domain.custom.SUGGESTED_KEY_CODES
import com.something.sthkey.domain.custom.TextComponent
import com.something.sthkey.domain.custom.centeredOnCanvas
import com.something.sthkey.domain.custom.clampedToCanvas
import com.something.sthkey.domain.custom.movedTo
import com.something.sthkey.domain.custom.primaryText
import com.something.sthkey.domain.custom.resizedTo
import com.something.sthkey.domain.custom.withPrimaryText
import com.something.sthkey.domain.custom.withStyle
import com.something.sthkey.ui.component.EditableSliderRow
import com.something.sthkey.domain.custom.withTextOffset
import com.something.sthkey.domain.custom.withTextScale
import com.something.sthkey.domain.font.FontRegistry
import com.something.sthkey.domain.keys.KeyCodes
import com.something.sthkey.ui.component.HexColorRow
import kotlin.math.round

/**
 * 添加组件的类型选择弹窗（与"新建配置"选样式同一套做法）。
 *
 * 用卡片而不是下拉菜单：类型只有两种，但**每种都需要一句说明** ——
 * "按键"与"文本"的区别不是名字能讲清的（文本也能显示 CPS）。
 */
@Composable
fun AddComponentDialog(
    onDismiss: () -> Unit,
    onPick: (ComponentType) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加组件") },
        text = {
            /*
             * ============================================================
             * 按**栏**分组，不是把四种类型平铺一行
             * ============================================================
             * 用户的原话:"原本的 key 组件与文本组件分到'通用'栏，然后再分出
             * 键盘专栏与手柄专栏组件，键盘专栏还没想好放什么，先写个无，
             * 留个占位，手柄专栏就放个摇杆组件"。
             *
             * ⚠️ 平铺的问题不只是"以后更挤":用户看不出"这个组件属于哪一类"。
             * 分栏之后，"想找手柄相关的东西 → 看手柄那一栏"是直觉的。
             *
             * ⚠️ 空栏**照样显示**（键盘那一栏现在是空的）—— 那是刻意的占位，
             * 告诉用户"这里以后会放东西"，而不是让人以为键盘只有按键和文本。
             */
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ComponentCategory.entries.forEach { category ->
                    CategoryRow(
                        category = category,
                        types = ComponentType.inCategory(category),
                        onPick = onPick,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 弹窗里的一栏:标题 + 该栏的组件卡片（空栏给一行置灰占位） */
@Composable
private fun CategoryRow(
    category: ComponentCategory,
    types: List<ComponentType>,
    onPick: (ComponentType) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = category.label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(4.dp))

        if (types.isEmpty()) {
            /*
             * ⚠️ 置灰的占位行。文案说明"暂无"而不是留一块空白 ——
             * 空白会让人以为是界面出错了。
             */
            Text(
                text = "暂无可选组件",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                types.forEach { type ->
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onPick(type) },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(text = type.label, style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = type.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

/*
 * 新建 / 复制组件的**数据规则**在 `domain/custom/ComponentFactory.kt`：
 * 它们不碰 Compose、全是纯计算，放在那里才能单元测试 ——
 * 而"复制出来的东西和原件联动""复制了却看不见"这类问题恰恰必须有测试钉住。
 */

/*
 * ============================================================
 * 属性面板的分组
 * ============================================================
 * 早先是一长条"位置 / 尺寸 / 外观 / 文字 / 描边 / 圆角 / CPS / 按下时 /
 * 按下动画 / 监听按键"全部摊开，接近 20 个控件排成一列 ——
 * 在一屏上根本看不完，而且**大部分当前用不到**（文本组件那一半是灰的、
 * 没开描边时"描边粗细"是多余的）。
 *
 * 现在按"改的时候一起改的东西"分组，每组可折叠，默认只展开最常用的几组。
 * 分组的依据是**用户的心智**，不是数据结构：
 * 摆位置、调大小、改样子、写内容、按下什么样。
 */
private enum class PanelSection(val title: String, val defaultExpanded: Boolean) {
    /**
     * 内容：这个组件显示什么字、认识哪些键。
     *
     * 排在最前面，因为**它决定了这个组件能干什么** ——
     * 文字里写了 `(cps)` 才有 CPS，绑了键才会亮。
     */
    CONTENT("内容", true),

    /** 位置与尺寸：摆在哪里、多大 */
    GEOMETRY("位置与尺寸", true),

    /**
     * 外观：文字缩放与偏移。
     *
     * ⚠️ 颜色、透明度、描边、阴影**都不在这一组**（见下面三个分区）。
     * 早先它们全挤在"外观"与"描边与圆角"两组里，
     * 一个组里塞二十来个控件，找一项要滚很久 ——
     * 这正是需求里说的"很不容易找到想要的配置"。
     *
     * 现在按**元素**与**属性种类**重新分组，与 Key 样式的编辑器完全对称：
     * 颜色 → 颜色、透明度 → 透明度、形状 → 外观。
     */
    LOOK("外观", true),

    /** 颜色：键帽 / 文字 / 描边 / 阴影，每项分未按下与按下 */
    COLORS("颜色", false),

    /** 透明度：与颜色同样的四个元素、各两种状态 */
    OPACITY("透明度", false),

    /** 动画：只有按键组件有 */
    MOTION("按下动画", false),

    /*
     * ============================================================
     * 摇杆专属：**也按"属性种类"分组**，不按"零件"分组
     * ============================================================
     * 用户的原话:"标准样式里那么做是为了区分，自定义是单独配置的，
     * 应该把圆角，透明度，描边这些分出来，而不是把摇杆，摇杆帽分出来，
     * 标题应该是圆角，透明度，颜色这些"。
     *
     * ⚠️ 两边的分组逻辑**不同是有理由的**:
     *
     * | 场景 | 分组依据 | 为什么 |
     * |---|---|---|
     * | 「标准」样式的设置页 | 按**零件**（摇杆 / 摇杆帽 / 手感） | 那里的摇杆是一个整体，用户想的是"我要调那个帽子" |
     * | **自定义编辑页** | 按**属性种类** | 这里每个属性都是**逐个配**的，与按键/文本组件同一套分组 —— 换个组件不用重新找 |
     *
     * 所以摇杆在这边**复用**已有的「外观 / 颜色 / 透明度」三组,
     * 只多一个「摇杆手感」（那是它独有、无处可放的项）。
     */
    JOYSTICK_FEEL("摇杆手感", false),
    ;

    companion object {
        /** 默认展开的分组；换组件时按它重置，避免"上一个展开的组"状态串台 */
        val defaults: Set<PanelSection> = entries.filter { it.defaultExpanded }.toSet()
    }
}

/**
 * 组件属性面板。
 *
 * ============================================================
 * 定位全靠滑块（画布上没有拖动）
 * ============================================================
 * 画布只负责"看"，改动都在这里。滑块范围是**确定**的：画布固定
 * [CustomLayout.BASE_CANVAS]，所以 X 取 `−宽度 … 画布边长`
 * （允许负值是为了让组件能贴到画布外沿一点点）。同时显示百分比，
 * 让用户对"在画布的哪个位置"有直观感受。
 *
 * ============================================================
 * 连续改动 vs "一步"改动
 * ============================================================
 * 滑块拖动会产生几十次改动，必须算**一步**撤回（起点 [onBeginContinuous]、
 * 抬手 [onEndContinuous]）。开关、选色、按钮是原子操作，直接当一步。
 *
 * ============================================================
 * ⚠️ 文本输入框必须有**本地状态**（这里踩过一个很严重的坑）
 * ============================================================
 * 上一版这里是 `value = component.primaryText()`，
 * 也就是"输入框的值直接来自草稿"。后果是：每敲一个字符都要走
 * `草稿 → 重组 → 新值` 这一圈，而且**每一个字符都 commit 一步撤回**。
 * 表现就是"输入框打不进字、退格像在删别的东西" —— 用户看到的"整个界面是死的"。
 *
 * 现在按 [HexColorRow] 的做法：本地 `remember` 一份文本，
 * 外部值变化时用 [LaunchedEffect] 同步回来，输入过程**只写本地状态**，
 * 每次改动仍然照实写进草稿（这样画布是实时的），但 UI 不再依赖那一圈往返。
 */
@Composable
fun PropertyPanel(
    component: CustomComponent?,
    /** 全部组件；用来算悬浮窗的真实尺寸（包围盒）*/
    components: List<CustomComponent>,
    modifier: Modifier = Modifier,
    onStyleChange: (ComponentStyle, Boolean) -> Unit,
    onComponentChange: (CustomComponent, Boolean) -> Unit,
    onBeginContinuous: () -> Unit,
    onEndContinuous: () -> Unit,
    onPickFont: () -> Unit,
    onPickBitmapFont: () -> Unit,
    onPickKeys: (KeyComponent) -> Unit,
    onPickCpsKeys: (TextComponent, Int) -> Unit,
    /**
     * 「摇杆-键盘」的**四个键位**里，用户点了第 [Int] 个（0=上 / 1=左 / 2=下 / 3=右）。
     *
     * ⚠️ 带回**下标**是必需的 —— 只传组件的话，点「下」也会改到「上」，
     * 前面配好的就全丢了（与 `onPickCpsKeys` 必须带"第几个占位符"同一个理由）。
     */
    onPickJoystickKey: (JoystickComponent, Int) -> Unit,
) {
    if (component == null) {
        Column(
            modifier = modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "没有选中组件",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "用上面那一排选中一个组件",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    /*
     * 悬浮窗尺寸 = 所有组件的包围盒（`CustomLayout.bounds`），与画布上画的
     * 定位区**不是一回事**。这里算好文案传下去，让面板把两个数都写出来 ——
     * 只说"画布 600×600"的话，用户会以为窗口也占那么大。
     */
    val bounds = CustomLayout.bounds(components)
    val windowSize = if (bounds.visible) {
        "${bounds.width.toInt()} × ${bounds.height.toInt()}"
    } else {
        "不会显示（没有组件）"
    }

    /*
     * 展开状态按**组件 id** 重置：换一个组件时回到默认展开的那几组。
     *
     * 不重置的话，上一个组件展开的是"按下动画"、选到文本组件之后
     * 那个分组不存在，面板会看起来"空了一截"。
     */
    var expanded by remember(component.id) { mutableStateOf(PanelSection.defaults) }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp),
    ) {
        ComponentHeader(component)

        PanelGroup(
            section = PanelSection.CONTENT,
            expanded = PanelSection.CONTENT in expanded,
            onToggle = { expanded = expanded.toggle(PanelSection.CONTENT) },
        ) {
            /*
             * ⚠️ 按组件类型分派 —— 摇杆的"内容"只有一个选项
             * （监听左摇杆还是右摇杆），与按键/文本完全不是一回事。
             *
             * 用户的原话:"'内容'栏的配置只保留一个分段按钮用来选择监听的是
             * 左摇杆还是右摇杆"。
             */
            when (component) {
                is JoystickComponent -> JoystickContentSection(
                    component = component,
                    onComponentChange = onComponentChange,
                    /*
                     * ⚠️ 键位映射在「内容」栏（用户纠正过:我原来放在了
                     * 「摇杆手感」栏），所以这个回调也从这里往下传。
                     */
                    onPickJoystickKey = onPickJoystickKey,
                )

                is KeyComponent, is TextComponent -> ContentSection(
                    /*
                     * `is KeyComponent, is TextComponent` 这个分支会让 Kotlin 把
                     * `component` 收窄成 `KeyComponent | TextComponent` 的**交集类型**,
                     * 它不自动算作 [TextualComponent]。所以要显式转一次。
                     */
                    component = component as TextualComponent,
                    onComponentChange = onComponentChange,
                    onBeginContinuous = onBeginContinuous,
                    onEndContinuous = onEndContinuous,
                    onPickKeys = onPickKeys,
                    onPickCpsKeys = onPickCpsKeys,
                )
            }
        }

        /*
         * ============================================================
         * 位置与尺寸（三种组件共用）
         * ============================================================
         * ⚠️ X / Y / 宽 / 高 对三种组件含义完全一样，所以是**同一个组**，
         * 不按类型分叉。摇杆额外多两项（摇杆缩放 / 摇杆帽缩放）——
         * 用户的原话:"摇杆缩放与摇杆帽缩放都整合在'位置与尺寸'里"。
         *
         * ⚠️ 位置是**逐组件配**的（自定义样式的本意），所以摇杆也必须能调 X/Y ——
         * 早先只给了"居中"按钮加宽高，位置只能靠居中，那是漏做的。
         */
        PanelGroup(
            section = PanelSection.GEOMETRY,
            expanded = PanelSection.GEOMETRY in expanded,
            onToggle = { expanded = expanded.toggle(PanelSection.GEOMETRY) },
        ) {
            GeometrySection(
                component = component,
                windowSize = windowSize,
                onComponentChange = onComponentChange,
                onBeginContinuous = onBeginContinuous,
                onEndContinuous = onEndContinuous,
            )

            /* 摇杆的两个"缩放"属于尺寸，所以也在这里 */
            if (component is JoystickComponent) {
                JoystickScaleSliders(
                    component = component,
                    onComponentChange = onComponentChange,
                )
            }
        }

        /*
         * ============================================================
         * 外观 / 颜色 / 透明度 —— **按属性种类分组**，三种组件各自填内容
         * ============================================================
         * 用户的原话:"自定义是单独配置的，应该把圆角，透明度，描边这些分出来，
         * 而不是把摇杆，摇杆帽分出来，标题应该是圆角，透明度，颜色这些"。
         *
         * ⚠️ 所以组的**标题与顺序**对三种组件完全相同，变的只是每组里的控件 ——
         * 用户换一个组件时不需要重新找"圆角在哪一组"。
         */
        PanelGroup(
            section = PanelSection.LOOK,
            expanded = PanelSection.LOOK in expanded,
            onToggle = { expanded = expanded.toggle(PanelSection.LOOK) },
        ) {
            when (component) {
                is JoystickComponent -> JoystickLookSection(
                    component = component,
                    onComponentChange = onComponentChange,
                )

                is KeyComponent, is TextComponent -> LookSection(
                    component = component as TextualComponent,
                    onComponentChange = onComponentChange,
                    onBeginContinuous = onBeginContinuous,
                    onEndContinuous = onEndContinuous,
                    onStyleChange = onStyleChange,
                    onPickFont = onPickFont,
                    onPickBitmapFont = onPickBitmapFont,
                )
            }
        }

        PanelGroup(
            section = PanelSection.COLORS,
            expanded = PanelSection.COLORS in expanded,
            onToggle = { expanded = expanded.toggle(PanelSection.COLORS) },
        ) {
            when (component) {
                is JoystickComponent -> JoystickColorSection(
                    component = component,
                    onComponentChange = onComponentChange,
                )

                is KeyComponent, is TextComponent -> ColorSection(
                    component = component as TextualComponent,
                    onStyleChange = onStyleChange,
                )
            }
        }

        PanelGroup(
            section = PanelSection.OPACITY,
            expanded = PanelSection.OPACITY in expanded,
            onToggle = { expanded = expanded.toggle(PanelSection.OPACITY) },
        ) {
            when (component) {
                is JoystickComponent -> JoystickOpacitySection(
                    component = component,
                    onComponentChange = onComponentChange,
                )

                is KeyComponent, is TextComponent -> OpacitySection(
                    component = component as TextualComponent,
                    onStyleChange = onStyleChange,
                    onBeginContinuous = onBeginContinuous,
                    onEndContinuous = onEndContinuous,
                )
            }
        }

        /*
         * 「摇杆手感」是摇杆**独有**的一组 —— 死区/灵敏度/平滑没有别处可放。
         * 它排在最后（那是"调完样子之后再调手感"的顺序）。
         */
        if (component is JoystickComponent) {
            PanelGroup(
                section = PanelSection.JOYSTICK_FEEL,
                expanded = PanelSection.JOYSTICK_FEEL in expanded,
                onToggle = { expanded = expanded.toggle(PanelSection.JOYSTICK_FEEL) },
            ) {
                JoystickFeelSection(
                    component = component,
                    onComponentChange = onComponentChange,
                    onPickJoystickKey = onPickJoystickKey,
                )
            }
        }

        /* 动画只有按键组件才有 —— 文本/摇杆连标题都不显示 */
        if (component is KeyComponent) {
            PanelGroup(
                section = PanelSection.MOTION,
                expanded = PanelSection.MOTION in expanded,
                onToggle = { expanded = expanded.toggle(PanelSection.MOTION) },
            ) {
                MotionSection(
                    component = component,
                    onComponentChange = onComponentChange,
                    onBeginContinuous = onBeginContinuous,
                    onEndContinuous = onEndContinuous,
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

/* ============================================================
 * 标题与分组容器
 * ============================================================ */

/**
 * 面板顶部那一条：这是哪个组件。
 *
 * 不是装饰：侧栏模式下属性面板离组件条很远，**必须有个地方告诉你
 * 现在改的是哪一个**，否则调半天发现改错了组件是很常见的。
 */
@Composable
private fun ComponentHeader(component: CustomComponent) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            /*
             * ⚠️ 类型名走 [typeLabel]，**不要**写成
             * `if (component is KeyComponent) "按键" else "文本"`。
             *
             * 那种二元写法加上第三种组件时会把摇杆叫成"文本"，
             * 而这里正是"我在改的是哪一个组件"的答案 ——
             * 叫错名字会让人以为选错了组件。
             */
            text = component.typeLabel(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier
                .padding(end = 8.dp)
                .background(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.small,
                )
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        Text(
            /*
             * 摘要:按键是键面文字、文本是内容、**摇杆是"左摇杆/右摇杆"** ——
             * 走 [summary] 一处决定，别在这里再 `when` 一遍。
             */
            text = component.summary().ifBlank { "（空）" },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${component.width.toInt()}×${component.height.toInt()}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 一个可折叠的分组：标题点一下就展开/收起 */
@Composable
private fun PanelGroup(
    section: PanelSection,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = section.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
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

    if (expanded) {
        content()
        Spacer(modifier = Modifier.height(4.dp))
    }
}

/** 在展开集合里切换一个分组的开合 */
private fun Set<PanelSection>.toggle(section: PanelSection): Set<PanelSection> =
    if (section in this) this - section else this + section

/* ============================================================
 * 各分组的内容
 * ============================================================ */

/**
 * 内容：这个组件显示什么字、认识哪些键。
 *
 * 这里是 CPS 的入口 —— **文字里写占位符就显示 CPS**，不再有开关。
 * 所以下面那行提示不是装饰，它就是这个功能的说明书。
 */
@Composable
private fun ContentSection(
    component: TextualComponent,
    onComponentChange: (CustomComponent, Boolean) -> Unit,
    onBeginContinuous: () -> Unit,
    onEndContinuous: () -> Unit,
    onPickKeys: (KeyComponent) -> Unit,
    onPickCpsKeys: (TextComponent, Int) -> Unit,
) {
    val text = component.primaryText()
    val usesCps = CustomLayout.hasCpsPlaceholder(text)

    PrimaryTextField(
        component = component,
        onComponentChange = onComponentChange,
        onBeginEdit = onBeginContinuous,
        onEndEdit = onEndContinuous,
    )

    if (component is KeyComponent) {
        /*
         * 监听哪些键 —— **多选**，与 Key 样式的键位映射同一个交互：
         * 芯片列出已选的键，右边一个编辑按钮打开多选对话框。
         *
         * 多选是必需的而不是锦上添花：左右 Shift 是两个不同的键码，
         * 只绑一个的话另一边按下去不亮（Key 样式的键位映射就是这么做的）。
         */
        KeyCodesField(
            label = "监听的键",
            codes = component.inputKeyCodes,
            emptyHint = "未绑定（这个组件不会亮）",
            onEdit = { onPickKeys(component) },
        )

        /*
         * 按键组件的 CPS 来源就是上面那组键，没有单独的选项 ——
         * 所以这里只是一行说明，而不是第二个选择器。
         *
         * 给了按钮反而更糟：那个选择与 inputKeyCodes 是两个真源，
         * 用户改了一个忘了另一个，就会出现"按键亮的是 Q、CPS 数的是左键"。
         */
        HintText(
            if (usesCps) {
                "CPS 统计的就是上面这些键（它们共享计数，不会重复计算）"
            } else {
                "想显示 CPS 就把文字写成 LMB(cps2) —— 数字为 0 时整个括号消失"
            },
        )
    } else if (component is TextComponent) {
        /*
         * 文本组件才需要选"CPS 统计哪些键"。
         *
         * ⚠️ **只在文字里含占位符时才出现** —— 没有占位符时它不影响任何东西，
         * 摆在那里只会让人以为"这个选项坏了"。这就是需求里说的
         * "做生动一点"：控件跟着内容出现和消失。
         */
        if (usesCps) {
            /*
             * ============================================================
             * 一个占位符一条，各自选键位
             * ============================================================
             * 早先这里只有**一条**"CPS 统计的键" —— 一个组件里写几个
             * 占位符，它们全都显示同一个数值。现在按出现顺序逐个对应，
             * 于是 `LMB(cps) | RMB(cps)` 这种写法能分别数左右键。
             *
             * ⚠️ 光有列表还不够：文字里两个 `(cps)` 长得一模一样，
             * 用户看不出哪一条对应哪一处。所以**用颜色对上** ——
             * 文字预览里第 n 个占位符染成第 n 种颜色，
             * 下面第 n 条键位的标签也是同一种颜色。
             *
             * ⚠️ 只染色、**不改动占位符文本本身**：改文本会让"存下来的文字"
             * 与"用户输入的文字"不一致，而占位符是功能的一部分，
             * 不能变成带装饰的东西。
             */
            Text(
                text = buildMarkedCpsText(component.text),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            )
            HintText("上面用颜色标出了每个占位符的位置，下面按颜色一一对应")

            val placeholders = CustomLayout.cpsPlaceholders(component.text)
            placeholders.forEach { placeholder ->
                val codes = CustomLayout.cpsKeyCodesAt(component, placeholder.index)
                KeyCodesField(
                    label = "第 ${placeholder.index + 1} 个${if (placeholder.isMode1) "(cps2)" else "(cps)"} 统计的键",
                    codes = codes,
                    emptyHint = "未选择（这一处 CPS 会一直是 0）",
                    onEdit = { onPickCpsKeys(component, placeholder.index) },
                    labelColor = cpsMarkerColor(placeholder.index),
                )
            }

            HintText("数字为 0 时 (cps) 显示 0，(cps2) 会把括号一起隐藏（多选时相加）")
        } else {
            HintText("文字里写上 (cps) 就一直显示数字，(cps2) 则在为 0 时把括号一起隐藏")
        }
    }
}

/**
 * CPS 占位符的标记色。
 *
 * ============================================================
 * 为什么要有颜色标记
 * ============================================================
 * 一个组件里可以写多个 `(cps)`，而它们长得**一模一样**。
 * 属性面板里因此会有好几条"CPS 统计的键"，用户无法判断
 * 哪一条对应文字里的哪一处 —— 改错了要等看到数字才能发现。
 *
 * 给第 n 个占位符与其对应的那条键位用同一种颜色，一眼就能对上。
 *
 * ⚠️ 刻意**不用黄色与青色**：这两种在浅色主题的背景上几乎看不见，
 * 标了等于没标。选的六种在深浅两种主题下都够清楚。
 *
 * 超过六种就循环 —— 一个组件里有几十个 CPS 占位符属于极端情况，
 * 那时颜色重复也还能靠顺序判断。
 */
private val CPS_MARKER_COLORS = listOf(
    Color(0xFFE53935), // 红
    Color(0xFFFB8C00), // 橙
    Color(0xFF43A047), // 绿
    Color(0xFF1E88E5), // 蓝
    Color(0xFF8E24AA), // 紫
    Color(0xFFD81B60), // 品红
)

private fun cpsMarkerColor(index: Int): Color =
    CPS_MARKER_COLORS[index % CPS_MARKER_COLORS.size]

/**
 * 把文字里的 CPS 占位符按出现顺序染成不同的颜色。
 *
 * 返回 [AnnotatedString]，不改动任何字符 —— 只加颜色样式。
 * 这样"预览里看到的"与"存下来的文字"始终是同一串字符。
 */
private fun buildMarkedCpsText(text: String): AnnotatedString = buildAnnotatedString {
    val placeholders = CustomLayout.cpsPlaceholders(text)
    if (placeholders.isEmpty()) {
        append(text)
        return@buildAnnotatedString
    }

    var cursor = 0
    placeholders.forEach { placeholder ->
        // 占位符之前的普通文字
        if (placeholder.start > cursor) {
            append(text.substring(cursor, placeholder.start))
        }
        withStyle(
            SpanStyle(
                color = cpsMarkerColor(placeholder.index),
                fontWeight = FontWeight.Bold,
            ),
        ) {
            append(
                text.substring(
                    placeholder.start,
                    placeholder.start + placeholder.length,
                ),
            )
        }
        cursor = placeholder.start + placeholder.length
    }
    if (cursor < text.length) append(text.substring(cursor))
}

/**
 * "一组按键"的展示行：芯片 + 编辑按钮。
 *
 * 与 Key 样式键位映射那一行**长得一样、用起来也一样**（[com.something.sthkey.ui.component.KeyMappingEditor]
 * 的"绑定按键"）：已选的键用 [AssistChip] 流式排开，右边一个笔形按钮打开多选对话框。
 * 两边保持一致是有意的 —— 用户在配置页学会了一次，到这里不用再学一遍。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyCodesField(
    label: String,
    codes: List<Int>,
    emptyHint: String,
    onEdit: () -> Unit,
    /**
     * 标签颜色；非 null 时用它。
     *
     * 用来把"第几个 CPS 占位符"与"它的键位"用**同一种颜色**对上 ——
     * 一个组件里有两个 `(cps)` 时，光看文字分不出哪个对应哪一条。
     */
    labelColor: Color? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = labelColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))

            if (codes.isEmpty()) {
                Text(
                    text = emptyHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    codes.forEach { code ->
                        AssistChip(
                            onClick = onEdit,
                            label = {
                                Text(
                                    text = "${KeyCodes.displayName(code)} · $code",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                        )
                    }
                }
            }
        }

        IconButton(onClick = onEdit) {
            Icon(Icons.Default.Edit, contentDescription = "编辑$label")
        }
    }
}

/**
 * 位置与尺寸。定位全靠滑块，画布上不能拖。
 *
 * @param windowSize 悬浮窗的实际尺寸文案（由调用方按包围盒算好）。
 *   画布上画的是**定位区**（固定 600×600），窗口只覆盖内容包围盒 ——
 *   两个数不一样，所以必须把"窗口到底多大"明说出来，
 *   否则用户看到画布上大片空白会以为窗口也那么大。
 */
@Composable
private fun GeometrySection(
    /*
     * ⚠️ 形参是 **`CustomComponent`**，不是 `TextualComponent` ——
     * X / Y / 宽 / 高 对三种组件含义完全一样（摇杆也必须能调位置，
     * 这是用户点名要的），所以这一组三种组件**共用同一份实现**。
     */
    component: CustomComponent,
    windowSize: String,
    onComponentChange: (CustomComponent, Boolean) -> Unit,
    onBeginContinuous: () -> Unit,
    onEndContinuous: () -> Unit,
) {
    val canvas = CustomLayout.BASE_CANVAS

    OutlinedButton(
        onClick = { onComponentChange(component.centeredOnCanvas(), true) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Text("居中")
    }

    HintText("定位区 $canvas × $canvas（虚线框；X/Y 滑块的可选范围）")
    HintText("悬浮窗 $windowSize（实线框；屏幕上实际占这么大）")

    SliderRow(
        label = "X 轴",
        value = component.x,
        range = CustomLayout.minCoordinate()..CustomLayout.maxCoordinate(),
        display = positionText(component.x, canvas),
        onBegin = onBeginContinuous,
        onEnd = onEndContinuous,
        onChange = { value ->
            onComponentChange(component.movedTo(x = value, y = component.y), false)
        },
    )
    SliderRow(
        label = "Y 轴",
        value = component.y,
        range = CustomLayout.minCoordinate()..CustomLayout.maxCoordinate(),
        display = positionText(component.y, canvas),
        onBegin = onBeginContinuous,
        onEnd = onEndContinuous,
        onChange = { value ->
            onComponentChange(component.movedTo(x = component.x, y = value), false)
        },
    )
    /*
     * ============================================================
     * ⚠️ 摇杆是**边长**，不是宽 / 高
     * ============================================================
     * 用户的原话:"摇杆组件在自定义编辑中，应该是调'边长'，而不是像别的组件
     * 一样设计长、宽，摇杆本身就是圆角正方形，单调一个长或宽，显示都有 bug"。
     *
     * ⚠️ "调一个什么都不变"是必然的:摇杆的三层几何全部由**一个边长**推出，
     * 而渲染层取的是 `min(宽, 高)` —— 只把宽调大、高不动时，
     * `min` 还是原来那个值，于是滑块动了、画面纹丝不动。
     *
     * 所以对摇杆**不显示宽/高两个滑块**，只给一个「边长」。
     */
    if (component is JoystickComponent) {
        SliderRow(
            label = "边长",
            value = component.width,
            range = CustomLayout.COMPONENT_SIZE_MIN..CustomLayout.COMPONENT_SIZE_MAX,
            display = component.width.toInt().toString(),
            onBegin = onBeginContinuous,
            onEnd = onEndContinuous,
            onChange = { value ->
                onComponentChange(component.resizedToSide(value), false)
            },
        )
        return
    }

    SliderRow(
        label = "宽度",
        value = component.width,
        range = CustomLayout.COMPONENT_SIZE_MIN..CustomLayout.COMPONENT_SIZE_MAX,
        display = component.width.toInt().toString(),
        onBegin = onBeginContinuous,
        onEnd = onEndContinuous,
        onChange = { value ->
            onComponentChange(component.resizedTo(value, component.height), false)
        },
    )
    SliderRow(
        label = "高度",
        value = component.height,
        range = CustomLayout.COMPONENT_SIZE_MIN..CustomLayout.COMPONENT_SIZE_MAX,
        display = component.height.toInt().toString(),
        onBegin = onBeginContinuous,
        onEnd = onEndContinuous,
        onChange = { value ->
            onComponentChange(component.resizedTo(component.width, value), false)
        },
    )
}

/**
 * 外观：文字缩放与偏移、圆角、描边、文字阴影、字体。
 *
 * ============================================================
 * 为什么把"颜色与透明度"从这里搬走（这里换过一次分组）
 * ============================================================
 * 早先这一组装着颜色、透明度、缩放、偏移、字体，再加上一个
 * "描边与圆角"组 —— 一个组件二十来个控件全挤在两组里，
 * 要改某一项得滚很久，而且**颜色的未按下与按下被拆在两头**，
 * 想对比着调就得来回滚。这正是用户反馈的"很不容易找到配置"。
 *
 * 现在按**属性种类**拆开，与 Key 样式的编辑器完全对称：
 *
 * | 分区 | 内容 |
 * |---|---|
 * | 外观 | 文字缩放/偏移、圆角、描边、阴影、字体 |
 * | 颜色 | 键帽/文字/描边/阴影 × 未按下/按下 |
 * | 透明度 | 同上八项 |
 *
 * 于是"改颜色"和"改透明度"各自只在一处，元素顺序也一致 ——
 * 在两个分区之间对照时不需要重新找位置。
 */
@Composable
private fun LookSection(
    component: TextualComponent,
    onComponentChange: (CustomComponent, Boolean) -> Unit,
    onBeginContinuous: () -> Unit,
    onEndContinuous: () -> Unit,
    onStyleChange: (ComponentStyle, Boolean) -> Unit,
    onPickFont: () -> Unit,
    onPickBitmapFont: () -> Unit,
) {
    SubLabel("文字")

    SliderRow(
        label = "文字缩放",
        value = component.textScalePercent.toFloat(),
        range = CustomLayout.TEXT_SCALE_MIN.toFloat()..CustomLayout.TEXT_SCALE_MAX.toFloat(),
        display = "${component.textScalePercent}%",
        onBegin = onBeginContinuous,
        onEnd = onEndContinuous,
        onChange = { value -> onComponentChange(component.withTextScale(value.toInt()), false) },
    )

    /*
     * 文字偏移。
     *
     * 加这一对滑块的原因很实在：有些字体自带左右边距，
     * 居中排出来看着就是偏的；而"看着偏"没法用别的方式修。
     * 只挪文字、**不动边框**。
     */
    SliderRow(
        label = "文字 X 偏移",
        value = component.textOffsetX,
        range = -60f..60f,
        display = component.textOffsetX.toInt().toString(),
        onBegin = onBeginContinuous,
        onEnd = onEndContinuous,
        onChange = { value -> onComponentChange(component.withTextOffset(x = value), false) },
    )
    SliderRow(
        label = "文字 Y 偏移",
        value = component.textOffsetY,
        range = -60f..60f,
        display = component.textOffsetY.toInt().toString(),
        onBegin = onBeginContinuous,
        onEnd = onEndContinuous,
        onChange = { value -> onComponentChange(component.withTextOffset(y = value), false) },
    )

    /*
     * ============================================================
     * 字间距 / 行间距（**相对字号的百分比**）
     * ============================================================
     * 用百分比而不是像素：字号是可调的，固定像素值在小字号下会挤成一团、
     * 大字号下又几乎看不出来。
     *
     * ⚠️ **对所有字体都显示**，包括常规（矢量）字体。
     * 早先只在使用图片字体时才显示，那是个设计错误 ——
     * "字距不合适"在矢量字体上同样会发生（中文字体尤其常见），
     * 把入口藏起来等于这个功能对多数用户不存在。
     */
    SliderRow(
        label = "字间距",
        value = component.style.letterSpacing,
        range = LETTER_SPACING_MIN..LETTER_SPACING_MAX,
        display = "${component.style.letterSpacing.toInt()}%",
        onBegin = onBeginContinuous,
        onEnd = onEndContinuous,
        onChange = { value ->
            onComponentChange(
                component.withStyle(component.style.copy(letterSpacing = value)),
                false,
            )
        },
    )
    SliderRow(
        label = "行间距",
        value = component.style.lineSpacing,
        range = LINE_SPACING_MIN..LINE_SPACING_MAX,
        display = "${component.style.lineSpacing.toInt()}%",
        onBegin = onBeginContinuous,
        onEnd = onEndContinuous,
        onChange = { value ->
            onComponentChange(
                component.withStyle(component.style.copy(lineSpacing = value)),
                false,
            )
        },
    )

    /*
     * ============================================================
     * 这里**没有**「CPS 行字号」滑块
     * ============================================================
     * 曾经有过一个，删掉了 —— 用户实际用不到：
     *
     * - 组件里的两行字号由组件自己的字号决定，用户要改就改那个；
     * - "从 Key 样式 CPS 模式 3 转换过来时副行更小"这件事，
     *   转换时已经定好了，用户不需要再微调。
     *
     * 留一个用不到的滑块只会让面板更长、更像"没效果"。
     *
     * ⚠️ 数据字段 `cpsTextScalePercent` 仍然保留并生效：
     * **转换**需要它（`KeyToCustomConverter` 会把 79% 写进去），
     * 渲染两条路径也都吃它。删掉的只是这一个界面入口。
     */

    SubLabel("形状")
    /* ---------- 圆角 ---------- */

    SwitchRow(
        label = "圆角",
        checked = component.style.cornerRadiusEnabled,
        onChange = { onStyleChange(component.style.copy(cornerRadiusEnabled = it), true) },
    )
    if (component.style.cornerRadiusEnabled) {
        SliderRow(
            label = "圆角程度",
            value = component.style.cornerRadiusPercent,
            range = CustomLayout.CORNER_PERCENT_MIN..CustomLayout.CORNER_PERCENT_MAX,
            display = "${component.style.cornerRadiusPercent.toInt()}%",
            onBegin = onBeginContinuous,
            onEnd = onEndContinuous,
            onChange = { value ->
                onStyleChange(component.style.copy(cornerRadiusPercent = value), false)
            },
        )
    }

    /* ---------- 描边 ---------- */

    SwitchRow(
        label = "描边",
        checked = component.style.outlineEnabled,
        onChange = { onStyleChange(component.style.copy(outlineEnabled = it), true) },
    )
    if (component.style.outlineEnabled) {
        SliderRow(
            label = "描边宽度",
            value = component.style.outlineWidth,
            range = CustomLayout.OUTLINE_WIDTH_MIN..CustomLayout.OUTLINE_WIDTH_MAX,
            display = formatTrimmed(component.style.outlineWidth),
            onBegin = onBeginContinuous,
            onEnd = onEndContinuous,
            onChange = { value -> onStyleChange(component.style.copy(outlineWidth = value), false) },
        )
    }

    /* ---------- 文字阴影 ---------- */

    SwitchRow(
        label = "文字阴影",
        checked = component.style.shadowEnabled,
        onChange = { onStyleChange(component.style.copy(shadowEnabled = it), true) },
    )
    if (component.style.shadowEnabled) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ShadowMode.entries.forEach { shadowMode ->
                val selected = component.style.shadowMode == shadowMode
                OutlinedButton(
                    onClick = {
                        onStyleChange(component.style.copy(shadowMode = shadowMode), true)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = shadowMode.label,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
        /*
         * 一个滑块两种含义，必须写清楚 ——
         * 否则用户切到硬阴影会以为滑块坏了（效果从"变糊"变成"挪位"）。
         */
        HintText(
            text = when (component.style.shadowMode) {
                ShadowMode.SOFT -> "柔光：跟随文字形状的模糊投影，滑块控模糊程度"
                ShadowMode.HARD -> "硬阴影：文字右下方一份实心副本，滑块控偏移距离"
            },
        )
        SliderRow(
            label = if (component.style.shadowMode == ShadowMode.SOFT) "模糊程度" else "偏移距离",
            value = component.style.shadowSize,
            range = CustomLayout.SHADOW_SIZE_MIN..CustomLayout.SHADOW_SIZE_MAX,
            display = formatTrimmed(component.style.shadowSize),
            onBegin = onBeginContinuous,
            onEnd = onEndContinuous,
            onChange = { value -> onStyleChange(component.style.copy(shadowSize = value), false) },
        )
    }

    SubLabel("字体")

    /*
     * 字体入口。
     *
     * ⚠️ 这里必须用 `FontRegistry.displayNameOf`，**不能**拿 id 去截字符串。
     *
     * 字体 id 的形态是 `前缀:载荷`，而那段载荷对用户毫无意义：
     *
     * | 来源 | id | 截出来是 | 该显示 |
     * |---|---|---|---|
     * | 系统 | `system:sans-serif` | `sans-serif` | 默认字体 |
     * | 内置 | `builtin:Minecraft AE.ttf` | `Minecraft AE.ttf` | Minecraft AE |
     * | 导入 | `imported:<uuid>` | 一串 uuid | 用户给它起的名字 |
     *
     * 早先这里写的是 `fontId.substringAfter(':')`，于是三种字体在外面
     * 显示的全是内部标识 —— 而"选择字体"对话框里显示的是显示名，
     * 同一份数据在两个地方名字不一样，用户会以为选错了。
     *
     * `displayNameOf` 在字体已被删除时会给"默认（字体已移除）"这种情况化文案，
     * 比显示一个查不到的 id 友好得多。
     */
    OutlinedButton(
        onClick = onPickFont,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Text("字体：${FontRegistry.displayNameOf(component.style.fontId)}")
    }

    /*
     * 图片字体（可选）。
     *
     * ============================================================
     * 为什么是**单独一项**、而不是和上面合成一个按钮
     * ============================================================
     * 两者不是二选一：图片字体只有 ASCII 字形，中文要靠上面的常规字体
     * 兜底。所以真实关系是「常规字体（必选）+ 图片字体（可选）」，
     * 界面上也就是两个入口。
     *
     * 未选择时按钮显示"不使用" —— 它的默认状态就是不用，
     * 而写成"图片字体：默认（字体已移除）"那种会让人以为哪里坏了。
     */
    OutlinedButton(
        onClick = onPickBitmapFont,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        val id = component.style.bitmapFontId
        Text(
            if (id.isBlank()) {
                "图片字体：不使用"
            } else {
                "图片字体：${FontRegistry.displayNameOf(id)}"
            },
        )
    }
}

/**
 * 颜色：键帽 / 文字 / 描边 / 阴影，每项分未按下与按下。
 *
 * 元素顺序与 Key 样式的「颜色」分区**完全一致**，
 * 也与下面的「透明度」分区一致 —— 三处对照着改时不需要重新找位置。
 *
 * 描边与阴影那两对**只在功能开启时出现**：没开的时候调颜色没有意义。
 */
@Composable
private fun ColorSection(
    component: TextualComponent,
    onStyleChange: (ComponentStyle, Boolean) -> Unit,
) {
    /* 只有按键组件有"按下"这个状态；文本组件只显示未按下那一档 */
    val hasPressed = component is KeyComponent

    HexColorRow(
        label = "键帽（未按下）",
        value = component.style.fillUp,
        alphaPercent = component.style.fillOpacityUp,
        onValueChange = { onStyleChange(component.style.copy(fillUp = it), true) },
    )
    if (hasPressed) {
        HexColorRow(
            label = "键帽（按下）",
            value = component.style.fillDown,
            alphaPercent = component.style.fillOpacityDown,
            onValueChange = { onStyleChange(component.style.copy(fillDown = it), true) },
        )
    }
    HexColorRow(
        label = "文字（未按下）",
        value = component.style.textUp,
        alphaPercent = component.style.textOpacityUp,
        onValueChange = { onStyleChange(component.style.copy(textUp = it), true) },
    )
    if (hasPressed) {
        HexColorRow(
            label = "文字（按下）",
            value = component.style.textDown,
            alphaPercent = component.style.textOpacityDown,
            onValueChange = { onStyleChange(component.style.copy(textDown = it), true) },
        )
    }

    if (component.style.outlineEnabled) {
        HexColorRow(
            label = "描边（未按下）",
            value = component.style.outlineUp,
            alphaPercent = component.style.outlineOpacityUp,
            onValueChange = { onStyleChange(component.style.copy(outlineUp = it), true) },
        )
        if (hasPressed) {
            HexColorRow(
                label = "描边（按下）",
                value = component.style.outlineDown,
                alphaPercent = component.style.outlineOpacityDown,
                onValueChange = { onStyleChange(component.style.copy(outlineDown = it), true) },
            )
        }
    }

    if (component.style.shadowEnabled) {
        HexColorRow(
            label = "文字阴影（未按下）",
            value = component.style.shadowUp,
            alphaPercent = component.style.shadowOpacityUp,
            onValueChange = { onStyleChange(component.style.copy(shadowUp = it), true) },
        )
        if (hasPressed) {
            HexColorRow(
                label = "文字阴影（按下）",
                value = component.style.shadowDown,
                alphaPercent = component.style.shadowOpacityDown,
                onValueChange = { onStyleChange(component.style.copy(shadowDown = it), true) },
            )
        }
    }
}

/**
 * 透明度：与「颜色」同样的四个元素、各两种状态。
 *
 * 顺序刻意与「颜色」分区一致，方便来回对照。
 */
@Composable
private fun OpacitySection(
    component: TextualComponent,
    onStyleChange: (ComponentStyle, Boolean) -> Unit,
    onBeginContinuous: () -> Unit,
    onEndContinuous: () -> Unit,
) {
    val hasPressed = component is KeyComponent

    @Composable
    fun slider(label: String, value: Int, apply: (ComponentStyle, Int) -> ComponentStyle) {
        SliderRow(
            label = label,
            value = value.toFloat(),
            range = 0f..100f,
            display = "$value%",
            onBegin = onBeginContinuous,
            onEnd = onEndContinuous,
            onChange = { v -> onStyleChange(apply(component.style, v.toInt()), false) },
        )
    }

    slider("键帽（未按下）", component.style.fillOpacityUp) { s, v -> s.copy(fillOpacityUp = v) }
    if (hasPressed) {
        slider("键帽（按下）", component.style.fillOpacityDown) { s, v -> s.copy(fillOpacityDown = v) }
    }
    slider("文字（未按下）", component.style.textOpacityUp) { s, v -> s.copy(textOpacityUp = v) }
    if (hasPressed) {
        slider("文字（按下）", component.style.textOpacityDown) { s, v -> s.copy(textOpacityDown = v) }
    }

    if (component.style.outlineEnabled) {
        slider("描边（未按下）", component.style.outlineOpacityUp) { s, v ->
            s.copy(outlineOpacityUp = v)
        }
        if (hasPressed) {
            slider("描边（按下）", component.style.outlineOpacityDown) { s, v ->
                s.copy(outlineOpacityDown = v)
            }
        }
    }

    if (component.style.shadowEnabled) {
        slider("文字阴影（未按下）", component.style.shadowOpacityUp) { s, v ->
            s.copy(shadowOpacityUp = v)
        }
        if (hasPressed) {
            slider("文字阴影（按下）", component.style.shadowOpacityDown) { s, v ->
                s.copy(shadowOpacityDown = v)
            }
        }
    }
}

/**
 * 按下动画（只有按键组件）。
 */
@Composable
private fun MotionSection(
    component: KeyComponent,
    onComponentChange: (CustomComponent, Boolean) -> Unit,
    onBeginContinuous: () -> Unit,
    onEndContinuous: () -> Unit,
) {
    /*
     * 三种动画模式用一组按钮而不是下拉菜单：它们**并列**，
     * 而且"无动画"本身也是一种模式（见 [AnimationMode] 的说明）。
     * 三个按钮刚好一行放得下。
     */
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AnimationMode.entries.forEach { mode ->
            val selected = component.animationMode == mode
            OutlinedButton(
                onClick = { onComponentChange(component.copy(animationMode = mode), true) },
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = mode.label,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }

    /* 选了"无动画"就没有时长可调 —— 摆一条不起作用的滑块只会让人困惑 */
    if (component.animationMode != AnimationMode.NONE) {
        SliderRow(
            label = "动画时长",
            value = component.animationDurationSec,
            range = ANIMATION_DURATION_MIN..ANIMATION_DURATION_MAX,
            display = "${formatTrimmed(component.animationDurationSec)}s",
            onBegin = onBeginContinuous,
            onEnd = onEndContinuous,
            onChange = { value ->
                onComponentChange(component.copy(animationDurationSec = value), false)
            },
        )
    }
}

/* ============================================================
 * 小控件
 * ============================================================ */

/** 一行小字提示。用 tertiary 而不是 onSurfaceVariant：这些都是"怎么办"的说明 */
@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.tertiary,
        modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 4.dp),
    )
}

/**
 * 组内的小标题（例如"外观"组里的「未按下」/「按下时」）。
 *
 * 与 [PanelGroup] 的标题刻意不同：那个是可点击的折叠条、字号更大、用主题色；
 * 这个只是分隔，跟着正文的颜色走 —— 视觉上一眼能分出"这是分组"与"这是组内分段"。
 */
@Composable
private fun SubLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp),
    )
}

/**
 * 主文字输入框（按键是键面文字、文本是内容）。
 *
 * 本地状态 + [LaunchedEffect] 同步外部值，理由见 [PropertyPanel] 的说明。
 * `key` 用组件 id：切换选中组件时必须**换成另一个输入框的状态**，
 * 否则会出现"选中的是 A，输入框里还是 B 的文字"。
 */
@Composable
private fun PrimaryTextField(
    component: TextualComponent,
    onComponentChange: (CustomComponent, Boolean) -> Unit,
    /**
     * 编辑开始（获得焦点）。
     *
     * ⚠️ 必须给，否则**文字改动撤回不了**（这里漏过一次）。
     *
     * 撤回栈只在 `mutateOnce()` 之后才前进；而这里传的是
     * `onComponentChange(..., asStep = false)`（打字过程中不该每敲一个字
     * 压一步），于是历史**原地不动** —— `canUndo` 永远是灰的，
     * 改完文字想撤回发现没反应。
     *
     * 由这两个回调把"一次输入 = 一步"围起来：聚焦时开步、
     * 失焦或按「完成」时收步。与滑块用的是同一对语义。
     */
    onBeginEdit: () -> Unit,
    onEndEdit: () -> Unit,
) {
    val external = component.primaryText()
    var text by remember(component.id) { mutableStateOf(external) }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(component.id, external) {
        if (external != text) text = external
    }

    OutlinedTextField(
        value = text,
        onValueChange = { newValue ->
            text = newValue
            onComponentChange(component.withPrimaryText(newValue), false)
        },
        singleLine = true,
        label = { Text(if (component is KeyComponent) "键面文字" else "文本内容") },
        placeholder = {
            Text(if (component is KeyComponent) "例如 Q 或 LMB(cps2)" else "例如 CPS: (cps)")
        },
        supportingText = {
            Text(
                text = if (CustomLayout.hasCpsPlaceholder(text)) {
                    "已包含 CPS 占位符，画布上会显示实时数字"
                } else {
                    "(cps) 显示数字，(cps2) 为 0 时连括号一起隐藏"
                },
                style = MaterialTheme.typography.labelSmall,
            )
        },
        /*
         * "完成"要真的**结束编辑**。
         *
         * 软键盘上点「完成」（或外接键盘按 Enter）默认只是收起键盘，
         * 焦点还留在输入框里 —— 表现是"编辑完了但还在编辑中"，
         * 光标一直闪、下一次点击别处才真正退出。
         * 这里显式 `clearFocus()`，与颜色输入框保持同一套行为。
         */
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(
            onDone = {
                focusManager.clearFocus()
                // 「完成」也算这次编辑结束 —— 与失焦走同一条收步路径
                onEndEdit()
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .onFocusChanged { state ->
                /*
                 * 聚焦开步、失焦收步：整段输入只占**一步**撤回。
                 *
                 * 收步时 [CustomLayoutDraft.finishStep] 会自己判断
                 * "这一步里没有实际变化"并把空步撤掉，
                 * 所以点进输入框又直接退出**不会**留下一步无效撤回。
                 */
                if (state.isFocused) onBeginEdit() else onEndEdit()
            },
    )
}

/** 位置显示：数值 + 占画布的百分比，让"在画布的哪个位置"更直观 */
private fun positionText(value: Float, canvas: Float): String {
    val percent = (value / canvas * 100f).toInt()
    return "${value.toInt()}（$percent%）"
}

/**
 * 带数值显示的滑块（也供别处复用）。
 *
 * ============================================================
 * ⚠️ "拖动开始"用 `onValueChange` 的首次回调去重标记
 * ============================================================
 * 不要为了标记开始而在外面套 `pointerInput` —— 那会和 Slider 自己的手势竞争，
 * 结果是**滑块彻底拖不动**（这个坑踩过）。用它的回调，别抢它的事件。
 */
/**
 * 带数值显示的滑块行，**数值可点开直接输入**。
 *
 * 实现已搬到共用的 [EditableSliderRow]（`ui/component/EditableSliderRow.kt`）——
 * 自定义编辑页与配置编辑页原先各写了一份，两份的差异只有内边距与字号，
 * 却各自维护。共用之后"点数值改精确值"这个能力对两边**同时**生效，
 * 不会出现"这个页面能改、那个页面不能"。
 *
 * ⚠️ 保留这个薄封装而不是让调用方直接用 `EditableSliderRow`：
 * 参数名（`range` / `onChange` / `onBegin` / `onEnd`）与这里所有调用点一致，
 * 换实现时不必改几十处调用。
 */
@Composable
fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    onBegin: () -> Unit,
    onChange: (Float) -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 滑块的**档位间隔数**（`Slider` 的 `steps`）；`0` = 连续可拖。
     *
     * ⚠️ 默认 `0`（连续）—— 与改动前完全一致，老调用方不受影响。
     *
     * ⚠️ 什么时候要传:当"范围宽度 / 100"算出来的档位对用户**太粗**时。
     * 例如「响应时间」的范围是 `20..600`（宽 580），默认粒度是 5.8 ——
     * 拖出来 `470.88318` 这种值，**存进配置、界面上还显示不出来**
     * （用户的原话:"滑块没有设置最小单位是1所以会有一堆小数"）。
     *
     * ⚠️ 传 `跨度 - 1` 就是"每 1 个单位一档"（`steps` 是间隔数，
     * 可停靠位置有 `steps + 1` 个）。
     */
    steps: Int = 0,
) {
    EditableSliderRow(
        label = label,
        value = value,
        range = range,
        display = display,
        onValueChange = onChange,
        modifier = modifier.padding(horizontal = 4.dp),
        step = stepOf(range),
        steps = steps,
        onBeginDrag = onBegin,
        onEndDrag = onEnd,
    )
}

/**
 * 输入框 / 滑块的粒度：按**范围宽度**猜一个合理的精度。
 *
 * 范围很窄的（比如 `0..1` 的透明度）需要小数，宽范围（`0..200`）用整数就够
 * —— 一律用整数的话窄范围只能填 0 或 1，一律用小数的话宽范围
 * 会看到"125.0"这种多余的小数点。
 *
 * ============================================================
 * ⚠️⚠️ 最后那个"宽度不足 100 档就按 100 档切"是必需的
 * ============================================================
 * 我原来只写了"span ≤ 2 → 0.01"这一档。而那对**特别窄**的范围是坏的:
 * 鼠标灵敏度是 `0.0005..0.02`（span `0.0195`），按 0.01 切的话
 * **整个滑块只剩两三个可停的位置**，精调完全不可能 ——
 * 而那个范围恰恰是用户明确要求"下限再低一点"才变成这么窄的。
 *
 * ⚠️ 所以现在:`step = span / 100`，但**不比原来猜出来的粒度更细**。
 * 「平滑时间」那种 span 290 的滑块仍然是 1（与改动前一致，行为没变）。
 */
internal fun stepOf(range: ClosedFloatingPointRange<Float>): Float {
    val span = range.endInclusive - range.start
    val guessed = when {
        span <= 2f -> 0.01f
        span <= 20f -> 0.1f
        else -> 1f
    }
    /*
     * ⚠️ 至少给 100 个可停位置 —— 否则窄范围会变成"只有两三档"。
     * 比原粒度更粗的方向**不动**（guessed 更大时保留 guessed）。
     */
    return minOf(guessed, span / 100f).coerceAtLeast(1e-6f)
}

/**
 * 把"存在比例"的字段做成滑块：**滑块上的数字 = 输入框里的数字**。
 *
 * ============================================================
 * ⚠️⚠️ 它解决的是"显示单位与输入单位不一致"
 * ============================================================
 * 摇杆的这几个字段在数据里存的是 **`0..1` 的比例**
 * （`JoystickStyle.cornerRatio` / `opacity` …），而界面上习惯用**百分比**显示:
 *
 * ```
 * 数据 0.225  →  滑块旁边显示 "45%"
 * ```
 *
 * 问题出在"点数字弹输入框"那一步:[EditableSliderRow] 的输入框填的是
 * **滑块自己的值**（也就是 `0.225`）—— 于是用户看到:
 *
 * | 位置 | 显示 |
 * |---|---|
 * | 滑块旁边 | `45%` |
 * | 点开输入框 | `0.225` |
 *
 * 用户的原话:"**手柄摇杆的圆角滑块是百分比调整，但是点击数字弹出输入框，
 * 数值居然不一样**……不明不白的，需要改进一下，与正常组件配置调整时的单位统一"。
 *
 * ⚠️ 这里**不靠 `suffix` 补救** —— 加个单位后缀只是让输入框多显示一个 `%`，
 * 里面的数字仍然是 `0.225`，用户还是得自己换算。而"统一单位"的意思是
 * **两个地方的数字一样**。
 *
 * ============================================================
 * 做法:滑块工作在**显示单位**上，进出各换算一次
 * ============================================================
 * - [range] 与 [displayValue] 都用**显示单位**（百分比就是 `0..50`、`0..100`）；
 * - [toRatio] 把显示值换算回数据用的比例（百分比就是 `÷ 100`）；
 * - 从而输入框的取值范围 = 用户看到的范围，两边天然一致。
 *
 * ⚠️ 只对**确实是"显示单位与存储单位不同"**的字段用它。
 * 像 `sizeScale`（`1.5` 显示成 `1.50×`）、`smoothingMs`（`60` 显示成 `60 ms`）
 * 这类"数字本身没变、只是加了符号"的字段用普通 [SliderRow] 就行 ——
 * 硬套这一层只会多一次没必要的乘除，还容易把范围写错。
 */
@Composable
internal fun JoystickSlider(
    label: String,
    style: JoystickStyle,
    value: Float,
    /**
     * 以**显示单位**给出的合法范围。
     *
     * ⚠️ 名字里带 `Display` 是刻意的:它**不是**数据里那个 `0..1` 的比例范围。
     * 滑块、输入框、粒度三者都按这个范围工作，才不会出现"滑块能拖到 50、
     * 输入框却只允许 0.5"这种对不上的情况。
     */
    displayRange: ClosedFloatingPointRange<Float>,
    /** 显示值 → 比例（数据里存的那个数） */
    toRatio: (Float) -> Float,
    /** 比例 → 显示值 */
    fromRatio: (Float) -> Float,
    /**
     * 把**比例**写回样式。
     *
     * ⚠️ 由调用方给出（`{ s, r -> s.copy(cornerRatio = r) }`），
     * 而不是让这个组件去猜字段名 —— 它只知道"新值"，不知道往哪放。
     */
    applyRatio: (JoystickStyle, Float) -> JoystickStyle,
    /** 输入框的单位后缀；空表示不带单位 */
    suffix: String,
    /**
     * 显示文本。
     *
     * ⚠️ 参数是**显示值**（`45f`），不是比例 —— 调用方不用再自己乘。
     * 由这个函数统一换算，就不会出现"某个滑块忘了乘"这种不一致。
     */
    displayText: (Float) -> String,
    onStyleChange: (JoystickStyle) -> Unit,
    /** 内边距等；默认取本文件里滑块的共同值（`horizontal = 4.dp`） */
    modifier: Modifier = Modifier.padding(horizontal = 4.dp),
) {
    /*
     * ⚠️ **滑块与输入框都在显示单位上工作** —— 与按键/文本组件那边的做法一致
     * （它们的 `cornerRadiusPercent` 存的就是 `16`，所以滑块与输入框天然一致）。
     *
     * 于是"滑块显示 45、输入框填 45"是**同一个数**，不存在
     * "滑块显示 45%、输入框里却是 0.225"那种对不上的情况。
     */
    val shown = fromRatio(value)

    EditableSliderRow(
        label = label,
        value = shown,
        range = displayRange,
        display = displayText(shown),
        /* 粒度也按显示单位的范围算，与输入框里的数字量级一致 */
        step = stepOf(displayRange),
        suffix = suffix,
        onValueChange = { typed -> onStyleChange(applyRatio(style, toRatio(typed))) },
        modifier = modifier,
    )
}

/**
 * 把 [JoystickSlider] 用在**描边宽度**这类字段上。
 *
 * ============================================================
 * ⚠️⚠️ 描边宽度的单位必须与按键/文本组件一致（dp）
 * ============================================================
 * 用户的原话:"别的组件描边宽度单位都是 dp，**只有摇杆组件描边粗细的单位是
 * 千分号**，不知道还有没有别的地方是这个问题，你检查一下，**不要让摇杆组件
 * 单独用一套单位**，不然很迷"。
 *
 * 而 `JoystickStyle` 里那几个字段存的是**相对边长的比例**
 * （`strokeWidthRatio = 0.02` = 边长的 2%），渲染时:
 *
 * ```kotlin
 * val strokeWidth = sideBase * style.strokeWidthRatio * pxPerBase
 * ```
 *
 * ⚠️ 这个**存储方式**是有道理的（描边要跟着组件尺寸一起缩放，
 * 组件能从 20 变到 1000），所以**存储保持比例不变**。
 *
 * ⚠️ 但**显示与输入都用 dp** —— 那才是"不让摇杆单独用一套单位"的意思。
 * [baseDp] 是这条线宽的基准（底盘 / 内圆 = 组件边长，
 * 帽描边 = 帽直径），单位与组件边长一致（基础坐标里 1:1 对应 dp）。
 *
 * 于是换算很直接:
 *
 * ```
 * 显示 dp  = 比例 × baseDp
 * 存回比例 = 输入的 dp / baseDp
 * ```
 */
@Composable
internal fun JoystickWidthSlider(
    label: String,
    style: JoystickStyle,
    value: Float,
    /** 这条线宽的基准长度（基础坐标，1:1 对应 dp） */
    baseDp: Float,
    /** 存回比例；`{ s, r -> s.copy(strokeWidthRatio = r) }` */
    applyRatio: (JoystickStyle, Float) -> JoystickStyle,
    /**
     * **dp 上限**（不是比例上限）。
     *
     * ⚠️ 用 `JoystickStyle.STROKE_WIDTH_MAX_DP` 那几个常量 ——
     * 它们与标准样式的「描边宽度」（`0.5f..5f`）一致。
     *
     * ⚠️ 以前这里传的是**比例**上限（`0.2f`），于是滑块的 dp 上限
     * 变成 `0.2 × 基准` —— 在 120 的组件上就是 **24dp**，
     * 而别的组件最多只能到 5dp。用户直接问了:
     * "怎么你摇杆这里写描边最大值是 24？"
     */
    maxDp: Float,
    onStyleChange: (JoystickStyle) -> Unit,
    /**
     * 内边距等。
     *
     * ============================================================
     * ⚠️⚠️ 必须与**同一个页面里其它滑块**一致，否则长短不齐
     * ============================================================
     * 两个页面的标准内边距**不同**（它们各自的 `SliderRow` 就是这么写的）:
     *
     * | 页面 | 内边距 |
     * |---|---|
     * | 自定义编辑页 | `horizontal = 4.dp` |
     * | 配置编辑页（标准样式） | `horizontal = 16.dp, vertical = 10.dp` |
     *
     * ⚠️ 我第一版把它写死成 `4.dp`，于是标准页那三个滑块比旁边的
     * 滑块**左右各多出 12dp** —— 用户的原话:"**标准页有些滑块长一截**"。
     *
     * ⚠️ 所以这个值**由调用方给**，默认值取自定义页那个
     * （本文件里所有滑块的共同默认）。
     */
    modifier: Modifier = Modifier.padding(horizontal = 4.dp),
) {
    /*
     * ⚠️ `baseDp` 兜底到非 0:它来自组件尺寸，而尺寸理论上不会为 0，
     * 但真出现 0 的话这里会除零得到 `NaN`，那会一路画到界面上
     * （`NaN` 的滑块会整个失灵）。兜一下比事后排查便宜得多。
     */
    val safeBase = baseDp.coerceAtLeast(1f)

    /*
     * ⚠️ 存储是**比例**，而比例不能超过"dp 上限 / 基准" ——
     * 超过的话渲染出来的线会超出 5dp，与滑块显示的上限对不上。
     *
     * 例:基准 24dp、dp 上限 5 → 比例上限 0.208。
     */
    val maxRatioByDp = maxDp / safeBase

    /*
     * ⚠️ 再按**真实的夹取上限**收一次:存档那边（`JsonConfigCodec` 的
     * `ratioMax`）也会夹，两边不一致的话会出现"拖到底、数字不动"。
     */
    val ratioMax = minOf(maxRatioByDp, JoystickStyle.MAX_WIDTH_RATIO)

    JoystickSlider(
        label = label,
        style = style,
        value = value,
        /* 滑块的 dp 范围:比例上限 × 基准长度 */
        displayRange = 0f..(ratioMax * safeBase),
        toRatio = { dp -> dp / safeBase },
        fromRatio = { ratio -> ratio * safeBase },
        applyRatio = applyRatio,
        /*
         * ⚠️ **不带单位后缀** —— 与按键/文本组件的「描边宽度」一致
         * （它显示的就是一个纯数字 `2.5`）。dp 是这个项目的**隐含单位**，
         * 到处都标出来反而啰嗦；而 `‰` 那种额外单位才是"很迷"的来源。
         */
        suffix = "",
        displayText = { dp -> formatTrimmed(dp) },
        onStyleChange = onStyleChange,
        modifier = modifier,
    )
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * 去掉多余小数位。
 *
 * 不用 `String.format`：它跟系统 Locale 走，某些地区的小数点是逗号，
 * 界面上会显示成 `0,1` —— 这种"看起来像 bug"的显示问题不值得冒险。
 */
private fun formatTrimmed(value: Float): String = (round(value * 100f) / 100f).toString()
/*
 * ============================================================
 * 字间距 / 行间距的取值上下限（**相对字号的百分比**）
 * ============================================================
 * 为什么用百分比而不是像素：字号可调，固定像素值在小字号下会挤成一团、
 * 在大字号下又几乎看不出来。百分比天生跟着字号缩放。
 *
 * 字间距允许负值（收紧到重叠），因为有些材质包的字形自带较宽的
 * 左侧留白，需要收回来才好看。
 *
 * ⚠️ 行间距的负值**有下限**（引擎侧夹在 0.5 倍行高）：
 * 再小两行就压在一起了，那不是"紧凑"而是坏了。
 */
private const val LETTER_SPACING_MIN = -30f
private const val LETTER_SPACING_MAX = 100f
private const val LINE_SPACING_MIN = -50f
private const val LINE_SPACING_MAX = 200f

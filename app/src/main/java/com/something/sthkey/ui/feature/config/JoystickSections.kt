package com.something.sthkey.ui.feature.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.something.sthkey.domain.config.JoystickStyle
import com.something.sthkey.ui.feature.custom.JoystickWidthSlider
import com.something.sthkey.domain.config.KeyStrokesConfig
import com.something.sthkey.domain.style.KeyLayout
import com.something.sthkey.ui.component.collapsibleSection
import com.something.sthkey.ui.component.setExpanded
import com.something.sthkey.ui.component.CardDivider
import com.something.sthkey.ui.component.HexColorRow
import com.something.sthkey.ui.component.SectionHint
import com.something.sthkey.ui.component.SettingsCard

/*
 * ============================================================
 * 摇杆的专属设置分区（只有手柄样式显示）
 * ============================================================
 * 用户的原话:"摇杆相关配置可以单独拉出来，不跟其他按键共同配置"。
 *
 * 理由:摇杆和"按键"不是一回事（按键只有按下/没按下，摇杆有底盘、
 * 内圆、摇杆帽三层），混在一起时"摇杆帽的圆角"和"Shift 的颜色"
 * 会挨着出现，改一个要翻半天。
 *
 * ============================================================
 * ⚠️ 分三组，与自定义编辑页的分组思路一致
 * ============================================================
 * | 组 | 内容 |
 * |---|---|
 * | 摇杆 | 缩放、圆角、透明度、颜色、底盘描边、内圆（颜色/透明度/粗细） |
 * | 摇杆帽 | 缩放、圆角、颜色、透明度、描边 |
 * | 手感（**仅显示**） | 死区、灵敏度、平滑·精准 |
 *
 * ⚠️ **底盘描边**与**内圆**是两回事（用户专门提过）:
 * - 描边 = 方框的四条边
 * - 内圆 = 底盘上那个圆（活动轨道）
 *
 * ⚠️ **手感那一组只影响悬浮窗的显示**，不改变游戏收到的摇杆输入。
 * 界面上必须写明 —— 不写会有人以为调了灵敏度就能改变游戏手感。
 *
 * ⚠️ 本文件是**新加的**（原来这些分区写在 `ConfigEditorScreen.kt` 里，
 * 那个文件已经 1800 多行）。拆出来纯粹是为了不再往它里面塞东西 ——
 * 那个文件太大，任何一次批量改动都很难核对。
 */

/**
 * 摇杆设置的三个分区（**都可折叠**）。
 *
 * 调用方负责判断"当前样式是不是手柄样式"。
 *
 * ⚠️ 折叠状态与展开回调**从调用方传进来** —— 它们是**页面级**的 `remember`
 * 状态，而本函数是 `LazyListScope` 的扩展、不在 composable 作用域里，
 * 读不到页面里的局部变量。
 */
internal fun LazyListScope.joystickStyleSections(
    editable: KeyStrokesConfig,
    expandedSections: Set<String>,
    onExpandedChange: (Set<String>) -> Unit,
    applyChange: ((KeyStrokesConfig) -> KeyStrokesConfig) -> Unit,
) {
    val js = editable.joystick

    /*
     * ============================================================
     * 描边 / 内圆的 dp 基准（**与渲染公式一一对应**）
     * ============================================================
     * 渲染那边（`Joystick.kt`）是:
     *
     * ```
     * val strokeWidth = sideBase * style.strokeWidthRatio * pxPerBase
     * val knobStrokeWidth = knobDiameter * style.knobStrokeWidthRatio * pxPerBase
     * ```
     *
     * 而 `sideBase` 就是**摇杆槽位的宽度**（`Gamepad2Content` 直接把
     * `KeyLayout.keys()` 给出的那个 `box` 传进去）—— 所以这里从**同一个函数**
     * 取，不自己再算一份（自己算必然与布局漂开）。
     *
     * ⚠️ 单位是**基础坐标**，它与 dp 是 1:1
     * （用户在「位置与尺寸」里看到的宽高数字就是 dp）。
     */
    val stickSizeDp = KeyLayout.keys(editable)
        .firstOrNull { it.slotId == KeyLayout.Id.JOYSTICK_LEFT }
        ?.width
        ?: 0f

    /*
     * ⚠️ 帽直径的 `coerceIn(0.2f, 2f)` 与渲染那边**必须一致** ——
     * 存档时可以存超出范围的值，渲染会夹，而这里不夹的话
     * 算出来的 dp 基准就和真实画出来的不一样（描边数值会对不上）。
     */
    val knobDiameterDp = stickSizeDp * js.knobScale.coerceIn(0.2f, 2f)

    collapsibleSection(
        expanded = "joystick:摇杆" in expandedSections,
        onExpandChange = { on ->
            onExpandedChange(setExpanded(expandedSections, "joystick:摇杆", on))
        },
        key = "joystick:摇杆",
        title = "摇杆",
    ) {

    item {
        SettingsCard {
            SliderRow(
                label = "摇杆缩放",
                value = js.sizeScale,
                valueRange = JOYSTICK_SIZE_SCALE_MIN..JOYSTICK_SIZE_SCALE_MAX,
                display = "%.2f×".format(js.sizeScale),
                onValueChange = { v ->
                    applyChange { it.copy(joystick = it.joystick.copy(sizeScale = v)) }
                },
            )

            CardDivider()

            SliderRow(
                label = "圆角",
                value = js.cornerRatio,
                valueRange = 0f..0.5f,
                display = if (js.cornerRatio >= 0.499f) {
                    "正圆"
                } else {
                    "${(js.cornerRatio * 200f).toInt()}%"
                },
                onValueChange = { v ->
                    applyChange { it.copy(joystick = it.joystick.copy(cornerRatio = v)) }
                },
            )

            CardDivider()

            SliderRow(
                label = "透明度",
                value = js.opacity,
                valueRange = 0f..1f,
                display = "${(js.opacity * 100f).toInt()}%",
                onValueChange = { v ->
                    applyChange { it.copy(joystick = it.joystick.copy(opacity = v)) }
                },
            )

            CardDivider()

            HexColorRow(
                label = "摇杆颜色",
                value = js.color.toLong() and 0xFFFFFFFFL,
                onValueChange = { v ->
                    applyChange { it.copy(joystick = it.joystick.copy(color = v.toInt())) }
                },
            )
        }
    }

    /* ---- 底盘描边（方框的四条边）---- */
    item {
        SettingsCard {
            /*
             * ⚠️ 用户的原话:"'摇杆'栏需要加一下描边，你忘记加了，
             * 别漏掉颜色透明度粗细这类的，细心一点"。
             *
             * 三项一个都不能少 —— 只有颜色没法隐藏描边，
             * 只有粗细没法调成半透明。
             */
            HexColorRow(
                label = "描边颜色",
                value = js.strokeColor.toLong() and 0xFFFFFFFFL,
                onValueChange = { v ->
                    applyChange { it.copy(joystick = it.joystick.copy(strokeColor = v.toInt())) }
                },
            )

            CardDivider()

            SliderRow(
                label = "描边透明度",
                value = js.strokeOpacity,
                valueRange = 0f..1f,
                display = if (js.strokeOpacity <= 0f) {
                    "不显示"
                } else {
                    "${(js.strokeOpacity * 100f).toInt()}%"
                },
                onValueChange = { v ->
                    applyChange { it.copy(joystick = it.joystick.copy(strokeOpacity = v)) }
                },
            )

            CardDivider()

            /*
             * ⚠️ 基线宽用 **dp**，不是千分号 —— 与按键/文本组件的「描边宽度」一致。
             * 见 [JoystickWidthSlider] 的说明。
             */
            JoystickWidthSlider(
                label = "描边粗细",
                style = js,
                value = js.strokeWidthRatio,
                baseDp = stickSizeDp,
                applyRatio = { s, r -> s.copy(strokeWidthRatio = r) },
                maxDp = JoystickStyle.STROKE_WIDTH_MAX_DP,
                onStyleChange = { next ->
                    applyChange { it.copy(joystick = next) }
                },
                /* ⚠️ 与同页的 SliderRow 一致（16dp / 10dp），否则长短不齐 */
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }

    /* ---- 内圆（底盘上的活动轨道；**粗细调到 0 就是隐藏它**）---- */
    item {
        SettingsCard {
            HexColorRow(
                label = "内圆颜色",
                value = js.ringColor.toLong() and 0xFFFFFFFFL,
                onValueChange = { v ->
                    applyChange { it.copy(joystick = it.joystick.copy(ringColor = v.toInt())) }
                },
            )

            CardDivider()

            SliderRow(
                label = "内圆透明度",
                value = js.ringOpacity,
                valueRange = 0f..1f,
                display = if (js.ringOpacity <= 0f) {
                    "不显示"
                } else {
                    "${(js.ringOpacity * 100f).toInt()}%"
                },
                onValueChange = { v ->
                    applyChange { it.copy(joystick = it.joystick.copy(ringOpacity = v)) }
                },
            )

            CardDivider()

            JoystickWidthSlider(
                label = "内圆粗细",
                style = js,
                value = js.ringWidthRatio,
                baseDp = stickSizeDp,
                applyRatio = { s, r -> s.copy(ringWidthRatio = r) },
                maxDp = JoystickStyle.RING_WIDTH_MAX_DP,
                onStyleChange = { next ->
                    applyChange { it.copy(joystick = next) }
                },
                /* ⚠️ 与同页的 SliderRow 一致（16dp / 10dp），否则长短不齐 */
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }

    item {
        SectionHint(
            text = "「摇杆」组管方框底盘、四条边框与盘内那个圆；" +
                "圆和描边的**粗细调到 0 就是隐藏它们**。",
        )
    }

    } // ← 折叠结束：joystick:摇杆

    /* ============================================================
     * 摇杆帽
     * ============================================================ */

    collapsibleSection(
        expanded = "joystick:帽" in expandedSections,
        onExpandChange = { on ->
            onExpandedChange(setExpanded(expandedSections, "joystick:帽", on))
        },
        key = "joystick:帽",
        title = "摇杆帽",
    ) {

    item {
        SettingsCard {
            SliderRow(
                label = "摇杆帽缩放",
                value = js.knobScale,
                valueRange = JOYSTICK_KNOB_SCALE_MIN..JOYSTICK_KNOB_SCALE_MAX,
                display = "%.2f×".format(js.knobScale),
                onValueChange = { v ->
                    applyChange { it.copy(joystick = it.joystick.copy(knobScale = v)) }
                },
            )

            CardDivider()

            SliderRow(
                label = "摇杆帽圆角",
                value = js.knobCornerRatio,
                valueRange = 0f..0.5f,
                display = if (js.knobCornerRatio >= 0.499f) {
                    "正圆"
                } else {
                    "${(js.knobCornerRatio * 200f).toInt()}%"
                },
                onValueChange = { v ->
                    applyChange { it.copy(joystick = it.joystick.copy(knobCornerRatio = v)) }
                },
            )

            CardDivider()

            HexColorRow(
                label = "摇杆帽颜色",
                value = js.knobColor.toLong() and 0xFFFFFFFFL,
                onValueChange = { v ->
                    applyChange { it.copy(joystick = it.joystick.copy(knobColor = v.toInt())) }
                },
            )

            CardDivider()

            SliderRow(
                label = "摇杆帽透明度",
                value = js.knobOpacity,
                valueRange = 0f..1f,
                display = "${(js.knobOpacity * 100f).toInt()}%",
                onValueChange = { v ->
                    applyChange { it.copy(joystick = it.joystick.copy(knobOpacity = v)) }
                },
            )
        }
    }

    item {
        SettingsCard {
            HexColorRow(
                label = "摇杆帽描边颜色",
                value = js.knobStrokeColor.toLong() and 0xFFFFFFFFL,
                onValueChange = { v ->
                    applyChange { it.copy(joystick = it.joystick.copy(knobStrokeColor = v.toInt())) }
                },
            )

            CardDivider()

            SliderRow(
                label = "摇杆帽描边透明度",
                value = js.knobStrokeOpacity,
                valueRange = 0f..1f,
                display = if (js.knobStrokeOpacity <= 0f) {
                    "不显示"
                } else {
                    "${(js.knobStrokeOpacity * 100f).toInt()}%"
                },
                onValueChange = { v ->
                    applyChange { it.copy(joystick = it.joystick.copy(knobStrokeOpacity = v)) }
                },
            )

            CardDivider()

            JoystickWidthSlider(
                label = "摇杆帽描边粗细",
                style = js,
                value = js.knobStrokeWidthRatio,
                /* ⚠️ 帽描边的基数是**帽直径**，与渲染里的公式一致 */
                baseDp = knobDiameterDp,
                applyRatio = { s, r -> s.copy(knobStrokeWidthRatio = r) },
                maxDp = JoystickStyle.KNOB_STROKE_WIDTH_MAX_DP,
                onStyleChange = { next ->
                    applyChange { it.copy(joystick = next) }
                },
                /* ⚠️ 与同页的 SliderRow 一致（16dp / 10dp），否则长短不齐 */
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }

    } // ← 折叠结束：joystick:帽

    /* ============================================================
     * 手感（**只影响显示**）
     * ============================================================ */

    collapsibleSection(
        expanded = "joystick:手感" in expandedSections,
        onExpandChange = { on ->
            onExpandedChange(setExpanded(expandedSections, "joystick:手感", on))
        },
        key = "joystick:手感",
        title = "摇杆手感",
    ) {

    item {
        SettingsCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "以下几项只改变悬浮窗上摇杆画到哪，",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "不会改变游戏收到的摇杆输入。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            CardDivider()

            SliderRow(
                label = "死区",
                value = js.deadZone,
                valueRange = 0f..0.5f,
                display = if (js.deadZone <= 0f) {
                    "关闭"
                } else {
                    "%.0f%%".format(js.deadZone * 100f)
                },
                onValueChange = { v ->
                    applyChange { it.copy(joystick = it.joystick.copy(deadZone = v)) }
                },
            )

            CardDivider()

            SliderRow(
                label = "灵敏度",
                value = js.sensitivity,
                valueRange = JOYSTICK_SENSITIVITY_MIN..JOYSTICK_SENSITIVITY_MAX,
                display = "%.2f×".format(js.sensitivity),
                onValueChange = { v ->
                    applyChange { it.copy(joystick = it.joystick.copy(sensitivity = v)) }
                },
            )

            CardDivider()

            DisplayModePicker(
                smoothingMs = js.smoothingMs,
                onChange = { v ->
                    applyChange { it.copy(joystick = it.joystick.copy(smoothingMs = v)) }
                },
            )

            /*
             * 选了「平滑」之后才细调时间 —— 精准模式没有"平滑时间"可言。
             *
             * ⚠️ 平滑时间为 0 时**整个滑块都不显示**，避免出现
             * "拖了没反应"（渲染层看到 0 会直接走精准分支）。
             */
            if (js.smoothingMs > 0f) {
                CardDivider()

                SliderRow(
                    label = "平滑时间",
                    value = js.smoothingMs,
                    valueRange = 10f..JOYSTICK_SMOOTHING_MAX,
                    display = "%.0f ms".format(js.smoothingMs),
                    onValueChange = { v ->
                        applyChange { it.copy(joystick = it.joystick.copy(smoothingMs = v)) }
                    },
                )
            }
        }
    }

    item {
        SectionHint(
            text = "「平滑」起步有加速、停下有缓冲，" +
                "「精准」直接画手柄上报的原始位置、零延迟。",
        )
    }

    } // ← 折叠结束：joystick:手感
}

/**
 * 显示方式:**平滑 / 精准**。
 *
 * ============================================================
 * 为什么用分段按钮而不是一个开关
 * ============================================================
 * 用户的原话:"在'行为'栏搞一个配置，用分页按钮组件，来配置摇杆是
 * 平滑显示还是精准显示"。
 *
 * ⚠️ 两者是**并列的显示方式**，不是"开/关某个功能" —— 开关的语义
 * 会让人以为关掉就没有摇杆了。这与「按下动画」那一组是同一种表达。
 *
 * ⚠️ 内部仍用 `smoothingMs` 表示:`0` = 精准，`> 0` = 平滑（毫秒）。
 * 不新加一个布尔字段 —— 那样两者可能矛盾（开关说精准、毫秒数却不等于 0）。
 */
@Composable
private fun DisplayModePicker(
    smoothingMs: Float,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = "显示方式",
            style = MaterialTheme.typography.bodyLarge,
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = if (smoothingMs > 0f) {
                "平滑：起步与停下都更柔和"
            } else {
                "精准：直接显示手柄上报的原始位置，没有任何延迟"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(8.dp))

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val labels = listOf("平滑", "精准")

            labels.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = if (index == 0) smoothingMs > 0f else smoothingMs <= 0f,
                    onClick = {
                        /* 平滑用 60ms，精准用 0 */
                        onChange(if (index == 0) DEFAULT_SMOOTHING_MS else 0f)
                    },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = labels.size,
                    ),
                ) {
                    Text(label)
                }
            }
        }
    }
}

/** 点「平滑」时用的默认时长（毫秒）。*/
private const val DEFAULT_SMOOTHING_MS = 60f

/*
 * ============================================================
 * 摇杆各滑块的范围
 * ============================================================
 * ⚠️ 与 `JoystickStyle` / `Joystick.kt` 里的 `coerceIn` 范围**必须一致** ——
 * 滑块给得出、渲染却夹掉的话，用户会看到"拖到底但没反应"，
 * 而那是**两边各写了一份范围**造成的。
 */

internal const val JOYSTICK_SIZE_SCALE_MIN = 0.3f
internal const val JOYSTICK_SIZE_SCALE_MAX = 1.08f
internal const val JOYSTICK_KNOB_SCALE_MIN = 0.2f
internal const val JOYSTICK_KNOB_SCALE_MAX = 2f
internal const val JOYSTICK_SENSITIVITY_MIN = 0.2f
internal const val JOYSTICK_SENSITIVITY_MAX = 3f

/**
 * 平滑时间的上限（毫秒）。
 *
 * ⚠️ 与 `Joystick.kt` 里的 `SMOOTHING_MAX_MS` **必须一致**。
 * 300ms 已经很重了（拇指要 0.3 秒才追上大半），再大没有意义。
 */
internal const val JOYSTICK_SMOOTHING_MAX = 300f

/**
 * 「键位映射」里**不该出现**的槽位。
 *
 * ============================================================
 * ⚠️ 判据是"这个开关**真的控制**这个槽位吗"，不是"开关是关的吗"
 * ============================================================
 * 用户报过两件相反的事，根因是同一个:
 *
 * 1. "shift 键我是关闭了的，但为什么还是出现" → 该隐藏的**没隐藏**；
 * 2. "键盘样式配置中，键位映射的 space 组件不见了" → 不该隐藏的**隐藏了**。
 *
 * ⚠️ 第 2 条的成因:键盘样式里 `SPACE` 是**恒定画出来的**
 * （`KeyLayout` 只在手柄样式那条路判断 `showSpaceKey`），
 * 而 `showSpaceKey` 的默认值本来就是 `false`。把它一起接上就等于
 * "布局在画、映射列表里却没有" —— 两边不一致。
 *
 * ⚠️ 所以第 2 条是我的错:用户只说了 Shift，我**顺手扩大了范围**。
 * 下面逐个写明"哪个开关在哪一种样式下真的控制这个槽位"。
 */
internal fun hiddenMappingSlots(config: KeyStrokesConfig): Set<String> = buildSet {
    val gamepad = KeyLayout.usesJoystickLayout(config)

    /*
     * 两种样式里都**恒显示**、且没有对应开关的槽位现在只剩:
     * 手柄样式的 `A_BUTTON`（它拿 A 当"空格位"）—— 见下面 `if (gamepad)` 那段。
     */

    /* `showShiftKey` 两种样式都控制 SHIFT 槽位（键盘显示 Shift、手柄显示 B） */
    if (!config.showShiftKey) add(KeyLayout.Id.SHIFT)

    if (gamepad) {
        /*
         * 手柄专属开关 —— 它们**只**在手柄样式下有意义。
         *
         * ⚠️ 键盘样式没有 LB/RB 这两个键，而 `showShoulderButtons` 默认 `false`、
         * `showAButton` 默认 `true` —— 拿它们在键盘样式下做判断是**语义错位**
         * （碰巧不出问题，但那是巧合，不是设计）。
         */
        if (!config.showShoulderButtons) {
            add(KeyLayout.Id.SHOULDER_L)
            add(KeyLayout.Id.SHOULDER_R)
        }
        if (!config.showAButton) add(KeyLayout.Id.A_BUTTON)

        /* 摇杆槽位永远隐藏:它不绑键码，让人去绑只会得到"按了没反应" */
        add(KeyLayout.Id.JOYSTICK_LEFT)
        add(KeyLayout.Id.JOYSTICK_RIGHT)
    }

    /*
     * ⚠️ `SPACE` 槽位**两种样式都看 `showSpaceKey`**（v2.6.0 起）。
     *
     * 以前键盘样式的空格是**恒定显示**的，所以那时这里**不能**隐藏它 ——
     * 隐藏了就会出现"布局在画、映射列表里却没有"。用户为此报过两次:
     *
     * - "shift 键我是关闭了的，但为什么还是出现"（该隐藏的没隐藏）；
     * - "键盘样式配置中，键位映射的 space 组件不见了"（不该隐藏的隐藏了）。
     *
     * ⚠️ 现在键盘样式有了「显示 SPACE 键」开关，空格**真的可以被关掉**，
     * 于是它必须跟着开关一起隐藏 —— 否则又回到那个不一致:
     * 悬浮窗上不画了、映射列表里还留着一行。
     */
    if (!config.showSpaceKey) add(KeyLayout.Id.SPACE)

    /* `showMouseButtons` 两种样式都控制 LMB / RMB（手柄样式下它们是 LT / RT） */
    if (!config.showMouseButtons) {
        add(KeyLayout.Id.LMB)
        add(KeyLayout.Id.RMB)
    }
}

/*
 * ============================================================
 * 按键高度 / 按键间距 的范围 —— 已挪到 `KeyLayout`
 * ============================================================
 * ⚠️ 它们原来在这里、与 `KeyLayout` 里各写一份，于是出了这个 bug:
 * 滑块下限改成 0、读取那边还是 50，用户设的值**重进就被夹回**。
 *
 * 现在**只有一个真源**（`KeyLayout.KEY_GAP_PERCENT_*` / `KEY_HEIGHT_PERCENT_*`），
 * 设置页、布局层、`JsonConfigCodec` 共用。
 */

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
import com.something.sthkey.domain.config.KeyStrokesConfig
import com.something.sthkey.domain.style.KeyLayout
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

/** 摇杆设置的三个分区。调用方负责判断"当前样式是不是手柄样式" */
internal fun LazyListScope.joystickStyleSections(
    editable: KeyStrokesConfig,
    applyChange: ((KeyStrokesConfig) -> KeyStrokesConfig) -> Unit,
) {
    val js = editable.joystick

    item { SectionHeaderText("摇杆") }

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

            SliderRow(
                label = "描边粗细",
                value = js.strokeWidthRatio,
                valueRange = 0f..0.1f,
                display = if (js.strokeWidthRatio <= 0f) {
                    "不显示"
                } else {
                    "%.1f‰".format(js.strokeWidthRatio * 1000f)
                },
                onValueChange = { v ->
                    applyChange { it.copy(joystick = it.joystick.copy(strokeWidthRatio = v)) }
                },
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

            SliderRow(
                label = "内圆粗细",
                value = js.ringWidthRatio,
                valueRange = 0f..0.06f,
                display = if (js.ringWidthRatio <= 0f) {
                    "不显示"
                } else {
                    "%.0f‰".format(js.ringWidthRatio * 1000f)
                },
                onValueChange = { v ->
                    applyChange { it.copy(joystick = it.joystick.copy(ringWidthRatio = v)) }
                },
            )
        }
    }

    item {
        SectionHint(
            text = "「摇杆」组管方框底盘、四条边框与盘内那个圆；" +
                "圆和描边的**粗细调到 0 就是隐藏它们**。",
        )
    }

    /* ============================================================
     * 摇杆帽
     * ============================================================ */

    item { SectionHeaderText("摇杆帽") }

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

            SliderRow(
                label = "摇杆帽描边粗细",
                value = js.knobStrokeWidthRatio,
                valueRange = 0f..0.3f,
                display = if (js.knobStrokeWidthRatio <= 0f) {
                    "不显示"
                } else {
                    "%.0f‰".format(js.knobStrokeWidthRatio * 1000f)
                },
                onValueChange = { v ->
                    applyChange { it.copy(joystick = it.joystick.copy(knobStrokeWidthRatio = v)) }
                },
            )
        }
    }

    /* ============================================================
     * 手感（**只影响显示**）
     * ============================================================ */

    item { SectionHeaderText("摇杆手感") }

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
            text = "「平滑」跟着 Axon 的弹簧曲线（起步有加速、停下有缓冲），" +
                "「精准」直接画手柄上报的原始位置、零延迟。",
        )
    }
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
                "平滑：跟着 Axon 的弹簧曲线，起步与停下都更柔和"
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
                        /* 平滑用 60ms（= Axon 原值），精准用 0 */
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

/** 点「平滑」时用的默认时长（毫秒）。60 = Axon 的原始手感 */
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
 * ⚠️ 关掉的键不该还列在键位映射里
 * ============================================================
 * 用户的原话:"shift 键我是关闭了的，但为什么还是出现"。
 *
 * 根因:`KeyMappingEditor` 只按传进来的 `hiddenSlotIds` 过滤，
 * 而这个调用点**根本没传** —— 于是「显示 Shift 键」关掉之后，
 * 悬浮窗上确实不画了，但键位映射列表里那一行**还在**，
 * 用户会以为开关没生效（而开关是好的）。
 *
 * ⚠️ 判据是**显示开关**，不是"这个映射存不存在":
 * 映射始终保留在配置里（关掉再打开，用户改过的绑定不该丢），
 * 变的只是**要不要给他看**。
 *
 * ⚠️ 摇杆槽位永远隐藏 —— 它不绑键码，让人去绑只会得到
 * "按了没反应"（摇杆上报的是**轴**，不是键码）。
 */
internal fun hiddenMappingSlots(config: KeyStrokesConfig): Set<String> = buildSet {
    if (!config.showShiftKey) add(KeyLayout.Id.SHIFT)
    if (!config.showSpaceKey) add(KeyLayout.Id.SPACE)
    if (!config.showAButton) add(KeyLayout.Id.A_BUTTON)
    if (!config.showShoulderButtons) {
        add(KeyLayout.Id.SHOULDER_L)
        add(KeyLayout.Id.SHOULDER_R)
    }
    if (!config.showMouseButtons) {
        add(KeyLayout.Id.LMB)
        add(KeyLayout.Id.RMB)
    }
    if (KeyLayout.usesJoystickLayout(config)) {
        add(KeyLayout.Id.JOYSTICK_LEFT)
        add(KeyLayout.Id.JOYSTICK_RIGHT)
    }
}

/*
 * ============================================================
 * 按键高度 / 按键间距 的范围
 * ============================================================
 * ⚠️ 与 `KeyLayout` 里的同名常量**必须一致** —— 滑块给得出、
 * 布局却夹掉的话，用户会看到"拖到底但没反应"。
 */

/** 按键高度：只改高度，不改宽度、不改间距 */
internal const val KEY_HEIGHT_PERCENT_MIN = 50f
internal const val KEY_HEIGHT_PERCENT_MAX = 200f

/**
 * 按键间距：**只改位置**。
 *
 * ⚠️ 下限是 **0**（键挨在一起），不是"把键缩小" ——
 * 用户专门纠正过:"不能调整组件大小，只是起到调整间距的效果，
 * 本质是改位置，尺寸不能改"。
 */
internal const val KEY_GAP_PERCENT_MIN = 0f
internal const val KEY_GAP_PERCENT_MAX = 400f

/** 分区标题（本文件内用，避免把 `SectionHeader` 的 import 也拖进来） */
@Composable
private fun SectionHeaderText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
    )
}

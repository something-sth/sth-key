package com.something.sthkey.ui.feature.custom

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.something.sthkey.domain.config.JoystickStyle
import com.something.sthkey.domain.custom.CustomComponent
import com.something.sthkey.domain.custom.JoystickComponent
import com.something.sthkey.domain.custom.StickSide
import com.something.sthkey.ui.component.HexColorRow
import com.something.sthkey.ui.feature.config.JOYSTICK_KNOB_SCALE_MAX
import com.something.sthkey.ui.feature.config.JOYSTICK_KNOB_SCALE_MIN
import com.something.sthkey.ui.feature.config.JOYSTICK_SENSITIVITY_MAX
import com.something.sthkey.ui.feature.config.JOYSTICK_SENSITIVITY_MIN
import com.something.sthkey.ui.feature.config.JOYSTICK_SIZE_SCALE_MAX
import com.something.sthkey.ui.feature.config.JOYSTICK_SIZE_SCALE_MIN
import com.something.sthkey.ui.feature.config.JOYSTICK_SMOOTHING_MAX

/*
 * ============================================================
 * 摇杆组件在属性面板里填的内容
 * ============================================================
 * ⚠️⚠️ **按"属性种类"分组，不按"零件"分组**
 * ============================================================
 * 用户的原话:"标准样式里那么做是为了区分，自定义是单独配置的，
 * 应该把圆角，透明度，描边这些分出来，而不是把摇杆，摇杆帽分出来，
 * 标题应该是圆角，透明度，颜色这些"。
 *
 * 所以这里**没有**"摇杆组"和"摇杆帽组"，而是填进已有的那几组:
 *
 * | 面板组 | 摇杆填什么 |
 * |---|---|
 * | 位置与尺寸 | X / Y / 宽 / 高 + 摇杆缩放 / 摇杆帽缩放 |
 * | 外观 | 圆角（底盘 + 帽）、底盘描边、帽描边、内圆粗细 |
 * | 颜色 | 摇杆 / 底盘描边 / 内圆 / 摇杆帽 / 帽描边 |
 * | 透明度 | 同「颜色」的五项 |
 * | 摇杆手感 | 死区 / 灵敏度 / 平滑·精准 |
 *
 * ⚠️ 组的**标题与顺序**与按键/文本组件完全一致 ——
 * 用户换一个组件时不需要重新找"圆角在哪一组"。
 *
 * ⚠️ 外观项与「标准」样式（`JoystickSections.kt`）**逐项相同**（用户要求），
 * 范围常量也复用那一份（`JOYSTICK_*`，它们是 `internal`）。
 */

/**
 * 「内容」栏:**只有一个分段按钮** —— 监听左摇杆还是右摇杆。
 *
 * 用户的原话:"'内容'栏的配置只保留一个分段按钮用来选择监听的是左摇杆还是右摇杆"。
 *
 * ⚠️ 不做"绑键码"那一套:摇杆上报的是**轴**，不是键码 ——
 * 与「键位映射」里"摇杆槽位不参与映射"是同一个理由。
 */
@Composable
internal fun JoystickContentSection(
    component: JoystickComponent,
    onComponentChange: (CustomComponent, Boolean) -> Unit,
) {
    JoystickSubLabel("监听的摇杆")

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StickSide.entries.forEach { side ->
            JoystickChoiceButton(
                label = side.label,
                selected = component.side == side,
                onClick = { onComponentChange(component.copy(side = side), true) },
            )
        }
    }

    JoystickHint("摇杆上报的是摇杆轴、不绑键码 —— 与「键位映射」里摇杆槽位不参与映射同一个理由。")
}

/**
 * 「位置与尺寸」组里摇杆额外的那两项缩放。
 *
 * 用户的原话:"摇杆缩放与摇杆帽缩放都整合在'位置与尺寸'里"。
 *
 * ⚠️ 放在这里而不是「外观」:它们改的是**大小**，与宽高是同一类事情。
 * 放到「外观」会让人以为它只是视觉微调。
 */
@Composable
internal fun JoystickScaleSliders(
    component: JoystickComponent,
    onComponentChange: (CustomComponent, Boolean) -> Unit,
) {
    val js = component.joystick

    JoystickSubLabel("缩放")

    JoystickSlider(
        label = "摇杆缩放",
        value = js.sizeScale,
        range = JOYSTICK_SIZE_SCALE_MIN..JOYSTICK_SIZE_SCALE_MAX,
        display = "%.2f×".format(js.sizeScale),
        component = component,
        onComponentChange = onComponentChange,
    ) { s, v -> s.copy(sizeScale = v) }

    JoystickSlider(
        label = "摇杆帽缩放",
        value = js.knobScale,
        range = JOYSTICK_KNOB_SCALE_MIN..JOYSTICK_KNOB_SCALE_MAX,
        display = "%.2f×".format(js.knobScale),
        component = component,
        onComponentChange = onComponentChange,
    ) { s, v -> s.copy(knobScale = v) }

    JoystickHint("缩放是相对组件宽高算的 —— 想整体变大，直接调上面的宽度/高度更直观。")
}

/**
 * 「外观」组里摇杆填的内容:**形状与线条**（圆角 / 描边 / 内圆粗细）。
 *
 * ⚠️ 颜色与透明度**不在这一组**（见 [JoystickColorSection] / [JoystickOpacitySection]）——
 * 与按键/文本组件同一套分工:外观管形状，颜色管色，透明度管透。
 */
@Composable
internal fun JoystickLookSection(
    component: JoystickComponent,
    onComponentChange: (CustomComponent, Boolean) -> Unit,
) {
    val js = component.joystick

    JoystickSubLabel("圆角")

    JoystickSlider(
        label = "底盘圆角",
        value = js.cornerRatio,
        range = 0f..0.5f,
        display = if (js.cornerRatio >= 0.499f) "正圆" else "${(js.cornerRatio * 200f).toInt()}%",
        component = component,
        onComponentChange = onComponentChange,
    ) { s, v -> s.copy(cornerRatio = v) }

    JoystickSlider(
        label = "摇杆帽圆角",
        value = js.knobCornerRatio,
        range = 0f..0.5f,
        display = if (js.knobCornerRatio >= 0.499f) {
            "正圆"
        } else {
            "${(js.knobCornerRatio * 200f).toInt()}%"
        },
        component = component,
        onComponentChange = onComponentChange,
    ) { s, v -> s.copy(knobCornerRatio = v) }

    JoystickSubLabel("底盘描边（方框的四条边）")

    JoystickSlider(
        label = "描边粗细",
        value = js.strokeWidthRatio,
        range = 0f..0.1f,
        display = if (js.strokeWidthRatio <= 0f) {
            "不显示"
        } else {
            "%.1f‰".format(js.strokeWidthRatio * 1000f)
        },
        component = component,
        onComponentChange = onComponentChange,
    ) { s, v -> s.copy(strokeWidthRatio = v) }

    JoystickSubLabel("内圆（盘内的活动轨道）")

    JoystickSlider(
        label = "内圆粗细",
        value = js.ringWidthRatio,
        range = 0f..0.06f,
        display = if (js.ringWidthRatio <= 0f) "不显示" else "%.0f‰".format(js.ringWidthRatio * 1000f),
        component = component,
        onComponentChange = onComponentChange,
    ) { s, v -> s.copy(ringWidthRatio = v) }

    JoystickSubLabel("摇杆帽描边")

    JoystickSlider(
        label = "帽描边粗细",
        value = js.knobStrokeWidthRatio,
        range = 0f..0.3f,
        display = if (js.knobStrokeWidthRatio <= 0f) {
            "不显示"
        } else {
            "%.0f‰".format(js.knobStrokeWidthRatio * 1000f)
        },
        component = component,
        onComponentChange = onComponentChange,
    ) { s, v -> s.copy(knobStrokeWidthRatio = v) }

    JoystickHint("**粗细调到 0 就是隐藏**对应的线（描边 / 内圆 / 帽描边都适用）。")
}

/**
 * 「颜色」组里摇杆填的内容。
 *
 * ⚠️ 与按键/文本的「颜色」组**同一个位置、同一个标题** ——
 * 换组件时"颜色在这一组"这件事不变。
 *
 * ⚠️ 顺序与 [JoystickOpacitySection] **完全一致**（底盘 → 描边 → 内圆 → 帽 → 帽描边），
 * 与按键/文本那边的做法相同:两个分组之间对照时不需要重新找位置。
 */
@Composable
internal fun JoystickColorSection(
    component: JoystickComponent,
    onComponentChange: (CustomComponent, Boolean) -> Unit,
) {
    val js = component.joystick

    JoystickColorRow(
        label = "摇杆",
        color = js.color,
        component = component,
        onComponentChange = onComponentChange,
    ) { s, c -> s.copy(color = c) }

    JoystickColorRow(
        label = "底盘描边",
        color = js.strokeColor,
        component = component,
        onComponentChange = onComponentChange,
    ) { s, c -> s.copy(strokeColor = c) }

    JoystickColorRow(
        label = "内圆",
        color = js.ringColor,
        component = component,
        onComponentChange = onComponentChange,
    ) { s, c -> s.copy(ringColor = c) }

    JoystickColorRow(
        label = "摇杆帽",
        color = js.knobColor,
        component = component,
        onComponentChange = onComponentChange,
    ) { s, c -> s.copy(knobColor = c) }

    JoystickColorRow(
        label = "帽描边",
        color = js.knobStrokeColor,
        component = component,
        onComponentChange = onComponentChange,
    ) { s, c -> s.copy(knobStrokeColor = c) }

    JoystickHint("颜色只填 6 位十六进制（例如 FF8800），透明度用「透明度」那一组单独调。")
}

/**
 * 「透明度」组里摇杆填的内容。
 *
 * ⚠️ 顺序与 [JoystickColorSection] **完全一致** —— 两个分组之间对照时不用重新找。
 */
@Composable
internal fun JoystickOpacitySection(
    component: JoystickComponent,
    onComponentChange: (CustomComponent, Boolean) -> Unit,
) {
    val js = component.joystick

    JoystickSlider(
        label = "摇杆",
        value = js.opacity,
        range = 0f..1f,
        display = "${(js.opacity * 100f).toInt()}%",
        component = component,
        onComponentChange = onComponentChange,
    ) { s, v -> s.copy(opacity = v) }

    JoystickSlider(
        label = "底盘描边",
        value = js.strokeOpacity,
        range = 0f..1f,
        display = if (js.strokeOpacity <= 0f) "不显示" else "${(js.strokeOpacity * 100f).toInt()}%",
        component = component,
        onComponentChange = onComponentChange,
    ) { s, v -> s.copy(strokeOpacity = v) }

    JoystickSlider(
        label = "内圆",
        value = js.ringOpacity,
        range = 0f..1f,
        display = if (js.ringOpacity <= 0f) "不显示" else "${(js.ringOpacity * 100f).toInt()}%",
        component = component,
        onComponentChange = onComponentChange,
    ) { s, v -> s.copy(ringOpacity = v) }

    JoystickSlider(
        label = "摇杆帽",
        value = js.knobOpacity,
        range = 0f..1f,
        display = "${(js.knobOpacity * 100f).toInt()}%",
        component = component,
        onComponentChange = onComponentChange,
    ) { s, v -> s.copy(knobOpacity = v) }

    JoystickSlider(
        label = "帽描边",
        value = js.knobStrokeOpacity,
        range = 0f..1f,
        display = if (js.knobStrokeOpacity <= 0f) {
            "不显示"
        } else {
            "${(js.knobStrokeOpacity * 100f).toInt()}%"
        },
        component = component,
        onComponentChange = onComponentChange,
    ) { s, v -> s.copy(knobStrokeOpacity = v) }
}

/**
 * 「摇杆手感」组:死区、灵敏度、平滑·精准。
 *
 * ⚠️ 这一组是摇杆**独有**的（按键/文本没有"手感"可言），所以它单独成组。
 *
 * ⚠️ **只影响悬浮窗的显示**，不改变游戏收到的摇杆输入 —— 界面上写明了
 * （与「标准」样式那边完全一致）。
 */
@Composable
internal fun JoystickFeelSection(
    component: JoystickComponent,
    onComponentChange: (CustomComponent, Boolean) -> Unit,
) {
    val js = component.joystick

    JoystickHint("以下几项只改变悬浮窗上摇杆画到哪，不会改变游戏收到的摇杆输入。")

    JoystickSlider(
        label = "死区",
        value = js.deadZone,
        range = 0f..0.5f,
        display = if (js.deadZone <= 0f) "关闭" else "%.0f%%".format(js.deadZone * 100f),
        component = component,
        onComponentChange = onComponentChange,
    ) { s, v -> s.copy(deadZone = v) }

    JoystickSlider(
        label = "灵敏度",
        value = js.sensitivity,
        range = JOYSTICK_SENSITIVITY_MIN..JOYSTICK_SENSITIVITY_MAX,
        display = "%.2f×".format(js.sensitivity),
        component = component,
        onComponentChange = onComponentChange,
    ) { s, v -> s.copy(sensitivity = v) }

    JoystickSubLabel("显示方式")

    /* 平滑用 60ms，精准用 0 —— 与「标准」样式同一套取值 */
    val smoothingSelected = js.smoothingMs > 0f

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        JoystickChoiceButton(
            label = "平滑",
            selected = smoothingSelected,
            onClick = {
                onComponentChange(
                    component.copy(joystick = js.copy(smoothingMs = JOYSTICK_DEFAULT_SMOOTHING_MS)),
                    true,
                )
            },
        )
        JoystickChoiceButton(
            label = "精准",
            selected = !smoothingSelected,
            onClick = {
                onComponentChange(component.copy(joystick = js.copy(smoothingMs = 0f)), true)
            },
        )
    }

    JoystickHint(
        if (smoothingSelected) {
            "平滑：起步与停下都更柔和"
        } else {
            "精准：直接显示手柄上报的原始位置，没有任何延迟"
        },
    )

    /* 选了「平滑」之后才细调时间 —— 精准模式没有"平滑时间"可言 */
    if (smoothingSelected) {
        JoystickSlider(
            label = "平滑时间",
            value = js.smoothingMs,
            range = 10f..JOYSTICK_SMOOTHING_MAX,
            display = "%.0f ms".format(js.smoothingMs),
            component = component,
            onComponentChange = onComponentChange,
        ) { s, v -> s.copy(smoothingMs = v) }
    }
}

/* ============================================================
 * 小组件
 * ============================================================ */

/**
 * 摇杆外观滑块。
 *
 * ⚠️ 拖动过程中产生的几十次改动**不该各算一步撤回** ——
 * 所以固定传 `false`（属性面板的撤回粒度由它自己的 onBegin/onEnd 负责，
 * 而这里每一下都是独立的原子改动）。
 */
@Composable
private fun JoystickSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    component: JoystickComponent,
    onComponentChange: (CustomComponent, Boolean) -> Unit,
    apply: (JoystickStyle, Float) -> JoystickStyle,
) {
    SliderRow(
        label = label,
        value = value,
        range = range,
        display = display,
        onBegin = {},
        onChange = { v ->
            onComponentChange(component.copy(joystick = apply(component.joystick, v)), false)
        },
        onEnd = {},
    )
}

/** 摇杆的一个颜色行（只存 24 位 RGB，透明度由独立滑块管 —— 与项目其它地方一致） */
@Composable
private fun JoystickColorRow(
    label: String,
    color: Int,
    component: JoystickComponent,
    onComponentChange: (CustomComponent, Boolean) -> Unit,
    apply: (JoystickStyle, Int) -> JoystickStyle,
) {
    HexColorRow(
        label = label,
        /* `and 0xFFFFFFFFL` 把有符号 Int 当成无符号色彩值传给控件 */
        value = color.toLong() and 0xFFFFFFFFL,
        onValueChange = { v ->
            onComponentChange(component.copy(joystick = apply(component.joystick, v.toInt())), true)
        },
    )
}

/**
 * 分段按钮（左右摇杆 / 平滑精准 共用同一套外观）。
 *
 * ⚠️ 声明成 `RowScope` 的扩展函数:里面用了 `Modifier.weight(1f)` 让按钮**等宽**，
 * 而 `weight` 只在 `RowScope` 里可见 —— 写成普通 `@Composable` 会编译不过。
 * 这也顺带保证了它**只能**放进 Row 里用。
 */
@Composable
private fun RowScope.JoystickChoiceButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.weight(1f),
        colors = if (selected) {
            ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            )
        } else {
            ButtonDefaults.outlinedButtonColors()
        },
    ) {
        Text(label)
    }
}

/** 小节标题（与属性面板里的 `SubLabel` 同一套外观） */
@Composable
private fun JoystickSubLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp),
    )
}

/** 说明文字 */
@Composable
private fun JoystickHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp),
    )
}

/** 点「平滑」时用的默认时长（毫秒）。*/
private const val JOYSTICK_DEFAULT_SMOOTHING_MS = 60f

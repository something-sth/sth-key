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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.something.sthkey.domain.config.JoystickStyle
import com.something.sthkey.domain.custom.JoystickSource
import com.something.sthkey.domain.custom.JOYSTICK_THRESHOLD_MIN
import com.something.sthkey.domain.custom.JOYSTICK_SMOOTHING_MAX_MS
import com.something.sthkey.ui.component.EditableSliderRow
import com.something.sthkey.domain.custom.JoystickFollowMode
import com.something.sthkey.domain.custom.JoystickDirection
import com.something.sthkey.domain.custom.JOYSTICK_FOLLOW_DURATION_MIN
import com.something.sthkey.domain.custom.JOYSTICK_FOLLOW_DURATION_MAX
import androidx.compose.material3.TextButton
import com.something.sthkey.domain.keys.KeyCodes
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
 * 「内容」栏 —— **按输入源分三种**。
 *
 * | 输入源 | 内容栏里有什么 |
 * |---|---|
 * | 手柄 | 一个分段按钮:监听左摇杆还是右摇杆 |
 * | **键盘** | **四个键位映射**（上 / 左 / 下 / 右） |
 * | 鼠标 | **没有可选项**（它固定监听鼠标位移），只有一句说明 |
 *
 * 用户的原话（手柄那一版）:"'内容'栏的配置只保留一个分段按钮用来选择
 * 监听的是左摇杆还是右摇杆"。
 *
 * ============================================================
 * ⚠️⚠️ 键位映射属于**这一栏**，不是「摇杆手感」（我放错过一次）
 * ============================================================
 * 用户的原话:
 *
 * > 内容那一块加**四个键位映射**的组件，分别是 WASD，因为这**不能写死**，
 * > 有的用户可能会用别的按键…… 而这应该加在**内容栏**，
 * > 你加在摇杆手感栏干什么？让你参考，你就给我乱写
 *
 * ⚠️ 判据很清楚:「内容」栏回答的是"这个组件**监听什么**"，
 * 「摇杆手感」回答的是"画出来是什么**感觉**"。键位映射是前者。
 *
 * ⚠️ 与手柄那个"监听左/右摇杆"**语义上完全对应** —— 都是"输入从哪来"，
 * 只是一个选轴、一个选键。所以两者都放在「内容」栏的第一段，
 * 换组件类型时"输入配置在哪一组"这件事不变。
 *
 * ⚠️ 不做"绑键码"那一套（那是键位映射面板的事）:摇杆上报的是
 * **轴 / 归一化向量**，不参与「键位映射」里那个槽位映射 ——
 * 与"摇杆槽位不参与映射"是同一个理由。
 */
@Composable
internal fun JoystickContentSection(
    component: JoystickComponent,
    onComponentChange: (CustomComponent, Boolean) -> Unit,
    onPickJoystickKey: (JoystickComponent, Int) -> Unit,
) {
    when (component.source) {
        JoystickSource.GAMEPAD -> {
            JoystickSubLabel("监听的摇杆")

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StickSide.entries.forEach { side ->
                    JoystickChoiceButton(
                        /*
                         * ⚠️ 用「**手柄**左摇杆」而不是「左摇杆」——
                         * 用户的原话:"现在只是左摇杆、右摇杆，改成手柄左摇杆、
                         * 手柄右摇杆"。
                         *
                         * ⚠️ 前缀在这里加（而不是改 `StickSide.label`）:
                         * 那个枚举**只描述手柄的左右侧**，"加不加设备前缀"
                         * 是**界面措辞**的问题，不该写进数据模型。
                         * 而且 `label` 还被别处当"纯粹的左右"用。
                         */
                        label = "手柄${side.label}",
                        selected = component.side == side,
                        onClick = { onComponentChange(component.copy(side = side), true) },
                    )
                }
            }

            JoystickHint(
                "摇杆上报的是摇杆轴、不绑键码 —— 与「键位映射」里摇杆槽位不参与映射同一个理由。",
            )
        }

        JoystickSource.KEYBOARD -> {
            JoystickSubLabel("键位映射")

            /*
             * ⚠️ 顺序固定是 上 / 左 / 下 / 右（[JoystickDirection] 的声明顺序）——
             * 那个顺序也是存档里数组的顺序，**不能改**。
             */
            JoystickDirection.all.forEachIndexed { index, direction ->
                /*
                 * ⚠️ 显示**这一向绑的所有键**（用 `、` 连起来）——
                 * 数据模型是"每一向一组键"，显示成单个键会让人以为只能绑一个。
                 */
                val codes = component.inputKeyCodes.getOrNull(index).orEmpty()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = direction.label,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onPickJoystickKey(component, index) }) {
                        Text(
                            text = if (codes.isEmpty()) {
                                "（未绑定）"
                            } else {
                                codes.joinToString("、") { KeyCodes.displayName(it) }
                            },
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            JoystickHint(
                "按一个键就是那个正方向，按相邻两个键就是中间的斜向；" +
                    "相对的两个键同时按会互相抵消。" +
                    "按哪个键、走多快，由「摇杆手感」里那几项决定。",
            )
        }

        JoystickSource.MOUSE -> JoystickHint(
            "固定监听鼠标位移:鼠标往哪移，摇杆帽就往哪偏。" +
                "灵敏度与平滑在「摇杆手感」里。",
        )
    }
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

    /*
     * ⚠️ 缩放是**倍率**，数字本身就是数据（`1.50×` 里的 `1.50`）——
     * 没有"显示单位 ≠ 存储单位"的问题，所以用普通 [SliderRow]，
     * 不需要走那个带换算的 [JoystickSlider]。
     */
    SliderRow(
        label = "摇杆缩放",
        value = js.sizeScale,
        range = JOYSTICK_SIZE_SCALE_MIN..JOYSTICK_SIZE_SCALE_MAX,
        display = "%.2f×".format(js.sizeScale),
        onBegin = {},
        onChange = { v ->
            onComponentChange(component.copy(joystick = js.copy(sizeScale = v)), false)
        },
        onEnd = {},
    )

    SliderRow(
        label = "摇杆帽缩放",
        value = js.knobScale,
        range = JOYSTICK_KNOB_SCALE_MIN..JOYSTICK_KNOB_SCALE_MAX,
        display = "%.2f×".format(js.knobScale),
        onBegin = {},
        onChange = { v ->
            onComponentChange(component.copy(joystick = js.copy(knobScale = v)), false)
        },
        onEnd = {},
    )

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

    /*
     * ⚠️ 描边 / 内圆的宽度按**边长**算（渲染时就是 `边长 × 比例`），
     * 而摇杆帽描边按**帽直径**算 —— 见 `Joystick.kt` 里那几个公式。
     *
     * ⚠️ 这里的单位是**基础坐标**，它与 dp 是 1:1
     * （组件的宽高本身就是基础坐标，用户看到的数字就是 dp）。
     */
    val sideDp = minOf(component.width, component.height)
    val knobDiameterDp = sideDp * js.knobScale

    JoystickSubLabel("圆角")

    JoystickSlider(
        label = "底盘圆角",
        style = js,
        value = js.cornerRatio,
        displayRange = 0f..50f,
        toRatio = { it / 100f },
        fromRatio = { it * 100f },
        applyRatio = { s, r -> s.copy(cornerRatio = r) },
        suffix = "%",
        displayText = { shown ->
            if (shown >= 49.9f) "正圆" else "${shown.toInt()}%"
        },
        onStyleChange = { next ->
            onComponentChange(component.copy(joystick = next), false)
        },
    )

    JoystickSlider(
        label = "摇杆帽圆角",
        style = js,
        value = js.knobCornerRatio,
        displayRange = 0f..50f,
        toRatio = { it / 100f },
        fromRatio = { it * 100f },
        applyRatio = { s, r -> s.copy(knobCornerRatio = r) },
        suffix = "%",
        displayText = { shown ->
            if (shown >= 49.9f) "正圆" else "${shown.toInt()}%"
        },
        onStyleChange = { next ->
            onComponentChange(component.copy(joystick = next), false)
        },
    )

    /*
     * ============================================================
     * ⚠️ 三个宽度用 **dp**，不再用千分号
     * ============================================================
     * 用户的原话:"别的组件描边宽度单位都是 dp，只有摇杆组件描边粗细的单位是
     * 千分号……不要让摇杆组件单独用一套单位，不然很迷"。
     *
     * ⚠️ 存储仍然是**比例**（描边要跟着组件尺寸缩放，组件能从 20 变到 1000），
     * 但**显示与输入都换算成 dp** —— 与按键/文本组件的「描边宽度」完全一致。
     */
    JoystickSubLabel("底盘描边（方框的四条边）")

    JoystickWidthSlider(
        label = "描边粗细",
        style = js,
        value = js.strokeWidthRatio,
        baseDp = sideDp,
        applyRatio = { s, r -> s.copy(strokeWidthRatio = r) },
        maxDp = JoystickStyle.STROKE_WIDTH_MAX_DP,
        onStyleChange = { next -> onComponentChange(component.copy(joystick = next), false) },
    )

    JoystickSubLabel("内圆（盘内的活动轨道）")

    JoystickWidthSlider(
        label = "内圆粗细",
        style = js,
        value = js.ringWidthRatio,
        baseDp = sideDp,
        applyRatio = { s, r -> s.copy(ringWidthRatio = r) },
        maxDp = JoystickStyle.RING_WIDTH_MAX_DP,
        onStyleChange = { next -> onComponentChange(component.copy(joystick = next), false) },
    )

    JoystickSubLabel("摇杆帽描边")

    JoystickWidthSlider(
        label = "摇杆帽描边粗细",
        style = js,
        value = js.knobStrokeWidthRatio,
        /* ⚠️ 帽描边的基数是**帽直径**，不是边长 —— 与渲染里的公式保持一致 */
        baseDp = knobDiameterDp,
        applyRatio = { s, r -> s.copy(knobStrokeWidthRatio = r) },
        maxDp = JoystickStyle.KNOB_STROKE_WIDTH_MAX_DP,
        onStyleChange = { next -> onComponentChange(component.copy(joystick = next), false) },
    )

    JoystickHint("粗细调到 0 就是隐藏对应的线（描边 / 内圆 / 帽描边都适用）。")
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
        label = "摇杆帽描边",
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
        style = js,
        value = js.opacity,
        displayRange = 0f..100f,
        toRatio = { it / 100f },
        fromRatio = { it * 100f },
        applyRatio = { s, r -> s.copy(opacity = r) },
        suffix = "%",
        displayText = { shown -> "${shown.toInt()}%" },
        onStyleChange = { next -> onComponentChange(component.copy(joystick = next), false) },
    )

    JoystickSlider(
        label = "底盘描边",
        style = js,
        value = js.strokeOpacity,
        displayRange = 0f..100f,
        toRatio = { it / 100f },
        fromRatio = { it * 100f },
        applyRatio = { s, r -> s.copy(strokeOpacity = r) },
        suffix = "%",
        displayText = { shown -> if (shown <= 0f) "不显示" else "${shown.toInt()}%" },
        onStyleChange = { next -> onComponentChange(component.copy(joystick = next), false) },
    )

    JoystickSlider(
        label = "内圆",
        style = js,
        value = js.ringOpacity,
        displayRange = 0f..100f,
        toRatio = { it / 100f },
        fromRatio = { it * 100f },
        applyRatio = { s, r -> s.copy(ringOpacity = r) },
        suffix = "%",
        displayText = { shown -> if (shown <= 0f) "不显示" else "${shown.toInt()}%" },
        onStyleChange = { next -> onComponentChange(component.copy(joystick = next), false) },
    )

    JoystickSlider(
        label = "摇杆帽",
        style = js,
        value = js.knobOpacity,
        displayRange = 0f..100f,
        toRatio = { it / 100f },
        fromRatio = { it * 100f },
        applyRatio = { s, r -> s.copy(knobOpacity = r) },
        suffix = "%",
        displayText = { shown -> "${shown.toInt()}%" },
        onStyleChange = { next -> onComponentChange(component.copy(joystick = next), false) },
    )

    JoystickSlider(
        label = "摇杆帽描边",
        style = js,
        value = js.knobStrokeOpacity,
        displayRange = 0f..100f,
        toRatio = { it / 100f },
        fromRatio = { it * 100f },
        applyRatio = { s, r -> s.copy(knobStrokeOpacity = r) },
        suffix = "%",
        displayText = { shown -> if (shown <= 0f) "不显示" else "${shown.toInt()}%" },
        onStyleChange = { next -> onComponentChange(component.copy(joystick = next), false) },
    )
}

/**
 * 「摇杆手感」组 —— **按输入源分三种**。
 *
 * ⚠️ 这一组是摇杆**独有**的（按键/文本没有"手感"可言），所以它单独成组。
 *
 * ⚠️ **只影响悬浮窗的显示**，不改变游戏收到的输入 —— 界面上写明了
 * （与「标准」样式那边完全一致）。
 *
 * ============================================================
 * ⚠️⚠️ 为什么三种摇杆**各有一套手感**、而不是共用 [JoystickStyle] 那三个
 * ============================================================
 * 用户的原话:"具体配置项不建议全部参考手柄摇杆，UI 层面的描边、颜色之类的
 * 可以复用，**具体的手感之类配置项得单独分出来**"。
 *
 * 而"单独分出来"不只是分类问题 —— 那三个字段在键鼠上**根本没有意义**:
 *
 * | 字段 | 手柄 | 键盘（八段式） | 鼠标 |
 * |---|---|---|---|
 * | 死区 | 轴漂移的死区 | ❌ 离散方向，没有漂移 | ❌ 同理 |
 * | 灵敏度 | 轴值放大 | ❌ 只有 8 个方向，放大不改方向 | ✅ 位移→偏移的倍率 |
 * | 平滑 | 弹簧软硬 | ✅ 有意义 | ✅ 有意义 |
 *
 * ⚠️ 所以共用一个字段的话，用户会在键盘摇杆上看到"死区"和"灵敏度"两个
 * **改了完全没反应**的滑块 —— 那种"骗人设置"是本项目明确要避免的。
 */
@Composable
internal fun JoystickFeelSection(
    component: JoystickComponent,
    onComponentChange: (CustomComponent, Boolean) -> Unit,
    onPickJoystickKey: (JoystickComponent, Int) -> Unit,
) {
    JoystickHint("以下几项只改变悬浮窗上摇杆画到哪，不会改变游戏收到的输入。")

    when (component.source) {
        JoystickSource.GAMEPAD -> GamepadJoystickFeel(component, onComponentChange)
        JoystickSource.KEYBOARD -> KeyboardJoystickFeelSliders(
            component,
            onComponentChange,
            onPickJoystickKey,
        )
        JoystickSource.MOUSE -> MouseJoystickFeelSliders(component, onComponentChange)
    }
}

/**
 * 「摇杆-键盘」的手感:八段式阈值 + 平滑时间。
 *
 * ⚠️ 参考实现里 WASD 那一套阈值是 `0.35`（理由:"手柄摇杆的物理死区通常在 0.2
 * 左右，阈值设到 0.5 玩家要推很大力才点亮"）。
 *
 * ⚠️ 但**那个理由在本项目里不成立**:这里是"按了哪个键"（数字信号），
 * 不是"轴推到了多少"（模拟量）—— 按下去就是 1，没有"推得轻"这回事。
 * 所以默认取 `0.5f`（严格八段式:按一个键就是正方向，按两个相邻键就是 45°）。
 */
@Composable
private fun KeyboardJoystickFeelSliders(
    component: JoystickComponent,
    onComponentChange: (CustomComponent, Boolean) -> Unit,
    onPickJoystickKey: (JoystickComponent, Int) -> Unit,
) {
    val feel = component.keyboardFeel

    /*
     * ⚠️ 分段按钮放在最上面 —— 它决定下面第二个滑块是哪一个
     * （用户明确说了"这两个滑块**位置相同**"）。
     */
    JoystickSubLabel("跟随方式")

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        JoystickFollowMode.entries.forEach { mode ->
            JoystickChoiceButton(
                label = mode.label,
                selected = feel.mode == mode,
                onClick = {
                    onComponentChange(component.copy(keyboardFeel = feel.copy(mode = mode)), true)
                },
            )
        }
    }

    /* ⚠️ 方向阈值**两种模式共用**（用户明确要求:"方向阈值是共用的"） */
    EditableSliderRow(
        label = "方向阈值",
        value = feel.threshold * 100f,
        range = JOYSTICK_THRESHOLD_MIN * 100f..100f,
        display = "${(feel.threshold * 100f).toInt()}%",
        step = 1f,
        suffix = "%",
        onValueChange = { shown ->
            onComponentChange(
                component.copy(keyboardFeel = feel.copy(threshold = shown / 100f)),
                false,
            )
        },
        modifier = Modifier.padding(horizontal = 4.dp),
    )

    /*
     * ============================================================
     * ⚠️ 第二个滑块:**位置相同，含义按模式换**
     * ============================================================
     * 用户的原话:
     *
     * > 常规模式也可以调整方向阈值，不过平滑时间的滑块改为……emm我也不知道
     * > 改什么好，但含义都是**中心到摇杆帽到指定位置时的时长**吧，你看着办。
     * > 注意别理解错我意思，平滑模式用"平滑时间"，常规模式的滑块你自己想一个，
     * > **这两个滑块位置相同**
     *
     * ⚠️ 所以命名按"这个模式里它到底控制什么":
     *
     * | 模式 | 标签 | 含义 |
     * |---|---|---|
     * | 平滑 | 「平滑时间」 | 弹簧有多**软** —— 间接影响快慢 |
     * | 常规 | 「响应时间」 | **直接**就是走完这段距离的时长 |
     *
     * ⚠️ 写成 `when` 里的两个滑块、而不是一个共用控件 —— 因为两者的
     * **取值范围都不一样**（平滑 0..300，响应 20..600），
     * 合并要引入"范围也跟着模式变"的间接层，反而更容易出错。
     */
    when (feel.mode) {
        JoystickFollowMode.SMOOTH -> SliderRow(
            label = "平滑时间",
            value = feel.smoothingMs,
            range = 0f..JOYSTICK_SMOOTHING_MAX_MS,
            display = if (feel.smoothingMs <= 0f) "关闭" else "%.0f ms".format(feel.smoothingMs),
            onBegin = {},
            onChange = { v ->
                onComponentChange(
                    component.copy(keyboardFeel = feel.copy(smoothingMs = v)),
                    false,
                )
            },
            onEnd = {},
        )

        JoystickFollowMode.REGULAR -> SliderRow(
            label = "响应时间",
            value = feel.durationMs,
            range = JOYSTICK_FOLLOW_DURATION_MIN..JOYSTICK_FOLLOW_DURATION_MAX,
            display = "%.0f ms".format(feel.durationMs),
            /*
             * ⚠️ **1 毫秒一档** —— 用户的原话:
             *
             * > 470是我滑块随便拖的值，因为**滑块没有设置最小单位是1**
             * > 所以会有一堆小数
             *
             * ⚠️ 默认的 `stepOf` 按"范围宽度 / 100"算，这里是 580/100 = 5.8，
             * 于是拖出来 `470.88318` 这种值 —— **存进配置、界面还显示不出来**。
             *
             * ⚠️ `steps` 是"档位之间的间隔数"，可停靠位置有 `steps + 1` 个。
             * 想让跨度里每 1ms 一档，就是 `跨度 - 1`
             * （与 `ConfigEditorScreen` 里那个「整体缩放」滑块同一个算法）。
             */
            steps = (JOYSTICK_FOLLOW_DURATION_MAX - JOYSTICK_FOLLOW_DURATION_MIN).toInt() - 1,
            onBegin = {},
            onChange = { v ->
                onComponentChange(component.copy(keyboardFeel = feel.copy(durationMs = v)), false)
            },
            onEnd = {},
        )
    }

    /*
     * ⚠️⚠️ 键位映射**不在这一组** —— 它在「内容」栏。
     *
     * 用户的原话:"这应该加在**内容栏**，你加在摇杆手感栏干什么？
     * 让你参考，你就给我乱写"。
     *
     * ⚠️ 他说得对:键位映射回答的是"这个组件**监听什么**"，与「内容」栏的
     * 语义完全一致；而「摇杆手感」管的是"画出来是什么感觉"。我放错了组，
     * 看起来就像随手塞进去的。现在见 [JoystickContentSection]。
     */

    JoystickHint(
        "方向阈值越小，斜向越容易触发。" +
            when (feel.mode) {
                JoystickFollowMode.SMOOTH ->
                    "平滑时间调小则跟手更干脆，调大则摆动更柔和（带惯性）。"
                JoystickFollowMode.REGULAR ->
                    "响应时间 = 摇杆帽从中心走到满偏要多久；这个模式**全程匀速**。"
            },
    )
}

/**
 * 「摇杆-鼠标」的手感:灵敏度 + **回中延迟** + 回中时长 + 平滑时间。
 *
 * ⚠️ **回中延迟**是用户特意要求的一项，原话:
 *
 * > 回中不能"瞬间拐弯"，否则用户稍微停了一小下鼠标就被拉回去了，
 * > 当然这方面可以做**自动回中时间（ms）**，就是写**鼠标停止移动多久后
 * > 开始进行回中**
 *
 * ⚠️ 它是"多久之后**开始**回中"，不是"多久回完" —— 后者是下面那项。
 * 两者分开是刻意的:用户要调的是"停多久别拉我"，而回中的快慢是另一回事。
 */
@Composable
private fun MouseJoystickFeelSliders(
    component: JoystickComponent,
    onComponentChange: (CustomComponent, Boolean) -> Unit,
) {
    val feel = component.mouseFeel

    /*
     * ⚠️ 灵敏度**显示成"满偏需要多少累计位移"的反向刻度**是没必要的 ——
     * 直接把倍率显示成三位小数就够（`0.005`），单位在提示里说明。
     *
     * ⚠️ 它的范围极窄（`0.0005..0.02`），粒度靠 [stepOf] 的
     * "至少 100 档"规则兜住 —— 见那个函数的说明。
     */
    SliderRow(
        label = "灵敏度",
        value = feel.sensitivity,
        range = JOYSTICK_SENSITIVITY_MIN..JOYSTICK_SENSITIVITY_MAX,
        display = "%.4f".format(feel.sensitivity),
        onBegin = {},
        onChange = { v ->
            onComponentChange(component.copy(mouseFeel = feel.copy(sensitivity = v)), false)
        },
        onEnd = {},
    )



    SliderRow(
        label = "平滑时间",
        value = feel.smoothingMs,
        range = 0f..JOYSTICK_SMOOTHING_MAX_MS,
        display = if (feel.smoothingMs <= 0f) "关闭" else "%.0f ms".format(feel.smoothingMs),
        onBegin = {},
        onChange = { v ->
            onComponentChange(component.copy(mouseFeel = feel.copy(smoothingMs = v)), false)
        },
        onEnd = {},
    )

    JoystickHint(
        "鼠标移动时摇杆帽跟着偏，停下后就停在原地（**没有自动回中** —— " +
            "那个功能先撤掉了，见代码里的说明）。灵敏度调大偏得更快、调小更稳。",
    )
}

/** 手柄摇杆的手感（原来那一套，**行为完全不变**） */
@Composable
private fun GamepadJoystickFeel(
    component: JoystickComponent,
    onComponentChange: (CustomComponent, Boolean) -> Unit,
) {
    val js = component.joystick

    JoystickSlider(
        label = "死区",
        style = js,
        value = js.deadZone,
        displayRange = 0f..50f,
        toRatio = { it / 100f },
        fromRatio = { it * 100f },
        applyRatio = { s, r -> s.copy(deadZone = r) },
        suffix = "%",
        displayText = { shown -> if (shown <= 0f) "关闭" else "%.0f%%".format(shown) },
        onStyleChange = { next -> onComponentChange(component.copy(joystick = next), false) },
    )

    /* ⚠️ 灵敏度是**倍率**（1.00×），数字本身就是数据，没有单位换算 —— 用普通 SliderRow */
    SliderRow(
        label = "灵敏度",
        value = js.sensitivity,
        range = JOYSTICK_SENSITIVITY_MIN..JOYSTICK_SENSITIVITY_MAX,
        display = "%.2f×".format(js.sensitivity),
        onBegin = {},
        onChange = { v ->
            onComponentChange(component.copy(joystick = js.copy(sensitivity = v)), false)
        },
        onEnd = {},
    )

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
        /* ⚠️ 平滑时间的数字本身就是毫秒数，没有单位换算 —— 用普通 SliderRow */
        SliderRow(
            label = "平滑时间",
            value = js.smoothingMs,
            range = 10f..JOYSTICK_SMOOTHING_MAX,
            display = "%.0f ms".format(js.smoothingMs),
            onBegin = {},
            onChange = { v ->
                onComponentChange(component.copy(joystick = js.copy(smoothingMs = v)), false)
            },
            onEnd = {},
        )
    }
}

/* ============================================================
 * 小组件
 * ============================================================ */

/*
 * ⚠️ 这里**原本有一个私有的 `JoystickSlider`**（直接包一层 `SliderRow`），
 * 已经删掉 —— 它只做了"把显示文本传下去"，**不做单位换算**，
 * 于是"滑块显示 45%、输入框里是 0.225"。
 *
 * 现在统一用 `ComponentProperties.kt` 里那个**带换算**的 [JoystickSlider]：
 * 滑块工作在显示单位上，进出各换算一次，两边的数字自然一致。
 */

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



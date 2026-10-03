package com.something.sthkey.ui.component

import androidx.compose.runtime.Composable
import com.something.sthkey.domain.config.KeyStrokesConfig
import com.something.sthkey.domain.config.TextOffset
import com.something.sthkey.domain.config.TextSpacing

/**
 * 每个按键**各自**的文字偏移。
 *
 * ============================================================
 * 为什么需要它（不是"全局偏移"能替代的）
 * ============================================================
 * 用户实测的场景：空格位显示的是 `————` 这种**非 ASCII** 的横线，
 * 它不会被图片字体替换（图片字体只有 ASCII 字形），走的是矢量字体。
 * 两种字体的基线位置不同 —— 把全局偏移往下调让横线好看了，
 * 那些**真正用图片字体画的键**就偏下了。
 *
 * 反过来也一样。所以"全局一个值"必然按下葫芦浮起瓢，
 * 必须能**每个键单独调**。
 *
 * @param offsets 已有的每槽位偏移
 * @param onOffsetsChange 改动回调（整份 map 替换）
 */
@Composable
fun SlotTextOffsetDialog(
    config: KeyStrokesConfig,
    offsets: Map<String, TextOffset>,
    onOffsetsChange: (Map<String, TextOffset>) -> Unit,
    onDismiss: () -> Unit,
) {
    SlotSettingsDialog(
        config = config,
        title = "单独调整每个键的位置",
        hint = "这里的偏移会**叠加**在整体偏移之上。用它可以修正个别键 —— " +
            "比如空格显示的是非 ASCII 横线、走的不是图片字体，位置会和别的键不一样。",
        onDismiss = onDismiss,
        onClearAll = { onOffsetsChange(emptyMap()) },
    ) { slot, _ ->
        val current = offsets[slot.slotId] ?: TextOffset()

        SlotSettingsRow {
            SlotSettingsTitle(slot)
            CompactSlider(
                label = "X",
                value = current.x,
                valueRange = SLOT_OFFSET_MIN..SLOT_OFFSET_MAX,
                onValueChange = { value ->
                    onOffsetsChange(offsets.write(slot.slotId, current.copy(x = value)))
                },
            )
            CompactSlider(
                label = "Y",
                value = current.y,
                valueRange = SLOT_OFFSET_MIN..SLOT_OFFSET_MAX,
                onValueChange = { value ->
                    onOffsetsChange(offsets.write(slot.slotId, current.copy(y = value)))
                },
            )
        }
    }
}

/**
 * 每个按键**各自**的字间距 / 行间距。
 *
 * 全局值不够用的场景：同一个键面上有一行是图片字体、另一行是非 ASCII 的
 * 矢量字体，两者的"合适间距"不一样。
 */
@Composable
fun SlotTextSpacingDialog(
    config: KeyStrokesConfig,
    spacings: Map<String, TextSpacing>,
    onSpacingsChange: (Map<String, TextSpacing>) -> Unit,
    onDismiss: () -> Unit,
) {
    SlotSettingsDialog(
        config = config,
        title = "单独调整每个键的间距",
        hint = "会**叠加**在上面的整体间距之上。百分比相对字号算，" +
            "以后改字号不用重新调。",
        onDismiss = onDismiss,
        onClearAll = { onSpacingsChange(emptyMap()) },
    ) { slot, _ ->
        val current = spacings[slot.slotId] ?: TextSpacing()

        SlotSettingsRow {
            SlotSettingsTitle(slot)
            CompactSlider(
                label = "字间距",
                value = current.letter,
                valueRange = SLOT_LETTER_MIN..SLOT_LETTER_MAX,
                suffix = "%",
                onValueChange = { value ->
                    onSpacingsChange(spacings.write(slot.slotId, current.copy(letter = value)))
                },
            )
            CompactSlider(
                label = "行间距",
                value = current.line,
                valueRange = SLOT_LINE_MIN..SLOT_LINE_MAX,
                suffix = "%",
                onValueChange = { value ->
                    onSpacingsChange(spacings.write(slot.slotId, current.copy(line = value)))
                },
            )
        }
    }
}

/*
 * ============================================================
 * 写回：归零的项**直接删掉**
 * ============================================================
 * 全零的项与"没有这一项"在渲染上完全等价，留着只会让配置越来越大，
 * 也让"这个键调过没有"看不出来。
 */
private fun Map<String, TextOffset>.write(slotId: String, value: TextOffset): Map<String, TextOffset> =
    if (value.x == 0f && value.y == 0f) this - slotId else this + (slotId to value)

private fun Map<String, TextSpacing>.write(slotId: String, value: TextSpacing): Map<String, TextSpacing> =
    if (value.letter == 0f && value.line == 0f) this - slotId else this + (slotId to value)

/*
 * 范围与**全局滑块保持一致** —— 不一致的话，同一个视觉位移在整体与
 * 单独两处对应的数值不同，用户会以为其中一个坏了。
 *
 * 偏移的范围也与自定义 Key 的文字偏移一致（−60..60），
 * 这样"Key → 自定义"转换后位置不会跑偏。
 */
private const val SLOT_OFFSET_MIN = -60f
private const val SLOT_OFFSET_MAX = 60f

private const val SLOT_LETTER_MIN = -30f
private const val SLOT_LETTER_MAX = 100f
private const val SLOT_LINE_MIN = -50f
private const val SLOT_LINE_MAX = 200f

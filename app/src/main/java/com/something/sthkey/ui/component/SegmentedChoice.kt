package com.something.sthkey.ui.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

/**
 * 分段按钮**统一实现** —— 解决"文字换行把按钮撑高"的问题。
 *
 * ============================================================
 * ⚠️ 为什么要自己包一层（用户反馈）
 * ============================================================
 * 素材自带的 `SingleChoiceSegmentedButtonRow` 对文字**不做任何约束**：
 * 段数一多、或者标签是 `Choreographer` 这种长英文，在**小 DPI / 窄屏**上
 * 就会折成两行 —— 于是那一格比别的段高，整排按钮参差不齐
 * （用户报过好几次，且不止 FPS 那一处）。
 *
 * 这里的做法:
 * 1. **每段等宽**（`Modifier.weight`）—— 不会因为标签长短不一而宽度不齐;
 * 2. 文字**单行**、不换行、超长省略（`maxLines = 1` + `softWrap = false`）;
 * 3. **字号随段数自动缩小**（2 段 13sp / 3 段 12sp / 4 段及以上 11sp）——
 *    比省略号更友好，短标签根本不会被截。
 *
 * ⚠️ 新增分段按钮时**请用这个**，不要直接用 Material 的
 * `SingleChoiceSegmentedButtonRow` —— 那等于把这套修复绕过去了。
 *
 * @param options 选项列表（顺序即显示顺序）
 * @param selected 当前选中项
 * @param label 选项 → 显示文字；**尽量给短标签**（长英文会缩字号）
 * @param onSelect 点击某一项
 */
@Composable
fun <T> SegmentedChoice(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fontSize = when (options.size) {
        0, 1, 2 -> 13.sp
        3 -> 12.sp
        else -> 11.sp
    }

    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = label(option),
                    fontSize = fontSize,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

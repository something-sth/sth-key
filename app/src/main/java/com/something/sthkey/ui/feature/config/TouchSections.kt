package com.something.sthkey.ui.feature.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.something.sthkey.domain.config.TouchSettings
import com.something.sthkey.domain.config.KeyStrokesConfig
import com.something.sthkey.ui.component.SectionHeader
import com.something.sthkey.ui.component.SectionHint
import com.something.sthkey.ui.component.SettingsCard

/**
 * 触屏样式的**「触屏设置」分区**。
 *
 * ============================================================
 * ⚠️ 位置:在「外观」**上方**（用户明确指定）
 * ============================================================
 * 用户的原话:
 *
 * > 在**触屏样式的**「外观」栏**上方**添加「触屏设置」栏，
 * > 里面有一个「触摸区域编辑」的按钮
 *
 * ⚠️ 为什么放这么前、而且**只有这一个按钮**:
 *
 * > 我把这个放外观前面是因为**这个最重要**
 *
 * ============================================================
 * ⚠️⚠️ 这里**不放**摇杆的任何设置（我第一版放了一排滑块，被要求改回来）
 * ============================================================
 * 用户的原话:
 *
 * > 触屏设置**不需要把摇杆之类的写进去** …… **摇杆之类的 UI 方面不用动**，
 * > 而且**我没让你把两个摇杆分开**，我说过了 **UI 层面跟手柄基本相同**，
 * > 只有**读取逻辑和具体手感之类的要单独设计**
 *
 * ⚠️ 我把"手感单独设计"（那是**内部实现**）理解成了"UI 上把两个摇杆拆开摆" ——
 * 那是错的。触屏摇杆的**外观与 UI 完全跟手柄走**:
 *
 * | 项 | 在哪调 |
 * |---|---|
 * | 颜色 / 圆角 / 描边 / 摇杆帽 … | 「外观」栏（与手柄**共用同一份** `JoystickStyle`） |
 * | 手感（死区 / 灵敏度 / 平滑） | **不进界面**，用 `TouchSettings` 里的默认值；将来若要暴露再单独设计 |
 *
 * ⚠️ 所以这个分区**只有触摸区域一件事** —— 它回答"手指按哪里算操作轮盘"，
 * 那是触屏**独有**、手柄没有的东西，也正是"最重要"的原因。
 */
internal fun LazyListScope.touchStyleSections(
    editable: KeyStrokesConfig,
    onOpenRegionEditor: () -> Unit,
) {
    val regions = editable.touch.regions
    val joystick = regions.firstOrNull { it.slotId == TouchSettings.JOYSTICK_SLOT_ID }
    val buttonCount = regions.count { it.slotId != TouchSettings.JOYSTICK_SLOT_ID }

    item { SectionHeader("触屏设置") }

    item {
        SettingsCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = when {
                        regions.isEmpty() -> "还没有配置触摸区域"
                        joystick == null -> "轮盘未配置 · 按键区域 $buttonCount 块"
                        else -> "轮盘 中心 (%.2f, %.2f) 边长 %.2f · 按键区域 %d 块".format(
                            joystick.centerX,
                            joystick.centerY,
                            joystick.size,
                            buttonCount,
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(12.dp))

                /*
                 * ⚠️ 用 `Button`（填充 = 蓝底白字）+ 图标 ——
                 * 与「自定义编辑」那个按钮**逐字同构**（用户明确要求
                 * "样式与'自定义编辑'那个按钮是一样的，蓝底白字"）。
                 *
                 * ⚠️ 我第一版自己写了个 `TextButton`（无底色），
                 * 被指出"这么丑的 button" —— 不要自己发明样式，
                 * 页面里已有的按钮怎么写就怎么写。
                 */
                Button(
                    onClick = onOpenRegionEditor,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.TouchApp, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("触摸区域编辑")
                }
            }
        }
    }

    item {
        SectionHint(
            text = "触摸区域决定「手指按在屏幕的哪一块算操作移动轮盘」——" +
                "它画在**整块屏幕**上，与悬浮窗的位置无关。" +
                "没有落在任何区域里的滑动都会进视角摇杆。",
        )
    }
}

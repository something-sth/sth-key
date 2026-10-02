package com.something.sthkey.ui.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Power
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.something.sthkey.ui.EditMode
import com.something.sthkey.ui.MainViewModel
import com.something.sthkey.ui.component.CardDivider
import com.something.sthkey.ui.component.ScrollableScreen
import androidx.compose.material.icons.filled.Campaign
import com.something.sthkey.ui.feature.announcement.AnnouncementDialog
import com.something.sthkey.ui.feature.announcement.AppAnnouncement
import com.something.sthkey.ui.component.SectionHeader
import com.something.sthkey.ui.component.SectionHint
import com.something.sthkey.ui.component.SettingItem
import com.something.sthkey.ui.component.SettingsCard

/**
 * 设置页。
 *
 * 相比旧项目的改动：
 * - 删掉了顶部的 "event 策略" 开关：现在一律监听全部 event 设备，
 *   少一个会让人困惑的开关（是否真的带来性能损耗由采集层负责优化）；
 * - "调试"作为二级页面从设置进入（模式切换 + 运行日志）；
 * - GitHub / 开源许可证 / QQ 群 统一收进"关于"页。
 */
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onOpenDebug: () -> Unit,
    onOpenAbout: () -> Unit,
    onReopenSetup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showResetSetupDialog by remember { mutableStateOf(false) }

    /** 是否打开更新公告（**浏览模式**：可随时关闭，不需要读满倒计时） */
    var showAnnouncement by remember { mutableStateOf(false) }

    ScrollableScreen(
        title = "设置",
        subtitle = "权限、诊断与关于信息",
        modifier = modifier,
    ) {
        /*
         * ============================================================
         * 更新公告
         * ============================================================
         * 与「关于」页里那个是同一个弹窗、同一种浏览模式
         * （可随时关闭、不需要读满倒计时）。
         *
         * 放在最上面而不是塞进「关于」：发了新版之后，用户最想先知道的是
         * "这次改了什么"，而设置页是他会主动翻的地方；「关于」页更多是
         * 查开源地址与许可证时才去。
         */
        item { SectionHeader("更新公告") }

        item {
            SettingsCard {
                SettingItem(
                    title = "v${AppAnnouncement.latest.version} 更新公告",
                    subtitle = "发布于 ${AppAnnouncement.latest.date}，" +
                        "点开可以往前翻看历史版本",
                    leadingIcon = Icons.Default.Campaign,
                    onClick = { showAnnouncement = true },
                )
            }
        }

        /*
         * ============================================================
         * 权限
         * ============================================================
         */

        item { SectionHeader("权限") }

        item {
            SettingsCard {
                SettingItem(
                    title = "权限引导",
                    subtitle = if (viewModel.isSetupCompleted) {
                        "已完成；可重新进入检查各项授权"
                    } else {
                        "尚未完成，建议先走一遍引导"
                    },
                    leadingIcon = Icons.Default.Power,
                    onClick = onReopenSetup,
                )

                CardDivider()

                SettingItem(
                    title = "重置首次启动引导",
                    subtitle = "清除引导完成标记，下次启动重新进入引导页",
                    onClick = { showResetSetupDialog = true },
                )
            }
        }

        /*
         * ============================================================
         * 配置编辑方式
         * ============================================================
         */

        item { SectionHeader("配置编辑") }

        item {
            SettingsCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "编辑配置时的生效方式",
                        style = MaterialTheme.typography.bodyLarge,
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = viewModel.editMode.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        EditMode.entries.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = viewModel.editMode == mode,
                                onClick = { viewModel.updateEditMode(mode) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = EditMode.entries.size,
                                ),
                            ) {
                                Text(mode.label)
                            }
                        }
                    }
                }
            }
        }

        /*
         * 画布边框那两个开关**不在这里** ——
         * 它们在自定义编辑页顶部的「更多」菜单里（竖三点图标）。
         *
         * ============================================================
         * 为什么放在编辑器里而不是设置页
         * ============================================================
         * 它们是"看着画布调"的东西：改完要立刻看效果。
         * 放设置页就得来回跳两个页面，而每次跳转都要重建编辑页。
         *
         * ⚠️ 但它们的**性质仍是全局偏好**（存 `AppPrefs`），
         * 不是某份配置的专属设置 —— 所以不是塞进组件属性面板，
         * 而是收进编辑页顶栏的「更多」里。
         */

        /*
         * ============================================================
         * 诊断
         * ============================================================
         */

        item { SectionHeader("诊断") }

        item {
            SettingsCard {
                SettingItem(
                    title = "调试",
                    subtitle = "手动切换 root / Shizuku，查看运行日志",
                    leadingIcon = Icons.Default.BugReport,
                    onClick = onOpenDebug,
                )
            }
        }

        item {
            SectionHint(
                text = "遇到按键无反应、悬浮窗不显示等问题时，先到调试页查看日志，" +
                    "日志记录了每次权限检测与模式切换的结果。",
            )
        }

        /*
         * ============================================================
         * 关于
         * ============================================================
         */

        item { SectionHeader("关于") }

        item {
            SettingsCard {
                SettingItem(
                    title = "关于本应用",
                    subtitle = "版本信息、开源仓库、许可证与交流群",
                    leadingIcon = Icons.Default.Info,
                    onClick = onOpenAbout,
                )
            }
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "按键显示依赖外接键盘并使用 root 或 Shizuku 读取原始输入事件。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showResetSetupDialog) {
        AlertDialog(
            onDismissRequest = { showResetSetupDialog = false },
            title = { Text("重置首次启动引导？") },
            text = { Text("下次启动应用时会重新进入权限引导页。不会影响已有配置。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetSetupDialog = false
                        viewModel.resetSetupCompleted()
                    },
                ) {
                    Text("重置")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetSetupDialog = false }) { Text("取消") }
            },
        )
    }

    /*
     * ============================================================
     * 更新公告（浏览模式）
     * ============================================================
     * 与「关于」页里那个完全一样：回看历史，不是"必读公告"，
     * 所以随时可以关掉、也没有倒计时。
     */
    if (showAnnouncement) {
        AnnouncementDialog(
            initialVersion = AppAnnouncement.latest.version,
            requireReading = false,
            onConfirm = { showAnnouncement = false },
            onDismiss = { showAnnouncement = false },
        )
    }
}

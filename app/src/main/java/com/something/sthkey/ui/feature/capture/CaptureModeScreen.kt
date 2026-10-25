package com.something.sthkey.ui.feature.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.something.sthkey.capture.CaptureSession
import com.something.sthkey.capture.CaptureState
import com.something.sthkey.capture.shizuku.ShizukuShell
import com.something.sthkey.domain.capture.KeyMode
import com.something.sthkey.ui.component.CardDivider
import com.something.sthkey.ui.component.ScrollableScreen
import com.something.sthkey.ui.component.SectionHeader
import com.something.sthkey.ui.component.SectionHint
import com.something.sthkey.ui.component.SettingItem
import com.something.sthkey.ui.component.SettingsCard
import com.something.sthkey.ui.MainViewModel
import kotlinx.coroutines.launch



/**
 * **采集通道设置** —— 从主页那张大卡片点进来。
 *
 * ============================================================
 * ⚠️⚠️ 为什么单独开一页（用户的要求 + 照 SukiSU 的做法）
 * ============================================================
 * 用户的原话:
 *
 * > 调试页的工作模式切换的卡片，也可以做在**我们这一班搞的工作状态卡片里**，
 * > sukisu 的卡片点开后是关于 boot 之类的功能，**我们把那个页面换一下就行了**
 *
 * ⚠️ SukiSU 那边确实是这么做的:它的 `StatusCard` 的 `onClick` 是
 * `navigator.push(Route.Install())` —— **推开一个独立页面**，
 * 而不是弹对话框或抽屉。所以这里也用一个页面。
 *
 * ⚠️ 为什么从调试页搬出来:
 *
 * | 放在哪 | 问题 / 好处 |
 * |---|---|
 * | 调试页 | ⚠️ 「切换采集通道」是**日常操作**，不是调试功能。藏进"调试"里让普通用户以为它很危险 |
 * | **主页卡片点进来** | ✅ 用户看到「监听失败」时，手指就在那张卡上，点一下就能换通道 |
 *
 * ⚠️ 留在调试页的是**真正的诊断**（能力检测、Shizuku 直连自检、日志）——
 * 那些才是"出问题才去看"的东西。
 */
@Composable
fun CaptureModeScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    /*
     * ⚠️ **待应用的**模式:点分段按钮只改这个，点「应用」才真的切。
     *
     * ⚠️ 为什么不点了就立刻切:切通道会**重启采集**（几百毫秒到几秒），
     * 用户如果只是想看看每个模式的说明，一路点过去就会连续重启好几次。
     */
    var pendingMode by remember { mutableStateOf(viewModel.requestedMode) }

    val scope = rememberCoroutineScope()
    /* ⚠️ Shizuku 直连自检的输出（只有真跑过才有内容） */
    var probeResult by remember { mutableStateOf<String?>(null) }

    ScrollableScreen(
        title = "采集通道",
        subtitle = "决定用什么身份读取按键",
        onBack = onBack,
        modifier = modifier,
        actions = {
            /*
             * ⚠️ 「重启监听」放在这一页的右上角（它原来在主页那张卡里）。
             *
             * ⚠️ 为什么需要它:采集走的是 `getevent`，而它**不能热添加设备** ——
             * 启动时有哪些输入设备就只监听哪些。于是插上手柄、接上外接键盘
             * 之后不重启就永远读不到，"明明插上了却没反应"。
             */
            OutlinedButton(onClick = { viewModel.restartCapture() }) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("重启监听")
            }
        },
    ) {
        item { SectionHeader("采集模式") }

        item {
            SettingsCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "选择读取输入设备的方式",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        KeyMode.entries.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = pendingMode == mode,
                                onClick = { pendingMode = mode },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = KeyMode.entries.size,
                                ),
                            ) {
                                Text(mode.label)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = pendingMode.description,
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    if (pendingMode != viewModel.requestedMode) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = { viewModel.applyRequestedMode(pendingMode) },
                            ) {
                                Text("应用")
                            }
                            OutlinedButton(
                                onClick = { pendingMode = viewModel.requestedMode },
                            ) {
                                Text("撤销")
                            }
                        }
                    }

                    /*
                     * 直连自检。
                     *
                     * 只在"这条通道可能真的会用到 Shizuku"时才显示 ——
                     * 强制 root 的用户看到它只会困惑。
                     *
                     * 直连有两处外部依赖容易出问题:
                     * ① `newProcess` 的事务号是硬编码的（见 ShizukuShell），
                     *    Shizuku 升级后可能错位；
                     * ② 有些 ROM 的 `getevent` 参数支持不一致。
                     *
                     * 自检按顺序各问一个问题，答案写在哪一步上 ——
                     * 用户不必猜，我们也不必远程猜。
                     */
                    if (pendingMode != KeyMode.ROOT) {
                        Spacer(modifier = Modifier.height(20.dp))
                        CardDivider()
                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "Shizuku 直连",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "事件由 Shizuku 启动的读取进程回传。" +
                                "如果采集启动不起来，点下面的按钮做一次自检。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedButton(
                            onClick = {
                                probeResult = "检测中…"
                                scope.launch { probeResult = ShizukuShell.runDiagnostics() }
                            },
                            enabled = probeResult != "检测中…",
                        ) {
                            Text("检测直连通道")
                        }
                        probeResult?.let {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        item {
            val resolved = viewModel.resolvedMode
            SettingsCard {
                SettingItem(
                    title = "当前生效",
                    leadingIcon = Icons.Rounded.CheckCircle,
                    subtitle = "${viewModel.requestedMode.label} → ${resolved.mode.label}",
                )

                CardDivider()

                SettingItem(
                    title = "解析依据",
                    leadingIcon = Icons.Rounded.Info,
                    subtitle = resolved.reason,
                )

                if (resolved.fellBack) {
                    CardDivider()
                    SettingItem(
                        title = "注意：发生了自动回退",
                        leadingIcon = Icons.Rounded.ErrorOutline,
                        subtitle = "请求的模式不可用，已自动切换。若结果不符合预期，" +
                            "可在上方手动指定模式后点击应用。",
                    )
                }
            }
        }

        /*
         * ============================================================
         * ⚠️ 运行详情 —— 从主页那张「工作状态细节卡」搬过来
         * ============================================================
         * 用户的原话:
         *
         * > 原来旧的工作状态卡片应该**删掉，只保留新的这个**
         *
         * ⚠️ 细节没有删掉，而是搬到了**它本该在的地方**:这里是"采集通道"
         * 的地盘，通道可用性、设备数、能力边界都属于这一页。
         *
         * ⚠️ 摊在主页上会有两个坏处:把好端端的大卡片挤成一整块信息面板；
         * 而且"读不到手柄"这种话，用户想改的时候才需要看，那时他正要点这张卡。
         */
        item { SectionHeader("当前环境") }

        item {
            val capabilities = viewModel.capabilities
            SettingsCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    CapabilityRow("Root", capabilities.rootAvailable, capabilities.rootDetail)
                    CapabilityRow("Shizuku", capabilities.shizukuReady, null)
                    /*
                     * ⚠️ 无障碍**也要列**（用户要求过"卡片做具体一点"）——
                     * 它是第三条通道，不列的话用户看不出来态。
                     *
                     * ⚠️ 后面那个"仅键盘"是**刻意**写出来的:无障碍的能力弱于
                     * 另外两条，不写清会让用户以为"有它就能读全"。
                     */
                    CapabilityRow(
                        name = "无障碍",
                        available = capabilities.accessibilityReady,
                        detail = if (capabilities.accessibilityReady) "仅键盘" else null,
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    /*
                     * ============================================================
                     * ⚠️⚠️ 监听详情:无障碍模式下**不显示设备数**
                     * ============================================================
                     * 用户的原话:
                     *
                     * > 主页的工作状态卡片，现在显示的是"正在监听 n 个输入设备"
                     * > 这样子的，但是无障碍不同，需要变通一下
                     *
                     * ⚠️ 无障碍**没有"设备"这个概念**（它收的是系统派发的 KeyEvent，
                     * 不知道也不关心来自哪块键盘）。硬显示的话会是一个永远 0 的假数字。
                     */
                    val captureState by CaptureSession.state.collectAsState()
                    val channel by CaptureSession.channel.collectAsState()
                    val deviceCount by CaptureSession.deviceCount.collectAsState()
                    val message by CaptureSession.message.collectAsState()

                    Text(
                        text = buildString {
                            append("状态：")
                            append(captureState.label)
                            channel?.let { append(" · ${it.label}") }
                            if (captureState == CaptureState.RUNNING) {
                                /*
                                 * ⚠️ 分两支:只有"能读 /dev/input"的通道才有设备数。
                                 * 用 `channel.keyboardOnly` 判，而不是写
                                 * `channel == ACCESSIBILITY` —— 以后再加受限通道时
                                 * 这里不用改（见 `CaptureChannel.keyboardOnly`）。
                                 */
                                if (channel?.keyboardOnly == true) {
                                    append(" · 键盘按键")
                                } else {
                                    append(" · $deviceCount 个设备")
                                }
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    /*
                     * ⚠️⚠️ 无障碍模式的能力边界（用户要求的"给红字提示一下"）
                     *
                     * ⚠️ 只在**真的用了无障碍通道**时才出现 —— 有 root 的用户
                     * 不该看到一行"读不到手柄"的红字（那会让人以为他配错了）。
                     *
                     * ⚠️ 条件用 `channel?.keyboardOnly == true` 而不是"模式是 ACCESSIBILITY":
                     * AUTO 模式下**降级到无障碍**时 `requestedMode` 是"自动"，
                     * 只看模式的话这条红字就不会出现 —— 而那时恰恰最需要它。
                     */
                    if (captureState == CaptureState.RUNNING && channel?.keyboardOnly == true) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "当前是无障碍通道：只能读物理键盘。" +
                                "鼠标、手柄摇杆与触摸都读不到 —— 那些需要 root 或 Shizuku。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    if (message.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (captureState == CaptureState.FAILED) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }

        item {
            SectionHint(
                text = "「自动」按 root → Shizuku → 无障碍 逐级尝试；" +
                    "手动指定某个模式时不会自动回退，失败原因会直接记录到日志里。",
            )
        }
    }
}

/**
 * 一行通道可用性：绿点/灰点 + 名称 + 详情。
 *
 * ⚠️ 从 `HomeScreen` 搬过来的（那张「工作状态细节卡」删掉后它没有调用方了）。
 */
@Composable
private fun CapabilityRow(
    name: String,
    available: Boolean,
    detail: String?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (available) Icons.Default.CheckCircle else Icons.Default.Error,
            contentDescription = null,
            tint = if (available) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline
            },
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(72.dp),
        )
        Text(
            text = if (available) {
                detail?.takeIf { it.isNotBlank() } ?: "可用"
            } else {
                detail?.takeIf { it.isNotBlank() } ?: "不可用"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

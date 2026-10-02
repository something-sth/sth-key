package com.something.sthkey.ui.feature.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.something.sthkey.capture.CaptureSession
import com.something.sthkey.capture.OverlayService
import com.something.sthkey.capture.shizuku.ShizukuShell
import com.something.sthkey.core.log.AppLog
import com.something.sthkey.core.log.CrashLogger
import com.something.sthkey.core.log.LogLevel
import com.something.sthkey.core.prefs.AppPrefs
import com.something.sthkey.domain.capture.KeyMode
import com.something.sthkey.ui.MainViewModel
import com.something.sthkey.ui.component.CardDivider
import com.something.sthkey.ui.component.EmptyHint
import com.something.sthkey.ui.component.ScrollableScreen
import com.something.sthkey.ui.component.SectionHeader
import com.something.sthkey.ui.component.SectionHint
import com.something.sthkey.ui.component.SettingItem
import com.something.sthkey.ui.component.SettingsCard
import com.something.sthkey.ui.component.SwitchItem
import com.something.sthkey.ui.component.findActivity
import com.something.sthkey.ui.overlay.cpsDiagnosticReport
import com.something.sthkey.ui.overlay.keyLayoutDiagnosticReport
import com.something.sthkey.ui.overlay.liveWindowPickerHint
import com.something.sthkey.ui.overlay.noLiveWindowReport
import com.something.sthkey.domain.style.StyleId
import com.something.sthkey.ui.theme.monoSmall
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 调试页。
 *
 * 两个用途，分别对应两类问题：
 *
 * 1. **模式切换**：手动指定 root 或 Shizuku，用于排查"某条采集路径在这台设备上
 *    到底能不能用"。默认「自动」优先 root，失败回退 Shizuku。
 * 2. **运行日志**：带毫秒时间戳的应用内日志。真机复现问题时不必连电脑看 logcat，
 *    直接把日志复制发出来即可。
 *
 * 日志只保留最近 [AppLog.DISPLAY_LIMIT] 条，且反过来显示（最新在最上方），
 * 避免每次刷新都要滚到底部。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DebugScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val prefs = remember { AppPrefs.get(context) }

    // 订阅日志计数以触发刷新；这里只观察计数，不承载日志内容本身
    val logVersion by AppLog.updates.collectAsState()

    var minLevelName by remember { mutableStateOf(prefs.logMinLevel) }
    var query by remember { mutableStateOf(prefs.logQuery) }
    var pendingMode by remember { mutableStateOf(viewModel.requestedMode) }

    /** 「检测直连通道」的结果；null = 还没点过 */
    var probeResult by remember { mutableStateOf<String?>(null) }

    val minLevel = remember(minLevelName) {
        minLevelName?.let { name -> LogLevel.entries.firstOrNull { it.name == name } }
    }

    val records = remember(logVersion, minLevelName, query) {
        AppLog.recent(limit = AppLog.DISPLAY_LIMIT, minLevel = minLevel, query = query).asReversed()
    }

    /*
     * CPS 自诊断的刷新节拍。
     *
     * CPS 是"最近一秒内的点击数"，会随时间自然衰减 —— 只在进入页面时取一次的话，
     * 面板会永远停在"那一刻"的值上，用户点了鼠标也看不到变化，
     * 反而会以为是诊断本身坏了。一秒一跳，与 CPS 的统计窗口同一个节拍。
     */
    var cpsTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000L)
            cpsTick++
        }
    }

    /*
     * 上次崩溃的内容。
     *
     * 必须在**组合上下文**里读（remember 是 @Composable），
     * 不能写在下面 LazyColumn 的 item 块里 —— 那里是 LazyListScope。
     *
     * 用独立的 crashRevision 而不是 logVersion 做 key：
     * 日志每次追加都会让 logVersion 变化，那样"清除记录"后画面会立刻又读回旧内容。
     */
    var crashRevision by remember { mutableIntStateOf(0) }
    val crashText = remember(crashRevision) { CrashLogger.readLast(context) }

    /*
     * Shizuku 诊断状态。
     *
     * 手动刷新（按钮）而不是持续订阅：诊断信息变化不频繁，
     * 持续订阅会让整页频繁重组，收益却很小。
     */
    val scope = rememberCoroutineScope()

    ScrollableScreen(
        title = "调试",
        subtitle = "采集模式切换与运行日志",
        onBack = onBack,
        modifier = modifier,
        actions = {
            IconButton(onClick = { viewModel.refreshCapabilities(force = true) }) {
                Icon(Icons.Default.Refresh, contentDescription = "重新检测")
            }
        },
    ) {
        /*
         * ============================================================
         * 采集模式
         * ============================================================
         */

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
                     * 直连有两处外部依赖容易出问题：
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
                    subtitle = "${viewModel.requestedMode.label} → ${resolved.mode.label}",
                )

                CardDivider()

                SettingItem(
                    title = "解析依据",
                    subtitle = resolved.reason,
                )

                if (resolved.fellBack) {
                    CardDivider()
                    SettingItem(
                        title = "注意：发生了自动回退",
                        subtitle = "请求的模式不可用，已自动切换。若结果不符合预期，" +
                            "可在上方手动指定模式后点击应用。",
                    )
                }
            }
        }

        item {
            SectionHint(
                text = "「自动」会优先使用 root，root 不可用或启动失败时回退到 Shizuku；" +
                    "手动指定某个模式时不会自动回退，失败原因会直接记录到日志里。",
            )
        }

        /*
         * ============================================================
         * 能力检测
         * ============================================================
         */

        item { SectionHeader("能力检测") }

        item {
            SettingsCard {
                if (viewModel.isProbing) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("正在检测 root…", style = MaterialTheme.typography.bodyLarge)
                    }
                } else {
                    SettingItem(
                        title = "Root",
                        subtitle = if (viewModel.capabilities.rootAvailable) {
                            "可用 · ${viewModel.capabilities.rootDetail}"
                        } else {
                            "不可用 · ${viewModel.capabilities.rootDetail}"
                        },
                    )

                    CardDivider()

                    SettingItem(
                        title = "Shizuku",
                        subtitle = if (viewModel.capabilities.shizukuReady) {
                            "已授权，可作为 root 之外的采集通道"
                        } else {
                            "未授权（点击下方按钮申请）"
                        },
                    )
                }

                CardDivider()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(
                        onClick = { viewModel.refreshCapabilities(force = true) },
                        enabled = !viewModel.isProbing,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("重新检测")
                    }
                    OutlinedButton(
                        onClick = { viewModel.requestShizukuPermission(context.findActivity()) },
                        enabled = !viewModel.isProbing,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("授权 Shizuku")
                    }
                }
            }
        }

        /*
         * ============================================================
         * Shizuku 诊断
         *
         * 用户报"Shizuku 用不了"时，这一块要能直接回答"卡在哪一步"：
         * 未运行 → 未授权 → 设备读不到。
         * 因此把每一步的状态都摊开显示，而不是一句"不可用"。
         *
         * ⚠️ 这里原来还有「UserService」「服务进程」「服务 uid」
         * 「远端读取」四行 + 「重连服务」「断开服务」两个按钮 ——
         * 那些是 UserService 通道的专属诊断，那条通道已经删掉了。
         * 留着只会让用户对着一堆永远显示"—"的行发愣。
         * ============================================================
         */

        item { SectionHeader("Shizuku 诊断") }

        item {
            SettingsCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    DiagnosticRow(
                        label = "Shizuku 服务",
                        value = if (ShizukuShell.isShizukuRunning()) "运行中" else "未运行",
                        ok = ShizukuShell.isShizukuRunning(),
                    )
                    DiagnosticRow(
                        label = "本应用授权",
                        value = if (ShizukuShell.hasPermission()) "已授权" else "未授权",
                        ok = ShizukuShell.hasPermission(),
                    )

                    val version = remember(viewModel.capabilities) { ShizukuShell.shizukuVersion() }
                    DiagnosticRow(
                        label = "Shizuku 版本",
                        value = if (version >= 0) "v$version" else "未知",
                        // 直连要求 v11+：`newProcess` 的行为从 v11 起才稳定
                        ok = version >= 11,
                        hint = if (version in 0..10) "版本过低，需要 v11 及以上" else null,
                    )
                }

                CardDivider()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(
                        onClick = {
                            // 重新查一遍能力：诊断行会因 StateFlow 变化自动刷新
                            viewModel.refreshCapabilities(force = true)
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("刷新状态")
                    }
                    OutlinedButton(
                        onClick = { viewModel.restartCapture() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("重启采集")
                    }
                }
            }
        }

        item {
            SectionHint(
                text = "Shizuku 通道需要：Shizuku 正在运行 → 本应用已授权 → " +
                    "至少能读到一个输入设备。哪一步显示异常，问题就在那一步。" +
                    "若采集仍启动不了，用上面的「检测直连通道」做一次自检。",
            )
        }

        /*
         * ============================================================
         * 悬浮窗按键模拟
         *
         * 输入采集（root / Shizuku）还没接，但悬浮窗已经能画了。
         * 这里提供"手动按一下"的入口：既能验证悬浮窗渲染是否正确，
         * 也方便调按键大小/配色时立刻看到效果。
         * ============================================================
         */

        item { SectionHeader("悬浮窗") }

        item {
            val overlayRunning = OverlayService.isRunning()

            /*
             * 实际挂着的窗口。
             *
             * 与"哪几个开关开着"对比即可定位多窗口的常见问题：
             * - 开关开着、这里没有 → 窗口没建起来（服务没收到通知 / 权限被收回）
             * - 这里有、屏幕上却看不到 → 位置出屏、或透明度为 0
             *
             * 用 configVersion 作 key：改了配置之后重新取一次（配置名可能变了）。
             */
            val windows = remember(overlayRunning, viewModel.configVersion) {
                OverlayService.renderedWindowLabels()
            }
            val enabledCount = viewModel.overlayEnabledIds.size

            SettingsCard {
                SettingItem(
                    title = "悬浮窗状态",
                    subtitle = if (overlayRunning) {
                        "正在运行 · 实际显示 ${windows.size} 个窗口"
                    } else {
                        "未运行（可在主页打开某个配置的开关）"
                    },
                )

                CardDivider()

                SettingItem(
                    title = "正在显示的配置",
                    subtitle = if (windows.isEmpty()) {
                        "—（没有窗口）"
                    } else {
                        windows.joinToString(separator = "\n")
                    },
                )

                CardDivider()

                /*
                 * 开关集合与实际窗口的差集。
                 *
                 * 两者不一致就是问题所在，所以直接把差集摆出来，
                 * 不用让用户自己去比对两串 id。
                 */
                SettingItem(
                    title = "开关与窗口是否一致",
                    subtitle = buildString {
                        append("开着的开关：$enabledCount 个")
                        if (enabledCount == windows.size) {
                            append(" · 一致")
                        } else {
                            append(" · ⚠ 不一致（窗口没建起来，看日志里的「添加悬浮窗失败」）")
                        }
                    },
                )

                CardDivider()

                /*
                 * 几何诊断。
                 *
                 * "窗口能拖到哪"涉及三份尺寸（我们算的边界 / 系统允许的最大区域 /
                 * 窗口实际落位），任何一份对不上都表现为"边界不对"。
                 * 这个按钮把三者一起复制出来 —— 排查边界问题时，
                 * 有这三行数字就不用猜了。
                 */
                SettingItem(
                    title = "悬浮窗边界数据",
                    subtitle = "复制显示器尺寸、系统最大窗口区域与每个窗口的实际/请求位置",
                    trailing = {
                        OutlinedButton(
                            onClick = {
                                copyText(context, OverlayService.geometryReport())
                                Toast.makeText(context, "边界数据已复制", Toast.LENGTH_SHORT).show()
                            },
                        ) {
                            Text("复制")
                        }
                    },
                )

                CardDivider()

                /*
                 * CPS 自诊断。
                 *
                 * 直接把"组件绑了什么 / 翻译成哪个槽位 / 现在数值多少 / 显示成什么"
                 * 摆出来 —— 这类问题光看悬浮窗只能看到最后一层（一个空串），
                 * 分不清是没绑上、翻译不出来，还是数值确实是 0。
                 *
                 * 做成面板而不是让用户翻日志：日志会被过滤、会被挤出缓冲区。
                 */
                val cpsReport = remember(overlayRunning, viewModel.configVersion, cpsTick) {
                    cpsDiagnosticReport(
                        configs = viewModel.allConfigs(),
                        cpsBySlotOf = { id -> CaptureSession.cpsSnapshotOf(id) },
                    )
                }

                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "CPS 自诊断",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            onClick = {
                                copyText(context, cpsReport)
                                Toast.makeText(context, "CPS 诊断已复制", Toast.LENGTH_SHORT).show()
                            },
                        ) {
                            Text("复制")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = cpsReport,
                        style = monoSmall,
                        modifier = Modifier
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }

                CardDivider()

                /*
                 * 按键布局的真实像素尺寸。
                 *
                 * 排查"悬浮窗上某个键右边短一截"这类问题时，光看代码里的
                 * 常量算不出结论（窗口宽度与内容宽度在数值上是刚好相等的）——
                 * 需要看**真实取值**。这个面板把每一步的实际数字摊开，
                 * 包括取整前后的窗口尺寸与每个键的像素左右边界。
                 */
                val layoutReport = remember(viewModel.configVersion, cpsTick) {
                    /*
                     * ⚠️ 只读**运行中那些窗口**自己的配置。
                     *
                     * 早先这里是"从全部配置里挑第一份非自定义样式的"——
                     * 结果屏幕上那个窗口用的是另一份配置，
                     * 面板算出来的尺寸与真实窗口对不上，白查了两轮。
                     */
                    val live = OverlayService.liveWindows()
                    if (live.isEmpty()) {
                        noLiveWindowReport()
                    } else {
                        buildString {
                            appendLine(liveWindowPickerHint(live.size))
                            live.forEachIndexed { index, window ->
                                if (index > 0) appendLine()
                                appendLine(keyLayoutDiagnosticReport(window))
                            }
                        }.trimEnd()
                    }
                }

                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "按键布局尺寸",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            onClick = {
                                copyText(context, layoutReport)
                                Toast.makeText(context, "布局尺寸已复制", Toast.LENGTH_SHORT).show()
                            },
                        ) {
                            Text("复制")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = layoutReport,
                        style = monoSmall,
                        modifier = Modifier
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }

                CardDivider()

                SettingItem(
                    title = "全部关闭",
                    subtitle = "一次关掉所有悬浮窗（等价于把主页的开关逐个关掉）",
                    trailing = {
                        OutlinedButton(
                            onClick = { viewModel.clearAllOverlays() },
                            enabled = enabledCount > 0,
                        ) {
                            Text("关闭")
                        }
                    },
                )
            }
        }

        item {
            SettingsCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "模拟按键",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "点击某个键可以让悬浮窗上的对应位置亮起，用于验证显示效果（" +
                            "真实输入采集尚未接入）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    /*
                     * 用**第一个开着悬浮窗的配置**的键位：模拟按键就是为了看
                     * 屏幕上的窗口有没有反应，那就该按屏幕上那份配置来。
                     * 一个窗口都没开时回退到第一份配置。
                     */
                    val config = viewModel.debugConfig()
                    val pressedCodes = CaptureSession.pressedKeys.value

                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        config.keyMappings.forEach { mapping ->
                            val code = mapping.inputKeyCodes.firstOrNull()
                            if (code == null) return@forEach

                            val pressed = code in pressedCodes
                            FilledTonalButton(
                                /*
                                 * 传 context：模拟按键会**同时算一次 CPS 点击**，
                                 * 否则调试页按得再欢 CPS 也是 0，看起来像功能坏了。
                                 */
                                onClick = { CaptureSession.simulate(code, !pressed, context) },
                                colors = if (pressed) {
                                    ButtonDefaults.filledTonalButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary,
                                    )
                                } else {
                                    ButtonDefaults.filledTonalButtonColors()
                                },
                            ) {
                                Text(mapping.displayText.ifBlank { mapping.id })
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = { CaptureSession.clearKeys() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("全部松开")
                    }
                }
            }
        }

        /*
         * ============================================================
         * 上次崩溃
         *
         * 真机闪退时通常拿不到 logcat，所以崩溃堆栈会落盘，
         * 在这里展示并支持一键复制 —— 复现一次就能把原因贴出来。
         * ============================================================
         */

        if (crashText != null) {
            item { SectionHeader("上次崩溃") }

            item {
                SettingsCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "应用上次运行以崩溃结束，下面是异常堆栈：",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = crashText.take(4000),
                            style = monoSmall,
                            modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    copyText(context, crashText)
                                    Toast.makeText(context, "崩溃日志已复制", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("复制崩溃日志")
                            }

                            OutlinedButton(
                                onClick = {
                                    CrashLogger.clear(context)
                                    crashRevision++
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("清除记录")
                            }
                        }
                    }
                }
            }
        }

        /*
         * ============================================================
         * 运行日志
         * ============================================================
         */

        item { SectionHeader("运行日志") }

        item {
            SettingsCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "最近 ${records.size} 条 / 共记录 ${AppLog.totalCount} 条（最多保留 ${AppLog.DISPLAY_LIMIT} 条）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        val options = listOf<LogLevel?>(null) + LogLevel.entries
                        options.forEachIndexed { index, level ->
                            SegmentedButton(
                                selected = minLevelName == level?.name,
                                onClick = {
                                    minLevelName = level?.name
                                    prefs.logMinLevel = level?.name
                                },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = options.size,
                                ),
                            ) {
                                Text(level?.label ?: "全部")
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = query,
                        onValueChange = {
                            query = it
                            prefs.logQuery = it
                        },
                        singleLine = true,
                        label = { Text("过滤关键字") },
                        placeholder = { Text("例如 root / Shizuku / 悬浮窗") },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                copyLogs(context)
                                Toast.makeText(context, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("复制全部")
                        }

                        OutlinedButton(
                            onClick = { AppLog.clear() },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("清空")
                        }
                    }
                }
            }
        }

        if (records.isEmpty()) {
            item {
                SettingsCard {
                    EmptyHint(
                        icon = Icons.Default.Terminal,
                        text = if (query.isBlank()) {
                            "暂无日志。切换模式或点「检测并记录日志」后，这里会出现记录。"
                        } else {
                            "没有匹配「$query」的日志。"
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        } else {
            items(records, key = { it.seq }) { record ->
                LogRow(
                    timeText = record.timeText,
                    levelLabel = record.level.label,
                    tag = record.tag,
                    message = record.message,
                )
            }
        }

        item {
            SectionHint(
                text = "日志时间戳精确到毫秒。排查「按键无反应」时，" +
                    "请先确认采集模式是否为预期模式，再看是否有打开设备失败的记录。",
            )
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

/** 单条日志：时间戳 + 级别 + 来源 + 内容 */
@Composable
private fun LogRow(
    timeText: String,
    levelLabel: String,
    tag: String,
    message: String,
    modifier: Modifier = Modifier,
) {
    val levelColor = when (levelLabel) {
        "E" -> MaterialTheme.colorScheme.error
        "W" -> MaterialTheme.colorScheme.tertiary
        "I" -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    SettingsCard(modifier = modifier) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = timeText,
                    style = monoSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = levelLabel,
                    style = monoSmall.copy(fontFamily = FontFamily.Monospace),
                    color = levelColor,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = tag,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * 诊断信息行：标签 + 值 + 通过/未通过标记。
 *
 * 用图标而不是纯文字，是为了让用户一眼扫出"哪一步没过"。
 */
@Composable
private fun DiagnosticRow(
    label: String,
    value: String,
    ok: Boolean,
    hint: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (ok) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(96.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            hint?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** 复制全部日志到剪贴板，方便直接发给开发者排查 */
private fun copyLogs(context: Context) {
    copyText(context, AppLog.dump().ifBlank { "（暂无日志）" })
}

/** 复制任意文本到剪贴板 */
private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("sth key 日志", text))
}

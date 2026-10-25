package com.something.sthkey.ui.feature.home

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DashboardCustomize
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.something.sthkey.BuildConfig
import com.something.sthkey.capture.CaptureChannel
import com.something.sthkey.capture.CaptureSession
import com.something.sthkey.capture.CaptureState
import com.something.sthkey.capture.OverlayBounds
import com.something.sthkey.capture.OverlayService
import com.something.sthkey.core.log.AppLog
import com.something.sthkey.data.permission.PermissionHelper
import com.something.sthkey.domain.capture.CaptureCapabilities
import com.something.sthkey.domain.config.ConfigGroup
import com.something.sthkey.domain.config.KeyStrokesConfig
import com.something.sthkey.domain.style.OverlayStyleRegistry
import com.something.sthkey.ui.component.CardDivider
import com.something.sthkey.ui.component.ExpressiveSwitch
import com.something.sthkey.ui.component.IconTileButton
import com.something.sthkey.ui.component.ScreenScaffold
import com.something.sthkey.ui.component.SectionHint
import com.something.sthkey.ui.component.SettingRowSurface
import com.something.sthkey.ui.component.SettingsCard
import com.something.sthkey.ui.MainViewModel
import com.something.sthkey.ui.overlay.overlayWindowHeightPx
import com.something.sthkey.ui.overlay.overlayWindowWidthPx
import java.io.File
import kotlinx.coroutines.delay




/** 本页日志用的 tag；与 OverlayService 用同一个，方便在调试页一起过滤 */
private const val TAG = "Overlay"

/** 悬浮窗运行状态的检查间隔 */
private const val SERVICE_CHECK_INTERVAL_MS = 500L

/**
 * 同时开启多个悬浮窗时的建议上限。
 *
 * 不是硬限制：每个 Live2D 窗口都是一个常驻 WebGL 的 WebView，
 * 低端机开到 4 个以上很可能明显卡顿。挡住用户不如提示一句 ——
 * 他自己的机器什么状况，他比我们清楚。
 */
private const val SUGGESTED_MAX_WINDOWS = 3

/**
 * 主页。
 *
 * ============================================================
 * 这一版的形态
 * ============================================================
 * 去掉「显示按键悬浮窗」单一开关与「当前配置」，改成**所有配置的列表**：
 * 每一行是 `编辑按钮 + 配置名 + 开关`。
 *
 * - 开任意一个开关 → 显示那个悬浮窗；连续开多个 → **只启动一次服务**（服务层幂等）
 * - **全部关闭**只停止悬浮窗服务，**不停监听**（采集是应用级的，见下）
 * - 编辑按钮**仅在该悬浮窗开启时可点**，未开启时置灰 ——
 *   它的内容是"这个窗口在屏幕上怎么摆"，窗口都没开时调它没有意义
 *
 * ============================================================
 * ⚠️ 采集（监听）不再跟着悬浮窗开关走
 * ============================================================
 * 只要应用活着，监听就一直进行：
 *
 * - 应用启动时自动开（`CaptureController.init` 无条件启动）；
 * - 关掉全部悬浮窗**不停**它；
 * - 调试页留了一个"重启监听"按钮，用来在出问题时手动重来。
 *
 * 为什么这样更好：全局监听下"插上设备就能用"本来就不需要任何操作，
 * 而"采集跟着窗口走"反而会让**刚开窗口那一瞬间的按键丢掉**，
 * 也会让"先插设备、再开窗口"的用户疑惑设备怎么没反应。
 *
 * 主页仍然**只做开关**：窗口怎么建、怎么摆全在 [OverlayService] 里。
 * 采集状态（没授权、服务没起来）以"状态卡"形式提示，但不提供复杂操作。
 */
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    /** 点「工作状态」大卡片 → 打开采集通道页（见 `WorkStatusCard` 的说明） */
    onOpenCaptureMode: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var batteryIgnored by remember {
        mutableStateOf(PermissionHelper.isIgnoringBatteryOptimizations(context))
    }

    // 服务是异步启动的，用轮询反映"是否真的在跑"，而不是只在进场时查一次
    var serviceRunning by remember { mutableStateOf(OverlayService.isRunning()) }

    /** 正在编辑设置的那一份配置；非 null 时弹设置窗 */
    var editing by remember { mutableStateOf<KeyStrokesConfig?>(null) }

    /** 开出第 4 个窗口时的提示；用户点掉就没了 */
    var showManyWindowsHint by remember { mutableStateOf(false) }

    /*
     * 采集状态。
     *
     * 全部来自 [CaptureSession]（采集层写的线程安全状态），
     * 这里只负责订阅展示 —— UI 不掺和采集逻辑。
     */
    val captureState by CaptureSession.state.collectAsState()
    val captureChannel by CaptureSession.channel.collectAsState()
    /*
     * ⚠️ 这里原来还订阅了 `CaptureSession.deviceCount` —— 已经删掉。
     *
     * ⚠️ 它唯一的用处是旧的「工作状态细节卡」里那句"正在监听 n 个设备"，
     * 而那张卡按用户要求已经删了（细节搬进 `CaptureModeScreen`，
     * 那里自己订阅）。留在主页只会是一份**没人读的订阅**。
     */
    val captureMessage by CaptureSession.message.collectAsState()

    // 配置每次重组现取：只有个位数，复制开销可忽略，换来的是"改完立刻是最新的"
    val configs = viewModel.allConfigs()
    val enabledIds = viewModel.overlayEnabledIds
    val hasAnyEnabled = enabledIds.isNotEmpty()
    // 改名/增删不会改变集合，靠版本号保证列表刷新
    val configRevision = viewModel.configVersion

    /*
     * ============================================================
     * 配置组合
     * ============================================================
     * ⚠️ 主页的排布（用户的要求）:
     * > 配置组合在主页也是显示配置组合名称+配置组合字样……左边的配置按钮
     * > 则换成向下的小三角，点击可以展开……展开后下面就是这个分组里的每个配置了
     *
     * ⚠️ 与配置页不同:主页**不隐藏**组合成员的配置行 ——
     * 组合行下面展开的就是它们，而"零散配置"仍然各自一行。
     * 也就是说主页显示的是**全部配置**（分组的不分组的一起），
     * 只是分组的那些在组合行展开后出现，避免重复显示。
     */
    val groups = viewModel.groups
    val groupedIds = remember(groups) { groups.flatMap { it.memberIds }.toSet() }
    val ungroupedConfigs = configs.filterNot { it.id in groupedIds }

    /* 展开状态:⚠️ 刻意不持久化（重组后收起），见方案文档 §7.2 */
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                /* ⚠️ 无障碍状态由上面那个轮询维护，这里只管电池优化 */
                batteryIgnored = PermissionHelper.isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    /*
     * 有开关开着时持续确认服务是否真的在跑。
     *
     * 为什么需要轮询：startForegroundService 是异步的，请求刚发出时服务还没起来，
     * 那一刻查状态必然是 false —— 只查一次就会显示成"服务未在运行"。
     * 服务被系统回收同理，轮询能让开关自己回到关闭状态。
     *
     * ⚠️ key 只用"有没有开关开着"，**不能把 serviceRunning 也放进去**：
     * 那会让"检测到服务起来了"这件事本身重启这个 effect，
     * 把正在跑的轮询连同它的延时一起取消掉，状态就会来回抖。
     */
    LaunchedEffect(hasAnyEnabled) {
        if (!hasAnyEnabled) {
            serviceRunning = false
            return@LaunchedEffect
        }

        // 给服务一点启动时间：连续两次都查不到才认为它失败了
        serviceRunning = OverlayService.isRunning()
        if (!serviceRunning) {
            delay(SERVICE_CHECK_INTERVAL_MS)
            serviceRunning = OverlayService.isRunning()
        }

        while (true) {
            if (!OverlayService.isRunning()) {
                /*
                 * 服务没了（被系统回收，或者用户刚刚划掉了应用又切回来）。
                 *
                 * 这里**不再只是记一条日志**：开关是持久化的，而这个服务不会
                 * 自己再起来，所以留着它们就是一群"亮着但屏幕上什么都没有"的开关 ——
                 * 用户唯一的出路是手动一个个关掉。直接对齐成"全部关闭"最省事，
                 * 也与"退出重进后回到关闭状态"这条既定行为一致。
                 *
                 * 传 force：本方法内部会再确认一次服务确实没在跑，
                 * 避免把"正在启动中"误判成"已停止"。
                 */
                AppLog.w(TAG, "检测到悬浮窗服务已停止，把开关对齐为全部关闭")
                serviceRunning = false
                viewModel.syncOverlayState(force = true)
                return@LaunchedEffect
            }
            serviceRunning = true
            delay(SERVICE_CHECK_INTERVAL_MS)
        }
    }

    ScreenScaffold(
        title = "主页",
        subtitle = "打开某个配置的开关，即可在屏幕上看到它的悬浮窗（可同时开多个）",
        modifier = modifier,
    ) { innerPadding ->
        /*
         * ============================================================
         * 整页可滚动（以前不是，配置一多就废了）
         * ============================================================
         * 这里原本是个**不能滚动**的 Column：配置只有两三份时看不出来，
         * 一旦多起来（每份一行 + 提示卡 + 状态卡 + 工作状态卡），
         * 超出屏幕的部分既显示不出来、也拉不下去 —— 下面的开关根本点不到。
         *
         * 用 `verticalScroll` 而不是 LazyColumn：这一页的内容是**固定几块**
         * （配置列表 / 提示 / 状态卡 / 工作状态卡），不是长列表，
         * 一个滚动容器就够，也不需要额外的懒加载与 key 管理。
         *
         * ⚠️ 必须放在 `padding(innerPadding)` **之后**：
         * 反过来的话滚动区域会连顶栏与底栏的位置一起算进去，
         * 内容会被顶栏压住一段，滚到底部还会多出一截空白。
         */
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            /*
             * ============================================================
             * ⚠️⚠️ 工作状态大卡片 —— **整页最上面**（用户要求置顶）
             * ============================================================
             * 用户的原话:
             *
             * > 这个新卡片应该**置顶，比悬浮窗列表还要提前**
             *
             * ⚠️ 它回答的是"现在到底在不在读按键" —— 用户打开应用第一眼
             * 就该看到这件事。排在配置列表后面的话，配置一多就得往下翻才看得见。
             */
            WorkStatusCard(
                captureState = captureState,
                channel = captureChannel,
                /*
                 * ⚠️ 只在这时报错详情:监听中 / 未监听时这张卡保持干净。
                 * 用户的原话是"只保留新的这个" —— 所以没有第二张细节卡了，
                 * 但**失败原因必须能看见**，否则他只知道"失败了"却不知道
                 * 为什么（下一步就是点卡片进「采集通道」页）。
                 */
                message = captureMessage,
                onOpenCaptureMode = onOpenCaptureMode,
            )

            Spacer(modifier = Modifier.height(4.dp))

            /*
             * ============================================================
             * 配置列表：每项一个开关 + 一个编辑按钮
             * ============================================================
             */

            SettingsCard {
                /*
                 * ============================================================
                 * 配置组合行（在零散配置**之前**）
                 * ============================================================
                 * ⚠️ 组合行左侧是**展开三角**（用户要求），不是配置按钮 ——
                 * 组合本身没有悬浮窗，也就没有"可触摸 / 可移出屏幕"可调。
                 * ⚠️ 展开后的成员行**保留各自的配置按钮**（用户明确要求）。
                 */
                groups.forEachIndexed { index, group ->
                    if (index > 0) CardDivider()

                    val memberConfigs = group.memberIds.mapNotNull { id ->
                        configs.firstOrNull { it.id == id }
                    }
                    val expanded = expandedGroups[group.id] == true
                    val groupOn = viewModel.isGroupOn(group)

                    GroupOverlayRow(
                        group = group,
                        memberCount = memberConfigs.size,
                        enabled = groupOn,
                        expanded = expanded,
                        onToggleExpand = { expandedGroups[group.id] = !expanded },
                        onToggle = { on -> viewModel.setGroupOverlayEnabled(group, on) },
                    )

                    if (expanded) {
                        memberConfigs.forEach { config ->
                            CardDivider()
                            OverlayConfigRow(
                                config = config,
                                enabled = config.id in enabledIds,
                                onEdit = { editing = config },
                                onToggle = { on ->
                                    if (on) requestNotificationPermissionIfNeeded(context)
                                    viewModel.setConfigOverlayEnabled(config.id, on)
                                },
                                /* ⚠️ 成员行缩进一级，表示从属于上面的组合 */
                                indented = true,
                            )
                        }
                    }
                }

                /* ⚠️ 零散配置（不属于任何组合的） */
                ungroupedConfigs.forEachIndexed { index, config ->
                    if (index > 0 || groups.isNotEmpty()) CardDivider()
                    OverlayConfigRow(
                        config = config,
                        enabled = config.id in enabledIds,
                        // 编辑按钮的可用性就是"这个窗口开着没"
                        onEdit = { editing = config },
                        onToggle = { on ->
                            if (on) requestNotificationPermissionIfNeeded(context)
                            viewModel.setConfigOverlayEnabled(config.id, on)

                            /*
                             * 第 4 个窗口才提示一次（3 个及以内不提）。
                             *
                             * 提示而不是拦截：低端机开多了确实会卡，
                             * 但那取决于机型与配置，硬拦会让高端机用户莫名其妙。
                             */
                            if (on && config.id !in enabledIds &&
                                enabledIds.size + 1 > SUGGESTED_MAX_WINDOWS
                            ) {
                                showManyWindowsHint = true
                            }
                        },
                    )
                }

                if (configs.isEmpty()) {
                    // 理论上不会发生（ConfigStore 保证至少有内置 Default），留个兜底
                    Text(
                        text = "还没有任何配置，请到「配置」页新建一个。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            SectionHint(
                text = if (enabledIds.isEmpty()) {
                    "每个开关对应屏幕上的一个悬浮窗；可以只开一个，也可以同时开多个。"
                } else {
                    "正在显示 ${enabledIds.size} 个悬浮窗（直接拖动可移动位置）。" +
                        "全部关闭后才会停止按键监听。"
                },
            )

            /*
             * ============================================================
             * 状态提示
             * ============================================================
             */

            /*
             * ============================================================
             * ⚠️⚠️ 这里原来是「缺少悬浮窗权限」，现在换成「无障碍服务」
             * ============================================================
             * 改用 `TYPE_ACCESSIBILITY_OVERLAY` 之后:
             *
             * | 以前 | 现在 |
             * |---|---|
             * | 缺悬浮窗权限 → 显示不出按键 | 缺**无障碍服务** → 显示不出按键 |
             *
             * ⚠️ 这是**用户拍板的结果**，不是退步:
             * 用户原话「这样就**不需要用户单独授权悬浮窗权限**了」。
             * 而对用户来说，这两个东西都是"去系统里点一下"，步骤数一样。
             *
             * ⚠️ 而且无障碍这一项**本来就要有**（纯无障碍通道也靠它），
             * 所以是"一举两得"，不是多一个负担。
             */
            /*
             * ============================================================
             * ⚠️⚠️ 这里**没有**「需要开启无障碍服务」那张卡了（用户要求删掉）
             * ============================================================
             * 用户的原话:
             *
             * > 我觉得主页无障碍未授权的卡片可以删掉，**开启悬浮窗的时候就会有
             * > 校验了**，不用多此一举
             *
             * ⚠️ 他说得对。无障碍**只在"要显示悬浮窗"时才是必需的** ——
             * 而那一刻本来就有校验（`OverlayController.enableConfigs` 与
             * `OverlayService.onCreate` 都会检查，没连上就跳系统无障碍页）。
             *
             * ⚠️ 在主页提前挂一张红卡有两个坏处:
             *
             * | 问题 | 说明 |
             * |---|---|
             * | 多余 | 用户还没打算开窗口，就被要求去授权 |
             * | ⚠️ **误导** | 有 root/Shizuku 的用户**根本不用自己开**（我们替他开），那张卡却让他以为必须手动点 |
             *
             * ⚠️ 所以删掉。真要提示的那一刻（点开关时）再说，那才有意义。
             */

            /*
             * ============================================================
             * ⚠️⚠️ 这里**原来还有一张「工作状态细节卡」，已经删掉**
             * ============================================================
             * 用户的原话:
             *
             * > 原来旧的工作状态卡片应该**删掉，只保留新的这个**
             *
             * ⚠️ 它里面的东西没有丢，而是**搬到了该在的地方**:
             *
             * | 原来在细节卡里的 | 现在在哪 |
             * |---|---|
             * | 三条通道可用性（Root / Shizuku / 无障碍） | `CaptureModeScreen` 的「当前环境」卡 |
             * | 实际通道与设备数 | 同上 |
             * | 失败原因 | ⚠️ 已并入上面那张大卡片（只在有问题时出现） |
             * | 无障碍「只能读键盘」的红字 | `CaptureModeScreen` |
             * | 「重启监听」按钮 | `CaptureModeScreen` 顶栏 |
             *
             * ⚠️ 为什么这样更对:那些都是**「采集通道」自己的细节**，
             * 点卡片进去看才顺 —— 摊在主页上会把它变成一块信息面板，
             * 而且会把配置列表挤到看不见的地方。
             */

            if (!batteryIgnored) {
                StatusCard(
                    isError = false,
                    title = "建议忽略电池优化",
                    description = "系统可能在后台冻结本应用，导致按键显示时有时无。",
                    actionLabel = "去设置",
                    onAction = { PermissionHelper.requestIgnoreBatteryOptimizations(context) },
                )
            }

            /*
             * ============================================================
             * 工作状态
             *
             * 只剩上面那张大卡片（细节卡已删）。这里不再放第二张。
             * ============================================================
             */

            SectionHint(text = "提示：每个配置的键位、颜色与透明度在「配置」页调整，改动会立即应用到它的悬浮窗。")

            Spacer(modifier = Modifier.height(24.dp))
        }
    }


    /*
     * ============================================================
     * 悬浮窗设置弹窗
     *
     * 只在这个窗口开着时才可能打开（编辑按钮那时才可点），
     * 所以这里不再额外判断开关状态。
     * ============================================================
     */

    editing?.let { config ->
        val layout = viewModel.overlayLayoutOf(config.id)

        OverlaySettingsDialog(
            configName = config.name,
            layout = layout,
            /*
             * 窗口尺寸按**同一个函数**算（与 Service 建窗口时同源）。
             * 这里要是自己估一个尺寸，滑块的行程就会和真实窗口对不上 ——
             * 表现是"推到尽头还差一点"或"推到一半就不动了"。
             *
             * 不需要传 CPS 数值：决定尺寸的只有配置本身（见 OverlayService.windowSizePx）。
             */
            windowWidth = overlayWindowWidthPx(config).toInt(),
            windowHeight = overlayWindowHeightPx(config).toInt(),
            screenWidth = OverlayBounds.screenSize(context).first,
            screenHeight = OverlayBounds.screenSize(context).second,
            onTouchableChange = { viewModel.setOverlayTouchable(config.id, it) },
            onMovableOffScreenChange = { viewModel.setOverlayMovableOffScreen(config.id, it) },
            onOffsetChange = { x, y -> viewModel.setOverlayOffset(config.id, x, y) },
            onReset = { viewModel.resetOverlayPosition(config.id) },
            onDismiss = { editing = null },
        )
    }

    if (showManyWindowsHint) {
        AlertDialog(
            onDismissRequest = { showManyWindowsHint = false },
            title = { Text("同时开启了多个悬浮窗") },
            text = {
                Text(
                    "现在有 ${enabledIds.size} 个悬浮窗同时在屏幕上。\n\n" +
                        "每个 Live2D 悬浮窗都是一个持续渲染的网页视图，" +
                        "开得太多会明显增加耗电，低配置设备上还可能卡顿。\n\n" +
                        "如果觉得卡，关掉用不到的那几个即可。",
                )
            },
            confirmButton = {
                TextButton(onClick = { showManyWindowsHint = false }) { Text("知道了") }
            },
        )
    }
}

/**
 * 配置列表里的一行：编辑按钮 + 名称 + 开关。
 *
 * ============================================================
 * 为什么编辑按钮在左边、而且未开启时置灰
 * ============================================================
 * - 放左边：右侧留给开关，整行点开关是最常用的动作，位置固定才好形成肌肉记忆；
 * - 置灰：编辑的内容是"这个窗口在屏幕上怎么摆、能不能点"，
 *   窗口都没开的时候调它没有任何可见效果 —— 与其让用户对着一个没反应的弹窗发呆，
 *   不如直接告诉他"先开起来"。
 */
@Composable
private fun OverlayConfigRow(
    config: KeyStrokesConfig,
    enabled: Boolean,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    /**
     * 是否是**组合里的成员行**。
     *
     * ⚠️ 缩进一级表示从属关系；控件（配置按钮 + 开关）**完全不变** ——
     * 用户明确要求"展开后显示的单独配置，左侧仍然有配置按钮"。
     */
    indented: Boolean = false,
) {
    /*
     * ⚠️ 用共享的 `SettingRowSurface`（白底圆角矩形）+ `IconTileButton`（圆角方块），
     * 而不是裸 `Row` + `IconButton` —— 用户报的「全都糊在背景上，没有对应的圆角矩形,
     * 左边的配置按钮背景也没有圆角正方形」。
     *
     * ⚠️ 原来这行自己在 `Row` 上写 `padding` 去迁就 `IconButton` 的 48dp 触摸区，
     * 现在交给 `SettingRowSurface` 统一处理，别在这里再补一层。
     */
    Box(
        modifier = if (indented) Modifier.padding(start = 20.dp) else Modifier,
    ) {
    SettingRowSurface {
        IconTileButton(
            icon = Icons.Default.Tune,
            contentDescription = if (enabled) "悬浮窗设置" else "先打开开关才能调整",
            enabled = enabled,
            onClick = onEdit,
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = config.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = styleIcon(config),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = config.style.label + if (enabled) " · 显示中" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        ExpressiveSwitch(
            checked = enabled,
            onCheckedChange = onToggle,
        )
    }
    }
}

/** 样式 → 图标；与配置列表页保持同一套映射 */
private fun styleIcon(config: KeyStrokesConfig) = when (
    OverlayStyleRegistry.find(config.styleId)?.iconKey
) {
    OverlayStyleRegistry.ICON_LIVE2D -> Icons.Default.Person
    OverlayStyleRegistry.ICON_KEYBOARD -> Icons.Default.Keyboard
    OverlayStyleRegistry.ICON_CUSTOM -> Icons.Default.DashboardCustomize
            OverlayStyleRegistry.ICON_GAMEPAD -> Icons.Default.SportsEsports
    else -> Icons.Default.Tune
}

/**
 * 「工作状态」**大卡片** —— 视觉参照 SukiSU / KSU 管理器的首页状态卡。
 *
 * ============================================================
 * ⚠️ 为什么换成这个样子（用户要求）
 * ============================================================
 * 用户的原话:
 *
 * > 把主页的工作状态卡片换成类似于 sukisu / ksu 那样的 root 管理器风格，
 * > 我认为那个 UI **非常美观**……主页卡片显示依次是:
 * > 左上方 **LKM 角标**，左下方「工作中」主标题，左下方「版本」副标题，
 * > 我认为可以借鉴，左上方改为「**Root / Shizuku / 无障碍**」，
 * > 左下方主标题「**监听中**」，副标题「**v 版本号**（从 versionName 获取）」
 *
 * ⚠️ 布局与 SukiSU 的 `ActiveStatusCard` **逐块对应**（源码只读参考）:
 *
 * | SukiSU | 我们 |
 * |---|---|
 * | `Surface(color = primary, shape = RoundedCornerShape(28.dp))` | 同（颜色按状态变） |
 * | `drawBehind { drawStatusRings(...) }` 右上角同心圆 | ⭐ 同（纯装饰，不参与布局） |
 * | 左上 `FlowRow` 里放 `StatusTag("LKM")` | 左上 `StatusTag("Root"/"Shizuku"/"无障碍")` |
 * | 左下 `Text("工作中", displaySmall, Black)` | 左下 `Text("监听中", displaySmall, Black)` |
 * | 左下 `Text("版本 xxx", bodyMedium, Monospace)` | 左下 `Text("v2.7.0", bodyMedium, Monospace)` |
 * | `heightIn(min = 188.dp)` + `SpaceBetween` | 同 |
 *
 * ⚠️ **只借结构，不借配色来源**:SukiSU 用 `primary` 硬顶，我们按
 * [CaptureState] 换色 —— 因为"监听失败"和"监听中"必须一眼分开，
 * 全都刷成同一种绿的话用户看不出出事了。
 *
 * ⚠️ 那些**诊断细节**（三条通道可用性、设备数、失败原因、重启监听）
 * 没有丢，只是从这张卡里**挪到下面那张紧凑卡**了 ——
 * 这张卡要留白才好看，塞满文字就变回原来那个样子。
 */
@Composable
private fun WorkStatusCard(
    captureState: CaptureState,
    channel: CaptureChannel?,
    /**
     * 点整张卡 → 打开「采集通道」页。
     *
     * ============================================================
     * ⚠️⚠️ 照 SukiSU 的做法（用户指定）
     * ============================================================
     * 用户的原话:
     *
     * > 调试页的工作模式切换的卡片，也可以做在**我们这一班搞的工作状态卡片里**，
     * > sukisu 的卡片点开后是关于 boot 之类的功能，**我们把那个页面换一下就行了**
     *
     * ⚠️ SukiSU 的 `StatusCard` 的 `onClick` 是 `navigator.push(Route.Install())`
     * —— 推开一个**独立页面**，不是对话框、不是抽屉。所以这里也一样。
     *
     * ⚠️ 这解决了两件事:
     *
     * | 问题 | 现在 |
     * |---|---|
     * | 「切换采集通道」藏在调试页里，普通用户以为它很危险 | ✅ 就在主页，点卡片即达 |
     * | 卡片只是个"看板"，点了没反应 | ✅ 整张卡是个入口（有 ripple） |
     */
    onOpenCaptureMode: () -> Unit,
    /**
     * 失败原因（只有出问题时才用得上）。
     *
     * ⚠️ 用户要求"只保留新的这个"卡片，所以旧的细节卡删了。但
     * **失败原因不能跟着一起消失** —— 否则用户只看到「监听失败」两个字，
     * 完全不知道为什么。所以它并进这张卡，并且**只在有问题时显示**。
     */
    message: String = "",
) {
    /*
     * ============================================================
     * ⚠️⚠️ 卡片**只有一种配色**，不随状态变色（用户要求）
     * ============================================================
     * 用户的原话:
     *
     * > 然后是卡片颜色，不同状态可以**不用变色**吧，变色感觉更丑一些
     *
     * ⚠️ 我原来按状态给了四种容器色（primary / tertiary / errorContainer /
     * surfaceVariant）。实测确实更丑:
     *
     * | 问题 | 说明 |
     * |---|---|
     * | 观感跳 | 状态一秒变几次，卡片颜色就跟着闪几次 |
     * | 主次乱 | 四种色彼此没有层级关系，"未监听"和"失败"看起来一样重要 |
     * | ⚠️ 与 SukiSU 不一致 | 它那张卡**永远是 `primary`** —— 这就是它好看的原因之一 |
     *
     * ⚠️ 那"失败"怎么让人看出来?靠**文字本身**（`CaptureState.label`
     * 就是「监听失败」）加下面那行原因。状态靠语义表达，不靠换色。
     */
    val containerColor = MaterialTheme.colorScheme.primary
    val contentColor = MaterialTheme.colorScheme.onPrimary

    /*
     * ⚠️ 角标写的是**通道**，而不是"当前模式"。
     *
     * ⚠️ 为什么:`requestedMode` 可能是"自动"，用户看不出实际用的是哪条 ——
     * 而他最想知道的就是"到底靠什么在读"。
     * ⚠️ `channel` 为 null 时（还没起来）写"—" ——
     * 角标很短，"未知"两个字会把圆角撑得难看。
     */
    val badge = channel?.label ?: "—"

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = containerColor,
        contentColor = contentColor,
        /* ⚠️ 28dp 是照 SukiSU 的 `HeroShape` —— 比普通卡片(16dp)更圆，显"大" */
        shape = RoundedCornerShape(28.dp),
        /*
         * ⚠️ **整张卡可点** → 打开采集通道页（照 SukiSU 的 `Surface(onClick)`）。
         *
         * ⚠️ 用带 `onClick` 的 `Surface` 而不是外面套一层 `Modifier.clickable`:
         * 前者会把**圆角**也考虑进 ripple 的裁剪范围，套 clickable 的话
         * 水波纹会在 28dp 圆角外面露出方角。
         */
        onClick = onOpenCaptureMode,
    ) {
        Box(
            /*
             * ⚠️ 装饰圆环画在**背景**里（`drawBehind`），不是真的放组件 ——
             * 放组件会占布局空间，而且会和角标/标题抢触摸事件。
             */
            modifier = Modifier.drawBehind {
                drawStatusRings(contentColor.copy(alpha = 0.18f))
            },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 188.dp)
                    .padding(22.dp),
                /* ⚠️ SpaceBetween = 角标顶到上边、标题沉到下边，中间留白 */
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                /*
                 * 顶部一行:通道角标 + 右侧的"可点"提示。
                 *
                 * ⚠️ 「重启监听」已经搬进那个页面了（见函数参数说明）。
                 * 这里换成一个 `chevron` —— 它**只是视觉提示**，不单独响应点击:
                 * 整张卡已经是点击目标，再套一个可点的小图标反而让
                 * "点哪儿是什么"变得含糊。
                 */
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusTag(
                        label = badge,
                        backgroundColor = contentColor,
                        contentColor = containerColor,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = contentColor.copy(alpha = 0.75f),
                        modifier = Modifier.size(20.dp),
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        /*
                         * ⚠️ 直接用 [CaptureState.label]:它的取值就是
                         * "未监听 / 启动中 / 监听中 / 监听失败" ——
                         * 正好是用户要的「监听中」，而且是**单一真源**，
                         * 不要在卡片里另写一套文案（两套必然漂开）。
                         */
                        text = captureState.label,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Black,
                    )
                    Text(
                        /*
                         * ⚠️ 副标题是**软件版本号**（用户明确要求"从 versionName 获取"），
                         * 不是内核版本 —— 我们没有内核版本这个概念。
                         *
                         * ⚠️ 等宽字体:版本号里有数字与点，等宽看起来更像"技术信息"，
                         * 而且与 SukiSU 那一行观感一致。
                         */
                        text = "v" + BuildConfig.VERSION_NAME,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )

                    /*
                     * ⚠️ 只有**出问题时**才多这一行。
                     *
                     * ⚠️ 判据用 `captureState != RUNNING` 而不是只判 FAILED:
                     * 「未监听」时也可能带着原因（比如"无障碍服务未开启"），
                     * 那同样是用户需要看到的。
                     *
                     * ⚠️ 正常监听时这一行**不存在** —— 卡片保持只有
                     * 「角标 + 标题 + 版本」三样，留白才够。
                     */
                    if (captureState != CaptureState.RUNNING && message.isNotBlank()) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            /* ⚠️ 用半透明内容色，不用红色 —— 底色已经是 primary，红字在上面看不清 */
                            color = contentColor.copy(alpha = 0.85f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 右上角的同心圆装饰 —— **照抄 SukiSU 的 `drawStatusRings`**。
 *
 * ⚠️ 三层描边 + 一个实心点，半径 112 / 84 / 56 / 18 dp，圆心在
 * "右上角往里缩 36dp、往下 32dp"的位置 —— 这些数字是它调好的观感，
 * 自己另编一套不会更协调。
 *
 * ⚠️ 第二层用**虚线**（4dp 实 / 8dp 空）:三层全实线会像水印，太抢眼。
 *
 * ⚠️ 参数 [color] 传的是**已经乘过 alpha 的内容色** ——
 * 为什么不在函数里乘:调用方才知道这张卡的对比度够不够。
 */
private fun DrawScope.drawStatusRings(color: Color) {
    val center = Offset(size.width - 36.dp.toPx(), 32.dp.toPx())
    val stroke = 2.dp.toPx()
    drawCircle(color = color, radius = 112.dp.toPx(), center = center, style = Stroke(stroke))
    drawCircle(
        color = color,
        radius = 84.dp.toPx(),
        center = center,
        style = Stroke(
            stroke,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 8.dp.toPx())),
        ),
    )
    drawCircle(color = color, radius = 56.dp.toPx(), center = center, style = Stroke(stroke))
    drawCircle(color = color, radius = 18.dp.toPx(), center = center)
}

/**
 * 角标（"Root" / "Shizuku" / "无障碍"）—— 照抄 SukiSU 的 `StatusTagMaterial`。
 *
 * ⚠️ 它是**反色**的:背景用卡片的文字色、文字用卡片的背景色。
 * 于是无论卡片换成什么色，角标永远清晰 —— 不用为每种状态各配一对颜色。
 *
 * ⚠️ 圆角 4dp、内边距 上下 2dp / 左右 4dp:小尺寸才是"角标"的观感，
 * 做大了就变成按钮。
 */
@Composable
private fun StatusTag(
    label: String,
    backgroundColor: Color,
    contentColor: Color,
) {
    Box(
        modifier = Modifier
            .background(color = backgroundColor, shape = RoundedCornerShape(4.dp)),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(vertical = 2.dp, horizontal = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = contentColor,
        )
    }
}
/**
 * 悬浮窗需要前台服务保活，Android 13+ 还需要通知权限才能显示那条常驻通知。
 *
 * 这里只做"请求"，拒绝也不拦着用户开启悬浮窗：
 * 通知被拒顶多是少一条提示，不该因此不让用功能。
 */
private fun requestNotificationPermissionIfNeeded(context: android.content.Context) {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return

    val granted = androidx.core.content.ContextCompat.checkSelfPermission(
        context,
        android.Manifest.permission.POST_NOTIFICATIONS,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    if (granted) return

    (context as? android.app.Activity)?.let { activity ->
        androidx.core.app.ActivityCompat.requestPermissions(
            activity,
            arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
            NOTIFICATION_PERMISSION_REQUEST_CODE,
        )
    }
}

private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 2001

/** 状态提示卡：有问题时给用户一个明确的下一步动作 */
@Composable
private fun StatusCard(
    isError: Boolean,
    title: String,
    description: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsCard(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isError) Icons.Default.Error else Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = if (isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

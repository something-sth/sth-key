package com.something.sthkey.ui.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DashboardCustomize
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import android.widget.Toast
import androidx.compose.material.icons.filled.AddToHomeScreen
import com.something.sthkey.data.shortcut.ShortcutPublisher
import com.something.sthkey.data.shortcut.ShortcutSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.something.sthkey.data.shortcut.ShortcutIcons
import java.io.File
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.something.sthkey.capture.CaptureChannel
import com.something.sthkey.capture.CaptureSession
import com.something.sthkey.capture.CaptureState
import com.something.sthkey.capture.OverlayBounds
import com.something.sthkey.capture.OverlayService
import com.something.sthkey.core.log.AppLog
import com.something.sthkey.data.permission.PermissionHelper
import com.something.sthkey.domain.capture.CaptureCapabilities
import com.something.sthkey.domain.config.KeyStrokesConfig
import com.something.sthkey.domain.style.OverlayStyleRegistry
import com.something.sthkey.ui.MainViewModel
import com.something.sthkey.ui.component.CardDivider
import com.something.sthkey.ui.component.ScreenScaffold
import com.something.sthkey.ui.component.SectionHint
import com.something.sthkey.ui.component.SettingsCard
import com.something.sthkey.ui.overlay.overlayWindowHeightPx
import com.something.sthkey.ui.overlay.overlayWindowWidthPx
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
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 每次回到前台都重新检查权限：用户可能刚从系统设置页返回
    var overlayGranted by remember { mutableStateOf(PermissionHelper.canDrawOverlays(context)) }
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
    val deviceCount by CaptureSession.deviceCount.collectAsState()
    val captureMessage by CaptureSession.message.collectAsState()

    // 配置每次重组现取：只有个位数，复制开销可忽略，换来的是"改完立刻是最新的"
    val configs = viewModel.allConfigs()
    val enabledIds = viewModel.overlayEnabledIds
    val hasAnyEnabled = enabledIds.isNotEmpty()
    // 改名/增删不会改变集合，靠版本号保证列表刷新
    val configRevision = viewModel.configVersion

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = PermissionHelper.canDrawOverlays(context)
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

    /*
     * ============================================================
     * 桌面快捷方式
     * ============================================================
     * 已勾选的配置从偏好里读 —— 快捷方式的 Intent 里**不带**配置清单
     * （它钉到桌面就被系统冻结了，改不了），清单存在偏好里，
     * 所以"改选择"只要更新偏好，不必让用户重新创建快捷方式。
     */
    val shortcutContext = LocalContext.current
    var showShortcutDialog by remember { mutableStateOf(false) }


    val shortcutConfigs = remember(configs) { configs.map { it.id to it.name } }

    /*
     * 用户自己传的图标。
     *
     * ⚠️ 只存在**内存**里、不落偏好：它是"这一次创建快捷方式想用哪张图"，
     * 而快捷方式的图标在钉下去那一刻就被系统读走了 —— 之后留着这份文件
     * 没有任何用处，反而会在私有目录里越积越多。
     *
     * 但**文件必须落盘**（`ShortcutIcons.saveCustomIcon`）：`IconCompat`
     * 需要一份能解码的数据，而 SAF 给的 Uri 读权限是临时的。
     */
    var customIconFile by remember { mutableStateOf<File?>(null) }
    var customIconBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    val iconPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult

        val file = ShortcutIcons.saveCustomIcon(shortcutContext, uri)
        if (file == null) {
            Toast.makeText(shortcutContext, "这张图片读不出来，换一张试试", Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }

        customIconFile = file
        customIconBitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
    }

    ScreenScaffold(
        title = "主页",
        subtitle = "打开某个配置的开关，即可在屏幕上看到它的悬浮窗（可同时开多个）",
        modifier = modifier,
        actions = {
            /*
             * 与配置页的「导入配置」同一款按钮：文字比孤零零一个图标
             * 更容易看懂，"快捷方式"这个图标本身没有公认语义。
             */
            OutlinedButton(
                onClick = { showShortcutDialog = true },
                modifier = Modifier.padding(end = 8.dp),
            ) {
                Icon(
                    Icons.Default.AddToHomeScreen,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("桌面快捷方式")
            }
        },
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
             * 配置列表：每项一个开关 + 一个编辑按钮
             * ============================================================
             */

            SettingsCard {
                configs.forEachIndexed { index, config ->
                    if (index > 0) CardDivider()
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

            if (!overlayGranted) {
                StatusCard(
                    isError = true,
                    title = "缺少悬浮窗权限",
                    description = "没有该权限时无法在任何应用之上显示按键，请先前往授权。",
                    actionLabel = "去授权",
                    onAction = { PermissionHelper.openOverlaySettings(context) },
                )
            }

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
             * 不只显示"用哪条通道"，还要显示"到底有没有在读按键"。
             * 用户遇到"按了没反应"时，第一眼看这里就能判断是采集没起来，
             * 还是按键映射不对。
             * ============================================================
             */

            WorkStatusCard(
                capabilities = viewModel.capabilities,
                captureState = captureState,
                channel = captureChannel,
                deviceCount = deviceCount,
                message = captureMessage,
                requestedModeLabel = viewModel.requestedMode.label,
                resolvedModeLabel = viewModel.resolvedMode.mode.label,
                onRestartCapture = { viewModel.restartCapture() },
            )

            SectionHint(text = "提示：每个配置的键位、颜色与透明度在「配置」页调整，改动会立即应用到它的悬浮窗。")

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    /*
     * ============================================================
     * 桌面快捷方式设置
     * ============================================================
     * 勾选哪些配置 → 写入偏好 → 请求钉到桌面。
     *
     * ⚠️ **先写偏好、再钉快捷方式**，顺序不能反。
     * 快捷方式的 Intent 是冻结的，它运行时读的是偏好；偏好没写好就钉，
     * 用户第一次点那个图标会启动一个空清单。
     */
    if (showShortcutDialog) {
        /*
         * ⚠️ 刻意**不读"已创建了哪些快捷方式"**。
         *
         * 第一版做过一个列表 + 删除按钮，但应用**删不掉桌面上那个图标**
         * （那是启动器管的），结果是"列表里没了、图标还在，而且已经变成
         * 空壳"。删不干净比没有删除功能更糟，所以整块去掉了 ——
         * 要删就让用户在桌面上长按删。
         *
         * 所以每次打开这个窗口都是"新建一个"。
         */
        val iconOptions = remember(showShortcutDialog, configs) {
            buildIconOptions(shortcutContext, configs)
        }

        ShortcutSetupDialog(
            configs = shortcutConfigs,
            iconOptions = iconOptions,
            customIconBitmap = customIconBitmap,
            onPickCustomIcon = { iconPickerLauncher.launch(arrayOf("image/*")) },
            onConfirm = { disableAll, configIds, iconId ->
                showShortcutDialog = false

                val icon = resolveIcon(
                    context = shortcutContext,
                    selectedId = iconId,
                    configs = configs,
                    customIconFile = customIconFile,
                )

                val error = if (disableAll) {
                    /* 关闭类只允许一个，id 固定 */
                    ShortcutPublisher.pinDisableShortcut(shortcutContext, "关闭悬浮窗", icon)
                } else {
                    /*
                     * 每次都分配一个**新** id —— 于是每次都是"再加一个图标"，
                     * 而不是替换掉已有的那一个。
                     */
                    val id = ShortcutSettings.nextLauncherId(shortcutContext)

                    /*
                     * 顺序：先写清单，再钉。
                     *
                     * ⚠️ Intent 钉下去就冻结了，它运行时读的是偏好 ——
                     * 偏好没写好就钉，用户第一次点那个图标会启动一个空清单。
                     */
                    ShortcutSettings.setLauncherConfigIds(shortcutContext, id, configIds)
                    ShortcutPublisher.pinLaunchShortcut(
                        context = shortcutContext,
                        shortcutId = id,
                        /*
                         * 名称：一份配置就用它的名字，多份就用数量。
                         *
                         * ⚠️ 名称与图标一样，在钉下去那一刻定下，之后改配置
                         * **不会**自动更新 —— 这是系统限制。界面上有说明。
                         */
                        label = if (configIds.size == 1) {
                            configs.firstOrNull { it.id == configIds.first() }?.name
                                ?: "启动悬浮窗"
                        } else {
                            "启动 ${configIds.size} 个悬浮窗"
                        },
                        icon = icon,
                    )
                }

                /*
                 * 失败必须说清：否则用户点完确定、桌面什么都没多出来，
                 * 只能怀疑是不是坏了。最常见的原因是启动器不支持
                 * （部分国产 ROM 的自研桌面）。
                 */
                Toast.makeText(
                    shortcutContext,
                    error ?: "已请求添加到桌面，请在系统弹窗里确认",
                    Toast.LENGTH_LONG,
                ).show()
            },
            onDismiss = { showShortcutDialog = false },
        )
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
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onEdit,
            enabled = enabled,
        ) {
            Icon(
                imageVector = Icons.Default.Tune,
                contentDescription = if (enabled) "悬浮窗设置" else "先打开开关才能调整",
                tint = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    // 明确置灰：这是"不可用"的视觉信号，也是需求里点名要的
                    MaterialTheme.colorScheme.outlineVariant
                },
            )
        }

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

        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
        )
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
 * 工作状态卡片。
 *
 * 显示三类信息，回答用户最关心的三个问题：
 * 1. **能不能读**：Root / Shizuku 是否可用；
 * 2. **在读吗**：监听状态（未监听 / 启动中 / 监听中 / 失败）+ 设备数；
 * 3. **出问题了吗**：失败原因或当前提示（例如"未检测到 root"）。
 *
 * 这三件事分开显示是刻意的：旧项目把它们混在一条状态文字里，
 * 用户分不清"是没授权"还是"监听没起来"。
 */
@Composable
private fun WorkStatusCard(
    capabilities: CaptureCapabilities,
    captureState: CaptureState,
    channel: CaptureChannel?,
    deviceCount: Int,
    message: String,
    requestedModeLabel: String,
    resolvedModeLabel: String,
    /** 点「重启监听」——重新枚举并监听输入设备（getevent 不支持热添加） */
    onRestartCapture: () -> Unit,
) {
    val stateColor = when (captureState) {
        CaptureState.RUNNING -> MaterialTheme.colorScheme.primary
        CaptureState.FAILED -> MaterialTheme.colorScheme.error
        CaptureState.STARTING -> MaterialTheme.colorScheme.tertiary
        CaptureState.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    SettingsCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "工作状态",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = captureState.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = stateColor,
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            /* 通道可用性 */
            CapabilityRow("Root", capabilities.rootAvailable, capabilities.rootDetail)
            CapabilityRow("Shizuku", capabilities.shizukuReady, null)

            Spacer(modifier = Modifier.height(10.dp))

            /* 监听详情 */
            Text(
                text = buildString {
                    append("通道：$requestedModeLabel")
                    if (requestedModeLabel != resolvedModeLabel) {
                        append(" → 实际 $resolvedModeLabel")
                    }
                    channel?.let { append(" · ${it.label}") }
                    if (captureState == CaptureState.RUNNING) {
                        append(" · $deviceCount 个设备")
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
            )

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

            Spacer(modifier = Modifier.height(8.dp))

            SectionHint(text = "如需手动切换 root / Shizuku，请前往「设置 → 调试」。")

            Spacer(modifier = Modifier.height(6.dp))

            /*
             * 重启监听。
             *
             * ============================================================
             * 为什么需要它（这不是调试功能的暴露，而是当前实现的补丁）
             * ============================================================
             * 采集走的是 `getevent`，而它**不能热添加设备** ——
             * 启动时有哪些输入设备就只监听哪些。于是插上手柄、接上外接键盘
             * 之后不重启就永远读不到，"明明插上了却没反应"。
             *
             * 做法上有个取舍：
             * - 想做成"自动检测设备变化"就得轮询 `/dev/input`，代价是常驻开销，
             *   而且**解决不了根本问题**（真正该做的是 native 二进制，
             *   由它自己处理设备增删）；
             * - 一个按钮的成本几乎为零，而且用户**明确知道自己在做什么**
             *   （"我刚插了手柄 → 点一下"），不会出现"它为什么自己在重启"。
             *
             * 所以先给按钮，等 native 那套做完再撤掉。
             */
            OutlinedButton(
                onClick = onRestartCapture,
                modifier = Modifier.align(Alignment.End),
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("重启监听")
            }
        }
    }
}

/** 一行通道可用性：绿点/灰点 + 名称 + 详情 */
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
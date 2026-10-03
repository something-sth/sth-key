package com.something.sthkey

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import android.content.Intent
import com.something.sthkey.data.config.IncomingConfig
import com.something.sthkey.ui.feature.config.IncomingConfigDialog
import com.something.sthkey.core.log.AppLog
import com.something.sthkey.core.prefs.AppPrefs
import com.something.sthkey.ui.AppStage
import com.something.sthkey.ui.MainViewModel
import com.something.sthkey.ui.SubRoutes
import com.something.sthkey.ui.TopDestination
import com.something.sthkey.ui.feature.announcement.AnnouncementDialog
import com.something.sthkey.ui.feature.config.ConfigEditorScreen
import com.something.sthkey.ui.feature.config.ConfigListScreen
import com.something.sthkey.ui.feature.custom.CustomLayoutEditorScreen
import com.something.sthkey.ui.feature.debug.DebugScreen
import com.something.sthkey.ui.feature.home.HomeScreen
import com.something.sthkey.ui.feature.settings.AboutScreen
import com.something.sthkey.ui.feature.settings.SettingsScreen
import com.something.sthkey.ui.feature.setup.SetupScreen
import com.something.sthkey.ui.theme.SthKeyTheme
import kotlinx.coroutines.delay

/**
 * 应用唯一的 Activity。
 *
 * 职责被刻意压到最小：
 * - 决定进入引导页还是主界面；
 * - 承载 Compose 导航（底栏三项 + 两个二级页面）；
 * - 处理返回键语义。
 *
 * 不做任何采集 / 权限 / 配置逻辑，那些都在各自的层里。
 */
class MainActivity : ComponentActivity() {

    /**
     * 外部传进来的待导入配置包。
     *
     * ============================================================
     * ⚠️ 关键是 [onCreate] 与 [onNewIntent] **都要走这里**
     * ============================================================
     * 冷启动（应用没在运行）时只有 `onCreate` 会拿到那个 Intent；
     * 应用已在后台时只有 `onNewIntent` 会拿到。
     *
     * 只写 `onNewIntent` 的后果正是"第一次分享没用、再分享一次才行" ——
     * 因为第一次是冷启动，走了 `onCreate` 而那里没处理。
     * 所以两条路都调同一个 [handleIncomingIntent]，不存在只覆盖一条的情况。
     *
     * 用 Compose 状态（而不是普通字段）是为了让界面能**响应式**地
     * 弹出确认框：分享进来时应用可能停在任意页面，甚至还在引导页。
     */
    private var incomingRequest by mutableStateOf<IncomingConfig.Request?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        AppLog.i(TAG, "MainActivity 创建")

        /*
         * 冷启动这条路。`savedInstanceState == null` 才处理：
         * 屏幕旋转等原因重建时系统会把**原来的** Intent 再给一次，
         * 不判断的话每次转屏都会重新弹一次导入确认框。
         */
        if (savedInstanceState == null) {
            handleIncomingIntent(intent)
        }

        setContent {
            SthKeyTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SthKeyApp(
                        incomingRequest = incomingRequest,
                        onIncomingHandled = { incomingRequest = null },
                    )
                }
            }
        }
    }

    /**
     * 应用已在运行时收到新的 Intent（用户又分享/打开了一次）。
     *
     * ⚠️ 必须调 `setIntent`：不调的话 `getIntent()` 之后一直返回**旧的**
     * 那个 Intent。这里虽然不依赖它，但保持这个约定能避免以后
     * 有人读 `intent` 时拿到过期数据。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    /**
     * 从 Intent 里取出配置包并转成"待确认"状态。
     *
     * 注意这里**只是登记，不导入** —— 真正的导入在确认框里，
     * 由用户点了「导入」才发生（见 `IncomingConfigDialog`）。
     */
    private fun handleIncomingIntent(intent: Intent?) {
        val request = IncomingConfig.extract(intent) ?: return
        AppLog.i(TAG, "收到外部配置包：action=${intent?.action}")
        incomingRequest = request
    }

    private companion object {
        const val TAG = "App"
    }
}

/**
 * 应用根组件。
 *
 * 引导页与主界面是**并列**的两个阶段，而不是"盖一层遮罩"：
 * 引导完成后不应留在返回栈里，否则用户在主界面按返回键会退回引导页。
 */
@Composable
private fun SthKeyApp(
    viewModel: MainViewModel = viewModel(),
    /** 外部分享/打开进来的配置包；非 null 时弹确认框 */
    incomingRequest: IncomingConfig.Request? = null,
    onIncomingHandled: () -> Unit = {},
) {
    // rememberSaveable：进程被系统回收重建时仍停留在用户当时的状态
    var stage by rememberSaveable { mutableStateOf(AppStage.SETUP.name) }
    var showAnnouncement by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        /*
         * 配置列表已经就绪：版本号 +1，主页那个"每配置一个开关"的列表
         * 才会在首帧就显示出全部配置（它按版本号决定何时重取列表）。
         */
        viewModel.refreshConfigs()

        // 权限齐全（此前已走过引导）则直接进主界面，这是对旧项目的主要优化之一
        if (viewModel.isSetupCompleted && stage == AppStage.SETUP.name) {
            stage = AppStage.MAIN.name
            AppLog.i("App", "已完成引导，直接进入主界面")
        }
        // 主界面需要展示"当前采集通道"，因此无论如何都先探测一次能力
        viewModel.refreshCapabilities()
    }

    /*
     * 更新公告在**进入主界面之后**才弹，而不是盖在权限引导页上：
     * 引导期用户还没拿到授权，先看到"更新了什么"没有意义，
     * 也容易和授权弹窗抢焦点。
     *
     * 用 stage 作 key：走过引导（或从设置里重进引导再回来）时都会重新判断，
     * 但判断读的是"已读版本"，已确认过就不会重复弹。
     */
    LaunchedEffect(stage) {
        if (stage == AppStage.MAIN.name && viewModel.shouldShowAnnouncement) {
            showAnnouncement = true
        }
    }

    when (stage) {
        AppStage.SETUP.name -> {
            SetupScreen(
                viewModel = viewModel,
                onFinished = { stage = AppStage.MAIN.name },
            )
        }

        else -> {
            MainScaffold(
                viewModel = viewModel,
                onOpenSetup = { stage = AppStage.SETUP.name },
            )
        }
    }

    if (showAnnouncement) {
        AnnouncementDialog(
            initialVersion = BuildConfig.VERSION_NAME,
            // 首次阅读：读满 3 秒才能确认，且不能点外部关掉
            requireReading = true,
            onConfirm = {
                viewModel.markAnnouncementSeen()
                showAnnouncement = false
            },
            onDismiss = null,
        )
    }

    /*
     * ============================================================
     * 外部分享/打开进来的配置包
     * ============================================================
     * 放在**最外层**（而不是某个页面里）：分享进来时应用可能停在任意页面，
     * 也可能还在引导页 —— 挂在具体页面上就会漏掉那些情况。
     *
     * 导入成功后刷新配置列表：`ConfigListScreen` 按版本号重取列表，
     * 不刷新的话用户切到配置页看不到刚导入的那一份
     * （表现是"导入了但列表里没有，重启才出现"）。
     */
    IncomingConfigDialog(
        request = incomingRequest,
        onImported = {
            viewModel.reloadConfigs()
            /*
             * 顺手切到配置页：用户刚导入一份配置，最可能想立刻看到它 / 改它。
             * 停在原来的页面上会让人以为"导入完了但什么也没发生"。
             */
            stage = AppStage.MAIN.name
        },
        onDismiss = onIncomingHandled,
    )
}

/**
 * 主界面骨架：底部导航 + 导航图。
 *
 * 底栏只在一级页面显示；进入调试 / 关于这类二级页面时隐藏，
 * 让二级页面回到全屏专注的状态（也更符合 Material 的层级习惯）。
 */
@Composable
private fun MainScaffold(
    viewModel: MainViewModel,
    onOpenSetup: () -> Unit,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    // 一级页面才显示底栏；子路由（含带参数的配置编辑页）一律隐藏
    val showBottomBar = TopDestination.of(currentRoute) != null

    /*
     * 是否正在切换底栏标签。
     *
     * 动画期间**整个内容区都不吃触摸**（见下面的覆盖层）：
     * 让"点在新页面上"生效听起来更聪明，实际会引入更多怪问题 ——
     * 页面在滑动，手指落下的位置对应哪个控件是不确定的，
     * 用户以为点的是 A、实际点到了刚滑过来的 B 上。
     * 这 220ms 内什么都不响应，是最可预测的行为。
     */
    var tabTransitioning by remember { mutableStateOf(false) }
    LaunchedEffect(tabTransitioning) {
        if (tabTransitioning) {
            delay(TRANSITION_DURATION_MS.toLong())
            tabTransitioning = false
        }
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                SthKeyBottomBar(
                    currentRoute = currentRoute,
                    onSelect = { destination ->
                        // 标记"正在切标签"，动画期间挡住内容区的触摸
                        if (destination.route != currentRoute) tabTransitioning = true
                        navController.switchTab(destination)
                    },
                )
            }
        },
    ) { innerPadding ->
        /*
         * 内容区外面再套一层：切标签的动画期间盖一层吃掉所有触摸的透明层。
         *
         * 覆盖层在 NavHost **之上**（Box 里后写的在上层），所以它拦得住
         * 正在滑动的两个页面 —— 包括"新页面已经滑进来一半"的情况。
         */
        Box(modifier = Modifier.padding(innerPadding)) {
            NavHost(
            navController = navController,
            startDestination = TopDestination.HOME.route,
            modifier = Modifier.padding(innerPadding),
            /*
             * 切页动画：横向滑动，方向**按标签索引**决定，时长明显缩短。
             *
             * 默认动画是 700ms 的叠化（fade），有两个问题：
             * 1. 慢 —— 半秒多，点完底栏还得等它演完；
             * 2. 危险 —— 过渡期间**旧页面仍在合成里、仍然接收触摸**（见下面的拦截）。
             *
             * ⚠️ 方向不能写死：写死成"一律从左进"的话，从设置切回配置
             * 会变成反方向滚动，看着很别扭。索引大的往右、小的往左才对。
             */
            enterTransition = {
                slideIntoContainer(
                    slideDirection(initialState, targetState, forPop = false),
                    animationSpec = tween(TRANSITION_DURATION_MS),
                )
            },
            exitTransition = {
                slideOutOfContainer(
                    slideDirection(initialState, targetState, forPop = false),
                    animationSpec = tween(TRANSITION_DURATION_MS),
                )
            },
            popEnterTransition = {
                slideIntoContainer(
                    slideDirection(initialState, targetState, forPop = true),
                    animationSpec = tween(TRANSITION_DURATION_MS),
                )
            },
            popExitTransition = {
                slideOutOfContainer(
                    slideDirection(initialState, targetState, forPop = true),
                    animationSpec = tween(TRANSITION_DURATION_MS),
                )
            },
        ) {
            composable(TopDestination.HOME.route) { entry ->
                // 回到主页时刷新能力状态，避免用户刚从系统设置页授权回来却看到旧结果
                LaunchedEffect(Unit) { viewModel.refreshCapabilities() }
                BlockInputWhenHidden(isCurrent = isCurrentRoute(entry, currentRoute)) {
                    HomeScreen(viewModel = viewModel)
                }
            }

            composable(TopDestination.CONFIG.route) { entry ->
                BlockInputWhenHidden(isCurrent = isCurrentRoute(entry, currentRoute)) {
                    ConfigListScreen(
                        viewModel = viewModel,
                        onEditConfig = { configId ->
                            navController.navigate(SubRoutes.configEditor(configId))
                        },
                    )
                }
            }

            composable(TopDestination.SETTINGS.route) { entry ->
                BlockInputWhenHidden(isCurrent = isCurrentRoute(entry, currentRoute)) {
                    SettingsScreen(
                        viewModel = viewModel,
                        onOpenDebug = { navController.navigate(SubRoutes.DEBUG) },
                        onOpenAbout = { navController.navigate(SubRoutes.ABOUT) },
                        onReopenSetup = onOpenSetup,
                    )
                }
            }

            /*
             * 二级页面
             */

            composable(
                route = "${SubRoutes.CONFIG_EDITOR}/{${SubRoutes.ARG_CONFIG_ID}}",
            ) { entry ->
                val configId = entry.arguments?.getString(SubRoutes.ARG_CONFIG_ID).orEmpty()
                BlockInputWhenHidden(isCurrent = isCurrentRoute(entry, currentRoute)) {
                    ConfigEditorScreen(
                        viewModel = viewModel,
                        configId = configId,
                        onBack = { navController.popBackStack() },
                        onOpenCustomEditor = { id ->
                            navController.navigate(SubRoutes.customEditor(id))
                        },
                    )
                }
            }

            /*
             * 自定义 Key 的图形化编辑器。
             *
             * 独立的二级页面（不显示底栏）：它有工具栏、画布、属性面板三块，
             * 塞进配置编辑页那个滚动列表里既挤，画布和页面滚动也会打架。
             */
            composable(
                route = "${SubRoutes.CUSTOM_EDITOR}/{${SubRoutes.ARG_CONFIG_ID}}",
            ) { entry ->
                val configId = entry.arguments?.getString(SubRoutes.ARG_CONFIG_ID).orEmpty()
                val config = viewModel.findConfig(configId)
                BlockInputWhenHidden(isCurrent = isCurrentRoute(entry, currentRoute)) {
                    if (config == null) {
                        // 配置被删掉了：直接退回，别让用户对着一个空页面
                        LaunchedEffect(Unit) { navController.popBackStack() }
                    } else {
                        /*
                         * 属性面板停在哪一边。
                         *
                         * ============================================================
                         * "自动"只在**首次**打开时生效
                         * ============================================================
                         * 偏好的初值是 [EditorPanelSide.AUTO]，界面把它解析成
                         * 具体的一边（平板→左、手机→下）；用户一点按钮就变成
                         * 他选的那个位置并写回偏好。
                         *
                         * 于是：第一次进来是平板就落在左边；用户之后手动切到下方，
                         * 下次打开仍然是下方 —— 不会突然又跑回左边（那看起来就像
                         * "设置没记住"）。
                         *
                         * `autoChosen` 只用来决定顶栏那个按钮要不要显示
                         * `-自动` 后缀：位置是自动定的、还是用户自己点的。
                         */
                        val context = LocalContext.current
                        val prefs = remember(context) { AppPrefs.get(context) }
                        var panelSide by remember { mutableStateOf(prefs.editorPanelSide) }
                        var autoChosen by remember { mutableStateOf(!prefs.editorPanelSideChosen) }
                        // 画布边框的两个开关（在设置页切换，这里只读）
                        var showWindowFrame by remember {
                            mutableStateOf(prefs.editorShowWindowFrame)
                        }
                        var showSelectedFrame by remember {
                            mutableStateOf(prefs.editorShowSelectedFrame)
                        }

                        /*
                         * 从设置页回来时重新读一次偏好。
                         *
                         * 这两个开关在**另一个页面**改，而这里是 `remember` 的
                         * 局部状态 —— 不刷新的话用户改完返回，画布还是旧的。
                         * key 用当前路由，于是每次回到这个页面都会重读。
                         */
                        LaunchedEffect(currentRoute) {
                            showWindowFrame = prefs.editorShowWindowFrame
                            showSelectedFrame = prefs.editorShowSelectedFrame
                        }

                        CustomLayoutEditorScreen(
                            config = config,
                            panelSide = panelSide,
                            autoChosen = autoChosen,
                            showWindowFrame = showWindowFrame,
                            showSelectedFrame = showSelectedFrame,
                            onShowWindowFrameChange = { show ->
                                /*
                                 * 写进应用级偏好而不是这份配置：
                                 * 画布边框是"看的方式"，与编辑哪份配置无关。
                                 * 写回 prefs 是为了下次进编辑器还能记住。
                                 */
                                showWindowFrame = show
                                prefs.editorShowWindowFrame = show
                            },
                            onShowSelectedFrameChange = { show ->
                                showSelectedFrame = show
                                prefs.editorShowSelectedFrame = show
                            },
                            onPanelSideChange = { picked ->
                                panelSide = picked
                                autoChosen = false
                                prefs.editorPanelSide = picked
                                prefs.editorPanelSideChosen = true
                            },
                            onSave = { components ->
                                viewModel.updateConfigById(configId) { current ->
                                    current.copy(
                                        custom = current.custom.copy(components = components),
                                    )
                                }
                                navController.popBackStack()
                            },
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
            }

            composable(SubRoutes.DEBUG) { entry ->
                BlockInputWhenHidden(isCurrent = isCurrentRoute(entry, currentRoute)) {
                    DebugScreen(
                        viewModel = viewModel,
                        onBack = { navController.popBackStack() },
                    )
                }
            }

            composable(SubRoutes.ABOUT) { entry ->
                BlockInputWhenHidden(isCurrent = isCurrentRoute(entry, currentRoute)) {
                    AboutScreen(
                        onBack = { navController.popBackStack() },
                    )
                }
            }
            }

            /*
             * 切标签动画期间的全局触摸拦截（在 NavHost 之上）。
             *
             * 与按页面拦截（[BlockInputWhenHidden]）的分工：
             * - 这里管**切标签**：两个页面都在滑动，一律不响应最不容易出错；
             * - 那里管**二级页进退**：退场的那个页面不该再接收点击。
             */
            if (tabTransitioning) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent().changes.forEach { it.consume() }
                                }
                            }
                        },
                )
            }
        }
    }
}

/**
 * 切页方向：往右切（索引变大）从右边进，往左切从左边进。
 *
 * 参数不是一级页面时（二级页的进退）用 [forPop] 的默认方向：
 * 进入二级页当"向前"，返回当"向后"。
 */
private fun slideDirection(
    initial: NavBackStackEntry?,
    target: NavBackStackEntry?,
    forPop: Boolean,
): AnimatedContentTransitionScope.SlideDirection {
    val from = TopDestination.of(initial?.destination?.route)?.ordinal
    val to = TopDestination.of(target?.destination?.route)?.ordinal
    if (from == null || to == null) {
        return if (forPop) {
            AnimatedContentTransitionScope.SlideDirection.Right
        } else {
            AnimatedContentTransitionScope.SlideDirection.Left
        }
    }
    return if (to >= from) {
        AnimatedContentTransitionScope.SlideDirection.Left
    } else {
        AnimatedContentTransitionScope.SlideDirection.Right
    }
}

/** 切页动画时长（毫秒）；短到"看得见但不碍事" */
private const val TRANSITION_DURATION_MS = 220

/**
 * 平板的最短边阈值（dp）：Android 官方对 `sw600dp` 的定义。
 *
 * 与 [com.something.sthkey.ui.feature.custom] 里那份是同一个值，
 * 但那份是 private。两处都只用于"是不是平板"这一个判断，
 * 重复一个数字比为此建一个公共常量文件更省事。
 */
private const val TABLET_MIN_WIDTH_DP = 600

/**
 * 这个页面是不是"当前页面"。
 *
 * 用 `destination.route`（路由**模式**，不是解析后的路径）与当前路由比对：
 * 带参数的页面（配置编辑页）两者都是 `config_editor/{configId}`，能对上。
 */
private fun isCurrentRoute(
    entry: NavBackStackEntry,
    currentRoute: String?,
): Boolean = entry.destination.route == currentRoute

/**
 * 过渡期间挡住**即将退场**页面的触摸。
 *
 * ============================================================
 * 为什么需要
 * ============================================================
 * 切页动画期间旧页面仍在合成里，而且**仍然接收触摸**。实际踩到的例子：
 * 从配置页切回主页后立刻点悬浮窗开关，那一击落在了还没退场的配置列表上，
 * 于是把配置切成了列表里对应位置的那一个 —— 用户完全没碰过卡片。
 *
 * 这里在非当前页面上盖一层透明层，把触摸全部吃掉：
 * - 动画期间点在新页面上 → 照常响应（新页面在上层，先拿到事件）；
 * - 点在新页面还没铺满的那部分（即旧页面）→ 被吃掉，**什么都不会发生**。
 *
 * 后一种情况下"没反应"是可接受的 —— 比"悄悄改了别的配置"好得多，
 * 而且只有短短 220ms。
 */
@Composable
private fun BlockInputWhenHidden(
    isCurrent: Boolean,
    content: @Composable () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        content()

        if (!isCurrent) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        }
                    },
            )
        }
    }
}

/**
 * 底部导航栏。
 *
 * 只有三个一级页面（主页 / 配置 / 设置）；调试与关于从设置页进入，
 * 这样底栏保持精简，日常只用得到的入口才占位置。
 */
@Composable
private fun SthKeyBottomBar(
    currentRoute: String?,
    onSelect: (TopDestination) -> Unit,
) {
    NavigationBar {
        TopDestination.entries.forEach { destination ->
            NavigationBarItem(
                selected = currentRoute == destination.route,
                onClick = { onSelect(destination) },
                icon = {
                    Icon(
                        imageVector = destination.icon(),
                        contentDescription = destination.label,
                    )
                },
                label = { Text(destination.label) },
            )
        }
    }
}

/** 一级页面的图标 */
private fun TopDestination.icon(): ImageVector = when (this) {
    TopDestination.HOME -> Icons.Default.Home
    TopDestination.CONFIG -> Icons.Default.Tune
    TopDestination.SETTINGS -> Icons.Default.Settings
}

/**
 * 切换一级页面。
 *
 * 用 popUpTo(起点) + launchSingleTop：反复点底栏不会堆积返回栈，
 * 也不会出现"点了主页再点配置，返回键要按两次"的怪现象。
 */
private fun NavHostController.switchTab(destination: TopDestination) {
    navigate(destination.route) {
        popUpTo(TopDestination.HOME.route) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

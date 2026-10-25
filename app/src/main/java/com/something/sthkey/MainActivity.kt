package com.something.sthkey

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import com.something.sthkey.capture.accessibility.AccessibilityGrant
import com.something.sthkey.core.log.AppLog
import com.something.sthkey.core.prefs.AppPrefs
import com.something.sthkey.core.prefs.CanvasColorPreset
import com.something.sthkey.data.incoming.IncomingFile
import com.something.sthkey.domain.font.bitmap.BitmapFontImporter
import com.something.sthkey.domain.ui.BottomBarStyle
import com.something.sthkey.ui.AppStage
import com.something.sthkey.ui.component.FloatingTab
import com.something.sthkey.ui.component.LocalBottomBarInset
import com.something.sthkey.ui.component.MaterialFloatingBottomBar
import com.something.sthkey.ui.component.PendingBitmapImportDialog
import com.something.sthkey.ui.feature.announcement.AnnouncementDialog
import com.something.sthkey.ui.feature.capture.CaptureModeScreen
import com.something.sthkey.ui.feature.config.ConfigEditorScreen
import com.something.sthkey.ui.feature.config.ConfigGroupDetailScreen
import com.something.sthkey.ui.feature.config.ConfigListScreen
import com.something.sthkey.ui.feature.custom.CustomLayoutEditorScreen
import com.something.sthkey.ui.feature.debug.DebugScreen
import com.something.sthkey.ui.feature.home.HomeScreen
import com.something.sthkey.ui.feature.incoming.IncomingFileDialog
import com.something.sthkey.ui.feature.settings.AboutScreen
import com.something.sthkey.ui.feature.settings.SettingsScreen
import com.something.sthkey.ui.feature.settings.ThemeScreen
import com.something.sthkey.ui.feature.setup.SetupScreen
import com.something.sthkey.ui.MainViewModel
import com.something.sthkey.ui.SubRoutes
import com.something.sthkey.ui.theme.SthKeyTheme
import com.something.sthkey.ui.TopDestination
import kotlinx.coroutines.launch
















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
    private var incomingRequest by mutableStateOf<IncomingFile.Request?>(null)

    /**
     * 待命名的图片字体。
     *
     * ⚠️ 由统一导入对话框「交出来」:它在关闭前把这个塞给我们，
     * 我们再在下面的组合里渲染命名窗口（不能两个 AlertDialog 同时开）。
     */
    private var pendingBitmapSource by mutableStateOf<BitmapFontImporter.Source?>(null)

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
            /*
             * ⚠️ 主题现在由**偏好驱动**（设置页「外观」里改）。
             *
             * ⚠️ 用 `viewModel { }` 在这里再取一次 ViewModel —— 与 `SthKeyApp`
             * 里那个是**同一个实例**（`ViewModelStoreOwner` 是 Activity），
             * 所以「外观」里改完，这里立刻重组、底栏与配色一起变。
             *
             * ⚠️ 不能让 `SthKeyTheme` 自己去读 `AppPrefs`:
             * 那样改完偏好不会触发重组，用户点完"深色"要杀进程才生效。
             */
            /*
             * ⚠️⚠️ **提供 NavigationEventDispatcherOwner**
             * ============================================================
             * 当初是为了修这个崩溃:
             *
             * > java.lang.IllegalStateException: No NavigationEventDispatcher was
             * > provided via LocalNavigationEventDispatcherOwner
             *
             * ⚠️ 触发点是 Miuix 的 `FloatingNavigationBar`（**已随 Miuix 删除**）。
             *
             * ⚠️ 但这里**保留**这个 provider，因为:
             * `activity-compose` 1.13.0 的 `BackHandler` 也走 `NavigationEventHandler`，
             * 而本应用用 `BackHandler` 实现"在配置/设置页按返回先回主页"。
             *
             * ⚠️ 顺带说明咱们的依赖现状:
             *
             * | 依赖 | 版本 | 说明 |
             * |---|---|---|
             * | `activity-compose` | 1.13.0 | 比原来的 1.8.0 新，`ComponentActivity` 已实现该接口 |
             * | `navigation-compose` | 2.7.7 | 偏老，其 `NavHost` 不提供这个 Local，所以这里手动补 |
             *
             * ⚠️ `rememberNavigationEventDispatcherOwner()` 会从 ViewTree 找宿主 Activity 的
             * dispatcher，找不到时自己建一个 —— 两种情况都不会抛异常。
             */
            val navigationOwner = rememberNavigationEventDispatcherOwner()

            val appViewModel: MainViewModel = viewModel()
            CompositionLocalProvider(
                LocalNavigationEventDispatcherOwner provides navigationOwner,
            ) {
            SthKeyTheme(
                colorMode = appViewModel.appearanceColorMode,
                seedColor = appViewModel.seedColor,
                palette = appViewModel.appearancePalette,
            ) {
                /*
                 * ⚠️⚠️ 根背景用 **surfaceContainer**（灰），不是 `surface`（白）
                 * ============================================================
                 * SukiSU 的 Scaffold 容器色就是 `surfaceContainer`:
                 * 页面是**灰底**、卡片是**白色** —— 这样才有「卡片浮在页面上」的层次。
                 *
                 * ⚠️ 之前我们是白底 + 灰行，方向正好反了:
                 * 整页偏亮（用户说的「我们软件色调更亮一点」），
                 * 而悬浮底栏那颗灰胶囊压在白底上就像一块砖（用户说的「实色背景」）。
                 */
                val rootBackground = MaterialTheme.colorScheme.surfaceContainer
                /*
                 * ⚠️ 状态栏/导航栏的**图标明暗**要跟着主题走。
                 *
                 * ⚠️ 不设的话:深色主题下状态栏图标还是黑的 —— 黑底黑字，看不见。
                 */
                val window = this@MainActivity.window
                /*
                 * ⚠️ 必须**先**在组合上下文里取一次系统深浅色，不能在 `when` 里就地调
                 * `isSystemInDarkTheme()` —— 那是 `@Composable`，放进普通分支表达式会编译不过。
                 */
                val systemDarkNow = androidx.compose.foundation.isSystemInDarkTheme()
                val darkThemeNow = when (appViewModel.appearanceColorMode) {
                    com.something.sthkey.domain.ui.AppearanceColorMode.DARK,
                    com.something.sthkey.domain.ui.AppearanceColorMode.MONET_DARK,
                    -> true

                    com.something.sthkey.domain.ui.AppearanceColorMode.LIGHT,
                    com.something.sthkey.domain.ui.AppearanceColorMode.MONET_LIGHT,
                    -> false

                    else -> systemDarkNow
                }
                LaunchedEffect(window, darkThemeNow) {
                    androidx.core.view.WindowInsetsControllerCompat(
                        window, window.decorView,
                    ).apply {
                        isAppearanceLightStatusBars = !darkThemeNow
                        isAppearanceLightNavigationBars = !darkThemeNow
                    }
                }
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = rootBackground,
                ) {
                    SthKeyApp(
                        incomingRequest = incomingRequest,
                        onIncomingHandled = { incomingRequest = null },
                        pendingBitmapSource = pendingBitmapSource,
                        /* ⚠️ 命名窗口由这里弹:导入对话框已经关掉了 */
                        onBitmapNamingRequested = { source -> pendingBitmapSource = source },
                        /* 有资源进了库 → 设置页重读列表 */
                        onBitmapNamingDone = { name ->
                            pendingBitmapSource = null
                            /*
                             * ⚠️ 命名成功后也要自增库版本号:图片字体是在命名窗口里
                             * 才真正落库的（外部导入时 `runImport` 只解读出图集）。
                             */
                            if (name.isNotBlank()) {
                                Toast.makeText(
                                    this@MainActivity,
                                    "已加入字体库：「$name」",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                    )
                }
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
        val request = IncomingFile.extract(intent) ?: return
        AppLog.i(TAG, "收到外部文件：action=${intent?.action}")
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
    incomingRequest: IncomingFile.Request? = null,
    onIncomingHandled: () -> Unit = {},
    /* ⚠️ 图片字体待命名（统一导入对话框交出来的） */
    pendingBitmapSource: BitmapFontImporter.Source? = null,
    /** 有图片字体需要确认名字（Activity 关掉导入对话框后弹命名窗口） */
    onBitmapNamingRequested: (BitmapFontImporter.Source) -> Unit = {},
    onBitmapNamingDone: (String) -> Unit = {},
) {
    // rememberSaveable：进程被系统回收重建时仍停留在用户当时的状态
    /*
     * ============================================================
     * ⚠️⚠️ **初始阶段是同步算出来的**（所以启动**没有任何等待页面**）
     * ============================================================
     * 用户的原话:
     *
     * > 而且我并不想让用户每次进入都放一个这么丑的检查权限的页面，
     * > **你冷检查就行了**
     *
     * ⚠️ 所以不再有"校验中"那一屏:
     *
     * | 用户状态 | 首帧 |
     * |---|---|
     * | 以前配好过 | ✅ **直接主界面**（绝大多数情况） |
     * | 第一次装 / 以前点过跳过 | 引导页 |
     *
     * ⚠️ 而"权限后来没了"这种情况，由下面那段**静默校验**兜底:
     * 它跑完发现有问题，才把用户带到引导页（那时人已经在主界面了，
     * 但只过了不到一秒，看着就是"进来就提示你一下"）。
     */
    var stage by rememberSaveable {
        mutableStateOf(
            if (viewModel.isSetupCompleted) AppStage.MAIN.name else AppStage.SETUP.name,
        )
    }
    var showAnnouncement by rememberSaveable { mutableStateOf(false) }

    /**
     * 静默校验是否**已经生效过**。
     *
     * ⚠️ 只生效**一次**:跑完就置 `true`。
     * 不设这个的话，用户后来从设置里主动进引导页，
     * 会被那条 effect 反复弹回主界面。
     */
    var startupVerificationApplied by rememberSaveable { mutableStateOf(false) }

    /*
     * ============================================================
     * shell 打开无障碍后的提示
     * ============================================================
     * ⚠️ 收集的是 `AccessibilityGrant.notice`（唯一的成功点发出的）而不是
     * "开悬浮窗时那一步":无障碍最常是在**引导页**就被 shell 开好的，
     * 等用户来开悬浮窗时服务早就连上了，那些调用点根本不会被执行。
     *
     * ⚠️ 用 Toast 而不是弹窗:这是"顺带告诉你一声"的提示，
     * 弹窗会打断引导流程（用户正要点下一步）。
     */
    val notifyContext = LocalContext.current

    LaunchedEffect(Unit) {
        AccessibilityGrant.notice.collect { message ->
            Toast.makeText(notifyContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        /*
         * 配置列表已经就绪：版本号 +1，主页那个"每配置一个开关"的列表
         * 才会在首帧就显示出全部配置（它按版本号决定何时重取列表）。
         */
        viewModel.refreshConfigs()

        /*
         * ⚠️⚠️ 这里**不再调** `viewModel.refreshCapabilities()`
         * ============================================================
         * 用户报的"检查权限检查太久了" —— 原因之一就是**探测跑了两遍**:
         *
         * | 谁 | 什么时候 |
         * |---|---|
         * | `MainViewModel.init` | 创建 ViewModel 时（就是启动那一刻） |
         * | 这里 | 组合完第一帧时 |
         *
         * ⚠️ 两遍串起来等，`RootProbe` 的 15 秒超时就是 30 秒。
         * `init` 里那次已经覆盖了这件事，所以这里删掉。
         */
    }

    /*
     * ============================================================
     * ⚠️⚠️ **静默校验**:查出问题才把用户带回引导页
     * ============================================================
     * 用户的原话:
     *
     * > 我主要是为了**防止无障碍服务被系统自己取消然后用户还不知道**，
     * > 所以即便是点了进入应用，**下次启动也仍然需要校验**，
     * > 没问题**直接冷跳过**，有问题**再留在权限授权页**
     *
     * ⚠️ "冷跳过"= **不显示任何等待界面**，直接按上次的状态进；
     * 校验在后台跑，出结果后**只在有问题时**才干预。
     *
     * ⚠️ 这条 effect 只做一件事:把"以前配好过、但这次查出问题"的人
     * 送到引导页并说明原因。正常情况它**什么都不做**（用户无感）。
     */
    LaunchedEffect(viewModel.startupCheckDone, viewModel.startupCheckIssue) {
        if (startupVerificationApplied) return@LaunchedEffect
        if (!viewModel.startupCheckDone) return@LaunchedEffect
        startupVerificationApplied = true

        if (viewModel.isSetupCompleted && viewModel.startupCheckIssue != null) {
            AppLog.w("App", "启动校验未通过，带到引导页：${viewModel.startupCheckIssue}")
            stage = AppStage.SETUP.name
        }
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
            /*
             * ⚠️ 这里用 `else` 而不是 `AppStage.MAIN.name ->`:
             * `rememberSaveable` 可能从旧版本的 `savedInstanceState` 里
             * 回放出一个**已经删掉的阶段名**（比如曾经的 `CHECKING`）。
             * 用 `else` 接住，回放到未知值时落到主界面，而不是白屏。
             */
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
    IncomingFileDialog(
        request = incomingRequest,
        onFinished = onIncomingHandled,
        /*
         * ⚠️ 图片字体要确认名字:先把统一导入对话框关掉再弹命名窗口，
         * 两个 AlertDialog 同时开着时部分 ROM 会把先弹的顶掉。
         */
        onNeedBitmapNaming = { source ->
            /*
             * ⚠️ 交给 Activity:命名窗口要在**这个对话框关掉之后**才弹，
             * 所以这里只能把 Source 抛上去，由 Activity 关对话框、再渲染命名窗口。
             */
            onBitmapNamingRequested(source)
            onIncomingHandled()
        },
    )

    /*
     * 图片字体的命名窗口。
     *
     * ⚠️ 它必须**单独**渲染:统一导入对话框在交出来之前已经关掉了
     * （见 `onNeedBitmapNaming` 的说明）。
     */
    PendingBitmapImportDialog(
        source = pendingBitmapSource,
        onDismiss = { onBitmapNamingDone("") },
        onRegistered = { _, name -> onBitmapNamingDone(name) },
        onFailed = { onBitmapNamingDone("") },
    )
}

/**
 * 主界面骨架：底部导航 + 一级页面 pager + 二级页面导航。
 *
 * 底栏只在**一级页面**显示；进入调试 / 关于 / 配置编辑这类二级页面时隐藏，
 * 让二级页面回到全屏专注的状态（也更符合 Material 的层级习惯）。
 *
 * ============================================================
 * ⚠️⚠️ 一级页面从 `NavHost` 换成了 `HorizontalPager`（用户拍板 A 方案）
 * ============================================================
 * 用户的原话:
 *
 * > 下一步把底栏优化一下，让主页切到设置栏的过程中，
 * > **配置页也渲染在滚动过程中**
 *
 * > 我赞同你说的"A（真正的左右滑动 + 底栏指示条，这才是现在 Android 的
 * > 标准做法）"，按你节奏来做就行
 *
 * ⚠️ 以前：三个页面是 `NavHost` 里的三条路由，切换靠 `AnimatedContent`
 * 的 `slideIntoContainer` —— **中间那一页压根没被组合**，所以从主页切到设置时
 * 配置页是"不存在"的，屏幕上只有两页在叠化滑动。
 *
 * ⚠️ 现在：三页常驻在 pager 里，切页是**真的横向滚动**，
 * 主页 → 设置的途中配置页会从屏幕上真实地滑过去。
 *
 * ⚠️ 顺带去掉了一个老补丁：以前切标签时要盖一层透明层拦触摸
 * （`tabTransitioning`），因为退场的旧页面**还在合成里、还在收事件**，
 * 于是"从配置页切回主页后立刻点悬浮窗开关"会点到还没退场的配置列表上。
 * pager 里每一页都在**自己正确的位置**上，手指落点所见即所得，
 * 所以那层拦截**不再需要**（留着反而会误伤滑动）。
 */
@Composable
private fun MainScaffold(
    viewModel: MainViewModel,
    onOpenSetup: () -> Unit,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    /* 一级页面才显示底栏；二级页（含带参数的配置编辑页）一律隐藏 */
    val showBottomBar = currentRoute == SubRoutes.MAIN

    /*
     * ⚠️ `PagerState` 提到 `MainScaffold` 这一层，而不是放进 pager 的
     * `composable(MAIN)` 里 —— 因为**底栏要读它**来决定哪一项高亮。
     *
     * ⚠️ 放在这一层还有个好处:进二级页面再返回时，它不会因为
     * 那个 composable 被销毁而丢页号（这一层一直活着）。
     */
    val pagerState = rememberPagerState(
        initialPage = TopDestination.START.ordinal,
        pageCount = { TopDestination.pageCount },
    )
    val scope = rememberCoroutineScope()

    /*
     * 回到主页时刷新一次能力状态。
     *
     * ⚠️ 为什么挂在**这里**而不是主页那个 composable 的 `LaunchedEffect(Unit)`:
     * pager 里第 0 页在滑到第 1 页时**仍然被组合着**（相邻页要预渲染），
     * 所以"滑走再滑回主页"不会重新触发 `LaunchedEffect(Unit)` ——
     * 那样从系统设置页授权回来就看不到新状态了。
     *
     * ⚠️ 用 `(currentRoute, pagerState.currentPage)` 做 key:
     * 既覆盖"滑回主页"，也覆盖"从二级页面返回主页"。
     */
    LaunchedEffect(currentRoute, pagerState.currentPage) {
        if (currentRoute == SubRoutes.MAIN &&
            pagerState.currentPage == TopDestination.HOME.ordinal
        ) {
            viewModel.refreshCapabilities()
        }
    }

    /*
     * ============================================================
     * ⚠️⚠️ 返回键:在配置 / 设置页时**先回主页**，而不是直接退出应用
     * ============================================================
     * ⚠️ 这是换成 pager 之后**必须补的一处行为**，否则是个回归:
     *
     * | 以前（三条路由） | 换成 pager 之后（不补的话） |
     * |---|---|
     * | `switchTab` 用 `popUpTo(HOME)`，栈是 `[HOME, CONFIG]` | 栈里只剩 `[MAIN]` |
     * | 在配置页按返回 → **回主页** | 在配置页按返回 → ⚠️ **直接退出应用** |
     *
     * ⚠️ 一条路由的时候，返回键不拦就只能退出 —— 那是"我按了一下怎么退出了"
     * 的惊吓。所以这里把它变成"回主页"，与原来一致。
     *
     * ⚠️ `enabled` 必须判 `currentRoute == MAIN`:停在二级页面时
     * 返回键要交给 NavHost 自己 pop，不能被这里抢走。
     */
    BackHandler(
        enabled = currentRoute == SubRoutes.MAIN &&
            pagerState.currentPage != TopDestination.HOME.ordinal,
    ) {
        scope.launch {
            pagerState.animateScrollToPage(
                page = TopDestination.HOME.ordinal,
                animationSpec = tween(PAGER_SCROLL_DURATION_MS),
            )
        }
    }

    /*
     * ============================================================
     * ⚠️⚠️ 选中项的来源
     * ============================================================
     * 用户的原话:
     *
     * > sukisu 底栏点击的时候就已经开始放动画了，你写的这个是切换完成才有动画，手感很差
     * > 软件左右滑动切换页面时，底栏的动画是切换完成才开始走的
     *
     * ⚠️ 两种情况**都要立刻有动画**，但来源不同:
     *
     * | 操作 | 谁在变 | 什么时候该跟 |
     * |---|---|---|
     * | 点底栏 | 我们主动滚过去 | 点下去**立刻**改（不能等滚动结束） |
     * | 左右滑动翻页 | `currentPage` 越过半程就变 | 拖动过程中就跟上，**不等 settle** |
     *
     * ⚠️ 那为什么不能无脑跟 `currentPage`:
     * `animateScrollToPage(2)` 从第 0 页出发时会**经过第 1 页**，
     * 无脑跟的话指示块会先跳到 1 再跳到 2（来回抽搐，这个坑踩过）。
     *
     * ⚠️ 所以用 [programmaticTarget] 区分"这次滚动是我们自己发起的":
     * 非 null 时**忽略**拖动跟随，只认点下去时定下的那个目标。
     */
    var selectedPage by remember { mutableIntStateOf(TopDestination.START.ordinal) }

    /** 正在进行的**编程滚动**的目标页；null = 当前是用户手指在拖 */
    var programmaticTarget by remember { mutableStateOf<Int?>(null) }

    /*
     * 手指拖动时跟随 `currentPage`。
     *
     * ⚠️ 读 `isScrollInProgress` 是为了避免"页面根本没在动时也重设一遍"
     * ——那会让 `selectedPage` 在没有滚动的情况下被写成 currentPage，
     * 把编程滚动刚设好的目标冲掉。
     */
    LaunchedEffect(pagerState.currentPage, pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress) return@LaunchedEffect
        if (programmaticTarget != null) return@LaunchedEffect
        selectedPage = pagerState.currentPage
    }

    /* 停稳后对齐一次（兜底：拖动被取消、页面数量变化、编程滚动结束都靠它收尾） */
    LaunchedEffect(pagerState.settledPage) {
        programmaticTarget = null
        selectedPage = pagerState.settledPage
    }


    /*
     * 悬浮胶囊给内容让出的高度。
     *
     * ⚠️ 必须把**导航栏内边距**算进去:胶囊自己会避开手势条，
     * 内容只按 64 + 12*2 留空间的话，最后一项会正好藏到手势条下面。
     */

    /*
     * 点底栏 → 立刻改选中项（动画随即开始）+ 滚动过去。
     *
     * ⚠️ 顺序很关键:**先改选中项、再滚动**。
     * 反过来的话要等滚动结束才看到高亮变化（用户报的"切完才有动画"）。
     */
    /*
     * ============================================================
     * ⚠️ 悬浮底栏的**尺寸与开关**
     * ============================================================
     * ⚠️ 它不进 `Scaffold` 的 `bottomBar` 槽位（那样会预留一条空白 =「实色背景」），
     * 而是叠在内容之上；高度通过 `LocalBottomBarInset` 传给需要避让的东西。
     */
    val floatingBar = viewModel.bottomBarStyle == BottomBarStyle.FLOATING

    /*
     * 胶囊自己会避开手势条（`applyWindowInsets = true`），
     * 所以给内容让出的高度必须把**导航栏内边距**也算进去，
     * 否则最后一项会正好藏到手势条下面。
     */
    val navigationBarInset = WindowInsets.navigationBars
        .asPaddingValues()
        .calculateBottomPadding()
    val floatingBarReserved = 64.dp + 12.dp + 12.dp + navigationBarInset

    val animateTo: (TopDestination) -> Unit = { destination ->
        /*
         * ⚠️ 先改选中项（动画立刻开始），再标记"这次滚动是程序发起的"，最后才滚。
         * 顺序反了就又回到"切完才有动画"那个手感。
         */
        selectedPage = destination.ordinal
        programmaticTarget = destination.ordinal
        AppLog.i(
            "App",
            "底栏点了「${destination.label}」→ 滚到第 ${destination.ordinal} 页" +
                "（当前 ${pagerState.currentPage}）",
        )
        scope.launch {
            pagerState.animateScrollToPage(
                page = destination.ordinal,
                animationSpec = tween(PAGER_SCROLL_DURATION_MS),
            )
        }
    }

    Scaffold(
        /*
         * ⚠️⚠️ 容器色必须与**页面**一致，否则状态栏/导航栏会和页面对不上
         * ============================================================
         * 用户报的:
         *
         * > 主色选择除了跟随壁纸以外的任意颜色时，系统顶部状态栏和导航栏
         * > 会与背景色不一样
         *
         * ⚠️ 原因:这个外层 `Scaffold` 原来**没指定** `containerColor`，
         * 默认取 `colorScheme.background`；而各页面自己画的是 `surfaceContainer`。
         *
         * | 配色来源 | background | surfaceContainer | 看得出来吗 |
         * |---|---|---|---|
         * | 手写的固定配色 | #EDEDF6 | #EDEDF6 | 一样，看不出来 |
         * | 取色（种子） | #FDF7FF | #F2ECF5 | ⚠️ 差一档，状态栏那一圈就露馅 |
         *
         * ⚠️ 状态栏/导航栏那一圈（系统栏内边距所在的区域）归**外层** Scaffold 画，
         * 所以两者必须同色 —— 页面换色时这里也要跟着换。
         */
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        bottomBar = {
            /*
             * ⚠️⚠️ 两种样式**都**放在 `bottomBar` 槽里，不再自己叠在内容上
             * ============================================================
             * 用户的原话:
             *
             * > 配置页的新建配置按钮，启用悬浮底栏时，新建按钮会跑到底栏后面
             *
             * ⚠️ 原因:我上一版把悬浮底栏挪出槽位、改成叠在内容之上，
             * 于是 `Scaffold` **不知道底部有东西** —— 内容内边距和 FAB 位置都不让开，
             * 底部按钮就被胶囊压住了。
             *
             * ⚠️ SukiSU 的做法就是放进 `bottomBar` 槽:
             * 这样 `innerPadding` 与 FAB 都会自动避开它。
             *
             * ⚠️ 那"看不出悬浮"怎么解决？——不是靠叠放，是靠**底色一致**:
             * 页面底色和胶囊所在的槽位都是 `surfaceContainer`，
             * 胶囊（`surfaceContainerHighest`）在它上面只深一档，自然像浮着。
             * 之前那片"实色背景"是**白底 + 灰胶囊**的对比造成的。
             */
            /*
             * ⚠️ **只有「标准」样式**才占底栏槽位。
             *
             * ⚠️ 悬浮样式放槽位里会给它**预留一条空白**，
             * 看起来就像"底栏被安在一块实色背景上"（用户三番两次指出的坑）。
             * 悬浮样式改为叠在内容之上，见下面 `Box` 里的那一段。
             */
            if (showBottomBar && !floatingBar) {
                SthKeyBottomBar(
                    currentPage = selectedPage,
                    style = viewModel.bottomBarStyle,
                    onSelect = { destination -> animateTo(destination) },
                )
            }
        },
    ) { innerPadding ->
        /*
         * ⚠️⚠️ 悬浮底栏的真实高度传给页面（`LocalBottomBarInset`）
         * ============================================================
         * 悬浮样式下底栏**不占槽位**（否则会留一条空白），
         * 于是"需要避让"的东西自己加内边距:
         * 列表的 `contentPadding.bottom`、以及悬浮按钮的下边距。
         */
        CompositionLocalProvider(
            LocalBottomBarInset provides
                if (floatingBar && showBottomBar) floatingBarReserved else 0.dp,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                NavHost(
            navController = navController,
            startDestination = SubRoutes.MAIN,
            modifier = Modifier.padding(innerPadding),
            /*
             * 二级页的进退动画。
             *
         * ⚠️ 方向**不再按标签索引算了** —— 一级页面已经不是路由，
         * `TopDestination.of(route)` 那种判断自然消失。
         * 二级页一律"从右侧进、向右侧退"，与 pager 的左右滑动也不冲突
         * （pager 在三页之间，二级页在它之上）。
         */
            enterTransition = {
                slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(TRANSITION_DURATION_MS),
                )
            },
            exitTransition = {
                slideOutOfContainer(
                    AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(TRANSITION_DURATION_MS),
                )
            },
            popEnterTransition = {
                slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(TRANSITION_DURATION_MS),
                )
            },
            popExitTransition = {
                slideOutOfContainer(
                    AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(TRANSITION_DURATION_MS),
                )
            },
        ) {
            /*
             * ⚠️ **一级页面**（三页 pager）。
             *
             * ⚠️ 外面那层 `BlockInputWhenHidden` 保留:进二级页时这一整页会退场，
             * 而它退场期间仍可能收到触摸（"从二级页返回后立刻点底栏"这类）。
             */
            composable(SubRoutes.MAIN) { entry ->
                BlockInputWhenHidden(isCurrent = isCurrentRoute(entry, currentRoute)) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        /*
                         * ⚠️ 停在二级页时 pager 不在前台，手势不该被它吃掉；
                         * 不过那时整个 MAIN 页都被 BlockInputWhenHidden 盖住了，
                         * 所以这里不用再单独判断。
                         */
                    ) { page ->
                        when (TopDestination.entries[page]) {
                            TopDestination.HOME -> HomeScreen(
                                viewModel = viewModel,
                                /*
                                 * ⚠️ 「工作状态」大卡片点进来 —— 采集通道设置页。
                                 * 它原来是调试页里的「采集模式」，用户要求搬到卡片里
                                 * （见 `WorkStatusCard` 的说明）。
                                 */
                                onOpenCaptureMode = {
                                    navController.navigate(SubRoutes.CAPTURE_MODE)
                                },
                            )

                            TopDestination.CONFIG -> ConfigListScreen(
                                viewModel = viewModel,
                                onEditConfig = { configId ->
                                    navController.navigate(SubRoutes.configEditor(configId))
                                },
                                onEditGroup = { groupId ->
                                    navController.navigate(SubRoutes.groupDetail(groupId))
                                },
                            )

                            TopDestination.SETTINGS -> SettingsScreen(
                                viewModel = viewModel,
                                onOpenTheme = { navController.navigate(SubRoutes.THEME) },
                                onOpenDebug = { navController.navigate(SubRoutes.DEBUG) },
                                onOpenAbout = { navController.navigate(SubRoutes.ABOUT) },
                                onReopenSetup = onOpenSetup,
                            )
                        }
                    }
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
                        /*
                         * 画布的两个边框开关 + 画布底色。
                         *
                         * ⚠️ 它们都在**编辑器页的「更多」**里改（不在设置页 ——
                         * 这句注释以前写的是"在设置页切换"，已经过时）。
                         * 这里持有状态是因为编辑器页要能立刻看到效果，
                         * 同时写回 `AppPrefs` 让它**下次进来还记得**。
                         */
                        var showWindowFrame by remember {
                            mutableStateOf(prefs.editorShowWindowFrame)
                        }
                        var showSelectedFrame by remember {
                            mutableStateOf(prefs.editorShowSelectedFrame)
                        }
                        var canvasColor by remember {
                            mutableStateOf(prefs.editorCanvasColor)
                        }

                        /*
                         * 回到这个页面时重新读一次偏好。
                         *
                         * 它们是 `remember` 的局部状态，而可能在别处被改
                         * （例如多窗口/进程重建）—— 不重读的话画布还是旧的。
                         * key 用当前路由，于是每次回到这个页面都会重读。
                         */
                        LaunchedEffect(currentRoute) {
                            showWindowFrame = prefs.editorShowWindowFrame
                            showSelectedFrame = prefs.editorShowSelectedFrame
                            canvasColor = prefs.editorCanvasColor
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
                            canvasColor = canvasColor,
                            onCanvasColorChange = { argb ->
                                /*
                                 * ⚠️ 与上面两个开关**同一个存法**:
                                 * 写进 `AppPrefs`（应用级偏好），不是这份配置 ——
                                 * 所以**导出配置时不会带上它**，也不会影响
                                 * 悬浮窗与配置预览（用户明确要求"只是本地选项"）。
                                 */
                                canvasColor = argb
                                prefs.editorCanvasColor = argb
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

            /*
             * ⚠️ 「采集通道」页 —— 从主页「工作状态」大卡片点进来。
             *
             * ⚠️ 它原来在调试页里（「采集模式」那一块），用户要求搬到卡片里:
             * 「切换采集通道」是日常操作，藏在调试页里会让普通用户以为它很危险。
             */
            composable(SubRoutes.CAPTURE_MODE) { entry ->
                BlockInputWhenHidden(isCurrent = isCurrentRoute(entry, currentRoute)) {
                    CaptureModeScreen(
                        viewModel = viewModel,
                        onBack = { navController.popBackStack() },
                    )
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

            /*
             * 主题设置（从「设置 → 外观 → 主题设置」点进来）。
             *
             * ⚠️ 也套 `BlockInputWhenHidden`:退场动画期间这一页仍在树上，
             * 不盖住的话"返回后立刻点底栏"的点击会被它吃掉。
             */
            composable(SubRoutes.THEME) { entry ->
                BlockInputWhenHidden(isCurrent = isCurrentRoute(entry, currentRoute)) {
                    ThemeScreen(
                        viewModel = viewModel,
                        onBack = { navController.popBackStack() },
                    )
                }
            }

            /*
             * 配置组合详情页（从配置页的组合卡片点进来）。
             */
            composable(
                route = "${SubRoutes.GROUP_DETAIL}/{${SubRoutes.ARG_GROUP_ID}}",
            ) { entry ->
                val groupId = entry.arguments?.getString(SubRoutes.ARG_GROUP_ID).orEmpty()
                BlockInputWhenHidden(isCurrent = isCurrentRoute(entry, currentRoute)) {
                    ConfigGroupDetailScreen(
                        viewModel = viewModel,
                        groupId = groupId,
                        onBack = { navController.popBackStack() },
                        onEditConfig = { configId ->
                            navController.navigate(SubRoutes.configEditor(configId))
                        },
                    )
                }
            }
        }

            /*
             * 悬浮底栏叠在内容之上（不占布局空间）—— 这才是"悬浮"该有的样子。
             *
             * ⚠️ 它自己会避开系统栏（`applyWindowInsets = true`），
             * 这里只负责对齐到底部。
             */
            if (floatingBar && showBottomBar) {
                SthKeyBottomBar(
                    currentPage = selectedPage,
                    style = viewModel.bottomBarStyle,
                    onSelect = { destination -> animateTo(destination) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
            }
        }
    }
}

/** 二级页进退动画时长（毫秒）；短到"看得见但不碍事" */
private const val TRANSITION_DURATION_MS = 220

/**
 * 点底栏时**滚动**到目标页的时长（毫秒）。
 *
 * ============================================================
 * ⚠️ 为什么要有这个常量，以及为什么不瞬间跳
 * ============================================================
 * 用户的原话:
 *
 * > 让主页切到设置栏的过程中，**配置页也渲染在滚动过程中**
 *
 * ⚠️ 所以点底栏不能 `scrollToPage`（瞬间跳）—— 那样中间那一页
 * 一帧都不会出现，等于白改。
 *
 * ⚠️ 也不能用默认的弹簧动画:跨两页时弹簧会先冲过头再回弹一次，
 * 看起来像"抖了一下"。匀速 `tween` 滑过最稳。
 *
 * ⚠️ 320ms 是折中:再短看不清中间的配置页，再长点个 tab 要等。
 */
private const val PAGER_SCROLL_DURATION_MS = 320

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
 * 底部导航栏（**Material 3**，两种样式）。
 *
 * ============================================================
 * ⚠️ 两种样式都是 Material3 实现的，没有第三方依赖
 * ============================================================
 * | 样式 | 实现 |
 * |---|---|
 * | [BottomBarStyle.STANDARD] | Material3 `NavigationBar` + `NavigationBarItem` |
 * | [BottomBarStyle.FLOATING] | [MaterialFloatingBottomBar]（照 SukiSU 的悬浮胶囊） |
 *
 * ⚠️ 样式从偏好读（`设置 → 外观 → 主题设置 → 底栏样式`），改完立刻生效。
 *
 * ⚠️ 曾经还有一套基于 Miuix 的实现，已随 Miuix 一起删除。
 *
 * @param currentPage 当前停在第几页（= `pagerState.settledPage`）
 * @param style 底栏样式
 * @param onSelect 点了某一项（去做动画滚动）
 */
@Composable
private fun SthKeyBottomBar(
    currentPage: Int,
    style: BottomBarStyle,
    onSelect: (TopDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (style) {
        BottomBarStyle.STANDARD -> NavigationBar(modifier = modifier) {
            TopDestination.entries.forEach { destination ->
                NavigationBarItem(
                    selected = currentPage == destination.ordinal,
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

        BottomBarStyle.FLOATING -> MaterialFloatingBottomBar(
            modifier = modifier,
            tabs = TopDestination.entries.map { destination ->
                FloatingTab(
                    label = destination.label,
                    selectedIcon = destination.selectedIcon(),
                    unselectedIcon = destination.unselectedIcon(),
                )
            },
            selectedIndex = currentPage,
            onSelect = { index ->
                TopDestination.entries.getOrNull(index)?.let(onSelect)
            },
        )
    }
}

/** 一级页面的图标（标准底栏用，选中/未选中同一个） */
private fun TopDestination.icon(): ImageVector = when (this) {
    TopDestination.HOME -> Icons.Default.Home
    TopDestination.CONFIG -> Icons.Default.Tune
    TopDestination.SETTINGS -> Icons.Default.Settings
}

/**
 * 悬浮底栏**选中**时的图标（实心）。
 *
 * ⚠️ 为什么悬浮底栏要两套图标:未选中时只有图标没有文字，
 * 靠"实心 / 描边"来区分选中态比靠颜色更清楚（小图标上颜色差异很弱）。
 * 标准底栏不需要 —— 它本来就有 `NavigationBarItem` 的选中指示器。
 */
private fun TopDestination.selectedIcon(): ImageVector = when (this) {
    TopDestination.HOME -> Icons.Filled.Home
    TopDestination.CONFIG -> Icons.Filled.Tune
    TopDestination.SETTINGS -> Icons.Filled.Settings
}

/** 悬浮底栏**未选中**时的图标（描边） */
private fun TopDestination.unselectedIcon(): ImageVector = when (this) {
    TopDestination.HOME -> Icons.Outlined.Home
    TopDestination.CONFIG -> Icons.Outlined.Tune
    TopDestination.SETTINGS -> Icons.Outlined.Settings
}


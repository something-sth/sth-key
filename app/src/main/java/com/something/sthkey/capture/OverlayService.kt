package com.something.sthkey.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.something.sthkey.MainActivity
import com.something.sthkey.R
import com.something.sthkey.core.log.AppLog
import com.something.sthkey.core.prefs.AppPrefs
import com.something.sthkey.data.config.ConfigStore
import com.something.sthkey.domain.config.KeyStrokesConfig
import com.something.sthkey.domain.live2d.Live2DModels
import com.something.sthkey.domain.overlay.OverlayLayout
import com.something.sthkey.domain.overlay.OverlayLayouts
import com.something.sthkey.domain.style.StyleId
import com.something.sthkey.ui.overlay.OVERLAY_MIN_SIZE_PX
import com.something.sthkey.ui.overlay.OVERLAY_PADDING_PX
import com.something.sthkey.ui.overlay.OverlayContent
import com.something.sthkey.ui.overlay.dpToPx
import com.something.sthkey.ui.overlay.live2d.Live2DOverlayView
import com.something.sthkey.ui.overlay.overlayWindowHeightPx
import com.something.sthkey.ui.overlay.overlayWindowWidthPx
import com.something.sthkey.ui.overlay.toWindowPx
import com.something.sthkey.ui.theme.SthKeyTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 悬浮窗服务。
 *
 * ============================================================
 * 职责
 * ============================================================
 * 1. 把**每一份开着的配置**各自做成一个覆盖层窗口（见 [windows]）；
 * 2. 每个窗口独立拖动、独立记住位置（基础坐标 + 偏移）；
 * 3. 配置 / 屏幕 / 开关状态变化时，把全部窗口同步到最新状态。
 *
 * 窗口集合的唯一依据是 [OverlayLayouts.enabledIds]（本机持久化）。
 * 主页的开关只做两件事：改这份集合、再把服务叫起来 ——
 * **窗口怎么摆、有几个，全部由这个服务说了算**，
 * 因此服务被系统单独重启（START_STICKY）时也能自行恢复到正确状态。
 *
 * ============================================================
 * 为什么是前台服务
 * ============================================================
 * 悬浮窗需要长时间存活，普通后台服务在 Android 12+ 很容易被冻结/回收，
 * 一旦被回收窗口就消失了，用户会以为是 bug。前台服务能显著降低被杀概率，
 * 代价是需要一条常驻通知 —— 通知本身就是"悬浮窗正在运行"的提示，不算白占。
 *
 * ============================================================
 * 多窗口下刻意保留的两个"单份"设计
 * ============================================================
 * 1. **窗口只在有新配置时创建，已存在的就地更新**（[syncWindows]）：
 *    Live2D 的 WebView 重建要几百毫秒，动一次开关就把所有猫都重载一遍是不可接受的。
 * 2. **按键只有一条订阅，扇出给所有窗口**（[startInputFanOut]）：
 *    按键是"全量状态"，一条订阅把同一个集合发给所有窗口天然正确，
 *    N 个窗口各起一条只是白白的 N 倍唤醒。
 *    位移则相反 —— 它是"相对量"，每个窗口零点不同，必须各订阅各的
 *    （见 [startMouseFeed]）。
 */
class OverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    /**
     * 一个悬浮窗。
     *
     * @param configId 它属于哪一份配置 —— 窗口的"身份"，也是开关/设置/位置的键
     * @param view 窗口的根视图（按键样式是 ComposeView，Live2D 是 FrameLayout + WebView）
     * @param params 这个窗口**自己的**布局参数。多窗口之后它不能再是一个全局字段：
     *   拖动改的是自己这份，[WindowManager.updateViewLayout] 也认自己这份。
     * @param config 当前渲染的配置。用 mutableStateOf 承载：Compose 内容要能感知变化，
     *   而"哪个 View 属于哪个 config"必须**与窗口绑定**，
     *   不能再像单窗口时那样读服务上的一个 currentConfig 字段。
     * @param styleId 当前视图是按哪个样式造的；与配置不一致就要重建视图
     */
    private class OverlayWindow(
        val configId: String,
        val view: View,
        val params: WindowManager.LayoutParams,
        initialConfig: KeyStrokesConfig,
        var styleId: String,
    ) {
        /**
         * 这个窗口的配置状态。
         *
         * ⚠️ 用 State 承载而不是普通字段：Compose 内容必须能感知到它的变化
         * （用户在主页改了颜色/键位，窗口要立刻跟上而不重建 WebView），
         * 而**每个窗口各有一份** —— 这正是多窗口与单窗口的关键差别：
         * 单窗口时读服务上的一个 `currentConfig` 就够了，
         * 多窗口那样做会让所有窗口一起显示最后被设置的那一份配置。
         */
        val configState: MutableState<KeyStrokesConfig> = mutableStateOf(initialConfig)

        var config: KeyStrokesConfig
            get() = configState.value
            set(value) {
                configState.value = value
            }

        /**
         * 这个窗口的鼠标位移流。
         *
         * ⚠️ **每个窗口各开一条**，而且必须在窗口创建时开 ——
         * 零点就是"开这条流的那一刻"。所有窗口共用一条的话，
         * 后开的窗口会立刻收到一个巨大的历史位移（见 [CaptureSession.openMouseMotion]）。
         *
         * 由 Service 注入而不是在 Live2DOverlayView 内部创建：
         * 采集层的入口（[CaptureSession]）不该被 UI 层直接依赖，
         * 而且"哪个窗口订阅哪条流"是窗口生命周期的一部分，归 Service 管。
         */
        var motion: StateFlow<MouseMotion>? = null

        /** 拖动是否已经开始（用来决定要不要存位置，以及参数错乱时的自愈） */
        var dragging: Boolean = false
    }

    private lateinit var windowManager: WindowManager

    /**
     * 当前挂着的全部窗口，键是配置 id。
     *
     * 为什么用 LinkedHashMap：窗口的创建顺序就是用户在主页里打开开关的顺序，
     * 调试页按这个顺序列出来更好读（而且阶梯错开的序号也依赖顺序稳定）。
     */
    private val windows = LinkedHashMap<String, OverlayWindow>()

    /**
     * 上一次已知的屏幕尺寸；与当前不一致就说明发生了旋转 / 折叠 / 分屏变化。
     *
     * 用**屏幕尺寸本身**判断而不是 `orientation`：分屏、折叠屏、显示尺寸设置
     * 都会改变可用区域却不改变方向，只看 orientation 会漏掉它们。
     */
    private var lastScreenSize: Pair<Int, Int>? = null

    /**
     * 这一次同步是不是由**系统重新布局**引起的（转屏 / 分屏 / 折叠）。
     *
     * ============================================================
     * 为什么需要区分"谁触发的"
     * ============================================================
     * `refreshScreenSize()` 本来每次同步都跑一遍。问题是 `onStartCommand` 也会
     * 触发同步 —— 而点一次「可触摸」开关、拖一下偏移滑块都会走 onStartCommand，
     * 于是**每一次操作都在拿尺寸做比对**，一旦当时显示器报出的尺寸与上次不同
     * （多窗口 / 分屏下很常见），就会顺势把窗口位置缩放一遍，
     * 表现为"点一下开关，窗口往左偏一下"。
     *
     * 现在只有系统真的重新布局时才比对与缩放；`onStartCommand` 那种例行同步
     * 只刷新窗口，不碰坐标。
     */
    private var displayRelayout = false

    /**
     * 服务自己的协程作用域。
     *
     * 目前只有一件事：把按键与鼠标状态扇出给所有 Live2D 宿主（WebView 只能在主线程碰）。
     * 服务销毁时一并取消，避免订阅悬挂。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 按键扇出订阅；服务存活期间只有这一条（位移是每窗口各一条，见 [startMouseFeed]） */
    private var keyFeedJob: Job? = null

    /*
     * ============================================================
     * Compose 的生命周期宿主
     * ============================================================
     * 悬浮窗不是在 Activity 里，而是 Service 自己往 WindowManager 上加窗口，
     * 因此视图树上**没有** Activity 提供的 LifecycleOwner / SavedStateRegistryOwner。
     *
     * ComposeView 在 attach 时会去找这两个 owner，找不到就抛异常 ——
     * 表现就是"一开悬浮窗就闪退"。所以这里由 Service 自己充当宿主：
     * 手动驱动生命周期状态，让 Compose 能正常完成首次组合。
     * 多窗口下这一份宿主由所有 ComposeView 共用，仍然是够的。
     * ============================================================
     */
    private val lifecycleRegistry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()

        /*
         * 整个启动流程都包在 try 里。
         *
         * 悬浮窗起不来是"功能不可用"，让应用闪退是"应用坏了" —— 后者严重得多，
         * 而且用户拿不到任何线索。任何一步失败都只记日志、停服务。
         */
        try {
            isRunning = true
            instance = this
            AppLog.i(TAG, "悬浮窗服务创建中…")

            savedStateRegistryController.performRestore(null)
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

            // 前台服务与加窗口的失败原因完全不同，分开捕获便于定位
            if (!startAsForegroundSafely()) {
                shutdownSelf()
                return
            }

            /*
             * 记下屏幕尺寸并对齐窗口。
             *
             * 启动时**必须**先对一次尺寸：屏幕可能在应用没运行的时候变过
             * （用户横着屏幕用了一阵、折叠屏开合），那时坐标的含义已经变了，
             * 只有落盘的那份"上次尺寸"能告诉我们需不需要重新锚定。
             */
            refreshScreenSize()
            syncWindows()

            /*
             * 窗口挂上去之后再把生命周期提到 RESUMED，Compose 才会开始渲染。
             * 注意这一步必须在 syncWindows 之后：ComposeView 是先 attach 才会去查 owner。
             */
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED

            AppLog.i(TAG, "悬浮窗服务已启动，窗口 ${windows.size} 个")
        } catch (e: Throwable) {
            AppLog.e(TAG, "悬浮窗启动异常", e as? Exception ?: Exception(e))
            shutdownSelf()
        }
    }

    /** 启动失败时的统一收尾：标记未运行并结束服务 */
    private fun shutdownSelf() {
        isRunning = false
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLog.i(
            TAG,
            "onStartCommand：当前 ${windows.size} 个窗口",
        )

        try {
            /*
             * 屏幕尺寸先对一次。
             *
             * 主页改开关、改配置设置之后都会走 startService 把服务叫起来刷新，
             * 而旋转不一定能及时收到 onConfigurationChanged（部分 ROM 上
             * 无 Activity 的服务进程收不到），所以每个入口都对一次最稳。
             */
            refreshScreenSize()
            syncWindows()
        } catch (e: Throwable) {
            AppLog.e(TAG, "刷新悬浮窗失败", e as? Exception ?: Exception(e))
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 屏幕尺寸变化（旋转 / 分屏 / 折叠 / 显示尺寸调整）。
     *
     * ⚠️ 这里最忌讳"只把坐标夹回屏内"：
     * 竖屏放在右下角的窗口，旋转后那个 X 已经超出屏宽，夹一下会让它"啪"地贴边，
     * 用户看到的是一次莫名其妙的跳位。正确做法是按新旧尺寸**重新锚定**
     * （保持窗口中心的相对位置），见 [applyRotationToWindows]。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLog.i(TAG, "系统配置变化（旋转 / 分屏 / 折叠），重新计算窗口位置")
        // 标记来源：只有这一条路径允许比对屏幕尺寸并缩放窗口坐标
        displayRelayout = true
        refreshScreenSize()
        syncWindows()
    }

    override fun onDestroy() {
        isRunning = false
        instance = null

        /*
         * 采集跟着窗口一起停。
         *
         * 顺序很重要：先停采集再拆窗口 —— 反过来的话，
         * 窗口拆掉后仍可能有一批事件在途，会往一个已经不存在的界面写状态。
         */
        CaptureController.stop()

        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        removeAllWindows()
        scope.cancel()
        AppLog.i(TAG, "悬浮窗已停止")
        super.onDestroy()
    }

    /*
     * ============================================================
     * 窗口集合的同步
     * ============================================================
     */

    /**
     * 把所有窗口同步到"当前应该有的样子"。
     *
     * ============================================================
     * 这是多窗口的唯一入口
     * ============================================================
     * 增（新开的开关）、删（关掉的开关 / 删掉的配置）、改（配置内容或本机设置变了）
     * 全在这里处理。主页只负责改"哪几个开着"，不碰窗口 ——
     * 否则窗口的创建/销毁逻辑会散在 UI 与 Service 两处，迟早打架。
     *
     * **能就地更新就不重建**：Live2D 窗口重建要重新解析模型（几百毫秒，
     * 期间是空的），所以配置内容变化走 [OverlayWindow.config] 赋值，
     * 只有"样式换了"（Compose ↔ WebView 是两种完全不同的视图）才重建视图。
     */
    private fun syncWindows() {
        val enabled = OverlayLayouts.enabledIds(this)
        val store = ConfigStore.get(this)

        // 失效的 id（配置已被删除）先清掉，避免它永远占着"还有窗口开着"的位置
        val valid = enabled.filter { store.find(it) != null }
        if (valid.size != enabled.size) {
            OverlayLayouts.setEnabledIds(this, valid.toSet())
        }

        // 1. 关掉的：拆窗口
        val removed = windows.keys.filter { it !in valid }
        removed.forEach { id -> removeWindow(id) }

        // 2. 开着的：已存在就地更新，新增的才创建
        valid.forEachIndexed { index, id ->
            val config = store.find(id) ?: return@forEachIndexed
            val existing = windows[id]

            if (existing == null) {
                addWindow(config, index)
            } else {
                existing.config = config
                if (existing.styleId != config.styleId) {
                    AppLog.i(TAG, "样式变化：${existing.styleId} → ${config.styleId}，重建窗口 $id")
                    recreateWindow(id, config)
                } else {
                    applyLayout(existing)
                }
            }
        }

        // 3. 尺寸重算。
        //
        //    配置里的按键大小/是否显示 Shift/CPS 模式都会改变窗口所需尺寸，
        //    尺寸没跟上会表现为"内容被裁掉"或"底部一大块空白"。
        //    [updateWindowSize] 内部先比较再动手，所以这里无条件跑一遍是安全的 ——
        //    比"猜哪些改动会影响尺寸"可靠得多（漏猜一次就是一个只在特定配置下
        //    才出现的裁切 bug）。
        windows.values.forEach { window ->
            updateWindowSize(window)
            // 尺寸变了，原本合法的坐标可能出屏，重新走一遍位置计算
            applyPosition(window)
        }

        /*
         * 4. 采集的开关由"有没有窗口"决定，而不是由请求方决定。
         *
         * [CaptureController.start] 本身幂等：连续开多个开关只会真正启动一次，
         * 这正是"多开不冲突"的落点。反过来只要还有窗口就不能停 ——
         * 少判这一下就会出现"关掉一个窗口，剩下的窗口按键不动了"。
         */
        if (windows.isEmpty()) {
            CaptureController.stop()
            AppLog.i(TAG, "已没有悬浮窗，停止采集并结束服务")
            stopSelf()
        } else {
            CaptureController.start()
        }
    }

    /**
     * 新开一个窗口。
     *
     * @param index 这是第几个（用来做阶梯错开的默认位置，
     *   否则新窗口会完全盖住已有的那个，用户会以为开关没生效）
     * @return 是否成功
     */
    private fun addWindow(config: KeyStrokesConfig, index: Int): Boolean {
        val size = windowSizePx(config)
        val screen = OverlayBounds.screenSize(this)
        val density = resources.displayMetrics.density

        val params = WindowManager.LayoutParams(
            size.first,
            size.second,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            windowFlags(touchable = OverlayLayouts.of(this, config.id).touchable),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START

            /*
             * 默认位置：阶梯错开。
             *
             * 每个窗口都落在同一个默认点上时，后开的那一个会**完全盖住**前一个，
             * 看起来就是"开了第二个开关但什么都没发生"。
             */
            val (defaultX, defaultY) = OverlayBounds.cascadeDefault(
                baseX = dpToPx(DEFAULT_X_DP, density),
                baseY = dpToPx(DEFAULT_Y_DP, density),
                index = index,
                stepPx = dpToPx(CASCADE_STEP_DP, density),
            )

            // 兜底位置也要夹边界：窗口比屏幕还大、或错开得太多时会出屏
            val (x0, y0) = OverlayBounds.clamp(
                defaultX,
                defaultY,
                size.first,
                size.second,
                screen.first,
                screen.second,
            )
            x = x0
            y = y0
        }

        /*
         * 视图与窗口互为前提（窗口要有视图才能构造，视图要有窗口才有配置状态），
         * 用一个后填的取值函数把环打开：Compose 的首次组合发生在 attach 之后，
         * 那时 [windowRef] 一定已经赋值。
         */
        var windowRef: OverlayWindow? = null
        val view = createOverlayView(config) {
            windowRef?.configState ?: mutableStateOf(config)
        }
        setupDragListener(view, params, config.id)

        /*
         * 鼠标位移流**在创建窗口的这一刻就开**，零点就是现在。
         *
         * 必须在 addView 之前拿到：Live2DOverlayView 构造完就会开始加载页面，
         * 页面就绪后可能马上就有位移进来，晚开一步就会漏掉开头那一小段。
         */
        val motion = if (config.styleId == StyleId.KEYBOARD_CAT) {
            CaptureSession.openMouseMotion()
        } else {
            null
        }

        return try {
            windowManager.addView(view, params)
            val window = OverlayWindow(
                configId = config.id,
                view = view,
                params = params,
                initialConfig = config,
                styleId = config.styleId,
            ).apply { this.motion = motion }
            windowRef = window
            windows[config.id] = window

            /*
             * 位置走**唯一算法**（基础坐标 + 偏移，再夹进屏内）。
             * 直接写 params.x/y 的话，那条"必须夹边界"的规则就漏了一处 ——
             * 而漏掉的表现是"某个入口进来的窗口会跑到屏幕外"。
             */
            applyPosition(window)

            // 输入：按键是共用的一条扇出，位移是这个窗口自己的一条
            startInputFanOut()
            startMouseFeed(window)

            /*
             * 挂上去之后才知道窗口到底有没有硬件加速。
             * 这条日志是 Live2D 出问题时最关键的判据：
             * 如果是 false，WebView 画不出任何东西，问题不在 JS 也不在模型。
             */
            view.post {
                AppLog.i(
                    TAG,
                    "悬浮窗已挂载：配置=${config.name}(${config.id.take(8)})，" +
                        "样式=${config.styleId}，硬件加速=${view.isHardwareAccelerated}，" +
                        "尺寸=${params.width}×${params.height}，位置=(${params.x},${params.y})，" +
                        "可触摸=${OverlayLayouts.of(this, config.id).touchable}",
                )
            }
            true
        } catch (e: Throwable) {
            AppLog.e(TAG, "添加悬浮窗失败（是否缺少悬浮窗权限？）", e as? Exception ?: Exception(e))
            false
        }
    }

    /**
     * 样式换了：拆掉旧视图、按新样式重建。
     *
     * 两种样式的视图类型完全不同（Compose 树 vs WebView），
     * 在既有视图上没法切换 —— 只更新配置的话，用户从设置里切样式会毫无反应，
     * 旧视图还在，只是配置变了。
     *
     * 窗口位置保持不动：用户看到的是"画面的样式变了"，
     * 位置跟着跳回默认值会很突兀。
     */
    private fun recreateWindow(configId: String, config: KeyStrokesConfig): Boolean {
        val old = windows.remove(configId) ?: return addWindow(config, windows.size)

        val x = old.params.x
        val y = old.params.y
        // Live2D 的 WebView 必须显式销毁，否则会连同渲染线程一起泄漏
        (old.view as? Live2DOverlayView)?.release()
        runCatching { if (old.view.parent != null) windowManager.removeView(old.view) }

        val size = windowSizePx(config)
        val params = WindowManager.LayoutParams(
            size.first,
            size.second,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            windowFlags(touchable = OverlayLayouts.of(this, configId).touchable),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }

        var windowRef: OverlayWindow? = null
        val view = createOverlayView(config) { windowRef?.configState ?: mutableStateOf(config) }
        setupDragListener(view, params, configId)

        // 重建视图也要重开一条位移流：旧视图那条的零点属于上一个页面，
        // 新页面刚 clear() 过（指针回到中心），沿用它会让第一次移动的幅度不对
        val motion = if (config.styleId == StyleId.KEYBOARD_CAT) {
            CaptureSession.openMouseMotion()
        } else {
            null
        }

        return try {
            windowManager.addView(view, params)
            val window = OverlayWindow(
                configId = configId,
                view = view,
                params = params,
                initialConfig = config,
                styleId = config.styleId,
            ).apply { this.motion = motion }
            windowRef = window
            windows[configId] = window
            applyPosition(window)
            startMouseFeed(window)
            true
        } catch (e: Throwable) {
            AppLog.e(TAG, "重建悬浮窗失败：$configId", e as? Exception ?: Exception(e))
            false
        }
    }

    private fun removeWindow(configId: String) {
        val window = windows.remove(configId) ?: return
        try {
            // Live2D 的 WebView 必须显式销毁，否则会连同它的渲染线程一起泄漏
            (window.view as? Live2DOverlayView)?.release()
            if (window.view.parent != null) windowManager.removeView(window.view)
        } catch (_: Exception) {
        }
        AppLog.i(TAG, "已关闭悬浮窗：${configId.take(8)}（剩余 ${windows.size} 个）")
    }

    private fun removeAllWindows() {
        windows.keys.toList().forEach { removeWindow(it) }
    }

    /*
     * ============================================================
     * 窗口参数
     * ============================================================
     */

    /**
     * 窗口尺寸（像素）：宽度与高度。
     *
     * 尺寸必须**按配置重算**：按键大小/是否显示 Shift/CPS 模式都会改变所需高度，
     * 不重算的话窗口会保持旧尺寸，内容被裁掉或留大片空白。
     *
     * ⚠️ 这里**不需要**传实时的 CPS 数值。查过 [KeyLayout.baseHeight]：
     * 决定高度的只有配置本身（CPS 模式 2 多一行、模式 3 键更高），
     * 与"现在有没有点击、数字是几"无关。传实时值反而会让窗口尺寸
     * 跟着每秒衰减的数字抖动 —— 那是个更糟的 bug。
     *
     * ⚠️ 取整必须用 [toWindowPx]（**向上**取整）。内容尺寸是小数，用截断的话
     * 窗口会比内容小一点，最右/最下那几个像素被切掉 —— 只在缩放不是整数时
     * 出现，极难联想到取整。宁可多一个像素（外面多一条透明边）。
     *
     * ============================================================
     * ⚠️ 高度**必须**带上 CPS 数值（这里漏过）
     * ============================================================
     * `KeyLayout.baseHeight` 依赖 CPS 数值：**模式 2 会多出一行**、
     * 模式 3 会让鼠标键变高。按"没有 CPS"算的话，多出来的那一行
     * 落在窗口之外 —— 表现就是"底部的 CPS 行不显示"。
     *
     * [overlayWindowHeightPx] 的注释里明确写了"必须传"，但这里
     * 一直用的是默认的空 map，于是那条要求在**改尺寸**这条路径上失效：
     * 开窗口时可能碰巧对（首次算的时候 CPS 还是 0、行高用最小值），
     * 一旦 CPS 有数值、模式 2 那一行变高，窗口就不够了。
     */
    private fun windowSizePx(config: KeyStrokesConfig): Pair<Int, Int> = toWindowPx(
        overlayWindowWidthPx(config),
    ) to toWindowPx(
        overlayWindowHeightPx(
            config = config,
            /*
             * 与渲染用的是**同一份**数值来源（那份配置自己的计数器），
             * 否则"窗口按 A 算、内容按 B 画"，又是两套口径。
             */
            cpsBySlot = CaptureSession.cpsSnapshotOf(config.id),
        ),
    )

    /**
     * 窗口标志。
     *
     * [touchable] = false 时加 `FLAG_NOT_TOUCHABLE`：窗口**仍然在、仍然渲染**，
     * 只是不再拦截任何点击（这就是"贴图"的正确实现方式，
     * 而不是把窗口设成不可见或降低 alpha）。
     */
    private fun windowFlags(touchable: Boolean): Int {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            /*
             * 必须显式要求硬件加速。
             *
             * 用 WindowManager 加的窗口（不是 Activity 的窗口）默认**不一定**拿到
             * GPU 上下文，而 Live2D 的 WebView 走 WebGL，没有 GPU 就什么都画不出来 ——
             * 表现是"窗口在、里面全空"，而且没有任何报错。
             * 旧项目的可编辑窗口也加了这一条，属于同一类坑。
             */
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return flags
    }

    /*
     * ============================================================
     * ⚠️ 这里原来还有 FLAG_LAYOUT_NO_LIMITS，**故意去掉了**
     * ============================================================
     * 那个标志的语义是"允许窗口跑到屏幕外，系统不要管" ——
     * 它把系统**本来自带的边界约束**关掉了，于是边界全靠我们自己算，
     * 而我们算的是"整块显示器"的高度，比窗口真实可用区域大一点点（系统栏）。
     * 两个后果都真实发生过：
     *
     * 1. **底部能多拖一点**：系统不夹了，只有我们那个偏大的边界在夹；
     * 2. **开关「可触摸」时窗口跳一下**：切换 FLAG_NOT_TOUCHABLE 会让系统
     *    重新计算窗口的 insets 并把窗口重新落位，叠加在原本就偏的位置上就表现为跳。
     *
     * 去掉之后，越界由系统兜住（它永远比我们算得准），
     * 我们算的边界退回本职：给偏移滑块定范围、给重置定默认位置。
     * ============================================================
     */

    /**
     * 按键样式是 ComposeView；Live2D 是普通的 FrameLayout + WebView。
     *
     * @param configState 这个窗口的配置状态。ComposeView 的**首次组合**要等到
     *   attach 到窗口之后才会发生，那时窗口已经登记进 [windows]，所以这个取值
     *   一定能拿到真正的状态。用取值函数而不是直接传状态：
     *   视图必须先造出来才能 addView，而窗口对象要先有视图才能构造 ——
     *   两者互为前提，中间只能靠一层延迟取值把环打开。
     */
    private fun createOverlayView(
        config: KeyStrokesConfig,
        configState: () -> State<KeyStrokesConfig>,
    ): View =
        if (config.styleId == StyleId.KEYBOARD_CAT) {
            Live2DOverlayView(this).apply {
                applyTarget(Live2DModels.resolve(config.live2d.modelId))
                alpha = config.live2d.opacityPercent.coerceIn(0, 100) / 100f
            }
        } else {
            /*
             * ComposeView 需要显式挂上 ViewTree 的 owner。
             *
             * 普通 Activity 里 Compose 会自动拿到这些（由 ComponentActivity 提供），
             * 但悬浮窗用的是 Service 的 context 创建的独立窗口，没有这些 owner。
             * 缺失时视图树在 attach 阶段可能直接抛异常，也就是"一开悬浮窗就崩"。
             *
             * ⚠️ 内容里读的是**这个窗口自己的**配置状态，不能再像单窗口时那样
             * 读服务上的一个 currentConfig 字段 —— 那样 N 个窗口会一起显示
             * 最后被设置的那一份配置。传 State 而不是值：配置内容改动时
             * 窗口要能重组，而"传值"只会在首次组合时读一次。
             */
            ComposeView(this).apply {
                setViewTreeLifecycleOwner(this@OverlayService)
                setViewTreeSavedStateRegistryOwner(this@OverlayService)
                setContent {
                    SthKeyTheme {
                        OverlayContent(
                            config = configState(),
                            // 内边距按像素换算成 dp，与窗口尺寸的口径保持一致
                            modifier = Modifier.padding(
                                (OVERLAY_PADDING_PX / resources.displayMetrics.density).dp,
                            ),
                        )
                    }
                }
            }
        }

    /**
     * 把配置应用到已经存在的视图上（不重建）。
     *
     * 只处理**能在既有视图上生效**的改动：Live2D 的模型页与整层透明度
     * 都可以直接改，不必重建 WebView（重建要重新解析模型，几百毫秒）。
     */
    private fun applyConfigToView(view: View, config: KeyStrokesConfig) {
        val live2d = view as? Live2DOverlayView ?: return
        live2d.applyTarget(Live2DModels.resolve(config.live2d.modelId))
        live2d.alpha = config.live2d.opacityPercent.coerceIn(0, 100) / 100f
    }

    /**
     * 把**本机设置**（可触摸 / 偏移）应用到窗口上。
     *
     * 可触摸状态用 `updateViewLayout` 改标志位实现，**不重建窗口**：
     * 贴图 ↔ 可拖动切换时，用户不希望看见猫重新加载一次。
     */
    private fun applyLayout(window: OverlayWindow) {
        val layout = OverlayLayouts.of(this, window.configId)

        val flags = windowFlags(layout.touchable)
        if (window.params.flags != flags) {
            window.params.flags = flags
            // 关掉可触摸时若手势还没结束，先把拖动状态清掉，
            // 否则下次打开会带着一条不存在的手势继续移动
            if (!layout.touchable) window.dragging = false
            updateLayoutParams(window)
        }

        applyPosition(window)
        applyConfigToView(window.view, window.config)
    }

    /**
     * 把窗口挪到它应该在的位置：**基础坐标 + 偏移，再夹进屏内**。
     *
     * 这是"位置"这件事的唯一算法（[OverlayBounds.resolvePosition]），
     * 拖动、滑块、旋转、重置都走它 —— 少走一处就会出现
     * "从这个入口进来的窗口会跑到屏幕外"。
     */
    private fun applyPosition(window: OverlayWindow) {
        val layout = OverlayLayouts.of(this, window.configId)
        val screen = OverlayBounds.screenSize(this)
        val (defaultX, defaultY) = defaultPosition(window.configId)

        val (x, y) = OverlayBounds.resolvePosition(
            layout = layout,
            defaultX = defaultX,
            defaultY = defaultY,
            windowWidth = window.params.width,
            windowHeight = window.params.height,
            screenWidth = screen.first,
            screenHeight = screen.second,
        )

        if (window.params.x == x && window.params.y == y) {
            // 位置没变也要对一次存档 —— 系统可能自己挪过窗口（见 syncPositionFromActual）
            window.view.post { syncPositionFromActual(window) }
            return
        }
        window.params.x = x
        window.params.y = y
        updateLayoutParams(window)
        // 落位之后再读实际值，系统若又挪了就以它为准
        window.view.post { syncPositionFromActual(window) }
    }

    /**
     * 把"实际生效的位置"同步回存档。
     *
     * ============================================================
     * 为什么需要
     * ============================================================
     * `params.x/y` 是**我们请求**的位置，而系统可能按窗口 insets 自己再挪一点。
     * 存档里如果留着我们请求的那个值，下次任何一次重算都会把它**重新应用一遍**。
     *
     * ============================================================
     * ⚠️ 存进去的必须是 base，不是实际位置（这里踩过坑）
     * ============================================================
     * 最终位置 = **base + offset**，所以"实际位置"里**含着偏移**。
     * 直接把实际位置写进 base，等于把偏移又算了一遍：
     *
     *     实际 = base + offset  →  写成 base' = base + offset
     *     下次请求 = base' + offset = base + 2×offset
     *
     * 偏移是负的时候（贴屏幕右侧时 base 被夹小、offset 为负），
     * 每同步一次就再减一个 offset —— 真实表现就是
     * **"每开关一次「可触摸」，窗口就往左移一点"**。
     * 所以这里必须减掉 offset 存回去。
     *
     * 拖动过程中跳过：那时用户的手指正在定位置，
     * 存档该由 ACTION_UP 那一处负责写（那里存的是 params，且不带偏移的语义见该处注释）。
     */
    private fun syncPositionFromActual(window: OverlayWindow) {
        if (!window.view.isLaidOut || window.dragging) return

        val layout = OverlayLayouts.of(this, window.configId)
        // 还没被拖过的窗口停在**默认位置**（阶梯错开算出来的），那个值不该被当成 base 存下来，
        // 否则"重置位置"就再也回不到默认点了
        if (layout.baseX == OverlayLayout.POSITION_UNSET ||
            layout.baseY == OverlayLayout.POSITION_UNSET
        ) {
            return
        }

        // 减掉偏移：base 是"没有偏移时窗口该在的位置"，实际位置里含着偏移
        val baseX = window.params.x - layout.offsetX
        val baseY = window.params.y - layout.offsetY
        if (layout.baseX == baseX && layout.baseY == baseY) return

        OverlayLayouts.update(this, window.configId) {
            it.copy(baseX = baseX, baseY = baseY)
        }
        AppLog.d(
            TAG,
            "窗口位置已按系统实际落位同步：${window.configId.take(8)} " +
                "实际=(${window.params.x}, ${window.params.y}) → base=($baseX, $baseY)",
        )
    }

    /**
     * 这个窗口"从没被拖动过"时应该待的地方。
     *
     * 与创建时用的是同一套阶梯错开算法，序号取它当前在窗口列表里的位置 ——
     * 于是"重置坐标"会让窗口回到它该在的错开位置，而不是所有窗口都叠到同一个点。
     */
    private fun defaultPosition(configId: String): Pair<Int, Int> {
        val index = windows.keys.indexOf(configId).coerceAtLeast(0)
        val density = resources.displayMetrics.density
        return OverlayBounds.cascadeDefault(
            baseX = dpToPx(DEFAULT_X_DP, density),
            baseY = dpToPx(DEFAULT_Y_DP, density),
            index = index,
            stepPx = dpToPx(CASCADE_STEP_DP, density),
        )
    }

    /**
     * 需要时重算尺寸：改缩放、开关 Shift、换 CPS 模式都会改变所需尺寸。
     *
     * ============================================================
     * ⚠️ 改完尺寸**必须重新夹一次位置**（这里踩过坑）
     * ============================================================
     * 现象：把「整体缩放」调大之后，**空格与 Shift 的右边短了一截**，
     * 而配置预览、自定义编辑页都正常。
     *
     * 根因就在这个函数里：它只改了 `params.width/height`，**没有重算位置**。
     * 窗口原本落在 x=463（屏幕 900 宽），尺寸从 300 变成 502 之后
     * 右边界到了 965 —— 超出屏幕。系统不会让窗口悬在屏幕外，
     * 于是把它压回 `900 − 463 = 437`。
     *
     * 而 Compose 的布局是按**我们请求的 502** 排的，画布右边一截
     * （正好是最宽的空格与 Shift）就落在了窗口之外被裁掉。
     * 调试页的"运行时实测"把矛盾摆得很清楚：
     * **窗口参数 437，而我们算的是 502**。
     *
     * 修法不是"把尺寸限制在屏幕内" —— 那等于用户调大缩放时反而变小，
     * 把他要做的事直接否掉。而是**把窗口往左推，让它整个可见**：
     * 位置本来就有 [OverlayBounds.clamp] 这个唯一算法（[applyPosition]），
     * 尺寸变了就该再走一遍。
     */
    private fun updateWindowSize(window: OverlayWindow) {
        val (width, height) = windowSizePx(window.config)
        if (window.params.width == width && window.params.height == height) return

        window.params.width = width
        window.params.height = height
        updateLayoutParams(window)
        AppLog.d(TAG, "窗口 ${window.configId.take(8)} 尺寸更新为 ${width}×${height}")

        /*
         * 尺寸变了，可用余量也变了 —— 重新走一遍位置算法。
         *
         * 这是"位置"的唯一入口（拖动、滑块、旋转、重置都走它），
         * 所以这里不会引入第二套边界逻辑。
         */
        applyPosition(window)
    }

    /**
     * 提交参数给 WindowManager。
     *
     * 每个窗口都有自己的 params，所以这里必须拿到窗口本身，
     * 不能再像单窗口时那样隐式地用一个全局 params。
     */
    private fun updateLayoutParams(window: OverlayWindow) {
        try {
            if (window.view.parent != null) windowManager.updateViewLayout(window.view, window.params)
        } catch (e: Exception) {
            AppLog.w(TAG, "更新悬浮窗参数失败：${e.javaClass.simpleName}")
        }
    }

    /*
     * ============================================================
     * 屏幕尺寸变化
     * ============================================================
     */

    /**
     * 读一次屏幕尺寸，必要时把全部窗口重新锚定。
     *
     * 判断依据是**尺寸本身**：分屏、折叠屏、显示尺寸设置都会改变可用区域
     * 却不改变方向，只看 orientation 会全部漏掉。
     */
    private fun refreshScreenSize() {
        val prefs = AppPrefs.get(this)
        val current = OverlayBounds.screenSize(this)

        /*
         * 例行同步（onStartCommand）只负责"窗口按最新配置重排"，
         * **不碰坐标** —— 原因见 displayRelayout 的说明。
         */
        if (!displayRelayout) {
            lastScreenSize = current
            return
        }
        displayRelayout = false

        /*
         * "上次尺寸"取两份记录中更可靠的那一份：
         * - 进程内的 lastScreenSize（同一次运行里转屏，最准）；
         * - 落盘的 overlayScreenWidth/Height（应用没运行时屏幕变过的情况）。
         * 两边都没有（全新安装）时不做换算 —— 没有"旧位置"可以换算。
         */
        val stored: Pair<Int, Int>? = prefs.overlayScreenWidth
            .takeIf { it > 0 }
            ?.let { it to prefs.overlayScreenHeight }

        val previous = lastScreenSize ?: stored
        lastScreenSize = current
        prefs.overlayScreenWidth = current.first
        prefs.overlayScreenHeight = current.second

        if (previous == null || previous == current) return

        /*
         * ============================================================
         * ⚠️ 只有**真的转屏**才缩放窗口位置（这里踩过坑）
         * ============================================================
         * 原来只要"尺寸不一样"就缩放一遍。看起来合理，实际会出事：
         * 开关一次「可触摸」就会走一遍 onStartCommand → 这里比对一次，
         * 而多窗口 / 分屏下**显示器尺寸本来就会被系统报成另一个值** ——
         * 于是每点一次开关就把窗口按比例缩一次，表现为"窗口往左偏一下"。
         *
         * 现在只在**方向真的翻转**（竖↔横）时才缩放，其余情况一律只夹回屏内。
         * 代价是"分屏里屏幕变矮"这种尺寸变化不做等比缩放 ——
         * 那本来就该由用户自己摆（位置是他的选择），我们只保证不出屏。
         * ============================================================
         */
        val p = previous.first to previous.second
        if (isOrientationFlip(p, current)) {
            AppLog.i(TAG, "屏幕方向变化：${p.first}×${p.second} → ${current.first}×${current.second}，重新锚定窗口")
            applyRotationToWindows(p, current)
        } else {
            AppLog.i(TAG, "显示器尺寸变化（非转屏）：${p.first}×${p.second} → ${current.first}×${current.second}，只夹回屏内")
        }
    }

    /**
     * 两次尺寸之间是不是"竖横翻转"。
     *
     * 判据用**宽高的大小关系**（谁更大），而不是具体比例：多窗口下同一方向
     * 也可能报出差别很大的尺寸，用比例判断会把"小窗"误判成转屏 ——
     * 那正是上面那个 bug 的成因。
     */
    private fun isOrientationFlip(previous: Pair<Int, Int>, current: Pair<Int, Int>): Boolean =
        previous.first > previous.second != current.first > current.second

    /**
     * 屏幕尺寸变化后重新锚定每个窗口。
     *
     * 基础坐标按窗口中心等比换算（"贴右下角"的旋转后仍在右下角），
     * **偏移量也一起换算**：它是"相对基准位置的位移"，基准尺度变了一半而偏移不动，
     * 滑块的中点就不再是视觉上的"默认位置"，几个窗口还会因此挤到一起。
     */
    private fun applyRotationToWindows(previous: Pair<Int, Int>, current: Pair<Int, Int>) {
        val (oldWidth, oldHeight) = previous
        val (newWidth, newHeight) = current
        if (oldWidth <= 0 || oldHeight <= 0) return

        val scaleX = newWidth.toFloat() / oldWidth
        val scaleY = newHeight.toFloat() / oldHeight

        windows.forEach { (configId, window) ->
            val layout = OverlayLayouts.of(this, configId)
            val baseX = if (layout.baseX == OverlayLayout.POSITION_UNSET) {
                OverlayLayout.POSITION_UNSET
            } else {
                (layout.baseX * scaleX).toInt()
            }
            val baseY = if (layout.baseY == OverlayLayout.POSITION_UNSET) {
                OverlayLayout.POSITION_UNSET
            } else {
                (layout.baseY * scaleY).toInt()
            }

            OverlayLayouts.update(this, configId) {
                it.copy(
                    baseX = baseX,
                    baseY = baseY,
                    offsetX = (layout.offsetX * scaleX).toInt(),
                    offsetY = (layout.offsetY * scaleY).toInt(),
                )
            }

            // 尺寸也可能跟着显示区域变（分屏），一并重算
            updateWindowSize(window)
            applyPosition(window)
        }
    }

    /*
     * ============================================================
     * 输入扇出
     * ============================================================
     * 采集层与窗口之间只有这一处连接。**一条订阅扇出给所有窗口**，
     * 而不是每个窗口各起一个协程订阅 —— N 个窗口也只有一条订阅，
     * 少掉的 N-1 条唤醒在高频输入下是实打实的开销。
     */

    private fun startInputFanOut() {
        if (keyFeedJob != null) return

        keyFeedJob = scope.launch {
            CaptureSession.pressedKeys.collect { propagateKeys(it) }
        }

        /*
         * 鼠标位移：**每个窗口一条订阅**，订阅的是它自己的那条位移流。
         *
         * ============================================================
         * 为什么这里不像按键那样扇出
         * ============================================================
         * 按键是"全量状态"，一条订阅把同一个集合发给所有窗口是天然正确的。
         * 位移是"相对量"，每个窗口的**零点不同**（各自在创建时才开流），
         * 所以必须各订阅各的 —— 共用一条就会让后开的窗口吃到一段历史位移。
         *
         * 开销可以接受：鼠标 1000Hz 时每条订阅只是做一次减法 + 排一帧，
         * 真正发往 JS 的调用被合进帧里（最多 60 次/秒/窗口）。
         */
        windows.values.forEach { startMouseFeed(it) }
    }

    /**
     * 给一个窗口接上它自己的鼠标位移流。
     *
     * 窗口创建时调用。流由窗口持有（[OverlayWindow.motion]），
     * 所以窗口销毁时这条订阅自然失去意义 —— 仍在 [scope] 里，
     * 服务销毁时一并取消。
     */
    private fun startMouseFeed(window: OverlayWindow) {
        val live = window.view as? Live2DOverlayView ?: return
        val motion = window.motion ?: return

        scope.launch {
            motion.collect { value -> live.updateMouseMotion(value.dx, value.dy) }
        }
    }

    /**
     * 把按键集合推给所有 Live2D 窗口。
     *
     * 按键样式不需要这条链路：它的 Compose 内容自己 `collectAsState()` 订阅同一个
     * StateFlow（Compose 的订阅不需要我们操心）。
     *
     * ⚠️ 每个窗口都必须收到**全量集合**，不能只推变化量：
     * 窗口内部的语义状态机是"用完整集合重算"的，缺一个键就会算错。
     */
    private fun propagateKeys(codes: Set<Int>) {
        live2dViews().forEach { it.updateKeys(codes) }
    }

    /** 当前所有 Live2D 宿主；没有时返回空列表，调用方不用判空 */
    private fun live2dViews(): List<Live2DOverlayView> =
        windows.values.mapNotNull { it.view as? Live2DOverlayView }

    /*
     * ============================================================
     * 拖动
     * ============================================================
     */

    /**
     * 拖动移动悬浮窗（**每个窗口一份**）。
     *
     * 两个要点：
     * 1. 用原始坐标（rawX/rawY）而不是相对坐标 —— 窗口自身在移动，
     *    用相对坐标会出现"越拖越偏"的经典问题；
     * 2. 位置写进 `params` 之前**先夹边界**，手指滑出屏幕时窗口会稳稳停在边上
     *    而不是跟着跑到屏幕外（旧项目的毛病是底部永远有一块拖不到，
     *    原因是那边窗口尺寸写死、比内容高，边界按窗口算就留了空白；
     *    我们的窗口尺寸是按内容算的，所以边界可以直接用窗口尺寸）。
     *
     * 监听器挂在**根视图**上。对 Live2D 来说，子 View 是 WebView，
     * 它默认会吃掉触摸事件，所以 Live2DOverlayView 自己在
     * `onInterceptTouchEvent` 里把整个手势抢了过来（否则窗口拖不动）。
     *
     * ⚠️ 这里改的只是**基础坐标**，与偏移滑块互不干扰：
     * 最终位置 = 基础坐标 + 偏移，滑块调的是后者。所以拖完之后
     * 滑块的值**不会变**（这正是需求里"拖动不影响滑块"的含义）。
     *
     * @param configId 这个视图属于哪一份配置。**必须在创建时就传进来**，
     *   不能等登记进 [windows] 之后再反查：登记发生在 addView 成功之后，
     *   而那时监听器早就装好了 —— 反查会得到一个 null，拖完什么都不存。
     */
    private fun setupDragListener(
        view: View,
        params: WindowManager.LayoutParams,
        configId: String,
    ) {
        var startX = 0
        var startY = 0
        var touchStartX = 0f
        var touchStartY = 0f
        var moved = false

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    moved = false
                    windows[configId]?.dragging = true
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchStartX
                    val dy = event.rawY - touchStartY
                    if (kotlin.math.abs(dx) > DRAG_THRESHOLD_PX ||
                        kotlin.math.abs(dy) > DRAG_THRESHOLD_PX
                    ) {
                        moved = true
                    }

                    /*
                     * 夹边界。
                     *
                     * 拖动过程中**不夹**的话，手指划出屏幕再划回来，
                     * 窗口会被"欠"下一大截位移（表现为拖不动了）；
                     * 所以这里实时夹，窗口跟手但不会离开屏幕。
                     *
                     * ⚠️ 屏幕尺寸必须走 [OverlayBounds.screenSize]（问显示器），
                     * 不能用 `resources.displayMetrics`（那是应用窗口的尺寸）——
                     * 多窗口下后者会偏小，于是边界算宽、窗口能拖出屏幕外。
                     */
                    val (screenWidth, screenHeight) = OverlayBounds.screenSize(this)
                    val (cx, cy) = OverlayBounds.clamp(
                        x = startX + dx.toInt(),
                        y = startY + dy.toInt(),
                        windowWidth = params.width,
                        windowHeight = params.height,
                        screenWidth = screenWidth,
                        screenHeight = screenHeight,
                    )
                    params.x = cx
                    params.y = cy
                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (_: Exception) {
                    }
                    true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                -> {
                    windows[configId]?.dragging = false
                    if (moved) {
                        /*
                         * 只记住用户主动拖过的位置，避免打开时的初始位置被覆盖。
                         *
                         * ⚠️ `params` 里是**最终位置**（= base + 偏移），
                         * 而这里要存的是 **base**，所以必须把偏移减掉。
                         * 不减的话：拖完之后 base 里就含了一份偏移，
                         * 下次再加一遍偏移 —— 只要偏移不是 0，窗口就会跳一下，
                         * 而且每拖一次就多叠一份（与 syncPositionFromActual 是同一个坑）。
                         *
                         * 偏移在这里读一次即可：拖动不会改它（滑块与拖动是两回事）。
                         */
                        val layout = OverlayLayouts.of(this, configId)
                        val baseX = params.x - layout.offsetX
                        val baseY = params.y - layout.offsetY
                        OverlayLayouts.update(this, configId) {
                            it.copy(baseX = baseX, baseY = baseY)
                        }
                        AppLog.d(
                            TAG,
                            "悬浮窗位置已保存：$configId 实际=(${params.x}, ${params.y}) → base=($baseX, $baseY)",
                        )
                    }
                    true
                }

                else -> false
            }
        }
    }

    /*
     * ============================================================
     * 前台服务
     * ============================================================
     */

    /**
     * 启动前台服务。
     *
     * @return 是否成功。Android 12+ 对后台启动前台服务有严格限制，
     *   被系统拒绝时这里会失败 —— 只记录原因，不让应用崩溃。
     */
    private fun startAsForegroundSafely(): Boolean = try {
        startAsForeground()
        true
    } catch (e: Throwable) {
        AppLog.e(
            TAG,
            "启动前台服务失败：${e.javaClass.simpleName}: ${e.message}",
            e as? Exception ?: Exception(e),
        )
        false
    }

    private fun startAsForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "悬浮窗运行状态",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "显示按键悬浮窗时保持运行"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("按键悬浮窗正在运行")
            .setContentText("拖动可移动位置；在应用主页可逐个关闭")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val TAG = "Overlay"

        private const val CHANNEL_ID = "sthkey_overlay"
        private const val NOTIFICATION_ID = 1001

        /** 判定为"拖动"而不是"点击"的阈值（像素）；小一点的手抖不会影响点击 */
        private const val DRAG_THRESHOLD_PX = 4

        /** 首次启动时悬浮窗的默认位置（dp） */
        private const val DEFAULT_X_DP = 16f
        private const val DEFAULT_Y_DP = 120f

        /**
         * 多个窗口默认位置的阶梯错开量（dp）。
         *
         * 20dp 在手机上是肉眼可辨的一小段，既不会把第二个窗口推出屏幕，
         * 又能让用户立刻看出"确实多了一个窗口"。
         */
        private const val CASCADE_STEP_DP = 24f

        @Volatile
        private var isRunning = false

        /** 当前服务实例；用于让外部查询它正在渲染的窗口 */
        @Volatile
        private var instance: OverlayService? = null

        fun isRunning(): Boolean = isRunning

        /**
         * 当前**实际挂着**的窗口标签（形如 `名称(id前8位)`）。
         *
         * 调试页用它和"哪几个开关开着"做对比：
         * - 开着的 id 不在这个列表里 → 窗口没建起来（服务没收到通知 / 权限没了）
         * - 列表里有、屏幕上却看不到 → 位置出屏或透明度为 0
         *
         * @return 按创建顺序排列的标签；服务未运行或没有窗口时返回空列表
         */
        fun renderedWindowLabels(): List<String> {
            if (!isRunning) return emptyList()
            val service = instance ?: return emptyList()
            return service.windows.values.map { window ->
                val config = window.config
                "${config.name}(${window.configId.take(8)})"
            }
        }

        /**
         * 当前**实际在显示**的窗口及其配置。
         *
         * ============================================================
         * 为什么必须由服务给出配置，而不是调用方自己挑一份
         * ============================================================
         * 排查"某个键被裁"时，诊断面板原来是**自己从全部配置里挑一份**
         * （第一份非自定义样式的）来算尺寸 —— 而那很可能根本不是
         * 屏幕上那个窗口用的配置。
         *
         * 表现就是：面板说"窗口该是 502 宽"，真实窗口却是 323，
         * 于是所有推算全部失去意义，往错误的方向查了两轮。
         * **诊断工具读错数据比没有诊断更糟。**
         */
        fun liveWindows(): List<LiveWindow> {
            val service = instance
            if (!isRunning || service == null) return emptyList()

            return service.windows.values.map { window ->
                LiveWindow(
                    config = window.config,
                    windowWidthPx = window.params.width,
                    windowHeightPx = window.params.height,
                    x = window.params.x,
                    y = window.params.y,
                    viewWidthPx = window.view.width,
                    viewHeightPx = window.view.height,
                )
            }
        }

        /**
         * 悬浮窗的几何诊断（调试页"复制边界数据"用）。
         *
         * ============================================================
         * 为什么需要它
         * ============================================================
         * "窗口能拖到哪"这件事涉及三份尺寸，任何一份对不上都会表现为
         * "边界不对"，而光看画面分不清是哪一份错了：
         *
         * 1. **我们算的边界**（`OverlayBounds.screenSize` → 显示器尺寸）；
         * 2. **系统允许的最大窗口区域**（`getMaximumWindowMetrics`）；
         * 3. **窗口实际落位**（`params.x/y`，系统夹过之后的值）。
         *
         * 三者一起打出来，一眼就能看出是"我们算大了"还是"系统允许更大"。
         *
         * @return 多行文本；服务未运行时返回一句说明
         */
        fun geometryReport(): String {
            val service = instance
            if (!isRunning || service == null) return "悬浮窗服务未在运行"

            val (displayWidth, displayHeight) = OverlayBounds.screenSize(service)
            val windowBounds = runCatching {
                val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.maximumWindowMetrics.bounds
            }.getOrNull()

            return buildString {
                appendLine("显示器（我们算边界用的）: ${displayWidth}×${displayHeight}")
                appendLine(
                    "系统最大窗口区域: " + (
                        windowBounds?.let {
                            "${it.width()}×${it.height()} @(${it.left},${it.top})"
                        } ?: "取不到"
                        ),
                )
                appendLine("窗口数: ${service.windows.size}")
                service.windows.values.forEach { window ->
                    val layout = OverlayLayouts.of(service, window.configId)
                    appendLine(
                        "  ${window.config.name}(${window.configId.take(8)}): " +
                            "实际=(${window.params.x},${window.params.y}) " +
                            "请求=(${layout.baseX}+${layout.offsetX},${layout.baseY}+${layout.offsetY}) " +
                            "尺寸=${window.params.width}×${window.params.height} " +
                            "可触摸=${layout.touchable}",
                    )
                }
            }
        }

        /**
         * 让正在运行的悬浮窗重新读取配置与本机设置。
         *
         * 配置改动、开关增减、悬浮窗设置修改后调用：走 onStartCommand 同步，
         * 不用重建服务（重建会让所有 Live2D 窗口重新加载模型）。
         *
         * 每个提前返回的分支都写日志 —— 否则"没反应"时完全查不出是卡在哪一步：
         * 服务没在跑、还是 startService 抛了异常、还是配置读出来不对。
         */
        fun refresh(context: Context) {
            if (!isRunning()) {
                AppLog.w(TAG, "刷新悬浮窗被跳过：服务未在运行")
                return
            }

            try {
                val intent = Intent(context, OverlayService::class.java)
                context.startService(intent)
                AppLog.d(TAG, "已请求悬浮窗刷新")
            } catch (e: Throwable) {
                // 后台启动限制等情况下系统会直接抛异常，这里兜住，别让调用方把应用搞崩
                AppLog.e(TAG, "请求悬浮窗刷新失败", e as? Exception ?: Exception(e))
            }
        }

        /** 启动悬浮窗；服务内部的失败不会抛给调用方 */
        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Throwable) {
                AppLog.e(TAG, "启动悬浮窗失败", e as? Exception ?: Exception(e))
            }
        }

        /**
         * 停止悬浮窗。
         *
         * 由"全部开关关闭"这个动作调用。同时清空开关集合 ——
         * 否则服务停下之后集合里还留着一串 id，下次开任意一个开关时
         * 会把它们**全部**一起拉起来（用户只点了一个，却弹出好几个窗口）。
         */
        fun stop(context: Context, clearEnabledIds: Boolean = false) {
            if (clearEnabledIds) {
                OverlayLayouts.setEnabledIds(context, emptySet())
            }
            try {
                context.stopService(Intent(context, OverlayService::class.java))
            } catch (e: Throwable) {
                AppLog.w(TAG, "停止悬浮窗失败：${e.javaClass.simpleName}")
            }
        }

        /** 最小尺寸常量（像素），供外部引用 */
        val minSizePx: Float = OVERLAY_MIN_SIZE_PX
    }
}

/**
 * 一个**正在显示**的悬浮窗的快照：它自己的配置 + 真实尺寸。
 *
 * ============================================================
 * 为什么要连配置一起给出来
 * ============================================================
 * 诊断"某个键被裁"时必须保证**算的与看的是同一个窗口**。
 * 之前诊断面板是自己从全部配置里挑一份来算的，而屏幕上那个窗口
 * 用的可能是别的配置 —— 于是算出"窗口该是 502 宽"，真实窗口却是 323，
 * 所有推论全部作废，白查了两轮。
 *
 * 所以配置由**窗口自己**带出来，调用方没有机会挑错。
 */
data class LiveWindow(
    val config: KeyStrokesConfig,
    val windowWidthPx: Int,
    val windowHeightPx: Int,
    val x: Int,
    val y: Int,
    val viewWidthPx: Int,
    val viewHeightPx: Int,
)

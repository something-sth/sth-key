package com.something.sthkey.capture

import com.something.sthkey.capture.shizuku.ShizukuGeteventInputSource
import com.something.sthkey.core.log.AppLog
import com.something.sthkey.core.prefs.AppPrefs
import com.something.sthkey.data.config.ConfigStore
import com.something.sthkey.domain.capture.CaptureCapabilities
import com.something.sthkey.domain.capture.KeyMode
import com.something.sthkey.domain.capture.RootProbe
import com.something.sthkey.domain.capture.ShizukuBridge
import com.something.sthkey.domain.capture.resolveCaptureMode
import com.something.sthkey.domain.config.KeyStrokesConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 采集控制器（采集层的总入口）。
 *
 * ============================================================
 * 职责
 * ============================================================
 * 1. 解析实际要用的通道（自动 / root / Shizuku）；
 * 2. 扫描设备 → 启动输入源 → 把事件归约成"按下的键集合"写进 [CaptureSession]；
 * 3. 失败时重试，退出时彻底清理（子进程 + 线程 + 按键状态）。
 *
 * ============================================================
 * 与旧项目的三个关键差别
 * ============================================================
 * 1. **不需要用户开监听、不需要选设备**：调用 [start] 即自动扫描全部设备并开始读；
 * 2. **没有"已选择的设备"这种状态**：可读设备就是全部，
 *    "选错设备导致按了没反应"这类问题从根上消失；
 * 3. **状态集中在这一层**：UI 只读 [CaptureSession]，
 *    不再像旧项目那样把监听状态散落在 Activity 的各个 remember 里。
 *
 * ============================================================
 * 线程模型
 * ============================================================
 * - 控制逻辑跑在自己的 IO 协程里（扫描、启停都是阻塞操作）；
 * - 设备读取在 [RootInputSource] 自己的线程上；
 * - 事件回调里只做"更新按下键集合"，写进线程安全的 StateFlow，
 *   **不需要 post 到主线程**（Compose 订阅时自动切回主线程）。
 */
object CaptureController {

    private const val TAG = "Capture"

    /** 启动失败后的重试间隔 */
    private const val RETRY_DELAY_MS = 3_000L

    /** 最大重试次数：su 授权弹窗可能还没点完，给一两次机会就够，避免无 root 时刷屏 */
    private const val MAX_RETRY = 2

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 由 [init] 注入，之后的启停都只依赖它 */
    private var prefs: AppPrefs? = null

    /**
     * 应用上下文。
     *
     * 用于读取当前配置 —— CPS 统计需要"键码 → 键位"的映射，而那个映射由配置决定。
     * 只在事件回调里读一次（配置对象本身很轻），不做缓存，避免配置改了却用旧映射。
     */
    private var appContext: android.content.Context? = null

    /** 当前输入源（这一版只可能是 root） */
    private var source: InputSource? = null

    /** 按键状态机（线程安全） */
    private val keyState = KeyStateManager()

    private var startJob: Job? = null

    private val _capabilities = MutableStateFlow(CaptureCapabilities.UNKNOWN)
    val capabilities: StateFlow<CaptureCapabilities> = _capabilities.asStateFlow()

    private val _enabled = MutableStateFlow(false)

    /** 用户是否希望采集开启（与界面生命周期解耦，由持久化的意图恢复） */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /**
     * 初始化（在 Application 里调用一次）。
     *
     * 除了拿到 prefs，还会**按上次的意图自动恢复采集**：
     * 用户开着悬浮窗直接进游戏时，应用可能已经被系统回收过，
     * 恢复意图能保证"开过一次就一直有效"，而不是每次都要回应用里点一下。
     */
    fun init(context: android.content.Context, prefs: AppPrefs) {
        this.appContext = context.applicationContext
        this.prefs = prefs
        val intent = prefs.captureEnabled
        AppLog.i(TAG, "采集控制器初始化，上次开关状态：$intent")
        if (intent) {
            start()
        }
    }

    /**
     * 开始采集（幂等）。
     *
     * 失败会按 [MAX_RETRY] 重试；最终失败则进入 [CaptureState.FAILED]，
     * 但**保留用户的开启意图**，下次进程启动还会再试一次。
     */
    fun start() {
        if (_enabled.value) {
            AppLog.d(TAG, "采集已开启，忽略重复启动")
            return
        }

        _enabled.value = true
        prefs?.captureEnabled = true

        startJob?.cancel()
        startJob = scope.launch {
            CaptureSession.updateState(CaptureState.STARTING, "正在准备采集…")
            AppLog.i(TAG, "开始启动采集")

            var attempt = 0
            while (attempt <= MAX_RETRY) {
                val ok = runCatching { startOnce() }
                    .onFailure { AppLog.e(TAG, "启动采集异常", it) }
                    .getOrDefault(false)

                if (ok) return@launch

                attempt++
                if (attempt > MAX_RETRY) break
                AppLog.w(TAG, "启动失败，${RETRY_DELAY_MS}ms 后重试（第 $attempt 次）")
                delay(RETRY_DELAY_MS)
            }

            AppLog.e(TAG, "采集启动失败（已重试 $MAX_RETRY 次）")
            stopInternal()
        }
    }

    /** 停止采集（用户主动关闭：清掉自动恢复的意图） */
    fun stop() {
        if (!_enabled.value && source == null) return

        _enabled.value = false
        prefs?.captureEnabled = false

        startJob?.cancel()
        startJob = null
        stopInternal()
        CaptureSession.updateState(CaptureState.IDLE, "未监听")
    }

    /**
     * 重新探测能力（调试页 / 引导页"重新检测"用）。
     *
     * 只刷新状态，不打断正在运行的采集。
     *
     * 它是**挂起**的：调用方（UI）需要知道探测什么时候真的结束，
     * 才能把"检测中"的忙碌态收回去。root 探测要跑 `su`，
     * 首次会等用户在授权弹窗上点确认，几秒钟都很正常 ——
     * 用一个写死的短延时假装探测已完成，只会让用户以为点了没反应。
     */
    suspend fun refreshCapabilities() {
        val root = RootProbe.probe()
        val shizuku = ShizukuBridge.isPermissionGrantedSafe()
        _capabilities.value = CaptureCapabilities(
            rootAvailable = root.available,
            rootDetail = root.detail,
            shizukuReady = shizuku,
        )
    }

    /*
     * ============================================================
     * 内部实现
     * ============================================================
     */

    private fun stopInternal() {
        AppLog.i(TAG, "停止采集")
        runCatching { source?.stop() }
            .onFailure { AppLog.w(TAG, "停止输入源出错：${it.javaClass.simpleName}") }
        source = null

        // 关键：必须清空按键状态，否则设备断开时会留下"卡住的键"
        keyState.clear()
        CaptureSession.clearKeys()
        CaptureSession.updateDeviceCount(0)
        CaptureSession.updateChannel(null)
        CaptureSession.updateState(CaptureState.IDLE, "未监听")
    }

    /**
     * 执行一次启动。
     *
     * @return 是否成功开始读取
     */
    private suspend fun startOnce(): Boolean {
        val prefs = this.prefs ?: run {
            AppLog.e(TAG, "尚未初始化（prefs 为空），无法启动采集")
            CaptureSession.updateState(CaptureState.FAILED, "采集未初始化")
            return false
        }

        // 1. 探测能力并解析实际通道
        val rootProbe = RootProbe.probe()
        val shizukuReady = ShizukuBridge.isPermissionGrantedSafe()
        val capabilities = CaptureCapabilities(
            rootAvailable = rootProbe.available,
            rootDetail = rootProbe.detail,
            shizukuReady = shizukuReady,
        )
        _capabilities.value = capabilities

        val resolved = resolveCaptureMode(prefs.keyMode, capabilities)
        AppLog.i(
            TAG,
            "通道解析：请求 ${prefs.keyMode.label} → 实际 ${resolved.mode.label}（${resolved.reason}）",
        )

        // 2. 按通道准备设备列表。
        //
        //    这里有个关键差别，走错就会直接失败：
        //    - root 通道：主应用自己 `su -c ls` 列设备；
        //    - Shizuku 通道：**主应用根本没有权限 ls /dev/input**，
        //      设备必须由 shell 身份的一方去扫。
        //      所以这里传空列表，让输入源自己去远端取。
        if (resolved.mode == KeyMode.SHIZUKU) {
            if (!shizukuReady) {
                CaptureSession.updateState(
                    CaptureState.FAILED,
                    "Shizuku 未授权或未运行，请到引导页 / 调试页完成授权",
                )
                return false
            }

            /*
             * 直连：Shizuku 直接起若干 `sh` 进程（一个设备一个），
             * 事件走进程 stdout 回来。
             *
             * 设备清单由输入源自己用 shell 身份去扫（主应用列不了 /dev/input），
             * 所以这里传空列表。
             */
            return startSource(ShizukuGeteventInputSource(), emptyList(), "Shizuku 直连")
        }

        val scan = InputDeviceScanner.scan(useRoot = true)
        if (!scan.success || scan.devicePaths.isEmpty()) {
            CaptureSession.updateState(CaptureState.FAILED, scan.message)
            return false
        }
        CaptureSession.updateDeviceCount(scan.devicePaths.size)

        // 3. 启动 root 输入源
        return when (resolved.mode) {
            KeyMode.ROOT -> {
                if (!rootProbe.available) {
                    CaptureSession.updateState(CaptureState.FAILED, "未检测到 root，无法读取按键")
                    return false
                }
                startSource(RootInputSource(), scan.devicePaths, "root")
            }

            // Shizuku 与 AUTO 在上面已处理；这里只是穷尽分支
            else -> {
                CaptureSession.updateState(
                    CaptureState.FAILED,
                    "采集通道解析异常：${resolved.mode.label}",
                )
                false
            }
        }
    }

    /**
     * 启动一个输入源并把状态登记进 [CaptureSession]。
     *
     * root 与 Shizuku 的差别只在"谁来读设备"，启动后的状态维护完全一样，
     * 所以这段逻辑共用 —— 以后加手柄/触屏也走这里。
     */
    private fun startSource(
        inputSource: InputSource,
        devicePaths: List<String>,
        label: String,
    ): Boolean {
        if (source?.isRunning() == true) {
            AppLog.d(TAG, "输入源已在运行")
            return true
        }

        keyState.clear()
        CaptureSession.clearKeys()

        source = inputSource
        CaptureSession.updateChannel(inputSource.channel)

        AppLog.i(TAG, "启动 $label 输入源，设备数 ${devicePaths.size}")

        inputSource.start(
            devicePaths = devicePaths,
            onEvent = ::handleEvent,
            onError = { message ->
                // 回调在读取线程上：StateFlow 线程安全，直接写即可
                AppLog.w(TAG, "$label 输入源报错：$message")
                CaptureSession.updateState(CaptureState.FAILED, message)
            },
        )

        /*
         * Shizuku 通道是**异步**的（要先起读取进程、再扫设备），
         * 此刻不可能已经就绪，所以这里不判死、也不轮询观察：
         *
         * 状态由输入源自己负责 —— 连上并开始读之后它会把状态标成 RUNNING
         * （那时设备数也已经写好），失败则通过 onError 回调上报，
         * 在这里被标成 FAILED。
         *
         * 之所以不在控制器里轮询：源自己最清楚每一步的结果，
         * 控制器再猜一遍只会出现两处状态互相覆盖的问题。
         */
        if (inputSource.channel == CaptureChannel.SHIZUKU) {
            CaptureSession.updateState(
                CaptureState.STARTING,
                "正在连接 Shizuku 服务…",
            )
            return true
        }

        if (!inputSource.isRunning()) {
            source = null
            return false
        }

        CaptureSession.updateState(
            CaptureState.RUNNING,
            "正在监听 ${devicePaths.size} 个输入设备",
        )
        AppLog.i(TAG, "$label 采集已启动：${devicePaths.size} 个设备")
        return true
    }

    /**
     * 处理一条输入事件（**运行在设备读取线程上**）。
     *
     * 这里只做状态计算，不碰任何 UI：算完写进线程安全的 StateFlow 即可。
     * 旧版这里是 `mainHandler.post { ... }`，每来一个事件切一次线程，
     * 高频输入下开销很可观 —— 现在不需要了。
     */
    private fun handleEvent(event: InputEvent) {
        when {
            event.isKey -> {
                keyState.update(event.code, event.value)

                /*
                 * 记录点击，供 CPS 统计。
                 *
                 * 按**键位**记录而不是键码：一个位置（如 LMB）可以绑任意键码，
                 * 写死 272/273 的话"用键盘键代替鼠标"的玩家 CPS 永远是 0。
                 * 具体怎么记、为什么每份配置都记，见 [recordCpsClick]。
                 *
                 * 两处都要写：
                 * - [recordCpsClick] → 每份配置自己的计数器（悬浮窗按它显示）；
                 * - [CaptureSession.cps] → 全局计数器（调试页的面板在看它）。
                 * 只写一处就会出现"某一边永远不动"。
                 */
                if (event.value == InputEvent.VALUE_DOWN) {
                    appContext?.let { context ->
                        recordCpsClick(context, event.code).forEach { slotId ->
                            CaptureSession.cps.recordClick(slotId)
                        }
                    }
                }

                CaptureSession.setPressedKeys(keyState.snapshot())
            }

            event.isRelative -> {
                /*
                 * 鼠标移动：Live2D 样式用它驱动猫的头 / 眼球 / 鼠标位置。
                 *
                 * 按键样式用不到它（所以这条分支以前是空的），但照样要收集 ——
                 * 采集层不该知道当前是哪种样式在用，否则又变成"按样式分支"。
                 */
                when (event.code) {
                    InputEvent.REL_X -> CaptureSession.addMouseMotion(event.value, 0)
                    InputEvent.REL_Y -> CaptureSession.addMouseMotion(0, event.value)
                }
            }

            event.isSync -> {
                // 一帧结束：当前按事件即时更新，不需要按帧聚合
            }
        }
    }
}

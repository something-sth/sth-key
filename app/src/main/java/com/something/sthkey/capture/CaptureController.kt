package com.something.sthkey.capture

import com.something.sthkey.capture.shizuku.ShizukuGeteventInputSource
import com.something.sthkey.capture.gamepad.GamepadProcessLauncher
import com.something.sthkey.capture.gamepad.RootGamepadLauncher
import com.something.sthkey.capture.gamepad.ShizukuGamepadLauncher
import com.something.sthkey.capture.gamepad.GamepadNativeMonitor
import com.something.sthkey.capture.shizuku.ABS_HAT0X
import com.something.sthkey.capture.shizuku.ABS_HAT0Y
import com.something.sthkey.capture.shizuku.ABS_RX
import com.something.sthkey.capture.shizuku.ABS_RY
import com.something.sthkey.capture.shizuku.ABS_X
import com.something.sthkey.capture.shizuku.ABS_Y
import com.something.sthkey.capture.shizuku.AxisNormalizer
import com.something.sthkey.capture.shizuku.AxisNames
import com.something.sthkey.capture.shizuku.AxisRange
import com.something.sthkey.core.log.AppLog
import com.something.sthkey.core.prefs.AppPrefs
import com.something.sthkey.data.config.ConfigStore
import com.something.sthkey.domain.capture.CaptureCapabilities
import com.something.sthkey.domain.capture.KeyMode
import com.something.sthkey.domain.capture.RootProbe
import com.something.sthkey.domain.capture.ShizukuBridge
import com.something.sthkey.domain.capture.resolveCaptureMode
import com.something.sthkey.domain.keys.KeyCodes
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
 * 2. 启动输入源（全局 `getevent`，设备清单由它自己枚举）→ 把事件归约成"按下的键集合"写进 [CaptureSession]；
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

    /**
     * 扳机的**按下阈值**（归一化后的 `0..1`）。
     *
     * ============================================================
     * 为什么是 0.5
     * ============================================================
     * 屏幕上的 LT/RT 是**二进制指示器**（亮 / 不亮），而扳机是连续值，
     * 必须划一条线。取一半的理由:
     *
     * - 真实手柄的扳机在**半按**处有明确的段落感，那是玩家心里的
     *   "按下去了"；
     * - 取太小（比如 0.1）会**误亮** —— 手指搭在扳机上就会触发；
     * - 取太大（比如 0.9）会**按到底才亮**，看起来像没反应。
     *
     * ⚠️ 以后如果用户想看到"按了多少"，那要做的是**扳机条组件**
     * （读 `lt` / `rt` 的连续值），而不是把这里改成比例点亮 ——
     * 二进制指示器没有"半亮"这个状态。
     */
    private const val TRIGGER_THRESHOLD = 0.5f

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

    /**
     * "哪个设备按着哪些键"的记账。
     *
     * 只为一件事服务：**设备被拔掉时精确释放它留下的按键状态**。
     * 全局监听（`getevent -lt`）下设备可能在按键还按着时被拔掉，
     * 内核不会补发 UP —— 不释放的话那个键会永久卡在按下态。
     *
     * ⚠️ 放在这里而不是各个读取源里：这是"事件 → 按键状态"的**唯一
     * 归约点**，root 与 Shizuku 两条路都在这里汇合，记账放这儿两边
     * 都能用；放进读取源就只有那一条路有，而两只手各写一份迟早不一致。
     */
    private val heldKeys = HeldKeyTracker()

    private var startJob: Job? = null

    /** 订阅输入源的设备数变化；换输入源/停止时取消 */
    private var deviceCountJob: Job? = null

    private val _capabilities = MutableStateFlow(CaptureCapabilities.UNKNOWN)
    val capabilities: StateFlow<CaptureCapabilities> = _capabilities.asStateFlow()

    private val _enabled = MutableStateFlow(false)

    /** 用户是否希望采集开启（与界面生命周期解耦，由持久化的意图恢复） */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /**
     * 初始化（在 Application 里调用一次）。
     *
     * ============================================================
     * ⚠️ 现在**无条件启动采集**
     * ============================================================
     * 原来是"按上次的意图恢复"：读 `prefs.captureEnabled`，
     * 上次开着才启动。那套逻辑是为"采集跟着悬浮窗开关走"服务的 ——
     * 关掉全部悬浮窗就停采集，所以需要记住用户上次的意图。
     *
     * **那个耦合已经拆掉了**：现在只要应用活着，采集就一直跑。
     * 于是"上次的意图"这个概念也不存在了 —— 采集不再是一个
     * 用户开关的东西，而是应用运行的一部分。
     *
     * 为什么可以一直开着：
     *
     * - **不需要重启就能识别新设备**：全局监听下 `getevent` 自己
     *   枚举设备并打印热插拔公告，所以"常驻"不再是为了热插拔；
     * - **代价很小**：一条 `getevent` 进程 + 一个读取线程，
     *   空闲时几乎不耗电（没有事件就没有回调）；
     * - **收益是"插上就能用"**：用户不必先想着"我得先把监听开起来"。
     *
     * ⚠️ 代价要说清：一直跑意味着**一直占着一条特权通道**
     * （root 的 `su` 子进程或 Shizuku 的远端进程）。
     * 这是刻意的取舍，不是疏忽。
     */
    fun init(context: android.content.Context, prefs: AppPrefs) {
        this.appContext = context.applicationContext
        this.prefs = prefs
        AppLog.i(TAG, "采集控制器初始化，自动启动采集")
        start()
    }

    /**
     * 开始采集（幂等）。
     *
     * 失败会按 [MAX_RETRY] 重试；最终失败则进入 [CaptureState.FAILED]。
     *
     * ⚠️ 失败后**不会**跨进程自动再试了。从前会把"用户想开"这个意图
     * 存进偏好、下次进程启动再试一次 —— 现在没有那个意图了（采集恒开），
     * 所以失败就停在 FAILED，靠调试页那个"重启监听"按钮手动重来，
     * 或者等用户的操作（开悬浮窗 / 点快捷方式）再触发一次 [start]。
     */
    fun start() {
        if (_enabled.value) {
            AppLog.d(TAG, "采集已开启，忽略重复启动")
            return
        }

        _enabled.value = true

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

    /**
     * 采集**实际上**在不在读事件。
     *
     * ⚠️ 与 [enabled] 的区别很重要：
     *
     * | | 含义 |
     * |---|---|
     * | [enabled] | 用户**想不想**采集（意图，会持久化） |
     * | 本方法 | 采集**真的**在跑吗（事实） |
     *
     * 两者会分叉：读取进程被系统杀掉、root / Shizuku 掉线时，
     * `enabled` 还是 true，但一个事件都读不到。
     *
     * 分叉时必须看这个 —— 只看 `enabled` 的话，"点快捷方式自愈"
     * 这类逻辑会以为一切正常，什么都不做。
     */
    fun isRunning(): Boolean = source?.isRunning() == true

    /** 停止采集（用户主动关闭：清掉自动恢复的意图） */
    fun stop() {
        if (!_enabled.value && source == null) return

        _enabled.value = false

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
        stopGamepadMonitor()
        runCatching { source?.stop() }
            .onFailure { AppLog.w(TAG, "停止输入源出错：${it.javaClass.simpleName}") }
        source = null

        /*
         * 关键：必须清空按键状态，否则设备断开时会留下"卡住的键"
         *
         * ⚠️ 这几行**以前是各自重复写了两遍**的（`source?.stop()` 也是）——
         * 那是一次改动的残留。重复本身无害（都是幂等的），但会让后来人
         * 以为是"两件不同的事"，所以清掉。
         */
        keyState.clear()
        CaptureSession.clearKeys()
        /* 手柄的轴状态也要清 —— 否则悬浮窗上会留着"最后推到的位置" */
        CaptureSession.clearSticks()
        CaptureSession.updateChannel(null)
        CaptureSession.updateState(CaptureState.IDLE, "未监听")

        /*
         * ⚠️ **扳机门闩也必须清** —— 这一条以前漏了，而它正是
         * "停止监听再重新开始，LT/RT 还是卡在按下状态"的原因:
         * 门闩留着旧值，重启后第一帧就与真实值对不上，于是再也不发事件。
         * 见 [resetTriggerState] 的注释。
         *
         * ⚠️ 必须放在**所有状态清理之后、且 monitor 已经完全停掉之后**
         * （[stopGamepadMonitor] 在最上面）。反过来的话，还没退出的读线程
         * 会在我们复位之后又写一次门闩 —— **那正好又会造出一个卡住的状态**。
         */
        resetTriggerState("停止采集")
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

        /*
         * 2. 分通道启动。
         *
         * ⚠️ **这里不再准备设备列表**。
         *
         * 老实现要按通道分别处理"谁来列 `/dev/input`"：
         * root 自己 `su -c ls`，Shizuku 则**根本没有权限 ls**
         * （主应用不是 shell 身份），得让输入源自己去远端扫。
         * 两套逻辑走错一边就直接失败。
         *
         * 现在两条通道都是全局监听（`getevent -t` 不带设备参数），
         * 设备清单由 `getevent` 自己枚举并通过公告给出 ——
         * 那层"谁来列设备"的分叉整个消失了。
         */
        if (resolved.mode == KeyMode.SHIZUKU) {
            if (!shizukuReady) {
                CaptureSession.updateState(
                    CaptureState.FAILED,
                    "Shizuku 未授权或未运行，请到引导页 / 调试页完成授权",
                )
                return false
            }
            /*
             * ============================================================
             * ⚠️ 手柄 monitor 在 Shizuku 通道**也要启动**（这里曾经漏了）
             * ============================================================
             * 用户的原话:"我现在自己用 shizuku 也用不了摇杆那些，
             * 扳机键也用不了……只能检测字母键，摇杆和其他键无反应"。
             *
             * 根因:手柄的**轴**（`EV_ABS`）只有原生 helper 提供
             * （见 [GamepadNativeMonitor] 的说明），而那个 helper
             * 以前只在 root 分支启动、且写死走 `su` —— 于是 Shizuku 用户
             * 的摇杆与扳机**永远不动**，按键却正常（那走 `getevent`）。
             *
             * ⚠️ Shizuku 的 shell 身份**本来就能读 `/dev/input`** ——
             * 证据就是下面那条 `getevent` 在无 root 时跑得好好的。
             * 所以缺的从来不是权限，只是没人用 Shizuku 去起 helper。
             *
             * ⚠️ 只换 [GamepadProcessLauncher]，**命令一字不改** ——
             * 复制到 `/data/local/tmp` + `chmod 700` + `exec`
             * 这套流程两条通道通用（`/data/local/tmp` shell 也可写）。
             */
            startGamepadMonitor(ShizukuGamepadLauncher())
            return startSource(ShizukuGeteventInputSource(), "Shizuku 直连")
        }

        /*
         * ⚠️ root 现在**不再需要预先扫设备**。
         *
         * `getevent -lt` 自己会枚举全部设备、并在热插拔时打印公告 ——
         * 那正是这次改造的目的。原来那趟 `su -c ls /dev/input/event*`
         * 不但多余，还有一个坏后果：**扫不到设备就报失败**，
         * 而"现在没有设备"和"读不了设备"是两回事 ——
         * 用户完全可能先开监听、再插手柄（这正是热插拔要支持的场景）。
         *
         * 所以只检查 root 可用性，设备清单交给 `getevent`。
         */
        return when (resolved.mode) {
            KeyMode.ROOT -> {
                if (!rootProbe.available) {
                    CaptureSession.updateState(CaptureState.FAILED, "未检测到 root，无法读取按键")
                    return false
                }
                /*
                 * ⚠️ 手柄 monitor 与 `getevent` **并行**启动。
                 *
                 * 它是独立的一条链：`getevent` 管键盘鼠标，native monitor
                 * 管手柄的摇杆与扳机。两者失败互不影响 ——
                 * 手柄起不来时键鼠仍然工作。
                 *
                 * ⚠️ Shizuku 通道在**上面**也启动了同一个 monitor，
                 * 只是换成 [ShizukuGamepadLauncher]（见那边的说明）。
                 */
                startGamepadMonitor(RootGamepadLauncher())
                startSource(RootInputSource(), "root")
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
     * root 与 Shizuku 的差别只在"谁去起那个进程"，启动后的状态维护
     * 完全一样，所以这段逻辑共用 —— 以后加手柄/触屏也走这里。
     *
     * ⚠️ **没有 `devicePaths` 参数**，这是刻意的：全局监听
     * （`getevent -t` 不带设备参数）下设备清单由 `getevent` 自己枚举
     * 并通过公告给出，外部预扫一遍不但多余，还**立刻就过时**。
     */
    private fun startSource(
        inputSource: InputSource,
        label: String,
    ): Boolean {
        if (source?.isRunning() == true) {
            AppLog.d(TAG, "输入源已在运行")
            return true
        }

        keyState.clear()
        CaptureSession.clearKeys()
        heldKeys.releaseAll()

        source = inputSource
        CaptureSession.updateChannel(inputSource.channel)

        AppLog.i(TAG, "启动 $label 输入源")

        inputSource.start(
            onEvent = ::handleEvent,
            onError = { message ->
                // 回调在读取线程上：StateFlow 线程安全，直接写即可
                AppLog.w(TAG, "$label 输入源报错：$message")
                CaptureSession.updateState(CaptureState.FAILED, message)
            },
        )

        /*
         * 设备数由**输入源自己报**（如果它支持）。
         *
         * ⚠️ 不能改成"启动前扫一遍、用那个数字"：全局监听下设备清单是
         * `getevent` 给的，而且热插拔时会变 —— 用启动那一刻的数字，
         * 用户会看到"插了手柄但界面还写着 3 个设备"。
         */
        deviceCountJob?.cancel()
        deviceCountJob = inputSource.deviceCount?.let { flow ->
            scope.launch {
                flow.collect { count -> CaptureSession.updateDeviceCount(count) }
            }
        }

        /*
         * Shizuku 通道是**异步**的（要先发 Binder 事务把远端进程起起来），
         * 此刻不可能已经就绪，所以这里不判死、也不轮询观察：
         *
         * 状态由输入源自己负责 —— 连上并读到设备公告之后它会把状态标成
         * RUNNING，失败则通过 onError 回调上报，在这里被标成 FAILED。
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

        /*
         * 全局监听下**启动那一刻还不知道有几个设备** —— 公告随后才到，
         * 条数由上面那个 deviceCount 订阅更新。所以说"正在监听"
         * 而不是"正在监听 N 个"。
         */
        CaptureSession.updateState(
            CaptureState.RUNNING,
            if (inputSource.deviceCount != null) {
                "正在监听输入设备"
            } else {
                "正在监听"
            },
        )
        AppLog.i(TAG, "$label 采集已启动（设备清单由 getevent 公告给出）")
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
            /*
             * ⚠️ 这个分支必须**排在 isKey 之前**。
             *
             * `EV_DEVICE_GONE` 是读取源发明的信号（不是内核事件），
             * 表示"某个输入设备没了"。它要在这里把**那个设备持有的键**
             * 释放掉 —— 设备可能在按键还按着时被拔掉，内核不会补发 UP，
             * 不释放的话那个键会永久卡在按下态。
             *
             * 排在前面的原因：这个事件的 `code` / `value` 没有按键语义，
             * 落进 `isKey` 分支会往 [keyState] 里写垃圾。
             */
            event.type == InputEvent.EV_DEVICE_GONE -> {
                val released = heldKeys.releaseDevice(event.device.orEmpty())
                if (released.isNotEmpty()) {
                    released.forEach { code -> keyState.update(code, InputEvent.VALUE_UP) }
                    CaptureSession.setPressedKeys(keyState.snapshot())
                    AppLog.i(TAG, "设备 ${event.device} 断开，释放了 ${released.size} 个按键状态")
                }
            }

            event.isKey -> handleKeyEvent(event)

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

    /**
     * 手柄的轴状态（**由原生 monitor 喂进来**）。
     *
     * ============================================================
     * ⚠️ 这里曾经有一整套 Kotlin 侧的轴解析，已经全部删掉
     * ============================================================
     * 删掉的是:`DeviceCapabilities` 探测（`getevent -i`）、
     * `AxisNormalizer`（中点校准 + 死区 + 归一化）、
     * `InputDeviceProbe`（启动探测 + 热插拔补探）、
     * 以及本文件里的 `handleAxisEvent` / `axisRangeFor` / 那一堆诊断日志。
     *
     * **为什么删**:那套东西解决的是"`getevent` 不告诉你轴范围"这个
     * 根本问题，而在用户设备上反复出错:
     *
     * 1. 探测时机不对 → 用"假定范围"顶着，读出来的值是错的；
     * 2. 中点校准不可靠 → **松手后拇指偏向一个角落**（反复复现）；
     * 3. 左右摇杆的轴位置不固定、按键与摇杆可能在不同节点；
     * 4. 没有 SYN 聚合 → 一次物理动作触发好几个回调，动画被反复打断。
     *
     * 原生 monitor 用 ioctl 从根上解决了这些（见 `GamepadNativeProtocol`
     * 的类注释），所以那 900 行 Kotlin **一行都不需要了**。
     *
     * ⚠️ **不要再在 Kotlin 侧加"补偿性"逻辑**（比如自己再校一次中点、
     * 再钳一次范围）—— 那会把 native 已经做对的事情弄坏，
     * 而且两个地方各改一半时没人能说清哪个是对的。
     */
    fun setGamepadStick(axis: Int, value: Float) {
        val s = CaptureSession.sticks.value
        when (axis) {
            GamepadNativeMonitor.AXIS_LEFT_X -> CaptureSession.setLeftStick(value, s.ly)
            GamepadNativeMonitor.AXIS_LEFT_Y -> CaptureSession.setLeftStick(s.lx, value)
            GamepadNativeMonitor.AXIS_RIGHT_X -> CaptureSession.setRightStick(value, s.ry)
            GamepadNativeMonitor.AXIS_RIGHT_Y -> CaptureSession.setRightStick(s.rx, value)

            /*
             * ============================================================
             * 扳机：模拟轴 → 当成按键上报
             * ============================================================
             * ⚠️ **扳机在手柄上不是按键**（这段踩过坑，见下面）。
             *
             * evdev 里:
             *
             * | 东西 | 怎么报 |
             * |---|---|
             * | LB / RB（肩键） | **按键** `BTN_TL` / `BTN_TR` |
             * | LT / RT（扳机） | **模拟轴** `ABS_Z` / `ABS_RZ`（`0..255`，或 `0..1023`） |
             *
             * 而 native 协议把它们分得更清楚:
             *
             * ```
             * GAMEPAD lx ly rx ry lt rt buttons
             *                  ↑  ↑   ↑
             *                  |  |   位掩码（位 6/7 = LB/RB）
             *                  |  右扳机 0..1000
             *                  左扳机 0..1000
             * ```
             *
             * ⚠️ 所以我们**不能**把 LT/RT 绑成 `BTN_TL` / `BTN_TR` ——
             * 那是 **LB / RB**。绑错的表现在用户那里就是:
             * "配置里写的是 LT/RT，按下去亮的却是 LB/RB"。
             *
             * ============================================================
             * 怎么把"轴"变成"按键"
             * ============================================================
             * 屏幕上的 LT/RT 是**二进制指示器**（亮 / 不亮），而扳机是连续值。
             * 用**过阈值**来判定，阈值取一半（真实手柄的"半按"位置）。
             *
             * 这样做而不是"让 UI 直接读扳机轴"的理由:
             * **渲染层只认 `pressedCodes`**。在采集层把轴折成按键，
             * 键盘样式、手柄样式、CPS 统计、设备记账就全都自动能用，
             * 不需要为扳机再开一条通道。
             */
            GamepadNativeMonitor.AXIS_TRIGGER_LEFT -> emitTrigger(
                code = KeyCodes.PSEUDO_KEY_TRIGGER_LEFT,
                value = value,
            )

            GamepadNativeMonitor.AXIS_TRIGGER_RIGHT -> emitTrigger(
                code = KeyCodes.PSEUDO_KEY_TRIGGER_RIGHT,
                value = value,
            )
        }
    }

    /**
     * 扳机过阈值时发一条"按下 / 抬起"。
     *
     * ⚠️ **只在跨越阈值时发事件**，不是每次轴变化都发 ——
     * 扳机在 `0..1000` 之间连续抖动，每次都发会让 CPS 统计飞涨。
     *
     * 状态记在 [triggerDown] 里（每个扳机一位），所以反复经过阈值
     * 只会产生成对的 down / up。
     */
    /**
     * 扳机的过阈值判定 —— **每次拿到扳机值都调**，不是"只在变化时"。
     *
     * ============================================================
     * ⚠️⚠️ 为什么改成"持续对账"而不是"边沿判定"
     * ============================================================
     * 老实现是纯粹的**边沿判定**:只在"跨越阈值"那一下发事件，
     * 状态记在 [triggerDown] 那个锁存里。
     *
     * 那种结构的致命弱点是:**锁存一旦被脏数据顶上去，就再也没有
     * 任何东西来纠正它** —— 后续的真实值每次都被判定成"没有变化"，
     * 于是一条事件都不发。用户看到的正是这个:"LT/RT 一直显示按下、
     * 按下去反而显示未按下、重启监听都没用"。
     *
     * 脏数据来自手柄**开机那一小段**:native 侧在对多轴活动做能力重探，
     * 期间会吐出一串不可信的轴值。用户的原话:"单独动哪个摇杆都不会有事，
     * **两个一起动就会复现**"。而 native 是预编译的 `.so`，那个源头
     * **我们改不了** —— 所以只能让这一侧**自愈**。
     *
     * ============================================================
     * 现在的做法:每一次都对账
     * ============================================================
     * 以**真实值**为准，把 [triggerDown] 那个锁存当成"我们上次报了什么"，
     * 两者不一致就补一条事件。于是:
     *
     * - 脏数据顶上去 → 真实值回来时**立刻**被纠正（不再依赖边沿）；
     * - 出任何异常（漏事件、顺序错乱、锁存漂了）→ **下一帧自愈**；
     * - 幂等由 [setGamepadButton] 保证，所以"对账"**不会**产生重复事件。
     *
     * ⚠️ 每帧都调不等于每帧都发事件 —— 只在**确实不一致**时才发。
     * 按住扳机不动时，`down` 与锁存一致，这里是空转。
     */
    private fun emitTrigger(code: Int, value: Float) {
        val down = value >= TRIGGER_THRESHOLD
        /* 位 0 = 左扳机，位 1 = 右扳机 */
        val bit = if (code == KeyCodes.PSEUDO_KEY_TRIGGER_LEFT) 1 else 2

        /*
         * ⚠️ 用 **CAS 循环**而不是 `get()` + `set()`。
         *
         * 这个锁存有两个写入者:本函数（monitor 的读线程）与
         * [resetTriggerState]（"手柄就绪"回调 —— 可能是**另一次会话的新线程**，
         * 旧线程还没退干净时两边会重叠）。非原子的 get-then-set 会让
         * "读到的旧值"与"写回时的实际值"不一致，那**正好又会造出一个
         * 卡住的锁存** —— 也就是刚修掉的那个 bug。
         */
        while (true) {
            val wasDown = triggerDown.get()
            if (down == (wasDown and bit != 0)) return
            val next = if (down) wasDown or bit else wasDown and bit.inv()
            if (triggerDown.compareAndSet(wasDown, next)) {
                setGamepadButton(code, down)
                return
            }
        }
    }

    /** 扳机的"按下"状态，位 0 = 左、位 1 = 右 */
    private val triggerDown = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * 把两个扳机的门闩**清零**，并抬起还留在 `pressedCodes` 里的扳机键码。
     *
     * ============================================================
     * ⚠️⚠️ 为什么必须有这个函数（这里漏了，导致一个很难描述的真机 bug）
     * ============================================================
     * 用户的原话:"我插上拓展坞后，手柄还没启动，然后我手动按开关启动手柄，
     * 会有一小段时间开机，这一段时间，如果我着急动了摇杆，就会导致手柄开机
     * 之后，悬浮窗上 LT 与 RT 被**一直识别成按下状态**……按 LT、RT 也不能恢复，
     * 而是一种相反的状态……重启监听都没用，只能划掉后台才能恢复"。
     *
     * 成因是一条**没有出口的锁存**:
     *
     * 1. 手柄开机的那一小段时间会连续上报**抖动的轴值**（所以"着急动摇杆"
     *    才会触发 —— 那段窗口里轴数据本来就在乱跳）；
     * 2. 扳机是**过阈值**判定（见 [emitTrigger]），抖动值跨过阈值就把
     *    [triggerDown] 的那一位**永久置上**；
     * 3. [triggerDown] **以前从来没有任何地方重置**。于是手柄真正就绪、
     *    真实值回到 0 时，`down(false) == wasDown(已置位 → true)` 成立 →
     *    [emitTrigger] **直接 return，一条事件都不发**；
     * 4. `pressedCodes` 里那两位就一直留着 → LT/RT 永远显示按下；
     * 5. **"重启监听"救不回来**：[triggerDown] 是采集控制器的字段，
     *    [stopInternal] 只清了 `keyState` 与摇杆，**没清它**；
     * 6. **"按下显示未按下、松开显示按下"** 正是"锁存位卡住"的典型表现:
     *    状态机与真实状态差了一次翻转之后，每一条事件都被反向解读。
     *
     * ============================================================
     * 为什么在"手柄就绪"时调用（而不是加一个手动按钮）
     * ============================================================
     * 手柄**每次**就绪都是一个干净的起点 —— 那一刻逻辑上没有任何扳机是按下的。
     * 清掉门闩之后，紧随其后的第一条 `GAMEPAD` 行会带上**真实**的扳机值，
     * 于是 `down != wasDown` 成立，状态**自然就同步到真值**了
     * （按着就亮、没按就不亮），不需要我们猜。
     *
     * ⚠️ 顺带把 `pressedCodes` 里的扳机键码也抬起 —— 否则"手柄开机时扳机
     * 恰好是脏值、之后又没再上报"的情况下，那两个键码会一直卡在按下集合里。
     *
     * ⚠️ 幂等:本来就没按下时什么都不做（不发多余事件）。
     */
    private fun resetTriggerState(reason: String) {
        if (triggerDown.getAndSet(0) == 0) return

        AppLog.i(TAG, "复位扳机状态（$reason）")
        setGamepadButton(KeyCodes.PSEUDO_KEY_TRIGGER_LEFT, down = false)
        setGamepadButton(KeyCodes.PSEUDO_KEY_TRIGGER_RIGHT, down = false)
    }

    /**
     * 手柄按键（**由原生 monitor 喂进来**）。
     *
     * ⚠️ 走的是**与键盘完全相同**的那条路（[handleKeyEvent]）——
     * 这样 CPS 统计、设备记账、按下动画全都不用为手柄写第二份。
     *
     * 这也意味着 `pressedCodes` 里同时装着键盘键码与手柄键码，
     * 而消费方（按键组件 / 手柄组件）按**键码**取用，不需要知道来源。
     *
     * ============================================================
     * ⚠️⚠️ **幂等**:状态没变就直接返回，绝不发重复事件
     * ============================================================
     * 这不是优化，是**修一个真机 bug 的必要条件**。
     *
     * [handleKeyEvent] 会把这个键记进 [heldKeys]，而 [HeldKeyTracker]
     * 是**按引用计数**的:
     *
     * ```
     * DOWN → byDevice[device] = 计数 + 1
     * UP   → byDevice[device] = 计数 - 1，归零才真正释放
     * ```
     *
     * 所以**重复发同一条 DOWN 会把计数顶上去，而一个 UP 只还原 1** ——
     * 计数再也回不到 0，这个键**从此不会被释放**。
     *
     * 用户遇到的就是这个（原话）:"手柄开机之后，悬浮窗上 LT 与 RT 被
     * **一直识别成按下状态**……重启监听都没用，只能划掉后台才能恢复"
     * （`stopInternal` 会 `heldKeys.releaseAll()` 并重建，所以划掉后台能好）。
     *
     * ⚠️ 重复 DOWN 至少有三个来源，**都堵在这里最省事**:
     * 1. 手柄开机那一小段上报**抖动的扳机值**，反复跨越阈值 →
     *    [emitTrigger] 每次都发一条 DOWN；
     * 2. [resetTriggerState] 发的"抬起"刚好落在真实状态仍是按下的时候 →
     *    下一次真实状态又发一条 DOWN；
     * 3. 摇杆/按键与扳机共用 `device = null`（空串）这一个计数器，
     *    互相之间也会把计数垫高。
     *
     * ⚠️ 读 [keyState] 判断而不是自己再维护一份"上次发过什么":
     * 那份影子状态迟早会与 `keyState` / `heldKeys` 漂开，而**漂开就是
     * 又一个永久卡键**。`keyState` 本身就是那个唯一真源。
     */
    fun setGamepadButton(code: Int, down: Boolean) {
        /*
         * ⚠️ `keyState.snapshot()` 每次都要复制整个集合。
         * 手柄按键的频率（摇杆每帧一条 `GAMEPAD` 行，但按键只在变化时才调这里）
         * 远低于键盘，所以这点开销可以忽略 —— 换来的是"永远不会重复发"。
         */
        val alreadyDown = code in keyState.snapshot()
        if (alreadyDown == down) return

        handleKeyEvent(
            InputEvent(
                type = InputEvent.EV_KEY,
                code = code,
                value = if (down) InputEvent.VALUE_DOWN else InputEvent.VALUE_UP,
                /*
                 * ⚠️ 设备路径传 null。
                 *
                 * 手柄按键不由 `getevent` 那条路送来，没有 evdev 节点路径；
                 * 而 [heldKeys] 的"设备拔出时释放按键"依赖路径。
                 *
                 * 传 null 的后果是:手柄按键会被记在"路径未知"名下，
                 * 于是**任何设备拔出都会释放手柄的按键** —— 那正是我们
                 * 想要的行为（手柄拔了，它按着的键就该灭）。
                 */
                device = null,
            ),
        )
    }

    /** 手柄断开:清掉它留下的轴状态（按键由 [GamepadNativeMonitor] 自己发 UP） */
    fun clearGamepadAxes() {
        CaptureSession.clearSticks()
    }

    /* ============================================================
     * 原生手柄 monitor 的启停
     * ============================================================ */

    @Volatile
    private var gamepadMonitor: GamepadNativeMonitor? = null

    /**
     * 启动原生手柄 monitor。
     *
     * ============================================================
     * ⚠️ 它是**独立于 `getevent` 那条路**的第二个输入源
     * ============================================================
     * | 来源 | 负责 |
     * |---|---|
     * | `getevent`（[RootInputSource]） | 键盘、鼠标 |
     * | 本 monitor（native） | **手柄的摇杆、扳机、按键** |
     *
     * 两条同时跑，各管一半。**不合并成一条**是因为手柄需要的
     * 轴能力查询（`ioctl EVIOCGABS`）是 `getevent` 给不了的 ——
     * 硬要合并就得回到那套"探测 + 假定范围"的老路（踩过的坑）。
     *
     * ⚠️ **失败不算采集失败**：手柄起不来时键盘鼠标仍然工作，
     * 所以这里只记日志、不改 `CaptureState`。用户看到的是
     * "键鼠正常、手柄没反应"，而不是整个采集红掉。
     *
     * ============================================================
     * ⚠️ [launcher] 决定"谁去起 helper" —— 两条通道都要调它
     * ============================================================
     * | 通道 | launcher | 起进程的方式 |
     * |---|---|---|
     * | root | [RootGamepadLauncher] | `su -c` |
     * | Shizuku | [ShizukuGamepadLauncher] | 远端 `sh -c`（复用 [ShizukuShell]） |
     *
     * ⚠️ 这里**必须传参、不能有默认值**:有默认值的话，将来新增一条通道时
     * 会静默地用了错误的 launcher（手柄没反应，但看不出哪里错了）。
     */
    private fun startGamepadMonitor(launcher: GamepadProcessLauncher) {
        if (gamepadMonitor?.isRunning() == true) return

        val context = appContext ?: return

        val monitor = GamepadNativeMonitor(
            context = context,
            launcher = launcher,
            onReady = { detail ->
                AppLog.i(TAG, "手柄已连接：$detail")
                /*
                 * ⚠️ 手柄每次就绪都重新同步扳机状态 —— 这是那个"LT/RT 卡在
                 * 按下状态、重启监听也救不回来"的 bug 的修复点。
                 * 完整成因见 [resetTriggerState] 的注释。
                 */
                resetTriggerState("手柄就绪")
            },
            onDisconnected = {
                /* 断开时把轴归零，避免摇杆停在最后的位置 */
                clearGamepadAxes()
            },
            onButton = ::setGamepadButton,
            onAxis = ::setGamepadStick,
        )

        val error = monitor.start()
        if (error != null) {
            AppLog.w(TAG, "手柄 monitor 启动失败（${launcher.label}）：$error")
            return
        }
        gamepadMonitor = monitor
    }

    /** 停掉手柄 monitor。**幂等** —— [stopInternal] 可能被反复调用 */
    private fun stopGamepadMonitor() {
        gamepadMonitor?.let { monitor ->
            runCatching { monitor.stop() }
                .onFailure { AppLog.w(TAG, "停止手柄 monitor 出错：${it.javaClass.simpleName}") }
        }
        gamepadMonitor = null
    }

    /** 按键分支（原来在 `handleEvent` 里，被抽出来是因为那段已经太长） */
    private fun handleKeyEvent(event: InputEvent) {
        keyState.update(event.code, event.value)

        /*
         * 记账：这个键由哪个设备按着。
         *
         * 只为"设备拔出时能精确释放"服务 —— 见上面那个分支。
         * `device` 为 null 时记在空串名下，效果是"路径未知的设备"，
         * 那种情况下拔任何设备都会释放全部键（保守但安全）。
         */
        heldKeys.onKey(
            code = event.code,
            pressed = event.value != InputEvent.VALUE_UP,
            device = event.device,
        )

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
}

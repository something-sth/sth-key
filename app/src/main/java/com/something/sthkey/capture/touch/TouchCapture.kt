package com.something.sthkey.capture.touch

import android.content.Context
import com.something.sthkey.capture.gamepad.GamepadProcessLauncher
import com.something.sthkey.core.log.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 触屏采集的**入口**（当前只有一个宿主，将来是采集层的一部分）。
 *
 * ============================================================
 * ⚠️ 为什么要一个单例，而不是把 monitor 塞进调试页
 * ============================================================
 * 调试页会**离开组合**（用户切走、转屏），而触屏采集是**长期运行**的东西。
 * 把 monitor 交给某个 Composable 的 `DisposableEffect` 管，
 * 一离开页面就被销毁 —— 而真机上要验证的东西恰恰是
 * "它能不能一直稳稳地跑着"。
 *
 * ⚠️ 所以生命周期归这里，调试页只当一个**观察窗口**（读 [state]）。
 *
 * ============================================================
 * ⚠️ 通道（root / Shizuku）**由调用方决定**，这里不自己判
 * ============================================================
 * 与手柄同规则 —— 见 `CaptureController` 里 `startGamepadMonitor(Root…)`
 * 与 `startGamepadMonitor(Shizuku…)` 那两处:它按**解析后的 `KeyMode`**
 * 决定用哪条通道。
 *
 * ⚠️ 一开始我在这里写了个"root 可用就 root、否则 Shizuku"的判断 ——
 * **那是错的**:会出现"采集走 Shizuku、触屏走 root"这种用户解释不了的
 * 状态（他明明选的 Shizuku）。而且 `RootGamepadLauncher.isAvailable()`
 * 那种方法**根本不存在**（launcher 只负责起进程，不负责探测可用性）——
 * 可用性判断归 `CaptureController`（它有 `rootProbe` 与 Shizuku 状态）。
 *
 * ⚠️ 所以 [start] 要求调用方**把 launcher 传进来**，与手柄完全一致。
 *
 * ⚠️ 本类是 `internal` 的:[GamepadProcessLauncher] 就是 `internal`
 * （它只该在采集层内用），公开的话编译器会拒绝暴露那个参数类型。
 * 界面层与它在同一个模块里，照常能用。
 */
internal object TouchCapture {

    private const val TAG = "TouchCapture"

    /**
     * 当前状态 —— 给界面看的。
     *
     * ⚠️ 用 `StateFlow` 而不是回调:调试页与将来的编辑器都要读它，
     * 而"谁先订阅"不该影响结果（回调会漏掉订阅之前发生的事）。
     */
    data class State(
        /** 采集是否在运行（含"正在重试"） */
        val running: Boolean = false,
        /** 设备是否就绪（native 报过 `touch-ready`） */
        val ready: Boolean = false,
        /** 给人看的一句话状态 */
        val status: String = "未启动",
        /** 就绪时的设备描述（`event1 | 名字 | MT+SLOT+DIRECT`）—— 挑错设备时靠它 */
        val device: String = "",
        /** 最近一帧的触点（已归一化到 0..1） */
        val points: List<TouchNativeProtocol.Point> = emptyList(),
        /**
         * 帧序号 —— 每收到一帧就 +1。
         *
         * ============================================================
         * ⚠️⚠️ 为什么需要一个"没别的用"的计数器
         * ============================================================
         * Compose 只在**读到的 State 变了**时才重组。而触屏的摇杆状态
         * 是**算出来的**（`TouchFrameTracker`），不是存在 State 里的 ——
         * 所以没有这个计数器时:
         *
         * | 情况 | 表现 |
         * |---|---|
         * | 手指按住不动 | `points` 不变 → **不重组** → 摇杆停在原处不动（看起来正常） |
         * | 手指滑动中 | `points` 每帧都变 → 重组 → 正常 |
         *
         * ⚠️ 真正会出问题的是**松手之后**:视角摇杆按设计**不回中**，
         * 而"松手"这一帧之后 `points` 就一直是空列表了 ——
         * 于是悬浮窗上那个摇杆**停在最后一次画出来的位置**，
         * 而不是"当前的真实值"（两者恰好一样，所以看不出来；
         * 但只要有人改一下配置、或换一块区域，画出来的就是**旧的**）。
         *
         * ⚠️ 更明显的是**按键区域**:按下 A 时 `points` 变（重组、A 亮），
         * 手指不动地按着 —— 那时没有新帧也就没有重组，看起来正常；
         * 但如果两次「按下同一块区域」之间的坐标完全相同
         * （`frameSame` 会把它判成重复帧而**不推送**），
         * 那么"抬起再按下"就**不会让 A 重新亮起来**。
         *
         * ⚠️ 加了这个计数器之后，每一帧都会引起一次重组，
         * 于是摇杆与按键状态**永远与真实值一致**。
         * 代价是每帧一次重组 —— 而帧率本来就只有触摸事件的频率
         * （远低于屏幕刷新率），可以忽略。
         */
        val frameSeq: Long = 0L,
        /** 启动失败的原因（`null` = 没失败） */
        val startError: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var monitor: TouchNativeMonitor? = null

    /** 最近一帧 —— 给"只想要当前触点"的调用方（不必订阅 flow） */
    @Volatile
    var latestPoints: List<TouchNativeProtocol.Point> = emptyList()
        private set

    fun isRunning(): Boolean = monitor?.isRunning() == true

    /**
     * 启动采集。
     *
     * @param launcher 用哪条通道起 helper。⚠️ **必须由调用方决定**
     *   （见类注释）—— 与手柄的 `startGamepadMonitor(launcher)` 同一个约定。
     * @return 启动失败的原因；成功返回 `null`
     */
    fun start(context: Context, launcher: GamepadProcessLauncher): String? {
        if (isRunning()) return null

        val app = context.applicationContext

        val created = TouchNativeMonitor(
            context = app,
            launcher = launcher,
            onReady = { detail ->
                _state.value = _state.value.copy(
                    ready = true,
                    device = detail,
                    status = "触屏已连接 · $detail",
                )
            },
            onDisconnected = {
                /*
                 * ⚠️ 断开时把触点**清空** —— 不清的话悬浮窗会留着
                 * "最后一次触摸的位置"，看起来像卡住了。
                 */
                latestPoints = emptyList()
                _state.value = _state.value.copy(
                    ready = false,
                    points = emptyList(),
                    /*
                     * ⚠️ 状态文案**不在这里覆盖** —— native 那边紧接着会报
                     * `waiting-touch` 或 `touch-disconnected`，
                     * 由 [onStatus] 统一更新。这里写死会让两条信息打架。
                     */
                )
            },
            onFrame = { frame ->
                latestPoints = frame.points
                _state.value = _state.value.copy(
                    points = frame.points,
                    /* ⚠️ 每帧都 +1，让消费方**每帧都重组** —— 见 [State.frameSeq] */
                    frameSeq = _state.value.frameSeq + 1,
                )
            },
            onStatus = { text ->
                _state.value = _state.value.copy(status = text)
            },
        )

        val error = created.start()
        if (error != null) {
            _state.value = _state.value.copy(running = false, startError = error, status = error)
            return error
        }

        monitor = created
        _state.value = _state.value.copy(running = true, startError = null)
        return null
    }

    fun stop() {
        monitor?.stop()
        monitor = null
        latestPoints = emptyList()
        _state.value = State(status = "已停止")
    }
}

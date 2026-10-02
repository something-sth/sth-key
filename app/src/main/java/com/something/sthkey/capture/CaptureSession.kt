package com.something.sthkey.capture

import com.something.sthkey.data.config.ConfigStore
import com.something.sthkey.domain.overlay.OverlayLayouts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 鼠标位移：**自本次采集开始以来**的净累计量（dx / dy）。
 *
 * ============================================================
 * 为什么要减掉"读到的第一个值"（这里是踩过坑的地方）
 * ============================================================
 * 采集层维护的累计值是**从进程启动**开始算的，而消费方（悬浮窗）可能是
 * 后来才出现的（多悬浮窗下新开一个窗口就是这种情形）。
 * 如果直接把"从启动到现在的总量"交给它，它算出来的第一帧位移就是
 * 那个巨大的总量 —— 表现是**猫的鼠标瞬间冲到画布边缘**。
 *
 * 原来的做法是让消费方自己记一个"基准"再相减，但那要求消费方维护
 * 「基准 + 已转发量 + 累计总量」三个变量始终保持一致，
 * 任何一个漂了，表现都是"鼠标朝一个方向顶到底、之后再也不动"
 * 这种极难定位的现象。
 *
 * 现在改成在**采集层**就减好：创建这个对象时就记下当前值作为零点，
 * 于是消费方拿到的第一个值必然是 0，之后每个值都是"相对它出现那一刻的净位移"。
 * 消费方只剩一个减法要做。
 *
 * @param dx 自本对象创建以来的净 X 位移（像素）
 * @param dy 自本对象创建以来的净 Y 位移（像素）
 */
data class MouseMotion(val dx: Long = 0L, val dy: Long = 0L) {
    val isZero: Boolean get() = dx == 0L && dy == 0L
}

/**
 * 采集会话状态（采集层与 UI 的唯一交汇点）。
 *
 * ============================================================
 * 为什么要有这一层
 * ============================================================
 * - 采集线程（root 读取线程）只管把"当前按下的键"写进来；
 * - 悬浮窗与主页只管订阅它并渲染 / 展示。
 *
 * 两边互不认识：以后接 Shizuku、手柄、触屏时，
 * 只要有人往这里写状态，悬浮窗一行都不用改。
 *
 * ============================================================
 * 线程安全
 * ============================================================
 * [MutableStateFlow] 本身是线程安全的，可以从任意线程写入；
 * Compose 侧用 `collectAsState()` 订阅，会自动切回主线程。
 * 因此采集线程**不需要** post 到主线程，直接写即可 ——
 * 这是相较旧项目（到处 `mainHandler.post`）刻意简化的一点。
 */
object CaptureSession {

    private val _pressedKeys = MutableStateFlow<Set<Int>>(emptySet())

    /** 当前按下的输入键码集合 */
    val pressedKeys: StateFlow<Set<Int>> = _pressedKeys.asStateFlow()

    /** 采集状态（未监听 / 启动中 / 监听中 / 失败） */
    private val _state = MutableStateFlow(CaptureState.IDLE)
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    /** 当前使用的采集通道 */
    private val _channel = MutableStateFlow<CaptureChannel?>(null)
    val channel: StateFlow<CaptureChannel?> = _channel.asStateFlow()

    /** 正在监听的设备数 */
    private val _deviceCount = MutableStateFlow(0)
    val deviceCount: StateFlow<Int> = _deviceCount.asStateFlow()

    /** 最近一次失败 / 提示信息，展示在主页工作状态里 */
    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message.asStateFlow()

    /**
     * CPS 统计器。
     *
     * 放在会话里而不是采集控制器里：它记录的是**输入事件**层面的东西，
     * 而显示（悬浮窗）与采集都可能用到，属于共享状态。
     */
    val cps = CpsCounter()

    /**
     * 鼠标位移的**原始累计量**：自进程启动以来的总和，永不重置。
     *
     * ============================================================
     * 为什么保留它，而不是直接对外发增量
     * ============================================================
     * 鼠标能报到 1000Hz，如果每来一个事件就对外发一个值，订阅方会被刷爆；
     * 而消费方本来就**按帧**取用。所以这里只维护一个"总和"，
     * 消费方按需[开一个自己的窗口][openMouseMotion]去差分 ——
     * 中间丢多少帧都不会丢位移（丢帧时差分会得到一个更大的增量，位置仍然正确）。
     *
     * 为什么**不重置**（包括停止采集时）：消费方记的是"已经转发到哪了"，
     * 一旦重置就会出现一个巨大的反向位移，表现为猫的头或鼠标突然跳到另一个角。
     * 它是 Long，累计到天荒地老也不会溢出。
     */
    private val _mouseTotal = MutableStateFlow(MouseMotion())

    /**
     * 开一个鼠标位移窗口：拿到的是**从这一刻起**的净位移。
     *
     * 每个悬浮窗各开一个（多窗口下互不干扰），见 [MouseMotion] 的说明。
     * 必须在主线程调用（消费方是随窗口创建而建的）。
     *
     * ⚠️ 这个窗口**不重置也不回收**：它的位置是"零点"，而零点必须一直有效 ——
     * 一旦被回收重用，新窗口就会拿到一个巨大的首帧位移，又回到那个坑里。
     * 一个 MouseMotion 对象几十字节，每个窗口一个可以忽略。
     */
    fun openMouseMotion(): StateFlow<MouseMotion> {
        val total = _mouseTotal.value
        val window = MutableStateFlow(MouseMotion())
        windows += MouseMotionWindow(window, total.dx, total.dy)
        return window.asStateFlow()
    }

    /** 一个位移窗口：只记"创建时的原始累计量"作为零点 */
    private class MouseMotionWindow(
        val flow: MutableStateFlow<MouseMotion>,
        val zeroDx: Long,
        val zeroDy: Long,
    )

    private val windows = mutableListOf<MouseMotionWindow>()

    /*
     * ============================================================
     * 写入口（只有采集层会调用）
     * ============================================================
     */

    /**
     * 累加一次鼠标相对位移（**运行在设备读取线程上**）。
     *
     * 只有**相对**位移（EV_REL）：触屏/数位板那种绝对坐标是另一回事，
     * 它们要的是 pointerRatio 而不是 delta，等真接上再单独加。
     */
    fun addMouseMotion(dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return

        /*
         * 没有消费窗口时直接不累加。
         *
         * 只有 Live2D 样式会订阅这条流；按键样式下没人要这个值，
         * 而鼠标能报到 1000Hz —— 白写 1000 次/秒（还附带对象分配）没有意义。
         * 跳过写入也不会造成位移跳变：零点记的是"创建那一刻的原始总量"，
         * 而原始总量在停止累加期间也不会动。
         */
        if (windows.isEmpty()) return

        val current = _mouseTotal.value
        val next = MouseMotion(current.dx + dx, current.dy + dy)
        _mouseTotal.value = next

        // 扇出给每个窗口；窗口拿到的是"相对它自己零点"的净位移
        windows.forEach { window ->
            window.flow.value = MouseMotion(next.dx - window.zeroDx, next.dy - window.zeroDy)
        }
    }

    /**
     * 记一次 CPS 点击（**按配置隔离**）。
     *
     * ============================================================
     * 为什么不能只用一个全局计数器（这里踩过坑）
     * ============================================================
     * 计数是按**键位 id** 记的，而"键位 id 属于哪份配置"是配置自己的事：
     * 三份配置都可能有一条 `LMB` 映射。用同一个计数器的话，
     * 一次鼠标点击会被记三次 —— 用户看到的是**CPS 变成三倍**。
     *
     * 所以每个配置 id 一个计数器：记录时按该配置的键位映射查槽位，
     * 显示时取该配置自己的计数器。互不干扰。
     *
     * 顺带说明为什么记录时**不再遍历所有配置**：那是上一个版本的写法，
     * 每个键按几份配置就记几次，同样是重复计数。
     *
     * @param configId 这份点击属于哪份配置（就是键位映射的归属）
     */
    fun recordCpsClick(configId: String, slotId: String) {
        if (slotId.isBlank()) return
        synchronized(counters) { counters.getOrPut(configId) { CpsCounter() } }
            .recordClick(slotId)
    }

    /** 某个配置的 CPS 数值快照；没有记录时返回空表（调用方按 0 处理） */
    fun cpsSnapshotOf(configId: String): Map<String, Int> =
        synchronized(counters) { counters[configId] }?.snapshot() ?: emptyMap()

    /** 清空某个配置的 CPS（改键位映射后旧计数就失去意义了） */
    fun clearCpsOf(configId: String) {
        synchronized(counters) { counters[configId] }?.clear()
    }

    /** 当前有计数的配置 id；调试页的自诊断用 */
    fun cpsConfigIds(): Set<String> = synchronized(counters) { counters.keys.toSet() }

    private val counters = mutableMapOf<String, CpsCounter>()

    /**
     * 更新按下的键集合。
     *
     * 只有内容真的变化才发新值：按键重复（value=2）等情况会很频繁，
     * 无脑写入会让悬浮窗无谓重组。
     */
    fun setPressedKeys(codes: Set<Int>) {
        if (_pressedKeys.value == codes) return
        _pressedKeys.value = codes
    }

    /** 清空按键状态。停止采集、切换配置时必须调用，避免留下"卡住的键" */
    fun clearKeys() {
        cps.clear()
        if (_pressedKeys.value.isEmpty()) return
        _pressedKeys.value = emptySet()
    }

    fun updateState(state: CaptureState, message: String = "") {
        _state.value = state
        if (message.isNotEmpty() || state != CaptureState.RUNNING) {
            _message.value = message
        }
    }

    fun updateChannel(channel: CaptureChannel?) {
        _channel.value = channel
    }

    fun updateDeviceCount(count: Int) {
        _deviceCount.value = count
    }

    /*
     * ============================================================
     * 调试用
     * ============================================================
     */

    /**
     * 模拟一个按键事件（调试页用）。
     *
     * ============================================================
     * 它**同时算一次 CPS 点击**
     * ============================================================
     * 否则调试页按得再欢，CPS 也永远是 0 —— 而调试页的意义就是"不接真键盘也能看效果"，
     * 用户会以为是 CPS 坏了。计数走与真实输入**同一条路径**
     * （见 [recordCpsClick]），所以两边的口径不可能不一致。
     */
    fun simulate(code: Int, pressed: Boolean, context: android.content.Context? = null) {
        val current = _pressedKeys.value
        if (pressed && code !in current) {
            context?.let { ctx ->
                recordCpsClick(ctx, code).forEach { slotId ->
                    cps.recordClick(slotId)
                }
            }
        }
        _pressedKeys.value = if (pressed) current + code else current - code
    }
}

/**
 * 记一次 CPS 点击。
 *
 * ============================================================
 * 按"每一份**开着悬浮窗**的配置"各记一次
 * ============================================================
 * 计数是按键位记的、且按配置隔离（见 [CaptureSession.recordCpsClick]），
 * 所以一份输入事件要落到它影响的那些配置上：
 *
 * - **开着悬浮窗的配置**：屏幕上真正在显示的那些 —— CPS 就是给它们看的；
 * - 一份都没开时退回**第一份配置**，保证调试页手点的时候也有数。
 *
 * ⚠️ 这里**只遍历这些配置，不是全部配置**。早先的写法是"所有配置都记一遍"，
 * 结果同一份键位映射存在于几份配置里时就被记几次 —— 用户看到的 CPS 直接翻倍。
 * 那是个真实 bug，不要再改回去。
 *
 * 放在这个文件里而不是 [CaptureController] 内部：调试页的模拟按键
 * 也要能计数，两处必须**走同一条路径**，否则口径迟早分叉。
 *
 * @return 记下了哪些槽位。调用方（采集层）需要它来同步维护
 *   [CaptureSession.cps] 那个全局计数器 —— 面板/调试页还在用它。
 */
fun recordCpsClick(context: android.content.Context, code: Int): List<String> {
    val store = ConfigStore.get(context)
    val enabledIds = OverlayLayouts.enabledIds(context)

    val targets = store.all().filter { it.id in enabledIds }.ifEmpty { listOf(store.first()) }

    val recorded = mutableListOf<String>()
    targets.forEach { config ->
        CpsCounter.slotOf(config, code)?.let { slotId ->
            CaptureSession.recordCpsClick(config.id, slotId)
            recorded += slotId
        }
    }
    return recorded
}

package com.something.sthkey.capture.accessibility

import android.view.KeyEvent
import com.something.sthkey.capture.CaptureChannel
import com.something.sthkey.capture.InputEvent
import com.something.sthkey.capture.InputSource
import com.something.sthkey.core.log.AppLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * **纯无障碍**采集通道 —— 靠 [android.accessibilityservice.AccessibilityService.onKeyEvent] 读按键。
 *
 * ============================================================
 * ⚠️⚠️ 它只能读键盘（这条必须记住，不要指望它读鼠标/手柄/触摸）
 * ============================================================
 * | 能 | 不能 |
 * |---|---|
 * | ✅ 物理键盘按键、组合键、多键同时按 | ❌ 鼠标（移动**和**左右中键） |
 * | | ❌ 手柄摇杆 / 扳机 / 按键 |
 * | | ❌ 触摸 |
 *
 * ⚠️ 鼠标读不到的原因**不是**"没做",而是**做不到**:
 * 鼠标按键是 `MotionEvent`（`ACTION_BUTTON_PRESS`），不在 `onKeyEvent` 里；
 * 想收它得用 `onMotionEvent`，而那条路**登记即吞**
 * （官方文档:来自 `getMotionEventSources()` 的事件不会被发送到系统其余部分）,
 * 一个只读的按键显示工具把鼠标吃掉是不可接受的。
 * 详细论证见 `docs/accessibility-mode.md` §2.3。
 *
 * ============================================================
 * ⚠️⚠️ 为什么它是 [InputSource] 的一个实现，而不是"另一套东西"
 * ============================================================
 * 这个接口**早就在了**（root 与 Shizuku 都是它的实现）。
 * 于是:
 *
 * ```
 * InputSource
 *  ├─ RootInputSource              getevent
 *  ├─ ShizukuGeteventInputSource   getevent（借 Shizuku）
 *  └─ AccessibilityInputSource     onKeyEvent        ← 本类
 * ```
 *
 * ⭐ **上层一行都不用改** —— 按键状态机、CPS、悬浮窗、配置全都只认
 * [InputEvent]，不关心它是从哪来的。
 */
internal class AccessibilityInputSource(
    /**
     * 无障碍服务现在是否连着。
     *
     * ⚠️ 传成 lambda 而不是直接读 [SthKeyAccessibilityService.isConnected]:
     * 启动那一刻服务可能还没连上，而"连没连"是**会变的**（用户随时可能关掉）。
     * 每次判断时现读，才不会拿着一个过期的快照做决定。
     */
    private val isConnected: () -> Boolean = { SthKeyAccessibilityService.isConnected() },
) : InputSource {

    override val channel: CaptureChannel = CaptureChannel.ACCESSIBILITY

    /**
     * ⚠️ **不实现设备数** —— 返回 `null`。
     *
     * 无障碍**没有"设备"这个概念**:它拿到的是系统派发下来的 `KeyEvent`，
     * 不知道也不关心这个键来自哪块键盘。
     *
     * ⚠️ 所以主页状态卡在无障碍模式下**不能**显示"N 个输入设备"——
     * 那会是一个永远 _0_ 的假数字（见 `HomeScreen` 里那段分支）。
     */
    override val deviceCount: kotlinx.coroutines.flow.StateFlow<Int>? = null

    /** ⚠️ 也不上报设备能力（绝对轴范围）—— 我们拿不到轴，只有键 */
    override val capabilities: Map<String, com.something.sthkey.capture.shizuku.DeviceCapabilities>
        get() = emptyMap()

    private val running = AtomicBoolean(false)

    /**
     * 已经打过日志的"无法映射"键码。
     *
     * ⚠️ 只用来**避免刷屏**:按住音量键一秒会触发几十次回调，
     * 每次都打一行日志会让真正的线索被埋掉。
     * ⚠️ 每个不同的键码各打一次，所以排查时仍然能看到"哪些键被丢了"。
     */
    private val unmappedCodes = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    /** 记下来，[stop] 时用它摘回调（必须摘，见下面的说明） */
    private var previousCallback: ((Int, Boolean) -> Unit)? = null

    override fun start(
        onEvent: (InputEvent) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!isConnected()) {
            onError("无障碍服务未开启，请在系统设置 → 无障碍里启用")
            return
        }

        /*
         * ⚠️⚠️ **必须覆盖同一个回调，不能在 stop 里"清空"**
         * ============================================================
         * 用 `onKeyEventCallback = ...` 而不是"追加一个监听器"，
         * 是因为两条通道**绝不允许同时上报**:
         *
         * | 情况 | 后果 |
         * |---|---|
         * | 无障碍 + getevent 同时上报同一个键 | ⚠️ CPS **翻倍**，键位状态也会错乱 |
         *
         * ⚠️ 所以 [CaptureController] 那边是**二选一**挂回调的
         * （见它那条 `if (channel == ACCESSIBILITY)`），
         * 而这里用覆盖赋值保证"只有一个消费者"。
         */
        previousCallback = SthKeyAccessibilityService.onKeyEventCallback
        running.set(true)

        SthKeyAccessibilityService.onKeyEventCallback = callback@ { keyCode, pressed ->
            /* ⚠️ 停用之后**不再上报** —— 否则停用的那一瞬间还会漏出去几个事件 */
            if (!running.get()) return@callback

            /*
             * ⚠️⚠️ **必须转换键码**（我第一版漏了，用户实测"按键对应错误"）
             * ============================================================
             * Android 的 `KeyEvent.KEYCODE_A` 是 **29**，而 evdev 的 `KEY_A` 是 **30**。
             * 直接透传的话:
             *
             * | 用户按 | 上报成 evdev | 被当成 |
             * |---|---|---|
             * | A | 29 | ⚠️ Ctrl 左 |
             * | S | 47 | ⚠️ V |
             * | Space | 62 | ⚠️ F4 |
             *
             * ⚠️ 转换表在 [AccessibilityKeyCode]（照 Axon 抄的）。
             */
            val evdevCode = AccessibilityKeyCode.toEvdev(keyCode)

            /*
             * ⚠️ 认不出的键**直接丢掉**，不要上报 `-1` ——
             * evdev 里没有 -1 这个键，报上去只会在悬浮窗上显示一个
             * 没人认识的怪东西（而且键位映射表里也找不到它）。
             *
             * ⚠️ 哪些会走到这里:音量键、返回、HOME、媒体键、输入法特殊键。
             * 它们本来就不是"键盘按键"，系统还会优先消费掉。
             */
            if (evdevCode == AccessibilityKeyCode.UNKNOWN) {
                /* ⚠️ 只打一次日志（每个不同的键各一次），否则按住音量键会刷屏 */
                if (unmappedCodes.add(keyCode)) {
                    AppLog.d(TAG, "无障碍收到无法映射的键码 ${keyCode}（会上报为不显示）")
                }
                return@callback
            }

            onEvent(
                InputEvent(
                    type = InputEvent.EV_KEY,
                    code = evdevCode,
                    value = if (pressed) InputEvent.VALUE_DOWN else InputEvent.VALUE_UP,
                    /*
                     * ⚠️ 设备未知（传 null）。
                     *
                     * ⚠️ 这会不会影响"拔设备时只清那个设备的键"那条逻辑?
                     * 不会 —— 那是 `getevent` 全局监听才有的问题
                     * （拔设备时内核不补发 UP）。无障碍是**系统派发**的，
                     * 服务断开时系统会正常结束事件流，不存在"卡住"。
                     */
                    device = null,
                ),
            )
        }

        AppLog.i(TAG, "无障碍采集已启动（只支持键盘按键）")
    }

    override fun stop() {
        /*
         * ⚠️⚠️ **摘掉回调**（而不是留着它、让它自己判 running）
         * ============================================================
         * 留着的话，服务即使"停用"了，每按一个键仍会走一遍 lambda ——
         * 那是白耗电，也让"到底谁在收事件"变得难以排查。
         *
         * ⚠️ 只在**当前回调还是自己挂的那个**时才还原，不要把别人
         * 后来挂的覆盖掉（虽然现在只有一个消费者，但这个习惯能防以后出错）。
         */
        running.set(false)
        SthKeyAccessibilityService.onKeyEventCallback = previousCallback
        previousCallback = null
        AppLog.i(TAG, "无障碍采集已停止")
    }

    override fun isRunning(): Boolean = running.get()

    private companion object {
        const val TAG = "AccessibilityInput"
    }
}

/**
 * `KeyEvent.keyCode` 与 Linux `KEY_*` 是同一套编号 —— 这条**断言**值得单测钉住。
 *
 * ⚠️ 为什么单独写出来:[AccessibilityInputSource] 里"直接把 keyCode 当
 * [InputEvent.code] 用"是全功能能不能跑通的关键假设。若哪天有人
 * "顺手加个映射"，键位就会整体错位，而症状（"按 A 亮的是别的键"）
 * 会让人往键位映射表那边查，很难联想到这一层。
 */
internal object AccessibilityKeyCodeMap {
    /** 一个按键码是否被我们当作"物理键盘的键"来处理 */
    fun isKeyboardKey(keyCode: Int): Boolean =
        keyCode != KeyEvent.KEYCODE_UNKNOWN
}

package com.something.sthkey.capture

/**
 * 一条原始输入事件（来自 /dev/input/event*）。
 *
 * 这是采集层的"内部货币"：所有输入源（root / 以后的 Shizuku、手柄）
 * 都产出这个结构，上层的按键状态机只认它。
 *
 * 字段直接对应 Linux `struct input_event`：
 * type / code / value 三个 16 位与 32 位整数，
 * 因此命名保持 type、code、value，不做"面向业务"的包装 —— 包装会丢信息。
 */
data class InputEvent(
    /** 事件类型：EV_KEY / EV_REL / EV_ABS / EV_SYN */
    val type: Int,
    /** 事件码：按键码（KEY_* / BTN_*）或轴码（REL_X / REL_Y） */
    val code: Int,
    /** 值：按键为 0 抬起 / 1 按下 / 2 重复；轴为位移量 */
    val value: Int,
    /**
     * 这个事件来自哪个输入设备（`/dev/input/event3`）；不知道时为 null。
     *
     * ============================================================
     * ⚠️ 为什么必须传到这一层
     * ============================================================
     * 全局监听（`getevent -lt`）下，一个设备可能在**按键还按着**的时候
     * 被拔掉 —— 那种情况内核**不会**补发 UP 事件。不处理的话那个键会
     * **永久卡在按下态**，悬浮窗上一直亮着，而用户完全不知道为什么。
     *
     * 正确处理是"只清掉这个设备持有的键"。要做到这点就必须知道
     * "每个按下的键来自哪个设备"，所以设备路径得一路传上来。
     *
     * ⚠️ 不能退化成"拔设备就清空全部按键"：另一个设备上还按着的键
     * 会被误清 —— 表现为"动了动鼠标，键盘上按着的键突然灭了"。
     *
     * 单设备模式（root 的二进制路径）拿不到设备路径，传 null 即可 ——
     * 那种模式下每个进程只管一个设备，本来就按设备隔离了。
     */
    val device: String? = null,
) {
    /** 是否为按键类事件 */
    val isKey: Boolean get() = type == EV_KEY

    /** 是否为相对轴事件（鼠标移动） */
    val isRelative: Boolean get() = type == EV_REL

    /** 是否同步事件（一帧结束） */
    val isSync: Boolean get() = type == EV_SYN

    companion object {
        const val EV_SYN = 0x00
        const val EV_KEY = 0x01
        const val EV_REL = 0x02
        const val EV_ABS = 0x03

        const val VALUE_UP = 0
        const val VALUE_DOWN = 1
        const val VALUE_REPEAT = 2

        const val REL_X = 0
        const val REL_Y = 1

        /**
         * **不是内核事件**：读取源用它表示"某个输入设备没了"。
         *
         * 取一个内核不会用到的类型值（内核的 `EV_*` 目前到 `0x1f`），
         * 于是它绝不会与真实事件混淆。
         *
         * ⚠️ 携带方式：`device` 字段是被拔掉的设备路径（**可能为空串**，
         * 某些 ROM 的公告里没有路径）；`code` / `value` 无意义。
         *
         * 为什么需要这样一个信号：全局监听下设备可能在按键还按着时被拔掉，
         * 内核**不会**补发 UP —— 上层要据此只清掉那个设备的按键状态。
         */
        const val EV_DEVICE_GONE = 0x7F
    }
}

/**
 * 把 `getevent` 解析出来的事件转成 [InputEvent]。
 *
 * ⚠️ **设备路径要一起带上去**：全局监听下设备可能在被按下时拔掉
 * （内核不会补发 UP），读取源要靠这个字段"只清掉那个设备持有的键"。
 * 见 [InputEvent.device] 的说明。
 *
 * 放在这里而不是 `shizuku` 包里：**root 与 Shizuku 两条读取路径都要用**，
 * 而它依赖的 [InputEvent] 就在本文件。
 */
internal fun com.something.sthkey.capture.shizuku.ParsedLine.Event.toInputEvent(): InputEvent =
    InputEvent(type = type, code = code, value = value, device = device)

/**
 * 采集器运行状态。
 *
 * 用于主页"工作状态"展示：用户需要一眼看出"到底有没有在读按键"，
 * 而不是只看到一个开关。
 */
enum class CaptureState(val label: String) {
    /** 未启动 */
    IDLE("未监听"),

    /** 正在建立读取通道（起进程 / 等设备公告） */
    STARTING("启动中"),

    /** 正常读取中 */
    RUNNING("监听中"),

    /** 启动失败（没有 root、没有设备、读取报错…） */
    FAILED("监听失败"),
}

/**
 * 采集通道类型。
 *
 * 这一版只实现 [ROOT]；[SHIZUKU] 与 [GAMEPAD]、[TOUCH] 是预留位 ——
 * 新增通道 = 新增一个 [InputSource] 实现，上层不需要改。
 */
enum class CaptureChannel(val label: String) {
    ROOT("Root"),
    SHIZUKU("Shizuku"),
    GAMEPAD("手柄"),
    TOUCH("触屏"),
}

/**
 * 输入源接口。
 *
 * 抽这一层的目的：把"怎么拿到输入"和"拿到之后怎么用"彻底分开。
 * - root：spawn `su -c cat /dev/input/eventX` 再解析二进制
 * - shizuku：借 Shizuku 的 shell 身份起读取进程，事件经进程输出回传
 * - 手柄 / 触屏：各自的事件来源完全不同
 *
 * 三者的**输出**都必须是 [InputEvent]，于是按键状态机、
 * 悬浮窗、配置全都不需要知道输入是从哪来的。
 */
interface InputSource {
    /** 通道类型，用于日志与状态展示 */
    val channel: CaptureChannel

    /**
     * 当前正在监听的设备数；不支持上报时返回 null。
     *
     * ============================================================
     * 为什么让输入源自己报，而不是外部预先扫一遍
     * ============================================================
     * 全局监听（`getevent -lt`）下**设备清单是 `getevent` 给的**，
     * 而且在热插拔时会变 —— 外部预先扫出来的数字会立刻过时，
     * 用户看到的是"插了手柄但界面还写着 3 个设备"。
     *
     * ⚠️ 用"可空 + 可选实现"而不是要求所有源都支持：
     * 后面的手柄 / 触屏通道可能压根没有"设备数"这个概念，
     * 强迫它们实现一个只会返回 0 的属性是噪音。
     */
    val deviceCount: kotlinx.coroutines.flow.StateFlow<Int>?
        get() = null

    /**
     * 本会话的设备能力（每个绝对轴的取值范围）。
     *
     * 摇杆归一化要用它把裸 ADC 值换算成 `-1..1` —— 而 `getevent`
     * **不告诉你范围**（`getevent -t` 只打印裸值），所以读取源要先用
     * `getevent -i` 探一次。见 `InputDeviceProbe`。
     *
     * ⚠️ 默认返回**空表**而不是强制每个实现都写：不能探测的输入源
     * （以后的手柄 / 触屏通道）返回空表即可，消费方会回落到假定范围。
     * 强迫每个实现写一个 `= emptyMap()` 是噪音。
     */
    val capabilities: Map<String, com.something.sthkey.capture.shizuku.DeviceCapabilities>
        get() = emptyMap()

    /**
     * 启动采集。
     *
     * 实现必须是**非阻塞**的：内部起自己的线程读设备，
     * 事件通过 [onEvent] 回调送出来。
     *
     * ⚠️ **没有 `devicePaths` 参数**，这是刻意的：全局监听（`getevent -t`
     * 不带设备参数）下设备清单由 `getevent` 自己枚举并通过公告给出，
     * 外部预扫一遍不但多余，还**立刻就过时**（它不反映热插拔）。
     *
     * @param onEvent     事件回调。**注意：回调发生在读取线程上**，
     *                    实现方不得在其中直接操作 UI。
     * @param onError     错误回调（同样是后台线程）
     */
    fun start(
        onEvent: (InputEvent) -> Unit,
        onError: (String) -> Unit,
    )

    /** 停止采集并释放资源；必须幂等（重复调用不能抛异常） */
    fun stop()

    /** 是否正在运行 */
    fun isRunning(): Boolean
}

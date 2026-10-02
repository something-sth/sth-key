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
    }
}

/**
 * 采集器运行状态。
 *
 * 用于主页"工作状态"展示：用户需要一眼看出"到底有没有在读按键"，
 * 而不是只看到一个开关。
 */
enum class CaptureState(val label: String) {
    /** 未启动 */
    IDLE("未监听"),

    /** 正在扫描设备 / 启动读取线程 */
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
     * 启动采集。
     *
     * 实现必须是**非阻塞**的：内部起自己的线程读设备，
     * 事件通过 [onEvent] 回调送出来。
     *
     * @param devicePaths 要监听的设备节点路径（由 [InputDeviceScanner] 提供）
     * @param onEvent     事件回调。**注意：回调发生在读取线程上**，
     *                    实现方不得在其中直接操作 UI。
     * @param onError     错误回调（同样是后台线程）
     */
    fun start(
        devicePaths: List<String>,
        onEvent: (InputEvent) -> Unit,
        onError: (String) -> Unit,
    )

    /** 停止采集并释放资源；必须幂等（重复调用不能抛异常） */
    fun stop()

    /** 是否正在运行 */
    fun isRunning(): Boolean
}

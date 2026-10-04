package com.something.sthkey.capture.shizuku

import com.something.sthkey.capture.InputEvent
import com.something.sthkey.capture.toInputEvent
import com.something.sthkey.core.log.AppLog
import java.io.BufferedReader

/**
 * `getevent` 文本流的**共用消费逻辑**。
 *
 * ============================================================
 * 为什么要有这一层
 * ============================================================
 * root 与 Shizuku 两条通道**跑的是同一个命令**（`getevent -t`，全局监听），
 * 差别只有一件事：**谁去起那个进程**。
 *
 * ```
 * root：   ProcessBuilder("su", "-c", COMMAND)
 * Shizuku： ShizukuShell.start(COMMAND)
 *                    │
 *                    ▼  都是"一个能按行读的文本流"
 *            GeteventStream   ← 本类：解析 + 公告 + 计数 + 拔出信号
 * ```
 *
 * ⚠️ 抽出来的**动机**不是"少写几行"，而是**行为必须一致**：
 *
 * - "设备拔出要发 `EV_DEVICE_GONE`"这种约定，两边各写一份就迟早不一致；
 * - 上一版就这么吃过亏 —— root 改成了全局监听，Shizuku 还是旧的多进程，
 *   于是同一个功能在两条通道上表现不同，而排查时根本想不到"通道之间不一样"。
 *
 * ⚠️ 本类**不做**的事（刻意留给调用方）：
 * - 起进程 / 关进程（两条通道完全不同）；
 * - 决定"流结束了算不算失败"（root 是进程死了，Shizuku 还可能是远端 binder 断了）；
 * - 重试与退避（策略不同）。
 */
internal class GeteventStream(
    /** 读到的行来源；返回 null = 流结束 */
    private val reader: BufferedReader,
    /** 日志标签，两条通道各自区分（`RootInput` / `ShizukuGetevent`） */
    private val tag: String,
    /** 还在运行吗；false 时尽快退出循环 */
    private val isRunning: () -> Boolean,
    private val onEvent: (InputEvent) -> Unit,
    /** 设备数变化时回调（**只在变化时**调） */
    private val onDeviceCount: (Int) -> Unit,
    /**
     * 事件过滤器：返回 false 的事件**在最内层就被丢掉**。
     *
     * ⚠️ 抽这一层是因为**过滤必须发生在源头**，不能放到上层去：
     *
     * - 一台触摸屏每秒能产生**几百个**轴事件(`ABS_MT_*` 那一族加上
     *   `ABS_X`/`ABS_Y`，多点触控时会成倍增加)；
     * - 它们在采集层只是被忽略，**但跨线程回调、字典查找、
     *   日志判定都已经做完了** —— 白烧 CPU，还会把调试日志刷爆。
     *
     * 默认不过滤(null),让调用方显式决定。
     */
    private val accept: ((InputEvent) -> Boolean)? = null,
    /**
     * 新设备接入时回调（**在读取线程上**，必须立刻返回）。
     *
     * ⚠️ 调用方**不能**在这里做耗时的事（比如跑 `getevent -i` 补探）——
     * 那会把读取循环卡住，用户会看到"按了键没反应"。
     * 正确做法是丢给后台线程，见 `RootInputSource` / `ShizukuGeteventInputSource`
     * 里的 `probeNewDevice`。
     *
     * ⚠️ **只对"这台 stream 第一次见到"的设备触发**。
     *
     * 但注意：*这台 stream first seen* ≠ *这个进程 first seen*。
     * 每次重新启动采集都会新建一个 stream，于是启动时那批设备又会被
     * 当成"新的"再探一遍 —— 实测日志里就是"每次重启采集，8 台设备
     * 全部重探一遍"。
     *
     * 所以调用方还要自己再挡一道：见 [onlyNewDevices]。
     */
    private val onDeviceAttached: ((String) -> Unit)? = null,
    /**
     * 判断"这台设备还值不值得补探"。
     *
     * ⚠️ 传 `Set` 不行 —— 集合是**每次枚举时现取**的
     * （调用方在补探完成后才往里加），传值就永远看到空集。
     */
    private val onlyNewDevices: ((String) -> Boolean)? = null,
) {

    /** 本次会话见过的设备；拔出时按它清理 */
    private val devices = linkedSetOf<String>()

    /** 已经上报过的设备数，用来"只在变化时回调" */
    private var reportedCount = 0

    private var lineCount = 0L
    private var eventCount = 0L

    /** 通过过滤、真正上报上去的事件数(与 eventCount 的差额就是被丢掉的) */
    private var acceptedCount = 0L

    /**
     * 一直读到流结束。
     *
     * ⚠️ 这是**阻塞**调用，必须在后台线程上跑。
     *
     * @return 是否读到了任何一行。用来区分"流完全没数据"（多半是
     *   命令没跑起来 / 参数不认）与"正常读了一段然后结束"
     */
    fun run(): Boolean {
        /* 前几行原样记进日志，见 [PROBE_LOG_LINES] 的说明 */
        while (isRunning()) {
            val line = try {
                reader.readLine()
            } catch (e: Exception) {
                /*
                 * 主动 stop() 时这里必然抛（流被关掉了）—— 那不是错误。
                 * 判断依据是 isRunning，而不是异常类型：不同 ROM 上
                 * 关闭管道抛的异常类型并不一致。
                 */
                if (isRunning()) {
                    AppLog.w(tag, "读取中断：${e.javaClass.simpleName}: ${e.message}")
                }
                break
            } ?: break

            lineCount++

            /*
             * ⚠️ 前几行**原样记进日志**。
             *
             * `getevent` 的输出格式在不同 ROM / toybox 版本上并不完全一致，
             * 而且它自己出问题（参数不认、设备打不开）时也是**打几行文本
             * 就退出**。真机上出问题时，这几行是唯一能说明
             * "它到底打印了什么"的东西 —— 没有它就只能靠猜。
             */
            if (lineCount <= PROBE_LOG_LINES) {
                AppLog.i(tag, "getevent[$lineCount] ${line.take(MAX_LOG_LINE)}")
            }

            handleLine(line)
        }

        /*
         * ⚠️ 统计里要分开"收到"与"上报"。
         *
         * 两者的差额就是被过滤掉的(触摸屏那一大堆轴事件) ——
         * 只看总量的话,分不清"手柄没上报"与"被过滤掉了",
         * 而这两种情况的排查方向完全相反。
         */
        AppLog.i(
            tag,
            "读取循环结束：共 $lineCount 行、收到 $eventCount 个事件、" +
                "上报 $acceptedCount 个(过滤掉 ${eventCount - acceptedCount} 个)、" +
                "见过 ${devices.size} 个设备",
        )
        return lineCount > 0
    }

    /** 当前见过的设备数 */
    fun deviceCount(): Int = devices.size

    /** 处理一行 */
    private fun handleLine(line: String) {
        when (val parsed = GeteventParser.parse(line)) {
            is ParsedLine.Event -> {
                eventCount++
                val converted = parsed.toInputEvent()

                /*
                 * ⚠️ 过滤放在**最靠内的位置**：不过滤的话触摸屏那几百个
                 * 事件会走完整个回调链才在上层被忽略，白烧 CPU 还刷爆日志。
                 */
                if (accept == null || accept(converted)) {
                    acceptedCount++
                    onEvent(converted)
                }
            }

            is ParsedLine.Attached -> {
                val isNew = parsed.device.isNotEmpty() && parsed.device !in devices
                devices += parsed.device
                AppLog.i(
                    tag,
                    "设备接入：${parsed.device.ifEmpty { DEVICE_UNKNOWN }}（当前 ${devices.size} 个）",
                )
                notifyCount()

                /*
                 * ⚠️ 两重判定，缺一不可：
                 *
                 * 1. `isNew` —— 这台 stream 第一次见到它。
                 *    `getevent` 启动时会把当时所有设备都公告一遍，
                 *    那批设备启动前的探测**已经覆盖过了**。
                 * 2. `onlyNewDevices` —— **这个进程**第一次见到它。
                 *    采集重启会新建一个 stream，第 1 条判定会失效，
                 *    于是全部设备被重探一遍（实测日志里就是这样）。
                 */
                val worthProbing = isNew && (onlyNewDevices?.invoke(parsed.device) ?: true)
                if (worthProbing) onDeviceAttached?.invoke(parsed.device)
            }

            is ParsedLine.Detached -> {
                devices -= parsed.device
                AppLog.i(
                    tag,
                    "设备拔出：${parsed.device.ifEmpty { DEVICE_UNKNOWN }}（剩 ${devices.size} 个）",
                )
                notifyCount()

                /*
                 * ⚠️ 发"设备没了"的信号。
                 *
                 * 物理设备可能在**按键还按着**的时候被拔掉 —— 那种情况
                 * 内核**不会**补发 UP 事件。不处理的话那个键会**永久卡在
                 * 按下态**：悬浮窗上一直亮着，用户完全不知道为什么。
                 *
                 * ⚠️ 这里**不自己算该释放哪些键** —— 那件事由
                 * `CaptureController` 用 `HeldKeyTracker` 做，因为那里才是
                 * "事件 → 按键状态"的唯一归约点。本类只负责发信号，
                 * 两条通道于是自动共享同一套释放逻辑。
                 */
                onEvent(
                    InputEvent(
                        type = InputEvent.EV_DEVICE_GONE,
                        code = 0,
                        value = 0,
                        device = parsed.device,
                    ),
                )
            }

            null -> Unit
        }
    }

    /** 只在设备数**变化**时回调，避免热插拔时刷一堆重复值 */
    private fun notifyCount() {
        if (devices.size != reportedCount) {
            reportedCount = devices.size
            onDeviceCount(reportedCount)
        }
    }

    internal companion object {
        /**
         * 要执行的命令。**两条通道共用这一个常量**。
         *
         * ============================================================
         * ⚠️ 为什么不让 root / Shizuku 各自写一份
         * ============================================================
         * 它们跑的是**同一条命令** —— root 用 `su -c` 起它、
         * Shizuku 用 Binder 让服务端起它，差别只在"谁起进程"。
         *
         * 分开写死两份的后果：改了一边忘了另一边 → 出现
         * "root 能热插拔、Shizuku 不能"这种分叉，而且极难查
         * （两条通道看起来都"正常"，只是行为不同）。
         *
         * ============================================================
         * ⚠️ 两个不能动的点
         * ============================================================
         * **不带 device 参数** = 监听全部设备，这是热插拔的前提。
         * 传一个设备就变成单设备模式，`getevent` 不再打印设备公告。
         *
         * **绝对不要加 `-l`**：它把事件行从十六进制换成符号名
         * （`EV_KEY KEY_W DOWN`），而解析器只认十六进制 ——
         * 于是**每个真实按键都被丢掉、但设备公告不受影响**，
         * 表现成"热插拔正常、设备数会更新，但悬浮窗毫无反应"。
         * 详见 `docs/input-capture.md` 第 2 节。
         */
        const val COMMAND = "/system/bin/getevent -t"

        /**
         * 启动时原样记进日志的行数。
         *
         * 30 行足够覆盖"设备公告 + 前几个事件" —— 那正好是验证
         * 输出格式是否与解析器一致所需的最小样本。
         */
        const val PROBE_LOG_LINES = 30

        /** 单行日志截断长度，防止异常长行把日志刷爆 */
        const val MAX_LOG_LINE = 200

        /** 公告里取不到设备路径时的日志占位 */
        const val DEVICE_UNKNOWN = "(路径未知)"

        /**
         * 事件过滤器：**丢掉"直接输入设备"**（触摸屏、手写笔）的事件。
         *
         * ============================================================
         * ⚠️ 为什么必须挡在源头
         * ============================================================
         * 触摸屏**也报 `ABS_X` / `ABS_Y`**（实测是 `0..20352` / `0..44800`
         * 的屏幕坐标），而且多点触控时每秒产生几百个事件。
         *
         * 放它们上来有两层后果：
         *
         * 1. **手指碰屏幕会让悬浮窗上的摇杆偏到一边**
         *    （屏幕中间 ≈ 归一化 0.5），而用户完全想不到是"摸屏幕"引起的；
         * 2. 日志被刷爆，真出问题时看不到有用的东西。
         *
         * 判据是 `INPUT_PROP_DIRECT`，由启动时的 `getevent -i` 探测给出。
         *
         * ⚠️ **探测不到的设备一律放行**（返回 true）：热插拔进来的手柄
         * 不在能力表里，拦掉的话它整台都没反应。
         * 触摸屏是开机就有的，所以"未知 = 放行"是安全的。
         *
         * ============================================================
         * ⚠️ 参数是**取值函数**，不是能力表本身
         * ============================================================
         * 传 `Map` 的话调用方在**构造过滤器的那一刻**就把值读走了 ——
         * 而 root 那边构造过滤器与跑探测的先后顺序取决于字段初始化顺序，
         * 一不小心就抓到一个**空表**，过滤器于是永远放行，
         * 而表现是"触摸屏还是会刷屏"，很难联想到是求值时机。
         *
         * 传函数则每次过滤都现取，与顺序无关。
         */
        fun deviceFilter(
            capabilities: () -> Map<String, DeviceCapabilities>,
        ): (InputEvent) -> Boolean = { event ->
            capabilities()[event.device]?.direct != true
        }
    }
}

package com.something.sthkey.capture

import com.something.sthkey.capture.shizuku.DeviceCapabilities
import com.something.sthkey.capture.shizuku.GeteventStream
import com.something.sthkey.capture.shizuku.InputDeviceProbe
import com.something.sthkey.core.log.AppLog
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * root 输入源。
 *
 * ============================================================
 * 实现方式（**已改为全局 `getevent -lt`**）
 * ============================================================
 * ```
 * su -c "/system/bin/getevent -lt"
 * ```
 *
 * 一条命令监听**全部**输入设备，并从它的输出里读设备增删公告 ——
 * 于是**插上手柄就能立刻用，不需要重启监听**。
 *
 * ============================================================
 * ⚠️ 从"每设备一个 su + 二进制解析"改过来，为什么
 * ============================================================
 * 原来是这样：
 *
 * ```
 * su -c "cat /dev/input/event3"    ← 每个设备一条
 * su -c "cat /dev/input/event5"
 * …
 * ```
 *
 * 由专用线程按固定长度读 `struct input_event` 二进制。它**能用**，
 * 但有个硬伤：**设备清单在启动那一刻就定死了**。
 * 插上手柄不会开始工作，用户必须去点"重启监听"——
 * 而他不会知道要这么做，只会觉得"手柄没反应"。
 *
 * 改成 `getevent -lt` 之后：
 * - `getevent` 自己枚举设备，并在热插拔时打印 `add device` / `remove device`；
 * - 我们只要**解析这些公告**就知道设备什么时候来了、什么时候走了；
 * - 一条进程代替 N 条（`ls /dev/input` 那次扫描也省了）。
 *
 * ⚠️ 代价：**单点失败**。一个进程死了全部停。所以 [readLoop] 必须
 * 能把"进程退出"如实报上去，让上层的重试机制接手。
 *
 * ⚠️ 为什么不用 `FileInputStream` 直接读 `/dev/input/event*`：
 * 普通应用进程即使用 root 也没有权限 open 那些设备节点（SELinux 限制），
 * 必须让 `su` 派生的 shell 去读。这也是"绕一层 shell"的原因。
 *
 * ============================================================
 * 线程模型
 * ============================================================
 * - 只有**一个**后台读取线程（原来是一设备一个）；
 * - `readLine()` 是阻塞的，所以停止时必须**先杀子进程**再中断线程 ——
 *   不杀进程的话线程醒不过来。
 *
 * @param (已移除) 全局模式下没有 devicePaths 参数，见接口注释。
 *
 *   保留这个参数只是为了不让 [InputSource] 的签名分叉 ——
 *   Shizuku 那边也把它忽略了（它自己用 shell 扫）。
 *   全局模式下设备清单由 `getevent` 自己给出，不需要外部先扫一遍。
 */
class RootInputSource : InputSource {

    override val channel: CaptureChannel = CaptureChannel.ROOT

    /**
     * 当前见过的设备数。
     *
     * 由 `getevent` 的 `add device` / `remove device` 公告驱动 ——
     * **这是外部预先扫一遍做不到的**：热插拔之后那个数字立刻就过时了。
     */
    private val _deviceCount = kotlinx.coroutines.flow.MutableStateFlow(0)
    override val deviceCount: kotlinx.coroutines.flow.StateFlow<Int> = _deviceCount

    private val running = AtomicBoolean(false)

    @Volatile
    private var thread: Thread? = null

    @Volatile
    private var process: Process? = null

    /** 设备能力探测进程(只在启动时用一次,用完立刻收掉) */
    @Volatile
    private var probeProcess: Process? = null

    /**
     * 本次会话的设备能力(每个绝对轴的取值范围)。
     *
     * 读取线程**先探测、后开监听**,所以监听期间它是只读的。
     * 摇杆归一化要用它把裸 ADC 值换算成 `-1..1`
     * (见 `AxisNormalizer` 与 `InputDeviceProbe` 的注释)。
     *
     * ⚠️ 探测失败时是空表 —— 那时摇杆回落到"假定 16 位对称范围",
     * **不是崩掉**。拿不到轴范围只是幅度不准,而采集本身还该工作。
     */
    @Volatile
    override var capabilities: Map<String, DeviceCapabilities> = emptyMap()
        private set

    override fun isRunning(): Boolean = running.get()

    override fun start(
        onEvent: (InputEvent) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!running.compareAndSet(false, true)) {
            AppLog.w(TAG, "已经在运行，忽略重复启动")
            return
        }

        AppLog.i(TAG, "启动 root 采集（全局监听）：${GeteventStream.COMMAND}")

        thread = Thread({ readLoop(onEvent, onError) }, "RootInput-getevent").also {
            it.isDaemon = true
            it.start()
        }
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) {
            releaseProcess()
            return
        }

        AppLog.i(TAG, "停止 root 采集")
        releaseProbe()
        releaseProcess()
    }
    /** 收掉探测进程(它可能不退出,留着会一直占着一个 su) */
    private fun releaseProbe() {
        try {
            probeProcess?.destroy()
        } catch (_: Exception) {
        }
        probeProcess = null
    }

    /** 正在补探的设备路径,防止同一个设备被重复探 */
    private val probing = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * **本进程**已经补探过的设备路径。
     *
     * ⚠️ 它与 [GeteventStream] 内部那个 `devices` 集合**不是一回事**:
     * 那个是"这台 stream 见过谁",每次采集重启都会新建一个 ——
     * 于是启动时那批设备又会被当成"新的"再探一遍。
     *
     * **实测日志里就是这样**:每次重启采集,8 台设备全部重探一遍,
     * 白白多起 8 条 `su` 进程。
     *
     * 这个集合跨 stream 存活,所以补探**每台设备一辈子只做一次**。
     */
    private val probedDevices = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * 新设备接入时**在后台**补探它的轴范围。
     *
     * ============================================================
     * ⚠️ 必须异步
     * ============================================================
     * 这个回调发生在**读取线程**上 —— 同步跑 `getevent -i` 会把读取循环
     * 卡住几百毫秒，用户看到的是"插上手柄之后按键有一秒没反应"。
     *
     * ============================================================
     * ⚠️ 只探"第一次见到的设备"
     * ============================================================
     * `getevent` 启动时会把当时所有设备都公告一遍，那些设备启动前的
     * 探测已经覆盖过了。重复探每台多一条进程，没有收益。
     * 判据在 [GeteventStream] 里（`isNew`）。
     *
     * ============================================================
     * 为什么用"合并"而不是"整体替换"
     * ============================================================
     * 补探只拿到**一台**设备的信息。整体替换会把启动时探到的其它设备
     * 全丢掉 —— 表现是"插一次手柄，触摸屏过滤和别的设备都不认了"。
     */
    private fun probeNewDevice(devicePath: String) {
        if (devicePath.isEmpty()) return
        if (!probing.add(devicePath)) return

        /*
         * 先记下来。补探**已经排上队了**，之后采集重启不该再排一次 ——
         * 放在这里而不是"探完再记"，是为了让"同一台设备被连续公告两次"
         * （有些设备插入时会先出一个再出一个子节点）也只探一次。
         */
        probedDevices += devicePath

        Thread({
            try {
                val caps = probeForDevice(devicePath)
                if (caps != null) {
                    /*
                     * ⚠️ 合并而不是替换。整体替换会丢掉启动时探到的其它设备。
                     *
                     * `capabilities` 是 `@Volatile` 的 `Map` 字段，
                     * 整体赋值是原子的 —— 读侧（事件过滤器）永远看到
                     * 一个完整的表，不会看到"改了一半"的状态。
                     */
                    capabilities = capabilities + (devicePath to caps)
                }
            } catch (e: Exception) {
                AppLog.w(TAG, "补探 $devicePath 失败：${e.javaClass.simpleName}")
            } finally {
                probing.remove(devicePath)
            }
        }, "RootProbe-${devicePath.substringAfterLast('/')}").also {
            it.isDaemon = true
            it.start()
        }
    }

    /** 起一条 `su -c` 只探一台设备;调用方必须在后台线程 */
    private fun probeForDevice(devicePath: String): DeviceCapabilities? =
        InputDeviceProbe { command ->
            val probeProc = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            probeProcess = probeProc
            BufferedReader(InputStreamReader(probeProc.inputStream))
        }.probeDevice(devicePath).also { releaseProbe() }

    /**
     * 收尾：**先杀子进程再中断线程**。
     *
     * ⚠️ 顺序不能反。`readLine()` 阻塞在管道上，中断线程叫不醒它；
     * 关掉进程让管道 EOF，`readLine()` 才会返回。
     */
    private fun releaseProcess() {
        try {
            process?.destroy()
        } catch (_: Exception) {
        }
        process = null
        thread?.interrupt()
        thread = null
    }

    private fun readLoop(onEvent: (InputEvent) -> Unit, onError: (String) -> Unit) {
        /*
         * ⚠️ 先探测设备能力,再开主监听。
         *
         * 摇杆的归一化需要每个轴的 `min / max / flat`,而那些**只有
         * `getevent -i` 会打印** —— 主监听流里只有裸值。所以必须多这一步。
         *
         * 放在读循环的**最前面**、且只在启动时做一次:它是冷路径
         * (一次进程 + 一次解析),而热插拔进来的设备查不到轴范围 ——
         * 这是刻意的取舍,理由见 [InputDeviceProbe] 的类注释。
         *
         * ⚠️ 它**失败也不影响采集**:拿不到范围时摇杆回落到假定范围
         * (16 位对称),而不是让整个采集起不来。
         */
        capabilities = InputDeviceProbe { command ->
            val probeProc = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            probeProcess = probeProc
            BufferedReader(InputStreamReader(probeProc.inputStream))
        }.probe().also {
            /* 探测进程用完就收掉:它可能不退出,留着会一直占着一个 su */
            releaseProbe()
        }

        try {
            val proc = ProcessBuilder("su", "-c", GeteventStream.COMMAND)
                /*
                 * ⚠️ 必须合并 stderr。
                 *
                 * `su` 与 `getevent` 的失败原因（没有 root 权限、
                 * 参数不认、设备打不开）经常走 stderr。不合并的话
                 * 它们会被丢掉，我们就只剩"进程退出了"这一句废话 ——
                 * 而用户看到的是"监听失败"却没有原因。
                 */
                .redirectErrorStream(true)
                .start()
            process = proc

            AppLog.i(TAG, "getevent 已启动，开始读取：${GeteventStream.COMMAND}")

            val stream = GeteventStream(
                reader = BufferedReader(InputStreamReader(proc.inputStream)),
                tag = TAG,
                isRunning = { running.get() },
                onEvent = onEvent,
                onDeviceCount = { count -> _deviceCount.value = count },
                accept = GeteventStream.deviceFilter { capabilities },
                onDeviceAttached = ::probeNewDevice,
                onlyNewDevices = { path -> path !in probedDevices },
            )

            /*
             * 解析、设备公告、设备数、拔出信号**全部在 [GeteventStream] 里** ——
             * 那是 root 与 Shizuku 两条通道共用的部分，见它的类注释。
             */
            val gotAnything = stream.run()

            /*
             * `readLine()` 返回 null = 进程的 stdout 关了。
             *
             * 主动 stop() 时也是这条路，靠 running 标志区分 ——
             * 不同 ROM 上关闭管道抛的异常类型并不一致，
             * 用异常类型判断会漏。
             */
            if (running.get()) {
                throw IllegalStateException(
                    if (gotAnything) {
                        "getevent 进程已退出"
                    } else {
                        "getevent 没有输出任何内容（可能参数不被支持，或 su 被拒绝）"
                    },
                )
            }
        } catch (e: Exception) {
            if (running.get()) {
                AppLog.w(TAG, "读取中断：${e.javaClass.simpleName}: ${e.message}")
                onError("root 读取失败：${e.message ?: e.javaClass.simpleName}")
            }
        } finally {
            running.set(false)
            try {
                process?.destroy()
            } catch (_: Exception) {
            }
            process = null
        }
    }

    private companion object {
        const val TAG = "RootInput"


        /** 启动时原样记进日志的行数 */
        const val PROBE_LOG_LINES = 30

        /** 单行日志截断长度，防止异常长行把日志刷爆 */
        const val MAX_LOG_LINE = 200
    }
}

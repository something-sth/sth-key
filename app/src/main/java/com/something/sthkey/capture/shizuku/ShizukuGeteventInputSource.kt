package com.something.sthkey.capture.shizuku

import com.something.sthkey.capture.CaptureChannel
import com.something.sthkey.capture.CaptureSession
import com.something.sthkey.capture.CaptureState
import com.something.sthkey.capture.InputEvent
import com.something.sthkey.capture.InputSource
import com.something.sthkey.core.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shizuku 输入源。
 *
 * ============================================================
 * 链路
 * ============================================================
 * ```
 * 主应用 ──裸 Binder 事务──▶ Shizuku 服务端 ──spawn──▶ sh -c "/system/bin/getevent -t"
 *        ◀──────── 进程 stdout（管道）──────── 文本行
 * ```
 *
 * 与 root 通道**跑的是同一个命令**，差别只有"谁去起那个进程"。
 * 拿到文本行之后的全部处理（解析、设备公告、设备数、拔出信号）
 * 都在 [GeteventStream] 里 —— 两条通道共用，见它的类注释。
 *
 * ============================================================
 * ⚠️ 与 root 的关键差别
 * ============================================================
 * | | root | Shizuku |
 * |---|---|---|
 * | 起进程 | `ProcessBuilder("su", "-c", …)` | `ShizukuShell.start(…)`（Binder） |
 * | 失败面 | su 被拒 / 没有 root | 未授权、Shizuku 被杀、binder 断开 |
 * | 断线后 | 进程没了就是没了 | **服务端可能还在，重连往往能成** |
 *
 * 最后一条决定了这里**必须有重连**：Shizuku 服务重启、被系统回收、
 * 或者 OEM 把远端进程杀掉，都会让流结束 —— 而那种情况下
 * 直接报"监听失败"是错的：用户什么都没做，采集却停了。
 *
 * ============================================================
 * ⚠️ 为什么是"一条进程"，不是"一设备一条"
 * ============================================================
 * 原来每个设备起一条 `getevent <device>`，于是**设备清单在启动那一刻
 * 就定死了** —— 插上手柄不会开始工作，用户得去点"重启监听"，
 * 而他不会知道要这么做，只会觉得"手柄没反应"。
 *
 * 改成全局监听之后：
 * - `getevent` 自己枚举设备，并在热插拔时打印 `add device` / `remove device`；
 * - 一条进程代替 N 条（也省掉了 `ls /dev/input` 那趟预扫）。
 *
 * ⚠️ 代价：**单点失败**。一条进程死了全部停 —— 所以重连不是可选项。
 *
 * ============================================================
 * ⚠️ 不再用 pid 握手（`cat /proc/self/stat; exec …`）
 * ============================================================
 * 旧实现会在命令前面拼一段"让进程自报 pid"的握手，用来探测
 * "进程还在不在"。那是为**每设备一条进程**设计的 —— 那时要逐个
 * 判断哪个设备活着。
 *
 * 现在只有一条进程，判据简单得多：**流读到过内容没有**
 * （[runOnce] 的返回值）。读到过又断了 = 意外断开，重连；
 * 一行都没读到 = 命令没跑起来，同样重连但会记账。
 * 于是那层握手的复杂度可以整个去掉。
 *
 * @param (已移除) 全局模式下没有 devicePaths 参数，见接口注释。
 *
 *   保留这个参数只是为了不让 [InputSource] 的签名分叉（root 那边同样忽略它）。
 *   全局模式下设备清单由 `getevent` 自己给出，不需要外部先扫一遍 ——
 *   而且主应用**根本没有权限** `ls /dev/input`。
 */
class ShizukuGeteventInputSource : InputSource {

    override val channel: CaptureChannel = CaptureChannel.SHIZUKU

    /**
     * 设备数由 `getevent` 的公告驱动（见 [GeteventStream]）。
     *
     * ⚠️ 不能用"启动前扫一遍得到的数字"—— 全局监听下设备清单会随
     * 热插拔变化，那个数字**立刻就过时了**。
     */
    private val _deviceCount = MutableStateFlow(0)
    override val deviceCount: StateFlow<Int> = _deviceCount

    private val running = AtomicBoolean(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 当前远端进程；[stop] 时关掉它（关流会让读取循环退出） */
    @Volatile
    private var shell: ShizukuShell.RemoteShell? = null

    @Volatile
    private var loopJob: Job? = null

    /** 连续失败次数，用来算退避间隔 */
    private var consecutiveFailures = 0

    /**
     * 本次会话的设备能力（每个绝对轴的取值范围）。
     *
     * 摇杆归一化要用它把裸 ADC 值换算成 `-1..1`。
     *
     * ⚠️ **只在第一次连接时探测**（见 [probeCapabilities]）：轴范围在一台
     * 设备插上之后不会变，每次重连都探一遍纯属浪费 —— 而重连本身
     * 已经够频繁了（Shizuku 重启、被回收）。
     */
    @Volatile
    override var capabilities: Map<String, DeviceCapabilities> = emptyMap()
        private set

    /** 探测过了就不再探（哪怕是空的 —— 空表也是"探过了"的结论） */
    @Volatile
    private var capabilitiesProbed = false

    /**
     * 探测设备能力;**只做一次**。
     *
     * ⚠️ 不放在每次重连里:轴范围在一台设备插上之后不会变,
     * 而重连本身已经够频繁(Shizuku 重启、被回收)。
     * 每次重连都多起一条进程纯属浪费。
     *
     * ⚠️ **失败不抛**:拿不到轴范围只是摇杆幅度不准(回落到假定范围),
     * 而采集本身还该工作 —— 不能因为探测失败就让整个通道起不来。
     *
     * ⚠️ **非 suspend**:内部自己开协程,这样非协程的 [start] 也能调它。
     * 结果是探测**异步**完成 —— 摇杆头几百毫秒可能拿不到范围,
     * 那时回落到假定范围,之后就准了。这个延迟无所谓:
     * 从插上手柄到用户推摇杆本来就不止几百毫秒。
     */
    private fun probeCapabilities() {
        if (capabilitiesProbed) return
        capabilitiesProbed = true

        scope.launch {
            /*
             * ⚠️ 顺序很重要:`ShizukuShell.start` 是 **suspend**,而
             * [InputDeviceProbe] 的 `launcher` 是**普通函数类型** ——
             * 在它里面调 suspend 函数会报
             * `Suspension functions can only be called within coroutine body`
             * (而错误会指向 `start` 那一行,看起来像协程上下文的问题)。
             *
             * 所以**先在外面把 shell 起起来**,只把"读流"这一步交给探测。
             */
            val probed: Map<String, DeviceCapabilities> = try {
                val shell = ShizukuShell.start(InputDeviceProbe.COMMAND)
                withContext(Dispatchers.IO) {
                    InputDeviceProbe {
                        BufferedReader(InputStreamReader(shell.inputStream))
                    }.probe()
                }
            } catch (e: Exception) {
                AppLog.w(TAG, "设备能力探测失败：${e.javaClass.simpleName}: ${e.message}")
                emptyMap()
            }
            capabilities = probed
        }
    }

    /** 正在补探的设备路径，防止同一个设备被重复探 */
    private val probing = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * **本进程**已经补探过的设备路径。
     *
     * ⚠️ 它与 [GeteventStream] 内部那个 `devices` 集合**不是一回事**：
     * 那个是"这台 stream 见过谁"，每次采集重启都会新建一个 ——
     * 于是启动时那批设备又会被当成"新的"再探一遍。
     *
     * 这个集合跨 stream 存活，所以补探**每台设备一辈子只做一次**。
     */
    private val probedDevices = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * 新设备接入时**在后台**补探它的轴范围（只探 **一台**）。
     *
     * ============================================================
     * 为什么需要它
     * ============================================================
     * [probeCapabilities] 只在**第一次连接时**跑，所以启动后才插进来的
     * 手柄查不到轴范围 —— 实测日志里那种 `[event7 未知设备(未探测)]`。
     *
     * ============================================================
     * ⚠️ 必须异步
     * ============================================================
     * 回调发生在**读取线程**上。同步跑 `getevent -i` 会把读取循环卡住，
     * 用户看到的是"插上手柄之后按键有一秒没反应"。
     *
     * ============================================================
     * ⚠️ **合并**而不是替换
     * ============================================================
     * 补探只拿到一台设备。整体替换会丢掉启动时探到的其它设备 ——
     * 表现是"插一次手柄，触摸屏过滤和别的设备都不认了"。
     */
    private fun probeNewDevice(devicePath: String) {
        if (devicePath.isEmpty()) return
        if (!probing.add(devicePath)) return

        /* 先记下来：补探已排队，采集重启不该再排一次 */
        probedDevices += devicePath

        scope.launch {
            try {
                val shell = ShizukuShell.start(
                    "${InputDeviceProbe.GETEVENT} -i 2>&1 '$devicePath' </dev/null",
                )
                val caps = withContext(Dispatchers.IO) {
                    InputDeviceProbe {
                        BufferedReader(InputStreamReader(shell.inputStream))
                    }.probeDevice(devicePath)
                }
                if (caps != null) {
                    /* 整体赋值是原子的：读侧永远看到一个完整的表 */
                    capabilities = capabilities + (devicePath to caps)
                }
            } catch (e: Exception) {
                AppLog.w(TAG, "补探 $devicePath 失败：${e.javaClass.simpleName}")
            } finally {
                probing.remove(devicePath)
            }
        }
    }

    override fun isRunning(): Boolean = running.get()

    override fun start(
        onEvent: (InputEvent) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!running.compareAndSet(false, true)) {
            AppLog.w(TAG, "已经在运行，忽略重复启动")
            return
        }

        AppLog.i(TAG, "启动 Shizuku 采集（全局监听）：${GeteventStream.COMMAND}")

        loopJob = scope.launch { readLoop(onEvent, onError) }
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) {
            releaseShell()
            return
        }

        AppLog.i(TAG, "停止 Shizuku 采集")
        loopJob?.cancel()
        loopJob = null
        releaseShell()
        scope.cancel()
    }

    /**
     * 收尾：关掉远端进程。
     *
     * ⚠️ 必须关。远端进程是 Shizuku 服务端 spawn 的 —— 我们只是拿着
     * 它的 stdout 描述符。不显式销毁的话，应用退出后它会在服务端
     * **一直活着**（孤儿进程），而且每次重启采集都会再堆一个。
     */
    private fun releaseShell() {
        try {
            shell?.close()
        } catch (e: Exception) {
            AppLog.w(TAG, "关闭远端进程失败：${e.javaClass.simpleName}")
        }
        shell = null
    }

    private suspend fun readLoop(onEvent: (InputEvent) -> Unit, onError: (String) -> Unit) {
        while (running.get()) {
            val ok = runOnce(onEvent, onError)

            if (!running.get()) break

            /*
             * 流结束了但用户还开着监听 —— 说明是**意外断开**。
             *
             * 原因可能是：Shizuku 服务重启、被系统回收、OEM 杀远端进程。
             * 这类问题**重连往往能恢复**（服务端还在），所以先重试，
             * 重试到一定次数才报失败。
             */
            consecutiveFailures++

            if (consecutiveFailures >= MAX_FAILURES_BEFORE_REPORT) {
                /*
                 * 连续失败到上限：这时才报给用户。
                 *
                 * ⚠️ 用 CaptureSession 直接改状态，而不是调 onError：
                 * onError 会让 CaptureController 把状态标成 FAILED 并
                 * **不再重试**，而这里还在重试循环里 —— 两者会打架，
                 * 表现为状态在 RUNNING / FAILED 之间跳。
                 */
                CaptureSession.updateState(
                    CaptureState.FAILED,
                    "Shizuku 采集连续 $consecutiveFailures 次无法建立，请检查 Shizuku 是否在运行",
                )
                AppLog.w(TAG, "连续 $consecutiveFailures 次失败，停止重试")
                running.set(false)
                break
            }

            val waitMs = backoffMs(consecutiveFailures)
            AppLog.i(
                TAG,
                "采集中断（第 $consecutiveFailures 次），${waitMs}ms 后重连" +
                    if (ok) "" else "（本次没有读到任何内容）",
            )
            delay(waitMs)
        }

        AppLog.i(TAG, "读取循环结束")
        running.set(false)
        releaseShell()
    }

    /**
     * 起一条远端进程并读到结束。
     *
     * @return 是否读到了任何一行。用来区分"流完全没数据"（多半是命令
     *   没跑起来）与"正常读了一段然后断开" —— 前者重试的意义小得多，
     *   报错时也该说清是哪种。
     */
    private suspend fun runOnce(
        onEvent: (InputEvent) -> Unit,
        onError: (String) -> Unit,
    ): Boolean {
        probeCapabilities()

        val remote = try {
            withContext(Dispatchers.IO) { ShizukuShell.start(GeteventStream.COMMAND) }
        } catch (e: Exception) {
            AppLog.w(TAG, "启动远端进程失败：${e.javaClass.simpleName}: ${e.message}")
            onError("Shizuku 启动失败：${e.message ?: e.javaClass.simpleName}")
            return false
        }

        shell = remote
        AppLog.i(TAG, "远端 shell 已启动：${remote.command}")

        val stream = GeteventStream(
            reader = BufferedReader(InputStreamReader(remote.inputStream)),
            tag = TAG,
            isRunning = { running.get() },
            onEvent = onEvent,
            accept = GeteventStream.deviceFilter { capabilities },
            onDeviceAttached = ::probeNewDevice,
            onlyNewDevices = { path -> path !in probedDevices },
            onDeviceCount = { count ->
                _deviceCount.value = count

                /*
                 * 第一次拿到设备数时把状态标成 RUNNING。
                 *
                 * ⚠️ 不能更早：全局监听下**启动那一刻还不知道有几个设备**，
                 * 公告要等 `getevent` 打印出来。若在这里之前就标 RUNNING，
                 * 用户可能看到"监听中"但其实一个设备都没枚举到。
                 *
                 * ⚠️ 只在"不是重连中"才标：重连期间状态由重试逻辑管，
                 * 这里再写会把 FAILED 覆盖掉。
                 */
                CaptureSession.updateState(CaptureState.RUNNING, "正在监听 $count 个输入设备")
            },
        )

        val gotAnything = try {
            stream.run()
        } finally {
            /*
             * ⚠️ 每次重连都要清一次设备数。
             *
             * 旧进程见过的设备在新进程里要重新枚举，不清的话中断期间
             * 界面会一直显示一个过时的数字，用户看到"监听中，3 个设备"
             * 但按键没反应。
             */
            _deviceCount.value = 0
            releaseShell()
        }

        if (gotAnything) consecutiveFailures = 0

        if (running.get()) {
            AppLog.i(
                TAG,
                if (gotAnything) {
                    "远端流已结束（进程退出或 Shizuku 断开）"
                } else {
                    "远端流没有任何输出 —— 命令可能没跑起来"
                },
            )
        }

        return gotAnything
    }

    /**
     * 退避间隔。
     *
     * 从 400ms 起、每次翻倍、上限 8s —— 既能扛住"Shizuku 正在重启"
     * 那几秒，也不会在真的坏掉时把 CPU 打满。
     */
    private fun backoffMs(failures: Int): Long {
        var ms = INITIAL_BACKOFF_MS
        repeat((failures - 1).coerceAtMost(6)) { ms *= 2 }
        return ms.coerceAtMost(MAX_BACKOFF_MS)
    }

    private companion object {
        const val TAG = "ShizukuGetevent"

        /*
         * ⚠️ 这里**没有** COMMAND 常量。
         *
         * 命令在 `GeteventStream.COMMAND` —— 两条通道共用同一个，因为
         * root 与 Shizuku 跑的本来就是同一条命令（差别只在"谁起进程"）。
         * 各写一份就会出现"root 能热插拔、Shizuku 不能"这种分叉，
         * 而且极难查：两条通道看起来都"正常"，只是行为不同。
         */

        /** 连续失败到几次就报失败并停止重试 */
        const val MAX_FAILURES_BEFORE_REPORT = 6

        const val INITIAL_BACKOFF_MS = 400L
        const val MAX_BACKOFF_MS = 8_000L
    }
}

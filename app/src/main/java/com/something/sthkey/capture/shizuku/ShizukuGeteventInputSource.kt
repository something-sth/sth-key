package com.something.sthkey.capture.shizuku

import com.something.sthkey.capture.CaptureChannel
import com.something.sthkey.capture.CaptureSession
import com.something.sthkey.capture.CaptureState
import com.something.sthkey.capture.InputEvent
import com.something.sthkey.capture.InputSource
import com.something.sthkey.core.log.AppLog
import com.something.sthkey.domain.capture.ShizukuBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shizuku 输入源（**绕开 UserService** 的那条通道）。
 *
 * ============================================================
 * 链路
 * ============================================================
 * ```
 * 主应用 ──裸 Binder 事务──▶ Shizuku 服务端 ──spawn──▶ sh -c "getevent <单个设备>"
 *        ◀──────── 进程 stdout（管道）──────── 文本行
 * ```
 *
 * 与老的 UserService 方案（Shizuku 托管一个用户服务进程、事件走 AIDL 回调）相比：
 *
 * | | UserService 版 | 本版 |
 * |---|---|---|
 * | 谁 spawn 读取进程 | Shizuku 托管 | Shizuku 直接起 `sh` |
 * | 事件怎么回传 | AIDL 批量回调 | **进程 stdout 文本行** |
 * | 应用被杀后 | 远端还活着，采集能续上 | **进程一起死** |
 * | 依赖 | `bindUserService` | `newProcess`（已废弃，见 [ShizukuShell]） |
 *
 * 之所以要多这一条：有用户设备上 `bindUserService` **每次都超时**，
 * 而同机另一个用 Shizuku 的应用（走的正是 `newProcess`）却正常 ——
 * 说明坏的只是 UserService 那一段。
 *
 * ============================================================
 * ⚠️ 一个设备一个进程（这里踩过一个很贵的坑）
 * ============================================================
 * 起初为了省事，是"**一个 getevent 进程监听全部设备**"：
 *
 * ```
 * getevent /dev/input/event0 /dev/input/event1 … /dev/input/event6
 * ```
 *
 * 在真实设备上直接失败 —— `getevent` 把用法打了出来：
 *
 * ```
 * Usage: /dev/input/getevent [-t] [-n] … [-c count] [-r] [device]
 *                                                          ^^^^^^^^ 单数！
 * ```
 *
 * 也就是说**它只接受一个 device 参数**，多传就打印用法并退出。
 * 现象是"探测期读到 13 行都是非事件行、随后进程立刻退出" ——
 * 那 13 行就是这段用法文本。
 *
 * 正确做法是**每个设备起一个进程**（老的 UserService 版本就是这么做的，
 * 它给每个设备一个独立的读取线程）。教训：**别为了"优化"去合并
 * 一个已经被验证过能工作的结构** —— 那里的"一设备一进程"不是随意写的。
 *
 * ============================================================
 * 为什么用 `getevent` 而不是自己读设备
 * ============================================================
 * 读 `/dev/input/event*` 需要自己解析二进制 `input_event`，而且要先知道
 * 每个设备的节点号。`getevent` 是系统自带工具，已经把这些做好了 ——
 * 代价是**多一次文本解析**，对这个应用（只关心按键与鼠标）完全可以接受。
 *
 * ⚠️ 一个必须知道的限制：`getevent` **不能在运行中追加设备**。
 * 设备列表在启动那一刻就定死了，所以"插上手柄"不会自动开始工作，
 * 需要重启采集。这是选它换来的简单性，记在这里免得当成 bug 查。
 */
class ShizukuGeteventInputSource : InputSource {

    override val channel: CaptureChannel = CaptureChannel.SHIZUKU

    private val running = AtomicBoolean(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 每个设备一个读取者 */
    private val readers = Collections.synchronizedList(mutableListOf<DeviceReader>())

    /** 本次采集的启动时刻，用来判断"是不是很快就死了"（决定要不要验尸） */
    private var startedAt = 0L

    override fun isRunning(): Boolean = running.get()

    /**
     * 启动采集。
     *
     * `devicePaths` **被忽略**：设备清单由这里自己用 shell 身份扫
     * （普通进程列不了 `/dev/input`，调用方拿不到真清单）。
     * 这一点与 root 通道一致，`CaptureController` 那边也是传空列表进来的。
     */
    override fun start(
        devicePaths: List<String>,
        onEvent: (InputEvent) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!running.compareAndSet(false, true)) {
            AppLog.w(TAG, "已经在运行，忽略重复启动")
            return
        }

        scope.launch {
            try {
                val devices = scanDevices()
                if (devices.isEmpty()) {
                    fail(onError, "没有找到可读的输入设备（ls 没列出 /dev/input/event*）")
                    return@launch
                }

                startedAt = System.currentTimeMillis()

                val ready = launchWithFallback(devices)

                /*
                 * ⚠️ 只有确认**至少有一个**设备真的读起来了，才报"监听中"。
                 *
                 * 早先是"进程一起来就报监听中"，用户看到状态闪一下
                 * 就变成"已退出"—— 界面先乐观后打脸。
                 *
                 * 这里允许"部分设备失败"：某些节点本来就打不开
                 * （比如权限受限的传感器），一个打不开不该拖垮全部。
                 * 但**一个都打不开**就是失败。
                 */
                if (ready.isEmpty()) {
                    /*
                     * 具体原因由 `launchWithFallback` 已经写进日志了
                     * （那里会把**所有轮次**的原因一起输出）。
                     * 这里只给一句结论，避免同一段长文本在界面上重复两遍。
                     */
                    fail(onError, "所有 ${devices.size} 个输入设备都无法读取（已尝试两种启动策略，原因见日志）")
                    return@launch
                }

                val opened = ready.size
                CaptureSession.updateDeviceCount(opened)
                ShizukuBridge.reportDiagnostic(
                    "Shizuku（直连）已启动，$opened/${devices.size} 个设备可读",
                )
                CaptureSession.updateState(
                    CaptureState.RUNNING,
                    "正在通过 Shizuku 监听 $opened 个输入设备" +
                        if (opened < devices.size) "（共 ${devices.size} 个，部分不可读）" else "",
                )
                AppLog.i(
                    TAG,
                    "Shizuku 直连采集已启动：$opened/${devices.size} 个设备可读" +
                        "（耗时 ${System.currentTimeMillis() - startedAt}ms）",
                )

                /*
                 * 顺序刻意如此：**先报状态，再起读线程**。
                 *
                 * 反过来的话，读线程可能在状态还是"启动中"的时候就发现
                 * 进程死了并调 [fail] —— 那会先报失败、再报成功，很荒唐。
                 */
                ready.forEach { startReader(it, onEvent) }
            } catch (e: Exception) {
                fail(onError, "Shizuku 直连启动失败：${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) return

        AppLog.i(TAG, "停止 Shizuku 直连采集")
        synchronized(readers) {
            readers.forEach { it.stop() }
            readers.clear()
        }
    }

    /*
     * ============================================================
     * 内部
     * ============================================================
     */

    /**
     * 一个设备对应的一整套资源。
     *
     * 每个设备独立持有 shell / 读取器 / 线程：一个设备的进程挂掉
     * 不该影响其它设备（这正是老 UserService 版的结构，
     * 我一度把它合并成单进程，结果整个功能都跑不起来）。
     */
    private class DeviceReader(
        val device: String,
        val shell: ShizukuShell.RemoteShell,
        val input: BufferedReader,
    ) {
        @Volatile
        var thread: Thread? = null

        /** 这个设备读到了多少行（用于日志与失败判断） */
        @Volatile
        var lines = 0

        /** 探测期读到的非事件行（getevent 的报错通常在这里） */
        @Volatile
        var evidence: String? = null

        fun stop() {
            thread?.interrupt()
            thread = null
            // 关掉 stdout 会让 getevent 收到 SIGPIPE 自行退出；
            // 再显式 destroy 一次，确保服务端不留下孤儿进程
            shell.close()
        }
    }

    /**
     * 两轮启动：先「偏快」，失败且**值得**才用「偏稳」再来一遍。
     *
     * ============================================================
     * 为什么要有第二轮
     * ============================================================
     * 「偏快」靠一个很短的探活窗口抢速度，代价是理论上可能把
     * "只是启动慢了一点"误判成失败。第二轮用一个厚得多的判据兜住它，
     * 于是**常规路径很快、坏情况也不会失败** —— 比二选一都好。
     *
     * ============================================================
     * ⚠️ 不是所有失败都值得重试
     * ============================================================
     * 有些失败是**确定性**的，重试只会让用户白等一倍时间：
     * - `getevent` 打印用法（参数不认）→ 换多厚的探活都一样；
     * - `could not open …`（权限问题）→ 第二次照样打不开。
     *
     * 所以只在一眼看出"可能就是太急了"时才跑第二轮，
     * 判据见 [StartupPolicy.decide]。
     *
     * ============================================================
     * ⚠️ 两轮的失败原因都要留着
     * ============================================================
     * 只报最后那一轮的话，真正有用的线索可能在前一轮。
     * 这个项目已经踩过一次"证据被覆盖"（见 [Probe.evidence] 那段注释），
     * 不能再犯：这里会把两轮的失败原因**一起**带出去。
     */
    private suspend fun launchWithFallback(devices: List<String>): List<DeviceReader> {
        val attempts = mutableListOf<StartupPolicy.Attempt>()

        for ((round, strategy) in StartupPolicy.STRATEGIES.withIndex()) {
            val startedAt = System.currentTimeMillis()
            val (attempt, ready) = launchAll(devices, strategy)
            val elapsed = System.currentTimeMillis() - startedAt
            attempts += attempt

            AppLog.i(
                TAG,
                "第 ${round + 1} 轮（${strategy.label}）耗时 ${elapsed}ms：" +
                    "可读 ${attempt.readyCount}/${devices.size}",
            )

            when (val decision = StartupPolicy.decide(round, attempt)) {
                is StartupPolicy.Decision.Succeeded -> {
                    if (decision.usedFallback) {
                        // 走了第二轮说明第一轮判断偏了，记一条便于以后回看
                        ShizukuBridge.reportDiagnostic(
                            "「${StartupPolicy.STRATEGIES.first().label}」启动失败，" +
                                "改用「${strategy.label}」后成功",
                        )
                    }
                    return ready
                }

                StartupPolicy.Decision.Retry -> Unit

                StartupPolicy.Decision.GiveUp -> {
                    AppLog.i(TAG, "不再尝试下一轮")
                    break
                }
            }
        }

        // 两轮都没成：把**所有**轮次的原因一起报出去
        AppLog.e(TAG, StartupPolicy.failureMessage(attempts))
        return emptyList()
    }

    /**
     * 一轮启动：**所有设备同时起**。
     *
     * ============================================================
     * ⚠️ 并行是这里的关键（原来串行，慢得离谱）
     * ============================================================
     * 早先是 `for (device in devices)` 逐个来，于是每个设备都要
     * **串行**付一次"握手 + 探活窗口"。7 个设备 × 约 700ms ≈ 5 秒，
     * 这正是用户反馈"启动要 5 秒多"的来源。
     *
     * 设备之间**本来就是独立的**，没有任何理由排队。改成并行之后，
     * 总耗时约等于**单个设备**的耗时。
     *
     * ⚠️ 并发上限不能省：设备多的时候（某些机器有十几个节点）
     * 会一口气发十几个 binder 调用，Shizuku 服务端要同时 spawn
     * 十几个进程，容易把它压出问题。
     *
     * @return 判定用的纯数据 + 成功打开的读取者。
     *   两者分开是因为 [StartupPolicy.Attempt] 要能**在本地单测里构造**
     *   （只有数字与字符串），所以它不能持有 binder。
     */
    private suspend fun launchAll(
        devices: List<String>,
        strategy: StartupPolicy.Strategy,
    ): Pair<StartupPolicy.Attempt, List<DeviceReader>> = coroutineScope {
        val gate = Semaphore(MAX_CONCURRENT_STARTS)

        val tasks = devices.map { device ->
            async(Dispatchers.IO) {
                gate.withPermit { launchOne(device, strategy) }
            }
        }

        val results = tasks.awaitAll()
        val ready = results.mapNotNull { it.reader }
        val failures = results.mapNotNull { it.failure }

        StartupPolicy.Attempt(
            readyCount = ready.size,
            failures = failures,
            /*
             * "全都是转瞬即逝的失败"才值得重试。
             *
             * 一个设备都没成功、而且每个失败都没有留下任何报错输出 ——
             * 这是"可能只是太急了"的典型样子。
             * 只要有任何一个失败带着明确报错（用法提示、打不开），
             * 就说明问题不在速度上。
             */
            allTransient = ready.isEmpty() && results.all { it.transient },
        ) to ready
    }

    /**
     * 起**一个**设备。返回成功，或带着原因失败。
     *
     * 内部会依次试 [COMMAND_VARIANTS]（参数组合）：有些 ROM 的
     * `getevent` 不认 `-q`，换一组就能跑，不必让用户去猜。
     */
    private suspend fun launchOne(
        device: String,
        strategy: StartupPolicy.Strategy,
    ): LaunchResult {
        val problems = mutableListOf<String>()
        var sawEvidence = false

        for ((index, variant) in COMMAND_VARIANTS.withIndex()) {
            val command = variant.build(device)
            AppLog.i(TAG, "启动 getevent（$device，${variant.label}）：$command")

            val shell = try {
                ShizukuShell.start(command)
            } catch (e: Exception) {
                problems += "启动失败（${e.message ?: e.javaClass.simpleName}）"
                continue
            }

            val input = BufferedReader(InputStreamReader(shell.inputStream))

            /*
             * 先读握手行拿 pid（命令头是 `cat /proc/self/stat; exec …`）。
             *
             * ⚠️ 必须是**这个进程自己**报的 pid。另起一条 shell 跑 `echo $$`
             * 拿到的是那条 shell 的 pid，与目标进程无关，
             * 用它判断死活必然得出错误结论。
             */
            val pid = readHandshakePid(input)
            val probe = probeAlive(input, pid, strategy)

            if (probe.alive) {
                AppLog.i(
                    TAG,
                    "$device 存活确认（${variant.label}），探测期读到 ${probe.lines} 行" +
                        if (probe.skipped > 0) "，其中 ${probe.skipped} 行不是事件" else "",
                )
                probe.evidence.takeIf { it.isNotBlank() }?.let {
                    AppLog.i(TAG, "$device 探测期非事件输出：$it")
                }
                if (index > 0) {
                    ShizukuBridge.reportDiagnostic(
                        "$device 使用「${variant.label}」参数（默认那组在这台设备上不可用）",
                    )
                }
                return LaunchResult(
                    device = device,
                    reader = DeviceReader(device, shell, input).also { it.evidence = probe.evidence },
                )
            }

            if (probe.evidence.isNotBlank()) sawEvidence = true
            problems += "${variant.label}：${probe.reason}"
            // 这一步不能省：进程已经没用了，留着会在服务端变成孤儿进程
            shell.close()
        }

        return LaunchResult(
            device = device,
            reader = null,
            failure = "$device：" + problems.joinToString("，"),
            /*
             * 试探性失败 = **从头到尾没读到任何报错输出**。
             *
             * 读到了用法提示或 `could not open` 说明原因明确，
             * 再怎么重试也是同一个结果 —— 那不是"太急了"。
             */
            transient = !sawEvidence,
        )
    }

    /**
     * 单个设备的启动结果。
     */
    private data class LaunchResult(
        val device: String,
        val reader: DeviceReader?,
        val failure: String? = null,
        val transient: Boolean = false,
    )

    /**
     * 读握手行（进程自报的 pid）。
     *
     * ⚠️ **必须带超时**：这一行读下去是阻塞的，如果命令因为某种原因连
     * `cat` 都没跑到，我们就永远卡在这里。超时后返回 null，
     * 让 [probeAlive] 退化到"只看输出"的判断。
     *
     * 放在临时线程里读而不是直接读，就是为了能给这个阻塞读加超时。
     */
    private fun readHandshakePid(input: BufferedReader): Int? {
        var pid: Int? = null
        val worker = Thread({
            pid = runCatching { input.readLine()?.trim()?.toIntOrNull() }.getOrNull()
        }, "ShizukuHandshake")

        worker.isDaemon = true
        worker.start()
        worker.join(HANDSHAKE_TIMEOUT_MS)

        if (worker.isAlive) {
            AppLog.w(TAG, "握手超时（${HANDSHAKE_TIMEOUT_MS}ms 内没读到 pid）")
            return null
        }
        return pid
    }

    /**
     * 探活结果。
     */
    private data class Probe(
        val alive: Boolean,
        val lines: Int,
        val skipped: Int,
        val reason: String,
        /**
         * 探测期读到的**非事件行**（getevent 的报错、用法提示等）。
         *
         * 这些内容以前被当成"噪声"丢掉，结果排查时只剩"0 行输出"这种废话。
         * 实际上它们往往是**唯一**能说明原因的东西 —— 必须带出来。
         * 本文件的"一设备一进程"这个结论，就是靠它才定下来的：
         * 那 13 行非事件输出正是 `getevent` 的用法文本。
         */
        val evidence: String = "",
    )

    /**
     * 确认进程**真的活着**。
     *
     * ============================================================
     * ⚠️ 这里的第一版是坏的（值得记一笔）
     * ============================================================
     * 第一版写的是"读 300ms，没抛异常就算活着"，用 `ready()` 判断有没有数据。
     * 它错在：
     *
     * **流被对端关闭时 `ready()` 只是返回 false，并不抛异常。**
     *
     * 于是进程早就死了，探活却空转 300ms 之后报"存活"，接着读线程立刻
     * 读到 EOF，界面又变成"已退出"—— 闪烁一点没少，等于探活白做。
     *
     * 现在改成两条独立的证据：
     * - **输出**：窗口内读到的每一行都留着（含非事件行，它们是报错证据）；
     * - **进程**（偏稳那轮才做）：拿 pid 去问 `/proc`
     *   （见 [ShizukuShell.isProcessAlive]）—— 这是确定性的答案，不在流上猜。
     *
     * ⚠️ 判据是"**只有明确死了才算死**"：读到 EOF 是硬证据；
     * 安静但 pid 还活着就是健康（`getevent` 没有输入时本来就安静）。
     * 没有 pid 时保守判活 —— 宁可漏报一次，也不误杀一台正常的设备。
     *
     * @param strategy 决定探活窗口多长、要不要走 `/proc` 复核。
     *   窗口长短是"快"与"稳"的直接取舍，所以由调用方给，不写死在这里。
     */
    private suspend fun probeAlive(
        input: BufferedReader,
        pid: Int?,
        strategy: StartupPolicy.Strategy,
    ): Probe {
        val deadline = System.currentTimeMillis() + strategy.probeWindowMs
        var lines = 0
        var skipped = 0
        val collected = StringBuilder()

        /*
         * do/while：**至少跑一轮**。
         *
         * 写成普通 while 的话，设备卡顿时这个循环可能一次都不执行 ——
         * 那样等于没探活，又回到"先乐观后打脸"。
         */
        do {
            try {
                // 用 ready() 避免在安静时被阻塞住，导致探活窗口完全失效
                if (!input.ready()) {
                    Thread.sleep(POLL_INTERVAL_MS)
                    continue
                }

                val line = input.readLine()
                if (line == null) {
                    // 读到 EOF：流已结束，进程必然没了（这是最硬的证据）
                    return Probe(
                        alive = false,
                        lines = lines,
                        skipped = skipped,
                        reason = "启动后立刻结束（读到 $lines 行输出）",
                        evidence = collected.toString(),
                    )
                }
                lines++

                if (GeteventParser.parse(line) == null) {
                    skipped++
                    /*
                     * 把非事件行留下来当证据。
                     *
                     * `getevent` 的报错（用法提示、"could not open …"）
                     * 也在输出里。之前这些内容被直接丢掉，
                     * 用户只能看到一句"已退出"—— 真正的原因全没了。
                     */
                    if (collected.length < MAX_EVIDENCE_CHARS) {
                        collected.append(line.trim()).append(" | ")
                    }
                }
            } catch (e: Exception) {
                return Probe(
                    alive = false,
                    lines = lines,
                    skipped = skipped,
                    reason = "读取中断（${e.javaClass.simpleName}）",
                    evidence = collected.toString(),
                )
            }
        } while (System.currentTimeMillis() < deadline)

        /*
         * 窗口结束：安静不等于死了。
         *
         * 偏快那轮**到此为止**（多查一次 /proc 要多一个 binder 往返，
         * 而它本来就只是"先试试看"）；偏稳那轮才去问系统要确定答案。
         *
         * 没有 pid 时（握手失败）保守判活：宁可漏报一次"已退出"，
         * 也不要把正常设备误杀。
         */
        if (strategy.verifyViaProc) {
            val stillAlive = pid?.let { ShizukuShell.isProcessAlive(it) } ?: true
            if (!stillAlive) {
                return Probe(
                    alive = false,
                    lines = lines,
                    skipped = skipped,
                    reason = "进程已退出（pid=$pid 在 /proc 里查不到了）",
                    evidence = collected.toString(),
                )
            }
        }

        return Probe(
            alive = true,
            lines = lines,
            skipped = skipped,
            reason = "存活",
            evidence = collected.toString(),
        )
    }

    /**
     * 给一个设备起读线程。
     *
     * 放在**独立线程**而不是协程：`BufferedReader.readLine()` 是阻塞的，
     * 取消协程不会中断它。用一个可 interrupt 的线程，[stop] 才能真的停下来。
     *
     * ⚠️ `input` 是**探活时用过的那个读取器**，必须接着用同一个：
     * `BufferedReader` 会预读，换一个新实例意味着旧缓冲里已经读到的事件
     * 被丢掉 —— 表现为"刚开始监听的一瞬间按键没反应"，
     * 而且是间歇出现、极难查。
     */
    private fun startReader(reader: DeviceReader, onEvent: (InputEvent) -> Unit) {
        val thread = Thread({
            try {
                reader.input.use { source ->
                    while (running.get()) {
                        val line = source.readLine() ?: break
                        reader.lines++
                        GeteventParser.parse(line)?.let { event ->
                            if (isRelevant(event)) onEvent(event)
                        }
                    }
                }
                // 流结束：要么是 stop()，要么是这个设备的进程挂了
                if (running.get()) {
                    onDeviceDied(reader, null)
                }
            } catch (e: Exception) {
                /*
                 * 主动 stop() 时这里必然抛（流被关掉了）—— 那不是错误。
                 * 判断依据是 running 标志，而不是异常类型：不同 ROM 上
                 * 关闭管道抛的异常类型并不一致。
                 */
                if (running.get()) {
                    onDeviceDied(reader, e.javaClass.simpleName)
                }
            }
        }, "ShizukuGetevent-${reader.device.substringAfterLast('/')}")

        reader.thread = thread
        thread.start()
    }

    /**
     * 单个设备的读取结束了。
     *
     * 刻意**不在这里整体失败**：一个设备（比如某个传感器的节点）读不到
     * 不该让其它设备一起停。只有"全部都没了"才算真的死了。
     */
    private fun onDeviceDied(reader: DeviceReader, errorType: String?) {
        reader.stop()
        readers.remove(reader)

        val detail = reader.evidence?.takeIf { it.isNotBlank() }?.let { "，它的输出：$it" } ?: ""
        AppLog.w(
            TAG,
            "${reader.device} 的读取结束" +
                (errorType?.let { "（$it）" } ?: "") +
                "，期间 ${reader.lines} 行$detail",
        )

        if (readers.isEmpty() && running.get()) {
            fail(null, "${reader.device} 的读取结束，且已无其它设备在读取$detail")
        }
    }

    /**
     * 扫描设备节点（shell 身份）。
     *
     * 与 [com.something.sthkey.capture.InputDeviceScanner] 用同样的正则，
     * 但**必须走我们自己的 shell** —— 那边是用 `su` 跑的，Shizuku 通道下没有 root。
     */
    private suspend fun scanDevices(): List<String> {
        val remote = ShizukuShell.start(LIST_COMMAND)
        val output = remote.use { shell ->
            BufferedReader(InputStreamReader(shell.inputStream)).readLines()
        }

        return output.asSequence()
            .map { it.trim() }
            .filter { DEVICE_PATTERN.matches(it) }
            .sortedBy { it.substringAfter("event").toIntOrNull() ?: Int.MAX_VALUE }
            .toList()
    }

    /**
     * 只把上层关心的事件交出去。
     *
     * 过滤条件与 [ShizukuInputService] 里那段**完全一致**
     * （按键的 up/down/repeat、以及鼠标 X/Y 位移）。
     * 尽早过滤掉 EV_SYN 之类的噪声：鼠标移动时每秒可能有上千行，
     * 全部跨线程丢给上层是白白浪费。
     */
    private fun isRelevant(event: InputEvent): Boolean = when (event.type) {
        InputEvent.EV_KEY ->
            event.value == InputEvent.VALUE_UP ||
                event.value == InputEvent.VALUE_DOWN ||
                event.value == InputEvent.VALUE_REPEAT

        InputEvent.EV_REL ->
            (event.code == InputEvent.REL_X || event.code == InputEvent.REL_Y) && event.value != 0

        else -> false
    }

    /**
     * 统一收尾：标记不再运行、把原因交给上层、清掉全部远端进程。
     *
     * `compareAndSet` 是刻意的：**已被 [stop] 停掉时返回 false 且不上报** ——
     * 否则读线程在正常停止时也会报一次"失败"，用户会看到莫名其妙的红字。
     */
    private fun fail(onError: ((String) -> Unit)?, message: String): Boolean {
        if (!running.compareAndSet(true, false)) return false

        synchronized(readers) {
            readers.forEach { it.stop() }
            readers.clear()
        }
        ShizukuBridge.reportDiagnostic(message)
        AppLog.e(TAG, message)
        onError?.invoke(message)
        return true
    }

    /**
     * 组装 `getevent` 命令的一种参数组合。
     *
     * 之所以做成"可变体"而不是写死：不同 ROM 的 `getevent` 对参数的支持
     * 并不一致，写死一种就等于把兼容性赌在那一组参数上。
     * 两种输出格式 [GeteventParser] 都支持，所以可以放心地换着试。
     */
    private data class CommandVariant(val label: String, val build: (String) -> String)

    private companion object {
        const val TAG = "ShizukuGetevent"

        const val GETEVENT = "/system/bin/getevent"

        /** shell 身份下列设备；错误输出丢掉，避免污染解析 */
        const val LIST_COMMAND = "ls /dev/input/event* 2>/dev/null"

        val DEVICE_PATTERN = Regex("""/dev/input/event\d+""")

        /**
         * 命令前置的握手片段：让进程**自报 pid**。
         *
         * ⚠️ 顺序不能颠倒：`cat` 必须在 `exec` 之前，因为 `exec` 会把当前进程
         * 替换成目标程序，pid 不变但 shell 已经没了。
         *
         * ⚠️ `2>&1` 必须带上：`getevent` 的失败原因（用法提示、"could not open …"）
         * 经常走 stderr。不合并的话它会被丢掉，我们就只剩"进程退出了"
         * 这一句废话 —— 这正是排查卡住过的原因。
         */
        const val HANDSHAKE = "cat /proc/self/stat; exec "

        /**
         * 依次尝试的参数组合。
         *
         * ⚠️ 注意 `$GETEVENT <一个设备>` —— **只传一个设备**。
         * `getevent` 的用法里写的是 `[device]`（单数），传多个它会直接
         * 打印用法并退出（踩过，见类注释）。
         *
         * ⚠️ 顺序：**先不带 `-q`**。
         * 不带 `-q` 时每行会带设备前缀（`/dev/input/event3: 0001 …`），
         * 那是 `getevent` 最基本、最不可能出问题的输出格式；
         * `-q` 是优化项（少一列、少一次字符串处理），放后面。
         */
        val COMMAND_VARIANTS: List<CommandVariant> = listOf(
            CommandVariant(label = "默认参数") { device ->
                HANDSHAKE + "$GETEVENT 2>&1 $device"
            },
            CommandVariant(label = "quiet 参数") { device ->
                HANDSHAKE + "$GETEVENT -q 2>&1 $device"
            },
        )

        /**
         * 同时启动的设备数上限。
         *
         * 不设上限的话，某些机器有十几个输入节点，会一口气发十几个
         * binder 调用，让 Shizuku 服务端同时 spawn 十几个进程 —— 容易把它压垮。
         */
        const val MAX_CONCURRENT_STARTS = 6

        /** 探活轮询间隔 */
        const val POLL_INTERVAL_MS = 20L

        /** 握手等待上限：读 pid 是阻塞的，必须有兜底 */
        const val HANDSHAKE_TIMEOUT_MS = 1_000L

        /** 失败证据最多收集多少个字符（避免把日志刷爆） */
        const val MAX_EVIDENCE_CHARS = 400
    }
}

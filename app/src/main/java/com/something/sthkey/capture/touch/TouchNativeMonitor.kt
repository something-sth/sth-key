package com.something.sthkey.capture.touch

import com.something.sthkey.capture.gamepad.GamepadProcess
import com.something.sthkey.capture.gamepad.GamepadProcessLauncher
import com.something.sthkey.core.log.AppLog
import java.io.BufferedReader
import java.io.File

/**
 * 原生**触屏** monitor 的托管。
 *
 * ============================================================
 * 它做的事（与 [com.something.sthkey.capture.gamepad.GamepadNativeMonitor] 一一对应）
 * ============================================================
 * 1. 找到 `nativeLibraryDir` 里的 `libtouchmonitor.so`；
 * 2. 把它复制到 `/data/local/tmp` 并 `exec`；
 * 3. 按行读它的 stdout，交给 [TouchNativeProtocol] 解析；
 * 4. 把解析结果通过回调送出去。
 *
 * **所有设备相关的麻烦事（轴能力、设备挑选、MT 协议 A/B、SYN 聚合）
 * 都在 native 里**，这里不重复做。
 *
 * ============================================================
 * ⚠️⚠️ 为什么直接复用 Gamepad* 那几个类型（而不是改个通用的名字）
 * ============================================================
 * [GamepadProcess] / [GamepadProcessLauncher] 以及两个实现
 * （root 走 `su -c`、Shizuku 走远端 shell）**本身与手柄毫无关系** ——
 * 它们只回答"谁来起这个 helper 进程"。
 *
 * ⚠️ 抽出来的时候就是为了这件事:当年手柄只有 root 能用（Shizuku 用户
 * 摇杆永远不动），根因就是把 `ProcessBuilder("su", …)` 写死在 monitor 里。
 * 抽成接口之后，"换一条通道"变成了**换一个实现**，与业务无关。
 *
 * ⚠️ 所以触屏**直接复用它**，而不是再抄一份 `TouchProcessLauncher`:
 * 抄一份的代价是"以后修启动流程要改两处"，而那正是这个接口要消灭的东西。
 *
 * ⚠️ 代价是名字里带 `Gamepad`（读起来别扭）。**但这个改名要单独做** ——
 * 它是纯重命名，与触屏功能混在一起改的话，出了问题是哪种原因都说不清。
 * 见下面那条 TODO。
 *
 * TODO(重命名): 两个功能都稳定之后，把
 * `GamepadProcessLauncher` / `GamepadProcess` / `RootGamepadLauncher` /
 * `ShizukuGamepadLauncher` 改成 `Privileged*` —— **单独一次提交**，只改名。
 *
 * ============================================================
 * ⚠️ 与 `getevent` 那条路的关系（重要，别重复处理）
 * ============================================================
 * `getevent`（[com.something.sthkey.capture.RootInputSource]）那条路
 * **会把触屏的 `EV_KEY`/`EV_ABS` 也读进来** —— 而它的解析器是
 * "通用三元组"，不区分设备。
 *
 * ⚠️ 也就是说:**不加处理的话，一次触摸会同时被两条路看到**。
 * 现在 `getevent` 那条路只把关心的键码转成按键事件，触屏的 `BTN_TOUCH`
 * 不在其中，所以**暂时不会撞**。但以后往 `getevent` 那条路加东西时
 * 要记住这件事。
 *
 * ============================================================
 * ⚠️ 触屏与手柄的一个根本差别:它**永远在线**
 * ============================================================
 * 手柄可以没插，所以"等待手柄"是常态。而**触屏就是屏幕本身**，
 * 一定在。所以 `waiting-touch` 只可能意味着**设备识别失败**
 * （打分没挑中），而不是"用户没操作"—— 提示文案上要区分开，
 * 否则用户会以为是自己的问题。
 *
 * @param context 应用上下文 —— 用它拿 nativeLibraryDir
 * @param launcher 谁来起 helper 进程（root / Shizuku）
 * @param onReady 认到触屏（参数是设备描述，形如 `event1 | 名字 | MT+SLOT+DIRECT`）
 * @param onDisconnected 断开或回到"未就绪"
 * @param onFrame 收到一帧触点
 * @param onStatus 状态变化（给人看的文案，**已经过 native 文案映射**）
 */
internal class TouchNativeMonitor(
    private val context: android.content.Context,
    private val launcher: GamepadProcessLauncher,
    private val onReady: (String) -> Unit,
    private val onDisconnected: () -> Unit,
    private val onFrame: (TouchNativeProtocol.Frame) -> Unit,
    private val onStatus: (String) -> Unit = {},
) {

    @Volatile
    private var process: GamepadProcess? = null

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var running = false

    /** 连上过没有 —— 用来判断 `touch-disconnected` 是**首次**还是真的断了 */
    @Volatile
    private var everReady = false

    /** 本次会话是不是真的读到过输出（区分"启动失败"与"跑完一轮"） */
    @Volatile
    private var sawOutput = false

    /**
     * 触屏当前是否就绪。
     *
     * ⚠️ 与手柄那个同义:**native 报过 `touch-ready` 且没断开**。
     * 未就绪期间**不采信任何触点数据** ——
     * 参见 [GamepadNativeMonitor] 里那段"`ready` 之前的数据一律不采信"
     * 的血泪史（那边是假的扳机值把锁存顶死了）。
     */
    @Volatile
    var ready: Boolean = false
        private set

    fun isRunning(): Boolean = running

    /**
     * 启动。（**非阻塞**，内部起线程）
     *
     * @return 启动失败的原因；成功返回 null
     */
    fun start(): String? {
        if (running) return null
        running = true
        sawOutput = false

        val helper = prepareHelper()
        if (helper == null) {
            running = false
            return "触屏 helper 不在 nativeLibraryDir（检查 jniLibs 有没有打进 APK）"
        }

        AppLog.i(
            TAG,
            "启动原生触屏 monitor（通道 ${launcher.label}）：" +
                "${helper.absolutePath}（${helper.length()} 字节，" +
                "可读=${helper.canRead()} 可执行=${helper.canExecute()}）",
        )

        worker = Thread({ runLoop(helper) }, "SthKey-Touch").apply {
            isDaemon = true
            start()
        }
        return null
    }

    /** 停止并释放；**可重复调用** */
    fun stop() {
        if (!running) return
        running = false
        process?.let { runCatching { it.close() } }
        process = null
        worker?.interrupt()
        worker = null
        ready = false
        /* ⚠️ 停的时候要把"手指都抬起"报出去，否则下游会留着按下的状态 */
        onFrame(TouchNativeProtocol.Frame.EMPTY)
    }

    /**
     * 重试循环:一条会话结束就歇一会儿再起一条。
     *
     * ⚠️ 与手柄同构 —— 退避重试而不是原地重来:
     * helper 起不来时立刻重试会变成"每秒起一条 su 进程"，把系统拖垮。
     */
    private fun runLoop(helper: File) {
        while (running) {
            try {
                runSession(helper)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Throwable) {
                /*
                 * ============================================================
                 * ⚠️⚠️ 「关流导致的读中断」**不是异常**，别记进日志
                 * ============================================================
                 * 现象（用户实测）:点「停止采集」时日志里多出一行
                 *
                 * > 触屏 monitor 会话异常：InterruptedIOException:
                 * > read interrupted by close() on another thread
                 *
                 * ⚠️ 成因很直白:读线程**阻塞在 `readLine()`** 等数据，
                 * 而 [stop] 从**另一个线程**关掉了那条流 —— Java 的流实现
                 * 就会把这次阻塞的读抛成 `InterruptedIOException`。
                 *
                 * ⚠️ **这是关闭流的标准行为，不是错误**。判据是
                 * [running] —— 为 `false` 就说明"是我们自己要停"，
                 * 那次中断正是预期结果。
                 *
                 * ⚠️ 但也不能一律吞掉:会话**在运行中**异常结束
                 * （helper 崩了、权限被撤）必须留下痕迹，
                 * 否则表现成"采集悄悄没了"，比一行噪声难查得多。
                 *
                 * ⚠️ 这道防护手柄那边**本来就有**（`GamepadNativeMonitor`
                 * 的 runLoop），是我写触屏时漏了。两处判据保持一致。
                 *
                 * ⚠️ 捕 `Throwable` 而不是 `Exception`:与手柄那条一致。
                 * 会话里可能抛出 `Error`（比如 OOM），
                 * 不捕的话整个工作线程会带着异常死掉、再也没有重试。
                 */
                if (running) {
                    AppLog.w(
                        TAG,
                        "触屏 monitor 会话异常：${e.javaClass.simpleName}: ${e.message}",
                    )
                } else {
                    AppLog.i(TAG, "触屏 monitor 已停止（关流中断了读，属预期）")
                }
            }

            if (!running) break

            ready = false
            onDisconnected()
            try {
                Thread.sleep(RETRY_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        AppLog.i(TAG, "触屏 monitor 退出")
    }

    /** 一条会话 = 起进程、读到底、收尾 */
    private fun runSession(helper: File) {
        val target = "$TEMP_PREFIX${android.os.Process.myUid()}"

        /*
         * ⚠️ 命令与手柄那条**逐字同构**（只换了临时文件名）。
         *
         * 为什么要"复制到 /data/local/tmp 再执行":
         * `nativeLibraryDir` 里的文件从 APK 解包出来未必带 `+x`，
         * 而 shell 又不能直接在 `/data/app/…` 上 `chmod`（那要写权限）。
         *
         * ⚠️ 那道 `test -s`（存在**且非空**）不能省:
         * 复制失败时会留下一个 0 字节文件，而 `exec` 一个空文件
         * **没有任何输出** —— 表现是"helper 没有输出"，毫无线索。
         */
        val command = buildString {
            append("rm -f ").append(quote(target)).append("; ")
            append("cat ").append(quote(helper.absolutePath))
            append(" > ").append(quote(target))
            append(" && test -s ").append(quote(target))
            append(
                " || { echo 'STATUS helper-copy-failed 复制 helper 失败（源文件读不到？）'; exit 1; }; ",
            )
            append("chmod 700 ").append(quote(target))
            append(" && exec ").append(quote(target))
        }

        val proc = launcher.start(command)
        if (proc == null) {
            AppLog.w(TAG, "触屏 helper 起不来（${launcher.label}）")
            return
        }
        process = proc

        var lineCount = 0
        try {
            proc.inputStream.bufferedReader().use { reader: BufferedReader ->
                while (running) {
                    val line = reader.readLine() ?: break
                    lineCount++
                    sawOutput = true
                    handleLine(line)
                }
            }
        } finally {
            if (process === proc) process = null
            runCatching { proc.close() }
            ready = false
            onDisconnected()
        }

        if (running && lineCount == 0) {
            /*
             * ⚠️ 必须说清**是哪条通道**起不来 ——
             * `su` 被拒与 Shizuku 未授权是两件完全不同的事，
             * 排查方向也完全不同。
             */
            AppLog.w(
                TAG,
                "触屏 helper 没有任何输出（通道 ${launcher.label}）—— " +
                    "可能是权限被拒、或 helper 无法执行",
            )
        }
    }

    private fun handleLine(line: String) {
        TouchNativeProtocol.parseFrame(line)?.let { frame ->
            /*
             * ⚠️ `touch-ready` 之前的数据**一律不采信**。
             *
             * 手柄那边这条规则是血泪换来的（开机那段的脏轴值把扳机锁存
             * 顶死了，按下去反而显示未按下、重启监听也救不回来）。
             *
             * 触屏这边同样的风险要小（没有"锁存"），但**未就绪时本来
             * 就不该有触点** —— 真收到了说明设备还在初始化，
             * 那几帧的坐标不可信。
             */
            if (!ready) return
            onFrame(frame)
            return
        }

        if (TouchNativeProtocol.isStatus(line)) {
            /* 状态文案统一走协议层映射，别在这里散着写中文 */
            val text = TouchNativeProtocol.statusText(line)
            onStatus(text)
        }

        when {
            TouchNativeProtocol.isReady(line) -> {
                ready = true
                everReady = true
                val detail = TouchNativeProtocol.readyDetail(line)
                AppLog.i(TAG, "触屏就绪：$detail")
                onReady(detail)
            }

            /*
             * ⚠️ 「还在找触屏」**不记日志** ——
             * helper 未就绪时每 900ms 上报一次，记了就会刷屏。
             * 但状态**照样要清**（不找的时候不该有触点数据）。
             *
             * ⚠️ 与手柄不同的是:触屏一定插着，所以这个状态意味着
             * **设备识别失败**而不是"用户还没操作"。文案上已在
             * [TouchNativeProtocol.statusText] 里区分（"等待触屏设备"
             * 而不是"请连接触屏"）。
             */
            TouchNativeProtocol.isWaiting(line) -> {
                ready = false
                onDisconnected()
            }

            TouchNativeProtocol.isDisconnected(line) -> {
                ready = false
                /* ⚠️ 只有真的认出过又断掉才记 —— 那才是有意义的事件 */
                if (everReady) AppLog.i(TAG, "触屏已断开")
                onDisconnected()
            }

            /*
             * 其它行原样记下来 —— 前几行尤其重要:
             * helper 起不来时它打的那几句是唯一能说明原因的线索。
             */
            else -> if (loggedLines < PROBE_LINES) {
                loggedLines++
                AppLog.i(TAG, "touch-helper[$loggedLines] ${line.take(MAX_LOG_LINE)}")
            }
        }
    }

    private var loggedLines = 0

    /**
     * 取 helper 的真实路径 —— **`nativeLibraryDir`**。
     *
     * ⚠️ 不能改成 `filesDir` 或"先从 assets 复制出来":
     * 那条路在 Shizuku 下**必然失败**（普通 shell 穿不过应用私有目录的
     * 0700 权限），而 root 下一直好用 —— 于是表现成"只有 root 用户能用"。
     * 这一段手柄那边错了两个来回，见 [GamepadNativeMonitor] 的注释。
     */
    private fun prepareHelper(): File? {
        val dir = context.applicationInfo?.nativeLibraryDir
        if (dir.isNullOrBlank()) {
            AppLog.e(TAG, "拿不到 nativeLibraryDir，无法定位触屏 helper")
            return null
        }

        val helper = File(dir, HELPER_NAME)
        if (!helper.isFile) {
            /*
             * ⚠️ 这里失败通常意味着**打包配置出问题了**（`.so` 没进 APK），
             * 而不是权限问题 —— 要明确指出"去检查 jniLibs"。
             */
            AppLog.e(
                TAG,
                "触屏 helper 不在 nativeLibraryDir（$dir）—— " +
                    "检查 src/main/jniLibs/arm64-v8a/$HELPER_NAME 是否打进 APK 了",
            )
            return null
        }

        /*
         * ⚠️ 显式补权限位:系统解包出来通常已是 0755，但不同 ROM 不保证，
         * 而 root 与 Shizuku 两条通道 `exec` 都需要可执行位。
         */
        helper.setExecutable(true, false)
        helper.setReadable(true, false)
        return helper
    }

    /** 单引号包路径（这个字符串会交给 shell 执行） */
    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private companion object {
        const val TAG = "TouchNative"

        /** `jniLibs/arm64-v8a/` 里的文件名 —— 与 `build-native.sh` 的输出名必须一致 */
        const val HELPER_NAME = "libtouchmonitor.so"

        /**
         * 临时文件前缀。
         *
         * ⚠️ 名字里带 **uid**（拼在末尾）:多用户 / 多开时不会互相覆盖，
         * 也不会与手柄那个 helper 撞车。
         */
        const val TEMP_PREFIX = "/data/local/tmp/sthkey_touch_"

        /** 会话失败后的重试间隔 */
        const val RETRY_MS = 800L

        /** 未识别行最多记几行（前几行最能说明"为什么起不来"） */
        const val PROBE_LINES = 8
        const val MAX_LOG_LINE = 160
    }
}

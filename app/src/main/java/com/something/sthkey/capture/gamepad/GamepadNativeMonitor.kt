package com.something.sthkey.capture.gamepad

import com.something.sthkey.core.log.AppLog
import java.io.BufferedReader
import java.io.File

/**
 * 原生手柄 monitor 的**进程宿主**。
 *
 * ============================================================
 * 它做的事只有四件
 * ============================================================
 * 1. 找到 `nativeLibraryDir` 里的 `libgamepadmonitor.so`；
 * 2. 把它复制到 `/data/local/tmp` 并 `exec`（见下面"为什么要复制"）；
 * 3. 按行读它的 stdout，交给 [GamepadNativeProtocol] 解析；
 * 4. 把解析结果通过回调送出去。
 *
 * ⚠️ 第 2 步"谁去执行"由 [launcher] 决定（root 走 `su`、Shizuku 走远端 shell）——
 * 本类**不再自己起进程**。以前这里写死了 `su`，导致 Shizuku 用户的手柄
 * 摇杆与扳机永远不动（见 [GamepadProcessLauncher] 的说明）。
 *
 * **所有设备相关的麻烦事（轴能力、中点校准、设备挑选、SYN 聚合）
 * 都在 native 里**，这里不重复做 —— 见 [GamepadNativeProtocol] 的类注释。
 *
 * ============================================================
 * ⚠️ 为什么要"复制到 /data/local/tmp 再执行"
 * ============================================================
 * 主要是**可执行位**:`nativeLibraryDir` 里的文件从 APK 解包出来，
 * 未必带 `+x`，而 shell 又不能直接在 `/data/app/…` 上 `chmod`
 * （那要写权限）。所以复制到自己能改的地方，`chmod 700` 之后再 `exec`。
 *
 * ⚠️ 顺手也绕开了另一件事:helper 是**预编译的 `.so`**，
 * 但我们要把它当**可执行文件**跑（不是 `dlopen`），
 * 从 `/data/local/tmp` 跑语义最清楚。
 *
 * ⚠️ 复制目标名里带 **uid**：多用户 / 多开时不会互相覆盖，
 * 而且不会与别的应用的同名 helper 撞车。
 *
 * ============================================================
 * ⚠️ 与 `getevent` 那条路的关系
 * ============================================================
 * **两条路同时在跑，各管一半**:
 *
 * | 来源 | 负责 |
 * |---|---|
 * | `getevent`（[com.something.sthkey.capture.RootInputSource]） | 键盘、鼠标 |
 * | 本类（native） | **手柄的摇杆、扳机、按键** |
 *
 * 之所以不合并成一条:键盘/鼠标走 `getevent` 已经稳定，
 * 而手柄需要的轴能力查询是 `getevent` **给不了**的。
 *
 * ⚠️ 两边都读 `/dev/input`，但**不会重复** —— `getevent` 那条路
 * 只把 `EV_KEY`/`EV_REL` 里我们关心的键码转成事件，
 * 而手柄的轴（`EV_ABS`）在 [com.something.sthkey.capture.CaptureController]
 * 里**已经不再处理**（改由本类提供）。
 */
internal class GamepadNativeMonitor(
    /** 应用上下文 —— 用它拿 nativeLibraryDir */
    private val context: android.content.Context,
    /**
     * 谁来起 helper 进程。
     *
     * ⚠️ **以前这里写死了 `ProcessBuilder("su", "-c", …)`**，
     * 而整个类又只在 root 通道启动 —— 于是 Shizuku 用户（大多数）
     * 的手柄**摇杆与扳机永远不动**（详见 [GamepadProcessLauncher]）。
     *
     * 现在由调用方决定:root 传 [RootGamepadLauncher]、
     * Shizuku 传 [ShizukuGamepadLauncher]。本类只管读流。
     */
    private val launcher: GamepadProcessLauncher,
    private val onReady: (String) -> Unit,
    private val onDisconnected: () -> Unit,
    private val onButton: (code: Int, down: Boolean) -> Unit,
    private val onAxis: (code: Int, value: Float) -> Unit,
) {

    /** 正在运行的进程；[stop] 时销毁它 */
    @Volatile
    private var process: GamepadProcess? = null

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var running = false

    /** 连上过没有 —— 用来判断 "ready" 是**首次**还是重连 */
    @Volatile
    private var everReady = false

    /** 本次会话是不是真的读到过输出（用来区分"启动失败"与"跑完一轮"） */
    @Volatile
    private var sawOutput = false

    fun isRunning(): Boolean = running

    /** 手柄当前是否就绪（native 报过 ready 且没断开） */
    @Volatile
    var ready: Boolean = false
        private set

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
            return "手柄 helper 不在 nativeLibraryDir（检查 jniLibs 有没有打进 APK）"
        }

        AppLog.i(
            TAG,
            "启动原生手柄 monitor（通道 ${launcher.label}）：" +
                "${helper.absolutePath}（${helper.length()} 字节，" +
                "可读=${helper.canRead()} 可执行=${helper.canExecute()}）",
        )

        worker = Thread({ runLoop(helper) }, "SthKey-Gamepad").apply {
            isDaemon = true
            start()
        }
        return null
    }

    fun stop() {
        if (!running) return
        running = false
        /*
         * 先杀进程:读线程阻塞在 readLine 上，不杀进程它醒不过来。
         *
         * ⚠️ [GamepadProcess.close] 两边实现都幂等，所以这里与
         * `runSession` 的 `finally` 里各关一次是安全的
         * （谁先谁后都行，也不会重复杀）。
         */
        runCatching { process?.close() }
        process = null
        worker?.interrupt()
        worker = null
        ready = false
        everReady = false
    }

    /**
     * 取 helper 的真实路径 —— **`nativeLibraryDir`**，不是 `filesDir`。
     *
     * ============================================================
     * ⚠️⚠️ 为什么必须走 `nativeLibraryDir`（这里错了两个来回）
     * ============================================================
     * 早先的做法是"把 helper 放进 `assets/`，运行时解包到 `filesDir`,
     * 再 `chmod` 之后让 shell `cat` 过去"。**那条路在 Shizuku 下必然失败**,
     * 而 root 下一直好用 —— 因为:
     *
     * | 身份 | 能不能读 `filesDir` 里的文件 |
     * |---|---|
     * | `su` 派生的 shell（**root**） | ✅ 无视一切权限 |
     * | Shizuku 的 shell（**普通 shell**） | ❌ **永远不能** |
     *
     * ⚠️ 用户日志里的证据（Shizuku 模式）:
     *
     * ```
     * 启动原生手柄 monitor（通道 Shizuku）：…/files/libgamepadmonitor.so（32072 字节，可读=true 可执行=true）
     * helper[1] STATUS helper-copy-failed 复制 helper 失败（源文件读不到？）
     * ```
     *
     * 文件是好的、32KB、AOSP 也认为它可读，但 shell `cat` 不到 ——
     * 因为 `filesDir` 在当前 SELinux 策略下**不允许 `shell` 域读**
     * （app 私有数据的标签是 `app_data_file`）。
     *
     * ⚠️ **这一点是 `chmod` 改不动的**:我先试过给目录加 `o+x`
     * （`makeReadableByShell`），DAC 权限确实加上了，**但依然读不到** ——
     * 因为拦路的是 **MAC（SELinux）**，不是 DAC。这也是这次多绕了一圈的原因。
     *
     * ============================================================
     * `nativeLibraryDir` 为什么就行
     * ============================================================
     * 它是 `/data/app/…/lib/arm64/`，标签是 `system_file` ——
     * **从 `shell` 域可读**。（参考实现 Axon-Input 就是这么做的:
     * `ApplicationInfo.nativeLibraryDir + "/libgamepadmonitor.so"`。）
     *
     * ⚠️ v2.5.1 曾断言"`jniLibs` 这条路在 AGP 9.2.1 上不成立" ——
     * **那个结论是错的**。实测（`clean` 之后重新打包）:
     *
     * | 配置 | APK 里的结果 |
     * |---|---|
     * | 只把 `.so` 放进 `src/main/jniLibs/arm64-v8a/` | `lib/arm64-v8a/…so`，**未压缩（100%）** ✅ |
     *
     * **不需要 `useLegacyPackaging`，也不需要 `extractNativeLibs`** ——
     * AGP 9 默认就把 `.so` 存成未压缩，系统安装时会把它**解包成真实文件**
     * 放进 `nativeLibraryDir`。
     *
     * ⚠️ 反倒是那句 `android:extractNativeLibs="true"` 会把**第三方**库
     * 压掉（注释里记着 `libandroidx.graphics.path.so` 从 10,096 变 4,386）——
     * 所以**不要加它**。
     *
     * ⚠️ 当年之所以看不出问题，是因为**只测了 root**:
     * root 能无视 SELinux 从 `filesDir` 读，所以 assets 那条路"看起来能用"。
     * 换成 `nativeLibraryDir` 之后，**两条通道走同一个文件**，
     * 不再有"root 能跑、Shizuku 不能"这种分裂。
     */
    private fun prepareHelper(): File? {
        val dir = context.applicationInfo?.nativeLibraryDir
        if (dir.isNullOrBlank()) {
            AppLog.e(TAG, "拿不到 nativeLibraryDir，无法定位手柄 helper")
            return null
        }

        val helper = File(dir, HELPER_NAME)
        if (!helper.isFile) {
            /*
             * ⚠️ 这里失败通常意味着**打包配置出问题了**（`.so` 没进 APK），
             * 而不是权限问题 —— 所以要明确指出「去检查 jniLibs」，
             * 而不是让人又去怀疑 shell 权限。
             */
            AppLog.e(
                TAG,
                "手柄 helper 不在 nativeLibraryDir（$dir）—— " +
                    "检查 src/main/jniLibs/arm64-v8a/$HELPER_NAME 是否打进 APK 了",
            )
            return null
        }

        /*
         * ⚠️ 仍然显式补权限位。
         *
         * 系统解包出来的文件通常已经是 0755，但**不同 ROM 不保证** ——
         * 而对 root 与 Shizuku 两条通道，`exec` 都需要可执行位。
         * 这里顺手设上，代价是两次 stat。
         */
        helper.setExecutable(true, false)
        helper.setReadable(true, false)

        return helper.takeIf { it.length() > 0L }
    }

    private fun runLoop(helper: File) {
        while (running) {
            try {
                runSession(helper)
            } catch (e: Throwable) {
                if (running) {
                    AppLog.w(TAG, "手柄 monitor 会话异常：${e.javaClass.simpleName}: ${e.message}")
                }
            }

            if (!running) break

            /*
             * 会话结束（helper 退出 / 被杀）。
             *
             * ⚠️ 退避重试，不要原地立刻重来:helper 起不来时
             * 立刻重试会变成"每秒起一条 su 进程"，把系统拖垮。
             */
            ready = false
            onDisconnected()
            try {
                Thread.sleep(RETRY_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        AppLog.i(TAG, "手柄 monitor 退出")
    }

    /** 一条会话 = 起进程、读到底、收尾 */
    private fun runSession(helper: File) {
        val target = "$TEMP_PREFIX${android.os.Process.myUid()}"

        /*
         * ⚠️ 命令用单引号包住路径。`nativeLibraryDir` 里含包名，
         * 而路径本身不会有引号 —— 但仍要引住，避免空格之类的问题
         * （这个字符串会交给 shell 执行）。
         */
        val command = buildString {
            append("rm -f ").append(quote(target)).append("; ")
            append("cat ").append(quote(helper.absolutePath))
            append(" > ").append(quote(target))
            /*
             * ============================================================
             * ⚠️ 复制失败要**说出来**，不能让它静默地 exec 一个空文件
             * ============================================================
             * 以前这里是 `&& chmod 700 … && exec …`。复制失败时
             * （比如源文件读不到）会留下一个 **0 字节文件**，
             * 而 `exec` 一个空文件**没有任何输出** —— 表现就是
             * "手柄 helper 没有任何输出"，排查时毫无线索。
             *
             * 这正是 Shizuku 通道那个 bug 的伪装:根因是
             * `filesDir` 目录 `0700` 让 shell 穿不过去（见
             * 应用私有目录），但日志里只有一句"没有输出"。
             *
             * 加一道 `test -s`（-s = 存在**且非空**）之后，
             * 失败会直接在调试页显示"复制 helper 失败"——
             * 一眼就能看出是哪一步断的。
             */
            append(" && test -s ").append(quote(target))
            append(" || { echo 'STATUS helper-copy-failed 复制 helper 失败（源文件读不到？）'; exit 1; }; ")
            append("chmod 700 ").append(quote(target))
            append(" && exec ").append(quote(target))
        }

        /*
         * ⚠️ 起进程交给 [launcher] —— 命令字符串两边通用，
         * 只有"谁来执行它"不同（root 走 `su`、Shizuku 走远端 shell）。
         */
        val proc = launcher.start(command)
        if (proc == null) {
            AppLog.w(TAG, "手柄 helper 起不来（${launcher.label}）")
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
            /* 断开时把状态归零，避免悬浮窗上留着"最后按下的键" */
            ready = false
            onDisconnected()
        }

        if (running && lineCount == 0) {
            /*
             * ⚠️ 这句话要说清**是哪条通道**起不来 ——
             * `su` 被拒与 Shizuku 未授权是两件完全不同的事，
             * 排查方向也完全不同。
             */
            AppLog.w(
                TAG,
                "手柄 helper 没有任何输出（通道 ${launcher.label}）—— " +
                    "可能是权限被拒、或 helper 无法执行",
            )
        }
    }

    private fun handleLine(line: String) {
        GamepadNativeProtocol.parseState(line)?.let { state ->
            current = state

            /*
             * ============================================================
             * ⚠️⚠️ `gamepad-ready` 之前的数据**一律不采信**（这是最强的一道防线）
             * ============================================================
             * 手柄**开机那一小段**里，native 侧还在做设备初始化与多轴能力
             * 重探 —— 那期间吐出来的轴值**本来就不可信**
             * （用户的原话:"单独动哪个摇杆都不会有事，**两个一起动就会复现**"，
             * 因为多轴活动才会触发那次重探）。
             *
             * ⚠️ 老实现把这个分支放在 [ready] 判断**之前**，等于把那段脏数据
             * 当成真实输入用了 —— 于是一串假的扳机值把扳机锁存顶上去，
             * 而那个锁存当时**没有任何出口**，就成了"LT/RT 永远按下、
             * 按下去反而显示未按下、重启监听也没用"。
             *
             * [ready] 这个标志本来就是为这件事准备的（见它的声明:
             * "native 报过 ready 且没断开"），只是这个分支**没看它**。
             *
             * ⚠️ 注意 `ready` 是**每次会话独立**的:每次连上都重新置 false，
             * 直到再次收到 `gamepad-ready`。而"就绪"那一刻我们还会
             * [resetTriggerState] 复位一次，随后第一条真实数据就把状态
             * 落到真值上 —— 所以**不需要**给这段等待加时间窗口。
             */
            if (!ready) return

            dispatch(state)
            return
        }

        when {
            GamepadNativeProtocol.isReady(line) -> {
                ready = true
                everReady = true
                val detail = GamepadNativeProtocol.readyDetail(line)
                AppLog.i(TAG, "手柄就绪：$detail")
                onReady(detail)
            }

            /*
             * ============================================================
             * ⚠️ 「还在等手柄接入」**不记日志**
             * ============================================================
             * 用户的原话:"不连手柄的时候调试页也一直在输出手柄断开等待重新连接，
             * 能不能把这个去掉，手柄只是一个功能而已"。
             *
             * 根因是这两种状态以前共用一个 [GamepadNativeProtocol.isDisconnected]，
             * 于是"还没插手柄"也被说成"断开"，而且 helper 会**反复上报**它 ——
             * 每 800ms（[RETRY_MS]）一条，调试页就没法看了。
             *
             * ⚠️ 状态**照样要清**（`ready = false` + [onDisconnected]）——
             * 不连手柄时本来就不该有轴数据。去掉的只是那行日志。
             */
            GamepadNativeProtocol.isWaiting(line) -> {
                ready = false
                onDisconnected()
            }

            GamepadNativeProtocol.isDisconnected(line) -> {
                ready = false
                /*
                 * ⚠️ 只有**真的接上过又拔掉**才记 —— 那是有意义的事件。
                 *
                 * 判据用 [everReady]:从没就绪过的"断开"就是"还没插"，
                 * 那是常态，不该占调试页。
                 */
                if (everReady) AppLog.i(TAG, "手柄已断开")
                onDisconnected()
            }

            /*
             * 其它行原样记下来 —— 前几行尤其重要:
             * helper 起不来时它打的那几句（比如 "no gamepad"）
             * 是唯一能说明原因的线索。
             */
            else -> if (loggedLines < PROBE_LINES) {
                loggedLines++
                AppLog.i(TAG, "helper[$loggedLines] ${line.take(MAX_LOG_LINE)}")
            }
        }
    }

    /** 上一次的状态，用来做**去抖**（只在变化时回调） */
    private var current: GamepadNativeProtocol.State? = null
    private var loggedLines = 0

    /** 上一次发出的按键集合与轴值 */
    private val previousButtons = mutableSetOf<Int>()
    private val nextButtons = mutableSetOf<Int>()
    private var lastLx = 0
    private var lastLy = 0
    private var lastRx = 0
    private var lastRy = 0
    private var lastLt = 0
    private var lastRt = 0

    /**
     * 把一份完整状态**差分**成事件。
     *
     * ============================================================
     * ⚠️ 为什么必须差分
     * ============================================================
     * native 每秒输出几十份**完整状态**（每份都带全部按键与轴）。
     * 直接转发的话:
     *
     * - 按键会被重复按下 —— CPS 统计会飞涨，按下动画会反复触发；
     * - 轴会每份都回调 —— 动画被反复打断（就是之前那个"卡顿"）。
     *
     * 所以这里只发**变化**:
     * - 按键只发 DOWN/UP **边沿**；
     * - 轴只在值变了才发。
     */
    private fun dispatch(state: GamepadNativeProtocol.State) {
        /* ---------- 按键边沿 ---------- */

        GamepadNativeProtocol.canonicalButtons(state.buttons, nextButtons)
        previousButtons.forEach { code ->
            if (code !in nextButtons) onButton(code, false)
        }
        nextButtons.forEach { code ->
            if (code !in previousButtons) onButton(code, true)
        }
        previousButtons.clear()
        previousButtons.addAll(nextButtons)

        /* ---------- 轴（只在变化时） ---------- */

        if (state.lx != lastLx) {
            lastLx = state.lx
            onAxis(AXIS_LEFT_X, GamepadNativeProtocol.axisFloat(state.lx))
        }
        if (state.ly != lastLy) {
            lastLy = state.ly
            onAxis(AXIS_LEFT_Y, GamepadNativeProtocol.axisFloat(state.ly))
        }
        if (state.rx != lastRx) {
            lastRx = state.rx
            onAxis(AXIS_RIGHT_X, GamepadNativeProtocol.axisFloat(state.rx))
        }
        if (state.ry != lastRy) {
            lastRy = state.ry
            onAxis(AXIS_RIGHT_Y, GamepadNativeProtocol.axisFloat(state.ry))
        }
        if (state.lt != lastLt) {
            lastLt = state.lt
            onAxis(AXIS_TRIGGER_LEFT, GamepadNativeProtocol.triggerFloat(state.lt))
        }
        if (state.rt != lastRt) {
            lastRt = state.rt
            onAxis(AXIS_TRIGGER_RIGHT, GamepadNativeProtocol.triggerFloat(state.rt))
        }

        /*
         * ⚠️ 最后再无条件对一次扳机的账 —— 这是"LT/RT 卡住"那个 bug
         * 的自愈点。理由见 [reconcileTriggers] 的说明。
         */
        reconcileTriggers(state)
    }

    /**
     * 每条 `GAMEPAD` 行都**对一次扳机的账**（不管值有没有变）。
     *
     * ============================================================
     * ⚠️ 为什么扳机要单独每帧对账，而摇杆不用
     * ============================================================
     * 摇杆是**无状态**的:悬浮窗直接读"当前值"，发不发事件都无所谓 ——
     * 所以 [dispatch] 里"只在变化时发"对它完全够用（而且更省）。
     *
     * ⚠️ 扳机不一样:它在采集层被折成**有状态的按键**
     * （过阈值 = 按下，见 `CaptureController.emitTrigger`），
     * 而那个状态一旦被脏数据顶错，**就没有任何东西会来纠正它**。
     *
     * 用户遇到的就是这个:手柄**开机那一小段**、且**两个摇杆一起动**时
     * （native 侧在做多轴能力重探），会吐出一串不可信的轴值，
     * 把扳机锁存顶上去 —— 之后 LT/RT 永远显示按下、按下去反而显示未按下。
     *
     * native 是预编译的 `.so`，那个源头改不了 —— 所以让这一侧每帧对账，
     * 以**真实值**为准，错了**下一帧就自愈**。
     *
     * ⚠️ "每帧都调"不等于"每帧都发事件" ——
     * `emitTrigger` 只在锁存与真实值不一致时才发，而
     * `setGamepadButton` 又是幂等的（见那边的说明），所以不会产生重复事件。
     *
     * ⚠️ 值仍然要更新（上面那两个 `if`），因为它是"上次报了什么"的记录 ——
     * 不对账时它只是省掉一次没必要的调用。
     */
    private fun reconcileTriggers(state: GamepadNativeProtocol.State) {
        lastLt = state.lt
        lastRt = state.rt
        onAxis(AXIS_TRIGGER_LEFT, GamepadNativeProtocol.triggerFloat(state.lt))
        onAxis(AXIS_TRIGGER_RIGHT, GamepadNativeProtocol.triggerFloat(state.rt))
    }

    /** 单引号包住,并把内部的单引号转义 —— 这个字符串会进 shell */
    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    internal companion object {
        const val TAG = "GamepadNative"

        /**
         * helper 在 **`nativeLibraryDir`** 里的文件名。
         *
         * ⚠️ 必须与 `app/src/main/jniLibs/arm64-v8a/libgamepadmonitor.so`
         * 的文件名**完全一致** —— 系统是按原文件名解包到 `nativeLibraryDir` 的。
         *
         * ⚠️ **不要**改回 `assets/` + 解包到 `filesDir` 那条路 ——
         * Shizuku 的 shell 读不到应用私有目录（见 [prepareHelper] 的长注释）。
         */
        const val HELPER_NAME = "libgamepadmonitor.so"

        private const val TEMP_PREFIX = "/data/local/tmp/sthkey_gamepad_"

        /** 会话异常后的重试间隔 */
        const val RETRY_MS = 800L

        /** 原样记进日志的行数 */
        const val PROBE_LINES = 20

        const val MAX_LOG_LINE = 200

        /* 轴码 */
        const val AXIS_LEFT_X = 0
        const val AXIS_LEFT_Y = 1
        const val AXIS_RIGHT_X = 2
        const val AXIS_RIGHT_Y = 3
        const val AXIS_TRIGGER_LEFT = 4
        const val AXIS_TRIGGER_RIGHT = 5
    }
}

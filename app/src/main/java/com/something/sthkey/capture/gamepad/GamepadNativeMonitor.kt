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
 * 2. 用 `su` 把它复制到 `/data/local/tmp` 并 `exec`（见下面"为什么要复制"）；
 * 3. 按行读它的 stdout，交给 [GamepadNativeProtocol] 解析；
 * 4. 把解析结果通过回调送出去。
 *
 * **所有设备相关的麻烦事（轴能力、中点校准、设备挑选、SYN 聚合）
 * 都在 native 里**，这里不重复做 —— 见 [GamepadNativeProtocol] 的类注释。
 *
 * ============================================================
 * ⚠️ 为什么要"复制到 /data/local/tmp 再执行"
 * ============================================================
 * `nativeLibraryDir` 在 `/data/app/…` 下面，那个目录对 **shell 用户
 * （`su` 派生的进程）没有执行权限** —— SELinux 与目录权限都不允许。
 *
 * 所以要先把它复制到一个双方都能碰的地方（`/data/local/tmp`），
 * 加上可执行位，再 `exec`。
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
class GamepadNativeMonitor(
    /** 应用上下文 —— 用它读 assets、拿 filesDir */
    private val context: android.content.Context,
    private val onReady: (String) -> Unit,
    private val onDisconnected: () -> Unit,
    private val onButton: (code: Int, down: Boolean) -> Unit,
    private val onAxis: (code: Int, value: Float) -> Unit,
) {

    /** 正在运行的进程；[stop] 时销毁它 */
    @Volatile
    private var process: Process? = null

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
            return "手柄 helper 解包失败（assets/$ASSET_PATH）"
        }

        AppLog.i(TAG, "启动原生手柄 monitor：${helper.absolutePath}（${helper.length()} 字节）")

        worker = Thread({ runLoop(helper) }, "SthKey-Gamepad").apply {
            isDaemon = true
            start()
        }
        return null
    }

    fun stop() {
        if (!running) return
        running = false
        /* 先杀进程:读线程阻塞在 readLine 上，不杀进程它醒不过来 */
        try {
            process?.destroy()
        } catch (_: Throwable) {
        }
        process = null
        worker?.interrupt()
        worker = null
        ready = false
        everReady = false
    }

    /**
     * 把 helper 从 **assets** 解包到 `filesDir`，返回那个文件。
     *
     * ============================================================
     * ⚠️ 为什么走 assets 而不是 `jniLibs`
     * ============================================================
     * 最直觉的做法是放进 `src/main/jniLibs/arm64-v8a/`，然后靠
     * `packaging { jniLibs { useLegacyPackaging = true } }` 让它
     * **不压缩**地进 APK（未压缩时系统会把它解包到 `nativeLibraryDir`，
     * 那里是一个真实文件，可以 `chmod` / `exec`）。
     *
     * **实测在 AGP 9.2.1 上这条不成立**:
     *
     * - `useLegacyPackaging = true` 配了也没用，`.so` 照样被压缩；
     * - 在 Manifest 里写 `android:extractNativeLibs="true"` 能编过，
     *   但**反而把原本未压缩的第三方库也压了**
     *   （`libandroidx.graphics.path.so` 从 10,096 变成 4,386），
     *   而我们要的那个仍然是压缩的。
     *
     * 压缩的 `.so` 不会出现在 `nativeLibraryDir` 里 —— 它是运行时
     * 从 APK **内存映射**的。于是"文件不存在"，`exec` 无从谈起。
     *
     * assets 这条路**不依赖任何打包开关**:文件在我们的完全控制下，
     * 解包时机与权限都由自己定。
     *
     * ============================================================
     * ⚠️ 幂等 + 版本校验
     * ============================================================
     * 解包只在"文件不在"或"长度与 assets 里的不一致"时做。
     * 长度变了说明 APK 里的 helper 换了版本（重新编译过），
     * 那时必须重新解包 —— 否则用户装上新 APK 却还在跑旧的 helper，
     * 而那种问题的表现是"改了代码但没生效"，极难查。
     */
    private fun prepareHelper(): File? = try {
        val target = File(context.filesDir, HELPER_NAME)
        val assetLength = context.assets.open(ASSET_PATH).use { it.available().toLong() }

        val needsExtract = !target.isFile || target.length() != assetLength
        if (needsExtract) {
            context.assets.open(ASSET_PATH).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            AppLog.i(TAG, "已解包手柄 helper：${target.length()} 字节")
        }

        /*
         * ⚠️ 必须显式设可执行位。
         *
         * 应用私有目录默认是 `0700`（不对外），而 `su` 派生的 shell
         * 是 **root**，能读能执行 —— 所以这里只需要把文件的权限位补上。
         *
         * 返回值不检查:`setExecutable` 在部分文件系统上会返回 false
         * 但实际已生效；真正的判据是后面 `su` 能不能执行它，
         * 而那有日志。
         */
        target.setExecutable(true, false)
        target.setReadable(true, false)

        target.takeIf { it.isFile && it.length() > 0L }
    } catch (e: Exception) {
        AppLog.e(TAG, "解包手柄 helper 失败", e)
        null
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
            append(" && chmod 700 ").append(quote(target))
            append(" && exec ").append(quote(target))
        }

        val proc = ProcessBuilder("su", "-c", command)
            /* stderr 合并进来:helper 的失败原因常走 stderr */
            .redirectErrorStream(true)
            .start()
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
            try {
                proc.destroy()
            } catch (_: Throwable) {
            }
            /* 断开时把状态归零，避免悬浮窗上留着"最后按下的键" */
            ready = false
            onDisconnected()
        }

        if (running && lineCount == 0) {
            AppLog.w(
                TAG,
                "手柄 helper 没有任何输出 —— 可能是 su 被拒、或 helper 无法执行",
            )
        }
    }

    private fun handleLine(line: String) {
        GamepadNativeProtocol.parseState(line)?.let { state ->
            current = state
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

            GamepadNativeProtocol.isDisconnected(line) -> {
                ready = false
                AppLog.i(TAG, "手柄断开（等待重新连接）")
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
    }

    /** 单引号包住,并把内部的单引号转义 —— 这个字符串会进 shell */
    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    internal companion object {
        const val TAG = "GamepadNative"

        /** 解包后在 `filesDir` 里的文件名 */
        const val HELPER_NAME = "libgamepadmonitor.so"

        /**
         * helper 在 **assets** 里的路径。
         *
         * ⚠️ 与 `app/src/main/assets/gamepad/libgamepadmonitor.so` 必须一致。
         */
        const val ASSET_PATH = "gamepad/libgamepadmonitor.so"

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

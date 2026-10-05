package com.something.sthkey.capture.gamepad

import com.something.sthkey.capture.shizuku.ShizukuShell
import com.something.sthkey.core.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * 一个已经在跑的 helper 进程。
 *
 * ⚠️ 只暴露 [inputStream] 与 [close] 两件事 —— 读的那一侧
 * （[GamepadNativeMonitor]）**不需要知道**这个进程是 `su` 起的还是
 * Shizuku 起的。这正是"接上 Shizuku"能做成一件小事的原因。
 */
internal interface GamepadProcess {
    /** helper 的 stdout（已经合并了 stderr，见各实现） */
    val inputStream: InputStream

    /**
     * 杀掉进程并释放流；**可重复调用**。
     *
     * ⚠️ 必须幂等:失败重试时进程还没交给任何字段持有，
     * 那一路也要能安全收尾，否则会在系统里留下孤儿进程。
     */
    fun close()
}

/**
 * 「谁来起 helper 进程」。
 *
 * ============================================================
 * ⚠️ 存在的理由:手柄以前**只有 root 能用**
 * ============================================================
 * 用户的原话:"我现在自己用 shizuku 也用不了摇杆那些，扳机键也用不了……
 * 不会闪了，但是好像只能检测字母键，摇杆和其他键无反应"。
 *
 * 根因很直白:[GamepadNativeMonitor] 原来把
 * `ProcessBuilder("su", "-c", …)` **写死**在里面，而它又只在
 * `KeyMode.ROOT` 分支启动 —— 于是 Shizuku 用户（也就是**大多数**用户，
 * 因为没有 root 的设备远多于有 root 的）：
 *
 * | 功能 | 为什么 |
 * |---|---|
 * | 手柄**按键** ✅ 能用 | 走 `getevent` 那条路（`EV_KEY`） |
 * | 摇杆 / 扳机 ❌ 不动 | 它们是**轴**（`EV_ABS`），只有本 helper 提供 |
 *
 * ⚠️ 而 Shizuku 的 shell 身份**本来就能读 `/dev/input`** ——
 * 证据是 `getevent` 那条路在无 root 时跑得好好的。
 * 所以缺的从来不是权限，只是**没人用 Shizuku 去起这个 helper**。
 *
 * ============================================================
 * ⚠️ 两条实现的差别只在"起进程"，命令完全一样
 * ============================================================
 * 复制 helper 到 `/data/local/tmp`、`chmod 700`、`exec` —— 这套流程
 * 两边通用（见 [GamepadNativeMonitor] 里拼命令的那段）。
 * 所以这里只抽"谁来执行这条命令"。
 */
internal interface GamepadProcessLauncher {

    /** 用于日志与报错的人话名字（`root` / `Shizuku`） */
    val label: String

    /**
     * 起进程并返回它的输出流。
     *
     * @return 起不来时返回 null（**不抛异常**）—— 调用方在重试循环里，
     *   抛异常会让"这一次失败"变成"整个循环炸掉"。
     */
    fun start(command: String): GamepadProcess?
}

/**
 * 走 `su -c`（root 通道）。
 *
 * ⚠️ 这是 v2.5.1 就有的那条路，行为**一点没变** —— 只是从
 * [GamepadNativeMonitor] 里搬了出来。
 */
internal class RootGamepadLauncher : GamepadProcessLauncher {

    override val label: String = "root"

    override fun start(command: String): GamepadProcess? = try {
        val proc = ProcessBuilder("su", "-c", command)
            /*
             * stderr 合并进来:helper 的失败原因常走 stderr
             * （比如 "no gamepad" / 权限错误）。不合并就看不到原因，
             * 表现是"手柄没反应但日志里什么都没有"。
             */
            .redirectErrorStream(true)
            .start()
        object : GamepadProcess {
            override val inputStream: InputStream = proc.inputStream
            override fun close() {
                runCatching { inputStream.close() }
                runCatching { proc.destroy() }
            }
        }
    } catch (e: Exception) {
        AppLog.w(TAG, "起 root helper 失败：${e.javaClass.simpleName}: ${e.message}")
        null
    }

    private companion object {
        const val TAG = "GamepadLauncher"
    }
}

/**
 * 走 Shizuku 的远端 shell（无 root 的设备）。
 *
 * ============================================================
 * ⚠️ 复用 [ShizukuShell]，不自己写 Binder 事务
 * ============================================================
 * `getevent` 那条路已经在用 [ShizukuShell]，它绕开 Shizuku 的
 * UserService、直接发裸 Binder 事务起一个 `sh` 进程。
 * 再写一份等于把那堆事务号硬编码抄一遍 —— 而注释里明确警告过
 * "升级 Shizuku 前先看这里"。
 *
 * ============================================================
 * ⚠️ 两个必须知道的代价
 * ============================================================
 * 1. **[ShizukuShell.start] 是 suspend 的**，而 [GamepadNativeMonitor]
 *    跑在普通线程上 —— 所以这里用 [runBlocking] 桥一次。
 *    可以接受:那条线程本来就是专用的一次只做一件事
 *    （与 `getevent` 那边 `withContext(Dispatchers.IO)` 等价）。
 * 2. **它只给 stdout，不给 stderr**。所以命令里要自己加 `2>&1`
 *    把 stderr 并过去（见下面 [start]）—— 不加的话 helper 的
 *    失败原因会丢，而那是排查"手柄没反应"唯一的线索。
 *
 * ⚠️ 还有一条（来自 [ShizukuShell] 的说明）:远端进程**随调用方进程一起死**。
 * 应用被杀之后采集不会自动续上 —— 这与 root 那条路的行为差别不大
 * （root 下 `stop()` 也会杀进程），所以不额外处理。
 */
internal class ShizukuGamepadLauncher : GamepadProcessLauncher {

    override val label: String = "Shizuku"

    override fun start(command: String): GamepadProcess? = try {
        /*
         * ⚠️ `2>&1` 必须加:ShizukuShell 只暴露 stdout，
         * 不合并的话 stderr 直接丢掉。
         *
         * 放在**整条命令之后**才管用 —— 中途加只会把前半段的 stderr
         * 并进去，`exec` 之后 helper 自己打的错误仍然会漏。
         */
        val remote = runBlocking {
            withContext(Dispatchers.IO) {
                ShizukuShell.start("$command 2>&1")
            }
        }
        object : GamepadProcess {
            override val inputStream: InputStream = remote.inputStream
            override fun close() = remote.close()
        }
    } catch (e: Exception) {
        AppLog.w(TAG, "起 Shizuku helper 失败：${e.javaClass.simpleName}: ${e.message}")
        null
    }

    private companion object {
        const val TAG = "GamepadLauncher"
    }
}

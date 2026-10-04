package com.something.sthkey.capture.shizuku

import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import com.something.sthkey.core.log.AppLog
import com.something.sthkey.domain.capture.ShizukuBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.Closeable
import java.io.InputStream

/**
 * 借 Shizuku 起一个**长驻 shell 进程**，并把它的 stdout 当流来读。
 *
 * ============================================================
 * 这条通道是干什么的：绕开 Shizuku UserService
 * ============================================================
 * 我们原本用 `bindUserService` 让 Shizuku 帮我们托管一个"用户服务"进程。
 * 但有用户反馈：Shizuku 明明在运行、也授权了，**每次**都在绑定 UserService
 * 时超时；同一台设备上另一个用 Shizuku 的应用却正常。
 *
 * 排查后发现那个应用（[Axon-Input](https://github.com/keepBacon/Axon-Input)）
 * 根本不用 UserService —— 它直接对 Shizuku 发**裸 Binder 事务**调 `newProcess`，
 * 拿一个 `sh` 进程来用。也就是说：**Shizuku 服务端本身是好的，坏的只是
 * UserService 那一段**（怎么 spawn、怎么托管）。
 *
 * 所以这里照做：绕开 UserService，直接用 Shizuku 起 `sh`。
 *
 * ============================================================
 * ⚠️ 为什么是"手写 Parcel"而不是调 SDK 方法
 * ============================================================
 * 因为 SDK 里 `Shizuku.newProcess` 是 **private**：
 *
 * ```java
 * @Deprecated
 * private static ShizukuRemoteProcess newProcess(String[] cmd, String[] env, String dir)
 * ```
 *
 * 字节码证实了这一点（方法名在常量池里，但可见性是 private）。
 * 官方 javadoc 还写明 **"planned to be removed from Shizuku API 14"**。
 *
 * ============================================================
 * ⚠️ 代价与脆弱点（升级 Shizuku 前先看这里）
 * ============================================================
 * 1. **事务号是硬编码的**：`FIRST_CALL_TRANSACTION + 7 = 8`。
 *    它是照 `IShizukuService.aidl` 里的方法顺序数出来的 ——
 *    `newProcess` 前面正好 7 个方法。如果 Shizuku 将来在它**前面**插方法，
 *    这个号就会错位（`transact` 会失败或行为诡异）。
 *    [verifyNewProcessTransaction] 就是为这件事准备的探针。
 * 2. **进程随调用方一起死**：javadoc 写着 "From version 11, like `su`,
 *    the process will be killed when the caller process is dead"。
 *    所以应用被杀之后采集**不会**自动续上（UserService 的 daemon 模式才会）。
 *    这是这条路线的已知代价。
 *
 * 需要的东西不多：`getInputStream` 与 `destroy` 两个事务，
 * 都是 `IRemoteProcess` 的前几个方法，稳定性比 `newProcess` 那个号高。
 */
internal object ShizukuShell {

    private const val TAG = "ShizukuShell"

    /** Shizuku 服务端的接口描述符（写进 Parcel 的第一个字段） */
    private const val SERVICE_DESCRIPTOR = "moe.shizuku.server.IShizukuService"

    /** 远端进程的接口描述符 */
    private const val REMOTE_PROCESS_DESCRIPTOR = "moe.shizuku.server.IRemoteProcess"

    /*
     * IShizukuService.aidl 的方法顺序（数出来的，别凭印象改）：
     *   1 destroy            2 exit               3 getVersion
     *   4 getUid             5 checkPermission    6 checkSelfPermission
     *   7 getApplications    8 newProcess   ← 就是它
     * 事务号 = FIRST_CALL_TRANSACTION + 序号 − 1 = 序号。
     */
    private const val TX_NEW_PROCESS = 8

    /*
     * IRemoteProcess.aidl 的方法顺序（这个更稳，前几个几乎不会变）：
     *   1 getOutputStream  2 getInputStream  3 getErrorStream
     *   4 waitFor          5 exitValue       6 destroy
     */
    private const val PROCESS_TX_GET_INPUT_STREAM = 2
    private const val PROCESS_TX_DESTROY = 6

    /** 一个正在运行的远端 shell 进程 */
    class RemoteShell internal constructor(
        /** 启动它的那条命令；出错时要把它写进日志，否则无从复现 */
        val command: String,
        private val processBinder: IBinder,
        stdout: ParcelFileDescriptor,
    ) : Closeable {

        /**
         * 远端进程的 stdout；**读它就能拿到命令输出**。
         *
         * 只在这里包一次 `AutoCloseInputStream`：包两层会让描述符被关两次，
         * 第二层抛的异常通常在别的线程上，表现为"随机的一个 IO 错误"。
         */
        val inputStream: InputStream = ParcelFileDescriptor.AutoCloseInputStream(stdout)

        @Volatile
        private var closed = false

        /**
         * 关掉流并销毁远端进程；**可重复调用**。
         *
         * 幂等这一点很重要：失败重试时进程还没交给任何字段持有，
         * 必须显式清掉，否则会在服务端留下孤儿进程。
         */
        override fun close() {
            if (closed) return
            closed = true

            // 先关流再销毁进程：反过来的话，读端会先看到"进程没了"的异常
            runCatching { inputStream.close() }
                .onFailure { AppLog.w(TAG, "关闭输出流失败：${it.javaClass.simpleName}") }
            destroyProcess(processBinder)
        }
    }

    /**
     * 起一个 `sh -c <command>` 并返回它的 stdout。
     *
     * @param command 要执行的命令；**不要**在这里拼用户输入（无转义，注入风险自负）
     * @throws RemoteException 拿不到 binder / transact 失败 / 对方没给流
     */
    suspend fun start(command: String): RemoteShell {
        if (!ShizukuBridge.isPermissionGranted()) {
            throw RemoteException("Shizuku 未授权或未运行")
        }

        /*
         * ⚠️ 必须先等 SDK 内部就绪。
         *
         * `Shizuku.getBinder()` 拿到的是原始 binder，`transact` 本身不经过
         * SDK 的 `service` 字段，所以严格说不等也能发 —— 但 attachApplication
         * （把我们的包名与 API 版本告诉服务端）是 SDK 在 binder 到达时做的，
         * 早于它调用会被服务端拒绝。等一次最稳，代价只是一次空转。
         */
        if (!ShizukuBridge.awaitBinderReady()) {
            throw RemoteException("Shizuku binder 未就绪")
        }

        val binder = Shizuku.getBinder() ?: throw RemoteException("拿不到 Shizuku binder")

        val processBinder = createProcess(binder, command)
            ?: throw RemoteException("Shizuku 没有返回进程")

        val stdout = try {
            processInputStream(processBinder)
        } catch (e: Exception) {
            destroyProcess(processBinder)
            throw e
        } ?: run {
            destroyProcess(processBinder)
            throw RemoteException("Shizuku 没有返回输出流")
        }

        AppLog.i(TAG, "远端 shell 已启动：$command")
        return RemoteShell(command = command, processBinder = processBinder, stdout = stdout)
    }

    /**
     * 探针：确认 `newProcess` 的事务号在这个 Shizuku 版本上还对。
     *
     * 跑一条 `echo` 并读回结果 —— 事务号错位时拿到的多半是异常或空输出。
     * 调试页可以调它，这样"Shizuku 升级后这条通道失效"能被一眼看出来，
     * 而不是让用户面对一句"启动失败"。
     */
    suspend fun verifyNewProcessTransaction(): String = try {
        val shell = start("echo sthkey-probe")
        val text = shell.use { it.inputStream.bufferedReader().readLine()?.trim() }
        if (text == "sthkey-probe") "事务号有效（echo 回显正常）" else "回显异常：$text"
    } catch (e: Exception) {
        "验证失败：${e.javaClass.simpleName}: ${e.message}"
    }

    /**
     * 跑一遍完整的自检，返回**给人看的多行报告**。
     *
     * ============================================================
     * 为什么是一串小测试而不是一条命令
     * ============================================================
     * "getevent 零输出就退出"这个现象有很多种可能的原因，而它们在
     * 一条命令里是分不开的。这一串测试按顺序各问一个问题：
     *
     * 1. `echo` 能不能跑              → 事务号对不对、输出通道通不通
     * 2. `getevent -p` 能不能列出设备  → 二进制能不能执行、看不看得见设备
     * 3. 真起一次 getevent             → 它到底是活着还是立刻退
     *
     * 哪一步先出问题，答案就写在哪一步上 —— 不用再猜。
     *
     * ============================================================
     * ⚠️ 这个函数曾经把应用卡死过（两个错误叠在一起）
     * ============================================================
     * **错误一（根本原因）：以为 `getevent` 不带参数会"列出设备后退出"。**
     * 实际上它是**进入监听模式常驻**——所以 `readLines()` 永远等不到
     * 流结束，就一直挂着。
     *
     * **错误二（放大器）：它在主线程上跑。**
     * 调试页用的是 `rememberCoroutineScope()`，它继承的是
     * `AndroidUiDispatcher.Main` —— 主线程一旦被那个永不到来的
     * `readLines()` 阻塞，整个界面就冻住了（ANR）。
     *
     * 修法对应两条，缺一不可：
     * 1. 整个自检跑在 [Dispatchers.IO] 上（**不要再依赖调用方切线程**）；
     * 2. 每一次可能阻塞的读都带超时，并且**用 `head` 之类的命令
     *    确保远端进程一定会结束** —— 不能假设"命令会自己退出"。
     */
    suspend fun runDiagnostics(): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        val startedAt = System.currentTimeMillis()

        // ① 输出通道本身（echo 一定会退出，读一行即可）
        sb.append("① echo：")
        sb.append(
            try {
                val shell = start("echo sthkey-ok")
                val line = shell.use { it.inputStream.bufferedReader().readLine()?.trim() }
                if (line == "sthkey-ok") "正常\n" else "回显异常（得到「$line」）\n"
            } catch (e: Exception) {
                "失败（${e.javaClass.simpleName}: ${e.message}）\n"
            },
        )

        /*
         * ② 二进制能不能执行、能不能看见设备。
         *
         * ⚠️ 用 `getevent -p`（show possible events）而**不是**裸 `getevent`：
         * 裸 `getevent` 会进入监听模式常驻，读它就等于挂死（踩过，见上面）。
         * `-p` 是"列出设备与能力"然后退出，正是这里需要的行为。
         *
         * ⚠️ 再加两道保险，因为"命令会自己退出"这件事不能再靠假设：
         *   - `| head -40`：读够就关管道，让 getevent 收到 SIGPIPE 自己退；
         *   - [READ_TIMEOUT_MS] 超时兜底：万一远端连 head 都没跑到，
         *     我们也不会无限等。
         *
         * 本来还想用 `2>&1` 把 stderr 并进来，但那需要 `sh -c`，
         * 而 `sh -c '...'` 在只跑一条简单命令时会被 exec 优化掉 ——
         * 那样进程就变成 getevent，`head` 也就没机会收尾了。不值得。
         */
        sb.append("② getevent -p（列设备）：")
        sb.append(
            try {
                val shell = start("$GETEVENT_BIN -p | head -40")
                val lines = shell.use { readLinesWithTimeout(it.inputStream.bufferedReader()) }
                when {
                    lines == null -> "**读取超时**（${READ_TIMEOUT_MS}ms 内没读完）\n"
                    lines.isEmpty() -> "**没有任何输出**（二进制没能执行）\n"
                    else -> "输出 ${lines.size} 行，前几行：" +
                        lines.take(4).joinToString(" / ") { it.trim() } + "\n"
                }
            } catch (e: Exception) {
                "失败（${e.javaClass.simpleName}: ${e.message}）\n"
            },
        )

        // ③ 真起一次，看它活不活（getevent 会一直跑，所以读操作必须带超时）
        sb.append("③ 起一次 getevent（单个设备）：")
        sb.append(
            try {
                /*
                 * ⚠️ 用 `cat /proc/self/stat; exec …` 让**这个进程自己**报 pid。
                 *
                 * 早先这里调的是另起一条 shell 的 `echo $$`，拿到的是那条 shell
                 * 的 pid，与 getevent 毫无关系 —— 用它判断死活必然得出错误结论。
                 */
                val shell = start("cat /proc/self/stat; exec $GETEVENT_BIN /dev/input/event0 2>&1")
                val reader = shell.inputStream.bufferedReader()

                // 第一行是握手（pid），必须带超时读，否则会挂住
                val pid = readHandshakeLine(reader)
                delay(PROBE_WINDOW_MS)

                val alive = pid?.let { isProcessAlive(it) }
                val first = readLineOrNull(reader)
                shell.close()

                "pid=${pid ?: "未知"}；${PROBE_WINDOW_MS}ms 后" +
                    when (alive) {
                        false -> "**已退出**"
                        true -> "仍在运行"
                        null -> "存活状态未知"
                    } +
                    if (first != null) "，首行输出：$first" else "，期间无输出"
            } catch (e: Exception) {
                "失败（${e.javaClass.simpleName}: ${e.message}）"
            },
        )

        sb.append("\n（自检耗时 ${System.currentTimeMillis() - startedAt}ms）")
        sb.toString()
    }

    /**
     * 读到流结束为止，超时返回 null。
     *
     * ⚠️ 放在临时线程里读：`readLines()` 是阻塞的，用 [withTimeoutOrNull]
     * **取消不了它**（协程取消不会中断阻塞的 IO）。所以只能另起一个线程，
     * 超时后放弃它、由调用方关流把它唤醒。
     */
    private fun readLinesWithTimeout(
        reader: java.io.BufferedReader,
        timeoutMs: Long = READ_TIMEOUT_MS,
    ): List<String>? {
        var lines: List<String>? = null
        val worker = Thread({
            lines = runCatching { reader.readLines() }.getOrNull()
        }, "ShizukuDiagLines")

        worker.isDaemon = true
        worker.start()
        worker.join(timeoutMs)

        return if (worker.isAlive) null else lines
    }

    /**
     * 带超时地读一行（用于握手 pid）。
     *
     * 同样是另起线程：阻塞读不吃协程取消那一套。
     */
    private fun readHandshakeLine(reader: java.io.BufferedReader): Int? {
        var text: String? = null
        val worker = Thread({
            text = runCatching { reader.readLine()?.trim() }.getOrNull()
        }, "ShizukuDiagHandshake")

        worker.isDaemon = true
        worker.start()
        worker.join(READ_TIMEOUT_MS)

        return if (worker.isAlive) null else text?.toIntOrNull()
    }

    /** 已经 [java.io.BufferedReader.ready] 才读一行，避免阻塞 */
    private fun readLineOrNull(reader: java.io.BufferedReader): String? =
        if (reader.ready()) runCatching { reader.readLine()?.trim() }.getOrNull() else null

    /** getevent 的绝对路径（自检用；输入源里也有一份同样的常量） */
    private const val GETEVENT_BIN = "/system/bin/getevent"

    /** 自检里"起一个进程后等多久再看它活不活"，与输入源的探活窗口保持一致 */
    private const val PROBE_WINDOW_MS = 300L

    /**
     * 自检里单次读取的上限。
     *
     * 两道保险里的一道（另一道是命令里的 `head`）：
     * 远端万一连 `head` 都没跑到，我们也不会无限等下去。
     * 千万不能省 —— 这个自检曾经因为没有兜底把整个应用卡死过。
     */
    private const val READ_TIMEOUT_MS = 2_000L

    /*
     * ============================================================
     * 诊断用的小工具（调试页直接调）
     * ============================================================
     */

    /** Shizuku 主程序是否在运行 */
    fun isShizukuRunning(): Boolean = ShizukuBridge.isRunning()

    /** 本应用是否已获得授权 */
    fun hasPermission(): Boolean = ShizukuBridge.isPermissionGranted()

    /**
     * Shizuku 版本号；取不到时返回 -1（界面按"未知"显示）。
     *
     * `getVersion()` 在 SDK 内部尚未拿到服务时会抛异常，这里吞掉。
     */
    fun shizukuVersion(): Int = try {
        Shizuku.getVersion()
    } catch (_: Exception) {
        -1
    }

    /**
     * 读一个远端 `/proc/<pid>/stat` 来确认**进程是否还活着**。
     *
     * ============================================================
     * 为什么需要它（我的探活曾经是坏的）
     * ============================================================
     * 最早的探活是"读 300ms，没抛异常就算活着"。但**流被对端关闭时
     * `InputStream.ready()` 只是返回 false，并不抛异常** —— 于是进程早就死了，
     * 探活却空转 300ms 之后报"存活"，然后读线程立刻读到 EOF，
     * 界面又变成"已退出"。等于探活白做，闪烁照旧。
     *
     * 判断进程死活不能只看流：**得去问系统**。所以这里另起一条 shell
     * 读 `/proc/<pid>/stat`。虽然多一次往返，但那是**确定性的答案**，
     * 比在流上猜可靠得多。
     *
     * @param pid 远端进程 pid（由 [querySelfPid] 得到）
     * @return true = 进程还在；false = 已经没了
     */
    suspend fun isProcessAlive(pid: Int): Boolean = try {
        val shell = start("cat /proc/$pid/stat 2>/dev/null")
        val line = shell.use { it.inputStream.bufferedReader().readLine() }
        // 读得到就是活着；读不到（空）说明 /proc 里已经没有它了
        !line.isNullOrBlank()
    } catch (e: Exception) {
        // 查不出来时**保守地当作还活着**：宁可漏报一次"已退出"，
        // 也不要把一台正常的设备误判成失败
        AppLog.w(TAG, "查询远端进程存活失败：${e.javaClass.simpleName}")
        true
    }

    /**
     * 启动一个进程并让它**自报 pid**。
     *
     * 用 `echo $$`（shell 自己的 pid）而不是去解析 `/proc/<pid>/stat`：
     * 少一层解析，也就少一类解析错误。
     *
     * ⚠️ 这里必须比 [start] 多截断一层：命令要写成
     * `echo $$` 而不是 `echo $$; exec <真正的命令>` —— 因为 `sh` 在
     * 只有一条简单命令时可能直接 `exec` 掉自己，pid 就变成了真正命令的 pid，
     * 那反而更好（我们想知道的就是"那个进程还在不在"）。
     */
    suspend fun querySelfPid(): Int? = try {
        val shell = start("echo \$\$")
        val text = shell.use { it.inputStream.bufferedReader().readLine()?.trim() }
        text?.toIntOrNull()
    } catch (e: Exception) {
        AppLog.w(TAG, "查询远端 pid 失败：${e.javaClass.simpleName}")
        null
    }

    /*
     * ============================================================
     * 裸 Binder 事务（Parcel 字段顺序照 IShizukuService.aidl）
     * ============================================================
     */

    private fun createProcess(binder: IBinder, command: String): IBinder? {
        if (!binder.pingBinder()) throw RemoteException("Shizuku binder 已失效")

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR)
            // 三个参数：cmd / env / dir —— 顺序与 AIDL 一致
            data.writeStringArray(arrayOf("/system/bin/sh", "-c", command))
            // env 传 null：继承服务端环境，Shizuku 自己会处理好 PATH
            data.writeStringArray(null)
            // dir 传 null：工作目录用默认值
            data.writeString(null)

            if (!binder.transact(TX_NEW_PROCESS, data, reply, 0)) {
                throw RemoteException("newProcess 事务被拒绝（事务号 $TX_NEW_PROCESS 可能已错位）")
            }
            reply.readException()
            return reply.readStrongBinder()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun processInputStream(process: IBinder): ParcelFileDescriptor? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(REMOTE_PROCESS_DESCRIPTOR)
            if (!process.transact(PROCESS_TX_GET_INPUT_STREAM, data, reply, 0)) {
                throw RemoteException("getInputStream 事务失败")
            }
            reply.readException()
            /*
             * 服务端回传的是一个 `ParcelFileDescriptor` 的**读端**
             * （见 ParcelFileDescriptorUtil.pipeFrom：把进程输出泵进管道，
             * 返回 readSide）。先读"有没有值"的 int，再读描述符本体 ——
             * 直接 createFromParcel 会在 Binder 传 null 时读出垃圾。
             *
             * 这里**只返回描述符**，包成流的事交给 RemoteShell 做一次。
             */
            val present = reply.readInt() != 0
            return if (present) ParcelFileDescriptor.CREATOR.createFromParcel(reply) else null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun destroyProcess(process: IBinder) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(REMOTE_PROCESS_DESCRIPTOR)
            if (process.transact(PROCESS_TX_DESTROY, data, reply, 0)) {
                reply.readException()
            }
        } catch (e: Exception) {
            // 进程可能已经自己退出了 —— 这不值得报错
            AppLog.d(TAG, "销毁远端进程时出错（通常无妨）：${e.javaClass.simpleName}")
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}

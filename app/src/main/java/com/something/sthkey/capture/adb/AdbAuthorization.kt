package com.something.sthkey.capture.adb

import android.content.Context
import android.os.Build
import com.something.sthkey.core.log.AppLog
import com.something.sthkey.core.prefs.AppPrefs
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ADB 授权的全局入口。
 *
 * 底层用 libadb（配对 / TLS / mDNS / 明文 TCP 都现成），
 * 这里只做三件事：密钥与证书、连接状态、给采集层开 shell 流。
 *
 * ⚠️ 与 Shizuku 的区别：不需要常驻服务、不需要 .so 启动器；
 * 应用自己连 adbd、开 shell 流，所以连接随应用进程存活。
 */
internal object AdbAuthorization {

    private const val TAG = "AdbAuth"
    const val DEFAULT_PORT = 5555

    private val mutex = Mutex()

    @Volatile
    private var manager: AbsAdbConnectionManager? = null

    @Volatile
    var lastUid: String? = null
        private set

    fun isConnected(): Boolean = manager?.isConnected == true

    fun authorizedKeyLine(context: Context): String = AdbCrypto.load(context).authorizedKeyLine()

    /** 明文 TCP（5555 / 用户 root 开的端口）。 */
    suspend fun connectTcp(
        context: Context,
        host: String = "127.0.0.1",
        port: Int = DEFAULT_PORT,
    ): Result = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val m = ensureManager(context)
                m.disconnect()
                m.setHostAddress(host)
                /* 第一次会弹「允许 USB 调试」，用户可能要几秒才点，超时给足 */
                m.setTimeout(60, TimeUnit.SECONDS)
                AppLog.i(TAG, "开始连接 $host:$port")
                if (!m.connect(port)) {
                    AppLog.w(TAG, "connect 返回 false")
                    return@runCatching Result(false, "连接被拒绝")
                }
                AppLog.i(TAG, "connect 成功，开始 id 验证")
                verify(m)
            }.getOrElse {
                AppLog.w(TAG, "ADB 5555 连接失败：${it.javaClass.simpleName} ${it.message}")
                Result(false, it.message ?: it.javaClass.simpleName)
            }
        }
    }

    /**
     * 无线调试配对：用 6 位码 + mDNS 发现的配对端口。
     */
    suspend fun pairAt(context: Context, host: String, port: Int, code: String): Result =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    val m = ensureManager(context)
                    m.pair(host, port, code)
                    AppLog.i(TAG, "ADB 配对成功：$host:$port")
                    Result(true, "配对成功")
                }.getOrElse {
                    AppLog.w(TAG, "ADB 配对失败：${it.javaClass.simpleName} ${it.message}")
                    Result(false, it.message ?: it.javaClass.simpleName)
                }
            }
        }

    suspend fun pair(context: Context, code: String, timeoutMs: Long = 60_000): Result {
        val resolved = discover(context, AdbMdns.SERVICE_TYPE_TLS_PAIRING, timeoutMs)
            ?: return Result(false, "没找到配对端口，请确认无线调试已打开")
        return pairAt(context, resolved.first, resolved.second, code)
    }

    /** 已配对后连接无线调试（mDNS 自动找连接端口）。 */
    suspend fun connectTls(context: Context, timeoutMs: Long = 30_000): Result =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    val m = ensureManager(context)
                    m.disconnect()
                    if (!m.connectTls(context, timeoutMs)) {
                        return@runCatching Result(false, "无线调试连接超时")
                    }
                    verify(m)
                }.getOrElse {
                    AppLog.w(TAG, "ADB TLS 连接失败：${it.javaClass.simpleName} ${it.message}")
                    val message = it.message ?: it.javaClass.simpleName
                    /*
                     * ⚠️ 用户在系统设置里取消了「无线调试」里对这个设备的配对后，
                     * adbd 会回 "adb pairing is required"。此时本地还留着
                     * `adbPaired = true`，会导致每次进来都去 connectTls、每次都失败。
                     * 这里顺手把配对标记清掉，让激活页回到「需要配对」。
                     */
                    if (message.contains("pairing", ignoreCase = true)) {
                        AppPrefs.get(context).adbPaired = false
                        AppLog.w(TAG, "配对已失效（可能在系统里取消了对这个设备的配对），已清除配对标记")
                    }
                    Result(false, message)
                }
            }
        }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching { manager?.disconnect() }
            manager = null
            lastUid = null
        }
    }

    /** 打开长驻 shell（`getevent -t` 等）。 */
    suspend fun openShell(command: String): AdbStream? = withContext(Dispatchers.IO) {
        val m = manager
        if (m == null) {
            AppLog.w(TAG, "openShell 失败：ADB 未连接（$command）")
            return@withContext null
        }
        runCatching {
            /*
             * ⚠️ 不能把长命令直接塞进 `shell:<command>`：
             * libadb 的 service 字符串缓冲区很小，手柄 helper 那条
             * 「复制 + test + chmod + exec」约 400 字符，会直接抛
             * `BufferOverflowException`（日志里已经踩到）。
             *
             * 改成先开一个交互 shell，再把命令写进 stdin —— service
             * 字符串永远只有 `shell:`，命令多长都行。
             */
            if (command.length > 120) {
                val stream = m.openStream("shell:")
                val out = stream.openOutputStream()
                out.write((command + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
                AppLog.i(TAG, "openShell 交互模式，命令长度 ${command.length}")
                stream
            } else {
                m.openStream("shell:$command")
            }
        }.onFailure {
            AppLog.w(TAG, "openShell 失败：${it.javaClass.simpleName}: ${it.message}（$command）")
        }.getOrNull()
    }

    /** 执行一条短命令并读完输出。 */
    suspend fun exec(command: String, timeoutMs: Long = 8_000): String? = withContext(Dispatchers.IO) {
        runCatching {
            val m = manager ?: return@runCatching null
            readCommand(m, command)
        }.getOrNull()
    }

    private fun verify(m: AbsAdbConnectionManager): Result {
        AppLog.i(TAG, "打开 shell 执行 id")
        val id = readCommand(m, "id")
        AppLog.i(TAG, "id 输出：$id")
        if (id.isBlank()) {
            /* 少数 ROM 的 legacy shell 不返回输出但连接是好的：先认，采集层会真正验证 getevent */
            AppLog.w(TAG, "id 无输出，但仍按已连接处理")
            manager = m
            lastUid = "shell"
            return Result(true, "shell")
        }
        lastUid = id.lineSequence().firstOrNull { it.contains("uid=") }?.trim()
        if (lastUid?.contains("uid=2000") != true) {
            runCatching { m.disconnect() }
            manager = null
            return Result(false, "已连接，但身份不是 shell：$id")
        }
        manager = m
        AppLog.i(TAG, "ADB 已连接：$lastUid")
        return Result(true, lastUid ?: "shell")
    }

    private fun readCommand(m: AbsAdbConnectionManager, command: String): String {
        AppLog.i(TAG, "openStream shell:$command")
        val stream = m.openStream("shell:$command")
        AppLog.i(TAG, "shell 已打开，开始读输出")
        val out = ByteArrayOutputStream()
        val worker = Thread({
            runCatching {
                stream.openInputStream().use { input ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        val n = try {
                            input.read(buffer)
                        } catch (e: java.io.IOException) {
                            break
                        }
                        if (n < 0) break
                        out.write(buffer, 0, n)
                    }
                }
            }
        }, "SthKey-AdbRead").apply {
            isDaemon = true
            start()
        }
        worker.join(5_000)
        if (worker.isAlive) {
            AppLog.w(TAG, "shell 命令 $command 超时，关闭流")
            runCatching { stream.close() }
        }
        return out.toString("UTF-8").trim()
    }

    private fun ensureManager(context: Context): AbsAdbConnectionManager {
        manager?.let { return it }
        return AdbManager(context.applicationContext).also { manager = it }
    }

    /** mDNS 发现：返回 (host, port)。 */
    private fun discover(
        context: Context,
        serviceType: String,
        timeoutMs: Long,
    ): Pair<String, Int>? {
        val latch = CountDownLatch(1)
        var host: String? = null
        var port = -1
        val mdns = AdbMdns(context, serviceType) { address, p ->
            if (address != null && p > 0) {
                host = address.hostAddress
                port = p
                latch.countDown()
            }
        }
        mdns.start()
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            mdns.stop()
        }
        val h = host ?: return null
        if (port <= 0) return null
        return h to port
    }

    private class AdbManager(context: Context) : AbsAdbConnectionManager() {
        private val crypto = AdbCrypto.load(context)
        private val certificate =
            AdbCertificate.load(context, crypto.privateKey, crypto.publicKey)

        init {
            setApi(Build.VERSION.SDK_INT)
            setTimeout(10, TimeUnit.SECONDS)
            setThrowOnUnauthorised(false)
        }

        override fun getPrivateKey(): PrivateKey = crypto.privateKey

        override fun getCertificate(): Certificate = certificate

        override fun getDeviceName(): String = "sthkey"
    }

    data class Result(val ok: Boolean, val detail: String)
}

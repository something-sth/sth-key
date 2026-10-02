package com.something.sthkey.domain.capture

import com.something.sthkey.core.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * root 可用性探测。
 *
 * 只回答一件事："这台设备上 `su` 能不能拿到 root 身份"。
 * 能拿到 root 身份**不等于**能读输入设备 —— 设备可读性由采集层在
 * 真正打开设备节点时验证，那是另一层的事。
 *
 * 定位：只服务于"引导页/调试页的状态显示"，不参与采集主流程。
 * 可能阻塞（等用户点授权弹窗），因此统一在 [Dispatchers.IO] 上执行。
 */
object RootProbe {

    private const val TAG = "RootProbe"

    /**
     * su 超时。
     *
     * 首次调用会弹出授权窗口，**用户需要时间去看清并点确认** ——
     * 之前给 5 秒经常不够：超时会被判成"没有 root"，
     * 用户明明点了允许，界面上却显示未授权，看起来就像软件坏了。
     * 15 秒既够点完弹窗，也不至于在真的没有 root 时干等太久
     * （没有 root 时 `su` 通常是立刻失败，不会走到超时）。
     */
    private const val TIMEOUT_SECONDS = 15L

    private val ROOT_UID_PATTERN = Regex("""uid=0(?:\(|\s|$)""")

    /** 探测结果：是否可用 + 给用户看的原因 */
    data class Result(val available: Boolean, val detail: String)

    suspend fun probe(): Result = withContext(Dispatchers.IO) {
        val result = probeBlocking()
        AppLog.i(TAG, "root 探测：${result.available}（${result.detail}）")
        result
    }

    /** 阻塞版探测；调用方自己保证不在主线程执行 */
    fun probeBlocking(): Result = try {
        val process = ProcessBuilder("su", "-c", "id")
            .redirectErrorStream(true)
            .start()

        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            Result(false, "su 超时（${TIMEOUT_SECONDS} 秒未响应）")
        } else {
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            if (process.exitValue() == 0 && ROOT_UID_PATTERN.containsMatchIn(output)) {
                Result(true, output.lineSequence().firstOrNull().orEmpty())
            } else {
                Result(false, "su 未返回 root 身份")
            }
        }
    } catch (e: Exception) {
        Result(false, "su 不可用（${e.javaClass.simpleName}）")
    }

    /** 缺少 root 时展示给用户的排查提示 */
    fun hint(): String =
        "需要已授权的 root 环境（Magisk / KernelSU 等）。首次调用会弹出授权窗口，请留意屏幕。"
}

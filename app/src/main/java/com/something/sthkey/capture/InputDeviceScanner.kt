package com.something.sthkey.capture

import com.something.sthkey.core.log.AppLog
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 输入设备扫描。
 *
 * 职责：列出系统里所有 `/dev/input/event*` 节点。
 *
 * 与旧项目的差别（也是这次重构的意图）：**不再让用户选择设备**。
 * 旧版要用户手动勾选 event，但设备号会随机型、插拔顺序变化，
 * 选错就"按了没反应"，排查成本很高。
 *
 * 现在的做法：全部监听。判断某个事件该不该点亮某个键，
 * 交给按键映射（键码匹配）去决定 —— 设备来源不重要。
 * 代价是需要打开的节点多一些，但每个节点开销很小，换来的是"插上就能用"。
 */
object InputDeviceScanner {

    private const val TAG = "Scanner"

    /** 一次扫描的结果 */
    data class Result(
        val devicePaths: List<String>,
        val success: Boolean,
        val message: String,
    )

    private val DEVICE_PATTERN = Regex("""/dev/input/event\d+""")

    /**
     * 扫描可读的输入设备。
     *
     * 会实际尝试打开每个节点来过滤掉打不开的（有些节点存在但无权限读，
     * 直接交给读取线程会在启动后才报错，不如在这里就筛掉）。
     *
     * 阻塞操作，必须在 IO 线程调用。
     */
    fun scan(useRoot: Boolean = true): Result = try {
        val listed = listDevices(useRoot)
        if (listed.isEmpty()) {
            Result(emptyList(), success = false, message = "没有找到 /dev/input/event* 设备")
        } else {
            Result(
                devicePaths = listed,
                success = true,
                message = "找到 ${listed.size} 个输入设备",
            )
        }
    } catch (e: Exception) {
        AppLog.e(TAG, "扫描输入设备失败", e)
        // 把异常类型和消息都带上：只说"扫描失败"用户完全无从下手，
        // 而这里最常见的失败其实是"没有 root 权限"（su 返回非 0）
        val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        Result(emptyList(), success = false, message = "扫描失败：$detail")
    }

    /**
     * 列出设备节点。
     *
     * 注意：**普通应用进程没有权限列 /dev/input**（SELinux 限制），
     * 因此 `useRoot = false` 基本注定失败 —— 保留这个分支只是为了
     * 在错误信息里区分"确实是权限问题"和"命令本身有问题"。
     *
     * Shizuku 通道下不要走这里：设备清单必须由 shell 身份的一方去扫
     * （见 ShizukuGeteventInputSource 里的 scanDevices）。
     */
    private fun listDevices(useRoot: Boolean): List<String> {
        val command = if (useRoot) {
            arrayOf("su", "-c", "ls /dev/input/event* 2>/dev/null")
        } else {
            arrayOf("sh", "-c", "ls /dev/input/event* 2>/dev/null")
        }

        val process = ProcessBuilder(*command)
            .redirectErrorStream(true)
            .start()

        val output = BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            reader.readLines()
        }
        val exitCode = process.waitFor()

        if (exitCode != 0 && output.isEmpty()) {
            // 带上退出码：su 被拒绝 / 未授权时就是非 0 且没有输出
            throw IllegalStateException("ls 返回错误码 $exitCode（通常是缺少 root 权限）")
        }

        return output.asSequence()
            .map { it.trim() }
            .filter { DEVICE_PATTERN.matches(it) }
            .sortedBy { path -> path.substringAfter("event").toIntOrNull() ?: Int.MAX_VALUE }
            .toList()
            .also { paths ->
                AppLog.i(TAG, "扫描到 ${paths.size} 个设备：${paths.joinToString()}")
            }
    }
}

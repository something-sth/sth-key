package com.something.sthkey.capture.shizuku

import com.something.sthkey.core.log.AppLog
import java.io.BufferedReader

/**
 * 一次性探测输入设备的**能力**(轴范围),供摇杆归一化使用。
 *
 * ============================================================
 * 为什么必须在采集**之前**单独跑一次
 * ============================================================
 * 归一化需要每个轴的 `min / max / flat`,而**主监听流里没有这些**
 * (`getevent -t` 只打印裸值)。只有 `getevent -i` 会打印它们。
 *
 * 所以主监听启动前先跑一次 `getevent -i`,把结果解析出来缓存住。
 *
 * ============================================================
 * ⚠️ 怎么让它停下来(这是本类唯一有技巧的地方)
 * ============================================================
 * `getevent -i` 打印完设备描述后**不会退出** —— 它会一直等事件
 * (而且它只在**启动那一刻**打印设备描述,之后再也不打印。
 * 所以热插拔进来的设备在这里查不到,见下面的"已知限制")。
 *
 * 处理办法:**stdin 接 `/dev/null`**。
 *
 * `getevent` 会 `tcgetattr(stdin)`,非 TTY 时它拿不到终端尺寸,
 * 于是把设备列表打印一遍就正常退出 —— 不需要我们杀它,
 * 也不需要 `timeout` / `head` 这些**不保证存在**的工具。
 *
 * ⚠️ 但不依赖"它能自己退出":读循环是**空闲即停**(连续
 * [IDLE_STOP_MS] 没有新行就收尾)。因为设备描述是一口气吐出来的,
 * 中间不会有几百毫秒的空档;而后面的等待可以是无限的。
 * 两条路任意一条先成立都能收尾,不会挂住启动。
 *
 * ============================================================
 * 已知限制:热插拔进来的设备查不到轴范围
 * ============================================================
 * 本探测只在启动时跑一次。**采集中途插进来的手柄拿不到轴范围**,
 * 于是它的摇杆归一化会回落到"假定 16 位对称范围"。
 *
 * ⚠️ 这是**刻意的取舍**,不是遗漏:
 *
 * - 常见手柄(含实测的 Xbox 360)摇杆轴都是 `-32768..32767`,
 *   回落值对它是对的;
 * - 要精确支持热插拔,得在每次 `add device` 时再单独跑一次
 *   `getevent -i <device>` —— 那是**每插一次多一条进程**,
 *   而收益只是"少数非标准手柄的方向键轴"(`-1..1`,本来也能靠
 *   钳制兜住);
 * - 真有人反馈某种手柄摇杆幅度不对时,再加那条路径,并**以实测为准**。
 */
class InputDeviceProbe(
    /** 起一条命令并返回它的输出流;失败时抛异常 */
    private val launcher: (String) -> BufferedReader,
) {

    /**
     * 跑一次探测。
     *
     * @return 设备路径 → 能力;失败返回空表(**不抛异常** ——
     *   探测失败不该让采集起不来,摇杆退化成"假定范围"而已)
     */
    fun probe(): Map<String, DeviceCapabilities> {
        val text = runCommand(COMMAND)
        if (text.isBlank()) {
            AppLog.w(TAG, "设备能力探测没有输出（摇杆将回落到假定范围）")
            return emptyMap()
        }

        val caps = DeviceCapabilitiesParser.parse(text)
        logDetail(caps, text)
        return caps
    }

    /**
     * **只探一台设备**（热插拔时用）。
     *
     * ============================================================
     * 为什么需要它
     * ============================================================
     * [probe] 只在启动时跑一次，所以**启动后才插进来的手柄查不到轴范围**
     * （实测日志里那种 `[event7 未知设备(未探测)]`）。
     *
     * 后果有两层：
     * 1. 摇杆回落到"假定 16 位范围" —— 对常见手柄碰巧是对的，
     *    但非标准范围（扳机 `0..255`、方向键 `-1..1` 之外的）会算错；
     * 2. **日志里认不出设备名** —— 真出问题时只有一堆数字。
     *
     * 现在设备接入时补探一次，两层都解决。
     *
     * @param devicePath 例如 `/dev/input/event7`
     * @return 这台设备的能力；探不到返回 null
     */
    fun probeDevice(devicePath: String): DeviceCapabilities? {
        val text = runCommand("$GETEVENT -i 2>&1 ${shellQuote(devicePath)} </dev/null")
        if (text.isBlank()) {
            AppLog.w(TAG, "补探 $devicePath 没有输出")
            return null
        }

        val caps = DeviceCapabilitiesParser.parse(text)[devicePath]
        if (caps == null) {
            AppLog.w(TAG, "补探 $devicePath 没解析出能力")
            return null
        }

        AppLog.i(
            TAG,
            "补探到设备：${caps.name}（$devicePath）" +
                "${caps.axes.size} 个轴" +
                if (caps.direct) "，带 INPUT_PROP_DIRECT（将被过滤）" else "",
        )
        return caps
    }

    /** 起一条命令、读到空闲、返回全部输出 */
    private fun runCommand(command: String): String {
        val reader = try {
            launcher(command)
        } catch (e: Exception) {
            AppLog.w(TAG, "设备能力探测启动失败：${e.javaClass.simpleName}: ${e.message}")
            return ""
        }

        return try {
            readUntilIdle(reader)
        } catch (e: Exception) {
            AppLog.w(TAG, "设备能力探测读取失败：${e.javaClass.simpleName}")
            ""
        } finally {
            runCatching { reader.close() }
        }
    }

    /**
     * 给路径加 shell 引号。
     *
     * ⚠️ 设备路径来自 `getevent` 的输出，理论上不含特殊字符，
     * 但**拼接进 shell 命令**就得挡住 —— 这里的输入是外部数据。
     */
    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    /**
     * 读到"连续 [IDLE_STOP_MS] 没有新行"为止。
     *
     * ⚠️ **不能简单地 `readLine()` 到 EOF**:`getevent -i` 可能不会退出,
     * 那样会把启动流程挂死。空闲判定是必需的保险。
     */
    private fun readUntilIdle(reader: BufferedReader): String {
        val buffer = StringBuilder()
        var lastLineAt = System.currentTimeMillis()

        while (true) {
            /* 有数据可读就读;没有就检查是不是已经空闲够久 */
            if (!reader.ready()) {
                if (buffer.isNotEmpty() &&
                    System.currentTimeMillis() - lastLineAt >= IDLE_STOP_MS
                ) {
                    break
                }
                if (buffer.isEmpty() &&
                    System.currentTimeMillis() - lastLineAt >= FIRST_LINE_TIMEOUT_MS
                ) {
                    /* 等了很久一行都没有:命令多半没跑起来,别继续等 */
                    AppLog.w(TAG, "探测等待首行超时")
                    break
                }
                Thread.sleep(POLL_MS)
                continue
            }

            val line = reader.readLine() ?: break
            buffer.append(line).append('\n')
            lastLineAt = System.currentTimeMillis()

            if (buffer.length > MAX_CHARS) {
                AppLog.w(TAG, "探测输出超过 $MAX_CHARS 字符，截断")
                break
            }
        }

        return buffer.toString()
    }

    /**
     * 把探测结果记进日志。
     *
     * ⚠️ **失败时连原始输出的开头一起记**。这一段的唯一用途就是
     * "真机上出问题时能看出它到底打印了什么" —— 没有它,
     * "手柄摇杆不动"就只能靠猜。
     */
    private fun logDetail(caps: Map<String, DeviceCapabilities>, raw: String) {
        AppLog.i(TAG, DeviceCapabilitiesParser.describe(caps))

        caps.values.filter { it.looksLikeGamepad }.forEach { pad ->
            AppLog.i(TAG, "手柄 ${pad.name}（${pad.path}）的轴：")
            pad.axes.entries.sortedBy { it.key }.forEach { (code, range) ->
                AppLog.i(
                    TAG,
                    "  ${AxisNames.of(code)}(${code.toString(16).padStart(4, '0')})：" +
                        "min ${range.min}, max ${range.max}, " +
                        "fuzz ${range.fuzz}, flat ${range.flat}, " +
                        "死区 ${"%.4f".format(range.deadZone)}",
                )
            }
        }

        if (caps.none { it.value.looksLikeGamepad }) {
            AppLog.i(TAG, "没有识别到手柄。探测输出开头：")
            raw.lineSequence().take(NOTHING_FOUND_LOG_LINES).forEach { line ->
                AppLog.i(TAG, "  ${line.take(MAX_LOG_LINE)}")
            }
        }
    }

    internal companion object {
        const val TAG = "DeviceProbe"

        /**
         * 命令。
         *
         * ⚠️ `-i` 是必须的:轴范围只有它打印。
         * ⚠️ 带 `2>&1`:失败原因(权限、参数不认)常走 stderr,丢掉就没法查。
         * ⚠️ `</dev/null`:见类注释 —— 这是让它在打印完描述后自己退出的关键。
         * ⚠️ **不带 device 参数**:一次拿到全部设备。
         */
        const val COMMAND = "/system/bin/getevent -i 2>&1 </dev/null"

        /**
         * `getevent` 的绝对路径。
         *
         * ⚠️ 补探单台设备时要用它拼命令 —— 与 [COMMAND] 必须指向**同一个**
         * 二进制，否则两条路径可能跑在不同版本上（比如 PATH 里有个别的）。
         */
        const val GETEVENT = "/system/bin/getevent"

        /** 判定"输出结束"的空闲时长 */
        const val IDLE_STOP_MS = 400L

        /** 等第一行的超时(命令压根没跑起来时别干等) */
        const val FIRST_LINE_TIMEOUT_MS = 3_000L

        const val POLL_MS = 20L

        /** 输出上限,防止异常情况把内存吃满 */
        const val MAX_CHARS = 200_000

        /** 没找到手柄时,原样记多少行输出 */
        const val NOTHING_FOUND_LOG_LINES = 40

        const val MAX_LOG_LINE = 200
    }
}

/** 轴码的可读名称(只用于日志) */
object AxisNames {
    fun of(code: Int): String = when (code) {
        ABS_X -> "ABS_X 左摇杆X"
        ABS_Y -> "ABS_Y 左摇杆Y"
        ABS_Z -> "ABS_Z 左扳机"
        ABS_RX -> "ABS_RX 右摇杆X"
        ABS_RY -> "ABS_RY 右摇杆Y"
        ABS_RZ -> "ABS_RZ 右扳机"
        ABS_HAT0X -> "ABS_HAT0X 方向键X"
        ABS_HAT0Y -> "ABS_HAT0Y 方向键Y"
        else -> "ABS_?"
    }
}

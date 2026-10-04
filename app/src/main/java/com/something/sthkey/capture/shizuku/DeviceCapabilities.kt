package com.something.sthkey.capture.shizuku


/**
 * 一个**绝对轴**的取值范围。
 *
 * ============================================================
 * ⚠️ 为什么必须要这个(getevent 不告诉你范围)
 * ============================================================
 * `getevent -t` 打印的是**裸 ADC 值**:
 *
 * ```
 * [  123.456] /dev/input/event7: 0003 0000 0000012c
 * ```
 *
 * `0x12c = 300` —— 但**300 是什么意思?** `getevent` 不会说。而各手柄差得极远:
 *
 * | 手柄 | ABS_X 范围 |
 * |---|---|
 * | Xbox 360 / Xbox 手柄 | `-32768 .. 32767`(16 位) |
 * | 很多国产手柄 | `0 .. 255`(8 位) |
 * | 方向键(HAT) | `-1 .. 1` |
 *
 * 不归一化就没法用:同一个"推到底"可能是 `32767`,也可能是 `255`。
 *
 * ============================================================
 * ⚠️ 中点必须**算出来**,不能假定是 0
 * ============================================================
 * 直觉上"摇杆松手时应该是 0",但实际不是:
 *
 * - 范围是 `0 .. 255` 的扳机,中点显然是 `127` 而不是 0;
 * - 方向键 `-1 .. 1` 的中点是 0,但**8 位手柄**的摇杆常见 `0 .. 255`
 *   (中心 127);
 * - 就算范围对称,**硬件也可能有零点漂移**。
 *
 * 所以中点一律取 `(min + max) / 2`,不写死 0。
 *
 * @param min  该轴的最小原始值
 * @param max  该轴的最大原始值
 * @param fuzz 内核报的**噪声容限**:相邻两次读数的抖动小于它就该忽略
 * @param flat 内核报的**硬件死区**(原始值单位)
 * @param value 读取那一刻该轴的值。
 *
 *   ============================================================
 *   ⚠️ 它不是没用 —— 它是"设备静止时的真实中点"
 *   ============================================================
 *   `getevent -i` 打印轴信息时**顺带打印当前值**。如果那一刻设备静止
 *   (手柄没被碰),这个值就是**这台设备的真实零点**,比
 *   `(min + max) / 2` 更可信 —— 硬件零点漂移很常见,
 *   用假定中点会让摇杆松手后**不归零**(拇指偏在一边)。
 *
 *   判定"能不能信它"见 [AxisNormalizer.resolveCenter]。
 */
data class AxisRange(
    val min: Int,
    val max: Int,
    val fuzz: Int,
    val flat: Int,
    val value: Int = (min.toLong() + max.toLong()).toInt() / 2,
) {
    /** 全量程;为 0 时说明数据异常,调用方要跳过这个轴 */
    val span: Int get() = max - min

    /** 中点。用 `Long` 避免 `min + max` 在极端值上溢出(两者都可能接近 Int 边界) */
    val center: Int get() = ((min.toLong() + max.toLong()) / 2L).toInt()

    /**
     * 死区换算成**归一化后**的比例。
     *
     * ⚠️ 用**短边**归一:
     *
     * ```cpp
     * double flatDenom = positiveRange < negativeRange ? positiveRange : negativeRange;
     * double dead = info.flat / flatDenom;
     * if (dead > 0.35) dead = 0.35;        // 上限保护
     * ```
     *
     * **为什么是短边**:`flat` 是**单边**容限,而它作用的那一侧可能比
     * 另一侧短得多(比如扳机 `0..255`、中点 127:正边 128、负边 127)。
     * 用整个 `span` 除会**低估死区**。
     *
     * ⚠️ **0.35 上限**不是保守,是防呆:某个 ROM 报了个离谱的 `flat`
     * (比如整量程的一大半)时,不夹住的话摇杆会被整个吃掉 ——
     * **一点都推不动**,而看代码完全看不出问题。
     *
     * ⚠️ 它比"手感死区"小得多(那个是 0.05 量级)。**两层死区不要混**:
     * 这一层是硬件噪声,只负责把静止时抖动的零点压回 0。
     */
    val deadZone: Float get() = AxisNormalizer.hardwareDeadZone(this)

    /** 这个范围是不是"看起来就不是摇杆"(量程为 0 的占位项、触摸屏的超大坐标) */
    val isUsable: Boolean get() = span > 0
}

/**
 * 一台输入设备的**能力信息**,来自 `getevent -i`。
 *
 * @param path  设备节点,如 `/dev/input/event7`
 * @param name  设备名,如 `"Microsoft X-Box 360 pad"`
 * @param axes  绝对轴码(`ABS_X` = 0 等) → 范围
 * @param direct true = 带 `INPUT_PROP_DIRECT`(触摸屏、手写笔)。
 *
 *   ⚠️ **这类设备必须整个跳过**。原因很实在:触摸屏也报 `ABS_X` / `ABS_Y`,
 *   但范围是 `0 .. 44800` 这种屏幕坐标 —— 不筛掉的话**手指一碰屏幕,
 *   悬浮窗上的摇杆就会满偏**。
 */
data class DeviceCapabilities(
    val path: String,
    val name: String,
    val axes: Map<Int, AxisRange>,
    val direct: Boolean,
) {
    /** 它像不像一台手柄:至少有一个**摇杆轴**(不含方向键 HAT) */
    val looksLikeGamepad: Boolean
        get() = !direct && STICK_AXES.any { axes[it]?.isUsable == true }

    companion object {
        /**
         * "摇杆类"轴码 —— 用它来判断一台设备是不是手柄。
         *
         * ⚠️ **刻意不含方向键** `ABS_HAT0X/Y`(16/17):很多**手机自己**也报
         * 一对 HAT 轴(导航键),只按 HAT 判断会把手机误判成手柄。
         */
        val STICK_AXES = listOf(
            ABS_X, ABS_Y, ABS_RX, ABS_RY,
            ABS_Z, ABS_RZ,
        )
    }
}

/** `getevent -i` 输出的解析(**纯函数,可单元测试**) */
internal object DeviceCapabilitiesParser {

    /** `/dev/input/event7` 的公告行,形如 `add device 1: /dev/input/event7` */
    private val ADD_DEVICE = Regex("""add device \d+:\s*(/dev/input/\S+)""")

    /**
     * 轴信息行。实测格式(`getevent -i`)：
     *
     * ```
     *   ABS (0003): 0000  : value 0, min -32768, max 32767, fuzz 16, flat 128, resolution 0
     *               0001  : value -1, min -32768, max 32767, fuzz 16, flat 128, resolution 0
     * ```
     *
     * ============================================================
     * ⚠️ 每个数字周围都可能有**多个空格**,必须用 `\s+`
     * ============================================================
     * `getevent` 用带**宽度**的格式打印轴信息(`%4s` / `%4d` 之类),
     * 所以字段会补空格对齐 —— 例如 `value     0`。
     *
     * 第一版我按"`,` 后面一个空格"写死了,结果**某些轴解析不出来**:
     * 实测里恰好是 `ABS_X`(它的 `value 0` 被补了空格)。
     * 症状是**左摇杆不动**,看起来像硬件或渲染问题,而真正的原因
     * 只是一行正则少容忍了空格。
     *
     * 教训:解析别人打印的文本,**任何位置的空格数都不能假定**。
     *
     * ============================================================
     * ⚠️ `ABS (0003):` 可能与第一个轴在**同一行**,也可能分开
     * ============================================================
     * 真实输出里它是**分开的两行**:
     *
     * ```
     *   ABS (0003): 0000  : value 0, min -32768, ...
     *               0001  : value -1, min -32768, ...
     * ```
     *
     * 但**手工复制整理时很容易粘成一行**(第一版样本就是这么错的),
     * 而那样 `ABS (0003):` 会让"行首是四位十六进制"的匹配失败 ——
     * **第一个轴整条丢掉**,症状同样是"左摇杆不动"。
     *
     * 所以前缀做成**可选**:两种形态都认。
     */
    private val AXIS_LINE = Regex(
        """^\s*(?:ABS\s*\([0-9a-fA-F]{4}\)\s*:)?\s*([0-9a-fA-F]{4})\s*:\s*""" +
            """value\s*(-?\d+)\s*,\s*min\s*(-?\d+)\s*,\s*max\s*(-?\d+)\s*,""" +
            """\s*fuzz\s*(\d+)\s*,\s*flat\s*(\d+)\s*,""",
    )

    /**
     * `value` 字段的提取。
     *
     * ⚠️ 单独一个正则,而不是并进 [AXIS_LINE]:
     *
     * - [AXIS_LINE] 用来**认出这是一行轴信息**(靠 `min` / `max` / `fuzz` / `flat`);
     * - 这个只负责取 `value`。
     *
     * 分开的好处是**其中一个字段格式变了不会让整行报废** ——
     * 取不到 `value` 时只是回落到理论中点(有默认值),而不是丢掉整个轴。
     */
    private val AXIS_VALUE = Regex("""^\s*(?:ABS\s*\([0-9a-fA-F]{4}\)\s*:)?\s*""" +
        """([0-9a-fA-F]{4})\s*:\s*value\s*(-?\d+)\s*,""")

    /** `name:     "Microsoft X-Box 360 pad"` */
    private val NAME_LINE = Regex("""^\s*name:\s*"(.*)""" + '"')

    /**
     * 解析一整个 `getevent -i` 输出。
     *
     * @return 设备路径 → 能力。解析不出的行一律忽略(格式随 ROM 变,不能崩)
     */
    fun parse(text: String): Map<String, DeviceCapabilities> {
        val result = linkedMapOf<String, DeviceCapabilities>()

        var currentPath: String? = null
        var currentName = ""
        val currentAxes = linkedMapOf<Int, AxisRange>()
        var currentDirect = false

        fun flush() {
            val path = currentPath ?: return
            result[path] = DeviceCapabilities(
                path = path,
                name = currentName,
                axes = currentAxes.toMap(),
                direct = currentDirect,
            )
        }

        text.lineSequence().forEach { line ->
            ADD_DEVICE.find(line)?.let { match ->
                /* 遇到下一个设备:先把上一个收尾 */
                flush()
                currentPath = match.groupValues[1]
                currentName = ""
                currentAxes.clear()
                currentDirect = false
                return@forEach
            }

            if (currentPath == null) return@forEach

            NAME_LINE.find(line)?.let { match ->
                currentName = match.groupValues[1]
                return@forEach
            }

            if (line.contains(INPUT_PROP_DIRECT)) {
                currentDirect = true
                return@forEach
            }

            AXIS_LINE.find(line)?.let { match ->
                val code = match.groupValues[1].toIntOrNull(16) ?: return@forEach
                val min = match.groupValues[3].toIntOrNull() ?: return@forEach
                val max = match.groupValues[4].toIntOrNull() ?: return@forEach

                /*
                 * ⚠️ `value` 要单独提。
                 *
                 * 它是**读取那一刻该轴的值** —— 设备静止时就是这台设备的
                 * 真实零点，比 `(min + max) / 2` 更可信（硬件零点会漂移）。
                 *
                 * 取不到就回落到理论中点（[AxisRange] 的默认参数），
                 * **不能**因此丢掉整个轴。
                 */
                val observed = AXIS_VALUE.find(line)
                    ?.groupValues
                    ?.getOrNull(2)
                    ?.toIntOrNull()

                currentAxes[code] = AxisRange(
                    min = min,
                    max = max,
                    fuzz = match.groupValues[5].toIntOrNull() ?: 0,
                    flat = match.groupValues[6].toIntOrNull() ?: 0,
                    value = observed ?: (((min.toLong() + max.toLong()) / 2L).toInt()),
                )
            }
        }

        /* 最后一个设备也要收尾 —— 少了这一步它会整台丢掉 */
        flush()

        return result
    }

    /**
     * 把解析结果写成一行日志。
     *
     * ⚠️ **日志不放在 [parse] 里**。它是纯函数,而本地 JVM 测试里
     * `android.util.Log` 是**桩** —— 一调用就抛
     * `Method i in android.util.Log not mocked`,整个测试类全红。
     *
     * 这条踩过:加了日志之后 6 条解析测试同时失败,而失败原因
     * ("Log 没被 mock")看起来跟解析毫无关系。
     */
    fun describe(caps: Map<String, DeviceCapabilities>): String {
        val gamepads = caps.values.filter { it.looksLikeGamepad }
        return if (gamepads.isEmpty()) {
            "设备能力：共 ${caps.size} 台，没有识别到手柄"
        } else {
            "设备能力：共 ${caps.size} 台，手柄 ${gamepads.size} 台 —— " +
                gamepads.joinToString { "${it.name}[${it.path.substringAfterLast('/')}] ${it.axes.size} 轴" }
        }
    }

    /** Android 在 `input props:` 下打印的属性名 */
    private const val INPUT_PROP_DIRECT = "INPUT_PROP_DIRECT"
}

/*
 * ============================================================
 * 绝对轴码(evdev)
 * ============================================================
 * 放在这里而不是 [com.something.sthkey.domain.keys.KeyCodes]:
 * 那边是**按键**码(会被用户绑到组件上),而轴码是**设备能力**的一部分,
 * 用户不直接绑定它 —— 摇杆轴由样式自己消费(见 gamepad1 / gamepad2)。
 *
 * ⚠️ 值必须与 Linux `input-event-codes.h` 一致,写错就是"摇杆不动"。
 */

/** 左摇杆 X */
const val ABS_X = 0x00

/** 左摇杆 Y */
const val ABS_Y = 0x01

/** 左扳机(LT)。Xbox 手柄上范围是 `0..255`,**不是**对称的 */
const val ABS_Z = 0x02

/** 右摇杆 X */
const val ABS_RX = 0x03

/** 右摇杆 Y */
const val ABS_RY = 0x04

/** 右扳机(RT) */
const val ABS_RZ = 0x05

/** 方向键(轴报法)X。范围通常 `-1..1` */
const val ABS_HAT0X = 0x10

/** 方向键(轴报法)Y */
const val ABS_HAT0Y = 0x11

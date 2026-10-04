package com.something.sthkey.capture.shizuku

/**
 * 把原始 ADC 值归一化成 `-1f .. 1f`。
 *
 * ============================================================
 * 纯函数,单独一个文件
 * ============================================================
 * 和 [GeteventParser] 同一个理由:手柄手感的问题**全都出在这几行算术上**,
 * 而它不依赖 Android 的任何东西 —— 可以完整单测。
 *
 * ============================================================
 * ⚠️ 三层处理,顺序不能反
 * ============================================================
 * ```
 * 原始值 → ① 减中点 → ② 除以半量程 → ③ 压硬件死区 → 归一化结果
 * ```
 *
 * **① 减中点必须在除法之前。** 范围 `0..255` 的扳机中点是 `127`,
 * 不减的话"松开"会被算成 `0.5` 的输入。
 *
 * **② 除以半量程。** 用 `max(center - min, max - center)` 而不是
 * `span / 2`:范围不对称时(比如 `min -32768, max 32767`)两边不同,
 * 用小的那一边会让另一边超出 1,用大的那一边才保证不越界。
 *
 * **③ 硬件死区用内核报的 `flat`。** 它不是手感的死区(那是 0.05 量级),
 * 只负责把静止时抖动的零点压回 0 —— Xbox 360 手柄实测 flat=128、
 * 量程 65535,换算过来只有 0.00195。
 */
internal object AxisNormalizer {

    /**
     * 判定"这个轴在静止时读到的值"能不能当中点用。
     *
     * ============================================================
     * 为什么需要calibratedCenter
     * ============================================================
     * `(min + max) / 2` 是**理论**中点,而硬件的**真实零点常常偏一点**。
     * 用理论中点的话,摇杆松手后不会归零 —— 悬浮窗上的拇指**偏在一边**,
     * 而用户会觉得"这个摇杆坏了"。
     *
     * `getevent -i` 打印轴信息时会**顺带打印读取那一刻的值**。
     * 如果那次读取时设备是静止的(手柄没被碰),那个值就是真实零点。
     *
     *
     * ```
     * 偏差 = |读取值 - 理论中点|
     * 容差 = max(量程的 8%, 硬件死区 × 2)
     * 偏差 <= 容差  →  信读取值;否则用理论中点
     * ```
     *
     * ⚠️ **为什么"偏差大就用理论中点"而不是"用读取值"**:
     * 偏差大说明**读取那一刻用户正推着摇杆**(或者设备报了个怪值),
     * 那不是零点。两害相权取理论上更安全的那个 —— 理论中点至少
     * "松手时大概在中间",而误用推着时的值会**永久偏置**。
     *
     * ⚠️ 拿 8% 当容差是有意的**宽**:手柄零点漂移通常在 1~3% 以内,
     * 而"用户推着摇杆"一般超过 20%。8% 落在两者中间。
     */
    fun resolveCenter(range: AxisRange): Float {
        val midpoint = (range.min.toLong() + range.max.toLong()) * 0.5
        val half = range.span * 0.5
        if (half <= 0.0) return midpoint.toFloat()

        val offset = range.value - midpoint
        var tolerance = half * CENTER_TOLERANCE_RATIO
        /* 内核明确报了死区的,容差不能比它还小 */
        if (range.flat > 0 && range.flat * 2.0 > tolerance) {
            tolerance = range.flat * 2.0
        }

        return if (kotlin.math.abs(offset) <= tolerance) {
            /* 读取那一刻设备静止 → 它就是真实零点 */
            range.value.toFloat()
        } else {
            midpoint.toFloat()
        }
    }

    /**
     * 硬件死区(归一化后的单边比例)。
     *
     *
     * - **短边**:`flat` 是单边容限,而它作用的那一侧可能比另一侧短
     *   (扳机 `0..255`、中点 127:正边 128、负边 127)。用整个量程除会低估。
     * - **0.35 上限**:某个 ROM 报了个离谱的 `flat` 时,不夹住的话
     *   摇杆会被整个吃掉 —— **一点都推不动**,而看代码看不出问题。
     */
    fun hardwareDeadZone(range: AxisRange): Float {
        if (!range.isUsable) return 0f

        val center = resolveCenter(range)
        val positive = range.max - center
        val negative = center - range.min
        val shorter = if (positive < negative) positive else negative
        if (shorter <= 0f) return 0f

        val dead = range.flat.toFloat() / shorter
        return dead.coerceIn(0f, MAX_HARDWARE_DEAD_ZONE)
    }

    /**
     * 归一化一个绝对轴的值。
     *
     * ============================================================
     * ⚠️ 三层处理,顺序不能反
     * ============================================================
     * ```
     * 原始值 → ① 减中点 → ② 按该侧量程归一 → ③ 压硬件死区 → 结果
     * ```
     *
     * **① 中点用 [resolveCenter]**,不是死板的 `(min+max)/2`。
     *
     * **② 按"那一侧"的量程归一**:
     *
     * ```
     * 正值侧除以 (max - center),负值侧除以 (center - min)
     * ```
     *
     * 而不是统一除以"较大的半量程"。对**对称**的轴两者一样;
     * 对**不对称**的轴(扳机),按侧归一能让**两侧都到得了 ±1**,
     * 而统一除大会让短的那一侧永远到不了满量程。
     *
     * **③ 死区见 [hardwareDeadZone]**;过了死区要**重新拉满**
     * (否则拇指永远到不了最外圈)。这一条在 [applyDeadZone] 里。
     *
     * @param raw   `getevent` 报的原始值
     * @param range 该轴的能力信息(来自 `getevent -i`)
     * @return `-1f .. 1f`;范围无效时返回 0
     */
    fun normalize(raw: Int, range: AxisRange): Float {
        if (!range.isUsable) return 0f

        val center = resolveCenter(range)

        val positive = range.max - center
        val negative = center - range.min
        val denominator = if (raw >= center) positive else negative
        if (denominator <= 0f) return 0f

        val normalized = (raw - center) / denominator

        /*
         * 硬件死区。
         *
         * ⚠️ 比较用 `<=`(而不是 `<`):Xbox 360 手柄 flat=128、半量程 32768,
         * 死区正好是 0.00390625,而原始值 128 归一化后**恰好等于**它 ——
         * 用 `<` 会让"正好在死区边界"的值漏过去,静止的摇杆就一直输出一个小值。
         */
        val dead = hardwareDeadZone(range)
        if (kotlin.math.abs(normalized) <= dead) return 0f

        /*
         * ⚠️ 必须钳制。兜住两类意外:
         * 1. 把**触摸屏**的轴(实测 `0..44800`)误当成摇杆;
         * 2. ROM 报的范围与实际不符。
         *
         * 钳制之后最坏是"摇杆满偏",而不是"画面炸掉"。
         */
        return normalized.coerceIn(-1f, 1f)
    }

    /**
     * 摇杆的**手感死区**:低于它一律当作没推。
     *
     * ============================================================
     * ⚠️ 它与硬件死区是两件事
     * ============================================================
     * | | 来源 | 量级 | 作用 |
     * |---|---|---|---|
     * | 硬件死区 | 内核 `flat` | 0.002~0.004 | 消除静止抖动 |
     * | 手感死区 | 本常量 | 0.05~0.2 | 决定"推多少才开始动" |
     *
     * 用户报告的"摇杆不跟手",多半是**这两个混用了**:
     * 把硬件死区当手感死区用 → 拇指一直在抖;
     * 把手感死区当硬件死区用 → 推掉五分之一才开始动。
     *
     * ⚠️ 0.05 是初值,**必须实测调**。它同时影响摇杆拇指与 WASD 点亮
     * (后者还有自己的 0.35 阈值,见 `StickGeometry`)。
     */
    const val STICK_DEAD_ZONE = 0.05f

    /**
     * 判定"读取值能不能当中点"的容差比例（量程的多少）。
     *
     * ⚠️ 8% 是有意的**宽**：手柄零点漂移通常在 1~3% 以内，
     * 而"用户正推着摇杆"一般超过 20%。8% 落在两者中间。
     */
    const val CENTER_TOLERANCE_RATIO = 0.08

    /**
     * 硬件死区的**上限**。
     *
     * 防呆用：某个 ROM 报了个离谱的 `flat`（比如整量程的一大半）时，
     * 不夹住的话摇杆会被整个吃掉 —— **一点都推不动**，
     */
    const val MAX_HARDWARE_DEAD_ZONE = 0.35f

    /**
     * 应用手感死区。
     *
     * ⚠️ 用**线性重映射**而不是"直接归零":
     *
     * ```
     * 直接归零:  0.05..1.0  →  0.05..1.0   (有 5% 的死行程,推到底也不满)
     * 重映射:    0.05..1.0  →  0.0 ..1.0   (死区之外重新拉满)
     * ```
     *
     * 不重映射的话,拇指**永远到不了最外圈** —— 而那是用户最容易
     * 一眼看出来的问题("怎么推到底拇指还差一截")。
     */
    fun applyDeadZone(value: Float, deadZone: Float = STICK_DEAD_ZONE): Float {
        if (deadZone <= 0f) return value.coerceIn(-1f, 1f)

        val magnitude = kotlin.math.abs(value)
        if (magnitude <= deadZone) return 0f

        val rescaled = (magnitude - deadZone) / (1f - deadZone)
        return (if (value < 0f) -rescaled else rescaled).coerceIn(-1f, 1f)
    }
}

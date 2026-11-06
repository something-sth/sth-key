package com.something.sthkey.domain.custom

/**
 * `(fps)` 占位符的解析与求值。
 *
 * ============================================================
 * 语法
 * ============================================================
 * ```
 * (fps)                          → 只显示帧率
 * (fps+1000)                     → n + 1000
 * (fps-5)                        → n - 5（结果 < 0 取 0）
 * (fps*2)                        → n × 2
 * (fps+1000,offset=20-80)        → 先 n+1000，再随机 ±[20,80]
 * ```
 *
 * 求值顺序:**先运算、后偏移**，一次刷新只算一遍:
 * ```
 * n'   = op(n, m)
 * b    = randomInt(x..y)        // 闭区间、均匀
 * sign = 每次随机 ±1
 * out  = max(0, n' + sign * b)
 * ```
 *
 * ⚠️ 随机每秒重抽一次（不跨秒保留）—— 真帧率本来也是每秒一个数，
 * "每秒抖一下"反而更像。
 *
 * ============================================================
 * 容错（都不报错、不吞文本）
 * ============================================================
 * | 情况 | 行为 |
 * |---|---|
 * | `x > y` | 自动交换 |
 * | 写错（`(fps+abc)`） | **原样显示**这串文本 |
 * | 值不可用（读到 N/A） | 显示 `N/A`，不参与运算 |
 * | `*` 且 `m = 0` | 视为 1（否则永远显示 0，像坏了） |
 */
object FpsText {

    /** 一个解析后的表达式 */
    data class Expression(
        /** `+` / `-` / `*`;`null` = 只有 `(fps)` */
        val operator: Char?,
        /** 参与运算的数值 */
        val operand: Long,
        /** 偏移下界（含） */
        val offsetMin: Int,
        /** 偏移上界（含） */
        val offsetMax: Int,
    )

    /**
     * 匹配一个完整占位符。
     *
     * ⚠️ 只匹配**合法**形式:数字必须是正整数。写错的（`(fps+abc)`）不会匹配，
     * 于是原样留在文本里 —— 这比"整段消失"好排查得多。
     */
    private val PLACEHOLDER = Regex("""\(fps(?:([+\-*])(\d+))?(?:,offset=(\d+)-(\d+))?\)""")

    /** 文本里是否含 `(fps)` 占位符（用来决定要不要启动采集） */
    fun hasPlaceholder(text: String): Boolean = PLACEHOLDER.containsMatchIn(text)

    /**
     * 解析第一个占位符;不合法或缺席返回 null。
     *
     * 解析一次、存起来，渲染时只做算术 —— 不要每秒重新正则。
     */
    fun parse(text: String): Expression? {
        val match = PLACEHOLDER.find(text) ?: return null
        val groups = match.groupValues
        val operator = groups[1].firstOrNull()
        val operand = groups[2].toLongOrNull() ?: 0L
        val min = groups[3].toIntOrNull() ?: 0
        val max = groups[4].toIntOrNull() ?: 0
        return Expression(
            operator = operator,
            operand = operand,
            offsetMin = minOf(min, max),
            offsetMax = maxOf(min, max),
        )
    }

    /**
     * 把文本里的所有 `(fps…)` 换成实际数值。
     *
     * @param fps `null` = 读不到（显示 `N/A`）
     * @param random 随机数来源（测试可注入固定值）
     */
    fun render(
        template: String,
        fps: Double?,
        random: (IntRange) -> Int = { range -> range.random() },
    ): String = PLACEHOLDER.replace(template) { match ->
        if (fps == null) {
            /* ⚠️ 读不到就是 N/A，不做运算 —— 与 DroidPerf 的 Zero Fabrication 一致 */
            return@replace NA
        }
        val expression = parse(match.value) ?: return@replace match.value
        apply(expression, fps, random)
    }

    /** 对一个已解析的表达式求值 */
    fun apply(
        expression: Expression,
        fps: Double,
        random: (IntRange) -> Int = { range -> range.random() },
    ): String {
        val raw = when (expression.operator) {
            '+' -> fps + expression.operand
            '-' -> fps - expression.operand
            '*' -> fps * expression.operand.coerceAtLeast(1L)
            else -> fps
        }

        /* 偏移:每次刷新重抽一次幅度和方向 */
        val offset = if (expression.offsetMax > 0) {
            val span = expression.offsetMin..expression.offsetMax
            val magnitude = runCatching { random(span) }.getOrDefault(expression.offsetMin)
            val sign = if (runCatching { random(0..1) }.getOrDefault(0) == 0) 1 else -1
            magnitude * sign
        } else {
            0
        }

        val result = (raw + offset).toLong().coerceAtLeast(0L)
        return result.toString()
    }

    private const val NA = "N/A"
}
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
     * ============================================================
     * ⚠️ `seed` 是**关键参数**，别省
     * ============================================================
     * 带 `offset` 时，偏移量是**每秒随机一次**的。但这个函数会被反复调用 ——
     * 只要界面重组（按一下按键就会重组），它就会再跑一遍。
     *
     * 早先这里直接用 `Range.random()`，于是**每按一次键偏移就重抽一次**，
     * 用户看到的是"按键盘刷新帧率"（用户反馈）。裸 `(fps)` 没有随机量，
     * 所以看不出问题 —— 这也解释了为什么只有带 `offset` 的才明显。
     *
     * 现在随机源由 `seed` 派生（调用方传采样序号 `Reading.seq`）:
     * 同一秒内重组多少次，结果都**完全一样**;下一秒换 seed 才变。
     *
     * @param fps `null` = 读不到（显示 `N/A`）
     * @param seed 随机种子（每秒变化一次）
     * @param random 显式随机源（测试用;传了就不用 seed）
     */
    fun render(
        template: String,
        fps: Double?,
        seed: Long = 0L,
        random: ((IntRange) -> Int)? = null,
    ): String {
        if (fps == null) {
            /* 读不到就整体显示 N/A，不参与运算 */
            return PLACEHOLDER.replace(template) { NA }
        }
        var occurrence = 0
        return PLACEHOLDER.replace(template) { match ->
            val expression = parse(match.value) ?: return@replace match.value
            /*
             * 每个占位符各用一条随机序列:同一段文字里写两个
             * `(fps,offset=…)` 不应该得到一模一样的抖动。
             */
            val source = random ?: run {
                val rng = kotlin.random.Random(seed * 31L + occurrence)
                occurrence++
                { range: IntRange -> rng.nextInt(range.first, range.last + 1) }
            }
            apply(expression, fps, source)
        }
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

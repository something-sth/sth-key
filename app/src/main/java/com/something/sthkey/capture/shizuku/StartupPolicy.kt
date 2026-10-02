package com.something.sthkey.capture.shizuku

/**
 * 启动策略与重试判据（**纯逻辑，可单元测试**）。
 *
 * ============================================================
 * 为什么单独抽出来
 * ============================================================
 * 「先快后稳」这套逻辑里有三个容易写错、而且**在真机上不容易复现**的决定：
 *
 * 1. 什么时候值得再来一轮（重试确定性失败 = 让用户白等一倍时间）；
 * 2. 两轮的失败原因怎么合并（只报最后一轮会丢掉真正的线索）；
 * 3. 成功时要不要说明"第一轮其实失败了"。
 *
 * 这些都不碰 Android、不碰 IO，没有理由不做成纯函数钉住 ——
 * 否则只能靠"在某台设备上碰巧遇到失败"来验证，那太不可靠了。
 */
internal object StartupPolicy {

    /**
     * 一轮启动用的策略。
     *
     * 两轮的区别不只是"等多久"，而是**判据厚度**：
     * 偏稳那轮额外去查 `/proc` 复核进程存活（多一次 binder 往返，
     * 换来确定性的答案）。这样两轮才有实质区别，而不是同一件事做两遍。
     *
     * @param probeWindowMs 探活窗口：这段时间内持续看它有没有立刻死掉
     * @param verifyViaProc 是否用 `/proc` 复核进程存活（慢但确定）
     */
    data class Strategy(
        val label: String,
        val probeWindowMs: Long,
        val verifyViaProc: Boolean,
    )

    /**
     * 启动策略：**先快后稳**。
     *
     * ⚠️ 顺序不能反：常规情况走第一条，只有在"看起来只是太急了"的时候
     * 才付第二条的代价。反过来就成了"每次都慢"。
     *
     * ⚠️ 探活窗口的值是"启动速度"与"误判风险"的直接取舍：
     * - 150ms：够抓住"打印用法后立刻退出"这类**毫秒级**失败；
     *   而且设备是并行的，所以这 150ms 只付一次；
     * - 500ms + `/proc` 复核：判据厚得多，兜住"只是慢了一点"的情况。
     */
    val STRATEGIES: List<Strategy> = listOf(
        Strategy(label = "偏快", probeWindowMs = 150L, verifyViaProc = false),
        Strategy(label = "偏稳", probeWindowMs = 500L, verifyViaProc = true),
    )

    /**
     * 一轮启动的结果（只有判据需要的字段，与 UI/IO 无关）。
     *
     * @param readyCount    这一轮成功打开的设备数
     * @param failures      每个失败设备的说明（形如 `/dev/input/event3：…`）
     * @param allTransient  是否"全都是可能只因太急而失败的"
     */
    data class Attempt(
        val readyCount: Int,
        val failures: List<String>,
        val allTransient: Boolean,
    )

    /** 某一轮之后该怎么办 */
    sealed interface Decision {
        /** 成了（`usedFallback` = 是不是靠第二轮才成的） */
        data class Succeeded(val usedFallback: Boolean) : Decision

        /** 这一轮没成，但值得试下一轮 */
        data object Retry : Decision

        /** 不必再试了，全部失败 */
        data object GiveUp : Decision
    }

    /**
     * 决定某一轮之后是继续还是收手。
     *
     * ============================================================
     * ⚠️ 为什么要区分"值得重试"与"不值得"
     * ============================================================
     * 有些失败是**确定性**的，重试只会让用户白等一倍时间：
     * - `getevent` 打印用法（参数不认）→ 换多厚的探活都一样；
     * - `could not open …`（权限问题）→ 第二次照样打不开。
     *
     * 所以只在一眼看出"可能就是太急了"时才跑下一轮。
     * 这个规矩与调试页里那条"只有超时才重试"是同一条经验 ——
     * Shizuku 绑定那边也踩过"重试确定性失败"的坑。
     *
     * @param attemptIndex 刚结束的是第几轮（从 0 开始）
     */
    fun decide(attemptIndex: Int, attempt: Attempt): Decision = when {
        attempt.readyCount > 0 -> Decision.Succeeded(usedFallback = attemptIndex > 0)

        // 还有下一轮，而且这一轮的失败看起来是"转瞬即逝"的
        attemptIndex < STRATEGIES.lastIndex && attempt.allTransient -> Decision.Retry

        else -> Decision.GiveUp
    }

    /**
     * 把**所有轮次**的失败原因合并成一段可读文本。
     *
     * ============================================================
     * ⚠️ 为什么不能只报最后一轮
     * ============================================================
     * 真正有用的线索常常在**前一轮**：比如第一轮读到了 `getevent` 的
     * 用法提示（说明参数不对），第二轮因为探活更厚，报的可能只是
     * 一句笼统的"进程已退出"。只报最后一条，就把线索丢了。
     *
     * 这个项目已经踩过一次"证据被覆盖"（见 `Probe.evidence` 的说明），
     * 不能再犯。
     */
    fun describeFailures(attempts: List<Attempt>): String =
        attempts.mapIndexed { index, attempt ->
            val label = STRATEGIES.getOrNull(index)?.label ?: "第 ${index + 1} 轮"
            "【$label】" + attempt.failures.joinToString("；").ifBlank { "没有记录到原因" }
        }.joinToString("\n")

    /**
     * 两轮都没成时给用户看的那句话。
     *
     * 刻意把"两轮"写出来：如果只看到一轮的原因，用户会以为我们只试了一次。
     */
    fun failureMessage(attempts: List<Attempt>): String =
        "所有输入设备都无法读取（已尝试 ${attempts.size} 种启动策略）：\n" +
            describeFailures(attempts)
}

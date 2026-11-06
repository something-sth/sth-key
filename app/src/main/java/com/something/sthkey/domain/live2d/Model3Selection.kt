package com.something.sthkey.domain.live2d

/**
 * 在模型包里**挑哪一个 `*.model3.json`**。
 *
 * ============================================================
 * ⚠️ 为什么不能"取遍历到的第一个"
 * ============================================================
 * 旧实现是 `dir.walkTopDown().firstOrNull { 以 .model3.json 结尾 }`。
 * 实测样本 `鸣潮卡提希娅最牛逼键盘猫模型` 直接把它打穿了:
 *
 * ```
 * A-卡提/img/cat.model3.json          ← 深度 2，**孤儿**：引用的 cat.moc3 与
 *                                        cat.8192/… 在同一层根本不存在
 * A-卡提/img/keyboard/cat_model/cat.model3.json   ← 深度 4，完整
 * A-卡提/img/standard/cat_model/cat.model3.json   ← 深度 4，完整
 * A-卡提/img/gamepad/cat_model/cat.model3.json    ← 深度 4，完整
 * ```
 *
 * ⚠️ 结果:`walkTopDown` 的遍历顺序由文件系统决定，**可能**先命中那个孤儿 →
 * 报「模型缺少主体文件（Moc）」→ 导入失败。用户看到的就是"有时候能导入、
 * 有时候不行"—— 而这种"有时候"最难查。
 *
 * ============================================================
 * ⚠️ 第二个坑:一个包里可能有**多套变体**
 * ============================================================
 * BongoCat 的发行包常同时带 `keyboard` / `standard` / `gamepad` 三套，
 * 每套的**键帽资源各不相同**（`lefthand/` `righthand/` `face/` 都是各一套）。
 * 随便取一套的话，画出来的键帽会和模型对不上。
 *
 * ⭐ 所以排序规则是:
 * 1. **校验通过的优先**（资源不全的直接淘汰）
 * 2. 路径**浅的优先**（与 Axon 的 `chooseModel3` 一致）
 * 3. 同深度时按**变体优先级**:`keyboard` > `standard` > `gamepad` > 其它
 * 4. 还并列就按路径字典序（保证结果稳定、可重现）
 */
object Model3Selection {

    /**
     * 变体优先级。
     *
     * ⚠️ `keyboard` 排第一是因为"键盘猫"是 BongoCat 的主形态:
     * 三套都在的包里，用户十有八九要的是键盘那套（键帽最全）。
     */
    private val VARIANT_PRIORITY = listOf("keyboard", "standard", "gamepad")

    /** 搜索深度上限（与 Axon 的 `findFilesBySuffix(root, ".model3.json", 5)` 一致） */
    const val MAX_DEPTH = 5

    /**
     * 一个候选。
     *
     * @param path 相对包根的路径（正斜杠分隔，用于排序与日志）
     * @param depth 路径深度（`a/b/c.model3.json` = 2）
     * @param valid 资源是否齐全
     * @param reason 不齐全时的原因（给人看的，会进错误提示）
     */
    data class Candidate(
        val path: String,
        val depth: Int,
        val valid: Boolean,
        val reason: String? = null,
    )

    /** 选择结果 */
    sealed interface Choice {
        data class Picked(
            val path: String,
            /** 这个包里一共有几套候选（>1 说明是多变体包） */
            val total: Int,
            /** 命中的变体名（`keyboard` / `standard` / …）；识别不出为 null */
            val variant: String?,
        ) : Choice

        /** 一个 model3 都没有 */
        data object NoModel3 : Choice

        /** 有，但全部资源不全 */
        data class AllBroken(val total: Int, val reasons: List<String>) : Choice
    }

    /**
     * 从候选里挑一个。
     *
     * ⚠️ 纯函数:文件系统那一侧只负责把候选（含"是否有效"）算出来，
     * 排序与决策全在这里 —— 这样这段逻辑能在 JVM 单测里跑。
     */
    fun choose(candidates: List<Candidate>): Choice {
        if (candidates.isEmpty()) return Choice.NoModel3
        if (candidates.none { it.valid }) {
            return Choice.AllBroken(
                total = candidates.size,
                reasons = candidates.map { candidate ->
                    "${candidate.path}: ${candidate.reason ?: "资源不完整"}"
                },
            )
        }

        val best = chooseAll(candidates).first()
        return Choice.Picked(path = best.path, total = candidates.size, variant = best.variant)
    }

    /** [chooseAll] 里的一项 */
    data class Picked(val path: String, val variant: String?)

    /**
     * 把**所有有效候选**按同一套规则排好序返回。
     *
     * ⚠️ 多模式模型需要这个:一个包里可能同时带 `keyboard` / `standard` / `gamepad`
     * 三套完整模型，用户要能选。第一个就是 [choose] 会选中的那个（键盘优先）。
     *
     * ⚠️ 无效候选（孤儿 model3）不会出现在这里 —— 它们连页面都建不出来。
     */
    fun chooseAll(candidates: List<Candidate>): List<Picked> =
        candidates.filter { it.valid }
            .sortedWith(
                compareBy<Candidate> { it.depth }
                    .thenBy { variantRank(it.path) }
                    .thenBy { it.path.lowercase() },
            )
            .map { Picked(path = it.path, variant = variantOf(it.path)) }

    /** 路径里最靠近文件的那一层"变体名" */
    fun variantOf(path: String): String? {
        val segments = path.split('/').dropLast(1).map { it.lowercase() }
        /* ⚠️ 从最内层往外找:变体名通常紧挨着 cat_model/ 这种目录 */
        return segments.asReversed().firstOrNull { it in VARIANT_PRIORITY }
    }

    private fun variantRank(path: String): Int {
        val variant = variantOf(path) ?: return VARIANT_PRIORITY.size
        return VARIANT_PRIORITY.indexOf(variant).takeIf { it >= 0 } ?: VARIANT_PRIORITY.size
    }
}

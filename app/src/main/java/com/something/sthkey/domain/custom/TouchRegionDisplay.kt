package com.something.sthkey.domain.custom

/**
 * 触摸区域在**编辑器面板**与**屏幕方块**上显示的文字。
 *
 * ============================================================
 * 格式（用户确认过，见 `docs/custom-key-touch-region-plan.md` §11.2）
 * ============================================================
 * | 情况 | 文字 |
 * |---|---|
 * | 没绑定 | `按键区域  未绑定 ⚠` |
 * | 绑了一个 | `按键区域 (按键 · A)` |
 * | 绑了多个 | `轮盘区域 (按键 · A ↑、文本 · CPS ←)` |
 *
 * ⚠️ 组件名一律取 [displayName] —— 与自定义编辑页的预览**同一个函数**。
 * 自己拼一套（"按键 A" / "A 键"）的话，同一块区域在两个界面里叫法不同，
 * 用户会以为是两份配置。
 *
 * ⚠️ **未绑定必须说出来**（面板里还是红字）:旧模型最坑人的地方就是
 * "方块画着、按下去没反应"，而界面上完全看不出原因。
 *
 * ============================================================
 * ⚠️ 顺序按**组件列表**排，不按勾选先后
 * ============================================================
 * 同一份绑定，用户先勾 A 还是先勾 B 不该影响显示 —— 否则面板上的字
 * 会随手势跳来跳去（旧编辑器的摘要就特意这么排）。
 *
 * @param bindings `(组件 id, 方向)`；方向 `null` = 整块区域（摇到哪儿都算）
 * @param components 这份布局的全部组件（用来把 id 翻成名字）
 */
fun touchRegionLabel(
    kind: CustomTouchRegion.Kind,
    bindings: List<Pair<String, TouchDirection?>>,
    components: List<CustomComponent>,
): String {
    if (bindings.isEmpty()) return kind.label + "  未绑定 ⚠"

    val knownIds = components.mapTo(mutableSetOf()) { it.id }
    val parts = buildList {
        /* ① 按组件列表顺序 */
        components.forEach { component ->
            val hits = bindings.filter { it.first == component.id }
            if (hits.isEmpty()) return@forEach
            add(component.displayName() + directionSuffix(hits))
        }
        /*
         * ② 组件已经被删掉、但绑定还留着的那些 —— 显示 id，而不是当成没绑。
         *
         * ⚠️ 显示成"未绑定"的话，用户会以为绑定丢了；而实际上他还得
         * 进绑定页把这条失效绑定取消掉（绑定页里也会因为组件没了而不显示它，
         * 所以点「确定」时会自动清掉 —— 见面板那边的说明）。
         */
        bindings.map { it.first }.distinct()
            .filterNot { it in knownIds }
            .forEach { id -> add(id + directionSuffix(bindings.filter { it.first == id })) }
    }
    return kind.label + " (" + parts.joinToString("、") + ")"
}

/**
 * 一条组件绑定的方向后缀。
 *
 * - 没有方向（整块区域）→ 空串（不占地方，语义就是"不限"）
 * - 有方向 → `" ↑←"` 这种（同方向只写一次）
 */
private fun directionSuffix(hits: List<Pair<String, TouchDirection?>>): String {
    val dirs = hits.mapNotNull { it.second }.distinct().sortedBy { it.ordinal }
    if (dirs.isEmpty()) return ""
    return " " + dirs.joinToString("") { directionArrow(it) }
}

/**
 * 方向的显示字符（**唯一**映射）。
 *
 * ============================================================
 * ⚠️ 为什么放在 domain 而不是面板里
 * ============================================================
 * 面板的九宫格、区域标题上的方向后缀、将来的诊断输出都要用它。
 * 三处各写一份 `when` 的话，加第九个方向时必然漏掉一处 ——
 * 而漏掉的地方**不会报错**，只会显示成空白或问号。
 *
 * ⚠️ `when` 不留 `else`:给 [TouchDirection] 加新项时编译器会在这里报错。
 */
fun directionArrow(direction: TouchDirection?): String = when (direction) {
    null -> "无"
    TouchDirection.FRONT -> "↑"
    TouchDirection.BACK -> "↓"
    TouchDirection.LEFT -> "←"
    TouchDirection.RIGHT -> "→"
    TouchDirection.FRONT_LEFT -> "↖"
    TouchDirection.FRONT_RIGHT -> "↗"
    TouchDirection.BACK_LEFT -> "↙"
    TouchDirection.BACK_RIGHT -> "↘"
}

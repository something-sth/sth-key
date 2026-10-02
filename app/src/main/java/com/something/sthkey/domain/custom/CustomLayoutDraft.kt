package com.something.sthkey.domain.custom

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 编辑器的**草稿状态**（撤回栈 + 可观察）。
 *
 * ============================================================
 * 两件事：不进实时保存 + 撤回/恢复
 * ============================================================
 * 需求里明确"全部做完再点保存"，理由有二：出 bug 不好维护、
 * 而且下面已经有图形化预览可以看效果。所以编辑期间**只改这份草稿**，
 * 点保存才写回配置。
 *
 * 撤回/恢复用**整份快照栈**而不是"操作对象 + 反操作"：
 * 组件只有几十个、每个都是小对象，一次快照几 KB；
 * 而"反操作"要为每种编辑各写一份逆运算，写错一个就是"撤回之后状态不对"
 * 这种极难查的问题。快照栈的语义**永远正确**，代价只是内存。
 *
 * ============================================================
 * ⚠️ 它必须是 Compose **可观察**的（这里踩过一个很严重的坑）
 * ============================================================
 * 第一版里 `history` 是个普通 `mutableListOf`，`current` 是个普通 `val` ——
 * 也就是说**改草稿不会触发任何重组**。当时我另加了一个 `revision` 计数器，
 * 在调用处 `refresh()` 自增，以为"读了它的地方就会重组"。
 *
 * 结果是：**整个编辑界面几乎都是死的**。开关点了没反应、滑块拖不动、
 * 输入框打不进字（打进去的字符立刻被旧值覆盖）。唯一"能用"的是组件列表切换
 * 与保存按钮 —— 因为它们读的是 `selectedId`、`draft.dirty` 这些**真正的**状态。
 *
 * 现在把整份状态装进一个 `mutableStateOf`：任何 `draft.current` 的读取
 * 都会自动订阅重组，**调用方再也不需要手动 refresh**。
 * 手写刷新令牌这种东西，漏一处就是一个"点了没反应"的 bug，别再引入了。
 */
class CustomLayoutDraft(initial: CustomLayoutSettings) {

    /**
     * 整份状态打包成一个 Compose 状态量。
     *
     * 用一个 holder 而不是三个 `mutableStateOf`：读取集中在一处，
     * 写回时也只产生一次失效，比"改三个字段触发三次重组"更省。
     */
    private var state by mutableStateOf(
        DraftState(
            history = listOf(initial),
            cursor = 0,
            saved = initial,
            stepOpen = false,
        ),
    )

    /** 当前状态；编辑器渲染的就是它。**读它就会订阅重组** */
    val current: CustomLayoutSettings get() = state.history[state.cursor]

    /**
     * 能不能撤回 —— 判据是"**上一步和现在真的不一样**"。
     *
     * ============================================================
     * ⚠️ 不能只看 `cursor > 0`（这里踩过坑）
     * ============================================================
     * [mutateOnce] 一开步就把 `cursor` 推到新的一格（那格是当前位置的
     * **副本**，内容与上一格完全相同），所以"开步"这个动作本身就让
     * `cursor > 0` 成立了 —— 与用户有没有改东西无关。
     *
     * 表现是：**打开编辑页撤回按钮就亮着**，点下去什么变化都没有；
     * 点一下输入框（只聚焦、不打字）也会让它亮起来。
     *
     * 所以判据必须比较**内容**：上一格与当前格不同才算能撤回。
     * 这也顺带管住了"连续操作结束时发现没有实际变化"的情况 ——
     * 即使调用方忘了 [finishStep]，撤回也不会亮。
     */
    val canUndo: Boolean
        get() = state.cursor > 0 &&
            state.history[state.cursor] != state.history[state.cursor - 1]

    /** 同理：下一格与当前格不同才算能恢复 */
    val canRedo: Boolean
        get() = state.cursor < state.history.lastIndex &&
            state.history[state.cursor] != state.history[state.cursor + 1]

    /** 有没有未保存的改动（与 [saved] 比较） */
    val dirty: Boolean get() = current != state.saved

    /** 供调试/测试观察历史深度 */
    val historySize: Int get() = state.history.size

    /** 当前在历史里的位置 */
    val historyCursor: Int get() = state.cursor

    /*
     * ============================================================
     * 写入
     * ============================================================
     */

    /**
     * 一次"操作"的开始（拖动/滑块的**起点**）。
     *
     * 把当前位置的内容**复制一份**推到后面、指针移过去，
     * 后续 [assign] 改的是这一格 —— 起点原封不动留在 `cursor - 1`，
     * 于是"拖一次 = 撤回一步"。
     *
     * 连续调用是安全的：`stepOpen` 挡住重复压栈。
     */
    fun mutateOnce() {
        val snapshot = state
        if (snapshot.stepOpen) return

        val history = snapshot.history.toMutableList()
        // 在新的操作前丢掉"未来"分支：撤回几步后又改东西，原来那条路就不该再能恢复
        while (history.size > snapshot.cursor + 1) history.removeAt(history.lastIndex)

        history += history[snapshot.cursor]
        var cursor = history.lastIndex

        // 历史太长就丢最旧的，但**至少保留一格可撤回**（否则撤回会突然失效）
        while (history.size > MAX_HISTORY && cursor > 1) {
            history.removeAt(0)
            cursor--
        }

        state = snapshot.copy(history = history, cursor = cursor, stepOpen = true)
    }

    /** 只改当前状态，不进历史（拖动过程中调用） */
    fun assign(transform: (CustomLayoutSettings) -> CustomLayoutSettings) {
        val snapshot = state
        val history = snapshot.history.toMutableList()
        history[snapshot.cursor] = transform(history[snapshot.cursor])
        state = snapshot.copy(history = history)
    }

    /**
     * 一次"操作"的结束（手指抬起、输入框失焦、按钮点击后）。
     *
     * - 与起点相比**没有实际变化**时把那一格撤掉，不留"撤回没反应"的空步；
     * - 没有以 [mutateOnce] 打底的原子操作（点一下开关）则整体作为一步。
     */
    fun finishStep() {
        val snapshot = state

        if (!snapshot.stepOpen) {
            pushSnapshot(snapshot.history[snapshot.cursor])
            return
        }

        val history = snapshot.history.toMutableList()
        var cursor = snapshot.cursor
        if (cursor > 0 && history[cursor] == history[cursor - 1]) {
            history.removeAt(cursor)
            cursor--
        }
        while (history.size > MAX_HISTORY && cursor > 1) {
            history.removeAt(0)
            cursor--
        }

        state = snapshot.copy(history = history, cursor = cursor, stepOpen = false)
    }

    /** 一步到位的改动（按钮、开关这类不会有连续中间状态的） */
    fun commit(transform: (CustomLayoutSettings) -> CustomLayoutSettings) {
        mutateOnce()
        assign(transform)
        finishStep()
    }

    private fun pushSnapshot(snapshot: CustomLayoutSettings) {
        val current = state
        val history = current.history.toMutableList()
        while (history.size > current.cursor + 1) history.removeAt(history.lastIndex)

        // 与当前格完全相同就不必多记一步
        if (history[current.cursor] == snapshot && history.size > 1) return

        history += snapshot
        var cursor = history.lastIndex
        while (history.size > MAX_HISTORY && cursor > 1) {
            history.removeAt(0)
            cursor--
        }

        state = current.copy(history = history, cursor = cursor)
    }

    /**
     * 撤回一步。
     *
     * ⚠️ 要**跳过内容相同的格子**，不能只退一格。
     *
     * 历史里会存在"内容与相邻格相同"的项（`mutateOnce` 开步时压入的那格
     * 就是当前位置的副本）。只退一格的话，用户点了撤回**画面毫无变化**，
     * 以为撤回坏了 —— 而那一步其实"确实没有可撤的内容"。
     */
    fun undo() {
        val snapshot = state
        var target = snapshot.cursor - 1
        while (target > 0 &&
            snapshot.history[target] == snapshot.history[snapshot.cursor]
        ) {
            target--
        }
        if (snapshot.history.getOrNull(target) == snapshot.history[snapshot.cursor]) return
        state = snapshot.copy(cursor = target, stepOpen = false)
    }

    /** 恢复一步；同样跳过内容相同的格子 */
    fun redo() {
        val snapshot = state
        var target = snapshot.cursor + 1
        while (target < snapshot.history.lastIndex &&
            snapshot.history[target] == snapshot.history[snapshot.cursor]
        ) {
            target++
        }
        if (snapshot.history.getOrNull(target) == snapshot.history[snapshot.cursor]) return
        state = snapshot.copy(cursor = target, stepOpen = false)
    }

    /** 保存成功后调用：把"未保存"的基准对齐到当前 */
    fun markSaved() {
        state = state.copy(saved = current, stepOpen = false)
    }

    /*
     * ============================================================
     * 组件级操作
     * ============================================================
     */

    /** 添加组件（一步） */
    fun add(component: CustomComponent) = commit { settings ->
        settings.copy(components = settings.components + component)
    }

    /** 删除组件（一步） */
    fun remove(componentId: String) = commit { settings ->
        settings.copy(components = settings.components.filterNot { it.id == componentId })
    }

    /**
     * 替换一个组件的内容。
     *
     * ⚠️ 按 **id** 找、按 id 换：不能用下标 ——
     * 撤回之后下标可能与调用方手里那个不一致，换错组件就是
     * "改一个另一个也变了"的翻版。
     *
     * @param asStep true 表示这是独立一步（按钮类改动）；
     *   false 表示它属于某个进行中的连续操作（滑块/拖动），由调用方负责首尾。
     */
    fun replace(component: CustomComponent, asStep: Boolean = true) {
        val transform: (CustomLayoutSettings) -> CustomLayoutSettings = { settings ->
            settings.copy(
                components = settings.components.map {
                    if (it.id == component.id) component else it
                },
            )
        }
        if (asStep) commit(transform) else assign(transform)
    }

    /*
     * ============================================================
     * 曾经有吸附设置的一对 API（setSnapEnabled / begin-update-endSnapGapDrag），
     * 已经随吸附一起删掉
     * ============================================================
     * 那两个字段（snapEnabled / snapGapDp）现在也不在 [CustomLayoutSettings] 里了，
     * 所以这些方法编译都过不去 —— 一并移除，不留半成品。
     *
     * 这里曾经留下过一条很有价值的教训，保留下来：
     *
     * ⚠️ **半成品 API 不对外**。当时 `setSnapGap(gap) = assign { … }` 是
     * "只改中间、不开步也不收尾"的那种半成品：历史一次都不会推进，
     * 撤回永远是灰的，而且它看起来完全正常，调用方不会怀疑。
     * 对外的连续操作只给 `beginXxxDrag / updateXxxDrag / endXxxDrag` 一整套 ——
     * 只要用到其中一个，另外两个必然会被调用（拖动一定有开始和结束）。
     */

    /** 恢复默认布局（这是个"大操作"，进历史、可以撤回） */
    fun resetToDefault() = commit { it.copy(components = defaultCustomComponents()) }

    /** 草稿的内部状态；由 [asState] 暴露给需要整体观察的调用方 */
    data class DraftState(
        val history: List<CustomLayoutSettings>,
        val cursor: Int,
        val saved: CustomLayoutSettings,
        val stepOpen: Boolean,
    )

    companion object {
        /**
         * 历史深度上限。
         *
         * 100 步对"摆一个悬浮窗布局"远远够用，而每步只是几十个小对象，
         * 内存可以忽略。设上限是为了防止极端情况下无限增长。
         */
        const val MAX_HISTORY = 100
    }
}

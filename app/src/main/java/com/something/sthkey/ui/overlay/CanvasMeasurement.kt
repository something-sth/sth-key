package com.something.sthkey.ui.overlay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 自定义 Key 画布的**实际渲染几何**（诊断用）。
 *
 * ============================================================
 * 为什么需要它
 * ============================================================
 * 排查"悬浮窗上空格右边短一截"时，我把声明的数值链（窗口尺寸、
 * 包围盒、组件坐标、缩放换算）反复验证过，**每一环都自洽**，
 * 从代码里推不出任何裁剪。
 *
 * 最后靠这里的数据才定位：画布请求 272.1px、**实际只渲染出 261.0px**，
 * 而容器在纸面上明明有 273px 可用。画布被压小之后，
 * 只有"声明宽度等于画布宽"的那两个组件（空格、Shift）显形。
 *
 * 教训：**渲染层的真实值只能由布局给出，读代码推不出来。**
 * 所以这个上报保留下来 —— 以后再出现"某个组件尺寸不对"，
 * 一眼就能看到是容器给少了、还是组件自己错了。
 *
 * ============================================================
 * 为什么用全局单例
 * ============================================================
 * 画布在**悬浮窗**里渲染，而调试页在**主界面**——两者不是同一棵
 * Compose 树，没法用 `remember` + 状态提升传递。
 *
 * 多个悬浮窗同时显示时记的是最后完成布局的那个（面板会写明）。
 */
object CanvasMeasurement {

    /** 最近一次画布布局的测量结果；还没有画布时不显示 */
    var latest: Snapshot? by mutableStateOf(null)
        private set

    data class Snapshot(
        /** 窗口尺寸按公式推出来的值（像素）—— 只是参考，不参与判定 */
        val requestedWidthPx: Float,
        val requestedHeightPx: Float,
        /** 实际渲染出来的尺寸（像素）：自适应模式下由容器空间反推 */
        val actualWidthPx: Float,
        val actualHeightPx: Float,
        /** 容器**真实**给了多少空间（像素）；自适应的判据就是它 */
        val availableWidthPx: Float,
        val availableHeightPx: Float,
    ) {
        /**
         * 渲染尺寸是否超出了容器空间。
         *
         * ⚠️ 判据用**容器空间**，不是"窗口推算值"。
         *
         * 自适应模式下渲染尺寸必然 ≤ 容器空间，正常时这里永远是 false。
         * 而"推算值 ≠ 容器空间"是**允许的**：窗口尺寸是按公式算的，
         * 与容器实际给的空间差几个像素不算问题 —— 把它报成问题只会误导人
         * （排查过程中就是这么被带偏了两轮）。
         */
        val overflowsContainer: Boolean
            get() = actualWidthPx > availableWidthPx + 1f ||
                actualHeightPx > availableHeightPx + 1f
    }

    fun record(
        requestedWidthPx: Float,
        requestedHeightPx: Float,
        actualWidthPx: Float,
        actualHeightPx: Float,
        availableWidthPx: Float,
        availableHeightPx: Float,
    ) {
        latest = Snapshot(
            requestedWidthPx = requestedWidthPx,
            requestedHeightPx = requestedHeightPx,
            actualWidthPx = actualWidthPx,
            actualHeightPx = actualHeightPx,
            availableWidthPx = availableWidthPx,
            availableHeightPx = availableHeightPx,
        )
    }

    /** 固定尺寸模式（编辑器画布）：没有"容器空间"这回事，用自身尺寸充当 */
    fun recordExact(widthPx: Float, heightPx: Float) {
        latest = Snapshot(
            requestedWidthPx = widthPx,
            requestedHeightPx = heightPx,
            actualWidthPx = widthPx,
            actualHeightPx = heightPx,
            availableWidthPx = widthPx,
            availableHeightPx = heightPx,
        )
    }

    /**
     * 每个组件的测量结果，按 id 存。
     *
     * 用映射而不是列表：每帧布局都会回调所有组件，追加进列表的话
     * 旧值会越积越多、而且混着过期数据（比没有更糟）。
     * 按 id 覆盖就没有这个问题，也不需要维护"一帧的开始与结束"。
     */
    var components: Map<String, ComponentBox> by mutableStateOf(emptyMap())
        private set

    data class ComponentBox(
        val id: String,
        /** 组件声明的宽度换算成像素 */
        val declaredWidthPx: Float,
        /** 布局实际量到的宽度（像素） */
        val actualWidthPx: Float,
    )

    fun addComponentBox(box: ComponentBox) {
        components = components + (box.id to box)
    }

    /** 面板里的一段文字说明 */
    fun report(): String {
        val snapshot = latest ?: return "画布还没有完成布局（悬浮窗没开？）"

        return buildString {
            appendLine(
                "容器可用：${"%.1f".format(snapshot.availableWidthPx)} × " +
                    "${"%.1f".format(snapshot.availableHeightPx)} px",
            )
            appendLine(
                "画布渲染：${"%.1f".format(snapshot.actualWidthPx)} × " +
                    "${"%.1f".format(snapshot.actualHeightPx)} px" +
                    "（按容器空间反推，必然装得下）",
            )
            appendLine(
                "窗口推算：${"%.1f".format(snapshot.requestedWidthPx)} × " +
                    "${"%.1f".format(snapshot.requestedHeightPx)} px" +
                    "（参考值，与容器有出入属正常）",
            )
            /*
             * 判据是"渲染尺寸有没有超出容器空间"。
             *
             * 自适应之后正常时永远是 false —— 它一旦为 true，
             * 就说明真的有内容溢出了，值得报警。
             */
            if (snapshot.overflowsContainer) {
                appendLine("  ⚠️ 渲染尺寸超出容器空间 —— 内容会被裁")
            } else {
                appendLine("  ✓ 渲染尺寸没有超出容器（内容不会被裁）")
            }
            appendLine("  （多个悬浮窗时这里显示最后完成布局的那个）")

            appendComponents()
        }.trimEnd()
    }

    /**
     * 每个组件的**实际像素几何**：声明的宽 vs 量到的宽。
     *
     * 这是"到底哪个组件没拿到它该有的宽度"最直接的答案 ——
     * 声明的宽度在域层已经反复验证过是对的，所以两者一旦不同，
     * 问题就在渲染层，而且能直接看出差多少。
     */
    private fun StringBuilder.appendComponents() {
        val boxes = components.values.sortedBy { it.id }
        if (boxes.isEmpty()) {
            appendLine("  （还没有组件的测量数据）")
            return
        }

        appendLine("  组件实际几何：")
        boxes.forEach { box ->
            val shortfall = box.declaredWidthPx - box.actualWidthPx
            /*
             * ⚠️ 容差 2px：自适应之后渲染尺寸是反推出来的，与"声明值 ×
             * 名义缩放"本来就会差千分之几，那是正常误差不是问题。
             */
            val flag = if (shortfall > 2f) {
                "  ⚠️ 比声明的窄 ${"%.1f".format(shortfall)}px"
            } else {
                ""
            }
            appendLine(
                "    ${box.id.take(16).padEnd(17)}" +
                    " 声明宽 ${"%.1f".format(box.declaredWidthPx)}" +
                    " 实得宽 ${"%.1f".format(box.actualWidthPx)}$flag",
            )
        }
        /*
         * 贴边的组件"实得宽 < 声明宽"是**正常现象**，不是错误。
         *
         * 它声明了自己铺满整块画布，而画布就是容器那么大 ——
         * 于是它被裁到与画布同宽。这正是"空格铺满一行"的设计意图。
         *
         * 写在面板里是为了不再有人（包括我）把它当成新 bug：
         * 排查过程中这条警告两次把我引向"空格被裁"这个错误方向。
         */
        appendLine("  （贴边的组件实得宽 = 画布宽，属正常；只要上面那行是 ✓ 就没有内容被裁）")
    }
}

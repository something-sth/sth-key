package com.something.sthkey.domain.style

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * 摇杆的**几何与手感**——全部是纯函数。
 *
 * ============================================================
 * 为什么单独一个文件、而且不留一点 UI 依赖
 * ============================================================
 * 摇杆"怎么写"看起来很难说清，其实难点只有一处:
 * **输入向量 `(x, y)` 该映射到圆里的哪个点**。这是个纯几何问题，
 * 与 Compose 无关 —— 抽出来就能写测试，而测试是唯一能在
 * 真机之外验证"手感参数对不对"的东西。
 *
 * 剩下的部分（背景圆、拇指圆、动画）就是"把算出来的偏移画上去"，
 * 没有任何理论难度。
 *
 * ============================================================
 * 坐标系
 * ============================================================
 * 输入 `(x, y)` 是**归一化后的**手柄值:`-1f .. 1f`，
 * **Y 向上为正**（与 [com.something.sthkey.capture.StickState] 一致）。
 *
 * ⚠️ 屏幕坐标 Y **向下**为正，所以调用方把结果画到屏幕时必须
 * **对 Y 取反** —— 忘了取反的表现是"往上推摇杆、拇指往下走"。
 * 这里刻意不代劳:让"数学"与"像素"各自保持自己的约定，
 * 取反只在一处发生（渲染时），比两边都偷偷取反好查得多。
 *
 * ============================================================
 * 参数来自哪
 * ============================================================
 * | 参数 | 值 | 依据 |
 * |---|---|---|
 * | 死区 | 采集层已经去过（`AxisNormalizer.STICK_DEAD_ZONE`） | 硬件 + 手感死区在采集层处理 |
 * | 混合区间 | `0.6 .. 0.9` | 照抄参考实现（小幅度精确、大幅度能到边角） |
 * | WASD 阈值 | `0.35` | 照抄参考实现（低于它点不亮，手感迟钝） |
 */
object StickGeometry {

    /**
     * 线性映射与方形映射的**混合区间**。
     *
     * - 长度 <= [BLEND_START]：**全线性**（小幅度移动精确跟手）
     * - 长度 >= [BLEND_END]：**全方形**（推到底能到外圈的角）
     * - 中间：线性插值
     *
     * ⚠️ **为什么需要方形映射**：纯线性映射下，拇指只能到
     * "半径 = 最大偏移"的圆上 —— 斜着推 45° 时到不了外圈的**角落**。
     * 而真摇杆的可动范围是一个**方形**（四个角都推得到）。
     *
     * ⚠️ **为什么又不能纯方形**：方形映射会把方向**量化** ——
     * 小幅度移动时方向会跳到最近的轴，看起来"很生硬"。
     *
     * 所以两者混合。这个思路来自一份做过 iOS 手柄显示的参考实现，
     * 这里把它写成可测试的几何函数。
     */
    const val BLEND_START = 0.6f

    /** 超过它就完全用方形映射 */
    const val BLEND_END = 0.9f

    /**
     * WASD 样式的点亮阈值。
     *
     * ⚠️ 取 `0.35` 而不是 `0.5`：手柄摇杆的**物理**死区通常在 0.2 左右，
     * 阈值设到 0.5 的话玩家要推很大力才点亮，手感迟钝。
     *
     * ⚠️ 它与采集层的死区是**两个不同的东西**:
     * 采集层的死区决定"拇指什么时候开始动"，这个阈值决定
     * "WASD 什么时候亮"。两者可以不同 —— 拇指动了但还没点亮是完全合理的。
     */
    const val WASD_THRESHOLD = 0.35f

    /**
     * 算出拇指的偏移量。
     *
     * @param x 归一化输入 X（右为正）
     * @param y 归一化输入 Y（**上为正**）
     * @param maxOffset 最大偏移（像素）——"推到底"时拇指中心离中心多远
     * @return 偏移 `(dx, dy)`，同样是**Y 向上为正**；画的时候要取反
     */
    fun thumbOffset(x: Float, y: Float, maxOffset: Float): Pair<Float, Float> {
        val magnitude = hypot(x, y)
        if (magnitude <= 0f) return 0f to 0f

        /* 单位方向。长度与方向分开算，后面混合的只是"该走多远" */
        val ux = x / magnitude
        val uy = y / magnitude

        /* 推到底的强度（0..1）：输入长度已经归一化，直接夹到 1 */
        val strength = min(1f, magnitude)

        val blend = ((strength - BLEND_START) / (BLEND_END - BLEND_START)).coerceIn(0f, 1f)

        val distance = maxOffset * strength * blendedRadiusFactor(ux, uy, blend)
        return ux * distance to uy * distance
    }

    /**
     * 混合映射下"这个方向能走多远"相对 [thumbOffset] 里 `strength` 的倍数。
     *
     * ============================================================
     * 几何含义（这是"摇杆怎么写"的答案）
     * ============================================================
     * 把方向拆成两种情况 —— **哪根轴占主导，就由哪根轴决定外边界**:
     *
     * | 情况 | 线性映射能走多远 | 方形映射能走多远 |
     * |---|---|---|
     * | `|x| >= |y|`（偏向左右） | `1`（到内切圆） | `1/|x|`（到方框竖边） |
     * | 否则（偏向上下） | `1`（到内切圆） | `1/|y|`（到方框横边） |
     *
     * 再按 [blend] 在两者之间插值。
     *
     * - 纯左右推（`|x|=1, y=0`）：两种映射都给 `1` —— 没有区别；
     * - 斜推 45°（`|x|=|y|=0.707`）：线性给 `1`（圆上），
     *   方形给 `1.414`（**方框的角上**）—— 这正是"外圈角落"的来历。
     *
     * ⚠️ 这里就是参考实现里那段"看不太懂"的代码的几何解释:
     * 它**不是**什么玄学手感调参，就是"轴独立钳制 + 强度插值"。
     */
    private fun blendedRadiusFactor(ux: Float, uy: Float, blend: Float): Float {
        val dominant = maxOf(abs(ux), abs(uy))

        /*
         * ⚠️ `dominant` 理论上不会是 0（magnitude > 0 时至少一个分量非零），
         * 但浮点下极小值可能出现 —— 兜一下，避免除出 Infinity 把拇指甩飞。
         */
        if (dominant <= 1e-6f) return 1f

        val square = 1f / dominant
        return 1f + (square - 1f) * blend
    }

    /**
     * 摇杆点亮了哪些方向键（WASD 样式用）。
     *
     * 两个轴**互相独立**判定，所以斜推时能同时点亮两个键（W + D）。
     *
     * @return `(left, right, up, down)`
     */
    fun wasdDirections(
        x: Float,
        y: Float,
        threshold: Float = WASD_THRESHOLD,
    ): WasdDirections = WasdDirections(
        left = x <= -threshold,
        right = x >= threshold,
        up = y >= threshold,
        down = y <= -threshold,
    )

    /**
     * 方向键（D-Pad）的点亮状态。
     *
     * ⚠️ 与 [wasdDirections] **不能共用**：方向键是**离散**的
     * （`-1 / 0 / 1` 三个值），没有"推了一半"这回事。
     * 用阈值去判它反而会在模拟量略有偏差时点不亮 ——
     * 而那种手柄的轴本来就只报三个值。
     *
     * @param hatX 方向键 X（`-1 / 0 / 1`）
     * @param hatY 方向键 Y（**上为正**）
     */
    fun hatDirections(hatX: Float, hatY: Float): WasdDirections = WasdDirections(
        left = hatX < 0f,
        right = hatX > 0f,
        up = hatY > 0f,
        down = hatY < 0f,
    )
}

/** 四个方向的点亮状态 */
data class WasdDirections(
    val left: Boolean = false,
    val right: Boolean = false,
    val up: Boolean = false,
    val down: Boolean = false,
) {
    /** 有没有任何一个方向亮着 */
    val any: Boolean get() = left || right || up || down
}

package com.something.sthkey.domain.config

/**
 * 触摸区域的**几何换算** —— 唯一一份。
 *
 * ============================================================
 * ⚠️⚠️ 为什么必须收成一份（用户问出来的）
 * ============================================================
 * 用户的原话:
 *
 * > 我们现在能**保证调节的触摸区域方框保存后一定跟实际触摸区域对应**吗，
 * > 你检查一下？
 *
 * ⚠️ 这个担心是**对的**，而且我当时确实有两处各写了一遍:
 *
 * | 谁 | 算什么 | 在哪 |
 * |---|---|---|
 * | 编辑器 | 方块窗口的 `x/y/宽/高`（像素） | `TouchRegionEditorService.syncRegionGeometry` |
 * | 采集 | 命中判定的边界（归一化） | `TouchInput.regionContains` |
 *
 * 两者**当时恰好一致**，但没有任何东西保证以后也一致 ——
 * 而"画出来的方块"与"真正生效的范围"不一致，用户是**查不出来的**:
 * 他只会觉得"我明明按在方块上却没反应"。
 *
 * ⚠️ 更糟的是它们**已经**差了一处:编辑器给窗口加了"最小 32dp"的下限，
 * 而命中判定没有 —— 区域很小时**画的比判定的大**。
 *
 * ⚠️ 所以现在两处都从**这一个函数**取边界。改公式只改这里，
 * 两处**必然**同步。
 *
 * ============================================================
 * ⚠️ 两个单位（这是最容易搞混的地方，写在最前面）
 * ============================================================
 * | 量 | 单位 | 为什么 |
 * |---|---|---|
 * | 中心 | **整屏归一化**（`0..1` 指整屏宽/高） | 让区域能放到屏幕任何位置（左撇子/右手摇杆） |
 * | 边长 | **较短边归一化**（`size` 指短边的比例） | 让正方形不随朝向变形 |
 *
 * ⚠️ 所以"归一化坐标下的半宽"要乘 `短边/屏宽`（不是 `size/2`）——
 * 横屏下屏宽是短边的 2 倍多，漏了这个因子会让判定范围**横向缩成一半**。
 */
object TouchRegionGeometry {

    /**
     * 触摸区域的边距（像素）—— **画与判定共用**。
     *
     * ============================================================
     * ⚠️ 为什么要一个下限
     * ============================================================
     * 区域可以调到很小（`SIZE_MIN = 0.10`），而横屏时
     * `0.10 × 短边` 可能只有几十像素 —— **比手指还窄**，
     * 用户根本按不中（他报的"偶尔才能亮起来"就是这个）。
     *
     * ⚠️ 关键是这个下限**必须两边都加**:只加在画的那一侧，
     * 就会出现"方块看着挺大、按上去没反应"。
     */
    const val MIN_SIDE_DP = 32f

    /**
     * 一块区域在屏幕上的像素边界。
     *
     * @param centerX 中心 X，整屏宽的比例
     * @param centerY 中心 Y，整屏高的比例
     * @param size 边长，屏幕**较短边**的比例
     * @param screenWidth 屏幕宽（像素）
     * @param screenHeight 屏幕高（像素）
     * @param density 显示密度（用于 [MIN_SIDE_DP] 换算）；`<= 0` 时跳过后限
     */
    data class Bounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /**
     * 算出边界（像素），**四舍五入到整数像素**。
     *
     * ⚠️ 用 `roundToInt` 而不是截断:截断会让边界**整体偏小半个像素**，
     * 而"画"与"判定"如果一边截断一边四舍五入，边界就会差 1 像素 ——
     * 1 像素本身无所谓，但那是"两处不一致"的种子。
     *
     * ⚠️ 下限 [MIN_SIDE_DP] 在这里统一生效，所以**画与判定必然同尺寸**。
     */
    fun bounds(
        centerX: Float,
        centerY: Float,
        size: Float,
        screenWidth: Int,
        screenHeight: Int,
        density: Float,
    ): Bounds {
        val w = screenWidth.coerceAtLeast(1)
        val h = screenHeight.coerceAtLeast(1)
        val short = minOf(w, h)

        val minSide = if (density > 0f) (MIN_SIDE_DP * density).toInt() else 0
        val side = (size * short).toInt().coerceAtLeast(minSide).coerceAtLeast(1)

        val cx = (centerX * w)
        val cy = (centerY * h)

        /*
         * ⚠️ 用 `roundToInt` 而不是 `toInt`（截断）——
         * 边界与窗口位置**必须是同一个取整**，否则会差一像素。
         * 窗口那个 `params.x` 是整数，所以这里也只能是整数。
         */
        val left = kotlin.math.round(cx - side / 2f).toInt()
        val top = kotlin.math.round(cy - side / 2f).toInt()

        return Bounds(left = left, top = top, left + side, top + side)
    }

    /**
     * 边界换算回**归一化**坐标（命中判定用）。
     *
     * ⚠️ 采集侧与编辑器**必须走同一个 [bounds]**，再各自换算 ——
     * 这样"像素边界"是同一个真源，两边不可能漂。
     */
    fun normalizedBounds(
        centerX: Float,
        centerY: Float,
        size: Float,
        screenWidth: Int,
        screenHeight: Int,
        density: Float,
    ): FloatArray {
        val b = bounds(centerX, centerY, size, screenWidth, screenHeight, density)
        val w = screenWidth.coerceAtLeast(1).toFloat()
        val h = screenHeight.coerceAtLeast(1).toFloat()
        /* [left, top, right, bottom] */
        return floatArrayOf(b.left / w, b.top / h, b.right / w, b.bottom / h)
    }
}

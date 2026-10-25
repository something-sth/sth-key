package com.something.sthkey.capture.touch

import com.something.sthkey.domain.config.TouchRegionGeometry
import com.something.sthkey.domain.config.TouchRegion
import com.something.sthkey.domain.config.TouchStickFeel

/**
 * 触屏输入算法 —— **纯函数**（不碰 Android，可完整单测）。
 *
 * ============================================================
 * 两个摇杆的语义完全不同
 * ============================================================
 * | | 移动轮盘 | 视角摇杆 |
 * |---|---|---|
 * | 检测范围 | **触摸区域内** | **没落在任何区域里的地方** |
 * | 位置怎么算 | 相对**区域中心**的偏移 | 相对**上一帧**的增量 |
 * | 手感 | 摇杆（推得越远越偏，有死区） | 触摸板（滑多少走多少） |
 * | 松手后 | 回到中心（向量变 0） | **停在原处不回中** |
 *
 * ⚠️ 最后一行是用户明确的:"松手后不做回中，因为很难写，
 * 鼠标摇杆写过回中但是 bug 一大堆后面删了"。
 *
 * ============================================================
 * ⚠️⚠️ 单位:一切都在「**较短边归一化**」坐标系里
 * ============================================================
 * 这里有个必须一次性想清的问题:区域的"边长"说的是"占**较短边**的比例"，
 * 而触点的 `x/y` 是"占整屏宽/高"的 `0..1` —— **两者单位不同**。
 *
 * 不统一的话:
 * - 横屏（16:9）下，区域边长 `0.3` 在 X 方向是 `0.3 × 短边 = 0.3 × 高`，
 *   换算成"屏宽的几分之几"只有 `0.3 × 9/16 ≈ 0.17` ——
 *   于是同样标着 `0.3`，**横屏看起来比竖屏窄一半**；
 * - 死区/灵敏度的基准也跟着飘（"同一个值在两种朝向下手感不一样"）。
 *
 * ⚠️ 所以本文件**先把触点换算到"较短边归一化"坐标系**再算:
 *
 * ```
 * 短边 = min(屏宽, 屏高)          // 以像素为单位，消掉即可
 * X' = x × 屏宽 / 短边            // 范围 0 .. 长边/短边
 * Y' = y × 屏高 / 短边            // 范围 0 .. 长边/短边
 * ```
 *
 * ⚠️ 与 [TouchRegion] 的中心同单位（那也是较短边归一化）——
 * 于是"区域在哪、触点在哪、边长多少"三者可以直接比较，
 * 中间不需要任何换算函数。
 *
 * ============================================================
 * ⚠️ 触点的"归属"在**按下那一刻**决定，之后不再改
 * ============================================================
 * 这是抄 Axon 的做法（`classifyTouchRole` 只在触点首次出现时调用），
 * 而且手感上是对的:手指从区域里划出去之后，摇杆**不该突然失去输入**
 * （那会让"推着摇杆往外滑"变成"摇杆突然回中"）。
 */
object TouchInput {

    /**
     * 把归一化触点换算到**较短边归一化**坐标系。
     *
     * @param x 满屏归一化 X（`0..1`）
     * @param y 满屏归一化 Y（`0..1`）
     * @param width 屏幕宽（像素）
     * @param height 屏幕高（像素）
     */
    fun toShortEdge(x: Float, y: Float, width: Int, height: Int): Pair<Float, Float> {
        val short = minOf(width, height).coerceAtLeast(1)
        return (x * width / short) to (y * height / short)
    }

    /**
     * 这个触点落在某块区域里吗？
     *
     * ============================================================
     * ⚠️⚠️ 边界取自 [TouchRegionGeometry.bounds] —— **与编辑器画方块同一个函数**
     * ============================================================
     * 原来这里自己算了一遍（`center ± size/2 × 短边/屏宽`），而编辑器那边
     * 也算了一遍。两者当时恰好一致，但**没有任何东西保证以后一致** ——
     * 而"画出来的方块"与"真正生效的范围"不一致，用户是查不出来的
     * （他只会觉得"我明明按在方块上却没反应"）。
     *
     * 用户的原话:
     *
     * > 我们现在能**保证调节的触摸区域方框保存后一定跟实际触摸区域对应**吗
     *
     * ⚠️ 而且它们**已经**差了一处:编辑器给窗口加了"最小 32dp"的下限，
     * 判定这边没有 —— 区域很小时**画的比判定的大**。
     * 现在两处都走 [TouchRegionGeometry.bounds]，下限也一起生效。
     *
     * @param pointScreen 触点的**整屏归一化**坐标（0..1，不是短边归一化）
     * @param density 显示密度（[TouchRegionGeometry.MIN_SIDE_DP] 要用）
     */
    fun regionContains(
        region: TouchRegion,
        pointScreen: Pair<Float, Float>,
        width: Int,
        height: Int,
        density: Float = 0f,
    ): Boolean {
        val b = TouchRegionGeometry.bounds(
            centerX = region.centerX,
            centerY = region.centerY,
            size = region.size,
            screenWidth = width,
            screenHeight = height,
            density = density,
        )
        val w = width.coerceAtLeast(1).toFloat()
        val h = height.coerceAtLeast(1).toFloat()
        val (x, y) = pointScreen
        return x >= b.left / w && x <= b.right / w &&
            y >= b.top / h && y <= b.bottom / h
    }

    /**
     * 移动轮盘的向量（`-1..1`，**Y 向下为正**）。
     *
     * 算法:
     * ```
     * 偏移   = 触点 - 区域中心
     * 半边长 = 区域边长 / 2
     * 归一化 = 偏移 / 半边长            // 区域边缘 = 1.0
     * 死区   = |归一化| < deadZone ? 0 : 归一化
     * 输出   = 夹到单位圆内(死区 × sensitivity)
     * ```
     *
     * ⚠️ **这里全程用"较短边归一化"坐标**（触点在短边单位下的偏移，
     * 除以同样在短边单位下的半边长）—— 于是"区域是正方形"这件事
     * 在数学上成立，四个方向与斜向的手感一致。
     *
     * ⚠️ 若混用单位（比如中心用整屏、触点用短边），斜向会被拉偏:
     * 横屏下"往右推"和"往上推"同样的手指距离会得到不同的输出。
     *
     * ⚠️ **死区是圆形的**（比较向量长度），不是分别比 x/y ——
     * 分别比的话斜向会漏（`(0.09, 0.09)` 每个分量都没超，
     * 但实际已经推得挺远了）。
     *
     * ⚠️ 最后**夹到单位圆内**而不是分别夹 x/y:
     * 分别夹的话斜向能到 `(1, 1)`，模长 `√2 ≈ 1.41` ——
     * 于是"斜着推"比"正着推"**更远**，下游按模长用时就会超界
     * （摇杆帽画到框外）。夹圆之后四个方向和斜向的最大值一致。
     *
     * @param pointShortEdge 已按 [toShortEdge] 换算过的触点
     * @param centerShortEdge 区域中心，同样换算到较短边单位
     * @return 归一化向量；区域没被触摸时返回 `0f to 0f`
     */
    fun moveVector(
        region: TouchRegion,
        pointShortEdge: Pair<Float, Float>,
        centerShortEdge: Pair<Float, Float>,
        feel: TouchStickFeel,
    ): Pair<Float, Float> {
        val half = (region.size / 2f).coerceAtLeast(1e-4f)
        var dx = (pointShortEdge.first - centerShortEdge.first) / half
        var dy = (pointShortEdge.second - centerShortEdge.second) / half

        /* 圆形死区 */
        val magnitude = kotlin.math.hypot(dx, dy)
        val dead = feel.deadZone.coerceAtLeast(0f)
        if (magnitude <= dead) return 0f to 0f

        dx *= feel.sensitivity
        dy *= feel.sensitivity

        /* 夹到单位圆内（保持方向，只压模长） */
        val scaled = kotlin.math.hypot(dx, dy)
        if (scaled > 1f) {
            dx /= scaled
            dy /= scaled
        }
        return dx to dy
    }

    /**
     * 区域中心换算到**较短边归一化**坐标系（[moveVector] 要用）。
     *
     * ⚠️ 与 [toShortEdge] 是**同一个换算**，只是输入是中心而不是触点。
     * 抽出来是为了让"两者必须用同一种单位"这件事在一处说清 ——
     * 分开写的话改了一处、忘了另一处，表现就是"推轮盘的方向是斜的"。
     */
    fun centerToShortEdge(
        region: TouchRegion,
        width: Int,
        height: Int,
    ): Pair<Float, Float> = toShortEdge(region.centerX, region.centerY, width, height)

    /**
     * 视角摇杆的**增量**（触摸板语义）。
     *
     * ============================================================
     * ⚠️ 它算的是"这一帧相对上一帧动了多少"，不是"在哪"
     * ============================================================
     * 所以调用方必须把**上一帧**的触点位置一起传进来
     * （见 [TouchFrameTracker]）。
     *
     * ⚠️ 三个必须处理的边界情况（都是真实会发生的）:
     *
     * | 情况 | 处理 | 不处理的后果 |
     * |---|---|---|
     * | 这一帧新出现的指头 | **不产生增量** | 手指刚按下就被算成一大段位移，视角**猛地一甩** |
     * | 上一帧有、这一帧没了 | **不产生增量** | 同理（抬手被算成反向甩） |
     * | 手指从区域里划到区域外 | 按**按下时的归属**算，不重新归类 | 摇杆突然失去输入 / 视角突然被拽一下 |
     *
     * @param previous 上一帧的触点（**已换算**，按 id 索引）
     * @param current 这一帧的触点（已换算）
     * @param pointsInMoveRegion 这一帧里落在移动区域内的触点 id
     * @return 未缩放、未累加的原始增量（较短边归一化单位）
     */
    fun cameraDelta(
        previous: Map<Int, Pair<Float, Float>>,
        current: Map<Int, Pair<Float, Float>>,
        pointsInMoveRegion: Set<Int>,
    ): Pair<Float, Float> {
        var dx = 0f
        var dy = 0f
        current.forEach { (id, now) ->
            /* ⚠️ 落在移动轮盘区域里的触点**不参与**视角 —— 否则推轮盘会同时转视角 */
            if (id in pointsInMoveRegion) return@forEach
            /*
             * ⚠️ 上一帧没有这根手指 → 跳过（新按下，不产生增量）。
             * 见上面表格的第一行。
             */
            val before = previous[id] ?: return@forEach
            dx += now.first - before.first
            dy += now.second - before.second
        }
        return dx to dy
    }

    /**
     * 视角摇杆的**累加 + 夹取**（触摸板语义，松手不回中）。
     *
     * ⚠️ 累加到 ±1 就**夹住**（用户明确要求:"很快顶到边界就跟鼠标摇杆
     * 一样处理就行了"）。反向滑动能立刻拉回来 —— 夹的是值不是速度，
     * 所以不存在"顶住之后要先泄掉多余量"的问题。
     *
     * @param accumulated 上一帧的累计值
     * @param delta 这一帧的原始增量（较短边归一化单位）
     */
    fun accumulateCamera(
        accumulated: Pair<Float, Float>,
        delta: Pair<Float, Float>,
        feel: TouchStickFeel,
    ): Pair<Float, Float> {
        val s = feel.sensitivity.coerceAtLeast(0f)
        return (accumulated.first + delta.first * s).coerceIn(-1f, 1f) to
            (accumulated.second + delta.second * s).coerceIn(-1f, 1f)
    }
}

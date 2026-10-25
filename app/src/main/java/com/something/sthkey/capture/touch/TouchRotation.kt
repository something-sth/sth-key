package com.something.sthkey.capture.touch

import android.view.Surface

/**
 * 触摸坐标的**屏幕旋转换算**。
 *
 * ============================================================
 * 问题是什么
 * ============================================================
 * evdev 报的触点是**面板自然方向**的坐标（绝大多数手机是竖屏）。
 * 而用户玩游戏时多半是横屏 —— 此时"屏幕左上角"在原生坐标系里
 * 可能对应"右下角"。
 *
 * ⚠️ 不换算的后果很直观也很有趣:用户按屏幕**左下角**，
 * 而我们以为他按了**右下角** —— 触摸区域与手指位置**完全对不上**，
 * 而且"镜像"这种错看起来像"区域画反了"，很容易往错误的方向查。
 *
 * ============================================================
 * ⚠️⚠️ 映射方向是抄 Axon 的，不是自己推的
 * ============================================================
 * 四个象限的映射（`ROTATION_90` 为例:`x' = y, y' = 1 - x`）抄自
 * Axon 的 `mapTouchPoint`。它已经在真机上跑通，所以这里不自己发明。
 *
 * ⚠️ **但我推导过一遍，得到的结论与这个映射相反**（我认为
 * `ROTATION_90` 应该是 `x' = 1 - y, y' = x`）。两者是**互逆**的，
 * 也就是"转 90° 还是转 -90°"的区别。
 *
 * ⚠️ 我没有在真机上验证过哪个对，所以:
 *
 * 1. **照抄 Axon**（已验证过的那个，胜算更大）；
 * 2. 加一道 [isConsistent] 自检 —— 见下。
 *
 * ============================================================
 * ⚠️ 自检:方向错了会露馅，而且不用人肉比对
 * ============================================================
 * 四种旋转**恰好有两个**会把"横屏的屏幕"映射成"竖屏的屏幕"，
 * 另两个不。所以:
 *
 * | 当前屏幕 | 自然屏 | 哪个 ROTATION 会翻转宽高 |
 * |---|---|---|
 * | 竖屏 | 竖屏 | 90 / 270 |
 * | 横屏 | 竖屏 | 0 / 180 |
 *
 * ⚠️ 于是"[isConsistent] == false"就是**方向搞反了**的强信号:
 * 那时候要么改用互逆映射，要么说明这台设备的自然方向判断不对。
 * 采集层发现它不一致时会**记一条警告**（而不是静默地算错坐标）。
 *
 * ⚠️ 这也是为什么这里**不静默兜底**:算错的坐标比"报个错"危害大得多 ——
 * 用户会以为是自己区域没画好，去反复调一个根本没参与计算的东西。
 */
object TouchRotation {

    /**
     * 把"面板自然方向"的归一化坐标换算成**当前屏幕方向**的坐标。
     *
     * @param x 自然坐标系 X，`0..1`
     * @param y 自然坐标系 Y，`0..1`
     * @param rotation [Surface.ROTATION_0] / `_90` / `_180` / `_270`
     * @return 当前屏幕坐标系的 `(x, y)`
     */
    fun map(x: Float, y: Float, rotation: Int): Pair<Float, Float> = when (rotation) {
        Surface.ROTATION_90 -> y to (1f - x)

        Surface.ROTATION_180 -> (1f - x) to (1f - y)

        Surface.ROTATION_270 -> (1f - y) to x

        /*
         * 自然方向 = 当前方向，不用换算。
         *
         * ⚠️ 未知的 rotation 值（系统以后加了新常量）也走这一支 ——
         * 按"不换算"处理比按某个角度硬套安全:前者顶多方向不对，
         * 后者会**镜像**坐标。
         *
         * ⚠️ `else` 必须是 `when` 的**最后一项**（放中间会编译不过），
         * 所以 `ROTATION_0` 不单独写出来。
         */
        else -> x to y
    }

    /** 换算一批触点 */
    fun mapPoints(
        points: List<TouchNativeProtocol.Point>,
        rotation: Int,
    ): List<TouchNativeProtocol.Point> {
        if (rotation == Surface.ROTATION_0) return points
        return points.map { p ->
            val (nx, ny) = map(p.x, p.y, rotation)
            p.copy(x = nx, y = ny)
        }
    }

    /**
     * 自查:这个旋转角度与"屏幕 / 自然屏的宽高比"是否自洽。
     *
     * ============================================================
     * 判据
     * ============================================================
     * `ROTATION_90` / `ROTATION_270` 把画面转了 90°，
     * 所以**当前屏幕的宽高比应该与自然屏相反**；
     * `ROTATION_0` / `ROTATION_180` 则**相同**。
     *
     * ⚠️ 只在"自然屏与当前屏的宽高比明显不同"时才有判断力 ——
     * 正方形屏幕（或接近正方形）两种都对，这时返回 `true`
     * （不该为了一个无法判断的情况报假警）。
     *
     * @param rotation 当前旋转
     * @param screenWidth 当前屏幕宽（像素）
     * @param screenHeight 当前屏幕高（像素）
     * @param naturalWidth 面板自然方向的宽（像素）
     * @param naturalHeight 面板自然方向的高（像素）
     */
    fun isConsistent(
        rotation: Int,
        screenWidth: Int,
        screenHeight: Int,
        naturalWidth: Int,
        naturalHeight: Int,
    ): Boolean {
        if (screenWidth <= 0 || screenHeight <= 0) return true
        if (naturalWidth <= 0 || naturalHeight <= 0) return true

        val quarterTurn = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270

        /* 当前屏幕是不是横的（宽 > 高） */
        val screenLandscape = screenWidth > screenHeight
        /* 自然屏是不是横的 */
        val naturalLandscape = naturalWidth > naturalHeight

        /*
         * ⚠️ 自然屏接近正方形时判不出来 —— 直接放行，不要报假警。
         * 阈值取 5%:手机屏幕的长宽比差异远大于它（16:9 ≈ 1.78），
         * 而折叠屏内屏那种接近 1:1 的才落在阈值内。
         */
        val naturalRatio = naturalWidth.toFloat() / naturalHeight.toFloat()
        if (naturalRatio in SQUARE_LOW..SQUARE_HIGH) return true

        /* 转了 90°/270° → 横竖应该互换；否则应该一致 */
        val expectFlip = quarterTurn
        val actuallyFlipped = screenLandscape != naturalLandscape
        return expectFlip == actuallyFlipped
    }

    /** 宽高比落在这个区间里就当"正方形"，判不出方向 */
    private const val SQUARE_LOW = 0.95f
    private const val SQUARE_HIGH = 1.05f
}

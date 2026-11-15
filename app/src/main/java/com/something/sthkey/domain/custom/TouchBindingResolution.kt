package com.something.sthkey.domain.custom

/**
 * 方向判定的死区。
 *
 * ⚠️ 取一个**很小**的值:作用只是"手指刚按下去时的微小抖动不该让方向成立"，
 * 而不是"用户要推很远才算方向"。真正的手感死区在摇杆自己的
 * `touchFeel.deadZone` 里（它决定向量本身是不是 0）。
 */
private const val TOUCH_DIRECTION_DEAD_ZONE = 0.05f

/**
 * 这一帧**真正被触发的组件**（把绑定的方向也算进去）。
 *
 * ============================================================
 * ⚠️⚠️ 为什么不能只看"区域被按到"
 * ============================================================
 * 自定义 Key 的区域绑按键/文本组件时可以带方向:
 *
 * | 区域类型 | 绑定 | 什么时候算 |
 * |---|---|---|
 * | 按键区域 | 任何组件（不带方向） | 按到就亮 |
 * | 轮盘区域 | 按键/文本 + 方向 | 按到**且**摇杆朝向那个方向才亮 |
 * | 轮盘区域 | 摇杆组件（不带方向） | 由摇杆渲染那条路消费，不走这里 |
 *
 * ⚠️ 如果这里不做方向过滤，一块轮盘区域绑了"前 / 后 / 左 / 右"四个按键组件，
 * 手指一碰**四个一起亮** —— 用户报的"8 向方向没生效"就是这种形态。
 *
 * ⚠️ 触屏样式（`touch.regions`）**不走这里**:它的区域只认槽位、
 * 没有方向的概念，那条路在 `TouchFrameTracker` 里就已经处理完了。
 *
 * ============================================================
 * 为什么做成纯函数
 * ============================================================
 * 它只吃"区域列表 + 这帧按到了谁 + 每块区域的向量"，不碰 Compose、
 * 不碰 Android —— 于是 8 个方向的判定可以在 JVM 单测里逐条验证
 * （`TouchBindingResolutionTest`）。这正是最容易写错、又最难在真机上
 * 一条条试的地方。
 *
 * @param regions 配置里的全部触摸区域（多块可以重叠）
 * @param pressedRegionIds 这一帧被按到的区域 id
 * @param vectorOf 给定区域这一刻的摇杆向量（`-1..1`，Y 向下为正）；
 *   **查不到 = 这块区域没有向量**（不是轮盘、或没有手指在里面）
 */
fun touchPressedComponents(
    regions: List<CustomTouchRegion>,
    pressedRegionIds: Set<String>,
    vectorOf: (regionId: String) -> Pair<Float, Float>?,
): Set<String> {
    if (pressedRegionIds.isEmpty()) return emptySet()

    val result = mutableSetOf<String>()
    for (region in regions) {
        if (region.id !in pressedRegionIds) continue
        for (binding in region.bindings) {
            val direction = binding.direction
            /* ⚠️ 不带方向 = 整块区域，按到就算 */
            if (direction == null) {
                result += binding.componentId
                continue
            }
            val (x, y) = vectorOf(region.id) ?: continue
            /* ⚠️ 死区之内不算方向 —— 防"刚按下就四个方向全亮" */
            if (kotlin.math.hypot(x, y) <= TOUCH_DIRECTION_DEAD_ZONE) continue
            /*
             * ⚠️ 8 向判定**统一走** [TouchDirection.resolve]（45° 扇区）——
             * 与渲染、与触屏样式的方向判定同一个函数。
             * 自己"看符号"的话，斜推会同时命中两个方向。
             */
            if (TouchDirection.resolve(x, y) == direction) result += binding.componentId
        }
    }
    return result
}

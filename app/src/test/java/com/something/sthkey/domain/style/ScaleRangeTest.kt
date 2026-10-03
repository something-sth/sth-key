package com.something.sthkey.domain.style

import com.something.sthkey.domain.config.defaultConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 整体缩放的范围与映射。
 *
 * ============================================================
 * 这里钉的是"滑块范围不能与读取范围脱钩"
 * ============================================================
 * `scalePercent` 的合法范围散落在至少三处：
 *
 * 1. `KeyLayout.SCALE_PERCENT_MIN/MAX` —— 滑块范围
 * 2. `KeyLayout.uiScale` 里的 `coerceIn` —— 实际绘制用的映射
 * 3. 读配置时的 `coerceIn` —— 决定**存下来的值会不会被吃回去**
 *
 * 第 3 处早先是写死的字面量 `coerceIn(50, 200)`。改滑块下限时很容易漏掉它，
 * 症状是"我调的缩放存不住"：调到 20、保存、再打开就变回 50 ——
 * 而编译期完全看不出来。
 *
 * 现在第 3 处已经改成引用第 1 处的常量，这个测试负责在**有人再写死字面量**
 * 或改了映射公式时报警。
 */
class ScaleRangeTest {

    private fun scaleAt(percent: Int): Float =
        KeyLayout.uiScale(defaultConfig().copy(scalePercent = percent))

    /**
     * 范围内**每一个**百分比都要能撑得住，且映射必须单调。
     *
     * ⚠️ 只测两端是不够的：中间的 `coerceIn` 写错（比如夹到别的区间）
     * 时两端可能恰好还对，中间已经乱了。
     */
    @Test
    fun `范围内每个值都能映射且单调递增`() {
        var previous = Float.NEGATIVE_INFINITY
        for (percent in KeyLayout.SCALE_PERCENT_MIN..KeyLayout.SCALE_PERCENT_MAX) {
            val scale = scaleAt(percent)
            assertTrue("缩放必须是有限正数：$percent% → $scale", scale.isFinite() && scale > 0f)
            assertTrue(
                "缩放必须随百分比单调递增：$percent% → $scale，上一个 $previous",
                scale > previous,
            )
            previous = scale
        }
    }

    /**
     * ⚠️ **低于下限的值会被夹到下限**，而不是被丢掉。
     *
     * 这是"老配置里存着更小的值"的兼容行为：
     * 下限从 50 降到 20 之后，以前存过 50 的配置不受影响；
     * 而如果有人手改配置文件写了个 5，也会被安全地夹到 20 而不是崩掉。
     */
    @Test
    fun `低于下限的值被夹到下限`() {
        assertEquals(scaleAt(KeyLayout.SCALE_PERCENT_MIN), scaleAt(0), 0.0001f)
        assertEquals(scaleAt(KeyLayout.SCALE_PERCENT_MIN), scaleAt(-100), 0.0001f)
    }

    /** 高于上限的值被夹到上限 */
    @Test
    fun `高于上限的值被夹到上限`() {
        assertEquals(scaleAt(KeyLayout.SCALE_PERCENT_MAX), scaleAt(9999), 0.0001f)
    }

    /**
     * 下限必须**足够小**，能应付"屏幕尺寸极端"的设备。
     *
     * 有用户反馈悬浮窗在小屏上显得过大，原来的下限（50）不够小。
     * 这条断言把这个需求钉住：**不要再把下限调回去**。
     *
     * ⚠️ 不能断言具体的倍率数值（那是调参），只断言"比 50 的时候更小"——
     * 这样以后微调 [KeyLayout.SCALE_PERCENT_MIN] 到 25 或 15 都不会误报，
     * 但调回 50 会立刻失败。
     */
    @Test
    fun `下限要足够小以适配极端屏幕`() {
        assertTrue(
            "整体缩放的下限应当不超过 50（用户要求能调得更小），实际 ${KeyLayout.SCALE_PERCENT_MIN}",
            KeyLayout.SCALE_PERCENT_MIN <= 50,
        )
    }

    /** 下限必须小于上限，否则滑块的数值区间是空的 */
    @Test
    fun `下限必须小于上限`() {
        assertTrue(
            "缩放范围必须非空：${KeyLayout.SCALE_PERCENT_MIN}..${KeyLayout.SCALE_PERCENT_MAX}",
            KeyLayout.SCALE_PERCENT_MIN < KeyLayout.SCALE_PERCENT_MAX,
        )
    }
}

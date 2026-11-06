package com.something.sthkey.domain.custom

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * **整体字符缩放**与组件文字缩放如何合成最终字号。
 *
 * ============================================================
 * ⚠️ 这一层为什么要单测
 * ============================================================
 * 用户的原话:
 *
 * > 整体字符缩放与自定义 key 内单独组件的字符缩放共同决定最终字符缩放
 *
 * "共同决定"只有一种正确实现:**相乘**。写成下列任何一种都是错的，
 * 而它们**都能编译、都能跑、在真机上只是"字号不太对"** ——
 * 这种错最难靠肉眼发现，所以在这里钉死:
 *
 * | 错误写法 | 表现 |
 * |---|---|
 * | 只取整体，忽略组件 | 组件自己的滑块失效（用户报"那个滑块没效果"） |
 * | 只取组件，忽略整体 | 整体滑块失效 |
 * | 相加（100% + 120% = 220%） | 越调越大，且两个滑块互相影响 |
 * | 把整体折算进组件里 | 之后拖整体滑块改的是组件的值，两层边界消失 |
 */
class CustomLayoutTextScaleTest {

    /** 基础字号（`CustomLayout.textBaseSize`），用于对照 */
    private val base = CustomLayout.textBaseSize

    @Test
    fun `整体 100% 时结果等于组件自己的缩放`() {
        assertEquals(base * 1.2f, CustomLayout.textSize(120, 100), 0.001f)
    }

    @Test
    fun `组件 100% 时结果等于整体缩放`() {
        assertEquals(base * 1.5f, CustomLayout.textSize(100, 150), 0.001f)
    }

    @Test
    fun `两层相乘而不是相加`() {
        /*
         * ⚠️ 120% × 80% = 96%（相乘），不是 200%（相加）。
         * 这条是本文件的核心。
         */
        assertEquals(base * 0.96f, CustomLayout.textSize(80, 120), 0.001f)

        /* 反过来的组合也要对（乘法的交换性不该被写死成某种顺序） */
        assertEquals(
            CustomLayout.textSize(80, 120),
            CustomLayout.textSize(120, 80),
            0.001f,
        )
    }

    @Test
    fun `两个都 100% 时就是基础字号`() {
        assertEquals(base, CustomLayout.textSize(100, 100), 0.001f)
    }

    @Test
    fun `乘积不夹上限`() {
        /*
         * ⚠️ 300% × 300% = 900% 是用户自己拧出来的，要照做。
         * 夹乘积会造出"我把整体调大了，可那个组件不动"这种新困惑
         * （见 `CustomLayout.textSize(组件, 整体)` 的说明）。
         */
        assertEquals(base * 9f, CustomLayout.textSize(300, 300), 0.001f)
    }

    @Test
    fun `两个百分比各自夹在自己的范围里`() {
        /*
         * 手改过的 JSON 可能塞进 0 或 5000。
         * 两处都要夹到 50..300 —— 不夹的话字号会变成 0（字消失）或大到糊满屏幕。
         */
        /* 组件 0 → 夹成 50%；整体 50% → 两者相乘是 25%，不是 50% */
        assertEquals(base * 0.25f, CustomLayout.textSize(0, 50), 0.001f)
        assertEquals(
            CustomLayout.textSize(300, 300),
            CustomLayout.textSize(9999, 9999),
            0.001f,
        )
    }

    @Test
    fun `整体倍率换算`() {
        assertEquals(1f, CustomLayout.overallTextScale(100), 0.001f)
        assertEquals(1.5f, CustomLayout.overallTextScale(150), 0.001f)
        /* 越界值走夹取，不是原样返回 */
        assertEquals(0.5f, CustomLayout.overallTextScale(1), 0.001f)
        assertEquals(3f, CustomLayout.overallTextScale(4000), 0.001f)
    }
}

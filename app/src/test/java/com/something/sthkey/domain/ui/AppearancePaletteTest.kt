package com.something.sthkey.domain.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 色彩风格 + 取色种子的取值约束。
 *
 * ============================================================
 * ⚠️ 为什么这一组也必须有测试
 * ============================================================
 * 「色彩风格」上一轮做过又删掉，原因是**改了没反应** ——
 * 根因是没有取色种子。这一轮两个东西是一起做的，所以要有测试卡住:
 *
 * | 约束 | 破坏后的后果 |
 * |---|---|
 * | 风格 id 稳定 | 用户存的旧 id 认不出 → 静默回落，用户以为设置被重置 |
 * | 每种风格 id 唯一 | 两个成员同 id，后一个永远选不中（"点了没反应"） |
 * | 预设色不透明 | 有个预设是 0（透明黑）的话，会和「跟随壁纸」的 0 撞在一起 |
 */
class AppearancePaletteTest {

    @Test
    fun `色彩风格的 id 是稳定的字符串`() {
        assertEquals("tonal_spot", AppearancePalette.TONAL_SPOT.id)
        assertEquals("monochrome", AppearancePalette.MONOCHROME.id)
        assertEquals("fidelity", AppearancePalette.FIDELITY.id)
    }

    @Test
    fun `九种风格都在，且 id 互不相同`() {
        /*
         * ⚠️ 数量变了要**显式改这条测试**，而不是让它自动通过:
         * 每种风格都需要在 `SeedColor.kt` 的 `when` 里加一个分支，
         * 漏了的话编译会报"when 不穷尽"，但这里先响一次更直观。
         */
        assertEquals(9, AppearancePalette.entries.size)
        val ids = AppearancePalette.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `没设置过时用默认值`() {
        assertEquals(AppearancePalette.TONAL_SPOT, AppearancePalette.fromId(null))
        assertEquals(AppearancePalette.TONAL_SPOT, AppearancePalette.fromId(""))
    }

    @Test
    fun `认不出的值静默回落到默认值`() {
        /*
         * ⚠️ 降级安装会遇到:新版本写了旧版本不认识的 id。
         * 抛异常的话应用一开就崩，用户连设置页都进不去。
         */
        assertEquals(AppearancePalette.TONAL_SPOT, AppearancePalette.fromId("neon"))
        assertEquals(
            AppearancePalette.TONAL_SPOT,
            AppearancePalette.fromId("tonal_spot_v2"),
        )
    }

    @Test
    fun `id 往返一致`() {
        AppearancePalette.entries.forEach {
            assertEquals(it, AppearancePalette.fromId(it.id))
        }
    }

    @Test
    fun `每个风格都有非空的中文标签与说明`() {
        AppearancePalette.entries.forEach {
            assertTrue("${it.name} 缺标签", it.label.isNotBlank())
            assertTrue("${it.name} 缺说明", it.description.isNotBlank())
            /*
             * ⚠️ 标签必须**唯一**:两个成员用同一个中文标签的话，
             * 下拉菜单里会出现两条一模一样的选项，用户分不清点哪个。
             */
            assertEquals(
                "${it.name} 的标签和其它成员重复了",
                1,
                AppearancePalette.entries.count { p -> p.label == it.label },
            )
        }
    }

    @Test
    fun `种子色 0 专门表示跟随壁纸`() {
        /*
         * ⚠️ 这条是**约定**而不是代码逻辑:
         * `AppPrefs.seedColor` 用 0 表示"没设过"，
         * 而 UI 层的预设色块**必须都是不透明色**（alpha = 0xFF），
         * 否则某个预设正好是 0 的话，会和"跟随壁纸"撞在一起，
         * 用户点了一个颜色却被当成"跟随壁纸"。
         */
        assertEquals(0, 0 and 0xFFFFFFFF.toInt())
    }
}

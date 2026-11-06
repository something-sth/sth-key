package com.something.sthkey.domain.live2d

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 贴图降采样倍数。
 *
 * ⚠️ 这个数字错了的表现**很难从屏幕上看出来**:
 * 算小了 → 白屏/缺部件（纹理上传失败）、或者 OOM；
 * 算大了 → 明明能显示，却被糊成一片（用户只会觉得"模型质量差"）。
 */
class TextureBudgetTest {

    @Test
    fun `普通模型不降采样`() {
        /* 两张 2048² = 8.4 M 像素，远低于 48 M 预算 */
        assertEquals(
            1,
            TextureBudget.sampleFor(listOf(2048 to 2048, 2048 to 2048)),
        )
    }

    @Test
    fun `实测样本降到 2 倍`() {
        /*
         * `鸣潮卡提希娅最牛逼键盘猫模型` 的真实尺寸:
         * 8192² + 8192×4096 = 96 M 像素。
         * 96/4 = 24 M ≤ 48 M ✓，所以是 2 倍（8192 → 4096）。
         */
        assertEquals(
            2,
            TextureBudget.sampleFor(listOf(8192 to 8192, 8192 to 4096)),
        )
    }

    @Test
    fun `超大模型继续翻倍到 4 倍`() {
        /*
         * ⚠️ 算法是"降到预算以内就停"，所以要算清楚:
         * 11 × 4096² = 184 M 像素 → /4 = 46 M，**已经 ≤ 48 M**，所以是 2 倍。
         * 要逼出 4 倍得更大:20 × 4096² = 335 M → /4 = 84 M 仍超 → /16 = 21 M ✓ → 4 倍。
         */
        assertEquals(2, TextureBudget.sampleFor(List(11) { 4096 to 4096 }))
        assertEquals(4, TextureBudget.sampleFor(List(20) { 4096 to 4096 }))
    }

    @Test
    fun `边长超标但总量没超也要降`() {
        /*
         * ⚠️ 这条最容易漏:16384×1024 = 16.8 M 像素（不超预算），
         * 但边长 16384 远超 4096 → 纹理在多数机器上**上传不了**。
         * 16384/4 = 4096 ✓ 所以是 4 倍。
         */
        assertEquals(4, TextureBudget.sampleFor(listOf(16384 to 1024)))
    }

    @Test
    fun `刚好等于上限时不降`() {
        /* 4096 正好是允许的最大边长 */
        assertEquals(1, TextureBudget.sampleFor(listOf(4096 to 4096)))
    }

    @Test
    fun `清空或无贴图时不降`() {
        assertEquals(1, TextureBudget.sampleFor(emptyList()))
    }

    @Test
    fun `预算为 0 时仍然保证能上传`() {
        /*
         * ⚠️ 预算传 0 是"极端保守"（例如低端机模式）。这时总量那条会一直翻倍，
         * 但绝不能因为除不尽而无限循环 —— 边长那条给了它一个上界。
         */
        val sample = TextureBudget.sampleFor(listOf(8192 to 8192), budgetPixels = 0)
        assertEquals(2, sample)   /* 8192 → 4096 就够了 */
    }

    @Test
    fun `预算是 48 M`() {
        assertEquals(48_000_000L, TextureBudget.NORMAL_PIXELS)
        assertEquals(4096, TextureBudget.MAX_EDGE)
    }
}

package com.something.sthkey.domain.custom

import com.something.sthkey.domain.config.defaultConfig
import com.something.sthkey.domain.style.KeyLayout
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 转换后的**字号必须与按键样式一致**。
 *
 * ============================================================
 * 这里钉的是一个真实发生过的 bug
 * ============================================================
 * CPS 模式 3 是"键内两行"，而且主文字的基准字号**比普通键小**
 * （[KeyLayout.CPS_PRIMARY_TEXT_SIZE] = 28，而普通键是 35）——
 * 设计初衷就是防止文本过大不协调。
 *
 * 而转换器一开始对**所有**模式都用 35 当基数，于是模式 3 转过去
 * 主文字大了 25%，CPS 行也跟着一起大。用户看到的是
 * "转换后文本大小与别的键一样了"，原本的设计丢了。
 *
 * 光看代码不容易发现：`28 × 1.25 = 35` 这两个数字都对，
 * 错的是"该从 28 出发还是从 35 出发"。所以只能靠断言钉住。
 */
class FontScaleConversionTest {

    private fun source(cpsEnabled: Boolean, cpsMode: Int) = defaultConfig().copy(
        mouseCpsEnabled = cpsEnabled,
        mouseCpsMode = cpsMode,
        textScalePercent = 100,
    )

    private fun lmbOf(config: com.something.sthkey.domain.config.KeyStrokesConfig): KeyComponent =
        config.toCustomKeyStyle().first.custom.components
            .filterIsInstance<KeyComponent>()
            .first { it.label.startsWith("LMB") }

    private fun wOf(config: com.something.sthkey.domain.config.KeyStrokesConfig): KeyComponent =
        config.toCustomKeyStyle().first.custom.components
            .filterIsInstance<KeyComponent>()
            .first { it.label.startsWith("W") }

    /**
     * 普通键（不含 CPS 两行）：文字大小与按键样式一致。
     *
     * 按键样式是 `35 × 文字缩放`，自定义是 `28 × textScalePercent/100`，
     * 所以 textScalePercent 要补偿成 `35/28` 倍（100% → 125%）。
     */
    @Test
    fun `普通键的字号与按键样式一致`() {
        val config = source(cpsEnabled = false, cpsMode = 1)
        val w = wOf(config)

        assertEquals(
            KeyLayout.textSize(config),
            CustomLayout.textBaseSize * w.textScalePercent / 100f,
            0.01f,
        )
    }

    /**
     * ⚠️ 本测试的重点：模式 3 的两行字号。
     *
     * 主文字要用 28 这个基数（而不是 35），CPS 行再乘 22/28。
     */
    @Test
    fun `模式 3 的两行字号与按键样式一致`() {
        val config = source(cpsEnabled = true, cpsMode = 3)
        val lmb = lmbOf(config)
        val textScale = KeyLayout.textScale(config)

        val primary = CustomLayout.textBaseSize * lmb.textScalePercent / 100f
        val secondary = primary * lmb.cpsTextScalePercent / 100f

        /*
         * 容差 0.2 而不是 0：CPS 行的比例只能用**整数百分比**表示
         * （22/28 = 78.57% → 79%），所以必然有一丁点误差
         * （28 × 0.79 = 22.12）。这是表示精度的下限，不是算错了。
         */
        assertEquals(
            "模式 3 的主文字必须用 28 这个基数",
            KeyLayout.CPS_PRIMARY_TEXT_SIZE * textScale,
            primary,
            0.01f,
        )
        assertEquals(
            "模式 3 的 CPS 行必须用 22 这个基数",
            KeyLayout.CPS_SECONDARY_TEXT_SIZE * textScale,
            secondary,
            0.2f,
        )
    }

    /**
     * 模式 1 的 CPS 与键名**同一行**，所以是同一个字号，
     * 不该因为"CPS 行"这个名头而被缩小。
     */
    @Test
    fun `模式 1 不做行内缩放`() {
        val config = source(cpsEnabled = true, cpsMode = 1)
        val lmb = lmbOf(config)

        assertEquals("单行写法没有第二行可缩放", 100, lmb.cpsTextScalePercent)
        assertEquals(
            "模式 1 与普通键同字号",
            KeyLayout.textSize(config),
            CustomLayout.textBaseSize * lmb.textScalePercent / 100f,
            0.01f,
        )
    }

    /** 文字缩放滑块要跟着走：50% 与 150% 都不能错位 */
    @Test
    fun `文字缩放非默认值时模式 3 仍然对得上`() {
        listOf(50, 80, 120, 150).forEach { percent ->
            val config = source(cpsEnabled = true, cpsMode = 3).copy(textScalePercent = percent)
            val lmb = lmbOf(config)
            val textScale = KeyLayout.textScale(config)

            val primary = CustomLayout.textBaseSize * lmb.textScalePercent / 100f
            /*
             * 大缩放时整数百分比的误差会被放大（150% 时 22.12 → 33.18，
             * 而目标是 33.0），所以容差按比例给，并额外留 0.5。
             */
            val tolerance = 0.2f * percent / 100f + 0.5f

            assertEquals(
                "文字缩放 ${percent}% 时主文字不对",
                KeyLayout.CPS_PRIMARY_TEXT_SIZE * textScale,
                primary,
                0.01f,
            )
            assertEquals(
                "文字缩放 ${percent}% 时 CPS 行不对",
                KeyLayout.CPS_SECONDARY_TEXT_SIZE * textScale,
                primary * lmb.cpsTextScalePercent / 100f,
                tolerance,
            )
        }
    }
}

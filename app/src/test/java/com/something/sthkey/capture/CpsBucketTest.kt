package com.something.sthkey.capture

import com.something.sthkey.domain.config.KeyStrokesConfig
import com.something.sthkey.domain.custom.ComponentStyle
import com.something.sthkey.domain.custom.CustomLayout
import com.something.sthkey.domain.custom.TextComponent
import com.something.sthkey.domain.keys.KeyCodes
import com.something.sthkey.domain.style.StyleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 自定义 Key 的 **CPS 桶**（用户 2026-11-15 报的 bug）。
 *
 * ============================================================
 * ⚠️ 现象
 * ============================================================
 * 自定义 Key 里新建文本组件、内容 `(cps)`，在属性面板把它绑到手柄按键 →
 * 按手柄时 CPS **永远是 0**。
 *
 * ⚠️ 根因:自定义 Key 的 `keyMappings` 是键盘/鼠标默认值
 * （W/A/S/D/SPACE/SHIFT/LMB/RMB），**没有手柄键码**。
 * 记录和显示两侧都走 `KeyLayout.codeToSlotMap`，于是:
 *
 * | 侧 | 结果 |
 * |---|---|
 * | 记录（`recordCpsClick`） | 翻译不出来 → 不记 |
 * | 显示（`CustomLayout.cpsAt`） | 翻译不出来 → 查不到 → 0 |
 *
 * ⚠️ 修法:自定义 Key 里翻译不出来的码按键码本身记（`@code:<码>`），
 * 记录与显示共用 [CpsCounter.cpsBucketOf]。这组测试把两端都钉住。
 */
class CpsBucketTest {

    private fun customConfig() = KeyStrokesConfig(
        id = "custom",
        name = "自定义",
        styleId = StyleId.CUSTOM_KEY,
    )

    @Test
    fun `自定义 Key 里手柄键码按键码本身成桶`() {
        val config = customConfig()
        assertEquals(
            "@code:${KeyCodes.BTN_EAST}",
            CpsCounter.cpsBucketOf(config, KeyCodes.BTN_EAST),
        )
    }

    @Test
    fun `自定义 Key 里能翻成槽位的码仍走槽位`() {
        /*
         * ⚠️ W 在 `defaultKeyMappings()` 里 → 仍然记 "W"。
         * 这样"同一个物理键被多个组件绑定"时共享一份计数，不会分成两份。
         */
        assertEquals("W", CpsCounter.cpsBucketOf(customConfig(), 17))
    }

    @Test
    fun `键盘样式保持原样（不认识的码不记）`() {
        /*
         * ⚠️ 只有自定义 Key 需要这个兜底。键盘/手柄样式的键位是固定的，
         * 随便一个码都记一份只会让调试数据变脏。
         */
        val keyboard = KeyStrokesConfig(
            id = "kb",
            name = "键盘",
            styleId = StyleId.KEYSTROKES,
        )
        assertNull(CpsCounter.cpsBucketOf(keyboard, KeyCodes.BTN_EAST))
        assertEquals("W", CpsCounter.cpsBucketOf(keyboard, 17))
    }

    @Test
    fun `桶名不会与真实槽位撞车`() {
        assertEquals("@code:304", CpsCounter.codeBucket(304))
    }

    @Test
    fun `文本组件绑手柄键时 CPS 真的能读到计数`() {
        /*
         * ⚠️ 这是"记录下来"与"渲染查出来"的**接缝**:
         * 两边都必须用 `cpsBucketOf`，只改一边的话数值仍然是 0。
         */
        val config = customConfig()
        val component = TextComponent(
            id = "text_cps",
            x = 0f,
            y = 0f,
            width = 120f,
            height = 30f,
            style = ComponentStyle(),
            text = "(cps)",
            cpsKeyCodesPerPlaceholder = listOf(listOf(KeyCodes.BTN_EAST)),
        )

        val values = CustomLayout.cpsValuesOf(
            component = component,
            cpsBySlot = mapOf(CpsCounter.codeBucket(KeyCodes.BTN_EAST) to 3),
            slotIdOf = { code -> CpsCounter.cpsBucketOf(config, code) },
        )

        assertEquals("手柄键按了 3 次，CPS 必须读到 3", listOf(3), values)
    }

    @Test
    fun `摇杆八段式方向也能进 CPS 桶`() {
        /*
         * ⚠️ 摇杆方向没有真实键码，但有**内部保留码**；
         * 自定义 Key 的 `@code:` 兜底同样适用。
         */
        val config = customConfig()
        val code = KeyCodes.STICK_LEFT_UP
        assertEquals("@code:$code", CpsCounter.cpsBucketOf(config, code))

        val component = TextComponent(
            id = "text_stick_cps",
            x = 0f,
            y = 0f,
            width = 120f,
            height = 30f,
            style = ComponentStyle(),
            text = "(cps)",
            cpsKeyCodesPerPlaceholder = listOf(listOf(code)),
        )

        val values = CustomLayout.cpsValuesOf(
            component = component,
            cpsBySlot = mapOf(CpsCounter.codeBucket(code) to 2),
            slotIdOf = { c -> CpsCounter.cpsBucketOf(config, c) },
        )
        assertEquals("推左摇杆向上 2 次，CPS 必须读到 2", listOf(2), values)
    }
}

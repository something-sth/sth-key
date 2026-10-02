package com.something.sthkey.data.config

import com.something.sthkey.domain.config.ColorSet
import com.something.sthkey.domain.config.Opacity
import com.something.sthkey.domain.config.ShadowMode
import com.something.sthkey.domain.config.TextShadow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外观字段解析与**老数据迁移**的测试。
 *
 * ============================================================
 * 为什么这些用例最要紧
 * ============================================================
 * 这一版把外观字段扩了一轮：
 *
 * | 字段 | 旧 | 新 |
 * |---|---|---|
 * | 描边颜色 | `outline.color` 一个值 | `colors.outlineUp` / `outlineDown` |
 * | 透明度 | `key` / `text` / `outline` 三项 | 四个元素 × 两种状态 = 八项 |
 * | 文字阴影 | 没有 | 开关 + 形态 + 尺寸 + 两个颜色 + 两个透明度 |
 *
 * **迁移写错的后果是用户升级后外观被改掉** —— 他们只会觉得"我的配置坏了"，
 * 而我们很难从一句抱怨里反推出是哪个字段读错了。
 *
 * 所以这里把规则钉死：**升级前后观感必须一致**。
 *
 * ============================================================
 * 为什么不直接解析 JSON 文本
 * ============================================================
 * `org.json` 在本地单元测试里是**空壳**（方法静默返回 0 / null，
 * 见 ParamsTreeTest 的说明；离线环境也装不了真正的实现）。
 *
 * 但迁移规则住在 [AppearanceCodec] 里，它只依赖一个极小的 [AppearanceCodec.Reader]
 * 接口 —— 于是这里喂一个 Map 实现就够了。
 * **测的是生产代码本身，不是它的副本**，这一点是关键。
 */
class AppearanceMigrationTest {

    /**
     * 用 Map 实现的 Reader。
     *
     * ⚠️ 子对象**直接嵌套 [MapReader]**（而不是嵌套 Map）——
     * 第一版写的是 `map[key] as? Map<*,*>`，而调用方传进来的是
     * 另一个 MapReader，于是 `objectOrNull` 永远返回 null，
     * 四个用例一起失败。**那次失败是测试自己的 bug，不是生产代码的**，
     * 记在这里免得以后又照着写一遍。
     */
    private class MapReader(private val map: Map<String, Any?>) : AppearanceCodec.Reader {
        override fun objectOrNull(key: String): AppearanceCodec.Reader? =
            map[key] as? AppearanceCodec.Reader

        override fun has(key: String): Boolean = map.containsKey(key)

        override fun long(key: String, fallback: Long): Long =
            (map[key] as? Number)?.toLong() ?: fallback

        override fun int(key: String, fallback: Int): Int =
            (map[key] as? Number)?.toInt() ?: fallback

        override fun boolean(key: String, fallback: Boolean): Boolean =
            map[key] as? Boolean ?: fallback

        override fun float(key: String, fallback: Float): Float =
            (map[key] as? Number)?.toFloat() ?: fallback

        override fun string(key: String, fallback: String): String =
            map[key] as? String ?: fallback

        override fun isNull(key: String): Boolean =
            map.containsKey(key) && map[key] == null
    }

    private fun reader(vararg pairs: Pair<String, Any?>) =
        MapReader(linkedMapOf(*pairs))

    /*
     * ============================================================
     * 描边颜色的迁移
     * ============================================================
     */

    /**
     * 老配置设过描边色 → 那一个值**同时**填给按下与未按下。
     *
     * 这是需求明确指定的规则。旧版描边不区分状态，
     * 两个状态用同一个颜色才是"观感不变"。
     */
    @Test
    fun `老配置的描边色同时填给按下与未按下`() {
        val outline = reader("enabled" to true, "width" to 2.0, "color" to 0xFF0000L)

        val colors = AppearanceCodec.colors(
            json = reader("textUp" to 0xFFFFFFL, "textDown" to 0x000000L),
            legacyOutlineColor = AppearanceCodec.legacyOutlineColor(reader("outline" to outline)),
        )

        assertEquals("未按下描边色 = 老值", 0xFF0000, colors.outlineUp)
        assertEquals("按下描边色 = 同一个老值", 0xFF0000, colors.outlineDown)
    }

    /**
     * 老配置**没设过**描边色（旧版语义 = 跟随样式默认色 = 白色）。
     *
     * 这时回落到**文字色** —— 那正是旧版实际显示的颜色。
     * 本用例里没有自定义颜色，所以文字色就是默认值。
     */
    @Test
    fun `老配置没设描边色时跟随文字颜色`() {
        val outline = reader("enabled" to true, "color" to null)
        val defaults = ColorSet()

        val colors = AppearanceCodec.colors(
            json = reader(),
            legacyOutlineColor = AppearanceCodec.legacyOutlineColor(reader("outline" to outline)),
        )

        assertEquals("回落到未按下文字色", defaults.textUp, colors.outlineUp)
        assertEquals("回落到按下文字色", defaults.textDown, colors.outlineDown)
    }

    /**
     * 关键的一条：用户**改过文字颜色**、但没设过描边色时，
     * 描边必须跟着文字色走，而不是回落到默认白。
     *
     * 这条区分了"跟随文字色"与"固定默认色"两种实现 ——
     * 后者会在用户把文字改成别的颜色后，让描边仍然是白的。
     */
    @Test
    fun `改过文字色之后描边跟着改`() {
        val outline = reader("enabled" to true, "color" to null)

        val colors = AppearanceCodec.colors(
            json = reader("textUp" to 0xFFFF00L, "textDown" to 0xFF00FFL),
            legacyOutlineColor = AppearanceCodec.legacyOutlineColor(reader("outline" to outline)),
        )

        assertEquals("描边未按下跟着文字未按下", 0xFFFF00, colors.outlineUp)
        assertEquals("描边按下跟着文字按下", 0xFF00FF, colors.outlineDown)
    }

    @Test
    fun `老配置的描边色跟自定义文字色走`() {
        // 用户把文字改成黄色、没动过描边 → 描边应当跟随成黄色
        val colors = AppearanceCodec.colors(
            json = reader("textUp" to 0xFFFF00L, "textDown" to 0x000000L),
            legacyOutlineColor = AppearanceCodec.legacyOutlineColor(
                reader("outline" to reader("color" to null)),
            ),
        )

        assertEquals(0xFFFF00, colors.outlineUp)
    }

    @Test
    fun `老配置完全没有 outline 段时也不崩`() {
        val colors = AppearanceCodec.colors(
            json = reader("textUp" to 0xFFFFFFL, "textDown" to 0x000000L),
            legacyOutlineColor = AppearanceCodec.legacyOutlineColor(reader()),
        )

        assertEquals("回落到文字色", 0xFFFFFF, colors.outlineUp)
    }

    /**
     * ⚠️ 纯黑（0x000000）是完全合法的描边色。
     *
     * 如果迁移用"取到 0 就当没设过"来判断，黑色描边会被悄悄换成白色 ——
     * 很容易犯、又很难发现的错。
     */
    @Test
    fun `纯黑描边色不能被当成没设过`() {
        val legacy = AppearanceCodec.legacyOutlineColor(
            reader("outline" to reader("color" to 0x000000L)),
        )

        assertEquals("黑色要原样取出", 0x000000L, legacy)

        val colors = AppearanceCodec.colors(
            json = reader("textUp" to 0xFFFFFFL),
            legacyOutlineColor = legacy,
        )
        assertEquals(0x000000, colors.outlineUp)
    }

    /** 新配置走新字段，不经过迁移 */
    @Test
    fun `新配置的描边色分状态读取`() {
        val colors = AppearanceCodec.colors(
            json = reader("outlineUp" to 0xAAAAAAL, "outlineDown" to 0x555555L),
            legacyOutlineColor = 0xFF0000L, // 即使有老值也不该被采用
        )

        assertEquals(0xAAAAAA, colors.outlineUp)
        assertEquals(0x555555, colors.outlineDown)
    }

    /*
     * ============================================================
     * 透明度迁移
     * ============================================================
     */

    @Test
    fun `老配置的三项透明度填给两个状态`() {
        val opacity = AppearanceCodec.opacity(
            reader("key" to 55, "text" to 88, "outline" to 33),
        )

        assertEquals(55, opacity.keyUp)
        assertEquals("按下态沿用老值", 55, opacity.keyDown)
        assertEquals(88, opacity.textUp)
        assertEquals(88, opacity.textDown)
        assertEquals(33, opacity.outlineUp)
        assertEquals(33, opacity.outlineDown)
    }

    @Test
    fun `老配置的阴影透明度用默认值`() {
        val opacity = AppearanceCodec.opacity(reader("key" to 55))
        val defaults = Opacity()

        assertEquals(defaults.shadowUp, opacity.shadowUp)
        assertEquals(defaults.shadowDown, opacity.shadowDown)
    }

    @Test
    fun `新配置的八项互不干扰`() {
        val opacity = AppearanceCodec.opacity(
            reader(
                "keyUp" to 10, "keyDown" to 20, "textUp" to 30, "textDown" to 40,
                "outlineUp" to 50, "outlineDown" to 60, "shadowUp" to 70, "shadowDown" to 80,
            ),
        )

        assertEquals(10, opacity.keyUp)
        assertEquals(20, opacity.keyDown)
        assertEquals(30, opacity.textUp)
        assertEquals(40, opacity.textDown)
        assertEquals(50, opacity.outlineUp)
        assertEquals(60, opacity.outlineDown)
        assertEquals(70, opacity.shadowUp)
        assertEquals(80, opacity.shadowDown)
    }

    /**
     * 下限放宽到 0 之后，0 必须能存住。
     *
     * 早先下限是 20，`coerceIn` 会把 0 抬成 20 ——
     * "我明明拉到 0 了它却自己跳回去"这种问题最难查。
     */
    @Test
    fun `透明度 0 不会被抬高`() {
        val opacity = AppearanceCodec.opacity(reader("textUp" to 0, "outlineUp" to 0))

        assertEquals(0, opacity.textUp)
        assertEquals(0, opacity.outlineUp)
    }

    @Test
    fun `透明度超范围会被夹住`() {
        val opacity = AppearanceCodec.opacity(reader("textUp" to 999, "outlineUp" to -50))

        assertEquals(Opacity.MAX, opacity.textUp)
        assertEquals(Opacity.MIN, opacity.outlineUp)
    }

    /*
     * ============================================================
     * 阴影
     * ============================================================
     */

    @Test
    fun `阴影默认关闭且为柔光`() {
        val shadow = AppearanceCodec.shadow(reader())

        assertFalse(shadow.enabled)
        assertEquals(ShadowMode.SOFT, shadow.mode)
    }

    @Test
    fun `阴影的开关形态尺寸都能读出来`() {
        val shadow = AppearanceCodec.shadow(
            reader("enabled" to true, "mode" to "hard", "size" to 4.5),
        )

        assertTrue(shadow.enabled)
        assertEquals(ShadowMode.HARD, shadow.mode)
        assertEquals(4.5f, shadow.size, 0.001f)
    }

    /** 不认识的形态要安全回落（导入别人的包时会遇到） */
    @Test
    fun `未知阴影形态回落到柔光`() {
        assertEquals(
            ShadowMode.SOFT,
            AppearanceCodec.shadow(reader("mode" to "未来的新形态")).mode,
        )
        assertEquals(
            ShadowMode.SOFT,
            AppearanceCodec.shadow(reader("mode" to "")).mode,
        )
    }

    @Test
    fun `阴影尺寸超范围会被夹住`() {
        assertEquals(
            TextShadow.SIZE_MAX,
            AppearanceCodec.shadow(reader("size" to 999)).size,
            0.001f,
        )
        assertEquals(
            TextShadow.SIZE_MIN,
            AppearanceCodec.shadow(reader("size" to -5)).size,
            0.001f,
        )
    }

    /*
     * ============================================================
     * 迁移的"观感不变"总检验
     * ============================================================
     */

    /**
     * 这条是本文件的中心思想：**一份老配置读出来，外观应当与旧版一致**。
     *
     * 旧版的事实：
     * - 描边色没设过时显示白色 → 迁移后应当是未按下文字色（默认白）；
     * - 透明度三项 → 每个状态都用同一个值。
     */
    @Test
    fun `典型老配置读出来的外观与旧版一致`() {
        val root = reader(
            "colors" to reader(
                "keyUp" to 0x000000L,
                "keyDown" to 0xFFFFFFL,
                "textUp" to 0xFFFFFFL,
                "textDown" to 0x000000L,
            ),
            "outline" to reader("enabled" to true, "width" to 2.0, "color" to null),
            "opacity" to reader("key" to 70, "text" to 100, "outline" to 100),
        )

        val colors = AppearanceCodec.colors(
            root.objectOrNull("colors"),
            AppearanceCodec.legacyOutlineColor(root),
        )
        val opacity = AppearanceCodec.opacity(root.objectOrNull("opacity"))

        /*
         * 旧版描边没设过颜色 → 跟随文字色。
         * 于是未按下描边 = 文字的未按下色（白）、按下描边 = 文字的按下色（黑）。
         * 两者**各自跟随对应状态**，不是都等于白色。
         */
        assertEquals("未按下描边跟随未按下文字", 0xFFFFFF, colors.outlineUp)
        assertEquals("按下描边跟随按下文字", 0x000000, colors.outlineDown)
        // 旧版键帽 70% 不透明，两个状态都是 70%
        assertEquals(70, opacity.keyUp)
        assertEquals(70, opacity.keyDown)
        // 键帽底色原样
        assertEquals(ColorSet().keyUp, colors.keyUp)
        assertEquals(ColorSet().keyDown, colors.keyDown)
    }
}

package com.something.sthkey.data.config

import com.something.sthkey.domain.config.ColorSet
import com.something.sthkey.domain.config.Opacity
import com.something.sthkey.domain.config.ShadowMode
import com.something.sthkey.domain.config.TextShadow
import com.something.sthkey.domain.config.migratedOutlineColors

/**
 * 外观字段的解析与**老数据迁移**（纯 Kotlin，不碰 `org.json`）。
 *
 * ============================================================
 * 为什么要从 JsonConfigCodec 里把这块单独抽出来
 * ============================================================
 * 这一版把外观字段扩了一轮，于是解码时必须处理三种老数据：
 *
 * | 字段 | 旧 | 新 |
 * |---|---|---|
 * | 描边颜色 | `outline.color` 一个值 | `colors.outlineUp` / `outlineDown` |
 * | 透明度 | `key` / `text` / `outline` 三项 | 四个元素 × 两种状态 = 八项 |
 * | 文字阴影 | 没有 | 开关 + 形态 + 尺寸 + 两个颜色 + 两个透明度 |
 *
 * 而**迁移写错的后果是用户升级后外观被改掉** —— 他们只会觉得"我的配置坏了"，
 * 我们很难从一句抱怨里反推出是哪个字段读错了。所以这段逻辑必须有测试。
 *
 * ⚠️ 但 `JsonConfigCodec` 用的是 `org.json`，而它在**本地单元测试里是空壳**
 * （方法静默返回 0 / null，见 ParamsTreeTest 的说明；项目为此专门做过
 * 一个纯 Kotlin 的 `JsonNode`）。离线环境又装不了真正的实现。
 *
 * 所以这里把规则抽成**只依赖一个小读取接口**的形式：
 * - 生产路径：`JsonConfigCodec` 用 `JSONObject` 适配出 [Reader] 调它；
 * - 测试路径：直接喂一个 Map 实现的 [Reader]。
 *
 * 两条路走的是**同一份代码**，不是测试副本 —— 这一点很关键，
 * 否则测的只是"我以为的规则"。
 */
internal object AppearanceCodec {

    /**
     * 只读的 JSON 读取接口。
     *
     * 刻意做得极小：解码这段逻辑只需要"按名字取对象 / 取整数 / 取字符串"。
     * 接口越小，适配 `JSONObject` 与测试用的 Map 就越不容易出错。
     */
    interface Reader {
        /** 取子对象；不存在或不是对象时返回 null */
        fun objectOrNull(key: String): Reader?

        /** 字段是否存在（**区分"缺失"与"值为 0"**，颜色与透明度都依赖这一点） */
        fun has(key: String): Boolean

        /** 读取长整数（颜色用）；缺失时返回 [fallback] */
        fun long(key: String, fallback: Long): Long

        /** 读取整数；缺失时返回 [fallback] */
        fun int(key: String, fallback: Int): Int

        /** 读取布尔；缺失时返回 [fallback] */
        fun boolean(key: String, fallback: Boolean): Boolean

        /** 读取浮点；缺失时返回 [fallback] */
        fun float(key: String, fallback: Float): Float

        /** 读取字符串；缺失时返回 [fallback] */
        fun string(key: String, fallback: String): String

        /** 该字段是否为显式的 null（老配置用它表示"跟随样式默认色"） */
        fun isNull(key: String): Boolean
    }

    /*
     * ============================================================
     * 颜色
     * ============================================================
     */

    /**
     * 解析颜色，并完成描边色的迁移。
     *
     * @param legacyOutlineColor 老配置里 `outline.color` 的值；
     *   null 表示"没设过"（旧版语义是跟随样式默认色 = 白色）
     */
    fun colors(json: Reader?, legacyOutlineColor: Long?): ColorSet {
        val defaults = ColorSet()
        if (json == null) {
            val (up, down) = migratedOutlineColors(legacyOutlineColor, defaults)
            return defaults.copy(outlineUp = up, outlineDown = down)
        }

        val keyUp = json.long("keyUp", defaults.keyUp).and(0xFFFFFF)
        val keyDown = json.long("keyDown", defaults.keyDown).and(0xFFFFFF)
        val textUp = json.long("textUp", defaults.textUp).and(0xFFFFFF)
        val textDown = json.long("textDown", defaults.textDown).and(0xFFFFFF)

        /*
         * 描边色：新字段优先，都缺失时才走迁移。
         *
         * `has()` 而不是"取到 0 就当没设"：颜色 0x000000（纯黑）完全合法，
         * 用值判断会把黑色描边悄悄换成默认色 —— 很难发现的错。
         */
        val hasNewOutline = json.has("outlineUp") || json.has("outlineDown")
        val (outlineUp, outlineDown) = if (hasNewOutline) {
            json.long("outlineUp", textUp).and(0xFFFFFF) to
                json.long("outlineDown", textDown).and(0xFFFFFF)
        } else {
            /*
             * ⚠️ 这里**必须用刚解析出来的 textUp / textDown**，
             * 不能用 ColorSet() 的默认值。
             *
             * 踩过：第一版传的是 `ColorSet(...)` 的默认实例，
             * 于是"用户把文字改成黄色、描边没设过"时，描边被填成
             * **默认白色**而不是跟随黄色 —— 迁移把外观改掉了，
             * 正是迁移最不该做的事。测试抓到了这一条。
             */
            migratedOutlineColors(
                legacyOutlineColor,
                ColorSet(textUp = textUp, textDown = textDown),
            )
        }

        return ColorSet(
            keyUp = keyUp,
            keyDown = keyDown,
            textUp = textUp,
            textDown = textDown,
            outlineUp = outlineUp,
            outlineDown = outlineDown,
            // 阴影是这一版新增的，老配置没有；默认跟随文字色
            shadowUp = json.long("shadowUp", textUp).and(0xFFFFFF),
            shadowDown = json.long("shadowDown", textDown).and(0xFFFFFF),
        )
    }

    /** 从老配置的 `outline` 里取出那个唯一的颜色；没设过则 null */
    fun legacyOutlineColor(root: Reader?): Long? {
        val outline = root?.objectOrNull("outline") ?: return null
        // 老版本用显式 null 表示"跟随样式默认色"，那不是颜色
        if (outline.isNull("color")) return null
        return if (outline.has("color")) outline.long("color", 0xFFFFFF).and(0xFFFFFF) else null
    }

    /*
     * ============================================================
     * 透明度
     * ============================================================
     */

    /**
     * 解析透明度并迁移老字段名。
     *
     * 老版本三项（`key` / `text` / `outline`，不分按下）→
     * 新版本八项。迁移规则：**老值填给两个状态**，
     * 保证升级后观感不变（与描边色同一条思路）。
     */
    fun opacity(json: Reader?): Opacity {
        val defaults = Opacity()
        if (json == null) return defaults

        fun read(newKey: String, legacyKey: String?, fallback: Int): Int {
            val raw = when {
                json.has(newKey) -> json.int(newKey, fallback)
                legacyKey != null && json.has(legacyKey) -> json.int(legacyKey, fallback)
                else -> fallback
            }
            return raw.coerceIn(Opacity.MIN, Opacity.MAX)
        }

        val upKey = read("keyUp", "key", defaults.keyUp)
        val upText = read("textUp", "text", defaults.textUp)
        val upOutline = read("outlineUp", "outline", defaults.outlineUp)

        return Opacity(
            keyUp = upKey,
            // 老配置没有按下态：沿用同一个值
            keyDown = read("keyDown", "key", upKey),
            textUp = upText,
            textDown = read("textDown", "text", upText),
            outlineUp = upOutline,
            outlineDown = read("outlineDown", "outline", upOutline),
            // 阴影是新增的，老配置一律用默认值
            shadowUp = read("shadowUp", null, defaults.shadowUp),
            shadowDown = read("shadowDown", null, defaults.shadowDown),
        )
    }

    /*
     * ============================================================
     * 阴影
     * ============================================================
     */

    fun shadow(json: Reader?): TextShadow {
        val defaults = TextShadow()
        if (json == null) return defaults
        return TextShadow(
            enabled = json.boolean("enabled", defaults.enabled),
            // 不认识的形态安全回落（导入别人的包时会遇到）
            mode = ShadowMode.fromId(json.string("mode", defaults.mode.id).takeIf { it.isNotBlank() }),
            size = json.float("size", defaults.size)
                .coerceIn(TextShadow.SIZE_MIN, TextShadow.SIZE_MAX),
        )
    }
}

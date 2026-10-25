package com.something.sthkey.data.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置字段的**样式门控**:哪些字段只该写给哪些样式。
 *
 * ============================================================
 * ⚠️⚠️ 这是用户报的一个真问题
 * ============================================================
 * 用户的原话:
 *
 * > 现在这个自定义 key 的配置字段里为什么会有 aButtonOnTop，showSpaceKey，
 * > spaceKeyOnTop，showAButton 这些字段，这些字段不应该出现在自定义 key 里 …
 * >
 * > 也不止自定义 key 字段有问题，别的也是，例如键盘样式配置字段里居然出现了
 * > showAButton 和 aButtonOnTop，这两个应该是独属于手柄样式的字段 …
 * > 不要让配置字段里出现这个配置中多余的字段
 *
 * ============================================================
 * ⚠️ 为什么这些断言要读源码，而不是真的编码一遍
 * ============================================================
 * `org.json` 在本地单测里是**空壳**（一调就 `Method not mocked`），
 * [JsonConfigCodec.encode] 走的就是它 —— 编码结果在单测里拿不到。
 *
 * ⚠️ 项目里已有同一个问题的先例:见 `BitmapFontIdTest` 里
 * "用 org.json 之外的办法拿字段清单：直接读源码"。这里照那个做法。
 *
 * ============================================================
 * ⚠️⚠️ 我在这份测试里连错四次，全是**测试自己**的错
 * ============================================================
 * 记下来是因为这几种错都**看起来像生产代码有问题**，最难查:
 *
 * | 错法 | 症状 |
 * |---|---|
 * | 正则 `"([A-Za-z]+)"` | 匹配不了带数字的键名（`live2d`），那一项被静默跳过 |
 * | 正则 `setOf\(([^)]*)\)` | **多套了一层括号**，要求出现两个 `(`，被跳过的项更多 |
 * | 判"表结束"用 `substringBefore("\n    )")` | 被**注释里**的文本提前截断 |
 * | 判块体用 `\{([^}]*)\}` | 遇到**嵌套大括号**（`live2d` 那段）提前结束 |
 *
 * ⚠️ 共同点:**解析器太"聪明"**。所以现在改成"纯字符串切分 + 大括号配对"，
 * 笨但一眼能看懂、不可能有转义歧义。
 */
class StyleSpecificFieldsTest {

    /** 读 `JsonConfigCodec.kt` 的源码文本 */
    private fun codecSource(): String {
        val candidates = listOf(
            "src/main/java/com/something/sthkey/data/config/JsonConfigCodec.kt",
            "app/src/main/java/com/something/sthkey/data/config/JsonConfigCodec.kt",
        )
        candidates.forEach { path ->
            val file = java.io.File(path)
            if (file.exists()) return file.readText()
        }
        return ""
    }

    /** 键名的合法字符:字母 / 数字 / 下划线（`live2d`、`customOpacityPercent` 都在此列） */
    private val isKeyChar: (Char) -> Boolean = { c ->
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'
    }

    /** 从一段文本里抽出所有 `StyleId.XXX` */
    private fun stylesIn(text: String): Set<String> =
        Regex("""StyleId\.([A-Z0-9_]+)""").findAll(text).map { it.groupValues[1] }.toSet()

    /**
     * 解析源码里的**命名样式集合**（例如
     * `private val KEY_GRID_STYLES = setOf(StyleId.KEYSTROKES, StyleId.GAMEPAD2)`）。
     *
     * ⚠️ 表里成批的字段用的是这种命名集合（几十行都写 `setOf(...)` 太啰嗦），
     * 所以解析器必须跟着认得它 —— 否则那些项会被解析成空集合，
     * 测试报"表的内容变了"，又一次**指向生产代码**而错在测试。
     */
    private fun parsedNamedStyleSets(): Map<String, Set<String>> {
        val source = codecSource()
        return Regex("""val ([A-Z_]+) = setOf\(([^)]*)\)""")
            .findAll(source)
            .associate { it.groupValues[1] to stylesIn(it.groupValues[2]) }
    }

    /**
     * 解析门控表 `STYLE_SPECIFIC_KEYS`，返回 `键 -> 样式集合`。
     *
     * ⚠️ 每项的格式固定是 `"键名" to <样式集合>,`，独立成行 ——
     * 所以纯字符串切分就够，不需要正则。
     * `<样式集合>` 可以是 `setOf(StyleId.X, ...)`，也可以是命名集合。
     */
    private fun parsedGateTable(): Map<String, Set<String>> {
        val source = codecSource()
        assertTrue(
            "读不到 JsonConfigCodec.kt 的源码 —— 路径变了？" +
                "这个测试靠读源码工作（org.json 在单测里是空壳）",
            source.isNotEmpty(),
        )

        val marker = "STYLE_SPECIFIC_KEYS: Map<String, Set<String>> = mapOf("
        val start = source.indexOf(marker)
        assertTrue("没找到 STYLE_SPECIFIC_KEYS 表 —— 它被改名或被删了？", start >= 0)

        /* ⚠️ 表体到"行首恰好一个右括号"为止（`mapOf(` 的收尾） */
        val body = source.substring(start + marker.length)
            .lines()
            .takeWhile { it.trim() != ")" }

        val named = parsedNamedStyleSets()
        val result = mutableMapOf<String, Set<String>>()
        body.forEach { raw ->
            val line = raw.trim()
            if (!line.startsWith("\"") || !line.endsWith(",")) return@forEach
            val closeQuote = line.indexOf('"', 1)
            if (closeQuote <= 1) return@forEach
            val key = line.substring(1, closeQuote)
            if (!key.all(isKeyChar)) return@forEach

            val value = line.substringAfter(" to ", "").removeSuffix(",").trim()
            if (value.isEmpty()) return@forEach
            /*
             * ⚠️ 两种写法:`setOf(StyleId.X, ...)` 或命名集合（`KEY_GRID_STYLES`）。
             * 认不出来就给空集合 —— 上面的断言会因此报红，不会静默通过。
             */
            result[key] = when {
                value.startsWith("setOf(") -> stylesIn(value)
                else -> named[value] ?: emptySet()
            }
        }
        return result
    }

    /**
     * 取出源码里每个 `if (appliesTo(config.styleId, "...")) { ... }` 的
     * `门控的键 -> 块体文本`。
     *
     * ⚠️ 块体按**大括号配对**切，不能用 `\{([^}]*)\}` ——
     * `live2d` 那段是 `put("live2d", JSONObject().apply { ... })`，
     * 用 `[^}]*` 会在**第一个内层 `}`** 处提前结束，
     * 于是那个键被判成"没被门控包住"，测试报红并**指向生产代码**，
     * 而生产代码其实是对的。
     */
    private fun gateBlocks(): List<Pair<String, String>> {
        val source = codecSource()
        val headRegex = Regex("""if \(appliesTo\(config\.styleId, "([A-Za-z0-9_]+)"\)\) \{""")
        return headRegex.findAll(source).map { m ->
            val open = m.range.last
            var depth = 0
            var i = open
            while (i < source.length) {
                when (source[i]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) break
                    }
                }
                i++
            }
            m.groupValues[1] to source.substring(open + 1, i)
        }.toList()
    }

    /**
     * ⚠️⚠️ **门控表的内容必须与"布局真的读它"一致**。
     *
     * 这一条防的是"以后又有人把某个字段无条件写出去"。
     * 表里每一项的理由都写在源码注释里（读它的位置），改之前先看那里。
     *
     * ============================================================
     * ⚠️ 那批 `KEY_GRID`（键盘+手柄）字段的判据
     * ============================================================
     * 它们**只写给有键位网格的两个样式** —— 自定义 Key 与 Live2D
     * 各有自己的一套（每个组件 / 每个模型各自的样式），
     * 所以那些字段在这两个样式里**一个都不读**。
     *
     * ⚠️ 这不是新判断:`KeyStrokesConfig` 里 `custom` 字段的注释
     * **早就写好了这个规格** —— "上面那整套字段……一个都不适用；
     * 唯一仍然共用的是 `scalePercent`"。只是编码器一直没照做。
     */
    @Test
    fun `门控表的内容正确`() {
        /*
         * ⚠️ `both` 现在是**三个样式**（键盘 / 手柄 / **触屏**）。
         *
         * ⚠️ 触屏原来被漏在外面，于是它的**外观 / CPS / 字体 / 按键高度**
         * 等等**全都没被编码** —— 用户报的"触屏配置有些东西没做配置持久化"
         * 就是这个（每次重启都回默认值）。
         *
         * ⚠️ 根因是触屏**本来就是"键盘布局 + 摇杆"**（读的就是这一批字段）。
         */
        val both = setOf("KEYSTROKES", "GAMEPAD2", "TOUCHING")

        assertEquals(
            "门控表的内容变了。改动前请先确认布局代码真的（不）读它 —— " +
                "每一项的理由都写在源码注释里",
            mapOf(
                /* 键盘与触屏共用（触屏要与键盘「一样」） */
                "showSpaceKey" to setOf("KEYSTROKES", "TOUCHING"),
                "spaceKeyOnTop" to setOf("KEYSTROKES", "TOUCHING"),
                /* 只属于手柄（标准）样式 —— 全在 KeyLayout.joystickLayoutBoxes 里 */
                "showShoulderButtons" to setOf("GAMEPAD2"),
                "showAButton" to setOf("GAMEPAD2"),
                "aButtonOnTop" to setOf("GAMEPAD2"),
                /*
                 * ⚠️ **摇杆互换：手柄与触屏共用** —— 用户明确要求触屏保留它。
                 * ⚠️ 判据是"有没有两个摇杆"，而那正是 `usesJoystickLayout`。
                 */
                "swapSticks" to setOf("GAMEPAD2", "TOUCHING"),
                /* 只属于 Live2D 样式 */
                "live2d" to setOf("KEYBOARD_CAT"),
                /* 只属于触屏样式 */
                "touch" to setOf("TOUCHING"),
                /* 只属于自定义 Key 样式 */
                "custom" to setOf("CUSTOM_KEY"),
                "customOpacityPercent" to setOf("CUSTOM_KEY"),
                /* 有键位网格才读的一批：布局 / 文字 / 外观 */
                "textScalePercent" to both,
                "cornerRadiusEnabled" to both,
                "cornerRadiusPercent" to both,
                "animationMode" to both,
                "animationDurationSec" to both,
                "showShiftKey" to both,
                "showMouseButtons" to both,
                "keyHeightPercent" to both,
                "keyGapPercent" to both,
                "mouseCpsEnabled" to both,
                "mouseCpsMode" to both,
                "cpsTextTemplate" to both,
                "cpsTextTemplateMode1" to both,
                "bitmapFontId" to both,
                "textOffsetX" to both,
                "textOffsetY" to both,
                "slotTextOffsets" to both,
                "textSpacing" to both,
                "slotTextSpacings" to both,
                "outline" to both,
                "shadow" to both,
                "opacity" to both,
            ),
            parsedGateTable(),
        )
    }

    /**
     * ⚠️ **表里登记的每个键，编码时都必须真的被门控**。
     *
     * 光登记不判断 = 表是摆设，字段照样被无条件写进每一份配置 ——
     * 那正是用户报的问题本身。
     *
     * ⚠️ 判据是"这个键出现在某个 `appliesTo` 的 if 块里"。
     * **不能**要求"每个键都有自己的那一行 `appliesTo`" ——
     * 成组的字段共用一个条件是合理的（`showAButton` 与 `aButtonOnTop`
     * 就是一组:A 都不显示就无所谓置不置顶）。
     */
    @Test
    fun `表里每个键在编码时都被门控`() {
        val blocks = gateBlocks()
        parsedGateTable().keys.forEach { key ->
            val guarded = blocks.any { (_, body) -> body.contains(""""$key"""") }
            assertTrue(
                "`$key` 登记在门控表里，但编码时没有被任何 `appliesTo` 块包住 —— " +
                    "只登记不判断的话，它还是会被写进每一份配置",
                guarded,
            )
        }
    }

    /**
     * ⚠️ 反向:编码里的门控**不能用在没登记的键上**。
     *
     * 加了新门控就要同步登记 —— 否则没人知道它为什么只写给某些样式。
     */
    @Test
    fun `编码里的门控都已在表里登记`() {
        val table = parsedGateTable()
        gateBlocks().forEach { (key, _) ->
            assertTrue(
                "编码里门控了 `$key`，但它不在门控表里 —— 加门控时请同步登记，" +
                    "并在注释里写清「哪个样式的哪段代码读它」",
                key in table.keys,
            )
        }
    }

    /**
     * ⚠️ 表里的每个样式都必须是**真实活跃的样式**。
     *
     * ============================================================
     * ⚠️ 活跃样式有**五个**（不是三个、也不是四个）
     * ============================================================
     * | id | 是什么 |
     * |---|---|
     * | `keystrokes` | 键盘 |
     * | `gamepad2` | 手柄 |
     * | `keyboard_cat` | **Live2D**（名字看不出来，但注册表里 `enabled = true`） |
     * | `touching` | **触屏** |
     * | `custom_key` | 自定义 Key |
     *
     * ⚠️ 而 `gamepad1` **已下线**（注册表里被注释掉）。
     * 写错或写成一个已下线的样式**不会编译失败** ——
     * 但那个字段会**永远不被写给任何样式**（用户改了设置却存不住，
     * 比"多写"更隐蔽），所以这条要钉住。
     *
     * ⚠️ 每加一个样式这条都会红 —— **那是它该做的事**:
     * 逼着作者想清楚"新样式的专属字段登记了没有"。
     */
    @Test
    fun `门控表只许出现活跃样式`() {
        val styles = parsedGateTable().values.flatten().toSet()

        assertTrue(
            "门控表里出现了 GAMEPAD1 —— 那个样式已下线（注册表里被注释掉了），" +
                "写给它等于永远不写。手柄用的是 GAMEPAD2",
            "GAMEPAD1" !in styles,
        )
        assertEquals(
            "门控表只该出现这五种活跃样式" +
                "（keystrokes / gamepad2 / keyboard_cat / touching / custom_key）",
            setOf("KEYSTROKES", "GAMEPAD2", "KEYBOARD_CAT", "TOUCHING", "CUSTOM_KEY"),
            styles,
        )
    }
}

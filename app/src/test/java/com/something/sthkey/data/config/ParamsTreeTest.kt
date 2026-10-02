package com.something.sthkey.data.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * params.json 深度遍历的测试。
 *
 * ============================================================
 * 为什么这一层必须有测试
 * ============================================================
 * 它修的是一个**静默**的 bug：早先导出时只改顶层 `fontId`，
 * 自定义 Key 每个组件自己的 `style.fontId` 原样带着 `imported:<本机 uuid>`
 * 出了包 —— 导入方没有那个 id，字体悄悄回落成默认，
 * **不报错、不提示**，用户只会觉得"字体好像变了"。
 *
 * ============================================================
 * 为什么直接构造 JsonNode 而不是 JSONObject
 * ============================================================
 * `org.json` 在本地单元测试里是空壳（一调就抛 `Method not mocked`）。
 * 这正是当初把算法从 JSONObject 上挪到 [JsonNode] 上的原因 ——
 * 于是这里能直接测算法本身，不必绕过它。
 */
class ParamsTreeTest {

    private fun str(value: String) = JsonNode.Str(value)

    private fun obj(vararg pairs: Pair<String, JsonNode>) =
        JsonNode.Obj(LinkedHashMap<String, JsonNode>().apply { pairs.forEach { put(it.first, it.second) } })

    private fun arr(vararg items: JsonNode) =
        JsonNode.Arr(items.toMutableList())

    /**
     * 造一份最小但形状真实的 params：
     * 顶层字体 + 自定义组件里各自的字体。
     */
    private fun params(
        topFont: String = "system:sans-serif",
        componentFonts: List<String> = listOf("imported:aaa"),
    ) = obj(
        "id" to str("cfg"),
        "name" to str("测试"),
        "fontId" to str(topFont),
        "custom" to obj(
            "components" to arr(
                *componentFonts.mapIndexed { index, fontId ->
                    obj(
                        "type" to str("key"),
                        "id" to str("key_$index"),
                        "style" to obj(
                            "fillUp" to JsonNode.Other(0),
                            "fontId" to str(fontId),
                        ),
                    )
                }.toTypedArray(),
            ),
        ),
    )

    /*
     * ============================================================
     * 收集
     * ============================================================
     */

    @Test
    fun `顶层与每个组件的字体都被收集到`() {
        val ids = ParamsTree.collectFontIds(
            params(
                topFont = "system:sans-serif",
                componentFonts = listOf("imported:aaa", "imported:bbb"),
            ),
        )

        assertEquals(listOf("system:sans-serif", "imported:aaa", "imported:bbb"), ids)
    }

    @Test
    fun `同一个字体被多处引用时只收集一次`() {
        val ids = ParamsTree.collectFontIds(
            params(
                topFont = "imported:same",
                componentFonts = listOf("imported:same", "imported:same"),
            ),
        )

        assertEquals(listOf("imported:same"), ids)
    }

    @Test
    fun `没有自定义段时也能工作`() {
        assertEquals(
            listOf("imported:only"),
            ParamsTree.collectFontIds(obj("fontId" to str("imported:only"))),
        )
    }

    @Test
    fun `空白字体值不算数`() {
        // 空串不是合法字体 id；收集它会让导出侧去查一个不存在的字体
        assertTrue(ParamsTree.collectFontIds(obj("fontId" to str("   "))).isEmpty())
    }

    @Test
    fun `改成空串后能正确处理`() {
        assertEquals(
            listOf("有内容", "imported:real"),
            ParamsTree.collectFontIds(
                obj(
                    "a" to obj("fontId" to str("有内容")),
                    "b" to obj("fontId" to str("imported:real")),
                ),
            ),
        )
    }

    /*
     * ============================================================
     * 改写
     * ============================================================
     */

    @Test
    fun `改写会同时改顶层与组件里的字体`() {
        val json = params(topFont = "imported:aaa", componentFonts = listOf("imported:bbb"))
        val map = mapOf(
            "imported:aaa" to "sthkey-font://assets/fonts/A.ttf",
            "imported:bbb" to "sthkey-font://assets/fonts/B.ttf",
        )

        val changed = ParamsTree.rewriteFontIds(json) { map[it] }

        assertEquals(2, changed)
        assertEquals(str("sthkey-font://assets/fonts/A.ttf"), json.entries["fontId"])

        val component = ((json.entries["custom"] as JsonNode.Obj)
            .entries["components"] as JsonNode.Arr)
            .items[0] as JsonNode.Obj
        val style = component.entries["style"] as JsonNode.Obj
        assertEquals(str("sthkey-font://assets/fonts/B.ttf"), style.entries["fontId"])
    }

    @Test
    fun `映射返回 null 时保持原样`() {
        val json = params(topFont = "system:sans-serif", componentFonts = listOf("builtin:X.ttf"))

        // 只有导入字体进包：系统与内置字体的映射天然是 null
        val changed = ParamsTree.rewriteFontIds(json) { null }

        assertEquals(0, changed)
        assertEquals(str("system:sans-serif"), json.entries["fontId"])
    }

    @Test
    fun `只改字体字段不改别的字符串`() {
        val json = obj(
            "fontId" to str("imported:aaa"),
            // 值一样，但键名不是字体字段 —— 名字绝不能被改
            "name" to str("imported:aaa"),
            "description" to str("这句话里有个 fontId 字样"),
        )

        ParamsTree.rewriteFontIds(json) { "换掉了" }

        assertEquals(str("换掉了"), json.entries["fontId"])
        assertEquals("名字不该被改", str("imported:aaa"), json.entries["name"])
        assertEquals("描述不该被改", str("这句话里有个 fontId 字样"), json.entries["description"])
    }

    /**
     * 这条钉的是"数组里的裸字符串不要动"。
     *
     * 数组元素不带键名，无法判断该不该当字体处理 —— 宁可漏掉，
     * 也不要把用户写的文字内容改掉（那种错更难查）。
     */
    @Test
    fun `数组里的裸字符串不会被改`() {
        val json = obj(
            "fontId" to str("imported:aaa"),
            "watermarks" to arr(str("imported:aaa")),
        )

        ParamsTree.rewriteFontIds(json) { "换掉了" }

        assertEquals(str("换掉了"), json.entries["fontId"])
        val watermarks = (json.entries["watermarks"] as JsonNode.Arr).items
        assertEquals(
            "数组里的字符串没有键名，不理解它的语义就不该改",
            str("imported:aaa"),
            watermarks[0],
        )
    }

    @Test
    fun `嵌套数组里的对象也能被遍历到`() {
        val json = obj(
            "groups" to arr(
                arr(
                    obj("style" to obj("fontId" to str("imported:deep"))),
                ),
            ),
        )

        val changed = ParamsTree.rewriteFontIds(json) { "改到了" }

        assertEquals(1, changed)
        assertTrue(json.toString().contains("改到了"))
    }

    @Test
    fun `后缀形式的字体键也会被处理`() {
        // 约定是按后缀匹配，所以以后加 titleFontId 这类字段不需要动遍历代码
        val json = obj("titleFontId" to str("imported:title"))

        val changed = ParamsTree.rewriteFontIds(json) { "换掉了" }

        assertEquals(1, changed)
        assertEquals(str("换掉了"), json.entries["titleFontId"])
    }

    @Test
    fun `非字符串值不受影响`() {
        val json = obj(
            "fontId" to str("imported:aaa"),
            "count" to JsonNode.Other(7),
            "enabled" to JsonNode.Other(true),
            "missing" to JsonNode.Other(null),
        )

        val changed = ParamsTree.rewriteFontIds(json) { "换掉了" }

        assertEquals(1, changed)
        assertEquals(JsonNode.Other(7), json.entries["count"])
        assertEquals(JsonNode.Other(true), json.entries["enabled"])
        assertEquals(JsonNode.Other(null), json.entries["missing"])
    }

    /*
     * ============================================================
     * 包格式常量
     * ============================================================
     */

    /**
     * 新包用复数 `assets/fonts/`、老包用单数 `assets/font/`，
     * 两者必须互不误匹配 —— 否则读老包时会把条目找错。
     */
    @Test
    fun `新旧字体目录前缀互不误匹配`() {
        assertTrue(ConfigPackageCodec.DIR_FONTS == "assets/fonts/")
        assertTrue(ConfigPackageCodec.LEGACY_DIR_FONT == "assets/font/")

        assertTrue(
            "新前缀不该被老前缀匹配到",
            !ConfigPackageCodec.DIR_FONTS.startsWith(ConfigPackageCodec.LEGACY_DIR_FONT),
        )
        assertTrue(
            "老前缀不该被新前缀匹配到",
            !ConfigPackageCodec.LEGACY_DIR_FONT.startsWith(ConfigPackageCodec.DIR_FONTS),
        )
    }
}

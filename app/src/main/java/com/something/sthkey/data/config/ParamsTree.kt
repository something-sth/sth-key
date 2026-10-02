package com.something.sthkey.data.config

import org.json.JSONObject

/**
 * params.json 的**深度遍历**：找字体引用、改字体引用。
 *
 * ============================================================
 * 为什么必须"遍历整棵树"，不能只改 config.fontId
 * ============================================================
 * 这是自定义 Key 带来的一条真实缺口。字体 id 在 params 里**不止一处**：
 *
 * ```
 * params.json
 * ├── fontId                       ← Key 样式用（顶层）
 * └── custom
 *     └── components[]
 *         └── style
 *             └── fontId           ← **每个组件自己的字体**
 * ```
 *
 * 早先导出时只改顶层那一个 `fontId`，于是自定义 Key 的组件字体
 * 原样带着 `imported:<本机 uuid>` 出了包 —— 导入方没有这个 id，
 * 字体就静默回落成默认。而且**不报错**：用户只会看到"字体好像变了"。
 *
 * 现在改成"扫一遍树、见到字体字段就处理"，以后再加 `titleFontId`
 * 之类的字段也不需要动这里。
 *
 * ============================================================
 * 为什么按**键名**识别，而不是按路径
 * ============================================================
 * 键名约定统一是 `fontId`（自定义组件的字段也叫这个）。
 * 按固定路径写（`custom.components[].style.fontId`）的话，
 * 参数结构一变就要跟着改，而漏改的后果是静默丢字体 —— 与上面那个 bug 同类。
 * 按后缀匹配则对"新增一层、换个容器"都免疫。
 *
 * ============================================================
 * 算法跑在 [JsonNode] 上，不在 JSONObject 上
 * ============================================================
 * 因为 `org.json` 在本地单元测试里是空壳（一调就抛 `Method not mocked`），
 * 而这段逻辑**必须有测试** —— 它修的是静默 bug，肉眼审代码看不出来。
 * `org.json` 只出现在下面两个公开函数的转换里，那两步浅显且出错会立刻炸。
 */
internal object ParamsTree {

    /** 字体字段的键名后缀；`fontId` 与 `titleFontId` 这类都算 */
    private const val FONT_KEY = "fontId"

    private fun String.isFontKey(): Boolean =
        this == FONT_KEY || endsWith(FONT_KEY.replaceFirstChar { it.uppercase() })

    /*
     * ============================================================
     * 公开 API（对 org.json）
     * ============================================================
     */

    /** 收集树里出现过的全部字体 id；保持首次出现的顺序（包内容因此稳定） */
    fun collectFontIds(root: JSONObject): List<String> =
        collectFontIds(JsonNode.fromJson(root))

    /**
     * 把树里的字体 id 按 [map] 改写（**原地修改** [root]）。
     *
     * @param map 旧值 → 新值；返回 null 表示保持原样
     * @return 实际改写的处数
     */
    fun rewriteFontIds(root: JSONObject, map: (String) -> String?): Int {
        val tree = JsonNode.fromJson(root)
        val changed = rewriteFontIds(tree, map)
        if (changed == 0) return 0

        /*
         * 把改好的树写回原来的对象。
         *
         * 用"清空 + 重填"而不是逐个 put：树里可能有被删掉的键（类型变化），
         * 逐个 put 会留下旧键。`keys()` 先取快照再清，否则边遍历边删会出问题。
         */
        val rebuilt = JsonNode.toJson(tree) as? JSONObject ?: return 0
        root.keys().asSequence().toList().forEach { root.remove(it) }
        rebuilt.keys().asSequence().forEach { key -> root.put(key, rebuilt.opt(key)) }
        return changed
    }

    /*
     * ============================================================
     * 算法（纯 Kotlin，可测）
     * ============================================================
     */

    /** @return 去重后的字体 id，保持首次出现的顺序 */
    internal fun collectFontIds(node: JsonNode): List<String> {
        val found = LinkedHashSet<String>()
        forEachString(node) { key, value ->
            /*
             * ⚠️ 这个 lambda 必须返回 String?：`found += value` 的结果是 Unit，
             * 直接用它会编译不过。返回 null 就是"不改动"。
             */
            if (key.isFontKey() && value.isNotBlank()) found += value
            null
        }
        return found.toList()
    }

    /** @return 改写处数 */
    internal fun rewriteFontIds(node: JsonNode, map: (String) -> String?): Int {
        var changed = 0
        forEachString(node) { key, value ->
            // 同样不能用 `return@` 提前返回：带标签的返回会把 lambda 钉成 Unit
            val replacement = if (key.isFontKey()) map(value) else null
            if (replacement != null && replacement != value) changed++
            replacement
        }
        return changed
    }

    /**
     * 递归遍历所有字符串字段，并用 [onString] 的返回值写回（null = 不改）。
     *
     * 用一个"读改写"回调而不是两个（一个只读、一个改写）：两套会共享同一段
     * 递归却必须写成两份，改的时候更容易漏掉一份。
     */
    private fun forEachString(
        node: JsonNode,
        onString: (key: String, value: String) -> String?,
    ) {
        when (node) {
            is JsonNode.Obj -> node.entries.forEach { (key, child) ->
                if (child is JsonNode.Str) {
                    val replacement = onString(key, child.value)
                    if (replacement != null && replacement != child.value) {
                        node.entries[key] = JsonNode.Str(replacement)
                    }
                } else {
                    forEachString(child, onString)
                }
            }

            is JsonNode.Arr -> node.items.forEach { item ->
                /*
                 * ⚠️ 数组里的**裸字符串不带键名**，无法判断该不该当字体处理。
                 *
                 * 这里刻意跳过它：宁可漏掉（用户能在界面上看到字体没跟过来），
                 * 也不要把一个恰好长得像字体 id 的普通字符串改掉 ——
                 * 那种错更难查，因为它改的是**用户写的文字内容**。
                 * 真出现"字符串数组形式的字体字段"时，再加显式的键名规则。
                 */
                if (item !is JsonNode.Str) forEachString(item, onString)
            }

            is JsonNode.Str, is JsonNode.Other -> Unit
        }
    }
}

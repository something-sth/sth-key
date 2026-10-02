package com.something.sthkey.data.config

/**
 * params.json 的**纯 Kotlin 树**。
 *
 * ============================================================
 * 为什么不用 org.json
 * ============================================================
 * `org.json` 在 Android 上是**运行时实现**，而本地单元测试里只有一个空壳
 * （方法一调就抛 `Method not mocked`）。于是"遍历 params 找字体"这段逻辑
 * 在 JVM 测试里根本跑不起来 —— 而它恰恰是最该被测的部分：
 *
 * 它修的是一个**静默**的 bug：早先导出只改顶层 `fontId`，
 * 自定义 Key 每个组件自己的 `style.fontId` 带着本机 uuid 出了包，
 * 导入方没有那个 id，字体悄悄回落成默认，不报错也不提示。
 *
 * 所以这里把树抽象出来：算法对 [JsonNode] 操作（纯 Kotlin，可测），
 * `org.json` 只在 [JsonNode.fromJson] / [JsonNode.toJson] 这两步出现 ——
 * 那两步是浅层且显而易见的转换，出错会立刻炸，不会静默。
 */
sealed interface JsonNode {

    /** 对象：键 → 子节点 */
    data class Obj(val entries: LinkedHashMap<String, JsonNode> = LinkedHashMap()) : JsonNode

    /** 数组 */
    data class Arr(val items: MutableList<JsonNode> = mutableListOf()) : JsonNode

    /** 字符串 */
    data class Str(val value: String) : JsonNode

    /** 数字 / 布尔 / null —— 遍历不关心它们的区别，原样带过 */
    data class Other(val value: Any?) : JsonNode

    companion object {

        /**
         * 从 `org.json` 结构转过来。
         *
         * ⚠️ 用 `LinkedHashMap` 保序：转换回去时键的顺序不变 ——
         * 导出的 params.json 是给人看、也可能被手工改的，
         * 每次导出都换个键序会让人以为"内容变了"。
         */
        fun fromJson(value: Any?): JsonNode = when (value) {
            is org.json.JSONObject -> Obj(
                LinkedHashMap<String, JsonNode>().apply {
                    value.keys().asSequence().forEach { key ->
                        put(key, fromJson(value.opt(key)))
                    }
                },
            )

            is org.json.JSONArray -> Arr(
                MutableList(value.length()) { index -> fromJson(value.opt(index)) },
            )

            // JSONObject.NULL 落到这里；用 null 表示
            is String -> Str(value)
            else -> Other(value)
        }

        /**
         * 转回 `org.json` 结构。
         *
         * [JsonNode.Other] 里的 null 写成 `JSONObject.NULL` ——
         * `JSONObject.put(key, null)` 是**删除那个键**，不是写一个 null 值，
         * 两者差别很大（老代码里就有过"写了 null 结果字段没了"的坑）。
         */
        fun toJson(node: JsonNode): Any? = when (node) {
            is Obj -> org.json.JSONObject().apply {
                node.entries.forEach { (key, child) -> put(key, toJson(child)) }
            }

            is Arr -> org.json.JSONArray().apply {
                node.items.forEach { put(toJson(it)) }
            }

            is Str -> node.value
            is Other -> node.value ?: org.json.JSONObject.NULL
        }
    }
}

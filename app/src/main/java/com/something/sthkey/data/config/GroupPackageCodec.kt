package com.something.sthkey.data.config

import org.json.JSONArray
import org.json.JSONObject

/**
 * `.sthgroup` 配置组合包的格式定义。
 *
 * ============================================================
 * ⚠️ 包结构（与 `.sthkey` 一致的思路）
 * ============================================================
 * ```
 * 我的组合.sthgroup        (zip)
 * ├── manifest.json        ← 组合的元信息（本文件定义的格式）
 * ├── 配置A.sthkey         ← 直接是**完整的配置包**（含字体 / Live2D）
 * └── 配置B.sthkey
 * ```
 *
 * ⚠️⚠️ 为什么每个配置都存成完整 `.sthkey`，而不是"解包后平铺":
 *
 * | 好处 | 说明 |
 * |---|---|
 * | **复用全部现有逻辑** | 字体去重、Live2D 去重、manifest 校验、重名处理全都不用重写 |
 * | 单个也能用 | 用户从包里挑一个 `.sthkey` 出来照样能导入 |
 * | 不会产生"半份" | 配置包的原子性由 `.sthkey` 自己保证 |
 *
 * ⚠️ zip 里套 zip 是合法的，导入时逐条解到临时文件即可。
 *
 * ============================================================
 * ⚠️ 扩展名为什么是 `.sthgroup` 而不是 `.sthg`
 * ============================================================
 * 用户的原话:
 *
 * > 我一开始想的是 .sthgroup，但又想会不会太长，换成 .sthg？好像太简短了
 *
 * `.sthg` 太短容易被别的软件当成未知文件，也不容易从名字看出是"sth 的 group"。
 */
object GroupPackageCodec {

    /** 格式标识，用来拒绝"随便一个 zip" */
    const val FORMAT_ID = "sthkey-group"

    const val FORMAT_VERSION = 1

    const val EXTENSION = ".sthgroup"

    /** ⚠️ 导入时也接受 `.zip`（有些文件管理器会把后缀改掉） */
    val ACCEPTED_EXTENSIONS = listOf(".sthgroup", ".zip")

    const val ENTRY_MANIFEST = "manifest.json"

    /**
     * 包内每个配置的引用。
     *
     * ⚠️ `file` 是**包内路径**（就是 zip entry 名），导入时按它找条目。
     * ⚠️ `configs` 的**顺序**决定组合里成员的顺序 —— 不能依赖 zip entry 的遍历顺序
     * （那个顺序是不保证的）。
     */
    data class ConfigRef(
        val file: String,
        val name: String = "",
        val description: String = "",
        val styleId: String = "",
    )

    data class Manifest(
        val format: String,
        val formatVersion: Int,
        val appVersion: String,
        val exportedAt: Long,
        val name: String,
        val description: String,
        val configs: List<ConfigRef>,
    )

    fun encodeManifest(
        name: String,
        description: String,
        appVersion: String,
        configs: List<ConfigRef>,
    ): String = JSONObject().apply {
        put("format", FORMAT_ID)
        put("formatVersion", FORMAT_VERSION)
        put("appVersion", appVersion)
        put("exportedAt", System.currentTimeMillis())
        put("name", name)
        put("description", description)
        put(
            "configs",
            JSONArray().apply {
                configs.forEach { ref ->
                    put(
                        JSONObject().apply {
                            put("file", ref.file)
                            put("name", ref.name)
                            put("description", ref.description)
                            put("styleId", ref.styleId)
                        },
                    )
                }
            },
        )
    }.toString(2)

    /**
     * 解析 manifest。
     *
     * ⚠️ `configs` 为空也算合法 —— 允许导出/导入一个**空组合**
     * （组合本身可以是空的，用户之后再加配置）。
     *
     * @return null 表示不是组合包（格式标识不对或缺必需字段）
     */
    fun decodeManifest(raw: String): Manifest? = try {
        val obj = JSONObject(raw)
        val format = obj.optString("format")
        if (format != FORMAT_ID) {
            null
        } else {
            val array = obj.optJSONArray("configs")
            val refs = buildList {
                if (array != null) {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        val file = item.optString("file")
                        /*
                         * ⚠️ 没有 file 的条目直接丢弃:导入时无从找起，
                         * 留着只会在报告里多一条永远失败的记录。
                         */
                        if (file.isBlank()) continue
                        add(
                            ConfigRef(
                                file = file,
                                name = item.optString("name"),
                                description = item.optString("description"),
                                styleId = item.optString("styleId"),
                            ),
                        )
                    }
                }
            }
            Manifest(
                format = format,
                formatVersion = obj.optInt("formatVersion", 0),
                appVersion = obj.optString("appVersion"),
                exportedAt = obj.optLong("exportedAt"),
                name = obj.optString("name"),
                description = obj.optString("description"),
                configs = refs,
            )
        }
    } catch (e: Exception) {
        /* ⚠️ 解析失败 → 当成"不是组合包"，由调用方给出提示，不要抛 */
        null
    }
}

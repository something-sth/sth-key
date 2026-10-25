package com.something.sthkey.data.config

import com.something.sthkey.domain.config.ConfigGroup
import org.json.JSONArray
import org.json.JSONObject

/**
 * 配置组合的 JSON 编解码。
 *
 * ============================================================
 * ⚠️ 为什么不复用 `JsonConfigCodec`
 * ============================================================
 * 那个是给**配置**（几百个字段）用的，结构复杂；组合只有四个字段，
 * 硬套那套框架（带 `ParamsTree`、缺字段回退表）反而更绕。
 *
 * ⚠️ 但**容错策略必须一致**:
 * | 情况 | 行为 |
 * |---|---|
 * | 整条记录解不开 | **跳过这一条**，其余照常加载（不能整盘失败） |
 * | 缺字段 | 用默认值 |
 * | `memberIds` 里有不存在的 id | 保留（由 [GroupStore] 在读取后清理） |
 */
object GroupCodec {

    /** 存储格式版本。⚠️ 只在**结构**变化时加，加字段不算 */
    private const val VERSION = 1

    fun encodeList(groups: List<ConfigGroup>): String {
        val array = JSONArray()
        groups.forEach { group -> array.put(encode(group)) }
        return array.toString()
    }

    fun decodeList(raw: String?): List<ConfigGroup> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val obj = array.optJSONObject(index) ?: continue
                    val group = decode(obj) ?: continue
                    add(group)
                }
            }
        } catch (e: Exception) {
            /*
             * ⚠️ 整盘坏掉时返回空列表而不是抛异常:
             * 抛出去会让应用一启动就崩，用户连"新建组合"都点不到。
             * 组合丢了只是功能缺失，配置本身（另一份存储）不受影响。
             */
            emptyList()
        }
    }

    private fun encode(group: ConfigGroup): JSONObject = JSONObject().apply {
        put("version", VERSION)
        put("id", group.id)
        put("name", group.name)
        put("description", group.description)
        put("createdAt", group.createdAt)
        put("memberIds", JSONArray().apply { group.memberIds.forEach { put(it) } })
    }

    /** @return null 表示这条记录不可用（缺 id 或名字） */
    private fun decode(obj: JSONObject): ConfigGroup? {
        val id = obj.optString("id")
        val name = obj.optString("name")
        /*
         * ⚠️ id 是唯一标识、name 是给用户看的 —— 缺任何一个这条记录都没意义，
         * 直接丢弃比留一条"无名组合"好（后者在界面上无法区分、也无法删除）。
         */
        if (id.isBlank() || name.isBlank()) return null

        val members = obj.optJSONArray("memberIds")
        val ids = buildList {
            if (members != null) {
                for (index in 0 until members.length()) {
                    val memberId = members.optString(index)
                    if (memberId.isNotBlank() && memberId !in this) add(memberId)
                }
            }
        }

        return ConfigGroup(
            id = id,
            name = name,
            description = obj.optString("description"),
            memberIds = ids,
            createdAt = obj.optLong("createdAt"),
        )
    }
}

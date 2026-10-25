package com.something.sthkey.domain.config

/**
 * **配置组合** —— 一组配置的开关。
 *
 * ============================================================
 * ⚠️ 它**不是一种样式**，也不产生新的悬浮窗
 * ============================================================
 * 用户的原话:
 *
 * > 配置分组并不是一种配置类型，所以应该独立分出来
 * > ……开启这个配置组合，会将这个配置组合里的所有配置都一并启动
 *
 * ⚠️ 所以刻意**不**给 `KeyStrokesConfig` 加 `groupId` 字段，而是把成员关系存在
 * 组合这一侧（[memberIds]）。理由:
 *
 * | 放哪边 | 代价 |
 * |---|---|
 * | 配置上存 `groupId` | 删除组合时要扫一遍所有配置去清字段，容易漏 |
 * | **组合上存 `memberIds`** | 删组合 = 删一条记录，配置完全不受影响（"解散"天然成立） |
 *
 * ⚠️ 一个配置**最多属于一个组合**（用户确认为"否"多归属）。
 * 由 [com.something.sthkey.data.config.GroupStore] 保证，见那边的说明。
 *
 * @param id 稳定标识（UUID）。⚠️ 导出再导入后会重建，不要跨设备引用
 * @param memberIds 成员配置的 id，**有序** —— 主页展开后的顺序就是它
 */
data class ConfigGroup(
    val id: String,
    val name: String,
    val description: String = "",
    val memberIds: List<String> = emptyList(),
    val createdAt: Long = 0L,
)

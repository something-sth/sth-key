package com.something.sthkey.domain.config

/**
 * 配置组合的**开关语义**（纯函数，可单测）。
 *
 * ============================================================
 * ⚠️⚠️ 组合的开关**不单独存**，是**算出来的**
 * ============================================================
 * 用户的原话:
 *
 * > 如果这个配置组合是关闭的，那么单独开启里面的配置时，不全部开启都不刷新 switch，
 * > 全部开启时才顺便将这个配置组合 switch 调成开启；
 * > 如果这个配置组合是开启的，那么也一样，单独关某一个还是显示开启，
 * > 全部关闭时，这个配置组合的 switch 才会关闭
 *
 * ⭐ 这一整套规则刚好等价于一句:
 *
 *     组合开着 ⇔ 成员非空 且 所有成员都开着
 *
 * ⚠️ 所以**不需要任何额外状态**，也就不可能出现"两个状态不同步"。
 *
 * ⚠️ 关于"开启时单独关一个还是显示开启":按上面这句推导，应当是**关**。
 * （用户那句"也一样"意思含糊，这里按**同一套规则**处理 —— 两侧逻辑对称，
 * 不会出现"关一个不变、全关才变"这种需要额外记忆的特例。）
 *
 * ⚠️ 空组合:按公式恒为"关"。UI 上还要把开关**禁用**，否则点了没反应。
 */
object GroupSwitch {

    /** 组合是否处于"开启"状态（派生） */
    fun isOn(group: ConfigGroup, enabledIds: Set<String>): Boolean =
        group.memberIds.isNotEmpty() && group.memberIds.all { it in enabledIds }

    /**
     * 打开/关闭整个组合，返回新的启用集合。
     *
     * ⚠️ 打开时是"并集"而不是"替换":集合里还可能有**不属于任何组合**的配置，
     * 那些不该被这次操作影响。
     * ⚠️ 关闭时只移除**本组合的成员**，同理。
     */
    fun setEnabled(
        group: ConfigGroup,
        enabledIds: Set<String>,
        enabled: Boolean,
    ): Set<String> = if (enabled) {
        enabledIds + group.memberIds
    } else {
        enabledIds - group.memberIds.toSet()
    }

    /**
     * 组合里有多少个成员是开着的（主页副标题用"2/3 已开启"时可读性更好）。
     */
    fun enabledCount(group: ConfigGroup, enabledIds: Set<String>): Int =
        group.memberIds.count { it in enabledIds }
}

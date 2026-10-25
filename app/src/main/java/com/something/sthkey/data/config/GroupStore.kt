package com.something.sthkey.data.config

import android.content.Context
import com.something.sthkey.core.log.AppLog
import com.something.sthkey.core.prefs.AppPrefs
import com.something.sthkey.domain.config.ConfigGroup
import java.util.UUID

/**
 * 配置组合的存取（单例，与 [ConfigStore] 同构）。
 *
 * ============================================================
 * ⚠️ 归属规则:一个配置最多属于一个组合
 * ============================================================
 * 用户确认:"1 我选否"（不允许一个配置同时属于多个组合）。
 *
 * ⚠️ 为什么不允许:
 * 同一个配置出现在两个组合里时，"关掉组合 A 会不会把组合 B 的悬浮窗也关掉"
 * 就没有干净答案 —— 用户会撞上"我关了 A，B 里的那个也没了"这种无法解释的现象。
 *
 * ⚠️ 所以 [addMembers] 会**先把这些 id 从其它组合里摘掉**（移动语义），
 * 界面上再配一个确认对话框（"该配置已在组合「X」里，加入后会从那里移走"）。
 *
 * ============================================================
 * ⚠️ 存储
 * ============================================================
 * 与 [ConfigStore] 一致:全量写进 [AppPrefs.configGroupsJson]，用 `apply()` 异步落盘。
 * 组合只有个位数、JSON 也就几 KB，全量写最简单也最不容易漏字段。
 */
class GroupStore private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = AppPrefs.get(appContext)

    private val groups = mutableListOf<ConfigGroup>()

    init {
        load()
    }

    /** 全部组合，按创建顺序 */
    fun all(): List<ConfigGroup> = groups.toList()

    fun find(id: String): ConfigGroup? = groups.firstOrNull { it.id == id }

    /** 某个配置属于哪个组合；不属于任何组合时返回 null */
    fun groupOf(configId: String): ConfigGroup? =
        groups.firstOrNull { configId in it.memberIds }

    /** 被任何组合引用过的配置 id 集合（配置页靠它决定隐藏谁） */
    fun groupedConfigIds(): Set<String> =
        groups.flatMap { it.memberIds }.toSet()

    /**
     * 新建一个组合。
     *
     * @param memberIds 初始成员（会自动从其它组合里摘掉，见类注释）
     */
    fun create(
        name: String,
        description: String = "",
        memberIds: List<String> = emptyList(),
    ): ConfigGroup {
        val group = ConfigGroup(
            id = UUID.randomUUID().toString(),
            /*
             * ⚠️ 名称**允许留空**（用户要求）:留空时用「新建配置组合」，
             * 重名加 " (2)" 后缀 —— 与配置的命名规则一致。
             * 所以界面上**不再**做"空白爆红"提示。
             */
            name = nextAvailableName(name.trim().ifEmpty { "新建配置组合" }),
            description = description.trim(),
            memberIds = emptyList(),
            createdAt = System.currentTimeMillis(),
        )
        groups.add(group)
        /*
         * ⚠️ 先 add 再 addMembers:后者要做"从其它组合摘掉"，
         * 顺序反过来的话新组合自己也会被清理逻辑扫到（虽然结果一样，但没必要）。
         */
        if (memberIds.isNotEmpty()) {
            addMembers(group.id, memberIds)
        }
        persist()
        AppLog.i(TAG, "新建组合「${group.name}」，成员 ${memberIds.size} 个")
        return find(group.id) ?: group
    }

    /** 改名 / 改描述 */
    fun update(groupId: String, name: String? = null, description: String? = null): Boolean {
        val index = groups.indexOfFirst { it.id == groupId }
        if (index < 0) return false
        val current = groups[index]
        groups[index] = current.copy(
            name = name?.trim()?.takeIf { it.isNotEmpty() } ?: current.name,
            description = description?.trim() ?: current.description,
        )
        persist()
        return true
    }

    /**
     * 往组合里加成员。
     *
     * ⚠️ **移动语义**:这些 id 会先从其它组合里摘掉（一个配置只属于一个组合）。
     * ⚠️ 已经在**本组合**里的 id 会被忽略（不重复、不改变顺序）。
     */
    fun addMembers(groupId: String, configIds: List<String>) {
        val index = groups.indexOfFirst { it.id == groupId }
        if (index < 0) return

        /* 1) 先从其它组合里摘掉（移动语义） */
        val moved = configIds.toSet()
        for (otherIndex in groups.indices) {
            if (otherIndex == index) continue
            val other = groups[otherIndex]
            if (other.memberIds.none { it in moved }) continue
            groups[otherIndex] = other.copy(memberIds = other.memberIds.filterNot { it in moved })
        }

        /* 2) 再追加到本组合末尾，保持用户选择顺序 */
        val current = groups[index]
        val existing = current.memberIds.toSet()
        val appended = configIds.filter { it !in existing }
        if (appended.isEmpty()) {
            persist()
            return
        }
        groups[index] = current.copy(memberIds = current.memberIds + appended)
        persist()
        AppLog.i(TAG, "组合「${current.name}」加入 ${appended.size} 个配置")
    }

    /** 从组合里移除成员（配置本身不动，会重新出现在配置页） */
    fun removeMembers(groupId: String, configIds: List<String>) {
        val index = groups.indexOfFirst { it.id == groupId }
        if (index < 0) return
        val current = groups[index]
        val removed = configIds.toSet()
        val next = current.memberIds.filterNot { it in removed }
        if (next.size == current.memberIds.size) return
        groups[index] = current.copy(memberIds = next)
        persist()
        AppLog.i(TAG, "组合「${current.name}」移出 ${current.memberIds.size - next.size} 个配置")
    }

    /** 成员顺序调整（详情页里拖动 / 移动时用） */
    fun reorderMembers(groupId: String, memberIds: List<String>) {
        val index = groups.indexOfFirst { it.id == groupId }
        if (index < 0) return
        groups[index] = groups[index].copy(memberIds = memberIds)
        persist()
    }

    /**
     * 解散组合（用户点「解散」）。
     *
     * ⚠️ **只删组合这一条记录**，成员配置本身完全不动 ——
     * 它们会重新出现在配置页（因为不再被任何组合引用）。
     * 这就是为什么"解散"比"整体删除"安全，也是它天然不怕 `default` 的原因。
     */
    fun dissolve(groupId: String): ConfigGroup? {
        val index = groups.indexOfFirst { it.id == groupId }
        if (index < 0) return null
        val removed = groups.removeAt(index)
        persist()
        AppLog.i(TAG, "解散组合「${removed.name}」，${removed.memberIds.size} 个配置回到列表")
        return removed
    }

    /**
     * 清理悬空引用（配置被删除时调用）。
     *
     * ⚠️⚠️ 必须调用:否则删掉一个配置后，组合里会永远留着它的 id ——
     * 表现为"组合说是有 3 个配置，展开只有 2 个"，而且删不掉。
     *
     * @return 是否有组合被改动
     */
    fun pruneMissing(existingConfigIds: Set<String>): Boolean {
        var changed = false
        for (index in groups.indices) {
            val group = groups[index]
            val kept = group.memberIds.filter { it in existingConfigIds }
            if (kept.size == group.memberIds.size) continue
            groups[index] = group.copy(memberIds = kept)
            changed = true
            AppLog.i(TAG, "组合「${group.name}」清理了 ${group.memberIds.size - kept.size} 个已删除的配置")
        }
        if (changed) persist()
        return changed
    }

    /**
     * 算一个不重名的组合名（重名加 " (2)" / " (3)" …）。
     *
     * ⚠️ 与 `ConfigStore.nextAvailableName` **同一套规则**:
     * 导入组合时也要用，两处规则不同的话会出现"导入后名字和新建时长得不一样"。
     */
    fun nextAvailableName(base: String): String {
        val name = base.trim().ifBlank { "新建配置组合" }
        val existing = groups.map { it.name }.toSet()
        if (name !in existing) return name

        var index = 2
        while ("$name ($index)" in existing) index++
        return "$name ($index)"
    }

    private fun load() {
        groups.clear()
        val stored = GroupCodec.decodeList(prefs.configGroupsJson)
        groups.addAll(stored)
        AppLog.i(TAG, "配置组合已加载：${groups.size} 个")
        if (stored.isEmpty() && prefs.configGroupsJson != null) {
            /* ⚠️ 有数据但一条都解不开（格式坏了）→ 落一次空盘，别每次启动都重读坏数据 */
            persist()
        }
    }

    private fun persist() {
        prefs.configGroupsJson = GroupCodec.encodeList(groups)
    }

    companion object {
        private const val TAG = "GroupStore"

        @Volatile
        private var instance: GroupStore? = null

        fun get(context: Context): GroupStore =
            instance ?: synchronized(this) {
                instance ?: GroupStore(context).also { instance = it }
            }
    }
}

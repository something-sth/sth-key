package com.something.sthkey.data.shortcut

import android.content.Context
import com.something.sthkey.core.log.AppLog
import com.something.sthkey.core.prefs.AppPrefs

/**
 * 一个桌面快捷方式要启动哪些配置。
 *
 * ============================================================
 * ⚠️ 为什么每个快捷方式一份清单，而不是全局一份
 * ============================================================
 * 用户**可以创建多个**"启动悬浮窗"快捷方式 —— 比如
 * 「只开键位显示」和「只开 CPS 显示」各一个图标。
 *
 * 第一版做成了全局一份清单 + 固定的 `shortcutId`，后果是
 * **第二次创建会覆盖第一个**（`shortcutId` 相同，系统认为是同一个
 * 快捷方式，直接替换掉）。用户看到的现象是"我只能加两个：
 * 一个启动的、一个关闭的" —— 想再加第三个就没反应了。
 *
 * 所以现在：每次"添加到桌面"分配一个**唯一 id**
 * （`launch_overlays_1`、`_2`…），清单按这个 id 分开存。
 *
 * ============================================================
 * ⚠️ id 会随 Intent 一起被系统冻结
 * ============================================================
 * 快捷方式的 Intent 一经钉到桌面就冻结了 —— 所以
 * **"这个快捷方式启动哪几份配置"这件事可以在偏好里改**
 * （点图标时按 id 去查），但 **id 本身改不了**。
 *
 * 这也是为什么"编辑已有快捷方式"能生效、而"改变它的 id"不能。
 */
object ShortcutSettings {

    private const val TAG = "Shortcut"

    /**
     * 快捷方式的动作。
     *
     * ⚠️ 值与 Intent 里的 extra 一一对应；改动它们等于让**已经钉在桌面上**
     * 的快捷方式失效（Intent 是冻结的，旧值不会跟着变）。所以这些字符串
     * 是**对外契约**，不要随手重命名。
     */
    const val ACTION_LAUNCH = "launch"
    const val ACTION_DISABLE_ALL = "disable_all"

    /** Intent extra 的键 */
    const val EXTRA_ACTION = "shortcut_action"

    /**
     * ⚠️ id 也放进 Intent。
     *
     * 因为"启动哪几份配置"是按 id 查的 —— 不带 id 的话所有启动类快捷方式
     * 都会读到同一份清单，那正是第一版的 bug。
     */
    const val EXTRA_SHORTCUT_ID = "shortcut_id"

    /** 关闭类快捷方式固定用这一个 id（只允许存在一个，见 [nextLauncherId]） */
    const val DISABLE_SHORTCUT_ID = "disable_all_overlays"

    /** 启动类 id 的前缀；后面跟序号 */
    private const val LAUNCH_PREFIX = "launch_overlays_"

    /**
     * 分配一个**没用过**的启动类快捷方式 id。
     *
     * ⚠️ 不能只用"当前有几个"来算序号：用户删掉中间某一个之后，
     * 序号会重复，于是新快捷方式会**顶掉**已存在的那个。
     * 所以这里取"已用过的最大序号 + 1"。
     */
    fun nextLauncherId(context: Context): String {
        val used = decode(AppPrefs.get(context).shortcutConfigIds).keys
        val maxIndex = used
            .filter { it.startsWith(LAUNCH_PREFIX) }
            .mapNotNull { it.removePrefix(LAUNCH_PREFIX).toIntOrNull() }
            .maxOrNull() ?: 0
        return "$LAUNCH_PREFIX${maxIndex + 1}"
    }

    /** 某个快捷方式要启动哪几份配置 */
    fun launcherConfigIds(context: Context, shortcutId: String): List<String> =
        decode(AppPrefs.get(context).shortcutConfigIds)[shortcutId].orEmpty()

    /** 写入某个快捷方式的清单 */
    fun setLauncherConfigIds(context: Context, shortcutId: String, ids: List<String>) {
        val all = decode(AppPrefs.get(context).shortcutConfigIds).toMutableMap()
        all[shortcutId] = ids
        AppPrefs.get(context).shortcutConfigIds = encode(all)
        AppLog.i(TAG, "快捷方式 $shortcutId 的配置已更新：${ids.size} 份")
    }

    /*
     * ============================================================
     * ⚠️ 这里**没有删除**，也**没有"列出已有的"**
     * ============================================================
     * 曾经有过 `remove()` 与 `allLaunchers()`，配合主页一个
     * "已有的快捷方式"列表 + 删除按钮。但那件事**做不到**：
     *
     * 我们能移除的只是自己这条记录与系统里的声明，
     * 而**桌面上那个图标是启动器管的** —— 多数启动器不会因为我们调用
     * `removeLongLivedShortcuts` 就把它删掉。结果是用户点了删除、
     * 列表里没了，但桌面图标还在，而且已经变成**空壳**。
     *
     * **"删不干净"比"没有删除功能"更糟**，所以整块去掉了。
     * 要删就让用户在桌面上长按删 —— 那是启动器自己的功能。
     *
     * 于是这个对象只负责两件事：**分配 id** 与**按 id 存取清单**。
     * 清单里残留的 id 不会有人读（没有"列出"），所以也不需要清理。
     */

    /**
     * 清单里**还在的**配置，过滤掉已删除的。
     *
     * ⚠️ 必须过滤：用户在主页勾了几份配置做快捷方式，之后去配置页删掉一份 ——
     * 那时清单里还留着一个不存在的 id。不过滤的话
     * `OverlayController` 会把它当有效 id 写进"启用了哪些配置"，
     * 而服务找不到这份配置，表现是"点快捷方式没反应"或
     * "窗口数比勾的少一个"，很难联想到是删配置导致的。
     */
    fun resolveExisting(context: Context, shortcutId: String, allConfigIds: Set<String>): List<String> =
        launcherConfigIds(context, shortcutId).filter { it in allConfigIds }

    /*
     * 存储格式：`id=配置id,配置id` 每行一条。
     *
     * 不用 JSON：快捷方式 id 与配置 id 都是受限字符集（字母数字下划线连字符），
     * 不可能含 `=`、`,`、换行。为此引入 JSON 解析得不偿失，
     * 还多一处可能解析失败的地方（失败就只能回落成空，而用户会以为选择丢了）。
     */
    private fun encode(map: Map<String, List<String>>): String =
        map.entries
            .filter { it.key.isNotBlank() }
            .joinToString("\n") { (id, ids) ->
                "$id=${ids.filter { it.isNotBlank() }.distinct().joinToString(",")}"
            }

    private fun decode(raw: String): Map<String, List<String>> =
        raw.split('\n')
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@mapNotNull null

                val id = trimmed.substringBefore('=')
                if (id.isBlank()) return@mapNotNull null

                val ids = trimmed.substringAfter('=', "")
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

                id to ids
            }
            .toMap()
}

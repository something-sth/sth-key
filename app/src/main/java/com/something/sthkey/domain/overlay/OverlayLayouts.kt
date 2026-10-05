package com.something.sthkey.domain.overlay

import android.content.Context
import com.something.sthkey.core.log.AppLog
import com.something.sthkey.core.prefs.AppPrefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一个悬浮窗的**本机设置**。
 *
 * ============================================================
 * 为什么不放进配置里
 * ============================================================
 * 这些值跟设备屏幕强相关（位置、偏移都是像素），换台设备就完全不对，
 * 所以**不随配置导出**。放在 [AppPrefs] 里就天然满足了这一点 ——
 * 配置包只打包配置对象，压根读不到这些字段，不需要在导出逻辑里写排除。
 *
 * @param touchable 是否可触摸。`false` = 纯贴图：不可拖动、也不拦截点击
 * @param movableOffScreen 是否允许把窗口**拖到屏幕外**（默认关闭）
 * @param baseX 基础坐标 X（像素）；[POSITION_UNSET] 表示用户还没拖过
 * @param baseY 基础坐标 Y（像素）
 * @param offsetX 额外偏移 X（像素），可以在不可触摸时用滑块调
 * @param offsetY 额外偏移 Y（像素）
 */
data class OverlayLayout(
    val touchable: Boolean = true,
    /**
     * 允许窗口跑到**屏幕之外**（用户要求，默认关）。
     *
     * ============================================================
     * 它同时改两件事，必须成对
     * ============================================================
     * 1. 窗口标志加 `FLAG_LAYOUT_NO_LIMITS` —— 让**系统**不再约束位置；
     * 2. 我们自己的拖拽夹取**也要关掉** —— 见 [OverlayBounds.clampOrFree]。
     *
     * ⚠️ 只做第 1 件的话，窗口还是被我们夹在屏幕内，"能拖出去"根本没生效
     * （表现就是"开关打开了但没变化"）；只做第 2 件的话，系统会把它拽回来。
     *
     * ⚠️ `FLAG_LAYOUT_NO_LIMITS` 这个标志**以前被故意去掉过**，
     * 原因是当时它有两个副作用（详见 `OverlayService` 里那段注释）:
     * 底部能多拖一点、切换「可触摸」时窗口会跳一下。
     * 现在它是**开关控制的**，所以关掉时行为与以前**完全一致** ——
     * 那两条副作用只在用户主动打开时才可能出现，而那时他本来就要"不受限"。
     */
    val movableOffScreen: Boolean = false,
    val baseX: Int = POSITION_UNSET,
    val baseY: Int = POSITION_UNSET,
    val offsetX: Int = 0,
    val offsetY: Int = 0,
) {
    /** 最终位置 = 基础坐标 + 偏移（拖动只改前者，滑块只改后者） */
    fun positionX(defaultX: Int): Int = (if (baseX == POSITION_UNSET) defaultX else baseX) + offsetX

    fun positionY(defaultY: Int): Int = (if (baseY == POSITION_UNSET) defaultY else baseY) + offsetY

    companion object {
        /**
         * "还没被拖过"的标记。
         *
         * 用无效值而不是 0：0 是合法坐标（屏幕左上角），
         * 无法区分"用户拖到了左上角"和"还没设置过"。
         */
        const val POSITION_UNSET = -1
    }
}

/**
 * 全部悬浮窗的本机设置。
 *
 * ============================================================
 * 与"哪几个开着"分开存
 * ============================================================
 * 开关状态（[OverlayLayouts] 之外的 `AppPrefs.overlayEnabledIds`）改动频繁、
 * 而且启动时**必须单独读出来**决定要不要恢复采集；把两者塞进同一个 JSON，
 * 每次点开关都要重写整份文件，也会让"启动恢复"这条路径依赖无关的数据。
 */
object OverlayLayouts {

    private const val TAG = "Overlay"

    /** 读一份设置；没有记录时给默认值（可触摸、位置未设置、无偏移） */
    fun of(context: Context, configId: String): OverlayLayout =
        read(context)[configId] ?: OverlayLayout()

    fun all(context: Context): Map<String, OverlayLayout> = read(context)

    /**
     * 改一份设置。
     *
     * [transform] 拿到当前值（没有记录时是默认值），返回要写回的值。
     */
    fun update(
        context: Context,
        configId: String,
        transform: (OverlayLayout) -> OverlayLayout,
    ) {
        val layouts = read(context).toMutableMap()
        layouts[configId] = transform(layouts[configId] ?: OverlayLayout())
        write(context, layouts)
    }

    /** 重置这个窗口的位置与偏移；可触摸状态不动（那是用户的独立选择） */
    fun resetPosition(context: Context, configId: String) {
        update(context, configId) { layout ->
            layout.copy(
                baseX = OverlayLayout.POSITION_UNSET,
                baseY = OverlayLayout.POSITION_UNSET,
                offsetX = 0,
                offsetY = 0,
            )
        }
        AppLog.i(TAG, "已重置悬浮窗位置与偏移：$configId")
    }

    /** 配置被删除后清掉它的记录，避免本地越攒越多 */
    fun forget(context: Context, configId: String) {
        val layouts = read(context).toMutableMap()
        if (layouts.remove(configId) != null) write(context, layouts)
    }

    /*
     * ============================================================
     * 哪几个悬浮窗开着
     * ============================================================
     * 与布局分开存（原因见 AppPrefs.overlayEnabledIdsJson），
     * 但读写它的逻辑放在这里：两者同属"本机悬浮窗状态"，
     * 拆到两个文件只会让调用方要同时认识两个对象。
     */

    /** 当前开着悬浮窗的配置 id；启动时用它决定要不要恢复采集 */
    fun enabledIds(context: Context): Set<String> {
        val raw = AppPrefs.get(context).overlayEnabledIdsJson ?: return emptySet()
        return try {
            val array = JSONArray(raw)
            buildSet {
                for (index in 0 until array.length()) {
                    val id = array.optString(index)
                    if (id.isNotEmpty()) add(id)
                }
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "悬浮窗开关列表解析失败，按全关处理", e)
            emptySet()
        }
    }

    fun setEnabledIds(context: Context, ids: Set<String>): Set<String> {
        val array = JSONArray()
        ids.forEach { array.put(it) }
        AppPrefs.get(context).overlayEnabledIdsJson = array.toString()
        return ids
    }

    /** 开关某一个配置的悬浮窗，返回改完之后"还开着"的集合 */
    fun setEnabled(context: Context, configId: String, enabled: Boolean): Set<String> {
        val current = enabledIds(context).toMutableSet()
        if (enabled) current.add(configId) else current.remove(configId)
        setEnabledIds(context, current)
        AppLog.i(TAG, "悬浮窗开关：$configId → $enabled（当前共 ${current.size} 个）")
        return current
    }

    /**
     * 把旧的"全局单份设置"迁移到**当前生效的那份配置**上。
     *
     * 旧版本只有一个悬浮窗：`overlayEnabled` 一个布尔 + `overlayX/overlayY` 一对坐标。
     * 现在的模型是"每个配置一份"，所以要把那对值归到用户当时正在用的配置上。
     *
     * ⚠️ "当前配置"这个概念已经删除，所以这里读的 `prefs.activeConfigId`
     * 是一个**只读一次的墓碑**（见 AppPrefs 里的说明）：
     * 它是唯一还记得"当时是谁"的地方，读完立刻清掉，不留任何长期概念。
     *
     * 只在**首次**运行（还没有布局记录）时做。没有记录却已经清掉墓碑也没关系 ——
     * 那说明迁移早就做完了。
     */
    fun migrateLegacy(context: Context) {
        val prefs = AppPrefs.get(context)
        if (prefs.overlayLayoutsJson != null) return

        val legacyX = prefs.overlayX
        val legacyY = prefs.overlayY
        val legacyEnabled = prefs.overlayEnabled
        // 只在这次迁移里读一次；之后这个键就没有任何读者了
        val legacyActiveId = prefs.activeConfigId

        if (legacyActiveId != null) {
            val migrated = OverlayLayout(
                touchable = true,
                baseX = legacyX,
                baseY = legacyY,
            )
            write(context, mapOf(legacyActiveId to migrated))
            if (legacyEnabled) {
                prefs.overlayEnabledIdsJson = JSONArray().put(legacyActiveId).toString()
            }
            AppLog.i(TAG, "已把旧的悬浮窗位置迁移到配置 $legacyActiveId")
        } else {
            // 还没选中过配置：写一份空记录，让下次不再走迁移
            write(context, emptyMap())
        }

        prefs.overlayEnabled = false
        prefs.overlayX = OverlayLayout.POSITION_UNSET
        prefs.overlayY = OverlayLayout.POSITION_UNSET
        prefs.clearLegacyActiveConfigId()
    }

    /**
     * 丢弃已经不存在的配置留下的记录。
     *
     * 删除配置时必须调用，否则本机会越攒越多"孤儿记录"：
     * 它们的 id 再也匹配不到任何配置，却会一直跟着备份/迁移走。
     * 顺带把开关集合里失效的 id 也去掉 —— 否则"全关才停监听"的判定
     * 会被一个永远开不起来的 id 永久占住。
     */
    fun pruneMissing(context: Context, existingIds: Set<String>) {
        val layouts = read(context)
        val keptLayouts = layouts.filterKeys { it in existingIds }
        if (keptLayouts.size != layouts.size) {
            write(context, keptLayouts)
            AppLog.i(TAG, "已清理 ${layouts.size - keptLayouts.size} 份失效的悬浮窗设置")
        }

        val enabled = enabledIds(context)
        val keptEnabled = enabled.filterTo(mutableSetOf()) { it in existingIds }
        if (keptEnabled.size != enabled.size) {
            setEnabledIds(context, keptEnabled)
            AppLog.i(TAG, "已清理 ${enabled.size - keptEnabled.size} 个失效的悬浮窗开关")
        }
    }

    /*
     * ============================================================
     * 读写
     * ============================================================
     */

    private fun read(context: Context): Map<String, OverlayLayout> {
        val raw = AppPrefs.get(context).overlayLayoutsJson ?: return emptyMap()
        return try {
            val json = JSONObject(raw)
            buildMap {
                json.keys().forEach { id ->
                    val item = json.optJSONObject(id) ?: return@forEach
                    put(
                        id,
                        OverlayLayout(
                            touchable = item.optBoolean("touchable", true),
                            movableOffScreen = item.optBoolean("movableOffScreen", false),
                            baseX = item.optInt("baseX", OverlayLayout.POSITION_UNSET),
                            baseY = item.optInt("baseY", OverlayLayout.POSITION_UNSET),
                            offsetX = item.optInt("offsetX", 0),
                            offsetY = item.optInt("offsetY", 0),
                        ),
                    )
                }
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "悬浮窗设置解析失败，按默认处理", e)
            emptyMap()
        }
    }

    private fun write(context: Context, layouts: Map<String, OverlayLayout>) {
        val json = JSONObject()
        layouts.forEach { (id, layout) ->
            json.put(
                id,
                JSONObject().apply {
                    put("touchable", layout.touchable)
                    put("movableOffScreen", layout.movableOffScreen)
                    put("baseX", layout.baseX)
                    put("baseY", layout.baseY)
                    put("offsetX", layout.offsetX)
                    put("offsetY", layout.offsetY)
                },
            )
        }
        AppPrefs.get(context).overlayLayoutsJson = json.toString()
    }
}

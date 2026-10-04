package com.something.sthkey.data.config

import android.content.Context
import com.something.sthkey.core.log.AppLog
import com.something.sthkey.core.prefs.AppPrefs
import com.something.sthkey.domain.config.DEFAULT_CONFIG_ID
import com.something.sthkey.domain.config.KeyStrokesConfig
import com.something.sthkey.domain.config.defaultConfig
import com.something.sthkey.domain.config.duplicate
import com.something.sthkey.domain.config.resetParamsToDefault
import com.something.sthkey.domain.config.gamepad2KeyMappings
import com.something.sthkey.domain.config.defaultKeyMappings
import com.something.sthkey.domain.style.StyleId
import com.something.sthkey.domain.style.OverlayStyleRegistry
import java.util.UUID

/**
 * 配置仓库。
 *
 * ============================================================
 * 当前状态（第一步：UI 骨架）
 * ============================================================
 * 配置只保存在内存里，进程重启后回到内置默认配置。
 *
 * 这是刻意的：配置最终的存储形态是 **zip 压缩包**（见 domain/config/README.md），
 * 现在随便写一套 JSON 落盘，等 zip 实现时既要删旧代码又要写迁移，
 * 属于自己给自己挖坑。因此这里把"读/写"收敛成两个方法（[load] / [persist]），
 * zip 实现落地时只替换这两个方法体，UI 与调用方完全不动。
 *
 * ============================================================
 * 兼容性设计
 * ============================================================
 * 旧项目（KeyStrokes）写进 SharedPreferences 的配置里带有已废弃的
 * Xbox / 触屏字段。本项目**不读取**旧配置，因此不存在解析崩溃的风险；
 * 用户在旧版的配置不会自动迁移，需要重新建（后续 zip 导入功能补齐后可手动导入）。
 */
class ConfigStore private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = AppPrefs.get(appContext)

    /** 内存中的配置列表，"默认"永远是第 0 项 */
    private val configs = mutableListOf<KeyStrokesConfig>()

    init {
        load()
    }

    /** 全部配置（按列表顺序） */
    fun all(): List<KeyStrokesConfig> = configs.toList()

    /** 按 id 取一份配置，不存在返回 null */
    fun find(id: String): KeyStrokesConfig? = configs.firstOrNull { it.id == id }

    /**
     * 第一份配置；列表为空时返回内置默认配置（不会返回 null）。
     *
     * ============================================================
     * 为什么是"第一份"而不是"当前生效的那一份"
     * ============================================================
     * 这个仓库原本还有一个 `activeId`（哪一份正在被使用），多悬浮窗之后
     * **这个概念被删掉了**：每一份开着的配置都有自己的窗口，
     * "当前用的是哪一份"不再对应任何真实状态。
     *
     * 剩下几个地方仍然需要"随便给我一份确定的配置"作兜底
     * （编辑页 id 失效、CPS 统计没有窗口开着时的键位映射）。
     * 那就是列表第一项 —— 内置 Default 一定在第 0 位（见 [load]），
     * 所以这个兜底永远存在、且每次启动都一样。
     */
    fun first(): KeyStrokesConfig = configs.firstOrNull() ?: defaultConfig()

    /**
     * 调整配置顺序（列表页拖动排序用）。
     *
     * 采用"从 from 取出、插到 to"的语义，越界自动收敛，
     * 避免调用方自己算索引时写出 IndexOutOfBounds。
     *
     * 内置 Default **固定在第一位**：它是 [first] 的兜底目标，
     * 也是"数据坏了还能恢复"的锚点，被拖到中间之后这个兜底就不好找了。
     * 这里直接把与 Default 相关的移动请求挡掉（当前 UI 没做拖动排序，
     * 但接口还在，留着比以后出问题再补便宜）。
     */
    fun move(from: Int, to: Int) {
        if (from == to) return
        if (from !in configs.indices) return
        val target = to.coerceIn(0, configs.lastIndex)
        if (target == from) return

        if (configs[from].builtIn || configs[target].builtIn) {
            AppLog.w(TAG, "内置配置的位置不可调整：${configs[from].name}")
            return
        }

        val moved = configs.removeAt(from)
        configs.add(target, moved)
        persist()
        AppLog.i(TAG, "已调整配置顺序：${moved.name} → 第 ${target + 1} 位")
    }

    /*
     * ============================================================
     * ⚠️ 这里原本还有 setActive / active()，已经删除，不要加回来
     * ============================================================
     * 旧版本用"当前生效的配置"决定屏幕上显示哪一份。多悬浮窗之后，
     * 显示什么由**每一份配置各自的开关**决定，可以有多个同时开着 ——
     * "当前配置"不再对应任何真实状态，留着只会让调用方疑惑
     * "我改了 activeId，为什么屏幕上没变"。
     *
     * 界面上原来的"点卡片设为当前配置"也已一并去掉，
     * 配置列表页现在只做增删改。
     * ============================================================
     */

    /** 覆盖保存一份配置（按 id 匹配），随后落盘 */
    fun upsert(config: KeyStrokesConfig) {
        val index = configs.indexOfFirst { it.id == config.id }
        if (index >= 0) {
            configs[index] = config
        } else {
            configs.add(config)
        }
        persist()
    }

    /**
     * 按 id 修改某一份配置。
     *
     * 为什么需要它（曾经的 bug）：编辑页之前一律走 [updateActive]，
     * 于是"编辑配置 B"实际改的是"当前生效的配置 A" ——
     * 表现就是"改一个配置，所有配置都变了"。
     * 编辑页必须按自己拿到的 id 改，而不是按"当前生效"改。
     */
    fun updateById(id: String, transform: (KeyStrokesConfig) -> KeyStrokesConfig): KeyStrokesConfig? {
        val index = configs.indexOfFirst { it.id == id }
        if (index < 0) {
            AppLog.w(TAG, "修改配置失败：找不到 id=$id")
            return null
        }
        val updated = transform(configs[index])
        configs[index] = updated
        persist()
        return updated
    }

    /**
     * 新建一份空配置。
     *
     * 与"复制"的区别（这也是旧版的行为）：
     * - 新建：参数回到出厂默认，只带上用户选的样式 —— 全新开始；
     * - 复制：完整继承来源配置的全部参数 —— 在老配置基础上改。
     *
     * @param styleId 样式标识；必须在注册表里且已实现，否则回落到默认样式
     * @return 新建的配置（已加入列表末尾，不会自动开启悬浮窗）
     */
    fun create(
        name: String,
        description: String,
        styleId: String,
    ): KeyStrokesConfig {
        val resolvedStyle = OverlayStyleRegistry.resolveOrDefault(styleId).id

        /*
         * ⚠️ 键位映射要按样式给**不同的默认值**
         * ============================================================
         * 「手柄（标准）」（gamepad2）的槽位显示的是键鼠的名字，
         * 但实际该绑**手柄**的键（基岩版:RT=攻击、LT=挖掘、A=跳跃）。
         *
         * 用键盘那套默认值（`BTN_LEFT` / `BTN_RIGHT` / `KEY_SPACE`）的话，
         * 用户拿手柄按半天**一个键都不会亮** —— 而他会以为是监听坏了。
         *
         * ⚠️ 必须**在创建时**换，不能只改 `defaultKeyMappings()` ——
         * 那个是键盘样式的默认值，改了会连累键盘用户。
         */
        val mappings = if (resolvedStyle == StyleId.GAMEPAD2) {
            gamepad2KeyMappings()
        } else {
            defaultKeyMappings()
        }

        val created = defaultConfig().copy(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { "新配置" },
            description = description.trim(),
            builtIn = false,
            styleId = resolvedStyle,
            keyMappings = mappings,
        )
        configs.add(created)
        persist()
        AppLog.i(TAG, "已新建配置：${created.name}（样式 ${created.style.label}）")
        return created
    }

    /**
     * 复制一份配置并返回新配置。
     *
     * 名称按"副本 / 副本 2 / 副本 3"递增，避免出现难以分辨的
     * "Default 副本 副本 副本"。
     *
     * @param sourceId 来源配置；不存在时以第一份配置为来源
     */
    fun duplicate(sourceId: String? = null, newName: String? = null): KeyStrokesConfig {
        val source = configs.firstOrNull { it.id == sourceId } ?: first()
        val created = source.duplicate(
            newId = UUID.randomUUID().toString(),
            newName = newName?.takeIf { it.isNotBlank() } ?: nextCopyName(source.name),
        )
        configs.add(created)
        persist()
        AppLog.i(TAG, "已复制配置：${source.name} → ${created.name}")
        return created
    }

    /**
     * 重命名配置。
     *
     * 内置配置的名称**不允许改**：它是配置数据的兜底项，
     * 名字被改乱后很难让用户认出"哪个是能恢复的那一份"。
     */
    fun rename(id: String, newName: String): Boolean {
        val name = newName.trim()
        if (name.isEmpty()) return false

        val index = configs.indexOfFirst { it.id == id }
        if (index < 0) return false
        if (configs[index].builtIn) {
            AppLog.w(TAG, "内置配置不可重命名：${configs[index].name}")
            return false
        }

        val old = configs[index].name
        configs[index] = configs[index].copy(name = name)
        persist()
        AppLog.i(TAG, "已重命名配置：$old → $name")
        return true
    }

    /** 生成不重复的副本名称 */
    private fun nextCopyName(sourceName: String): String {
        val existing = configs.map { it.name }.toSet()
        val first = "$sourceName 副本"
        if (first !in existing) return first

        var index = 2
        while ("$first $index" in existing) index++
        return "$first $index"
    }

    /** 删除配置；内置配置不可删除 */
    fun delete(id: String): Boolean {
        val target = configs.firstOrNull { it.id == id } ?: return false
        if (target.builtIn) {
            AppLog.w(TAG, "内置配置不可删除：${target.name}")
            return false
        }
        configs.remove(target)
        persist()
        AppLog.i(TAG, "已删除配置：${target.name}")
        return true
    }

    /**
     * 把某份配置恢复为内置默认参数（保留 id 与名称）。
     *
     * 内置配置的名称也一并恢复为 `Default`：它本来就是"出厂状态"的锚点，
     * 允许它顶着被改过的名字没有意义。
     */
    fun resetToDefault(id: String): KeyStrokesConfig? {
        val target = configs.firstOrNull { it.id == id } ?: return null
        /*
         * 重置逻辑只有**领域层那一个实现**（resetParamsToDefault）。
         *
         * 这里原本内联了一整份 copy(...)，与配置编辑页里那份重复；两份漂移的后果是
         * "重置参数会把 Live2D 配置变回按键样式"。现在这里只是"算出来 + 落库"。
         */
        val reset = target.resetParamsToDefault()
        upsert(reset)
        AppLog.i(TAG, "已重置配置：${target.name}")
        return reset
    }

    /*
     * ============================================================
     * 读写
     * ============================================================
     */

    /**
     * 生成不冲突的配置名称：重名时追加 (2)、(3)…
     *
     * 导入配置包时用。与字体的命名规则保持一致，
     * 这样用户看到的是同一套行为。
     */
    fun nextAvailableName(base: String): String {
        val name = base.trim().ifBlank { "导入的配置" }
        val existing = configs.map { it.name }.toSet()
        if (name !in existing) return name

        var index = 2
        while ("$name ($index)" in existing) index++
        return "$name ($index)"
    }

    /**
     * 读取配置。
     *
     * 规则：
     * 1. 读不到（首次安装）或内容损坏 → 用内置 Default 起步；
     * 2. 无论如何都要保证列表里**有且只有一个** Default 配置 ——
     *    它是"数据坏了还能恢复"的兜底项，不能因为用户删光了就消失；
     * 3. Default 一定在**第 0 位**：它是 [first] 的兜底目标，
     *    顺序被用户拖动过（[move]）之后也要把它按回最前面。
     */
    private fun load() {
        configs.clear()

        val stored = JsonConfigCodec.decodeList(prefs.configsJson)
        configs.addAll(stored)

        // 保证 Default 存在、是内置项、且在第 0 位
        val defaultIndex = configs.indexOfFirst { it.id == DEFAULT_CONFIG_ID }
        if (defaultIndex < 0) {
            configs.add(0, defaultConfig())
        } else {
            val default = configs.removeAt(defaultIndex).copy(
                id = DEFAULT_CONFIG_ID,
                name = defaultConfig().name,
                builtIn = true,
            )
            configs.add(0, default)
        }

        AppLog.i(TAG, "配置已加载：${configs.size} 项，首项为 ${first().name}")

        // 首次启动（或修复过 Default）时先落一次盘，保证磁盘上始终有完整数据
        if (stored.isEmpty()) {
            persist()
        }
    }

    /**
     * 落盘。
     *
     * 每次增删改都会调用：配置项只有个位数、JSON 也就几 KB，
     * 直接全量写入最简单也最不容易出错（增量更新容易漏字段）。
     * 用 apply() 异步写，不阻塞 UI 线程。
     */
    private fun persist() {
        prefs.configsJson = JsonConfigCodec.encodeList(configs)
    }

    companion object {
        private const val TAG = "ConfigStore"

        @Volatile
        private var instance: ConfigStore? = null

        /**
         * 获取单例。
         *
         * 配置在 UI 多处读写（配置页、主页预览、以后的悬浮窗服务），
         * 统一到这里取，避免各自 new 出多份内存态互相覆盖。
         */
        fun get(context: Context): ConfigStore =
            instance ?: synchronized(this) {
                instance ?: ConfigStore(context).also { instance = it }
            }
    }
}

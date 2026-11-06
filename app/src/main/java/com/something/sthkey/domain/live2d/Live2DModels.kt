package com.something.sthkey.domain.live2d

import android.content.Context
import android.net.Uri
import com.something.sthkey.core.log.AppLog
import com.something.sthkey.core.prefs.AppPrefs
import com.something.sthkey.domain.config.DEFAULT_LIVE2D_MODEL_ID
import com.something.sthkey.domain.config.LIVE2D_MODEL_KEYBOARD
import com.something.sthkey.domain.config.LIVE2D_MODEL_STANDARD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Live2D 模型来源。
 *
 * 与字体库（`FontRegistry`）是同一套思路：内置的不能删，导入的可以改名/删除。
 */
enum class Live2DModelKind(val label: String) {
    /** 随应用打包的模型（assets/bongocat 下那两页），不可删除 */
    BUILTIN("内置模型"),

    /** 用户导入的模型，可改名、可删除 */
    IMPORTED("导入的模型"),
}

/**
 * 一个模型条目。
 *
 * @param id 写进配置的引用标识，因此**不可变**：
 *   - 内置 `builtin:keyboard` / `builtin:standard`
 *   - 导入 `imported:<uuid>`
 * @param pageUrl 真正加载的页面地址（assets 的 `file:///android_asset/...`
 *   或导入模型目录里的 `index.html`）
 * @param hasArrowSide 这个模型有没有"右侧键帽"。
 *   方向键属于右侧；但鼠标版模型（standard）的资源分组里根本没有右侧键，
 *   方向键在那个模型下会被 JS 忽略 —— 参 [Live2DSemantics.side]。
 * @param layout 导入模型的布局：`full` = 自带 BongoCat 键帽资源包，
 *   `cat` = 只有角色（用内置键盘资源兜底）。内置模型为 `native`。
 * @param supportedKeys 这个模型**真实提供**键帽的键名集合。
 *   **空集 = 不过滤**（内置模型、以及只有角色的模型都不过滤）。
 *   非空时，只有这些键会被转发给渲染器 —— 皮肤包只画了 5 个键，
 *   就不该让另外几十个键也把猫的手按下去。
 * @param dir 导入模型所在的目录；内置模型为 null
 * @param digest 内容摘要（见导入器）；内置模型为空
 */
data class Live2DModelEntry(
    val id: String,
    val displayName: String,
    val kind: Live2DModelKind,
    val subtitle: String,
    val pageUrl: String,
    val hasArrowSide: Boolean,
    val layout: String,
    val supportedKeys: Set<String> = emptySet(),
    val dir: File? = null,
    val digest: String = "",
    /**
     * 多模式变体（标准 / 键盘 / 手柄 …）。
     *
     * ⚠️ 只有一个变体的模型这里是**空列表**（绝大多数模型），
     * 那种情况下 [pageUrl] 就是目录根上的 `index.html`，与旧行为完全一致。
     */
    val variants: List<Live2DVariant> = emptyList(),
    /** 当前选中的变体（[Live2DVariant.pageDir]）；空串 = 根目录 */
    val variant: String = "",
    /**
     * 模型**实际内容**的宽高比（宽 / 高）。
     *
     * ⚠️ 为什么需要它:
     * 悬浮窗是固定设计尺寸 612×354，而导入模型的实际内容往往是竖构图，
     * 于是被塞进那块区域后**左、右、下各留一大截空白**（用户报的"多一截空气"）。
     *
     * 值由页面在加载完成后实测回报（`contentBounds`），存在模型记录里，
     * 之后算窗口尺寸时按它来，窗口就与内容比例一致、四周没有多余空白。
     *
     * `0f` = 还没测到（按 612×354 兜底）。
     */
    val contentAspect: Float = 0f,
)

/**
 * 模型包里的**一套模式**。
 *
 * 实测样本 `鸣潮卡提希娅最牛逼键盘猫模型` 同时带 `keyboard` / `standard` / `gamepad`
 * 三套，每套的键帽资源、moc 都不同，所以必须分开建页面。
 *
 * @param name 给人看的模式名（`键盘` / `标准` / `手柄` / 作者原始目录名）
 * @param pageDir 相对模型目录的页面所在目录（**空串 = 目录根**）
 */
data class Live2DVariant(
    val name: String,
    val pageDir: String,
)

/**
 * 全局 Live2D 模型库。
 *
 * ============================================================
 * 与字体库对齐的设计
 * ============================================================
 * 1. **全局而非按配置**：导入一次，所有配置都能选；配置里只存 [Live2DModelEntry.id]。
 * 2. **内置模型不进库**：它们在 assets 里，不需要复制、不需要管理，
 *    列出来只为让用户能选中它们。
 * 3. **模型文件落在应用私有目录**：导入时复制一份，之后即使原 zip 被删也能用。
 *
 * 与字体的差别只有一处：字体是一个文件，模型是一个**目录**
 * （`moc3` + 贴图 + 生成出来的 `index.html` / `modeldata.js`）。
 */
object Live2DModels {

    private const val TAG = "Live2D"

    /**
     * 默认模型：键盘猫键盘版，与 assets 里的内置模型对应。
     *
     * 引用配置层的常量而不是自己再写一份字符串：两处各写一份的话，
     * 改了其中一处就会出现"配置里存的 id 在这个库里找不到"，
     * 而那种问题在运行时只表现为"模型莫名回落到默认"，很难查。
     */
    const val DEFAULT_ID = DEFAULT_LIVE2D_MODEL_ID
    const val KEYBOARD = LIVE2D_MODEL_KEYBOARD
    const val STANDARD = LIVE2D_MODEL_STANDARD

    /** 导入模型 id 的前缀；公开是因为导出时要判断"这个模型需要进包吗" */
    const val IMPORTED_PREFIX = "imported:"

    /** 内置模型的页面（不能改路径：runtime.js 里有写死的资源回退地址） */
    private const val PAGE_KEYBOARD = "file:///android_asset/bongocat/keyboard/index.html"
    private const val PAGE_STANDARD = "file:///android_asset/bongocat/standard/index.html"

    /** 导入模型存放目录（应用私有） */
    private const val IMPORT_DIR = "live2d_models"

    /** 导入模型目录里由我们生成的入口文件 */
    const val ENTRY_INDEX = "index.html"
    const val ENTRY_MODEL_DATA = "modeldata.js"

    /** 布局标记，与 runtime.js 里的 `__BONGO_MODEL_LAYOUT` 对应 */
    const val LAYOUT_NATIVE = "native"
    const val LAYOUT_FULL = "full"
    const val LAYOUT_CAT = "cat"

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /*
     * ============================================================
     * 查询
     * ============================================================
     */

    /** 全部模型：内置两个 → 导入的 */
    fun all(): List<Live2DModelEntry> = builtinModels() + importedModels()

    /**
     * 内置模型。
     *
     * ⚠️ `builtin:standard` 的 `hasArrowSide = false`：
     * 它是鼠标版模型，资源里没有右侧键帽，方向键在那个页面下会被 JS 忽略。
     */
    fun builtinModels(): List<Live2DModelEntry> = listOf(
        Live2DModelEntry(
            id = KEYBOARD,
            displayName = "BongoCat 键盘版",
            kind = Live2DModelKind.BUILTIN,
            subtitle = "内置 · 右侧显示方向键",
            pageUrl = PAGE_KEYBOARD,
            hasArrowSide = true,
            layout = LAYOUT_NATIVE,
        ),
        Live2DModelEntry(
            id = STANDARD,
            displayName = "BongoCat 鼠标版",
            kind = Live2DModelKind.BUILTIN,
            subtitle = "内置 · 右侧显示鼠标",
            pageUrl = PAGE_STANDARD,
            hasArrowSide = false,
            layout = LAYOUT_NATIVE,
        ),
    )

    fun importedModels(): List<Live2DModelEntry> = readImported().map { record ->
        val dir = File(importDir(), record.id)
        Live2DModelEntry(
            id = IMPORTED_PREFIX + record.id,
            displayName = record.displayName,
            kind = Live2DModelKind.IMPORTED,
            subtitle = "${record.layoutLabel} · ${directorySizeLabel(dir)}",
            /*
             * ⚠️ 多模式模型:页面在各变体自己的目录里（`<variant>/index.html`），
             * 因为那里的相对路径（`cat_model/…`、`bg.png`、`left-keys/`）才是对的。
             * 单模式模型 `variant` 是空串，就是根目录的 `index.html`（旧行为）。
             */
            pageUrl = Uri.fromFile(
                if (record.variant.isEmpty()) File(dir, ENTRY_INDEX)
                else File(File(dir, record.variant), ENTRY_INDEX),
            ).toString(),
            // 导入模型走的是键盘版 runtime（贴图分组里有方向键），所以有右侧
            hasArrowSide = true,
            layout = record.layout,
            supportedKeys = record.supportedKeys,
            dir = dir,
            digest = record.digest,
            variants = record.variants,
            variant = record.variant,
            contentAspect = record.contentAspect,
        )
    }

    /**
     * 解析一条模型；找不到时回落到内置默认模型。
     *
     * 给渲染方用：模型被删掉之后，悬浮窗应该还能显示默认那只猫，
     * 而不是变成一个空白窗口。
     */
    fun resolve(id: String): Live2DModelEntry =
        find(id) ?: builtinModels().first { it.id == DEFAULT_ID }

    fun find(id: String): Live2DModelEntry? = all().firstOrNull { it.id == id }

    /** 取显示名（配置页展示用）；找不到时给出兜底文案 */
    fun displayNameOf(id: String): String =
        find(id)?.displayName ?: "已移除（回落到内置模型）"

    /**
     * 解析出要加载的页面地址。
     *
     * 找不到（模型被删了）时**回落到内置键盘版**，而不是返回 null：
     * 用户删掉正在用的模型后，悬浮窗应该还能显示点东西，而不是变成空白窗口。
     */
    fun pageUrlOf(id: String): String = find(id)?.pageUrl ?: PAGE_KEYBOARD

    /** 该模型有没有右侧键帽（方向键） */
    fun hasArrowSide(id: String): Boolean = find(id)?.hasArrowSide ?: true

    /** 按内容摘要找已有模型（导入时用来复用，避免同一模型占两份空间） */
    fun findByDigest(digest: String): Live2DModelEntry? =
        importedModels().firstOrNull { it.digest == digest && it.digest.isNotEmpty() }

    /*
     * ============================================================
     * 增删改
     * ============================================================
     */

    /**
     * 登记一个刚导入的模型。
     *
     * @param dirId 目录名（导入器生成的 uuid），与 [Live2DModelEntry.id] 不同：
     *   id 会写进配置，目录名只在本地用
     */
    fun registerImported(
        dirId: String,
        displayName: String,
        digest: String,
        layout: String,
        supportedKeys: Set<String> = emptySet(),
        variants: List<Live2DVariant> = emptyList(),
    ): Live2DModelEntry {
        val records = readImported().filterNot { it.id == dirId }.toMutableList()
        records += Record(
            id = dirId,
            displayName = nextAvailableName(displayName),
            digest = digest,
            layout = layout,
            supportedKeys = supportedKeys,
            variants = variants,
            /* ⚠️ 默认选第一个:导入时排序规则已经把 keyboard 排在最前 */
            variant = variants.firstOrNull()?.pageDir.orEmpty(),
        )
        writeImported(records)
        AppLog.i(
            TAG,
            "已登记模型「$displayName」（$dirId，布局 $layout，" +
                "支持的键 ${supportedKeys.size} 个）",
        )
        return importedModels().first { it.id == IMPORTED_PREFIX + dirId }
    }

    /**
     * 切换多模式模型的**当前模式**。
     *
     * ⚠️ 只改"这个模型用哪一套"，**不改配置**:
     * 多模式是**模型**的属性（用户说的"库里展开选模式"），不是配置的属性。
     * 导出时整个模型（所有模式）都会被打包带走 —— 见 `ConfigPackageManager`。
     *
     * @return 是否真的改了
     */
    fun selectVariant(id: String, pageDir: String): Boolean {
        val raw = id.removePrefix(IMPORTED_PREFIX)
        val records = readImported()
        val index = records.indexOfFirst { it.id == raw }
        if (index < 0) return false

        val record = records[index]
        if (record.variants.none { it.pageDir == pageDir }) {
            AppLog.w(TAG, "变体不存在:$raw / $pageDir")
            return false
        }
        if (record.variant == pageDir) return false

        records[index] = record.copy(variant = pageDir)
        writeImported(records)
        AppLog.i(TAG, "模型已切换模式:$raw → ${pageDir.ifEmpty { "根目录" }}")
        return true
    }

    /**
     * 记下模型**实际内容**的宽高比（由页面实测回报）。
     *
     * ⚠️ 只在"还没记过"或"变化明显"时才写盘，避免每次开悬浮窗都写一次偏好。
     * @return 是否真的更新了（调用方据此决定要不要重算窗口尺寸）
     */
    fun updateContentAspect(id: String, aspect: Float): Boolean {
        if (!aspect.isFinite() || aspect <= 0f) return false
        val raw = id.removePrefix(IMPORTED_PREFIX)
        val records = readImported()
        val index = records.indexOfFirst { it.id == raw }
        if (index < 0) return false

        val previous = records[index].contentAspect
        /* 相差 1% 以内就不动 —— 亚像素级别的抖动不该触发重排窗口 */
        if (previous > 0f && kotlin.math.abs(previous - aspect) / previous < 0.01f) return false

        records[index] = records[index].copy(contentAspect = aspect)
        writeImported(records)
        AppLog.i(TAG, "模型内容比例已记录:$raw → %.3f".format(aspect))
        return true
    }

    /**
     * 补记某个模型支持的按键（供启动时的修复流程使用）。
     *
     * 存在的意义：这个字段是后加的，用户之前导入的模型没有它 ——
     * 不补的话，那些模型会一直"所有键都响应"，用户会以为改动没生效。
     */
    fun updateSupportedKeys(id: String, keys: Set<String>) {
        if (keys.isEmpty()) return
        val raw = id.removePrefix(IMPORTED_PREFIX)
        val records = readImported()
        val index = records.indexOfFirst { it.id == raw }
        if (index < 0) return

        val merged = records[index].supportedKeys + keys
        if (merged == records[index].supportedKeys) return

        records[index] = records[index].copy(supportedKeys = merged)
        writeImported(records)
        AppLog.i(TAG, "已补记模型支持的按键：$raw → ${merged.size} 个")
    }

    fun rename(id: String, newName: String): Boolean {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return false
        val raw = id.removePrefix(IMPORTED_PREFIX)
        val records = readImported()
        val index = records.indexOfFirst { it.id == raw }
        if (index < 0) return false

        records[index] = records[index].copy(displayName = trimmed)
        writeImported(records)
        AppLog.i(TAG, "模型已改名：$raw → $trimmed")
        return true
    }

    /** 删除导入的模型；内置模型一律拒绝 */
    fun delete(id: String): Boolean {
        if (!id.startsWith(IMPORTED_PREFIX)) return false
        val raw = id.removePrefix(IMPORTED_PREFIX)
        val records = readImported()
        if (records.none { it.id == raw }) return false

        File(importDir(), raw).deleteRecursively()
        writeImported(records.filterNot { it.id == raw })
        AppLog.i(TAG, "已删除模型：$raw")
        return true
    }

    /**
     * 清掉"记录还在、文件没了"的失效条目。
     *
     * 用户清应用数据、或手工删目录之后会出现这种状态；
     * 留着的话模型列表里会有一项选了没反应。
     *
     * ============================================================
     * ⚠️⚠️ 多模式模型的页面**不在根目录**
     * ============================================================
     * 多模式（标准 / 键盘 / 手柄）的页面写在各自的变体目录里
     * （`<dirId>/<pageDir>/index.html`），根目录根本没有 `index.html`。
     *
     * 第一版这里只查根目录 —— 结果是**多模式模型一重启就被当成失效记录删掉**，
     * 用户报的「导入的模型不持久化了，杀进程重进就没」就是它。
     *
     * ⚠️ 所以判定要按**选定变体的页面**来查；没有变体信息的老记录仍然查根目录。
     */
    fun pruneMissing() {
        val records = readImported()
        val alive = records.filter { record -> pageFileOf(record).isFile }
        if (alive.size != records.size) {
            writeImported(alive)
            AppLog.w(TAG, "清理了 ${records.size - alive.size} 个失效模型记录")
        }
    }

    /** 这条记录**当前选中变体**的页面文件 */
    private fun pageFileOf(record: Record): File {
        val dir = File(importDir(), record.id)
        /*
         * 选中变体为空（老记录 / 单模式）→ 根目录页面；
         * 否则看变体目录。多模式的记录一定带 `variant`，但这里也兼容
         * "只有 variants 没有 variant" 的中间状态（取第一套）。
         */
        val pageDir = record.variant.ifEmpty { record.variants.firstOrNull()?.pageDir.orEmpty() }
        return if (pageDir.isEmpty()) File(dir, ENTRY_INDEX) else File(File(dir, pageDir), ENTRY_INDEX)
    }

    /**
     * 删掉"**没有记录、但文件还在**"的模型目录。
     *
     * 起因:`pruneMissing()` 修好之前，多模式模型的记录被误删过，
     * 而它们 100+ MB 的目录还留在磁盘上 —— 既占空间又永远不会出现在库里。
     *
     * ⚠️ 保守判据，两条都满足才删:
     * 1. 目录名是导入器生成的 32 位十六进制 id（别误删用户放进去的东西）；
     * 2. 目录里确实有模型内容（`index.html` 或任意 `*.model3.json`）。
     *
     * @return 清掉的目录数
     */
    fun pruneOrphanDirectories(): Int {
        val context = appContext ?: return 0
        val known = readImported().map { it.id }.toSet()
        val root = importRoot(context)
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return 0

        var removed = 0
        dirs.forEach { dir ->
            if (dir.name in known) return@forEach
            if (!dir.name.matches(Regex("[0-9a-f]{32}"))) return@forEach

            val looksLikeModel = File(dir, ENTRY_INDEX).isFile ||
                dir.walkTopDown().any { it.isFile && it.name.endsWith(".model3.json", ignoreCase = true) }
            if (!looksLikeModel) return@forEach

            if (runCatching { dir.deleteRecursively() }.getOrDefault(false)) {
                removed++
                AppLog.w(TAG, "清理了没有记录的模型目录：${dir.name}")
            }
        }
        return removed
    }

    /** 重名自动加序号（与配置、字体一致） */
    fun nextAvailableName(base: String): String {
        val name = base.trim().ifBlank { "导入的模型" }
        val existing = readImported().map { it.displayName }.toSet()
        if (name !in existing) return name
        var index = 2
        while ("$name ($index)" in existing) index++
        return "$name ($index)"
    }

    /*
     * ============================================================
     * 内部
     * ============================================================
     */

    /**
     * 导入模型的根目录（应用私有）。
     *
     * **公开**是刻意的：导入器（data 层）要往这里解压。
     * 目录名只能有这一处定义 —— 各写一份的话，某天改了一边就会出现
     * "导入进去了但列表里看不到"，查起来很费劲。
     */
    fun importRoot(context: Context): File = File(context.filesDir, IMPORT_DIR).apply { mkdirs() }

    /** 持久化记录；只有真正需要落盘的东西才存 */
    private data class Record(
        val id: String,
        val displayName: String,
        val digest: String,
        val layout: String,
        /** 真实提供键帽的键名；空集 = 不过滤 */
        val supportedKeys: Set<String> = emptySet(),
        /** 多模式变体；空列表 = 单模式（绝大多数模型） */
        val variants: List<Live2DVariant> = emptyList(),
        /** 当前选中的变体（空串 = 根目录页面） */
        val variant: String = "",
        /** 内容宽高比（宽/高）；0 = 还没测到。见 [Live2DModelEntry.contentAspect] */
        val contentAspect: Float = 0f,
    ) {
        val layoutLabel: String
            get() = when (layout) {
                LAYOUT_FULL -> "自带键帽资源"
                LAYOUT_CAT -> "仅角色"
                else -> "导入"
            }
    }

    /** 模型根目录；未初始化上下文时返回一个不存在的目录，调用方自然会当作"没有模型" */
    private fun importDir(): File {
        val context = appContext
        if (context == null) {
            AppLog.w(TAG, "模型库未初始化，导入模型不可用")
            return File("/dev/null/sthkey-live2d-models")
        }
        return importRoot(context)
    }

    private fun readImported(): MutableList<Record> {
        val context = appContext ?: return mutableListOf()
        val raw = AppPrefs.get(context).importedLive2DModelsJson ?: return mutableListOf()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    if (id.isBlank()) continue
                    add(
                        Record(
                            id = id,
                            displayName = item.optString("name").ifBlank { "导入的模型" },
                            digest = item.optString("digest"),
                            layout = item.optString("layout").ifBlank { LAYOUT_CAT },
                            variants = item.optJSONArray("variants")?.let { array ->
                                buildList {
                                    for (index in 0 until array.length()) {
                                        val v = array.optJSONObject(index) ?: continue
                                        val pageDir = v.optString("pageDir")
                                        add(
                                            Live2DVariant(
                                                name = v.optString("name").ifBlank { "模式" },
                                                pageDir = pageDir,
                                            ),
                                        )
                                    }
                                }
                            }.orEmpty(),
                            variant = item.optString("variant"),
                            contentAspect = item.optDouble("contentAspect", 0.0).toFloat(),
                            supportedKeys = item.optJSONArray("supportedKeys")
                                ?.let { array ->
                                    buildSet {
                                        for (index in 0 until array.length()) {
                                            val key = array.optString(index)
                                            if (key.isNotEmpty()) add(key)
                                        }
                                    }
                                }
                                .orEmpty(),
                        ),
                    )
                }
            }.toMutableList()
        } catch (e: Exception) {
            AppLog.e(TAG, "模型列表解析失败，按空处理", e)
            mutableListOf()
        }
    }

    private fun writeImported(records: List<Record>) {
        val context = appContext ?: return
        val array = JSONArray()
        records.forEach { record ->
            array.put(
                JSONObject().apply {
                    put("id", record.id)
                    put("name", record.displayName)
                    put("digest", record.digest)
                    put("layout", record.layout)
                    put(
                        "supportedKeys",
                        JSONArray().apply { record.supportedKeys.forEach { put(it) } },
                    )
                    if (record.variants.isNotEmpty()) {
                        put(
                            "variants",
                            JSONArray().apply {
                                record.variants.forEach { variant ->
                                    put(
                                        JSONObject().apply {
                                            put("name", variant.name)
                                            put("pageDir", variant.pageDir)
                                        },
                                    )
                                }
                            },
                        )
                        put("variant", record.variant)
                    }
                    if (record.contentAspect > 0f) {
                        put("contentAspect", record.contentAspect.toDouble())
                    }
                },
            )
        }
        AppPrefs.get(context).importedLive2DModelsJson = array.toString()
    }

    /** 目录体积，用于在列表里给用户一个"这模型多大"的概念 */
    private fun directorySizeLabel(dir: File): String {
        val bytes = runCatching {
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)
        return when {
            bytes <= 0L -> "大小未知"
            bytes < 1024L * 1024L -> "${bytes / 1024} KB"
            else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
        }
    }
}

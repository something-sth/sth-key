package com.something.sthkey.domain.font

import android.content.Context
import android.net.Uri
import com.something.sthkey.core.log.AppLog
import com.something.sthkey.core.prefs.AppPrefs
import com.something.sthkey.domain.config.DEFAULT_FONT_ID
import com.something.sthkey.domain.font.bitmap.BitmapFontStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 字体来源。
 *
 * 四者的差别在"能不能删/改名"和"从哪加载"，UI 上是同一个列表。
 */
enum class FontKind(val label: String) {
    /** 系统通用字体族（sans-serif / serif / monospace …），不可删除 */
    SYSTEM("系统字体"),

    /** 随应用打包的内置字体，不可删除 */
    BUILTIN("内置字体"),

    /** 用户导入的矢量字体（.ttf / .otf / .ttc），可删除、可重命名 */
    IMPORTED("导入的字体"),

    /**
     * 图片字体（Minecraft 风格的位图图集）。
     *
     * ⚠️ 它和上面三种**不是同一套渲染**：矢量字体交给 Compose 的 `Text`，
     * 而图片字体要自己逐字贴图（Compose 没有"替换字形"的机制）。
     * 但它们在**配置里是同一个 [FontEntry.id]**，所以配置层不需要区分。
     *
     * 单独列一类而不是混进 [IMPORTED]：界面上用户需要一眼看出
     * "这个字体是图片、只能显示 ASCII"，否则会奇怪为什么中文没了。
     */
    BITMAP("图片字体"),
}

/**
 * 字体条目。
 *
 * @param id 引用标识，会写进配置里，因此**不可变**：
 *   - 系统字体 `system:<族名>`
 *   - 内置字体 `builtin:<文件名>`
 *   - 导入字体 `imported:<uuid>`
 *   - 图片字体 `bitmap:<uuid>`
 * @param displayName 显示名称（导入字体可被用户改名）
 * @param subtitle 次要说明
 * @param file 实际字体文件。系统字体为 null（走 Compose 的通用族）；
 *   内置字体指向从 assets 解出的缓存文件；导入字体与图片字体
 *   指向私有目录里的文件。
 */
data class FontEntry(
    val id: String,
    val displayName: String,
    val kind: FontKind,
    val subtitle: String,
    val file: File? = null,
)

/**
 * 全局字体库。
 *
 * ============================================================
 * 设计要点
 * ============================================================
 * 1. **全局而非按配置**：导入一次，所有配置都能选。
 *    配置里只存 [FontEntry.id]（`fontId` 字段），换字体只改一个字符串。
 * 2. **三来源统一成一个列表**：系统 / 内置 / 导入对上层是同一种东西，
 *    UI 只按 [FontKind] 决定"能不能删/改名"。
 * 3. **字体文件落在应用私有目录**：导入时复制一份，之后即使原文件被删、
 *    或 SAF 授权失效，字体依然可用。
 *
 * 关于"系统字体"为什么是固定列表：Android 没有公开 API 能枚举系统字体，
 * `Typeface.create(name, style)` 只对通用族名有效，其他名字在部分 ROM 上会
 * **静默回退到默认字体**（用户选了却没变化，比不提供更糟）。
 * 因此这里只列出 AOSP 标准族名，保证选了就真的有变化。
 */
object FontRegistry {

    private const val TAG = "Font"

    /**
     * 默认字体 id。
     *
     * 直接引用配置层的常量（[DEFAULT_FONT_ID]）而不是自己再写一份字符串：
     * 两处各写一份的话，改了其中一处就会出现"配置里存的 id 在这个库里找不到"，
     * 而那种问题在运行时只表现为"字体莫名回落到默认"，很难查。
     */
    const val DEFAULT_ID = DEFAULT_FONT_ID

    private const val SYSTEM_PREFIX = "system:"
    private const val BUILTIN_PREFIX = "builtin:"
    private const val IMPORTED_PREFIX = "imported:"

    /** 内置字体目录（assets 下） */
    private const val BUILTIN_DIR = "fonts"

    /** 导入字体的存放目录（应用私有） */
    private const val IMPORT_DIR = "fonts"

    /**
     * 系统字体候选。
     *
     * **只保留一个默认字体**。原因是实测：`sans-serif-light` / `serif` /
     * `monospace` 这些通用族名在多数设备上渲染出来**完全一样**
     * （厂商 ROM 往往没为它们提供独立字体文件），
     * 列一堆"看起来一样"的选项只会让人困惑。
     *
     * 等以后真有需求，再加"从系统字体文件里枚举"的能力；
     * 现在这一个足够表达"不使用自定义字体"。
     */
    private val SYSTEM_FAMILIES = listOf(
        "sans-serif" to "默认字体",
    )

    /** 内置字体（assets/fonts 下的文件名 → 显示名） */
    private val BUILTIN_FONTS = listOf(
        "Minecraft AE.ttf" to "Minecraft AE",
    )

    private var appContext: Context? = null

    /** 导入字体的元数据缓存 */
    private var importedCache: List<ImportedFont>? = null

    private data class ImportedFont(
        val id: String,
        val displayName: String,
        val fileName: String,
        /** 内容哈希；用于导入配置包时判断"这个字体本地是不是已经有了" */
        val sha256: String,
    )

    /** 初始化（Application 里调用一次即可，未初始化时会自行取上下文） */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun requireContext(): Context? {
        if (appContext == null) {
            AppLog.w(TAG, "FontRegistry 未初始化，字体功能不可用")
        }
        return appContext
    }

    /*
     * ============================================================
     * 查询
     * ============================================================
     */

    /**
     * 全部字体：系统 → 内置 → 导入 → 图片。
     *
     * ⚠️ **界面上"常规字体"那个列表不该用这个** ——
     * 图片字体是**可选的叠加项**（"常规字体必选 + 图片字体可选"），
     * 混进同一个列表会让人以为它们是二选一，而图片字体只有 ASCII 字形、
     * 中文还得靠常规字体兜底。常规字体用 [vectorFonts]，图片字体用
     * [bitmapFonts]，两者在界面上是两个独立的入口。
     *
     * 保留这个合并视图是给"按 id 查一条"这类不区分类型的场合用的
     * （[find] / [displayNameOf] 都基于它）。
     */
    fun all(): List<FontEntry> = buildList {
        addAll(vectorFonts())
        addAll(bitmapFonts())
    }

    /**
     * 图片字体（Minecraft 风格的位图图集）。
     *
     * 元数据在 [com.something.sthkey.domain.font.bitmap.BitmapFontStore] 里，
     * 这里只是把它**翻译成同一个 [FontEntry] 形状** —— 上层（配置页、
     * 字体选择器、配置包导出）就不必为图片字体写第二套逻辑。
     */
    fun bitmapFonts(): List<FontEntry> = BitmapFontStore.entries().map { (id, displayName) ->
        FontEntry(
            id = id,
            displayName = displayName,
            kind = FontKind.BITMAP,
            /*
             * 副标题写清"只支持 ASCII"。
             *
             * 这不是废话：图片字体画不出中文（图集里没有那些字），
             * 用户选了之后发现中文变成空白，第一反应是"字体坏了"。
             * 在这里先说清楚，比让他自己去发现好。
             */
            subtitle = "图片字体 · 仅 ASCII",
            file = BitmapFontStore.specOf(id)?.let { BitmapFontStore.atlasFileOf(it) },
        )
    }

    /** 全部**矢量**字体（系统 / 内置 / 导入）；**不含**图片字体 */
    fun vectorFonts(): List<FontEntry> = buildList {
        addAll(systemFonts())
        addAll(builtinFonts())
        addAll(importedFonts())
    }

    fun systemFonts(): List<FontEntry> = SYSTEM_FAMILIES.map { (family, label) ->
        FontEntry(
            id = SYSTEM_PREFIX + family,
            displayName = label,
            kind = FontKind.SYSTEM,
            subtitle = family,
            file = null,
        )
    }

    fun builtinFonts(): List<FontEntry> = BUILTIN_FONTS.map { (fileName, displayName) ->
        FontEntry(
            id = BUILTIN_PREFIX + fileName,
            displayName = displayName,
            kind = FontKind.BUILTIN,
            subtitle = "内置 · $fileName",
            // assets 里的字体不能直接被 Compose 的 Font(File) 使用，
            // 首次访问时解出一份到私有目录（见 exportedBuiltinFile）
            file = exportedBuiltinFile(fileName),
        )
    }

    fun importedFonts(): List<FontEntry> = readImported().map { imported ->
        FontEntry(
            id = IMPORTED_PREFIX + imported.id,
            displayName = imported.displayName,
            kind = FontKind.IMPORTED,
            subtitle = imported.fileName,
            file = File(importDir(), imported.fileName).takeIf { it.exists() },
        )
    }

    /** 按 id 找一条；找不到返回 null */
    fun find(id: String): FontEntry? = all().firstOrNull { it.id == id }

    /** 取显示名（配置页展示用）；找不到时给出兜底文案 */
    fun displayNameOf(id: String): String =
        find(id)?.displayName ?: "默认（字体已移除）"

    /*
     * ============================================================
     * 加载
     * ============================================================
     */

    /**
     * 解析出字体文件；系统字体返回 null。
     *
     * 悬浮窗绘制用的是 Compose 文字，而 **Compose 这个版本没有
     * `Font(Typeface)` 这种桥接**，只提供 `Font(File)` / `Font(AssetManager, path)`。
     * 因此非系统字体统一走"给一个文件"这条路，
     * UI 层只需要 `Font(file)` 就够了。
     */
    fun resolveFile(fontId: String): File? {
        val entry = find(fontId)
        if (entry == null) {
            AppLog.w(TAG, "找不到字体 $fontId")
            return null
        }
        if (entry.kind == FontKind.SYSTEM) return null

        val file = entry.file
        if (file == null || !file.exists()) {
            AppLog.w(TAG, "字体文件不存在：${entry.displayName}")
            return null
        }
        return file
    }

    /**
     * 从 Uri 取文件名。
     *
     * 用系统 ContentResolver 直接查，**不引入 documentfile 依赖** ——
     * 这里只需要一个显示名，为此多带一个库不划算。
     * 查不到就返回 null，由调用方用 lastPathSegment 兜底。
     */
    private fun queryDisplayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(0)
            } else {
                null
            }
        }
    } catch (e: Exception) {
        AppLog.w(TAG, "读取文件名失败：${e.javaClass.simpleName}")
        null
    }

    /**
     * 把 assets 里的内置字体解出一份到私有目录。
     *
     * 为什么要这一步：assets 里的资源没有可直接访问的文件路径，
     * 而 Compose 的字体工厂只接受 File / ParcelFileDescriptor。
     * 解出一次之后缓存复用（用文件长度判断是否需要重新解）。
     */
    private fun exportedBuiltinFile(fileName: String): File? {
        val context = appContext ?: return null
        val exported = File(builtinDir(), fileName)

        return try {
            val assetSize = context.assets.open("$BUILTIN_DIR/$fileName").use { it.available().toLong() }
            val needsExport = !exported.exists() || exported.length() != assetSize

            if (needsExport) {
                context.assets.open("$BUILTIN_DIR/$fileName").use { input ->
                    exported.outputStream().use { output -> input.copyTo(output) }
                }
                AppLog.i(TAG, "已解出内置字体：$fileName（${exported.length()} 字节）")
            }
            exported
        } catch (e: Exception) {
            AppLog.e(TAG, "解出内置字体失败：$fileName", e)
            null
        }
    }

    /** 内置字体的缓存目录 */
    private fun builtinDir(): File =
        File(appContext!!.filesDir, "builtin_fonts").apply { if (!exists()) mkdirs() }

    /*
     * ============================================================
     * 导入 / 删除 / 改名
     * ============================================================
     */

    /**
     * 导入字体。
     *
     * 步骤：从 Uri 取文件名 → 复制到私有目录 → 登记元数据。
     * 同名时**自动加序号**（`字体 (2)`），不会覆盖已有字体。
     *
     * @return 新导入的条目；失败返回 null
     */
    fun import(uri: Uri): FontEntry? {
        val context = requireContext() ?: return null

        return try {
            val sourceName = queryDisplayName(context, uri)
                ?: uri.lastPathSegment?.substringAfterLast('/')
                ?: "font.ttf"

            if (!sourceName.isSupportedFont()) {
                AppLog.w(TAG, "不支持的文件类型：$sourceName")
                return null
            }

            /*
             * 目标文件名用 UUID，显示名才用原文件名。
             *
             * 为什么不直接拿原文件名当磁盘名：用户可能导入两个同名文件
             * （我们自动加了序号），也可能文件名里带空格/特殊字符。
             * 分开之后磁盘名永远是安全的，显示名可以随用户改。
             */
            val targetName = "${UUID.randomUUID()}.ttf"
            val target = File(importDir(), targetName)

            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: run {
                AppLog.e(TAG, "无法读取所选文件")
                return null
            }

            // 显示名取原文件名（去掉扩展名），同名时加序号
            val baseName = sourceName.substringBeforeLast('.')
            val displayName = nextAvailableName(baseName)

            val imported = ImportedFont(
                id = UUID.randomUUID().toString(),
                displayName = displayName,
                fileName = targetName,
                // 记下内容哈希，之后导入配置包时能判断"本地已有同款"
                sha256 = sha256Of(target),
            )
            writeImported(readImported() + imported)

            AppLog.i(TAG, "已导入字体：$displayName（$sourceName）")
            importedFonts().first { it.id == IMPORTED_PREFIX + imported.id }
        } catch (e: Exception) {
            AppLog.e(TAG, "导入字体失败", e)
            null
        }
    }

    /** 删除导入的字体；系统与内置字体不可删除 */
    fun delete(fontId: String): Boolean {
        if (!fontId.startsWith(IMPORTED_PREFIX)) {
            AppLog.w(TAG, "系统字体与内置字体不可删除：$fontId")
            return false
        }

        val id = fontId.removePrefix(IMPORTED_PREFIX)
        val list = readImported()
        val target = list.firstOrNull { it.id == id } ?: return false

        // 先删文件再删记录：反过来的话文件会成为孤儿
        runCatching { File(importDir(), target.fileName).delete() }
        writeImported(list.filterNot { it.id == id })

        AppLog.i(TAG, "已删除字体：${target.displayName}")
        return true
    }

    /**
     * 重命名字体；只有导入的字体可以改名。
     *
     * 空名字会被拒绝，避免列表里出现一项点不到的空白项。
     */
    fun rename(fontId: String, newName: String): Boolean {
        if (!fontId.startsWith(IMPORTED_PREFIX)) {
            AppLog.w(TAG, "系统字体与内置字体不可重命名：$fontId")
            return false
        }

        val name = newName.trim()
        if (name.isEmpty()) return false

        val id = fontId.removePrefix(IMPORTED_PREFIX)
        val list = readImported()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return false

        val old = list[index].displayName
        val updated = list.toMutableList().also {
            it[index] = it[index].copy(displayName = name)
        }
        writeImported(updated)

        AppLog.i(TAG, "字体已重命名：$old → $name")
        return true
    }

    /*
     * ============================================================
     * 内部
     * ============================================================
     */

    private fun importDir(): File =
        File(appContext!!.filesDir, IMPORT_DIR).apply { if (!exists()) mkdirs() }

    /**
     * 生成不冲突的显示名：重名时追加 (2)、(3)…
     *
     * 公开出去是为了**导入配置包时也能用同一套规则** ——
     * 两处各写一套的话，容易出现"字体加了序号、配置却直接重名"这种不一致。
     */
    fun nextAvailableName(base: String): String {
        val existing = (builtinFonts() + importedFonts()).map { it.displayName }.toSet()
        if (base !in existing) return base

        var index = 2
        while ("$base ($index)" in existing) index++
        return "$base ($index)"
    }

    /**
     * 按内容哈希查找本地已有的字体（**导入的与内置的都查**）。
     *
     * 导入配置包时用：包里带的字体如果本地已经有了（哈希一致），
     * 就直接复用本地那份，不再存一遍 —— 否则同一个字体被几个配置包
     * 各带一份，导入几次就多占几份磁盘。
     *
     * ============================================================
     * ⚠️ 为什么要查内置字体（这里原来漏了）
     * ============================================================
     * 以前"内置字体"是**跟着包一起导出**的（那时只有 Key 样式、字体也只有一个），
     * 于是导入时会把内置的 Minecraft AE 当成一个新字体存进导入库 ——
     * 本地明明已经有一模一样的一份，列表里却多出一条同名字体。
     *
     * 现在内置字体不进包了，所以正常路径下不会再走到这里；
     * 但**旧包仍然会带上它**，那时就得靠这一条把它们认出来。
     *
     * 内置字体按内容比对（而不是"名字对上了就算"）：名字是给用户看的、
     * 可以重复；内容是唯一可信的判据。这个哈希算得很值 ——
     * 它只在真的遇到"包里带了内置字体"时才付一次代价。
     */
    fun findBySha256(hash: String): FontEntry? {
        if (hash.isBlank()) return null

        readImported().firstOrNull { it.sha256 == hash }?.let { matched ->
            importedFonts().firstOrNull { it.id == IMPORTED_PREFIX + matched.id }?.let { return it }
        }

        return builtinFonts().firstOrNull { entry ->
            entry.file?.let { sha256Of(it) == hash } == true
        }
    }

    /**
     * 从字节数组导入字体（配置包里带的字体走这条路）。
     *
     * 与 [import] 的区别：那个从 SAF 的 Uri 读，这个直接给数据。
     *
     * @param preferredName 期望的显示名；重名时自动加序号
     * @param sha256 内容哈希（调用方已经算过，避免重复计算）
     */
    fun importBytes(
        bytes: ByteArray,
        preferredName: String,
        sha256: String,
    ): FontEntry? {
        requireContext() ?: return null

        return try {
            val targetName = "${UUID.randomUUID()}.ttf"
            val target = File(importDir(), targetName)
            target.writeBytes(bytes)

            val imported = ImportedFont(
                id = UUID.randomUUID().toString(),
                displayName = nextAvailableName(preferredName.ifBlank { "导入字体" }),
                fileName = targetName,
                sha256 = sha256,
            )
            writeImported(readImported() + imported)

            AppLog.i(TAG, "已从配置包导入字体：${imported.displayName}")
            importedFonts().first { it.id == IMPORTED_PREFIX + imported.id }
        } catch (e: Exception) {
            AppLog.e(TAG, "从配置包导入字体失败", e)
            null
        }
    }

    /** 记录已导入字体的内容哈希（从 SAF 导入时算） */
    private fun sha256Of(file: File): String = try {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        digest.joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        ""
    }

    private fun readImported(): List<ImportedFont> {
        importedCache?.let { return it }

        val prefs = appContext?.let { AppPrefs.get(it) }
        val raw = prefs?.importedFontsJson
        val parsed = if (raw.isNullOrBlank()) {
            emptyList()
        } else {
            try {
                val array = JSONArray(raw)
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        val id = item.optString("id")
                        val fileName = item.optString("fileName")
                        if (id.isBlank() || fileName.isBlank()) continue
                        add(
                            ImportedFont(
                                id = id,
                                displayName = item.optString("displayName").ifBlank { fileName },
                                fileName = fileName,
                                // 老记录没有哈希：留空即可，只影响"能否按内容去重"
                                sha256 = item.optString("sha256", ""),
                            ),
                        )
                    }
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "字体列表解析失败", e)
                emptyList()
            }
        }

        importedCache = parsed
        return parsed
    }

    private fun writeImported(list: List<ImportedFont>) {
        importedCache = list

        val array = JSONArray()
        list.forEach { font ->
            array.put(
                JSONObject().apply {
                    put("id", font.id)
                    put("displayName", font.displayName)
                    put("fileName", font.fileName)
                    put("sha256", font.sha256)
                },
            )
        }
        appContext?.let { AppPrefs.get(it).importedFontsJson = array.toString() }
    }

    /** 清理已经没有文件的记录（用户手动清过数据等极端情况） */
    fun pruneMissing() {
        val valid = readImported().filter { File(importDir(), it.fileName).exists() }
        if (valid.size != readImported().size) {
            AppLog.w(TAG, "清理了 ${readImported().size - valid.size} 条失效字体记录")
            writeImported(valid)
        }
    }

    /** 是否是可用的字体文件扩展名 */
    private fun String.isSupportedFont(): Boolean {
        val lower = lowercase()
        return lower.endsWith(".ttf") || lower.endsWith(".otf") || lower.endsWith(".ttc")
    }
}

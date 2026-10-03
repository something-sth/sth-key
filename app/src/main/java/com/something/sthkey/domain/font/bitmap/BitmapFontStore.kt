package com.something.sthkey.domain.font.bitmap

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import com.something.sthkey.core.log.AppLog
import com.something.sthkey.core.prefs.AppPrefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 位图字体库。
 *
 * 与 [com.something.sthkey.domain.font.FontRegistry] 并列：那边管矢量字体
 * （`.ttf` / `.otf` / `.ttc`），这边管图片字体。两者共用同一套
 * `fontId` 字符串空间，配置里只存一个 id，所以选哪种对配置层是透明的。
 *
 * ============================================================
 * 为什么图集文件落在 `fonts/` 而不是另开一个目录
 * ============================================================
 * 配置包导出时要把字体文件打进 `assets/fonts/`，而"一个字体 = 一个文件"
 * 这件事对矢量和位图是一样的。放同一个目录，导出/导入/清理都能走同一套逻辑，
 * 少一处分支就少一处"只有某种字体才会出问题"的 bug。
 *
 * 目录布局：
 * ```
 * files/fonts/<uuid>.ttf      矢量字体
 * files/fonts/<uuid>.png      位图字体的图集
 * ```
 */
object BitmapFontStore {

    private const val TAG = "BitmapFont"

    /** 位图字体的 id 前缀。与矢量字体的 `imported:` 区分开 */
    const val ID_PREFIX = "bitmap:"

    /** 图集与矢量字体共用的目录名（与 FontRegistry.IMPORT_DIR 一致） */
    private const val IMPORT_DIR = "fonts"

    private var appContext: Context? = null

    /** 元数据缓存；导入/删除/改名时置空 */
    private var cache: List<Entry>? = null

    /** 已解析的图集与字形；按 id 缓存。**图集很大，不要每次渲染都重扫** */
    private val loaded = mutableMapOf<String, BitmapFont>()

    /**
     * 一条位图字体记录（存进偏好里的部分）。
     *
     * 字形不在这里——那是从图集扫出来的派生数据，见 [BitmapFont]。
     */
    private data class Entry(
        val id: String,
        val displayName: String,
        val spec: BitmapFontSpec,
        /**
         * 图集内容的 SHA-256；没有时为空串（老记录、或导入时没算）。
         *
         * 用途与 `FontRegistry` 那边一致：导入配置包时判断
         * "这个图集本地是不是已经有了"，避免同一个字体存好几份。
         */
        val sha256: String = "",
    )

    /** 加载完成的位图字体：规格 + 字形表 */
    class BitmapFont(
        val id: String,
        val displayName: String,
        val spec: BitmapFontSpec,
        /** 图集图像。渲染时按字形给出的源矩形去取 */
        val atlas: PixelSource,
        /** 字形表，按码点索引 */
        val glyphs: Map<Int, Glyph>,
    ) {
        private var lazyImage: androidx.compose.ui.graphics.ImageBitmap? = null

        /**
         * 同一份像素的 `ImageBitmap` 视图，给 Compose 的 `drawImage` 用。
         *
         * ⚠️ 只在**真正要画的时候**才构造：`asImageBitmap()` 会把整份像素
         * 复制成一份 Android Bitmap，一张 512×512 的图集就是 1MB，
         * 而没用到这个字体的配置不该付这份内存。
         */
        val atlasImage: androidx.compose.ui.graphics.ImageBitmap
            get() = lazyImage ?: buildImage().also { lazyImage = it }

        private fun buildImage(): androidx.compose.ui.graphics.ImageBitmap {
            val pixels = IntArray(atlas.width * atlas.height) { i ->
                atlas.argb(i % atlas.width, i / atlas.width)
            }
            return android.graphics.Bitmap
                .createBitmap(
                    pixels,
                    atlas.width,
                    atlas.height,
                    android.graphics.Bitmap.Config.ARGB_8888,
                )
                .asImageBitmap()
        }

        /** 取一个字符的字形；没有就返回 null（调用方回退到矢量字体） */
        fun glyphFor(codePoint: Int): Glyph? = glyphs[codePoint]
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun requireContext(): Context? {
        if (appContext == null) {
            AppLog.w(TAG, "BitmapFontStore 未初始化")
        }
        return appContext
    }

    private fun importDir(): File = File(requireContext()?.filesDir, IMPORT_DIR).apply { mkdirs() }

    /** 图集文件的绝对路径 */
    fun atlasFileOf(spec: BitmapFontSpec): File = File(importDir(), spec.atlasFileName)

    /*
     * ============================================================
     * 查询
     * ============================================================
     */

    /** 全部位图字体（只含元数据，不解析图集） */
    fun all(): List<BitmapFontSpec> = readEntries().map { it.spec }

    /*
     * ============================================================
     * id 的两种形态（这里踩过坑，务必分清）
     * ============================================================
     * - **存储形态**：[Entry.id] 是**裸 uuid** —— 它是内部实现细节；
     * - **对外形态**：`"bitmap:" + 裸 uuid` —— 配置里存的是这个，
     *   与矢量字体的 `imported:` / `builtin:` 前缀保持同一套形状。
     *
     * ⚠️ 早先 [entries] 返回的是**裸 uuid**，而 [register] 返回的是
     * **带前缀**的。于是 [rename] / [delete] 里 `removePrefix(ID_PREFIX)`
     * 去不掉前缀、查不到条目，**静默返回 false** ——
     * 表现就是"导入时起的名字能用，之后再改就改不动、删也删不掉"，
     * 而且界面上一点提示都没有。
     *
     * 现在所有对外方法一律**收、发带前缀的 id**，只在内部换回裸 uuid。
     */

    /** 对外 id → 存储 id */
    private fun storageId(fontId: String): String = fontId.removePrefix(ID_PREFIX)

    /** 存储 id → 对外 id */
    private fun publicId(id: String): String = ID_PREFIX + id

    /** 全部图片字体：`对外 id` to `显示名` */
    fun entries(): List<Pair<String, String>> =
        readEntries().map { publicId(it.id) to it.displayName }

    fun displayNameOf(fontId: String): String? =
        readEntries().firstOrNull { it.id == storageId(fontId) }?.displayName

    fun specOf(fontId: String): BitmapFontSpec? =
        readEntries().firstOrNull { it.id == storageId(fontId) }?.spec

    /**
     * 按**图集内容哈希**找一条已登记的图片字体。
     *
     * @param matches 额外的匹配条件。同一个图集可以被存成不同规格
     *   （网格不同、遮罩模式不同就是两个字体），所以"哈希相同"还不够，
     *   要由调用方补一条规格比较 —— 见导入配置包时的用法。
     * @return 匹配到的**对外 id**；没有返回 null
     */
    fun findByContent(
        sha256: String,
        matches: (BitmapFontSpec) -> Boolean,
    ): String? {
        if (sha256.isBlank()) return null
        return readEntries()
            .firstOrNull { it.sha256 == sha256 && matches(it.spec) }
            ?.let { publicId(it.id) }
    }

    /**
     * 这个 id 是不是图片字体。
     *
     * ⚠️ 不能只看前缀。早期版本里 [entries] 返回的是**裸 uuid**，
     * 用户当时的配置里就存着那种形态 —— 只看前缀的话，
     * 那些配置会被判成"不是图片字体"，于是渲染悄悄回退到常规字体，
     * 表现就是"图片字体显示成系统默认字体"，而完全看不出是 id 的形态问题。
     *
     * 所以这里**两条都认**：带前缀的、以及能在库里查到的裸 id。
     * 新写入的一律带前缀（见 [publicId]），裸 id 只是兼容老数据。
     */
    fun isBitmapFont(fontId: String): Boolean =
        fontId.startsWith(ID_PREFIX) || specOf(fontId) != null

    /** 这个 id 对应的图集文件在不在 */
    fun hasAtlas(fontId: String): Boolean {
        val spec = specOf(fontId) ?: return false
        return atlasFileOf(spec).exists()
    }

    /*
     * ============================================================
     * 加载（解析图集 + 扫描字形）
     * ============================================================
     */

    /**
     * 取一个位图字体；解析过的会**缓存**。
     *
     * ⚠️ 必须缓存：扫描一张 512×512 的图集要遍历 26 万像素，
     * 而渲染是每帧都在跑的。每次重扫会让界面直接卡死。
     *
     * @return 加载失败返回 null（图集不存在 / 尺寸与网格不匹配 / 解码失败）
     */
    fun load(fontId: String): BitmapFont? {
        loaded[fontId]?.let { return it }

        val entry = readEntries().firstOrNull { it.id == storageId(fontId) } ?: return null
        val file = atlasFileOf(entry.spec)
        if (!file.exists()) {
            AppLog.w(TAG, "图集文件不存在：${entry.displayName}")
            return null
        }

        val source = decode(file, entry.spec.maskMode) ?: return null

        /*
         * 先校验"图能不能被这个网格整除"。
         *
         * ⚠️ 除不尽**必须拒绝**而不是凑合：格子会跨在字形之间，
         * 表现是每个字都被切错、还互相粘连，而画面上看不出是切分问题。
         */
        val cellSize = entry.spec.cellSizeOf(source.width, source.height)
        if (cellSize == null) {
            AppLog.e(
                TAG,
                "图集尺寸 ${source.width}×${source.height} 与网格 " +
                    "${entry.spec.grid.columns}×${entry.spec.grid.rows} 不匹配",
            )
            return null
        }

        val glyphs = scanAtlas(source, entry.spec.codePoints, entry.spec)
            .associateBy { it.codePoint }

        val font = BitmapFont(
            id = fontId,
            displayName = entry.displayName,
            spec = entry.spec,
            atlas = source,
            glyphs = glyphs,
        )
        loaded[fontId] = font

        AppLog.i(
            TAG,
            "已加载位图字体 ${entry.displayName}：" +
                "${source.width}×${source.height}，格 ${cellSize.first}×${cellSize.second}，" +
                "${glyphs.size} 个字形",
        )
        return font
    }

    /**
     * 解出一张图集的像素。
     *
     * 统一转成 `ARGB_8888`：位图字体的遮罩计算依赖 alpha 与 RGB 的准确值，
     * 让系统按 `RGB_565` 之类的格式去省内存会把半透明压没（抗锯齿的图尤其明显）。
     *
     * @param maskMode 遮罩模式；[MaskMode.LUMINANCE] 会把图集**转成白色墨迹**
     *   （见 [toWhiteInk]），具体原因那里有说明。
     */
    private fun decode(file: File, maskMode: MaskMode): PixelSource? = try {
        val options = BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
        if (bitmap == null) {
            AppLog.e(TAG, "图集解码失败：${file.name}")
            null
        } else {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            bitmap.recycle()

            val source = ArrayPixelSource(width, height, pixels)
            if (maskMode == MaskMode.LUMINANCE) toWhiteInk(source) else source
        }
    } catch (e: Exception) {
        AppLog.e(TAG, "图集解码异常", e)
        null
    } catch (e: OutOfMemoryError) {
        // 图集可能很大（用户可能丢一张 4096×4096 进来）
        AppLog.e(TAG, "图集太大，内存不足", e)
        null
    }

    /** 丢掉缓存（改了图集或规格后调用） */
    fun invalidate(fontId: String? = null) {
        if (fontId == null) loaded.clear() else loaded.remove(fontId)
    }

    /**
     * 把"白底 + 灰度字形"的图集转成**白色墨迹 + alpha 覆盖率**。
     *
     * ============================================================
     * 为什么必须转（而不是在渲染时换色）
     * ============================================================
     * 渲染时的染色是 `Modulate`（逐通道相乘）—— 这是**保留渐变**的前提
     * （见 `BitmapFontText` 里那段说明）。但 [MaskMode.LUMINANCE] 的图集
     * 恰好反过来：**白底、深色字形**。直接相乘的话字形会被染成
     * 比背景还暗的一团糊，而"白底"会变成一整块文字颜色。
     *
     * 所以在这里转一次：覆盖率为 `255 − 亮度`，颜色一律取白。
     * 转完之后它就和普通图集一样了，渲染层不必再分情况。
     *
     * ⚠️ 转的是**内存里的一份拷贝**，磁盘上的原图不动 ——
     * 用户以后想改回 [MaskMode.ALPHA] 时还能得到原始数据。
     */
    private fun toWhiteInk(source: PixelSource): PixelSource {
        val width = source.width
        val height = source.height
        val out = IntArray(width * height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val coverage = coverageOf(source.argb(x, y), MaskMode.LUMINANCE)
                // 覆盖率进 alpha，RGB 固定为白：于是 Modulate 之后就是"用文字颜色画的墨迹"
                out[y * width + x] = (coverage shl 24) or 0x00FFFFFF
            }
        }
        return ArrayPixelSource(width, height, out)
    }

    /*
     * ============================================================
     * 写入
     * ============================================================
     */

    /**
     * 把一个图集文件登记成位图字体。
     *
     * @param atlasBytes 图集内容（已从 SAF/压缩包里读出来）
     * @param displayName 用户起的名字；空白时用时间戳（见 [fallbackName]）
     * @param spec 除 [BitmapFontSpec.atlasFileName] 外的规格；文件名由这里填
     * @param sha256 图集内容哈希；导入配置包时传，用于之后去重
     * @return 新的字体 id；失败返回 null
     */
    fun register(
        atlasBytes: ByteArray,
        displayName: String?,
        spec: BitmapFontSpec,
        sha256: String = "",
    ): String? = try {
        val fileName = "${UUID.randomUUID()}.png"
        File(importDir(), fileName).writeBytes(atlasBytes)

        val id = UUID.randomUUID().toString()
        val entry = Entry(
            id = id,
            displayName = displayName?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackName(),
            spec = spec.copy(atlasFileName = fileName),
            sha256 = sha256,
        )
        writeEntries(readEntries() + entry)
        cache = null

        AppLog.i(TAG, "已登记位图字体：${entry.displayName}")
        // 返回**对外形态**的 id（带前缀），与 [entries] 保持一致
        publicId(id)
    } catch (e: Exception) {
        AppLog.e(TAG, "登记位图字体失败", e)
        null
    }

    /**
     * 从配置包导入一个图片字体。
     *
     * 与 [register] 是同一件事，单独开一个入口是为了把语义写清楚：
     * **规格来自包内 manifest**（不是本机猜的默认值），哈希也由调用方算好 —
     * 于是之后同一份包再导入时能靠 [findByContent] 直接复用。
     */
    fun registerImported(
        atlasBytes: ByteArray,
        displayName: String,
        spec: BitmapFontSpec,
        sha256: String,
    ): String? = register(
        atlasBytes = atlasBytes,
        displayName = displayName,
        spec = spec,
        sha256 = sha256,
    )

    /**
     * 用户没起名字时的兜底名。
     *
     * 用**导入时刻**（`yyyyMMddHHmmss`，精确到秒）而不是"未命名字体"：
     * 一个名字只能用一次，否则导入第二张时两个条目同名，用户根本分不清。
     */
    fun fallbackName(now: Long = System.currentTimeMillis()): String {
        val format = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US)
        return format.format(java.util.Date(now))
    }

    /*
     * ============================================================
     * 从导入来源登记
     * ============================================================
     */

    /** 登记结果：成功给 id，失败给一句能直接显示给用户的中文 */
    sealed interface RegisterResult {
        data class Ok(val fontId: String) : RegisterResult
        data class Failed(val reason: String) : RegisterResult
    }

    /**
     * 把导入读到的图集登记成字体。
     *
     * ============================================================
     * 为什么要先"试解码"再登记
     * ============================================================
     * 用户可能选错文件（随手一张照片，或者压缩包里那个**同名但不是
     * 字体图集**的文件）。直接登记的话条目会存下来、但**永远画不出东西**，
     * 而界面上只会显示"这个字体没效果"——原因完全看不出来。
     *
     * 所以这里先按规格真解一遍：解码失败、或**图宽装不下网格**，都当场
     * 拒绝并把原因说清楚。代价是解码两次（这里一次、首次渲染一次），
     * 但导入是低频操作，这点开销换来"选错立刻知道"很值。
     */
    fun registerFromSource(
        source: BitmapFontImporter.Source,
        displayName: String?,
        spec: BitmapFontSpec,
    ): RegisterResult {
        val bytes = when (source) {
            is BitmapFontImporter.Source.Failure -> return RegisterResult.Failed(source.reason)
            is BitmapFontImporter.Source.Image -> source.bytes
            is BitmapFontImporter.Source.FromArchive -> source.bytes
        }

        val probe = decodeBytes(bytes)
            ?: return RegisterResult.Failed("这个文件不是能识别的图片")

        val width = probe.width
        val height = probe.height
        probe.recycle()

        /*
         * ⚠️ 除不尽必须当场拒绝。
         *
         * 网格是"图宽 ÷ 列数"，除不尽时格子会跨在字形之间 ——
         * 表现是每个字都被切错、还互相粘连，而用户只会觉得
         * "这个字体渲染出来是坏的"，根本想不到是尺寸不匹配。
         */
        val cell = spec.cellSizeOf(width, height)
            ?: return RegisterResult.Failed(
                "图片尺寸 ${width}×${height} 装不下 ${spec.grid.columns}×" +
                    "${spec.grid.rows} 的网格（宽高都要能被整除）。" +
                    "这个文件可能不是 Minecraft 的字体图。",
            )

        /*
         * 记下内容哈希：导出配置包时不必重算，导入方也能靠它去重
         * （见 [findByContent]）—— 同一张图集反复导入不会存成好几份。
         */
        val fontId = register(bytes, displayName, spec, sha256 = sha256Of(bytes))
            ?: return RegisterResult.Failed("保存失败")

        AppLog.i(TAG, "导入图片字体成功：${width}×${height}，格 ${cell.first}×${cell.second}")
        return RegisterResult.Ok(fontId)
    }

    /**
     * 图集内容的 SHA-256。
     *
     * 与 `FontRegistry` 那边用同一套算法与十六进制格式 ——
     * 两边一致才有意义：配置包的去重是**按哈希查两套字体库**做的，
     * 格式不同的话永远查不到，表现是"同一个字体被重复导入"。
     */
    private fun sha256Of(bytes: ByteArray): String = try {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.digest(bytes).joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        AppLog.w(TAG, "计算图集哈希失败：${e.javaClass.simpleName}")
        ""
    }

    /** 从内存字节解码（试解码用）；失败返回 null */    private fun decodeBytes(bytes: ByteArray): android.graphics.Bitmap? = try {
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (e: Exception) {
        AppLog.w(TAG, "试解码异常：${e.javaClass.simpleName}")
        null
    } catch (e: OutOfMemoryError) {
        AppLog.w(TAG, "试解码内存不足")
        null
    }

    /** 改名 */
    fun rename(fontId: String, newName: String): Boolean {
        val id = storageId(fontId)
        val list = readEntries()
        val target = list.firstOrNull { it.id == id } ?: return false
        val name = newName.trim()
        if (name.isEmpty()) return false

        writeEntries(list.map { if (it.id == id) target.copy(displayName = name) else it })
        invalidate(fontId)

        AppLog.i(TAG, "已重命名位图字体：${target.displayName} -> $name")
        return true
    }

    /** 删除；连同图集文件一起删掉 */
    fun delete(fontId: String): Boolean {
        val id = storageId(fontId)
        val list = readEntries()
        val target = list.firstOrNull { it.id == id } ?: return false

        // 先删文件再删记录：反过来的话文件会成为孤儿
        runCatching { atlasFileOf(target.spec).delete() }
        writeEntries(list.filterNot { it.id == id })
        invalidate(fontId)

        AppLog.i(TAG, "已删除位图字体：${target.displayName}")
        return true
    }

    /** 清掉"记录还在但图集丢了"的条目（与 FontRegistry.pruneMissing 同一个用途） */
    fun pruneMissing() {
        val alive = readEntries().filter { atlasFileOf(it.spec).exists() }
        if (alive.size != readEntries().size) {
            writeEntries(alive)
        }
    }

    /*
     * ============================================================
     * 持久化
     * ============================================================
     */

    private fun readEntries(): List<Entry> {
        cache?.let { return it }
        val context = requireContext()
        val raw = context?.let { AppPrefs.get(it).bitmapFontsJson } ?: return emptyList()

        val list = try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val atlas = obj.optString("atlasFileName").takeIf { it.isNotBlank() } ?: continue
                    add(
                        Entry(
                            id = id,
                            displayName = obj.optString("displayName").ifBlank { id.take(8) },
                            spec = BitmapFontSpec.fromJson(obj, atlasFileName = atlas),
                            sha256 = obj.optString("sha256"),
                        ),
                    )
                }
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "位图字体记录解析失败：${e.javaClass.simpleName}")
            emptyList()
        }

        cache = list
        return list
    }

    private fun writeEntries(list: List<Entry>) {
        val context = requireContext() ?: return
        val array = JSONArray()
        list.forEach { entry ->
            array.put(
                entry.spec.toJson().apply {
                    put("id", entry.id)
                    put("displayName", entry.displayName)
                    put("sha256", entry.sha256)
                },
            )
        }
        AppPrefs.get(context).bitmapFontsJson = array.toString()
        cache = list
    }
}

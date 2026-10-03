package com.something.sthkey.data.config

import android.content.Context
import android.net.Uri
import com.something.sthkey.BuildConfig
import com.something.sthkey.core.log.AppLog
import com.something.sthkey.domain.config.DEFAULT_FONT_ID
import com.something.sthkey.domain.config.DEFAULT_LIVE2D_MODEL_ID
import com.something.sthkey.domain.config.KeyStrokesConfig
import com.something.sthkey.domain.font.FontKind
import com.something.sthkey.domain.font.FontRegistry
import com.something.sthkey.domain.font.bitmap.BitmapFontStore
import com.something.sthkey.domain.live2d.Live2DModelKind
import com.something.sthkey.domain.live2d.Live2DModels
import com.something.sthkey.data.live2d.Live2DModelImporter
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 配置包的导入导出。
 *
 * ============================================================
 * 设计要点
 * ============================================================
 * 1. **导出时按需携带字体**：只把这份配置**实际用到**的字体复制进包，
 *    而不是把所有字体都塞进去。这样包小、内容明确。
 * 2. **导入时字体进全局库**：包里的字体被提取到本地字体库，
 *    之后所有配置都能选 —— 避免"同一个字体在每个配置里各存一份"。
 * 3. **内容去重**：按 SHA-256 判断包里字体本地是否已有，
 *    已有就复用（只把 params 里的引用改指到本地 id），不重复占磁盘。
 * 4. **绝不带着 builtIn 入库**：导入的配置一律 `builtIn = false`，
 *    否则用户会得到一个"删不掉"的配置。
 * 5. **重名自动加序号**：导入零操作，不会覆盖已有配置。
 */
object ConfigPackageManager {

    private const val TAG = "ConfigPackage"

    /** 导出后回读校验失败时的重试间隔：给异步落盘的 provider 一点时间 */
    private const val VERIFY_RETRY_DELAY_MS = 300L

    /**
     * 导入结果：给 UI 生成报告用。
     *
     * @param config 入库后的配置（id 已是新的）
     * @param originalName 包里的原始名称
     * @param renamed 是否因为重名而改了名字
     * @param unknownKeys 配置里有、本版本不认识的字段
     * @param missingKeys 本版本认识、配置里没写的字段（用了默认值）
     * @param importedFonts 本次新加入字体库的字体名
     * @param reusedFonts 复用了本地已有字体的字体名
     * @param missingFonts 包里没带、本地也没有的字体名（引用它们的地方已回落为默认字体）
     * @param importedModels 本次新加入模型库的模型名
     * @param reusedModels 复用了本地已有模型的模型名
     * @param modelMissing 模型缺失（该配置已回落到内置模型）
     */
    data class ImportResult(
        val config: KeyStrokesConfig,
        val originalName: String,
        val renamed: Boolean,
        val unknownKeys: List<String>,
        val missingKeys: List<String>,
        val importedFonts: List<String>,
        val reusedFonts: List<String>,
        /**
         * 缺失的字体名。
         *
         * ⚠️ 是**列表**而不是一个布尔：自定义 Key 里每个组件都能有自己的字体，
         * 缺的可能只是其中一个 —— 只报"有字体缺失"的话，
         * 用户不知道该去改哪个组件。列出名字才对得上号。
         */
        val missingFonts: List<String>,
        val importedModels: List<String> = emptyList(),
        val reusedModels: List<String> = emptyList(),
        val modelMissing: Boolean = false,
        val packageFormatVersion: Int,
        val packageAppVersion: String,
    ) {
        /** 是否需要给用户看"有差异"的提示 */
        val hasDifferences: Boolean
            get() = unknownKeys.isNotEmpty() || missingKeys.isNotEmpty() ||
                missingFonts.isNotEmpty() || modelMissing
    }

    /** 导入失败的原因（用于给用户明确提示，而不是笼统的"导入失败"） */
    sealed interface ImportError {
        data object NotAPackage : ImportError
        data object MissingManifest : ImportError
        data object MissingParams : ImportError
        data class Broken(val message: String) : ImportError
    }

    /* ============================================================
     * 导出
     * ============================================================ */

    /**
     * 导出配置到指定 Uri。
     *
     * 写完之后会**回读校验**（见 [verifyReadable]）：只有确认这个文件
     * 真的能被完整解压，才算成功。
     *
     * ============================================================
     * ⚠️ 失败时顺手把系统建出来的空文件删掉，然后补写同名
     * ============================================================
     * 系统选择器（`CreateDocument`）在**应用还没写任何数据之前**就把文件
     * 建出来了。于是写入失败时，目标目录里会留下一个 **0 字节的同名文件** ——
     * 用户看到的就是"导出生成了一个空文件"，而且不知道为什么。
     *
     * 更麻烦的是：这个空文件会让**下一次导出**被系统自动改名成 `xxx_1`，
     * 于是"导出的文件名不对"也跟着来了。用户就是这样撞上的。
     *
     * 所以失败后要做两件事：
     * 1. 把那个空文件删掉（它没有任何价值，只会挡住下一次导出）；
     * 2. 再试一次**同名**导出 —— 名字这时正好空出来了，于是最终文件名
     *    就是用户要的那个，而不是 `xxx_1`。
     *
     * ============================================================
     * 关于"覆盖已有的同名文件"
     * ============================================================
     * 这一点 SAF **做不到**：`ACTION_CREATE_DOCUMENT` 的语义就是"创建新文件"，
     * 系统遇到重名一律自动改名，应用既无法请求覆盖、也拿不到"用户想覆盖"的意图。
     * 文件管理器里那些"覆盖/保留两者"的选项是它自己的 UI，不经过我们。
     *
     * 能确定的是：**成功的导出不会留下 `_1`**。真出现 `xxx_1`，
     * 说明那一次是失败的（空文件），而这正是上面两步要清掉的情况。
     *
     * @param requestedName 我们提交给选择器的文件名。
     *   用来判断系统有没有背地里改名 —— 只有改过名的空文件才允许被清理。
     * @return 成功与否；失败原因写进日志
     */
    fun export(
        context: Context,
        config: KeyStrokesConfig,
        target: Uri,
        requestedName: String,
    ): Boolean {
        if (writePackage(context, config, target)) return true

        AppLog.w(TAG, "首次导出失败，尝试清理目标文件后按同名重试一次")
        if (!deleteEmptyTarget(context, target, requestedName)) {
            AppLog.w(TAG, "目标文件不符合清理条件（非空、未改名或删不掉），放弃同名重试")
            return false
        }
        return writePackage(context, config, target)
    }

    /**
     * 删掉**空**的目标文件；不符合条件或删不掉都返回 false。
     *
     * 判据本身抽在 [shouldCleanUp] 里（它是纯函数，有测试钉着），
     * 这里只负责真正的删除动作。
     *
     * @param requestedName 我们提交给选择器的建议文件名
     */
    private fun deleteEmptyTarget(
        context: Context,
        target: Uri,
        requestedName: String,
    ): Boolean = try {
        val resolver = context.contentResolver
        val actualName = displayNameOf(context, target)
        val isEmpty = resolver.openInputStream(target)?.use { it.read() == -1 } ?: false

        val reason = shouldCleanUp(
            requestedName = requestedName,
            actualName = actualName,
            isEmpty = isEmpty,
        )
        if (reason != null) {
            AppLog.i(TAG, "不清理目标文件：$reason")
            false
        } else {
            val deleted = resolver.delete(target, null, null) > 0
            if (deleted) AppLog.i(TAG, "已清理系统改名产生的空文件：$actualName")
            deleted
        }
    } catch (e: Exception) {
        AppLog.w(TAG, "清理空目标文件失败：${e.javaClass.simpleName}: ${e.message}")
        false
    }

    /**
     * 判断目标文件该不该被清理。
     *
     * ============================================================
     * 三道保险，缺一不可
     * ============================================================
     * 这是本项目里唯一一处"删用户的文件"，所以判据保守到近乎偏执。
     * 抽成纯函数是为了**能测** —— 一个判断失误就是删掉用户的文件，
     * 这种逻辑不能只靠读代码确认。
     *
     * @param requestedName 我们提交给选择器的建议文件名
     * @param actualName 目标 Uri 实际的显示名；读不到传 null
     * @param isEmpty 目标文件是不是 0 字节
     * @return **null 表示可以清理**；非 null 是"不要清理"的原因（进日志用）
     */
    internal fun shouldCleanUp(
        requestedName: String,
        actualName: String?,
        isEmpty: Boolean,
    ): String? = when {
        !isEmpty ->
            "文件非空（${actualName ?: "?"}）—— 里面有内容，可能是用户自己的或上次成功的导出"

        actualName == null ->
            "读不到文件名，无法确认它是系统改名造出来的"

        actualName == requestedName ->
            "文件名与请求一致（$actualName）—— 用户选的就是它，空着也不该由我们删"

        else -> null
    }

    /**
     * 读一个 Uri 的显示名。
     *
     * 不引入 `documentfile` 依赖：这里只要一个名字，为此多带一个库不划算
     * （与 `FontRegistry` 里那段同样的取舍）。查不到返回 null。
     */
    private fun displayNameOf(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (e: Exception) {
        AppLog.w(TAG, "读取目标文件名失败：${e.javaClass.simpleName}")
        null
    }

    /**
     * 把配置包写到目标 Uri 的**唯一实现**。
     *
     * `internal` 而不是 `private`：三种"不用挑位置"的导出方式
     * （下载目录 / 自定义目录 / 分享）都在 [ConfigExporters] 里，
     * 它们必须走这同一份写入逻辑。
     *
     * ⚠️ 不能让它们各写一份：写入要带字体、Live2D 模型、改写真写 params
     * （顺序还不能错，见下面的注释），漏一步的表现是"用某种方式导出的包
     * 少了某个资源"，而且只有那一种方式会坏。
     */
    internal fun writeTo(context: Context, config: KeyStrokesConfig, target: Uri): Boolean =
        writePackage(context, config, target)

    /** 真正写一个包进去；不管名字、不做重试 */
    private fun writePackage(context: Context, config: KeyStrokesConfig, target: Uri): Boolean {
        val model = collectLive2DModel(config)

        return try {
            val output = openOutput(context, target)
                ?: run {
                    AppLog.e(TAG, "无法打开导出目标：$target")
                    return false
                }

            /*
             * 字体要**先把 params 拼出来**才能收集 —— 字体 id 遍布整棵树
             * （顶层一个 + 每个自定义组件一个），只有扫过 params 才知道
             * 这份配置到底引用了哪些导入字体、各自藏在哪。
             *
             * 顺序因此是：编码 params → 扫树收集 → 改写 params → 写盘。
             */
            val params = JsonConfigCodec.encode(config)
            val fonts = collectFonts(params)

            // 把树里所有包内字体的 id 改写成包内引用（系统/内置字体保持原样）
            ParamsTree.rewriteFontIds(params) { id -> fonts.uriById[id] }

            output.use { stream ->
                ZipOutputStream(stream).use { zip ->
                    // 1. manifest.json
                    zip.writeText(
                        ConfigPackageCodec.ENTRY_MANIFEST,
                        ConfigPackageCodec.encodeManifest(
                            config = config,
                            appVersion = BuildConfig.VERSION_NAME,
                            exportedAt = System.currentTimeMillis(),
                            fontFiles = fonts.files.map { it.info },
                            live2d = model?.info,
                        ),
                    )

                    /*
                     * 2. params.json
                     *
                     * 字体已经在上面改写成 `sthkey-font://…`。
                     *
                     * 模型同样要改成"包内引用"：直接存 `imported:<uuid>` 的话，
                     * 导入方那边没有这个 id，资源就会丢。
                     *
                     * ⚠️ 模型的改写**必须在字段差异比较之后**是无关的（那一步在导入侧），
                     * 但必须在这里、写盘之前完成。
                     */
                    val live2d = params.optJSONObject("live2d")
                    if (model != null) {
                        live2d?.put(
                            "modelId",
                            ConfigPackageCodec.LIVE2D_URI_PREFIX + model.info.path,
                        )
                    } else if (live2d != null &&
                        live2d.optString("modelId").startsWith(Live2DModels.IMPORTED_PREFIX)
                    ) {
                        // 模型已被删除：回落内置，别让对方的配置指向一个不存在的 id
                        live2d.put("modelId", DEFAULT_LIVE2D_MODEL_ID)
                        AppLog.w(TAG, "配置引用的模型已不存在，导出时回落为内置模型")
                    }

                    zip.writeText(ConfigPackageCodec.ENTRY_PARAMS, params.toString(2))

                    // 3. assets/fonts/...
                    fonts.files.forEach { font ->
                        zip.putNextEntry(ZipEntry(font.info.path))
                        font.file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }

                    // 4. assets/live2d/<模型名>/...
                    model?.let { writeModel(zip, it) }
                }
            }

            /*
             * 写完之后**立刻自己读一遍**。
             *
             * 写出去没抛异常并不等于文件是完整的：云盘、下载管理器这类
             * provider 可能在流关闭后才异步落盘，或者写入被系统中断，
             * 结果就是一个"看起来导出成功、导入时报解压错误"的坏文件。
             * 宁可在这里报失败让用户重试，也别让他到导入时才发现。
             */
            if (!verifyReadable(context, target)) {
                AppLog.e(TAG, "导出后回读校验失败：文件不完整（$target）")
                return false
            }

            AppLog.i(TAG, "已导出配置「${config.name}」，携带字体 ${fonts.files.size} 个")
            true
        } catch (e: Exception) {
            AppLog.e(TAG, "导出配置失败", e)
            false
        }
    }

    /**
     * 打开导出目标的输出流。
     *
     * 用 `"wt"` 而不是默认的 `"w"`：**`"w"` 不保证截断已有文件**。
     * 往一个已存在的同名文件里写，如果新内容比旧的短，
     * 旧文件的尾巴就会留在后面 —— 读回来就是一个"末尾带垃圾"的 zip：
     * 轻则中央目录对不上，重则直接报解压错误。
     *
     * 少数 provider 不支持 `"wt"`，那就退回默认模式，至少不会直接失败。
     */
    private fun openOutput(context: Context, target: Uri): OutputStream? {
        val resolver = context.contentResolver
        return runCatching { resolver.openOutputStream(target, "wt") }.getOrNull()
            ?: runCatching { resolver.openOutputStream(target) }.getOrNull()
    }

    /**
     * 回读校验：把刚写出去的文件完整解压一遍。
     *
     * 只校验"能不能读"，不解析内容 —— 目的是确认文件真的完整落盘了。
     * 失败后隔一下再试一次：有些 provider 的落盘是异步的，
     * 立刻回读可能读到还没写完的版本。
     */
    private fun verifyReadable(context: Context, target: Uri): Boolean {
        if (tryReadAll(context, target)) return true
        Thread.sleep(VERIFY_RETRY_DELAY_MS)
        return tryReadAll(context, target)
    }

    private fun tryReadAll(context: Context, target: Uri): Boolean = try {
        context.contentResolver.openInputStream(target)?.use { input ->
            ZipInputStream(input).use { zip ->
                val buffer = ByteArray(8 * 1024)
                var entries = 0
                while (true) {
                    zip.nextEntry ?: break
                    entries++
                    // 每条都必须读完：截断 / 损坏只在读到底时才暴露
                    while (zip.read(buffer) != -1) {
                        // 丢弃内容，只验证可读
                    }
                    zip.closeEntry()
                }
                entries > 0
            }
        } ?: false
    } catch (e: Exception) {
        AppLog.w(TAG, "回读校验失败：${e.javaClass.simpleName}: ${e.message}")
        false
    }

    /**
     * 收集这份配置**需要跟着包走**的字体，并给出"字体 id → 包内引用"的映射。
     *
     * ============================================================
     * 只有导入字体会进包
     * ============================================================
     * | 来源 | 进包？ | 为什么 |
     * |---|---|---|
     * | 系统字体 `system:*` | ❌ | 每台设备都有 |
     * | 内置字体 `builtin:*` | ❌ | 随应用打包，装了本应用就有一份 |
     * | 导入字体 `imported:*` | ✅ | 只存在于导出方的设备上 |
     *
     * 内置字体当年是跟着包一起导出的（那时是 Key 样式，字体也只有内置那一个），
     * 那是**多此一举**：它随应用打包，从"加入自定义字体"那个版本起人人都有。
     * 现在与系统字体同一套处理：只存 id。一份包因此能小好几 MB。
     *
     * ============================================================
     * 字体从 params **树**里收集，不是从 config 对象
     * ============================================================
     * 因为自定义 Key 的每个组件都有自己的 `style.fontId` ——
     * 从 `config.fontId` 取只能拿到顶层那一个（见 [ParamsTree] 的说明）。
     *
     * @return [CollectedFonts]：要写进 zip 的文件 + 改写映射
     */
    private fun collectFonts(params: JSONObject): CollectedFonts {
        val files = mutableListOf<CollectedFont>()
        val uriById = mutableMapOf<String, String>()
        /** 显示名 → 包内文件名，避免同一份包内出现两个同名文件 */
        val usedNames = mutableSetOf<String>()

        ParamsTree.collectFontIds(params).forEach { fontId ->
            /*
             * 系统字体与内置字体：不进包，树里保持原 id（导入方用自己那份）。
             *
             * ⚠️ 图片字体（[FontKind.BITMAP]）**也要进包**，而且比矢量字体
             * 更需要：它的"怎么切格子、怎么取遮罩、ascent 多少"是一组
             * **因图而异**的参数，只存一个 id 的话导入方拿到 PNG 也不知道
             * 该怎么解析（详见 `ConfigPackageCodec.FontFileInfo.bitmap`）。
             */
            val entry = FontRegistry.find(fontId) ?: run {
                AppLog.w(TAG, "配置引用的字体不存在，导出时跳过：$fontId")
                return@forEach
            }
            if (entry.kind != FontKind.IMPORTED && entry.kind != FontKind.BITMAP) {
                return@forEach
            }

            val file = FontRegistry.resolveFile(fontId) ?: run {
                AppLog.w(TAG, "字体文件缺失，导出时跳过：${entry.displayName}")
                return@forEach
            }

            /*
             * 图片字体：连**规格**一起进包（见 `FontFileInfo.bitmap`）。
             * 落盘文件名用 `.png`，与矢量字体区分开 ——
             * 导入方按后缀决定"注册成矢量字体还是图片字体"，
             * 不依赖 manifest 里的字段是否读全。
             */
            val bitmapSpec = if (entry.kind == FontKind.BITMAP) {
                BitmapFontStore.specOf(fontId)
            } else {
                null
            }

            val extension = if (bitmapSpec != null) ".png" else ".ttf"
            val path = ConfigPackageCodec.DIR_FONTS +
                uniqueFileName(entry.displayName, usedNames, extension)

            files += CollectedFont(
                file = file,
                info = ConfigPackageCodec.FontFileInfo(
                    path = path,
                    displayName = entry.displayName,
                    sha256 = sha256(file),
                    bitmap = bitmapSpec,
                ),
            )
            uriById[fontId] = ConfigPackageCodec.FONT_URI_PREFIX + path
        }

        return CollectedFonts(files = files, uriById = uriById)
    }

    /**
     * 包内文件名：用显示名（可读），重名时加序号。
     *
     * ⚠️ 必须去重：两个导入字体可以被用户改成同一个显示名
     * （字体库允许重名，因为 id 才是标识）。同名的话 zip 里会出现两条
     * 同名条目，后写的覆盖先写的 —— 表现是"导入后有一个字体变成了另一个"。
     *
     * @param extension 含点，例如 `.ttf` / `.png`
     */
    private fun uniqueFileName(
        displayName: String,
        used: MutableSet<String>,
        extension: String = ".ttf",
    ): String {
        val safe = displayName
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .trim()
            .ifBlank { "font" }

        var candidate = "$safe$extension"
        var index = 2
        while (!used.add(candidate)) {
            candidate = "$safe ($index)$extension"
            index++
        }
        return candidate
    }

    private data class CollectedFont(
        val file: File,
        val info: ConfigPackageCodec.FontFileInfo,
    )

    /** [collectFonts] 的结果：要进包的文件 + 树里的改写映射 */
    private data class CollectedFonts(
        val files: List<CollectedFont>,
        val uriById: Map<String, String>,
    )

    /**
     * 收集这份配置用到的 Live2D 模型。
     *
     * **只有导入的模型需要进包**：内置模型（`builtin:keyboard` / `builtin:standard`）
     * 对方本来就有，和系统字体同理，存个 id 就够了。
     *
     * 返回 null 表示"不用进包"，包括三种情况：样式不是 Live2D、用的是内置模型、
     * 模型已被删除（导出时会回落成内置模型并在日志里记一条）。
     */
    private fun collectLive2DModel(config: KeyStrokesConfig): CollectedModel? {
        val entry = Live2DModels.find(config.live2d.modelId) ?: return null
        if (entry.kind != Live2DModelKind.IMPORTED) return null
        val dir = entry.dir ?: return null
        if (!dir.isDirectory) return null

        // 包内目录名用显示名（可读），并做一次安全化处理
        val safeName = entry.displayName
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .trim()
            .ifBlank { "live2d" }

        return CollectedModel(
            dir = dir,
            info = ConfigPackageCodec.Live2DFileInfo(
                path = ConfigPackageCodec.DIR_LIVE2D + safeName,
                displayName = entry.displayName,
                // 摘要直接用模型库里存的那份，保证和导入时的判定一致
                sha256 = entry.digest,
                layout = entry.layout,
            ),
        )
    }

    private data class CollectedModel(
        val dir: File,
        val info: ConfigPackageCodec.Live2DFileInfo,
    )

    /**
     * 把模型目录写进包。
     *
     * ⚠️ **跳过导入时生成的 `index.html` 与 `modeldata.js`**：
     * - 它们是本机生成物，对方导入时会自己重新生成；
     * - 更要紧的是它们**会污染内容摘要** —— 摘要里带上它们，同一个模型
     *   在不同机器上算出来的摘要就不一样，"复用已有模型"永远命中不了。
     */
    private fun writeModel(zip: ZipOutputStream, model: CollectedModel) {
        val generated = setOf(Live2DModels.ENTRY_INDEX, Live2DModels.ENTRY_MODEL_DATA)
        val prefix = model.info.path.trimEnd('/') + "/"

        model.dir.walkTopDown()
            .filter { it.isFile && it.name !in generated }
            .sortedBy { it.relativeTo(model.dir).invariantSeparatorsPath }
            .forEach { file ->
                val relative = file.relativeTo(model.dir).invariantSeparatorsPath
                zip.putNextEntry(ZipEntry(prefix + relative))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
    }

    /* ============================================================
     * 导入
     * ============================================================ */

    /**
     * 从 Uri 导入配置。
     *
     * 整个过程在 IO 线程执行（解压 + 字体落盘都是磁盘操作）。
     *
     * ============================================================
     * 为什么先把包复制到缓存，再用 [ZipFile] 打开
     * ============================================================
     * 之前直接用 `ZipInputStream` 顺序读，有两个问题：
     *
     * 1. **报错看不懂**：顺序流读到损坏的数据只会抛出
     *    "Unexpected end of ZLIB input stream" 这类底层异常，
     *    用户完全不知道发生了什么、该怎么办；
     * 2. **定位不到原因**：zip 的条目清单在文件末尾的中央目录里，
     *    顺序流读不到它，因此无法判断"文件本身就残缺"还是"某一条坏了"。
     *
     * [ZipFile] 会先校验中央目录，损坏 / 被截断的文件在打开时就被拦下，
     * 而且条目可以按名字直接取，字体也不必整个读进内存。
     */
    fun import(context: Context, source: Uri): Result<ImportResult> {
        val temp = copyToCache(context, source)
            ?: return Result.failure(ImportException(ImportError.Broken("无法读取所选文件")))

        return try {
            ZipFile(temp).use { zip -> importFromZip(context, zip) }
        } catch (e: ImportException) {
            AppLog.e(TAG, "导入配置失败：${e.error}")
            Result.failure(e)
        } catch (e: IOException) {
            /*
             * 读取层面的失败：压缩包被截断、根本不是 zip、某条条目数据损坏。
             * ZipException 也在这里（它是 IOException 的子类）。
             *
             * 这些底层说法（例如 "Unexpected end of ZLIB input stream"）
             * 对用户毫无意义，必须翻译成"发生了什么 + 该怎么办"。
             * 末尾附上技术细节，方便用户反馈时直接贴给我们。
             */
            AppLog.e(TAG, "压缩包无法读取：${e.javaClass.simpleName}: ${e.message}", e)
            Result.failure(
                ImportException(
                    ImportError.Broken(
                        "压缩包已损坏或不完整，无法读取。\n" +
                            "常见原因是文件没有完整复制过来，或导出时被系统中断，" +
                            "请重新导出一份再试。\n" +
                            "技术细节：${e.javaClass.simpleName}: ${e.message ?: "无详情"}",
                    ),
                ),
            )
        } catch (e: Exception) {
            AppLog.e(TAG, "导入配置失败", e)
            Result.failure(ImportException(ImportError.Broken(e.message ?: e.javaClass.simpleName)))
        } finally {
            temp.delete()
        }
    }

    /** 真正的导入流程；此时压缩包已经校验过、可以随机取条目 */
    private fun importFromZip(context: Context, zip: ZipFile): Result<ImportResult> {
        // 1. manifest 必须存在且格式正确 —— 不做"半份入库"
        val manifestRaw = zip.readEntry(ConfigPackageCodec.ENTRY_MANIFEST)
            ?: return Result.failure(ImportException(ImportError.MissingManifest))
        val manifest = ConfigPackageCodec.decodeManifest(
            manifestRaw.toString(Charsets.UTF_8),
        ) ?: return Result.failure(ImportException(ImportError.NotAPackage))

        val paramsRaw = zip.readEntry(ConfigPackageCodec.ENTRY_PARAMS)
            ?: return Result.failure(ImportException(ImportError.MissingParams))

        val params = JSONObject(String(paramsRaw, Charsets.UTF_8))

        // 2. 字段差异（要在改写 fontId 之前算，否则 fontId 会被算成"存在"）
        val diff = ConfigPackageCodec.compareFields(params)

        // 3. 处理包内字体；**必须在 decode 之前**，它会把树里的包内引用改写成本机 id
        val fontOutcome = importFonts(zip, manifest, params)

        // 3b. 处理包内模型；**必须在 decode 之前**，它会把 modelId 改写成本地 id
        val modelOutcome = importModel(context, manifest, zip, params)

        // 4. 解析成配置对象
        val parsed = JsonConfigCodec.decode(params)

        /*
         * 5. 入库：新 id、非内置、重名加序号
         *
         * 名称与描述只认 manifest —— params.json 里根本没有这两个字段了。
         * manifest.name 必定非空（解析时已把空名回落成"未命名配置"），
         * 所以这里不需要再兜底。
         *
         * ⚠️ **不再覆盖 fontId 了**：字体已经在第 3 步按树改写好了，
         * 顶层与每个组件的字体都是本机可用的 id。早先这里会
         * `copy(fontId = 解析结果)`，那在"一个配置只有一个字体"时成立，
         * 现在会**把组件自己的字体改掉**（虽然值恰好相同，但那是巧合）。
         */
        val store = ConfigStore.get(context)
        val finalName = store.nextAvailableName(manifest.name)
        val created = parsed.copy(
            id = UUID.randomUUID().toString(),
            name = finalName,
            description = manifest.description,
            builtIn = false,
        )
        store.upsert(created)

        AppLog.i(
            TAG,
            "已导入配置「${created.name}」（原名称「${manifest.name}」，" +
                "跳过 ${diff.unknown.size} 个未知字段，默认值 ${diff.missing.size} 项）",
        )

        return Result.success(
            ImportResult(
                config = created,
                originalName = manifest.name,
                renamed = finalName != manifest.name,
                unknownKeys = diff.unknown,
                missingKeys = diff.missing,
                importedFonts = fontOutcome.imported,
                reusedFonts = fontOutcome.reused,
                missingFonts = fontOutcome.missing,
                importedModels = modelOutcome.imported,
                reusedModels = modelOutcome.reused,
                modelMissing = modelOutcome.missing,
                packageFormatVersion = manifest.formatVersion,
                packageAppVersion = manifest.appVersion,
            ),
        )
    }

    /**
     * 处理包内的 Live2D 模型。
     *
     * 与字体（[importFonts]）是一套思路，差别在于模型是**目录**：
     * 1. 摘要命中本地已有模型 → 直接复用（把 params 里的引用改指过去）；
     * 2. 否则把包内目录解压到模型库，再交给导入器做校验、生成页面、登记；
     * 3. 包里没带模型（老包、或导出时用的是内置模型）→ 把包内引用改回内置，
     *    避免配置指向一个本机不存在的 id。
     *
     * 失败**不算导入失败**：配置本身是好的，只是模型缺失 —— 回落到内置模型，
     * 并在导入报告里如实说明。
     */
    private fun importModel(
        context: Context,
        manifest: ConfigPackageCodec.Manifest,
        zip: ZipFile,
        params: JSONObject,
    ): ModelOutcome {
        val live2d = params.optJSONObject("live2d")
        val rawId = live2d?.optString("modelId").orEmpty()
        val referencesPackaged = rawId.startsWith(ConfigPackageCodec.LIVE2D_URI_PREFIX)
        val info = manifest.live2d

        if (info == null) {
            if (referencesPackaged) {
                AppLog.w(TAG, "配置引用了包内模型，但 manifest 里没有登记")
                live2d?.put("modelId", DEFAULT_LIVE2D_MODEL_ID)
                return ModelOutcome(missing = true)
            }
            return ModelOutcome()
        }

        // 摘要命中：同一个模型不重复占空间
        Live2DModels.findByDigest(info.sha256)?.let { existing ->
            AppLog.i(TAG, "模型已在本地，直接复用：${existing.displayName}")
            live2d?.put("modelId", existing.id)
            return ModelOutcome(reused = listOf(existing.displayName))
        }

        val dirId = UUID.randomUUID().toString().replace("-", "")
        val dir = File(Live2DModels.importRoot(context), dirId)
        return try {
            dir.mkdirs()
            extractModelDir(zip, info.path, dir)

            val outcome = Live2DModelImporter.registerDirectory(dir, dirId, info.displayName)
                .getOrThrow()
            live2d?.put("modelId", outcome.entry.id)

            if (outcome.reused) {
                ModelOutcome(reused = listOf(outcome.entry.displayName))
            } else {
                ModelOutcome(imported = listOf(outcome.entry.displayName))
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "包内模型导入失败，回落为内置模型", e)
            runCatching { dir.deleteRecursively() }
            live2d?.put("modelId", DEFAULT_LIVE2D_MODEL_ID)
            ModelOutcome(missing = true)
        }
    }

    /** 报给 UI 的模型处理结果 */
    private data class ModelOutcome(
        val imported: List<String> = emptyList(),
        val reused: List<String> = emptyList(),
        val missing: Boolean = false,
    )

    /**
     * 把包内某个目录下的所有文件解压到 [target]。
     *
     * 按前缀筛条目而不是按目录树递归：包里的条目名就是完整路径，
     * 前缀匹配最简单也最不容易出错。路径仍然要校验 —— 包是可以被手工改的。
     */
    private fun extractModelDir(zip: ZipFile, prefix: String, target: File) {
        val normalized = prefix.trimEnd('/') + "/"
        val root = target.canonicalPath

        zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.startsWith(normalized) }
            .forEach { entry ->
                val relative = entry.name.removePrefix(normalized)
                val out = File(target, relative).canonicalFile
                require(out.path.startsWith(root + File.separator)) {
                    "包内模型路径非法：${entry.name}"
                }
                out.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }
    }

    /**
     * 把 params 树里所有"包内字体引用"解析成本机字体 id（**原地改写**）。
     *
     * ============================================================
     * 与导出侧完全对称
     * ============================================================
     * 导出时把每一个导入字体的 id 改写成 `sthkey-font://<包内路径>`；
     * 这里按 manifest 登记的信息把它换回**本机**的字体 id：
     *
     * - 本地已有**内容相同**的字体（哈希一致）→ 复用，不重复占磁盘；
     * - 本地没有 → 落盘并登记进全局字体库（重名自动加序号）；
     * - 包里没带 / 读不出来 / 导入失败 → **换成默认字体**，
     *   并把字体名记进 [Outcome.missing]，交给导入报告说明。
     *
     * ⚠️ 失败时必须换成**默认字体**而不是留着 `sthkey-font://` 不管：
     * 那个字符串不是一个合法的字体 id，`FontRegistry.find` 找不到它，
     * 渲染层就静默回落 —— 用户看不到任何解释。换成默认字体至少是"确定的"。
     *
     * ⚠️ 系统字体与内置字体**不走这条**：树里存的就是 `system:*` / `builtin:*`，
     * 它们在本机同样有效，原样保留即可（这是"内置字体不进包"能成立的前提）。
     *
     * @return 处理结果（哪些字体是新导入的、哪些复用了、哪些缺失）
     */
    private fun importFonts(zip: ZipFile, manifest: ConfigPackageCodec.Manifest, params: JSONObject): Outcome {
        val imported = mutableListOf<String>()
        val reused = mutableListOf<String>()
        val missing = mutableListOf<String>()

        /*
         * 逐个包内字体处理一次，结果按"包内引用字符串"缓存 ——
         * 同一个字体被五个组件引用时只该落盘一次、也只该在报告里出现一次。
         */
        val resolved = mutableMapOf<String, String>()

        fun resolve(uri: String): String? = resolved[uri] ?: run {
            val path = uri.removePrefix(ConfigPackageCodec.FONT_URI_PREFIX)
            val info = manifest.fontFiles.firstOrNull { it.path == path }

            val outcome = when {
                info == null -> {
                    AppLog.w(TAG, "配置引用了包内字体，但 manifest 里没有登记：$uri")
                    null
                }

                else -> storeFont(zip, info, imported, reused)
            }

            val localId = outcome ?: run {
                missing += info?.displayName ?: path.substringAfterLast('/')
                DEFAULT_FONT_ID
            }
            resolved[uri] = localId
            localId
        }

        ParamsTree.rewriteFontIds(params) { value ->
            if (value.startsWith(ConfigPackageCodec.FONT_URI_PREFIX)) resolve(value) else null
        }

        /*
         * 老包（单数目录）里字体是记在**顶层 `fontId`** 上的，
         * 而上面那趟树遍历同样会扫到它、同样能处理 —— 不需要额外分支。
         * 唯一要兼容的是"manifest 里登记了字体、但 params 里没引用"这种
         * 早先的写法（新版导出不会这样，老包可能有）。
         */
        manifest.fontFiles.forEach { info ->
            val uri = ConfigPackageCodec.FONT_URI_PREFIX + info.path
            if (uri !in resolved) storeFont(zip, info, imported, reused)
        }

        return Outcome(imported, reused, missing)
    }

    /**
     * 把一个包内字体落到本机字体库。
     *
     * 两步：按哈希查本地是否已有（复用）→ 否则落盘并登记。
     *
     * ⚠️ 图片字体走的是**另一套字体库**（[BitmapFontStore]）：
     * 它要带上规格（网格 / 遮罩 / ascent），而且"同哈希复用"的判断
     * 也简单得多 —— 规格是包内自带的，只要本地已有同哈希的图集，
     * 直接复用那一条即可（同图 + 同规格 = 同字体）。
     *
     * @return 本机字体 id；失败返回 null
     */
    private fun storeFont(
        zip: ZipFile,
        info: ConfigPackageCodec.FontFileInfo,
        imported: MutableList<String>,
        reused: MutableList<String>,
    ): String? {
        val data = zip.readEntry(info.path)
        if (data == null) {
            AppLog.w(TAG, "包里缺少字体文件：${info.path}")
            return null
        }

        /*
         * 是不是图片字体，以 **manifest 里的规格**为准。
         *
         * 不看文件后缀：后缀只能说明"这是个 PNG"，而一个没带规格的 PNG
         * 根本没法用 —— 那样还不如报成缺失、让上面那套"回落默认字体 +
         * 在报告里列出来"的逻辑处理掉。
         */
        info.bitmap?.let { spec ->
            return storeBitmapFont(data, info, spec, imported, reused)
        }

        // 内容去重：本地已有同哈希字体就直接复用（导入与内置都查，见 findBySha256）
        val hash = sha256(data)
        FontRegistry.findBySha256(hash)?.let { existing ->
            reused += existing.displayName
            AppLog.i(TAG, "字体已在本地，直接复用：${existing.displayName}")
            return existing.id
        }

        val stored = FontRegistry.importBytes(
            bytes = data,
            preferredName = info.displayName,
            sha256 = hash,
        ) ?: return null

        imported += stored.displayName
        return stored.id
    }

    /**
     * 把一个包内**图片字体**落到本机字体库。
     *
     * 复用判据比矢量字体强：除了图集内容相同，还要求**规格也相同**
     * （同哈希 + 同规格 = 同一个字体；图一样但网格不同就是两个字体）。
     */
    private fun storeBitmapFont(
        data: ByteArray,
        info: ConfigPackageCodec.FontFileInfo,
        spec: com.something.sthkey.domain.font.bitmap.BitmapFontSpec,
        imported: MutableList<String>,
        reused: MutableList<String>,
    ): String? {
        val hash = sha256(data)

        // 本地已有"同图 + 同规格"的，直接复用
        BitmapFontStore.findByContent(hash) { existing ->
            existing.copy(atlasFileName = "") == spec.copy(atlasFileName = "")
        }?.let { existingId ->
            val name = BitmapFontStore.displayNameOf(existingId) ?: info.displayName
            reused += name
            AppLog.i(TAG, "图片字体已在本地，直接复用：$name")
            return existingId
        }

        val fontId = BitmapFontStore.registerImported(
            atlasBytes = data,
            displayName = info.displayName,
            spec = spec,
            sha256 = hash,
        ) ?: return null

        imported += BitmapFontStore.displayNameOf(fontId) ?: info.displayName
        return fontId
    }

    private data class Outcome(
        val imported: List<String>,
        val reused: List<String>,
        /** 缺失的字体名（已回落为默认字体） */
        val missing: List<String>,
    )

    /* ============================================================
     * zip 工具
     * ============================================================ */

    private fun ZipOutputStream.writeText(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    /** 按名字取一条条目；不存在返回 null。内容损坏会在读取时抛 IOException */
    private fun ZipFile.readEntry(name: String): ByteArray? {
        val entry = getEntry(name) ?: return null
        return getInputStream(entry).use { it.readBytes() }
    }

    /**
     * 把 Uri 内容复制到缓存目录。
     *
     * 不直接读进内存：字体可能十几 MB，而且 [ZipFile] 需要一个真实文件
     * （它靠随机访问读文件末尾的中央目录）。用完即删，不占用户空间。
     */
    private fun copyToCache(context: Context, source: Uri): File? {
        val file = File(context.cacheDir, "import_${System.currentTimeMillis()}.zip")
        return try {
            val input = context.contentResolver.openInputStream(source)
                ?: run {
                    file.delete()
                    return null
                }
            input.use { source0 ->
                file.outputStream().use { target0 -> source0.copyTo(target0) }
            }
            file
        } catch (e: Exception) {
            AppLog.e(TAG, "复制导入文件到缓存失败", e)
            file.delete()
            null
        }
    }

    private fun sha256(file: File): String = sha256(file.readBytes())

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** 带明确原因的导入异常 */
    class ImportException(val error: ImportError) : Exception(error.toString())
}

package com.something.sthkey.data.live2d

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.something.sthkey.core.log.AppLog
import com.something.sthkey.domain.live2d.Live2DModelEntry
import com.something.sthkey.domain.live2d.Live2DModels
import com.something.sthkey.domain.live2d.Live2DSemantics
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.Writer
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * 把一个 Live2D 模型 ZIP 导入到模型库。
 *
 * ============================================================
 * 流程照旧项目（它的 KeyboardCatModelStore 在真机上跑得通）
 * ============================================================
 * 1. 解压到 `filesDir/live2d_models/<uuid>/`（**流式写盘** + 路径穿越校验 + 体积上限）；
 * 2. 在目录树里找第一个 `*.model3.json`；
 * 3. 校验它引用的 `Moc` 与 `Textures` 都存在；
 * 4. 算内容摘要，命中已有模型就直接复用（少占一份磁盘）；
 * 5. 生成 `modeldata.js` 与 `index.html`；
 * 6. 登记进全局模型库。
 *
 * ============================================================
 * 两个关键实现选择
 * ============================================================
 * **① moc3 转 base64 写进 modeldata.js**：
 * `file://` 页面里 `fetch` / XHR 读二进制受限制，而 `<script src>` 不受限。
 * 这是旧项目踩过之后的选择，照抄。
 *
 * **② base64 是分块流式写的**（旧项目一次性读进内存）：
 * moc3 可能有 20MB，一次性 `readBytes()` 再拼一个 27MB 的字符串，
 * 峰值会到 47MB 左右 —— 在手机上这足以把应用推到内存边界。
 * 这里按 3 的整数倍分块编码、直接写文件，峰值只跟块大小有关。
 */
object Live2DModelImporter {

    private const val TAG = "Live2D"

    /** 解压上限，照旧项目：512 个文件 / 单条 192MB / 总计 512MB */
    private const val MAX_FILES = 512
    private const val MAX_ENTRY_BYTES = 192L * 1024L * 1024L
    private const val MAX_TOTAL_BYTES = 512L * 1024L * 1024L

    /** base64 分块大小；必须是 3 的整数倍，否则拼出来的 base64 是坏的 */
    private const val BASE64_CHUNK_BYTES = 3 * 1024 * 1024

    /**
     * 生成页面里引用的公共资源。
     *
     * ⚠️ 路径写死是**刻意的**：内置的 `keyboard/runtime.js` 里也有一个写死的
     * 资源回退地址（`file:///android_asset/bongocat/keyboard/resources`），
     * 两边必须一致，所以 assets 目录名不能为了整齐而改。
     */
    private const val ASSET_CORE_JS = "file:///android_asset/bongocat/live2dcubismcore.min.js"
    private const val ASSET_RUNTIME_JS = "file:///android_asset/bongocat/keyboard/runtime.js"
    private const val ASSET_FALLBACK_RESOURCES =
        "file:///android_asset/bongocat/keyboard/resources"

    /** 判定"这个模型自带 BongoCat 键帽资源包"的标志文件 */
    private const val RESOURCE_MARKER = "background.png"

    /** 兼容性检查关注的参数：没有它们，按键时手不会动 */
    private val HAND_PARAMS = listOf("CatParamLeftHandDown", "CatParamRightHandDown")

    /**
     * 导入结果。
     *
     * @param reused 本地已有同样内容的模型（按摘要命中），没有重复占磁盘
     * @param notes 给用户看的提示（兼容性、布局），导入报告里逐条显示
     */
    data class Outcome(
        val entry: Live2DModelEntry,
        val reused: Boolean,
        val notes: List<String>,
    )

    /**
     * 从 ZIP 导入。
     *
     * 整个过程是磁盘与解压操作，调用方要放在 IO 线程。
     */
    fun importZip(context: Context, uri: Uri): Result<Outcome> {
        val dirId = UUID.randomUUID().toString().replace("-", "")
        // 目录名只在模型库里定义一处，这里不自己拼路径
        val dir = File(Live2DModels.importRoot(context), dirId)

        return try {
            dir.mkdirs()
            extract(context, uri, dir)
            // 显示名取 SAF 给的文件名（去掉 .zip 后缀）
            val displayName = queryDisplayName(context, uri)
                .substringBeforeLast('.')
                .ifBlank { "导入的模型" }
            finishImport(dir, dirId, displayName)
        } catch (e: Exception) {
            // 任何一步失败都不留半份模型：目录整体删掉
            AppLog.e(TAG, "导入模型失败", e)
            runCatching { dir.deleteRecursively() }
            Result.failure(e)
        }
    }

    /**
     * 把**已经导入**的模型的页面刷新到当前版本。
     *
     * 生成页面的逻辑会变（例如"只有角色的模型不该显示内置键盘美术"），
     * 而那种改动只影响**新导入**的模型 —— 老模型的 `index.html` 还是旧内容，
     * 用户会看到"我明明导入了/明明修好了，画面却没变"。
     *
     * 启动时跑一次，内容一致就不写盘。这里只重写页面骨架，
     * **不动 `modeldata.js`**（那里是模型数据，重生成要重新 base64，没必要）。
     */
    fun repairImportedPages() {
        Live2DModels.importedModels().forEach { model ->
            val dir = model.dir ?: return@forEach
            val file = File(dir, Live2DModels.ENTRY_INDEX)
            val expected = buildHtml(model.layout)
            if (runCatching { file.readText() }.getOrNull() != expected) {
                runCatching { file.writeText(expected) }
                    .onSuccess { AppLog.i(TAG, "已更新模型页面：${model.displayName}") }
                    .onFailure { AppLog.w(TAG, "更新模型页面失败：${model.displayName}") }
            }

            /*
             * 补记"这个模型支持哪些键"。
             *
             * 这个字段是后加的：用户之前导入的模型没有它，不补的话那些模型会一直
             * "所有键都响应"（按 A 也会看见手动），用户会以为改动没生效。
             * 只对自带皮肤包的模型补（只有角色的模型本来就不过滤）。
             */
            if (model.layout == Live2DModels.LAYOUT_FULL && model.supportedKeys.isEmpty()) {
                val resourceDir = findResourceDir(dir) ?: return@forEach
                Live2DModels.updateSupportedKeys(model.id, providedKeycaps(resourceDir))
            }
        }
    }

    /**
     * 把一个**已经在模型库目录里**的模型登记进来。
     *
     * 配置包导入走这条路：包里的 `assets/live2d/<名字>/` 已经被解压到
     * 模型库目录下了，接下来要做的事和 ZIP 导入完全一样
     * （校验 → 摘要去重 → 生成页面 → 登记），所以共用同一段实现。
     */
    fun registerDirectory(
        dir: File,
        dirId: String,
        displayName: String,
    ): Result<Outcome> = try {
        finishImport(dir, dirId, displayName)
    } catch (e: Exception) {
        AppLog.e(TAG, "登记模型失败", e)
        runCatching { dir.deleteRecursively() }
        Result.failure(e)
    }

    private fun finishImport(
        dir: File,
        dirId: String,
        displayName: String,
    ): Result<Outcome> {
        // 1. 找模型描述文件（可能在子目录里，所以是遍历）
        val modelJsonFile = dir.walkTopDown()
            .firstOrNull { it.isFile && it.name.endsWith(".model3.json", ignoreCase = true) }
            ?: return Result.failure(IllegalStateException("这个压缩包里没有 .model3.json，不是 Live2D 模型包"))

        val modelJson = runCatching { JSONObject(modelJsonFile.readText()) }.getOrNull()
            ?: return Result.failure(IllegalStateException("模型描述文件无法解析（.model3.json 格式不对）"))

        val refs = modelJson.optJSONObject("FileReferences")
            ?: return Result.failure(IllegalStateException("模型描述文件里没有 FileReferences"))

        // 2. 校验 moc 与贴图
        val modelBase = modelJsonFile.parentFile ?: dir
        val moc = resolveInside(modelBase, refs.optString("Moc"), dir)
            ?: return Result.failure(IllegalStateException("模型缺少主体文件（Moc）"))
        if (!moc.isFile || moc.length() < 4L) {
            return Result.failure(IllegalStateException("模型主体文件无效（Moc 为空或损坏）"))
        }

        val textures = mutableListOf<File>()
        val texturesJson = refs.optJSONArray("Textures")
        if (texturesJson == null || texturesJson.length() == 0) {
            return Result.failure(IllegalStateException("模型没有贴图（Textures 为空）"))
        }
        for (index in 0 until texturesJson.length()) {
            val file = resolveInside(modelBase, texturesJson.optString(index), dir)
            if (file == null || !file.isFile) {
                return Result.failure(
                    IllegalStateException("模型缺少贴图：${texturesJson.optString(index)}"),
                )
            }
            textures += file
        }

        // 3. 内容摘要 —— 命中已有模型就直接复用
        val digest = computeDigest(dir)
        Live2DModels.findByDigest(digest)?.let { existing ->
            AppLog.i(TAG, "模型内容与已有模型一致，直接复用：${existing.displayName}")
            dir.deleteRecursively()
            return Result.success(Outcome(existing, reused = true, notes = emptyList()))
        }

        // 4. 布局：自带键帽资源包就用它自己的，否则用内置键盘资源兜底
        val resourceDir = findResourceDir(dir)
        val layout = if (resourceDir != null) Live2DModels.LAYOUT_FULL else Live2DModels.LAYOUT_CAT

        // 4b. 皮肤包补齐键帽文件（见 fillMissingKeycaps 的说明）
        if (resourceDir != null) fillMissingKeycaps(resourceDir)

        // 5. 生成页面
        writeModelData(dir, moc, textures, resourceDir, layout)
        File(dir, Live2DModels.ENTRY_INDEX).writeText(buildHtml(layout))

        // 6. 登记
        val entry = Live2DModels.registerImported(
            dirId = dirId,
            displayName = displayName,
            digest = digest,
            layout = layout,
            // 必须在补齐键帽**之后**算，而且要把透明占位排除掉（见 providedKeycaps）
            supportedKeys = resourceDir?.let { providedKeycaps(it) }.orEmpty(),
        )

        val notes = buildList {
            addAll(compatibilityNotes(modelJson, modelBase, dir))
            if (layout == Live2DModels.LAYOUT_CAT) {
                add("这个模型没有自带键盘美术，因此只显示角色本身（键帽与键盘底图都不会出现）。")
            }
        }
        AppLog.i(TAG, "模型导入成功：${entry.displayName}（布局 $layout，提示 ${notes.size} 条）")
        return Result.success(Outcome(entry, reused = false, notes = notes))
    }

    /* ============================================================
     * 解压
     * ============================================================ */

    private fun extract(context: Context, uri: Uri, target: File) {
        val input = context.contentResolver.openInputStream(uri)
            ?: error("无法读取所选文件")

        input.use { stream ->
            ZipInputStream(stream.buffered()).use { zip ->
                var count = 0
                var total = 0L

                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (++count > MAX_FILES) error("压缩包里文件太多（超过 $MAX_FILES 个）")

                    val out = safeTarget(target, entry.name)
                    if (entry.isDirectory) {
                        out.mkdirs()
                        zip.closeEntry()
                        continue
                    }

                    out.parentFile?.mkdirs()
                    out.outputStream().use { fileOut ->
                        val buffer = ByteArray(64 * 1024)
                        var entryBytes = 0L
                        while (true) {
                            val read = zip.read(buffer)
                            if (read <= 0) break
                            entryBytes += read
                            total += read
                            if (entryBytes > MAX_ENTRY_BYTES) error("压缩包里单个文件过大")
                            if (total > MAX_TOTAL_BYTES) error("模型体积过大（超过 512MB）")
                            fileOut.write(buffer, 0, read)
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
    }

    /** 解压目标路径必须落在 [root] 内，防止 zip 里的 `../` 写出去 */
    private fun safeTarget(root: File, name: String): File {
        val file = File(root, name).canonicalFile
        require(file.path.startsWith(root.canonicalPath + File.separator)) {
            "压缩包内路径非法：$name"
        }
        return file
    }

    /** 解析模型引用（相对路径），同样要求落在 [root] 内；越界返回 null */
    private fun resolveInside(base: File, relative: String, root: File): File? {
        if (relative.isBlank()) return null
        return runCatching {
            val file = File(base, relative).canonicalFile
            file.takeIf { it.path.startsWith(root.canonicalPath + File.separator) }
        }.getOrNull()
    }

    /* ============================================================
     * 生成页面
     * ============================================================ */

    /**
     * 写 `modeldata.js`：把运行时需要的四样东西交给页面。
     *
     * moc3 以 base64 分块写入（见类注释），贴图用相对路径由 `<img>` 自己加载。
     */
    private fun writeModelData(
        dir: File,
        moc: File,
        textures: List<File>,
        resourceDir: File?,
        layout: String,
    ) {
        val texturePaths = JSONArray().apply {
            textures.forEach { put(relativePath(dir, it)) }
        }
        // 自带资源包时用相对路径（跟着模型目录走），否则回退到内置键盘资源
        val resourceBase = resourceDir
            ?.let { "./" + relativePath(dir, it) }
            ?: ASSET_FALLBACK_RESOURCES

        File(dir, Live2DModels.ENTRY_MODEL_DATA).outputStream().writer(Charsets.UTF_8).use { out ->
            out.write("window.__BONGO_KEYBOARD_MOC_BASE64=\"")
            writeBase64(moc, out)
            out.write("\";\n")

            out.write("window.__BONGO_TEXTURES=${texturePaths};\n")
            out.write("window.__BONGO_RESOURCE_BASE=${JSONObject.quote(resourceBase)};\n")
            out.write("window.__BONGO_IMPORTED_MODEL=true;\n")
            out.write("window.__BONGO_MODEL_LAYOUT=${JSONObject.quote(layout)};\n")
        }
    }

    /**
     * 把文件内容以 base64 追加写入。
     *
     * 除最后一块外，每块都必须是 [BASE64_CHUNK_BYTES]（3 的整数倍）——
     * base64 每 3 字节编码成 4 个字符，块边界不对齐就会拼出无效结果。
     * 因此这里用 [readFully] 而不是普通的 `read()`：后者可能提前返回。
     */
    private fun writeBase64(file: File, out: Writer) {
        val buffer = ByteArray(BASE64_CHUNK_BYTES)
        file.inputStream().use { input ->
            while (true) {
                val read = input.readFully(buffer)
                if (read <= 0) break
                val slice = if (read == buffer.size) buffer else buffer.copyOf(read)
                out.write(Base64.encodeToString(slice, Base64.NO_WRAP))
            }
        }
    }

    /** 尽量填满缓冲区；只有到流末尾才会返回小于 buffer.size 的值 */
    private fun java.io.InputStream.readFully(buffer: ByteArray): Int {
        var filled = 0
        while (filled < buffer.size) {
            val read = read(buffer, filled, buffer.size - filled)
            if (read < 0) break
            filled += read
        }
        return filled
    }

    /** 相对路径，分隔符统一成 `/`（写进 JS 里要跨平台一致） */
    private fun relativePath(root: File, file: File): String =
        file.relativeTo(root).invariantSeparatorsPath

    /**
     * 找模型自带的 BongoCat 键帽资源目录。
     *
     * 判定条件与旧项目一致：有 `background.png`，且另外还有 `cover.png`
     * 或 `left-keys/` / `right-keys/` 之一 —— 只靠一张 png 容易误判成贴图目录。
     */
    private fun findResourceDir(dir: File): File? = dir.walkTopDown()
        .filter { it.isDirectory }
        .firstOrNull { candidate ->
            File(candidate, RESOURCE_MARKER).isFile && (
                File(candidate, "cover.png").isFile ||
                    File(candidate, "left-keys").isDirectory ||
                    File(candidate, "right-keys").isDirectory
                )
        }

    /* ============================================================
     * 皮肤包补齐
     * ============================================================ */

    /**
     * 键帽别名：运行时按**具体名字**（`ShiftLeft`）去取图，而模型作者常常
     * 只画一张通用的（`Shift.png`）。找不到时运行时会回退到**内置资源**，
     * 于是那个键以内置布局的错位图出现 —— 这是实际遇到过的问题。
     */
    private val KEYCAP_ALIASES: Map<String, List<String>> = mapOf(
        "ShiftLeft" to listOf("Shift"),
        "ShiftRight" to listOf("Shift"),
        "ControlLeft" to listOf("Control"),
        "ControlRight" to listOf("Control"),
    )

    /**
     * 这个皮肤包**真正提供**（不是透明占位）的键帽名。
     *
     * ============================================================
     * 为什么要算这个
     * ============================================================
     * 运行时每次按键都会调 `syncHandOverrides()`，把 `CatParamLeftHandDown` /
     * `CatParamRightHandDown` 置 1 —— 也就是说**任何**键都会让猫的手动一下，
     * 跟这个皮肤包画了什么键帽毫无关系。
     *
     * 于是一个只画了 5 个键的小键盘包，按 A 也会看见手往下按。
     * 所以要把"这个包真有哪些键"告诉上层，让它**只转发这些键**。
     *
     * ⚠️ 必须排除透明占位图：那是我为了阻止"回退到内置美术"而写进去的
     * （见 [fillMissingKeycaps]），它们**不代表**这个包支持那个键。
     * 判定方式是**逐字节比对**，而不是看文件大小 —— 大小是启发式，会漏。
     */
    fun providedKeycaps(resourceDir: File): Set<String> {
        val provided = mutableSetOf<String>()
        Live2DSemantics.Side.entries.forEach { side ->
            val dirName = if (side == Live2DSemantics.Side.LEFT) "left-keys" else "right-keys"
            val dir = File(resourceDir, dirName)
            if (!dir.isDirectory) return@forEach

            dir.listFiles().orEmpty().forEach { file ->
                if (!file.isFile || !file.name.endsWith(".png", ignoreCase = true)) return@forEach
                val isPlaceholder = file.length() == TRANSPARENT_PNG.size.toLong() &&
                    runCatching { file.readBytes().contentEquals(TRANSPARENT_PNG) }
                        .getOrDefault(false)
                if (!isPlaceholder) provided += file.nameWithoutExtension
            }
        }
        return provided
    }

    /**
     * 把皮肤包缺的键帽文件补上：能用别名的就复制，实在没有的补一张**全透明**占位图。
     *
     * 为什么必须补而不是"让它回退"：运行时的回退目标是**内置资源目录**，
     * 那套键帽画在内置布局的位置上。皮肤包少画一个键，那个键就会以
     * "错位的键帽"形式冒出来 —— 比什么都不显示更让人困惑。
     *
     * ⚠️ 只补**已经存在**的那一侧目录：如果一个包连 `left-keys/` 都没有，
     * 说明作者本意就是沿用内置键盘，不该给他塞一堆透明图。
     */
    private fun fillMissingKeycaps(resourceDir: File) {
        var filled = 0
        Live2DSemantics.Side.entries.forEach { side ->
            val dirName = if (side == Live2DSemantics.Side.LEFT) "left-keys" else "right-keys"
            val dir = File(resourceDir, dirName)
            if (!dir.isDirectory) return@forEach

            Live2DSemantics.keycapNames(side).forEach { name ->
                val target = File(dir, "$name.png")
                if (target.isFile) return@forEach

                val alias = KEYCAP_ALIASES[name]
                    ?.map { File(dir, "$it.png") }
                    ?.firstOrNull { it.isFile }
                if (alias != null) {
                    runCatching { alias.copyTo(target, overwrite = true) }
                } else {
                    runCatching { target.writeBytes(TRANSPARENT_PNG) }
                }
                filled++
            }
        }
        if (filled > 0) AppLog.i(TAG, "皮肤包补齐键帽 $filled 个（别名复制或透明占位）")
    }

    /**
     * 1×1 全透明 PNG，用来占位。
     *
     * **按 PNG 规范现场构造**，而不是内嵌一段 base64 字符串：
     * 那种字符串一旦抄错（一个字符就够）会得到一个坏文件，而报错只会出现在
     * 浏览器的图片加载里，排查成本远高于这二十行代码。
     */
    private val TRANSPARENT_PNG: ByteArray by lazy {
        val ihdr = byteArrayOf(
            0, 0, 0, 1, // 宽 1
            0, 0, 0, 1, // 高 1
            8, // 位深
            6, // 颜色类型 6 = RGBA
            0, 0, 0, // 压缩/滤波/隔行：均为默认
        )
        val raw = byteArrayOf(0, 0, 0, 0, 0) // 每行前置滤波字节 + RGBA(0,0,0,0)

        val deflater = java.util.zip.Deflater()
        deflater.setInput(raw)
        deflater.finish()
        val buffer = ByteArray(64)
        val size = deflater.deflate(buffer)
        deflater.end()
        val idat = buffer.copyOf(size)

        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        out.write(pngChunk("IHDR", ihdr))
        out.write(pngChunk("IDAT", idat))
        out.write(pngChunk("IEND", ByteArray(0)))
        out.toByteArray()
    }

    /** PNG 数据块：长度 + 类型 + 数据 + CRC32（按规范，长度与 CRC 都是大端） */
    private fun pngChunk(type: String, data: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val crc = java.util.zip.CRC32()
        crc.update(typeBytes)
        crc.update(data)

        val out = java.io.ByteArrayOutputStream()
        out.write(beInt(data.size))
        out.write(typeBytes)
        out.write(data)
        out.write(beInt(crc.value.toInt()))
        return out.toByteArray()
    }

    private fun beInt(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    /* ============================================================
     * 兼容性检查
     * ============================================================ */

    /**
     * 检查模型有没有"手部参数"。
     *
     * 依据是模型自带的 `DisplayInfo`（`.cdi3.json`，里面列了全部参数 id）。
     * 没有这个文件时**不做判断** —— 猜不出来就别乱报，宁可不说。
     *
     * 为什么值得做：任意一个 Live2D 角色都能被鼠标驱动（角度/眼球参数很普遍），
     * 但没有 `CatParamLeftHandDown` / `CatParamRightHandDown` 时**按键不会有反应**。
     * 不提示的话，用户导入完按键盘没动静，只会以为功能坏了。
     */
    private fun compatibilityNotes(
        modelJson: JSONObject,
        modelBase: File,
        root: File,
    ): List<String> {
        val displayInfo = modelJson.optJSONObject("FileReferences")
            ?.optString("DisplayInfo")
            .orEmpty()
        if (displayInfo.isBlank()) return emptyList()

        val file = resolveInside(modelBase, displayInfo, root) ?: return emptyList()
        if (!file.isFile) return emptyList()

        val ids = runCatching {
            val json = JSONObject(file.readText())
            val parameters = json.optJSONArray("Parameters") ?: return emptyList()
            buildList {
                for (index in 0 until parameters.length()) {
                    val id = parameters.optJSONObject(index)?.optString("Id").orEmpty()
                    if (id.isNotEmpty()) add(id)
                }
            }
        }.getOrNull() ?: return emptyList()

        if (ids.isEmpty()) return emptyList()

        val missing = HAND_PARAMS.filterNot { it in ids }
        return if (missing.isEmpty()) {
            emptyList()
        } else {
            listOf(
                "这个模型缺少手部参数（${missing.joinToString("、")}），" +
                    "按键盘时手不会动；头与眼球仍会跟随鼠标。",
            )
        }
    }

    /* ============================================================
     * 摘要与杂项
     * ============================================================ */

    /**
     * 目录摘要：所有文件按相对路径排序，把「路径 + 内容摘要」依次喂进一个总摘要。
     *
     * 为什么不是"只摘要 model3.json"：那个文件很小，两个不同模型完全可能共用同一份
     * （比如同一个角色换贴图），只摘要它就会把不同模型判成同一个。
     */
    private fun computeDigest(dir: File): String {
        val files = dir.walkTopDown()
            .filter { it.isFile }
            .sortedBy { relativePath(dir, it) }

        val digest = MessageDigest.getInstance("SHA-256")
        files.forEach { file ->
            digest.update(relativePath(dir, file).toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(sha256Of(file))
            digest.update('\n'.code.toByte())
        }
        return digest.digest().toHex()
    }

    /** 单文件摘要；流式读，不把文件整个读进内存 */
    private fun sha256Of(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun queryDisplayName(context: Context, uri: Uri): String =
        runCatching {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull() ?: "live2d-model"

    /**
     * 生成导入模型的页面骨架。
     *
     * 与旧项目的 CUSTOM_HTML 一致：只放图层与三个 script，
     * 渲染与状态机全在 `keyboard/runtime.js` 里，模型数据由 `modeldata.js` 提供。
     *
     * ============================================================
     * 唯一的差别：只有角色的模型要压掉"内置键盘美术"
     * ============================================================
     * `runtime.js` 第 101 行**无条件**加载 `background.png`，
     * 而 `setImageWithFallback` 在取不到时会**回退到内置资源目录**
     * （`file:///android_asset/bongocat/keyboard/resources`）。
     * 也就是说：一个只带角色的模型，画面上会凭空多出 BongoCat 的键盘底图，
     * 按键时还会显示内置键帽 —— 而 `MODEL_LAYOUT` 并不控制这些
     * （它只在 `full` 布局里参与缩放计算）。
     *
     * 旧项目就是这个行为。对"我只想导入一个角色"这个用法它是错的，
     * 所以在生成的页面里加一段 CSS 把它们压掉。
     *
     * ⚠️ 必须用 `!important`：runtime 显示键帽时走的是
     * `image.classList.remove('hidden')`，普通样式压不住。
     */
    private fun buildHtml(layout: String): String {
        val layoutCss = if (layout == Live2DModels.LAYOUT_CAT) {
            /*
             * 只有角色的模型：连 #fallback 一起藏掉。
             *
             * #fallback 是 cover.png，而它的来源同样是**内置资源目录** ——
             * 这就是"打开悬浮窗先闪出一只默认 BongoCat、过一会儿才换成自己的角色"
             * 的原因（模型加载期间回退图一直在显示，加载完才 hidden）。
             * 藏掉之后加载期间窗口是全透明的，不会出现误导性的画面。
             */
            "#background, #fallback, #leftKey, #rightKey { display: none !important; }"
        } else {
            ""
        }

        return """<!doctype html>
<html>
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
    <style>
        html, body {
            margin: 0;
            width: 100%;
            height: 100%;
            overflow: hidden;
            background: transparent;
            touch-action: none;
            -webkit-user-select: none;
            user-select: none;
        }
        #stage {
            position: relative;
            width: 100%;
            height: 100%;
            overflow: hidden;
            background: transparent;
        }
        .layer {
            position: absolute;
            inset: 0;
            width: 100%;
            height: 100%;
            display: block;
        }
        #background, #leftKey, #rightKey, #fallback {
            object-fit: fill;
            pointer-events: none;
        }
        #background { z-index: 0; }
        #fallback { z-index: 1; }
        #live2dCanvas { z-index: 2; }
        #leftKey, #rightKey { z-index: 3; }
        .hidden { display: none !important; }

        /* 由 buildHtml 按布局注入；只有角色的模型在这里压掉内置键盘美术 */
        $layoutCss
    </style>
</head>
<body>
<div id="stage">
    <img id="background" class="layer">
    <img id="fallback" class="layer">
    <canvas id="live2dCanvas" class="layer"></canvas>
    <img id="leftKey" class="layer hidden">
    <img id="rightKey" class="layer hidden">
</div>
<script src="$ASSET_CORE_JS"></script>
<script src="./${Live2DModels.ENTRY_MODEL_DATA}"></script>
<script src="$ASSET_RUNTIME_JS"></script>
</body>
</html>
"""
    }
}

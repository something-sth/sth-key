package com.something.sthkey.data.live2d

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.something.sthkey.core.log.AppLog
import com.something.sthkey.domain.live2d.Live2DJunkFilter
import com.something.sthkey.domain.live2d.Live2DModelEntry
import com.something.sthkey.domain.live2d.Live2DModels
import com.something.sthkey.domain.live2d.Live2DSemantics
import com.something.sthkey.domain.live2d.Live2DVariant
import com.something.sthkey.domain.live2d.Model3Selection
import java.io.File
import java.io.Writer
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import org.json.JSONArray
import org.json.JSONObject



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
    /**
     * @param displayNameHint 已知的显示名（外部导入时由调用方给）。
     *   ⚠️ 外部「打开方式」这条路传进来的是**我们自己的缓存文件**，
     *   `queryDisplayName` 查不到 `DISPLAY_NAME`，于是会落到兜底的
     *   `live2d-model` —— 用户在库里看到的是一堆同名模型。
     *   所以调用方必须把原文件名传进来。
     */
    fun importZip(
        context: Context,
        uri: Uri,
        displayNameHint: String? = null,
    ): Result<Outcome> {
        val dirId = UUID.randomUUID().toString().replace("-", "")
        // 目录名只在模型库里定义一处，这里不自己拼路径
        val dir = File(Live2DModels.importRoot(context), dirId)

        return try {
            dir.mkdirs()
            val skippedJunk = extract(context, uri, dir)
            /*
             * 显示名：优先用调用方给的（外部导入），否则取 SAF 给的文件名。
             * 两者都取到 .zip 后缀，统一去掉。
             */
            val displayName = (displayNameHint?.takeIf { it.isNotBlank() }
                ?: queryDisplayName(context, uri))
                .substringBeforeLast('.')
                .ifBlank { "导入的模型" }
            finishImport(dir, dirId, displayName, skippedJunk)
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

            /*
             * ⚠️ 多模式模型:每套模式各有一份页面，都要刷。
             * 只刷根目录那份的话，切到"标准"或"手柄"时看到的还是旧页面骨架。
             */
            val pages = if (model.variants.isEmpty()) {
                listOf(dir to model.layout)
            } else {
                model.variants.map { variant ->
                    val root = if (variant.pageDir.isEmpty()) dir else File(dir, variant.pageDir)
                    root to if (isResourceDir(root)) Live2DModels.LAYOUT_FULL else Live2DModels.LAYOUT_CAT
                }
            }

            pages.forEach { (root, layout) ->
                val file = File(root, Live2DModels.ENTRY_INDEX)
                val expected = buildHtml(layout)
                if (runCatching { file.readText() }.getOrNull() != expected) {
                    runCatching { file.writeText(expected) }
                        .onSuccess { AppLog.i(TAG, "已更新模型页面：${model.displayName}") }
                        .onFailure { AppLog.w(TAG, "更新模型页面失败：${model.displayName}") }
                }
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
        /** 解压时被丢掉的无关文件数（只用于给用户的提示） */
        skippedJunk: Int = 0,
    ): Result<Outcome> {
        /*
         * 1. 找模型描述文件。
         *
         * ⚠️⚠️ **不能取遍历到的第一个** —— 见 [Model3Selection] 的说明。
         *
         * 实测样本 `鸣潮卡提希娅最牛逼键盘猫模型` 的结构:
         * ```
         * img/cat.model3.json                 ← 最浅，但引用的 moc/贴图都不存在（孤儿）
         * img/keyboard/cat_model/cat.model3.json  ← 完整
         * img/standard/…  img/gamepad/…           ← 另外两套完整变体
         * ```
         * 取第一个的结果是:**导入成败取决于文件系统遍历顺序**，
         * 而且即使成功也是随机命中三套变体之一（键帽资源各不相同）。
         */
        val scan = chooseModel3(dir)
        val choice = scan.choice
        val modelJsonFile = when (choice) {
            is Model3Selection.Choice.Picked -> File(dir, choice.path)

            Model3Selection.Choice.NoModel3 -> return Result.failure(
                IllegalStateException("这个压缩包里没有 .model3.json，不是 Live2D 模型包"),
            )

            is Model3Selection.Choice.AllBroken -> return Result.failure(
                IllegalStateException(
                    "找到 ${choice.total} 个 .model3.json，但资源都不完整:\n" +
                        choice.reasons.joinToString("\n") { "· $it" },
                ),
            )
        }

        /*
         * 2.0 **多模式模型**:一个包里有多套完整模型时，各建一套页面。
         *
         * ⚠️ 用户的原话:
         * > 这个模型有三种模式……而软件内，导入的模型不能切换模式
         * > 做 live2d 库里展开的话，无论选择多模式模型的哪一种模式，
         * > 导出时都是这个模型全部导出，不是单独导出某个模式
         *
         * ⚠️ 所以导出**不做"只带选中变体"的裁剪** —— 整个模型目录都会被打包
         * （见 `ConfigPackageManager.writeModel`，它扫的就是整个 `entry.dir`）。
         */
        if (scan.picks.size > 1) {
            return importVariants(
                dir = dir,
                dirId = dirId,
                displayName = displayName,
                scan = scan,
                skippedJunk = skippedJunk,
            )
        }

        val modelJson = runCatching { JSONObject(modelJsonFile.readText()) }.getOrNull()
            ?: return Result.failure(IllegalStateException("模型描述文件无法解析（.model3.json 格式不对）"))

        val refs = modelJson.optJSONObject("FileReferences")
            ?: return Result.failure(IllegalStateException("模型描述文件里没有 FileReferences"))

        /*
         * 2. 校验 moc 与贴图。
         *
         * ⚠️ 这里**只做"能不能画"的最低校验**（选 model3 的时候已经用
         * [validateModel3] 校验过一遍了）。两处都留着的理由:
         * - 选 model3 那次是为了**在多个候选里挑一个能用的**；
         * - 这一次是为了给**用户**一句准确的话（缺哪个文件）。
         * 两次用的是同一套判据，不会出现"选了它又说它不行"。
         */
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

        /*
         * 2.5 超大贴图降采样。
         *
         * ⚠️ 必须在**摘要之前**做:摘要要反映"真正入库的内容"，
         * 否则同一个包在不同机型上（一个降了、一个没降）算出来的摘要不同。
         *
         * ⚠️ 也必须在这里（而不是渲染时）:渲染在 WebView 里，
         * 8192² 的纹理**根本传不上去**（很多机器 GL_MAX_TEXTURE_SIZE = 4096），
         * 那时已经没有"缩小"的机会了 —— 只能白屏。
         */
        val textureAdaptation = runCatching {
            Live2DTextureAdapter.adapt(modelBase, refs)
        }.onFailure {
            /* ⚠️ 降采样失败不该让整个导入失败:原图可能本来就能显示 */
            AppLog.w(TAG, "贴图降采样失败（保留原图）：${it.javaClass.simpleName}")
        }.getOrNull()

        /*
         * ⚠️ 降采样之后**重新校验一遍**（Axon 也这么做）:重编码是我们自己做的，
         * 万一某张图写坏了，必须在这里发现 —— 而不是等用户在屏幕上看到半个模型。
         */
        if (textureAdaptation != null) {
            val broken = validateModel3(dir, modelJsonFile)
            if (broken != null) {
                return Result.failure(IllegalStateException("贴图降采样后模型校验失败:$broken"))
            }
        }

        /* 3. 内容摘要 —— 命中已有模型就直接复用 */
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
        /* ⚠️ Mver 风格包（自带逐键 PNG 手）不能去收模型手臂，见 writeModelData 的说明 */
        val expressions = loadExpressions(modelJson, modelBase, dir)
        writeModelData(
            dir = dir,
            moc = moc,
            textures = textures,
            resourceDir = resourceDir,
            layout = layout,
            mverHands = hasMverHands(dir),
            expressions = expressions,
            exprBindings = loadExpressionBindings(dir, null, expressions.length()),
        )
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
            /*
             * 多变体包:告诉用户"选的是哪一套"。
             *
             * ⚠️ 不说的话用户会以为"我明明有 gamepad 版，怎么画出来是键盘"。
             * 实测样本里就有 keyboard / standard / gamepad 三套。
             */
            if (choice is Model3Selection.Choice.Picked && choice.total > 1) {
                val variantName = choice.variant ?: "其中一套"
                add("这个压缩包里带 ${choice.total} 套模型，已导入 $variantName 那一套。")
            }
            /*
             * 贴图被降采样时说清楚:用户会问"我的模型怎么不如电脑上清楚"。
             *
             * ⚠️ 这不是我们"优化"的结果，而是手机的硬限制:
             * 8192 的纹理在多数机型上根本上传不了（GL 上限 4096）。
             */
            textureAdaptation?.let { adapted ->
                add(
                    "这个模型的贴图对手机来说太大了，已把 ${adapted.count} 张贴图" +
                        "缩小到 1/${adapted.sample}（否则会白屏或只有部分部件显示）。",
                )
            }
            if (skippedJunk > 0) {
                add("已忽略 $skippedJunk 个无关文件（exe / dll / 说明文档等）。")
            }
        }
        AppLog.i(TAG, "模型导入成功：${entry.displayName}（布局 $layout，提示 ${notes.size} 条）")
        return Result.success(Outcome(entry, reused = false, notes = notes))
    }

    /* ============================================================
     * 选 model3
     * ============================================================ */

    /**
     * 把目录里所有候选 `*.model3.json` 找出来、逐个校验，再交给
     * [Model3Selection.choose] 排序决策。
     *
     * ⚠️ 校验在这里做（要读文件），**排序与决策在纯函数里做**（可单测）。
     *
     * ⚠️ 深度上限与 Axon 一致（5 层）:太深的多半是压缩包里套的备份目录，
     * 扫下去只会变慢，还可能命中模型作者自己放的历史版本。
     */
    private fun chooseModel3(root: File): Scan {
        val candidates = mutableListOf<Model3Selection.Candidate>()

        root.walkTopDown()
            .maxDepth(Model3Selection.MAX_DEPTH)
            .filter { it.isFile && it.name.endsWith(".model3.json", ignoreCase = true) }
            .forEach { file ->
                val relative = relativePath(root, file)
                val depth = relative.split('/').size - 1
                val reason = validateModel3(root, file)
                candidates += Model3Selection.Candidate(
                    path = relative,
                    depth = depth,
                    valid = reason == null,
                    reason = reason,
                )
                if (reason != null) {
                    AppLog.i(TAG, "跳过候选 $relative：$reason")
                }
            }

        val choice = Model3Selection.choose(candidates)
        val picks = Model3Selection.chooseAll(candidates)
        when (choice) {
            is Model3Selection.Choice.Picked -> AppLog.i(
                TAG,
                "选中 ${choice.path}（候选 ${choice.total} 个，有效 ${picks.size} 个，" +
                    "变体 ${choice.variant ?: "未知"}）",
            )

            Model3Selection.Choice.NoModel3 -> AppLog.w(TAG, "包内没有 .model3.json")

            is Model3Selection.Choice.AllBroken ->
                AppLog.w(TAG, "全部候选都不可用：${choice.reasons.joinToString("；")}")
        }
        return Scan(choice = choice, picks = picks)
    }

    /**
     * 扫描结果。
     *
     * @param choice 决策（用于报错文案与"选中哪一个"）
     * @param picks **所有有效候选**，已按同一套规则排序（第一个 = [choice] 选中的那个）。
     *   多于一个时说明是**多模式模型**，导入器会给它们各建一套页面。
     */
    private data class Scan(
        val choice: Model3Selection.Choice,
        val picks: List<Model3Selection.Picked>,
    )

    /**
     * 校验一个 model3 能不能用。
     *
     * @return `null` = 可用；否则是**给人看**的原因
     *
     * ⚠️ 判据照 Axon 的 `validateModel3`:
     * `Version >= 3`、`Moc` 存在、`Textures` 逐个存在、
     * 以及**声明了的** `Physics` / `Pose` / `DisplayInfo` 必须存在。
     *
     * ⚠️ 最后这三个是可选项，但"声明了却不在"是真的坏包:
     * 运行时会去找那个文件，找不到就报错 —— 与其让它渲染到一半炸，
     * 不如在导入时换下一套候选（这也正是多变体包的救法）。
     */
    private fun validateModel3(root: File, modelFile: File): String? {
        val json = runCatching { JSONObject(modelFile.readText()) }.getOrNull()
            ?: return "model3 无法解析"

        val version = json.optInt("Version", 0)
        if (version < 3) return "仅支持 Cubism 3 以上的模型（Version=$version）"

        val refs = json.optJSONObject("FileReferences") ?: return "缺少 FileReferences"
        val modelBase = modelFile.parentFile ?: return "model3 没有所在目录"

        val mocRelative = refs.optString("Moc").trim()
        if (mocRelative.isEmpty()) return "缺少 Moc"
        val moc = resolveInside(modelBase, mocRelative, root) ?: return "Moc 路径非法: $mocRelative"
        if (!moc.isFile || moc.length() < 4L) return "Moc 不存在或为空: $mocRelative"

        val textures = refs.optJSONArray("Textures") ?: return "缺少 Textures"
        if (textures.length() == 0) return "Textures 为空"
        for (index in 0 until textures.length()) {
            val relative = textures.optString(index).trim()
            if (relative.isEmpty()) return "存在空纹理路径"
            val file = resolveInside(modelBase, relative, root) ?: return "纹理路径非法: $relative"
            if (!file.isFile) return "纹理不存在: $relative"
        }

        /* ⚠️ 声明了就必须在（见上面的说明） */
        for (key in listOf("Physics", "Pose", "DisplayInfo")) {
            val relative = refs.optString(key).trim()
            if (relative.isEmpty()) continue
            val file = resolveInside(modelBase, relative, root) ?: return "$key 路径非法: $relative"
            if (!file.isFile) return "$key 不存在: $relative"
        }

        return null
    }

    /* ============================================================
     * 多模式导入
     * ============================================================ */

    /**
     * 一个变体最终落在哪里、用什么布局。
     *
     * @param name 给人看的模式名（`键盘` / `标准` / `手柄`）
     * @param pageDir 页面目录**相对模型根**的路径（写入模型库，切换模式时用）
     */
    private data class VariantPage(
        /** 这套模式的 model3 文件 */
        val modelFile: File,
        val name: String,
        val root: File,
        val pageDir: String,
        val layout: String,
        /** 包配置里对应的段名（`keyboard` / `standard` / `gamepad`） */
        val mode: String? = null,
    ) {
        /** 包配置里的段名；认不出来返回 null（`loadExpressionBindings` 会兜底） */
        fun configMode(): String? = mode
    }

    /**
     * 导入一个**多模式模型**:每套模式各写一套页面，登记成一个模型（可切换）。
     *
     * ⚠️ 为什么页面写在"变体自己的目录"里:
     * 生成页面时贴图路径是**相对页面目录**写的（`cat_model/cat.8192/texture_00.png`），
     * 键帽资源也是同级目录。把页面放在变体根目录，这些相对路径天然成立；
     * 若统一放在包根，就得写一堆 `../..`，`file://` 下很容易出错。
     *
     * ⚠️ 摘要仍然按**整个包**算（与单模式一致）:
     * 多模式是一个模型，不该因为"这次只看了键盘那套"就变两个模型。
     */
    private fun importVariants(
        dir: File,
        dirId: String,
        displayName: String,
        scan: Scan,
        skippedJunk: Int,
    ): Result<Outcome> {
        val plan = scan.picks.map { pick ->
            val modelFile = File(dir, pick.path)
            val modelBase = modelFile.parentFile ?: dir
            /*
             * 变体根目录:
             * 1. 优先找"带 BongoCat 键帽资源标记"的祖先目录（标准社区包）；
             * 2. 否则取 model3 的**父目录的父目录**（`<变体>/cat_model/cat.model3.json`
             *    是这个生态的固定结构），再兜底到 model3 所在目录。
             */
            val markerRoot = ancestorWithResourceMarker(modelBase, dir)
            val root = markerRoot ?: modelBase.parentFile?.takeIf { it.path.length > dir.path.length } ?: modelBase
            val layout = if (markerRoot != null) Live2DModels.LAYOUT_FULL else Live2DModels.LAYOUT_CAT
            VariantPage(
                name = variantLabel(pick.variant, root.name),
                mode = pick.variant,
                modelFile = modelFile,
                root = root,
                pageDir = relativePath(dir, root),
                layout = layout,
            )
        }.distinctBy { it.pageDir }

        if (plan.size <= 1) {
            /* 万一所有变体都落在同一个目录（奇怪但可能），退回单模式逻辑 */
            AppLog.w(TAG, "多变体候选落在同一目录，按单模式导入")
            return Result.failure(IllegalStateException("这个模型的多套模式在同一个目录里，无法分开"))
        }

        /* ① 先算摘要并去重 —— 同一份包不该占两份空间 */
        val digest = computeDigest(dir)
        Live2DModels.findByDigest(digest)?.let { existing ->
            AppLog.i(TAG, "模型内容与已有模型一致，直接复用：${existing.displayName}")
            dir.deleteRecursively()
            return Result.success(Outcome(existing, reused = true, notes = emptyList()))
        }

        /* ② 逐套建页面 */
        val supportedKeys = mutableSetOf<String>()
        var adaptedCount = 0
        var adaptedSample = 1

        plan.forEach { variant ->
            val modelFile = variant.modelFile
            val json = runCatching { JSONObject(modelFile.readText()) }.getOrNull()
            val refs = json?.optJSONObject("FileReferences")
            if (json == null || refs == null) {
                AppLog.w(TAG, "变体 ${variant.name} 的描述文件不可用，跳过")
                return@forEach
            }
            val modelBase = modelFile.parentFile ?: return@forEach

            /* 贴图降采样:每套各自算（三套的贴图可能大小不同） */
            runCatching { Live2DTextureAdapter.adapt(modelBase, refs) }
                .onFailure { AppLog.w(TAG, "变体 ${variant.name} 贴图降采样失败：${it.javaClass.simpleName}") }
                .getOrNull()
                ?.let { adapted ->
                    adaptedCount += adapted.count
                    adaptedSample = maxOf(adaptedSample, adapted.sample)
                }

            val moc = resolveInside(modelBase, refs.optString("Moc"), dir) ?: return@forEach
            val textures = buildList {
                val array = refs.optJSONArray("Textures") ?: return@buildList
                for (index in 0 until array.length()) {
                    resolveInside(modelBase, array.optString(index), dir)?.takeIf { it.isFile }?.let { add(it) }
                }
            }
            if (textures.isEmpty()) {
                AppLog.w(TAG, "变体 ${variant.name} 没有可用贴图，跳过")
                return@forEach
            }

            val resourceDir = if (variant.layout == Live2DModels.LAYOUT_FULL) variant.root else null
            if (resourceDir != null) {
                fillMissingKeycaps(resourceDir)
                supportedKeys += providedKeycaps(resourceDir)
            }

            val variantExpressions = loadExpressions(json, modelBase, dir)
            writeModelData(
                dir = variant.root,
                moc = moc,
                textures = textures,
                resourceDir = resourceDir,
                layout = variant.layout,
                /* ⚠️ Mver 风格包不能收模型手臂，见 writeModelData 的说明 */
                mverHands = hasMverHands(variant.root),
                expressions = variantExpressions,
                exprBindings = loadExpressionBindings(
                    packageRoot = dir,
                    /* 包配置按模式分段（keyboard / standard / gamepad） */
                    mode = variant.configMode(),
                    expressionCount = variantExpressions.length(),
                ),
            )
            File(variant.root, Live2DModels.ENTRY_INDEX).writeText(buildHtml(variant.layout))
            AppLog.i(TAG, "已生成变体页面：${variant.name}（${variant.pageDir}）")
        }

        /* ③ 登记（一个模型，多个模式） */
        val entry = Live2DModels.registerImported(
            dirId = dirId,
            displayName = displayName,
            digest = digest,
            layout = plan.first().layout,
            supportedKeys = supportedKeys,
            variants = plan.map { Live2DVariant(name = it.name, pageDir = it.pageDir) },
        )

        val notes = buildList {
            add(
                "这个模型带 ${plan.size} 套模式（${plan.joinToString(" / ") { it.name }}），" +
                    "已选用「${plan.first().name}」。可在「库 → Live2D」里切换。",
            )
            if (skippedJunk > 0) {
                add("已忽略 $skippedJunk 个无关文件（exe / dll / 说明文档等）。")
            }
            if (adaptedCount > 0) {
                add("贴图对手机来说太大了，已把 $adaptedCount 张贴图缩小到 1/$adaptedSample。")
            }
        }
        AppLog.i(TAG, "多模式模型导入成功：${entry.displayName}（${plan.size} 套）")
        return Result.success(Outcome(entry, reused = false, notes = notes))
    }

    /** 从 [start] 往上找第一个"带 BongoCat 键帽资源标记"的目录（不超过 [stopAt]） */
    private fun ancestorWithResourceMarker(start: File, stopAt: File): File? {
        var current: File? = start
        val limit = stopAt.canonicalPath.length
        while (current != null && current.canonicalPath.length >= limit) {
            if (isResourceDir(current)) return current
            current = current.parentFile
        }
        return null
    }

    /** 这个目录像不像 BongoCat 的键帽资源目录（判据与 [findResourceDir] 一致） */
    private fun isResourceDir(dir: File): Boolean =
        File(dir, RESOURCE_MARKER).isFile && (
            File(dir, "cover.png").isFile ||
                File(dir, "left-keys").isDirectory ||
                File(dir, "right-keys").isDirectory
        )

    /** 变体目录名 → 用户看得懂的模式名 */
    private fun variantLabel(variant: String?, dirName: String): String = when (variant) {
        "keyboard" -> "键盘"
        "standard" -> "标准"
        "gamepad" -> "手柄"
        else -> variant ?: dirName
    }

    /* ============================================================
     * 解压
     * ============================================================ */

    /**
     * 解压到 [target]。
     *
     * @return 被丢掉的**无关文件**数量（exe / dll / 说明文档等），用于给用户提示
     */
    private fun extract(context: Context, uri: Uri, target: File): Int {
        val input = context.contentResolver.openInputStream(uri)
            ?: error("无法读取所选文件")

        input.use { stream ->
            ZipInputStream(stream.buffered()).use { zip ->
                var count = 0
                var total = 0L
                var skipped = 0

                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (++count > MAX_FILES) error("压缩包里文件太多（超过 $MAX_FILES 个）")

                    /*
                     * ⚠️ **无关文件直接跳过，不落盘**。
                     *
                     * 这些包本来就是 Windows 版 BongoCat 的发行包:
                     * 实测样本里有 2 个 exe + 13 个 dll + docx + psd，约 24 MB。
                     * 以前它们会一起进模型库，导出配置时再被打进 `.sthkey` 带出去。
                     *
                     * ⚠️ 跳过的**内容仍然要读掉**（`closeEntry` 之前必须消费完），
                     * 否则 `ZipInputStream` 会把后面的条目读错位。
                     */
                    if (!entry.isDirectory && Live2DJunkFilter.isJunk(entry.name)) {
                        skipped++
                        val buffer = ByteArray(64 * 1024)
                        while (zip.read(buffer) > 0) { /* 丢弃 */ }
                        zip.closeEntry()
                        continue
                    }

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

                if (skipped > 0) {
                    AppLog.i(TAG, "已跳过 $skipped 个无关文件（exe/dll/文档等）")
                }
                return skipped
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
        /*
         * 这个包是不是 **Mver 风格的键盘猫包**（自带 PNG 手）。
         *
         * ⚠️ 用户报的原话:
         * > 第三方模型，按按键手部会直接消失
         *
         * 原因:这类包（实测样本就是）在 `config.json` 里把每个键映射到
         * `lefthand/<N>.png` / `righthand/<N>.png`，按下去时 **Mver 画那张 PNG**，
         * 同时把模型自己的手臂用 `CatParamLeftHandDown=1` 收起来。
         *
         * 我们只做了"收起"这一半 —— PNG 手没画，于是模型的手直接消失。
         *
         * ⚠️ 所以这类包**暂时不去动那两个参数**（保持手臂在 idle 姿势），
         * 宁可没有按压动画，也不能让手没了。
         */
        mverHands: Boolean = false,
        /* 表情与按键组合（见 loadExpressions / loadExpressionBindings） */
        expressions: JSONArray? = null,
        exprBindings: JSONArray? = null,
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
            out.write("window.__BONGO_MVER_HANDS=$mverHands;\n")

            /*
             * ⚠️ 表情与按键组合**预解析后内联**。
             *
             * 为什么不给页面一个路径让它自己 fetch:
             * 页面是 `file://`，WebView 对 `fetch`/XHR 读本地文件是**拒绝**的
             * （moc 当初也是因为这个才用 base64 内联，见类注释）。
             */
            out.write("window.__BONGO_EXPRESSIONS=${expressions.orEmptyJsonArray()};\n")
            out.write("window.__BONGO_EXPR_BINDINGS=${exprBindings.orEmptyJsonArray()};\n")
        }
    }

    /**
     * 把 model3 的 `Expressions` 读成**扁平结构**，供运行时段直接用。
     *
     * 输出:`[{"name":"正常眼","params":[["Param14",0,"Add"], …]}]`
     *
     * ⚠️ 读不出来的表情直接跳过（不因为一个坏文件让整个模型导入失败）。
     */
    private fun loadExpressions(
        modelJson: JSONObject,
        modelBase: File,
        root: File,
    ): JSONArray {
        val result = JSONArray()
        val list = modelJson.optJSONObject("FileReferences")?.optJSONArray("Expressions") ?: return result

        for (index in 0 until list.length()) {
            val item = list.optJSONObject(index) ?: continue
            val relative = item.optString("File").trim()
            if (relative.isEmpty()) continue
            val file = resolveInside(modelBase, relative, root) ?: continue
            val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: continue

            val params = JSONArray()
            val array = json.optJSONArray("Parameters") ?: continue
            for (p in 0 until array.length()) {
                val param = array.optJSONObject(p) ?: continue
                val id = param.optString("Id").trim()
                if (id.isEmpty()) continue
                params.put(
                    JSONArray().apply {
                        put(id)
                        put(param.optDouble("Value", 0.0))
                        put(param.optString("Blend").ifBlank { "Add" })
                    },
                )
            }
            if (params.length() == 0) continue

            result.put(
                JSONObject().apply {
                    put("name", item.optString("Name").ifBlank { "表情 ${index + 1}" })
                    put("params", params)
                },
            )
        }
        return result
    }

    /**
     * 读包配置里"按键组合 → 表情下标"的绑定。
     *
     * Mver 的 `config.json` 里每个模式都有一段 `l2d_expression`:
     * `[[18,219],[18,57],[18,48],[18,189]]` ——
     * **外层下标 = 表情下标**，内层那几个数字是 **Windows 虚拟键码**，要一起按下。
     *
     * ⚠️ 这条语义是从 Axon 的 `mverComboBindings` 抄过来的（它就是这么解的），
     * 不是我们猜的:它对每个内层数组调用 `mverKeyCombo`，把数字当 VK 翻成键名。
     */
    private fun loadExpressionBindings(
        packageRoot: File,
        mode: String?,
        expressionCount: Int,
    ): JSONArray {
        val result = JSONArray()
        if (expressionCount <= 0) return result

        val config = listOf("config.json", "config.json5", "config.jsonc")
            .map { File(packageRoot, it) }
            .firstOrNull { it.isFile } ?: return result

        val json = runCatching {
            /* ⚠️ 允许注释:社区包常见 `//注释`，org.json 会直接解析失败 */
            JSONObject(stripJsonComments(config.readText()))
        }.getOrNull() ?: return result

        val section = json.optJSONObject(mode ?: "standard")
            ?: json.optJSONObject("keyboard")
            ?: return result
        val combos = section.optJSONArray("l2d_expression") ?: return result

        for (index in 0 until minOf(combos.length(), expressionCount)) {
            val combo = combos.optJSONArray(index) ?: continue
            val keys = JSONArray()
            for (k in 0 until combo.length()) {
                vkKeyName(combo.optInt(k, -1))?.let { keys.put(it) }
            }
            if (keys.length() == 0) continue
            result.put(
                JSONObject().apply {
                    put("keys", keys)
                    put("index", index)
                },
            )
        }
        return result
    }

    /** 去掉 `//` 与 `/* */` 注释（字符串里的不动） */
    private fun stripJsonComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        var inString = false
        while (i < text.length) {
            val c = text[i]
            when {
                inString -> {
                    out.append(c)
                    if (c == '\\' && i + 1 < text.length) {
                        out.append(text[i + 1])
                        i += 2
                        continue
                    }
                    if (c == '"') inString = false
                    i++
                }

                c == '"' -> {
                    inString = true
                    out.append(c)
                    i++
                }

                c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
                    while (i < text.length && text[i] != '\n') i++
                }

                c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
                    i += 2
                }

                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /**
     * Windows 虚拟键码 → 我们运行时用的键名。
     *
     * ⚠️ 映射表照 Axon 的 `mverVkName` 抄（那份是社区包实测过的），
     * 键名要与我们 `runtime.js` 的 `LEFT_KEYS`/`RIGHT_KEYS` 一致，
     * 否则按下去永远匹配不上组合。
     */
    private fun vkKeyName(vk: Int): String? {
        if (vk in 0x30..0x39) return "Num" + (vk - 0x30)
        if (vk in 0x41..0x5A) return "Key" + ('A' + (vk - 0x41))
        if (vk in 0x60..0x69) return "Numpad" + (vk - 0x60)
        if (vk in 0x70..0x7B) return "F" + (vk - 0x70 + 1)
        return when (vk) {
            0x01 -> "MouseLeft"
            0x02 -> "MouseRight"
            0x04 -> "MouseMiddle"
            0x08 -> "Backspace"
            0x09 -> "Tab"
            0x0D -> "Return"
            0x10 -> "Shift"
            0x11 -> "Control"
            0x12 -> "Alt"
            0x13 -> "CapsLock"
            0x1B -> "Escape"
            0x20 -> "Space"
            0xA0 -> "ShiftLeft"
            0xA1 -> "ShiftRight"
            0xA2 -> "ControlLeft"
            0xA3 -> "ControlRight"
            0x25 -> "LeftArrow"
            0x26 -> "UpArrow"
            0x27 -> "RightArrow"
            0x28 -> "DownArrow"
            0xBB -> "Equal"
            0xBC -> "Comma"
            0xBD -> "Minus"
            0xBE -> "Period"
            0xBF -> "Slash"
            0xC0 -> "BackQuote"
            0xDB -> "BracketLeft"
            0xDC -> "Backslash"
            0xDD -> "BracketRight"
            0xDE -> "Quote"
            else -> null
        }
    }

    private fun JSONArray?.orEmptyJsonArray(): String =
        this?.toString() ?: "[]"

    /**
     * 这个目录像不像 **Mver 风格的键盘猫包**（自带逐键 PNG 手）。
     *
     * ⚠️ 不能只认 `lefthand/` + `righthand/`:
     * 实测样本的 `keyboard` 变体是 `lefthand/`+`righthand/`，
     * 但 **`standard` 变体用的是 `hand/`**，`gamepad` 又是另一套名字 ——
     * 第一版按名字卡死，结果标准模式没被识别出来（用户报"标准模式手还是消失"）。
     *
     * 社区包命名五花八门，所以常见的都算上，再把 Mver 包根的 `config.json` 也算一条。
     *
     * ⚠️ 这个标记现在**不决定"收不收手臂"**（导入模型一律不收，见 runtime 的
     * `syncHandOverrides`），它是留给后续"PNG 手合成"用的。
     */
    private fun hasMverHands(dir: File): Boolean {
        if (File(dir, "lefthand").isDirectory || File(dir, "righthand").isDirectory) return true
        if (File(dir, "hand").isDirectory || File(dir, "left_hand").isDirectory) return true
        return listOf("config.json", "config.json5", "config.jsonc").any { File(dir, it).isFile }
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
            /*
             * ⚠️ 跳过无关文件:老模型库里可能已经躺着 exe/dll（过滤是后加的）。
             * 不跳的话"同一个模型因为带了 exe 而摘要不同"，去重就失效了。
             */
            .filter { it.isFile && !Live2DJunkFilter.isJunk(it.name) }
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

package com.something.sthkey.data.config

import com.something.sthkey.domain.config.KeyStrokesConfig
import org.json.JSONObject

/**
 * 一份配置包（`.sthkey`）的读写。
 *
 * ============================================================
 * 包结构
 * ============================================================
 * ```
 * 我的配置.sthkey          （本质是 zip）
 * ├── manifest.json        元信息：格式标识、版本、名称、描述、样式
 * ├── params.json          全部功能参数（就是配置页能改的那些）
 * └── assets/
 *     └── fonts/           这个配置用到的**导入字体**（导出时按需复制）
 *         └── xxx.ttf
 * ```
 *
 * ============================================================
 * 只有"导入字体"会进包
 * ============================================================
 * 字体有三种来源（见 `FontRegistry.FontKind`），但需要跟着包走的**只有一种**：
 *
 * | 来源 | 进包？ | 为什么 |
 * |---|---|---|
 * | 系统字体 `system:*` | ❌ | 每台设备都有 |
 * | 内置字体 `builtin:*` | ❌ | **装了本应用就有** —— 见下 |
 * | 导入字体 `imported:*` | ✅ | 只存在于导出方的设备上 |
 *
 * 内置字体当年是跟着包一起导出的，那是**多此一举**：它随应用打包，
 * 每个用户手上都有一份（从加入自定义字体的那个版本起就有）。
 * 现在和系统字体同一套处理：**只存 id**，导入方直接用自己那份。
 * 一份包因此能小好几 MB。
 *
 * ============================================================
 * 为什么 manifest 与 params 分开
 * ============================================================
 * manifest 是"给人看、给列表用"的轻量元信息，params 是完整的参数集合。
 * 分开之后：
 * - 未来做"配置分享预览"只需要读 manifest，不必解析全部参数；
 * - 参数结构大改时，manifest 的结构可以保持稳定。
 *
 * 代价是两个文件可能出现"只有一半能读"的中间态（例如解压中断），
 * 因此导入时 **manifest 读不出来就直接报错**，不做半份入库。
 *
 * ============================================================
 * 后缀
 * ============================================================
 * 导出用 `.sthkey`：它是**只有本应用能读的配置包**，用 `.zip` 会让人
 * 误以为"解压出来就能用"，也容易和同类项目的产物混淆。
 * 导入时同时接受 `.zip`，兼容手滑改名的文件。
 * 与此同时 manifest 里写了 [FORMAT_ID]，导入时会校验 —— 随便一个 zip 不会被误读。
 */
object ConfigPackageCodec {

    /** 包格式标识；导入时校验，防止把任意 zip 当成配置 */
    const val FORMAT_ID = "sthkey-config"

    /** 当前包格式版本 */
    const val FORMAT_VERSION = 1

    /** 导出后缀 */
    const val EXTENSION = ".sthkey"

    /** 导入时也接受的后缀（用户可能手动改成 .zip） */
    val ACCEPTED_EXTENSIONS = listOf(".sthkey", ".zip")

    /** 包内路径常量 */
    const val ENTRY_MANIFEST = "manifest.json"
    const val ENTRY_PARAMS = "params.json"

    /**
     * 包内字体目录（**复数**）。
     *
     * ============================================================
     * 与 [LEGACY_DIR_FONT]（单数）刻意分开
     * ============================================================
     * 老的 `.sthkey` 包是**为 Key 样式设计的**，一份配置只有一个字体，
     * 所以当年用的是单数 `assets/font/`。自定义 Key 不一样：
     * 每个组件都能有自己的字体，于是一份配置可能带**多个**字体文件 ——
     * 单数那个名字就不合适了。
     *
     * 两个前缀长度不同、`assets/font/` 也不是 `assets/fonts/` 的前缀
     * （第 13 个字符是 `/` 与 `s`），所以**不会互相误匹配**：
     * 读条目时按各自的前缀找就行，不需要任何迁移。
     */
    const val DIR_FONTS = "assets/fonts/"

    /**
     * 旧包的字体目录（**单数**），只用于**读**。
     *
     * 老的 Key 样式配置包用的是这个前缀。新包一律写 [DIR_FONTS]。
     */
    const val LEGACY_DIR_FONT = "assets/font/"

    /**
     * Live2D 模型在包内的目录。
     *
     * 与 [DIR_FONT] 平级、**一个模型一个子目录**，里面放模型的原始文件
     * （`moc3` / 贴图 / 皮肤包资源），导入时生成的 `index.html` 与
     * `modeldata.js` **不进包**（那是本机生成物，且会污染内容摘要）。
     */
    const val DIR_LIVE2D = "assets/live2d/"

    /** params.json 里存字体的路径前缀：`sthkey-font://<包内相对路径>` */
    const val FONT_URI_PREFIX = "sthkey-font://"

    /** params.json 里存 Live2D 模型的路径前缀：`sthkey-live2d://<包内目录相对路径>` */
    const val LIVE2D_URI_PREFIX = "sthkey-live2d://"

    /**
     * 元信息。
     *
     * @param format 固定为 [FORMAT_ID]，导入时校验
     * @param formatVersion 包结构版本
     * @param appVersion 导出时的应用版本，便于排查"哪个版本导出的"
     * @param exportedAt 导出时间（Unix 毫秒）
     * @param fontFiles 包内字体：包内相对路径 → 字体元信息
     * @param live2d 包内携带的 Live2D 模型；不携带（内置模型或按键样式）时为 null
     */
    data class Manifest(
        val format: String,
        val formatVersion: Int,
        val appVersion: String,
        val exportedAt: Long,
        val name: String,
        val description: String,
        val styleId: String,
        val fontFiles: List<FontFileInfo>,
        val live2d: Live2DFileInfo? = null,
    )

    /** 包内一个字体的元信息 */
    data class FontFileInfo(
        /** 包内相对路径，例如 `assets/font/minecraft.ttf` */
        val path: String,
        /** 原始显示名 */
        val displayName: String,
        /** 内容 SHA-256；导入时用来判断"这个字体本地已经有了" */
        val sha256: String,
    )

    /**
     * 包内一个 Live2D 模型的元信息。
     *
     * @param path 包内**目录**相对路径，例如 `assets/live2d/我的模型`
     * @param displayName 原始显示名
     * @param sha256 目录摘要（所有文件按路径排序后逐个摘要再汇总）；
     *   导入时用来判断"这个模型本地已经有了"
     * @param layout `full`（自带键帽皮肤包）/ `cat`（只有角色）
     */
    data class Live2DFileInfo(
        val path: String,
        val displayName: String,
        val sha256: String,
        val layout: String,
    )

    /* ============================================================
     * 写
     * ============================================================ */

    fun encodeManifest(
        config: KeyStrokesConfig,
        appVersion: String,
        exportedAt: Long,
        fontFiles: List<FontFileInfo>,
        live2d: Live2DFileInfo?,
    ): String = JSONObject().apply {
        put("format", FORMAT_ID)
        put("formatVersion", FORMAT_VERSION)
        put("appVersion", appVersion)
        put("exportedAt", exportedAt)
        put("name", config.name)
        put("description", config.description)
        put("styleId", config.styleId)
        put(
            "fonts",
            org.json.JSONArray().apply {
                fontFiles.forEach { font ->
                    put(
                        JSONObject().apply {
                            put("path", font.path)
                            put("displayName", font.displayName)
                            put("sha256", font.sha256)
                        },
                    )
                }
            },
        )

        /*
         * 包内的 Live2D 模型；不携带时写 null。
         *
         * 写 null 而不是省略字段：读取方一眼能看出"这份包本来就没有模型"，
         * 不用去猜"是没带、还是导出时漏了"。
         */
        put(
            "live2d",
            live2d?.let { info ->
                JSONObject().apply {
                    put("path", info.path)
                    put("displayName", info.displayName)
                    put("sha256", info.sha256)
                    put("layout", info.layout)
                }
            } ?: JSONObject.NULL,
        )
    }.toString(2)

    /* ============================================================
     * 读
     * ============================================================ */

    /**
     * 解析元信息。
     *
     * @return 格式不对时返回 null（调用方据此报"这不是配置包"）
     */
    fun decodeManifest(raw: String): Manifest? = try {
        val json = JSONObject(raw)
        if (json.optString("format") != FORMAT_ID) {
            null
        } else {
            val fonts = buildList {
                val array = json.optJSONArray("fonts") ?: return@buildList
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val path = item.optString("path")
                    if (path.isBlank()) continue
                    add(
                        FontFileInfo(
                            path = path,
                            displayName = item.optString("displayName").ifBlank { path },
                            sha256 = item.optString("sha256"),
                        ),
                    )
                }
            }

            /*
             * 包内模型；老包没有这个字段（读到 null 或缺失都算"没带模型"）。
             * path 为空时同样按"没带"处理 —— 一个没有路径的模型条目没有意义。
             */
            val live2d = json.optJSONObject("live2d")?.let { item ->
                val path = item.optString("path")
                if (path.isBlank()) {
                    null
                } else {
                    Live2DFileInfo(
                        path = path,
                        displayName = item.optString("displayName").ifBlank { path },
                        sha256 = item.optString("sha256"),
                        layout = item.optString("layout").ifBlank { "cat" },
                    )
                }
            }

            Manifest(
                format = FORMAT_ID,
                formatVersion = json.optInt("formatVersion", 1),
                appVersion = json.optString("appVersion"),
                exportedAt = json.optLong("exportedAt", 0L),
                name = json.optString("name").ifBlank { "未命名配置" },
                description = json.optString("description", ""),
                styleId = json.optString("styleId"),
                fontFiles = fonts,
                live2d = live2d,
            )
        }
    } catch (_: Exception) {
        null
    }

    /**
     * 参数 JSON 与本版本的字段差异。
     *
     * @param unknown 配置里有、本版本不认识的字段（多半来自更新的版本）
     * @param missing 本版本认识、配置里没写的字段（会用默认值）
     */
    data class FieldDiff(
        val unknown: List<String>,
        val missing: List<String>,
    )

    /**
     * params.json 里**故意不写**的字段。
     *
     * 名称与描述的唯一出处是 manifest.json —— params 里再写一份就是两个真源。
     * 所以它们虽然在 KNOWN_KEYS 里（编解码器认识，读旧包时也能容忍），
     * 却绝不能算进"缺失字段"：否则每导入一个自家导出的包，
     * 报告都会说"名称、描述用了默认值"，把正常情况说成异常。
     */
    private val MANIFEST_OWNED_KEYS: Set<String> = setOf("name", "description")

    fun compareFields(params: JSONObject): FieldDiff {
        val present = params.keys().asSequence().toSet()
        val expected = JsonConfigCodec.KNOWN_KEYS - MANIFEST_OWNED_KEYS
        return FieldDiff(
            unknown = present.filterNot { JsonConfigCodec.isKnownKey(it) }.sorted(),
            missing = expected.filterNot { it in present }.sorted(),
        )
    }
}

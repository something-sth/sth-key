package com.something.sthkey.data.config

import android.content.Context
import android.net.Uri
import com.something.sthkey.BuildConfig
import com.something.sthkey.core.log.AppLog
import com.something.sthkey.domain.config.ConfigGroup
import com.something.sthkey.domain.config.KeyStrokesConfig
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * `.sthgroup`（配置组合包）的导入 / 导出。
 *
 * ============================================================
 * ⚠️ 设计要点（与 `ConfigPackageManager` 的分工）
 * ============================================================
 * | 谁 | 负责 |
 * |---|---|
 * | **本类** | 外层容器（manifest + 若干 `.sthkey` 条目），以及组合的建立 |
 * | `ConfigPackageManager` | 单个配置的**全部**内容:参数、字体、Live2D、重名、字段差异 |
 *
 * ⚠️ 所以本类**不做任何配置内容的解析** —— 每个 `.sthkey` 条目原样喂给
 * `ConfigPackageManager.import`，所有既有逻辑（去重、改名、字段校验）自动生效。
 *
 * ⚠️⚠️ 最容易踩的坑:**配置 id 在导入后一定会变**（UUID 重建），
 * 所以组合的 `memberIds` 必须用"导入后拿到的新 id"重建，不能抄包里的。
 */
object GroupPackageManager {

    private const val TAG = "GroupPackage"

    /** 导出的结果 */
    sealed interface ExportOutcome {
        data class Ok(val fileCount: Int) : ExportOutcome
        data class Failed(val reason: String) : ExportOutcome
    }

    /** 导入的结果 */
    data class ImportOutcome(
        /** 新建出来的组合（成员已按导入后的新 id 填好） */
        val group: ConfigGroup,
        /** 成功导入的配置名 */
        val importedNames: List<String>,
        /** 失败的条目及原因（⚠️ 单个失败**不中断**整包） */
        val failures: List<String>,
    )

    /** 这个文件是哪种包 */
    enum class PackageKind { CONFIG, GROUP, UNKNOWN }

    /* ============================================================
     * 一、嗅探:这个文件是配置包还是组合包
     * ============================================================ */

    /**
     * ⚠️ 按**包里的 manifest 内容**判断，而不是按文件后缀。
     *
     * 后缀不可靠:有些文件管理器会把 `.sthgroup` 改成 `.zip`，
     * 用户也可能手动改名。内容才是真的。
     */
    fun detectKind(context: Context, source: Uri): PackageKind {
        val temp = copyToTemp(context, source) ?: return PackageKind.UNKNOWN
        return try {
            ZipFile(temp).use { zip ->
                val raw = zip.readEntry(GroupPackageCodec.ENTRY_MANIFEST) ?: return PackageKind.UNKNOWN
                val json = raw.toString(Charsets.UTF_8)
                when {
                    GroupPackageCodec.decodeManifest(json) != null -> PackageKind.GROUP
                    /* ⚠️ 组合 manifest 解析失败时，再看配置包的格式标识 */
                    json.contains("\"format\"") && json.contains("sthkey-config") ->
                        PackageKind.CONFIG

                    else -> PackageKind.UNKNOWN
                }
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "嗅探包类型失败:${e.javaClass.simpleName}: ${e.message}")
            PackageKind.UNKNOWN
        } finally {
            temp.delete()
        }
    }

    /* ============================================================
     * 二、导出
     * ============================================================ */

    /**
     * 导出一个组合。
     *
     * ⚠️ 每个配置都用 `ConfigPackageManager.writeTo` **写成完整 `.sthkey`**，
     * 再把这个临时文件塞进外层 zip 的条目里。
     * 好处是配置内容（字体 / Live2D / 样式）完全是既有逻辑产出的，不会漏。
     *
     * @param configs 组合里的配置（⚠️ 调用方已按 `memberIds` 顺序排好）
     */
    fun export(
        context: Context,
        group: ConfigGroup,
        configs: List<KeyStrokesConfig>,
        target: Uri,
    ): ExportOutcome {
        if (configs.isEmpty() && group.memberIds.isNotEmpty()) {
            /*
             * ⚠️ 有成员却一个都找不到 = 数据不一致（配置被删了但引用没清）。
             * 这时导出一个空组合会误导用户，直接报错更诚实。
             */
            return ExportOutcome.Failed("组合里的配置已不存在，无法导出")
        }

        val resolver = context.contentResolver
        val output = runCatching {
            resolver.openOutputStream(target, "wt")
        }.getOrNull() ?: runCatching {
            resolver.openOutputStream(target)
        }.getOrNull() ?: return ExportOutcome.Failed("无法写入所选位置")

        val refs = mutableListOf<GroupPackageCodec.ConfigRef>()

        return try {
            ZipOutputStream(output.buffered()).use { zip ->
                /* 逐条把配置打成 `.sthkey` 再塞进外层 zip */
                configs.forEachIndexed { index, config ->
                    val temp = File(context.cacheDir, "group_export_${index}.sthkey")
                    try {
                        if (!ConfigPackageManager.writeTo(context, config, Uri.fromFile(temp))) {
                            return ExportOutcome.Failed("导出「${config.name}」失败")
                        }
                        val entryName = uniqueEntryName(refs.map { it.file }, config.name)
                        zip.putNextEntry(ZipEntry(entryName))
                        temp.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                        refs += GroupPackageCodec.ConfigRef(
                            file = entryName,
                            name = config.name,
                            description = config.description,
                            styleId = config.styleId,
                        )
                    } finally {
                        temp.delete()
                    }
                }

                /* ⚠️ manifest 最后写:它引用了上面生成的条目名 */
                val manifest = GroupPackageCodec.encodeManifest(
                    name = group.name,
                    description = group.description,
                    appVersion = BuildConfig.VERSION_NAME,
                    configs = refs,
                )
                zip.putNextEntry(ZipEntry(GroupPackageCodec.ENTRY_MANIFEST))
                zip.write(manifest.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            AppLog.i(TAG, "已导出组合「${group.name}」，${refs.size} 个配置")
            ExportOutcome.Ok(refs.size)
        } catch (e: IOException) {
            AppLog.e(TAG, "导出组合失败:${e.javaClass.simpleName}", e)
            ExportOutcome.Failed("写入失败:${e.message ?: e.javaClass.simpleName}")
        } finally {
            runCatching { output.close() }
        }
    }

    /* ============================================================
     * 三、导入
     * ============================================================ */

    /**
     * 导入一个组合包。
     *
     * 流程:
     * 1. 复制到临时文件、打开 zip、读 manifest;
     * 2. 按 `manifest.configs` 的**顺序**逐条抽出 `.sthkey` → 交给
     *    `ConfigPackageManager.import`（字体去重等全部复用）;
     * 3. 用**导入后的新 id** 建组合。
     *
     * ⚠️ 单条失败**不中断**:一个坏条目不该毁掉整包。失败项收进 [ImportOutcome.failures]。
     */
    fun import(context: Context, source: Uri): Result<ImportOutcome> {
        val temp = copyToTemp(context, source)
            ?: return Result.failure(IOException("无法读取所选文件"))

        return try {
            ZipFile(temp).use { zip ->
                val manifestRaw = zip.readEntry(GroupPackageCodec.ENTRY_MANIFEST)
                    ?: return Result.failure(IOException("缺少 manifest.json，这不是配置组合包"))
                val manifest = GroupPackageCodec.decodeManifest(manifestRaw.toString(Charsets.UTF_8))
                    ?: return Result.failure(IOException("manifest 格式标识不匹配，这不是配置组合包"))

                val importedIds = mutableListOf<String>()
                val importedNames = mutableListOf<String>()
                val failures = mutableListOf<String>()

                manifest.configs.forEachIndexed { index, ref ->
                    val bytes = zip.readEntry(ref.file)
                    if (bytes == null) {
                        failures += "「${ref.name.ifBlank { ref.file }}」:包里找不到该文件"
                        return@forEachIndexed
                    }

                    val entryTemp = File(context.cacheDir, "group_import_${index}.sthkey")
                    try {
                        entryTemp.writeBytes(bytes)
                        val result = ConfigPackageManager.import(context, Uri.fromFile(entryTemp))
                        val outcome = result.getOrNull()
                        if (outcome == null) {
                            failures += "「${ref.name.ifBlank { ref.file }}」:" +
                                (result.exceptionOrNull()?.message ?: "导入失败")
                        } else {
                            /*
                             * ⚠️⚠️ 用**导入后的新 id**（`outcome.config.id`），
                             * 不是 ref 里的任何东西 —— 配置 id 每次导入都会重建。
                             */
                            importedIds += outcome.config.id
                            importedNames += outcome.config.name
                        }
                    } finally {
                        entryTemp.delete()
                    }
                }

                val group = GroupStore.get(context).create(
                    name = manifest.name,
                    description = manifest.description,
                    memberIds = importedIds,
                )
                AppLog.i(
                    TAG,
                    "已导入组合「${group.name}」:成功 ${importedIds.size}，" +
                        "失败 ${failures.size}",
                )
                Result.success(ImportOutcome(group, importedNames, failures))
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "导入组合失败", e)
            Result.failure(e)
        } finally {
            temp.delete()
        }
    }

    /* ============================================================
     * 四、工具
     * ============================================================ */

    /** 包内条目名去重（同名配置加后缀），并保证是 `.sthkey` */
    private fun uniqueEntryName(taken: List<String>, displayName: String): String {
        val base = displayName.trim().ifBlank { "配置" }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        var candidate = "$base.sthkey"
        var index = 2
        while (candidate in taken) {
            candidate = "$base ($index).sthkey"
            index++
        }
        return candidate
    }

    private fun ZipFile.readEntry(name: String): ByteArray? {
        val entry = getEntry(name) ?: return null
        if (entry.isDirectory) return null
        return runCatching {
            getInputStream(entry).use { it.readBytes() }
        }.getOrNull()
    }

    private fun copyToTemp(context: Context, source: Uri): File? = runCatching {
        val temp = File(context.cacheDir, "group_pkg_${System.nanoTime()}.zip")
        context.contentResolver.openInputStream(source)?.use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        temp
    }.getOrNull()
}

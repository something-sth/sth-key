package com.something.sthkey.data.config

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.something.sthkey.core.log.AppLog
import com.something.sthkey.domain.config.KeyStrokesConfig
import java.io.File

/**
 * 两种"不用每次挑位置"的配置包导出方式。
 *
 * ============================================================
 * 为什么不需要任何存储权限
 * ============================================================
 * | 目标 | 机制 | 权限 |
 * |---|---|---|
 * | `Download/sthkeyconfigs/` | **MediaStore** | **零权限** |
 * | 分享给别的应用 | **FileProvider** + `ACTION_SEND` | 零权限 |
 *
 * ⚠️ **不需要 `MANAGE_EXTERNAL_STORAGE`**（"所有文件访问权限"）。
 * 本项目 `minSdk = 30`，而 Android 11+ 上写 `Download/` 走 MediaStore
 * 本来就不需要权限。加那个权限有害无益：它是"特殊应用权限"，
 * 要跳到系统设置页手动打开（比现在麻烦），而且一个悬浮窗 + root/Shizuku
 * 的应用再申请"所有文件访问"在用户眼里非常可疑，
 * 反而会让用户不敢装。
 *
 * ============================================================
 * ⚠️ 曾经有过"自定义导出目录"，已删除
 * ============================================================
 * 那套是用 SAF 目录树（`OpenDocumentTree` + 持久化授权）实现的，
 * 但**实测在多种设备上都会失败**（"无法在所选目录里创建文件"），
 * 而且失败原因难以定位：`DocumentsContract.createDocument` 抛出的
 * `FileNotFoundException` 在不同 DocumentsProvider 下含义不同，
 * 有的表示"重名"、有的表示"该目录不允许写入"，光看异常分不出来。
 *
 * 用户实际只需要"导到 Download 里"，所以直接删掉了这一整条路。
 * 留下的好处不只是少一处代码：
 * - 少了"授权被系统回收"这一整类失败（见下）；
 * - 少了 SAF 的持久化权限管理；
 * - 少了一个"用户以为设好了、其实存不进去"的困惑源。
 *
 * ⚠️ 仍然保留 [ExportMethod.MANUAL]：它是**用户当次自己选位置**，
 * 走 `ACTION_CREATE_DOCUMENT`（系统界面），那条路一直正常。
 * 与"预先设一个目录、之后静默写入"是两回事 —— 后者才是被删掉的。
 */
object ConfigExporters {

    /** 下载目录里的子目录名 */
    const val SUBDIR = "sthkeyconfigs"

    private const val TAG = "Export"

    /**
     * 导出结果。
     *
     * ⚠️ 用 sealed interface 而不是 `Boolean`：失败需要**能说清是什么问题** ——
     * 磁盘写不进、系统拒绝请求、没有分享应用，用户的下一步动作完全不同。
     * 全塞进一个 `false` 的话界面只能说"导出失败"，用户拿不到可行动的线索。
     */
    sealed interface ExportResult {
        /** @param displayName 最终落盘的文件名（可能与请求的不同，见下） */
        data class Ok(val displayName: String) : ExportResult

        data class Failed(val reason: String) : ExportResult
    }

    /* ============================================================
     * 一、MediaStore：写进 Download/sthkeyconfigs/
     * ============================================================ */

    /**
     * 写进系统下载目录，**零权限**。
     *
     * @param subDir `Download/` 下的子目录名；空表示直接放 `Download/`
     */
    fun exportToDownloads(
        context: Context,
        config: KeyStrokesConfig,
        fileName: String,
        subDir: String = SUBDIR,
    ): ExportResult {
        val relative = buildString {
            append(Environment.DIRECTORY_DOWNLOADS)
            if (subDir.isNotBlank()) {
                append('/').append(subDir.trim('/'))
                append('/')
            }
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            /*
             * ⚠️ MIME 必须给 `application/octet-stream`，**不能给 `application/zip`**。
             *
             * MediaStore 会把 MIME 与文件后缀**对一遍**，对不上就按 MIME
             * 补一个后缀。我们给的名字是 `配置名.sthkey`、MIME 却是
             * `application/zip` —— 于是它补成 `配置名.sthkey.zip`。
             *
             * 用户实测到的就是这个，而且**只有"导出到目录"这条路有**：
             * 分享与手动选位置都不经过 MediaStore 的这层推断。
             *
             * `application/octet-stream` 是"未知二进制"的正式说法，
             * 它**不会**触发任何后缀补全。
             */
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            /*
             * `IS_PENDING = 1`（写入中）→ 写完清零。
             *
             * ⚠️ 这不是可选的优化：不置 pending 的话，别的应用（文件管理器、
             * 扫描器、同步工具）可能在**我们写了一半**的时候读到那个文件，
             * 拿到一个残缺的 zip。
             */
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        return try {
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val uri = context.contentResolver.insert(collection, values)
                ?: return ExportResult.Failed("系统拒绝了写入请求")

            /*
             * ⚠️ MediaStore 遇到重名会**自动改名**（`x (1).sthkey`），
             * `insert` 返回的 Uri 已经是新的那个。这是好事：
             * 不会覆盖用户已有的包。所以这里**不尝试纠正名字**，
             * 而是把实际名字回报给界面，提示里说清楚 ——
             * 用户去文件管理器里得按那个名字找。
             */
            val actualName = displayNameOf(context, uri) ?: fileName

            if (!writePackageAt(context, config, uri)) {
                // 失败就把这个半成品删掉，别留一个 0 字节文件挡住下次导出
                runCatching { context.contentResolver.delete(uri, null, null) }
                return ExportResult.Failed("写入失败")
            }

            // 写入完成，解除 pending
            runCatching {
                context.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
            }

            AppLog.i(TAG, "已导出到下载目录：$relative$actualName")
            ExportResult.Ok(actualName)
        } catch (e: Exception) {
            AppLog.e(TAG, "导出到下载目录失败", e)
            ExportResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * 读一个 Uri 的显示名；查不到返回 null。
     *
     * 不引入 `documentfile` 依赖：这里只要一个名字，为此多带一个库不划算
     * （与 `ConfigPackageManager` 里那段同样的取舍）。
     */
    private fun displayNameOf(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    /* ============================================================
     * 二、分享
     * ============================================================ */

    /**
     * 把配置包写进缓存目录，再交给系统分享面板。
     *
     * ============================================================
     * ⚠️ 必须走 FileProvider，不能直接分享 `file://`
     * ============================================================
     * Android 7+ 起传 `file://` 出去会抛 `FileUriExposedException`。
     * 必须用 [FileProvider.getUriForFile] 生成 `content://`，
     * 并给 Intent 加上 `FLAG_GRANT_READ_URI_PERMISSION` ——
     * 否则接收方（微信等）读不到内容，表现是"分享过去是个空文件/发不出去"。
     *
     * @return 可以交给 `startActivity` 的 Intent；失败返回 null
     */
    fun buildShareIntent(
        context: Context,
        config: KeyStrokesConfig,
        fileName: String,
    ): Intent? {
        /*
         * ⚠️ 用块体而不是 `= try { … }`：表达式体里**不能用 `return`**
         * （Kotlin 报 "Returns are prohibited for functions with an
         * expression body"），而这里有"写失败就提前退出"的分支。
         */
        return try {
            /*
             * 写进 cacheDir 下的子目录：FileProvider 的路径配置只允许
             * 暴露我们指定的位置，不能把整个私有目录交出去。
             */
            val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }

            /*
             * ⚠️ 每次分享前**清掉上一次的**。
             *
             * 不清的话缓存会一直涨（每个配置包都可能带几 MB 字体），
             * 而这些文件在分享结束后就没有用了。
             */
            dir.listFiles()?.forEach { it.delete() }

            val file = File(dir, sanitizeFileName(fileName))
            if (!writePackageToFile(context, config, file)) return null

            val uri = FileProvider.getUriForFile(context, authorityOf(context), file)

            Intent(Intent.ACTION_SEND).apply {
                /*
                 * ⚠️ 分享的类型要给 `application/zip`，**不能**用 [MIME_TYPE]。
                 *
                 * 内容确实是 zip，而这个类型微信/QQ/邮件都认；
                 * 给 `application/octet-stream` 的话一部分接收方会直接拒收
                 * （"不支持的文件类型"）—— 那是"发不出去"，比后缀难看严重得多。
                 *
                 * 而**保存**时反而必须给 [MIME_TYPE]（octet-stream），
                 * 因为 MediaStore 会按 MIME 给 `.sthkey` 补后缀。
                 * 两处的要求正好相反，别互相"统一"。
                 */
                type = SHARE_MIME_TYPE
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, config.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "准备分享失败", e)
            null
        }
    }

    /* ============================================================
     * 常量与内部工具
     * ============================================================ */

    /**
     * **保存**配置包时用的 MIME。
     *
     * ⚠️ 必须是 `application/octet-stream`，**不能是 `application/zip`**：
     * 那会让 MediaStore 按 MIME 给 `配置名.sthkey` 补一个 `.zip`
     * （变成 `配置名.sthkey.zip`）。见 [exportToDownloads] 里的说明。
     */
    const val MIME_TYPE = "application/octet-stream"

    /**
     * **分享**时用的类型；与 [MIME_TYPE] 刻意不同，见 [buildShareIntent] 的说明。
     */
    private const val SHARE_MIME_TYPE = "application/zip"

    private const val SHARE_DIR = "share"

    /**
     * 把配置包写到目标 Uri。
     *
     * 与 `ConfigPackageManager.writePackage` 是同一件事 ——
     * 那边是 private，这里通过包内可见的桥接调用，避免两份实现
     * （两份的下场是"某一种导出方式少带了某个资源"）。
     */
    private fun writePackageAt(context: Context, config: KeyStrokesConfig, target: Uri): Boolean =
        ConfigPackageManager.writeTo(context, config, target)

    private fun writePackageToFile(
        context: Context,
        config: KeyStrokesConfig,
        file: File,
    ): Boolean = ConfigPackageManager.writeTo(context, config, Uri.fromFile(file))

    /** `FileProvider` 的 authority，与 manifest 里声明的一致 */
    private fun authorityOf(context: Context): String = "${context.packageName}.files"

    /**
     * 文件名里不能有路径分隔符等字符。
     *
     * ⚠️ 配置名是**用户随便写的**，完全可能带 `/` 或 `:`。
     * 直接拿去建文件的话，轻则建不出来，重则在 cacheDir 之外建出目录。
     */
    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").trim().ifBlank { "config.sthkey" }
}

package com.something.sthkey.data.config

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
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
/**
 * 把"往这个 Uri 里写一个包"抽象出来。
 *
 * ⚠️ 配置与配置组合走**同一条落盘通道**（MediaStore / 老式目录 / 分享缓存），
 * 唯一的区别就是"写什么进去"。把这件小事抽成函数之后，
 * 两边不会出现"某种导出方式少带资源"这种分叉。
 */
typealias PackageWriter = (android.net.Uri) -> Boolean

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
     * 写进系统下载目录。
     *
     * ============================================================
     * ⚠️⚠️ 两条路，因为 `MediaStore.Downloads` 是 API 29 才有的
     * ============================================================
     * | 系统 | 走哪条 | 权限 |
     * |---|---|---|
     * | Android 10（API 29）及以上 | **MediaStore** | 零权限 |
     * | Android 9（API 28）及以下 | **老式公共目录 `File`** | `WRITE_EXTERNAL_STORAGE` |
     *
     * ⚠️ 为什么必须分开:在 API 28 上访问 `MediaStore.Downloads.EXTERNAL_CONTENT_URI`
     * 会抛 **`NoSuchFieldError`** —— 它是 `Error` 不是 `Exception`，
     * 外面那个 `catch (e: Exception)` **接不住**，表现为"一点导出就闪退"。
     * 这已经由 lint 的 `NewApi` 检查确认过（minSdk 降到 28 时唯一一条真报错）。
     *
     * @param subDir `Download/` 下的子目录名；空表示直接放 `Download/`
     */
    fun exportToDownloads(
        context: Context,
        write: PackageWriter,
        fileName: String,
        subDir: String = SUBDIR,
    ): ExportResult = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
            exportToDownloadsMediaStore(context, write, fileName, subDir)

        else -> exportToDownloadsLegacy(context, write, fileName, subDir)
    }

    /** Android 10+：MediaStore，零权限。行为与之前完全一致。 */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun exportToDownloadsMediaStore(
        context: Context,
        write: PackageWriter,
        fileName: String,
        subDir: String,
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

            if (!write(uri)) {
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
     * Android 9 及以下：直接往公共下载目录写 `File`。
     *
     * ============================================================
     * ⚠️ 与 MediaStore 那条路的**行为差异**（有意为之）
     * ============================================================
     * | 项 | MediaStore（10+） | 这条路（9-） |
     * |---|---|---|
     * | 重名 | 系统自动改成 `x (1)` | **我们自己**用 [uniqueName] 做同样的事 |
     * | 写完可见性 | 系统自己索引 | 主动 `scanFile` 一次，文件管理器才立刻能看到 |
     * | 权限 | 无 | `WRITE_EXTERNAL_STORAGE`（见清单里的 `maxSdkVersion="28"`） |
     *
     * ⚠️ 重名策略必须"看起来一样":用户在两台机器上都点导出，
     * 一台得到 `配置 (1).sthkey`、另一台被覆盖，那是数据丢失。
     */
    @Suppress("DEPRECATION")
    private fun exportToDownloadsLegacy(
        context: Context,
        write: PackageWriter,
        fileName: String,
        subDir: String,
    ): ExportResult {
        /*
         * ⚠️ 权限检查放在最前面:没权限时 `mkdirs()` 只会**静默返回 false**，
         * 报出来的错会变成"无法创建目录"，把用户引到错误的方向。
         */
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            AppLog.w(TAG, "Android 9 导出需要存储权限，当前未授予")
            return ExportResult.Failed(NEED_STORAGE_PERMISSION)
        }

        return try {
            val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val dir = if (subDir.isBlank()) root else File(root, subDir.trim('/'))
            if (!dir.exists() && !dir.mkdirs()) {
                return ExportResult.Failed("无法创建目录 ${dir.absolutePath}")
            }

            val taken = dir.list()?.toSet().orEmpty()
            val name = uniqueName(fileName, taken)
            val file = File(dir, name)

            if (!write(Uri.fromFile(file))) {
                /*
                 * ⚠️ 失败就把半成品删掉。这条路**直接写目标文件**（没有 pending 标记），
                 * 留一个残缺 zip 的话，用户下次导出会在文件管理器里看到两个同名文件，
                 * 而其中一个打不开。
                 */
                runCatching { file.delete() }
                return ExportResult.Failed("写入失败")
            }

            /*
             * 主动扫码一次。不扫的话，部分机型的"文件管理 → 下载"里
             * 要等重启媒体库才出现，用户会以为导出失败。
             */
            runCatching {
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(file.absolutePath),
                    arrayOf(MIME_TYPE),
                    null,
                )
            }

            AppLog.i(TAG, "已导出到下载目录（API ${Build.VERSION.SDK_INT}）：${file.absolutePath}")
            ExportResult.Ok(name)
        } catch (e: Exception) {
            AppLog.e(TAG, "导出到下载目录失败（老式路径）", e)
            ExportResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * 在一个已被占用的名字集合里，算出一个不冲突的文件名。
     *
     * 规则与 MediaStore 的重名处理保持一致（`x.sthkey` → `x (1).sthkey` → `x (2).sthkey`），
     * 这样同一个配置在 Android 9 和 Android 10+ 上导出后，文件名长得一样。
     *
     * ⚠️ 抽成纯函数是为了能在 JVM 单测里覆盖 —— 它不碰文件系统，只认一个"名字集合"。
     *
     * @param taken 目录里已有的文件名（大小写敏感由调用方的文件系统决定）
     */
    internal fun uniqueName(desired: String, taken: Set<String>): String {
        if (desired !in taken) return desired

        /*
         * 拆成 "主名" + "扩展名" 两组。
         *
         * ⚠️ 用 `lastIndexOf('.')` 而不是 `substringBefore('.')`:
         * 名字里本身可能带点（`配置 1.2.sthkey`），从第一个点切会把扩展名判错。
         * ⚠️ 点在最前面（`.sthkey`，隐藏文件）时视为**没有主名**，
         * 否则会算出空基础和空扩展名，重名时拼出 " (1)" 这种怪名字。
         */
        val dot = desired.lastIndexOf('.')
        val base = if (dot > 0) desired.substring(0, dot) else desired
        val ext = if (dot > 0) desired.substring(dot) else ""

        var index = 1
        while (true) {
            val candidate = "$base ($index)$ext"
            if (candidate !in taken) return candidate
            index++
        }
    }

    /** [exportToDownloadsLegacy] 在没授权时返回的原因串，界面据此决定要不要去申请权限 */
    const val NEED_STORAGE_PERMISSION = "需要存储权限"

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
        displayName: String,
        fileName: String,
        write: PackageWriter,
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
            if (!write(Uri.fromFile(file))) return null

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
                putExtra(Intent.EXTRA_SUBJECT, displayName)
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
     * **分享**时用的类型；与 [MIME_TYPE] 刻意不同，见 `buildShareIntent` 的说明。
     */
    private const val SHARE_MIME_TYPE = "application/zip"

    /** 分享前把包临时写到这个缓存子目录 */
    private const val SHARE_DIR = "share"
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

package com.something.sthkey.data.incoming

import android.content.Context
import android.net.Uri
import com.something.sthkey.core.log.AppLog
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * **按内容嗅探**外部文件的类型。
 *
 * ============================================================
 * ⚠️⚠️ 为什么必须按内容判断
 * ============================================================
 * 外部「打开方式 / 分享」只给一个 `content://` Uri，后缀与 MIME 都不可信
 * （见 [FileKind] 的说明）。而现有两个导入器**都依赖后缀**:
 *
 * | 导入器 | 依赖后缀的地方 | 后缀丢了会怎样 |
 * |---|---|---|
 * | `FontRegistry.import` | `isSupportedFont()` 不在 `.ttf/.otf/.ttc` 里就返回 null | **静默失败** |
 * | `BitmapFontImporter.readFrom` | `.zip` 找 `ascii.png`、`.mcpack` 找 `default8.png`、否则当单张图片 | renamed zip 被当图片 → 报"读不出图片" |
 *
 * ⭐ 所以本类的产出是 **[Sniffed.kind] + [FileKind.suggestedFileName]**:
 * 判定"是什么"的同时，给现有导入器合成一个**带正确后缀**的名字。
 *
 * ============================================================
 * ⚠️ 判定顺序不能变
 * ============================================================
 * 1. zip 里的 `manifest.json` → 配置包 / 配置组合包（我们自己的格式，最确定）
 * 2. zip 里有 `*.model3.json` → Live2D（比"图片字体"更具体，必须先判）
 * 3. zip 里有 `ascii.png` / `default8.png` → 图片字体
 * 4. 图片魔数 → 单张图集
 * 5. 字体魔数 → 矢量字体
 * 6. 其它 → 未知
 *
 * ⚠️ 反过来（先判字体/图片）会把 Live2D 包误判成图片字体 —— 那类包里有贴图，
 * 按"图集"去读也能读出东西来，最后导入一个残缺的图片字体，比直接报错更糟。
 */
object FileSniffer {

    private const val TAG = "FileSniffer"

    /** 单个文件的大小上限；超过直接拒绝（防止把存储写满） */
    const val MAX_BYTES = 64L * 1024 * 1024

    /** 扫描 zip 时的条目数上限（防 zip 炸弹） */
    private const val MAX_ZIP_ENTRIES = 4000

    /** 嗅探结果 */
    data class Sniffed(
        val kind: FileKind,
        /**
         * 落到私有缓存的副本。
         *
         * ⚠️ 后续导入**必须**用这个文件而不是原 Uri:
         * 同一个 `content://` Uri 不能反复 `openInputStream`
         * （有些 provider 第二次直接抛 SecurityException 或返回空流）。
         */
        val cacheFile: File,
        /** 原文件名（可能为 null —— 外部打开时经常给不出） */
        val originalName: String?,
    )

    /**
     * 嗅探一个外部文件。
     *
     * ⚠️ 磁盘操作，调用方放 IO 线程。
     *
     * @return 失败时 `Result.failure` 里带**给用户看的原因**
     */
    fun sniff(context: Context, uri: Uri, originalName: String?): Result<Sniffed> {
        val cacheFile = copyToCache(context, uri)
            ?: return Result.failure(IOException("读不到这个文件"))

        return try {
            val kind = detectKind(context, cacheFile)
            Result.success(Sniffed(kind, cacheFile, originalName))
        } catch (e: Exception) {
            AppLog.w(TAG, "嗅探失败：${e.javaClass.simpleName}: ${e.message}")
            Result.failure(IOException("这个文件读不出来（${e.javaClass.simpleName}）"))
        }
    }

    /* ============================================================
     * 判定
     * ============================================================ */

    private fun detectKind(context: Context, file: File): FileKind {
        /* 先看魔数：字体与图片都能在几十字节内确定，不用开 zip */
        val head = readHead(file, 16)

        imageKindOf(head)?.let { return it }
        vectorFontKindOf(head)?.let { return it }

        if (!isZip(head)) return FileKind.UNKNOWN

        return detectZipKind(file)
    }

    /**
     * zip 里到底是什么。
     *
     * ⚠️ 一次遍历同时收集三种线索，**不要**为了"顺序清晰"扫三遍:
     * 大包（材质包）扫一遍就要几百毫秒。
     */
    private fun detectZipKind(file: File): FileKind {
        var hasModel3 = false
        var hasJavaAtlas = false
        var hasBedrockAtlas = false
        var manifestFormat: String? = null

        try {
            ZipFile(file).use { zip ->
                val entries = zip.entries()
                var count = 0
                while (entries.hasMoreElements()) {
                    if (++count > MAX_ZIP_ENTRIES) {
                        AppLog.w(TAG, "zip 条目超过 $MAX_ZIP_ENTRIES，放弃扫描")
                        break
                    }
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue

                    val name = entry.name.substringAfterLast('/').lowercase()
                    when {
                        name == "manifest.json" -> {
                            /* ⚠️ 读内容才能分辨配置包与组合包 */
                            manifestFormat = readManifestFormat(zip, entry.name)
                        }

                        name.endsWith(".model3.json") -> hasModel3 = true
                        name == "ascii.png" -> hasJavaAtlas = true
                        name == "default8.png" -> hasBedrockAtlas = true
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "zip 打不开：${e.javaClass.simpleName}")
            return FileKind.UNKNOWN
        }

        /*
         * ⚠️ 顺序就是优先级:我们自己的格式 > Live2D > 图片字体。
         * 理由见类注释。
         */
        return when {
            manifestFormat == FORMAT_CONFIG -> FileKind.CONFIG_PACKAGE
            manifestFormat == FORMAT_GROUP -> FileKind.GROUP_PACKAGE
            hasModel3 -> FileKind.LIVE2D_MODEL
            hasJavaAtlas -> FileKind.BITMAP_FONT_JAVA
            hasBedrockAtlas -> FileKind.BITMAP_FONT_BEDROCK
            else -> FileKind.UNKNOWN
        }
    }

    /**
     * 读 `manifest.json` 的 `format` 字段。
     *
     * ⚠️ 不在这里做完整校验:真正的校验仍在 `ConfigPackageManager` /
     * `GroupPackageManager` 里（它们会检查缺字段、版本等）。
     * 这里只是"分辨是谁的包"，读不出来就返回 null，让后续走"未知"。
     */
    private fun readManifestFormat(zip: ZipFile, entryName: String): String? = try {
        val entry = zip.getEntry(entryName) ?: return null
        /* manifest 只有几百字节，但别人做的包可能很大 —— 限个读取量 */
        val bytes = zip.getInputStream(entry).use { input -> input.readAtMost(64 * 1024) }
        REGEX_FORMAT.find(String(bytes, Charsets.UTF_8))?.groupValues?.getOrNull(1)
    } catch (e: Exception) {
        null
    }

    /* ============================================================
     * 魔数
     * ============================================================ */

    private fun isZip(head: ByteArray): Boolean =
        head.size >= 4 &&
            head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() && // "PK"
            head[2] == 0x03.toByte() && head[3] == 0x04.toByte()

    /**
     * 图片魔数 → 图片字体。
     *
     * ⚠️ 只认这三种:PNG / JPEG / WebP。
     * 别的图片格式（BMP/GIF）`BitmapFontImporter` 也读不了，认了反而误导。
     */
    private fun imageKindOf(head: ByteArray): FileKind? {
        if (head.size < 12) return null
        val png = head[0] == 0x89.toByte() && head[1] == 0x50.toByte() &&
            head[2] == 0x4E.toByte() && head[3] == 0x47.toByte()
        val jpeg = head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte()
        val webp = head[8] == 0x57.toByte() && head[9] == 0x45.toByte() &&
            head[10] == 0x42.toByte() && head[11] == 0x50.toByte()
        return if (png || jpeg || webp) FileKind.BITMAP_FONT_IMAGE else null
    }

    /**
     * 字体魔数 → 矢量字体。
     *
     * ⚠️ `ttcf` 必须排在 `ttf` 之前判断:字体集合的头部也是 `00010000` 之外
     * 的独立魔数，但两者都以 `t` 开头，靠长度区分容易错。
     */
    private fun vectorFontKindOf(head: ByteArray): FileKind? {
        if (head.size < 4) return null

        fun ascii(offset: Int, text: String): Boolean {
            if (head.size < offset + text.length) return false
            return text.indices.all { head[offset + it] == text[it].code.toByte() }
        }

        return when {
            /* TrueType: 00 01 00 00 */
            head[0] == 0x00.toByte() && head[1] == 0x01.toByte() &&
                head[2] == 0x00.toByte() && head[3] == 0x00.toByte() ->
                FileKind.VECTOR_FONT_TRUETYPE

            /* OpenType (CFF): "OTTO" */
            ascii(0, "OTTO") -> FileKind.VECTOR_FONT_OPENTYPE

            /* TrueType Collection: "ttcf" */
            ascii(0, "ttcf") -> FileKind.VECTOR_FONT_COLLECTION

            /* 老式 Apple 字体: "true" / "typ1" */
            ascii(0, "true") || ascii(0, "typ1") -> FileKind.VECTOR_FONT_TRUETYPE

            else -> null
        }
    }

    /* ============================================================
     * 工具
     * ============================================================ */

    private fun readHead(file: File, size: Int): ByteArray = try {
        file.inputStream().use { it.readAtMost(size) }
    } catch (e: Exception) {
        ByteArray(0)
    }

    /**
     * 最多读 [limit] 字节。
     *
     * ⚠️ **不要**用 `InputStream.readNBytes` —— 那是 Java 9 的 API，
     * Android 上要 **API 33+** 才有。minSdk 是 28，直接用会在旧机上抛
     * `NoSuchMethodError`，而且崩在导入路径里，看起来像"文件坏了"。
     */
    private fun java.io.InputStream.readAtMost(limit: Int): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        var total = 0
        while (total < limit) {
            val want = minOf(chunk.size, limit - total)
            val read = read(chunk, 0, want)
            if (read <= 0) break
            buffer.write(chunk, 0, read)
            total += read
        }
        return buffer.toByteArray()
    }

    /**
     * 复制到私有缓存。
     *
     * ⚠️ 有**大小上限**:先看已知大小，不知道就边读边数，超了就中止。
     * 不做这一步的话，用户点一个 2 GB 的视频，我们会先把它完整复制一份
     * 把手机写满，然后才报"不认识这个文件"。
     */
    private fun copyToCache(context: Context, uri: Uri): File? = try {
        val target = File(context.cacheDir, "incoming_${System.nanoTime()}.bin")
        var copied = 0L

        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    copied += read
                    if (copied > MAX_BYTES) {
                        output.close()
                        target.delete()
                        return null
                    }
                    output.write(buffer, 0, read)
                }
            }
        } ?: run {
            target.delete()
            return null
        }

        target
    } catch (e: Exception) {
        AppLog.w(TAG, "复制到缓存失败：${e.javaClass.simpleName}")
        null
    }

    /** 我们自己的两种包在 manifest 里的 `format` 取值 */
    private const val FORMAT_CONFIG = "sthkey-config"
    private const val FORMAT_GROUP = "sthkey-group"

    private val REGEX_FORMAT = Regex("\"format\"\\s*:\\s*\"([^\"]+)\"")
}

package com.something.sthkey.data.incoming

import android.content.Context
import android.net.Uri
import com.something.sthkey.core.log.AppLog
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

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
 *
 * ============================================================
 * ⚠️⚠️ 为什么是"流式嗅探"而不是"先复制到缓存"
 * ============================================================
 * 第一版是 `copyToCache()` 之后用 `ZipFile` 随机访问。那条路有两个硬伤:
 *
 * | 问题 | 后果 |
 * |---|---|
 * | 有一个 64 MB 的大小上限 | 实测的 Live2D 包有 **110 MB** → 直接被拒 → 提示"读不到这个文件"（用户报的 bug） |
 * | 为了嗅探**整份复制一次** | 110 MB 的包要占 220 MB（缓存 + 解压），低存储设备直接失败 |
 *
 * 现在改成:用 `ZipInputStream` **流式扫一遍条目名**（不落盘、不留副本），
 * 然后把**原 Uri** 交给导入器（每个导入器自己只需要顺序读一次）。
 */
object FileSniffer {

    private const val TAG = "FileSniffer"

    /** 扫描 zip 时的条目数上限（防 zip 炸弹） */
    private const val MAX_ZIP_ENTRIES = 4000

    /** 只读这么多个字节就够判断魔数（PNG 头 12 字节 + 字体 4 字节，留点余量） */
    private const val HEAD_BYTES = 16

    /** 嗅探结果 */
    data class Sniffed(
        val kind: FileKind,
        /** 原文件名（可能为 null —— 外部打开时经常给不出） */
        val originalName: String?,
    )

    /**
     * 嗅探一个外部文件。
     *
     * ⚠️ 只读**一次** Uri（顺序读），不产生任何临时文件。
     *
     * @return 失败时 `Result.failure` 里带**给用户看的原因**
     */
    fun sniff(context: Context, uri: Uri, originalName: String?): Result<Sniffed> {
        val head = readHead(context, uri)
            ?: return Result.failure(IOException("读不到这个文件"))

        val headKind = imageKindOf(head) ?: vectorFontKindOf(head)
        if (headKind != null) {
            return Result.success(Sniffed(headKind, originalName))
        }

        if (!isZip(head)) {
            return Result.success(Sniffed(FileKind.UNKNOWN, originalName))
        }

        /* zip:再开一次流扫条目（只读条目名，manifest.json 还要读内容） */
        return try {
            Result.success(Sniffed(detectZipKind(context, uri), originalName))
        } catch (e: Exception) {
            AppLog.w(TAG, "zip 扫描失败：${e.javaClass.simpleName}: ${e.message}")
            Result.success(Sniffed(FileKind.UNKNOWN, originalName))
        }
    }

    /* ============================================================
     * 判定
     * ============================================================ */

    /**
     * zip 里到底是什么。
     *
     * ⚠️ 一次遍历同时收集三种线索，**不要**为了"顺序清晰"扫三遍:
     * 大包（材质包）扫一遍就要几百毫秒。
     *
     * ⚠️ 用 `ZipInputStream` 而不是 `ZipFile`:后者需要随机访问，
     * 也就意味着必须先把整份文件落到磁盘上（见类注释）。
     */
    private fun detectZipKind(context: Context, uri: Uri): FileKind {
        var hasModel3 = false
        var hasJavaAtlas = false
        var hasBedrockAtlas = false
        var manifestFormat: String? = null

        val input = context.contentResolver.openInputStream(uri)
            ?: return FileKind.UNKNOWN

        input.use { stream ->
            ZipInputStream(stream.buffered()).use { zip ->
                var count = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (++count > MAX_ZIP_ENTRIES) {
                        AppLog.w(TAG, "zip 条目超过 $MAX_ZIP_ENTRIES，放弃扫描")
                        break
                    }
                    if (entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }

                    val name = entry.name.substringAfterLast('/').lowercase()
                    when {
                        name == "manifest.json" -> {
                            /* ⚠️ 读内容才能分辨配置包与组合包 */
                            manifestFormat = readManifestFormat(zip)
                        }

                        name.endsWith(".model3.json") -> hasModel3 = true
                        name == "ascii.png" -> hasJavaAtlas = true
                        name == "default8.png" -> hasBedrockAtlas = true
                    }

                    /*
                     * ⚠️ 线索齐了就不必扫完:300 个条目的 Live2D 包扫完很便宜，
                     * 但材质包可能上千条，先停能省一大截。
                     */
                    if (manifestFormat != null &&
                        (hasModel3 || hasJavaAtlas || hasBedrockAtlas)
                    ) {
                        break
                    }
                }
            }
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
     * 从流里读 `manifest.json` 的 `format` 字段。
     *
     * ⚠️ 不在这里做完整校验:真正的校验仍在 `ConfigPackageManager` /
     * `GroupPackageManager` 里（缺字段、版本等）。这里只分辨"是谁的包"，
     * 读不出来就返回 null，后续会走"未知"。
     *
     * ⚠️ 限读 64 KB:别人的包可能塞一个巨大的 manifest，读进内存不值。
     */
    private fun readManifestFormat(zip: ZipInputStream): String? = try {
        val bytes = zip.readAtMost(64 * 1024)
        REGEX_FORMAT.find(String(bytes, Charsets.UTF_8))?.groupValues?.getOrNull(1)
    } catch (e: Exception) {
        null
    }

    /* ============================================================
     * 魔数
     * ============================================================ */

    /** 只读文件开头几十个字节 */
    private fun readHead(context: Context, uri: Uri): ByteArray? = try {
        context.contentResolver.openInputStream(uri)?.use { it.readAtMost(HEAD_BYTES) }
    } catch (e: Exception) {
        AppLog.w(TAG, "读取文件头失败：${e.javaClass.simpleName}")
        null
    }

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
     * ⚠️ `ttcf` 必须排在 `ttf` 之前判断:字体集合的头部也是 `00010000` 之外的独立魔数，
     * 但两者都以 `t` 开头，靠长度区分容易错。
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

    /**
     * 最多读 [limit] 字节。
     *
     * ⚠️ **不要**用 `InputStream.readNBytes` —— 那是 Java 9 的 API，
     * Android 上要 **API 33+** 才有。minSdk 是 28，直接用会在旧机上抛
     * `NoSuchMethodError`，而且崩在导入路径里，看起来像"文件坏了"。
     */
    private fun InputStream.readAtMost(limit: Int): ByteArray {
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

    /** 我们自己的两种包在 manifest 里的 `format` 取值 */
    private const val FORMAT_CONFIG = "sthkey-config"
    private const val FORMAT_GROUP = "sthkey-group"

    private val REGEX_FORMAT = Regex("\"format\"\\s*:\\s*\"([^\"]+)\"")
}

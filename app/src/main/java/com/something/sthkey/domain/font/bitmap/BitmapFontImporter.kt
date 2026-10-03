package com.something.sthkey.domain.font.bitmap

import android.content.Context
import android.net.Uri
import com.something.sthkey.core.log.AppLog
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * 从各种来源取出**字体图集**。
 *
 * ============================================================
 * 支持三种来源，找法各不同
 * ============================================================
 * | 来源 | 扩展名 | 找什么 | 为什么 |
 * |---|---|---|---|
 * | 单张图片 | `.png`（以及其它图片） | **不检查**，直接用选中的文件 | 用户可能已经把 `ascii.png` 改过名、或从材质包里单独抠出来 |
 * | Java 材质包 | `.zip` | 包内任意位置的 **`ascii.png`** | Java 版把 ASCII 图集放在 `assets/<ns>/textures/font/ascii.png`，但材质包结构千奇百怪，按**文件名**找最稳 |
 * | 基岩版材质包 | `.mcpack` | 包内任意位置的 **`default8.png`** | 基岩版的主 ASCII 图集就叫这个名 |
 *
 * ⚠️ **按文件名在整个压缩包里递归找**，而不是写死路径。
 * 实测材质包的目录层级并不统一（有的多一层 `assets/minecraft/`、
 * 有的直接摊在根目录），写死路径会让相当一部分包导入失败，
 * 而失败信息只有"没找到"，用户无从下手。
 *
 * ⚠️ 找**第一个**匹配而不是全部：一个包里同名文件出现多次时，
 * 挑哪个都可能不对，但**让用户自己解压**又太麻烦。
 * 这里取第一个并在日志里记下路径，真出问题时能查。
 */
object BitmapFontImporter {

    private const val TAG = "BitmapFont"

    /** Java 版的 ASCII 图集名 */
    private const val JAVA_ATLAS = "ascii.png"

    /** 基岩版的主 ASCII 图集名 */
    private const val BEDROCK_ATLAS = "default8.png"

    /** 单个图集文件的大小上限；超过就拒绝，避免把内存吃满 */
    private const val MAX_ATLAS_BYTES = 16 * 1024 * 1024

    /** 压缩包内允许扫描的最大条目数，防"压缩炸弹"式的一堆小文件 */
    private const val MAX_ZIP_ENTRIES = 4096

    /** 导入来源 */
    sealed interface Source {
        /**
         * 建议用哪种遮罩模式。
         *
         * 导入时就**按像素猜一次**，而不是拖到渲染时：
         * 猜错的表现是"整块实心方块"，如果连一个默认值都没有，
         * 用户看到的第一次效果就是坏的，会直接以为这个功能不能用。
         *
         * ⚠️ 猜错不致命但很难自查，所以界面上要能手动改（后续）。
         */
        val suggestedMaskMode: MaskMode

        /** 单张图片，直接就是图集 */
        data class Image(
            val bytes: ByteArray,
            override val suggestedMaskMode: MaskMode,
        ) : Source

        /** 从压缩包里抠出来的图集 */
        data class FromArchive(
            val bytes: ByteArray,
            val pathInArchive: String,
            override val suggestedMaskMode: MaskMode,
        ) : Source

        /** 失败原因（给用户看的一句中文） */
        data class Failure(val reason: String) : Source {
            override val suggestedMaskMode: MaskMode get() = MaskMode.DEFAULT
        }
    }

    /**
     * 从 Uri 读出图集。
     *
     * 按扩展名分派：`.zip` 找 `ascii.png`、`.mcpack` 找 `default8.png`、
     * 其余一律当单张图片。
     *
     * ⚠️ 这里**不判断"是不是有效图集"**（尺寸、网格对不对）：
     * 那要看实际像素，属于 [BitmapFontSpec.cellSizeOf] 的职责。
     * 这一层只负责"把字节拿出来"。
     */
    fun readFrom(context: Context, uri: Uri): Source =
        readFrom(context, uri, queryDisplayName(context, uri))

    /** 同上，但调用方已经知道文件名时可以直接给 */
    fun readFrom(context: Context, uri: Uri, displayName: String?): Source {
        val name = (displayName ?: uri.lastPathSegment ?: "").lowercase()

        return try {
            when {
                name.endsWith(".zip") -> readFromArchive(context, uri, JAVA_ATLAS)
                name.endsWith(".mcpack") -> readFromArchive(context, uri, BEDROCK_ATLAS)

                else -> {
                    val bytes = readAll(context, uri)
                        ?: return Source.Failure("读不到所选文件")
                    if (bytes.size > MAX_ATLAS_BYTES) {
                        return Source.Failure("图片太大（超过 16 MB）")
                    }
                    Source.Image(bytes, bytes.guessMaskMode())
                }
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "读取图集失败", e)
            Source.Failure("读取失败：${e.javaClass.simpleName}")
        }
    }

    /**
     * 取 SAF 给出的文件名。
     *
     * ⚠️ **必须按文件名分派**：SAF 返回的 MIME 对 `.mcpack` 这类扩展名
     * 常常是 `application/octet-stream`（认不出来），而 `.zip` 与
     * `.mcpack` 的字节结构完全一样 —— 光看 MIME 分不清该找
     * `ascii.png` 还是 `default8.png`。
     *
     * 查不到就返回 null，由 [readFrom] 退回 `lastPathSegment`。
     */
    private fun queryDisplayName(context: Context, uri: Uri): String? = try {
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
        AppLog.w(TAG, "读取文件名失败：${e.javaClass.simpleName}")
        null
    }

    /**
     * 在压缩包里按文件名找图集。
     *
     * 用流式扫描（[ZipInputStream]）而不是 `ZipFile`：
     * 后者需要一个**真实文件路径**，而 SAF 给的是 content Uri，
     * 拿不到路径（复制一份到私有目录再扫会白写一遍磁盘）。
     */
    private fun readFromArchive(context: Context, uri: Uri, target: String): Source {
        val input = context.contentResolver.openInputStream(uri)
            ?: return Source.Failure("读不到所选压缩包")

        input.use { stream ->
            ZipInputStream(stream.buffered()).use { zip ->
                var entries = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (++entries > MAX_ZIP_ENTRIES) {
                        return Source.Failure("压缩包条目太多，已放弃查找")
                    }
                    if (entry.isDirectory) continue

                    /*
                     * 只比对**文件名**，忽略目录层级。
                     *
                     * 有的包把图集放在很深的路径下，也有的直接放根目录；
                     * 只比文件名能同时覆盖，而写死路径会漏掉一部分。
                     */
                    val entryName = entry.name.substringAfterLast('/').lowercase()
                    if (entryName != target) continue

                    val bytes = zip.readBytesCapped(MAX_ATLAS_BYTES)
                        ?: return Source.Failure("图集文件太大（超过 16 MB）")

                    AppLog.i(TAG, "在压缩包里找到图集：${entry.name}（${bytes.size} 字节）")
                    return Source.FromArchive(bytes, entry.name, bytes.guessMaskMode())
                }
            }
        }
        return Source.Failure(
            "这个压缩包里没有找到 $target —— " +
                "Java 材质包请确认里面有 ascii.png，基岩版请确认有 default8.png",
        )
    }

    /** 整份读出来；读不到返回 null */
    private fun readAll(context: Context, uri: Uri): ByteArray? = try {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (e: Exception) {
        AppLog.w(TAG, "读取文件失败：${e.javaClass.simpleName}")
        null
    }

    /**
     * 从字节里猜遮罩模式。
     *
     * 先解成像素再交给 [guessMaskMode] 判断 —— 判据是"透明像素占比"，
     * 必须看真实像素，光看字节长度没有意义。
     *
     * 解码失败时回落到默认值：真正的失败会在 [BitmapFontStore.registerFromSource]
     * 里被明确拒绝，这里不必重复报错。
     */
    private fun ByteArray.guessMaskMode(): MaskMode = try {
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(this, 0, size)
        if (bitmap == null) {
            MaskMode.DEFAULT
        } else {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            bitmap.recycle()
            guessMaskMode(ArrayPixelSource(width, height, pixels))
        }
    } catch (e: Exception) {
        AppLog.w(TAG, "判断遮罩模式失败：${e.javaClass.simpleName}")
        MaskMode.DEFAULT
    } catch (e: OutOfMemoryError) {
        AppLog.w(TAG, "判断遮罩模式时内存不足")
        MaskMode.DEFAULT
    }

    /**
     * 读取当前 zip 条目，**带大小上限**。
     *
     * ⚠️ 不能直接用 `readBytes()`：压缩包里的条目大小是元数据里写的，
     * 可以被伪造成很小而实际解出很大（压缩炸弹）。
     * 这里边读边计数，超了立刻停。
     */
    private fun ZipInputStream.readBytesCapped(limit: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = read(buffer)
            if (read <= 0) break
            out.write(buffer, 0, read)
            if (out.size() > limit) return null
        }
        return out.toByteArray()
    }
}

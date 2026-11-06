package com.something.sthkey.data.live2d

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.something.sthkey.core.log.AppLog
import com.something.sthkey.domain.live2d.TextureBudget
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 把**超大贴图**按 2 的幂降采样。
 *
 * ============================================================
 * ⚠️ 为什么必须用 `inSampleSize` 而不是"解码后缩放"
 * ============================================================
 * 一张 8192×8192 的 PNG 解码成 ARGB 就是 **256 MB**。
 * 先 `BitmapFactory.decodeFile()` 再 `Bitmap.createScaledBitmap()`
 * 意味着这 256 MB **必须先存在** —— 在手机上那一步就已经 OOM 了，
 * 后面的缩放根本没机会执行。
 *
 * `inSampleSize` 是**解码时就按比例丢像素**，内存立刻降到 1/(n²)。
 * ⚠️ 它只接受 2 的幂（传别的值内部会向下取整），这与 [TextureBudget]
 * 的算法正好一致。
 *
 * ============================================================
 * ⚠️ `inPremultiplied = false` 不能省
 * ============================================================
 * Live2D 的 WebGL 在**上传纹理时**自己做一次 alpha 预乘。
 * 如果我们在解码/重编码时先乘一次（Android 默认 `inPremultiplied = true`），
 * 那些半透明边缘就会被乘两次 —— 表现为**边缘发黑**。
 * 这是 Axon 的注释里专门写过的坑（`Live2DModelStore.adaptLargeTextures`）。
 *
 * ============================================================
 * ⚠️ 为什么不用"缩到任意尺寸"
 * ============================================================
 * 见 [TextureBudget] 的说明:UV 是归一化的，**统一等比**缩放才不用改 model3。
 */
object Live2DTextureAdapter {

    private const val TAG = "Live2DTexture"

    /**
     * @param sample 实际用的降采样倍数（1 = 没动）
     * @param count 被改写的贴图张数
     */
    data class Adapted(val sample: Int, val count: Int)

    /**
     * 按预算降采样。
     *
     * ⚠️ 磁盘与解码操作，调用方放 IO 线程。
     *
     * @param refs model3 的 `FileReferences`
     * @param modelBase model3 所在目录（贴图路径相对它）
     * @return `null` = 不需要处理（或没有贴图）
     */
    fun adapt(modelBase: File, refs: JSONObject): Adapted? {
        val textures = refs.optJSONArray("Textures") ?: return null
        if (textures.length() == 0) return null

        /* ① 先只读**尺寸**（inJustDecodeBounds 不分配像素内存） */
        val files = mutableListOf<File>()
        val sizes = mutableListOf<Pair<Int, Int>>()
        for (index in 0 until textures.length()) {
            val relative = textures.optString(index).trim()
            if (relative.isEmpty()) continue
            val file = File(modelBase, relative)
            if (!file.isFile) continue

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width <= 0 || height <= 0) {
                AppLog.w(TAG, "读不出贴图尺寸，跳过：$relative")
                continue
            }
            files += file
            sizes += width to height
        }
        if (files.isEmpty()) return null

        val sample = TextureBudget.sampleFor(sizes)
        if (sample <= 1) return null

        AppLog.i(TAG, "贴图总量超预算，降采样 ${sample}x（${files.size} 张）")

        var adapted = 0
        for (file in files) {
            if (downsample(file, sample)) adapted++
        }
        return if (adapted > 0) Adapted(sample, adapted) else null
    }

    /**
     * 把一张贴图按 [sample] 缩小并写回**原路径**。
     *
     * ⚠️ 写回原文件名（不改路径、不改 model3）:UV 归一化，换文件内容不影响引用。
     * ⚠️ 先写临时文件再替换:中途失败时原图还在，不会留下半张图。
     */
    private fun downsample(file: File, sample: Int): Boolean = try {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            /* ⚠️ 见类注释:不让系统按密度再缩放一次 */
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
            /* ⚠️ 见类注释:预乘会让半透明边缘发黑 */
            inPremultiplied = false
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
            ?: throw IOException("解码失败: ${file.name}")

        val temp = File(file.parentFile, file.name + ".tmp")
        try {
            BufferedOutputStream(FileOutputStream(temp)).use { out ->
                val format = formatOf(file.name)
                val quality = if (format == Bitmap.CompressFormat.JPEG) 95 else 100
                if (!bitmap.compress(format, quality, out)) {
                    throw IOException("编码失败: ${file.name}")
                }
            }
        } finally {
            bitmap.recycle()
        }

        if (!file.delete() || !temp.renameTo(file)) {
            /* ⚠️ 替换失败就把原图恢复回来，别把模型弄坏 */
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
        AppLog.i(TAG, "已降采样 ${file.name}（1/$sample）")
        true
    } catch (e: Exception) {
        AppLog.w(TAG, "降采样失败（保留原图）：${file.name} —— ${e.javaClass.simpleName}")
        false
    }

    /**
     * 按后缀选编码格式。
     *
     * ⚠️ PNG 必须用 PNG（无损、保留 alpha）:JPEG 没有 alpha，
     * 贴图一旦丢掉透明度，模型会出现一块块白边。
     *
     * ⚠️ `.webp` 沿用 WebP（Axon 也这么做）:改编码格式会让
     * 某些 WebView 对 `file://` 的 MIME 猜错，进而拒绝解码。
     */
    @Suppress("DEPRECATION")
    private fun formatOf(name: String): Bitmap.CompressFormat {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> Bitmap.CompressFormat.JPEG
            lower.endsWith(".webp") -> Bitmap.CompressFormat.WEBP
            else -> Bitmap.CompressFormat.PNG
        }
    }
}

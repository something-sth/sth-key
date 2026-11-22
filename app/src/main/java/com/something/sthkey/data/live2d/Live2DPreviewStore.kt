package com.something.sthkey.data.live2d

import android.graphics.Bitmap
import java.io.File

/**
 * 模型预览图的落盘位置。
 *
 * 每个**模式**一张（多模式包每个变体一套页面，预览也各算各的）：
 * `files/live2d_models/<模型id>/preview/<pageDir>.png`
 *
 * 生成方式不在这一层：这里是纯存储，负责"路径怎么定、怎么读、怎么原子写"。
 * 抓帧失败就当作没有，调用方回退到 cover/icon/占位图。
 */
object Live2DPreviewStore {

    /** 稳定的文件名：pageDir 可能带 `/`，不能直接当文件名 */
    fun fileName(pageDir: String): String {
        val trimmed = pageDir.trim().trim('/')
        val base = if (trimmed.isEmpty()) "root" else trimmed.replace('/', '_').replace('\\', '_')
        return "$base.png"
    }

    fun previewDir(modelDir: File): File = File(modelDir, "preview")

    fun previewFile(modelDir: File, pageDir: String): File = File(previewDir(modelDir), fileName(pageDir))

    /** 有没有现成的预览图（只判断文件存在，不校验尺寸） */
    fun has(modelDir: File, pageDir: String): Boolean = previewFile(modelDir, pageDir).isFile

    /**
     * 原子写：先写 `.tmp` 再改名。
     *
     * ⚠️ 直接写目标文件的话，写到一半被进程杀掉会留下**半张图**，
     * 之后每次加载都拿到损坏的 PNG（解码失败 → 占位图），且永远不会重生。
     */
    fun save(modelDir: File, pageDir: String, bitmap: Bitmap): File? {
        val target = previewFile(modelDir, pageDir)
        return runCatching {
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, target.name + ".tmp")
            temp.outputStream().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) return null
            }
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            target
        }.getOrNull()
    }

    /** 模型被删除/重命名时清掉预览，避免拖着孤儿文件 */
    fun clear(modelDir: File) {
        runCatching { previewDir(modelDir).deleteRecursively() }
    }
}

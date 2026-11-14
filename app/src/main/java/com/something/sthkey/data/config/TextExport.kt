package com.something.sthkey.data.config

import android.content.Context
import android.net.Uri

/**
 * 纯文本文件导出结果（日志等不打包 zip 的文本内容）。
 */
data class TextExportRequest(
    val fileName: String,
    val subDir: String,
    val content: String,
)

/**
 * 把纯文本写入 Download 下指定子目录。
 *
 * 与配置包共用 MediaStore / API 28 老式目录落盘与重名处理，
 * 但不套用 PackageWriter（后者语义是"给 Uri 写配置包"）。
 */
fun exportTextToDownloads(
    context: Context,
    request: TextExportRequest,
): ConfigExporters.ExportResult = ConfigExporters.exportToDownloads(
    context = context,
    write = { uri ->
        context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
            output.write(request.content.toByteArray(Charsets.UTF_8))
            true
        } ?: false
    },
    fileName = request.fileName,
    subDir = request.subDir,
)

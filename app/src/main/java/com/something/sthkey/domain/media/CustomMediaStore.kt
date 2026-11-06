package com.something.sthkey.domain.media

import android.content.Context
import android.net.Uri
import com.something.sthkey.core.log.AppLog
import com.something.sthkey.domain.custom.MediaKind
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * 自定义 Key 的**图片 / 动图 / 视频资源库**。
 *
 * ============================================================
 * 与字体库、Live2D 模型库对齐的设计
 * ============================================================
 * 1. **全局而非按配置**:导入一次，所有配置的组件都能引用；配置里只存 [MediaEntry.id]。
 * 2. **文件落在应用私有目录**:`filesDir/custom_media/<uuid>/<原文件名>`，
 *    之后即使原文件被删也能用。
 * 3. **元信息就放在同一个目录的 `index.json`**，不塞进 SharedPreferences ——
 *    资源可能很多（视频几十个），prefs 里塞大 JSON 会拖慢每次读取。
 *
 * ⚠️ 用户明确说过**不做数量与体积上限**（"等回头我更新公告里写一下"），
 * 所以这里只做"能不能读/写"的校验，不做配额。
 */
object CustomMediaStore {

    private const val TAG = "CustomMedia"
    private const val DIR_NAME = "custom_media"
    private const val INDEX_NAME = "index.json"

    /** 一条资源的元信息 */
    data class MediaEntry(
        val id: String,
        val displayName: String,
        val kind: MediaKind,
        /** 目录内的文件名（保留原扩展名，解码器/播放器靠它判断格式） */
        val fileName: String,
        val sizeBytes: Long,
        /** 内容摘要，用于"同一个文件再导入就复用" */
        val digest: String,
    )

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun all(): List<MediaEntry> = readIndex()

    fun find(id: String): MediaEntry? = readIndex().firstOrNull { it.id == id }

    /** 资源文件的真实路径；文件丢了返回 null */
    fun fileOf(entry: MediaEntry): File? {
        val context = appContext ?: return null
        val file = File(File(root(context), entry.id), entry.fileName)
        return file.takeIf { it.isFile }
    }

    fun fileOfId(id: String): File? = find(id)?.let { fileOf(it) }

    /** 显示名（配置页/列表用） */
    fun displayNameOf(id: String): String = find(id)?.displayName ?: "（资源已删除）"

    /**
     * 从 SAF 的 Uri 导入一个资源。
     *
     * ⚠️ 磁盘操作，调用方放 IO 线程。
     *
     * @param kind 组件声明的类型；决定"这个文件能不能收"
     * @param displayNameHint 已知显示名（外部导入时由调用方给）
     */
    fun import(
        context: Context,
        uri: Uri,
        kind: MediaKind,
        displayNameHint: String? = null,
    ): Result<MediaEntry> = try {
        val name = (displayNameHint?.takeIf { it.isNotBlank() }
            ?: queryDisplayName(context, uri)
            ?: "资源")
            .substringAfterLast('/')
            .ifBlank { "资源" }

        val id = UUID.randomUUID().toString().replace("-", "")
        val dir = File(root(context), id)
        if (!dir.mkdirs()) error("无法创建资源目录")

        val file = File(dir, name)
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        } ?: error("读不到这个文件")

        if (file.length() <= 0L) {
            dir.deleteRecursively()
            error("这个文件是空的")
        }

        val digest = sha256Of(file)

        /*
         * ⚠️ 同一个文件重复导入 → 复用已有条目。
         *
         * 用户从相册反复选同一张图很常见，不去重就会在库里堆一串一模一样的资源。
         */
        readIndex().firstOrNull { it.digest == digest }?.let { existing ->
            dir.deleteRecursively()
            AppLog.i(TAG, "资源内容与已有条目一致，复用：${existing.displayName}")
            return Result.success(existing)
        }

        val entry = MediaEntry(
            id = id,
            displayName = nextAvailableName(name, readIndex()),
            kind = kind,
            fileName = name,
            sizeBytes = file.length(),
            digest = digest,
        )
        writeIndex(readIndex() + entry)
        AppLog.i(TAG, "已导入资源「${entry.displayName}」（${kind.label}，${file.length()} 字节）")
        Result.success(entry)
    } catch (e: Exception) {
        AppLog.e(TAG, "导入资源失败", e)
        Result.failure(e)
    }

    /**
     * 从**已有的本地文件**导入（配置包解包后走这里）。
     *
     * ⚠️ 与 [import] 的区别:那条走 SAF Uri（相册），这条走包内解出来的文件。
     * 去重逻辑相同 —— 同一个文件（sha256 一样）已经存在时直接返回**已有条目**，
     * 调用方要据此做 `旧 id → 新 id` 的映射（见 `ConfigPackageManager`）。
     */
    fun importFile(source: File, kind: MediaKind, name: String): MediaEntry? = try {
        val digest = sha256Of(source)
        readIndex().firstOrNull { it.digest == digest }?.let { existing ->
            AppLog.i(TAG, "资源内容与已有条目一致，复用：${existing.displayName}")
            return existing
        }

        val id = UUID.randomUUID().toString().replace("-", "")
        val dir = File(rootOrNull() ?: error("资源库未初始化"), id)
        if (!dir.mkdirs()) error("无法创建资源目录")
        val fileName = name.substringAfterLast('/').ifBlank { "资源" }
        val target = File(dir, fileName)
        source.copyTo(target, overwrite = true)

        val entry = MediaEntry(
            id = id,
            displayName = nextAvailableName(fileName, readIndex()),
            kind = kind,
            fileName = fileName,
            sizeBytes = target.length(),
            digest = digest,
        )
        writeIndex(readIndex() + entry)
        entry
    } catch (e: Exception) {
        AppLog.e(TAG, "从文件导入资源失败", e)
        null
    }

    /**
     * 删掉一个资源。
     *
     * ⚠️ **不检查有没有组件在用它** —— 由调用方（库界面）负责提示；
     * 引用已删除资源的组件会渲染成占位框（见渲染层的说明），不会崩。
     */
    fun delete(id: String): Boolean {
        val context = appContext ?: return false
        val remaining = readIndex().filterNot { it.id == id }
        File(root(context), id).deleteRecursively()
        writeIndex(remaining)
        return true
    }

    /* ============================================================
     * 内部
     * ============================================================ */

    private fun root(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { mkdirs() }

    private fun rootOrNull(): File? = appContext?.let { root(it) }

    private fun indexFile(context: Context): File = File(root(context), INDEX_NAME)

    private fun readIndex(): List<MediaEntry> {
        val context = appContext ?: return emptyList()
        val file = indexFile(context)
        if (!file.isFile) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    val fileName = item.optString("fileName")
                    if (id.isBlank() || fileName.isBlank()) continue
                    val kind = MediaKind.fromId(item.optString("kind")) ?: MediaKind.IMAGE
                    add(
                        MediaEntry(
                            id = id,
                            displayName = item.optString("name").ifBlank { fileName },
                            kind = kind,
                            fileName = fileName,
                            sizeBytes = item.optLong("size", 0L),
                            digest = item.optString("digest"),
                        ),
                    )
                }
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "资源索引解析失败，按空处理", e)
            emptyList()
        }
    }

    private fun writeIndex(entries: List<MediaEntry>) {
        val context = appContext ?: return
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("id", entry.id)
                    put("name", entry.displayName)
                    put("kind", entry.kind.id)
                    put("fileName", entry.fileName)
                    put("size", entry.sizeBytes)
                    put("digest", entry.digest)
                },
            )
        }
        runCatching { indexFile(context).writeText(array.toString()) }
            .onFailure { AppLog.e(TAG, "写资源索引失败", it) }
    }

    private fun nextAvailableName(name: String, existing: List<MediaEntry>): String {
        val used = existing.map { it.displayName }.toSet()
        if (name !in used) return name
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var index = 2
        while (true) {
            val candidate = if (ext.isEmpty()) "$base ($index)" else "$base ($index).$ext"
            if (candidate !in used) return candidate
            index++
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else {
                null
            }
        }
    }.getOrNull()

    /** 库里一共占多少字节（设置页显示用） */
    fun totalBytes(): Long = readIndex().sumOf { it.sizeBytes }

    /** 仅测试/诊断用：当前根目录 */
    internal fun rootForDebug(): File? = rootOrNull()
}

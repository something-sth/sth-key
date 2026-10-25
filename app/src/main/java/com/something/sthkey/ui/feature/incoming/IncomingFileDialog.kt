package com.something.sthkey.ui.feature.incoming

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.something.sthkey.data.config.ConfigPackageManager
import com.something.sthkey.data.config.GroupPackageManager
import com.something.sthkey.data.incoming.FileKind
import com.something.sthkey.data.incoming.FileSniffer
import com.something.sthkey.data.incoming.IncomingFile
import com.something.sthkey.data.live2d.Live2DModelImporter
import com.something.sthkey.domain.font.bitmap.BitmapFontImporter
import com.something.sthkey.domain.font.FontRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


/**
 * 外部「打开方式 / 分享」进来之后的**统一导入对话框**。
 *
 * ============================================================
 * ⚠️ 一个入口，按**内容**分派（不是按后缀）
 * ============================================================
 * 用户的原话:
 *
 * > 有用户反馈导入字体 / live2d 太麻烦，想像导入配置那样，
 * > 直接从外部打开方式导入进来
 *
 * | 嗅探结果 | 落到哪 |
 * |---|---|
 * | 配置包 / 配置组合包 | 配置页（**行为与以前完全一致**） |
 * | 矢量字体 | 设置 → 库 → 字体库 |
 * | 图片字体 | 字体库（⚠️ 要先让用户确认名字） |
 * | Live2D 模型 | 设置 → 库 → Live2D 库 |
 *
 * ⚠️ 字体 / 模型是**全局资源**:外部导入时根本不知道用户正在编辑哪份配置，
 * 所以**不会**顺手把它设成某份配置的字体/模型。导入只进库。
 *
 * @param onFinished 用户关掉对话框（宿主清空请求）
 * @param onNeedBitmapNaming 图片字体需要确认名字。
 *   ⚠️ 宿主必须先把这个对话框关掉再弹命名窗口 —— 两个 `AlertDialog` 同时开着时，
 *   部分 ROM 会把先弹的那个顶掉（表现为"命名窗口一闪就没了"）。
 */
@Composable
fun IncomingFileDialog(
    request: IncomingFile.Request?,
    onFinished: () -> Unit,
    onNeedBitmapNaming: (BitmapFontImporter.Source) -> Unit,
) {
    if (request == null) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    /* ⚠️ 状态机而不是几个布尔:几个布尔会出现"既在识别又在显示结果"的中间态 */
    var phase by remember(request) { mutableStateOf<Phase>(Phase.Sniffing) }

    /*
     * 嗅探阶段复制出来的缓存文件。
     *
     * ⚠️ **必须**在离开时统一删掉:一个材质包动辄几十 MB，
     * 用户在确认框上点了「取消」的话，导入那一步根本不会跑，
     * 也就没人删它 —— 一次取消就永久占掉几十 MB 的 cacheDir。
     */
    val cacheFiles = remember(request) { mutableListOf<File>() }
    DisposableEffect(request) {
        onDispose {
            cacheFiles.forEach { runCatching { it.delete() } }
        }
    }

    /*
     * 识别：进来就做，不等用户点确定（他首先要知道"这文件能不能用"）。
     */
    LaunchedEffect(request) {
        val items = withContext(Dispatchers.IO) {
            request.uris.map { uri -> sniffOne(context, uri) }
        }

        /* ⚠️ 登记缓存，离开时统一删（见 cacheFiles 的说明） */
        items.forEach { item -> item.cacheFile?.let(cacheFiles::add) }

        /* ⚠️ 一份都认不出来就直接进结果页，不给一个"确认导入什么"的空框 */
        phase = if (items.none { it.kind != FileKind.UNKNOWN }) {
            Phase.Done(items.map { ItemResult.Failed(it.displayName, it.error ?: unknownReason()) })
        } else {
            Phase.Confirm(items)
        }
    }

    val title = when (val current = phase) {
        Phase.Sniffing -> "正在识别"
        is Phase.Confirm -> if (request.fromShare) "导入分享的文件" else "导入打开的文件"
        Phase.Working -> "正在导入"
        is Phase.Done -> if (current.results.any { it.ok }) "导入完成" else "导入失败"
    }

    AlertDialog(
        /* ⚠️ 导入中不允许点外面关掉:关掉不会取消已经开始的写入，用户会以为取消了 */
        onDismissRequest = { if (phase != Phase.Working) onFinished() },
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                when (val current = phase) {
                    Phase.Sniffing -> Progress()

                    is Phase.Confirm -> Column {
                        Text(
                            text = "识别到 ${current.items.count { it.kind != FileKind.UNKNOWN }} 个可以导入的文件:",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        SniffedList(current.items)
                    }

                    Phase.Working -> Progress()

                    is Phase.Done -> Column {
                        ResultList(current.results)
                        if (current.results.any { it is ItemResult.FontOk || it is ItemResult.ModelOk }) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "字体与模型都可以在「设置 → 库」里查看、改名或删除。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (val current = phase) {
                is Phase.Confirm -> TextButton(
                    onClick = {
                        phase = Phase.Working
                        scope.launch {
                            val results = withContext(Dispatchers.IO) {
                                current.items.map { runImport(context, it) }
                            }

                            /*
                             * ⚠️ 图片字体要确认名字:先把这一批里有名字需求的交出去，
                             * 再显示结果。宿主会在 onFinished() 之后弹命名窗口。
                             */
                            results.filterIsInstance<ItemResult.NeedsNaming>()
                                .forEach { onNeedBitmapNaming(it.source) }


                            phase = Phase.Done(results)
                        }
                    },
                ) {
                    Text("导入")
                }

                Phase.Sniffing, Phase.Working -> Unit

                is Phase.Done -> TextButton(onClick = onFinished) { Text("好") }
            }
        },
        dismissButton = {
            /* ⚠️ 只有"确认"阶段才有取消;完成/进行中都只留一个按钮，避免两个按钮含义相同 */
            if (phase is Phase.Confirm) {
                TextButton(onClick = onFinished) { Text("取消") }
            }
        },
    )
}

/* ============================================================
 * 识别
 * ============================================================ */

/** 一个文件识别出来之后的样子 */
internal data class SniffedItem(
    val uri: Uri,
    val kind: FileKind,
    val displayName: String,
    val cacheFile: File?,
    val error: String? = null,
)

private fun sniffOne(context: Context, uri: Uri): SniffedItem {
    val name = queryDisplayName(context, uri)
    val result = FileSniffer.sniff(context, uri, name)
    val sniffed = result.getOrNull()
        ?: return SniffedItem(
            uri = uri,
            kind = FileKind.UNKNOWN,
            displayName = name ?: uri.lastPathSegment.orEmpty(),
            cacheFile = null,
            error = result.exceptionOrNull()?.message ?: "读不到这个文件",
        )

    return SniffedItem(
        uri = uri,
        kind = sniffed.kind,
        displayName = sniffed.originalName ?: uri.lastPathSegment.orEmpty(),
        cacheFile = sniffed.cacheFile,
    )
}

/* ============================================================
 * 导入
 * ============================================================ */

/**
 * 执行一个文件的导入。
 *
 * ⚠️ 一律用嗅探阶段复制出来的**缓存文件**（包成 `file://` Uri）:
 * 同一个 `content://` Uri 不能反复 `openInputStream`。
 *
 * ⚠️ 每个导入器**只调一次**并把 `Result` 存下来 ——
 * 写成 `?: return Failed(importer(...).exceptionOrNull())` 会**导两遍**
 * （一遍进库、一遍只为了拿错误信息），既慢又可能生成两份数据。
 */
internal fun runImport(context: Context, item: SniffedItem): ItemResult {
    if (item.error != null) return ItemResult.Failed(item.displayName, item.error)
    val cacheFile = item.cacheFile ?: return ItemResult.Failed(item.displayName, "读不到这个文件")
    val cacheUri = Uri.fromFile(cacheFile)

    return try {
        when (item.kind) {
            FileKind.CONFIG_PACKAGE -> {
                val result = ConfigPackageManager.import(context, cacheUri)
                val outcome = result.getOrNull()
                if (outcome == null) {
                    ItemResult.Failed(item.displayName, describe(result.exceptionOrNull()))
                } else {
                    ItemResult.ConfigOk(outcome.config.name)
                }
            }

            FileKind.GROUP_PACKAGE -> {
                val result = GroupPackageManager.import(context, cacheUri)
                val outcome = result.getOrNull()
                if (outcome == null) {
                    ItemResult.Failed(item.displayName, describe(result.exceptionOrNull()))
                } else {
                    ItemResult.GroupOk(outcome.group.name, outcome.importedNames.size)
                }
            }

            FileKind.LIVE2D_MODEL -> {
                /*
                 * ⚠️ 必须把名字传进去:我们给的是自己的缓存文件，
                 * 导入器查不到 `DISPLAY_NAME`，会落到兜底的 `live2d-model`。
                 */
                val result = Live2DModelImporter.importZip(
                    context = context,
                    uri = cacheUri,
                    displayNameHint = namedForImport(item.displayName, item.kind),
                )
                val outcome = result.getOrNull()
                if (outcome == null) {
                    ItemResult.Failed(item.displayName, describe(result.exceptionOrNull()))
                } else {
                    ItemResult.ModelOk(
                        name = outcome.entry.displayName,
                        reused = outcome.reused,
                    )
                }
            }

            FileKind.BITMAP_FONT_JAVA,
            FileKind.BITMAP_FONT_BEDROCK,
            FileKind.BITMAP_FONT_IMAGE,
            -> {
                /*
                 * ⚠️ 名字由**嗅探结果**决定（`.zip` / `.mcpack` / `.png`）：
                 * `BitmapFontImporter` 就是按后缀决定"去哪找图集"的。
                 */
                val source = BitmapFontImporter.readFrom(
                    context,
                    cacheUri,
                    namedForImport(item.displayName, item.kind),
                )
                if (source is BitmapFontImporter.Source.Failure) {
                    ItemResult.Failed(item.displayName, source.reason)
                } else {
                    ItemResult.NeedsNaming(source)
                }
            }

            FileKind.VECTOR_FONT_TRUETYPE,
            FileKind.VECTOR_FONT_OPENTYPE,
            FileKind.VECTOR_FONT_COLLECTION,
            -> {
                val outcome = FontRegistry.importDetailed(
                    uri = cacheUri,
                    displayNameHint = namedForImport(item.displayName, item.kind),
                )
                if (outcome == null) {
                    ItemResult.Failed(item.displayName, "这个字体文件已损坏，或用了不支持的格式")
                } else {
                    ItemResult.FontOk(outcome.entry.displayName, reused = outcome.reused)
                }
            }

            FileKind.UNKNOWN -> ItemResult.Failed(item.displayName, unknownReason())
        }
    } catch (e: Exception) {
        ItemResult.Failed(item.displayName, e.message ?: e.javaClass.simpleName)
    } finally {
        /*
         * ⚠️ 缓存文件用完就删:一次导入几十 MB 很常见，
         * 不删的话 cacheDir 会被这些临时包堆满。
         */
        cacheFile.delete()
    }
}

/* ============================================================
 * 内部类型
 * ============================================================ */

private sealed interface Phase {
    data object Sniffing : Phase
    data class Confirm(val items: List<SniffedItem>) : Phase
    data object Working : Phase
    data class Done(val results: List<ItemResult>) : Phase
}

/** 单个文件的导入结果 */
internal sealed interface ItemResult {
    val ok: Boolean

    data class Failed(val name: String, val reason: String) : ItemResult {
        override val ok: Boolean get() = false
    }

    data class ConfigOk(val configName: String) : ItemResult {
        override val ok: Boolean get() = true
    }

    data class GroupOk(val groupName: String, val count: Int) : ItemResult {
        override val ok: Boolean get() = true
    }

    /**
     * @param reused 库里已经有同一份内容（按 sha256 判定）
     */
    data class FontOk(val name: String, val reused: Boolean) : ItemResult {
        override val ok: Boolean get() = true
    }

    data class ModelOk(val name: String, val reused: Boolean) : ItemResult {
        override val ok: Boolean get() = true
    }

    /** 图片字体需要用户确认名字（不算成功也不算失败，交给命名窗口） */
    data class NeedsNaming(val source: BitmapFontImporter.Source) : ItemResult {
        override val ok: Boolean get() = true
    }
}

/* ============================================================
 * UI 片段
 * ============================================================ */

@Composable
private fun Progress() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
    }
}

/** 识别阶段的列表：名字 + 类型（或错误原因） */
@Composable
private fun SniffedList(items: List<SniffedItem>) {
    ScrollList(items.size) { index ->
        val item = items[index]
        TwoLine(
            name = item.displayName.ifBlank { "（没有文件名）" },
            detail = item.error ?: item.kind.label,
            isError = item.error != null,
        )
    }
}

/**
 * 结果阶段的列表。
 *
 * ⚠️ 文案按用户要求写成 **已加入字体库 / 已加入 Live2D 库**:
 * 只说"导入成功"的话用户不知道东西去哪了。
 */
@Composable
private fun ResultList(results: List<ItemResult>) {
    ScrollList(results.size) { index ->
        val result = results[index]
        val (name, detail, isError) = when (result) {
            is ItemResult.Failed -> Triple(result.name, result.reason, true)
            is ItemResult.ConfigOk -> Triple(result.configName, "已导入为配置", false)
            is ItemResult.GroupOk ->
                Triple(result.groupName, "已导入配置组合（${result.count} 个配置）", false)
            is ItemResult.FontOk -> Triple(
                result.name,
                if (result.reused) "这个字体已经在字体库里" else "已加入字体库",
                false,
            )
            is ItemResult.ModelOk -> Triple(
                result.name,
                if (result.reused) "这个模型已经在 Live2D 库里" else "已加入 Live2D 库",
                false,
            )
            is ItemResult.NeedsNaming ->
                Triple("图片字体", "请在接下来的命名窗口里确认名称", false)
        }
        TwoLine(name = name, detail = detail, isError = isError)
    }
}

@Composable
private fun ScrollList(count: Int, content: @Composable (Int) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        repeat(count) { index ->
            if (index > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
            content(index)
        }
    }
}

@Composable
private fun TwoLine(name: String, detail: String, isError: Boolean) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.labelSmall,
            color = if (isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/* ============================================================
 * 工具
 * ============================================================ */

/**
 * 把异常翻成用户能看懂的一句话。
 *
 * ⚠️ 只用 `message` 会让用户看到
 * "java.io.IOException: ..." 或者干脆 null（很多异常没 message）。
 */
/**
 * 给导入器的文件名:**原文件名 + 嗅探出的正确后缀**。
 *
 * ⚠️ 为什么不是直接用 `suggestedFileName`:
 * 那是「字体.ttf」这种占位名，导进库里用户看到的全叫「字体」。
 * 而原文件名又不能直接用 —— 它可能没有后缀（`xxx.bin`），
 * 而 `BitmapFontImporter` / `FontRegistry` 都是**按后缀**分派的。
 *
 * 所以:拿原名的**主体**，接上嗅探出来的**后缀**。
 */
internal fun namedForImport(originalName: String, kind: FileKind): String {
    val suggested = kind.suggestedFileName ?: return originalName
    val extension = suggested.substringAfterLast('.', "")
    val fallbackBase = suggested.substringBeforeLast('.')

    /* 原名可能带任意后缀（甚至没有），只取主体 */
    val base = originalName.trim().substringBeforeLast('.').ifBlank { fallbackBase }
    return if (extension.isBlank()) base else "$base.$extension"
}

private fun describe(error: Throwable?): String = when (error) {
    null -> "导入失败"
    else -> error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
}

private fun unknownReason(): String =
    "不认识这个文件。支持:配置包(.sthkey)、配置组合(.sthgroup)、" +
        "字体(.ttf/.otf/.ttc)、图片字体(材质包 zip / mcpack)、Live2D 模型(zip)"

/** 读文件名；读不到返回 null（外部打开时经常没有） */
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
    null
}

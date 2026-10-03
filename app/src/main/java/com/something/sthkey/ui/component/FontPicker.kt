package com.something.sthkey.ui.component

import androidx.compose.foundation.Canvas
import com.something.sthkey.ui.overlay.BitmapTextLayout
import com.something.sthkey.ui.overlay.layoutBitmapText
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.something.sthkey.domain.font.FontEntry
import com.something.sthkey.domain.font.FontKind
import com.something.sthkey.domain.font.FontRegistry
import com.something.sthkey.domain.font.bitmap.BitmapFontSpec
import com.something.sthkey.domain.font.bitmap.BitmapFontStore
import com.something.sthkey.ui.overlay.BitmapFontText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * ============================================================
 * 字体选择：**两个独立的入口**，不是一个列表里的单选
 * ============================================================
 * 常规字体（矢量）与图片字体（Minecraft 图集）不是二选一：
 *
 * - 图片字体**只有 ASCII 字形**，中文必须由常规字体兜底；
 * - 所以真实关系是「**常规字体（必选）+ 图片字体（可选）**」。
 *
 * 早先把两者混在一个列表里做单选，后果是：选了图片字体之后中文没有退路
 * （回落到系统默认，而不是用户选的字体），而且配置里只有一个 `fontId`，
 * 根本表达不了"两个都要"。
 *
 * 现在配置有 `fontId` + `bitmapFontId` 两个字段，界面上也就是两个入口。
 *
 * ⚠️ 实现上有一个 Kotlin 可见性的坑（踩了很久）：
 * [FontPickerDialog] / [BitmapFontPickerDialog] 是公开函数，
 * 而它们要传一个"行操作"对象给内部列表。如果那个类型是 private，
 * **公开函数暴露私有类型会让声明被判为无效**，于是调用处报的却是
 * "语法错误"，看起来像文件被写坏了 —— 极难定位。
 *
 * 现在改成：[FontRowActions] 是 public，而 [FontListDialog] 是 internal，
 * 列表内容用普通 lambda 传（不带接收者），两边都不越界。
 */

/**
 * 选择**常规字体**（必选）。
 *
 * ⚠️ 列表**不含**图片字体（见 [FontRegistry.vectorFonts]）——
 * 混进来会让人以为它们是同一层的东西。
 *
 * @param selectedId 当前选中的常规字体 id
 * @param onSelect 选中回调；对话框不自己关闭，由调用方决定
 * @param onImport 触发导入（SAF 文件选择在调用方，因为需要 Activity）
 * @param refreshKey 外部变更标记。
 *   **导入是从这个对话框外面发起的**（SAF 选择器要 Activity，所以由调用方启动），
 *   导入完成后调用方把这个值 +1，这里才会重新读字体列表 ——
 *   否则会出现"导入成功了但列表里没有，得关掉重开才看得到"。
 */
@Composable
fun FontPickerDialog(
    selectedId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    onImport: () -> Unit,
    refreshKey: Int = 0,
) {
    FontListDialog(
        title = "选择常规字体",
        importLabel = "导入字体文件",
        importHint = "支持 .ttf / .otf / .ttc。系统字体与内置字体不可删除。\n" +
            "字体对所有配置可见；图片字体在「图片字体」那一项里单独选。",
        onDismiss = onDismiss,
        onImport = onImport,
        refreshKey = refreshKey,
    ) { actions ->
        /*
         * 按来源分组（系统 / 内置 / 导入）。
         *
         * 用 [FontRegistry.vectorFonts] 而不是 `all()`：图片字体不在这个
         * 对话框的管辖范围内，混进来会让人以为它们是二选一。
         */
        val fonts = FontRegistry.vectorFonts()
        listOf(FontKind.SYSTEM, FontKind.BUILTIN, FontKind.IMPORTED)
            .forEachIndexed { index, kind ->
                val group = fonts.filter { it.kind == kind }
                if (group.isEmpty()) return@forEachIndexed

                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                FontGroupHeader(kind.label)
                group.forEach { font -> FontListRow(font, selectedId, onSelect, actions) }
            }
    }
}

/**
 * 选择**图片字体**（可选）。
 *
 * 比常规字体多两件事：
 *
 * 1. **可以取消选择**（"不使用"那一项）—— 它是可选的，选了之后必须能
 *    退回去，否则用户只能靠换配置来摆脱它；
 * 2. **可以导入**（图片 / Java 资源包 / 基岩版资源包）。
 *
 * @param selectedId 当前选中的图片字体 id；空字符串表示不使用
 * @param onImport 触发导入图片字体（SAF 选择在调用方）
 */
@Composable
fun BitmapFontPickerDialog(
    selectedId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    onImport: () -> Unit,
    refreshKey: Int = 0,
) {
    FontListDialog(
        title = "选择图片字体",
        importLabel = "导入图片字体",
        importHint = "支持 .png 图片，或 Java 材质包（.zip，自动找 ascii.png）、" +
            "基岩版材质包（.mcpack，自动找 default8.png）。\n" +
            "图片字体**只能显示 ASCII**（中文仍用常规字体）。",
        onDismiss = onDismiss,
        onImport = onImport,
        refreshKey = refreshKey,
    ) { actions ->
        /*
         * "不使用"这一项。
         *
         * 放在列表最前面、而不是做成一个开关：它和"选某个字体"是同一件事的
         * 两个结果（用 / 不用），放在同一个列表里用户一眼就懂，
         * 也不会有"开关关了但字体还留着"的中间态。
         */
        FontListRow(
            entry = FontEntry(
                id = "",
                displayName = "不使用图片字体",
                kind = FontKind.BITMAP,
                subtitle = "只用常规字体",
            ),
            selectedId = selectedId,
            onSelect = onSelect,
            actions = actions,
        )

        val fonts = FontRegistry.bitmapFonts()
        if (fonts.isEmpty()) {
            Text(
                text = "还没有导入图片字体。\n\n" +
                    "可以导入 Minecraft 的字体图（例如 ascii.png），" +
                    "也可以直接选一个 Java 材质包（.zip）或基岩版材质包（.mcpack），" +
                    "会自动从包里找。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            FontGroupHeader("已导入的图片字体")
            fonts.forEach { font -> FontListRow(font, selectedId, onSelect, actions) }
        }
    }
}

/**
 * 行上的"改名 / 删除"回调。
 *
 * 这样列表内容由调用方画，而**状态仍集中在 [FontListDialog] 里** ——
 * 调用方不可能"忘记接上改名"，因为回调是外壳递过来的。
 *
 * ⚠️ 必须是 public：它出现在公开函数的 lambda 参数类型里，
 * 而 Kotlin 不允许公开函数暴露更窄可见性的类型（详见文件头那段说明）。
 */
class FontRowActions(
    val onRename: (FontEntry) -> Unit,
    val onDelete: (FontEntry) -> Unit,
)

/**
 * 两个选择对话框的公共外壳。
 *
 * 抽出来是因为它们的差别只有文案与列表内容，而"改名 / 删除"那套子对话框
 * **完全一样** —— 各写一份的话就会出现"常规字体能改名、图片字体不能"
 * 这种不对称的 bug（第一版正是这样：改名/删除写死在常规字体那一页里，
 * 图片字体那一页根本没接上，于是点了一点反应都没有）。
 *
 * ⚠️ 它是 `internal`：`list` 参数带 [FontRowActions]，
 * 而这个外壳不需要对外公开（两个对话框才是公开入口）。
 *
 * @param list 列表内容。它拿到 [FontRowActions]，自己决定画哪些字体。
 */
@Composable
internal fun FontListDialog(
    title: String,
    importLabel: String,
    importHint: String,
    onDismiss: () -> Unit,
    onImport: () -> Unit,
    refreshKey: Int,
    list: @Composable (actions: FontRowActions) -> Unit,
) {
    val scope = rememberCoroutineScope()

    /** 对话框内部的变更（改名/删除）自增它；外部变更用 refreshKey */
    var localRevision by remember { mutableIntStateOf(0) }
    var renaming by remember { mutableStateOf<FontEntry?>(null) }
    var deleting by remember { mutableStateOf<FontEntry?>(null) }

    /*
     * `refreshKey` 只用来"让列表重读"：FontRegistry / BitmapFontStore 都是
     * 普通对象、不是 Compose 状态，导入完成后必须靠这个令牌触发重组。
     */
    val actions = remember(localRevision, refreshKey) {
        FontRowActions(
            onRename = { renaming = it },
            onDelete = { deleting = it },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    list(actions)

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(importLabel)
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = importHint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )

    /* ---------------- 重命名 ---------------- */

    renaming?.let { target ->
        var name by remember(target.id) { mutableStateOf(target.displayName) }
        val valid = name.isNotBlank()

        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("重命名字体") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("字体名称") },
                    isError = !valid,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = target.id
                        val newName = name
                        renaming = null
                        scope.launch {
                            /*
                             * ⚠️ 用 [renameFont] 而不是 `FontRegistry.rename`。
                             *
                             * 后者只认 `imported:` 前缀，对图片字体是**静默失效**的：
                             * 点了确定、界面刷新、名字没变，也不报错。
                             */
                            withContext(Dispatchers.IO) { renameFont(id, newName) }
                            localRevision++
                        }
                    },
                    enabled = valid,
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text("取消") }
            },
        )
    }

    /* ---------------- 删除 ---------------- */

    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除字体？") },
            text = {
                Text(
                    "将删除「${target.displayName}」。\n" +
                        "如果某个配置正在使用它，那个配置会回落到默认字体。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = target.id
                        deleting = null
                        scope.launch {
                            // 同上：必须按类型分派，否则图片字体删不掉
                            withContext(Dispatchers.IO) { deleteFont(id) }
                            localRevision++
                        }
                    },
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            },
        )
    }
}

/** 一个分组的小标题 */
@Composable
private fun FontGroupHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

/**
 * 列表里的一行。
 *
 * 可改名、可删除的只有**用户导入的**两类（矢量导入 + 图片字体）：
 * 系统与内置字体删不掉，给它们一个删除按钮只会让人点了没反应。
 *
 * `id` 为空的"不使用图片字体"那一项也走这里 —— 它不是字体，
 * 只是列表里的一个选项，所以同样不给改名/删除。
 */
@Composable
private fun FontListRow(
    entry: FontEntry,
    selectedId: String,
    onSelect: (String) -> Unit,
    actions: FontRowActions,
) {
    val editable = entry.id.isNotEmpty() &&
        (entry.kind == FontKind.IMPORTED || entry.kind == FontKind.BITMAP)

    FontRow(
        entry = entry,
        selected = entry.id == selectedId,
        onClick = { onSelect(entry.id) },
        onRename = if (editable) ({ actions.onRename(entry) }) else null,
        onDelete = if (editable) ({ actions.onDelete(entry) }) else null,
    )
}

/**
 * 一行字体。
 *
 * 名称与样例文字都**用该字体自身渲染** —— 这是选字体时最有用的信息。
 * 系统字体加载失败（个别 ROM 上族名不认）时回落到默认字体，
 * 至少不会因为一个字体坏了整页崩掉。
 */
@Composable
private fun FontRow(
    entry: FontEntry,
    selected: Boolean,
    onClick: () -> Unit,
    onRename: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    val composeFont = composeFontFamily(entry.id)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Spacer(modifier = Modifier.height(2.dp))

            /*
             * ⚠️ 图片字体的样例**不含中文**。
             *
             * 它的图集里根本没有中文字形，带中文那部分会回退成常规字体 ——
             * 看起来像"这个字体只有一半是好的"，而实际上它本来就只能画 ASCII。
             * 预览要如实反映字体能画什么。
             */
            val bitmapSpec = remember(entry.id) { bitmapSpecOf(entry.id) }

            if (bitmapSpec != null) {
                /*
                 * ⚠️ 这里**不用 `BitmapFontText`**，而是自己按字形贴图。
                 *
                 * 原因：`BitmapFontText` 在"图集加载不出来"时是**静默返回**的
                 * （什么都不画），那样用户看到的是一个空行，完全不知道
                 * 是"字体坏了"还是"这个字体本来就长这样"。
                 *
                 * 这里自己扫一遍字形，于是能把真正的原因显示出来 ——
                 * "图片字体显示成默认字体"那个问题就是靠这个才好定位的。
                 */
                val specimen = remember(entry.id) { buildBitmapSpecimen(entry.id) }

                if (specimen == null) {
                    Text(
                        text = "⚠ 这个图片字体加载失败（图集丢失或网格不匹配）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                    )
                } else {
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(SPECIMEN_HEIGHT_DP.dp),
                    ) {
                        drawBitmapSpecimen(specimen, size)
                    }
                }
            } else {
                Text(
                    text = FONT_SAMPLE,
                    fontFamily = composeFont,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
            }
        }

        onRename?.let { action ->
            IconButton(onClick = action) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "重命名",
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        onDelete?.let { action ->
            IconButton(onClick = action) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除",
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        if (selected) {
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                Icons.Default.Check,
                contentDescription = "当前使用",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/*
 * ============================================================
 * 字体解析
 * ============================================================
 */

/**
 * 解析出 Compose 的字体族。
 *
 * - **系统字体** → 对应通用族（见 [systemFontFamily]）；
 * - **内置与导入字体** → `Font(file)`，文件由 [FontRegistry.resolveFile] 提供。
 *
 * 解析不出来时回落到默认字体，不会因为字体坏了画不出字。
 *
 * ⚠️ **图片字体不走这里**：它没有可用的字体文件（图集不是字体），
 * 必须由 `BitmapFontText` 逐字贴图。传图片字体的 id 进来会落到
 * `FontFamily.Default`，这正是"图片字体显示成系统字体"那个现象的来源 ——
 * 所以调用方要先判断 [bitmapSpecOf] 再用哪个渲染器。
 */
@Composable
fun composeFontFamily(fontId: String): FontFamily {
    val entry = remember(fontId) { FontRegistry.find(fontId) }
    val file = remember(fontId) { FontRegistry.resolveFile(fontId) }

    return remember(entry?.id, file?.path) {
        when {
            entry?.kind == FontKind.SYSTEM -> systemFontFamily(entry.id)
            file != null -> FontFamily(Font(file))
            else -> FontFamily.Default
        }
    }
}

/**
 * 取一个字体对应的**图片字体规格**；矢量字体或找不到时返回 null。
 *
 * ============================================================
 * 为什么需要这个判断，而不是只看 `FontKind`
 * ============================================================
 * "要不要走位图渲染"取决于**两件事**：
 *
 * 1. 这个字体是不是图片字体（这里回答）；
 * 2. 这段文字能不能用它画 —— 图片字体只支持纯 ASCII，
 *    文字里出现中文时必须回退常规字体。
 *
 * 第 2 条需要看文字内容，所以判据不能只放在字体上。这里只回答第 1 条，
 * 第 2 条由 `canRenderAsBitmap` 回答（见 `ui/overlay/BitmapFontText.kt`）。
 */
fun bitmapSpecOf(fontId: String?): BitmapFontSpec? =
    if (fontId != null && BitmapFontStore.isBitmapFont(fontId)) {
        BitmapFontStore.specOf(fontId)
    } else {
        null
    }

/*
 * ============================================================
 * 改名 / 删除：按**字体类型**分派
 * ============================================================
 * ⚠️ 这两件事以前直接调 [FontRegistry]，而它只认 `imported:` 前缀 ——
 * 于是对 `bitmap:` 的图片字体，改名和删除都**静默失效**：
 * 点了确定、界面刷新、名字没变、条目还在。
 *
 * 两套库本来就分开存（矢量在 FontRegistry，图片在 BitmapFontStore），
 * 所以分派必须在这里做，而不是指望某一个库同时认两种 id。
 */

/** 改名字体；图片字体与矢量字体都支持。返回是否成功 */
fun renameFont(fontId: String, newName: String): Boolean =
    if (BitmapFontStore.isBitmapFont(fontId)) {
        BitmapFontStore.rename(fontId, newName)
    } else {
        FontRegistry.rename(fontId, newName)
    }

/** 删除字体；图片字体与矢量字体都支持。返回是否成功 */
fun deleteFont(fontId: String): Boolean =
    if (BitmapFontStore.isBitmapFont(fontId)) {
        BitmapFontStore.delete(fontId)
    } else {
        FontRegistry.delete(fontId)
    }

/** 系统通用族 → Compose 的字体族 */
private fun systemFontFamily(fontId: String): FontFamily {
    val family = fontId.removePrefix("system:")
    return when (family) {
        "serif" -> FontFamily.Serif
        "monospace" -> FontFamily.Monospace
        // 其余统一用无衬线（当前系统字体只保留这一个）
        else -> FontFamily.SansSerif
    }
}

/** 样例文字：覆盖大小写字母与数字，能看出字体的主要特征 */
private const val FONT_SAMPLE = "ABCDEFG abcdefg 0123 按键显示"

/**
 * 图片字体的样例：**只有 ASCII**。
 *
 * 与 [FONT_SAMPLE] 是两份而不是"随便裁一段"：样例要能体现字体的棱角
 * 与字距，而中文那部分对图片字体没有意义（图集里根本没有）。
 */
private const val FONT_SAMPLE_ASCII = "ABCDEFG abcdefg 0123"

/** 图片字体样例行的高度（dp） */
private const val SPECIMEN_HEIGHT_DP = 26

/**
 * 预先算好的"样例排版"。
 *
 * 把图集、字形表与排好的结果一起带着，绘制时不必再查一遍 ——
 * 也顺便让"这个字体到底能不能画"变成一个**可以判空的事实**，
 * 而不是等到画的时候才发现画不出来。
 */
private class BitmapSpecimen(
    val atlas: androidx.compose.ui.graphics.ImageBitmap,
    val font: BitmapFontStore.BitmapFont,
    val layout: BitmapTextLayout,
    val fontSizePx: Float,
)

/**
 * 为预览扫一遍字形并排好样例文字；加载失败返回 null。
 *
 * ⚠️ **不要在组合期同步调用**：一张 512×512 的图集要遍历 26 万像素，
 * 同步做会让打开对话框时卡一下。所以只在 [remember] 里做一次
 * （`remember(entry.id)` 保证同一个字体只算一次，滚动列表不会重复扫）。
 */
private fun buildBitmapSpecimen(fontId: String): BitmapSpecimen? {
    val font = BitmapFontStore.load(fontId) ?: return null
    val size = 20f
    val layout = layoutBitmapText(font, FONT_SAMPLE_ASCII, size)
    if (layout.lines.all { it.isEmpty() }) return null
    return BitmapSpecimen(
        atlas = font.atlasImage,
        font = font,
        layout = layout,
        fontSizePx = size,
    )
}

/**
 * 把样例画出来。
 *
 * 与 `BitmapFontText` 用的是**同一套排版结果**（[layoutBitmapText]），
 * 所以预览里看到的字距与悬浮窗里是一致的 —— 否则会出现
 * "预览好看、实际挤在一起"这种没法解释的差别。
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBitmapSpecimen(
    specimen: BitmapSpecimen,
    size: androidx.compose.ui.geometry.Size,
) {
    val layout = specimen.layout
    val originX = (size.width - layout.widthPx) / 2f
    val originY = (size.height - layout.heightPx) / 2f

    layout.lines.forEach { line ->
        line.forEach { placed ->
            drawImage(
                image = specimen.atlas,
                srcOffset = androidx.compose.ui.unit.IntOffset(
                    placed.glyph.srcX,
                    placed.glyph.srcY,
                ),
                srcSize = androidx.compose.ui.unit.IntSize(
                    placed.glyph.srcWidth,
                    placed.glyph.srcHeight,
                ),
                dstOffset = androidx.compose.ui.unit.IntOffset(
                    kotlin.math.round(originX + placed.x).toInt(),
                    kotlin.math.round(originY + placed.y).toInt(),
                ),
                dstSize = androidx.compose.ui.unit.IntSize(
                    kotlin.math.round(placed.width).toInt().coerceAtLeast(1),
                    kotlin.math.round(placed.height).toInt().coerceAtLeast(1),
                ),
                filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
            )
        }
    }
}

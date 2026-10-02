package com.something.sthkey.ui.feature.config

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DashboardCustomize
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.something.sthkey.data.config.ConfigPackageCodec
import com.something.sthkey.data.config.ConfigPackageManager
import com.something.sthkey.data.config.JsonConfigCodec
import com.something.sthkey.domain.config.KeyStrokesConfig
import com.something.sthkey.domain.style.OverlayStyleDescriptor
import com.something.sthkey.domain.style.OverlayStyleRegistry
import com.something.sthkey.ui.MainViewModel
import com.something.sthkey.ui.component.ScrollableScreen
import com.something.sthkey.ui.component.SectionHint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 配置列表页（配置 Tab 的首页）。
 *
 * ============================================================
 * 这一版：只做增删改，"当前配置"的概念已经删除
 * ============================================================
 * 旧版本点一下卡片 = 把它设为"当前生效的配置"，悬浮窗就显示它。
 * 多悬浮窗之后**每一份开着的配置都有自己的窗口**（开关在主页），
 * "当前是哪一份"不再对应任何真实状态，所以：
 *
 * - 卡片不再有"使用中"的高亮；
 * - 点卡片 = 编辑它（最常用的动作，省掉一次瞄准小按钮）；
 * - 悬浮窗开关统一在主页，这里不重复放一份（同一个设置出现在两个地方，
 *   迟早出现"这里关了那里还亮着"）。
 *
 * 关于"拖动排序"：这一版**没有做**。之前那版实现质量太差（拖动过程与列表重排
 * 互相干扰，手感很糟），与其留着一个难用的功能，不如先去掉，
 * 等有精力时再单独做一版（配置顺序本身已经按列表顺序保存，接口还在）。
 */
@Composable
fun ConfigListScreen(
    viewModel: MainViewModel,
    onEditConfig: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // 每次重组都从仓库取最新列表：配置只有个位数，复制开销可忽略，
    // 换来的是"增删/改名后列表一定是最新的"，不需要手动维护刷新令牌
    val configs = viewModel.allConfigs()

    // 重命名不改变列表长度，靠版本号保证列表刷新
    val listRevision = viewModel.configVersion
    val rows = configs.chunked(2)

    var deleting by remember { mutableStateOf<KeyStrokesConfig?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }

    /** 导出目标配置；非 null 时说明用户点了"导出" */
    var exporting by remember { mutableStateOf<KeyStrokesConfig?>(null) }

    /**
     * 提交给系统选择器的建议文件名。
     *
     * 单独存一份而不是在回调里重算：配置可能在这期间被改名，而
     * **"我们请求的是哪个名字"是判断系统有没有背地里改名的依据** ——
     * 重算出来的名字与实际请求的不一致，清理逻辑就会失效（见 export 的说明）。
     */
    var exportingName by remember { mutableStateOf("") }

    /** 导入结果（成功）与失败原因，用于弹报告 */
    var importReport by remember { mutableStateOf<ConfigPackageManager.ImportResult?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }

    /** 导入/导出都是磁盘操作，放协程里 */
    val scope = rememberCoroutineScope()

    /*
     * 导出：先用系统选择器让用户决定存哪、叫什么名。
     *
     * CreateDocument 会带上我们建议的文件名（含 .sthkey 后缀），
     * 用户可以改。它返回目标 Uri，我们再把 zip 写进去 ——
     * 这样不需要任何存储权限。
     */
    val exportLauncher = rememberLauncherForActivityResult(
        /*
         * MIME 传通配符，而不是 application/octet-stream。
         *
         * 传具体 MIME 时，部分文件管理器会因为"类型与后缀不匹配"
         * 强行改成它认识的组合，于是 .sthkey 变成了 .bin。
         * 通配符下系统不会去纠后缀，我们给什么就是什么。
         */
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val target = exporting
        val suggestedName = exportingName
        exporting = null
        exportingName = ""
        if (uri == null || target == null) return@rememberLauncherForActivityResult

        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                // 传上建议的文件名：导出内部靠"实际名字与它不一致"来识别
                // "系统替我改名造了个空文件"，那才允许清理（见 ConfigPackageManager.export）
                ConfigPackageManager.export(context, target, uri, suggestedName)
            }
            notice = if (ok) {
                "已导出「${target.name}」"
            } else {
                "导出失败，请重试（若目录里出现了 0 字节的同名文件，可以删掉它再试）"
            }
        }
    }

    /*
     * 导入：让用户挑一个配置包。
     *
     * MIME 传通配符而不是具体类型：sthkey 是自定义后缀，
     * 系统不认识它，用具体 MIME 会导致文件在选择器里灰掉选不中。
     * 选完之后我们自己校验 manifest，不是我们的包会明确报错。
     *
     * 注意这里不能把通配符原样写进注释 —— 它的字符序列里含有块注释的
     * 结束标记，会让注释提前闭合，后面的文字变成裸代码而报一堆语法错。
     */
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult

        scope.launch {
            val result = withContext(Dispatchers.IO) { ConfigPackageManager.import(context, uri) }
            result
                .onSuccess { report ->
                    viewModel.reloadConfigs()
                    importReport = report
                }
                .onFailure { error ->
                    importError = when (val reason = (error as? ConfigPackageManager.ImportException)?.error) {
                        ConfigPackageManager.ImportError.NotAPackage ->
                            "这不是 sth key 的配置包（manifest 格式标识不匹配）"

                        ConfigPackageManager.ImportError.MissingManifest ->
                            "配置包缺少 manifest.json，文件可能不完整"

                        ConfigPackageManager.ImportError.MissingParams ->
                            "配置包缺少 params.json，文件可能不完整"

                        is ConfigPackageManager.ImportError.Broken -> reason.message
                        null -> error.message ?: "导入失败"
                    }
                }
        }
    }

    ScrollableScreen(
        title = "配置",
        subtitle = "点击卡片编辑它；悬浮窗开关在主页",
        modifier = modifier,
        actions = {
            /*
             * 导入入口做成带文字的按钮，而不是一个孤零零的图标。
             *
             * "下载/上传"这类图标本身没有公认语义，单独放一个用户得猜；
             * 配上「导入配置」字样就一目了然，也顺便和"导出"在视觉上配对。
             */
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.padding(end = 8.dp),
            ) {
                Icon(
                    Icons.Default.FileDownload,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("导入配置")
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "新建配置")
            }
        },
    ) {
        rows.forEachIndexed { rowIndex, rowItems ->
            item(key = "row_${listRevision}_${rowItems.firstOrNull()?.id ?: rowIndex}") {
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val cardWidth = (maxWidth - 12.dp) / 2

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        rowItems.forEach { config ->
                            ConfigCard(
                                config = config,
                                width = cardWidth,
                                // 点卡片即编辑：这是这一页最常用的动作。
                                // 悬浮窗开关在主页，这里不重复放一份。
                                onClick = { onEditConfig(config.id) },
                                onExport = {
                                    val name = config.name + ConfigPackageCodec.EXTENSION
                                    exporting = config
                                    // 记下建议的文件名：导出内部靠它识别"系统替我改名了"
                                    exportingName = name
                                    exportLauncher.launch(name)
                                },
                                onDuplicate = {
                                    val created = viewModel.duplicateConfig(config.id)
                                    notice = "已复制为「${created.name}」"
                                },
                                onDelete = { deleting = config },
                            )

                            if (rowItems.size == 1) {
                                // 奇数个配置时补一个空位，避免最后一张卡片被拉伸到整行
                                Spacer(modifier = Modifier.width(cardWidth))
                            }
                        }
                    }
                }
            }
        }

        item {
            SectionHint(
                text = "「Default」为内置配置，无法删除或改名，用于恢复出厂设置。" +
                    "复制任意配置后可以在编辑页里随意调整。",
            )
        }

        item {
            SectionHint(
                text = "要在屏幕上显示某个配置，请到主页打开它那一行的开关；" +
                    "可以同时开多个，各自有独立的悬浮窗。",
            )
        }

        notice?.let { message ->
            item {
                SectionHint(text = message)
            }
        }
    }

    /*
     * ============================================================
     * 新建配置
     * ============================================================
     */

    if (showCreateDialog) {
        CreateConfigDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, description, styleId ->
                val created = viewModel.createConfig(name, description, styleId)
                showCreateDialog = false
                notice = "已新建「${created.name}」（${created.style.label}）"
            },
        )
    }

    /*
     * ============================================================
     * 导入结果
     *
     * 这两个弹窗必须真的渲染出来 —— 之前只给 importReport / importError
     * 赋值却没有对应的 UI，导致"导入成功"和"导入失败"都毫无反应，
     * 看起来像按钮坏了。
     * ============================================================
     */

    importReport?.let { report ->
        ImportReportDialog(report = report, onDismiss = { importReport = null })
    }

    importError?.let { message ->
        AlertDialog(
            onDismissRequest = { importError = null },
            title = { Text("导入失败") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { importError = null }) { Text("知道了") }
            },
        )
    }

    /*
     * ============================================================
     * 删除
     * ============================================================
     */

    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除配置？") },
            text = {
                Text(
                    if (target.builtIn) {
                        "「${target.name}」是内置配置，不能删除。"
                    } else {
                        "将删除「${target.name}」，此操作不可撤销。"
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val ok = viewModel.deleteConfig(target.id)
                        notice = if (ok) "已删除「${target.name}」" else "内置配置无法删除"
                        deleting = null
                    },
                    enabled = !target.builtIn,
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

/**
 * 单个配置卡片。
 *
 * 卡片本身 = "编辑这一份"；导出 / 复制 / 删除是卡片内部的小按钮，
 * 点它们不会触发编辑（按钮自带点击处理，不会冒泡到卡片的 clickable）。
 */
@Composable
private fun ConfigCard(
    config: KeyStrokesConfig,
    width: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onExport: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = modifier
            .width(width)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            /*
             * 第一行：名称
             */
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = config.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = config.description.ifBlank { config.style.description },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = config.style.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(modifier = Modifier.height(2.dp))

            /*
             * 操作区：三个独立图标（编辑已经等于"点整张卡片"，不再重复放一个按钮）。
             *
             * 用菜单虽然省地方，但"要多点一次才能看到有什么操作"，观感也差。
             *
             * 宽度是这里唯一的麻烦：卡片只有半屏宽，扣掉内边距后，
             * 360dp 屏上大约只剩 134dp —— 四个 36dp 的按钮并排需要 150dp，
             * 直接溢出（标题会被挤没）。去掉编辑按钮后三个刚好宽裕，
             * 但仍然**按权重均分**而不是固定宽度：无论屏幕多窄都刚好排满、不会溢出，
             * 图标始终居中。触摸高度保持 36dp 不缩，手感不受影响。
             *
             * 这里不画缩略预览：卡片太窄，按键文字会挤错位，
             * 预览只在编辑页里看（那里的尺寸足够）。
             */

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                CompactIconButton(
                    icon = Icons.Default.SaveAlt,
                    description = "导出",
                    onClick = onExport,
                    modifier = Modifier.weight(1f),
                )
                CompactIconButton(
                    icon = Icons.Default.ContentCopy,
                    description = "复制",
                    onClick = onDuplicate,
                    modifier = Modifier.weight(1f),
                )
                CompactIconButton(
                    icon = Icons.Default.Delete,
                    description = "删除",
                    onClick = onDelete,
                    enabled = !config.builtIn,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 紧凑图标按钮。
 *
 * 宽度交给调用方（通常传 weight(1f)），这里只管高度与点击区：
 * 高度固定 36dp，既满足"可点击区域不小于 36dp"的可用性要求，
 * 又不会像默认 IconButton 的 48dp 那样把半屏卡片撑爆。
 */
@Composable
private fun CompactIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(36.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(18.dp),
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.outline
            },
        )
    }
}

/**
 * 新建配置对话框。
 *
 * 与旧项目一致：先把名称、描述、**样式**选好再创建。
 * 不做"直接复制当前配置" —— 新建是从零开始，复制是继承来源，两件事分开。
 *
 * 样式以卡片形式横排展示：每个样式给图标 + 名称 + 一句话说明。
 * 未实现的样式（LIVE 2D）置灰不可选，但**仍然显示**，
 * 这样用户知道后面会有什么，而不是觉得功能缺失。
 */
@Composable
private fun CreateConfigDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, description: String, styleId: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var styleId by remember { mutableStateOf(OverlayStyleRegistry.defaultStyleId) }

    val styles = remember { OverlayStyleRegistry.all() }
    val valid = name.isNotBlank() && OverlayStyleRegistry.find(styleId)?.enabled == true

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建配置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("名称") },
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("描述（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = "样式",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    styles.forEach { style ->
                        StyleOption(
                            style = style,
                            selected = styleId == style.id,
                            modifier = Modifier.weight(1f),
                            onSelect = { if (style.enabled) styleId = style.id },
                        )
                    }
                }

                Text(
                    text = "不同样式的配置互不通用，创建后不能切换样式。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), description.trim(), styleId) },
                enabled = valid,
            ) {
                Text("创建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 新建对话框里的样式选项卡片 */
@Composable
private fun StyleOption(
    style: OverlayStyleDescriptor,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = when {
        !style.enabled -> MaterialTheme.colorScheme.surfaceContainerLow
        selected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainer
    }

    Card(
        modifier = modifier.then(
            if (style.enabled) Modifier.clickable(onClick = onSelect) else Modifier,
        ),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = style.icon(),
                contentDescription = null,
                tint = if (style.enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.outline
                },
                modifier = Modifier.size(24.dp),
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = style.label,
                style = MaterialTheme.typography.titleMedium,
                color = if (style.enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.outline
                },
                maxLines = 1,
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = if (style.enabled) "可用" else "敬请期待",
                style = MaterialTheme.typography.labelSmall,
                color = if (style.enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
            )
        }
    }
}

/** 样式 → 图标：样式层只给标识，映射放在 UI 层 */
private fun OverlayStyleDescriptor.icon(): ImageVector = when (iconKey) {
    OverlayStyleRegistry.ICON_KEYBOARD -> Icons.Default.Keyboard
    OverlayStyleRegistry.ICON_LIVE2D -> Icons.Default.Person
    OverlayStyleRegistry.ICON_CUSTOM -> Icons.Default.DashboardCustomize
    else -> Icons.Default.Tune
}

/**
 * 导入结果报告。
 *
 * ============================================================
 * 为什么要有这个弹窗
 * ============================================================
 * 配置结构会随版本演进，导入一份"来自其它版本"的配置时，
 * 双方字段不可能完全对齐。如果不说明，用户只会看到
 * "有些设置没生效/有些设置丢了"却不知道为什么。
 *
 * 因此这里**双向都报**：
 * - **未识别字段**：配置里有、本版本没有 —— 通常是新版功能，已跳过；
 * - **缺失字段**：本版本有、配置里没写 —— 已使用默认值。
 *
 * 只报"缺失"会漏掉一半信息（导入新版配置到旧版时，用户看不到设置被丢了）。
 * 默认折叠，避免一次刷出几十行；需要细节时再展开。
 */
@Composable
private fun ImportReportDialog(
    report: ConfigPackageManager.ImportResult,
    onDismiss: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (report.hasDifferences) "导入完成（有差异）" else "导入成功") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = buildString {
                        append("已导入「${report.config.name}」")
                        if (report.renamed) {
                            append("（原名称「${report.originalName}」，因重名已自动改名）")
                        }
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )

                Text(
                    text = "包版本：格式 v${report.packageFormatVersion}" +
                        if (report.packageAppVersion.isNotBlank()) {
                            " · 导出自 ${report.packageAppVersion}"
                        } else {
                            ""
                        },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                /* ---- 字体 ---- */

                if (report.importedFonts.isNotEmpty()) {
                    ReportSection(
                        title = "已加入字体库",
                        lines = report.importedFonts,
                        hint = "这些字体对所有配置可用，无需重复导入",
                    )
                }

                if (report.reusedFonts.isNotEmpty()) {
                    ReportSection(
                        title = "复用了已有的字体",
                        lines = report.reusedFonts,
                        hint = "内容相同，没有重复占用空间",
                    )
                }

                if (report.missingFonts.isNotEmpty()) {
                    ReportSection(
                        title = "⚠ 字体缺失（已回落为默认字体）",
                        lines = report.missingFonts,
                        hint = "包里没带这些字体。用到它们的地方已回落为默认字体 —— " +
                            "到「自定义编辑 → 外观」里给那些组件重新选一个即可，其余组件不受影响",
                        titleColor = MaterialTheme.colorScheme.error,
                    )
                }

                /* ---- Live2D 模型 ---- */

                if (report.importedModels.isNotEmpty()) {
                    ReportSection(
                        title = "已加入模型库",
                        lines = report.importedModels,
                        hint = "该模型对所有配置可用，无需重复导入",
                    )
                }

                if (report.reusedModels.isNotEmpty()) {
                    ReportSection(
                        title = "复用了已有的模型",
                        lines = report.reusedModels,
                        hint = "内容相同，没有重复占用空间",
                    )
                }

                if (report.modelMissing) {
                    Text(
                        text = "⚠ 配置包里的 Live2D 模型缺失或导入失败，该配置已回落到内置模型。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                /* ---- 字段差异 ---- */

                if (report.unknownKeys.isNotEmpty() || report.missingKeys.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Text(
                        text = "字段差异 " +
                            "（未识别 ${report.unknownKeys.size} · 默认值 ${report.missingKeys.size}）",
                        style = MaterialTheme.typography.titleMedium,
                    )

                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "收起详情" else "展开详情")
                    }

                    if (expanded) {
                        if (report.unknownKeys.isNotEmpty()) {
                            ReportSection(
                                title = "未识别的字段（已跳过）",
                                lines = report.unknownKeys.map { JsonConfigCodec.labelOf(it) },
                                hint = "这些多半来自更新版本的功能，本版本无法应用",
                            )
                        }
                        if (report.missingKeys.isNotEmpty()) {
                            ReportSection(
                                title = "配置中未定义的项（已用默认值）",
                                lines = report.missingKeys.map { JsonConfigCodec.labelOf(it) },
                                hint = "这些项在配置包里没有写，因此使用本版本的默认值",
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        },
    )
}

/** 报告里的一个小节：标题 + 若干条目 + 一句说明 */
@Composable
private fun ReportSection(
    title: String,
    lines: List<String>,
    hint: String,
    titleColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleSmall, color = titleColor)
        lines.forEach { line ->
            Text(
                text = "· $line",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

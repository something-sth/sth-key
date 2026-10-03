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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.something.sthkey.core.prefs.AppPrefs
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
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
import android.net.Uri
import android.widget.Toast
import com.something.sthkey.data.config.ExportMethod
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

    /*
     * ============================================================
     * 列表排版：由**设置页**决定（这里的入口不提供切换）
     * ============================================================
     * ⚠️ 刻意**不在这一页放排版切换**。
     *
     * 这一页是"挑配置、编辑配置"的地方，排版是"一次性偏好"——
     * 混在一起会让每次操作都要面对一堆与当前任务无关的开关。
     * 而且顶部已经有「导入配置」按钮，再塞切换控件会越来越挤。
     *
     * 偏好从 `AppPrefs` 读：它是**应用级**的，不属于任何一份配置，
     * 所以导出配置时不会把"你的列表长什么样"带给别人。
     */
    val prefs = remember { AppPrefs.get(context) }
    var showDescription by remember { mutableStateOf(prefs.configListShowDescription) }
    var compactActions by remember { mutableStateOf(prefs.configListCompactActions) }
    var columns by remember { mutableStateOf(prefs.configListColumns) }

    /*
     * 每次回到这一页（ON_RESUME）重新读一次偏好。
     *
     * ============================================================
     * ⚠️ 为什么不能只在 `remember` 里读一次
     * ============================================================
     * 用户去设置页改完再回来时，这个页面在导航栈里可能**没有被销毁重建**，
     * 于是看到的还是旧排版 —— 表现为"设置里改了没生效，要重启应用才行"。
     *
     * 也**不能用 `LaunchedEffect(某个 key)`**：那个 key 在一次组合里是
     * 常量，`LaunchedEffect` 只在首次组合时跑一次，返回这一页时并不会重跑
     * （这一点很容易写错，而且症状和"完全没写"一模一样）。
     * 必须真正监听生命周期事件。
     */
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                showDescription = prefs.configListShowDescription
                compactActions = prefs.configListCompactActions
                columns = prefs.configListColumns
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val rows = configs.chunked(columns)

    var deleting by remember { mutableStateOf<KeyStrokesConfig?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }

    /** 导入失败的原因，用于弹错误框 */
    var importError by remember { mutableStateOf<String?>(null) }

    /*
     * ============================================================
     * 一次性提示改用 **Toast**
     * ============================================================
     * 以前这些提示画在页面**最下面**一行小字（`notice` + `SectionHint`）——
     * 而配置一多，那一行就在屏幕之外。用户点了导出、什么都没看见，
     * 只能怀疑是不是没成功。
     *
     * Toast 浮在界面上方，与页面滚到哪无关，而且这个项目里
     * 编辑器/调试页早就这么做了。
     */
    /* `context` 在本函数开头已经取过（导入那段也要用），这里不重复声明 */
    val showToast: (String) -> Unit = remember(context) {
        { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    /** 待选择导出方式的配置；非 null 时说明弹窗开着 */
    var choosingExportFor by remember { mutableStateOf<KeyStrokesConfig?>(null) }

    val exportFlow = rememberExportFlow(onMessage = showToast)

    /**
     * 点「导出」：按已设的默认方式走，没设过（`ASK`）就弹选择窗口。
     *
     * ⚠️ 每次点击都**重新读一次偏好**，而不是用组合期的快照：
     * 用户可能刚去设置页改了默认方式再回来。
     */
    fun startExport(config: KeyStrokesConfig) {
        when (val method = prefs.exportMethod) {
            ExportMethod.ASK -> choosingExportFor = config
            else -> exportFlow.start(config, method, false)
        }
    }

    /** 导入结果（成功），用于弹报告 */
    var importReport by remember { mutableStateOf<ConfigPackageManager.ImportResult?>(null) }

    /** 导入是磁盘操作，放协程里 */
    val scope = rememberCoroutineScope()

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
            item(key = "row_${listRevision}_${columns}_${rowItems.firstOrNull()?.id ?: rowIndex}") {
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    /*
                     * 卡片宽度 = (可用宽 − 间隙总和) / 列数。
                     *
                     * `columns - 1` 段间隙：n 张卡片之间有 n-1 个缝。
                     * 用 `coerceAtLeast(1)` 只是防除零（列数由 AppPrefs
                     * 夹在 1..6，正常不会为 0）。
                     */
                    val gap = 12.dp
                    val gaps = gap * (columns - 1).coerceAtLeast(0)
                    val cardWidth = (maxWidth - gaps) / columns.coerceAtLeast(1)

                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        rowItems.forEach { config ->
                            ConfigCard(
                                config = config,
                                width = cardWidth,
                                showDescription = showDescription,
                                compactActions = compactActions,
                                // 点卡片即编辑：这是这一页最常用的动作。
                                // 悬浮窗开关在主页，这里不重复放一份。
                                onClick = { onEditConfig(config.id) },
                                onExport = { startExport(config) },
                                onDuplicate = {
                                    val created = viewModel.duplicateConfig(config.id)
                                    showToast("已复制为「${created.name}」")
                                },
                                onDelete = { deleting = config },
                            )
                        }

                        /*
                         * 末行不满时补空位，避免最后几张卡片被拉伸。
                         *
                         * ⚠️ 换成 `columns` 列之后这里不能再用"只有 1 张就补"：
                         * 一行 3 列但只剩 2 张时，那 2 张会因为
                         * `spacedBy` + 固定宽度而**左对齐留白**（本来就是这样），
                         * 所以补齐只是为了不让 `Row` 把它们拉宽 —— 而宽度是
                         * 显式给的，不会被拉伸。因此**不需要补位**。
                         */
                        repeat((columns - rowItems.size).coerceAtLeast(0)) {
                            // 占位：保证这一行的卡片宽度与其它行一致（不参与布局宽度计算）
                            Spacer(modifier = Modifier.width(cardWidth))
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
                showToast("已新建「${created.name}」（${created.style.label}）")
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

    /*
     * ============================================================
     * 导出方式选择
     * ============================================================
     * 只在"没有默认导出方式"时出现（或用户主动来改时）。
     * 勾了「设为默认」之后下次点导出直接按那个方式走，不再弹这个窗口。
     */
    choosingExportFor?.let { target ->
        ExportMethodDialog(
            configName = target.name,
            onPick = { method, remember ->
                choosingExportFor = null
                exportFlow.start(target, method, remember)
            },
            onDismiss = { choosingExportFor = null },
        )
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
                        showToast(if (ok) "已删除「${target.name}」" else "内置配置无法删除")
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
    /** 是否显示描述文字（设置页可关，关掉后卡片更紧凑） */
    showDescription: Boolean = true,
    /** 是否把导出/复制/删除收进「更多」菜单 */
    compactActions: Boolean = false,
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
             * 名称：独占一整行。
             *
             * ⚠️ 紧凑模式下**不能**把「更多」按钮放进这一行 ——
             * 卡片可能只有 1/6 屏宽，一个 36dp 的按钮就能把标题挤没
             * （用户实测：配置名字直接消失）。按钮改到右下角，见下。
             */
            Text(
                text = config.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )

            /*
             * 描述：可以被设置页关掉。
             *
             * 关掉之后**整块不占位置**（而不是留一段空白）——
             * 否则"关掉描述"只是少了一行字，卡片高度没变，达不到紧凑的目的。
             *
             * ⚠️ 没有描述时也整块不渲染：`config.description` 与
             * `config.style.description` 都可能是空的，那时留着就是一个空行。
             */
            val description = config.description.ifBlank { config.style.description }
            if (showDescription && description.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = config.style.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            /*
             * ============================================================
             * 操作区 —— 两种排布，都在卡片的**右下角**
             * ============================================================
             * - **平铺**（默认）：三个独立图标。编辑已经等于"点整张卡片"，
             *   所以不再重复放一个编辑按钮。
             * - **紧凑**：收进一个「更多」菜单，**右对齐**放在同一位置。
             *
             * ⚠️ 紧凑模式下的按钮**不能挤进标题那一行**：
             * 卡片可能只有 1/6 屏宽，一个 36dp 的按钮足以把标题挤没 ——
             * 用户实测的现象是"配置名字直接消失"。
             * 放到右下角之后标题独占一行，多窄都不会被挤掉。
             *
             * ⚠️ 平铺时的宽度是另一个麻烦：卡片可能只有 1/6 屏宽，
             * 扣掉内边距后在窄屏上只剩几十 dp。所以**按权重均分**而不是
             * 固定宽度：无论多窄都刚好排满、不会溢出，图标始终居中。
             * 触摸高度保持 36dp 不缩，手感不受影响。
             *
             * ⚠️ 两种模式**占同样的高度**（都靠 `CompactIconButton` 的
             * 36dp 固定高度）：否则切换排版会让整行卡片高度跳动，
             * 看着像列表在闪。
             *
             * 这里不画缩略预览：卡片太窄，按键文字会挤错位 ——
             * 预览只在编辑页里看（那里的尺寸足够）。
             */
            if (compactActions) {
                /*
                 * 紧凑模式：占满整行、内容靠右 —— 于是按钮落在**右下角**，
                 * 与平铺模式那把图标的右端对齐。
                 */
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    ConfigActionsMenu(
                        builtIn = config.builtIn,
                        onExport = onExport,
                        onDuplicate = onDuplicate,
                        onDelete = onDelete,
                    )
                }
            } else {
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
}

/**
 * 配置卡片**右下角**的「更多」菜单（竖排三点）。
 *
 * ============================================================
 * 为什么用 `DropdownMenu` 而不是自己画一个
 * ============================================================
 * 它是 Material 的标准做法：位置自动贴着锚点、点外面自动关、
 * 有系统一致的进入动画与阴影。自己画的话这三点都得手动处理，
 * 而"看起来像原生但行为不一致"比"朴素一点"更糟。
 *
 * ⚠️ 菜单项**带图标 + 文字**，而不是纯文字：纯文字的菜单
 * 在多语言/长词下会变得很高，而图标能让用户扫一眼就找到目标。
 *
 * ⚠️ 内置配置（Default）的「删除」要禁用而不是隐藏：
 * 隐藏会让用户以为"这个菜单里没有删除"，禁用则明确告诉他
 * "有，但这一项不能删"（卡片平铺模式下也是同样的规矩）。
 */
@Composable
private fun ConfigActionsMenu(
    builtIn: Boolean,
    onExport: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        CompactIconButton(
            icon = Icons.Default.MoreVert,
            description = "更多操作",
            onClick = { expanded = true },
            /*
             * ⚠️ 必须**给定宽度并关掉 `fillWidth`** ——
             * 这个按钮是单独一个放在右下角的，撑满整行的话
             * 看起来是一条可点的长条，而不是一个"更多"按钮。
             *
             * 48dp 是 Material 的最小触摸目标尺寸，够大也够收敛。
             */
            modifier = Modifier.width(48.dp),
            fillWidth = false,
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("导出") },
                leadingIcon = { Icon(Icons.Default.SaveAlt, contentDescription = null) },
                onClick = {
                    expanded = false
                    onExport()
                },
            )
            DropdownMenuItem(
                text = { Text("复制") },
                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                onClick = {
                    expanded = false
                    onDuplicate()
                },
            )
            DropdownMenuItem(
                text = { Text("删除") },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                enabled = !builtIn,
                onClick = {
                    expanded = false
                    onDelete()
                },
            )
        }
    }
}

/**
 * 紧凑图标按钮。
 *
 * 宽度交给调用方（通常传 `weight(1f)`），这里只管高度与点击区：
 * 高度固定 36dp，既满足"可点击区域不小于 36dp"的可用性要求，
 * 又不会像默认 IconButton 的 48dp 那样把半屏卡片撑爆。
 *
 * ⚠️ 默认**占满可用宽度**（`fillMaxWidth`）——平铺模式下由 `weight`
 * 决定实际宽度，所以这个默认值不影响它。
 * 但"只放一个按钮"的场景（右下角的「更多」）要显式传窄宽度，
 * 否则它会撑满整行、看着像一个长条热区而不是一个按钮。
 */
@Composable
private fun CompactIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    fillWidth: Boolean = true,
) {
    Box(
        modifier = modifier
            .height(36.dp)
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
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

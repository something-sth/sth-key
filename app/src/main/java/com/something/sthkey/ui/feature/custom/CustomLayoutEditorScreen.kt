package com.something.sthkey.ui.feature.custom

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.something.sthkey.core.log.AppLog
import com.something.sthkey.domain.config.KeyStrokesConfig
import com.something.sthkey.domain.custom.CustomComponent
import com.something.sthkey.domain.custom.CustomLayout
import com.something.sthkey.domain.custom.CustomLayoutDraft
import com.something.sthkey.domain.custom.CustomLayoutSettings
import com.something.sthkey.domain.custom.KeyComponent
import com.something.sthkey.domain.custom.TextComponent
import com.something.sthkey.domain.custom.createComponent
import com.something.sthkey.domain.custom.duplicateComponent
import com.something.sthkey.domain.custom.summary
import com.something.sthkey.domain.custom.typeLabel
import com.something.sthkey.domain.custom.withCpsKeyCodesAt
import com.something.sthkey.domain.custom.withInputKeyCodes
import com.something.sthkey.domain.custom.withStyle
import com.something.sthkey.domain.font.FontRegistry
import com.something.sthkey.ui.EditorPanelSide
import com.something.sthkey.ui.component.FontPickerDialog
import com.something.sthkey.ui.component.MultiKeyPickerDialog
import com.something.sthkey.ui.overlay.CustomKeyCanvas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 选中框的描边宽度（dp） */
private const val SELECTION_STROKE_DP = 2f

/** 侧栏模式下面板的宽度（dp）—— 刚好放得下"标签 + 数值 + 滑块"一行 */
private val SIDE_PANEL_WIDTH = 330.dp

/** 平板的最短边阈值（dp）：Android 官方对 `sw600dp` 的定义 */
private const val TABLET_MIN_WIDTH_DP = 600

/**
 * 自定义 Key 的编辑器（二级页面）。
 *
 * ============================================================
 * ⚠️ 这里**没有画布手势**，这是刻意的
 * ============================================================
 * 第一版让用户在画布上拖动组件。那套东西引入了 `awaitEachGesture` 手势、
 * 工作区缩放、命中坐标换算、`pointerInput` key 与内容变化的相互影响 ——
 * 复杂度和它带来的收益完全不成比例，而且出问题时（看起来"整个界面都是死的"）
 * 极难定位到底是手势、坐标还是状态传播。
 *
 * 现在改成：**画布只负责"看"，属性面板负责"改"**，定位用 X / Y 滑块。
 * 于是这个页面**一行触摸处理都不需要**，所有编辑都走同一类控件（滑块），
 * 坏就一起坏、好就一起好，排查面小得多。
 *
 * 代价是失去了"直接拖"的直觉，换来的是**可靠**。这在编辑一个悬浮窗布局时是划算的。
 *
 * ============================================================
 * 画布是固定的 600×600
 * ============================================================
 * 见 [CustomLayout.canvasWidth] 的说明：固定尺寸让"滑块取值范围"有确定的上界，
 * 也不会出现"挪一个组件、整个窗口跟着长大"。画布在屏幕上按可用空间等比缩放显示，
 * 所以 600×600 在任何设备上都看得全。
 *
 * ============================================================
 * 属性面板可以换边（下 / 左 / 右 / 自动）
 * ============================================================
 * 属性面板是按"手机竖屏、面板在下方"设计的，到了平板上就不合适了 ——
 * 横屏上下空间很矮、左右大片空白。位置交给用户选，见 [EditorPanelSide]。
 *
 * **组件条固定在顶部**，不跟着面板走：它是"选哪个组件"的入口，
 * 而面板是"改这个组件"的地方。把它钉在画布上方，切换面板位置时
 * 选组件的动线完全不变，侧栏里也能专心放属性。
 */
@Composable
fun CustomLayoutEditorScreen(
    config: KeyStrokesConfig,
    onSave: (List<CustomComponent>) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    panelSide: EditorPanelSide = EditorPanelSide.DEFAULT,
    /** 当前位置是不是由"自动"定的；只影响顶栏那个按钮要不要显示 `-自动` 后缀 */
    autoChosen: Boolean = false,
    /**
     * 画布上是否画出**悬浮窗范围**（内容包围盒）。
     *
     * 由设置页控制（默认开）：它是"摆出来的东西在屏幕上占多大"的唯一提示，
     * 而"组件贴到这条线之外会被裁"这个坑恰恰要看得见边界才能避开。
     */
    showWindowFrame: Boolean = true,
    /** 画布上是否画出**当前选中组件**的边框 */
    showSelectedFrame: Boolean = true,
    /** 「更多」里切换这两个全局选项 */
    onShowWindowFrameChange: (Boolean) -> Unit = {},
    onShowSelectedFrameChange: (Boolean) -> Unit = {},
    onPanelSideChange: (EditorPanelSide) -> Unit = {},
) {
    /*
     * 草稿只在进入这个页面时创建一次。
     *
     * key 用 config.id：换了配置就重新取一份草稿，绝不能把 A 的编辑结果
     * 写到 B 上（那是"改一个另一个也变了"的翻版）。
     */
    val draft = remember(config.id) { CustomLayoutDraft(config.custom) }

    var selectedId by remember {
        mutableStateOf<String?>(draft.current.components.firstOrNull()?.id)
    }
    var editingKeysFor by remember { mutableStateOf<KeyComponent?>(null) }
    var editingCpsKeysFor by remember { mutableStateOf<Pair<TextComponent, Int>?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var showFontPicker by remember { mutableStateOf(false) }
    /** 「更多」菜单：画布边框那类"编辑器相关但全局生效"的选项放这里 */
    var showMoreMenu by remember { mutableStateOf(false) }
    /**
     * 字体列表的刷新令牌。
     *
     * 导入字体发生在**字体对话框外面**（SAF 选择器要 Activity），
     * 对话框自己感知不到，所以导入完成后把这个值 +1，让它重读列表 ——
     * 否则会出现"导入成功了但列表里没有，得关掉重开才看得到"。
     */
    var fontRevision by remember { mutableStateOf(0) }

    /**
     * 用户是**为了导入字体**才关掉对话框的；导入流程结束后要把对话框打开。
     *
     * ⚠️ 用一个显式的意图标记，而不是"在回调里直接 setState"：
     * 选择器返回时这个 Composable 可能刚经历过一轮重组，
     * 显式标记让"该不该重开"成为一个**可读的状态**，而不是隐式的时序假设。
     *
     * 三种情况都要把它清掉（见 [LaunchedEffect]）：选了文件、没选文件、
     * 选择器根本没起来 —— 漏掉任何一种都会让对话框莫名其妙地自己弹出来。
     */
    var reopenFontPicker by remember { mutableStateOf(false) }

    /** 导入 / 导出这类磁盘操作与 Toast 都要用 */
    val context = LocalContext.current

    /** 字体导入是磁盘操作，要放协程里 */
    val scope = rememberCoroutineScope()

    /**
     * 正在等用户确认删除的组件。
     *
     * 存**整个组件**而不是 id：弹窗上要显示"删的是哪一个"（类型 + 文字），
     * 而只存 id 的话就得在弹窗里再去列表里查一次 —— 多一次查找、
     * 多一种"查不到"的分支要处理。
     */
    var pendingDelete by remember { mutableStateOf<CustomComponent?>(null) }

    /*
     * `draft.current` 是 Compose 状态：读它就订阅了重组，
     * 所以这里**不需要**任何手动刷新。见 CustomLayoutDraft 的说明。
     */
    val settings = draft.current
    val selected = settings.components.firstOrNull { it.id == selectedId }

    /*
     * "自动"在这一刻解析成具体的一边。
     *
     * 用 `LocalConfiguration.smallestScreenWidthDp` 而不是当前宽高：
     * 它描述的是**设备形态**（最短边），转屏时不变 ——
     * 所以"这是不是平板"不会因为横竖屏切换而变来变去。
     */
    val isTablet = LocalConfiguration.current.smallestScreenWidthDp >= TABLET_MIN_WIDTH_DP
    val side = panelSide.resolved(isTablet)
    val sidePanel = side == EditorPanelSide.LEFT || side == EditorPanelSide.RIGHT

    /**
     * ⚠️ 给 launcher 回调读的**实时值**，不是快照。
     *
     * `rememberLauncherForActivityResult` 会把回调 lambda 记住（它内部只在
     * 注册时用一次）。而 lambda 捕获的是**注册那一刻**的变量快照 ——
     * 于是回调里读到的 `reopenFontPicker` 可能永远是初始的 `false`，
     * 结果就是"导入完成了但对话框不回来"，而且看起来毫无道理。
     *
     * `rememberUpdatedState` 给的是一个"永远是当前值"的容器，
     * 回调通过它读，就不会读到过期快照。`selected` 同理 ——
     * 用户可能在选择器开着的时候切了组件。
     */
    val reopenFontPickerState = rememberUpdatedState(reopenFontPicker)
    val selectedForImport = rememberUpdatedState(selected)

    /**
     * 字体导入的文件选择器。
     *
     * ⚠️ 这**整个 launcher 之前是缺的**：字体对话框的 `onImport` 写的是
     * `{ showFontPicker = false }` —— 只把对话框关掉，根本没拉起文件选择器，
     * 所以自定义编辑页里"导入字体文件"那个按钮点了跟没点一样。
     *
     * Key 样式的编辑页有完整的一套（launcher + 导入 + 刷新令牌），
     * 自定义编辑器是后来单独写的，漏了这一段。这里补上，做法与那边一致。
     *
     * 用 SAF（系统文件选择器）而不是申请存储权限：
     * 用户选哪个文件就只有那个文件的读取权，不用暴露整个存储。
     */
    val fontPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            // 用户在系统界面里取消了：把字体对话框还给他，别让他以为功能坏了
            if (reopenFontPickerState.value) {
                reopenFontPicker = false
                showFontPicker = true
            }
            return@rememberLauncherForActivityResult
        }

        scope.launch {
            val imported = withContext(Dispatchers.IO) { FontRegistry.import(uri) }
            if (imported == null) {
                Toast.makeText(
                    context,
                    "导入失败：请确认文件是 .ttf / .otf / .ttc",
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                /*
                 * 导入后**直接给选中的组件换上**，省掉"导入完还得再点一次"。
                 *
                 * 只改当前选中组件自己的 style.fontId（不是整份配置）——
                 * 与属性面板里选字体走同一条路，于是"改一个组件的外观"
                 * 这件事只有一个入口。
                 */
                selectedForImport.value?.let { target ->
                    draft.replace(target.withStyle(target.style.copy(fontId = imported.id)))
                }
                Toast.makeText(context, "已导入「${imported.displayName}」", Toast.LENGTH_SHORT)
                    .show()
            }

            /*
             * 让字体对话框重读列表（它感知不到外部导入），
             * 然后按用户当初的意图把对话框还给他 —— 两种结果都要还：
             * 成功了他会看到列表里多了一项，失败了他能就地重试。
             */
            fontRevision++
            if (reopenFontPickerState.value) {
                reopenFontPicker = false
                showFontPicker = true
            }
        }
    }

    val panelContent: @Composable (Modifier) -> Unit = { panelModifier ->
        PropertyPanel(
            component = selected,
            components = settings.components,
            modifier = panelModifier,
            onStyleChange = { style, asStep ->
                selected?.let { target -> draft.replace(target.withStyle(style), asStep) }
            },
            onComponentChange = { updated, asStep -> draft.replace(updated, asStep) },
            onBeginContinuous = { draft.mutateOnce() },
            onEndContinuous = { draft.finishStep() },
            onPickFont = { showFontPicker = true },
            onPickKeys = { key -> editingKeysFor = key },
            onPickCpsKeys = { text, index -> editingCpsKeysFor = text to index },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            /*
             * 键盘弹起时整页上移，否则改文本时输入框会被盖住。
             * 这也是"面板不能放在上方"的原因之一（见 EditorPanelSide 的说明）。
             */
            .imePadding(),
    ) {
        EditorTopBar(
            configName = config.name,
            dirty = draft.dirty,
            canUndo = draft.canUndo,
            canRedo = draft.canRedo,
            side = panelSide,
            autoChosen = autoChosen,
            onBack = { if (draft.dirty) showDiscardDialog = true else onBack() },
            onUndo = { draft.undo() },
            onRedo = { draft.redo() },
            onSideChange = onPanelSideChange,
            onSave = {
                draft.markSaved()
                onSave(draft.current.components)
            },
            onMore = { showMoreMenu = true },
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        /*
         * 组件条：**固定在上方**（见函数说明）。
         * 它是"选哪个组件"的入口，与属性面板的位置无关。
         */
        ComponentStrip(
            components = settings.components,
            selectedId = selectedId,
            onSelect = { id -> selectedId = id },
            onAdd = { showAddDialog = true },
            /* 复制：新组件立刻成为选中项，用户可以直接接着调它 */
            onDuplicate = {
                selected?.let { source ->
                    val copy = duplicateComponent(source, settings)
                    draft.add(copy)
                    selectedId = copy.id
                }
            },
            /*
             * 删除走确认弹窗，与「恢复默认布局」同一个待遇。
             *
             * 理由和那个一样：它是**一步就能毁掉一段调试成果**的操作，
             * 而旁边的编辑都是可逆的（能撤回，但用户不一定会想到撤回）。
             */
            onDelete = { selected?.let { target -> pendingDelete = target } },
            onReset = { showResetDialog = true },
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (sidePanel) {
            Row(modifier = Modifier.weight(1f)) {
                if (side == EditorPanelSide.LEFT) {
                    SidePanelSlot(side = side, content = panelContent)
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    CanvasPreview(
                        settings = settings,
                        selectedId = selectedId,
                        modifier = Modifier.weight(1f),
                        showWindowFrame = showWindowFrame,
                        showSelectedFrame = showSelectedFrame,
                    )
                } else {
                    CanvasPreview(
                        settings = settings,
                        selectedId = selectedId,
                        modifier = Modifier.weight(1f),
                        showWindowFrame = showWindowFrame,
                        showSelectedFrame = showSelectedFrame,
                    )
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SidePanelSlot(side = side, content = panelContent)
                }
            }
        } else {
            CanvasPreview(
                settings = settings,
                selectedId = selectedId,
                showWindowFrame = showWindowFrame,
                showSelectedFrame = showSelectedFrame,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            panelContent(
                Modifier
                    .fillMaxWidth()
                    .weight(1.4f)
                    .navigationBarsPadding(),
            )
        }
    }

    /*
     * ============================================================
     * 对话框
     * ============================================================
     */

    if (showAddDialog) {
        AddComponentDialog(
            onDismiss = { showAddDialog = false },
            onPick = { type ->
                val created = createComponent(type, settings, config)
                draft.add(created)
                selectedId = created.id
                showAddDialog = false
            },
        )
    }

    editingKeysFor?.let { key ->
        MultiKeyPickerDialog(
            title = "选择「${key.label.ifBlank { "按键" }}」监听的键",
            hint = "可以绑定多个物理键，任意一个按下都会点亮这个组件。" +
                "例如左右 Shift 是两个不同的键码，两个都选上才会都亮。",
            initialCodes = key.inputKeyCodes,
            emptyHint = "当前未绑定任何按键（这个组件不会亮）",
            onDismiss = { editingKeysFor = null },
            onConfirm = { codes ->
                draft.replace(key.withInputKeyCodes(codes))
                editingKeysFor = null
            },
        )
    }

    /*
     * CPS 统计的键：**只有文本组件会走到这里**。
     *
     * 按键组件的 CPS 来源是它自己监听的键（键位映射已经把它映射好了），
     * 所以它不需要这个对话框 —— 需求里点名的就是这个区别。
     */
    /*
     * CPS 键位对话框。
     *
     * ⚠️ 它必须带**第几个占位符**：一个组件可以有多个 `(cps)`，
     * 每个各有一组键位。只存组件本身的话，点第 3 条也会改到第 1 条，
     * 前面配好的就全丢了。
     */
    editingCpsKeysFor?.let { (text, placeholderIndex) ->
        val placeholders = CustomLayout.cpsPlaceholders(text.text)
        val placeholder = placeholders.getOrNull(placeholderIndex)

        MultiKeyPickerDialog(
            title = "第 ${placeholderIndex + 1} 个 CPS 占位符统计的键",
            hint = buildString {
                append("可以选多个键，它们的点击次数会**相加**。")
                append("注意同一个键位映射下的键共享计数，不会重复计算。")
                if (placeholder != null) {
                    append("\n\n对应文字里的第 ${placeholderIndex + 1} 处「")
                    append(if (placeholder.isMode1) "(cps2)" else "(cps)")
                    append("」—— 已用颜色标出。")
                }
            },
            initialCodes = CustomLayout.cpsKeyCodesAt(text, placeholderIndex),
            emptyHint = "当前未选择任何按键（这一处 CPS 会一直是 0）",
            onDismiss = { editingCpsKeysFor = null },
            onConfirm = { codes ->
                draft.replace(text.withCpsKeyCodesAt(placeholderIndex, codes))
                editingCpsKeysFor = null
            },
        )
    }

    /*
     * 字体对话框。
     *
     * ============================================================
     * ⚠️ 拉起 SAF 选择器**必须包 try/catch**（这里踩过坑）
     * ============================================================
     * 现象：点「导入字体文件」→ **字体窗口直接没了**，文件选择器也没起来，
     * 看起来就像"这个按钮只是把窗口关掉"。
     *
     * 根因是 `fontPicker.launch(...)` **抛了异常**：
     * 设备/ROM 上没有能处理 `ACTION_OPEN_DOCUMENT` 的应用时，
     * `ActivityResultLauncher.launch` 会抛 `ActivityNotFoundException`。
     * 而这个异常是从**按钮的 onClick 里**抛出去的，被 Compose 吞掉 ——
     * 于是既不弹选择器、也不报错，只剩"窗口关掉了"这一个可见现象，
     * 完全看不出真正的原因。
     *
     * 所以这里必须自己捕获并**把原因显示出来**（见下面 onImport）。
     */
    if (showFontPicker) {
        FontPickerDialog(
            selectedId = selected?.style?.fontId.orEmpty(),
            onSelect = { fontId ->
                selected?.let { target ->
                    draft.replace(target.withStyle(target.style.copy(fontId = fontId)))
                }
            },
            onDismiss = { showFontPicker = false },
            /*
             * ⚠️ 这里**不能关掉对话框**：字体选择器是"边导入边选"的用法 ——
             * 它自己带一个「完成」按钮，导入后列表会立刻多出那一项，
             * 用户可以接着点它。关掉的话用户得重新打开一次才能选。
             */
            onImport = {
                /*
                 * ⚠️ 先主动关掉字体对话框，再拉选择器。
                 *
                 * ============================================================
                 * 为什么不能"让对话框开着、选择器盖在上面"
                 * ============================================================
                 * 现象（用户实测）：点「导入字体文件」→ **字体窗口直接没了**，
                 * 选择器也没起来，看起来这个按钮就只会关窗口。
                 *
                 * `AlertDialog` 是**独立窗口**。SAF 选择器一起来，宿主 Activity
                 * 被暂停、对话框窗口失去焦点，系统就会给它一个 dismiss ——
                 * 而 `onDismissRequest` 把它映射成了 `showFontPicker = false`。
                 * 于是对话框的存亡取决于"窗口焦点"，完全不可控。
                 *
                 * 现在不依赖那个窗口活着：**由我们自己关掉它**（行为确定），
                 * 选择器拉起来，导入完成后（见 [reopenFontPicker]）再打开它。
                 */
                showFontPicker = false
                reopenFontPicker = true

                /*
                 * 必须捕获：没有文件选择器时 launch 会抛 ActivityNotFoundException，
                 * 而它从 onClick 里抛出去会被 Compose 吞掉 ——
                 * 用户看到的就是"窗口关掉了、什么都没发生"，完全不知道原因。
                 */
                try {
                    fontPicker.launch(
                        arrayOf(
                            "font/ttf",
                            "font/otf",
                            "font/ttc",
                            "application/x-font-ttf",
                            "application/octet-stream",
                        ),
                    )
                } catch (e: Exception) {
                    AppLog.e(
                        "CustomEditor",
                        "拉起字体选择器失败：${e.javaClass.simpleName}: ${e.message}",
                        e,
                    )
                    reopenFontPicker = false
                    showFontPicker = true
                    Toast.makeText(
                        context,
                        "打不开文件选择器（${e.javaClass.simpleName}）：" +
                            "这台设备上没有能选文件的应用",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            },
            refreshKey = fontRevision,
        )
    }

    /*
     * ============================================================
     * 「更多」
     * ============================================================
     * 放"编辑器相关、但**全局生效**"的选项。
     *
     * ⚠️ 它们不是这份配置的专属设置：画布边框这类东西是**看的方式**，
     * 与正在编辑哪份配置无关。所以存在应用级偏好里、由 [MainActivity]
     * 持有（它顺便负责"从别的页面回来时重读"），这里只调回调。
     */
    if (showMoreMenu) {
        AlertDialog(
            onDismissRequest = { showMoreMenu = false },
            title = { Text("编辑器选项") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "以下选项对所有自定义配置生效。\n" +
                            "画布可以单指拖动平移、双指捏合缩放，" +
                            "右下角的百分比点一下即可复位。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    EditorOptionSwitch(
                        title = "悬浮窗尺寸预览",
                        subtitle = "画出内容包围盒，也就是悬浮窗在屏幕上会有多大。" +
                            "组件贴到线外就会被裁，建议保持开启",
                        checked = showWindowFrame,
                        onCheckedChange = onShowWindowFrameChange,
                    )

                    EditorOptionSwitch(
                        title = "选中组件尺寸预览",
                        subtitle = "框出正在编辑的那个组件",
                        checked = showSelectedFrame,
                        onCheckedChange = onShowSelectedFrameChange,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showMoreMenu = false }) { Text("完成") }
            },
        )
    }

    /*
     * 删除单个组件的确认。
     *
     * 与「恢复默认布局」一样要确认：两者都是**一键就毁掉一段成果**。
     * 弹窗里写清删的是哪一个（类型 + 内容摘要），否则用户很容易
     * 在选中项不是自己以为的那个时点下去 —— 而上一个组件的内容就没了。
     */
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这个${target.typeLabel()}组件？") },
            text = {
                Text(
                    "「${target.summary()}」会从画布上移除。\n\n" +
                        "这一步可以撤回。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        draft.remove(target.id)
                        /*
                         * 删完之后把选中项挪到剩下的第一个。
                         *
                         * 不挪的话 `selectedId` 会指向一个已经不存在的组件，
                         * 属性面板变成"没有选中组件"—— 而用户刚删完，
                         * 正想接着改下一个，多一步点击是多余的。
                         */
                        selectedId = draft.current.components.firstOrNull()?.id
                        pendingDelete = null
                    },
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("恢复默认布局？") },
            text = {
                Text(
                    "会把画布上的组件全部替换成默认布局" +
                        "（一个 Q 键 + 一个 CPS 文本）。\n\n" +
                        "这一步可以撤回。注意「重置参数」是另一件事：" +
                        "自定义布局不会跟着参数一起重置 —— " +
                        "它是你的作品，不该被顺手抹掉。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        draft.resetToDefault()
                        selectedId = draft.current.components.firstOrNull()?.id
                        showResetDialog = false
                    },
                ) {
                    Text("恢复默认")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("取消") }
            },
        )
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("放弃未保存的改动？") },
            text = { Text("直接返回会丢弃这次编辑的全部改动。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        onBack()
                    },
                ) {
                    Text("放弃")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("继续编辑") }
            },
        )
    }
}

/**
 * 侧栏里的面板容器。
 *
 * 宽度用固定值而不是 `weight`：侧栏是"面板"，它该有多宽由内容决定，
 * 而不是由剩下的空间决定 —— 用 weight 的话手机横屏时面板会窄到滑块没法拖。
 */
@Composable
private fun SidePanelSlot(
    side: EditorPanelSide,
    content: @Composable (Modifier) -> Unit,
) {
    content(
        Modifier
            .width(SIDE_PANEL_WIDTH)
            .fillMaxHeight()
            .navigationBarsPadding()
            /*
             * 贴哪一边就把内边距留在那一边：手势条/挖孔屏在竖屏时
             * 通常在某一条边上，贴边的那一侧要给系统留位置。
             */
            .padding(start = if (side == EditorPanelSide.LEFT) 4.dp else 0.dp),
    )
}

/*
 * ============================================================
 * 顶部功能栏
 * ============================================================
 * 两行：第一行是返回 / 标题 / 撤回恢复 / 保存，第二行是"设置栏位置"。
 */

@Composable
private fun EditorTopBar(
    configName: String,
    dirty: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    side: EditorPanelSide,
    autoChosen: Boolean,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSideChange: (EditorPanelSide) -> Unit,
    onSave: () -> Unit,
    /** 打开「更多」菜单（画布边框那两个开关在里面） */
    onMore: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "自定义编辑",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = configName + if (dirty) " · 有未保存的改动" else " · 已保存",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (dirty) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            IconButton(onClick = onUndo, enabled = canUndo) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "撤回")
            }
            IconButton(onClick = onRedo, enabled = canRedo) {
                Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "恢复")
            }
            /*
             * 「更多」：竖三点。
             *
             * ============================================================
             * 为什么收进菜单而不是直接摆开关
             * ============================================================
             * 顶栏这一行已经有 返回 / 撤回 / 恢复 / 保存 四件东西，
             * 再塞两个 Switch 会挤成一团（而且 Switch 比图标宽得多）。
             *
             * 做成菜单还有个好处：以后要加"编辑器相关"的选项直接往里放，
             * 顶栏永远不会再变宽。
             */
            IconButton(onClick = onMore) {
                Icon(Icons.Default.MoreVert, contentDescription = "更多")
            }
            Button(
                onClick = onSave,
                modifier = Modifier.padding(start = 4.dp),
            ) {
                Text("保存")
            }
        }

        /*
         * 属性面板停在哪一边：**下 / 左 / 右**三个按钮。
         *
         * ============================================================
         * ⚠️ 没有单独的「自动」按钮（这里换过一次做法）
         * ============================================================
         * 早先这里是四个按钮（下/左/右/自动），"自动"自己占一格 ——
         * 但它不是一个**位置**，而是"位置从哪来"，混在一起很别扭：
         * 选了"自动"之后，用户还是不知道面板现在在哪一边。
         *
         * 现在改成：三个位置按钮，**由自动选出来的那个带后缀 `-自动`**
         * （例如 `左-自动`）。这样一眼就能同时看出两件事：
         * 面板现在在左边，而且这是自动定的、不是我选的。
         *
         * 用户点任意一个按钮 = 自己指定，后缀就没了（并且会记住，
         * 见 MainActivity 里 `editorPanelSideChosen` 的说明）。
         */
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "设置栏位置",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            EditorPanelSide.selectable.forEach { option ->
                val isCurrent = side == option
                OutlinedButton(
                    onClick = { onSideChange(option) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier.height(30.dp),
                ) {
                    Text(
                        text = if (isCurrent && autoChosen) {
                            "${option.label}-自动"
                        } else {
                            option.label
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isCurrent) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

/*
 * ============================================================
 * 画布预览（只读）
 * ============================================================
 */

/**
 * 画布预览。
 *
 * ============================================================
 * 只读，不处理任何触摸
 * ============================================================
 * 画布不接收点击：选中组件靠**上方那一条组件条**。
 * 早先还允许"点画布上的组件来选中"，那需要把点击坐标换算回基础坐标，
 * 而画布的缩放倍率是量出来的 —— 换算错一点就会出现"点 A 选中 B"。
 * 组件条是一排成熟的控件，不需要任何坐标换算。
 *
 * 画布固定 600×600，在可用空间里**等比缩放居中**显示，所以任何屏幕都看得全。
 *
 * ============================================================
 * ⚠️ `scale` 是"基础单位 → dp"的倍率，不是像素比值
 * ============================================================
 * 这里踩过一个很显眼的坑：`CustomKeyCanvas` 内部是
 * `.size((600 * scale).dp)`，它的 `scale` 是**dp 倍率**
 * （悬浮窗传的是 `密度系数 × 整体缩放`）。
 * 早先这里传的是 `可用像素宽 / 600` —— 那是个像素比值，
 * 在 2.75 倍密度的机器上等于把组件又放大 2.75 倍，
 * 于是"虚线框很小、键帽很大、位置还对不上"。
 *
 * 现在统一在 **dp** 里算：先定出画布显示边长（dp），再反推出 dp 倍率交给渲染层。
 * 这样编辑器和悬浮窗走的是同一个公式，只剩整体缩放的区别。
 */
@Composable
private fun CanvasPreview(
    settings: CustomLayoutSettings,
    selectedId: String?,
    modifier: Modifier = Modifier,
    showWindowFrame: Boolean = true,
    showSelectedFrame: Boolean = true,
) {
    val canvasWidth = CustomLayout.canvasWidth(settings.components)
    val canvasHeight = CustomLayout.canvasHeight(settings.components)
    val bounds = CustomLayout.bounds(settings.components)

    /*
     * ============================================================
     * 视野：平移 + 缩放
     * ============================================================
     * 需求是"去掉虚线定位区，改成能拖能缩的画布" —— 定位区那层坐标系
     * 本来就是给 X/Y 滑块当刻度用的，而滑块自己已经显示了数值，
     * 那圈虚线更多是干扰。
     *
     * ⚠️ 视野**不持久化**：每次进编辑页都重新对准内容。
     * 记住视野的话，用户上次放大到某个角落、这次进来只看到一片空白，
     * 会以为组件丢了 —— 而"自动对准内容"永远能看到全部组件。
     */
    /*
     * ⚠️ key 必须是**稳定**的东西，不能用 `settings`（这里错过一次）。
     *
     * `settings` 是 `draft.current` 派生的，**每次编辑都会换一个新对象**
     * （改一个字段、加一个组件…）。用它当 key 的后果：
     * 用户一改内容，`zoom/panX/panY` 就被重新初始化 ——
     * 表现是"每次编辑后画布都跳回初始视野"，看起来像画布坏了。
     *
     * 这里用空 key：视野在这个页面的生命周期内保持不变，
     * 离开页面（重组销毁）自然重置，正是需求要的"每次打开对准内容"。
     */
    var zoom by remember { mutableStateOf(DEFAULT_ZOOM) }
    var panX by remember { mutableStateOf(0f) }
    var panY by remember { mutableStateOf(0f) }

    // ⚠️ 不能叫 density：Canvas 的 DrawScope 自带同名属性，会撞上
    val localDensity = LocalDensity.current

    BoxWithConstraints(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            /*
             * ⚠️ 必须裁剪到自己的边界。
             *
             * 不裁剪的话，画布被平移到视野之外时**会画到预览区外面**，
             * 盖住上方的组件条、右边的属性面板 —— 看起来就是"层级错了"。
             * `graphicsLayer` 的平移发生在绘制阶段，父容器不裁剪就拦不住它。
             */
            .clipToBounds()
            /*
             * ⚠️ 手势挂在这一层（整个预览区），**不挂在父级**。
             *
             * 之前废弃的可拖动方案出过"别处的滑块和开关点不了"——
             * 根因就是手势检测铺在了比内容更大的范围上，把点击都吃掉了。
             * 挂在这里时，命中范围严格等于预览区，右边的属性面板
             * 与上方的组件条都在它之外，不可能被影响。
             */
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    // 单指拖动位移、双指捏合缩放，两者可以同时发生
                    val next = (zoom * gestureZoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
                    // 缩放按同比例调整平移，视觉上就是"以手势中心缩放"
                    val ratio = if (zoom <= 0f) 1f else next / zoom
                    /*
                     * ⚠️ 平移要**阻尼**：原样跟手会"一划就飞出画面"。
                     * 画布本身比预览区大得多（600×600 vs 一屏），
                     * 1:1 跟手时手指走一小段内容就移出视野了。
                     */
                    panX = panX * ratio + pan.x * PAN_SENSITIVITY
                    panY = panY * ratio + pan.y * PAN_SENSITIVITY
                    zoom = next
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        /*
         * 初始倍率：让**内容包围盒**刚好装进预览区。
         *
         * 早先是让 600×600 的定位区装进预览区（`coerceIn(..., 1f)`），
         * 于是内容只占定位区的一小块时，画面上就是"内容缩在左上角" ——
         * 这正是需求里说的"偏左上"。现在改成对准内容。
         */
        val fitScale = remember(maxWidth, maxHeight, bounds.width, bounds.height) {
            if (bounds.width <= 0f || bounds.height <= 0f) {
                1f
            } else {
                minOf(
                    maxWidth.value / bounds.width,
                    maxHeight.value / bounds.height,
                ).coerceIn(MIN_ZOOM, MAX_ZOOM)
            }
        }

        // 实际用于渲染的 dp 倍率：基础单位 → dp
        val scaleDp = fitScale * zoom

        /*
         * 内容在预览区里的绘制位置。
         *
         * 画布整体按 `scaleDp` 缩放后，内容包围盒落在
         * `offsetX × scaleDp` 处；再叠上手势平移，并把内容中心
         * 对齐到预览区中心（这样一开始就是居中的）。
         */
        val contentLeftDp = bounds.offsetX * scaleDp
        val contentTopDp = bounds.offsetY * scaleDp
        val offsetXDp = (maxWidth.value - bounds.width * scaleDp) / 2f - contentLeftDp + panX
        val offsetYDp = (maxHeight.value - bounds.height * scaleDp) / 2f - contentTopDp + panY

        Box(
            modifier = Modifier
                .graphicsLayer {
                    translationX = offsetXDp * localDensity.density
                    translationY = offsetYDp * localDensity.density
                }
                .size(
                    (canvasWidth * scaleDp).dp,
                    (canvasHeight * scaleDp).dp,
                )
                .drawBehind {
                    drawCanvasFrame(
                        scale = scaleDp,
                        bounds = bounds,
                        showWindowFrame = showWindowFrame,
                    )
                },
        ) {
            /*
             * 组件：与悬浮窗**同一段渲染**。
             *
             * ⚠️ 编辑器**不能**让画布自适应容器（`fitToContainer` 保持默认的
             * false）：这里画的是固定 600×600 的定位区，内容尺寸必须与
             * 滑块刻度严格对应 —— 跟着容器伸缩的话，拖动组件时
             * "看到的位移"和"坐标变化"就对不上了。
             */
            CustomKeyCanvas(
                settings = settings,
                pressedCodes = emptySet(),
                scale = scaleDp,
                baseWidth = canvasWidth,
                baseHeight = canvasHeight,
            )

            if (showSelectedFrame) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val selected = settings.components.firstOrNull { it.id == selectedId }
                    if (selected != null) {
                        drawRect(
                            color = Color(0xFF3380FF),
                            topLeft = Offset(
                                selected.x * scaleDp * localDensity.density,
                                selected.y * scaleDp * localDensity.density,
                            ),
                            size = Size(
                                selected.width * scaleDp * localDensity.density,
                                selected.height * scaleDp * localDensity.density,
                            ),
                            style = Stroke(width = SELECTION_STROKE_DP * localDensity.density),
                        )
                    }
                }
            }
        }

        /*
         * 缩放百分比：**固定在预览区右下角**。
         *
         * ⚠️ 必须放在这一层（`BoxWithConstraints` 的直接子级），
         * **不能**放进上面那个会被平移的 Box 里 —— 放进去它就会
         * 跟着画布一起动，"数字跟着手跑"，看起来像坏了。
         *
         * 需求是"角落显示百分比"而不是放一个滑块 —— 它只是个读数，
         * 调节靠双指捏合，所以做成一小块半透明标签，不占操作面积。
         */
        ZoomBadge(
            zoom = zoom,
            onReset = {
                zoom = DEFAULT_ZOOM
                panX = 0f
                panY = 0f
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp),
        )
    }
}

/**
 * 「更多」里的一行开关。
 *
 * 与项目里 [SwitchItem] 的区别：这个是给**对话框内部**用的，
 * 不带卡片分割线、左边距也更小（弹窗本身已经很窄）。
 */
@Composable
private fun EditorOptionSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            /*
             * 整行可点：只点那个 Switch 太小，而这是弹窗里唯一要点的东西。
             * 与设置页里那些 SettingItem 的做法一致。
             */
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 进入编辑页时的初始缩放（相对"内容刚好装下"那个倍率）。
 *
 * 取 0.5 而不是 1：装满预览区时组件显得很大、看得到的关系少，
 * 而编辑时更需要"一眼看清整块布局"。
 */
private const val DEFAULT_ZOOM = 0.5f

/**
 * 平移阻尼。
 *
 * 画布（600×600 基础单位）比预览区大得多，1:1 跟手时手指走一小段
 * 内容就飞出视野了 —— 表现是"灵敏度太高、一划就没"。
 * 取 0.5：手指走两格，内容走一格。
 */
private const val PAN_SENSITIVITY = 0.5f

/** 缩放倍率的可调范围 */
private const val MIN_ZOOM = 0.25f
private const val MAX_ZOOM = 6f

/**
 * 角落的缩放读数。
 *
 * 点一下复位（回到"对准内容"的初始视野）——
 * 拖乱之后想回到原样是常见需求，而为它单独放个按钮不值当。
 */
@Composable
private fun ZoomBadge(
    zoom: Float,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onReset,
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
    ) {
        Text(
            text = "${(zoom * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/**
 * 画布底板：淡网格 + **悬浮窗范围**（内容包围盒）。
 *
 * ============================================================
 * ⚠️ 定位区那圈虚线已经去掉了
 * ============================================================
 * 它原来标的是"X/Y 滑块能取到的范围"（固定 600×600）。但滑块自己
 * 就把数值写在那儿了，这圈虚线除了占视觉、让人以为"画布就这么大"
 * 之外没有别的作用；而且内容缩在定位区一角时，画面看起来就是
 * "内容偏左上"。
 *
 * 现在改成：视野**对准内容**（见 [CanvasPreview]），虚线不再需要。
 * 定位区依然是 600×600、滑块范围也没变 —— 变的只是"画不画那圈线"。
 *
 * `DrawScope` 里的长度都是**像素**，而 [scale] 是 dp 倍率，
 * 所以要乘 `density` 才能把基础单位换算成像素。
 * 网格间距随缩放走，并吸附到 20 的整数倍 —— 否则小倍率下网格会糊成一片。
 *
 * @param bounds 内容包围盒 = 窗口范围；`visible = false` 时说明没有组件，
 *   这时不画框（画一个 1×1 的框只会让人困惑），由界面上的文字提示代替。
 * @param showWindowFrame 是否画出窗口范围（可在设置里关掉）
 */
private fun DrawScope.drawCanvasFrame(
    scale: Float,
    bounds: CustomLayout.Bounds,
    showWindowFrame: Boolean,
) {
    val pxPerBase = scale * density
    val baseStep = 40f
    val step = (baseStep * pxPerBase).coerceAtLeast(8f).let { raw ->
        val unit = 20f * pxPerBase
        if (unit <= 0f) raw else (raw / unit).toInt().coerceAtLeast(1) * unit
    }

    // 网格
    val gridColor = Color(0x1A3380FF)
    var x = step
    while (x < size.width - 0.5f) {
        drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        x += step
    }
    var y = step
    while (y < size.height - 0.5f) {
        drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        y += step
    }

    /*
     * 窗口范围：**真正的悬浮窗大小**（内容包围盒）。实线 + 主题色。
     *
     * ⚠️ 这条线是"摆出来的东西在屏幕上占多大"的**唯一**提示，
     * 而且它标出的边界正是"组件贴到边界外会被裁"的那条线 ——
     * 所以默认开着，只在用户主动关掉时才不画。
     */
    if (showWindowFrame && bounds.visible) {
        drawRect(
            color = Color(0xCC3380FF),
            topLeft = Offset(bounds.offsetX * pxPerBase, bounds.offsetY * pxPerBase),
            size = Size(bounds.width * pxPerBase, bounds.height * pxPerBase),
            style = Stroke(width = 1.5f * density),
        )
    }
}

/*
 * ============================================================
 * 组件条
 * ============================================================
 * 固定在上方，横向排布：一排胶囊 + 三个操作按钮。
 */

@Composable
private fun ComponentStrip(
    components: List<CustomComponent>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            components.forEach { component ->
                ComponentPill(
                    component = component,
                    selected = component.id == selectedId,
                    onClick = { onSelect(component.id) },
                )
            }

            if (components.isEmpty()) {
                Text(
                    text = "还没有组件，点右边的 + 添加",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }

        /*
         * 四个按钮的分工（顺序也是按这个排的）：
         *
         *   加号   造一个新的        —— 创建
         *   复制   照抄选中的这个    —— 创建（差别是内容从哪来）
         *   删除   去掉选中的这个    —— 销毁
         *   重置   回到默认布局      —— 销毁（范围更大）
         *
         * 创建两个挨着、销毁两个挨着：手指记住的是"右边两个是造东西的、
         * 再往右两个是删东西的"，比随便排要少点错。
         */
        IconButton(onClick = onAdd) {
            Icon(Icons.Default.Add, contentDescription = "添加组件")
        }
        IconButton(onClick = onDuplicate, enabled = selectedId != null) {
            Icon(Icons.Default.ContentCopy, contentDescription = "复制选中组件")
        }
        IconButton(onClick = onDelete, enabled = selectedId != null) {
            Icon(Icons.Default.Delete, contentDescription = "删除选中组件")
        }
        IconButton(onClick = onReset) {
            Icon(Icons.Default.RestartAlt, contentDescription = "恢复默认布局")
        }
    }
}

/** 组件条上的一个胶囊：选中时高亮加粗 */
@Composable
private fun ComponentPill(
    component: CustomComponent,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(34.dp)
            .background(
                color = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = component.typeLabel() + "·" + component.summary(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

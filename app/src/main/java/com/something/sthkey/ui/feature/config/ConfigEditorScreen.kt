package com.something.sthkey.ui.feature.config

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.filled.DashboardCustomize
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BorderStyle
import androidx.compose.material.icons.rounded.FontDownload
import androidx.compose.material.icons.rounded.FormatColorText
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.RoundedCorner
import androidx.compose.material.icons.rounded.SpaceBar
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Transform
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.something.sthkey.capture.accessibility.AccessibilityGrant
import com.something.sthkey.capture.accessibility.SthKeyAccessibilityService
import com.something.sthkey.capture.touch.TouchRegionEditorService
import com.something.sthkey.core.log.AppLog
import com.something.sthkey.data.permission.PermissionHelper
import com.something.sthkey.domain.config.ANIMATION_DURATION_MAX
import com.something.sthkey.domain.config.ANIMATION_DURATION_MIN
import com.something.sthkey.domain.config.AnimationMode
import com.something.sthkey.domain.config.DEFAULT_CPS_TEMPLATE
import com.something.sthkey.domain.config.DEFAULT_CPS_TEMPLATE_MODE1
import com.something.sthkey.domain.config.KeyStrokesConfig
import com.something.sthkey.domain.config.resetParamsToDefault
import com.something.sthkey.domain.config.ShadowMode
import com.something.sthkey.domain.config.TextShadow
import com.something.sthkey.domain.custom.canConvertToCustom
import com.something.sthkey.domain.custom.CustomTouchRegion
import com.something.sthkey.domain.custom.KeyComponent
import com.something.sthkey.domain.custom.TextComponent
import com.something.sthkey.domain.custom.toCustomKeyStyle
import com.something.sthkey.domain.font.bitmap.BitmapFontImporter
import com.something.sthkey.domain.font.FontRegistry
import com.something.sthkey.domain.live2d.Live2DModels
import com.something.sthkey.domain.style.KeyLayout
import com.something.sthkey.domain.style.StyleId
import com.something.sthkey.ui.component.BitmapFontPickerDialog
import com.something.sthkey.ui.component.CardDivider
import com.something.sthkey.ui.component.collapsibleSection
import com.something.sthkey.ui.component.EditableSliderRow
import com.something.sthkey.ui.component.FontPickerDialog
import com.something.sthkey.ui.component.HexColorRow
import com.something.sthkey.ui.component.KeyMappingEditor
import com.something.sthkey.ui.component.Live2DModelPickerDialog
import com.something.sthkey.ui.component.PendingBitmapImportDialog
import com.something.sthkey.ui.component.ScrollableScreen
import com.something.sthkey.ui.component.SectionHeader
import com.something.sthkey.ui.component.SectionHint
import com.something.sthkey.ui.component.setExpanded
import com.something.sthkey.ui.component.SettingItem
import com.something.sthkey.ui.component.SettingsCard
import com.something.sthkey.ui.component.SlotTextOffsetDialog
import com.something.sthkey.ui.component.SlotTextSpacingDialog
import com.something.sthkey.ui.component.SwitchItem
import com.something.sthkey.ui.EditMode
import com.something.sthkey.ui.feature.custom.stepOf
import com.something.sthkey.ui.MainViewModel
import com.something.sthkey.ui.preview.ConfigPreview
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext



/**
 * 预览框的固定尺寸（dp）。
 *
 * 比例按基础坐标系的 300 × 420 来（宽高比 0.71），
 * 这样预览里的按键比例与悬浮窗一致，不会看起来"被压扁"。
 */
private const val PREVIEW_WIDTH_DP = 124f
private const val PREVIEW_HEIGHT_DP = 174f

/**
 * 配置编辑页（整页编辑）。
 *
 * ============================================================
 * 两种生效方式（在设置页切换）
 * ============================================================ * - [EditMode.REALTIME] 每次改动立即写入当前配置，退出即完成；
 * - [EditMode.ON_SAVE]  改动只写进本地 [draft]，点底部"保存"才落库，
 *   直接返回等于放弃本次修改（旧项目的行为）。
 *
 * 实现上只有一个分支点：`editable` 取"实时配置"还是"草稿"，
 * 以及 `onChange` 是写库还是改草稿。其它 UI 代码两者完全共用。
 */
@Composable
fun ConfigEditorScreen(
    viewModel: MainViewModel,
    configId: String,
    onBack: () -> Unit,
    onOpenCustomEditor: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val editMode = viewModel.editMode
    /*
     * 一般都能按 id 找到；找不到（配置刚被删掉、路由参数失效）时退到第一份配置，
     * 保证这个页面永远有一份可编辑的对象，不会白屏或抛异常。
     */
    val source = viewModel.findConfig(configId) ?: viewModel.firstConfig()
    val context = LocalContext.current

    // 草稿只在"保存生效"模式下有意义；key 用配置 id，切换配置时重新取值
    var draft by remember(configId) { mutableStateOf(source) }

    /*
     * 实时生效模式下正在编辑的那份配置。
     *
     * 为什么不每帧都从仓库里重新取：`allConfigs()` 取到的是一份新列表，
     * 而界面要的是"我正在编辑的这一份"的连续视图 ——
     * 这里把改动结果接住，保证编辑哪份就显示哪份，输入框的光标也不会跳。
     */
    var working by remember(configId) { mutableStateOf(source) }

    val editable = if (editMode == EditMode.ON_SAVE) draft else working

    /**
     * 当前配置是不是 Live2D 样式。
     *
     * 它决定下面显示**哪一整套分区**：两种样式的设置项完全不通用 ——
     * 按键样式那一套（颜色/描边/圆角/动画/CPS/键位映射/字体）对一只猫毫无意义。
     */
    val isLive2D = editable.styleId == StyleId.KEYBOARD_CAT

    /** 是不是自定义 Key 样式；它的编辑页只放通用项，中间交给独立的编辑器页面 */
    val isCustomKey = editable.styleId == StyleId.CUSTOM_KEY

    /**
     * 是不是**触屏**样式。
     *
     * ⚠️ 目前只用来**隐藏「键位映射」栏** —— 触屏不产出键码，
     * 它的输入来源由"触摸区域"回答（见那一栏的注释）。
     *
     * ⚠️ 它不是 `isCustomKey` / `isLive2D` 那种"整页都不一样"的分支:
     * 触屏的编辑页与手柄**几乎相同**（它俩的可见部分就是同一套布局），
     * 所以这里**只在个别分栏上打补丁**，不另开一个 `xxxStyleSections`。
     */
    val isTouching = editable.styleId == StyleId.TOUCHING

    /**
     * 打开**触摸区域编辑悬浮窗**（触屏样式与自定义 Key **共用同一套判断**）。
     *
     * ============================================================
     * ⚠️⚠️ 提成一个 lambda 而不是在两处各写一遍
     * ============================================================
     * 用户的原话:
     *
     * > 在**自定义 key 的编辑配置页**（不是自定义编辑页），加一个"触屏设置"栏，
     * > 这里面加一个 button"触摸区域编辑"，**是的，就跟触屏样式是一样的**
     *
     * ⚠️ "是一样的"指的是**行为**，所以判断逻辑**只有一份**。
     * 两处各写一遍的话，以后改了一处（比如加了"配置还没保存"那种情况）
     * 另一处就是老行为 —— 而那种不一致极难发现（两个入口长得一样）。
     *
     * ⚠️ 顺序要紧（三条都不能省）:
     *
     * | 检查 | 不检查会怎样 |
     * |---|---|
     * | 配置有 id | 悬浮窗不知道该把区域写进哪份配置 |
     * | 有**悬浮窗权限** | `addView` 抛 `BadTokenException`，看着像"编辑器打不开" |
     * | 没有**别的配置**在编辑 | 两套方块同时开着，屏幕上全是框 |
     *
     * ⚠️ 第三条的判断在 [TouchRegionEditorService.refuseReason] 里
     * **只有一份**（"谁的悬浮窗在开着"这个状态只有服务知道），
     * 这里只是把结果翻译成界面动作。
     */
    fun openTouchRegionEditor() {
        if (editable.id.isBlank()) {
            Toast.makeText(context, "请先保存这份配置，再配置触摸区域", Toast.LENGTH_LONG).show()
            return
        }
        /*
         * ⚠️⚠️ 前置条件从"悬浮窗权限"换成了"**无障碍服务**"
         * ============================================================
         * 改用 `TYPE_ACCESSIBILITY_OVERLAY` 之后 `SYSTEM_ALERT_WINDOW` 已经删了，
         * `canDrawOverlays()` 恒为 false —— 那会让这个入口**永远点不动**
         * （与"开启悬浮窗按钮点不动"是同一个 bug，用户已经报过一次）。
         *
         * ⚠️ 现在真正的条件是"无障碍服务连上了吗"，因为编辑器的窗口
         * （那些可拖动的方块）也要靠它建（见 `OverlayWindowHost`）。
         *
         * ⚠️ 没连上时**跳系统设置页**，而不是只弹一句 toast ——
         * 用户明确要求过这种兜底。
         */
        if (!SthKeyAccessibilityService.isConnected()) {
            Toast.makeText(
                context,
                "需要开启无障碍服务才能编辑触摸区域",
                Toast.LENGTH_LONG,
            ).show()
            AccessibilityGrant.openSettings(context)
            return
        }

        when (val reason = TouchRegionEditorService.refuseReason(editable.id)) {
            null -> TouchRegionEditorService.start(context, editable.id)

            /*
             * ⚠️ 同一个配置再按一次 = **不保存关闭**。
             *
             * ⚠️ 用户给过"双击关闭"这个备选，我选了"再按一次" ——
             * 那个按钮是普通按钮，双击是隐藏手势，而单击已经
             * 能表达"收起"（面板里也有「不保存退出」那条明路）。
             */
            TouchRegionEditorService.ALREADY_OPEN -> TouchRegionEditorService.stop(context)

            else -> Toast.makeText(context, reason, Toast.LENGTH_LONG).show()
        }
    }

    /*
     * ============================================================
     * 分区折叠状态
     * ============================================================
     * 用户的原话:"优化一下键盘样式与手柄样式的配置编辑页，让现在的'外观'、
     * '颜色'等标题，变成可折叠的 box，现在配置多，不方便找"。
     *
     * ⚠️ **默认只展开"基本信息"**，其余全部收起 —— 用户的原话:
     * "基本信息不做折叠，这是配置非常重要的一块，折叠起来看预览都要点一下，
     * 而且反直觉。其他的默认都折叠起来，有用户反馈我再调整"。
     *
     * ⚠️ "其它"（重置/删除）也**不折叠** —— 危险操作藏在折叠里不合适。
     *
     * ⚠️ 存的是**分区键**而不是标题:两种样式都有叫"外观"的分区
     * （键分别是 `外观` 与 `live2d:外观`），用标题当键会让它们联动
     * （点一个，另一个也展开）。
     *
     * ⚠️ 状态放在**页面**这一层、不按配置 id 重置:用户连着改几份配置时，
     * "我刚刚展开了颜色"这个意图应该延续下去。
     */
    var expandedSections by remember { mutableStateOf(emptySet<String>()) }

    /*
     * Live2D 模型选择器的状态。
     *
     * ⚠️ 必须声明在下面 [live2DModelName] **之前**：Kotlin 的局部变量
     * 要求"先声明后使用"（这一点和类属性不同 —— 类属性可以写在后面，
     * 但那样又有构造顺序的坑）。
     */
    var showModelPicker by remember { mutableStateOf(false) }

    /** 模型库的版本号：导入/改名/删除后自增，用来刷新"模型"那一行显示的名称 */
    var modelRevision by remember { mutableStateOf(0) }

    /**
     * 当前模型的显示名。
     *
     * ⚠️ 必须在**这里**（composable 函数体）算好再传进 LazyColumn，
     * 不能写在 LazyColumn 的内容块里：那个块是 `LazyListScope` 作用域，
     * **不是 @Composable**，里面调 `remember` 会直接编译不过。
     *
     * `modelRevision` 是 key：在模型库里改了名之后自增，这一行才会刷新。
     */
    val live2DModelName = remember(modelRevision, editable.live2d.modelId) {
        Live2DModels.displayNameOf(editable.live2d.modelId)
    }

    /**
     * 统一的修改入口：按模式决定写草稿还是直接落库。
     *
     * 注意用**[按 id 修改]**而不是"修改当前生效的配置"：
     * 走错接口就会出现"改一个配置，所有配置都变了"（改的其实是当前生效的那份）。
     */
    fun applyChange(transform: (KeyStrokesConfig) -> KeyStrokesConfig) {
        if (editMode == EditMode.ON_SAVE) {
            draft = transform(draft)
        } else {
            viewModel.updateConfigById(source.id, transform)?.let { working = it }
        }
    }

    val dirty = editMode == EditMode.ON_SAVE && draft != source

    /*
     * 名称/描述用顶层状态 + id 变化时同步。
     *
     * 为什么不直接绑 editable.name：实时生效模式下每次输入都会重建 config 对象，
     * 输入框的值若来自那个对象，光标会跳。用本地状态承载正在输入的内容，
     * 只在切换配置时才从配置里取一次初值。
     */
    var name by remember(editable.id) { mutableStateOf(editable.name) }
    var description by remember(editable.id) { mutableStateOf(editable.description) }

    LaunchedEffect(editable.id) {
        name = editable.name
        description = editable.description
    }

    /** 导入/删除字体是磁盘操作，放协程里做 */
    val scope = rememberCoroutineScope()

    /** 一次性提示（导入成功/失败） */
    var toast by remember { mutableStateOf<String?>(null) }

    /**
     * 字体库的版本号。
     *
     * 导入是从字体选择对话框**外面**发起的（SAF 选择器需要 Activity），
     * 所以对话框自己感知不到；这里在导入成功后自增，传给对话框让它重读列表。
     * 少了这一步就会出现"导入成功了但列表里没有，要关掉重开才看得到"。
     */
    var fontRevision by remember { mutableIntStateOf(0) }

    var showResetDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }

    /**
     * 触摸区域编辑器开着没有。
     *
     * ⚠️ 只有**触屏样式**会把「触摸区域编辑」按钮的点击接到它上面
     * （见 `TouchSections.kt`），别的样式永远读不到它。
     *
     * ⚠️ 它在这里只是一个**开关**:真正的编辑界面是几块独立的悬浮窗
     * （见 `TouchRegionEditor`），不画在这个页面里 ——
     * 因为触摸区域是**整块屏幕**上的东西，而这里是应用自己的窗口。
     */
    var showTouchRegionEditor by remember { mutableStateOf(false) }
    var showConvertDialog by remember { mutableStateOf(false) }
    var showFontPicker by remember { mutableStateOf(false) }

    /*
     * 图片字体是**独立的一个对话框**（常规字体必选 + 图片字体可选），
     * 所以状态也是独立的。合成一个布尔量的话，用户从图片字体那一项
     * 进去也会把"选择常规字体"弹出来。
     */
    var showBitmapFontPicker by remember { mutableStateOf(false) }

    /** 每个键各自的文字偏移窗口 */
    var showSlotOffsetDialog by remember { mutableStateOf(false) }

    /** 每个键各自的字间距 / 行间距窗口 */
    var showSlotSpacingDialog by remember { mutableStateOf(false) }

    /**
     * 待命名的图片字体导入。
     *
     * 选完文件先不登记，而是等用户起名字 —— 图集文件名基本都叫
     * `ascii.png`，直接拿它当字体名的话导几张之后列表里全是同名条目。
     */
    val pendingBitmapImport = remember {
        mutableStateOf<BitmapFontImporter.Source?>(null)
    }

    /*
     * 字体导入的文件选择器。
     *
     * 用 SAF（系统文件选择器）而不是申请存储权限：
     * 用户选哪个文件就只有那个文件的读取权，不用暴露整个存储。
     */
    val fontPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult

        scope.launch {
            val imported = withContext(Dispatchers.IO) { FontRegistry.import(uri) }
            if (imported == null) {
                toast = "导入失败：请确认文件是 .ttf / .otf / .ttc"
            } else {
                // 导入后直接选中它，省掉"导入完还得再点一次"的多余操作
                applyChange { it.copy(fontId = imported.id) }
                // 通知字体选择对话框重读列表（它感知不到外部导入）
                fontRevision++
                toast = "已导入「${imported.displayName}」"
            }
        }
    }

    /*
     * 图片字体的导入。
     *
     * ⚠️ 这一条**不走"先关对话框再重开"那一套**（矢量字体才需要）：
     * 选完文件之后弹的是**我们自己**的命名对话框，把选择窗口一起关掉
     * 反而让用户看不到"新导入的字体出现在列表里"。
     *
     * 但 `AlertDialog` 是独立窗口，SAF 选择器一起来宿主 Activity 暂停、
     * 对话框窗口失焦，系统会给它一个 dismiss。所以选择器返回时把
     * `showBitmapFontPicker` 重新置 true —— 抵消系统那次 dismiss。
     */
    val bitmapFontPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            showBitmapFontPicker = true
            return@rememberLauncherForActivityResult
        }

        scope.launch {
            val source = withContext(Dispatchers.IO) {
                BitmapFontImporter.readFrom(context, uri)
            }

            showBitmapFontPicker = true

            if (source is BitmapFontImporter.Source.Failure) {
                toast = source.reason
                return@launch
            }

            pendingBitmapImport.value = source
        }
    }

    PendingBitmapImportDialog(
        source = pendingBitmapImport.value,
        onDismiss = { pendingBitmapImport.value = null },
        onRegistered = { fontId, name ->
            pendingBitmapImport.value = null

            /*
             * ⚠️ 只改 `bitmapFontId`，**不动 `fontId`** ——
             * 图片字体是可选的叠加项，常规字体仍要留着给中文兜底。
             * 写成 `copy(fontId = ...)` 会把用户的常规字体顶掉，
             * 而那个 id 是 `bitmap:` 前缀、`FontRegistry` 找不到，
             * 界面就会显示"默认（字体已移除）"。
             */
            applyChange { it.copy(bitmapFontId = fontId) }
            fontRevision++
            toast = "已导入「$name」"
        },
        onFailed = { reason ->
            pendingBitmapImport.value = null
            toast = reason
        },
    )

    /** 返回：保存生效模式下有未保存改动时先确认，避免误触丢失 */
    fun requestBack() {
        if (dirty) showDiscardDialog = true else onBack()
    }

    ScrollableScreen(
        title = editable.name,
        subtitle = if (editMode == EditMode.ON_SAVE) {
            if (dirty) "有未保存的修改" else "保存生效模式"
        } else {
            "实时生效模式 · 改动已自动保存"
        },
        onBack = { requestBack() },
        modifier = modifier,
        bottomBar = if (editMode == EditMode.ON_SAVE) {
            {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 3.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (dirty) "有未保存的修改" else "没有改动",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = {
                                // 保存：内部会推版本号，并在这份配置正开着悬浮窗时刷新它
                                viewModel.saveConfig(draft)
                                onBack()
                            },
                            enabled = dirty,
                        ) {
                            Text("保存")
                        }                    }
                }
            }
        } else {
            null
        },
    ) {
        /*
         * ============================================================
         * 基本信息 + 预览（同一张卡片，一行两栏）
         *
         * 左边改名称/描述，右边固定尺寸的预览 —— 改完抬头就能看到效果，
         * 也省掉一层纵向空间。预览框尺寸是**固定**的，不随配置里的按键大小变化，
         * 这样页面高度稳定、不同配置看起来也整齐。
         * ============================================================
         */

        item { SectionHeader("基本信息") }

        item {
            SettingsCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    /*
                     * 左栏：名称 / 描述 / 样式
                     */

                    Column(modifier = Modifier.weight(1f)) {
                        if (editable.builtIn) {
                            Text(
                                text = "内置配置",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = "名称固定为 Default，不可改名或删除",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            OutlinedTextField(
                                value = name,
                                onValueChange = { value ->
                                    name = value
                                    applyChange { it.copy(name = value) }
                                },
                                singleLine = true,
                                label = { Text("名称") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = description,
                            onValueChange = { value ->
                                description = value
                                applyChange { it.copy(description = value) }
                            },
                            label = { Text("描述") },
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "样式：${editable.style.label}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    /*
                     * 右栏：固定尺寸预览
                     */

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "预览",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        ConfigPreview(
                            config = editable,
                            widthDp = PREVIEW_WIDTH_DP,
                            heightDp = PREVIEW_HEIGHT_DP,
                        )
                    }
                }
            }
        }

        /*
         * ============================================================
         * 从这里开始按样式分流
         * ============================================================
         * Live2D 与按键显示是两套完全不同的实现，设置项**没有一项是共用的**
         * （唯一例外是"整体缩放"：它对两者含义相同，都是把内容和窗口一起放大）。
         *
         * 所以这里一分为二：Live2D 只显示它自己那几项，
         * 按键那些一个都不出现 —— 否则用户会对着十几个改了没反应的选项发呆。
         *
         * 说明：为了不动下面这 400 多行已有代码，else 分支里的缩进**保持原样**
         * （少一级）。逻辑与结构不受影响，纯粹是为了把改动压到最小。
         */

        if (isLive2D) {
            live2DStyleSections(
                editable = editable,
                modelName = live2DModelName,
                onPickModel = { showModelPicker = true },
            ) { transform -> applyChange(transform) }
        } else if (isCustomKey) {
            /*
             * 自定义 Key：这里**只留通用的东西**（名称/描述/预览在上面，
             * 重置/删除在下面），中间全部交给"自定义编辑"那个二级页面。
             *
             * 不在这里内联摆编辑器的另一个理由：编辑器有工具栏、属性面板、
             * 带吸附的画布，塞进这个滚动页里既挤又会和页面的滚动抢手势。
             */
            customKeyStyleSections(
                editable = editable,
                // 传 editable.id 而不是 configId：草稿模式下用户可能正在改别的字段，
                // 但"要编辑的是哪一份配置"始终以当前正在编辑的这份为准
                onOpenEditor = { onOpenCustomEditor(editable.id) },
                onOpenRegionEditor = { openTouchRegionEditor() },
            ) { transform -> applyChange(transform) }
        } else {

        /*
         * ============================================================
         * 触屏设置（**只有触屏样式有**）
         * ============================================================
         * ⚠️ 位置在「外观」**之前**（用户明确指定）:
         *
         * > 在触屏样式的「外观」栏**上方**添加「触屏设置」栏
         *
         * ⚠️ 与手柄那三个摇杆分区（在「行为」之后）位置不同是有道理的:
         * 触屏设置里第一个东西是**触摸区域入口**，那是"能不能用"的事 ——
         * 用户先要能用，才谈得上调外观。
         *
         * ⚠️ 内容在 `TouchSections.kt`（本文件已经 2200 多行）。
         */
        if (isTouching) {
            touchStyleSections(
                editable = editable,
                /*
                 * ⚠️ 判断逻辑在 [openTouchRegionEditor] 里**只有一份** ——
                 * 自定义 Key 那边用的是**同一个** lambda（见上面那段说明）。
                 */
                onOpenRegionEditor = { openTouchRegionEditor() },
            )
        }

        /*
         * ============================================================
         * 外观
         *
         * 顺序按需求定：整体缩放 → 文字缩放 → 圆角 → 描边 → 文字阴影 → 字体。
         * 前三项是尺寸，中间两项是"给键帽/文字加一层装饰"，字体放最后。
         *
         * ⚠️ 描边**从独立分区挪进来了**：它和圆角一样是"键帽外观的一部分"，
         * 单独占一个分区会让用户在两处之间来回找。
         * ============================================================
         */

        collapsibleSection(
            expanded = "外观" in expandedSections,
            onExpandChange = { on -> expandedSections = setExpanded(expandedSections, "外观", on) },
            key = "外观",
            title = "外观",
        ) {

        item {
            SettingsCard {
                /*
                 * 这里只有"整体缩放"和"文字缩放"。
                 *
                 * 旧项目的按键大小与间距是**布局常量**（键 80、间距 10），
                 * 不提供单独调节 —— 一旦让它们可变，整套写死的坐标就得跟着重算，
                 * 极容易出现错位/裁剪。因此这两个滑块不做（字段仍保留在数据里，
                 * 以后要做自定义布局再放开）。
                 */

                SliderRow(
                    label = "整体缩放",
                    value = editable.scalePercent.toFloat(),
                    valueRange = KeyLayout.SCALE_PERCENT_MIN.toFloat()..
                        KeyLayout.SCALE_PERCENT_MAX.toFloat(),
                    steps = SCALE_SLIDER_STEPS,
                    display = "${editable.scalePercent}%",
                    onValueChange = { value -> applyChange { it.copy(scalePercent = value.toInt()) } },
                )

                CardDivider()

                SliderRow(
                    label = "文字缩放",
                    value = editable.textScalePercent.toFloat(),
                    valueRange = 50f..150f,
                    steps = 9,
                    display = "${editable.textScalePercent}%",
                    onValueChange = { value -> applyChange { it.copy(textScalePercent = value.toInt()) } },
                )

                CardDivider()

                /*
                 * ============================================================
                 * 按键高度 / 按键间距 —— 放在「外观」，**不放在「行为」**
                 * ============================================================
                 * 用户的原话:"按键高度的滑块不应该加到行为这里，应该加到外观那吧，
                 * 而且要注意位置……不要让按键高度滑块插在中间，因为刚刚发现 cps
                 * 开启时多出的设置项内，按键高度就插进去了"。
                 *
                 * ⚠️ 教训是**别把全局项插进"开关展开的专属项"之间**:
                 * 「描边」和「文字阴影」都是"开关 + 展开的专属项"，
                 * 中间插一个无关的滑块会让它看起来像是那个开关的子项。
                 * 所以这两个滑块紧跟在两个缩放之后、**在所有开关之前**。
                 *
                 * ⚠️ 两者的分工完全不同，界面文字必须说清:
                 *
                 * | 滑块 | 改什么 |
                 * |---|---|
                 * | **按键高度** | 键的**高度**（不影响宽度、不影响间距） |
                 * | **按键间距** | 键的**位置**（窗口宽度会跟着变，**键本身不变大变小**） |
                 *
                 * ⚠️ 间距那条是用户专门纠正过的:"这个调整间距的效果也需要改进一下，
                 * 不能调整组件大小，只是起到调整间距的效果，本质是改位置，尺寸不能改"。
                 * 所以它的下限是 **0%**（键挨在一起），而不是"把键缩小"。
                 */
                SliderRow(
                    label = "按键高度",
                    value = editable.keyHeightPercent.toFloat(),
                    valueRange = KeyLayout.KEY_HEIGHT_PERCENT_MIN.toFloat()..KeyLayout.KEY_HEIGHT_PERCENT_MAX.toFloat(),
                    display = "${editable.keyHeightPercent}%",
                    onValueChange = { value ->
                        applyChange { it.copy(keyHeightPercent = value.roundToInt()) }
                    },
                )

                CardDivider()

                SliderRow(
                    label = "按键间距",
                    value = editable.keyGapPercent.toFloat(),
                    valueRange = KeyLayout.KEY_GAP_PERCENT_MIN.toFloat()..KeyLayout.KEY_GAP_PERCENT_MAX.toFloat(),
                    display = "${editable.keyGapPercent}%",
                    onValueChange = { value ->
                        applyChange { it.copy(keyGapPercent = value.roundToInt()) }
                    },
                )

                CardDivider()

                SwitchItem(
                    title = "圆角",
                    leadingIcon = Icons.Rounded.RoundedCorner,
                    subtitle = "开启后可调整键帽圆角大小",
                    checked = editable.cornerRadiusEnabled,
                    onCheckedChange = { enabled ->
                        applyChange { it.copy(cornerRadiusEnabled = enabled) }
                    },
                )

                if (editable.cornerRadiusEnabled) {
                    SliderRow(
                        label = "圆角大小",
                        value = editable.cornerRadiusPercent,
                        valueRange = 0f..50f,
                        steps = 9,
                        display = "${editable.cornerRadiusPercent.toInt()}%",
                        onValueChange = { value -> applyChange { it.copy(cornerRadiusPercent = value) } },
                    )
                }

                CardDivider()

                /* ---------- 描边 ---------- */

                SwitchItem(
                    title = "描边",
                    leadingIcon = Icons.Rounded.BorderStyle,
                    subtitle = "在键帽外圈绘制一层轮廓，浅色背景下更清晰",
                    checked = editable.outline.enabled,
                    onCheckedChange = { enabled ->
                        applyChange { it.copy(outline = it.outline.copy(enabled = enabled)) }
                    },
                )

                if (editable.outline.enabled) {
                    SliderRow(
                        label = "描边宽度",
                        value = editable.outline.width,
                        valueRange = 0.5f..5f,
                        steps = 8,
                        display = "%.1f dp".format(editable.outline.width),
                        onValueChange = { value ->
                            applyChange { it.copy(outline = it.outline.copy(width = value)) }
                        },
                    )
                }

                CardDivider()

                /* ---------- 文字阴影 ---------- */

                SwitchItem(
                    title = "文字阴影",
                    leadingIcon = Icons.Rounded.FormatColorText,
                    subtitle = "给文字加一层影子，在花哨的背景上更易读",
                    checked = editable.shadow.enabled,
                    onCheckedChange = { enabled ->
                        applyChange { it.copy(shadow = it.shadow.copy(enabled = enabled)) }
                    },
                )

                if (editable.shadow.enabled) {
                    /*
                     * 形态用分段按钮而不是下拉：只有两种，摊开更直观，
                     * 而且点一下就能看到预览变化（预览就在本页顶部）。
                     */
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            text = "阴影形态",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            ShadowMode.entries.forEachIndexed { index, shadowMode ->
                                SegmentedButton(
                                    selected = editable.shadow.mode == shadowMode,
                                    onClick = {
                                        applyChange {
                                            it.copy(shadow = it.shadow.copy(mode = shadowMode))
                                        }
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = ShadowMode.entries.size,
                                    ),
                                ) {
                                    Text(shadowMode.label)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            /*
                             * 一个滑块两种含义，必须写清楚 ——
                             * 否则用户切到硬阴影会以为滑块坏了（效果从"变糊"变成"挪位"）。
                             */
                            text = when (editable.shadow.mode) {
                                ShadowMode.SOFT -> "柔光：跟随文字形状的模糊投影，滑块控模糊程度"
                                ShadowMode.HARD -> "硬阴影：文字右下方一份实心副本，滑块控偏移距离"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    SliderRow(
                        label = if (editable.shadow.mode == ShadowMode.SOFT) "模糊程度" else "偏移距离",
                        value = editable.shadow.size,
                        valueRange = TextShadow.SIZE_MIN..TextShadow.SIZE_MAX,
                        display = "%.1f dp".format(editable.shadow.size),
                        onValueChange = { value ->
                            applyChange { it.copy(shadow = it.shadow.copy(size = value)) }
                        },
                    )
                }
            }
        }

        /*
         * ============================================================
         * 自定义字体
         *
         * 放在「外观」的下一张卡：它属于外观，但和"尺寸/圆角"这类滑块不是一类东西
         * （要点开一个列表选），所以独立成卡更清楚。
         * ============================================================
         */

        item {
            SettingsCard {
                SettingItem(
                    title = "自定义字体",
                    leadingIcon = Icons.Rounded.FontDownload,
                    subtitle = FontRegistry.displayNameOf(editable.fontId),
                    onClick = { showFontPicker = true },
                )

                /*
                 * 图片字体（可选）。
                 *
                 * ============================================================
                 * 为什么是**独立一项**、而不是和上面合成一个入口
                 * ============================================================
                 * 两者不是二选一：图片字体只有 ASCII 字形，中文要靠上面的
                 * 常规字体兜底。所以真实关系是「常规字体（必选）+
                 * 图片字体（可选）」，界面上也就是两个入口。
                 *
                 * 未选择时显示"不使用" —— 它的默认状态就是不用，
                 * 写成"默认（字体已移除）"那种会让人以为哪里坏了。
                 */
                CardDivider()
                SettingItem(
                    title = "图片字体",
                    leadingIcon = Icons.Rounded.Image,
                    subtitle = if (editable.bitmapFontId.isBlank()) {
                        "不使用（只有 ASCII 会用到它）"
                    } else {
                        FontRegistry.displayNameOf(editable.bitmapFontId)
                    },
                    onClick = { showBitmapFontPicker = true },
                )
            }
        }

        } // ← 折叠结束：外观

        /*
         * ============================================================
         * 颜色（只有 6 位 RGB，透明度在下一分区单独调）
         *
         * 顺序按需求定：键帽 → 文字 → 描边 → 阴影，每对都是"未按下、按下"。
         * 四个元素的结构完全一样，扫一眼就知道规律。
         * 描边与阴影那两对**只在功能开启时出现** —— 没开的时候调颜色没有意义。
         * ============================================================
         */

        collapsibleSection(
            expanded = "颜色" in expandedSections,
            onExpandChange = { on -> expandedSections = setExpanded(expandedSections, "颜色", on) },
            key = "颜色",
            title = "颜色",
        ) {

        item {
            SettingsCard {
                HexColorRow(
                    label = "键帽（未按下）",
                    value = editable.colors.keyUp,
                    alphaPercent = editable.opacity.keyUp,
                    onValueChange = { color -> applyChange { it.copy(colors = it.colors.copy(keyUp = color)) } },
                )
                HexColorRow(
                    label = "键帽（按下）",
                    value = editable.colors.keyDown,
                    alphaPercent = editable.opacity.keyDown,
                    onValueChange = { color -> applyChange { it.copy(colors = it.colors.copy(keyDown = color)) } },
                )
                HexColorRow(
                    label = "文字（未按下）",
                    value = editable.colors.textUp,
                    alphaPercent = editable.opacity.textUp,
                    onValueChange = { color -> applyChange { it.copy(colors = it.colors.copy(textUp = color)) } },
                )
                HexColorRow(
                    label = "文字（按下）",
                    value = editable.colors.textDown,
                    alphaPercent = editable.opacity.textDown,
                    onValueChange = { color -> applyChange { it.copy(colors = it.colors.copy(textDown = color)) } },
                )

                if (editable.outline.enabled) {
                    HexColorRow(
                        label = "描边（未按下）",
                        value = editable.colors.outlineUp,
                        alphaPercent = editable.opacity.outlineUp,
                        onValueChange = { color ->
                            applyChange { it.copy(colors = it.colors.copy(outlineUp = color)) }
                        },
                    )
                    HexColorRow(
                        label = "描边（按下）",
                        value = editable.colors.outlineDown,
                        alphaPercent = editable.opacity.outlineDown,
                        onValueChange = { color ->
                            applyChange { it.copy(colors = it.colors.copy(outlineDown = color)) }
                        },
                    )
                }

                if (editable.shadow.enabled) {
                    HexColorRow(
                        label = "文字阴影（未按下）",
                        value = editable.colors.shadowUp,
                        alphaPercent = editable.opacity.shadowUp,
                        onValueChange = { color ->
                            applyChange { it.copy(colors = it.colors.copy(shadowUp = color)) }
                        },
                    )
                    HexColorRow(
                        label = "文字阴影（按下）",
                        value = editable.colors.shadowDown,
                        alphaPercent = editable.opacity.shadowDown,
                        onValueChange = { color ->
                            applyChange { it.copy(colors = it.colors.copy(shadowDown = color)) }
                        },
                    )
                }
            }
        }

        item {
            SectionHint(text = "颜色只填 6 位十六进制（例如 FF8800），透明度用下面的滑块单独调整。")
        }

        } // ← 折叠结束：颜色

        /*
         * ============================================================
         * 透明度
         *
         * 八个滑块：键帽 / 文字 / 描边 / 文字阴影，各自分"未按下、按下"。
         * 顺序与上面的「颜色」分区**完全一致** —— 用户在两个分区之间
         * 来回对照时不需要重新找位置。
         *
         * ⚠️ 下限从 20 放宽到 0：有独立滑块之后，"不想要描边/阴影"
         * 是合理需求，卡在 20 就变成了做不到的事。
         * ============================================================
         */

        collapsibleSection(
            expanded = "透明度" in expandedSections,
            onExpandChange = { on -> expandedSections = setExpanded(expandedSections, "透明度", on) },
            key = "透明度",
            title = "透明度",
        ) {

        item {
            SettingsCard {
                SliderRow(
                    label = "键帽（未按下）",
                    value = editable.opacity.keyUp.toFloat(),
                    valueRange = 0f..100f,
                    steps = 19,
                    display = "${editable.opacity.keyUp}%",
                    onValueChange = { value ->
                        applyChange { it.copy(opacity = it.opacity.copy(keyUp = value.toInt())) }
                    },
                )

                CardDivider()

                SliderRow(
                    label = "键帽（按下）",
                    value = editable.opacity.keyDown.toFloat(),
                    valueRange = 0f..100f,
                    steps = 19,
                    display = "${editable.opacity.keyDown}%",
                    onValueChange = { value ->
                        applyChange { it.copy(opacity = it.opacity.copy(keyDown = value.toInt())) }
                    },
                )

                CardDivider()

                SliderRow(
                    label = "文字（未按下）",
                    value = editable.opacity.textUp.toFloat(),
                    valueRange = 0f..100f,
                    steps = 19,
                    display = "${editable.opacity.textUp}%",
                    onValueChange = { value ->
                        applyChange { it.copy(opacity = it.opacity.copy(textUp = value.toInt())) }
                    },
                )

                CardDivider()

                SliderRow(
                    label = "文字（按下）",
                    value = editable.opacity.textDown.toFloat(),
                    valueRange = 0f..100f,
                    steps = 19,
                    display = "${editable.opacity.textDown}%",
                    onValueChange = { value ->
                        applyChange { it.copy(opacity = it.opacity.copy(textDown = value.toInt())) }
                    },
                )

                if (editable.outline.enabled) {
                    CardDivider()

                    SliderRow(
                        label = "描边（未按下）",
                        value = editable.opacity.outlineUp.toFloat(),
                        valueRange = 0f..100f,
                        steps = 19,
                        display = "${editable.opacity.outlineUp}%",
                        onValueChange = { value ->
                            applyChange { it.copy(opacity = it.opacity.copy(outlineUp = value.toInt())) }
                        },
                    )

                    CardDivider()

                    SliderRow(
                        label = "描边（按下）",
                        value = editable.opacity.outlineDown.toFloat(),
                        valueRange = 0f..100f,
                        steps = 19,
                        display = "${editable.opacity.outlineDown}%",
                        onValueChange = { value ->
                            applyChange { it.copy(opacity = it.opacity.copy(outlineDown = value.toInt())) }
                        },
                    )
                }

                if (editable.shadow.enabled) {
                    CardDivider()

                    SliderRow(
                        label = "文字阴影（未按下）",
                        value = editable.opacity.shadowUp.toFloat(),
                        valueRange = 0f..100f,
                        steps = 19,
                        display = "${editable.opacity.shadowUp}%",
                        onValueChange = { value ->
                            applyChange { it.copy(opacity = it.opacity.copy(shadowUp = value.toInt())) }
                        },
                    )

                    CardDivider()

                    SliderRow(
                        label = "文字阴影（按下）",
                        value = editable.opacity.shadowDown.toFloat(),
                        valueRange = 0f..100f,
                        steps = 19,
                        display = "${editable.opacity.shadowDown}%",
                        onValueChange = { value ->
                            applyChange { it.copy(opacity = it.opacity.copy(shadowDown = value.toInt())) }
                        },
                    )
                }
            }
        }

        } // ← 折叠结束：透明度

        /*
         * ============================================================
         * 行为
         * ============================================================
         */

        collapsibleSection(
            expanded = "行为" in expandedSections,
            onExpandChange = { on -> expandedSections = setExpanded(expandedSections, "行为", on) },
            key = "行为",
            title = "行为",
        ) {

        item {
            SettingsCard {
                /*
                 * 按键动画：三种模式并列。
                 *
                 * 把"无动画"也作为一种**模式**（而不是一个独立开关）：
                 * 语义上它和另外两种是并列关系，界面上用一组分段按钮表达最直观，
                 * 也不会出现"关掉开关后另一组选项该不该灰掉"这种别扭问题。
                 */
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "按下动画",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = editable.animationMode.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        AnimationMode.entries.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = editable.animationMode == mode,
                                onClick = { applyChange { it.copy(animationMode = mode) } },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = AnimationMode.entries.size,
                                ),
                            ) {
                                Text(mode.label)
                            }
                        }
                    }

                    // 无动画没有"时长"可言，因此滑块直接隐藏（而不是置灰）
                    if (editable.animationMode != AnimationMode.NONE) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "动画时长",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "%.1f s".format(editable.animationDurationSec),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Slider(
                            value = editable.animationDurationSec,
                            onValueChange = { value ->
                                applyChange { it.copy(animationDurationSec = value) }
                            },
                            valueRange = ANIMATION_DURATION_MIN..ANIMATION_DURATION_MAX,
                            // 0.1s 一档：(0.5-0.1)/0.1 - 1 = 3 个中间刻度
                            steps = 3,
                        )
                    }
                }

                CardDivider()

                SwitchItem(
                    /*
                     * ⚠️ 标题按**样式**走，三种情况:
                     *
                     * | 样式 | 这个槽位是 | 标题 |
                     * |---|---|---|
                     * | 手柄 | **B 键**（`BTN_EAST`） | 「显示 B 键」 |
                     * | 触屏 | **Shift**（与键盘一样） | 「显示 Shift 键」 |
                     * | 键盘 | Shift | 「显示 Shift 键」 |
                     *
                     * ⚠️ 手柄的布局用的是键盘布局，但键位映射换过了
                     * （见 `gamepad2KeyMappings`）—— 标题不跟着变的话，
                     * 手柄用户会看到一个"显示 Shift 键"的开关，
                     * 而悬浮窗上那个位置明明写着 B，没人能猜到那是一回事。
                     *
                     * ⚠️ 触屏**不跟着变** —— 用户明确要求"触屏样式的行为栏
                     * 跟键盘样式做一样，符合审美"，而触屏里这个槽位就是 Shift。
                     */
                    title = if (editable.styleId == StyleId.GAMEPAD2) {
                        "显示 B 键"
                    } else {
                        "显示 Shift 键"
                    },
                    checked = editable.showShiftKey,
                    leadingIcon = Icons.Rounded.Keyboard,
                    onCheckedChange = { enabled -> applyChange { it.copy(showShiftKey = enabled) } },
                )

                CardDivider()

                SwitchItem(
                    /*
                     * ⚠️ **只有手柄样式**叫「显示扳机键」。
                     *
                     * 手柄那边 `LMB` / `RMB` 这两个槽位在**屏幕上就是
                     * LT / RT**（见 `gamepad2KeyMappings`:"左边是 LT、右边是 RT"）——
                     * 所以标题该叫「显示扳机键」。用户的原话:
                     *
                     * > **手柄样式**的行为栏，应该把「显示鼠标按键」改为
                     * > 「显示扳机键」，这个没问题吧，**手柄样式就是这样讲的**
                     *
                     * ⚠️⚠️ **触屏仍然是「显示鼠标按键」**（我改错过一次）
                     * ============================================================
                     * 用户的反馈:
                     *
                     * > 而且触屏样式的显示鼠标键**怎么又变成显示扳机键**了
                     *
                     * ⚠️ 我当时的理由是"触屏的 LMB/RMB 也显示成 LMB/RMB" ——
                     * 但那是**结果**不是理由:触屏的默认键位文字按用户的要求
                     * 就是 **LMB / RMB**（见 `touchingKeyMappings`），
                     * 它压根不叫 LT/RT，所以也不该叫「扳机键」。
                     *
                     * ⚠️ 判据从 `usesJoystickLayout`（触屏也算）改成
                     * **`styleId == StyleId.GAMEPAD2`** —— 与「显示 B 键」
                     * 那个判据一致（它也只给手柄）。
                     */
                    title = if (editable.styleId == StyleId.GAMEPAD2) {
                        "显示扳机键"
                    } else {
                        "显示鼠标按键"
                    },
                    checked = editable.showMouseButtons,
                    onCheckedChange = { enabled -> applyChange { it.copy(showMouseButtons = enabled) } },
                )

                CardDivider()

                /*
                 * ============================================================
                 * 手柄样式专属开关
                 * ============================================================
                 * 手柄样式（「标准」）的布局仍是键盘布局，只是 WASD 那两块
                 * 换成了摇杆、并且多了扳机/肩键/A 键这些**手柄才有的键**。
                 *
                 * ⚠️ 只在手柄样式下显示 —— 键盘样式没有这些键，
                 * 显示出来点了也没反应。
                 *
                 * ⚠️ 注意 **Shift 槽位在手柄样式里就是 A 键**（用户说过
                 * "A 本身就指代 space"是误记，实际见 `GamepadBindings`）——
                 * 所以标签要跟着样式变，见下面那个 `title`。
                 */
                /*
                 * ============================================================
                 * 「显示 SPACE 键」与「SPACE 键置顶」—— **键盘与触屏都有**
                 * ============================================================
                 * 用户的原话:"key 样式，需要添加'显示 SPACE 键开关'（默认开启），
                 * 以及'SPACE 键置顶'开关，默认关闭，原理跟手柄的标准样式的
                 * 'A 键置顶'是一样的，你自己翻一下代码避免踩坑"。
                 *
                 * ⚠️⚠️ **触屏也要有这两个开关**（我在这一处反复错过，记下来）
                 * ============================================================
                 * 用户的原话:
                 *
                 * > 把现在的「显示 SPACE 键」**改为绑定在现在触屏样式已经默认
                 * > 有的这个 Space 组件上**，并让他**默认打开**，而不是删去，
                 * > **Space 置顶也用得到啊**
                 *
                 * ⚠️ 我犯过两个相反方向的错:
                 *
                 * | 版本 | 错在哪 |
                 * |---|---|
                 * | 第一版 | 判据 `!= GAMEPAD2` —— 触屏有了开关，但它绑在**多余**的 SPACE 槽位上（触屏当时拿 A 当 Space） |
                 * | 第二版 | 判据 `== KEYSTROKES` —— ⚠️ **把触屏直接排除了**，开关**消失**（用户当场发现） |
                 *
                 * ⚠️ 现在的前提已经修好:触屏**改用 `SPACE` 槽位**当 Space
                 * （见 `touchingKeyMappings`），`A_BUTTON` 行关掉 ——
                 * 于是这两个开关**正好绑在唯一的那个 Space 上**。
                 *
                 * ⚠️ 手柄仍然没有这两个开关:它拿 `A_BUTTON` 当空格位、
                 * 显示 "A"，那个槽位有自己的「显示 A 键」/「A 键置顶」。
                 */
                if (editable.styleId != StyleId.GAMEPAD2) {
                    SwitchItem(
                        title = "显示 SPACE 键",
                    leadingIcon = Icons.Rounded.SpaceBar,
                        subtitle = "独占一行，横跨两列",
                        checked = editable.showSpaceKey,
                        onCheckedChange = { enabled ->
                            applyChange { it.copy(showSpaceKey = enabled) }
                        },
                    )

                    CardDivider()

                    SwitchItem(
                        title = "SPACE 键置顶",
                    leadingIcon = Icons.Rounded.VerticalAlignTop,
                        subtitle = "把空格挪到 WASD 上方，其余键整体下移一行",
                        checked = editable.spaceKeyOnTop,
                        /* 不显示空格时"置顶"没有意义（与 A 键置顶同一个道理） */
                        enabled = editable.showSpaceKey,
                        onCheckedChange = { enabled ->
                            applyChange { it.copy(spaceKeyOnTop = enabled) }
                        },
                    )

                    CardDivider()
                }

                /*
                 * ⚠️ 手柄专属的键（肩键 / A 键）—— **只有手柄样式有**。
                 *
                 * ⚠️ 用户明确要求:"LB 与 RB 是**手柄样式专属**的，
                 * 触屏样式里不应该存在"。
                 *
                 * ⚠️ 所以判据从 `usesJoystickLayout` 改成
                 * **`styleId == StyleId.GAMEPAD2`** —— 触屏虽然槽位布局
                 * 与手柄一样，但它**没有** A / LB / RB 这几个键。
                 */
                if (editable.styleId == StyleId.GAMEPAD2) {
                    SwitchItem(
                        title = "显示肩键",
                    leadingIcon = Icons.Rounded.SportsEsports,
                        subtitle = "LB / RB，显示在 LT / RT 那一行下面",
                        checked = editable.showShoulderButtons,
                        onCheckedChange = { enabled ->
                            applyChange { it.copy(showShoulderButtons = enabled) }
                        },
                    )

                    CardDivider()

                    SwitchItem(
                        title = "显示 A 键",
                    leadingIcon = Icons.Rounded.TouchApp,
                        subtitle = "独占一行，横跨两列",
                        checked = editable.showAButton,
                        onCheckedChange = { enabled ->
                            applyChange { it.copy(showAButton = enabled) }
                        },
                    )

                    CardDivider()

                    SwitchItem(
                        title = "A 键置顶",
                    leadingIcon = Icons.Rounded.VerticalAlignTop,
                        subtitle = "把 A 键那一行提到摇杆正下方（LT / RT 之上）",
                        checked = editable.aButtonOnTop,
                        /* 不显示 A 键时"置顶"没有意义 */
                        enabled = editable.showAButton,
                        onCheckedChange = { enabled ->
                            applyChange { it.copy(aButtonOnTop = enabled) }
                        },
                    )

                    CardDivider()
                }

                /*
                 * ============================================================
                 * 摇杆互换（**手柄与触屏共用**，键盘没有）
                 * ============================================================
                 * ⚠️ 它原来在手柄专属块里，用户明确要求触屏也保留:
                 *
                 * > 现在触屏样式内，行为栏的功能外显都做成与键盘样式一样的
                 * > （**保留摇杆互换**，不过现在触屏样式的摇杆互换还没接上，
                 * > 一会记得补上）
                 *
                 * ⚠️ 判据用 `usesJoystickLayout` —— 那正是"有没有两个摇杆"，
                 * 键盘样式没有摇杆，显示这个开关点了也没反应。
                 */
                if (KeyLayout.usesJoystickLayout(editable)) {
                    SwitchItem(
                        title = "摇杆互换",
                    leadingIcon = Icons.Rounded.SwapHoriz,
                        subtitle = "左右两个摇杆对调显示的数据",
                        checked = editable.swapSticks,
                        onCheckedChange = { enabled ->
                            applyChange { it.copy(swapSticks = enabled) }
                        },
                    )

                    CardDivider()
                }

                SwitchItem(
                    title = "显示 CPS",
                    leadingIcon = Icons.Rounded.Speed,
                    /*
                     * ⚠️ **只有手柄样式**说「在 LT / RT 上……扳机次数」。
                     *
                     * ⚠️ 手柄那边 `LMB` / `RMB` 两个槽位在屏幕上就是
                     * LT / RT，所以说"扳机"才对。
                     *
                     * ⚠️⚠️ **触屏仍然是「在鼠标键位旁显示每秒点击次数」**
                     * （用户指出来的）—— 它的默认键位文字按用户的要求
                     * 就是 **LMB / RMB**（见 `touchingKeyMappings`），
                     * 压根不叫 LT/RT，所以也不该说"扳机"。
                     *
                     * ⚠️ 判据从 `usesJoystickLayout`（触屏也算）改成
                     * **`styleId == StyleId.GAMEPAD2`** —— 与上面
                     * 「显示扳机键」那一处**同一个判据**，两处必须一致。
                     */
                    subtitle = if (editable.styleId == StyleId.GAMEPAD2) {
                        "在 LT / RT 上显示每秒扳机次数"
                    } else {
                        "在鼠标键位旁显示每秒点击次数"
                    },
                    checked = editable.mouseCpsEnabled,
                    /*
                     * ⚠️ **不再**用 `showMouseButtons` 做前置条件。
                     *
                     * 手柄样式的 LT/RT 也计数 CPS，而它的"显示鼠标按键"
                     * 概念不同（槽位显示的是 LT/RT 的名字）——
                     * 拿它当门槛会让手柄样式里 CPS 开关**点不动**。
                     */
                    onCheckedChange = { enabled -> applyChange { it.copy(mouseCpsEnabled = enabled) } },
                )

                if (editable.mouseCpsEnabled) {
                    CardDivider()
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "CPS 显示模式",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            /*
                             * ============================================================
                             * ⚠️ 界面上只有**两个**模式，但内部编号是 `1` 与 `3`
                             * ============================================================
                             * 用户的原话:"干脆把 cps 模式 2 删掉吧，太多组件也不好
                             * 安排，就留模式 1 和 3 就好了，模式 3 替代原来模式 2
                             * 的位置，也就是原来的分段按钮，现在只保留模式 1、模式 2，
                             * 但是模式 2 其实是现在的模式 3"。
                             *
                             * ⚠️ **不动内部编号**是有意的:`mouseCpsMode` 是
                             * **持久化字段**，把 3 改成 2 会让所有老配置的模式
                             * 静默变掉。所以只改**界面**（按钮数量与文字）。
                             *
                             * 老配置里存着 `2` 的会在**解码时迁移成 `3`**
                             * （见 `JsonConfigCodec`）—— 否则它落在空模式上，
                             * 表现就是"CPS 打开了但什么都不显示"。
                             */
                            val labels = listOf("模式 1", "模式 2")
                            val modes = listOf(1, 3)

                            labels.forEachIndexed { index, label ->
                                SegmentedButton(
                                    selected = editable.mouseCpsMode == modes[index],
                                    onClick = {
                                        applyChange { it.copy(mouseCpsMode = modes[index]) }
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = labels.size,
                                    ),
                                ) {
                                    Text(label)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = when (editable.mouseCpsMode) {
                                1 -> "CPS 直接跟在键面文字后面，数值为 0 时不显示"
                                else -> "CPS 显示在键内部第二行（键会变高一点）"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        /*
                         * CPS 文本模板。
                         *
                         * 模式 1 与其它模式的模板**分开**：模式 1 的 CPS 接在主文字后面，
                         * 为 0 时要整段消失，因此用不同的占位符 `(cps2)`，
                         * 它展开时自带前导空格。
                         */
                        CpsTemplateField(
                            value = if (editable.mouseCpsMode == 1) {
                                editable.cpsTextTemplateMode1
                            } else {
                                editable.cpsTextTemplate
                            },
                            placeholder = if (editable.mouseCpsMode == 1) {
                                DEFAULT_CPS_TEMPLATE_MODE1
                            } else {
                                DEFAULT_CPS_TEMPLATE
                            },
                            hint = if (editable.mouseCpsMode == 1) {
                                "用 (cps2) 表示数值；为 0 时整段不显示，" +
                                    "占位符自带一个空格，想贴着写就把它删掉"
                            } else {
                                "用 (cps) 表示数值，其余文字原样显示，" +
                                    "例如 CPS: (cps) 或 (cps) CPS"
                            },
                            onValueChange = { value ->
                                if (editable.mouseCpsMode == 1) {
                                    applyChange { it.copy(cpsTextTemplateMode1 = value) }
                                } else {
                                    applyChange { it.copy(cpsTextTemplate = value) }
                                }
                            },
                        )
                    }
                }
            }
        }

        } // ← 折叠结束：行为

        /*
         * ============================================================
         * 摇杆的专属分区（**手柄与触屏都有**）
         * ============================================================
         * 用户的原话:"摇杆相关配置可以单独拉出来，不跟其他按键共同配置"。
         *
         * ⚠️ 两者**共用同一个 `joystickStyleSections`** —— 用户明确要求
         * "触屏样式的摇杆相关设置……跟手柄样式一样的处理"，
         * 而且"UI 层面跟手柄基本相同，只有读取逻辑和具体手感之类的要单独设计"。
         *
         * ⚠️ 所以触屏**没有**自己的一套摇杆外观界面:颜色、圆角、描边、
         * 摇杆帽、手感全在这里调，存的是**同一份** `config.joystick`
         * （`JoystickStyle`）—— 只有一份真源。
         *
         * ⚠️ 我中途曾把它对触屏**关掉过**（误以为触屏该有自己的摇杆 UI），
         * 那是错的:那会让触屏的摇杆外观**没地方调**。
         *
         * ⚠️ 放在 `} else {` **内部**:它属于"按键样式"这一套分区的一部分。
         */
        if (KeyLayout.usesJoystickLayout(editable)) {
            joystickStyleSections(
                editable = editable,
                expandedSections = expandedSections,
                onExpandedChange = { expandedSections = it },
            ) { transform -> applyChange(transform) }
        }

        /*
         * ============================================================
         * 文字偏移
         * ============================================================
         * 放在「行为」与「键位映射」之间：它既不是"行为"（不影响按键逻辑），
         * 也不是"键位映射"（不改显示什么字），而是**文字画在哪**。
         *
         * ⚠️ 这个分区主要是为**图片字体**加的：不同字体图集的 `ascent`
         * 不同，字形在格子里高低不一，换一张图集就可能整体偏上/偏下 ——
         * 没有这个滑块就只能靠改图解决。
         *
         * 单位与自定义 Key 的"文字 X/Y 偏移"**完全一致**（基础坐标单位），
         * 所以以后做"Key → 自定义"转换时是**原样对应**，不需要换算。
         * 范围也照抄（−60..60），免得两个页面的滑块手感不一样。
         */

        collapsibleSection(
            expanded = "文字偏移" in expandedSections,
            onExpandChange = { on -> expandedSections = setExpanded(expandedSections, "文字偏移", on) },
            key = "文字偏移",
            title = "文字偏移",
        ) {

        item {
            SettingsCard {
                SliderRow(
                    label = "文字 X 偏移",
                    value = editable.textOffsetX,
                    valueRange = TEXT_OFFSET_MIN..TEXT_OFFSET_MAX,
                    display = editable.textOffsetX.roundToInt().toString(),
                    onValueChange = { value -> applyChange { it.copy(textOffsetX = value) } },
                )
                CardDivider()
                SliderRow(
                    label = "文字 Y 偏移",
                    value = editable.textOffsetY,
                    valueRange = TEXT_OFFSET_MIN..TEXT_OFFSET_MAX,
                    display = editable.textOffsetY.roundToInt().toString(),
                    onValueChange = { value -> applyChange { it.copy(textOffsetY = value) } },
                )

                /*
                 * 每个键各自的偏移放进**独立窗口**。
                 *
                 * 直接平铺在这里的话，8~10 个键 × 2 个滑块 = 二十来个滑条，
                 * 这一页会长到没法用（而这一页本来就已经很长了）。
                 * 收进窗口之后主页面只多一行按钮。
                 */
                CardDivider()
                OutlinedButton(
                    onClick = { showSlotOffsetDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                ) {
                    Text("单独调整每个键…")
                }
            }
        }

        item {
            SectionHint(
                text = "正值是往右、往下。图片字体在不同图集里的基线位置会有差别，" +
                    "用它把文字挪到合适的位置。范围与自定义 Key 的文字偏移一致，" +
                    "所以两者之间互相转换时位置不会跑偏。\n\n" +
                    "个别键如果和别的不一样（比如空格显示的是非 ASCII 横线、" +
                    "走的不是图片字体），用「单独调整每个键」逐个修。",
            )
        }

        } // ← 折叠结束：文字偏移

        /*
         * ============================================================
         * 字间距 / 行间距
         * ============================================================
         * 与「文字偏移」同一套结构：两个全局滑块 + 一个"逐键调整"窗口。
         *
         * ⚠️ 单位是**相对字号的百分比**，不是像素：字号可调，
         * 固定像素值在小字号下会挤成一团、大字号下又几乎看不出来。
         *
         * ⚠️ **对所有字体都显示**，包括常规（矢量）字体。
         * 早先只在使用图片字体时才显示，那是个设计错误 ——
         * "字距不合适"在矢量字体上同样会发生（中文字体尤其常见），
         * 把入口藏起来等于这个功能对多数用户不存在。
         */

        collapsibleSection(
            expanded = "字间距与行间距" in expandedSections,
            onExpandChange = { on ->
                expandedSections = setExpanded(expandedSections, "字间距与行间距", on)
            },
            key = "字间距与行间距",
            title = "字间距与行间距",
        ) {

        item {
            SettingsCard {
                SliderRow(
                    label = "字间距",
                    value = editable.textSpacing.letter,
                    valueRange = SPACING_MIN..LETTER_SPACING_MAX,
                    display = "${editable.textSpacing.letter.roundToInt()}%",
                    onValueChange = { value ->
                        applyChange {
                            it.copy(textSpacing = it.textSpacing.copy(letter = value))
                        }
                    },
                )
                CardDivider()
                SliderRow(
                    label = "行间距",
                    value = editable.textSpacing.line,
                    valueRange = SPACING_MIN..LINE_SPACING_MAX,
                    display = "${editable.textSpacing.line.roundToInt()}%",
                    onValueChange = { value ->
                        applyChange {
                            it.copy(textSpacing = it.textSpacing.copy(line = value))
                        }
                    },
                )

                CardDivider()
                OutlinedButton(
                    onClick = { showSlotSpacingDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                ) {
                    Text("单独调整每个键…")
                }
            }
        }

        item {
            SectionHint(
                text = "百分比是相对字号算的，以后改字号不用重新调。\n\n" +
                    "行间距收紧有下限（不会把两行压在一起）；" +
                    "字间距可以收成负值 —— 有些字体的字形自带较宽的左侧留白，" +
                    "需要收回来才好看。",
            )
        }

        } // ← 折叠结束：字间距与行间距

        /*
         * ============================================================
         * 键位映射 / 键位文字
         * ============================================================
         * ⚠️ 触屏样式**保留这一栏，但只留"显示文字"**（用户要求）。
         *
         * 用户的原话:
         *
         * > 键位映射栏在触屏中**还是有用的**，键位映射栏原本即可以改显示文字
         * > 也可以改读取按键，我们可以**保留键位文字，读取按键删掉**，
         * > 这样子的，所以可以加回来一个「**键位文字**」栏
         *
         * ⚠️ 我第一版把整栏对触屏**隐藏**了 —— 那是错的:
         * "悬浮窗上那个位置写什么字"照样需要（LT 显示成"扳机"、
         * A 显示成"跳"，都是这一栏管的）。
         *
         * ⚠️ 而"绑定哪些物理键"对触屏**确实**没意义:触屏不产出键码、
         * 只产出坐标，输入从哪来由**触摸区域**回答。留着它用户会去绑一个键、
         * 然后发现绑了没反应 —— 那比"没有这个入口"更糟。
         *
         * ⚠️ 所以栏名也跟着换:触屏下叫**「键位文字」**，
         * 名字准确说明它现在只剩这一件事。
         */

        if (isTouching) {

        collapsibleSection(
            expanded = "键位文字" in expandedSections,
            onExpandChange = { on -> expandedSections = setExpanded(expandedSections, "键位文字", on) },
            key = "键位文字",
            title = "键位文字",
        ) {

        item {
            SettingsCard {
                KeyMappingEditor(
                    mappings = editable.keyMappings,
                    onChange = { mappings -> applyChange { it.copy(keyMappings = mappings) } },
                    hiddenSlotIds = hiddenMappingSlots(editable),
                    /*
                     * ⚠️ 触屏**不显示**「绑定按键」那一行 —— 见上面那段说明。
                     */
                    showKeyBinding = false,
                )
            }
        }

        item {
            SectionHint(
                text = "这里只改每个位置**显示什么文字** —— " +
                    "悬浮窗上显示哪些位置是固定的。" +
                    "触屏不读物理按键，所以没有「绑定按键」这一项；" +
                    "手指按哪里算操作轮盘由「触屏设置」里的**触摸区域**决定。",
            )
        }

        } // ← 折叠结束：键位文字

        } else {

        collapsibleSection(
            expanded = "键位映射" in expandedSections,
            onExpandChange = { on -> expandedSections = setExpanded(expandedSections, "键位映射", on) },
            key = "键位映射",
            title = "键位映射",
        ) {

        item {
            SettingsCard {
                KeyMappingEditor(
                    mappings = editable.keyMappings,
                    onChange = { mappings -> applyChange { it.copy(keyMappings = mappings) } },
                    hiddenSlotIds = hiddenMappingSlots(editable),
                )
            }
        }

        item {
            SectionHint(
                text = "悬浮窗显示哪些按键是固定的，这里只调整每个位置「显示什么文字」" +
                    "和「绑定哪些物理键」。一个位置可以绑定多个键（例如 Shift 可同时绑左右 Shift）。" +
                    "按键使用 Linux 输入事件码：W=17、A=30、空格=57。",
            )
        }

        } // ← 折叠结束：键位映射

        } // ← 结束「非触屏」那一支（触屏用上面的「键位文字」）

        } // ← 结束"按键样式"分区；见上面按样式分流的注释

        /*
         * ============================================================
         * 危险操作（两种样式共用）
         * ============================================================
         */

        item { SectionHeader("其它") }

        item {
            SettingsCard {
                /*
                 * 转自定义。
                 *
                 * ⚠️ 只对**按键样式**显示：Live2D 是一只猫，它的配置里
                 * 没有任何与按键布局有关的东西，转过去会得到一个空布局；
                 * 已经是自定义 Key 的也没什么可转的。
                 *
                 * 内置配置（Default）显示但**不可点** —— 与"删除"同一个待遇。
                 * 它是"配置被删光"时的兜底，而转换不可逆，
                 * 一旦被转掉用户就失去了唯一能退回去的地方。
                 */
                if (editable.canConvertToCustom() || editable.builtIn) {
                    SettingItem(
                        title = "转为自定义 Key",
                        leadingIcon = Icons.Rounded.Transform,
                        subtitle = if (editable.builtIn) {
                            "内置配置不可转换（它是恢复用的兜底，且转换不可逆）"
                        } else {
                            "把当前布局摊开成一个个可单独编辑的组件"
                        },
                        onClick = if (editable.canConvertToCustom()) {
                            { showConvertDialog = true }
                        } else {
                            null
                        },
                    )
                    CardDivider()
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { showResetDialog = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.RestartAlt, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("重置参数")
                    }

                    OutlinedButton(
                        onClick = { showDeleteDialog = true },
                        modifier = Modifier.weight(1f),
                        enabled = !editable.builtIn,
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("删除")
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }

    /*
     * ============================================================
     * Live2D 模型选择器
     * ============================================================
     */

    if (showModelPicker) {
        Live2DModelPickerDialog(
            selectedId = editable.live2d.modelId,
            onSelect = { id ->
                applyChange { it.copy(live2d = it.live2d.copy(modelId = id)) }
            },
            onLibraryChanged = { modelRevision++ },
            onDismiss = { showModelPicker = false },
        )
    }

    /*
     * ============================================================
     * 对话框
     * ============================================================
     */

    if (showConvertDialog) {
        ConvertToCustomDialog(
            onDismiss = { showConvertDialog = false },
            onConfirm = {
                val (converted, notes) = editable.toCustomKeyStyle()
                applyChange { converted }
                showConvertDialog = false
                notes.forEach { AppLog.i("ConfigEditor", "转自定义：$it") }
                // 转完顺手刷新一下能力/预览：样式变了，界面要跟着换一套
                viewModel.refreshCapabilities()
            },
        )
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("放弃未保存的修改？") },
            text = { Text("当前处于「保存生效」模式，直接返回会丢弃本次改动。") },
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

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("重置为默认参数？") },
            text = { Text("会把该配置的参数恢复为默认值，配置名称与样式保持不变。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        /*
                         * 重置走的是**领域层那一个实现**（resetParamsToDefault）。
                         *
                         * 这里原本内联了一整份 copy(...)，和 ConfigStore 里那份重复；
                         * 两份漂移的结果是"重置参数会把 Live2D 配置变回按键样式"，
                         * 而且 live2d 设置根本重置不掉。不要再把它写回来。
                         */
                        applyChange { it.resetParamsToDefault() }
                    },
                ) {
                    Text("重置")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("取消") }
            },
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除配置？") },
            text = { Text("将删除「${editable.name}」，此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deleteConfig(editable.id)
                        onBack()
                    },
                    enabled = !editable.builtIn,
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            },
        )
    }

    /* ---------------- 字体选择 ---------------- */

    if (showFontPicker) {
        FontPickerDialog(
            selectedId = editable.fontId,
            onSelect = { fontId -> applyChange { it.copy(fontId = fontId) } },
            onDismiss = { showFontPicker = false },
            refreshKey = fontRevision,
            onImport = {
                fontPicker.launch(
                    arrayOf(
                        "font/ttf",
                        "font/otf",
                        "font/ttc",
                        "application/x-font-ttf",
                        "application/octet-stream",
                    ),
                )
            },
        )
    }

    /*
     * 图片字体是**另一个对话框**（常规字体必选 + 图片字体可选），
     * 所以这里是独立的一段，而不是上面那个对话框里的第二个导入按钮。
     */
    if (showBitmapFontPicker) {
        BitmapFontPickerDialog(
            selectedId = editable.bitmapFontId,
            onSelect = { fontId -> applyChange { it.copy(bitmapFontId = fontId) } },
            onDismiss = { showBitmapFontPicker = false },
            /*
             * ⚠️ 这里**不关**对话框：图片字体导入后弹的是我们自己的命名
             * 对话框，选择窗口留着才能让用户看到新条目。
             * 系统那次 dismiss 由 `bitmapFontPicker` 回调里那句
             * `showBitmapFontPicker = true` 抵消。
             */
            onImport = {
                bitmapFontPicker.launch(
                    arrayOf(
                        /*
                         * MIME 要同时覆盖"图片"与"压缩包"。
                         *
                         * ⚠️ 不要把通配符 MIME 原样写进块注释：Kotlin 的块
                         * 注释是**可嵌套**的，注释里出现"斜杠 + 星号"会被当成
                         * 嵌套注释的开始，而闭合只有一层 —— 整段注释永不闭合，
                         * 后面代码全被吞掉，报出来的却是别的函数"未解析"。
                         */
                        "image/*",                 // 直接选 ascii.png
                        "application/zip",         // .zip 与 .mcpack（后者就是 zip）
                        "application/x-zip-compressed",
                        "application/octet-stream", // 有些 ROM 只认这个，否则 .mcpack 选不了
                    ),
                )
            },
            refreshKey = fontRevision,
        )
    }

    /*
     * 每个键各自的文字偏移（独立窗口，见分区的说明）。
     *
     * 放在这里而不是分区内部：它和"字体选择"一样是**点开才出现**的东西，
     * 挂在页面末尾的对话框区，与其它对话框在一起，不会被 LazyColumn
     * 的回收影响（放进 item 里的话滚出屏幕就可能被销毁重建）。
     */
    if (showSlotOffsetDialog) {
        SlotTextOffsetDialog(
            config = editable,
            offsets = editable.slotTextOffsets,
            onOffsetsChange = { offsets -> applyChange { it.copy(slotTextOffsets = offsets) } },
            onDismiss = { showSlotOffsetDialog = false },
        )
    }

    /*
     * ⚠️ 这个块曾经**漏了** —— 按钮把 `showSlotSpacingDialog` 置了 true，
     * 但没有任何地方读它，于是"点了没反应"。
     *
     * 两个对话框的写法必须**成对**：加一个"打开按钮"就要同时加渲染块。
     * 这类漏写在编译期看不出来（`showXxx` 是个合法的局部 var），
     * 所以以后加这类窗口时，改完顺手搜一下 `showXxx` 出现几次 ——
     * 只有两次（声明 + 置 true）就是漏了渲染。
     */
    if (showSlotSpacingDialog) {
        SlotTextSpacingDialog(
            config = editable,
            spacings = editable.slotTextSpacings,
            onSpacingsChange = { spacings -> applyChange { it.copy(slotTextSpacings = spacings) } },
            onDismiss = { showSlotSpacingDialog = false },
        )
    }

    /* ---------------- 一次性提示 ---------------- */

    toast?.let { message ->
        LaunchedEffect(message) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            toast = null
        }
    }
}

/*
 * ============================================================
 * Live2D 样式的分区
 * ============================================================
 * 只有三项：整体缩放、透明度、右侧显示什么。
 *
 * 这不是"暂时只做这些"，而是**这里是全部** —— Live2D 的画面由
 * assets/bongocat 里的 HTML/JS 渲染，安卓侧能调的只有这些东西。
 * 按键样式那一整套（颜色/描边/圆角/动画/CPS/键位映射/字体）对它完全无效，
 * 所以一个都不显示。
 *
 * 写成 LazyListScope 的扩展函数而不是塞回主函数里：主函数已经 900 多行，
 * 再插一段进去会把"按样式分流"这个结构淹掉。
 */
private fun LazyListScope.live2DStyleSections(
    editable: KeyStrokesConfig,
    modelName: String,
    onPickModel: () -> Unit,
    applyChange: ((KeyStrokesConfig) -> KeyStrokesConfig) -> Unit,
) {
    item { SectionHeader("外观") }

    item {
        SettingsCard {
            SliderRow(
                label = "整体缩放",
                value = editable.scalePercent.toFloat(),
                valueRange = KeyLayout.SCALE_PERCENT_MIN.toFloat()..
                    KeyLayout.SCALE_PERCENT_MAX.toFloat(),
                steps = SCALE_SLIDER_STEPS,
                display = "${editable.scalePercent}%",
                onValueChange = { value ->
                    applyChange { it.copy(scalePercent = value.toInt()) }
                },
            )
        }
    }

    item { SectionHeader("透明度") }

    item {
        SettingsCard {
            SliderRow(
                label = "模型",
                value = editable.live2d.opacityPercent.toFloat(),
                // 与按键样式一样有 20% 下限：调成 0 就是"悬浮窗开着却什么都看不见"，
                // 用户只会以为是坏了
                valueRange = 20f..100f,
                steps = 15,
                display = "${editable.live2d.opacityPercent}%",
                onValueChange = { value ->
                    applyChange {
                        it.copy(live2d = it.live2d.copy(opacityPercent = value.toInt()))
                    }
                },
            )
        }
    }

    item { SectionHeader("键盘猫") }

    item {
        SettingsCard {
            /*
             * 模型选择。
             *
             * 这里**不再有**"右侧显示方向键还是鼠标"的开关：那两个内置模型
             * 就是那两页，它们的副标题已经写明"右侧显示方向键/鼠标"。
             * 再加一个布尔开关就会和 modelId 打架。
             */
            SettingItem(
                title = "模型",
                leadingIcon = Icons.Rounded.ViewInAr,
                subtitle = modelName,
                onClick = onPickModel,
            )
        }
    }

    item {
        SectionHint(
            text = "切换模型会重新加载一次悬浮窗页面，需要几百毫秒。" +
                "颜色、键位映射、字体等属于「Key」样式的设置，在 Live2D 下不显示也不生效。",
        )
    }
}

/**
 * CPS 文本模板输入框。
 *
 * 占位符是普通文字（可以删、可以挪位置），因为玩家习惯不同：
 * 有人要 `CPS: (cps)`，有人要 `(cps) CPS`。
 * 因此这里**不做**"占位符不可删除"的限制。
 */
@Composable
private fun CpsTemplateField(
    value: String,
    placeholder: String,
    hint: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        label = { Text("CPS 文本") },
        placeholder = { Text(placeholder) },
        supportingText = { Text(hint) },
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * 自定义 Key 样式的分区。
 *
 * ============================================================
 * 这里**只有"进入编辑器"这一个入口**
 * ============================================================
 * 自定义 Key 的设置项全部挂在组件上（每个组件各有颜色/透明度/描边/圆角/字体/
 * 动画/键位），没有办法在这个滚动页里平铺出来 —— 那样会变成几百行控件，
 * 而且与"画布上选中哪个组件"这个上下文强耦合。
 *
 * 所以中间只留一个按钮，真正的编辑在独立页面里做（[CustomLayoutEditorScreen]）。
 * 名称/描述/预览在主函数里（两种样式共用），重置/删除在它下面（也共用）。
 */
private fun LazyListScope.customKeyStyleSections(
    editable: KeyStrokesConfig,
    onOpenEditor: () -> Unit,
    onOpenRegionEditor: () -> Unit,
    applyChange: ((KeyStrokesConfig) -> KeyStrokesConfig) -> Unit,
) {
    item { SectionHeader("外观") }

    item {
        SettingsCard {
            /*
             * 只留"整体缩放"与"整体透明度"。
             *
             * 组件自己的大小在编辑器里逐个调；这两个滑块管的是**整块画布**，
             * 与其它样式含义一致：整体缩放把内容与窗口一起放大。
             */
            SliderRow(
                label = "整体缩放",
                value = editable.scalePercent.toFloat(),
                valueRange = KeyLayout.SCALE_PERCENT_MIN.toFloat()..
                    KeyLayout.SCALE_PERCENT_MAX.toFloat(),
                steps = SCALE_SLIDER_STEPS,
                display = "${editable.scalePercent}%",
                onValueChange = { value ->
                    applyChange { it.copy(scalePercent = value.toInt()) }
                },
            )

            CardDivider()

            /*
             * ============================================================
             * 整体透明度
             * ============================================================
             * 用户的原话:"自定义 key 的配置编辑页加一个透明度调整
             * （控制整体透明度），不碰自定义编辑页"。
             *
             * ⚠️ 它是**整块画布**一起淡（所有组件），与 Live2D 那个
             * "模型透明度"是同一种语义 —— 两个样式都只有整体一项。
             *
             * ⚠️ 这里**不提供**按键样式那种按元素分的八个滑块:
             * 自定义 Key 的组件样式是每个组件自己的，在编辑器里逐个调，
             * 全局再放八个只会让人分不清谁盖过谁。
             *
             * ⚠️ 下限 0（允许完全隐去）:与按键样式的透明度一致 ——
             * 有独立滑块之后，"我不想要它"是合理需求。
             *
             * ⚠️ 渲染端用**一层 `graphicsLayer` 的整体 alpha**，不是把
             * alpha 乘进每个组件的颜色 —— 见 `CustomKeyCanvas` 的说明。
             */
            SliderRow(
                label = "整体透明度",
                value = editable.customOpacityPercent.toFloat(),
                valueRange = 0f..100f,
                steps = 19,
                display = "${editable.customOpacityPercent}%",
                onValueChange = { value ->
                    applyChange { it.copy(customOpacityPercent = value.toInt()) }
                },
            )
        }
    }

    item { SectionHeader("自定义编辑") }

    item {
        SettingsCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "组件：${editable.custom.components.size} 个" +
                        "（按键 ${editable.custom.components.count { it is KeyComponent }} · " +
                        "文本 ${editable.custom.components.count { it is TextComponent }}）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onOpenEditor,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.DashboardCustomize, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("自定义编辑")
                }
            }
        }
    }

    item {
        SectionHint(
            text = "在编辑器里摆放按键与文本组件：用 X / Y 滑块定位、逐个调外观与尺寸。" +
                "编辑过程中不会改动配置，点保存才写入。",
        )
    }

    /*
     * ============================================================
     * 触屏设置（自定义 Key）
     * ============================================================
     * 用户的原话:
     *
     * > 在**自定义 key 的编辑配置页**（不是自定义编辑页），加一个"触屏设置"栏，
     * > 这里面加一个 button"触摸区域编辑"，**是的，就跟触屏样式是一样的**，
     * > 但是**实际操作并不一样**
     *
     * ⚠️ "实际操作不一样"具体不一样在哪:
     *
     * | | 触屏样式 | 自定义 Key |
     * |---|---|---|
     * | 区域从哪来 | ⚠️ 由**槽位**决定（LMB/SPACE…一个不多一个不少） | ✅ 用户**自己加**（轮盘/按键两类） |
     * | 能不能删 | ❌ 不能 | ✅ 每块旁边有 ✕ |
     * | 绑定 | 天然对应那个槽位 | ✅ 要**手动选**绑哪些组件 |
     *
     * ⚠️ 而**面板本身是同一个**（服务里按样式分流，见 `buildCustomStyleSlots`）。
     *
     * ⚠️ 位置在自定义编辑**下面** —— 用户的原话是"加一个触屏设置栏"，
     * 而"先摆组件、再配触屏"才是自然的顺序（区域绑的就是那些组件）。
     */
    item { SectionHeader("触屏设置") }

    item {
        SettingsCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    /*
                     * ⚠️ 把"区域数"摆出来 —— 用户最需要知道的一件事是
                     * **这个配置到底吃不吃触摸**。
                     *
                     * ⚠️ 0 块时要说清后果:触摸采集**根本不会启动**
                     * （见 `usesTouchCapture`），所以纯键盘/手柄用户
                     * 不会被白白挂上一条采集线程。
                     */
                    text = if (editable.custom.touchRegions.isEmpty()) {
                        "触摸区域：还没有（这个配置不会监听触摸 —— " +
                            "用键盘/手柄的话正好，不会多占一份采集）"
                    } else {
                        val move = editable.custom.touchRegions
                            .count { it.kind == CustomTouchRegion.Kind.MOVE_STICK }
                        val button = editable.custom.touchRegions.size - move
                        "触摸区域：${editable.custom.touchRegions.size} 块" +
                            "（轮盘 $move · 按键 $button）· " +
                            "已绑定 ${editable.custom.touchRegions.count { it.bindings.isNotEmpty() }} 块"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onOpenRegionEditor,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.TouchApp, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("触摸区域编辑")
                }
            }
        }
    }

    item {
        SectionHint(
            text = "打开后是一个悬浮在所有应用之上的编辑面板：点左下角「＋ 添加触摸区域」" +
                "加一块（轮盘或按键），每块旁边可以「绑定」组件、也可以删掉。" +
                "拖动方块调位置、下面的滑块调边长，点「保存并退出」才写回配置。",
        )
    }
}

/**
 * 「转为自定义 Key」的确认弹窗。
 *
 * ============================================================
 * 为什么需要它（这不是走个形式的确认）
 * ============================================================
 * 这个操作**不可逆**：转完之后 `styleId` 就变了，编辑页换成自定义 Key
 * 那一套，「显示 Shift」「显示鼠标键」「CPS 模式」这些开关**连界面都没有了**，
 * 没有"转回去"的按钮。所以必须让用户在动手之前就明白自己换到了什么、
 * 失去了什么。
 *
 * ============================================================
 * ⚠️ 确认按钮延迟一秒才可点（需求指定）
 * ============================================================
 * 目的是**防误触**：这个弹窗是从列表式的设置项点出来的，
 * 手指还停在屏幕上时很容易顺手再点一下确认。
 *
 * 一秒是权衡过的：
 * - 太短（比如 200ms）手指还没抬起来，等于没防；
 * - 太长（3 秒以上）每次都要等，影响正常使用效率。
 *
 * 按钮上会**显示倒计时**而不是只置灰：置灰而不说明原因，
 * 用户会以为按钮坏了、或者以为这个功能点不动。
 */
@Composable
private fun ConvertToCustomDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var armed by remember { mutableStateOf(false) }

    // 弹窗出现后开始计时；离开时协程随作用域取消，不需要手动清理
    LaunchedEffect(Unit) {
        delay(CONVERT_CONFIRM_DELAY_MS)
        armed = true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("转为自定义 Key 样式？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "转换后这份配置会变成「自定义 Key」类型，" +
                        "现有按键会摊开成一个个可以单独编辑的组件。",
                )
                Text(
                    text = "你会得到：每个按键的位置、大小、颜色、透明度、" +
                        "描边与阴影都能分别调整，也可以任意增删组件。",
                )
                Text(
                    text = "你会失去：显示 Shift、显示鼠标键、CPS 模式这些" +
                        "专用开关，以及「一处改、全部生效」的统一调整方式 —— " +
                        "之后改颜色要一个组件一个组件地改。",
                )
                Text(
                    text = "此操作不可逆，转换后无法变回按键样式。",
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "建议先复制一份这份配置作为备份。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = armed,
            ) {
                Text(if (armed) "确定转换" else "请稍候…")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 转换确认按钮的解锁延迟：够抬手、又不影响效率 */
private const val CONVERT_CONFIRM_DELAY_MS = 1_000L

/**
 * 带数值显示的滑块行。
 */
/**
 * 带数值显示的滑块行，**数值可点开直接输入**。
 *
 * 实现已搬到共用的 [EditableSliderRow]（`ui/component/EditableSliderRow.kt`）——
 * 与自定义编辑页共用同一份，"点数值改精确值"对两边同时生效。
 */
@Composable
internal fun SliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    display: String,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    /**
     * **编辑框专用**的取值:显示单位下的 `(当前值, 合法范围)`。
     *
     * ============================================================
     * ⚠️⚠️ 只有"显示单位 ≠ 存储单位"的字段才需要它
     * ============================================================
     * 绝大多数滑块不用传:它们**存的就是显示的那个数**
     * （按键组件的 `cornerRadiusPercent` 存 `16`、显示 `16%`），
     * 滑块与输入框天然一致。
     *
     * ⚠️ 但摇杆那组字段存的是 **`0..1` 的比例**（`cornerRatio` / `opacity` …），
     * 而界面按**百分比**显示 —— 默认行为下:
     *
     * | 位置 | 显示 |
     * |---|---|
     * | 滑块旁边 | `45%` |
     * | **点开输入框** | **`0.225`** ← 用户报的"不明不白"就是这个 |
     *
     * 传了它之后，输入框按显示单位工作:填 `45`、范围 `0..50`、
     * 粒度也按 `0..50` 算，与滑块旁边的 `45%` 完全对得上。
     *
     * ⚠️ **值与范围打包成一个参数**（而不是两个可选参数）是刻意的:
     * 分开传就会有人只给值不给范围，那时输入框会拿"显示单位的数字"
     * 去比"比例的范围"，合法的 `45` 被标红成越界 ——
     * 一个很难联想到原因的 bug。
     */
    edit: EditValue? = null,
) {
    EditableSliderRow(
        label = label,
        value = value,
        range = valueRange,
        display = display,
        onValueChange = onValueChange,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        steps = steps,
        editValue = edit?.value,
        /*
         * ⚠️ 粒度按**输入框实际用的那个范围**算 ——
         * 用比例算的话 `0..0.5` 会给出 `0.01` 的粒度，
         * 而输入框里其实是 `0..50` 的整数。
         */
        step = stepOf(edit?.range ?: valueRange),
        labelStyle = MaterialTheme.typography.bodyLarge,
        valueStyle = MaterialTheme.typography.labelLarge,
    )
}

/**
 * 编辑框的"显示单位"取值 —— 见 [SliderRow] 的 `edit` 参数。
 *
 * ⚠️ 值与范围**必须一起给**:只给值不给范围会让输入框拿错范围去校验。
 */
internal data class EditValue(
    val value: Float,
    val range: ClosedFloatingPointRange<Float>,
)

/**
 * 文字偏移的取值上下限。
 *
 * ⚠️ **与自定义 Key 的文字偏移完全一致**（那边是 `range = -60f..60f`）。
 * 两边不一致的话，同一个视觉位置在两个页面里对应的数值不同，
 * 互相转换时位置就会跑偏 —— 而"转换后位置没变"是这个功能的基线要求。
 */
/* 字间距 / 行间距的取值上下限（**相对字号的百分比**） */
private const val SPACING_MIN = -50f
private const val LETTER_SPACING_MAX = 100f
private const val LINE_SPACING_MAX = 200f

private const val TEXT_OFFSET_MIN = -60f
private const val TEXT_OFFSET_MAX = 60f
/**
 * 「整体缩放」滑块的**分档数**（`Slider` 的 `steps` 参数）。
 *
 * ============================================================
 * ⚠️ 现在是 **1% 一档**（这个值改过两次）
 * ============================================================
 * `steps` 是"档位之间的间隔数"，实际可停靠的位置有 `steps + 1` 个。
 * 想让跨度里每 X% 一档，就是 `(跨度 / X) - 1`。
 *
 * | 版本 | 范围 | 档位 | 问题 |
 * |---|---|---|---|
 * | 最早 | 50..200 | 写死 `14` | 下限改成 20 之后档位不对齐（显示 20%、实际停 21） |
 * | 上一次 | 20..200 | 10% | 跨度 180 → 17 档，整齐 |
 * | **现在** | **1..300** | **1%** | 跨度 299，**除不尽 10** |
 *
 * ⚠️ 语义改成"百分比 = 倍率"之后（见 [KeyLayout.SCALE_PERCENT_MIN]），
 * 跨度是 299，`/10` 会得到 28.9 这种非整数 —— `Slider` 的档位落点会带上小数，
 * 显示与实际停靠值对不上，正是最早那版的老毛病。
 *
 * 取 1% 一档既整齐，又能在小倍率区间给出**可用**的精度
 * （1% 与 2% 是相邻两档，差别正好是 2 倍）。要精确值可以点数字直接输入。
 */
private val SCALE_SLIDER_STEPS = KeyLayout.SCALE_PERCENT_MAX - KeyLayout.SCALE_PERCENT_MIN - 1

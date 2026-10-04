package com.something.sthkey.ui.feature.config

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DashboardCustomize
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RestartAlt
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.something.sthkey.domain.config.ANIMATION_DURATION_MAX
import com.something.sthkey.domain.config.ANIMATION_DURATION_MIN
import com.something.sthkey.domain.config.AnimationMode
import com.something.sthkey.domain.config.DEFAULT_CPS_TEMPLATE
import com.something.sthkey.domain.config.DEFAULT_CPS_TEMPLATE_MODE1
import com.something.sthkey.domain.config.KeyStrokesConfig
import com.something.sthkey.domain.config.ShadowMode
import com.something.sthkey.domain.config.TextShadow
import androidx.compose.ui.text.font.FontWeight
import com.something.sthkey.core.log.AppLog
import com.something.sthkey.domain.custom.KeyComponent
import com.something.sthkey.domain.custom.TextComponent
import com.something.sthkey.domain.custom.canConvertToCustom
import com.something.sthkey.domain.custom.toCustomKeyStyle
import kotlinx.coroutines.delay
import com.something.sthkey.domain.config.resetParamsToDefault
import com.something.sthkey.domain.font.FontRegistry
import com.something.sthkey.domain.style.KeyLayout
import kotlin.math.roundToInt
import com.something.sthkey.domain.style.StyleId
import com.something.sthkey.domain.live2d.Live2DModels
import com.something.sthkey.ui.component.Live2DModelPickerDialog
import com.something.sthkey.ui.EditMode
import com.something.sthkey.ui.MainViewModel
import com.something.sthkey.ui.component.CardDivider
import com.something.sthkey.domain.font.bitmap.BitmapFontImporter
import com.something.sthkey.ui.component.BitmapFontPickerDialog
import com.something.sthkey.ui.component.PendingBitmapImportDialog
import com.something.sthkey.ui.component.FontPickerDialog
import com.something.sthkey.ui.component.HexColorRow
import com.something.sthkey.ui.component.KeyMappingEditor
import com.something.sthkey.ui.component.ScrollableScreen
import com.something.sthkey.ui.component.EditableSliderRow
import com.something.sthkey.ui.component.SectionHeader
import com.something.sthkey.ui.feature.custom.stepOf
import com.something.sthkey.ui.component.SectionHint
import com.something.sthkey.ui.component.SettingItem
import com.something.sthkey.ui.component.SettingsCard
import com.something.sthkey.ui.component.SlotTextOffsetDialog
import com.something.sthkey.ui.component.SlotTextSpacingDialog
import com.something.sthkey.ui.component.SwitchItem
import com.something.sthkey.ui.preview.ConfigPreview
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
            ) { transform -> applyChange(transform) }
        } else {

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

        item { SectionHeader("外观") }

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
                    valueRange = KEY_HEIGHT_PERCENT_MIN..KEY_HEIGHT_PERCENT_MAX,
                    display = "${editable.keyHeightPercent}%",
                    onValueChange = { value ->
                        applyChange { it.copy(keyHeightPercent = value.roundToInt()) }
                    },
                )

                CardDivider()

                SliderRow(
                    label = "按键间距",
                    value = editable.keyGapPercent.toFloat(),
                    valueRange = KEY_GAP_PERCENT_MIN..KEY_GAP_PERCENT_MAX,
                    display = "${editable.keyGapPercent}%",
                    onValueChange = { value ->
                        applyChange { it.copy(keyGapPercent = value.roundToInt()) }
                    },
                )

                CardDivider()

                SwitchItem(
                    title = "圆角",
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
                    subtitle = if (editable.bitmapFontId.isBlank()) {
                        "不使用（只有 ASCII 会用到它）"
                    } else {
                        FontRegistry.displayNameOf(editable.bitmapFontId)
                    },
                    onClick = { showBitmapFontPicker = true },
                )
            }
        }

        /*
         * ============================================================
         * 颜色（只有 6 位 RGB，透明度在下一分区单独调）
         *
         * 顺序按需求定：键帽 → 文字 → 描边 → 阴影，每对都是"未按下、按下"。
         * 四个元素的结构完全一样，扫一眼就知道规律。
         * 描边与阴影那两对**只在功能开启时出现** —— 没开的时候调颜色没有意义。
         * ============================================================
         */

        item { SectionHeader("颜色") }

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

        item { SectionHeader("透明度") }

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

        /*
         * ============================================================
         * 行为
         * ============================================================
         */

        item { SectionHeader("行为") }

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
                     * ⚠️ 手柄样式下这个槽位是 **B 键**（`BTN_EAST`），不是 Shift ——
                     * 布局用的是键盘布局，但键位映射换过了（见 `GamepadBindings`）。
                     *
                     * 标题不跟着变的话，手柄用户会看到一个"显示 Shift 键"的开关，
                     * 而悬浮窗上那个位置明明写着 B —— 没人能猜到那是一回事。
                     */
                    title = if (KeyLayout.usesJoystickLayout(editable)) {
                        "显示 B 键"
                    } else {
                        "显示 Shift 键"
                    },
                    checked = editable.showShiftKey,
                    onCheckedChange = { enabled -> applyChange { it.copy(showShiftKey = enabled) } },
                )

                CardDivider()

                SwitchItem(
                    title = "显示鼠标按键",
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
                if (KeyLayout.usesJoystickLayout(editable)) {
                    SwitchItem(
                        title = "显示肩键",
                        subtitle = "LB / RB，显示在 LT / RT 那一行下面",
                        checked = editable.showShoulderButtons,
                        onCheckedChange = { enabled ->
                            applyChange { it.copy(showShoulderButtons = enabled) }
                        },
                    )

                    CardDivider()

                    SwitchItem(
                        title = "显示 A 键",
                        subtitle = "独占一行，横跨两列",
                        checked = editable.showAButton,
                        onCheckedChange = { enabled ->
                            applyChange { it.copy(showAButton = enabled) }
                        },
                    )

                    CardDivider()

                    SwitchItem(
                        title = "A 键置顶",
                        subtitle = "把 A 键那一行提到摇杆正下方（LT / RT 之上）",
                        checked = editable.aButtonOnTop,
                        /* 不显示 A 键时"置顶"没有意义 */
                        enabled = editable.showAButton,
                        onCheckedChange = { enabled ->
                            applyChange { it.copy(aButtonOnTop = enabled) }
                        },
                    )

                    CardDivider()

                    SwitchItem(
                        title = "摇杆互换",
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
                    subtitle = if (KeyLayout.usesJoystickLayout(editable)) {
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

        /*
         * ============================================================
         * 摇杆的专属分区（**只有手柄样式有**）
         * ============================================================
         * 用户的原话:"摇杆相关配置可以单独拉出来，不跟其他按键共同配置"，
         * 位置指定为**「行为」与「键位映射」之间**。
         *
         * ⚠️ 放在 `} else {` **内部**:它属于"按键样式"这一套分区的一部分
         * （手柄样式本来就是按键布局改了 WASD 那两块），
         * 因此与"外观/颜色/行为"是并列的分区，顺序就按用户指定的位置。
         *
         * ⚠️ 内容在 `JoystickSections.kt`（本文件已经 1800 多行，
         * 不再往里塞新东西）。
         */
        if (KeyLayout.usesJoystickLayout(editable)) {
            joystickStyleSections(editable) { transform -> applyChange(transform) }
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

        item { SectionHeader("文字偏移") }

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

        item { SectionHeader("字间距与行间距") }

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

        /*
         * ============================================================
         * 键位映射
         * ============================================================
         */

        item { SectionHeader("键位映射") }

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
) {
    EditableSliderRow(
        label = label,
        value = value,
        range = valueRange,
        display = display,
        onValueChange = onValueChange,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        steps = steps,
        step = stepOf(valueRange),
        labelStyle = MaterialTheme.typography.bodyLarge,
        valueStyle = MaterialTheme.typography.labelLarge,
    )
}

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
 * `steps` 是"档位之间的间隔数"，即实际可停靠的位置有 `steps + 1` 个。
 * 想让用户按 **10%** 一档地调，就是 `(跨度 / 10) - 1`。
 *
 * ⚠️ 必须与 [KeyLayout.SCALE_PERCENT_MIN]/[KeyLayout.SCALE_PERCENT_MAX]
 * **联动推导**，不要写死 —— 早先写死 `14`（对应旧的 50..200），
 * 下限改成 20 之后档位就不对齐了：滑到最左边不是整数，
 * 显示出来是 `20%` 但实际停靠值是 21、19 这种。
 */
private val SCALE_SLIDER_STEPS =
    (KeyLayout.SCALE_PERCENT_MAX - KeyLayout.SCALE_PERCENT_MIN) / 10 - 1
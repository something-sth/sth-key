package com.something.sthkey.domain.custom

import com.something.sthkey.domain.config.AnimationMode
import com.something.sthkey.domain.config.ShadowMode
import com.something.sthkey.domain.keys.KeyCodes

/**
 * 自定义 Key 样式的**组件模型**。
 *
 * ============================================================
 * 这是什么
 * ============================================================
 * 别的样式把"窗口里有什么"写死在代码里（Key 样式是 WASD + 鼠标键 + CPS 行，
 * Live2D 是一只猫）。自定义 Key 反过来：**窗口里的一切都由用户摆放的组件决定**。
 *
 * 目前只有两种组件：
 * - [KeyComponent]：监听按键，按下时变色 —— 这才是"按键显示"；
 * - [TextComponent]：只显示一段文字，可以当水印，也可以单独显示 CPS。
 *
 * 两者共用 [ComponentStyle]（颜色/透明度/描边/圆角/字体），
 * 各自只加自己特有的东西 —— 这样以后加"图片组件"时，
 * 主题那部分不用再重复一遍。
 *
 * ============================================================
 * CPS 是怎么显示的（这里换过一次方案）
 * ============================================================
 * 早先每个组件带一个 `cps: CpsBinding`，配一个"显示 CPS"开关，
 * 打开后才渲染第二行。那有三个问题：
 *
 * 1. **开关是多余的**：CPS 本来就是"文字里写不写占位符"这件事，
 *    再给一个开关就是同一个决定有两个入口；
 * 2. **"显示哪一项"选错了就是一片空白**：文本组件绑定没配好的话，
 *    用户看到的是一个空的第二行，完全不知道哪里不对；
 * 3. **两种组件的规则还不同**，渲染与诊断代码到处 `when`。
 *
 * 现在改成与 Key 样式**完全一致**的做法（那边就是这么干的）：
 *
 * > **文字里有 `(cps)` 或 `(cps2)` 就显示 CPS，没有就不显示。**
 * > `(cps)` 为 0 时显示 `0`；`(cps2)` 为 0 时**整段连同数字一起消失**
 * > （所以键盘类按键通常写成 `LMB(cps2)` —— 观感就是键名后面跟一个数字）。
 *
 * 于是"显示 CPS"不再是一个字段，而是**文字内容本身**。
 * 对应的取值来源，两种组件各有各的天然答案：
 *
 * - [KeyComponent]：**就用这个组件监听的按键**（它已经映射好了）——
 *   不需要再选一次，这正是需求里说的"直接采用这个组件映射的按键"；
 * - [TextComponent]：它没有映射，所以有 [TextComponent.cpsKeyCode] 可选。
 *
 * ============================================================
 * 坐标系（重要）
 * ============================================================
 * 所有坐标与尺寸都是**基础坐标系**里的值（单位与 [com.something.sthkey.domain.style.KeyLayout]
 * 的 BASE_WIDTH 体系一致：基础像素）。整体缩放由 `KeyStrokesConfig.scalePercent`
 * 统一乘上去，组件本身**不感知缩放** —— 否则"改整体缩放"会把每个组件的值都改一遍。
 *
 * [CustomComponent.x] / [CustomComponent.y] 是组件**边框左上角**在画布里的位置。
 * 画布是固定的 600×600（见 [CustomLayout.BASE_CANVAS]）。
 */
sealed interface CustomComponent {
    /** 稳定标识；每次编辑都靠它定位是哪一个组件 */
    val id: String

    /** 边框左上角 X（基础坐标） */
    val x: Float

    /** 边框左上角 Y（基础坐标） */
    val y: Float

    /** 边框宽度（基础坐标）—— 它才是这个元件占的地方，文字只是画在里面 */
    val width: Float

    /** 边框高度（基础坐标） */
    val height: Float

    /**
     * 文字缩放百分比 50..150。
     *
     * 提到接口上是因为**两种组件都有文字**（Key 有键面文字、Text 有内容），
     * 渲染层要统一处理它，不该在每次取值时 `when` 一遍类型。
     */
    val textScalePercent: Int

    /** 主题（颜色 / 透明度 / 描边 / 圆角 / 字体） */
    val style: ComponentStyle

    /** 文字在边框内的偏移（基础坐标）；只挪文字，不影响边框与吸附 */
    val textOffsetX: Float
    val textOffsetY: Float
}

/**
 * 键帽 + 文字的外观（两种组件共用）。
 *
 * ============================================================
 * 为什么颜色和透明度分开存
 * ============================================================
 * 与 [com.something.sthkey.domain.config.ColorSet] 同一个理由：
 * 颜色只存 24 位 RGB、透明度单独存百分比，各自只有一个真源。
 * 混在一个 ARGB 里的话，"改颜色把透明度也改了"这种耦合迟早出现。
 */
data class ComponentStyle(
    /** 未按下时的底色 */
    val fillUp: Long = 0x000000,

    /** 按下时的底色（只有 Key 组件用得到；文本组件忽略它） */
    val fillDown: Long = 0xFFFFFF,

    /** 未按下时的文字颜色 */
    val textUp: Long = 0xFFFFFF,

    /** 按下时的文字颜色（只有 Key 组件用得到） */
    val textDown: Long = 0x000000,

    /** 键帽不透明度百分比 */
    val fillOpacityUp: Int = 70,

    /**
     * 按下时的键帽不透明度百分比。
     *
     * 与 [fillOpacityUp] 分开是需求里点名的"透明度区分（按下与未按下）"。
     */
    val fillOpacityDown: Int = 100,

    /** 文字不透明度百分比 */
    val textOpacityUp: Int = 100,

    /** 按下时的文字不透明度百分比 */
    val textOpacityDown: Int = 100,

    /*
     * ============================================================
     * 描边与文字阴影
     *
     * 结构与 Key 样式那边**刻意保持一致**（颜色分按下/未按下、
     * 各有自己的不透明度），两个编辑器的分区与顺序因此可以完全对称 ——
     * 用户在两边之间切换时不需要重新学一遍。
     * ============================================================
     */

    /** 描边开关、粗细（dp） */
    val outlineEnabled: Boolean = false,
    val outlineWidth: Float = 2f,

    /**
     * 描边颜色（未按下 / 按下）。
     *
     * 默认跟随文字颜色：描边的作用是让文字在复杂背景上更清楚，
     * 跟着文字色是唯一说得通的默认值。
     */
    val outlineUp: Long = 0xFFFFFF,
    val outlineDown: Long = 0x000000,
    val outlineOpacityUp: Int = 100,
    val outlineOpacityDown: Int = 100,

    /** 文字阴影（柔光 / 硬阴影），语义与 Key 样式完全一致 */
    val shadowEnabled: Boolean = false,
    val shadowMode: ShadowMode = ShadowMode.SOFT,
    /** 柔光 = 模糊半径；硬阴影 = 偏移距离。单位 dp（基础坐标） */
    val shadowSize: Float = 2f,
    val shadowUp: Long = 0xFFFFFF,
    val shadowDown: Long = 0x000000,
    val shadowOpacityUp: Int = 100,
    val shadowOpacityDown: Int = 100,

    /** 圆角开关与程度（0..50，按短边百分比；50 = 胶囊/圆形） */
    val cornerRadiusEnabled: Boolean = false,
    val cornerRadiusPercent: Float = 0f,

    /** 字体标识，规则与 [com.something.sthkey.domain.config.KeyStrokesConfig.fontId] 一致 */
    val fontId: String = DEFAULT_COMPONENT_FONT_ID,

    /**
     * 图片字体标识（可选）；空字符串表示不用。
     *
     * 与 [fontId] 是"**常规字体（必选）+ 图片字体（可选）**"的关系，
     * 不是二选一 —— 图片字体只有 ASCII 字形，中文要靠 [fontId] 兜底。
     * 字段名以 `fontId` 结尾是为了被配置包的 `ParamsTree` 自动收集，
     * 详见 `KeyStrokesConfig.bitmapFontId`。
     */
    val bitmapFontId: String = "",

    /**
     * 这个组件自己的**字间距 / 行间距**（相对字号的百分比，0 = 不动）。
     *
     * 放在**组件自己的样式**里、而不是像 Key 那样另开一张按槽位查的表：
     * 自定义 Key 的每个组件本来就是独立对象，"每个组件各自的间距"
     * 直接是一个字段就够，不必再查一次表。
     *
     * 图片字体的字形宽度与间距因图集而异（有的材质包字距很挤、有的很松），
     * 而 TTF 那套字距设置在图片字体上不起作用 —— 所以需要它。
     */
    val letterSpacing: Float = 0f,
    val lineSpacing: Float = 0f,
)

/**
 * 按键组件：监听按键、按下时变色。
 *
 * ============================================================
 * CPS 直接跟着"这个组件监听的键"走，没有单独的开关
 * ============================================================
 * Key 样式里 `LMB(cps2)` 这种写法就是模板：键名后面接一个占位符，
 * 数字来自**这个位置自己的键位映射**。这里照抄同一个思路 ——
 * [inputKeyCodes] 既是"监听哪些键"，也是"CPS 数哪个键"，
 * 用户不需要再选一遍（需求原话："Key 组件不需要额外的 cps 统计按键选择"）。
 *
 * 想显示 CPS 就把 [label] 写成 `LMB(cps2)`；不想显示就写 `LMB`。
 */
data class KeyComponent(
    override val id: String,
    override val x: Float,
    override val y: Float,
    override val width: Float,
    override val height: Float,
    override val style: ComponentStyle,

    /**
     * 键面文字，例如 `Q` / `SHIFT` / `LMB`。
     *
     * 它同时是 **CPS 模板**：可以写 `LMB(cps2)`。
     * 字段名沿用 `label`（JSON 里也是这个名字，改了会让老配置读不出来），
     * 但语义上是"这个组件要显示的文字"。
     */
    val label: String,

    /**
     * 监听哪些输入键码（evdev 码）。
     *
     * 是**列表**而不是单值，与 [com.something.sthkey.domain.config.KeyMapping] 同一个理由：
     * 左右 Shift 应该能一起亮。
     *
     * 它也是 CPS 的取值来源：列表里第一个键码所属的那个键位映射，
     * 就是这一行 CPS 数的对象（一个映射位置上的多个键**共享**计数，
     * 这正是映射的语义）。
     */
    val inputKeyCodes: List<Int>,

    override val textScalePercent: Int = 100,
    override val textOffsetX: Float = 0f,
    override val textOffsetY: Float = 0f,

    /**
     * CPS 那一行的**额外**缩放百分比（1..200）。
     *
     * ============================================================
     * 为什么单独一项，而不是把尺寸塞进文字内容里
     * ============================================================
     * 按键样式的 CPS 模式 3 是"键内两行"，而且**第二行是更小的字**
     * （主文字 28、CPS 22）。自定义组件只有 [textScalePercent] 一个缩放，
     * 两行会被画成一样大 —— 转换过去就与原样式不一致。
     *
     * 一开始想过在文字里写"字号标记"（类似 `{small}`），但那是把样式
     * 混进内容里：用户会看到、会误删、导出后还得解释。
     * 所以做成一个**独立字段**，只作用于含 CPS 占位符的那些行。
     *
     * 100 = 与主文字一样大。
     */
    val cpsTextScalePercent: Int = 100,

    /** 按下动画，语义与 Key 样式完全一致（见 [AnimationMode]） */
    val animationMode: AnimationMode = AnimationMode.DEFAULT,
    val animationDurationSec: Float = 0.1f,
) : CustomComponent

/**
 * 文本组件：只显示文字（可以当水印，也可以显示 CPS）。
 *
 * ============================================================
 * 它**也有键帽背景**
 * ============================================================
 * 早先这一块默认把底色调成全透明，于是文本组件在屏幕上就是一串"裸字" ——
 * 用户反馈"文本组件缺少键帽"，这是对的：能被描边、能被对齐、
 * 那它就是一个有边框的元件，外观上不该和按键组件是两种东西。
 *
 * 所以默认样式与按键组件完全一致（黑底白字 70% 不透明度），
 * 不想要背景就把不透明度拉到 0。**默认好看**比"默认特殊"重要。
 *
 * ============================================================
 * 它才需要选"CPS 统计哪些键"
 * ============================================================
 * 按键组件有 [KeyComponent.inputKeyCodes]，CPS 的来源是现成的；
 * 文本组件没有映射，所以要有 [cpsKeyCodes] 来指定。
 *
 * ⚠️ 这个选项**只在文字里含占位符时才出现在界面上** ——
 * 文字里没有 `(cps)` / `(cps2)` 的时候，选它也没有任何意义，
 * 摆在那里只会让人以为"这个开关坏了"。
 */
data class TextComponent(
    override val id: String,
    override val x: Float,
    override val y: Float,
    override val width: Float,
    override val height: Float,
    override val style: ComponentStyle,

    /**
     * 要显示的文字；含 `(cps)` / `(cps2)` 时才显示 CPS。
     *
     * 它同时是 CPS 模板 —— 这与 [KeyComponent.label] 是同一套规则。
     */
    val text: String,

    /**
     * CPS 统计**哪些键** —— **每个占位符一组**，按在文字里出现的先后对应。
     *
     * ============================================================
     * 为什么现在是"一组列表"而不是一个扁平列表
     * ============================================================
     * 早先这里是 `cpsKeyCodes: List<Int>`，一份配置对应整个组件。
     * 于是组件里写几个占位符，它们全都显示同一个数值 ——
     * 而实际需求是 `LMB(cps) | RMB(cps)` 这种要分别数左右键的写法。
     *
     * 形状变成 `List<List<Int>>` 之后，第 n 组就是第 n 个占位符的键位：
     * 外层下标 = 占位符序号，内层 = 那一处统计的键（仍然可多选，
     * 因为"左右 Shift 加起来"这种需求还在）。
     *
     * ⚠️ 空的**外层**项是有意义的：它表示"这一处不统计任何键"。
     * 所以读取时不能把空内层当成"没配置"而跳过。
     */
    val cpsKeyCodesPerPlaceholder: List<List<Int>> = listOf(DEFAULT_CPS_KEY_CODES),

    /**
     * @deprecated 老字段（扁平、整个组件共用一组键位）。
     *
     * 读老配置时用它回退：见 [CustomLayout.cpsKeyCodesAt]。
     * 新代码**不要**再往这里写 —— 两处都写就会出现"改了没生效"。
     * 保留它是为了让旧 JSON 能读出来，导出时也不再写它。
     */
    val cpsKeyCodes: List<Int> = DEFAULT_CPS_KEY_CODES,

    override val textScalePercent: Int = 100,
    override val textOffsetX: Float = 0f,
    override val textOffsetY: Float = 0f,
) : CustomComponent

/**
 * 文本组件 CPS 的默认取值：鼠标左键。
 *
 * 提成常量而不是直接写在 [TextComponent] 的默认值里：
 * 读配置时也要用它（老数据里没有这个字段），两处写同一个数是迟早会分叉的。
 */
val DEFAULT_CPS_KEY_CODES: List<Int> = listOf(KeyCodes.BTN_LEFT)

/** 默认字体；与配置层的默认字体保持同一个值（但这里不引用它，保持纯数据模型） */
const val DEFAULT_COMPONENT_FONT_ID = "system:sans-serif"

/**
 * 早期格式里的"CPS 槽位"取值。
 *
 * ⚠️ 这是**读旧数据专用**的墓碑：早期文本组件只能选左键/右键，
 * 存的是 `cpsSlot = "LMB"`，读到时要翻译成对应的键码，
 * 否则升级后那一行 CPS 会突然变成数鼠标左键（如果用户原来选的是右键）。
 *
 * 新代码不要再用它。
 */
const val LEGACY_CPS_SLOT_LEFT = "LMB"
const val LEGACY_CPS_SLOT_RIGHT = "RMB"

/**
 * 组件类型。
 *
 * ⚠️ [id] 会**写进 JSON**，发布之后不能改 —— 改了老配置里的组件就读不出来了。
 * 因此显示名走 [label]，两者刻意分开（与 [StyleId][com.something.sthkey.domain.style.StyleId]
 * 同一个约定）。
 */
enum class ComponentType(val id: String, val label: String, val description: String) {
    KEY("key", "按键", "监听某个按键，按下时变色"),
    TEXT("text", "文本", "只显示文字：可以当水印，也可以显示 CPS"),
    ;

    companion object {
        /** 不认识的类型返回 null，由调用方跳过这一个组件（而不是整份布局读失败） */
        fun fromId(id: String?): ComponentType? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 自定义 Key 样式的全部设置（配置里 `custom` 那一段）。
 *
 * 与 [com.something.sthkey.domain.config.Live2DSettings] 完全对称：
 * Key 样式那一整套字段（keySize / colors / outline / showShiftKey / CPS 模式…）
 * 对自定义布局一个都不适用 —— 这里的一切都由组件列表决定。
 *
 * ============================================================
 * 曾经有过 snapEnabled / snapGapDp 两个字段，已经删掉
 * ============================================================
 * 它们是"拖动时红线/绿线吸附"的设置。拖动被移除之后那两个字段就没有任何
 * 消费者了（吸附后来改成按钮式、再后来按需求一并去掉），
 * 留着就是"改了会存进配置但看不出效果"的骗人设置。
 *
 * 真要恢复吸附（拖动或按钮式都行），把字段加回来即可 ——
 * 编解码是宽容的，老配置里缺这两个字段只会用默认值。
 *
 * @param components 画布上的全部组件，按摆放顺序；**渲染顺序就是列表顺序**（后画的在上）
 */
data class CustomLayoutSettings(
    val components: List<CustomComponent> = defaultCustomComponents(),
)

/*
 * ============================================================
 * 默认布局
 * ============================================================
 */

/** 默认键帽边长（基础坐标）；与 Key 样式的 KEY_SIZE 对齐 */
private const val DEFAULT_KEY_SIZE = 80f

/** 默认文本组件的尺寸 */
private const val DEFAULT_TEXT_WIDTH = 120f
private const val DEFAULT_TEXT_HEIGHT = 30f

/** 默认组件之间的留白 */
private const val DEFAULT_GAP = 10f

/**
 * 新建自定义 Key 配置时的默认布局：**一个 Q 键 + 一个 CPS 文本**。
 *
 * 与需求一致，也刻意做成"一打开就能看懂"的样子：
 * Q 键按下去会亮，下面的 CPS 文本跟着鼠标左键的点击次数走。
 */
fun defaultCustomComponents(): List<CustomComponent> = listOf(
    KeyComponent(
        id = DEFAULT_KEY_COMPONENT_ID,
        x = DEFAULT_GAP,
        y = DEFAULT_GAP,
        width = DEFAULT_KEY_SIZE,
        height = DEFAULT_KEY_SIZE,
        style = ComponentStyle(),
        // 只写键名：默认不带 CPS（想带就把这里写成 "Q(cps2)"）
        label = "Q",
        inputKeyCodes = listOf(KEY_CODE_Q),
    ),
    TextComponent(
        id = DEFAULT_TEXT_COMPONENT_ID,
        x = DEFAULT_GAP,
        y = DEFAULT_GAP + DEFAULT_KEY_SIZE + DEFAULT_GAP,
        width = DEFAULT_TEXT_WIDTH,
        height = DEFAULT_TEXT_HEIGHT,
        /*
         * 与按键组件**同一个默认主题**（黑底白字、70% 不透明）。
         *
         * 早先这里把底色做成全透明，结果文本组件在屏幕上是"裸字"，
         * 看起来像缺了键帽 —— 不想要背景的用户把不透明度拉到 0 即可，
         * 但默认值应该是"看起来像个正常元件"。
         */
        style = ComponentStyle(),
        // 文字里有 (cps)，所以它默认就显示 CPS；数的键是默认的左键
        text = DEFAULT_CPS_TEXT,
    ),
)

/** 默认组件 id；固定值便于"重置"与"找不到就补回来" */
const val DEFAULT_KEY_COMPONENT_ID = "key_q"
const val DEFAULT_TEXT_COMPONENT_ID = "text_cps"

/**
 * 默认文本内容：与 Key 样式模式 2 的显示样式一致。
 *
 * ⚠️ 这里的占位符不是装饰，**它就是"显示 CPS"这件事本身** ——
 * 删掉它这个文本组件就不显示 CPS 了（见 [TextComponent] 的说明）。
 */
const val DEFAULT_CPS_TEXT = "CPS: (cps)"

/**
 * Q 键的键码。
 *
 * 用 evdev 键码而不是别的东西：与 Key 样式的键位映射**同一个体系**
 * （`KeyMapping.inputKeyCodes`），这样编辑器可以直接复用现成的键位选择器，
 * 也不会多出一套"自定义键码"的概念。
 */
const val KEY_CODE_Q = 16

/**
 * 默认键码表：新建 Key 组件时用它预填一个可用但不冲突的键。
 *
 * 不追求覆盖全部按键 —— 用户选中组件后到属性面板里改就行。
 */
val SUGGESTED_KEY_CODES: List<Int> = listOf(
    KEY_CODE_Q,
    17, 18, 19, 20, 21, 22, 23, 24, 25, // W E R T Y U I O P
    30, 31, 32, 33, 34, 35, 36, 37, 38, // A S D F G H J K L
    44, 45, 46, 47, 48, 49, 50, // Z X C V B N M
    57, // Space
    KeyCodes.BTN_LEFT,
    KeyCodes.BTN_RIGHT,
)

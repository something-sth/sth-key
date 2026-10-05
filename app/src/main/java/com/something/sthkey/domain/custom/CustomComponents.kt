package com.something.sthkey.domain.custom

import com.something.sthkey.domain.config.AnimationMode
import com.something.sthkey.domain.config.JoystickStyle
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
}

/**
 * **有文字**的组件（按键 / 文本）。
 *
 * ============================================================
 * ⚠️ 为什么把文字相关的字段从 [CustomComponent] 挪到这里
 * ============================================================
 * 摇杆组件**没有文字**。如果"文字缩放""文字偏移"还留在基接口上，
 * 摇杆就得带着几个永远不生效的字段：
 *
 * - 属性面板要一路 `is KeyComponent || is TextComponent` 才敢显示它们；
 * - 而**编辑摇杆时那几个值是死的**（改了没反应），用户会当成 bug。
 *
 * 现在按"有没有这个能力"分成两层，编译器帮着保证不会漏。
 *
 * ⚠️ 摇杆也不在这里 —— 它连 [ComponentStyle] 都不用（见 [JoystickComponent]）。
 */
sealed interface TextualComponent : CustomComponent {
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
) : TextualComponent

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
) : TextualComponent

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
 * 摇杆组件：显示一个跟着手柄摇杆动的**摇杆**。
 *
 * ============================================================
 * 它为什么**不**用 [ComponentStyle]
 * ============================================================
 * [ComponentStyle] 是"键帽 + 文字"那一套（底色 / 文字色 / 描边 / 阴影 /
 * 圆角 / 字体），而摇杆是**三层结构**（方框底盘 + 盘内的圆 + 摇杆帽），
 * 两套外观项几乎没有一个能对上。
 *
 * 硬塞进去的后果是实现里到处 `if (是摇杆) 忽略这个字段`——
 * 而那种"改了没反应"的设置项正是最招人烦的。
 *
 * ⚠️ 所以直接复用 [JoystickStyle]（「标准」样式 gamepad2 用的那一份）:
 * 用户的原话是"配置项要与'标准'样式（gamepad2）相同" ——
 * **同一个类型**才能真正保证"相同"，各写一份迟早会漂移
 * （改了一边忘了另一边，两处摇杆长得不一样）。
 *
 * ⚠️ 这也意味着摇杆的外观**不属于**组件自己的"颜色/透明度"那几组，
 * 属性面板为它单独开「摇杆 / 摇杆帽 / 摇杆手感」三组。
 */
data class JoystickComponent(
    override val id: String,
    override val x: Float,
    override val y: Float,
    override val width: Float,
    override val height: Float,

    /**
     * **监听哪一个输入源**。
     *
     * ⚠️ 默认 [JoystickSource.GAMEPAD] —— 老配置里没有这个字段时走这里，
     * 而它们本来就是手柄摇杆。所以加这个字段**不会改动任何老配置的表现**。
     */
    val source: JoystickSource = JoystickSource.GAMEPAD,

    /**
     * 监听**哪一个**手柄摇杆。
     *
     * ⚠️ 只对 [JoystickSource.GAMEPAD] 有意义 —— 键盘/鼠标摇杆没有左右之分，
     * 这个字段在它们身上是死值（不删是因为删了会让老配置的解析多一个分支，
     * 而"留一个用不上的字段"比"多一条兼容路径"便宜）。
     */
    val side: StickSide = StickSide.LEFT,

    /** 全部外观与**手柄**手感；与「标准」样式共用同一个类型 */
    val joystick: JoystickStyle = JoystickStyle(),

    /**
     * 键盘摇杆的手感。
     *
     * ⚠️ 三种摇杆**各存一份手感**，而不是共用一个字段 ——
     * 因为它们的含义完全不同（八段式阈值 / 回中延迟 / 死区），
     * 共用一个字段的话切换输入源时数值会莫名其妙地"继承"过来。
     *
     * ⚠️ 用不上的那两份**照样存**（不按 source 清空）——
     * 这样用户切过去又切回来，原来调好的手感还在。
     */
    val keyboardFeel: KeyboardJoystickFeel = KeyboardJoystickFeel(),

    /** 鼠标摇杆的手感 */
    val mouseFeel: MouseJoystickFeel = MouseJoystickFeel(),

    /**
     * 「摇杆-键盘」四个方向各自监听的**一组键**，顺序是 `上 / 左 / 下 / 右`。
     *
     * ============================================================
     * ⚠️ 为什么是 `List<List<Int>>` 而不是 `List<Int>`
     * ============================================================
     * 用户的原话:
     *
     * > 你这又改成啥了，**内部是多选外部是单选**，你统一多选不行吗，
     * > 不要自己乱写了
     *
     * ⚠️ 他说得对。我上一版是:
     *
     * | 位置 | 做法 |
     * |---|---|
     * | 选择器（外部） | `MultiKeyPickerDialog` → 返回 `List<Int>` |
     * | 数据模型（内部） | `List<Int>` = **每一向一个键** |
     * | 落盘时 | `codes.firstOrNull()` —— **只取第一个** |
     *
     * ⚠️ 也就是"**让你选多个，但只存一个**" —— 数据模型与交互**对不上**。
     * 那种"看起来能多选"的假象比直接单选更糟。
     *
     * ⚠️ 现在与文本组件的 [TextComponent.cpsKeyCodesPerPlaceholder]
     * **同一个形状**（`List<List<Int>>`，每个位置一组键）——
     * 项目里已经有这个模式，照它做，读的人不用再记一套。
     *
     * ⚠️ 空的**内层**是有意义的:"这一向不绑任何键"（那一向永远不触发）。
     * 所以读取时不能把空内层当成"没配置"而丢掉 —— 丢了会让后面几向
     * **整体前移、上下左右全乱**（与 CPS 那处同一个坑）。
     *
     * ⚠️ 只对 [JoystickSource.KEYBOARD] 有意义；另外两种摇杆用不到它
     * （留着是为了"切过去又切回来时键位还在"，与 [side] 同一个理由）。
     */
    val inputKeyCodes: List<List<Int>> = DEFAULT_KEYBOARD_JOYSTICK_KEYS,
) : CustomComponent {
    /**
     * 这一份组件是否**用得到** [side]（手柄摇杆才有左右之分）。
     *
     * ⚠️ 给 UI 用:属性面板据此决定要不要显示「监听左右摇杆」那一行，
     * 而不是到处写 `source == GAMEPAD` 的判断。
     */
    val usesSide: Boolean get() = source == JoystickSource.GAMEPAD
}

/**
 * 摇杆组件监听哪一个摇杆。
 *
 * ⚠️ [id] 会写进 JSON，发布后不能改（与 [ComponentType] 同一个约定）。
 */
enum class StickSide(val id: String, val label: String) {
    LEFT("left", "左摇杆"),
    RIGHT("right", "右摇杆"),
    ;

    companion object {
        /** 不认识的值回退到左摇杆 —— 老配置缺这个字段时也走这里 */
        fun fromId(id: String?): StickSide = entries.firstOrNull { it.id == id } ?: LEFT
    }
}

/**
 * 摇杆组件的**输入源**。
 *
 * ============================================================
 * 为什么要这一层（它是整个功能的地基）
 * ============================================================
 * 用户的原话:"一个是「摇杆-键盘」，另一个是「摇杆-鼠标」，键盘摇杆其实就是
 * 检测 WASD，然后分八段式，决定摇杆帽方向而已……鼠标摇杆其实就是检测
 * 鼠标移动的组件，不过会**自动回中**"。
 *
 * ⚠️ 而他给的参考实现里那套"三层分离"说得很清楚:
 *
 * > 第一层:输入采集层 —— 把键盘、鼠标、手柄、触屏这四种完全不同的输入源，
 * > 全部转换成统一的 `(x, y)` 二维向量（范围 -1 到 1）
 * > 第二层:显示面板层 —— 拿到 `(x, y)` 后决定怎么表现
 *
 * ⚠️ 好消息是**本项目的渲染层早就是这个形状**:
 * [com.something.sthkey.ui.overlay.gamepad.JoystickSlot] 与
 * [com.something.sthkey.ui.overlay.gamepad.Joystick] 只吃 `(x, y)` + 外观，
 * **完全不关心数据从哪来**。
 *
 * 所以这一层加出来之后，三种摇杆**共用同一个渲染与弹簧**，
 * 差别只在"喂进去的 `(x, y)` 怎么算" —— 这正是 [JoystickSource] 的全部含义。
 *
 * ============================================================
 * ⚠️ [id] 会写进 JSON，不能改
 * ============================================================
 * 与 [ComponentType.id] 同一个约定。
 */
enum class JoystickSource(val id: String, val label: String) {
    /** 手柄轴（`StickState`）。原来的「摇杆」组件，行为不变 */
    GAMEPAD("gamepad", "手柄"),

    /**
     * 键盘 WASD，**八段式**。
     *
     * ⚠️ 八段式 = 只看"哪几个键同时按着"，方向是 8 个离散值之一，
     * 没有"推一半"这回事。所以它的手感参数里**没有死区**
     * （死区是给连续轴用的，对离散方向没有意义）。
     */
    KEYBOARD("keyboard", "键盘"),

    /** 鼠标位移，**停止移动 N 毫秒后自动回中** */
    MOUSE("mouse", "鼠标"),
    ;

    companion object {
        /** 不认识的值回退到手柄 —— 老配置缺这个字段时也走这里 */
        fun fromId(id: String?): JoystickSource =
            entries.firstOrNull { it.id == id } ?: GAMEPAD
    }
}

/**
 * 「摇杆-键盘」的手感参数。
 *
 * ============================================================
 * ⚠️ 为什么不复用 [JoystickStyle] 里那三个手感字段
 * ============================================================
 * [JoystickStyle] 的 `deadZone` / `sensitivity` / `smoothingMs` 是给
 * **连续轴**用的:
 *
 * | 字段 | 手柄的含义 | 键盘上还有意义吗 |
 * |---|---|---|
 * | `deadZone` | 轴漂移的死区 | ❌ 八段式是离散值，没有"漂移" |
 * | `sensitivity` | 轴值的放大倍率 | ❌ 八段式只有 8 个方向，放大不改方向 |
 * | `smoothingMs` | 弹簧软硬 | ⚠️ 有意义，但名字与含义要重述 |
 *
 * 所以这里**单独定义**，而不是塞进 `JoystickStyle` —— 后者会让手柄那边
 * 多出两个永远用不上的滑块（那种"改了看不出效果"的设置是本项目明确要避免的）。
 */
data class KeyboardJoystickFeel(
    /**
     * 八段式的**触发阈值**（`0f .. 1f`）。
     *
     * ============================================================
     * ⚠️ 它在八段式里只有一个刻度意义（`0.5f`），别做成"想调多少调多少"
     * ============================================================
     * 八段式的方向是**离散**的:按了 W 就是"上"，不存在"推了 70% 的上"。
     * 所以阈值的作用只有一个 —— **两个方向都按住时算不算斜向**。
     *
     * ⚠️ 但这一项**必须存在**，因为用户可能想要"只按一个键也带一点斜"
     * 之类的行为，而把语义写死会让以后改不动。
     *
     * ⚠️ 默认 `0.5f` = 严格八段式:按一个键就是正方向，
     * 按两个相邻键就是那个 45° 角。
     *
     * ⚠️ **两种手感模式共用它**（用户明确要求:"方向阈值是共用的"）。
     */
    val threshold: Float = 0.5f,

    /**
     * 摇杆帽的**跟随方式**（两种，用户可切换）。
     *
     * 用户的原话:
     *
     * > 键盘摇杆我觉得可以搞个**手感的分段按钮**，跟手柄摇杆的平滑与精准
     * > 切换一样，我感觉现在键盘摇杆**手感特别怪**，这个用弹簧有点不太合适
     * > 吧可能，但不能直接删掉，要考虑别的用户的喜好，所以再加一种"**常规**"
     * > 模式，与原来的"平滑"组成分段按钮，可以自由切换
     */
    val mode: JoystickFollowMode = JoystickFollowMode.SMOOTH,

    /** 弹簧的平滑时间（毫秒）—— 与手柄那个同名同义，见 [JoystickStyle.smoothingMs] */
    val smoothingMs: Float = 60f,

    /**
     * **常规模式**下"从中心走到满偏"要多久（毫秒）。
     *
     * ============================================================
     * ⚠️ 它和 [smoothingMs] 是**两个模式里同一件事**的参数
     * ============================================================
     * 用户的原话:
     *
     * > 常规模式也可以调整方向阈值，不过平滑时间的滑块改为……emm我也不知道
     * > 改什么好，但含义都是**中心到摇杆帽到指定位置时的时长**吧，你看着办。
     * > 注意别理解错我意思，平滑模式用"平滑时间"，常规模式的滑块你自己想一个，
     * > **这两个滑块位置相同**
     *
     * ⚠️ 所以命名按"这个模式里它到底控制什么"来:
     *
     * | 模式 | 参数 | 含义 |
     * |---|---|---|
     * | 平滑 | [smoothingMs]「平滑时间」 | 弹簧有多软 —— **间接**影响快慢 |
     * | 常规 | [durationMs]「响应时间」 | **直接**就是走完这段距离的时长 |
     *
     * ⚠️ 两者都是"时间"，所以都带 `ms`；界面上位置相同、只在切模式时换。
     */
    val durationMs: Float = 120f,
)

/**
 * 摇杆帽的**跟随方式**（用户叫"手感"）。
 *
 * ============================================================
 * 为什么要有两种（而不是把弹簧换掉）
 * ============================================================
 * 用户的原话:
 *
 * > 我感觉现在键盘摇杆**手感特别怪**，这个用弹簧有点不太合适吧可能，
 * > 但**不能直接删掉**，要考虑别的用户的喜好
 *
 * ⚠️ 两种的差别在"**位置是怎么来的**":
 *
 * | 模式 | 位置怎么算 | 观感 |
 * |---|---|---|
 * | [SMOOTH] | **弹簧积分**（有惯性，会过冲再回稳） | 柔和，但换方向时"甩" |
 * | [REGULAR] | **按时间插值**（位置是算出来的） | "精准"、跟手，不会过冲 |
 *
 * ⚠️ [REGULAR] 是**匀速 + 两端极短的缓动** —— 见
 * [com.something.sthkey.ui.overlay.rememberJoystickFollow] 里的说明
 * （那里解释了为什么不用纯线性、也不用完整的 S 曲线）。
 */
enum class JoystickFollowMode(val id: String, val label: String) {
    /**
     * 常规:在 [KeyboardJoystickFeel.durationMs] 之内从当前位置走到目标。
     *
     * ⚠️ 放在第一个 —— 它是**新的默认**。用户觉得旧的手感怪，
     * 而默认值就是"多数人的第一印象"。
     */
    REGULAR("regular", "常规"),

    /** 平滑:原来的弹簧（保留，照顾喜欢那种手感的用户） */
    SMOOTH("smooth", "平滑"),
    ;

    companion object {
        /** 不认识的值回退到**常规** —— 老配置缺这个字段时也走这里 */
        fun fromId(id: String?): JoystickFollowMode =
            entries.firstOrNull { it.id == id } ?: REGULAR
    }
}

/**
 * 「摇杆-鼠标」的手感参数。
 *
 * ============================================================
 * 只有两项:**灵敏度** + **弹簧的平滑时间**
 * ============================================================
 * 做法与参考实现一致（也是 axon 的做法）:
 *
 * ```
 * 鼠标在动:  目标 = 累加位移 × 灵敏度（夹到 ±1）
 * 鼠标停下:  目标**停在原地** —— 帽子由弹簧平滑追上后就不动了
 * ```
 *
 * ============================================================
 * ⚠️⚠️ 曾经有"自动回中"，已经**删除**（不要再加回来）
 * ============================================================
 * 用户的原话:
 *
 * > 算了，我们还是不做回中了，清理一下吧，回中等有精力再做
 *
 * ⚠️ 删掉的理由不是"不需要"，而是**它把整个组件弄坏了**:
 * 回中是**纯时间驱动**的（鼠标停下后没有任何外部事件能推进它），
 * 所以它必须靠一个帧循环。而那个循环试了**四版**都没做稳:
 *
 * | 版本 | 症状 |
 * |---|---|
 * | 一 | 停止判据恒成立 → 循环刚起步就死 |
 * | 二 | 按一下任意按键才回中（启停被重组摆布） |
 * | 三 | 唯一的帧循环 + Channel → **整个组件直接死了** |
 *
 * ⚠️ 教训:**帧循环这类"自己驱动自己"的东西，一旦要跨重组存活，
 * 就很难做稳**；而它带来的收益（帽子自己回中）远小于它带来的风险
 * （帽子彻底不动）。所以宁可先不做。
 *
 * ⚠️ 真要再做，建议换个完全不依赖 Compose effect 的思路
 * （比如把累加与衰减放在采集层或一个独立的 View 里），
 * 而不是在 composable 里再试一次帧循环。
 */
data class MouseJoystickFeel(
    /**
     * 鼠标位移 → 摇杆位移的**灵敏度**。
     *
     * ============================================================
     * ⚠️⚠️ 单位是"像素"吗？不是 —— 它是"每次上报的位移量"的倍率
     * ============================================================
     * 输入来自 evdev 的 `REL_X` / `REL_Y`，而它们**每次上报往往只有 ±1**
     * （一次硬件采样），上报频率却可以到 **1000Hz**。
     *
     * 于是"每秒累计"约等于:
     *
     * ```
     * 1000Hz × 1 = 1000 /秒      普通鼠标
     * 高 DPI + 高回报率 → 更多
     * ```
     *
     * ⚠️ 我第一版默认 `0.01`，那意味着**满偏只需要 100 个累计单位**
     * —— 也就是**约 0.1 秒**就顶到边界。用户的原话:
     *
     * > 我灵敏度拉到最低也会动一下鼠标摇杆帽就直接向这个方向飞，
     * > 要不是有摇杆限制边界我都不知道摇杆帽要飞到哪去，
     * > 灵敏度可以再设低一点下限吧，**我鼠标 dpi 比较大**
     *
     * ⚠️ 所以现在按"**满偏需要多少像素**"来定档位:
     *
     * | 本值 | 满偏需要的累计位移 |
     * |---|---|
     * | `0.0005`（**下限**） | 2000 |
     * | `0.002` | 500 |
     * | `0.005`（**默认**） | 200 |
     * | `0.02`（上限） | 50 |
     *
     * ⚠️ 默认取 `0.005` 而不是更小:2000 像素满偏对普通鼠标太"死"，
     * 而 DPI 大的用户可以往下调（下限给足了余量）。
     */
    val sensitivity: Float = 0.005f,


    /** 弹簧的平滑时间（毫秒）—— 与手柄那个同名同义 */
    val smoothingMs: Float = 60f,
)

/*
 * ============================================================
 * 键鼠摇杆手感的取值上下限（**唯一真源**）
 * ============================================================
 * ⚠️ 滑块、存档读取时的夹取、以及以后可能有的"重置为默认"
 * 都要读这里 —— 三处各写一遍就会漂（那正是"按键间距存不住"的成因）。
 *
 * ⚠️ 为什么放在 `domain` 而不是 `data`:依赖方向是 `data → domain`，
 * 反过来会让领域层被存档格式绑住。⚠️ 实测本项目 `domain` 层
 * **一个 `import ...data...` 都没有**（有意的），别破坏它。
 *
 * ⚠️ 单位:毫秒（时间类）或倍率（灵敏度）。
 */

/** 八段式阈值下限 —— 不能是 0，否则滑块拉到最左会让摇杆完全不动 */
const val JOYSTICK_THRESHOLD_MIN = 0.05f

/**
 * 「摇杆-键盘」四个键位的**顺序**（`List<Int>` 的下标语义）。
 *
 * ⚠️ 顺序本身就是数据模型的一部分 —— 存档里那个数组是按这个顺序解释的，
 * **发布之后不能改**（改了会让老配置的上下左右全乱）。
 */
enum class JoystickDirection(val label: String) {
    UP("上"),
    LEFT("左"),
    DOWN("下"),
    RIGHT("右"),
    ;

    companion object {
        /** 四个方向按声明顺序 —— 界面与解码都按它遍历，不各写一份 */
        val all: List<JoystickDirection> = entries.toList()
    }
}

/**
 * 「摇杆-键盘」的默认键位:**上 W / 左 A / 下 S / 右 D**。
 *
 * ⚠️ 形状是"每一向一组键"（`List<List<Int>>`），与
 * [TextComponent.cpsKeyCodesPerPlaceholder] 一致 ——
 * 每一向可以绑多个键，绑几个都行。
 *
 * ⚠️ 用 [KeyCodes] 里的具名常量，**不写字面量** ——
 * 那几个常量有单测钉着与字母表一致（见 `KeyCodesWasdTest`），
 * 而字面量不会跟着表变。
 *
 * ⚠️ 外层顺序必须与 [JoystickDirection] 一致:上 / 左 / 下 / 右。
 */
val DEFAULT_KEYBOARD_JOYSTICK_KEYS: List<List<Int>> = listOf(
    listOf(KeyCodes.KEY_W),
    listOf(KeyCodes.KEY_A),
    listOf(KeyCodes.KEY_S),
    listOf(KeyCodes.KEY_D),
)

/** 常规模式的响应时间上下限（毫秒） */
const val JOYSTICK_FOLLOW_DURATION_MIN = 20f
const val JOYSTICK_FOLLOW_DURATION_MAX = 600f

/**
 * 鼠标灵敏度:每次上报位移的倍率。
 *
 * ⚠️ 按"满偏需要多少累计位移"定档（见 [MouseJoystickFeel.sensitivity]）:
 * 下限 `0.0005` = 2000、上限 `0.02` = 50。
 *
 * ⚠️ 下限给到这么小是**用户明确要求**的（"我鼠标 dpi 比较大"）——
 * 高 DPI + 高回报率的鼠标每秒能累计好几千，原来的 `0.01` 下限
 * 让它**一碰就顶到边界**。
 */
const val JOYSTICK_SENSITIVITY_MIN = 0.0005f
const val JOYSTICK_SENSITIVITY_MAX = 0.02f


/** 弹簧平滑时间的上限 —— 与 `JoystickStyle.smoothingMs` 那边保持一致 */
const val JOYSTICK_SMOOTHING_MAX_MS = 300f

/**
 * 组件类型。
 *
 * ============================================================
 * ⚠️ [id] 会**写进 JSON**，发布之后不能改
 * ============================================================
 * 改了老配置里的组件就读不出来了。因此显示名走 [label]，
 * 两者刻意分开（与 [StyleId][com.something.sthkey.domain.style.StyleId] 同一个约定）。
 *
 * ============================================================
 * ⚠️ 为什么多了 [category]
 * ============================================================
 * 用户的原话:"原本的 key 组件与文本组件分到'通用'栏，然后再分出键盘专栏
 * 与手柄专栏组件，键盘专栏还没想好放什么，先写个无，留个占位，
 * 手柄专栏就放个摇杆组件"。
 *
 * 于是"添加组件"弹窗按 [category] 分栏，而不是把四种类型平铺一行 ——
 * 平铺的话以后每加一种就更挤一分，而且"这个组件属于哪一类"
 * 在界面上完全看不出来。
 *
 * ⚠️ 后来用户把键盘栏填上了:"我想加两个键盘栏的组件，都是摇杆"。
 */
enum class ComponentCategory(val label: String) {
    /** 与具体输入设备无关的组件 */
    COMMON("通用"),

    /** 键盘专属 */
    KEYBOARD("键盘"),

    /** 手柄专属 */
    GAMEPAD("手柄"),
}

enum class ComponentType(
    val id: String,
    val label: String,
    val description: String,
    val category: ComponentCategory,
) {
    KEY(
        id = "key",
        label = "按键",
        description = "监听某个按键，按下时变色",
        category = ComponentCategory.COMMON,
    ),

    TEXT(
        id = "text",
        label = "文本",
        description = "只显示文字：可以当水印，也可以显示 CPS",
        category = ComponentCategory.COMMON,
    ),

    JOYSTICK(
        id = "joystick",
        label = "摇杆",
        description = "跟着手柄摇杆动：底盘、内圆、摇杆帽",
        category = ComponentCategory.GAMEPAD,
    ),

    /*
     * ============================================================
     * ⚠️ 下面两个是"同一套渲染、不同输入源"
     * ============================================================
     * 用户的原话:"我想加两个键盘栏的组件，都是摇杆，不过一个是
     * 「摇杆-键盘」，另一个是「摇杆-鼠标」…… 具体配置项不建议全部参考
     * 手柄摇杆，UI 层面的描边、颜色之类的可以复用，具体的手感之类配置项
     * 得单独分出来"。
     *
     * ⚠️ 三者**共用** [JoystickComponent] 与 [JoystickStyle]（外观），
     * 只有 [JoystickComponent.source] 不同 —— 渲染与弹簧因此完全复用，
     * 不存在"三份画摇杆的代码各改一半"的风险。
     */

    JOYSTICK_KEYBOARD(
        id = "joystick_keyboard",
        label = "摇杆-键盘",
        description = "跟着 WASD 动：八段式方向，按哪几个键就指向哪里",
        category = ComponentCategory.KEYBOARD,
    ),

    JOYSTICK_MOUSE(
        id = "joystick_mouse",
        label = "摇杆-鼠标",
        description = "跟着鼠标移动动：停下后会自动回中",
        category = ComponentCategory.KEYBOARD,
    ),
    ;
    /*
     * 注意:上面那三个摇杆类型与 [JoystickSource] 是**同一个东西的两种表达**
     * （前者是界面上的"组件类型"，后者是数据上的"输入源"）。
     * 两者之间的桥只有一处 —— 下面的 [of] / [joystickSource]，
     * 不要再在别处写第二个 `when`（两份映射必然漂开）。
     */

    companion object {
        /** 不认识的类型返回 null，由调用方跳过这一个组件（而不是整份布局读失败） */
        fun fromId(id: String?): ComponentType? = entries.firstOrNull { it.id == id }

        /** 某一栏里的组件类型（按声明顺序） */
        fun inCategory(category: ComponentCategory): List<ComponentType> =
            entries.filter { it.category == category }

        /**
         * [JoystickSource] → [ComponentType]。
         *
         * ============================================================
         * ⚠️⚠️ 这是**唯一**的映射，别在别处再写一个
         * ============================================================
         * 三种摇杆**共用同一个数据类** [JoystickComponent]，只靠
         * [JoystickComponent.source] 区分。于是"输入源 ↔ 组件类型"
         * 这条映射就成了**唯一的桥**，而它被三处用到:
         *
         * | 用它的地方 | 写错的后果 |
         * |---|---|
         * | 存档（`CustomLayoutCodec`） | 加个键盘摇杆，**重启后它开始跟手柄动** |
         * | 复制（`typeOf`） | 副本 id 的前缀与实际类型不符 |
         *
         * ⚠️ 我第一版就是**两处各写了一份**，而其中一处（`typeOf`）
         * 漏了分叉、把所有摇杆都当成手柄摇杆 —— 正是"两份映射必然漂开"
         * 的现场。现在只留这一处。
         *
         * ⚠️ `when` **不留 `else`**:给 [JoystickSource] 加第四种输入源时，
         * 编译器会在这里报错，而不是静默落进"手柄摇杆"。
         */
        fun of(source: JoystickSource): ComponentType = when (source) {
            JoystickSource.GAMEPAD -> JOYSTICK
            JoystickSource.KEYBOARD -> JOYSTICK_KEYBOARD
            JoystickSource.MOUSE -> JOYSTICK_MOUSE
        }

        /**
         * [ComponentType] → [JoystickSource]，[of] 的反向。
         *
         * ⚠️ **只列那三个摇杆类型、不留 `else`** —— 与项目其它 `when` 同一个约定:
         * 留 `else` 的话，以后把某个摇杆类型改名/拆分时编译器不会报错，
         * 而它会静默地掉进"手柄摇杆"。
         *
         * ⚠️ 传进来的必然是那三个之一（调用点只在摇杆的分支里）。
         */
        fun joystickSource(type: ComponentType): JoystickSource = when (type) {
            JOYSTICK -> JoystickSource.GAMEPAD
            JOYSTICK_KEYBOARD -> JoystickSource.KEYBOARD
            JOYSTICK_MOUSE -> JoystickSource.MOUSE
            KEY, TEXT -> error("不是摇杆类型：$type")
        }
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

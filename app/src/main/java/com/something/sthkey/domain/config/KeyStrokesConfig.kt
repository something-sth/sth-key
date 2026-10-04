package com.something.sthkey.domain.config

import com.something.sthkey.domain.custom.CustomLayoutSettings
import com.something.sthkey.domain.custom.defaultCustomComponents
import com.something.sthkey.domain.style.KeyLayout
import com.something.sthkey.domain.keys.KeyCodes
import com.something.sthkey.domain.style.OverlayStyleDescriptor
import com.something.sthkey.domain.style.OverlayStyleRegistry
import com.something.sthkey.domain.style.StyleId

/**
 * 一个文字偏移（基础坐标单位）。
 *
 * ============================================================
 * 为什么抽成独立的 data class，而不是两个并列的 map
 * ============================================================
 * `xBySlot` + `yBySlot` 两个 map 的话，"某个键只调了 X"这种状态
 * 要靠两处查表拼起来；任何一处漏了就会变成"Y 回落到全局、X 没有"，
 * 而这种不一致在界面上完全看不出来。
 *
 * 合成一个值之后，"这个键有没有自己的偏移"是一件事，不是两件。
 */
data class TextOffset(val x: Float = 0f, val y: Float = 0f)

/**
 * 一个组件的字间距 / 行间距（**相对字号的百分比**，0 = 不动）。
 *
 * ============================================================
 * 为什么用"百分比"而不是像素 / dp
 * ============================================================
 * 字号是可调的（文字缩放 + 整体缩放），间距必须跟着字号走 ——
 * 固定像素值在小字号下会挤成一团、大字号下又几乎看不出来。
 * 百分比天生跟着字号缩放，用户调好之后再改字号也不会白调。
 *
 * 与 [TextOffset] 同样合成一个值、而不是两个并列的 map：
 * "这个组件有没有自己的间距"应当是一件事。
 */
data class TextSpacing(
    /** 字间距：正值拉开、负值收紧 */
    val letter: Float = 0f,
    /** 行间距：正值加高行距、负值收紧（有下限，不会压到重叠） */
    val line: Float = 0f,
)

/**
 * 颜色配置。
 *
 * 每项是 **24 位 RGB**（`0xRRGGBB`），**不含透明度**：
 * 透明度由 [Opacity] 单独控制，绘制时再合成。
 * 这样做的好处是"颜色"和"透明度"各自只有一个真源，
 * 不会出现"改颜色把透明度也改掉了"这种耦合（旧项目就是混在一起）。
 *
 * ============================================================
 * 描边与阴影的颜色**每种状态各一个**
 * ============================================================
 * 描边与文字阴影原本各只有一个颜色，不区分按下。
 * 现在两者都按"未按下 / 按下"分开，与键帽、文字一致 ——
 * 于是四个元素的颜色结构完全对称，界面上也就能用同一个模式排列。
 *
 * ⚠️ 老配置的兼容：旧版没有这四项，读取时**用当时未按下文字的颜色**
 * 同时填给按下与未按下两个槽位（见 [migratedOutlineColors]）。
 * 这不是"随便挑个默认值"，而是还原旧版的实际观感 ——
 * 旧版描边色为空时就回落到白色，而默认文字未按下色正是白色。
 */
data class ColorSet(
    /** 键帽未按下时的底色 */
    val keyUp: Long = 0x000000,
    /** 键帽按下时的底色 */
    val keyDown: Long = 0xFFFFFF,
    /** 键帽未按下时的文字颜色 */
    val textUp: Long = 0xFFFFFF,
    /** 键帽按下时的文字颜色 */
    val textDown: Long = 0x000000,

    /**
     * 描边颜色（未按下 / 按下）。
     *
     * 默认**跟随文字颜色**：描边的作用是让文字在复杂背景上更清楚，
     * 跟着文字色是唯一说得通的默认值。
     */
    val outlineUp: Long = textUp,
    val outlineDown: Long = textDown,

    /** 文字阴影颜色（未按下 / 按下）；默认同样跟随文字颜色 */
    val shadowUp: Long = textUp,
    val shadowDown: Long = textDown,
)

/**
 * 文字阴影。
 *
 * ============================================================
 * 两种形态，用 [mode] 选
 * ============================================================
 * - [ShadowMode.SOFT]：跟随文字形状的**模糊投影**。观感自然，
 *   代价是模糊要额外绘制，键多的时候比硬阴影重一些；
 * - [ShadowMode.HARD]：偏移的**实心副本**。像描边一样锐利，
 *   小字下更清楚，开销也低。
 *
 * 两者共用 [size] 这一个滑块，含义随模式变化：
 * 柔光是**模糊半径**、硬阴影是**偏移距离** —— 对用户来说都是"阴影多明显"，
 * 给两个滑块反而要解释区别。
 */
data class TextShadow(
    val enabled: Boolean = false,
    val mode: ShadowMode = ShadowMode.SOFT,
    /** 柔光 = 模糊半径；硬阴影 = 偏移距离。单位 dp */
    val size: Float = 2f,
) {
    companion object {
        const val SIZE_MIN = 0.5f
        const val SIZE_MAX = 8f
    }
}

/**
 * 阴影形态。
 *
 * ⚠️ [id] 会写进 JSON，发布后不能改。
 */
enum class ShadowMode(val id: String, val label: String) {
    SOFT("soft", "柔光"),
    HARD("hard", "硬阴影"),
    ;

    companion object {
        val DEFAULT: ShadowMode = SOFT

        fun fromId(id: String?): ShadowMode =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * 键帽描边配置。
 *
 * ============================================================
 * ⚠️ [color] 是**读老配置专用**的墓碑字段，新代码不要用
 * ============================================================
 * 颜色已经搬到 [ColorSet.outlineUp] / [ColorSet.outlineDown]
 * （因为要区分按下与未按下）。这里留着它只为**迁移**：
 * 老配置把颜色存在这个字段里，读取时要搬过去。
 *
 * 迁移规则见 [migratedOutlineColors]。
 */
data class KeyOutline(
    val enabled: Boolean = false,
    /** 单位 dp */
    val width: Float = 2f,
    /** @deprecated 只用 [ColorSet.outlineUp] / [ColorSet.outlineDown]；此处仅供迁移 */
    val color: Long? = null,
)

/**
 * 单项透明度（百分比 0..100）。
 *
 * ============================================================
 * 为什么每个元素都要分"按下 / 未按下"
 * ============================================================
 * 这是需求里点名的做法：按下时让某个元素淡出（例如阴影变淡）
 * 是很自然的效果，而"只有一个值"就表达不了。
 *
 * 于是四个元素 × 两种状态 = 八项。
 * 结构上与会用它们的颜色完全对称，界面上按同一个顺序排列即可。
 *
 * ============================================================
 * ⚠️ 为什么下限从 20 放宽到 0
 * ============================================================
 * 早先下限是 20（"怕用户把键帽调到看不见"）。但描边与阴影的合理用法
 * 恰恰包括**完全关掉**（0），而它们现在有了独立的滑块 ——
 * 卡在 20 会让"不想要描边"变成一个做不到的需求。
 * 键帽与文字也一并放宽：有独立滑块之后，用户自己会判断要不要拉到 0。
 */
data class Opacity(
    val keyUp: Int = 70,
    val keyDown: Int = 100,
    val textUp: Int = 100,
    val textDown: Int = 100,
    val outlineUp: Int = 100,
    val outlineDown: Int = 100,
    val shadowUp: Int = 100,
    val shadowDown: Int = 100,
) {
    companion object {
        const val MIN = 0
        const val MAX = 100
    }
}

/**
 * 老配置的描边颜色迁移。
 *
 * ============================================================
 * 规则（这是需求明确指定的，别自己发挥）
 * ============================================================
 * 旧版描边只有一个颜色，而且默认就是**跟随文字颜色**。
 *
 * - 老配置**设过**描边色 → 那一个值**同时**填给按下与未按下。
 *   旧版本来就不区分状态，两个状态用同一个值才是"观感不变"。
 * - 老配置**没设过** → 各自跟随**对应状态**的文字色：
 *   未按下跟随 `textUp`、按下跟随 `textDown`。
 *
 * ⚠️ 第二句里的"各自"是关键。第一版写成 `up to up`，把按下态也填成了
 * `textUp` —— 于是"文字按下变黑、描边却没跟着变"，
 * 迁移把外观改掉了，正是迁移最不该做的事。测试抓到了这一条。
 *
 * @param legacyColor 旧配置里的描边色；null 表示没设过
 * @param colors      已经解析好的颜色（文字色要用它的）
 * @return 描边色（未按下）与描边色（按下）
 */
fun migratedOutlineColors(legacyColor: Long?, colors: ColorSet): Pair<Long, Long> =
    if (legacyColor != null) {
        legacyColor to legacyColor
    } else {
        colors.textUp to colors.textDown
    }

/**
 * 按键映射：把若干输入键码合成悬浮窗上的一个按键。
 *
 * [inputKeyCodes] 是**列表**而不是单值，因为一个显示键经常需要绑定多个物理键：
 * 例如 SHIFT 要同时接受左 Shift(42) 与右 Shift(54)，
 * 以后手柄的 A 键也可能同时映射到键盘的某个键。
 */
data class KeyMapping(
    val id: String,
    val inputKeyCodes: List<Int>,
    val displayText: String,
)

/**
 * 按键动画模式。
 *
 * 三者是**并列**关系，"无动画"本身就是一种模式 ——
 * 这样 UI 上可以用一组分段按钮表达，语义也比一个 switch 清楚得多。
 */
enum class AnimationMode(val id: String, val label: String, val description: String) {
    /** 没有过渡，按下即变色 */
    NONE("none", "无动画", "按键状态直接切换，没有过渡"),

    /** 颜色渐变：底色与文字颜色在时长内插值 */
    FADE("fade", "颜色渐变", "底色与文字颜色平滑过渡"),

    /**
     * 扩散收缩：从键帽中心扩散出实心圆，松开时收回。
     *
     * ⚠️ **测试功能**：效果尚未定稿（圆角与抗锯齿的处理仍在调整），
     * 因此说明文字里明确带「（测试）」后缀，避免用户以为它和另外两种一样稳定。
     */
    RIPPLE("ripple", "扩散收缩", "按下时从中心向外扩散，松开时向内收缩（测试）"),
    ;

    companion object {
        val DEFAULT: AnimationMode = FADE

        fun fromId(id: String?): AnimationMode =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * 一套完整的按键显示配置。
 *
 * 关于存储形态（重要）：
 * 配置的**导入/导出**是 zip 压缩包，里面除了这份参数还能放样式资源
 * （自定义 Live2D 模型、用于替换键帽的图片等）。详见 domain/config/README.md。
 * 本文件只描述参数本身，不关心打包方式。
 */
data class KeyStrokesConfig(
    val id: String,
    val name: String,
    val description: String = "",
    /** 内置配置不可删除，但可以复制 */
    val builtIn: Boolean = false,
    /** 悬浮窗样式，见 [OverlayStyleRegistry]；未知值在读取时回落为默认样式 */
    val styleId: String = OverlayStyleRegistry.defaultStyleId,
    /** 按键尺寸，单位 dp（同时决定悬浮窗基础尺寸） */
    val keySize: Float = 80f,
    /** 键间距，单位 dp */
    val keyGap: Float = 10f,
    /** 整体缩放百分比 50..200 */
    val scalePercent: Int = 100,
    /** 文字缩放百分比 50..150 */
    val textScalePercent: Int = 100,
    /** 圆角开关 + 半径百分比 0..50 */
    val cornerRadiusEnabled: Boolean = false,
    val cornerRadiusPercent: Float = 0f,
    val colors: ColorSet = ColorSet(),
    val outline: KeyOutline = KeyOutline(),
    /** 文字阴影（柔光 / 硬阴影），见 [TextShadow] */
    val shadow: TextShadow = TextShadow(),
    val opacity: Opacity = Opacity(),
    /**
     * 字体标识，见 [com.something.sthkey.domain.font.FontRegistry]。
     *
     * 只存一个 id（例如 `system:sans-serif` / `builtin:Minecraft AE.ttf` /
     * `imported:<uuid>`），字体本身是**全局**的 ——
     * 这样在一个配置里导入的字体，切到别的配置也能直接选，
     * 而换字体只改动这一个字符串。
     *
     * 默认值是纯字符串常量（而不是引用 FontRegistry 的常量）：
     * 配置是纯数据模型，不该反过来依赖带 Android 上下文的字体库。
     */
    val fontId: String = DEFAULT_FONT_ID,
    /**
     * 图片字体标识（可选）。
     *
     * ============================================================
     * 为什么它是**独立字段**，而不是又一个 [fontId] 的取值
     * ============================================================
     * 常规字体（矢量）与图片字体（Minecraft 图集）不是"二选一"：
     * 图片字体**只有 ASCII 字形**，中文必须由矢量字体兜底。
     * 所以两者是"**常规字体（必选）+ 图片字体（可选）**"的关系 ——
     * 合成一个单选框的话，选了图片字体就没有了中文的退路。
     *
     * 空字符串表示不用图片字体（走 [fontId] 那条路），这是默认值 ——
     * 绝大多数用户不会去导入字体图。
     *
     * 字段名以 `fontId` 结尾是**有意的**：配置包的导出/导入靠
     * `ParamsTree` 扫"名字以 fontId 结尾的字段"来收集字体文件，
     * 叫这个名字就自动被覆盖，不必去改那一套逻辑。
     */
    val bitmapFontId: String = "",

    /**
     * 文字偏移（基础坐标单位，与自定义 Key 的 `textOffsetX/Y` **同一套语义**）。
     *
     * ============================================================
     * 为什么 Key 也加这个
     * ============================================================
     * 1. **图片字体需要它**：不同字体图集的 `ascent` 不同，字形在格子里
     *    的高低也就不一样（有的图基线偏上、有的偏下）。不给人一个微调口子，
     *    换一张图集就可能"整体偏上"，而且只能靠改图解决。
     * 2. **自定义 Key 本来就有**（`CustomComponent.textOffsetX/Y`），
     *    Key 没有会让两者不对等 —— 转换过去时也无从对应。
     *
     * 单位与自定义 Key 一致（基础坐标单位，渲染时乘 `scale`），
     * 所以"Key → 自定义"的转换就是**原样搬运**，不需要换算。
     *
     * 正值 = 往右 / 往下。
     */
    val textOffsetX: Float = 0f,
    val textOffsetY: Float = 0f,

    /**
     * **每个槽位**各自的文字偏移（键 = [com.something.sthkey.domain.style.KeyLayout.Id]）。
     *
     * ============================================================
     * 为什么需要"每组件"而不只是全局
     * ============================================================
     * 用户实测遇到的问题：空格位显示的是 `————` 这种**非 ASCII** 的横线，
     * 它不会被图片字体替换（图片字体只有 ASCII 字形），走的是矢量字体。
     * 而矢量字体与图片字体的基线位置不同 ——
     * 把整体往下调让横线好看了，那些**真正用图片字体画的键**就偏下了。
     *
     * 所以需要"每个键各自微调"。这也天然覆盖了 CPS 模式 2 的两个额外
     * 显示位（[KeyLayout.Id.CPS_L] / [KeyLayout.Id.CPS_R]）——
     * 它们各自是独立的槽位 id，不需要特判。
     *
     * 用**槽位 id 做键**而不是下标：布局调整（加键、换布局）后下标会错位，
     * 而槽位 id 是稳定的，用户的调整不会"跑到别的键上去"。
     *
     * 值为 null / 不存在 = 用全局偏移。
     */
    val slotTextOffsets: Map<String, TextOffset> = emptyMap(),

    /**
     * 字间距 / 行间距（**相对字号的百分比**，0 = 不动）。
     *
     * 与 [textOffsetX]/[textOffsetY] 一样分两层：
     * 这里是**全局**默认值，[slotTextSpacings] 是每个槽位各自的覆盖。
     *
     * 图片字体的字形宽度与间距因图集而异（有的材质包字距很挤、有的很松），
     * 而 TTF 那套字距设置在图片字体上不起作用 —— 所以需要它。
     */
    val textSpacing: TextSpacing = TextSpacing(),

    /**
     * **每个槽位**各自的字间距 / 行间距；键与语义同 [slotTextOffsets]。
     *
     * 全局值不够用的场景：同一个键面上有一行是图片字体、另一行是
     * 非 ASCII 的矢量字体，两者的"合适间距"不一样。
     */
    val slotTextSpacings: Map<String, TextSpacing> = emptyMap(),
    /** 按键动画模式；旧的布尔开关已被它取代（三选一，见 [AnimationMode]） */
    val animationMode: AnimationMode = AnimationMode.DEFAULT,
    /** 动画时长（秒），0.1..0.5，默认 0.1 —— 与旧项目的渐变动画时长一致 */
    val animationDurationSec: Float = 0.1f,
    /** 是否显示 Shift 键位 */
    val showShiftKey: Boolean = false,
    /** 是否显示鼠标左右键 */
    val showMouseButtons: Boolean = true,
    /** 鼠标 CPS 显示 + 模式 1..3 */
    val mouseCpsEnabled: Boolean = false,
    val mouseCpsMode: Int = 1,

    /* ============================================================
     * 「手柄（标准）」样式专属 —— 只在这些样式下有意义
     * ============================================================ */

    /**
     * 摇杆的外观与观感设置（颜色 / 圆角 / 透明度 / 灵敏度 …）。
     *
     * ⚠️ 单独一个类而不是在这里再加十几个字段 —— 见 [JoystickStyle] 的说明。
     */
    val joystick: JoystickStyle = JoystickStyle(),

    /**
     * 是否显示**肩键**（LB / RB）。
     *
     * 画在 LT / RT 的**下方**，尺寸与它们相同。
     *
     * ⚠️ 默认 `false` —— 肩键用得比扳机少，多一行会占掉屏幕。
     */
    val showShoulderButtons: Boolean = false,

    /**
     * 是否显示 **A 键**（下方位键）。
     *
     * 这个样式里 A 是"跳跃"，是唯一一个不在摇杆 / 扳机里的常用键。
     */
    val showAButton: Boolean = true,

    /**
     * 是否显示 **SPACE**（空格槽位）。
     *
     * ⚠️ 在 gamepad2 里它与 [showAButton] 是**同一个屏幕位置**
     * （都横跨两列、都在 LT/RT 下面那一行）——
     * 键盘样式里的 `SPACE` 就是这个位置。
     *
     * 所以两个开关**同时打开会重叠**。设置页要让它们互斥:
     * 打开一个就关掉另一个。
     *
     * ⚠️ 为什么保留两个而不是合成一个:键位映射里它们是两个槽位
     * （`SPACE` 与 `A_BUTTON`），可以绑不同的手柄键。
     */
    val showSpaceKey: Boolean = false,

    /**
     * A 键**置顶** —— 把它挪到摇杆的正下方，LT / RT 整体下移一行。
     *
     * ============================================================
     * 布局对比（尺寸完全不变，只挪位置）
     * ============================================================
     * ```
     * 关闭:  左摇杆   右摇杆          开启:  左摇杆   右摇杆
     *           LT  A  RT                       A       ← 挪到摇杆正下方
     *             SPACE                       LT  ·  RT  ← 下移一行
     *                                           SPACE
     * ```
     *
     * ⚠️ 为什么有人要这个:拇指在摇杆上时，**右手拇指**够 A 键更近 ——
     * A 挪到中间之后，两个拇指都够得着。
     *
     * ⚠️ **仅在 [showAButton] 为真时有意义** —— A 都不显示就无所谓置不置顶。
     * 设置页要让这个开关跟着 [showAButton] 一起禁用。
     */
    val aButtonOnTop: Boolean = false,

    /**
     * **左右摇杆互换**（只换摇杆，不动键位映射）。
     *
     * 有些手柄（或某些游戏的设置）把左右摇杆的轴报反了，
     * 于是"推左摇杆、屏幕上动的是右边那个"。
     *
     * ⚠️ 只交换**摇杆的轴来源**，不交换键位 —— 用户原话:
     * "别的互换键位映射可以自己改不用加在这里"。
     */
    val swapSticks: Boolean = false,

    /**
     * 按键**高度的百分比**（`50..200`，`100` = 布局原值）。
     *
     * ============================================================
     * 与 `scalePercent` 的区别（很容易混）
     * ============================================================
     * | 设置 | 影响 |
     * |---|---|
     * | `scalePercent` | **整体**缩放:窗口、间距、字号、所有东西 |
     * | 本值 | **只**改键的高度（宽度与间距不动） |
     *
     * 用途:把键压扁一点好塞进屏幕，或者加高一点让字更好认 ——
     * 而整体缩放做不到（那会连窗口一起变）。
     */
    val keyHeightPercent: Int = 100,

    /**
     * 按键**间距的百分比**（`50..400`，`100` = 布局原值 10）。
     *
     * ============================================================
     * ⚠️ 它**不改变窗口宽度**
     * ============================================================
     * 用户的原话:"按键间距不能写死，要做成滑块供自行调整……
     * 记得悬浮窗尺寸要算好"。
     *
     * 做法是**锁住外框、让键宽跟着让位**:
     *
     * ```
     * 三列总跨度 = 260（固定）
     * 键宽     = (260 − 2 × 间距) / 3
     * 鼠标键宽 = (260 − 间距) / 2
     * ```
     *
     * 于是间距调到 0 时键变宽、调到最大时键变窄，**窗口一直是 300 宽**。
     *
     * ⚠️ 反过来做（键宽固定、间距变）会让窗口宽度跟着变 ——
     * 那不但要改样式注册值，还会让用户已经摆好的悬浮窗位置失效。
     *
     * ⚠️ 上限受几何限制:键宽要 `> 0`，所以间距必须 `< 130`。
     * 取 `400%`（= 40）是保守的，真实夹取在 `KeyLayout` 里。
     */
    val keyGapPercent: Int = 100,

    /**
     * 模式 2 / 3 的 CPS 文本模板。
     *
     * `(cps)` 是占位符，会被替换成实际数值；其余部分原样输出。
     * 例如 `CPS: (cps)` → `CPS: 3`，`(cps) CPS` → `3 CPS`。
     * 玩家习惯不同（前缀/后缀），所以做成模板而不是写死。
     */
    val cpsTextTemplate: String = DEFAULT_CPS_TEMPLATE,
    /**
     * 模式 1 的 CPS 文本模板。
     *
     * 用**单独的占位符** `(cps2)`：模式 1 的 CPS 是接在 LMB / RMB 主文字后面的，
     * 为 0 时应当什么都不显示，非 0 时才显示；而且前缀的空格是可调的
     * （有人想要 `LMB 3`，有人想要 `LMB3`）。
     *
     * 因此这一段**自带空格**：`(cps2)` 展开为 `" 3"`（非 0）或 `""`（为 0）。
     */
    val cpsTextTemplateMode1: String = DEFAULT_CPS_TEMPLATE_MODE1,
    val keyMappings: List<KeyMapping> = defaultKeyMappings(),
    /**
     * Live2D 样式专用的配置。
     *
     * ============================================================
     * 为什么要单独一块，而不是共用上面那些字段
     * ============================================================
     * Live2D 与按键显示是两套完全不同的实现：上面从 [keySize] 到 [keyMappings]
     * 全部只对按键样式有意义（键位、颜色、描边、圆角、动画、CPS、字体…），
     * Live2D 一个都用不到。
     *
     * 所以这里单独开一块，**Live2D 的编辑页只显示这一块 + 整体缩放 + 透明度**，
     * 按键那些设置项一个都不出现 —— 否则用户会对着一堆不起作用的选项发呆。
     *
     * 用一个嵌套对象而不是散在顶层：以后 Live2D 要加东西（模型缩放偏移、
     * 镜像、动作开关…）都进这里，不会再往顶层堆。
     */
    val live2d: Live2DSettings = Live2DSettings(),

    /**
     * 自定义 Key 样式专用的配置（画布上摆的那些组件）。
     *
     * ============================================================
     * 与 [live2d] 同一个思路，但更彻底
     * ============================================================
     * 自定义 Key 连"窗口里有什么"都是用户在编辑器里摆出来的，
     * 所以上面那整套字段（keySize / keyGap / colors / outline / opacity /
     * cornerRadius / animationMode / showShiftKey / showMouseButtons /
     * mouseCpsEnabled / keyMappings…）**一个都不适用**：
     *
     * - 键位与 CPS 显示 → 由 Key 组件、文本组件分别承担；
     * - 颜色 / 透明度 / 描边 / 圆角 / 字体 / 动画 → 每个组件各有一套。
     *
     * 唯一仍然共用的是 [scalePercent]（整体缩放）——
     * 它对每种样式含义都一样：把内容与窗口一起放大。
     *
     * 注：文本组件的 CPS 占位符（`(cps)` / `(cps2)`）**复用**上面
     * [cpsTextTemplate] 那一套替换逻辑，不另造一套规则。
     */
    val custom: CustomLayoutSettings = CustomLayoutSettings(),

    /**
     * 自定义 Key 样式的**整体透明度**（百分比）。
     *
     * ============================================================
     * 为什么单独开一个字段，而不是复用 [Opacity]
     * ============================================================
     * 用户的原话:"自定义 key 的配置编辑页加一个透明度调整（控制整体透明度），
     * 不碰自定义编辑页"。
     *
     * ⚠️ [Opacity] 那一套是**按键样式**的:键帽/文字/描边/阴影，各自分
     * 未按下与按下 —— 八个滑块。自定义 Key 的组件样式是**每个组件自己**的
     * （在编辑器里逐个调），全局再放八个只会让人分不清哪个盖过哪个。
     *
     * ⚠️ 所以这一个管的是**整块画布**（所有组件一起淡），语义与
     * Live2D 的 `Live2DSettings.opacityPercent` 一致 —— 那是另一个
     * "只有整体透明度"的样式，两边的做法刻意保持对称。
     *
     * 默认 100（不透明）:新配置看起来必须是"正常的样子"。
     */
    val customOpacityPercent: Int = 100,
) {
    /** 样式描述；配置里存的样式 id 失效时自动回落到默认样式 */
    val style: OverlayStyleDescriptor
        get() = OverlayStyleRegistry.resolveOrDefault(styleId)
}

/**
 * Live2D（键盘猫）样式的设置。
 *
 * 只有三项真正属于它自己；"整体缩放"是共用的（[KeyStrokesConfig.scalePercent]），
 * 因为它对两种样式含义相同 —— 都是把内容与窗口一起放大缩小。
 */
data class Live2DSettings(
    /**
     * 用哪个模型。
     *
     * ============================================================
     * 为什么是"模型 id"而不是"鼠标模式布尔值"
     * ============================================================
     * 早先这里是 `mouseMode: Boolean`（右侧显示方向键还是鼠标）。
     * 但"右侧显示什么"本质上就是"用哪个内置模型" ——
     * [LIVE2D_MODEL_KEYBOARD] 与 [LIVE2D_MODEL_STANDARD] 正是那两页。
     *
     * 允许用户导入模型之后，两个字段会打架
     * （`modelId = 鼠标版` + `mouseMode = false` 该听谁的？），
     * 所以统一成一个 id：内置两个 + `imported:<uuid>`。
     * 老配置读取时会自动迁移（见 JsonConfigCodec）。
     */
    val modelId: String = DEFAULT_LIVE2D_MODEL_ID,
    /**
     * 整个模型的透明度 0..100。
     *
     * 不复用按键样式的 [KeyStrokesConfig.opacity]：那套是"键帽/文字/描边"三份，
     * 对一只猫没有意义；猫只有一层画面。
     */
    val opacityPercent: Int = 100,
)

/*
 * ============================================================
 * 默认配置
 * ============================================================
 */

/** 内置默认配置的固定 id；首次启动时由 ConfigStore 落盘 */
const val DEFAULT_CONFIG_ID = "default"

/**
 * 默认字体 id（系统默认无衬线）。
 *
 * 与 [com.something.sthkey.domain.font.FontRegistry] 的系统字体 id 规则一致：
 * `system:<族名>`。写在这里而不是引用字体库的常量，是为了让配置模型保持纯粹
 * （不依赖任何 Android 上下文相关的代码）。
 */
const val DEFAULT_FONT_ID = "system:sans-serif"

/**
 * Live2D 模型 id。
 *
 * 与 [com.something.sthkey.domain.live2d.Live2DModels] 的规则一致：
 * `builtin:<名字>` / `imported:<uuid>`。和字体同理，这里只写纯字符串常量，
 * 不让配置模型依赖带 Android 上下文的模型库。
 */
const val LIVE2D_MODEL_KEYBOARD = "builtin:keyboard"
const val LIVE2D_MODEL_STANDARD = "builtin:standard"

/** 默认 Live2D 模型：内置的键盘猫键盘版 */
const val DEFAULT_LIVE2D_MODEL_ID = LIVE2D_MODEL_KEYBOARD

/** 模式 2 / 3 的默认 CPS 模板 */
const val DEFAULT_CPS_TEMPLATE = "CPS: (cps)"

/**
 * 模式 1 的默认 CPS 模板。
 *
 * 只有占位符、没有别的文字：它接在 LMB / RMB 主文字后面，
 * 展开后就是 `" 3"`（非 0）或 `""`（为 0）。
 */
const val DEFAULT_CPS_TEMPLATE_MODE1 = "(cps2)"

/** CPS 占位符（模式 2 / 3） */
const val CPS_PLACEHOLDER = "(cps)"

/** CPS 占位符（模式 1，自带空格语义） */
const val CPS_PLACEHOLDER_MODE1 = "(cps2)"

/** 动画时长范围（秒） */
const val ANIMATION_DURATION_MIN = 0.1f
const val ANIMATION_DURATION_MAX = 0.5f

/** 默认键位：与旧项目保持一致（WASD + 空格 + Shift + 鼠标左右键） */
fun defaultKeyMappings(): List<KeyMapping> = listOf(
    KeyMapping(id = "W", inputKeyCodes = listOf(17), displayText = "W"),
    KeyMapping(id = "A", inputKeyCodes = listOf(30), displayText = "A"),
    KeyMapping(id = "S", inputKeyCodes = listOf(31), displayText = "S"),
    KeyMapping(id = "D", inputKeyCodes = listOf(32), displayText = "D"),
    KeyMapping(
        id = "SPACE",
        inputKeyCodes = listOf(57),
        displayText = "————",
    ),
    KeyMapping(
        id = "SHIFT",
        inputKeyCodes = listOf(42, 54),
        displayText = "SHIFT",
    ),
    KeyMapping(
        id = "LMB",
        inputKeyCodes = listOf(KeyCodes.BTN_LEFT),
        displayText = "LMB",
    ),
    KeyMapping(
        id = "RMB",
        inputKeyCodes = listOf(KeyCodes.BTN_RIGHT),
        displayText = "RMB",
    ),
)

/**
 * 「手柄（标准）」样式的默认键位。
 *
 * ============================================================
 * ⚠️ 显示的是**键鼠的名字**，绑的是**手柄的键**
 * ============================================================
 * 这个样式给"用手柄玩键鼠游戏"的人看 —— 屏幕上写的是他脑子里想的
 * 键（LT / RT / A），而实际会亮的是他手上按的手柄键。
 *
 * | 槽位 | 显示 | 绑的键 |
 * |---|---|---|
 * | `LMB`（左） | `LT` | **扳机伪键码** `PSEUDO_KEY_TRIGGER_LEFT` |
 * | `RMB`（右） | `RT` | **扳机伪键码** `PSEUDO_KEY_TRIGGER_RIGHT` |
 * | `SPACE` | `A` | `BTN_SOUTH`（下方位） |
 * | `SHIFT` | `L3` | `BTN_THUMBL`（左摇杆按下） |
 *
 * ============================================================
 * ⚠️⚠️ LT/RT **不能**绑 `BTN_TL` / `BTN_TR` —— 那是 LB / RB
 * ============================================================
 * 这里犯过一次错，用户的原话是:
 * **"配置里写的是LT和RT，但监听的是LB和RB，你逗我呢"**。
 *
 * evdev 的真实分工（本项目 `KeyCodes` 的键名表也印证了这一点）:
 *
 * | 东西 | 报法 | 键码 |
 * |---|---|---|
 * | **LB** 左肩键 | **按键** | `BTN_TL` = `0x136` |
 * | **RB** 右肩键 | **按键** | `BTN_TR` = `0x137` |
 * | **LT** 左扳机 | **模拟轴** | `ABS_Z`（数字式手柄才用 `BTN_TL2`） |
 * | **RT** 右扳机 | **模拟轴** | `ABS_RZ`（数字式手柄才用 `BTN_TR2`） |
 *
 * `KeyCodes` 里的键名表写得很清楚:
 * `AvailableKey(BTN_TL, "LB", …)` / `AvailableKey(BTN_TL2, "LT", …)`。
 *
 * 所以扳机要用**伪键码**（见 `KeyCodes.PSEUDO_KEY_TRIGGER_*` 的说明）——
 * native 把扳机作为 `0..1000` 的轴送过来，采集层按阈值折成"按下 / 抬起"。
 *
 * ⚠️ 玩家当然可以自己改（配置页的键位映射是通用的）——
 * 这里只是**默认值**。
 *
 * ⚠️ WASD 那四个位置**没有默认映射** —— gamepad2 里它们的位置
 * 是摇杆（见 `KeyLayout.Id.JOYSTICK_*`），摇杆不绑键码。
 * 留着的话配置页会列出四个"永远不会亮"的槽位。
 */
fun gamepad2KeyMappings(): List<KeyMapping> = listOf(
    /*
     * ⚠️ 槽位 id 用 **`A_BUTTON`**，不是 `SPACE`。
     *
     * `A_BUTTON` 就是屏幕上那个"A"（横跨两列、在 LT/RT 下面那张卡片），
     * 对应键盘样式里 space 的位置 —— 用户的原话:"A 本身就指代 space"。
     *
     * ⚠️ `SPACE` 那个槽位在 gamepad2 里**默认隐藏**
     * （`showSpaceKey = false`），所以不要往它上面绑东西。
     */
    KeyMapping(
        id = KeyLayout.Id.A_BUTTON,
        inputKeyCodes = listOf(KeyCodes.BTN_SOUTH),
        displayText = "A",
    ),
    /*
     * ⚠️ 这个槽位在游戏里代表 **Shift（潜行）**，而绑的是 **B 键**。
     *
     * 用户的原话:"那个 L3 对应的应该是 shift 键，但是 shift 在我的世界
     * 对应的手柄按键应该是 B 键，这个需要调整"。
     *
     * 基岩版把潜行放在 **B** 上 —— 所以显示 `B`、实际绑 `BTN_EAST`。
     *
     * ⚠️ 槽位 id 仍是键盘样式的 `SHIFT`（位置的语义），
     * 只有"绑哪个键 / 显示什么字"换成手柄的。
     */
    KeyMapping(
        id = "SHIFT",
        inputKeyCodes = listOf(KeyCodes.BTN_EAST),
        displayText = "B",
    ),
    /*
     * ⚠️ **左边是 LT、右边是 RT**。
     *
     * 键位映射的 `id` 沿用键盘样式的 `LMB` / `RMB`
     * （左列那个 id 就是 `LMB`），而手柄上左扳机在左边 ——
     * **屏幕上的位置**才决定用户按得对不对，不是语义。
     */
    KeyMapping(
        id = "LMB",
        inputKeyCodes = listOf(KeyCodes.PSEUDO_KEY_TRIGGER_LEFT),
        displayText = "LT",
    ),
    KeyMapping(
        id = "RMB",
        inputKeyCodes = listOf(KeyCodes.PSEUDO_KEY_TRIGGER_RIGHT),
        displayText = "RT",
    ),
    /*
     * 肩键 —— 默认**隐藏**（`showShoulderButtons = false`），
     * 用户打开"显示肩键"之后才出现在 LT/RT 下面。
     *
     * ⚠️ `BTN_TL` / `BTN_TR` **才是** LB / RB —— 见 [KeyCodes] 的键名表。
     * （扳机那次就是错在这里:把 LT/RT 绑成了这两个。）
     */
    KeyMapping(
        id = KeyLayout.Id.SHOULDER_L,
        inputKeyCodes = listOf(KeyCodes.BTN_TL),
        displayText = "LB",
    ),
    KeyMapping(
        id = KeyLayout.Id.SHOULDER_R,
        inputKeyCodes = listOf(KeyCodes.BTN_TR),
        displayText = "RB",
    ),
)

/**
 * 内置默认配置。
 *
 * 名称固定为 `Default`：它不可删除，是"配置被删光/数据损坏"时的兜底，
 * 所以名字不跟界面语言走，也不允许用户改（列表页里改名按钮对它禁用）。
 */
fun defaultConfig(): KeyStrokesConfig = KeyStrokesConfig(
    id = DEFAULT_CONFIG_ID,
    name = "Default",
    description = "内置默认配置，不可删除",
    builtIn = true,
    styleId = StyleId.KEYSTROKES,
)

/*
 * ============================================================
 * CPS 文本模板
 * ============================================================
 */

/**
 * 套用 CPS 模板。
 *
 * @param template 形如 `CPS: (cps)` 或 `(cps) CPS`
 * @param cps      实际 CPS 数值
 * @param placeholder 该模板使用的占位符（模式 1 与其它模式用不同占位符）
 * @param hideWhenZero 为 0 时是否整段隐藏（模式 1 需要，模式 2/3 不需要）
 */
fun formatCpsTemplate(
    template: String,
    cps: Int,
    placeholder: String = CPS_PLACEHOLDER,
    hideWhenZero: Boolean = false,
): String {
    if (hideWhenZero && cps <= 0) return ""

    val value = if (placeholder == CPS_PLACEHOLDER_MODE1) {
        // 模式 1：占位符自带前导空格，写法由用户决定（可以不要空格）
        " $cps"
    } else {
        cps.toString()
    }

    return template.replace(placeholder, value)
}

/**
 * 复制一份配置。
 *
 * 任何"另存为/复制"都必须走这里，确保：
 * - 新 id；
 * - 不再是内置配置（否则会被当成不可删除项）；
 * - 名称带上来源，便于在列表里区分。
 */
fun KeyStrokesConfig.duplicate(newId: String, newName: String = "$name 副本"): KeyStrokesConfig =
    copy(id = newId, name = newName, builtIn = false)

/**
 * 把**参数**重置为默认值，配置的身份保持不变。
 *
 * ============================================================
 * 为什么必须只有这一个实现
 * ============================================================
 * 这件事原本在两个地方各写了一遍：`ConfigStore.resetToDefault` 里一份、
 * 配置编辑页的重置对话框里又内联了一份。两份一定会漂移，实际上就漂了：
 * 编辑页那份**漏了两件事** ——
 *
 * 1. 它写了 `styleId = preset.styleId`，于是"重置参数"会把一个 Live2D 配置
 *    变回按键样式（用户看到的是"重置完样式都变了"）；
 * 2. 它没有重置 [KeyStrokesConfig.live2d]，Live2D 自己的设置根本重置不掉。
 *
 * 现在只有这一个实现，所有调用方都走它。加字段时也只需要在这里加一行。
 *
 * **保留**的身份字段：id、描述、是否内置、样式。
 * **重置**的名称字段：只有内置配置的名称会回到 `Default` ——
 * 它本来就是"出厂状态"的锚点，顶着被改过的名字没有意义。
 *
 * @param preset 默认参数来源，抽成参数是为了测试时能塞别的预设
 */
fun KeyStrokesConfig.resetParamsToDefault(
    preset: KeyStrokesConfig = defaultConfig(),
): KeyStrokesConfig = copy(
    /*
     * 身份字段（id / 描述 / styleId）**故意不列出来**：copy 不写就是保持原样。
     * 这里曾经写着 styleId = preset.styleId —— 见上面第 1 条。
     */
    name = if (builtIn) preset.name else name,
    keySize = preset.keySize,
    keyGap = preset.keyGap,
    scalePercent = preset.scalePercent,
    textScalePercent = preset.textScalePercent,
    cornerRadiusEnabled = preset.cornerRadiusEnabled,
    cornerRadiusPercent = preset.cornerRadiusPercent,
    colors = preset.colors,
    outline = preset.outline,
    shadow = preset.shadow,
    opacity = preset.opacity,
    fontId = preset.fontId,
    animationMode = preset.animationMode,
    animationDurationSec = preset.animationDurationSec,
    showShiftKey = preset.showShiftKey,
    showMouseButtons = preset.showMouseButtons,
    mouseCpsEnabled = preset.mouseCpsEnabled,
    mouseCpsMode = preset.mouseCpsMode,
    cpsTextTemplate = preset.cpsTextTemplate,
    cpsTextTemplateMode1 = preset.cpsTextTemplateMode1,
    keyMappings = preset.keyMappings,
    live2d = preset.live2d,
    /*
     * ⚠️ 这里**故意不列 custom**（自定义 Key 的组件布局）。
     *
     * 与上面 Live2D 那次踩坑是同一类问题的镜像：`resetParamsToDefault` 是一份
     * "必须枚举全部字段"的清单，漏一个就是"某个设置重置不掉"。
     * 但"重置参数"对自定义 Key 的含义与别处不同 ——
     * 用户在编辑器里摆的布局**本身就是这份配置的主要内容**，
     * 把它一并抹掉等于删了用户的成品。
     *
     * 所以自定义布局的重置**单独放在编辑器的"恢复默认布局"按钮上**，
     * 让用户明确知道自己要丢掉的是什么。
     * 加新字段时请照着这个标准判断：它是"参数"还是"用户的创作"。
     */
)

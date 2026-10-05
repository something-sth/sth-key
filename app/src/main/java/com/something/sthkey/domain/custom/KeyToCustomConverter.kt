package com.something.sthkey.domain.custom

import com.something.sthkey.domain.config.CPS_PLACEHOLDER
import com.something.sthkey.domain.config.CPS_PLACEHOLDER_MODE1
import com.something.sthkey.domain.config.KeyStrokesConfig
import com.something.sthkey.domain.style.KeyBox
import com.something.sthkey.domain.style.KeyLayout
import com.something.sthkey.domain.style.StyleId

/**
 * 把「按键样式」的配置**转成**「自定义 Key 样式」。
 *
 * ============================================================
 * 这件事为什么值得做，以及它的难点在哪
 * ============================================================
 * 按键样式是一套**写死的布局**（W / ASD / 鼠标键 / 空格 / Shift，
 * 坐标全是常量），换来的是"专有开关"（显示 Shift、显示鼠标键、
 * CPS 模式、统一调色…）。自定义 Key 反过来：自由摆放，
 * 但没有那些专有开关。
 *
 * 转换就是**在两者之间换一边**：把写死的布局一次摊开成一个个组件，
 * 之后每个组件都能单独编辑，但再也回不去（所以界面上有确认弹窗）。
 *
 * ⚠️ **难点全在"转完看起来必须和原来一样"**。用户按下确认之后
 * 期待的是"同一块悬浮窗，只是现在能编辑了"。任何一处颜色、透明度、
 * 位置、字号对不上，他都会觉得这个功能是坏的 ——
 * 而且因为它不可逆，连退回都做不到。
 *
 * ============================================================
 * 坐标是**直接对应**的，不需要换算
 * ============================================================
 * 两边用的是同一套基础坐标系（基础像素、与 `KeyLayout` 的
 * BASE_WIDTH 体系一致）：`KeyBox` 的 `centerX` / `topY` / `width` / `height`
 * 与 [CustomComponent] 的 `x` / `y` / `width` / `height` 指的是同一件事，
 * 只是前者给的是**水平中心**，转换时要减去半个宽度。
 *
 * ⚠️ 配置里的 `keySize` / `keyGap` 两个字段**其实没有被布局使用**
 * （键边长与间距是 [KeyLayout] 里的常量）。所以这里不能拿它们算位置 ——
 * 那样会与屏幕上真实显示的位置对不上。位置一律从 [KeyLayout.keys] 取。
 *
 * ============================================================
 * 字号：两边基数不同，要补偿
 * ============================================================
 * - 按键样式的键面文字是 `35 × 文字缩放`；
 * - 自定义 Key 的组件文字基数是 [CustomLayout.textBaseSize] = 28。
 *
 * 所以直接搬 `textScalePercent` 会让文字**小 20%**。
 * 这里按比例补偿（35/28 = 1.25），让转过去的字号与原来一致，
 * 补偿后再夹进自定义 Key 允许的区间。
 */
object KeyToCustomConverter {

    /**
     * 转换后用来表示换行的**转义写法**（两个字符：反斜杠 + n）。
     *
     * ============================================================
     * 为什么用转义而不是真的换行符（这里改过一次）
     * ============================================================
     * 一开始用的是真的换行符，理由是"在输入框里按回车就行，不用记规则"。
     * 但文字输入框是**单行**的，于是第二行虽然存在却看不见 ——
     * 用户点那片空白还能改到文字，像是在编辑一块空气。
     *
     * 转义写法在输入框里就是两个可见字符 `\n`：所见即所得，
     * 也不会被误当成空行删掉。代价是要知道这个约定，
     * 而它已经是编程里最广为人知的写法之一。
     */
    const val LINE_BREAK = "\\n"

    /**
     * 把 CPS 那一行比主文字小多少（百分比），由布局常量推出。
     *
     * ⚠️ 用**四舍五入**而不是截断：`22/28 = 78.57%`，
     * 截断成 78% 会让实际字号变成 `28 × 0.78 = 21.84`，
     * 与按键样式的 22 差 0.16 —— 单看无所谓，但它会让
     * "转换后字号必须与原来一致"这条断言失败，说明我们对不齐。
     * 取整方向在这里是**可见的精度损失**，所以按最近值取。
     */
    val CPS_LINE_SCALE_PERCENT: Int = kotlin.math.round(
        KeyLayout.CPS_SECONDARY_TEXT_SIZE / KeyLayout.CPS_PRIMARY_TEXT_SIZE * 100f,
    ).toInt()

    /**
     * 按键样式键面文字的基数（见 [KeyLayout.textSize]）。
     *
     * 写在这里而不是引用那个函数：那个函数要的是整份 config，
     * 而这里只需要这个比例。
     */
    private const val KEY_TEXT_BASE = 35f

    /** 字号补偿比例：把按键样式的 35 基数折算成自定义 Key 的 28 基数 */
    private const val TEXT_SCALE_COMPENSATION = KEY_TEXT_BASE / CustomLayout.textBaseSize

    /**
     * 把"某个基准字号 × 文字缩放"折算成自定义 Key 的**文字缩放百分比**。
     *
     * ============================================================
     * ⚠️ CPS 模式 3 的主文字基数**不是 35**（这里错过一次）
     * ============================================================
     * 按键样式里：
     * - 普通键的文字是 `35 × 文字缩放`；
     * - **模式 3 是键内两行**，主文字用 [KeyLayout.CPS_PRIMARY_TEXT_SIZE]（28）、
     *   副文字用 [KeyLayout.CPS_SECONDARY_TEXT_SIZE]（22）—— 刻意比普通键小，
     *   设计初衷就是"防止文本过大不协调"。
     *
     * 一开始这里对所有模式都用 35 当基数，于是模式 3 转过去之后
     * **主文字大了 25%**（35 而不是 28），CPS 行也跟着一起大 ——
     * 表现为"转换后文本大小与别的键一样了"，把原本的设计丢了。
     *
     * @param sourceSize 按键样式在这个模式下用的基准字号
     */
    private fun textScalePercentFor(sourceSize: Float, percent: Int): Int =
        (sourceSize / CustomLayout.textBaseSize * percent).toInt()
            .coerceIn(CustomLayout.TEXT_SCALE_MIN, CustomLayout.TEXT_SCALE_MAX)

    /**
     * 转换结果。
     *
     * @param custom 新的自定义布局组件列表
     */
    data class Result(
        val custom: CustomLayoutSettings,
        /** 转换过程中的说明（例如"哪些专有开关被丢掉了"），给日志与界面用 */
        val notes: List<String>,
    )

    /**
     * 执行转换。
     *
     * 返回的是**组件列表**而不是整份配置：调用方在原有配置上
     * 只改 `styleId` 与 `custom` 两项，其余字段（id / 名称 / 描述 /
     * 整体缩放 / Live2D 设置…）原样保留 —— 这样"转换"不会顺手
     * 把用户没要求改的东西也改掉。
     */
    fun convert(config: KeyStrokesConfig): Result {
        val notes = mutableListOf<String>()

        /*
         * 直接复用**按键样式自己的布局函数**。
         *
         * 这是本功能忠实度的关键：位置、尺寸、哪些键存在，
         * 全部由 [KeyLayout.keys] 回答 —— 只要我们照着它摆组件，
         * 转出来的东西就与屏幕上的完全重合。
         *
         * ⚠️ 千万不要在这里重算一遍布局。那等于把 `keys()` 的逻辑抄一份，
         * 而两份迟早会分叉（改了键距、加了新键…），分叉的表现就是
         * "转换后的位置和之前不一样"。
         *
         * CPS 数值传空 map：这里只关心几何与文字模板，运行时的数字由
         * 渲染层按组件的键位另行计算。
         *
         * ============================================================
         * ⚠️ 这里**不要**把内容居中（曾经加过，是个 bug）
         * ============================================================
         * 自定义样式的窗口尺寸取**所有组件的包围盒**（`CustomLayout.bounds`），
         * 而渲染时 `CustomLayout.toWindow` 会**按包围盒的左上角平移一次**
         * （把内容挪到窗口原点）。所以位置这件事已经有人管了。
         *
         * 在转换器里再居中一遍，等于把内容**平移两次**：
         *
         *     组件被挪到 x=170（居中）
         *     → bounds.offsetX 也变成 170
         *     → 渲染时再减 170 → 画在 0
         *     → 但窗口只有 260 宽，而内容从 170 摆到 430
         *     → **右边一截落在窗口外被裁**
         *
         * 症状是"悬浮窗上最宽的空格与 Shift 右边短一截"，而编辑器里正常
         * （编辑器画的是 600×600 定位区，不显示窗口边界）。
         *
         * 真机上看到的"转换后在编辑器里偏左上"是**编辑器的取景**问题，
         * 该由编辑器把视口对准内容，而不是改数据。
         */
        val boxes = KeyLayout.keys(config)

        /*
         * ⚠️ 显式写 `buildList<CustomComponent>`:三个分支返回的是三种不同的
         * 具体类型（KeyComponent / TextComponent / JoystickComponent），
         * 不标注的话 Kotlin 推不出共同的元素类型。
         */
        val components = buildList<CustomComponent> {
            boxes.forEachIndexed { index, box ->
                /*
                 * ⚠️ **必须 `add(...)`**。改成 `when` 时漏掉它的话，
                 * `when` 的结果会被直接丢掉 —— 转出来的是**空布局**，
                 * 而编译器不会报错（`when` 作为语句是合法的）。
                 * 这个错被测试当场抓住（"expected 8 but was 0"）。
                 */
                add(
                    when {
                        /*
                         * ⚠️ 摇杆槽位**必须先判**，否则它会掉进下面的 `else`，
                         * 被转成一个"空标签的按键组件"（它的 `label` 是空、
                         * `codes` 也是空）—— 表现就是"转自定义之后摇杆变成了
                         * 一个不亮的空方块"。
                         *
                         * 摇杆在自定义样式里有自己的组件类型（[JoystickComponent]），
                         * 所以这里转成它，外观整段照搬配置里的摇杆设置。
                         */
                        box.slotId == KeyLayout.Id.JOYSTICK_LEFT ||
                            box.slotId == KeyLayout.Id.JOYSTICK_RIGHT ->
                            joystickComponentOf(box = box, config = config, index = index)

                        // 静态 CPS 行 → 文本组件（它本来就不参与按键点亮）
                        box.static -> textComponentOf(box = box, config = config, index = index)

                        else -> keyComponentOf(box = box, config = config, index = index)
                    },
                )
            }
        }

        /* 把"丢了什么"如实记下来 —— 弹窗里说过会失去专有选项，这里给出具体是哪些 */
        if (config.mouseCpsEnabled) {
            notes += when (config.mouseCpsMode) {
                1 -> "CPS 已写在鼠标键的文字里（模式 1）"
                2 -> "CPS 已转成单独的文本组件（模式 2）"
                // 模式 3 的两行靠换行符保留下来了，不是损失，所以如实说
                else -> "CPS 已作为第二行并入鼠标键的文字（模式 3，用换行保留两行）"
            }
        }
        notes += "转换后不再有「显示 Shift」「显示鼠标键」「CPS 模式」这些开关，" +
            "要增删按键请直接在自定义编辑器里加删组件"

        return Result(
            custom = CustomLayoutSettings(components = components),
            notes = notes,
        )
    }

    /*
     * ============================================================
     * 单个键位 → 组件
     * ============================================================
     */

    /**
     * 一个按键 → [KeyComponent]。
     *
     * ============================================================
     * ⚠️ CPS 必须用**模板**重建，不能照抄 `box.label`
     * ============================================================
     * `KeyLayout.keys()` 返回的 label 是**已经套过模板、替换成具体数字**的
     * （模式 1 的 `LMB 3`、模式 3 的副文字 `CPS: 5`）。
     * 直接拿它当组件文字，这个组件就永远停在"转换那一刻的数字"上 ——
     * 之后再也不会变。
     *
     * 所以开着 CPS 时要用配置里的**模板**重建文字，让占位符活着：
     * - 模式 1：`LMB` + `(cps2)` → 运行时展开成 `LMB 3`，为 0 时括号消失；
     * - 模式 3：键内两行 → 用**换行符**保留成两行。
     *
     * ============================================================
     * 关于"用 `\n` 换行"这件事
     * ============================================================
     * 用户提到过想用反斜杠 + n 来换行。这里**直接用真的换行符**，
     * 而不是引入 `\n` 这种转义写法，理由是：
     *
     * - 在输入框里按回车就是换行，**不需要记任何规则**。
     *   转义写法要额外解释"哪些字符是特殊的""怎么输入一个真的反斜杠"，
     *   而对一个按键标签来说，这些复杂度换不来任何好处；
     * - 换行符在 JSON 里本来就有标准转义（`\n`），存档、导出、
     *   配置包分享全都不需要额外处理。
     *
     * 代价只有一个：文字里**没法直接写反斜杠加 n 这两个字符**。
     * 对一个按键标签来说这个代价可以忽略。
     */
    private fun keyComponentOf(
        box: KeyBox,
        config: KeyStrokesConfig,
        index: Int,
    ): KeyComponent {
        val baseLabel = baseLabelOf(box, config)

        val label = when {
            !config.mouseCpsEnabled -> baseLabel
            !isMouseSlot(box.slotId) -> baseLabel

            config.mouseCpsMode == 1 ->
                baseLabel + config.cpsTextTemplateMode1.ifBlank { CPS_PLACEHOLDER_MODE1 }

            config.mouseCpsMode == 3 ->
                /*
                 * 键内两行 → 用**转义的换行符**（`\n` 两个字符）保留成两行。
                 *
                 * ⚠️ 刻意不用真的换行符：文字输入框是单行的，真的换行
                 * 会让第二行**看不见但存在** —— 用户点那片空白还能改到文字，
                 * 像是"在编辑一块空白"。转义写法在输入框里就是可见的 `\n`，
                 * 所见即所得，也不会被误当成空行删掉。
                 */
                baseLabel + LINE_BREAK + config.cpsTextTemplate.ifBlank { CPS_PLACEHOLDER }

            // 模式 2 的 CPS 是单独一行（静态组件），键面文字不带占位符
            else -> baseLabel
        }

        /*
         * 模式 3 是键内两行，主文字的基准字号**比普通键小**
         * （28 而不是 35）—— 设计初衷就是防止文本过大不协调。
         * 其余模式（含模式 1 的 `LMB(cps2)` 单行写法）用普通键的字号。
         */
        val twoLineCps = config.mouseCpsEnabled &&
            config.mouseCpsMode == 3 &&
            isMouseSlot(box.slotId)

        return KeyComponent(
            id = componentId(box.slotId, index),
            x = box.left,
            y = box.topY,
            width = box.width,
            height = box.height,
            style = styleOf(config, box.slotId),
            label = label,
            inputKeyCodes = box.codes,
            textScalePercent = textScalePercentFor(
                sourceSize = if (twoLineCps) {
                    KeyLayout.CPS_PRIMARY_TEXT_SIZE
                } else {
                    KEY_TEXT_BASE
                },
                percent = config.textScalePercent,
            ),
            /*
             * CPS 那一行要比主文字小 —— 比例由布局常量推出（22/28），
             * 而不是写死一个 79：哪天那边调了字号，这里跟着变。
             *
             * 不是两行布局时保持 100：没有"第二行"这回事，
             * 缩放它只会让模式 1 的单行 CPS 莫名其妙变小。
             */
            cpsTextScalePercent = if (twoLineCps) CPS_LINE_SCALE_PERCENT else 100,
            /*
             * 文字偏移与 Key 是**同一套语义**（基础坐标单位），所以原样搬运、
             * 不做换算 —— 转换后位置必须一致。
             *
             * ⚠️ 要带上**本槽位**的偏移：Key 那边是"全局 + 本槽位"叠加，
             * 只搬全局的话，用户在 Key 里单独调过的键（比如空格）
             * 转到自定义之后会跳回默认位置。
             */
            textOffsetX = config.textOffsetX + (config.slotTextOffsets[box.slotId]?.x ?: 0f),
            textOffsetY = config.textOffsetY + (config.slotTextOffsets[box.slotId]?.y ?: 0f),
            animationMode = config.animationMode,
            animationDurationSec = config.animationDurationSec,
        )
    }

    /**
     * 一个摇杆槽位 → [JoystickComponent]。
     *
     * ============================================================
     * ⚠️ 不处理的话它会变成一个"空标签的按键组件"
     * ============================================================
     * 摇杆槽位的 `label` 与 `codes` **都是空的**（见 `KeyLayout` 里构造它的地方），
     * 所以掉进普通按键那条路之后会得到一个**不亮的空方块** ——
     * 看起来就像"转自定义把摇杆弄丢了"。
     *
     * ============================================================
     * 几何:与按键组件同一套换算
     * ============================================================
     * `KeyBox` 给的是**水平中心**（`centerX`）与**顶部**（`topY`），
     * 而 [CustomComponent] 要的是左上角 —— 所以 x 要减半个宽度。
     * 这与 [keyComponentOf] 用的是同一个 `box.left`，不要另算一遍。
     *
     * ⚠️ `sizeScale` 与 `knobScale` **原样搬过去**，不预先乘进宽高:
     * 自定义画布里它们由渲染层乘（与「标准」样式同一条路），
     * 在这里乘一次的话渲染时会再乘一次 —— 那就是**平方**。
     *
     * ⚠️ 摇杆的监听对象由 `side` 决定，而两个槽位正好一一对应。
     */
    private fun joystickComponentOf(
        box: KeyBox,
        config: KeyStrokesConfig,
        index: Int,
    ): JoystickComponent = JoystickComponent(
        id = componentId(box.slotId, index),
        x = box.left,
        y = box.topY,
        width = box.width,
        height = box.height,
        side = if (box.slotId == KeyLayout.Id.JOYSTICK_RIGHT) StickSide.RIGHT else StickSide.LEFT,
        /*
         * 整段外观照搬 —— 用户的原话是"配置项要与'标准'样式相同",
         * 那么转换时**一个字段都不该丢**，否则转过去外观会变。
         */
        joystick = config.joystick,
    )

    /**
     * 键面上的**纯键名**（不含任何 CPS）。
     *
     * 从布局给的 label 里剥掉 CPS 部分：布局在模式 1 下会把它接在末尾，
     * 而我们要的是干净的键名，之后自己拼模板。
     *
     * 做法是直接取键位映射里的 `displayText` —— 那才是键名的真源，
     * 比从拼接结果里"反向截断"可靠得多（截断要猜模板长什么样）。
     */
    private fun baseLabelOf(box: KeyBox, config: KeyStrokesConfig): String =
        config.keyMappings.firstOrNull { it.id == box.slotId }?.displayText
            ?: box.label

    /** 这个槽位是不是鼠标键（只有它们会带 CPS） */
    private fun isMouseSlot(slotId: String): Boolean =
        slotId == KeyLayout.Id.LMB || slotId == KeyLayout.Id.RMB

    /**
     * 静态 CPS 行 → [TextComponent]。
     *
     * 它不属于任何按键，只是"显示某个键位的 CPS"，
     * 所以只能用文本组件，并且要**自己声明统计哪些键** ——
     * 这正是文本组件 [TextComponent.cpsKeyCodesPerPlaceholder] 的用途。
     */
    private fun textComponentOf(
        box: KeyBox,
        config: KeyStrokesConfig,
        index: Int,
    ): TextComponent {
        /*
         * 用配置里的 CPS 模板，而不是 box.label。
         *
         * `box.label` 是**已经替换过数字**的结果（转换这一刻的数值），
         * 拿它当文字的话，这个组件就永远停在那个数字上了。
         * 我们要的是模板 —— 它才是"持续显示 CPS"这件事本身。
         */
        val text = config.cpsTextTemplate.ifBlank { CPS_PLACEHOLDER }

        /*
         * 这一行显示哪个键位的 CPS：由槽位反查该位置绑定的键码。
         *
         * CPS_L / CPS_R 分别对应鼠标左右键，与 [KeyLayout] 里生成它们时的
         * 对应关系一致。
         */
        val codes = when (box.slotId) {
            KeyLayout.Id.CPS_L -> codesOf(config, KeyLayout.Id.LMB)
            KeyLayout.Id.CPS_R -> codesOf(config, KeyLayout.Id.RMB)
            else -> emptyList()
        }

        return TextComponent(
            id = componentId(box.slotId, index),
            x = box.left,
            y = box.topY,
            width = box.width,
            height = box.height,
            style = styleOf(config, box.slotId),
            text = text,
            cpsKeyCodesPerPlaceholder = listOf(codes.ifEmpty { DEFAULT_CPS_KEY_CODES }),
            textScalePercent = textScalePercentFor(
                sourceSize = KEY_TEXT_BASE,
                percent = config.textScalePercent,
            ),
            /*
             * 文字偏移与 Key 是**同一套语义**（基础坐标单位），所以原样搬运、
             * 不做换算 —— 转换后位置必须一致。
             *
             * ⚠️ 要带上**本槽位**的偏移：Key 那边是"全局 + 本槽位"叠加，
             * 只搬全局的话，用户在 Key 里单独调过的键（比如空格）
             * 转到自定义之后会跳回默认位置。
             */
            textOffsetX = config.textOffsetX + (config.slotTextOffsets[box.slotId]?.x ?: 0f),
            textOffsetY = config.textOffsetY + (config.slotTextOffsets[box.slotId]?.y ?: 0f),
        )
    }

    /*
     * ============================================================
     * 取值：颜色 / 透明度 / 描边 / 字体
     * ============================================================
     */

    /**
     * 把按键样式的整份外观搬到组件样式上。
     *
     * ⚠️ 每一个字段都要**按状态**对应（未按下 ↔ Up、按下 ↔ Down）。
     * 漏一个的表现是"转换后按下时的样子不对"，而且只在按下时看得到 ——
     * 很容易以为转换没问题。
     *
     * 文字阴影在按键样式里也有，所以一并搬过去（两边语义已经统一）。
     */
    private fun styleOf(config: KeyStrokesConfig, slotId: String): ComponentStyle = ComponentStyle(
        fillUp = config.colors.keyUp,
        fillDown = config.colors.keyDown,
        fillOpacityUp = config.opacity.keyUp,
        fillOpacityDown = config.opacity.keyDown,

        textUp = config.colors.textUp,
        textDown = config.colors.textDown,
        textOpacityUp = config.opacity.textUp,
        textOpacityDown = config.opacity.textDown,

        outlineEnabled = config.outline.enabled,
        outlineWidth = config.outline.width,
        outlineUp = config.colors.outlineUp,
        outlineDown = config.colors.outlineDown,
        outlineOpacityUp = config.opacity.outlineUp,
        outlineOpacityDown = config.opacity.outlineDown,

        shadowEnabled = config.shadow.enabled,
        shadowMode = config.shadow.mode,
        shadowSize = config.shadow.size,
        shadowUp = config.colors.shadowUp,
        shadowDown = config.colors.shadowDown,
        shadowOpacityUp = config.opacity.shadowUp,
        shadowOpacityDown = config.opacity.shadowDown,

        cornerRadiusEnabled = config.cornerRadiusEnabled,
        cornerRadiusPercent = config.cornerRadiusPercent,

        fontId = config.fontId,
        /*
         * ⚠️ 图片字体**必须一起搬**。
         *
         * 它和 `fontId` 是两个独立字段（常规字体必选 + 图片字体可选），
         * 只搬 `fontId` 的话，转换后**图片字体就丢了** ——
         * 表现是"转成自定义 Key 之后，键面文字变回矢量字体"，
         * 而用户明明在 Key 样式里选好了图片字体。
         *
         * 这个字段是后来才加的，`styleOf` 里当时漏了它；
         * 凡是"从配置构造组件样式"的地方都要检查一遍。
         */
        bitmapFontId = config.bitmapFontId,
        /*
         * 字间距 / 行间距也一起搬。
         *
         * Key 那边是"全局 + 本槽位"两层，而组件这边只有一层字段 ——
         * 所以在这里把两层加起来。转换后用户看到的是同一个间距，
         * 想改就改那一个滑块，不必再去想"是哪一层在生效"。
         */
        letterSpacing = config.textSpacing.letter +
            (config.slotTextSpacings[slotId]?.letter ?: 0f),
        lineSpacing = config.textSpacing.line +
            (config.slotTextSpacings[slotId]?.line ?: 0f),
    )

    /** 该槽位绑定的键码（CPS 文本组件要用它声明"数哪些键"） */
    private fun codesOf(config: KeyStrokesConfig, slotId: String): List<Int> =
        config.keyMappings.firstOrNull { it.id == slotId }?.inputKeyCodes.orEmpty()

    /**
     * 组件 id。
     *
     * 用槽位名（`W` / `LMB` / `CPS_L`）加序号：可读、稳定、且同一份配置里
     * 不会重复（槽位名本来就是唯一的，序号只是防御）。
     *
     * 可读很重要 —— 组件条上会显示这个 id 派生出来的东西，
     * 一串随机 uuid 对排查问题毫无帮助。
     */
    private fun componentId(slotId: String, index: Int): String =
        "conv_${slotId.lowercase()}_$index"
}

/**
 * `KeyBox` 的左边界。
 *
 * 布局里给的是**水平中心**（因为它按中心对称摆放），
 * 而组件要的是左上角 —— 差半个宽度。
 */
private val KeyBox.left: Float get() = centerX - width / 2f

/**
 * 判断一份配置能否转换。
 *
 * ============================================================
 * 内置配置不能转（与"删除"同一个待遇）
 * ============================================================
 * `Default` 是"配置被删光 / 数据损坏"时的兜底，必须永远保持出厂状态。
 * 而转换**不可逆** —— 一旦把它转成自定义 Key，用户就失去了那个
 * 唯一能退回去的地方。所以这里直接禁掉，界面上也做成不可点。
 *
 * 其余规则：只有**按键样式**能转。Live2D 是一只猫，它的配置里没有任何
 * 与"按键布局"有关的东西，转过去会得到一个空布局；
 * 已经是自定义 Key 的也没什么可转的。
 */
fun KeyStrokesConfig.canConvertToCustom(): Boolean {
    if (builtIn) return false

    return when (styleId) {
        StyleId.CUSTOM_KEY -> false
        StyleId.KEYBOARD_CAT -> false
        else -> true
    }
}

/**
 * 生成转换后的配置。
 *
 * ⚠️ 只改 `styleId` 与 `custom` 两项，其余原样保留 ——
 * 转换不该顺手改掉用户没要求改的东西（名称、描述、
 * 整体缩放、Live2D 设置…）。
 *
 * ⚠️ 这个操作**不可逆**：`styleId` 一改，编辑页就换成自定义 Key 那一套，
 * 「显示 Shift」这类专有开关连界面都没有了。所以界面上必须确认。
 */
fun KeyStrokesConfig.toCustomKeyStyle(): Pair<KeyStrokesConfig, List<String>> {
    val result = KeyToCustomConverter.convert(this)
    return copy(
        styleId = StyleId.CUSTOM_KEY,
        custom = result.custom,
    ) to result.notes
}

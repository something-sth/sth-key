package com.something.sthkey.domain.custom

import com.something.sthkey.domain.config.ColorSet
import com.something.sthkey.domain.config.CPS_PLACEHOLDER
import com.something.sthkey.domain.config.CPS_PLACEHOLDER_MODE1
import com.something.sthkey.domain.config.formatCpsTemplate
import com.something.sthkey.domain.keys.KeyCodes
import com.something.sthkey.domain.style.KeyLayout
import kotlin.math.roundToInt

/**
 * 自定义 Key 样式的**几何与取值规则**（单一真源）。
 *
 * ============================================================
 * 为什么单独抽一层
 * ============================================================
 * 与 [KeyLayout] 之于 Key 样式、[com.something.sthkey.domain.style.OverlayStyleRegistry]
 * 之于窗口尺寸是同一个角色。三处都要用这些规则，各写一遍必然分叉：
 *
 * 1. **悬浮窗**：算窗口多大、把组件画在哪；
 * 2. **配置预览**（编辑页/列表页的小图）；
 * 3. **编辑器画布**：等比缩放显示、画选中框。
 *
 * 这里只做纯计算，不碰 Android 与 Compose，便于直接写单元测试。
 *
 * ============================================================
 * 画布与窗口的关系
 * ============================================================
 * ```
 * 画布 = 所有组件边框的包围盒 + 四周留白（[canvasInset]）
 * 窗口宽 = 画布宽 × 整体缩放 + 内边距×2      （见 ui/overlay/OverlayContent.kt）
 * ```
 * 即"窗口尺寸跟着内容走"，与其它样式一致。
 * 副作用是**拖组件时窗口会跟着长**——这正是我们要的：
 * 放在右下角的东西不会被裁掉。代价是画布左上角恒为 (0,0)，
 * 所以编辑时要能整体平移（[normalize]），不允许出现负坐标。
 */
object CustomLayout {

    /**
     * 画布四周的留白（基础坐标）。
     *
     * 用途有两个，都不能省：
     * 1. 描边是**画在边框上**的（`Modifier.border` 向内），留白避免描边贴屏幕边被裁；
     * 2. 文字略微超出边框时不至于立刻被窗口裁掉。
     */
    const val canvasInset = 6f

    /**
     * 文字基础字号（基础坐标）。
     *
     * 取 28 而不是 Key 样式的 35：组件是可以缩小的，
     * 35 在 80×80 的键帽里略挤。实际大小还要乘组件自己的文字缩放。
     */
    const val textBaseSize = 28f

    /**
     * 文字缩放下限 / 上限（百分比）。
     *
     * ============================================================
     * ⚠️ 上限从 150 提到 300
     * ============================================================
     * 组件尺寸上限升到 1000 之后（见 [COMPONENT_SIZE_MAX]），
     * 150% 在 1000 边长的组件上就是"看不见的小字" ——
     * 字号必须能跟着组件一起变大。
     *
     * 下限保持 50:再小就没法读了，而"想更小"的正确做法是把**组件**调小
     * （两者的效果等价，但组件调小更直观）。
     */
    const val TEXT_SCALE_MIN = 50
    const val TEXT_SCALE_MAX = 300

    /**
     * CPS 那一行的额外缩放下限 / 上限（百分比）。
     *
     * 上限给到 200：它是在组件文字缩放**之上**再乘一次，
     * 所以即使组件本身缩到 50%，也能把 CPS 行放大回可用的大小。
     */
    const val CPS_LINE_SCALE_MIN = 1
    const val CPS_LINE_SCALE_MAX = 200

    /**
     * 描边粗细范围。
     *
     * ============================================================
     * ⚠️ 上限从 5 提到 20
     * ============================================================
     * 单位是**基础坐标**（与组件尺寸同一套），不是屏幕像素 ——
     * 所以组件能有 1000 边长，5 的描边在它上面根本看不出来。
     *
     * ⚠️ 与 Key 样式那边**不再同一区间**了:那边是"固定尺寸的键帽"，
     * 5 已经够；这边组件能大到 1000，必须能给出更粗的描边。
     * 这不是漂移，是两边的尺寸量级本来就不同。
     */
    const val OUTLINE_WIDTH_MIN = 0.5f
    const val OUTLINE_WIDTH_MAX = 20f

    /**
     * 阴影尺寸范围。
     *
     * ⚠️ 上限从 8 提到 30 —— 理由与 [OUTLINE_WIDTH_MAX] 完全一样
     * （组件能到 1000，8 的阴影在它上面看不见）。
     */
    const val SHADOW_SIZE_MIN = 0.5f
    const val SHADOW_SIZE_MAX = 30f

    /** 圆角程度范围（短边百分比） */
    const val CORNER_PERCENT_MIN = 0f
    const val CORNER_PERCENT_MAX = 50f

    /*
     * ============================================================
     * 画布尺寸
     * ============================================================
     */

    /**
     * 定位区边长（基础坐标）：**固定 600×600**，与窗口尺寸无关。
     *
     * ============================================================
     * 它是"坐标的取值范围"，不是"窗口多大"
     * ============================================================
     * 这两个概念早先混在一起（画布 = 窗口 = 内容包围盒），于是有两个真实后果：
     *
     * 1. **位置滑块的取值范围没有确定的上界**：画布尺寸取决于组件位置，
     *    而滑块上界又要反过来参考画布 —— 自我递归，只能靠瞎猜一个数；
     * 2. 用户把组件往外挪一点，**整个窗口就跟着长大**。
     *
     * 现在拆开了：
     *
     * ```
     * BASE_CANVAS = 600      定位区 —— X/Y 滑块的范围是 [−尺寸, 600]，永远不会变
     * bounds(components)     真正的悬浮窗范围 = 所有组件边框的包围盒
     * ```
     *
     * 滑块范围因此**与窗口尺寸解耦**，不再自我递归；而窗口本身贴着内容，
     * 不占多余的地方（这正是要的）。
     *
     * 编辑器里两个框都画出来：外层虚线 = 定位区，里面实线 = 窗口范围。
     */
    const val BASE_CANVAS = 600f

    /**
     * 所有组件的**最小外接矩形**（基础坐标）。
     *
     * ============================================================
     * 这就是悬浮窗的范围
     * ============================================================
     * 窗口尺寸 = 这个矩形，内容按 [offsetX]/[offsetY] 平移进窗口坐标系。
     * 于是"摆多少内容就占多大地方"，不再是一块固定的 600×600 空白。
     *
     * [offsetX]/[offsetY] 是包围盒左上角 —— 也就是**要把内容往左上挪多少**，
     * 才能让它对齐窗口的 (0,0)。组件允许是负坐标（贴边用），
     * 所以这个偏移是真实存在的，不能省。
     *
     * ⚠️ **没有组件时给 1×1**：窗口尺寸为 0 是 `WindowManager` 的无效参数，
     * 直接崩。给 1×1 得到的是一个看不见的点 —— 反正也没有内容可画。
     * 编辑器里会明确提示"没有组件 → 悬浮窗不会显示"。
     *
     * @param visible 有没有组件。没有时窗口退化成一个点
     */
    data class Bounds(
        val offsetX: Float,
        val offsetY: Float,
        val width: Float,
        val height: Float,
        val visible: Boolean,
    )

    fun bounds(components: List<CustomComponent>): Bounds {
        if (components.isEmpty()) {
            return Bounds(offsetX = 0f, offsetY = 0f, width = 1f, height = 1f, visible = false)
        }

        val left = components.minOf { it.x }
        val top = components.minOf { it.y }
        val right = components.maxOf { it.x + it.width }
        val bottom = components.maxOf { it.y + it.height }

        return Bounds(
            offsetX = left,
            offsetY = top,
            // 至少 1：尺寸为 0 的窗口在 WindowManager 里是无效参数
            width = (right - left).coerceAtLeast(1f),
            height = (bottom - top).coerceAtLeast(1f),
            visible = true,
        )
    }

    /**
     * 「搬进窗口坐标」的结果。
     *
     * 用具名类型而不是 `Pair`：调用点写着 `windowed.first` / `windowed.second`
     * 是读不出含义的，而这里两个字段一个都不能搞混（混了就是内容偏移）。
     */
    data class WindowContent(
        val components: List<CustomComponent>,
        val bounds: Bounds,
    )

    /**
     * 把全部组件搬进**窗口坐标系**，并给出窗口尺寸。
     *
     * ============================================================
     * 为什么要有这个函数（它挡的是一个很贵的错）
     * ============================================================
     * 窗口只覆盖内容的包围盒，而组件坐标的原点是**定位区**的左上角 ——
     * 两者不是同一个原点，所以绘制前必须把内容平移一次。
     *
     * 这个平移**极易写反**。写反（把 `−left` 当成位移）的后果是：
     * 内容被整体推出可视区，屏幕上只剩靠下的一小部分甚至什么都不剩 ——
     * 而编辑器画布里完全正常（那边不画在窗口里），所以极难定位。
     * 这个错误在项目里真实发生过一次。
     *
     * 所以这里把"平移"收成一个**纯函数**，让 [CustomLayoutWindowTest] 直接钉住
     * "每个组件都落在 [0, 窗口尺寸] 之内"这条不变量 ——
     * 符号写反时它会立刻失败，而不是等到用户报告"悬浮窗缺了一块"。
     *
     * 调用方因此**不需要、也不应该**自己算偏移。
     */
    fun toWindow(
        components: List<CustomComponent>,
        bounds: Bounds = bounds(components),
    ): WindowContent = WindowContent(
        components = components.map {
            it.movedTo(x = it.x - bounds.offsetX, y = it.y - bounds.offsetY)
        },
        bounds = bounds,
    )

    /*
     * ⚠️ 下面这两个函数**返回的是定位区尺寸（固定 600）**，不是窗口尺寸。
     *
     * 它们只该被编辑器画布与预览用（那两个地方要画"可摆放的范围"）。
     * **窗口尺寸必须走 [bounds]** —— 覆盖范围（`OverlayStyleRegistry.baseSizeOf`）
     * 用的是 bounds，不是这里。
     *
     * 两个函数保留成"接收 components 但忽略它"是刻意的：调用点不必改，
     * 而且签名里带着 components 能提醒读者"这里本来可能和内容有关"。
     */
    fun canvasWidth(components: List<CustomComponent>): Float = BASE_CANVAS

    fun canvasHeight(components: List<CustomComponent>): Float = BASE_CANVAS

    /**
     * 组件尺寸的合法范围（基础单位）。
     *
     * ============================================================
     * ⚠️ 上限为什么是 1000，而定位区只有 600
     * ============================================================
     * 用户的原话:"自定义编辑中组件的长宽上限应该放更大一些，
     * 反正我这边宽度限制 300 是完全不够的，怎么说也得加到 1000 吧"。
     *
     * ⚠️ 1000 > [BASE_CANVAS]（600）是**允许的**:定位区只决定
     * **X/Y 滑块的可选范围**（`[-尺寸, 600]`），不限制组件本身多大。
     * 一个 1000 宽的组件放在 x = -200 处，同样能让窗口覆盖到它 ——
     * `bounds()` 按所有组件的**包围盒**算窗口，与定位区无关。
     *
     * ⚠️ 编辑器画布会**按内容包围盒自动取景**（`fitScale`），
     * 所以组件超过定位区时会整体缩小显示，不会"跑出屏幕看不到"。
     */
    const val COMPONENT_SIZE_MIN = 20f
    const val COMPONENT_SIZE_MAX = 1000f

    /**
     * 组件坐标的合法范围。
     *
     * ============================================================
     * ⚠️ 改成**固定**范围，不再跟着组件尺寸变
     * ============================================================
     * 早先下界是 `-尺寸`（一个 200 宽的组件最小 x = -200），
     * 于是组件的**尺寸一改，X 滑块能拖到哪也跟着变**:
     * 用户把组件调小之后，原本合法的那一端会突然拖不到 ——
     * 而"滑块范围会莫名其妙变"是最难解释的一种界面行为。
     *
     * 现在固定 `-1000 .. 1000`:
     *
     * - 上下都留得比 [COMPONENT_SIZE_MAX]（1000）宽 ——
     *   1000 边长的组件也能完整摆进、摆出定位区;
     * - 与尺寸解耦，调宽高时 X/Y 滑块的范围**纹丝不动**。
     *
     * ⚠️ 允许负值是为了让组件能贴到画布外沿（甚至完全移出去）——
     * 窗口尺寸按 [bounds] 的包围盒算，组件在定位区外也能正常显示。
     */
    const val COORDINATE_MIN = -1000f
    const val COORDINATE_MAX = 1000f

    fun minCoordinate(): Float = COORDINATE_MIN

    fun maxCoordinate(): Float = COORDINATE_MAX

    /*
     * ⚠️ 坐标/尺寸的**修改函数**（`centeredOnCanvas` / `resizedTo` / `clampedToCanvas`）
     * 刻意**不放在这个 object 里**，而是在 `ComponentExtensions.kt` ——
     * 放在 object 里的话它们是"成员扩展函数"，只能写成 `CustomLayout.resizedTo(c, w, h)`，
     * 既不能 `import` 也不能 `component.resizedTo(...)` 调用。
     * 这个坑踩过一次（`Unresolved reference`），别再搬回来。
     */

    /** 两个矩形是否相交（诊断与"重叠提示"用） */
    fun overlaps(a: CustomComponent, b: CustomComponent): Boolean =
        a.x < b.x + b.width && b.x < a.x + a.width &&
            a.y < b.y + b.height && b.y < a.y + a.height

    /*
     * ============================================================
     * 外观取值
     * ============================================================
     * 颜色 + 透明度 → ARGB。规则与 [com.something.sthkey.domain.style.KeyStyleResolver]
     * 一致，但作用对象是组件自己的 [ComponentStyle] 而不是整份配置。
     */

    /** 键帽底色（ARGB） */
    fun fillColor(style: ComponentStyle, pressed: Boolean): Int = argb(
        rgb = if (pressed) style.fillDown else style.fillUp,
        opacityPercent = if (pressed) style.fillOpacityDown else style.fillOpacityUp,
    )

    /** 文字颜色（ARGB） */
    fun textColor(style: ComponentStyle, pressed: Boolean): Int = argb(
        rgb = if (pressed) style.textDown else style.textUp,
        opacityPercent = if (pressed) style.textOpacityDown else style.textOpacityUp,
    )

    /** 描边颜色（ARGB，按状态） */
    fun outlineColor(style: ComponentStyle, pressed: Boolean): Int = argb(
        rgb = if (pressed) style.outlineDown else style.outlineUp,
        opacityPercent = if (pressed) style.outlineOpacityDown else style.outlineOpacityUp,
    )

    /** 文字阴影颜色（ARGB，按状态） */
    fun shadowColor(style: ComponentStyle, pressed: Boolean): Int = argb(
        rgb = if (pressed) style.shadowDown else style.shadowUp,
        opacityPercent = if (pressed) style.shadowOpacityDown else style.shadowOpacityUp,
    )

    /** 阴影尺寸（dp）；未启用时返回 null —— 调用方据此决定画不画阴影层 */
    fun shadowSize(style: ComponentStyle): Float? =
        if (style.shadowEnabled) {
            style.shadowSize.coerceIn(SHADOW_SIZE_MIN, SHADOW_SIZE_MAX)
        } else {
            null
        }

    /** 描边宽度（dp）；未启用时返回 0 */
    fun outlineWidth(style: ComponentStyle): Float =
        if (style.outlineEnabled) {
            style.outlineWidth.coerceIn(OUTLINE_WIDTH_MIN, OUTLINE_WIDTH_MAX)
        } else {
            0f
        }

    /**
     * 圆角半径（dp）。
     *
     * 按**短边**的百分比换算，与 Key 样式的 `KeyStyleResolver.cornerRadius` 同规则：
     * 50% 就是胶囊/圆形、0% 是直角。
     *
     * @param shortSideDp 组件短边长度（已换算成 dp）
     */
    fun cornerRadius(style: ComponentStyle, shortSideDp: Float): Float {
        if (!style.cornerRadiusEnabled) return 0f
        val percent = style.cornerRadiusPercent.coerceIn(
            CORNER_PERCENT_MIN,
            CORNER_PERCENT_MAX,
        ) / 100f
        return shortSideDp * percent
    }

    /** 文字字号（基础坐标），已含组件自己的文字缩放 */
    fun textSize(textScalePercent: Int): Float =
        textBaseSize * textScalePercent.coerceIn(TEXT_SCALE_MIN, TEXT_SCALE_MAX) / 100f

    /*
     * ============================================================
     * 新建组件的默认几何
     * ============================================================
     * 新组件放在"当前内容的右下方"，而不是固定 (0,0)：
     * 固定位置的话它会**正好压在默认布局上**，用户以为没加成功。
     */

    /** 新组件的默认尺寸 */
    const val NEW_KEY_SIZE = 80f
    const val NEW_TEXT_WIDTH = 120f
    const val NEW_TEXT_HEIGHT = 30f

    /** 新组件相对现有内容的落点：放到最下面那个组件之下 */
    fun nextPlacement(
        existing: List<CustomComponent>,
        width: Float,
        height: Float,
    ): Pair<Float, Float> {
        if (existing.isEmpty()) return canvasInset to canvasInset

        val bottomMost = existing.maxOf { it.y + it.height }
        return canvasInset to (bottomMost + canvasInset)
    }

    private fun argb(rgb: Long, opacityPercent: Int): Int {
        val alpha = opacityPercent.coerceIn(0, 100) * 255 / 100
        return (alpha shl 24) or (rgb.toInt() and 0xFFFFFF)
    }

    /*
     * ============================================================
     * CPS 文本
     * ============================================================
     * 两种组件共用这一套：按键组件可以在键名下面挂一行 CPS，
     * 文本组件可以整块用来显示 CPS。
     */

    /**
     * 一个组件的文字里有没有 CPS 占位符。
     *
     * ⚠️ 这是"要不要显示 CPS"的**唯一判据** ——
     * 不再有 CpsBinding / "显示 CPS"开关那一套（见 [CustomComponent] 的说明）。
     * 文字里没有占位符就什么都不显示，这也意味着"关掉 CPS"就是**删掉占位符**。
     */
    fun hasCpsPlaceholder(text: String): Boolean =
        text.contains(CPS_PLACEHOLDER) || text.contains(CPS_PLACEHOLDER_MODE1)

    /**
     * 一个 CPS 占位符在文字里的位置与形态。
     *
     * @param index  第几个（从 0 起，**按在文字里出现的先后**）
     * @param start  在文字里的起始下标
     * @param isMode1 是 `(cps2)`（为 0 时连同空格一起隐藏）还是 `(cps)`
     */
    data class CpsPlaceholder(
        val index: Int,
        val start: Int,
        val isMode1: Boolean,
    ) {
        /** 占位符自身的长度 */
        val length: Int get() = if (isMode1) CPS_PLACEHOLDER_MODE1.length else CPS_PLACEHOLDER.length
    }

    /**
     * 扫出文字里**全部**的 CPS 占位符，按出现顺序。
     *
     * ============================================================
     * 为什么要支持多个（这里换过一次做法）
     * ============================================================
     * 早先一个组件只认**一个** CPS：文字里写几个占位符，
     * 它们全都显示同一个数值，而且"统计哪些键"只有一份配置。
     *
     * 但实际需求是"一个组件里显示多个不同的 CPS" ——
     * 例如 `LMB(cps) | RMB(cps)` 想分别数左右键。
     * 所以现在按**出现顺序**逐个对应：第 1 个占位符用第 1 组键位、
     * 第 2 个用第 2 组，以此类推（见 [cpsKeyCodesAt]）。
     *
     * ⚠️ 扫描要按 `indexOf` 逐个推进，**不能**用 indexOf 找第一个再找第二个 ——
     * 那样重复的占位符会一直返回同一个位置。
     */
    fun cpsPlaceholders(text: String): List<CpsPlaceholder> {
        if (!hasCpsPlaceholder(text)) return emptyList()

        val found = mutableListOf<CpsPlaceholder>()

        /*
         * 一趟扫完：每次取"两种占位符里更靠前的那个"。
         *
         * 之所以不能分两趟（先扫完 (cps) 再扫完 (cps2)）：那样得到的顺序是
         * "先全部 (cps)、再全部 (cps2)"，与它们在文字里真实的先后不符 ——
         * 而编号是按**文字顺序**给用户看的，顺序错了编号就会对不上。
         */
        var cursor = 0
        while (cursor < text.length) {
            val plain = text.indexOf(CPS_PLACEHOLDER, cursor).takeIf { it >= 0 }
            val mode1 = text.indexOf(CPS_PLACEHOLDER_MODE1, cursor).takeIf { it >= 0 }

            val next = when {
                plain == null && mode1 == null -> break
                plain == null -> mode1!!
                mode1 == null -> plain
                else -> minOf(plain, mode1)
            }
            val isMode1 = next == mode1

            found += CpsPlaceholder(index = found.size, start = next, isMode1 = isMode1)
            cursor = next + if (isMode1) CPS_PLACEHOLDER_MODE1.length else CPS_PLACEHOLDER.length
        }

        return found
    }

    /**
     * 这个组件显示**哪些键**的 CPS（返回输入键码列表）。
     *
     * - [KeyComponent]：它自己监听的那些键 —— 键位映射已经把它映射好了，
     *   用户不需要再选一次（需求里点名的做法）；
     * - [TextComponent]：它自己的 [TextComponent.cpsKeyCodes]，
     *   **每项对应一个占位符**（见 [cpsPlaceholders]）。
     *
     * 都用**输入键码**而不是槽位 id：翻译成槽位是渲染层的事
     * （`CpsCounter.slotOf` / `KeyLayout.codeToSlotMap`），
     * 这一层只回答"数哪些键"。
     */
    fun cpsKeyCodesOf(component: CustomComponent): List<Int> = when (component) {
        is KeyComponent -> component.inputKeyCodes
        is TextComponent -> component.cpsKeyCodesPerPlaceholder.flatten()
        /*
         * 摇杆**没有 CPS** —— 它显示的是摇杆位置，不是每秒次数。
         * 返回空列表就是"这个组件不统计任何键"，渲染层自然不会给它套 CPS 文字。
         */
        is JoystickComponent -> emptyList()
    }

    /**
     * 第 [index] 个占位符统计哪些键。
     *
     * 优先用新的"按占位符分组"字段；老配置里那一项是默认值、
     * 而旧的扁平 [TextComponent.cpsKeyCodes] 被设过时，用旧值兜底 ——
     * 否则升级后老用户配的键位就悄悄丢了。
     */
    fun cpsKeyCodesAt(component: CustomComponent, index: Int): List<Int> {
        if (component !is TextComponent) return cpsKeyCodesOf(component)

        val groups = component.cpsKeyCodesPerPlaceholder
        groups.getOrNull(index)?.let { return it }

        /*
         * 下标超出分组数：可能是老配置（只有扁平字段），
         * 也可能是用户刚新写了一个占位符还没配。
         * 两种情况都回落到第一组，行为一致、不会突然变成"数鼠标左键"。
         */
        return groups.firstOrNull()
            ?: component.cpsKeyCodes
            ?: DEFAULT_CPS_KEY_CODES
    }

    /**
     * 算出这个组件**第 [index] 个占位符**这一刻的 CPS 值。
     *
     * ============================================================
     * 按**键位**累加，并且先去重
     * ============================================================
     * 计数是按键位（槽位）记的：一个映射位置下绑了多个物理键时，
     * 它们**共享**同一个计数（左右 Shift 按哪个都算同一个位置）。
     *
     * 所以这里先按键位去重再求和。不去重的话，"左右 Shift 都选上"
     * 会把同一个位置的计数**加两遍** —— 用户看到 CPS 直接翻倍，
     * 而且只在多选时才出现，极难联想到是这里的问题。
     *
     * @param slotIdOf 键码 → 键位 id；调用方注入，保持本层是纯函数
     */
    fun cpsAt(
        component: CustomComponent,
        index: Int,
        cpsBySlot: Map<String, Int>,
        slotIdOf: (Int) -> String?,
    ): Int = cpsKeyCodesAt(component, index)
        .mapNotNull(slotIdOf)
        .distinct()
        .sumOf { cpsBySlot[it] ?: 0 }

    /**
     * 这个组件全部占位符的 CPS 值，按出现顺序。
     *
     * 按键组件只有一组键位（它监听的那些），所以它的多个占位符
     * 会得到**相同**的数值 —— 这是合理的：按键组件的语义就是"这一个键"。
     */
    fun cpsValuesOf(
        component: CustomComponent,
        cpsBySlot: Map<String, Int>,
        slotIdOf: (Int) -> String?,
    ): List<Int> = cpsPlaceholders(primaryTextOf(component))
        .map { cpsAt(component, it.index, cpsBySlot, slotIdOf) }

    /**
     * 把一个组件这一刻应该显示的文字算出来（**只有这一条路径**）。
     *
     * ============================================================
     * 为什么不再分"主文字 + CPS 行"
     * ============================================================
     * 早先渲染层是"主文字画一遍、CPS 行再画一遍"，于是文本组件会出现
     * `CPS: (cps)` 和 `CPS: 5` **两行同时显示** —— 因为主文字那一遍
     * 画的是没替换过的模板。这正是需求里要改掉的东西。
     *
     * 现在与 Key 样式完全一致：**整段文字替换一遍就完事**。
     * `LMB(cps2)` → `LMB`（为 0）或 `LMB 5`；`CPS: (cps)` → `CPS: 5`。
     * 一件事实只有一个出口，就不会有两行对不上的可能。
     *
     * @param cps 该组件这一刻的 CPS 值；调用方通过 [cpsOf] 算出
     */
    fun displayText(component: CustomComponent, cps: Int): String =
        renderCpsText(template = primaryTextOf(component), cps = cps)

    /**
     * 渲染最终文字：**每个占位符各用各的数值**。
     *
     * @param values 按出现顺序的 CPS 值；比占位符少时缺的那些按 0 处理
     *   （宁可显示 0，也不要因为数组短了而崩掉）
     */
    fun displayTextAt(component: CustomComponent, values: List<Int>): String =
        renderCpsTextAt(template = primaryTextOf(component), values = values)

    /** 组件的主体文字：按键组件是键面文字，文本组件是文本内容 */
    fun primaryTextOf(component: CustomComponent): String = when (component) {
        is KeyComponent -> component.label
        is TextComponent -> component.text
        /*
         * 摇杆**没有文字**。返回空串而不是报错:调用方（属性面板、
         * 转自定义的诊断、CPS 渲染）都会走到这里，
         * 让它返回空是"这个组件没有文字"最省事的表达。
         */
        is JoystickComponent -> ""
    }

    /**
     * 套用 CPS 模板（**整段替换**）。
     *
     * ============================================================
     * 两条分支最后都要 `trim()`
     * ============================================================
     * `(cps2)` 那条分支去掉占位符后会留下多余空格（`LMB(cps2)` 展开成 `" 5"`），
     * 这是刻意的 —— 但**整段文字的头尾空格必须去掉**：
     * 用一个空格把文字挤偏，正是用户要拿"文字偏移"去救的东西，
     * 不该由我们自己制造。而且多余的空白还会让"空白模板"看起来非空
     * （测试抓到过 `"   "` 被判成有内容）。
     *
     * 两种占位符是**二选一**分派，不是"两个都跑一遍"：
     * `formatCpsTemplate` 在 `hideWhenZero` 时把整段模板清空，
     * 那是给 Key 样式模式 1 设计的（那边模板里可能只有占位符），
     * 这里必须保留 `(cps2)` 周围的其它文字（`CPS: (cps2)` → `CPS:`）。
     */
    /**
     * 套用 CPS 模板（单个数值，所有占位符显示同一个值）。
     *
     * ⚠️ 它现在只是 [renderCpsTextAt] 的一层薄封装 —— **不要再在这里
     * 写第二套替换逻辑**。两种渲染路径并存过一次，结果就是
     * 多处占位符的行为只在新路径上是对的，另一处悄悄不一致。
     *
     * 保留它是为了给"只有一个数值"的调用方（按键样式、诊断日志）省事。
     */
    fun renderCpsText(template: String, cps: Int): String =
        renderCpsTextAt(template, listOf(cps))

    /**
     * 套用 CPS 模板，**每个占位符用各自的数值**。
     *
     * ============================================================
     * ⚠️ 必须**从右往左**替换（这里有两个坑，都踩过）
     * ============================================================
     * **坑一：下标会失效。** 占位符的位置是在**原始模板**上算出来的；
     * 一旦先替换了左边那个，右边的下标就全部错位了。
     * 用 `replaceFirst` 更是错上加错 —— 它替换的是"当前第一个"，
     * 与"第几个占位符"根本不是一回事。
     * **从右往左**替换时，左边的位置不受影响，下标始终有效。
     *
     * **坑二：`(cps)` 是 `(cps2)` 的前缀。** 直接对整串做
     * `replace("(cps)", …)` 会把 `(cps2)` 截成 `"2"`。
     * 扫描时**先看 `(cps2)`**（更长、更具体）就没有这个问题 ——
     * 见 [cpsPlaceholders] 里"取更靠前的那个"的写法。
     */
    fun renderCpsTextAt(template: String, values: List<Int>): String {
        val placeholders = cpsPlaceholders(template)
        if (placeholders.isEmpty()) return template.trim()

        fun valueAt(index: Int): Int = values.getOrElse(index) { 0 }

        var result = template
        // 从右往左，左边的下标就不会被前面的替换影响
        placeholders.asReversed().forEach { placeholder ->
            val cps = valueAt(placeholder.index)
            val replacement = if (placeholder.isMode1) {
                // 模式 1 自带前导空格，为 0 时整段消失
                if (cps <= 0) "" else " $cps"
            } else {
                cps.toString()
            }
            result = result.replaceRange(
                placeholder.start,
                placeholder.start + placeholder.length,
                replacement,
            )
        }

        return result.trim()
    }

    /**
     * 早期格式兼容：把老的"CPS 槽位"翻译成键码。
     *
     * 早期文本组件只能选左键/右键，存的是 `cpsSlot = "LMB"`。
     * 现在改成一个键码字段，读到旧值时翻译过来 ——
     * 否则升级后那一行 CPS 会从"数右键"悄悄变成"数左键"。
     */
    fun legacyKeyCodeOf(slot: String?): Int? = when (slot) {
        LEGACY_CPS_SLOT_LEFT -> KeyCodes.BTN_LEFT
        LEGACY_CPS_SLOT_RIGHT -> KeyCodes.BTN_RIGHT
        else -> null
    }

    /**
     * 这份布局里有没有用到 CPS。
     *
     * 编辑器与预览用它决定要不要起一个定时器刷新数字 ——
     * 没用到的布局不该每秒被唤醒十次。
     *
     * 判据就是"有没有哪个组件的文字里写了占位符"，与渲染**完全同一个判据**
     * （[hasCpsPlaceholder]）：两边用不同的判据，就会出现
     * "定时器在跑但屏幕上没东西"或者反过来"数字不动"。
     */
    fun usesCps(components: List<CustomComponent>): Boolean =
        components.any { hasCpsPlaceholder(primaryTextOf(it)) }

    /*
     * ============================================================
     * 与 Key 样式的颜色互转
     * ============================================================
     * 新建自定义 Key 组件时，从配置的 [ColorSet] 继承一套配色，
     * 用户就不必从"纯黑键帽 + 白字"开始调。
     */

    /** 用 Key 样式的配色作为组件的初始主题 */
    fun styleFromConfigColors(colors: ColorSet, fontId: String): ComponentStyle = ComponentStyle(
        fillUp = colors.keyUp,
        fillDown = colors.keyDown,
        textUp = colors.textUp,
        textDown = colors.textDown,
        fontId = fontId,
    )
}

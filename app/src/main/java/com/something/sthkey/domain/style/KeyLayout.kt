package com.something.sthkey.domain.style

import com.something.sthkey.domain.config.CPS_PLACEHOLDER
import com.something.sthkey.domain.config.CPS_PLACEHOLDER_MODE1
import com.something.sthkey.domain.config.KeyStrokesConfig
import com.something.sthkey.domain.config.formatCpsTemplate

/**
 * 悬浮窗上的一个键位（坐标全部在**基础坐标系**里）。
 *
 * @param slotId   位置标识（W / A / S / D / SPACE / SHIFT / LMB / RMB / CPS_L / CPS_R）
 * @param label    显示文字（已套用 CPS 模板）
 * @param subLabel 副文字（CPS 模式 3 用：主文字下面再显示一行）
 * @param codes    这个位置绑定的所有输入键码（任意一个按下都算按下）
 * @param centerX  水平中心（基础单位 = 物理像素）
 * @param topY     顶部 Y（基础单位 = 物理像素）
 * @param width    宽（基础单位）
 * @param height   高（基础单位）
 * @param static   是否为静态显示（CPS 行这种只显示数字、不参与按键点亮）
 */
data class KeyBox(
    val slotId: String,
    val label: String,
    val codes: List<Int>,
    val centerX: Float,
    val topY: Float,
    val width: Float,
    val height: Float,
    val subLabel: String? = null,
    val static: Boolean = false,
)

/**
 * 按键布局（预览与悬浮窗的唯一真源）。
 *
 * ============================================================
 * 为什么用"固定坐标系 + 整体缩放"
 * ============================================================
 * 这是照搬旧项目 [KeyOverlayView] 的做法，也是刻意的：
 *
 * - 所有键位坐标写死在 300 × 420 的坐标系里，**质量与间距都是常量**
 *   （键 80、间距 10、空格/Shift 高 55、鼠标键 125 宽）；
 * - "整体缩放"不改这些常量，而是整体缩放绘制并同步放大/缩小窗口。
 *
 * 好处是缩放不会改变布局关系 —— 不会出现"缩小之后被裁掉一角"
 * 或者"空格和 WASD 不再对齐"这类问题。
 * 反过来，如果让按键尺寸直接参与布局计算（我上一版就是这么写的），
 * 一旦尺寸与间距的比例变化，整个布局就得重算，极容易出错。
 *
 * 基础单位与**物理像素**是 1:1 的 —— 这一点很关键：
 * 旧项目直接用 `WindowManager.LayoutParams(BASE_WIDTH, BASE_HEIGHT, …)`，
 * 那两个值就是像素。如果按 dp 理解，在 2.75 密度的手机上会放大近 3 倍，
 * 悬浮窗会大到没法用。
 *
 * 因此：布局常量都以像素思考，绘制时再除以屏幕密度换算成 Compose 需要的 dp。
 * ============================================================
 */
object KeyLayout {

    /** 基础坐标系宽度（像素） */
    const val BASE_WIDTH = 300f

    /**
     * 基础坐标系高度（像素）。
     *
     * ⚠️ 这是**兜底值**，实际窗口高度请用 [baseHeight]。
     *
     * 旧项目固定用 420，但那是"默认配置下够用"的经验值：
     * 一旦同时开启 Shift + CPS 模式 2/3，内容高度会到 530 以上，
     * 窗口却还是 420 —— 底部直接被裁掉（旧项目也有这个 bug）。
     */
    const val BASE_HEIGHT = 420f
    /** 键位标识 */
    object Id {
        const val W = "W"
        const val A = "A"
        const val S = "S"
        const val D = "D"
        const val SPACE = "SPACE"
        const val SHIFT = "SHIFT"
        const val LMB = "LMB"
        const val RMB = "RMB"

        /** CPS 模式 2 专用的两个静态显示位（不参与按键点亮） */
        const val CPS_L = "CPS_L"
        const val CPS_R = "CPS_R"
    }

    /*
     * ============================================================
     * 布局常量（照搬旧项目 KeyOverlayView.drawKeyStrokesStyle）
     * ============================================================
     */

    /** 普通键边长 */
    private const val KEY_SIZE = 80f

    /** 键间距 */
    private const val GAP = 10f

    /** 顶部留白 */
    private const val TOP_MARGIN = 10f

    /** 长条键（空格 / Shift）高度 */
    private const val LONG_KEY_HEIGHT = 55f

    /** 鼠标键高度（CPS 模式 3 时增高） */
    private const val MOUSE_KEY_HEIGHT_CPS3 = 110f

    /** 圆角半径不参与缩放计算，只影响绘制 */

    /*
     * ============================================================
     * 缩放曲线
     * ============================================================
     * 滑块刻度仍是 50%–200%，但**实际倍率不是简单的 percent/100**。
     *
     * 原因：滑块拉到 50% 时按 0.5 倍画，在真机上小到几乎看不清，
     * 用户反馈"最小值太小"。因此把整条曲线整体上移：
     *
     *   滑块  50%  → 实际 0.90 倍（旧版 50% 的样子太小的那档，现在对应 0.9）
     *   滑块 100%  → 实际 1.27 倍
     *   滑块 200%  → 实际 2.00 倍（上限不变）
     *
     * 公式：0.9 + (percent - 50) / 150 × 1.1
     * ============================================================
     */

    /** 滑块最小值 */
    const val SCALE_PERCENT_MIN = 50

    /** 滑块最大值 */
    const val SCALE_PERCENT_MAX = 200

    /** 滑块最小时对应的实际倍率 */
    private const val SCALE_AT_MIN = 0.9f

    /** 滑块最大时对应的实际倍率（上限保持不变） */
    private const val SCALE_AT_MAX = 2.0f

    /**
     * 整体缩放倍率（把滑块百分比映射成实际倍率）。
     *
     * 它**不改变任何布局常量**，只决定画多大、窗口多大。
     * 悬浮窗与预览都走这里，因此两边的缩放始终一致。
     */
    fun uiScale(config: KeyStrokesConfig): Float {
        val percent = config.scalePercent
            .coerceIn(SCALE_PERCENT_MIN, SCALE_PERCENT_MAX)
        val t = (percent - SCALE_PERCENT_MIN).toFloat() /
            (SCALE_PERCENT_MAX - SCALE_PERCENT_MIN).toFloat()
        return SCALE_AT_MIN + t * (SCALE_AT_MAX - SCALE_AT_MIN)
    }

    /** 文字缩放倍率 */
    fun textScale(config: KeyStrokesConfig): Float =
        config.textScalePercent.coerceIn(50, 150) / 100f

    /** 字号（基础单位）；旧项目固定 35f × 文字缩放 */
    fun textSize(config: KeyStrokesConfig): Float = 35f * textScale(config)

    /*
     * ============================================================
     * CPS 模式 3 的"键内两行"字号
     * ============================================================
     * 主文字比普通键略小（给副文字腾位置），副文字再小一点。
     * 提成常量是为了让**转换到自定义 Key**时能算出正确的比例 ——
     * 那边只有"主文字缩放"与"CPS 行缩放"两个值，
     * 写死一个比例的话，这里改了字号那边就跟不上了。
     */
    const val CPS_PRIMARY_TEXT_SIZE = 28f
    const val CPS_SECONDARY_TEXT_SIZE = 22f

    /*
     * 这里原本还有 windowWidthPx / windowHeightPx 两个"窗口该多大"的函数。
     *
     * 它们已经被删除，因为有了第二种样式之后，"窗口尺寸"不能再由按键布局单独决定：
     * 统一入口是 [OverlayStyleRegistry.baseSizeOf]，由**样式自己**声明内容尺寸。
     * 留着这两个函数就是个陷阱 —— 谁顺手用了它，Live2D 配置就会按按键的尺寸开窗口。
     * （顺带一提，windowHeightPx 忽略了 CPS 参数，本身就是旧 bug 的来源。）
     */

    /**
     * 内容实际需要的高度（基础单位，未缩放）。
     *
     * 按"画到哪算到哪"计算：每行取该行最高的键，行间加上间距，
     * 顶部再加一个 [TOP_MARGIN] 的留白。
     *
     * 为什么不继续用固定的 420：那个值在"Shift + CPS 模式 2"同时开启时不够，
     * 窗口会把底部的 CPS 行裁掉。按内容算就不会再有这个问题，
     * 而且配置关掉 Shift / CPS 时窗口也会相应变矮，观感更紧凑。
     */
    fun baseHeight(
        config: KeyStrokesConfig,
        cpsBySlot: Map<String, Int> = emptyMap(),
    ): Float {
        val allKeys = keys(config, cpsBySlot)
        if (allKeys.isEmpty()) return BASE_HEIGHT

        // 按行归组：topY 相同的键属于同一行
        val rowTops = allKeys.map { it.topY }.distinct().sorted()
        val rowsHeight = rowTops.sumOf { topY: Float ->
            allKeys.filter { it.topY == topY }.maxOf { it.height }.toDouble()
        }.toFloat()

        val gaps = GAP * (rowTops.size - 1).coerceAtLeast(0)
        return rowsHeight + gaps + TOP_MARGIN
    }

    /**
     * 基础单位 → dp 的换算系数。
     *
     * 布局坐标是像素，Compose 要 dp，所以除以屏幕密度。
     * 悬浮窗与预览都用它，保证两边一致。
     */
    fun pxToDpFactor(density: Float): Float = 1f / density.coerceAtLeast(0.1f)

    /**
     * 生成全部键位（基础坐标系）。
     *
     * 布局顺序与旧项目一致：W / ASD / 鼠标左右键 /（模式 2 时的 CPS 行）/ 空格 /（可选 Shift）。
     *
     * @param cpsBySlot 各位置的当前 CPS，键为 [Id.LMB] / [Id.RMB]；
     *   只有开启 CPS 且模式为 1/3 时才会影响文字（模式 1 附加到主文字，
     *   模式 3 作为副文字显示在键内）
     */
    fun keys(
        config: KeyStrokesConfig,
        cpsBySlot: Map<String, Int> = emptyMap(),
    ): List<KeyBox> {
        val center = BASE_WIDTH / 2f

        fun mappingOf(id: String) = config.keyMappings.firstOrNull { it.id == id }

        fun labelOf(id: String): String = mappingOf(id)?.displayText ?: id

        fun codesOf(id: String): List<Int> = mappingOf(id)?.inputKeyCodes.orEmpty()

        /** 该位置的 CPS（未开启时为 0） */
        fun cpsOf(id: String): Int =
            if (config.mouseCpsEnabled) cpsBySlot[id] ?: 0 else 0

        val boxes = mutableListOf<KeyBox>()

        /* W */
        boxes += KeyBox(
            slotId = Id.W,
            label = labelOf(Id.W),
            codes = codesOf(Id.W),
            centerX = center,
            topY = TOP_MARGIN,
            width = KEY_SIZE,
            height = KEY_SIZE,
        )

        /* A S D */
        val asdY = KEY_SIZE + GAP + TOP_MARGIN
        listOf(Id.A to -1f, Id.S to 0f, Id.D to 1f).forEach { (id, offset) ->
            boxes += KeyBox(
                slotId = id,
                label = labelOf(id),
                codes = codesOf(id),
                centerX = center + offset * (KEY_SIZE + GAP),
                topY = asdY,
                width = KEY_SIZE,
                height = KEY_SIZE,
            )
        }

        /* 鼠标左右键 */
        val mouseY = asdY + KEY_SIZE + GAP
        val mouseWidth = KEY_SIZE * 1.5f + GAP * 0.5f
        var spaceY = mouseY

        if (config.showMouseButtons) {
            // 模式 3：把 CPS 显示在键**内部**，因此键要加高以容纳两行文字
            val mouseHeight = if (config.mouseCpsEnabled && config.mouseCpsMode == 3) {
                MOUSE_KEY_HEIGHT_CPS3
            } else {
                KEY_SIZE
            }

            /**
             * 主文字与副文字。
             *
             * - 模式 1：CPS 直接接在主文字后面（模板用 `(cps2)` 占位符，
             *   为 0 时整段不显示 —— 这也是默认模板能直接用的原因）
             * - 模式 3：键内分两行，第一行主文字、第二行套 `(cps)` 模板
             * - 模式 2：主文字不变，CPS 单独占一行（在下面）
             */
            fun mainLabel(id: String): String {
                val base = labelOf(id)
                return if (config.mouseCpsEnabled && config.mouseCpsMode == 1) {
                    base + formatCpsTemplate(
                        template = config.cpsTextTemplateMode1,
                        cps = cpsOf(id),
                        placeholder = CPS_PLACEHOLDER_MODE1,
                        hideWhenZero = true,
                    )
                } else {
                    base
                }
            }

            fun subLabel(id: String): String? =
                if (config.mouseCpsEnabled && config.mouseCpsMode == 3) {
                    formatCpsTemplate(config.cpsTextTemplate, cpsOf(id))
                } else {
                    null
                }

            boxes += KeyBox(
                slotId = Id.LMB,
                label = mainLabel(Id.LMB),
                subLabel = subLabel(Id.LMB),
                codes = codesOf(Id.LMB),
                centerX = center - mouseWidth / 2f - GAP / 2f,
                topY = mouseY,
                width = mouseWidth,
                height = mouseHeight,
            )
            boxes += KeyBox(
                slotId = Id.RMB,
                label = mainLabel(Id.RMB),
                subLabel = subLabel(Id.RMB),
                codes = codesOf(Id.RMB),
                centerX = center + mouseWidth / 2f + GAP / 2f,
                topY = mouseY,
                width = mouseWidth,
                height = mouseHeight,
            )

            spaceY = when {
                // 模式 2：鼠标键下面单独一行显示 CPS（静态，不随按键点亮）
                config.mouseCpsEnabled && config.mouseCpsMode == 2 -> {
                    val cpsY = mouseY + KEY_SIZE + GAP
                    boxes += KeyBox(
                        slotId = Id.CPS_L,
                        label = formatCpsTemplate(config.cpsTextTemplate, cpsOf(Id.LMB)),
                        codes = emptyList(),
                        centerX = center - mouseWidth / 2f - GAP / 2f,
                        topY = cpsY,
                        width = mouseWidth,
                        height = KEY_SIZE,
                        static = true,
                    )
                    boxes += KeyBox(
                        slotId = Id.CPS_R,
                        label = formatCpsTemplate(config.cpsTextTemplate, cpsOf(Id.RMB)),
                        codes = emptyList(),
                        centerX = center + mouseWidth / 2f + GAP / 2f,
                        topY = cpsY,
                        width = mouseWidth,
                        height = KEY_SIZE,
                        static = true,
                    )
                    cpsY + KEY_SIZE + GAP
                }

                config.mouseCpsEnabled && config.mouseCpsMode == 3 -> mouseY + mouseHeight + GAP

                else -> mouseY + KEY_SIZE + GAP
            }
        }

        /* 空格 */
        boxes += KeyBox(
            slotId = Id.SPACE,
            label = labelOf(Id.SPACE),
            codes = codesOf(Id.SPACE),
            centerX = center,
            topY = spaceY,
            width = KEY_SIZE * 3f + GAP * 2f,
            height = LONG_KEY_HEIGHT,
        )

        /* Shift */
        if (config.showShiftKey) {
            boxes += KeyBox(
                slotId = Id.SHIFT,
                label = labelOf(Id.SHIFT),
                codes = codesOf(Id.SHIFT),
                centerX = center,
                topY = spaceY + LONG_KEY_HEIGHT + GAP,
                width = KEY_SIZE * 3f + GAP * 2f,
                height = LONG_KEY_HEIGHT,
            )
        }

        return boxes
    }

    /**
     * 输入键码 → 键位标识。
     *
     * 一个位置可以绑定多个物理键（例如 Shift 同时绑左右 Shift），
     * 因此这里是多对一映射。
     */
    fun codeToSlotMap(config: KeyStrokesConfig): Map<Int, String> = buildMap {
        config.keyMappings.forEach { mapping ->
            mapping.inputKeyCodes.forEach { code ->
                put(code, mapping.id)
            }
        }
    }
}

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

        /**
         * 「手柄（标准）」样式里的**左摇杆**槽位。
         *
         * ============================================================
         * ⚠️ 它取代的是 W / A / S / D 四个位置
         * ============================================================
         * gamepad2 的布局**就是键盘样式**:同样的行结构、同样的鼠标键与
         * 空格、同样的字号描边 CPS —— 唯一区别是 WASD 那块换成左摇杆。
         *
         * 所以这里让 [KeyLayout.keys] 在 gamepad2 下**吐一个摇杆槽位**
         * 代替那四个键，而不是让渲染层自己去算位置。
         *
         * 好处是**下面所有行的位置自动正确** —— 鼠标键、空格、Shift 的
         * `topY` 都从"WASD 块的高度"推出来，换了高度它们自然跟着挪。
         * 渲染层只需要认槽位、画摇杆。
         *
         * ⚠️ 它**不参与键位映射**（摇杆没有"按下的键码"）——
         * 见 [KeyLayout.mappedKeys]。
         */
        const val JOYSTICK_LEFT = "JOYSTICK_LEFT"

        /**
         * 「手柄（标准）」样式里的**右摇杆**槽位。
         *
         * 与 [JOYSTICK_LEFT] 同一行，落在右列（`RMB` / `RT`）的正上方。
         * 两边**边长相同、位置对称**，见 [KeyLayout.keys] 里的布局图。
         */
        const val JOYSTICK_RIGHT = "JOYSTICK_RIGHT"

        /**
         * 「手柄（标准）」样式里的 **A 键**（下方位键）。
         *
         * ⚠️ 它**不是**键盘样式的 `A`（那个 id 是 `"A"`，WASD 的左移键）
         * —— 两个完全不同的东西，所以另起一个 id。
         *
         * 手柄上 A 是"跳跃"，是这个样式里唯一一个不在摇杆 / 扳机里的
         * 常用键，所以给它单独的槽位与开关。
         */
        const val A_BUTTON = "A_BUTTON"

        /** 「手柄（标准）」样式里的**左肩键**（LB） */
        const val SHOULDER_L = "SHOULDER_L"

        /** 「手柄（标准）」样式里的**右肩键**（RB） */
        const val SHOULDER_R = "SHOULDER_R"
    }

    /**
     * 这个样式是不是"键盘布局 + 左摇杆"（即 gamepad2）。
     *
     * ⚠️ 判据是 **styleId**，不是"有没有手柄在连" ——
     * 它是配置的属性，建配置时就定了，之后不会变。
     *
     * 放这里而不是让 UI 层各处写 `if (styleId == GAMEPAD2)`：
     * 那个判断在三个地方要用（布局、渲染、键位映射），
     * 散着写迟早有一处漏掉。
     */
    fun usesJoystickLayout(config: KeyStrokesConfig): Boolean =
        config.styleId == StyleId.GAMEPAD2

    /*
     * ============================================================
     * 布局常量（照搬旧项目 KeyOverlayView.drawKeyStrokesStyle）
     * ============================================================
     */

    /**
     * 普通键的**基准**边长。
     *
     * ⚠️ 真正的键宽是**算出来的**（`(260 − 2 × 间距) / 3`），不再是这个常量 ——
     * 因为间距可调（见 [COLUMN_SPAN]）。这个值只在两处还用得到:
     *
     * 1. `LONG_KEY_HEIGHT` 之类"与键宽无关"的比例参照；
     * 2. 文档里说明"默认长什么样"。
     *
     * 间距 = 10 时算出的键宽正好是 80 —— 也就是**默认外观一点没变**。
     */
    private const val KEY_SIZE = 80f

    /** 键间距 */
    private const val GAP = 10f

    /** 顶部留白 */
    private const val TOP_MARGIN = 10f

    /** 长条键（空格 / Shift / A / 扳机 / 肩键）高度 */
    private const val LONG_KEY_HEIGHT = 55f

    /**
     * 三列的**总跨度**（左列最左沿 → 右列最右沿）。
     *
     * = `2 × 125 + 10` = **260** —— 也就是"间距为默认值时"的样子。
     *
     * ⚠️ 它是**固定**的:间距滑块只改"键与键之间松紧多少"，
     * 不改这个跨度。于是窗口宽度恒为 [BASE_WIDTH]，用户的悬浮窗位置不会失效。
     * 见 `keys()` 里那段推导。
     */
    private const val COLUMN_SPAN = 260f

    /**
     * 按键间距百分比的滑块范围。
     *
     * ============================================================
     * ⚠️ 这三个地方**必须用同一份**（所以它是 public 的）
     * ============================================================
     * | 用它的地方 | 干什么 |
     * |---|---|
     * | `KeyLayout.keys()` | 按百分比算实际间距（**并夹到这个范围**） |
     * | 设置页的滑块 | 决定用户能拖到哪 |
     * | `JsonConfigCodec` 读取 | 把存下来的值夹回来 |
     *
     * 历史上它们是**各写一份**的，于是出过这个 bug:
     * 滑块下限改成了 0，而读取那边还是旧的 `50` ——
     * 用户设成 0~49、存进去了，**重进又被夹回 50**，
     * 表现是"按键间距配置没有持久化"。
     *
     * ⚠️ 下限是 **0**（键挨在一起），不是"把键缩小" ——
     * 用户专门纠正过:"不能调整组件大小，只是起到调整间距的效果，
     * 本质是改位置，尺寸不能改"。
     */
    const val KEY_GAP_PERCENT_MIN = 0
    const val KEY_GAP_PERCENT_MAX = 400

    /**
     * 按键高度百分比的滑块范围。同上，三个地方共用一份。
     *
     * ⚠️ 只乘**高度**，不乘宽度、不乘间距 —— 见 `keys()` 里 `heightScale` 的说明。
     */
    const val KEY_HEIGHT_PERCENT_MIN = 50
    const val KEY_HEIGHT_PERCENT_MAX = 200

    /**
     * 鼠标键（LT / RT / 肩键那一列）的**固定宽度**。
     *
     * = `1.5 × KEY_SIZE + 0.5 × GAP` = **125**（与老值一致）。
     *
     * ⚠️ 它是**常量**，不随"按键间距"变 —— 用户明确要求"尺寸不能改"。
     */
    private const val MOUSE_KEY_WIDTH = 125f

    /**
     * 间距的**硬上限**。
     *
     * 间距本身可以任意大（内容会变宽），但有个实际限制:
     * 间距超过键宽时"一行键"看起来就散架了。
     *
     * 40（= 半个键宽）是个保守值，滑块的上限也是它。
     */
    private const val MAX_KEY_GAP = 40f

    /**
     * 内容到窗口左右边缘的**留白**。
     *
     * ⚠️ 它是"窗口宽度"与"内容宽度"之间的差的一半 ——
     * `baseWidth = 2 × CONTENT_MARGIN + contentWidth`。
     *
     * 取 0 的话内容会**贴着窗口边缘**（描边/阴影会被裁掉一点），
     * 取太大则两边出现空气。20 与老布局的观感一致。
     */
    private const val CONTENT_MARGIN = 20f

    /**
     * 鼠标键在 **CPS 模式 3** 下的高度。
     *
     * ============================================================
     * ⚠️ 从 110 降到 90 —— 用户说"模式 3 也应该适当调矮一点"
     * ============================================================
     * 模式 3 是把 CPS 显示在**键内部的第二行**，所以键要能装两行文字。
     * 两行的高度是可以算出来的:
     *
     * ```
     * 主行 `CPS_PRIMARY_TEXT_SIZE`   = 28
     * 副行 `CPS_SECONDARY_TEXT_SIZE` = 22
     * 行高被显式钉成等于字号（见 `KeyGrid.lineStyle`），所以文字块 = 50
     * ```
     *
     * ============================================================
     * ⚠️ 但它**必须比普通鼠标键（[KEY_SIZE] = 80）高**
     * ============================================================
     * 第一版把它降到 70，结果比普通键还矮 —— 而"键内多一行文字"
     * 在几何上不可能比"没有那一行"更矮。那是**自相矛盾**的，
     * 测试（`模式 3 的鼠标键比普通鼠标键高`）当场就抓出来了。
     *
     * 所以取 90:既比原来的 110 矮（用户的要求），
     * 又比普通键的 80 高（模式 3 的语义要求），
     * 而且给 50 的文字块留了 40 的内边距。
     *
     * ⚠️ **不要再凭感觉调这个数** —— 它由下面两个条件夹住:
     * ```
     * 文字块 + 内边距  <=  本值  <=  一个"不至于白占屏幕"的上限
     * 本值             >   KEY_SIZE（普通鼠标键）
     * ```
     */
    private const val MOUSE_KEY_HEIGHT_CPS3 = 90f

    /**
     * **CPS 模式 2** 那个独立 CPS 组件的高度。
     *
     * ⚠️ 用户的原话:"模式 2 的单独 cps 组件需要调矮一点，
     * 与 LT，A 这些按键一样高" —— 所以就是 [LONG_KEY_HEIGHT]。
     *
     * 它只显示一行 CPS 文字（`CPS_PRIMARY_TEXT_SIZE` = 28），
     * 装进 55 完全够。
     */
    private const val CPS_ROW_HEIGHT = LONG_KEY_HEIGHT

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

    /**
     * 滑块最小值。
     *
     * ============================================================
     * ⚠️ 改这个值必须同步改 [JsonConfigCodec] 里的 `coerceIn`
     * ============================================================
     * 读配置时会把 `scalePercent` 夹到 `(50, 200)` —— **写死的字面量**。
     * 只改这里的话，用户调到 20 保存、再打开就变回 50，
     * 表现为"我调的缩放存不住"。
     *
     * 现在那边已经改成引用这两个常量，不会再漂。
     *
     * ============================================================
     * ⚠️⚠️ 语义已改:百分比**就是**倍率
     * ============================================================
     * 用户的原话:"就改成'百分比等于倍率'"。
     *
     * 旧做法是**线性映射**到 `0.9 .. 2.0` 倍，于是:
     *
     * | 滑块 | 旧的实际倍率 |
     * |---|---|
     * | 20% | 0.73× |
     * | 100% | 1.19× |
     * | 200% | 2.0× |
     *
     * 那个映射有个**很难解释**的后果:有用户说"最小值 20% 还是太大，
     * 想调特别 mini 的"，而把下限改成 5% 只对应 0.63× ——
     * **几乎看不出变化**，用户会以为滑块坏了。
     *
     * 现在直接 `倍率 = 百分比 / 100`:
     *
     * | 滑块 | 倍率 | 观感 |
     * |---|---|---|
     * | **20%**（下限） | 0.2× | 很小 —— 用户确认"20% 已经很小了" |
     * | 50% | 0.5× | |
     * | **100%** | **1.0×** | 默认 |
     * | 200% | 2.0× | |
     * | 300% | 3.0× | 上限（见 [SCALE_PERCENT_MAX]） |
     *
     * ⚠️ 下限一度试过 **1%**（0.01×），用户实测后要求改回 20%:
     * "最小还是 20% 吧，现在 20% 已经很小了"。
     *
     * ⚠️ 注意它的**语义与旧版不同**:旧版 20% 因为线性映射只等于 0.73×，
     * 现在 20% **就是 0.2×** —— 比旧版小了 3.6 倍。"最小值太大"那个反馈
     * 已经由**改映射**解决了，不需要靠极小的百分比。
     *
     * ⚠️ **老配置的外观会变**:旧版默认 `100%` 实际是 1.19×，
     * 现在 `100%` 是 1.0× —— 升级后悬浮窗会小一圈。
     * 用户明确说过这一点不用做迁移（"回头写更新公告里就行"）。
     */
    const val SCALE_PERCENT_MIN = 20

    /**
     * 滑块最大值。
     *
     * ============================================================
     * ⚠️ 从 200 提到 300，是为了**让范围围绕 100% 对称**
     * ============================================================
     * 语义改成"百分比 = 倍率"之后，100% 是默认值。
     * 上限留在 200 的话，用户能缩到 0.2× 却只能放到 2×——
     * 而"我要更大的悬浮窗"是同样常见的需求。
     *
     * ⚠️ 300% = 3.0× 已经很大（基础窗口 300 宽 → 900 像素），
     * 再往上在手机上就没意义了。
     */
    const val SCALE_PERCENT_MAX = 300

    /**
     * 整体缩放倍率。
     *
     * 它**不改变任何布局常量**，只决定画多大、窗口多大。
     * 悬浮窗与预览都走这里，因此两边的缩放始终一致。
     *
     * ⚠️ **百分比就是倍率**（`100%` = `1.0×`）—— 不再是线性映射。
     * 理由见 [SCALE_PERCENT_MIN] 的注释。
     */
    fun uiScale(config: KeyStrokesConfig): Float {
        val percent = config.scalePercent
            .coerceIn(SCALE_PERCENT_MIN, SCALE_PERCENT_MAX)

        /*
         * ⚠️ 结果再夹一个**下限兜底**。
         *
         * `percent` 最小是 1（`SCALE_PERCENT_MIN`），所以这里正常不会触发 ——
         * 但**手工改过的配置包**可以把 `scalePercent` 写成 0 或负数，
         * 而 `coerceIn` 只夹到常量范围内、夹不到"数据本身是坏的"。
         *
         * 倍率为 0 时窗口尺寸会变成 0，`WindowManager` 对 0 尺寸的窗口
         * 行为未定义（某些设备直接抛异常）。所以兜到 0.01（3 像素宽）——
         * 小到几乎看不见，但**是个合法的窗口**。
         */
        return (percent / 100f).coerceAtLeast(0.01f)
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

        /*
         * ⚠️ 不用"行高之和 + 行间隙"推,而是直接取**最低那个槽位的底边**。
         *
         * 两者本来该相等，但实测**差 12 像素** —— 用推算法得到的值
         * 比内容实际占的小，于是**最后一行的底部被裁掉一条**。
         *
         * 这是用户报的"A 键被裁剪了"的成因之一（另一个是注册值没跟着
         * 布局一起改，见 `StyleRegistryLayoutTest`）。
         *
         * ⚠️ 为什么推算法会对不上:行与行之间的 `GAP` 是**每处手写**的
         * （每个 `topY` 都写 `上一行 + 高度 + GAP`），而推算法只数
         * "有几个不同的 topY"。一旦某两行之间不是标准 GAP、
         * 或者最后一行之后还有内容，两边就分叉了。
         *
         * 直接问内容要底边，就不可能对不上 —— 而窗口尺寸与它是
         * 同一个函数的返回值，于是"窗口装不下内容"这类问题从根上消失。
         */
        val contentBottom = allKeys.maxOf { it.topY + it.height }

        /*
         * 上下各留一个顶边距，视觉才对称。
         *
         * `TOP_MARGIN` 已经包含在内容的 `topY` 里了（第一个槽位就落在
         * `TOP_MARGIN`），所以这里只补**底部**那一个。
         */
        return contentBottom + BOTTOM_MARGIN
    }

    /**
     * 底部边距。
     *
     * ⚠️ 与 [TOP_MARGIN] **分开**定义而值相同，是刻意的:
     * 它们将来可能不同（比如底部要放水印），而合成一个常量之后
     * 就分不开了。现在写成 `TOP_MARGIN` 的引用，改一处两处都动。
     */
    private val BOTTOM_MARGIN = TOP_MARGIN

    /**
     * 「手柄（标准）」样式的**全部**布局。
     *
     * ============================================================
     * 行是**顺序堆叠**的，不是各自算坐标
     * ============================================================
     * 默认（`A 显示` + `A 置顶` 关）:
     *
     * ```
     * 行 1   左摇杆        右摇杆        125 × 125
     * 行 2   LT           RT            125 × 55
     * 行 3   A（横跨两列）               260 × 55
     * 行 4   LB           RB            （可选，肩键）
     * 行 5   SPACE                      （A 隐藏时才需要；横跨两列）
     * ```
     *
     * ⚠️ **A 是"独占一行的横跨键"，不是 LT/RT 中间那个键** ——
     * 用户的原话:
     *
     * > "A 本身就指代 space，然后现在的排列是 LT、RT 占一行，
     * >  A 在它们下面独占一行，类似于键盘样式 LMB、RMB 与 space 的
     * >  位置关系"
     *
     * 我最初理解成"A 在 LT 与 RT 之间"（三键一行），**那是错的** ——
     * 按那个排法三键各 125 宽需要 395，而窗口只有 300，
     * 会互相压住。用户随后明确说了"A 根本没有与 RT、LT 共处一行"。
     *
     * A 置顶（`aButtonOnTop` = 真）时，把 A 那一行**提到最前**:
     *
     * ```
     * 行 1   左摇杆        右摇杆
     * 行 2   A（横跨两列）               ← 挪到摇杆正下方
     * 行 3   LT           RT             ← 顺延
     * ```
     *
     * ⚠️ 之所以"顺序堆叠"而不是像键盘样式那样每行手写 `topY`:
     * 可选开关有五个（[KeyStrokesConfig.showAButton] /
     * [KeyStrokesConfig.aButtonOnTop] /
     * [KeyStrokesConfig.showShoulderButtons] /
     * [KeyStrokesConfig.showMouseButtons] /
     * [KeyStrokesConfig.showShiftKey]），手写坐标要写 2⁵ 种组合。
     * 顺序堆叠则**任何一种组合都自动对** —— 而"漏了一种组合"
     * 正是布局类 bug 最常见的来源。
     *
     * ============================================================
     * ⚠️ 尺寸是用户拍定的，改任何一个都要重新问他
     * ============================================================
     * | 元素 | 尺寸 | 用户原话 |
     * |---|---|---|
     * | `LT` / `RT` | **125 × 55** | "把 LT 与 RT 的 Y 方向长度与 A 同步" |
     * | 摇杆 | **125 × 125** | "摇杆的边长与 LT/RT 同步" |
     * | 肩键 | 与 LT/RT **同尺寸** | "组件大小一样的" |
     * | `A` / `SPACE` | **260 × 55** | 横跨两列 |
     */
    private fun joystickLayoutBoxes(
        config: KeyStrokesConfig,
        center: Float,
        leftCenter: Float,
        rightCenter: Float,
        /** 摇杆用的**整列**中心（与鼠标键中心不同，见 `keys()` 里的说明） */
        stickLeftCenter: Float,
        stickRightCenter: Float,
        buttonWidth: Float,
        columnsWidth: Float,
        labelOf: (String) -> String,
        codesOf: (String) -> List<Int>,
        heightScale: Float,
        /** 按键间距（已按百分比算好，见 `keys()` 里的推导） */
        gap: Float,
        /** 摇杆缩放的上限（两摇杆刚好贴在一起，见 `keys()` 里的推导） */
        stickMaxScale: Float,
    ): List<KeyBox> {
        val boxes = mutableListOf<KeyBox>()

        /*
         * ⚠️ 弹出末尾**只能用 [popLast]** —— 与 `keys()` 里那份是同一个理由:
         * `boxes.removeLast()` 会编译成对 `java.util.List.removeLast()` 的调用，
         * 而那是 Java 21 才有的默认方法，**Android 的 ArrayList 没有它** ——
         * 编译过、单测过，只有在真机上跑到这一行才崩。
         * 完整说明见 `keys()` 里那段注释。
         */
        fun popLast(): KeyBox = boxes.removeAt(boxes.lastIndex)

        val buttonHeight = LONG_KEY_HEIGHT * heightScale

        /*
         * ============================================================
         * 摇杆边长 = 槽位边长 × 摇杆缩放
         * ============================================================
         * ⚠️ 这里乘上 `sizeScale` 是**故意的**:让"摇杆缩放"也参与
         * **窗口高度**的计算（`baseHeight` 取各行最高元素）。
         *
         * 否则用户把摇杆放大之后，它会超出为自己分配的那一行、
         * 压住下面的键 —— 而"改动之后布局自动重算"正是这套
         * "从 `keys()` 推一切"的设计要保证的事。
         *
         * ⚠️ 上限由调用方夹好（见 `keys()` 里的 `stickMaxScale`）——
         * 上限是"两个摇杆刚好贴在一起"，不是随便一个大数。
         * 更大的话左右摇杆会**互相重叠**，而用户明确说过不要那个。
         */
        val stickSize = buttonWidth * config.joystick.sizeScale.coerceIn(0.3f, stickMaxScale)

        /* 当前行的顶边，每加一行就往下推 */
        var y = TOP_MARGIN

        fun advance(rowHeight: Float) {
            y += rowHeight + gap
        }

        /* ---------------- 行 1：两个摇杆 ---------------- */
        listOf(
            Id.JOYSTICK_LEFT to stickLeftCenter,
            Id.JOYSTICK_RIGHT to stickRightCenter,
        ).forEach { (slotId, cx) ->
            boxes += KeyBox(
                slotId = slotId,
                /* 摇杆没有键面文字，label 留空（渲染层会画摇杆） */
                label = "",
                /* ⚠️ 空列表：它不参与"按下了哪些键"的匹配 */
                codes = emptyList(),
                centerX = cx,
                topY = y,
                width = stickSize,
                height = stickSize,
            )
        }
        advance(stickSize)

        /**
         * 一个"窄按钮"（LT / RT / 肩键），落在左右两列。
         *
         * ⚠️ `topY` 由 `y` 决定，所以调用它之前必须已经把 `y` 推到那一行。
         */
        fun pushColumnButton(slotId: String, cx: Float) {
            boxes += KeyBox(
                slotId = slotId,
                label = labelOf(slotId),
                codes = codesOf(slotId),
                centerX = cx,
                topY = y,
                width = buttonWidth,
                height = buttonHeight,
            )
        }

        /** 一个"横跨两列的按钮"（A / SPACE），居中 */
        fun pushWideButton(slotId: String) {
            boxes += KeyBox(
                slotId = slotId,
                label = labelOf(slotId),
                codes = codesOf(slotId),
                centerX = center,
                topY = y,
                width = columnsWidth,
                height = buttonHeight,
            )
        }

        /**
         * ============================================================
         * 行序（**先排好，再统一推进 y**）
         * ============================================================
         * 用户的原话:"肩键应该和扳机键是一起的，但是现在如果开启了 A 键，
         * 肩键就会跑到 A 键下面，不跟扳机键一起了"。
         *
         * ⚠️ 老写法是"插一段、`advance` 一次"，于是**肩键的位置取决于
         * A 在哪里** —— A 一置顶或一后置，肩键就被挤到别处。
         *
         * 改成"先把每一行的内容排成列表，再逐行落 y"之后，
         * 行与行之间**不可能**因为某个开关而错位:
         * 每个 `Row` 就是一个 `List<KeyBox>`，推进多少只由它自己的高度决定。
         */
        val rows = mutableListOf<List<KeyBox>>()

        /* 行:LT 与 RT —— 后面紧跟肩键（它们是同一组） */
        if (config.showMouseButtons) {
            val triggerRow = mutableListOf<KeyBox>()
            /* 借用 pushColumnButton 的构造:先落 y 再取出来 */
            pushColumnButton(Id.LMB, leftCenter)
            pushColumnButton(Id.RMB, rightCenter)
            triggerRow += popLast()
            triggerRow += popLast()
            /* 弹出来的顺序是反的，转回来（RMB 在右，LMB 在左） */
            rows += triggerRow.reversed()

            /*
             * ⚠️ 肩键**紧跟在扳机键下面** —— 这是用户的明确要求。
             *
             * 把肩键放进**同一个 if** 里（而不是另起一个 if 排在 A 后面），
             * 才能保证"A 置不置顶、显不显示"都不影响肩键的位置。
             */
            if (config.showShoulderButtons) {
                pushColumnButton(Id.SHOULDER_L, leftCenter)
                pushColumnButton(Id.SHOULDER_R, rightCenter)
                val shoulderRow = mutableListOf<KeyBox>()
                shoulderRow += popLast()
                shoulderRow += popLast()
                rows += shoulderRow.reversed()
            }
        } else if (config.showShoulderButtons) {
            /*
             * ⚠️ 扳机键关掉但仍要肩键时，肩键**自己占一行**（不并进别处）——
             * 否则它会莫名其妙地跑到 A 那一行去。
             */
            pushColumnButton(Id.SHOULDER_L, leftCenter)
            pushColumnButton(Id.SHOULDER_R, rightCenter)
            val shoulderRow = mutableListOf<KeyBox>()
            shoulderRow += popLast()
            shoulderRow += popLast()
            rows += shoulderRow.reversed()
        }

        /* 行:A（独占一行，横跨两列） */
        if (config.showAButton) {
            pushWideButton(Id.A_BUTTON)
            rows += listOf(popLast())
        }


        /* 行:SPACE */
        if (config.showSpaceKey) {
            pushWideButton(Id.SPACE)
            rows += listOf(popLast())
        }

        /* 行:SHIFT */
        if (config.showShiftKey) {
            pushWideButton(Id.SHIFT)
            rows += listOf(popLast())
        }

        /*
         * A 置顶:把 A 那一行**提到最前**（摇杆正下方）。
         *
         * ⚠️ 只挪 A 那一行，**不动** LT/RT/肩键那一组 —— 那是"跟扳机键
         * 在一起"的要求。老写法把 A 插在行序前面时会连带影响肩键，
         * 正是用户报的那个问题。
         */
        val orderedRows = if (config.showAButton && config.aButtonOnTop) {
            val aRow = rows.first { row -> row.any { it.slotId == Id.A_BUTTON } }
            listOf(aRow) + rows.filter { it !== aRow }
        } else {
            rows
        }

        /*
         * 现在按行统一落 `topY`。
         *
         * 每行的高度取该行**最高的那个元素** —— 摇杆行是边长、按钮行是按钮高。
         */
        orderedRows.forEach { row ->
            val rowHeight = row.maxOf { it.height }
            row.forEach { box ->
                boxes += box.copy(topY = y)
            }
            advance(rowHeight)
        }

        return boxes
    }

    /**
     * 基础单位 → dp 的换算系数。
     *
     * 布局坐标是像素，Compose 要 dp，所以除以屏幕密度。
     * 悬浮窗与预览都用它，保证两边一致。
     */
    fun pxToDpFactor(density: Float): Float = 1f / density.coerceAtLeast(0.1f)

    /**
     * 内容的**实际宽度**（基础像素）。
     *
     * ============================================================
     * ⚠️ 为什么它不能再是常量
     * ============================================================
     * "按键间距"滑块**只改位置、不改键宽**（用户明确要求），
     * 所以间距一变内容就变宽/变窄 —— 窗口宽度必须跟着算:
     *
     * | 间距 | 内容宽 |
     * |---|---|
     * | `0%`（gap 0） | 240 |
     * | `100%`（gap 10，默认） | **260** |
     * | `400%`（gap 40） | 320 |
     *
     * ⚠️ 三行的宽度可能不同（`3 × 80 + 2 × gap` 与 `2 × 125 + gap`
     * 只在 `gap = 10` 时相等），所以取**最宽的那一行**。
     *
     * ⚠️ 样式注册表与渲染内容都用**这一个函数** —— 两处各算一遍
     * 就会出现"右边一块空气"或"底部被裁"（那两种都真的发生过，
     * 见 `StyleRegistryLayoutTest`）。
     */
    fun baseWidth(config: KeyStrokesConfig): Float = CONTENT_MARGIN * 2f + contentWidth(config)

    /**
     * 内容本身的宽度（不含左右边距）。
     *
     * = **最宽的那一行**。三行的宽度可能不同:
     *
     * ```
     * WASD 行: 3 × 80  + 2 × gap
     * 鼠标行: 2 × 125 + 1 × gap
     * ```
     *
     * ⚠️ 它们在 `gap = 10`（默认）时都是 260，所以以前看不出一致性问题；
     * 一旦间距可调就必须显式取最大值，否则会出现"某一行比别的宽几个像素"
     * 或者"窗口比内容宽/窄几个像素"（右边一块空气 / 右边被裁）。
     */
    private fun contentWidth(config: KeyStrokesConfig): Float {
        val gap = (GAP * config.keyGapPercent.coerceIn(KEY_GAP_PERCENT_MIN, KEY_GAP_PERCENT_MAX) / 100f)
            .coerceIn(0f, MAX_KEY_GAP)

        /* WASD 行（键盘样式；三键两缝） */
        val threeKeyRow = 3f * KEY_SIZE + 2f * gap

        /* 鼠标键行（两键一缝）—— gamepad2 的 LT/RT 与摇杆都用这一列 */
        val mouseRow = 2f * MOUSE_KEY_WIDTH + gap

        /*
         * ⚠️ gamepad2 的摇杆行也可能变宽，但它的上限已经被
         * `stickMaxScale` 夹到"两个摇杆相切"，而那个上限正好在
         * 鼠标键行之内 —— 所以取这两行的最大值就够。
         */
        return maxOf(threeKeyRow, mouseRow)
    }

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
        /*
         * ⚠️ 水平中心按**内容宽度 + 边距**推，不是 `BASE_WIDTH / 2`。
         *
         * "按键间距"可调之后内容宽度会变，用固定常量会让左右边距
         * **不相等**（实测:间距 50% 时左边界跑到 22.5 而右边界还是 280）。
         */
        val center = baseWidth(config) / 2f

        fun mappingOf(id: String) = config.keyMappings.firstOrNull { it.id == id }

        fun labelOf(id: String): String = mappingOf(id)?.displayText ?: id

        fun codesOf(id: String): List<Int> = mappingOf(id)?.inputKeyCodes.orEmpty()

        /** 该位置的 CPS（未开启时为 0） */
        fun cpsOf(id: String): Int =
            if (config.mouseCpsEnabled) cpsBySlot[id] ?: 0 else 0

        val boxes = mutableListOf<KeyBox>()

        /*
         * ============================================================
         * ⚠️ 弹出列表末尾**只能用下面那个 popLast()**，不要写 `boxes.removeLast()`
         * ============================================================
         * 这不是风格问题，是**线上闪退**:
         *
         * ```
         * java.lang.NoSuchMethodError: No virtual method removeLast()Ljava/lang/Object;
         *   in class Ljava/util/ArrayList;
         * ```
         *
         * `List.removeLast()` 是 **Java 21** 才加进 `java.util.List` 的默认方法，
         * 而 Android 的 `ArrayList` **从来没有**它（libcore 不是 OpenJDK）。
         *
         * ⚠️ 编译器不拦:Kotlin 编译用的是 JDK 25，那里 `List` 上有这个方法，
         * 于是编译成一次**成员调用** —— 编译过、单测过（JVM 上有它）、打包正常，
         * 只有**在 Android 上真跑到那一行**才崩。
         *
         * ⚠️ 而且它**不是每次启动都崩**:下面那些调用全在
         * "显示鼠标键 / 肩键 / A / 空格 / Shift"的分支里，
         * 配置不同就走不到 —— 这正是"你那边复现不出来"的原因。
         *
         * 见文件末尾的 [popLast]（`removeAt(lastIndex)`，到处都有）。
         */
        fun popLast(): KeyBox = boxes.removeAt(boxes.lastIndex)

        /* ============================================================
         * 间距只改**位置**，不改**尺寸**
         * ============================================================
         * 用户的原话:"这个调整间距的效果也需要改进一下，不能调整组件大小，
         * 只是起到调整间距的效果，本质是改位置，尺寸不能改"。
         *
         * ⚠️ 第一版做反了:我"锁住外框、让键宽让位"—— 窗口宽度确实恒定了，
         * 但键**跟着变小/变大**了，正是用户不要的效果。
         *
         * 正确做法:**键宽是常量**，间距变了内容就变宽/变窄，
         * 于是**窗口宽度跟着变**。这与"整体缩放"是两件不同的事:
         *
         * | 设置 | 键宽 | 窗口宽 |
         * |---|---|---|
         * | 整体缩放 | 变 | **变** |
         * | 按键间距 | **不变** | **变** |
         *
         * ⚠️ 所以窗口宽度不能再是 `BASE_WIDTH` 常量:
         *
         * ```
         * 三键两缝 = 3 × KEY_SIZE + 2 × gap
         * gap =  0 → 240
         * gap = 10 → 260   ← 默认，与以前一致
         * gap = 40 → 320
         * ```
         *
         * 见 [contentWidth] —— 窗口尺寸按它算。
         */
        val gap = (GAP * (config.keyGapPercent.coerceIn(KEY_GAP_PERCENT_MIN, KEY_GAP_PERCENT_MAX)) / 100f)
            .coerceIn(0f, MAX_KEY_GAP)

        /**
         * 键宽 —— **常量**，与间距无关。
         *
         * 间距滑块只挪位置，这里一个像素都不动（用户明确要求）。
         */
        val keySize = KEY_SIZE

        /**
         * 鼠标键（= 屏幕上的 LT / RT）的宽度。
         *
         * = **常量 125**，与间距无关 —— 同 [keySize]，"间距只改位置"。
         *
         * ⚠️ 于是"三键两缝"与"两键一缝"就不是同一个宽度了:
         *
         * ```
         * WASD 行: 3 × 80  + 2 × gap
         * 鼠标行: 2 × 125 + 1 × gap
         * ```
         *
         * `gap = 10` 时两者都是 260（所以以前"锁外框"能成立）；
         * `gap = 0` 时分别是 240 与 250 —— **不再相等**。
         *
         * ⚠️ 这没关系:[baseWidth] 取**最宽的那一行**，各行的
         * 水平中心仍然都是 `BASE_WIDTH / 2`，所以看起来还是居中的。
         */
        val mouseWidth = MOUSE_KEY_WIDTH

        /**
         * 左列 / 右列的中心 —— **鼠标键（LT/RT）与肩键**落在这里。
         *
         * ⚠️ 公式**必须与写死位置的那两处（`CPS_L` / `CPS_R`）一致** ——
         * 它们本来就是同一列，各写一份迟早会分叉。
         *
         * ```
         * 左列外沿 = center − mouseWidth / 2 − gap / 2
         * ```
         *
         * ⚠️ 这里踩过坑:第一版写成
         * `center − KEY_SIZE − gap + mouseWidth / 2f`，
         * 算出的中心是 **122.5** 而不是 **67.5** —— 整体右移了 55，
         * 而且与 `CPS_L` 不再重合。
         *
         * 那种错**不会崩、也不报错**，表现只是"看起来偏了一点"。
         * 是靠 `StyleRegistryLayoutTest` 里那条打印边界的断言看出来的。
         */
        val leftCenter = center - mouseWidth / 2f - gap / 2f
        val rightCenter = center + mouseWidth / 2f + gap / 2f

        /**
         * **整列**的中心 —— 摇杆落在这里。
         *
         * ============================================================
         * ⚠️ 摇杆用整列中心，不是鼠标键中心（这两个差 15）
         * ============================================================
         * 鼠标键比整列**窄一个 `gap`**（`mouseWidth = (260 − gap) / 2`），
         * 所以两者的中心不同:
         *
         * | 基准 | 左 / 右中心 | 中心距 | 摇杆能放大到 |
         * |---|---|---|---|
         * | 鼠标键中心 | 82.5 / 217.5 | 135 | **1.08×** |
         * | **整列中心** | 67.5 / 232.5 | **165** | **1.24×** |
         *
         * ⚠️ 用错基准的后果很隐蔽:摇杆缩放的上限被算成 `1.08`，
         * 于是用户**怎么拖滑块都没反应**（全被夹掉了）——
         * 而"没反应"看起来像滑块坏了，不像布局算错了。
         *
         * 用整列中心还有一个理由:摇杆占满整列（`260 / 2 = 130` 的余地）
         * 才是它"属于那一列"的视觉表达，与 LT/RT 的关系也更自然。
         */
        val stickLeftCenter = leftCenter
        val stickRightCenter = rightCenter

        /**
         * 两列**最外沿之间**的距离 —— 摇杆与空格要横跨的宽度。
         *
         * = `mouseWidth + gap + mouseWidth` = **260**（与间距无关，恒等于
         * [COLUMN_SPAN]）。
         *
         * ⚠️ 这里连着写错过两次，把三个容易混的数列清楚:
         *
         * | 名字 | 值 | 含义 |
         * |---|---|---|
         * | `mouseWidth` | 125 | **一列**的宽度（单个 LT/RT/肩键） |
         * | `rightCenter − leftCenter` | 135 | 鼠标键两列**中心之间**的距离 |
         * | `stickRightCenter − stickLeftCenter` | 165 | **整列**中心之间 |
         * | `columnsWidth`（本值） | **260** | 两列**最外沿之间**的距离 |
         *
         * 用错的表现:
         *
         * - 用 165 当宽度 → 空格只有 135 宽（比两列窄 125），"横跨两列"不成立；
         * - 写成 `2 × mouseWidth + gap` = 262 → 差 2，超出对称边界。
         */
        val columnsWidth = 2f * mouseWidth + gap

        /**
         * 按键**高度**的倍率（来自 `keyHeightPercent`）。
         *
         * ⚠️ **只乘高度，不乘宽度、不乘间距** —— 这是它与 `scalePercent`
         * 的分工:整体缩放管一切，本值只让键变扁 / 变高。
         *
         * ⚠️ 行与行之间的 `topY` 也必须用**乘过之后**的高度去推，
         * 否则键变高之后会互相压住。
         */
        /**
         * 摇杆缩放的**实际上限** —— 两个摇杆**刚好贴在一起**的时候。
         *
         * 用户的原话:"摇杆缩放不应该设置那么大的，最大也只是到了
         * '两个摇杆之间没有间距'的时候"。
         *
         * 推导:两个摇杆分别居中在左右两列，要让它们不重叠:
         *
         * ```
         * 边长 × sizeScale  <=  两列中心距 − 间距
         * sizeScale         <=  (中心距 − gap) / mouseWidth
         * ```
         *
         * ⚠️ 写成 `>= 1`（`coerceAtLeast`）:间距很大时算出来的上限会
         * **小于 1**，那时"不能放大"是对的，但也不该让用户连缩回去都做不到。
         *
         * ⚠️ 这个上限**随间距变化**（间距可调），所以不能写死在
         * `JoystickStyle` 里 —— 那里只放兜底范围。
         */
        val stickMaxScale = ((stickRightCenter - stickLeftCenter) / mouseWidth)
            .coerceAtLeast(1f)

        val heightScale = (config.keyHeightPercent.coerceIn(50, 200)) / 100f


        var spaceWidth = columnsWidth

        /** 各列的顶边，由下面的分支填 */
        var mouseY: Float
        var mouseHeight = keySize * heightScale

        if (usesJoystickLayout(config)) {
            /*
             * 「手柄（标准）」的布局**整块**在
             * [joystickLayoutBoxes] 里 —— 它自己把行**顺序**排好，
             * 这里只负责分发。
             *
             * ⚠️ 为什么整块交出去，而不是在这里 `if` 一下"要不要 A 置顶":
             * 置顶会改变**行的顺序**（A 从中间列挪到独立一行，
             * LT/RT 下移），那不是"少画一个键"那么简单 ——
             * 混在一起写会让这个函数变成一团条件判断。
             */
            return applyCpsLabels(
                boxes = joystickLayoutBoxes(
                    config = config,
                    center = center,
                    leftCenter = leftCenter,
                    rightCenter = rightCenter,
                    stickLeftCenter = stickLeftCenter,
                    stickRightCenter = stickRightCenter,
                    buttonWidth = mouseWidth,
                    columnsWidth = columnsWidth,
                    labelOf = ::labelOf,
                    codesOf = ::codesOf,
                    heightScale = heightScale,
                    gap = gap,
                    stickMaxScale = stickMaxScale,
                ),
                config = config,
                cpsBySlot = cpsBySlot,
            )
        }

        /* ============================================================
         * 键盘样式：W / A S D
         * ============================================================
         * ⚠️ WASD 永远是第一行 —— "SPACE 置顶"**不会**把空格挪到它上面
         * （我第一版就是那么理解的，被用户纠正了）。
         * 那个开关的效果在下面"空格与鼠标键共用一行"那段。
         */
        val wasdTop = TOP_MARGIN

        /* W */
        boxes += KeyBox(
            slotId = Id.W,
            label = labelOf(Id.W),
            codes = codesOf(Id.W),
            centerX = center,
            topY = wasdTop,
            width = keySize,
            height = keySize * heightScale,
        )

        /* A S D */
        val asdY = wasdTop + keySize * heightScale + gap
        listOf(Id.A to -1f, Id.S to 0f, Id.D to 1f).forEach { (id, offset) ->
            boxes += KeyBox(
                slotId = id,
                label = labelOf(id),
                codes = codesOf(id),
                centerX = center + offset * (keySize + gap),
                topY = asdY,
                width = keySize,
                height = keySize * heightScale,
            )
        }

        mouseY = asdY + keySize * heightScale + gap

        /*
         * ============================================================
         * 下面三行（鼠标键 / 空格 / Shift）**先建好，再排行序，最后统一落 y**
         * ============================================================
         * ⚠️ 这是照 `joystickLayoutBoxes` 里 [KeyStrokesConfig.aButtonOnTop]
         * 的做法 —— 而我是踩了三次坑之后才改过来的。
         *
         * 用户的原话:"建议你参考一下 **A 键置顶**那一块，**别凭感觉改**"。
         *
         * ============================================================
         * ⚠️ 前三次为什么都错（都是在"调坐标"而不是"排行序"）
         * ============================================================
         * | 写法 | 症状 |
         * |---|---|
         * | 写死 `spaceY + 空格高 + gap` | 空格**关掉**时 Shift 上面留一块空缺 |
         * | Shift 跟着 `mouseRowY` 走 | 空格**置顶**时 Shift **上移一行、贴到鼠标键上边缘** |
         * | 用 `spaceBelowMouseRow` 重算 | Shift 贴到鼠标键**下边缘**（还是重叠） |
         *
         * ⚠️ 三次的病根是同一个:**用"谁在哪一行"去推另一行的位置**。
         * 只要某个开关让某一行位移，那个位移就会被传导给本来无关的行。
         *
         * 而 A 键置顶那一块早就有正解 —— **行是顺序堆叠的，
         * 位置只由"它在行序里排第几"决定**:
         *
         * ```
         * val rows = mutableListOf<List<KeyBox>>()   // 1. 先建行
         * ...                                        // 2. 排行序（置顶 = 交换两行）
         * orderedRows.forEach { row ->               // 3. 统一落 y
         *     row.forEach { boxes += it.copy(topY = y) }
         *     advance(row.maxOf { it.height })
         * }
         * ```
         *
         * 于是"置顶"只是**把两行在列表里换个位置**，
         * 第三行（Shift）自然跟着走，**不需要任何额外判断**。
         */
        val tailRows = mutableListOf<List<KeyBox>>()

        /* ---------- 行:鼠标左右键 ---------- */
        if (config.showMouseButtons) {
            /*
             * 模式 3：把 CPS 显示在键**内部**，因此键要加高以容纳两行文字。
             *
             * ⚠️ 高度必须在**建行之前**定好 —— 行的推进量取的是
             * `row.maxOf { it.height }`。
             */
            if (config.mouseCpsEnabled && config.mouseCpsMode == 3) {
                mouseHeight = MOUSE_KEY_HEIGHT_CPS3 * heightScale
            }

            /*
             * ⚠️ 主文字/副文字的 CPS 套用**不在这里** ——
             * 统一挪到 `keys()` 最后那一步 `applyCpsLabels`。
             *
             * 原因见那个函数的说明:写在这里的话（a）"显示鼠标键"一关
             * CPS 也跟着消失，（b）gamepad2 走另一个布局函数、
             * 完全没有 CPS。
             *
             * 这里只负责"键要画多高"（模式 3 要装两行文字）。
             *
             * ⚠️ 模式 2（独立 CPS 组件）**已经删掉** —— 用户原话:
             * "干脆在 gamepad2 这里把 cps 模式 2 删掉吧……就留模式 1 和 3"。
             * 两个布局都删了:只删一边会让模式 2 在两种样式下表现不同。
             * 内部编号保持不变（`mouseCpsMode` 是持久化字段）。
             */
            tailRows += listOf(
                KeyBox(
                    slotId = Id.LMB,
                    label = labelOf(Id.LMB),
                    codes = codesOf(Id.LMB),
                    centerX = leftCenter,
                    topY = TOP_MARGIN, // 占位，下面统一改
                    width = mouseWidth,
                    height = mouseHeight,
                ),
                KeyBox(
                    slotId = Id.RMB,
                    label = labelOf(Id.RMB),
                    codes = codesOf(Id.RMB),
                    centerX = rightCenter,
                    topY = TOP_MARGIN, // 占位
                    width = mouseWidth,
                    height = mouseHeight,
                ),
            )
        }

        /* ---------- 行:空格（独占一行、横跨两列） ---------- */
        if (config.showSpaceKey) {
            tailRows += listOf(
                KeyBox(
                    slotId = Id.SPACE,
                    label = labelOf(Id.SPACE),
                    codes = codesOf(Id.SPACE),
                    centerX = center,
                    topY = TOP_MARGIN, // 占位
                    width = spaceWidth,
                    height = LONG_KEY_HEIGHT * heightScale,
                ),
            )
        }

        /* ---------- 行:Shift ---------- */
        if (config.showShiftKey) {
            tailRows += listOf(
                KeyBox(
                    slotId = Id.SHIFT,
                    label = labelOf(Id.SHIFT),
                    codes = codesOf(Id.SHIFT),
                    centerX = center,
                    topY = TOP_MARGIN, // 占位
                    width = spaceWidth,
                    height = LONG_KEY_HEIGHT * heightScale,
                ),
            )
        }

        /*
         * ============================================================
         * 排行序:SPACE 置顶 = 把空格那一行**提到鼠标键前面**
         * ============================================================
         * 用户对"置顶"的定义:
         *
         * > SPACE 置顶的意思是，置顶到 WASD 下面，**顶替掉 LMB、RMB 的位置**。
         *
         * ⚠️ 也就是**两行交换**，行数不变:
         *
         * ```
         * 关闭（默认）:        开启:
         *   LMB    RMB           SPACE      ← 提到前面
         *   SPACE                LMB   RMB  ← 顺延
         *   SHIFT                SHIFT      ← **完全不动**
         * ```
         *
         * ⚠️ Shift 之所以"不动"，不是因为有判断，而是因为**它排在最后** ——
         * 这正是"排行序"比"算坐标"稳的地方:
         * 无论前面两行怎么换，第三行的 y 都由前两行的实际高度堆出来。
         *
         * ⚠️ 与 A 键置顶同一个模式（见 `joystickLayoutBoxes` 里
         * `orderedRows` 那段）—— 那边也是"取出一行、提到最前、
         * 其余保持相对顺序"。
         */
        val orderedTailRows = if (config.showSpaceKey && config.spaceKeyOnTop) {
            val spaceRow = tailRows.first { row -> row.any { it.slotId == Id.SPACE } }
            listOf(spaceRow) + tailRows.filter { it !== spaceRow }
        } else {
            tailRows
        }

        /* ---------- 统一落 y:从 WASD 下面开始逐行推进 ---------- */
        var tailY = mouseY
        orderedTailRows.forEach { row ->
            /*
             * 行高取该行**最高**的那个元素 —— 鼠标键在 CPS 模式 3 下会变高，
             * 而空格 / Shift 是固定高度。用 `maxOf` 而不是"第一个元素的高度"，
             * 否则同一行里两个键高度不同时会算错推进量。
             */
            val rowHeight = row.maxOf { it.height }
            row.forEach { box -> boxes += box.copy(topY = tailY) }
            tailY += rowHeight + gap
        }

        /*
         * ============================================================
         * CPS:统一在最后处理（**两种布局共用**）
         * ============================================================
         * ⚠️ 这里踩过一个坑:第一版把 CPS 的处理写在"鼠标键那一块"的
         * `if (config.showMouseButtons)` 里面 —— 那是**键盘样式**的代码路径。
         * 而 gamepad2 的布局在 [joystickLayoutBoxes] 里，于是:
         *
         * - `独立槽位=[无]`（模式 2 该有的 CPS 行根本没生成）；
         * - 关掉"显示鼠标键"时 CPS 也一起消失。
         *
         * 现在抽成最后一步、**任何布局都过一遍**，那两种症状都不会再有。
         */
        return applyCpsLabels(boxes, config, cpsBySlot)
    }

    /**
     * 把 CPS 数字套进各个键的显示文字（三种模式都在这里）。
     *
     * ============================================================
     * ⚠️ 为什么要抽成独立一步
     * ============================================================
     * CPS 是**通用的显示选项**（任何绑了键码的槽位都能显示），
     * 而它以前被写在"鼠标键那一块"里 —— 那是**键盘样式的实现细节**。
     * 结果就是:
     *
     * | 症状 | 原因 |
     * |---|---|
     * | 关掉"显示鼠标键" → CPS 也没了 | CPS 的处理在那个 `if` 里面 |
     * | gamepad2 开了 CPS 完全没反应 | 它的布局走另一个函数，没有这段代码 |
     *
     * ⚠️ 现在它与布局**解耦**:谁生成的 `boxes` 都要过这一步。
     *
     * ============================================================
     * 三种模式的区别
     * ============================================================
     * | 模式 | 做法 |
     * |---|---|
     * | 1 | CPS 接在**主文字后面**（模板用 `(cps2)`，为 0 时整段不显示） |
     * | 2 | 单独的 `CPS_L` / `CPS_R` 槽位（由布局生成，这里只填文字） |
     * | 3 | 主文字下面的**副文字**（`subLabel`） |
     */
    private fun applyCpsLabels(
        boxes: List<KeyBox>,
        config: KeyStrokesConfig,
        cpsBySlot: Map<String, Int>,
    ): List<KeyBox> {
        if (!config.mouseCpsEnabled) return boxes

        /** 该位置的 CPS 数值（没开就按 0） */
        fun cpsOf(slotId: String): Int = cpsBySlot[slotId] ?: 0

        /*
         * ============================================================
         * ⚠️⚠️ CPS **只对 LT / RT 生效**，用**白名单**，不是"有没有计数"
         * ============================================================
         * 用户的原话:"cps，应该对 LT 与 RT 有效果的，但是我测试发现
         * 所有按键都有 cps，这点要改"。
         *
         * 原因:下面两个分支（模式 1 / 模式 2）原本对**每一个** `box` 都套用，
         * 于是 W、A、S、D、空格、A 键…全都被加上了 CPS 文字。
         *
         * ============================================================
         * ⚠️ 第一版修错了:用"这个槽位有没有计数"当判据
         * ============================================================
         * 那是错的 —— `CpsCounter` 对**任何槽位都统计**（见它自己的注释:
         * "现在**任何槽位都统计**"，那是为自定义 Key 的任意组件留的口子）。
         * 所以 W/A/S/D 全都有计数，那道闸形同虚设，用户实测
         * **"其他按键因 bug 产生的 cps 显示，数值会 x2"** 就是这个原因:
         * 主文字和后缀各带一份，看起来就是翻倍。
         *
         * ⚠️ 现在改成**写死白名单**:
         *
         * | 样式 | 显示 CPS 的槽位 |
         * |---|---|
         * | 键盘 | `LMB` / `RMB`（鼠标左右键） |
         * | 「标准」手柄 | `LMB` / `RMB` —— 它们在这套键位下**就是 LT / RT** |
         *
         * ⚠️ 手柄样式的槽位 id 仍复用键盘的 `LMB` / `RMB`（位置语义），
         * 只是显示文字换成了 `LT` / `RT`（见 `gamepad2KeyMappings`）——
         * 所以这里**不需要**按样式分支，一套白名单两边都对。
         *
         * ⚠️ 自定义 Key 样式不走这里（它有自己的 `CustomKeyGrid`），
         * 所以那边"任意组件显示 CPS"的能力不受影响。
         */
        val cpsSlots = setOf(Id.LMB, Id.RMB)

        return boxes.map { box ->
            /*
             * 不在白名单里的槽位（W/A/S/D、空格、A 键、肩键…）**一律不动**。
             *
             * ⚠️ 用 `return@map box` 而不是在 `when` 里加分支 ——
             * 后者要为每种模式各写一遍，漏一个就漏一种模式。
             */
            if (box.slotId !in cpsSlots) return@map box

            when {
                /*
                 * 模式 2 的独立 CPS 行:文字**由这里填**。
                 *
                 * ⚠️ `static` 的槽位就是"只显示数字、不参与按键点亮"，
                 * 数值来自实时的 `cpsBySlot` —— 布局函数拿不到它，
                 * 所以文字必须在这一步生成（在布局里写死的话永远是 0）。
                 */
                box.static -> box.copy(
                    label = formatCpsTemplate(config.cpsTextTemplate, cpsOf(box.slotId)),
                )

                /* 模式 3:主文字 + 键内第二行的副文字 */
                config.mouseCpsMode == 3 -> box.copy(
                    subLabel = formatCpsTemplate(config.cpsTextTemplate, cpsOf(box.slotId)),
                )

                /* 模式 1:CPS 接在主文字后面（为 0 时整段不显示） */
                config.mouseCpsMode == 1 -> box.copy(
                    label = box.label + formatCpsTemplate(
                        template = config.cpsTextTemplateMode1,
                        cps = cpsOf(box.slotId),
                        placeholder = CPS_PLACEHOLDER_MODE1,
                        hideWhenZero = true,
                    ),
                )

                else -> box
            }
        }
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


package com.something.sthkey.ui.overlay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.something.sthkey.capture.CaptureSession
import com.something.sthkey.domain.config.KeyStrokesConfig
import com.something.sthkey.domain.custom.CustomLayout
import com.something.sthkey.domain.style.KeyLayout
import com.something.sthkey.domain.style.OverlayStyleRegistry
import com.something.sthkey.domain.style.StyleId
import com.something.sthkey.ui.overlay.gamepad.Gamepad1Content
import com.something.sthkey.ui.overlay.gamepad.Gamepad2Content
import kotlinx.coroutines.delay

/**
 * 悬浮窗内容。
 *
 * ============================================================
 * 关键点：根布局尺寸必须与窗口尺寸**同源**
 * ============================================================
 * 悬浮窗的窗口大小是 Service 用 `WindowManager` 定的（像素）。
 * 如果这里用"内容自适应"，根布局就会按内容撑开，而内容是固定基础尺寸 ——
 * 窗口一缩小，内容就被裁掉一角，表现就是"缩放像是在裁剪"。
 *
 * 因此这里显式把根布局设成 `KeyLayout` 算出的窗口尺寸（换算成 dp），
 * 与 Service 用的是同一套常量、同一个密度，两者永远相等，不可能裁。
 *
 * ============================================================
 * CPS 刷新
 * ============================================================
 * CPS 是"最近 1 秒内的点击数"，它**会随时间自然衰减**（不按键时从 3 变 2 变 0）。
 * 光靠按键事件驱动刷新的话，松开鼠标后数字会停在最后一个值不动，
 * 所以这里在开启 CPS 时起一个定时器持续重算。
 *
 * ============================================================
 * 两个重载：固定配置 / 可观察配置
 * ============================================================
 * 多悬浮窗之后，"这个窗口显示哪份配置"必须在**窗口存活期间**能改
 * （用户在主页改了配置内容，窗口要立刻跟上而不重建 WebView）。
 * 因此 Service 传进来的是一个 [State] 而不是一个值 ——
 * 直接传值的话 Compose 只会在首次组合时读一次，之后配置再变它也不会重组。
 */
@Composable
fun OverlayContent(
    config: KeyStrokesConfig,
    modifier: Modifier = Modifier,
) {
    OverlayContent(config = rememberUpdatedState(config), modifier = modifier)
}

/** 可观察配置版本：配置对象本身在别处被替换时（例如配置内容改动）能触发重组 */
@Composable
fun OverlayContent(
    config: State<KeyStrokesConfig>,
    modifier: Modifier = Modifier,
) {
    val pressedKeys by CaptureSession.pressedKeys.collectAsState()
    val density = LocalDensity.current.density

    val factor = KeyLayout.pxToDpFactor(density)
    val current = config.value

    /*
     * CPS 数值：只在**真的用到**时定时刷新。
     *
     * ============================================================
     * ⚠️ 判据是"这份布局里到底有没有 CPS 槽位"
     * ============================================================
     * 这里连着踩过两次坑，都是**判据与渲染不一致**造成的:
     *
     * 1. 第一版只看 `mouseCpsEnabled` —— 那是 Key 样式的配置项。
     *    自定义 Key 的组件可以绑 CPS，但它的 `mouseCpsEnabled` 是 false，
     *    于是刷新循环根本不起来，数字**永远停在初始值**；
     * 2. 第二版按样式分支（Key 看开关、自定义看组件文字），
     *    ⚠️ 但**漏了「手柄（标准）」样式** —— 它走的是 `KeyLayout`，
     *    于是"显示 CPS 打开后数字不动"（用户报的"CPS 开关无效"）。
     *
     * 现在改成**问布局本身**:
     *
     * - 走 `KeyLayout` 的样式（键盘 / 标准）→ 看 `keys()` 里
     *   有没有 `CPS_L` / `CPS_R` 槽位。那就是"会不会画 CPS"这件事本身，
     *   与渲染用的是**同一个函数、同一个判据**；
     * - 自定义 Key → 看有没有组件的文字里写了 `(cps)` / `(cps2)`。
     *
     * ⚠️ 关键原则:**判据必须与渲染同源**。两边各写一套的话，
     * 表现就是"定时器在跑但屏幕上没东西"或者反过来 ——
     * 而这两种都极难从现象联想到原因。
     *
     * 另外数值取自**这份配置自己的计数器**（CaptureSession.cpsSnapshotOf）——
     * 用全局那个的话，别的配置也在数同一个位置时，数字会翻倍。
     */
    val cpsRefreshKey = when (current.styleId) {
        StyleId.CUSTOM_KEY -> CustomLayout.usesCps(current.custom.components)

        /*
         * ⚠️ 模式 1 的 CPS 接在主文字后面、**不在槽位里** —— 所以
         * `mouseCpsEnabled` 也要算进去，不然模式 1 不会刷新。
         *
         * ⚠️ 用 `remember(config)` 缓存:这个表达式会跑一遍 `keys()`，
         * 而 `OverlayContent` 是悬浮窗的根组合函数、重组很频繁 ——
         * 每次重组都算一遍布局是白费功夫。
         */
        else -> remember(current) {
            current.mouseCpsEnabled || KeyLayout.keys(current).any {
                it.slotId == KeyLayout.Id.CPS_L || it.slotId == KeyLayout.Id.CPS_R
            }
        }
    }
    val configId = current.id

    var cps by remember { mutableStateOf(emptyMap<String, Int>()) }
    LaunchedEffect(cpsRefreshKey, configId) {
        if (!cpsRefreshKey) {
            cps = emptyMap()
            return@LaunchedEffect
        }
        while (true) {
            cps = CaptureSession.cpsSnapshotOf(configId)
            delay(CPS_REFRESH_MS)
        }
    }

    val windowWidthDp = (overlayWindowWidthPx(current) * factor).dp
    val windowHeightDp = (overlayWindowHeightPx(current, cps) * factor).dp

    Box(
        modifier = modifier
            .size(windowWidthDp, windowHeightDp)
            .padding((OVERLAY_PADDING_PX * factor).dp),
    ) {
        /*
         * 按样式分流。（Live2D 不在这里 —— 它的宿主是 WebView，
         * 由 OverlayService 直接作为窗口根视图挂上去，见下面那段注释。）
         */
        val sticks by CaptureSession.sticks.collectAsState()

        if (current.styleId == StyleId.GAMEPAD1 || KeyLayout.usesJoystickLayout(current)) {
            /*
             * 手柄样式。
             *
             * ⚠️ 它们是**唯二**消费 [CaptureSession.sticks] 的样式 ——
             * 那个通道装的是归一化后的摇杆状态（`-1..1`）。
             * 键盘样式与自定义 Key 不看它（它们只有"按下/没按下"）。
             *
             * ⚠️ 判据用 `KeyLayout.usesJoystickLayout` 而不是
             * `styleId == GAMEPAD2` —— 那个函数是"这个样式走不走
             * 键盘布局 + 摇杆"的**唯一判据**，布局层与渲染层共用它。
             * 这里再写一遍比较，加样式时就会漏一处。
             */
            val stickScale = factor * KeyLayout.uiScale(current)

            if (current.styleId == StyleId.GAMEPAD1) {
                Gamepad1Content(
                    sticks = sticks,
                    pressedCodes = pressedKeys,
                    scale = stickScale,
                )
            } else {
                Gamepad2Content(
                    config = current,
                    sticks = sticks,
                    pressedCodes = pressedKeys,
                    scale = stickScale,
                    /* CPS 数值要传:鼠标键的 CPS 显示与键盘样式同一套 */
                    cpsBySlot = cps,
                )
            }
        } else if (current.styleId == StyleId.CUSTOM_KEY) {
            /*
             * 自定义 Key：画布上的一切由组件列表决定。
             *
             * 用**同一个** CustomKeyCanvas 渲染，编辑器里看到的与这里逐像素一致；
             * CPS 数值也传进去，组件文字里的 `(cps)` / `(cps2)` 占位符才会跟着动。
             *
             * `slotIdOf` 把"组件指向的那个键码"翻译成键位 id ——
             * 计数是按键位记的（同一个映射位置下的键共享计数），
             * 翻译表就是配置自己的键位映射。哪个组件指向哪个键码由
             * `CustomLayout.cpsKeyCodesOf` 回答（按键组件用它自己监听的键，
             * 文本组件用它的 cpsKeyCodes）。
             *
             * ============================================================
             * ⚠️ 内容必须先搬进"窗口坐标"，而且要用 toWindow 而不是手写偏移
             * ============================================================
             * 窗口只覆盖内容的**包围盒**，而组件坐标的原点是**定位区**的左上角 ——
             * 两者不是同一个原点（组件可以放在 (300, 400)，也可以是负坐标）。
             *
             * 这里踩过一个很迷惑的坑：手写偏移量时把符号写反了（用了 `−left`），
             * 于是内容被整体推出可视区，屏幕上只剩靠下的一小部分
             * （默认布局里就是那行 CPS 文本）甚至什么都不剩 ——
             * 而编辑器画布里完全正常（那边不画在窗口里），极难定位。
             *
             * 现在改用 `CustomLayout.toWindow`：它是纯函数、有测试钉着
             * "每个组件都落在窗口之内"，符号不可能再写错。
             *
             * 窗口尺寸（样式注册表那边）与这里用的是**同一个** `bounds` ——
             * 同一个纯函数，必然同一个结果。
             */
            val windowed = CustomLayout.toWindow(current.custom.components)

            CustomKeyCanvas(
                settings = current.custom.copy(components = windowed.components),
                pressedCodes = pressedKeys,
                scale = factor * KeyLayout.uiScale(current),
                cpsBySlot = cps,
                slotIdOf = { code -> KeyLayout.codeToSlotMap(current)[code] },
                baseWidth = windowed.bounds.width,
                baseHeight = windowed.bounds.height,
                overallAlpha = current.customOpacityPercent.coerceIn(0, 100) / 100f,
                /*
                 * ⚠️ 必须自适应。
                 *
                 * 窗口尺寸是按公式"推算"的，而容器实际给的空间可能与它
                 * 差几个像素（实测差过 11px）。自己算尺寸 + 指望容器给得起
                 * 的结果就是画布被压小，而**声明宽度等于画布宽**的那些组件
                 * （空格、Shift）被裁 —— 就是那个很难查的"空格短一截"。
                 *
                 * 自适应之后，画布严格等于容器给的空间，不可能溢出。
                 */
                fitToContainer = true,
            )
        } else {
            /*
             * 这里只画**按键样式**。
             *
             * Live2D 不走 Compose：它的宿主是普通的 FrameLayout + WebView
             * （见 ui/overlay/live2d/Live2DOverlayView），由 OverlayService 直接
             * 作为窗口根视图挂上去。原因写在那个类的注释里 —— 塞进 Compose 的
             * 互操作层时它渲染不出来，而旧项目那条路是跑通的。
             *
             * 也就是说，"按样式决定用什么画"这个分支在 OverlayService.createOverlayView()，
             * 全项目只有那一处。
             */
            KeyGrid(
                config = current,
                pressedCodes = pressedKeys,
                scale = factor * KeyLayout.uiScale(current),
                cpsBySlot = cps,
            )
        }
    }
}
/*
 * ============================================================
 * 窗口尺寸
 * ============================================================
 * 悬浮窗用 WindowManager，窗口大小必须以**像素**给出（它不会按内容自适应）。
 * 尺寸来自 [OverlayStyleRegistry.baseSizeOf]：由**样式自己**声明内容有多大，
 * 再统一乘缩放。这里不允许再去直接问 [KeyLayout] ——
 * 那样加了第二种样式之后，窗口与内容的口径必然会分叉。
 */

/** 悬浮窗外框内边距（像素）：给描边留空间，避免贴边被裁 */
const val OVERLAY_PADDING_PX = 6f

/** 窗口最小边长（像素）：配置极小时避免窗口退化成一条线 */
const val OVERLAY_MIN_SIZE_PX = 48f

/** CPS 刷新间隔：约 10fps 就够（数字变化不需要每帧算） */
private const val CPS_REFRESH_MS = 100L

/**
 * 窗口宽度（像素）。
 *
 * ⚠️ 宽度必须与高度用**同一个来源**（[OverlayStyleRegistry.baseSizeOf]）。
 *
 * 曾经这里是写死的 `KeyLayout.BASE_WIDTH * 缩放`，而高度一直是问样式要的 ——
 * 两种口径并存。当时没出问题只是因为按键样式的 `baseSize` 恰好也是
 * `KeyLayout.BASE_WIDTH`，一旦有了尺寸不同的样式（自定义 Key 的宽度
 * 是所有组件的包围盒）就会立刻分叉。
 *
 * 排查"悬浮窗右边被裁"时我一度以为是这里写死了，但那段代码其实已经是
 * 问样式要的 —— **真正的病因在别处**（转换时多做了一次居中平移，
 * 使组件坐标与包围盒偏移叠加了两次，见 `KeyToCustomConverter`）。
 * 把这段注释留下，是为了让下一个人不必再走一遍同样的弯路。
 */
fun overlayWindowWidthPx(config: KeyStrokesConfig): Float = (
    OverlayStyleRegistry.baseSizeOf(config).width * KeyLayout.uiScale(config) +
        OVERLAY_PADDING_PX * 2
    ).coerceAtLeast(OVERLAY_MIN_SIZE_PX)

/**
 * 窗口高度（像素）。
 *
 * @param cpsBySlot CPS 数值。**必须传**：CPS 模式 2 会多出一行、模式 3 会让键变高，
 *   窗口尺寸按"没有 CPS"算的话，那部分内容就会被裁掉。
 *   （Live2D 样式的尺寸与它无关，由样式自己忽略。）
 */
fun overlayWindowHeightPx(
    config: KeyStrokesConfig,
    cpsBySlot: Map<String, Int> = emptyMap(),
): Float = (
    OverlayStyleRegistry.baseSizeOf(config, cpsBySlot).height * KeyLayout.uiScale(config) +
        OVERLAY_PADDING_PX * 2
    ).coerceAtLeast(OVERLAY_MIN_SIZE_PX)

/**
 * 窗口尺寸用的取整：**向上取整**，不是截断。
 *
 * ⚠️ 内容尺寸是小数（`bounds.width × 缩放`），而窗口必须以整数像素给出。
 * 用 `toInt()` 截断的话，窗口会比内容**小一点点** ——
 * 而这一丁点正好落在最右/最下的那几个像素上，表现是**边缘被裁掉一条**，
 * 而且缩放不是整数时才出现（例如 155.5 → 155），极难联想到取整。
 *
 * 宁可多一个像素（外面多一条透明边，看不出来），也不能少 ——
 * 少的那一个像素切掉的是用户内容。
 */
fun toWindowPx(value: Float): Int = kotlin.math.ceil(value).toInt().coerceAtLeast(1)

/** dp → 像素（默认位置等按 dp 思考的地方用） */
fun dpToPx(dp: Float, density: Float): Int = (dp * density).toInt().coerceAtLeast(1)

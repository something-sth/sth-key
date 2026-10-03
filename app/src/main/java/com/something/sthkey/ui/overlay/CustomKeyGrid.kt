package com.something.sthkey.ui.overlay

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import com.something.sthkey.ui.component.bitmapSpecOf
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.something.sthkey.core.log.AppLog
import com.something.sthkey.domain.config.AnimationMode
import com.something.sthkey.domain.config.KeyStrokesConfig
import com.something.sthkey.domain.config.ShadowMode
import com.something.sthkey.domain.custom.CustomComponent
import com.something.sthkey.domain.custom.CustomLayout
import com.something.sthkey.domain.custom.CustomLayoutSettings
import com.something.sthkey.domain.custom.KeyComponent
import com.something.sthkey.domain.custom.TextComponent
import com.something.sthkey.domain.style.KeyLayout
import com.something.sthkey.ui.component.composeFontFamily

/**
 * 自定义 Key 的画布渲染（悬浮窗与编辑器/预览**共用同一份**）。
 *
 * ============================================================
 * 为什么只有一份
 * ============================================================
 * "编辑器里看到的"和"悬浮窗上显示出来的"必须逐像素一致，
 * 否则用户排好的版到了屏幕上会错位。所以编辑器不做"看起来差不多"的简化版本，
 * 而是直接调这里的 [CustomKeyCanvas] —— 顶多叠一层选中框与吸附线（在调用方画）。
 *
 * ============================================================
 * 坐标系
 * ============================================================
 * 组件坐标是**基础坐标**（与 [KeyLayout] 同一体系）。这里把每个值乘 [scale]
 * 换成 dp：`scale` 由调用方给出（悬浮窗是 `密度系数 × 整体缩放`，
 * 编辑器还要再乘一个"画布适配屏幕"的倍率）。
 *
 * ============================================================
 * 窗口尺寸 = 内容包围盒，所以内容要**先搬进窗口坐标**
 * ============================================================
 * 组件坐标的原点是**定位区**的左上角，而窗口只覆盖内容的包围盒 ——
 * 两者不是同一个原点（组件可以放在负坐标，也可以离原点很远）。
 *
 * ⚠️ 搬运**不要**在这里手写偏移量，用 [CustomLayout.toWindow]（纯函数、有测试）。
 * 这里曾经用一对 `contentOffsetX/Y` 让调用方自己传，结果符号写反了：
 * 内容被整体推出可视区，屏幕上只剩靠下的一小部分（默认布局里就是那行 CPS 文本），
 * 而编辑器画布里完全正常，极难定位。
 *
 * 现在的分工是：**调用方负责搬，这里只负责画**。
 *
 * - **悬浮窗 / 配置预览**：`CustomLayout.toWindow(components)` 之后再传进来，
 *   并且把 [baseWidth]/[baseHeight] 传成包围盒尺寸 ——
 *   于是根容器正好框住内容，窗口尺寸这个参数才真的起作用；
 * - **编辑器画布**：直接传原始组件（画的就是整个定位区），
 *   [baseWidth]/[baseHeight] 传定位区尺寸。窗口范围是另外用一个框标出来的。
 *
 * 默认值取"包围盒"是给预览与测试省事的：单测里直接传几个组件就能画。
 */
/**
 * 读**容器实际给的空间**，反推出内容该用多大的缩放。
 *
 * ============================================================
 * 为什么不能自己算尺寸（这里踩过一个很难查的坑）
 * ============================================================
 * 原来是 `Modifier.size((baseWidth × scale).dp)` —— 自己算一个尺寸，
 * 然后**指望**容器给得起。窗口尺寸那边也是按同一个公式算的，
 * 所以"纸面上"两边必然相等，怎么核都对。
 *
 * 但实测不是：画布请求 272.1px、只拿到 261.0px（少 11.1px），
 * 而容器在纸面上明明有 273px 可用。于是画布被裁，
 * **只有声明宽度等于画布宽的那两个组件（空格、Shift）显形** ——
 * 表现为"悬浮窗上空格右边短一截"，而预览与编辑页（不受窗口约束）完全正常。
 *
 * 反复推演都得出"应该够"，所以这条路走不通：只要还在"自己算尺寸 +
 * 指望容器配合"，就总有一个我没找到的环节能把那几像素吃掉。
 *
 * 这里改成**反过来**：先问容器给了多少（`BoxWithConstraints`，
 * 它给的是**经过所有约束之后**的真实值），再据此反推缩放。
 * 于是"内容超出容器"在数学上不可能发生 —— 不依赖任何推算准确。
 *
 * 取宽高两个比例的**较小值**，保证两个方向都装得下；两个方向都不至于
 * 溢出，最多是内容比容器略小一点点（肉眼不可见）。
 *
 * @param baseWidth/baseHeight 内容的基础尺寸（自定义 Key 用的是包围盒）
 * @param content 用反推出来的缩放渲染内容
 */
@Composable
fun FittedKeyCanvas(
    baseWidth: Float,
    baseHeight: Float,
    /** 名义缩放（窗口尺寸是按它算的）；只用来兜底与诊断 */
    nominalScale: Float,
    /** 倍率上限，见 [computeFittedScale] 的说明 */
    maxScale: Float = Float.MAX_VALUE,
    /** 内容：拿到**已经反推好的 dp 倍率**，并要求按内容尺寸摆放 */
    content: @Composable BoxScope.(fittedScale: Float) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val pxPerDp = density.density

        // 容器真实可用的像素尺寸
        val availableWidthPx = with(density) { maxWidth.toPx() }
        val availableHeightPx = with(density) { maxHeight.toPx() }

        /*
         * 反推缩放：容器像素 ÷ 基础尺寸。
         *
         * ⚠️ 基础尺寸先取到局部变量：`BoxWithConstraints` 作用域里有
         * 同名的 `maxWidth`/`maxHeight`（Dp 类型），参数名一旦撞上
         * 会出现"operator 修饰符缺失"这种看不懂的报错。
         */
        val contentBaseWidth = baseWidth
        val contentBaseHeight = baseHeight

        val fittedScalePxPerBase = computeFittedScale(
            availableWidthPx = availableWidthPx,
            availableHeightPx = availableHeightPx,
            baseWidth = contentBaseWidth,
            baseHeight = contentBaseHeight,
            maxScale = maxScale,
        )

        /*
         * ⚠️ 换算成 **dp / 基础单位** 才能当 `scale` 用。
         *
         * [computeFittedScale] 给的是像素比（因为容器空间是像素），
         * 而项目里 `scale` 的语义是 dp 比 —— 组件内部还会再乘一次密度。
         * 少除这一次密度的后果是内容被放大到约 3 倍、只看得见一个角。
         */
        val fittedScale = fittedScalePxPerBase / pxPerDp

        CanvasMeasurement.record(
            requestedWidthPx = contentBaseWidth * nominalScale * pxPerDp,
            requestedHeightPx = contentBaseHeight * nominalScale * pxPerDp,
            actualWidthPx = contentBaseWidth * fittedScalePxPerBase,
            actualHeightPx = contentBaseHeight * fittedScalePxPerBase,
            availableWidthPx = availableWidthPx,
            availableHeightPx = availableHeightPx,
        )

        /*
         * ⚠️ 内容必须摆在一个**恰好等于内容尺寸**的盒子里。
         *
         * 调用方拿到的是 `BoxScope`，必须写
         * `.size(baseWidth × fittedScale, …)`；直接 `fillMaxSize()` 的话，
         * 盒子的坐标原点会落在容器左上角，而组件的 `offset` 是相对
         * 画布原点算的 —— 结果是所有组件整体偏移（"全错位"）。
         *
         * 想居中就把这个盒子 `align(Alignment.Center)`，
         * 而不是把容器本身居中。
         */
        content(fittedScale)
    }
}

/**
 * 按容器可用空间反推内容缩放 —— [FittedKeyCanvas] 的全部算法。
 *
 * ============================================================
 * ⚠️ 返回的是"像素 / 基础单位"，**不是** dp / 基础单位
 * ============================================================
 * 这一步很容易搞错（我就在这里把界面放大到只看得见一个角）：
 * 容器给的空间是**像素**，所以算出来的比例是**像素比**。
 * 而整个项目里 `scale` 这个参数的语义是 **dp / 基础单位**
 * （调用点一律写成 `factor * uiScale(...)`，其中 `factor = 1/密度`），
 * 组件内部还要再乘一次密度才是像素。
 *
 * 所以调用方**必须**再除以密度才能把它当成 `scale` 用。
 * 两套单位混用的表现是：内容被放大到密度的倍数（约 3 倍），
 * 严重溢出窗口 —— 而这看起来像"布局全乱了"，很难联想到单位问题。
 *
 * 提成纯函数是为了能写测试：那条性质（内容绝不超出容器）
 * 必须被钉住，而它恰恰是"空格被裁"那个 bug 的正解。
 * 放在 composable 里就只能靠肉眼看，等于没保证。
 *
 * @param availableWidthPx/availableHeightPx 容器**真实**给的空间（像素）
 * @param baseWidth/baseHeight 内容的基础尺寸（自定义 Key 用包围盒）
 * @return 像素 / 基础单位；容器或内容尺寸无效时返回 0（调用方据此不画内容，
 *   而不是拿 `Infinity`/`NaN` 去布局 —— 那会让整个界面消失）
 */
fun computeFittedScale(
    availableWidthPx: Float,
    availableHeightPx: Float,
    baseWidth: Float,
    baseHeight: Float,
    /**
     * 倍率上限。
     *
     * 悬浮窗传 [Float.MAX_VALUE]（容器给多少就用多少，它本来就是为了
     * 装下内容而开的窗口）；**配置编辑页的缩略图必须传上限**，
     * 因为它有一条"只缩小、不放大"的规则（`fit` 里的 `1f`）——
     * 不留上限时内容会被撑满预览框，看起来就是"预览突然变大了"。
     */
    maxScale: Float = Float.MAX_VALUE,
): Float {
    if (baseWidth <= 0f || baseHeight <= 0f) return 0f
    if (availableWidthPx <= 0f || availableHeightPx <= 0f) return 0f

    /*
     * 取宽高两个比例的**较小值**。
     *
     * 用较大的那个会让其中一个方向溢出 —— 而溢出的那个方向
     * 正好是"最长的那一行/那一列"贴边的组件，被裁掉一截，
     * 其它组件却毫发无损。这正是"只有空格和 Shift 短一截"的成因，
     * 所以这里必须保证两个方向都装得下。
     */
    val fitted = minOf(
        availableWidthPx / baseWidth,
        availableHeightPx / baseHeight,
    )

    // 上限也要防呆：上限本身为负或 0 时不要返回 0（那会让内容消失）
    return if (maxScale > 0f) minOf(fitted, maxScale) else fitted
}

/**
 * 自定义 Key 画布。
 *
 * @param scale 名义缩放（窗口尺寸按它算）。**实际渲染用的是反推值** ——
 *   见 [FittedKeyCanvas]。两者通常只差千分之几。
 */
@Composable
fun CustomKeyCanvas(
    settings: CustomLayoutSettings,
    pressedCodes: Set<Int>,
    scale: Float,
    modifier: Modifier = Modifier,
    cpsBySlot: Map<String, Int> = emptyMap(),
    slotIdOf: (Int) -> String? = { null },
    /** 根容器尺寸（基础坐标）；默认 = 包围盒。传定位区尺寸就是整个定位区 */
    baseWidth: Float = CustomLayout.bounds(settings.components).width,
    baseHeight: Float = CustomLayout.bounds(settings.components).height,
    /**
     * 是否按容器实际空间自适应（悬浮窗/预览这类"有真实容器"的场合）。
     *
     * 编辑器的画布**不能**自适应：它画的是固定 600×600 的定位区，
     * 内容尺寸必须与滑块刻度严格对应，跟着容器伸缩的话
     * "拖动组件"和"看到的位移"就对不上了。
     */
    fitToContainer: Boolean = false,
    /**
     * 自适应时的倍率上限。
     *
     * 悬浮窗不设上限（容器给多少就用多少）；**配置编辑页的缩略图要传**，
     * 因为它有一条"只缩小、不放大"的规则 —— 不留上限时内容会被撑满预览框。
     */
    maxFitScale: Float = Float.MAX_VALUE,
) {
    if (!fitToContainer) {
        CanvasMeasurement.recordExact(
            widthPx = baseWidth * scale * LocalDensity.current.density,
            heightPx = baseHeight * scale * LocalDensity.current.density,
        )
        Box(modifier = modifier.size((baseWidth * scale).dp, (baseHeight * scale).dp)) {
            CustomKeyCanvasContent(settings, pressedCodes, scale, cpsBySlot, slotIdOf)
        }
        return
    }

    FittedKeyCanvas(
        baseWidth = baseWidth,
        baseHeight = baseHeight,
        nominalScale = scale,
        maxScale = maxFitScale,
    ) { fittedScale ->
        /*
         * 盒子**恰好等于内容尺寸**，再用 align 把自己摆在容器中间。
         *
         * 中心对齐的是这个盒子，盒子内部的坐标原点仍是内容的左上角 ——
         * 组件坐标不会被改动。（写成 `fillMaxSize() + contentAlignment`
         * 会让坐标原点跑到容器中心，全部组件一起偏，见上面那段说明。）
         */
        Box(
            modifier = modifier
                .size(
                    (baseWidth * fittedScale).dp,
                    (baseHeight * fittedScale).dp,
                )
                .align(Alignment.Center),
        ) {
            CustomKeyCanvasContent(settings, pressedCodes, fittedScale, cpsBySlot, slotIdOf)
        }
    }
}

/** 画布里逐个摆组件；尺寸由外层决定（自适应或固定） */
@Composable
private fun CustomKeyCanvasContent(
    settings: CustomLayoutSettings,
    pressedCodes: Set<Int>,
    scale: Float,
    cpsBySlot: Map<String, Int>,
    slotIdOf: (Int) -> String?,
) {
    settings.components.forEach { component ->
        CustomComponentView(
            component = component,
            pressed = component.isPressed(pressedCodes),
            scale = scale,
            cpsBySlot = cpsBySlot,
            slotIdOf = slotIdOf,
        )
    }
}

/** 组件这一刻是否"按下"：只对按键组件有意义，文本组件永远返回 false */
private fun CustomComponent.isPressed(pressedCodes: Set<Int>): Boolean = when (this) {
    is KeyComponent -> inputKeyCodes.any { it in pressedCodes }
    is TextComponent -> false
}

/**
 * 单个组件。
 *
 * 绘制顺序与 Key 样式的 [KeyGrid] 刻意保持一致（底色 → 扩散覆盖层 → 文字），
 * 那边踩过的坑这里就都不用再踩一遍：
 * - 扩散圆必须**画在文字下面**，否则按下去会糊住文字；
 * - 扩散用"裁剪一个满键帽大小的色块"实现，不用颜色插值 ——
 *   颜色混合 + 圆形裁剪会在边缘抗锯齿处偏色（详见 KeyGrid 的注释）；
 * - 覆盖层要按键帽形状裁掉，否则圆角处会溢出。
 */
@Composable
private fun CustomComponentView(
    component: CustomComponent,
    pressed: Boolean,
    scale: Float,
    cpsBySlot: Map<String, Int>,
    slotIdOf: (Int) -> String?,
) {
    val style = component.style
    val mode = (component as? KeyComponent)?.animationMode ?: AnimationMode.NONE
    val durationSec = (component as? KeyComponent)?.animationDurationSec ?: 0f

    /*
     * 进度：0 = 未按下，1 = 完全按下。
     *
     * 保留 State 对象本身而不是解包成 Float：扩散动画要在绘制 lambda 里读它，
     * 读解包后的普通值不会触发逐帧重绘（表现就是没有动画）。
     */
    val progressState = animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = if (mode == AnimationMode.NONE) {
            tween(durationMillis = 0)
        } else {
            tween(durationMillis = (durationSec * 1000f).toInt(), easing = LinearEasing)
        },
        label = "custom-component-progress",
    )
    val progress = progressState.value

    val widthDp = component.width * scale
    val heightDp = component.height * scale
    // 诊断用：把 dp 换算成像素来比对（见 CanvasMeasurement）
    val pxPerDp = LocalDensity.current.density

    val upFill = CustomLayout.fillColor(style, pressed = false)
    val downFill = CustomLayout.fillColor(style, pressed = true)
    val upText = CustomLayout.textColor(style, pressed = false)
    val downText = CustomLayout.textColor(style, pressed = true)

    val isRipple = mode == AnimationMode.RIPPLE

    /*
     * 底色按模式分三类（与 KeyGrid 完全一致）：
     * - 无动画：瞬间切换；
     * - 颜色渐变：底色按进度插值；
     * - 扩散收缩：底色**始终是未按下色**，按下态作为一层圆从中心盖上来 ——
     *   底色若跟着瞬时值切换，圆和底色就是同一个颜色，扩散过程看不见。
     */
    val fillColor = when (mode) {
        AnimationMode.FADE -> blendColor(upFill, downFill, progress)
        AnimationMode.RIPPLE -> Color(upFill)
        AnimationMode.NONE -> Color(CustomLayout.fillColor(style, pressed))
    }

    // 文字颜色：只有渐变模式跟着插值，其余直接切换（文字要始终清晰）
    val textColor = if (mode == AnimationMode.FADE) {
        blendColor(upText, downText, progress)
    } else {
        Color(CustomLayout.textColor(style, pressed))
    }

    val outlineWidth = CustomLayout.outlineWidth(style)
    /*
     * 描边颜色也分按下与未按下，与底色/文字同一个处理：
     * 渐变模式跟着插值，其余模式直接切换。
     */
    val outlineColor = if (mode == AnimationMode.FADE) {
        blendColor(
            from = CustomLayout.outlineColor(style, pressed = false),
            to = CustomLayout.outlineColor(style, pressed = true),
            fraction = progress,
        )
    } else {
        Color(CustomLayout.outlineColor(style, pressed))
    }

    /* 文字阴影：颜色同样跟按下状态走；null 表示没启用 */
    val shadowSize = CustomLayout.shadowSize(style)
    val shadowArgb = if (mode == AnimationMode.FADE) {
        blendArgb(
            from = CustomLayout.shadowColor(style, pressed = false),
            to = CustomLayout.shadowColor(style, pressed = true),
            fraction = progress,
        )
    } else {
        CustomLayout.shadowColor(style, pressed)
    }
    val shadowColor = Color(shadowArgb)

    val radiusDp = CustomLayout.cornerRadius(style, minOf(widthDp, heightDp))
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(radiusDp.dp)

    val fontSize = (CustomLayout.textSize(component.textScalePercent) * scale).sp
    val fontFamily = composeFontFamily(style.fontId)

    val rippleColor = if (isRipple) Color(downFill) else Color.Transparent

    // 要盖满整个圆角矩形，半径得达到最远角：hypot(w/2-r, h/2-r) + r
    val maxCorner = minOf(widthDp, heightDp) / 2f
    val cornerPx = radiusDp.coerceIn(0f, maxCorner)
    val coverRadiusDp = kotlin.math.hypot(widthDp / 2f - cornerPx, heightDp / 2f - cornerPx) + cornerPx

    Box(
        modifier = Modifier
            .offset(
                x = (component.x * scale).dp,
                y = (component.y * scale).dp,
            )
            .size(widthDp.dp, heightDp.dp)
            /*
             * 把**布局结果**记下来（诊断用）。
             *
             * 声明的宽度（[CustomComponent.width]）在域层已经反复验证是对的，
             * 所以"悬浮窗上空格短一截"这件事只可能出在**布局实际给了多少**。
             * 这里同时记"声明的像素宽"与"实际量到的像素宽"。
             */
            .onGloballyPositioned { coordinates ->
                CanvasMeasurement.addComponentBox(
                    CanvasMeasurement.ComponentBox(
                        id = component.id,
                        declaredWidthPx = widthDp * pxPerDp,
                        actualWidthPx = coordinates.size.width.toFloat(),
                    ),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(widthDp.dp, heightDp.dp)
                .background(fillColor, shape)
                .then(
                    if (outlineWidth > 0f) {
                        Modifier.border(outlineWidth.dp, outlineColor, shape)
                    } else {
                        Modifier
                    },
                )
                .drawWithContent {
                    drawContent()

                    if (!isRipple) return@drawWithContent
                    val animated = progressState.value
                    if (animated <= 0f) return@drawWithContent

                    val maxRadiusPx = coverRadiusDp * density
                    clipPath(
                        path = Path().apply {
                            addOval(
                                Rect(
                                    center = Offset(size.width / 2f, size.height / 2f),
                                    radius = maxRadiusPx * animated,
                                ),
                            )
                        },
                    ) {
                        drawRect(color = rippleColor)
                    }
                }
                .clip(shape),
        )

        /*
         * 文字：**一段文字替换一遍就完事**。
         *
         * ============================================================
         * ⚠️ 这里曾经画两遍（一个真实的 bug）
         * ============================================================
         * 早先的写法是"主文字画一遍、CPS 行再画一遍"，而主文字用的是**没替换过的模板**。
         * 于是文本组件的 `CPS: (cps)` 在屏幕上是两行：
         * `CPS: (cps)` 和 `CPS: 5` —— 而且注释里还写着"只渲染替换后的那一行"。
         *
         * 现在与 Key 样式完全一致：**整段文字替换一遍**。
         * `LMB(cps2)` → `LMB`（为 0）或 `LMB 5`。
         *
         * 数值由 [CustomLayout.cpsOf] 算：一个组件可以统计**多个键**，
         * 结果按键位去重后累加（左右 Shift 共享一个位置，不能加两遍）。
         */
        val template = CustomLayout.primaryTextOf(component)
        /*
         * 每个占位符各算各的数值。
         *
         * 按键组件只有一组键位（它监听的那些），所以它的多个占位符
         * 会得到相同的数值 —— 这是合理的：那个组件的语义就是"这一个键"。
         * 文本组件则可以给每个占位符配不同的键位（见组件属性面板里的列表）。
         */
        val cpsValues = CustomLayout.cpsValuesOf(component, cpsBySlot, slotIdOf)
        val cpsValue = cpsValues.firstOrNull() ?: 0
        val text = CustomLayout.displayTextAt(component, cpsValues)

        /*
         * CPS 那一行的额外缩放（只有按键组件有这个字段）。
         *
         * 文本组件整块就是 CPS，所以它没有"哪一行更小"这回事，
         * 一律 100（由它自己的文字缩放决定大小）。
         */
        val cpsLineScale = (component as? KeyComponent)?.cpsTextScalePercent ?: 100

        /*
         * 诊断：这一段文字到底算出了什么。
         *
         * ============================================================
         * 为什么值得留一条常驻日志
         * ============================================================
         * "CPS 不显示数字"这类问题涉及**三层**：文字里的模板是什么、
         * 模板里的键码翻译成了哪个槽位、那个槽位这一刻的数值是多少。
         * 光看屏幕只能看到最后一层的结果（一个和模板一样的字符串），
         * 分不清是"没写占位符""翻译不出来"还是"数值确实是 0"。
         *
         * 这条日志把三层一起打出来，一次就能定位。节流到每秒最多一条；
         * 调试页里还有对应的面板（`cpsDiagnosticReport`），那个才是给人看的。
         */
        if (CustomLayout.hasCpsPlaceholder(template)) {
            logCpsDiagnostic(component, template, text, cpsValue, cpsBySlot, slotIdOf)
        }

        val textOffsetXDp = (component.textOffsetX * scale).dp
        val textOffsetYDp = (component.textOffsetY * scale).dp

        if (text.isNotEmpty()) {
            val shadowSpan = shadowSize ?: 0f
            val contentPadding = if (shadowSpan <= 0f) {
                0.dp
            } else {
                (shadowSpan * 2f).coerceAtMost(minOf(widthDp, heightDp) / 4f).dp
            }

            /*
             * 文字阴影：与 Key 样式**完全相同的做法** ——
             * 一整层只画阴影，再用 [BlendMode.DstOut] 把文字轮廓抠掉，
             * 于是只剩"文字之外"的那圈阴影。
             *
             * ⚠️ 为什么不能"画两层文字"：文字不透明时看着没问题，
             * 但文字半透明时阴影会从字缝里透出来，看起来像阴影盖住了文字。
             * 详细说明见 KeyGrid 里那段注释，两边必须保持一致 ——
             * 否则同一个"文字阴影"在两个样式下观感不同。
             *
             * ⚠️ `DstOut` 必须配 [CompositingStrategy.Offscreen]：
             * 不隔离图层的话它会擦到**已经画好的键帽底色**，擦出一个洞。
             */
            if (shadowSpan > 0f) {
                val isHard = style.shadowMode == ShadowMode.HARD

                /*
                 * 柔光：优先用**真模糊**（API 31+），与按键样式同一个做法。
                 *
                 * 只靠 `BitmapFontText` 里的多层副本上限不高：那一层画完副本后
                 * 还要用 `DstOut` 擦掉字形本身，剩下的只是"字形外一圈薄晕"，
                 * 而图片字体是像素块 —— 看起来就跟硬阴影一样。
                 *
                 * 半径口径见 `KeyGrid` 里 `REAL_BLUR_RATIO` 的说明。
                 */
                val useRealBlur = !isHard && canUseRealBlur && shadowSpan > 0f

                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .padding(contentPadding)
                        .offset(x = textOffsetXDp, y = textOffsetYDp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .graphicsLayer {
                                compositingStrategy = CompositingStrategy.Offscreen
                            }
                            .then(
                                if (useRealBlur) {
                                    Modifier.realBlur((shadowSpan * CUSTOM_REAL_BLUR_RATIO).dp)
                                } else {
                                    Modifier
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        /* 第一遍：阴影本体 */
                        ComponentText(
                            text = text,
                            color = shadowColor,
                            baseStyle = lineStyle(
                                size = fontSize,
                                blurRadius = if (isHard) 0f else shadowSpan,
                                blurColor = shadowColor,
                            ),
                            fontSize = fontSize,
                            fontFamily = fontFamily,
                            cpsLineScalePercent = cpsLineScale,
                            bitmapFontId = style.bitmapFontId.ifBlank { null },
                            letterSpacingPercent = style.letterSpacing,
                            lineSpacingPercent = style.lineSpacing,
                            /*
                             * 图片字体的柔光半径（**像素**；矢量字体那条路
                             * 读的是 `TextStyle.Shadow.blurRadius`，单位是 dp）。
                             *
                             * ⚠️ 用了真模糊就不能再叠多层副本 ——
                             * 两套一起上会糊成一团，还白白多画 8 遍。
                             */
                            shadowBlurPx = when {
                                isHard -> 0f
                                useRealBlur -> 0f
                                else -> with(LocalDensity.current) { shadowSpan.dp.toPx() }
                            },
                            modifier = Modifier.graphicsLayer {
                                translationX = if (isHard) shadowSpan * 0.6f * density else 0f
                                translationY = if (isHard) shadowSpan * 0.6f * density else 0f
                            },
                        )

                        /* 第二遍：把文字轮廓从这一层里擦掉（颜色只为不透明度，不参与显示） */
                        ComponentText(
                            text = text,
                            color = Color.White,
                            baseStyle = lineStyle(fontSize),
                            fontSize = fontSize,
                            fontFamily = fontFamily,
                            cpsLineScalePercent = cpsLineScale,
                            bitmapFontId = style.bitmapFontId.ifBlank { null },
                            letterSpacingPercent = style.letterSpacing,
                            lineSpacingPercent = style.lineSpacing,
                            /*
                             * 抠洞层必须是**实心剪影**：图片字体默认按字形
                             * 原色绘制，渐变色形的浅色部分 `DstOut` 抠不干净。
                             */
                            asSolidMask = true,
                            modifier = Modifier.graphicsLayer {
                                blendMode = BlendMode.DstOut
                                compositingStrategy = CompositingStrategy.Offscreen
                            },
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(contentPadding)
                    .offset(x = textOffsetXDp, y = textOffsetYDp),
                contentAlignment = Alignment.Center,
            ) {
                ComponentText(
                    text = text,
                    color = textColor,
                    baseStyle = lineStyle(fontSize),
                    fontSize = fontSize,
                    fontFamily = fontFamily,
                    cpsLineScalePercent = cpsLineScale,
                    bitmapFontId = style.bitmapFontId.ifBlank { null },
                    letterSpacingPercent = style.letterSpacing,
                    lineSpacingPercent = style.lineSpacing,
                )
            }
        }
    }
}

/**
 * 组件上的一段文字，**支持换行**。
 *
 * ============================================================
 * 为什么需要它
 * ============================================================
 * 按键样式转自定义时，CPS 模式 3 的"键内两行"要靠换行符保留下来
 * （见 `KeyToCustomConverter`）。如果这里仍然 `maxLines = 1`，
 * 第二行会被**直接截掉** —— 用户会看到 CPS 那行凭空消失。
 *
 * 逐行画而不是交给 `Text` 自己换行：`Text("A\nB")` 的行高由字体度量决定，
 * 与按键样式那条路径（自己定死行高）不一致，同一个键面的行距会随
 * 来源不同而变。
 */
@Composable
private fun ComponentText(
    text: String,
    color: Color,
    baseStyle: TextStyle,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontFamily: androidx.compose.ui.text.font.FontFamily?,
    /** CPS 那一行的额外缩放（1..200），100 = 与主文字一样大 */
    cpsLineScalePercent: Int = 100,
    /**
     * 图片字体的 id；`null` 表示用矢量字体。
     *
     * ⚠️ 判断"这段文字能不能用图片字体画"是**逐行**做的：
     * 图片字体只有 ASCII 字形，而一个组件可能一行是 `LMB`、
     * 另一行是中文。整段回退的话 `LMB` 那行也白搭了。
     */
    bitmapFontId: String? = null,
    /**
     * 柔光半径（像素）；0 表示硬阴影。
     *
     * ⚠️ 专给图片字体：矢量字体的柔光走 `TextStyle.Shadow`（`baseStyle` 里），
     * 而图片字体自己贴图、**读不到 `TextStyle`** —— 不传的话柔光对它就无效。
     */
    shadowBlurPx: Float = 0f,
    /**
     * 是否把字形画成**实心剪影**（抠洞层 `DstOut` 用）。
     *
     * `DstOut` 按源的不透明度擦除，而图片字体默认按字形原色绘制 ——
     * 渐变色形的浅色部分抠不干净，会留一圈毛边。
     */
    asSolidMask: Boolean = false,
    /** 字间距 / 行间距（相对字号的百分比，0 = 不动）；只对图片字体生效 */
    letterSpacingPercent: Float = 0f,
    lineSpacingPercent: Float = 0f,
    modifier: Modifier = Modifier,
) {
    /*
     * 先还原**转义的换行**，再拆行。
     *
     * 转换过来的文字里换行是 `\n` 两个字符（见 `KeyToCustomConverter.LINE_BREAK`）——
     * 用转义而不是真换行，是因为文字输入框是单行的：真换行会让第二行
     * 看不见但存在，用户点那片空白还能改到文字。
     *
     * 这里同时接受真的换行符：老配置里可能存着真的（用户直接按过回车）。
     */
    val lines = text.replace("\\n", "\n").split('\n')

    /*
     * 图片字体：逐行判断能不能用它画，能就用 [BitmapFontText]。
     *
     * 与下面的矢量路径**完全不共用代码**（一个贴图、一个走 Text），
     * 所以放在最前面直接返回，避免两条路径的判断纠缠在一起。
     */
    if (bitmapFontId != null) {
        val spec = remember(bitmapFontId) { bitmapSpecOf(bitmapFontId) }
        if (spec != null && lines.all { canRenderAsBitmap(it, spec) }) {
            val lineStyle = baseStyle.copy(
                lineHeight = fontSize,
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.Both,
                ),
            )

            /*
             * ============================================================
             * ⚠️ 整段**一次画完**，不能逐行各画一次
             * ============================================================
             * 早先是 `lines.forEach { BitmapFontText(it) }` ——
             * 每个 `BitmapFontText` 都是一个独立 Canvas、各自按自身内容高度
             * 居中，于是**多行全部重叠在同一个位置**
             * （用户实测的"CPS 模式 3 转自定义 Key 后两行挤在一起"）。
             *
             * 交给 [BitmapFontText] 一次处理换行，行高与行距才是按整段算的。
             */
            BitmapFontText(
                fontId = bitmapFontId,
                text = lines.joinToString("\n"),
                fontSize = fontSize,
                /*
                 * 颜色照传：图片字体的染色语义是"字形 RGB × 文字颜色"，
                 * 白色字形就是整块染成这个颜色。
                 */
                color = color,
                /*
                 * 阴影**不在这里画**：调用方已经把阴影那一层
                 * 用同一个 [ComponentText] 画过了（只是颜色不同），
                 * 这里再画一次会叠两层阴影，边缘变实。
                 *
                 * 但**柔光半径要传**（见参数说明）。
                 */
                shadowBlurPx = shadowBlurPx,
                asSolidMask = asSolidMask,
                extraAdvance = safeLetterSpacing(lineStyle),
                /*
                 * 逐行字号：**主行 100%、CPS 行按比例**。
                 *
                 * ⚠️ 这里的下标容易写反。`lineScalePercents` 是**相对
                 * [fontSize]** 的倍率，而 `cpsLineScalePercent` 是"CPS 行
                 * 相对主行的百分比"（转换过来是 79）—— 所以
                 * **主行给 100、CPS 行给 `cpsLineScalePercent`**。
                 *
                 * 曾经写成"CPS 行给 100、其余给 `cpsLineScalePercent`"，
                 * 结果主行被放大成 **79 倍**（把 79 当成了 7900%），
                 * 而 CPS 行反倒成了正常大小。用户看到的是"这个滑块没效果"，
                 * 其实整套字号已经乱掉了。
                 *
                 * 口径与矢量路径一致（那边是 `fontSize * percent/100`）。
                 */
                lineScalePercents = lines.map { line ->
                    if (CustomLayout.hasCpsPlaceholder(line)) cpsLineScalePercent else 100
                },
                letterSpacingPercent = letterSpacingPercent,
                lineSpacingPercent = lineSpacingPercent,
                modifier = modifier,
            )
            return
        }
    }

    // 单行且不需要缩放：不必包 Column，少一层布局
    if (lines.size <= 1 && cpsLineScalePercent == 100) {
        Text(
            text = lines.firstOrNull().orEmpty(),
            color = color,
            textAlign = TextAlign.Center,
            style = baseStyle,
            fontFamily = fontFamily,
            modifier = modifier,
        )
        return
    }

    /*
     * ============================================================
     * 矢量路径也要吃字间距 / 行间距
     * ============================================================
     * ⚠️ 早先只有图片字体那条路吃这两个值，矢量字体调了完全没反应 ——
     * 而"字距不合适"在矢量字体上同样会发生（中文字体尤其常见）。
     *
     * 字间距：`TextStyle.letterSpacing` 的单位是 **em 倍数**，
     * 而我们的滑块是"相对字号的百分比" → 除以 100 即可对上。
     *
     * 行间距：直接乘进**行高**。行高本身已经等于字号（见下），
     * 所以"行距 = 行高 × (1 + 行间距)"就是把行与行的基准距离拉开，
     * 而**首行之前不会多出空白** —— 这正是想要的。
     * （用 Spacer 插在行间也行，但那样多一层布局、还要特判首行。）
     */
    val letterSpacingEm = (letterSpacingPercent / 100f).takeIf { it.isFinite() } ?: 0f
    val lineSpacingFactor = if (lineSpacingPercent.isFinite()) {
        (1f + lineSpacingPercent / 100f).coerceAtLeast(MIN_LINE_SPACING_FACTOR)
    } else {
        1f
    }

    val lineStyle = baseStyle.copy(
        lineHeight = fontSize * lineSpacingFactor,
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.Both,
        ),
        letterSpacing = letterSpacingEm.em,
    )

    /*
     * 含 CPS 占位符的行用**更小的字** —— 按键样式的模式 3 就是这么排的
     * （主文字 28、CPS 22）。用逐行测量而不是把尺寸混进文本里，
     * 所以文字内容始终是干净的用户输入。
     */
    val cpsLineSize = fontSize * (cpsLineScalePercent.coerceIn(1, 200) / 100f)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        lines.forEach { line ->
            val isCpsLine = CustomLayout.hasCpsPlaceholder(line)
            Text(
                text = line,
                color = color,
                textAlign = TextAlign.Center,
                style = if (isCpsLine) {
                    // 行高也要跟着这一行自己的字号走，否则小字那行会多出空白
                    lineStyle.copy(
                        fontSize = cpsLineSize,
                        lineHeight = cpsLineSize * lineSpacingFactor,
                    )
                } else {
                    lineStyle
                },
                fontFamily = fontFamily,
                maxLines = 1,
            )
        }
    }
}


/**
 * 行样式：行高等于字号，并去掉上下多余留白。
 *
 * 不这么做的话行高由字体度量决定，换一个行高偏大的字体，
 * 两行之间就会凭空多出一截空白 —— 而且看起来像"组件被拉高了"，
 * 完全查不到原因。让字号决定位置，与字体无关。
 *
 * @param blurRadius 大于 0 时给文字加模糊投影（柔光阴影用）
 */
private fun lineStyle(
    size: androidx.compose.ui.unit.TextUnit,
    blurRadius: Float = 0f,
    blurColor: Color = Color.Unspecified,
): TextStyle = TextStyle(
    fontSize = size,
    lineHeight = size,
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.Both,
    ),
    // 柔光阴影：偏移给 0（要的是"文字周围一圈光晕"，不是往右下挪的影子）
    shadow = if (blurRadius > 0f) {
        Shadow(color = blurColor, offset = Offset.Zero, blurRadius = blurRadius)
    } else {
        null
    },
)

/** CPS 诊断日志的节流时刻（每个进程一份，够用了） */
private var lastCpsLogAt = 0L

/**
 * 生成**CPS 自诊断报告**（调试页直接显示，不依赖日志）。
 *
 * ============================================================
 * 为什么做成面板而不是日志
 * ============================================================
 * "CPS 不显示数字"这类问题涉及三层：文字里的模板是什么、
 * 模板里的键码翻译成了哪个槽位、那个槽位这一刻的数值是多少。
 * 光看悬浮窗只能看到最后一层的结果（一个和模板一样的字符串），
 * 分不清是"没写占位符""翻译不出来"还是"数值确实是 0"。
 *
 * 日志能说清，但要用户去翻日志、还可能被级别/关键字过滤挡掉、
 * 更会被后续日志挤出缓冲区 —— 这不行。所以直接在调试页把三层摆出来。
 *
 * @param configs 要诊断的配置（调用方给"开着悬浮窗的"或全部）
 * @param cpsBySlotOf 取某份配置当前的 CPS 快照
 */
fun cpsDiagnosticReport(
    configs: List<KeyStrokesConfig>,
    cpsBySlotOf: (String) -> Map<String, Int>,
): String {
    if (configs.isEmpty()) return "没有可诊断的配置"

    return buildString {
        configs.forEach { config ->
            val snapshot = cpsBySlotOf(config.id)
            val components = config.custom.components
            val slotMap = KeyLayout.codeToSlotMap(config)

            appendLine("配置「${config.name}」(${config.id.take(8)})")
            appendLine("  CPS 计数：${if (snapshot.isEmpty()) "（还没有任何点击）" else snapshot.toString()}")
            appendLine("  键位映射：${slotMap.entries.joinToString { "${it.key}→${it.value}" }}")

            if (components.isEmpty()) {
                appendLine("  组件：无")
                appendLine()
                return@forEach
            }

            /*
             * 只列出**文字里写了占位符**的组件 —— 那才是会显示 CPS 的。
             * 用与渲染同一个判据（CustomLayout.primaryTextOf + hasCpsPlaceholder），
             * 两边判据不同的话，这个面板会与实际显示对不上，那它就白做了。
             */
            val withPlaceholder = components.filter {
                CustomLayout.hasCpsPlaceholder(CustomLayout.primaryTextOf(it))
            }

            if (withPlaceholder.isEmpty()) {
                appendLine(
                    "  组件：${components.size} 个，但**没有一个写了 (cps) / (cps2) 占位符**" +
                        "（CPS 就是靠占位符显示的，没有占位符就没有 CPS）",
                )
                appendLine()
                return@forEach
            }

            withPlaceholder.forEach { component ->
                val template = CustomLayout.primaryTextOf(component)
                val keyCodes = CustomLayout.cpsKeyCodesOf(component)
                val slots = keyCodes.mapNotNull(slotMap::get).distinct()
                val value = slots.sumOf { snapshot[it] ?: 0 }
                val resolved = CustomLayout.displayText(component, value)

                appendLine(
                    "  组件 ${component.id}：" +
                        "模板 \"$template\"" +
                        " → 键码 ${if (keyCodes.isEmpty()) "**无**（这个组件没有可用的按键映射）" else keyCodes.toString()}" +
                        " → 槽位 ${if (slots.isEmpty()) "**翻译不出来**（这些键不在这份配置的键位映射里）" else slots.toString()}" +
                        " → 数值 $value" +
                        " → 显示 \"$resolved\"",
                )
            }
            appendLine()
        }
    }.trimEnd()
}

/**
 * 把这一段文字的**三层信息**打出来（见调用处的说明）。
 *
 * 节流到每秒最多一条：这段代码在悬浮窗的每一帧都会跑。
 * 调试页有对应的面板（[cpsDiagnosticReport]），日志只是留个历史。
 */
private fun logCpsDiagnostic(
    component: CustomComponent,
    template: String,
    resolved: String,
    value: Int,
    cpsBySlot: Map<String, Int>,
    slotIdOf: (Int) -> String?,
) {
    val now = System.currentTimeMillis()
    if (now - lastCpsLogAt < CPS_LOG_INTERVAL_MS) return
    lastCpsLogAt = now

    val keyCodes = CustomLayout.cpsKeyCodesOf(component)
    val slots = keyCodes.mapNotNull(slotIdOf).distinct()

    AppLog.i(
        TAG,
        "CPS 诊断：组件=${component.id} 模板=\"$template\" " +
            "键码=$keyCodes " +
            "槽位=$slots " +
            "数值=$value " +
            "显示=\"$resolved\" " +
            "可用槽位=${cpsBySlot.keys}",
    )
}

private const val TAG = "CustomKey"

/** CPS 诊断日志的最小间隔 */
private const val CPS_LOG_INTERVAL_MS = 1_000L

/*
 * 这里原本还有一份私有 `lerpArgb`，而且**名字与 KeyGrid 里那个相同、语义却不同**
 * （那个返回 ARGB 整数，这个返回 Color）。两份分头维护的结果就是：
 * 自定义 Key 这边漏掉了描边与阴影的插值，同一个"颜色渐变"动画
 * 在两个样式下表现不一致。
 *
 * 现在统一用 [ColorLerp.kt] 里的 `lerpColor`（返回 Color）与
 * `lerpArgb`（返回 Int），按需要挑一个。
 */
/**
 * 自定义 Key 的真模糊半径倍数。
 *
 * 与按键样式的 `KeyGrid.REAL_BLUR_RATIO` 取同一个值 ——
 * 两个样式的"柔光"观感必须一致，否则同一个阴影大小在两个样式下
 * 一个糊一个锐，用户会以为是 bug。
 */
private const val CUSTOM_REAL_BLUR_RATIO = 0.5f
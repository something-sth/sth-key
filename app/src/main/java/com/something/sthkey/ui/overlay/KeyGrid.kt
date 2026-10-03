package com.something.sthkey.ui.overlay

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import com.something.sthkey.ui.component.bitmapSpecOf
import androidx.compose.runtime.Composable
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.something.sthkey.domain.config.AnimationMode
import kotlin.math.roundToInt
import com.something.sthkey.domain.config.KeyStrokesConfig
import com.something.sthkey.domain.config.ShadowMode
import com.something.sthkey.domain.style.KeyBox
import com.something.sthkey.domain.style.KeyLayout
import com.something.sthkey.domain.style.KeyStyleResolver
import com.something.sthkey.ui.component.composeFontFamily

/**
 * 按键网格（悬浮窗与配置预览共用的那一份渲染）。
 *
 * ============================================================
 * 坐标系
 * ============================================================
 * 键位坐标来自 [KeyLayout]，都在**基础坐标系**里（宽 300、键 80、间距 10…），
 * 单位是**物理像素**（与旧项目一致）。
 *
 * 这里只做一件事：把基础坐标乘以 [scale] 得到 Compose 需要的 dp。
 *
 * ============================================================
 * 动画
 * ============================================================
 * 三种模式（见 [AnimationMode]）：
 * - [AnimationMode.NONE]：直接切换颜色，不插值；
 * - [AnimationMode.FADE]：底色与文字颜色在时长内插值（旧项目的"渐变过渡"）；
 * - [AnimationMode.RIPPLE]：按下时从键帽中心扩散出一个实心圆，松开时收缩。
 *
 * 实现用 `animateFloatAsState` + 线性缓动，时长取配置里的
 * [KeyStrokesConfig.animationDurationSec]。线性是刻意的：
 * 按键反馈要"跟手"，缓动曲线会让人觉得迟滞。
 *
 * @param scale 基础像素 → dp 的换算倍率（含整体缩放）。
 * @param cpsBySlot 各位置的 CPS 数值（键为 LMB / RMB），用于 CPS 显示
 */
@Composable
fun KeyGrid(
    config: KeyStrokesConfig,
    pressedCodes: Set<Int>,
    scale: Float,
    modifier: Modifier = Modifier,
    cpsBySlot: Map<String, Int> = emptyMap(),
) {
    Box(
        modifier = modifier.size(
            (KeyLayout.BASE_WIDTH * scale).dp,
            // 高度按内容算，不能用固定值 —— 否则 Shift + CPS 行同时开启时底部会被裁
            (KeyLayout.baseHeight(config, cpsBySlot) * scale).dp,
        ),
    ) {
        KeyLayout.keys(config, cpsBySlot).forEach { box ->
            val pressed = box.codes.any { it in pressedCodes }
            OverlayKey(
                box = box,
                pressed = pressed,
                config = config,
                scale = scale,
            )
        }
    }
}

/**
 * 单个键帽。
 *
 * 颜色/圆角/描边全部走 [KeyStyleResolver] 与 [KeyLayout] 的常量，
 * 保证预览与悬浮窗完全一致（它们本来就是同一段代码）。
 */
@Composable
private fun OverlayKey(
    box: KeyBox,
    pressed: Boolean,
    config: KeyStrokesConfig,
    scale: Float,
) {
    val mode = config.animationMode

    /*
     * 进度：0 = 未按下，1 = 完全按下。
     *
     * 这里刻意**保留 State 对象本身**（而不是 `by` 解包成 Float）：
     * 扩散动画要在 `drawWithContent` 里读它，而绘制 lambda 只在
     * "读到的 State 变化"时才会重绘 —— 如果读的是解包后的普通 Float，
     * 动画就不会逐帧重绘（表现为没有动画）。
     */
    val progressState = animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = if (mode == AnimationMode.NONE) {
            tween(durationMillis = 0)
        } else {
            tween(
                durationMillis = (config.animationDurationSec * 1000f).toInt(),
                easing = LinearEasing,
            )
        },
        label = "key-progress",
    )
    val progress = progressState.value

    val widthDp = box.width * scale
    val heightDp = box.height * scale

    // 按下 / 未按下的**最终颜色**（ARGB 整数），后面所有合成都在它们之间做
    val upColorArgb = KeyStyleResolver.keyColor(config, pressed = false)
    val downColorArgb = KeyStyleResolver.keyColor(config, pressed = true)
    val upTextArgb = KeyStyleResolver.textColor(config, pressed = false)
    val downTextArgb = KeyStyleResolver.textColor(config, pressed = true)

    /*
     * 底色与"覆盖层"的处理，按模式分三类。
     *
     * 关键在于"谁负责表现按下"：
     *
     * 1. **无动画**：底色瞬间切换 —— 就是没有过渡；
     * 2. **颜色渐变**：底色按 progress 插值 —— 渐变本身就是它的过渡方式；
     * 3. **扩散收缩**：底色**始终是未按下色**，按下态作为一层的圆从中心覆盖上来。
     *    如果底色跟着按下的瞬时值一起切，圆和被覆盖的底色就是同一个颜色，
     *    扩散过程完全看不见（按下去像没动画）。
     *
     * ⚠️ 覆盖层必须用 [over] 手工合成，**不能**直接画一层半透明圆：
     *    底色 70% 与圆 70% 叠起来会合成约 91%，而纯按下态是 70% ——
     *    动画结束时反而比"无动画按下"更实、更灰。
     *    [over] 保证 (底色, 覆盖层) 的合成结果**恰好等于**纯按下态颜色。
     */
    val isRipple = mode == AnimationMode.RIPPLE

    val keyColor = when (mode) {
        AnimationMode.FADE -> blendColor(
            from = upColorArgb,
            to = downColorArgb,
            fraction = progress,
        )

        AnimationMode.RIPPLE -> Color(upColorArgb)

        AnimationMode.NONE -> Color(KeyStyleResolver.keyColor(config, pressed))
    }

    /*
     * 文字颜色：
     * - 渐变模式跟着插值；
     * - 其余模式（含扩散）直接切换 —— 文字要始终清晰，
     *   而且扩散模式下文字若也渐变，会和圆的扩散叠在一起显得糊。
     */
    val textColor = if (mode == AnimationMode.FADE) {
        blendColor(from = upTextArgb, to = downTextArgb, fraction = progress)
    } else {
        Color(KeyStyleResolver.textColor(config, pressed))
    }

    val outlineWidth = KeyStyleResolver.outlineWidth(config)
    /*
     * 描边颜色也分按下与未按下，因此与其它元素一样：
     * 渐变模式跟着插值，其余模式直接切换。
     */
    val outlineColor = if (mode == AnimationMode.FADE) {
        blendColor(
            from = KeyStyleResolver.outlineColor(config, pressed = false),
            to = KeyStyleResolver.outlineColor(config, pressed = true),
            fraction = progress,
        )
    } else {
        Color(KeyStyleResolver.outlineColor(config, pressed))
    }

    /*
     * 文字阴影。
     *
     * 颜色与色调一样跟着按下状态走；[shadowSize] 为 null 表示没启用，
     * 这时**不画第二层文字**（省一次绘制，键多时是实打实的开销）。
     */
    val shadowSize = KeyStyleResolver.shadowSize(config)
    val shadowArgb = if (mode == AnimationMode.FADE) {
        blendArgb(
            from = KeyStyleResolver.shadowColor(config, pressed = false),
            to = KeyStyleResolver.shadowColor(config, pressed = true),
            fraction = progress,
        )
    } else {
        KeyStyleResolver.shadowColor(config, pressed)
    }
    val shadowColor = Color(shadowArgb)

    val radiusDp = KeyStyleResolver.cornerRadius(config, minOf(widthDp, heightDp))
    val shape = RoundedCornerShape(radiusDp.dp)

    // 旧项目文字大小固定 35 × 文字缩放，与键帽宽高无关
    val fontSize = (KeyLayout.textSize(config) * scale).sp

    /*
     * 字体。
     *
     * 悬浮窗文字由 Compose 绘制，所以走 Compose 的字体体系：
     * 系统字体用内置通用族，其余用 `Font(File)`（见 composeFontFamily）。
     * 字体坏了会回落默认字体，不会让整个悬浮窗画不出来。
     */
    val fontFamily = composeFontFamily(config.fontId)

    /*
     * 扩散覆盖层的颜色。
     *
     * 直接用**不透明的按下态颜色**，不再按进度做 alpha 混合。
     *
     * 为什么：颜色混合 + 圆形裁剪会踩到抗锯齿的坑 ——
     * 裁剪路径边缘那一圈像素会被羽化成"覆盖层与底色的混合"。
     * 当圆半径刚好等于"最远角距离"时，圆弧只有极少的点真正贴到边界，
     * 绝大多数边缘都落在这条羽化带里，于是整圈偏淡、整体看着更灰。
     *
     * 现在"露出多少"完全由裁剪半径决定，颜色始终是纯按下态：
     * 羽化边混合的是"按下色 ↔ 按下色"，不存在偏色；
     * 进度 1 时也与"无动画按下"完全一致。
     */
    val rippleColor = if (isRipple) {
        Color(downColorArgb)
    } else {
        Color.Transparent
    }

    /*
     * 扩散圆的半径。
     *
     * 要盖满整个**圆角矩形**，半径必须达到最远角：
     *     hypot(w/2 - r, h/2 - r) + r      （r 为圆角半径）
     * 圆角为 0 时就是矩形对角线的一半。
     */
    val maxCornerRadius = minOf(widthDp, heightDp) / 2f
    val cornerPx = radiusDp.coerceIn(0f, maxCornerRadius)
    val coverRadiusDp = kotlin.math.hypot(
        widthDp / 2f - cornerPx,
        heightDp / 2f - cornerPx,
    ) + cornerPx

    Box(
        modifier = Modifier
            .offset(
                x = ((box.centerX - box.width / 2f) * scale).dp,
                y = (box.topY * scale).dp,
            )
            .size(widthDp.dp, heightDp.dp),
        contentAlignment = Alignment.Center,
    ) {
        /*
         * 键帽底层：底色 + 描边 + 扩散覆盖层。
         *
         * 单独包一层是为了**让扩散画在文字下面**：
         * `drawWithContent` 是在"该元素自身内容之后"绘制，
         * 如果把它挂在最外层，扩散会盖住文字。
         */
        Box(
            modifier = Modifier
                .size(widthDp.dp, heightDp.dp)
                .background(keyColor, shape)
                .then(
                    if (outlineWidth > 0f) {
                        Modifier.border(outlineWidth.dp, outlineColor, shape)
                    } else {
                        Modifier
                    },
                )
                /*
                 * 扩散收缩：在**绘制阶段**显式裁剪，不依赖 Modifier.clip 的形状语义。
                 *
                 * 之前用 `clip()` + 形状动画试了两版都出现"方向反了 / 盖不满"
                 * 这类无法从代码直接推出的现象（clip 的形状解析与布局尺寸耦合，
                 * 且百分比圆角会随尺寸缩放）。改成自己画就完全确定了：
                 *
                 * 1. 覆盖层是**整个键帽大小**（尺寸恒定，不参与动画）；
                 * 2. 用 clipPath 把它裁剪到"圆心在键帽中心、半径随进度增长"的圆内。
                 *
                 * 半径 0 时什么都画不出来，半径到最大时盖满整块 —— 方向不可能反。
                 * 绘制块里读的是 State 本身（progressState.value），
                 * 这样动画每帧都会触发重绘；读解包后的普通值不会重绘。
                 */
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
                // 覆盖层要按键帽形状裁掉，否则会溢出到键帽之外（例如圆角处）
                .clip(shape),
        )

        /*
         * 文字阴影。
         *
         * ============================================================
         * ⚠️ 为什么不是"画两层文字"（第一版就是那么写的，是错的）
         * ============================================================
         * 直觉做法是：先画一份带模糊/偏移的文字当阴影，再把正文盖上去。
         * 文字**完全不透明**时看不出问题，但**文字半透明时阴影会从字缝里
         * 透出来** —— 看起来像"阴影盖住了文字"。
         *
         * 根因是"画两层"根本表达不了需求：需求是
         * **文字区域内不渲染阴影，只在文字外渲染**。
         *
         * ============================================================
         * 正确做法：把文字从阴影层里**抠掉**
         * ============================================================
         * 1. 一整层只画阴影（模糊或偏移的副本）；
         * 2. 在这一层里再画一遍文字，但用 [BlendMode.DstOut] ——
         *    它的语义正是"把目的画面中与源重叠的部分擦掉"；
         * 3. 于是这一层只剩下**文字轮廓之外**的那圈阴影，
         *    再正常合成到键帽上，正文照旧画在最上面。
         *
         * ⚠️ `DstOut` 必须配合 [CompositingStrategy.Offscreen]：
         * 不隔离成独立图层的话，混合会作用到**已经画好的整块画面**上
         * （包括键帽底色），把键帽一起擦出个洞。官方文档明确写了
         * "使用 BlendMode 需要把 CompositingStrategy 设为 Offscreen"。
         *
         * ⚠️ 代价要说清楚：每个键多一张离屏纹理。所以**只在开启阴影时才走
         * 这条路**（关着的时候一个多余图层都没有）。键位少时无感，
         * 但这是个真的开销，键很多又想开阴影时值得知道。
         */
        val shadowSpan = if (shadowSize == null) 0f else shadowSize
        val contentPadding = (shadowSpan * 2f).coerceAtMost(minOf(widthDp, heightDp) / 4f)

        /*
         * ⚠️ 柔光半径的单位要换算：`TextStyle.Shadow.blurRadius` 吃的是 **dp**，
         * 而 `BitmapFontText` 的参数是**像素**（它自己不做 dp 换算）。
         *
         * 之前直接把 dp 值当像素传进去，于是柔光半径小了 2~3 倍 ——
         * 表现就是"硬阴影有效、柔光看起来没效果"。
         */
        val shadowSpanPx = with(LocalDensity.current) { shadowSpan.dp.toPx() }

        if (shadowSpan > 0f) {
            val isHard = config.shadow.mode == ShadowMode.HARD

            /*
             * 柔光：**优先用真模糊**（API 31+）。
             *
             * ============================================================
             * 为什么不能只靠 `BitmapFontText` 里的多层副本
             * ============================================================
             * 那一层画完 8 个偏移副本后，还要用 `DstOut` 把字形本身擦掉 ——
             * 剩下的只是"字形外面一圈薄晕"。而图片字体是**像素块**，
             * 块状字形外一圈薄晕看起来就是硬阴影。
             *
             * 改成在这一层上做真模糊之后：模糊先作用在"阴影副本"上，
             * 抠洞再擦掉中心，留下的才是真正的光晕。
             *
             * API 30 没有 `RenderEffect`，那时仍然走多层副本
             * （见 [realBlur] 与 `canUseRealBlur`）。
             */
            val useRealBlur = !isHard && canUseRealBlur && shadowSpanPx > 0f

            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(contentPadding.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        /*
                         * 隔离图层：见上面关于 DstOut 的说明。
                         * 不隔离会把键帽底色也擦掉。
                         */
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        /*
                         * 真模糊作用在**这一层**上（阴影副本 + 抠洞之后的整体）。
                         * 半径用 `shadowSpanPx / 2`：`RenderEffect` 的模糊半径
                         * 是"标准差量级"的，直接用 dp 值会糊过头，
                         * 而 TTF 那边 `TextStyle.Shadow.blurRadius` 的口径偏小。
                         */
                        .then(
                            if (useRealBlur) {
                                Modifier.realBlur((shadowSpan * REAL_BLUR_RATIO).dp)
                            } else {
                                Modifier
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    /* 第一遍：阴影本体 */
                    KeyLabel(
                        box = box,
                        config = config,
                        scale = scale,
                        textColor = shadowColor,
                        fontSize = fontSize,
                        fontFamily = fontFamily,
                        /*
                         * 矢量字体的柔光仍然挂 `TextStyle.Shadow` ——
                         * 那是它自己的真模糊，而且对 TTF 一直工作正常。
                         * 图片字体那条走上面这一层的 `realBlur`。
                         */
                        blurRadiusDp = if (isHard) 0f else shadowSpan,
                        /*
                         * ⚠️ 用了真模糊就**不能**再叠多层副本：
                         * 两套一起上会糊成一团，而且白白多画 8 遍。
                         */
                        shadowBlurPx = when {
                            isHard -> 0f
                            useRealBlur -> 0f
                            else -> shadowSpanPx
                        },
                        /*
                         * 只给**硬阴影自身**的位移。
                         * 文字偏移由 [KeyLabel] 内部统一叠加（全局 + 本槽位），
                         * 这里再传一次就会算两遍。
                         */
                        offsetXDp = if (isHard) (shadowSpan * 0.6f).dp else 0.dp,
                        offsetYDp = if (isHard) (shadowSpan * 0.6f).dp else 0.dp,
                    )

                    /* 第二遍：把文字轮廓从这一层里擦掉 */
                    KeyLabel(
                        box = box,
                        config = config,
                        scale = scale,
                        /*
                         * 颜色用不透明白 —— DstOut 只看源的不透明度，
                         * 用别的颜色反而会让人误以为它参与显示。
                         */
                        textColor = Color.White,
                        fontSize = fontSize,
                        fontFamily = fontFamily,
                        blurRadiusDp = 0f,
                        // 抠的位置必须与正文一致（不加偏移），否则会擦错地方
                        blendMode = BlendMode.DstOut,
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(contentPadding.dp),
            contentAlignment = Alignment.Center,
        ) {
            /*
             * 文字偏移走 `KeyLabel` 的 `offsetXDp/YDp` ——
             * 与自定义 Key 的 `Modifier.offset(x = (textOffsetX*scale).dp)` 一致，
             * 也与上面阴影那一遍用的是同一个偏移，两者不会错位。
             */
            KeyLabel(
                box = box,
                config = config,
                scale = scale,
                textColor = textColor,
                fontSize = fontSize,
                fontFamily = fontFamily,
                blurRadiusDp = 0f,
            )
        }
    }
}

/**
 * 键面上的一行或两行文字。
 *
 * ============================================================
 * 为什么抽成独立 composable
 * ============================================================
 * 阴影要画**三遍**文字（阴影本体、抠洞、正文），而抠洞那一遍必须与正文
 * 逐像素对齐。抽出来之后它们走的是同一段代码 ——
 * 否则一旦改了字号或行距，阴影与文字就会对不上
 * （这种错位很难一眼看出来，但很丑）。
 *
 * @param blurRadiusDp 大于 0 时给文字加模糊投影（柔光阴影用）
 * @param shadowBlurPx 同上，但单位是**像素** —— 图片字体那条路要这个
 *   （`TextStyle.Shadow` 吃 dp，而 `BitmapFontText` 自己不做 dp 换算）
 * @param offsetXDp / offsetYDp 相对自身的偏移（硬阴影与文字偏移用）
 * @param blendMode 绘制模式；抠洞时传 [BlendMode.DstOut]
 */
@Composable
private fun KeyLabel(
    box: KeyBox,
    config: KeyStrokesConfig,
    scale: Float,
    textColor: Color,
    fontSize: TextUnit,
    fontFamily: FontFamily?,
    blurRadiusDp: Float,
    shadowBlurPx: Float = 0f,
    offsetXDp: Dp = 0.dp,
    offsetYDp: Dp = 0.dp,
    blendMode: BlendMode = BlendMode.SrcOver,
) {
    /*
     * ============================================================
     * 文字偏移：**全局 + 本槽位**，在这里统一算出来
     * ============================================================
     * 为什么放在这一层、而不是各调用点各算一次：
     * 阴影要画三遍（阴影本体、抠洞、正文），三遍必须**逐像素对齐**。
     * 偏移若在不同地方各算，只要有一处漏了或算法不同，
     * 抠洞就会擦错位置 —— 表现是"文字边上缺一块"，很难查。
     *
     * ⚠️ 本槽位偏移的键是 [KeyBox.slotId]：CPS 模式 2 那两个额外显示位
     * （`CPS_L` / `CPS_R`）天然就是独立的槽位 id，不需要特判。
     */
    val slotOffset = config.slotTextOffsets[box.slotId]
    val offsetX = config.textOffsetX + (slotOffset?.x ?: 0f) + offsetXDp.value
    val offsetY = config.textOffsetY + (slotOffset?.y ?: 0f) + offsetYDp.value

    /*
     * 字间距 / 行间距同样"全局 + 本槽位"叠加。
     *
     * 与偏移放在**同一处**算：两者的层级语义必须一致，
     * 分两处写迟早会出现"偏移有本槽位、间距忘了加"这种不对称。
     */
    val slotSpacing = config.slotTextSpacings[box.slotId]
    val letterSpacing = config.textSpacing.letter + (slotSpacing?.letter ?: 0f)
    val lineSpacing = config.textSpacing.line + (slotSpacing?.line ?: 0f)

    /*
     * 柔光阴影：把模糊挂在 TextStyle 上，由字体渲染层负责。
     *
     * 偏移给 0 —— 柔光要的是"文字周围一圈光晕"，
     * 而不是"往右下挪一点的影子"（那是硬阴影要做的事）。
     * 光晕跟着文字走这件事由外层 [layerModifier] 的位移负责。
     */
    val shadowStyle = if (blurRadiusDp > 0f) {
        TextStyle(
            shadow = Shadow(
                color = textColor,
                offset = Offset.Zero,
                blurRadius = blurRadiusDp,
            ),
        )
    } else {
        TextStyle.Default
    }

    /*
     * 偏移与混合模式挂在这一层上。
     *
     * ⚠️ 参数只在**需要时**才设置：
     * - 普通绘制（正文、阴影本体）**不碰** `blendMode` 与 `compositingStrategy`，
     *   保持默认。阴影的模糊光晕需要溢出到字形之外，
     *   随便隔离成图层会把光晕裁在图层边界里；
     * - 抠洞那一遍（[BlendMode.DstOut]）则**两者都要**：
     *   DstOut 的语义是"擦掉目的画面中与源重叠的部分"，
     *   不隔离的话它擦的是**已经画好的整块画面**（包括键帽底色），
     *   会直接擦出一个洞。官方文档也明确要求
     *   "用 BlendMode 要把 CompositingStrategy 设为 Offscreen"。
     *
     * 这个差别从调用点看不出来，所以写在这里。
     */
    val layerModifier = Modifier.graphicsLayer {
        /*
         * `offsetX/offsetY` 是**基础坐标单位**（全局 + 本槽位 + 本次偏移），
         * 乘 `scale` 得到 dp，再乘 density 才是像素。
         *
         * ⚠️ 这一层是**唯一**做位移的地方：正文、阴影本体、抠洞三遍
         * 都挂在它上面，所以它们必然对齐（见上面 [offsetX] 的说明）。
         */
        translationX = (offsetX * scale).dp.toPx()
        translationY = (offsetY * scale).dp.toPx()
        if (blendMode != BlendMode.SrcOver) {
            this.blendMode = blendMode
            compositingStrategy = CompositingStrategy.Offscreen
        }
    }

    val subLabel = box.subLabel
    if (subLabel.isNullOrEmpty()) {
        /*
         * 支持**多行**：文字里的换行符会真的换行。
         *
         * 为什么值得支持：CPS 模式 3 原本是"键内两行"（主文字 + CPS），
         * 而自定义 Key 的组件只有一行文字 —— 转换过去时两行会被压成一行，
         * 挤在一个键里很难看。有了换行，两行就能原样保留。
         */
        KeyLabelText(
            text = box.label,
            color = textColor,
            fontSize = fontSize,
            fontFamily = fontFamily,
            style = shadowStyle,
            bitmapFontId = config.bitmapFontId.ifBlank { null },
            /*
             * 柔光半径要显式传给图片字体那条路：它读不到 `TextStyle.Shadow`。
             * 抠洞那一层（`DstOut`）不需要柔光，所以只在非 DstOut 时给。
             */
            shadowBlurPx = if (blendMode == BlendMode.DstOut) 0f else shadowBlurPx,
            /*
             * 抠洞那一层必须是**实心剪影**：图片字体默认按字形原色绘制，
             * 用它去 `DstOut` 会抠得不干净（渐变字形的浅色部分抠不掉）。
             */
            asSolidMask = blendMode == BlendMode.DstOut,
            letterSpacingPercent = letterSpacing,
            lineSpacingPercent = lineSpacing,
            modifier = layerModifier,
        )
        return
    }

    /*
     * CPS 模式 3：键内两行。
     *
     * 这里是"两行之间看起来多了一个换行"的根源，所以行高必须**自己定死**，
     * 不能让字体说了算：
     *
     * 旧项目是直接按**固定基线距离**画的 —— 主文字在 centerY - 4、
     * 副文字在 centerY + 24，两者恒差 28px，与字体无关。
     * 而 Compose 的 Text 默认按字体度量算行高，还会带上 Android 的
     * legacy font padding：换一个行高偏大的字体后，两个行盒各自高出一截，
     * 加起来就在两行之间凭空多出近一行的空白 ——
     * 于是 LMB/RMB 与 CPS 看起来"隔了两行"，和鼠标键脱节。
     *
     * 所以三件事一起做：
     * 1. `lineHeight` 显式等于字号 —— 行盒高度不再由字体决定；
     * 2. `LineHeightStyle(Trim.Both)` —— 去掉首行顶部与末行底部的多余留白
     *    （等价于关掉 font padding，且不依赖那套已废弃的平台开关）；
     * 3. 两行的间距只由中间那个 4px 决定。
     *
     * 结果：任何字体下两行间距都只跟字号有关，与旧项目对齐。
     */
    val primarySize = KeyLayout.CPS_PRIMARY_TEXT_SIZE * KeyLayout.textScale(config) * scale
    val secondarySize = KeyLayout.CPS_SECONDARY_TEXT_SIZE * KeyLayout.textScale(config) * scale

    fun lineStyle(size: Float) = TextStyle(
        fontSize = size.sp,
        lineHeight = size.sp,
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.Both,
        ),
        // 柔光阴影也要跟着行样式走，否则两行里只有一行带阴影
        shadow = shadowStyle.shadow,
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        // 偏移与混合模式要作用在**整块两行文字**上，不能只挂到某一行
        modifier = layerModifier,
    ) {
        /*
         * ============================================================
         * 两行合成**一次**调用
         * ============================================================
         * 两行字号不同（主 28、副 22），所以要把"副行相对主行的比例"
         * 作为**逐行字号**传下去，而不是各画一次。
         *
         * ⚠️ 分开画的两个后果（都实测过）：
         * 1. 图片字体逐行画会让**两行重叠**（每个 `BitmapFontText` 都是
         *    独立 Canvas、各自按自身高度居中）；
         * 2. 副文字走的是矢量路径，**图片字体在它上面不生效** ——
         *    用户实测的"开了 CPS 模式 3，下面那行没用图片字体"。
         *
         * 矢量路径仍然只认一个字号，所以 [KeyLabelText] 内部会按
         * "主行同号、其余行按比例"处理（见它的实现）。
         */
        val secondaryPercent = if (primarySize > 0f) {
            (secondarySize / primarySize * 100f).roundToInt().coerceIn(1, 1000)
        } else {
            100
        }

        KeyLabelText(
            /*
             * ⚠️ 用**真的换行符** `"\n"`，不是 `LINE_BREAK`（`"\\n"`）。
             *
             * `"\\n"` 是"反斜杠 + n"两个**字符** —— 那是自定义 Key 那边
             * 为了"文字输入框是单行的、真换行会看不见"而定下的约定
             * （见 `KeyToCustomConverter.LINE_BREAK`）。
             *
             * 而这里是**渲染路径**，没有任何理由走那层转义：
             * 传 `"\\n"` 会被当成普通文字原样画出来 ——
             * 用户实测看到的就是 `LMB\nCPS 0` 这种字面量。
             *
             * `KeyLabelText` 同时接受真换行与转义写法（老配置里可能存着
             * 转义的），所以这里给真的即可，两条路径都不受影响。
             */
            text = box.label + "\n" + subLabel,
            color = textColor,
            // 旧项目主文字 28 × 文字缩放，比普通键小一点，给副文字腾位置
            fontSize = primarySize.sp,
            fontFamily = fontFamily,
            style = lineStyle(primarySize),
            bitmapFontId = config.bitmapFontId.ifBlank { null },
            /*
             * 主行 100%、副行按比例（22/28 ≈ 79%）。
             */
            lineScalePercents = listOf(100, secondaryPercent),
            /*
             * ⚠️ 间距**必须在这里也传一次**。
             *
             * `KeyLabelText` 的这两个参数默认是 0，漏传不会报错、也不会有
             * 任何提示 —— 只是"调了没反应"。用户实测的"CPS 模式 3 的
             * LMB/RMB 调不动字间距行间距，转成自定义 Key 才生效"就是这个：
             * 单行那条分支（第 627 行）传了，这一条漏了。
             *
             * 同一个参数在同一个函数里有**两条调用路径**时，
             * 加参数一定要两条都加 —— 编译期看不出来。
             */
            letterSpacingPercent = letterSpacing,
            lineSpacingPercent = lineSpacing,
        )
    }
}



/**
 * 一段键面文字，**支持换行**。
 *
 * ============================================================
 * 为什么不用一个 Text 加 maxLines
 * ============================================================
 * `Text("A\nB")` 自己也会换行，但它的行高由**字体度量**决定，
 * 与 CPS 模式 3 那条路径（自己定死行高）就不一致了 ——
 * 同一个键面的两行间距会随"是不是 CPS 模式 3"而变，看起来像 bug。
 *
 * 所以这里手动按换行符拆开、逐行画，行高统一由字号决定。
 * 单行时就是普通的一个 Text，没有额外层级。
 */
@Composable
private fun KeyLabelText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    fontFamily: FontFamily?,
    style: TextStyle,
    /** 图片字体的 id；`null` 表示用矢量字体 */
    bitmapFontId: String? = null,
    /**
     * 行与行之间的额外间距（dp）。
     *
     * CPS 模式 3 的两行之间有 4 像素间距，而普通的 `\n` 换行不需要。
     * 合并成一个组件之后用这个参数区分，不必再维护两套绘制代码。
     */
    lineSpacingDp: Float = 0f,
    /**
     * 柔光半径（像素）；0 表示硬阴影。
     *
     * ⚠️ 这个参数是**专门给图片字体**的。
     *
     * 矢量字体的柔光走 `TextStyle.Shadow`（`baseStyle` 里的 `blurRadius`），
     * 而图片字体是自己贴图的，**读不到 `TextStyle`** —— 于是柔光看起来
     * "对图片字体无效"，只有硬阴影起作用。
     *
     * 调用方在画阴影那一层时把 `shadowSpan` 传进来即可，两条路径的观感一致。
     */
    shadowBlurPx: Float = 0f,
    /**
     * 是否把字形画成**实心剪影**（只取覆盖率、颜色统一）。
     *
     * ⚠️ 抠洞层（`DstOut`）必须开：`DstOut` 按源的不透明度擦除，
     * 而图片字体默认是"按字形原色绘制" —— 渐变色形的浅色部分
     * 不透明度低，抠出来就是一圈没擦干净的毛边。
     */
    asSolidMask: Boolean = false,
    /**
     * 逐行的字号百分比（100 = 与 [fontSize] 同号）；空 = 所有行同号。
     *
     * ⚠️ 只对**图片字体**那条路生效。
     *
     * CPS 模式 3 的两行字号不同（主 28、副 22），而矢量路径只接受一个
     * 字号 —— 所以那边仍然由调用方分开画两次。图片路径能逐行给字号，
     * 是因为它整段一次画完、每行单独算缩放（见 `BitmapFontText`）。
     */
    lineScalePercents: List<Int> = emptyList(),
    /** 行距系数：1 = 行高即行距，< 1 收紧，> 1 拉开（只对图片字体生效） */
    lineSpacingScale: Float = 1f,
    /*
     * 字间距 / 行间距（相对字号的百分比，0 = 不动）。
     *
     * ⚠️ **刻意不给默认值**。
     *
     * 给了 `= 0f` 的话，"漏传"就会静默变成"这个值永远是 0" ——
     * 编译通过、没有警告、只是"调了没反应"。用户实测的
     * "CPS 模式 3 的 LMB/RMB 调不动间距、转成自定义 Key 才生效"
     * 就是这么来的：这个函数有**两条调用路径**，
     * 加参数时只加了一条。
     *
     * 去掉默认值之后，漏传直接编译失败。
     */
    letterSpacingPercent: Float,
    lineSpacingPercent: Float,
    modifier: Modifier = Modifier,
) {
    val lines = text.split('\n')

    /*
     * 图片字体：整段都是 ASCII 时走位图渲染。
     *
     * ⚠️ 与矢量路径**不共用代码**（一个贴图、一个走 Text），
     * 所以放在最前面直接返回，避免两条路径的判断纠缠。
     *
     * 判断是"**整段**都能用"才走位图：键面文字很短（`LMB`、`W`），
     * 逐行判断的收益不值得多一层分支。以后做中英混排时再改成逐行。
     */
    if (bitmapFontId != null) {
        val spec = remember(bitmapFontId) { bitmapSpecOf(bitmapFontId) }
        if (spec != null && lines.all { canRenderAsBitmap(it, spec) }) {
            /*
             * ⚠️ 整段**一次画完**，不能逐行各画一次。
             *
             * 每个 `BitmapFontText` 都是一个独立 Canvas、各自按自身内容高度
             * 居中 —— 逐行画会让**多行全部重叠在同一个位置**。
             *
             * 行间距由 [lineSpacingDp] 折成"行距系数"传进去，
             * 而不是在外面插 `Spacer`：插 Spacer 的话每行仍是独立 Canvas，
             * 重叠问题照旧。
             */
            val lineHeightDp = with(LocalDensity.current) { fontSize.toDp().value }
            val spacingScale = if (lineSpacingScale != 1f) {
                lineSpacingScale
            } else if (lineHeightDp > 0f) {
                (lineHeightDp + lineSpacingDp) / lineHeightDp
            } else {
                1f
            }

            BitmapFontText(
                fontId = bitmapFontId,
                text = text,
                fontSize = fontSize,
                /*
                 * 颜色照传：图片字体的染色语义是"字形 RGB × 文字颜色"，
                 * 白色字形就是整块染成这个颜色。
                 */
                color = color,
                /*
                 * 阴影**不在这里画**：调用方已经把阴影那一层用同一个
                 * [KeyLabelText] 画过了（只是颜色不同），
                 * 这里再画会叠两层，边缘变实。
                 *
                 * 但**柔光半径要传**：矢量字体能从 `style` 里读到
                 * `blurRadius`，图片字体读不到 —— 不传的话柔光就
                 * 只有硬阴影那一档，看起来像"柔光对图片字体无效"。
                 */
                shadowBlurPx = shadowBlurPx,
                asSolidMask = asSolidMask,
                lineScalePercents = lineScalePercents,
                lineSpacingScale = spacingScale,
                letterSpacingPercent = letterSpacingPercent,
                lineSpacingPercent = lineSpacingPercent,
                modifier = modifier,
            )
            return
        }
    }

    /*
     * 字间距 / 行间距同样要作用到单行（行间距对单行无意义，
     * 但字间距一定要 —— 否则"只有一个字的键"调不动，
     * 而那种键恰恰是最需要微调字距的）。
     */
    val letterSpacingEm = (letterSpacingPercent / 100f).takeIf { it.isFinite() } ?: 0f
    val lineSpacingFactor = if (lineSpacingPercent.isFinite()) {
        (1f + lineSpacingPercent / 100f).coerceAtLeast(MIN_LINE_SPACING_FACTOR)
    } else {
        1f
    }

    // 单行：不必包 Column，少一层布局
    if (lines.size <= 1) {
        Text(
            text = text,
            color = color,
            textAlign = TextAlign.Center,
            fontSize = fontSize,
            fontFamily = fontFamily,
            style = style.copy(letterSpacing = letterSpacingEm.em),
            modifier = modifier,
        )
        return
    }

    /*
     * ============================================================
     * 矢量路径也要吃字间距 / 行间距（CPS 模式 3 就在这里）
     * ============================================================
     * ⚠️ 早先这两个值**只有图片字体那条路**吃，于是矢量字体调了完全没反应 ——
     * 而 CPS 模式 3 那两行常常是矢量的（副行含非 ASCII，或用户根本没选
     * 图片字体），表现就是"怎么调都没变化"。
     *
     * 字间距：`TextStyle.letterSpacing` 的单位是 **em 倍数**，
     * 而滑块是"相对字号的百分比" → 除以 100 即可对上。
     *
     * 行间距：乘进**行高**。行高本身已显式等于字号（见下），
     * 所以"行距 = 行高 × (1 + 行间距)"就是把行与行的基准距离拉开，
     * 且**首行之前不会多出空白**。
     */
    /*
     * 行高显式等于字号 × 行距系数，并去掉上下多余留白 ——
     * 与 CPS 模式 3 那条路径用同一套做法，两处的行距才一致。
     */
    val lineStyle = style.copy(
        lineHeight = fontSize * lineSpacingFactor,
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.Both,
        ),
        letterSpacing = letterSpacingEm.em,
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        lines.forEachIndexed { index, line ->
            /*
             * 逐行字号：`lineScalePercents` 给了就按它缩放（CPS 模式 3 的
             * 副文字更小），没给就所有行同号。
             *
             * ⚠️ 行高也要跟着这一行自己的字号走：只改 `fontSize` 而沿用
             * 按主字号算出来的 `lineHeight`，两行之间会凭空多出一截空白 ——
             * 看起来像"副文字被推远了"。
             */
            val lineSize = fontSize * (lineScalePercents.getOrNull(index)?.let { it / 100f } ?: 1f)
            Text(
                text = line,
                color = color,
                textAlign = TextAlign.Center,
                fontFamily = fontFamily,
                maxLines = 1,
                style = lineStyle.copy(
                    fontSize = lineSize,
                    lineHeight = lineSize * lineSpacingFactor,
                ),
            )
        }
    }
}

/**
 * 两个 ARGB 颜色按比例插值。
 *
 * ⚠️ 实现已经搬到 [ColorLerp.kt]（`lerpColor` / `lerpArgb`），两种样式共用。
 * 这里不再各留一份 —— 早先两份分头维护时，
 * 自定义 Key 那边就漏掉了描边与阴影的插值，表现与按键样式不一致。
 */

/**
 * 真模糊的半径相对 `shadowSpan` 的倍数。
 *
 * `RenderEffect` 的模糊半径口径比 `TextStyle.Shadow.blurRadius` 大 ——
 * 直接用 dp 值会糊过头（字都看不出形状）。`0.5` 是让图片字体的柔光
 * 与矢量字体的柔光**观感接近**的取值。
 *
 * 调柔光观感就改这一个数。
 */
private const val REAL_BLUR_RATIO = 0.5f

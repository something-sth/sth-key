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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.something.sthkey.domain.config.AnimationMode
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

        if (shadowSpan > 0f) {
            val isHard = config.shadow.mode == ShadowMode.HARD

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
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
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
                        // 柔光靠字体自带的模糊；硬阴影是实心副本，不加模糊
                        blurRadiusDp = if (isHard) 0f else shadowSpan,
                        offsetXDp = if (isHard) shadowSpan * 0.6f else 0f,
                        offsetYDp = if (isHard) shadowSpan * 0.6f else 0f,
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
 * @param offsetXDp / offsetYDp 相对自身的偏移（硬阴影用）
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
    offsetXDp: Float = 0f,
    offsetYDp: Float = 0f,
    blendMode: BlendMode = BlendMode.SrcOver,
) {
    /*
     * 柔光阴影：把模糊挂在 TextStyle 上，由字体渲染层负责。
     *
     * 偏移给 0 —— 柔光要的是"文字周围一圈光晕"，
     * 而不是"往右下挪一点的影子"（那是硬阴影要做的事）。
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
        translationX = offsetXDp * density
        translationY = offsetYDp * density
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
        Text(
            text = box.label,
            color = textColor,
            textAlign = TextAlign.Center,
            // 旧项目主文字 28 × 文字缩放，比普通键小一点，给副文字腾位置
            style = lineStyle(primarySize),
            fontFamily = fontFamily,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.height((4f * scale).dp))
        Text(
            text = subLabel,
            color = textColor,
            textAlign = TextAlign.Center,
            style = lineStyle(secondarySize),
            fontFamily = fontFamily,
            maxLines = 1,
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
    modifier: Modifier = Modifier,
) {
    val lines = text.split('\n')

    // 单行：不必包 Column，少一层布局
    if (lines.size <= 1) {
        Text(
            text = text,
            color = color,
            textAlign = TextAlign.Center,
            fontSize = fontSize,
            fontFamily = fontFamily,
            style = style,
            modifier = modifier,
        )
        return
    }

    /*
     * 行高显式等于字号，并去掉上下多余留白 ——
     * 与 CPS 模式 3 那条路径用同一套做法，两处的行距才一致。
     */
    val lineStyle = style.copy(
        lineHeight = fontSize,
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.Both,
        ),
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        lines.forEach { line ->
            Text(
                text = line,
                color = color,
                textAlign = TextAlign.Center,
                fontFamily = fontFamily,
                maxLines = 1,
                style = lineStyle,
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

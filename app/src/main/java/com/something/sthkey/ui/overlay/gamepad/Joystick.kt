package com.something.sthkey.ui.overlay.gamepad

import android.view.Choreographer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.something.sthkey.core.log.AppLog
import com.something.sthkey.domain.config.JoystickStyle
import com.something.sthkey.domain.style.GamepadLayout
import com.something.sthkey.domain.style.KeyBox
import com.something.sthkey.domain.style.StickGeometry
import kotlin.math.abs
import kotlinx.coroutines.awaitCancellation

/**
 * 一个摇杆：方框底盘 + 内圆轨道 + 跟着输入移动的摇杆帽。
 *
 * ============================================================
 * ⚠️ 外观全部来自 [JoystickStyle]，不在这里写死
 * ============================================================
 * 颜色 / 圆角 / 透明度 / 描边 / 摇杆帽 / 平滑都是配置项 ——
 * 见 `JoystickStyle` 的说明。
 *
 * ============================================================
 * ⚠️ 两种显示方式，**必须完全隔离**
 * ============================================================
 * | 方式 | 画什么 |
 * |---|---|
 * | **精准** | 纯计算值，零延迟，**不碰任何 State** |
 * | **平滑** | `Choreographer` 驱动的弹簧值 |
 *
 * 隔离的理由见下面各段注释 —— 简短说:精准曾经被平滑的实现错误**连带弄死过**。
 *
 * @param x 归一化后的摇杆 X（`-1..1`，右为正；**已含**死区/灵敏度变换）
 * @param y 归一化后的摇杆 Y（`-1..1`，**下为正**，屏幕符号）
 * @param sizeDp 摇杆边长（**基础单位**，与 `KeyLayout` 同一套）
 * @param scale 基础单位 → dp 的换算系数
 * @param pxPerBase 基础单位 → 屏幕像素 的倍数（= `scale × 密度`）
 * @param travelRadius 摇杆帽中心能走的最大距离（基础单位）
 * @param thumbSize 摇杆帽直径（基础单位）
 * @param style 外观与平滑设置
 */
@Composable
fun Joystick(
    x: Float,
    y: Float,
    sizeDp: Float,
    scale: Float,
    pxPerBase: Float,
    travelRadius: Float,
    thumbSize: Float,
    style: JoystickStyle,
    modifier: Modifier = Modifier,
) {
    val maxOffset = travelRadius

    /*
     * ============================================================
     * 目标偏移（基础像素）—— **纯计算，零 State**
     * ============================================================
     * ⚠️ 这一行是**精准模式唯一的数据来源**。它必须是纯计算:
     *
     * | 写法 | 结果 |
     * |---|---|
     * | 纯计算（本写法） | 精准模式**必定可用** |
     * | 包一层 `remember { mutableStateOf(...) }` + 帧循环 | **精准也一起死** —— 值卡在 State 里进不了绘制 |
     *
     * 用户实测的日志就是证据:
     * ```
     * 输入=(0.85, -0.32) ｜ 目标=(32.3, -12.1) ｜ 画=(0.0, 0.0)
     * ```
     * 输入与目标都在变，而"画"恒为 0。
     *
     * ⚠️ **不要把这一行改成 State。** 精准模式的价值就是"我要看真实数据"。
     */
    val (targetX, targetY) = StickGeometry.thumbOffset(x, y, maxOffset)

    /**
     * 平滑时间（毫秒）。`0` = 精准模式。
     *
     * ⚠️ 它只做**上限夹取**；真正的平滑快慢由弹簧参数决定
     */
    val smoothingMs = style.smoothingMs.coerceIn(0f, SMOOTHING_MAX_MS)

    /*
     * ============================================================
     * 平滑:用 **Android 原生 `Choreographer`** 驱动弹簧
     * ============================================================
     * ============================================================
     * ⚠️ 为什么不用 `withFrameNanos`
     * ============================================================
     *
     * 三方架构对比（这是"抄了还碰壁"的根本原因）:
     *
     * | | 悬浮窗是什么 | 动画靠什么驱动 |
     * |---|---|---|
     * | `FrameLayout` + WebView | `postOnAnimation(frameRunnable)` → **原生 Choreographer** |
     * ✅ **`Choreographer` 在悬浮窗里有先例**，
     * 所以走这条最稳。
     *
     * ============================================================
     * ⚠️⚠️ 另一个必须同时修的错:闭包冻结
     * ============================================================
     * `LaunchedEffect(smoothingMs)` 的 body **只在 key 变化时创建一次**。
     * 而 `targetX` / `targetY` 是**普通 `val`** —— 一旦闭包建好，
     * 它们就被**冻结在那一刻的值**。循环拿一个不变的目标做弹簧，
     * 收敛后自然**再也不动**（用户看到的"画一直没变"）。
     *
     * 修法用 Compose 官方的 [rememberUpdatedState]:它的存在意义**就是**
     * "让长生命周期 effect 读到最新值、而不重启 effect"。
     *
     * ============================================================
     * 弹簧参数
     * ============================================================
     * ```java
     * float dt = Math.min(0.022f, Math.max(0.001f, (now - lastFrameMs) / 1000f));
     * float ax = 120f * (targetX - shownX) - 22f * velocityX;
     * velocityX += ax * dt;
     * shownX += velocityX * dt;
     * ```
     *
     * ⚠️ 刚度/阻尼的**比值**决定有没有过冲:阻尼小会来回晃，
     * 阻尼大又变回生硬。`dt` 夹到 `0.001..0.022` 也是照抄 ——
     * 掉帧时 `dt` 会突然变大，不夹住弹簧会一步冲过头。
     *
     * ============================================================
     * ⚠️ 精准模式**完全不参与**这里
     * ============================================================
     * 绘制层在 `smoothingMs <= 0` 时读 `targetX` / `targetY`（纯计算值），
     * **一个 State 都不碰**。所以下面这段即使写错，精准也不受影响 ——
     * 这是刻意的隔离，不要再把两者合并。
     */
    var smoothX by remember { mutableStateOf(0f) }
    var smoothY by remember { mutableStateOf(0f) }
    var velocityX by remember { mutableStateOf(0f) }
    var velocityY by remember { mutableStateOf(0f) }

    /*
     * ⚠️ **必须**用它读目标与平滑时间:普通 `val` 会被长生命周期 effect
     * 的闭包冻结（见上面"闭包冻结"那段）。
     */
    val latestTarget by rememberUpdatedState(targetX to targetY)
    val latestSmoothingMs by rememberUpdatedState(smoothingMs)

    LaunchedEffect(Unit) {
        /*
         * ⚠️⚠️ key 是 `Unit` —— **循环绝不因配置变化而重启**。
         *
         * 之前 key 写的是 `smoothingMs`，于是拖一下滑块就:
         *
         * 1. 取消这个 effect → 建一个新的；
         * 2. 新的 `seeded = false` → 下一帧**直接把 smoothX/Y 对准目标**；
         * 3. 表现是"拖滑块时摇杆瞬间跟手" —— 平滑像是被关掉了。
         *
         * 平滑时间改成每帧从 [latestSmoothingMs] 读，改它只影响
         * 之后的弹簧刚度，不会重置状态。
         */
        val choreographer = Choreographer.getInstance()
        var last = 0L
        var seeded = false

        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val dt = if (last == 0L) {
                    0f
                } else {
                    (((frameTimeNanos - last) / 1_000_000f) / 1000f).coerceIn(0.001f, 0.022f)
                }
                last = frameTimeNanos

                /* ⚠️ 每帧读**最新**目标（不是创建时冻结的那个） */
                val (tx, ty) = latestTarget

                /*
                 * ============================================================
                 * 平滑时间必须**真的参与运算**
                 * ============================================================
                 * 原因就是上一版只把 `smoothingMs` 当 `LaunchedEffect` 的 key，
                 * **滑块的数值从来没进过公式**。
                 *
                 * 现在按"时间常数"缩放:刚度与阻尼**一起**乘 `k`，
                 * 于是:
                 *
                 * | 效果 | 说明 |
                 * |---|---|
                 * | 快慢随滑块变 | `k` 越小 → 弹簧越软 → 追得越慢 |
                 * | **过冲特性不变** | 两者比值固定，所以不会有"某个值开始晃" |
                 *
                 * ⚠️ 阻尼按 `k` 缩放（而不是 `√k`）意味着这不是严格的
                 * 二阶系统时间缩放，但**观感单调**才是这里要的:
                 * 滑块往右一定更柔，不会出现"中间某段反而更硬"。
                 */
                val ms = latestSmoothingMs
                val k = (AXON_SPRING_REFERENCE_MS / ms.coerceAtLeast(1f))
                    .coerceIn(0.15f, 6f)
                val stiffness = AXON_SPRING_STIFFNESS * k
                val damping = AXON_SPRING_DAMPING * k

                if (!seeded) {
                    /*
                     * 首帧:对准目标。
                     *
                     * ⚠️ 速度也一起初始化 —— 否则从 (0,0) 起步时
                     * 弹簧会先朝旧目标冲一下再折回来（"甩一下"）。
                     * 初速度取"当前位置到目标的差 / 一个帧间隔"。
                     */
                    val dx = tx - smoothX
                    val dy = ty - smoothY
                    smoothX = tx
                    smoothY = ty
                    velocityX = if (dt > 0f) dx / dt else 0f
                    velocityY = if (dt > 0f) dy / dt else 0f
                    seeded = true
                } else if (dt > 0f) {
                    /* 弹簧积分（120 / 22，按平滑时间缩放） */
                    velocityX += (stiffness * (tx - smoothX) - damping * velocityX) * dt
                    velocityY += (stiffness * (ty - smoothY) - damping * velocityY) * dt
                    smoothX += velocityX * dt
                    smoothY += velocityY * dt
                }

                /*
                 * ============================================================
                 * ⚠️⚠️ 硬限幅 —— 让"数值发散"在结构上不可能
                 * ============================================================
                 * 用户报过"摇杆帽慢慢变透明直到消失、无法恢复"。
                 *
                 * 数值积分（半隐式欧拉）在 `dt` 抖动时**可能**发散:
                 * `smoothX` 越滚越大 → 帽被画到画布外面 → 看起来就是"消失了"。
                 * 而且它**不会自己回来** —— 与用户描述的"无法恢复"一致。
                 *
                 * ⚠️ 这里不去争论它到底会不会发散，而是**不管发不发散都安全**:
                 *
                 * | 限制 | 作用 |
                 * |---|---|
                 * | `smooth` 夹到 `±maxOffset` | 帽永远在底盘内，画得出来 |
                 * | `velocity` 夹到 `±20 × maxOffset / s` | 不会越滚越快 |
                 *
                 * ⚠️ 夹的是**显示值**，不是目标值 —— 精准模式读的是
                 * `targetX/targetY`，不受这里影响。
                 */
                val limit = maxOffset.coerceAtLeast(1e-3f)
                val velocityLimit = limit * MAX_SPRING_VELOCITY_PER_SECOND
                smoothX = smoothX.coerceIn(-limit, limit)
                smoothY = smoothY.coerceIn(-limit, limit)
                velocityX = velocityX.coerceIn(-velocityLimit, velocityLimit)
                velocityY = velocityY.coerceIn(-velocityLimit, velocityLimit)

                /*
                 * **自我续期**，直到组件离开组合。
                 * 那在**自定义 View** 里成立（静止时不画省电）。
                 * 我们这里必须**一直跑** —— 因为 `smoothX` 是 Compose State，
                 * 停掉就再也不会有下一帧来读新目标了。
                 *
                 * 代价是每帧一次极轻量的浮点运算（两个弹簧），可以忽略。
                 */
                choreographer.postFrameCallback(this)
            }
        }

        choreographer.postFrameCallback(callback)
        try {
            /* 挂住，直到这个 effect 离开组合 */
            awaitCancellation()
        } finally {
            choreographer.removeFrameCallback(callback)
        }
    }

    /*
     * ⚠️ 诊断:摇杆"不动"时必须能一眼看出断在哪一环。
     *
     * | 现象 | 断在哪 |
     * |---|---|
     * | 这条日志**一直不出现** | 输入根本没变化 → 采集层 / 通道 |
     * | `输入` 在变而 `目标` 恒为 `0.0` | `thumbOffset` 或死区/灵敏度把它吃掉了 |
     * | 精准模式下屏幕不动而 `目标` 在变 | 绘制层（本文件）的问题 |
     */
    LaunchedEffect(targetX, targetY) {
        if (abs(x) > 0.1f || abs(y) > 0.1f) {
            AppLog.d(
                "Joystick",
                "输入=(${"%.2f".format(x)}, ${"%.2f".format(y)}) ｜ " +
                    "目标=(${"%.1f".format(targetX)}, ${"%.1f".format(targetY)}) ｜ " +
                    "平滑画=(${"%.1f".format(smoothX)}, ${"%.1f".format(smoothY)}) ｜ " +
                    "上限=$maxOffset ｜ 平滑=${smoothingMs}ms",
            )
        }
    }

    Box(
        modifier = modifier.size((sizeDp * scale).dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            /*
             * ============================================================
             * ⚠️ 单位：画布里的一切都乘 `pxPerBase`
             * ============================================================
             * 三个坐标空间必须显式换算，**不能靠 `graphicsLayer` 缩放** ——
             * 试过那个做法，结果是"拇指的圆跑到方框外面"：
             * 缩放后的画布尺寸与绘制坐标不在同一个空间里，
             * 而 `graphicsLayer` 只缩放已经画好的内容，
             * 尺寸计算（`size.minDimension`）读到的仍是**缩放前**的值，
             * 于是"按画布尺寸推出来的半径"和"按基础像素画的偏移"对不上。
             *
             * 现在每一个尺寸都显式乘一次 `pxPerBase`，看得见、算得清:
             *
             * ```
             * 像素 = 基础像素 × pxPerBase
             * pxPerBase = 密度 × scale
             * ```
             *
             * ⚠️ 这里曾经写成 `1 / scale`，于是绘制尺寸变成
             * `基础 × 密度 × scale²` —— 随用户的缩放百分比**反向**变化
             * （缩放调小、圆反而变大，还会跑出底盘）。
             * 完整推导见 [baseToPixelFactor] 的注释。
             */

            /** 画布边长换算回**基础像素**，后面所有尺寸都从它推 */
            val sideBase = size.minDimension / pxPerBase

            /* ① 方框底盘 */
            val corner = sideBase * style.cornerRatio * pxPerBase
            val cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner)
            drawRoundRect(
                color = argbWithOpacity(style.color, style.opacity),
                topLeft = Offset.Zero,
                size = size,
                cornerRadius = cornerRadius,
            )

            /*
             * ①b **底盘描边**（方框的四条边）。
             *
             * ⚠️ 与下面那个"内圆"是**两回事** —— 用户明确说过
             * "'摇杆'栏需要加一下描边，你忘记加了"。我第一版只加了内圆，
             * 把底盘本身的轮廓线整个漏了（而按键样式里也有描边，摇杆不该少）。
             *
             * ⚠️ 线宽为 0 或透明度为 0 时**不画** —— 那是"隐藏描边"的做法。
             * 不判断的话 `Stroke(width = 0f)` 在部分设备上会画成一像素细线。
             */
            val strokeWidth = sideBase * style.strokeWidthRatio * pxPerBase
            if (strokeWidth > 0f && style.strokeOpacity > 0f) {
                drawRoundRect(
                    color = argbWithOpacity(style.strokeColor, style.strokeOpacity),
                    topLeft = Offset.Zero,
                    size = size,
                    cornerRadius = cornerRadius,
                    style = Stroke(width = strokeWidth),
                )
            }

            /*
             * ② 底盘上的圆 —— 拇指的活动轨道（用户管它叫"内接圆边"）。
             *
             * ⚠️ 它必须把"拇指能到的最远处"包住:
             * `半径 = travelRadius + thumbSize / 2`。
             *
             * ⚠️ 线宽为 0 时**不画** —— 那是"隐藏这个圆"的做法
             * （用户要的"控制这个圆边显示/隐藏"）。
             * 不去判断的话，`Stroke(width = 0f)` 在某些设备上会画成
             * 一像素的细线，看起来像"没关干净"。
             */
            val ringWidth = sideBase * style.ringWidthRatio * pxPerBase
            if (ringWidth > 0f && style.ringOpacity > 0f) {
                val baseRadius = (maxOffset + thumbSize / 2f) * pxPerBase
                drawCircle(
                    color = argbWithOpacity(style.ringColor, style.ringOpacity),
                    radius = baseRadius,
                    center = Offset(size.width / 2f, size.height / 2f),
                    style = Stroke(width = ringWidth),
                )
            }

            /*
             * ③ 摇杆帽（拇指那个圆）。
             *
             * ⚠️ 半径与偏移**必须成对算**:
             * `maxOffset` 是"帽中心能走多远"，它的取值前提是
             * `帽半径 <= 半边长 - maxOffset`（见 `GamepadLayout` 的验算）。
             * 这里把帽半径乘上 `knobScale` 之后，那个前提就**可能不成立** ——
             * 所以 `maxOffset` 也要按同样的比例缩，
             * 否则用户把"摇杆帽缩放"调大之后，帽会**跑出底盘**。
             */
            val knobScale = style.knobScale.coerceIn(0.2f, 2f)
            val knobDiameter = thumbSize * knobScale

            /*
             * 帽的可用活动半径 = 边长一半 - 帽半径。
             *
             * ⚠️ 用"边长一半"而不是 `maxOffset + 帽半径`:
             * 后者在 `knobScale > 1` 时会超出边界。
             */
            val safeTravel = (sideBase / 2f - knobDiameter / 2f).coerceAtLeast(0f)
            val travel = minOf(maxOffset * knobScale, safeTravel)
            val knobRadius = knobDiameter / 2f * pxPerBase

            /*
             * ============================================================
             * ⚠️ Y **不取反** —— native 给的就是屏幕坐标语义
             * ============================================================
             * 这里第一版写了 `- animY.value`，于是**两个摇杆的 Y 都反了**。
             *
             * 原因:native monitor 输出的 `ly` / `ry` 已经把 evdev 的
             * "上为正"翻成了**屏幕的"下为正"**。
             *
             * ```
             * val ty = cy + y * travel     // ← 加号，不是减号
             * ```
             *
             * 所以 `StickState.ly` / `ry` 是**屏幕符号**，这里直接加。
             * 再加一次负号就等于又翻了回去。
             */
            /*
             * ============================================================
             * ⚠️ 这里就是"两种显示方式"的分岔口 —— **只有这一处**
             * ============================================================
             * | 模式 | 读什么 |
             * |---|---|
             * | **精准** | [targetX] / [targetY]（组合时算好的**纯计算值**，零延迟） |
             * | **平滑** | `smoothX` / `smoothY`（常驻帧循环里的弹簧值） |
             *
             * ⚠️ 精准那条路**一个 State 都不碰** —— 这是它必然可用的原因。
             * 以前两种模式都绕一个 State 走，于是"State 一坏、两种模式一起死"。
             *
             * ⚠️ `smoothX` 在**绘制阶段**读:Compose 会为它注册
             * "绘制失效"，动画每帧自动重画。放到组合阶段读就会被
             * 捕获进闭包，要等重组才更新。
             */
            val travelRatio = travel / maxOffset.coerceAtLeast(1e-3f)
            val drawX = if (smoothingMs <= 0f) targetX else smoothX
            val drawY = if (smoothingMs <= 0f) targetY else smoothY
            val center = Offset(
                x = size.width / 2f + drawX * pxPerBase * travelRatio,
                y = size.height / 2f + drawY * pxPerBase * travelRatio,
            )

            /*
             * 帽的形状:圆角比例 `0.5` = 正圆；小于它 = 圆角方帽。
             *
             * ⚠️ 用 `drawRoundRect` 而不是 `drawCircle` ——
             * 圆角比例可调就必须走矩形那条路（`drawCircle` 画不出方帽）。
             */
            val knobCorner = knobDiameter * style.knobCornerRatio * pxPerBase
            val knobTopLeft = Offset(center.x - knobRadius, center.y - knobRadius)
            val knobSize = androidx.compose.ui.geometry.Size(knobRadius * 2f, knobRadius * 2f)
            val knobCornerRadius = androidx.compose.ui.geometry.CornerRadius(knobCorner, knobCorner)

            drawRoundRect(
                color = argbWithOpacity(style.knobColor, style.knobOpacity),
                topLeft = knobTopLeft,
                size = knobSize,
                cornerRadius = knobCornerRadius,
            )

            /* 帽的描边（透明度为 0 时不画） */
            val knobStrokeWidth = knobDiameter * style.knobStrokeWidthRatio * pxPerBase
            if (knobStrokeWidth > 0f && style.knobStrokeOpacity > 0f) {
                drawRoundRect(
                    color = argbWithOpacity(style.knobStrokeColor, style.knobStrokeOpacity),
                    topLeft = knobTopLeft,
                    size = knobSize,
                    cornerRadius = knobCornerRadius,
                    style = Stroke(width = knobStrokeWidth),
                )
            }
        }
    }
}
/**
 * 摇杆平滑时间的上限（毫秒）。
 *
 * ⚠️ 与设置页的 `JOYSTICK_SMOOTHING_MAX` **必须一致** ——
 * 不一致的话用户能拖到一个会被夹掉的值，表现是"拖到底没变化"。
 *
 * 300ms 已经很"重"了（拇指要 0.3 秒才追上大半），再大没有意义。
 */
private const val SMOOTHING_MAX_MS = 300f

/*
 * ============================================================
 * 弹簧参数
 * ============================================================
 * 出处:`GamepadOverlayView.stepFrame()`
 *
 * ```java
 * float ax = 120f * (targetX - shownX) - 22f * velocityX;
 * ```
 *
 * ⚠️ 两者的**比值**决定有没有过冲:
 *
 * | 改动 | 观感 |
 * |---|---|
 * | 阻尼调小 | 来回晃（欠阻尼） |
 * | 阻尼调大 | 变回生硬（过阻尼） |
 */
private const val AXON_SPRING_STIFFNESS = 120f
private const val AXON_SPRING_DAMPING = 22f

/**
 *
 * 用户拖平滑滑块时，刚度与阻尼**一起**乘 `本值 / smoothingMs` ——
 * 于是"快慢"变了而"有没有过冲"不变（两者比值固定）。
 *
 * ⚠️ `smoothingMs` 的默认值就是 60**。
 */
private const val AXON_SPRING_REFERENCE_MS = 60f

/**
 * 弹簧速度上限（单位:每秒多少倍的 `maxOffset`）。
 *
 * ⚠️ 这是**安全网**，不是调参旋钮 —— 见循环里"硬限幅"那段。
 * 取 20 意味着"一帧最多走约 1/3 个底盘"，比任何真实推力都快，
 * 但足以把数值发散挡在画布之外。
 */
private const val MAX_SPRING_VELOCITY_PER_SECOND = 20f


/**
 * 把配置里的颜色（`Int`）变成 Compose 的 [Color]，并套上一个**不透明度**。
 *
 * ============================================================
 * ⚠️ 为什么配置里存 `Int` 而不是 `Color`
 * ============================================================
 * `Color` 是 Compose 的类型，而 `JoystickStyle` 在 **domain 层** ——
 * domain 不该依赖 UI 框架（那样连单元测试都要拖上 Compose）。
 *
 * 而且项目里**已有的**颜色字段（`keyUpColor` / `keyDownColor` …）
 * 全是整数，新加的跟着一致，JSON 那边也不用特殊处理。
 *
 * ============================================================
 * ⚠️⚠️ 这里犯过一个"把摇杆弄消失"的错，务必看完
 * ============================================================
 * 第一版是 `alpha = color.alpha × opacity`（**两个 alpha 相乘**），
 * 理由是"颜色自带的 alpha 表示它本身多透明，opacity 是用户再调一层"。
 *
 * 听起来合理，但**前提是颜色里的 alpha 能保住** ——
 * 而它保不住:
 *
 * 配置页的颜色控件 [com.something.sthkey.ui.component.HexColorRow]
 * 只处理 **RGB**（内部 `formatHexDigits` 里 `and 0xFFFFFF` 截掉 alpha），
 * 而且项目里所有颜色字段本来就只存 RGB、透明度由独立字段管。
 *
 * 于是带 alpha 的默认值（`0xB3000000`）一旦进过配置页就被抹成 `0x00xxxxxx`，
 * 渲染时 `0 × 1.0 = 0` → **全透明 → 摇杆消失**，而且写进了存档。
 *
 * 修法:**不透明度只由 opacity 决定**，颜色里的 alpha 一律忽略。
 *
 * ```
 * alpha = opacity          ← 只看这一个来源
 * ```
 *
 * 这样即使某处存进了带 alpha 的旧值，也只会忽略它，而不会把画面弄没 ——
 * **"错的值只影响一个方面"比"两个来源相乘"安全得多**。
 */
private fun argbWithOpacity(argb: Int, opacity: Float): Color {
    /* `or 0xFF000000` 强制不透明:忽略颜色里可能残留的 alpha */
    val rgb = Color(argb or 0xFF000000.toInt())
    return rgb.copy(alpha = opacity.coerceIn(0f, 1f))
}
/**
 * 摇杆的**几何比例** —— 与具体尺寸无关。
 *
 * ============================================================
 * 为什么要有这个东西
 * ============================================================
 * 两个手柄样式的摇杆**大小差很多**:
 *
 * | 样式 | 边长 | 来源 |
 * |---|---|---|
 * | `gamepad1` | 110 | `GamepadLayout.STICK_SIZE` |
 * | `gamepad2` | 80 | 槽位宽度（= 一个键宽） |
 *
 * 但它们的**观感**必须一致 —— 拇指相对底盘多大、能推多远。
 * 所以比例从 gamepad1 那套标定值推导一次，两边共用:
 *
 * ```
 * 推到底的距离 / 边长
 * 拇指直径     / 边长
 * ```
 *
 * ⚠️ 不要在两处各写一份比例。改了一处忘了另一处，
 * 表现是"两个手柄样式的摇杆手感不一样"，而那很难说清哪个才对。
 */
internal object JoystickSpec {

    /** 用户拇指能推到的**最大距离**（占边长的比例） */
    const val TRAVEL_RATIO =
        GamepadLayout.STICK_TRAVEL_RADIUS / GamepadLayout.STICK_SIZE

    /** 拇指**直径**（占边长的比例） */
    const val THUMB_RATIO = GamepadLayout.THUMB_SIZE / GamepadLayout.STICK_SIZE

    /**
     * 按边长算出一份几何参数。
     *
     * ⚠️ 返回值**不夹到边长的某个上限** —— 比例本身已经保证
     * `travel + thumb / 2 <= 边长 / 2`（那正是 `STICK_TRAVEL_RADIUS`
     * 的取值依据，见它的注释）。在这里再夹一次会把错藏起来。
     */
    fun of(sideDp: Float): Pair<Float, Float> =
        (sideDp * TRAVEL_RATIO) to (sideDp * THUMB_RATIO)
}

/**
 * 画一个**摇杆槽位**。
 *
 * ============================================================
 * ⚠️ 尺寸与比例都从槽位推，不写死常量
 * ============================================================
 * "摇杆多大"由 `KeyLayout` 一处决定（它同时决定窗口尺寸），
 * 两处不会再各算一遍 —— 那是"内容与窗口对不上"的常见来源。
 */
@Composable
internal fun JoystickSlot(
    x: Float,
    y: Float,
    box: KeyBox,
    scale: Float,
    style: JoystickStyle,
    modifier: Modifier = Modifier,
) {
    /*
     * ⚠️ 边长取 `box.width` —— **不再在这里乘 `style.sizeScale`**。
     *
     * 那个缩放已经由 `KeyLayout` 算进 `box.width` 了（见
     * `joystickLayoutBoxes` 里 `stickSize` 的推导）——那样它才能
     * 参与**窗口高度**的计算，否则放大后会压住下面的键。
     *
     * ⚠️ 两处都乘的话会**平方**（1.5× 变成 2.25×），
     * 表现是"调一点点就大得离谱"。
     */
    val side = box.width
    val (travel, thumb) = JoystickSpec.of(side)

    /*
     * ⚠️ 密度必须从 `LocalDensity` 读，不能靠 `scale` 反推。
     *
     * 调用方传进来的 `scale` 是 `pxToDpFactor × uiScale`，
     * 而画布是按 dp 排的 —— 两者之间差一个**密度**。
     * 见 [baseToPixelFactor] 的推导（那里记录了一次真实的错）。
     */
    val density = LocalDensity.current.density

    Joystick(
        x = x,
        y = y,
        sizeDp = side,
        scale = scale,
        pxPerBase = baseToPixelFactor(scale, density),
        travelRadius = travel,
        thumbSize = thumb,
        style = style,
        modifier = modifier,
    )
}
/**
 * 摇杆的配色。
 *
 * 与按键样式用的是同一套语义（未按下/按下），但摇杆没有"按下"状态，
 * 所以只用一组。
 */
data class JoystickColors(
    val fill: Color = Color(0xB3000000),
    val stroke: Color = Color(0x66FFFFFF),
    val thumb: Color = Color(0xE6FFFFFF),
)

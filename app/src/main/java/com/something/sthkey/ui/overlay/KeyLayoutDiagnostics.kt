package com.something.sthkey.ui.overlay

import com.something.sthkey.capture.LiveWindow
import com.something.sthkey.domain.custom.CustomLayout
import com.something.sthkey.domain.style.KeyLayout
import com.something.sthkey.domain.style.OverlayStyleRegistry
import com.something.sthkey.domain.style.StyleId

/**
 * 悬浮窗尺寸诊断。
 *
 * ============================================================
 * ⚠️ 必须按**样式**分别算，不能一套算法套所有样式
 * ============================================================
 * 第一版无论什么样式都用 `KeyLayout.keys()` 推位置，而自定义 Key 样式
 * 根本不用那套布局。于是面板对自定义配置报出一堆**看着煞有介事、
 * 其实毫无意义**的数字：它把按键布局的坐标当成了自定义内容的坐标，
 * 算出"内容 334 宽、窗口只有 311"这种结论 —— 而真实情况完全不是那样。
 *
 * **诊断工具算错比没有诊断更糟**：它会把人稳稳地引向错误的方向，
 * 而且因为数字看起来自洽，很难被怀疑。所以这里按样式分流：
 * 按键样式走 [KeyLayout]，自定义样式走 [CustomLayout]，各算各的。
 *
 * 面板还刻意打出**尺寸的来源**（[OverlayStyleRegistry.baseSizeOf]）——
 * 窗口到底该多大由它决定，与"内容摆在哪"是两件事。
 */
fun keyLayoutDiagnosticReport(live: LiveWindow): String {
    val config = live.config

    val sb = StringBuilder()

    sb.appendLine("【这个窗口】")
    sb.appendLine("  配置：${config.name}（${config.id.take(8)}）")
    sb.appendLine("  样式：${config.styleId}")
    /*
     * 把这份配置的**关键取值**直接打出来。
     *
     * 排查过程中反复出现"面板显示的缩放与实际不符"，光看结论分不清
     * 是"配置读错了"还是"尺寸算错了"。原始值摆出来，矛盾会自己暴露。
     */
    sb.appendLine("  整体缩放 scalePercent = ${config.scalePercent}")
    sb.appendLine("  文字缩放 textScalePercent = ${config.textScalePercent}")
    sb.appendLine("  显示 Shift = ${config.showShiftKey}，显示鼠标键 = ${config.showMouseButtons}")
    sb.appendLine(
        "  CPS = ${config.mouseCpsEnabled}" +
            (if (config.mouseCpsEnabled) "（模式 ${config.mouseCpsMode}）" else ""),
    )

    val scale = KeyLayout.uiScale(config)

    /*
     * ============================================================
     * 窗口尺寸：它由**样式**决定，与内容怎么摆无关
     * ============================================================
     */
    val base = OverlayStyleRegistry.baseSizeOf(config)
    val windowWidth = overlayWindowWidthPx(config)
    val windowHeight = overlayWindowHeightPx(config)
    val requestedWidth = toWindowPx(windowWidth)
    val requestedHeight = toWindowPx(windowHeight)

    sb.appendLine()
    sb.appendLine("【这个窗口的尺寸】")
    sb.appendLine("  样式声明的内容尺寸 baseSize = ${base.width} × ${base.height}")
    sb.appendLine("  我们请求：$requestedWidth × $requestedHeight px")
    sb.appendLine(
        "  窗口实得：${live.windowWidthPx} × ${live.windowHeightPx} px" +
            "  位置 (${live.x}, ${live.y})",
    )
    sb.appendLine("  View 测量：${live.viewWidthPx} × ${live.viewHeightPx} px")

    /*
     * ⚠️ "我们请求的尺寸"与"窗口实得"不一致，**本身不是问题**。
     *
     * 排查"某个键被裁"时，我把这个差值当成了报警条件 ——
     * 结果它把我引偏了两轮：窗口尺寸是按公式**推算**的，
     * 与系统最终给的空间差几像素很正常，而内容早就改成
     * "按容器实际空间自适应"了，那几像素的出入不再有任何后果。
     *
     * 所以这里只**如实报告**，不打警告标记。
     * 真正该看的是下面那段【自定义画布的实际渲染几何】。
     */
    val widthDiff = requestedWidth - live.windowWidthPx
    val heightDiff = requestedHeight - live.windowHeightPx
    if (widthDiff != 0 || heightDiff != 0) {
        sb.appendLine(
            "  与推算的差：宽 ${widthDiff}px、高 ${heightDiff}px" +
                "（自适应渲染，不影响内容；不超出容器就没问题）",
        )
    }
    sb.appendLine("  内边距每侧 $OVERLAY_PADDING_PX px")
    sb.appendLine(
        "  内容可用区：${live.windowWidthPx - OVERLAY_PADDING_PX * 2} × " +
            "${live.windowHeightPx - OVERLAY_PADDING_PX * 2} px",
    )

    sb.appendLine()
    sb.appendLine("【按这份配置算出来的】")
    sb.appendLine("  整体缩放：滑块 ${config.scalePercent}% → 实际 ${"%.3f".format(scale)} 倍")

    /*
     * ============================================================
     * 内容：按样式分流
     * ============================================================
     */
    if (config.styleId == StyleId.CUSTOM_KEY) {
        appendCustomSection(sb = sb, live = live, scale = scale)
    } else {
        appendKeySection(sb = sb, live = live, scale = scale)
    }

    return sb.toString().trimEnd()
}

/**
 * 自定义 Key 样式：内容是用户摆的组件，窗口尺寸取包围盒。
 *
 * ⚠️ 位置诊断这里最要紧的是**包围盒偏移与组件坐标是否一致**：
 * 渲染时 `CustomLayout.toWindow` 会按偏移平移一次，而组件坐标是
 * 平移**之后**的结果。两者一旦不同源，画面就会整体错位 ——
 * "转换后右边被裁"那个 bug 就是这么来的（转换时多做了一次居中）。
 */
private fun appendCustomSection(
    sb: StringBuilder,
    live: LiveWindow,
    scale: Float,
) {
    val components = live.config.custom.components
    if (components.isEmpty()) {
        sb.appendLine("  这份自定义布局没有任何组件")
        return
    }

    val bounds = CustomLayout.bounds(components)

    sb.appendLine("  组件数：${components.size}")
    sb.appendLine(
        "  内容包围盒（基础）：${"%.1f".format(bounds.offsetX)}, " +
            "${"%.1f".format(bounds.offsetY)} 起，${"%.1f".format(bounds.width)} × " +
            "${"%.1f".format(bounds.height)}",
    )
    sb.appendLine(
        "  内容包围盒（像素）：${"%.1f".format(bounds.width * scale)} × " +
            "${"%.1f".format(bounds.height * scale)}",
    )

    /*
     * 平移一致性：偏移必须等于组件的最小坐标。
     *
     * 不一致就说明有人额外平移过一次（转换、草稿编辑都可能），
     * 后果是画面整体偏移、边缘被裁。
     */
    val minX = components.minOf { it.x }
    val minY = components.minOf { it.y }
    val shiftDx = bounds.offsetX - minX
    val shiftDy = bounds.offsetY - minY
    if (kotlin.math.abs(shiftDx) > 0.01f || kotlin.math.abs(shiftDy) > 0.01f) {
        sb.appendLine(
            "  ⚠️ 包围盒偏移与组件坐标不一致（差 " +
                "${"%.1f".format(shiftDx)}, ${"%.1f".format(shiftDy)}）" +
                "—— 渲染会按偏移平移，画面将整体错位",
        )
    } else {
        sb.appendLine("  ✓ 包围盒偏移与组件坐标一致（渲染不会错位）")
    }

    val available = live.windowWidthPx - OVERLAY_PADDING_PX * 2
    val contentWidthPx = bounds.width * scale
    if (contentWidthPx > available + 0.5f) {
        sb.appendLine(
            "  ⚠️ 内容宽 ${"%.1f".format(contentWidthPx)} > 可用宽 $available，" +
                "右侧会被裁 ${"%.1f".format(contentWidthPx - available)}px",
        )
    }

    /*
     * ============================================================
     * 画布的**实际渲染几何**
     * ============================================================
     * 上面每一环（窗口尺寸、包围盒、组件坐标）都被数值验证过是对的，
     * 真正出问题的是"布局实际给了多少" —— 那件事只能由**布局结果**回答。
     *
     * ⚠️ 这里**不再**用名义缩放逐项推算每个组件的位置（曾经有过一段）。
     * 画布改成"按容器实际空间反推缩放"之后，名义缩放与实际渲染用的
     * 倍率就不是同一个值了，那段推算出来的坐标**不再代表屏幕上画在哪** ——
     * 而它看起来完全合理，排查时把人引向过错误方向。
     *
     * **诊断工具算错比没有诊断更糟**，所以只保留真实测量这一份。
     */
    sb.appendLine()
    sb.appendLine("【自定义画布的实际渲染几何】")
    CanvasMeasurement.report().lineSequence().forEach { sb.appendLine("  $it") }
}

/**
 * 按键样式：内容是写死的布局，窗口宽度按 [KeyLayout] 算。
 */
private fun appendKeySection(
    sb: StringBuilder,
    live: LiveWindow,
    scale: Float,
) {
    val config = live.config
    val boxes = KeyLayout.keys(config)

    if (boxes.isEmpty()) {
        sb.appendLine("  这份配置没有任何键位")
        return
    }

    val left = boxes.minOf { it.centerX - it.width / 2f }
    val right = boxes.maxOf { it.centerX + it.width / 2f }

    val available = live.windowWidthPx - OVERLAY_PADDING_PX * 2
    sb.appendLine(
        "【内容（像素）：${"%.1f".format(left * scale)} .. ${"%.1f".format(right * scale)}】",
    )
    sb.appendLine(
        "  （基础坐标 ${"%.0f".format(left)}..${"%.0f".format(right)} × ${"%.3f".format(scale)}）",
    )
    boxes.forEach { box ->
        val boxLeft = (box.centerX - box.width / 2f) * scale
        val boxRight = (box.centerX + box.width / 2f) * scale
        val overflow = if (boxRight > available) {
            "  ⚠️ 超出可用宽 ${"%.1f".format(boxRight - available)}px"
        } else {
            ""
        }
        sb.appendLine(
            "  ${box.slotId.padEnd(7)} ${"%.1f".format(boxLeft)} .. ${"%.1f".format(boxRight)}" +
                "（宽 ${"%.1f".format(box.width * scale)}）$overflow",
        )
    }
}

/**
 * 没有任何窗口在显示时给一句说明。
 *
 * 单独提出来是因为"没窗口"和"有窗口但算不出来"要分得清 ——
 * 前者是正常状态（用户还没开开关），后者才是问题。
 */
fun noLiveWindowReport(): String =
    "当前没有正在显示的悬浮窗\n\n" +
        "这个面板读的是**运行中那个窗口**的真实尺寸，" +
        "所以先把要排查的配置打开（主页那个开关）。"

/** 有多个窗口时，让调用方知道该看哪一个 */
fun liveWindowPickerHint(count: Int): String =
    if (count <= 1) "" else "（有 $count 个窗口，下面逐个列出）"

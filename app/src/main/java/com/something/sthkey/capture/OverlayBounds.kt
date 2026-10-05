package com.something.sthkey.capture

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import com.something.sthkey.core.log.AppLog
import com.something.sthkey.domain.overlay.OverlayLayout

/**
 * 悬浮窗的边界与偏移范围。
 *
 * ============================================================
 * 为什么单独抽出来
 * ============================================================
 * 这两件事都**只跟屏幕与窗口尺寸有关**，不涉及任何窗口生命周期：
 * - 拖动时要把坐标夹在屏内；
 * - 偏移滑块的取值范围按屏幕算；
 * - 屏幕旋转后两者都要重算。
 *
 * 塞进 Service 里的话，它们会散在"拖动""应用设置""旋转"三处各写一遍，
 * 迟早出现"拖动有边界、滑块没有"这种不一致。这里一处定义，三处调用。
 *
 * ============================================================
 * 旧项目的教训
 * ============================================================
 * 旧项目悬浮窗尺寸写死（固定 420 高），实际内容更矮 —— 于是窗口底部永远
 * 留一块空白，用户**拖不到屏幕最底部**（边界是按窗口算的，不是按内容算的）。
 * 我们按内容算窗口尺寸（`KeyLayout.baseHeight` / 样式自报尺寸），
 * 所以直接用窗口尺寸做边界就是对的。
 */
object OverlayBounds {

    /** 与 OverlayService 用同一个 tag，方便在调试页一起过滤 */
    private const val TAG = "Overlay"

    /**
     * 当前**显示器**的可用区域（像素）。
     *
     * ============================================================
     * ⚠️ 这里踩过一个很严重的坑：不能用 `context.resources.displayMetrics`
     * ============================================================
     * 那玩意儿返回的是**本应用窗口**的大小，不是屏幕大小。
     * 平时全屏运行时两者恰好相等，所以看不出问题；但在 Android 11+ 的
     * **分屏 / 小窗（多窗口）**下它会变成小窗的尺寸，于是：
     *
     * 1. 服务收到 `onConfigurationChanged` → 我们误以为"屏幕尺寸变了"
     *    （其实只是自己的窗口变小了）；
     * 2. 按"小窗 ÷ 全屏"的比例去缩放所有窗口坐标 → **悬浮窗位置直接跑偏**；
     * 3. 这个错值还会被写进 `AppPrefs.overlayScreenWidth/Height` 存档；
     * 4. 之后每次夹边界都拿"小窗宽"当屏幕宽 → **悬浮窗被卡在屏幕左侧一小块里**，
     *    偏移滑块也只能在这个错误范围里挪（用户反馈的"调偏移会被卡住"）；
     * 5. 小窗划掉 → 又按存档里的错值反向缩放一次，看起来"自己恢复了"。
     *
     * 悬浮窗是 `TYPE_APPLICATION_OVERLAY`，它的坐标空间是**整块显示器**，
     * 跟本应用窗口多大毫无关系。所以这里必须问显示器本身：
     * [Display.getRealMetrics]（含状态栏/导航栏/刘海，正是覆盖层能用的范围）。
     */
    fun screenSize(context: Context): Pair<Int, Int> {
        val display = defaultDisplay(context)
        if (display != null) {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                logIfWindowDiffers(context, metrics)
                return metrics.widthPixels to metrics.heightPixels
            }
        }

        /*
         * 兜底：拿不到显示器时退回应用窗口尺寸。
         *
         * 这是**退化的**值（多窗口下会偏小），所以记一条警告 ——
         * 真机上如果出现这条，说明显示器查询失败，边界会算不准。
         */
        val fallback = context.resources.displayMetrics
        AppLog.w(
            TAG,
            "取不到显示器尺寸，回退为应用窗口尺寸：${fallback.widthPixels}×${fallback.heightPixels}",
        )
        return fallback.widthPixels to fallback.heightPixels
    }

    /** 默认显示器；理论上一定存在，取不到时返回 null 交由调用方兜底 */
    private fun defaultDisplay(context: Context): Display? = runCatching {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        manager?.getDisplay(Display.DEFAULT_DISPLAY)
    }.getOrNull()

    /**
     * 显示器尺寸与应用窗口尺寸不一致时记一条日志。
     *
     * 这条日志是上面那个坑的**哨兵**：出现它就说明当前处于多窗口模式，
     * 而此时"用应用窗口尺寸当屏幕"必然出错。只要它没出现，
     * 就说明全屏运行、两者相等（老代码在这个场景下是碰巧对的）。
     * 用 once 标记，避免每次同步都刷屏。
     */
    private var windowSizeMismatchLogged = false

    private fun logIfWindowDiffers(context: Context, display: DisplayMetrics) {
        val window = context.resources.displayMetrics
        if (display.widthPixels == window.widthPixels && display.heightPixels == window.heightPixels) {
            return
        }
        if (windowSizeMismatchLogged) return
        windowSizeMismatchLogged = true

        AppLog.i(
            TAG,
            "显示器与应用窗口尺寸不同（多窗口 / 分屏 / 小窗）：" +
                "显示器 ${display.widthPixels}×${display.heightPixels}，" +
                "应用窗口 ${window.widthPixels}×${window.heightPixels} —— " +
                "窗口位置与边界一律按**显示器**计算",
        )
    }

    /**
     * 新窗口的默认位置：**阶梯错开**。
     *
     * 多个窗口都用同一个默认位置的话，后开的那一个会**完全盖住**前一个 ——
     * 用户会以为开关没生效，其实是被压在下面了。
     *
     * @param index 这是第几个窗口（从 0 开始）
     * @param stepPx 每级错开的像素数（由调用方按 dp 换算，这里不碰密度）
     */
    fun cascadeDefault(baseX: Int, baseY: Int, index: Int, stepPx: Int): Pair<Int, Int> =
        (baseX + index * stepPx) to (baseY + index * stepPx)

    /**
     * 把窗口坐标夹进屏幕内。
     *
     * 窗口比屏幕还大时（极端缩放），上限会小于下限 —— 此时贴左上角，
     * 而不是让 coerceIn 抛异常（`coerceIn(min, max)` 在 min > max 时会 throw）。
     */
    fun clamp(
        x: Int,
        y: Int,
        windowWidth: Int,
        windowHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
    ): Pair<Int, Int> {
        val maxX = (screenWidth - windowWidth).coerceAtLeast(0)
        val maxY = (screenHeight - windowHeight).coerceAtLeast(0)
        return x.coerceIn(0, maxX) to y.coerceIn(0, maxY)
    }

    /**
     * 「可移出屏幕外」时用的坐标 —— **完全不夹**。
     *
     * ============================================================
     * ⚠️ 为什么不留"至少一条边在屏内"
     * ============================================================
     * 我第一版留了 40px 的边（怕用户把窗口拖丢、再也点不到）。
     * 用户否掉了，理由很对:
     *
     * > "这个 40px 其实是多此一举，加了跟没加没啥区别……
     * > 很多用户需要这个的目的其实也只是往下面移一点点而已，
     * > 而且你这样做也不符合这个开关的用意。"
     *
     * ⚠️ 关键在最后一句:这个开关的**用意**是"**不受系统约束**"。
     * 留一条边等于又给它加了一个我们自己的约束 —— 那正是用户想关掉的东西。
     *
     * ⚠️ 而且"拖丢了怎么办"**已经有答案了**:悬浮窗设置的弹窗里有
     * **「重置位置与偏移」** 按钮（`OverlayLayouts.resetPosition`），
     * 而那个入口在**主页**上、不依赖窗口本身可见。
     *
     * ⚠️ 屏幕坐标**允许负值**（左上方向），这也是"移出屏幕"必须的 ——
     * `clamp` 里那个 `coerceIn(0, …)` 正是要放开的东西。
     */
    fun clampOrFree(
        x: Int,
        y: Int,
        windowWidth: Int,
        windowHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
        movableOffScreen: Boolean,
    ): Pair<Int, Int> = if (!movableOffScreen) {
        clamp(x, y, windowWidth, windowHeight, screenWidth, screenHeight)
    } else {
        /*
         * ⚠️ **原样返回**，一个都不夹。
         *
         * `windowWidth` / `screenWidth` 这些参数在自由模式下用不到 ——
         * 保留它们是为了**两条分支同一个签名**，调用方不必分叉
         * （分叉就会有人只改一边，那正是这个功能最容易出的错）。
         */
        x to y
    }

    /**
     * 偏移滑块的最大绝对值：**可用余量**（屏幕尺寸 − 窗口尺寸）。
     *
     * ============================================================
     * 为什么不是"半屏"
     * ============================================================
     * 半屏偏移在多数机型上**会被 [clamp] 吃掉一大半**：
     * 竖屏手机宽约 1080px、窗口宽约 300~500px，半屏就是 540，
     * 而真正能移动的余量只有 580~780 —— 看起来还够，
     * 但只要基础坐标不在最左，`基础 + 540` 立刻超界。
     * 表现就是**滑块推到中间以后窗口不再动**，用户以为滑块坏了。
     *
     * 用可用余量则每一段行程都真实有效：拉到一端正好贴屏幕边缘、另一端贴另一边缘，
     * 0 在正中间。而且因为"可触摸关掉时窗口拖不动"，
     * 这段行程正是**唯一**能把它挪到任意位置的手段，不能有任何一段是死的。
     *
     * ⚠️ 这个值与屏幕、窗口尺寸都绑定，所以**旋转 / 改缩放后必须重算**。
     */
    fun maxOffset(
        windowWidth: Int,
        windowHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
    ): Pair<Int, Int> =
        (screenWidth - windowWidth).coerceAtLeast(0) to
            (screenHeight - windowHeight).coerceAtLeast(0)

    /**
     * 屏幕尺寸变化后**重新锚定**基础坐标。
     *
     * ============================================================
     * 为什么不能只夹边界
     * ============================================================
     * 窗口位置是**像素**，旋转后屏幕宽高互换：竖屏放在右下角的窗口，
     * 横屏时那个 X 已经超出屏宽，只夹边界会让它"啪"地贴到右边缘，
     * 用户看到的是一次莫名其妙的跳位。
     *
     * 这里按**窗口中心**等比换算：中心在原屏的相对位置与在新屏的相对位置相同。
     * 于是"贴右下角"的窗口旋转后仍在右下角，"大致居中"的仍在中间 ——
     * 这是所有桌面系统处理分辨率变化时用的同一套办法。
     *
     * @param size 旧尺寸，[current] 新尺寸
     */
    fun rescaleToScreen(
        x: Int,
        y: Int,
        windowWidth: Int,
        windowHeight: Int,
        size: Pair<Int, Int>,
        current: Pair<Int, Int>,
    ): Pair<Int, Int> {
        val (oldWidth, oldHeight) = size
        val (newWidth, newHeight) = current
        // 旧尺寸无效（首帧/退化）时不做换算，只夹边界
        if (oldWidth <= 0 || oldHeight <= 0) {
            return clamp(x, y, windowWidth, windowHeight, newWidth, newHeight)
        }

        // 按窗口中心换算：中心点在新屏的相对位置保持一致
        val centerX = (x + windowWidth / 2f) * newWidth / oldWidth
        val centerY = (y + windowHeight / 2f) * newHeight / oldHeight

        return clamp(
            x = (centerX - windowWidth / 2f).toInt(),
            y = (centerY - windowHeight / 2f).toInt(),
            windowWidth = windowWidth,
            windowHeight = windowHeight,
            screenWidth = newWidth,
            screenHeight = newHeight,
        )
    }

    /**
     * 算出窗口最终该放在哪：基础坐标 + 偏移，再夹进屏内。
     *
     * 这是"位置"这件事的**唯一算法** —— 拖动、滑块、旋转、重置都走它，
     * 免得某个入口忘了夹边界。
     *
     * @param defaultX/defaultY 用户从未拖动过时用的初始位置
     */
    fun resolvePosition(
        layout: OverlayLayout,
        defaultX: Int,
        defaultY: Int,
        windowWidth: Int,
        windowHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
    ): Pair<Int, Int> = clamp(
        x = layout.positionX(defaultX),
        y = layout.positionY(defaultY),
        windowWidth = windowWidth,
        windowHeight = windowHeight,
        screenWidth = screenWidth,
        screenHeight = screenHeight,
    )
}

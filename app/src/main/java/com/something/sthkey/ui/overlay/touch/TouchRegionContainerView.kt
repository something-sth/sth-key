package com.something.sthkey.ui.overlay.touch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.something.sthkey.domain.config.TouchRegionGeometry
import com.something.sthkey.domain.custom.TouchDirection

/**
 * **单容器**触摸区域编辑器（P2:可拖动）。
 *
 * ============================================================
 * 为什么换成"一块容器"
 * ============================================================
 * 旧实现是"每个区域一个悬浮窗、各自 `setLayout(x, y, w, h)`"，
 * 每个窗口都要自己算屏幕坐标与旋转补偿 —— 只要有一处算错，
 * 用户看到的就是"区域卡在屏幕左边、过不去右边"（真实反馈）。
 *
 * 现在:一块容器，区域按**百分比**摆在里面。
 *
 * ============================================================
 * ⚠️ 坐标换算（**别改**）
 * ============================================================
 * 百分比 → 像素用 [TouchRegionGeometry.bounds]，并且:
 *
 * - `screenWidth/screenHeight` 传**整块显示**的尺寸（不是本 View 的宽高）;
 * - 算出绝对坐标后再**减掉本 View 在屏幕上的原点偏移**。
 *
 * ⚠️ 为什么要这么做:无障碍 overlay 窗口会被系统按可用区域内缩
 * （实测固定少 141px，见 `P1 坐标诊断` 日志），而**游戏里的触点**
 * 是按整块面板归一化的。不减偏移，画出来的框就会与真实命中范围错开 5%。
 *
 * ⚠️ 偏移是**运行时实测**的（`getLocationOnScreen`），不是硬编码，
 * 所以换设备 / 转屏 / 分屏都会自动跟上。
 */
class TouchRegionContainerView(context: Context) : View(context) {

    /** 一个区域（中心 + 边长，全是归一化值；与配置里的 `TouchRegionBinding` 对应） */
    data class Item(
        val id: String,
        val label: String,
        val centerX: Float,
        val centerY: Float,
        val size: Float,
        /*
         * ⚠️ 区域类型（只有自定义 Key 需要）:
         * 轮盘区域才会被当成"移动摇杆"驱动，按键区域只是"点一下"。
         * 触屏样式恒为 null（它的轮盘由 `@move` 槽位决定）。
         */
        val kind: com.something.sthkey.domain.custom.CustomTouchRegion.Kind? = null,
        /*
         * ⚠️ 自定义 Key 的**绑定**（组件 id + 方向）。
         *
         * 触屏样式恒为空 —— 它的区域是槽位决定的，"绑谁"是固定的。
         *
         * ⚠️ 只放 `(id, 方向)` 而不是 `CustomTouchRegionBinding`:
         * 编辑器的 `Item` 是**屏幕上的一个方块**，它只需要"怎么画、怎么显示"；
         * 领域类型在**保存时**才由服务端翻回去（见 `commitAndExit`）。
         * 把领域对象搬进 View 层会让"哪一层负责持久化"变模糊。
         */
        val bindings: List<Pair<String, TouchDirection?>> = emptyList(),
    )

    var items: List<Item> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    /** 顶部提示文字 */
    var hint: String = ""
        set(value) {
            field = value
            invalidate()
        }

    /**
     * 拖动结束 / 点「保存」时回调，给出**归一化**后的区域列表。
     *
     * ⚠️ 只回调用户真正动过的那些 —— 没动过的保持原样，由服务端合并。
     */
    var onSave: ((List<Item>) -> Unit)? = null

    /**
     * 是否在容器上画「保存 / 取消」按钮。
     *
     * ⚠️ 用户定的分工:保存/不保存在**尺寸面板**里，容器只负责"看与拖"。
     * 所以面板接管后这里传 false（按钮不画、也不响应点击）。
     */
    var showButtons: Boolean = false

    /**
     * 是否在每块方块左上角画**序号**。
     *
     * ⚠️ 只有**自定义 Key** 打开:它的区域是用户自己加的，绑同一个按键时
     * 标题会一模一样;触屏样式的行名是槽位（W / A / LMB…），本来就唯一。
     *
     * ⚠️ 序号与面板行用的是**同一个下标**（都是 `items` 的顺序），
     * 所以"面板第 3 行"永远对应"屏幕上第 ③ 块"。
     */
    var showOrdinals: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** 点「取消」或返回键时回调 */
    var onCancel: (() -> Unit)? = null

    /** 整块显示的像素尺寸（百分比基准） */
    var screenWidthPx: Int = 0
    var screenHeightPx: Int = 0

    /** 本 View 左上角在屏幕坐标系里的位置（每次绘制刷新） */
    private val originOnScreen = IntArray(2)

    /* ------------------------------------------------------------ 拖动状态 */

    private var draggingIndex = -1
    private var dragTouchOffsetX = 0f
    private var dragTouchOffsetY = 0f

    /** 当前这次拖动是"改大小"而不是"挪位置" */
    private var resizing = false

    /** 用户动过的区域 id（保存时只提交这些） */
    private val touched = linkedSetOf<String>()

    /* ------------------------------------------------------------ 画笔 */

    /** 填充 / 描边画笔（颜色逐个区域设置） */
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF7C4DFF.toInt()
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        color = 0xFF7C4DFF.toInt()
    }

    /**
     * 区域**序号**（左上角）。
     *
     * ============================================================
     * ⚠️ 它解决的是"几块区域名字一样"
     * ============================================================
     * 都绑同一个按键时，标题会完全相同。用户提了两个方案:
     * 名字染色 / 加序号 —— 最后选了序号（理由见 [touchRegionOrdinal]）。
     *
     * ⚠️ 序号**不拼进标题**，而是单独画在左上角:
     * 标题在方块上会被截断（`…(按键 · A)`），拼进去的话序号也会被切掉。
     * 单独画还顺带解决了"方块极小、平时不画标题"的情况 ——
     * 那种时候序号仍然看得见，用户至少知道这是第几块。
     *
     * ⚠️ 白色 + 黑色阴影:方块底色是半透明填充叠在游戏画面上，
     * 再叠一块不透明底会挤掉里面的名称 —— 阴影足够让数字在浅色块上也清楚。
     */
    private val ordinalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * resources.displayMetrics.scaledDensity
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(2.5f * resources.displayMetrics.density, 0f, 0f, 0xCC000000.toInt())
    }

    /** 给颜色加透明度（0x00..0xFF） */
    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha shl 24)

    private val activeBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
        color = 0xFFFFC107.toInt()
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * resources.displayMetrics.scaledDensity
        color = Color.WHITE
    }

    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC000000.toInt()
    }

    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f * resources.displayMetrics.scaledDensity
        color = Color.WHITE
    }

    private val hintBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6000000.toInt()
    }

    private val buttonBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1565C0.toInt()
    }

    private val buttonTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 14f * resources.displayMetrics.scaledDensity
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    private val cancelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC333333.toInt()
    }

    /* ------------------------------------------------------------ 布局辅助 */

    private val density: Float get() = resources.displayMetrics.density

    private fun statusBarInset(): Float = originOnScreen[1].toFloat()

    /** 「保存」按钮矩形（右下角，避开导航栏） */
    private fun saveRect(): RectF {
        val d = 16f * density
        val w = 88f * density
        val h = 44f * density
        val bottom = height - d - 24f * density
        return RectF(width - d - w, bottom - h, width - d, bottom)
    }

    /** 「取消」按钮矩形（保存左边） */
    private fun cancelRect(): RectF {
        val save = saveRect()
        val d = 8f * density
        val w = 72f * density
        return RectF(save.left - d - w, save.top, save.left - d, save.bottom)
    }

    /** 屏幕绝对坐标 → 本 View 坐标 */
    private fun toViewX(screenX: Float): Float = screenX - originOnScreen[0]

    private fun toViewY(screenY: Float): Float = screenY - originOnScreen[1]

    /** 屏幕宽（像素）—— 归一化 X 的基准 */
    private fun screenW(): Float = (screenWidthPx.takeIf { it > 0 } ?: width).toFloat()

    /** 屏幕高（像素）—— 归一化 Y 的基准 */
    private fun screenH(): Float = (screenHeightPx.takeIf { it > 0 } ?: height).toFloat()

    /** 归一化区域 → 本 View 内的矩形 */
    private fun rectOf(item: Item, out: RectF) {
        val sw = screenWidthPx.takeIf { it > 0 } ?: width
        val sh = screenHeightPx.takeIf { it > 0 } ?: height
        val b = TouchRegionGeometry.bounds(
            centerX = item.centerX,
            centerY = item.centerY,
            size = item.size,
            screenWidth = sw,
            screenHeight = sh,
            density = density,
        )
        out.set(toViewX(b.left.toFloat()), toViewY(b.top.toFloat()), toViewX(b.right.toFloat()), toViewY(b.bottom.toFloat()))
    }

    /** 本 View 坐标 → 归一化中心（反向换算，与 [rectOf] 严格对称） */
    private fun centerOf(viewX: Float, viewY: Float): Pair<Float, Float> {
        val sw = (screenWidthPx.takeIf { it > 0 } ?: width).toFloat()
        val sh = (screenHeightPx.takeIf { it > 0 } ?: height).toFloat()
        val screenX = viewX + originOnScreen[0]
        val screenY = viewY + originOnScreen[1]
        return (screenX / sw).coerceIn(0f, 1f) to (screenY / sh).coerceIn(0f, 1f)
    }

    /* ------------------------------------------------------------ 绘制 */

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        getLocationOnScreen(originOnScreen)

        val contentWidth = screenWidthPx.takeIf { it > 0 } ?: width
        val contentHeight = screenHeightPx.takeIf { it > 0 } ?: height
        if (contentWidth <= 0 || contentHeight <= 0) return

        val rect = RectF()
        items.forEachIndexed { index, item ->
            rectOf(item, rect)
            /*
             * 外观对齐**旧编辑器**:每个区域一个颜色、半透明填充 + 实心描边，
             * 标签直接画在方块**里面**（不是吊在外面）。
             *
             * ⚠️ 颜色按**下标**取（与槽位顺序绑定），刷新后不会乱跳。
             */
            val color = touchRegionColor(index)
            fillPaint.color = withAlpha(color, 0x3D)
            strokePaint.color = color
            strokePaint.strokeWidth = if (index == draggingIndex) 3f * density else 1.5f * density

            canvas.drawRoundRect(rect, 8f * density, 8f * density, fillPaint)
            canvas.drawRoundRect(rect, 8f * density, 8f * density, strokePaint)

            /*
             * ============================================================
             * 方块里的文字（方案 §11.3）
             * ============================================================
             * | 情况 | 处理 |
             * |---|---|
             * | 够宽 | 原字号、居中整段 |
             * | 略超 | 按比例缩字号，最小 8sp，不换行 |
             * | 仍超 | 截断成 `…(按键 · A)` —— 保住括号里的组件名 |
             * | 方块极小 | 只在**拖动**时画字，平时只画色块 |
             *
             * ⚠️ 之前是"超宽就从左边对齐、让它自然溢出" —— 文字会**顶出方块**，
             * 跟旁边那块的字叠在一起，两行都读不出来。
             */
            val label = item.label
            val textWidth = rect.width() - 8f * density
            val tooSmall = rect.width() < 44f * density || rect.height() < 22f * density
            if (label.isNotEmpty() && textWidth > 0f && (!tooSmall || index == draggingIndex)) {
                val baseSize = 12f * resources.displayMetrics.scaledDensity
                val minSize = 8f * resources.displayMetrics.scaledDensity
                val fitted = fitRegionLabel(label, textWidth, baseSize, minSize) { value, size ->
                    labelPaint.textSize = size
                    labelPaint.measureText(value)
                }
                if (fitted.text.isNotEmpty()) {
                    labelPaint.textSize = fitted.textSize
                    /*
                     * ⚠️ **未绑定用红字**（与面板一致）:
                     * 方块照样画着、按下去却没反应 —— 这是旧模型最坑人的地方，
                     * 所以必须在屏幕上就能看出来。
                     *
                     * 触屏样式（kind == null）没有"未绑定"这个概念，保持白字。
                     */
                    labelPaint.color = if (item.kind != null && item.bindings.isEmpty()) {
                        0xFFFF6B6B.toInt()
                    } else {
                        Color.WHITE
                    }
                    val w = labelPaint.measureText(fitted.text)
                    canvas.drawText(
                        fitted.text,
                        rect.centerX() - w / 2f,
                        rect.centerY() + fitted.textSize / 3f,
                        labelPaint,
                    )
                }
            }

            /*
             * **序号**（见 [ordinalPaint] 的说明）。
             *
             * ⚠️ 放在最后画:标题居中、序号在左上，正常不会重叠;
             * 万一某个长标题把左上角也占了，序号压在上面才读得到。
             *
             * ⚠️ 字号跟着方块高度缩，但**最小 8sp** —— 再小就是糊的。
             * 方块再小也画:名称可以不画，但"这是第几块"必须能看见。
             */
            if (showOrdinals) {
                val ordinal = touchRegionOrdinal(index)
                val ordinalSize = (11f * resources.displayMetrics.scaledDensity)
                    .coerceAtMost(rect.height() * 0.45f)
                    .coerceAtLeast(8f * resources.displayMetrics.scaledDensity)
                ordinalPaint.textSize = ordinalSize
                canvas.save()
                canvas.clipRect(rect)
                canvas.drawText(
                    ordinal,
                    rect.left + 4f * density,
                    rect.top + 4f * density + ordinalSize,
                    ordinalPaint,
                )
                canvas.restore()
            }
        }

        /* 顶部提示（避开状态栏：用屏幕原点偏移当 inset） */
        if (hint.isNotEmpty()) {
            val tw = hintPaint.measureText(hint)
            val th = hintPaint.textSize
            val top = statusBarInset() + 12f * density
            canvas.drawRoundRect(12f, top, 12f + tw + 20f, top + th + 14f, 8f, 8f, hintBgPaint)
            canvas.drawText(hint, 22f, top + th + 3f, hintPaint)
        }

        /* 底部按钮（面板接管后不画） */
        if (!showButtons) return

        val save = saveRect()
        canvas.drawRoundRect(save, 10f * density, 10f * density, buttonBgPaint)
        canvas.drawText(
            "保存",
            save.centerX(),
            save.centerY() + buttonTextPaint.textSize / 3f,
            buttonTextPaint,
        )

        val cancel = cancelRect()
        canvas.drawRoundRect(cancel, 10f * density, 10f * density, cancelBgPaint)
        canvas.drawText(
            "取消",
            cancel.centerX(),
            cancel.centerY() + buttonTextPaint.textSize / 3f,
            buttonTextPaint,
        )
    }

    /* ------------------------------------------------------------ 触摸 */

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (showButtons && saveRect().contains(event.x, event.y)) {
                    commitAndExit()
                    return true
                }
                if (showButtons && cancelRect().contains(event.x, event.y)) {
                    onCancel?.invoke()
                    return true
                }

                val rect = RectF()
                /* 从后往前找:后画的在上面，命中也应该优先 */
                for (index in items.indices.reversed()) {
                    rectOf(items[index], rect)
                    /* 稍微放大命中区，手指不容易"差一点没点到" */
                    val slop = 8f * density
                    if (rect.left - slop <= event.x && event.x <= rect.right + slop &&
                        rect.top - slop <= event.y && event.y <= rect.bottom + slop
                    ) {
                        draggingIndex = index
                        /* ⚠️ 尺寸只在面板里调（用户要求）—— 拖动一律是"挪位置" */
                        dragTouchOffsetX = event.x - rect.centerX()
                        dragTouchOffsetY = event.y - rect.centerY()
                        invalidate()
                        return true
                    }
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val index = draggingIndex
                if (index < 0) return true
                val item = items[index]
                val (nx, ny) = centerOf(event.x - dragTouchOffsetX, event.y - dragTouchOffsetY)
                items = items.toMutableList().also { it[index] = item.copy(centerX = nx, centerY = ny) }
                touched += item.id
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (draggingIndex >= 0) {
                    draggingIndex = -1
                    invalidate()
                }
                return true
            }
        }
        return true
    }

    /** 只提交**动过**的区域，其余由服务端保持原值 */
    fun commitAndExit() {
        val changed = items.filter { it.id in touched }
        if (changed.isEmpty()) {
            onCancel?.invoke()
            return
        }
        onSave?.invoke(changed)
    }
}

package com.something.sthkey.ui.overlay.touch

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/**
 * 触摸区域编辑器的**悬浮胶囊**（收起 / 展开）。
 *
 * ============================================================
 * 样式来源:`D:\Project\sthtool`
 * ============================================================
 * ⚠️ **不要再自己配色/配色**。下面每个值都能在 sthtool 里找到出处:
 *
 * | 项 | 值 | 出处 |
 * |---|---|---|
 * | 胶囊底 | `#F3EDF7` | `ThemeManager.bgPrimary_Light` |
 * | 次级底 / 描边 | `#E8DEF8` | `bgSecondary_Light` |
 * | 主文字 | `#1C1B1F` | `textPrimary_Light` |
 * | 次文字 | `#49454F` | `textSecondary_Light` |
 * | 强调色 | `#6750A4` | `accent_light`（MD3 Primary）|
 * | 圆角 | 12dp | `FloatingCard` 的 `dp(12)` |
 * | 行高 | 38dp | `FloatingCard` 的 `dp(38)` |
 * | 展开动画 | 高度插值 | `FloatingCard.animateHeight` |
 * | 展开小三角 | **图标**（旋转 90°）| `FloatingCard` 的 `TriangleView` |
 *
 * ⚠️ 之前几处被用户点名的错误做法（已改）:
 * - 宽度随内容变化 → **收起/展开宽度不一致**;现在**固定宽度**;
 * - 展开没有动画 → 现在 `animateHeight`;
 * - 小三角用文本 `▸` → 现在是自绘三角形并有旋转动画;
 * - 尺寸只显示整数 → 现在**保留一位小数**。
 */
object TouchEditorPanel {

    private const val BG = 0xFFF3EDF7.toInt()
    private const val CHIP = 0xFFE8DEF8.toInt()
    private const val TEXT = 0xFF1C1B1F.toInt()
    private const val TEXT_DIM = 0xFF49454F.toInt()
    private const val THEME = 0xFF6750A4.toInt()

    /** 固定宽度（dp）—— 收起/展开必须一致 */
    private const val WIDTH_DP = 190f
    private const val ROW_H = 38f
    private const val RADIUS = 12f
    private const val ANIM_MS = 180L

    private fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Float, context: Context): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = context.dp(radiusDp).toFloat()
        }

    data class Row(val id: String, val label: String, val size: Float)

    /* ============================================================ 三角图标 */

    /**
     * 展开小三角（sthtool 的 `TriangleView` 同款）。
     *
     * ⚠️ 用画笔自绘而不是文本 `▸`:文本字形在不同字体下宽度/基线都不一样，
     * 竖着居中会偏，而且没法做旋转动画。
     */
    private class TriangleView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = THEME
            style = Paint.Style.FILL
        }
        private val path = Path()

        /** 0 = 朝右（收起）, 90 = 朝下（展开） */
        var angle: Float = 0f
            set(value) {
                field = value
                invalidate()
            }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w / 2f
            val cy = h / 2f
            path.reset()
            canvas.save()
            canvas.rotate(angle, cx, cy)
            /*
             * ⚠️ 三角尺寸:控件 14dp、臂长 = 短边的 0.30，
             * 画出来只有几个 dp —— 之前"巨大"是因为按 0.42 半径又乘 1.45 跨距。
             */
            val r = minOf(w, h) * 0.5f
            val arm = r * 0.30f
            path.moveTo(cx - arm * 0.5f, cy - arm)
            path.lineTo(cx + arm * 0.55f, cy)
            path.lineTo(cx - arm * 0.5f, cy + arm)
            path.close()
            canvas.drawPath(path, paint)
            canvas.restore()
        }
    }

    /* ============================================================ 构建 */

    fun build(
        context: Context,
        rows: List<Row>,
        expanded: Boolean,
        onToggle: () -> Unit,
        onSizeChange: (id: String, size: Float) -> Unit,
        onSave: () -> Unit,
        onDiscard: () -> Unit,
        /** 拖动开始:给出按下点的**屏幕坐标**（绝对定位用，避免累加误差）*/
        onDragStart: (rawX: Float, rawY: Float) -> Unit,
        /** 拖动中:给出当前手指的**屏幕坐标** */
        onDragMove: (rawX: Float, rawY: Float) -> Unit,
        /** 拖动结束（用来把位置存进偏好 —— 不能每帧写，会掉帧） */
        onDragEnd: () -> Unit,
    ): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(BG, RADIUS, context)
            /* ⚠️ 固定宽度:收起与展开必须一样宽，否则展开时会"跳一下宽" */
            layoutParams = LinearLayout.LayoutParams(
                context.dp(WIDTH_DP),
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        /* ---------------- 头部 ---------------- */
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(14f), 0, context.dp(12f), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                context.dp(ROW_H),
            )
        }

        header.addView(TextView(context).apply {
            text = "触摸区域"
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        val triangle = TriangleView(context).apply {
            angle = if (expanded) 90f else 0f
            /* ⚠️ 20dp 的控件里画 14dp 的箭头，四周留白才不会"顶着边框" */
            layoutParams = LinearLayout.LayoutParams(context.dp(14f), context.dp(14f))
        }
        header.addView(triangle)

        /* 拖动 / 点击判定（与之前一致：位移超过阈值算拖动） */
        var downRawX = 0f
        var downRawY = 0f
        var lastRawX = 0f
        var lastRawY = 0f
        var dragging = false
        val slop = context.dp(6f)
        header.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX; downRawY = event.rawY
                    lastRawX = event.rawX; lastRawY = event.rawY
                    dragging = false
                    onDragStart(event.rawX, event.rawY)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging &&
                        (kotlin.math.abs(event.rawX - downRawX) > slop ||
                            kotlin.math.abs(event.rawY - downRawY) > slop)
                    ) {
                        dragging = true
                    }
                    if (dragging) {
                        onDragMove(event.rawX, event.rawY)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        onToggle()
                    } else {
                        onDragEnd()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    onDragEnd()
                    true
                }
                else -> true
            }
        }
        root.addView(header)

        if (!expanded) return root

        /* ---------------- 展开体 ---------------- */
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(6f), 0, context.dp(6f), context.dp(8f))
        }

        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                context.dp(ROW_H * 4.4f).toInt(),
            )
        }
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        rows.forEach { row ->
            val line = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.dp(8f), context.dp(4f), context.dp(8f), context.dp(4f))
            }

            val title = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            title.addView(TextView(context).apply {
                text = row.label
                setTextColor(TEXT_DIM)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val valueView = TextView(context).apply {
                text = formatPercent(row.size)
                setTextColor(TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            }
            title.addView(valueView)
            line.addView(title)

            val seek = SeekBar(context).apply {
                max = PROGRESS_MAX
                progress = sizeToProgress(row.size)
                progressTintList = android.content.res.ColorStateList.valueOf(THEME)
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(CHIP)
                thumbTintList = android.content.res.ColorStateList.valueOf(THEME)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        val size = progressToSize(progress)
                        valueView.text = formatPercent(size)
                        onSizeChange(row.id, size)
                    }

                    override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar?) = Unit
                })
            }
            line.addView(seek)
            list.addView(line)
        }

        scroll.addView(list)
        body.addView(scroll)

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(context.dp(8f), context.dp(4f), context.dp(8f), 0)
        }
        actions.addView(actionButton(context, "不保存", accent = false) { onDiscard() })
        actions.addView(actionButton(context, "保存退出", accent = true) { onSave() })
        body.addView(actions)

        root.addView(body)

        /*
         * ⚠️ **展开动画**:把一个"高度 0"的 body 逐渐撑到测量高度。
         *
         * ⚠️⚠️ 必须先**测出真实高度**再清零：
         * 原来直接把 height 设 0 再读 `measuredHeight`，读到的还是 0 →
         * 动画目标 0 → body 永远不显示（用户反馈"展开后尺寸调节全没了"）。
         */
        /*
         * ⚠️ **不做展开动画**（用户明确要求去掉）:
         * 之前的高度插值观感差，展开/收起直接切换更干净。
         */

        return root
    }

    private const val PROGRESS_MAX = 1000

    private fun actionButton(
        context: Context,
        label: String,
        accent: Boolean,
        onClick: () -> Unit,
    ): TextView = TextView(context).apply {
        text = label
        setTextColor(if (accent) Color.WHITE else THEME)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        gravity = Gravity.CENTER
        setPadding(context.dp(12f), context.dp(8f), context.dp(12f), context.dp(8f))
        background = rounded(if (accent) THEME else CHIP, 10f, context)
        setOnClickListener { onClick() }
    }

    /** 尺寸显示:**保留一位小数**（用户要求，整数看不出微调效果） */
    private fun formatPercent(size: Float): String = "%.1f%%".format(size * 100f)

    private fun sizeToProgress(size: Float): Int {
        val span = com.something.sthkey.domain.config.TouchRegionSpec.SIZE_MAX -
            com.something.sthkey.domain.config.TouchRegionSpec.SIZE_MIN
        val t = (size - com.something.sthkey.domain.config.TouchRegionSpec.SIZE_MIN) / span
        return (t * PROGRESS_MAX).toInt().coerceIn(0, PROGRESS_MAX)
    }

    private fun progressToSize(progress: Int): Float {
        val span = com.something.sthkey.domain.config.TouchRegionSpec.SIZE_MAX -
            com.something.sthkey.domain.config.TouchRegionSpec.SIZE_MIN
        return (com.something.sthkey.domain.config.TouchRegionSpec.SIZE_MIN +
            span * (progress / PROGRESS_MAX.toFloat()))
            .coerceIn(
                com.something.sthkey.domain.config.TouchRegionSpec.SIZE_MIN,
                com.something.sthkey.domain.config.TouchRegionSpec.SIZE_MAX,
            )
    }
}

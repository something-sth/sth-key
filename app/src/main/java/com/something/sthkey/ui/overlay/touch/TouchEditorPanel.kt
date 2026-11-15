package com.something.sthkey.ui.overlay.touch

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.something.sthkey.domain.custom.CustomTouchRegion
import com.something.sthkey.domain.custom.TouchDirection
import com.something.sthkey.domain.custom.directionArrow

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

    /**
     * "未绑定"的警示色。
     *
     * ⚠️ 用 MD3 的 error 色（`#B3261E`），不是纯红 `#F00`:
     * 面板底色是浅紫灰，纯红对比过强、看起来像报错弹窗。
     */
    private const val WARN = 0xFFB3261E.toInt()

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

    /**
     * 面板里的一行。
     *
     * @param bindings **自定义 Key** 的区域绑定（组件 id + 方向）；
     *   触屏样式恒为空。面板靠它决定:红字"未绑定"、要不要显示「绑定」按钮。
     * @param label 已经是**成品文案**（服务端用 `touchRegionLabel` 拼好）——
     *   ⚠️ 面板不再自己拼字:屏幕方块与面板必须显示**同一句话**，
     *   各拼一份迟早不一样。
     */
    data class Row(
        val id: String,
        val label: String,
        val size: Float,
        val bindings: List<Pair<String, TouchDirection?>> = emptyList(),
        /**
         * 1 起的**显示序号**（自定义 Key 才有；触屏样式的槽位名本来就唯一）。
         * ⚠️ 与屏幕方块上的序号是**同一个下标**，见 `showOrdinals`。
         */
        val ordinal: Int? = null,
    )

    /**
     * 「绑定组件」二级页的全部状态（**面板内切换，不弹新窗口**）。
     *
     * ============================================================
     * ⚠️ 为什么不是新窗口
     * ============================================================
     * 旧编辑器用的是一个独立的悬浮窗，用户点开之后面板还在、方块还在，
     * 三个窗口叠在一起 —— 用户原话是"**多窗口串味**"。
     * 现在把胶囊内容整个换掉，关掉时再换回来，视觉上只有一块东西。
     *
     * ⚠️ 这个对象是**服务端每帧重建**的（用户点一下复选框就重建一次），
     * 所以它必须是**不可变的草稿** —— 勾选状态由服务端持有，
     * 面板只负责画。
     *
     * @param direction 当前正在编辑哪个方向（`null` = "无"，即整块区域）。
     *   ⚠️ 面板上的三×三九宫格选的就是它:选中方向后勾组件，
     *   勾出来的是"这个组件在这个方向上触发"。
     * @param candidates 可绑定的组件（按组件列表顺序；已按区域类型过滤）
     * @param bindings 草稿里的全部绑定（组件 id + 方向）
     */
    data class BindingPage(
        val kind: CustomTouchRegion.Kind,
        val direction: TouchDirection?,
        val candidates: List<BindingCandidate>,
        val bindings: List<Pair<String, TouchDirection?>>,
        /**
         * 组件列表的滚动位置（像素）。
         *
         * ⚠️ 勾一个组件会**整块重建面板**（见 [BindingPage] 上面的说明），
         * 不把滚动位置传回来的话，每勾一次列表都跳回顶部 ——
         * 组件多的时候用户要反复往下滚。
         */
        val scrollY: Int = 0,
        /**
         * 这块区域的显示序号（1 起）。
         * ⚠️ 标题里带上它:几块区域同名时，用户要知道自己正在改哪一块。
         */
        val ordinal: Int? = null,
    )

    /** 绑定页里的一行组件 */
    data class BindingCandidate(
        val id: String,
        /** 显示名 —— 与自定义编辑页预览**同一个** `displayName()` */
        val name: String,
        /** 摇杆组件（只有轮盘区域能绑；而且它不吃方向） */
        val isJoystick: Boolean,
    )


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
        /** 是否允许"新建 / 删除区域"（只有自定义 Key 为 true） */
        allowEdit: Boolean = false,
        /** 新建区域:true = 轮盘区域,false = 按键区域 */
        onAddRegion: ((Boolean) -> Unit)? = null,
        onDeleteRegion: ((String) -> Unit)? = null,
        /** 点某行的「绑定」:参数是区域 id */
        onBindRegion: ((String) -> Unit)? = null,
        /**
         * 正在编辑绑定的区域（`null` = 正常列表）。
         * ⚠️ 非 null 时**整个展开体换成绑定页**，见 [BindingPage]。
         */
        binding: BindingPage? = null,
        /** 九宫格里点了一个方向（`null` = 中间那个「无」） */
        onBindingDirection: ((TouchDirection?) -> Unit)? = null,
        /** 勾 / 取消一个组件（参数是组件 id） */
        onBindingToggle: ((String) -> Unit)? = null,
        onBindingConfirm: (() -> Unit)? = null,
        /** 点「←」或「取消」—— 丢弃草稿、回到列表 */
        onBindingCancel: (() -> Unit)? = null,
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

        /*
         * ⚠️ **绑定页**:整个展开体换成它（不弹新窗口，见 [BindingPage] 的说明）。
         * 此时不画"新建 / 区域列表 / 保存退出" —— 用户点「绑定」要看的是
         * "这块绑了谁"，同时面对两套操作只会点错。
         */
        if (binding != null) {
            body.addView(
                buildBindingBody(
                    context = context,
                    page = binding,
                    onDirection = onBindingDirection,
                    onToggle = onBindingToggle,
                    onConfirm = onBindingConfirm,
                    onCancel = onBindingCancel,
                ),
            )
            root.addView(body)
            return root
        }

        /*
         * ⚠️ **自定义 Key 专用的两个新建按钮**（触屏样式不显示）:
         * 触屏的区域由槽位决定，加不了;自定义 Key 加出来的是"用户自己的区域"。
         */
        if (allowEdit) {
            val addRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(context.dp(8f), context.dp(4f), context.dp(8f), context.dp(2f))
            }
            addRow.addView(chip(context, "＋ 轮盘") { onAddRegion?.invoke(true) })
            addRow.addView(
                LinearLayout.LayoutParams(context.dp(6f), 1).let { sp ->
                    android.view.View(context).apply { layoutParams = sp }
                },
            )
            addRow.addView(chip(context, "＋ 按键") { onAddRegion?.invoke(false) })
            body.addView(addRow)
        }

        /* ⚠️ 空态:列表为空时给一句说明（新建按钮就在上面，不用再弹窗） */
        if (allowEdit && rows.isEmpty()) {
            body.addView(TextView(context).apply {
                text = "还没有触摸区域 —— 点上面两个按钮新建"
                setTextColor(TEXT_DIM)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(context.dp(10f), context.dp(2f), context.dp(10f), context.dp(4f))
            })
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

            val valueView = TextView(context).apply {
                text = formatPercent(row.size)
                setTextColor(TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            }

            val title = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            /*
             * ============================================================
             * 序号 + 色点（用户反馈:几块区域绑同一个按键时标题一模一样）
             * ============================================================
             * | 元素 | 作用 |
             * |---|---|
             * | 色点 | 与屏幕上那块方块的**描边色**一致，快速对位（辅助）|
             * | ① ② ③ | **主标识**，名字相同也能分;方块左上角也有同一个 |
             *
             * ⚠️ 色点不把名字染色:浅紫灰底上蓝/绿/橙/青的对比度太低，
             * 11sp 会很难读（见 `touchRegionOrdinal` 的说明）。
             */
            row.ordinal?.let { ordinal ->
                title.addView(View(context).apply {
                    background = rounded(touchRegionColor(ordinal - 1), 3.5f, context)
                    layoutParams = LinearLayout.LayoutParams(
                        context.dp(7f),
                        context.dp(7f),
                    ).apply { rightMargin = context.dp(4f) }
                })
                title.addView(TextView(context).apply {
                    text = touchRegionOrdinal(ordinal - 1)
                    setTextColor(TEXT_DIM)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { rightMargin = context.dp(3f) }
                })
            }

            title.addView(TextView(context).apply {
                text = row.label
                /*
                 * ⚠️ **未绑定 = 红字**（方案 §11.2）:
                 * "画着却没效果"在旧模型里完全看不出来，用户只能靠猜。
                 */
                setTextColor(if (allowEdit && row.bindings.isEmpty()) WARN else TEXT_DIM)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                /* ⚠️ 单行 + **中间省略**:两端都保住 —— 开头是区域类型、结尾是绑定摘要。
                 * 从尾部截（END）的话，最先被切掉的恰恰是"绑了谁"。 */
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.MIDDLE
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            /* 触屏样式:尺寸值就在名字这一行（自定义 Key 挪到按钮那一行） */
            if (!allowEdit) title.addView(valueView)
            line.addView(title)

            /*
             * 自定义 Key:第二行 = [绑定] [✕] …… 右对齐的尺寸百分比。
             *
             * ⚠️ 不把这两个按钮塞进第一行 —— 面板只有 190dp 宽，
             * 名字 + 百分比 + 两个按钮挤一行的话，名字只剩四五个字
             * （"按键区域 (…"），而用户正是靠这行字分辨哪块区域是哪块。
             * ⚠️ 删除按钮也从第一行挪到这里:否则它会把名字再挤窄一次。
             */
            if (allowEdit) {
                val actions = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, context.dp(2f), 0, 0)
                }
                actions.addView(chip(context, "绑定") { onBindRegion?.invoke(row.id) })
                actions.addView(spacer(context, 4f))
                actions.addView(chip(context, "✕") { onDeleteRegion?.invoke(row.id) })
                /* 弹性空位，把百分比顶到右边 */
                actions.addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                })
                actions.addView(valueView)
                line.addView(actions)
            }

            val seek = SeekBar(context).apply {
                max = PROGRESS_MAX
                progress = sizeToProgress(row.size)
                progressTintList = ColorStateList.valueOf(THEME)
                progressBackgroundTintList = ColorStateList.valueOf(CHIP)
                thumbTintList = ColorStateList.valueOf(THEME)
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

    /** 绑定页组件列表的 tag —— 服务端用它读回滚动位置（面板每次重建） */
    const val BINDING_SCROLL_TAG = "sthkey:binding-scroll"

    /** 小胶囊按钮（新建 / 删除用） */
    private fun chip(context: Context, label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            setTextColor(THEME)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            setPadding(context.dp(10f), context.dp(5f), context.dp(10f), context.dp(5f))
            background = rounded(CHIP, 8f, context)
            setOnClickListener { onClick() }
        }

    /* ============================================================ 绑定页 */

    /**
     * 九宫格的方向顺序（**与空间位置对应**）。
     *
     * ⚠️ 中间那个 `null` 是"无"（整块区域），不是缺失 —— 用户要的是
     * "轮盘区域可以绑按键/文本组件，也可以不指定方向"（方案 §11.4）。
     *
     * ⚠️ 行/列顺序就是屏幕上的上下左右:改成 List 之后**不要**按
     * `TouchDirection.entries` 的顺序排 —— 那样"前"会在第一行中间，
     * 而"左上"跑到最后一格，用户得重新学一遍方位。
     */
    private val DIRECTION_GRID: List<List<TouchDirection?>> = listOf(
        listOf(TouchDirection.FRONT_LEFT, TouchDirection.FRONT, TouchDirection.FRONT_RIGHT),
        listOf(TouchDirection.LEFT, null, TouchDirection.RIGHT),
        listOf(TouchDirection.BACK_LEFT, TouchDirection.BACK, TouchDirection.BACK_RIGHT),
    )

    /**
     * 「绑定组件」二级页的展开体（方案 §11.4）。
     *
     * 结构（从上到下）:
     * ```
     * ← 绑定「轮盘区域」
     *   ↖ ↑ ↗          ← 九宫格；选中 = 强调色，配过的方向 = 角落小圆点
     *   ← 无 →
     *   ↙ ↓ ↘
     * 方向：↑ 前
     * ☑ W  ↑     ← 多选；勾的是"当前方向下"的绑定
     * ☐ 文本…
     * ☑ 触屏移动摇杆（MOVE_STICK 才有）
     *            [取消] [确定]
     * ```
     *
     * ⚠️ **按键区域不画九宫格**。方向只对轮盘区域有意义 ——
     * 按键区域没有"摇杆向量"，给它选方向等于给一个永远不成立的判据;
     * 真让用户选，他会以为"选了方向就只在这个方向触发"，而实际按到就触发。
     */
    private fun buildBindingBody(
        context: Context,
        page: BindingPage,
        onDirection: ((TouchDirection?) -> Unit)?,
        onToggle: ((String) -> Unit)?,
        onConfirm: (() -> Unit)?,
        onCancel: (() -> Unit)?,
    ): View {
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(8f), context.dp(2f), context.dp(8f), 0)
        }

        /* 「←」整行可点:与手机上的返回手势一致（点标题也能退） */
        body.addView(TextView(context).apply {
            val ordinal = page.ordinal?.let { touchRegionOrdinal(it - 1) + " " } ?: ""
            text = "← 绑定 $ordinal「${page.kind.label}」"
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(context.dp(2f), context.dp(6f), 0, context.dp(4f))
            setOnClickListener { onCancel?.invoke() }
        })

        if (page.kind == CustomTouchRegion.Kind.MOVE_STICK) {
            body.addView(directionGrid(context, page, onDirection))
            body.addView(TextView(context).apply {
                text = "方向：" + (
                    page.direction?.let { "${directionArrow(it)} ${it.label}" }
                        ?: "无（整块区域）"
                    )
                setTextColor(TEXT_DIM)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(context.dp(2f), context.dp(2f), 0, context.dp(2f))
            })
        } else {
            body.addView(TextView(context).apply {
                text = "按键区域：按到就触发，不需要选方向"
                setTextColor(TEXT_DIM)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(context.dp(2f), context.dp(4f), 0, context.dp(4f))
            })
        }

        /*
         * ⚠️ 组件列表用 ScrollView + 固定高度:190dp 宽的胶囊里横排 3 个
         * 复选框的话，每个名字只剩两三个字，根本分不清是哪个组件。
         */
        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                context.dp(ROW_H * 2.8f).toInt(),
            )
            /* 服务端靠这个 tag 找回滚动位置（面板每次都是重建的） */
            tag = BINDING_SCROLL_TAG
        }
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        if (page.candidates.isEmpty()) {
            list.addView(TextView(context).apply {
                text = "这个配置还没有可绑定的组件"
                setTextColor(TEXT_DIM)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(context.dp(4f), context.dp(6f), 0, context.dp(6f))
            })
        }

        page.candidates.forEach { candidate ->
            /*
             * ⚠️ 摇杆在**选了具体方向**时禁用 —— 它上报的是**轴**，没有
             * "朝某个方向才触发"这回事。不禁用的话用户勾了也看不出区别，
             * 只会以为坏了。
             */
            val disabled = candidate.isJoystick && page.direction != null
            /*
             * 勾选状态 = "这个组件在**当前方向**下有没有绑定"。
             * ⚠️ 摇杆没有方向，所以它只看"绑没绑"。
             */
            val checked = if (candidate.isJoystick) {
                page.bindings.any { it.first == candidate.id }
            } else {
                page.bindings.any { it.first == candidate.id && it.second == page.direction }
            }
            val marks = if (candidate.isJoystick) "" else boundMarks(page.bindings, candidate.id)

            list.addView(CheckBox(context).apply {
                text = candidate.name + marks + if (disabled) "（摇杆不吃方向）" else ""
                setTextColor(if (disabled) TEXT_DIM else TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                buttonTintList = ColorStateList.valueOf(THEME)
                minimumHeight = context.dp(30f)
                isChecked = checked
                isEnabled = !disabled
                alpha = if (disabled) 0.45f else 1f
                setOnCheckedChangeListener { _, _ -> onToggle?.invoke(candidate.id) }
            })
        }

        scroll.addView(list)
        body.addView(scroll)
        if (page.scrollY > 0) {
            /* 等这一轮布局完再滚 —— 直接 scrollTo 会被尚未完成的测量夹回 0 */
            scroll.post { scroll.scrollTo(0, page.scrollY) }
        }

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(context.dp(2f), context.dp(6f), context.dp(2f), 0)
        }
        actions.addView(actionButton(context, "取消", accent = false) { onCancel?.invoke() })
        actions.addView(actionButton(context, "确定", accent = true) { onConfirm?.invoke() })
        body.addView(actions)

        return body
    }

    /** 三×三方向九宫格（见 [buildBindingBody] 的说明） */
    private fun directionGrid(
        context: Context,
        page: BindingPage,
        onDirection: ((TouchDirection?) -> Unit)?,
    ): View {
        /* 已经配过绑定的方向:角落里点一个圆点，用户一眼看出"哪几向有东西" */
        val configured = page.bindings.mapNotNull { it.second }.toSet()
        val anyWholeRegion = page.bindings.any { it.second == null }

        val grid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, context.dp(2f), 0, 0)
        }
        DIRECTION_GRID.forEach { rowDirections ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            rowDirections.forEach { direction ->
                val selected = page.direction == direction
                val hasBinding = if (direction == null) anyWholeRegion else direction in configured

                val cell = FrameLayout(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        context.dp(40f),
                        context.dp(28f),
                    ).apply {
                        setMargins(context.dp(1f), context.dp(1f), context.dp(1f), context.dp(1f))
                    }
                    background = rounded(if (selected) THEME else CHIP, 8f, context)
                    setOnClickListener { onDirection?.invoke(direction) }
                }
                cell.addView(TextView(context).apply {
                    text = directionArrow(direction)
                    gravity = Gravity.CENTER
                    setTextColor(if (selected) Color.WHITE else THEME)
                    /* "无"是两个字，字号小一点才不会挤 */
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, if (direction == null) 10f else 14f)
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                })
                if (hasBinding) {
                    cell.addView(View(context).apply {
                        background = rounded(if (selected) Color.WHITE else THEME, 2.5f, context)
                        layoutParams = FrameLayout.LayoutParams(
                            context.dp(5f),
                            context.dp(5f),
                            Gravity.TOP or Gravity.END,
                        ).apply {
                            topMargin = context.dp(3f)
                            rightMargin = context.dp(3f)
                        }
                    })
                }
                row.addView(cell)
            }
            grid.addView(row)
        }
        return grid
    }

    /**
     * 某个组件**已经绑在哪些方向**（显示在复选框后面）。
     *
     * - 没绑 → 空串
     * - 整块区域（方向 null）→ `不限`
     * - 具体方向 → `↑←`（按方向顺序，不按勾选先后）
     *
     * ⚠️ 没有它的话，用户切到"无"时会看到一个未勾选的组件，
     * 以为它没绑 —— 而实际上它绑在"前"上（九宫格角落里的小圆点太小，
     * 具体是哪个组件看不出来）。
     */
    private fun boundMarks(
        bindings: List<Pair<String, TouchDirection?>>,
        componentId: String,
    ): String {
        val hits = bindings.filter { it.first == componentId }
        if (hits.isEmpty()) return ""
        if (hits.any { it.second == null }) return "  不限"
        return "  " + hits.mapNotNull { it.second }.distinct()
            .sortedBy { it.ordinal }
            .joinToString("") { directionArrow(it) }
    }

    /** 两个小胶囊之间的固定间距 */
    private fun spacer(context: Context, widthDp: Float): View =
        View(context).apply {
            layoutParams = LinearLayout.LayoutParams(context.dp(widthDp), 1)
        }

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

package com.something.sthkey.capture.touch

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.something.sthkey.capture.accessibility.OverlayWindowHost
import com.something.sthkey.core.log.AppLog
import com.something.sthkey.core.prefs.AppPrefs
import com.something.sthkey.data.config.ConfigStore
import com.something.sthkey.domain.config.TouchRegion
import com.something.sthkey.domain.config.TouchRegionSpec
import com.something.sthkey.domain.config.TouchSettings
import com.something.sthkey.domain.custom.CustomComponent
import com.something.sthkey.domain.custom.CustomTouchRegion
import com.something.sthkey.domain.custom.CustomTouchRegionBinding
import com.something.sthkey.domain.custom.JoystickComponent
import com.something.sthkey.domain.custom.JoystickSource
import com.something.sthkey.domain.custom.KeyComponent
import com.something.sthkey.domain.custom.MediaComponent
import com.something.sthkey.domain.custom.StickSide
import com.something.sthkey.domain.custom.TextComponent
import com.something.sthkey.domain.custom.TouchDirection
import com.something.sthkey.domain.custom.displayName
import com.something.sthkey.domain.custom.touchRegionLabel
import com.something.sthkey.domain.style.StyleId
import com.something.sthkey.domain.style.touchEffectiveRegions
import com.something.sthkey.ui.overlay.touch.TouchEditorPanel
import com.something.sthkey.ui.overlay.touch.TouchRegionContainerView

/**
 * 触摸区域编辑器（单容器 + 可展开胶囊）。
 *
 * ============================================================
 * 结构（用户定的方案）
 * ============================================================
 * | 窗口 | 何时存在 | 负责 |
 * |---|---|---|
 * | 胶囊面板 | **始终存在**，可拖动 | 展开/收起、尺寸滑块、保存退出/不保存退出 |
 * | 方块层 | **展开时才铺上去** | 展示并拖动触摸区域 |
 *
 * ⚠️ 收起只撤掉方块层，**不丢数据**:所有改动都留在内存里的 [pendingItems]，
 * 再展开时原样铺回来。只有"保存退出 / 不保存退出"才真正结束这次编辑。
 *
 * ⚠️ 两个窗口都是**独立小窗口**，不做"全屏透明层"，否则会吃掉游戏点击。
 */
class TouchRegionContainerService : Service() {

    private var windowManager: WindowManager? = null

    /** 胶囊窗口 */
    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null

    /** 方块层（展开时才有） */
    private var containerView: TouchRegionContainerView? = null

    private var editingConfigId: String? = null

    /** 当前编辑的样式 id（决定区域从哪读、存回哪） */
    private var editingStyleId: String = ""

    /** 当前编辑中的区域（**唯一真源**；收起也不丢） */
    private var pendingItems: List<TouchRegionContainerView.Item> = emptyList()

    /** 打开时的原始值（保存时只写"变了的"） */
    private val snapshot = linkedMapOf<String, TouchRegionContainerView.Item>()

    private var expanded = false

    /* ============================================================ 绑定页状态 */

    /**
     * 这份配置里的**全部组件**（打开编辑器时抄一份）。
     *
     * 用途只有两个:绑定页的候选列表、区域标题里的组件名。
     * ⚠️ 编辑触摸区域期间用户不可能去改组件（那是另一个页面），
     * 所以抄一份不会过期；每次重建面板都去 `ConfigStore` 读一遍反而更慢。
     */
    private var editingComponents: List<CustomComponent> = emptyList()

    /**
     * 正在编辑绑定的区域 id（`null` = 正常列表页）。
     *
     * ⚠️ 绑定页的状态放在**服务**里、而不是面板 View 里:
     * 面板每次改动都会被整个重建（`showPanel`），放在 View 里一重建就丢。
     */
    private var bindingRegionId: String? = null

    /** 绑定页当前选中的方向（`null` = 九宫格中间那个「无」） */
    private var bindingDirection: TouchDirection? = null

    /**
     * 绑定页的**草稿**。
     *
     * ⚠️ 确认之前绝不写进 [pendingItems] —— 用户点「取消」应该什么都没发生，
     * 与"删除是内存里删、保存才落盘"是同一套语义（方案 §5）。
     */
    private var bindingDraft: List<Pair<String, TouchDirection?>> = emptyList()

    /**
     * 绑定页组件列表的滚动位置。
     *
     * ⚠️ 勾一个组件就会重建整个面板（见 `showPanel`），不抄这个值的话
     * 列表每勾一次跳回顶部 —— 组件多的时候要反复往下滚。
     */
    private var bindingScrollY: Int = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = OverlayWindowHost.windowManagerOrNull()
            ?: getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val explicitId = intent?.getStringExtra(EXTRA_CONFIG_ID)
        val configId = explicitId?.takeIf { it.isNotBlank() }
            ?: ConfigStore.get(this).all().firstOrNull { it.styleId == StyleId.TOUCHING }?.id
        if (configId.isNullOrBlank()) {
            AppLog.w(TAG, "没有任何触屏样式的配置，无法编辑")
            stopSelf()
            return START_NOT_STICKY
        }

        val config = ConfigStore.get(this).find(configId)
        if (config == null) {
            AppLog.w(TAG, "配置不存在（$configId）")
            stopSelf()
            return START_NOT_STICKY
        }
        if (config.styleId != StyleId.TOUCHING && config.styleId != StyleId.CUSTOM_KEY) {
            AppLog.w(TAG, "暂只支持触屏与自定义 Key 样式（当前 ${config.styleId}）")
            stopSelf()
            return START_NOT_STICKY
        }

        editingConfigId = configId
        activeConfigId = configId
        editingStyleId = config.styleId
        editingComponents = config.custom.components
        /* ⚠️ 重新进入编辑器时绑定页必须关着（上一次的草稿不能带过来） */
        bindingRegionId = null
        bindingDirection = null
        bindingDraft = emptyList()
        pendingItems = buildItems(config)
        snapshot.clear()
        pendingItems.forEach { snapshot[it.id] = it }

        showPanel()
        return START_NOT_STICKY
    }

    /* ============================================================ 胶囊 */

    /** @param animateExpand 是否播放"展开高度"动画（收起时不需要） */
    private fun showPanel() {
        /*
         * ⚠️ 重建面板前先把方块层里的最新位置抄回来。
         *
         * 用户可能刚拖过方块，那些位置**只存在于方块层**
         * （见 [commitAndExit] 里同一个坑的说明）；不抄的话，
         * 这次重建（点「绑定」、勾组件…）会把面板与 [pendingItems]
         * 一起退回拖动前的坐标，用户看到的是"方块自己跳回去了"。
         */
        syncFromContainer()

        /*
         * ⚠️ 面板马上要被 remove + 重建 —— 先把绑定页的滚动位置抄下来。
         * 标记打在 `TouchEditorPanel` 的 ScrollView 上（见 BINDING_SCROLL_TAG）。
         */
        (panelView?.findViewWithTag<View>(TouchEditorPanel.BINDING_SCROLL_TAG) as? android.widget.ScrollView)
            ?.let { bindingScrollY = it.scrollY }

        removePanel()
        val wm = windowManager ?: return

        /* 只有自定义 Key 需要"序号 / 色点"（触屏样式的槽位名本来就唯一） */
        val allowEdit = editingStyleId == StyleId.CUSTOM_KEY
        val panel = TouchEditorPanel.build(
            context = this,
            rows = pendingItems.mapIndexed { index, item ->
                TouchEditorPanel.Row(
                    id = item.id,
                    label = item.label,
                    size = item.size,
                    bindings = item.bindings,
                    /* ⚠️ 1 起;与屏幕方块的下标是同一个（`items` 的顺序） */
                    ordinal = if (allowEdit) index + 1 else null,
                )
            },
            expanded = expanded,
            onToggle = { setExpanded(!expanded) },
            onSizeChange = { id, size -> applySize(id, size) },
            onSave = { commitAndExit() },
            onDiscard = {
                AppLog.i(TAG, "不保存退出")
                shutdown()
            },
            onDragStart = { x, y -> startPanelDrag(x, y) },
            onDragMove = { x, y -> movePanel(x, y) },
            onDragEnd = { persistPanelPosition() },
            /* 只有自定义 Key 能加/删区域（触屏样式的区域由槽位决定） */
            allowEdit = allowEdit,
            onAddRegion = { stick -> addRegion(stick) },
            onDeleteRegion = { id -> deleteRegion(id) },
            onBindRegion = { id -> openBinding(id) },
            binding = if (editingStyleId == StyleId.CUSTOM_KEY) currentBindingPage() else null,
            onBindingDirection = { direction -> setBindingDirection(direction) },
            onBindingToggle = { componentId -> toggleBinding(componentId) },
            onBindingConfirm = { confirmBinding() },
            onBindingCancel = { closeBindingPage() },
        )

        /*
         * ⚠️⚠️ **窗口必须给固定宽度**:
         * 以前用 WRAP_CONTENT，window 的 LayoutParams 会**覆盖**根布局的 300dp，
         * 于是收起/展开时宽度随内容变化（用户反馈"展开后短一截"）。
         *
         * ⚠️ 高度用 WRAP_CONTENT 会导致**每帧 relayout 重新测量**（SeekBar 很贵），
         * 这正是拖动只有 ~30fps 的原因。这里改成"量一次、写死高度"。
         */
        val panelWidth = (PANEL_WIDTH_DP * resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            panelWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            OverlayWindowHost.windowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val pos = panelPosition()
            x = pos.first
            y = pos.second
        }

        if (runCatching { wm.addView(panel, params) }.isFailure) {
            AppLog.w(TAG, "胶囊添加失败")
            return
        }
        panelView = panel
        panelParams = params

        /* ⚠️ 量一次真实高度后**写死**:之后拖动只改 x/y，不再触发重新测量 */
        panel.post {
            val measured = panel.height
            if (measured > 0) {
                params.height = measured
                runCatching { windowManager?.updateViewLayout(panel, params) }
            }
        }
    }

    private fun removePanel() {
        val v = panelView ?: return
        panelView = null
        panelParams = null
        runCatching { windowManager?.removeView(v) }
    }

    /*
     * 拖动胶囊 —— **绝对定位**。
     *
     * ⚠️⚠️ 之前用"增量累加"（`params.x += dx`），只要有一帧被丢掉 / 被夹取，
     * 误差就永久留在里面，手感变成"手指走在前面、胶囊慢慢跟"（用户反馈）。
     *
     * 现在:
     * - DOWN 时记下**按下点**与**当时的窗口坐标**;
     * - MOVE 时只存**手指绝对坐标**，每帧算 `起始窗口坐标 + (手指 - 按下点)`。
     *
     * 这样误差不会累积：手指在哪，窗口就在哪。
     */
    private var dragDownRawX = 0f
    private var dragDownRawY = 0f
    private var dragStartWinX = 0
    private var dragStartWinY = 0
    private var dragRawX = 0f
    private var dragRawY = 0f
    private var dragFrameScheduled = false

    /* 诊断:事件数 / 帧数 / 位移，松手时打一条 */
    private var dragEventCount = 0
    private var dragFrameCount = 0
    private var dragStartMs = 0L

    private fun startPanelDrag(rawX: Float, rawY: Float) {
        val params = panelParams ?: return
        dragDownRawX = rawX
        dragDownRawY = rawY
        dragRawX = rawX
        dragRawY = rawY
        dragStartWinX = params.x
        dragStartWinY = params.y
        dragStartMs = android.os.SystemClock.elapsedRealtime()
        dragEventCount = 0
        dragFrameCount = 0
    }

    private fun movePanel(rawX: Float, rawY: Float) {
        dragRawX = rawX
        dragRawY = rawY
        dragEventCount++
        if (!dragFrameScheduled) {
            dragFrameScheduled = true
            android.view.Choreographer.getInstance().postFrameCallback {
                dragFrameScheduled = false
                applyDrag()
            }
        }
    }

    private fun applyDrag() {
        val params = panelParams ?: return
        val metrics = resources.displayMetrics
        val w = panelView?.width ?: 0
        val h = panelView?.height ?: 0
        val targetX = dragStartWinX + (dragRawX - dragDownRawX).toInt()
        val targetY = dragStartWinY + (dragRawY - dragDownRawY).toInt()
        params.x = targetX.coerceIn(0, (metrics.widthPixels - w).coerceAtLeast(0))
        params.y = targetY.coerceIn(0, (metrics.heightPixels - h).coerceAtLeast(0))
        /* ⚠️ 不要在这里写偏好:每帧落盘会卡主线程 —— 拖动结束再由 persistPanelPosition() 存 */
        runCatching { windowManager?.updateViewLayout(panelView, params) }
        dragFrameCount++
    }

    /** 把胶囊当前位置写进偏好（**只在拖动结束时调用**），并打一条诊断日志 */
    private fun persistPanelPosition() {
        val params = panelParams ?: return
        /* 松手时补最后一帧，避免"差一点到位" */
        applyDrag()
        AppPrefs.get(this).touchPanelX = params.x
        AppPrefs.get(this).touchPanelY = params.y

    }

    private fun panelPosition(): Pair<Int, Int> {
        val prefs = AppPrefs.get(this)
        val metrics = resources.displayMetrics
        val fallbackX = ((metrics.widthPixels - 200 * metrics.density) / 2).toInt().coerceAtLeast(0)
        val fallbackY = (metrics.heightPixels * 0.16f).toInt()
        val x = prefs.touchPanelX.takeIf { it >= 0 } ?: fallbackX
        val y = prefs.touchPanelY.takeIf { it >= 0 } ?: fallbackY
        return x.coerceIn(0, metrics.widthPixels) to y.coerceIn(0, metrics.heightPixels)
    }

    /* ============================================================ 展开 / 收起 */

    /**
     * 展开 = 铺上方块层；收起 = 撤掉方块层。
     *
     * ⚠️ 无论哪种，[pendingItems] 都保留 —— 收起不等于放弃改动。
     */
    private fun setExpanded(value: Boolean) {
        expanded = value
        if (value) {
            showContainer()
        } else {
            removeContainer()
        }
        /* 头部箭头与展开体要跟着变 → 重建胶囊（内容很少，代价可接受） */
        showPanel()
    }

    private fun showContainer() {
        if (containerView != null) return
        val wm = windowManager ?: return
        val metrics = resources.displayMetrics

        val container = TouchRegionContainerView(this).apply {
            screenWidthPx = metrics.widthPixels
            screenHeightPx = metrics.heightPixels
            items = pendingItems
            showButtons = false
            /* ⚠️ 与面板行的序号同一套下标 —— 自定义 Key 才画 */
            showOrdinals = editingStyleId == StyleId.CUSTOM_KEY
            hint = ""
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            OverlayWindowHost.windowType(),
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }

        if (runCatching { wm.addView(container, params) }.isFailure) {
            AppLog.w(TAG, "方块层添加失败")
            return
        }
        containerView = container
    }

    private fun removeContainer() {
        val v = containerView ?: return
        /* 收起前把拖动过的位置抄回 pendingItems，避免丢改动 */
        pendingItems = v.items.map { it }
        containerView = null
        runCatching { windowManager?.removeView(v) }
    }

    /* ============================================================ 数据 */

    /**
     * 把方块层里的最新数据抄回 [pendingItems]。
     *
     * ⚠️ 拖动只改方块层，`pendingItems` 是"上一次重建面板时"的快照。
     * 任何"要拿全部区域做点什么"的地方（重建面板、保存、绑定）都得先抄一遍，
     * 否则会把用户刚拖的位置**覆盖回原坐标**（用户实测过的 bug）。
     */
    private fun syncFromContainer() {
        containerView?.let { view -> pendingItems = view.items.toList() }
    }

    /** 改一块区域（只改 [pendingItems] 与方块层，不动配置） */
    private fun updateItem(id: String, transform: (TouchRegionContainerView.Item) -> TouchRegionContainerView.Item) {
        val base = containerView?.items ?: pendingItems
        pendingItems = base.map { item -> if (item.id == id) transform(item) else item }
        containerView?.items = pendingItems
    }

    /**
     * 新建一块区域（**只有自定义 Key**）。
     *
     * ⚠️ 位置放到屏幕偏下中央、用默认尺寸 —— 先让用户看得到，再自己拖。
     * ⚠️ 新区域**没有绑定**，标题里直接写"未绑定 ⚠"（红字），
     * 别让用户以为"加出来就能用"。
     */
    private fun addRegion(stick: Boolean) {
        /*
         * ⚠️ 先抄回方块层的拖动结果:否则 `pendingItems` 还是上次重建面板时的
         * 旧坐标，加一块新区域会把**其它区域刚拖好的位置**一起退回去。
         */
        syncFromContainer()
        val kind = if (stick) CustomTouchRegion.Kind.MOVE_STICK else CustomTouchRegion.Kind.BUTTON
        val item = TouchRegionContainerView.Item(
            id = "region_" + System.currentTimeMillis(),
            label = touchRegionLabel(kind, emptyList(), editingComponents),
            centerX = 0.5f,
            centerY = 0.62f,
            size = TouchRegionSpec.SIZE_DEFAULT,
            kind = kind,
        )
        pendingItems = pendingItems + item
        containerView?.items = pendingItems
        showPanel()
        android.widget.Toast.makeText(this, "已添加${kind.label}（未绑定组件）", android.widget.Toast.LENGTH_SHORT).show()
    }

    /** 删除一块区域（**内存里删**，保存时才落盘） */
    private fun deleteRegion(id: String) {
        /* ⚠️ 与 [addRegion] 同一个理由:先抄回拖动结果，别让删除把别人挪回去 */
        syncFromContainer()
        /* ⚠️ 删的正好是绑定页那块 → 绑定页跟着关，否则会画一块不存在的区域 */
        if (bindingRegionId == id) {
            bindingRegionId = null
            bindingDirection = null
            bindingDraft = emptyList()
        }
        pendingItems = pendingItems.filterNot { it.id == id }
        containerView?.items = pendingItems
        showPanel()
    }

    /**
     * 改某个区域的尺寸。
     *
     * ⚠️⚠️ 基准必须优先取**方块层当前的值**（`containerView.items`）:
     * 用户可能刚拖过方块，那些位置只存在于方块层里;
     * 若拿 `pendingItems`（展开时的快照）去 map，拖动结果会被**覆盖回原坐标**（用户实测的 bug）。
     */
    private fun applySize(id: String, size: Float) {
        val base = containerView?.items ?: pendingItems
        pendingItems = base.map { item ->
            if (item.id == id) item.copy(size = size) else item
        }
        containerView?.items = pendingItems
    }

    /* ============================================================ 绑定（C3） */

    /** 打开某块区域的绑定页；参数是区域 id */
    private fun openBinding(id: String) {
        if (editingStyleId != StyleId.CUSTOM_KEY) return
        val item = pendingItems.firstOrNull { it.id == id } ?: return
        val kind = item.kind ?: CustomTouchRegion.Kind.BUTTON

        /* ⚠️ 没有可绑组件时别开一个空页面 —— 直接说清楚为什么 */
        if (bindingCandidates(kind).isEmpty()) {
            android.widget.Toast.makeText(this, "这个配置还没有可绑定的组件", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        bindingRegionId = id
        /* ⚠️ 默认停在「无」:它是"整块区域"，也是用户新加区域后的状态 */
        bindingDirection = null
        bindingDraft = item.bindings
        bindingScrollY = 0
        showPanel()
    }

    /** 关掉绑定页（取消 / 确定后都走它） */
    private fun closeBindingPage() {
        bindingRegionId = null
        bindingDirection = null
        bindingDraft = emptyList()
        bindingScrollY = 0
        showPanel()
    }

    /** 把服务端状态拼成面板要的形状；没在绑定就返回 null */
    private fun currentBindingPage(): TouchEditorPanel.BindingPage? {
        val id = bindingRegionId ?: return null
        val item = pendingItems.firstOrNull { it.id == id }
        if (item == null) {
            /* 区域被删了（理论上 deleteRegion 已经处理）—— 兜一下，别画出空页 */
            bindingRegionId = null
            return null
        }
        val kind = item.kind ?: CustomTouchRegion.Kind.BUTTON
        /* ⚠️ 面板行上的序号:标题里带上它，几块区域同名时用户知道在改哪块 */
        val ordinal = pendingItems.indexOfFirst { it.id == id }.let { if (it >= 0) it + 1 else null }
        return TouchEditorPanel.BindingPage(
            kind = kind,
            direction = bindingDirection,
            candidates = bindingCandidates(kind),
            bindings = bindingDraft,
            scrollY = bindingScrollY,
            ordinal = ordinal,
        )
    }

    /**
     * 一块区域**能绑哪些组件**。
     *
     * ============================================================
     * ⚠️ 比"全部组件"窄，这是刻意的
     * ============================================================
     * | 区域 | 可绑 | 为什么 |
     * |---|---|---|
     * | 按键区域 | 按键 / 文本 | 摇杆没有"按下"这回事;媒体组件也不会按下 |
     * | 轮盘区域 | 按键 / 文本 / **触屏移动摇杆** | 摇杆要"推"它;手柄/键盘/鼠标摇杆跟的是各自的输入源，绑区域**没有任何效果** |
     *
     * ⚠️ 列出来"选了没效果"的组件，比不列更糟 —— 用户会以为配了，
     * 然后反复试"为什么不动"（方案 §3 对摇杆的要求也是"默认过滤"）。
     */
    private fun bindingCandidates(kind: CustomTouchRegion.Kind): List<TouchEditorPanel.BindingCandidate> =
        editingComponents.mapNotNull { component ->
            val allowed = when (kind) {
                CustomTouchRegion.Kind.BUTTON ->
                    component is KeyComponent || component is TextComponent

                CustomTouchRegion.Kind.MOVE_STICK -> when (component) {
                    is KeyComponent, is TextComponent -> true
                    /* ⚠️ 只有"触屏移动摇杆"吃区域（视角摇杆吃全屏，见 JoystickSource.TOUCH） */
                    is JoystickComponent ->
                        component.source == JoystickSource.TOUCH && component.side == StickSide.LEFT
                    is MediaComponent -> false
                }
            }
            if (!allowed) {
                null
            } else {
                TouchEditorPanel.BindingCandidate(
                    id = component.id,
                    name = component.displayName(),
                    isJoystick = component is JoystickComponent,
                )
            }
        }

    /** 绑定页里点了一个方向（`null` = 中间那个「无」） */
    private fun setBindingDirection(direction: TouchDirection?) {
        if (bindingRegionId == null) return
        bindingDirection = direction
        showPanel()
    }

    /**
     * 勾 / 取消一个组件。
     *
     * ⚠️ 当前选中的方向决定"勾出来的是哪一条绑定":
     * 九宫格停在「无」时勾的是"整块区域"，停在「前」时勾的是"前方向"。
     *
     * ⚠️ "整块"与"具体方向"**互斥**（对同一个组件）:
     * 两者同时存在时，"整块"会把方向那条**完全盖住**（按到就触发），
     * 用户看到摘要里两条绑定却只有一条生效，只会以为是 bug。
     */
    private fun toggleBinding(componentId: String) {
        val page = currentBindingPage() ?: return
        val candidate = page.candidates.firstOrNull { it.id == componentId } ?: return
        /* ⚠️ 摇杆不吃方向 —— 强行归成 null（与保存时的规整同一个规则） */
        val direction = if (candidate.isJoystick) null else bindingDirection

        val active = bindingDraft.any { it.first == componentId && it.second == direction }
        bindingDraft = if (active) {
            bindingDraft.filterNot { it.first == componentId && it.second == direction }
        } else {
            val base = if (direction == null) {
                /* 选了「整块」→ 之前那些方向绑定全部作废 */
                bindingDraft.filterNot { it.first == componentId }
            } else {
                /* 选了具体方向 → 去掉"整块"那条（它会让方向失去意义） */
                bindingDraft.filterNot { it.first == componentId && it.second == null }
            }
            base + (componentId to direction)
        }
        showPanel()
    }

    /**
     * 点「确定」:把草稿写进 [pendingItems]（**还不落盘**）。
     *
     * ⚠️ 顺序按组件列表重排、去重，并**丢掉失效绑定**（组件已经删了）——
     * 那种绑定在绑定页里没有对应的复选框，用户没法取消它，
     * 只能在这里顺手清掉（"点确定 = 以当前可见的勾选为准"）。
     *
     * ⚠️ 仍要等「保存退出」才写配置:与拖动/改尺寸同一个语义，
     * 「不保存退出」会把绑定一起丢掉。
     */
    private fun confirmBinding() {
        val id = bindingRegionId ?: return
        val item = pendingItems.firstOrNull { it.id == id } ?: run {
            closeBindingPage()
            return
        }
        val kind = item.kind ?: CustomTouchRegion.Kind.BUTTON
        val allowed = bindingCandidates(kind).associateBy { it.id }

        val normalized = buildList {
            for (component in editingComponents) {
                val candidate = allowed[component.id] ?: continue
                val hits = bindingDraft.filter { it.first == component.id }
                /* ⚠️ 没勾的组件**不要**顺手加一条 —— 见下面摇杆那条说明 */
                if (hits.isEmpty()) continue

                if (candidate.isJoystick) {
                    /*
                     * ⚠️ 摇杆最多一条、且不带方向。
                     * ⚠️ 这里必须在 `hits.isEmpty()` 之后判断:否则每个
                     * **没勾选**的摇杆也会被塞一条 `(id, null)` ——
                     * 用户只是打开看了一眼绑定页、点确定，就凭空多出绑定。
                     */
                    add(component.id to null)
                    continue
                }

                hits.map { it.second }
                    .distinct()
                    /* null（整块）排最前，其余按方向顺序 */
                    .sortedBy { it?.ordinal ?: -1 }
                    .forEach { direction -> add(component.id to direction) }
            }
        }

        updateItem(id) { old ->
            old.copy(
                bindings = normalized,
                label = touchRegionLabel(kind, normalized, editingComponents),
            )
        }
        closeBindingPage()
    }

    /** 保存"变了的"区域并退出 */
    private fun commitAndExit() {
        /* 方块层还开着时，先把它的最新位置抄回来 */
        containerView?.let { pendingItems = it.items.map { item -> item } }

        val changed = pendingItems.filter { item ->
            val before = snapshot[item.id] ?: return@filter true
            before.centerX != item.centerX ||
                before.centerY != item.centerY ||
                before.size != item.size ||
                /*
                 * ⚠️ **绑定也算改动**（C3）。
                 * 只比几何的话，"只改绑定、没动位置"会走进下面那句
                 * "没有改动，直接退出" —— 用户点了确定、看到了摘要更新，
                 * 保存退出后却什么都没写进去。那是最难查的一类"改了没用"。
                 */
                before.bindings != item.bindings ||
                before.kind != item.kind
        }

        /*
         * ⚠️⚠️ **删除也要算改动**。
         *
         * 只看 `pendingItems`（剩下的区域）的话，"只删了一块、别的都没动"
         * 会让 `changed` 为空 → 走进"没有改动，直接退出" → **删除没被保存**。
         * 用户看到的是"删掉的区域重开又回来了"，而日志还写着"没有改动"。
         *
         * ⚠️ 判据用 **id 集合的差**:`snapshot` 是打开编辑器时的那一份，
         * 里面少了谁就是用户删了谁。
         */
        val remainingIds = pendingItems.mapTo(mutableSetOf()) { it.id }
        val removedIds = snapshot.keys - remainingIds

        if (changed.isEmpty() && removedIds.isEmpty()) {
            AppLog.i(TAG, "没有改动，直接退出")
            shutdown()
            return
        }

        val id = editingConfigId
        if (id.isNullOrBlank()) {
            shutdown()
            return
        }

        val byId = changed.associateBy { it.id }
        val updated = ConfigStore.get(this).updateById(id) { config ->
            when (editingStyleId) {
                /*
                 * 自定义 Key:区域在 `custom.touchRegions` 里，按 **id** 匹配。
                 *
                 * ⚠️ **按完整列表重建**（C2 起支持新增/删除）:
                 * - 已有区域:id 命中就更新几何与**绑定**（C3）;
                 * - 新区域:用 `Item.kind` 与 `Item.bindings` 建;
                 * - 列表里没有的 = 用户删了，自然消失。
                 */
                StyleId.CUSTOM_KEY -> {
                    val existing = config.custom.touchRegions.associateBy { it.id }
                    val rebuilt = pendingItems.map { item ->
                        /*
                         * ⚠️ 绑定一律以 `Item.bindings` 为准 —— 不能 `old.copy()`
                         * 保留旧绑定:用户在绑定页改完点确定，改的正是它。
                         */
                        val bindings = item.bindings.map { (componentId, direction) ->
                            CustomTouchRegionBinding(componentId = componentId, direction = direction)
                        }
                        val old = existing[item.id]
                        if (old != null) {
                            old.copy(
                                region = old.region.copy(
                                    centerX = item.centerX,
                                    centerY = item.centerY,
                                    size = item.size,
                                ),
                                bindings = bindings,
                            )
                        } else {
                            CustomTouchRegion(
                                id = item.id,
                                kind = item.kind ?: CustomTouchRegion.Kind.BUTTON,
                                region = TouchRegion(
                                    centerX = item.centerX,
                                    centerY = item.centerY,
                                    size = item.size,
                                ),
                                bindings = bindings,
                            )
                        }
                    }
                    config.copy(custom = config.custom.copy(touchRegions = rebuilt))
                }

                /* 触屏样式:按槽位 id 合并（新槽位允许补进去） */
                else -> {
                    val existing = config.touch.regions.associateBy { it.slotId }
                    val merged = config.touch.regions.map { binding ->
                        val change = byId[binding.slotId] ?: return@map binding
                        binding.copy(centerX = change.centerX, centerY = change.centerY, size = change.size)
                    }
                    val added = changed
                        .filter { it.id !in existing }
                        .map { item ->
                            com.something.sthkey.domain.config.TouchRegionBinding(
                                slotId = item.id,
                                centerX = item.centerX,
                                centerY = item.centerY,
                                size = item.size,
                                /*
                                 * ⚠️⚠️ **必须 pinned = true**（用户踩过的坑）。
                                 *
                                 * `activeRegions` 只认 `pinned == true` 的区域，
                                 * 而 `pinned` 默认是 **false** —— 新建时漏了它，
                                 * 区域会被写进配置却**不生效**:
                                 * 表现就是"移动摇杆突然没反应"（用户与测试用户都遇到）。
                                 */
                                pinned = true,
                            )
                        }
                    config.copy(touch = config.touch.copy(regions = merged + added))
                }
            }
        }

        AppLog.i(
            TAG,
            "已保存 ${changed.size} 个区域、删除 ${removedIds.size} 个（配置=${updated?.name ?: id}）",
        )

        /*
         * ⚠️⚠️ **让正在显示的悬浮窗立刻用上新值**（用户反馈的老 bug）:
         * 之前保存完要"关掉悬浮窗再重开"才生效，用户会以为"改了没用"。
         *
         * [OverlayService.refresh] 会重走一次 `onStartCommand` → 重新读配置 →
         * 组合层拿到新的 `touch.regions`，触摸区域立即按新坐标生效。
         */
        runCatching { com.something.sthkey.capture.OverlayService.refresh(this) }
            .onFailure { AppLog.w(TAG, "请求悬浮窗刷新失败:${it.javaClass.simpleName}") }

        /* ⚠️ 必须给反馈:在游戏里保存完，界面上什么提示都没有会很怪 */
        android.widget.Toast.makeText(this, "触摸区域已保存", android.widget.Toast.LENGTH_SHORT).show()

        shutdown()
    }

    private fun shutdown() {
        activeConfigId = null
        removeContainer()
        removePanel()
        stopSelf()
    }

    override fun onDestroy() {
        /*
         * ⚠️⚠️ **必须在这里也清掉 activeConfigId**。
         *
         * 只清在 [shutdown] 里是不够的:从配置页"再按一次关闭"走的是
         * `stopService` → `onDestroy`，**根本不经过 shutdown()**。
         * 于是 `activeConfigId` 残留 → 下次点按钮 `refuseReason` 返回
         * ALREADY_OPEN → 调用方又去 stop → 永远打不开（用户反馈的"悬浮窗死了"）。
         */
        activeConfigId = null
        removeContainer()
        removePanel()
        super.onDestroy()
    }

    /* ============================================================ 区域构造 */

    /**
     * 按**样式**构造待绘制的区域列表。
     *
     * | 样式 | 区域来源 |
     * |---|---|
     * | 触屏 | **生效区域**（配过的 + 没配过的按默认坐标补齐），见 `touchEffectiveRegions` |
     * | 自定义 Key | `custom.touchRegions`（用户自己加的） |
     */
    private fun buildItems(
        config: com.something.sthkey.domain.config.KeyStrokesConfig,
    ): List<TouchRegionContainerView.Item> = when (config.styleId) {
        StyleId.CUSTOM_KEY -> config.custom.touchRegions.map { region ->
            /*
             * ⚠️ 面板与屏幕方块显示**同一句文案**（`touchRegionLabel`）——
             * 两处各拼一份的话，改了一处另一处就会对不上。
             */
            val bindings = region.bindings.map { it.componentId to it.direction }
            TouchRegionContainerView.Item(
                id = region.id,
                label = touchRegionLabel(region.kind, bindings, config.custom.components),
                kind = region.kind,
                centerX = region.region.centerX,
                centerY = region.region.centerY,
                size = region.region.size,
                bindings = bindings,
            )
        }

        else -> buildTouchStyleItems(config)
    }

    /**
     * 触屏样式的待编辑区域 —— 直接取**生效区域**。
     *
     * ⚠️ 不再自己写一遍"轮盘 + 按键默认坐标":那套规则现在在
     * `touchEffectiveRegions` 里，悬浮窗也用它。
     * 两处各写一份的话，编辑器里拖好的位置与悬浮窗实际生效的位置
     * 迟早会漂开 —— 而那种"编辑器里明明拖上去了、游戏里却不灵"极难查。
     */
    private fun buildTouchStyleItems(
        config: com.something.sthkey.domain.config.KeyStrokesConfig,
    ): List<TouchRegionContainerView.Item> =
        touchEffectiveRegions(config).map { binding ->
            TouchRegionContainerView.Item(
                id = binding.slotId,
                /* 只有轮盘有名字，其余按键直接显示槽位（LMB / RMB / SPACE / SHIFT） */
                label = if (binding.slotId == TouchSettings.JOYSTICK_SLOT_ID) "轮盘" else binding.slotId,
                centerX = binding.centerX,
                centerY = binding.centerY,
                size = binding.size,
            )
        }

    companion object {
        /** 正在编辑的配置 id（null = 没开）—— 用来实现"再按一次关闭" */
        @Volatile
        private var activeConfigId: String? = null

        /** 与旧编辑器同名的常量:同一个配置再按一次 = 关闭 */
        const val ALREADY_OPEN = "__already_open__"

        /**
         * 能不能打开编辑器；不能时返回给用户看的原因。
         *
         * ⚠️ 同一份配置已在编辑 → 返回 [ALREADY_OPEN]（调用方据此"关闭"）。
         * ⚠️ 另一份配置正在编辑 → 返回原因，避免两份互相覆盖。
         */
        fun refuseReason(configId: String): String? {
            val active = activeConfigId ?: return null
            if (active == configId) return ALREADY_OPEN
            return "另一份配置正在编辑触摸区域，请先退出那一份"
        }
        private const val TAG = "TouchContainer"

        /** 胶囊窗口宽度（dp）—— 收起与展开**必须一致** */
        private const val PANEL_WIDTH_DP = 190f

        const val EXTRA_CONFIG_ID = "config_id"

        fun start(context: Context, configId: String?) {
            val intent = Intent(context, TouchRegionContainerService::class.java)
            if (configId != null) intent.putExtra(EXTRA_CONFIG_ID, configId)
            context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TouchRegionContainerService::class.java))
        }
    }
}

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
import com.something.sthkey.domain.config.TouchRegionSpec
import com.something.sthkey.domain.config.TouchSettings
import com.something.sthkey.domain.style.KeyLayout
import com.something.sthkey.domain.style.StyleId
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
        pendingItems = buildItems(config)
        snapshot.clear()
        pendingItems.forEach { snapshot[it.id] = it }

        showPanel()
        return START_NOT_STICKY
    }

    /* ============================================================ 胶囊 */

    /** @param animateExpand 是否播放"展开高度"动画（收起时不需要） */
    private fun showPanel() {
        removePanel()
        val wm = windowManager ?: return

        val panel = TouchEditorPanel.build(
            context = this,
            rows = pendingItems.map { TouchEditorPanel.Row(it.id, it.label, it.size) },
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

    /** 保存"变了的"区域并退出 */
    private fun commitAndExit() {
        /* 方块层还开着时，先把它的最新位置抄回来 */
        containerView?.let { pendingItems = it.items.map { item -> item } }

        val changed = pendingItems.filter { item ->
            val before = snapshot[item.id] ?: return@filter true
            before.centerX != item.centerX ||
                before.centerY != item.centerY ||
                before.size != item.size
        }

        if (changed.isEmpty()) {
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
                 * ⚠️ 这里**只更新已有区域的位置与大小** —— 新增/删除/绑定是 C2/C3 的事，
                 * 现在绝不能凭空往配置里塞区域。
                 */
                StyleId.CUSTOM_KEY -> {
                    /*
                     * ⚠️ **按完整列表重建**（C2 起支持新增/删除）:
                     * - 已有区域:id 命中就只更新几何，**绑定原样保留**;
                     * - 新区域:用 `Item.kind` 建一个空绑定的区域;
                     * - 列表里没有的 = 用户删了，自然消失。
                     */
                    val existing = config.custom.touchRegions.associateBy { it.id }
                    val rebuilt = pendingItems.map { item ->
                        val old = existing[item.id]
                        if (old != null) {
                            old.copy(
                                region = old.region.copy(
                                    centerX = item.centerX,
                                    centerY = item.centerY,
                                    size = item.size,
                                ),
                            )
                        } else {
                            com.something.sthkey.domain.custom.CustomTouchRegion(
                                id = item.id,
                                kind = item.kind
                                    ?: com.something.sthkey.domain.custom.CustomTouchRegion.Kind.BUTTON,
                                region = com.something.sthkey.domain.config.TouchRegion(
                                    centerX = item.centerX,
                                    centerY = item.centerY,
                                    size = item.size,
                                ),
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

        AppLog.i(TAG, "已保存 ${changed.size} 个区域（配置=${updated?.name ?: id}）")

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
     * | 触屏 | 槽位固定（轮盘 + 键盘槽位），配置里没存过的用默认位置占位 |
     * | 自定义 Key | `custom.touchRegions`（用户自己加的） |
     */
    private fun buildItems(
        config: com.something.sthkey.domain.config.KeyStrokesConfig,
    ): List<TouchRegionContainerView.Item> = when (config.styleId) {
        StyleId.CUSTOM_KEY -> config.custom.touchRegions.map { region ->
            TouchRegionContainerView.Item(
                id = region.id,
                /* ⚠️ 标签用"类型"，绑定摘要留到 C3（绑定 UI 落地后一起显示） */
                label = region.kind.label,
                kind = region.kind,
                centerX = region.region.centerX,
                centerY = region.region.centerY,
                size = region.region.size,
            )
        }

        else -> buildTouchStyleItems(
            config.touch.regions.associateBy { it.slotId },
            config,
        )
    }

    private fun buildTouchStyleItems(
        saved: Map<String, com.something.sthkey.domain.config.TouchRegionBinding>,
        config: com.something.sthkey.domain.config.KeyStrokesConfig,
    ): List<TouchRegionContainerView.Item> = buildList {
        add(
            TouchRegionContainerView.Item(
                id = TouchSettings.JOYSTICK_SLOT_ID,
                label = "轮盘",
                centerX = saved[TouchSettings.JOYSTICK_SLOT_ID]?.centerX
                    ?: TouchRegionSpec.MOVE_DEFAULT.centerX,
                centerY = saved[TouchSettings.JOYSTICK_SLOT_ID]?.centerY
                    ?: TouchRegionSpec.MOVE_DEFAULT.centerY,
                size = saved[TouchSettings.JOYSTICK_SLOT_ID]?.size
                    ?: TouchRegionSpec.MOVE_DEFAULT.size,
            ),
        )
        KeyLayout.keys(config)
            .map { it.slotId }
            .filter { it != KeyLayout.Id.JOYSTICK_LEFT && it != KeyLayout.Id.JOYSTICK_RIGHT }
            .distinct()
            .forEachIndexed { index, slotId ->
                val region = saved[slotId]
                val default = TouchRegionSpec.defaultCenterFor(index)
                add(
                    TouchRegionContainerView.Item(
                        id = slotId,
                        label = slotId,
                        centerX = region?.centerX ?: default.first,
                        centerY = region?.centerY ?: default.second,
                        size = region?.size ?: TouchRegionSpec.SIZE_DEFAULT,
                    ),
                )
            }
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

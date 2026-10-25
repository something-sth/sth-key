package com.something.sthkey.capture.touch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.Toast
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.something.sthkey.capture.accessibility.OverlayWindowHost
import com.something.sthkey.capture.OverlayBounds
import com.something.sthkey.core.log.AppLog
import com.something.sthkey.data.config.ConfigStore
import com.something.sthkey.domain.config.KeyStrokesConfig
import com.something.sthkey.domain.config.TouchRegion
import com.something.sthkey.domain.config.TouchRegionGeometry
import com.something.sthkey.domain.config.TouchRegionBinding
import com.something.sthkey.domain.config.TouchRegionSpec
import com.something.sthkey.domain.config.TouchSettings
import com.something.sthkey.domain.custom.CustomComponent
import com.something.sthkey.domain.custom.CustomTouchRegion
import com.something.sthkey.domain.custom.CustomTouchRegionBinding
import com.something.sthkey.domain.custom.JoystickComponent
import com.something.sthkey.domain.custom.KeyComponent
import com.something.sthkey.domain.custom.StickSide
import com.something.sthkey.domain.custom.TextComponent
import com.something.sthkey.domain.custom.TouchDirection
import com.something.sthkey.domain.custom.summary
import com.something.sthkey.domain.style.KeyLayout
import com.something.sthkey.domain.style.StyleId
import kotlin.math.roundToInt

/**
 * **触摸区域编辑器**。
 *
 * ============================================================
 * 它长什么样（用户指定的方案）
 * ============================================================
 * | 元素 | 说明 |
 * |---|---|
 * | **触摸区域小窗** | **每个可按键的槽位一个**独立悬浮窗，可拖（改中心）、可缩放（改边长） |
 * | **`touching` 徽标** | 黑底白字小窗，点它**弹出面板**（弹出时徽标隐藏） |
 * | **控制面板** | **屏幕居中**，每个区域一行边长滑块；右上角「✕」、右下角「不保存退出」「保存并退出」 |
 *
 * ============================================================
 * ⚠️⚠️ 为什么每个区域是**独立窗口**（而不是一块全屏遮罩）
 * ============================================================
 * 用户的原话:
 *
 * > axon 现在就是，编辑触摸区域时是**不让动编辑区域外的地方**的，
 * > 就导致需要先进游戏再分屏开 axon 调，我们**不要在调整触摸区域时
 * > 挡住空白地方的触摸**
 *
 * ⚠️ 独立窗口天然满足这一点:**空白处压根没有窗口**，触摸直接落到
 * 下面的应用（游戏）—— 不需要 `FLAG_NOT_TOUCHABLE`、不需要任何特殊处理。
 *
 * ⚠️ 而"一块全屏遮罩 + 挖洞"做不到这件事:遮罩要么收触摸、要么穿透，
 * 两者不可兼得。
 *
 * ============================================================
 * ⚠️ 哪些槽位会有区域（用户选了"打开时全给"）
 * ============================================================
 * 用户的原话:
 *
 * > 你只搞了一个「轮盘」触摸区域，但是实际上**不止轮盘需要配置，
 * > 其他按键同样需要**，参考 axon
 *
 * 而"区域从哪来"他选的是**方案 ①**:打开编辑器就把**所有会显示在
 * 悬浮窗上的槽位**各给一块，拖到位置即可。
 *
 * ⚠️ 摇杆那两个槽位**不给区域**:
 * 左摇杆就是移动轮盘（它自己那块区域由 [TouchSettings.JOYSTICK_SLOT_ID] 表示），
 * 右摇杆是视角摇杆（它吃"所有没落在区域里的触摸"，**按设计没有区域**）。
 *
 * ============================================================
 * ⚠️ 窗口参数照 `OverlayService` 抄
 * ============================================================
 * | 参数 | 取法 | 为什么 |
 * |---|---|---|
 * | `TYPE_APPLICATION_OVERLAY` | 固定 | 唯一能在别的应用之上画、又不需要特殊权限的类型 |
 * | `FLAG_NOT_FOCUSABLE` | 固定 | 不抢输入焦点（否则会顶掉目标应用的键盘） |
 * | `FLAG_HARDWARE_ACCELERATED` | 固定 | 与 `OverlayService` 一致 |
 * | **`FLAG_LAYOUT_NO_LIMITS`** | **加** | 用户明确要求"记得加上" |
 */
class TouchRegionEditorService : Service() {

    /**
     * 一块区域的可视元素。
     */
    private data class RegionSlot(
        /** 绑的槽位 id（[TouchSettings.JOYSTICK_SLOT_ID] 或 `KeyLayout.Id` 里的值） */
        val slotId: String,
        /**
         * **这块区域属于哪个组件**（自定义 Key 样式才有意义）。
         *
         * ============================================================
         * ⚠️ 为什么需要它
         * ============================================================
         * 触屏样式的区域挂在**槽位**上（`config.touch.regions`），
         * 槽位 id 本身就是身份。
         *
         * ⚠️ 而自定义布局里区域挂在**组件的 `touchBindings` / `touchRegion`** 上 ——
         * 保存时必须知道"这块要写回哪个组件"，否则只能猜。
         *
         * ⚠️ 触屏样式下它与 [slotId] **相同**（那里槽位就是归属），
         * 所以触屏那条路完全不受影响。
         */

        /** 显示用的名字（轮盘 / A / LT …） */
        val label: String,
        val color: Int,
        /** 当前区域值（**编辑中的草稿**，点「保存并退出」才写回配置） */
        var region: TouchRegion,
        /**
         * ⚠️ 用户动过没有。
         *
         * ⚠️⚠️ **不持久化这个标记是原来那个"持久化漏洞"的根因**:
         * 「保存并退出」靠它决定"这块要不要写进配置"，而它只活在
         * 编辑器服务的内存里 —— 关掉编辑器就没了，于是**同一块区域
         * 每次打开都从"没动过"开始**（用户明明已经配过）。
         *
         * ⚠️ 现在它只是一个**会话内的**标记:
         * - 打开编辑器时，`slots` 里**只放已经从配置里读出来的区域**（那些必然
         *   是 `pinned` 的），外加用户这次新拖动的；
         * - 保存时**整个 `slots` 就是结果** —— 不再需要"哪些该写"的判断。
         *
         * ⚠️ 而"编辑器一打开就给所有槽位画方块"里的那些**占位方块**
         * 用 [temporary] 区分:它们放在 `placeholders` 里，
         * **不会**被保存，也不参与命中判定。
         */
        var touched: Boolean,
        /**
         * 这一块只是编辑器画出来给用户拖的**占位**吗。
         *
         * ⚠️ `true` = 配置里还没有、用户也还没配 → **保存时不写**。
         * ⚠️ 用户一拖它（或拉它的滑块）就变成 `false`（转正）。
         */
        var temporary: Boolean,
        var box: View,
        var params: WindowManager.LayoutParams,

        /**
         * **自定义 Key 那一支**:这块区域在 `config.custom.touchRegions` 里的 id。
         *
         * ⚠️ 触屏样式那一支恒为 `null`（那边靠 [slotId] 认人）。
         *
         * ⚠️ 新模型下区域是**配置级的一等数据**，所以面板要能:
         * 删除它、给它加/改绑定 —— 那些操作都要靠这个 id 定位。
         */
        val customId: String? = null,

        /**
         * **自定义 Key 那一支**:这块区域的类型（轮盘 / 按键）。
         *
         * ⚠️ 类型决定"能绑什么、绑了之后什么语义"（见 `CustomTouchRegion.Kind`）——
         * 面板要用它决定:显示"已绑 xxx"还是"未绑定"、绑定对话框里给不给方向。
         */
        val customKind: CustomTouchRegion.Kind = CustomTouchRegion.Kind.BUTTON,

        /**
         * **自定义 Key 那一支**:这块区域绑了哪些组件。
         *
         * ⚠️ 面板上"绑定"按钮改的就是它 —— 改完**立刻写回配置**
         * （不像几何那样等"保存并退出"），因为绑定是**数据**、
         * 不依赖屏幕上的位置。
         */
        var customBindings: List<CustomTouchRegionBinding> = emptyList(),
    )

    private lateinit var windowManager: WindowManager
    private val slots = mutableListOf<RegionSlot>()

    private var badge: View? = null
    private var panel: View? = null

    /**
     * 「绑定」那个界面（也是一个悬浮窗，**不是 `AlertDialog`**）。
     *
     * ⚠️ 见 [showBindingPicker] 里那段"一次真实的闪退换来的" ——
     * 本类是 `Service`，没有 Activity 的窗口令牌，用不了 `AlertDialog`。
     */
    private var bindingPicker: View? = null

    /**
     * 打开编辑器那一刻的配置快照 —— 「不保存退出」用它恢复。
     *
     * ⚠️ **触屏样式**用它（恢复 `touch.regions`）。
     */
    private var snapshot: List<TouchRegionBinding> = emptyList()

    /**
     * 打开编辑器那一刻的**自定义区域**快照 —— 「不保存退出」用它恢复。
     *
     * ⚠️ 与 [snapshot] 是两个字段而不是"一个通用快照":两者的类型不同
     * （槽位绑定 vs 自定义区域），合成一个 `Any` 只会把类型判断推到用的时候。
     *
     * ⚠️ 自定义那一支**几何**是草稿（保存才写），但**绑定**是立刻写的
     * （见 [RegionSlot.customBindings]）—— 所以"不保存退出"要把绑定
     * 也一起恢复，否则用户改了绑定又放弃，绑定却留下了。
     */
    private var customSnapshot: List<CustomTouchRegion> = emptyList()

    private var configId: String = ""

    /**
     * 这次编辑的是**自定义 Key** 样式吗（`false` = 触屏样式）。
     *
     * ⚠️ 面板与行的构建发生在好几个函数里（[showPanel]、[buildRegionRow]），
     * 而它们都拿不到 `config` —— 所以在这里存一份。
     *
     * ⚠️ 编辑期间样式**不可能变**（配置页那边"样式建完不能改"），
     * 所以这个值一旦定下就不会过期。
     */
    private var isCustomStyle: Boolean = false

    private lateinit var appContext: Context

    private val density get() = resources.displayMetrics.density

    private fun dp(value: Float): Int = (value * density).roundToInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        /*
         * ============================================================
         * ⚠️⚠️ WindowManager 改从**无障碍服务**借
         * ============================================================
         * 与 `OverlayService` 同一个理由:改用 `TYPE_ACCESSIBILITY_OVERLAY`
         * 之后，这个窗口类型**只能由 `AccessibilityService` 的 context 建**，
         * 用本服务的 context 会抛 `WindowManager$BadTokenException`。
         *
         * ⚠️ 借不到时**先回落到自己的** —— 报错交给 [onStartCommand]
         * （那里有"没拿到配置就退"的结构，能顺便给用户一句准确的提示）。
         * 在这里直接失败的话，连一句能看懂的日志都没有。
         */
        windowManager = OverlayWindowHost.windowManagerOrNull()
            ?: getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_CONFIG_ID).orEmpty()
        if (id.isBlank()) {
            AppLog.w(TAG, "没拿到配置 id，无法编辑")
            stopSelf()
            return START_NOT_STICKY
        }
        if (configId == id && slots.isNotEmpty()) {
            /* 已经在编辑这一份了 —— 不要重建（重建会把用户拖到一半的位置重置） */
            return START_NOT_STICKY
        }

        val config = ConfigStore.get(appContext).find(id)
        if (config == null) {
            AppLog.w(TAG, "配置不存在（$id）")
            stopSelf()
            return START_NOT_STICKY
        }

        /*
         * ⚠️ **自报身份** —— 别的配置想开编辑器时要靠它拦住
         * （见 [refuseReason]）。放在最前面，因为后面任何一条 `return`
         * 都意味着"这次没开成"，那就不该占着身份。
         */
        editingConfigId = id

        /*
         * ============================================================
         * ⚠️⚠️ 按**样式**分流 —— 但**面板是同一套**（用户明确要求）
         * ============================================================
         * | 样式 | 区域存在哪 | 认人靠 |
         * |---|---|---|
         * | [StyleId.TOUCHING] | `config.touch.regions`（**槽位**） | `slotId` |
         * | [StyleId.CUSTOM_KEY] | `config.custom.touchRegions`（**配置级**） | `id` |
         *
         * ⚠️ 用户的原话:
         *
         * > touching 面板按这个思路做，也**不用专门区分触屏样式和自定义 key
         * > 样式的 touching 面板内容**，触屏样式默认的触摸区域就已经绑定好了
         * > 对应的组件
         *
         * ⚠️ "已经绑定好了"是**算出来的** —— 触屏样式的区域 `slotId`
         * 天然对应一个槽位组件（见 [slotLabelOf]）。
         *
         * ⚠️ 而**悬浮窗、拖动、滑块、偏移校正全部共用** ——
         * 那些与"区域存在哪"无关。分流的只有"建哪些方块"与"写回哪里"。
         */
        val isTouchStyle = config.styleId == StyleId.TOUCHING
        val isCustomStyle = config.styleId == StyleId.CUSTOM_KEY
        if (!isTouchStyle && !isCustomStyle) {
            AppLog.w(TAG, "这个样式（${config.styleId}）没有触摸区域可配")
            stopSelf()
            return START_NOT_STICKY
        }

        configId = id
        this.isCustomStyle = isCustomStyle

        if (isTouchStyle) {
            buildTouchStyleSlots(config)
        } else {
            buildCustomStyleSlots(config)
        }

        addBadge()

        /*
         * ⚠️⚠️ **量出系统给窗口加的偏移**，然后自我校正。
         *
         * 这是"编辑器画的方块"与"命中判定算的范围"能对齐的**关键一步**:
         * 判定用的是纯计算的边界，而窗口的实际位置 = `params + 系统偏移`。
         *
         * ⚠️ 详见 [measureWindowOffset] 的说明（用户实测抓到 141 像素的偏移）。
         *
         * ⚠️ 原来这里调的是一条"几何自检"日志 —— 它用的是与
         * `syncRegionGeometry` **完全相同的公式**，自己跟自己比，
         * 永远打印"✅ 一致"，**什么也证明不了**。已删掉。
         */
        slots.firstOrNull()?.box?.post { measureWindowOffset() }

        AppLog.i(TAG, "触摸区域编辑器已打开：配置=${config.name}，窗口 ${slots.size} 个")
        return START_NOT_STICKY
    }

    /**
     * 触屏样式:一个**轮盘** + 每个按键槽位一块（没配过的画占位方块）。
     *
     * ⚠️ 「不保存退出」要恢复原样，所以先存一份快照。
     *
     * ⚠️ 快照只在**打开时**取一次 —— 每次 `onStartCommand` 都取的话，
     * 编辑过程中被系统重启一次，"不保存退出"就变成"恢复到刚才那一步"了。
     *
     * ⚠️ 快照取 `regions`（**不是** `activeRegions`）—— 「不保存退出」
     * 要把配置**逐字**恢复成打开时的样子，包括那些 `pinned = false`
     * 的占位条目（如果有的话）。
     */
    private fun buildTouchStyleSlots(config: KeyStrokesConfig) {
        snapshot = config.touch.regions
        val existing = snapshot.associateBy { it.slotId }

        addRegion(
            slotId = TouchSettings.JOYSTICK_SLOT_ID,
            label = "轮盘",
            color = COLOR_JOYSTICK,
            region = existing[TouchSettings.JOYSTICK_SLOT_ID]?.let {
                TouchRegion(it.centerX, it.centerY, it.size)
            } ?: TouchRegionSpec.MOVE_DEFAULT,
            touched = TouchSettings.JOYSTICK_SLOT_ID in existing,
            /*
             * ⚠️ 轮盘的占位**永远转正**（即使是第一次打开）—— 用户明确要求
             * "打开编辑器时全给"，而轮盘是触屏的核心（没有它就没有移动摇杆）。
             * 按键区域才需要"用户碰过才算数"。
             */
            temporary = false,
        )

        /*
         * ⚠️ 按键区域 = "会显示在悬浮窗上的槽位"（用户选了方案 ①）。
         *
         * 取法与编辑页/渲染那边**同一个函数**（`KeyLayout.keys`）——
         * 那里画什么，这里就能配什么。自己另列一份的话，
         * 以后布局加了槽位就会出现"悬浮窗上有、但配不了"。
         *
         * ⚠️ 配置里没配过的槽位给一个**占位**（`temporary = true`）:
         * 画出来给用户拖，但**用户不碰就不写进配置**。
         * 详见 [RegionSlot.temporary] —— 那是"持久化漏洞"的修法。
         */
        KeyLayout.keys(config)
            .map { it.slotId }
            .filter { it != KeyLayout.Id.JOYSTICK_LEFT && it != KeyLayout.Id.JOYSTICK_RIGHT }
            .distinct()
            .forEachIndexed { index, slotId ->
                val saved = existing[slotId]
                val default = TouchRegionSpec.defaultCenterFor(index)
                addRegion(
                    slotId = slotId,
                    label = labelFor(config, slotId),
                    color = PALETTE[index % PALETTE.size],
                    region = saved?.let { TouchRegion(it.centerX, it.centerY, it.size) }
                        ?: TouchRegion(default.first, default.second, TouchRegionSpec.SIZE_DEFAULT),
                    touched = saved != null,
                    /*
                     * ⚠️ 配置里**有**这块 → 它已经是用户配过的（`temporary = false`）；
                     * 没有 → 只是画出来给用户拖的占位，**不碰就不写进配置**。
                     */
                    temporary = saved == null,
                )
            }
    }

    /**
     * 自定义 Key 样式:画**这个配置里所有的触摸区域**。
     *
     * ============================================================
     * ⚠️⚠️ 与触屏样式最大的区别:**不画占位方块**
     * ============================================================
     * 触屏样式一打开就给所有槽位画方块（用户选的方案 ①），因为那些槽位
     * **本来就存在**，只是还没配区域。
     *
     * ⚠️ 而自定义布局里"有哪些区域"是**用户自己加出来的** ——
     * 凭空画方块没有意义（他不知道那些方块代表什么）。
     *
     * ⚠️ 用户的原话:
     *
     * > 打开 touching 面板后，**左下角可以添加触摸区域**……
     * > 添加出的触摸区域也可以**删除**
     *
     * ⚠️ 所以这里**只画配置里已有的**，一个占位都不加。
     */
    private fun buildCustomStyleSlots(config: KeyStrokesConfig) {
        customSnapshot = config.custom.touchRegions

        customSnapshot.forEachIndexed { index, region ->
            addRegion(
                slotId = region.id,
                label = labelForCustom(config, region, index),
                color = if (region.kind == CustomTouchRegion.Kind.MOVE_STICK) {
                    COLOR_JOYSTICK
                } else {
                    PALETTE[index % PALETTE.size]
                },
                region = region.region,
                /* ⚠️ 配置里读出来的**必然是**用户配过的（新模型下"新建即存在"） */
                touched = true,
                temporary = false,
                customId = region.id,
                customKind = region.kind,
                customBindings = region.bindings,
            )
        }
    }

    /**
     * 自定义区域在界面上叫什么。
     *
     * ⚠️ **按类型 + 序号**命名（"轮盘区域" / "按键区域 2"）——
     * 用户的原话里就是这种叫法:
     *
     * > 触摸区域也有分类:**轮盘触摸区域 / 按键触摸区域**
     *
     * ⚠️ 序号**只数同类型的**（两块按键区域是"1""2"，不因为中间夹了
     * 一块轮盘而跳号）。
     */
    private fun labelForCustom(
        config: KeyStrokesConfig,
        region: CustomTouchRegion,
        index: Int,
    ): String {
        val sameKind = config.custom.touchRegions
            .take(index + 1)
            .count { it.kind == region.kind }
        return if (region.kind == CustomTouchRegion.Kind.MOVE_STICK) {
            "轮盘区域"
        } else {
            "按键区域 $sameKind"
        }
    }

    override fun onDestroy() {
        removeAll()
        /*
         * ⚠️⚠️ **必须清掉身份** —— 不清的话那个配置会**永远拒绝再次打开**
         * （服务已经死了，而 `editingConfigId` 还留着它的 id）。
         *
         * ⚠️ 这个坑很隐蔽:用户关掉编辑器、再点一次「编辑触摸区域」，
         * 得到的是"正在编辑另一个配置"—— 而屏幕上**一个方块都没有**。
         */
        editingConfigId = null
        AppLog.i(TAG, "触摸区域编辑器已关闭")
        super.onDestroy()
    }


    /**
     * 槽位在界面上叫什么 —— 优先用用户自己改过的显示文字。
     *
     * ⚠️ 用 `keyMappings` 里的 `displayText` 而不是写死一张表:
     * 用户把 LT 改成"扳机"之后，编辑器里也该写"扳机"，
     * 否则他会对着两个名字猜哪个是哪个。
     */
    private fun labelFor(config: KeyStrokesConfig, slotId: String): String =
        config.keyMappings.firstOrNull { it.id == slotId }?.displayText?.takeIf { it.isNotBlank() }
            ?: slotId

    /*
     * ============================================================
     * 窗口：一块触摸区域
     * ============================================================
     */

    private fun addRegion(
        slotId: String,
        label: String,
        color: Int,
        region: TouchRegion,
        touched: Boolean,
        /** 见 [RegionSlot.temporary] —— `true` = 只是画出来给用户拖的占位 */
        temporary: Boolean,
        /** **自定义 Key 那一支**的额外字段，见 [RegionSlot.customId] */
        customId: String? = null,
        customKind: CustomTouchRegion.Kind = CustomTouchRegion.Kind.BUTTON,
        customBindings: List<CustomTouchRegionBinding> = emptyList(),
    ) {
        val box = buildRegionView(label, color)
        val params = regionParams()

        val slot = RegionSlot(
            slotId = slotId,
            label = label,
            color = color,
            region = TouchRegionSpec.clamp(region),
            touched = touched,
            temporary = temporary,
            box = box,
            params = params,
            customId = customId,
            customKind = customKind,
            customBindings = customBindings,
        )
        attachRegionTouch(slot)
        windowManager.addView(box, params)
        slots += slot

        syncRegionGeometry(slot)
    }

    /**
     * 那个方块的**初始**窗口参数（位置与大小在 [syncRegionGeometry] 里按区域值算）。
     *
     * ============================================================
     * ⚠️⚠️ 这几个 flag 是照 **Axon** 抄的（用户让我参考它，这次是对的）
     * ============================================================
     * 用户实测抓到的证据:
     *
     * ```
     * 真实位置 LT(LMB) | getLocationOnScreen=(902,1776) 视口=151x151
     *                  | params=(902,1635) 151x151
     * ```
     *
     * ⚠️ **我把位置设成 1635，系统却把它放到了 1776** —— 差 141 像素，
     * 而且**只在 Y 方向**。竖屏时那表现为"实际触摸区偏上"，
     * 横屏时同一条物理边缘变成左右方向的挪动，表现为"偏左"。
     *
     * ⚠️ 成因是**刘海/挖孔的安全边**:系统把 overlay 窗口往里挤了，
     * 而我**从来没处理过 cutout**（全项目一处都没有）。
     *
     * ⚠️ Axon 那边在同一个 `LayoutParams` 上设了这些（见它的
     * `AxonInputAccessibilityService` 里那个编辑器窗口）:
     *
     * | flag | 作用 |
     * |---|---|
     * | `FLAG_LAYOUT_IN_SCREEN` | ⚠️ **我原来漏了这个** —— 让窗口相对**整块屏幕**布局，不被系统栏挤开 |
     * | `FLAG_LAYOUT_NO_LIMITS` | 允许超出屏幕边界（原来就有） |
     * | `layoutInDisplayCutoutMode = SHORT_EDGES` | ⚠️ 允许画进刘海区（原来没有） |
     * | `SYSTEM_UI_FLAG_LAYOUT_*` | 视图层不因系统栏而收缩 |
     *
     * ⚠️ 但**光靠抄 flag 不够**（我刚被"自证日志"骗过一次）——
     * 所以 [syncRegionGeometry] 里还有一套**真实测量 + 自我校正**:
     * 它读 `getLocationOnScreen`，把系统的偏移补回去。
     * 两者一起才叫"保证对齐"。
     */
    private fun regionParams(): WindowManager.LayoutParams {
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            /* ⚠️ Axon 有、我原来漏了的那个 */
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        return WindowManager.LayoutParams(
            1,
            1,
            OverlayWindowHost.windowType(),
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            /* ⚠️ 允许画进刘海区（Axon 也是这么设的） */
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    /**
     * 按区域值算出窗口的**位置与大小**，并应用到窗口上。
     *
     * ============================================================
     * ⚠️⚠️ 两个单位，别用混
     * ============================================================
     * | 量 | 单位 | 换算 |
     * |---|---|---|
     * | 中心 | **整屏归一化** | `中心 × 屏宽` / `中心 × 屏高` |
     * | 边长 | **较短边归一化** | `边长 × 短边` |
     *
     * ⚠️⚠️ 我第一版**中心也乘了短边**，于是横向只能用到"一个短边" ——
     * 横屏下就是**屏幕一半**，右手摇杆的人根本放不过去。
     *
     * 用户实测指出:
     *
     * > 你怎么把轮盘触摸区域限制在屏幕一半位置了，这是多此一举的操作，
     * > 你考虑过**左撇子**吗？要是有人习惯**右手摇杆**呢
     *
     * ⚠️ 所以中心横向必须 `× 屏宽`（不是 `× 短边`），见 [TouchRegion] 的说明。
     */
    private fun syncRegionGeometry(slot: RegionSlot) {
        val (screenW, screenH) = OverlayBounds.screenSize(appContext)

        /*
         * ⚠️⚠️ 边界取自 [TouchRegionGeometry.bounds] —— **与命中判定同一个函数**。
         *
         * 原来这里自己算了一遍（还多加了"最小 32dp"的下限），而判定那边
         * 也算了一遍、没有下限 —— 用户问的"能保证方框与实际区域对应吗"
         * 就是因为这种"两处各写一遍"。
         *
         * ⚠️ 现在改公式只改 `TouchRegionGeometry`，两边**必然**同步。
         */
        val b = TouchRegionGeometry.bounds(
            centerX = slot.region.centerX,
            centerY = slot.region.centerY,
            size = slot.region.size,
            screenWidth = screenW,
            screenHeight = screenH,
            density = density,
        )

        slot.params.width = b.width
        slot.params.height = b.height

        /*
         * ⚠️⚠️ **减去已知的窗口偏移**（见 [windowOffset]）。
         *
         * 系统会因为刘海/安全边把 overlay 窗口往里挤 —— 实测 141 像素，
         * 而那个偏移是**我们控制不了**的。所以目标位置要**先减掉它**，
         * 系统再挤一次之后才正好落在 [b.left] / [b.top]。
         *
         * ⚠️ 这正是"命中判定与画出来的方块对齐"的关键一步:
         * 判定用的是 [b]（纯计算），而方块的实际位置 = `params + 系统偏移`。
         * 不减掉偏移的话，两者**永远差那 141 像素**。
         */
        slot.params.x = b.left - windowOffsetX
        slot.params.y = b.top - windowOffsetY

        runCatching { windowManager.updateViewLayout(slot.box, slot.params) }
            .onFailure { AppLog.w(TAG, "更新区域窗口失败：${it.javaClass.simpleName}") }
    }

    /**
     * 系统给 overlay 窗口加的**额外偏移**（像素）。
     *
     * ============================================================
     * ⚠️⚠️ 这是用户实测抓出来的一个真 bug
     * ============================================================
     * 证据:
     *
     * ```
     * 真实位置 LT(LMB) | getLocationOnScreen=(902,1776) 视口=151x151
     *                  | params=(902,1635) 151x151
     * ```
     *
     * ⚠️ 我们设 `y=1635`，系统把它放到了 `1776` —— **差 141 像素**，
     * 而且只在 Y 方向。用户看到的现象正是:
     *
     * > 横屏是实际的触摸区域相比编辑的会**向左偏移**，竖屏的实际触摸区域
     * > 则是会**向上偏移**
     *
     * （同一条物理边缘，在两种朝向下分别成为左右/上下的挪动。）
     *
     * ⚠️ 光靠 `FLAG_LAYOUT_IN_SCREEN` + cutout 模式**不一定够** ——
     * 那要看具体设备与系统版本。所以这里**量出来再用**:
     * 实测偏移 = `getLocationOnScreen - params`。
     *
     * ⚠️ 量到之后**要重新摆一次**（`resyncAll`），否则本次仍是偏的。
     */
    private var windowOffsetX: Int = 0
    private var windowOffsetY: Int = 0

    /** 已经量过几次 —— 防"量一次改一次"来回震荡 */
    private var measureAttempts: Int = 0

    /**
     * 量出 [windowOffsetX] / [windowOffsetY]，若与上次不同就**重新摆一遍**。
     *
     * ⚠️ 必须在 `post` 里调（要等这一帧布局跑完，否则 `getLocationOnScreen`
     * 拿到的还是 0）。
     *
     * ⚠️ 加一个尝试次数上限:偏移量本身是稳定的（取决于刘海位置，不取决于
     * 我们设的坐标），所以**一两次就该收敛**。若一直变说明假设错了 ——
     * 那时停手并记日志，好过无限循环。
     */
    private fun measureWindowOffset() {
        val sample = slots.firstOrNull() ?: return
        val loc = IntArray(2)
        runCatching { sample.box.getLocationOnScreen(loc) }.onFailure { return }

        val measuredX = loc[0] - sample.params.x
        val measuredY = loc[1] - sample.params.y

        val changed = measuredX != windowOffsetX || measuredY != windowOffsetY
        if (changed) {
            /*
             * ⚠️ **只在真的变了的时候记**。
             *
             * ⚠️ 我原来每次测量都记一行（含"✅ 已稳定"）—— 那会在**每次**
             * 打开编辑器时都刷一行正常的噪声，把真异常埋掉。
             */
            AppLog.i(
                TAG,
                "窗口偏移校正：getLocationOnScreen=(${loc[0]},${loc[1]}) " +
                    "params=(${sample.params.x},${sample.params.y}) " +
                    "→ 系统偏移=($measuredX,$measuredY)，重新摆放并复测",
            )
        }

        if (!changed) return
        if (measureAttempts >= MAX_MEASURE_ATTEMPTS) {
            AppLog.w(
                TAG,
                "⚠️ 窗口偏移量测了 $MAX_MEASURE_ATTEMPTS 次还在变，停止校正" +
                    "（说明「偏移是常数」这个假设不成立，需要换办法）",
            )
            return
        }

        measureAttempts++
        windowOffsetX = measuredX
        windowOffsetY = measuredY
        slots.forEach { syncRegionGeometry(it) }
        /* ⚠️ 摆完还要再量一次（新位置可能带来新的偏移） */
        slots.firstOrNull()?.box?.post { measureWindowOffset() }
    }

    /** 重新摆一遍全部窗口（拖动/尺寸变化后调用） */
    private fun resyncAll() {
        slots.forEach { syncRegionGeometry(it) }
    }

    /**
     * 一个区域的方块外观。
     *
     * ⚠️ 用户的要求:
     *
     * > 方块 ui 都简单一些因为**只起到定位作用**，画的不用太精细，
     * > 只需要里面加个小文字「轮盘」，亦或者**每个方块用不同颜色来区分**
     *
     * ⚠️ 所以两者都做了:**淡色半透明填充 + 一个边框 + 居中标签**。
     */
    private fun buildRegionView(label: String, color: Int): View {
        val container = FrameLayout(this)

        container.background = GradientDrawable().apply {
            setColor(withAlpha(color, ALPHA_FILL))
            setStroke(dp(2f), color)
            cornerRadius = dp(8f).toFloat()
        }
        container.isClickable = true

        val text = TextView(this).apply {
            text = label
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
        }
        container.addView(
            text,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ),
        )
        return container
    }

    /**
     * 拖动与缩放。
     *
     * ============================================================
     * ⚠️ 交互:中间拖动 = 移动；边缘拖动 = 缩放
     * ============================================================
     * 与 Axon 那套一致（它也是"边缘 24dp 内是缩放、中间是移动"）。
     *
     * ⚠️ 缩放时**保持正方形**:宽高都由边长决定（[TouchRegion] 只有一个
     * `size`）。所以缩放取的是"拖动点在两个方向上的最大位移"，
     * 而不是分别改宽高 —— 那样会变成矩形，而数据模型存不下。
     *
     * ⚠️ 拖动改的是**中心**（数据模型存中心），不是左上角。
     * 直接改 `params.x` 的话，缩放时中心会漂（因为边长变了、
     * 而左上角没跟着补偿）。
     */
    /**
     * 拖动:方块**只能移动，不能缩放**。
     *
     * ============================================================
     * ⚠️⚠️ 边框缩放**已取消**（我加过，用户实测后要求去掉）
     * ============================================================
     * 用户的原话:
     *
     * > 拖动触摸区域边框调整边长的功能**还是取消吧太容易误触了**，
     * > 只保留在 touching 面板里调整就行了
     *
     * ⚠️ 他说得对，而且原因是**交互本身冲突**:方块只有 100~600 像素宽，
     * 而"边缘 24dp 内算缩放"意味着**方块越小、能拖动的中间区域越小** ——
     * 小方块几乎全身都是"边缘"，于是**想移动却变成了缩放**。
     *
     * ⚠️ 所以现在整块方块都是"移动"，边长只在面板的滑块里调。
     * 各管一件事:定位靠拖、尺寸靠滑块。
     *
     * ⚠️ 拖动改的是**中心**（数据模型存中心），不是左上角。
     * 直接改 `params.x` 的话，位置被夹取时中心会漂掉。
     */
    private fun attachRegionTouch(slot: RegionSlot) {
        var downRawX = 0f
        var downRawY = 0f
        var startCenterX = 0f
        var startCenterY = 0f

        slot.box.setOnTouchListener { _, event ->
            val (screenW, screenH) = OverlayBounds.screenSize(appContext)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startCenterX = slot.region.centerX
                    startCenterY = slot.region.centerY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    /*
                     * ⚠️ 位移换算用**各自的单位**:横向除以屏宽、纵向除以屏高
                     * （中心是整屏归一化）。两者都除以短边的话，横向会
                     * **走得比手指快**（横屏下屏宽 > 短边）。
                     */
                    val dx = (event.rawX - downRawX) / screenW
                    val dy = (event.rawY - downRawY) / screenH

                    slot.region = TouchRegionSpec.clamp(
                        TouchRegion(
                            centerX = startCenterX + dx,
                            centerY = startCenterY + dy,
                            /* ⚠️ 边长**不动** —— 缩放已取消，见函数注释 */
                            size = slot.region.size,
                        ),
                    )
                    slot.touched = true
                    /*
                     * ⚠️ **一拖就转正** —— 占位方块从这一刻起是"用户配过的"，
                     * 保存时会被写进配置。见 [RegionSlot.temporary]。
                     */
                    slot.temporary = false
                    syncRegionGeometry(slot)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    /*
                     * ⚠️ 松手后**重新量一次** —— 位置变了，顺便确认偏移没变。
                     * （偏移取决于刘海位置，与我们的坐标无关，所以正常应当稳定。）
                     */
                    slot.box.post { measureWindowOffset() }
                    true
                }

                else -> false
            }
        }
    }

    /*
     * ============================================================
     * 窗口：touching 徽标 + 控制面板
     * ============================================================
     */

    private fun addBadge() {
        val text = TextView(this).apply {
            this.text = "touching"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            background = GradientDrawable().apply {
                setColor(COLOR_BADGE)
                cornerRadius = dp(6f).toFloat()
            }
            isClickable = true
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            OverlayWindowHost.windowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12f)
            y = dp(12f)
        }

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        text.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (kotlin.math.abs(dx) > dp(6f) || kotlin.math.abs(dy) > dp(6f)) moved = true
                    params.x = startX + dx
                    params.y = startY + dy
                    runCatching { windowManager.updateViewLayout(text, params) }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    /* ⚠️ 只有"没拖动"才算点击 —— 否则拖一下就会莫名弹出面板 */
                    if (!moved) showPanel()
                    true
                }

                else -> false
            }
        }

        windowManager.addView(text, params)
        badge = text
    }

    /**
     * 弹出控制面板，并**隐藏 `touching` 徽标**。
     *
     * ⚠️ 用户的原话:
     *
     * > 点击 touching 悬浮窗**弹出面板**，**面板居中**，**面板弹出时隐藏
     * > touching 悬浮窗**，面板右上角做一个**叉号**表示关闭面板，
     * > 面板右下方做「**不保存退出**」与「**保存并退出**」
     *
     * ⚠️ 关于"touching 本身就是一块调节面板"那句:
     * 用户说的是"它**不是**调出触摸区域细节的按钮" ——
     * 处理方式是**徽标是入口、面板是本体**，弹出时把入口藏起来避免重叠。
     */
    private fun showPanel() {
        if (panel != null) return

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            background = GradientDrawable().apply {
                setColor(COLOR_PANEL)
                cornerRadius = dp(14f).toFloat()
                setStroke(dp(1f), COLOR_PANEL_BORDER)
            }
        }

        /* 顶栏:标题 + 右上角叉号 */
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(
            TextView(this).apply {
                text = "触摸区域"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        titleRow.addView(
            TextView(this).apply {
                text = "✕"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                gravity = Gravity.CENTER
                setPadding(dp(10f), dp(2f), dp(10f), dp(6f))
                isClickable = true
                /*
                 * ⚠️ 叉号 = **收起面板回到编辑**（不退出编辑器）。
                 * 这与「不保存退出」是两件事 —— 后者会关掉所有窗口。
                 */
                setOnClickListener { hidePanel() }
            },
        )
        root.addView(titleRow)

        root.addView(
            TextView(this).apply {
                text = "拖动方块调位置，下面的滑块调边长"
                setTextColor(COLOR_HINT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(4f), 0, dp(8f))
            },
        )

        /*
         * ⚠️ 区域多起来会超出屏幕 —— 必须能滚动。
         * 不滚的话下面的滑块和按钮会被挤到屏幕外，用户点不到。
         */
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        slots.forEach { slot -> list.addView(buildRegionRow(slot)) }

        root.addView(
            ScrollView(this).apply {
                addView(list)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                )
            },
        )

        /*
         * ============================================================
         * ⚠️⚠️ 底部**只有一行** —— 「添加」在左、「保存/不保存」在右
         * ============================================================
         * 用户的原话:
         *
         * > 加上"添加触摸区域"的时候**不要把原来的保存并退出和不保存退出
         * > 挤掉了**，注意一下
         *
         * ⚠️ 他说得对，而且这是个**真会发生**的问题:
         *
         * | 做法 | 后果 |
         * |---|---|
         * | 添加按钮**单独占一行** | ⚠️ 面板总高有上限（见这个函数末尾那段），多一行就可能把「保存并退出」挤出屏幕 —— 而它**没有替代入口**（退不出去了） |
         * | **并进同一行**（现在这样） | ✅ 面板高度与改之前**完全一样**，只是左端多了一个按钮 |
         *
         * ⚠️ 用户说的"**左下角**可以添加触摸区域"正好就是这个位置。
         *
         * ⚠️ 触屏样式**不放这个按钮**（它的区域由槽位决定，一个不多一个不少），
         * 但**弹簧照放** —— 这样右边那两个按钮与改之前**逐像素一致**。
         */
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10f), 0, 0)
        }

        if (isCustomStyle) {
            /*
             * ⚠️ **两个按钮而不是"一个 + 对话框"** —— 见 [showAddRegionPicker]
             * 里那段"一次真实的闪退换来的"。
             *
             * ⚠️ 用户的原话是"触摸区域也有分类:**轮盘触摸区域 / 按键触摸区域**"，
             * 所以这两个按钮正好就是那个分类，不用再套一层选择界面。
             */
            buttons.addView(
                panelButton("＋ 轮盘", primary = false) {
                    addCustomRegion(CustomTouchRegion.Kind.MOVE_STICK)
                },
            )
            buttons.addView(
                panelButton("＋ 按键", primary = false) {
                    addCustomRegion(CustomTouchRegion.Kind.BUTTON)
                },
            )
        }

        /* ⚠️ 弹簧:把后面两个按钮顶到右端 */
        buttons.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        buttons.addView(panelButton("不保存", primary = false) { cancelAndExit() })
        buttons.addView(panelButton("保存并退出", primary = true) { saveAndExit() })
        root.addView(buttons)

        /*
         * ⚠️ **居中**（用户要求:"这样才能放更多组件调整边长的滑块"）。
         *
         * ⚠️ 用 `Gravity.CENTER` 而不是自己算坐标:让系统去居中，
         * 屏幕尺寸/旋转变化时自动跟着变。
         *
         * ⚠️ 宽度取"屏幕宽的这个比例，再夹到 dp 上下限":
         * 固定 dp 会在小屏上超出、在大屏上太窄。
         */
        val (screenW, _) = OverlayBounds.screenSize(appContext)
        val panelWidth = (screenW * PANEL_WIDTH_RATIO).roundToInt()
            .coerceIn(dp(240f), dp(420f))

        val params = WindowManager.LayoutParams(
            panelWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            OverlayWindowHost.windowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
        }

        windowManager.addView(root, params)
        panel = root

        /* ⚠️ 面板弹出时隐藏徽标 —— 用户明确要求，也免得两者重叠 */
        badge?.let { runCatching { it.visibility = View.GONE } }
    }

    /** 收起面板，把徽标放回来（回到"编辑 + 徽标入口"的状态） */
    private fun hidePanel() {
        panel?.let { runCatching { windowManager.removeView(it) } }
        panel = null
        badge?.let { runCatching { it.visibility = View.VISIBLE } }
    }

    /** 一个区域在面板里的一行:色块 + 名称 + 当前边长 + 滑块 */
    private fun buildRegionRow(slot: RegionSlot): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6f), 0, dp(6f))
        }

        val labelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        /* 一个小色块，与方块颜色对应 —— 用户靠颜色区分哪个是哪个 */
        labelRow.addView(
            View(this).apply {
                background = GradientDrawable().apply {
                    setColor(slot.color)
                    cornerRadius = dp(2f).toFloat()
                }
            },
            LinearLayout.LayoutParams(dp(10f), dp(10f)).apply { rightMargin = dp(8f) },
        )
        labelRow.addView(
            TextView(this).apply {
                text = slot.label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        labelRow.addView(
            TextView(this).apply {
                setTextColor(COLOR_HINT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                text = formatSize(slot.region.size)
                tag = "value:${slot.slotId}"
            },
        )

        /*
         * ============================================================
         * ⚠️⚠️ **绑定 / 删除**（只有自定义 Key 那一支有）
         * ============================================================
         * 用户的原话:
         *
         * > 添加出的触摸区域也可以**删除**（删除按钮就加在添加出的
         * > 触摸区域名字旁），然后，**每个触摸区域旁都要加一个绑定按钮**
         *
         * ⚠️ 放在**名字那一行**（不是滑块那一行）—— 挤同一行会把滑块
         * 压得没法拖，那是用户明确提过的顾虑。
         *
         * ⚠️ 触屏样式**没有这两个按钮**:它的区域是**槽位**决定的
         * （数量与归属都定死了），既不能加也不能删。
         */
        if (slot.customId != null) {
            labelRow.addView(
                smallButton("绑定") { showBindingPicker(slot) },
            )
            labelRow.addView(
                smallButton("✕") { deleteCustomRegion(slot) },
            )
        }

        row.addView(labelRow)

        /*
         * ⚠️ **绑定摘要** —— 用户要能一眼看出"这块配好了没有"。
         *
         * ⚠️ 没有它的话，"加了区域但忘了绑定"在界面上完全看不出来
         * （方块照样画着、滑块照样能拖），而真机上按下去毫无反应 ——
         * 那正是旧模型最坑人的地方。
         */
        if (slot.customId != null) {
            row.addView(
                TextView(this).apply {
                    text = bindingSummaryOf(slot)
                    setTextColor(if (slot.customBindings.isEmpty()) COLOR_WARN else COLOR_HINT)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setPadding(dp(18f), dp(2f), 0, dp(2f))
                    tag = "bind:${slot.slotId}"
                },
            )
        }

        row.addView(
            SeekBar(this).apply {
                max = SEEKBAR_MAX
                progress = sizeToProgress(slot.region.size)
                tag = "bar:${slot.slotId}"
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, p: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        slot.region = TouchRegionSpec.clamp(
                            TouchRegion(slot.region.centerX, slot.region.centerY, progressToSize(p)),
                        )
                        slot.touched = true
                        /* ⚠️ 拉了滑块也算"配过" —— 见 [RegionSlot.temporary] */
                        slot.temporary = false
                        syncRegionGeometry(slot)
                        syncPanelValues()
                    }

                    override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar?) = Unit
                })
            },
        )
        return row
    }

    private fun panelButton(text: String, primary: Boolean, onClick: () -> Unit): View =
        TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            background = GradientDrawable().apply {
                setColor(if (primary) COLOR_PRIMARY else COLOR_SECONDARY)
                cornerRadius = dp(8f).toFloat()
            }
            isClickable = true
            setOnClickListener { onClick() }
        }

    /** 拖动方块之后把面板上的滑块也同步过去（否则滑块还停在旧值） */
    private fun syncPanelValues() {
        val root = panel as? ViewGroup ?: return
        slots.forEach { slot ->
            (root.findViewWithTag<View>("bar:${slot.slotId}") as? SeekBar)?.let { bar ->
                val p = sizeToProgress(slot.region.size)
                if (bar.progress != p) bar.progress = p
            }
            (root.findViewWithTag<View>("value:${slot.slotId}") as? TextView)?.let { label ->
                label.text = formatSize(slot.region.size)
            }
            /* ⚠️ 绑定摘要也要跟着刷新（绑定对话框改完就变了） */
            (root.findViewWithTag<View>("bind:${slot.slotId}") as? TextView)?.let { label ->
                label.text = bindingSummaryOf(slot)
                label.setTextColor(
                    if (slot.customBindings.isEmpty()) COLOR_WARN else COLOR_HINT,
                )
            }
        }
    }

    /*
     * ============================================================
     * 自定义 Key 那一支:添加 / 删除 / 绑定
     * ============================================================
     */

    /**
     * 行上的**小按钮**（绑定 / 删除）。
     *
     * ⚠️ 与 [panelButton] 分开:那个是"保存并退出"那类**主按钮**
     * （大内边距、绿底），塞进行里会把名字挤没。
     */
    private fun smallButton(text: String, onClick: () -> Unit): View =
        TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
            background = GradientDrawable().apply {
                setColor(COLOR_SECONDARY)
                cornerRadius = dp(6f).toFloat()
            }
            isClickable = true
            setOnClickListener { onClick() }
        }.also {
            (it.layoutParams as? LinearLayout.LayoutParams)?.leftMargin = dp(6f)
            it.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = dp(6f) }
        }

    /**
     * 这块区域**绑了谁**的一行摘要。
     *
     * ⚠️ 目标组件可能已经被删了（区域留着、绑定指向一个不存在的 id）——
     * 那种情况要**说出来**，而不是显示一个看不懂的 id。
     */
    private fun bindingSummaryOf(slot: RegionSlot): String {
        if (slot.customBindings.isEmpty()) return "    ⚠️ 未绑定 —— 这块区域现在没有任何作用"

        val config = ConfigStore.get(appContext).find(configId)
        val names = slot.customBindings.joinToString("、") { binding ->
            val label = config?.let { componentLabel(it, binding.componentId) }
                ?: binding.componentId
            binding.direction?.let { "$label（${it.label}）" } ?: label
        }
        return "    已绑：$names"
    }

    /**
     * 一个组件在界面上叫什么。
     *
     * ⚠️ 认不出来时**回退到 id**，而不是空串 —— 空串会让人以为
     * "这块区域没绑东西"，而实际上它绑了（只是那个组件被删了）。
     */
    private fun componentLabel(config: KeyStrokesConfig, componentId: String): String? {
        val component = config.custom.components.firstOrNull { it.id == componentId }
            ?: return null
        return when (component) {
            is KeyComponent -> component.label.takeIf { it.isNotBlank() } ?: component.id
            is TextComponent -> component.text.takeIf { it.isNotBlank() }
                ?.take(12)
                ?: "文本"
            is JoystickComponent -> "摇杆（" +
                (if (component.side == StickSide.LEFT) "移动" else "视角") + "）"
        }
    }

    /**
     * 左下角「＋ 添加触摸区域」→ 选类型。
     *
     * ============================================================
     * ⚠️⚠️ **不加对话框，直接摆两个按钮**（一次真实的闪退换来的）
     * ============================================================
     * 我第一版是弹一个 `AlertDialog` 让用户选类型。用户实测:
     *
     * > 点击添加触摸区域**软件直接闪退了**
     *
     * ⚠️ 成因:**本类是 `Service`，它没有 Activity 的窗口令牌** ——
     * 而 `AlertDialog` 需要一个。我原来写的是
     * `AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)`，
     * 那还是个 **Activity 主题**（不是 `…Dialog_Alert` 那个 Dialog 主题），
     * 于是在这里 `show()` 会抛 `WindowManager$BadTokenException`：
     *
     * ```
     * android.view.WindowManager$BadTokenException:
     *   Unable to add window -- token null is not for an application
     * ```
     *
     * ⚠️ 修法**不是**"换个主题再试"（那还是在赌），而是
     * **照这个类已有的做法**:它所有的界面都是
     * `TYPE_APPLICATION_OVERLAY` 悬浮窗（方块、徽标、面板），
     * 那一套本来就**不需要窗口令牌**。
     *
     * ⚠️ 所以这里干脆**不要对话框**:面板底部直接给两个按钮 ——
     * 少一层界面、少一次点击，也不再有任何窗口令牌的风险。
     */
    private fun showAddRegionPicker() {
        addCustomRegion(CustomTouchRegion.Kind.MOVE_STICK)
        addCustomRegion(CustomTouchRegion.Kind.BUTTON)
    }

    /**
     * 真的加一块区域。
     *
     * ============================================================
     * ⚠️⚠️ 新区域**立刻写进配置**（不等到"保存并退出"）
     * ============================================================
     * 用户的原话:
     *
     * > 4 解决**配置持久化**就行，要能保证下次进触摸区域编辑**依然能看到
     * > 上次新建与绑定**的触摸区域
     *
     * ⚠️ 这是新模型与旧模型**最本质的差别**:
     *
     * | 模型 | "新建的区域"什么时候存在 |
     * |---|---|
     * | 旧（挂在组件上） | ⚠️ 用户**碰过**它才算配过 → 不碰就静默丢失 |
     * | **新（配置级）** | ✅ **新建即存在**，与用户碰没碰无关 |
     *
     * ⚠️ 落点取 [TouchRegionSpec.BUTTON_DEFAULT]（屏幕下方偏左），
     * 而不是屏幕正中 —— 正中往往压着游戏的操作区，
     * 一放下去就挡着用户要看的东西。
     */
    private fun addCustomRegion(kind: CustomTouchRegion.Kind) {
        /*
         * ⚠️⚠️ **整段兜住异常** —— 宁可弹一句提示，也不要闪退。
         *
         * ⚠️ 用户报过"点击添加触摸区域**软件直接闪退**"，那次是
         * `AlertDialog` 在 `Service` 里拿不到窗口令牌（见 [showAddRegionPicker]）。
         *
         * ⚠️ 教训不止"那个 API 用错了"，而是:**编辑器里任何一次操作崩掉，
         * 用户丢掉的是整份编辑**（所有悬浮窗随之消失、拖了半天的位置全没）。
         * 所以这些"点一下"的入口都该兜住。
         */
        runCatching { addCustomRegionOrThrow(kind) }
            .onFailure {
                /*
                 * ⚠️ 带 `it` 的那条重载会**把堆栈一起记下来** ——
                 * 下次再出这类问题，用户把日志给我就够了，
                 * 不用再靠"猜哪个 API 用错了"。
                 */
                AppLog.e(TAG, "添加触摸区域失败", it)
                toast("添加失败：${it.javaClass.simpleName}")
            }
    }

    private fun addCustomRegionOrThrow(kind: CustomTouchRegion.Kind) {
        val store = ConfigStore.get(appContext)
        val config = store.find(configId) ?: return

        /*
         * ⚠️ id 用**时间戳**，不复用被删掉的 ——
         * 复用的话"删了再加"会拿到同一个 id，而屏幕上可能还留着
         * 那个旧方块的引用，于是两块区域打架。
         */
        val id = "touch_${System.currentTimeMillis()}"

        val region = CustomTouchRegion(
            id = id,
            kind = kind,
            region = if (kind == CustomTouchRegion.Kind.MOVE_STICK) {
                TouchRegionSpec.MOVE_DEFAULT
            } else {
                /*
                 * ⚠️ 按键区域的默认落点**按已有数量错开**，
                 * 否则连加三块会完全重叠（用户看不出自己加了三块）。
                 */
                val count = config.custom.touchRegions
                    .count { it.kind == CustomTouchRegion.Kind.BUTTON }
                val center = TouchRegionSpec.defaultCenterFor(count)
                TouchRegion(
                    center.first,
                    center.second,
                    TouchRegionSpec.SIZE_DEFAULT,
                )
            },
            bindings = emptyList(),
        )

        store.upsert(
            config.copy(
                custom = config.custom.copy(
                    touchRegions = config.custom.touchRegions + region,
                ),
            ),
        )

        /* ⚠️ 加完之后**重建面板** —— 新的一行要立刻出现 */
        rebuildSlotsAndPanel(config.styleId)
        AppLog.i(TAG, "已添加触摸区域：$id（${kind.label}）")
    }

    /**
     * 删掉一块自定义区域。
     *
     * ⚠️ 只从**配置**里删 —— 下一句的 [rebuildSlotsAndPanel] 会把
     * 方块窗口也一并收掉（`removeAll` 里统一做，不在这里各写一遍）。
     */
    private fun deleteCustomRegion(slot: RegionSlot) {
        /* ⚠️ 与 [addCustomRegion] 同一个道理:兜住异常，别让编辑器整个崩掉 */
        runCatching { deleteCustomRegionOrThrow(slot) }
            .onFailure {
                AppLog.e(TAG, "删除触摸区域失败", it)
                toast("删除失败：${it.javaClass.simpleName}")
            }
    }

    private fun deleteCustomRegionOrThrow(slot: RegionSlot) {
        val id = slot.customId ?: return
        val store = ConfigStore.get(appContext)
        val config = store.find(configId) ?: return

        store.upsert(
            config.copy(
                custom = config.custom.copy(
                    touchRegions = config.custom.touchRegions.filterNot { it.id == id },
                ),
            ),
        )

        rebuildSlotsAndPanel(config.styleId)
        AppLog.i(TAG, "已删除触摸区域：$id")
    }

    /**
     * 按配置**重建**所有方块与面板。
     *
     * ============================================================
     * ⚠️⚠️ 必须先**收好草稿几何**，否则用户刚拖好的位置会全部弹回
     * ============================================================
     * ⚠️ 这是个真会发生的 bug，而且症状很气人:
     *
     * | 步骤 | 用户看到 |
     * |---|---|
     * | 拖好 3 块区域 | 位置都对 |
     * | 点「＋ 添加触摸区域」 | ⚠️ **刚拖好的 3 块全部跳回原位**（只有新加的那块在新位置） |
     *
     * ⚠️ 成因:几何是**草稿**（只在「保存并退出」才写回配置，见 [saveCustomStyle]），
     * 而重建是从**配置**读的 —— 于是草稿整个丢掉。
     *
     * ⚠️ 修法不是"顺手也把几何存了"（那会破坏「不保存退出」的语义:
     * 用户放弃编辑时位置**应该**回到原样），而是**把内存里的草稿搬过去**。
     *
     * ⚠️ 为什么是"整份重建"而不是"只加/删那一个窗口":
     * 面板上的行按 `slots` 顺序建，序号（"按键区域 2"）也依赖它 ——
     * 只改一个的话序号会与实际不符（那种错很难看出来）。
     */
    private fun rebuildSlotsAndPanel(styleId: String) {
        /*
         * ⚠️ 键用**区域身份**（自定义那一支是 `customId`，触屏那一支是槽位 id）——
         * 用下标的话，"删掉中间一块"会让后面所有块的草稿错位一格，
         * 那比"全部弹回"更糟（位置看着像是被谁挪了一下）。
         */
        val drafts = slots.associate { slot ->
            (slot.customId ?: slot.slotId) to slot.region
        }

        removeAll()
        val config = ConfigStore.get(appContext).find(configId) ?: return
        if (styleId == StyleId.CUSTOM_KEY) {
            buildCustomStyleSlots(config)
        } else {
            buildTouchStyleSlots(config)
        }

        /* ⚠️ 把草稿几何铺回去 —— 只在**集合没变过**的那些上恢复（新的那块用默认落点） */
        slots.forEach { slot ->
            val draft = drafts[slot.customId ?: slot.slotId] ?: return@forEach
            slot.region = draft
            slot.touched = true
            slot.temporary = false
            syncRegionGeometry(slot)
        }

        addBadge()
        showPanel()

        /* ⚠️ 面板是按草稿几何建的，但滑块初值可能还差一点 —— 对齐一次 */
        syncPanelValues()
    }

    /**
     * 「绑定」按钮 —— 选这块区域要绑哪些组件。
     *
     * ============================================================
     * ⚠️⚠️ 多选 + 逐组件选方向（用户的方案）
     * ============================================================
     * 用户的原话:
     *
     * > 按键触摸区域可以绑定按键组件/文本组件，轮盘触摸区域既可以绑定
     * > 按键组件/文本组件，**也可以绑定触屏摇杆组件**，轮盘触摸区域绑定
     * > 按键组件/文本组件时，需要额外选择绑定后触发是**前/后/左/右**
     *
     * ⚠️ 于是对话框分两段:
     *
     * | 段 | 内容 |
     * |---|---|
     * | 上 | **多选**组件（复选框） |
     * | 下 | 轮盘区域才出现:每个**已勾选的**按键/文本组件各一个方向选择 |
     *
     * ⚠️ 方向选择**不放在面板上** —— 一个区域可以绑好几个组件，
     * 每个各带一个方向，面板上摆不下（用户明确提过面板已经很挤）。
     */
    private fun showBindingPicker(slot: RegionSlot) {
        /* ⚠️ 与 [addCustomRegion] 同一个道理:兜住异常，别让编辑器整个崩掉 */
        runCatching { showBindingPickerOrThrow(slot) }
            .onFailure {
                AppLog.e(TAG, "打开绑定界面失败", it)
                toast("绑定界面打不开：${it.javaClass.simpleName}")
            }
    }

    private fun showBindingPickerOrThrow(slot: RegionSlot) {
        val config = ConfigStore.get(appContext).find(configId) ?: return
        val candidates = config.custom.components

        if (candidates.isEmpty()) {
            toast("这个配置还没有任何组件")
            return
        }

        /*
         * ⚠️ 轮盘区域能绑**触屏摇杆**；按键区域不能
         * （摇杆没有"按下"这回事 —— 见 `CustomTouchRegion.Kind` 那张表）。
         */
        val allowed = candidates.filter { component ->
            slot.customKind == CustomTouchRegion.Kind.MOVE_STICK ||
                component !is JoystickComponent
        }

        /*
         * ⚠️ 勾选状态与方向**在这里各存一份草稿** ——
         * 对话框关掉之前不写配置（用户可能点"取消"）。
         *
         * ⚠️ 初值从**当前绑定**来（再次打开时看到的是上次的选择）。
         */
        val checked = allowed
            .filter { candidate -> slot.customBindings.any { it.componentId == candidate.id } }
            .mapTo(mutableSetOf()) { it.id }
        val directions = slot.customBindings
            .mapNotNull { binding ->
                binding.direction?.let { binding.componentId to it }
            }
            .toMap().toMutableMap()

        val needsDirection = slot.customKind == CustomTouchRegion.Kind.MOVE_STICK

        /*
         * ⚠️ 内容是**动态**的（勾一个按键/文本组件就多一行方向选择），
         * 所以整块内容每次重建 —— 只改可见性的话，
         * "勾了再取消勾选"会留下一行没有意义的空选择。
         */
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
        }

        fun rebuildContent() {
            content.removeAllViews()

            content.addView(
                TextView(this).apply {
                    text = "「${slot.label}」绑定哪些组件"
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setPadding(0, 0, 0, dp(6f))
                },
            )

            if (needsDirection) {
                content.addView(
                    TextView(this).apply {
                        text = "轮盘区域:按键 / 文本组件可以指定「摇杆朝哪个方向时触发」；" +
                            "不选方向就是摇到哪儿都触发。摇杆组件不吃方向。"
                        setTextColor(COLOR_HINT)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        setPadding(0, 0, 0, dp(8f))
                    },
                )
            }

            allowed.forEach { component ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(6f), 0, dp(6f))
                }

                val name = componentLabel(config, component.id) ?: component.id
                val extra = if (component is JoystickComponent) "（摇杆）" else ""
                row.addView(
                    CheckBox(this).apply {
                        text = name + extra
                        setTextColor(Color.WHITE)
                        isChecked = component.id in checked
                        setOnCheckedChangeListener { _, isChecked ->
                            if (isChecked) {
                                checked += component.id
                            } else {
                                checked -= component.id
                                /* ⚠️ 取消勾选时把它的方向也丢掉，否则下次勾上会带回旧方向 */
                                directions -= component.id
                            }
                            rebuildContent()
                        }
                    },
                )

                /*
                 * ⚠️ 只有**勾选了的**、且是**按键/文本**的组件才给方向 ——
                 * 摇杆组件绑上去不带方向（它上报的是轴）。
                 *
                 * ⚠️ 标签与胶囊**分两行**（不是并排）:
                 * 五个胶囊加一个标签挤在一行里，窄屏上会换行成很难看的形状。
                 */
                if (needsDirection && component !is JoystickComponent &&
                    component.id in checked
                ) {
                    row.addView(
                        TextView(this).apply {
                            text = "触发方向"
                            setTextColor(COLOR_HINT)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                            setPadding(dp(32f), 0, 0, dp(2f))
                        },
                    )
                    row.addView(
                        LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            setPadding(dp(32f), 0, 0, 0)
                            /* ⚠️「不选」= `null`，含义是"摇到哪儿都触发" */
                            addView(
                                directionChip("不限", component.id, null, directions, ::rebuildContent),
                            )
                            TouchDirection.entries.forEach { direction ->
                                addView(
                                    directionChip(
                                        direction.label,
                                        component.id,
                                        direction,
                                        directions,
                                        ::rebuildContent,
                                    ),
                                )
                            }
                        },
                    )
                }

                content.addView(row)
            }

            /* ⚠️ 按钮也一起重建 —— 内容重建时它们会被 `removeAllViews` 清掉 */
            content.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                    setPadding(0, dp(12f), 0, 0)
                    addView(panelButton("取消", primary = false) { hideBindingPicker() })
                    addView(panelButton("确定", primary = true) {
                        applyCustomBindings(slot, checked, directions, config)
                        hideBindingPicker()
                    })
                },
            )
        }

        rebuildContent()

        /*
         * ============================================================
         * ⚠️⚠️ **用悬浮窗画，不用 `AlertDialog`**（一次真实的闪退换来的）
         * ============================================================
         * 见 [showAddRegionPicker] 里那段说明:本类是 `Service`，
         * **没有 Activity 的窗口令牌**，`AlertDialog` 在这里 `show()`
         * 会抛 `WindowManager$BadTokenException` → 闪退。
         *
         * ⚠️ 所以照这个类**已有的做法**:`TYPE_APPLICATION_OVERLAY` 悬浮窗。
         * 那一套已经用了很久（方块/徽标/面板），不会踩窗口令牌。
         *
         * ⚠️ 高度按内容自适应、再加一个上限 —— 组件多的时候能滚，
         * 少的时候不会留一大片空白。
         */
        showBindingPickerView(
            ScrollView(this).apply {
                addView(content)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            },
        )
    }

    /**
     * 把绑定界面作为一个**悬浮窗**挂上去（见 [showBindingPicker] 那段说明）。
     *
     * ⚠️ 与面板一样居中;宽度同样"按屏幕比例再夹到 dp 上下限"
     * （固定 dp 在小屏上会超出、在大屏上太窄）。
     */
    private fun showBindingPickerView(content: View) {
        hideBindingPicker()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(COLOR_PANEL)
                cornerRadius = dp(14f).toFloat()
                setStroke(dp(1f), COLOR_PANEL_BORDER)
            }
            addView(content)
        }

        val (screenW, _) = OverlayBounds.screenSize(appContext)
        val width = (screenW * PANEL_WIDTH_RATIO).roundToInt().coerceIn(dp(240f), dp(420f))

        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            OverlayWindowHost.windowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
        }

        runCatching { windowManager.addView(root, params) }
            .onFailure {
                AppLog.w(TAG, "绑定界面打不开：${it.javaClass.simpleName} ${it.message}")
                toast("绑定界面打不开：${it.javaClass.simpleName}")
                return
            }
        bindingPicker = root
    }

    /** 收掉绑定界面（点确定/取消，或编辑器关闭时） */
    private fun hideBindingPicker() {
        bindingPicker?.let { runCatching { windowManager.removeView(it) } }
        bindingPicker = null
    }

    /** 方向选择里的一个小胶囊 */
    private fun directionChip(
        label: String,
        componentId: String,
        direction: TouchDirection?,
        directions: MutableMap<String, TouchDirection>,
        onChanged: () -> Unit,
    ): View = TextView(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        gravity = Gravity.CENTER
        setPadding(dp(10f), dp(5f), dp(10f), dp(5f))
        val selected = directions[componentId] == direction
        setTextColor(if (selected) Color.WHITE else COLOR_HINT)
        background = GradientDrawable().apply {
            setColor(if (selected) COLOR_PRIMARY else COLOR_SECONDARY)
            cornerRadius = dp(6f).toFloat()
        }
        isClickable = true
        setOnClickListener {
            if (direction == null) directions -= componentId else directions[componentId] = direction
            onChanged()
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { rightMargin = dp(6f) }
    }

    /**
     * 把选好的绑定**立刻写回配置**。
     *
     * ⚠️ 立刻写（不等"保存并退出"）是刻意的:绑定与屏幕上的位置无关，
     * 用户在对话框里点"确定"就是最终决定。
     *
     * ⚠️ 顺序按 `components` 的顺序排（不是按勾选顺序）—— 这样
     * 面板上的"已绑：A、B"读起来稳定，不会因为勾选先后而变。
     */
    private fun applyCustomBindings(
        slot: RegionSlot,
        checked: Set<String>,
        directions: Map<String, TouchDirection>,
        config: KeyStrokesConfig,
    ) {
        val id = slot.customId ?: return

        val bindings = config.custom.components
            .filter { it.id in checked }
            .map { component ->
                CustomTouchRegionBinding(
                    componentId = component.id,
                    /*
                     * ⚠️ 摇杆组件**不带方向** —— 即使 `directions` 里
                     * 碰巧留着它的条目（用户先选了方向、又把摇杆勾上），
                     * 摇杆也吃不了方向（它上报的是**轴**）。
                     */
                    direction = if (component is JoystickComponent) {
                        null
                    } else {
                        directions[component.id]
                    },
                )
            }

        val store = ConfigStore.get(appContext)
        val latest = store.find(configId) ?: return
        store.upsert(
            latest.copy(
                custom = latest.custom.copy(
                    touchRegions = latest.custom.touchRegions.map { region ->
                        if (region.id == id) region.copy(bindings = bindings) else region
                    },
                ),
            ),
        )

        /* ⚠️ 屏上的那份也要同步 —— 摘要那一行是从它读的 */
        slot.customBindings = bindings
        syncPanelValues()

        AppLog.i(TAG, "触摸区域 $id 的绑定已更新：${bindings.size} 个")
    }

    private fun toast(message: String) {
        runCatching {
            Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    /*
     * ============================================================
     * 保存 / 不保存
     * ============================================================
     */

    /**
     * 「保存并退出」:把区域写回配置。
     *
     * ============================================================
     * ⚠️⚠️ 只写"**不是占位**"的那些 —— 而且这个判断必须是**持久的**
     * ============================================================
     * 打开编辑器时会给**所有**槽位各画一块方块（用户选的方案 ①），
     * 若把它们全部写进配置，用户"只想配轮盘"却会**凭空得到几个按键区域**
     * —— 那会让 A / LT / RT 在他没碰过的地方突然能按，而且
     * `regions` 不再为空 → **"没配区域"的提示再也不弹**。
     *
     * ⚠️ 我第一版靠的是一个**只活在内存里**的 `touched` 标记，那就是
     * 用户说的"有些东西没做配置持久化"的根因:
     *
     * | 重开编辑器后 | 后果 |
     * |---|---|
     * | `touched` 全变回 false | **已经配过的区域也变成"没动过"** |
     * | 而 `slotId in previouslyConfigured` 又把它救回来 | 于是"配过的"碰巧没事，但**新拖过、保存过、再打开**的那块…… |
     *
     * ⚠️ 现在改成看 [RegionSlot.temporary] —— 它由**配置里有没有这块**决定
     * （那种信息是持久的），用户一拖就转正。
     *
     * ⚠️ 已经配过的那些**保持原样写回**（即使用户这次没碰它）——
     * 它们本来就在配置里，丢掉等于"打开一次编辑器就删了别人的配置"。
     */
    private fun saveAndExit() {
        val store = ConfigStore.get(appContext)
        val config = store.find(configId)
        if (config == null) {
            AppLog.w(TAG, "保存失败：配置已不存在")
            stopSelf()
            return
        }

        /* ⚠️ 按样式分流 —— 见 `onStartCommand` 里那张表 */
        if (config.styleId == StyleId.CUSTOM_KEY) {
            saveCustomStyle(config, store)
        } else {
            saveTouchStyle(config, store)
        }

        stopSelf()
    }

    /**
     * 触屏样式:把方块写成 `touch.regions`。
     */
    private fun saveTouchStyle(config: KeyStrokesConfig, store: ConfigStore) {
        val regions = slots
            /* ⚠️ 占位条目（用户没碰过的）不写 —— 见 [RegionSlot.temporary] */
            .filter { !it.temporary }
            .map {
                TouchRegionBinding(
                    slotId = it.slotId,
                    centerX = it.region.centerX,
                    centerY = it.region.centerY,
                    size = it.region.size,
                    /*
                     * ⚠️ 写进配置的一律 `pinned = true` —— 它们要么是用户
                     * 新拖的（已转正），要么是本来就在配置里的。
                     */
                    pinned = true,
                )
            }

        store.upsert(config.copy(touch = config.touch.copy(regions = regions)))

        AppLog.i(TAG, "触摸区域已保存：${regions.size} 块（${regions.joinToString { it.slotId }}）")
    }

    /**
     * 自定义 Key 样式:把方块写成 `custom.touchRegions`。
     *
     * ============================================================
     * ⚠️⚠️ 只写**几何**，绑定**不从这里写**
     * ============================================================
     * 用户的原话:
     *
     * > 4 解决**配置持久化**就行，要能保证下次进触摸区域编辑**依然能看到
     * > 上次新建与绑定**的触摸区域，且要做好存储
     *
     * ⚠️ 绑定（"这块区域绑了哪些组件"）在**点确定的那一刻**就已经写回配置了
     * （见 [applyCustomBindings]）—— 它与屏幕上的位置无关，所以没必要
     * 拖到"保存并退出"才生效。
     *
     * ⚠️ 而**几何**是拖出来的草稿，必须等「保存并退出」——
     * 否则用户拖到一半点「不保存退出」，位置却已经变了。
     *
     * ⚠️ 所以这里按 [RegionSlot.customId] 把几何**并回**配置里已有的那条，
     * **保留它当前的绑定**（绑定可能在这次会话里被改过）。
     */
    private fun saveCustomStyle(config: KeyStrokesConfig, store: ConfigStore) {
        /*
         * ⚠️ 以**配置里现在的**那条为基准（它带着最新的绑定），
         * 只把几何换成屏幕上拖出来的值 —— 这样两边各管各的，不会互相覆盖。
         */
        val current = config.custom.touchRegions.associateBy { it.id }

        val regions = slots.mapNotNull { slot ->
            val id = slot.customId ?: return@mapNotNull null
            val existing = current[id]
            CustomTouchRegion(
                id = id,
                kind = existing?.kind ?: slot.customKind,
                region = slot.region,
                bindings = existing?.bindings ?: slot.customBindings,
            )
        }

        store.upsert(config.copy(custom = config.custom.copy(touchRegions = regions)))

        AppLog.i(
            TAG,
            "自定义触摸区域已保存：${regions.size} 块" +
                "（${regions.joinToString { "${it.id}→${it.bindings.size} 个绑定" }}）",
        )
    }

    /**
     * 「不保存退出」:恢复打开编辑器时的快照。
     *
     * ⚠️ 恢复的是**打开那一刻**的值（不是"上一步"）—— 见 `snapshot` 的说明。
     */
    private fun cancelAndExit() {
        val store = ConfigStore.get(appContext)
        val config = store.find(configId)
        if (config != null) {
            /*
             * ⚠️ 按样式分流 —— 与 [saveAndExit] 同一个理由。
             *
             * ⚠️ 两边恢复的都是**整个列表**（而不是"把每个字段对一遍"）——
             * 那是"逐字复原"最稳的做法:漏一个字段就是"放弃了但没完全放弃"。
             */
            if (config.styleId == StyleId.CUSTOM_KEY) {
                store.upsert(
                    config.copy(custom = config.custom.copy(touchRegions = customSnapshot)),
                )
                AppLog.i(TAG, "自定义触摸区域已放弃，恢复为原值（${customSnapshot.size} 块）")
            } else {
                store.upsert(config.copy(touch = config.touch.copy(regions = snapshot)))
                AppLog.i(TAG, "触摸区域已放弃，恢复为原值（${snapshot.size} 块）")
            }
        }
        stopSelf()
    }

    private fun removeAll() {
        /* ⚠️ 绑定界面也要收 —— 漏了它会在编辑器关掉后**留在屏幕上** */
        hideBindingPicker()
        panel?.let { runCatching { windowManager.removeView(it) } }
        panel = null
        badge?.let { runCatching { windowManager.removeView(it) } }
        badge = null
        slots.forEach { slot -> runCatching { windowManager.removeView(slot.box) } }
        slots.clear()
    }

    /*
     * ============================================================
     * 小工具
     * ============================================================
     */

    private fun formatSize(size: Float): String = "%.0f%%".format(size * 100f)

    private fun sizeToProgress(size: Float): Int {
        val t = (size - TouchRegionSpec.SIZE_MIN) /
            (TouchRegionSpec.SIZE_MAX - TouchRegionSpec.SIZE_MIN)
        return (t * SEEKBAR_MAX).roundToInt().coerceIn(0, SEEKBAR_MAX)
    }

    private fun progressToSize(progress: Int): Float {
        val t = progress.toFloat() / SEEKBAR_MAX
        return TouchRegionSpec.SIZE_MIN +
            t * (TouchRegionSpec.SIZE_MAX - TouchRegionSpec.SIZE_MIN)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    /**
     * 前台通知。
     *
     * ⚠️ 必须前台:编辑器可能要开着好一会儿（用户对着游戏调），
     * 而后台服务会被系统随时回收 —— 那会让窗口**突然全部消失**。
     */
    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "触摸区域编辑",
                NotificationManager.IMPORTANCE_LOW,
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("正在编辑触摸区域")
            .setContentText("拖动方块调位置，点 touching 弹出面板")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "TouchRegionEditor"

        private const val EXTRA_CONFIG_ID = "config_id"
        private const val CHANNEL_ID = "sthkey_touch_region_editor"
        private const val NOTIFICATION_ID = 0x7C01

        /** 面板宽度 = 屏幕宽的这个比例（再夹到 dp 上下限） */
        private const val PANEL_WIDTH_RATIO = 0.8f

        private const val SEEKBAR_MAX = 1000

        /** 窗口偏移最多量几次 —— 防止「量一次改一次」来回震荡 */
        private const val MAX_MEASURE_ATTEMPTS = 4

        /*
         * ⚠️ 原来这里还有 `EDGE_DP` 与 `MODE_LEFT/TOP/RIGHT/BOTTOM` ——
         * 它们是"拖边框缩放"用的。那个功能已取消（用户实测后要求），
         * 常量也一并删掉:留着会让人以为还有那条交互。
         */

        /** 填充透明度（0..255）—— 淡一点才看得清底下的游戏画面 */
        private const val ALPHA_FILL = 60

        private val COLOR_JOYSTICK = Color.parseColor("#4DA3FF")
        private val COLOR_BADGE = Color.parseColor("#CC000000")
        private val COLOR_PANEL = Color.parseColor("#E6202020")
        private val COLOR_PANEL_BORDER = Color.parseColor("#44FFFFFF")
        private val COLOR_HINT = Color.parseColor("#AAFFFFFF")

    /**
     * 「⚠️ 未绑定」那种**要提醒**的颜色。
     *
     * ⚠️ 与 [COLOR_HINT] 分开是刻意的:提示色是"顺便看一眼"，
     * 而这个要**扎眼** —— 因为它代表"这块区域现在没有任何作用"，
     * 而那种状态在界面上很容易被忽略（方块照样画着、滑块照样能拖），
     * 用户会以为配好了。
     */
    private val COLOR_WARN = Color.parseColor("#FFB74D")
        private val COLOR_PRIMARY = Color.parseColor("#2E7D32")
        private val COLOR_SECONDARY = Color.parseColor("#444444")

        /**
         * 按键区域的调色板 —— 用户要求"每个方块用不同颜色来区分"。
         *
         * ⚠️ 轮盘用固定的蓝色（[COLOR_JOYSTICK]），所以这里从**橙色**开始，
         * 避免轮盘与第一个按键撞色。
         */
        private val PALETTE = listOf(
            Color.parseColor("#FFA726"),
            Color.parseColor("#66BB6A"),
            Color.parseColor("#EF5350"),
            Color.parseColor("#AB47BC"),
            Color.parseColor("#FFEE58"),
            Color.parseColor("#26C6DA"),
            Color.parseColor("#EC407A"),
            Color.parseColor("#8D6E63"),
        )

        /**
         * **当前正在编辑哪个配置**（`null` = 没在编辑）。
         *
         * ============================================================
         * ⚠️ 编辑器要**自报身份**，调用方才能拦住"两个一起编"
         * ============================================================
         * 用户的原话:
         *
         * > 触摸区域编辑，应该**一次只能配置一个配置**的，如果多个配置一起编辑，
         * > 会很杂乱，所以要给个检测，如果当前悬浮窗已经有在编辑触摸区域，
         * > 这时在其他触屏配置里想按配置触摸区域**直接拒绝，并 toast 提醒**
         *
         * ⚠️ 而在同一个配置里再按一次 = **不保存关闭** ——
         * 见 [refuseReason] 的返回值说明。
         *
         * ⚠️ 它由 [onStartCommand] 设置、[onDestroy] 清空 ——
         * **不清空的话会永远拒绝打开**（服务已经死了，这个变量还留着旧 id）。
         */
        @Volatile
        var editingConfigId: String? = null
            private set

        /**
         * 现在能不能为 [configId] 打开编辑器？返回**拒绝的理由**（`null` = 可以开）。
         *
         * ============================================================
         * ⚠️ 三种情况，三种行为
         * ============================================================
         * | 当前状态 | 返回 | 调用方该做什么 |
         * |---|---|---|
         * | 没人在编辑 | `null` | 正常打开 |
         * | **同一个**配置在编辑 | [ALREADY_OPEN] | ⚠️ **不保存关闭**（等同"不保存退出"） |
         * | **别的**配置在编辑 | 一句话 | ⚠️ 拒绝 + toast |
         *
         * ⚠️ 「同一个配置」那条用**特殊标记**而不是一句话:
         * 它要触发的是"关闭"，不是"提示"，两者是不同的行为。
         *
         * ⚠️ 用户说过"改为**双击关闭**即可"这个备选方案，但我选了
         * "**再按一次就关**" —— 见 [ALREADY_OPEN] 的说明。
         */
        fun refuseReason(configId: String): String? {
            val editing = editingConfigId ?: return null
            if (editing == configId) return ALREADY_OPEN
            return "正在编辑另一个配置的触摸区域，请先关掉它"
        }

        /**
         * 「同一个配置已经在编辑」的标记（[refuseReason] 的返回值之一）。
         *
         * ============================================================
         * ⚠️ 为什么是"再按一次就关"而不是"双击关闭"
         * ============================================================
         * 用户给过两个方案:
         *
         * > 再按一次触摸区域编辑的按钮**直接不保存关闭**即可，亦或者，
         * > 如果这样做太直接，改为**双击关闭**即可
         *
         * ⚠️ 我选前者的理由:那个按钮是**普通按钮**，双击是个**隐藏手势** ——
         * 而单击已经能表达"收起"（面板里也有「不保存退出」那条明路）。
         * 加一层"要快按两下"只会让用户以为第一次点没生效。
         *
         * ⚠️ 它带一个空格前缀，绝不会与上面那句提示混淆。
         */
        const val ALREADY_OPEN = " __already_open__"

        /**
         * 打开编辑器。
         *
         * ⚠️ 调用方要先确认有悬浮窗权限 —— 没有的话 `addView` 会抛
         * `BadTokenException`，而那个异常在服务里很难看出是权限问题。
         *
         * ⚠️ 调用方还要先问 [refuseReason]（见那里的三种情况）。
         *
         * ⚠️ 这里**原来还有一个 `focusComponentId` 参数**（"用户点了哪个组件"），
         * 已经删掉 —— 那属于"区域挂在组件上"那个被否掉的模型。
         * 新模型下区域是**配置级的独立实体**，面板自己就能列出全部区域。
         */
        fun start(context: Context, configId: String) {
            val intent = Intent(context, TouchRegionEditorService::class.java)
                .putExtra(EXTRA_CONFIG_ID, configId)
            runCatching { context.startForegroundService(intent) }
                .onFailure { AppLog.w(TAG, "启动编辑器失败：${it.javaClass.simpleName}") }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TouchRegionEditorService::class.java)) }
        }
    }
}

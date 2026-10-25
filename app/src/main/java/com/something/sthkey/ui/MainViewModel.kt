package com.something.sthkey.ui

import android.app.Activity
import android.app.Application
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.something.sthkey.BuildConfig
import com.something.sthkey.capture.accessibility.AccessibilityGrant
import com.something.sthkey.capture.accessibility.SthKeyAccessibilityService
import com.something.sthkey.capture.CaptureController
import com.something.sthkey.capture.OverlayController
import com.something.sthkey.capture.OverlayService
import com.something.sthkey.core.log.AppLog
import com.something.sthkey.core.prefs.AppPrefs
import com.something.sthkey.data.config.ConfigStore
import com.something.sthkey.data.config.GroupStore
import com.something.sthkey.data.permission.PermissionHelper
import com.something.sthkey.domain.capture.CaptureCapabilities
import com.something.sthkey.domain.capture.KeyMode
import com.something.sthkey.domain.capture.resolveCaptureMode
import com.something.sthkey.domain.capture.ResolvedCaptureMode
import com.something.sthkey.domain.capture.ShizukuBridge
import com.something.sthkey.domain.config.ConfigGroup
import com.something.sthkey.domain.config.GroupSwitch
import com.something.sthkey.domain.config.KeyStrokesConfig
import com.something.sthkey.domain.overlay.OverlayLayout
import com.something.sthkey.domain.overlay.OverlayLayouts
import com.something.sthkey.domain.ui.AppearanceColorMode
import com.something.sthkey.domain.ui.AppearancePalette
import com.something.sthkey.domain.ui.BottomBarStyle
import com.something.sthkey.ui.EditMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob




/**
 * 应用级状态持有者。
 *
 * 用 [AndroidViewModel] 而不是纯 ViewModel：需要 Application 上下文去拿
 * SharedPreferences 与探测 root/Shizuku。它跨 Activity 重建存活，
 * 因此"配置正在编辑的内容""能力探测结果"不会因为旋转屏幕而丢失。
 *
 * 第一步只承载 UI 需要的状态；采集服务接入后会在这里增加"服务是否运行"等状态，
 * 并改成从 Service 的连接回调更新（而不是各页面自己判断）。
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppPrefs.get(application)
    private val configStore = ConfigStore.get(application)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /*
     * ============================================================
     * 悬浮窗开关：**每个配置一个**
     * ============================================================
     * 旧版本是一个全局布尔。多悬浮窗之后语义变成"哪几份配置开着悬浮窗"：
     * - 开任意一个 → 启动服务（服务里 [CaptureController.start] 幂等，多开不冲突）
     * - 全部关闭 → 停止服务与采集
     *
     * ⚠️ 这里**只负责改"哪几个开着"**，窗口怎么建、怎么摆全在 OverlayService 里。
     * 让 UI 去管窗口会出现两套增删逻辑，迟早打架。
     */

    /** 当前开着悬浮窗的配置 id 集合 */
    var overlayEnabledIds by mutableStateOf(OverlayLayouts.enabledIds(application))
        private set

    /*
     * ============================================================
     * 配置组合
     * ============================================================
     * ⚠️ 与 `configs` 一样用**版本号**驱动重组，而不是把 `List` 放进
     * `mutableStateOf`:组合是"读的时候再取"的（[groups] 是 getter），
     * 只有版本号变了界面才会重读 —— 这也避免了"两个 List 状态不同步"。
     */
    private val groupStore = GroupStore.get(application)

    /** 组合的版本号。⚠️ 任何增删改之后都要自增，否则界面还是旧的 */
    var groupsVersion by mutableIntStateOf(0)
        private set

    /**
     * 全部组合。
     *
     * ⚠️ 这是个 **getter**，靠 [groupsVersion] 触发重读。
     * 直接写成 `val groups = mutableStateOf(store.all())` 的话，
     * 每次改完都要手动同步两份数据，迟早漏。
     */
    val groups: List<ConfigGroup>
        get() {
            /* 读一次版本号，建立"版本变了就重读"的关系 */
            @Suppress("UNUSED_EXPRESSION")
            groupsVersion
            return groupStore.all()
        }

    /**
     * 让界面重读组合列表。
     *
     * ⚠️ 导入 `.sthgroup` 时组合是 `GroupStore` 直接建出来的，
     * ViewModel 不知道 —— 必须显式叫它重读一次，否则配置页看不到新组合。
     */
    fun reloadGroups() {
        groupsVersion++
    }

    /** 某个配置属于哪个组合（主页/配置页用来区分"组合成员"与普通配置） */
    fun groupOf(configId: String): ConfigGroup? = groupStore.groupOf(configId)

    /** 被任何组合引用过的配置 id（配置页靠它隐藏组合成员） */
    fun groupedConfigIds(): Set<String> = groupStore.groupedConfigIds()

    /** 新建组合 */
    fun createGroup(
        name: String,
        description: String = "",
        memberIds: List<String> = emptyList(),
    ): ConfigGroup {
        val group = groupStore.create(name, description, memberIds)
        groupsVersion++

        /*
         * ⚠️ 新建时如果带了成员，而其中某些成员正好**开着悬浮窗**，
         * 那组合开关一进来就是"开"（因为派生）。这是正确的，不用特殊处理。
         */
        AppLog.i(TAG, "配置组合：新建「${group.name}」，成员 ${group.memberIds.size} 个")
        return group
    }

    fun updateGroup(groupId: String, name: String? = null, description: String? = null) {
        if (groupStore.update(groupId, name, description)) groupsVersion++
    }

    /** 加成员（移动语义：会从其它组合里摘掉） */
    fun addGroupMembers(groupId: String, configIds: List<String>) {
        groupStore.addMembers(groupId, configIds)
        groupsVersion++
    }

    /** 移除成员（配置回到配置页，不删配置） */
    fun removeGroupMembers(groupId: String, configIds: List<String>) {
        groupStore.removeMembers(groupId, configIds)
        groupsVersion++

        /*
         * ⚠️ 被移除的配置如果正开着悬浮窗，**保持开着** ——
         * 移出组合只是改归属，不该顺手把用户开着的窗口关掉。
         */
    }

    /**
     * 解散组合。成员回到配置页，**配置本身不受影响**。
     *
     * ⚠️ 成员悬浮窗的开关状态保持原样（不强制关），
     * 因为"解散"是关于分组的操作，不是关于开关的操作。
     */
    fun dissolveGroup(groupId: String) {
        val removed = groupStore.dissolve(groupId) ?: return
        groupsVersion++
        AppLog.i(TAG, "配置组合：解散「${removed.name}」")
    }

    /**
     * 开/关整个组合。
     *
     * ⚠️ 开的时候必须和"单配置开关"走**同一条路**:
     * 先 `ensureAccessibilityForOverlay()`，再 `OverlayController.enableConfigs(…, 全部成员)`。
     * 顺序反了会被 `OverlayController` 的前置检查挡下，表现成"组合开关点不动"。
     *
     * ⚠️ 关的时候**不调** `enableConfigs`，直接改集合并让服务同步 ——
     * 与单配置关闭那条路一致（集合空了就停服务）。
     */
    fun setGroupOverlayEnabled(group: ConfigGroup, enabled: Boolean) {
        val members = group.memberIds
        if (members.isEmpty()) return

        val context = getApplication<Application>()

        if (enabled) {
            scope.launch {
                ensureAccessibilityForOverlay()

                val ok = OverlayController.enableConfigs(
                    context = context,
                    configIds = members,
                )
                if (!ok) return@launch
                overlayEnabledIds = OverlayLayouts.enabledIds(context)
                AppLog.i(
                    TAG,
                    "悬浮窗开关：组合「${group.name}」→ 开" +
                        "（当前共 ${overlayEnabledIds.size} 个）",
                )
            }
            return
        }

        val next = GroupSwitch.setEnabled(group, overlayEnabledIds, enabled = false)
        if (next == overlayEnabledIds) return
        overlayEnabledIds = OverlayLayouts.setEnabledIds(context, next)
        AppLog.i(
            TAG,
            "悬浮窗开关：组合「${group.name}」→ 关" +
                "（当前共 ${overlayEnabledIds.size} 个）",
        )

        if (overlayEnabledIds.isEmpty()) {
            OverlayService.stop(context)
            AppLog.i(TAG, "已无悬浮窗，停止服务（采集保持运行）")
        } else {
            OverlayService.refresh(context)
        }
    }

    /** 组合是否处于开启状态（派生，见 [GroupSwitch]） */
    fun isGroupOn(group: ConfigGroup): Boolean =
        GroupSwitch.isOn(group, overlayEnabledIds)

    /** 组合里有几个成员开着 */
    fun groupEnabledCount(group: ConfigGroup): Int =
        GroupSwitch.enabledCount(group, overlayEnabledIds)

    /**
     * 全部悬浮窗的本机设置（可触摸 / 基础坐标 / 偏移）。
     *
     * 放成 Compose 状态是为了让编辑弹窗能实时反映改动；
     * 真正的存储在 [OverlayLayouts]（SharedPreferences，**不随配置导出** ——
     * 这些值跟设备屏幕强相关，换台设备就完全不对了）。
     */
    var overlayLayouts by mutableStateOf(OverlayLayouts.all(application))
        private set

    /** 是否已经有任意一个悬浮窗开着 */
    val hasAnyOverlay: Boolean
        get() = overlayEnabledIds.isNotEmpty()

    /**
     * 同步悬浮窗的真实运行状态。
     *
     * ============================================================
     * 为什么需要
     * ============================================================
     * 开关是持久化的，但服务会随进程一起被杀（用户划掉应用、系统回收）。
     * 下次启动如果直接读持久化值，就会显示一个假象 ——
     * "开关是开的，屏幕上却什么都没有"。
     *
     * 所以本次进程启动时对齐一次：**服务没在跑 → 开关全部关掉**。
     *
     * ============================================================
     * ⚠️ 为什么必须是挂起的、而且要看两次（这里踩过坑）
     * ============================================================
     * 只看一次 `isRunning()` 会误判：用户"退出后马上重进"时，
     * 上一个进程的服务实例**还在销毁过程中**，此刻查它仍然是 true，
     * 于是被当成"服务还活着"→ 开关不清 → 用户看到一堆亮着的开关，
     * 而屏幕上什么都没有（开关自己不会恢复，这正是他报告的现象）。
     *
     * 处置：第一次查到"还在跑"时**等一会儿再确认一次**。
     * 真活着的服务会一直活着（它也确实该保留开关，窗口马上就会建出来）；
     * 正在死的那一个等完就没了，于是走"清空"分支。
     *
     * @param force 已同步过也再查一次（运行中检测到服务消失时用）
     * @return 是否发生过修正
     */
    suspend fun syncOverlayState(force: Boolean = false): Boolean {
        if (prefs.overlayStateSynced && !force) return false

        // 第一次查：区分"没在跑"与"不确定"
        if (!OverlayService.isRunning()) return clearOverlayState()

        /*
         * 还在跑。等一下再确认：这段延时专门用来躲开"正在销毁的上一个实例"。
         * 800ms 足够系统走完 onDestroy（那是个同步收尾，不含网络/IO），
         * 又不至于让用户觉得界面卡了一下 —— 何况它是后台协程，不挡首帧。
         */
        delay(SERVICE_SHUTDOWN_GRACE_MS)
        if (!OverlayService.isRunning()) return clearOverlayState()

        // 两次都活着：服务确实在跑，保留用户的开关（窗口会按它们自己恢复）
        prefs.overlayStateSynced = true
        AppLog.i(TAG, "悬浮窗服务正在运行，保留开关集合（${overlayEnabledIds.size} 个）")
        return false
    }

    /** 服务确认已停止：把开关与采集意图一起清干净 */
    private fun clearOverlayState(): Boolean {
        prefs.overlayStateSynced = true
        if (!hasAnyOverlay) {
            AppLog.i(TAG, "悬浮窗服务未在运行，且没有开关记录，无需清理")
            return false
        }

        overlayEnabledIds = OverlayLayouts.setEnabledIds(getApplication(), emptySet())
        AppLog.i(TAG, "悬浮窗服务未在运行，已清空全部悬浮窗开关（上次退出时被系统回收）")
        return true
    }

    /**
     * 设置某一份配置的悬浮窗开关。
     *
     * ============================================================
     * 三种情况，逻辑刻意分成两段
     * ============================================================
     * 1. 开：先确认权限 → 写入集合 → 启动服务（只会真正启动一次）
     * 2. 关掉其中一个，但还有别的开着：只刷新服务，**不停采集** ——
     *    采集是"所有窗口共用"的，停掉会让剩下那些窗口按键不动
     * 3. 关掉最后一个：停服务并清空采集
     *
     * 方法名不叫 setOverlayEnabled：即使属性是 `private set`，
     * Kotlin 仍会为它生成 JVM setter，同名方法会直接撞签名（Platform declaration clash）。
     */
    fun setConfigOverlayEnabled(configId: String, enabled: Boolean) {
        val context = getApplication<Application>()

        if (enabled) {
            /*
             * ============================================================
             * ⚠️⚠️ 开之前先**确保无障碍真的能用**（有 shell 就替用户开）
             * ============================================================
             * 用户的原话:
             *
             * > 授权了 shizuku/root 的时候不要在主页提示无障碍未开启，
             * > 应该**在开启悬浮窗的时候自动用 shell 授权无障碍**
             *
             * ⚠️ 顺序不能反:必须**先开无障碍、再建窗口**。反过来的话
             * [OverlayController.enableConfigs] 会被它自己那道前置检查拦下
             * 直接 return —— 用户看到的就是"开关点不动"（已经报过一次）。
             *
             * ⚠️ 所以放进协程:shell 授权要等系统把服务绑上（可能到一两秒），
             * 在主线程同步等会卡住界面。
             */
            scope.launch {
                ensureAccessibilityForOverlay()

                /*
                 * ⚠️ 走 [OverlayController] 而不是在这里自己写一遍。
                 *
                 * 桌面快捷方式是**不经过界面**的入口，它也做同一件事。
                 * 两份实现的下场是"某个入口漏了一步"，而最容易漏的是
                 * "确保采集在跑" —— 表现是窗口出现了但按键不动，
                 * 看起来像采集坏了，其实只是没人去管它。
                 */
                val ok = OverlayController.enableConfigs(
                    context = context,
                    configIds = listOf(configId),
                )
                if (!ok) return@launch
                overlayEnabledIds = OverlayLayouts.enabledIds(context)
                AppLog.i(TAG, "悬浮窗开关：$configId → 开（当前共 ${overlayEnabledIds.size} 个）")
            }
            return
        }

        val next = OverlayLayouts.setEnabled(context, configId, false)
        overlayEnabledIds = next
        AppLog.i(TAG, "悬浮窗开关：$configId → 关（当前共 ${next.size} 个）")

        if (next.isEmpty()) {
            OverlayService.stop(context)
            /*
             * ⚠️ 这里**不停采集**（原来会）。
             *
             * 采集是应用级的：只要应用活着就一直监听。
             * 关掉最后一个悬浮窗只意味着"不画了"，不代表"不读按键了" ——
             * 用户可能马上又要开，或者想先关掉窗口再插设备。
             */
            AppLog.i(TAG, "已无悬浮窗，停止服务（采集保持运行）")
        } else {
            // 还有窗口开着：让服务把刚关掉的那个拆掉，其余窗口不受影响
            OverlayService.refresh(context)
        }
    }

    /**
     * 确保无障碍服务可用（**有 root / Shizuku 就替用户开**）。
     *
     * ⚠️ 这是"开悬浮窗"与"应用启动"两条路共用的那一步 ——
     * 提成一个函数，免得两处各写一遍（那种不一致很难发现）。
     *
     * ⚠️ 幂等:已经连上就什么都不做，所以可以放心在每次开窗口前调。
     *
     * @return 调用结束后无障碍是否可用
     */
    private suspend fun ensureAccessibilityForOverlay(): Boolean {
        if (SthKeyAccessibilityService.isConnected()) return true

        val root = capabilities.rootAvailable
        val shizuku = capabilities.shizukuReady
        if (!root && !shizuku) {
            /*
             * ⚠️ 没有 shell 可借 —— 只能让用户自己去系统设置点。
             * 那个提示交给 [OverlayController]（它会跳设置页），这里只记日志。
             */
            AppLog.w(TAG, "无障碍未开启，且没有 root / Shizuku 可代为开启")
            return false
        }

        AppLog.i(TAG, "开悬浮窗前自动用 ${if (root) "root" else "Shizuku"} 开启无障碍")
        val result = AccessibilityGrant.grant(
            context = getApplication(),
            useRoot = root,
        )
        AppLog.i(TAG, "无障碍授权结果：${result.detail}")

        /* ⚠️ 授权后刷新能力快照，界面上的无障碍状态才会跟着变 */
        runCatching { CaptureController.refreshCapabilities() }
        /*
         * ⚠️ 提示**不在这里弹**。
         *
         * 无障碍最常被打开的时候是"首次启动引导页"，那时还没人想着开悬浮窗；
         * 等用户真去开悬浮窗时 `isConnected()` 早就 true，这个函数直接返回了 ——
         * 所以"在这里弹 Toast"实际上永远不会触发（第一版就是这么错的）。
         *
         * 现在由 [AccessibilityGrant.notice] 在**唯一的成功点**发事件，UI 层收集后弹。
         */
        return result.ok
    }

    /**
     * 一次关掉全部悬浮窗（调试页用）。
     *
     * 走 [OverlayController.disableAll]：它多做了一件容易漏的事 —— 清空开关集合。
     */
    fun clearAllOverlays() {
        OverlayController.disableAll(getApplication())
        overlayEnabledIds = OverlayLayouts.enabledIds(getApplication())
        AppLog.i(TAG, "已关闭全部悬浮窗（调试页操作）")
    }

    /** 只清集合、不停服务；采集的停止由服务发现"没有窗口"后自己完成 */
    private fun clearEnabled() {
        val context = getApplication<Application>()
        overlayEnabledIds = OverlayLayouts.setEnabledIds(context, emptySet())
        OverlayService.stop(context, clearEnabledIds = false)
    }

    /** 让悬浮窗服务重建窗口（开关集合没变、只有内容变了时用） */
    fun refreshOverlay() {
        if (!hasAnyOverlay) return
        OverlayService.refresh(getApplication())
    }

    /* ============================================================
     * 单个悬浮窗的本机设置（可触摸 / 可移出屏幕外 / 偏移）
     * ============================================================ */

    /**
     * 某个配置的悬浮窗设置。
     *
     * ⚠️ 没有记录时返回**默认值**而不是 null:调用方（编辑弹窗）拿到的
     * 永远是一个可以直接用的对象，不用每处都写 `?: OverlayLayout()`。
     */
    fun overlayLayoutOf(configId: String): OverlayLayout =
        overlayLayouts[configId] ?: OverlayLayout()

    private fun updateOverlayLayout(
        configId: String,
        transform: (OverlayLayout) -> OverlayLayout,
    ) {
        OverlayLayouts.update(getApplication(), configId, transform)
        overlayLayouts = OverlayLayouts.all(getApplication())
        /*
         * ⚠️ 改完立刻让窗口同步:可触摸 / 偏移这类设置**必须马上生效**，
         * 否则用户调完看不到变化，会以为没保存。
         */
        refreshOverlay()
    }

    fun setOverlayTouchable(configId: String, touchable: Boolean) {
        updateOverlayLayout(configId) { it.copy(touchable = touchable) }
        AppLog.i(TAG, "悬浮窗可触摸：$configId → $touchable")
    }

    fun setOverlayMovableOffScreen(configId: String, movable: Boolean) {
        updateOverlayLayout(configId) { it.copy(movableOffScreen = movable) }
        AppLog.i(TAG, "悬浮窗可移出屏幕外：$configId → $movable")
    }

    fun setOverlayOffset(configId: String, offsetX: Int, offsetY: Int) {
        updateOverlayLayout(configId) { it.copy(offsetX = offsetX, offsetY = offsetY) }
    }

    fun resetOverlayPosition(configId: String) {
        OverlayLayouts.resetPosition(getApplication(), configId)
        overlayLayouts = OverlayLayouts.all(getApplication())
        refreshOverlay()
    }

    /* ============================================================
     * 外观（明暗模式 / 底栏 / 主色 / 色彩风格）
     * ============================================================
     * ⚠️ 这一组都是"改完立刻反映到界面"的:先写偏好、再更新 Compose 状态。
     * 顺序反过来会让界面先变、偏好后写 —— 中途进程被杀就丢了设置。
     */

    var appearanceColorMode by mutableStateOf(AppearanceColorMode.fromId(prefs.appearanceColorMode))
        private set

    fun applyAppearanceColorMode(mode: AppearanceColorMode) {
        prefs.appearanceColorMode = mode.id
        appearanceColorMode = mode
        AppLog.i(TAG, "外观：明暗模式 → ${mode.label}")
    }

    var bottomBarStyle by mutableStateOf(BottomBarStyle.fromId(prefs.bottomBarStyle))
        private set

    fun applyBottomBarStyle(style: BottomBarStyle) {
        prefs.bottomBarStyle = style.id
        bottomBarStyle = style
        AppLog.i(TAG, "外观：底栏样式 → ${style.label}")
    }

    var seedColor by mutableStateOf(prefs.seedColor)
        private set

    fun applySeedColor(argb: Int) {
        prefs.seedColor = argb
        seedColor = argb
        AppLog.i(
            TAG,
            if (argb == 0) "外观：主色 → 跟随壁纸" else "外观：主色 → #%08X".format(argb),
        )
    }

    var appearancePalette by mutableStateOf(AppearancePalette.fromId(prefs.appearancePalette))
        private set

    fun applyAppearancePalette(palette: AppearancePalette) {
        prefs.appearancePalette = palette.id
        appearancePalette = palette
        AppLog.i(TAG, "外观：色彩风格 → ${palette.label}")
    }

    /* ============================================================
     * 采集通道能力（root / Shizuku / 无障碍）
     * ============================================================ */

    var requestedMode by mutableStateOf(prefs.keyMode)
        private set

    var capabilities by mutableStateOf(CaptureController.capabilities.value)
        private set

    /** 是否正在探测能力（界面用它显示进度） */
    var isProbing by mutableStateOf(false)
        private set

    /** 探测进行中又被要求再探一次时置位，结束后补一轮 */
    private var probeAgain = false

    val resolvedMode: ResolvedCaptureMode
        get() = resolveCaptureMode(requestedMode, capabilities)

    var startupCheckDone by mutableStateOf(false)
        private set

    /**
     * 启动自检发现的问题（`null` = 没发现）。
     *
     * ⚠️ 只在"既没有无障碍、也没有 shell"时给提示:那两种情况都还能救，
     * 提前吓唬用户没有意义。
     */
    var startupCheckIssue by mutableStateOf<String?>(null)
        private set

    private fun evaluateStartupIssue(): String? {
        if (SthKeyAccessibilityService.isConnected()) return null

        val caps = capabilities
        if (caps.rootAvailable || caps.shizukuReady) return null

        return "无障碍服务没开，也没有 root / Shizuku —— 现在开不了悬浮窗"
    }

    /**
     * 重新探测采集能力（root / Shizuku / 无障碍）。
     *
     * ⚠️ 探测是**串行**的:进行中再调用只置一个标记，结束后自动补一轮。
     * 并发探测会让"root 可用"这类结论互相覆盖，最后落地的可能是旧结论。
     */
    fun refreshCapabilities(force: Boolean = false) {
        if (isProbing) {
            probeAgain = true
            AppLog.d(TAG, "能力探测进行中，已标记结束后补测一次（force=$force）")
            return
        }

        isProbing = true
        AppLog.i(TAG, "开始检测采集能力（root / Shizuku），force=$force")
        scope.launch {
            try {
                CaptureController.refreshCapabilities()
            } finally {
                isProbing = false
            }

            capabilities = CaptureController.capabilities.value
            startupCheckIssue = evaluateStartupIssue()

            if (probeAgain) {
                probeAgain = false
                refreshCapabilities()
            }
        }
    }

    fun restartCapture() {
        AppLog.i(TAG, "手动重启采集")
        CaptureController.stop()
        CaptureController.start()
    }

    /**
     * 申请 Shizuku 权限。
     *
     * ⚠️ 必须传 Activity（而不是 application context）:
     * Shizuku 的授权对话框要挂在一个真实窗口上，用 application context 会直接失败。
     */
    fun requestShizukuPermission(activity: Activity?) {
        scope.launch {
            val ok = ShizukuBridge.requestPermission(activity)
            AppLog.i(TAG, "Shizuku 权限申请结果：$ok")
            if (ok) refreshCapabilities(force = true)
        }
    }

    /**
     * 「用 shell 立刻重试打开无障碍」（调试页 / 引导页的按钮）。
     *
     * ⚠️ 它不是"唯一路径"——开悬浮窗时也会自动试一次；
     * 这个函数的价值是**强制重绑**:服务卡死时 `settings` 里明明开着、
     * `isConnected()` 却是 false，只有先摘掉再写回去才救得回来。
     */
    fun grantAccessibilityByShell() {
        scope.launch {
            val root = capabilities.rootAvailable
            val shizuku = capabilities.shizukuReady
            if (!root && !shizuku) {
                AppLog.w(TAG, "没有 root 或 Shizuku，无法用 shell 开启无障碍")
                return@launch
            }

            AppLog.i(TAG, "手动请求用 shell 开启无障碍（${if (root) "root" else "Shizuku"}）")
            val result = AccessibilityGrant.grant(
                context = getApplication(),
                useRoot = root,
                /* ⚠️ 用户主动点，就用"强制重绑"—— 卡死时它能救回来 */
                forceRebind = true,
            )
            AppLog.i(TAG, "无障碍授权结果：${result.detail}（连上=${result.connected}）")
            refreshCapabilities(force = true)
        }
    }

    /**
     * 切换采集通道。
     *
     * ⚠️ 改完会**重启采集**（如果本来在跑）:用户特意切通道，
     * 就应该立刻按新通道生效，而不是等下次开关悬浮窗 ——
     * 否则界面显示的是新通道、实际还跑着旧通道，最难排查。
     */
    fun applyRequestedMode(mode: KeyMode) {
        if (requestedMode == mode) {
            AppLog.d(TAG, "采集模式未变化：${mode.label}")
            return
        }

        requestedMode = mode
        prefs.keyMode = mode

        val wasCapturing = CaptureController.enabled.value
        AppLog.i(TAG, "采集模式切换为：${mode.label}（重启采集：$wasCapturing）")

        if (wasCapturing) {
            CaptureController.stop()
            CaptureController.start()
        }
    }

    /* ============================================================
     * 配置（增删改查）
     * ============================================================ */

    /**
     * 配置列表的版本号。
     *
     * ⚠️ 界面靠它重读列表:配置对象本身不是 Compose 状态，
     * 改完之后**必须自增**，否则列表看起来"没保存"。
     */
    var configVersion by mutableIntStateOf(0)
        private set

    /** 强制刷新配置列表（外部改了存储之后用） */
    fun refreshConfigs() {
        configVersion++
    }

    fun updateConfigById(
        id: String,
        transform: (KeyStrokesConfig) -> KeyStrokesConfig,
    ): KeyStrokesConfig? {
        val updated = configStore.updateById(id, transform) ?: return null
        configVersion++
        refreshOverlayIfEnabled(id)
        return updated
    }

    fun saveConfig(config: KeyStrokesConfig) {
        configStore.upsert(config)
        configVersion++
        refreshOverlayIfEnabled(config.id)
    }

    /**
     * 只有这份配置的窗口**正开着**才需要重建。
     *
     * ⚠️ 不看这个的话，编辑一份没开着的配置也会重刷窗口，
     * 屏幕上其它窗口会闪一下。
     */
    private fun refreshOverlayIfEnabled(configId: String) {
        if (configId in overlayEnabledIds) refreshOverlay()
    }

    /** 存储层被外部改过（导入配置 / 导入组合）后叫界面重读一次 */
    fun reloadConfigs() {
        configVersion++
    }

    fun allConfigs(): List<KeyStrokesConfig> = configStore.all()

    fun firstConfig(): KeyStrokesConfig = configStore.first()

    fun findConfig(id: String): KeyStrokesConfig? = configStore.find(id)

    /**
     * 调试页要显示"现在生效的那份配置"：优先开着的窗口，一份都没开就退回第一份。
     *
     * ⚠️ 这里**只用于展示**，不影响哪个窗口该显示（那完全由开关集合决定）。
     */
    fun debugConfig(): KeyStrokesConfig =
        configStore.all().firstOrNull { it.id in overlayEnabledIds } ?: configStore.first()

    fun moveConfig(from: Int, to: Int) {
        configStore.move(from, to)
        configVersion++
    }

    fun duplicateConfig(sourceId: String? = null, newName: String? = null): KeyStrokesConfig {
        val created = configStore.duplicate(sourceId, newName)
        configVersion++
        return created
    }

    fun createConfig(
        name: String,
        description: String,
        styleId: String,
    ): KeyStrokesConfig {
        val created = configStore.create(name, description, styleId)
        configVersion++
        return created
    }

    fun renameConfig(id: String, newName: String): Boolean {
        val ok = configStore.rename(id, newName)
        if (ok) configVersion++
        return ok
    }

    fun deleteConfig(id: String): Boolean {
        if (!configStore.delete(id)) return false
        configVersion++

        /*
         * 删掉配置要顺带清掉它的三样本机数据：
         * 1. 悬浮窗开关 —— 不清的话"全关才停监听"会被一个永远开不起来的 id 占住；
         * 2. 悬浮窗位置与设置 —— 不清的话会一直攒着孤儿记录；
         * 3. **配置组合里的引用** —— 不清的话组合会永远留着一个幽灵成员
         *    （表现为"组合说有 3 个配置，展开只有 2 个"，而且那个成员删不掉）。
         *
         * 都用"现存的 id 集合"一次清干净，免得每删一份配置就写一遍"删这个删那个"。
         */
        val existing = configStore.all().map { it.id }.toSet()
        OverlayLayouts.pruneMissing(getApplication(), existing)
        overlayEnabledIds = OverlayLayouts.enabledIds(getApplication())
        overlayLayouts = OverlayLayouts.all(getApplication())

        if (groupStore.pruneMissing(existing)) {
            groupsVersion++
        }

        // 窗口的增删交给服务：它按最新的开关集合自己同步
        refreshOverlay()
        return true
    }

    /** 把一份配置恢复成出厂值（内容重置，id 与开关都不变） */
    fun resetConfig(id: String) {
        configStore.resetToDefault(id)
        configVersion++
        refreshOverlayIfEnabled(id)
    }

    /* ============================================================
     * 配置编辑方式 / 配置列表排版
     * ============================================================
     * ⚠️ 这些都是**应用级偏好**（存 `AppPrefs`），不属于任何一份配置 ——
     * 所以导出配置时不会把"你怎么排版"带给别人。
     */

    var editMode by mutableStateOf(prefs.editMode)
        private set

    fun updateEditMode(mode: EditMode) {
        if (editMode == mode) return
        editMode = mode
        prefs.editMode = mode
        AppLog.i(TAG, "配置编辑方式切换为：${mode.label}")
    }

    var configListShowDescription by mutableStateOf(prefs.configListShowDescription)
        private set

    fun updateConfigListShowDescription(show: Boolean) {
        if (configListShowDescription == show) return
        configListShowDescription = show
        prefs.configListShowDescription = show
    }

    var configListCompactActions by mutableStateOf(prefs.configListCompactActions)
        private set

    fun updateConfigListCompactActions(compact: Boolean) {
        if (configListCompactActions == compact) return
        configListCompactActions = compact
        prefs.configListCompactActions = compact
    }

    var configListColumns by mutableStateOf(prefs.configListColumns)
        private set

    fun updateConfigListColumns(count: Int) {
        val clamped = count.coerceIn(1, 6)
        if (configListColumns == clamped) return
        configListColumns = clamped
        prefs.configListColumns = clamped
    }

    /* ============================================================
     * 引导流程 / 更新公告
     * ============================================================ */

    fun markSetupCompleted() {
        prefs.setupCompleted = true
        AppLog.i(TAG, "引导流程已完成")
    }

    fun resetSetupCompleted() {
        prefs.setupCompleted = false
        AppLog.i(TAG, "已重置引导标记，下次启动将重新进入引导页")
    }

    val isSetupCompleted: Boolean
        get() = prefs.setupCompleted

    /**
     * 是否需要弹更新公告。
     *
     * ⚠️ 用 `BuildConfig.VERSION_NAME` 而不是写死的字符串:
     * 版本号一改，公告自动对每个人重新弹一次，不需要两处同步。
     */
    val shouldShowAnnouncement: Boolean
        get() = prefs.announcementVersion != BuildConfig.VERSION_NAME

    fun markAnnouncementSeen() {
        prefs.announcementVersion = BuildConfig.VERSION_NAME
        AppLog.i(TAG, "已确认 v${BuildConfig.VERSION_NAME} 更新公告")
    }

    /*
     * ============================================================
     * 初始化
     * ============================================================
     * ⚠️ 这段**放在文件末尾**（所有属性声明之后）:
     * `init` 按声明顺序执行，放在前面时下面那些 `by mutableStateOf` 还没初始化，
     * 协程里一读就是 null/0。Kotlin 不报错，运行起来才炸。
     */
    init {
        AppLog.i(TAG, "应用启动，当前模式设置：${requestedMode.label}")

        /*
         * ① 上次退出时服务可能被系统回收 —— 开关集合与实际状态对不上，先对齐一次。
         */
        scope.launch { syncOverlayState() }

        /*
         * ② 采集能力（root / Shizuku / 无障碍）是**外部状态**，持续同步进来。
         * ⚠️ 必须用收集而不是"每次用完手动赋一次":
         * Shizuku 可能在应用运行中被授权或断开，手动赋值的那些点覆盖不到。
         */
        scope.launch {
            CaptureController.capabilities.collect { capabilities = it }
        }

        /*
         * ③ 启动自检:等首次能力探测跑完，再判断"现在能不能开悬浮窗"。
         *
         * ⚠️ 必须**等探测结束**再判断 —— 探测是异步的，
         * 立刻判会永远判成"没 root 也没 Shizuku"，然后给用户一个假警报。
         */
        scope.launch {
            val probeJob = launch {
                runCatching { CaptureController.refreshCapabilities() }
                    .onFailure {
                        AppLog.w(TAG, "启动时探测能力失败：${it.javaClass.simpleName}")
                    }
            }
            probeJob.join()

            startupCheckIssue = evaluateStartupIssue()
            startupCheckDone = true
            AppLog.i(TAG, "启动校验完成：${startupCheckIssue ?: "一切正常"}")
        }
    }

    private companion object {
        /** 停服务后等它真正退出的宽限期（见 `syncOverlayState`） */
        const val SERVICE_SHUTDOWN_GRACE_MS = 800L

        const val TAG = "App"
    }
}

package com.something.sthkey.ui

import android.app.Activity
import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.something.sthkey.BuildConfig
import com.something.sthkey.capture.CaptureController
import com.something.sthkey.capture.OverlayController
import com.something.sthkey.capture.OverlayService
import com.something.sthkey.core.log.AppLog
import com.something.sthkey.core.prefs.AppPrefs
import com.something.sthkey.data.config.ConfigStore
import com.something.sthkey.data.permission.PermissionHelper
import com.something.sthkey.domain.capture.CaptureCapabilities
import com.something.sthkey.domain.capture.KeyMode
import com.something.sthkey.domain.capture.ResolvedCaptureMode
import com.something.sthkey.domain.capture.ShizukuBridge
import com.something.sthkey.domain.capture.resolveCaptureMode
import com.something.sthkey.domain.config.KeyStrokesConfig
import com.something.sthkey.domain.overlay.OverlayLayout
import com.something.sthkey.domain.overlay.OverlayLayouts
import com.something.sthkey.ui.EditMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
            if (!ok) return
            overlayEnabledIds = OverlayLayouts.enabledIds(context)
            AppLog.i(TAG, "悬浮窗开关：$configId → 开（当前共 ${overlayEnabledIds.size} 个）")
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
     * 一次关掉全部悬浮窗（调试页用）。
     *
     * 走 [OverlayController.disableAll]：桌面快捷方式的"一键关闭"用的是
     * **同一份**逻辑 —— 它多做了一件容易漏的事：清空集合。
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

    /** 让正在运行的悬浮窗重新读取配置与本机设置（改缩放、改偏移、改可触摸后调用） */
    fun refreshOverlay() {
        if (!hasAnyOverlay) return
        OverlayService.refresh(getApplication())
    }

    /*
     * ============================================================
     * 单个悬浮窗的本机设置
     * ============================================================
     * 编辑弹窗只通过这些方法改值，不直接碰 SharedPreferences ——
     * 那样 UI 与存储各写一份，改完界面不会更新（也不会有日志）。
     */

    /** 某个配置的悬浮窗设置；没有记录时返回默认值（可触摸、无偏移） */
    fun overlayLayoutOf(configId: String): OverlayLayout =
        overlayLayouts[configId] ?: OverlayLayout()

    /** 统一入口：改一份设置 → 刷新状态 → 通知服务按新值调整窗口 */
    private fun updateOverlayLayout(configId: String, transform: (OverlayLayout) -> OverlayLayout) {
        OverlayLayouts.update(getApplication(), configId, transform)
        overlayLayouts = OverlayLayouts.all(getApplication())
        refreshOverlay()
    }

    /**
     * 设置该悬浮窗是否可触摸。
     *
     * `false` = 纯贴图：不可拖动、也不拦截点击（窗口仍在、仍渲染）。
     */
    fun setOverlayTouchable(configId: String, touchable: Boolean) {
        updateOverlayLayout(configId) { it.copy(touchable = touchable) }
        AppLog.i(TAG, "悬浮窗可触摸：$configId → $touchable")
    }

    /**
     * 设置「可移出屏幕外」（默认关）。
     *
     * ============================================================
     * ⚠️ 它一次改两件事，靠 [updateOverlayLayout] 的重建兜住
     * ============================================================
     * 那个开关同时影响:
     * 1. **窗口标志**（`FLAG_LAYOUT_NO_LIMITS`，见 `OverlayService.windowFlags`）；
     * 2. **拖拽时的坐标范围**（见 `OverlayService` 里 `clampOrFree` 的调用点）。
     *
     * 两者都必须按新值生效 —— 而 [updateOverlayLayout] 结尾会
     * `refreshOverlay()`，正好把窗口重建一遍，所以这里不需要额外做什么。
     *
     * ⚠️ 只改数据、不重建的话，表现是"开关打开了但拖不出去"，
     * 那正是这类开关最容易出的问题。
     */
    fun setOverlayMovableOffScreen(configId: String, movable: Boolean) {
        updateOverlayLayout(configId) { it.copy(movableOffScreen = movable) }
        AppLog.i(TAG, "悬浮窗可移出屏幕外：$configId → $movable")
    }

    /**
     * 设置偏移量。
     *
     * **只改偏移，不碰基础坐标** —— 拖动改的是后者。
     * 两者相加才是最终位置，所以"手动拖动不会影响滑块的值"是天然成立的。
     */
    fun setOverlayOffset(configId: String, offsetX: Int, offsetY: Int) {
        updateOverlayLayout(configId) { it.copy(offsetX = offsetX, offsetY = offsetY) }
    }

    /** 重置位置与偏移：基础坐标回到"没拖过"、偏移归零 */
    fun resetOverlayPosition(configId: String) {
        OverlayLayouts.resetPosition(getApplication(), configId)
        overlayLayouts = OverlayLayouts.all(getApplication())
        refreshOverlay()
    }

    /*
     * ============================================================
     * 采集模式与状态
     * ============================================================
     */

    /** 用户选择的模式（可能为"自动"） */
    var requestedMode by mutableStateOf(prefs.keyMode)
        private set

    /**
     * 当前环境能力快照。
     *
     * ⚠️ 必须是 Compose **能观察到**的状态量，不能写成 `StateFlow.value` 的 getter。
     *
     * 之前就是 getter，于是出现了这个 bug：探测结果确实写进了采集层的
     * StateFlow，但界面读到的只是"取值那一刻"的快照，之后永远不会重组 ——
     * 表现就是"root / Shizuku 明明授权了，引导页一直显示未授权"。
     * 切后台再回来也没用：回到前台只刷新了悬浮窗权限那两个状态量，
     * 值没变时 Compose 会直接跳过重组，于是又读到同一份旧快照。
     *
     * 现在由 [init] 里的一次订阅把采集层的状态同步过来，探测结果一变界面就跟着变。
     */
    var capabilities: CaptureCapabilities by mutableStateOf(CaptureController.capabilities.value)
        private set

    var isProbing by mutableStateOf(false)
        private set

    /** 按用户选择 + 能力快照解析出的实际模式 */
    val resolvedMode: ResolvedCaptureMode
        get() = resolveCaptureMode(requestedMode, capabilities)

    init {
        AppLog.i(TAG, "应用启动，当前模式设置：${requestedMode.label}")

        /*
         * 对齐一次悬浮窗的真实状态，避免显示"已开启"但其实窗口并不存在。
         *
         * 放在协程里：它内部可能要等一小会儿再确认服务是不是真的活着
         * （见 syncOverlayState 的说明），不该卡住首帧。
         */
        scope.launch { syncOverlayState() }

        /*
         * 把采集层的能力快照同步成 Compose 状态。
         *
         * 采集层探测完（启动采集、手动重测、切通道）都会更新那个 StateFlow，
         * 这里跟着写进 [capabilities]，界面才能看到变化。
         */
        scope.launch {
            CaptureController.capabilities.collect { capabilities = it }
        }
    }

    /**
     * 探测进行中又收到请求时的补测标记。
     *
     * 直接丢掉那次请求会漏刷新：用户从 Shizuku 授权页返回时正好可能
     * 撞上一次还在跑的慢探测（root 探测要等 `su` 弹窗，十几秒都可能），
     * 而那次探测是在授权**之前**发起的，结果必然是旧的。
     * 所以记下来，等当前这次结束再补一次。
     */
    private var probeAgain = false

    /**
     * 重新探测采集能力（root + Shizuku 授权状态）。
     *
     * 实际探测交给 [CaptureController]（采集层自己也需要这些结果），
     * 这里只负责转成 UI 能感知的"进行中"状态。
     *
     * 忙碌态跟着**真实探测时长**走：root 探测要跑 `su`，首次会等用户在
     * 授权弹窗上点确认，好几秒很正常。之前写死 400ms 就清掉，
     * 界面会假装"已经检完了"，用户再点一次还会被当成重复请求挡掉。
     *
     * @param force 保留参数以兼容调用方；探测本身很轻，每次都真探
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
                // 异常也要把忙碌态收回来，否则按钮会永远停在"检测中"
                isProbing = false
            }

            if (probeAgain) {
                probeAgain = false
                refreshCapabilities()
            }
        }
    }

    /**
     * 重启采集。
     *
     * 调试页用：改完设置（通道、权限）后不想去动悬浮窗开关，
     * 直接重来一遍最快，也能顺便验证"冷启动"是否正常。
     */
    fun restartCapture() {
        AppLog.i(TAG, "手动重启采集")
        CaptureController.stop()
        CaptureController.start()
    }

    /**
     * 申请 Shizuku 授权。
     *
     * 会挂起等待用户在系统对话框上做出选择（或超时），因此放在协程里执行。
     *
     * Shizuku 的授权对话框需要**前台 Activity** 才能真正弹出来，
     * 而 ViewModel 不该持有 Activity 引用（配置变更会让它失效并泄漏），
     * 所以这里由 UI 层在调用时临时提供。
     *
     * @param activity 当前前台 Activity；为 null 时会退化为打开 Shizuku 应用
     */
    fun requestShizukuPermission(activity: Activity?) {
        scope.launch {
            val granted = ShizukuBridge.requestPermission(activity)
            AppLog.i(TAG, if (granted) "Shizuku 授权已授予" else "Shizuku 授权未完成")
            refreshCapabilities(force = true)
        }
    }

    /**
     * 设置用户期望的采集模式。
     *
     * 改完会**重启采集**（如果本来在跑）：用户特意切通道，
     * 就应该立刻按新通道生效，而不是等下次开关悬浮窗 ——
     * 否则界面显示的是新通道、实际还跑着旧通道，最难排查。
     *
     * 命名沿用"动词在前"的写法：即使属性是 `private set`，
     * Kotlin 仍会为它生成 JVM setter，同名方法会直接撞签名
     * （Platform declaration clash，见 [setConfigOverlayEnabled]）。
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

    /*
     * ============================================================
     * 配置
     * ============================================================
     * ⚠️ 这里**没有**"当前生效的配置"了（`activeConfig` 已删除）。
     * 每一份配置各自有悬浮窗开关，屏幕上显示什么由它们决定，
     * "当前是哪一份"不再对应任何真实状态。见 ConfigStore 的说明。
     */

    /**
     * 配置集合的版本号，每次增删改/排序后 +1。
     *
     * 配置列表页与主页的列表都是**每次重组现取**（`allConfigs()`），
     * 所以需要一个能看见"增删改"的信号：改名与调整顺序不会改变列表长度，
     * 也就不会从别处触发重组，只靠列表本身会出现"改完名字界面没刷新"。
     */
    var configVersion by mutableIntStateOf(0)
        private set

    /** 推一下版本号，让依赖配置内容的界面重新取数 */
    fun refreshConfigs() {
        configVersion++
    }

    /**
     * 按 id 修改某一份配置（编辑页用）。
     *
     * @return 修改后的配置；找不到时返回 null
     */
    fun updateConfigById(
        id: String,
        transform: (KeyStrokesConfig) -> KeyStrokesConfig,
    ): KeyStrokesConfig? {
        val updated = configStore.updateById(id, transform) ?: return null
        configVersion++
        // 这份配置正开着悬浮窗的话，窗口要立刻跟着变
        refreshOverlayIfEnabled(id)
        return updated
    }

    /**
     * 写入指定配置（保存生效模式用：整份草稿一次提交）。
     *
     * 界面在处理完自己的收尾（返回上一页等）之后**可能还需要**刷新悬浮窗，
     * 所以这里返回"要不要刷"，而不是让调用方去猜。
     */
    fun saveConfig(config: KeyStrokesConfig) {
        configStore.upsert(config)
        configVersion++
        refreshOverlayIfEnabled(config.id)
    }

    /** 只有这份配置正开着悬浮窗时才值得打扰服务 */
    private fun refreshOverlayIfEnabled(configId: String) {
        if (configId in overlayEnabledIds) refreshOverlay()
    }

    /**
     * 重读配置列表。
     *
     * 导入配置包时由 [com.something.sthkey.data.config.ConfigPackageManager]
     * 直接写入仓库（它在 IO 线程、且要处理字体落盘），
     * 完成后调用这里让界面同步 —— 与 UI 主动修改的路径分开，避免职责混淆。
     */
    fun reloadConfigs() {
        configVersion++
    }

    /** 所有配置（列表页用；每次调用取最新，保证增删/排序后一致） */
    fun allConfigs(): List<KeyStrokesConfig> = configStore.all()

    /** 第一份配置（编辑页 id 失效、CPS 统计没有窗口开着时的兜底） */
    fun firstConfig(): KeyStrokesConfig = configStore.first()

    fun findConfig(id: String): KeyStrokesConfig? = configStore.find(id)

    /**
     * 调试页"模拟按键"用哪一份配置的键位。
     *
     * 优先取**第一个开着悬浮窗的配置**：模拟按键的意义就是看屏幕上的窗口动没动，
     * 那就该用屏幕上那份配置的键位。一个窗口都没开时退回到第一份配置。
     */
    fun debugConfig(): KeyStrokesConfig {
        val all = configStore.all()
        return all.firstOrNull { it.id in overlayEnabledIds } ?: configStore.first()
    }

    /**
     * 调整配置顺序（列表页拖动排序）。
     *
     * @param from 原索引
     * @param to   目标索引
     */
    fun moveConfig(from: Int, to: Int) {
        configStore.move(from, to)
        // 顺序变化不影响配置内容，因此必须显式推版本号触发列表刷新
        configVersion++
    }

    fun duplicateConfig(sourceId: String? = null, newName: String? = null): KeyStrokesConfig {
        val created = configStore.duplicate(sourceId, newName)
        configVersion++
        return created
    }

    /**
     * 新建一份配置（参数为出厂默认 + 指定样式）。
     *
     * 与 [duplicateConfig] 的区别：新建是"从零开始"，复制是"继承来源"。
     *
     * 新建出来的配置悬浮窗开关**默认是关的**：用户刚建好还没调过参数，
     * 直接弹一个窗口出来只会挡住屏幕。要显示就去主页打开它的开关。
     */
    fun createConfig(
        name: String,
        description: String,
        styleId: String,
    ): KeyStrokesConfig {
        val created = configStore.create(name, description, styleId)
        configVersion++
        return created
    }

    /**
     * 重命名配置；内置配置会被拒绝并返回 false
     */
    fun renameConfig(id: String, newName: String): Boolean {
        val ok = configStore.rename(id, newName)
        if (ok) configVersion++
        return ok
    }

    fun deleteConfig(id: String): Boolean {
        if (!configStore.delete(id)) return false
        configVersion++

        /*
         * 删掉配置要顺带清掉它的两样本机数据：
         * 1. 悬浮窗开关 —— 不清的话"全关才停监听"会被一个永远开不起来的 id 占住；
         * 2. 悬浮窗位置与设置 —— 不清的话会一直攒着孤儿记录。
         *
         * 两者都放在 [OverlayLayouts.pruneMissing] 里，用"现存的 id 集合"一次清干净，
         * 免得每删一份配置就写一遍"删这个删那个"。
         */
        val existing = configStore.all().map { it.id }.toSet()
        OverlayLayouts.pruneMissing(getApplication(), existing)
        overlayEnabledIds = OverlayLayouts.enabledIds(getApplication())
        overlayLayouts = OverlayLayouts.all(getApplication())

        // 窗口的增删交给服务：它按最新的开关集合自己同步
        refreshOverlay()
        return true
    }

    fun resetConfig(id: String) {
        configStore.resetToDefault(id)
        configVersion++
        refreshOverlayIfEnabled(id)
    }

    /*
     * ============================================================
     * 配置编辑方式
     * ============================================================
     */

    /** 编辑页的生效方式：实时生效 / 保存生效，在设置页切换 */
    var editMode by mutableStateOf(prefs.editMode)
        private set

    fun updateEditMode(mode: EditMode) {
        if (editMode == mode) return
        editMode = mode
        prefs.editMode = mode
        AppLog.i(TAG, "配置编辑方式切换为：${mode.label}")
    }

    /*
     * ============================================================
     * 配置列表的排版（设置页可调）
     * ============================================================
     * 用户反馈"配置太多，列表看着杂乱"，所以列表长什么样交给用户决定。
     *
     * 放在 view-model 里而不是像画布边框那样直接读写 `AppPrefs`：
     * 这三项**渲染在同一页**（设置页），改完要立刻看到开关/滑块的反馈，
     * 用 Compose 状态最直接。而配置列表页在回到它时重新读一次偏好
     * （见 `ConfigListScreen` 的生命周期监听）。
     */

    /** 是否在配置卡片上显示描述 */
    var configListShowDescription by mutableStateOf(prefs.configListShowDescription)
        private set

    fun updateConfigListShowDescription(show: Boolean) {
        if (configListShowDescription == show) return
        configListShowDescription = show
        prefs.configListShowDescription = show
    }

    /** 是否把导出/复制/删除收进「更多」菜单 */
    var configListCompactActions by mutableStateOf(prefs.configListCompactActions)
        private set

    fun updateConfigListCompactActions(compact: Boolean) {
        if (configListCompactActions == compact) return
        configListCompactActions = compact
        prefs.configListCompactActions = compact
    }

    /** 配置列表一行显示几个（1..6） */
    var configListColumns by mutableStateOf(prefs.configListColumns)
        private set

    fun updateConfigListColumns(count: Int) {
        val clamped = count.coerceIn(
            AppPrefs.CONFIG_LIST_COLUMNS_MIN,
            AppPrefs.CONFIG_LIST_COLUMNS_MAX,
        )
        if (configListColumns == clamped) return
        configListColumns = clamped
        prefs.configListColumns = clamped
    }

    /*
     * ============================================================
     * 画布边框那两个开关**不在这里**
     * ============================================================
     * 它们在自定义编辑页顶部的「更多」菜单里，由 [MainActivity] 直接
     * 读写 `AppPrefs` —— 因为编辑页需要"改完立刻看到效果"，
     * 走 view-model 反而要多一层状态同步，而设置页已经不再展示它们。
     *
     * ⚠️ 它们的性质仍是**全局偏好**（存在 `AppPrefs`），
     * 不是某份配置的专属设置。
     */

    /*
     * ============================================================
     * 引导页
     * ============================================================
     */

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

    /*
     * ============================================================
     * 版本更新公告
     * ============================================================
     */

    /**
     * 是否需要弹更新公告。
     *
     * 只比版本号：首次安装（没存过）与升级后（版本号变了）都为 true，
     * 确认过同一版本的则不再打扰。正文与版本无关，写在
     * [com.something.sthkey.ui.feature.announcement.AppAnnouncement]。
     */
    val shouldShowAnnouncement: Boolean
        get() = prefs.announcementVersion != BuildConfig.VERSION_NAME

    /** 用户点了公告的"确认"：记下版本号，之后同一版本不再弹 */
    fun markAnnouncementSeen() {
        prefs.announcementVersion = BuildConfig.VERSION_NAME
        AppLog.i(TAG, "已确认 v${BuildConfig.VERSION_NAME} 更新公告")
    }

    private companion object {
        const val TAG = "App"

        /**
         * 判定"服务是不是真的还活着"时，第二次确认前的等待时长。
         *
         * 这个值专门用来躲开"上一个进程的服务实例还在销毁中"的窗口期：
         * 太短会照样误判（开关清不掉），太长则残留的开关会亮在界面上好一会儿。
         * 800ms 对"服务销毁"这种纯内存收尾是很宽裕的余量。
         */
        const val SERVICE_SHUTDOWN_GRACE_MS = 800L
    }
}

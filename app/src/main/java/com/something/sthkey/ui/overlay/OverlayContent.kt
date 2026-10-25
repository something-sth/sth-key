package com.something.sthkey.ui.overlay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import kotlinx.coroutines.flow.StateFlow
import com.something.sthkey.capture.MouseMotion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.something.sthkey.core.log.AppLog
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.DisposableEffect
import android.view.Surface
import com.something.sthkey.domain.config.TouchRegionBinding
import com.something.sthkey.domain.config.TouchStickFeel
import com.something.sthkey.domain.custom.CustomComponent
import com.something.sthkey.domain.custom.JoystickComponent
import com.something.sthkey.domain.custom.JoystickSource
import com.something.sthkey.domain.custom.KeyComponent
import com.something.sthkey.domain.custom.StickSide
import com.something.sthkey.domain.custom.TextComponent
import com.something.sthkey.domain.custom.CustomTouchRegion
import com.something.sthkey.capture.OverlayBounds
import com.something.sthkey.capture.touch.TouchCapture
import com.something.sthkey.capture.touch.TouchFrameTracker
import com.something.sthkey.ui.overlay.gamepad.TouchContent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.something.sthkey.capture.CaptureSession
import com.something.sthkey.domain.config.KeyStrokesConfig
import com.something.sthkey.domain.custom.CustomLayout
import com.something.sthkey.domain.style.KeyLayout
import com.something.sthkey.domain.style.OverlayStyleRegistry
import com.something.sthkey.domain.style.StyleId
import com.something.sthkey.ui.overlay.gamepad.Gamepad1Content
import com.something.sthkey.ui.overlay.gamepad.Gamepad2Content
import kotlinx.coroutines.delay

/**
 * 悬浮窗内容。
 *
 * ============================================================
 * 关键点：根布局尺寸必须与窗口尺寸**同源**
 * ============================================================
 * 悬浮窗的窗口大小是 Service 用 `WindowManager` 定的（像素）。
 * 如果这里用"内容自适应"，根布局就会按内容撑开，而内容是固定基础尺寸 ——
 * 窗口一缩小，内容就被裁掉一角，表现就是"缩放像是在裁剪"。
 *
 * 因此这里显式把根布局设成 `KeyLayout` 算出的窗口尺寸（换算成 dp），
 * 与 Service 用的是同一套常量、同一个密度，两者永远相等，不可能裁。
 *
 * ============================================================
 * CPS 刷新
 * ============================================================
 * CPS 是"最近 1 秒内的点击数"，它**会随时间自然衰减**（不按键时从 3 变 2 变 0）。
 * 光靠按键事件驱动刷新的话，松开鼠标后数字会停在最后一个值不动，
 * 所以这里在开启 CPS 时起一个定时器持续重算。
 *
 * ============================================================
 * 两个重载：固定配置 / 可观察配置
 * ============================================================
 * 多悬浮窗之后，"这个窗口显示哪份配置"必须在**窗口存活期间**能改
 * （用户在主页改了配置内容，窗口要立刻跟上而不重建 WebView）。
 * 因此 Service 传进来的是一个 [State] 而不是一个值 ——
 * 直接传值的话 Compose 只会在首次组合时读一次，之后配置再变它也不会重组。
 */
@Composable
fun OverlayContent(
    config: KeyStrokesConfig,
    modifier: Modifier = Modifier,
) {
    OverlayContent(config = rememberUpdatedState(config), modifier = modifier)
}

/** 可观察配置版本：配置对象本身在别处被替换时（例如配置内容改动）能触发重组 */
@Composable
fun OverlayContent(
    config: State<KeyStrokesConfig>,
    modifier: Modifier = Modifier,
    /**
     * 这个窗口的**鼠标位移流**（自定义 Key 的「摇杆-鼠标」组件读它）。
     *
     * ⚠️ 传**流**而不是当前值:值是一个快照，Compose 不会因为"流里的值变了"
     * 而重组（只有读到 `State` 才会）。所以订阅必须在组合里做。
     *
     * ⚠️ `null` = 没有鼠标数据源 → 鼠标摇杆画成居中静止
     * （编辑器画布与配置预览也是这个表现，它们根本不传）。
     */
    mouseMotionFlow: StateFlow<MouseMotion>? = null,
) {
    val pressedKeys by CaptureSession.pressedKeys.collectAsState()
    val density = LocalDensity.current.density

    /*
     * ⚠️ 触屏样式要用它拿屏幕尺寸与旋转（见下面那一支）。
     * 取在函数顶部是刻意的:Compose 的 `remember` / `LaunchedEffect`
     * 槽位数量必须稳定，**不能在 `if` 分支里取** —— 那会让
     * "进过触屏分支"和"没进过"两种情况的槽位数量不同、进而错乱。
     */
    val context = LocalContext.current

    /*
     * ⚠️ `collectAsState` 必须**无条件调用**（Kotlin 里 `if (flow != null)` 包住
     * 会让"有流/没流"两种情况的 `remember` 槽位数量不同，Compose 会错乱）。
     * 没有流时拿一个常量默认值，代价是零。
     */
    val mouseMotion = mouseMotionFlow?.collectAsState()?.value ?: MouseMotion()

    val factor = KeyLayout.pxToDpFactor(density)
    val current = config.value

    /**
     * 自定义布局这一刻的触屏读数（摇杆向量 + 被按住的区域）。
     *
     * ============================================================
     * ⚠️⚠️ 必须**无条件**在这里调，不能挪进那个 `if (styleId == CUSTOM_KEY)` 分支
     * ============================================================
     * 同一个理由（见上面 [mouseMotion] 那段）:分支里调的话，
     * "进过自定义分支"与"没进过"两种情况的**槽位数量不同**，
     * Compose 会错乱 —— 症状是切换样式后某些效果莫名其妙不再触发。
     *
     * ⚠️ 而且这个函数内部有 `LaunchedEffect` / `DisposableEffect`
     * （tracker 复位），那两个同样**不能条件调用**。
     *
     * ⚠️ 代价:非自定义样式的配置也会建一个 `TouchFrameTracker` 并每帧
     * 推进一次。它收到的是空区域列表，本来就不做什么 —— 用这点可忽略的
     * 开销换掉一个极难查的坑，划算。
     *
     * ⚠️ 位置在 `current` **之后** —— 它要读 `current.custom.components`
     * （局部 `val` 不能提前引用，Kotlin 会报 Unresolved reference）。
     */
    val touchStateForCustom = customTouchInput(
        context = context,
        components = current.custom.components,
        /* ⚠️ 区域是**配置级**的数据，不在组件上（返工后的模型） */
        touchRegions = current.custom.touchRegions,
        density = density,
    )

    /**
     * 自定义布局里**因触摸区域而记的 CPS**（**区域 id** → 次数）。
     *
     * ⚠️ 与 [touchStateForCustom] 一样无条件调用 —— 它内部也有 effect。
     */
    val touchCpsByRegion = rememberTouchCps(
        configId = current.id,
        pressedRegionIds = touchStateForCustom?.pressedRegionIds.orEmpty(),
    )

    /**
     * 把"区域 → 次数"翻成"**组件** → 次数"。
     *
     * ⚠️ 这一步只能在**这里**做:渲染层（`CustomKeyGrid`）认识组件、
     * 不认识区域列表；而"哪块区域绑了谁"只有配置知道。
     *
     * ⚠️ 一块区域可以绑**多个**组件 —— 所以不是简单的一对一映射，
     * 而是"每个组件把**绑了它的那些区域**的次数加起来"。
     *
     * ⚠️ 按**组件**给值（不是按 CPS 占位符）:一条绑定就是"某块区域 →
     * 某个组件"，它不区分哪一个 `(cps)`。旧模型试过按占位符分
     * （`placeholderIndex`），那正是被用户否掉的那套。
     */
    val touchCpsByComponent = remember(touchCpsByRegion, current.custom.touchRegions) {
        val counts = mutableMapOf<String, Int>()
        current.custom.touchRegions.forEach { region ->
            val clicks = touchCpsByRegion[region.id] ?: 0
            if (clicks == 0) return@forEach
            region.bindings.forEach { binding ->
                counts[binding.componentId] = (counts[binding.componentId] ?: 0) + clicks
            }
        }
        counts
    }

    /*
     * CPS 数值：只在**真的用到**时定时刷新。
     *
     * ============================================================
     * ⚠️ 判据是"这份布局里到底有没有 CPS 槽位"
     * ============================================================
     * 这里连着踩过两次坑，都是**判据与渲染不一致**造成的:
     *
     * 1. 第一版只看 `mouseCpsEnabled` —— 那是 Key 样式的配置项。
     *    自定义 Key 的组件可以绑 CPS，但它的 `mouseCpsEnabled` 是 false，
     *    于是刷新循环根本不起来，数字**永远停在初始值**；
     * 2. 第二版按样式分支（Key 看开关、自定义看组件文字），
     *    ⚠️ 但**漏了「手柄（标准）」样式** —— 它走的是 `KeyLayout`，
     *    于是"显示 CPS 打开后数字不动"（用户报的"CPS 开关无效"）。
     *
     * 现在改成**问布局本身**:
     *
     * - 走 `KeyLayout` 的样式（键盘 / 标准）→ 看 `keys()` 里
     *   有没有 `CPS_L` / `CPS_R` 槽位。那就是"会不会画 CPS"这件事本身，
     *   与渲染用的是**同一个函数、同一个判据**；
     * - 自定义 Key → 看有没有组件的文字里写了 `(cps)` / `(cps2)`。
     *
     * ⚠️ 关键原则:**判据必须与渲染同源**。两边各写一套的话，
     * 表现就是"定时器在跑但屏幕上没东西"或者反过来 ——
     * 而这两种都极难从现象联想到原因。
     *
     * 另外数值取自**这份配置自己的计数器**（CaptureSession.cpsSnapshotOf）——
     * 用全局那个的话，别的配置也在数同一个位置时，数字会翻倍。
     */
    val cpsRefreshKey = when (current.styleId) {        StyleId.CUSTOM_KEY -> CustomLayout.usesCps(current.custom.components)

        /*
         * ⚠️ 模式 1 的 CPS 接在主文字后面、**不在槽位里** —— 所以
         * `mouseCpsEnabled` 也要算进去，不然模式 1 不会刷新。
         *
         * ⚠️ 用 `remember(config)` 缓存:这个表达式会跑一遍 `keys()`，
         * 而 `OverlayContent` 是悬浮窗的根组合函数、重组很频繁 ——
         * 每次重组都算一遍布局是白费功夫。
         */
        else -> remember(current) {
            current.mouseCpsEnabled || KeyLayout.keys(current).any {
                it.slotId == KeyLayout.Id.CPS_L || it.slotId == KeyLayout.Id.CPS_R
            }
        }
    }
    val configId = current.id

    var cps by remember { mutableStateOf(emptyMap<String, Int>()) }
    LaunchedEffect(cpsRefreshKey, configId) {
        if (!cpsRefreshKey) {
            cps = emptyMap()
            return@LaunchedEffect
        }
        while (true) {
            cps = CaptureSession.cpsSnapshotOf(configId)
            delay(CPS_REFRESH_MS)
        }
    }

    val windowWidthDp = (overlayWindowWidthPx(current) * factor).dp
    val windowHeightDp = (overlayWindowHeightPx(current, cps) * factor).dp

    Box(
        modifier = modifier
            .size(windowWidthDp, windowHeightDp)
            .padding((OVERLAY_PADDING_PX * factor).dp),
    ) {
        /*
         * 按样式分流。（Live2D 不在这里 —— 它的宿主是 WebView，
         * 由 OverlayService 直接作为窗口根视图挂上去，见下面那段注释。）
         */
        val sticks by CaptureSession.sticks.collectAsState()

        if (current.styleId == StyleId.GAMEPAD1 || KeyLayout.usesJoystickLayout(current)) {
            /*
             * 手柄样式。
             *
             * ⚠️ 它们是**唯二**消费 [CaptureSession.sticks] 的样式 ——
             * 那个通道装的是归一化后的摇杆状态（`-1..1`）。
             * 键盘样式与自定义 Key 不看它（它们只有"按下/没按下"）。
             *
             * ⚠️ 判据用 `KeyLayout.usesJoystickLayout` 而不是
             * `styleId == GAMEPAD2` —— 那个函数是"这个样式走不走
             * 键盘布局 + 摇杆"的**唯一判据**，布局层与渲染层共用它。
             * 这里再写一遍比较，加样式时就会漏一处。
             */
            val stickScale = factor * KeyLayout.uiScale(current)

            if (current.styleId == StyleId.GAMEPAD1) {
                Gamepad1Content(
                    sticks = sticks,
                    pressedCodes = pressedKeys,
                    scale = stickScale,
                )
            } else if (current.styleId == StyleId.TOUCHING) {
                /*
                 * ============================================================
                 * 触屏样式:布局与手柄共用，**摇杆读数来自屏幕触摸**
                 * ============================================================
                 * ⚠️ 它落在 `usesJoystickLayout` 那一支里（因为槽位布局确实
                 * 与手柄一样），但**不能**走 `Gamepad2Content` ——
                 * 那边读的是 `CaptureSession.sticks`（物理手柄的轴）。
                 *
                 * ⚠️ 所以这个分支必须在 `Gamepad2Content` **之前**。
                 * 放到后面的话永远轮不到它，表现是"触屏悬浮窗画出来了，
                 * 但摇杆只跟着手柄动、碰屏幕没反应"。
                 */
                val touchSettings = current.touch
                val (screenW, screenH) = OverlayBounds.screenSize(context)

                /*
                 * ⚠️ `remember(current.id)` —— 跨帧状态（归属 / 上一帧 / 累计）
                 * 必须**跟着配置**，换配置时应当从零开始。
                 * 不 key 的话换了配置还留着上一份的累计值，
                 * 表现是"刚切过去视角就偏着"。
                 */
                val tracker = remember(current.id) { TouchFrameTracker() }

                /*
                 * ⚠️ 真机诊断**接上**（默认关闭，见 `TouchFrameTracker.debugEnabled`）。
                 *
                 * 排查"按 LT 位置却几乎不亮、亮的是 RT"这类问题时打开它，
                 * 用 `adb logcat -s TouchNative` 就能看到:触点的原始坐标、
                 * 旋转、屏幕尺寸、每块区域**实际覆盖的范围**、命中结果。
                 *
                 * ⚠️ 挂在这里而不是 tracker 内部:tracker 是**纯逻辑类**
                 * （要能单测），不该碰 Android 的日志设施。
                 */
                DisposableEffect(tracker) {
                    tracker.onDebugLine = { line -> AppLog.i("TouchNative", line) }
                    onDispose {
                        tracker.onDebugLine = null
                        tracker.reset()
                    }
                }

                /*
                 * ⚠️ 采集状态**必须在这里读**:`TouchCapture` 是单例，
                 * 它的 `state` 一变这里就重组，于是摇杆跟着动。
                 *
                 * ⚠️ 判据里带上 `frameSeq` —— 只看 `points` 的话
                 * "手指按住不动"与"抬起之后"都不会触发重组，
                 * 画出来的就是**旧的**状态（见 `TouchCapture.State.frameSeq`）。
                 */
                val touchState by TouchCapture.state.collectAsState()

                val rotation = remember(screenW, screenH) { currentRotation(context) }

                /*
                 * ⚠️ 自然屏尺寸恒为 `0,0`（Android 拿不到，见
                 * [naturalPanelSize] 的说明）—— 自检会因此跳过。
                 * 留着这个调用是为了**接口已经接好**:将来如果用
                 * "用户点四角标定"拿到了真实值，直接在这里返回即可。
                 */
                val (naturalW, naturalH) = naturalPanelSize(context)

                /*
                 * ⚠️ 上一次已经记过 CPS 的槽位 —— 用来找出"**新按下**"的那些。
                 *
                 * ⚠️ 必须是**跨帧**的状态（`remember`），不能用 `touchSnapshot`
                 * 自己算 —— 那里面只有"这一帧按着什么"，没有"上一帧"。
                 *
                 * ⚠️ 跟着配置 id 重置:换配置时不该把上一份的按下状态带过来。
                 */
                var lastCpsSlots by remember(current.id) {
                    mutableStateOf(emptySet<String>())
                }

                /*
                 * ============================================================
                 * ⚠️⚠️ 状态推进必须发生在**组合期**，不能在 `LaunchedEffect` 里
                 * ============================================================
                 * 用户实测报的现象（这段注释就是为了记住它）:
                 *
                 * > 我按下 LT 的位置，**不会有变化，我松手-LT 亮起，
                 * > 再按下-LT 熄灭**，别的键同理
                 *
                 * ⚠️ 那是一帧延迟，成因很典型:
                 *
                 * | 我原来的写法 | 问题 |
                 * |---|---|
                 * | `LaunchedEffect(...) { tracker.onFrame(...) }` | ⚠️ **组合完成之后**才跑 |
                 * | `remember(frameSeq) { tracker.pressedSlots() }` | 组合期读 → 读到**上一帧**的状态 |
                 *
                 * 于是屏幕上画的永远比手指慢一帧 —— 按下看不到亮、
                 * 松手才看到亮、再按又熄灭。
                 *
                 * ⚠️ Axon 那边是 `setPressedMask(mask)` 之后**直接
                 * `invalidate()`**，同一帧内就画出来了，没有这个缝隙。
                 *
                 * ⚠️ 修法是**在组合期推进**，靠 `advance(seq, ...)` 里的
                 * `lastSeq` 保证同一帧**恰好推进一次**（组合可能因为别的原因
                 * 重跑，那时不该再推进）。
                 *
                 * ⚠️ 副作用写在组合期通常是不好的做法，但这里的"副作用"是
                 * **推进一个由帧序号严格驱动的状态机** —— 它是幂等的
                 * （同一 seq 只生效一次），所以结果不依赖重组次数。
                 *
                 * ⚠️ `advance` 一次**拿全**摇杆与按键:分两次读的话，
                 * 中间可能夹着一次状态更新，于是同一帧的摇杆与按键
                 * 来自**不同的帧**（表现是两者不同步）。
                 */
                /* ⚠️ 与编辑器同一个密度值 —— 见 TouchFrameTracker.density 的说明 */
                tracker.density = density

                val touchSnapshot = tracker.advance(
                    seq = touchState.frameSeq,
                    rawPoints = touchState.points,
                    rotation = rotation,
                    screenWidth = screenW,
                    screenHeight = screenH,
                    /*
                     * ⚠️ 用 `activeRegions`（**只含用户真配过的**），不是 `regions` ——
                     * 列表里可能含有"编辑器画出来给用户拖、但没碰过"的占位条目，
                     * 那些**不该生效**。详见 `TouchRegionBinding.pinned`。
                     */
                    regions = touchSettings.activeRegions,
                    moveFeel = touchSettings.move.feel,
                    cameraFeel = touchSettings.camera.feel,
                    naturalWidth = naturalW,
                    naturalHeight = naturalH,
                )

                /*
                 * 离开组合（关掉悬浮窗 / 换配置）时要复位 ——
                 * 不清的话下次打开会带着上一次的视角累计值。
                 *
                 * ⚠️ 诊断出口也在这里清掉:留着的话 tracker 被回收后
                 * 还会被回调（虽然无害，但那是泄漏的形状）。
                 */
                DisposableEffect(tracker) {
                    onDispose {
                        tracker.onDebugLine = null
                        tracker.reset()
                    }
                }

                /*
                 * ============================================================
                 * ⚠️⚠️ 区域按下的槽位**直接传下去**，不做键码翻译
                 * ============================================================
                 * 我第一版把它经 `keyMappings` 翻译成键码、并进 `pressedCodes`
                 * —— **那是错的，而且表现是"按了完全没反应"**:
                 *
                 * 键帽点亮走 `box.codes.any { it in pressedCodes }`，
                 * 而 `box.codes` 来自 `codeToSlotMap`，那张表**只从
                 * `inputKeyCodes` 建** —— 触屏的那些**全是空的**
                 * （触屏不读物理按键）。于是 `box.codes` 是空集、
                 * `any {}` 永远 false、**永远不亮**。
                 *
                 * ⚠️ 根子上的错:触屏**根本没有键码**，硬要挤进"键码"这条
                 * 通道就等于要求一个不存在的翻译。所以改成传**槽位**，
                 * 由 `OverlayKeySlot` 另一条路比 `box.slotId`。
                 *
                 * ⚠️ 它已经跟着 `touchSnapshot` 一起算好了（见上面 `advance`），
                 * 不再单独读一次 —— 分两次读会让按键与摇杆不同步。
                 */
                /*
                 * ============================================================
                 * ⚠️ 触屏区域的按下**也要计 CPS**
                 * ============================================================
                 * 用户的原话:
                 *
                 * > 然后触屏样式的显示 cps**我打算接回来**，你看看怎么处理比较好
                 *
                 * ⚠️ 原来没接，所以触屏下 CPT 永远是 0 —— 而触屏**完全不产键码**，
                 * 采集层那条"按键 → 计一次 CPS"的路对它是死的。
                 *
                 * ⚠️ 好在 `CaptureSession.recordCpsClick(configId, slotId)`
                 * 收的就是**槽位**（不是键码），于是触屏可以直接用它 ——
                 * 口径与真实输入**完全一致**（同一个计数器、同一个时钟）。
                 *
                 * ⚠️ **只在"由没按变按下"那一下记一次** ——
                 * 每帧都记的话，按住 LS/RT 一秒就是 60 次/秒的假 CPS。
                 * 这与 `CaptureController` 里那条判据一致（它也是
                 * `if (pressed && code !in current)`）。
                 *
                 * ⚠️ 放在 `LaunchedEffect` 里（不是在组合期直接调）——
                 * 这是真正的副作用（改全局计数器），不该写在组合里。
                 * 它由 `frameSeq` 驱动，每帧跑一次；而"只在边沿记"这条
                 * 保证了重复跑不会多记。
                 */
                LaunchedEffect(touchSnapshot.pressedSlots, current.id) {
                    val now = touchSnapshot.pressedSlots
                    val fresh = now - lastCpsSlots
                    lastCpsSlots = now
                    fresh.forEach { slotId ->
                        CaptureSession.recordCpsClick(current.id, slotId)
                    }
                }

                TouchContent(
                    config = current,
                    move = touchSnapshot.move,
                    camera = touchSnapshot.camera,
                    pressedCodes = pressedKeys,
                    pressedSlots = touchSnapshot.pressedSlots,
                    scale = stickScale,
                    cpsBySlot = cps,
                )
            } else {
                Gamepad2Content(
                    config = current,
                    sticks = sticks,
                    pressedCodes = pressedKeys,
                    scale = stickScale,
                    /* CPS 数值要传:鼠标键的 CPS 显示与键盘样式同一套 */
                    cpsBySlot = cps,
                )
            }
        } else if (current.styleId == StyleId.CUSTOM_KEY) {
            /*
             * 自定义 Key：画布上的一切由组件列表决定。
             *
             * 用**同一个** CustomKeyCanvas 渲染，编辑器里看到的与这里逐像素一致；
             * CPS 数值也传进去，组件文字里的 `(cps)` / `(cps2)` 占位符才会跟着动。
             *
             * `slotIdOf` 把"组件指向的那个键码"翻译成键位 id ——
             * 计数是按键位记的（同一个映射位置下的键共享计数），
             * 翻译表就是配置自己的键位映射。哪个组件指向哪个键码由
             * `CustomLayout.cpsKeyCodesOf` 回答（按键组件用它自己监听的键，
             * 文本组件用它的 cpsKeyCodes）。
             *
             * ============================================================
             * ⚠️ 内容必须先搬进"窗口坐标"，而且要用 toWindow 而不是手写偏移
             * ============================================================
             * 窗口只覆盖内容的**包围盒**，而组件坐标的原点是**定位区**的左上角 ——
             * 两者不是同一个原点（组件可以放在 (300, 400)，也可以是负坐标）。
             *
             * 这里踩过一个很迷惑的坑：手写偏移量时把符号写反了（用了 `−left`），
             * 于是内容被整体推出可视区，屏幕上只剩靠下的一小部分
             * （默认布局里就是那行 CPS 文本）甚至什么都不剩 ——
             * 而编辑器画布里完全正常（那边不画在窗口里），极难定位。
             *
             * 现在改用 `CustomLayout.toWindow`：它是纯函数、有测试钉着
             * "每个组件都落在窗口之内"，符号不可能再写错。
             *
             * 窗口尺寸（样式注册表那边）与这里用的是**同一个** `bounds` ——
             * 同一个纯函数，必然同一个结果。
             */
            val windowed = CustomLayout.toWindow(current.custom.components)

            CustomKeyCanvas(
                settings = current.custom.copy(components = windowed.components),
                pressedCodes = pressedKeys,
                scale = factor * KeyLayout.uiScale(current),
                cpsBySlot = cps,
                slotIdOf = { code -> KeyLayout.codeToSlotMap(current)[code] },
                baseWidth = windowed.bounds.width,
                baseHeight = windowed.bounds.height,
                overallAlpha = current.customOpacityPercent.coerceIn(0, 100) / 100f,
                /*
                 * ⚠️ 自定义画布上的摇杆也要跟着手柄动 —— 传真实数据进去。
                 *
                 * 编辑器画布与配置预览**不传**（用默认的全 0 = 摇杆居中）:
                 * 它们没有手柄数据源，而且用户明确说过自定义编辑页的画布
                 * "纯静态就行了"（与 Key 组件的按下动画一样，编辑器不演示动效）。
                 */
                sticks = sticks,
                /* ⚠️ 只有「摇杆-鼠标」组件用它；其它样式的调用点不传 */
                mouseMotion = mouseMotion,
                /*
                 * ⚠️ 必须自适应。
                 *
                 * 窗口尺寸是按公式"推算"的，而容器实际给的空间可能与它
                 * 差几个像素（实测差过 11px）。自己算尺寸 + 指望容器给得起
                 * 的结果就是画布被压小，而**声明宽度等于画布宽**的那些组件
                 * （空格、Shift）被裁 —— 就是那个很难查的"空格短一截"。
                 *
                 * 自适应之后，画布严格等于容器给的空间，不可能溢出。
                 */
                fitToContainer = true,
                touchInput = touchStateForCustom,
                /*
                 * ============================================================
                 * ⚠️ **两路 CPS 相加**
                 * ============================================================
                 * | 路 | 按什么记 |
                 * |---|---|
                 * | [cps]（`cpsBySlot`） | 物理键 —— **按键位 id** 记 |
                 * | **触摸** | **按区域 id** 记，再翻成组件（见 [touchCpsByComponent]） |
                 *
                 * ⚠️ 触摸那个**不能**并进 `cpsBySlot` 再另外加一遍 ——
                 * 那会加两遍、CPS 直接翻倍（见 `rememberTouchCps` 的说明）。
                 */
                touchCpsOf = { componentId -> touchCpsByComponent[componentId] ?: 0 },
            )
        } else {
            /*
             * 这里只画**按键样式**。
             *
             * Live2D 不走 Compose：它的宿主是普通的 FrameLayout + WebView
             * （见 ui/overlay/live2d/Live2DOverlayView），由 OverlayService 直接
             * 作为窗口根视图挂上去。原因写在那个类的注释里 —— 塞进 Compose 的
             * 互操作层时它渲染不出来，而旧项目那条路是跑通的。
             *
             * 也就是说，"按样式决定用什么画"这个分支在 OverlayService.createOverlayView()，
             * 全项目只有那一处。
             */
            KeyGrid(
                config = current,
                pressedCodes = pressedKeys,
                scale = factor * KeyLayout.uiScale(current),
                cpsBySlot = cps,
            )
        }
    }
}
/*
 * ============================================================
 * 窗口尺寸
 * ============================================================
 * 悬浮窗用 WindowManager，窗口大小必须以**像素**给出（它不会按内容自适应）。
 * 尺寸来自 [OverlayStyleRegistry.baseSizeOf]：由**样式自己**声明内容有多大，
 * 再统一乘缩放。这里不允许再去直接问 [KeyLayout] ——
 * 那样加了第二种样式之后，窗口与内容的口径必然会分叉。
 */

/** 悬浮窗外框内边距（像素）：给描边留空间，避免贴边被裁 */
const val OVERLAY_PADDING_PX = 6f

/** 窗口最小边长（像素）：配置极小时避免窗口退化成一条线 */
const val OVERLAY_MIN_SIZE_PX = 48f

/** CPS 刷新间隔：约 10fps 就够（数字变化不需要每帧算） */
private const val CPS_REFRESH_MS = 100L

/**
 * 窗口宽度（像素）。
 *
 * ⚠️ 宽度必须与高度用**同一个来源**（[OverlayStyleRegistry.baseSizeOf]）。
 *
 * 曾经这里是写死的 `KeyLayout.BASE_WIDTH * 缩放`，而高度一直是问样式要的 ——
 * 两种口径并存。当时没出问题只是因为按键样式的 `baseSize` 恰好也是
 * `KeyLayout.BASE_WIDTH`，一旦有了尺寸不同的样式（自定义 Key 的宽度
 * 是所有组件的包围盒）就会立刻分叉。
 *
 * 排查"悬浮窗右边被裁"时我一度以为是这里写死了，但那段代码其实已经是
 * 问样式要的 —— **真正的病因在别处**（转换时多做了一次居中平移，
 * 使组件坐标与包围盒偏移叠加了两次，见 `KeyToCustomConverter`）。
 * 把这段注释留下，是为了让下一个人不必再走一遍同样的弯路。
 */
fun overlayWindowWidthPx(config: KeyStrokesConfig): Float = (
    OverlayStyleRegistry.baseSizeOf(config).width * KeyLayout.uiScale(config) +
        OVERLAY_PADDING_PX * 2
    ).coerceAtLeast(OVERLAY_MIN_SIZE_PX)

/**
 * 窗口高度（像素）。
 *
 * @param cpsBySlot CPS 数值。**必须传**：CPS 模式 2 会多出一行、模式 3 会让键变高，
 *   窗口尺寸按"没有 CPS"算的话，那部分内容就会被裁掉。
 *   （Live2D 样式的尺寸与它无关，由样式自己忽略。）
 */
fun overlayWindowHeightPx(
    config: KeyStrokesConfig,
    cpsBySlot: Map<String, Int> = emptyMap(),
): Float = (
    OverlayStyleRegistry.baseSizeOf(config, cpsBySlot).height * KeyLayout.uiScale(config) +
        OVERLAY_PADDING_PX * 2
    ).coerceAtLeast(OVERLAY_MIN_SIZE_PX)

/**
 * 窗口尺寸用的取整：**向上取整**，不是截断。
 *
 * ⚠️ 内容尺寸是小数（`bounds.width × 缩放`），而窗口必须以整数像素给出。
 * 用 `toInt()` 截断的话，窗口会比内容**小一点点** ——
 * 而这一丁点正好落在最右/最下的那几个像素上，表现是**边缘被裁掉一条**，
 * 而且缩放不是整数时才出现（例如 155.5 → 155），极难联想到取整。
 *
 * 宁可多一个像素（外面多一条透明边，看不出来），也不能少 ——
 * 少的那一个像素切掉的是用户内容。
 */
fun toWindowPx(value: Float): Int = kotlin.math.ceil(value).toInt().coerceAtLeast(1)

/** dp → 像素（默认位置等按 dp 思考的地方用） */
fun dpToPx(dp: Float, density: Float): Int = (dp * density).toInt().coerceAtLeast(1)

/**
 * 当前屏幕旋转（[Surface] 的常量）。
 *
 * ============================================================
 * ⚠️ 触屏样式**必须**用它换算触点坐标
 * ============================================================
 * 原生给的触点是**面板自然方向**（通常竖屏）的坐标，而用户多半横屏
 * 玩游戏 —— 不换算的话手指按左下角、我们以为他按了右下角，
 * 触摸区域与手指位置**完全对不上**。详见 `TouchRotation` 的说明。
 *
 * ⚠️ 用 `Display.getRotation()` 而不是 `Configuration.orientation`:
 * 后者只有"横/竖"两种值，**分不出 90° 与 270°** ——
 * 而这两种在触屏下是**左右镜像**的关系，搞混了区域会跑到反方向。
 *
 * ⚠️ 拿不到显示器时回落到 `ROTATION_0`（不换算）。
 * 那顶多是方向不对，而抛异常会让整个悬浮窗画不出来。
 */
private fun currentRotation(context: android.content.Context): Int = runCatching {
    val manager = context.getSystemService(android.content.Context.DISPLAY_SERVICE)
        as? android.hardware.display.DisplayManager
    manager?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation
        ?: Surface.ROTATION_0
}.getOrDefault(Surface.ROTATION_0)

/**
 * 面板**自然方向**的像素尺寸。
 *
 * ============================================================
 * ⚠️⚠️ 我第一版想用它给 `TouchRotation.isConsistent` 当参照物 ——
 * 那是**错的**，所以这个函数现在**只返回 0**（等于"不知道"）。
 * ============================================================
 * Android **没有**公开接口能拿到"自然方向"的尺寸:
 *
 * | API | 给的其实是 |
 * |---|---|
 * | `DisplayMetrics.widthPixels`（经 `getRealMetrics`） | **当前方向**的宽高 |
 * | `getRealSize` | 同上 |
 * | `Configuration.screenWidthDp` | 同上 |
 *
 * ⚠️ 拿"当前方向尺寸"当"自然方向尺寸"的话，`isConsistent` 里那两个
 * 宽高比**永远相等** —— 自检永远通过。那是个**假自检，比没有更坏**:
 * 它会让人以为"方向已经验证过了"。
 *
 * ⚠️ 所以这里诚实地返回 0，自检自动跳过（见 `TouchRotation.isConsistent`
 * 里"参照物缺失就放行"那条）。真要判断方向，得用别的办法
 * （比如让用户点屏幕四角做标定）—— 那是另一件事，不塞在这里假装。
 */
private fun naturalPanelSize(context: android.content.Context): Pair<Int, Int> = 0 to 0

/**
 * 算出**自定义画布**这一刻的触屏读数。
 *
 * ============================================================
 * ⚠️⚠️ 区域从**配置**来，不从组件来（返工后的模型）
 * ============================================================
 * 我第一版让区域挂在**组件**上（`touchBindings` / `touchRegion`），
 * 用户否掉了那个模型（见 `CustomTouchRegion` 的说明）。
 *
 * ⚠️ 新模型下这里要做的只有**两件事**:
 *
 * | 步骤 | 做什么 |
 * |---|---|
 * | ① | 把配置的 [CustomTouchRegion] 转成采集层认的区域，喂给 tracker |
 * | ② | 把结果**翻回去**:哪块区域被按到 → 它绑了哪些组件 → 谁该亮 / 谁该加 CPS |
 *
 * ============================================================
 * ⚠️ 这里**不提前返回**
 * ============================================================
 * 我第一版写过"没有触屏组件就 `return null`，省掉这份开销"——
 * 而那个提前返回**在 `DisposableEffect` 之前**，于是切换配置时
 * **槽位表会错位**（Compose 的经典坑，症状是"某些效果莫名其妙不再触发"）。
 *
 * ⚠️ 现在一律无条件跑到最后；没有区域时 tracker 收到空列表，本来就不做什么。
 */
@Composable
private fun customTouchInput(
    context: android.content.Context,
    components: List<CustomComponent>,
    touchRegions: List<CustomTouchRegion>,
    density: Float,
): TouchInputState? {
    val touchState by TouchCapture.state.collectAsState()

    /*
     * ⚠️ 把**配置里的区域**转成采集层认的形状 —— `slotId` 用**区域自己的 id**，
     * 于是命中之后能直接查回是哪一块（见下面 ②）。
     */
    val bindings = remember(touchRegions) {
        touchRegions.map { region ->
            TouchRegionBinding(
                slotId = region.id,
                centerX = region.region.centerX,
                centerY = region.region.centerY,
                size = region.region.size,
                /*
                 * ⚠️ 恒 `true` —— 这个字段是**触屏样式**那边
                 * "编辑器画出来的占位方块要不要生效"的开关，
                 * 而自定义布局里的区域**全都是用户真的新建出来的**。
                 */
                pinned = true,
            )
        }
    }

    val tracker = remember { TouchFrameTracker() }
    val (screenW, screenH) = remember { OverlayBounds.screenSize(context) }
    val rotation = remember(screenW, screenH) { currentRotation(context) }

    /*
     * ⚠️ 触屏手感取**第一个触屏移动摇杆**的那份（与旧模型同一个简化）。
     *
     * ⚠️ 手感本是"每个组件自己的"，而全屏滑动的那种推摇杆只产出一份向量 ——
     * 多个触屏摇杆各要各的手感是另一件事。
     */
    val feel = remember(components) {
        components.filterIsInstance<JoystickComponent>()
            .firstOrNull { it.source == JoystickSource.TOUCH && it.side == StickSide.LEFT }
            ?.touchFeel
            ?: TouchStickFeel()
    }

    /*
     * ⚠️⚠️ **组合期推进**（不是 `LaunchedEffect`）—— 与触屏样式那一支
     * 同一个理由:effect 在组合完成之后才跑，画出来会**永远慢一帧**
     * （现象正是用户报过的"按下不亮、松手才亮"）。
     */
    val snapshot = tracker.advance(
        seq = touchState.frameSeq,
        rawPoints = touchState.points,
        rotation = rotation,
        screenWidth = screenW,
        screenHeight = screenH,
        regions = bindings,
        moveFeel = feel,
        cameraFeel = feel,
        /*
         * ⚠️⚠️ **哪些区域算"轮盘"** —— 必须告诉 tracker。
         *
         * ⚠️ 自定义 Key 里轮盘区域有**它自己的 id**，而不是触屏样式那个
         * 固定的 `@move` 槽位。不告诉它的话:
         *
         * | 后果 | 为什么 |
         * |---|---|
         * | 推摇杆时那个按键组件**也亮** | 轮盘区域的 id 被当成普通按键区域混进 `pressedSlots` |
         * | 推移动摇杆**把视角一起带偏** | 它的触点没被算进"不喂视角摇杆"那一类 |
         *
         * ⚠️ `direction == null` 的绑定 = 纯推摇杆（见 `CustomTouchRegionBinding`）:
         * 带方向的那种是"按键式"的，不该被当成轮盘。
         */
        moveSlotIds = touchRegions
            .filter { it.kind == CustomTouchRegion.Kind.MOVE_STICK }
            .mapTo(mutableSetOf()) { it.id },
    )

    DisposableEffect(tracker) {
        onDispose { tracker.reset() }
    }

    /* ② 翻回去:被按到的区域 → 它绑的组件 */
    val pressedRegions = snapshot.pressedSlots
    val pressedComponents = remember(pressedRegions, touchRegions) {
        touchRegions.filter { it.id in pressedRegions }
            .flatMap { it.bindings }
            .mapTo(mutableSetOf()) { it.componentId }
    }

    /*
     * ⚠️ **移动摇杆组件**的向量:它吃的是**绑定了它的那块轮盘区域**。
     *
     * ============================================================
     * ⚠️⚠️ 直接用 [TouchFrameTracker.advance] 算好的那一份
     * ============================================================
     * 我第一版在这里**又调了一次** `regionMoveVectors` —— 每帧算两遍，
     * 而且那一遍走的是"每帧重判手指在不在区域内"那条**更差**的判据
     * （真正那份走的是"按下时定归属"，见 `regionMoveVectors` 的
     * `ownedPointIds` 参数）。
     *
     * ⚠️ 用户报的就是这个:
     *
     * > 自定义 key 组件里摇杆……**常规模式有卡顿现象**，
     * > 你翻一下代码看一下，**跟触屏样式写一样才对**
     *
     * ⚠️ 现在与触屏样式**逐字同一条路**:都用 `advance` 的产物，
     * 一处计算、一处消费。
     *
     * ⚠️ 这里只做**一次映射**:"区域 id → 向量" 翻成
     * "组件 id → 向量"（因为渲染层认识组件、不认识区域）。
     */
    val moveByComponent = remember(snapshot.moveRegions, touchRegions) {
        buildMap {
            touchRegions.forEach { region ->
                val vector = snapshot.moveRegions[region.id] ?: return@forEach
                region.bindings
                    .filter { it.direction == null }
                    .forEach { put(it.componentId, vector) }
            }
        }
    }

    return remember(snapshot, pressedComponents, moveByComponent) {
        TouchInputState(
            moveByComponent = moveByComponent,
            camera = snapshot.camera,
            pressedComponentIds = pressedComponents,
            pressedRegionIds = pressedRegions,
            moveByRegion = snapshot.moveRegions,
        )
    }
}

/**
 * 自定义布局里**因触摸区域而记的 CPS**（**区域 id** → 次数）。
 *
 * ============================================================
 * ⚠️⚠️ 为什么单开一路，而不是并进 `cpsBySlot`
 * ============================================================
 * 用户的原话:
 *
 * > 点到这个区域按键组件就亮、**文本组件 cps 就加**
 *
 * ⚠️ CPS 计数在 `CaptureSession` 里是**按键位 id** 记的
 * （`recordCpsClick(configId, slotId)`），而自定义布局的触摸区域
 * **不属于任何槽位** —— 它是配置级的一等数据（`CustomTouchRegion`）。
 *
 * ⚠️ 所以触摸这一路按**区域 id** 记。把它并进 `cpsBySlot` 再另外
 * 加一遍的话会**加两遍**（用户看到 CPS 直接翻倍，而且只在
 * "既绑了键又配了区域"时才出现 —— 极难联想）。
 *
 * ============================================================
 * ⚠️ 为什么按**区域**而不是按组件记
 * ============================================================
 * 一块区域可以绑**多个**组件（用户确认过"能选多个"）。
 * 若按组件记，那"这块区域被按了几次"就分不出来。
 *
 * ⚠️ 抽成一个 `@Composable` 而不是内联在那一大段里，是因为它带
 * `LaunchedEffect`（真正的副作用:改全局计数器），混在几百行里读不出来。
 */
@Composable
private fun rememberTouchCps(
    configId: String,
    pressedRegionIds: Set<String>,
): Map<String, Int> {
    /*
     * ⚠️ 「上一次按住了哪些区域」只活在**这个会话**里 ——
     * 它只是用来判"这一帧是不是**新按下**的边沿"，不需要跨进程存活。
     */
    var lastPressed by remember(configId) { mutableStateOf(emptySet<String>()) }

    /*
     * ⚠️⚠️ **只在"由没按变按下"那一下记一次** —— 与触屏样式那一支同一个理由:
     * 每帧都记的话，按住区域一秒就是 60 次/秒的假 CPS。
     *
     * ⚠️ 这是**真正的副作用**（改全局计数器），所以放 `LaunchedEffect`
     * 而不是组合期直接调。它由 `pressedRegionIds` 驱动。
     */
    LaunchedEffect(pressedRegionIds, configId) {
        val fresh = pressedRegionIds - lastPressed
        lastPressed = pressedRegionIds
        fresh.forEach { key ->
            CaptureSession.recordCpsClick(configId, key)
        }
    }

    /*
     * ⚠️ 读数**单独轮询**（与 `cpsRefreshKey` 那条循环同一个节奏）——
     * 只在"按下的那一刻"更新的话，**松手之后数字不会回落**:
     * 那一段没有新的边沿，effect 不会再跑，界面就停在上一次的值上。
     */
    var counts by remember(configId) { mutableStateOf(emptyMap<String, Int>()) }
    LaunchedEffect(configId) {
        while (true) {
            counts = CaptureSession.cpsSnapshotOf(configId)
            delay(CPS_REFRESH_MS)
        }
    }

    return counts
}

/**
 * 一块触摸区域在 CPS 计数器里的**键**。
 *
 * ============================================================
 * ⚠️⚠️ 为什么不是"一个组件一个键"
 * ============================================================
 * 用户报的:
 *
 * > 在**任意占位符**添加的触摸区域都会**加到其他触摸区域上**
 *
 * ⚠️ 成因:计数原来按**组件 id** 记，而文本组件可以有好几个 `(cps)`，
 * 各配各的区域 —— 于是两处各按一下，**两处都显示 2**（组件总次数）。
 * 用户看到的是"两块区域的次数混在一起"。
 *
 * ⚠️ 现在按 **组件 id + 占位符下标** 记，每块区域各有各的计数:
 *
 * | 组件 | 键 |
 * |---|---|
 * | 按键组件（没有占位符） | `组件id` |
 * | 文本组件第 n 处 | `组件id#n` |
 *
 * ⚠️ 分隔符用 `#` —— 组件 id 是 `custom:key_1` 那种形状（有冒号），
 * 再用冒号会撞；`#` 在 id 里不会出现。
 */
internal fun touchCpsKey(componentId: String, placeholderIndex: Int?): String =
    if (placeholderIndex == null) componentId else "$componentId#$placeholderIndex"

/**
 * 组件 id → 它在 CPS 计数器里的**所有**键。
 *
 * ⚠️ 按下"区域"时只知道它属于哪个组件（`pressedRegionIds` 里是组件 id），
 * 而计数是按"组件 + 占位符"记的 —— 所以要**扇出**成它的每一个键。
 *
 * ⚠️ 文本组件有几处占位符是不确定的（用户随时可以改文字），
 * 所以这里用一个**够大的范围**扫一遍，而不是去查配置
 * （查配置会让这个 effect 依赖整份配置，重组范围大得多）。
 */
private const val MAX_TOUCH_CPS_PLACEHOLDERS = 8

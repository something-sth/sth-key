package com.something.sthkey.ui.overlay.live2d

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.something.sthkey.capture.OverlayBounds
import com.something.sthkey.core.log.AppLog
import com.something.sthkey.domain.keys.KeyCodes
import com.something.sthkey.domain.live2d.Live2DModelEntry
import com.something.sthkey.domain.live2d.Live2DSemantics
import org.json.JSONObject

/**
 * Live2D（键盘猫）悬浮层。
 *
 * ============================================================
 * 为什么是普通 View，而不是 Compose 的 AndroidView
 * ============================================================
 * 这一版**刻意照搬旧项目（社区版）的结构**：把 WebView 放进一个 FrameLayout，
 * 由 `OverlayService` 把这个 FrameLayout 直接 `addView` 成窗口的**根视图**。
 *
 * 之前那版是把它塞进 Compose 的 `AndroidView` 互操作层里，结果是悬浮窗
 * 什么都不显示。互操作层里再套一个硬件层 WebView，多出来的不确定性太多，
 * 而且**没有任何日志能说明是哪一层没画**。旧项目这条路是跑通的，
 * 那就用跑通的那条：安卓侧只负责"把 WebView 放到正确位置、把触摸让出来"，
 * 渲染全部交给 assets/bongocat 里的 HTML + JS。
 *
 * ============================================================
 * 触摸：必须由自己抢下来
 * ============================================================
 * 拖动逻辑挂在**根视图**的 `OnTouchListener` 上（见 OverlayService），
 * 而 Android 的事件分发是子 View 优先 —— WebView 默认会把 `ACTION_DOWN` 吃掉，
 * 事件永远到不了父容器，表现就是"猫显示出来了，但窗口拖不动"。
 *
 * 所以在 [onInterceptTouchEvent] 里把整个手势抢过来（ACTION_DOWN 返回 true），
 * 之后的事件就会送到本视图自己的 `OnTouchListener`，拖动恢复正常。
 * 这也是旧项目的做法。
 */
class Live2DOverlayView(context: Context) : FrameLayout(context) {

    private companion object {
        const val TAG = "Live2D"

        /**
         * 初始页面。
         *
         * 只作为"还没被 [applyTarget] 设置过"时的兜底 —— OverlayService 创建视图后
         * 会立刻按配置调用 [applyTarget]，正常流程下它几乎马上就被替换掉。
         */
        const val DEFAULT_PAGE = "file:///android_asset/bongocat/keyboard/index.html"

        /** 页面就绪后延迟多久做健康检查（模型是异步初始化的，要给它时间） */
        const val HEALTH_CHECK_DELAY_MS = 1_200L
        const val HEALTH_CHECK_RETRY_DELAY_MS = 3_000L

        /** mouseDelta 日志：前这么多条逐条记 */
        const val MOTION_LOG_HEAD = 8

        /** 之后每隔这么多条记一条 */
        const val MOTION_LOG_EVERY = 60

        /** 发到第这么多条位移时补一次"位移后"体检（见 dispatchMouseDelta） */
        const val MOTION_PROBE_AFTER = 10

        /** 补体检前等多久，让阻尼动画走完、指针稳定下来 */
        const val MOTION_PROBE_DELAY_MS = 600L
    }

    private val webView: WebView

    /** 当前要加载的页面；由配置里的 `live2d.modelId` 解析而来 */
    private var pageUrl: String = DEFAULT_PAGE

    /** 当前模型有没有右侧键帽（方向键）；影响"同侧恢复"的判定 */
    private var hasArrowSide = true

    /**
     * 当前模型**真实提供**键帽的键名；**空集表示不过滤**。
     *
     * 为什么要过滤：运行时每次按键都会让猫的手动一下（`CatParamLeftHandDown`），
     * 跟皮肤包画了什么键帽无关。于是一个只画了 5 个键的小键盘包，
     * 按 A 也会看见手往下按 —— 只转发这个包真有的键，才符合直觉。
     */
    private var supportedKeys: Set<String> = emptySet()

    private var pageReady = false

    /*
     * ============================================================
     * 输入状态
     * ============================================================
     * ⚠️ 这些字段**必须声明在 init 之前**。
     *
     * Kotlin 按声明顺序初始化属性与 init 块，而 init 会调用 reload()，
     * reload() 里要清空 pending。字段声明在后面的话，init 执行时它们还是 null ——
     * 构造这个 View 就会直接 NPE（表现是悬浮窗起不来，与 Live2D 本身无关）。
     */

    /** 最近一次推进来的键码集合（页面就绪时用它整体补发） */
    private var latestCodes: Set<Int> = emptySet()

    /** 语义 → 当前有几个物理键在按下它（左右 Meta 这类会 >1） */
    private val semanticCounts = mutableMapOf<String, Int>()

    /** 按下的先后顺序；同一侧"最后一个按下的"要显示出来 */
    private val semanticOrder = linkedSetOf<String>()

    /** 待发送的 JS 调用，合并到下一帧统一发 */
    private val pending = mutableListOf<Pair<String, Boolean>>()

    private var currentMouseMask = 0
    private var pendingMouseMask = 0

    /**
     * 鼠标位移：已经转发到 JS 的量，以及这一帧待发送的位移。
     *
     * 采集层给的是**增量式的累计值**（自这条流被创建以来的净位移，
     * 见 [CaptureSession.openMouseMotion]），这里做一次减法得到"这次新增了多少"；
     * 增量再按帧累加，一帧只发一条 mouseDelta。
     *
     * ⚠️ 这里**只有一个** sent 变量，没有"基准"：
     * 零点由采集层负责（它记的是创建那条流时的原始总量）。
     * 之前是消费方自己记基准，那要求「基准 + 已转发量 + 总量」三个变量
     * 始终一致，任何一个漂了都表现为"鼠标朝一个方向顶到底、之后再也不动" ——
     * 所以现在把零点收回到采集层，**不要再在消费方加基准**。
     */
    private var sentMotionDx = 0L
    private var sentMotionDy = 0L
    private var pendingMotionDx = 0L
    private var pendingMotionDy = 0L

    private var pushScheduled = false

    /** 每个页面只打一次"收到鼠标位移"的日志，避免 1000Hz 刷屏 */
    private var motionLogged = false

    /** 已发出的 mouseDelta 条数；用于节流日志（见 [dispatchMouseDelta]） */
    private var motionDispatchCount = 0

    private val pushRunnable = Runnable {
        pushScheduled = false
        flushPending()
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        setClipChildren(true)
        setClipToPadding(true)
        // 必须可点击：不可点击的 View 收不到触摸，拖动就无从谈起
        isClickable = true
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO

        webView = createWebView(context)
        addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        reload()
    }

    /**
     * 切换到这个模型。
     *
     * 三件事一起换：页面地址、有没有右侧键帽、以及**这个模型真实支持哪些键**。
     * 由模型库统一解析，而不是让调用方挨个查 —— 分开查很容易漏掉一项，
     * 表现就是"改了模型但行为没变"。
     */
    fun applyTarget(model: Live2DModelEntry) {
        if (model.pageUrl == pageUrl && model.hasArrowSide == hasArrowSide &&
            model.supportedKeys == supportedKeys
        ) {
            return
        }
        AppLog.i(
            TAG,
            "切换模型：${model.displayName}（右侧键帽=${model.hasArrowSide}，" +
                "支持 ${model.supportedKeys.size} 个键）",
        )
        pageUrl = model.pageUrl
        hasArrowSide = model.hasArrowSide
        supportedKeys = model.supportedKeys
        reload()
    }

    private fun reload() {
        pageReady = false
        motionLogged = false
        pending.clear()
        /*
         * 待发位移也丢掉：JS 的 clear() 会把指针位置重置回屏幕中心，
         * 而位移是"相对上一次位置"的量 —— 把旧位置下的位移带过去，
         * 猫的头会从一个不对的起点开始偏。
         */
        pendingMotionDx = 0
        pendingMotionDy = 0
        runCatching { webView.stopLoading() }
        webView.loadUrl(pageUrl)
    }

    /*
     * ============================================================
     * 输入 → JS
     * ============================================================
     * 调用方（OverlayService）只做一件事：按键集合变化时把**整个集合**推进来。
     * 映射、去重、合并、补发全在这里 —— 与旧项目一致（那边也是 Service 把
     * 原始键码集合推进 View），好处是上层不需要理解 BongoCat 的任何语义。
     *
     * 状态字段声明在文件上方（init 之前），原因见那里的注释。
     */

    /**
     * 推入当前按下的键码集合（**必须在主线程调用**）。
     *
     * 调用方只需在集合变化时调用；集合没变时这里会直接返回。
     */
    fun updateKeys(codes: Set<Int>) {
        if (codes == latestCodes) return
        latestCodes = codes

        applyDiff(codes)

        // 页面没就绪时不必排推送：onPageFinished 会按最新状态整体补发
        if (pageReady) schedulePush()
    }

    /**
     * 推入鼠标**净位移**（**必须在主线程调用**）。
     *
     * 传入的是"自这条位移流被创建以来的总位移"（采集层已经把零点处理好了，
     * 所以第一个值必然是 0），不是单次事件的增量 —— 这里差分出新增量再按帧合并。
     * 这样即使中间被合并掉了若干次回调，位移也不会丢。
     */
    fun updateMouseMotion(totalDx: Long, totalDy: Long) {
        val dx = totalDx - sentMotionDx
        val dy = totalDy - sentMotionDy
        if (dx == 0L && dy == 0L) return

        /*
         * 每个页面只记一条"确实收到位移了"。
         *
         * 排查"猫对鼠标没反应"时，这条日志把问题一刀切成两半：
         * 完全没有这条 → 采集层没把 EV_REL 送上来；
         * 有这条而猫不动 → 问题在 JS 侧（模型没有对应参数，或渲染没起来）。
         */
        if (!motionLogged) {
            motionLogged = true
            AppLog.i(TAG, "收到鼠标位移，开始转发（首帧 $dx,$dy）")
        }

        sentMotionDx = totalDx
        sentMotionDy = totalDy
        pendingMotionDx += dx
        pendingMotionDy += dy

        if (pageReady) schedulePush()
    }

    /**
     * 算出"语义层面"的变化，放进 [pending]。
     *
     * 每次都用**完整集合重算**，而不是增量加减计数：调用方给的就是全量状态，
     * 重算最简单也最不容易错（增量计数一旦漏掉一条事件就会永久偏掉）。
     */
    private fun applyDiff(codes: Set<Int>) {
        val target = mutableMapOf<String, Int>()
        codes.forEach { code ->
            Live2DSemantics.of(code)?.let { name ->
                /*
                 * 只转发这个模型**真实提供键帽**的键（集合为空时不过滤）。
                 *
                 * 这一步只影响"猫的反应"，不影响键位显示本身：被过滤掉的键
                 * 只是不再让手往下按 —— 而那些键本来也没有键帽图可显示。
                 */
                if (supportedKeys.isNotEmpty() && name !in supportedKeys) return@let
                target[name] = (target[name] ?: 0) + 1
            }
        }

        // 松开：目标状态里已经没有的语义
        semanticOrder.toList().forEach { name ->
            if ((target[name] ?: 0) == 0) release(name)
        }

        // 按下：目标状态里有、但当前还没按下的语义
        target.keys.forEach { name ->
            if ((semanticCounts[name] ?: 0) == 0) press(name)
        }

        semanticCounts.clear()
        semanticCounts.putAll(target)

        // 鼠标左右键走 mouseButtons，不进 key()：否则会污染"最近按下的键"的顺序
        val mask = (if (codes.contains(KeyCodes.BTN_LEFT)) 1 else 0) or
            (if (codes.contains(KeyCodes.BTN_RIGHT)) 2 else 0)
        if (mask != pendingMouseMask) {
            pendingMouseMask = mask
            schedulePush()
        }
    }

    private fun press(name: String) {
        semanticOrder.remove(name)
        semanticOrder.add(name)
        pending += name to true
    }

    private fun release(name: String) {
        semanticOrder.remove(name)
        pending += name to false

        /*
         * 同侧还有别的键按着时，把最近按下的那个重新发一次。
         *
         * 为什么需要：JS 的每一侧只显示"最后一个按下的键"，而且松开时
         * 会把该侧整个清空（`setOverlay(side, null)`）。
         * 于是 Shift 按住不放、点一下 A 再松开 A，Shift 的手也会跟着放下来 ——
         * 看上去就是"手莫名掉了"。补发一次就正常了。
         */
        val side = Live2DSemantics.side(name, hasArrowSide) ?: return
        val fallback = semanticOrder.lastOrNull {
            Live2DSemantics.side(it, hasArrowSide) == side
        } ?: return
        pending += fallback to true
    }

    /**
     * 把这次的改动合并到**下一帧**再发。
     *
     * 直接一条事件发一次 `evaluateJavascript` 是不行的：那是一条跨进程调用，
     * 高速输入（或按键重复）下会把 WebView 主线程刷爆。
     * 合并到一帧意味着最多 60 次/秒，而且一帧内的多次按键会被压成一次。
     *
     * 注意 [pending] 里是"变化量"而不是"全量"：一帧内按下再松开同一个键时
     * 会先后发 true / false，JS 是状态机，按顺序处理的结果与逐条发送完全一致。
     */
    private fun schedulePush() {
        if (pushScheduled) return

        /*
         * 视图还没 attach 时不排这一帧。
         *
         * 此时页面也没就绪（窗口刚创建），状态已经记在 [latestCodes] 里，
         * onPageFinished 会整体补发 —— 排了也不会执行，反而可能把
         * pushScheduled 卡在 true 上，之后所有推送都被吞掉。
         */
        if (!isAttachedToWindow) return

        pushScheduled = true
        postOnAnimation(pushRunnable)
    }

    private fun flushPending() {
        val hasMotion = pendingMotionDx != 0L || pendingMotionDy != 0L
        if (pending.isEmpty() && !hasMotion && pendingMouseMask == currentMouseMask) return

        /*
         * 页面没就绪时**直接丢弃**这次变化量，而不是攒着。
         *
         * 丢弃是安全的：onPageFinished 会按 latestCodes 整体补发一遍，
         * 那份状态才是权威的。攒着反而会让加载期间的几百毫秒里
         * 堆积一大串已经没有意义的中间状态。
         *
         * 位移更是必须丢掉：那几百毫秒里鼠标可能已经划过半个屏幕，
         * 一次性灌进去只会让猫的头瞬间甩到边上。
         */
        if (!pageReady) {
            pending.clear()
            pendingMotionDx = 0
            pendingMotionDy = 0
            return
        }

        pending.forEach { (name, pressed) ->
            dispatchRaw(
                "window.AxonBongoCat&&AxonBongoCat.key(" +
                    JSONObject.quote(name) + "," + (if (pressed) "true" else "false") + ")",
            )
        }
        pending.clear()

        if (hasMotion) {
            dispatchMouseDelta(pendingMotionDx, pendingMotionDy)
            pendingMotionDx = 0
            pendingMotionDy = 0
        }

        if (pendingMouseMask != currentMouseMask) {
            currentMouseMask = pendingMouseMask
            dispatchMouseButtons(currentMouseMask)
        }
    }

    /**
     * 发一条鼠标位移。
     *
     * 第三、四个参数是**屏幕**尺寸（像素）：JS 用它把位移换算成 0..1 的归一化位置，
     * 所以必须给屏幕而不是悬浮窗自己的尺寸 —— 给错了猫的反应幅度就不对。
     *
     * 每条都带日志（**节流**：前几条逐条记，之后每 60 条记一条）。
     * 这么做是有理由的：鼠标"朝一个方向顶到底然后不动"这类问题，
     * 只有"到底发出去了什么值"能一刀切开 ——
     * 是根本没在发（值一直是 0），还是发出去的值本身就不对（越滚越大）。
     * 靠猜是猜不出来的，所以宁可留一条常驻的节流日志。
     */
    private fun dispatchMouseDelta(dx: Long, dy: Long) {
        // 必须是**显示器**尺寸而不是应用窗口尺寸：多窗口下后者偏小，
        // 归一化后的位移会被放大，猫的反应幅度就不对了
        val (screenWidth, screenHeight) = OverlayBounds.screenSize(context)
        dispatchRaw(
            "window.AxonBongoCat&&AxonBongoCat.mouseDelta(" +
                "$dx,$dy,$screenWidth,$screenHeight)",
        )

        motionDispatchCount++
        if (motionDispatchCount <= MOTION_LOG_HEAD || motionDispatchCount % MOTION_LOG_EVERY == 0) {
            AppLog.d(
                TAG,
                "mouseDelta #$motionDispatchCount: d=($dx,$dy) " +
                    "屏=${screenWidth}×${screenHeight} " +
                    "归一化后≈(${"%.4f".format(dx.toDouble() / screenWidth)}," +
                    "${"%.4f".format(dy.toDouble() / screenHeight)})",
            )
        }

        /*
         * 收到位移后**延迟**再体检一次。
         *
         * 前两次体检都在页面刚加载时（用户还没动鼠标），而"指针被顶到边上"
         * 这类问题只有动过之后才看得见。所以这里在位移稳定下来之后补一次，
         * 把页面认为的 target/cursor 打出来 —— 这是判断
         * "是我们发错了" 还是 "页面自己算错了" 的唯一直接证据。
         */
        if (motionDispatchCount == MOTION_PROBE_AFTER) {
            postDelayed({ logHealthCheck("位移后") }, MOTION_PROBE_DELAY_MS)
        }
    }

    private fun dispatchMouseButtons(mask: Int) {
        dispatchRaw("window.AxonBongoCat&&AxonBongoCat.mouseButtons($mask)")
    }

    /**
     * 页面就绪后把当前状态**整体补发**一遍。
     *
     * 这是最容易漏、也最难查的一处：WebView 加载要几百毫秒，这期间用户按下的键
     * 推过去会被静默丢掉，猫的状态就永远和实际不同步（比如"手一直按着不放"）。
     * 所以先 `clear()` 把 JS 侧清空，再按当前按下的键重建。
     */
    private fun flushAllToPage() {
        dispatchRaw("window.AxonBongoCat&&AxonBongoCat.clear()")

        semanticOrder.forEach { name ->
            dispatchRaw(
                "window.AxonBongoCat&&AxonBongoCat.key(" +
                    JSONObject.quote(name) + ",true)",
            )
        }

        currentMouseMask = pendingMouseMask
        dispatchMouseButtons(currentMouseMask)
    }

    private fun dispatchRaw(script: String) {
        runCatching { webView.evaluateJavascript(script, null) }
    }

    /**
     * 把**所有**触摸都抢过来，一个都不给 WebView。
     *
     * 返回 true 之后，后续事件会送到本视图自己的 `OnTouchListener`（拖动逻辑）。
     * 这里的 WebView 不需要任何交互 —— 它是画面，不是浏览器。
     */
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = true

    override fun onTouchEvent(event: MotionEvent): Boolean = true

    /**
     * 彻底释放 WebView；不显式销毁会泄漏实例（自带的渲染线程不会自己停）
     */
    fun release() {
        pageReady = false
        pending.clear()
        pendingMotionDx = 0
        pendingMotionDy = 0
        semanticCounts.clear()
        semanticOrder.clear()
        latestCodes = emptySet()
        runCatching { webView.stopLoading() }
        runCatching { webView.loadUrl("about:blank") }
        runCatching { removeView(webView) }
        runCatching { webView.destroy() }
    }

    /*
     * ============================================================
     * 内部
     * ============================================================
     */

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: Context): WebView = WebView(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
        /*
         * 硬件层：WebGL 必须有 GPU 上下文。
         *
         * 顺带说明一个排查方向：如果窗口本身不是硬件加速的（`isHardwareAccelerated`
         * 为 false），Chromium 画不出任何东西，表现就是"窗口在、里面全空"。
         * 所以 Service 那边给窗口加了 FLAG_HARDWARE_ACCELERATED，并且会打一条日志。
         */
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        setVerticalScrollBarEnabled(false)
        setHorizontalScrollBarEnabled(false)
        overScrollMode = View.OVER_SCROLL_NEVER
        isClickable = false
        isFocusable = false

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = true
            allowContentAccess = false
            // 禁网：页面只从 assets 读，不需要也不应该联网
            blockNetworkLoads = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            defaultTextEncodingName = "utf-8"
            // 这条必须开：index.html 要按相对路径读贴图和 mocdata.js
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = false
        }

        webChromeClient = object : WebChromeClient() {
            /**
             * 把 JS 的 console 接到应用日志。
             *
             * 这不是临时调试措施：模型初始化失败时 runtime.js 会走
             * `console.error('BongoCat source model init failed', error)`，
             * 那是**唯一**能说明"为什么是空的"的线索，而用户能看到的只有调试页。
             */
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                AppLog.i(
                    TAG,
                    "JS ${message.messageLevel()}: ${message.message()} @${message.lineNumber()}",
                )
                return true
            }
        }

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean = true

            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                AppLog.i(TAG, "页面加载完成：$url（硬件加速=${view.isHardwareAccelerated}）")

                // 加载期间按下的键在这里一次性补上，否则猫的状态会一直不对
                flushAllToPage()

                // 模型是异步初始化的，隔一会儿再看结果
                postDelayed({ logHealthCheck("首次") }, HEALTH_CHECK_DELAY_MS)
                postDelayed({ logHealthCheck("复检") }, HEALTH_CHECK_RETRY_DELAY_MS)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                AppLog.e(TAG, "页面加载失败：${error.errorCode} ${error.description}（${request.url}）")
            }
        }
    }

    /**
     * 自诊断：一句话说清"核心库加载了没 / 模型起来没 / 画布多大 / 指针在哪"。
     *
     * 为什么要专门做这个：Live2D 出问题时画面可能是全空、也可能停在封面图上，
     * 光看画面分不清是**核心库没加载**、**moc 解析失败**还是**画布尺寸为 0**。
     * 用户能提供的信息只有日志，所以日志必须自己把结论说清楚。
     */
    private fun logHealthCheck(stage: String) {
        /*
         * 直接把页面自己的状态机打出来。
         *
         * `state` 是 runtime.js 里 IIFE 顶层的 const —— 它对**同一页面的脚本可见**，
         * 所以这里不用改那个（受许可证约束、要求原样转发的）上游文件，
         * 就能读到页面**真实认为**的指针位置：
         *
         * - target 是"位移累加出来的目标位置"，cursor 是阻尼跟随后的实际位置，
         *   两者都在 0..1 之间；
         * - 如果 target 卡在 0 或 1 → 是**发过去的位移把它顶到边上**了
         *   （对照本端那条 mouseDelta 日志看是哪个值不对）；
         * - 如果 target 在正常范围内而猫不动 → 问题在渲染/参数，与位移无关。
         *
         * "猫乱动"这类问题只有这一条证据能一刀切开，所以常驻。
         * 取不到 state（上游改了实现）时给出 'no-state'，不影响其它判据。
         */
        val script = """
            (function () {
              var canvas = document.getElementById('live2dCanvas');
              var s = (typeof state !== 'undefined') ? state : null;
              return JSON.stringify({
                core: !!window.Live2DCubismCore,
                ready: !!(window.AxonBongoCat && window.AxonBongoCat.isReady()),
                canvas: canvas ? (canvas.width + 'x' + canvas.height) : 'missing',
                target: s ? [s.targetX, s.targetY] : 'no-state',
                cursor: s ? [s.cursorX, s.cursorY] : 'no-state',
                pointerActive: s ? s.pointerActive : 'no-state'
              });
            })()
        """.trimIndent()

        runCatching {
            webView.evaluateJavascript(script) { result ->
                /*
                 * 把"本端按住几个键"与"已经发出多少条 mouseDelta"一起打出来。
                 *
                 * 这是排查"猫不动 / 猫乱动"的关键分界线：
                 * - 按住键时这里一直是 0 → **采集层没把按键送到**，与 JS 无关；
                 * - 这里是 1（或更多）而猫不动 → 问题在 JS 侧，看上面的 console 日志。
                 */
                AppLog.i(
                    TAG,
                    "健康检查（$stage）：$result" +
                        "（本端按住 ${semanticOrder.size} 个键，" +
                        "已发 mouseDelta $motionDispatchCount 条）",
                )
            }
        }
    }
}

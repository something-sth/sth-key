package com.something.sthkey.capture.touch

import com.something.sthkey.domain.config.TouchRegion
import com.something.sthkey.domain.config.TouchRegionBinding
import com.something.sthkey.domain.config.TouchSettings
import com.something.sthkey.core.log.AppLog
import com.something.sthkey.domain.config.TouchStickFeel

/**
 * 触屏输入的**跨帧状态机**（普通类，可完整单测）。
 *
 * ============================================================
 * 它管四件"必须记住"的事
 * ============================================================
 * | 状态 | 为什么必须记 |
 * |---|---|
 * | **触点的归属**（按下时落在哪块区域） | 手指划出区域后那个键/摇杆不该突然失去输入 |
 * | **上一帧的触点位置** | 视角摇杆是"增量"语义，没有上一帧就没有增量 |
 * | **视角摇杆的累计值** | 它**不回中**（用户明确要求），所以要一直累加 |
 * | **哪些槽位正被按住** | 按键区域是"按下/抬起"语义，要在抬起的那一帧报出去 |
 *
 * ⚠️ 抽成普通类而不是塞进 Service / Composable:
 * 这些事都**跨帧**，而跨帧状态是"真机上偶尔不对、单测里测不到"
 * 的重灾区。抽出来之后可以用假数据一帧一帧喂，把每种边界都钉住。
 *
 * ============================================================
 * ⚠️ 区域分两类，语义完全不同
 * ============================================================
 * | 区域绑的槽位 | 按下时算什么 |
 * |---|---|
 * | [TouchSettings.JOYSTICK_SLOT_ID] | 算**偏移向量**给移动轮盘（推得越远越偏，松手回中） |
 * | 真实按键槽位（`A_BUTTON` / `LMB` / …） | **那个键就按下**，松手抬起 |
 *
 * ⚠️ 视角摇杆**没有区域** —— 它吃"所有没落在任何区域里的滑动"。
 * 所以上面那张表里没有它。
 *
 * ============================================================
 * ⚠️ 与鼠标摇杆那条路的对照
 * ============================================================
 * 「摇杆-鼠标」当年也有一份类似的累加逻辑，而它**只靠 `remember`
 * 存两个变量**（见 `mouseJoystickStep`）。触屏这边更复杂（多触点、
 * 归属、多块区域），所以做成显式状态机而不是散在渲染层里。
 *
 * ⚠️ 但**回中那件事不要再试**:鼠标摇杆写过四版自动回中、全部失败
 * 最后删掉（见 `MouseJoystickFeel` 的注释）。触屏这里从一开始就
 * 明确"视角摇杆不回中、移动轮盘回中"，别再给视角摇杆加回中。
 */
internal class TouchFrameTracker {

    /**
     * 每个触点**按下时**落在哪些区域（槽位 id 列表）。
     *
     * ⚠️ 是**列表**而不是单个值 —— 重叠的区域全部生效
     * （见 [onFrame] 里"重叠"那段说明）。
     * 空列表 = 没落在任何区域（那个触点归视角摇杆）。
     */
    private val belongsTo = mutableMapOf<Int, List<String>>()

    /** 上一帧的触点位置（已换算到较短边归一化坐标） */
    private var previous = emptyMap<Int, Pair<Float, Float>>()

    /** 视角摇杆的累计值（**不回中**） */
    private var cameraX = 0f
    private var cameraY = 0f

    /** 移动轮盘的当前向量 */
    private var moveX = 0f
    private var moveY = 0f

    /** 这一帧被按住的**按键槽位**（区域产生的那些） */
    private var pressedSlots: Set<String> = emptySet()

    /**
     * 这一帧每块**轮盘类区域**的向量（区域 id → 向量）。
     *
     * ⚠️ 见 [Snapshot.moveRegions] 的说明 —— 自定义 Key 那边靠它
     * 把"哪块区域在推"对回**绑定到它的组件**。
     */
    private var moveRegions: Map<String, Pair<Float, Float>> = emptyMap()

    /**
     * 喂一帧。
     *
     * @param rawPoints 原生给的触点（**面板自然方向**、满屏归一化）
     * @param rotation 当前屏幕旋转（[android.view.Surface] 的常量）
     * @param screenWidth 当前屏幕宽（像素）—— 当前方向下的宽
     * @param screenHeight 当前屏幕高（像素）
     * @param regions 全部触摸区域；空列表 = 用户还没配过
     * @param moveFeel 移动轮盘的手感
     * @param cameraFeel 视角摇杆的手感
     */
    fun onFrame(
        rawPoints: List<TouchNativeProtocol.Point>,
        rotation: Int,
        screenWidth: Int,
        screenHeight: Int,
        regions: List<TouchRegionBinding>,
        moveFeel: TouchStickFeel,
        cameraFeel: TouchStickFeel,
        /**
         * **被当作轮盘用的**区域 id。
         *
         * 触屏样式是那个固定的 `@move` 槽位；而**自定义 Key 里是它自己的
         * 轮盘区域**（各有各的 id）。见 [moveClaimedSlots] —— 它决定
         * "哪些区域不该算作按键、哪些触点不喂视角摇杆"。
         *
         * ⚠️ 留空时按"只有触屏样式那一块轮盘"处理。
         */
        moveSlotIds: Set<String> = emptySet(),
        /** 面板自然方向像素尺寸；`0` = 不知道（诊断自检会跳过） */
        naturalWidth: Int = 0,
        naturalHeight: Int = 0,
    ) {
        /*
         * 1. 旋转 → 当前屏幕方向。
         *
         * ⚠️ 顺序不能反:旋转是"面板方向 → 屏幕方向"的**坐标轴互换**，
         * 而下面的短边归一化要用**换算之后**的屏幕宽高（旋转后宽高会互换）。
         * 反过来的话横屏下所有 X/Y 都会被乘错一个比例。
         */
        val rotated = TouchRotation.mapPoints(rawPoints, rotation)

        /* 整屏归一化 —— **命中测试**用它（区域中心也是这个单位） */
        val screenPoints = rotated.associate { p -> p.id to (p.x to p.y) }

        /* 较短边归一化 —— **算向量与增量**用它（区域边长是这个单位） */
        val shortPoints = rotated.associate { p ->
            p.id to TouchInput.toShortEdge(p.x, p.y, screenWidth, screenHeight)
        }

        /*
         * 2. 清理已经抬起的触点。
         *
         * ⚠️ 必须清:不清的话 id 会被系统复用（同一根手指再按下去可能
         * 拿到同一个 id），而残留的归属会让"这次按在屏幕上"被当成
         * "上次那块区域里的手指又动了"。
         */
        belongsTo.keys.retainAll(screenPoints.keys)

        /*
         * 3. 给**新出现**的触点定归属（只在按下那一刻定一次）。
         *
         * ⚠️ 用 `containsKey` 判断"是不是新的"，而不是"这一帧在不在区域里" ——
         * 后者每帧都会重新归类，手指划出去的那一帧归属就变了
         * （于是摇杆突然回中、按键突然抬起）。
         *
         * ============================================================
         * ⚠️⚠️ 重叠的区域**全部生效**（不是"第一个赢"）
         * ============================================================
         * 用户的原话:
         *
         * > 其实重叠应该做的是**所有重叠的所有触摸区域互不干扰**吧，
         * > 我不清楚能不能实现，但是这样搞应该功能范围更广一点
         *
         * ⚠️ 能做到，而且这正是自定义布局里"前后左右"玩法的**基础**:
         *
         * > 反正做了重叠的话，**多搞几个摇杆在同一个位置选前后左右
         * > 也是一样的效果**
         *
         * ⚠️ 我原来的实现是 `firstOrNull` —— 列表靠前的那块**永远赢**，
         * 于是"拖到一起的两块区域"里总有一块**永远不亮**，
         * 而那个现象**用户查不出来**（他只会觉得"这个按键坏了"）。
         *
         * ============================================================
         * ⚠️ 唯一的例外:**移动区域**只认一块（列表靠前的那个）
         * ============================================================
         * 一个触点只该推**一个**摇杆 —— 若两块移动区域重叠，
         * 让它们各自算出不同的向量、同时推两个摇杆，那是说不通的行为。
         * 所以取**列表里第一个**移动区域，并把触点算在它头上。
         *
         * ⚠️ 而"落进任何区域里的触点不喂视角摇杆"这条**保留** ——
         * 不保留的话，推轮盘会**同时转视角**。
         */
        val moveSlotId = regions.firstOrNull {
            it.slotId == TouchSettings.JOYSTICK_SLOT_ID
        }?.slotId

        screenPoints.forEach { (id, point) ->
            if (belongsTo.containsKey(id)) return@forEach

            /* ⚠️ 找出**全部**命中的区域 —— 不是第一个 */
            val hits = regions.filter { binding ->
                TouchInput.regionContains(
                    TouchRegion(binding.centerX, binding.centerY, binding.size),
                    point,
                    screenWidth,
                    screenHeight,
                    density,
                )
            }
            /*
             * ⚠️ 移动区域最多算一块（见上面那段）——
             * 其余命中的槽位**照样记**，于是它们能同时生效。
             */
            val slotIds = hits.map { it.slotId }.toMutableList()
            if (moveSlotId != null && slotIds.count { it == moveSlotId } > 1) {
                /* 只留一个移动区域，其余重复的丢掉 */
                var kept = false
                slotIds.removeAll { slotId ->
                    if (slotId != moveSlotId) return@removeAll false
                    if (kept) return@removeAll true
                    kept = true
                    false
                }
            }
            belongsTo[id] = slotIds
        }

        /*
         * ⚠️ 抬起的触点在第 2 步已经清过了（`belongsTo.keys.retainAll(...)`），
         * 这里**不要**再清一遍 —— 重复清理会让人以为"两处各有一套规则"。
         */

        /* 落在**轮盘**区域里的触点（可能多根手指） */
        val moveIds = belongsTo.filterValues { TouchSettings.JOYSTICK_SLOT_ID in it }.keys

        /*
         * ⚠️⚠️ **被当作轮盘用的**区域 —— 触屏样式是那个固定的 `@move` 槽位，
         * 而**自定义 Key 里是它自己的轮盘区域**（各有各的 id）。
         *
         * ⚠️ 下面两处用它:
         *
         * | 用在哪 | 不这么做会怎样 |
         * |---|---|
         * | 从 `pressedSlots` 里剔掉 | 推摇杆时那个**按键组件也会亮** |
         * | 算进"不喂视角摇杆的触点" | 推移动摇杆**会把视角一起带偏** |
         *
         * ⚠️ 由调用方给（它才知道哪些区域是轮盘）—— 本层只知道"区域 id"，
         * 不认识 `CustomTouchRegion.Kind` 那种领域概念。
         */
        val moveClaimedSlots = moveSlotIds + TouchSettings.JOYSTICK_SLOT_ID
        val moveClaimedPointIds = belongsTo
            .filterValues { ids -> ids.any { it in moveClaimedSlots } }
            .keys

        /* 4. 移动轮盘:取**第一根**落在区域里的手指 */
        val moveRegionBinding = regions.firstOrNull { it.slotId == TouchSettings.JOYSTICK_SLOT_ID }
        val movePoint = moveIds.firstNotNullOfOrNull { shortPoints[it] }
        if (movePoint != null && moveRegionBinding != null) {
            val region = TouchRegion(
                moveRegionBinding.centerX,
                moveRegionBinding.centerY,
                moveRegionBinding.size,
            )
            val (vx, vy) = TouchInput.moveVector(
                region = region,
                pointShortEdge = movePoint,
                centerShortEdge = TouchInput.centerToShortEdge(region, screenWidth, screenHeight),
                feel = moveFeel,
            )
            moveX = vx
            moveY = vy
        } else {
            /*
             * ⚠️ 没手指在轮盘上 → 向量归零（**这个要回中**，
             * 与视角摇杆相反）。摇杆的物理含义就是"手推着它"，
             * 松手不回中会变成一个永远偏着的轮盘。
             */
            moveX = 0f
            moveY = 0f
        }

        /*
         * 5. 按键区域:被按住的槽位 = 这一帧有触点归属到它（且不是轮盘）。
         *
         * ⚠️ 用 `flatten()` —— 一个触点可以同时算在**多块**区域上（重叠），
         * 于是那几个组件会**一起亮**（用户要的就是这个）。
         *
         * ⚠️ "轮盘"那几块要从这里**剔掉** —— 见上面 `moveClaimedSlots`。
         * 不剔的话，推摇杆时那个**按键组件也会亮**（因为摇杆区域的 id
         * 也在 `belongsTo` 里）。
         */
        pressedSlots = belongsTo.values
            .flatten()
            .filter { it !in moveClaimedSlots }
            .toSet()

        /* 6. 视角摇杆:累加增量（不回中） */
        val delta = TouchInput.cameraDelta(
            previous = previous,
            current = shortPoints,
            /*
             * ⚠️⚠️ "落进轮盘里的触点**不喂视角摇杆**" —— 不这样的话
             * 推移动摇杆会**同时转视角**。
             *
             * ⚠️ 我原来只传了 `moveIds`（= 触屏样式的那个 `@move` 槽位），
             * 而**自定义 Key 里根本没有那个槽位** —— 于是自定义布局下
             * 推移动摇杆**会把视角一起带偏**。
             *
             * ⚠️ 现在把"被当作轮盘用的"那几块区域里的触点**并进来** ——
             * 触屏样式是 `@move` 那一块，自定义是它的轮盘区域。
             */
            pointsInMoveRegion = moveIds + moveClaimedPointIds,
        )
        val (nx, ny) = TouchInput.accumulateCamera(cameraX to cameraY, delta, cameraFeel)
        cameraX = nx
        cameraY = ny

        /*
         * 7. **每一块轮盘类区域**的向量 —— 见 [Snapshot.moveRegions] 与
         * [regionMoveVectors] 的说明（自定义 Key 那边靠它）。
         *
         * ⚠️⚠️ 归属用 `belongsTo`（**按下那一刻定的**），不是"每帧重新判
         * 哪根手指现在在区域里" —— 后者是**一卡一卡**的根源:
         *
         * | 判据 | 手指滑动时 |
         * |---|---|
         * | **按下时定归属**（与上面第 4 步的 `move` 逐字同一条路） | ✅ 一直推着，不抖 |
         * | 每帧重判"现在在不在区域内" | ⚠️ 边缘上这一帧算、下一帧不算 → **一顿一顿** |
         *
         * ⚠️ 用户报的正是自定义 Key 摇杆"常规模式有卡顿"。而触屏样式
         * （第 4 步那条路）**从来没这个问题** —— 差别就在这里。
         */
        moveRegions = regionMoveVectors(
            rawPoints = rawPoints,
            rotation = rotation,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            regions = regions,
            feel = moveFeel,
            /*
             * ⚠️⚠️ **只有轮盘**那几块才算向量 —— 不能传"所有区域"。
             *
             * ⚠️ 我上一版传的是 `regions.map { it.slotId }`（全部），
             * 于是**按键区域也被算了向量** —— 而它一被算向量，
             * 就会进 `moveByComponent` 里那个"方向为 null 的绑定"，
             * 于是**点屏幕上任何地方都会推摇杆**（用户实测）:
             *
             * > 现在移动摇杆，**点屏幕任何地方都会触发**
             */
            moveRegionIds = moveClaimedSlots,
            /* ⚠️ 归属表整份给下去 —— 逐**区域**查，不是用全局那一堆 */
            ownership = belongsTo,
        )

        previous = shortPoints

        /* 真机诊断 —— 见 [debugLine] 的说明 */
        debugLine(
            rotation = rotation,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            regions = regions,
            rawPoints = rawPoints,
            /*
             * ⚠️ 自然屏尺寸由调用方给（它才拿得到 `Display`）。
             * 拿不到时给 0 —— [TouchRotation.isConsistent] 会跳过判断，
             * 不报假警。
             */
            naturalWidth = naturalWidth,
            naturalHeight = naturalHeight,
        )?.let { line -> onDebugLine?.invoke(line) }
    }

    /**
     * 全部触点抬起时的收尾。
     *
     * ⚠️ 与 [onFrame] 的区别:这里**不动视角摇杆的累计值** ——
     * 它按设计就是不回中的。只把移动轮盘归零、把按键全部抬起、
     * 把"上一帧"清空（清空是为了让下一次按下被当成新触点，
     * 而不是"从上一个位置滑过来"）。
     */
    fun onReleased() {
        moveX = 0f
        moveY = 0f
        pressedSlots = emptySet()
        /* ⚠️ 每块轮盘区域的向量也要清 —— 不清的话松手后它们还留着上一帧的值 */
        moveRegions = emptyMap()
        previous = emptyMap()
        belongsTo.clear()
    }

    /** 停止采集/切换配置时彻底复位（连视角摇杆也回中） */
    fun reset() {
        onReleased()
        cameraX = 0f
        cameraY = 0f
    }

    /** 当前移动轮盘向量（`-1..1`，Y 向下为正） */
    fun moveStick(): Pair<Float, Float> = moveX to moveY

    /** 当前视角摇杆值（`-1..1`，Y 向下为正，**不回中**） */
    fun cameraStick(): Pair<Float, Float> = cameraX to cameraY

    /**
     * 这一帧被按住的**按键槽位** id。
     *
     * ⚠️ 渲染层再把它翻译成键码（`config.keyMappings`）——
     * 状态机不认识键码，它只认槽位（与 `KeyLayout.Id` 同一套）。
     */
    fun pressedSlots(): Set<String> = pressedSlots

    /** 诊断用:当前记着几个触点、各有几个属于轮盘 */
    fun debugInfo(): String =
        "触点=${previous.size} 归属轮盘=${belongsTo.count { TouchSettings.JOYSTICK_SLOT_ID in it.value }}"

    /*
     * ============================================================
     * 真机诊断（用户报「按 LT 位置却几乎不亮、亮的是 RT」时加的）
     * ============================================================
     * ⚠️ 它打的是**判定过程的全部输入**:触点的原始坐标、旋转、屏幕尺寸、
     * 每块区域的命中结果，以及每块区域**实际覆盖的范围**。
     *
     * 有了它就能一眼分辨下面几种完全不同的成因:
     *
     * | 现象 | 说明 |
     * |---|---|
     * | 坐标与手指位置**对不上** | 坐标系/旋转错了 |
     * | 坐标对、但**没命中任何区域** | 区域范围或单位错了（比如纵向被压扁） |
     * | 命中了**另一块** | 槽位绑定或区域重叠顺序错了 |
     * | 命中对了、向量也对 | 问题在下游（渲染/点灯那条路） |
     *
     * ⚠️⚠️ 这里**只产出文本、不自己打日志**。
     *
     * 我第一版直接调 `AppLog.i`，于是 `TouchFrameTrackerTest` 全线报
     * `Method i in android.util.Log not mocked` —— JVM 单测里
     * `android.util.Log` 是空壳。**tracker 是纯逻辑类**，不该依赖 Android
     * 的日志设施（那会让它无法单测，而它恰恰是最需要单测的一层）。
     *
     * ⚠️⚠️ **默认关着** —— 排查时才开。
     *
     * ============================================================
     * ⚠️ 我在这上面反复错过两次，都记下来
     * ============================================================
     * | 版本 | 问题 |
     * |---|---|
     * | 第一版 | 默认关、还**让用户自己去改一行代码** —— 把活推给用户，错的 |
     * | 第二版 | 默认开 —— 用户复现时能直接看到，但**长期开着是噪声** |
     *
     * ⚠️ 现在默认关、但**代码留着**:真要排查某个触屏问题时，
     * 把这里改成 `true` 重新编译即可，不用重写一遍诊断。
     *
     * ⚠️ 开着时的开销可以忽略:只在**触点集合变化**时拼一次字符串
     * （触点变化频率远低于屏幕刷新率），而不是每帧。
     */
    var debugEnabled: Boolean = false

    /**
     * 显示密度 —— 命中判定要用它把 `TouchRegionGeometry.MIN_SIDE_DP` 换成像素。
     *
     * ⚠️ **必须与编辑器用同一个密度值**:`TouchRegionEditorService` 用的是
     * `resources.displayMetrics.density`。两处不一致的话，"最小 32dp"
     * 在两边的像素数就不同 → 又是"画的与判定的不一样"。
     *
     * ⚠️ 默认 `0f` = 不加下限（纯按 `size` 算）。单测用这个默认值，
     * 免得每条用例都得编一个密度。
     */
    var density: Float = 0f

    /** 上一次已经报过的触点快照 —— 只在**变化**时产出，免得刷爆日志 */
    private var lastDebugKey: String? = null

    /**
     * 这一帧的诊断文本；没有变化或没开诊断时返回 `null`。
     *
     * 调用方负责把它送进日志。
     */
    private fun debugLine(
        rotation: Int,
        screenWidth: Int,
        screenHeight: Int,
        regions: List<TouchRegionBinding>,
        rawPoints: List<TouchNativeProtocol.Point>,
        naturalWidth: Int,
        naturalHeight: Int,
    ): String? {
        if (!debugEnabled) return null

        /*
         * ⚠️ 只在**触点集合变化**时产出（按下 / 抬起 / 移动到位）。
         * 每帧都产会把 logcat 刷爆，而"手指按下那一刻"才是关键信息。
         */
        val key = rawPoints.joinToString("|") { "${it.id}:${it.x},${it.y}" }
        if (key == lastDebugKey) return null
        lastDebugKey = key

        val w = screenWidth.coerceAtLeast(1)
        val h = screenHeight.coerceAtLeast(1)
        val short = minOf(w, h).toFloat()

        /*
         * ============================================================
         * ⚠️⚠️ 两套坐标都要打出来（这是排查"区域偏了"的关键）
         * ============================================================
         * 用户报的现象:
         *
         * > 是**实际触摸区域比配置的触摸区域向左偏一点**，这也就解释了
         * > 为什么我按 LT 只有偶尔才能亮起来而且亮的还是 RT 位置
         *
         * ⚠️ 那是**横屏才有**的偏移，而横屏下唯一多出来的一步就是
         * **旋转换算**。所以必须同时看到:
         *
         * | 打什么 | 用来看 |
         * |---|---|
         * | `原生` | 面板自然方向的坐标（native 报的，**未换算**） |
         * | `屏` | 旋转+归一化之后的坐标（**命中测试真正用的**） |
         *
         * 两者只有 `旋转 != 0` 时才不同 —— 于是:
         * - 若"屏"坐标与你手指位置**对不上** → 旋转换算错了；
         * - 若"屏"坐标对得上、却仍然没命中 → 区域范围/单位错了。
         *
         * ⚠️ 我第一版只打了原生坐标，于是**看不出是哪一段偏的**
         * （原生坐标本来就是"错的"那个方向，它偏是正常的）。
         */
        val mapped = TouchRotation.mapPoints(rawPoints, rotation)

        /*
         * ⚠️ 顺手做一次**方向自检**（见 [TouchRotation.isConsistent]）。
         *
         * 它判的是"这个旋转角度与屏幕/自然屏的宽高比是否自洽" ——
         * 不自洽说明**旋转方向搞反了**，而那会表现为
         * "触摸区域整体镜像/偏移"（用户报的正是偏移）。
         *
         * ⚠️ 只在启用诊断时算（它要读系统属性，不该每帧跑）。
         */
        val consistent = TouchRotation.isConsistent(
            rotation = rotation,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            naturalWidth = naturalWidth,
            naturalHeight = naturalHeight,
        )
        val consistencyNote = when {
            naturalWidth <= 0 || naturalHeight <= 0 -> "自然屏未知"
            consistent -> "自检通过"
            else -> "⚠️⚠️ 自检**不通过** —— 旋转方向可能搞反了！" +
                "（自然屏 ${naturalWidth}x$naturalHeight 与当前屏 ${screenWidth}x$screenHeight " +
                "在旋转 $rotation 下不自洽）"
        }

        val points = rawPoints.mapIndexed { index, p ->
            val m = mapped[index]
            val hit = regions.firstOrNull { binding ->
                TouchInput.regionContains(
                    TouchRegion(binding.centerX, binding.centerY, binding.size),
                    m.x to m.y,
                    screenWidth,
                    screenHeight,
                    density,
                )
            }
            "触点${p.id} 原生(${"%.3f".format(p.x)},${"%.3f".format(p.y)})" +
                " → 屏(${"%.3f".format(m.x)},${"%.3f".format(m.y)})" +
                " → ${hit?.slotId ?: "无"}"
        }.joinToString(" | ")

        val bounds = regions.joinToString(" ") { b ->
            val halfX = b.size / 2f * short / w
            val halfY = b.size / 2f * short / h
            "${b.slotId}[x ${"%.3f".format(b.centerX - halfX)}~${"%.3f".format(b.centerX + halfX)}" +
                " y ${"%.3f".format(b.centerY - halfY)}~${"%.3f".format(b.centerY + halfY)}]" +
                /* ⚠️ 像素范围也打出来，好与编辑器里看到的方块直接比对 */
                "(px x ${"%.0f".format((b.centerX - halfX) * w)}~${"%.0f".format((b.centerX + halfX) * w)}" +
                " y ${"%.0f".format((b.centerY - halfY) * h)}~${"%.0f".format((b.centerY + halfY) * h)})"
        }

        return "旋转=$rotation 屏=${w}x$h 短边=${short.toInt()} | $consistencyNote | " +
            "$points | 区域: $bounds | " +
            "轮盘向量=(${"%.2f".format(moveX)},${"%.2f".format(moveY)}) " +
            "按住=${pressedSlots.joinToString(",").ifEmpty { "无" }}"
    }

    /** 诊断文本的**出口** —— 由调用方设成"打进日志" */
    var onDebugLine: ((String) -> Unit)? = null

    /**
     * 上一帧的序号 —— 用来保证**同一帧只推进一次**。
     *
     * ⚠️ 调用方（`OverlayContent`）现在在**组合期**推进状态
     * （而不是在 `LaunchedEffect` 里 —— 那会造成一帧延迟，见
     * [advance] 的说明）。而组合可能因为**别的原因**重跑
     * （配置变了、尺寸变了…），那时帧序号没变，就不该再推进一次。
     */
    private var lastSeq: Long = Long.MIN_VALUE

    /**
     * 按帧序号推进状态；**同一序号只推进一次**。
     *
     * @return 这一帧处理完之后的 [Snapshot]
     */
    fun advance(
        seq: Long,
        rawPoints: List<TouchNativeProtocol.Point>,
        rotation: Int,
        screenWidth: Int,
        screenHeight: Int,
        regions: List<TouchRegionBinding>,
        moveFeel: TouchStickFeel,
        cameraFeel: TouchStickFeel,
        /** **被当作轮盘用的**区域 id；见 [onFrame] 的同名参数 */
        moveSlotIds: Set<String> = emptySet(),
        /**
         * 面板**自然方向**的像素尺寸（诊断自检用）。
         *
         * ⚠️ 拿不到就给 `0` —— 自检会跳过，不会报假警。
         */
        naturalWidth: Int = 0,
        naturalHeight: Int = 0,
    ): Snapshot {
        if (seq != lastSeq) {
            lastSeq = seq
            onFrame(
                rawPoints = rawPoints,
                rotation = rotation,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                regions = regions,
                moveFeel = moveFeel,
                cameraFeel = cameraFeel,
                moveSlotIds = moveSlotIds,
                naturalWidth = naturalWidth,
                naturalHeight = naturalHeight,
            )
        }
        return snapshot()
    }

    /** 当前的输出（摇杆读数 + 按住的槽位） */
    fun snapshot(): Snapshot = Snapshot(
        move = moveX to moveY,
        camera = cameraX to cameraY,
        pressedSlots = pressedSlots,
        moveRegions = moveRegions,
    )

    /**
     * 一帧的输出。
     *
     * ============================================================
     * ⚠️⚠️ 为什么要有这个类型（而不是让调用方分别读三个方法）
     * ============================================================
     * 用户实测报的现象:
     *
     * > 我按下 LT 的位置，**不会有变化，我松手-LT 亮起，再按下-LT 熄灭**
     *
     * ⚠️ 那是**一帧延迟**:调用方原来在 `LaunchedEffect` 里推进状态，
     * 而 `LaunchedEffect` 在**组合完成之后**才跑 —— 于是组合期读到的是
     * **上一帧**的 tracker 状态，画到屏幕上的也是上一帧。
     *
     * ⚠️ 现在改成组合期推进（见 [advance]），并且**一次拿全**:
     * 摇杆与按键如果分两次读，中间可能夹着一次状态更新，
     * 于是同一帧的摇杆与按键可能来自**不同的帧**（表现是两者不同步）。
     */
    data class Snapshot(
        /** 移动轮盘向量 */
        val move: Pair<Float, Float>,
        /** 视角摇杆值 */
        val camera: Pair<Float, Float>,
        /** 这一帧被按住的**按键槽位** id */
        val pressedSlots: Set<String>,
        /**
         * 这一帧落在**"轮盘类"区域**里的触点算出的向量（槽位 id → 向量）。
         *
         * ============================================================
         * ⚠️ 为什么需要它（自定义 Key 那边靠它）
         * ============================================================
         * [move] 只有**一个**，而且只由 [TouchSettings.JOYSTICK_SLOT_ID]
         * 那块区域产生 —— 那是**触屏样式**的约定。
         *
         * ⚠️ 自定义 Key 里区域是**配置级的独立实体**（可以有好几块轮盘区域，
         * 每块绑不同的组件），所以"哪块区域在推"必须**按区域**回答。
         *
         * ⚠️ 与 [pressedSlots] 的区别:[pressedSlots] 是"按到了就算"
         * （按键语义），而这里算的是**向量**（摇杆语义）——
         * 两者取的区域集合也不同（见 [advance] 里那两段过滤）。
         */
        val moveRegions: Map<String, Pair<Float, Float>> = emptyMap(),
    ) {
        /**
         * 摇杆当前是否指向某个方向（八段式）。
         *
         * ============================================================
         * ⚠️ 判据与键盘摇杆的"八段式"一致
         * ============================================================
         * 只看**该轴上的符号**，不看大小:
         *
         * | 方向 | 判据 |
         * |---|---|
         * | 前 | `y < 0` |
         * | 后 | `y > 0` |
         * | 左 | `x < 0` |
         * | 右 | `x > 0` |
         *
         * ⚠️ 于是斜向（比如前左）时 `FRONT` 与 `LEFT` **都成立** ——
         * 那正是用户要的八段式:
         *
         * > 摇杆斜向时候是**两个都算**，这样才有八段式，没问题
         *
         * ⚠️ 用户还指出过一个玩法:把两个"触屏移动摇杆"组件叠在同一块区域上、
         * 各选一个方向，效果等同于给一个组件绑多个方向 ——
         * 所以这里必须支持"两个方向同时成立"。
         *
         * ⚠️ [deadZone] 之上才算 —— 否则手指刚按下去的微小抖动
         * 会让四个方向一起亮。
         */
        fun pointsToward(
            direction: com.something.sthkey.domain.custom.TouchDirection,
            deadZone: Float = 0f,
        ): Boolean {
            val (x, y) = move
            /* ⚠️ 死区之内一律不算 —— 防"刚按下就四个方向全亮" */
            if (kotlin.math.hypot(x, y) <= deadZone) return false
            return when (direction) {
                com.something.sthkey.domain.custom.TouchDirection.FRONT -> y < 0f
                com.something.sthkey.domain.custom.TouchDirection.BACK -> y > 0f
                com.something.sthkey.domain.custom.TouchDirection.LEFT -> x < 0f
                com.something.sthkey.domain.custom.TouchDirection.RIGHT -> x > 0f
            }
        }
    }

    /**
     * 算出**每一块"轮盘类"区域**这一刻的向量（区域 id → 向量）。
     *
     * ============================================================
     * ⚠️⚠️ 为什么不能只用 [Snapshot.move]
     * ============================================================
     * [Snapshot.move] 只由**触屏样式的轮盘槽位**
     * （[TouchSettings.JOYSTICK_SLOT_ID]）产生 —— 而**自定义 Key** 里
     * 区域是配置级的独立实体（可以有好几块轮盘区域，每块绑不同的组件）。
     *
     * ⚠️ 所以这里按区域**分别**算:每块区域用**自己的中心**当原点。
     * 这与"移动轮盘 = 固定中心 + 按在区域里的偏移"是同一个语义 ——
     * 只不过每块区域各有各的中心。
     *
     * ⚠️ 只算 [moveRegionIds] 里的那些 —— 普通按键区域"点一下就亮"，
     * 与手指在区域里的位置无关。给它算向量、再被别处读到，
     * 会让"这块区域有没有方向"变得说不清。
     *
     * ⚠️ 没有手指的区域**不出现在结果里**（而不是给个 `0 to 0`）——
     * 两种写法都是"方向不成立"，但"不出现"顺带表达了
     * "这块区域现在没人按"（`pointsToward` 查不到就当不成立）。
     *
     * ⚠️⚠️ **归属必须与 [Snapshot.move] 那条路一致**（用户报的卡顿）
     * ============================================================
     * 用户的原话:
     *
     * > 自定义 key 组件里摇杆有点问题……**常规模式有卡顿现象**，
     * > 你翻一下代码看一下，**跟触屏样式写一样才对**
     *
     * ⚠️ 他说得对:两条路的**归属判据原来不一样**:
     *
     * | 路 | 归属怎么定 | 手指滑动时 |
     * |---|---|---|
     * | [Snapshot.move]（触屏样式） | **按下那一刻**定（`belongsTo`） | ✅ 一直推着 |
     * | 这里（原来） | **每帧重判"现在在不在区域内"** | ⚠️ 边缘上这一帧算、下一帧不算 → **一顿一顿** |
     *
     * ⚠️ 现在两边都走 `belongsTo` —— 见 [ownership]。
     *
     * @param moveRegionIds **该算向量的**区域 id（**只有轮盘**那些）。
     *   ⚠️ 传"所有区域"会让按键区域也有向量 —— 那正是
     *   "**点屏幕任何地方都会推摇杆**"那个 bug（用户实测）。
     * @param ownership 触点 → 它归属的区域 id（= `belongsTo`）。
     *   ⚠️ **整份表**，不是"键的集合" —— 因为要**逐区域**查:
     *   "哪几根手指是**这一块**区域的"。用全局那一堆的话，
     *   每块区域都会拿"随便哪根手指"的坐标算向量。
     *   传空表时退化成"按几何判"（给单元测试/独立调用留的后路）。
     */
    internal fun regionMoveVectors(
        rawPoints: List<TouchNativeProtocol.Point>,
        rotation: Int,
        screenWidth: Int,
        screenHeight: Int,
        regions: List<com.something.sthkey.domain.config.TouchRegionBinding>,
        feel: com.something.sthkey.domain.config.TouchStickFeel,
        moveRegionIds: Set<String>,
        ownership: Map<Int, List<String>> = emptyMap(),
    ): Map<String, Pair<Float, Float>> {
        if (regions.isEmpty() || rawPoints.isEmpty()) return emptyMap()
        val useOwnership = ownership.isNotEmpty()

        val points = TouchRotation.mapPoints(rawPoints, rotation)
        /* ⚠️ 与 [advance] 里**逐字相同**的两套归一化（顺序也不能反） */
        val screenPoints = points.associate { p -> p.id to (p.x to p.y) }
        val shortPoints = points.associate { p ->
            p.id to TouchInput.toShortEdge(p.x, p.y, screenWidth, screenHeight)
        }

        return buildMap {
            regions.forEach { binding ->
                if (binding.slotId !in moveRegionIds) return@forEach

                val region = com.something.sthkey.domain.config.TouchRegion(
                    binding.centerX,
                    binding.centerY,
                    binding.size,
                )
                /*
                 * ⚠️ 这块区域里有**任何一根手指**就给它算向量 —— 取第一根。
                 *
                 * ============================================================
                 * ⚠️⚠️ 归属认了之后**不再复判几何**（这条是卡顿与回中的根因）
                 * ============================================================
                 * 用户报的:
                 *
                 * > 常规都会卡，平滑模式的移动摇杆**不松手也会莫名其妙回中**，
                 * > 触屏样式没这种问题
                 *
                 * ⚠️ 两个现象**同一个根因** —— 我原来在这里写的是:
                 *
                 * ```
                 * 挑了归属正确的触点，还要再 regionContains 一次
                 * ```
                 *
                 * ⚠️ 于是手指**滑出区域**的那些帧（推摇杆时几乎必然发生，
                 * 因为手会往区域外推）这块区域就**没有向量** →
                 * 上层把目标读成 `(0,0)` →
                 *
                 * | 模式 | 表现 |
                 * |---|---|
                 * | **平滑** | 目标一变成 `(0,0)`，弹簧立刻往回拉 → **不松手也回中** |
                 * | **常规** | 目标反复跳 → 线性动画反复重开 → **一顿一顿** |
                 *
                 * ⚠️ 而触屏样式那条路（[Snapshot.move]）**只用归属、从不复判几何** ——
                 * 它取的是 `moveIds.firstNotNullOfOrNull { shortPoints[it] }`。
                 *
                 * ⚠️ 所以现在**逐字对齐**:
                 *
                 * | [useOwnership] | 挑法 |
                 * |---|---|
                 * | `true` | 归属到区域的触点里取第一根 —— **只看归属**，与触屏样式一致 |
                 * | `false` | 落在区域几何内的（单元测试/独立调用 —— 那儿没有"归属"概念） |
                 */
                val point = if (useOwnership) {
                    /*
                     * ============================================================
                     * ⚠️⚠️ 用**这一块区域自己的**归属，不是全局那一堆
                     * ============================================================
                     * 用户报的:
                     *
                     * > 现在移动摇杆，**点屏幕任何地方都会触发**，
                     * > 触屏样式没这种问题
                     *
                     * ⚠️ 成因:我上一版传的是 `ownedPointIds = belongsTo.keys`
                     * —— 那是"**落到任何区域**的触点"。于是每块区域都会拿
                     * "随便哪根手指"的坐标算向量 → 哪儿点一下都有向量 →
                     * 绑了摇杆的那个组件就被推了。
                     *
                     * ⚠️ 现在按区域查:`ownership[id]` 里含这份 `slotId` 的
                     * 才算是"推这块区域的手指" —— 与触屏样式那句
                     * `moveIds = belongsTo.filterValues { ID in it }.keys`
                     * **同一个形状**。
                     */
                    ownership.entries
                        .filter { (_, slots) -> binding.slotId in slots }
                        .firstNotNullOfOrNull { (id, _) -> shortPoints[id] }
                } else {
                    screenPoints.entries
                        .firstOrNull { (_, screen) ->
                            TouchInput.regionContains(
                                region,
                                screen,
                                screenWidth,
                                screenHeight,
                                density,
                            )
                        }
                        ?.let { shortPoints[it.key] }
                } ?: return@forEach

                put(
                    binding.slotId,
                    TouchInput.moveVector(
                        region = region,
                        pointShortEdge = point,
                        centerShortEdge = TouchInput.centerToShortEdge(
                            region,
                            screenWidth,
                            screenHeight,
                        ),
                        feel = feel,
                    ),
                )
            }
        }
    }
}

package com.something.sthkey.data.fps

import android.content.Context
import android.os.SystemClock
import android.view.Choreographer
import com.something.sthkey.capture.shizuku.ShizukuShell
import com.something.sthkey.core.log.AppLog
import com.something.sthkey.domain.capture.RootProbe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import kotlin.math.abs

/**
 * FPS 采集。
 *
 * ============================================================
 * 实现照 FPS-Meter（`SurfaceFlingerFpsMonitor.kt`），不是 DroidPerf
 * ============================================================
 * 用户实测:DroidPerf 在他的机器上会闪退，FPS-Meter 完全正常。
 * 对照源码后差异很明确 —— 也是我们之前"卡住 / 显示 0"的根因:
 *
 * | 项 | FPS-Meter | 我们之前的做法 |
 * |---|---|---|
 * | 主数据源 | `--latency '<layer>'` 的**时间戳差量** | timestats 的 `averageFPS` |
 * | 交叉校验 | **帧计数器差量**（`dumpsys SurfaceFlinger` 里 `frame=N`），Vulkan 走它 | 无 |
 * | 前台包 | `dumpsys window` 的 `mCurrentFocus` | `dumpsys activity` 的 ResumedActivity |
 * | **排除自己** | 前台是我们的包时**不算游戏** → 不报数 | 会把自己界面的帧率当游戏帧率（=那一堆 125/0） |
 * | 0 值处理 | 连续 2 拍为 0 → **丢弃缓存的层重新找** | 无（层一旦选错就一直错） |
 * | 层解析 | `Output Layer` 优先，`--list` 兜底 + `cleanLayerName` | 只用 `--list` |
 *
 * ⚠️ 我们这台机器上 `--latency` 只回 `11111111`（无时间戳），所以**真正起作用的是
 * 帧计数器差量**那条路。这正是 FPS-Meter 能跑而我们不能的原因之一。
 *
 * ============================================================
 * 无 Shizuku 时的降级
 * ============================================================
 * 走 Choreographer 节拍（**估算上限**，不是游戏帧率）。用户认可这条路配合
 * `offset` 用于视觉;但公告里要写清。
 */
object FpsMonitor {

    private const val TAG = "Fps"

    private const val SAMPLE_INTERVAL_MS = 1_000L

    /** root 探测与命令的超时（毫秒） */
    private const val ROOT_PROBE_TIMEOUT_MS = 2_500L
    private const val ROOT_TIMEOUT_MS = 3_500L

    /** root 探测失败后的重试间隔 */
    private const val ROOT_RETRY_MS = 30_000L

    /** 批量命令的分段标记 */
    private const val BATCH_MARKER = "__FPS_SEC__"

    /** 小于这个窗口就不重新计算（避免短窗口算出离谱帧率） */
    private const val MIN_WINDOW_MS = 700L

    /** 常驻 shell 的结束标记 */
    private const val DONE_MARK = "__FPS_DONE__"
    /**
     * 单条命令最多读这么多。
     *
     * ⚠️ 从 256KB 提到 4MB:完整 `dumpsys SurfaceFlinger` 常见 >1MB，
     * 截断会让"层名所在的那一行"根本读不到（原神读不到的原因之一）。
     */
    private const val READ_LIMIT_BYTES = 4 * 1024 * 1024

    /** `--latency` 里表示"尚未 present"的哨兵值 */
    private const val PENDING_TIMESTAMP = Long.MAX_VALUE

    /**
     * 读取通道。用户要求能在「采集通道」页手动切（"自动" / "Choreographer" / "Surfaceflinger"）。
     *
     * ⚠️ 手动档位是**为了调试**:自动判断出错时，能强制走某一条路，
     * 不必靠猜（用户原话:"这样子搞也方便我调试"）。
     */
    enum class Mode(val id: String, val label: String) {
        AUTO("auto", "自动"),
        CHOREOGRAPHER("choreographer", "Choreographer"),
        SURFACE_FLINGER("surfaceflinger", "Surfaceflinger");

        companion object {
            fun fromId(id: String?): Mode = entries.firstOrNull { it.id == id } ?: AUTO
        }
    }

    enum class Source(val label: String) {
        /** Shizuku/root 读 SurfaceFlinger，前台游戏真实帧率 */
        SURFACE_FLINGER("SurfaceFlinger"),

        /** 无权限:Choreographer 节拍（估算上限） */
        CHOREOGRAPHER("估算上限"),

        /** 还没开始 / 已停止 */
        NONE("未启动"),
    }

    data class Reading(
        val value: Double? = null,
        val source: Source = Source.NONE,
        /** 采样序号:每秒 +1，保证 StateFlow 一定发射（否则相等值不触发重组，`offset` 不会重抽） */
        val seq: Long = 0,
    )

    private val _reading = MutableStateFlow(Reading())
    val reading: StateFlow<Reading> = _reading

    /** 当前通道;由 UI/服务写入，采样循环每秒读一次，改了立刻生效（不用重启服务） */
    @Volatile
    var mode: Mode = Mode.AUTO

    private var scope: CoroutineScope? = null
    private var job: Job? = null

    /* Choreographer */
    private var frameCounter = 0
    private var lastFrameCountTick = 0
    private var lastFrameAtMs = 0L
    private var choreographerInstalled = false
    private var sampleSeq = 0L

    /* SurfaceFlinger 采样状态（跨拍保留，差量靠它） */
    private var targetPackage: String? = null
    private var targetLayer: String? = null
    private var lastSampleAtMs = 0L
    private var lastPresentTimestamp = 0L
    private var lastLayerFrameCount = -1L
    private var zeroFpsCount = 0

    /** 连续多少拍 latency 空、计数器却有数（用来判定"这个包只能靠计数器"） */
    private var counterStrikes = 0

    /* root 探测（只探一次） */
    /** 常驻 root shell（见 [RootShell]） */
    private val rootShell = RootShell()

    private var rootChecked = false
    private var rootAvailable = false
    private var rootCheckedAtMs = 0L

    /* 探针:每种命令只打一次原始输出，用来确认真机格式 */
    private var latencyProbeLogged = false
    private var frameProbeLogged = false

    /**
     * 每个包各自记住"上次选中的层"。
     *
     * ⚠️ 有些游戏切场景会重建 SurfaceView（层名/编号变了），但**同一个包**
     * 往往还是同一类层 —— 记住它，下次进游戏不用再全量扫一遍（省 1~2 条命令）。
     */
    private val layerCache = mutableMapOf<String, String>()

    /**
     * 该包所有可用的 SurfaceView 层（按优先顺序）。
     *
     * ⚠️ 一个包可能有**多个** SurfaceView（主画面 + 视频/UI 叠加，
     * 双开时更多）。`--latency` 只认其中一个，其余全空 ——
     * 所以 latency 失败时要**逐个**用帧计数器去试（FPS-Meter 也是这么做的）。
     */
    private var targetCandidates: List<String> = emptyList()

    /**
     * 只能靠「帧计数器」读的包（Vulkan / 自绘 surface 常见）。
     *
     * ⚠️ 这类游戏的 `--latency` 永远回空，每拍去试只是白跑一条命令;
     * 记住之后直接走计数器差量（FPS-Meter 对 Vulkan 也是这么做）。
     */
    private val counterOnlyPackages = mutableSetOf<String>()

    /* 实测可用的层名写法（找到后固定用） */
    /**
     * 每个包实测可用的层名写法。
     *
     * ⚠️ 必须是**按包**的映射:之前是全局一个变量，从 Minecraft 切到原神后
     * 还在用 MC 的层名（日志里可见 `--latency '9ce024a SurfaceView[com.netease.mc.m4399…]'`），
     * 每次必然失败 → 原神永远 N/A。
     */
    private val latencyCandidates = mutableMapOf<String, String>()

    /**
     * 这个包是不是 Vulkan（`dumpsys gpu` 判定，照 FPS-Meter 的做法）。
     *
     * ⚠️ Vulkan 游戏的 `--latency` 基本不出时间戳，直接走帧计数器更快更准;
     * 判定一次缓存起来，不必每拍再问 `dumpsys gpu`。
     */
    private val vulkanPackages = mutableMapOf<String, Boolean>()
    private var frameCandidate: String? = null

    fun start(context: Context) {
        if (job != null) return
        val appContext = context.applicationContext
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope
        installChoreographer()

        job = newScope.launch {
            AppLog.i(TAG, "FPS 采集启动（1 Hz）")
            while (isActive) {
                val hasShizuku = runCatching { ShizukuShell.hasPermission() }.getOrDefault(false)
                /*
                 * ⚠️ **root 也算特权源**（用户要求"跟 Shizuku 走同一条路"）。
                 * 只授权了 root 的用户，Surfaceflinger 这条通道同样能用 ——
                 * 之前只在 Shizuku 下才走 SF，等于白白浪费了 root。
                 */
                val hasRoot = if (hasShizuku) false else hasRootShell()
                val hasShell = hasShizuku || hasRoot
                /* ⚠️ 熄屏时不采样:采了也没意义，还白耗电（用户日志里那串 N/A 有一段就是息屏） */
                if (!isScreenOn(appContext)) {
                    _reading.value = Reading(null, Source.NONE, ++sampleSeq)
                    delay(SAMPLE_INTERVAL_MS)
                    continue
                }

                /*
                 * ⚠️ 通道选择**必须同时认 root**:之前这里只判断 `hasShizuku`，
                 * 于是只授权 root 的用户永远走 Choreographer（实测踩到 ——
                 * 日志里明明有"检测到 root"，fps 却标着"估算上限"）。
                 */
                val useSf = when (mode) {
                    Mode.AUTO -> hasShell
                    Mode.SURFACE_FLINGER -> hasShell
                    Mode.CHOREOGRAPHER -> false
                }
                /* 强制 Surfaceflinger 但没有任何特权源 → 直接 N/A，不偷偷降级 */
                val forcedUnavailable = mode == Mode.SURFACE_FLINGER && !hasShell

                val next = try {
                    when {
                        forcedUnavailable -> Reading(null, Source.SURFACE_FLINGER)
                        useSf -> sampleSurfaceFlinger()
                        else -> sampleChoreographer()
                    }
                } catch (e: Exception) {
                    AppLog.w(TAG, "采样异常（${e.javaClass.simpleName}）")
                    Reading(null, if (useSf) Source.SURFACE_FLINGER else Source.CHOREOGRAPHER)
                }
                _reading.value = next
                if (next.value != null) {
                    AppLog.i(TAG, "fps=%.1f 源=%s".format(next.value, next.source.label))
                } else {
                    AppLog.w(TAG, "fps=N/A 源=${next.source.label}")
                }
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        scope?.cancel()
        scope = null
        uninstallChoreographer()
        rootShell.close()
        targetPackage = null
        targetLayer = null
        lastPresentTimestamp = 0L
        lastLayerFrameCount = -1L
        zeroFpsCount = 0
        _reading.value = Reading()
        AppLog.i(TAG, "FPS 采集已停止")
    }

    /* ============================================================
     * SurfaceFlinger（照 FPS-Meter）
     * ============================================================ */

    private suspend fun sampleSurfaceFlinger(): Reading {
        val seq = ++sampleSeq

        /* ① 前台包:不是游戏（桌面/系统 UI/我们自己的界面）就不报数 */
        val pkg = foregroundPackage()
        if (pkg == null) {
            targetPackage = null
            targetLayer = null
            lastPresentTimestamp = 0L
            lastLayerFrameCount = -1L
            zeroFpsCount = 0
            return Reading(null, Source.SURFACE_FLINGER, seq)
        }

        if (pkg != targetPackage) {
            AppLog.i(TAG, "前台变化：${targetPackage ?: "无"} → $pkg")
            targetPackage = pkg
            targetLayer = null
            lastPresentTimestamp = 0L
            lastLayerFrameCount = -1L
            zeroFpsCount = 0
        }

        /* ② 解析层（只在需要时跑，之后复用） */
        var layer = targetLayer
        if (layer == null) {
            layer = resolveLayer(pkg)
            targetLayer = layer
            lastPresentTimestamp = 0L
            lastLayerFrameCount = -1L
            zeroFpsCount = 0
            if (layer != null) AppLog.i(TAG, "目标层：$layer")
        }
        if (layer == null) return Reading(null, Source.SURFACE_FLINGER, seq)

        val now = SystemClock.elapsedRealtime()
        val elapsed = if (lastSampleAtMs == 0L) SAMPLE_INTERVAL_MS else (now - lastSampleAtMs).coerceAtLeast(1L)
        lastSampleAtMs = now

        /*
         * ③+④ 一次 su 里跑完**所有候选写法**:
         *
         * ⚠️ 层名有 8 种可能写法（去 hash 前缀 / 去 #id / 去 (BLAST)），
         * SurfaceFlinger 只认其中一种。之前每拍只试一种 → 一直 N/A;
         * 而每拍试 8 种、每种单起一个 su，又会让间隔涨到 3 秒。
         * 所以:**全部塞进同一次 su**，一次拿回所有结果。
         *
         * ⚠️ 一旦找到能用的写法就缓存下来（`latencyCandidate`），
         * 之后每拍只跑那一条，恢复 1 Hz。
         */
        /*
         * ⚠️ **缓存命中时只跑一条**。
         *
         * 之前这里无条件 `layerNameCandidates(...)` —— 也就是每拍都跑 8 条
         * `--latency`，实测采样间隔从 1.0 秒涨到 **1.45 秒**（用户反馈"卡卡的"）。
         * 8 种写法只在**首次发现**时需要;找到之后固定一条即可。
         */
        val cached = latencyCandidates[pkg]
        val candidates = if (cached != null) listOf(cached) else layerNameCandidates(layer)
        val batch = runShellBatch(
            candidates.mapIndexed { index, candidate ->
                "L$index" to "dumpsys SurfaceFlinger --latency '$candidate'"
            },
        )
        var fps = 0.0
        if (batch != null) {
            for ((index, candidate) in candidates.withIndex()) {
                val parsed = batch["L$index"]?.let { parseLatency(it, elapsed) } ?: continue
                if (parsed > 0.0) {
                    fps = parsed
                    if (latencyCandidates[pkg] != candidate) {
                        latencyCandidates[pkg] = candidate
                        AppLog.i(TAG, "latency 可用写法（$pkg）：$candidate")
                    }
                    break
                }
            }
        }

        /*
         * ④ 计数器:latency 拿不到、或这个包已知只能靠计数器时才跑。
         *
         * ⚠️ 查询在 **shell 侧过滤**（见 queryFrameCount）:
         * 读完整 dumpsys（1~3MB）再本地匹配虽然能工作，但每秒搬几 MB
         * 既慢又费电。过滤后只回来一两行。
         */
        var counter: Long? = null
        if (fps <= 0.0 || pkg in counterOnlyPackages) {
            counter = queryFrameCount(layer)
        }
        if (counter != null && counter >= 0) {
            if (lastLayerFrameCount >= 0) {
                val delta = (counter - lastLayerFrameCount).coerceAtLeast(0L)
                val counterFps = delta * 1000.0 / elapsed
                if (fps <= 0.0 && counterFps > 0.0) {
                    fps = counterFps
                } else if (fps > 0.0 && counterFps > 0.0 && abs(counterFps - fps) > 3.0) {
                    /* 两者差得多时信计数器（FPS-Meter 对 Vulkan 就是这么做的） */
                    fps = counterFps
                }
            }
            lastLayerFrameCount = counter
        }

        /*
         * ⑤ latency 连续给不出数、计数器却有数 → 记住这个包只能靠计数器。
         * （Vulkan 游戏常见:`--latency` 不报帧，但 `frame=` 一直在涨）
         */
        if (fps <= 0.0 && counter != null) {
            counterStrikes++
            if (counterStrikes >= 2 && pkg !in counterOnlyPackages) {
                counterOnlyPackages += pkg
                AppLog.i(TAG, "包 $pkg 改用帧计数器（latency 不可用）")
            }
        } else if (fps > 0.0) {
            counterStrikes = 0
        }

        /* ⑥ 连续两拍 0 → 层失效，下一拍重新找（自愈） */
        if (fps <= 0.0) {
            zeroFpsCount++
            if (zeroFpsCount >= 2) {
                AppLog.w(TAG, "层 $layer 连续 $zeroFpsCount 拍无帧，重新解析")
                targetLayer = null
                lastPresentTimestamp = 0L
                lastLayerFrameCount = -1L
                zeroFpsCount = 0
            }
            return Reading(null, Source.SURFACE_FLINGER, seq)
        }
        zeroFpsCount = 0
        return Reading(fps, Source.SURFACE_FLINGER, seq)
    }


    /** 解析 `--latency` 输出;拿不到时间戳返回 null（区别于"确实是 0 帧"） */
    private fun parseLatency(out: String, elapsedMs: Long): Double? {
        val lines = out.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.size <= 1) return null

        val timestamps = mutableListOf<Long>()
        for (i in 1 until lines.size) {
            val parts = lines[i].trim().split(WHITESPACE)
            if (parts.size < 3) continue
            val col1 = parts[1].toLongOrNull() ?: 0L
            val col2 = parts[2].toLongOrNull() ?: 0L
            val ts = when {
                col1 > 0 && col1 != PENDING_TIMESTAMP -> col1
                col2 > 0 && col2 != PENDING_TIMESTAMP -> col2
                else -> 0L
            }
            if (ts > 0) timestamps += ts
        }
        if (timestamps.isEmpty()) return null

        val currentMax = timestamps.max()
        if (lastPresentTimestamp == 0L || currentMax < lastPresentTimestamp) {
            val fps = if (timestamps.size >= 2) {
                val spanSec = (currentMax - timestamps.min()) / 1_000_000_000.0
                if (spanSec in 0.01..2.5) (timestamps.size - 1) / spanSec else 0.0
            } else {
                0.0
            }
            lastPresentTimestamp = currentMax
            return fps
        }

        val newFrames = timestamps.count { it > lastPresentTimestamp }
        if (newFrames > 0) lastPresentTimestamp = currentMax
        return newFrames * 1000.0 / elapsedMs
    }


    /**
     * 同一个层的多种写法（去 hash 前缀 / 去 #id / 去 (BLAST)）。
     *
     * ⚠️ Android 16 的 `--list` 给出的是 `ec9dd4e SurfaceView[...](BLAST)#351957`，
     * 但 SurfaceFlinger 的 `--latency` / `dumpsys` 用的是**另一种**写法，
     * 逐个试比猜一个靠得住。
     */
    private fun layerNameCandidates(layer: String): List<String> {
        val trimmed = layer.trim()
        val noHash = trimmed.substringAfter(' ', trimmed)
        val list = mutableListOf(trimmed, noHash)
        list += trimmed.substringBeforeLast('#')
        list += noHash.substringBeforeLast('#')
        list += trimmed.replace("(BLAST)", "")
        list += noHash.replace("(BLAST)", "")
        list += trimmed.replace("(BLAST)", "").substringBeforeLast('#')
        list += noHash.replace("(BLAST)", "").substringBeforeLast('#')
        return list.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }
    /** 前台包名;不是「游戏」返回 null（照 FPS-Meter 的 `isGameCandidate`） */
    private suspend fun foregroundPackage(): String? {
        val window = runShell(SF_WINDOW_FOCUS)
        window?.lineSequence()?.forEach { line ->
            val match = FOCUS_REGEX.find(line) ?: return@forEach
            val pkg = match.groupValues[1]
            if (isGameCandidate(pkg)) return pkg
        }

        val activity = runShell(SF_ACTIVITY)
        activity?.lineSequence()?.forEach { line ->
            val match = PACKAGE_REGEX.find(line) ?: return@forEach
            val pkg = match.groupValues[1]
            if (isGameCandidate(pkg)) return pkg
        }
        return null
    }

    /**
     * ⚠️ **排除我们自己**:FPS-Meter 也这么做。
     * 不排除的话，用户在我们软件里时读到的是自己的帧率（实测 125 / 0 满天飞），
     * 而那个数对用户毫无意义。
     */
    private fun isGameCandidate(pkg: String): Boolean =
        pkg != "android" &&
            !pkg.contains("systemui", ignoreCase = true) &&
            !pkg.contains("launcher", ignoreCase = true) &&
            !pkg.contains("quickstep", ignoreCase = true) &&
            pkg != OWN_PACKAGE

    /**
     * 找目标层。
     *
     * ⚠️ 两个来源合并后**统一按优先级**挑，不要"先到先得":
     * `Output Layer` 里常常先出现的是 Activity 窗口（`com.pkg/com.pkg.Activity#123`），
     * 它的 `--latency` 永远是空 —— 游戏画面在 `SurfaceView[...](BLAST)` 那层上。
     *
     * 优先级:`SurfaceView` + `BLAST` → `SurfaceView` → `BLAST/BBQ` → 其它。
     */
    private suspend fun resolveLayer(pkg: String): String? {
        val candidates = mutableListOf<String>()

        /* 来源一:Output Layer（当前正在输出的层，最准） */
        val sf = runShell("dumpsys SurfaceFlinger | grep -F 'Output Layer' | grep -F '$pkg'")
        if (!sf.isNullOrBlank()) {
            OUTPUT_LAYER.findAll(sf)
                .map { it.groupValues[1].trim() }
                .filter { isRenderableLayer(it, pkg) }
                .forEach { candidates += it }
        }

        /* 来源二:--list（兜底;A16 是 RequestedLayerState{...} 包装格式） */
        val list = runShell(SF_LAYER_LIST)
        if (!list.isNullOrBlank()) {
            list.lineSequence()
                .map { it.trim() }
                .filter { it.contains(pkg) }
                .map { cleanLayerName(it) }
                .filter { isRenderableLayer(it, pkg) }
                .forEach { candidates += it }
        }

        if (candidates.isEmpty()) return null
        val distinct = candidates.distinct()
        return distinct.lastOrNull { it.contains("SurfaceView", true) && it.contains("BLAST", true) }
            ?: distinct.lastOrNull { it.contains("SurfaceView", true) }
            ?: distinct.lastOrNull { it.contains("BLAST", true) || it.contains("BBQ", true) }
            ?: distinct.lastOrNull { !it.contains("PopupWindow") }
            ?: distinct.lastOrNull()
    }
    private fun isRenderableLayer(layer: String, pkg: String): Boolean {
        if (layer.isEmpty() || !layer.contains(pkg)) return false
        val excluded = listOf(
            "ActivityRecord", "Background for", "Bounds for", "Dim layer",
            "SnapshotStartingWindow", "Transition", "leash",
        )
        if (excluded.any { layer.contains(it) }) return false
        return !layer.startsWith("Task=")
    }

    /** `RequestedLayerState{...}` / `Layer [...]` 都还原成裸层名 */
    private fun cleanLayerName(raw: String): String {
        var str = raw.trim()
        if (str.startsWith("RequestedLayerState{")) {
            str = str.removePrefix("RequestedLayerState{")
            str = str.substringBefore(" parentId=").substringBefore(" relativeParentId=").substringBefore(" z=")
            str = str.removeSuffix("}")
        } else if (str.startsWith("Layer [")) {
            str = str.substringAfter("] ").trim()
        }
        return str.trim()
    }

    /**
     * 执行一条 shell 命令:**Shizuku 优先，没有就退回 root**。
     *
     * ⚠️ 两条路跑的是**同一条命令**，只有"用什么身份起进程"不同 ——
     * 所以上层解析一行都不用改（与 `GeteventStream` 的取舍一致）。
     */
    private suspend fun runShell(command: String): String? {
        val viaShizuku = runCatching { ShizukuShell.hasPermission() }.getOrDefault(false)
        if (viaShizuku) {
            return try {
                val shell = ShizukuShell.start(command)
                val text = shell.inputStream.readAtMost(READ_LIMIT_BYTES)
                shell.close()
                text
            } catch (e: Exception) {
                AppLog.w(TAG, "Shizuku 命令失败（${e.javaClass.simpleName}）：$command")
                null
            }
        }
        return runShellAsRoot(command)
    }

    /** root 执行（`su -c`）;失败返回 null */
    private fun runShellAsRoot(command: String): String? {
        /* 常驻 shell:省掉每拍 ≈300ms 的 su 启动 */
        rootShell.exec(command, ROOT_TIMEOUT_MS)?.let { return it.take(READ_LIMIT_BYTES) }
        /* 常驻这条路失败（进程死/超时）→ 退回一次性 su，至少这一拍还有数 */
        return try {
            val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            val text = process.inputStream.readAtMost(READ_LIMIT_BYTES)
            val finished = process.waitFor(ROOT_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) process.destroy()
            text
        } catch (e: Exception) {
            AppLog.w(TAG, "root 命令失败（${e.javaClass.simpleName}）：$command")
            null
        }
    }

    /**
     * 一次 shell 调用里跑**多条命令**，用标记分段。
     *
     * ⚠️ 为什么必须合并:root 模式下每次 ProcessBuilder(su, …) 都要新起进程，
     * KernelSU/Magisk 约 300ms。一拍跑 3 条就是 ≈1 秒 —— 实测采样间隔变成
     * **1.3 秒**（用户反馈"卡卡的、不像 1 秒一次"）。合并后一拍只付一次启动开销。
     */
    private suspend fun runShellBatch(commands: List<Pair<String, String>>): Map<String, String>? {
        if (commands.isEmpty()) return null
        val script = buildString {
            commands.forEach { (marker, command) ->
                append("echo ").append(BATCH_MARKER).append(marker).append('\n')
                append(command).append('\n')
            }
        }
        val output = runShell(script) ?: return null
        val result = mutableMapOf<String, String>()
        var current: String? = null
        val buffer = StringBuilder()
        for (line in output.lineSequence()) {
            if (line.startsWith(BATCH_MARKER)) {
                current?.let { result[it] = buffer.toString() }
                current = line.removePrefix(BATCH_MARKER).trim()
                buffer.setLength(0)
            } else if (current != null) {
                buffer.append(line).append('\n')
            }
        }
        current?.let { result[it] = buffer.toString() }
        return result
    }

    /**
     * 查这一层的帧计数器。
     *
     * ⚠️ **在 shell 侧过滤**（`grep -F 层名 | grep -F frame=`）:
     * 读完整 `dumpsys SurfaceFlinger`（1~3MB）再本地找，虽然能工作，
     * 但每秒搬几 MB 进内存既慢又费电 —— 过滤后只回来 1 行。
     *
     * ⚠️ 逐个候选试:一个包可能有多个 SurfaceView（主画面/叠加层/双开），
     * 只有其中一个有 `frame=`。
     */
    private suspend fun queryFrameCount(layer: String): Long? {
        for (key in counterCandidateKeys(layer)) {
            val escaped = key.replace("'", "'\\''")
            val out = runShell(
                "dumpsys SurfaceFlinger | grep -F '$escaped' | grep -F 'frame=' | head -2",
            ) ?: continue
            val value = parseFrameCount(out)
            if (value != null) return value
        }
        /* 全都没匹配上 → 退回完整 dump（极少数机型/写法） */
        val dump = runShell(SF_FULL_DUMP) ?: return null
        return parseFrameCountForCandidates(dump, counterCandidateKeys(layer))
    }
    /**
     * 判定这个包是不是 Vulkan 渲染（照 FPS-Meter 的 `detectGraphicsApi`）。
     *
     * ⚠️ 先看 `dumpsys gpu` 的 `appPackageName = <包>` 段里有没有
     * `createdVulkanSwapchain = 1`;再退回 `dumpsys gfxinfo` 的 Pipeline 行。
     * 判定结果**缓存**，一局游戏只问一次。
     */
    private suspend fun isVulkan(pkg: String): Boolean {
        vulkanPackages[pkg]?.let { return it }

        var vulkan = false
        val gpu = runShell("dumpsys gpu")
        if (gpu != null) {
            val marker = "appPackageName = $pkg"
            val start = gpu.indexOf(marker)
            if (start >= 0) {
                val next = gpu.indexOf("appPackageName =", start + marker.length)
                val section = if (next > start) gpu.substring(start, next) else gpu.substring(start)
                vulkan = section.contains("createdVulkanSwapchain = 1") ||
                    section.contains("createdVulkanDevice = 1")
            }
        }
        if (!vulkan) {
            val gfx = runShell("dumpsys gfxinfo $pkg | grep -i 'Pipeline'")
            if (gfx != null && gfx.contains("Skia (Vulkan)", ignoreCase = true)) vulkan = true
        }

        vulkanPackages[pkg] = vulkan
        if (vulkan) AppLog.i(TAG, "$pkg 是 Vulkan → 用帧计数器")
        return vulkan
    }
    /** 收集该包所有可用层（用于计数器逐个试） */
    private suspend fun collectCandidates(pkg: String): List<String> {
        val list = runShell(SF_LAYER_LIST) ?: return emptyList()
        return list.lineSequence()
            .map { it.trim() }
            .filter { it.contains(pkg) }
            .map { cleanLayerName(it) }
            .filter { isRenderableLayer(it, pkg) }
            .filter { it.contains("SurfaceView", true) }
            .distinct()
            .toList()
    }

    /** 计数器要匹配的 key（去 #id）;含当前层与全部候选 */
    private fun counterCandidateKeys(layer: String): List<String> {
        val keys = mutableListOf(layer)
        keys += targetCandidates
        return keys.map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.distinct()
    }

    /**
     * 在完整 `dumpsys SurfaceFlinger` 输出里，按行找 `<key> ... frame=N`。
     *
     * ⚠️ 与 FPS-Meter 的 `queryLayerFrameCount` 等价:层名和 `frame=` 必须在**同一行**。
     */
    private fun parseFrameCountForCandidates(dump: String, keys: List<String>): Long? {
        for (key in keys) {
            for (line in dump.lineSequence()) {
                if (!line.contains(key)) continue
                if (line.contains("Background for")) continue
                val match = FRAME_COUNT.find(line) ?: continue
                return match.groupValues[1].toLongOrNull()
            }
        }
        return null
    }
    /** 从 frame= 输出里取计数 */
    private fun parseFrameCount(out: String): Long? = out.lineSequence()
        .filterNot { it.contains("Background for") }
        .mapNotNull { FRAME_COUNT.find(it)?.groupValues?.getOrNull(1)?.toLongOrNull() }
        .firstOrNull()

    /** 有没有 root（只探测一次，之后缓存） */
    /**
     * 有没有 root。
     *
     * ⚠️ 用项目已有的 [RootProbe]（`domain/capture/RootProbe.kt`）:它按 `uid=0` 判断，
     * 比"看退出码"可靠（某些 su 包装器退出码是 0 但没真提权）。
     *
     * ⚠️ 失败结果**不永久缓存**:用户可能第一次探的时候还没点"允许"授权弹窗，
     * 缓存成 false 就再也用不了 SF 了。这里失败后 30 秒重试一次。
     */
    private fun hasRootShell(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (rootChecked) {
            if (rootAvailable) return true
            if (now - rootCheckedAtMs < ROOT_RETRY_MS) return false
        }
        val result = runCatching {
            kotlinx.coroutines.runBlocking { RootProbe.probe() }
        }.getOrNull()
        rootChecked = true
        rootCheckedAtMs = now
        rootAvailable = result?.available == true
        if (rootAvailable) AppLog.i(TAG, "root 可用：Surfaceflinger 通道也能走")
        return rootAvailable
    }
    /** 亮屏判断 */
    private fun isScreenOn(context: Context): Boolean = try {
        val power = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        power.isInteractive
    } catch (e: Exception) {
        true
    }

    /* ============================================================
     * Choreographer（无权限时的估算上限）
     * ============================================================ */

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            frameCounter++
            if (choreographerInstalled) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun installChoreographer() {
        if (choreographerInstalled) return
        choreographerInstalled = true
        frameCounter = 0
        lastFrameCountTick = 0
        lastFrameAtMs = SystemClock.elapsedRealtime()
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    private fun uninstallChoreographer() {
        if (!choreographerInstalled) return
        choreographerInstalled = false
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching { Choreographer.getInstance().removeFrameCallback(frameCallback) }
        }
    }

    private fun sampleChoreographer(): Reading {
        val now = SystemClock.elapsedRealtime()
        val count = frameCounter
        val frames = count - lastFrameCountTick
        val elapsed = now - lastFrameAtMs
        lastFrameCountTick = count
        lastFrameAtMs = now
        if (elapsed <= 0L) return Reading(null, Source.CHOREOGRAPHER, ++sampleSeq)
        val fps = frames * 1000.0 / elapsed
        return Reading(if (fps > 0) fps else null, Source.CHOREOGRAPHER, ++sampleSeq)
    }

    /* ============================================================
     * 常量
     * ============================================================ */

    private val OWN_PACKAGE: String = "com.something.sthkey"

    private const val SF_WINDOW_FOCUS = "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'"
    private const val SF_ACTIVITY =
        "dumpsys activity activities | grep -E 'mResumedActivity|ResumedActivity'"
    private const val SF_LAYER_LIST = "dumpsys SurfaceFlinger --list"

    /** 完整 dump:计数器匹配用（FPS-Meter 也是读全量） */
    private const val SF_FULL_DUMP = "dumpsys SurfaceFlinger"

    private val FOCUS_REGEX = Regex("(?:mCurrentFocus|mFocusedApp)=.*?([a-zA-Z0-9._]+)/[a-zA-Z0-9._]+")
    private val PACKAGE_REGEX = Regex("([a-zA-Z0-9._]+)/[a-zA-Z0-9._]+")
    private val OUTPUT_LAYER = Regex("Output Layer.*?\\((.+)\\)")
    private val FRAME_COUNT = Regex("frame=(\\d+)")
    private val WHITESPACE = Regex("\\s+")

    /** 不用 readNBytes（API 33+）:minSdk 28 会 NoSuchMethodError */
    private fun InputStream.readAtMost(limit: Int): String {
        val buffer = ByteArray(8192)
        val out = StringBuilder()
        var total = 0
        while (total < limit) {
            val read = read(buffer)
            if (read <= 0) break
            total += read
            out.append(String(buffer, 0, read, Charsets.UTF_8))
        }
        return out.toString()
    }
    /**
     * 常驻的 root shell。
     *
     * ============================================================
     * ⚠️ 为什么要常驻
     * ============================================================
     * 实测:root 模式下**每拍新起一个 `su` 进程**，光启动就 ≈300ms，
     * 加上 `dumpsys SurfaceFlinger --latency` 本身，采样间隔实测 **1.45 秒**。
     * 把间隔调到 0.7 秒没用 —— 瓶颈是**每拍的固定开销**，不是间隔。
     *
     * 常驻之后:`su` 只起一次，每拍只是往 stdin 写一段脚本、从 stdout 读到结束标记。
     *
     * ⚠️ 进程会死（关 root / 被系统回收 / 超时），所以每次执行前 `ensure()`，
     * 死了就重启；超时会主动杀掉重来，避免管道卡死。
     */
    private class RootShell {

        private var process: Process? = null
        private var writer: java.io.BufferedWriter? = null
        private var reader: java.io.BufferedReader? = null

        fun ensure(): Boolean {
            val current = process
            if (current != null && current.isAlive) return true
            close()
            return try {
                val process = ProcessBuilder("su").redirectErrorStream(true).start()
                this.process = process
                writer = process.outputStream.bufferedWriter()
                reader = process.inputStream.bufferedReader()
                true
            } catch (e: Exception) {
                close()
                false
            }
        }

        /** 执行一段脚本，读到 [DONE_MARK] 为止;超时/进程死返回 null */
        fun exec(script: String, timeoutMs: Long): String? {
            if (!ensure()) return null
            val out = writer ?: return null
            val input = reader ?: return null
            return try {
                /* 包在 { } 里:多行脚本当一组执行，错误也并进 stdout */
                out.write("{\n")
                out.write(script)
                out.write("\n} 2>&1\n")
                out.write("echo ")
                out.write(DONE_MARK)
                out.write("\n")
                out.flush()

                val builder = StringBuilder()
                val deadline = SystemClock.elapsedRealtime() + timeoutMs
                while (SystemClock.elapsedRealtime() < deadline) {
                    if (input.ready()) {
                        val line = input.readLine() ?: run { close(); return null }
                        if (line.trim() == DONE_MARK) return builder.toString()
                        builder.append(line).append('\n')
                    } else {
                        Thread.sleep(5)
                    }
                }
                close()
                null
            } catch (e: Exception) {
                close()
                null
            }
        }

        fun close() {
            runCatching { writer?.close() }
            runCatching { reader?.close() }
            runCatching { process?.destroy() }
            writer = null
            reader = null
            process = null
        }
    }

}

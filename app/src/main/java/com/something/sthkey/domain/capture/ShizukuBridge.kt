package com.something.sthkey.domain.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.something.sthkey.core.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/**
 * Shizuku 的隔离访问层。
 *
 * ============================================================
 * 职责
 * ============================================================
 * 只做三件事：**查询授权**、**申请授权**、**判断服务是否在跑**。
 * 所有 Shizuku SDK 调用都收敛在这里，上层（引导页、调试页、以后的采集层）
 * 不需要知道任何 SDK 细节。
 *
 * 不做"是否安装"检测：未安装时 [isRunning] 就是 false，
 * 对用户来说处理动作一样（去装 / 去启动），多一个可能不准的判断只会误导。
 *
 * ============================================================
 * 两个必须注意的细节
 * ============================================================
 * 1. **申请授权需要 Activity**：`Shizuku.requestPermission()` 内部是
 *    `Activity.requestPermissions()`，没有前台 Activity 时会抛异常。
 *    因此 [requestPermission] 要求传入 Activity；拿不到时回退为"打开 Shizuku 应用"
 *    让用户手动授予 —— 至少不会点了没反应。
 * 2. **必须先注册回调再发起申请**：结果是通过监听器异步回来的，
 *    顺序反了用户手快时结果就丢了。
 *
 * binder 调用统一加超时：Shizuku 进程异常时这些调用可能长时间不返回，
 * 直接放在启动流程里会卡住界面。
 */
object ShizukuBridge {

    private const val TAG = "Shizuku"

    /** 权限申请 requestCode；注册回调与发起请求用同一个值，避免结果对不上 */
    const val PERMISSION_REQUEST_CODE = 1001

    /** Shizuku 主应用包名（回退方案要打开它） */
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    private const val BINDER_TIMEOUT_MILLIS = 1_500L

    /** 等待 Shizuku binder 送达的最长时间（冷启动时它会晚一点到） */
    private const val BINDER_WAIT_MILLIS = 2_000L

    /** 等待 SDK 内部 service 就绪的上限（见 [awaitBinderReady]） */
    private const val READY_TIMEOUT_MILLIS = 3_000L

    /** 等待 binder 时的轮询间隔 */
    private const val BINDER_POLL_INTERVAL_MILLIS = 150L

    private const val PERMISSION_POLL_INTERVAL_MILLIS = 150L
    private const val PERMISSION_TIMEOUT_MILLIS = 30_000L

    /** 监听器只需注册一次 */
    @Volatile
    private var listenerRegistered = false

    /** 最近一次授权结果；null 表示用户还没做出选择 */
    @Volatile
    private var lastPermissionResult: Boolean? = null

    private val permissionListener = object : Shizuku.OnRequestPermissionResultListener {
        override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
            if (requestCode != PERMISSION_REQUEST_CODE) return
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            lastPermissionResult = granted
            AppLog.i(TAG, "Shizuku 授权结果：${if (granted) "已授予" else "被拒绝"}")
        }
    }

    private fun ensureListener() {
        if (listenerRegistered) return
        synchronized(this) {
            if (listenerRegistered) return
            try {
                Shizuku.addRequestPermissionResultListener(permissionListener)
                listenerRegistered = true
            } catch (e: Exception) {
                AppLog.e(TAG, "注册 Shizuku 权限回调失败", e)
            }
        }
    }

    /*
     * ============================================================
     * 状态查询
     * ============================================================
     */

    /** Shizuku 服务是否正在运行 */
    fun isRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Exception) {
        AppLog.w(TAG, "Shizuku pingBinder 失败：${e.javaClass.simpleName}")
        false
    }

    /** 供 UI 层直接调用的带超时版本 */
    suspend fun isRunningSafe(): Boolean = withContext(Dispatchers.IO) {
        withTimeoutOrNull(BINDER_TIMEOUT_MILLIS) { isRunning() } ?: false
    }

    /**
     * 本应用是否已获得 Shizuku 授权。
     *
     * 未运行时直接返回 false，不去调 `checkSelfPermission()`：
     * 服务不在时那个调用会抛异常或长时间阻塞，问了也没意义。
     */
    fun isPermissionGranted(): Boolean {
        if (!isRunning()) return false
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            AppLog.w(TAG, "检查 Shizuku 权限失败：${e.javaClass.simpleName}")
            false
        }
    }

    /**
     * 本应用是否已获得 Shizuku 授权（供 UI 调用的版本）。
     *
     * ⚠️ 冷启动时 `pingBinder()` 返回 false **不等于**"没授权"：
     * Shizuku 的 binder 是在应用进程启动**之后**才异步送到的。
     * 此刻直接下结论就会得到一个假结果 —— 用户明明早就授权过，
     * 引导页却显示未授权，而且怎么切后台、怎么重进都不对。
     *
     * 所以这里在"服务尚未就绪"时短暂轮询等待，等不到才判否。
     * 等待窗口很短：Shizuku 没装或没启动时不会让用户干等太久。
     */
    suspend fun isPermissionGrantedSafe(): Boolean = withContext(Dispatchers.IO) {
        withTimeoutOrNull(BINDER_WAIT_MILLIS) {
            while (!isRunning()) {
                delay(BINDER_POLL_INTERVAL_MILLIS)
            }
            isPermissionGranted()
        } ?: false
    }

    /**
     * 等待 Shizuku 的 binder **真正就绪**。
     *
     * ============================================================
     * 为什么不能只用 [isRunning]（它只是 `pingBinder()`）
     * ============================================================
     * Shizuku SDK 内部有一个 `service` 字段，它在 binder 收到后才被赋值；
     * 而 `bindUserService()` / `newProcess()` 内部都会调 `requireService()`，
     * 那个方法在 `service == null` 时**直接抛**：
     *
     * ```
     * IllegalStateException("binder haven't been received")
     * ```
     *
     * `pingBinder()` 只说明 binder 对象存在，**不代表 `service` 已就绪** ——
     * 两者之间存在一个很短的时间窗。恰好在这段时间里发调用，
     * 就会立刻抛异常；如果异常又被后续清理覆盖掉，
     * 用户看到的就是一句莫名其妙的"启动失败"。
     *
     * 所以这里用 SDK 自己的 `addBinderReceivedListener` 等一次：
     * 已经就绪就立即返回，未就绪则挂起等待（带超时兜底）。
     *
     * ⚠️ 这个方法是**通用**的（直连通道也在用），所以它必须住在
     * [ShizukuBridge] 这种"Shizuku 访问层"里，而不是某个具体实现里 ——
     * 早先它住在已经删掉的 `ShizukuUserService` 里，导致直连要跨模块借它。
     */
    suspend fun awaitBinderReady(): Boolean {
        if (isServiceReady()) return true

        AppLog.i(TAG, "等待 Shizuku binder 就绪…")
        return withTimeoutOrNull(READY_TIMEOUT_MILLIS) {
            withContext(Dispatchers.IO) {
                suspendCancellableCoroutine { cont ->
                    val listener = Shizuku.OnBinderReceivedListener {
                        if (cont.isActive) cont.resumeWith(Result.success(Unit))
                    }
                    try {
                        Shizuku.addBinderReceivedListener(listener)
                    } catch (e: Exception) {
                        AppLog.w(TAG, "注册 binder 监听失败：${e.javaClass.simpleName}")
                        // 注册失败就直接放行：让真正的调用去报错，比在这里憋着好
                        if (cont.isActive) cont.resumeWith(Result.success(Unit))
                    }
                }
            }
        }?.let { isServiceReady() } ?: false
    }

    /**
     * SDK 内部的 service 是否已就绪。
     *
     * 用 `getVersion()` 探：它在 `service == null` 时会抛
     * `IllegalStateException`，正好能当作"就绪了没有"的判据。
     * 比直接读那个私有字段可靠 —— 那是 SDK 内部实现，不保证一直存在。
     */
    private fun isServiceReady(): Boolean = try {
        Shizuku.getVersion()
        true
    } catch (_: Exception) {
        false
    }

    /*
     * ============================================================
     * 申请授权
     * ============================================================
     */

    /**
     * 申请 Shizuku 授权，挂起等待用户选择。
     *
     * @param activity 当前前台 Activity。为 null 时无法弹系统对话框，
     *   会回退为打开 Shizuku 应用让用户手动授予。
     * @return 是否已获得授权；超时、异常、未运行均返回 false
     */
    suspend fun requestPermission(activity: Activity?): Boolean {
        if (isPermissionGranted()) {
            AppLog.i(TAG, "Shizuku 已授权，无需重复申请")
            return true
        }

        if (!isRunning()) {
            AppLog.w(TAG, "Shizuku 未运行，无法申请授权")
            return false
        }

        if (activity == null) {
            AppLog.w(TAG, "没有前台 Activity，回退为打开 Shizuku 应用手动授权")
            openShizukuApp(activity)
            return false
        }

        ensureListener()
        lastPermissionResult = null

        return try {
            AppLog.i(TAG, "发起 Shizuku 授权申请（requestCode=$PERMISSION_REQUEST_CODE）")
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)

            val result = withTimeoutOrNull(PERMISSION_TIMEOUT_MILLIS) {
                // 结果由回调写入 volatile，这里只做轮询等待，不阻塞线程
                while (lastPermissionResult == null) {
                    delay(PERMISSION_POLL_INTERVAL_MILLIS)
                }
                lastPermissionResult
            }

            when (result) {
                true -> true
                false -> false
                null -> {
                    // 超时不等于失败：用户可能还没点，复查一次真实状态
                    val granted = isPermissionGranted()
                    AppLog.w(TAG, "等待授权结果超时，复查结果：$granted")
                    granted
                }
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "申请 Shizuku 授权失败，回退为打开 Shizuku 应用", e)
            openShizukuApp(activity)
            false
        }
    }

    /** 打开 Shizuku 应用，让用户在里面手动授权 */
    private fun openShizukuApp(context: Context?): Boolean {
        if (context == null) return false
        val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 引导页/调试页展示用的说明 */
    fun hint(): String =
        "需先安装并启动 Shizuku（可通过无线调试启动），再回到本页授予本应用权限。"

    /*
     * ============================================================
     * 诊断信息
     * ============================================================
     */

    private val _diagnostic = MutableStateFlow<String?>(null)

    /**
     * 最近一条诊断说明（调试页展示）。
     *
     * 采集层每走一步都往这里写一句，用户报"Shizuku 用不了"时
     * 看一眼就知道卡在哪。它住在这里而不是某个具体后端里，
     * 是因为**两条通道都要写**（直连与以后可能的新后端），
     * 而它描述的是"Shizuku 这条路的整体状态"。
     */
    val diagnostic: StateFlow<String?> = _diagnostic.asStateFlow()

    fun reportDiagnostic(message: String) {
        _diagnostic.value = message
    }
}

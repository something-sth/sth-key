package com.something.sthkey.capture.accessibility

import android.content.Context
import android.view.WindowManager
import com.something.sthkey.core.log.AppLog

/**
 * **窗口宿主的借用点** —— 让别的 Service 能用无障碍服务的窗口资格。
 *
 * ============================================================
 * ⚠️⚠️ 这个类存在的原因（一句话）
 * ============================================================
 * `TYPE_ACCESSIBILITY_OVERLAY` **只能由 `AccessibilityService` 的 context 建**。
 * 而我们的窗口代码在 `OverlayService` / `TouchRegionEditorService` 里（普通 Service）——
 * 拿它们的 context 去建会抛:
 *
 * ```
 * WindowManager$BadTokenException: token null is not for an application
 * ```
 *
 * ⚠️ 这与早先 `AlertDialog` 闪退是**同一类**错误 —— 给多大权限都没用，
 * 是系统在窗口类型那一层拒绝。
 *
 * ============================================================
 * ⚠️⚠️ 为什么用"借"而不是"搬"
 * ============================================================
 * 两个服务的窗口代码加起来 4000 多行（含拖动/粘边/多窗口/旋转处理）。
 *
 * | 做法 | 代价 |
 * |---|---|
 * | 把两个服务都塞进 `AccessibilityService` | ⚠️ 4000 行挤一个类；而且两者的**生命周期完全不同**（窗口服务是前台服务、编辑器是另一个前台服务） |
 * | **只借"窗口资格"**（现在这样） | ✅ 两个服务的代码**几乎不动**，只把 `windowManager` 的来源换掉 |
 *
 * ⚠️ 而且窗口代码本身**没有交集**（两者唯一共用的只有 `OverlayBounds` 这个纯计算类），
 * 所以"抽公共基类"也不划算 —— 借一个 `WindowManager` 是最小的改动面。
 *
 * ============================================================
 * ⚠️ 生命周期:谁 attach、谁 detach
 * ============================================================
 * | 时机 | 动作 |
 * |---|---|
 * | `AccessibilityService.onServiceConnected` | [attach] |
 * | `onUnbind` / `onDestroy`（用户关掉无障碍） | [detach] |
 *
 * ⚠️ detach 之后 [isAttached] 变 `false`，两个窗口服务据此提示用户
 * "需要开启无障碍服务"，而**不是**闷头去建然后抛异常。
 *
 * ⚠️ **不能**在这里持有 Activity 或任何 View —— 只留一个
 * `Context` 与它的 `WindowManager`，都是服务级的，不会泄漏。
 */
internal object OverlayWindowHost {

    private const val TAG = "Accessibility"

    /**
     * 无障碍服务的 context（= 唯一能建无障碍窗口的那个）。
     *
     * ⚠️ 这是 `AccessibilityService` 自己，它是个 `Context` 但不是 Activity ——
     * **不会**因为界面关闭而失效，所以留着引用不会泄漏 Activity。
     */
    private var serviceContext: Context? = null

    /** 无障碍服务的 `WindowManager` —— 窗口操作都走它 */
    private var windowManager: WindowManager? = null

    /** 无障碍服务连上了吗（= 现在能不能建窗口） */
    fun isAttached(): Boolean = windowManager != null

    /**
     * 无障碍服务连上了 —— 登记自己。
     *
     * ⚠️ 重复 attach 是**幂等**的（服务重连时会再调一次）——
     * 每次都覆盖成最新那个实例，不要累加。
     */
    fun attach(service: Context) {
        val manager = service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (manager == null) {
            AppLog.w(TAG, "无障碍服务拿不到 WindowManager，窗口将无法创建")
            return
        }
        serviceContext = service.applicationContext
        windowManager = manager
        AppLog.i(TAG, "窗口宿主已就绪")
    }

    /** 无障碍服务断开了 —— 清掉，之后 [windowManagerOrNull] 会返回 null */
    fun detach() {
        serviceContext = null
        windowManager = null
    }

    /**
     * 拿窗口管理器。
     *
     * ⚠️ 返回 `null` = **无障碍服务没连上**。调用方必须处理这个 `null`
     * （提示用户去开无障碍），而不是想当然地当作一定有 ——
     * 否则就是那个 `BadTokenException` 的老路。
     */
    fun windowManagerOrNull(): WindowManager? = windowManager

    /**
     * 建窗口用的 context。
     *
     * ⚠️ 与 [windowManagerOrNull] 配套:有些地方要 context 去算屏幕尺寸、
     * 弹 Toast、起别的服务 —— 那些都应该用**这个**，
     * 而不是自己那个普通 Service 的 context（否则算出来的屏幕尺寸一样，
     * 但语义上会让人以为是"窗口服务的 context"）。
     *
     * ⚠️ 返回 `null` 的理由同 [windowManagerOrNull]。
     */
    fun contextOrNull(): Context? = serviceContext

    /**
     * 悬浮窗该用哪种窗口类型。
     *
     * ============================================================
     * ⚠️⚠️ 这里**只有一种**返回值，是有意的
     * ============================================================
     * 用户拍板"走 Axon 那条路":
     *
     * > 这样就**不需要用户单独授权悬浮窗权限**了
     *
     * ⚠️ 所以**不再有 `SYSTEM_ALERT_WINDOW`**，也不再需要按模式分支 ——
     * 三种采集通道（root / Shizuku / 无障碍）**都用同一个类型**。
     *
     * ⚠️ 留成函数而不是就地写常量:以后若真要退回"应用层悬浮窗"，
     * 只改这一处，7 个调用点不用动。
     */
    fun windowType(): Int = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
}

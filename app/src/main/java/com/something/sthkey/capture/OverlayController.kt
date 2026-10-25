package com.something.sthkey.capture

import android.content.Context
import com.something.sthkey.core.log.AppLog
import com.something.sthkey.capture.accessibility.AccessibilityGrant
import com.something.sthkey.capture.accessibility.SthKeyAccessibilityService
import com.something.sthkey.core.prefs.AppPrefs
import com.something.sthkey.domain.overlay.OverlayLayouts
import com.something.sthkey.data.permission.PermissionHelper

/**
 * 悬浮窗的**开关协调层**（界面与桌面快捷方式共用）。
 *
 * ============================================================
 * 为什么必须抽出来
 * ============================================================
 * "打开某几份配置的悬浮窗"这件事要做四步，而且**顺序不能乱**：
 *
 * ```
 * 1. 检查悬浮窗权限     —— 没有就什么都别做，否则服务起来也画不出窗口
 * 2. 写"启用了哪些配置" —— 服务的真源是这个集合，不是方法的参数
 * 3. 启动悬浮窗服务
 * 4. 启动采集
 * ```
 *
 * 早先这套只在 `MainViewModel.setConfigOverlayEnabled` 里。桌面快捷方式
 * 是**不经过界面**的入口，如果它自己再写一份，两份迟早会不一致 ——
 * 而漏掉第 4 步的表现是"窗口出现了但按键不动"，非常难查
 * （看起来像采集坏了，其实只是没人去启动它）。
 *
 * ⚠️ 所以这里**不持有任何界面状态**：ViewModel 负责把结果同步到
 * Compose 状态，这里只管"让系统真的动起来"。两边读写的偏好啊、
 * 集合啊都是同一份（`AppPrefs` / `OverlayLayouts`），不会出现
 * "快捷方式开了、界面显示没开"。
 */
object OverlayController {

    private const val TAG = "OverlayCtl"

    /**
     * 打开若干份配置的悬浮窗（**累加**语义）。
     *
     * ⚠️ 累加而不是重置：用户的心智是"再点一次快捷方式没什么坏处"。
     * 重置会把他之前手动开的窗口悄悄关掉 —— 那是最容易让人骂人的行为。
     *
     * @return 是否真的做了事；false 表示被权限挡住了
     */
    fun enableConfigs(
        context: Context,
        configIds: Collection<String>,
    ): Boolean {
        if (configIds.isEmpty()) {
            AppLog.w(TAG, "没有要打开的配置，忽略")
            return false
        }

        /*
         * ============================================================
         * ⚠️⚠️ 这里有道**前置检查**，判据从"悬浮窗权限"换成了"无障碍服务"
         * ============================================================
         * 用户报的:
         *
         * > 开启悬浮窗的按钮**点不动**，授权了也是
         *
         * ⚠️ 成因就是这一行:改用 `TYPE_ACCESSIBILITY_OVERLAY` 之后
         * `SYSTEM_ALERT_WINDOW` 已经删了，`canDrawOverlays()` 于是**恒为 false**
         * —— 每次点开关都从这里直接 return，开关**看起来点不动**。
         *
         * ⚠️ 换成"无障碍服务连上了吗"才是**现在**的正确前置条件:
         * 窗口要靠它建（见 `OverlayWindowHost`）。
         *
         * ⚠️ 但**不能只是 return** —— 那样用户又遇到同一个问题:
         * 点了没反应、不知道为什么。所以要**跳系统无障碍设置页**，
         * 这是用户明确要求过的兜底:
         *
         * > 只需要开启悬浮窗的时候做个弥补，跳转系统的无障碍授权页
         */
        if (!SthKeyAccessibilityService.isConnected()) {
            AppLog.w(TAG, "无障碍服务未连接，取消开启（跳转系统无障碍设置页）")
            AccessibilityGrant.openSettings(context)
            return false
        }

        val app = context.applicationContext

        // 1. 累加写入：已有的 + 新的
        val current = OverlayLayouts.enabledIds(app).toSet()
        val next = current + configIds
        OverlayLayouts.setEnabledIds(app, next)
        AppLog.i(TAG, "悬浮窗开关：新增 ${configIds.size} 个（当前共 ${next.size} 个）")

        // 2. 启动服务（幂等：已经在跑就只是刷新）
        OverlayService.start(app)

        /*
         * 3. 采集
         *
         * ⚠️ **这里不再停-启采集，也不再需要 `restartCapture` 参数**。
         *
         * 采集现在是**应用级**的：应用一启动就开着，只在应用退出时结束。
         * 它不跟着悬浮窗开关走，也不跟着快捷方式走。
         *
         * 所以这里只需要"确保它在跑"：`start()` 本身幂等，
         * 已经在跑时什么都不做。这一个调用同时覆盖两种情况：
         *
         * - 冷启动点快捷方式（应用刚起来，采集可能还在建立通道）；
         * - 采集进程被系统杀掉之后（`_enabled` 还是 true 但没在真读，
         *   那种情况下 `start()` 的短路会挡住恢复）。
         *
         * ⚠️ 最后那条要注意：`start()` 只认 `_enabled` 这个"意图"标志，
         * 不认"实际在不在读"。所以这里补一个真实状态判断，
         * 否则被杀之后点快捷方式会毫无反应，而用户以为快捷方式坏了。
         */
        if (!CaptureController.isRunning()) {
            AppLog.i(TAG, "采集未在运行，重新拉起")
            CaptureController.stop()
        }
        CaptureController.start()
        return true
    }

    /**
     * 关闭**全部**悬浮窗（桌面快捷方式与调试页共用）。
     *
     * ⚠️ 清空集合与停服务**必须一起做**：只停服务的话集合里还留着一串 id，
     * 下次开任意一个开关会把它们**全部**一起拉起来 ——
     * 用户会觉得"我明明只开了一个"。
     */
    fun disableAll(context: Context) {
        val app = context.applicationContext
        OverlayLayouts.setEnabledIds(app, emptySet())
        /*
         * `clearEnabledIds = false`：集合上面已经清过了，让服务自己决定
         * 何时退出（它会发现"没有窗口"然后收尾）。传 true 会让清空动作
         * 做两遍，还会与"服务正在画的那些窗口"抢时序。
         */
        OverlayService.stop(app, clearEnabledIds = false)
        /*
         * ⚠️ **不停采集**。
         *
         * 采集是应用级的，与"有没有窗口"无关。这里停掉的话，
         * 用户"一键关闭"之后插上设备就没反应了 —— 而他会以为
         * 是设备或者一键关闭那个功能坏了。
         */
        AppLog.i(TAG, "已关闭全部悬浮窗（采集保持运行）")
    }

    /** 某份配置的悬浮窗当前是不是开着（界面与快捷方式共用的判据） */
    fun isEnabled(context: Context, configId: String): Boolean =
        configId in OverlayLayouts.enabledIds(context.applicationContext)
}

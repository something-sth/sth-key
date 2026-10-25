package com.something.sthkey.capture.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.KeyEvent
import com.something.sthkey.core.log.AppLog

/**
 * 「纯无障碍」采集通道的服务本体。
 *
 * ============================================================
 * ⚠️⚠️ 它同时干**两件事**，这是本方案的地基
 * ============================================================
 *
 * | 职责 | 靠什么 |
 * |---|---|
 * | **① 读按键** | [onKeyEvent] —— 纯无障碍模式下唯一的输入来源 |
 * | **② 当窗口宿主** | 它是**唯一**有资格建 `TYPE_ACCESSIBILITY_OVERLAY` 的 context |
 *
 * ⚠️ 第 ② 条容易被忽略:换用无障碍窗口之后，**悬浮窗的生死就系在这个服务上** ——
 * 服务没连上 = 一个窗口都建不出来（看 [OverlayWindowHost]）。
 *
 * ⚠️ 所以它**不是可选的**:哪怕用户走 root/Shizuku 通道，
 * 这个服务也必须被启用（只是那时不用它的按键回调）。
 * 用户的原话:
 *
 * > 用户如果授权了 shizuku/root，理应可以通过 shell 直接授权无障碍
 *
 * ============================================================
 * ⚠️ 与 Axon 的对照（用户让我参考它）
 * ============================================================
 * | | Axon | 我们 |
 * |---|---|---|
 * | 窗口 | 全部 `TYPE_ACCESSIBILITY_OVERLAY` | 同（见 [OverlayWindowHost]） |
 * | 按键 | `onKeyEvent` | 同 |
 * | 启动方式 | root/Shizuku 用 shell 打开 + 用户手动开 | 同 |
 *
 * ⚠️ 而 Axon 那边窗口代码**天生**就在服务里（它没有 `OverlayService`），
 * 我们的窗口代码在**两个独立的 Service** 里 —— 所以这里用一个
 * [OverlayWindowHost] 单例把"窗口资格"借给它们，**不搬那 4000 行代码**。
 * 理由见 `docs/accessibility-mode.md` §3.2。
 */
class SthKeyAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        /*
         * ⚠️ **必须第一时间登记** —— 窗口的建/改/删都靠它。
         *
         * ⚠️ 而且要在 [onKeyEvent] 之前:用户可能一进来就开悬浮窗，
         * 那时按键还没来，窗口却得先能建出来。
         */
        OverlayWindowHost.attach(this)
        AppLog.i(TAG, "无障碍服务已连接（按键通道 + 窗口宿主就绪）")
    }

    /**
     * 读按键。
     *
     * ============================================================
     * ⚠️⚠️ 返回值**恒为 `false`**
     * ============================================================
     * 返回 `true` = "这个事件我吃了，不给别的应用"。
     *
     * ⚠️ 一个**按键显示**工具绝不能吃掉用户的按键 —— 那样游戏就收不到输入了。
     * 我们只是**旁观**，所以恒 `false`。
     *
     * ⚠️ 这也是纯无障碍与"用 `onMotionEvent` 读鼠标"的根本区别:
     * 后者**登记即吞**（官方文档明说不会传到系统其余部分），
     * 所以鼠标那条路我们**不走**（见 docs/accessibility-mode.md §2.3）。
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        /*
         * ⚠️ 只关心按下/抬起 —— `ACTION_MULTIPLE` 之类的不要，
         * 那种事件的 `keyCode` 语义不同，混进去会让按键状态机错乱。
         */
        val action = event.action
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return false

        /*
         * ⚠️ 连按（`repeatCount > 0`）**要当成"仍然按住"**，不能当成新的按下 ——
         * 上层是按"边沿"计数 CPS 的（见 `CaptureSession.recordCpsClick`），
         * 把连按当成新按下会让 CPS 变成键盘的重复率（一秒几十次）。
         *
         * ⚠️ 所以这里**照传**，由上层自己判重（它有 `KeyStateManager`）。
         * 我们负责"原样上报"，不在这里偷偷改语义。
         */
        val pressed = action == KeyEvent.ACTION_DOWN

        onKeyEventCallback?.invoke(event.keyCode, pressed)
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        /*
         * ⚠️ 我们**不读界面内容**（`canRetrieveWindowContent=false`）。
         *
         * ⚠️ 这里目前什么都不做，但方法**不能删** —— 它是抽象方法，
         * 且配置里声明了 `typeWindowStateChanged`。留着是为了:
         * 以后要判断"前台是哪个应用"时有地方接（那是另一件事）。
         */
    }

    override fun onInterrupt() {
        /*
         * ⚠️ 系统要求"服务被中断时把正在做的事收尾"。
         *
         * ⚠️ 我们**不清按键状态** —— 那是 `CaptureController` 的事
         * （它才是按键状态的唯一真源）。这里清了反而会造成
         * "两个地方各清一半"的不一致。
         */
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        /*
         * ⚠️⚠️ **用户关掉无障碍时必须释放窗口资格**。
         *
         * ⚠️ 不释放的后果:窗口建不出来（`BadTokenException`），
         * 而用户看到的是"悬浮窗突然没了" —— 完全联想不到是无障碍被关了。
         *
         * ⚠️ 释放之后两个窗口服务会通过 [OverlayWindowHost] 拿到 `null`，
         * 由它们各自提示"需要无障碍服务"（见那边的说明）。
         */
        OverlayWindowHost.detach()
        AppLog.i(TAG, "无障碍服务已断开（窗口宿主随之失效）")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        OverlayWindowHost.detach()
        AppLog.i(TAG, "无障碍服务已销毁")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Accessibility"

        /**
         * 按键回调 —— 由 [CaptureController] 在走无障碍通道时挂上、停用时摘掉。
         *
         * ⚠️ 用**可空回调**而不是直接调采集层:服务不认识采集层，
         * 而采集层要能在"切换到 root/Shizuku"时把它摘掉 ——
         * 否则两条通道会**同时**上报同一个键，CPS 直接翻倍。
         *
         * ⚠️ `volatile`:写入在 UI 线程、读取在系统回调线程。
         */
        @Volatile
        var onKeyEventCallback: ((keyCode: Int, pressed: Boolean) -> Unit)? = null

        /**
         * 服务现在是否连着（能读按键、也能当窗口宿主）。
         *
         * ⚠️ 判据是 [OverlayWindowHost] 里有没有 context —— 那才是
         * "真有服务实例"的证据。**不要**用 `Settings.Secure` 里那个
         * 已启用列表:那是"用户打开过"，而服务可能还没连上（甚至被系统杀了）。
         */
        fun isConnected(): Boolean = OverlayWindowHost.isAttached()
    }
}

package com.something.sthkey.shortcut

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.something.sthkey.capture.OverlayController
import com.something.sthkey.core.log.AppLog
import com.something.sthkey.data.config.ConfigStore
import com.something.sthkey.data.shortcut.ShortcutSettings
/**
 * 桌面快捷方式的入口：**完全不进前台**。
 *
 * ============================================================
 * 为什么必须是 Activity，不能是 Service / Receiver
 * ============================================================
 * 要做的三件事里有两件需要"前台身份"：
 *
 * 1. 启动**前台服务**（悬浮窗）—— Android 12+ 限制后台启动 FGS；
 * 2. 启动**采集**（Shizuku 通道要拿 binder）—— 同样受限。
 *
 * 而"用户点了桌面图标"这件事给的是一个 **Activity 启动**。
 * 走 Service 或 Receiver 的话，从启动器点击到 FGS 真正起来之间有一段
 * 没有前台组件的空窗，部分 ROM 会判定成后台启动直接拦掉。
 *
 * 所以：Activity 只做"立刻转交给 Service 然后结束自己"这一步。
 *
 * ============================================================
 * ⚠️ 三个容易踩的点
 * ============================================================
 * - **必须 `finish()` 得足够早**：主题是 `Theme.NoDisplay`，
 *   而它要求 Activity 在 `onResume` 之前就结束 —— 否则系统直接抛
 *   `IllegalStateException`（"声明了不显示却显示了"）。
 *   所以工作放在 `onCreate`，`finish()` 在它末尾同步调用。
 *
 * - **`onCreate` 里不能 await 异步结果**：`OverlayService.start` 与
 *   `CaptureController.start` 本身都是"发起请求就返回"，不会阻塞，
 *   正好适合这里。要等的失败结果由它们自己弹提示（见下）。
 *
 * - **`savedInstanceState == null` 才处理**：转屏等原因重建时不能重复执行，
 *   否则会莫名其妙多启动一次采集。
 */
class ShortcutActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        /*
         * ⚠️ 重建时不重复执行。
         *
         * 这个 Activity 理论上一瞬间就结束、不会重建，但系统在某些情况下
         * （比如它还没 finish 就转屏）会重建它。那时再执行一遍会
         * 白白重启一次采集。
         */
        if (savedInstanceState == null) {
            dispatch(
                action = intent?.getStringExtra(ShortcutSettings.EXTRA_ACTION),
                shortcutId = intent?.getStringExtra(ShortcutSettings.EXTRA_SHORTCUT_ID),
            )
        }

        /*
         * `NoDisplay` 主题要求这里**同步**结束自己。
         * 后面不要加任何挂起/异步的等待。
         */
        finish()
    }

    /**
     * 按快捷方式带的动作干活。
     *
     * ⚠️ 动作名来自一个**冻结的 Intent**（可能是几个版本前钉下的），
     * 所以要能容忍不认识的取值 —— 那种情况什么都不做而不是崩。
     */
    private fun dispatch(action: String?, shortcutId: String?) {
        when (action) {
            ShortcutSettings.ACTION_LAUNCH -> launchOverlays(shortcutId)

            ShortcutSettings.ACTION_DISABLE_ALL -> {
                OverlayController.disableAll(this)
                toast("已关闭全部悬浮窗")
            }

            else -> {
                /*
                 * 没带动作（或带了不认识的值）。
                 *
                 * 不做"兜底打开全部配置"之类的猜测：快捷方式的语义必须是
                 * 明确的，猜错了会凭空弹出一堆窗口。记一条日志就够了。
                 */
                AppLog.w(TAG, "快捷方式动作无法识别：$action")
            }
        }
    }

    private fun launchOverlays(shortcutId: String?) {
        if (shortcutId.isNullOrBlank()) {
            /*
             * 老版本钉下的快捷方式没带 id（第一版没有这个 extra）。
             *
             * 不猜"那就用第一个"：那样用户点任何旧图标都启动同一组配置，
             * 而他可能已经建了好几个。明确让他重新创建一次更安全。
             */
            toast("这个快捷方式是旧版本创建的，请到应用主页重新添加")
            AppLog.w(TAG, "快捷方式没带 id，可能是旧版本创建的")
            return
        }

        /*
         * ⚠️ 清单里可能含**已删除**的配置 id（用户勾了之后又去删了配置）。
         * 必须过滤：不过滤的话 `OverlayController` 会把无效 id 写进
         * "启用了哪些配置"，而服务找不到那份配置 ——
         * 表现是"点快捷方式没反应"或"窗口比勾的少一个"，
         * 完全联想不到是删配置导致的。
         */
        val allIds = ConfigStore.get(this).all().map { it.id }.toSet()
        val ids = ShortcutSettings.resolveExisting(this, shortcutId, allIds)

        if (ids.isEmpty()) {
            /*
             * 一句提示很关键：否则用户点完快捷方式**桌面毫无变化**，
             * 只能怀疑是不是坏了。这是"不进前台"这条设计最容易让人困惑的地方。
             */
            toast("快捷方式里没有可用的配置，请在应用主页重新设置")
            AppLog.w(TAG, "快捷方式没有可启动的配置")
            return
        }

        /*
         * ⚠️ 不再传 `restartCapture`（那个参数已经删掉了）。
         *
         * 从前它传 `true` 是为了"每次点快捷方式都重启一次采集"，
         * 因为旧的 `getevent` 通道**不能热插拔** —— 插上设备必须重新
         * 扫描才能识别，而重扫只能走一次完整的停-启。
         *
         * **那个限制已经不存在了**：现在采集走全局监听
         * （`getevent -t` 不带设备参数），设备由 `getevent` 自己枚举、
         * 热插拔由它打印的设备公告给出。
         *
         * 而且采集现在是**应用级**的：冷启动点快捷方式时
         * `Application.onCreate` 已经把采集拉起来了，
         * `enableConfigs` 里那句 `start()` 只是幂等的兜底。
         */
        val ok = OverlayController.enableConfigs(
            context = this,
            configIds = ids,
        )

        if (ok) {
            toast("已启动 ${ids.size} 个悬浮窗")
        } else {
            /*
             * 最常见的失败原因是没有悬浮窗权限（用户可能在系统设置里关掉了）。
             * 必须说清，否则同样是"点了没反应"。
             */
            toast("没有悬浮窗权限，请先在应用里完成授权")
        }
    }

    private fun toast(message: String) {
        /*
         * 用 Toast 而不是通知：这是**一次操作的即时反馈**，
         * 与"常驻状态"（那由 OverlayService 的前台通知负责）是两回事。
         */
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val TAG = "Shortcut"
    }
}

package com.something.sthkey.data.shortcut

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.something.sthkey.MainActivity
import com.something.sthkey.core.log.AppLog
import com.something.sthkey.shortcut.ShortcutActivity

/**
 * 把一个快捷方式**钉到桌面上**。
 *
 * ============================================================
 * 为什么用 `requestPinShortcut` 而不是 `INSTALL_SHORTCUT` 广播
 * ============================================================
 * 老的 `com.android.launcher.action.INSTALL_SHORTCUT` 广播从 Android 8.0
 * 起已经废弃，而且是"应用偷偷往桌面塞图标"的语义 —— 现在的 ROM 基本
 * 不认它，也不再需要 `INSTALL_SHORTCUT` 权限。
 *
 * 现在的标准做法是 [ShortcutManagerCompat.requestPinShortcut]：系统会弹
 * 一个"要把这个添加到主屏幕吗"的确认框，用户点确定才真的加上。
 * **不需要任何权限**，而且是用户自己点的，语义正确。
 *
 * ============================================================
 * ⚠️ 有些启动器不支持
 * ============================================================
 * 用 [ShortcutManagerCompat.isRequestPinShortcutSupported] 先判断。
 * 不支持时**必须明确告诉用户**，而不是静默什么都不做 ——
 * 后者在用户看来就是"点了确定但桌面什么都没多出来"。
 */
object ShortcutPublisher {

    private const val TAG = "Shortcut"

    /**
     * 钉"启动悬浮窗"快捷方式。
     *
     * @param shortcutId **必须由调用方分配**（见 `ShortcutSettings.nextLauncherId`）。
     *   ⚠️ 写死一个固定 id 的话，第二次创建会**顶掉第一个** ——
     *   系统认为那是同一个快捷方式。用户看到的现象就是
     *   "我只能加两个：一个启动的、一个关闭的"。
     * @param icon 图标；null 时用应用图标
     * @return null 表示成功（或已交给系统处理）；非 null 是失败原因（给界面提示）
     */
    fun pinLaunchShortcut(
        context: Context,
        shortcutId: String,
        label: String,
        icon: IconCompat? = null,
    ): String? =
        pin(
            context = context,
            shortcutId = shortcutId,
            label = label,
            action = ShortcutSettings.ACTION_LAUNCH,
            icon = icon,
        )

    /**
     * 钉"关闭所有悬浮窗"快捷方式。
     *
     * ⚠️ 这一种**只能有一个**（id 固定）：多一个"关闭全部"的图标没有任何
     * 意义，而且它们的行为完全一样。所以不分配序号。
     */
    fun pinDisableShortcut(context: Context, label: String, icon: IconCompat? = null): String? =
        pin(
            context = context,
            shortcutId = ShortcutSettings.DISABLE_SHORTCUT_ID,
            label = label,
            action = ShortcutSettings.ACTION_DISABLE_ALL,
            icon = icon,
        )

    /**
     * 从"图标文件"造一个 `IconCompat`；读不出来返回 null。
     *
     * ⚠️ 这里**刻意读成位图**，而不是用 `IconCompat.createWithContentUri(file)`。
     *
     * 传 `file://` 的话，读取方是**启动器进程** —— 它没有权限读我们私有目录
     * 里的文件。结果不是崩，而是**白图标**（各家启动器的失败表现不一致，
     * 有的干脆显示成默认图标），而且从我们的代码里完全看不出问题。
     *
     * 读成位图就是把自己这份数据交给系统，不存在跨进程读权限的问题。
     * 代价是图标走 Binder 传输 —— 192×192 的 PNG 只有几十 KB。
     */
    fun iconFromFile(file: java.io.File): IconCompat? = runCatching {
        android.graphics.BitmapFactory.decodeFile(file.absolutePath)
            ?.let { IconCompat.createWithBitmap(it) }
    }.getOrNull()

    /**
     * 把已经拿到的位图包成 `IconCompat`。
     *
     * 与 [iconFromFile] 的区别只是"位图从哪来" —— 生成图标的那条路
     * （`configIconBitmap`）已经拿着位图了，再落盘一次、再读回来纯属多余。
     */
    fun iconFromBitmap(bitmap: android.graphics.Bitmap): IconCompat =
        IconCompat.createWithBitmap(bitmap)

    private fun pin(
        context: Context,
        shortcutId: String,
        label: String,
        action: String,
        icon: IconCompat?,
    ): String? {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            AppLog.w(TAG, "当前启动器不支持由应用请求创建快捷方式")
            return "当前桌面不支持由应用添加快捷方式，请到桌面的小部件/快捷方式菜单里手动添加"
        }

        return try {
            /*
             * ⚠️ Intent 必须**同时**指定 targetClass 与 action。
             *
             * action 用 `ACTION_VIEW` 而不是自定义字符串：部分启动器对
             * 自定义 action 的处理不一致，而 VIEW 是最通用的。
             * 真正的"要干什么"由 extra 里的 [ShortcutSettings.EXTRA_ACTION]
             * 决定 —— 这也是唯一能改的部分（Intent 本身钉下去就冻结了）。
             */
            val intent = Intent(context, ShortcutActivity::class.java).apply {
                this.action = Intent.ACTION_VIEW
                putExtra(ShortcutSettings.EXTRA_ACTION, action)
                /*
                 * ⚠️ **id 也必须放进 Intent**（不只是 extra 里的动作）。
                 *
                 * "启动哪几份配置"是按 id 去偏好里查的 —— 不带 id 的话
                 * 所有启动类快捷方式都会读到同一份清单，
                 * 那正是"多个启动快捷方式行为一样"的 bug。
                 */
                putExtra(ShortcutSettings.EXTRA_SHORTCUT_ID, shortcutId)
                /*
                 * `FLAG_ACTIVITY_NEW_TASK` 必须加：从启动器发起的启动
                 * 不在任何任务栈里。不加的话部分 ROM 上会直接起不来。
                 */
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val info = ShortcutInfoCompat.Builder(context, shortcutId)
                .setShortLabel(label)
                .setLongLabel(label)
                /*
                 * ⚠️ 图标：调用方给了就用它的，没给才回落应用图标。
                 *
                 * 图标是**钉下去那一刻定下**的 —— 之后改配置不会自动更新，
                 * 要更新只能让用户重新创建一次。这一点在设计界面时必须说清。
                 */
                .setIcon(
                    icon ?: IconCompat.createWithResource(
                        context,
                        com.something.sthkey.R.mipmap.ic_launcher,
                    ),
                )
                .setIntent(intent)
                /*
                 * ⚠️ `setActivity(ComponentName)` 很重要。
                 *
                 * 它决定这个快捷方式"属于哪个 Activity" —— 系统按 Activity
                 * 统计数量上限（`getMaxShortcutCountPerActivity`，一般是 5），
                 * 长按应用图标弹出的菜单也是按它归组的。
                 *
                 * ⚠️ 收 `ComponentName` 而不是 `Class`（这一点容易写错）。
                 */
                .setActivity(ComponentName(context, MainActivity::class.java))
                .build()

            ShortcutManagerCompat.requestPinShortcut(context, info, null)
            AppLog.i(TAG, "已请求钉快捷方式：$shortcutId（$label）")
            null
        } catch (e: Exception) {
            AppLog.e(TAG, "请求钉快捷方式失败", e)
            e.message ?: e.javaClass.simpleName
        }
    }

    /*
     * ============================================================
     * ⚠️ 这里**没有**"删除快捷方式"，这是刻意的
     * ============================================================
     * 曾经有过 `unpin()`（内部调 `removeLongLivedShortcuts`）+ 一个
     * "已有的快捷方式"列表 + 删除按钮。它**做不到它承诺的事**：
     *
     * - 我们能移除的只是**自己那条记录**与系统里的声明；
     * - **桌面上那个图标是启动器管的**，多数启动器不会因为我们调用
     *   `removeLongLivedShortcuts` 就把用户的图标删掉（各家行为不一致，
     *   而且这是它们自己的 UI 决定）。
     *
     * 结果是：用户点了删除、列表里没了，但桌面图标还在 ——
     * 而那个图标已经变成**空壳**（记录没了，点它什么也不发生）。
     *
     * **"删不干净"比"没有删除功能"更糟**，所以整块去掉了。
     * 要删就让用户在桌面上长按删 —— 那是启动器自己的功能，一定删得掉。
     *
     * 同样地，也不需要 `isPinned()`：既然每次都只是"新建一个"，
     * 就没有"这个是已存在的、按钮要写更新"这种判断。
     */

    /*
     * ⚠️ 这里也**没有**固定的快捷方式 id 常量。
     *
     * "启动悬浮窗"可以有多个（用户想给不同的配置组合各做一个图标），
     * 所以 id 由 `ShortcutSettings.nextLauncherId` 动态分配。
     * 写死一个常量的话，第二次创建会因为 id 相同而**顶掉第一个** ——
     * 用户看到的现象就是"我只能加两个：一个启动的、一个关闭的"。
     */
}

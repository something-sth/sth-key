package com.something.sthkey

import android.app.Application
import android.os.Process
import com.something.sthkey.capture.CaptureController
import com.something.sthkey.core.log.AppLog
import com.something.sthkey.core.log.CrashLogger
import com.something.sthkey.core.prefs.AppPrefs
import com.something.sthkey.data.config.ConfigStore
import com.something.sthkey.data.live2d.Live2DModelImporter
import com.something.sthkey.domain.font.FontRegistry
import com.something.sthkey.domain.font.bitmap.BitmapFontStore
import com.something.sthkey.domain.live2d.Live2DModels
import com.something.sthkey.domain.overlay.OverlayLayouts

/**
 * 应用入口。
 *
 * ============================================================
 * 关于"第二个进程"（历史记录，现在已经没有这件事了）
 * ============================================================
 * 早先用 Shizuku 的 UserService 时，Shizuku 会用 `app_process` 在另一个进程里
 * 再加载一次**同一个 Application 类**（进程名形如 `com.something.sthkey:input`）。
 * 那时必须在这里把非主进程挡掉：否则它会在那个进程里再去探测 root、起 `su`、
 * 恢复采集，结果多半崩掉，而 Shizuku 侧永远等不到 `onServiceConnected` ——
 * 用户看到的现象是"绑定 UserService 超时"。
 *
 * ⚠️ UserService 通道已经删掉了（改为直连：只发一条 Binder 事务让对方起 `sh`，
 * 不再加载我们的类），所以那段进程判断成了永远不会命中的死代码，已移除。
 *
 * 保留这段说明是因为：**如果将来又要"在另一个进程里按类名加载我们自己的类"，
 * 这个坑会原样回来** —— 而且它当时的表象（绑定超时）与真正的原因
 * （子进程里重复初始化）隔得很远，很难定位。
 */
class SthKeyApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        CrashLogger.install(this)

        AppLog.i("App", "主进程启动（$packageName / ${currentProcessName()}）")

        // 上次如果是崩溃退出的，这里记一条，方便用户在调试页看到
        CrashLogger.lastCrashTime(this)?.let { time ->
            AppLog.w("App", "检测到上次运行以崩溃结束（记录时间戳 $time），可在调试页查看详情")
        }

        /*
         * 把旧的"全局单份悬浮窗设置"迁移到"每个配置一份"。
         *
         * ⚠️ 必须在 [CaptureController.init] **之前**跑：采集恢复读的是
         * "哪几个窗口开着"，而迁移正是把旧的那个布尔翻译成这份列表的地方。
         * 顺序反了的话，老用户升级后第一次启动会恢复不出采集。
         */
        OverlayLayouts.migrateLegacy(this)

        // 按上次的意图恢复采集：用户开着悬浮窗直接进游戏时，
        // 应用可能已经被系统回收过，恢复意图能保证采集不会被中断
        CaptureController.init(this, AppPrefs.get(this))

        // 字体库需要上下文才能读 assets 与私有目录
        FontRegistry.init(this)
        // 顺手清掉"记录还在、文件没了"的失效字体（用户清过数据等极端情况）
        FontRegistry.pruneMissing()

        /*
         * 图片字体（Minecraft 风格的位图图集）。
         *
         * ⚠️ 必须在 [FontRegistry.pruneMissing] **之前**初始化：
         * `FontRegistry.all()` 会去问图片字体库有哪些条目
         * （见 `FontRegistry.bitmapFonts`），没初始化就会漏掉它们。
         */
        BitmapFontStore.init(this)
        BitmapFontStore.pruneMissing()

        // Live2D 模型库同理：导入的模型放在私有目录
        Live2DModels.init(this)
        Live2DModels.pruneMissing()
        // 生成页面的逻辑可能变过：把已导入模型的页面刷新到当前版本
        Live2DModelImporter.repairImportedPages()

        /*
         * 清掉已删除配置留下的悬浮窗开关与设置。
         *
         * 放在最后：要等配置仓库读完之后才有"现存的 id 集合"可比对。
         * 不做这一步的话，一个失效的 id 会永久占住"还有窗口开着"的判定，
         * 表现是"全部关掉之后监听仍然不停"。
         */
        OverlayLayouts.pruneMissing(this, ConfigStore.get(this).all().map { it.id }.toSet())
    }

    /**
     * 取当前进程名。
     *
     * `Process.myProcessName()` 从 API 28 起可用，minSdk 是 30，因此可以直接用；
     * 万一返回空串，再退化为读 /proc/self/cmdline。
     *
     * 现在只用它打一行启动日志（排查时能看出是哪个进程），
     * 不再用于"区分主进程与 UserService 进程"—— 那个判断已经不需要了。
     */
    private fun currentProcessName(): String {
        val direct = runCatching { Process.myProcessName() }.getOrNull()
        if (!direct.isNullOrBlank()) return direct

        return runCatching {
            java.io.File("/proc/self/cmdline").readText().trim().trimEnd('\u0000')
        }.getOrDefault("")
    }
}

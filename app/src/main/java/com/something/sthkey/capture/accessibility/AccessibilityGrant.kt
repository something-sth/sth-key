package com.something.sthkey.capture.accessibility

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.something.sthkey.capture.shizuku.ShizukuShell
import com.something.sthkey.core.log.AppLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext


/**
 * **代为开启无障碍服务**（root / Shizuku 的 shell 身份）。
 *
 * ============================================================
 * ⚠️⚠️ 为什么需要这个类
 * ============================================================
 * 用户的原话:
 *
 * > 用户如果授权了 shizuku/root，**理应可以通过 shell 直接授权无障碍**……
 * > 既然有了 shell **那直接启动的时候也授权一下无障碍做个保障**就好了
 *
 * ⚠️ 这件事**不是可选的**:换成无障碍窗口之后，**没有无障碍服务就没有悬浮窗**。
 * 所以"有特权却没开无障碍"必须能**自动修好**，否则用户会以为
 * "我明明给了 root，怎么什么都不显示"。
 *
 * ============================================================
 * ⚠️⚠️ 教训:shell 脚本**只做最简单的事**
 * ============================================================
 * 我第一版把整段列表运算（`for` / `case` / `if`）写成 shell，靠
 * `.replace("\n", "; ")` 压成一行。⚠️ **那产生的是语法错误**:
 *
 * ```sh
 * if [ -z "$CURRENT" ]; then; NEW="..."; else; case ... esac; fi
 *                              ↑                              ↑
 *                        then 后面不能紧跟 ;            else 后面也不能
 * ```
 *
 * ⚠️ 结果:命令**从来没成功过**（`settings` 一次都没写），于是每次开悬浮窗
 * 都"卡一下、然后跳到无障碍设置页"（用户实测报的就是这个）。
 *
 * ⭐ 现在改成:**列表运算全在 Kotlin 里算**（[mergeServiceList]，有单测），
 * shell 只剩下两条朴素的命令:
 *
 * ```sh
 * settings get secure enabled_accessibility_services      # 读
 * settings put secure enabled_accessibility_services '…'  # 写
 * ```
 *
 * ⚠️ 这样做还有个额外好处:那个"保留别人已启用的服务"的逻辑**可以被单测覆盖**了
 * —— 写在 shell 里的时候它一行测试都没有，而它一旦写错，
 * 后果是"把用户的屏幕阅读器关掉了"。
 *
 * 参考实现（**只读**）:
 * `Axon-Input-main/app/src/main/java/com/axon/input/MainActivity.java:3565`
 */
internal object AccessibilityGrant {

    private const val TAG = "Accessibility"

    /** `Settings.Secure` 里存"已启用无障碍服务"的那个键 */
    private const val KEY_ENABLED_SERVICES = "enabled_accessibility_services"

    /** `Settings.Secure` 里存总开关的那个键 */
    private const val KEY_ACCESSIBILITY_ENABLED = "accessibility_enabled"

    /**
     * 无障碍服务在系统里的**完整组件名**（`包名/完整类名`）。
     *
     * ⚠️ 必须与 manifest 里那个 `android:name` **逐字对应**，
     * 否则 `settings put` 写进去的是一个不存在的组件 —— 系统不会报错，
     * 只是永远不生效。那种"写了但没效果"最难查。
     */
    /*
     * ============================================================
     * 一次性通知：「shell 把无障碍打开了」
     * ============================================================
     * ⚠️ 为什么放在这里而不是调用方:
     *
     * `grant()` 有**三条**调用路径（开悬浮窗前 / 用户点重试 / 后台能力探测），
     * 而实际生效的往往是**最早的那条** —— 用户在首次启动引导页就把
     * 无障碍开好了，之后开悬浮窗时 `isConnected()` 已经是 true，
     * 那些"授权完再提示"的调用点根本不会被走到（第一版就栽在这）。
     *
     * ⚠️ 所以在**唯一的成功点**发事件，UI 层收集后弹一次 Toast。
     * 用 `SharedFlow` 而不是 `StateFlow`:这是一次性事件，
     * `StateFlow` 会让后来的收集者又收到一遍旧消息。
     */
    private val _notice = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val notice: SharedFlow<String> = _notice.asSharedFlow()

    fun componentName(context: Context): String =
        ComponentName(context, SthKeyAccessibilityService::class.java).flattenToString()

    /*
     * ============================================================
     * 列表运算（**纯函数，有单测**）
     * ============================================================
     */

    /**
     * 把 [service] 并进现有的已启用列表，**保留别的服务**。
     *
     * ============================================================
     * ⚠️⚠️ 这条是"别把用户别的无障碍服务关掉"的唯一防线
     * ============================================================
     * `enabled_accessibility_services` 是一个用 **冒号** 分隔的列表，
     * 里面可能还有用户的屏幕阅读器、自动化工具、输入法……
     *
     * ⚠️ 所以**绝不能**直接 `settings put secure ... "$SERVICE"` ——
     * 那会把整串覆盖掉，等于**一次关掉用户所有无障碍服务**。
     * 那是个会让人崩溃的 bug，而且很难联想到是我们干的。
     *
     * @param current `settings get` 的原样输出。⚠️ 可能是:
     *   · `null`（字符串，**不是** null 引用）—— 系统没设过时给的就是这个
     *   · `""` / 纯空白
     *   · `a/b:c/d` 这种列表
     * @param forceRebind `true` = 先把它从列表里**摘掉**（用于"重绑"）。
     *   与 `false` 的区别只在"已经在列表里"时。
     * @return 新的列表（用冒号连接）。⚠️ 返回空串表示"一个都不启用"，
     *   这时调用方要 `settings delete` 而不是 `put ""`（见 [grant]）。
     */
    fun mergeServiceList(
        current: String?,
        service: String,
        forceRebind: Boolean = false,
    ): String {
        val trimmed = current?.trim().orEmpty()
        /* ⚠️ 系统没设过时 `settings get` 输出的是字符串 "null"，要当成空 */
        val existing = if (trimmed.isEmpty() || trimmed.equals("null", ignoreCase = true)) {
            emptyList()
        } else {
            trimmed.split(':').map { it.trim() }.filter { it.isNotEmpty() }
        }

        val others = existing.filter { it != service }
        /* ⚠️ 没有 forceRebind 时保留原来的位置；有就只留别人、把我们的加到末尾 */
        val alreadyThere = !forceRebind && existing.contains(service)
        val result = if (alreadyThere) {
            existing
        } else {
            others + service
        }
        return result.joinToString(":")
    }

    /**
     * 把 [service] 从列表里**摘掉**（重绑用）。
     *
     * ⚠️ 返回空串时调用方要 `settings delete` —— 往一个"空列表"里
     * `put ""` 在某些 ROM 上会留下一个空条目，反而让无障碍总开关失效。
     */
    fun removeServiceFromList(current: String?, service: String): String {
        val trimmed = current?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.equals("null", ignoreCase = true)) return ""
        return trimmed.split(':')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != service }
            .joinToString(":")
    }

    /*
     * ============================================================
     * 授权
     * ============================================================
     */

    /**
     * 结果。
     *
     * ⚠️ [writeSucceeded] 与 [connected] 是**两件事** ——
     * 前者只是"settings 写进去了"，后者才是"服务真的连上了、能用"。
     *
     * ⚠️ 只写不验就是这个项目最容易踩的"假成功"
     * （Axon 的注释也专门提醒过这一点）。
     */
    data class Result(
        val writeSucceeded: Boolean,
        val connected: Boolean,
        val detail: String,
    ) {
        /** 真正可用的判据 —— **只看这个** */
        val ok: Boolean get() = connected
    }

    /**
     * 用 shell 打开无障碍，并**等到服务真的连上**。
     *
     * @param useRoot `true` 用 `su`;`false` 走 Shizuku 的 shell
     * @param forceRebind `true` = 先摘掉再加回来（服务卡死时用）
     * @param timeoutMs 等"服务连上"最多等多久
     */
    suspend fun grant(
        context: Context,
        useRoot: Boolean,
        forceRebind: Boolean = false,
        timeoutMs: Long = 4000L,
    ): Result = withContext(Dispatchers.IO) {
        val service = componentName(context)
        /* ⚠️ 已经在跑就不用折腾了 —— 少一次 su 调用，用户少等一会儿 */
        if (SthKeyAccessibilityService.isConnected() && !forceRebind) {
            return@withContext Result(
                writeSucceeded = true,
                connected = true,
                detail = "已开启",
            )
        }

        AppLog.i(TAG, "尝试用 ${if (useRoot) "root" else "Shizuku"} 开启无障碍")

        /* ① 读现状 */
        val current = runShell(useRoot, "settings get secure $KEY_ENABLED_SERVICES")
        if (current == null) {
            AppLog.w(TAG, "读不到无障碍启用列表（shell 执行失败）")
            return@withContext Result(
                writeSucceeded = false,
                connected = SthKeyAccessibilityService.isConnected(),
                detail = "无法执行 shell 命令（root / Shizuku 未就绪？）",
            )
        }
        AppLog.i(TAG, "当前已启用的无障碍服务：${current.trim().ifEmpty { "(空)" }}")

        /* ② 重绑时先摘掉，给系统时间处理"移除" */
        if (forceRebind) {
            val without = removeServiceFromList(current, service)
            val removed = writeList(useRoot, without)
            AppLog.i(TAG, "重绑：已先移除自己（成功=$removed）")
            if (removed) delay(300)
        }

        /* ③ 写入（保留别人的） */
        val merged = mergeServiceList(
            current = if (forceRebind) null else current,
            service = service,
            forceRebind = forceRebind,
        )
        val writeOk = writeList(useRoot, merged)
        if (!writeOk) {
            AppLog.w(TAG, "写入无障碍设置失败")
            return@withContext Result(
                writeSucceeded = false,
                connected = SthKeyAccessibilityService.isConnected(),
                detail = "写入系统设置失败",
            )
        }
        AppLog.i(TAG, "已写入无障碍启用列表：$merged")

        /* ④ **必须等它真的连上** —— settings 写成功不等于服务起来了 */
        val connected = awaitConnected(timeoutMs)

        /*
         * ⚠️ 只在**这次真的把它打开**时发 —— 上面那个"已开启"的提前返回
         * （用户自己手动开过）不会走到这里，所以手动授权不会弹提示。
         */
        if (connected) {
            _notice.tryEmit(
                "无障碍服务已通过 ${if (useRoot) "root" else "Shizuku"} 启动",
            )
        }
        Result(
            writeSucceeded = true,
            connected = connected,
            detail = if (connected) "已开启" else "已写入设置，但服务尚未连接（可能被系统限制了）",
        )
    }

    /**
     * 写"已启用列表"+ 打开总开关。
     *
     * ⚠️ **总开关也要开** —— 光有列表不够。用户可能在某次操作里
     * 把「无障碍」总开关关了，那时列表里有我们也不会生效。
     *
     * ⚠️ 列表为空时用 `settings delete` 而不是 `put ""`:
     * 某些 ROM 上留一个空条目会让整个无障碍系统判成配置损坏。
     */
    private suspend fun writeList(useRoot: Boolean, list: String): Boolean {
        val putServices = if (list.isEmpty()) {
            "settings delete secure $KEY_ENABLED_SERVICES"
        } else {
            /*
             * ⚠️ 用**单引号**包住列表 —— 组件名里有 `/` 和 `.`，
             * 单引号里它们都是普通字符，不需要转义。
             * ⚠️ 组件名里**不可能**有单引号（包名与类名不允许），所以这样是安全的。
             */
            "settings put secure $KEY_ENABLED_SERVICES '$list'"
        }
        val command = "$putServices; settings put secure $KEY_ACCESSIBILITY_ENABLED 1"
        return runShell(useRoot, command) != null
    }

    /*
     * ============================================================
     * shell 执行
     * ============================================================
     */

    /**
     * 跑一条命令，返回 stdout。
     *
     * @return `null` = **执行失败**（拿不到进程 / 非 0 退出 / 抛异常）。
     *   ⚠️ 返回 `null` 与"输出空串"是**两件事**:前者是失败，后者是"命令成功但没有输出"。
     *   调用方必须区分。
     */
    private suspend fun runShell(useRoot: Boolean, command: String): String? =
        if (useRoot) runRoot(command) else runShizuku(command)

    /**
     * `su -c <command>`，返回 stdout。
     *
     * ⚠️ `redirectErrorStream(true)` + 把输出**读干净** ——
     * 不读的话缓冲区满了进程会卡住，然后就永远等不到退出
     * （表现是"授权卡住不动"）。
     */
    private fun runRoot(command: String): String? = try {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        if (exit == 0) output else {
            AppLog.w(TAG, "root 命令退出码 $exit，输出：${output.take(200)}")
            null
        }
    } catch (t: Throwable) {
        AppLog.w(TAG, "root 执行失败：${t.javaClass.simpleName}")
        null
    }

    /** Shizuku 的 shell，返回 stdout（理由同上） */
    private suspend fun runShizuku(command: String): String? = try {
        val shell = ShizukuShell.start(command)
        try {
            shell.inputStream.bufferedReader().use { it.readText() }
        } finally {
            runCatching { shell.close() }
        }
    } catch (t: Throwable) {
        AppLog.w(TAG, "Shizuku 执行失败：${t.javaClass.simpleName}")
        null
    }

    /**
     * 轮询等服务连上；超时就返回 false（**不抛异常**，由调用方决定怎么说）。
     *
     * ⚠️ 用轮询而不是 `delay(固定值)`:不同机型绑定快慢差很多
     * （实测有的 100 多毫秒、有的要一秒以上）。
     */
    private suspend fun awaitConnected(timeoutMs: Long): Boolean {
        val step = 100L
        var waited = 0L
        while (waited <= timeoutMs) {
            if (SthKeyAccessibilityService.isConnected()) return true
            delay(step)
            waited += step
        }
        return SthKeyAccessibilityService.isConnected()
    }

    /*
     * ============================================================
     * 读状态 / 跳设置页
     * ============================================================
     */

    /**
     * 用户在系统设置里**打开过**我们吗。
     *
     * ⚠️ 这只是"设置里那串列表含我们"，**不等于服务连上了**。
     * 判"能不能用"请用 [SthKeyAccessibilityService.isConnected]。
     *
     * ⚠️ 它的用处是区分两种失败:
     *
     * | 情况 | 该提示什么 |
     * |---|---|
     * | 列表里**没有**我们 | "去系统设置开启" |
     * | 列表里**有**我们但服务没连上 | ⚠️ "可能被系统限制了，试着关掉再打开" |
     */
    fun isEnabledInSettings(context: Context): Boolean = try {
        val enabled = Settings.Secure.getString(context.contentResolver, KEY_ENABLED_SERVICES)
            .orEmpty()
        val service = componentName(context)
        enabled.split(':').any { it.equals(service, ignoreCase = true) }
    } catch (t: Throwable) {
        AppLog.w(TAG, "读取无障碍启用列表失败：${t.javaClass.simpleName}")
        false
    }

    /**
     * 跳到系统的无障碍设置页。
     *
     * ⚠️ 这是**兜底**（用户没有 root/Shizuku、又跳过了引导页时）。
     * 用户的原话:
     *
     * > 就算用户跳过了授权直接进来，也只需要开启悬浮窗的时候做个弥补，
     * > **跳转系统的无障碍授权页**让用户授权就好了
     *
     * ⚠️ 加 `FLAG_ACTIVITY_NEW_TASK` —— **必须**，否则从 Service/Application
     * 起动 Activity 会直接抛 `AndroidRuntimeException`。
     */
    fun openSettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure {
                AppLog.w(TAG, "打不开无障碍设置页：${it.javaClass.simpleName}")
            }
    }
}

package com.something.sthkey.ui.feature.config

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.something.sthkey.core.log.AppLog
import com.something.sthkey.core.prefs.AppPrefs
import com.something.sthkey.data.config.ConfigExporters
import com.something.sthkey.data.config.ConfigPackageCodec
import com.something.sthkey.data.config.ConfigPackageManager
import com.something.sthkey.data.config.ExportMethod
import com.something.sthkey.data.config.GroupPackageCodec
import com.something.sthkey.data.config.GroupPackageManager
import com.something.sthkey.domain.config.ConfigGroup
import com.something.sthkey.domain.config.KeyStrokesConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


/**
 * 配置导出的整套流程。
 *
 * ============================================================
 * 为什么要单独封一层
 * ============================================================
 * 三种落点里有一种（手动选位置）要弹系统界面、要等异步回调。
 * 把它平铺在 `ConfigListScreen` 里的话，回调回来时必须还查得到
 * "用户当初点了哪个配置" —— 那会变成一堆零散的 remember 状态。
 * 这里收成一个字段 [pending]，回调只认它。
 *
 * ⚠️ **`rememberLauncherForActivityResult` 必须在组合期无条件调用**，
 * 不能放进 `if` 里 —— 这是 Compose 的硬性要求（注册表的 key 会错位）。
 * 所以 launcher 在本函数顶部声明，不放在任何分支里。
 *
 * ============================================================
 * ⚠️ 曾经还有"自定义导出目录"，已删除
 * ============================================================
 * 那套是 SAF 目录树（`OpenDocumentTree` + 持久化授权 + `createDocument`），
 * 但实测在多种设备上都会失败（"无法在所选目录里创建文件"），
 * 失败原因还难以定位。用户实际只需要导到 Download，所以整条路删掉了。
 *
 * 删掉之后这个文件的复杂度显著下降：不用管"授权被系统回收"、
 * 不用管"重选目录后再接着导出"、也不用管那种情况下递归弹选择器的重入。
 *
 * @param onMessage 一次性的结果提示（由调用方用 Toast 显示）
 */
/**
 * 一次导出的目标（配置 **或** 配置组合）。
 *
 * ⚠️ 为什么要这个抽象:导出有**三种方式**（手动选位置 / 静默存下载 / 分享），
 * 而配置与配置组合的差别只有"往 Uri 里写什么"。
 * 把差别收成 [write] 一个函数，三种方式就只需要维护一份。
 *
 * ⚠️ [fileName] 必须**带扩展名**（`.sthkey` / `.sthgroup`）:
 * 它同时被用作 SAF 的默认文件名和下载目录里的落盘名。
 */
class PendingExport(
    /** 给用户看的名字（提示语里用） */
    val displayName: String,
    /** 落盘文件名，**含扩展名** */
    val fileName: String,
    /** 把包写进目标 Uri；返回是否成功 */
    val write: (android.net.Uri) -> Boolean,
)


@Composable
fun rememberExportFlow(onMessage: (String) -> Unit): ExportFlow {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { AppPrefs.get(context) }

    /** 当前正在导出的配置；系统回调回来时靠它知道要写什么 */
    var pending by remember { mutableStateOf<PendingExport?>(null) }

    /**
     * 用户在这次导出里勾了"设为默认"。导出**成功之后**才写进偏好 ——
     * 失败也记住的话，用户下次点导出会直接按一个刚失败过的方式走，
     * 而且再也没机会看到那个窗口。
     */
    var rememberChoice by remember { mutableStateOf(false) }

    /**
     * 等存储权限回来时要继续导出的配置（**仅 Android 9 及以下会用到**）。
     *
     * ⚠️ 单独存一份、而不是复用 [pending]：那个是给"手动选位置"用的，
     * 两者会在"权限被拒 → 退回手动"时**同时存在**，混用会丢配置。
     */
    var pendingDownload by remember { mutableStateOf<PendingExport?>(null) }
    var pendingDownloadAsDefault by remember { mutableStateOf(false) }

    /*
     * 手动导出（SAF 建文档）。
     *
     * MIME 传通配符而不是 application/zip：sthkey 与 application/zip
     * "后缀不匹配"，部分文件管理器会据此把 .sthkey 强行改成 .zip / .bin。
     * 详见 `ConfigPackageManager` 与 `ConfigExporters` 里的说明。
     */
    val manualLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val target = pending
        pending = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult

        scope.launch {
            val ok = withContext(Dispatchers.IO) { target.write(uri) }
            if (ok) {
                commitDefaultIfAsked(prefs, ExportMethod.MANUAL, rememberChoice)
                rememberChoice = false
                onMessage("已导出「${target.displayName}」")
            } else {
                onMessage("导出失败，请重试（若目录里出现了 0 字节的同名文件，可以删掉它再试）")
            }
        }
    }

    /*
     * Android 9（API 28）及以下：写公共下载目录需要 `WRITE_EXTERNAL_STORAGE`。
     *
     * ⚠️ 29+ 完全不需要 —— 清单里那条权限带了 `maxSdkVersion="28"`，
     * 所以这里用 [needsStoragePermission] 提前判掉，不会在 29+ 上白弹一次窗口。
     *
     * ⚠️ 必须声明在 [manualLauncher] **之后**：被拒时要退回手动导出，
     * 用到 `manualLauncher`。Kotlin 的局部变量不能前向引用。
     */
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val target = pendingDownload
        pendingDownload = null
        if (target == null) return@rememberLauncherForActivityResult

        if (granted) {
            val asDefault = pendingDownloadAsDefault
            scope.launch {
                exportToDownloads(context, prefs, target, asDefault, onMessage) {
                    rememberChoice = false
                }
            }
        } else {
            /*
             * ⚠️ 被拒**不能就到此为止** —— 用户点了"导出"却什么都没发生，
             * 那是最糟的一种体验。直接退回到"手动选择保存位置"（SAF 免权限），
             * 配置照样能导出去。
             */
            AppLog.i("Export", "存储权限被拒，退回手动选择保存位置")
            rememberChoice = pendingDownloadAsDefault
            pending = target
            manualLauncher.launch(target.fileName)
            onMessage("未授予存储权限，已改为手动选择保存位置")
        }
    }

    /*
     * 三种导出方式的**唯一分派点**。
     *
     * ⚠️ 配置与配置组合共用它:两者的区别只在 [PendingExport.write] 里"写什么进去"，
     * 分派逻辑必须只有一份，否则迟早出现"组合没有分享方式""组合导出到下载会崩"这类分叉。
     */
    val startInternal: (PendingExport, ExportMethod, Boolean) -> Unit =
        { target, method, asDefault ->
            rememberChoice = asDefault
            when (method) {
                ExportMethod.MANUAL, ExportMethod.ASK -> {
                    /*
                     * ASK 不该走到这里 —— 调用方看到 ASK 会先弹选择窗口。
                     * 真走到了就退回手动导出，至少不会什么都不发生。
                     *
                     * ⚠️ MIME 用通配符（见 `manualLauncher` 的说明）：
                     * 给具体类型会让系统按 MIME 补后缀（`.sthgroup` → `.sthgroup.bin`）。
                     */
                    pending = target
                    manualLauncher.launch(target.fileName)
                }

                ExportMethod.DOWNLOAD -> {
                    /*
                     * ⚠️ Android 9 及以下先要存储权限（29+ 这一步直接跳过）。
                     * 拿到权限后在 launcher 的回调里继续导出。
                     */
                    if (needsStoragePermission(context)) {
                        pendingDownload = target
                        pendingDownloadAsDefault = asDefault
                        storagePermissionLauncher.launch(
                            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        )
                    } else {
                        scope.launch {
                            exportToDownloads(context, prefs, target, asDefault, onMessage) {
                                rememberChoice = false
                            }
                        }
                    }
                }

                ExportMethod.SHARE -> scope.launch {
                    sharePackage(context, target, prefs, asDefault, onMessage) {
                        rememberChoice = false
                    }
                }
            }
        }

    return remember(manualLauncher, storagePermissionLauncher) {
        ExportFlow(
            start = { target, method, asDefault ->
                startInternal(target, method, asDefault)
            },
            startGroup = { group, configs, method, asDefault ->
                startInternal(
                    PendingExport(
                        displayName = group.name,
                        fileName = group.name + GroupPackageCodec.EXTENSION,
                        write = { uri ->
                            GroupPackageManager.export(
                                context = context,
                                group = group,
                                configs = configs,
                                target = uri,
                            ) is GroupPackageManager.ExportOutcome.Ok
                        },
                    ),
                    method,
                    asDefault,
                )
            },
        )
    }
}

/**
 * 导出流程的对外接口。
 *
 * 做成一个只有函数字段的类而不是回调集合：调用方只需要"开始导出"。
 */
class ExportFlow(
    /** @param asDefault 弹窗里勾了"设为默认导出方式" */
    val start: (target: PendingExport, method: ExportMethod, asDefault: Boolean) -> Unit,

    /**
     * 导出一个**配置组合**。
     *
     * ⚠️ 与 [start] 走同一套方式（手动选择位置 / 下载目录 / 分享），
     * 区别只在"写进 Uri 的是什么"。
     */
    val startGroup: (group: ConfigGroup, configs: List<KeyStrokesConfig>, method: ExportMethod, asDefault: Boolean) -> Unit,
)

/* ============================================================
 * 具体动作（都是 suspend，因为要写磁盘）
 * ============================================================ */

/**
 * 静默存到 `Download/sthkeyconfigs/`（MediaStore，**零权限**）。
 */
private suspend fun exportToDownloads(
    context: Context,
    prefs: AppPrefs,
    target: PendingExport,
    asDefault: Boolean,
    onMessage: (String) -> Unit,
    clearRemember: () -> Unit,
) {
    val fileName = target.fileName

    val result = withContext(Dispatchers.IO) {
        ConfigExporters.exportToDownloads(context, target.write, fileName, ConfigExporters.SUBDIR)
    }

    clearRemember()

    when (result) {
        is ConfigExporters.ExportResult.Ok -> {
            commitDefaultIfAsked(prefs, ExportMethod.DOWNLOAD, asDefault)
            /*
             * 报**实际落盘的文件名**而不是配置名：MediaStore 遇到重名
             * 会加 " (1)"，用户去文件管理器里要按那个名字找。
             */
            onMessage("已导出「${result.displayName}」")
        }

        is ConfigExporters.ExportResult.Failed -> {
            onMessage("导出失败：${result.reason}")
        }
    }
}

/** 生成分享 Intent 并交给系统面板 */
private suspend fun sharePackage(
    context: Context,
    target: PendingExport,
    prefs: AppPrefs,
    asDefault: Boolean,
    onMessage: (String) -> Unit,
    clearRemember: () -> Unit,
) {
    val fileName = target.fileName

    val intent = withContext(Dispatchers.IO) {
        ConfigExporters.buildShareIntent(context, target.displayName, fileName, target.write)
    }

    clearRemember()

    if (intent == null) {
        onMessage("准备分享失败，请重试")
        return
    }

    try {
        /*
         * 用 `createChooser` 而不是直接 `startActivity`：
         * 系统选择器更清楚，而且各家 ROM 对"没有应用能处理"的处理不一致，
         * chooser 能给出统一的"没有应用可分享"界面。
         */
        context.startActivity(Intent.createChooser(intent, "分享配置"))
        commitDefaultIfAsked(prefs, ExportMethod.SHARE, asDefault)
    } catch (e: ActivityNotFoundException) {
        AppLog.w(TAG, "没有应用能接收分享：${e.message}")
        onMessage("没有可以接收分享的应用")
    } catch (e: Exception) {
        AppLog.e(TAG, "发起分享失败", e)
        onMessage("分享失败：${e.message ?: e.javaClass.simpleName}")
    }
}

/**
 * 用户勾了"设为默认"时才写进偏好。
 *
 * ⚠️ 只在**成功之后**调用。失败也记住的话，用户下次点导出会直接按一个
 * 刚失败过的方式走，而且再也没机会看到那个窗口 —— 那是最难受的形态：
 * 点了没反应，还不知道去哪改。
 */
private fun commitDefaultIfAsked(prefs: AppPrefs, method: ExportMethod, asked: Boolean) {
    if (!asked) return
    prefs.exportMethod = method
    AppLog.i(TAG, "默认导出方式已设为：${method.label}")
}

private const val TAG = "Export"

/**
 * 这台机器导出到下载目录前，是否需要先申请存储权限。
 *
 * ============================================================
 * ⚠️ 只有 Android 9（API 28）及以下才需要
 * ============================================================
 * Android 10 起写 `Download/` 走 MediaStore，**不要任何权限**。
 * 清单里那条 `WRITE_EXTERNAL_STORAGE` 带了 `maxSdkVersion="28"`，
 * 所以在 29+ 上系统根本不会把它列出来 —— 这里也必须同步判掉，
 * 否则会在不该弹窗的机器上弹一个"系统直接拒绝"的空窗。
 *
 * ⚠️ 已授权时返回 false（直接导出），没授权才返回 true（去申请）。
 */
private fun needsStoragePermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) return false
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
    ) != PackageManager.PERMISSION_GRANTED
}

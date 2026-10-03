package com.something.sthkey.ui.feature.config

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.something.sthkey.core.log.AppLog
import com.something.sthkey.core.prefs.AppPrefs
import com.something.sthkey.data.config.ConfigExporters
import com.something.sthkey.data.config.ConfigPackageCodec
import com.something.sthkey.data.config.ConfigPackageManager
import com.something.sthkey.data.config.ExportMethod
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
@Composable
fun rememberExportFlow(onMessage: (String) -> Unit): ExportFlow {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { AppPrefs.get(context) }

    /** 当前正在导出的配置；系统回调回来时靠它知道要写什么 */
    var pending by remember { mutableStateOf<KeyStrokesConfig?>(null) }

    /**
     * 用户在这次导出里勾了"设为默认"。导出**成功之后**才写进偏好 ——
     * 失败也记住的话，用户下次点导出会直接按一个刚失败过的方式走，
     * 而且再也没机会看到那个窗口。
     */
    var rememberChoice by remember { mutableStateOf(false) }

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
            val ok = withContext(Dispatchers.IO) {
                ConfigPackageManager.export(
                    context = context,
                    config = target,
                    target = uri,
                    requestedName = target.name + ConfigPackageCodec.EXTENSION,
                )
            }
            if (ok) {
                commitDefaultIfAsked(prefs, ExportMethod.MANUAL, rememberChoice)
                rememberChoice = false
                onMessage("已导出「${target.name}」")
            } else {
                onMessage("导出失败，请重试（若目录里出现了 0 字节的同名文件，可以删掉它再试）")
            }
        }
    }

    return remember(manualLauncher) {
        ExportFlow(
            start = { config, method, asDefault ->
                rememberChoice = asDefault
                when (method) {
                    ExportMethod.MANUAL, ExportMethod.ASK -> {
                        /*
                         * ASK 不该走到这里 —— 调用方看到 ASK 会先弹选择窗口。
                         * 真走到了就退回手动导出，至少不会什么都不发生。
                         */
                        pending = config
                        manualLauncher.launch(config.name + ConfigPackageCodec.EXTENSION)
                    }

                    ExportMethod.DOWNLOAD -> scope.launch {
                        exportToDownloads(context, prefs, config, asDefault, onMessage) {
                            rememberChoice = false
                        }
                    }

                    ExportMethod.SHARE -> scope.launch {
                        shareConfig(context, config, prefs, asDefault, onMessage) {
                            rememberChoice = false
                        }
                    }
                }
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
    val start: (config: KeyStrokesConfig, method: ExportMethod, asDefault: Boolean) -> Unit,
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
    config: KeyStrokesConfig,
    asDefault: Boolean,
    onMessage: (String) -> Unit,
    clearRemember: () -> Unit,
) {
    val fileName = config.name + ConfigPackageCodec.EXTENSION

    val result = withContext(Dispatchers.IO) {
        ConfigExporters.exportToDownloads(context, config, fileName)
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
private suspend fun shareConfig(
    context: Context,
    config: KeyStrokesConfig,
    prefs: AppPrefs,
    asDefault: Boolean,
    onMessage: (String) -> Unit,
    clearRemember: () -> Unit,
) {
    val fileName = config.name + ConfigPackageCodec.EXTENSION

    val intent = withContext(Dispatchers.IO) {
        ConfigExporters.buildShareIntent(context, config, fileName)
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

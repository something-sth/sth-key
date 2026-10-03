package com.something.sthkey.ui.feature.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.something.sthkey.core.log.AppLog
import com.something.sthkey.data.config.ConfigPackageManager
import com.something.sthkey.data.config.IncomingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「从外部打开/分享进来的配置包」的确认与导入流程。
 *
 * ============================================================
 * 为什么不直接导入
 * ============================================================
 * `.sthkey` 本质是个 zip，而我们是靠 **intent-filter 里那几个"大路货"
 * MIME**（`application/octet-stream` / `application/zip`）把它接住的 ——
 * 也就是说**任何**这类文件都可能被送到这里，包括根本不是我们格式的。
 *
 * 收下之后当然会验 manifest，但"点一下就凭空多出一份配置"这件事
 * 本身就该让用户知情。所以先确认，再导入。
 *
 * ============================================================
 * 为什么不提供"覆盖同名配置"
 * ============================================================
 * [ConfigPackageManager.import] 遇到重名会**自动改名**并在结果里
 * 标记 `renamed`（于是你得到"配置 (2)"）。这比覆盖安全得多：
 * 覆盖是**不可逆**的，而用户点"导入"时想的是"把这份加进来"，
 * 不是"把我现在那份毁掉"。
 *
 * 想合并的话，导入完自己去复制粘贴组件更可控。
 *
 * @param request 待处理的请求；null 表示没有（不显示任何东西）
 * @param onImported 导入成功后的回调（刷新列表、提示等）
 * @param onDismiss 用户取消或处理完毕
 */
@Composable
fun IncomingConfigDialog(
    request: IncomingConfig.Request?,
    onImported: (ConfigPackageManager.ImportResult) -> Unit,
    onDismiss: () -> Unit,
) {
    if (request == null) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    /*
     * 阶段：确认 → 导入中 → 结果。
     *
     * 用**状态机**而不是几个布尔：几个布尔会出现
     * "既在导入中又显示结果"这种自相矛盾的中间态。
     */
    var phase by remember(request) {
        mutableStateOf<Phase>(Phase.Confirm)
    }

    AlertDialog(
        /*
         * 导入进行中**不允许点外面关掉**。
         *
         * 关掉对话框不会取消已经开始的写入（协程还在跑），
         * 于是用户以为"取消了"，实际上配置已经进去了 ——
         * 这比"关不掉"难受得多。
         */
        onDismissRequest = { if (phase != Phase.Working) onDismiss() },
        title = {
            /*
             * 先取到局部变量再判断。
             *
             * ⚠️ `phase` 是 `by remember` 的**委托属性**，Kotlin 不能对它
             * 做 smart cast（编译器无法保证两次读取之间没被改过），
             * 直接写 `is Phase.Done -> phase.result` 会报
             * "Smart cast to 'Phase.Done' is impossible"。
             */
            val current = phase
            Text(
                when (current) {
                    Phase.Confirm -> if (request.fromShare) "导入分享的配置" else "导入配置"
                    Phase.Working -> "正在导入"

                    /*
                     * 与报告内容的口径一致：有差异就点出来。
                     * 外部导入的多半是别人分享的包，版本常常对不齐 ——
                     * 标题上先提示一句，用户才会去展开详情看。
                     */
                    is Phase.Done ->
                        if (current.result.hasDifferences) "导入完成（有差异）" else "导入成功"

                    is Phase.Failed -> "导入失败"
                },
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                when (val current = phase) {
                    Phase.Confirm -> {
                        Text(
                            text = if (request.fromShare) {
                                "要把分享过来的这个文件作为新配置导入吗？"
                            } else {
                                "要把打开的这个文件作为新配置导入吗？"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            /*
                             * 显示文件名而不是完整 Uri：Uri 又长又是
                             * 一串百分号编码，对用户没有任何信息量。
                             */
                            text = request.uri.lastPathSegment ?: request.uri.toString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Phase.Working -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CircularProgressIndicator()
                        }
                    }

                    is Phase.Done -> {
                        /*
                         * ⚠️ 用**与配置页导入完全相同**的报告内容。
                         *
                         * 早先这里只写了一句"已导入为「X」"（顶多再加一句
                         * 重名/缺字体的提示），于是同一个包从两个入口导入
                         * 看到的**信息量不一样** —— 外部导入看不到字段差异、
                         * 版本号、字体与模型的去向。
                         *
                         * 那是设计缺陷而不是少写几行：用户就没法区分
                         * "这次导入很干净"和"这次导入悄悄丢了设置"。
                         * 而"来自其它版本的包"恰恰是外部导入最常见的场景
                         * （别人分享给你的包，版本大概率和你不一样）。
                         *
                         * 现在只有 [ImportReportContent] 一个实现，两个入口共用。
                         */
                        ImportReportContent(current.result)
                    }

                    is Phase.Failed -> {
                        Text(
                            text = current.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            /* 同样先取局部变量：见上面 title 里关于 smart cast 的说明 */
            val current = phase
            when (current) {
                Phase.Confirm -> TextButton(
                    onClick = {
                        phase = Phase.Working
                        scope.launch {
                            val outcome = withContext(Dispatchers.IO) {
                                ConfigPackageManager.import(context, request.uri)
                            }
                            phase = outcome.fold(
                                onSuccess = { Phase.Done(it) },
                                onFailure = { Phase.Failed(describe(it)) },
                            )
                        }
                    },
                ) {
                    Text("导入")
                }

                Phase.Working -> Unit

                is Phase.Done -> TextButton(
                    onClick = {
                        onImported(current.result)
                        onDismiss()
                    },
                ) {
                    Text("好")
                }

                is Phase.Failed -> TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
        dismissButton = {
            if (phase == Phase.Confirm) {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

/** 对话框的几个阶段 */
private sealed interface Phase {
    data object Confirm : Phase
    data object Working : Phase
    data class Done(val result: ConfigPackageManager.ImportResult) : Phase
    data class Failed(val message: String) : Phase
}

/**
 * 把导入失败的异常翻成人话。
 *
 * 与配置页里那段是同一套措辞 —— 同一个失败原因在两个入口
 * 给出不同说法，用户会以为是两个不同的问题。
 */
private fun describe(error: Throwable): String =
    when (val reason = (error as? ConfigPackageManager.ImportException)?.error) {
        ConfigPackageManager.ImportError.NotAPackage ->
            "这不是 sth key 的配置包（manifest 格式标识不匹配）"

        ConfigPackageManager.ImportError.MissingManifest ->
            "配置包缺少 manifest.json，文件可能不完整"

        ConfigPackageManager.ImportError.MissingParams ->
            "配置包缺少 params.json，文件可能不完整"

        is ConfigPackageManager.ImportError.Broken -> reason.message
        null -> error.message ?: "导入失败"
    }

package com.something.sthkey.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.something.sthkey.domain.update.UpdateInfo

/**
 * 「发现新版本」弹窗。
 *
 * ============================================================
 * 视觉约定
 * ============================================================
 * - 标题 `发现新版本 v2.8.8`：**版本号（含 v）用主题色**，与更新公告里的写法一致；
 * - 正文是 Release 说明原文（纯文本、保留换行），**超出高度可滚动**；
 * - 不显示"当前版本 → 新版本"这种多余的一行（版本号标题里已经有了）；
 * - 按钮：`以后再说` 在**左下角**、`前往下载` 在**右下角**。
 *
 * ⚠️ 没有用 `AlertDialog`：Material3 的按钮区固定在右下角，
 * 两个按钮会挤在一起；这里用自绘对话框，按钮分到两端。
 */
@Composable
fun UpdateAvailableDialog(
    info: UpdateInfo,
    onDownload: () -> Unit,
    onLater: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = true),
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
            modifier = Modifier.widthIn(min = 280.dp, max = 460.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
                Text(
                    text = buildAnnotatedString {
                        append("发现新版本 ")
                        withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) {
                            /* 版本号连同前缀 v 一起用主题色，和更新公告的写法统一 */
                            append("v${info.versionName}")
                        }
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                if (info.notes.isNotBlank()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    /*
                     * ⚠️ 必须能滚：Release 说明可能很长（用户公告就是多段带空行的），
                     * 不给滚动的话弹窗会超出屏幕、按钮被挤出去。
                     * 360dp 大约是"标题 + 4~5 行说明 + 按钮"能完整显示的高度。
                     */
                    Text(
                        text = info.notes.trim(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = onLater) { Text("以后再说") }
                    TextButton(onClick = onDownload) { Text("前往下载") }
                }
            }
        }
    }
}

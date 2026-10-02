package com.something.sthkey.ui.feature.announcement

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.something.sthkey.data.permission.PermissionHelper
import com.something.sthkey.ui.AppLinks
import kotlinx.coroutines.delay

/** 阅读倒计时：这段时间内"确认"不可点 */
private const val READ_SECONDS = 3

/**
 * 更新公告弹窗（**可翻页看历史公告**）。
 *
 * ============================================================
 * 行为约定
 * ============================================================
 * 1. **只弹一次**：确认后把当前版本号写进设置，同一版本不再打扰
 *    （判断逻辑在 MainViewModel，这里只负责展示）；
 * 2. **首次阅读不能随手关掉**：点弹窗外部或按返回键都不会关闭
 *    （`onDismissRequest` 传空实现），必须点"确认"——
 *    否则一个"必读公告"点一下空白就没了，等于没发；
 * 3. **三秒后才能确认**：按钮在倒计时期间置灰并显示剩余秒数；
 * 4. **可以往前翻历史**：默认显示最新一版，用"上一版 / 下一版"翻。
 *    历史是给"忘了上版改了什么"的人查的，所以翻页**不受倒计时限制**。
 *
 * 从「关于」页进来时是浏览模式：可以随时关掉，也不需要倒计时。
 *
 * 链接不用富文本注解，而是在正文末尾单列成可点的行：
 * 弹窗里手指点小字上的链接很难点准，单独一行整行可点要可靠得多，
 * 也顺带避开了已废弃的 ClickableText。
 *
 * @param initialVersion 一进来显示哪一版；通常传最新版
 * @param requireReading 是否要求读满倒计时才能确认（首次阅读为 true）
 * @param onConfirm 首次阅读时点"确认"
 * @param onDismiss 浏览模式下关闭；为 null 表示不可关闭（首次阅读）
 */
@Composable
fun AnnouncementDialog(
    initialVersion: String,
    requireReading: Boolean,
    onConfirm: () -> Unit,
    onDismiss: (() -> Unit)?,
) {
    val releases = AppAnnouncement.releases
    var index by remember {
        mutableStateOf(
            releases.indexOfFirst { it.version == initialVersion }.coerceAtLeast(0),
        )
    }
    val release = releases[index]

    var remaining by remember { mutableStateOf(if (requireReading) READ_SECONDS else 0) }

    LaunchedEffect(Unit) {
        while (remaining > 0) {
            delay(1000L)
            remaining--
        }
    }

    AlertDialog(
        // null = 点外部 / 按返回键都不关闭，必须走"确认"
        onDismissRequest = { onDismiss?.invoke() },

        title = { Text("${AppAnnouncement.APP_NAME} v${release.version} 更新公告") },

        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // 内容可能比屏幕高（小屏 + 大字体），给个上限后内部滚动，
                    // 不要让它把按钮挤出屏幕
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PagerRow(
                    release = release,
                    isLatest = index == 0,
                    hasOlder = index < releases.lastIndex,
                    hasNewer = index > 0,
                    onOlder = { index++ },
                    onNewer = { index-- },
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Text(
                    text = release.intro,
                    style = MaterialTheme.typography.bodyMedium,
                )

                release.sections.forEach { section ->
                    Text(
                        text = section.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )

                    section.lines.forEach { line ->
                        Text(
                            text = "· $line",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )

                LinkLine(
                    label = "GitHub 开源仓库",
                    url = AppLinks.GITHUB_REPO,
                )
                LinkLine(
                    label = "QQ 交流反馈群：${AppLinks.QQ_GROUP_NUMBER}",
                    url = AppLinks.QQ_GROUP,
                )

                Text(
                    text = AppAnnouncement.footer,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },

        confirmButton = {
            if (onDismiss == null) {
                // 首次阅读：读满倒计时才能点
                TextButton(
                    onClick = onConfirm,
                    enabled = remaining == 0,
                ) {
                    Text(
                        if (remaining > 0) {
                            "确认（$remaining）"
                        } else {
                            "确认"
                        },
                    )
                }
            } else {
                // 浏览模式（从「关于」页进来）：随时可关
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}

/**
 * 版本翻页行：`‹ 上一版` + `v2.1.0 · 2026-09-26 · 最新` + `下一版 ›`。
 *
 * 为什么用左右翻页而不是版本列表：公告条数少（一版一条），但每条很长 ——
 * 列表要先选再看，翻页则是"直接往前后读"，更符合"查历史"这个用途。
 */
@Composable
private fun PagerRow(
    release: AppAnnouncement.Release,
    isLatest: Boolean,
    hasOlder: Boolean,
    hasNewer: Boolean,
    onOlder: () -> Unit,
    onNewer: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onOlder, enabled = hasOlder) {
            // 说的是"更早的那一版"，所以标签就叫上一版
            Text("‹ 上一版", style = MaterialTheme.typography.labelLarge)
        }

        Text(
            text = buildString {
                append("v${release.version} · ${release.date}")
                if (isLatest) append(" · 最新")
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )

        TextButton(onClick = onNewer, enabled = hasNewer) {
            Text("下一版 ›", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** 一行可点击的外部链接 */
@Composable
private fun LinkLine(
    label: String,
    url: String,
) {
    val context = LocalContext.current

    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                // 没有浏览器 / 没有 QQ 时不能静默失败，否则用户以为点坏了
                if (!PermissionHelper.openUrl(context, url)) {
                    Toast.makeText(
                        context,
                        "没有可打开链接的应用，请手动搜索「$label」",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
            .padding(vertical = 6.dp),
    )
}

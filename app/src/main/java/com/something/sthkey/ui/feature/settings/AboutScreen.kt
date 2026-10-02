package com.something.sthkey.ui.feature.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.something.sthkey.BuildConfig
import com.something.sthkey.data.permission.PermissionHelper
import com.something.sthkey.ui.AppLinks
import com.something.sthkey.ui.component.CardDivider
import com.something.sthkey.ui.component.ScrollableScreen
import com.something.sthkey.ui.component.SectionHeader
import com.something.sthkey.ui.component.SectionHint
import com.something.sthkey.ui.component.SettingItem
import com.something.sthkey.ui.component.SettingsCard
import com.something.sthkey.ui.feature.announcement.AnnouncementDialog
import com.something.sthkey.ui.feature.announcement.AppAnnouncement

/**
 * 关于页。
 *
 * 内容沿用旧项目的"关于"：GitHub 仓库、MIT 许可证、QQ 交流群、开发者信息。
 * 链接统一走 [PermissionHelper.openUrl]，内部会捕获"没有浏览器"这类异常，
 * 不会出现点了没反应或直接崩溃。
 */
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    /** 是否打开更新公告（**浏览模式**：可随时关闭，也不需要读满倒计时） */
    var showAnnouncement by remember { mutableStateOf(false) }

    ScrollableScreen(
        title = "关于",
        subtitle = "版本 ${BuildConfig.VERSION_NAME}",
        onBack = onBack,
        modifier = modifier,
    ) {
        /*
         * ============================================================
         * 应用介绍
         * ============================================================
         */

        item {
            SettingsCard {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "sth key",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    /*
                     * 构建标记。
                     *
                     * 显示的格式固定为 `月日-时分秒`（例如 0926-143207），
                     * 显示成 `构建 0926-143207`，用来回答"我装的是哪一次构建"。
                     * 排查问题时先看它 —— "手机上还是旧包"是排查里最常见的假象之一。
                     */
                    Text(
                        text = "构建 ${BuildConfig.BUILD_TAG}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "通过 root 或 Shizuku 读取外接键盘的原始输入事件，" +
                            "并在悬浮窗中实时显示按键状态的 Android 应用。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (BuildConfig.DEBUG) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "调试构建（debug）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        /*
         * ============================================================
         * 链接
         * ============================================================
         */

        item { SectionHeader("项目链接") }

        item {
            SettingsCard {
                SettingItem(
                    title = "更新公告",
                    subtitle = "查看各版本的更新内容（可往前翻历史）",
                    leadingIcon = Icons.Default.Campaign,
                    onClick = { showAnnouncement = true },
                )

                CardDivider()

                SettingItem(
                    title = "GitHub 开源仓库",
                    subtitle = "something-sth/sth-key",
                    onClick = { PermissionHelper.openUrl(context, AppLinks.GITHUB_REPO) },
                    trailing = { Chevron() },
                )

                CardDivider()

                SettingItem(
                    title = "MIT License",
                    subtitle = "查看项目开源许可证",
                    onClick = { PermissionHelper.openUrl(context, AppLinks.LICENSE) },
                    trailing = { Chevron() },
                )

                CardDivider()

                SettingItem(
                    title = "QQ 交流反馈群",
                    subtitle = "群号 ${AppLinks.QQ_GROUP_NUMBER} · 密码 ${AppLinks.QQ_GROUP_PASSWORD}",
                    onClick = {
                        if (!PermissionHelper.openUrl(context, AppLinks.QQ_GROUP)) {
                            Toast.makeText(
                                context,
                                "没有可打开链接的应用，请手动搜索群号 ${AppLinks.QQ_GROUP_NUMBER}",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    },
                    trailing = { Chevron() },
                )
            }
        }

        item {
            SectionHint(
                text = "点击 QQ 群链接会尝试直接唤起 QQ；若没有反应，" +
                    "可在 QQ 中搜索群号 ${AppLinks.QQ_GROUP_NUMBER} 加入。",
            )
        }

        /*
         * ============================================================
         * 开发者
         * ============================================================
         */

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "开发者：${AppLinks.DEVELOPER}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "多多支持谢谢喵~",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    /*
     * ============================================================
     * 更新公告（浏览模式）
     * ============================================================
     * 从「关于」页进来是回看历史，不是"必读公告"：
     * 所以随时可以关掉，也不需要读满倒计时。
     */
    if (showAnnouncement) {
        AnnouncementDialog(
            initialVersion = AppAnnouncement.latest.version,
            requireReading = false,
            onConfirm = { showAnnouncement = false },
            onDismiss = { showAnnouncement = false },
        )
    }
}

/** 列表项右侧的指示箭头 */
@Composable
private fun Chevron() {
    Text(
        text = "›",
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

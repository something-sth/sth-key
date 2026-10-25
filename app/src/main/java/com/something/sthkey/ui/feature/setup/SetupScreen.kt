package com.something.sthkey.ui.feature.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.something.sthkey.capture.accessibility.AccessibilityGrant
import com.something.sthkey.capture.accessibility.SthKeyAccessibilityService
import com.something.sthkey.data.permission.PermissionHelper
import com.something.sthkey.domain.capture.RootProbe
import com.something.sthkey.ui.component.findActivity
import com.something.sthkey.ui.component.ScreenScaffold
import com.something.sthkey.ui.component.SectionHint
import com.something.sthkey.ui.component.SettingsCard
import com.something.sthkey.ui.MainViewModel


/**
 * 首次启动的权限引导。
 *
 * 设计原则：**文案尽量短**。每张卡片只有"要什么、一句话为什么、状态、按钮"，
 * 详细排查提示只在真的缺东西时才出现 —— 这是用户第一次看到的页面，
 * 不该先读一大段文字。
 *
 * 行为上的三点优化（相对旧项目）：
 * 1. 权限齐全时本页**不会出现**（由 MainActivity 判断）；
 * 2. 从系统设置页返回后自动重新检查，无需手动刷新；
 * 3. 允许"暂不授权"进入（带二次确认），不把人卡在这一页。
 */
@Composable
fun SetupScreen(
    viewModel: MainViewModel,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    /*
     * ============================================================
     * ⚠️⚠️ 这里**不再检查悬浮窗权限**（那一步已经整个去掉了）
     * ============================================================
     * 用户拍板"走 Axon 那条路":所有悬浮窗改用
     * `TYPE_ACCESSIBILITY_OVERLAY`，于是**不需要 SYSTEM_ALERT_WINDOW**。
     *
     * ⚠️ 取而代之的是**无障碍服务**:
     *
     * | 以前 | 现在 |
     * |---|---|
     * | 悬浮窗权限（用户去系统里点一下） | 无障碍服务（用户去系统里点一下） |
     *
     * ⭐ 对用户来说**步骤数一样**，但我们少了一个权限、少了一张卡 ——
     * 而且无障碍这一项**本来就要有**（纯无障碍通道也要它）。
     */
    var accessibilityOn by remember { mutableStateOf(SthKeyAccessibilityService.isConnected()) }
    var batteryIgnored by remember {
        mutableStateOf(PermissionHelper.isIgnoringBatteryOptimizations(context))
    }
    var showSkipDialog by remember { mutableStateOf(false) }

    /** 从系统页面返回时重新检查 */
    fun recheck() {
        accessibilityOn = SthKeyAccessibilityService.isConnected()
        batteryIgnored = PermissionHelper.isIgnoringBatteryOptimizations(context)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                recheck()
                viewModel.refreshCapabilities(force = true)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshCapabilities()
    }

    val rootAvailable = viewModel.capabilities.rootAvailable
    val shizukuGranted = viewModel.capabilities.shizukuReady

    /*
     * ============================================================
     * ⚠️ 有 root 或 Shizuku，就**能自动开无障碍**
     * ============================================================
     * 用户的原话:
     *
     * > 有 shizuku / root / 无障碍三者其一应该**都可以进入软件**，虽然悬浮窗是
     * > 无障碍悬浮窗，但是 shizuku 和 root 不是都可以使用 shell 授权无障碍吗，
     * > 所以**不需要让用户已经授权 shizuku/root 的时候还单独开启无障碍**
     *
     * ⚠️ 所以这里分成两种"能用":
     *
     * | 情况 | 判据 | 用户要做什么 |
     * |---|---|---|
     * | 有 shell | [canGrantByShell] | ⚠️ **什么都不用做** —— 我们启动时自动开无障碍 |
     * | 只有无障碍 | `accessibilityOn` | 用户自己点一下（没有别的路） |
     */
    val canGrantByShell = rootAvailable || shizukuGranted

    /** 三条通道任一可用（用户说的"三者其一"） */
    val captureReady = canGrantByShell || accessibilityOn

    /**
     * ⚠️ 「全部就绪」= **能用**，而不是"每样都授权了"。
     *
     * ⚠️ 硬要求无障碍会让"只给了 Shizuku 的用户"卡在引导页 ——
     * 但他其实**完全能用**（我们替他开无障碍）。那正是用户报的问题。
     *
     * ⚠️ 唯一的例外是"什么特权都没有、无障碍也没开"时才拦 ——
     * 那种情况确实一个窗口都建不出来。
     */
    val allReady = captureReady

    /*
     * ⚠️⚠️ 这里**曾经有一张"上次配好的权限没了"的红卡，已经删掉**
     * ============================================================
     * 用户的原话:
     *
     * > 而且你**也不需要加一个"上次配好的权限没了"的卡片，很丑**，
     * > **别做这些无用的**
     *
     * ⚠️ 他说得对，而且那张卡不只是"丑"—— 它当时还是**错的结论**:
     * 有 root 的用户被误判成"无障碍没了"，于是看到一张根本不成立的红卡。
     *
     * ⚠️ 现在校验只在**真的什么都用不了**时才把人送到这一页
     * （见 `MainViewModel.evaluateStartupIssue`），
     * 而这一页本身的卡片已经清楚标出哪一项没完成 ——
     * 再加一层横幅是**重复**，而且还更难看。
     */

    ScreenScaffold(
        title = "准备一下",
        subtitle = if (allReady) "全部就绪" else "完成后即可使用按键显示",
        modifier = modifier,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    /*
                     * ⚠️⚠️ 这张卡**取代了原来那张「悬浮窗权限」**
                     * ============================================================
                     * 用户拍板走 Axon 那条路之后:
                     *
                     * | 以前 | 现在 |
                     * |---|---|
                     * | 悬浮窗权限（只为画窗口） | **无障碍服务**（画窗口 + 读键盘，一举两得） |
                     *
                     * ⭐ 所以这里**不是加了一张卡，是换了一张** ——
                     * 用户的总步骤数没有变多。
                     *
                     * ⚠️ 而且这张卡是**必需**的:换成无障碍窗口之后，
                     * 没有它就**一个窗口都建不出来**（不只是"读不到按键"）。
                     */
                    SetupCard(
                        icon = Icons.Default.Accessibility,
                        title = "无障碍服务",
                        /*
                         * ⚠️ **有 shell 时它就"不是必填项"** —— 我们替用户开。
                         * 这时它是"自动完成"的状态，不需要用户动手。
                         *
                         * 用户的原话:
                         * > 不需要让用户已经授权 shizuku/root 的时候还单独开启无障碍
                         */
                        required = !canGrantByShell,
                        granted = accessibilityOn || canGrantByShell,
                        summary = when {
                            accessibilityOn -> "已开启：可显示悬浮窗，也能直接读取键盘按键"
                            canGrantByShell ->
                                "会自动开启（你有 root / Shizuku，启动时我们替你开）"
                            else -> "需要开启：悬浮窗要靠它显示"
                        },
                        /*
                         * ⚠️ 用户明确要求「权限引导页给一些声明就好了」——
                         * 所以这里把能力边界说清楚，而不是做复杂交互。
                         */
                        detail = "无障碍模式只支持物理键盘按键；" +
                            "鼠标、手柄摇杆与触摸读不到，那些需要 root 或 Shizuku。",
                        /*
                         * ⚠️ 有 shell 时**不给按钮** —— 没什么可点的，
                         * 给了反而让用户以为"还得我手动点一下"。
                         */
                        actionLabel = when {
                            accessibilityOn -> null
                            canGrantByShell -> "立即开启"
                            else -> "去开启"
                        },
                        onAction = {
                            if (canGrantByShell) {
                                /*
                                 * ⚠️ 有 shell 时点一下就能开 —— 走 shell 授权，
                                 * 而不是把用户扔去系统设置页（那一步他能省掉）。
                                 */
                                viewModel.grantAccessibilityByShell()
                            } else {
                                AccessibilityGrant.openSettings(context)
                            }
                        },
                    )
                }

                item {
                    SetupCard(
                        icon = Icons.Default.Security,
                        title = "输入采集通道",
                        required = true,
                        granted = captureReady,
                        summary = when {
                            rootAvailable && shizukuGranted -> "已有 root 与 Shizuku，可读取全部输入"
                            rootAvailable -> "已有 root，可读取全部输入"
                            shizukuGranted -> "已有 Shizuku，可读取全部输入"
                            accessibilityOn -> "只有无障碍：能读键盘，读不到鼠标 / 手柄 / 触摸"
                            else -> "需要无障碍服务，或 root / Shizuku 其中之一"
                        },                        detail = buildList {
                            add("Root：" + if (rootAvailable) viewModel.capabilities.rootDetail else "不可用")
                            add("Shizuku：" + if (shizukuGranted) "已授权" else "未授权")
                            add("无障碍：" + if (accessibilityOn) "已开启（仅键盘）" else "未开启")
                        }.joinToString("\n"),
                        isBusy = viewModel.isProbing,
                        actionLabel = if (viewModel.isProbing) null else "检测 Root",
                        onAction = { viewModel.refreshCapabilities(force = true) },
                    )
                }

                // Shizuku 单独一张卡：它的授权流程与 root 完全不同（弹窗 vs su 授权）
                item {
                    SetupCard(
                        icon = Icons.Default.Key,
                        title = "Shizuku 授权",
                        required = false,
                        granted = shizukuGranted,
                        summary = if (shizukuGranted) {
                            "已授权，可作为 root 之外的采集通道"
                        } else {
                            "没有 root 时用它读取按键"
                        },
                        isBusy = viewModel.isProbing,
                        actionLabel = if (shizukuGranted || viewModel.isProbing) null else "申请授权",
                        onAction = { viewModel.requestShizukuPermission(context.findActivity()) },
                    )
                }

                item {
                    SetupCard(
                        icon = Icons.Default.BatteryAlert,
                        title = "忽略电池优化",
                        required = false,
                        granted = batteryIgnored,
                        summary = "避免后台被冻结导致按键中断",
                        actionLabel = if (batteryIgnored) null else "去设置",
                        onAction = { PermissionHelper.requestIgnoreBatteryOptimizations(context) },
                    )
                }

                // 只有确实缺东西时才展开详细提示
                if (!captureReady && !viewModel.isProbing) {
                    item { SectionHint(text = RootProbe.hint()) }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!allReady) {
                    Text(
                        text = "还差：" + buildList {
                            /*
                             * ⚠️⚠️ 有 root / Shizuku 时**不列"无障碍服务"**
                             * ============================================================
                             * 那种情况我们**替他开**（见 `canGrantByShell`），
                             * 不列出来才是对的 —— 列了会让用户以为还得自己点一下。
                             *
                             * 用户的原话:
                             * > 不需要让用户已经授权 shizuku/root 的时候
                             * > 还单独开启无障碍
                             */
                            if (!canGrantByShell && !accessibilityOn) add("无障碍服务")
                            if (!captureReady) add("采集通道")
                        }.joinToString("、").ifEmpty { "（等待检测…）" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { showSkipDialog = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("暂不授权")
                    }

                    Button(
                        onClick = {
                            /*
                             * ⚠️⚠️ **只有"真的授权齐全"才写这个标记**
                             * ============================================================
                             * 它决定**下次启动是否还走引导页**（见 `MainActivity`）。
                             *
                             * ⚠️ 这个按钮 `enabled = allReady`，所以走到这里
                             * 一定是"有可用的采集通道"了 —— 标记可以放心写。
                             */
                            viewModel.markSetupCompleted()
                            onFinished()
                        },
                        modifier = Modifier.weight(1f),
                        enabled = allReady,
                    ) {
                        Text("进入应用")
                    }
                }
            }
        }
    }

    if (showSkipDialog) {
        AlertDialog(
            onDismissRequest = { showSkipDialog = false },
            title = { Text("确认跳过？") },
            text = {
                Text(
                    "⚠️ 这一步**只有本次生效**。\n\n" +
                        "没授权的话按键读不到、悬浮窗也不会显示内容。\n" +
                        "下次打开应用会**再校验一次**，那时再授权也可以。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSkipDialog = false
                        /*
                         * ============================================================
                         * ⚠️⚠️ **跳过：只进这次，不写"引导已完成"**
                         * ============================================================
                         * 用户的原话:
                         *
                         * > 跳过授权只是**本次**的跳过授权，**下次进入应该仍然再次校验**
                         *
                         * ⚠️ 所以这里**故意不调** `markSetupCompleted()` ——
                         * 那个标记是"已经真的配好了"，只在「进入应用」时写。
                         *
                         * ⚠️ 我原来在这里也调了它，后果是"点过一次跳过，
                         * 以后永远不再校验"（用户报的正是这个）。
                         *
                         * ⚠️ 另外**主动清一次标记**:万一用户以前完成过引导、
                         * 从设置里重新进来又点了跳过，也要回到"未完成"状态，
                         * 这样下次启动的校验才是有效的。
                         */
                        viewModel.resetSetupCompleted()
                        onFinished()
                    },
                ) {
                    Text("仅本次跳过")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSkipDialog = false }) { Text("继续授权") }
            },
        )
    }
}

/**
 * 引导项卡片。
 *
 * 固定结构：标题行（图标 + 标题 + 状态）、一句话说明、可选细节、可选按钮。
 * 没有大段正文，保证一眼能扫完。
 */
@Composable
private fun SetupCard(
    icon: ImageVector,
    title: String,
    required: Boolean,
    granted: Boolean,
    summary: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    isBusy: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    SettingsCard(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )

                Spacer(modifier = Modifier.width(10.dp))

                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )

                if (isBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else if (granted) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "已完成",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    Text(
                        text = if (required) "必需" else "建议",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (required) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (detail != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = detail, style = MaterialTheme.typography.bodyMedium)
            }

            if (actionLabel != null || secondaryActionLabel != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (actionLabel != null && onAction != null) {
                        FilledTonalButton(onClick = onAction) { Text(actionLabel) }
                    }
                    if (secondaryActionLabel != null && onSecondaryAction != null) {
                        OutlinedButton(onClick = onSecondaryAction) { Text(secondaryActionLabel) }
                    }
                }
            }
        }
    }
}

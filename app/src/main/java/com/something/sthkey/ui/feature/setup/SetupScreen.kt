package com.something.sthkey.ui.feature.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Security
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.something.sthkey.data.permission.PermissionHelper
import com.something.sthkey.domain.capture.RootProbe
import com.something.sthkey.ui.MainViewModel
import com.something.sthkey.ui.component.ScreenScaffold
import com.something.sthkey.ui.component.SectionHint
import com.something.sthkey.ui.component.SettingsCard
import com.something.sthkey.ui.component.findActivity

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

    var overlayGranted by remember { mutableStateOf(PermissionHelper.canDrawOverlays(context)) }
    var batteryIgnored by remember {
        mutableStateOf(PermissionHelper.isIgnoringBatteryOptimizations(context))
    }
    var showSkipDialog by remember { mutableStateOf(false) }

    /** 从系统页面返回时重新检查 */
    fun recheck() {
        overlayGranted = PermissionHelper.canDrawOverlays(context)
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
    val captureReady = rootAvailable || shizukuGranted
    val allReady = overlayGranted && captureReady

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
                    SetupCard(
                        icon = Icons.Default.Layers,
                        title = "悬浮窗权限",
                        required = true,
                        granted = overlayGranted,
                        summary = "在其他应用之上显示按键",
                        actionLabel = if (overlayGranted) null else "去授权",
                        onAction = { PermissionHelper.openOverlaySettings(context) },
                    )
                }

                item {
                    SetupCard(
                        icon = Icons.Default.Security,
                        title = "输入采集通道",
                        required = true,
                        granted = captureReady,
                        summary = when {
                            rootAvailable && shizukuGranted -> "已有 root 与 Shizuku"
                            rootAvailable -> "已有 root，可读取按键"
                            shizukuGranted -> "已有 Shizuku，可读取按键"
                            else -> "需要 root 或 Shizuku 其中之一"
                        },
                        detail = buildList {
                            add("Root：" + if (rootAvailable) viewModel.capabilities.rootDetail else "不可用")
                            add("Shizuku：" + if (shizukuGranted) "已授权" else "未授权")
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
                            if (!overlayGranted) add("悬浮窗权限")
                            if (!captureReady) add("采集通道")
                        }.joinToString("、"),
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
            text = { Text("未授权时无法读取按键，悬浮窗不会显示内容。之后可在「设置 → 权限引导」重新授权。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSkipDialog = false
                        viewModel.markSetupCompleted()
                        onFinished()
                    },
                ) {
                    Text("跳过")
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

package com.something.sthkey.ui.feature.setup

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.something.sthkey.capture.adb.AdbPairingService
import com.something.sthkey.ui.component.ScrollableScreen

/**
 * 「激活 ADB」独立页。
 *
 * 交互和视觉尽量照 Stellar `StarterScreen`：
 * - 时间线步骤有进入/退出动画、状态颜色过渡；
 * - 无线调试关闭时插入「开启无线调试」步骤，回到页面自动继续；
 * - 未配对时插入通知权限 + 配对步骤；
 * - 连接成功自动开无障碍并进入主界面。
 */
@Composable
fun AdbActivationScreen(
    onBack: () -> Unit,
    onConnected: () -> Unit,
    modifier: Modifier = Modifier,
    activationViewModel: AdbActivationViewModel = viewModel(),
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val steps by activationViewModel.steps.collectAsState()
    val connected by activationViewModel.connected.collectAsState()
    val error by activationViewModel.error.collectAsState()

    BackHandler { onBack() }

    LaunchedEffect(Unit) { activationViewModel.start() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) activationViewModel.onResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var finished = remember { false }
    LaunchedEffect(connected) {
        if (connected && !finished) {
            finished = true
            onConnected()
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            activationViewModel.beginPairing()
            openDeveloperSettings(context)
        } else {
            android.widget.Toast.makeText(context, "需要通知权限才能输入配对码", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    val startPairing = {
        val needsNotification = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (needsNotification) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            activationViewModel.beginPairing()
            openDeveloperSettings(context)
        }
    }

    ScrollableScreen(
        title = "激活 ADB",
        subtitle = "通过无线调试或 5555 端口启动 Shell",
        onBack = onBack,
        modifier = modifier,
    ) {
        item {
            Column(
                modifier = Modifier.animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                steps.forEachIndexed { index, step ->
                    androidx.compose.runtime.key(step.id) {
                        TimelineStep(
                            isLast = index == steps.lastIndex,
                            step = step,
                        ) {
                            StepActionContent(
                                step = step,
                                onOpenWireless = { openDeveloperSettings(context) },
                                onStartPairing = startPairing,
                                onRequestNotification = {
                                    notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                },
                                onCopyTcpip = {
                                    clipboard.setText(AnnotatedString("adb tcpip 5555"))
                                    android.widget.Toast.makeText(
                                        context,
                                        "命令已复制",
                                        android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                },
                                onRetry = { activationViewModel.retry() },
                            )
                        }
                    }
                }

                if (error != null) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = error.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { activationViewModel.retry() }) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("重试")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun openDeveloperSettings(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(":settings:fragment_args_key", "toggle_adb_wireless"),
        )
    }
}

@Composable
private fun StepActionContent(
    step: AdbStep,
    onOpenWireless: () -> Unit,
    onStartPairing: () -> Unit,
    onRequestNotification: () -> Unit,
    onCopyTcpip: () -> Unit,
    onRetry: () -> Unit,
) {
    AnimatedVisibility(
        visible = step.action != null,
        enter = fadeIn(tween(250)) + slideInVertically(tween(250)) { it / 3 },
        exit = fadeOut(tween(150)) + slideOutVertically(tween(150)) { it / 3 },
    ) {
        Column(modifier = Modifier.padding(top = 12.dp)) {
            when (step.action) {
                AdbStepAction.OPEN_WIRELESS -> {
                    Text(
                        text = "打开系统开关后返回本页，会自动继续。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onOpenWireless, modifier = Modifier.fillMaxWidth()) {
                        Text("去打开无线调试")
                    }
                }

                AdbStepAction.GRANT_NOTIFICATION -> {
                    Text(
                        text = "配对码要显示在通知栏里，必须先允许通知。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onRequestNotification, modifier = Modifier.fillMaxWidth()) {
                        Text("允许通知")
                    }
                }

                AdbStepAction.START_PAIRING -> {
                    Text(
                        text = "点「开始配对」后，在系统「使用配对码配对设备」弹窗出现时，从通知栏输入 6 位码。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onStartPairing, modifier = Modifier.fillMaxWidth()) {
                        Text("开始配对")
                    }
                }

                AdbStepAction.COPY_TCPIP -> {
                    Text(
                        text = "adb tcpip 5555",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onCopyTcpip, modifier = Modifier.weight(1f)) {
                            Text("复制命令")
                        }
                        Button(onClick = onRetry, modifier = Modifier.weight(1f)) {
                            Text("重试")
                        }
                    }
                }

                null -> Unit
            }
        }
    }
}

/** 时间线单步：状态圆点 + 连接线 + Surface 卡片，视觉照 Stellar `TimelineStep`。 */
@Composable
private fun TimelineStep(
    isLast: Boolean,
    step: AdbStep,
    action: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val isCompleted = step.status == AdbStepStatus.COMPLETED
    val isRunning = step.status == AdbStepStatus.RUNNING
    val isPending = step.status == AdbStepStatus.PENDING
    val isError = step.status == AdbStepStatus.ERROR
    val isWarning = step.status == AdbStepStatus.WARNING

    val cardColor by animateColorAsState(
        targetValue = when {
            isCompleted -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            isError -> MaterialTheme.colorScheme.errorContainer
            isWarning -> MaterialTheme.colorScheme.tertiaryContainer
            isRunning -> MaterialTheme.colorScheme.surfaceContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        animationSpec = tween(300),
    )

    AnimatedVisibility(
        visible = true,
        enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 4 },
        exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { it / 4 },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(36.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            when {
                                isCompleted -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                isError -> MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                                isWarning -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)
                                isRunning -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.1f)
                            },
                            CircleShape,
                        )
                        .padding(6.dp)
                        .background(
                            when {
                                isCompleted -> MaterialTheme.colorScheme.primary
                                isError -> MaterialTheme.colorScheme.error
                                isWarning -> MaterialTheme.colorScheme.tertiary
                                isRunning -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            },
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        isCompleted -> Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp),
                        )
                        isError -> Icon(
                            Icons.Default.Close,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onError,
                            modifier = Modifier.size(16.dp),
                        )
                        isWarning -> Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiary,
                            modifier = Modifier.size(16.dp),
                        )
                        isRunning -> CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
                if (!isLast) {
                    Box(
                        Modifier
                            .width(2.dp)
                            .weight(1f)
                            .background(
                                when {
                                    isCompleted -> MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                    isError -> MaterialTheme.colorScheme.error.copy(alpha = 0.3f)
                                    else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                                },
                            ),
                    )
                }
            }

            Surface(
                modifier = Modifier
                    .weight(1f)
                    .padding(bottom = 12.dp),
                shape = RoundedCornerShape(20.dp),
                color = cardColor,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(
                                    when {
                                        isCompleted -> MaterialTheme.colorScheme.primaryContainer
                                        isError -> MaterialTheme.colorScheme.errorContainer
                                        isWarning -> MaterialTheme.colorScheme.tertiaryContainer
                                        isRunning -> MaterialTheme.colorScheme.primaryContainer
                                        else -> MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    RoundedCornerShape(16.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = step.icon,
                                contentDescription = null,
                                tint = when {
                                    isCompleted -> MaterialTheme.colorScheme.primary
                                    isError -> MaterialTheme.colorScheme.error
                                    isWarning -> MaterialTheme.colorScheme.tertiary
                                    isRunning -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                },
                                modifier = Modifier.size(24.dp),
                            )
                        }
                        Text(
                            text = step.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = when {
                                isPending -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                isError -> MaterialTheme.colorScheme.onErrorContainer
                                isWarning -> MaterialTheme.colorScheme.onTertiaryContainer
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = step.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = when {
                            isPending -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            isError -> MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                            isWarning -> MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    action?.invoke(this)
                }
            }
        }
    }
}

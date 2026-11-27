package com.something.sthkey.ui.feature.setup

import android.app.Application
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.something.sthkey.capture.adb.AdbAuthorization
import com.something.sthkey.capture.adb.AdbPairingService
import com.something.sthkey.capture.adb.AdbPortProbe
import com.something.sthkey.core.prefs.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

enum class AdbStepStatus { PENDING, RUNNING, COMPLETED, WARNING, ERROR }

enum class AdbStepAction { OPEN_WIRELESS, START_PAIRING, GRANT_NOTIFICATION, COPY_TCPIP }

data class AdbStep(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val status: AdbStepStatus = AdbStepStatus.PENDING,
    val description: String = "",
    val action: AdbStepAction? = null,
)

/**
 * ADB 激活流程（照 Stellar StarterViewModel 的顺序与状态机）：
 *
 * 检测端口 → 检测配对 → 连接 → 校验 → 完成
 *
 * - 有 5555：跳过配对直接连接；
 * - 无线调试关着：插入「开启无线调试」步骤，回页面后自动继续；
 * - 未配对：插入通知权限 → 配对步骤，配对成功自动继续；
 * - Android 10 及以下无端口：给 adb tcpip 5555 指引，不假装能配对。
 */
class AdbActivationViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = AppPrefs.get(app)
    private val context: Context get() = getApplication()

    private val _steps = MutableStateFlow(initialSteps())
    val steps: StateFlow<List<AdbStep>> = _steps.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var started = false
    private var waitingWireless = false
    private var waitingPairing = false
    private var detectedPort = -1

    fun start() {
        if (started) return
        started = true
        viewModelScope.launch { detectAndConnect() }
    }

    /** 从系统设置回来时调用：无线调试打开了就继续。 */
    fun onResume() {
        if (waitingWireless && wirelessEnabled()) {
            waitingWireless = false
            viewModelScope.launch {
                update("enable_wireless", AdbStepStatus.COMPLETED, "无线调试已开启")
                delay(300.milliseconds)
                remove("enable_wireless")
                resetBase()
                detectAndConnect()
            }
            return
        }
        if (waitingPairing && prefs.adbPaired) {
            waitingPairing = false
            viewModelScope.launch {
                update("pair", AdbStepStatus.COMPLETED, "配对成功")
                delay(300.milliseconds)
                connect()
            }
        }
    }

    /** 用户点了「配对」且通知权限已拿到。 */
    fun beginPairing() {
        waitingPairing = true
        remove("notification")
        update("pair", AdbStepStatus.RUNNING, "请在通知栏输入配对码")
        update("pair_action", AdbStepStatus.RUNNING, "正在等待通知栏配对码…", action = null)
        AdbPairingService.start(context)
        viewModelScope.launch {
            while (waitingPairing) {
                delay(500)
                if (prefs.adbPaired) {
                    waitingPairing = false
                    update("pair", AdbStepStatus.COMPLETED, "配对成功")
                    delay(300)
                    connect()
                    break
                }
            }
        }
    }

    fun retry() {
        _error.value = null
        waitingWireless = false
        waitingPairing = false
        _steps.value = initialSteps()
        started = false
        start()
    }

    private suspend fun detectAndConnect() {
        update("port", AdbStepStatus.RUNNING, "正在检测 5555 端口…")
        delay(300.milliseconds)

        val port = withContext(Dispatchers.IO) { if (AdbPortProbe.probe()) AdbAuthorization.DEFAULT_PORT else null }
        if (port != null) {
            detectedPort = port
            update("port", AdbStepStatus.COMPLETED, "检测到 $port 端口")
            update("pair", AdbStepStatus.COMPLETED, "5555 端口不需要配对")
            delay(300.milliseconds)
            connect()
            return
        }

        update("port", AdbStepStatus.WARNING, "没有检测到 5555 端口")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            insertAfter(
                "port",
                AdbStep(
                    id = "android10",
                    title = "Android 10 需要电脑开端口",
                    icon = Icons.Default.WifiOff,
                    status = AdbStepStatus.WARNING,
                    description = "在电脑执行 adb tcpip 5555，然后回本页重试",
                    action = AdbStepAction.COPY_TCPIP,
                ),
            )
            return
        }

        if (!wirelessEnabled()) {
            waitingWireless = true
            insertAfter(
                "port",
                AdbStep(
                    id = "enable_wireless",
                    title = "开启无线调试",
                    icon = Icons.Default.WifiOff,
                    status = AdbStepStatus.RUNNING,
                    description = "进入开发者选项打开「无线调试」",
                    action = AdbStepAction.OPEN_WIRELESS,
                ),
            )
            return
        }

        update("port", AdbStepStatus.COMPLETED, "无线调试已开启")

        if (prefs.adbPaired) {
            update("pair", AdbStepStatus.COMPLETED, "已配对")
            delay(300.milliseconds)
            connect()
        } else {
            showPairingSteps()
        }
    }

    /** 插入「允许通知」+「无线调试配对」两个步骤，回到配对流程。 */
    private fun showPairingSteps() {
        update("pair", AdbStepStatus.WARNING, "需要一次无线调试配对")
        val notificationOk = notificationGranted()
        if (!notificationOk) {
            insertAfter(
                "pair",
                AdbStep(
                    id = "notification",
                    title = "允许通知",
                    icon = Icons.Default.Notifications,
                    status = AdbStepStatus.RUNNING,
                    description = "配对码要显示在通知栏里",
                    action = AdbStepAction.GRANT_NOTIFICATION,
                ),
            )
        }
        insertAfter(
            if (notificationOk) "pair" else "notification",
            AdbStep(
                id = "pair_action",
                title = "无线调试配对",
                icon = Icons.Default.VerifiedUser,
                status = if (notificationOk) AdbStepStatus.RUNNING else AdbStepStatus.PENDING,
                description = "通知栏会先显示「正在查找配对服务」",
                action = AdbStepAction.START_PAIRING,
            ),
        )
    }

    private fun connect() {
        viewModelScope.launch {
            _error.value = null
            update("connect", AdbStepStatus.RUNNING, "正在连接 ADB…")
            val result = withContext(Dispatchers.IO) {
                if (detectedPort > 0) {
                    AdbAuthorization.connectTcp(context)
                } else {
                    AdbAuthorization.connectTls(context)
                }
            }
            if (result.ok) {
                update("connect", AdbStepStatus.COMPLETED, "连接成功")
                update("verify", AdbStepStatus.COMPLETED, "uid=2000(shell)")
                delay(300.milliseconds)
                update("finish", AdbStepStatus.COMPLETED, "ADB 采集已可用")
                prefs.adbEverConnected = true
                _connected.value = true
            } else {
                update("connect", AdbStepStatus.ERROR, result.detail)
                _error.value = result.detail
                /*
                 * 无线调试（TLS）路径下，如果连接失败且本地配对标记已被
                 * AdbAuthorization 清掉（用户在系统里取消了配对），
                 * 直接回到「需要配对」，不要停在错误上等用户猜。
                 */
                if (detectedPort == 0 && !prefs.adbPaired) {
                    showPairingSteps()
                }
            }
        }
    }

    private fun initialSteps(): List<AdbStep> = listOf(
        AdbStep("port", "检测 ADB 端口", Icons.Default.Wifi, AdbStepStatus.PENDING, "正在检测 5555 / 无线调试"),
        AdbStep("pair", "检测配对状态", Icons.Default.VerifiedUser, AdbStepStatus.PENDING, "检查是否已配对"),
        AdbStep("connect", "连接 ADB 服务", Icons.Default.Cable, AdbStepStatus.PENDING, "等待连接"),
        AdbStep("verify", "校验 shell 身份", Icons.Default.Search, AdbStepStatus.PENDING, "连接成功后自动校验"),
        AdbStep("finish", "启动采集", Icons.Default.CheckCircle, AdbStepStatus.PENDING, "授权后自动使用 ADB 通道"),
    )

    private fun resetBase() {
        _steps.value = initialSteps()
    }

    private fun update(id: String, status: AdbStepStatus, description: String, action: AdbStepAction? = null) {
        viewModelScope.launch {
            val list = _steps.value.toMutableList()
            val index = list.indexOfFirst { it.id == id }
            if (index >= 0) {
                list[index] = list[index].copy(
                    status = status,
                    description = description,
                    action = action ?: list[index].action,
                )
                _steps.value = list
            }
        }
    }

    private fun insertAfter(afterId: String, step: AdbStep) {
        val list = _steps.value.toMutableList()
        val index = list.indexOfFirst { it.id == afterId }
        list.add(if (index >= 0) index + 1 else list.size, step)
        _steps.value = list
    }

    private fun remove(id: String) {
        _steps.value = _steps.value.filterNot { it.id == id }
    }

    private fun wirelessEnabled(): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1
    }.getOrDefault(false)

    private fun notificationGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}

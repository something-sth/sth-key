package com.something.sthkey.capture.adb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.something.sthkey.R
import com.something.sthkey.core.log.AppLog
import com.something.sthkey.core.prefs.AppPrefs
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 无线调试配对的前台服务（照 Stellar 的时序）：
 *
 * 1. 启动后先显示「正在查找配对服务」，**没有输入框**；
 * 2. mDNS 发现配对端口后才显示带 RemoteInput 的通知；
 * 3. 输入框通过 `PendingIntent.getForegroundService` 把码和端口回传给本服务；
 * 4. 配对失败 2 秒后自动恢复输入框；
 * 5. 成功后自动 connectTls，并写 `adbPaired`。
 */
class AdbPairingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())

    private var mdns: AdbMdns? = null
    private var discoveredPort = -1
    private var searching = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startSearch()
                startForegroundCompat(notification(SEARCHING_TITLE, SEARCHING_TEXT))
            }

            ACTION_REPLY -> {
                val code = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(KEY_CODE)?.toString()?.trim().orEmpty()
                val port = intent.getIntExtra(KEY_PORT, -1)
                if (code.length == 6 && port > 0) {
                    pair(code, port)
                    startForegroundCompat(notification("配对中…", "正在与配对服务握手"))
                } else {
                    startForegroundCompat(inputNotification(discoveredPort))
                }
            }

            ACTION_STOP -> {
                stopSearch()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            else -> return START_NOT_STICKY
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        stopSearch()
        mdns = null
        scope.cancel()
        super.onDestroy()
    }

    private fun startSearch() {
        if (searching) return
        searching = true
        mdns = AdbMdns(this, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { _, port ->
            if (port > 0) {
                discoveredPort = port
                AppLog.i(TAG, "发现配对端口：$port")
                handler.post {
                    if (searching) {
                        startForegroundCompat(inputNotification(port))
                    }
                }
            }
        }.apply { start() }
    }

    private fun stopSearch() {
        if (!searching) return
        searching = false
        runCatching { mdns?.stop() }
        mdns = null
    }

    private fun pair(code: String, port: Int) {
        scope.launch {
            val result = AdbAuthorization.pairAt(applicationContext, "127.0.0.1", port, code)
            if (result.ok) {
                AppPrefs.get(applicationContext).adbPaired = true
                AppLog.i(TAG, "配对成功，开始连接")
                handler.post {
                    startForegroundCompat(notification("配对成功", "正在连接无线调试…"))
                }
                val tls = AdbAuthorization.connectTls(applicationContext)
                handler.post {
                    startForegroundCompat(
                        notification(
                            if (tls.ok) "ADB 已连接" else "配对成功，等待启动",
                            if (tls.ok) "回到应用即可使用 ADB 通道" else "回到应用点「启动」",
                        ),
                    )
                }
                delay(5_000)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else {
                AppLog.w(TAG, "配对失败：${result.detail}")
                handler.post {
                    startForegroundCompat(notification("配对失败", "2 秒后自动重试"))
                    handler.postDelayed({
                        if (discoveredPort > 0) {
                            startForegroundCompat(inputNotification(discoveredPort))
                        }
                    }, 2_000)
                }
            }
        }
    }

    private fun startForegroundCompat(n: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "startForeground 失败：${t.javaClass.simpleName}")
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
        }
    }

    private fun notification(title: String, text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .build()

    private fun inputNotification(port: Int): Notification {
        val remoteInput = RemoteInput.Builder(KEY_CODE)
            .setLabel("6 位配对码")
            .build()
        val reply = PendingIntent.getForegroundService(
            this,
            REQUEST_REPLY,
            Intent(this, AdbPairingService::class.java)
                .setAction(ACTION_REPLY)
                .putExtra(KEY_PORT, port),
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0,
        )
        val action = Notification.Action.Builder(
            null,
            "输入配对码",
            reply,
        ).addRemoteInput(remoteInput).build()

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("已找到配对服务")
            .setContentText("在通知栏输入系统显示的 6 位配对码")
            .setOngoing(true)
            .addAction(action)
            .build()
    }

    private fun stopAction(): Notification.Action =
        Notification.Action.Builder(
            null,
            "停止",
            PendingIntent.getService(
                this,
                REQUEST_STOP,
                Intent(this, AdbPairingService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        ).build()

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "ADB 配对", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val TAG = "AdbPairingService"
        private const val CHANNEL_ID = "adb_pairing"
        private const val NOTIFICATION_ID = 4321
        private const val ACTION_START = "com.something.sthkey.adb.PAIR_START"
        private const val ACTION_REPLY = "com.something.sthkey.adb.PAIR_REPLY"
        private const val ACTION_STOP = "com.something.sthkey.adb.PAIR_STOP"
        private const val KEY_CODE = "pairing_code"
        private const val KEY_PORT = "pairing_port"
        private const val REQUEST_REPLY = 11
        private const val REQUEST_STOP = 12
        private const val SEARCHING_TITLE = "正在查找配对服务"
        private const val SEARCHING_TEXT = "请先打开无线调试的「使用配对码配对设备」"

        fun start(context: Context) {
            val intent = Intent(context, AdbPairingService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}

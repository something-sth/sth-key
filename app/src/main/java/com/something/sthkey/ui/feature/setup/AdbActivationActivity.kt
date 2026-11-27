package com.something.sthkey.ui.feature.setup

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.something.sthkey.R
import com.something.sthkey.capture.accessibility.AccessibilityGrant
import com.something.sthkey.capture.accessibility.SthKeyAccessibilityService
import com.something.sthkey.ui.theme.SthKeyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ADB 激活页的 Activity。
 *
 * 注意：这里**不实例化 MainViewModel**。MainViewModel 的 init 会做采集恢复、
 * 能力探测、自动重连等一整套启动工作；在激活页再跑一遍既浪费，也会和
 * 激活流程自己发起的 ADB 连接抢锁，之前就出现过主线程 ANR。
 *
 * 激活成功后只做两件事：用 ADB 开无障碍、setResult 回引导页。
 */
class AdbActivationActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SthKeyTheme(seedColor = 0xFF6750A4.toInt()) {
                AdbActivationScreen(
                    onBack = { finish() },
                    onConnected = {
                        lifecycleScope.launch {
                            if (!SthKeyAccessibilityService.isConnected()) {
                                withContext(Dispatchers.IO) {
                                    runCatching {
                                        AccessibilityGrant.grant(
                                            context = applicationContext,
                                            useRoot = false,
                                            useAdb = true,
                                            forceRebind = true,
                                        )
                                    }
                                }
                            }
                            setResult(Activity.RESULT_OK)
                            finish()
                        }
                    },
                )
            }
        }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, R.anim.adb_slide_out)
    }
}

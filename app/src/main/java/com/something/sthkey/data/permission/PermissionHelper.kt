package com.something.sthkey.data.permission

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import com.something.sthkey.core.log.AppLog

/**
 * 系统权限的查询与跳转。
 *
 * 与采集能力（root / Shizuku）分开：那两者是"外部环境是否具备"，
 * 这里是"系统是否允许本应用做事"，两者的引导方式完全不同。
 */
object PermissionHelper {

    private const val TAG = "Permission"

    /*
     * ============================================================
     * 悬浮窗
     * ============================================================
     */

    /** 是否已获得悬浮窗权限（显示在其他应用上层） */
    fun canDrawOverlays(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    /** 跳转到系统的悬浮窗授权页 */
    fun openOverlaySettings(context: Context) {
        AppLog.i(TAG, "跳转悬浮窗权限设置")
        startSafely(
            context,
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ),
        )
    }

    /*
     * ============================================================
     * 电池优化
     * ============================================================
     */

    /**
     * 是否已忽略电池优化。
     *
     * 采集服务需要长时间后台运行，被系统冻结会导致按键"时灵时不灵"，
     * 因此引导页会要求用户放开这一项。
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 请求忽略电池优化。
     *
     * 这里用 ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 直接弹系统对话框
     * （需要 manifest 里声明对应权限）。部分 ROM 会拦截该 action，
     * 因此失败时回落到电池优化列表页，让用户手动选。
     */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        AppLog.i(TAG, "请求忽略电池优化")
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        if (!startSafely(context, direct)) {
            startSafely(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    /*
     * ============================================================
     * 应用详情页
     * ============================================================
     */

    /** 打开本应用的系统设置页（权限被拒绝且不再询问时的兜底入口） */
    fun openAppSettings(context: Context) {
        AppLog.i(TAG, "跳转应用详情设置")
        startSafely(
            context,
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            ),
        )
    }

    /**
     * 打开外链。
     *
     * 部分 ROM 没有浏览器时 startActivity 会抛 ActivityNotFoundException，
     * 因此统一走 [startSafely]，避免"点了没反应"或直接崩。
     */
    fun openUrl(context: Context, url: String): Boolean {
        AppLog.i(TAG, "打开链接：$url")
        return startSafely(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    /** 启动 Activity，失败返回 false 并记录日志 */
    private fun startSafely(context: Context, intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        AppLog.e(TAG, "启动系统页面失败：${intent.action}", e)
        false
    }
}

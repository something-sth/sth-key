package com.something.sthkey.data.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.something.sthkey.core.log.AppLog
import com.something.sthkey.domain.update.UpdateInfo
import java.net.HttpURLConnection
import java.net.URL

/**
 * 检查更新：**不需要任何服务器**，全部走静态资源 + GitHub 公开接口。
 *
 * ============================================================
 * 为什么要两个来源
 * ============================================================
 * | 来源 | 优点 | 缺点 |
 * |---|---|---|
 * | jsDelivr 读仓库里的 `update/version.json` | CDN、无 API 限流、国内相对可用 | 需要仓库里维护这个文件（发版时由 Actions 自动更新） |
 * | GitHub API `/releases/latest` | 天然就是"最新 Release"，还附带更新说明 | `api.github.com` 国内经常不通；未认证 60 次/小时/IP，运营商 NAT 下容易 403 |
 *
 * 所以**先 jsDelivr、失败再 GitHub API**；两个都失败就返回 null，
 * 调用方静默处理（自动检查）或提示"请检查网络"（手动检查）。
 *
 * ⚠️ 不使用缓存目录：这是"检查有没有新版"，结果必须尽量新鲜；
 * 用 6s 超时 + 失败回落更省事。
 */
object UpdateChecker {

    private const val TAG = "Update"

    /** 仓库（与 README / 关于页里的链接一致） */
    const val REPO = "something-sth/sth-key"

    /** 默认分支是 **master**（不是 main，见关于页链接那段注释） */
    private const val JSDELIVR_MANIFEST =
        "https://cdn.jsdelivr.net/gh/$REPO@master/update/version.json"

    /** 不带 ref 的写法会跟随仓库默认分支，作为分支改名时的兜底 */
    private const val JSDELIVR_MANIFEST_FALLBACK =
        "https://cdn.jsdelivr.net/gh/$REPO/update/version.json"

    private const val GITHUB_LATEST_RELEASE =
        "https://api.github.com/repos/$REPO/releases/latest"

    private const val CONNECT_TIMEOUT_MS = 6_000
    private const val READ_TIMEOUT_MS = 8_000

    /**
     * 拉取远端最新版本；全部来源都失败时返回 null。
     *
     * ⚠️ 先做**联网预检**（借自 SukiSU 的做法）：没网时直接返回 null，
     * 不陪着超时等 6~8 秒 —— 自动检查是在启动路径上跑的，不能拖慢首屏。
     *
     * @param manifestUrlOverride 调试用：非空时**只**读这个地址（见 AppPrefs 里的说明），
     *   这样构造"有新版本 / 坏 JSON / 404 / 超时"都是可控的。
     *
     * ⚠️ 这是**阻塞**调用（网络 IO），调用方必须在 IO 线程执行。
     */
    fun fetchLatest(context: Context, manifestUrlOverride: String? = null): UpdateInfo? {
        if (!isNetworkAvailable(context)) {
            AppLog.i(TAG, "检查更新跳过：当前没有可用网络")
            return null
        }
        if (!manifestUrlOverride.isNullOrBlank()) {
            AppLog.i(TAG, "检查更新：使用调试数据源 $manifestUrlOverride")
            return fetchJson(manifestUrlOverride)?.let { UpdateManifest.parse(it) }?.also {
                AppLog.i(TAG, "检查更新：调试数据源命中 ${it.versionName}（${it.versionCode}）")
            } ?: run {
                AppLog.w(TAG, "检查更新：调试数据源不可用/解析失败")
                null
            }
        }
        fetchJson(JSDELIVR_MANIFEST)?.let { UpdateManifest.parse(it) }?.let {
            AppLog.i(TAG, "检查更新：manifest 命中 ${it.versionName}（${it.versionCode}）")
            return it
        }
        fetchJson(JSDELIVR_MANIFEST_FALLBACK)?.let { UpdateManifest.parse(it) }?.let {
            AppLog.i(TAG, "检查更新：manifest(无 ref) 命中 ${it.versionName}")
            return it
        }
        fetchJson(GITHUB_LATEST_RELEASE)?.let { UpdateManifest.parseGitHubRelease(it) }?.let {
            AppLog.i(TAG, "检查更新：GitHub Release 命中 ${it.versionName}（${it.versionCode}）")
            return it
        }
        AppLog.w(TAG, "检查更新：所有来源都失败（jsDelivr x2 / GitHub API）")
        return null
    }

    /** 有没有可用的网络（Wi-Fi / 移动数据 / 以太网） */
    private fun isNetworkAvailable(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(true)

    private fun fetchJson(url: String): String? = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            /*
             * ⚠️ GitHub API **必须要 User-Agent**，否则直接 403；
             * 用固定的应用标识而不是默认的 Dalvik/xxx，问题好排查。
             */
            setRequestProperty("User-Agent", "sth-key-android-updater")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                AppLog.w(TAG, "拉取失败 $url → HTTP $code")
                return null
            }
            connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            runCatching { connection.disconnect() }
        }
    }.onFailure {
        /* 超时 / DNS 污染 / 连接重置都会走到这里 —— 静默回落，不打扰用户 */
        AppLog.w(TAG, "拉取异常 $url → ${it.javaClass.simpleName}: ${it.message?.take(80)}")
    }.getOrNull()

    /** 给"前往下载"用：优先 APK 直链，否则 Release 页，最后退回 Releases 列表 */
    fun downloadTarget(info: UpdateInfo): String = when {
        info.downloadUrl.isNotBlank() -> info.downloadUrl
        info.pageUrl.isNotBlank() -> info.pageUrl
        else -> "https://github.com/$REPO/releases"
    }

    /** 给"查看更新说明"用：Release 页，取不到就退回 Releases 列表 */
    fun pageTarget(info: UpdateInfo): String =
        info.pageUrl.ifBlank { "https://github.com/$REPO/releases" }
}

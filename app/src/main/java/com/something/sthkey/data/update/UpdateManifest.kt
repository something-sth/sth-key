package com.something.sthkey.data.update

import com.something.sthkey.domain.update.UpdateInfo
import com.something.sthkey.domain.update.versionCodeFromTag
import org.json.JSONObject

/**
 * 两种数据来源的 JSON 解析。
 *
 * 单独抽出来是为了**能被本地单测直接调**（`org.json` 在单测里由
 * `org.json:json` 提供实现）—— 解析是这里唯一容易出错的部分，
 * 网络本身没法在单测里覆盖。
 *
 * 解析思路参考了 SukiSU 的做法：**从 Release 附件名里同时拿版本名和 versionCode**
 * （它要求附件名形如 `SukiSU_v4.3.0_10012-release.apk`）。
 * 我们沿用这个思路但更宽松：只要附件名以 `_<数字>.apk` 结尾就认，
 * 认不出再退回"从 tag 推 versionCode"。
 */
object UpdateManifest {

    /** 附件名里的版本号：`…_288.apk` / `…_288-release.apk` */
    private val ASSET_VERSION_CODE = Regex("""_(\d+)(?:-[^/.]*)?\.apk$""", RegexOption.IGNORE_CASE)

    /**
     * 解析仓库里的 `update/version.json`：
     *
     * ```json
     * { "versionCode": 288, "versionName": "2.8.8", "tag": "v2.8.8",
     *   "pageUrl": "https://github.com/…/releases/tag/v2.8.8", "notes": "…" }
     * ```
     */
    fun parse(json: String): UpdateInfo? = runCatching {
        val obj = JSONObject(json)
        val name = obj.optString("versionName").trim()
        if (name.isEmpty()) return null
        UpdateInfo(
            versionCode = obj.optInt("versionCode", 0).coerceAtLeast(0),
            versionName = name,
            pageUrl = obj.optString("pageUrl").trim(),
            downloadUrl = obj.optString("downloadUrl").trim(),
            notes = obj.optString("notes"),
            source = "manifest",
        )
    }.getOrNull()

    /**
     * 解析 GitHub Releases API 的 `/releases/latest` 响应。
     *
     * ⚠️ `/latest` 本身就不返回 prerelease/draft，但这里仍然显式挡一道：
     * 万一以后换成分页接口，漏掉这两个标志会让用户被推去装测试版。
     */
    fun parseGitHubRelease(json: String): UpdateInfo? = runCatching {
        val obj = JSONObject(json)
        if (obj.optBoolean("prerelease", false) || obj.optBoolean("draft", false)) return null
        val tag = obj.optString("tag_name").trim()
        if (tag.isEmpty()) return null

        /* ① 附件名优先：它能同时给出 versionCode 与 APK 直链 */
        var assetCode = 0
        var assetName: String? = null
        var downloadUrl = ""
        var firstApkUrl = ""
        val assets = obj.optJSONArray("assets")
        if (assets != null) {
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                val name = asset.optString("name")
                val url = asset.optString("browser_download_url").trim()
                if (firstApkUrl.isEmpty() && name.endsWith(".apk", ignoreCase = true)) {
                    firstApkUrl = url
                }
                val match = ASSET_VERSION_CODE.find(name) ?: continue
                val code = match.groupValues[1].toIntOrNull() ?: continue
                if (code > assetCode) {
                    assetCode = code
                    assetName = name
                    downloadUrl = url
                }
            }
        }
        /* 附件名里没有版本号时，直链退回第一个 .apk */
        if (downloadUrl.isEmpty()) downloadUrl = firstApkUrl

        /* ② 附件名没有版本号时，从 tag 推（`v2.8.8` → 288） */
        val parsedTagName = tag.removePrefix("v").removePrefix("V").trim().ifEmpty { return null }
        val versionCode = if (assetCode > 0) assetCode else (versionCodeFromTag(tag) ?: 0)

        UpdateInfo(
            versionCode = versionCode,
            /* 附件名里有版本名时以它为准（tag 偶尔会带 -release 之类后缀） */
            versionName = assetVersionName(assetName) ?: parsedTagName,
            pageUrl = obj.optString("html_url").trim(),
            downloadUrl = downloadUrl,
            notes = obj.optString("body"),
            source = "github",
        )
    }.getOrNull()

    /** `sth-key_v2.8.8_288.apk` → `2.8.8`（取不到就返回 null，交给 tag） */
    private fun assetVersionName(assetName: String?): String? {
        if (assetName.isNullOrBlank()) return null
        val match = Regex("""[vV](\d+(?:\.\d+)+)""").find(assetName) ?: return null
        return match.groupValues[1]
    }
}

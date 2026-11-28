package com.something.sthkey.domain.update

/**
 * 一次「检查更新」的结果。
 *
 * 只有一个来源能拿到 `versionCode`（仓库里的 `update/version.json`）；
 * GitHub Releases API 只有 tag，所以那时 [versionCode] 是 0，比较时改用版本名。
 */
data class UpdateInfo(
    /** 远端版本号；0 = 该来源不提供（GitHub API 且附件名不规范时） */
    val versionCode: Int = 0,
    /** 版本名，例如 `2.8.8` */
    val versionName: String,
    /** 供用户打开的页面（Release 页）；有直链时也保留，便于展示更新说明 */
    val pageUrl: String = "",
    /** APK 直链（GitHub Release 附件）；空 = 只有页面可开 */
    val downloadUrl: String = "",
    /** 更新说明（Release 正文），可能为空 */
    val notes: String = "",
    /** 数据来源，只用于日志：manifest / github */
    val source: String = "",
)

/**
 * 本地版本是否比远端旧。
 *
 * 优先比 `versionCode`（整数比较最可靠）；远端没给 code 时（GitHub API）
 * 退化成按版本名的三段数字比较。
 */
fun UpdateInfo.isNewerThan(localVersionCode: Int, localVersionName: String): Boolean {
    if (versionCode > 0) return versionCode > localVersionCode
    return compareVersionNames(localVersionName, versionName) < 0
}

/**
 * 版本名比较：`2.8.10 > 2.8.9`、`2.9.0 > 2.8.99`。
 *
 * 逐段转数字比较；段数不足时按缺省 0 处理（`2.8` == `2.8.0`）。
 * 解析不出来的段按 0 处理 —— 检查更新不该因为 tag 不规范就崩。
 */
fun compareVersionNames(left: String, right: String): Int {
    val leftParts = versionParts(left)
    val rightParts = versionParts(right)
    val size = maxOf(leftParts.size, rightParts.size)
    for (index in 0 until size) {
        val l = leftParts.getOrElse(index) { 0 }
        val r = rightParts.getOrElse(index) { 0 }
        if (l != r) return l.compareTo(r)
    }
    return 0
}

/** 只取前导 `数字.数字(.数字…)`，忽略 `v` 前缀与 `-beta` 之类后缀 */
private fun versionParts(value: String): List<Int> =
    Regex("\\d+")
        .findAll(value)
        .take(3)
        .mapNotNull { it.value.toIntOrNull() }
        .toList()

/**
 * 从 tag 推 `versionCode`（`v2.8.8` → 288）。
 *
 * 项目现行的编码规则是 `major*100 + minor*10 + patch`（2.8.7 = 287）。
 * 只在**降级兜底**里用（GitHub API 不给 versionCode）；万一以后
 * minor/patch 到两位数，这个推导会失真，所以真相源仍是 `version.json`。
 */
fun versionCodeFromTag(tag: String): Int? {
    val parts = versionParts(tag)
    if (parts.size < 3) return null
    return parts[0] * 100 + parts[1] * 10 + parts[2]
}

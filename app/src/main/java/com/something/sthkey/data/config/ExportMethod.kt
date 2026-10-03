package com.something.sthkey.data.config

/**
 * 配置包的导出方式。
 *
 * ============================================================
 * ⚠️ 为什么没有"自定义导出目录"
 * ============================================================
 * 曾经有过一项：用 SAF 目录树预先设一个目录，之后静默写入。
 * 但**实测在多种设备上都会失败**（"无法在所选目录里创建文件"），
 * 而且失败原因难以定位 —— `DocumentsContract.createDocument` 抛的
 * `FileNotFoundException` 在不同 DocumentsProvider 下含义不同，
 * 有的表示重名、有的表示该目录不允许写入，光看异常分不出来。
 *
 * 用户实际只需要"导到 Download 里"，所以整条路删掉了。
 * 详见 `ConfigExporters` 的说明。
 *
 * ⚠️ [MANUAL] 与它**不是**一回事：那是**用户当次自己选位置**，
 * 走 `ACTION_CREATE_DOCUMENT`（系统界面），一直正常。
 * 区别在"预先设一个目录、之后静默写入" —— 那才是被删掉的。
 */
enum class ExportMethod(
    /** 设置页与导出弹窗里显示的名字 */
    val label: String,
) {
    /**
     * 每次询问（即"无默认方式"）。
     *
     * ⚠️ 这个值**同时是默认值**：新装的用户第一次点导出当然应该看到那个窗口，
     * 而不是被一个他从没选过的默认方式替做决定。
     *
     * 用户在弹窗里选过并勾了"设为默认"之后，这个值才会变成别的。
     * 想变回"每次都问"就来设置页选它 —— 这就是"无"的作用。
     */
    ASK("每次询问"),

    /**
     * 静默存到 `Download/sthkeyconfigs/`。
     *
     * ⚠️ 走 **MediaStore**，零权限。这不是"没权限就退而求其次"，
     * 而是这条路的正式做法（见 `ConfigExporters` 的说明）。
     */
    DOWNLOAD("导出到目录"),

    /** 走 SAF，每次自己选存哪（就是原来的行为） */
    MANUAL("手动选择位置"),

    /** 交给系统分享面板（微信 / QQ 等） */
    SHARE("分享"),

    ;

    companion object {
        fun fromId(id: String?): ExportMethod =
            entries.firstOrNull { it.name == id } ?: ASK
    }
}

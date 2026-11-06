package com.something.sthkey.domain.live2d

/**
 * 模型包里**该丢掉哪些文件**。
 *
 * ============================================================
 * ⚠️ 为什么需要这个
 * ============================================================
 * 用户的原话:
 *
 * > 导入的模型应该要自己识别哪些资源有用哪些没用，例如 exe dll 这些，
 * > 本身是 Windows 程序，但是这些导入进来后，也会随着配置一起导出
 *
 * ⚠️ 现实里这些包**本来就是 Windows 版 BongoCat 的发行包**:
 * 实测样本 `鸣潮卡提希娅最牛逼键盘猫模型` 里就有
 * 2 个 `.exe` + 13 个 `.dll` + 1 个 `.docx` + 1 个 `.psd`，
 * 合计约 24 MB，全都跟着进了模型库，导出配置时又被打进包里。
 *
 * ============================================================
 * ⚠️ 为什么用**黑名单**而不是白名单
 * ============================================================
 * 白名单（只留 png/json/moc3…）会误删我们还不认识但模型确实要用的东西:
 * 作者自带的字体、贴图变体、自定义 js、甚至下一个 Cubism 版本的新扩展名。
 * 一旦误删，用户看到的是"模型缺东西"，而且根本不知道该怪谁。
 *
 * 黑名单只删**语义上绝不可能是模型资源**的那些:可执行程序、安装包、
 * 嵌套压缩包、快捷方式、设计源文件、图文说明。
 *
 * ⚠️ 判定只看**后缀**，不看文件名:
 * 一个叫 `texture_00.png` 的文件永远不该被删，哪怕它旁边有个 `texture_00.dll`。
 */
object Live2DJunkFilter {

    /**
     * 要丢掉的后缀（小写，**带点**）。
     *
     * ⚠️ 音频（wav/mp3/flac/ogg）**故意不在**这份名单里:
     * 按键音效是 BongoCat 生态的一部分，虽然我们当前版本不播，
     * 但删掉之后再想支持就得让用户重新导入。
     */
    private val EXTENSIONS: Set<String> = setOf(
        /* Windows / 可执行 */
        ".exe", ".dll", ".bat", ".cmd", ".ps1", ".vbs", ".msi", ".sys",
        ".scr", ".com", ".pif", ".jar",
        /* 嵌套压缩包：解压出来也不会被用到 */
        ".zip", ".rar", ".7z", ".tar", ".gz", ".bz2", ".xz",
        /* 快捷方式 / 下载描述 */
        ".lnk", ".url", ".torrent",
        /* 设计源文件 */
        ".psd", ".psb", ".ai", ".xd", ".sketch", ".fig",
        /* 图文说明 */
        ".docx", ".doc", ".xlsx", ".xls", ".pptx", ".ppt", ".pdf",
        ".txt", ".rtf",
    )

    /** 这个文件名是不是该丢掉 */
    fun isJunk(fileName: String): Boolean {
        val lower = fileName.lowercase()
        /* ⚠️ 用"最后一个点之后"而不是 endsWith:multipart 后缀也能覆盖 */
        val dot = lower.lastIndexOf('.')
        if (dot < 0) return false
        return lower.substring(dot) in EXTENSIONS
    }
}

package com.something.sthkey.data.incoming

/**
 * 外部文件被识别成**什么**。
 *
 * ============================================================
 * ⚠️ 为什么不按后缀判断
 * ============================================================
 * 外部「打开方式 / 分享」时我们只拿到一个 `content://` Uri:
 *
 * | 不可信的东西 | 现实 |
 * |---|---|
 * | 后缀 | 聊天软件常把文件改名成 `xxx.bin`；SAF 也可能给不出名字 |
 * | MIME | `.mcpack` / `.sthkey` 这类自定义后缀，系统一路报 octet-stream |
 *
 * ⚠️ 所以类型是**嗅探出来的**（见 [FileSniffer]），这里只负责
 * "识别出来之后叫什么、合成什么文件名给现有导入器"。
 *
 * @param label 给用户看的类型名（确认框里显示）
 * @param suggestedFileName 合成文件名 —— **这个才是关键**:
 *   现有导入器（`FontRegistry` / `BitmapFontImporter`）**按后缀分派**，
 *   不合成一个带正确后缀的名字给它们，就会静默失败或走错分支。
 *   `null` 表示不需要（配置包/组合包/Live2D 都不看后缀）。
 */
enum class FileKind(
    val label: String,
    val suggestedFileName: String?,
) {
    /** 单份配置包（`.sthkey`） */
    CONFIG_PACKAGE("配置包", null),

    /** 配置组合包（`.sthgroup`） */
    GROUP_PACKAGE("配置组合包", null),

    /** Live2D 模型包（zip 里有 `*.model3.json`） */
    LIVE2D_MODEL("Live2D 模型", null),

    /** Java 版材质包里的图片字体（zip 里有 `ascii.png`） */
    BITMAP_FONT_JAVA("图片字体（Java 版材质包）", "字体.zip"),

    /** 基岩版材质包里的图片字体（`.mcpack` 里有 `default8.png`） */
    BITMAP_FONT_BEDROCK("图片字体（基岩版材质包）", "字体.mcpack"),

    /** 单张图集图片 */
    BITMAP_FONT_IMAGE("图片字体（单张图集）", "字体.png"),

    /** TrueType 矢量字体 */
    VECTOR_FONT_TRUETYPE("字体（TrueType）", "字体.ttf"),

    /** OpenType（CFF）矢量字体 */
    VECTOR_FONT_OPENTYPE("字体（OpenType）", "字体.otf"),

    /** TrueType Collection（一个文件里多套字体） */
    VECTOR_FONT_COLLECTION("字体集合", "字体.ttc"),

    /** 不认识 */
    UNKNOWN("无法识别的文件", null),
    ;

    /** 是否是我们自己的配置/组合包（走原来的导入与报告） */
    val isConfigPackage: Boolean
        get() = this == CONFIG_PACKAGE || this == GROUP_PACKAGE

    /** 是否是字体（矢量或图片） */
    val isFont: Boolean
        get() = when (this) {
            VECTOR_FONT_TRUETYPE, VECTOR_FONT_OPENTYPE, VECTOR_FONT_COLLECTION -> true
            BITMAP_FONT_JAVA, BITMAP_FONT_BEDROCK, BITMAP_FONT_IMAGE -> true
            else -> false
        }

    /** 是否是图片字体（导入后还要让用户确认名字） */
    val isBitmapFont: Boolean
        get() = this == BITMAP_FONT_JAVA || this == BITMAP_FONT_BEDROCK || this == BITMAP_FONT_IMAGE
}

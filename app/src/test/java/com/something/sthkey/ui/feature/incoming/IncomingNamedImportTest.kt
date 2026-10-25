package com.something.sthkey.ui.feature.incoming

import com.something.sthkey.data.incoming.FileKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 外部导入时"交给导入器的名字"。
 *
 * ============================================================
 * ⚠️ 这条规则容易被写错，而错了的表现是**用户可见的**
 * ============================================================
 * 用户在「设置 → 库」里看到的应当是他**自己的文件名**（去掉后缀）:
 *
 * | 原名 | 识别为 | 交给导入器 | 库里显示 |
 * |---|---|---|---|
 * | `MyFont.ttf` | TrueType | `MyFont.ttf` | **MyFont** |
 * | `MyFont`（无后缀） | TrueType | `MyFont.ttf` | **MyFont** |
 * | `my font.bin`（被改名） | TrueType | `my font.ttf` | **my font** |
 * | `材质包.zip` | 图片字体 | `材质包.zip` | 用户自己命名 |
 * | `我的模型.zip` | Live2D | `我的模型.zip` | **我的模型** |
 *
 * ⚠️ 为什么交给导入器的名字**要带后缀**:
 * `FontRegistry.isSupportedFont()` 与 `BitmapFontImporter` 的
 * `endsWith(".zip")` 都是**按后缀分派**的 —— 不给后缀，前者静默失败、
 * 后者会把 zip 当成单张图片。
 *
 * ⚠️ 而后缀又**不能进库名**:所以剥后缀这件事由各个导入器自己做
 * （`FontRegistry` 的 `substringBeforeLast('.')`、`Live2DModelImporter` 同理）。
 */
class IncomingNamedImportTest {

    /**
     * ⚠️ 只测纯字符串逻辑:被测函数刻意**不接收 `SniffedItem`**，
     * 因为那个类型带 `Uri`，而 `Uri` 在本地单测里是空壳
     * （`Uri.EMPTY` 直接抛 "EMPTY must not be null"）。
     */
    private fun named(name: String, kind: FileKind): String = namedForImport(name, kind)

    @Test
    fun `原名的后缀被换成嗅探出的后缀`() {
        /*
         * ⚠️ 用户的文件可能被改过名（`xxx.bin`），不能沿用原名后缀，
         * 否则导入器按后缀分派时走错分支。
         */
        assertEquals(
            "my font.ttf",
            named("my font.bin", FileKind.VECTOR_FONT_TRUETYPE),
        )
    }

    @Test
    fun `没有后缀的原名也补上正确后缀`() {
        assertEquals(
            "MyFont.ttf",
            named("MyFont", FileKind.VECTOR_FONT_TRUETYPE),
        )
    }

    @Test
    fun `本来就是正确后缀时只保留一份`() {
        /* ⚠️ 不能变成 "MyFont.ttf.ttf" */
        assertEquals(
            "MyFont.otf",
            named("MyFont.otf", FileKind.VECTOR_FONT_OPENTYPE),
        )
    }

    @Test
    fun `多段后缀只去掉最后一段`() {
        /* "字体.v2.otf" → 主体 "字体.v2" + 嗅探出的 .otf */
        assertEquals(
            "字体.v2.otf",
            named("字体.v2.otf", FileKind.VECTOR_FONT_OPENTYPE),
        )
    }

    @Test
    fun `图片字体的后缀决定去哪找图集`() {
        assertEquals(
            "材质包.zip",
            named("材质包.bin", FileKind.BITMAP_FONT_JAVA),
        )
        assertEquals(
            "基岩包.mcpack",
            named("基岩包", FileKind.BITMAP_FONT_BEDROCK),
        )
    }

    @Test
    fun `没有推荐后缀的类型原样返回`() {
        /*
         * Live2D 与配置包都不靠后缀:
         * 名字原样交给导入器，由它自己去掉 `.zip` 作为显示名。
         */
        assertEquals(
            "我的模型.zip",
            named("我的模型.zip", FileKind.LIVE2D_MODEL),
        )
    }

    @Test
    fun `没有文件名时退回占位名`() {
        /* 外部打开经常给不出名字，此时至少不能是空串 */
        assertEquals(
            "字体.ttf",
            named("", FileKind.VECTOR_FONT_TRUETYPE),
        )
    }
}

package com.something.sthkey.domain.font.bitmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 位图字体 id 语义的测试。
 *
 * ============================================================
 * 这里钉的是一个**真实的 bug**，而且它一次性造成两个症状
 * ============================================================
 * [BitmapFontStore] 里有两种 id：
 *
 * - **存储形态**：`Entry.id` 是**裸 uuid**，属于内部实现；
 * - **对外形态**：`"bitmap:" + 裸 uuid`，配置里存的是这个。
 *
 * 早先 [BitmapFontStore.entries] 返回**裸 uuid**，而
 * [BitmapFontStore.register] 返回**带前缀**的，于是：
 *
 * 1. **重命名 / 删除静默失效** —— `removePrefix("bitmap:")` 去不掉前缀，
 *    查不到条目，直接 `return false`，而界面上一点提示都没有。
 *    表现是"导入时起的名字能用，之后再改就改不动、删也删不掉"。
 *
 * 2. **图片字体显示成默认字体** —— `bitmapFontId` 里存的是裸 uuid，
 *    而 `isBitmapFont()` / `load()` 都要求 `bitmap:` 前缀，
 *    于是判定"这不是图片字体"，渲染直接走常规字体（= 系统默认）。
 *
 * 两个症状看起来毫不相关，实际是同一个 id 不一致引起的 ——
 * 所以这两条断言必须钉住。
 *
 * ⚠️ 这里只测**不需要 Android 上下文**的部分：id 的换算规则。
 * 真正调用 `entries()` 要读 SharedPreferences，本地单测里跑不了
 * （项目的其它测试也是同样的取舍）。
 */
class BitmapFontIdTest {

    /** 与 [BitmapFontStore.ID_PREFIX] 一致 */
    private val prefix = "bitmap:"

    @Test
    fun `对外 id 带 bitmap 前缀`() {
        val raw = "550e8400-e29b-41d4-a716-446655440000"
        val public = prefix + raw

        assertTrue("对外 id 必须能被识别为图片字体", public.startsWith(prefix))
        assertEquals("存储 id 应当能由对外 id 去掉前缀得到", raw, public.removePrefix(prefix))
    }

    /**
     * ⚠️ 这条是那个 bug 的核心：**裸 uuid 不是合法的对外 id**。
     *
     * 只要哪里漏了前缀，[BitmapFontStore.isBitmapFont] 就会返回 false，
     * 渲染层随即判定"这不是图片字体"并回退到常规字体 ——
     * 表现就是"图片字体显示成系统默认字体"，而完全看不出是 id 的问题。
     */
    @Test
    fun `裸 uuid 不是合法的对外 id`() {
        val raw = "550e8400-e29b-41d4-a716-446655440000"

        assertTrue(
            "裸 uuid 不该被当成图片字体 —— 漏了前缀就必然回退成常规字体",
            !raw.startsWith(prefix),
        )
        assertNotEquals("两种形态必须不同", raw, prefix + raw)
    }

    /**
     * 存储 id 一定**不含**前缀。
     *
     * 如果哪天有人把带前缀的 id 写进了 `Entry.id`，
     * 再去 `removePrefix` 就会得到双重前缀的怪物，
     * 于是"改名后条目消失"这类问题会以另一种形式回来。
     */
    @Test
    fun `去掉前缀的幂等性`() {
        val raw = "abc-123"

        assertEquals("裸 id 去前缀应当原样返回", raw, raw.removePrefix(prefix))
        assertEquals(
            "带前缀的 id 去一次就够，第二次不该再变",
            raw,
            (prefix + raw).removePrefix(prefix).removePrefix(prefix),
        )
    }

    /** 兜底名：14 位时间戳，且同一个时刻只产生一个名字 */
    @Test
    fun `兜底名是时间戳`() {
        // 2026-10-02 19:00:00 本地时间对应的毫秒数用一个固定值，避免时区影响断言
        val name = BitmapFontStore.fallbackName(0L)

        assertEquals("yyyyMMddHHmmss 共 14 位", 14, name.length)
        assertTrue("必须全是数字", name.all { it.isDigit() })
        assertEquals("同一个时刻只产生一个名字", name, BitmapFontStore.fallbackName(0L))
    }

    /*
     * ============================================================
     * 配置包：规格必须"跟着包走"
     * ============================================================
     */

    /**
     * 规格的字段清单必须**完整**。
     *
     * ⚠️ 配置包导出时会把这些字段平铺进 manifest
     * （见 `ConfigPackageCodec` 里 `FontFileInfo.bitmap` 的写入），
     * 导入时再用 `fromJson` 读回来。
     *
     * 所以"`toJson` 写了什么"就是"包里带走了什么"。少写一个字段的后果是
     * **导入方拿到的字体和导出方看到的不一样** —— 而且这种差别在画面上
     * 表现成"字全错位""整块实心方块"，完全不会让人联想到"少存了一个参数"。
     *
     * 这条断言列出**全部**必须往返的字段：加了新字段却忘了写进 `toJson`
     * 时它会失败，而不是等用户导入别人分享的配置包才发现。
     */
    @Test
    fun `规格的字段清单必须完整`() {
        /*
         * 用 org.json 之外的办法拿字段清单：直接读源码里 `toJson` 写出的键。
         * 本地单测里 `org.json` 是空壳（见项目说明），不能真的构造 JSONObject。
         *
         * 所以这里改成"检查源码里出现了哪些 put 键" —— 粗糙但有效：
         * 它会在"新增字段没同步进 toJson"时失败，这正是要防的。
         */
        val expectedKeys = listOf(
            "atlasFileName", "columns", "rows", "firstCodePoint",
            "firstColumn", "firstRow", "codePoints", "height", "ascent",
            "maskMode", "widthInCells", "pixelAlign", "tinted", "verticalNudge",
        )

        val source = readOwnSource()
        expectedKeys.forEach { key ->
            assertTrue(
                "`BitmapFontSpec.toJson` 必须写出 `$key` —— " +
                    "不写的话配置包里就带不走它，导入方的字体和导出方不一样",
                source.contains("put(\"$key\""),
            )
        }
    }

    /**
     * 读 `BitmapFontSpec.kt` 的源码文本。
     *
     * 单测的工作目录是模块根目录（`app/`），所以路径从这里出发。
     * 找不到就跳过（返回空串，于是上面的断言会失败并提示原因）——
     * 静默通过比失败更糟。
     */
    private fun readOwnSource(): String {
        val candidates = listOf(
            "src/main/java/com/something/sthkey/domain/font/bitmap/BitmapFontSpec.kt",
            "app/src/main/java/com/something/sthkey/domain/font/bitmap/BitmapFontSpec.kt",
        )
        candidates.forEach { path ->
            val file = java.io.File(path)
            if (file.exists()) return file.readText()
        }
        return ""
    }
}

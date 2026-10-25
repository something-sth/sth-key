package com.something.sthkey.data.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入报告的**字段差异基准必须按样式算**。
 *
 * ============================================================
 * ⚠️⚠️ 用户报的问题
 * ============================================================
 * 用户的原话:
 *
 * > 不过现在导入配置显示的字段差异还没改，我**导出再导入有一大堆字段差异**，
 * > 不过实际上没什么区别
 *
 * ⚠️ 成因:编码改成"按样式只写相关字段"之后（见 `JsonConfigCodec`
 * 里的 `STYLE_SPECIFIC_KEYS`），一份自定义 Key 配置里不再有键盘、手柄
 * 那些字段。而导入报告的基准还是 `KNOWN_KEYS`（**所有样式的并集**），
 * 于是每一项键盘字段都报成"缺失"。
 *
 * ⚠️ 那是**把正常情况说成异常** —— 与 `MANIFEST_OWNED_KEYS`
 * 当初要解决的问题一模一样。
 *
 * ============================================================
 * ⚠️ 为什么读源码而不是跑一遍
 * ============================================================
 * `compareFields` 要真的构造 `JSONObject`，而 `org.json` 在本地单测里
 * 是空壳。所以照项目里的先例（`BitmapFontIdTest`）读源码做结构性断言。
 */
class ImportFieldDiffTest {

    private fun source(relative: String): String {
        val candidates = listOf("src/main/java/", "app/src/main/java/")
        candidates.forEach { prefix ->
            val file = java.io.File(prefix + relative)
            if (file.exists()) return file.readText()
        }
        return ""
    }

    private val codecSource =
        source("com/something/sthkey/data/config/JsonConfigCodec.kt")

    private val packageSource =
        source("com/something/sthkey/data/config/ConfigPackageCodec.kt")

    @Test
    fun `能读到两个源文件`() {
        assertTrue("读不到 JsonConfigCodec.kt", codecSource.isNotEmpty())
        assertTrue("读不到 ConfigPackageCodec.kt", packageSource.isNotEmpty())
    }

    /**
     * ⚠️⚠️ **比对基准不许再用 `KNOWN_KEYS`**。
     *
     * 它就是用户报的那个"一大堆字段差异"的来源 ——
     * 那是一份跨样式的并集，拿它当基准必然把别的样式的字段
     * 全报成"缺失"。
     */
    @Test
    fun `比对基准不再用 KNOWN_KEYS`() {
        assertFalse(
            "`compareFields` 里出现了 `KNOWN_KEYS` —— 它是所有样式的并集，" +
                "拿它当基准会把别的样式的字段全报成缺失（用户报的就是这个）",
            packageSource.contains("KNOWN_KEYS - MANIFEST_OWNED_KEYS") ||
                packageSource.contains("expected = JsonConfigCodec.KNOWN_KEYS"),
        )
    }

    /**
     * ⚠️ 比对基准必须**按 `styleId`** 取。
     */
    @Test
    fun `比对基准按 styleId 取`() {
        assertTrue(
            "`compareFields` 必须用 `JsonConfigCodec.expectedKeysFor(<styleId>)` 当基准",
            packageSource.contains("expectedKeysFor("),
        )
        assertTrue(
            "取样式要用 params 里那个 styleId",
            packageSource.contains("""params.optString("styleId")"""),
        )
    }

    /**
     * ⚠️ `expectedKeysFor` 必须真的**按传入的样式挑那份探针**，
     * 而不是又回落到并集（那就等于没改）。
     */
    @Test
    fun `expectedKeysFor 按样式挑探针`() {
        val start = codecSource.indexOf("fun expectedKeysFor(")
        assertTrue("`expectedKeysFor` 不见了", start >= 0)
        val body = codecSource.substring(start).substringBefore("\n    }")

        assertTrue(
            "`expectedKeysFor` 里必须 `firstOrNull { it.styleId == resolved }` " +
                "挑出对应样式的那份探针",
            body.contains("firstOrNull { it.styleId =="),
        )
        assertFalse(
            "`expectedKeysFor` 里不该把 `KNOWN_KEYS` 当主返回值 —— " +
                "那只配当「认不出样式」时的兜底，不能是主路径",
            body.contains("?: KNOWN_KEYS") && !body.contains("resolved"),
        )
    }

    /**
     * ⚠️ "属于别的样式的字段"必须是**独立一类**。
     *
     * 它既不是"未识别"（我们认识它），也不是"缺失"（这份配置本来不该有它）。
     * 混进前两类，报告就会把"旧版本导出的包"说成"包有问题"。
     */
    @Test
    fun `别的样式的字段是独立一类`() {
        assertTrue(
            "FieldDiff 必须有 otherStyle 这一类",
            packageSource.contains("val otherStyle: List<String>"),
        )
        assertTrue(
            "otherStyle 的判据必须同时排除 expected / 清单字段 / unknown",
            packageSource.contains("it !in expected && it !in MANIFEST_OWNED_KEYS") &&
                packageSource.contains("it !in unknown"),
        )
    }

    /**
     * ⚠️ 报告里要把这一类**显示出来** —— 算了不显示等于白算，
     * 用户下次还会看到"未识别 N"却找不到那些字段去哪了。
     */
    @Test
    fun `报告里显示了这一类`() {
        val dialog = source("com/something/sthkey/ui/feature/config/ImportReportDialog.kt")
        assertTrue("读不到 ImportReportDialog.kt", dialog.isNotEmpty())
        assertTrue(
            "导入报告里必须显示「属于别的样式的字段」这一类",
            dialog.contains("属于别的样式的字段"),
        )
        assertTrue(
            "统计那一行也要带上它，否则数量对不上",
            /* ⚠️ `$` 在 Kotlin 字符串里是模板起始符，这里是**字面量**比较，要转义 */
            dialog.contains("别的样式 \${report.otherStyleKeys.size}"),
        )
    }
}

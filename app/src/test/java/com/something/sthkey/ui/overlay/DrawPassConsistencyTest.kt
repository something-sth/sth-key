package com.something.sthkey.ui.overlay

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 源码级检查：**同一段文字的多次绘制必须传同一套参数**。
 *
 * ============================================================
 * 为什么需要这种"读自己源码"的测试
 * ============================================================
 * 键面文字要画**三遍**（阴影本体 / 抠洞 / 正文），三遍都调同一个函数。
 * 这个函数有几个"影响字形落点或宽度"的参数，
 * 只要有一遍漏传，它就会用默认值 —— 而默认值往往等于"关掉"：
 *
 * | 漏传 | 症状 |
 * |---|---|
 * | 字间距 / 行间距 | **调了完全没反应**（用户实测："CPS 模式 3 的 LMB/RMB 调不动间距，转成自定义 Key 才生效"） |
 * | 逐行字号 | 副行和主行一样大 |
 * | 抠洞层没对齐 | 文字轮廓抠不干净，边上留一圈毛边 |
 *
 * 这些都是**编译期看不出来**的：参数有默认值就合法，
 * 没默认值也只是"少传一个"，不会指向"你漏了另一条调用路径"。
 *
 * 所以用一条源码级断言把它钉住 —— 粗糙但有效：
 * **同一个函数在同一个文件里的每一处调用，都必须提到这些参数名。**
 *
 * ============================================================
 * 与"必填参数"的分工
 * ============================================================
 * 更好的防线是把参数做成**必填**（去掉默认值）—— 那样漏传直接编译失败，
 * 实测报的是 `No value passed for parameter 'letterSpacingPercent'`。
 * `KeyLabelText` 的间距参数就是这么做的（见它的声明）。
 *
 * 那这条测试还有用吗？**兜住那些做不成必填的参数**：
 * 有的参数确实需要默认值（比如"只对某一条路径生效"的开关），
 * 那时编译期不会再报警，只有这条测试能发现漏传。
 *
 * ⚠️ 改了绘制代码、加了新的绘制遍数时如果这条测试挂了，
 * 不要删测试，去把漏的那一处补上。
 */
class DrawPassConsistencyTest {

    private val overlayDir =
        "src/main/java/com/something/sthkey/ui/overlay"

    /**
     * 这些参数会改变字形的**落点或宽度**，三遍绘制必须一致。
     */
    private val criticalParams = listOf(
        "letterSpacingPercent",
        "lineSpacingPercent",
    )

    @Test
    fun `KeyLabelText 的每一处调用都要传间距参数`() {
        assertEveryCallMentions(
            fileName = "KeyGrid.kt",
            functionName = "KeyLabelText",
            params = criticalParams,
        )
    }

    @Test
    fun `ComponentText 的每一处绘制调用都要传间距参数`() {
        assertEveryCallMentions(
            fileName = "CustomKeyGrid.kt",
            functionName = "ComponentText",
            params = criticalParams,
        )
    }

    /**
     * 检查 [fileName] 里对 [functionName] 的**每一处调用**
     * （不含定义、不含注解/注释行）都提到了 [params] 里的每个名字。
     */
    private fun assertEveryCallMentions(
        fileName: String,
        functionName: String,
        params: List<String>,
    ) {
        val text = readSource(fileName)
        val callSites = findCallSites(text, functionName)

        assertTrue(
            "$fileName 里没找到 $functionName 的调用 —— " +
                "要么函数改名了，要么这条测试的匹配规则过时了（请修测试，别删）",
            callSites.isNotEmpty(),
        )

        callSites.forEachIndexed { index, body ->
            params.forEach { param ->
                assertTrue(
                    "$fileName 里 $functionName 的第 ${index + 1} 处调用没提到 `$param`。\n" +
                        "⚠️ 同一个函数有多条调用路径（阴影 / 抠洞 / 正文）时，" +
                        "参数必须**每一处都传** —— 漏一处不会编译失败，" +
                        "只会表现为「调了没反应」或「抠洞错位」。\n" +
                        "--- 该处调用的内容 ---\n$body",
                    body.contains(param),
                )
            }
        }
    }

    /**
     * 找出一段源码里对 [functionName] 的所有**调用**（不含定义）。
     *
     * 做法：按行扫描，找"行尾是 `函数名(`"的行当作调用起点
     * （定义写成 `private fun 函数名(`，会被开头关键字排除），
     * 然后按括号配平取到调用的闭合处。
     *
     * 注释行会被跳过 —— 否则注释里提一句函数名就会被当成调用。
     */
    private fun findCallSites(text: String, functionName: String): List<String> {
        val lines = text.lines()
        val sites = mutableListOf<String>()

        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()
            val isComment = trimmed.startsWith("*") ||
                trimmed.startsWith("//") ||
                trimmed.startsWith("/*")

            if (isComment) return@forEachIndexed

            /*
             * 调用形如 `            KeyLabelText(` —— 行尾就是函数名加左括号。
             * 定义形如 `private fun KeyLabelText(` —— 含 `fun`，排除掉。
             */
            if (!trimmed.endsWith("$functionName(")) return@forEachIndexed
            if (trimmed.contains("fun ")) return@forEachIndexed

            var depth = 0
            var end = index
            for (i in index until minOf(index + 120, lines.size)) {
                depth += lines[i].count { it == '(' } - lines[i].count { it == ')' }
                if (depth <= 0 && i > index) {
                    end = i
                    break
                }
            }
            sites += lines.subList(index, end + 1).joinToString("\n")
        }

        return sites
    }

    /**
     * 读源码文件。
     *
     * ⚠️ 单测的工作目录是**模块根目录**（`app/`），
     * 但不同 Gradle 版本下也可能是仓库根 —— 两个位置都试，
     * 都找不到就**失败并说清原因**（静默通过比失败更糟）。
     */
    private fun readSource(fileName: String): String {
        val candidates = listOf(
            "$overlayDir/$fileName",
            "app/$overlayDir/$fileName",
        )
        candidates.forEach { path ->
            val file = File(path)
            if (file.exists()) return file.readText()
        }
        error("找不到源码 $fileName（试过：${candidates.joinToString()}）")
    }
}

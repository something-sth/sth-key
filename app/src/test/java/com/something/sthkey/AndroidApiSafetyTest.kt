package com.something.sthkey

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 源码里的 **Android API 安全**守卫。
 *
 * ============================================================
 * ⚠️ 这个测试防的是一次真实的线上闪退
 * ============================================================
 * 用户发来的日志:
 *
 * ```
 * java.lang.NoSuchMethodError: No virtual method removeLast()Ljava/lang/Object;
 *   in class Ljava/util/ArrayList;
 *     at ... KeyLayout.keys(...)
 * ```
 *
 * **`List.removeLast()` 是 Java 21 才加进 `java.util.List` 的默认方法**，
 * 而 Android 的 `java.util.ArrayList` 从来没有它（libcore 不是 OpenJDK）。
 *
 * ⚠️ 最毒的地方是**四种检查全都拦不住**:
 *
 * | 检查 | 为什么拦不住 |
 * |---|---|
 * | 编译 | JDK 25 的 `List` 上有这个方法，写出来完全合法 |
 * | 单元测试 | **JVM 上确实有** `removeLast`，测试跑得通、还跑得对 |
 * | `jvmTarget = 11` | 只改字节码版本，不改解析时用的类库 |
 * | `-Xjdk-release=11` | 只管源码看到的 API；`removeLast` 走的是 stdlib 扩展，那个扩展自己的字节码里就调了 `java.util.List.removeLast()` |
 * | Android Lint | 实测**不报**（它的 SDK 存根没把这条标成新 API） |
 *
 * 而且它**不是每次启动都崩**:`keys()` 里那些调用全在
 * "显示鼠标键 / 肩键 / A / 空格 / Shift"的分支里 ——
 * 配置不同就走不到，所以开发者自己复现不出来。
 *
 * ⚠️ 所以只能**在源码层面**扫。这个测试是唯一的防线。
 *
 * ============================================================
 * 为什么扫源码而不是扫字节码
 * ============================================================
 * 扫字节码更彻底，但要先把整个模块编译一遍、再逐个 class 反编译 ——
 * 而这个模块有 400 多个 class，在单测里做那件事既慢又脆
 * （class 路径、变体名都会变）。扫源码一行正则就够，
 * 而且报错信息能**直接指到文件和行号**。
 *
 * 代价是它只能发现字面写法（`x.removeLast()`）。这是可接受的:
 * 没有人会为了绕开这个测试而反射调用它。
 */
class AndroidApiSafetyTest {

    /**
     * ⚠️ Android 上**不存在**、但 JDK 21+ 上有的方法。
     *
     * 这些是 Java 21 的 `SequencedCollection` 给 `List` 加上的默认方法，
     * libcore 一个都没实现。
     *
     * ⚠️ `removeFirst` 在 `kotlin.collections.ArrayDeque` 上是**安全**的
     * （Kotlin 自己的类，不涉及 `java.util.List`）—— 见 `CpsCounter`。
     * 所以下面报错时要说清"换成什么"，而不是让人以为是禁用词。
     */
    private val forbidden = listOf(
        "removeLast",
        "removeFirst",
        "getFirst",
        "getLast",
    )

    /**
     * 扫描 `app/src/main/java` 下所有 .kt。
     *
     * ⚠️ 用 `user.dir` 定位而不是相对路径:单测的工作目录是**模块目录**
     * （`app/`），不同 Gradle 版本/IDE 下可能不同，写死相对路径会变成
     * "在 IDE 里能过、命令行下报找不到目录"。
     */
    @Test
    fun `源码里没有 Android 上不存在的 Java 21 集合方法`() {
        val sourceRoot = File(System.getProperty("user.dir"), "src/main/java")
        assertTrue(
            "找不到源码目录（找的是 $sourceRoot）—— " +
                "单测的工作目录应当是本模块目录（app/）",
            sourceRoot.isDirectory,
        )

        val offenders = mutableListOf<String>()

        sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                /*
                 * ⚠️ **必须把注释排除掉**，否则这份守卫会抓自己 ——
                 * `KeyLayout` 里就用 KDoc 写着 `boxes.removeLast()`
                 * （那是解释"为什么不能用它"）。第一次跑就被抓了 3 行。
                 *
                 * 用"括号深度"跟踪块注释太容易写错（定界符出现在字符串里、
                 * KDoc 嵌套…）。这里用最笨但最稳的办法:
                 * 先把块注释整段切掉，再逐行去掉双斜杠之后的部分。
                 *
                 * ⚠️ 这段注释本身也**不能**出现那对定界符 ——
                 * Kotlin 的块注释是**可嵌套**的，写了就会把后面的代码
                 * 一起吞进注释里（写这个文件时踩了三次，编译器只报
                 * "Unclosed comment"）。
                 */
                val withoutBlockComments = stripBlockComments(file.readText())

                withoutBlockComments.lines().forEachIndexed { index, rawLine ->
                    val line = rawLine.substringBefore("//")

                    forbidden.forEach { name ->
                        if (line.contains(".$name()")) {
                            offenders += "${file.relativeTo(sourceRoot)}:${index + 1}  →  $name()"
                        }
                    }
                }
            }

        assertTrue(
            buildString {
                appendLine("源码里出现了 Android 上不存在的方法:")
                offenders.forEach { appendLine("  $it") }
                appendLine()
                appendLine("这些都是 Java 21 的 SequencedCollection 给 List 加的默认方法，")
                appendLine("Android 的 ArrayList **没有实现**它们 —— 编译过、单测过，")
                appendLine("只有在真机上跑到那一行才抛 NoSuchMethodError（线上闪退）。")
                appendLine()
                appendLine("换成这些（从 Java 1.2 起就有）:")
                appendLine("  .removeLast()   →  .removeAt(lastIndex)")
                appendLine("  .removeFirst()  →  .removeAt(0)")
                appendLine("  .getFirst()     →  .first()")
                appendLine("  .getLast()      →  .last()")
                appendLine()
                appendLine("⚠️ 例外:kotlin.collections.ArrayDeque 上的 removeFirst/removeLast")
                appendLine("是 KOTLIN 自己的类，不涉及 java.util.List，那些是安全的 ——")
                appendLine("但为简单起见这里一并禁止，改用 removeAt(0) / removeAt(lastIndex) 即可。")
            },
            offenders.isEmpty(),
        )
    }

    /**
     * 把块注释与 KDoc 整段换成空白。
     *
     * 起止定界符由下面两个局部变量拼出来，**不在这里原样写** ——
     * 写了的话这段 KDoc 会被自己提前闭合（写它时踩过两次，
     * 编译器报 Unclosed comment）。
     *
     * 保留换行数（用等量空行替换），否则报出来的行号会和编辑器里
     * 看到的对不上，而"行号对不上"会让这条守卫的报错失去价值。
     *
     * 手写而不是上正则:块注释不嵌套，一个简单扫描就够。
     * 这里也不需要处理"字符串字面量里出现定界符"这种极端情况 ——
     * 真出现了也只是漏报，不会误报。
     */
    private fun stripBlockComments(text: String): String {
        val open = "/" + "*"
        val close = "*" + "/"
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            if (text.startsWith(open, i)) {
                val end = text.indexOf(close, i + open.length)
                if (end < 0) break
                text.substring(i, end + close.length).forEach { ch ->
                    if (ch == '\n') out.append('\n')
                }
                i = end + close.length
            } else {
                out.append(text[i])
                i++
            }
        }
        return out.toString()
    }
}

package com.something.sthkey.ui.overlay

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 触屏的"按了要亮"这件事 —— 靠**源码判据**钉住。
 *
 * ============================================================
 * ⚠️⚠️ 为什么这类断言必须存在（这里踩过一个很隐蔽的坑）
 * ============================================================
 * 用户报的现象:
 *
 * > 轮盘问题解决了但是 **A 键和其他配置的键亮不起来**，这是渲染的问题吗？
 *
 * ⚠️ 是渲染的问题，而且成因很绕:
 *
 * | 环节 | 事实 |
 * |---|---|
 * | 键帽点亮的判据 | `pressed = box.codes.any { it in pressedCodes }` |
 * | `box.codes` 从哪来 | `KeyLayout.codeToSlotMap(config)` —— 只从 `keyMappings.inputKeyCodes` 建 |
 * | 触屏的 `inputKeyCodes` | **全是空的**（触屏不读物理按键） |
 * | 于是 | `box.codes` 是空集 → `any {}` 永远 false → **永远不亮** |
 *
 * ⚠️ 根子上的错:我第一版把触摸区域按下的**槽位**翻译成**键码**再并进
 * `pressedCodes` —— 而触屏**根本没有键码**（输入是坐标）。
 * 硬要挤进"键码"这条通道，就等于要求一个不存在的翻译。
 *
 * ⚠️ 修法是**另开一条路**:直接比槽位 `box.slotId in pressedSlots`。
 *
 * ============================================================
 * ⚠️ 为什么用源码判据而不是跑起来测
 * ============================================================
 * 这两个判据都在 **Compose 的 `@Composable` 函数体**里，
 * 而 JVM 单测起不了 Compose 运行时（项目里没有 instrumentation 测试）。
 *
 * ⚠️ 所以读源码、断言那两行**确实存在**。这保不住"逻辑真的对"，
 * 但能保住"没有人把它删掉/改回只按键码" —— 而那正是这次的事故形态。
 * 先例:`BitmapFontIdTest.readOwnSource()`。
 */
class TouchKeyLightingTest {

    private fun readSource(relative: String): String {
        val candidates = listOf(
            File("app/src/main/java/com/something/sthkey/$relative"),
            File("src/main/java/com/something/sthkey/$relative"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到源码文件:$relative（工作目录=${File(".").absolutePath}）")
        return file.readText()
    }

    /**
     * ⚠️⚠️ 最要紧的一条:`OverlayKeySlot` 必须**同时**看键码与槽位。
     *
     * 只按键码 → 触屏永远不亮（这次的事故）；
     * 只按槽位 → 手柄/键盘那套物理按键永远不会亮。
     */
    @Test
    fun `键帽点亮同时看键码与槽位`() {
        val source = readSource("ui/overlay/KeyGrid.kt")

        assertTrue(
            "`OverlayKeySlot` 里的点亮判据必须同时包含键码与槽位两条路。\n" +
                "⚠️ 只按键码的话触屏永远不亮（触屏的 inputKeyCodes 是空的）；" +
                "只按槽位的话物理按键永远不亮。",
            source.contains("box.codes.any { it in pressedCodes }") &&
                source.contains("box.slotId in pressedSlots"),
        )
    }

    /** `OverlayKeySlot` 要有 `pressedSlots` 这个参数（否则调用方传不进来） */
    @Test
    fun `键帽组件收槽位参数`() {
        val source = readSource("ui/overlay/KeyGrid.kt")

        assertTrue(
            "`OverlayKeySlot` 必须有 `pressedSlots: Set<String>` 参数",
            source.contains("pressedSlots: Set<String>"),
        )
        assertTrue(
            "`pressedSlots` 该有默认值 `emptySet()` —— " +
                "否则键盘/手柄样式那些不传它的调用点全都要改",
            source.contains("pressedSlots: Set<String> = emptySet()"),
        )
    }

    /** 触屏渲染器要把槽位**转交给** `OverlayKeySlot` */
    @Test
    fun `触屏渲染器把槽位传下去`() {
        val source = readSource("ui/overlay/gamepad/TouchContent.kt")

        assertTrue(
            "`TouchContent` 要有 `pressedSlots` 参数",
            source.contains("pressedSlots: Set<String> = emptySet()"),
        )
        /*
         * ⚠️ 断言"它在调用 OverlayKeySlot 时传了 pressedSlots"。
         * 参数收下却忘了往下传，现象与没修一模一样。
         */
        val callIndex = source.indexOf("OverlayKeySlot(")
        assertTrue("TouchContent 里应当调用 OverlayKeySlot", callIndex >= 0)
        val callBody = source.substring(callIndex, minOf(callIndex + 600, source.length))
        assertTrue(
            "调用 `OverlayKeySlot` 时必须把 `pressedSlots` 传下去 —— " +
                "收下参数却忘了传，现象和没修一样",
            callBody.contains("pressedSlots = pressedSlots"),
        )
    }

    /**
     * ⚠️ `OverlayContent` 必须**直接传槽位**，而不是翻译成键码。
     *
     * 翻成键码那条路对触屏是死的（`box.codes` 空集），
     * 所以这里反向断言:不该再把触摸的槽位喂进 `pressedCodes`。
     */
    @Test
    fun `悬浮窗不把触摸翻译成键码`() {
        val source = readSource("ui/overlay/OverlayContent.kt")

        assertTrue(
            "触屏分支要把按住的槽位传给 `TouchContent`",
            source.contains("pressedSlots = touchSnapshot.pressedSlots"),
        )
        assertTrue(
            "触屏分支要经 `advance` 拿到这一帧的快照",
            source.contains("tracker.advance("),
        )
        assertTrue(
            "⚠️ 不该再把触摸槽位经 keyMappings 翻成键码并进 pressedCodes —— " +
                "那条路对触屏是死的（box.codes 是空集）。",
            !source.contains("pressedCodes = pressedKeys + touchCodes"),
        )
    }

    /**
     * ⚠️⚠️ **状态推进必须在组合期**，不能在 `LaunchedEffect` 里。
     *
     * ============================================================
     * 这条断言守的是一个已经发生过的事故
     * ============================================================
     * 用户实测报的现象:
     *
     * > 我按下 LT 的位置，**不会有变化，我松手-LT 亮起，再按下-LT 熄灭**
     *
     * ⚠️ 那是因为原来写成:
     *
     * ```kotlin
     * val pressedSlots = remember(frameSeq) { tracker.pressedSlots() }  // 组合期读 → 上一帧
     * LaunchedEffect(frameSeq) { tracker.onFrame(...) }                 // 组合后才跑
     * ```
     *
     * `LaunchedEffect` 在**组合完成之后**执行，于是画出来的永远比手指
     * 慢一帧。Axon 那边是 `setPressedMask(mask)` + `invalidate()`，
     * 同一帧内完成。
     *
     * ⚠️ 所以这里断言:触屏分支**不再**用 `LaunchedEffect` 推进状态。
     */
    @Test
    fun `触屏状态在组合期推进而不是在 effect 里`() {
        val source = readSource("ui/overlay/OverlayContent.kt")

        /*
         * ⚠️ 只看触屏那一段，按**行**切。
         *
         * ⚠️ 我第一版用 `indexOf("StyleId.TOUCHING")` / `indexOf("StyleId.CUSTOM_KEY")`
         * 取前后边界 —— 两次都**先命中注释里提到它们的行**
         * （`StyleId.CUSTOM_KEY` 在上面的 CPS 判断里出现过一次，
         * 位置比触屏分支还靠前），于是切片区间是反的、直接抛异常。
         *
         * ⚠️ 改成按行找:起点是那一行 `} else if (...) {`，
         * 终点是**它之后**第一个以 `} else if` 开头的行。
         * 不依赖行号，也不怕注释里提到这些名字。
         */
        val lines = source.lines()
        val startLine = lines.indexOfFirst {
            it.trimStart().startsWith("} else if (current.styleId == StyleId.TOUCHING)")
        }
        assertTrue(
            "找不到触屏分支的起点（`} else if (current.styleId == StyleId.TOUCHING) {`）",
            startLine >= 0,
        )
        val endLine = lines.withIndex()
            .drop(startLine + 1)
            .firstOrNull { it.value.trimStart().startsWith("} else if") }
            ?.index
            ?: lines.size
        val touchBlock = lines.subList(startLine, endLine).joinToString("\n")

        /*
         * ⚠️⚠️ 必须**先剥掉注释**再查 `LaunchedEffect`。
         *
         * 我在那段代码上写了很长一段注释解释"原来错在 `LaunchedEffect` 里"，
         * 于是朴素的 `contains("LaunchedEffect")` **命中的是注释**
         * —— 这条断言会永远红，而红的原因与它要守的东西毫无关系。
         * （我连着被它骗了两次才反应过来。）
         */
        val code = stripComments(touchBlock)

        assertTrue(
            "⚠️ 触屏分支里不该用 `LaunchedEffect` **推进触摸状态** —— " +
                "它在组合完成之后才跑，会让画面永远慢一帧" +
                "（现象:按下不亮、松手才亮）。改成在组合期 `tracker.advance(...)`。\n" +
                "⚠️ 注意这里查的是 `onFrame` / `advance` 这两个**推进**动作，" +
                "不是「不许出现 LaunchedEffect」 —— 记 CPS 那种副作用本来就该放 effect 里。",
            !code.contains("tracker.onFrame("),
        )
        assertTrue(
            "触屏分支必须在组合期调 `tracker.advance(`",
            code.contains("tracker.advance("),
        )
    }

    /**
     * 去掉行注释与块注释。
     *
     * ⚠️ 只做到"够用":本项目这个文件里没有嵌在字符串里的 `//`，
     * 也没有嵌套块注释。真要做完整解析就得上 Kotlin 词法器，
     * 那对一条断言来说太重了。
     */
    private fun stripComments(source: String): String {
        val noBlock = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).replace(source, "")
        return noBlock.lines().joinToString("\n") { line ->
            val idx = line.indexOf("//")
            if (idx >= 0) line.substring(0, idx) else line
        }
    }
}

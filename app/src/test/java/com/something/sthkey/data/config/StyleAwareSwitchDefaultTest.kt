package com.something.sthkey.data.config

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「创建时设的开关值」与「解码时的缺省值」**必须一致**。
 *
 * ============================================================
 * ⚠️⚠️ 这类 bug 已经出现过几次，每次现象都是"怪怪的"
 * ============================================================
 * 成因**永远是同一个**:
 *
 * 1. 某个开关被 `STYLE_SPECIFIC_KEYS` **门控**掉 → 那个样式**不写它**；
 * 2. `ConfigStore.create` 按样式设了正确的值（所以"刚建好是对的"）；
 * 3. 配置**重新解码**时（**划后台**导致进程回收、或重启应用），
 *    存档里没有这个键 → 走 `optBoolean(..., 默认值)`；
 * 4. ⚠️ 那个默认值**与创建时的判断不一致** → 开关自己变回去。
 *
 * 用户报过的几次:
 *
 * | 字段 | 现象 |
 * |---|---|
 * | `showSpaceKey` | 键盘老配置的空格消失 / 关掉后自己又开 |
 * | `showShiftKey` | 触屏看不到 Shift |
 * | `showAButton` | ⚠️ **"划了下后台，新建的触屏配置多了个 A_BUTTON 组件"** |
 *
 * ⚠️ 修法统一成**一个函数**（`defaultShowXxx(styleId)`），
 * `ConfigStore.create` 与 `JsonConfigCodec` 都调它 —— 两处同源就不可能漂。
 *
 * ============================================================
 * ⚠️ 为什么这些测试读源码
 * ============================================================
 * `org.json` 在 JVM 单测里是**空壳**（`JSONObject` 直接抛异常），
 * 所以没法真的编解码一遍。先例:`BitmapFontIdTest.readOwnSource()`。
 *
 * ⚠️ 但源码判据也能抓住这次这个 bug 的**形态**:
 * "解码里对某个被门控的字段用了裸的 `optBoolean(键, 默认值)`"。
 */
class StyleAwareSwitchDefaultTest {

    private fun readSource(relative: String): String {
        val candidates = listOf(
            File("app/src/main/java/com/something/sthkey/$relative"),
            File("src/main/java/com/something/sthkey/$relative"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到源码文件:$relative（工作目录=${File(".").absolutePath}）")
        return file.readText()
    }

    /** 取出一个函数的**声明那一行**（用来断言它的实现形态） */
    private fun functionLine(source: String, marker: String): String {
        val index = source.indexOf(marker)
        assertTrue("找不到「$marker」—— 它被改名或删了？", index >= 0)
        /* ⚠️ 表达式体函数可能折行，所以取到两行 */
        return source.substring(index, minOf(index + 260, source.length)).lines()
            .take(2)
            .joinToString(" ")
    }

    /**
     * ⚠️⚠️ **触屏压根不该读 `showAButton`** —— 这是那个 bug 的根治判据。
     *
     * 用户的原话:
     *
     * > 你应该**直接在触屏样式的配置字段直接去掉 showAButton** 吧。。。
     * > 不然不是字段冗余了吗
     *
     * ⚠️ 修法不是"创建时设成 false"（那字段被门控掉、**不写进存档**，
     * 重新解码又变回 `true` → A 键行自己冒出来，用户报的就是这个），
     * 也不是"给解码加一条按样式的缺省"（那是在给一个不该有的字段写补丁）。
     *
     * ⚠️ 而是**让布局不读它**:`KeyLayout` 里按 `styleId == GAMEPAD2` 判。
     * 于是触屏的 `showAButton` 是个**谁也不读的字段**，
     * 存 `true` 还是 `false` 都一样，冗余消失。
     */
    @Test
    fun `布局只在手柄样式下读 showAButton`() {
        val source = readSource("domain/style/KeyLayout.kt")

        assertTrue(
            "`KeyLayout` 里应当有一个按样式算出的 `usesAButton`（只有手柄为真）—— " +
                "触屏/键盘不该读 `showAButton`",
            source.contains("val usesAButton = config.styleId == StyleId.GAMEPAD2"),
        )
        assertTrue(
            "A 键行必须用 `usesAButton && config.showAButton` 判，不能裸读 `config.showAButton`",
            source.contains("if (usesAButton && config.showAButton)"),
        )
        assertTrue(
            "A 键置顶同理",
            source.contains("if (usesAButton && config.showAButton && config.aButtonOnTop)"),
        )
    }

    /**
     * ⚠️ 非手柄样式的隐藏槽位里**必须有 A_BUTTON / 肩键**。
     *
     * 因为布局根本不生成它们 —— 键位文字栏与触摸区域编辑也不该列出来。
     */
    @Test
    fun `非手柄样式隐藏 A 键与肩键槽位`() {
        val source = readSource("ui/feature/config/JoystickSections.kt")

        assertTrue(
            "`hiddenMappingSlots` 在非手柄分支里要把 A_BUTTON / SHOULDER_L / SHOULDER_R 加进隐藏集 —— " +
                "否则触屏的键位文字栏与区域列表里会列出布局压根不生成的槽位",
            source.contains("add(KeyLayout.Id.A_BUTTON)") &&
                source.contains("add(KeyLayout.Id.SHOULDER_L)") &&
                source.contains("add(KeyLayout.Id.SHOULDER_R)"),
        )
    }

    /** `showSpaceKey` 也得走 `json.has`（它是更早的那次同类事故） */
    @Test
    fun `解码时 showSpaceKey 按样式取缺省`() {
        val source = readSource("data/config/JsonConfigCodec.kt")
        assertTrue(
            "`showSpaceKey` 的解码必须走 `json.has` —— 见 `defaultShowSpaceKey` 的注释",
            source.contains("showSpaceKey = if (json.has(\"showSpaceKey\"))"),
        )
    }

    /**
     * ⚠️ **`ConfigStore.create` 里 `showSpaceKey` 必须走按样式的函数**。
     *
     * 用户的原话（关于这类问题的根子）:
     *
     * > 每次都是"两处各写一份判断"
     */
    @Test
    fun `创建时走按样式的 SPACE 缺省`() {
        val source = readSource("data/config/ConfigStore.kt")

        assertTrue(
            "`ConfigStore.create` 里 `showSpaceKey` 要用 `defaultShowSpaceKey(...)`",
            source.contains("showSpaceKey = defaultShowSpaceKey(resolvedStyle)"),
        )
    }

    /**
     * ⚠️ `showSpaceKey` 对**键盘与触屏**缺省为 `true`。
     *
     * 触屏那条是用户明确要求的:
     *
     * > 把现在的「显示 SPACE 键」改为绑定在现在触屏样式已经默认有的
     * > 这个 Space 组件上，并让他**默认打开**
     *
     * ⚠️ 而且它**不能退化成一个常量**:手柄必须是 `false`，
     * 否则手柄会凭空多出一行 SPACE（与 A 键重叠）。
     */
    @Test
    fun `键盘与触屏的 SPACE 缺省打开而手柄关闭`() {
        val source = readSource("domain/config/KeyStrokesConfig.kt")
        val body = functionLine(source, "fun defaultShowSpaceKey(styleId: String): Boolean =")

        assertTrue(
            "`defaultShowSpaceKey` 里应当同时有 KEYSTROKES 与 TOUCHING，" +
                "实际实现是:`$body`",
            body.contains("StyleId.KEYSTROKES") && body.contains("StyleId.TOUCHING"),
        )
        assertTrue(
            "⚠️ 它**不能**退化成 `true` 常量 —— 手柄的 SPACE 槽位与 A 键是" +
                "同一个屏幕位置，默认打开会让手柄凭空多出一行、和 A 键重叠",
            !body.contains(": Boolean = true"),
        )
    }

    /**
     * ⚠️ 按样式的缺省函数**只有一份实现**。
     *
     * 这条挡的是"为了省事把判断内联回调用方"的改动 ——
     * 那正是这类 bug 反复出现的根子（两处各写一份判断）。
     */
    @Test
    fun `按样式的缺省值只有一份实现`() {
        val source = readSource("domain/config/KeyStrokesConfig.kt")
        val count = Regex("fun defaultShowSpaceKey\\(").findAll(source).count()
        assertTrue(
            "`defaultShowSpaceKey` 应当**恰好有一个**定义，实际找到 $count 个 —— " +
                "多份实现必然漂开（这类 bug 出过好几次）",
            count == 1,
        )
    }
}

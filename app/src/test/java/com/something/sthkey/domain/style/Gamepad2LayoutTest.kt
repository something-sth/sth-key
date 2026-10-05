package com.something.sthkey.domain.style

import com.something.sthkey.domain.config.KeyStrokesConfig
import com.something.sthkey.domain.config.defaultShowSpaceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * gamepad2（「手柄（标准）」）的布局。
 *
 * ============================================================
 * 这个样式的核心决定:**它就是键盘样式的布局**
 * ============================================================
 * 只有 WASD 那块换成一个左摇杆，其余（鼠标键、空格、Shift、CPS 模式）
 * 全部沿用键盘样式。所以这些测试防的不是"摇杆画得对不对"（那是渲染层），
 * 而是:
 *
 * 1. **摇杆槽位出现了**，且**取代**了 W/A/S/D（不是并列）；
 * 2. **下面几行的位置跟着摇杆的高度算对了** —— 这是最容易错的一处，
 *    因为"在渲染层再补一次偏移"会让内容与窗口尺寸对不上；
 * 3. **键盘样式一点没变**（回归）。
 */
class Gamepad2LayoutTest {

    /**
     * 造一份最小可用的配置。
     *
     * ⚠️ `id` 与 `name` 没有默认值 —— 它们是必填的标识，
     * 而测试里只需要"某个样式的配置"，所以这里给固定值。
     *
     * ============================================================
     * ⚠️ 为什么这里要显式写 `showSpaceKey`
     * ============================================================
     * 这个字段的**字段默认值**是 `true`（键盘样式要"默认显示空格"，
     * 用户要求），而它在 gamepad2 里的含义完全不同 ——
     * 那是"显示 **SPACE 槽位**"，而 SPACE 与 `A_BUTTON`
     * **是同一个屏幕位置**，同时开会重叠。
     *
     * ⚠️ 生产代码不靠这个默认值:解码时用 `json.has("showSpaceKey")`
     * 区分"老配置（没这个键）"与"用户的选择"，缺省按**样式**给
     * （见 `defaultShowSpaceKey`）。
     *
     * 但测试是**直接 new** 出配置的，绕过了解码 ——
     * 于是会拿到键盘的默认值、凭空多出一行 SPACE，把 A 键挤出最后一行
     * （`A 横跨两列并排在最后` 就是这么挂的）。
     *
     * 所以这里按样式的真实缺省补上，测试才与线上行为一致。
     */
    private fun config(styleId: String) = KeyStrokesConfig(
        id = "test-$styleId",
        name = "测试",
        styleId = styleId,
        showSpaceKey = defaultShowSpaceKey(styleId),
    )

    private fun keyboard() = config(StyleId.KEYSTROKES)

    private fun gamepad2() = config(StyleId.GAMEPAD2)

    /* ============================================================
     * 键盘样式：一个字都没变（回归）
     * ============================================================ */

    @Test
    fun `键盘样式仍然是 WASD`() {
        val ids = KeyLayout.keys(keyboard()).map { it.slotId }

        listOf("W", "A", "S", "D").forEach { id ->
            assertTrue("键盘样式必须还有 $id", id in ids)
        }
        assertFalse(
            "键盘样式不该有摇杆槽位",
            KeyLayout.Id.JOYSTICK_LEFT in ids,
        )
    }

    @Test
    fun `键盘样式的基准高度没变`() {
        /*
         * ⚠️ 345 = 内容底边(333) + 底部边距(12)。
         *
         * 注意它**不是** `KeyLayout.BASE_HEIGHT`（420）—— `baseHeight`
         * 是按内容算的，而 420 只是"内容为空时的兜底值"。
         * 两个数都对，但含义不同。
         *
         * ⚠️ 这个值在修"底部被裁 12 像素"时从 335 变成了 359 ——
         * 那时的 `baseHeight` 用"行高之和 + 行间隙"推，比内容实际
         * 占用的**少 12**，于是最后一行底部被裁。
         *
         * 这条是**回归断言**:键盘样式的布局不该因为加手柄样式而变，
         * 也不该再退回推算法。
         */
        assertEquals(345f, KeyLayout.baseHeight(keyboard()), 0.5f)
    }

    /* ============================================================
     * ⚠️ 回归：两个摇杆槽位的**水平中心必须不同**
     * ============================================================
     * 用户报过"转换后的标准配置，左右摇杆堆在一起"。
     *
     * 那条症状有两个可能的来源:
     * 1. 布局给两个槽位算了**同一个** centerX（就是这条测的）；
     * 2. 转换器把 centerX 换算成 x 时算错（见 `KeyToCustomConverter`）。
     *
     * ⚠️ 这条断言防的是第 1 种 —— 而它是**很容易发生**的那种错:
     * `stickLeftCenter` / `stickRightCenter` 现在直接取
     * `leftCenter` / `rightCenter`，哪天有人"顺手"把它们都写成 `center`，
     * 画面上就是两个摇杆叠在一起，而**没有任何报错**。
     */
    @Test
    fun `两个摇杆槽位的中心不同`() {
        val boxes = KeyLayout.keys(gamepad2())
        val left = boxes.first { it.slotId == KeyLayout.Id.JOYSTICK_LEFT }
        val right = boxes.first { it.slotId == KeyLayout.Id.JOYSTICK_RIGHT }

        assertTrue(
            "左右摇杆必须有各自的中心（左 ${left.centerX}、右 ${right.centerX}）—— " +
                "相等就意味着它们在画面上叠在一起",
            right.centerX - left.centerX > 1f,
        )

        /* 而且左右摇杆的先后顺序不能反 */
        assertTrue("左摇杆必须在右摇杆左边", left.centerX < right.centerX)

        /* 两者尺寸相同（对称），否则"叠在一起"会变成"一个大一个小" */
        assertEquals(left.width, right.width, 0.01f)
    }

    /* ============================================================
     * gamepad2：摇杆取代 WASD
     * ============================================================ */

    @Test
    fun `gamepad2 用摇杆槽位取代 WASD`() {
        val ids = KeyLayout.keys(gamepad2()).map { it.slotId }

        assertTrue("必须有左摇杆槽位", KeyLayout.Id.JOYSTICK_LEFT in ids)

        listOf("W", "A", "S", "D").forEach { id ->
            assertFalse(
                "gamepad2 里不该再有 $id —— 它的位置被摇杆占了",
                id in ids,
            )
        }
    }

    /**
     * ⚠️ 摇杆槽位的 `codes` 必须是**空的**。
     *
     * 它不参与"按下了哪些键"的匹配。给个非空列表的话，
     * 那四个键码会永远匹配不上（摇杆不上报键码），
     * 而这个槽位会白白出现在键位映射列表里。
     */
    @Test
    fun `摇杆槽位没有键码`() {
        val joystick = KeyLayout.keys(gamepad2())
            .first { it.slotId == KeyLayout.Id.JOYSTICK_LEFT }

        assertTrue("摇杆不绑键码", joystick.codes.isEmpty())
        assertEquals("摇杆没有键面文字", "", joystick.label)
    }

    /* ============================================================
     * 摇杆与三列的对齐（用户明确要求的那几条）
     * ============================================================ */

    /** 两个摇杆都是**正方形**，且**边长相同**（左右对称） */
    @Test
    fun `两个摇杆都是正方形且边长相同`() {
        val boxes = KeyLayout.keys(gamepad2())
        val left = boxes.first { it.slotId == KeyLayout.Id.JOYSTICK_LEFT }
        val right = boxes.first { it.slotId == KeyLayout.Id.JOYSTICK_RIGHT }

        assertEquals("左摇杆必须是正方形", left.width, left.height, 0.01f)
        assertEquals("右摇杆必须是正方形", right.width, right.height, 0.01f)
        assertEquals("左右摇杆边长必须相同", left.width, right.width, 0.01f)
        assertEquals("两个摇杆必须在同一行", left.topY, right.topY, 0.01f)
    }

    /**
     * ⚠️ 用户的原话:**"左摇杆的边长与 LT/RT 的长度同步"**。
     *
     * 也就是摇杆边长 = `LMB` / `RMB`（= 屏幕上的 LT / RT）的**宽度**，
     * 于是摇杆与它下面那个键**上下严格对齐成一列**。
     */
    @Test
    fun `摇杆边长等于 LT 与 RT 的宽度`() {
        val boxes = KeyLayout.keys(gamepad2())
        val left = boxes.first { it.slotId == KeyLayout.Id.JOYSTICK_LEFT }
        val right = boxes.first { it.slotId == KeyLayout.Id.JOYSTICK_RIGHT }
        val lt = boxes.first { it.slotId == "LMB" }
        val rt = boxes.first { it.slotId == "RMB" }

        assertEquals("左摇杆边长 = LT 宽度", lt.width, left.width, 0.01f)
        assertEquals("右摇杆边长 = RT 宽度", rt.width, right.width, 0.01f)
    }

    /**
     * ⚠️ 用户的原话:**"放在 LT 上面"**、**"右摇杆加在 RT 上面"**。
     *
     * 断言**水平中心对齐**（摇杆正压在它那个键的正上方）
     * 与**垂直不重叠**（摇杆底边在 LT 顶边之上）。
     */
    @Test
    fun `左摇杆在 LT 正上方、右摇杆在 RT 正上方`() {
        val boxes = KeyLayout.keys(gamepad2())
        val left = boxes.first { it.slotId == KeyLayout.Id.JOYSTICK_LEFT }
        val right = boxes.first { it.slotId == KeyLayout.Id.JOYSTICK_RIGHT }
        val lt = boxes.first { it.slotId == "LMB" }
        val rt = boxes.first { it.slotId == "RMB" }

        /*
         * ⚠️ 摇杆在**整列**中心，LT/RT 在**鼠标键**中心 —— 两者差
         * `gap / 2`（默认 5），因为鼠标键比整列窄一个 `gap`。
         *
         * 所以这里断言的是"摇杆**包住**它下面那个键"（左右各多出一点），
         * 而不是"中心完全重合"。见 `KeyLayout` 里 `stickLeftCenter` 的说明。
         */
        assertTrue(
            "左摇杆应当**包住** LT（摇杆 ${left.centerX - left.width / 2f}.." +
                "${left.centerX + left.width / 2f}，LT ${lt.centerX - lt.width / 2f}.." +
                "${lt.centerX + lt.width / 2f}）",
            left.centerX - left.width / 2f <= lt.centerX - lt.width / 2f + 0.01f &&
                left.centerX + left.width / 2f >= lt.centerX + lt.width / 2f - 0.01f,
        )
        assertTrue(
            "右摇杆应当包住 RT",
            right.centerX - right.width / 2f <= rt.centerX - rt.width / 2f + 0.01f &&
                right.centerX + right.width / 2f >= rt.centerX + rt.width / 2f - 0.01f,
        )
        assertEquals(
            "两个摇杆必须左右对称",
            300f,
            left.centerX + right.centerX,
            0.01f,
        )

        assertTrue(
            "左摇杆不能压在 LT 上（摇杆底 ${left.topY + left.height}，LT 顶 ${lt.topY}）",
            left.topY + left.height <= lt.topY + 0.01f,
        )
        assertTrue(
            "右摇杆不能压在 RT 上（摇杆底 ${right.topY + right.height}，RT 顶 ${rt.topY}）",
            right.topY + right.height <= rt.topY + 0.01f,
        )
    }

    /**
     * ⚠️ 用户的原话:**"把 LT 与 RT 的 Y 方向长度与 A 同步"** ——
     * 即把 LT/RT 的**高度**改成与 `A` 一样。
     *
     * ⚠️ 用户说的 "A" **就是空格那个槽位**（`SPACE`）——
     * 它的显示文字被 `gamepad2KeyMappings` 设成了 `"A"`。
     * 他原话"反正A不变就是了"也是这个意思。
     */
    @Test
    fun `LT 与 RT 的高度与 A 同步`() {
        val boxes = KeyLayout.keys(gamepad2())
        val space = boxes.first { it.slotId == KeyLayout.Id.A_BUTTON }
        val lt = boxes.first { it.slotId == "LMB" }
        val rt = boxes.first { it.slotId == "RMB" }

        assertEquals("LT 的高度应当与 A（空格槽位）一致", space.height, lt.height, 0.01f)
        assertEquals("RT 的高度应当与 A（空格槽位）一致", space.height, rt.height, 0.01f)
    }

    /**
     * 空格**横跨两列、且在最下面**（用户选的方案）。
     *
     * ⚠️ 这条同时保证"下面没有东西被空格盖住" ——
     * 空格是最后一行，它的 `topY` 必须在其它所有槽位的底边之下。
     */
    @Test
    fun `A 横跨两列并排在最后`() {
        val boxes = KeyLayout.keys(gamepad2())
        val space = boxes.first { it.slotId == KeyLayout.Id.A_BUTTON }
        val maxBottom = boxes.filter { it.slotId != KeyLayout.Id.A_BUTTON }.maxOf { it.topY + it.height }

        assertTrue(
            "空格必须在所有其它槽位之下（空格顶 ${space.topY}，其它最高底边 $maxBottom）",
            space.topY >= maxBottom - 0.01f,
        )
    }

    @Test
    fun `鼠标键与空格都还在`() {
        val boxes = KeyLayout.keys(gamepad2())
        val ids = boxes.map { it.slotId }

        listOf("LMB", "RMB").forEach { id ->
            assertTrue("gamepad2 必须有 $id", id in ids)
        }

        /* 水平位置与键盘样式一致（同一个 centerX） */
        val kb = KeyLayout.keys(keyboard()).associateBy { it.slotId }
        boxes.filter { it.slotId in listOf("LMB", "RMB") }.forEach { box ->
            assertEquals(
                "${box.slotId} 的水平位置应当与键盘样式一致",
                kb.getValue(box.slotId).centerX,
                box.centerX,
                0.01f,
            )
        }
    }

    /** 判据是 styleId，与"有没有手柄在连"无关 */
    @Test
    fun `usesJoystickLayout 只看样式`() {
        assertTrue(KeyLayout.usesJoystickLayout(gamepad2()))
        assertFalse(KeyLayout.usesJoystickLayout(keyboard()))
        assertFalse(
            "gamepad1 是另一套布局（两摇杆 + 方向键 + YXAB），不走这条",
            KeyLayout.usesJoystickLayout(config(StyleId.GAMEPAD1)),
        )
        assertFalse(KeyLayout.usesJoystickLayout(config(StyleId.CUSTOM_KEY)))
    }
}

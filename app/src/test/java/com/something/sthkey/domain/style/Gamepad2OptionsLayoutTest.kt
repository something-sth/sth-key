package com.something.sthkey.domain.style

import com.something.sthkey.domain.config.KeyStrokesConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「手柄（标准）」布局的**可选项**:
 * A 键开关 / A 键置顶 / 肩键开关 / 按键高度百分比。
 *
 * ============================================================
 * ⚠️ 为什么这组要单独测
 * ============================================================
 * 这几个开关**互相组合**，而布局是"顺序堆叠"出来的。
 * 组合漏掉一种的表现不是崩溃，而是**某个键压住另一个**
 * 或者**窗口高度算错**（底部被裁 / 底部一块空气）——
 * 两种都只能靠人眼发现。
 *
 * 所以这里把"行序"当作可断言的东西直接钉住。
 */
class Gamepad2OptionsLayoutTest {

    private fun config(
        showA: Boolean = true,
        aOnTop: Boolean = false,
        shoulders: Boolean = false,
        shift: Boolean = false,
        heightPercent: Int = 100,
    ) = KeyStrokesConfig(
        id = "test-gamepad2",
        name = "测试",
        styleId = StyleId.GAMEPAD2,
        showAButton = showA,
        aButtonOnTop = aOnTop,
        showShoulderButtons = shoulders,
        showShiftKey = shift,
        keyHeightPercent = heightPercent,
    )

    private fun boxesOf(config: KeyStrokesConfig) = KeyLayout.keys(config)

    private fun box(config: KeyStrokesConfig, slotId: String) =
        boxesOf(config).first { it.slotId == slotId }

    /**
     * 任何两个槽位都不许重叠 —— A 独占一行之后它不再与任何键相交，
     * 所以这条可以**全查**（不需要例外）。
     */
    private fun assertNoOverlap(config: KeyStrokesConfig, hint: String) {
        val boxes = boxesOf(config)
        boxes.forEachIndexed { i, a ->
            boxes.drop(i + 1).forEach { b ->

                val overlapX = a.centerX - a.width / 2f < b.centerX + b.width / 2f &&
                    b.centerX - b.width / 2f < a.centerX + a.width / 2f
                val overlapY = a.topY < b.topY + b.height &&
                    b.topY < a.topY + a.height
                assertFalse(
                    "$hint:${a.slotId} 与 ${b.slotId} 重叠了\n" +
                        "  ${a.slotId}: ${a.centerX - a.width / 2f}..${a.centerX + a.width / 2f} " +
                        "× ${a.topY}..${a.topY + a.height}\n" +
                        "  ${b.slotId}: ${b.centerX - b.width / 2f}..${b.centerX + b.width / 2f} " +
                        "× ${b.topY}..${b.topY + b.height}",
                    overlapX && overlapY,
                )
            }
        }
    }

    /* ============================================================
     * A 键
     * ============================================================ */

    @Test
    fun `A 键开关关掉之后就没有 A 槽位`() {
        val ids = boxesOf(config(showA = false)).map { it.slotId }
        assertFalse("关掉开关就不该有 A", KeyLayout.Id.A_BUTTON in ids)
    }

    /**
     * ⚠️ **A 独占一行**，不与 LT/RT 同行 —— 用户的原话:
     *
     * > "A 本身就指代 space，然后现在的排列是 LT、RT 占一行，
     * >  A 在它们下面独占一行，类似于键盘样式 LMB、RMB 与 space 的
     * >  位置关系"
     *
     * 而且**横跨两列**（和键盘样式的 space 一样），不是中间列的窄键。
     *
     * ⚠️ 这条测试的前一版断言的是"A 与 LT 同一行"—— 那是我把
     * A 理解成"LT/RT 中间那个键"时写的，**理解错了**。
     * 按那个排法三键各 125 宽需要 395，而窗口只有 300。
     */
    @Test
    fun `A 独占一行在 LT RT 之下且横跨两列`() {
        val c = config()
        val a = box(c, KeyLayout.Id.A_BUTTON)
        val lt = box(c, "LMB")
        val rt = box(c, "RMB")

        assertTrue(
            "A 必须在 LT/RT 之下（A 顶 ${a.topY}，LT 底 ${lt.topY + lt.height}）",
            a.topY >= lt.topY + lt.height,
        )
        assertEquals("LT 与 RT 同一行", lt.topY, rt.topY, 0.01f)
        assertEquals("A 居中", 150f, a.centerX, 0.01f)

        /* 横跨两列 = 与最左最右的键同边 */
        assertEquals("A 左边与 LT 对齐", lt.centerX - lt.width / 2f, a.centerX - a.width / 2f, 0.01f)
        assertEquals("A 右边与 RT 对齐", rt.centerX + rt.width / 2f, a.centerX + a.width / 2f, 0.01f)

        /* 高度与 LT/RT 同步（用户原话:"把 LT 与 RT 的 Y 方向长度与 A 同步"） */
        assertEquals("A 与 LT 等高", lt.height, a.height, 0.01f)
    }

    /**
     * ⚠️ **A 键置顶的核心断言** —— 用户的原话:
     * "A 键尺寸不变，移到摇杆正下方，取代 LT，RT 的位置，
     *  同时 LT 与 RT 往下移，整个过程只改变位置不改变尺寸"。
     */
    @Test
    fun `A 键置顶时移到摇杆正下方且 LT RT 下移`() {
        val flat = config(aOnTop = false)
        val top = config(aOnTop = true)

        val aTop = box(top, KeyLayout.Id.A_BUTTON)
        val stickL = box(top, KeyLayout.Id.JOYSTICK_LEFT)
        val ltTop = box(top, "LMB")
        val ltFlat = box(flat, "LMB")

        /* ① 尺寸**不变** */
        assertEquals("置顶不该改 A 的宽", box(flat, KeyLayout.Id.A_BUTTON).width, aTop.width, 0.01f)
        assertEquals("置顶不该改 A 的高", box(flat, KeyLayout.Id.A_BUTTON).height, aTop.height, 0.01f)

        /* ② 位置:在**摇杆正下方** */
        assertEquals(
            "A 的水平中心应当与摇杆一致（在中间列）",
            box(flat, KeyLayout.Id.A_BUTTON).centerX,
            aTop.centerX,
            0.01f,
        )
        assertTrue(
            "A 必须排在**摇杆下面**（A 顶 ${aTop.topY}，摇杆底 ${stickL.topY + stickL.height}）",
            aTop.topY >= stickL.topY + stickL.height,
        )

        /* ③ LT/RT 顺延到 A **下面**，而且比不置顶时更靠下 */
        assertTrue(
            "LT 必须排在 A 下面（LT 顶 ${ltTop.topY}，A 底 ${aTop.topY + aTop.height}）",
            ltTop.topY >= aTop.topY + aTop.height,
        )
        assertTrue(
            "LT 必须比不置顶时更靠下（原 ${ltFlat.topY}，现 ${ltTop.topY}）",
            ltTop.topY > ltFlat.topY,
        )
    }

    @Test
    fun `置顶与不置顶的总高度相同`() {
        /*
         * 两者行数一样（A 都是独立的一块高度），只是顺序不同 ——
         * 所以窗口高度不该变。变了说明某一行的推进算错了。
         */
        assertEquals(
            "置顶只换顺序，不该改变总高度",
            KeyLayout.baseHeight(config(aOnTop = false)),
            KeyLayout.baseHeight(config(aOnTop = true)),
            0.01f,
        )
    }

    /* ============================================================
     * 肩键
     * ============================================================ */

    @Test
    fun `肩键开关关掉之后就没有肩键槽位`() {
        val ids = boxesOf(config(shoulders = false)).map { it.slotId }
        assertFalse(KeyLayout.Id.SHOULDER_L in ids)
        assertFalse(KeyLayout.Id.SHOULDER_R in ids)
    }

    @Test
    fun `肩键在 LT RT 下方且尺寸相同`() {
        val c = config(shoulders = true)
        val lt = box(c, "LMB")
        val rt = box(c, "RMB")
        val lb = box(c, KeyLayout.Id.SHOULDER_L)
        val rb = box(c, KeyLayout.Id.SHOULDER_R)

        assertTrue(
            "LB 必须在 LT 下方（LB 顶 ${lb.topY}，LT 底 ${lt.topY + lt.height}）",
            lb.topY >= lt.topY + lt.height,
        )
        assertTrue(
            "RB 必须在 RT 下方",
            rb.topY >= rt.topY + rt.height,
        )

        /* 用户原话:"组件大小一样的" */
        assertEquals("LB 与 LT 同宽", lt.width, lb.width, 0.01f)
        assertEquals("LB 与 LT 等高", lt.height, lb.height, 0.01f)
        assertEquals("LB 与 LT 同列", lt.centerX, lb.centerX, 0.01f)
        assertEquals("RB 与 RT 同列", rt.centerX, rb.centerX, 0.01f)
    }

    /* ============================================================
     * 组合：不许重叠、不许漏算高度
     * ============================================================ */

    @Test
    fun `所有开关组合下都不会重叠`() {
        listOf(false, true).forEach { showA ->
            listOf(false, true).forEach { onTop ->
                listOf(false, true).forEach { shoulders ->
                    listOf(false, true).forEach { shift ->
                        val c = config(showA, onTop, shoulders, shift)
                        val hint = "A=$showA 置顶=$onTop 肩键=$shoulders Shift=$shift"
                        assertNoOverlap(c, hint)

                        /*
                         * 而且**注册高度必须装得下** —— 这正是
                         * "忘了同步注册值"那个 bug 的防线。
                         */
                        val registered = OverlayStyleRegistry.baseSizeOf(c).height
                        val bottom = boxesOf(c).maxOf { it.topY + it.height }
                        assertTrue(
                            "$hint:内容底边 $bottom 超出注册高度 $registered",
                            bottom <= registered + 0.01f,
                        )
                    }
                }
            }
        }
    }

    /* ============================================================
     * 按键高度百分比
     * ============================================================ */

    @Test
    fun `按键高度百分比只改高度不改宽度`() {
        val normal = config(heightPercent = 100)
        val flat = config(heightPercent = 60)

        val ltNormal = box(normal, "LMB")
        val ltFlat = box(flat, "LMB")

        assertEquals("宽度不该变", ltNormal.width, ltFlat.width, 0.01f)
        assertEquals("高度应当变成 60%", ltNormal.height * 0.6f, ltFlat.height, 0.01f)
        assertTrue(
            "整体应当变矮",
            KeyLayout.baseHeight(flat) < KeyLayout.baseHeight(normal),
        )
    }

    @Test
    fun `按键高度百分比会被夹在 50 到 200 之间`() {
        /*
         * ⚠️ 不夹的话，用户手输 0 会让所有键消失、输 1000 会让
         * 窗口高到没法看 —— 而两者都不会报错。
         */
        val tooSmall = box(config(heightPercent = 0), "LMB")
        val atMin = box(config(heightPercent = 50), "LMB")
        assertEquals("0 应当被夹到 50", atMin.height, tooSmall.height, 0.01f)

        val tooBig = box(config(heightPercent = 9999), "LMB")
        val atMax = box(config(heightPercent = 200), "LMB")
        assertEquals("9999 应当被夹到 200", atMax.height, tooBig.height, 0.01f)
    }

    @Test
    fun `按键高度百分比不影响键盘样式`() {
        /*
         * ⚠️ 这个滑块是给 gamepad2 加的，但字段在公共配置里 ——
         * 所以键盘样式**也会**跟着变。这是**有意的**（键盘用户也想要），
         * 但必须确认它不是"忘了加分支"导致的意外。
         */
        val normal = KeyStrokesConfig(id = "k", name = "k", styleId = StyleId.KEYSTROKES)
        val flat = normal.copy(keyHeightPercent = 50)

        assertTrue(
            "键盘样式的高度应当也跟着滑块变（有意为之）",
            KeyLayout.baseHeight(flat) < KeyLayout.baseHeight(normal),
        )
        assertEquals(
            "但宽度不该变",
            box(normal, "W").width,
            box(flat, "W").width,
            0.01f,
        )
    }
}

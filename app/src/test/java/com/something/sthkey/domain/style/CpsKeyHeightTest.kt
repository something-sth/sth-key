package com.something.sthkey.domain.style

import com.something.sthkey.domain.config.KeyStrokesConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CPS 三种模式下的**键高**。
 *
 * ============================================================
 * 为什么值得测
 * ============================================================
 * CPS 会改变布局:
 *
 * | 模式 | 布局变化 |
 * |---|---|
 * | 1 | 无（CPS 接在主文字后面） |
 * | 2 | 鼠标键下面**多一行**独立的 CPS 组件 |
 * | 3 | 鼠标键**变高**，CPS 显示在键内部的第二行 |
 *
 * 而"键多高才够装下文字"是**可以算的**，不该凭感觉给 ——
 * 原来模式 3 给了 110（实际两行文字只需要 50），
 * 用户的原话是"模式 3 也应该适当调矮一点"。
 *
 * 所以这里把"高度 >= 文字块"这条关系钉住。
 */
class CpsKeyHeightTest {

    private fun config(
        enabled: Boolean = true,
        mode: Int,
        showMouse: Boolean = true,
    ) = KeyStrokesConfig(
        id = "test-cps",
        name = "测试",
        styleId = StyleId.KEYSTROKES,
        mouseCpsEnabled = enabled,
        mouseCpsMode = mode,
        showMouseButtons = showMouse,
    )

    private fun box(config: KeyStrokesConfig, slotId: String) =
        KeyLayout.keys(config).first { it.slotId == slotId }

    /* ============================================================
     * 模式 3：键内两行文字，所以必须够高
     * ============================================================ */

    /**
     * ⚠️ 模式 3 的鼠标键必须**装得下两行文字**。
     *
     * 文字块高度是可以算的:两行的行高都被显式钉成等于字号
     * （见 `KeyGrid.lineStyle`），所以
     *
     * ```
     * 文字块 = CPS_PRIMARY_TEXT_SIZE + CPS_SECONDARY_TEXT_SIZE = 28 + 22 = 50
     * ```
     *
     * 键高低于它就会**裁字** —— 而那是"看起来有点挤"到"字被切掉"之间
     * 的渐变，不容易判断，所以用比值给一条硬线。
     */
    @Test
    fun `模式 3 的鼠标键装得下两行文字`() {
        val lmb = box(config(mode = 3), "LMB")
        val textBlock = KeyLayout.CPS_PRIMARY_TEXT_SIZE + KeyLayout.CPS_SECONDARY_TEXT_SIZE

        assertTrue(
            "模式 3 的键高 ${lmb.height} 必须 >= 文字块 $textBlock",
            lmb.height >= textBlock,
        )
        assertTrue(
            "键高 ${lmb.height} 相对文字块 $textBlock 太紧（应当留内边距，至少 1.25 倍）",
            lmb.height >= textBlock * 1.25f,
        )
    }

    @Test
    fun `模式 3 的鼠标键比普通鼠标键高`() {
        val normal = box(config(enabled = false, mode = 1), "LMB")
        val cps3 = box(config(mode = 3), "LMB")

        assertTrue(
            "模式 3 要把 CPS 塞进键内，必须更高（普通 ${normal.height}，模式 3 ${cps3.height}）",
            cps3.height > normal.height,
        )
    }

    /**
     * ⚠️ 但不能高得离谱 —— 用户报的就是"太高了"。
     *
     * 110 是凭感觉给的，实际 70 就够。这里给一条宽松但有效的上界:
     * 键高不该超过**三行文字**（否则就是白占屏幕）。
     */
    @Test
    fun `模式 3 的鼠标键不该高得离谱`() {
        val lmb = box(config(mode = 3), "LMB")
        val textBlock = KeyLayout.CPS_PRIMARY_TEXT_SIZE + KeyLayout.CPS_SECONDARY_TEXT_SIZE

        assertTrue(
            "模式 3 的键高 ${lmb.height} 超过三行文字（${textBlock * 3}）—— 白占屏幕",
            lmb.height <= textBlock * 3f,
        )
    }

    /* ============================================================
     * 模式 2（独立 CPS 组件）**已删除**
     * ============================================================ */

    /**
     * ⚠️ **回归**:独立 CPS 组件那一条路已经不存在。
     *
     * 用户的原话:"模式 2 的组件出来了但是没有变化，cps 一直显示 0，
     * 干脆在 gamepad2 这里把 cps 模式 2 删掉吧，太多组件也不好安排，
     * 就留模式 1 和 3 就好了"。
     *
     * ⚠️ 内部编号 `2` 这个值**仍然被接受**（老配置里可能存着），
     * 但它不再生成任何槽位 —— 解码时会被迁移到 `3`（见 JSON 编解码）。
     */
    @Test
    fun `不再生成独立的 CPS 槽位`() {
        listOf(1, 2, 3).forEach { mode ->
            val ids = KeyLayout.keys(config(mode = mode)).map { it.slotId }
            assertTrue(
                "模式 $mode 不该再有 CPS_L 槽位",
                KeyLayout.Id.CPS_L !in ids,
            )
            assertTrue(
                "模式 $mode 不该再有 CPS_R 槽位",
                KeyLayout.Id.CPS_R !in ids,
            )
        }
    }

    /* ============================================================
     * 模式 1：不该改变任何高度
     * ============================================================ */

    @Test
    fun `模式 1 不改变鼠标键高度`() {
        val off = box(config(enabled = false, mode = 1), "LMB")
        val on = box(config(enabled = true, mode = 1), "LMB")

        assertEquals("模式 1 的 CPS 接在主文字后面，键高不变", off.height, on.height, 0.01f)
        assertEquals(
            "模式 1 的总高度也不变",
            KeyLayout.baseHeight(config(enabled = false, mode = 1)),
            KeyLayout.baseHeight(config(enabled = true, mode = 1)),
            0.01f,
        )
    }

    /* ============================================================
     * 与按键高度滑块的相互作用
     * ============================================================ */

    @Test
    fun `按键高度滑块会一起缩放 CPS 模式的键高`() {
        val normal = config(mode = 3)
        val flat = normal.copy(keyHeightPercent = 50)

        assertEquals(
            "模式 3 的键高要跟着滑块变（它比普通键高，装了第二行文字）",
            box(normal, "LMB").height * 0.5f,
            box(flat, "LMB").height,
            0.01f,
        )
    }
}

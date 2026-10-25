package com.something.sthkey.capture.touch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 触屏原生协议解析:[TouchNativeProtocol]。
 *
 * ============================================================
 * ⚠️ 这里保的是"**宁丢一帧、不错一帧**"
 * ============================================================
 * `TOUCH` 行的字段数**必须严格**等于 `2 + 3 × 数量`。宽松解析的后果:
 *
 * | 宽松解析 | 表现 |
 * |---|---|
 * | 把残缺行当有效帧 | 触点坐标**两两错位** —— 手指位置全乱 |
 * | 数量与实际不符 | 摇杆按着按着突然松手（那根手指"消失"了） |
 *
 * ⚠️ 丢弃整行的表现是"卡住一帧"（下一帧就恢复），
 * 而错位帧的表现是"乱跳" —— 前者顶多卡顿，后者会让用户以为坏了。
 * 所以下面一半的用例都在验"**该丢的必须丢掉**"。
 */
class TouchNativeProtocolTest {

    // ---------- 正常解析 ----------

    @Test
    fun `没有手指时解析出空帧`() {
        val frame = TouchNativeProtocol.parseFrame("TOUCH 0")
        assertEquals(TouchNativeProtocol.Frame.EMPTY, frame)
        assertTrue("触点应该为空", frame!!.points.isEmpty())
    }

    @Test
    fun `单个触点坐标归一化正确`() {
        /* 0 / 50000 / 100000 → 0f / 0.5f / 1f */
        val frame = TouchNativeProtocol.parseFrame("TOUCH 1 7 50000 25000")!!
        assertEquals(1, frame.points.size)
        val p = frame.points[0]
        assertEquals("id 要原样带出来", 7, p.id)
        assertEquals(0.5f, p.x, 1e-4f)
        assertEquals(0.25f, p.y, 1e-4f)
    }

    @Test
    fun `多个触点按顺序解析`() {
        val frame = TouchNativeProtocol.parseFrame(
            "TOUCH 3 0 0 0 1 100000 100000 2 50000 0",
        )!!
        assertEquals(3, frame.points.size)
        assertEquals(0f to 0f, frame.points[0].x to frame.points[0].y)
        assertEquals(1f to 1f, frame.points[1].x to frame.points[1].y)
        assertEquals(0.5f to 0f, frame.points[2].x to frame.points[2].y)
    }

    /**
     * ⚠️ 坐标**越界要夹住**:设备报出越界值时不该让下游拿到越界坐标 ——
     * 那会让命中测试与摇杆偏移一起跑偏（比如"手指在区域外却算区域内"）。
     */
    @Test
    fun `越界坐标被夹住`() {
        val frame = TouchNativeProtocol.parseFrame("TOUCH 1 0 -5000 200000")!!
        assertEquals(0f, frame.points[0].x, 1e-4f)
        assertEquals(1f, frame.points[0].y, 1e-4f)
    }

    // ---------- ⚠️ 该丢的必须丢 ----------

    @Test
    fun `字段数不对就整行丢弃`() {
        val bad = listOf(
            /* 说 1 个触点，却给了 2 组坐标 */
            "TOUCH 1 0 100 100 1 200 200",
            /* 说 2 个触点，只给了 1 组 */
            "TOUCH 2 0 100 100",
            /* 说 1 个触点，坐标缺一个 */
            "TOUCH 1 0 100",
            /* 只有命令与数量都还好，但数量是 1 而后面什么都没有 */
            "TOUCH 1",
        )
        bad.forEach { line ->
            assertNull("这一行必须被丢弃: $line", TouchNativeProtocol.parseFrame(line))
        }
    }

    @Test
    fun `非数字字段就整行丢弃`() {
        assertNull(TouchNativeProtocol.parseFrame("TOUCH 1 0 abc 100"))
        assertNull(TouchNativeProtocol.parseFrame("TOUCH x 0 100 100"))
    }

    @Test
    fun `数量超出上限就丢弃`() {
        /* 上限是 20，21 直接丢 —— 截断会"凭空少一根手指" */
        assertNull(TouchNativeProtocol.parseFrame("TOUCH 21"))
        assertNull(TouchNativeProtocol.parseFrame("TOUCH 999"))
    }

    @Test
    fun `负数 id 与负数数量就丢弃`() {
        assertNull(TouchNativeProtocol.parseFrame("TOUCH 1 -1 100 100"))
        assertNull(TouchNativeProtocol.parseFrame("TOUCH -1"))
    }

    @Test
    fun `不是 TOUCH 行的返回 null`() {
        assertNull(TouchNativeProtocol.parseFrame(null))
        assertNull(TouchNativeProtocol.parseFrame(""))
        assertNull(TouchNativeProtocol.parseFrame("   "))
        assertNull(TouchNativeProtocol.parseFrame("STATUS touch-ready x"))
        assertNull(TouchNativeProtocol.parseFrame("GAMEPAD 1 2 3 4 5 6 7"))
    }

    /**
     * ⚠️ 前缀必须**严格**匹配 —— 且要**要求后面的分隔**。
     *
     * | 写法 | 结果 |
     * |---|---|
     * | `touch 1 …` | 丢掉（大小写不对） |
     * | `TOUCHX 1 …` | ⚠️ **必须丢掉** —— 它是另一个命令，不是触点帧 |
     *
     * ⚠️ `TOUCHX` 这一条是真抓出来的:原来只判 `startsWith("TOUCH")`，
     * 于是以后 native 加 `TOUCHCAL` 之类的新命令时会**被当成触点帧解析**。
     * 现在要求前缀后面必须是空格（或整行就是 `TOUCH`）。
     */
    @Test
    fun `前缀必须严格匹配`() {
        assertNull("大小写不对", TouchNativeProtocol.parseFrame("touch 1 0 100 100"))
        assertNull(
            "`TOUCHX` 是另一个命令，不能当触点帧",
            TouchNativeProtocol.parseFrame("TOUCHX 1 0 100 100"),
        )
        assertNull(
            "`TOUCHCAL` 同理",
            TouchNativeProtocol.parseFrame("TOUCHCAL 1 0 100 100"),
        )
    }

    /** 多余空格不该影响解析（native 那边格式固定，但读流时可能出现） */
    @Test
    fun `多余空格不影响解析`() {
        val frame = TouchNativeProtocol.parseFrame("TOUCH  1   0   100   100  ")!!
        assertEquals(1, frame.points.size)
        assertEquals(0.001f, frame.points[0].x, 1e-5f)
    }

    // ---------- 状态行分类 ----------

    /**
     * ⚠️⚠️ `waiting-touch` 与 `touch-disconnected` **必须是两种状态**。
     *
     * 手柄那边把它们合成一个，结果"没插手柄"被说成"手柄断开"、
     * 每 800ms 刷一条日志 —— 用户原话:"不连手柄的时候调试页也一直在输出
     * 手柄断开等待重新连接，能不能把这个去掉"。
     *
     * ⚠️ 触屏更要注意:触屏**一定插着**，所以 `waiting-touch` 意味着
     * **设备识别失败**，而不是"用户没操作"。
     */
    @Test
    fun `等待与断开是两种状态`() {
        val waiting = "STATUS waiting-touch"
        val gone = "STATUS touch-disconnected"

        assertTrue(TouchNativeProtocol.isWaiting(waiting))
        assertFalse("等待不是断开", TouchNativeProtocol.isDisconnected(waiting))

        assertTrue(TouchNativeProtocol.isDisconnected(gone))
        assertFalse("断开不是等待", TouchNativeProtocol.isWaiting(gone))
    }

    @Test
    fun `就绪行带设备描述`() {
        val line = "STATUS touch-ready /dev/input/event1 | goodix_ts | MT+SLOT+DIRECT"
        assertTrue(TouchNativeProtocol.isReady(line))
        assertEquals(
            "/dev/input/event1 | goodix_ts | MT+SLOT+DIRECT",
            TouchNativeProtocol.readyDetail(line),
        )
        /* 就绪行不该被误判成等待或断开 */
        assertFalse(TouchNativeProtocol.isWaiting(line))
        assertFalse(TouchNativeProtocol.isDisconnected(line))
    }

    @Test
    fun `状态行识别`() {
        assertTrue(TouchNativeProtocol.isStatus("STATUS starting"))
        assertTrue(TouchNativeProtocol.isStatus("STATUS no-input-devices"))
        assertFalse(TouchNativeProtocol.isStatus("TOUCH 0"))
        assertFalse(TouchNativeProtocol.isStatus(null))
    }

    /**
     * ⚠️ 文案要与 native 的 `printf` 逐条对得上。
     *
     * 这一条是防"native 改了文案、Kotlin 这边没跟上"——
     * 那样界面会显示英文原文（兜底逻辑在，不会崩，但会很难看）。
     */
    @Test
    fun `状态文案覆盖 native 会打的每一条`() {
        /* 与 touchmonitor.cpp 里的 STATUS 字面量一一对应 */
        val nativeStatuses = listOf(
            "starting",
            "waiting-touch",
            "touch-disconnected",
            "no-touchscreen-candidate",
            "input-permission-denied",
            "no-input-devices",
            "stopped",
        )
        nativeStatuses.forEach { raw ->
            val text = TouchNativeProtocol.statusText("STATUS $raw")
            assertFalse(
                "`$raw` 没有登记中文文案 —— 界面上会露出英文原文",
                text.startsWith("触屏监听："),
            )
            assertTrue("`$raw` 的文案不该为空", text.isNotBlank())
        }
    }

    /** 认不出来的状态**原样显示**而不是丢掉（以后 native 加状态时的兜底） */
    @Test
    fun `未知状态原样显示`() {
        assertEquals(
            "触屏监听：some-new-status from the future",
            TouchNativeProtocol.statusText("STATUS some-new-status from the future"),
        )
    }

    /** `helper-copy-failed` 来自部署脚本，不是 native —— 但也要有中文 */
    @Test
    fun `复制失败有中文文案`() {
        val text = TouchNativeProtocol.statusText(
            "STATUS helper-copy-failed 复制 helper 失败（源文件读不到？）",
        )
        assertTrue("应该带上原因", text.contains("复制 helper 失败"))
        assertTrue("原因要保留", text.contains("源文件读不到"))
    }

    /** 就绪行的文案要把设备描述带上（挑错设备时一眼看得出） */
    @Test
    fun `就绪文案带设备描述`() {
        val text = TouchNativeProtocol.statusText(
            "STATUS touch-ready /dev/input/event1 | goodix_ts | MT+SLOT+DIRECT",
        )
        assertTrue(text.contains("触屏已连接"))
        assertTrue("设备描述必须露出来 —— 挑错设备时靠它排查", text.contains("goodix_ts"))
    }
}

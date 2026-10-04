package com.something.sthkey.capture.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `getevent` 输出解析的测试。
 *
 * ============================================================
 * 为什么这些用例值得写
 * ============================================================
 * 这条通道拿到的是**文本**，解析错了不会崩、也不会报错 ——
 * 只会"某些键不亮"。而按键不亮的排查成本极高（用户不知道为什么，
 * 我们看不出哪一步没对上）。
 *
 * ============================================================
 * 样本要逐字照抄真实输出
 * ============================================================
 * 自己编样本，测的是"我以为的格式"，而不是 `getevent` 真实的格式 ——
 * 那还不如不测。
 *
 * ⚠️ 以后在真机上抓到不一样的输出，**把样本补到这里**再加一条断言，
 * 不要改代码去"顺手兼容"。
 */
class GeteventParserTest {

    /* ============================================================
     * 旧格式（不带 -l）：单设备模式下的输出
     * ============================================================ */

    @Test
    fun `解析没有设备前缀的一行`() {
        // KEY_Q 按下：type=1 code=16 value=1
        assertEquals(
            ParsedLine.Event(device = null, type = 0x01, code = 0x10, value = 1, timestampMicros = 0L),
            GeteventParser.parse("0001 0010 00000001"),
        )
    }

    @Test
    fun `解析带设备前缀的一行`() {
        val parsed = GeteventParser.parse("/dev/input/event3: 0001 0010 00000001")

        assertTrue(parsed is ParsedLine.Event)
        assertEquals("/dev/input/event3", (parsed as ParsedLine.Event).device)
        assertEquals(0x10, parsed.code)
    }

    /**
     * ⚠️ 负值必须能解析。
     *
     * `getevent` 把字段按 int 打印，所以负数显示成 `ffffffff`。
     * `"ffffffff".toInt(16)` 会抛 `NumberFormatException` ——
     * 于是**鼠标往左/往上移动的事件被整条丢掉**，
     * 表现为"鼠标只能往右下动"，很容易被误判成硬件问题。
     */
    @Test
    fun `解析负数（鼠标往左上移动）`() {
        val parsed = GeteventParser.parse("0002 0000 ffffffff") as ParsedLine.Event

        assertEquals(0x02, parsed.type)
        assertEquals(0, parsed.code)
        assertEquals(-1, parsed.value)
    }

    /* ============================================================
     * 新格式（-lt）：全局监听 + 时间戳 + 设备前缀
     * ============================================================ */

    @Test
    fun `解析带时间戳与设备前缀的事件行`() {
        val parsed = GeteventParser.parse(
            "[   12345.678901] /dev/input/event3: 0001 0011 00000001",
        )

        assertTrue("应当是事件", parsed is ParsedLine.Event)
        val event = parsed as ParsedLine.Event
        assertEquals("/dev/input/event3", event.device)
        assertEquals(0x0001, event.type)
        assertEquals(0x0011, event.code)
        assertEquals(1, event.value)
    }

    /**
     * ⚠️ 时间戳的数字**不能**参与字段解析。
     *
     * `12345` 与 `678901` 都是**合法十六进制** —— 如果实现只是
     * "按空白拆字段"而不先剥掉时间戳，这里会解析出
     * type=0x12345、code=0x678901 这种**看起来正常但完全错误**的结果。
     *
     * 症状是"按键全乱、鼠标往奇怪方向动"，很难联想到时间戳。
     */
    @Test
    fun `时间戳不会被当成字段`() {
        val parsed = GeteventParser.parse(
            "[   12345.678901] /dev/input/event3: 0001 0011 00000001",
        ) as ParsedLine.Event

        assertEquals("type 必须是真正的第一个字段", 0x0001, parsed.type)
        assertEquals("code 必须是真正的第二个字段", 0x0011, parsed.code)
        assertEquals("value 必须是真正的第三个字段", 1, parsed.value)
    }

    @Test
    fun `时间戳换算成微秒`() {
        val parsed = GeteventParser.parse(
            "[   12345.678901] /dev/input/event3: 0001 0011 00000001",
        ) as ParsedLine.Event

        assertEquals(12_345_678_901L, parsed.timestampMicros)
    }

    /** ⚠️ 小数位不足 6 位要**右补零**：`5.5` 是半秒，不是 55 微秒 */
    @Test
    fun `时间戳小数位不足时右补零`() {
        val parsed = GeteventParser.parse(
            "[      12.5] /dev/input/event3: 0001 0011 00000001",
        ) as ParsedLine.Event

        assertEquals("12.5 秒 = 12_500_000 微秒", 12_500_000L, parsed.timestampMicros)
    }

    /* ============================================================
     * 设备公告 —— 热插拔的唯一来源
     * ============================================================ */

    @Test
    fun `解析设备接入`() {
        assertEquals(
            ParsedLine.Attached("/dev/input/event3"),
            GeteventParser.parse("add device 5: /dev/input/event3"),
        )
    }

    @Test
    fun `解析设备拔出`() {
        assertEquals(
            ParsedLine.Detached("/dev/input/event3"),
            GeteventParser.parse("remove device 5: /dev/input/event3"),
        )
    }

    /**
     * ⚠️ 公告行里**取不到路径**时也要认出来。
     *
     * 不同 ROM / toybox 版本打印的内容不完全一样（有的带完整路径，
     * 有的只带节点序号）。取不到路径时返回**空路径的公告**，
     * 让读取源走"清掉全部按键状态"这条保守路径 ——
     * 那比"什么都不做、按键永久卡住"好得多。
     */
    @Test
    fun `公告行没有路径时仍然认出来`() {
        assertEquals(
            "接入",
            ParsedLine.Attached(""),
            GeteventParser.parse("add device 5: event3"),
        )
        assertEquals(
            "拔出",
            ParsedLine.Detached(""),
            GeteventParser.parse("remove device 5: event3"),
        )
    }

    /* ============================================================
     * ⚠️ 描述块绝不能被当成事件
     * ============================================================
     */

    /**
     * 这是**最危险**的一类误判。
     *
     * `-lt` 在设备公告之后会打印一整个描述块，其中 `events:` 那一行
     * **末尾恰好是一串十六进制**：
     *
     * ```
     *   events:   KEY (0001): 0001 0002 0003 ...
     * ```
     *
     * 宽松的"取最后三个字段"策略会把它解析成一个**假的输入事件** ——
     * 采集里就凭空多出几个按下的键，而用户完全不知道为什么。
     *
     * 判据必须是"**这一行有没有时间戳前缀**"，不是"字段数够不够"。
     */
    @Test
    fun `描述块里的 events 行不会被当成事件`() {
        assertNull(
            "`events:` 行末尾是十六进制，但它是描述块，不是事件",
            GeteventParser.parse(
                "  events:   KEY (0001): 0001 0002 0003 0004 0005 0006",
            ),
        )
    }

    @Test
    fun `描述块里的 name 行被忽略`() {
        assertNull(
            GeteventParser.parse("  name:     \"Xbox Wireless Controller\""),
        )
    }

    @Test
    fun `描述块里的其它行被忽略`() {
        assertNull(GeteventParser.parse("  input props:  00000000 00000000"))
        assertNull(GeteventParser.parse("  abs info:     ..."))
        assertNull(GeteventParser.parse("  value:        0"))
    }

    /* ============================================================
     * ⚠️ 把"不要用 -l"这个约定钉住
     * ============================================================
     */

    /**
     * ⚠️ **这条测试断言的是"解析不了"，那是刻意的。**
     *
     * `getevent -l` 会把事件行换成符号名（`EV_KEY KEY_W DOWN`），
     * 要支持它得带一张几百项的 `KEY_*` 名称表。
     *
     * 而它的失败方式**极其隐蔽**（踩过一次）：
     *
     * - 每一个真实按键事件都被丢掉；
     * - 但**设备公告不受 `-l` 影响**，照样能解析；
     * - 于是表现成"热插拔正常、设备数会更新，但悬浮窗毫无反应"。
     *
     * 一半对一半错，很容易以为是采集之外的地方坏了。
     *
     * 所以命令里只用 `-t`（见 `RootInputSource.COMMAND`）。
     * **这条测试的作用是**：谁要是把 `-l` 加回命令里，
     * 他会先看到这条测试失败，然后读到上面这段话。
     */
    @Test
    fun `带 -l 的符号名格式解析不了（所以命令里不能用 -l）`() {
        assertNull(
            "符号名格式没有十六进制字段，解析必然失败",
            GeteventParser.parse("[   12345.678901] /dev/input/event3: EV_KEY KEY_W DOWN"),
        )
        assertNull(
            GeteventParser.parse("[   12345.678901] /dev/input/event3: EV_REL REL_X 1"),
        )
    }

    /* ============================================================
     * 噪声
     * ============================================================ */

    @Test
    fun `空行返回 null`() {
        assertNull(GeteventParser.parse(""))
        assertNull(GeteventParser.parse("   "))
    }

    /** `getevent` 参数不认时会打印用法 —— 那不是事件，也不该崩 */
    @Test
    fun `用法提示与错误文本被忽略`() {
        assertNull(
            GeteventParser.parse(
                "Usage: /dev/input/getevent [-t] [-n] [-r] [-s] [-S] [-c count] [device]",
            ),
        )
        assertNull(GeteventParser.parse("could not open /dev/input/event99"))
        assertNull(GeteventParser.parse("Permission denied"))
    }

    /** 字段不足时不要硬凑出一个事件 */
    @Test
    fun `字段不足的行返回 null`() {
        assertNull(GeteventParser.parse("0001 0011"))
        assertNull(GeteventParser.parse("[  1.000000] /dev/input/event3:"))
    }
}

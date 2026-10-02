package com.something.sthkey.capture.shizuku

import com.something.sthkey.capture.InputEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `getevent -q` 输出解析的测试。
 *
 * ============================================================
 * 为什么这些用例值得写
 * ============================================================
 * 这条通道拿到的是**文本**，解析错了不会崩、也不会报错 ——
 * 只会"某些键不亮"。而按键不亮的排查成本极高（用户不知道为什么，
 * 我们看不出哪一步没对上）。
 *
 * 三个最容易错、且都真的会发生的点：
 * 1. **负数**：`getevent` 把字段按 int 打印，鼠标往左/上动是 `ffffffff`。
 *    用 `toInt(16)` 解析会**抛异常**，于是那半个方向的鼠标事件全丢 ——
 *    表现成"鼠标只能往右下动"，会被误判成硬件问题；
 * 2. **设备前缀**：一次监听多个设备时，每行会多一段 `/dev/input/event3:`；
 * 3. **非事件行**：设备切换头、空行、错误文本都要安全地丢弃而不是崩。
 */
class GeteventParserTest {

    /*
     * ============================================================
     * 基本格式
     * ============================================================
     */

    @Test
    fun `解析单设备的一行`() {
        // KEY_Q 按下：type=1 code=16 value=1
        assertEquals(
            InputEvent(type = 0x01, code = 0x10, value = 1),
            GeteventParser.parse("0001 0010 00000001"),
        )
    }

    @Test
    fun `解析按下与抬起`() {
        assertEquals(
            InputEvent(0x01, 0x10, InputEvent.VALUE_DOWN),
            GeteventParser.parse("0001 0010 00000001"),
        )
        assertEquals(
            InputEvent(0x01, 0x10, InputEvent.VALUE_UP),
            GeteventParser.parse("0001 0010 00000000"),
        )
    }

    @Test
    fun `解析鼠标左键`() {
        // BTN_LEFT = 0x110
        assertEquals(
            InputEvent(0x01, 0x110, InputEvent.VALUE_DOWN),
            GeteventParser.parse("0001 0110 00000001"),
        )
    }

    @Test
    fun `容忍行首行尾空白`() {
        // 从管道读回来的行可能带 \r（不同 toybox 版本），trim 之后要能解析
        assertEquals(
            InputEvent(0x01, 0x10, 1),
            GeteventParser.parse("  0001 0010 00000001  "),
        )
    }

    /*
     * ============================================================
     * ⚠️ 负数：这条最重要
     * ============================================================
     */

    /**
     * 鼠标往左移动：`REL_X` = -1，`getevent` 打印成 `ffffffff`。
     *
     * 如果解析器用 `toInt(16)`，这里会抛 NumberFormatException 并丢掉整行 ——
     * 于是用户看到"鼠标只能往右下动"。这条测试就是钉住这个坑。
     */
    @Test
    fun `鼠标向左移动的负位移要能解析`() {
        val event = GeteventParser.parse("0002 0000 ffffffff")

        assertEquals(
            "EV_REL / REL_X / -1",
            InputEvent(type = InputEvent.EV_REL, code = InputEvent.REL_X, value = -1),
            event,
        )
    }

    @Test
    fun `鼠标向上移动的负位移要能解析`() {
        assertEquals(
            InputEvent(type = InputEvent.EV_REL, code = InputEvent.REL_Y, value = -1),
            GeteventParser.parse("0002 0001 ffffffff"),
        )
    }

    @Test
    fun `向右下的正位移按原值解析`() {
        assertEquals(
            InputEvent(InputEvent.EV_REL, InputEvent.REL_X, 5),
            GeteventParser.parse("0002 0000 00000005"),
        )
    }

    /*
     * ============================================================
     * 多设备前缀
     * ============================================================
     */

    @Test
    fun `带设备路径前缀的行要能解析`() {
        // 一次监听多个设备时，getevent 会给每行加设备前缀
        assertEquals(
            InputEvent(0x01, 0x10, 1),
            GeteventParser.parse("/dev/input/event3: 0001 0010 00000001"),
        )
    }

    @Test
    fun `带前缀的负位移也要能解析`() {
        assertEquals(
            InputEvent(InputEvent.EV_REL, InputEvent.REL_X, -1),
            GeteventParser.parse("/dev/input/event5: 0002 0000 ffffffff"),
        )
    }

    @Test
    fun `设备编号是两位数时同样能解析`() {
        // event10 及以上：前缀变长，不能按固定列宽切
        assertEquals(
            InputEvent(0x01, 0x10, 1),
            GeteventParser.parse("/dev/input/event12: 0001 0010 00000001"),
        )
    }

    /*
     * ============================================================
     * 不该崩的行
     * ============================================================
     */

    @Test
    fun `空行返回 null`() {
        assertNull(GeteventParser.parse(""))
        assertNull(GeteventParser.parse("   "))
    }

    @Test
    fun `设备切换头返回 null`() {
        // getevent 在多设备模式下会打印这种头，它不是事件
        assertNull(GeteventParser.parse("add device 1: /dev/input/event3"))
        assertNull(GeteventParser.parse("/dev/input/event3: could not get driver version"))
    }

    @Test
    fun `字段不足三列返回 null`() {
        assertNull(GeteventParser.parse("0001 0010"))
        assertNull(GeteventParser.parse("0001"))
    }

    @Test
    fun `字段不是十六进制时返回 null`() {
        // 远端报错文本会混进同一个流（stderr 被合并），必须安全丢弃
        assertNull(GeteventParser.parse("getevent: not found"))
        assertNull(GeteventParser.parse("zzzz 0010 00000001"))
    }
}

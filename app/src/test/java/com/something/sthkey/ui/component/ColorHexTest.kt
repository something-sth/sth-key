package com.something.sthkey.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 颜色文本的解析 / 格式化 / 输入整理。
 *
 * 这些函数是取色对话框与所有 `HexColorRow` 共用的唯一入口，
 * 钉住它们等价于钉住"颜色只存 6 位 RGB"这条不变量。
 */
class ColorHexTest {

    // ---------------- parseHexColor ----------------

    @Test
    fun `解析接受纯 hex、井号与前缀`() {
        assertEquals(0xFF8800L, parseHexColor("FF8800"))
        assertEquals(0xFF8800L, parseHexColor("#FF8800"))
        assertEquals(0xFF8800L, parseHexColor("0xFF8800"))
        assertEquals(0xFF8800L, parseHexColor("0XFF8800"))
        assertEquals(0xFF8800L, parseHexColor("  ff8800  "))
    }

    @Test
    fun `长度不是 6 位一律拒绝`() {
        assertNull(parseHexColor(""))
        assertNull(parseHexColor("#"))
        assertNull(parseHexColor("FF880"))
        assertNull(parseHexColor("FF8800F"))
        assertNull(parseHexColor("FFFF8800"))
    }

    @Test
    fun `非十六进制字符一律拒绝`() {
        assertNull(parseHexColor("GGGGGG"))
        assertNull(parseHexColor("FF 800"))
        assertNull(parseHexColor("FF88-0"))
    }

    @Test
    fun `解析结果永远不含 alpha`() {
        /* 若哪天有人把 8 位 ARGB 塞进来，这里会先炸 */
        val parsed = parseHexColor("FFFFFF")!!
        assertEquals(0xFFFFFFL, parsed)
        assertEquals(0L, parsed and 0xFF000000L)
    }

    // ---------------- format ----------------

    @Test
    fun `格式化永远 6 位大写`() {
        assertEquals("#000000", formatHexColor(0L))
        assertEquals("#FF8800", formatHexColor(0xFF8800L))
        assertEquals("#0A0B0C", formatHexColor(0x0A0B0CL))
        assertEquals("000000", formatHexDigits(0L))
        assertEquals("FF8800", formatHexDigits(0xFF8800L))
    }

    @Test
    fun `带 alpha 的值也会被截成 RGB`() {
        /* 历史配置里可能残留 0xB3000000 这类带 alpha 的值，格式化时不能把它带出去 */
        assertEquals("#000000", formatHexColor(0xB3000000L))
        assertEquals("000000", formatHexDigits(0xB3000000L))
    }

    // ---------------- normalizeHexDigits ----------------

    @Test
    fun `输入整理会剥前缀、过滤、截断并大写`() {
        assertEquals("FF8800", normalizeHexDigits("#ff8800"))
        assertEquals("FF8800", normalizeHexDigits("0xFF8800"))
        assertEquals("FF8800", normalizeHexDigits("0Xff8800"))
        assertEquals("FF8800", normalizeHexDigits("  ff8800  "))
        assertEquals("FF880", normalizeHexDigits("FF880"))
        assertEquals("", normalizeHexDigits(""))
    }

    @Test
    fun `粘贴 0x 前缀不会被截成错位的 7 位`() {
        /*
         * ⚠️ 回归点：先过滤再剥前缀的话，"0xFF8800" 会变成 "0FF880"（少一位且错位）。
         * 这里钉住"先剥前缀"的顺序。
         */
        assertEquals("FF8800", normalizeHexDigits("0xFF8800"))
        assertEquals(0xFF8800L, parseHexColor(normalizeHexDigits("0xFF8800")))
    }

    @Test
    fun `超出 6 位只保留前 6 位`() {
        assertEquals("FF8800", normalizeHexDigits("FF880012"))
        assertEquals("AABBCC", normalizeHexDigits("#AABBCCDD"))
    }

    @Test
    fun `非十六进制字符被丢掉`() {
        assertEquals("FF8800", normalizeHexDigits("FF 88 00"))
        assertEquals("ABCDEF", normalizeHexDigits("ab-cd-ef"))
        assertEquals("", normalizeHexDigits("xyz"))
    }

    @Test
    fun `整理后的文本可以被解析回来（往返）`() {
        listOf("000000", "FFFFFF", "FF8800", "0A0B0C").forEach { hex ->
            val parsed = parseHexColor(normalizeHexDigits(hex))
            assertEquals(hex, formatHexDigits(parsed!!))
        }
    }
}

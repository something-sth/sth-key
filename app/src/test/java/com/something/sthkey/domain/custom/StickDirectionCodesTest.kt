package com.something.sthkey.domain.custom

import com.something.sthkey.domain.keys.KeyCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手柄摇杆八段式方向（2026-11-15）。
 *
 * ============================================================
 * ⚠️ 这里钉住的是"方向怎么算"，不是"窗口怎么画"
 * ============================================================
 * | 规则 | 破了会怎样 |
 * |---|---|
 * | 45° 八扇区 | 斜推同时触发两个正向（用户要的是八段式独立方向）|
 * | 阈值 0.5 | 摇杆回中抖动也会触发 → 按键乱闪、CPS 乱涨 |
 * | 左右独立 | 推左摇杆却触发右摇杆的绑定 |
 */
class StickDirectionCodesTest {

    private val allDirections = TouchDirection.entries

    @Test
    fun `十六个方向码与左右方向一一往返`() {
        val seen = mutableSetOf<Int>()
        StickSide.entries.forEach { side ->
            allDirections.forEach { direction ->
                val code = StickDirectionCodes.codeOf(side, direction)
                assertTrue("$side/$direction 的码重复了", seen.add(code))
                assertEquals(side, StickDirectionCodes.sideOf(code))
                assertEquals(direction, StickDirectionCodes.directionOf(code))
                assertTrue("$code 不在摇杆区间", KeyCodes.isStickDirection(code))
            }
        }
        assertEquals(16, seen.size)
    }

    @Test
    fun `摇杆不动时没有方向`() {
        assertEquals(emptySet<Int>(), StickDirectionCodes.activeCodes(0f, 0f, 0f, 0f))
    }

    @Test
    fun `低于阈值不算方向`() {
        /* 0.3 在阈值 0.5 以下 —— 摇杆漂移/轻碰不该触发 */
        assertEquals(
            emptySet<Int>(),
            StickDirectionCodes.activeCodes(0.3f, 0f, 0f, 0f),
        )
    }

    @Test
    fun `八个方向各自只触发自己`() {
        val vectors = mapOf(
            TouchDirection.FRONT to (0f to -1f),
            TouchDirection.BACK to (0f to 1f),
            TouchDirection.LEFT to (-1f to 0f),
            TouchDirection.RIGHT to (1f to 0f),
            TouchDirection.FRONT_LEFT to (-1f to -1f),
            TouchDirection.FRONT_RIGHT to (1f to -1f),
            TouchDirection.BACK_LEFT to (-1f to 1f),
            TouchDirection.BACK_RIGHT to (1f to 1f),
        )
        vectors.forEach { (direction, xy) ->
            assertEquals(
                "推 $direction 时应当只触发它自己",
                setOf(StickDirectionCodes.codeOf(StickSide.LEFT, direction)),
                StickDirectionCodes.activeCodes(xy.first, xy.second, 0f, 0f),
            )
        }
    }

    @Test
    fun `斜推只命中斜向（不会同时触发两个正向）`() {
        /* (0.7, -0.7) = 右上:只该命中 FRONT_RIGHT */
        assertEquals(
            setOf(StickDirectionCodes.codeOf(StickSide.LEFT, TouchDirection.FRONT_RIGHT)),
            StickDirectionCodes.activeCodes(0.7f, -0.7f, 0f, 0f),
        )
    }

    @Test
    fun `两只摇杆可以同时触发各自的码`() {
        val codes = StickDirectionCodes.activeCodes(
            lx = 0f, ly = -1f, // 左上
            rx = 1f, ry = 0f, // 右右
        )
        assertEquals(
            setOf(
                KeyCodes.STICK_LEFT_UP,
                KeyCodes.STICK_RIGHT_RIGHT,
            ),
            codes,
        )
    }

    @Test
    fun `右摇杆与左摇杆互不串台`() {
        assertEquals(
            setOf(KeyCodes.STICK_RIGHT_DOWN_LEFT),
            StickDirectionCodes.activeCodes(0f, 0f, -0.8f, 0.8f),
        )
    }

    @Test
    fun `不属于摇杆方向的码返回 null`() {
        assertNull(StickDirectionCodes.sideOf(KeyCodes.BTN_SOUTH))
        assertNull(StickDirectionCodes.directionOf(30))
    }
}

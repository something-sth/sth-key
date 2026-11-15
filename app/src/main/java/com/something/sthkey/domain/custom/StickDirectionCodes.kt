package com.something.sthkey.domain.custom

import com.something.sthkey.domain.keys.KeyCodes
import kotlin.math.hypot

/**
 * 手柄摇杆**八段式方向** ↔ 内部码（2026-11-15）。
 *
 * ============================================================
 * ⚠️ 方向没有 evdev 键码
 * ============================================================
 * 设备只上报 `ABS_X` / `ABS_Y` 两个**轴**，没有"上/下/左/右"这样的按键事件。
 * 所以方向由采集层每帧从摇杆向量算出来，用一个**保留的内部码**表示
 * （[KeyCodes.STICK_LEFT_UP] 等）。这个码:
 *
 * - 只活在内存里（`pressedCodes` / CPS 桶），**永远不写进 JSON**；
 * - JSON 里存的是 `JOYSTICK_LEFT_UP` 这样的明文 ID（见 `KeyIds`）。
 *
 * ============================================================
 * ⚠️ 八段式 = 45° 扇区，斜向是**独立方向**
 * ============================================================
 * 与触屏完全同一套（[TouchDirection.resolve]）：
 * 推右上只命中"右上"，不会同时命中"上"和"右"。
 *
 * ============================================================
 * ⚠️ 阈值
 * ============================================================
 * 推杆长度要 >= [ENTER_MAGNITUDE] 才算方向。**不复用**
 * `JoystickStyle.deadZone` —— 那是"显示死区"（默认 0），
 * 用在方向判定上会让摇杆漂移就乱触发。
 */
object StickDirectionCodes {

    /**
     * 触发方向的最小推杆长度（`0..1`）。
     *
     * ⚠️ 0.5 是"明显推出去"的位置:太低会被摇杆回中抖动触发，
     * 太高会让轻推不响应。真机上如果发现临界抖动，再加"进入 0.5 / 离开 0.35"
     * 的滞回 —— 先保持单一阈值，行为最好解释。
     */
    const val ENTER_MAGNITUDE = 0.5f

    /** 方向 → 内部码 */
    fun codeOf(side: StickSide, direction: TouchDirection): Int = when (side) {
        StickSide.LEFT -> when (direction) {
            TouchDirection.FRONT -> KeyCodes.STICK_LEFT_UP
            TouchDirection.BACK -> KeyCodes.STICK_LEFT_DOWN
            TouchDirection.LEFT -> KeyCodes.STICK_LEFT_LEFT
            TouchDirection.RIGHT -> KeyCodes.STICK_LEFT_RIGHT
            TouchDirection.FRONT_LEFT -> KeyCodes.STICK_LEFT_UP_LEFT
            TouchDirection.FRONT_RIGHT -> KeyCodes.STICK_LEFT_UP_RIGHT
            TouchDirection.BACK_LEFT -> KeyCodes.STICK_LEFT_DOWN_LEFT
            TouchDirection.BACK_RIGHT -> KeyCodes.STICK_LEFT_DOWN_RIGHT
        }

        StickSide.RIGHT -> when (direction) {
            TouchDirection.FRONT -> KeyCodes.STICK_RIGHT_UP
            TouchDirection.BACK -> KeyCodes.STICK_RIGHT_DOWN
            TouchDirection.LEFT -> KeyCodes.STICK_RIGHT_LEFT
            TouchDirection.RIGHT -> KeyCodes.STICK_RIGHT_RIGHT
            TouchDirection.FRONT_LEFT -> KeyCodes.STICK_RIGHT_UP_LEFT
            TouchDirection.FRONT_RIGHT -> KeyCodes.STICK_RIGHT_UP_RIGHT
            TouchDirection.BACK_LEFT -> KeyCodes.STICK_RIGHT_DOWN_LEFT
            TouchDirection.BACK_RIGHT -> KeyCodes.STICK_RIGHT_DOWN_RIGHT
        }
    }

    /** 内部码 → 哪只摇杆；不是摇杆方向返回 null */
    fun sideOf(code: Int): StickSide? = when (code) {
        KeyCodes.STICK_LEFT_UP,
        KeyCodes.STICK_LEFT_DOWN,
        KeyCodes.STICK_LEFT_LEFT,
        KeyCodes.STICK_LEFT_RIGHT,
        KeyCodes.STICK_LEFT_UP_LEFT,
        KeyCodes.STICK_LEFT_UP_RIGHT,
        KeyCodes.STICK_LEFT_DOWN_LEFT,
        KeyCodes.STICK_LEFT_DOWN_RIGHT,
        -> StickSide.LEFT

        KeyCodes.STICK_RIGHT_UP,
        KeyCodes.STICK_RIGHT_DOWN,
        KeyCodes.STICK_RIGHT_LEFT,
        KeyCodes.STICK_RIGHT_RIGHT,
        KeyCodes.STICK_RIGHT_UP_LEFT,
        KeyCodes.STICK_RIGHT_UP_RIGHT,
        KeyCodes.STICK_RIGHT_DOWN_LEFT,
        KeyCodes.STICK_RIGHT_DOWN_RIGHT,
        -> StickSide.RIGHT

        else -> null
    }

    /** 内部码 → 方向；不是摇杆方向返回 null */
    fun directionOf(code: Int): TouchDirection? = when (code) {
        KeyCodes.STICK_LEFT_UP, KeyCodes.STICK_RIGHT_UP -> TouchDirection.FRONT
        KeyCodes.STICK_LEFT_DOWN, KeyCodes.STICK_RIGHT_DOWN -> TouchDirection.BACK
        KeyCodes.STICK_LEFT_LEFT, KeyCodes.STICK_RIGHT_LEFT -> TouchDirection.LEFT
        KeyCodes.STICK_LEFT_RIGHT, KeyCodes.STICK_RIGHT_RIGHT -> TouchDirection.RIGHT
        KeyCodes.STICK_LEFT_UP_LEFT, KeyCodes.STICK_RIGHT_UP_LEFT -> TouchDirection.FRONT_LEFT
        KeyCodes.STICK_LEFT_UP_RIGHT, KeyCodes.STICK_RIGHT_UP_RIGHT -> TouchDirection.FRONT_RIGHT
        KeyCodes.STICK_LEFT_DOWN_LEFT, KeyCodes.STICK_RIGHT_DOWN_LEFT -> TouchDirection.BACK_LEFT
        KeyCodes.STICK_LEFT_DOWN_RIGHT, KeyCodes.STICK_RIGHT_DOWN_RIGHT -> TouchDirection.BACK_RIGHT
        else -> null
    }

    /**
     * 这一刻两只摇杆各自触发的方向码。
     *
     * ⚠️ **必须传一帧完整的状态**（四个轴一起）—— 见 [CaptureSession.stickDirections]
     * 的说明：按单轴更新会产生"右 → 右上"的假方向。
     *
     * @param threshold 推杆长度阈值；单测可以传别的值
     */
    fun activeCodes(
        lx: Float,
        ly: Float,
        rx: Float,
        ry: Float,
        threshold: Float = ENTER_MAGNITUDE,
    ): Set<Int> {
        val result = mutableSetOf<Int>()
        codeFor(StickSide.LEFT, lx, ly, threshold)?.let { result += it }
        codeFor(StickSide.RIGHT, rx, ry, threshold)?.let { result += it }
        return result
    }

    private fun codeFor(side: StickSide, x: Float, y: Float, threshold: Float): Int? {
        if (hypot(x, y) < threshold) return null
        val direction = TouchDirection.resolve(x, y) ?: return null
        return codeOf(side, direction)
    }
}

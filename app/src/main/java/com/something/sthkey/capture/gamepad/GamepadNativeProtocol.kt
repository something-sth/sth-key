package com.something.sthkey.capture.gamepad

import kotlin.math.abs

/**
 * 原生手柄 monitor 的**文本协议**。
 *
 * ============================================================
 * 协议长什么样
 * ============================================================
 * ```
 * STATUS gamepad-ready <设备名与说明>
 * STATUS gamepad-disconnected
 * STATUS waiting-gamepad
 * GAMEPAD <lx> <ly> <rx> <ry> <lt> <rt> <buttons>
 * ```
 *
 * | 字段 | 范围 | 说明 |
 * |---|---|---|
 * | `lx ly rx ry` | `-1000 .. 1000` | 左右摇杆，**已经归一化且校准过中点** |
 * | `lt rt` | `0 .. 1000` | 扳机（单边） |
 * | `buttons` | 位掩码 | 见 [canonicalButtons] |
 *
 * ============================================================
 * ⚠️ 为什么改用 native（这段历史不要删）
 * ============================================================
 * 一开始是纯 Kotlin 从 `getevent` 文本流里自己解析手柄，踩了一串坑:
 *
 * 1. **`getevent` 不打印轴范围** —— 只给裸 ADC 值。要拿 `min/max/flat`
 *    得另外跑 `getevent -i`，于是又要在设备热插拔时补探、
 *    补探期间还要用"假定范围"顶着（那段时间读出来的值是错的）；
 * 2. **中点校准不可靠** —— 探测那一刻用户要是正推着摇杆，
 *    读到的"静止值"就不是中点，于是松手后拇指**偏向一个角落**
 *    （真实发生的 bug，反复复现）；
 * 3. **左右摇杆的轴位置不固定** —— 有的手柄右摇杆在 `ABS_Z/ABS_RZ`，
 *    有的扳机在 `ABS_BRAKE/ABS_GAS`，还有的把按键与摇杆拆到不同节点；
 * 4. **没有 `SYN_REPORT` 聚合** —— 每个轴单独上报，一次物理动作
 *    会触发好几个回调，动画被反复打断（表现为卡顿）。
 *
 * 原生 monitor 用 `ioctl(EVIOCGABS)` **随时**读轴能力、
 * 自己做中点校准与设备挑选、按 `SYN_REPORT` 聚合成**一份完整状态**输出。
 * 前三条它全解决了，第四条也顺带解决。
 *
 * ⚠️ 所以这个类**只是解析**，不要在这里加任何"猜测性"的逻辑
 * （比如自己再校一次中点）—— 那会把 native 已经做对的事情弄坏。
 */
object GamepadNativeProtocol {

    /** 一份完整的语义状态 */
    data class State(
        val lx: Int,
        val ly: Int,
        val rx: Int,
        val ry: Int,
        val lt: Int,
        val rt: Int,
        val buttons: Long,
    )

    /**
     * 解析 `GAMEPAD …` 行。
     *
     * ⚠️ 字段数**必须恰好 8**（命令名 + 7 个数）。
     * 用"至少"的话，以后协议加字段时会把新字段当成按键掩码解析，
     * 那是一个**静默的错**（按键全亮或全不亮）。
     */
    fun parseState(line: String?): State? {
        if (line.isNullOrBlank() || !line.startsWith(PREFIX)) return null
        val parts = line.trim().split(' ')
        if (parts.size != 8) return null

        return State(
            lx = parts[1].toIntOrNull()?.coerceIn(-1000, 1000) ?: return null,
            ly = parts[2].toIntOrNull()?.coerceIn(-1000, 1000) ?: return null,
            rx = parts[3].toIntOrNull()?.coerceIn(-1000, 1000) ?: return null,
            ry = parts[4].toIntOrNull()?.coerceIn(-1000, 1000) ?: return null,
            lt = parts[5].toIntOrNull()?.coerceIn(0, 1000) ?: return null,
            rt = parts[6].toIntOrNull()?.coerceIn(0, 1000) ?: return null,
            buttons = (parts[7].toLongOrNull() ?: return null) and 0xffffffffL,
        )
    }

    /** 手柄就绪（后面跟设备描述） */
    fun isReady(line: String?): Boolean = line?.startsWith("$PREFIX_STATUS gamepad-ready ") == true

    /** 手柄断开 / 还在等 */
    fun isDisconnected(line: String?): Boolean =
        line?.startsWith("$PREFIX_STATUS gamepad-disconnected") == true ||
            line?.startsWith("$PREFIX_STATUS waiting-gamepad") == true

    /** 就绪行里的设备描述（日志用） */
    fun readyDetail(line: String): String =
        line.removePrefix("$PREFIX_STATUS gamepad-ready ").trim()

    /**
     * 位掩码 → 一组 **evdev 按键码**。
     *
     * ============================================================
     * ⚠️ 用的是 evdev 的**标准**对应，不是按某台手柄校准过的
     * ============================================================
     * |
     * | 位 | 码 | Xbox 上的名字 |
     * |---|---|---|
     * | 0 | `BTN_SOUTH` (0x130) | A |
     * | 1 | `BTN_EAST` (0x131) | B |
     * | 3 | `BTN_NORTH` (0x133) | Y |
     * | 4 | `BTN_WEST` (0x134) | X |
     * | 20..23 | `BTN_DPAD_*` | 方向键 |
     *
     * 之前那套纯 Kotlin 实现里，我按用户实测把 X/Y 与 A/B **对调**过 ——
     * 但那是在"自己映射方位"的前提下才需要的。
     *
     * 现在 native 输出的掩码位序是**固定的**（它内部已经把厂商的方位约定
     * 归一化到标准位序了），所以这里用标准对应即可。
     *
     * ⚠️ 如果实测仍然发现某个键对不上，那说明 native 的归一化与
     * 这台手柄不符 —— **改的地方在 native 那张 `buttonIndex` 表里**，
     * 不是在这里翻。在这里翻会让别的设备跟着错。
     */
    fun canonicalButtons(mask: Long, out: MutableSet<Int>) {
        out.clear()
        fun add(bit: Int, code: Int) {
            if (mask and (1L shl bit) != 0L) out.add(code)
        }

        /* 面键。2 与 5 是旧式手柄的 BTN_C / BTN_Z，与 WEST/EAST 同义 */
        add(0, BTN_SOUTH)
        add(1, BTN_EAST)
        add(2, BTN_WEST)
        add(3, BTN_NORTH)
        add(4, BTN_WEST)
        add(5, BTN_EAST)

        /* 肩键与扳机 */
        add(6, BTN_TL)
        add(7, BTN_TR)
        add(8, BTN_TL2)
        add(9, BTN_TR2)

        /* 功能键 */
        add(10, BTN_SELECT)
        add(11, BTN_START)
        add(12, BTN_MODE)
        add(13, BTN_THUMBL)
        add(14, BTN_THUMBR)

        /* 背键（部分国产手柄的 M1-M4） */
        add(15, BTN_TRIGGER_HAPPY1)
        add(16, BTN_TRIGGER_HAPPY2)
        add(17, BTN_TRIGGER_HAPPY3)
        add(18, BTN_TRIGGER_HAPPY4)

        /* 方向键 */
        add(20, BTN_DPAD_UP)
        add(21, BTN_DPAD_DOWN)
        add(22, BTN_DPAD_LEFT)
        add(23, BTN_DPAD_RIGHT)
    }

    /**
     * 摇杆值 → `-1f .. 1f`。
     *
     * ⚠️ 末尾那个"极小值归零"是有意的:某些手柄静止时会输出 `1` 或 `-1`
     * （千分之一），不归零的话拇指会有**肉眼可见的偏移**。
     */
    fun axisFloat(value: Int): Float {
        val f = value.coerceIn(-1000, 1000) / 1000f
        return if (abs(f) < NEAR_ZERO) 0f else f
    }

    /** 扳机值 → `0f .. 1f`（单边，没有负值） */
    fun triggerFloat(value: Int): Float {
        val f = value.coerceIn(0, 1000) / 1000f
        return if (f < NEAR_ZERO) 0f else f
    }

    private const val PREFIX = "GAMEPAD"
    private const val PREFIX_STATUS = "STATUS"

    /** 小于它就当作 0（千分之一） */
    private const val NEAR_ZERO = 0.0005f

    /* ============================================================
     * evdev 按键码（与 KeyCodes 里的值必须一致，但这里不依赖它 ——
     * 本文件是"协议层"，不该依赖领域层）
     * ============================================================ */

    private const val BTN_SOUTH = 0x130
    private const val BTN_EAST = 0x131
    private const val BTN_NORTH = 0x133
    private const val BTN_WEST = 0x134
    private const val BTN_TL = 0x136
    private const val BTN_TR = 0x137
    private const val BTN_TL2 = 0x138
    private const val BTN_TR2 = 0x139
    private const val BTN_SELECT = 0x13a
    private const val BTN_START = 0x13b
    private const val BTN_MODE = 0x13c
    private const val BTN_THUMBL = 0x13d
    private const val BTN_THUMBR = 0x13e
    private const val BTN_DPAD_UP = 0x220
    private const val BTN_DPAD_DOWN = 0x221
    private const val BTN_DPAD_LEFT = 0x222
    private const val BTN_DPAD_RIGHT = 0x223
    private const val BTN_TRIGGER_HAPPY1 = 0x2c0
    private const val BTN_TRIGGER_HAPPY2 = 0x2c1
    private const val BTN_TRIGGER_HAPPY3 = 0x2c2
    private const val BTN_TRIGGER_HAPPY4 = 0x2c3
}

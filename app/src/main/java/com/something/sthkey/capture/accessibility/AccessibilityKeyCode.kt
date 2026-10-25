package com.something.sthkey.capture.accessibility

import android.view.KeyEvent

/**
 * **Android `KeyEvent.keyCode` → Linux evdev 键码**。
 *
 * ============================================================
 * ⚠️⚠️ 为什么必须有这张表（一个真实的 bug）
 * ============================================================
 * 我第一版**直接把 `event.keyCode` 当成 evdev 码上报**，理由是"两者应该是同一套编号"。
 * ⚠️ **这个假设是错的**，用户实测:
 *
 * > 纯无障碍模式，监听得到但是有问题，**很多按键对应的不是原本按键**，
 * > 有些按键还反映不出来，我怀疑是不是无障碍收到的按键数据与直接读
 * > `/dev/input` 得到的不一样？
 *
 * ⚠️ 一样才怪。两套编号**完全不同**:
 *
 * | 按键 | Android `KEYCODE_*` | Linux `KEY_*`（evdev） |
 * |---|---|---|
 * | A | 29 | **30** |
 * | S | 47 | **31** |
 * | Q | 45 | **16** |
 * | Space | 62 | **57** |
 * | Shift 左 | 59 | **42** |
 * | Ctrl 左 | 113 | **29** |
 * | Enter | 66 | **28** |
 *
 * ⚠️ 于是"按 A"会被当成 **29 = Ctrl 左**、"按 S"被当成 **47 = V** ——
 * 正是用户说的"对应错了"。而表里没有的键码返回 -1，表现为"反映不出来"。
 *
 * ⚠️ 本项目的 [com.something.sthkey.domain.keys.KeyCodes] **只认 evdev 码**
 * （它文件头第 9~13 行就把这条坑写清楚了）。所以转换必须在这一个边界处做。
 *
 * ============================================================
 * ⚠️ 这张表照着 Axon 抄的（用户让我参考它）
 * ============================================================
 * 出处（**只读，绝不修改**）:
 * `Axon-Input-main/app/src/main/java/com/axon/input/GamepadMappingStore.java:82`
 * 的 `keyboardEvdevCode(int keyCode)`。
 *
 * ⚠️ 它覆盖的是**标准 101/104 键盘**能发出的键。照抄而没自己扩展，因为:
 * · 这些编码是 Linux 内核固定的（`input-event-codes.h`），不是随手编的；
 * · 多写一条错的比少写一条更糟（错误的映射会让"按 A 亮 B"重新出现）。
 *
 * ⚠️ 表里**没有**的键（音量、返回、HOME、媒体键等）返回 [UNKNOWN]。
 * 那些键本来就**不该**由无障碍来显示 —— 它们不是"键盘按键"，
 * 而且系统会优先处理（无障碍拦不住）。
 */
object AccessibilityKeyCode {

    /** 无法映射 */
    const val UNKNOWN: Int = -1

    /**
     * 转换；认不出的返回 [UNKNOWN]。
     *
     * ⚠️ 调用方**必须**处理 `UNKNOWN`（丢掉这个事件），
     * 不能把它当成 `code = -1` 上报 —— 那个键码在 evdev 里不存在，
     * 上报上去只会在悬浮窗上显示成一个没人认识的键。
     */
    fun toEvdev(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_ESCAPE -> 1

        KeyEvent.KEYCODE_1 -> 2
        KeyEvent.KEYCODE_2 -> 3
        KeyEvent.KEYCODE_3 -> 4
        KeyEvent.KEYCODE_4 -> 5
        KeyEvent.KEYCODE_5 -> 6
        KeyEvent.KEYCODE_6 -> 7
        KeyEvent.KEYCODE_7 -> 8
        KeyEvent.KEYCODE_8 -> 9
        KeyEvent.KEYCODE_9 -> 10
        KeyEvent.KEYCODE_0 -> 11

        KeyEvent.KEYCODE_MINUS -> 12
        KeyEvent.KEYCODE_EQUALS -> 13
        /* ⚠️ `KEYCODE_DEL` 是**退格**，对应 evdev 的 KEY_BACKSPACE(14)，不是 Delete(111) */
        KeyEvent.KEYCODE_DEL -> 14
        KeyEvent.KEYCODE_TAB -> 15

        KeyEvent.KEYCODE_Q -> 16
        KeyEvent.KEYCODE_W -> 17
        KeyEvent.KEYCODE_E -> 18
        KeyEvent.KEYCODE_R -> 19
        KeyEvent.KEYCODE_T -> 20
        KeyEvent.KEYCODE_Y -> 21
        KeyEvent.KEYCODE_U -> 22
        KeyEvent.KEYCODE_I -> 23
        KeyEvent.KEYCODE_O -> 24
        KeyEvent.KEYCODE_P -> 25

        KeyEvent.KEYCODE_LEFT_BRACKET -> 26
        KeyEvent.KEYCODE_RIGHT_BRACKET -> 27
        KeyEvent.KEYCODE_ENTER -> 28

        /*
         * ⚠️ 注意这两个容易混的行:
         * · `KEYCODE_CTRL_LEFT`(113) → **29**（evdev 的 KEY_LEFTCTRL）
         * · `KEYCODE_A`(29)         → **30**
         * ⚠️ 两个数字看着"错位"，但那正是两套编号的真实关系 ——
         * 别"顺手改正"，改了就回到"按 A 亮 Ctrl"那个 bug。
         */
        KeyEvent.KEYCODE_CTRL_LEFT -> 29
        KeyEvent.KEYCODE_A -> 30
        KeyEvent.KEYCODE_S -> 31
        KeyEvent.KEYCODE_D -> 32
        KeyEvent.KEYCODE_F -> 33
        KeyEvent.KEYCODE_G -> 34
        KeyEvent.KEYCODE_H -> 35
        KeyEvent.KEYCODE_J -> 36
        KeyEvent.KEYCODE_K -> 37
        KeyEvent.KEYCODE_L -> 38

        KeyEvent.KEYCODE_SEMICOLON -> 39
        KeyEvent.KEYCODE_APOSTROPHE -> 40
        KeyEvent.KEYCODE_GRAVE -> 41
        KeyEvent.KEYCODE_SHIFT_LEFT -> 42
        KeyEvent.KEYCODE_BACKSLASH -> 43

        KeyEvent.KEYCODE_Z -> 44
        KeyEvent.KEYCODE_X -> 45
        KeyEvent.KEYCODE_C -> 46
        KeyEvent.KEYCODE_V -> 47
        KeyEvent.KEYCODE_B -> 48
        KeyEvent.KEYCODE_N -> 49
        KeyEvent.KEYCODE_M -> 50

        KeyEvent.KEYCODE_COMMA -> 51
        KeyEvent.KEYCODE_PERIOD -> 52
        KeyEvent.KEYCODE_SLASH -> 53
        KeyEvent.KEYCODE_SHIFT_RIGHT -> 54

        KeyEvent.KEYCODE_ALT_LEFT -> 56
        KeyEvent.KEYCODE_SPACE -> 57
        KeyEvent.KEYCODE_CAPS_LOCK -> 58

        KeyEvent.KEYCODE_F1 -> 59
        KeyEvent.KEYCODE_F2 -> 60
        KeyEvent.KEYCODE_F3 -> 61
        KeyEvent.KEYCODE_F4 -> 62
        KeyEvent.KEYCODE_F5 -> 63
        KeyEvent.KEYCODE_F6 -> 64
        KeyEvent.KEYCODE_F7 -> 65
        KeyEvent.KEYCODE_F8 -> 66
        KeyEvent.KEYCODE_F9 -> 67
        KeyEvent.KEYCODE_F10 -> 68
        /* ⚠️ F11/F12 是 87/88，不是 69/70 —— 中间那一段是别的键 */
        KeyEvent.KEYCODE_F11 -> 87
        KeyEvent.KEYCODE_F12 -> 88

        KeyEvent.KEYCODE_CTRL_RIGHT -> 97
        KeyEvent.KEYCODE_ALT_RIGHT -> 100

        KeyEvent.KEYCODE_META_LEFT -> 125
        KeyEvent.KEYCODE_META_RIGHT -> 126

        else -> UNKNOWN
    }

    /**
     * 这个 `KeyEvent` 能不能被我们显示。
     *
     * ⚠️ 给调用方一个"要不要上报"的判据，而不是让它去 `!= UNKNOWN`
     * —— 以后如果加了"某些映射到了但依然不显示"的规则（比如音量键），
     * 改这里就行。
     */
    fun isMappable(keyCode: Int): Boolean = toEvdev(keyCode) != UNKNOWN
}

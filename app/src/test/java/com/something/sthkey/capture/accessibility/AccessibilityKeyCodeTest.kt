package com.something.sthkey.capture.accessibility

import android.view.KeyEvent
import com.something.sthkey.domain.keys.KeyCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `KeyEvent.keyCode → evdev` 的转换表。
 *
 * ============================================================
 * ⚠️⚠️ 为什么这些测试这么"琐碎"却非写不可
 * ============================================================
 * 我第一版**直接把 `event.keyCode` 当 evdev 码用**，理由是"两者应该同一套编号"。
 * 结果是用户实测到的:
 *
 * > 很多按键**对应的不是原本按键**，有些按键还**反映不出来**
 *
 * ⚠️ 而这类错误的症状是"按 A 亮了别的键"—— 用户会去**键位映射**那边查，
 * 很难联想到"采集层把编号搞错了"。所以这里逐项钉住。
 *
 * 参考（只读）:Axon 的 `GamepadMappingStore.keyboardEvdevCode(int)`
 */
class AccessibilityKeyCodeTest {

    @Test
    fun `字母键的转换与 evdev 一致`() {
        /*
         * ⚠️ 这一组是**最容易错**的:Android 的字母码是**连续**的（29..54），
         * 而 evdev 是按键盘布局排的（Q=16、W=17、A=30、Z=44）。
         *
         * 直接透传的话，"按 A"=29 会被当成 evdev 的 **Ctrl 左**（也是 29）——
         * 看起来像"按键错位"，其实就是没转换。
         */
        assertEquals(30, AccessibilityKeyCode.toEvdev(KeyEvent.KEYCODE_A))
        assertEquals(31, AccessibilityKeyCode.toEvdev(KeyEvent.KEYCODE_S))
        assertEquals(32, AccessibilityKeyCode.toEvdev(KeyEvent.KEYCODE_D))
        assertEquals(33, AccessibilityKeyCode.toEvdev(KeyEvent.KEYCODE_F))
        assertEquals(16, AccessibilityKeyCode.toEvdev(KeyEvent.KEYCODE_Q))
        assertEquals(17, AccessibilityKeyCode.toEvdev(KeyEvent.KEYCODE_W))
        assertEquals(18, AccessibilityKeyCode.toEvdev(KeyEvent.KEYCODE_E))
        assertEquals(19, AccessibilityKeyCode.toEvdev(KeyEvent.KEYCODE_R))
        assertEquals(44, AccessibilityKeyCode.toEvdev(KeyEvent.KEYCODE_Z))
        assertEquals(45, AccessibilityKeyCode.toEvdev(KeyEvent.KEYCODE_X))
        assertEquals(46, AccessibilityKeyCode.toEvdev(KeyEvent.KEYCODE_C))
        assertEquals(47, AccessibilityKeyCode.toEvdev(KeyEvent.KEYCODE_V))
        assertEquals(48, AccessibilityKeyCode.toEvdev(KeyEvent.KEYCODE_B))
    }

    @Test
    fun `修饰键的转换`() {
        /*
         * ⚠️ Shift 是一个**典型陷阱**:Android 是 59，evdev 是 42。
         * 而 59 在 evdev 里是 F1 —— 透传的话"按左 Shift 会亮 F1"。
         */
        assertEquals(42, AccessibilityKeyCode.toEvdev(KeyEvent.KEYCODE_SHIFT_LEFT))
        assertEquals(54, AccessibilityKeyCode.toEvdev(KeyEvent.KEYCODE_SHIFT_RIGHT))
        assertEquals(29, AccessibilityKeyCode.toEvdev(KeyEvent.KEYCODE_CTRL_LEFT))
        assertEquals(97, AccessibilityKeyCode.toEvdev(KeyEvent.KEYCODE_CTRL_RIGHT))
        assertEquals(56, AccessibilityKeyCode.toEvdev(KeyEvent.KEYCODE_ALT_LEFT))
        assertEquals(100, AccessibilityKeyCode.toEvdev(KeyEvent.KEYCODE_ALT_RIGHT))
        assertEquals(125, AccessibilityKeyCode.toEvdev(KeyEvent.KEYCODE_META_LEFT))
        assertEquals(126, AccessibilityKeyCode.toEvdev(KeyEvent.KEYCODE_META_RIGHT))
        assertEquals(58, AccessibilityKeyCode.toEvdev(KeyEvent.KEYCODE_CAPS_LOCK))
    }

    @Test
    fun `常用功能键的转换`() {
        assertEquals(57, AccessibilityKeyCode.toEvdev(KeyEvent.KEYCODE_SPACE))
        assertEquals(28, AccessibilityKeyCode.toEvdev(KeyEvent.KEYCODE_ENTER))
        assertEquals(15, AccessibilityKeyCode.toEvdev(KeyEvent.KEYCODE_TAB))
        /* ⚠️ KEYCODE_DEL 是**退格**，对应 evdev 的 14（KEY_BACKSPACE） */
        assertEquals(14, AccessibilityKeyCode.toEvdev(KeyEvent.KEYCODE_DEL))
        assertEquals(1, AccessibilityKeyCode.toEvdev(KeyEvent.KEYCODE_ESCAPE))
    }

    @Test
    fun `F11 与 F12 不是连续的`() {
        /*
         * ⚠️ evdev 里 F1..F10 是 59..68，而 F11/F12 跳到 **87/88**
         * （69..86 那段是别的键）。照"顺手 +1"去推必然错。
         */
        assertEquals(59, AccessibilityKeyCode.toEvdev(KeyEvent.KEYCODE_F1))
        assertEquals(68, AccessibilityKeyCode.toEvdev(KeyEvent.KEYCODE_F10))
        assertEquals(87, AccessibilityKeyCode.toEvdev(KeyEvent.KEYCODE_F11))
        assertEquals(88, AccessibilityKeyCode.toEvdev(KeyEvent.KEYCODE_F12))
    }

    @Test
    fun `数字键与符号键`() {
        assertEquals(2, AccessibilityKeyCode.toEvdev(KeyEvent.KEYCODE_1))
        assertEquals(11, AccessibilityKeyCode.toEvdev(KeyEvent.KEYCODE_0))
        assertEquals(12, AccessibilityKeyCode.toEvdev(KeyEvent.KEYCODE_MINUS))
        assertEquals(13, AccessibilityKeyCode.toEvdev(KeyEvent.KEYCODE_EQUALS))
        assertEquals(26, AccessibilityKeyCode.toEvdev(KeyEvent.KEYCODE_LEFT_BRACKET))
        assertEquals(27, AccessibilityKeyCode.toEvdev(KeyEvent.KEYCODE_RIGHT_BRACKET))
        assertEquals(39, AccessibilityKeyCode.toEvdev(KeyEvent.KEYCODE_SEMICOLON))
        assertEquals(40, AccessibilityKeyCode.toEvdev(KeyEvent.KEYCODE_APOSTROPHE))
        assertEquals(41, AccessibilityKeyCode.toEvdev(KeyEvent.KEYCODE_GRAVE))
        assertEquals(43, AccessibilityKeyCode.toEvdev(KeyEvent.KEYCODE_BACKSLASH))
        assertEquals(51, AccessibilityKeyCode.toEvdev(KeyEvent.KEYCODE_COMMA))
        assertEquals(52, AccessibilityKeyCode.toEvdev(KeyEvent.KEYCODE_PERIOD))
        assertEquals(53, AccessibilityKeyCode.toEvdev(KeyEvent.KEYCODE_SLASH))
    }

    @Test
    fun `Android 键码与 evdev 码确实不同（这条是回归防线）`() {
        /*
         * ⚠️ 这条测试的用意是**记录一个事实**:两套编号不同。
         *
         * ⚠️ 如果哪天有人"优化"成 `toEvdev(k) = k`，上面那些测试会全红，
         * 而这一条会告诉他"你搞错了前提"。
         */
        assertNotEquals(
            KeyEvent.KEYCODE_A.toLong(),
            AccessibilityKeyCode.toEvdev(KeyEvent.KEYCODE_A).toLong(),
        )
    }

    @Test
    fun `认不出的键返回 UNKNOWN 而不是随便一个数`() {
        /*
         * ⚠️ 必须返回 -1 而不是"原样返回"或"返回 0":
         * · 原样返回 → 又变成"透传"，绕过了整张表；
         * · 返回 0   → evdev 的 0 是 KEY_RESERVED，一个不存在的键。
         */
        assertEquals(AccessibilityKeyCode.UNKNOWN, AccessibilityKeyCode.toEvdev(KeyEvent.KEYCODE_VOLUME_UP))
        assertEquals(AccessibilityKeyCode.UNKNOWN, AccessibilityKeyCode.toEvdev(KeyEvent.KEYCODE_HOME))
        assertEquals(AccessibilityKeyCode.UNKNOWN, AccessibilityKeyCode.toEvdev(KeyEvent.KEYCODE_BACK))
        assertEquals(AccessibilityKeyCode.UNKNOWN, AccessibilityKeyCode.toEvdev(KeyEvent.KEYCODE_MEDIA_PLAY))
    }

    @Test
    fun `isMappable 与 toEvdev 结论一致`() {
        assertTrue(AccessibilityKeyCode.isMappable(KeyEvent.KEYCODE_A))
        assertTrue(!AccessibilityKeyCode.isMappable(KeyEvent.KEYCODE_HOME))
    }

    @Test
    fun `转换后的码都在项目的按键表里`() {
        /*
         * ⚠️⚠️ 这条是**跨层的一致性检查**（价值最高的一条）
         * ============================================================
         * 转换表的输出必须是 [KeyCodes] 认识的 evdev 码 —— 否则:
         *
         * | 后果 | 用户看到 |
         * |---|---|
         * | 键位映射里选不到它 | "我按了键但设置里配不了" |
         * | 悬浮窗上显示成未知 | "亮了个怪东西" |
         *
         * ⚠️ 它同时防住"转换表写错方向"（比如把 evdev 码当 keyCode 传进去）。
         */
        val known = KeyCodes.ALL.map { it.keyCode }.toSet()
        val missing = buildList {
            for (k in ALL_MAPPABLE_KEYCODES) {
                val evdev = AccessibilityKeyCode.toEvdev(k)
                if (evdev != AccessibilityKeyCode.UNKNOWN && evdev !in known) {
                    add("KeyEvent $k → evdev $evdev")
                }
            }
        }
        assertEquals(
            "这些键转换后不在项目的按键表里（键位映射会选不到它们）:$missing",
            emptyList<String>(),
            missing,
        )
    }

    private companion object {
        /**
         * 表里覆盖到的全部 Android 键码。
         *
         * ⚠️ 手写一遍而不是"遍历所有 KeyEvent 常量"（Kotlin 反射拿不到常量），
         * 而**手写正好是好事**:新增映射时忘了加到这里，
         * `转换后的码都在项目的按键表里` 那条就少测一个新键 ——
         * 但那不会让它变红，所以这里留个提醒:
         * ⚠️ **给 `AccessibilityKeyCode` 加映射时，这里也要加。**
         */
        val ALL_MAPPABLE_KEYCODES = listOf(
            KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_3,
            KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_6,
            KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_9,
            KeyEvent.KEYCODE_0,
            KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_DEL,
            KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_E,
            KeyEvent.KEYCODE_R, KeyEvent.KEYCODE_T, KeyEvent.KEYCODE_Y,
            KeyEvent.KEYCODE_U, KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_O,
            KeyEvent.KEYCODE_P,
            KeyEvent.KEYCODE_LEFT_BRACKET, KeyEvent.KEYCODE_RIGHT_BRACKET,
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_CTRL_LEFT,
            KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_D,
            KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_G, KeyEvent.KEYCODE_H,
            KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_L,
            KeyEvent.KEYCODE_SEMICOLON, KeyEvent.KEYCODE_APOSTROPHE,
            KeyEvent.KEYCODE_GRAVE, KeyEvent.KEYCODE_SHIFT_LEFT,
            KeyEvent.KEYCODE_BACKSLASH,
            KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_C,
            KeyEvent.KEYCODE_V, KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_N,
            KeyEvent.KEYCODE_M,
            KeyEvent.KEYCODE_COMMA, KeyEvent.KEYCODE_PERIOD, KeyEvent.KEYCODE_SLASH,
            KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.KEYCODE_ALT_LEFT,
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_CAPS_LOCK,
            KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_F2, KeyEvent.KEYCODE_F3,
            KeyEvent.KEYCODE_F4, KeyEvent.KEYCODE_F5, KeyEvent.KEYCODE_F6,
            KeyEvent.KEYCODE_F7, KeyEvent.KEYCODE_F8, KeyEvent.KEYCODE_F9,
            KeyEvent.KEYCODE_F10, KeyEvent.KEYCODE_F11, KeyEvent.KEYCODE_F12,
            KeyEvent.KEYCODE_CTRL_RIGHT, KeyEvent.KEYCODE_ALT_RIGHT,
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT,
        )
    }
}

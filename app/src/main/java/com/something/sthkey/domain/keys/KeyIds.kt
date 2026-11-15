package com.something.sthkey.domain.keys

/**
 * 键位绑定的**跨平台明文 ID 注册表**（2026-11-15）。
 *
 * ============================================================
 * 为什么要有这一层
 * ============================================================
 * 早先 JSON 里存的是 **Linux evdev 键码**（LMB = 272、手柄 A = 304）。
 * 这对 Android 采集很方便，但:
 *
 * - 配置文件不可读 —— 手工改、diff、看备份都是数字；
 * - **Windows 版本没法直接用** —— Windows 没有 evdev 码；
 * - 手柄摇杆方向**根本没有键码**，只能另造伪码。
 *
 * 现在改成"**JSON 存明文 ID，运行时转 int**":
 *
 * ```
 * JSON:   "inputKeyCodes": ["KEY_A", "GAMEPAD_A", "JOYSTICK_LEFT_UP"]
 *             │  decode
 *             ▼
 * 内存:   [30, 304, 0x400]  ← 采集/渲染/CPS 全部按 evdev 码跑，零改动
 * ```
 *
 * Windows 端也照这个做:自己的 `ID -> VK / 扫描码` 表，读同一份配置。
 *
 * ============================================================
 * ⚠️ ID 一旦发布就不能改
 * ============================================================
 * 它与显示名**解耦**：UI 里把"手柄A"改成"A 键"不影响 ID。
 * 改 ID = 改配置格式，老配置会读不出来（读不出来时会被跳过并记日志）。
 *
 * ============================================================
 * ⚠️ 读取兼容老配置
 * ============================================================
 * 解码侧**数字和字符串都收**（见 `data/config/KeyCodeJson`）:
 * 老配置的数字照旧走 [KeyCodes.migrateLegacyCode]；
 * 新配置的 ID 走 [decode]。**写出去只会是 ID**。
 *
 * ============================================================
 * ⚠️ 未知键
 * ============================================================
 * 不在表里的 evdev 码（厂商自定义键）编码成 `EVDEV_<码>`——
 * 它只保证"能原样存取"，不保证 Windows 认识。
 */
object KeyIds {

    /**
     * evdev 码 → 明文 ID。
     *
     * ⚠️ 键的顺序就是阅读顺序（键盘 / 鼠标 / 手柄 / 摇杆），
     * 不影响语义。新增键时**只加新行**，不要改旧 ID。
     */
    private val byCode: Map<Int, String> = linkedMapOf(
        /* ---------------- 字母 ---------------- */
        30 to "KEY_A",
        48 to "KEY_B",
        46 to "KEY_C",
        32 to "KEY_D",
        18 to "KEY_E",
        33 to "KEY_F",
        34 to "KEY_G",
        35 to "KEY_H",
        23 to "KEY_I",
        36 to "KEY_J",
        37 to "KEY_K",
        38 to "KEY_L",
        50 to "KEY_M",
        49 to "KEY_N",
        24 to "KEY_O",
        25 to "KEY_P",
        16 to "KEY_Q",
        19 to "KEY_R",
        31 to "KEY_S",
        20 to "KEY_T",
        22 to "KEY_U",
        47 to "KEY_V",
        17 to "KEY_W",
        45 to "KEY_X",
        21 to "KEY_Y",
        44 to "KEY_Z",

        /* ---------------- 数字行 ---------------- */
        2 to "KEY_1",
        3 to "KEY_2",
        4 to "KEY_3",
        5 to "KEY_4",
        6 to "KEY_5",
        7 to "KEY_6",
        8 to "KEY_7",
        9 to "KEY_8",
        10 to "KEY_9",
        11 to "KEY_0",

        /* ---------------- 符号 ---------------- */
        12 to "KEY_MINUS",
        13 to "KEY_EQUAL",
        26 to "KEY_LEFT_BRACKET",
        27 to "KEY_RIGHT_BRACKET",
        39 to "KEY_SEMICOLON",
        40 to "KEY_APOSTROPHE",
        41 to "KEY_GRAVE",
        43 to "KEY_BACKSLASH",
        51 to "KEY_COMMA",
        52 to "KEY_DOT",
        53 to "KEY_SLASH",

        /* ---------------- 功能键 ---------------- */
        1 to "KEY_ESC",
        14 to "KEY_BACKSPACE",
        15 to "KEY_TAB",
        28 to "KEY_ENTER",
        57 to "KEY_SPACE",
        58 to "KEY_CAPS_LOCK",
        99 to "KEY_SYSRQ",
        70 to "KEY_SCROLL_LOCK",
        119 to "KEY_PAUSE",
        59 to "KEY_F1",
        60 to "KEY_F2",
        61 to "KEY_F3",
        62 to "KEY_F4",
        63 to "KEY_F5",
        64 to "KEY_F6",
        65 to "KEY_F7",
        66 to "KEY_F8",
        67 to "KEY_F9",
        68 to "KEY_F10",
        87 to "KEY_F11",
        88 to "KEY_F12",

        /* ---------------- 修饰键 ---------------- */
        29 to "KEY_CTRL_LEFT",
        97 to "KEY_CTRL_RIGHT",
        42 to "KEY_SHIFT_LEFT",
        54 to "KEY_SHIFT_RIGHT",
        56 to "KEY_ALT_LEFT",
        100 to "KEY_ALT_RIGHT",
        125 to "KEY_META_LEFT",
        126 to "KEY_META_RIGHT",

        /* ---------------- 方向与导航 ---------------- */
        103 to "KEY_UP",
        108 to "KEY_DOWN",
        105 to "KEY_LEFT",
        106 to "KEY_RIGHT",
        102 to "KEY_HOME",
        107 to "KEY_END",
        104 to "KEY_PAGE_UP",
        109 to "KEY_PAGE_DOWN",
        110 to "KEY_INSERT",
        111 to "KEY_DELETE",

        /* ---------------- 小键盘 ---------------- */
        69 to "KEY_NUM_LOCK",
        98 to "KEY_KP_DIVIDE",
        55 to "KEY_KP_MULTIPLY",
        74 to "KEY_KP_MINUS",
        78 to "KEY_KP_PLUS",
        96 to "KEY_KP_ENTER",
        82 to "KEY_KP_0",
        79 to "KEY_KP_1",
        80 to "KEY_KP_2",
        81 to "KEY_KP_3",
        75 to "KEY_KP_4",
        76 to "KEY_KP_5",
        77 to "KEY_KP_6",
        71 to "KEY_KP_7",
        72 to "KEY_KP_8",
        73 to "KEY_KP_9",
        83 to "KEY_KP_DOT",

        /* ---------------- 鼠标 ---------------- */
        KeyCodes.BTN_LEFT to "MOUSE_LMB",
        KeyCodes.BTN_RIGHT to "MOUSE_RMB",
        KeyCodes.BTN_MIDDLE to "MOUSE_MMB",
        KeyCodes.BTN_SIDE to "MOUSE_SIDE",
        KeyCodes.BTN_EXTRA to "MOUSE_EXTRA",

        /* ---------------- 手柄按键 ---------------- */
        KeyCodes.BTN_SOUTH to "GAMEPAD_A",
        KeyCodes.BTN_EAST to "GAMEPAD_B",
        KeyCodes.BTN_WEST to "GAMEPAD_X",
        KeyCodes.BTN_NORTH to "GAMEPAD_Y",
        KeyCodes.BTN_TL to "GAMEPAD_LB",
        KeyCodes.BTN_TR to "GAMEPAD_RB",
        KeyCodes.BTN_TL2 to "GAMEPAD_LT",
        KeyCodes.BTN_TR2 to "GAMEPAD_RT",
        KeyCodes.BTN_SELECT to "GAMEPAD_BACK",
        KeyCodes.BTN_START to "GAMEPAD_START",
        KeyCodes.BTN_MODE to "GAMEPAD_GUIDE",
        KeyCodes.BTN_THUMBL to "GAMEPAD_LS",
        KeyCodes.BTN_THUMBR to "GAMEPAD_RS",
        KeyCodes.BTN_DPAD_UP to "GAMEPAD_DPAD_UP",
        KeyCodes.BTN_DPAD_DOWN to "GAMEPAD_DPAD_DOWN",
        KeyCodes.BTN_DPAD_LEFT to "GAMEPAD_DPAD_LEFT",
        KeyCodes.BTN_DPAD_RIGHT to "GAMEPAD_DPAD_RIGHT",

        /* ---------------- 手柄摇杆八段式（仅内存码，JSON 永远只写 ID） ---------------- */
        KeyCodes.STICK_LEFT_UP to "JOYSTICK_LEFT_UP",
        KeyCodes.STICK_LEFT_DOWN to "JOYSTICK_LEFT_DOWN",
        KeyCodes.STICK_LEFT_LEFT to "JOYSTICK_LEFT_LEFT",
        KeyCodes.STICK_LEFT_RIGHT to "JOYSTICK_LEFT_RIGHT",
        KeyCodes.STICK_LEFT_UP_LEFT to "JOYSTICK_LEFT_UP_LEFT",
        KeyCodes.STICK_LEFT_UP_RIGHT to "JOYSTICK_LEFT_UP_RIGHT",
        KeyCodes.STICK_LEFT_DOWN_LEFT to "JOYSTICK_LEFT_DOWN_LEFT",
        KeyCodes.STICK_LEFT_DOWN_RIGHT to "JOYSTICK_LEFT_DOWN_RIGHT",
        KeyCodes.STICK_RIGHT_UP to "JOYSTICK_RIGHT_UP",
        KeyCodes.STICK_RIGHT_DOWN to "JOYSTICK_RIGHT_DOWN",
        KeyCodes.STICK_RIGHT_LEFT to "JOYSTICK_RIGHT_LEFT",
        KeyCodes.STICK_RIGHT_RIGHT to "JOYSTICK_RIGHT_RIGHT",
        KeyCodes.STICK_RIGHT_UP_LEFT to "JOYSTICK_RIGHT_UP_LEFT",
        KeyCodes.STICK_RIGHT_UP_RIGHT to "JOYSTICK_RIGHT_UP_RIGHT",
        KeyCodes.STICK_RIGHT_DOWN_LEFT to "JOYSTICK_RIGHT_DOWN_LEFT",
        KeyCodes.STICK_RIGHT_DOWN_RIGHT to "JOYSTICK_RIGHT_DOWN_RIGHT",
    )

    /** 明文 ID → evdev 码（含摇杆方向的内部码） */
    private val byId: Map<String, Int> = byCode.entries.associate { (code, id) -> id to code }

    /** 未知键的兜底格式：`EVDEV_514` */
    private val evdevPattern = Regex("""^EVDEV_(\d+)$""")

    /** evdev 码 → 明文 ID；未知码返回 `EVDEV_<码>`（老伪码先迁移） */
    fun encode(code: Int): String {
        val canonical = KeyCodes.migrateLegacyCode(code)
        return byCode[canonical] ?: "EVDEV_$code"
    }

    /**
     * 明文 ID → evdev 码。
     *
     * ⚠️ 只认**规范 ID** 与 `EVDEV_<码>`，不认显示名（`A` 这种分不清
     * 键盘还是手柄，宁可报不认识也不要猜）。
     */
    fun decode(id: String): Int? =
        byId[id] ?: evdevPattern.matchEntire(id)?.groupValues?.get(1)?.toIntOrNull()

    /** 全部规范 ID（不含 `EVDEV_` 动态值）；给测试与文档用 */
    fun canonicalIds(): Set<String> = byId.keys

    /** 这个字符串是不是规范 ID（供解码诊断用） */
    fun isCanonical(id: String): Boolean = id in byId
}

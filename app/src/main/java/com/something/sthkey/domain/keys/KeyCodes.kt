package com.something.sthkey.domain.keys

/**
 * 可用按键。
 *
 * [keyCode] 使用 **Linux evdev 键码**（也就是 `/dev/input/event` 上报的原始 code），
 * 不是 Android 的 `KeyEvent.KEYCODE_*`。
 *
 * 为什么必须区分（旧项目的实际教训）：
 * 采集层直接读设备节点，拿到的是 evdev 码（W = 17、A = 30）；
 * 而 Android KeyEvent 里 W = 51、A = 29。混用会导致"UI 里选了 A，
 * 按 A 却毫无反应"，而且日志里看不出问题。因此本项目**只使用 evdev 码**，
 * 所有按键相关代码一律引用本文件的 [KeyCodes]，不再各自维护一份表。
 *
 * 以后若需要把外接键盘事件接入 Android 输入框架，转换只在边界处做一次。
 */
data class AvailableKey(
    val keyCode: Int,
    val name: String,
    val aliases: List<String> = emptyList(),
) {
    /** 关键字/别名检索用：名称与别名统一小写 */
    val searchTokens: List<String>
        get() = buildList {
            add(name.lowercase())
            aliases.forEach { add(it.lowercase()) }
        }
}

/**
 * 按键分组，仅用于选择界面的分段展示。
 */
enum class KeyCategory(val label: String) {
    LETTER("字母"),
    DIGIT("数字"),
    SYMBOL("符号"),
    FUNCTION("功能键"),
    MODIFIER("修饰键"),
    NAVIGATION("方向与导航"),
    NUMPAD("小键盘"),
    MOUSE("鼠标"),

    /**
     * 手柄按键。
     *
     * ⚠️ 单独一类而不是并进 [MOUSE]：两者虽然 evdev 码相邻（都在 `0x13x`），
     * 但用户在界面上找的是"手柄"，混在鼠标里会让他以为不支持。
     */
    GAMEPAD("手柄"),
}

/**
 * 按键表的单一真源。
 *
 * 新增按键只需在 [ALL] 里加一行：配置页、键位选择器、悬浮窗标签都会自动跟上。
 */
object KeyCodes {

    /*
     * ============================================================
     * 鼠标按键（evdev BTN_* 段）
     * ============================================================
     */

    const val BTN_LEFT = 272
    const val BTN_RIGHT = 273
    const val BTN_MIDDLE = 274
    const val BTN_SIDE = 275
    const val BTN_EXTRA = 276

    /*
     * ============================================================
     * 手柄按键（evdev 码，与鼠标键同一段 0x13x）
     * ============================================================
     * ⚠️ 内核用的是**方位名**（SOUTH/EAST/NORTH/WEST），不是 A/B/X/Y ——
     * 因为不同厂商的按键布局不同。对照关系见下面 `gamepad` 那段的注释。
     *
     * ⚠️ 值必须与 Linux `input-event-codes.h` 一致，写错就是"某个键不亮"。
     */
    const val BTN_SOUTH = 0x130
    const val BTN_EAST = 0x131
    const val BTN_NORTH = 0x133
    const val BTN_WEST = 0x134
    const val BTN_TL = 0x136
    const val BTN_TR = 0x137
    const val BTN_TL2 = 0x138
    const val BTN_TR2 = 0x139
    const val BTN_SELECT = 0x13a
    const val BTN_START = 0x13b
    const val BTN_MODE = 0x13c
    const val BTN_THUMBL = 0x13d
    const val BTN_THUMBR = 0x13e

    /*
     * 方向键的**按键报法**（0x220 那一族）。
     *
     * ⚠️ 另一批手柄把方向键报成 `ABS_HAT0X` / `ABS_HAT0Y` 两个轴 ——
     * 两条路都要支持，见 `gamepad` 那段的说明。
     */
    const val BTN_DPAD_UP = 0x220
    const val BTN_DPAD_DOWN = 0x221
    const val BTN_DPAD_LEFT = 0x222
    const val BTN_DPAD_RIGHT = 0x223

    /**
     * ============================================================
     * 扳机的**伪键码** —— 不是 evdev 的码，是本应用自己造的
     * ============================================================
     * ⚠️ **扳机不是按键，是模拟轴** —— 这是本项目踩过的一个坑，
     * 用户的原话是:
     * **"配置里写的是LT和RT，但监听的是LB和RB，你逗我呢"**。
     *
     * evdev 的真实分工（本文件下面的键名表也印证了）:
     *
     * | 东西 | 报法 | 键码 |
     * |---|---|---|
     * | **LB** 左肩键 | **按键** | [BTN_TL] = `0x136` |
     * | **RB** 右肩键 | **按键** | [BTN_TR] = `0x137` |
     * | **LT** 左扳机 | **模拟轴** | `ABS_Z`（数字式手柄才用 [BTN_TL2]） |
     * | **RT** 右扳机 | **模拟轴** | `ABS_RZ`（数字式手柄才用 [BTN_TR2]） |
     *
     * native monitor 把扳机作为 `0..1000` 的**轴**送过来，
     * 而屏幕上的 LT/RT 是二进制的亮/不亮 —— 所以由采集层按阈值
     * 折成"按下 / 抬起"，并用下面这两个码表示（见
     * `CaptureController.setGamepadStick`）。
     *
     * ⚠️ 取值在 **`0x300` 以上**是有意的:evdev 的真实按键码最大到
     * `BTN_TRIGGER_HAPPY40`（`0x2c0 + 39 = 0x2e7`），留出余量就不会
     * 与任何真实键码撞车 —— 撞车的话"按 A 却亮了 LT"这种错极难查。
     *
     * ⚠️ 它们**只在本应用内部流转**（`pressedCodes` 里），
     * 不会写到 evdev，也不需要设备认识。
     */
    const val PSEUDO_KEY_TRIGGER_LEFT = 0x300
    const val PSEUDO_KEY_TRIGGER_RIGHT = 0x301

    /** 相对位移轴，键盘猫等需要鼠标移动的样式以后会用到 */
    const val REL_X = 0
    const val REL_Y = 1

    /**
     * 空格。
     *
     * ⚠️ 提成具名常量是为了让**手柄样式二**那个"长条键"引用它 ——
     * 那个样式绑的就是空格（它模仿的是键盘的空格键）。
     * 原来它在 [functionKeys] 里是字面量 `57`，从外面引不到。
     *
     * evdev: `KEY_SPACE = 57`。
     */
    const val KEY_SPACE = 57

    /*
     * ============================================================
     * WASD（"摇杆-键盘"组件要读它们）
     * ============================================================
     * ⚠️ 提成具名常量的理由与 [KEY_SPACE] 完全一样:组件那边要引用它们，
     * 而它们原来只是 [letters] 表里的字面量，从外面引不到。
     *
     * ⚠️ **不要在组件里直接写 `17` / `30`**:那个表以后可能被重整，
     * 而写死的字面量不会跟着变 —— 表现是"摇杆突然不跟 WASD 动了"。
     *
     * ⚠️ 这几个值与 [letters] 表**必须一致**，而那不是靠"记得同时改"保证的 ——
     * `KeyCodesWasdTest` 会逐个断言（表改了而这里没改就直接红）。
     *
     * evdev: `KEY_W = 17`、`KEY_A = 30`、`KEY_S = 31`、`KEY_D = 32`。
     */
    const val KEY_W = 17
    const val KEY_A = 30
    const val KEY_S = 31
    const val KEY_D = 32

    /*
     * ============================================================
     * 事件类型（Linux input event type）
     * ============================================================
     */

    const val EV_SYN = 0x00
    const val EV_KEY = 0x01
    const val EV_REL = 0x02
    const val EV_ABS = 0x03

    /** 按键 value：0 抬起 / 1 按下 / 2 重复 */
    const val VALUE_UP = 0
    const val VALUE_DOWN = 1
    const val VALUE_REPEAT = 2

    /** 字母键，按 A-Z 顺序 */
    private val letters = listOf(
        AvailableKey(30, "A"),
        AvailableKey(48, "B"),
        AvailableKey(46, "C"),
        AvailableKey(32, "D"),
        AvailableKey(18, "E"),
        AvailableKey(33, "F"),
        AvailableKey(34, "G"),
        AvailableKey(35, "H"),
        AvailableKey(23, "I"),
        AvailableKey(36, "J"),
        AvailableKey(37, "K"),
        AvailableKey(38, "L"),
        AvailableKey(50, "M"),
        AvailableKey(49, "N"),
        AvailableKey(24, "O"),
        AvailableKey(25, "P"),
        AvailableKey(16, "Q"),
        AvailableKey(19, "R"),
        AvailableKey(31, "S"),
        AvailableKey(20, "T"),
        AvailableKey(22, "U"),
        AvailableKey(47, "V"),
        AvailableKey(17, "W"),
        AvailableKey(45, "X"),
        AvailableKey(21, "Y"),
        AvailableKey(44, "Z"),
    )

    /** 主键盘数字行 */
    private val digits = listOf(
        AvailableKey(2, "1"),
        AvailableKey(3, "2"),
        AvailableKey(4, "3"),
        AvailableKey(5, "4"),
        AvailableKey(6, "5"),
        AvailableKey(7, "6"),
        AvailableKey(8, "7"),
        AvailableKey(9, "8"),
        AvailableKey(10, "9"),
        AvailableKey(11, "0"),
    )

    private val symbols = listOf(
        AvailableKey(12, "-", listOf("MINUS")),
        AvailableKey(13, "=", listOf("EQUAL")),
        AvailableKey(26, "[", listOf("LEFT_BRACKET")),
        AvailableKey(27, "]", listOf("RIGHT_BRACKET")),
        AvailableKey(39, ";", listOf("SEMICOLON")),
        AvailableKey(40, "'", listOf("APOSTROPHE")),
        AvailableKey(41, "`", listOf("GRAVE")),
        AvailableKey(43, "\\", listOf("BACKSLASH")),
        AvailableKey(51, ",", listOf("COMMA")),
        AvailableKey(52, ".", listOf("DOT", "PERIOD")),
        AvailableKey(53, "/", listOf("SLASH")),
    )

    private val functionKeys = buildList {
        add(AvailableKey(1, "ESC", listOf("ESCAPE")))
        add(AvailableKey(14, "BACKSPACE"))
        add(AvailableKey(15, "TAB"))
        add(AvailableKey(28, "ENTER", listOf("RETURN")))
        add(AvailableKey(KEY_SPACE, "SPACE"))
        add(AvailableKey(58, "CAPS_LOCK", listOf("CAPSLOCK")))
        add(AvailableKey(99, "SYSRQ", listOf("PRINT_SCREEN")))
        add(AvailableKey(70, "SCROLL_LOCK"))
        add(AvailableKey(119, "PAUSE"))
        for (i in 0 until 12) {
            // F1 = 59 ... F10 = 68，F11 = 87，F12 = 88
            val code = if (i < 10) 59 + i else 87 + (i - 10)
            add(AvailableKey(code, "F${i + 1}"))
        }
    }

    private val modifiers = listOf(
        AvailableKey(29, "CTRL_LEFT", listOf("LEFT_CTRL", "LCTRL")),
        AvailableKey(97, "CTRL_RIGHT", listOf("RIGHT_CTRL", "RCTRL")),
        AvailableKey(42, "SHIFT_LEFT", listOf("LEFT_SHIFT", "LSHIFT")),
        AvailableKey(54, "SHIFT_RIGHT", listOf("RIGHT_SHIFT", "RSHIFT")),
        AvailableKey(56, "ALT_LEFT", listOf("LEFT_ALT", "LALT")),
        AvailableKey(100, "ALT_RIGHT", listOf("RIGHT_ALT", "RALT", "ALTGR")),
        AvailableKey(125, "META_LEFT", listOf("LEFT_META", "LEFT_WIN", "SUPER")),
        AvailableKey(126, "META_RIGHT", listOf("RIGHT_META", "RIGHT_WIN")),
    )

    private val navigation = listOf(
        AvailableKey(103, "UP", listOf("UP_ARROW")),
        AvailableKey(108, "DOWN", listOf("DOWN_ARROW")),
        AvailableKey(105, "LEFT", listOf("LEFT_ARROW")),
        AvailableKey(106, "RIGHT", listOf("RIGHT_ARROW")),
        AvailableKey(102, "HOME"),
        AvailableKey(107, "END"),
        AvailableKey(104, "PAGE_UP", listOf("PGUP")),
        AvailableKey(109, "PAGE_DOWN", listOf("PGDN")),
        AvailableKey(110, "INSERT", listOf("INS")),
        AvailableKey(111, "DELETE", listOf("DEL")),
    )

    private val numpad = listOf(
        AvailableKey(69, "NUM_LOCK"),
        AvailableKey(98, "KP_DIVIDE"),
        AvailableKey(55, "KP_MULTIPLY"),
        AvailableKey(74, "KP_MINUS"),
        AvailableKey(78, "KP_PLUS"),
        AvailableKey(96, "KP_ENTER"),
        AvailableKey(82, "KP_0"),
        AvailableKey(79, "KP_1"),
        AvailableKey(80, "KP_2"),
        AvailableKey(81, "KP_3"),
        AvailableKey(75, "KP_4"),
        AvailableKey(76, "KP_5"),
        AvailableKey(77, "KP_6"),
        AvailableKey(71, "KP_7"),
        AvailableKey(72, "KP_8"),
        AvailableKey(73, "KP_9"),
        AvailableKey(83, "KP_DOT"),
    )

    private val mouse = listOf(
        AvailableKey(BTN_LEFT, "LMB", listOf("LEFT_MOUSE", "鼠标左键")),
        AvailableKey(BTN_RIGHT, "RMB", listOf("RIGHT_MOUSE", "鼠标右键")),
        AvailableKey(BTN_MIDDLE, "MMB", listOf("MIDDLE_MOUSE", "鼠标中键")),
        AvailableKey(BTN_SIDE, "MOUSE_SIDE", listOf("SIDE_MOUSE", "侧键")),
        AvailableKey(BTN_EXTRA, "MOUSE_EXTRA", listOf("EXTRA_MOUSE", "扩展键")),
    )

    /**
     * 手柄按键。
     *
     * ============================================================
     * ⚠️ 名字用 **Xbox 的叫法**（A/B/X/Y），不是 evdev 的方位名
     * ============================================================
     * evdev 里它们是 `BTN_SOUTH` / `BTN_EAST` / `BTN_NORTH` / `BTN_WEST`
     * —— 那是**方位**（下/右/上/左），因为不同厂商的布局不同。
     *
     * 但中文用户看到的、说的都是 A/B/X/Y。所以:
     *
     * - **常量名**保留 evdev 的方位名（`BTN_SOUTH`），便于与内核对照；
     * - **显示名**用 A/B/X/Y，因为那是用户在界面上要找的东西。
     *
     * ⚠️ 对照关系（Xbox 布局）:
     * ```
     *        Y (NORTH)
     *  X (WEST)   B (EAST)
     *        A (SOUTH)
     * ```
     */
    private val gamepad = listOf(
        AvailableKey(BTN_SOUTH, "A", listOf("手柄A", "PAD_A")),
        AvailableKey(BTN_EAST, "B", listOf("手柄B", "PAD_B")),
        AvailableKey(BTN_WEST, "X", listOf("手柄X", "PAD_X")),
        AvailableKey(BTN_NORTH, "Y", listOf("手柄Y", "PAD_Y")),
        AvailableKey(BTN_TL, "LB", listOf("手柄LB", "L1", "左肩键")),
        AvailableKey(BTN_TR, "RB", listOf("手柄RB", "R1", "右肩键")),
        AvailableKey(BTN_TL2, "LT", listOf("手柄LT", "L2", "左扳机")),
        AvailableKey(BTN_TR2, "RT", listOf("手柄RT", "R2", "右扳机")),
        AvailableKey(BTN_SELECT, "BACK", listOf("手柄BACK", "SELECT", "视图键")),
        AvailableKey(BTN_START, "START", listOf("手柄START", "菜单键")),
        AvailableKey(BTN_MODE, "GUIDE", listOf("手柄GUIDE", "西瓜键", "HOME")),
        AvailableKey(BTN_THUMBL, "LS", listOf("左摇杆按下", "L3", "THUMBL")),
        AvailableKey(BTN_THUMBR, "RS", listOf("右摇杆按下", "R3", "THUMBR")),
        /*
         * 方向键的**按键报法**。
         *
         * ⚠️ 手柄的方向键有**两种报法**（见 `docs/input-capture.md`）:
         * 1. 独立按键码（下面这四个）；
         * 2. 两个轴 `ABS_HAT0X` / `ABS_HAT0Y`（实测的 Xbox 360 就是这种）。
         *
         * 两个都要有 —— 只认一种就会出现"方向键在某个手柄上没反应"。
         * 轴报法由 `StickState.hat*` 承载，不在这张表里（它不是按键码）。
         */
        AvailableKey(BTN_DPAD_UP, "DPAD_UP", listOf("方向键上", "十字键上")),
        AvailableKey(BTN_DPAD_DOWN, "DPAD_DOWN", listOf("方向键下", "十字键下")),
        AvailableKey(BTN_DPAD_LEFT, "DPAD_LEFT", listOf("方向键左", "十字键左")),
        AvailableKey(BTN_DPAD_RIGHT, "DPAD_RIGHT", listOf("方向键右", "十字键右")),
    )

    /** 按分类组织的完整按键表 */
    val grouped: Map<KeyCategory, List<AvailableKey>> = linkedMapOf(
        KeyCategory.LETTER to letters,
        KeyCategory.DIGIT to digits,
        KeyCategory.SYMBOL to symbols,
        KeyCategory.FUNCTION to functionKeys,
        KeyCategory.MODIFIER to modifiers,
        KeyCategory.NAVIGATION to navigation,
        KeyCategory.NUMPAD to numpad,
        KeyCategory.MOUSE to mouse,
        KeyCategory.GAMEPAD to gamepad,
    )

    val ALL: List<AvailableKey> = grouped.values.flatten()

    private val byCode: Map<Int, AvailableKey> = ALL.associateBy { it.keyCode }

    /**
     * 按键码 → 可读名称。
     *
     * 查不到时返回 `KEY_<code>`：这类按键仍然可以被映射，
     * 只是没有内置名字（例如某些键盘的厂商自定义键）。
     */
    fun displayName(keyCode: Int): String =
        byCode[keyCode]?.name ?: "KEY_$keyCode"

    fun find(keyCode: Int): AvailableKey? = byCode[keyCode]

    /**
     * 按键检索，供键位选择器过滤。
     *
     * 匹配规则（**故意用前缀匹配，不用任意子串**）：
     * - 名称前缀：`L` → L、LMB、LEFT、LEFT_CTRL…；`SP` → SPACE
     * - 别名前缀：`左` → 鼠标左键（别名）等
     * - 按键码精确匹配：`272` → LMB
     *
     * 为什么不用 `contains`：一个字母会命中任何位置含该字母的键
     * （搜 `L` 会翻出 `EQUAL`、`LEFT_BRACKET` 之类一堆无关项），
     * 选择器立刻变得没法用。
     */
    fun search(query: String): List<AvailableKey> {
        val keyword = query.trim().lowercase()
        if (keyword.isEmpty()) return ALL

        val asCode = keyword.toIntOrNull()
        return ALL.filter { key ->
            (asCode != null && key.keyCode == asCode) ||
                key.name.lowercase().startsWith(keyword) ||
                key.aliases.any { it.lowercase().startsWith(keyword) }
        }
    }

    /**
     * 按键选择器里的**大类**。
     *
     * ============================================================
     * ⚠️ 为什么要在搜索之前先选大类
     * ============================================================
     * 用户的原话:
     *
     * > "应该在'添加一个按键'点击后先弹一个选择键位分类的窗口，
     * >  选择键位在键盘分类还是手柄分类，否则可能会搞混，
     * >  现在键盘手柄都挤在同一个栏里，有些用户没有经验，
     * >  想调 A 这类按键就会同时搜索出键盘的 A 和手柄的 A"
     *
     * 这确实是个真问题:键盘的 `A`（`KEY_A` = 30）与手柄的 `A`
     * （`BTN_SOUTH` = 304）搜出来**名字都叫 A**，只有 code 不同。
     * 没有经验的人会随便点一个，然后"按了没反应"。
     *
     * ⚠️ 保留 [ALL] 这一项:高级用户知道自己要找什么，
     * 强制两步会让改键变得很烦。
     */
    enum class PickerGroup(val label: String, val description: String) {
        KEYBOARD("键盘", "字母、数字、符号、功能键、鼠标"),
        GAMEPAD("手柄", "摇杆方向、面键、扳机、肩键、摇杆按下"),
        ALL("全部", "不分类，显示所有按键（高级用法）"),
    }

    /** 这个按键属于哪个大类 */
    fun groupOf(keyCode: Int): PickerGroup = when {
        /*
         * ⚠️ 用**键码范围**判断，而不是查表 ——
         * 手柄按键在 evdev 里是连成一片的:
         *
         * ```
         * 0x130..0x13e  BTN_SOUTH … BTN_THUMBR（面键 / 扳机 / 摇杆按下）
         * 0x220..0x223  BTN_DPAD_*（方向键）
         * 0x2c0..0x2e7  BTN_TRIGGER_HAPPY*（背键）
         * 0x300+        扳机伪键码（本项目自造，见 PSEUDO_KEY_TRIGGER_*）
         * ```
         */
        keyCode in 0x130..0x13e -> PickerGroup.GAMEPAD
        keyCode in 0x220..0x223 -> PickerGroup.GAMEPAD
        keyCode in 0x2c0..0x2e7 -> PickerGroup.GAMEPAD
        keyCode >= 0x300 -> PickerGroup.GAMEPAD
        else -> PickerGroup.KEYBOARD
    }

    /**
     * 按大类过滤 + 检索。
     *
     * ⚠️ 与 [search] 的匹配规则**完全一致**（前缀匹配、支持键码数字）——
     * 两份实现分叉的话，用户会看到"在全部里能搜到、在手柄里搜不到"。
     */
    fun searchIn(query: String, group: PickerGroup): List<AvailableKey> {
        val base = search(query)
        if (group == PickerGroup.ALL) return base
        return base.filter { groupOf(it.keyCode) == group }
    }

    /** 是否为鼠标按键（影响 CPS 统计等后续特性） */
    fun isMouseButton(keyCode: Int): Boolean = keyCode in BTN_LEFT..BTN_EXTRA
}

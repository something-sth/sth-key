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

    /** 相对位移轴，键盘猫等需要鼠标移动的样式以后会用到 */
    const val REL_X = 0
    const val REL_Y = 1

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
        add(AvailableKey(57, "SPACE"))
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

    /** 是否为鼠标按键（影响 CPS 统计等后续特性） */
    fun isMouseButton(keyCode: Int): Boolean = keyCode in BTN_LEFT..BTN_EXTRA
}

package com.something.sthkey.domain.live2d

/**
 * evdev 键码 → BongoCat 语义名。
 *
 * ============================================================
 * 为什么这张表在 domain 层，而不是塞在 View 里
 * ============================================================
 * 它是**领域知识**：Linux 输入键码与 BongoCat 资源命名之间的对应关系。
 * 放在这里的直接好处是它不依赖任何 Android API，将来要给别的渲染器复用、
 * 或者做"按这个键会点亮哪个键帽"的界面提示时，都不用从 View 里挖出来。
 *
 * ============================================================
 * ⚠️ 这张表是**照抄**资源命名，不要凭感觉增删
 * ============================================================
 * 名字必须与 `assets/bongocat/<模型>/resources/left-keys/<键名>.png` 完全一致。
 * 写错了不会有任何报错 —— JS 那边会去加载 `left-keys/KeyA.png` 这样的路径，
 * 图片取不到时键帽就是不显示，表现为"按了这个键没反应"，很难查。
 *
 * 反过来，多写几个名字是安全的：JS 的 `keySide()` 不认识就直接 return。
 */
object Live2DSemantics {

    /** 语义名属于猫的哪一侧 */
    enum class Side { LEFT, RIGHT }

    /**
     * 方向键是**唯一的右侧键**。
     *
     * 依据是 JS 里的资源分组：键盘模型 `RIGHT_KEYS = {Up/Down/Left/RightArrow}`，
     * 而鼠标模型（standard）的 `RIGHT_KEYS` 是**空集** —— 它没有右侧键帽资源，
     * 方向键在那个模型下会被 JS 直接忽略。
     * 导入的模型走的是键盘版 runtime，所以有右侧。
     */
    private val ARROWS = setOf("UpArrow", "DownArrow", "LeftArrow", "RightArrow")

    /** 键码 → 语义名；不认识的键返回 null（它不参与 BongoCat 的画面） */
    fun of(code: Int): String? = TABLE[code]

    /**
     * 运行时可能向皮肤包请求的全部键帽名，按左右侧分组。
     *
     * 用途：导入皮肤包时**补齐缺失的键帽文件**（见 Live2DModelImporter）。
     * 运行时找不到 `left-keys/<名字>.png` 时会回退到**内置资源**，
     * 而内置键帽画在内置布局的位置上 —— 皮肤包缺哪个键，那个键就会以
     * "错位的键帽"形式出现。所以必须知道"运行时到底会问哪些名字"。
     */
    fun keycapNames(side: Side): Set<String> =
        TABLE.values.filterTo(mutableSetOf()) { side(it, hasArrowSide = true) == side }

    /**
     * 该语义属于哪一侧；`null` 表示"这个模型里它没有对应资源"。
     *
     * 只有一个用途：某个键松开时，若同侧还有别的键按着，
     * 需要把最近按下的那个重新发一次（见 [com.something.sthkey.ui.overlay.live2d.Live2DOverlayView]），
     * 否则 JS 会把整侧清空，猫的手会莫名其妙放下来。
     *
     * @param hasArrowSide 当前模型有没有右侧键帽。鼠标版模型（standard）没有，
     *   那个页面下方向键会被 JS 直接忽略，所以不必为它做"同侧恢复"。
     */
    fun side(name: String, hasArrowSide: Boolean): Side? = when {
        name in ARROWS -> if (hasArrowSide) Side.RIGHT else null
        else -> Side.LEFT
    }

    /*
     * 键码常量写在 domain/keys/KeyCodes.kt 里，但那张是"可映射按键"的清单；
     * 这里是输入设备层面的原始键码，直接用数字并分组注释，与 JS 资源命名一一对应。
     */
    private val TABLE: Map<Int, String> = mapOf(
        // ---- 字母（evdev 16..50，注意中间夹着别的键，不是连续的）----
        16 to "KeyQ", 17 to "KeyW", 18 to "KeyE", 19 to "KeyR", 20 to "KeyT",
        21 to "KeyY", 22 to "KeyU", 23 to "KeyI", 24 to "KeyO", 25 to "KeyP",
        30 to "KeyA", 31 to "KeyS", 32 to "KeyD", 33 to "KeyF", 34 to "KeyG",
        35 to "KeyH", 36 to "KeyJ", 37 to "KeyK", 38 to "KeyL",
        44 to "KeyZ", 45 to "KeyX", 46 to "KeyC", 47 to "KeyV", 48 to "KeyB",
        49 to "KeyN", 50 to "KeyM",

        // ---- 数字（1..0 对应 evdev 2..11）----
        2 to "Num1", 3 to "Num2", 4 to "Num3", 5 to "Num4", 6 to "Num5",
        7 to "Num6", 8 to "Num7", 9 to "Num8", 10 to "Num9", 11 to "Num0",

        // ---- 功能键与符号 ----
        1 to "Escape",
        14 to "Backspace",
        15 to "Tab",
        28 to "Return",
        29 to "ControlLeft",
        97 to "ControlRight",
        42 to "ShiftLeft",
        54 to "ShiftRight",
        56 to "Alt",
        100 to "AltGr",
        57 to "Space",
        58 to "CapsLock",
        41 to "BackQuote",
        53 to "Slash",
        111 to "Delete",

        /*
         * 左右 Meta 都叫 Meta —— 两个键码映射到同一个语义。
         *
         * 这就是"必须按语义计数而不是按布尔"的原因：同时按住左右 Meta，
         * 松开其中一个时不能把这一侧清掉（用计数就不会）。
         */
        125 to "Meta", 126 to "Meta",

        // ---- 方向键（唯一的右侧键）----
        103 to "UpArrow", 105 to "LeftArrow", 106 to "RightArrow", 108 to "DownArrow",

        // ---- F1..F12 共用一个 Fn 键帽 ----
        59 to "Fn", 60 to "Fn", 61 to "Fn", 62 to "Fn", 63 to "Fn", 64 to "Fn",
        65 to "Fn", 66 to "Fn", 67 to "Fn", 68 to "Fn", 87 to "Fn", 88 to "Fn",

        /*
         * evdev 55（KEY_KPASTERISK）在旧项目里映射为 Fn。
         *
         * 标准键盘上它是小键盘的 `*`，但不少 60% / 笔记本键盘把 Fn 报成这个键码。
         * 这里照抄旧项目（人家在真机上验过），不要"按规范纠正"成小键盘星号 ——
         * 那样这些键盘的 Fn 就彻底没反应了。
         */
        55 to "Fn",
    )
}

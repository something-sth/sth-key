package com.something.sthkey.data.live2d

import java.io.File
import org.json.JSONObject

/**
 * Mver 包 `config.json` 的纯解析（不碰 Android，可 JVM 单测）。
 *
 * ============================================================
 * 为什么单独抽出来
 * ============================================================
 * 这些逻辑决定"按键盘有没有反应"：键码 → 键名、模式段选择、手图目录判断。
 * 放在导入器里（依赖 `Context`）就没法单测，只能靠真机试错 ——
 * 抽出来之后可以直接拿样本目录跑 JUnit。
 */
internal object Live2DMverConfig {

    /**
     * Windows 虚拟键码 → runtime 的键名。
     *
     * ⚠️ 必须与 `runtime.js` 的 `LEFT_KEYS` / `RIGHT_KEYS` 用同一套写法
     * （`KeyA` / `Num1` / `Space` …），否则按键与手图/动作永远匹配不上。
     */
    fun vkKeyName(vk: Int): String? {
        if (vk in 0x30..0x39) return "Num" + (vk - 0x30)
        if (vk in 0x41..0x5A) return "Key" + ('A' + (vk - 0x41))
        if (vk in 0x60..0x69) return "Numpad" + (vk - 0x60)
        if (vk in 0x70..0x7B) return "F" + (vk - 0x70 + 1)
        return when (vk) {
            0x01 -> "MouseLeft"
            0x02 -> "MouseRight"
            0x04 -> "MouseMiddle"
            0x08 -> "Backspace"
            0x09 -> "Tab"
            0x0D -> "Return"
            0x10 -> "Shift"
            0x11 -> "Control"
            0x12 -> "Alt"
            0x13 -> "CapsLock"
            0x1B -> "Escape"
            0x20 -> "Space"
            0xA0 -> "ShiftLeft"
            0xA1 -> "ShiftRight"
            0xA2 -> "ControlLeft"
            0xA3 -> "ControlRight"
            0x25 -> "LeftArrow"
            0x26 -> "UpArrow"
            0x27 -> "RightArrow"
            0x28 -> "DownArrow"
            0xBB -> "Equal"
            0xBC -> "Comma"
            0xBD -> "Minus"
            0xBE -> "Period"
            0xBF -> "Slash"
            0xC0 -> "BackQuote"
            0xDB -> "BracketLeft"
            0xDC -> "Backslash"
            0xDD -> "BracketRight"
            0xDE -> "Quote"
            else -> null
        }
    }

    /**
     * Mver 手柄按钮编号 → runtime 键名。
     *
     * 编号是 Mver 自己的枚举（不是 evdev BTN_*）：
     * `0 A(South) / 1 B(East) / 2 X(West) / 3 Y(North) /
     * 4 LB / 5 RB / 6 LT / 7 RT / 8 L3 / 9 R3 /
     * 10 DpadLeft / 11 DpadRight / 12 DpadUp / 13 DpadDown / 14 Start / 15 Back`
     *
     * ⚠️ 这张表**照 Axon 的 `mverGamepadName` 核对过**：之前我把 10/11 当成
     * Back/Start、12~15 当成 Dpad，结果就是"方向键左右没反应"（它们被映射成了
     * 不存在的按钮名），而上下碰巧对了。
     */
    fun gamepadButtonName(id: Int): String? = when (id) {
        0 -> "GamepadA"
        1 -> "GamepadB"
        2 -> "GamepadX"
        3 -> "GamepadY"
        4 -> "GamepadLB"
        5 -> "GamepadRB"
        6 -> "GamepadLT"
        7 -> "GamepadRT"
        8 -> "GamepadL3"
        9 -> "GamepadR3"
        10 -> "GamepadDpadLeft"
        11 -> "GamepadDpadRight"
        12 -> "GamepadDpadUp"
        13 -> "GamepadDpadDown"
        14 -> "GamepadStart"
        15 -> "GamepadBack"
        else -> null
    }

    /**
     * Mver 的设计画布 = `decoration.window_size`。
     *
     * ⚠️ 这是**所有 full-frame 贴图（背景/键盘/手/表情）的坐标原点**：
     * 它们都是"窗口尺寸大小的整幅图，从 (0,0) 画起"，不是按模型画布铺的。
     * 卡提 window=1400×1400、贴图 1400²；星野 standard window=1600×1000、贴图 1600×1000；
     * 超天酱 window=612×352、贴图 612×354。按模型画布去拉手图必然错位。
     */
    fun loadDesign(packageRoot: File): JSONObject? {
        val config = loadConfig(packageRoot) ?: return null
        val decoration = config.optJSONObject("decoration") ?: return null
        val size = decoration.optJSONArray("window_size") ?: return null
        if (size.length() < 2) return null
        val width = size.optDouble(0, 0.0)
        val height = size.optDouble(1, 0.0)
        if (!width.isFinite() || !height.isFinite() || width < 1 || height < 1) return null
        return JSONObject().apply {
            put("width", width)
            put("height", height)
            put("handOffsetX", decoration.optJSONArray("hand_offset")?.optDouble(0, 0.0) ?: 0.0)
            put("handOffsetY", decoration.optJSONArray("hand_offset")?.optDouble(1, 0.0) ?: 0.0)
        }
    }

    /**
     * Mver 的模型摆放参数（`decoration.l2d_correct` / `l2d_offset`）。
     *
     * Mver 会先把模型缩放 `l2d_correct` 倍，再按 `l2d_offset`（相对窗口的比例）平移。
     * 不应用这些值，人物就会"飘"在桌子上面（星野 standard 的典型表现）。
     */
    fun loadLayout(packageRoot: File): JSONObject? {
        val config = loadConfig(packageRoot) ?: return null
        val decoration = config.optJSONObject("decoration") ?: return null
        val offset = decoration.optJSONArray("l2d_offset")
        val correct = decoration.optDouble("l2d_correct", 1.0)
        if (!correct.isFinite() && offset == null) return null
        return JSONObject().apply {
            put("correct", if (correct.isFinite() && correct > 0.01) correct else 1.0)
            put("offsetX", offset?.optDouble(0, 0.0) ?: 0.0)
            put("offsetY", offset?.optDouble(1, 0.0) ?: 0.0)
        }
    }

    /**
     * 桌面/键盘底图。
     *
     * Mver 的"键盘猫"通常**不是模型的一部分**：桌面和键盘是一张背景图
     * （standard 用 `mousebg.png` / `tabletbg.png`，其它模式用 `bg.png`），
     * 人物模型叠在上面。我们之前不画它，所以只剩上半身、下面空着。
     */
    fun loadBackground(packageRoot: File, mode: String?, variantRoot: File?): String? {
        if (variantRoot == null) return null
        val section = mode?.let { loadConfig(packageRoot)?.optJSONObject(it) }
        val candidates = if (mode == "standard") {
            val mouse = section?.optBoolean("mouse", true) ?: true
            listOf(if (mouse) "mousebg" else "tabletbg", "bg", "background")
        } else {
            listOf("bg", "background")
        }
        for (base in candidates) {
            for (extension in listOf("png", "webp", "jpg", "jpeg")) {
                if (File(variantRoot, "$base.$extension").isFile) return "$base.$extension"
            }
        }
        return null
    }

    /**
     * 表情贴图（Mver 的 `face/`）。
     *
     * 和手部同一套规则：配置里的 `face` 是"按键组合 → 贴图下标"，
     * 图是**设计画布尺寸的整幅图**，从 (0,0) 以原始像素绘制。
     * 例如 `[[17,97],[17,98],[17,99],[17,100]]` = Ctrl + 小键盘 1~4。
     */
    fun loadFaceSprites(packageRoot: File, mode: String?, variantRoot: File?): JSONObject? {
        if (mode == null || variantRoot == null) return null
        if (!File(variantRoot, "face").isDirectory) return null
        val json = loadConfig(packageRoot) ?: return null
        val section = json.optJSONObject(mode) ?: return null
        val combos = section.optJSONArray("face") ?: return null
        if (combos.length() == 0) return null

        val bindings = org.json.JSONArray()
        for (index in 0 until combos.length()) {
            val combo = combos.optJSONArray(index) ?: continue
            val keys = org.json.JSONArray()
            for (k in 0 until combo.length()) {
                vkKeyName(combo.optInt(k, -1))?.let { keys.put(it) }
            }
            if (keys.length() == 0) continue
            bindings.put(
                JSONObject().apply {
                    put("keys", keys)
                    put("index", index)
                },
            )
        }
        if (bindings.length() == 0) return null
        return JSONObject().apply {
            put("dir", "face")
            put("bindings", bindings)
        }
    }

    /**
     * 手柄摇杆贴图（`stick_offset_L/R` + `left_stick.png` / `right_stick.png`）。
     *
     * 配置格式：`stick_offset_L: [x, y, 半径]`（设计画布像素）。
     * 我们没有摇杆底座的独立图层，Mver 这两张图就是摇杆本体，按下 L3/R3 换 `*_down.png`。
     */
    fun loadSticks(packageRoot: File, mode: String?, variantRoot: File?): JSONObject? {
        if (mode != "gamepad" || variantRoot == null) return null
        val json = loadConfig(packageRoot) ?: return null
        val section = json.optJSONObject("gamepad") ?: return null

        fun one(field: String, imageName: String, downKey: String): JSONObject? {
            val offset = section.optJSONArray(field) ?: return null
            if (offset.length() < 2) return null
            val base = File(variantRoot, "$imageName.png")
            if (!base.isFile) return null
            val down = File(variantRoot, "${imageName}_down.png")
            return JSONObject().apply {
                put("base", base.name)
                if (down.isFile) put("down", down.name)
                put("x", offset.optDouble(0, 0.0))
                put("y", offset.optDouble(1, 0.0))
                put("radius", offset.optDouble(2, 0.0))
                put("downKey", downKey)
            }
        }

        val left = one("stick_offset_L", "left_stick", "GamepadL3")
        val right = one("stick_offset_R", "right_stick", "GamepadR3")
        if (left == null && right == null) return null
        return JSONObject().apply {
            left?.let { put("left", it) }
            right?.let { put("right", it) }
        }
    }

    /** 读包里的 `config.json`（允许 `//` 注释；没有就返回 null）。
     *
     * ⚠️ **不能只在包根找**：BongoCat 发行包常常是
     * `<zip根>/A-卡提/config.json`（多套一层目录），而导入器的 `dir` 是 zip 根。
     * 只在根找的结果是"配置永远读不到 → 手图/动作绑定全空 → 按键没反应"。
     * 所以先看根，再浅扫 3 层。
     */
    fun loadConfig(packageRoot: File): JSONObject? {
        val names = listOf("config.json", "config.json5", "config.jsonc")
        val file = names.map { File(packageRoot, it) }.firstOrNull { it.isFile }
            ?: packageRoot.walkTopDown()
                .maxDepth(3)
                .firstOrNull { it.isFile && it.name.lowercase() in names }
            ?: return null
        return runCatching { JSONObject(stripJsonComments(file.readText())) }.getOrNull()
    }

    /**
     * 手部贴图：`lefthand` / `righthand` 的"键组合 → 图号"。
     *
     * @param variantRoot 变体根目录（`img/keyboard` 这种）；目录不存在就不写绑定，
     *   否则会"收起模型手臂、手图却 404"，手直接消失。
     */
    fun loadHandSprites(
        packageRoot: File,
        mode: String?,
        variantRoot: File?,
    ): JSONObject? {
        val json = loadConfig(packageRoot) ?: return null
        val section = json.optJSONObject(mode ?: "keyboard")
            ?: json.optJSONObject("keyboard")
            ?: return null

        fun side(field: String, dir: String): JSONObject? {
            if (variantRoot != null && !File(variantRoot, dir).isDirectory) return null
            val combos = section.optJSONArray(field) ?: return null
            if (combos.length() == 0) return null

            val bindings = org.json.JSONArray()
            for (index in 0 until combos.length()) {
                val combo = combos.optJSONArray(index) ?: continue
                val keys = org.json.JSONArray()
                for (k in 0 until combo.length()) {
                    val raw = combo.optInt(k, -1)
                    /*
                     * ⚠️ **手柄段不是 VK 码**：`lefthand`/`righthand` 里是 Mver 自己的按钮编号。
                     * 拿 vkKeyName 去解会把 6/10/12 之类全丢掉（真机表现：left:2/right:2）。
                     */
                    val name = if (mode == "gamepad") gamepadButtonName(raw) else vkKeyName(raw)
                    name?.let { keys.put(it) }
                }
                if (keys.length() == 0) continue
                bindings.put(
                    JSONObject().apply {
                        put("keys", keys)
                        put("index", index)
                    },
                )
            }
            return if (bindings.length() == 0) {
                null
            } else {
                JSONObject().apply {
                    put("dir", dir)
                    put("bindings", bindings)
                }
            }
        }

        val left = side("lefthand", "lefthand")
        val right = side("righthand", "righthand")
        if (left != null || right != null) {
            return JSONObject().apply {
                left?.let { put("left", it) }
                right?.let { put("right", it) }
            }
        }

        /*
         * standard（键鼠版）用的是**单层** `hand/`：一张 1400×1400 的手图里
         * 同时画着按压的那只手，编号由 `hand` 数组的下标决定。
         */
        val single = side("hand", "hand") ?: return null
        return JSONObject().apply { put("hand", single) }
    }

    /** 去掉 `//` 与 `/* */` 注释（字符串里的不动） */
    private fun stripJsonComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        var inString = false
        while (i < text.length) {
            val c = text[i]
            when {
                inString -> {
                    out.append(c)
                    if (c == '\\' && i + 1 < text.length) {
                        out.append(text[i + 1])
                        i += 2
                        continue
                    }
                    if (c == '"') inString = false
                    i++
                }

                c == '"' -> {
                    inString = true
                    out.append(c)
                    i++
                }

                c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
                    while (i < text.length && text[i] != '\n') i++
                }

                c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
                    i = (i + 2).coerceAtMost(text.length)
                }

                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }
}

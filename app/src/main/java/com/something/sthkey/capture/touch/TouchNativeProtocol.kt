package com.something.sthkey.capture.touch

import com.something.sthkey.capture.gamepad.GamepadNativeProtocol

/**
 * 原生触屏 monitor 的**文本协议**。
 *
 * ============================================================
 * 协议长什么样（与 `touchmonitor.cpp` 的文件头逐条对应）
 * ============================================================
 * ```
 * STATUS starting
 * STATUS touch-ready /dev/input/eventX | <设备名> | MT+SLOT+DIRECT
 * STATUS waiting-touch
 * STATUS touch-disconnected
 * STATUS no-touchscreen-candidate
 * STATUS input-permission-denied
 * STATUS no-input-devices
 * STATUS stopped
 * TOUCH <数量> <id> <x> <y> <id> <x> <y> ...
 * ```
 *
 * | 字段 | 范围 | 说明 |
 * |---|---|---|
 * | 数量 | `0 .. 20` | 当前同时按下的触点数；`0` = 手指都抬起来了 |
 * | `id` | `>= 0` | 触点标识 |
 * | `x` `y` | `0 .. 100000` | 归一化屏幕坐标，左上角 `(0,0)` |
 *
 * ============================================================
 * ⚠️ 这个类**只做解析**，不要在这里加业务逻辑
 * ============================================================
 * 与 [GamepadNativeProtocol] 同一个约定:
 *
 * - 命中测试（哪个触点落在哪个区域）→ 调用方（[TouchRegions]）；
 * - 屏幕旋转换算 → 调用方（原生拿不到可靠的旋转信息）；
 * - 摇杆向量 / 触摸板增量 → 调用方（那是"手感"的事，随时会调）。
 *
 * ⚠️ 在这里做任何一件，都会让 native 与 Kotlin 的职责边界糊掉，
 * 而"两边各算一半"是最难查的一类 bug（手柄的中点校准就吃过这个亏）。
 *
 * ============================================================
 * ⚠️ 坐标是"屏幕自然方向"的，旋转由调用方换算
 * ============================================================
 * evdev 报的是**面板自然方向**（绝大多数手机是竖屏）。用户横屏玩游戏时，
 * 归一化坐标必须转 90°/180°/270° 才能和"用户看到的屏幕"对应上。
 *
 * ⚠️ 放在 Kotlin 侧是因为:Java 里 `Display.getRotation()` 一行就有，
 * 而原生要拿到同一份信息得绕一大圈（且各 ROM 表现不一）。
 */
object TouchNativeProtocol {

    /** 协议前缀 —— 与 native 里的 `printf` 逐字对应 */
    const val PREFIX_STATUS = "STATUS"
    const val PREFIX_TOUCH = "TOUCH"

    /** 原生那条 `printf("TOUCH %d …")` 里的坐标刻度 */
    const val NATIVE_COORD_SCALE = 100_000f

    /**
     * 一次最多认多少触点。
     *
     * ⚠️ 与 native 的 `kMaxSlots` 一致。超过就**整行丢弃**而不是截断 ——
     * 截断的话会"凭空少一根手指"，而少的那根可能正按着摇杆，
     * 表现是"摇杆偶尔松手"。丢弃整行至少是"这一帧没数据"，下一帧就恢复。
     */
    const val MAX_POINTS = 20

    /** 一个触点（坐标已归一化到 `0f .. 1f`） */
    data class Point(
        val id: Int,
        val x: Float,
        val y: Float,
    )

    /** 一帧的完整状态 */
    data class Frame(val points: List<Point>) {
        companion object {
            val EMPTY = Frame(emptyList())
        }
    }

    /** 这一行是不是状态行 */
    fun isStatus(line: String?): Boolean =
        line?.startsWith("$PREFIX_STATUS ") == true

    /** 触屏就绪（后面跟设备描述，形如 `/dev/input/event1 | 名字 | MT+SLOT+DIRECT`） */
    fun isReady(line: String?): Boolean =
        line?.startsWith("$PREFIX_STATUS touch-ready ") == true

    /**
     * 「**还在找触屏**」—— 启动后的常态。
     *
     * ⚠️ 与 [isDisconnected] **分开**（手柄那边合成一个吃过亏）:
     *
     * | 状态 | 什么时候出现 | 该不该记日志 |
     * |---|---|---|
     * | [isWaiting] | 还没认出触屏 —— 常态，helper 会反复上报 | **不该**（刷屏） |
     * | [isDisconnected] | 认出过、又断了 | 该记 |
     *
     * ⚠️ 触屏比手柄更极端:手柄是"用户插没插"，而**触屏一定是插着的**
     * （它就是屏幕本身）。所以 `waiting-touch` 出现只说明**设备识别失败**，
     * 而不是"用户没操作"—— 这一点在提示文案上要区分开。
     */
    fun isWaiting(line: String?): Boolean =
        line?.startsWith("$PREFIX_STATUS waiting-touch") == true

    /** 触屏真的断开了（认出过又断） */
    fun isDisconnected(line: String?): Boolean =
        line?.startsWith("$PREFIX_STATUS touch-disconnected") == true

    /** 就绪行里的设备描述（日志与调试页用） */
    fun readyDetail(line: String): String =
        line.removePrefix("$PREFIX_STATUS touch-ready ").trim()

    /**
     * 从状态行里取出**给人看的原因**；认不出来返回 `null`。
     *
     * ⚠️ 用一个映射表而不是 `when` 里的字面量:文案要与 native 的
     * `printf` 逐字对齐，散在多处迟早漂。加新状态时**只改这里**，
     * 而没登记的会走"原样显示"的兜底（见 [unknownStatus]）。
     */
    private val STATUS_TEXT: Map<String, String> = mapOf(
        "starting" to "正在扫描触屏设备",
        "waiting-touch" to "等待触屏设备",
        "touch-disconnected" to "触屏设备断开，正在重新扫描",
        "no-touchscreen-candidate" to "未识别到触屏设备",
        "input-permission-denied" to "无权读取 /dev/input",
        "no-input-devices" to "未发现 /dev/input/event 设备",
        "stopped" to "已停止",
    )

    /**
     * 状态行 → 中文说明。
     *
     * ⚠️ 认不出来的状态**原样显示**（拼上"触屏监听："前缀），
     * 而不是丢掉:以后 native 加了新状态、而这个表还没跟上时，
     * 用户至少能看到原始英文，比一片空白强。
     */
    fun statusText(line: String): String {
        val raw = line.removePrefix("$PREFIX_STATUS ").trim()
        /* `touch-ready …` 的详细描述要单独处理（它带设备名） */
        if (raw.startsWith("touch-ready")) {
            val detail = raw.removePrefix("touch-ready").trim()
            return if (detail.isEmpty()) "触屏已连接" else "触屏已连接 · $detail"
        }
        /*
         * `helper-copy-failed <原因>` —— 这条不是 native 打的，
         * 而是部署脚本里 `test -s` 那道防线的输出。
         */
        if (raw.startsWith("helper-copy-failed")) {
            val detail = raw.removePrefix("helper-copy-failed").trim()
            return "复制 helper 失败${if (detail.isEmpty()) "" else " · $detail"}"
        }
        return STATUS_TEXT[raw] ?: "触屏监听：$raw"
    }

    /**
     * 解析 `TOUCH …` 行。
     *
     * ============================================================
     * ⚠️ 字段数**必须严格**等于 `2 + 3 × 数量`
     * ============================================================
     * 与 [GamepadNativeProtocol.parseState] 同一个理由，但这里更危险:
     *
     * | 宽松解析的后果 | 说明 |
     * |---|---|
     * | 把残缺行当成有效帧 | 触点坐标**两两错位** —— 表现是"手指位置全乱" |
     * | 触点数量对不上 | 摇杆按着按着突然松手（那一根手指"消失"了） |
     *
     * ⚠️ 所以任何一处不对就**返回 null**（整行丢弃），让调用方保持上一帧。
     * 保持上一帧的表现是"卡住一帧"，而错位帧的表现是"乱跳"——
     * 前者顶多卡顿，后者会让用户以为功能坏了。
     *
     * @return 解析失败返回 `null`
     */
    fun parseFrame(line: String?): Frame? {
        if (line.isNullOrBlank()) return null
        /*
         * ⚠️ 前缀判据必须**要求后面的分隔**，不能只 `startsWith`。
         *
         * 只判 `startsWith("TOUCH")` 的话 `TOUCHX 1 0 100 100` 也会通过 ——
         * 而它其实是**另一个命令**（以后 native 加 `TOUCHCAL` 之类的行时
         * 就会撞上），后果是"把别的行当成触点帧"。
         *
         * ⚠️ 这个缺陷是单测抓出来的（我原本写的用例名与内容不符，
         * 但把真问题暴露了）。
         */
        if (line != PREFIX_TOUCH && !line.startsWith("$PREFIX_TOUCH ")) return null
        val parts = line.trim().split(' ').filter { it.isNotEmpty() }
        /* 至少要 "TOUCH 0" 两段 */
        if (parts.size < 2) return null

        val count = parts[1].toIntOrNull() ?: return null
        if (count < 0 || count > MAX_POINTS) return null
        if (parts.size != 2 + count * 3) return null

        val points = ArrayList<Point>(count)
        for (i in 0 until count) {
            val base = 2 + i * 3
            val id = parts[base].toIntOrNull() ?: return null
            val nx = parts[base + 1].toIntOrNull() ?: return null
            val ny = parts[base + 2].toIntOrNull() ?: return null
            if (id < 0) return null
            points += Point(
                id = id,
                /* ⚠️ 夹到 0..1:归一化算出来理论上就在这个范围里，但设备
                 * 报出越界值时不该让下游拿到越界坐标（那会让命中测试与
                 * 摇杆偏移一起跑偏）。 */
                x = (nx / NATIVE_COORD_SCALE).coerceIn(0f, 1f),
                y = (ny / NATIVE_COORD_SCALE).coerceIn(0f, 1f),
            )
        }
        return Frame(points)
    }
}

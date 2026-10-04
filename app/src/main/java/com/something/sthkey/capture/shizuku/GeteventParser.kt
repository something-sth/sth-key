package com.something.sthkey.capture.shizuku

/**
 * `getevent` 输出的解析（**纯函数，可单元测试**）。
 *
 * [ShizukuGeteventInputSource] 与 [com.something.sthkey.capture.RootInputSource]
 * 都只负责"读流、按行丢进来"，解析全在这里。
 *
 * ============================================================
 * 支持两种输出格式
 * ============================================================
 * **已有格式**（不带 `-l`）：
 * ```
 * 0001 0011 00000001
 * /dev/input/event3: 0001 0011 00000001
 * ```
 *
 * **现在用的格式**（`-t`，时间戳 + 设备前缀）：
 * ```
 * add device 5: /dev/input/event3
 *   name:     "Xbox Wireless Controller"
 * [   12345.678901] /dev/input/event3: 0001 0011 00000001
 * remove device 5: /dev/input/event3
 * ```
 *
 * ============================================================
 * ⚠️ **不支持 `getevent -l`**
 * ============================================================
 * `-l` 会把事件行从十六进制换成符号名：
 *
 * ```
 * 不用 -l：  0001 0011 00000001
 * 用了 -l：  EV_KEY KEY_W DOWN
 * ```
 *
 * 后者**解析不了**（要支持就得带一张几百项的 `KEY_*` 名称表），
 * 而它的失败方式很隐蔽：**每一个真实按键事件都被丢掉，但设备公告
 * 不受影响** —— 表现成"热插拔正常、设备数会更新，但悬浮窗毫无反应"。
 * 一半对一半错，很容易以为是别的地方坏了。（这个坑踩过一次。）
 *
 * 所以 `getevent` 的命令里**只用 `-t`**。设备路径来自事件行的
 * `/dev/input/eventN:` 前缀，与 `-l` 无关，不需要它。
 *
 * `GeteventParserTest` 里有一条测试把这个约定钉住了 ——
 * 谁要是把 `-l` 加回去，那条测试会失败。
 *
 * ============================================================
 * ⚠️ 为什么用"有没有时间戳"判定事件行
 * ============================================================
 * `-lt` 输出里除了事件行，还有**设备描述块**：
 *
 * ```
 * add device 5: /dev/input/event3
 *   name:     "Xbox Wireless Controller"
 *   events:   KEY (0001): ...
 *   input props: ...
 * ```
 *
 * 描述块里那些行如果被拿去解析，`name:` 之后的词会被当成十六进制字段试试看 ——
 * 大部分会被拒绝（"name" 不是合法十六进制），但**"events" / "input" 这些
 * 也全都不是**，所以看起来安全。可是 `events:   KEY (0001): 0001 0002 …`
 * 这一行**末尾恰好是一串十六进制**，宽松的"取最后三个字段"策略会把它
 * 解析成一个**假的输入事件** —— 于是采集里会凭空多出几个按键。
 *
 * 所以判据不能是"字段数够不够"，必须是"**这一行是不是以时间戳开头**"：
 * 只有真正的事件行才有 `[ 12345.678901]` 前缀，描述块没有。
 * 这是 `getevent -l` 的格式约定，比数空格可靠得多。
 */
internal object GeteventParser {

    /** `[  12345.678901]` 形式的时间戳前缀 */
    private val TIMESTAMP = Regex("""^\[\s*(\d+)\.(\d+)]""")

    /** `/dev/input/event3` */
    private val DEVICE = Regex("""/dev/input/event\d+""")

    /**
     * 判断一行是不是**描述块**。
     *
     * ============================================================
     * 判据：冒号后紧跟的第一个词**不是合法十六进制**
     * ============================================================
     * 两类行的形状对比：
     *
     * | 行 | 冒号后第一个词 |
     * |---|---|
     * | `/dev/input/event3: 0001 0011 00000001`（事件） | `0001` → **合法十六进制** |
     * | `  name:     "Xbox …"`（描述） | `"Xbox` → 不是 |
     * | `  events:   KEY (0001): …`（描述） | `KEY` → 不是 |
     * | `  input props:  00000000`（描述） | `00000000` → **合法十六进制！** |
     *
     * ⚠️ 最后一行说明**不能只看第一个词** ——
     * `input props:` 后面那串也是十六进制。所以还要做一件事：
     * 描述块的行首是**缩进的**，而事件行不是。
     *
     * 两条一起用就稳了：
     * - 有缩进 → 描述块（事件行从不缩进）
     * - 冒号后第一个词不是十六进制 → 描述块
     *
     * 为什么不用"冒号后跟空白"：事件行的 `/dev/input/event3: 0001`
     * 也是冒号后跟空白，那样会把**所有带设备前缀的事件行**都丢掉。
     * （这个错误被测试抓到过一次。）
     */
    private fun isDescriptorLine(line: String): Boolean {
        /* 描述块的行首一定缩进；事件行与公告行都不缩进 */
        if (line.firstOrNull()?.isWhitespace() == true) return true

        /*
         * 没有冒号就不是描述块（裸事件行 `0001 0011 00000001` 走这里）。
         */
        val colon = line.indexOf(':')
        if (colon < 0) return false

        val afterColon = line.substring(colon + 1).trim().substringBefore(' ')
        if (afterColon.isEmpty()) return false

        /*
         * 冒号后第一个词是合法十六进制 → 那是事件的第一个字段
         * （可能是设备前缀后面的 `0001`）。
         */
        return afterColon.toLongOrNull(16) == null
    }

    /**
     * 解析一行。
     *
     * @return 事件 / 设备接入 / 设备拔出；这一行不是有效内容时返回 null
     */
    fun parse(line: String): ParsedLine? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null

        /*
         * 设备公告要**先判**：它们的行首不是时间戳，但也不能掉进
         * "描述块"的忽略分支里 —— 那是热插拔的唯一来源。
         */
        parseDeviceAnnouncement(trimmed)?.let { return it }

        val timestamp = TIMESTAMP.find(trimmed)

        /*
         * 没有时间戳时，靠 [isDescriptorLine] 排除描述块。
         *
         * 有描述块特征的行一律不是事件 —— 其中 `events:` 那行末尾恰好是
         * 一串十六进制，不排除的话会被解析成一个**假的输入事件**。
         */
        if (timestamp == null && isDescriptorLine(trimmed)) return null

        /*
         * 取时间戳**之后**的部分再解析字段。
         *
         * 不这么做的话，`12345` 与 `678901` 会被当成前两个十六进制字段 ——
         * 恰好 `678901` 是合法十六进制，于是会解析出一个**错误的 type/code**。
         * 这是个很隐蔽的坑：事件能解析出来，但类型与码全错。
         */
        val rest = if (timestamp != null) {
            trimmed.substring(timestamp.range.last + 1).trim()
        } else {
            trimmed
        }

        return parseEvent(
            text = rest,
            timestampMicros = timestamp?.let { timestampMicros(it) } ?: 0L,
        )
    }

    /**
     * 解析事件本体：`[/dev/input/event3:] tttt cccc vvvvvvvv`
     *
     * @param text 时间戳之后的部分（可能带设备前缀）
     */
    private fun parseEvent(text: String, timestampMicros: Long): ParsedLine.Event? {
        val device = DEVICE.find(text)?.value

        /*
         * 去掉设备前缀：它形如 `/dev/input/event3:`。
         *
         * 按**冒号**切而不是按空格：设备路径里不含空格，
         * 而 `getevent` 在路径后面紧跟一个冒号。
         */
        val body = if (device != null) text.substringAfter(':').trim() else text

        /*
         * 按空白拆成字段。
         *
         * 用 `split` 而不是"取最后三个"的宽松策略：宽松策略会把
         * 描述块里恰好以十六进制结尾的行也吃进来（见文件头的说明）。
         * 严格三段反而更安全 —— 事件行的格式是固定的。
         */
        val parts = body.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (parts.size < 3) return null

        val type = parseKernelInt(parts[0]) ?: return null
        val code = parseKernelInt(parts[1]) ?: return null
        val value = parseKernelInt(parts[2]) ?: return null

        return ParsedLine.Event(
            device = device,
            type = type,
            code = code,
            value = value,
            timestampMicros = timestampMicros,
        )
    }

    /**
     * 设备公告：`add device 5: /dev/input/event3` / `remove device 5: …`
     *
     * ⚠️ 只用**行首的动作词**判断，不强求一定能取到设备路径。
     *
     * 不同 ROM / toybox 版本的 `getevent` 这里打印的内容不完全一样
     * （有的带完整路径，有的只带事件节点序号）。取不到路径时返回一个
     * **空路径的公告**，让调用方走"清掉全部按键状态"这条保守路径 ——
     * 那比"什么都不做、按键永久卡住"好得多。
     */
    private fun parseDeviceAnnouncement(line: String): ParsedLine? {
        val attached = when {
            line.startsWith("add device") -> true
            line.startsWith("remove device") -> false
            else -> return null
        }

        val device = DEVICE.find(line)?.value.orEmpty()
        return if (attached) ParsedLine.Attached(device) else ParsedLine.Detached(device)
    }

    /** `[  12345.678901]` → 微秒 */
    private fun timestampMicros(match: MatchResult): Long {
        val seconds = match.groupValues[1].toLongOrNull() ?: return 0L
        /*
         * 小数部分要**右补零到 6 位**再当微秒：`5.5` 是半秒（500000µs），
         * 直接拼成 `55` 会差三个数量级。
         */
        val fraction = match.groupValues[2].padEnd(6, '0').take(6).toLongOrNull() ?: 0L
        return seconds * 1_000_000L + fraction
    }

    /**
     * 解析一个内核打印的十六进制字段。
     *
     * ============================================================
     * ⚠️ 不能直接用 `toInt(16)`
     * ============================================================
     * `getevent` 把 `struct input_event` 的字段按 **int** 打印，
     * 所以负数会显示成 `ffffffff`。`"ffffffff".toInt(16)` 会抛
     * `NumberFormatException`（超出 Int 正数范围），
     * 于是**鼠标往左/往上移动的事件会被整条丢掉** ——
     * 表现为"鼠标只能往右下动"，很容易被误判成硬件问题。
     *
     * 正确做法：按 Long 读（容忍 8 位十六进制），再截成 32 位有符号。
     */
    private fun parseKernelInt(token: String): Int? {
        val text = token.trim()
        if (text.isEmpty()) return null
        return text.toLongOrNull(16)?.toInt()
    }
}

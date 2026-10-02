package com.something.sthkey.capture.shizuku

import com.something.sthkey.capture.InputEvent

/**
 * `getevent -q` 输出的解析（**纯函数，可单元测试**）。
 *
 * ============================================================
 * 为什么要单独抽出来
 * ============================================================
 * 这条通道拿到的是一串**文本**，而不是 root 通道那种二进制 `input_event`。
 * 解析错了不会崩，只会"某些键不亮" —— 正是最难查的那类问题。
 *
 * 而解析逻辑本身不碰 Android、不碰 IO，所以没理由不把它做成纯函数钉住。
 * [ShizukuGeteventInputSource] 只负责"读流、按行丢进来"。
 *
 * ============================================================
 * getevent 的输出格式（两种都要支持）
 * ============================================================
 * `-q` 是 quiet，去掉时间戳。**注意它会同时去掉设备名**：
 *
 * ```
 * 单设备：0001 0011 00000001
 * 多设备：/dev/input/event3: 0001 0011 00000001
 * ```
 *
 * 我们为了"一个进程监听全部设备"会一次传多个节点，所以**必须两种都能解析**。
 * 另外字段值可能是负数（`getevent` 打印的是有符号十进制），例如：
 *
 * ```
 * 0002 0000 ffffffff
 * ```
 *
 * 这个值是 **-1**（鼠标往左移一格）。用 `toInt(16)` 去解析会抛异常 ——
 * 老老实实走"按 16 位无符号读、再按 32 位补回符号"这条路。
 */
internal object GeteventParser {

    /** 解析结果：三字段有效，或这一行不是事件（设备切换头、空行、报错文本） */
    fun parse(line: String): InputEvent? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null

        /*
         * 多设备时前缀形如 `/dev/input/event3:` —— 以冒号结尾。
         * 用 lastIndexOf(' ') 而不是 `startsWith("/dev/")`：
         * 万一输出格式变成带别的前缀（换 ROM、换 toybox 版本），
         * 只要"最后三个字段是 type code value"就还能解析。
         */
        val body = trimmed.substringAfterLast(' ')
        val afterType = trimmed.substringBeforeLast(' ', missingDelimiterValue = "")
        if (afterType.isEmpty()) return null
        val codeToken = afterType.substringAfterLast(' ')
        val typeToken = afterType.substringBeforeLast(' ', missingDelimiterValue = "")
        if (typeToken.isEmpty()) return null

        // 设备前缀挂在 type 字段上：`/dev/input/event3: 0001`
        val cleanType = typeToken.substringAfterLast(':').trim()

        /*
         * ⚠️ 设备前缀是"以冒号结尾的一整段" —— 它会被 substringAfterLast(':')
         * 丢掉。但如果**没有**设备前缀，cleanType 就是 type 本身。
         * 两种情况都安全，因为设备路径里除了结尾那个冒号不含别的冒号。
         */
        val type = parseKernelInt(cleanType) ?: return null
        val code = parseKernelInt(codeToken) ?: return null
        val value = parseKernelInt(body) ?: return null

        return InputEvent(type, code, value)
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

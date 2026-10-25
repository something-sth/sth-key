package com.something.sthkey.domain.capture

/**
 * 采集模式。
 *
 * 区别只在"用什么身份读输入"，与采集到的内容无关：
 * - [AUTO]          交给应用决定：root → Shizuku → 无障碍，逐级降级
 * - [ROOT]          强制走 root，失败就明确报错，不做回退（便于定位问题）
 * - [SHIZUKU]       强制走 shizuku
 * - [ACCESSIBILITY] 强制走无障碍（不需要任何特权），但只能读键盘
 *
 * 这个枚举是**用户意图**；实际生效的模式由 [CaptureModeResolver] 解析后得出，
 * 两者分开的原因：自动模式下实际结果可能随环境变化，UI 上要能分别展示。
 *
 * ============================================================
 * ⚠️⚠️ 无障碍为什么**并入** [AUTO]
 * ============================================================
 * 用户的原话:
 *
 * > 无障碍**并列进去**会比较方便，虽然能做的确实不同，但是"auto"的能力
 * > 就是这样子的
 *
 * ⚠️ 他说得对:[AUTO] 的字面语义就是"你能给什么权限，我就用最好的那个"。
 * 把无障碍排除在外，反而会让开了无障碍的用户困惑
 * （"我明明开了无障碍，AUTO 怎么不用？"）。
 *
 * ⚠️ 而"能力不同"这件事**由界面负责说清**（主页状态卡 + 引导页声明），
 * 不该靠把无障碍踢出 [AUTO] 来回避 —— 那只是把困惑挪到别处。
 */
enum class KeyMode(val id: String, val label: String, val description: String) {
    AUTO(
        id = "auto",
        label = "自动",
        description = "按 root → Shizuku → 无障碍 逐级尝试。" +
            "⚠️ 落到无障碍时只能读键盘，鼠标、手柄、触摸读不到",
    ),
    ROOT(
        id = "root",
        label = "Root",
        description = "强制使用 root 读取输入设备，失败时直接报错，不做回退",
    ),
    SHIZUKU(
        id = "shizuku",
        label = "Shizuku",
        description = "强制使用 Shizuku（shell 身份）读取输入设备",
    ),
    ACCESSIBILITY(
        id = "accessibility",
        label = "无障碍",
        description = "不需要 root 或电脑，但只能读物理键盘；" +
            "鼠标、手柄摇杆与触摸都读不到",
    ),
    ;

    /**
     * 这个模式是**只有键盘**吗。
     *
     * ⚠️ 给界面用:无障碍模式要在主页状态卡上明确写出"读不到鼠标/手柄/触摸"，
     * 而其它模式能读全。**不要**在界面里各处写 `mode == ACCESSIBILITY` ——
     * 以后若再加一条受限通道，改这里一处就够。
     */
    val keyboardOnly: Boolean get() = this == ACCESSIBILITY

    companion object {
        val DEFAULT: KeyMode = AUTO

        /** 从持久化的 id 还原，未知或缺失一律回落到 [DEFAULT] */
        fun fromId(id: String?): KeyMode =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

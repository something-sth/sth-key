package com.something.sthkey.domain.capture

/**
 * 采集模式。
 *
 * 三种模式的区别只在"用什么身份读输入设备"，与采集到的内容无关：
 * - [AUTO]     交给应用决定：优先 root，root 不可用或启动失败时回退 shizuku
 * - [ROOT]     强制走 root，失败就明确报错，不做回退（便于定位问题）
 * - [SHIZUKU]  强制走 shizuku
 *
 * 这个枚举是**用户意图**；实际生效的模式由 [CaptureModeResolver] 解析后得出，
 * 两者分开的原因：自动模式下实际结果可能随环境变化，UI 上要能分别展示。
 */
enum class KeyMode(val id: String, val label: String, val description: String) {
    AUTO(
        id = "auto",
        label = "自动",
        description = "优先使用 root；root 不可用或读取失败时自动回退到 Shizuku",
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
    ;

    companion object {
        val DEFAULT: KeyMode = AUTO

        /** 从持久化的 id 还原，未知或缺失一律回落到 [DEFAULT] */
        fun fromId(id: String?): KeyMode =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

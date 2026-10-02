package com.something.sthkey.core.log

/**
 * 日志级别。
 *
 * 顺序即严重程度，筛选时按 [ordinal] 比较。
 */
enum class LogLevel(val label: String) {
    DEBUG("D"),
    INFO("I"),
    WARN("W"),
    ERROR("E"),
}

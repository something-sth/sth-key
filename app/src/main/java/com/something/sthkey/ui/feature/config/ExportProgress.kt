package com.something.sthkey.ui.feature.config

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 导出/导入的**全局进度文案**。
 *
 * ============================================================
 * ⚠️ 为什么要做成全局单例，而不是某个页面的局部状态
 * ============================================================
 * 导出/导入有**很多入口**:配置列表、配置组合详情、编辑页……
 * 第一版把进度对话框挂在 `ConfigListScreen` 里，结果用户从别的入口导出时
 * **什么提示都没有**（他实测"啥弹窗都没有"）。
 *
 * 现在状态放在这里，对话框由**应用根**统一渲染一次 —— 不管从哪条入口进来，
 * 提示都在。
 *
 * ⚠️ 只能在主线程写（调用方用 Handler 切过去），它只是给 Compose 读的。
 */
object ExportProgress {

    /** `null` = 没在忙 */
    var text: String? by mutableStateOf(null)
        private set

    /**
     * 阶段序号:每上报一次 +1。
     *
     * ⚠️ 进度条要按它跳一小步 —— 按 `text` 做 key 的话，
     * 连续两次上报同一句文案（例如多次"正在导入媒体"）不会触发跳动。
     */
    var tick: Int by mutableStateOf(0)
        private set

    fun update(value: String) {
        text = value
        tick++
    }

    fun clear() {
        text = null
    }
}
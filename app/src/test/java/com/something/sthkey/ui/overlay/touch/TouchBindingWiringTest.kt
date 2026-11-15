package com.something.sthkey.ui.overlay.touch

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 绑定这条链路在**服务端**的接线（源码级断言）。
 *
 * ============================================================
 * ⚠️ 为什么是"读源码"而不是跑起来测
 * ============================================================
 * `TouchRegionContainerService` 是 `Service`、`TouchEditorPanel` 是 Android View，
 * 两者的逻辑都在 JVM 单测跑不起来的地方（项目里没有 instrumentation 测试）。
 *
 * ⚠️ 而这条链路上有三个**静默失败**，每一个都曾经发生过或差一点发生:
 *
 * | 漏掉 | 用户看到 |
 * |---|---|
 * | 改动检测只比几何 | 只改了绑定 → 保存退出**什么都没写** |
 * | 改动检测只看"剩下的区域" | 只删了一块 → **删除不落盘**，重开又回来了 |
 * | 候选列表不过滤 | 绑了摇杆/媒体 → 按了**完全没反应** |
 *
 * 这类断言保不住"逻辑对"，但能保住"没有人把这几行删掉" ——
 * 与项目里 `TouchKeyLightingTest` / `TouchRegionCodecTest` 同一个取舍。
 */
class TouchBindingWiringTest {

    private fun read(relative: String): String {
        val candidates = listOf(
            File("app/src/main/java/com/something/sthkey/$relative"),
            File("src/main/java/com/something/sthkey/$relative"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到源码文件:$relative（工作目录=${File(".").absolutePath}）")
        return file.readText()
    }

    @Test
    fun `只改绑定也会触发保存`() {
        val source = read("capture/touch/TouchRegionContainerService.kt")
        assertTrue(
            "改动检测里没有比 `bindings` —— 只改绑定、没动位置时会被当成'没有改动'，" +
                "保存退出后配置里什么都没写",
            source.contains("before.bindings != item.bindings"),
        )
        assertTrue(
            "保存自定义区域时没有把 Item.bindings 写回配置",
            source.contains("bindings = bindings"),
        )
    }

    @Test
    fun `只删区域也会触发保存`() {
        val source = read("capture/touch/TouchRegionContainerService.kt")
        assertTrue(
            "没有统计被删掉的区域 id —— 只删一块时 `changed` 为空，" +
                "会走进'没有改动，直接退出'，删除丢失",
            source.contains("val removedIds = snapshot.keys - remainingIds"),
        )
        assertTrue(
            "早退判据没有把 `removedIds` 算进去",
            source.contains("changed.isEmpty() && removedIds.isEmpty()"),
        )
    }

    @Test
    fun `绑定页只列有效组件`() {
        val source = read("capture/touch/TouchRegionContainerService.kt")
        assertTrue(
            "轮盘区域的候选没有限定'触屏移动摇杆' —— 绑手柄/键盘/鼠标摇杆" +
                "或视角摇杆都不会有任何效果",
            source.contains("component.source == JoystickSource.TOUCH && component.side == StickSide.LEFT"),
        )
        assertTrue(
            "候选没有过滤媒体组件 —— 它没有'按下'这回事，绑了等于没绑",
            source.contains("is MediaComponent -> false"),
        )
    }

    @Test
    fun `面板把绑定页的交互交给服务`() {
        val panel = read("ui/overlay/touch/TouchEditorPanel.kt")
        listOf("onBindingDirection", "onBindingToggle", "onBindingConfirm", "onBindingCancel")
            .forEach { callback ->
                assertTrue(
                    "面板没有接上 `$callback` —— 绑定页点了没反应",
                    panel.contains(callback),
                )
            }
    }
}

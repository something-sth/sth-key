package com.something.sthkey.ui.feature.incoming

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外部导入之后"要不要刷新配置列表"的判据（2026-11-15）。
 *
 * | 结果 | 刷新？ | 为什么 |
 * |---|---|---|
 * | `ConfigOk` | ✅ | 新配置进了 `ConfigStore` |
 * | `GroupOk` | ✅ | 组合包会一次建出多份配置 + 组合 |
 * | `FontOk` / `ModelOk` | ❌ | 全局资源，不在配置列表里 |
 * | `Failed` | ❌ | 什么都没写进去 |
 */
class IncomingImportRefreshTest {

    @Test
    fun `导入配置需要刷新`() {
        assertTrue(resultsNeedConfigRefresh(listOf(ItemResult.ConfigOk("测试配置"))))
    }

    @Test
    fun `导入组合需要刷新`() {
        assertTrue(resultsNeedConfigRefresh(listOf(ItemResult.GroupOk("组合", 2))))
    }

    @Test
    fun `字体和模型不需要刷新配置列表`() {
        assertFalse(
            resultsNeedConfigRefresh(
                listOf(
                    ItemResult.FontOk("字体", reused = false),
                    ItemResult.ModelOk("模型", reused = true),
                ),
            ),
        )
    }

    @Test
    fun `失败不需要刷新`() {
        assertFalse(resultsNeedConfigRefresh(listOf(ItemResult.Failed("坏文件", "读不到"))))
    }

    @Test
    fun `一批里只要有一个配置成功就要刷新`() {
        assertTrue(
            resultsNeedConfigRefresh(
                listOf(
                    ItemResult.Failed("坏文件", "读不到"),
                    ItemResult.FontOk("字体", reused = false),
                    ItemResult.ConfigOk("测试配置"),
                ),
            ),
        )
    }

    /**
     * 源码级接线：外部导入的成功回调必须真的接到 ViewModel 的版本号上。
     *
     * ⚠️ 逻辑单测只能证明"该不该刷新"，证明不了"有没有接上"——
     * 而这个 bug 的成因正是"存储写了，但没人通知界面"。
     */
    @Test
    fun `外部导入成功后接到配置与组合的版本号`() {
        val activity = read("MainActivity.kt")
        assertTrue(
            "MainActivity 没把 IncomingFileDialog 的 onImported 接上:\n" +
                activity.substringAfter("IncomingFileDialog(", "").take(800),
            activity.contains("onImported = {") &&
                activity.contains("viewModel.reloadConfigs()") &&
                activity.contains("viewModel.reloadGroups()"),
        )
    }

    /**
     * ⚠️ 顺序不能反：图片字体命名回调会清空 `incomingRequest`，对话框随之销毁；
     * 刷新通知放在它后面的话，"配置 + 图片字体"同批导入会漏刷新。
     */
    @Test
    fun `刷新通知在图片字体命名之前`() {
        val source = read("ui/feature/incoming/IncomingFileDialog.kt")
        val refresh = source.indexOf("if (resultsNeedConfigRefresh(results))")
        val naming = source.indexOf("onNeedBitmapNaming(it.source)")
        assertTrue("找不到刷新通知", refresh >= 0)
        assertTrue("找不到图片字体命名回调", naming >= 0)
        assertTrue("刷新通知必须在图片字体命名之前（见注释）", refresh < naming)
    }

    private fun read(relative: String): String {
        val candidates = listOf(
            File("app/src/main/java/com/something/sthkey/$relative"),
            File("src/main/java/com/something/sthkey/$relative"),
        )
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: error("找不到源码:$relative（工作目录=${File(".").absolutePath}）")
    }
}

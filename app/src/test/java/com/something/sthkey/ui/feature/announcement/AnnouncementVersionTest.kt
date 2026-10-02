package com.something.sthkey.ui.feature.announcement

import com.something.sthkey.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新公告与版本号的一致性。
 *
 * ============================================================
 * 这里钉的是一个"发了新版却不弹公告"的坑
 * ============================================================
 * 弹窗的判断依据是 `BuildConfig.VERSION_NAME` 与设置里"已读版本"
 * 是否一致 —— 也就是说**公告条的版本号必须与 `versionName` 逐字相同**。
 *
 * 漏改一处（改了 `versionName` 没加公告条，或者公告条写错了版本号）
 * 的表现是：发了新版却什么都不弹。而这件事**在开发机上不会自己暴露**：
 * 代码全对、编译通过，只有用户侧"没看到公告"。
 *
 * 所以只能靠这条断言钉住。
 */
class AnnouncementVersionTest {

    /**
     * 公告里最新的一版必须与 `BuildConfig.VERSION_NAME` 完全一致。
     *
     * 发版时的两步（改 `versionName`、在最前面插一条公告）必须**同时**做，
     * 这条测试就是那个"同时"的检查。
     */
    @Test
    fun `最新公告的版本号与当前版本一致`() {
        assertEquals(
            "app/build.gradle.kts 的 versionName 与 AppAnnouncement.releases " +
                "里最新一条对不上 —— 会表现为「发了新版却不弹公告」",
            BuildConfig.VERSION_NAME,
            AppAnnouncement.latest.version,
        )
    }

    /**
     * 版本号不能重复。
     *
     * 重复的话，弹窗往前翻历史时会看到两条同名版本，
     * 而"已读版本"是按版本号记的 —— 用户可能永远看不到其中一条。
     */
    @Test
    fun `公告里的版本号不重复`() {
        val versions = AppAnnouncement.releases.map { it.version }

        assertEquals(
            "有重复的版本号：$versions",
            versions.size,
            versions.distinct().size,
        )
    }

    /**
     * 最新的排在最前面。
     *
     * 弹窗默认显示第一条（`latest`），而且"上一版 / 下一版"的翻页
     * 是按列表顺序走的。顺序错了的话，用户打开公告看到的是旧版本内容。
     *
     * 用日期排序判断而不是版本号字符串比较：版本号是字符串，
     * `"2.10.0" < "2.9.0"` 这种比较会得出错误结论。
     */
    @Test
    fun `公告按时间从新到旧排列`() {
        val dates = AppAnnouncement.releases.map { it.date }
        val sorted = dates.sortedDescending()

        assertEquals("公告顺序应当从新到旧，实际：$dates", sorted, dates)
    }

    /** 每一版都要有正文：空条目会在弹窗里显示成一片空白 */
    @Test
    fun `每一版公告都有内容`() {
        AppAnnouncement.releases.forEach { release ->
            assertTrue(
                "${release.version} 没有 intro",
                release.intro.isNotBlank(),
            )
            assertTrue(
                "${release.version} 没有任何小节",
                release.sections.isNotEmpty(),
            )
            release.sections.forEach { section ->
                assertTrue(
                    "${release.version} 的「${section.title}」小节没有正文",
                    section.lines.isNotEmpty(),
                )
            }
        }
    }
}

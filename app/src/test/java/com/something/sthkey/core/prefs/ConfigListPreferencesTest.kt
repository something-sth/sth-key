package com.something.sthkey.core.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置列表的排版偏好。
 *
 * ============================================================
 * 这里防的是什么
 * ============================================================
 * `KEY_CONFIG_LIST_COLUMNS` 这个偏好有一个**极易混淆的地方**：
 * "默认一行几个"和"允许最少一行几个"是两件事，而它们很容易被写成同一个值。
 *
 * 混在一起的后果：
 * - 若把默认值直接当成下限（`coerceIn(DEFAULT, MAX)`），
 *   用户就**调不到 1**，而 1 是完全合理的需求（小屏、长配置名）；
 * - 若把下限当成默认值，新装的用户会看到"一行一个"的长列表。
 *
 * 所以两者各是一个常量，这里把它们的关系钉住。
 */
class ConfigListPreferencesTest {

    /**
     * ⚠️ 默认值必须是 **2**，而不是下限。
     *
     * 用户明确要求过：手机竖屏一行两个最省滚动，"一行一个"会让列表长得离谱。
     */
    @Test
    fun `默认一行两个`() {
        assertEquals(
            "配置列表默认应当一行 2 个",
            2,
            AppPrefs.CONFIG_LIST_COLUMNS_DEFAULT,
        )
    }

    /**
     * ⚠️ 默认值**不等于**下限。
     *
     * 一旦相等，就说明有人把两件事合成了一件 —— 那时"默认一行两个"
     * 和"最少能调到 1"必然有一个是错的。
     */
    @Test
    fun `默认值与下限是两件事`() {
        assertTrue(
            "默认值(${AppPrefs.CONFIG_LIST_COLUMNS_DEFAULT}) 必须不小于下限" +
                "(${AppPrefs.CONFIG_LIST_COLUMNS_MIN})",
            AppPrefs.CONFIG_LIST_COLUMNS_DEFAULT >= AppPrefs.CONFIG_LIST_COLUMNS_MIN,
        )
        assertTrue(
            "默认值(${AppPrefs.CONFIG_LIST_COLUMNS_DEFAULT}) 必须不大于上限" +
                "(${AppPrefs.CONFIG_LIST_COLUMNS_MAX})",
            AppPrefs.CONFIG_LIST_COLUMNS_DEFAULT <= AppPrefs.CONFIG_LIST_COLUMNS_MAX,
        )
    }

    /**
     * 上限是 6。
     *
     * 由**可读性**决定：再密的话每个卡片窄到装不下一个 `LMB(cps2)`
     * 这样的配置名，列表就没法看了。
     */
    @Test
    fun `上限为 6`() {
        assertEquals("一行最多 6 个", 6, AppPrefs.CONFIG_LIST_COLUMNS_MAX)
    }

    /** 下限为 1：一行一个在小屏上是合理需求，不能因为改了默认值就把它禁掉 */
    @Test
    fun `下限仍为 1`() {
        assertEquals("用户仍然可以选一行一个", 1, AppPrefs.CONFIG_LIST_COLUMNS_MIN)
    }
}

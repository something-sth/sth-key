package com.something.sthkey.data.config

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Android 9 上「导出到 Download」的重名处理。
 *
 * ============================================================
 * 为什么单测这么重要
 * ============================================================
 * Android 10 及以上走 MediaStore，**重名由系统自动改名**（`x (1).sthkey`），
 * 我们不用管。但 Android 9 走的是老的 `File` 写入 —— 重名策略得自己实现，
 * 而且**必须与 MediaStore 长得一样**:
 *
 * | 场景 | 期望 |
 * |---|---|
 * | 两台设备导出同一个配置 | 都得到 `配置 (1).sthkey`，而不是一台覆盖另一台 |
 *
 * ⚠️ 覆盖会**直接丢用户数据**（配置包里还有字体文件），所以这是必须测的部分。
 */
class ExportNameCollisionTest {

    @Test
    fun `没重名时文件名原样返回`() {
        assertEquals(
            "我的配置.sthkey",
            ConfigExporters.uniqueName("我的配置.sthkey", emptySet()),
        )
    }

    @Test
    fun `重名时按 MediaStore 的样式加序号`() {
        assertEquals(
            "我的配置 (1).sthkey",
            ConfigExporters.uniqueName("我的配置.sthkey", setOf("我的配置.sthkey")),
        )
    }

    @Test
    fun `连续重名时序号往上递增`() {
        val taken = setOf(
            "配置.sthkey",
            "配置 (1).sthkey",
            "配置 (2).sthkey",
        )
        assertEquals("配置 (3).sthkey", ConfigExporters.uniqueName("配置.sthkey", taken))
    }

    @Test
    fun `序号中间被删掉时会补那个空位`() {
        /*
         * 用户删掉了 "配置 (1).sthkey"、留下了 "(2)"。
         * 我们应当**补 (1)**（第一个空位），而不是无脑取 max+1 变成 (3)。
         * ⚠️ 这与 MediaStore 的实际行为一致，也避免序号无限往上长。
         */
        val taken = setOf("配置.sthkey", "配置 (2).sthkey")
        assertEquals("配置 (1).sthkey", ConfigExporters.uniqueName("配置.sthkey", taken))
    }

    @Test
    fun `名字里本身带点时只认最后一个点`() {
        /*
         * ⚠️ 用 `substringBefore('.')` 的话，这里会得到 "配置 1 (1)" ——
         * 既丢了 ".2"，扩展名也没了。
         */
        assertEquals(
            "配置 1.2 (1).sthkey",
            ConfigExporters.uniqueName("配置 1.2.sthkey", setOf("配置 1.2.sthkey")),
        )
    }

    @Test
    fun `没有扩展名时序号加在末尾`() {
        assertEquals("配置 (1)", ConfigExporters.uniqueName("配置", setOf("配置")))
    }

    @Test
    fun `隐藏文件风格的名字不会切出空基础名`() {
        /*
         * `.sthkey` 这种（点在最前面）不能当成"有扩展名"，
         * 否则会拼出 " (1)" 这种看着像坏掉的名字。
         */
        assertEquals(".sthkey (1)", ConfigExporters.uniqueName(".sthkey", setOf(".sthkey")))
    }

    @Test
    fun `大小写不同的同名文件视为不同`() {
        /*
         * ⚠️ Android 的公共 Download 目录底层多为大小写敏感（ext4/f2fs），
         * 所以 `A.sthkey` 与 `a.sthkey` 是两个文件，不算重名。
         * 这里把"是否重名"完全交给调用方传入的集合，逻辑本身不做归一化。
         */
        assertEquals(
            "A.sthkey",
            ConfigExporters.uniqueName("A.sthkey", setOf("a.sthkey")),
        )
    }
}

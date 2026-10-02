package com.something.sthkey.data.config

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 导出失败后"清理空文件再重试"的**判据**测试。
 *
 * ============================================================
 * 为什么这几条必须有测试
 * ============================================================
 * 这是全项目唯一一处**删用户文件**的地方。判断失误的后果不是"显示不对"，
 * 而是用户目录里的文件没了 —— 而且他很可能不会立刻发现。
 *
 * 所以判据写成了纯函数（[ConfigPackageManager.shouldCleanUp]），
 * 把"允许删"与"拒绝删"的每一种情形都摆在这里。
 *
 * ============================================================
 * 背景：那个空文件是哪来的
 * ============================================================
 * 系统选择器（`ACTION_CREATE_DOCUMENT`）在**应用还没写任何数据之前**就把
 * 文件建出来了。写入失败时目录里就留下一个 0 字节的文件；
 * 它还会占住名字，导致下一次导出被自动改名成 `xxx_1`（ColorOS 文件管理器）
 * 或 `xxx (1).sthkey`（系统默认）。
 */
class ExportCleanupTest {

    /** 常见情形：用户请求 `布吉岛.sthkey`，目录里已有同名，系统改名成 `布吉岛_1.sthkey` */
    private fun decide(
        requested: String = "布吉岛.sthkey",
        actual: String? = "布吉岛_1.sthkey",
        empty: Boolean = true,
    ) = ConfigPackageManager.shouldCleanUp(
        requestedName = requested,
        actualName = actual,
        isEmpty = empty,
    )

    /*
     * ============================================================
     * 允许清理：只有这一种情形
     * ============================================================
     */

    @Test
    fun `系统改名造出来的空文件可以清理`() {
        assertNull(
            "这是唯一该被清理的情形：空 + 名字被系统改过",
            decide(requested = "布吉岛.sthkey", actual = "布吉岛_1.sthkey", empty = true),
        )
    }

    @Test
    fun `系统默认文件管理器的括号后缀也算改名`() {
        assertNull(decide(requested = "布吉岛.sthkey", actual = "布吉岛 (1).sthkey", empty = true))
    }

    /*
     * ============================================================
     * 拒绝清理：每一种都要各自钉一条
     * ============================================================
     */

    @Test
    fun `非空文件永远不清理`() {
        // 里面可能有东西 —— 用户自己放的文件，或者上一次**成功**的导出。
        // 这一条是三道保险里最重要的：宁可留下垃圾，也不能删掉内容。
        assertNotNull(
            "哪怕名字被改过，非空也绝不清理",
            decide(requested = "布吉岛.sthkey", actual = "布吉岛_1.sthkey", empty = false),
        )
    }

    @Test
    fun `名字与请求一致时不清理`() {
        // 名字一致说明用户选的就是它 —— 那可能是他已有的文件，
        // 空着也轮不到应用来删（他可能就是特意建了个空文件占位）
        assertNotNull(
            "没被改名就不是系统替我们造的，不能删",
            decide(requested = "布吉岛.sthkey", actual = "布吉岛.sthkey", empty = true),
        )
    }

    @Test
    fun `读不到文件名时不清理`() {
        // provider 不提供 DISPLAY_NAME 时无从判断有没有被改名 —— 无法确认就不动手
        assertNotNull(
            "读不到名字就无法确认它是系统造的，保守拒绝",
            decide(actual = null, empty = true),
        )
    }

    @Test
    fun `空文件 + 没有内容时任何名字都允许清理`() {
        /*
         * 边界：实际名字是空串（provider 返回了一个不正常的名字）时，
         * 它与请求名不同，于是被判成"系统改过名"，允许清理。
         *
         * 可以接受：删掉一个 **0 字节**的文件不会损失任何内容。
         * 真正要守住的是下面那条 —— 一旦有内容，无论名字是什么都不清理。
         */
        assertNull(decide(requested = "布吉岛.sthkey", actual = "", empty = true))
        assertNotNull(
            "但只要它有内容，就绝不清理",
            decide(requested = "布吉岛.sthkey", actual = "", empty = false),
        )
    }

    @Test
    fun `建议文件名与后缀常量拼得自洽`() {
        // 判据比较的是"请求的名字"与"实际的名字"，而请求名是
        // config.name + EXTENSION 拼出来的。拼接方式变了而这里没跟着变的话，
        // "改名检测"会整体失效（永远判成没改名 → 永远不清理）。
        org.junit.Assert.assertEquals(
            "布吉岛.sthkey",
            "布吉岛" + ConfigPackageCodec.EXTENSION,
        )
    }
}

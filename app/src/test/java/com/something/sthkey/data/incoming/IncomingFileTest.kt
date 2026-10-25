package com.something.sthkey.data.incoming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外部请求的 Uri 提取（纯逻辑，不需要 Android 环境）。
 *
 * ⚠️ 这里钉的是"哪种 action 从哪取"这类约定 ——
 * 错了的表现是"分享过来没反应"，很难从现象反推。
 */
class IncomingFileTest {

    @Test
    fun `分享走 EXTRA_STREAM`() {
        val picked = IncomingFile.pickUris(
            action = "android.intent.action.SEND",
            data = null,
            stream = "content://share/font.ttf",
            streamList = null,
        )
        assertEquals(listOf("content://share/font.ttf"), picked?.locations)
        assertTrue(picked?.fromShare == true)
    }

    @Test
    fun `一次分享多个文件全部收下`() {
        /*
         * ⚠️ 这是与旧实现最关键的差别:旧版只取第一个，
         * 而"一次分享三个字体"是很常见的操作。
         */
        val picked = IncomingFile.pickUris(
            action = "android.intent.action.SEND",
            data = null,
            stream = null,
            streamList = listOf("content://a.ttf", "content://b.otf", "content://c.ttc"),
        )
        assertEquals(3, picked?.locations?.size)
    }

    @Test
    fun `单值与列表同时存在时不重复`() {
        val picked = IncomingFile.pickUris(
            action = "android.intent.action.SEND",
            data = null,
            stream = "content://a.ttf",
            streamList = listOf("content://a.ttf", "content://b.ttf"),
        )
        assertEquals(listOf("content://a.ttf", "content://b.ttf"), picked?.locations)
    }

    @Test
    fun `打开方式走 data`() {
        val picked = IncomingFile.pickUris(
            action = "android.intent.action.VIEW",
            data = "content://view/thing.bin",
            stream = null,
            streamList = null,
        )
        assertEquals(listOf("content://view/thing.bin"), picked?.locations)
        assertTrue(picked?.fromShare == false)
    }

    @Test
    fun `打开方式下 data 为空时退一步看 extra`() {
        /* ⚠️ 确实有应用在 VIEW 时把 Uri 塞 extra（不规范但存在） */
        val picked = IncomingFile.pickUris(
            action = "android.intent.action.VIEW",
            data = null,
            stream = "content://view/fallback.zip",
            streamList = null,
        )
        assertEquals(listOf("content://view/fallback.zip"), picked?.locations)
    }

    @Test
    fun `打开方式永远只有一个文件`() {
        val picked = IncomingFile.pickUris(
            action = "android.intent.action.VIEW",
            data = "content://view/first.ttf",
            stream = null,
            streamList = listOf("content://a", "content://b"),
        )
        assertEquals(1, picked?.locations?.size)
    }

    @Test
    fun `主入口的 action 不处理`() {
        assertNull(
            IncomingFile.pickUris(
                action = "android.intent.action.MAIN",
                data = "content://whatever",
                stream = null,
                streamList = null,
            ),
        )
    }

    @Test
    fun `没有位置时返回 null`() {
        assertNull(
            IncomingFile.pickUris(
                action = "android.intent.action.SEND",
                data = null,
                stream = null,
                streamList = null,
            ),
        )
    }
}

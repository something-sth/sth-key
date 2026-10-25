package com.something.sthkey.data.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `.sthgroup` 的 manifest 编解码。
 *
 * ⚠️ 这一层出错的表现是"导入一个组合包，结果提示不是组合包"或者
 * "组合建出来了但里面一个配置都没有" —— 都是很难从现象反推的故障，
 * 所以格式的边界情况要在这里钉住。
 */
class GroupPackageCodecTest {

    private fun encode(vararg files: String) = GroupPackageCodec.encodeManifest(
        name = "我的组合",
        description = "日常用",
        appVersion = "2.7.1",
        configs = files.map { GroupPackageCodec.ConfigRef(file = it, name = it.substringBeforeLast('.')) },
    )

    @Test
    fun `编码后能原样解回来`() {
        val raw = GroupPackageCodec.encodeManifest(
            name = "战斗",
            description = "打游戏用",
            appVersion = "2.7.1",
            configs = listOf(
                GroupPackageCodec.ConfigRef("键盘.sthkey", "键盘", "主配置", "keyboard"),
                GroupPackageCodec.ConfigRef("手柄.sthkey", "手柄", "", "gamepad2"),
            ),
        )

        val manifest = GroupPackageCodec.decodeManifest(raw)
        assertNotNull(manifest)
        requireNotNull(manifest)

        assertEquals(GroupPackageCodec.FORMAT_ID, manifest.format)
        assertEquals(GroupPackageCodec.FORMAT_VERSION, manifest.formatVersion)
        assertEquals("战斗", manifest.name)
        assertEquals("打游戏用", manifest.description)
        assertEquals(2, manifest.configs.size)
        /* ⚠️ 顺序必须保持 —— 它决定组合里成员的顺序 */
        assertEquals(listOf("键盘.sthkey", "手柄.sthkey"), manifest.configs.map { it.file })
        assertEquals("keyboard", manifest.configs[0].styleId)
    }

    @Test
    fun `空组合是合法的`() {
        /*
         * ⚠️ 组合本身可以为空（用户建完再往里加配置），
         * 所以 `configs` 为空**不能**被当成"格式错误"。
         */
        val manifest = GroupPackageCodec.decodeManifest(encode())
        assertNotNull(manifest)
        assertEquals(0, requireNotNull(manifest).configs.size)
    }

    @Test
    fun `配置包的 manifest 不会被当成组合包`() {
        /*
         * ⚠️ `.sthkey` 的 manifest 也是 JSON 且有 format 字段，
         * 只是值不同 —— 这里必须返回 null，否则导入配置会被误判成组合。
         */
        val configManifest = """{"format":"sthkey-config","formatVersion":1,"name":"x"}"""
        assertNull(GroupPackageCodec.decodeManifest(configManifest))
    }

    @Test
    fun `缺 file 的条目被丢弃`() {
        /*
         * ⚠️ 没有 file 就无从在包里找到它，留着只会变成一条
         * "永远导入失败"的报告项。
         */
        val raw = """
            {
              "format": "sthkey-group",
              "formatVersion": 1,
              "name": "x",
              "configs": [
                {"file": "a.sthkey", "name": "a"},
                {"name": "缺文件名"},
                {"file": "", "name": "空文件名"}
              ]
            }
        """.trimIndent()

        val manifest = GroupPackageCodec.decodeManifest(raw)
        assertNotNull(manifest)
        assertEquals(1, requireNotNull(manifest).configs.size)
        assertEquals("a.sthkey", manifest.configs[0].file)
    }

    @Test
    fun `坏 JSON 返回 null 而不是抛异常`() {
        assertNull(GroupPackageCodec.decodeManifest("{ 这不是 json"))
        assertNull(GroupPackageCodec.decodeManifest(""))
    }

    @Test
    fun `扩展名常量符合预期`() {
        assertEquals(".sthgroup", GroupPackageCodec.EXTENSION)
        /* ⚠️ 也接受 .zip:有些文件管理器会把后缀改掉 */
        assertTrue(".zip" in GroupPackageCodec.ACCEPTED_EXTENSIONS)
        assertFalse(".sthg" in GroupPackageCodec.ACCEPTED_EXTENSIONS)
    }
}

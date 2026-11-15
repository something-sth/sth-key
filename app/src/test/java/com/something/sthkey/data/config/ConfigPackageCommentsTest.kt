package com.something.sthkey.data.config

import com.something.sthkey.domain.config.defaultConfig
import com.something.sthkey.domain.style.StyleId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导出说明（`_comment`）在**导出/导入两侧**都不添乱。
 */
class ConfigPackageCommentsTest {

    @Test
    fun `manifest 带说明且仍能解析`() {
        val raw = ConfigPackageCodec.encodeManifest(
            config = defaultConfig(),
            appVersion = "test",
            exportedAt = 1L,
            fontFiles = emptyList(),
            live2d = null,
        )

        assertTrue("manifest 应带 _comment:$raw", raw.contains("\"_comment\""))
        val manifest = ConfigPackageCodec.decodeManifest(raw)
        assertNotNull("带说明的 manifest 必须仍能解析", manifest)
        assertEquals(ConfigPackageCodec.FORMAT_ID, manifest!!.format)
    }

    @Test
    fun `组合 manifest 带说明且仍能解析`() {
        val raw = GroupPackageCodec.encodeManifest(
            name = "组合",
            description = "",
            appVersion = "test",
            configs = emptyList(),
        )

        assertTrue("组合 manifest 应带 _comment:$raw", raw.contains("\"_comment\""))
        assertNotNull(GroupPackageCodec.decodeManifest(raw))
    }

    @Test
    fun `导入字段差异会忽略下划线元数据`() {
        val params = ConfigJsonComments.withParamsComment(
            JsonConfigCodec.encodeForStorage(defaultConfig()),
            StyleId.KEYSTROKES,
        )

        val diff = ConfigPackageCodec.compareFields(params)
        assertFalse(
            "_comment 不该被报成未识别字段:${diff.unknown}",
            diff.unknown.any { it.startsWith("_") },
        )
    }

    @Test
    fun `带说明的 params 仍能解码`() {
        val config = defaultConfig().copy(id = "comment-test", name = "说明测试")
        val params = ConfigJsonComments.withParamsComment(
            JsonConfigCodec.encodeForStorage(config),
            config.styleId,
        )

        val decoded = JsonConfigCodec.decode(params)
        assertEquals("comment-test", decoded.id)
        assertEquals("说明测试", decoded.name)
    }

    @Test
    fun `说明是合法 JSON 数组`() {
        val wrapped = ConfigJsonComments.withParamsComment(
            JSONObject().apply {
                put("styleId", StyleId.KEYSTROKES)
                put("scalePercent", 100)
            },
            StyleId.KEYSTROKES,
        )

        val comment = wrapped.optJSONArray(ConfigJsonComments.KEY)
        assertNotNull("_comment 必须是数组", comment)
        assertTrue("说明不能为空", (comment?.length() ?: 0) > 0)
    }
}

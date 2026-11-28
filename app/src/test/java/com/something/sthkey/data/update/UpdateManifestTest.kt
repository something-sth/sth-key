package com.something.sthkey.data.update

import com.something.sthkey.domain.update.compareVersionNames
import com.something.sthkey.domain.update.isNewerThan
import com.something.sthkey.domain.update.versionCodeFromTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 检查更新的**解析与版本比较**。
 *
 * 网络没法在单测里覆盖，但"解析错了 / 比较反了"恰恰是这类功能最容易出事的地方：
 * 判反了会天天提示更新，解析失败会永远"已是最新"。
 */
class UpdateManifestTest {

    @Test
    fun `解析 version_json 的完整字段`() {
        val json = """
            {
              "versionCode": 288,
              "versionName": "2.8.8",
              "tag": "v2.8.8",
              "pageUrl": "https://github.com/something-sth/sth-key/releases/tag/v2.8.8",
              "downloadUrl": "https://example.com/sth-key_v2.8.8_288.apk",
              "notes": "Live2D 背景\n检查更新"
            }
        """.trimIndent()

        val info = UpdateManifest.parse(json)
        assertNotNull("应该解析成功", info)
        assertEquals(288, info!!.versionCode)
        assertEquals("2.8.8", info.versionName)
        assertEquals("https://example.com/sth-key_v2.8.8_288.apk", info.downloadUrl)
        assertEquals("https://github.com/something-sth/sth-key/releases/tag/v2.8.8", info.pageUrl)
        assertTrue("更新说明要保留换行", info.notes.contains("Live2D"))
        assertEquals("manifest", info.source)
    }

    @Test
    fun `version_json 没有版本名时视为无效`() {
        assertNull(UpdateManifest.parse("""{"versionCode": 288}"""))
        assertNull(UpdateManifest.parse("""{"versionName": "  "}"""))
        assertNull(UpdateManifest.parse("not a json"))
    }

    @Test
    fun `GitHub Release 从附件名同时拿到版本号与直链`() {
        val json = """
            {
              "tag_name": "v2.8.8",
              "html_url": "https://github.com/something-sth/sth-key/releases/tag/v2.8.8",
              "body": "更新说明",
              "prerelease": false,
              "draft": false,
              "assets": [
                { "name": "sth-key_v2.8.8_288.apk",
                  "browser_download_url": "https://github.com/something-sth/sth-key/releases/download/v2.8.8/sth-key_v2.8.8_288.apk" },
                { "name": "sth-key_v2.8.8_288.apk.sha256",
                  "browser_download_url": "https://example.com/ignore" }
              ]
            }
        """.trimIndent()

        val info = UpdateManifest.parseGitHubRelease(json)
        assertNotNull(info)
        assertEquals(288, info!!.versionCode)
        assertEquals("2.8.8", info.versionName)
        assertTrue(info.downloadUrl.endsWith("sth-key_v2.8.8_288.apk"))
        assertEquals("github", info.source)
    }

    @Test
    fun `附件名没有版本号时从 tag 推 versionCode`() {
        val json = """
            {
              "tag_name": "v2.10.1",
              "html_url": "https://github.com/something-sth/sth-key/releases/tag/v2.10.1",
              "assets": [
                { "name": "app-release.apk", "browser_download_url": "https://example.com/app-release.apk" }
              ]
            }
        """.trimIndent()

        val info = UpdateManifest.parseGitHubRelease(json)
        assertNotNull(info)
        assertEquals("2.10.1", info!!.versionName)
        /*
         * 现行规则是 major*100 + minor*10 + patch：2*100 + 10*10 + 1 = 301。
         *
         * ⚠️ 这套编码在 minor/patch ≥ 10 时会撞号（2.10.1 与 3.0.1 都是 301），
         * 所以真相源始终是 version.json 里显式写的 versionCode；这里只是兜底。
         */
        assertEquals(301, info.versionCode)
        assertEquals("https://example.com/app-release.apk", info.downloadUrl)
    }

    @Test
    fun `预发布与草稿不提示更新`() {
        val prerelease = """{"tag_name":"v2.9.0","prerelease":true,"assets":[]}"""
        val draft = """{"tag_name":"v2.9.0","draft":true,"assets":[]}"""
        assertNull(UpdateManifest.parseGitHubRelease(prerelease))
        assertNull(UpdateManifest.parseGitHubRelease(draft))
    }

    @Test
    fun `tag 里没有三段版本号时也能给出版本名_但 code 为 0`() {
        val target = """{"tag_name":"v2.8-beta","html_url":"https://e.x","assets":[]}"""
        val info = UpdateManifest.parseGitHubRelease(target)
        assertNotNull(info)
        assertEquals("2.8-beta", info!!.versionName)
        assertEquals(0, info.versionCode)
    }

    @Test
    fun `versionCodeFromTag 规则与项目一致`() {
        assertEquals(287, versionCodeFromTag("v2.8.7"))
        assertEquals(288, versionCodeFromTag("v2.8.8"))
        assertEquals(290, versionCodeFromTag("v2.9.0"))
        assertEquals(310, versionCodeFromTag("3.1.0"))
        assertNull(versionCodeFromTag("v2.8"))
        assertNull(versionCodeFromTag("nightly"))
    }

    @Test
    fun `版本名比较是逐段数字比较而不是字符串比较`() {
        assertTrue(compareVersionNames("2.8.9", "2.8.10") < 0)
        assertTrue(compareVersionNames("2.9.0", "2.8.99") > 0)
        assertEquals(0, compareVersionNames("2.8", "2.8.0"))
        assertTrue(compareVersionNames("v2.8.8", "2.8.8") == 0)
    }

    @Test
    fun `有 versionCode 时按 code 判断新旧`() {
        val remote = com.something.sthkey.domain.update.UpdateInfo(
            versionCode = 288,
            versionName = "2.8.8",
        )
        assertTrue(remote.isNewerThan(287, "2.8.7"))
        assertFalse(remote.isNewerThan(288, "2.8.8"))
        assertFalse(remote.isNewerThan(290, "2.9.0"))
    }

    @Test
    fun `没有 versionCode 时按版本名判断新旧`() {
        val remote = com.something.sthkey.domain.update.UpdateInfo(
            versionCode = 0,
            versionName = "2.8.10",
        )
        assertTrue(remote.isNewerThan(287, "2.8.9"))
        assertFalse(remote.isNewerThan(287, "2.8.10"))
    }
}

package com.something.sthkey.capture.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 无障碍写入命令的回归测试。
 *
 * ⚠️ 这里钉的是一个真实踩过的坑：`--user current` 前缀被拼了两次，
 * 命令变成 `settings --user current settings --user current put …`。
 * `settings` 会把第二个 `settings` 当成 key 名，命令照常返回 0，
 * 但**什么都没写** —— 表现是"读回校验失败、列表里只有别人的服务"，
 * 用户看到的就是"授权成功提示/无反应、无障碍没开"。
 */
class AccessibilityGrantCommandTest {

    @Test
    fun `写入命令只带一次 settings 前缀`() {
        val command = AccessibilityGrant.buildWriteCommand("com.a/com.a.Svc")

        assertTrue(command.startsWith("settings --user current put secure enabled_accessibility_services"))
        assertFalse("不能出现两个 settings 前缀：$command", command.contains("settings --user current settings"))
        assertTrue(command.contains("accessibility_enabled 1"))
    }

    @Test
    fun `空列表走 delete 而不是 put 空串`() {
        val command = AccessibilityGrant.buildWriteCommand("")

        assertTrue(command.contains("delete secure enabled_accessibility_services"))
        assertFalse(command.contains("put secure enabled_accessibility_services ''"))
        assertTrue(command.contains("accessibility_enabled 1"))
    }
}

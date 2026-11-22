package com.something.sthkey.data.live2d

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Mver 手部贴图解析的自检（**本机样本存在时才跑**）。
 *
 * ⚠️ 这段解析是"按键没反应"最可能出问题的地方：目录判断、模式段选择、VK→键名。
 * 任何一处错了 `__BONGO_HANDS` 都会是 null，按键就什么都不会发生。
 */
class Live2DHandSpritesTest {

    private val root = File("D:/Project/_samples/live2d/鸣潮卡提希娅最牛逼键盘猫模型/A-卡提")

    @Test
    fun `键盘变体能解析出左右手绑定`() {
        assumeTrue("样本不存在，跳过", root.isDirectory)
        val hands = Live2DMverConfig.loadHandSprites(root, "keyboard", File(root, "img/keyboard"))

        assertNotNull("keyboard 变体应当解析出手部贴图", hands)
        val left = hands!!.optJSONObject("left")
        val right = hands.optJSONObject("right")
        assertNotNull("缺少 lefthand", left)
        assertNotNull("缺少 righthand", right)
        assertTrue("lefthand 绑定为空", (left!!.optJSONArray("bindings")?.length() ?: 0) > 0)
        assertTrue("righthand 绑定为空", (right!!.optJSONArray("bindings")?.length() ?: 0) > 0)
    }

    @Test
    fun `lefthand 里能找到 KeyA`() {
        assumeTrue("样本不存在，跳过", root.isDirectory)
        val hands = Live2DMverConfig.loadHandSprites(root, "keyboard", File(root, "img/keyboard"))
        val bindings = hands?.optJSONObject("left")?.optJSONArray("bindings")
        assumeTrue(bindings != null)

        assertTrue("lefthand 里没有 KeyA 的绑定", containsKey(bindings, "KeyA"))
    }

    @Test
    fun `标准变体走单层 hand`() {
        assumeTrue("样本不存在，跳过", root.isDirectory)
        val hands = Live2DMverConfig.loadHandSprites(root, "standard", File(root, "img/standard"))
        assertNotNull("standard 应当解析出 hand 单层", hands)
        assertTrue("缺少 hand", hands!!.optJSONObject("hand") != null)
    }

    @Test
    fun `手柄变体按按钮编号解析左右手`() {
        assumeTrue("样本不存在，跳过", root.isDirectory)
        val hands = Live2DMverConfig.loadHandSprites(root, "gamepad", File(root, "img/gamepad"))

        assertNotNull("gamepad 变体应当解析出手部贴图", hands)
        val left = hands!!.optJSONObject("left")?.optJSONArray("bindings")
        val right = hands.optJSONObject("right")?.optJSONArray("bindings")
        assertNotNull("缺少 lefthand", left)
        assertNotNull("缺少 righthand", right)
        // 样本里两侧各 6 条；之前误当 VK 解析时只有 2 条
        assertEquals("lefthand 条数不对", 6, left!!.length())
        assertEquals("righthand 条数不对", 6, right!!.length())
        assertTrue("righthand 里应有 GamepadA", containsKey(right, "GamepadA"))
        assertTrue("lefthand 里应有 GamepadLB", containsKey(left, "GamepadLB"))
        // 方向键左右在 Mver 里是 10/11（之前误映射成 Back/Start，表现为左右没反应）
        assertTrue("lefthand 里应有 GamepadDpadLeft", containsKey(left, "GamepadDpadLeft"))
        assertTrue("lefthand 里应有 GamepadDpadDown", containsKey(left, "GamepadDpadDown"))
    }

    @Test
    fun `手柄摇杆能解析出位置与贴图`() {
        assumeTrue("样本不存在，跳过", root.isDirectory)
        val sticks = Live2DMverConfig.loadSticks(root, "gamepad", File(root, "img/gamepad"))

        assertNotNull("应当解析出摇杆", sticks)
        val left = sticks!!.optJSONObject("left")
        assertNotNull("缺少左摇杆", left)
        assertEquals("left_stick.png", left!!.optString("base"))
        assertTrue("左摇杆半径应 > 0", left.optDouble("radius", 0.0) > 0.0)
    }

    @Test
    fun `包根多一层目录时也能找到 config`() {
        // 真正的导入痛点：zip 根 = .../鸣潮…/，config.json 在 A-卡提/ 里
        val zipRoot = File("D:/Project/_samples/live2d/鸣潮卡提希娅最牛逼键盘猫模型")
        assumeTrue("样本不存在，跳过", zipRoot.isDirectory)

        val hands = Live2DMverConfig.loadHandSprites(
            zipRoot,
            "keyboard",
            File(zipRoot, "A-卡提/img/keyboard"),
        )
        assertNotNull("多层目录下应当仍能解析出手部贴图", hands)
    }

    private fun containsKey(bindings: org.json.JSONArray?, key: String): Boolean {
        if (bindings == null) return false
        for (i in 0 until bindings.length()) {
            val keys = bindings.optJSONObject(i).optJSONArray("keys") ?: continue
            for (k in 0 until keys.length()) if (keys.optString(k) == key) return true
        }
        return false
    }
}

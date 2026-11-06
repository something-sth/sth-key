package com.something.sthkey.domain.live2d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 挑哪一个 `*.model3.json`。
 *
 * ⚠️ 这组测试直接对应用户实测的那个模型
 * （`鸣潮卡提希娅最牛逼键盘猫模型`）:
 * 它最浅的那个 model3 是孤儿、三套真模型在更深一层、而且分 keyboard/standard/gamepad。
 * 旧实现（取遍历到的第一个）会让"能不能导入"取决于文件系统的遍历顺序。
 */
class Model3SelectionTest {

    private fun candidate(path: String, valid: Boolean = true, reason: String? = null): Model3Selection.Candidate {
        val depth = path.split('/').size - 1
        return Model3Selection.Candidate(path = path, depth = depth, valid = valid, reason = reason)
    }

    @Test
    fun `孤儿 model3 会被跳过（哪怕它最浅）`() {
        /*
         * ⚠️ 这就是那个样本的真实结构。旧实现会先命中孤儿 → 报"缺少主体文件"。
         */
        val choice = Model3Selection.choose(
            listOf(
                candidate("A-卡提/img/cat.model3.json", valid = false, reason = "缺少 Moc: cat.moc3"),
                candidate("A-卡提/img/keyboard/cat_model/cat.model3.json"),
                candidate("A-卡提/img/standard/cat_model/cat.model3.json"),
                candidate("A-卡提/img/gamepad/cat_model/cat.model3.json"),
            ),
        )

        assertTrue(choice is Model3Selection.Choice.Picked)
        val picked = choice as Model3Selection.Choice.Picked
        assertEquals("A-卡提/img/keyboard/cat_model/cat.model3.json", picked.path)
        assertEquals(4, picked.total)
        assertEquals("keyboard", picked.variant)
    }

    @Test
    fun `同深度时 keyboard 优先于 standard 和 gamepad`() {
        val choice = Model3Selection.choose(
            listOf(
                candidate("m/gamepad/cat_model/cat.model3.json"),
                candidate("m/standard/cat_model/cat.model3.json"),
                candidate("m/keyboard/cat_model/cat.model3.json"),
            ),
        ) as Model3Selection.Choice.Picked

        /* ⚠️ 与列表顺序无关:排序规则说了算 */
        assertEquals("m/keyboard/cat_model/cat.model3.json", choice.path)
    }

    @Test
    fun `更浅的优先于更深的（两者都完整时）`() {
        val choice = Model3Selection.choose(
            listOf(
                candidate("a/b/c/d/cat.model3.json"),
                candidate("a/cat.model3.json"),
            ),
        ) as Model3Selection.Choice.Picked

        assertEquals("a/cat.model3.json", choice.path)
    }

    @Test
    fun `一个 model3 都没有`() {
        assertEquals(
            Model3Selection.Choice.NoModel3,
            Model3Selection.choose(emptyList()),
        )
    }

    @Test
    fun `全都不完整时给出每个的原因`() {
        val choice = Model3Selection.choose(
            listOf(
                candidate("x/cat.model3.json", valid = false, reason = "缺少 Moc"),
                candidate("y/cat.model3.json", valid = false, reason = "缺少贴图"),
            ),
        )

        assertTrue(choice is Model3Selection.Choice.AllBroken)
        val broken = choice as Model3Selection.Choice.AllBroken
        assertEquals(2, broken.total)
        assertEquals(2, broken.reasons.size)
        assertTrue(broken.reasons.any { it.contains("缺少 Moc") })
        assertTrue(broken.reasons.any { it.contains("缺少贴图") })
    }

    @Test
    fun `识别变体名`() {
        assertEquals("keyboard", Model3Selection.variantOf("m/keyboard/cat_model/cat.model3.json"))
        assertEquals("standard", Model3Selection.variantOf("m/standard/cat.model3.json"))
        /* 认不出的就是 null，不要瞎猜 */
        assertNull(Model3Selection.variantOf("m/bycat/cat.model3.json"))
        assertNull(Model3Selection.variantOf("cat.model3.json"))
    }

    @Test
    fun `深度上限与 Axon 一致`() {
        /* Axon 的 findFilesBySuffix(root, ".model3.json", 5) */
        assertEquals(5, Model3Selection.MAX_DEPTH)
    }

    @Test
    fun `无用文件判定`() {
        /* Windows 程序与运行库 —— 样本里真实存在的 */
        assertTrue(Live2DJunkFilter.isJunk("Bongo Cat Mver.exe"))
        assertTrue(Live2DJunkFilter.isJunk("A-卡提/sfml-audio-2.dll"))
        assertTrue(Live2DJunkFilter.isJunk("openal32.dll"))
        assertTrue(Live2DJunkFilter.isJunk("按键表情使用说明.docx"))
        assertTrue(Live2DJunkFilter.isJunk("cat.psd"))
        /* 嵌套压缩包 */
        assertTrue(Live2DJunkFilter.isJunk("extra.zip"))
        /* ⚠️ 大小写不敏感 */
        assertTrue(Live2DJunkFilter.isJunk("SETUP.EXE"))

        /* 模型资源一个都不能误杀 */
        assertFalse(Live2DJunkFilter.isJunk("cat.model3.json"))
        assertFalse(Live2DJunkFilter.isJunk("cat.moc3"))
        assertFalse(Live2DJunkFilter.isJunk("cat.8192/texture_00.png"))
        assertFalse(Live2DJunkFilter.isJunk("cat.physics3.json"))
        assertFalse(Live2DJunkFilter.isJunk("expression1.exp3.json"))
        assertFalse(Live2DJunkFilter.isJunk("CAT_motion_lock/motion1.motion3.json"))
        assertFalse(Live2DJunkFilter.isJunk("cat.cdi3.json"))
        /* ⚠️ 音频**故意保留**（BongoCat 生态的按键音效） */
        assertFalse(Live2DJunkFilter.isJunk("sfx/press.flac"))
        assertFalse(Live2DJunkFilter.isJunk("sfx/press.wav"))
        /* 作者自带字体 */
        assertFalse(Live2DJunkFilter.isJunk("font.ttf"))
        /* 没有后缀的文件不删（不认识的就不动） */
        assertFalse(Live2DJunkFilter.isJunk("LICENSE"))
    }
    @Test
    fun `多模式包能列出全部有效变体，键盘排第一`() {
        /*
         * 实测样本:`img/keyboard` / `img/standard` / `img/gamepad` 三套完整，
         * 另有一个深度 2 的孤儿。列表里只应有那三套，且 keyboard 第一。
         */
        val all = Model3Selection.chooseAll(
            listOf(
                candidate("A/img/cat.model3.json", valid = false, reason = "缺少 Moc"),
                candidate("A/img/gamepad/cat_model/cat.model3.json"),
                candidate("A/img/standard/cat_model/cat.model3.json"),
                candidate("A/img/keyboard/cat_model/cat.model3.json"),
            ),
        )

        assertEquals(3, all.size)
        assertEquals("A/img/keyboard/cat_model/cat.model3.json", all[0].path)
        assertEquals("keyboard", all[0].variant)
        /* 顺序固定（standard 在 gamepad 之前），保证每次导入结果一致 */
        assertEquals("A/img/standard/cat_model/cat.model3.json", all[1].path)
        assertEquals("A/img/gamepad/cat_model/cat.model3.json", all[2].path)
    }

    @Test
    fun `只有一套完整模型时 chooseAll 只有一个`() {
        val all = Model3Selection.chooseAll(
            listOf(candidate("m/cat.model3.json"), candidate("m/broken/cat.model3.json", valid = false)),
        )
        assertEquals(1, all.size)
        assertEquals("m/cat.model3.json", all[0].path)
    }
}

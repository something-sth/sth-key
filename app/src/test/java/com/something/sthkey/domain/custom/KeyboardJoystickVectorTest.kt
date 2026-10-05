package com.something.sthkey.domain.custom

import com.something.sthkey.domain.keys.KeyCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * 「摇杆-键盘」的八段式方向。
 *
 * ============================================================
 * ⚠️ 这个测试最要紧的一条是 **Y 轴方向**
 * ============================================================
 * [com.something.sthkey.capture.StickState] 里记着一次真实的翻车:
 *
 * > ⚠️ **这里踩过坑**:第一版文件写的是"Y 向上为正"，渲染层于是按那个约定
 * > 取了反 —— 结果**两个摇杆的 Y 都反了**（往上推、拇指往下走）。
 *
 * ⚠️ 而"按 W 帽子往上跑"这件事**只有真机上按一下才看得出来**，
 * 写错的人（包括我）在代码里看 `s - w` 是"对的"。所以必须用测试钉住符号。
 */
class KeyboardJoystickVectorTest {

    private fun vector(vararg codes: Int): Pair<Float, Float> =
        keyboardJoystickVector(codes.toSet())

    // ---------- 四个正方向 ----------

    @Test
    fun `按 W 是往上（Y 为负）`() {
        val (x, y) = vector(KeyCodes.KEY_W)
        assertEquals(0f, x, 1e-4f)
        assertEquals("⚠️ 上 = Y 负（屏幕坐标，下为正）", -0.5f, y, 1e-4f)
    }

    @Test
    fun `按 S 是往下（Y 为正）`() {
        val (x, y) = vector(KeyCodes.KEY_S)
        assertEquals(0f, x, 1e-4f)
        assertEquals(0.5f, y, 1e-4f)
    }

    @Test
    fun `按 A 是往左`() {
        val (x, y) = vector(KeyCodes.KEY_A)
        assertEquals(-0.5f, x, 1e-4f)
        assertEquals(0f, y, 1e-4f)
    }

    @Test
    fun `按 D 是往右`() {
        val (x, y) = vector(KeyCodes.KEY_D)
        assertEquals(0.5f, x, 1e-4f)
        assertEquals(0f, y, 1e-4f)
    }

    // ---------- 斜向 ----------

    @Test
    fun `W 加 D 是右上`() {
        val (x, y) = vector(KeyCodes.KEY_W, KeyCodes.KEY_D)
        assertTrue("右上应该 X 正、Y 负", x > 0f && y < 0f)
    }

    @Test
    fun `W 加 A 是左上`() {
        val (x, y) = vector(KeyCodes.KEY_W, KeyCodes.KEY_A)
        assertTrue("左上应该 X 负、Y 负", x < 0f && y < 0f)
    }

    @Test
    fun `S 加 A 是左下`() {
        val (x, y) = vector(KeyCodes.KEY_S, KeyCodes.KEY_A)
        assertTrue("左下应该 X 负、Y 正", x < 0f && y > 0f)
    }

    @Test
    fun `S 加 D 是右下`() {
        val (x, y) = vector(KeyCodes.KEY_S, KeyCodes.KEY_D)
        assertTrue("右下应该 X 正、Y 正", x > 0f && y > 0f)
    }

    // ---------- 抵消 ----------

    @Test
    fun `相对的键互相抵消`() {
        assertEquals(0f to 0f, vector(KeyCodes.KEY_W, KeyCodes.KEY_S))
        assertEquals(0f to 0f, vector(KeyCodes.KEY_A, KeyCodes.KEY_D))
        /* 四个全按也是 0 */
        assertEquals(
            0f to 0f,
            vector(KeyCodes.KEY_W, KeyCodes.KEY_A, KeyCodes.KEY_S, KeyCodes.KEY_D),
        )
    }

    @Test
    fun `没按键就是 0`() {
        assertEquals(0f to 0f, keyboardJoystickVector(emptySet()))
        /* 按的是别的键（不是 WASD）也一样 */
        assertEquals(0f to 0f, keyboardJoystickVector(setOf(KeyCodes.KEY_SPACE)))
    }

    // ---------- 长度 ----------

    /**
     * ⚠️ 斜向**必须归一化**。
     *
     * 不归一的话 `(1,1)` 的长度是 `1.414` —— 斜推比直推"推得更远"，
     * 帽子会跑出底盘（与 `StickState.leftMagnitude` 里那个 `min(1f, hypot)`
     * 是同一个问题的两种防法）。
     *
     * ⚠️ 这里断言的是"斜向长度 == 正向长度"（都等于阈值），
     * 而不是写死 0.707 —— 后者会让阈值语义变了之后测试跟着一起错。
     */
    @Test
    fun `斜向与正向长度相同（已归一化）`() {
        val (fx, _) = vector(KeyCodes.KEY_D)
        val forward = hypot(fx, 0f)

        val (dx, dy) = vector(KeyCodes.KEY_W, KeyCodes.KEY_D)
        val diagonal = hypot(dx, dy)

        assertEquals("斜向长度必须与正向相等（归一化）", forward, diagonal, 1e-4f)
    }

    @Test
    fun `阈值决定长度`() {
        val (x, _) = keyboardJoystickVector(setOf(KeyCodes.KEY_D), threshold = 1f)
        assertEquals("阈值 1 = 推到底", 1f, x, 1e-4f)
    }

    /**
     * ⚠️ 阈值再小也**不能变成 0** —— 那会让摇杆完全不动
     * （一个"按了没反应"的组件，用户会以为坏了）。
     */
    @Test
    fun `阈值再小方向也还在`() {
        val (x, y) = keyboardJoystickVector(setOf(KeyCodes.KEY_D), threshold = 0f)
        assertTrue("方向必须保留，长度不能为 0", x > 0f)
        assertEquals(0f, y, 1e-4f)
    }

    // ---------- 键位可配置（不能写死 WASD） ----------

    /**
     * ⚠️ 用户的原话:"因为这**不能写死**，有的用户可能会用别的按键"。
     *
     * 换一套键位之后，方向必须跟着换 —— 而不是"还是认 WASD"。
     *
     * ⚠️ 键位表的形状是**每一向一组键**（`List<List<Int>>`）。
     */
    @Test
    fun `换一套键位之后按新键才动`() {
        /* 随便四个不同的码，每向一个键 */
        val custom = listOf(listOf(36), listOf(38), listOf(23), listOf(37))

        val up = keyboardJoystickVector(setOf(36), keys = custom)
        assertTrue("按第 1 组键应该朝上（Y 负）", up.second < 0f)

        val right = keyboardJoystickVector(setOf(37), keys = custom)
        assertTrue("按第 4 组键应该朝右（X 正）", right.first > 0f)

        /* ⚠️ 原来的 WASD 不该再触发它 */
        assertEquals(0f to 0f, keyboardJoystickVector(setOf(KeyCodes.KEY_W), keys = custom))
    }

    /**
     * ⚠️⚠️ **同一向绑多个键时，任一个按下都算这一向**。
     *
     * 这就是"多选"的语义 —— 例如左右 Ctrl 是两个不同的键码，
     * 两个都绑上就都能触发同一向。
     *
     * ⚠️ 我上一版数据模型里每一向只有一个键，与多选选择器**对不上**
     * （用户的原话:"内部是多选外部是单选，你统一多选不行吗"）。
     */
    @Test
    fun `同一向绑多个键时任一个都触发`() {
        val multi = listOf(
            listOf(KeyCodes.KEY_W, 100),        // 上：W 或 100
            listOf(KeyCodes.KEY_A),
            listOf(KeyCodes.KEY_S),
            listOf(KeyCodes.KEY_D, 101, 102),   // 右：D 或 101 或 102
        )

        /* 上:两个绑的键各自都能触发 */
        assertTrue(keyboardJoystickVector(setOf(KeyCodes.KEY_W), keys = multi).second < 0f)
        assertTrue("绑的第二个键也该触发", keyboardJoystickVector(setOf(100), keys = multi).second < 0f)

        /* 右:三个绑的键各自都能触发 */
        assertTrue(keyboardJoystickVector(setOf(KeyCodes.KEY_D), keys = multi).first > 0f)
        assertTrue(keyboardJoystickVector(setOf(101), keys = multi).first > 0f)
        assertTrue(keyboardJoystickVector(setOf(102), keys = multi).first > 0f)

        /* 没绑的键不触发 */
        assertEquals(0f to 0f, keyboardJoystickVector(setOf(999), keys = multi))
    }

    /**
     * ⚠️ 某一向绑了**空组**（用户清空了那一向）= 那一向永远不触发，
     * 但**不该让其它向错位**（外层顺序是靠下标定位的）。
     */
    @Test
    fun `空组只让那一向不触发`() {
        val withEmpty = listOf(
            emptyList(),                        // 上：没绑
            listOf(KeyCodes.KEY_A),
            listOf(KeyCodes.KEY_S),
            listOf(KeyCodes.KEY_D),
        )

        assertEquals("空组应该不触发", 0f to 0f, keyboardJoystickVector(setOf(KeyCodes.KEY_W), keys = withEmpty))
        assertTrue("其它向不受影响", keyboardJoystickVector(setOf(KeyCodes.KEY_A), keys = withEmpty).first < 0f)
        assertTrue(keyboardJoystickVector(setOf(KeyCodes.KEY_S), keys = withEmpty).second > 0f)
        assertTrue(keyboardJoystickVector(setOf(KeyCodes.KEY_D), keys = withEmpty).first > 0f)
    }

    /**
     * ⚠️ 残缺的键位表（老配置、手改过的 JSON）不能让渲染崩。
     *
     * 缺的那一向就当"没绑键"（永远不触发），而不是抛
     * `IndexOutOfBoundsException` —— 与按键组件"没绑定就不亮"同一个取舍。
     */
    @Test
    fun `键位表残缺时不崩且缺失方向不触发`() {
        val partial = listOf(listOf(KeyCodes.KEY_W), listOf(KeyCodes.KEY_A))

        /* 有绑的两向正常 */
        assertTrue(keyboardJoystickVector(setOf(KeyCodes.KEY_W), keys = partial).second < 0f)
        assertTrue(keyboardJoystickVector(setOf(KeyCodes.KEY_A), keys = partial).first < 0f)

        /* 没绑的两向不触发（不会崩） */
        assertEquals(0f to 0f, keyboardJoystickVector(setOf(KeyCodes.KEY_S), keys = partial))
        assertEquals(0f to 0f, keyboardJoystickVector(setOf(KeyCodes.KEY_D), keys = partial))
    }
}

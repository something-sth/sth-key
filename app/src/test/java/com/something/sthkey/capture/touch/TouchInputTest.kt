package com.something.sthkey.capture.touch

import android.view.Surface
import com.something.sthkey.domain.config.TouchRegion
import com.something.sthkey.domain.config.TouchRegionSpec
import com.something.sthkey.domain.config.TouchSettings
import com.something.sthkey.domain.config.TouchStickFeel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 触屏输入算法:[TouchInput] 的纯函数部分。
 *
 * ============================================================
 * ⚠️ 这里保的是几件"看起来对、实际很容易错"的事
 * ============================================================
 * | 断言 | 防的是什么 |
 * |---|---|
 * | 短边归一化 | 横屏下区域"看起来比竖屏窄一半" |
 * | **中心用整屏归一化** | 区域只能放到屏幕一半 → **左撇子/右手摇杆放不过去** |
 * | 圆形死区 | 斜向漏判（`(0.09,0.09)` 每个分量都没超但已经推远了） |
 * | **夹到单位圆** | 斜着推比正着推**更远**（`√2` 倍），摇杆帽画到框外 |
 * | 边界取等号 | 按在轮盘边缘的那一下被判成"没落在区域"→ 视角乱转 |
 * | 新触点不产生增量 | 手指刚按下就被算成一大段位移，视角**猛地一甩** |
 *
 * ============================================================
 * ⚠️ 大部分断言用**正方形屏幕**（1000×1000）
 * ============================================================
 * 那时"整屏归一化"与"较短边归一化"**恰好数值相同**（短边 = 宽 = 高），
 * 于是这组测试专注于**算法本身**（死区、夹圆、方向）。
 *
 * ⚠️ 而两个单位是否会混用，由最后那组**横屏测试**专门抓 ——
 * 那才是"区域只能放屏幕一半"那个 bug 的现场。
 */
class TouchInputTest {

    private val eps = 1e-4f

    /** 正方形屏幕，两个坐标系重合 —— 见类注释 */
    private val sq = 1000

    /* 一块边长 0.3、中心在屏幕正中的区域 */
    private val region = TouchRegion(centerX = 0.5f, centerY = 0.5f, size = 0.3f)

    // ---------- 短边归一化 ----------

    /**
     * ⚠️ 竖屏时短边就是宽，X 不变；Y 要乘以 `高/宽`（2400/1080 ≈ 2.22）。
     *
     * 因为"短边"是 1 个单位，而屏幕的高有 2.22 个短边那么长。
     */
    @Test
    fun `竖屏下短边归一化`() {
        val (x, y) = TouchInput.toShortEdge(0.5f, 0.5f, 1080, 2400)
        assertEquals("X 不该变（短边就是宽）", 0.5f, x, eps)
        assertEquals("Y 要乘 高/宽", 0.5f * 2400f / 1080f, y, eps)
    }

    /**
     * ⚠️ 横屏时**短边是高度** —— 这一条是短边归一化的理由。
     *
     * 不这么算的话，同一块标着 `0.3` 的区域在横屏下会变成
     * "0.3 个屏宽"= 0.67 个短边 —— **看起来大了一倍多**。
     */
    @Test
    fun `横屏下短边归一化`() {
        val (x, y) = TouchInput.toShortEdge(0.5f, 0.5f, 2400, 1080)
        assertEquals("横屏时 X 要乘 宽/高", 0.5f * 2400f / 1080f, x, eps)
        assertEquals("横屏时短边是高度，Y 不变", 0.5f, y, eps)
    }

    @Test
    fun `正方形屏幕不缩放`() {
        val (x, y) = TouchInput.toShortEdge(0.4f, 0.6f, sq, sq)
        assertEquals(0.4f, x, eps)
        assertEquals(0.6f, y, eps)
    }

    @Test
    fun `尺寸为零不崩`() {
        val (x, y) = TouchInput.toShortEdge(0.5f, 0.5f, 0, 0)
        assertFalse("不该出 NaN", x.isNaN() || y.isNaN())
    }

    // ---------- 命中测试 ----------

    /**
     * ⚠️⚠️ **中心是整屏归一化** —— 这一条专抓"区域被限制在屏幕一半"。
     *
     * 横屏 2400×1080，中心 X 给 `0.9`（屏幕**右侧**）。
     * 若实现把中心当成"短边单位"（短边 = 1080），`0.9 × 1080 = 972` 像素
     * 只到屏幕中部偏左一点；而用户要的是**屏幕 90% 处**（2160 像素）。
     *
     * ⚠️ 这里断言的是"屏幕 90% 处那个点被判定在区域里" ——
     * 单位用错时它会在区域外。
     */
    @Test
    fun `横屏下区域能放到屏幕最右侧`() {
        val region = TouchRegion(centerX = 0.9f, centerY = 0.5f, size = 0.2f)
        /* 屏幕 90% 处、垂直居中 —— 这正是区域中心，必然在区域内 */
        assertTrue(
            "屏幕 90% 处的点必须在区域内（否则中心被当成短边单位了）",
            TouchInput.regionContains(region, 0.9f to 0.5f, 2400, 1080),
        )
        /* 屏幕 50% 处应当在区域外（区域只有 0.2 个短边 = 216px 宽） */
        assertFalse(
            "屏幕中间不该落在这块区域里",
            TouchInput.regionContains(region, 0.5f to 0.5f, 2400, 1080),
        )
    }

    /** 竖屏下同样能放到最下方（另一个方向也要能到边） */
    @Test
    fun `竖屏下区域能放到屏幕最下方`() {
        val region = TouchRegion(centerX = 0.5f, centerY = 0.92f, size = 0.2f)
        assertTrue(
            "屏幕 92% 高度处的点必须在区域内",
            TouchInput.regionContains(region, 0.5f to 0.92f, 1080, 2400),
        )
    }

    @Test
    fun `区域中心与四角`() {
        assertTrue("中心当然在里面", TouchInput.regionContains(region, 0.5f to 0.5f, sq, sq))
        /* 边长 0.3、正方形屏幕 → 半边长 0.15 → 范围 [0.35, 0.65] */
        assertTrue("左上角在内", TouchInput.regionContains(region, 0.35f to 0.35f, sq, sq))
        assertTrue("右下角在内", TouchInput.regionContains(region, 0.65f to 0.65f, sq, sq))
        assertTrue("恰好在边界上也算在内", TouchInput.regionContains(region, 0.35f to 0.5f, sq, sq))
        assertFalse("外面一点就不算", TouchInput.regionContains(region, 0.34f to 0.5f, sq, sq))
        assertFalse("X 在内但 Y 在外也不算", TouchInput.regionContains(region, 0.5f to 0.66f, sq, sq))
    }

    // ---------- 移动轮盘向量 ----------

    /** 正方形屏幕下中心与触点换算后仍是自己 */
    private fun center() = TouchInput.centerToShortEdge(region, sq, sq)

    @Test
    fun `中心位置没有偏移`() {
        val (x, y) = TouchInput.moveVector(
            region,
            0.5f to 0.5f,
            center(),
            TouchStickFeel(deadZone = 0f),
        )
        assertEquals(0f, x, eps)
        assertEquals(0f, y, eps)
    }

    @Test
    fun `四个正方向`() {
        val feel = TouchStickFeel(deadZone = 0f, sensitivity = 1f)
        /* 半边长 0.15，所以推到边缘（中心 ± 0.15）= 满偏 */
        assertEquals("右", 1f, TouchInput.moveVector(region, 0.65f to 0.5f, center(), feel).first, eps)
        assertEquals("左", -1f, TouchInput.moveVector(region, 0.35f to 0.5f, center(), feel).first, eps)
        assertEquals("下（Y 正）", 1f, TouchInput.moveVector(region, 0.5f to 0.65f, center(), feel).second, eps)
        assertEquals("上（Y 负）", -1f, TouchInput.moveVector(region, 0.5f to 0.35f, center(), feel).second, eps)
    }

    /** ⚠️ 死区之内一律归零（防手抖） */
    @Test
    fun `死区之内归零`() {
        val feel = TouchStickFeel(deadZone = 0.2f, sensitivity = 1f)
        assertEquals(0f to 0f, TouchInput.moveVector(region, 0.52f to 0.5f, center(), feel))
        val (x, _) = TouchInput.moveVector(region, 0.56f to 0.5f, center(), feel)
        assertTrue("超过死区应该动", x > 0f)
    }

    /**
     * ⚠️⚠️ **死区必须是圆形的**。
     *
     * 若按分量判（x 与 y 都 < 死区就归零），斜向会被误判成"没动"。
     */
    @Test
    fun `斜向死区按模长算而不是按分量`() {
        val feel = TouchStickFeel(deadZone = 0.2f, sensitivity = 1f)
        /* 归一化后 (1,1)，模长 √2 —— 远超死区 */
        val (x, y) = TouchInput.moveVector(region, 0.65f to 0.65f, center(), feel)
        assertTrue("斜向不该被判成死区", x > 0f && y > 0f)
    }

    /**
     * ⚠️⚠️ **必须夹到单位圆内**。
     *
     * 区域是**正方形**，推到角落时归一化偏移是 `(1, 1)`，模长 `√2`。
     * 分别夹 x/y 的话输出就是 `(1, 1)` —— 下游按模长用时（摇杆帽的偏移量）
     * 会得到 1.41 倍，**斜着推比正着推更远**，帽子被画到框外。
     */
    @Test
    fun `斜向被夹到单位圆内`() {
        val feel = TouchStickFeel(deadZone = 0f, sensitivity = 1f)
        val (x, y) = TouchInput.moveVector(region, 0.65f to 0.65f, center(), feel)
        val magnitude = kotlin.math.hypot(x, y)
        assertTrue("模长不该超过 1（实际 $magnitude）", magnitude <= 1f + eps)
        assertEquals("应该恰好夹到圆上", 1f, magnitude, 1e-3f)
        assertEquals("方向该保持 45°", x, y, eps)
    }

    @Test
    fun `高灵敏度也不越界`() {
        val feel = TouchStickFeel(deadZone = 0f, sensitivity = 4f)
        val (x, y) = TouchInput.moveVector(region, 0.65f to 0.5f, center(), feel)
        assertEquals("推到边缘、灵敏度 4 → 仍然只是满偏", 1f, x, eps)
        assertEquals(0f, y, eps)
    }

    /**
     * ⚠️⚠️ **横屏下推"右"与推"上"的手感必须一致**。
     *
     * 这是"两个单位混用"最直接的后果:若触点用短边归一化、
     * 而中心没有（或反过来），横屏下横向偏移会被多乘 `屏宽/短边 ≈ 2.2` ——
     * 表现为"往右推一点点就满偏，往上推要推到底"。
     *
     * ⚠️ 这里在横屏 2400×1080 上，把手指从中心往右移 `0.1 × 短边` 像素、
     * 以及往上移同样像素，两者得到的输出**必须相同**。
     */
    @Test
    fun `横屏下两个方向的灵敏度一致`() {
        val w = 2400
        val h = 1080
        val short = 1080f
        val r = TouchRegion(centerX = 0.5f, centerY = 0.5f, size = 0.4f)
        val c = TouchInput.centerToShortEdge(r, w, h)
        val feel = TouchStickFeel(deadZone = 0f, sensitivity = 1f)

        /* 往右移 0.1 个短边的像素 → 换算成整屏归一化就是 0.1×1080/2400 */
        val dxScreen = 0.1f * short / w
        /* 往上移同样的像素 → 0.1×1080/1080 */
        val dyScreen = 0.1f * short / h

        val right = TouchInput.moveVector(
            r,
            TouchInput.toShortEdge(0.5f + dxScreen, 0.5f, w, h),
            c,
            feel,
        )
        val up = TouchInput.moveVector(
            r,
            TouchInput.toShortEdge(0.5f, 0.5f - dyScreen, w, h),
            c,
            feel,
        )

        assertEquals(
            "横屏下推右与推上的输出必须相同（实际 右=${right.first} 上=${-up.second}）",
            right.first,
            -up.second,
            1e-3f,
        )
    }

    // ---------- 视角摇杆增量 ----------

    /**
     * ⚠️⚠️ **新出现的触点不产生增量** —— 这是最容易漏的一条。
     *
     * 不处理的话:手指按下那一瞬间，"上一帧没有它"会被当成
     * "它从 (0,0) 滑过来"，于是视角**猛地一甩**。
     */
    @Test
    fun `新触点不产生增量`() {
        val delta = TouchInput.cameraDelta(
            previous = emptyMap(),
            current = mapOf(7 to (0.6f to 0.4f)),
            pointsInMoveRegion = emptySet(),
        )
        assertEquals("新按下不该有位移", 0f to 0f, delta)
    }

    @Test
    fun `抬起的触点不产生增量`() {
        val delta = TouchInput.cameraDelta(
            previous = mapOf(7 to (0.6f to 0.4f)),
            current = emptyMap(),
            pointsInMoveRegion = emptySet(),
        )
        assertEquals(0f to 0f, delta)
    }

    @Test
    fun `已有的触点产生真实增量`() {
        val delta = TouchInput.cameraDelta(
            previous = mapOf(7 to (0.5f to 0.5f)),
            current = mapOf(7 to (0.6f to 0.45f)),
            pointsInMoveRegion = emptySet(),
        )
        assertEquals("向右滑 0.1", 0.1f, delta.first, eps)
        assertEquals("向上滑（Y 变小）", -0.05f, delta.second, eps)
    }

    /** ⚠️ 落在移动轮盘里的手指**不参与**视角 —— 否则推轮盘会同时转视角 */
    @Test
    fun `轮盘上的手指不转视角`() {
        val delta = TouchInput.cameraDelta(
            previous = mapOf(7 to (0.5f to 0.5f), 8 to (0.2f to 0.2f)),
            current = mapOf(7 to (0.6f to 0.5f), 8 to (0.25f to 0.2f)),
            pointsInMoveRegion = setOf(8),
        )
        assertEquals("只有 7 号的位移生效", 0.1f, delta.first, eps)
        assertEquals(0f, delta.second, eps)
    }

    @Test
    fun `多指增量相加`() {
        val delta = TouchInput.cameraDelta(
            previous = mapOf(1 to (0.2f to 0.2f), 2 to (0.6f to 0.6f)),
            current = mapOf(1 to (0.25f to 0.2f), 2 to (0.65f to 0.6f)),
            pointsInMoveRegion = emptySet(),
        )
        assertEquals(0.1f, delta.first, eps)
    }

    // ---------- 视角摇杆累加 ----------

    /**
     * ⚠️⚠️ **松手不回中**（用户明确要求）。
     *
     * 用户的原话:"松手后不做回中，因为很难写，鼠标摇杆写过回中
     * 但是 bug 一大堆后面删了"。
     */
    @Test
    fun `零增量保持原值不回中`() {
        val feel = TouchStickFeel(sensitivity = 1f)
        val (x, y) = TouchInput.accumulateCamera(0.5f to -0.3f, 0f to 0f, feel)
        assertEquals("没有增量就该原地不动", 0.5f, x, eps)
        assertEquals(-0.3f, y, eps)
    }

    @Test
    fun `累加并夹到正负一`() {
        val feel = TouchStickFeel(sensitivity = 1f)
        assertEquals(1f, TouchInput.accumulateCamera(0.9f to 0f, 0.5f to 0f, feel).first, eps)
        assertEquals(-1f, TouchInput.accumulateCamera(-0.9f to 0f, -0.5f to 0f, feel).first, eps)
    }

    /**
     * ⚠️ **顶到边界之后反向能立刻拉回来** —— 用户明确要求的行为。
     *
     * 夹的是"值"不是"速度"，所以不存在"顶住之后要先泄掉多余量"。
     */
    @Test
    fun `顶边后反向立刻回来`() {
        val feel = TouchStickFeel(sensitivity = 1f)
        val (x, _) = TouchInput.accumulateCamera(1f to 0f, -0.3f to 0f, feel)
        assertEquals("从满偏往回收 0.3 就该是 0.7", 0.7f, x, eps)
    }

    @Test
    fun `灵敏度放大增量`() {
        val (x, _) = TouchInput.accumulateCamera(0f to 0f, 0.1f to 0f, TouchStickFeel(sensitivity = 2f))
        assertEquals(0.2f, x, eps)
    }

    // ---------- 取值范围的夹取 ----------

    /**
     * ⚠️⚠️ **中心允许在整整一个屏幕范围内**（`0..1`）。
     *
     * 这是我第一版写错的地方:当时 `CENTER_MIN/MAX` 是 `0.05..0.95`
     * 且中心按短边算 —— 两者一起把区域挤在屏幕一半里。
     *
     * ⚠️ 这条断言钉住"范围就是 0..1"，防止以后有人又"为了整齐"收窄它。
     */
    @Test
    fun `中心范围就是整整一个屏幕`() {
        assertEquals("下限必须是 0", 0f, TouchRegionSpec.CENTER_MIN, eps)
        assertEquals("上限必须是 1", 1f, TouchRegionSpec.CENTER_MAX, eps)

        /* 右上角、左下角都要能放 */
        assertEquals(
            1f,
            TouchRegionSpec.clamp(TouchRegion(1.5f, 1.5f, 0.3f)).centerX,
            eps,
        )
        assertEquals(
            0f,
            TouchRegionSpec.clamp(TouchRegion(-0.5f, -0.5f, 0.3f)).centerX,
            eps,
        )
    }

    /** 边长仍然夹在合法范围里 */
    @Test
    fun `边长被夹住`() {
        assertEquals(
            TouchRegionSpec.SIZE_MAX,
            TouchRegionSpec.clamp(TouchRegion(0.5f, 0.5f, 99f)).size,
            eps,
        )
        assertEquals(
            TouchRegionSpec.SIZE_MIN,
            TouchRegionSpec.clamp(TouchRegion(0.5f, 0.5f, -1f)).size,
            eps,
        )
    }

    /** 按键区域的默认中心必须**都在屏幕内**（否则用户一打开就看不到方块） */
    @Test
    fun `按键区域默认中心都在屏幕内`() {
        repeat(12) { index ->
            val (x, y) = TouchRegionSpec.defaultCenterFor(index)
            assertTrue("第 $index 个默认中心 X=$x 出屏了", x in 0f..1f)
            assertTrue("第 $index 个默认中心 Y=$y 出屏了", y in 0f..1f)
        }
    }

    /** 轮盘的虚拟槽位 id 不能与任何真实槽位撞车 */
    @Test
    fun `轮盘槽位 id 是特殊的`() {
        assertFalse(
            "轮盘槽位 id 不该长得像真实槽位（真实 id 都是大写下划线）",
            TouchSettings.JOYSTICK_SLOT_ID.all { it.isUpperCase() || it == '_' },
        )
    }
}

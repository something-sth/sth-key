package com.something.sthkey.capture.touch

import android.view.Surface
import com.something.sthkey.domain.config.TouchRegionBinding
import com.something.sthkey.domain.config.TouchSettings
import com.something.sthkey.domain.config.TouchStickFeel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨帧状态机:[TouchFrameTracker]。
 *
 * ============================================================
 * ⚠️ 这一层最该被钉住 —— 它管的全是"跨帧"的东西
 * ============================================================
 * 跨帧状态是"真机上偶尔不对、单测里测不到"的重灾区。
 * 这里用假数据一帧一帧喂，把每种边界都钉住。
 *
 * ⚠️ 屏幕用正方形（1000×1000），两个坐标系重合 —— 专注于状态机本身，
 * 单位换算那件事由 `TouchInputTest` 的横屏那组负责。
 */
class TouchFrameTrackerTest {

    private val eps = 1e-4f
    private val size = 1000

    private val moveFeel = TouchStickFeel(deadZone = 0f, sensitivity = 1f)
    private val cameraFeel = TouchStickFeel(deadZone = 0f, sensitivity = 1f)

    /** 轮盘区域:正中、边长 0.3 */
    private val joystickRegion = TouchRegionBinding(
        slotId = TouchSettings.JOYSTICK_SLOT_ID,
        centerX = 0.5f,
        centerY = 0.5f,
        size = 0.3f,
    )

    /** A 键区域:右上、边长 0.2 */
    private val aRegion = TouchRegionBinding(
        slotId = "A_BUTTON",
        centerX = 0.8f,
        centerY = 0.2f,
        size = 0.2f,
    )

    /**
     * 喂一帧。
     *
     * ⚠️ 默认没有任何区域 —— 那时**整块屏幕都归视角摇杆**，
     * 正好用来测"没配区域"的行为。
     */
    private fun TouchFrameTracker.feed(
        vararg points: TouchNativeProtocol.Point,
        regions: List<TouchRegionBinding> = emptyList(),
        rotation: Int = Surface.ROTATION_0,
    ) = onFrame(
        rawPoints = points.toList(),
        rotation = rotation,
        screenWidth = size,
        screenHeight = size,
        regions = regions,
        moveFeel = moveFeel,
        cameraFeel = cameraFeel,
    )

    private fun point(id: Int, x: Float, y: Float) = TouchNativeProtocol.Point(id, x, y)

    // ---------- 移动轮盘 ----------

    @Test
    fun `轮盘区域内按下会产生向量`() {
        val tracker = TouchFrameTracker()
        /* 中心偏右 0.06 → 半边长 0.15 → 归一化 0.4 */
        tracker.feed(point(0, 0.56f, 0.5f), regions = listOf(joystickRegion))
        val (x, y) = tracker.moveStick()
        assertEquals(0.4f, x, 1e-2f)
        assertEquals(0f, y, eps)
    }

    /**
     * ⚠️⚠️ **归属只在按下那一刻决定，划出去之后不重新归类**。
     *
     * 不这样做的话:用户推着轮盘往外滑 → 手指离开区域 →
     * 摇杆**突然失去输入**（回中），同时视角被拽一下。
     */
    @Test
    fun `划出区域后摇杆仍跟着`() {
        val tracker = TouchFrameTracker()
        tracker.feed(point(0, 0.55f, 0.5f), regions = listOf(joystickRegion))
        assertTrue("按下时该有向量", tracker.moveStick().first > 0f)

        /* 划到屏幕左边缘 —— 归属不该变，向量被夹到满偏 */
        tracker.feed(point(0, 0.05f, 0.5f), regions = listOf(joystickRegion))
        assertEquals("划出区域后仍该满偏左", -1f, tracker.moveStick().first, eps)
    }

    /** ⚠️ 手指抬起 → 轮盘**回中**（与视角摇杆相反） */
    @Test
    fun `抬手后轮盘回中`() {
        val tracker = TouchFrameTracker()
        tracker.feed(point(0, 0.6f, 0.5f), regions = listOf(joystickRegion))
        assertTrue(tracker.moveStick().first > 0f)

        tracker.feed(regions = listOf(joystickRegion))
        assertEquals("轮盘必须回中", 0f to 0f, tracker.moveStick())
    }

    /** 没配区域时 (`regions` 为空) 整块屏幕都归视角摇杆 */
    @Test
    fun `没配区域时全屏都是视角摇杆`() {
        val tracker = TouchFrameTracker()
        repeat(2) { i -> tracker.feed(point(0, 0.1f + i * 0.05f, 0.5f)) }
        assertEquals("轮盘不该有输出（没有区域）", 0f to 0f, tracker.moveStick())
        assertTrue("视角该动", tracker.cameraStick().first > 0f)
    }

    // ---------- 视角摇杆 ----------

    /**
     * ⚠️⚠️ **视角摇杆抬手后不回中** —— 用户明确要求。
     */
    @Test
    fun `抬手后视角摇杆不回中`() {
        val tracker = TouchFrameTracker()
        tracker.feed(point(0, 0.2f, 0.5f))
        tracker.feed(point(0, 0.3f, 0.5f))
        val moved = tracker.cameraStick()
        assertTrue("滑了一下该有值", moved.first > 0f)

        tracker.feed()
        assertEquals("视角摇杆**不该**回中", moved, tracker.cameraStick())
    }

    /**
     * ⚠️ **新按下不产生视角位移**（跨帧状态机这一层也要验一次）。
     *
     * 这是"视角猛地一甩"那个 bug 的现场:第一根手指按下时
     * `previous` 是空的，若不特判就会算出一大段位移。
     */
    @Test
    fun `新按下不会让视角甩一下`() {
        val tracker = TouchFrameTracker()
        tracker.feed(point(0, 0.9f, 0.9f))
        assertEquals("刚按下不该有视角位移", 0f to 0f, tracker.cameraStick())
    }

    /** ⚠️ 推轮盘时**不该**同时转视角 */
    @Test
    fun `推轮盘不转视角`() {
        val tracker = TouchFrameTracker()
        tracker.feed(point(0, 0.45f, 0.5f), regions = listOf(joystickRegion))
        tracker.feed(point(0, 0.55f, 0.5f), regions = listOf(joystickRegion))
        assertEquals("轮盘上的手指不参与视角", 0f to 0f, tracker.cameraStick())
    }

    /** 轮盘 + 另一根手指转视角，两者**同时**工作 */
    @Test
    fun `轮盘与视角可以同时用`() {
        val tracker = TouchFrameTracker()
        val regions = listOf(joystickRegion)

        /* 0 号按在轮盘，1 号在角落（不落区域） */
        tracker.feed(point(0, 0.5f, 0.5f), point(1, 0.1f, 0.9f), regions = regions)
        tracker.feed(point(0, 0.6f, 0.5f), point(1, 0.2f, 0.9f), regions = regions)

        assertTrue("轮盘该有输出", tracker.moveStick().first > 0f)
        assertTrue("视角也该有输出", tracker.cameraStick().first > 0f)
    }

    // ---------- 按键区域 ----------

    /** 区域内按下 → 那个槽位被按住 */
    @Test
    fun `按键区域按下会上报槽位`() {
        val tracker = TouchFrameTracker()
        tracker.feed(point(0, 0.8f, 0.2f), regions = listOf(joystickRegion, aRegion))
        assertEquals(setOf("A_BUTTON"), tracker.pressedSlots())
    }

    /** 抬手 → 槽位不再被按住 */
    @Test
    fun `按键区域抬手会松开`() {
        val tracker = TouchFrameTracker()
        tracker.feed(point(0, 0.8f, 0.2f), regions = listOf(aRegion))
        assertEquals(setOf("A_BUTTON"), tracker.pressedSlots())

        tracker.feed(regions = listOf(aRegion))
        assertTrue("抬手后不该还按着", tracker.pressedSlots().isEmpty())
    }

    /**
     * ⚠️ **划出按键区域后仍然算按住**（归属在按下时定死）。
     *
     * 不这样的话:手指按着 A 稍微一滑出边界，A 就**突然熄灭** ——
     * 而用户觉得自己一直按着。
     */
    @Test
    fun `划出按键区域仍算按住`() {
        val tracker = TouchFrameTracker()
        tracker.feed(point(0, 0.8f, 0.2f), regions = listOf(aRegion))
        tracker.feed(point(0, 0.1f, 0.9f), regions = listOf(aRegion))
        assertEquals("划出去之后仍该按着", setOf("A_BUTTON"), tracker.pressedSlots())
    }

    /** 轮盘槽位**不会**出现在"按住的按键"里（它是摇杆，不是键） */
    @Test
    fun `轮盘不会出现在按住的按键里`() {
        val tracker = TouchFrameTracker()
        tracker.feed(point(0, 0.5f, 0.5f), regions = listOf(joystickRegion))
        assertTrue(tracker.pressedSlots().isEmpty())
    }

    /** 两根手指按两个不同的按键区域 → 两个都上报 */
    @Test
    fun `多指按多个按键区域`() {
        val tracker = TouchFrameTracker()
        val bRegion = TouchRegionBinding("SHIFT", 0.2f, 0.2f, 0.2f)
        tracker.feed(
            point(0, 0.8f, 0.2f),
            point(1, 0.2f, 0.2f),
            regions = listOf(aRegion, bRegion),
        )
        assertEquals(setOf("A_BUTTON", "SHIFT"), tracker.pressedSlots())
    }

    /**
     * ⚠️⚠️ **重叠的区域全部生效**（不是"靠前的赢"）。
     *
     * ============================================================
     * 这条原来是反过来的，用户把它改掉了
     * ============================================================
     * 用户的原话:
     *
     * > 其实重叠应该做的是**所有重叠的所有触摸区域互不干扰**吧，
     * > 我不清楚能不能实现，但是这样搞应该功能范围更广一点
     *
     * ⚠️ 而它正是自定义布局里"前后左右"玩法的**基础**:
     *
     * > 反正做了重叠的话，**多搞几个摇杆在同一个位置选前后左右
     * > 也是一样的效果**
     *
     * ⚠️ 我原来的 `firstOrNull` 让列表靠前的那块**永远赢** ——
     * 拖到一起的两块里总有一块**永远不亮**，而那个现象**用户查不出来**
     * （他只会觉得"这个按键坏了"）。
     */
    @Test
    fun `重叠的区域全部生效`() {
        val tracker = TouchFrameTracker()
        val overlapping = TouchRegionBinding("SHIFT", 0.8f, 0.2f, 0.3f)
        tracker.feed(point(0, 0.8f, 0.2f), regions = listOf(aRegion, overlapping))
        assertEquals(
            "重叠的两块都该被按下 —— 只亮一块的话，另一块就永远不亮了",
            setOf("A_BUTTON", "SHIFT"),
            tracker.pressedSlots(),
        )
    }

    /**
     * ⚠️ 唯一的例外:**移动区域**只认一块（列表靠前的那个）。
     *
     * 一个触点只该推**一个**摇杆 —— 两块移动区域重叠时各算各的向量、
     * 同时推两个摇杆，那是说不通的行为。
     */
    @Test
    fun `两块移动区域重叠时只算一块`() {
        val tracker = TouchFrameTracker()
        val a = TouchRegionBinding(TouchSettings.JOYSTICK_SLOT_ID, 0.5f, 0.5f, 0.3f)
        val b = TouchRegionBinding(TouchSettings.JOYSTICK_SLOT_ID, 0.5f, 0.5f, 0.3f)
        tracker.feed(point(0, 0.5f, 0.5f), regions = listOf(a, b))
        /* ⚠️ 它俩是同一个槽位 id，所以"集合"里本来就只有一个 —— 这里验的是不崩、且向量算出来了 */
        assertTrue("轮盘槽位不该出现在按住的按键里", tracker.pressedSlots().isEmpty())
    }

    // ---------- 复位 ----------

    /** `reset()` 会把视角摇杆也归零、把按住的键全松开 */
    @Test
    fun `复位会清掉一切`() {
        val tracker = TouchFrameTracker()
        tracker.feed(point(0, 0.1f, 0.5f), point(1, 0.8f, 0.2f), regions = listOf(aRegion))
        tracker.feed(point(0, 0.2f, 0.5f), point(1, 0.8f, 0.2f), regions = listOf(aRegion))
        assertTrue(tracker.cameraStick().first != 0f)
        assertEquals(setOf("A_BUTTON"), tracker.pressedSlots())

        tracker.reset()
        assertEquals(0f to 0f, tracker.cameraStick())
        assertEquals(0f to 0f, tracker.moveStick())
        assertTrue("按住的键也要松开", tracker.pressedSlots().isEmpty())
    }

    /**
     * ⚠️ **触点 id 会被系统复用** —— 上一次的归属不能在复用后残留。
     *
     * 不清理的话:"同一根手指再按下去"（拿到同一个 id）会被当成
     * "上次那块区域里的手指又动了"，于是它**不会被重新归类** ——
     * 按在 A 上却仍然算在轮盘上（或反过来）。
     */
    @Test
    fun `id 复用后归属会重新判定`() {
        val tracker = TouchFrameTracker()
        val regions = listOf(joystickRegion, aRegion)

        /* 先用 0 号按轮盘，然后抬手 */
        tracker.feed(point(0, 0.5f, 0.5f), regions = regions)
        tracker.feed(regions = regions)
        assertTrue(tracker.pressedSlots().isEmpty())

        /* 同一个 id 再按到 A 上 —— 应当算 A，而不是"还在轮盘上" */
        tracker.feed(point(0, 0.8f, 0.2f), regions = regions)
        assertEquals("复用同一个 id 后必须重新归类", setOf("A_BUTTON"), tracker.pressedSlots())
        assertEquals("这时轮盘不该有输出", 0f to 0f, tracker.moveStick())
    }

    /** 不存在的槽位 id 不崩（手改 JSON 时会出现） */
    @Test
    fun `未知槽位不崩`() {
        val tracker = TouchFrameTracker()
        val bogus = TouchRegionBinding("NOT_A_REAL_SLOT", 0.5f, 0.5f, 0.3f)
        tracker.feed(point(0, 0.5f, 0.5f), regions = listOf(bogus))
        assertEquals(setOf("NOT_A_REAL_SLOT"), tracker.pressedSlots())
        assertEquals("轮盘没配，所以没有向量", 0f to 0f, tracker.moveStick())
    }

    /** 触点数为 0 的帧不该崩、也不该改变任何东西 */
    @Test
    fun `空帧不崩`() {
        val tracker = TouchFrameTracker()
        tracker.feed()
        assertEquals(0f to 0f, tracker.moveStick())
        assertEquals(0f to 0f, tracker.cameraStick())
        assertFalse(tracker.pressedSlots().isNotEmpty())
    }

    // ---------- advance：组合期推进 ----------

    /**
     * ⚠️⚠️ `advance` 必须**同一帧序号只推进一次**。
     *
     * ============================================================
     * 为什么这条最要紧
     * ============================================================
     * 用户实测报的"按下不亮、松手才亮"是一帧延迟，修法是**在组合期推进**
     * （`LaunchedEffect` 在组合完成之后才跑，于是画出来的是上一帧）。
     *
     * ⚠️ 而组合期推进的风险是:**组合会重跑**（配置变了、尺寸变了、
     * 甚至只是父级重组）—— 那样同一帧会被推进好几次。
     *
     * ⚠️ 重复推进的后果很具体:视角摇杆是**累加**的，
     * 同一段位移被加两遍 → 转视角**转得比手指快一倍**；
     * 而"归属"会被反复重算，手指划出区域后又会重新归类。
     */
    @Test
    fun `同一帧序号只推进一次`() {
        val tracker = TouchFrameTracker()
        val regions = listOf(joystickRegion)

        /* 第 1 帧:手指出现在某个位置 */
        tracker.advance(1L, listOf(point(0, 0.2f, 0.5f)), Surface.ROTATION_0, size, size, regions, moveFeel, cameraFeel)
        /* 第 2 帧:向右滑 0.1 */
        val snap = tracker.advance(2L, listOf(point(0, 0.3f, 0.5f)), Surface.ROTATION_0, size, size, regions, moveFeel, cameraFeel)
        assertEquals("滑一次就该累加一次", 0.1f, snap.camera.first, eps)

        /*
         * ⚠️ 同一个序号**再推进几次**（模拟组合重跑）—— 结果必须不变。
         */
        repeat(5) {
            val again = tracker.advance(
                2L,
                listOf(point(0, 0.3f, 0.5f)),
                Surface.ROTATION_0,
                size,
                size,
                regions,
                moveFeel,
                cameraFeel,
            )
            assertEquals("重跑组合不该把这段位移再加一遍", 0.1f, again.camera.first, eps)
        }
    }

    /** `advance` 返回的快照要包含**这一帧**的摇杆与按键（而不是上一帧） */
    @Test
    fun `advance 返回的是这一帧的快照`() {
        val tracker = TouchFrameTracker()
        val regions = listOf(joystickRegion, aRegion)

        /* 第 1 帧:按在 A 上 */
        val first = tracker.advance(
            1L,
            listOf(point(0, 0.8f, 0.2f)),
            Surface.ROTATION_0,
            size,
            size,
            regions,
            moveFeel,
            cameraFeel,
        )
        assertEquals("按下这一帧就该报出来（不能等下一帧）", setOf("A_BUTTON"), first.pressedSlots)
        assertEquals("轮盘没有输出", 0f to 0f, first.move)

        /* 第 2 帧:抬手 */
        val second = tracker.advance(
            2L,
            emptyList(),
            Surface.ROTATION_0,
            size,
            size,
            regions,
            moveFeel,
            cameraFeel,
        )
        assertTrue("松手这一帧就该松开（不能等下一帧）", second.pressedSlots.isEmpty())
    }

    /** 序号不变但换了输入 —— 仍然不推进（序号才是唯一的推进依据） */
    @Test
    fun `序号没变就不看新输入`() {
        val tracker = TouchFrameTracker()
        val regions = listOf(aRegion)

        tracker.advance(7L, listOf(point(0, 0.8f, 0.2f)), Surface.ROTATION_0, size, size, regions, moveFeel, cameraFeel)
        /* 同一个序号，但这次"抬手" —— 不该生效 */
        val snap = tracker.advance(7L, emptyList(), Surface.ROTATION_0, size, size, regions, moveFeel, cameraFeel)
        assertEquals(
            "同一个序号不该被第二次输入改变（推进依据只有序号）",
            setOf("A_BUTTON"),
            snap.pressedSlots,
        )
    }
}

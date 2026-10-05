package com.something.sthkey.capture.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `getevent -i` 输出的解析 + 轴归一化。
 *
 * ============================================================
 * ⚠️ 样本是**实测**的,不是编的
 * ============================================================
 * 下面 [XBOX360_DUMP] 是 `Microsoft X-Box 360 pad` 在真机上
 * `su -c "/system/bin/getevent -i -t"` 的**原样输出**(只截了开头两台设备)。
 *
 * 自己编样本的话,测的是"我以为的格式" —— 而上一次
 * (`-lt` 那个 bug)已经证明:格式猜错的代价是**一个功能整个不工作**,
 * 而症状还会指向别的地方。
 *
 * ⚠️ 以后换手柄抓到不一样的输出,**把样本补到这里**再加断言,
 * 不要改代码去"顺手兼容"。
 */
class AxisNormalizerTest {

    /* ============================================================
     * 解析:设备能力
     * ============================================================ */

    @Test
    fun `解析 Xbox360 手柄的轴范围`() {
        val caps = DeviceCapabilitiesParser.parse(XBOX360_DUMP)

        val pad = caps["/dev/input/event7"]
        assertNotNull("应当解析出手柄那台设备", pad)
        requireNotNull(pad)

        assertEquals("Microsoft X-Box 360 pad", pad.name)
        assertFalse("手柄不是触摸屏", pad.direct)
        assertTrue("应当像手柄", pad.looksLikeGamepad)

        /* 左摇杆 X:min -32768, max 32767, fuzz 16, flat 128 */
        val lx = pad.axes[ABS_X]
        assertNotNull("ABS_X 应当被解析出来", lx)
        requireNotNull(lx)
        assertEquals(-32768, lx.min)
        assertEquals(32767, lx.max)
        assertEquals(16, lx.fuzz)
        assertEquals(128, lx.flat)

        /* 左扳机:范围完全不同,是 0..255 */
        val lt = pad.axes[ABS_Z]
        assertNotNull("ABS_Z 应当被解析出来", lt)
        requireNotNull(lt)
        assertEquals(0, lt.min)
        assertEquals(255, lt.max)

        /* 方向键:范围 -1..1 */
        val hat = pad.axes[ABS_HAT0X]
        assertNotNull("方向键的轴应当被解析出来", hat)
        requireNotNull(hat)
        assertEquals(-1, hat.min)
        assertEquals(1, hat.max)
    }

    /**
     * ⚠️ 轴信息行**第二行起没有 `ABS (0003):` 前缀**,只有缩进。
     *
     * 实测输出里 `0001`、`0002` … 都是这种"裸缩进"行。
     * 如果正则要求前缀,那么只有第一个轴会被解析出来 ——
     * 症状是"左摇杆能动、右摇杆不动",极难联想到解析。
     */
    @Test
    fun `续行没有 ABS 前缀也要能解析`() {
        val caps = DeviceCapabilitiesParser.parse(XBOX360_DUMP)
        val pad = caps.getValue("/dev/input/event7")

        assertEquals("8 个轴应当全部解析出来", 8, pad.axes.size)
        listOf(ABS_X, ABS_Y, ABS_Z, ABS_RX, ABS_RY, ABS_RZ, ABS_HAT0X, ABS_HAT0Y)
            .forEach { code ->
                assertNotNull("轴 $code 应当被解析出来", pad.axes[code])
            }
    }

    /**
     * ⚠️ 触摸屏必须被标成 `direct`。
     *
     * 实测里 `touchpanel` 也报 `ABS_X` / `ABS_Y`,但范围是 `0..20352` /
     * `0..44800` 的**屏幕坐标**。不排除的话**手指一碰屏幕,
     * 悬浮窗上的摇杆就会满偏**。
     */
    @Test
    fun `触摸屏被标记为 direct`() {
        val caps = DeviceCapabilitiesParser.parse(XBOX360_DUMP)

        val touch = caps["/dev/input/event4"]
        assertNotNull("触摸屏也应当被解析出来", touch)
        requireNotNull(touch)
        assertTrue("触摸屏应当带 INPUT_PROP_DIRECT", touch.direct)
        assertFalse("触摸屏不该被当成手柄", touch.looksLikeGamepad)
    }

    /** 耳机孔、电源键之类的设备没有 ABS,不该被当成手柄 */
    @Test
    fun `没有轴设备的不算手柄`() {
        val caps = DeviceCapabilitiesParser.parse(XBOX360_DUMP)

        listOf("/dev/input/event6", "/dev/input/event5", "/dev/input/event1")
            .forEach { path ->
                val device = caps[path]
                assertNotNull("$path 应当被解析出来", device)
                assertFalse("$path 不该被当成手柄", device!!.looksLikeGamepad)
            }
    }

    /**
     * ⚠️ 只有方向键(HAT)轴的设备**不算手柄**。
     *
     * 很多**手机自己**会报一对 HAT 轴(导航键)。只按 HAT 判断的话,
     * 手机本身会被误判成手柄 —— 于是"没插手柄也显示摇杆在动"。
     */
    @Test
    fun `只有 HAT 轴不算手柄`() {
        val onlyHat = """
            add device 1: /dev/input/event9
              name:     "gpio-keys"
              ABS (0003): 0010  : value 0, min -1, max 1, fuzz 0, flat 0, resolution 0
                          0011  : value 0, min -1, max 1, fuzz 0, flat 0, resolution 0
        """.trimIndent()

        val caps = DeviceCapabilitiesParser.parse(onlyHat)
        assertFalse(
            "只有 HAT 轴不该被当成手柄",
            caps.getValue("/dev/input/event9").looksLikeGamepad,
        )
    }

    /** 最后一个设备也要收尾,不能因为"没有下一个 add device"被丢掉 */
    @Test
    fun `最后一台设备不会被丢掉`() {
        val caps = DeviceCapabilitiesParser.parse(XBOX360_DUMP)
        assertNotNull(
            "dump 里最后一台是 mtk-pmic-keys,它必须也在结果里",
            caps["/dev/input/event2"],
        )
    }

    /* ============================================================
     * 归一化
     * ============================================================ */

    /** 实测的 Xbox 360 摇杆范围 */
    private val stick = AxisRange(min = -32768, max = 32767, fuzz = 16, flat = 128)

    @Test
    fun `摇杆推到底归一化成正负一`() {
        assertEquals(1f, AxisNormalizer.normalize(32767, stick), 0.001f)
        assertEquals(-1f, AxisNormalizer.normalize(-32768, stick), 0.001f)
    }

    @Test
    fun `摇杆在中点归一化成零`() {
        assertEquals(0f, AxisNormalizer.normalize(0, stick), 0.0001f)
        assertEquals(0f, AxisNormalizer.normalize(-1, stick), 0.0001f)
    }

    @Test
    fun `摇杆推一半约等于零点五`() {
        /* 16384 / 32768 = 0.5 */
        assertEquals(0.5f, AxisNormalizer.normalize(16384, stick), 0.001f)
    }

    /**
     * ⚠️ 死区边界上的值要被压成 0。
     *
     * 实测 `flat = 128`、半量程 `32768`,于是 `128 / 32768 = 0.00390625`
     * 正好落在死区上。用 `<` 而不是 `<=` 的话这个值会被放行 ——
     * 静止的摇杆就会一直输出一个极小的值。
     */
    @Test
    fun `静止时的抖动被死区压成零`() {
        assertEquals(0f, AxisNormalizer.normalize(128, stick), 0f)
        assertEquals(0f, AxisNormalizer.normalize(-128, stick), 0f)
        assertEquals(0f, AxisNormalizer.normalize(16, stick), 0f)
    }

    /**
     * ⚠️ 范围不对称时,中点**不是 0**,半量程也要取较大的一边。
     *
     * 这是扳机的情形:实测 `ABS_Z` 是 `0..255`,中点 127。
     * 不减中点的话"松开扳机"会被算成 `0.5` 的输入。
     */
    @Test
    fun `不对称范围(扳机)的中点是对的`() {
        val trigger = AxisRange(min = 0, max = 255, fuzz = 0, flat = 0)

        assertEquals("中点:127 应当归一化成 0", 0f, AxisNormalizer.normalize(127, trigger), 0.01f)
        assertEquals("最小值应当归一化成 -1", -1f, AxisNormalizer.normalize(0, trigger), 0.01f)
        assertEquals("最大值应当归一化成 1", 1f, AxisNormalizer.normalize(255, trigger), 0.01f)
    }

    /** 方向键轴 `-1..1`:三个取值应当干净地映射成 -1 / 0 / 1 */
    @Test
    fun `方向键轴只有三个取值`() {
        val hat = AxisRange(min = -1, max = 1, fuzz = 0, flat = 0)

        assertEquals(-1f, AxisNormalizer.normalize(-1, hat), 0.001f)
        assertEquals(0f, AxisNormalizer.normalize(0, hat), 0.001f)
        assertEquals(1f, AxisNormalizer.normalize(1, hat), 0.001f)
    }

    /**
     * ⚠️ 越界值必须被钳住。
     *
     * 兜住两类意外:把触摸屏的轴(实测 `0..44800`)当成摇杆、
     * 或者 ROM 报的范围与实际不符。钳制之后最坏是"摇杆满偏",
     * 而不是"画面炸掉"。
     */
    @Test
    fun `超出范围的值被钳到正负一`() {
        assertEquals(
            "假设它是摇杆,44800 这种触摸屏坐标也会被钳到 1",
            1f,
            AxisNormalizer.normalize(44800, stick),
            0.001f,
        )
        assertEquals(-1f, AxisNormalizer.normalize(-99999, stick), 0.001f)
    }

    /** 量程为 0 的占位轴(实测触摸屏里有一堆 `min 0, max 0`)不能参与计算 */
    @Test
    fun `量程为零的轴返回零`() {
        val empty = AxisRange(min = 0, max = 0, fuzz = 0, flat = 0)

        assertFalse("量程为 0 应当被判定为不可用", empty.isUsable)
        assertEquals(0f, AxisNormalizer.normalize(12345, empty), 0f)
    }

    /* ============================================================
     * 手感死区
     * ============================================================ */

    @Test
    fun `手感死区内的值归零`() {
        assertEquals(0f, AxisNormalizer.applyDeadZone(0.03f, 0.05f), 0f)
        assertEquals(0f, AxisNormalizer.applyDeadZone(-0.03f, 0.05f), 0f)
        assertEquals(0f, AxisNormalizer.applyDeadZone(0f, 0.05f), 0f)
    }

    /**
     * ⚠️ 死区之外要**重新拉满**,不是简单归零。
     *
     * 直接归零的话 1.0 仍然映射到 1.0,但 0.05 映射到 0.05 ——
     * 中间那段死行程会让拇指**永远到不了最外圈**,
     * 而那是用户最容易一眼看出来的问题("怎么推到底还差一截")。
     */
    @Test
    fun `死区之外重新映射到满量程`() {
        /* 0.05 是死区边界 → 0;1.0 是满量程 → 1 */
        assertEquals("死区边界映射成 0", 0f, AxisNormalizer.applyDeadZone(0.05f, 0.05f), 0.0001f)
        assertEquals("满量程仍是 1", 1f, AxisNormalizer.applyDeadZone(1f, 0.05f), 0.0001f)

        /* 中点 (1.0 + 0.05) / 2 = 0.525 应当映射到 0.5 */
        assertEquals(0.5f, AxisNormalizer.applyDeadZone(0.525f, 0.05f), 0.001f)
    }

    @Test
    fun `手感死区保留符号`() {
        assertEquals(-1f, AxisNormalizer.applyDeadZone(-1f, 0.05f), 0.0001f)
        assertTrue(
            "负值过了死区之后仍然是负的",
            AxisNormalizer.applyDeadZone(-0.5f, 0.05f) < 0f,
        )
    }

    /** 死区为 0 时等于不处理 */
    @Test
    fun `死区为零时不改变输入`() {
        assertEquals(0.5f, AxisNormalizer.applyDeadZone(0.5f, 0f), 0.0001f)
    }

    /* ============================================================
     * ⚠️ 事件过滤：触摸屏必须被挡掉
     * ============================================================
     */

    /**
     * ⚠️ **这条测试对应一个真的出过的 bug。**
     *
     * 触摸屏也报 `ABS_X` / `ABS_Y`（实测 `0..20352` / `0..44800` 的屏幕坐标）。
     * 第一版只在探测里把触摸屏标成 `direct`，却**没有真的用它过滤事件** ——
     * 后果有两层：
     *
     * 1. 手指碰屏幕时，屏幕中间 `ABS_X ≈ 10176` → 归一化约 0.5，
     *    **悬浮窗上的摇杆会莫名其妙偏到一边**，而用户想不到是摸屏幕引起的；
     * 2. 触摸上报频率远高于手柄，日志被刷爆。
     *
     * 所以"探测到了 direct"与"真的挡掉了"是两件事 —— 这条只验证过滤器
     * 本身，接线由 `GeteventStream` 的使用处保证（那条没法在本地单测）。
     */
    @Test
    fun `触摸屏的事件被过滤器挡掉`() {
        val caps = DeviceCapabilitiesParser.parse(XBOX360_DUMP)
        val filter = GeteventStream.deviceFilter { caps }

        assertFalse("触摸屏的事件必须被挡掉", filter(axisEvent(ABS_X, 10176, "/dev/input/event4")))
    }

    @Test
    fun `手柄的事件放行`() {
        val caps = DeviceCapabilitiesParser.parse(XBOX360_DUMP)
        val filter = GeteventStream.deviceFilter { caps }

        assertTrue("手柄的事件必须放行", filter(axisEvent(ABS_X, 32767, "/dev/input/event7")))
    }

    /**
     * ⚠️ **未知设备一律放行**。
     *
     * 热插拔进来的手柄不在能力表里（探测只在启动时跑一次）。
     * 拦掉的话它整台都没反应 —— 而"插上但没反应"很容易被当成手柄坏了。
     *
     * 触摸屏是开机就有的，所以"未知 = 放行"是安全的。
     */
    @Test
    fun `未探测到的设备放行`() {
        val caps = DeviceCapabilitiesParser.parse(XBOX360_DUMP)
        val filter = GeteventStream.deviceFilter { caps }

        assertTrue(
            "没探测到的设备要放行，否则热插拔的手柄没反应",
            filter(axisEvent(ABS_X, 12345, "/dev/input/event99")),
        )
    }

    /** 没有设备路径的事件（理论上不该出现）也要放行，不能因为查不到就丢 */
    @Test
    fun `没有设备路径的事件放行`() {
        val caps = DeviceCapabilitiesParser.parse(XBOX360_DUMP)
        val filter = GeteventStream.deviceFilter { caps }

        val noDevice = com.something.sthkey.capture.InputEvent(
            type = com.something.sthkey.capture.InputEvent.EV_KEY,
            code = 17,
            value = 1,
            device = null,
        )
        assertTrue("路径未知时保守放行", filter(noDevice))
    }

    /**
     * ⚠️ 过滤器必须**现取**能力表，不能把值抓在构造那一刻。
     *
     * 这是一条**回归测试**：第一版 `deviceFilter(capabilities)` 收的是
     * `Map`，而 root 那边构造过滤器的时机在探测完成之前 ——
     * 于是抓到一个**空表**，过滤器永远放行，触摸屏照样刷屏，
     * 而症状与"完全没写这个过滤器"一模一样，很难联想到是求值时机。
     *
     * 传函数则每次现取，与顺序无关。
     */
    @Test
    fun `过滤器在能力表后填充时也能生效`() {
        var caps: Map<String, DeviceCapabilities> = emptyMap()
        val filter = GeteventStream.deviceFilter { caps }
        val touchEvent = axisEvent(ABS_X, 10176, "/dev/input/event4")

        assertTrue("能力表还空着时保守放行", filter(touchEvent))

        /* 探测完成，能力表被填上 */
        caps = DeviceCapabilitiesParser.parse(XBOX360_DUMP)

        assertFalse("填上之后必须立刻生效", filter(touchEvent))
    }

    /** 造一条绝对轴事件，只为测试过滤器 */
    private fun axisEvent(
        code: Int,
        value: Int,
        device: String,
    ) = com.something.sthkey.capture.InputEvent(
        type = com.something.sthkey.capture.InputEvent.EV_ABS,
        code = code,
        value = value,
        device = device,
    )



    /**
     * ⚠️ **中点要信"读取那一刻的值"**（`calibratedCenter`）。
     *
     * `(min + max) / 2` 是**理论**中点，而硬件真实零点常常偏一点。
     * 用理论中点的话摇杆松手后不归零 —— 悬浮窗上的拇指**偏在一边**。
     *
     * `getevent -i` 打印轴信息时会顺带打印读取那一刻的值，
     * 设备静止时它就是真实零点。
     */
    @Test
    fun `读取值离中点近时当中点用`() {
        /* 理论中点 0（-32768..32767），读取值是 -400 —— 偏了约 1.2%，在容差内 */
        val range = AxisRange(min = -32768, max = 32767, fuzz = 0, flat = 0, value = -400)

        assertEquals(
            "读取值离中点很近时应当信它（硬件零点漂移）",
            -400f,
            AxisNormalizer.resolveCenter(range),
            0.01f,
        )
    }

    /**
     * ⚠️ 但**偏差大时要用理论中点** —— 那说明读取那一刻用户正推着摇杆。
     *
     * 误用"推着时的值"当零点的后果是**永久偏置**：
     * 摇杆松手后拇指停在一边，而用户会觉得摇杆坏了。
     * 理论中点至少"松手时大概在中间"。
     */
    @Test
    fun `读取值偏离中点太远时用理论中点`() {
        /* 读取值 20000，离中点 0 有 20000/32768 ≈ 61% —— 明显是推着摇杆 */
        val range = AxisRange(min = -32768, max = 32767, fuzz = 0, flat = 0, value = 20000)

        /*
         * ⚠️ 理论中点是 **-0.5** 而不是 0：
         * `(-32768 + 32767) / 2 = -0.5`。与
         * `(min + max) * 0.5` 是同一个值 —— 整数范围的"中点"本来就可能带小数。
         *
         * 不影响归一化：`normalize(0)` 会得到 `0.5 / 32767.5 ≈ 0.00002`，
         * 早就被死区压成 0 了。
         */
        assertEquals(
            "偏差超过容差时应当回落到理论中点",
            -0.5f,
            AxisNormalizer.resolveCenter(range),
            0.01f,
        )
    }

    /** 容差不能比内核报的死区还小 —— 否则死区外的抖动会被当成"推着摇杆" */
    @Test
    fun `容差至少是硬件死区的两倍`() {
        /*
         * flat 很大(1000)而量程很小(10000)：量程的 8% 是 800，
         * 比 flat*2 = 2000 小，所以容差应当是 2000。
         * 读取值 1500 离中点 0 有 1500 —— 在 2000 内，应当被信任。
         */
        val range = AxisRange(min = -5000, max = 5000, fuzz = 0, flat = 1000, value = 1500)

        assertEquals(1500f, AxisNormalizer.resolveCenter(range), 0.01f)
    }

    /**
     * ⚠️ 死区用**短边**归一。
     *
     * 对不对称的轴，两侧量程不同：扳机 `0..255`、中点 127
     * （正边 128、负边 127）。用**长边**除会**低估**死区。
     */
    @Test
    fun `死区按短边归一`() {
        /*
         * 不对称：min -100, max 300 → 中点 100，正边 200、负边 200（相等）
         * 换一组真的不对称的：min -100, max 300, 但 value 让中点偏移
         */
        val asymmetric = AxisRange(min = -100, max = 300, fuzz = 0, flat = 10, value = 100)

        /* 中点 100：正边 200、负边 200 —— 这组是对称的，换一个 */
        val lopsided = AxisRange(min = 0, max = 255, fuzz = 0, flat = 25, value = 127)

        /*
         * 中点 127：正边 128、负边 127 → 短边 127
         * 死区 = 25 / 127 ≈ 0.1969（若用整个量程 255 除则只有 0.098 —— 差一倍）
         */
        assertEquals(
            "应当除以短边（127）而不是整个量程（255）",
            25f / 127f,
            AxisNormalizer.hardwareDeadZone(lopsided),
            0.001f,
        )
        /* 顺带确认对称的那组算出来是 flat/半量程 */
        assertEquals(10f / 200f, AxisNormalizer.hardwareDeadZone(asymmetric), 0.001f)
    }

    /**
     *
     * 某个 ROM 报了个离谱的 `flat`（比如整量程的一大半）时，
     * 不夹住的话摇杆会被整个吃掉 —— **一点都推不动**，
     * 而看代码完全看不出问题。
     */
    @Test
    fun `死区有上限保护`() {
        /* flat 占半量程的 90% —— 不夹住的话摇杆基本推不动 */
        val broken = AxisRange(min = -1000, max = 1000, fuzz = 0, flat = 900, value = 0)

        assertEquals(
            "离谱的 flat 必须被夹到 0.35",
            0.35f,
            AxisNormalizer.hardwareDeadZone(broken),
            0.001f,
        )
    }

    /**
     *
     * 统一除以"较大的半量程"会让短的那一侧永远到不了满量程 ——
     * 表现是"扳机按到底只有 60%"。
     */
    @Test
    fun `不对称的轴两侧都能到满量程`() {
        /*
         * ⚠️ 造"真的不对称"要小心:`value` 是**读取值**,
         * 它离理论中点太远时会被判成"用户正推着摇杆"而**不被采用**。
         *
         * 所以这里让 value 等于理论中点(150)—— 于是中点就是 150,
         * 正边 150、负边 150(对称了)。要造不对称,得靠 **min/max 本身**:
         * `min -100, max 300` → 理论中点 100,正边 200、负边 100。
         */
        val lopsided = AxisRange(min = -100, max = 300, fuzz = 0, flat = 0, value = 100)

        assertEquals("推到最大应当到 1", 1f, AxisNormalizer.normalize(300, lopsided), 0.001f)
        assertEquals("拉到最小应当到 -1", -1f, AxisNormalizer.normalize(-100, lopsided), 0.001f)
        assertEquals("中点应当到 0", 0f, AxisNormalizer.normalize(100, lopsided), 0.001f)

        /*
         * 按侧归一的**关键验证**：负边只有 100 宽，正边有 200 宽。
         * 若统一除以较大的半量程（200），`raw = 0` 只会得到 -0.5；
         * 按侧归一才会到 -1。这就是"扳机按到底只有 60%"那个 bug 的成因。
         */
        assertEquals(
            "短的那一侧也必须能到满量程（按侧归一的关键）",
            -1f,
            AxisNormalizer.normalize(-100, lopsided),
            0.001f,
        )
    }

    /**
     * ⚠️ 探测到的真实 `value` 字段现在会参与计算 —— 回归测试。
     *
     * 你实测的 dump 里摇杆 Y 轴是 `value -1`：
     *
     * ```
     * 0001  : value -1, min -32768, max 32767, fuzz 16, flat 128
     * ```
     *
     * `-1` 离中点 0 只有 1/32768 ≈ 0.003%，在容差内 ——
     * 所以它会被当成中点。这是**对的**（那只是抓取瞬间的微小偏移）。
     */
    @Test
    fun `实测 dump 里的 value 字段会被当中点`() {
        val caps = DeviceCapabilitiesParser.parse(XBOX360_DUMP)
        val ly = caps.getValue("/dev/input/event7").axes.getValue(ABS_Y)

        assertEquals("解析出的 value 应当是 -1", -1, ly.value)
        assertEquals("它离中点够近，应当被信任", -1f, AxisNormalizer.resolveCenter(ly), 0.01f)

        /* 于是"松手"时的 -1 归一化后正好是 0 —— 拇指归中 */
        assertEquals(0f, AxisNormalizer.normalize(-1, ly), 0.0001f)
    }

    private companion object {
        /**
         * 实测输出(原样,只截了前两台 + 末尾一台)。
         *
         * 来源:Microsoft X-Box 360 pad 接在真机上,
         * `su -c "/system/bin/getevent -i -t"`。
         */
        val XBOX360_DUMP = """
            add device 1: /dev/input/event7
              bus:      0003
              vendor    045e
              product   028e
              version   0104
              name:     "Microsoft X-Box 360 pad"
              location: "usb-16700000.xhci0-1.2/input0"
              id:       ""
              version:  1.0.1
              events:
                KEY (0001): 0130  0131  0133  0134  0136  0137  013a  013b
                            013c  013d  013e
                ABS (0003): 0000  : value 0, min -32768, max 32767, fuzz 16, flat 128, resolution 0
                            0001  : value -1, min -32768, max 32767, fuzz 16, flat 128, resolution 0
                            0002  : value 0, min 0, max 255, fuzz 0, flat 0, resolution 0
                            0003  : value 0, min -32768, max 32767, fuzz 16, flat 128, resolution 0
                            0004  : value -1, min -32768, max 32767, fuzz 16, flat 128, resolution 0
                            0005  : value 0, min 0, max 255, fuzz 0, flat 0, resolution 0
                            0010  : value 0, min -1, max 1, fuzz 0, flat 0, resolution 0
                            0011  : value 0, min -1, max 1, fuzz 0, flat 0, resolution 0
              input props:
                <none>
            add device 2: /dev/input/event6
              bus:      0003
              vendor    0000
              product   0000
              version   0001
              name:     "uinput_nav"
              events:
                KEY (0001): 0067  0069  006a  006c  0073  00d4  00d9
              input props:
                <none>
            add device 4: /dev/input/event4
              bus:      0000
              vendor    0000
              product   0001
              version   0001
              name:     "touchpanel"
              location: "synaptics_tcm/touch_input"
              events:
                KEY (0001): 003e  008e  0145  014a  02f8  02f9
                ABS (0003): 0000  : value 0, min 0, max 20352, fuzz 0, flat 0, resolution 0
                            0001  : value 0, min 0, max 44800, fuzz 0, flat 0, resolution 0
                            0018  : value 0, min 0, max 0, fuzz 0, flat 0, resolution 0
              input props:
                INPUT_PROP_DIRECT
            add device 5: /dev/input/event5
              bus:      0019
              name:     "mt6991-mt6681 Headset Jack"
              events:
                KEY (0001): 0072  0073  00a4  0246
                SW  (0005): 0002  0004  0006  0007
              input props:
                <none>
            add device 6: /dev/input/event1
              bus:      0019
              name:     "mtk-kpd"
              events:
                KEY (0001): 0072
              input props:
                <none>
            add device 8: /dev/input/event2
              bus:      0019
              name:     "mtk-pmic-keys"
              events:
                KEY (0001): 0073  0074
              input props:
                <none>
        """.trimIndent()
    }
}

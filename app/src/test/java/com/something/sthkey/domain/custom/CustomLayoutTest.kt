package com.something.sthkey.domain.custom

import com.something.sthkey.domain.keys.KeyCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自定义 Key 的**纯逻辑**测试。
 *
 * ============================================================
 * 为什么只测这一层
 * ============================================================
 * 渲染（[com.something.sthkey.ui.overlay.CustomKeyCanvas]）要 Compose 运行时，
 * JSON 编解码要 `org.json`（Android 实现），两者都得跑仪器测试。
 * 而这里测的 [CustomLayout] 是纯计算 —— 不碰 Android、不碰 Compose，
 * 正好是"最容易悄悄写错、又最难在真机上发现"的那部分：
 * CPS 占位符替换错了只会表现为"数字偶尔不对"，画布算错了只会表现为"边缘差几像素"。
 *
 * 真机验证仍然要做（见 docs/custom-key.md 的验证清单），但这层先钉死。
 */
class CustomLayoutTest {

    /**
     * 造一个文本组件。
     *
     * ⚠️ 键位要同时填进**两个字段**：
     * - [TextComponent.cpsKeyCodesPerPlaceholder] 是新字段（每个占位符一组），
     *   读取时以它为准；
     * - [TextComponent.cpsKeyCodes] 是旧的扁平字段，只有老配置才有。
     *
     * 测试里两个都填，是为了让"用哪一组"这件事不影响这些用例 ——
     * 它们测的是求和与去重，不是字段优先级（那有专门的用例）。
     */
    private fun textComponent(
        text: String,
        keyCodes: List<Int> = listOf(1),
    ) = TextComponent(
        id = "t",
        x = 0f,
        y = 0f,
        width = 100f,
        height = 30f,
        style = ComponentStyle(),
        text = text,
        cpsKeyCodesPerPlaceholder = listOf(keyCodes),
        cpsKeyCodes = keyCodes,
    )

    /*
     * ============================================================
     * CPS 文本
     * ============================================================
     * 规则必须与 Key 样式的 cpsTextTemplate 一致 ——
     * 两套替换逻辑一旦分叉，用户在自定义 Key 里学到的写法换个样式就不成立了。
     *
     * ⚠️ 这里测的是 [CustomLayout.renderCpsText]（**整段替换**）。
     * 早先还有一个 `resolveCps(binding, template, …)`，它多一层"绑定是否开启"的
     * 判断，而渲染层是"主文字 + CPS 行各画一遍" —— 结果是文本组件的
     * `CPS: (cps)` 在屏幕上出现两行。现在只有一条路径：把整段文字替换一遍。
     */

    @Test
    fun `cps 占位符在有数值时替换成数字`() {
        assertEquals("CPS: 3", CustomLayout.renderCpsText(DEFAULT_CPS_TEXT, cps = 3))
    }

    @Test
    fun `cps 占位符在数值为零时仍然显示零`() {
        // (cps) 不隐藏 —— 与 Key 样式模式 2 的行为一致
        assertEquals("CPS: 0", CustomLayout.renderCpsText(DEFAULT_CPS_TEXT, cps = 0))
    }

    @Test
    fun `cps2 占位符在数值为零时把数字那段去掉`() {
        assertEquals("CPS:", CustomLayout.renderCpsText("CPS: (cps2)", cps = 0))
    }

    @Test
    fun `cps2 单独成串时数值为零就什么都不显示`() {
        // 这条与 Key 样式模式 1 的语义一致：模板里只有占位符 → 整段隐藏
        assertEquals("", CustomLayout.renderCpsText("(cps2)", cps = 0))
    }

    @Test
    fun `cps2 占位符在有数值时带上前导空格`() {
        // 这就是 Key 样式里 `LMB(cps2)` 的观感：键名后面跟一个数字
        assertEquals("LMB 5", CustomLayout.renderCpsText("LMB(cps2)", cps = 5))
    }

    @Test
    fun `模板里没有占位符时原样显示`() {
        // 这是"不显示 CPS"的唯一形式 —— 没有开关了，删掉占位符就是关掉
        assertEquals("水印", CustomLayout.renderCpsText("水印", cps = 9))
        assertFalse(CustomLayout.hasCpsPlaceholder("水印"))
    }

    @Test
    fun `空白模板不显示任何东西`() {
        // 按键组件没有键面文字时不该渲染出一个空壳
        assertEquals("", CustomLayout.renderCpsText("   ", cps = 3))
    }

    @Test
    fun `早期的槽位写法仍然能读出来`() {
        // 早期格式只能选左/右键，存的是 cpsSlot；升级后不能突然变成数左键
        assertEquals(KeyCodes.BTN_LEFT, CustomLayout.legacyKeyCodeOf("LMB"))
        assertEquals(KeyCodes.BTN_RIGHT, CustomLayout.legacyKeyCodeOf("RMB"))
        assertEquals(null, CustomLayout.legacyKeyCodeOf("别的"))
    }

    @Test
    fun `判断布局是否用到 cps`() {
        val plainKey = KeyComponent(
            id = "k",
            x = 0f,
            y = 0f,
            width = 80f,
            height = 80f,
            style = ComponentStyle(),
            label = "Q",
            inputKeyCodes = listOf(KEY_CODE_Q),
        )
        val keyWithCps = plainKey.copy(label = "Q(cps2)")

        assertFalse("键名里没有占位符就不该判成用到", CustomLayout.usesCps(listOf(plainKey)))
        assertTrue("键名里写了占位符就该判成用到", CustomLayout.usesCps(listOf(keyWithCps)))
        assertTrue(CustomLayout.usesCps(listOf(textComponent("CPS: (cps)"))))
        assertFalse(CustomLayout.usesCps(listOf(textComponent("水印"))))
        assertFalse(CustomLayout.usesCps(emptyList()))
    }

    @Test
    fun `按键组件的 cps 取自它自己监听的键`() {
        val key = KeyComponent(
            id = "k",
            x = 0f,
            y = 0f,
            width = 80f,
            height = 80f,
            style = ComponentStyle(),
            label = "Q(cps2)",
            inputKeyCodes = listOf(KEY_CODE_Q, 17),
        )
        // 监听的键就是 CPS 的来源 —— 用户不需要再选一遍
        assertEquals(listOf(KEY_CODE_Q, 17), CustomLayout.cpsKeyCodesOf(key))

        val noKeys = key.copy(inputKeyCodes = emptyList())
        assertEquals(emptyList<Int>(), CustomLayout.cpsKeyCodesOf(noKeys))
    }

    @Test
    fun `文本组件的 cps 取自它自己的字段`() {
        val text = textComponent("(cps)", keyCodes = listOf(KeyCodes.BTN_RIGHT))
        assertEquals(listOf(KeyCodes.BTN_RIGHT), CustomLayout.cpsKeyCodesOf(text))
    }

    /**
     * 这条钉的是"多选之后不要把同一个位置算两遍"。
     *
     * 左右 Shift 是两个不同的键码，但它们在键位映射里是**同一个位置**、
     * 共享一个计数。用户把两个都选上时，CPS 应该是那个计数的值，
     * 而不是它的两倍 —— 翻倍只在多选时出现，极难联想到原因。
     */
    @Test
    fun `多个键映射到同一个位置时只算一次`() {
        val text = textComponent("(cps)", keyCodes = listOf(42, 54))
        val slotOf: (Int) -> String? = { "SHIFT" }

        val cps = CustomLayout.cpsAt(text, 0, cpsBySlot = mapOf("SHIFT" to 7), slotIdOf = slotOf)
        assertEquals(7, cps)
    }

    @Test
    fun `多个键映射到不同位置时相加`() {
        val text = textComponent("(cps)", keyCodes = listOf(16, 272))
        val slotOf: (Int) -> String? = { code -> if (code == 16) "Q" else "LMB" }

        val cps = CustomLayout.cpsAt(
            component = text,
            index = 0,
            cpsBySlot = mapOf("Q" to 3, "LMB" to 4),
            slotIdOf = slotOf,
        )
        assertEquals(7, cps)
    }

    @Test
    fun `翻译不出位置的键不计入`() {
        val text = textComponent("(cps)", keyCodes = listOf(16, 999))
        // 999 不在这份配置的键位映射里 → 拿不到位置 → 不参与求和
        val slotOf: (Int) -> String? = { code -> if (code == 16) "Q" else null }

        val cps = CustomLayout.cpsAt(text, 0, cpsBySlot = mapOf("Q" to 5), slotIdOf = slotOf)
        assertEquals(5, cps)
    }

    @Test
    fun `主体文字按类型取`() {
        val key = KeyComponent(
            id = "k",
            x = 0f,
            y = 0f,
            width = 80f,
            height = 80f,
            style = ComponentStyle(),
            label = "LMB(cps2)",
            inputKeyCodes = listOf(KeyCodes.BTN_LEFT),
        )
        assertEquals("LMB(cps2)", CustomLayout.primaryTextOf(key))
        // 为 0 时占位符整段消失，只剩键名；非 0 时后面跟一个数字
        assertEquals("LMB", CustomLayout.displayText(key, cps = 0))
        assertEquals("LMB 7", CustomLayout.displayText(key, cps = 7))

        val text = textComponent("CPS: (cps)")
        assertEquals("CPS: (cps)", CustomLayout.primaryTextOf(text))
        assertEquals("CPS: 7", CustomLayout.displayText(text, cps = 7))
    }

    /*
     * ============================================================
     * 画布几何
     * ============================================================
     * 画布是**固定**的 600×600，不再是"内容包围盒 + 留白"。
     * 早先是包围盒，代价有两个（都很真实）：
     * 1. 位置滑块的取值范围没有确定上界 —— 画布尺寸取决于组件位置，
     *    而滑块上界又要参考画布，自我递归；
     * 2. 把组件往外挪一点，**整个悬浮窗跟着长大**，把屏幕上别的东西顶开。
     */

    @Test
    fun `画布是固定边长与组件无关`() {
        val messy = listOf(
            textComponent("a").copy(x = -500f, y = -500f, width = 300f, height = 300f),
            textComponent("b").copy(x = 5000f, y = 5000f, width = 300f, height = 300f),
        )
        assertEquals(CustomLayout.BASE_CANVAS, CustomLayout.canvasWidth(messy))
        assertEquals(CustomLayout.BASE_CANVAS, CustomLayout.canvasHeight(messy))
    }

    @Test
    fun `没有组件时画布不会退化成零尺寸`() {
        // 零尺寸的窗口在 WindowManager 里是无效参数；"用户删光了组件"是可能的中间状态
        assertTrue(CustomLayout.canvasWidth(emptyList()) > 0f)
        assertTrue(CustomLayout.canvasHeight(emptyList()) > 0f)
    }

    /*
     * ============================================================
     * 坐标范围与夹取
     * ============================================================
     * 不变量：**任何时刻组件的坐标都在合法范围内**。
     * 因为坐标下界是 `-尺寸`、跟着尺寸变，所以"改尺寸"也必须夹坐标 ——
     * 否则缩小一个贴左边界的组件之后，它的 X 滑块会突然跳一下。
     */

    @Test
    fun `坐标下界跟着组件尺寸走`() {
        assertEquals(-80f, CustomLayout.minCoordinate(80f), 0.001f)
        assertEquals(-300f, CustomLayout.minCoordinate(300f), 0.001f)
        assertEquals(CustomLayout.BASE_CANVAS, CustomLayout.maxCoordinate())
    }

    @Test
    fun `缩小贴左边界的组件会把坐标一起夹回来`() {
        // 贴住左边界：x = -width
        val wide = textComponent("a").copy(x = -200f, y = 0f, width = 200f, height = 40f)
        val narrow = wide.resizedTo(width = 50f, height = 40f)

        assertEquals(50f, narrow.width, 0.001f)
        // 不夹的话 x 会留在 -200，超出新下界 -50，滑块一碰就跳 150
        assertEquals(-50f, narrow.x, 0.001f)
        assertEquals(CustomLayout.minCoordinate(narrow.width), narrow.x, 0.001f)
    }

    @Test
    fun `改尺寸会夹进合法范围`() {
        assertEquals(
            CustomLayout.COMPONENT_SIZE_MAX,
            textComponent("a").resizedTo(width = 9999f, height = 9999f).width,
        )
        assertEquals(
            CustomLayout.COMPONENT_SIZE_MIN,
            textComponent("a").resizedTo(width = 0f, height = 0f).width,
        )
    }

    @Test
    fun `夹取把越界坐标拉回范围`() {
        val clamped = textComponent("a")
            .copy(x = 9999f, y = -9999f, width = 100f, height = 40f)
            .clampedToCanvas()

        assertEquals(CustomLayout.maxCoordinate(), clamped.x, 0.001f)
        assertEquals(CustomLayout.minCoordinate(clamped.height), clamped.y, 0.001f)
    }

    @Test
    fun `居中把组件放到画布正中`() {
        val centered = textComponent("a")
            .copy(x = 0f, y = 0f, width = 200f, height = 100f)
            .centeredOnCanvas()

        assertEquals((CustomLayout.BASE_CANVAS - 200f) / 2f, centered.x, 0.001f)
        assertEquals((CustomLayout.BASE_CANVAS - 100f) / 2f, centered.y, 0.001f)
    }

    @Test
    fun `居中不会让组件越界`() {
        // 组件比画布还大时"居中"会算出负值，必须仍在合法范围内
        val huge = textComponent("a")
            .copy(width = CustomLayout.COMPONENT_SIZE_MAX, height = CustomLayout.COMPONENT_SIZE_MAX)
            .centeredOnCanvas()
            .clampedToCanvas()

        assertTrue(huge.x >= CustomLayout.minCoordinate(huge.width))
        assertTrue(huge.y >= CustomLayout.minCoordinate(huge.height))
    }

    /*
     * ============================================================
     * 颜色与外观
     * ============================================================
     */

    @Test
    fun `颜色与透明度分开合成`() {
        val style = ComponentStyle(
            fillUp = 0xFF0000,
            fillOpacityUp = 50,
        )
        val argb = CustomLayout.fillColor(style, pressed = false)

        // 50% → alpha 127；RGB 分量原样保留（这是"颜色与透明度各一个真源"的保证）
        assertEquals(127, (argb ushr 24) and 0xFF)
        assertEquals(0xFF0000, argb and 0xFFFFFF)
    }

    @Test
    fun `按下态用按下那一套颜色与透明度`() {
        val style = ComponentStyle(
            fillUp = 0x000000,
            fillDown = 0xFFFFFF,
            fillOpacityUp = 70,
            fillOpacityDown = 100,
        )
        val up = CustomLayout.fillColor(style, pressed = false)
        val down = CustomLayout.fillColor(style, pressed = true)

        assertEquals(70 * 255 / 100, (up ushr 24) and 0xFF)
        assertEquals(255, (down ushr 24) and 0xFF)
        assertEquals(0x000000, up and 0xFFFFFF)
        assertEquals(0xFFFFFF, down and 0xFFFFFF)
    }

    @Test
    fun `描边未启用时宽度为零`() {
        assertEquals(0f, CustomLayout.outlineWidth(ComponentStyle(outlineEnabled = false)), 0.001f)
        assertTrue(CustomLayout.outlineWidth(ComponentStyle(outlineEnabled = true)) > 0f)
    }

    @Test
    fun `圆角按短边百分比换算`() {
        val style = ComponentStyle(cornerRadiusEnabled = true, cornerRadiusPercent = 50f)
        // 短边 80dp 的 50% = 40dp，也就是胶囊/圆形
        assertEquals(40f, CustomLayout.cornerRadius(style, shortSideDp = 80f), 0.001f)

        // 关掉开关就一定是直角
        val off = ComponentStyle(cornerRadiusEnabled = false, cornerRadiusPercent = 50f)
        assertEquals(0f, CustomLayout.cornerRadius(off, shortSideDp = 80f), 0.001f)
    }

    /*
     * ============================================================
     * 默认布局
     * ============================================================
     * 需求指定：新建后要有一个 Q 键与一个 CPS 文本，一打开就能看懂。
     */

    @Test
    fun `默认布局是一个 Q 键加一个 CPS 文本`() {
        val components = defaultCustomComponents()

        assertEquals(2, components.size)
        val key = components.filterIsInstance<KeyComponent>().single()
        val text = components.filterIsInstance<TextComponent>().single()

        assertEquals("Q", key.label)
        assertEquals(listOf(KEY_CODE_Q), key.inputKeyCodes)
        assertEquals(DEFAULT_CPS_TEXT, text.text)

        /*
         * 默认按键**不显示** CPS、默认文本**显示** CPS ——
         * 两者都不是靠开关，而是靠文字里有没有占位符：
         * "Q" 里没有，DEFAULT_CPS_TEXT 里有。
         */
        assertFalse("按键组件默认不该显示 CPS", CustomLayout.usesCps(listOf(key)))
        assertTrue("文本组件默认应该显示 CPS", CustomLayout.usesCps(listOf(text)))
        assertEquals(DEFAULT_CPS_KEY_CODES, text.cpsKeyCodes)
    }

    @Test
    fun `默认布局的两个组件都有键帽背景`() {
        val components = defaultCustomComponents()

        // 文本组件早先默认是全透明的，看起来像"缺了键帽" —— 现在两者同一套默认主题
        components.forEach { component ->
            assertTrue(
                "组件 ${component.id} 的底色默认不透明度过低，会看起来没有键帽",
                component.style.fillOpacityUp > 0,
            )
        }
    }

    @Test
    fun `默认布局的两个组件不重叠`() {
        val (key, text) = defaultCustomComponents().let {
            it.filterIsInstance<KeyComponent>().single() to
                it.filterIsInstance<TextComponent>().single()
        }
        assertFalse(
            "默认布局里 Q 键与 CPS 文本叠在一起了",
            CustomLayout.overlaps(key, text),
        )
    }

    @Test
    fun `默认布局落在画布之内`() {
        defaultCustomComponents().forEach { component ->
            assertTrue(
                "组件 ${component.id} 的右边界超出了画布",
                component.x + component.width <= CustomLayout.canvasWidth(emptyList()),
            )
            assertTrue(
                "组件 ${component.id} 的下边界超出了画布",
                component.y + component.height <= CustomLayout.canvasHeight(emptyList()),
            )
            assertTrue("组件 ${component.id} 的 X 越界", component.x >= 0f)
            assertTrue("组件 ${component.id} 的 Y 越界", component.y >= 0f)
        }
    }
}

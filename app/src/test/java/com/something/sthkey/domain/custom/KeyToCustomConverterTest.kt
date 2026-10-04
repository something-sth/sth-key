package com.something.sthkey.domain.custom

import com.something.sthkey.domain.config.AnimationMode
import com.something.sthkey.domain.config.CPS_PLACEHOLDER
import com.something.sthkey.domain.config.ColorSet
import com.something.sthkey.domain.config.KeyStrokesConfig
import com.something.sthkey.domain.config.KeyOutline
import com.something.sthkey.domain.config.Opacity
import com.something.sthkey.domain.config.ShadowMode
import com.something.sthkey.domain.config.TextShadow
import com.something.sthkey.domain.config.defaultKeyMappings
import com.something.sthkey.domain.font.FontRegistry
import com.something.sthkey.domain.keys.KeyCodes
import com.something.sthkey.domain.style.KeyLayout
import com.something.sthkey.domain.style.StyleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「按键样式 → 自定义 Key」转换的测试。
 *
 * ============================================================
 * 为什么这些用例最要紧
 * ============================================================
 * 这个操作**不可逆**：转完 `styleId` 就变了，编辑页换成自定义 Key 那一套，
 * 再没有「显示 Shift」这些开关可以退回去。所以用户按下确认之后，
 * 期待的是"同一块悬浮窗，只是现在能编辑了" ——
 * **任何一处位置、颜色、透明度、字号对不上，他都没法退回**。
 *
 * 所以这里逐项钉住"转完与原来一致"：
 * 位置与尺寸照抄布局、颜色/透明度按状态对应、字号补偿基数差、
 * 哪些键存在跟着原来的开关走。
 */
class KeyToCustomConverterTest {

    private fun keyConfig(
        showShift: Boolean = false,
        showMouse: Boolean = true,
        cpsEnabled: Boolean = false,
        cpsMode: Int = 1,
    ) = KeyStrokesConfig(
        id = "src",
        name = "源配置",
        styleId = StyleId.KEYSTROKES,
        showShiftKey = showShift,
        showMouseButtons = showMouse,
        mouseCpsEnabled = cpsEnabled,
        mouseCpsMode = cpsMode,
    )

    private fun convert(config: KeyStrokesConfig) =
        KeyToCustomConverter.convert(config).custom.components

    /*
     * ============================================================
     * 数量：哪些键该被转出来
     * ============================================================
     */

    @Test
    fun `默认配置转出 WASD 加鼠标键加空格`() {
        val components = convert(keyConfig())

        // W、A、S、D、LMB、RMB、SPACE
        assertEquals(7, components.size)
        assertTrue(components.all { it is KeyComponent })
    }

    /** Shift 关着就不该转出 SHIFT 组件 —— 否则用户会莫名多一个键 */
    @Test
    fun `没开 Shift 就不转出 SHIFT`() {
        val labels = convert(keyConfig(showShift = false))
            .mapNotNull { (it as? KeyComponent)?.label }

        assertFalse("SHIFT" in labels)
    }

    @Test
    fun `开了 Shift 就转出 SHIFT`() {
        val labels = convert(keyConfig(showShift = true))
            .mapNotNull { (it as? KeyComponent)?.label }

        assertTrue("SHIFT" in labels)
    }

    @Test
    fun `没开鼠标键就不转出鼠标键`() {
        val components = convert(keyConfig(showMouse = false))
        val labels = components.mapNotNull { (it as? KeyComponent)?.label }

        assertFalse("LMB" in labels)
        assertFalse("RMB" in labels)
        // 鼠标键关掉后只剩 W/A/S/D/SPACE
        assertEquals(5, components.size)
    }

    /*
     * ============================================================
     * 位置与尺寸：必须与布局完全一致
     * ============================================================
     */

    /**
     * ⚠️ 这条是整个转换的**忠实度核心**。
     *
     * 位置直接取自 [KeyLayout.keys]（按键样式自己用的那个函数），
     * 之后**整体平移**到定位区正中。所以"相对关系"必须与布局完全一致 ——
     * 转换后的组件摆出来的形状，与转换前屏幕上的形状是同一个。
     *
     * 断言的是**相对偏移**而不是绝对坐标：绝对坐标会因为居中而改变
     * （那是期望的行为），而相对关系**任何时候都不该变**。
     * 第一版这里直接比绝对坐标，加了居中之后就失败了 ——
     * 失败是对的，它提醒我"绝对坐标本来就不该被钉死"。
     */
    @Test
    fun `每个组件的相对位置与尺寸都来自布局函数`() {
        val config = keyConfig(showShift = true)
        val boxes = KeyLayout.keys(config)
        val components = convert(config)

        assertEquals(boxes.size, components.size)

        // 以第一个组件为基准，逐个比相对偏移
        val baseBox = boxes[0]
        val baseComponent = components[0]

        boxes.forEachIndexed { index, box ->
            val component = components[index]

            assertEquals(
                "第 $index 个相对基准的水平偏移",
                (box.centerX - box.width / 2f) - (baseBox.centerX - baseBox.width / 2f),
                component.x - baseComponent.x,
                0.001f,
            )
            assertEquals(
                "第 $index 个相对基准的垂直偏移",
                box.topY - baseBox.topY,
                component.y - baseComponent.y,
                0.001f,
            )
            assertEquals("第 $index 个的宽度", box.width, component.width, 0.001f)
            assertEquals("第 $index 个的高度", box.height, component.height, 0.001f)
        }
    }

    /** 键位绑定的键码要跟着走，否则转过去按键不会亮 */
    @Test
    fun `W 键转出来仍然监听 W 的键码`() {
        val w = convert(keyConfig()).filterIsInstance<KeyComponent>()
            .first { it.label == "W" }

        assertEquals(listOf(17), w.inputKeyCodes)
    }

    /* ============================================================
     * 外观：颜色 / 透明度 / 描边 / 阴影，逐个字段对应
     * ============================================================
     */

    /**
     * ⚠️ 逐项核对整份外观。
     *
     * 漏一个字段的表现是"转换后某一处在某个状态下不对"，
     * 而且只在那个状态下看得到 —— 很容易以为转换没问题。
     * 所以这里给一份**每个字段都不相同**的配置，
     * 任何一处对应错了都会被这条抓到。
     */
    @Test
    fun `外观字段逐项搬到组件样式上`() {
        val config = keyConfig().copy(
            colors = ColorSet(
                keyUp = 0x111111, keyDown = 0x222222,
                textUp = 0x333333, textDown = 0x444444,
                outlineUp = 0x555555, outlineDown = 0x666666,
                shadowUp = 0x777777, shadowDown = 0x888888,
            ),
            opacity = Opacity(
                keyUp = 11, keyDown = 12, textUp = 13, textDown = 14,
                outlineUp = 15, outlineDown = 16, shadowUp = 17, shadowDown = 18,
            ),
            outline = KeyOutline(enabled = true, width = 3.5f),
            shadow = TextShadow(enabled = true, mode = ShadowMode.HARD, size = 4f),
            cornerRadiusEnabled = true,
            cornerRadiusPercent = 25f,
        )

        val style = (convert(config).first() as KeyComponent).style

        assertEquals(0x111111, style.fillUp)
        assertEquals(0x222222, style.fillDown)
        assertEquals(0x333333, style.textUp)
        assertEquals(0x444444, style.textDown)
        assertEquals(0x555555, style.outlineUp)
        assertEquals(0x666666, style.outlineDown)
        assertEquals(0x777777, style.shadowUp)
        assertEquals(0x888888, style.shadowDown)

        assertEquals(11, style.fillOpacityUp)
        assertEquals(12, style.fillOpacityDown)
        assertEquals(13, style.textOpacityUp)
        assertEquals(14, style.textOpacityDown)
        assertEquals(15, style.outlineOpacityUp)
        assertEquals(16, style.outlineOpacityDown)
        assertEquals(17, style.shadowOpacityUp)
        assertEquals(18, style.shadowOpacityDown)

        assertTrue(style.outlineEnabled)
        assertEquals(3.5f, style.outlineWidth, 0.001f)
        assertTrue(style.shadowEnabled)
        assertEquals(ShadowMode.HARD, style.shadowMode)
        assertTrue(style.cornerRadiusEnabled)
        assertEquals(25f, style.cornerRadiusPercent, 0.001f)
    }

    @Test
    fun `字体与按下动画跟着走`() {
        val config = keyConfig().copy(
            fontId = "builtin:Whatever.ttf",
            animationMode = AnimationMode.RIPPLE,
            animationDurationSec = 0.3f,
        )

        val component = convert(config).first() as KeyComponent

        assertEquals("builtin:Whatever.ttf", component.style.fontId)
        assertEquals(AnimationMode.RIPPLE, component.animationMode)
        assertEquals(0.3f, component.animationDurationSec, 0.001f)
    }

    /* ============================================================
     * 字号：基数是 35 与 28，必须补偿
     * ============================================================
     */

    /**
     * ⚠️ 这条钉的是"转过去字变小了"。
     *
     * 按键样式的键面文字是 `35 × 文字缩放`，而自定义 Key 的组件文字
     * 基数是 28。直接搬百分比会让文字小 20%。
     *
     * 100% → 补偿成 125%，于是 `28 × 1.25 = 35`，与原来一样大。
     */
    @Test
    fun `100 的文字缩放补偿成 125`() {
        val component = convert(keyConfig().copy(textScalePercent = 100))
            .first() as KeyComponent

        assertEquals(125, component.textScalePercent)
        // 补偿之后算出来的字号要等于按键样式的
        assertEquals(
            KeyLayout.textSize(keyConfig().copy(textScalePercent = 100)),
            CustomLayout.textSize(component.textScalePercent),
            0.001f,
        )
    }

    /** 补偿后要夹进允许区间，不能因为补偿而越界 */
    @Test
    fun `补偿后不会越出文字缩放的上下限`() {
        val maxed = convert(keyConfig().copy(textScalePercent = 150)).first() as KeyComponent
        val minimum = convert(keyConfig().copy(textScalePercent = 50)).first() as KeyComponent

        assertTrue(maxed.textScalePercent <= CustomLayout.TEXT_SCALE_MAX)
        assertTrue(minimum.textScalePercent >= CustomLayout.TEXT_SCALE_MIN)
    }

    /* ============================================================
     * CPS
     * ============================================================
     */

    /**
     * 模式 1：CPS 接在鼠标键文字后面，写成 `LMB(cps2)`。
     *
     * 用**占位符**而不是当时的数字 —— 数字会定死，占位符才是"持续显示"。
     */
    @Test
    fun `模式 1 的 CPS 写成 cps2 占位符`() {
        val config = keyConfig(cpsEnabled = true, cpsMode = 1)
        val lmb = convert(config).filterIsInstance<KeyComponent>()
            .first { it.label.startsWith("LMB") }

        assertTrue("要带 (cps2) 占位符，得到的是「${lmb.label}」", lmb.label.contains("(cps2)"))
        // 而且要能真的显示 CPS
        assertTrue(CustomLayout.hasCpsPlaceholder(lmb.label))
    }

    /** 没开 CPS 就不该冒出占位符 */
    @Test
    fun `没开 CPS 时文字里没有占位符`() {
        val components = convert(keyConfig(cpsEnabled = false))

        assertTrue(
            components.none {
                CustomLayout.hasCpsPlaceholder(CustomLayout.primaryTextOf(it))
            },
        )
    }

    /**
     * ⚠️ **回归**:模式 2（独立 CPS 组件）已经删除。
     *
     * 用户的原话:"干脆在 gamepad2 这里把 cps 模式 2 删掉吧，太多组件
     * 也不好安排，就留模式 1 和 3 就好了"。
     *
     * ⚠️ 所以"模式 2 转出两个独立文本组件"这件事**不该再发生** ——
     * 转自定义之后如果还冒出两个只数左键 / 只数右键的 CPS 文本框，
     * 那是没删干净。
     */
    @Test
    fun `模式 2 不再转出独立的 CPS 文本组件`() {
        val config = keyConfig(cpsEnabled = true, cpsMode = 2)
        val withCps = convert(config)
            .filterIsInstance<TextComponent>()
            .filter { CustomLayout.hasCpsPlaceholder(it.text) }

        assertTrue(
            "不该再有'独立 CPS 文本框'（当时是 2 个），实际 ${withCps.size} 个",
            withCps.size <= 1,
        )
    }


    /**
     * 模式 3：键内两行 → 用**转义的换行符**保留成两行。
     *
     * ⚠️ 是转义（`\n` 两个字符）而不是真的换行 —— 文字输入框是单行的，
     * 真换行会让第二行**看不见但存在**，用户点那片空白还能改到文字，
     * 像是"在编辑一块空气"。转义写法在输入框里就是可见的两个字符。
     */
    @Test
    fun `模式 3 用转义换行保留两行`() {
        val config = keyConfig(cpsEnabled = true, cpsMode = 3)
        val result = KeyToCustomConverter.convert(config)
        val lmb = result.custom.components.filterIsInstance<KeyComponent>()
            .first { it.label.startsWith("LMB") }

        assertTrue(
            "应当含转义换行（\\n 两个字符），得到「${lmb.label}」",
            lmb.label.contains("\\n"),
        )
        assertFalse(
            "不能含真的换行符（输入框是单行的，会看不见）",
            lmb.label.contains('\n'),
        )
        assertTrue(
            "第二行要是 CPS 模板",
            CustomLayout.hasCpsPlaceholder(lmb.label),
        )
        assertTrue(
            "说明里要如实讲清两行是怎么保留的，实际：${result.notes}",
            result.notes.any { it.contains("模式 3") },
        )
    }

    /**
     * ⚠️ 两行里的 CPS 占位符仍然要能被算出来，而且**转义换行要被还原**。
     *
     * 换行符不能干扰占位符扫描 —— 如果扫描把 `\n` 当成文本，
     * 第二行的 `(cps)` 就永远不会被替换，用户看到的是字面的 "(cps)"。
     */
    @Test
    fun `转义换行后的占位符仍然能被识别与替换`() {
        val config = keyConfig(cpsEnabled = true, cpsMode = 3)
        val lmb = KeyToCustomConverter.convert(config).custom.components
            .filterIsInstance<KeyComponent>()
            .first { it.label.startsWith("LMB") }

        assertEquals(1, CustomLayout.cpsPlaceholders(lmb.label).size)
        // 默认模板是 `CPS: (cps)`，所以第二行展开成 `CPS: 5`
        assertEquals("LMB\\nCPS: 5", CustomLayout.renderCpsTextAt(lmb.label, listOf(5)))
    }

    /* ============================================================
     * 坐标系：绝不自己平移
     * ============================================================
     */

    /**
     * ⚠️ 这条钉的是一个**我做出来又修掉**的 bug。
     *
     * 曾经在转换器里把内容"居中到 600 定位区"，理由是"用户打开编辑器
     * 看到布局在左上角会以为转换坏了"。
     *
     * 但自定义样式的窗口尺寸取**所有组件的包围盒**，
     * 而渲染时 `CustomLayout.toWindow` 会**按包围盒的左上角平移一次**。
     * 转换器里再居中一遍，等于把内容**平移两次**：
     * 组件被挪到 x=170 → 渲染时再减 170 → 画在 0，
     * 但窗口只有 260 宽、内容却从 170 摆到 430 —— **右边一截被裁**。
     *
     * 症状就是"悬浮窗上最宽的空格与 Shift 右边短一截"，
     * 而编辑器里正常（画的是 600×600 定位区，不显示窗口边界）。
     *
     * 所以位置必须**原样照抄布局函数**，平移交给 `toWindow` 一家负责。
     */
    @Test
    fun `组件坐标原样照抄布局不做任何平移`() {
        val config = keyConfig(showShift = true)
        val boxes = KeyLayout.keys(config)
        val components = convert(config)

        boxes.forEachIndexed { index, box ->
            val component = components[index]
            assertEquals(
                "第 $index 个的左边界必须等于布局给出的值",
                box.centerX - box.width / 2f,
                component.x,
                0.001f,
            )
            assertEquals(
                "第 $index 个的顶部必须等于布局给出的值",
                box.topY,
                component.y,
                0.001f,
            )
        }
    }

    /**
     * ⚠️ 包围盒偏移必须等于组件的最小坐标。
     *
     * 因为渲染时会按这个偏移平移一次 —— 它一旦与组件坐标不一致，
     * 画面就会整体错位（那正是上面那个 bug 的机制）。
     */
    @Test
    fun `包围盒偏移与组件坐标一致`() {
        val components = convert(keyConfig(showShift = true, cpsEnabled = true, cpsMode = 2))
        val bounds = CustomLayout.bounds(components)

        assertEquals(
            "偏移必须等于最小 x",
            components.minOf { it.x },
            bounds.offsetX,
            0.001f,
        )
        assertEquals(
            "偏移必须等于最小 y",
            components.minOf { it.y },
            bounds.offsetY,
            0.001f,
        )
        assertEquals("宽度必须等于内容的横向跨度", boxesSpanX(components), bounds.width, 0.001f)
    }

    /** 内容的横向跨度（最大右边界 − 最小左边界） */
    private fun boxesSpanX(components: List<CustomComponent>): Float =
        components.maxOf { it.x + it.width } - components.minOf { it.x }

    /**
     * ⚠️ 窗口尺寸必须装得下内容，不能右边裁掉一块。
     *
     * 这条是从"空格与 Shift 右边短一截"那个真实 bug 反推出来的断言：
     * `baseSizeOf` 是窗口尺寸的来源，它必须 **≥ 内容的包围盒**。
     */
    @Test
    fun `窗口尺寸不小于内容包围盒`() {
        val config = keyConfig(showShift = true, cpsEnabled = true, cpsMode = 2)
        val converted = config.toCustomKeyStyle().first

        val bounds = CustomLayout.bounds(converted.custom.components)
        val base = com.something.sthkey.domain.style.OverlayStyleRegistry
            .baseSizeOf(converted)

        assertTrue(
            "窗口宽 ${base.width} < 内容宽 ${bounds.width}，右侧会被裁",
            base.width >= bounds.width - 0.001f,
        )
        assertTrue(
            "窗口高 ${base.height} < 内容高 ${bounds.height}，底部会被裁",
            base.height >= bounds.height - 0.001f,
        )
    }

    /* ============================================================
     * 配置层面
     * ============================================================
     */

    @Test
    fun `转换后样式 id 变成自定义 Key`() {
        val (converted, _) = keyConfig().toCustomKeyStyle()

        assertEquals(StyleId.CUSTOM_KEY, converted.styleId)
        assertTrue(converted.custom.components.isNotEmpty())
    }

    /**
     * ⚠️ 除了样式与组件，**别的都不许改**。
     *
     * 转换不该顺手改掉用户没要求改的东西 —— 名称、描述、整体缩放
     * 都是他们的东西。漏了这条就会出现"转个样式把我的名字也改了"。
     */
    @Test
    fun `身份与整体缩放原样保留`() {
        val source = keyConfig().copy(
            id = "keep-me",
            name = "我的配置",
            description = "别动我",
            builtIn = false,
            scalePercent = 137,
        )

        val (converted, _) = source.toCustomKeyStyle()

        assertEquals("keep-me", converted.id)
        assertEquals("我的配置", converted.name)
        assertEquals("别动我", converted.description)
        assertEquals(137, converted.scalePercent)
    }

    @Test
    fun `哪些配置能转换`() {
        assertTrue(keyConfig().canConvertToCustom())
        assertFalse(keyConfig().copy(styleId = StyleId.CUSTOM_KEY).canConvertToCustom())
        assertFalse(keyConfig().copy(styleId = StyleId.KEYBOARD_CAT).canConvertToCustom())
    }

    /** 组件 id 不能重复，否则编辑器里选中一个会牵动另一个 */
    @Test
    fun `组件 id 互不重复`() {
        val ids = convert(keyConfig(showShift = true, cpsEnabled = true, cpsMode = 2))
            .map { it.id }

        assertEquals("id 有重复：$ids", ids.size, ids.distinct().size)
    }

    /** 转换出来的组件应当能被正常的编辑操作接受（形状合法） */
    @Test
    fun `转出的组件尺寸都在合法范围内`() {
        convert(keyConfig(showShift = true, cpsEnabled = true, cpsMode = 2)).forEach { component ->
            assertNotNull(component.id)
            assertTrue("${component.id} 宽度非法", component.width > 0f)
            assertTrue("${component.id} 高度非法", component.height > 0f)
        }
    }
}

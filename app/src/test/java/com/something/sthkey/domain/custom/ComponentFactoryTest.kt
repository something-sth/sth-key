package com.something.sthkey.domain.custom

import com.something.sthkey.domain.config.KeyStrokesConfig
import com.something.sthkey.domain.keys.KeyCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 新建 / 复制组件的测试。
 *
 * ============================================================
 * 为什么这两件事必须测
 * ============================================================
 * 它们的失败方式都**不会报错、只会看起来很奇怪**：
 *
 * - 复制出来的组件 id 与原件相同 → 改一个另一个也跟着变
 *   （用户会以为"复制出来的东西是联动的"）；
 * - 副本与原件位置相同 → 点了复制什么都没发生（用户会以为按钮坏了）；
 * - 副本越界 → 它在悬浮窗上不见了。
 *
 * 三件事都能用纯计算表达，所以没有理由不钉住。
 */
class ComponentFactoryTest {

    private fun config() = KeyStrokesConfig(
        id = "cfg",
        name = "测试配置",
    )

    private fun settings(vararg components: CustomComponent) =
        CustomLayoutSettings(components = components.toList())

    private fun key(
        id: String = "k1",
        x: Float = 100f,
        y: Float = 100f,
        label: String = "Q",
        codes: List<Int> = listOf(KEY_CODE_Q),
    ) = KeyComponent(
        id = id,
        x = x,
        y = y,
        width = 80f,
        height = 80f,
        style = ComponentStyle(),
        label = label,
        inputKeyCodes = codes,
    )

    private fun text(
        id: String = "t1",
        x: Float = 100f,
        y: Float = 200f,
        content: String = "CPS: (cps)",
    ) = TextComponent(
        id = id,
        x = x,
        y = y,
        width = 120f,
        height = 30f,
        style = ComponentStyle(),
        text = content,
    )

    /*
     * ============================================================
     * id 唯一性
     * ============================================================
     */

    @Test
    fun `生成的 id 不会与现有的重复`() {
        val existing = listOf("key_abc", "text_xyz")
        val fresh = newComponentId(ComponentType.KEY, existing)

        assertTrue("新 id 撞上了现有的：$fresh", fresh !in existing)
        assertTrue("新 id 该带上类型前缀", fresh.startsWith("key_"))
    }

    @Test
    fun `id 撞车时往后顺延而不是覆盖`() {
        /*
         * 这条模拟"同一毫秒内连续生成两次"：第二次必须换一个 id。
         *
         * 直接连调两次 newComponentId 时，两次的 System.currentTimeMillis()
         * 很可能是同一个值 —— 这正是要防的场景。
         */
        val first = newComponentId(ComponentType.KEY, emptyList())
        val second = newComponentId(ComponentType.KEY, listOf(first))

        assertEquals("前缀该一致", true, first.startsWith("key_") && second.startsWith("key_"))
        assertNotEquals("同毫秒内两次生成必须得到不同的 id", first, second)
    }

    /*
     * ============================================================
     * 复制
     * ============================================================
     */

    @Test
    fun `复制出来的组件换了 id`() {
        val source = key(id = "original")
        val copy = duplicateComponent(source, settings(source))

        assertNotEquals(
            "副本与原件同 id 会让「改一个另一个也跟着变」，这是最难看懂的一类 bug",
            source.id,
            copy.id,
        )
    }

    @Test
    fun `复制出来的组件位置有偏移`() {
        val source = key(x = 100f, y = 100f)
        val copy = duplicateComponent(source, settings(source))

        assertNotEquals("副本压在原件上的话，用户会以为复制按钮坏了", source.x, copy.x)
        assertNotEquals(source.y, copy.y)
        assertEquals(100f + DUPLICATE_OFFSET, copy.x, 0.001f)
        assertEquals(100f + DUPLICATE_OFFSET, copy.y, 0.001f)
    }

    @Test
    fun `复制保留全部内容与外观`() {
        val source = key(label = "LMB(cps2)", codes = listOf(KeyCodes.BTN_LEFT, KeyCodes.BTN_RIGHT))
            .copy(
                style = ComponentStyle(
                    fillUp = 0x123456,
                    fillOpacityUp = 42,
                    outlineEnabled = true,
                    outlineWidth = 3f,
                    cornerRadiusEnabled = true,
                    cornerRadiusPercent = 25f,
                    fontId = "system:serif",
                ),
                textScalePercent = 120,
                textOffsetX = 5f,
                textOffsetY = -3f,
                animationMode = com.something.sthkey.domain.config.AnimationMode.RIPPLE,
            )
        val copy = duplicateComponent(source, settings(source)) as KeyComponent

        // 复制就是"一模一样"：不做自动改名之类的自作聪明
        assertEquals(source.label, copy.label)
        assertEquals(source.inputKeyCodes, copy.inputKeyCodes)
        assertEquals(source.style, copy.style)
        assertEquals(source.textScalePercent, copy.textScalePercent)
        assertEquals(source.textOffsetX, copy.textOffsetX, 0.001f)
        assertEquals(source.textOffsetY, copy.textOffsetY, 0.001f)
        assertEquals(source.animationMode, copy.animationMode)
        assertEquals(source.width, copy.width, 0.001f)
        assertEquals(source.height, copy.height, 0.001f)
    }

    @Test
    fun `文本组件也能复制并保留 cps 设置`() {
        val source = text(content = "CPS: (cps)").copy(cpsKeyCodes = listOf(272, 273))
        val copy = duplicateComponent(source, settings(source)) as TextComponent

        assertNotEquals(source.id, copy.id)
        assertEquals(source.text, copy.text)
        assertEquals(source.cpsKeyCodes, copy.cpsKeyCodes)
    }

    /**
     * 这条钉的是"贴边时副本会压在原件上"那个坑。
     *
     * 原件贴住右边界时 `+偏移` 会被夹回原位，副本就与原件逐像素重合、
     * 用户点了复制什么也看不见。所以夹取之后要察觉"位置没动"，改往左上偏。
     */
    @Test
    fun `贴住边界的原件复制时会往反方向偏`() {
        /*
         * ⚠️ 原件要摆在**坐标上界**上，而不是定位区边缘（600）。
         *
         * 坐标上界这一版从 600 提到了 1000（用户可以把组件摆到定位区之外），
         * 所以摆在 600 的原件已经**不再贴住边界** —— 副本会照常往右下方偏。
         * 换成真正的上界，这条才测的是"贴边时往反方向偏"。
         */
        val source = text(
            x = CustomLayout.maxCoordinate(),
            y = CustomLayout.maxCoordinate(),
        )
        val copy = duplicateComponent(source, settings(source))

        assertTrue(
            "副本必须真的挪动了（否则就是压在原件上、看不见）",
            copy.x != source.x || copy.y != source.y,
        )
        assertEquals(
            "贴住右边界时副本应当往左偏，而不是留在边界外",
            CustomLayout.maxCoordinate() - DUPLICATE_OFFSET,
            copy.x,
            0.001f,
        )
        assertEquals(
            CustomLayout.maxCoordinate() - DUPLICATE_OFFSET,
            copy.y,
            0.001f,
        )
    }

    /**
     * 不变量是"**左上角**落在合法范围内"，而不是"整个组件都在画布内"。
     *
     * 组件允许有一部分伸出画布外（那就是"贴边"的用法，见
     * docs/custom-key.md 7.3），所以这里断言的是坐标范围。
     */
    @Test
    fun `副本的坐标一定落在合法范围内`() {
        listOf(
            text(x = 0f, y = 0f),
            text(x = CustomLayout.BASE_CANVAS, y = CustomLayout.BASE_CANVAS),
            text(x = CustomLayout.minCoordinate(), y = CustomLayout.minCoordinate()),
        ).forEach { source ->
            val copy = duplicateComponent(source, settings(source))
            assertTrue(
                "副本 x=${copy.x} 越界（原件 x=${source.x}）",
                copy.x >= CustomLayout.minCoordinate() &&
                    copy.x <= CustomLayout.maxCoordinate(),
            )
            assertTrue(
                "副本 y=${copy.y} 越界（原件 y=${source.y}）",
                copy.y >= CustomLayout.minCoordinate() &&
                    copy.y <= CustomLayout.maxCoordinate(),
            )
        }
    }

    @Test
    fun `连续复制两次得到三个不同的 id`() {
        // 模拟用户连点两下复制：三个组件必须互不相同，否则会互相联动
        val source = key(id = "original")
        val first = duplicateComponent(source, settings(source))
        val all = listOf(source, first)
        val second = duplicateComponent(source, CustomLayoutSettings(components = all))

        val ids = listOf(source.id, first.id, second.id).toSet()
        assertEquals("三个组件必须有三个不同的 id", 3, ids.size)
    }

    /*
     * ============================================================
     * 新建
     * ============================================================
     */

    @Test
    fun `新建的按键组件绑一个还没被用过的键`() {
        val existing = key(codes = listOf(KEY_CODE_Q))
        val created = createComponent(
            type = ComponentType.KEY,
            settings = settings(existing),
            config = config(),
        ) as KeyComponent

        assertNotEquals(
            "新建的组件绑了已经被占用的键，按下去会一起亮",
            existing.inputKeyCodes,
            created.inputKeyCodes,
        )
    }

    @Test
    fun `新建的组件落在画布范围内`() {
        ComponentType.entries.forEach { type ->
            val created = createComponent(type, settings(), config())
            assertTrue("${type.id} 的 x 越界", created.x >= 0f)
            assertTrue("${type.id} 的 y 越界", created.y >= 0f)
            assertTrue(
                "${type.id} 的右边界越界",
                created.x + created.width <= CustomLayout.canvasWidth(emptyList()),
            )
            assertTrue(
                "${type.id} 的下边界越界",
                created.y + created.height <= CustomLayout.canvasHeight(emptyList()),
            )
        }
    }

    @Test
    fun `新建的文本组件默认不显示 cps`() {
        val created = createComponent(
            type = ComponentType.TEXT,
            settings = settings(),
            config = config(),
        )

        assertTrue(
            "新建文本默认带占位符的话，一建出来就是一行 CPS: 0，很困惑",
            !CustomLayout.hasCpsPlaceholder(CustomLayout.primaryTextOf(created)),
        )
    }

    @Test
    fun `新建的组件会沿用配置的配色`() {
        val cfg = config().let { it.copy(fontId = "system:serif") }
        val created = createComponent(ComponentType.KEY, settings(), cfg)

        assertEquals("system:serif", created.textStyle()!!.fontId)
        assertEquals(cfg.colors.keyUp, created.textStyle()!!.fillUp)
        assertEquals(cfg.colors.textUp, created.textStyle()!!.textUp)
    }

    @Test
    fun `类型识别与组件类型对得上`() {
        assertEquals(ComponentType.KEY, typeOf(key()))
        assertEquals(ComponentType.TEXT, typeOf(text()))
    }
}

package com.something.sthkey.ui.overlay

import com.something.sthkey.domain.config.KeyMapping
import com.something.sthkey.domain.config.defaultShowSpaceKey
import com.something.sthkey.domain.config.touchingKeyMappings
import com.something.sthkey.domain.style.KeyLayout
import com.something.sthkey.domain.style.StyleId
import com.something.sthkey.domain.config.defaultConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 触屏**区域槽位**与**渲染槽位**必须一一对得上。
 *
 * ============================================================
 * ⚠️ 这里要抓的是什么
 * ============================================================
 * 用户报的现象:
 *
 * > 渲染会亮了但只是**偶尔亮**而且有问题，我按设置了 **LT 的位置**，
 * > 却**很难亮得起来**，亮起来的时候亮的还是 **RT**
 *
 * ⚠️ "亮错了一个" + "很难亮" 这种组合，最常见的成因是
 * **区域绑的槽位与画出来的槽位对不上**（或者有几块区域绑到了同一个槽位）——
 * 那样手指按 A 却点亮 B，而"正确的那个"永远不亮。
 *
 * ⚠️ 两条链路取的槽位 id 来源不同:
 *
 * | 谁 | 从哪取槽位 |
 * |---|---|
 * | 编辑器（生成区域） | `KeyLayout.keys(config)` |
 * | 渲染（点灯） | `KeyLayout.keys(config)` 给出的 `box.slotId` |
 *
 * 同一个函数，所以**理论上**必然一致 —— 但这个测试把它钉住，
 * 并且顺带查出"有没有重复槽位"这类会让区域互相抢的问题。
 */
class TouchRegionSlotIdTest {

    /**
     * 一份触屏配置 —— **与 `ConfigStore.create` 造出来的那一份同构**。
     *
     * ============================================================
     * ⚠️⚠️ 这个"同构"要求我已经栽过**三次**了，所以逐条写明
     * ============================================================
     * `defaultConfig()` 是**键盘**样式的默认值，而 `ConfigStore.create`
     * 会按样式把好几个开关重算 —— 漏一个，这份"假配置"就与真机不一致，
     * 测试于是断言一个**真机上不存在**的槽位列表:
     *
     * | 开关 | 键盘默认 | 触屏应为 | 漏了会怎样 |
     * |---|---|---|---|
     * | `showSpaceKey` | `true` | **`true`** | 凭空多出 SPACE（第 1 次栽） |
     * | `showShiftKey` | `false` | **`true`** | 少掉 SHIFT（第 2 次栽） |
     * | `showAButton` | `true` | **`false`** | 多出一个"叫 A 的键"（第 3 次栽） |
     *
     * ⚠️ 三条的**根因是同一个**:键盘的默认值不能直接给别的样式用。
     * ⚠️ 真源在 `ConfigStore.create` —— 改那边时这里必须跟着改。
     */
    private fun touchConfig() = defaultConfig().copy(
        styleId = StyleId.TOUCHING,
        keyMappings = touchingKeyMappings(),
        showSpaceKey = defaultShowSpaceKey(StyleId.TOUCHING),
        /* 触屏默认显示 Shift —— 好让「触摸区域编辑」里有一块 Shift 可配 */
        showShiftKey = true,
        /*
         * ⚠️ 触屏**关掉 A 键行** —— 它拿 `SPACE` 槽位当 Space
         * （见 `touchingKeyMappings`），A 行留着就是**第二个 Space**。
         */
        showAButton = false,
    )

    /** 编辑器会给哪些槽位生成区域（除去两个摇杆） */
    private fun editorSlotIds(): List<String> =
        KeyLayout.keys(touchConfig())
            .map { it.slotId }
            .filter { it != KeyLayout.Id.JOYSTICK_LEFT && it != KeyLayout.Id.JOYSTICK_RIGHT }
            .distinct()

    /**
     * ⚠️⚠️ 最要紧的一条:**每个槽位只能出现一次**。
     *
     * 重复的话，编辑器会给同一个槽位生成两块区域，而命中的永远是
     * 列表靠前的那块 —— 用户拖另一块**完全没用**（"很难亮"）。
     */
    @Test
    fun `编辑器的槽位 id 没有重复`() {
        val raw = KeyLayout.keys(touchConfig()).map { it.slotId }
        val duplicates = raw.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
        assertTrue(
            "槽位 id 重复了:$duplicates —— 编辑器会给同一个槽位生成多块区域，" +
                "而命中的永远是靠前那块，用户拖另一块完全没用",
            duplicates.isEmpty(),
        )
    }

    /**
     * ⚠️ **每个可绑区域的槽位，渲染层都得画得出来**。
     *
     * 编辑器给的 id 如果在渲染那边找不到对应的 `box`，
     * 那块区域按下去谁都不亮（"很难亮"）。
     */
    @Test
    fun `编辑器生成的槽位渲染层都有对应的键帽`() {
        val renderSlots = KeyLayout.keys(touchConfig()).map { it.slotId }.toSet()
        editorSlotIds().forEach { slotId ->
            assertTrue(
                "编辑器会为「$slotId」生成区域，但渲染层的键盒里没有这个槽位 —— " +
                    "那块区域按下去谁都不亮",
                slotId in renderSlots,
            )
        }
    }

    /**
     * ⚠️⚠️ **触屏的 `inputKeyCodes` 必须全是空的**（那正是键帽不能靠键码点亮的原因）。
     *
     * 若哪天有人给触屏填上了键码，那么"槽位那条路"与"键码那条路"
     * 会同时对同一个槽位生效 —— 一条会亮、一条不会，表现就是**时亮时不亮**。
     */
    @Test
    fun `触屏的键位映射不绑任何键码`() {
        val nonEmpty = touchingKeyMappings().filter { it.inputKeyCodes.isNotEmpty() }
        assertTrue(
            "触屏的键位映射不该绑键码（触屏不读物理按键），但以下槽位绑了:" +
                "${nonEmpty.map { it.id }} —— 那会让点灯出现两条互相矛盾的路径",
            nonEmpty.isEmpty(),
        )
    }

    /**
     * ⚠️ 触屏的 `box.codes` 必须**全是空的** —— 这是"必须走槽位那条路"的证据。
     *
     * 与上一条是同一个事实的两种表达:一个查映射表、一个查布局产出的键盒。
     * 两条都留着是因为它们坏掉的方式不同（有人改映射 vs 有人改布局）。
     */
    @Test
    fun `触屏的键盒不带键码`() {
        val withCodes = KeyLayout.keys(touchConfig()).filter { it.codes.isNotEmpty() }
        assertTrue(
            "触屏的键盒不该带键码，但以下槽位带了:${withCodes.map { it.slotId }} —— " +
                "键帽点亮的键码那条路对触屏本来就是死的",
            withCodes.isEmpty(),
        )
    }

    /**
     * ⚠️ 显示文字与槽位 id 的对应关系必须**唯一**（面板靠它给区域命名）。
     *
     * 两个槽位显示同一个字的话，用户在面板里分不出哪行是哪个 ——
     * 而他正是靠面板的滑块调边长的。
     */
    @Test
    fun `触屏各槽位的显示文字不重复`() {
        val labels = touchingKeyMappings().map { it.displayText }
        val duplicates = labels.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
        assertTrue(
            "显示文字重复了:$duplicates —— 面板里两行同名，用户分不出哪个是哪个",
            duplicates.isEmpty(),
        )
    }

    /**
     * ⚠️ 面板上每个区域都得有名字。
     *
     * `labelFor` 在 `keyMappings` 里找不到时**回落成槽位 id**，
     * 所以这条断言的是"回落不会发生"（那串 id 用户看不懂）。
     */
    @Test
    fun `每个可绑槽位在映射表里都有显示文字`() {
        val mappings: List<KeyMapping> = touchingKeyMappings()
        val missing = editorSlotIds().filter { id -> mappings.none { it.id == id } }
        assertTrue(
            "以下槽位在键位映射里没有条目，面板上会显示成内部 id:$missing",
            missing.isEmpty(),
        )
    }

    /** 让人能一眼看出触屏到底有哪些可绑区域 */
    @Test
    fun `打印触屏的可绑槽位`() {
        val ids = editorSlotIds()
        println("触屏可绑区域的槽位（${ids.size} 个）:$ids")
        assertFalse("触屏总得有几个可绑的按键槽位", ids.isEmpty())
        assertEquals(
            "轮盘不在这个列表里（它是虚拟槽位，由编辑器单独加）",
            false,
            ids.contains(com.something.sthkey.domain.config.TouchSettings.JOYSTICK_SLOT_ID),
        )
    }
}

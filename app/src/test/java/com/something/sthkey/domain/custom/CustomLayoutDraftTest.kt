package com.something.sthkey.domain.custom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 草稿状态与撤回栈的测试。
 *
 * ============================================================
 * 为什么值得专门测
 * ============================================================
 * 撤回/恢复是个状态机，"拖一次算几步""改完再撤回到哪"这类问题
 * 靠肉眼看代码审不出来，在真机上又只表现为"撤回怪怪的"。
 * 而它出错的后果是**用户的作品被改坏**，属于必须钉死的部分。
 *
 * 核心约定：**一次滑块拖动 = 撤回一步**（中途那几十次中间状态不占历史）。
 */
class CustomLayoutDraftTest {

    private fun draft(): CustomLayoutDraft = CustomLayoutDraft(CustomLayoutSettings())

    /** 取第一个按键组件 */
    private fun keyOf(d: CustomLayoutDraft) =
        d.current.components.filterIsInstance<KeyComponent>().first()

    /** 模拟一次拖动：起点 → 若干中间状态 → 抬手 */
    private fun drag(d: CustomLayoutDraft, from: Float, steps: List<Float>) {
        d.mutateOnce()
        (listOf(from) + steps).forEach { x ->
            d.assign { settings ->
                settings.copy(
                    components = settings.components.map {
                        if (it is KeyComponent) it.copy(x = x) else it
                    },
                )
            }
        }
        d.finishStep()
    }

    /*
     * ============================================================
     * 基本读写
     * ============================================================
     */

    @Test
    fun `初始状态没有改动也不能撤回`() {
        val d = draft()
        assertFalse("新建的草稿不该是 dirty", d.dirty)
        assertFalse(d.canUndo)
        assertFalse(d.canRedo)
    }

    @Test
    fun `改一下就变 dirty`() {
        val d = draft()
        d.replace(keyOf(d).withPrimaryText("改过了"))
        assertTrue(d.dirty)
    }

    @Test
    fun `保存之后不再是 dirty`() {
        val d = draft()
        d.replace(keyOf(d).withPrimaryText("改过了"))
        d.markSaved()
        assertFalse("保存后不该还是 dirty", d.dirty)
    }

    /*
     * ============================================================
     * 撤回 / 恢复
     * ============================================================
     */

    @Test
    fun `拖动一次只算一步撤回`() {
        val d = draft()
        val startX = keyOf(d).x

        // 一次拖动产生 5 个中间状态
        drag(d, from = startX, steps = listOf(20f, 40f, 60f, 80f, 100f))
        assertEquals(100f, keyOf(d).x, 0.001f)

        d.undo()
        assertEquals("撤回一次应该直接回到拖动前", startX, keyOf(d).x, 0.001f)
        assertFalse("撤回到底之后不该还能撤", d.canUndo)
    }

    @Test
    fun `恢复能回到拖动后的位置`() {
        val d = draft()
        val startX = keyOf(d).x
        drag(d, from = startX, steps = listOf(50f, 100f))

        d.undo()
        assertTrue(d.canRedo)
        d.redo()
        assertEquals(100f, keyOf(d).x, 0.001f)
    }

    /*
     * ============================================================
     * ⚠️ 只 assign 不改历史 —— 文本输入框踩过这个坑
     * ============================================================
     * 输入框打字时传的是 `asStep = false`（不该每敲一个字压一步），
     * 而 [CustomLayoutDraft.assign] **只改当前格、历史原地不动**。
     *
     * 一开始没给输入框配"开步/收步"，于是改完文字 `canUndo` 仍然是 false ——
     * 用户看到的就是"改了文本内容却撤回不了"。颜色那类走
     * `commit`（自己会开步）所以没这个问题，于是 bug 表现得很局部、
     * 很难联想到是"缺了一对 begin/end"。
     */

    /** 钉住错误做法的症状：只 assign 不会让撤回可用 */
    @Test
    fun `只 assign 不推进历史所以撤回不了`() {
        val d = draft()
        val original = keyOf(d).label

        // 模拟"没有 begin/end 的输入框"
        d.assign { settings ->
            settings.copy(
                components = settings.components.map {
                    if (it is KeyComponent) it.copy(label = "改过了") else it
                },
            )
        }

        assertEquals("改过了", keyOf(d).label)
        assertTrue("内容确实变了，应当是 dirty", d.dirty)
        assertFalse("只 assign 不该推进历史", d.canUndo)

        // 这一步是为了让断言不至于"因为别的原因"通过
        assertNotEquals(original, keyOf(d).label)
    }

    /** 正确做法：聚焦开步 → 连续 assign → 失焦收步，整段输入只占一步 */
    @Test
    fun `一次输入会话只算一步撤回`() {
        val d = draft()
        val original = keyOf(d).label

        d.mutateOnce()
        listOf("L", "LM", "LMB").forEach { text ->
            d.assign { settings ->
                settings.copy(
                    components = settings.components.map {
                        if (it is KeyComponent) it.copy(label = text) else it
                    },
                )
            }
        }
        d.finishStep()

        assertEquals("LMB", keyOf(d).label)
        assertTrue("改完文字必须能撤回", d.canUndo)

        d.undo()
        assertEquals("撤回一次应回到改动前，而不是中间态", original, keyOf(d).label)
    }

    /** 点进输入框又直接退出（什么都没改）不该留下一步无效撤回 */
    @Test
    fun `输入框没改动就退出不留空步`() {
        val d = draft()

        d.mutateOnce()
        // 没有任何 assign
        d.finishStep()

        assertFalse("空步必须被撤掉，否则撤回按了没反应", d.canUndo)
    }

    /*
     * ============================================================
     * ⚠️ "开步"本身不许让撤回亮起来
     * ============================================================
     * [mutateOnce] 一开步就把 `cursor` 推到新的一格（内容是当前位置的
     * **副本**），所以"开步"这个动作会让 `cursor > 0` 成立 ——
     * 与用户有没有改东西无关。
     *
     * 所以 `canUndo` **不能只看 cursor**，必须比较内容。
     * 不比较的表现是：**打开编辑页撤回按钮就亮着**，点下去什么都没变；
     * 只聚焦一下输入框（不打字）也会让它亮。
     */

    @Test
    fun `刚开步时撤回不该亮`() {
        val d = draft()

        // 模拟"聚焦输入框"——只开步，一个字都没打
        d.mutateOnce()

        assertFalse("开步不代表有改动，撤回必须还是灰的", d.canUndo)
    }

    @Test
    fun `开步之后真改了内容撤回才亮`() {
        val d = draft()

        d.mutateOnce()
        assertFalse(d.canUndo)

        d.assign { settings ->
            settings.copy(
                components = settings.components.map {
                    if (it is KeyComponent) it.copy(label = "改了") else it
                },
            )
        }

        assertTrue("确实改了内容，撤回应当可用", d.canUndo)
    }

    /**
     * 点撤回必须**真的看到变化**。
     *
     * 历史里会留下"内容与相邻格相同"的项，只退一格的话用户会觉得
     * "点了没反应"。所以 [CustomLayoutDraft.undo] 要跳过相同的格子。
     */
    @Test
    fun `撤回会跳过内容相同的格子`() {
        val d = draft()
        val original = keyOf(d).label

        // 开步但不改内容 → 历史里多了一格相同的
        d.mutateOnce()
        d.assign { settings ->
            settings.copy(
                components = settings.components.map {
                    if (it is KeyComponent) it.copy(label = "新文字") else it
                },
            )
        }
        // 故意**不调 finishStep**，模拟"连续操作还没收尾就点了撤回"
        d.undo()

        assertEquals("撤回必须回到真正不同的那一格", original, keyOf(d).label)
    }

    @Test
    fun `多次操作可以逐步撤回`() {
        val d = draft()
        val startX = keyOf(d).x

        drag(d, from = startX, steps = listOf(30f))
        drag(d, from = 30f, steps = listOf(60f))
        drag(d, from = 60f, steps = listOf(90f))
        assertEquals(90f, keyOf(d).x, 0.001f)

        d.undo()
        assertEquals(60f, keyOf(d).x, 0.001f)
        d.undo()
        assertEquals(30f, keyOf(d).x, 0.001f)
        d.undo()
        assertEquals(startX, keyOf(d).x, 0.001f)
        assertFalse(d.canUndo)
    }

    @Test
    fun `没造成变化的拖动不留下历史`() {
        val d = draft()
        val startX = keyOf(d).x

        // 点了一下但没真正移动
        drag(d, from = startX, steps = listOf(startX))

        assertFalse("原地不动不该能撤回", d.canUndo)
        assertEquals(1, d.historySize)
    }

    @Test
    fun `撤回之后再做新操作会丢掉恢复分支`() {
        val d = draft()
        val startX = keyOf(d).x

        drag(d, from = startX, steps = listOf(40f))
        drag(d, from = 40f, steps = listOf(80f))

        d.undo()
        assertTrue("撤回后应该有可恢复的分支", d.canRedo)

        // 在新的位置又拖了一次：原来那条"未来"分支不该再能恢复
        drag(d, from = 40f, steps = listOf(10f))
        assertFalse("做了新操作后不该还能恢复", d.canRedo)
        assertEquals(10f, keyOf(d).x, 0.001f)
    }

    /*
     * ============================================================
     * 组件级操作
     * ============================================================
     */

    @Test
    fun `添加组件可以撤回`() {
        val d = draft()
        val before = d.current.components.size

        d.add(
            KeyComponent(
                id = "new",
                x = 0f,
                y = 0f,
                width = 80f,
                height = 80f,
                style = ComponentStyle(),
                label = "A",
                inputKeyCodes = listOf(30),
            ),
        )
        assertEquals(before + 1, d.current.components.size)

        d.undo()
        assertEquals(before, d.current.components.size)
    }

    @Test
    fun `删除组件可以撤回`() {
        val d = draft()
        val target = keyOf(d).id

        d.remove(target)
        assertTrue(d.current.components.none { it.id == target })

        d.undo()
        assertTrue("撤回后组件应该回来", d.current.components.any { it.id == target })
    }

    @Test
    fun `按 id 替换不会动到别的组件`() {
        val d = draft()
        val key = keyOf(d)
        val other = d.current.components.first { it.id != key.id }

        d.replace(key.copy(label = "改过了"))

        assertEquals("改过了", keyOf(d).label)
        assertEquals(
            "别的组件不该被连带改掉",
            other,
            d.current.components.first { it.id == other.id },
        )
    }

    @Test
    fun `恢复默认布局可以撤回`() {
        val d = draft()
        d.remove(keyOf(d).id)
        val afterRemove = d.current.components.size

        d.resetToDefault()
        assertEquals(defaultCustomComponents().size, d.current.components.size)
        assertNotEquals(afterRemove, d.current.components.size)

        d.undo()
        assertEquals(afterRemove, d.current.components.size)
    }

    @Test
    fun `一步到位的改动会走 dirty 再走回干净`() {
        val d = draft()
        assertFalse(d.dirty)

        val original = d.current.components.first()
        /*
         * ⚠️ 要转成 [TextualComponent]:`withPrimaryText` 现在只接受**有文字**的组件
         * （摇杆没有文字）。默认布局第一个是按键组件，所以这个转换一定成立。
         */
        d.replace((original as TextualComponent).withPrimaryText("改过了"))
        assertTrue(d.dirty)

        // 改回来就不该还是 dirty —— 比较的是整份快照，不是"改过没有"的标志位
        d.replace(original)
        assertFalse("改回原值不该算未保存", d.dirty)
    }

    /*
     * ============================================================
     * 历史深度上限
     * ============================================================
     */

    @Test
    fun `历史不会无限增长`() {
        val d = draft()
        val max = CustomLayoutDraft.MAX_HISTORY

        repeat(max * 3) { index ->
            // 用"移动组件"当那个连续操作：一次拖动 = 开步 → 中途改 → 收尾
            d.mutateOnce()
            val component = d.current.components.first()
            d.replace(component.movedTo(x = (index % 50).toFloat(), y = component.y), asStep = false)
            d.finishStep()
        }

        assertTrue(
            "历史应被裁剪到上限附近，实际 ${d.historySize}",
            d.historySize <= max + 2,
        )
        assertTrue(
            "裁剪之后**必须仍然能撤回**（裁掉的应该是最旧的，不是刚压进去的锚点）：" +
                "size=${d.historySize} cursor=${d.historyCursor}",
            d.canUndo,
        )
    }

    @Test
    fun `一次连续操作只占一步历史`() {
        val d = draft()
        val before = d.historySize
        val start = d.current.components.first()

        // 模拟滑块：按下 → 拖过 20 个中间值 → 抬手
        d.mutateOnce()
        repeat(20) { step ->
            d.replace(
                d.current.components.first().movedTo(x = 20f + step, y = start.y),
                asStep = false,
            )
        }
        d.finishStep()

        assertEquals("一次拖动只该压一格历史", before + 1, d.historySize)

        d.undo()
        assertEquals("撤回要回到拖动前的位置", start.x, d.current.components.first().x, 0.001f)
    }
}
